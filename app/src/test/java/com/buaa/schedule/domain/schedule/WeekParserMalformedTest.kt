package com.buaa.schedule.domain.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WeekParser] 畸形区间的解析结果。
 *
 * 教务接口与用户手输的文本课表里都出现过脏周次串（前导负号、0 起始、
 * 首尾倒置、上界爆炸）。这类输入不应静默产出错误的周集合，也不应抛异常。
 */
class WeekParserMalformedTest {

    @Test
    fun leadingMinusDropsEmptyHeadAndStaysBounded() {
        // "-3-10" split('-') 得到 ["", "3", "10"]，空串被 mapNotNull 丢掉，
        // 实际按 3..10 解析。这里把行为钉死：有界、非空、不含第 1-2 周。
        val weeks = WeekParser.parse("-3-10")
        assertEquals((3..10).toList(), weeks)
    }

    @Test
    fun zeroStartIsClampedToWeekOne() {
        // start <= 0 会构造出从 0 开始的无意义区间，必须夹到 1
        val weeks = WeekParser.parse("0-5")
        assertEquals(listOf(1, 2, 3, 4, 5), weeks)
    }

    @Test
    fun invertedRangeYieldsEmptyInsteadOfSwapping() {
        // 首尾倒置（"17-2"）不猜用户意图、不交换，直接当作无有效周次
        assertTrue(WeekParser.parse("17-2").isEmpty())
    }

    @Test
    fun hugeUpperBoundIsClampedToMaxWeek() {
        val weeks = WeekParser.parse("1-999999")
        assertEquals(CourseConstraints.MAX_WEEK, weeks.size)
        assertEquals(1, weeks.first())
        assertEquals(CourseConstraints.MAX_WEEK, weeks.last())
    }

    @Test
    fun hugeUpperBoundWithOddFilterStaysBounded() {
        val weeks = WeekParser.parse("1-999999单")
        assertTrue(weeks.isNotEmpty())
        assertTrue(weeks.all { it % 2 == 1 })
        assertTrue(weeks.all { it in 1..CourseConstraints.MAX_WEEK })
    }

    @Test
    fun hugeLowerBoundYieldsEmptyInsteadOfCrashing() {
        // start 被夹到 MAX_WEEK、end 也被夹到 MAX_WEEK 之前 start>end 已经成立
        assertTrue(WeekParser.parse("2000000000-2000000001").isEmpty())
    }

    @Test
    fun shortNumericInputIsNotMistakenForBitmap() {
        // "10" 长度 < 3，走数字周次分支；若被当位图会解析成第 1 周
        assertEquals(listOf(10), WeekParser.parse("10"))
    }

    @Test
    fun bitmapLongerThanMaxWeekIsTruncated() {
        val bitmap = "1".repeat(CourseConstraints.MAX_WEEK + 12)
        val weeks = WeekParser.parseBitmap(bitmap)
        assertEquals(CourseConstraints.MAX_WEEK, weeks.size)
        assertEquals(CourseConstraints.MAX_WEEK, weeks.last())
    }

    @Test
    fun blankAndNullAreEmpty() {
        assertTrue(WeekParser.parse(null).isEmpty())
        assertTrue(WeekParser.parse("   ").isEmpty())
        assertTrue(WeekParser.parseBitmap("").isEmpty())
    }

    @Test
    fun bareParitySegmentAppliesToWholeDescription() {
        // "1-16周,单周"：裸「单」段清掉后只剩空串，此前奇偶约束随该段一起被丢掉，
        // 解析成完整 1..16；现在它是整份描述的全局修饰符
        assertEquals((1..16).step(2).toList(), WeekParser.parse("1-16周,单周"))
    }

    @Test
    fun conflictingBareParityFallsBackToPerSegment() {
        // 同时出现裸「单」与裸「双」是矛盾输入：不猜意图，退回按段解析
        assertEquals((1..10).toList(), WeekParser.parse("1-10周,单周,双周"))
    }

    @Test
    fun fullWidthDigitsAndDashParseLikeHalfwidth() {
        // 群里复制的课表常带全角数字/连字符，toIntOrNull 不认，整段会被静默丢光
        assertEquals((1..16).toList(), WeekParser.parse("１－１６"))
        assertEquals((1..15).step(2).toList(), WeekParser.parse("１－１５单"))
    }
}
