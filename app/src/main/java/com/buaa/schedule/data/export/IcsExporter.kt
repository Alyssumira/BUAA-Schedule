package com.buaa.schedule.data.export

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.schedule.ScheduleOccurrences
import java.time.format.DateTimeFormatter

/**
 * 课表 → ICS 文本（一次性导出，交给用户选择的日历应用导入）。
 *
 * - 每次实际上课（课程 × 教学周 × 连续节次段）生成一个 VEVENT，
 *   而不是用单一 RRULE 近似——北航课表的单双周/调课/非连续节次
 *   无法用简单 RRULE 准确表达；
 * - UID/内容由 [ScheduleOccurrences] 统一生成，与系统日历同步共用
 *   同一套稳定身份：课程内容不变时重复导出得到相同 UID，
 *   日历应用重导入会更新而不是复制事件；
 * - 时间使用本地浮动时间（无 TZID），符合大多数日历应用的导入习惯。
 */
object IcsExporter {

    data class ExportResult(
        val text: String,
        val eventCount: Int,
        /** 因节次时间缺失/非法而跳过的课次数量 */
        val skippedOccurrences: Int,
    )

    // Locale 钉死为 US：ICS 是机器读写的交换格式，
    // 默认 locale 一旦输出非 ASCII 数字（ar/fa 等）或非 ISO 年号，
    // 导出的文件其他日历应用会直接解析失败。
    private val dateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", java.util.Locale.US)
    private val utcStampFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", java.util.Locale.US)

    /** RFC 5545：每个物理行的八位组上限 */
    private const val FOLD_LIMIT_OCTETS = 75

    fun export(
        semester: Semester,
        courses: List<Course>,
        timeSlots: List<TimeSlot>,
    ): ExportResult {
        val build = ScheduleOccurrences.build(semester, courses, timeSlots)
        if (build.occurrences.isEmpty()) {
            return ExportResult("", 0, build.skipped)
        }

        // RFC 5545 要求 VEVENT 带 DTSTAMP（生成时刻，UTC）
        val dtStamp = java.time.Instant.now()
            .atZone(java.time.ZoneOffset.UTC)
            .format(utcStampFormatter)

        val lines = StringBuilder()
        lines.appendLine("BEGIN:VCALENDAR")
        lines.appendLine("VERSION:2.0")
        lines.appendLine("PRODID:-//BUAA Schedule//课表导出//CN")
        lines.appendLine("CALSCALE:GREGORIAN")
        lines.appendLine("METHOD:PUBLISH")
        lines.appendLine("X-WR-CALNAME:${escape(semester.termName)}")

        for (occurrence in build.occurrences) {
            lines.appendLine("BEGIN:VEVENT")
            lines.appendLine("UID:${occurrence.stableId}")
            lines.appendLine("DTSTAMP:$dtStamp")
            lines.appendLine("DTSTART:${occurrence.date.atTime(occurrence.start).format(dateTimeFormatter)}")
            lines.appendLine("DTEND:${occurrence.date.atTime(occurrence.end).format(dateTimeFormatter)}")
            lines.appendLine("SUMMARY:${escape(occurrence.title)}")
            occurrence.course.location?.let { lines.appendLine("LOCATION:${escape(it)}") }
            lines.appendLine("DESCRIPTION:${escape(occurrence.description)}")
            lines.appendLine("END:VEVENT")
        }

        lines.appendLine("END:VCALENDAR")
        return ExportResult(
            fold(lines.toString()),
            build.occurrences.size,
            build.skipped,
        )
    }

    /**
     * RFC 5545 文本转义：反斜杠、分号、逗号、换行。
     *
     * 必须先统一换行再转义：Windows/旧 Mac 风格的 `\r\n` 与 `\r` 若先被
     * `replace("\n", "\\n")` 处理，会残留一个裸 `\r`（CR）在字段值里，
     * 导出文件被其他日历应用解析时会被当成行结束，把一条 VEVENT 撕成两段。
     */
    private fun escape(value: String): String = value
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\n", "\\n")

    /**
     * RFC 5545 折行：内容行超过 75 个八位组应折行，续行以单个空格开头。
     *
     * **按 UTF-8 字节数而不是字符数计算**。此前按 74 个 UTF-16 字符折行，
     * 对 CJK 内容完全无效 —— 74 个汉字是 222 字节，严格解析器会截断或拒绝
     * （原注释"对 CJK 内容同样安全"与事实相反）。
     * 逐字符累加字节数，天然不会切断多字节序列。
     */
    private fun fold(content: String): String =
        content.split("\r\n", "\n").joinToString("\r\n") { line -> foldLine(line) }

    private fun foldLine(line: String): String {
        if (line.toByteArray(Charsets.UTF_8).size <= FOLD_LIMIT_OCTETS) return line
        val out = StringBuilder(line.length + 16)
        var usedBytes = 0
        var continuation = false
        for (ch in line) {
            val chBytes = ch.toString().toByteArray(Charsets.UTF_8).size
            // 续行以空格开头，该空格本身占 1 个八位组
            val limit = if (continuation) FOLD_LIMIT_OCTETS - 1 else FOLD_LIMIT_OCTETS
            if (usedBytes + chBytes > limit) {
                out.append("\r\n ")
                usedBytes = 0
                continuation = true
            }
            out.append(ch)
            usedBytes += chBytes
        }
        return out.toString()
    }
}
