package com.buaa.schedule.core.designsystem

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 课表数据的小图形件（周次密度条 / 学期进度线 / 今日时间带 / 一周负载柱 /
 * 单门课占比条，以及 T51 加的三张增密图：课程周次覆盖条、一周空闲热力格、
 * 学期负载跨周趋势）。
 *
 * 全站此前只有一个 Canvas——课程卡右上角那个 7dp 的"非每周"三角（`WeekView`）。
 * 其余数量信息一律以文字出现（"第 3 周"、"比上周多 2 门"），而周次本质是一维序列，
 * 用形状读比用数字读快：哪几周一节课都没有、这门课只上到第几周，看图一眼就有。
 *
 * 三条硬规则：
 * - 长度变化一律走 `animateFloatAsState(spec = motionSpec(...))`。系统要求减少动态
 *   效果时 motionSpec 退化成 snap()，图形直接到位，不会留下"半截条"这种中间态；
 * - 颜色只取 `colorScheme` 与 [CourseColors]，不自造常量——这两处会随主题淡入
 *   （见 Theme.kt 的逐槽位过渡），自造常量会在切换的那 260ms 里脱节。
 * - **入参一律是已经算好的数据**（数据库查询与归并全在 `domain/schedule/` 的纯 Kotlin
 *   内核里做完），组件自己不查库、不读时钟。设备事实（今天是第几周）当参数传进来，
 *   于是"画的是不是这一周"这件事能在 JVM 单测里钉住。
 *
 * T51 的三张新图（[CourseWeekGantt] / [WeekFreeHeatGrid] / [WeeklyLoadTrendChart]）
 * 是这一族里第一批用 Canvas 自绘的成员：它们的条目数能到几十（一门课一行 /
 * 一节一行 / 一周一点），用 Box 堆就是几十个 composable 各挂一份 semantics 字符串。
 * 画在 Canvas 里还顺手解决了第二件事——动画值读在 draw 阶段，
 * 补间那 380ms 只重绘、不重组（同一件事在 Box 那一族里要靠
 * [fillMaxWidthOf] / [fillMaxHeightOf] 才做到）。
 */

/**
 * 每周的课量：`counts[i]` = 第 i+1 周有多少门课。
 *
 * 抽成纯函数是为了能在 JVM 里钉住"数字从哪来"——图形的绘制不好断言，而这里
 * 有两个真实坑：周次集合可以从中间起排（`WeekParser` 允许 {3,4,5}），
 * 也可以越界（教务给出的周次超出学期总周数）。两种都要给出正确长度而不是崩在下角标。
 */
fun weekCourseCounts(courses: List<Set<Int>>, totalWeeks: Int): IntArray {
    if (totalWeeks <= 0) return IntArray(0)
    val counts = IntArray(totalWeeks)
    for (weeks in courses) {
        for (week in weeks) {
            if (week in 1..totalWeeks) counts[week - 1]++
        }
    }
    return counts
}

/**
 * 周次密度条：一周一根柱子，高度 = 这一周的课量。
 *
 * 用在「跳转到周次」里替代纯文字清单：原来的 19 行 `Text` 只能表达"第 N 周"，
 * 看不出第 12 周之后整个学期就空了、也看不出哪一周课最多。柱子一次说完这两件事。
 *
 * - 当前教学周 = `primary` 实心，正在浏览的那周 = 描一圈 `onSurface`。
 *   两个语义不同不能混用，否则"我在看第几周"和"现在是第几周"会读成一件事；
 * - 零课的那周仍然画一根极矮的素色柱：留白会被读成"这里没画"，而"那周真的没课"
 *   恰恰是要传达的信息；
 * - 全 0（还没排课）时不画一排等高空柱，退化成一句文字。
 */
