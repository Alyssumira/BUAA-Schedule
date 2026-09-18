package com.buaa.schedule.ui.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.TimeSlot
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 周视图的纯几何：节次行布局、纵向度量、拖拽与缩取的取整口径。
 *
 * 单独成文件的理由是**可测**：这些函数原先是 WeekView.kt 里的 private 顶层函数，
 * 藏在 Compose 文件里一条 JVM 单测都写不了，而 P1-3（拖拽取整用行高、卡片排布用
 * 小时高）正是这种没人测得动的双口径才会拖两轮的问题。
 */

/** 单个节次的行布局：节次号、起止分钟、在网格内容坐标系里的顶部与高度 */
internal data class PeriodLayout(
    val number: Int,
    val startMin: Int,
    val endMin: Int,
    val top: Dp,
    val height: Dp,
)

/** 可解析的节次 → (节次号, 开始分钟, 结束分钟)；时间写坏的节次直接丢弃而不是炸渲染 */
private fun parseSlotMinutes(slots: List<TimeSlot>): List<Triple<Int, Int, Int>> =
    slots.mapNotNull { slot ->
        val start = runCatching { LocalTime.parse(slot.startTime) }.getOrNull() ?: return@mapNotNull null
        val end = runCatching { LocalTime.parse(slot.endTime) }.getOrNull() ?: return@mapNotNull null
        Triple(slot.number, start.hour * 60 + start.minute, end.hour * 60 + end.minute)
    }

/**
 * 按「每节等高的行」计算布局。
 *
 * 默认不插入课间空档：空档的动画值每秒变 60 次，把它算进布局等于每帧重建整份
 * `periodLayouts`、进而让整片卡片树重组（R5 F-22）。空档只在 placement 阶段由
 * [gapShiftFor] 叠加，见 [TimeLabels] 里那个 Spacer。
 */
internal fun buildPeriodLayouts(
    slots: List<TimeSlot>,
    rowHeight: Dp,
    gapAfterNumber: Int? = null,
    gapHeight: Dp = 0.dp,
): List<PeriodLayout> {
    val parsed = parseSlotMinutes(slots)
    if (parsed.isEmpty()) return emptyList()

    var cursor = 0.dp
    return parsed.map { (number, startMin, endMin) ->
        val layout = PeriodLayout(number, startMin, endMin, cursor, rowHeight)
        cursor += rowHeight
        if (gapAfterNumber == number) cursor += gapHeight
        layout
    }
}

/** 连续段顶部：按真实时间布局定位 */
internal fun periodSegTop(layouts: List<PeriodLayout>, segment: IntRange, fallbackRowHeight: Dp): Dp =
    layouts.firstOrNull { it.number == segment.first }?.top ?: (fallbackRowHeight * (segment.first - 1))

/**
 * 课间空档造成的纵向偏移：空档插在 [gapAfterNumber] 之后，因此只有编号更大的行会下移。
 */
internal fun gapShiftFor(periodNumber: Int, gapAfterNumber: Int?, gapHeight: Dp): Dp =
    if (gapAfterNumber != null && periodNumber > gapAfterNumber) gapHeight else 0.dp

/** 连续段高度：末端底部 - 首端顶部 */
internal fun periodSegHeight(layouts: List<PeriodLayout>, segment: IntRange, fallbackRowHeight: Dp): Dp {
    val bottom = layouts.firstOrNull { it.number == segment.last }
        ?.let { it.top + it.height }
        ?: (fallbackRowHeight * segment.last)
    val top = periodSegTop(layouts, segment, fallbackRowHeight)
    return (bottom - top).coerceAtLeast(DesignTokens.weekMinCardHeight)
}

/**
 * 当前时间是否落在某两个节次之间的真实空档；若是，返回要展开 gap 的上一节次号和 gap 高度。
 *
 * 传进来的必须是**不含空档**的基准布局（[gapAfterNumber] 全为 null 的那份）：
 * 空档一旦叠进布局，这里量到的间隔就带着上一帧的偏移，会自我放大。
 */
