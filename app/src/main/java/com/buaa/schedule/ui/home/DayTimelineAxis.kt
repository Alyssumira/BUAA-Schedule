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
 * - now 不早于一天结束（全 past）→ **最后一段课的上课时刻**：
 *   把"一天上到哪儿"钉在视口上三分之一，而不是让用户停在一屏空档底部；
 * - 其余（课中/课间）→ now 本身：课间空档已有虚线＋文字显式表达，不靠移视野遮掩。
 *
 * 「一天结束」走 [mergeDayTimelineBlocks] 之后取末段，而不是旧口径的
 * `maxByOrNull { it.first }.last`：并行课（冲突数据）的区间会嵌套——外层 A=[600,800]、
 * 内层 B=[610,620] 时旧口径把 B 当"最后一节课"，700（正落在 A 里、天没上完）
 * 被误判成全 past，锚点跳到 610 而不是停在 now。与 [dayTimelineGaps] 选并块
 * 而非 maxOf{last} 是同一个理由：并完还顺手给出正确的段起点，两处共用一份口径不再各算各的。
 */
internal fun dayTimelineAnchorMinute(
    nowMin: Int,
    window: DayTimelineWindow,
    blocks: List<IntRange>,
): Int {
    val last = mergeDayTimelineBlocks(blocks).lastOrNull() ?: return window.startMin
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
 * 合并课程块（先排序、再并重叠区间）。
 *
 * 合并这步不能省：并行课（冲突数据）的区间互相重叠甚至嵌套，不并区间
 * 直接按起点排序做差，会拿**内层块**的尾巴去减下一块的开头，量出一段
 * 其实有课覆盖的"假课间"画到轴上（负重叠本身会被阈值滤掉，救不了嵌套那种）。
 * [dayTimelineAnchorMinute] 的"一天结束"取的就是这里的末段——两处共用一份口径。
 */
private fun mergeDayTimelineBlocks(blocks: List<IntRange>): List<IntRange> {
    val sorted = blocks.filter { it.last >= it.first }.sortedBy { it.first }
    if (sorted.size < 2) return sorted
    val merged = mutableListOf<IntRange>()
    for (b in sorted) {
        val prev = merged.lastOrNull()
        if (prev != null && b.first <= prev.last) {
            merged[merged.lastIndex] = IntRange(prev.first, maxOf(prev.last, b.last))
        } else {
            merged += b
        }
    }
    return merged
}

/** 相邻两个（已合并的）课程块之间的课间空档区间（当日分钟数）≥ [minGapMinutes] 的那些 */
internal fun dayTimelineGaps(
    blocks: List<IntRange>,
    minGapMinutes: Int = DAY_GAP_MARKER_MIN_MINUTES,
): List<DayTimelineGap> =
    mergeDayTimelineBlocks(blocks).zipWithNext().mapNotNull { (a, b) ->
        val gap = DayTimelineGap(a.last, b.first)
        if (gap.minutes >= minGapMinutes) gap else null
    }

/** 此刻是否落在这个课程块里：左闭右开——下课铃响的那一分钟，这块就不再是"正在上" */
internal fun dayTimelineBlockContains(startMin: Int, endMin: Int, nowMin: Int): Boolean =
    nowMin in startMin until endMin

/**
 * 「现在」线在窗口内的纵向分数：0 = 窗口顶，1 = 窗口底。
 *
 * 从 NowLine 的函数体里搬出来（T52③）：这条算式日视图时间轴与周视图 24h 模式共用，
 * 而"越界怎么取值"决定了线画不画（调用点按 `fraction <= 0f || >= 1f` 直接不画），
 * 摆在只有 android 依赖的绘制件里就没法在 JVM 上钉住。
 * 窗口高 ≤0（脏节次表）时取 0 = 不画，与搬动前逐位一致。
 */
internal fun nowLineFraction(minuteOfDay: Int, startMin: Int, endMin: Int): Float {
    val total = endMin - startMin
    if (total <= 0) return 0f
    return ((minuteOfDay - startMin).toFloat() / total).coerceIn(0f, 1f)
}

/**
 * 「现在」线的 15 秒链这一拍要不要跑。
 *
 * 链唯一喂给的是 NowLine 一个组合作用域（块的高亮走分钟级 now 参数），而
 * NowLine 在 !visible 或 fraction≤0/≥1 时直接 return——非今天、以及 now 落在
 * 窗口外（早于第一节、晚于最后一节）时线根本不画，链每 15 秒醒来写一次没人读的 State。
 * 真机实测（buaa36）：翻到周二看时间轴，屏幕每 15 秒白醒一次。边界取开区间，
 * 与 NowLine 自己的 fraction>0 && <1 可见条件同口径；回到今天时这个值翻回 true、
 * effect 重启，第一拍先发布当前时刻再等边界，红线不会停在旧时刻。
 */
internal fun nowLineNeedsLiveTick(isToday: Boolean, nowMin: Int, window: DayTimelineWindow): Boolean =
    isToday && nowMin > window.startMin && nowMin < window.endMin
