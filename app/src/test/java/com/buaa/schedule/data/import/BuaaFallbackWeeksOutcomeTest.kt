package com.buaa.schedule.data.import

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T216②：`parseArrangedListOutcome` 那格「教务没给周次信息 ⇒ 整学期每周都上」的**表驱动 JVM 单测**
 * （台账 #238 病灶 B；卡面 §3-1 要求摆「给了周次 / 没给周次 / `totalWeeks` 没传」三档）。
 *
 * 本文件只管两件事，两样都量自盘面：
 * 1. **兜底周次那一格的形状** —— 没给教师周次片段时，这门课被做成 `1..weeksUpperBound` 每周都上、
 *    教师写死 `"未知教师"`；给了周次时不碰这一格。语义本卡**一个字没改**（卡面 §2-B 明令：改周次
 *    语义是产品判断，归编排者问用户），这里只把今天的行为钉成格子。
 * 2. **`fallbackWeekCourses` 到底数了什么** —— 它数的是**并条之后**的条数
 *    （`mergeSameSlotOccurrences` 之后 `teacher == "未知教师" && weeks == fallbackWeeks`），
 *    所以教务按周返回、同一门兜底课重复 19 轮也只算 1 条。这一枚数就是导入预览那句
 *    「N 条课程缺少教师/周次信息，已按整学期展示，请核对」的输入；文案本体与消费者册子钉在
 *    `BuaaScheduleParserTotalWeeksGuardTest` 第 ③ 层（JVM 单测跑不到 Compose 那一句，
 *    这里只把"那句要念的到底是哪枚数"摆清）。
 *
 * ⚠️ `totalWeeks` 那枚默认参数：三档里「没传」那一格的 20 就是它造出来的事实。今天**两枚生产调用点
 * 都把实参传满**（静默刷新递 `SemesterInfo.totalWeeks`、首次导入递本轮抓到的周数）⇒ 这枚 20 决定不了
 * 任何一句用户看得见的周次范围，省略只长在 JVM 单测里（本文件故意留了两格省略，用来把这件事量出来）。
 * 「不许再长第三枚签名默认值」与「生产调用点零省略」两格钉在守卫第 ①② 层。
 */
class BuaaFallbackWeeksOutcomeTest {

    private companion object {
        const val TERM = "2026-2027-1"

        /** 兜底上界的夹取上限（`CourseConstraints.MAX_TOTAL_WEEKS`，本文件按盘面写死以便格子可读） */
        const val MAX_WEEKS = 30
    }

    /** 一格子 = 一批 arrangedList 行 +（可省的）totalWeeks + 期望的三条数与第一门课的周次 */
    private data class Case(
        val name: String,
        val items: List<BuaaCourseDto>,
        val totalWeeks: Int?,
        val expectedCourses: Int,
        val expectedFallbackWeekCourses: Int,
        val expectedUnknownTeacherCourses: Int,
        val expectedFirstWeeks: List<Int>?,
    )

    private fun course(
        vararg cells: String,
        day: Int = 3,
        weeksAndTeachers: String? = null,
    ) = BuaaCourseDto(
        courseName = "大学物理",
        courseCode = "PHY101",
        dayOfWeek = day,
        beginSection = 3,
        endSection = 4,
        placeName = "J3-201",
        campusName = "沙河",
        cellDetail = cells.map { CellDetailDto(text = it) },
        weeksAndTeachers = weeksAndTeachers,
    )

    /** `totalWeeks = null` 这一档就是"省着不传"，走签名默认值那一枚 20 */
    private fun outcomeOf(case: Case): BuaaScheduleParser.ParseOutcome = if (case.totalWeeks == null) {
        BuaaScheduleParser.parseArrangedListOutcome(case.items, TERM)
    } else {
        BuaaScheduleParser.parseArrangedListOutcome(case.items, TERM, case.totalWeeks)
    }

