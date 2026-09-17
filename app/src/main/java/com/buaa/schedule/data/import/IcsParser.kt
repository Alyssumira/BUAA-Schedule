package com.buaa.schedule.data.import

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.TimeSlotProfile
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 轻量 ICS 解析器，覆盖常用课程表导出格式（VEvent + RRULE 每周重复）。
 *
 * 由于 App 使用“教学周”而不是绝对日期，需要传入学期开学日期（周一）来换算周次。
 * 时间到节次的映射用调用方传入的节次表（导出/导入同一份表才幂等），未传时退回默认 14 节。
 */
object IcsParser {

    // Locale 钉死为 US：ICS 走的是 ASCII 数字，`yyyyMMdd` 在部分 locale 下
    // 会用非 ASCII 数字解析/输出，导致导入的课表日期整体错位。
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd", java.util.Locale.US)
    private val timeFormatter = DateTimeFormatter.ofPattern("HHmmss", java.util.Locale.US)

    private data class DefaultSlot(
        val number: Int,
        val start: LocalTime,
        val end: LocalTime,
    )

    /**
     * 时间 → 节次的映射表（R5 F-29）。
     *
     * 必须用**调用方传入**的节次表：导出用的就是这份表，导入若按默认 14 节反查，
     * 用户改过作息（例如把第 3 节挪到 09:30）后「导出 → 再导入」会把课排错节次。
     * 未传时退回统一的默认表，与其他模块的兜底口径一致。
     */
    private fun slotTable(timeSlots: List<TimeSlot>): List<DefaultSlot> =
        (timeSlots.ifEmpty { TimeSlotProfile.DEFAULT })
            .mapNotNull { slot ->
                runCatching {
                    DefaultSlot(
                        number = slot.number,
                        start = LocalTime.parse(slot.startTime),
                        end = LocalTime.parse(slot.endTime),
                    )
                }.getOrNull()
            }

    fun parse(
        content: String,
        semesterStart: LocalDate,
        termCode: String,
        maxWeeks: Int = 25,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
        timeSlots: List<TimeSlot> = emptyList(),
    ): List<Course> {
        val slots = slotTable(timeSlots)
        val weekUpperBound = maxWeeks.coerceIn(1, com.buaa.schedule.domain.schedule.CourseConstraints.MAX_WEEK)
        val unfolded = unfold(content)
        val blocks = unfolded.split("BEGIN:VEVENT")
            .drop(1)
            .mapNotNull { block ->
                val end = block.indexOf("END:VEVENT")
                if (end >= 0) block.substring(0, end) else block
            }

        val courses = mutableListOf<Course>()
        blocks.forEach { block ->
            val summary = extractLine(block, "SUMMARY") ?: return@forEach
            val location = extractLine(block, "LOCATION")
            val description = extractLine(block, "DESCRIPTION")
            // UTC 时间（Z 后缀）换算到本地时区后再取日期与节次
            val startDateTime = extractDateTime(block, "DTSTART", zone) ?: return@forEach
            val endDateTime = extractDateTime(block, "DTEND", zone) ?: startDateTime
            val startTime = startDateTime.toLocalTime()
            val endTime = endDateTime.toLocalTime()
            val startDate = startDateTime.toLocalDate()

            val rruleLine = extractRawLine(block, "RRULE")
            // 只支持每周重复；DAILY/MONTHLY 等按单次事件处理，不再误当每周重复
            val isWeekly = rruleLine != null && isWeeklyRrule(rruleLine)
            val until = extractUntil(rruleLine, zone)
            val interval = extractInterval(rruleLine)
            val count = extractCount(rruleLine)
            val teacher = extractTeacher(description)
            val exDates = extractExDates(block)

            val startSection = sectionOf(startTime, slots)
            val endSection = sectionOf(endTime, slots)

            // RRULE 的 BYDAY=MO,WE：一周在多个星期几重复。此前一律按 DTSTART 的
            // 星期几 plusWeeks，另一半星期的课整门静默丢失（Google Calendar 导出常见写法）。
            // 现在每个目标星期各展开一条周次序列。
            val byDays = extractByDays(rruleLine)
            val weekdays: List<java.time.DayOfWeek> =
                if (isWeekly && byDays.isNotEmpty()) byDays else listOf(startDate.dayOfWeek)
            // COUNT 限的是总次数；拆成多条星期序列后按均摊近似（UNTIL 语义不受影响）
            val perSeriesCount = count?.let { (it + weekdays.size - 1) / weekdays.size }
            val mondayOfStart = startDate.minusDays((startDate.dayOfWeek.value - 1).toLong())

            weekdays.forEach { dow ->
                // 锚定 DTSTART 所在周（周一起算）里该星期几的日子；早于 DTSTART 的首周
                // 出现由 isBefore 过滤，之后每周步进 interval 周
                var date = mondayOfStart.plusDays((dow.value - 1).toLong())
                val weeks = mutableListOf<Int>()
                var occurrences = 0
                // 硬性迭代上限：即使 RRULE 参数畸形也不能无限跑。
                // 一个学期最多 MAX_WEEK(30) 周，留两倍余量足以覆盖任何合法输入
                // （此前 INTERVAL=0 会让 date.plusWeeks(0) 原地踏步 → 死循环 + weeks 无限增长）。
                val maxIterations = weekUpperBound * 2 + 8
                while (true) {
                    val week = dateToWeek(date, semesterStart)
                    if (week in 1..weekUpperBound && !date.isBefore(startDate) && date !in exDates) {
                        weeks.add(week)
                    }
                    if (rruleLine == null || !isWeekly) break
                    occurrences++
                    if (occurrences >= maxIterations) break
                    if (perSeriesCount != null && occurrences >= perSeriesCount) break
                    date = date.plusWeeks(interval.toLong())
                    if (until != null && date.isAfter(until)) break
                    // 无 UNTIL/COUNT 的无限重复，超出学期周数即可停止
                    if (week > weekUpperBound && until == null && count == null) break
                }

                if (weeks.isNotEmpty()) {
                    courses.add(
                        Course(
                            name = summary,
                            teacher = teacher,
                            location = location,
                            dayOfWeek = dow.value,
                            periods = (startSection..endSection).toList(),
                            weeks = weeks.distinct().sorted(),
                            colorIndex = (startSection + dow.value) % 8,
                            sourceGroupKey = null,
                            semesterCode = termCode,
                        )
                    )
                }
            }
        }
        return courses
    }

