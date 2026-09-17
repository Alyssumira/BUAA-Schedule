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
            // （见过 2147483647 这类溢出值），`(start..end).toList()` 直接 OOM
            val periods = (start.coerceIn(1, CourseConstraints.MAX_PERIOD)..
                end.coerceIn(1, CourseConstraints.MAX_PERIOD)).toList()
            val campus = item.campusName ?: extractCampus(item)
            val location = item.placeName?.takeIf { it.isNotBlank() } ?: extractLocation(item)
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
                        )
                    )
                }
            }
        }
        // 教务 type=week&week=N 按周返回：同一门课在它上的每个周都出现一次，19 轮汇总后
        // 直译会让预览每门课重复十几行、「新增 N 门」虚高、同源行两两判成假冲突
        // （落库有 ImportPlanner 并键兜住，预览此前没有这一步；BuaaScheduleImporter.merge
        // 的 KDoc 描述的就是这里该做的事，但它本身已无人调用）。
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
            else group.first().copy(weeks = group.flatMap { it.weeks }.distinct().sorted())
        }
    }

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
                Regex("([^\\[\\]]+)\\[([^\\]]+)\\]").findAll(text)
                    .map { it.groupValues[1].trim() to it.groupValues[2].trim() }
                    .toList()
            }
            .filter { (_, weeks) -> weeks.any { it.isDigit() } }

        if (cellPairs.isNotEmpty()) return cellPairs

        return item.weeksAndTeachers
            ?.let { raw ->
                Regex("([^\\[\\]/]+?)\\[([\\d][\\d,\\-周单双（）()]*)\\]").findAll(raw)
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
     * 将研究生 GSMIS 的 jgList 转换为 Course 列表。
     * 每行是一条“节次粒度”安排，这里先做基础转换；连续节次合并后续在导入流程中完成。
     */
    fun parseJgList(items: List<GsmisCourseDto>, termCode: String): List<Course> {
        return items.mapIndexed { index, item ->
            val weeks = WeekParser.parseBitmap(item.weekBitmap ?: "")
            val startSection = item.startSection?.toIntOrNull() ?: 1
            Course(
                name = item.courseName ?: "未知课程",
                teacher = item.teacherNames?.takeIf { it.isNotBlank() } ?: "未知教师",
                location = item.placeName,
                dayOfWeek = item.dayOfWeek?.toIntOrNull() ?: 1,
                periods = listOf(startSection),
                weeks = weeks,
                colorIndex = index % 8,
                sourceGroupKey = listOf(termCode, item.courseCode ?: "", item.dayOfWeek, item.startSection).joinToString("|"),
                semesterCode = termCode,
            )
        }
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
