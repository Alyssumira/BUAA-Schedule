package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.TimeSlotProfile
import com.buaa.schedule.domain.model.toStartEndTimes
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** 课次状态：已结束 / 进行中 / 未开始 */
enum class SlotStatus { PAST, ONGOING, UPCOMING }

/** 今日某个课次（含具体时间） */
data class TodayCourseSlot(
    val course: Course,
    val start: LocalTime,
    val end: LocalTime,
    val segment: IntRange,
    val status: SlotStatus,
)

/** 今日安排：全部课次 + 当前/下一节课摘要 */
data class TodayPlan(
    val slots: List<TodayCourseSlot>,
    val ongoing: TodayCourseSlot?,
    val next: TodayCourseSlot?,
    /** 距下一节开始的分钟数 */
    val minutesToNext: Long?,
    /** 当前课剩余分钟数 */
    val minutesRemaining: Long?,
)

/**
 * 计算今日课程安排（纯函数）。
 *
 * 本文件**不再判学期**：走哪一把尺（学期＋周次 / 星期几 / 都不放行）是
 * [TodayScreenRuler.scopeOf] 的结论，由调用点当 [DayCourseScope] 参数递进来。
 * 改前它起手两枚 `?: return EMPTY`（没有学期行、那一周取不到），而今日页的列表按
 * 「星期几」放行同一批课 —— 于是没设学期的用户同屏看见「列表有 14:00 那张卡」与
 * 「Hero 说今天没有更多课了」（#344 / T264-01）。
 *
 * 时钟照旧是入参：`today` 与 `now` 由调用点递，函数体不读 `LocalDate.now()` /
 * `LocalTime.now()`（本仓成文禁令，台账 #265 那一族）。
 */
object TodayPlanner {

    fun plan(
        courses: List<Course>,
        timeSlots: List<TimeSlot>,
        today: LocalDate,
        now: LocalTime,
        scope: DayCourseScope,
    ): TodayPlan {
        val dayOfWeek = today.dayOfWeek.value
        // 窗口构造与状态判定全部交给 PeriodWindows —— 界面、组件、通知三个面读的是同一份算法，
        // 缺下课时间时按 45 分钟兜底（此前这里 `?: continue` 会把整段静默丢掉，
        // 于是"Hero 说还有 20 分钟下课"与"列表里这一行没有状态"同屏打架）。
        val slotTimes = (if (timeSlots.isNotEmpty()) timeSlots else TimeSlotProfile.DEFAULT)
            .toStartEndTimes()
        val moment = LocalDateTime.of(today, now)

        val todaySlots = mutableListOf<TodayCourseSlot>()
        for (course in courses) {
            if (course.dayOfWeek != dayOfWeek || !courseInsideScope(course, scope)) continue
            for (window in periodWindowsOf(course, today, slotTimes)) {
                todaySlots += TodayCourseSlot(
                    course = course,
                    start = window.begin.toLocalTime(),
                    end = window.end.toLocalTime(),
                    segment = window.segment,
                    status = window.statusAt(moment),
                )
            }
        }
        todaySlots.sortWith(compareBy({ it.start }, { it.course.name }))

        val ongoing = todaySlots.firstOrNull { it.status == SlotStatus.ONGOING }
        val next = todaySlots.firstOrNull { it.status == SlotStatus.UPCOMING }
        return TodayPlan(
            slots = todaySlots,
            ongoing = ongoing,
            next = next,
            // 向上取整，与实况通知的分钟口径同一个实现（见 minutesCeil）：
            // 向下截断会在下课（上课）前最后一分钟显示"还有 0 分钟"，两处数字还恒定差一分钟
            minutesToNext = next?.let { minutesUntil(now, it.start) },
            minutesRemaining = ongoing?.let { minutesUntil(now, it.end) },
        )
    }

    /**
     * 这门课在不在这一屏那把尺上。尺是哪一把由 [TodayScreenRuler] 选定，这里只**执行**它：
     *
     * - [DayCourseScope.TermWeek]：那一周的课才算数（周号是调用点从
     *   [WeekCalculator.currentWeekOrNull] 算好后交回的，本文件不再第二次算它）。
     * - [DayCourseScope.WeekdayOnly]：没设学期，只按星期几 —— 甲档的落点：
     *   Hero 与列表读同一把尺，句子就不会压在还挂着的卡上。
     * - [DayCourseScope.OutOfTerm]：两把尺都不放行（假期 / 开学日脏数据 / `totalWeeks = 0`），
     *   一门都不排 —— 与列表那一份结论（`coursesFor` 交回空表）同形状。
     */
    private fun courseInsideScope(course: Course, scope: DayCourseScope): Boolean = when (scope) {
        is DayCourseScope.TermWeek -> course.weeks.contains(scope.week)
        DayCourseScope.WeekdayOnly -> true
        DayCourseScope.OutOfTerm -> false
    }
}