    /**
     * RRULE 的 BYDAY=MO,WE,FR。带序数前缀（`+1MO`）的按周频语义无意义，剥掉字母部分即可；
     * 一个都认不出来时返回空，由调用方退回「按 DTSTART 星期几」的老语义。
     */
    private fun extractByDays(rruleLine: String?): List<java.time.DayOfWeek> {
        val line = rruleLine ?: return emptyList()
        val value = RE_BYDAY.find(line)?.groupValues?.get(1) ?: return emptyList()
        return value.split(',').mapNotNull { raw ->
            when (raw.trim().filter { it.isLetter() }.uppercase()) {
                "MO" -> java.time.DayOfWeek.MONDAY
                "TU" -> java.time.DayOfWeek.TUESDAY
                "WE" -> java.time.DayOfWeek.WEDNESDAY
                "TH" -> java.time.DayOfWeek.THURSDAY
                "FR" -> java.time.DayOfWeek.FRIDAY
                "SA" -> java.time.DayOfWeek.SATURDAY
                "SU" -> java.time.DayOfWeek.SUNDAY
                else -> null
            }
        }
    }

    private fun sectionOf(time: LocalTime, slots: List<DefaultSlot>): Int {
        slots.firstOrNull { !time.isBefore(it.start) && time.isBefore(it.end) }?.let { return it.number }
        // 如果跨节次，按开始时间落入的节次处理；找不到则用最近的前一个节次
        val previous = slots.lastOrNull { !time.isBefore(it.start) }
        return previous?.number ?: 1
    }

    private fun dateToWeek(date: LocalDate, semesterStart: LocalDate): Int {
        if (date.isBefore(semesterStart)) return 0
        return (ChronoUnit.DAYS.between(semesterStart, date) / 7).toInt() + 1
    }