@Composable
fun WeekDensityStrip(
    counts: IntArray,
    currentWeek: Int?,
    displayWeek: Int?,
    onWeekSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (counts.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val peak = max(1, counts.max())
    val barShape = remember { RoundedCornerShape(DesignTokens.cornerCourse) }
    // 进场只驱动一个总缩放：逐根挂动画会让 19 个 composable 各起一个动画，
    // 而它们本来就是同一次进场。
    var mounted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { mounted = true }
    val grow: FiniteAnimationSpec<Float> = motionSpec(MotionTokens.DURATION_LONG)
    val scale by animateFloatAsState(if (mounted) 1f else 0f, grow, label = "weekStripGrow")

    Column(modifier = modifier) {
        if (counts.all { it == 0 }) {
            Text(
                text = "本学期还没有排课",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
            return@Column
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            counts.forEachIndexed { index, count ->
                val week = index + 1
                val isCurrent = week == currentWeek
                val fillRatio = if (count == 0) 0.08f else 0.18f + 0.82f * count / peak
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onWeekSelected(week) }
                        .semantics {
                            contentDescription = "第 $week 周，$count 门课" +
                                if (isCurrent) "，当前教学周" else ""
                        },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 分数高度乘总缩放：一根条目的进场就是"从底部抽出"。
                            // 缩放读在测量期（见文件末尾 fillMaxHeightOf），否则进场那
                            // 380ms 会把 19 根柱子连同各自的 semantics 字符串每帧重走一遍
                            .fillMaxHeightOf { (fillRatio * scale).coerceIn(0f, 1f) }
                            .background(
                                color = when {
                                    isCurrent -> scheme.primary
                                    count == 0 -> scheme.surfaceVariant
                                    else -> scheme.primary.copy(alpha = 0.32f)
                                },
                                shape = barShape,
                            )
                            // 浏览中的那周：同一形状的描边垫在外圈，不再引入第三种填充色
                            .then(
                                if (week == displayWeek) {
                                    Modifier.padding(0.dp).borderOf(barShape, scheme.onSurface)
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = DesignTokens.spaceXS),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // 刻度行：省得用户自己数柱子。"本周"单独出来，因为 currentWeek 可能为 null（假期中）
            Text(
                text = "共 ${counts.size} 周",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            Text(
                text = currentWeek?.let { "本周第 $it 周" } ?: "假期中",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** 一圈 1.5dp 的描边，形状与所描述的柱子一致 */
private fun Modifier.borderOf(
    shape: androidx.compose.ui.graphics.Shape,
    color: Color,
): Modifier = border(1.5.dp, color, shape)

/**
 * 学期进度线：一条细线，实心部分是已经过完的周。
 *
 * 顶栏原本只有"第 3 周"这一个数，学期长什么样、还剩多少全凭用户自己算。
 * 一条 3dp 的线就能回答，而且它不新增信息层级——它是"第 N 周"这句话的图形化，
 * 所以放在同一行、同一种颜色，读起来是一件事。
 *
 * 宽度从上一帧连续补间：跨零点进入新的一周时它自己长一格，而不是突然跳过去
 * （这条线常驻屏幕，跳变会被看见）。
 */
@Composable
fun SemesterProgressLine(
    currentWeek: Int?,
    totalWeeks: Int,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val target = if (currentWeek != null && totalWeeks > 0) {
        (currentWeek.toFloat() / totalWeeks).coerceIn(0f, 1f)
    } else {
        0f
    }
    val shown by animateFloatAsState(
        targetValue = target,
        animationSpec = motionSpec(MotionTokens.DURATION_MEDIUM),
        label = "semesterProgress",
    )
    val track = RoundedCornerShape(DesignTokens.cornerPill)
    Box(
        modifier = modifier
            .width(96.dp)
            .height(3.dp)
            .background(color = scheme.onSurface.copy(alpha = 0.12f), shape = track),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                // 宽度读在测量期：跨零点补间那 260ms 里不再每帧重组一次这条线、
                // 每帧重算一次渐变
                .fillMaxWidthOf { shown }
                .background(
                    brush = Brush.horizontalGradient(
                        listOf(scheme.primary.copy(alpha = 0.45f), scheme.primary),
                    ),
                    shape = track,
                ),
        )
    }
}

/** 时间带的一段：`from`/`to` 是一天内的 0..1 占比，`lane` 是重叠分层 */
data class TimelineSegment(
    val from: Float,
    val to: Float,
    val lane: Int,
    val colorIndex: Int,
)

/**
 * 取景范围 07:30 → 22:30。早于此 / 晚于此的课次**夹到边缘而不是丢弃**：
 * 一门 7 点开始的课要是整条带看不见，用户读到的就是"今天没课"。
 */
private const val DAY_WINDOW_START_MIN = 7 * 60 + 30
private const val DAY_WINDOW_MINUTES = 15 * 60

/** 把"自零点的分钟数"折成时间带内的 0..1 占比 */
fun dayFractionOfMinute(totalMinutes: Int): Float =
    ((totalMinutes - DAY_WINDOW_START_MIN).toFloat() / DAY_WINDOW_MINUTES).coerceIn(0f, 1f)

/**
 * 今日课次 → 时间带线段（纯函数）。入参是 (startMinute, endMinute, colorIndex)。
 *
 * 分层是必须的、不是为了好看：同一天里 8:00-9:40 与 8:50-10:30 两门课真实存在
 * （换教室、分段课），压在一起就成一根分不清边界的长条，等于没说。
 */
fun dayTimelineSegments(slots: List<Triple<Int, Int, Int>>): List<TimelineSegment> {
    val laneEnds = mutableListOf<Float>()
    return slots.sortedBy { it.first }.map { (startMinute, endMinute, colorIndex) ->
        val from = dayFractionOfMinute(startMinute)
        val to = dayFractionOfMinute(max(startMinute, endMinute))
        val lane = laneEnds.indexOfFirst { it <= from }
        if (lane >= 0) laneEnds[lane] = to else laneEnds.add(to)
        TimelineSegment(
            from = from,
            // 完全重叠（同一时段两间教室）时给一个最小可见宽度，否则这根宽度为 0
            to = if (to <= from) (from + 0.012f).coerceAtMost(1f) else to,
            lane = if (lane >= 0) lane else laneEnds.size - 1,
            colorIndex = colorIndex,
        )
    }
}

/**
 * 今日时间带：把一天的课画成一条横向色带，竖线是"现在"。
 *
 * 今日卡片此前 100% 是文字（"08:00 - 09:40 · 还有 25 分钟下课"），用户得在脑子里
 * 自己排版才知道"下午全是空的""下一节在两小时以后"。一条带子把全天的疏密一次给出，
 * 而"现在"这根竖线顺带回答了"我处在这一天的哪里"。
 *
 * 竖线每分钟才挪一格，但它是**补间**过去的：常驻元素突然跳一格会被眼睛读成"闪了一下"
 * （reduce-motion 下 motionSpec 退化为 snap，直接到位）。
 */
@Composable
fun TodayTimelineStrip(
    segments: List<TimelineSegment>,
    nowFraction: Float?,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    if (segments.isEmpty()) return
    val lanes = segments.maxOf { it.lane } + 1
    val animatedNow by animateFloatAsState(
        targetValue = nowFraction ?: 0f,
        animationSpec = motionSpec(MotionTokens.DURATION_LONG),
        label = "todayNowLine",
    )
    val barShape = remember { RoundedCornerShape(DesignTokens.cornerCourse) }
    val laneHeight = 8.dp
    val laneGap = 3.dp
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(laneHeight * lanes + laneGap * (lanes - 1)),
    ) {
        // 占比换算成像素一次到位：用百分比 padding 定位会在窄屏上累积舍入误差，
        // 两根相邻色条之间露出一道缝
        val widthPx = constraints.maxWidth
        val density = androidx.compose.ui.platform.LocalDensity.current.density
        val laneGapPx = with(androidx.compose.ui.platform.LocalDensity.current) {
            (laneHeight + laneGap).roundToPx()
        }
        segments.forEach { seg ->
            val startPx = (seg.from * widthPx).roundToInt()
            val endPx = (seg.to * widthPx).roundToInt()
            Box(
                modifier = Modifier
                    .offset { IntOffset(startPx, laneGapPx * seg.lane) }
                    .width((max(2, endPx - startPx) / density).dp)
                    .height(laneHeight)
                    .background(
                        color = CourseColors[Math.floorMod(seg.colorIndex, CourseColors.size)]
                            .copy(alpha = 0.75f),
                        shape = barShape,
                    ),
            )
        }
        if (nowFraction != null) {
            Box(
                modifier = Modifier
                    .offset { IntOffset((animatedNow * widthPx).roundToInt(), 0) }
                    .width(2.dp)
                    .fillMaxHeight()
                    // 与周课表的时间指示线同一个语义色（WeekView 的 NowLine 也走 error）
                    .background(scheme.error),
            )
        }
    }
}

/**
 * 一周负载柱状图：一天一根柱，高度 = 该天全学期的平均每周上课分钟数。
 *
 * 统计页此前的说法是"周中第 3 天最忙"，这句话要用户自己去对照哪天是周三；
 * 七根柱子把"忙"和"闲"直接摊开，哪天真的一天没课也一眼看得见。
 *
 * - 用平均值而不是总分钟数：只上 1-8 周的课在后 11 周不该继续占高度，
 *   否则"前紧后松"的课表会被读成整学期都这么忙；
 * - 最忙那天用实心 primary，其余半透明——只标一个"最"，不排全套名次，
 *   七种深浅读起来只是一团灰；
 * - 空那天画一根极矮的素色柱（与 [WeekDensityStrip] 同一口径）：完全留白会被读成"没画"。
 */
@Composable
fun DayLoadBars(
    averageMinutes: List<Long>,
    busiestDayIndex: Int?,
    modifier: Modifier = Modifier,
) {
    if (averageMinutes.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val peak = max(1L, averageMinutes.max())
    val barShape = remember { RoundedCornerShape(DesignTokens.cornerCourse) }
    val weekdayLabels = remember { "一二三四五六日" }
    var mounted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { mounted = true }
    val scale by animateFloatAsState(
        targetValue = if (mounted) 1f else 0f,
        animationSpec = motionSpec(MotionTokens.DURATION_LONG),
        label = "dayLoadGrow",
    )

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.spaceS),
        ) {
            averageMinutes.forEachIndexed { index, minutes ->
                val isBusiest = index == busiestDayIndex
                val ratio = if (minutes == 0L) 0.06f else 0.12f + 0.88f * minutes / peak
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics {
                            contentDescription = "周${weekdayLabels[index]}，平均每周 ${hoursOf(minutes)}"
                        },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 缩放读在测量期（见文件末尾 fillMaxHeightOf）：进场那 380ms
                            // 不再把七根柱子连各自的 semantics 字符串每帧重走一遍
                            .fillMaxHeightOf { (ratio * scale).coerceIn(0f, 1f) }
                            .background(
                                color = when {
                                    minutes == 0L -> scheme.surfaceVariant
                                    isBusiest -> scheme.primary
                                    else -> scheme.primary.copy(alpha = 0.32f)
                                },
                                shape = barShape,
                            ),
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = DesignTokens.spaceXS),
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.spaceS),
        ) {
            // 刻度与柱子同权重，否则七个字会被挤成靠左的一坨
            averageMinutes.indices.forEach { index ->
                Text(
                    text = "周${weekdayLabels[index]}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (index == busiestDayIndex) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
            }
        }
    }
}

/** "3 小时 20 分钟" / "45 分钟" / "0 分钟"——读屏与文字行共用这一个口径 */
private fun hoursOf(minutes: Long): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h <= 0L -> "$m 分钟"
        m <= 0L -> "$h 小时"
        else -> "$h 小时 $m 分钟"
    }
}

/**
 * 一条横向占比条：统计页用它表示单门课学分的相对量。
 *
 * 与 [SemesterProgressLine] 同一种画法（轨道 + 渐变填充），但宽度由调用方给，
 * 因为这里要跟着一行课程名对齐，而不是自己占死一个尺寸。
 */
@Composable
fun MiniBar(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shown by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = motionSpec(MotionTokens.DURATION_MEDIUM),
        label = "miniBar",
    )
    val track = RoundedCornerShape(DesignTokens.cornerPill)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(color = scheme.onSurface.copy(alpha = 0.10f), shape = track),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                // 同上：宽度读在测量期，260ms 的补间不再拖着这根条每帧重组
                .fillMaxWidthOf { shown }
                .background(brush = Brush.horizontalGradient(listOf(color.copy(alpha = 0.5f), color)), shape = track),
        )
    }
}