internal fun findIntervalGap(
    layouts: List<PeriodLayout>,
    now: LocalTime,
    rowHeight: Dp,
): Pair<Int, Dp>? {
    if (layouts.size < 2) return null
    val nowMin = now.hour * 60 + now.minute
    for (i in 0 until layouts.lastIndex) {
        val prev = layouts[i]
        val next = layouts[i + 1]
        if (nowMin >= prev.endMin && nowMin < next.startMin) {
            val gapMinutes = (next.startMin - prev.endMin).coerceAtLeast(1)
            return prev.number to rowHeight * (gapMinutes / DesignTokens.weekGapMinutesPerRow)
        }
    }
    return null
}

/**
 * 网格的纵向度量：「第几节」↔「多少像素」互换算的唯一入口（P1-3）。
 *
 * 两种网格模式以前各写各的换算：卡片、网格总高、左侧标签在 24h 模式下按
 * `weekHourHeight × 真实分钟` 排布，而拖拽取整、拖到边缘时的自动滚动、落点高亮框
 * 却一律除以 `weekRowHeight` —— 于是手指停在第 7 节的视觉上，确认框告诉你第 8 节。
 * 两套节距（一节 45 分钟 vs 一小时 56dp）本来就不该互相换算，这里收成一个对象：
 * 任何新的纵向换算都必须经过它，于"模式"不再有第二份口径。
 *
 * 名次（rank）= 节次在可视顺序里的 0 基下标。24h 模式下节次号可能不连续
 * （作息表被用户删过几节），名次才是两种模式共同的语言。
 */
