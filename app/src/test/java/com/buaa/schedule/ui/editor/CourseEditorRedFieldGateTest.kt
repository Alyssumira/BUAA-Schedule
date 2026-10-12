package com.buaa.schedule.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T321②：编辑器「逐格判红」与「保存闸门」的表驱动单测（台账 #499 = #379 升档，甲档）。
 *
 * 纯 JVM：真调 [editorFieldVerdict] 与 [editorCanSave]，期望值**逐格手写**，
 * 没有一格用被测函数自己算期望（本仓在册教训：期望由被测函数生成 = 守卫替病灶作证）。
 *
 * 四段：
 * 1. [判红名单逐格表驱动]：六格的红口径（与改前 `CourseEditorScreen` 就地算的那六枚 Boolean
 *    逐条同口径），含边界 0 / 1 / 30 / 31 / 240 / 241、`"3,5"`、`"1e308"`、带空白的数值。
 * 2. [单改一格只咬自己那一格]：基线全合法，每次只动一格 ⇒ 红名单恰好多出那一枚。
 *    这一段拦的是"红名单串味"（一红全红 = 按不下去却没人说是哪一格）。
 * 3. [闸门四项合取表驱动]：weeks × periods × saving × redFields 的组合逐格写死期望。
 * 4. [三格病灶在甲档下按不下去]：⓪ 那枚改前探针的翻面 —— 同一批输入，递了红名单就按不下去。
 *    末了一格 [默认值口子…] 把"调用点不递红名单 ⇒ 闸门照旧朝宽放开"登记成读数：
 *    判据写得再对，调用点不递就是第二本账（那一格由 ③ 的接线守卫负责，W 臂扭的就是它）。
 */
class CourseEditorRedFieldGateTest {

    /** 表驱动用的取样器：默认值就是"这一格不掺和本次改动"，只让被测那一枚动。 */
    private fun verdict(
        start: String = "1",
        end: String = "2",
        extra: String = "",
        weeks: List<Int> = listOf(1, 2, 3),
        credit: String = "",
        advance: String = "10",
    ): EditorFieldVerdict = editorFieldVerdict(
        startSection = start,
        endSection = end,
        extraPeriods = extra,
        weeks = weeks,
        creditText = credit,
        advanceMinutes = advance,
    )

    // ─────────────── ① 判红名单本体 ───────────────

    private data class Row(
        val start: String,
        val end: String,
        val extra: String,
        val weeks: List<Int>,
        val credit: String,
        val advance: String,
        val expectedRed: Set<EditorRedField>,
        val expectedOrderReversed: Boolean,
        val expectedCreditNumber: Double?,
        val why: String,
    )

