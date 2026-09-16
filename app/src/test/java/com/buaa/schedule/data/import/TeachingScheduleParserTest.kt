package com.buaa.schedule.data.import

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「学习日程」响应的防御式解析测试。
 *
 * 该接口没有公开文档（抓包获得），响应字段形态可能变化；
 * 解析必须做到：形态变化时尽量仍能提取，提取不了的条目直接丢弃而不崩溃。
 * **语义约定：标注只做视觉提示，不参与周次计算与提醒调度。**
 */
class TeachingScheduleParserTest {

    @Test
    fun parsesJjrArrayUnderDatas() {
        val raw = """
            {"datas":{"JJR":[
                {"rq":"2026-10-01","mc":"国庆节","lx":"1"},
                {"rq":"2026-10-02","mc":"国庆节","lx":"1"}
            ]}}
        """.trimIndent()
        val days = TeachingScheduleParser.parse(raw)
        assertEquals(2, days.size)
        assertTrue(days.all { it.isHoliday })
        assertEquals("国庆节", days.first().note)
    }

    @Test
    fun marksAdjustedWorkdayAsWorkday() {
        val raw = """
            {"datas":{"JJR":[
                {"rq":"2026-09-28","mc":"国庆节调休上班","lx":"2"}
            ]}}
        """.trimIndent()
        val days = TeachingScheduleParser.parse(raw)
        assertEquals(1, days.size)
        assertTrue("调休上班应标为「班」", !days.first().isHoliday)
    }

    @Test
    fun toleratesAlternateFieldNames() {
        // 字段名变化（date/type/name）也应能提取
        val raw = """
            {"holidayList":[
                {"date":"2026-10-01","type":"holiday","name":"National Day"}
            ]}
        """.trimIndent()
        val days = TeachingScheduleParser.parse(raw)
        assertEquals(1, days.size)
        assertTrue(days.first().isHoliday)
        assertEquals("2026-10-01", days.first().date.toString())
    }

    @Test
    fun dropsEntriesWithoutRecognizableDate() {
        val raw = """
            {"datas":{"JJR":[
                {"mc":"国庆节"},
                {"rq":"2026-10-01","mc":"国庆节"}
            ]}}
        """.trimIndent()
        val days = TeachingScheduleParser.parse(raw)
        assertEquals(1, days.size)
    }

    @Test
    fun dedupesSameDate() {
        val raw = """
            {"datas":{"JJR":[
                {"rq":"2026-10-01","mc":"国庆节"},
                {"rq":"2026-10-01","mc":"国庆节（重复）"}
            ]}}
        """.trimIndent()
        val days = TeachingScheduleParser.parse(raw)
        assertEquals(1, days.size)
    }

    @Test
    fun nonHolidayEntriesAreIgnored() {
        // 普通日程条目（不含假/休/节/调休关键词）不应产出标注
        val raw = """
            {"datas":{"SKKC":{"days":["2026-09-11","2026-09-14"]}}}
        """.trimIndent()
        assertTrue(TeachingScheduleParser.parse(raw).isEmpty())
    }

    @Test
    fun invalidJsonYieldsEmpty() {
        assertTrue(TeachingScheduleParser.parse("not json at all").isEmpty())
        assertTrue(TeachingScheduleParser.parse("").isEmpty())
    }

    @Test
    fun vacationParentKeyIsHolidayContext() {
        // HOLIDAY_CONTEXT_KEYS 里曾写成 " vacation"（带前导空格），
        // 于是 vacation / vacationList 这类父字段名永远匹配不到，条目被整条丢弃
        val raw = """{"vacation":[{"date":"2026-05-01","name":"Labour Day"}]}"""

        val days = TeachingScheduleParser.parse(raw)

        assertEquals(1, days.size)
        assertEquals(true, days[0].isHoliday)
    }
}
