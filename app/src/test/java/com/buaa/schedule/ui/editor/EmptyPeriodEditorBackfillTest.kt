package com.buaa.schedule.ui.editor

import com.buaa.schedule.domain.model.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一门**没有节次**的课进编辑器时，「时间安排」那一组该回填什么、能不能保存、提示说什么。
 *
 * 旧写法在这里替用户编了一节课：`periodSegments.firstOrNull()?.first ?: 1` / `?: 2`
 * 于是 `periods = []` 的课一进编辑器就写着「开始节次 1 / 结束节次 2」，而 `canSave`
 * 要的恰好是 `periods.isNotEmpty()` —— `parsePeriods("1","2","")` 非空，于是保存按钮
 * 亮着。用户只是去改个教师名字，这门课就被存成 08:00 上第一节课（`ScheduleRepository`
 * 的 `normalize` 只会把空节次判为不可保存，可这里递给它的是编出来的 [1,2]，它拦不到）。
 * 入口是真实的：备份/分享口令里的 `"periods":[]`（T27，`BackupCourse.toDomain`）与
 * 库读路径的 `Converters.toIntList("")`（T27 的 `EmptyPeriodPlacementTest`）都产得出这种行。
 *
 * 口径与 T26/T27 一致：**缺的就是空着**，不许换个猜测值顶上；但「新建课程」不是
 * "已存在的课缺项"，那里给 1-2 节是起点而不是造假，所以两边分开各钉一条。
 *
 * 全部断言真调生产函数（`editorPeriodDraft` / `parsePeriods` / `editorCanSave` /
 * `sectionSupportingText`），不在测试里重拼一遍回填。
 */
class EmptyPeriodEditorBackfillTest {

    private fun course(
        name: String = "没有节次的课",
        periods: List<Int>,
        weeks: List<Int> = listOf(1, 2, 3),
    ) = Course(
        id = 20L,
        name = name,
        teacher = "赵敏",
        dayOfWeek = 6,
        periods = periods,
        weeks = weeks,
    )

    private val blank = course(periods = emptyList())

    /** 编辑器回填 → 保存时真正递给仓库的那串节次（这条链上唯一的造假日击点）。 */
    private fun savedPeriods(periods: List<Int>): List<Int> {
        val draft = editorPeriodDraft(course(periods = periods))
        return parsePeriods(draft.startSection, draft.endSection, draft.extraPeriods)
    }

    // —— 契约 2 + 3：没有节次就是空着，且存不进去 ——

    /**
     * 钉本体：空节次课的两格必须回填空串。改前是 "1" / "2"（`?: 1` / `?: 2`），这条为红。
     */
    @Test
    fun `空节次的课程两格回填空串而不是1和2`() {
        val draft = editorPeriodDraft(blank)
        assertEquals("没有节次就不该有开始节次，实测=${draft.startSection}", "", draft.startSection)
        assertEquals("没有节次就不该有结束节次，实测=${draft.endSection}", "", draft.endSection)
        assertEquals("", draft.extraPeriods)
        assertTrue("编辑器要能说出『这门课本身没有节次』", draft.courseHadNoPeriods)
    }

    /**
     * 钉写入路径：回填出来的三格经 `parsePeriods` 之后仍必须是空，且 `canSave` 必须是
     * false——否则「改个教师名字 + 保存」就把这门课造出一节 08:00 的课。
     * 改前 parsePeriods("1","2","") = [1,2] 非空、canSave 为 true，这条整体为红。
     */
    @Test
    fun `空节次的课程保存必须禁用`() {
        val draft = editorPeriodDraft(blank)
        val periods = parsePeriods(draft.startSection, draft.endSection, draft.extraPeriods)
        assertTrue("回填三格解析后必须还是空表，实测=$periods", periods.isEmpty())
        assertFalse(
            "没有节次时保存按钮必须灰着（现状靠 periods.isNotEmpty()，不许放宽）",
            editorCanSave(weeks = blank.weeks, periods = periods, saving = false),
        )
    }

    // —— 契约 6：新建课程是"给个起点"，与上面分开钉，两边互不牵连 ——

