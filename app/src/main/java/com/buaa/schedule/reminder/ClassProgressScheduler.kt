package com.buaa.schedule.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.compose.ui.graphics.toArgb
import com.buaa.schedule.MainActivity
import com.buaa.schedule.core.designsystem.courseColor
import com.buaa.schedule.data.repository.scheduleRepository
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.TimeSlotProfile
import com.buaa.schedule.domain.model.periodGapMinutesOf
import com.buaa.schedule.domain.model.periodLabel
import com.buaa.schedule.domain.model.startLocalDate
import com.buaa.schedule.domain.model.toPeriodSegments
import com.buaa.schedule.domain.model.toStartEndTimes
import com.buaa.schedule.domain.schedule.ChainReadSnapshot
import com.buaa.schedule.domain.schedule.WeekdayDateOffset
import com.buaa.schedule.domain.schedule.WeekdayOffset
import com.buaa.schedule.domain.schedule.periodWindowsOf
import com.buaa.schedule.domain.schedule.toEpochMillis
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * [ClassProgressScheduler.rescheduleNextWindow] 的默认失败留痕口：什么都不做。
 *
 * 形状照 `widget.BackgroundSync.NO_STEP_FAILURE`，但**不复用那一份**：`internal` 同模块确实
 * 调得到，可依赖方向是 widget → reminder（`BackgroundSync` 正是那一步的调用方之一），
 * 让被调方去 import 调用方的常量，等于给这条已经缠得够紧的链再加一根反向的线。
 * 与那一份同样的理由写成顶层属性而不是签名里的 lambda：本仓库的源码形状守卫按
 * "函数体第一个 `{`"切函数体，签名里出现 lambda 它会切到默认值上去
 * （原话见 `BackgroundSync.kt` 里 `NO_STEP_FAILURE` 的注释）。
 */
private val NO_RESCHEDULE_STEP_FAILURE: (label: String, error: Throwable) -> Unit = { _, _ -> }

/**
 * 续排失败的那句 `Log.w` —— 原样搬来，tag 与文案一字未改（审计 §4.4 的取证过滤条件不许变），
 * 只是挪成了 [ClassProgressScheduler.rescheduleNextWindow] 编排本体的日志钩子默认值。
 *
 * 为什么非挪不可：`android.util.Log` 在本模块 JVM 单测里是抛 "not mocked" 的桩，
 * 留在 `onFailure { }` 里第一句就报告口根本轮不到被调用，那条"失败要能被闸门看见"的断言
 * 会红得看不出原因（同 `ColdStartRebuild` 顶部那两个 `LOG_*` 属性，理由一字不差）。
 */
private val LOG_RESCHEDULE_WINDOW_FAILURE: (Throwable) -> Unit = {
    Log.w("ClassProgressScheduler", "下课后续排课堂窗口失败", it)
}

/**
 * 排课堂铃失败的留痕口（生产那一份）。
 *
 * 级别只许 `Log.w` 以上：这台机器上的 HyperOS 把 logcat 截在 Info，`Log.d` 写下去等于没写，
 * 而课堂铃失败的后果是**勿扰挂着不解除**（#16 / T14 那本账），这种事必须留得下痕迹。
 *
 * 与 [LOG_RESCHEDULE_WINDOW_FAILURE] 同理收成参数而不是写死在函数体里：`android.util.Log`
 * 在本模块 JVM 单测里是抛 "not mocked" 的桩，写死就先崩在报告口之前，那条"失败要能被看见"
 * 的断言会红得看不出原因。失败原因（哪一枚铃、哪一档）由调用点给，tag 与级别只此一份。
 */
private val LOG_BELL_ARM_FAILURE: (reason: String, error: Throwable) -> Unit = { reason, error ->
    Log.w("ClassProgressScheduler", reason, error)
}

/**
 * 「这一周在不在学期内」那枚判据的**旧名字**（T222，#306 + #307）。
 *
 * 判据本体已由这张卡从本文件搬进 `domain/schedule/TermWeekGate.kt` —— 搬的理由是"并尺"：
 * 冲突判定（`domain/schedule/ConflictDetector.kt`）与三条导出链
 * （`ScheduleOccurrences.build` 喂 ICS 与系统日历、`ScheduleExporters.toWakeUpJson` 喂 WakeUp）
 * 都在 domain 里，让它们去 import `reminder/` 那一枚 = 让 domain 反向依赖排铃侧，
 * 而排铃侧本来就 import domain 的 [com.buaa.schedule.domain.schedule.WeekCalculator] 与
 * [com.buaa.schedule.domain.schedule.SemesterWeekDates]。
 *
 * **别名不是第二把尺**：这一行不承载任何判据文本，编译期直接折成 domain 那一枚 `object`，
 * 两条排铃链里那道判据语句（`if (!TermWeekGate.isInTermWeek(week, semesterStart, totalWeeks)) continue`）
 * 逐字节一字未改 —— 这正是卡面要的"搬家后 `reminder/` 侧零行为改动"的证据，
 * 守卫 `reminder/TermWeekGateSingleSourceGuardTest` 第 ⑦ 层按别名目标的全限定名钉住它，
 * 再数"全 `app/src/main` 里 `object TermWeekGate` 恰一处、且长在 domain"。
 *
 * 为什么用别名而不是给 `ReminderScheduler.kt` 补一行 import：那张卡的白名单只给了本文件，
 * 越界改它比留一行别名更贵（别名与本文件同包 ⇒ 那一侧那句 `TermWeekGate.isInTermWeek(...)`
 * 不补 import 也照样解析到 domain 那一枚，一根线都没多）。
 */