    @Test
    fun `判红名单逐格表驱动`() {
        val rows = listOf(
            Row("1", "2", "", listOf(1, 2, 3), "", "10", emptySet(), false, null,
                "全合法：一格不红"),
            Row("", "", "", listOf(1), "", "", setOf(EditorRedField.StartSection, EditorRedField.EndSection), false, null,
                "两格都空着：各咬自己那一枚（这两档改前就被 periods 空拦着，红与闸门自此同名）"),
            Row("0", "5", "", listOf(1), "", "0", setOf(EditorRedField.StartSection), false, null,
                "开始节次 0 越下界；提前量 0 是合法端点"),
            Row("1", "30", "", listOf(30), "", "240", emptySet(), false, null,
                "节次上界 30 / 提前量上界 240 都是闭区间端点"),
            Row("31", "31", "", listOf(1), "", "241",
                setOf(EditorRedField.StartSection, EditorRedField.EndSection, EditorRedField.Advance), false, null,
                "节次两格与提前量各越上界一枚"),
            Row("5", "2", "", listOf(1), "", "10", setOf(EditorRedField.EndSection), true, null,
                "结束早于开始：只咬结束那一格，orderReversed 供措辞分档"),
            Row("2", "02", "", listOf(1), "", "10", emptySet(), false, null,
                "前导零不参与判红（trim 后同一枚数）"),
            Row(" 2 ", " 3 ", "", listOf(1), "", " 15 ", emptySet(), false, null,
                "首尾空白不算坏值"),
            Row("abc", "xyz", "", listOf(1), "", "abc",
                setOf(EditorRedField.StartSection, EditorRedField.EndSection, EditorRedField.Advance), false, null,
                "非数字三格各判各的"),
            Row("1", "2", "abc", listOf(1), "", "10", setOf(EditorRedField.ExtraPeriods), false, null,
                "额外节次填了解析为空 ⇒ 红（改前这一格红着却能保存，病灶本体）"),
            Row("1", "2", "9-10", listOf(1), "", "10", emptySet(), false, null,
                "额外节次是可选的：解析得出合法区间就不红"),
            Row("1", "2", "1,0", listOf(1), "", "10", emptySet(), false, null,
                "段里那枚 0 被 WeekParser 的 1..MAX_WEEK 滤掉，剩下的仍非空 ⇒ 不判红"),
            Row("1", "2", "99", listOf(1), "", "10", setOf(EditorRedField.ExtraPeriods), false, null,
                "整段越界到解析为空 ⇒ 判红"),
            Row("1", "2", "", emptyList(), "", "10", setOf(EditorRedField.Weeks), false, null,
                "周次解析为空"),
            Row("1", "2", "", listOf(1, 2, 3), "3,5", "10", setOf(EditorRedField.Credit), false, null,
                "学分逗号：屏上有 3,5、库里会是 null ⇒ 必须红"),
            Row("1", "2", "", listOf(1, 2, 3), "3.5", "10", emptySet(), false, 3.5,
                "北航最常见的那枚小数学分：合法，且把数值原样交回保存链"),
            Row("1", "2", "", listOf(1, 2, 3), "-1", "10", setOf(EditorRedField.Credit), false, -1.0,
                "负学分越下界：红名单咬学分；creditNumber 仍如实交回（归一化不是内核的活）"),
            Row("1", "2", "", listOf(1, 2, 3), "1e308", "10", setOf(EditorRedField.Credit), false, 1.0E308,
                "有限却远超 MAX_CREDIT：红（统计页求和被它算成无穷大那一族）"),
            Row("1", "2", "", listOf(1, 2, 3), " 4 ", "10", emptySet(), false, 4.0,
                "学分首尾空白不算坏值"),
            Row("1", "2", "", listOf(1, 2, 3), "", "999", setOf(EditorRedField.Advance), false, null,
                "提前分钟 999：改前静默 coerceIn 成 240（不是那句过期注释写的 60）"),
            Row("1", "2", "", listOf(1, 2, 3), "", "-1", setOf(EditorRedField.Advance), false, null,
                "提前分钟负数越下界"),
            Row("1", "2", "", listOf(1, 2, 3), "", "", emptySet(), false, null,
                "两格都留空：可选就是可选，不许判红"),
        )
        var checked = 0
        for (row in rows) {
            val actual = verdict(
                start = row.start,
                end = row.end,
                extra = row.extra,
                weeks = row.weeks,
                credit = row.credit,
                advance = row.advance,
            )
            assertEquals("${row.why}：红名单", row.expectedRed, actual.redFields)
            assertEquals("${row.why}：anyRed 与红名单必须同翻", row.expectedRed.isNotEmpty(), actual.anyRed)
            assertEquals("${row.why}：orderReversed", row.expectedOrderReversed, actual.orderReversed)
            assertEquals("${row.why}：creditNumber", row.expectedCreditNumber, actual.creditNumber)
            checked++
        }
        assertEquals("参数化退化：格子数没对上", rows.size, checked)
    }

    // ─────────────── ② 红名单不许串味 ───────────────

