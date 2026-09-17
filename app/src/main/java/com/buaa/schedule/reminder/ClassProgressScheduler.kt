package com.buaa.schedule.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.buaa.schedule.MainActivity
import com.buaa.schedule.data.repository.scheduleRepository
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.TimeSlotProfile
import com.buaa.schedule.domain.model.periodGapMinutesOf
import com.buaa.schedule.domain.model.periodLabel
import com.buaa.schedule.domain.model.startLocalDate
import com.buaa.schedule.domain.model.toPeriodSegments
import com.buaa.schedule.domain.model.toStartEndTimes
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 「课程进行中」常驻通知的调度。
 *
 * 在课程提醒（上课前）之外再加两个时刻：
 * - 上课铃：发一条 ongoing 进度通知（Android 16 上可被系统提升为实况窗/流体云）；
 * - 下课铃：撤掉该通知，并恢复勿扰状态。
 *
 * 排程本身只依赖 AlarmManager（不需要额外权限声明，也不会被任务面板看到）；
 * 上课铃触发后另会尝试拉起 [CourseFluidService] 作为实况载体，
 * 服务起不来时同 id 的普通常驻通知继续兜底。
 */
object ClassProgressScheduler {

    private const val TAG = "ClassProgressScheduler"

    data class ClassWindow(
        val courseId: Long,
        val courseName: String,
        val location: String?,
        val sectionText: String,
        val startMillis: Long,
        val endMillis: Long,
        /** 授课教师：同名课在不同班之间只能靠它区分（「下一节课」组件显示） */
        val teacher: String? = null,
    )

    // 动作串只认 [ClassProgressReceiver] 里的那一份（ACTION_START / ACTION_END）。
    // 这里曾自设过一对取值不同的私有常量：广播照排照发，接收器的 when 却永远落空，
    // 上课铃不静音、下课铃不恢复、课中实况也不发 —— 全程零报错。不要再写回私有副本。

    private const val REQUEST_START = 30_260_031
    private const val REQUEST_END = 30_260_032
    private const val REQUEST_ALARM_SHOW = 30_260_033

    /** 节次表缺下课时间时的兜底时长 */
    private const val DEFAULT_CLASS_MINUTES = 45L

    /**
     * 从课程列表里找「尚未结束的最早一次」上课窗口（开始→结束）。
     * 结束时间 = **该连续节次段**最后一节的下课时间。
     *
     * 按节次段而不是按整门课取窗口：`[1,2,9,10]` 的课此前会算出一个
     * 08:00→18:15 的窗口，常驻通知与上课勿扰把整个白天罩住（R5 F-30）。
     *
     * 注意包含**正在上课**的那一次：此前只找 `now` 之后的开始时间，
     * 于是"上课进行中"会跳到再下一节，与本类下课铃撤销本次通知的口径不一致。
     * 纯函数，可单测。
     */
    fun planNextClassWindow(
        courses: List<Course>,
        semesterStart: LocalDate,
        timeSlots: List<TimeSlot>,
        now: LocalDateTime,
    ): ClassWindow? {
        val slots = (if (timeSlots.isNotEmpty()) timeSlots else TimeSlotProfile.DEFAULT)
            .toStartEndTimes()
        val gapMinutes = periodGapMinutesOf(slots)
        val (course, window) = courses.asSequence()
            .mapNotNull { candidate ->
                nextOrCurrentWindow(candidate, semesterStart, slots, gapMinutes, now)
                    ?.let { candidate to it }
            }
            .filter { (_, window) -> window.end.isAfter(window.begin) }
            .minByOrNull { (_, window) -> window.begin }
            ?: return null
        val zone = ZoneId.systemDefault()
        return ClassWindow(
            courseId = course.id,
            // 别名优先（审查 3.1）：这个字段同时喂给「课程进行中」常驻通知与「下一节课」组件，
            // 用教务原名的话用户起的短名在这两处都不生效、长课名还会被截断。
            courseName = course.displayName,
            location = course.location,
            sectionText = periodLabel(window.segment),
            startMillis = window.begin.atZone(zone).toInstant().toEpochMilli(),
            endMillis = window.end.atZone(zone).toInstant().toEpochMilli(),
            teacher = course.teacher,
        )
    }

