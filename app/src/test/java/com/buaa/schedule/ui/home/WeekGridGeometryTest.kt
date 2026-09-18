package com.buaa.schedule.ui.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.TimeSlotProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

/**
 * 周视图几何（P1-3 的回归闸门）。
 *
 * 这些函数此前是 WeekView.kt 里的 private 顶层函数，藏在 Compose 文件里一条 JVM
 * 单测都写不了 —— 而「卡片按小时高排布、拖拽却按行高取整」正是这种测不动的口径
 * 才会拖两轮的问题。
 */
class WeekGridGeometryTest {

    /** density 钉成 1：dp 数值即像素数值，断言里不用带缩放系数 */
    private val density = Density(1f, 1f)
    private val rowHeight = 64.dp
    private val hourHeight = 56.dp
    private val slots = TimeSlotProfile.DEFAULT

    private fun course(periods: List<Int>) = Course(
        name = "高数",
        dayOfWeek = 1,
        periods = periods,
        weeks = listOf(1),
    )

    private fun dragState(segment: IntRange, originTopPx: Float) = CourseDragState(
        course = course(segment.toList()),
        segment = segment,
        originDayIndex = 0,
        originStartPeriod = segment.first,
        totalOffset = Offset.Zero,
        targetDayIndex = 0,
        targetStartPeriod = segment.first,
        originTopPx = originTopPx,
        heightPx = 64f,
    )

    private fun timeMetric() = buildWeekGridMetric(
        slots = slots,
        layouts = emptyList(),
        rowHeight = rowHeight,
        density = density,
        timeMode = true,
        hourHeight = hourHeight,
        windowStartMin = 8 * 60,
        gapAfterNumber = null,
        gapHeight = 0.dp,
    )

    private fun periodMetric(gapAfterNumber: Int? = null, gapHeight: Int = 0) = buildWeekGridMetric(
        slots = slots,
        layouts = buildPeriodLayouts(slots, rowHeight),
        rowHeight = rowHeight,
        density = density,
        timeMode = false,
        hourHeight = hourHeight,
        windowStartMin = 0,
        gapAfterNumber = gapAfterNumber,
        gapHeight = gapHeight.dp,
    )

    // —— 24h 时间轴：一节多高由真实时长决定 ——————————————————————

    @Test
    fun timeModeRowsFollowRealMinutes() {
        val metric = timeMetric()
        // 08:00 起算、每小时 56dp：一节多高由它的**开始时间**决定，不是等距一行
        assertEquals(0f, metric.topOfRank(0f), 0.01f)
        assertEquals(50 / 60f * 56f, metric.topOfRank(1f), 0.01f)
        // 第 6 节 14:00 上课：中间那 1 小时 45 分钟的午休是真实距离
        assertEquals(336f, metric.topOfRank(metric.rankOfPeriod(6).toFloat()), 0.01f)
    }

    @Test
    fun dragSnapsToTheRowTheCardActuallyCovers() {
        val metric = timeMetric()
        // 第 4 节 10:40 上课 → 顶部在 149.33px。卡片停在那里，目标就必须是第 4 节。
        // 旧口径除以行高：round(149.33 / 64) = 2 → 告诉用户「第 3 节」，
        // 手指底下的那一行却是第 4 节（P1-3）。
        val dragged = dragState(1..1, originTopPx = 0f).advancedBy(
            amount = Offset(0f, 149.33f), dayWidthPx = 100, dayCount = 7, metric = metric,
        )
        assertEquals(4, dragged.targetStartPeriod)
    }

    @Test
    fun dragLunchBreakIsOneLongJumpNotSeveralRows() {
        val metric = timeMetric()
        // 第 5 节 12:15 下课、第 6 节 14:00 上课：午休在时间轴上占 105 分钟 = 98px，
        // 按行高只有 1.5 行。旧口径会把这段空档当成「还能再拖两节」，
        // 于是卡片已经压在 14:00 那条线上，目标却还停在第 5 节。
        val dragged = dragState(5..5, originTopPx = 196f).advancedBy(
            amount = Offset(0f, 100f), dayWidthPx = 100, dayCount = 7, metric = metric,
        )
        assertEquals(6, dragged.targetStartPeriod)
    }

    @Test
    fun dragNeverLandsPastTheLastFullSegment() {
        val metric = timeMetric()
        // 三节连堂拖到最底：起始名次只能到 size - span
        val dragged = dragState(1..3, originTopPx = 0f).advancedBy(
            amount = Offset(0f, 9_999f), dayWidthPx = 100, dayCount = 7, metric = metric,
        )
        assertEquals(slots.size - 3 + 1, dragged.targetStartPeriod)
    }

    // —— 节次行模式：口径必须和改动前逐字一致 ————————————————

    @Test
    fun periodModeStillRoundsByRowHeight() {
        val metric = periodMetric()
        // 名次 = 节次号 - 1，等距 64px：与改动前逐字一致
        assertEquals(3, dragState(1..1, 0f).advancedBy(Offset(0f, 100f), 100, 7, metric).targetStartPeriod)
        assertEquals(3, dragState(1..1, 0f).advancedBy(Offset(0f, 153f), 100, 7, metric).targetStartPeriod)
        assertEquals(4, dragState(1..1, 0f).advancedBy(Offset(0f, 192f), 100, 7, metric).targetStartPeriod)
    }