    private fun assertSingleRed(field: String, actual: EditorFieldVerdict, expected: EditorRedField) {
        assertEquals(
            "只改 $field 一枚输入：红名单只许多出 $expected（实到 ${actual.redFields}）",
            setOf(expected),
            actual.redFields,
        )
        assertTrue("$field 之后 isRed($expected) 必须为真", actual.isRed(expected))
        assertTrue("$field 之后 anyRed 必须跟着翻", actual.anyRed)
    }

    @Test
    fun `单改一格只咬自己那一格`() {
        val baseline = verdict()
        assertEquals("基线一格不红", emptySet<EditorRedField>(), baseline.redFields)
        assertFalse("基线 anyRed", baseline.anyRed)
        assertSingleRed("startSection", verdict(start = "abc"), EditorRedField.StartSection)
        assertSingleRed("endSection", verdict(end = ""), EditorRedField.EndSection)
        assertSingleRed("extraPeriods", verdict(extra = "abc"), EditorRedField.ExtraPeriods)
        assertSingleRed("weeks", verdict(weeks = emptyList()), EditorRedField.Weeks)
        assertSingleRed("creditText", verdict(credit = "3,5"), EditorRedField.Credit)
        assertSingleRed("advanceMinutes", verdict(advance = "999"), EditorRedField.Advance)
    }

    // ─────────────── ③ 闸门四项合取 ───────────────

    private data class GateRow(
        val weeks: List<Int>,
        val periods: List<Int>,
        val saving: Boolean,
        val redFields: Set<EditorRedField>,
        val expected: Boolean,
    )

    @Test
    fun `闸门四项合取表驱动`() {
        val rows = listOf(
            // weeks 为空那一支：四档无论如何都不许放行
            GateRow(emptyList(), listOf(1), false, emptySet(), false),
            GateRow(emptyList(), listOf(1), false, setOf(EditorRedField.Credit), false),
            GateRow(emptyList(), listOf(1), true, emptySet(), false),
            GateRow(emptyList(), listOf(1), true, setOf(EditorRedField.Credit), false),
            GateRow(emptyList(), emptyList(), false, emptySet(), false),
            GateRow(emptyList(), emptyList(), false, setOf(EditorRedField.Credit), false),
            GateRow(emptyList(), emptyList(), true, emptySet(), false),
            GateRow(emptyList(), emptyList(), true, setOf(EditorRedField.Credit), false),
            // periods 为空那一支（T28 那一族：不许放宽）
            GateRow(listOf(1), emptyList(), false, emptySet(), false),
            GateRow(listOf(1), emptyList(), false, setOf(EditorRedField.Credit), false),
            GateRow(listOf(1), emptyList(), true, emptySet(), false),
            // saving 那一支（T260 那一族：不许放宽）
            GateRow(listOf(1), listOf(1), true, emptySet(), false),
            GateRow(listOf(1), listOf(1), true, setOf(EditorRedField.Credit), false),
            // 本卡新增的那一枚合取：三项齐全却有一格判红 ⇒ 按不下去
            GateRow(listOf(1), listOf(1), false, setOf(EditorRedField.Credit), false),
            GateRow(listOf(1), listOf(1), false, setOf(EditorRedField.Advance), false),
            GateRow(listOf(1), listOf(1), false, setOf(EditorRedField.ExtraPeriods), false),
            GateRow(listOf(1), listOf(1), false, setOf(EditorRedField.Weeks), false),
            GateRow(listOf(1), listOf(1), false, setOf(EditorRedField.StartSection), false),
            GateRow(listOf(1), listOf(1), false, setOf(EditorRedField.EndSection), false),
            GateRow(
                listOf(1), listOf(1), false,
                setOf(EditorRedField.Credit, EditorRedField.Advance, EditorRedField.ExtraPeriods), false,
            ),
            // 三格合法档：朝宽不许误杀
            GateRow(listOf(1), listOf(1), false, emptySet(), true),
            GateRow(listOf(1, 2, 3), listOf(1, 2, 9, 10), false, emptySet(), true),
            GateRow(listOf(1, 2), listOf(30), false, emptySet(), true),
            // 边界与合法档交叉：合法 weeks/periods 也照样被红名单咬住
            GateRow(listOf(1, 2, 3), listOf(1, 2, 9, 10), false, setOf(EditorRedField.Credit), false),
            GateRow(listOf(1, 2, 3), listOf(1, 2, 9, 10), true, setOf(EditorRedField.Advance), false),
        )
        var legalCells = 0
        for (row in rows) {
            val actual = editorCanSave(
                weeks = row.weeks,
                periods = row.periods,
                saving = row.saving,
                redFields = row.redFields,
            )
            assertEquals(
                "weeks=${row.weeks} periods=${row.periods} saving=${row.saving} red=${row.redFields}",
                row.expected,
                actual,
            )
            if (row.expected) legalCells++
        }
        assertEquals("三项齐全的合法档必须真扫到（三格），否则这张表只会替『越拦越严』作证", 3, legalCells)
        assertEquals("表体枚数不许悄悄变", 25, rows.size)
    }

