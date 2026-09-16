package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictDetectorTest {

    private fun course(
        id: Long,
        day: Int,
        periods: List<Int>,
        weeks: List<Int> = (1..16).toList(),
    ) = Course(
        id = id,
        name = "C$id",
        dayOfWeek = day,
        periods = periods,
        weeks = weeks,
    )

    @Test
    fun findsOverlapConflict() {
        val a = course(1, day = 1, periods = listOf(1, 2))
        val b = course(2, day = 1, periods = listOf(2, 3))
        val conflicts = ConflictDetector.findConflicts(listOf(a, b))
        assertEquals(1, conflicts.size)
        assertTrue(conflicts[0].weeks.isNotEmpty())
    }

    @Test
    fun ignoresDifferentDay() {
        val a = course(1, day = 1, periods = listOf(1, 2))
        val b = course(2, day = 2, periods = listOf(1, 2))
        assertTrue(ConflictDetector.findConflicts(listOf(a, b)).isEmpty())
    }

    @Test
    fun ignoresNonOverlappingWeeks() {
        val a = course(1, day = 1, periods = listOf(1, 2), weeks = listOf(1, 2))
        val b = course(2, day = 1, periods = listOf(1, 2), weeks = listOf(3, 4))
        assertTrue(ConflictDetector.findConflicts(listOf(a, b)).isEmpty())
    }

    @Test
    fun ignoresSelfWhenUpdating() {
        val a = course(1, day = 1, periods = listOf(1, 2))
        assertTrue(ConflictDetector.findConflicts(listOf(a, a.copy())).isEmpty())
    }

    @Test
    fun nonContiguousPeriodsConflictWithMiddlePeriod() {
        // 课程 A 占第 1,2,9,10 节（非连续），课程 B 占第 9 节 → 冲突
        val a = course(1, day = 1, periods = listOf(1, 2, 9, 10))
        val b = course(2, day = 1, periods = listOf(9))
        val conflicts = ConflictDetector.findConflicts(listOf(a, b))
        assertEquals(1, conflicts.size)
    }

    @Test
    fun nonContiguousPeriodsDoNotConflictWithGapPeriod() {
        // 课程 A 占第 1,2,9,10 节，课程 B 占第 3-8 节（中间空档）→ 不冲突
        val a = course(1, day = 1, periods = listOf(1, 2, 9, 10))
        val b = course(2, day = 1, periods = (3..8).toList())
        assertTrue(ConflictDetector.findConflicts(listOf(a, b)).isEmpty())
    }
}