    @Test
    fun `新建课程照旧从第1到第2节起步`() {
        val draft = editorPeriodDraft(null)
        assertEquals("1", draft.startSection)
        assertEquals("2", draft.endSection)
        assertEquals("", draft.extraPeriods)
        assertFalse("新建不是『已存在的课缺节次』，别共用那条判据", draft.courseHadNoPeriods)
        assertTrue(
            editorCanSave(
                weeks = listOf(1, 2, 3),
                periods = parsePeriods(draft.startSection, draft.endSection, draft.extraPeriods),
                saving = false,
            ),
        )
    }

    // —— 契约 5：非空节次的行为逐字不变（含 T15 那条 P0）——

    /**
     * T15 的 P0：一门 1-2 + 9-10 的课，只改教师名字保存后不许被拉成 1..10 的连续块。
     * 顺带钉住几段回填的逐字形状。
     */
    @Test
    fun `非连续节次按段落回填不被拉成连续块`() {
        val draft = editorPeriodDraft(course(name = "高等数学 A", periods = listOf(1, 2, 9, 10)))
        assertEquals("1", draft.startSection)
        assertEquals("2", draft.endSection)
        assertEquals("9-10", draft.extraPeriods)
        assertFalse("改节次号相邻切段不许用时间表口径", draft.courseHadNoPeriods)
        assertEquals(listOf(1, 2, 9, 10), savedPeriods(listOf(1, 2, 9, 10)))
        assertFalse(
            "不许被拉成 1..10 连续块",
            savedPeriods(listOf(1, 2, 9, 10)) == (1..10).toList(),
        )
        // 逐字形状表：首段进两格，其余段拼成 parsePeriods 认得的文本
        val shapes = listOf(
            Shape(listOf(3), "3", "3", ""),
            Shape(listOf(5, 6), "5", "6", ""),
            Shape(listOf(1, 2, 4), "1", "2", "4"),
            Shape(listOf(9, 10, 11, 12), "9", "12", ""),
            Shape(listOf(1, 2, 3, 7, 8, 20), "1", "3", "7-8,20"),
            Shape(listOf(29, 30), "29", "30", ""),
        )
        for (shape in shapes) {
            val actual = editorPeriodDraft(course(name = "逐字形${shape.periods}", periods = shape.periods))
            assertEquals(
                "${shape.periods}：",
                "${shape.start}/${shape.end}/${shape.extra}",
                "${actual.startSection}/${actual.endSection}/${actual.extraPeriods}",
            )
            assertEquals(shape.periods, savedPeriods(shape.periods))
        }
    }

    private class Shape(
        val periods: List<Int>,
        val start: String,
        val end: String,
        val extra: String,
    )

    /**
     * 契约 5 要求的新守卫：把空节次的课**掺进**一批正常课里，其余课程的整串输出
     * 必须与不掺时逐字相同（形状同 `EmptyPeriodPlacementTest` 的"缺项整段缺席"）。
     * 改前空节次那门会凭空贡献一段 "1,2"，整串变长，这条为红。
     */
    @Test
    fun `掺进空节次课程时其余课程的回填整串逐字不变`() {
        val real = listOf(
            course(name = "高等数学 A", periods = listOf(3, 4)),
            course(name = "大学英语听说", periods = listOf(9, 10, 12)),
            course(name = "体育", periods = listOf(7)),
        )
        assertEquals("3,4;9,10,12;7", savedLineOf(real))
        assertEquals(savedLineOf(real), savedLineOf(real + blank))
        assertEquals(savedLineOf(real), savedLineOf(listOf(blank) + real))
        assertEquals(savedLineOf(real), savedLineOf(listOf(blank) + real + listOf(blank)))
        // 只剩这门课：整串是空的，而不是里面躺着一段假节次
        assertEquals("", savedLineOf(listOf(blank)))
    }

    /** 每门课一段（保存时会写进库的节次串），没有节次的那门连分隔符一起缺席。 */
    private fun savedLineOf(courses: List<Course>): String = courses
        .map { course ->
            val draft = editorPeriodDraft(course)
            parsePeriods(draft.startSection, draft.endSection, draft.extraPeriods).joinToString(",")
        }
        .filter { it.isNotEmpty() }
        .joinToString(";")

