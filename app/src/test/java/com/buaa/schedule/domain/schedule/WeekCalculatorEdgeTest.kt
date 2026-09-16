package com.buaa.schedule.domain.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * [WeekCalculator] 的时间边界。
 *
 * 开学日来自教务 `getTermWeeks` 的 startDate，跨年学期（秋季学期末到春季学期初）
 * 与闰年是历史上算错周次的两个来源，这里用固定日期钉死，不依赖 `LocalDate.now()`。
 */
class WeekCalculatorEdgeTest {

    @Test
    fun leapDayStaysInTheSameTeachingWeek() {
        // 2024-02-26 是周一，2024-02-29 是闰日（同周周四）
        val start = LocalDate.of(2024, 2, 26)
        assertEquals(1, WeekCalculator.currentWeek(start, LocalDate.of(2024, 2, 29)))
    }

    @Test
    fun leapYearFebruarySpanDoesNotShiftWeekBoundary() {
        // 闰年 2 月有 29 天：从第 1 周周一算起 28 天后应进入第 5 周，
        // 若按「每月 30 天」或「每年 365 天」近似会算成第 4 周。
        val start = LocalDate.of(2024, 2, 26)
        assertEquals(5, WeekCalculator.currentWeek(start, start.plusDays(28)))
    }

    @Test
    fun semesterSpanningYearEndKeepsCounting() {
        // 2025-12-29 周一开学，2026-01-05 已是第 2 周（跨自然年不能归零）
        val start = LocalDate.of(2025, 12, 29)
        assertEquals(1, WeekCalculator.currentWeek(start, LocalDate.of(2025, 12, 31)))
        assertEquals(2, WeekCalculator.currentWeek(start, LocalDate.of(2026, 1, 5)))
    }

    @Test
    fun dayBeforeTermStartIsWeekZero() {
        val start = LocalDate.of(2025, 9, 1)
        assertEquals(0, WeekCalculator.currentWeek(start, start.minusDays(1)))
        assertNull(WeekCalculator.currentWeekOrNull(start, 20, start.minusDays(1)))
    }

    @Test
    fun lastWeekIsInclusiveAndWeekAfterIsNull() {
        val start = LocalDate.of(2025, 9, 1)
        val totalWeeks = 18
        val lastDay = start.plusDays(((totalWeeks - 1) * 7 + 6).toLong())
        assertEquals(totalWeeks, WeekCalculator.currentWeekOrNull(start, totalWeeks, lastDay))
        assertNull(WeekCalculator.currentWeekOrNull(start, totalWeeks, lastDay.plusDays(1)))
    }

    @Test
    fun isCurrentWeekMatchesCurrentWeek() {
        val start = LocalDate.of(2025, 9, 1)
        val today = start.plusDays(14) // 第 3 周周一
        assertEquals(3, WeekCalculator.currentWeek(start, today))
        assertEquals(true, WeekCalculator.isCurrentWeek(start, 3, today))
        assertEquals(false, WeekCalculator.isCurrentWeek(start, 4, today))
    }
}