// ==== T51：三张增密图（单门课周次覆盖 / 一周空闲热力格 / 学期负载跨周趋势）====

/** 横条 Gantt 折叠时先画几门课：再多就把"每周负载""空档"那两张卡挤出首屏 */
private const val GanttCollapsedRowCount = 8

/** 标签列与轨道列的宽度分配：轨道要装下 19 个可分辨的格位，所以给它两倍多 */
private const val GanttLabelWeight = 1f
private const val GanttTrackWeight = 1.9f

/** 已过去的周次 / 还在上的周次的浓度差：同一个色相只动 alpha，色相留给课程本身 */
private const val GanttPastAlpha = 0.30f
private const val GanttUpcomingAlpha = 0.88f

/** 整条都已经上完的课：过去段再提一档，否则一根条全淡到读不出边界 */
private const val GanttFinishedPastAlpha = 0.42f

/** 一根条目的厚度与行距都从版面刻度取：与课程卡同一档，不留新的魔法数 */
private val GanttBarHeight = DesignTokens.iconSmall
private val GanttRowPitch = DesignTokens.iconSmall + DesignTokens.spaceS

/** 热力格一格的边长与缝：格 = 一节 × 一天，读的是"亮/暗"两态，不需要更大 */
private val HeatCellSize = DesignTokens.iconSmall
private val HeatCellGap = DesignTokens.spaceMicro

