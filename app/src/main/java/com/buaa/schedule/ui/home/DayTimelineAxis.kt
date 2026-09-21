package com.buaa.schedule.ui.home

import java.time.LocalTime
import kotlin.math.roundToInt

/**
 * 日视图时间轴（T49）的判据内核：窗口吸附、刻度位置、滚动落点、课间分段、当前块命中。
 *
 * 这里零 android import（收单硬判据：纯 JVM 判据函数设备事实当参数传进来——
 * 时刻以「当日分钟数」表达，dp/px 换算率由调用点量好后注入），
 * 布局侧只做摆放，不再各自复算判断。
 */

/** 日视图时间轴窗口（当日分钟数，整点吸附后） */
internal data class DayTimelineWindow(val startMin: Int, val endMin: Int)

/**
 * 窗口整点吸附：门下界、点上界，与周视图 [timeWindowOf] 同一口径。
 *
 * 为什么必须吸附到整点：小时刻度列（[HourLabels]）是"每整点一格"排的，
 * 窗口端点不落在整点上，最后一格刻度就残缺，网格线与刻度文字从此对不上——
 * 而这两者对不齐正是本卡要修掉的"看不出这是时间轴"。
 * 课程块的定位不受吸附影响：它们按自己的真实起止分钟摆。
 */
internal fun dayTimelineWindow(minStart: LocalTime, maxEnd: LocalTime): DayTimelineWindow {
    val s = minStart.hour * 60 + minStart.minute
    val e = maxEnd.hour * 60 + maxEnd.minute
    val start = s.floorDiv(60) * 60
    // 上界吸附后若与下界同点（单节短课表的极端情形）补一格，
    // 否则窗口高 0 分钟，整条轴什么都摆不下
    val end = (((e + 59) / 60) * 60).coerceAtLeast(start + 60)
    return DayTimelineWindow(start, end)
}

/** 当日分钟数（时钟读取只在调用点发生一次，判据这边只吃数） */
internal fun timelineMinuteOfDay(now: LocalTime): Int = now.hour * 60 + now.minute

/**
 * 整点网格线的 y 偏移（dp，窗口相对）：只画窗口**内部**的整点分界。
 *
 * 窗口顶不画——那条线贴着 Hero 卡下沿，会被读成 Hero 的边框；
 * 窗口底不画——最后一节的下课线以下没有任何东西，通栏线悬在空档里像渲染坏了。
 */
internal fun dayTimelineHourLineOffsets(
    window: DayTimelineWindow,
    heightPerMinuteDp: Double,
): List<Double> {
    val offsets = mutableListOf<Double>()
    var m = window.startMin.floorDiv(60) * 60 + 60
    while (m < window.endMin) {
        offsets += (m - window.startMin).coerceAtLeast(0) * heightPerMinuteDp
        m += 60
    }
    return offsets
}

/**
 * 自动滚动的锚点（当日分钟数）。当天没有"现在"可对齐时的三种情形：
 * - 空课表、或 now 早于窗口（全 future）→ 窗口顶：白天之前本来就该从第一节看起；
 * - now 不早于最后一节课的下课（全 past）→ **最后一节课的上课时刻**：
 *   把"一天上到哪儿"钉在视口上三分之一，而不是让用户停在一屏空档底部；
 * - 其余（课中/课间）→ now 本身：课间空档已有虚线＋文字显式表达，不靠移视野遮掩。
 */
internal fun dayTimelineAnchorMinute(
    nowMin: Int,
    window: DayTimelineWindow,
    blocks: List<IntRange>,
): Int {
    if (blocks.isEmpty()) return window.startMin
    val last = blocks.maxByOrNull { it.first } ?: return window.startMin
    return when {
        nowMin < window.startMin -> window.startMin
        nowMin >= last.last -> last.first
        else -> nowMin
    }
}

/**
 * 进入时间轴模式时的滚动目标（px）：把锚点停在视口 [NOW_VIEWPORT_FRACTION] 处——
 * 与周视图「进入本周时把现在滚进视野」同一个落点口径（那是真机上已验收过的位置）。
 * 恒 ≥0：窗口顶之上没有内容，负值会让 ScrollState 立刻回弹。
 */
internal fun dayTimelineScrollTargetPx(
    anchorMin: Int,
    window: DayTimelineWindow,
    heightPerMinutePx: Float,
    viewportHeightPx: Int,
): Int {
    val anchorPx = (anchorMin - window.startMin).coerceAtLeast(0) * heightPerMinutePx
    return (anchorPx - viewportHeightPx * NOW_VIEWPORT_FRACTION).roundToInt().coerceAtLeast(0)
}

/** 相邻两个（已合并的）课程块之间的课间空档区间（当日分钟数） */
internal data class DayTimelineGap(val startMin: Int, val endMin: Int) {
    val minutes: Int get() = endMin - startMin
}

/** 课间达到这个分钟数就画虚线标记：比它短的课间在两节课块之间只剩十几 dp，画什么都是噪点 */
internal const val DAY_GAP_MARKER_MIN_MINUTES = 20

/** 课间达到这个分钟数才追加「课间 N 分钟」文字：一行 labelMedium 约 16dp，再矮就和上下块咬住了 */
internal const val DAY_GAP_LABEL_MIN_MINUTES = 30

/**
 * 合并课程块（先排序、再并重叠区间），取相邻块之间 ≥ [minGapMinutes] 的课间。
 *
 * 合并这步不能省：并行课（冲突数据）的区间互相重叠甚至嵌套，不并区间
 * 直接按起点排序做差，会拿**内层块**的尾巴去减下一块的开头，量出一段
 * 其实有课覆盖的"假课间"画到轴上（负重叠本身会被阈值滤掉，救不了嵌套那种）。
 */
internal fun dayTimelineGaps(
    blocks: List<IntRange>,
    minGapMinutes: Int = DAY_GAP_MARKER_MIN_MINUTES,
): List<DayTimelineGap> {
    val sorted = blocks.filter { it.last >= it.first }.sortedBy { it.first }
    if (sorted.size < 2) return emptyList()
    val merged = mutableListOf<IntRange>()
    for (b in sorted) {
        val prev = merged.lastOrNull()
        if (prev != null && b.first <= prev.last) {
            merged[merged.lastIndex] = IntRange(prev.first, maxOf(prev.last, b.last))
        } else {
            merged += b
        }
    }
    return merged.zipWithNext().mapNotNull { (a, b) ->
        val gap = DayTimelineGap(a.last, b.first)
        if (gap.minutes >= minGapMinutes) gap else null
    }
}

/** 此刻是否落在这个课程块里：左闭右开——下课铃响的那一分钟，这块就不再是"正在上" */
internal fun dayTimelineBlockContains(startMin: Int, endMin: Int, nowMin: Int): Boolean =
    nowMin in startMin until endMin