    /**
     * 全组合扫一遍：任何非空节次集合经"回填 → 解析"都必须原样回来，空集必须回到空集。
     * 这条既钉"正常课一格不挪"，也钉"缺项不许被补成 1-2"。带计数器，防空跑。
     */
    @Test
    fun `任意节次组合的往返恒等`() {
        val combos = mutableListOf<List<Int>>()
        val pool = (1..12).toList()
        for (a in pool.indices) {
            combos.add(listOf(pool[a]))
            for (b in a + 1 until pool.size) {
                combos.add(listOf(pool[a], pool[b]))
                for (c in b + 1 until pool.size) {
                    combos.add(listOf(pool[a], pool[b], pool[c]))
                }
            }
        }
        // 12 + C(12,2) + C(12,3)
        assertEquals(12 + 66 + 220, combos.size)
        val boundaries = listOf(
            listOf(1), listOf(30), listOf(1, 30), listOf(29, 30), listOf(1, 2, 3, 4, 5),
            listOf(2, 4, 6, 8, 10, 12, 14, 16), emptyList(),
        )
        var checked = 0
        var blanks = 0
        for (periods in combos + boundaries) {
            val roundTripped = savedPeriods(periods)
            assertEquals("$periods 经过编辑器不该变出别的节次：", periods, roundTripped)
            if (periods.isEmpty()) blanks++
            checked++
        }
        assertEquals("参数化测试退化：组合数没对上", combos.size + boundaries.size, checked)
        assertEquals("网格必须真扫到空节次那一路，否则这条守卫是空跑", 1, blanks)
    }

    // —— 契约 3 的闸门本体：不许放宽 periods.isNotEmpty() ——

    @Test
    fun `保存闸门的值域网格`() {
        val weeksGrid = listOf(emptyList(), listOf(1), listOf(1, 2, 3))
        val periodsGrid = listOf(
            emptyList(), listOf(1), listOf(1, 2), listOf(1, 2, 9, 10), listOf(30),
        )
        var checked = 0
        var emptyPeriodRows = 0
        for (weeks in weeksGrid) {
            for (periods in periodsGrid) {
                for (saving in listOf(false, true)) {
                    val canSave = editorCanSave(weeks = weeks, periods = periods, saving = saving)
                    if (periods.isEmpty()) emptyPeriodRows++
                    if (periods.isEmpty() || weeks.isEmpty() || saving) {
                        assertFalse(
                            "缺项组合不许能保存：weeks=$weeks periods=$periods saving=$saving",
                            canSave,
                        )
                    } else {
                        assertTrue(
                            "三项齐全的组合不许被误拦：weeks=$weeks periods=$periods",
                            canSave,
                        )
                    }
                    checked++
                }
            }
        }
        assertEquals(3 * 5 * 2, checked)
        assertEquals("网格必须真扫到 periods 为空的行", 3 * 2, emptyPeriodRows)
    }

    // —— 契约 4：提示不许误导 ——

    /**
     * 「这门课根本没有节次」不能套一句格式提示（那等于暗示"填个 1 就行"）。
     * 改前只有格式那一条分支，这条为红。
     */
    @Test
    fun `空节次课程的提示要说还没有节次`() {
        val hint = sectionSupportingText(text = "", orderReversed = false, courseHadNoPeriods = true)
        assertEquals("这门课还没有节次，请先选一节", hint)
        assertFalse("不能再是格式口吻：$hint", hint.contains("节次范围"))
        assertFalse("提示里不许出现节次数字冒充这门课的节次：$hint", hint.any { it.isDigit() })
    }

    /** 正常课程 / 用户自己清空 / 顺序反了：措辞逐字不变，也不许显示成 1、2。 */
    @Test
    fun `格式与顺序两条措辞逐字不变`() {
        assertEquals("节次范围 1–30", sectionSupportingText("", orderReversed = false, courseHadNoPeriods = false))
        assertEquals(
            "节次范围 1–30",
            sectionSupportingText("abc", orderReversed = false, courseHadNoPeriods = false),
        )
        assertEquals(
            "节次范围 1–30",
            sectionSupportingText("99", orderReversed = false, courseHadNoPeriods = false),
        )
        // 用户已经动手填过（哪怕来自空节次的课）就按格式口径说，不再替他描述这门课
        assertEquals(
            "节次范围 1–30",
            sectionSupportingText("99", orderReversed = false, courseHadNoPeriods = true),
        )
        assertEquals("结束需晚于开始", sectionSupportingText("1", orderReversed = true, courseHadNoPeriods = false))
        assertEquals("结束需晚于开始", sectionSupportingText("1", orderReversed = true, courseHadNoPeriods = true))
    }
}
