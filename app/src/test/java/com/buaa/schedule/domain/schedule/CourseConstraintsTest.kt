package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CourseConstraintsTest {

    private fun course(
        dayOfWeek: Int = 1,
        periods: List<Int> = listOf(1, 2),
        weeks: List<Int> = (1..16).toList(),
        colorIndex: Int = 0,
        customColorArgb: Long? = null,
        name: String = "高数",
    ) = Course(
        name = name,
        dayOfWeek = dayOfWeek,
        periods = periods,
        weeks = weeks,
        colorIndex = colorIndex,
        customColorArgb = customColorArgb,
    )

    @Test
    fun normalizeKeepsValidCourse() {
        val normalized = CourseConstraints.normalize(course())
        assertNotNull(normalized)
        assertEquals(course(), normalized)
    }

    @Test
    fun normalizeTrimsNameAndRejectsBlank() {
        assertEquals("高数", CourseConstraints.normalize(course(name = "  高数  "))?.name)
        assertNull(CourseConstraints.normalize(course(name = "   ")))
    }

    @Test
    fun normalizeFiltersOutOfRangeWeeksAndPeriods() {
        val normalized = CourseConstraints.normalize(
            course(weeks = listOf(0, 1, 2, 31, 99), periods = listOf(0, 1, 2, 99))
        )
        assertNotNull(normalized)
        assertEquals(listOf(1, 2), normalized!!.weeks)
        assertEquals(listOf(1, 2), normalized.periods)
    }

    @Test
    fun normalizeRejectsInvalidDayOfWeek() {
        assertNull(CourseConstraints.normalize(course(dayOfWeek = 0)))
        assertNull(CourseConstraints.normalize(course(dayOfWeek = 8)))
    }

    @Test
    fun normalizeRejectsEmptyPeriodsOrWeeks() {
        assertNull(CourseConstraints.normalize(course(periods = listOf(99))))
        assertNull(CourseConstraints.normalize(course(weeks = listOf(0))))
    }

    @Test
    fun normalizeClampsNegativeColorIndex() {
        val normalized = CourseConstraints.normalize(course(colorIndex = -3))
        assertEquals(0, normalized!!.colorIndex)
    }

    @Test
    fun normalizeKeepsInRangeCustomColor() {
        // 0xFFFFFFFF 为合法上界（不透明白）
        val normalized = CourseConstraints.normalize(course(customColorArgb = 0xFFFFFFFFL))
        assertEquals(0xFFFFFFFFL, normalized!!.customColorArgb)
    }

    @Test
    fun normalizeDropsOutOfRangeCustomColor() {
        // 损坏备份 / 分享口令可能塞进超过 32 位的值，必须回退到调色板而不是原样写库
        assertNull(CourseConstraints.normalize(course(customColorArgb = -1L))!!.customColorArgb)
        assertNull(CourseConstraints.normalize(course(customColorArgb = 0x1FFFFFFFFL))!!.customColorArgb)
        assertNull(CourseConstraints.normalize(course(customColorArgb = Long.MAX_VALUE))!!.customColorArgb)
    }

    @Test
    fun normalizeCustomColorArgbBounds() {
        assertEquals(0L, CourseConstraints.normalizeCustomColorArgb(0L))
        assertEquals(0xFF5B8DEFL, CourseConstraints.normalizeCustomColorArgb(0xFF5B8DEFL))
        assertNull(CourseConstraints.normalizeCustomColorArgb(null))
        assertNull(CourseConstraints.normalizeCustomColorArgb(-1L))
        assertNull(CourseConstraints.normalizeCustomColorArgb(0x100000000L))
    }

    @Test
    fun weekParserCapsUnboundedRange() {
        // "1-999" 不能物化成 999 个元素
        val weeks = WeekParser.parse("1-999")
        assertEquals((1..CourseConstraints.MAX_WEEK).toList(), weeks)
    }

    @Test
    fun weekParserCapsBitmapLength() {
        val weeks = WeekParser.parse("1".repeat(100))
        assertEquals((1..CourseConstraints.MAX_WEEK).toList(), weeks)
    }
}
