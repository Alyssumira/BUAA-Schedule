package com.buaa.schedule.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 假期/调休徽标判据内核 [SpecialDayBadgePolicy] 的表驱动单测（T62①）。
 *
 * 这张表能存在，全部意义就在于判据是纯的：日期是一枚整数键、周末是一个布尔、
 * 标注是一个不带 `LocalDate` 的列表。真机那侧「9/25 中秋该出休、10/1 出休、
 * 补班的那个周六出班而不是休」三件事，以前只能靠肉眼在周课表的七列里找一个 20dp 的字，
 * 而它现在在这里逐支打表 —— 因为同一条判据马上要被三处界面共用（周表头 / 今日页 / 顶栏），
 * 一处判错就是三处各错各的。
 */
class SpecialDayBadgePolicyTest {

    // ---- 日期键与周末布尔 ----

    @Test
    fun dateKeySeparatesEveryRealDate() {
        val cases = listOf(
            Triple(2026, 9, 25) to 20260925,
            Triple(2026, 10, 1) to 20261001,
            Triple(2026, 1, 2) to 20260102,
            Triple(2026, 11, 20) to 20261120,
            // 月/日互换必须得到不同的键：这枚键只做等值匹配，撞了就是一天挂上另一天的标注
            Triple(2026, 5, 9) to 20260509,
        )
        cases.forEach { (ymd, expected) ->
            val (y, m, d) = ymd
            assertEquals("$y-$m-$d 的键不对：", expected, specialDayDateKey(y, m, d))
        }
        assertTrue("同月同日不同年必须分开：", specialDayDateKey(2026, 9, 25) != specialDayDateKey(2025, 9, 25))
    }

    @Test
    fun weekendFlagFollowsIsoDayNumber() {
        // ISO：周一=1…周日=7。周末口径只有六、日两档，其余五档都得是 false
        (1..5).forEach { dow -> assertFalse("星期 $dow 被判成周末：", specialDayIsWeekend(dow)) }
        (6..7).forEach { dow -> assertTrue("星期 $dow 没被判成周末：", specialDayIsWeekend(dow)) }
    }

    // ---- ① 没有标注就不画 ----

    @Test
    fun noMarkMeansNoBadgeEvenOnWeekend() {
        val saturday = specialDayDateKey(2026, 9, 26)
        assertNull("普通周末凭空获得一枚「休」：", specialDayBadgeAt(saturday, emptyList(), isWeekend = true))
        assertNull(
            "标注列表里没有这一天却还是画了：",
            specialDayBadgeAt(saturday, listOf(mark(20260925, isHoliday = true)), isWeekend = true),
        )
        // 周末布尔为 false 时同样不许无中生有：工作日没标注就是没标注
        assertNull(specialDayBadgeAt(20261012, emptyList(), isWeekend = false))
    }

    // ---- ② 单一标注：休 / 班 ----

    @Test
    fun singleMarkProducesItsOwnBadge() {
        val data = listOf(
            // 中秋（周五）
            Triple(20260925, false, SpecialDayBadgeKind.Holiday),
            // 国庆 10/1（周四）
            Triple(20261001, false, SpecialDayBadgeKind.Holiday),
            // 国庆后那个周六补班
            Triple(20261010, true, SpecialDayBadgeKind.Workday),
        )
        data.forEach { (dateKey, weekend, expected) ->
            val mark = mark(dateKey, isHoliday = expected == SpecialDayBadgeKind.Holiday, note = "某节日")
            val badge = specialDayBadgeAt(dateKey, listOf(mark), isWeekend = weekend)
            assertEquals("$dateKey（周末=$weekend）该挂的那枚不对：", expected, badge?.kind)
            assertEquals("徽标文案只能出自枚举那一份：", expected.label, badge?.kind?.label)
            assertEquals("note 得原样带回去，摆不摆由版面定：", "某节日", badge?.note)
        }
    }

    @Test
    fun badgeLabelsAreExactlyRestAndWork() {
        assertEquals("休", SpecialDayBadgeKind.Holiday.label)
        assertEquals("班", SpecialDayBadgeKind.Workday.label)
        assertEquals("两枚徽标不许同名：", 2, SpecialDayBadgeKind.entries.map { it.label }.distinct().size)
    }

    // ---- ③ 周末 + 班：这一枚是本卡的存在理由 ----

