package com.buaa.schedule.data.import

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T229 ①（#301）：教务导入的**节次缺失**必须与**星期缺失**同一把尺 —— 整条丢，不折成第 1 节。
 *
 * 纯 JVM 判据：[BuaaScheduleParser] 零 android、零时钟、零库 —— 喂进来的就是 DTO 原文，
 * 交回去的就是 Course 列表与三枚计数。设备侧事实（教务到底会不会送 `null beginSection`）
 * 不在这一格里，那一半登记在回执的「未验到」。
 *
 * ## 为什么这一格值得单独一枚文件（而不是并进 `PeriodRangeBoundsTest`）
 *
 * `PeriodRangeBoundsTest` 只管**上界**（溢出钳制、反序丢弃），它的夹具两枚参数都写成 `Int?`
 * 却从没打过 `(null, *)` —— 也就是「缺值」这一等在那把尺上根本不存在。本文件补的就是那一片，
 * 并把「缺 end 有 start 是**有出处**的单节」与「缺 start 是**凭空造**的第 1 节」这两等**并排摆**，
 * 因为把它们混成一条规则是这一族最容易复发的形状（卡面：同族已修三处，口径照它们）。
 *
 * ## 表驱动两侧格子
 *
 * 每一格都同时断言三样：**落库条数**、**落库的 periods**、**`missingSectionCourses` 计数**。
 * 只断言第一条的话，「整条丢」与「折成第 1 节再被上游过滤掉」看起来一样绿。
 *
 * ⚠️ 病灶那一档最狠的一形是「缺 beginSection 但 endSection 有值」那一格：改前
 * `start = beginSection ?: 1` 之后
 * `end = endSection ?: start` 收到的是**接口真送来的 5** ⇒ 展开成 `1..5` —— 不是凭空造一节，
 * 是凭空造**五节**。表里那一格就是照着这一形摆的。
 */
class BuaaMissingSectionDropTest {

    private companion object {
        const val TERM = "2026-2027-1"
    }

    private fun dto(
        dayOfWeek: Int?,
        beginSection: Int?,
        endSection: Int?,
        cells: List<String> = listOf("张三[1-8周]"),
    ) = BuaaCourseDto(
        courseName = "高等数学",
        courseCode = "MATH001",
        courseSerialNo = "01",
        dayOfWeek = dayOfWeek,
        beginSection = beginSection,
        endSection = endSection,
        placeName = "J3-101",
        campusName = "学院路",
        cellDetail = cells.map { CellDetailDto(text = it) },
    )

    /** 星期在、节次缺、end 也缺：改前落库成 periods = [1] */
    private val g1 = dto(dayOfWeek = 1, beginSection = null, endSection = null)

    /** 星期在、节次缺、end 有值：改前落库成 periods = [1,2,3,4,5]（凭空造**五**节） */
    private val g2 = dto(dayOfWeek = 1, beginSection = null, endSection = 5)

    /** 反方向的尺（不许被这次改动带跑）：缺星期整条丢，且**不进节次账** */
    private val g3 = dto(dayOfWeek = null, beginSection = 3, endSection = 4)

    /** 两样都缺 ⇒ 星期那一手先 return，节次账仍 0（一枚缺失只记一次，不重复计） */
    private val g4 = dto(dayOfWeek = null, beginSection = null, endSection = null)

    /** 正面对照：缺 end 而有 start 是**有出处**的单节，不属"凭空造"那一等 */
    private val g5 = dto(dayOfWeek = 1, beginSection = 3, endSection = null)

    /** 另一条既有的尺：反序钳制后空 periods ⇒ 整条丢，但它不算"缺节次" */
    private val g6 = dto(dayOfWeek = 1, beginSection = 9, endSection = 2)

    /** 完整行：既不掉课也不掉计数 */
    private val g7 = dto(dayOfWeek = 1, beginSection = 3, endSection = 4)

    // ─────────────── ① 逐格对账（表驱动） ───────────────

    @Test
    fun `缺节次那一等整条丢 且只有它进节次账`() {
        data class Cell(val name: String, val item: BuaaCourseDto, val kept: Int, val accounted: Int)

        val cells = listOf(
            Cell("缺 beginSection 且缺 endSection", g1, kept = 0, accounted = 1),
            Cell("缺 beginSection 但 endSection 有值", g2, kept = 0, accounted = 1),
            Cell("缺 dayOfWeek 而节次齐全", g3, kept = 0, accounted = 0),
            Cell("星期与节次同时缺", g4, kept = 0, accounted = 0),
            Cell("缺 endSection 但 beginSection 有值", g5, kept = 1, accounted = 0),
            Cell("节次反序", g6, kept = 0, accounted = 0),
            Cell("星期与节次都齐", g7, kept = 1, accounted = 0),
        )

        for (cell in cells) {
            val outcome = BuaaScheduleParser.parseArrangedListOutcome(listOf(cell.item), TERM, 20)
            assertEquals(
                "【${cell.name}】落库条数：期望 ${cell.kept}，实到 ${outcome.courses.size}（" +
                    "periods = ${outcome.courses.map { it.periods }}）",
                cell.kept,
                outcome.courses.size,
            )
            assertEquals(
                "【${cell.name}】missingSectionCourses：期望 ${cell.accounted}，" +
                    "实到 ${outcome.missingSectionCourses}",
                cell.accounted,
                outcome.missingSectionCourses,
            )
        }
    }

