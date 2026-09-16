package com.buaa.schedule.domain.schedule

import org.junit.Assert.assertEquals
import org.junit.Test

class WeekParserTest {

    @Test
    fun parseSimpleRange() {
        assertEquals(listOf(2, 3, 4, 5), WeekParser.parse("2-5"))
    }

    @Test
    fun parseOddWeeks() {
        assertEquals(listOf(1, 3, 5, 7, 9, 11, 13, 15, 17), WeekParser.parse("1-17单"))
    }

    @Test
    fun parseEvenWeeks() {
        assertEquals(listOf(2, 4, 6, 8), WeekParser.parse("2-8双"))
    }

    @Test
    fun parseCommaList() {
        assertEquals(listOf(1, 3, 7, 11, 12, 13), WeekParser.parse("1,3,7,11-13"))
    }

    @Test
    fun parseChineseParentheses() {
        assertEquals(listOf(1, 3, 5, 7, 9, 11, 13, 15, 17), WeekParser.parse("1-17周(单)"))
    }

    @Test
    fun parseBitmap() {
        assertEquals(listOf(1, 3), WeekParser.parseBitmap("10100000000000000000"))
    }

    @Test
    fun displayString() {
        assertEquals("1-3,5周", WeekParser.toDisplayString(listOf(1, 2, 3, 5)))
    }

    @Test
    fun shortDigitStringParsesAsWeekNumber() {
        // 1-2 位数字是周次数字而不是位图："10" 不能被解析成第 1 周
        assertEquals(listOf(1), WeekParser.parse("1"))
        assertEquals(listOf(10), WeekParser.parse("10"))
        assertEquals(listOf(11), WeekParser.parse("11"))
    }

    @Test
    fun longBinaryStringStillParsesAsBitmap() {
        assertEquals(listOf(2, 4, 6, 8), WeekParser.parse("01010101000000000000"))
    }
}
