package com.buaa.schedule.core.designsystem

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 课表数据的小图形件（周次密度条 / 学期进度线）。
 *
 * 全站此前只有一个 Canvas——课程卡右上角那个 7dp 的"非每周"三角（`WeekView`）。
 * 其余数量信息一律以文字出现（"第 3 周"、"比上周多 2 门"），而周次本质是一维序列，
 * 用形状读比用数字读快：哪几周一节课都没有、这门课只上到第几周，看图一眼就有。
 *
 * 两条硬规则：
 * - 长度变化一律走 `animateFloatAsState(spec = motionSpec(...))`。系统要求减少动态
 *   效果时 motionSpec 退化成 snap()，图形直接到位，不会留下"半截条"这种中间态；
 * - 颜色只取 `colorScheme` 与 [CourseColors]，不自造常量——这两处会随主题淡入
 *   （见 Theme.kt 的逐槽位过渡），自造常量会在切换的那 260ms 里脱节。
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