    // ─────────────── ④ 三格病灶的翻面：红 ⇒ 按不下去 ───────────────

    @Test
    fun `学分判红时保存按钮按不下去`() {
        val actual = verdict(credit = "3,5")
        assertTrue("学分格判红", actual.isRed(EditorRedField.Credit))
        assertFalse(
            "甲档：红 ⇒ 按不下去（改前这一档按钮亮着，落库的是 normalizeCredit 交回的 null）",
            editorCanSave(weeks = listOf(1, 2, 3), periods = listOf(1, 2), saving = false, redFields = actual.redFields),
        )
    }

    @Test
    fun `提前分钟判红时保存按钮按不下去`() {
        val actual = verdict(advance = "999")
        assertTrue("提前分钟格判红", actual.isRed(EditorRedField.Advance))
        assertFalse(
            "甲档：改前这一档按钮亮着，落库的是 coerceIn 之后的 240",
            editorCanSave(weeks = listOf(1, 2, 3), periods = listOf(1, 2), saving = false, redFields = actual.redFields),
        )
    }

    @Test
    fun `额外节次判红时保存按钮按不下去`() {
        val actual = verdict(extra = "abc")
        assertTrue("额外节次格判红", actual.isRed(EditorRedField.ExtraPeriods))
        val periods = parsePeriods("1", "2", "abc")
        assertEquals("parsePeriods 交回的只剩首段（用户看到的那段被丢掉）", listOf(1, 2), periods)
        assertFalse(
            "甲档：改前 periods 非空就把按钮放开、把红框里那段静默丢掉",
            editorCanSave(weeks = listOf(1, 2, 3), periods = periods, saving = false, redFields = actual.redFields),
        )
    }

    /**
     * 默认值口子那一格读数：`redFields` 不递时闸门照旧朝宽放开。
     *
     * 这一格**不是**在说"这样也行"，它登记的是 W 臂的靶子 —— 生产调用点一旦不递红名单，
     * 行为就退回基点，而本文件（连同所有直接调内核的表驱动）一枚都抓不到：
     * 唯一抓得住的是 `CourseEditorRedFieldGateWiringGuardTest`。
     */
    @Test
    fun `默认值口子 调用点不递红名单时闸门朝宽放开`() {
        val actual = verdict(credit = "3,5", advance = "999")
        assertEquals("红名单实到两枚", setOf(EditorRedField.Credit, EditorRedField.Advance), actual.redFields)
        assertTrue(
            "不递红名单 ⇒ 与基点同宽（「判据写得再对，调用点不递就是第二本账」的形状）",
            editorCanSave(weeks = listOf(1, 2, 3), periods = listOf(1, 2), saving = false),
        )
        assertFalse(
            "递了 ⇒ 甲档生效",
            editorCanSave(weeks = listOf(1, 2, 3), periods = listOf(1, 2), saving = false, redFields = actual.redFields),
        )
    }
}
