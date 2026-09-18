package com.buaa.schedule.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.buaa.schedule.R
import com.buaa.schedule.data.local.AppDatabase
import com.buaa.schedule.data.repository.ScheduleRepository
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.startLocalDate
import com.buaa.schedule.domain.schedule.WeekCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 明日课程预告：每天 22:00 推送第二天的课程概览。
 *
 * 设计取舍：
 * - 默认开启（用户可在设置里关），时间暂固定 22:00，不做成可配（避免 UI 复杂化）；
 * - 有课才推：明天没课 / 假期中不推，避免无效打扰；
 * - 推送后自动排下一天 22:00，链条不断；设备重启由 BootReceiver 全家桶兜底重排。
 */
class TomorrowPreviewReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 与 ReminderReceiver/WidgetRefreshReceiver 同口径：goAsync 只保证进程存活、
                // 不保证 CPU 醒着，22:00 前后正处 Doze 窗口，中途睡回去这条预告就凭空消失。
                WakeLocks.withPartialWakeLock(context, "tomorrow_preview") {
                // 续排也在里面做：它需要"往后第一个上课日"这个答案，而这个答案只能
                // 从已经读出的课表里来（见 postTomorrowPreviewIfAny）。
                postTomorrowPreviewIfAny(context)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 协程取消是控制流信号，必须继续向上传播，不能吞掉
                throw e
            } catch (e: Exception) {
                // goAsync 广播里的未捕获异常会杀死进程；这里兜底并留痕
                Log.w(TAG, "明日课程预告处理失败", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun postTomorrowPreviewIfAny(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_ENABLED, true)) return

        val repository = ScheduleRepository(AppDatabase.getInstance(context))
        val semester = repository.getCurrentSemester()
        val courses = repository.getDisplayCourses(semester)
        val timeSlots = repository.getTimeSlots()
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)

        val semesterStart = semester?.startLocalDate
        val week = semesterStart?.let {
            WeekCalculator.currentWeekOrNull(it, semester.totalWeeks, tomorrow)
        }
        val tomorrowCourses = tomorrowPreviewCourses(courses, semester, week, tomorrow)
        // 有课才推（KDoc 与设置页文案的口径）：空列表发出去就是周末/假期每晚一条"明天没课"
        if (tomorrowCourses.isNotEmpty()) {
            ReminderNotifications.postTomorrowPreview(context, tomorrowCourses, tomorrow, timeSlots)
        }

        // 把下一次预告排到「往后第一个『明天有课』的 22:00」，而不是机械地 +1 天：
        // 周末、假期、学期结束后根本没有可推的内容，那一晚的精确闹钟是纯白醒
        // （还要查三张表）。找不到这样的日子就不再续排 —— 链条停下，等
        // 改课表 / 开机 / 改时间这些事件重新对齐（BootReceiver 与
        // WidgetRefreshReceiver 都会调 BackgroundSync.scheduleTomorrowPreview）。
        val candidate = nextPreviewDay(courses, semester, today)
        if (candidate != null) {
            // ⚠️ 复核的是**真正会响的那一天**：22:00 已过时候选日会被顺延一天（闹钟排在
            // 过去永远不会响，链条会当场断），而顺延出来的那一天从没参与过上面的搜索 ——
            // 学期最后一节课的预告之后那一晚就是这么空转醒一次的。
            val fireDay = TomorrowPreviewScheduler.fireDay(candidate)
            if (hasPreviewContentOn(fireDay, courses, semester)) {
                runCatching { TomorrowPreviewScheduler.schedule(context, fireDay) }
                    .onFailure { Log.w(TAG, "续排明日预告失败", it) }
            } else {
                Log.d(TAG, "$fireDay 的次日已没有课可推，明日预告链在此停下")
            }
        }
    }

    companion object {
        private const val TAG = "TomorrowPreviewReceiver"

        const val PREFS_NAME = "schedule_settings"
        const val PREF_ENABLED = "tomorrow_preview_enabled"
        const val ACTION_FIRE = "com.buaa.schedule.reminder.ACTION_TOMORROW_PREVIEW"

        /** 预告最多往后找多少天：学期长度 25 周，取一个明显够用的上界即可，防止死循环 */
        private const val MAX_SEARCH_DAYS = 120

        /**
         * 这一天 22:00 有没有东西可推：预告推的是**次日**的课，所以判据是
         * 「次日落在教学周内且有课」。
         *
         * [nextPreviewDay] 的循环体与续排前的复核用的是同一条判定，必须只有一份实现 ——
         * 各写一遍时，漏掉"顺延后的那一天"这类边界的代价是设备每晚白醒一次。
         */
        internal fun hasPreviewContentOn(
            previewDay: LocalDate,
            courses: List<Course>,
            semester: Semester?,
        ): Boolean {
            val term = semester ?: return false
            val start = term.startLocalDate ?: return false
            val target = previewDay.plusDays(1)
            val week = WeekCalculator.currentWeekOrNull(start, term.totalWeeks, target)
                ?: return false
            return courses.any { it.dayOfWeek == target.dayOfWeek.value && it.weeks.contains(week) }
        }

        /**
         * 下一个"值得在 22:00 醒来推预告"的日期：该日的**次日**落在教学周内且有课。
         * 找不到（学期结束 / 课表为空）返回 null。
         */
        internal fun nextPreviewDay(
            courses: List<Course>,
            semester: Semester?,
            from: LocalDate,
        ): LocalDate? {
            if (semester?.startLocalDate == null) return null
            for (offset in 0L..MAX_SEARCH_DAYS.toLong()) {
                val dayBefore = from.plusDays(offset)
                if (hasPreviewContentOn(dayBefore, courses, semester)) return dayBefore
            }
            return null
        }

        /**
         * 明天要推的课程：没有学期、或明天落在教学周之外（假期中）时**必须为空** ——
         * 空列表一旦推出去，就是周末/假期每晚一条"明天没课"（R5 F-14）。
         */
        internal fun tomorrowPreviewCourses(
            courses: List<Course>,
            semester: Semester?,
            week: Int?,
            tomorrow: LocalDate,
        ): List<Course> {
            if (semester == null || week == null) return emptyList()
            val day = tomorrow.dayOfWeek.value
            return courses.filter { it.dayOfWeek == day && it.weeks.contains(week) }
        }
    }
}

