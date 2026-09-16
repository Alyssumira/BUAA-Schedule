package com.buaa.schedule.domain.schedule

import java.time.LocalDate

object WeekCalculator {

    /**
     * 归一到该日期所在自然周的周一。
     *
     * 全应用的课次日期都是 `startDate.plusWeeks(w-1).plusDays(dayOfWeek-1)`，
     * 开学日期只要不是周一，整张课表就会整体偏移且周次编号错位，
     * 因此任何写入 `startDate` 的路径都要先过这里。
     * 周日（ISO 一周的最后一天）要往前退 6 天，不能用 previousOrNext。
     */
    fun mondayOf(date: LocalDate): LocalDate = date.minusDays((date.dayOfWeek.value - 1).toLong())

    /**
     * 根据开学日期计算当前教学周。
     *
     * @param semesterStart 开学日期（应为周一）
     * @param today 当天日期
     * @return 1..totalWeeks；开学前返回 0；超过总周数返回 totalWeeks + 1 或 null 由调用方处理。
     */
    fun currentWeek(semesterStart: LocalDate, today: LocalDate = LocalDate.now()): Int {
        val start = semesterStart
        if (today.isBefore(start)) return 0
        val days = java.time.temporal.ChronoUnit.DAYS.between(start, today)
        return (days / 7).toInt() + 1
    }

    fun currentWeekOrNull(semesterStart: LocalDate, totalWeeks: Int, today: LocalDate = LocalDate.now()): Int? {
        val week = currentWeek(semesterStart, today)
        if (week <= 0 || week > totalWeeks) return null
        return week
    }

    /**
     * 判断某个教学周是否为当前周。
     */
    fun isCurrentWeek(semesterStart: LocalDate, week: Int, today: LocalDate = LocalDate.now()): Boolean =
        currentWeek(semesterStart, today) == week
}