    @Test
    fun `三档表驱动 给了周次 没给周次 totalWeeks 没传`() {
        val cases = listOf(
            // ── 档一：教务给了教师[周次]片段 ⇒ 不碰兜底，两枚计数都该是 0 ──
            Case(
                name = "给了周次 + totalWeeks 传满 ⇒ 周次来自片段，兜底计数 0",
                items = listOf(course("张三[1-8周]", "李四[9-16周]")),
                totalWeeks = 19,
                expectedCourses = 2,
                expectedFallbackWeekCourses = 0,
                expectedUnknownTeacherCourses = 0,
                expectedFirstWeeks = (1..8).toList(),
            ),

            // ── 档二：没给周次信息 + 调用点传满 ⇒ 整学期每周都上，计数必须进报数 ──
            Case(
                name = "没给周次 + totalWeeks=19 ⇒ 补成 1..19，兜底计数 1",
                items = listOf(course()),
                totalWeeks = 19,
                expectedCourses = 1,
                expectedFallbackWeekCourses = 1,
                expectedUnknownTeacherCourses = 1,
                expectedFirstWeeks = (1..19).toList(),
            ),

            // ── 档三：没给周次 + totalWeeks 省着不传 ⇒ 那枚默认 20 在这里当场决定周次范围 ──
            Case(
                name = "没给周次 + 不传 totalWeeks ⇒ 补成 1..20（这 20 没有来源，只是签名默认值）",
                items = listOf(course()),
                totalWeeks = null,
                expectedCourses = 1,
                expectedFallbackWeekCourses = 1,
                expectedUnknownTeacherCourses = 1,
                expectedFirstWeeks = (1..20).toList(),
            ),

            // ── 档三续：给了周次那一支省略 totalWeeks 时，20 连一次都不参与（走不到兜底就没有上界） ──
            Case(
                name = "给了周次 + 不传 totalWeeks ⇒ 默认 20 不参与任何一格",
                items = listOf(course("张三[1-8周]")),
                totalWeeks = null,
                expectedCourses = 1,
                expectedFallbackWeekCourses = 0,
                expectedUnknownTeacherCourses = 0,
                expectedFirstWeeks = (1..8).toList(),
            ),

            // ── 混档：预览里同时有补出来的与真给的 ⇒ 那句必须念「1 条」，不是「2 条」 ──
            Case(
                name = "两门课：一门补出来、一门给了周次 ⇒ 兜底计数只数那一门",
                items = listOf(course(), course("张三[1-8周]", day = 4)),
                totalWeeks = 19,
                expectedCourses = 2,
                expectedFallbackWeekCourses = 1,
                expectedUnknownTeacherCourses = 1,
                expectedFirstWeeks = (1..19).toList(),
            ),

            // ── 教务按周返回那一族：同一门兜底课在 19 个周各出现一次 ⇒ 并条之后才计数 ──
            Case(
                name = "同一门兜底课重复 19 轮 ⇒ 并成一条，兜底计数 1（不是 19）",
                items = (1..19).map { course() },
                totalWeeks = 19,
                expectedCourses = 1,
                expectedFallbackWeekCourses = 1,
                expectedUnknownTeacherCourses = 1,
                expectedFirstWeeks = (1..19).toList(),
            ),

            // ── 片段有文字但一个数字都没有 ⇒ 不算给了周次信息，仍走兜底 ──
            Case(
                name = "cellDetail 只有「张三[主讲]」（没数字）⇒ 片段作废，走兜底",
                items = listOf(course("张三[主讲]")),
                totalWeeks = 19,
                expectedCourses = 1,
                expectedFallbackWeekCourses = 1,
                expectedUnknownTeacherCourses = 1,
                expectedFirstWeeks = (1..19).toList(),
            ),

            // ── 上界夹取：默认值之外，越界与 0 值都过 coerceIn ──
            Case(
                name = "totalWeeks=60 ⇒ 上界夹到 MAX_TOTAL_WEEKS=$MAX_WEEKS",
                items = listOf(course()),
                totalWeeks = 60,
                expectedCourses = 1,
                expectedFallbackWeekCourses = 1,
                expectedUnknownTeacherCourses = 1,
                expectedFirstWeeks = (1..MAX_WEEKS).toList(),
            ),
            Case(
                name = "totalWeeks=0 ⇒ 夹到 1，只补出第 1 周",
                items = listOf(course()),
                totalWeeks = 0,
                expectedCourses = 1,
                expectedFallbackWeekCourses = 1,
                expectedUnknownTeacherCourses = 1,
                expectedFirstWeeks = listOf(1),
            ),

            // ── 另一格静默（本卡不修，登记用）：给了周次片段但整段在学期范围外 ⇒ 这门课直接消失 ──
            Case(
                name = "「张三[31-40周]」全在界外 ⇒ 整门课被丢掉，兜底计数 0（这一档没人念）",
                items = listOf(course("张三[31-40周]")),
                totalWeeks = 19,
                expectedCourses = 0,
                expectedFallbackWeekCourses = 0,
                expectedUnknownTeacherCourses = 0,
                expectedFirstWeeks = null,
            ),
        )

        for (case in cases) {
            val outcome = outcomeOf(case)
            assertEquals("格子「${case.name}」课程条数", case.expectedCourses, outcome.courses.size)
            assertEquals(
                "格子「${case.name}」兜底周次条数（那句「已按整学期展示」的输入）",
                case.expectedFallbackWeekCourses,
                outcome.fallbackWeekCourses,
            )
            assertEquals(
                "格子「${case.name}」缺教师条数",
                case.expectedUnknownTeacherCourses,
                outcome.unknownTeacherCourses,
            )
            assertEquals(
                "格子「${case.name}」第一门课的周次",
                case.expectedFirstWeeks,
                outcome.courses.firstOrNull()?.weeks,
            )
        }
    }