typealias TermWeekGate = com.buaa.schedule.domain.schedule.TermWeekGate
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

    // 日志 tag 的字面量写在文件顶层那两枚留痕口里（`LOG_RESCHEDULE_WINDOW_FAILURE` 与
    // `LOG_BELL_ARM_FAILURE`）：它们是顶层属性，看不见 object 内的私有常量，
    // 所以这里曾有一枚 `private const val TAG` 跟着排铃的日志一起变成死码。
    // `logcat -d -s ClassProgressScheduler` 的过滤条件不变，仍是同一句字面量。

    data class ClassWindow(
        val courseId: Long,
        val courseName: String,
        val location: String?,
        val sectionText: String,
        val startMillis: Long,
        val endMillis: Long,
        /** 授课教师：同名课在不同班之间只能靠它区分（「下一节课」组件显示） */
        val teacher: String? = null,
        /** 这一次上课所在的教学周（1 起）：实况卡片与组件的「第 N 周」都取它，没有学期时为 null */
        val week: Int? = null,
        /** 星期几（1=周一…7=周日）：与 [week] 一起定位"这是哪一天的一节课" */
        val dayOfWeek: Int? = null,
        /** 课程色（ARGB）：实况通知的着色、岛上高亮色、组件色条共用，与课表卡片同一口径 */
        val colorArgb: Int? = null,
    ) {

        /** 写进任意 Bundle 的 extras：上/下课铃、课前提醒、前台服务共用这一份实现 */
        fun putInto(target: Bundle) = target.apply {
            putLong(KEY_ID, courseId)
            putString(KEY_NAME, courseName)
            putString(KEY_LOCATION, location)
            putString(KEY_SECTION, sectionText)
            putLong(KEY_START, startMillis)
            putLong(KEY_END, endMillis)
            putString(KEY_TEACHER, teacher)
            putInt(KEY_WEEK, week ?: NO_VALUE)
            putInt(KEY_DAY, dayOfWeek ?: NO_VALUE)
            putInt(KEY_COLOR, colorArgb ?: NO_COLOR)
        }

        /** [putInto] 的独立 Bundle 版：Intent 只接受 `putExtras(Bundle)` */
        fun toExtras(): Bundle = putInto(Bundle())

        // ---- 「这个窗口相对此刻是什么状态」的唯一实现 ----
        // 四个消费方此前各写一遍比较式（组件 `nextWidgetInClass`、实况服务的自停判据、
        // LiveClassResyncer.decide、rescheduleWindows 的遗留清理分支），
        // 边界口径靠"四处恰好都写对"维持。收敛后它们差的只是**谁在什么时候被叫醒**：
        // 闹钟投递、下课铃广播、进前台校准、组件重绘，算法只此一份。
        // 口径：开课那一秒起算进行中，下课那一秒起算已结束（end 取开区间）。

        /** 此刻是否正在上这一节（含开课那一秒，不含下课那一秒） */
        fun ongoingAt(nowMillis: Long = System.currentTimeMillis()): Boolean =
            startMillis <= nowMillis && nowMillis < endMillis

        /** 此刻这一节是否还没开始（含 end 缺失的 0 值窗口：那不算"正在上"） */
        fun startsAfter(nowMillis: Long = System.currentTimeMillis()): Boolean =
            startMillis > nowMillis

        /** 此刻这一节是否已经结束 */
        fun endedAt(nowMillis: Long = System.currentTimeMillis()): Boolean =
            nowMillis >= endMillis

        companion object {
            /**
             * 一条课堂链路上有四个进程边界要传这个窗口（下课铃、上课铃、课前提醒、前台服务）。
             * 此前每个边界各写一份 `putExtra`，键名还各起一套 —— 加字段时漏掉哪一条就只有
             * 那一条不显示，而且零报错。教师就是这样在整个实况链路上丢了很久：
             * [planNextClassWindow] 早就算好了它，只有「下一节课」组件读得到。
             *
             * 键取的是原 `ClassProgressReceiver.EXTRA_*` 的同名字符串，
             * 因此升级后仍在系统里的旧 PendingIntent（闹钟已排出的那一节课）照旧读得出。
             */
            private const val KEY_ID = "extra_course_id"
            private const val KEY_NAME = "extra_course_name"
            private const val KEY_LOCATION = "extra_location"
            private const val KEY_SECTION = "extra_section"
            private const val KEY_START = "extra_start"
            private const val KEY_END = "extra_end"
            private const val KEY_TEACHER = "extra_teacher"
            private const val KEY_WEEK = "extra_week"
            private const val KEY_DAY = "extra_day_of_week"
            private const val KEY_COLOR = "extra_color"

            /** 可空 Int 的哨兵：-1 表示"这项没有"（0 是个真实的周次/星期） */
            private const val NO_VALUE = -1

            /** 颜色为 0 即全透明，等价于"没着色"，与 null 同一含义 */
            private const val NO_COLOR = 0

            /**
             * 覆盖安装兼容：旧版本的课前提醒闹钟（那时 [ReminderScheduler] 自己排铃、
             * 且课前那一段的"结束时刻"就是上课时刻）把时刻写在 `extra_class_start_at` 下，
             * 键名与 [KEY_END] 不同。系统里那些已排出、还没响的闹钟改不了，
             * 升级后读不到新键就得回退读旧键，否则那一节课的实况/勿扰会拿到一个 0 时刻。
             */
            private const val LEGACY_KEY_CLASS_START_AT = "extra_class_start_at"

            /** [putInto] 的逆运算；缺键一律按"没有这项"处理，不抛异常 */
            fun from(extras: Bundle?): ClassWindow {
                val e = extras ?: Bundle()
                return ClassWindow(
                    courseId = e.getLong(KEY_ID, 0L),
                    courseName = e.getString(KEY_NAME) ?: "课程",
                    location = e.getString(KEY_LOCATION)?.takeIf { it.isNotBlank() },
                    sectionText = e.getString(KEY_SECTION) ?: "",
                    startMillis = e.getLong(KEY_START, 0L),
                    // 0 = 新键缺失，回退旧键；两个键都没有时保持 0（消费侧按"没有这项"处理）
                    endMillis = e.getLong(KEY_END, 0L).takeIf { it != 0L }
                        ?: e.getLong(LEGACY_KEY_CLASS_START_AT, 0L),
                    teacher = e.getString(KEY_TEACHER)?.takeIf { it.isNotBlank() },
                    week = e.getInt(KEY_WEEK, NO_VALUE).takeIf { it != NO_VALUE },
                    dayOfWeek = e.getInt(KEY_DAY, NO_VALUE).takeIf { it != NO_VALUE },
                    colorArgb = e.getInt(KEY_COLOR, NO_COLOR).takeIf { it != NO_COLOR },
                )
            }
        }
    }

    // 动作串只认 [ClassProgressReceiver] 里的那一份（ACTION_START / ACTION_END）。
    // 这里曾自设过一对取值不同的私有常量：广播照排照发，接收器的 when 却永远落空，
    // 上课铃不静音、下课铃不恢复、课中实况也不发 —— 全程零报错。不要再写回私有副本。

    private const val REQUEST_START = 30_260_031
    private const val REQUEST_END = 30_260_032
    private const val REQUEST_ALARM_SHOW = 30_260_033

    // 缺下课时间时的单节兜底时长只在 PeriodWindows.DEFAULT_CLASS_MINUTES 有一份：
    // 这里曾自设过一个同值的私有常量，改名/调值时另一处不会跟着动。

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
     *
     * @param totalWeeks 学期总周数，喂给 [TermWeekGate.isInTermWeek]：**`reminder/` 目录下每一枚
     *   调用点都必须显式递它**（课堂铃链 `rescheduleWindows`、勿扰判据
     *   `shouldTakeDownClassProgress`、前台校准 `LiveClassResyncer.nextWindow` 三枚，
     *   守卫 `reminder/TermWeekGateSingleSourceGuardTest` 第 ③④⑧ 层按目录数调用块）。
     *   默认值 [TermWeekGate.UNBOUNDED_TOTAL_WEEKS] = "这一处调用方明说不判上界"，
     *   T227 之后它**只**属于展示侧那一处历史包袱（`widget/WidgetCommon.kt` 的两枚「下一节课」，
     *   改它们要动本卡白名单外的文件 ⇒ 回执「需要跨卡配合」那一节），
     *   **不是**第二枚"默认值"：拿不到学期行的排铃侧传的是 null，那一档答"不在学期内"。
     *   ⚠️ 会排闹钟的调用点一律不许吃默认值：`LiveClassResyncer.startLive` 补的那枚下课铃
     *   到点撤的是全仓唯一那枚课堂实况通知 id 并恢复勿扰（#332）。
     */
    fun planNextClassWindow(
        courses: List<Course>,
        semesterStart: LocalDate,
        timeSlots: List<TimeSlot>,
        now: LocalDateTime,
        totalWeeks: Int? = TermWeekGate.UNBOUNDED_TOTAL_WEEKS,
    ): ClassWindow? {
        val slots = (if (timeSlots.isNotEmpty()) timeSlots else TimeSlotProfile.DEFAULT)
            .toStartEndTimes()
        val gapMinutes = periodGapMinutesOf(slots)
        val (course, window) = courses.asSequence()
            .mapNotNull { candidate ->
                nextOrCurrentWindow(candidate, semesterStart, totalWeeks, slots, gapMinutes, now)
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
            week = window.week,
            dayOfWeek = window.begin.dayOfWeek.value,
            colorArgb = courseColor(course).toArgb(),
        )
    }

    /**
     * 该课程「尚未结束的最早一个节次段窗口」（含正在上的这次）；没有则返回 null。
     * 周次升序 × 段升序即时间升序，第一个未结束的窗口就是最近的一次。
     *
     * 窗口本身（哪一段、几点到几点、缺时间怎么兜）由
     * [com.buaa.schedule.domain.schedule.periodWindowsOf] 给 —— 界面 Hero 与桌面组件
     * 读的是同一份，三处各写一遍时"缺下课时间"就有三种答案。
     *
     * **周次先过 [TermWeekGate.isInTermWeek] 再算日期**（T214 病灶 A）：屏上那一周画不到，
     * 这一节就不该有上课铃、下课铃与自动勿扰。越界的周次是**跳过**而不是截断整门课 ——
     * 一门 `weeks = [1, 2, 17]`、学期只有 16 周的课仍然在第 1、2 周照响。
     */
    private fun nextOrCurrentWindow(
        course: Course,
        semesterStart: LocalDate,
        totalWeeks: Int?,
        slots: Map<Int, Pair<LocalTime, LocalTime>>,
        gapMinutes: (Int, Int) -> Long?,
        now: LocalDateTime,
    ): SegmentWindow? {
        // **星期那一维也要过尺**（#524，T275 审计 N1）：改前这里判完周号就直接
        // `(course.dayOfWeek - 1)` 推日期，脏星期（教务返回过 0 / 8）于是被推成**相邻那一周**的
        // 真实日子（0 → 上一周的周日，8 → 下一周的周一），屏上根本没有这门课的那一天照响上课铃、
        // 照挂自动勿扰；更糟的是下面 `window.begin.dayOfWeek.value` 把**偏移后的**星期发给实况卡与
        // 组件，屏上念的星期与课程行不同。判据只在 [WeekdayDateOffset] 那一处，这里不写第二遍；
        // 两档 Rejected 一律「算不出这一门课的下一个窗口」= 交回 null（与同函数
        // `semesterStart` 取不到时同一口径：没有可靠的日期就不排铃，宁少不吵）。
        val dayTier = WeekdayDateOffset.dayOffsetOrReject(course.dayOfWeek)
        if (dayTier !is WeekdayOffset.Accepted) return null
        val gapSegments = course.periods.toPeriodSegments(gapMinutes)
        for (week in course.weeks.sorted()) {
            if (!TermWeekGate.isInTermWeek(week, semesterStart, totalWeeks)) continue
            val date = semesterStart.plusWeeks((week - 1).toLong())
                .plusDays(dayTier.offsetDays)
            val window = periodWindowsOf(course, date, slots, gapSegments)
                .firstOrNull { it.end.isAfter(now) } ?: continue
            return SegmentWindow(window.begin, window.end, window.segment, week)
        }
        return null
    }

    /**
     * 某个连续节次段的一次上课窗口。
     *
     * [week] 必须跟着窗口走：窗口可能是"下周三第 9-10 节"，事后按 `LocalDate.now()`
     * 反推周次必然推错，而实况卡片和组件都要显示「第 N 周」。
     */
    private data class SegmentWindow(
        val begin: LocalDateTime,
        val end: LocalDateTime,
        val segment: IntRange,
        val week: Int,
    )

    /**
     * 按最新的「尚未结束的最早一次课」重排上/下课铃。
     *
     * 用 [planNextClassWindow] 而不是课前提醒计划里的那门课：提醒的触发时间比上课时间早一个
     * 提前量，提前量大时最近的一节课会被判成"触发时间已过"而跳过，
     * 于是上课铃落到再下一节课 —— 与「下一节课」组件、常驻通知的口径不一致。
     *
     * 另外：当最近一次课**还没开始**时，说明此刻没有课在进行。若此时仍挂着上一次的常驻通知
     * 或勿扰（典型场景：用户把正在上 / 刚上完的那门课删了，重排后直接跳到下周），
     * 必须先收干净，否则那条通知会一直留到下一节课下课、勿扰也会一直关着。
     *
     * @param totalWeeks 学期总周数，**必填**：这一条是排铃链（上/下课铃 + 自动勿扰），
     *   屏上画不到的那一周不该有铃。拿不到学期行时传 null（[TermWeekGate] 那一档答"不放行"），
     *   不许退到 [TermWeekGate.UNBOUNDED_TOTAL_WEEKS]（那是展示侧三处旧调用点的包袱，不是默认值）。
     * @return 本轮**有没有**"该排而没排上"的课堂铃。false 只有一种来源：排铃那两手里至少
     *   有一枚抛了异常（每一枚自己都已 `Log.w` 留痕）。挑不出课堂窗口是 true ——
     *   那是"没有可排的铃"，不是"排失败"，把它记成失败会逼调用方去救一个不需要救的现场
     *   （同一口径见 [rescheduleNextWindow] 那两条早退为什么不报失败）。
     */
    fun rescheduleWindows(
        context: Context,
        courses: List<Course>,
        semesterStart: LocalDate,
        totalWeeks: Int?,
        timeSlots: List<TimeSlot>,
    ): Boolean {
        // 先撤掉旧的两个闹钟，再按最新的「尚未结束的最早一次课」重排
        cancel(context)
        // 整次重排只读一次时钟：下面既要拿它选窗口、又要拿它判"课还没开始"，
        // 两次各读各的就会在跨过节次边界的那一秒选出上一节、却按下下一节的清理分支。
        val now = LocalDateTime.now()
        val window = planNextClassWindow(
            courses = courses,
            semesterStart = semesterStart,
            timeSlots = timeSlots,
            now = now,
            totalWeeks = totalWeeks,
        )
        if (window == null) {
            cancelAll(context)
            return true
        }
        if (window.startsAfter(now.toEpochMillis()) &&
            // 课前倒计时挂的就是这节即将到来的课（同一通知 id）。
            // 此前这里无条件把"课还没开始"当成下课铃被吞的遗留收干净，
            // ReminderReceiver 自己触发的重排会在倒计时下发后一秒内把它拆掉 ——
            // 这正是"课中能上岛、课前倒计时上不了岛"剩下的那条机制级根因。
            // 课被删/时间被改时归属对不上，照常回收，R5 F-11 的清理语义不变。
            //
            // 只判**归属**（#338，与 ReminderScheduler 那条接线同一口径）：上面那判刚刚用
            // 这只注入的 `now` 说过"课还没开始"，再让谓词自己回体内读一只真钟就是
            // "一次判断两只钟"—— 两次读数之间隔着上面那轮全量窗口搜索（毫秒到几十毫秒级），
            // 跨秒那一瞬两处会先掉个儿：注入的 now 说还没开始、真钟说已开始 ⇒ 守卫当场失效，
            // 当场拆掉的恰恰是"这一节正要开始、勿扰正要生效"的那一秒。
            // 时间判没有丢，它归上面那句 startsAfter(now.toEpochMillis())。
            !ReminderNotifications.ownsCountdownTo(window.courseId, window.startMillis)
        ) {
            CourseFluidService.stop(context)
            ReminderNotifications.cancelClassOngoing(context)
            ClassProgressDnd.restore(context)
        }
        // 勿扰看门狗跟着新帧走（#603）：上面那句 cancel 只撤双铃，而那只看门狗与 prefs 里的
        // 恢复期限都是**排铃那一刻**按当时的墙钟/默认时区算出来的旧帧值 —— 它唯一的排程点是
        // ClassProgressDnd.enter，而 enter 唯一的调用点是上课铃那一刻。用户改时间 / 跨时区之后
        // 这一跳（清单里那唯一入口 WidgetRefreshReceiver → BackgroundSync.rescheduleRemindersAndBells
        // → 这里）是新帧的下课时刻唯一已经在手上的地方：双铃按它重排了，看门狗不能还按旧的走，
        // 否则勿扰在一个错误的墙钟时刻解除，而 selfCheck 判的也是那个旧帧值。
        // 六档判据在 ClassProgressDnd.watchdogRearmDecision：没进勿扰 / 新帧这一轮没有正在上的课 /
        // 新期限算不出 / 那格期限从来没写过 / 期限本来就跟着新帧走过 —— 五种情况一格都不许动，
        // 只有第六档才重排。classOngoing 必须显式判：这里选出的窗口是"最早一个还没结束的"，
        // 下课之后它就是**下一节**，照下一节的下课时刻推那一格遗留记录 = 把恢复点推后一整节课。
        // 读数吃的是上面那只已经注入的 now：整次重排只读一只钟（口径见
        // reminder/CountdownClockSingleReadGuardTest 第 ① 层）。
        ClassProgressDnd.alignWatchdogToNewFrame(
            context,
            classOngoing = window.ongoingAt(now.toEpochMillis()),
            freshDeadlineMillis = ClassProgressDnd.deadlineOfEnd(window.endMillis),
        )
        return schedule(context, window)
    }

    /**
     * 从数据库读最新课表，续排下一个课堂窗口。
     *
     * 独立于课前提醒开关：全部提醒关闭时 [ReminderScheduler.rescheduleAll] 走
     * "没有下一条提醒"分支直接收铃返回，下课铃若不自己续排，这类用户在第一节课之后
     * 就再也不会有课程实况与上课自动勿扰。两个开关都关时不武装任何闹钟。
     *
     * @param onStepFailed "这一步抛了"的留痕口，默认 `NO_RESCHEDULE_STEP_FAILURE` 等于无操作。
     *   多出来的这个口不为日志（那句 `Log.w` 由文件顶层的 `LOG_RESCHEDULE_WINDOW_FAILURE`
     *   原样承担，tag 与文案一字未改，审计 §4.4 的取证过滤条件不许变），
     *   只为让冷启动闸门看得见这一层的失败 —— 完整的账写在
     *   [com.buaa.schedule.widget.BackgroundSync.rescheduleRemindersAndBells] 的注释里。
     *   两条 `return`（两个开关都关 / 没有学期或学期没有起始日期）**不报**：那是"没有可排的窗口"，
     *   不是"排失败了"，记成失败只会逼闸门白重跑一整轮。
     */
    suspend fun rescheduleNextWindow(
        context: Context,
        onStepFailed: (label: String, error: Throwable) -> Unit = NO_RESCHEDULE_STEP_FAILURE,
        snapshot: ChainReadSnapshot? = null,
    ) = rescheduleNextWindow(
        readClassSwitches = {
            val prefs =
                context.getSharedPreferences(ClassProgressReceiver.PREFS_NAME, Context.MODE_PRIVATE)
            Pair(
                prefs.getBoolean(ClassProgressReceiver.PREF_CLASS_PROGRESS, true),
                prefs.getBoolean(ClassProgressReceiver.PREF_DND, false),
            )
        },
        loadWindowInput = load@{
            // 这一读在改前是"自己去查三张表"，而它上面那一步（rescheduleReminders）刚查完同样的
            // 三张表（#536 F2）。现在入口把结论装进载体传下来，这里只从载体上取；没有载体
            // （WidgetFallbackWorker / CourseFluidService / ClassProgressReceiver 那五处单独调用）
            // 才自己去读一趟，读的仍是同一批表、同一个口径。
            val read = snapshot ?: context.scheduleRepository().loadChainReadSnapshot()
            val semester = read.semester ?: return@load null
            val semesterStart = semester.startLocalDate ?: return@load null
            NextWindowInput(
                courses = read.courses,
                semesterStart = semesterStart,
                // 学期行就在手上，总周数就没有"取不到"那一档：这里传的是那一行的真值，
                // 而不是 UNBOUNDED 那一枚（拿不到学期行的路在上一句已经 return@load null 了）
                totalWeeks = semester.totalWeeks,
                timeSlots = read.timeSlots,
            )
        },
        reschedule = { input ->
            rescheduleWindows(
                context = context,
                courses = input.courses,
                semesterStart = input.semesterStart,
                totalWeeks = input.totalWeeks,
                timeSlots = input.timeSlots,
            )
        },
        onStepFailed = onStepFailed,
    )

    /**
     * [rescheduleNextWindow] 的编排本体：**不接 Context，三步副作用全是注入的 lambda**。
     *
     * 形状抄 [com.buaa.schedule.widget.BackgroundSync.runColdStartWidgetSteps] —— 本模块单测
     * 没有 Robolectric（android.jar 里全是抛 "not mocked" 的桩），真跑一次这条链要设备，
     * 而这张卡要钉的恰好是数得出来的那件事：抛了有没有向闸门报一声、报的标签是什么、
     * 以及"没东西可排"那几条早退不许被算成失败（见 `ClassProgressRescheduleFailureReportTest`）。
     *
     * 整体仍然吞异常（`runCatching` 不许改成向外抛）：五个调用点里四个没有报告口，
     * 让它们把异常逃出去等于把下课铃广播 / 前台服务那条链变成崩溃或被静默吞掉。
     *
     * @param readClassSwitches 两个课堂开关，顺序是 `classProgress to dndEnabled`
     * @param loadWindowInput 读库定位下一个窗口要用的三样输入；null = 没有可排的窗口（早退，不算失败）
     * @param reschedule 真正的重排（第一句就是 [cancel]，见 [rescheduleWindows]）
     * @param logFailure 那句 `Log.w` 的位置，默认 `LOG_RESCHEDULE_WINDOW_FAILURE`（= 原来那行，
     *   一字不差）。同样收成参数是为了让 JVM 单测不被 android.jar 的桩带偏
     *   （同 [com.buaa.schedule.widget.BackgroundSync.runColdStartWidgetSteps] 的那个参数，理由一字不差）。
     */
    internal suspend fun rescheduleNextWindow(
        readClassSwitches: () -> Pair<Boolean, Boolean>,
        loadWindowInput: suspend () -> NextWindowInput?,
        reschedule: suspend (NextWindowInput) -> Unit,
        onStepFailed: (label: String, error: Throwable) -> Unit,
        logFailure: (Throwable) -> Unit = LOG_RESCHEDULE_WINDOW_FAILURE,
    ) {
        runCatching {
            val (classProgress, dndEnabled) = readClassSwitches()
            if (!classProgress && !dndEnabled) return
            val input = loadWindowInput() ?: return
            reschedule(input)
        }.onFailure {
            logFailure(it)
            onStepFailed("rescheduleNextWindow", it)
        }
    }

    /**
     * 续排一个课堂窗口要读的三样输入 + 那一枚学期行的总周数：只在 seam 与生产接线之间传一次，
     * 不落任何状态。
     *
     * [totalWeeks] 可空不是"大概用不上"：读库侧只有拿到学期行才会构造这一格，所以生产上它恒为非 null；
     * 类型留成 `Int?` 是为了让 [TermWeekGate] 的"判不出 ⇒ 不放行"那一档在 seam 上也摆得进去
     * （表驱动单测 `reminder/TermWeekGateTest` 的第五格），而不是在这里再兜一枚假的周数 ——
     * 台账 #236 那族 `?: 20` 造出「这学期 20 周」的账就是这么来的。
     */
    internal data class NextWindowInput(
        val courses: List<Course>,
        val semesterStart: LocalDate,
        val totalWeeks: Int?,
        val timeSlots: List<TimeSlot>,
    )

    /**
     * 双铃一起排（课堂窗口变了 / 冷启动重建）。
     *
     * @return 本轮有没有"该排而没排上"的课堂铃（判据与留痕都在 [armStartBell] 与 [armEndBell]）。
     *   拿不到 AlarmManager 也是 false：铃确实没排上，答 true 就是假成功。
     */
    fun schedule(context: Context, window: ClassWindow): Boolean {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return false
        val startArmed = scheduleClassStartBell(
            context,
            alarmManager,
            window.startMillis,
            startPendingIntent(context, window),
        )
        val endArmed = scheduleEndBell(alarmManager, window.endMillis, endPendingIntent(context, window))
        // 两枚都要试，不许因为上课铃失败就跳下课铃：已经进去的勿扰只有下课铃能解除，
        // 跳过它等于把"挂着不解除"这件事锁死在这一节课里。
        return startArmed && endArmed
    }

    /**
     * 只排下课铃（上课铃触发后由接收器调用，撤常驻通知并恢复勿扰）。
     *
     * @return 这枚下课铃排上没有。false 的后果是**勿扰挂着不解除**（#16 / T12 / T14 那本账）；
     *   原因已经在 [armEndBell] 里 `Log.w` 过，这里把结论带出去给调用方。
     */
    fun scheduleEnd(context: Context, endMillis: Long): Boolean {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return false
        return scheduleEndBell(alarmManager, endMillis, endPendingIntent(context, emptyWindow()))
    }

    /**
     * 上课铃用 `setAlarmClock`（「用户闹钟」档）：Doze / 厂商省电白名单对它的豁免
     * 最彻底——系统把它当作用户亲自设定的闹钟对待，这正是「下一节课要上」的语义。
     * 代价是状态栏会出现闹钟图标，属预期信号；一次只排一个，量可控。
     * （跨 ROM 调研结论：拾光/Sleepy/SleepDown 的主链都没用它，这是我们的加强项。）
     *
     * 仍受 SCHEDULE_EXACT_ALARM 约束：未授权时降级 setAndAllowWhileIdle；
     * 个别 ROM 对 setAlarmClock 也抛异常时退回 setExactAndAllowWhileIdle。
     * 三手 set 各自包进 runCatching：`alarmShowIntent(context)` 拿 Context 造意图也可能抛
     * （ROM 禁用 MainActivity 那一档），裸着它会把兜底那一手一起跳过。
     *
     * @return 这一枚上课铃排上没有（档位与留痕见 [armStartBell]）。
     */
    private fun scheduleClassStartBell(
        context: Context,
        alarmManager: AlarmManager,
        atMillis: Long,
        pi: PendingIntent,
    ): Boolean {
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
        return armStartBell(
            canExact = canExact,
            setPowerSave = { alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi) },
            setAlarmClock = {
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, alarmShowIntent(context)), pi)
            },
            setExactAsFallback = { alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi) },
        )
    }

    /**
     * [scheduleClassStartBell] 的档位本体：**零 android、零时钟**，三手 set 与留痕口全是参数。
     *
     * 形状抄 [rescheduleNextWindow] 那枚内部重载（本模块单测没有 Robolectric，真跑一次排铃要设备，
     * 而这张卡要钉的恰好是数得出来的那件事：省电档失败有没有被念、退回精确档再失败时
     * 结论有没有被读成成功）。
     *
     * 三档：
     * 1. 未授权精确闹钟（或 Android 11 以下）：只有省电档这一手，它抛了就是 false ——
     *    此前这里是一枚裸 `runCatching` 直接 `return`，失败既不留痕也不带结论，
     *    而它的后果是勿扰挂着不解除；
     * 2. `setAlarmClock` 成功：true，一手都不许多试；
     * 3. `setAlarmClock` 抛（个别 ROM 就这么干）：留痕 + 退回 `setExactAndAllowWhileIdle`，
     *    **退回那一手自己也要留痕、也要带结论** —— [recoverCatching] 那种写法把返回值丢掉时，
     *    两次失败读起来就像一次都没失败过。
     *
     * 整体仍然吞异常（不许向外抛）：这条链从广播回调里被调，抛出去就是把下课铃广播打崩，
     * 而"这一枚没排上"已经由返回值与那句 `Log.w` 说完了。
     */
    internal fun armStartBell(
        canExact: Boolean,
        setPowerSave: () -> Unit,
        setAlarmClock: () -> Unit,
        setExactAsFallback: () -> Unit,
        logFailure: (reason: String, error: Throwable) -> Unit = LOG_BELL_ARM_FAILURE,
    ): Boolean {
        if (!canExact) {
            return armBellAttempt("省电档排上课铃失败：这一节的实况与自动勿扰都不会自己开始", setPowerSave, logFailure)
        }
        val clock = runCatching(setAlarmClock).onFailure {
            logFailure("setAlarmClock 失败，退回精确档重排上课铃", it)
        }
        if (clock.isSuccess) return true
        return armBellAttempt("退回精确档那一手也没排上课铃：这一轮的实况与勿扰没有闹钟接手", setExactAsFallback, logFailure)
    }

    /**
     * 下课铃（精确档 + 省电档兜底），[schedule] 与 [scheduleEnd] 共用。
     *
     * 精确闹钟降级：Android 12+ 的 SCHEDULE_EXACT_ALARM 是特殊权限
     * （Android 14+ 新装默认不授予），未授予时 `setExactAndAllowWhileIdle` 会抛
     * `SecurityException`。此前这里没做守卫，异常会从广播回调里逃出去 ——
     * 轻则上/下课铃静默失效，重则在 `ClassProgressReceiver` 里直接杀掉进程。
     * 与 [ReminderScheduler] 的处理保持一致。
     *
     * @return 这一枚下课铃排上没有（档位与留痕见 [armEndBell]）。
     */
    private fun scheduleEndBell(alarmManager: AlarmManager, atMillis: Long, pi: PendingIntent): Boolean {
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
        return armEndBell(
            canExact = canExact,
            setExact = { alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi) },
            setPowerSave = { alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi) },
        )
    }

    /**
     * [scheduleEndBell] 的档位本体：**零 android、零时钟**。
     *
     * 两档：授权了就试精确档（部分 ROM 即使授权也抛 SecurityException，省电策略收紧），
     * 留痕后退回省电档；没授权就直接省电档。
     * 与上课铃那两档同一条规矩：**兜底那一手的失败既要有 `Log.w`，也要进结论** ——
     * 此前这两处都是裸 `runCatching`，下课铃排失败 = 勿扰挂着不解除，而 logcat 里零证据。
     */
    internal fun armEndBell(
        canExact: Boolean,
        setExact: () -> Unit,
        setPowerSave: () -> Unit,
        logFailure: (reason: String, error: Throwable) -> Unit = LOG_BELL_ARM_FAILURE,
    ): Boolean {
        if (!canExact) {
            return armBellAttempt("省电档排下课铃失败：勿扰只能靠看门狗与冷启动自愈", setPowerSave, logFailure)
        }
        val exact = runCatching(setExact).onFailure {
            logFailure("精确下课铃排程失败，退回非精确档", it)
        }
        if (exact.isSuccess) return true
        return armBellAttempt("非精确档也没排上下课铃：勿扰只能靠看门狗与冷启动自愈", setPowerSave, logFailure)
    }

    /**
     * 一次排铃尝试的**唯一**形状：`runCatching` 之后必须接 `onFailure`，结论由 `isSuccess` 带出去。
     * 裸着一枚 `runCatching` 再 `return` 就是把失败读成成功（本卡的两格病灶都是这个形状）。
     */
    private fun armBellAttempt(
        reason: String,
        attempt: () -> Unit,
        logFailure: (reason: String, error: Throwable) -> Unit,
    ): Boolean = runCatching(attempt).onFailure { logFailure(reason, it) }.isSuccess

    /**
     * 闹钟图标点击时打开 App（setAlarmClock 的展示意图，固定复用一个）。
     *
     * internal 是因为 [ClassProgressDnd] 的勿扰看门狗同样用 `setAlarmClock`，
     * 复用这一份展示意图而不是再造一个 requestCode + 另一份 MainActivity 意图。
     */
    internal fun alarmShowIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_ALARM_SHOW,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

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
     * 完整撤除：上/下课铃 + 勿扰看门狗闹钟 + 常驻通知 + 恢复勿扰状态。
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
        // 看门狗也收掉：restore 成功时它已经自己取消过，这里覆盖的是取消路径 ——
        // 用户都关掉功能/清空课表了，不该再留一条会唤醒设备的闹钟。
        // restore 因权限被撤而失败时同样取消：那一刻排着闹钟也恢复不了什么，
        // 记录仍在 prefs 里，等重新授权后的冷启动 selfCheck 自愈。
        ClassProgressDnd.cancelWatchdog(context)
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

    private fun ClassWindow.toBundle(action: String): Bundle =
        Bundle().apply {
            putString(ClassProgressReceiver.EXTRA_ACTION, action)
            putAll(this@toBundle.toExtras())
        }
}