    @Test
    fun `造出来的那一节不许以第 1 节的形式留在盘上`() {
        // 病灶的正身：改前这两格都会交出含第 1 节的行（g1 → [1]，G2 → [1,2,3,4,5]），
        // 而接口从没说过这节课上第 1 节。断言"一个字节都没有"，而不是"条数少一点"。
        for (item in listOf(g1, g2)) {
            val outcome = BuaaScheduleParser.parseArrangedListOutcome(listOf(item), TERM, 20)
            val firstPeriodRows = outcome.courses.filter { 1 in it.periods }
            assertEquals(
                "缺 beginSection 的行不许产出任何「第 1 节」的课：" +
                    "实到 periods = ${outcome.courses.map { it.periods }}",
                emptyList<Any>(),
                firstPeriodRows,
            )
        }
    }

    @Test
    fun `薄壳与 Outcome 版两路同尺`() {
        // 静默刷新那一路走 parseArrangedList（丢计数），首次导入走 Outcome 版。
        // 卡面问「两路行为是否要一起改」：落点在 Outcome 版的循环体里、薄壳只是委派 ⇒
        // 一次改动两路同时生效。这一格钉的是"两路一致"这件事实，改前它同样成立（病灶在两路都成立），
        // 所以它**不是**这次修红的格子，而是"以后只改一路就会红"的那一把尺。
        for (item in listOf(g1, g2)) {
            val viaList = BuaaScheduleParser.parseArrangedList(listOf(item), TERM, 20)
            val viaOutcome = BuaaScheduleParser.parseArrangedListOutcome(listOf(item), TERM, 20)
            assertEquals(
                "薄壳与 Outcome 版必须交出同一份 courses（一条都不许多、一条都不许少）",
                viaOutcome.courses,
                viaList,
            )
            assertTrue("两路都必须是零落库，实到 ${viaList.size}", viaList.isEmpty())
        }
    }

    // ─────────────── ② 计数与落库的账对得上 ───────────────

    @Test
    fun `一批里混着缺的 落库数与丢弃数各归各`() {
        val outcome = BuaaScheduleParser.parseArrangedListOutcome(
            listOf(g7, g1, g2, g3, g7),
            TERM,
            20,
        )

        assertEquals(
            "5 条进来 ⇒ 落的只有星期与节次都齐的那两门（g7 两行身份键相同、周次也相同 ⇒ 并成 1 条），" +
                "实到 ${outcome.courses.size} 条 / periods ${outcome.courses.map { it.periods }}",
            1,
            outcome.courses.size,
        )
        assertEquals("g1 + G2 两枚进节次账", 2, outcome.missingSectionCourses)
        assertEquals(
            "缺星期那一枚（g3）**不许**同时进节次账：一把尺只记一次，同一行在两把尺上被数两遍" +
                "会让报数的总数大于输入条数（实到 " + outcome.missingSectionCourses + "，只该数到 g1/G2）",
            2,
            outcome.missingSectionCourses,
        )
        assertEquals("g7 带着教师 ⇒ 教师账与周次账都不许涨", 0, outcome.unknownTeacherCourses)
        assertEquals("g7 带着周次片段 ⇒ 兜底周次账也不许涨", 0, outcome.fallbackWeekCourses)
    }

    @Test
    fun `片段展开之前就被丢掉 每个教师片段一枚都不留`() {
        // 一条记录带 2 个 `教师[周次]` 片段：改前缺 beginSection 会产出**两行**假课
        // （片段循环在节次检查之后），修完必须一行都不剩。
        val twoFragments = dto(
            dayOfWeek = 2,
            beginSection = null,
            endSection = null,
            cells = listOf("张三[1-8周]", "李四[9-16周]"),
        )

        val outcome = BuaaScheduleParser.parseArrangedListOutcome(listOf(twoFragments), TERM, 20)

        assertEquals("两个片段都得跟着整条丢掉", 0, outcome.courses.size)
        assertEquals("而账上只记**一条教务记录**，不是两条", 1, outcome.missingSectionCourses)
        assertEquals("教师账不许跟着涨", 0, outcome.unknownTeacherCourses)
    }

    @Test
    fun `默认 totalWeeks 那一档也一样丢`() {
        // 省着不传 totalWeeks（走签名默认 20）的那一路不许有第二套节次判据。
        val outcome = BuaaScheduleParser.parseArrangedListOutcome(listOf(g1), TERM)
        assertEquals(0, outcome.courses.size)
        assertEquals(1, outcome.missingSectionCourses)
    }
}