    @Test
    fun periodModeGapShiftMovesTheTargetRowsToo() {
        // 空档展开后第 6 节往后挪了整整一个 gap，落点吸附必须跟着走：
        // 否则「视觉上在第 6 节」会被算成第 5 节 —— 与 P1-3 同一类错，只是换了触发条件
        val withGap = periodMetric(gapAfterNumber = 5, gapHeight = 100)
        assertEquals(100f + 320f, withGap.topOfRank(withGap.rankOfPeriod(6).toFloat()), 0.01f)
        val dragged = dragState(1..1, originTopPx = 0f).advancedBy(
            amount = Offset(0f, 490f), dayWidthPx = 100, dayCount = 7, metric = withGap,
        )
        assertEquals(7, dragged.targetStartPeriod)
    }

    @Test
    fun fingerAndEdgeScrollShareOneRounding() {
        // 边缘自动滚动和手指位移都走 advancedBy：分两段喂进去必须等于一次喂到位，
        // 否则「停在边缘滚过去」的那几节会在松手时和确认框里的目标节次不一致（①I-01）
        val metric = timeMetric()
        val oneShot = dragState(1..1, 0f).advancedBy(Offset(0f, 336f), 100, 7, metric)
        val stepped = dragState(1..1, 0f)
            .advancedBy(Offset(0f, 200f), 100, 7, metric)
            .advancedBy(Offset(0f, 136f), 100, 7, metric)
        assertEquals(oneShot.targetStartPeriod, stepped.targetStartPeriod)
        assertEquals(Offset(0f, 336f), stepped.totalOffset)
    }

    // —— 缩节次 ——————————————————————————————————

    @Test
    fun resizeEndFollowsTheSameMetric() {
        val metric = timeMetric()
        // 从第 1 节往下拖到第 3 节那条线上 → 结束节就是第 3 节
        val resized = ResizeState(course((1..1).toList()), 1..1, originalStart = 1, originalEnd = 1, deltaY = 102.67f)
        assertEquals(3, resized.newEnd(metric))
        // 不能缩到比起点还早
        assertEquals(1, resized.copy(deltaY = -500f).newEnd(metric))
    }

    @Test
    fun resizeKeepsOtherSegments() {
        // 1-2 + 9-10 的课只缩第一段，第二段必须原样留着（P0 那次的老伤口）
        val merged = resizeCoursePeriods(course(listOf(1, 2, 9, 10)), 1..2, newStart = 1, newEnd = 3)
        assertEquals(listOf(1, 2, 3, 9, 10), merged)
    }

    // —— 布局与空档 ————————————————————————————————

    @Test
    fun layoutsUseOneRowPerPeriodRegardlessOfDuration() {
        val layouts = buildPeriodLayouts(slots, rowHeight)
        assertEquals(slots.size, layouts.size)
        assertEquals(0.dp, layouts.first().top)
        assertEquals(64.dp, layouts[1].top)
        assertEquals(13 * 64, layouts.last().top.value.toInt())
    }

    @Test
    fun unparseableSlotLeavesItsRowOutInsteadOfCrashing() {
        val layouts = buildPeriodLayouts(
            listOf(
                TimeSlot(number = 1, startTime = "08:00", endTime = "08:45"),
                TimeSlot(number = 2, startTime = "99:99", endTime = "09:35"),
                TimeSlot(number = 3, startTime = "09:50", endTime = "10:35"),
            ),
            rowHeight,
        )
        // 坏时间那一节整行消失（与「缺节次时间即不可排课」同口径），行距不因此错位
        assertEquals(listOf(1, 3), layouts.map { it.number })
        assertEquals(64.dp, layouts[1].top)
    }

    @Test
    fun gapOpensOnlyAfterTheCurrentTimeSitsInsideIt() {
        val layouts = buildPeriodLayouts(slots, rowHeight)
        // 13:00 正卡在 12:15 下课与 14:00 上课之间 → 105 分钟午休
        val (afterNumber, height) = checkNotNull(findIntervalGap(layouts, LocalTime.of(13, 0), rowHeight))
        assertEquals(5, afterNumber)
        assertEquals((64f * 105f / 45f), height.value, 0.01f)
        // 正在上课，没有空档
        assertNull(findIntervalGap(layouts, LocalTime.of(8, 20), rowHeight))
        // 最后一节之后不算空档
        assertNull(findIntervalGap(layouts, LocalTime.of(23, 0), rowHeight))
    }

    @Test
    fun gapShiftOnlyMovesRowsBelowTheGap() {
        assertEquals(0.dp, gapShiftFor(5, gapAfterNumber = 5, gapHeight = 40.dp))
        assertEquals(40.dp, gapShiftFor(6, gapAfterNumber = 5, gapHeight = 40.dp))
        assertEquals(0.dp, gapShiftFor(6, gapAfterNumber = null, gapHeight = 40.dp))
    }

    @Test
    fun segmentNeverCollapsesBelowTheReadableFloor() {
        // 一节 0dp 高的极端缩放：卡片也得留出看得见字的那点高度
        assertEquals(DesignTokens.weekMinCardHeight, periodSegHeight(emptyList(), 3..3, 0.dp))
    }
}