/** 一周超过这么多格就不画竖线：几十行 × 三十道 hairline 是白花的开销 */
private const val MaxVisibleGridLines = 20

/**
 * 横条几何（纯函数）：一条周次区间在轴上占的 0..1 占比。
 *
 * 抽出来是因为这里有个真实的坑：周次可以越出学期（教务给过 1-20 周而学期只有 16 周），
 * 直接算 `span.last / totalWeeks` 会得出 1.25 这种把条画出画布外的数。夹住而不是丢，
 * 丢会让"跨到学期末"的课看起来提前结课。
 */
fun ganttSpanFraction(span: IntRange, totalWeeks: Int): Pair<Float, Float> {
    if (totalWeeks <= 0) return 0f to 0f
    val from = ((span.first - 1).toFloat() / totalWeeks).coerceIn(0f, 1f)
    val to = (span.last.toFloat() / totalWeeks).coerceIn(0f, 1f)
    return from to max(from, to)
}

/** 第 [week] 周在轴上的起点（游标落点）；周号越界时夹进画布 */
fun ganttWeekStartFraction(week: Int, totalWeeks: Int): Float =
    if (totalWeeks <= 0) 0f else ((week - 1).toFloat() / totalWeeks).coerceIn(0f, 1f)

/** 折线几何（纯函数）：`x 占比 to y 占比`，y 以**顶部为 0**，所以地板那一档是 1 */
fun weeklyLoadPoints(minutes: List<Long>): List<Pair<Float, Float>> {
    if (minutes.isEmpty()) return emptyList()
    val top = max(1L, minutes.maxOrNull() ?: 1L)
    val last = minutes.size - 1
    return minutes.mapIndexed { index, value ->
        val x = if (last <= 0) 0.5f else index.toFloat() / last
        x to (1f - (value.toFloat() / top).coerceIn(0f, 1f))
    }
}