    @Test
    fun makeupWorkdayOnWeekendStillShows() {
        // 2026-10-10 是周六：这一天数据里挂的是「班」，界面上必须看得见班
        val saturdayMark = mark(20261010, isHoliday = false, note = "国庆节调休上班")
        val badge = specialDayBadgeAt(20261010, listOf(saturdayMark), isWeekend = true)
        assertEquals("周末的补班日被吞掉了：", SpecialDayBadgeKind.Workday, badge?.kind)
        assertEquals("周末补班那格的文案：", "班", specialDayBadgeLabel(badge!!))
        assertEquals("带说明那一档：", "班· 国庆节调休上班", specialDayBadgeLabel(badge, withNote = true))
        // 同一个标注落在工作日（布尔传错）也得照样出班：布尔只管冲突时的取舍，不是开关
        assertEquals(
            "周末布尔把「班」的显示权管得太宽：",
            SpecialDayBadgeKind.Workday,
            specialDayBadgeAt(20261010, listOf(saturdayMark), isWeekend = false)?.kind,
        )
    }

    // ---- ④ 同一日期重复（同种）：只出一枚，note 取第一条带字的 ----

    @Test
    fun duplicateMarksCollapseToOneBadge() {
        val dupes = listOf(
            mark(20261001, isHoliday = true, note = null),
            mark(20261001, isHoliday = true, note = "   "),
            mark(20261001, isHoliday = true, note = "国庆节"),
            mark(20261001, isHoliday = true, note = "中华人民共和国国庆"),
        )
        val badge = specialDayBadgeAt(20261001, dupes, isWeekend = false)
        assertEquals("重复标注只该出一枚：", SpecialDayBadgeKind.Holiday, badge?.kind)
        assertEquals("空白 note 不算一条 note：", "国庆节", badge?.note)
        // 顺序换了，取的还是"第一条带字的"而不是"最后一条"：方向定死，三处界面才读得出同一句
        val reversed = dupes.reversed()
        assertEquals(
            "同一批重复条目的 note 取向随列表顺序漂了：",
            "中华人民共和国国庆",
            specialDayBadgeAt(20261001, reversed, isWeekend = false)?.note,
        )
    }

    // ---- ⑤ 同一日期既休又班：周末判班、工作日判休 ----

    @Test
    fun conflictingMarksResolveByWeekendFlag() {
        val rest = mark(20261010, isHoliday = true, note = "重阳节")
        val work = mark(20261010, isHoliday = false, note = "国庆节调休上班")
        // 周末：调休只会落在周末，这句"要上课"后果最重，必须赢
        val weekendBadge = specialDayBadgeAt(20261010, listOf(rest, work), isWeekend = true)
        assertEquals("周末那天的冲突没判成班：", SpecialDayBadgeKind.Workday, weekendBadge?.kind)
        assertEquals("赢的那一枚才带自己的 note：", "国庆节调休上班", weekendBadge?.note)
        // 顺序反过来一样：结论只能由判据给，不能由数据侧的数组顺序给
        assertEquals(
            "冲突取舍跟着输入顺序漂：",
            weekendBadge,
            specialDayBadgeAt(20261010, listOf(work, rest), isWeekend = true),
        )
        // 工作日（2026-09-25 是周五）：两条打架时按节假日显示，宁可白欢喜不误了课
        val fridayRest = mark(20260925, isHoliday = true, note = "中秋节")
        val fridayWork = mark(20260925, isHoliday = false, note = "调休上班")
        val weekdayBadge = specialDayBadgeAt(20260925, listOf(fridayRest, fridayWork), isWeekend = false)
        assertEquals("工作日那天的冲突没判成休：", SpecialDayBadgeKind.Holiday, weekdayBadge?.kind)
        assertEquals("中秋节", weekdayBadge?.note)
    }

    // ---- ⑥ 文案拼法只有一份 ----

    @Test
    fun labelWordingIsOwnedByTheKernel() {
        val noNote = SpecialDayBadge(SpecialDayBadgeKind.Holiday, note = null)
        val withNote = SpecialDayBadge(SpecialDayBadgeKind.Holiday, note = "中秋节")
        val blankNote = SpecialDayBadge(SpecialDayBadgeKind.Workday, note = "  ")
        assertEquals("无 note 那一档开不开说明都只有一个字：", "休", specialDayBadgeLabel(noNote, withNote = true))
        assertEquals("休", specialDayBadgeLabel(noNote))
        assertEquals("中秋节", withNote.note)
        assertEquals("带说明：", "休· 中秋节", specialDayBadgeLabel(withNote, withNote = true))
        assertEquals("空白 note 不许拼出悬空分隔符：", "班", specialDayBadgeLabel(blankNote, withNote = true))
        assertFalse("说明里不许出现英文占位：", specialDayBadgeLabel(withNote, withNote = true).contains("null"))
    }

    private fun mark(dateKey: Int, isHoliday: Boolean, note: String? = null) =
        SpecialDayMark(dateKey = dateKey, isHoliday = isHoliday, note = note)
}
