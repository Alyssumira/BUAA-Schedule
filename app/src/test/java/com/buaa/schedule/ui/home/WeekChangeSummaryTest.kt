package com.buaa.schedule.ui.home

import com.buaa.schedule.domain.model.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WeekChangeSummary] 的表驱动单测（纯 JVM，零 android、零时钟、零 Room）：
 * 周次异动摘要那句「比上周多 N 门」到底按什么数。
 *
 * 病灶是**单位错了**：旧版拿 `Course.id`（一条排课片段的主键）当"门"，而同段注释承诺
 * 「两段课只算一门」。教务最常见的形态「理论 1-8 周 + 实验 9-16 周」正是同门课的两段，
 * 于是第 9 周摘要念成「比上周多 1 门、少 1 门」，而按门的真相是没变。
 * 表格第 ① 档就是这一格，它是本卡存在的全部理由。
 *
 * 「门」的口径不另造：[com.buaa.schedule.domain.schedule.SemesterStats.courseGroupKey]
 * 是全仓唯一一份（学分归并、周次覆盖图、每日门数、趋势结课周都取它），本表直接拿它当尺。
 * ⚠️ 第 ⑦ 档钉的是**已知且刻意沿用**的粗口径 —— `sourceGroupKey == null` 时退化成"名字即身份"，
 * 两门同名手动课会并成 1 门。[com.buaa.schedule.domain.schedule.SemesterStats] 早已接受这一档，
 * 本卡不修它，只把它钉住别被后人当 bug 顺手"修好"（修了就等于给全站四台引擎对第二套账）。
 *
 * "HomeScreen 那一句有没有真的用上本判据"由 [WeekChangeSummaryWiringGuardTest] 钉；
 * 本文件只跑判据本身，复跑的是**结论**，不是装机：**未验到换周后那条横幅真的出现/消失**。
 *
 * 两侧都有格子（这一族的规矩，实测红档见本卡回执的变异自证表）：
 * - 朝**严**扭（门键换回 `course.id`，片段即身份）⇒ ①②④⑤⑦ 五档一起红：段数被数成门数，
 *   连"多 1 门"那种真异动也被翻倍报；
 * - 朝**松**扭（门键换成 `course.name.trim()`，名字即身份）⇒ ⑥⑧ 两档红：
 *   同名不同教学班、同名不同学期的课被并掉，真多出来的门念不出来。
 * 表格因此一次跑完、把所有红档一起交出去，不在第一档红时短路。
 */
class WeekChangeSummaryTest {

    private class Cell(
        val name: String,
        val courses: List<Course>,
        val thisWeek: Int,
        val lastWeek: Int,
        val added: Int,
        val removed: Int,
        /** 摘要文案；null = 这一条不显示 */
        val text: String?,
        val why: String,
    )

    /**
     * 表格一次跑完、把**所有**红档一起交出去（不是第一档红就短路）：
     * 变异自证要看清"哪几档红"，fail-fast 只会露出第一档，把同一支上的其余档藏起来。
     */
    @Test
    fun `每一档都按门数对异动 段数与名字都不许当尺`() {
        val red = mutableListOf<String>()
        for (cell in table) {
            val actual = WeekChangeSummary.counts(cell.courses, cell.thisWeek, cell.lastWeek)
            if (actual.added != cell.added || actual.removed != cell.removed) {
                red += "档位「${cell.name}」门数异动：期望 added=${cell.added} removed=${cell.removed}，" +
                    "实际 added=${actual.added} removed=${actual.removed} —— ${cell.why}"
            }
            val text = WeekChangeSummary.summaryText(cell.courses, cell.thisWeek, cell.lastWeek)
            if (text != cell.text) {
                red += "档位「${cell.name}」文案：期望「${cell.text}」，实际「${text}」 —— ${cell.why}"
            }
        }
        assertTrue(
            "十档表格里红了多少：期望 0 档，实际 ${red.size} 档\n" + red.joinToString("\n"),
            red.isEmpty(),
        )
    }

    /** [WeekChangeSummary.Counts] 那枚 `unchanged` 与文案的 null 档必须同真同假 */
    @Test
    fun `没动那一档文案与判据同时闭嘴`() {
        val red = mutableListOf<String>()
        for (cell in table) {
            val unchanged = WeekChangeSummary.counts(cell.courses, cell.thisWeek, cell.lastWeek).unchanged
            if (unchanged != (cell.text == null)) {
                red += "档位「${cell.name}」：unchanged=$unchanged 却配了文案「${cell.text}」——" +
                    "界面那条横幅的显隐与判据各说各话了"
            }
        }
        assertTrue(
            "显隐与文案配对的十档里有 ${red.size} 档不配对，期望 0 档\n" + red.joinToString("\n"),
            red.isEmpty(),
        )
    }

