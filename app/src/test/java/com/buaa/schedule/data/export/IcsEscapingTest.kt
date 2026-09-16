package com.buaa.schedule.data.export

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RFC 5545 文本转义的往返正确性。
 *
 * 课名/教师/地点来自教务或用户手输，可能带 `\r`（旧 Mac / 教务拼接产物）、
 * `\r\n`（Windows）、`\`、`,`、`;`。若 `\r` 没被先归一化成 `\n` 再转义，
 * 会残留一个裸 CR 在字段值里 —— 日历应用把它当行结束，一条 VEVENT 被撕成两段。
 */
class IcsEscapingTest {

    private val semester = Semester(
        termCode = "2026-2027-1",
        termName = "2026-2027-1",
        startDate = "2026-09-07", // 周一
        totalWeeks = 19,
    )

    private fun exportWith(raw: String): String {
        val course = Course(
            name = raw,
            teacher = raw,
            location = raw,
            dayOfWeek = 1,
            periods = listOf(1, 2),
            weeks = listOf(1),
        )
        return IcsExporter.export(semester, listOf(course), emptyList()).text
    }

    @Test
    fun loneCarriageReturnIsEscapedNotEmittedRaw() {
        val text = exportWith("高等\r数学")
        // 不能出现裸 CR：所有 CR 都必须是 CRLF 行结束的一部分
        val crlfStripped = text.replace("\r\n", "")
        assertEquals(0, crlfStripped.count { it == '\r' })
        assertTrue("应转义为 \\n", text.contains("\\n"))
    }

    @Test
    fun crlfIsEscapedAsASingleNewlineEscape() {
        val text = exportWith("高等\r\n数学")
        val crlfStripped = text.replace("\r\n", "")
        assertEquals(0, crlfStripped.count { it == '\r' })
        // \r\n 归一化成一个 \n 再转义，不能产生两个 \n 转义
        val escapedNewlines = Regex("""\\n""").findAll(text).count()
        // SUMMARY + LOCATION + DESCRIPTION 三处，每处恰好一个
        assertEquals(3, escapedNewlines)
    }

    @Test
    fun backslashSemicolonAndCommaAreEscaped() {
        val text = exportWith("""C\语言;高级,编程""")
        assertTrue("反斜杠未转义", text.contains("C\\\\语言"))
        assertTrue("分号未转义", text.contains("语言\\;高级"))
        assertTrue("逗号未转义", text.contains("高级\\,编程"))
    }

    @Test
    fun everyEventHasExactlyOneSummaryLine() {
        // 转义不彻底时 SUMMARY 会被 CR 截断成两行，这条断言直接兜住该回归
        val text = exportWith("高等\r数学")
        val summaryLines = text.lineSequence().filter { it.startsWith("SUMMARY:") }.toList()
        assertEquals(1, summaryLines.size)
        assertTrue(summaryLines[0].endsWith("数学"))
    }

    @Test
    fun longCjkValuesAreFoldedByOctets() {
        val text = exportWith("计".repeat(60))
        val folded = text.lineSequence().filter { it.startsWith(" ") }
        assertTrue("CJK 超长字段应按 UTF-8 字节折行", folded.any())
        assertTrue(
            "折行后每行不得超过 75 字节",
            text.split("\r\n").all { it.toByteArray(Charsets.UTF_8).size <= 75 },
        )
    }
}