/**
 * 一根横条的读屏文案（纯函数）。
 *
 * Canvas 画的东西读屏拿不到，而这张图的信息全在"哪几段周次"上，所以数值要在这里
 * 以文字复述一遍——系统开着减少动态效果时，这一行也就是全部信息，不能比图上少。
 */
fun ganttRowDescription(row: GanttRow, totalWeeks: Int, currentWeek: Int?): String {
    if (row.weeksUnknown) return "${row.label}：没有可用的周次数据（不是整学期没课）"
    val segments = if (row.spans.isEmpty()) {
        "本学期没有落在周次范围内的排课"
    } else {
        row.spans.joinToString("、") { span ->
            if (span.first == span.last) "第 ${span.first} 周" else "第 ${span.first}-${span.last} 周"
        }
    }
    val ending = row.endsAtWeek?.let { week ->
        when {
            currentWeek == null -> "第 $week 周结课"
            week < currentWeek -> "第 $week 周结课，已结束"
            else -> "第 $week 周结课，还剩 ${week - currentWeek + 1} 周"
        }
    }
    return listOfNotNull("${row.label}：$segments", ending).joinToString("，")
}

/** 热力格一天的读屏文案（纯函数，同上）。`currentWeek == null` 时口径换成"全学期" */
fun heatGridDayDescription(day: HeatGridDay, currentWeek: Int?): String {
    val occupied = day.occupiedPeriods.count { it }
    val scope = if (currentWeek == null) "全学期" else "第 $currentWeek 周"
    return if (occupied == 0) {
        "${day.label}：$scope 一节都没有"
    } else {
        "${day.label}：$scope 上 $occupied 节"
    }
}

/** [CourseWeekGantt] 的一行：一门课（归并后的，不是一条排课片段） */
data class GanttRow(
    val label: String,
    val color: Color,
    val spans: List<IntRange>,
    val weeksUnknown: Boolean = false,
    val finished: Boolean = false,
    val endsAtWeek: Int? = null,
)

/** [WeekFreeHeatGrid] 的一行：一天 */
data class HeatGridDay(
    val label: String,
    val occupiedPeriods: List<Boolean>,
    val isEmptiest: Boolean = false,
)

/**
 * 课程周次覆盖条：一门课一行，横轴 = 学期第 1..N 周。
 *
 * 统计页此前只有"周三平均每周 2 小时"这种全学期平均数，而它把
 * "只上 1-8 周"和"整学期都上"抹成了同一句话。这张图说的一定是另一半：
 * 这门课上到哪一周、中间断不断、本周在第几格。
 *
 * - 连续周次并成一根、单周/双周/离散周次画成多根（数据来自 `Course.weeks`，
 *   并成一根就是把"只在单周上"说成"整段都在上"）；
 * - 游标左边的周次淡一档（已经过去），右边浓一档；[GanttRow.weeksUnknown]
 *   那几行画成虚线轨道——**"没有周次数据"不能画成空轨道**，空轨道的读法是
 *   "这门课整学期都没课"，那是假的；
 * - 条目可以到几十行，所以默认只画前 [GanttCollapsedRowCount] 门、剩下点开；
 *   顺序由 `domain/schedule/CourseWeekSpans` 排好（先结课的在前），
 *   组件这一层不重排、不归并，只画。
 */
