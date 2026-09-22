package com.buaa.schedule.ui.home

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日视图时间轴判据内核（DayTimelineAxis.kt）的真单测。
 *
 * 这些函数是 T49 全部布局改动的可测面：刻度位置、滚动落点、课间分段、当前块命中。
 * 设备事实（时刻、px/分钟、视口高）一律当参数注入，测试里没有 android。
 * 数字取真机镜像（buaa36）实测场景：周一 10:44、窗口 08:00–23:00、1.35dp/分钟
 * （T61 把 [com.buaa.schedule.core.designsystem.DesignTokens.dayHeightPerMinute] 从 1.05 抬上来的
 * 那一档；这里的比率只是注入的自变量，改令牌不会改红这些断言）。
 */
class DayTimelineAxisTest {

    // ── 窗口整点吸附 ──────────────────────────────────────────────

    @Test
    fun windowSnapsOutwardToHourBounds() {
        val w = dayTimelineWindow(LocalTime.of(8, 10), LocalTime.of(22, 30))
        assertEquals(8 * 60, w.startMin)
        assertEquals(23 * 60, w.endMin)
    }

    @Test
    fun hourAlignedEndStaysPutSoNoEmptyHourHangsAtBottom() {
        // 上界已在整点时不许再进一格：多出来的那格刻度底下什么都没有，
        // 又变回本卡要修的"空档像渲染坏了"
        val w = dayTimelineWindow(LocalTime.of(8, 0), LocalTime.of(22, 0))
        assertEquals(8 * 60, w.startMin)
        assertEquals(22 * 60, w.endMin)
    }

    @Test
    fun degenerateWindowKeepsAtLeastOneHour() {
        val w = dayTimelineWindow(LocalTime.of(9, 5), LocalTime.of(9, 5))
        assertEquals(9 * 60, w.startMin)
        assertEquals(10 * 60, w.endMin)
    }

    // ── 整点网格线 ────────────────────────────────────────────────

    @Test
    fun hourLinesCoverOnlyInteriorBoundaries() {
        val w = DayTimelineWindow(8 * 60, 23 * 60)
        val lines = dayTimelineHourLineOffsets(w, 1.35)
        // 09:00..22:00 共 14 条；顶（窗口沿）与底（末整点）不画
        assertEquals(14, lines.size)
        // 注入的比率与断言同源：这里换的是"真机这一档是多少 dp/分钟"（T61：1.05 → 1.35），
        // 判据本身一个字没动
        assertEquals(60 * 1.35, lines.first(), 1e-6)
        assertEquals((22 - 8) * 60 * 1.35, lines.last(), 1e-6)
    }

    @Test
    fun oneHourWindowHasNoInteriorLine() {
        assertTrue(dayTimelineHourLineOffsets(DayTimelineWindow(480, 540), 1.0).isEmpty())
    }

    // ── 滚动锚点 ──────────────────────────────────────────────────

    @Test
    fun anchorFallsToWindowTopWhenEmptyOrAllFuture() {
        val w = DayTimelineWindow(8 * 60, 23 * 60)
        // 7:30 打开今天：现在在窗口上方 → 停在窗口顶（没有更早的东西可看）
        assertEquals(8 * 60, dayTimelineAnchorMinute(7 * 60 + 30, w, listOf(IntRange(480, 570))))
        // 空课表同落点
        assertEquals(8 * 60, dayTimelineAnchorMinute(10 * 60, w, emptyList()))
    }

    @Test
    fun anchorFollowsNowInsideTheDay() {
        val w = DayTimelineWindow(8 * 60, 23 * 60)
        val blocks = listOf(IntRange(480, 570), IntRange(600, 690))
        // 课间也停在自己的"现在"：空档已有虚线＋文字显式表达
        assertEquals(10 * 60 + 44, dayTimelineAnchorMinute(10 * 60 + 44, w, blocks))
    }

    @Test
    fun anchorStopsAtLastClassStartWhenAllPast() {
        val w = DayTimelineWindow(8 * 60, 23 * 60)
        val blocks = listOf(IntRange(900, 990), IntRange(480, 570))
        // 22:00 回看今天：停在**最后一节课的上课时刻**，"一天上到哪儿"钉在视口上部，
        // 而不是钉死在一屏空档底部
        assertEquals(900, dayTimelineAnchorMinute(22 * 60, w, blocks))
    }

    @Test
    fun nestedBlocksDoNotFakeAllPastInsideTheOuterClass() {
        val w = DayTimelineWindow(8 * 60, 23 * 60)
        // 并行课（冲突数据）嵌套：A=[600,800] 10:00–13:20，B=[610,620] 整段在 A 里。
        // 旧口径 maxByOrNull{first} 挑中外层里的**小块 B** 当"最后一节课"，now=700
        // （11:40，明明还压在 A 里、天没上完）被误判成全 past，锚点跳到 610。
        // 与 dayTimelineGaps 同口径先并块：合并块 [600,800] 盖着 700 → 锚点停在 now 本身。
        // 负向验证：把 DayTimelineAnchor 换回 maxByOrNull{first} 这条必红（实测红在 610≠700）。
        assertEquals(
            700,
            dayTimelineAnchorMinute(700, w, listOf(IntRange(600, 800), IntRange(610, 620))),
        )
    }

    @Test
    fun allPastAnchorUsesMergedBlockStartNotInnerBlock() {
        val w = DayTimelineWindow(8 * 60, 23 * 60)
        // 真·全 past（14:00 已过合并块下课 13:20）：锚点是**合并块**的起点 600，
        // 不是内层 B 的 610——一天真正"上到哪儿"由外层区间说话（旧口径这里红在 610≠600）
        assertEquals(
            600,
            dayTimelineAnchorMinute(14 * 60, w, listOf(IntRange(600, 800), IntRange(610, 620))),
        )
    }

    // ── 滚动目标 ──────────────────────────────────────────────────

    @Test
    fun scrollTargetPlacesAnchorAtSharedViewportFraction() {
        val w = DayTimelineWindow(8 * 60, 23 * 60)
        // 锚点距窗口顶 240 分钟 × 2px/分钟 = 480px，视口 1000px、领先 0.35 → 130
        assertEquals(130, dayTimelineScrollTargetPx(12 * 60, w, 2f, 1000))
        // 落点系数必须与周视图同口径（那是真机验收过的位置），这里把它钉死
        assertEquals(0.35f, NOW_VIEWPORT_FRACTION, 1e-6f)
    }

    @Test
    fun scrollTargetNeverNegative() {
        val w = DayTimelineWindow(8 * 60, 23 * 60)
        assertEquals(0, dayTimelineScrollTargetPx(8 * 60 + 5, w, 2f, 1000))
    }

    // ── 课间分段 ──────────────────────────────────────────────────

    @Test
    fun gapsMergeOverlappingBlocksBeforeSplitting() {
        // (600,650) 整段被 (480,700) 包住。不先并区间，zip 会拿**内层块**的尾巴 650
        // 去减下一块的开头，量出一段 650–900 的"假课间"（480–700 那段其实一直有课上）
        val gaps = dayTimelineGaps(
            listOf(IntRange(480, 700), IntRange(600, 650), IntRange(900, 990)),
        )
        assertEquals(listOf(DayTimelineGap(700, 900)), gaps)
        assertEquals(200, gaps.single().minutes)
    }

    @Test
    fun gapsTolerateUnsortedInputAndDropShortBreaks() {
        val gaps = dayTimelineGaps(listOf(IntRange(900, 990), IntRange(480, 570)))
        assertEquals(listOf(DayTimelineGap(570, 900)), gaps)
        // 15 分钟的课间低于阈值：画出来只会是噪点
        assertTrue(dayTimelineGaps(listOf(IntRange(480, 570), IntRange(585, 675))).isEmpty())
    }

    @Test
    fun gapThresholdsMatchTheDesignRationale() {
        // 20 分钟 ≈27dp（1.35dp/分钟）只够一条虚线；30 分钟才装得下文字行
        assertEquals(20, DAY_GAP_MARKER_MIN_MINUTES)
        assertEquals(30, DAY_GAP_LABEL_MIN_MINUTES)
    }

    @Test
    fun singleBlockDayHasNoGap() {
        assertTrue(dayTimelineGaps(listOf(IntRange(480, 570))).isEmpty())
    }

    // ── 当前块命中 ────────────────────────────────────────────────

    @Test
    fun blockContainsIsLeftClosedRightOpen() {
        // 下课铃响的那一分钟就不再是"正在上"——与块自己的 end 口径一致
        assertTrue(dayTimelineBlockContains(480, 570, 480))
        assertTrue(dayTimelineBlockContains(480, 570, 569))
        assertFalse(dayTimelineBlockContains(480, 570, 570))
    }

    @Test
    fun minuteOfDayConvertsLocalTime() {
        assertEquals(10 * 60 + 44, timelineMinuteOfDay(LocalTime.of(10, 44)))
    }

    // ── 15 秒链的开关（T49b③） ────────────────────────────────────

    @Test
    fun liveTickOffForOtherDaysAndOutsideWindow() {
        val w = DayTimelineWindow(8 * 60, 23 * 60)
        assertFalse("非今天：NowLine 直接 return，链不许醒", nowLineNeedsLiveTick(false, 10 * 60, w))
        assertFalse(nowLineNeedsLiveTick(true, 7 * 60, w))   // 早于窗口：fraction≤0 不画线
        assertFalse(nowLineNeedsLiveTick(true, 23 * 60, w))  // 窗口末界：fraction≥1 不画线
    }

    @Test
    fun liveTickBoundsMatchNowLineVisibility() {
        val w = DayTimelineWindow(8 * 60, 23 * 60)
        // 开区间界：与 NowLine 的 fraction>0 && <1 逐点对齐——窗口内一秒都不许多醒，
        // 界线外一秒都不许漏（漏了红线会停在窗口外时刻的旧位置）
        assertTrue(nowLineNeedsLiveTick(true, 8 * 60 + 1, w))
        assertTrue(nowLineNeedsLiveTick(true, 23 * 60 - 1, w))
    }
}
