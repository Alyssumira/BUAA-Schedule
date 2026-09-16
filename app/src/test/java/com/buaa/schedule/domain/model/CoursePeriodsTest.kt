package com.buaa.schedule.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CoursePeriodsTest {

    @Test
    fun segmentsOfContiguousPeriods() {
        assertEquals(listOf(1..3), listOf(1, 2, 3).toPeriodSegments())
    }

    @Test
    fun segmentsOfNonContiguousPeriods() {
        assertEquals(listOf(1..2, 9..10), listOf(1, 2, 9, 10).toPeriodSegments())
    }

    @Test
    fun segmentsOfSinglePeriod() {
        assertEquals(listOf(5..5), listOf(5).toPeriodSegments())
    }

    @Test
    fun segmentsIgnoreDuplicatesAndOrder() {
        assertEquals(listOf(1..2, 4..5), listOf(5, 2, 1, 4, 2).toPeriodSegments())
    }

    @Test
    fun segmentsOfEmptyList() {
        assertEquals(emptyList<IntRange>(), emptyList<Int>().toPeriodSegments())
    }

    @Test
    fun labelOfContiguousPeriods() {
        assertEquals("第1-2节", periodLabel(listOf(1, 2)))
    }

    @Test
    fun labelOfNonContiguousPeriods() {
        assertEquals("第1-2,9-10节", periodLabel(listOf(1, 2, 9, 10)))
    }

    @Test
    fun labelOfSinglePeriod() {
        assertEquals("第3节", periodLabel(listOf(3)))
    }

    @Test
    fun coursePeriodBounds() {
        val course = Course(
            name = "高数",
            dayOfWeek = 1,
            periods = listOf(9, 2, 1, 10),
            weeks = listOf(1),
        )
        assertEquals(1, course.startPeriod)
        assertEquals(10, course.endPeriod)
    }

    // —— R5 F-30：节次号相邻 ≠ 连堂 ——————————————————————————————

    private val defaultGap = periodGapMinutesOf(TimeSlotProfile.DEFAULT.toStartEndTimes())

    @Test
    fun lunchBreakSplitsAdjacentPeriods() {
        // 第 5 节 12:15 下课，第 6 节 14:00 才上课：中间隔着 105 分钟午饭
        assertEquals(listOf(5..5, 6..6), listOf(5, 6).toPeriodSegments(defaultGap))
    }

    @Test
    fun shortBreakStillCountsAsLinked() {
        // 课间只有 5-15 分钟，1-2、6-7 这类真连堂不能被切开
        assertEquals(listOf(1..2), listOf(1, 2).toPeriodSegments(defaultGap))
        assertEquals(listOf(6..7), listOf(6, 7).toPeriodSegments(defaultGap))
        assertEquals(listOf(9..10), listOf(9, 10).toPeriodSegments(defaultGap))
    }

    @Test
    fun dinnerBreakSplitsTenFromEleven() {
        assertEquals(listOf(10..10, 11..11), listOf(10, 11).toPeriodSegments(defaultGap))
    }

    @Test
    fun customTimetableWithLongRecessSplitsEveryPair() {
        // 用户自定义作息：每节之间空 45 分钟 —— 那就不算连堂
        val slots = listOf(
            TimeSlot(number = 1, startTime = "08:00", endTime = "08:45"),
            TimeSlot(number = 2, startTime = "09:30", endTime = "10:15"),
            TimeSlot(number = 3, startTime = "10:20", endTime = "11:05"),
        ).toStartEndTimes()
        val gap = periodGapMinutesOf(slots)
        assertEquals(listOf(1..1, 2..3), listOf(1, 2, 3).toPeriodSegments(gap))
    }

    @Test
    fun missingTimetableFallsBackToPeriodAdjacency() {
        // 节次表残缺时退回旧行为：只按节次号相邻切段
        val gap = periodGapMinutesOf(emptyMap())
        assertEquals(listOf(5..6), listOf(5, 6).toPeriodSegments(gap))
    }
}