@Composable
fun CourseWeekGantt(
    rows: List<GanttRow>,
    totalWeeks: Int,
    currentWeek: Int?,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    if (rows.isEmpty() || totalWeeks <= 0) {
        // 没有课 / 学期周数未知：退化成一句话，不画一根空轨道
        Text(
            text = if (rows.isEmpty()) "还没有课程，画不出周次覆盖" else "学期周数未设置，画不出周次覆盖",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    val collapsible = rows.size > GanttCollapsedRowCount
    var expanded by remember { mutableStateOf(false) }
    val visibleRows = if (expanded) rows else rows.take(GanttCollapsedRowCount)
    var mounted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { mounted = true }
    // 整块图只有一个进场缩放（与 WeekDensityStrip 同一口径：不逐行挂动画），
    // 而且它读在 draw 阶段——那 380ms 里只有重绘、没有重组
    val reveal by animateFloatAsState(
        targetValue = if (mounted) 1f else 0f,
        animationSpec = motionSpec(MotionTokens.DURATION_LONG),
        label = "ganttReveal",
    )

    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(
                modifier = Modifier
                    .weight(GanttLabelWeight)
                    .padding(end = DesignTokens.spaceS),
            )
            Row(
                modifier = Modifier.weight(GanttTrackWeight),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // 刻度行：省得用户自己数第几格是第几周
                Text("第 1 周", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                Text("第 $totalWeeks 周", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
        }
        Box(modifier = Modifier.fillMaxWidth()) {
            // 背景层：格线与游标一次画完。几十行各画 19 道 hairline 是白花的开销，
            // 而且"本周"是一件事、不该在每一行里各出现一根短线。
            // 高度按行数自己算死（不用 matchParentSize：外层是可滚动列，约束是无限高）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(GanttRowPitch * visibleRows.size),
            ) {
                Spacer(
                    modifier = Modifier
                        .weight(GanttLabelWeight)
                        .padding(end = DesignTokens.spaceS),
                )
                Box(modifier = Modifier.weight(GanttTrackWeight)) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        clipRect(right = size.width * reveal) {
                            if (totalWeeks <= MaxVisibleGridLines) {
                                for (week in 2..totalWeeks) {
                                    val x = ganttWeekStartFraction(week, totalWeeks) * size.width
                                    drawLine(
                                        color = scheme.outlineVariant,
                                        start = Offset(x, 0f),
                                        end = Offset(x, size.height),
                                        strokeWidth = 1.dp.toPx(),
                                    )
                                }
                            }
                            if (currentWeek != null) {
                                val x = ganttWeekStartFraction(currentWeek, totalWeeks) * size.width
                                // 游标不借 error 红：那个色号在周/日视图里是"现在这一刻"独享的
                                // （T49b④ 刚把它收回去），这里画的是"哪一周"不是"哪一分钟"
                                drawLine(
                                    color = scheme.onSurface,
                                    start = Offset(x, 0f),
                                    end = Offset(x, size.height),
                                    strokeWidth = 1.5.dp.toPx(),
                                )
                            }
                        }
                    }
                }
            }
            visibleRows.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(GanttRowPitch),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = row.label,
                        style = MaterialTheme.typography.bodySmall,
                        // 已结课的那门课名字也跟着淡一档：明度是"还要不要上"的第二通道
                        color = if (row.finished) scheme.onSurfaceVariant else scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(GanttLabelWeight)
                            .padding(end = DesignTokens.spaceS),
                    )
                    Box(
                        modifier = Modifier
                            .weight(GanttTrackWeight)
                            .height(GanttBarHeight)
                            .semantics {
                                contentDescription = ganttRowDescription(row, totalWeeks, currentWeek)
                            },
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val width = size.width
                            val height = size.height
                            val corner = CornerRadius(height / 2f, height / 2f)
                            // 轨道：整学期留白会被读成"这里没画"，先铺一层素色（同 WeekDensityStrip）
                            drawRoundRect(
                                color = scheme.surfaceVariant,
                                size = Size(width, height),
                                cornerRadius = corner,
                            )
                            clipRect(right = width * reveal) {
                                if (row.weeksUnknown) {
                                    drawDashedLine(
                                        color = scheme.outline,
                                        y = height / 2f,
                                        fromX = 0f,
                                        toX = width,
                                        dash = DesignTokens.spaceS.toPx(),
                                    )
                                } else {
                                    row.spans.forEach { span ->
                                        val (from, to) = ganttSpanFraction(span, totalWeeks)
                                        // 过去/未来分两档浓度；当前周拿不到就不分——
                                        // 没有原点时"哪一段过去了"这个问题不成立
                                        val split = currentWeek?.let {
                                            ganttWeekStartFraction(it, totalWeeks).coerceIn(from, to)
                                        } ?: to
                                        if (split > from) {
                                            drawRoundRect(
                                                color = row.color.copy(
                                                    alpha = if (row.finished) GanttFinishedPastAlpha else GanttPastAlpha,
                                                ),
                                                topLeft = Offset(from * width, 0f),
                                                size = Size((split - from) * width, height),
                                                cornerRadius = corner,
                                            )
                                        }
                                        if (to > split) {
                                            drawRoundRect(
                                                color = row.color.copy(alpha = GanttUpcomingAlpha),
                                                topLeft = Offset(split * width, 0f),
                                                size = Size((to - split) * width, height),
                                                cornerRadius = corner,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Text(
            text = currentWeek?.let { "游标以左是已过去的周 · 本周第 $it 周" }
                ?: "学期原点缺失：不分已过去与将来",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = DesignTokens.spaceXS),
        )
        if (collapsible) {
            Text(
                text = if (expanded) "收起" else "展开全部 ${rows.size} 门（现在显示最先结课的前 $GanttCollapsedRowCount 门）",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.primary,
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(vertical = DesignTokens.spaceS),
            )
        }
    }
}

/** Canvas 没有现成的 dash，"这一格没有数据"就得自己一段段铺出来 */
private fun DrawScope.drawDashedLine(
    color: Color,
    y: Float,
    fromX: Float,
    toX: Float,
    dash: Float,
) {
    if (dash <= 0f) return
    var x = fromX
    while (x < toX) {
        val end = minOf(x + dash, toX)
        drawLine(color, Offset(x, y), Offset(end, y), dash / 2.5f)
        x = end + dash / 2f
    }
}

/**
 * 一周空闲热力格：7 行（周一..周日）× 节次列，一格亮 = 那一天的那一节有课。
 *
 * 它补的是 [DayLoadBars] 说不了的那半句话：日负载柱是**全学期平均**，
 * "周三 9-10 节全学期只出现过一次"和"周三下午每周都排满"会是同一根柱子的高度。
 * 这一格矩阵按**指定的那一周**逐格判定（判定在 `WeekFreeGrid` 内核里，可 JVM 单测），
 * 所以"周三下午到底空不空"是看图就有，而一门 1-8 周的课不会在第 12 周继续亮着。
 *
 * - 亮/暗两态用的是 `primary` 与 `surfaceVariant`，不是"画/不画"：
 *   空格子也要占一格，否则整行留白读起来像"这一行没数据"；
 * - [HeatGridDay.isEmptiest] 那行的行名换成 `primary`，一眼定位"最空的是哪天"；
 * - 整列都不亮的那几个节次由调用方在图下用文字说（`WeekFreeGrid.freePeriods`），
 *   不在格子里再叠第三种颜色。
 */
@Composable
fun WeekFreeHeatGrid(
    days: List<HeatGridDay>,
    periodLabels: List<String>,
    currentWeek: Int?,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    if (days.isEmpty() || periodLabels.isEmpty()) {
        Text(
            text = "作息表里没有可画的节次",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    val occupiedColor = scheme.primary.copy(alpha = DesignTokens.dayBlockTintAlpha)
    val freeColor = scheme.surfaceVariant
    val cellShape = remember { RoundedCornerShape(DesignTokens.cornerChip) }
    var mounted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { mounted = true }
    val reveal by animateFloatAsState(
        targetValue = if (mounted) 1f else 0f,
        animationSpec = motionSpec(MotionTokens.DURATION_LONG),
        label = "heatGridReveal",
    )

    Column(modifier = modifier) {
        // 列头：节次号。与下面的格子同一套"weight + spacedBy(同一条缝)"的分法，才对得齐
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HeatCellGap),
        ) {
            Text(
                text = if (currentWeek == null) "全学期" else "第 ${currentWeek}周",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
            )
            periodLabels.forEach { label ->
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        days.forEach { day ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(HeatCellSize),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(HeatCellGap),
            ) {
                Text(
                    text = day.label,
                    style = MaterialTheme.typography.labelMedium,
                    // 最空的那天用 primary 标行名：它是这张图要回答的问题本身
                    color = if (day.isEmptiest) scheme.primary else scheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.semantics {
                        contentDescription = heatGridDayDescription(day, currentWeek)
                    },
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(HeatCellSize),
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val columns = day.occupiedPeriods.size
                        if (columns == 0) return@Canvas
                        val gapPx = HeatCellGap.toPx()
                        val cellWidth = (size.width - gapPx * (columns - 1)) / columns
                        if (cellWidth <= 0f) return@Canvas
                        val corner = CornerRadius(cellWidth / 3f, cellWidth / 3f)
                        day.occupiedPeriods.forEachIndexed { index, occupied ->
                            // 逐格依次点亮（reveal 读在 draw 阶段）；reduce-motion 下
                            // motionSpec 直接 snap，一到位就是完整矩阵，信息一点不少
                            val staged = (reveal * (columns + 1) - index).coerceIn(0f, 1f)
                            if (staged <= 0f) return@forEachIndexed
                            val left = index * (cellWidth + gapPx)
                            drawRoundRect(
                                color = if (occupied) occupiedColor else freeColor,
                                topLeft = Offset(left, 0f),
                                size = Size(cellWidth * staged, size.height),
                                cornerRadius = corner,
                            )
                        }
                    }
                }
            }
        }
        // 图例：两态必须当场说明，否则"暗格"会被读成"这块没数据"
        Row(
            modifier = Modifier.padding(top = DesignTokens.spaceXS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.spaceXS),
        ) {
            Box(
                modifier = Modifier
                    .size(HeatCellSize / 2)
                    .background(color = occupiedColor, shape = cellShape),
            )
            Text("有课", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            Box(
                modifier = Modifier
                    .size(HeatCellSize / 2)
                    .background(color = freeColor, shape = cellShape),
            )
            Text("空", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
    }
}

/**
 * 学期负载跨周趋势：横轴周次、纵轴该周上课分钟数的面积 + 折线。
 *
 * 与 [WeekDensityStrip] 的分工不一样：那张画的是"每周有几门课"（计数，用来跳周次），
 * 这张画的是"每周上多少分钟"（时长，用来看出学期什么时候塌下去）。
 * 三门 45 分钟的课和一门 45 分钟的课在计数条上是两根一样的柱子，在这里差三倍高。
 *
 * - 峰值周实心点标出来（只标一个"最"，与 [DayLoadBars] 同一口径）；
 * - 结课周在轴底打小旗（`endings` 来自内核，只有真的有课在那一周结束时才有旗，
 *   学期最后一周不标——那叫学期结束）；
 * - 游标同 [CourseWeekGantt]：`onSurface`，不借 error 红；
 * - [dataAbsent] 为真时**不画贴地板的线**：没有周次数据与整学期零分钟是两件事，
 *   画出来只能画第一件。
 */
@Composable
fun WeeklyLoadTrendChart(
    minutes: List<Long>,
    totalWeeks: Int,
    currentWeek: Int?,
    peakWeek: Int?,
    endings: Map<Int, Int>,
    dataAbsent: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    if (dataAbsent || minutes.isEmpty() || totalWeeks <= 0) {
        Text(
            text = "课程里没有可用的周次数据，这条线不画",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    var mounted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { mounted = true }
    val reveal by animateFloatAsState(
        targetValue = if (mounted) 1f else 0f,
        animationSpec = motionSpec(MotionTokens.DURATION_LONG),
        label = "weeklyLoadReveal",
    )

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
        ) {
            val width = size.width
            val height = size.height
            val points = weeklyLoadPoints(minutes)
            val padTop = DesignTokens.spaceS.toPx()
            val bottom = height - padTop
            // 地板线：面积图的下沿要有参照物，否则"满高"和"满屏"分不清
            drawLine(
                color = scheme.outlineVariant,
                start = Offset(0f, bottom),
                end = Offset(width, bottom),
                strokeWidth = 1.dp.toPx(),
            )
            val stepX = if (points.size <= 1) 0f else width / (points.size - 1)
            val centers = points.mapIndexed { index, point ->
                Offset(
                    x = if (stepX == 0f) width / 2f else stepX * index,
                    y = padTop + point.second * (bottom - padTop),
                )
            }
            clipRect(right = width * reveal) {
                val area = Path().apply {
                    moveTo(centers.first().x, bottom)
                    centers.forEach { lineTo(it.x, it.y) }
                    lineTo(centers.last().x, bottom)
                    close()
                }
                drawPath(
                    path = area,
                    brush = Brush.verticalGradient(
                        listOf(scheme.primary.copy(alpha = 0.45f), scheme.primary.copy(alpha = 0.06f)),
                    ),
                )
                val line = Path().apply {
                    moveTo(centers.first().x, centers.first().y)
                    centers.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(path = line, color = scheme.primary, style = Stroke(width = 1.5.dp.toPx()))
            }
            val radius = DesignTokens.spaceMicro.toPx() * 1.5f
            centers.forEachIndexed { index, center ->
                val week = index + 1
                if (week == peakWeek) {
                    drawCircle(color = scheme.primary, radius = radius, center = center)
                }
            }
            // 结课旗：轴底一小段竖线，颜色用 secondary，不与"负载"这条线抢
            endings.keys.forEach { week ->
                if (week - 1 !in centers.indices) return@forEach
                val x = centers[week - 1].x
                drawLine(
                    color = scheme.secondary,
                    start = Offset(x, bottom),
                    end = Offset(x, bottom - DesignTokens.spaceS.toPx()),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
            if (currentWeek != null && currentWeek in 1..centers.size) {
                val x = centers[currentWeek - 1].x
                drawLine(
                    color = scheme.onSurface,
                    start = Offset(x, padTop),
                    end = Offset(x, bottom),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = DesignTokens.spaceXS),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("第 1 周", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            Text(
                text = peakWeek?.let { "最忙：第 $it 周" } ?: "没有有课的周",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.primary,
            )
            Text("第 $totalWeeks 周", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
    }
}

/**
 * `Modifier.fillMaxWidth(fraction)` 的**测量期**版本：算法一字不改，只是 fraction
 * 改在 measure 块里求值。
 *
 * 差别很实在：`fillMaxWidth(fraction = shown)` 里的 `shown` 是补间动画的当前值，
 * 只能读在组合期——于是统计页 / 学期进度线每次长度补间，都在**每一帧**把整块图形
 * （每根柱子、每段 semantics 文案、每次渐变 Brush）重走一遍组合。读在这里，状态
 * 变化就只重测这一个盒子。
 */
private fun Modifier.fillMaxWidthOf(fraction: () -> Float): Modifier =
    layout { measurable, constraints ->
        val f = fraction()
        val newWidth = if (constraints.hasBoundedWidth) {
            (constraints.maxWidth * f).roundToInt()
        } else {
            (constraints.minWidth * f).roundToInt()
        }
        val placeable = measurable.measure(constraints.copy(minWidth = newWidth, maxWidth = newWidth))
        layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }

/** [fillMaxWidthOf] 的竖向版本，对应 `Modifier.fillMaxHeight(fraction)` */
private fun Modifier.fillMaxHeightOf(fraction: () -> Float): Modifier =
    layout { measurable, constraints ->
        val f = fraction()
        val newHeight = if (constraints.hasBoundedHeight) {
            (constraints.maxHeight * f).roundToInt()
        } else {
            (constraints.minHeight * f).roundToInt()
        }
        val placeable = measurable.measure(constraints.copy(minHeight = newHeight, maxHeight = newHeight))
        layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }
