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
        // T68 新加的那一档同理：算不出「哪一周」就没有判断依据，不许跟着 body 走
        assertEquals(
            "9月21日 星期一",
            topBarDateLabel(null, currentWeek = 4, browseWeek = null, today = today, dateOnScreen = today.plusDays(1)),
        )
        assertEquals(
            "9月21日 星期一",
            topBarDateLabel(semesterStart, currentWeek = null, browseWeek = null, today = today, dateOnScreen = today.plusDays(1)),
        )
    }

    // ---- T68：顶栏那一行与 body 那一天同源 ----

    /**
     * 缺陷本体（T56 那一族没修的另一半）：日视图翻到别的那一天时，顶栏第二行恒写今天，
     * 于是「写着今天、画着用户翻到的那一天」。装机在 360dp 窄屏上量的是今日页标题那一行，
     * 宽屏（周 | 日 并排）量的就是这一行。
     */
    @Test
    fun `日视图真的画着本周别的那一天时 第二行跟着那一天`() {
        assertEquals(
            "body 画的是 9月22日，顶栏第二行不许还写着今天",
            "9月22日 星期二",
            topBarDateLabel(semesterStart, currentWeek = 4, browseWeek = null, today = today, dateOnScreen = today.plusDays(1)),
        )
        // 本周最后一天仍然算本周（周一开头那一档整周都认）
        assertEquals(
            "9月27日 星期日",
            topBarDateLabel(semesterStart, currentWeek = 4, browseWeek = null, today = today, dateOnScreen = today.plusDays(6)),
        )
        // 往前翻到上周日：它属于第 3 周，而第一行说的是第 4 周 ⇒ 不认（见下面那档）
        assertEquals(
            "9月21日 星期一",
            topBarDateLabel(semesterStart, currentWeek = 4, browseWeek = null, today = today, dateOnScreen = today.minusDays(1)),
        )
    }

    /** 跟随模式一字不变：dateOnScreen 缺省就是 today，跟随档连一个字符都不该漂 */
    @Test
    fun `跟随模式下这一行连一个字符都不漂`() {
        assertEquals(
            "9月21日 星期一",
            topBarDateLabel(semesterStart, currentWeek = 4, browseWeek = null, today = today, dateOnScreen = today),
        )
        // 翻回今天（body == today）也不算"在浏览别的日子"
        assertEquals(
            "9月21日 星期一",
            topBarDateLabel(semesterStart, currentWeek = 4, browseWeek = 4, today = today, dateOnScreen = today),
        )
    }

    /**
     * 反向钉 T56：新加的一档不许把「浏览别的周」那一档吃掉。
     * 第一行写「第3周（浏览）」时第二行跟着第 3 周 —— 两行自相矛盾就是 T56 那张单的原文。
     */
    @Test
    fun `越出第一行说的那一周时 周那一档赢`() {
        // 浏览第 3 周、日视图还画着第 4 周里的那一天（含今天本身）
        for (screen in listOf(today, today.plusDays(7), semesterStart.plusDays(21))) {
            assertEquals(
                "body=$screen 越出第 3 周，第二行仍该说那一周的周一",
                "9月14日 星期一",
                topBarDateLabel(semesterStart, currentWeek = 4, browseWeek = 3, today = today, dateOnScreen = screen),
            )
        }
        // 落在第 3 周里的那一天才认：两行同时成立
        assertEquals(
            "9月16日 星期三",
            topBarDateLabel(semesterStart, currentWeek = 4, browseWeek = 3, today = today, dateOnScreen = LocalDate.of(2026, 9, 16)),
        )
    }

    // ---- T68b：第一档只认「日视图真的在屏上」的那一天 ----

    /**
     * 缺陷本体（T68 自己带来的那笔账）：`dateLabel` 只活在 `Crossfade` 的 isWeekTab 那一支，
     * 也就是**只有周课表页签才渲染这一行** —— 而人在周课表时窄屏上根本没有日视图，
     * 把 `browseDateOnScreen` 端给第二行就是报一个屏上没有的日子。
     * 真机 f128bc02：网格里高亮的今天是 9/22 周二，第二行写「9月24日 星期四」。
     */
    @Test
    fun `日视图不在屏上时这一行回到今天`() {
        assertEquals(
            "闸关掉（= 窄屏周课表页签）以后第二行不许还写着翻到的那一天：" +
                "dateOnScreen=2026-09-24 而今天=2026-09-21",
            "9月21日 星期一",
            topBarDateLabel(
                semesterStart, currentWeek = 4, browseWeek = null, today = today,
                dateOnScreen = today.plusDays(3), dayViewDrawnOnScreen = false,
            ),
        )
        // 同一份输入把闸放行（宽屏并排 / 今日页签）：跟着 body 那一天的那一档一个字都不许漂
        assertEquals(
            "9月24日 星期四",
            topBarDateLabel(
                semesterStart, currentWeek = 4, browseWeek = null, today = today,
                dateOnScreen = today.plusDays(3), dayViewDrawnOnScreen = true,
            ),
        )
    }

    /** 闸只管第一档：浏览别的周那一档（T56）与日视图在不在屏上无关，不许被顺手关掉 */
    @Test
    fun `闸关掉时浏览别的周那一档照旧赢`() {
        for (screen in listOf(today, today.plusDays(3))) {
            assertEquals(
                "body=$screen、闸关着，第二行仍该说第 3 周的周一",
                "9月14日 星期一",
                topBarDateLabel(
                    semesterStart, currentWeek = 4, browseWeek = 3, today = today,
                    dateOnScreen = screen, dayViewDrawnOnScreen = false,
                ),
            )
        }
        // 第 3 周里的那一天：闸开着才认（与上面那档同一判据的另一侧）
        assertEquals(
            "9月14日 星期一",
            topBarDateLabel(
                semesterStart, currentWeek = 4, browseWeek = 3, today = today,
                dateOnScreen = LocalDate.of(2026, 9, 16), dayViewDrawnOnScreen = false,
            ),
        )
        assertEquals(
            "9月16日 星期三",
            topBarDateLabel(
                semesterStart, currentWeek = 4, browseWeek = 3, today = today,
                dateOnScreen = LocalDate.of(2026, 9, 16), dayViewDrawnOnScreen = true,
            ),
        )
    }

    /** 缺省值 = 放行：老的 5 参调用点（含上面 8 档）连一个字符都不漂，闸是加出来的不是换掉的 */
    @Test
    fun `不传闸时与显式放行逐字相同`() {
        for (screen in listOf(today, today.plusDays(1), today.minusDays(1), nextWeekSameSlot)) {
            assertEquals(
                "dateOnScreen=$screen",
                topBarDateLabel(semesterStart, 4, null, today, screen),
                topBarDateLabel(semesterStart, 4, null, today, screen, dayViewDrawnOnScreen = true),
            )
        }
        // 跟随模式（dateOnScreen 缺省就是今天）关着闸也仍是今天
        assertEquals(
            "9月21日 星期一",
            topBarDateLabel(semesterStart, 4, null, today, today, dayViewDrawnOnScreen = false),
        )
    }

    /** 越出第一行那一周 + 闸开着：仍然越出（闸不会把「认不认」这件事反过来） */
    @Test
    fun `闸开着也不许把越出那一周的那一天端上来`() {
        assertEquals(
            "9月21日 星期一",
            topBarDateLabel(
                semesterStart, currentWeek = 4, browseWeek = null, today = today,
                dateOnScreen = nextWeekSameSlot, dayViewDrawnOnScreen = true,
            ),
        )
    }

    private val nextWeekSameSlot = LocalDate.of(2026, 9, 28) // 第 5 周周一，越出第一行说的那一周
}