    /**
     * 补出来的那一门：教师格写死 `"未知教师"` —— 它**不是 null**，而卡片格式与统计页那两路都按
     * 「不是 null 就当一位真人」处理（`CourseMetaFormat` / `StatsCourseMeta` 各自把哨兵折掉的）。
     * 本卡不改这一格（改它 = 改语义），但把它钉成格子，免得将来有人说"兜底那支没教师"。
     */
    @Test
    fun `补出来的那一门教师格写死未知教师 周次格是整学期`() {
        val outcome = BuaaScheduleParser.parseArrangedListOutcome(listOf(course()), TERM, 19)

        val fallback = outcome.courses.single()
        assertEquals("未知教师", fallback.teacher)
        assertEquals((1..19).toList(), fallback.weeks)
        assertEquals(1, outcome.fallbackWeekCourses)
        assertEquals(1, outcome.unknownTeacherCourses)
        // 两枚计数的包含关系：补出来的那门必然同时被算进"缺教师"，所以 unknown >= fallback
        assertTrue(outcome.unknownTeacherCourses >= outcome.fallbackWeekCourses)
    }

    /**
     * 预览页那两句话的**互斥形状**（逐字钉文案在守卫第 ③ 层，这里量它覆盖到哪、漏在哪）：
     * 第二枚 if 的条件是 `unknownTeacherCourses > 0 && fallbackWeekCourses == 0` ⇒ 只在"没有兜底课"时
     * 才念缺教师那一句。下面这批行里既有补出来的（1 门）又有"给了周次、教师格是哨兵"的（1 门）：
     * 兜底那句念 1，第二句整段不出现 ⇒ **那一门只缺教师的课在这一屏里没有任何一句替它说话**。
     * 这一格是本卡核读时量出来的第二处报数缺口（同属 #229 那一族；改它要动 `BuaaLoginScreen`，
     * 不在本卡白名单）。写在这里是让它有一枚可复算的落点，不改行为。
     */
    @Test
    fun `兜底与只缺教师混在一批时 第二句被第一句吃掉`() {
        val outcome = BuaaScheduleParser.parseArrangedListOutcome(
            listOf(
                course(), // 没给周次信息 ⇒ 补出来的一门
                course(day = 4, weeksAndTeachers = "未知教师[1-8周]"), // 给了周次、教师格是哨兵
            ),
            TERM,
            19,
        )

        assertEquals(2, outcome.courses.size)
        assertEquals(1, outcome.fallbackWeekCourses)
        assertEquals(2, outcome.unknownTeacherCourses)
        val firstSentenceSpoken = outcome.fallbackWeekCourses > 0
        val secondSentenceSpoken = outcome.unknownTeacherCourses > 0 && outcome.fallbackWeekCourses == 0
        assertEquals("第一句进、第二句被挡掉", true to false, firstSentenceSpoken to secondSentenceSpoken)
    }
}
