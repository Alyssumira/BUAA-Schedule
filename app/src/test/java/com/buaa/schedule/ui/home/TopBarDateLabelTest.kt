package com.buaa.schedule.ui.home

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 顶栏第二行的日期（装机实测：第一行「第3周（浏览）」、第二行「9月21日 星期一」，
 * 而 9/21 是第 4 周的周一——两行自相矛盾）。
 *
 * 学期起点 2026-08-31（周一）；今天取 2026-09-21（周一，第 4 周），正是截图那台机器的日子。
 */
class TopBarDateLabelTest {

    private val semesterStart = LocalDate.of(2026, 8, 31)
    private val today = LocalDate.of(2026, 9, 21) // 第 4 周的周一

    @Test
    fun `浏览别的周时第二行跟着那一周的周一`() {
        val cases = listOf(
            // 浏览周号 → 期望文案（该周都是周一开头，星期几名恒为「星期一」）
            1 to "8月31日 星期一",
            2 to "9月7日 星期一",
            3 to "9月14日 星期一",   // 截图里错成「9月21日」的那一周
            5 to "9月28日 星期一",
            16 to "12月14日 星期一",
            // 跨年学期：秋季学期一路排到次年 1 月，月份与年份都得跟着换
            20 to "1月11日 星期一",
            // 越出总周数也要和网格表头同口径（表头本来就把那七天画出来）
            25 to "2月15日 星期一",
        )
        for ((week, expected) in cases) {
            assertEquals(
                "浏览第${week}周",
                expected,
                topBarDateLabel(semesterStart, currentWeek = 4, browseWeek = week, today = today),
            )
        }
    }

    @Test
    fun `跟随模式一字不变仍是今天`() {
        // 回归底线：browseWeek 为 null 时行为与改前完全一致
        assertEquals(
            "9月21日 星期一",
            topBarDateLabel(semesterStart, currentWeek = 4, browseWeek = null, today = today),
        )
        // 翻出去又翻回本周：第一行已经不带「（浏览）」，第二行也不该改成周一日期
        assertEquals(
            "9月21日 星期一",
            topBarDateLabel(semesterStart, currentWeek = 4, browseWeek = 4, today = today),
        )
        // 今天不是周一时跟随模式仍报今天
        assertEquals(
            "9月23日 星期三",
            topBarDateLabel(semesterStart, currentWeek = 4, browseWeek = null, today = today.plusDays(2)),
        )
    }

    @Test
    fun `当前周恰好是第一周`() {
        val firstMonday = semesterStart
        assertEquals(
            "8月31日 星期一",
            topBarDateLabel(semesterStart, currentWeek = 1, browseWeek = null, today = firstMonday),
        )
        // 第 1 周里翻到第 2 周：不能因为「才第二周」就漏掉换算
        assertEquals(
            "9月7日 星期一",
            topBarDateLabel(semesterStart, currentWeek = 1, browseWeek = 2, today = firstMonday),
        )
    }

    @Test
    fun `开学日期不在周中时按所在自然周对齐`() {
        // 周三开学：第 1 周仍从那个周一（8/31）算起，与 WeekCalculator.mondayOf 同口径
        val wednesdayStart = semesterStart.plusDays(2)
        assertEquals(
            "9月14日 星期一",
            topBarDateLabel(wednesdayStart, currentWeek = 4, browseWeek = 3, today = today),
        )
    }

    @Test
    fun `算不出那一周时退回今天而不是凭空造日期`() {
        // 未设置学期 / 开学日期写坏
        assertEquals(
            "9月21日 星期一",
            topBarDateLabel(null, currentWeek = 4, browseWeek = 3, today = today),
        )
        // 周号越出下界（真机上取不到，纯算式的兜底）
        assertEquals(
            "9月21日 星期一",
            topBarDateLabel(semesterStart, currentWeek = 4, browseWeek = 0, today = today),
        )
        // 假期里 currentWeek 为 null、用户硬翻到某周：仍然按那一周显示
        assertEquals(
            "9月14日 星期一",
            topBarDateLabel(semesterStart, currentWeek = null, browseWeek = 3, today = today),
        )
    }
}