    private fun extractLine(block: String, name: String): String? {
        val regex = lineRegexFor(name, optionalParams = true)
        return regex.find(block)?.groupValues?.get(1)?.trim()
            ?.let { unescape(it) }
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * RFC 5545 文本反转义：单趟扫描一次认账 `\,` `\;` `\n` `\N` `\\`。
     *
     * 此前的链式 `replace` 只还原 `\n` 与 `\,`，而且先替换 `\n` 再考虑 `\\`：
     * 值里的字面反斜杠紧跟字母 n（`A\\nB`）会被拆成「反斜杠 + 换行」，
     * `\;`、`\\` 更是原样留在课程名里 —— 导出的 ICS 再导入回来不再幂等。
     * 与 `IcsExporter.escape` 使用同一套转义字符才算对称。
     */
    private fun unescape(value: String): String {
        val slash = value.indexOf('\\')
        if (slash < 0) return value
        val out = StringBuilder(value.length)
        out.append(value, 0, slash)
        var i = slash
        while (i < value.length) {
            val ch = value[i]
            if (ch != '\\') {
                out.append(ch)
                i++
                continue
            }
            when (val next = value.getOrNull(i + 1)) {
                'n', 'N' -> {
                    out.append('\n')
                    i += 2
                }
                ',', ';', '\\' -> {
                    out.append(next)
                    i += 2
                }
                else -> {
                    out.append(ch)
                    i++
                }
            }
        }
        return out.toString()
    }

    /**
     * 解析 DTSTART/DTEND。
     * - 带 `Z` 后缀：按 UTC 解释后换算到 [zone]；
     * - 带 `TZID=` 参数：按该时区解释后换算到 [zone]（此前一律当浮动时间，跨时区会整体错位）；
     * - 都没有：按本地浮动时间（IcsExporter 导出的就是这种）。
     */
    private fun extractDateTime(block: String, name: String, zone: java.time.ZoneId): java.time.LocalDateTime? {
        val line = extractFullLine(block, name) ?: return null
        val match = RE_DATETIME.find(line) ?: return null
        return runCatching {
            val date = LocalDate.parse(match.groupValues[1], dateFormatter)
            val time = LocalTime.parse(match.groupValues[2], timeFormatter)
            val local = date.atTime(time)
            when {
                match.groupValues[3] == "Z" ->
                    local.atZone(java.time.ZoneOffset.UTC).withZoneSameInstant(zone).toLocalDateTime()

                else -> tzidOf(line)?.let { from ->
                    local.atZone(from).withZoneSameInstant(zone).toLocalDateTime()
                } ?: local
            }
        }.getOrNull()
    }

    /** 行内 `TZID=xxx`（可能带引号）；无法识别该时区时返回 null，交由调用方按浮动时间处理 */
    private fun tzidOf(line: String): java.time.ZoneId? =
        RE_TZID.find(line)?.groupValues?.get(1)
            ?.trim()
            ?.removeSurrounding("\"")
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() }

    /** 取包含参数段的整行（如 `DTSTART;TZID=Asia/Shanghai:20260901T080000`） */
    private fun extractFullLine(block: String, name: String): String? =
        lineRegexFor(name, optionalParams = false).find(block)?.value

    private fun extractRawLine(block: String, name: String): String? =
        lineRegexFor(name, optionalParams = true).find(block)?.groupValues?.get(1)?.trim()

    private fun isWeeklyRrule(rruleLine: String): Boolean =
        RE_FREQ_WEEKLY.containsMatchIn(rruleLine)

    private fun extractUntil(rruleLine: String?, zone: java.time.ZoneId): LocalDate? {
        val line = rruleLine ?: return null
        val match = RE_UNTIL.find(line) ?: return null
        return runCatching {
            val date = LocalDate.parse(match.groupValues[1], dateFormatter)
            if (match.groupValues[3] == "Z") {
                val time = match.groupValues[2].takeIf { it.isNotBlank() }
                    ?.let { LocalTime.parse(it, timeFormatter) }
                    ?: LocalTime.MIDNIGHT
                date.atTime(time)
                    .atZone(java.time.ZoneOffset.UTC)
                    .withZoneSameInstant(zone)
                    .toLocalDate()
            } else {
                date
            }
        }.getOrNull()
    }

    /**
     * RRULE 的 INTERVAL。RFC 5545 规定必须 ≥ 1：
     * `INTERVAL=0` 会让 `date.plusWeeks(0)` 原地不动，配合循环的退出条件
     * 直接构成死循环（`weeks` 还会无限增长直到 OOM）。这里显式钳到 ≥1。
     */
    private fun extractInterval(rruleLine: String?): Int {
        val line = rruleLine ?: return 1
        return (RE_INTERVAL.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 1)
            .coerceAtLeast(1)
    }

