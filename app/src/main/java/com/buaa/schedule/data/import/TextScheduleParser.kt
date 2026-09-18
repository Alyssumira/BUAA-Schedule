package com.buaa.schedule.data.import

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.schedule.CourseConstraints
import com.buaa.schedule.domain.schedule.WeekParser

/**
 * 简单文本课表导入。
 *
 * 每行一条课程，支持分隔符逗号/中文逗号/竖线/Tab，格式：
 * `课程名,教师,地点,星期(1-7),开始节-结束节,周次`
 *
 * 示例：
 * `高等数学,张三,J3-101,1,1-2,1-16`
 * `大学物理,李四,J3-205,3,3-4,1-16单`
 *
 * 学分不在这条链上（`credit` 恒为 null）：文本格式是固定的 6 列，没有学分列。
 * 不把可能的第 7 列猜成学分 —— 用户从群里粘的第 7 段更可能是备注/考试安排，
 * 猜错就是替用户写进一条看起来合法的脏学分，而 null 只是"不知道"。
 */
object TextScheduleParser {

    fun parse(
        content: String,
        termCode: String,
    ): List<Course> {
        val result = mutableListOf<Course>()
        content.lineSequence().forEachIndexed { index, rawLine ->
            // 群里复制的课表常混着全角空格/全角逗号/全角数字，不归一整个字段会错位、
            // 整行 parts<6 被静默丢掉（用户看到的是"解析结果为空"）
            val line = com.buaa.schedule.domain.schedule.WeekParser
                .normalizeWidths(rawLine).trim()
            if (line.isBlank() || line.startsWith("#") || line.startsWith("//")) return@forEachIndexed

            // 不做空字段过滤：空教师/空地点必须占位，否则后续字段会整体左移错位
            val parts = line
                .replace("，", ",")
                .replace("、", ",")
                .split(',', '|', '\t')
                .map { it.trim() }

            if (parts.size < 6) return@forEachIndexed

            val name = parts[0]
            if (name.isBlank()) return@forEachIndexed
            val teacher = parts[1].ifBlank { null }
            val location = parts[2].ifBlank { null }
            val day = parseDay(parts[3]) ?: return@forEachIndexed
            val section = parts[4].split("-").mapNotNull { it.toIntOrNull() }
            val start = section.getOrNull(0) ?: return@forEachIndexed
            val end = section.getOrNull(1) ?: start
            val weeks = WeekParser.parse(parts[5])
            if (weeks.isEmpty()) return@forEachIndexed

            // 上限钳制必须在展开前做：`(start..end).toList()` 对
            // "1-2000000000" 这种输入会立刻分配 20 亿个 Integer（约 8GB），
            // 一张恶意/损坏的文本课表就能 OOM 掉整个进程。
            // 反序（"8-3"）钳制后展开为空：宁可不收这行，也不能入库一条
            // 课表上永远画不出来的空 periods 课程。
            val periods = (start.coerceIn(1, CourseConstraints.MAX_PERIOD)..
                end.coerceIn(1, CourseConstraints.MAX_PERIOD)).toList()
            if (periods.isEmpty()) return@forEachIndexed
            result.add(
                Course(
                    name = name,
                    teacher = teacher,
                    location = location,
                    dayOfWeek = day,
                    periods = CourseConstraints.normalizePeriods(periods),
                    weeks = weeks,
                    colorIndex = index % 8,
                    sourceGroupKey = null,
                    semesterCode = termCode,
                )
            )
        }
        return result
    }

    private fun parseDay(value: String): Int? = when (value.trim()) {
        "1", "周一", "星期一" -> 1
        "2", "周二", "星期二" -> 2
        "3", "周三", "星期三" -> 3
        "4", "周四", "星期四" -> 4
        "5", "周五", "星期五" -> 5
        "6", "周六", "星期六" -> 6
        "7", "周日", "周天", "星期日" -> 7
        else -> value.toIntOrNull()?.takeIf { it in 1..7 }
    }
}
