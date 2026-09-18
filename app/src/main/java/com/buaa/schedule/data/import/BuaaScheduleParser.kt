package com.buaa.schedule.data.import

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.schedule.CourseConstraints
import com.buaa.schedule.domain.schedule.WeekParser

object BuaaScheduleParser {

    /**
     * 将北航本科生/本研教育管理系统返回的 arrangedList 转换为 Course 列表。
     *
     * 一条教务排课记录可能包含多个 `教师[周次]` 片段（理论课/实验课拆分），
     * 这里按片段拆成多条 Course，并用 sourceGroupKey 关联同一门课。
     *
     * @param totalWeeks 学期总周数，缺少教师周次信息时的兜底周次范围
     */
    /** 解析结果：课程 + 供导入预览展示的兜底警告计数 */
    data class ParseOutcome(
        val courses: List<Course>,
        /** 教务数据缺少教师/周次片段、按整学期兜底展示的课程数 */
        val fallbackWeekCourses: Int = 0,
        /** 教师信息缺失的课程数 */
        val unknownTeacherCourses: Int = 0,
    )

    fun parseArrangedList(
        items: List<BuaaCourseDto>,
        termCode: String,
        totalWeeks: Int = 20,
    ): List<Course> = parseArrangedListOutcome(items, termCode, totalWeeks).courses

    /**
     * 将北航本科生/本研教育管理系统返回的 arrangedList 转换为 Course 列表。
     *
     * 一条教务排课记录可能包含多个 `教师[周次]` 片段（理论课/实验课拆分），
     * 这里按片段拆成多条 Course，并用 sourceGroupKey 关联同一门课。
     *
     * @param totalWeeks 学期总周数，缺少教师周次信息时的兜底周次范围
     */
    fun parseArrangedListOutcome(
        items: List<BuaaCourseDto>,
        termCode: String,
        totalWeeks: Int = 20,
    ): ParseOutcome {
        val weeksUpperBound = totalWeeks.coerceIn(1, CourseConstraints.MAX_TOTAL_WEEKS)
        val fallbackWeeks = (1..weeksUpperBound).toList()
        val result = mutableListOf<Course>()
        items.forEachIndexed { index, item ->
            val courseName = item.courseName ?: "未知课程"
            val day = item.dayOfWeek ?: return@forEachIndexed
            val start = item.beginSection ?: 1
            val end = item.endSection ?: start
            // 钳制上限必须在展开前：教务接口返回的 endSection 如果是脏数据
            // （见过 2147483647 这类溢出值），`(start..end).toList()` 直接 OOM。
            // 反序（end < start）钳制后展开为空——空 periods 的行在课表上永远画不出来，
            // 却占着管理页一行与课程名额，整条丢弃。
            val periods = (start.coerceIn(1, CourseConstraints.MAX_PERIOD)..
                end.coerceIn(1, CourseConstraints.MAX_PERIOD)).toList()
            if (periods.isEmpty()) return@forEachIndexed
            val campus = item.campusName ?: extractCampus(item)
            val location = item.placeName?.takeIf { it.isNotBlank() } ?: extractLocation(item)
            // 学分在一条教务记录上是**课程级**的（每个 `教师[周次]` 片段共用同一个值），
            // 所以这里解析一次、各片段共享；认不出来只丢学分，绝不丢这一行课。
            val credit = parseCredit(item.credit)
            val groupKey = listOf(
                termCode,
                item.teachClassId ?: item.courseCode ?: courseName,
                item.courseSerialNo ?: "",
            ).joinToString("|")

            val teacherWeekPairs = extractTeacherWeekPairs(item)

            if (teacherWeekPairs.isEmpty()) {
                result.add(
                    Course(
                        name = courseName,
                        teacher = "未知教师",
                        location = location,
                        campus = campus,
                        dayOfWeek = day,
                        periods = periods,
                        weeks = fallbackWeeks,
                        colorIndex = index % 8,
                        sourceGroupKey = groupKey,
                        semesterCode = termCode,
                        credit = credit,
                    )
                )
            } else {
                teacherWeekPairs.forEach { (teacher, weeksDesc) ->
                    val weeks = WeekParser.parse(weeksDesc)
                    if (weeks.isEmpty()) return@forEach
                    result.add(
                        Course(
                            name = courseName,
                            teacher = teacher,
                            location = location,
                            campus = campus,
                            dayOfWeek = day,
                            periods = periods,
                            weeks = weeks,
                            colorIndex = index % 8,
                            sourceGroupKey = groupKey,
                            semesterCode = termCode,
                            credit = credit,
                        )
                    )
                }
            }
        }
        // 教务 type=week&week=N 按周返回：同一门课在它上的每个周都出现一次，19 轮汇总后
        // 直译会让预览每门课重复十几行、「新增 N 门」虚高、同源行两两判成假冲突
        // （落库有 ImportPlanner 并键兜住，预览此前没有这一步，靠下面的 mergeSameSlotOccurrences 补）。
        val merged = mergeSameSlotOccurrences(result)
        return ParseOutcome(
            courses = merged,
            fallbackWeekCourses = merged.count { it.teacher == "未知教师" && it.weeks == fallbackWeeks },
            unknownTeacherCourses = merged.count { it.teacher == "未知教师" },
        )
    }

