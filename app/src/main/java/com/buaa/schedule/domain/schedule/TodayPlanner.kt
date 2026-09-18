package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.TimeSlotProfile
import com.buaa.schedule.domain.model.startLocalDate
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
 * 学期未设置 / 日期非法 / 假期时返回空安排。
 */
object TodayPlanner {

    private val EMPTY = TodayPlan(emptyList(), null, null, null, null)

    fun plan(
        courses: List<Course>,
        semester: Semester?,
        timeSlots: List<TimeSlot>,
        today: LocalDate,
        now: LocalTime,
    ): TodayPlan {
        val semesterStart = semester?.startLocalDate ?: return EMPTY
        val week = WeekCalculator.currentWeekOrNull(semesterStart, semester.totalWeeks, today)
            ?: return EMPTY
        val dayOfWeek = today.dayOfWeek.value
        // 窗口构造与状态判定全部交给 PeriodWindows —— 界面、组件、通知三个面读的是同一份算法，
        // 缺下课时间时按 45 分钟兜底（此前这里 `?: continue` 会把整段静默丢掉，
        // 于是"Hero 说还有 20 分钟下课"与"列表里这一行没有状态"同屏打架）。
        val slotTimes = (if (timeSlots.isNotEmpty()) timeSlots else TimeSlotProfile.DEFAULT)
            .toStartEndTimes()
        val moment = LocalDateTime.of(today, now)

        val todaySlots = mutableListOf<TodayCourseSlot>()
        for (course in courses) {
            if (course.dayOfWeek != dayOfWeek || !course.weeks.contains(week)) continue
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
}