    /**
     * 该课程「尚未结束的最早一个节次段窗口」（含正在上的这次）；没有则返回 null。
     * 周次升序 × 段升序即时间升序，第一个未结束的窗口就是最近的一次。
     */
    private fun nextOrCurrentWindow(
        course: Course,
        semesterStart: LocalDate,
        slots: Map<Int, Pair<LocalTime, LocalTime>>,
        gapMinutes: (Int, Int) -> Long?,
        now: LocalDateTime,
    ): SegmentWindow? {
        val segments = course.periods.toPeriodSegments(gapMinutes)
        for (week in course.weeks.sorted()) {
            val date = semesterStart.plusWeeks((week - 1).toLong())
                .plusDays((course.dayOfWeek - 1).toLong())
            for (segment in segments) {
                // 缺节次时间时**不要**兜底成 08:00：那会凭空造出一个"从 8 点开始"的窗口，
                // 让某门没有任何时间信息的课在每天早上被判成"正在上课"，
                // 触发上课铃 + 常驻通知 + 勿扰。缺数据就该跳过这一段。
                val start = slots[segment.first]?.first ?: continue
                val begin = date.atTime(start)
                // 下课时间缺失时也不能丢掉整门课：节次表不完整（只缺最后节下班时间）的
                // 学期会被整门忽略，表现为"常驻通知突然不再出现"。按默认时长兜底继续算。
                val end = segmentEndTime(segment, date, slots)
                    ?: begin.plusMinutes(DEFAULT_CLASS_MINUTES)
                if (end.isAfter(now)) return SegmentWindow(begin, end, segment)
            }
        }
        return null
    }

    /** 段末下课时间：最后一节缺时间时退到段内更早的有时间的节，都没有返回 null */
    private fun segmentEndTime(
        segment: IntRange,
        date: LocalDate,
        slots: Map<Int, Pair<LocalTime, LocalTime>>,
    ): LocalDateTime? {
        val end = slots[segment.last]?.second
            ?: (segment.first until segment.last).toList().asReversed()
                .firstNotNullOfOrNull { slots[it]?.second }
            ?: return null
        return date.atTime(end)
    }

    /** 某个连续节次段的一次上课窗口 */
    private data class SegmentWindow(
        val begin: LocalDateTime,
        val end: LocalDateTime,
        val segment: IntRange,
    )

    /**
     * 按最新的「尚未结束的最早一次课」重排上/下课铃。
     *
     * 用 [planNextClassWindow] 而不是课前提醒计划里的那门课：提醒的触发时刻是
     * 「上课时间 − 提前量」，提前量大时最近的一节课会被判成"触发时间已过"而跳过，
     * 于是上课铃落到再下一节课 —— 与「下一节课」组件、常驻通知的口径不一致。
     *
     * 另外：当最近一次课**还没开始**时，说明此刻没有课在进行。若此时仍挂着上一次的常驻通知
     * 或勿扰（典型场景：用户把正在上 / 刚上完的那门课删了，重排后直接跳到下周），
     * 必须先收干净，否则那条通知会一直留到下一节课下课、勿扰也会一直关着。
     */
    fun rescheduleWindows(
        context: Context,
        courses: List<Course>,
        semesterStart: LocalDate,
        timeSlots: List<TimeSlot>,
    ) {
        // 先撤掉旧的两个闹钟，再按最新的「尚未结束的最早一次课」重排
        cancel(context)
        val window = planNextClassWindow(
            courses = courses,
            semesterStart = semesterStart,
            timeSlots = timeSlots,
            now = LocalDateTime.now(),
        )
        if (window == null) {
            cancelAll(context)
            return
        }
        if (window.startMillis > System.currentTimeMillis() &&
            // 课前倒计时挂的就是这节即将到来的课（同一通知 id）。
            // 此前这里无条件把"课还没开始"当成下课铃被吞的遗留收干净，
            // ReminderReceiver 自己触发的重排会在倒计时下发后一秒内把它拆掉 ——
            // 这正是"课中能上岛、课前倒计时上不了岛"剩下的那条机制级根因。
            // 课被删/时间被改时归属对不上，照常回收，R5 F-11 的清理语义不变。
            !ReminderNotifications.isCountingDownTo(window.courseId, window.startMillis)
        ) {
            CourseFluidService.stop(context)
            ReminderNotifications.cancelClassOngoing(context)
            ClassProgressDnd.restore(context)
        }
        schedule(context, window)
    }