    /** 身份键完全相同、只有周次不同的行并成一条（周次取并集）；其余行原样保留、维持原序 */
    private fun mergeSameSlotOccurrences(courses: List<Course>): List<Course> {
        val byKey = LinkedHashMap<List<Any?>, MutableList<Course>>()
        courses.forEach { course ->
            // 空 id 的预览行没有可靠主键，用身份字段组合；weeks 不参与判同（它正是要并的）
            val key = listOf(
                course.sourceGroupKey, course.name, course.teacher, course.location,
                course.campus, course.dayOfWeek, course.periods, course.semesterCode,
            )
            byKey.getOrPut(key) { mutableListOf() }.add(course)
        }
        return byKey.values.map { group ->
            if (group.size == 1) group.first()
            else group.first().copy(
                weeks = group.flatMap { it.weeks }.distinct().sorted(),
                // 学分**取最大值**，不跟周次那样求并集/求和：教务在每一行上都写整门课的学分，
                // 1-8 周与 9-16 周是同一门课的同一个 3.5 分写了两遍，按周抓取还会把同一门课
                // 再重复十几轮 —— 求和等于把学分乘上片段数，统计页的总学分当场翻倍。
                // 取最大值还顺带解决"其中一段没带 credit"：已知的那个数不会被空值抹掉，
                // 且并一次与并两次结果相同（幂等，导入按周循环合并时靠这一点）。
                credit = group.mapNotNull { it.credit }.maxOrNull(),
            )
        }
    }

    /** 开头的数值段：`^3.5` 之于 "3.5学分"、"3.5 (必修)" */
    private val RE_LEADING_NUMBER = Regex("^\\d+(?:\\.\\d+)?")

    /**
     * 教务的 `credit` 是字符串（正常是 `"3.5"` / `"0.0"`，也见过空串、缺字段、
     * 带单位的 `"3.5学分"` 与全角数字）。
     *
     * 认不出来只返回 null（=没有学分数据），**不抛、也不丢这一行课**：抓一学期要打
     * 十几轮接口，一个学分脏值就让某一周导入失败，正是 R6 给响应加 `code` 校验想要
     * 避免的那种静默缺周（少一周 = 覆盖导入时那一周的课被清空）。
     */
    private fun parseCredit(raw: String?): Double? {
        val text = raw?.let { WeekParser.normalizeWidths(it).replace('．', '.') }?.trim()
        if (text.isNullOrEmpty()) return null
        // 先整体转数（"3.5"、"0.0"），不行再退到开头的数值段（"3.5学分"、"3.5 (必修)"）
        val value = text.toDoubleOrNull() ?: RE_LEADING_NUMBER.find(text)?.value?.toDoubleOrNull()
        return CourseConstraints.normalizeCredit(value)
    }

    /** 与活路径同族的两枚片段正则：提成常量（此前每条目每次调用都重新编译，这是解析热路径） */
    private val RE_CELL_PAIR = Regex("([^\\[\\]]+)\\[([^\\]]+)\\]")
    private val RE_TEACHER_WEEK = Regex("([^\\[\\]/]+?)\\[([\\d][\\d,\\-周单双（）()]*)\\]")

    /**
     * 从 cellDetail 或 weeksAndTeachers 中提取 `教师[周次]` 片段。
     *
     * 优先使用 cellDetail 中类似 `曾煜[1周]` 的行；
     * 兼容 weeksAndTeachers 中类似 `1周[实践]/曾煜[主讲]` 的非标准写法。
     */
    private fun extractTeacherWeekPairs(item: BuaaCourseDto): List<Pair<String, String>> {
        val cellPairs = item.cellDetail
            .orEmpty()
            .mapNotNull { it.text }
            .flatMap { text ->
                RE_CELL_PAIR.findAll(text)
                    .map { it.groupValues[1].trim() to it.groupValues[2].trim() }
                    .toList()
            }
            .filter { (_, weeks) -> weeks.any { it.isDigit() } }

        if (cellPairs.isNotEmpty()) return cellPairs

        return item.weeksAndTeachers
            ?.let { raw ->
                RE_TEACHER_WEEK.findAll(raw)
                    .map { it.groupValues[1].trim() to it.groupValues[2].trim() }
                    .toList()
            }
            .orEmpty()
    }

    private fun extractCampus(item: BuaaCourseDto): String? {
        val locationLine = item.titleDetail.orEmpty().firstOrNull { it.startsWith("上课地点") } ?: return null
        // 形如：上课地点：沙河校区/沙河校区场地/不使用教室
        return locationLine.substringAfter("：").substringBefore("/").trim().ifBlank { null }
    }

    private fun extractLocation(item: BuaaCourseDto): String? {
        val locationLine = item.titleDetail.orEmpty().firstOrNull { it.startsWith("上课地点") } ?: return null
        return locationLine.substringAfter("：").trim().ifBlank { null }
    }

    /**
     * 将北航 getTermWeeks 返回的周列表转换为 Semester。
     *
     * 返回 null 表示数据不完整（没有第一周或周数为 0）。
     */
    fun parseTermWeeks(items: List<BuaaTermWeekDto>): Semester? {
        if (items.isEmpty()) return null
        val first = items.minByOrNull { it.serialNumber ?: Int.MAX_VALUE } ?: return null
        val startDate = first.startDate
            ?.substringBefore(" ")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val term = first.term ?: return null
        return Semester(
            termCode = term,
            termName = term,
            startDate = startDate,
            totalWeeks = items.size,
        )
    }
}
