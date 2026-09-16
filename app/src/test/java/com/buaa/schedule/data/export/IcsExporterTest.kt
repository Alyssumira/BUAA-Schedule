package com.buaa.schedule.data.export

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class IcsExporterTest {

    private val semester = Semester(
        termCode = "2026-2027-1",
        termName = "2026-2027-1",
        startDate = "2026-09-07",
        totalWeeks = 19,
    )

    private fun course(
        name: String = "高等数学",
        dayOfWeek: Int = 1,
        periods: List<Int> = listOf(1, 2),
        weeks: List<Int> = listOf(1, 3),
        teacher: String? = "张三",
        location: String? = "J3-101",
    ) = Course(
        name = name,
        teacher = teacher,
        location = location,
        dayOfWeek = dayOfWeek,
        periods = periods,
        weeks = weeks,
    )

    @Test
    fun oneEventPerOccurrence() {
        // 第 1、3 周各一次课 → 2 个 VEVENT，日期相差 14 天
        val result = IcsExporter.export(semester, listOf(course()), emptyList())

        assertEquals(2, result.eventCount)
        assertEquals(0, result.skippedOccurrences)
        assertTrue(result.text.contains("DTSTART:20260907T080000"))
        assertTrue(result.text.contains("DTSTART:20260921T080000"))
        assertTrue(result.text.contains("DTEND:20260907T093500"))
    }

    @Test
    fun nonContiguousPeriodsSplitIntoSeparateEvents() {
        // 第 1,2 节 + 第 9,10 节 → 每周 2 个事件
        val result = IcsExporter.export(
            semester,
            listOf(course(periods = listOf(1, 2, 9, 10), weeks = listOf(1))),
            emptyList(),
        )

        assertEquals(2, result.eventCount)
        // 第一段 08:00-09:35，第二段 16:40-18:15
        assertTrue(result.text.contains("DTSTART:20260907T080000"))
        assertTrue(result.text.contains("DTEND:20260907T093500"))
        assertTrue(result.text.contains("DTSTART:20260907T164000"))
        assertTrue(result.text.contains("DTEND:20260907T181500"))
    }

    @Test
    fun deterministicUidAcrossExports() {
        val courses = listOf(course())
        val first = IcsExporter.export(semester, courses, emptyList())
        val second = IcsExporter.export(semester, courses, emptyList())

        // DTSTAMP 是"生成时刻"（RFC 5545 要求），逐次导出必然不同，
        // 因此稳定性只对除它以外的内容成立
        assertEquals(stripDtStamp(first.text), stripDtStamp(second.text))
        assertTrue(first.text.contains("UID:"))
        // UID 带周次后缀，两个周次的事件 UID 不同
        val uids = Regex("UID:(.+)").findAll(first.text).map { it.groupValues[1] }.toList()
        assertEquals(2, uids.size)
        assertEquals(uids.distinct().size, uids.size)
    }

    @Test
    fun everyEventCarriesDtStamp() {
        val result = IcsExporter.export(semester, listOf(course()), emptyList())

        val eventCount = Regex("BEGIN:VEVENT").findAll(result.text).count()
        val stampCount = Regex("DTSTAMP:\\d{8}T\\d{6}Z").findAll(result.text).count()
        assertEquals(2, eventCount)
        // RFC 5545：每个 VEVENT 都必须有 DTSTAMP
        assertEquals(eventCount, stampCount)
    }

    private fun stripDtStamp(text: String): String =
        text.split("\r\n").filterNot { it.startsWith("DTSTAMP:") }.joinToString("\r\n")

    @Test
    fun escapesSpecialCharacters() {
        val result = IcsExporter.export(
            semester,
            listOf(course(name = "高数,期末", location = "J3;101")),
            emptyList(),
        )

        assertTrue(result.text.contains("SUMMARY:高数\\,期末"))
        assertTrue(result.text.contains("LOCATION:J3\\;101"))
    }

    @Test
    fun foldsLongLinesWithContinuation() {
        val longName = "超".repeat(100)
        val result = IcsExporter.export(semester, listOf(course(name = longName)), emptyList())

        val lines = result.text.split("\r\n")
        // 折行后续行以空格开头
        assertTrue(lines.any { it.startsWith(" ") })
        // RFC 5545 按**八位组**限长 75：CJK 每字 3 字节，因此不能再用字符数判断
        assertTrue(lines.all { it.toByteArray(Charsets.UTF_8).size <= 75 })
        // 折行不能把多字节字符切开：去掉续行前缀后仍能拼回原文
        val reassembled = lines.joinToString("") { if (it.startsWith(" ")) it.drop(1) else it }
        assertTrue(reassembled.contains(longName))
    }

    @Test
    fun usesCrlfLineEndings() {
        val result = IcsExporter.export(semester, listOf(course()), emptyList())
        assertTrue(result.text.contains("\r\n"))
        assertTrue(!result.text.contains(Regex("(?<!\r)\n")))
    }

    @Test
    fun customTimeSlotsOverrideDefaults() {
        val slots = listOf(
            com.buaa.schedule.domain.model.TimeSlot(number = 1, startTime = "09:00", endTime = "09:45"),
            com.buaa.schedule.domain.model.TimeSlot(number = 2, startTime = "09:50", endTime = "10:35"),
        )
        val result = IcsExporter.export(semester, listOf(course()), slots)

        assertTrue(result.text.contains("DTSTART:20260907T090000"))
    }

    @Test
    fun skipsOccurrencesWithMissingSlotTimes() {
        // 只配置了第 1 节的时间，第 9 节缺 → 该段跳过并计数
        val slots = listOf(
            com.buaa.schedule.domain.model.TimeSlot(number = 1, startTime = "08:00", endTime = "08:45"),
        )
        val result = IcsExporter.export(
            semester,
            listOf(course(periods = listOf(1, 2, 9, 10), weeks = listOf(1))),
            slots,
        )

        // 第 1-2 段缺第 2 节结束槽位？第 2 节槽缺失 → 两段都缺 → 全部跳过
        assertEquals(0, result.eventCount)
        assertEquals(2, result.skippedOccurrences)
    }

    @Test
    fun invalidSemesterStartDateYieldsEmptyResult() {
        val bad = semester.copy(startDate = "not-a-date")
        val result = IcsExporter.export(bad, listOf(course()), emptyList())
        assertEquals(0, result.eventCount)
        assertEquals("", result.text)
    }
}
