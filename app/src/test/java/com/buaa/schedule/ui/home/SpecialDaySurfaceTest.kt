package com.buaa.schedule.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页头版面判据 [SpecialDaySurface] 的表驱动单测（T62③）。
 *
 * 这张表钉的是"先量后摆"的那笔账：真机上页头宽度随箭头、系统字号、翻到的那天变，
 * 五档版面（恰好放下 / 差一点点 / 完全放不下 / 无说明 / 说明超长）靠肉眼换字号去撞
 * 一辈子也撞不齐，在这里只是七个 Double 的组合。数值取自装机口径：
 * 中间列 ~230dp、labelMedium 下一个汉字 ~11dp、「今天 · 第 4 周」实测 ~93dp。
 */
class SpecialDaySurfaceTest {

    // ---- ① 没有数据：None，页头一个字节都不许多出来 ----

    @Test
    fun noDataYieldsNone() {
        assertEquals(
            "没有标注的那天该走 None：",
            SpecialDayHeaderSurface.None,
            surface(badgeText = null),
        )
        assertEquals(
            "空白徽标不算标注：",
            SpecialDayHeaderSurface.None,
            surface(badgeText = "   "),
        )
        // None 与"有数据但装不下"是两档：后者仍要交出本体
        assertTrue(
            "有标注时永远不许回 None：",
            surface(badgeText = "休", fullText = "休· 中秋节", availableWidthDp = 1.0)
                !is SpecialDayHeaderSurface.None,
        )
    }

    // ---- ② 整句放得下：Full（含边界取等） ----

    @Test
    fun fullSentenceWinsWhenItFits() {
        assertEquals(
            "宽裕时该摆整句：",
            SpecialDayHeaderSurface.Full("休· 中秋节"),
            surface(fullText = FULL_REST, fullWidthDp = 66.0, availableWidthDp = 230.0),
        )
        assertEquals(
            "恰好放得下（账算平）就是放得下，边界取等号：",
            SpecialDayHeaderSurface.Full(FULL_REST),
            surface(fullText = FULL_REST, fullWidthDp = 230.0 - LEADING - GAP, availableWidthDp = 230.0),
        )
    }

    @Test
    fun oneDpShortFallsBackToBadgeOnly() {
        assertEquals(
            "差 1dp 就把说明整段省下（宁可少一句，不可换行/撑第三行）：",
            SpecialDayHeaderSurface.BadgeOnly(BADGE),
            surface(fullText = FULL_REST, fullWidthDp = 230.0 - LEADING - GAP + 1.0, availableWidthDp = 230.0),
        )
        assertEquals(
            "差一点点这一档对「班」同样成立：",
            SpecialDayHeaderSurface.BadgeOnly("班"),
            surface(
                badgeText = "班",
                fullText = "班· 国庆节调休上班",
                badgeWidthDp = 11.0,
                fullWidthDp = 130.0,
                availableWidthDp = 129.0, // 前导 93 + 留白 2 + 130 = 225 才放得下，129 远远不够
            ),
        )
    }

    // ---- ③ 完全放不下：本体仍然在 ----

    @Test
    fun badgeBodySurvivesEvenWhenNothingFits() {
        val result = surface(
            fullText = FULL_REST,
            fullWidthDp = 66.0,
            availableWidthDp = LEADING + GAP - 5.0, // 连本体+留白都塞不进前导文字之后
        )
        assertEquals(
            "极限宽度下回的仍是 BadgeOnly（本体不被裁是调用点摆法的责任，这里不许吞标注）：",
            SpecialDayHeaderSurface.BadgeOnly(BADGE),
            result,
        )
        val text = (result as SpecialDayHeaderSurface.BadgeOnly).text
        assertTrue("被省的只能是说明，本体那枚字必须在：", text.contains(BADGE) && !text.contains("中秋"))
    }

    // ---- ④ 没有说明 / 说明空白：两档文字同为单体，不再有"整句"可言 ----

    @Test
    fun absentNoteBehavesLikeSingleCharBadge() {
        assertEquals(
            "教务没给 note 时，放得下就摆「休」：",
            SpecialDayHeaderSurface.Full(BADGE),
            surface(fullText = null, fullWidthDp = 0.0, availableWidthDp = 230.0),
        )
        assertEquals(
            "空白 note 与没有 note 同义：",
            SpecialDayHeaderSurface.Full(BADGE),
            surface(fullText = "  ", fullWidthDp = 0.0, availableWidthDp = 230.0),
        )
        assertEquals(
            "note 缺席时'装不下'的判据只能落在本体那枚字上：",
            SpecialDayHeaderSurface.BadgeOnly(BADGE),
            surface(fullText = null, fullWidthDp = 0.0, badgeWidthDp = 11.0, availableWidthDp = LEADING + GAP + 10.0),
        )
    }

    // ---- ⑤ 超长 note：绝不换行、绝不撑第三行，省到只剩本体 ----

    @Test
    fun absurdlyLongNoteNeverEscapesToLayout() {
        val longNote = "休· " + "国".repeat(80)
        assertEquals(
            "说明再长也只影响 Full/BadgeOnly 的取向，页头行数不由它决定：",
            SpecialDayHeaderSurface.BadgeOnly(BADGE),
            surface(fullText = longNote, fullWidthDp = 11.0 * 83, availableWidthDp = 230.0),
        )
    }

    // ---- ⑥ 读屏整句：休/班分得开，无 note 不加语义 ----

    @Test
    fun hiddenNoteDescriptionSeparatesRestFromWork() {
        assertEquals("国庆节（节假日）", specialDayHiddenNoteDescription(isHoliday = true, note = "国庆节"))
        assertEquals("国庆节调休上班（调休上班）", specialDayHiddenNoteDescription(isHoliday = false, note = "国庆节调休上班"))
        assertTrue(
            "休与班的说法必须分得开：",
            specialDayHiddenNoteDescription(true, "中秋节") != specialDayHiddenNoteDescription(false, "中秋节"),
        )
        assertNull("无 note 不加语义（与 None 同口径）：", specialDayHiddenNoteDescription(isHoliday = true, note = null))
        assertNull("空白 note 也不加：", specialDayHiddenNoteDescription(isHoliday = false, note = "  "))
    }

    // ---- 表默认值：以 2026-09-25（中秋，周五）那天的页头为原型 ----

    private fun surface(
        badgeText: String? = BADGE,
        fullText: String? = FULL_REST,
        badgeWidthDp: Double = 11.0,
        fullWidthDp: Double = 66.0,
        leadingWidthDp: Double = LEADING,
        gapWidthDp: Double = GAP,
        availableWidthDp: Double = 230.0,
    ): SpecialDayHeaderSurface = specialDayHeaderSurface(
        badgeText = badgeText,
        fullText = fullText,
        badgeWidthDp = badgeWidthDp,
        fullWidthDp = fullWidthDp,
        leadingWidthDp = leadingWidthDp,
        gapWidthDp = gapWidthDp,
        availableWidthDp = availableWidthDp,
    )

    private companion object {
        const val BADGE = "休"
        const val FULL_REST = "休· 中秋节"
        /** 「今天 · 第 4 周」labelMedium 实测口径（真机 1x 字号） */
        const val LEADING = 93.0
        /** 徽标与前导文字之间那份 padding(start = spaceMicro) */
        const val GAP = 2.0
    }
}