    /** 没有"上一周"可比的那几档一律不下结论：第 1 周之前不许念成"整学期都是新课" */
    @Test
    fun `周号缺失或没有上一周时不显示摘要`() {
        val lecture = listOf(fragment(1L, "讲座", "g1", listOf(2)))
        val alwaysOn = listOf(fragment(1L, "高等数学", "g1", (1..16).toList()))
        val cases = listOf(
            Silent("本周周号拿不到（未设学期）", null, 8, lecture),
            Silent("上周周号拿不到", 2, null, lecture),
            Silent("第 1 周没有上一周可比", 1, 0, lecture),
            Silent("周号越界（0 与 -1）", 0, -1, lecture),
            Silent("第 1 周且整学期都在上的课", 1, 0, alwaysOn),
        )
        for (cell in cases) {
            assertNull(
                "${cell.label}：这一档该整条不显示，实际念出「" +
                    "${WeekChangeSummary.summaryText(cell.courses, cell.thisWeek, cell.lastWeek)}」",
                WeekChangeSummary.summaryText(cell.courses, cell.thisWeek, cell.lastWeek),
            )
        }
        // 反面对照：那道闸只管关掉第 1 周及更早，第 2 周起异动照样要报得出来
        assertEquals(
            "第 2 周起摘要该照常成立（闸拧过头就会整学期念不出东西）",
            "比上周多 1 门",
            WeekChangeSummary.summaryText(lecture, 2, 1),
        )
    }

    private class Silent(
        val label: String,
        val thisWeek: Int?,
        val lastWeek: Int?,
        val courses: List<Course>,
    )