    /**
     * 从数据库读最新课表，续排下一个课堂窗口。
     *
     * 独立于课前提醒开关：全部提醒关闭时 [ReminderScheduler.rescheduleAll] 走
     * "没有下一条提醒"分支直接收铃返回，下课铃若不自己续排，这类用户在第一节课之后
     * 就再也不会有课程实况与上课自动勿扰。两个开关都关时不武装任何闹钟。
     */
    suspend fun rescheduleNextWindow(context: Context) {
        runCatching {
            val prefs =
                context.getSharedPreferences(ClassProgressReceiver.PREFS_NAME, Context.MODE_PRIVATE)
            val classProgress = prefs.getBoolean(ClassProgressReceiver.PREF_CLASS_PROGRESS, true)
            val dndEnabled = prefs.getBoolean(ClassProgressReceiver.PREF_DND, false)
            if (!classProgress && !dndEnabled) return
            val repository = context.scheduleRepository()
            val semester = repository.getCurrentSemester() ?: return
            val semesterStart = semester.startLocalDate ?: return
            rescheduleWindows(
                context = context,
                courses = repository.getDisplayCourses(semester),
                semesterStart = semesterStart,
                timeSlots = repository.getTimeSlots(),
            )
        }.onFailure { Log.w(TAG, "下课后续排课堂窗口失败", it) }
    }