internal class WeekGridMetric(
    /** 可落的节次号，按从上到下的视觉顺序排列 */
    val periodNumbers: List<Int>,
    /** 与 [periodNumbers] 一一对应的各节顶部像素（网格内容坐标系） */
    val topsPx: List<Float>,
    /** 节次表整个解析不出来时用来外推的等距节距；正常路径读不到 */
    private val pitchPx: Float,
) {
    val size: Int get() = periodNumbers.size

    /** 名次 → 顶部像素；小数名次在相邻两节之间线性插值，供落点高亮框做弹簧动画 */
    fun topOfRank(rank: Float): Float = when {
        topsPx.isEmpty() -> rank * pitchPx
        rank <= 0f -> topsPx.first()
        rank >= topsPx.lastIndex -> topsPx.last()
        else -> {
            val lower = rank.toInt()
            topsPx[lower] + (topsPx[lower + 1] - topsPx[lower]) * (rank - lower)
        }
    }

    /** 顶部像素 → 名次：吸附到离得最近的那一节，而不是「除以行高再取整」 */
    fun rankAtTop(targetTopPx: Float): Int {
        if (topsPx.isEmpty()) return (targetTopPx / pitchPx).roundToInt().coerceAtLeast(0)
        var best = 0
        var bestDistance = Float.MAX_VALUE
        for (i in topsPx.indices) {
            val distance = abs(topsPx[i] - targetTopPx)
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        return best
    }

    fun periodAtRank(rank: Int): Int =
        periodNumbers.getOrElse(rank.coerceIn(0, (size - 1).coerceAtLeast(0))) { 1 }

    /** 节次号 → 名次；表里没有这个节次时按第 1 节处理（调用方的课已经画不出来，取整只能是兜底） */
    fun rankOfPeriod(period: Int): Int = periodNumbers.indexOf(period).coerceAtLeast(0)

    /** 一段 [span] 节课能放下的最大起始名次 */
    fun maxStartRank(span: Int): Int = (size - span).coerceAtLeast(0)
}

/**
 * 组装当前这一帧的度量。
 *
 * 空档用**目标高度**而不是动画中间值，和 [gridHeight] 同一口径（见其注释）：
 * 读动画值等于每帧重建度量。动画那 300ms 里正好按住卡片拖的机会微乎其微，
 * 而且落点仍会经过确认框二次核对。
 */
internal fun buildWeekGridMetric(
    slots: List<TimeSlot>,
    layouts: List<PeriodLayout>,
    rowHeight: Dp,
    density: Density,
    timeMode: Boolean,
    hourHeight: Dp,
    windowStartMin: Int,
    gapAfterNumber: Int?,
    gapHeight: Dp,
): WeekGridMetric {
    val rowHeightPx = with(density) { rowHeight.toPx() }
    val hourHeightPx = with(density) { hourHeight.toPx() }
    return if (timeMode) {
        val parsed = parseSlotMinutes(slots)
        WeekGridMetric(
            periodNumbers = parsed.map { it.first },
            topsPx = parsed.map { (_, startMin, _) ->
                (startMin - windowStartMin) / 60f * hourHeightPx
            },
            pitchPx = hourHeightPx,
        )
    } else if (layouts.isEmpty()) {
        // 节次整个解析不出来：退化成「按节次号等距分行」，与卡片侧的 fallback 同口径
        WeekGridMetric(
            periodNumbers = slots.map { it.number },
            topsPx = slots.indices.map { it * rowHeightPx },
            pitchPx = rowHeightPx,
        )
    } else {
        WeekGridMetric(
            periodNumbers = layouts.map { it.number },
            topsPx = layouts.map { layout ->
                with(density) {
                    (layout.top + gapShiftFor(layout.number, gapAfterNumber, gapHeight)).toPx()
                }
            },
            pitchPx = rowHeightPx,
        )
    }
}

/** 一次进行中的拖拽：被拿起的卡片、累计位移与取整后的目标格子 */
internal data class CourseDragState(
    val course: Course,
    val segment: IntRange,
    val originDayIndex: Int,
    val originStartPeriod: Int,
    val totalOffset: Offset,
    val targetDayIndex: Int,
    val targetStartPeriod: Int,
    /**
     * 卡片**拿起那一刻**在网格内容坐标系里的顶部像素（不含 [totalOffset]）。
     * 边缘自动滚动要靠它算出卡片此刻在视口的哪一条边上——只有累计位移的话，
     * 网格一滚就再也对不上手指了（①I-01）。
     */
    val originTopPx: Float,
    /** 卡片高度像素：下边缘判定要用卡片的底；落点高亮框直接复用，保证和手上的卡片一样高 */
    val heightPx: Float,
)

/**
 * 把一段位移叠进拖拽状态，并按格子取整出目标位置。
 *
 * 手指的 `dragAmount` 与边缘自动滚动滚掉的量都走这里（①I-01）：两处必须是同一份取整口径，
 * 否则"停在边缘滚过去"的那几节会在松手时和确认框里的目标节次不一致。
 *
 * 纵向取整交给 [WeekGridMetric]：横向一列恒等宽，纵向节距却随网格模式而变（P1-3）。
 */
internal fun CourseDragState.advancedBy(
    amount: Offset,
    dayWidthPx: Int,
    dayCount: Int,
    metric: WeekGridMetric,
): CourseDragState {
    val total = totalOffset + amount
    val span = segment.last - segment.first + 1
    // 吸附的起点是卡片拿起那一刻的真实顶部，而不是「名次 × 节距」：
    // 前者含了行间隙与空档偏移，才是眼睛看到的那条线
    val targetRank = metric.rankAtTop(originTopPx + total.y)
        .coerceIn(0, metric.maxStartRank(span))
    return copy(
        totalOffset = total,
        targetDayIndex = (originDayIndex + (total.x / dayWidthPx).roundToInt())
            .coerceIn(0, dayCount - 1),
        targetStartPeriod = metric.periodAtRank(targetRank),
    )
}

/** 缩放改节次中的临时状态：记录原始起止节与累计纵向位移 */
internal data class ResizeState(
    val course: Course,
    val segment: IntRange,
    val originalStart: Int,
    val originalEnd: Int,
    val deltaY: Float = 0f,
) {
    /** 当前拖动对应的新结束节：末端那条线跟着手指走，吸附到离得最近的一节 */
    fun newEnd(metric: WeekGridMetric): Int {
        val endRank = metric.rankOfPeriod(originalEnd)
        val startRank = metric.rankOfPeriod(originalStart)
        val targetRank = metric.rankAtTop(metric.topOfRank(endRank.toFloat()) + deltaY)
            .coerceIn(startRank, (metric.size - 1).coerceAtLeast(startRank))
        return metric.periodAtRank(targetRank)
    }
}

/** 把某段连续节次替换为新范围，保留课程其它非连续片段并去重排序 */
internal fun resizeCoursePeriods(course: Course, segment: IntRange, newStart: Int, newEnd: Int): List<Int> =
    (course.periods.filter { it !in segment } + (newStart..newEnd))
        .distinct()
        .sorted()