/** 明日预告的 22:00 闹钟调度（与零点 Widget 刷新同一套 AlarmManager 模式） */
object TomorrowPreviewScheduler {

    private const val TAG = "TomorrowPreviewScheduler"
    private const val REQUEST_CODE = 20_260_022
    private const val FIRE_HOUR = 22

    /**
     * 在 [previewDay] 那一天的 22:00 触发预告。
     * 已经过去的时间点会被推到该日的次日，保证排上去的一定是未来时刻。
     */
    fun schedule(context: Context, previewDay: LocalDate) {
        val next = nextFireTime(previewDay)
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
        val operation = pendingIntent(context)
        runCatching {
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, operation)
            } else {
                // 未授予精确闹钟权限时降级为不精确，可能晚几分钟，可接受
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, operation)
            }
        }.onFailure {
            Log.w(TAG, "明日预告闹钟调度失败", it)
        }
    }

    /**
     * 事件入口用的默认对齐：按"明天 22:00"排。
     * 只有真正读过课表的那条路（[TomorrowPreviewReceiver]）才知道下一个值得醒的日子。
     */
    fun schedule(context: Context) = schedule(context, LocalDate.now().plusDays(1))

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
    }

    /**
     * [previewDay] 当天 22:00 若已过则顺延到次日 —— 返回**闹钟真正会响的那一天**。
     *
     * 抽出来是为了让调用方看得见这次顺延：[TomorrowPreviewReceiver] 搜索到的是
     * "下一个明天有课的日子"，顺延出来的那一天并没有参与搜索，
     * 不复核就照排等于在学期结束后多排一次必空转的闹钟。
     */
    internal fun fireDay(previewDay: LocalDate, now: LocalDateTime = LocalDateTime.now()): LocalDate {
        val cutoff = previewDay.atTime(LocalTime.of(FIRE_HOUR, 0))
        return if (cutoff.isAfter(now)) previewDay else previewDay.plusDays(1)
    }

    /** [previewDay] 当天的 22:00；已过则顺延一天（排在过去的闹钟永远不会响，链条会当场断） */
    internal fun nextFireTime(previewDay: LocalDate, now: LocalDateTime = LocalDateTime.now()): Long =
        fireDay(previewDay, now).atTime(LocalTime.of(FIRE_HOUR, 0))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** 旧口径：以「今天」为预告日（22:00 前落今天、之后落明天），纯函数测试钉的就是这条 */
    internal fun nextFireTime(now: LocalDateTime): Long =
        nextFireTime(now.toLocalDate(), now)

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, TomorrowPreviewReceiver::class.java).apply {
            action = TomorrowPreviewReceiver.ACTION_FIRE
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
