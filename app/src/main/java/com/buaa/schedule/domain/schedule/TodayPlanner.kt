package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.TimeSlotProfile
import com.buaa.schedule.domain.model.periodGapMinutesOf
import com.buaa.schedule.domain.model.startLocalDate
import com.buaa.schedule.domain.model.toPeriodSegments
import com.buaa.schedule.domain.model.toStartEndTimes
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

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
        val slots = (timeSlots.ifEmpty { TimeSlotProfile.DEFAULT }).toStartEndTimes()
        val gapMinutes = periodGapMinutesOf(slots)

        val todaySlots = mutableListOf<TodayCourseSlot>()
        for (course in courses) {
            if (course.dayOfWeek != dayOfWeek || !course.weeks.contains(week)) continue
            for (segment in course.periods.toPeriodSegments(gapMinutes)) {
                val start = slots[segment.first]?.first ?: continue
                val end = slots[segment.last]?.second ?: continue
                val status = when {
                    now.isBefore(start) -> SlotStatus.UPCOMING
                    now.isBefore(end) -> SlotStatus.ONGOING
                    else -> SlotStatus.PAST
                }
                todaySlots += TodayCourseSlot(course, start, end, segment, status)
            }
        }
        todaySlots.sortWith(compareBy({ it.start }, { it.course.name }))

        val ongoing = todaySlots.firstOrNull { it.status == SlotStatus.ONGOING }
        val next = todaySlots.firstOrNull { it.status == SlotStatus.UPCOMING }
        return TodayPlan(
            slots = todaySlots,
            ongoing = ongoing,
            next = next,
            // 向上取整，与实况通知的 minutesLeft 同一口径：
            // 向下截断会在下课（上课）前最后一分钟显示"还有 0 分钟"，两处数字还恒定差一分钟
            minutesToNext = next?.let { minutesUntil(now, it.start) },
            minutesRemaining = ongoing?.let { minutesUntil(now, it.end) },
        )
    }

    /** [from] 到 [to] 之间还剩多少分钟：不足一分钟按一分钟算，永不为负 */
    private fun minutesUntil(from: LocalTime, to: LocalTime): Long {
        val seconds = ChronoUnit.SECONDS.between(from, to)
        return ((seconds + 59) / 60).coerceAtLeast(0L)
    }
}
