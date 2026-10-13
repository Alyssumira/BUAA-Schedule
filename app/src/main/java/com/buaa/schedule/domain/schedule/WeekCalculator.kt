package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.mondayOfWeekAnchor
import java.time.LocalDate

/**
 * 教学周号换算（纯 JVM 判据）：本文件**零 android / androidx import**、不读时钟、不读库 ——
 * 「今天」一律由调用点当 `today` 参数递进来，三枚函数的 `today` 都没有默认值
 * （默认参数位 = 求值时照样读钟的那一形，`ui/WakeUpExportGateWiringGuardTest` 的 ⑤c 那格钉的就是它）。
 *
 * 周号上界不在这一层：[currentWeek] 只按自然周往上数，界内/界外由 [currentWeekOrNull]
 * 判 null、或由调用方自己夹取（见 [currentWeek] 的 @return）。
 */
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
     * @return 开学前返回 0；学期内从 1 起按自然周递增；学期外**继续往上数**（这一层没有上界夹取，
     *         签名里也拿不到总周数），界内与否由 [currentWeekOrNull] 判 null、或由调用方自己夹取。
     */
    fun currentWeek(semesterStart: LocalDate, today: LocalDate): Int {
        val start = mondayOf(semesterStart)
        if (today.isBefore(start)) return 0
        val days = java.time.temporal.ChronoUnit.DAYS.between(start, today)
        return (days / 7).toInt() + 1
    }

    fun currentWeekOrNull(semesterStart: LocalDate, totalWeeks: Int, today: LocalDate): Int? {
        val week = currentWeek(semesterStart, today)
        if (week <= 0 || week > totalWeeks) return null
        return week
    }

    /**
     * 判断某个教学周是否为当前周。
     */
    fun isCurrentWeek(semesterStart: LocalDate, week: Int, today: LocalDate): Boolean =
        currentWeek(semesterStart, today) == week
}