    /** RRULE 的 COUNT；非法值（<1）按「未指定」处理，由周次上限自然收尾 */
    private fun extractCount(rruleLine: String?): Int? {
        val line = rruleLine ?: return null
        return RE_COUNT.find(line)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it >= 1 }
    }

    private fun unfold(content: String): String {
        val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
        val result = StringBuilder()
        normalized.lineSequence().forEach { line ->
            if ((line.startsWith(" ") || line.startsWith("\t")) && result.isNotEmpty()) {
                result.append(line.trim())
            } else {
                if (result.isNotEmpty()) result.append('\n')
                result.append(line)
            }
        }
        return result.toString()
    }

    private fun extractExDates(block: String): Set<LocalDate> {
        val result = mutableSetOf<LocalDate>()
        RE_EXDATE.findAll(block).forEach { match ->
            match.groupValues[1]
                .split(',')
                .mapNotNull { raw ->
                    val value = raw.trim()
                    runCatching {
                        if (value.contains("T")) {
                            LocalDate.parse(value.substring(0, 8), dateFormatter)
                        } else {
                            LocalDate.parse(value, dateFormatter)
                        }
                    }.getOrNull()
                }
                .forEach(result::add)
        }
        return result
    }

    private fun extractTeacher(description: String?): String? {
        if (description.isNullOrBlank()) return null
        // 教师名到换行为止，避免把 DESCRIPTION 后续行（地点等）带进来
        return RE_TEACHER.find(description)?.groupValues?.get(1)?.trim()
    }

    // ---- 正则常量 ----
    //
    // 一份 ICS 会有几十上百个 VEVENT，而下面这些正则此前是**每个字段、每个事件**
    // 各编译一次 —— 解析一份两百事件的课表要编译上千次正则，
    // 纯属浪费（Regex 构造要走一次完整的模式解析 + 自动机构建）。

    /** `20260901T080000` / `20260901T080000Z` */
    private val RE_DATETIME = Regex("(\\d{8})T(\\d{6})(Z?)")

    /** 行内 `TZID=Asia/Shanghai` 参数 */
    private val RE_TZID = Regex("TZID=([^;:]+)")

    /** RRULE 的 FREQ=WEEKLY（大小写不敏感） */
    private val RE_FREQ_WEEKLY = Regex("FREQ\\s*=\\s*WEEKLY", RegexOption.IGNORE_CASE)

    /** RRULE 的 UNTIL=20261231 / UNTIL=20261231T235959Z */
    private val RE_UNTIL = Regex("UNTIL=(\\d{8})(?:T(\\d{6}))?(Z?)")

    private val RE_INTERVAL = Regex("INTERVAL=(\\d+)")
    private val RE_COUNT = Regex("COUNT=(\\d+)")
    /** RRULE 的 BYDAY=MO,WE,FR（值段到分号或行尾为止） */
    private val RE_BYDAY = Regex("BYDAY=([^;\\s]+)")
    private val RE_EXDATE = Regex("(?m)^EXDATE(?:;[^:]*)?:(.*)$")
    /**
     * 教师名截断到 ` · ` 分隔符或行尾。
     *
     * 贪婪的 `[^\n]+` 会把本应用导出的 DESCRIPTION 后半段（`第1-2节 · 第1,3,5周`）
     * 一起当成教师名：导出的 ICS 再导入回来，教师字段被污染，而污染串里含周次，
     * 同一门课的各次上课会生成不同 courseKey，合并去重直接失效。
     * 分隔符两侧必须各带一个空格 —— 少数民族姓名的「买买提·艾力」不带，不能切断。
     */
    private val RE_TEACHER = Regex("教师[:：][^\\S\\n]*([^\\n]+?)(?=\\s·\\s|\\n|$)")

    /**
     * 带字段名（`SUMMARY` / `DTSTART` / ...）的正则无法做成常量，
     * 但字段名只有固定的七八个，按 name 缓存即可；
     * 用 ConcurrentHashMap 是因为解析跑在 IO 线程上，可能并发。
     */
    private val LINE_REGEX_CACHE = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    /**
     * @param optionalParams true 表示参数段可选（`^NAME(?:;...)?:(.*)$`），
     *   false 表示必须有参数段（`^NAME(;...)?:...$`，用于取回含 TZID 的整行）
     */
    private fun lineRegexFor(name: String, optionalParams: Boolean): Regex {
        val key = "$name|${if (optionalParams) "opt" else "raw"}"
        return LINE_REGEX_CACHE.getOrPut(key) {
            val params = if (optionalParams) "(?:;[^:]*)?" else "(;[^:]*)?"
            Regex("(?m)^${Regex.escape(name)}$params:(.*)$")
        }
    }
}
