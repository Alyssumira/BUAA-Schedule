package com.buaa.schedule.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.ReminderSetting
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.TimeSlotProfile
import com.buaa.schedule.domain.model.periodLabel
import com.buaa.schedule.domain.model.startLocalDate
import com.buaa.schedule.domain.schedule.CourseConstraints
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 课程提醒调度器。
 *
 * 为每门启用提醒的课程计算“触发时间 = 下次上课时间 - 提前量”，
 * 从中选出最近的未来一次设置闹钟；课程表变化后重新调用 rescheduleAll 即可。
 */
object ReminderScheduler {

    private const val TAG = "ReminderScheduler"
    private const val DEFAULT_ADVANCE_MINUTES = 10

    data class ReminderPlan(
        val course: Course,
        val classStart: LocalDateTime,
        val advanceMinutes: Int,
        val triggerAtMillis: Long,
    )

    fun rescheduleAll(
        context: Context,
        courses: List<Course>,
        semester: Semester?,
        timeSlots: List<TimeSlot>,
        reminders: Map<Long, ReminderSetting> = emptyMap(),
    ) {
        val semesterStart = semester?.startLocalDate
        if (semesterStart == null || courses.isEmpty()) {
            cancelAll(context)
            // 只撤课前提醒还不够：上下课铃、常驻通知与勿扰都得收干净，
            // 否则"上课中途清空课表"会留下永久勿扰 + 一条滑不掉的常驻通知（与下方 plan==null 同口径）
            ClassProgressScheduler.cancelAll(context)
            return
        }
        val plan = planNextReminder(
            courses = courses,
            semesterStart = semesterStart,
            timeSlots = timeSlots,
            reminders = reminders,
            now = LocalDateTime.now(),
            nowMillis = System.currentTimeMillis(),
        )
        cancelAll(context)
        if (plan == null) {
            // 没有下一条提醒（课表被清空 / 学期已结束 / 全部提醒被关）：
            // 必须把上/下课铃、常驻通知、勿扰状态一并收干净，
            // 否则上课期间清空课表会留下"永不消失的常驻通知 + 永久勿扰"。
            ClassProgressScheduler.cancelAll(context)
            return
        }
        // 上/下课铃与常驻通知的撤销/重排统一交给 scheduleClassProgress：
        // 它会先撤掉旧的两个闹钟，再按最新的「尚未结束的最早一次课」重排，
        // 并在没有课在进行时把遗留的常驻通知与勿扰收干净。

        // getSystemService(Class) 在极端情况下返回 null（系统服务未就绪/定制 ROM），
        // 不判空会直接 NPE，而这条路径是从广播里调的，崩了就是"设置里点保存闪退"
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val classStartMillis = plan.classStart
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pendingIntent = createPendingIntent(context, plan.course, classStartMillis)
        // Android 12+ 精确闹钟是特殊权限（默认拒绝），未授予时降级为不精确闹钟，避免崩溃。
        // canScheduleExactAlarms() 本身也可能抛 SecurityException（权限被运行期撤销），
        // 因此整段调度都套上 runCatching 兜底，失败只是这一次不精确，不影响其余链路。
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
        runCatching {
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.triggerAtMillis, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.triggerAtMillis, pendingIntent)
            }
        }.onFailure { Log.w(TAG, "调度课程提醒失败", it) }

        // 上/下课铃与常驻通知（"课程进行中"）和「上课自动勿扰」是两个独立开关，
        // 但共用同一对上课/下课闹钟：任一开启都必须排铃，接收器里再按开关决定做什么。
        // 此前任一开关关闭就直接不排铃，导致"只开勿扰、关掉常驻通知"的用户整条链路
        // 都不跑 —— 表现为上课自动勿扰"好像没用"。
        val prefs = context.getSharedPreferences(ClassProgressReceiver.PREFS_NAME, Context.MODE_PRIVATE)
        val classProgress = prefs.getBoolean(ClassProgressReceiver.PREF_CLASS_PROGRESS, true)
        val dndEnabled = prefs.getBoolean(ClassProgressReceiver.PREF_DND, false)
        if (classProgress || dndEnabled) {
            // 口径与下课铃广播共用 ClassProgressScheduler.rescheduleWindows
            ClassProgressScheduler.rescheduleWindows(context, courses, semesterStart, timeSlots)
        } else {
            // 两个开关都关了：连常驻通知 / 勿扰遗留状态一起收干净，
            // 别留着一条不会再更新的通知或一个永不恢复的勿扰
            ClassProgressScheduler.cancelAll(context)
        }
    }

    /**
     * 纯函数：挑选下一个应触发的提醒。
     *
     * - 按“触发时间”（上课时间 - 提前量）排序，而不是上课时间本身——
     *   不同课程提前量不同时，触发顺序和上课顺序可能不一致；
     * - 触发时间已过的候选直接跳过（哪怕课还没上），不补发陈旧提醒。
     *   这样上一次提醒触发后重新调度，会正确落到下一门课，链条不会中断。
     */
    fun planNextReminder(
        courses: List<Course>,
        semesterStart: LocalDate,
        timeSlots: List<TimeSlot>,
        reminders: Map<Long, ReminderSetting>,
        now: LocalDateTime,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ReminderPlan? {
        val slots = if (timeSlots.isNotEmpty()) timeSlots else TimeSlotProfile.DEFAULT
        return courses.asSequence()
            .mapNotNull { course ->
                val setting = reminders[course.id]
                if (setting != null && !setting.enabled) return@mapNotNull null
                val occurrence = nextOccurrence(course, semesterStart, slots, now)
                    ?: return@mapNotNull null
                val advanceMinutes =
                    CourseConstraints.normalizeAdvanceMinutes(setting?.advanceMinutes ?: DEFAULT_ADVANCE_MINUTES)
                val triggerAt = occurrence.atZone(zone).toInstant().toEpochMilli() -
                    advanceMinutes * 60_000L
                if (triggerAt <= nowMillis) return@mapNotNull null
                ReminderPlan(
                    course = course,
                    classStart = occurrence,
                    advanceMinutes = advanceMinutes,
                    triggerAtMillis = triggerAt,
                )
            }
            .minByOrNull { it.triggerAtMillis }
    }

    fun cancelAll(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        // FLAG_NO_CREATE：只取"已存在"的那个来撤销。
        // 之前用 FLAG_UPDATE_CURRENT，等于每次取消都新建一个永不使用的 PendingIntent。
        // （PendingIntent 判等基于 Intent.filterEquals，不含 extras，因此这里不需要拼 extras）
        val existing = PendingIntent.getBroadcast(
            context,
            0,
            baseIntent(context),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager.cancel(existing)
    }

    private fun baseIntent(context: Context): Intent = Intent(context, ReminderReceiver::class.java)

    private fun createPendingIntent(context: Context, course: Course, classStartMillis: Long): PendingIntent {
        val intent = baseIntent(context).apply {
            putExtra(ReminderReceiver.EXTRA_COURSE_ID, course.id)
            putExtra(ReminderReceiver.EXTRA_COURSE_NAME, course.name)
            putExtra(ReminderReceiver.EXTRA_LOCATION, course.location)
            putExtra(ReminderReceiver.EXTRA_SECTION, periodLabel(course.periods))
            putExtra(ReminderReceiver.EXTRA_CLASS_START_AT, classStartMillis)
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun nextOccurrence(
        course: Course,
        semesterStart: LocalDate,
        timeSlots: List<TimeSlot>,
        now: LocalDateTime,
    ): LocalDateTime? {
        val firstPeriod = course.startPeriod
        val startTime = timeSlots.firstOrNull { it.number == firstPeriod }?.startTime ?: "08:00"
        val start = runCatching { LocalTime.parse(startTime) }.getOrDefault(LocalTime.of(8, 0))

        for (week in course.weeks.sorted()) {
            val date = semesterStart.plusWeeks((week - 1).toLong())
                .plusDays((course.dayOfWeek - 1).toLong())
            val dateTime = date.atTime(start)
            if (dateTime.isAfter(now)) {
                return dateTime
            }
        }
        return null
    }

}