    private companion object {
        private val semester = "2026-2027-1"

        private fun weeks(range: IntRange): List<Int> = range.toList()

        /** 十档表格：每档交两个周号，读数是门数异动与那句文案；① 是病灶本体，⑦ 是刻意沿用的粗口径 */
        private val table = listOf(
            Cell(
                name = "①交接周：理论 1-8 + 实验 9-16 是同一门课",
                courses = listOf(
                    fragment(1L, "高等数学", "g1", weeks(1..8), dayOfWeek = 1),
                    fragment(2L, "高等数学", "g1", weeks(9..16), dayOfWeek = 3),
                ),
                thisWeek = 9,
                lastWeek = 8,
                added = 0,
                removed = 0,
                text = null,
                why = "病灶本体：两段各有各的 id，按 id 数会在第 9 周同时念「多 1 门、少 1 门」，" +
                    "而按门根本没变（同段注释那句「两段课只算一门」兑不了现）",
            ),
            Cell(
                name = "②真新增一门：另一门课从第 9 周才开",
                courses = listOf(
                    fragment(1L, "高等数学", "g1", weeks(1..8)),
                    fragment(2L, "高等数学", "g1", weeks(9..16), dayOfWeek = 3),
                    fragment(3L, "思想政治", "g2", weeks(9..16)),
                ),
                thisWeek = 9,
                lastWeek = 8,
                added = 1,
                removed = 0,
                text = "比上周多 1 门",
                why = "g1 的交接不计数、只有 g2 这门新课计数 ⇒ 药治了病灶还没把真异动一起吃掉",
            ),
            Cell(
                name = "③真结课一门：整门课只上到第 8 周",
                courses = listOf(
                    fragment(1L, "高等数学", "g1", weeks(1..16)),
                    fragment(2L, "体育", "g2", weeks(1..8)),
                ),
                thisWeek = 9,
                lastWeek = 8,
                added = 0,
                removed = 1,
                text = "比上周少 1 门",
                why = "g2 整门课（一个片段）都上完了，这一档本来就该念出来",
            ),
            Cell(
                name = "④两个方向同时动：一门结课 + 一门开课 + 一门交接",
                courses = listOf(
                    fragment(1L, "高等数学", "g1", weeks(1..8)),
                    fragment(2L, "高等数学", "g1", weeks(9..16), dayOfWeek = 3),
                    fragment(3L, "体育", "g2", weeks(1..8)),
                    fragment(4L, "形势与政策", "g3", weeks(9..16)),
                ),
                thisWeek = 9,
                lastWeek = 8,
                added = 1,
                removed = 1,
                text = "比上周多 1 门、少 1 门",
                why = "g1 交接不计，只计 g2 结课与 g3 开课 ⇒ 交接周混在真异动里也不污染读数",
            ),
            Cell(
                name = "⑤门数 ≠ 片段数：同一门课本周排两段、上周没排",
                courses = listOf(
                    fragment(1L, "大学英语", "g1", weeks(9..16), dayOfWeek = 1),
                    fragment(2L, "大学英语", "g1", weeks(9..16), dayOfWeek = 3),
                    fragment(3L, "高等数学", "g2", weeks(1..8)),
                ),
                thisWeek = 9,
                lastWeek = 8,
                added = 1,
                removed = 1,
                text = "比上周多 1 门、少 1 门",
                why = "g1 由两条片段并成 1 门：按 id 数会念成「多 2 门」，门才是这句话的单位",
            ),
            Cell(
                name = "⑥同名不同教学班：仍是两门",
                courses = listOf(
                    fragment(1L, "高等数学", "A班", weeks(9..16), dayOfWeek = 1),
                    fragment(2L, "高等数学", "B班", weeks(9..16), dayOfWeek = 3),
                    fragment(3L, "体育", "g2", weeks(1..8)),
                ),
                thisWeek = 9,
                lastWeek = 8,
                added = 2,
                removed = 1,
                text = "比上周多 2 门、少 1 门",
                why = "名字相同而组键不同是两个教学班，并成 1 门就是把真多出来的门念没了",
            ),
            Cell(
                name = "⑦已知粗口径：无组键时名字即身份，两门同名手动课并成 1 门",
                courses = listOf(
                    fragment(1L, "重修英语", null, weeks(9..16), dayOfWeek = 1),
                    fragment(2L, "重修英语", null, weeks(9..16), dayOfWeek = 3),
                ),
                thisWeek = 9,
                lastWeek = 8,
                added = 1,
                removed = 0,
                text = "比上周多 1 门",
                why = "SemesterStats 写下并接受的退化档（两条手动课程记录、门数只报 1）。" +
                    "本卡只沿用不修：这一档红了说明有人把口径拧回了片段即身份",
            ),
            Cell(
                name = "⑧跨学期码不并门：同名同组键也不是一门课",
                courses = listOf(
                    fragment(1L, "高等数学", "g1", weeks(9..16), semesterCode = "2025-2026-2"),
                    fragment(2L, "高等数学", "g1", weeks(9..16), semesterCode = semester),
                ),
                thisWeek = 9,
                lastWeek = 8,
                added = 2,
                removed = 0,
                text = "比上周多 2 门",
                why = "门键带学期前缀（见 SemesterStats.courseGroupKey），把上学期的重修课并进来就是少报",
            ),
            Cell(
                name = "⑨单周课：只上第 5 周那一门",
                courses = listOf(
                    fragment(1L, "高等数学", "g1", weeks(1..16)),
                    fragment(2L, "讲座", "g2", listOf(5)),
                ),
                thisWeek = 5,
                lastWeek = 4,
                added = 1,
                removed = 0,
                text = "比上周多 1 门",
                why = "③C-02 的原意之一：单周课那一次「多了一门」是真的，必须报得出",
            ),
            Cell(
                name = "⑩隔周课：本周轮到它上、上周不排",
                courses = listOf(
                    fragment(1L, "高等数学", "g1", weeks(1..16)),
                    fragment(2L, "实验课", "g2", (1..16).step(2).toList()),
                ),
                thisWeek = 5,
                lastWeek = 4,
                added = 1,
                removed = 0,
                text = "比上周多 1 门",
                why = "g2 只在单周出现，隔周就是异动；这一档不许被当成「课没了」抹平",
            ),
        )

        /**
         * 一条**排课片段**（不是一门课）：`id` 各不相同，正是病灶拿来当「门」的那一枚主键。
         * fixture 用同一个学期码，只有 ⑧ 那一档故意带两枚。
         */
        private fun fragment(
            id: Long,
            name: String,
            groupKey: String?,
            weeks: List<Int>,
            dayOfWeek: Int = 1,
            semesterCode: String = semester,
        ) = Course(
            id = id,
            name = name,
            dayOfWeek = dayOfWeek,
            periods = listOf(1, 2),
            weeks = weeks,
            sourceGroupKey = groupKey,
            semesterCode = semesterCode,
        )
    }
}
