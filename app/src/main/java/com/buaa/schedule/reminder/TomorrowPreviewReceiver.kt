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
import com.buaa.schedule.domain.model.periodLabel
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
                postTomorrowPreviewIfAny(context)
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
        // 无论有没有课，都把下一天 22:00 的预告排上
        runCatching { TomorrowPreviewScheduler.schedule(context) }.onFailure {
            Log.w(TAG, "排下一天明日预告失败", it)
        }
    }

    private suspend fun postTomorrowPreviewIfAny(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_ENABLED, true)) return

        val repository = ScheduleRepository(AppDatabase.getInstance(context))
        val semester = repository.getCurrentSemester()
        val courses = repository.getDisplayCourses(semester)
        val timeSlots = repository.getTimeSlots()
        val tomorrow = LocalDate.now().plusDays(1)

        val week = semester?.startLocalDate?.let {
            WeekCalculator.currentWeekOrNull(it, semester.totalWeeks, tomorrow)
        }
        val tomorrowCourses = tomorrowPreviewCourses(courses, semester, week, tomorrow)
        // 有课才推（KDoc 与设置页文案的口径）：空列表发出去就是周末/假期每晚一条"明天没课"
        if (tomorrowCourses.isEmpty()) return
        ReminderNotifications.postTomorrowPreview(context, tomorrowCourses, tomorrow, timeSlots)
    }

    companion object {
        private const val TAG = "TomorrowPreviewReceiver"

        const val PREFS_NAME = "schedule_settings"
        const val PREF_ENABLED = "tomorrow_preview_enabled"
        const val ACTION_FIRE = "com.buaa.schedule.reminder.ACTION_TOMORROW_PREVIEW"

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

    fun schedule(context: Context) {
        val next = nextFireTime(LocalDateTime.now())
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

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
    }

    /** 下一个 22:00（已过今天 22:00 则取明天的） */
    internal fun nextFireTime(now: LocalDateTime): Long {
        var next = now.toLocalDate().atTime(LocalTime.of(FIRE_HOUR, 0))
        if (!next.isAfter(now)) next = next.plusDays(1)
        return next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

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
