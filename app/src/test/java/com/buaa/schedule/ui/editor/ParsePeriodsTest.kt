package com.buaa.schedule.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsePeriodsTest {

    @Test
    fun contiguousRangeOnly() {
        assertEquals(listOf(1, 2), parsePeriods("1", "2", ""))
    }

    @Test
    fun mergesExtraPeriods() {
        assertEquals(listOf(1, 2, 9, 10), parsePeriods("1", "2", "9-10"))
    }

    @Test
    fun mergesCommaSeparatedExtra() {
        assertEquals(listOf(1, 2, 9, 10), parsePeriods("1", "2", "9,10"))
    }

    @Test
    fun deduplicatesOverlap() {
        assertEquals(listOf(1, 2, 3), parsePeriods("1", "3", "2"))
    }

    @Test
    fun invalidStartReturnsEmpty() {
        assertTrue(parsePeriods("x", "2", "").isEmpty())
    }

    @Test
    fun endBeforeStartReturnsEmpty() {
        assertTrue(parsePeriods("3", "2", "").isEmpty())
    }
}
