package com.buaa.schedule.data.import

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.schedule.CourseConstraints
import com.buaa.schedule.domain.schedule.WeekParser

object BuaaScheduleParser {

    /**
     * `parseArrangedListOutcome` 的薄壳：**只回 courses，三枚警告计数在这里全部丢掉**。
     *
     * 静默刷新那一路（`BuaaWebSession.refreshSchedule`）今天走的就是这一枚 ⇒ 病灶 B「这几门课的周次是
     * 补出来的」那一格在那条路上没人念（台账 #238；册子钉在 `BuaaScheduleParserTotalWeeksGuardTest` 第 ③ 层）。
     *
     * ⚠️ **丢的只是报数，不是判据**：T229 #301 那一格「教务没给节次 ⇒ 整条丢弃」落点在
     * [parseArrangedListOutcome] 的循环体里，薄壳委派给它 ⇒ **两条路（首次导入预览 / 静默刷新）行为一致**，
     * 不需要各自再改一遍。要接上的是「`missingSectionCourses` 念不念」，不是「丢不丢」。
     *
     * @param totalWeeks 兜底周次上界；默认 `20` 无来源（台账 #236）—— 见下面那枚函数的同名参数
     */
    /** 解析结果：课程 + 供导入预览展示的兜底警告计数 */
    data class ParseOutcome(
        val courses: List<Course>,
        /** 教务数据缺少教师/周次片段、按整学期兜底展示的课程数 */
        val fallbackWeekCourses: Int = 0,
        /** 教师信息缺失的课程数 */
        val unknownTeacherCourses: Int = 0,
        /**
         * 缺节次而被**整条丢弃**的教务记录条数（T229 #301）。
         *
         * 与上面两枚不同：它们数的是**落库之后**的行，这一枚数的是**没有落库**的那些条
         * （一节根本不存在的课不许变成第 1 节）。
         */
        val missingSectionCourses: Int = 0,
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
     * **缺一律整条丢，不凭空造**（T229 #301）：星期几缺失、节次缺失、节次反序钳制后展开为空，
     * 三样都是 `return@forEachIndexed`。节次那一手改前是 `?: 1`（造出一节不存在的课），现在与星期同尺，
     * 丢的条数进 [ParseOutcome.missingSectionCourses]。
     *
     * @param totalWeeks 学期总周数，缺教师周次片段那一条的兜底周次范围；默认 `20` **无来源**，两枚生产调用点都传满
     */
    fun parseArrangedListOutcome(
        items: List<BuaaCourseDto>,
        termCode: String,
        totalWeeks: Int = 20,
    ): ParseOutcome {
        val weeksUpperBound = totalWeeks.coerceIn(1, CourseConstraints.MAX_TOTAL_WEEKS)
        val fallbackWeeks = (1..weeksUpperBound).toList()
        val result = mutableListOf<Course>()
        var droppedNoSection = 0
        items.forEachIndexed { index, item ->
            val courseName = item.courseName ?: "未知课程"
            // T229 #301：星期与节次**同一把尺** —— 缺哪一样都整条丢。
            // 改前这里是两把尺：`dayOfWeek` 缺了 return，节次缺了折成第 1 节 ⇒ 一节根本不存在的课
            // 画上周课表、排铃、被 ICS/WakeUp 导出；用户因为"课不对"手改过之后又被 isManualOverride
            // 锁住，每轮重导都删不掉。同族先例的口径都是「没有依据就不落库」：
            // T27（`startPeriod`）、T192（ICS `sectionOf`）、T28（编辑器回填），以及本层
            // `TextScheduleParser.parse` 那句 `section.getOrNull(0) ?: return@forEachIndexed`。
            val day = item.dayOfWeek ?: return@forEachIndexed
            val start = item.beginSection
            if (start == null) {
                droppedNoSection++
                return@forEachIndexed
            }
            // 缺 end 而有 start 是**有出处**的单节（教务的"本节次"写法），不是凭空造：
            // PeriodRangeBoundsTest 的 `arranged(3, null)` 钉的就是这一等，不许跟着上面一起改成丢弃。
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
            missingSectionCourses = droppedNoSection,
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
     * 将北航 getTermWeeks 返回的周列表转换为 Semester —— **另一路、未接线**（台账 #238 病灶 A，T216 裁定）。
     *
     * 先把调用面说清：全 `app/src/main` 对本函数的调用点 **0 处**（只有 `BuaaTermWeeksParserTest` 在叫）。
     * 真路是 `BuaaInPageFetcher.fetchSemesterInfo`，它把同一批行自己喂给内核、组的是 `SemesterInfo`
     * 而不是 `Semester`，不经过这里。三解里选的是 ②「换成调真路内核」；不选 ①「连 DTO 侧一起删」的理由
     * 写在 T216 回执（删它要连坐一枚既有测试文件，超出本卡白名单），枚数由守卫钉。
     *
     * 「哪一行算第 1 周」这一判**不再在这里复刻**：DTO → [BuaaWeekRow] 的映射由本调用点做（与真路
     * 同一手），判据本体全仓只有一份 [resolveBuaaFirstWeek]。改前这里是那一判的第二枚写法，而且是
     * T193 判死的那一形（逐字留档，守卫按「注释里 1 处、代码里 0 处」两把尺钉它）：
     * `val first = items.minByOrNull { it.serialNumber ?: Int.MAX_VALUE } ?: return null`
     * —— 序号全缺时每行的键都算成同一枚 ⇒ 并列 ⇒ 回**列表首行**，首行只要日期读得通就被当「第 1 周」
     * 用，而它完全可能是第 8 周。
     *
     * 返回 null = 挑不出，**不猜**：空列表、内核每一档 Undetermined、学期号在这批行里不唯一，三样都算。
     * 周数那一格照旧是行数（与真路 `weeks.size` 同口径；「重复行也占一格」那笔账在台账 #236，本卡不重立）。
     */
    fun parseTermWeeks(items: List<BuaaTermWeekDto>): Semester? {
        if (items.isEmpty()) return null
        val first = resolveBuaaFirstWeek(items.map { BuaaWeekRow(it.serialNumber, it.startDate) })
        val startDate = when (first) {
            is BuaaFirstWeek.Resolved -> first.startDate
            // 诚实兜底还是 return null：挑不出就说挑不出（守卫第 ⑦ 层钉的就是这一手）
            is BuaaFirstWeek.Undetermined -> return null
        }
        // term 同样不猜。改前取的是"挑中那一行的 term"，而那一行正是上面判出来的 ⇒ 换个更严的说法重立：
        // 这批行里非空 term 去重后**恰好一枚**才认，两枚以上 = 数据自己分家，取哪一枚都是猜。
        val terms = items.mapNotNull { it.term }.distinct()
        if (terms.size != 1) return null
        return Semester(
            termCode = terms.first(),
            termName = terms.first(),
            startDate = startDate,
            totalWeeks = items.size,
        )
    }
}
