package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.mondayOfWeekAnchor
import java.time.LocalDate

object WeekCalculator {

    /**
     * 归一到该日期所在自然周的周一。
     *
     * 公式在 [mondayOfWeekAnchor]（domain.model）只有那一份——`Semester.startLocalDate`
     * 读出来时已经过它，全应用不再有第二套锚点算法。
     */
    fun mondayOf(date: LocalDate): LocalDate = mondayOfWeekAnchor(date)

    /**
     * 根据开学日期计算当前教学周。
     *
     * @param semesterStart 开学日期（应为周一；非周一的历史值在这里被归一）
     * @param today 当天日期
     * @return 1..totalWeeks；开学前返回 0；超过总周数返回 totalWeeks + 1 或 null 由调用方处理。
     */
    fun currentWeek(semesterStart: LocalDate, today: LocalDate = LocalDate.now()): Int {
        val start = mondayOf(semesterStart)
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