    fun schedule(context: Context, window: ClassWindow) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        scheduleClassStartBell(context, alarmManager, window.startMillis, startPendingIntent(context, window))
        scheduleExactOrFallback(alarmManager, window.endMillis, endPendingIntent(context, window))
    }

    /** 只排下课铃（上课铃触发后由接收器调用，撤常驻通知并恢复勿扰） */
    fun scheduleEnd(context: Context, endMillis: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        scheduleExactOrFallback(alarmManager, endMillis, endPendingIntent(context, emptyWindow()))
    }

    /**
     * 上课铃用 `setAlarmClock`（「用户闹钟」档）：Doze / 厂商省电白名单对它的豁免
     * 最彻底——系统把它当作用户亲自设定的闹钟对待，这正是「下一节课要上」的语义。
     * 代价是状态栏会出现闹钟图标，属预期信号；一次只排一个，量可控。
     * （跨 ROM 调研结论：拾光/Sleepy/SleepDown 的主链都没用它，这是我们的加强项。）
     *
     * 仍受 SCHEDULE_EXACT_ALARM 约束：未授权时降级 setAndAllowWhileIdle；
     * 个别 ROM 对 setAlarmClock 也抛异常时退回 setExactAndAllowWhileIdle。
     */
    private fun scheduleClassStartBell(
        context: Context,
        alarmManager: AlarmManager,
        atMillis: Long,
        pi: PendingIntent,
    ) {
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
        if (!canExact) {
            runCatching { alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi) }
            return
        }
        runCatching {
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, alarmShowIntent(context)), pi)
        }.recoverCatching {
            Log.w(TAG, "setAlarmClock 失败，退回精确档重排上课铃", it)
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
    }

    /** 闹钟图标点击时打开 App（setAlarmClock 的展示意图，固定复用一个） */
    private fun alarmShowIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_ALARM_SHOW,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * 精确闹钟降级：Android 12+ 的 SCHEDULE_EXACT_ALARM 是特殊权限
     * （Android 14+ 新装默认不授予），未授予时 `setExactAndAllowWhileIdle` 会抛
     * `SecurityException`。此前这里没做守卫，异常会从广播回调里逃出去 ——
     * 轻则上/下课铃静默失效，重则在 `ClassProgressReceiver` 里直接杀掉进程。
     * 与 [ReminderScheduler] 的处理保持一致。
     */
    private fun scheduleExactOrFallback(alarmManager: AlarmManager, atMillis: Long, pi: PendingIntent) {
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
        if (canExact) {
            // 部分 ROM 即使授权也抛 SecurityException（省电策略收紧），退到非精确档而不是让异常逃出广播
            runCatching {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            }.onFailure {
                Log.w(TAG, "精确下课铃排程失败，退回非精确档", it)
                runCatching { alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi) }
            }
        } else {
            runCatching { alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi) }
        }
    }

    /**
     * 上/下课铃是否还挂着。用 FLAG_NO_CREATE 查询，不会顺手创建 PendingIntent。
     * 供仪器化测试断言"清空课表后收铃"（R5 F-11）。
     */
    internal fun hasPendingClassBells(context: Context): Boolean =
        existingPendingIntent(context, REQUEST_START, ClassProgressReceiver.ACTION_START) != null ||
            existingPendingIntent(context, REQUEST_END, ClassProgressReceiver.ACTION_END) != null

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        // FLAG_NO_CREATE：只撤销“已存在”的 PendingIntent，
        // 避免每次重排提醒都新造两个永远不会使用的 PendingIntent。
        // 只 alarmManager.cancel 不够：PI 记录仍被应用侧的引用钉住，FLAG_NO_CREATE
        // 事后照样查得到（真机实测），于是"铃到底还挂不挂着"无法回答。
        // PendingIntent.cancel() 把记录本身摘掉；重排时 getBroadcast 会再造新的。
        existingPendingIntent(context, REQUEST_START, ClassProgressReceiver.ACTION_START)?.cancelWith(alarmManager)
        existingPendingIntent(context, REQUEST_END, ClassProgressReceiver.ACTION_END)?.cancelWith(alarmManager)
    }

    private fun PendingIntent.cancelWith(alarmManager: AlarmManager) {
        alarmManager.cancel(this)
        cancel()
    }

    /**
     * 完整撤除：上/下课铃 + 常驻通知 + 恢复勿扰状态。
     *
     * 用于"数据被清空""提醒模式切到系统日历""用户关掉本功能"等所有取消路径。
     * 只调 [cancel] 会留下两个后果：已发出的常驻通知永不消失、
     * 上课期间被改掉的勿扰状态永不恢复（连重启都不会自愈）。
     */
    fun cancelAll(context: Context) {
        cancel(context)
        CourseFluidService.stop(context)
        ReminderNotifications.cancelClassOngoing(context)
        ClassProgressDnd.restore(context)
    }

    private fun startPendingIntent(context: Context, window: ClassWindow): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQUEST_START,
            baseIntent(context).putExtras(window.toBundle(ClassProgressReceiver.ACTION_START)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun endPendingIntent(context: Context, window: ClassWindow): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQUEST_END,
            baseIntent(context).putExtras(window.toBundle(ClassProgressReceiver.ACTION_END)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun baseIntent(context: Context): Intent =
        Intent(context, ClassProgressReceiver::class.java)

    private fun existingPendingIntent(context: Context, requestCode: Int, action: String): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            baseIntent(context).putExtra(ClassProgressReceiver.EXTRA_ACTION, action),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun emptyWindow(): ClassWindow = ClassWindow(0L, "", null, "", 0L, 0L)

    private fun ClassWindow.toBundle(action: String): Bundle = Bundle().apply {
        putString(ClassProgressReceiver.EXTRA_ACTION, action)
        putLong(ClassProgressReceiver.EXTRA_COURSE_ID, courseId)
        putString(ClassProgressReceiver.EXTRA_COURSE_NAME, courseName)
        putString(ClassProgressReceiver.EXTRA_LOCATION, location)
        putString(ClassProgressReceiver.EXTRA_SECTION, sectionText)
        putLong(ClassProgressReceiver.EXTRA_START, startMillis)
        putLong(ClassProgressReceiver.EXTRA_END, endMillis)
    }
}
