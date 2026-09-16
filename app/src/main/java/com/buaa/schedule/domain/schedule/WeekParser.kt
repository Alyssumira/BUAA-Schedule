package com.buaa.schedule.domain.schedule

/**
 * 解析北航课表中常见的周次描述。
 *
 * 支持格式：
 * - "2-17" -> 2..17
 * - "1-17单" -> 1,3,5...17
 * - "2-18双" -> 2,4,6...18
 * - "3,7,11-17单" -> 组合
 * - "1-17周(单)" / "1-17周(双)" -> 兼容括号写法
 * - "011111111111111110..." -> 周次位图
 */
object WeekParser {

    fun parse(description: String?): List<Int> {
        if (description.isNullOrBlank()) return emptyList()
        val trimmed = description.trim()
        // 仅长字符串才可能是位图；"10"/"11" 这类 1-2 位输入应按数字周次解析，
        // 否则 "10" 会被误判为位图解析成第 1 周。
        if (trimmed.length >= 3 && trimmed.all { it == '0' || it == '1' }) {
            return parseBitmap(trimmed)
        }

        val weeks = LinkedHashSet<Int>()
        // 去掉“周”字、空格，中文逗号转英文逗号
        val clean = trimmed
            .replace("周", "")
            .replace(" ", "")
            .replace("，", ",")

        clean.split(",").forEach { segment ->
            if (segment.isBlank()) return@forEach
            val isOdd = segment.contains("单") || segment.contains("(单)") || segment.contains("（单）")
            val isEven = segment.contains("双") || segment.contains("(双)") || segment.contains("（双）")
            val normalized = segment
                .replace("(单)", "")
                .replace("（单）", "")
                .replace("(双)", "")
                .replace("（双）", "")
                .replace("单", "")
                .replace("双", "")

            val range = normalized.split("-").mapNotNull { it.toIntOrNull() }
            when {
                range.size == 2 -> {
                    // 两端都要限幅：此前只夹了 end，
                    // 于是 "-3-10"（教务偶发的脏数据，split 后首元素为空被 mapNotNull 丢掉）
                    // 会静默按 3..10 解析 —— 少了一周也不报错；
                    // 而 "0-999" 这类 start<=0 的输入则会构造出一个从 0 开始的无意义区间。
                    val start = range[0].coerceAtLeast(1)
                    val end = range[1].coerceAtMost(CourseConstraints.MAX_WEEK)
                    if (start > end) return@forEach
                    for (week in start..end) {
                        if (isOdd && week % 2 == 0) continue
                        if (isEven && week % 2 != 0) continue
                        weeks.add(week)
                    }
                }
                range.size == 1 -> {
                    val week = range[0]
                    if (isOdd && week % 2 == 0) return@forEach
                    if (isEven && week % 2 != 0) return@forEach
                    weeks.add(week)
                }
            }
        }

        // 超出教学周范围的输入（如 1-999）截断到合法区间，防止无界列表
        return weeks.filter { it in 1..CourseConstraints.MAX_WEEK }.sorted()
    }

    fun parseBitmap(bitmap: String): List<Int> {
        if (bitmap.isBlank()) return emptyList()
        return bitmap.mapIndexedNotNull { index, c ->
            if (c == '1' && index + 1 <= CourseConstraints.MAX_WEEK) index + 1 else null
        }
    }

    /**
     * 把周次列表压缩为人类可读描述，用于导入预览和课程卡片。
     */
    fun toDisplayString(weeks: List<Int>): String {
        if (weeks.isEmpty()) return "无周次"
        val sorted = weeks.sorted().distinct()
        val parts = mutableListOf<String>()
        var i = 0
        while (i < sorted.size) {
            var j = i
            while (j + 1 < sorted.size && sorted[j + 1] == sorted[j] + 1) j++
            if (j > i) {
                parts.add("${sorted[i]}-${sorted[j]}")
            } else {
                parts.add(sorted[i].toString())
            }
            i = j + 1
        }
        return parts.joinToString(",") + "周"
    }
}
