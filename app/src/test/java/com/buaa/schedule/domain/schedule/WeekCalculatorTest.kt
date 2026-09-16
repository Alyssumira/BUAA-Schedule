package com.buaa.schedule.domain.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class WeekCalculatorTest {

    private val start = LocalDate.of(2025, 9, 1) // Monday

    @Test
    fun beforeTermReturnsZero() {
        assertEquals(0, WeekCalculator.currentWeek(start, LocalDate.of(2025, 8, 31)))
    }

    @Test
    fun firstDayIsWeekOne() {
        assertEquals(1, WeekCalculator.currentWeek(start, LocalDate.of(2025, 9, 1)))
    }

    @Test
    fun afterSevenDaysIsWeekTwo() {
        assertEquals(2, WeekCalculator.currentWeek(start, LocalDate.of(2025, 9, 8)))
    }

    @Test
    fun beyondTotalWeeksReturnsNull() {
        assertNull(WeekCalculator.currentWeekOrNull(start, 20, LocalDate.of(2026, 2, 1)))
    }
}
