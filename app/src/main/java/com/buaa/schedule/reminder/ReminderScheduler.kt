package com.buaa.schedule.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.compose.ui.graphics.toArgb
import com.buaa.schedule.core.designsystem.courseColor
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.ReminderSetting
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.TimeSlotProfile
import com.buaa.schedule.domain.model.periodGapMinutesOf
import com.buaa.schedule.domain.model.periodLabel
import com.buaa.schedule.domain.model.startLocalDate
import com.buaa.schedule.domain.model.toPeriodSegments
import com.buaa.schedule.domain.model.toStartEndTimes
import com.buaa.schedule.domain.schedule.CourseConstraints
import com.buaa.schedule.domain.schedule.toEpochMillis
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
        /** 本次提醒对应的那个**连续节次段**（跨午休的课一段一次），不是整门课的节次 */
        val segment: IntRange,
        val classStart: LocalDateTime,
        val advanceMinutes: Int,
        val triggerAtMillis: Long,
        /** 这一次上课落在第几周：课前实况的「第 N 周」取它，与 [segment] 同为"这一次"的属性 */
        val week: Int,
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
        // 一次重排只读一次时钟：planNextReminder 拿 `now` 判"这一段还没开始"、
        // 拿 `nowMillis` 判"触发时刻过了没有"，两次各读各的就会在跨秒那一刻自相矛盾
        // （表现为提前量刚好用尽的那节课被跳过，链条跳到下周）。
        val now = LocalDateTime.now()
        val plan = planNextReminder(
            courses = courses,
            semesterStart = semesterStart,
            timeSlots = timeSlots,
            reminders = reminders,
            now = now,
            nowMillis = now.toEpochMillis(),
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
        // 不判空会直接 NPE，而这条路径是从广播里调的，崩了就是"设置里点保存闪退"。
        // ⚠️ 但**不能早退**：拿不到 AlarmManager 只意味着"这一次课前闹钟排不上"，
        // 下面那段课堂铃的撤销/重排跟 AlarmManager 无关（它自己会判 null），
        // 早退会把清理块一起跳过，留下永不消失的常驻通知 + 永不恢复的勿扰。
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (alarmManager == null) {
            Log.w(TAG, "AlarmManager 不可用，跳过课前提醒排程（课堂铃清理照常执行）")
        } else {
            val pendingIntent = createPendingIntent(context, plan)
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
        }

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
        val slots = (if (timeSlots.isNotEmpty()) timeSlots else TimeSlotProfile.DEFAULT)
            .toStartEndTimes()
        val gapMinutes = periodGapMinutesOf(slots)
        return courses.asSequence()
            .mapNotNull { course ->
                val setting = reminders[course.id]
                if (setting != null && !setting.enabled) return@mapNotNull null
                val occurrence = nextOccurrence(course, semesterStart, slots, gapMinutes, now)
                    ?: return@mapNotNull null
                val advanceMinutes =
                    CourseConstraints.normalizeAdvanceMinutes(setting?.advanceMinutes ?: DEFAULT_ADVANCE_MINUTES)
                val triggerAt = occurrence.classStart.atZone(zone).toInstant().toEpochMilli() -
                    advanceMinutes * 60_000L
                if (triggerAt <= nowMillis) return@mapNotNull null
                ReminderPlan(
                    course = course,
                    segment = occurrence.segment,
                    classStart = occurrence.classStart,
                    advanceMinutes = advanceMinutes,
                    triggerAtMillis = triggerAt,
                    week = occurrence.week,
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

    private fun createPendingIntent(context: Context, plan: ReminderPlan): PendingIntent {
        val classStartMillis = plan.classStart
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // 课前这一段实况量的是"这段等待过去了多少"：收铃时刻就是上课时刻，
        // 所以 start/end 先都填上课时间，真正开跑时由接收器把 start 改成"此刻"。
        val window = ClassProgressScheduler.ClassWindow(
            courseId = plan.course.id,
            courseName = plan.course.displayName,
            location = plan.course.location,
            sectionText = periodLabel(plan.segment),
            startMillis = classStartMillis,
            endMillis = classStartMillis,
            // 以下四项是课前倒计时那条实况此前缺的全部信息：
            // 与上课铃共用一套 extras 序列化，才不会又只补齐其中一条链。
            teacher = plan.course.teacher,
            week = plan.week,
            dayOfWeek = plan.classStart.dayOfWeek.value,
            colorArgb = courseColor(plan.course).toArgb(),
        )
        val intent = baseIntent(context).apply { putExtras(window.toExtras()) }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 该课程「下一次将要开始」的那一段：周次升序 × 段升序即时间升序，取第一个晚于 now 的。
     *
     * 必须按**节次段**枚举，口径与 [ClassProgressScheduler.planNextClassWindow] 一致。
     * 此前它只取整门课第一节课的时间，于是 `[1,2,9,10]` 这种跨午休的课：上午那段一上课，
     * 本周就再没有"晚于 now 的第一节"，挑到的下一次直接跳到**下周**——下午那节的课前提醒
     * 整学期都不会发。节次文案同理，见 [createPendingIntent]。
     *
     * 缺时间表时跳过该段，不兜底成 08:00：那会凭空造出一节 8 点的课，
     * 让一门没有任何时间信息的课每天早上被提醒一次（与课堂窗口同一取舍）。
     */
    private fun nextOccurrence(
        course: Course,
        semesterStart: LocalDate,
        slots: Map<Int, Pair<LocalTime, LocalTime>>,
        gapMinutes: (Int, Int) -> Long?,
        now: LocalDateTime,
    ): Occurrence? {
        val segments = course.periods.toPeriodSegments(gapMinutes)
        for (week in course.weeks.sorted()) {
            val date = semesterStart.plusWeeks((week - 1).toLong())
                .plusDays((course.dayOfWeek - 1).toLong())
            for (segment in segments) {
                val start = slots[segment.first]?.first ?: continue
                val dateTime = date.atTime(start)
                if (dateTime.isAfter(now)) return Occurrence(segment, dateTime, week)
            }
        }
        return null
    }

    /** 一次未来上课的开始：节次段 + 时刻 + 教学周 */
    private data class Occurrence(
        val segment: IntRange,
        val classStart: LocalDateTime,
        val week: Int,
    )

}
