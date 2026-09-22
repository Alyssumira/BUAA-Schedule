package com.buaa.schedule.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.buaa.schedule.core.designsystem.MotionTokens
import com.buaa.schedule.core.designsystem.motionSpec
import java.time.LocalTime
import kotlin.math.roundToInt

/**
 * 时间轴坐标系的公共绘制件：原来 private 在 WeekView.kt 里，
 * 日视图时间轴（T49）要画同一套小时刻度与「现在」线，两处各留一份
 * 就会在下次调口径时只改到一边。提取只动了可见性（private → internal），
 * 函数体与渲染结果逐字未动——周视图的调用点在同一个包里，签名兼容。
 */

/**
 * 刻度文字在小时格内的纵向锚点（[HourLabels] 的对齐档）。
 *
 * 两档对应两种画布：周视图 24h 模式**不画整点线**（全文件没有 outlineVariant），
 * 文字归属由所在行决定，居中即可；日视图时间轴（T49）画了整点线，居中档的
 * 文字便恰好落在自己那条线与下一条线的正中间——真机实测（buaa36，周二）
 * 09:00 线在 y=830 而 "09:00" 文字中心在 y=903，逐枚偏后半格，用户没法判断
 * 哪条线是几点。[LineTop] 把文字顶边贴到格顶 = 自己那枚整点上。
 */
internal enum class HourLabelAnchor(val cellAlignment: Alignment) {
    /** 既有口径（周视图）：文字在小时格内逐像素居中，默认档 */
    SlotCenter(Alignment.Center),

    /** 日视图时间轴：文字顶边对齐格顶（即该整点的网格线所在高度），水平仍居中 */
    LineTop(Alignment.TopCenter),
}

/** 24h 时间轴模式的小时刻度列 */
@Composable
internal fun HourLabels(
    startHour: Int,
    endHour: Int,
    hourHeight: Dp,
    anchor: HourLabelAnchor = HourLabelAnchor.SlotCenter,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        (startHour until endHour).forEach { hour ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(hourHeight),
                contentAlignment = anchor.cellAlignment,
            ) {
                Text(
                    text = "%02d:00".format(hour),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 当前时间指示线：独立组合作用域，每分钟只重组这一条线 */
@Composable
internal fun NowLine(
    visible: Boolean,
    startMin: Int,
    endMin: Int,
    totalHeight: Dp,
    nowTickState: State<LocalTime>,
) {
    if (!visible) return
    val now = nowTickState.value
    // 落点算式在 DayTimelineAxis.kt（零 android 的判据内核）：日视图与周视图 24h 模式
    // 画的是同一条线，越界不画的边界行为也只写一处，两边不会再各改各的。
    NowGlideLine(nowLineFraction(timelineMinuteOfDay(now), startMin, endMin), totalHeight)
}

/**
 * 「现在」线本体：一条 2dp 的横线 + 一分钟一跳补成的一段滑行（T52③）。
 *
 * 为什么原来读起来"卡"：线的位置来自 `timelineMinuteOfDay(now)`，**只到分钟**——
 * 15 秒那四拍里有三拍发布的是同一个值，线每 60 秒才动一次，一次两三个像素。
 * 单帧到位就是"瞬移"，眼睛对 2dp 物体的瞬移比对它的位置敏感得多。
 *
 * 修法只有这一条路可走：**给那一跳补一段过渡**，而不是把心跳调勤。
 * 时长取 [MotionTokens.DURATION_LONG]：一次位移约 3.5px（1.35dp/分钟 × 2.625 密度，
 * 真机密度档；T61 把每分钟 1.05 抬到 1.35，位移同比例变长，档位选择不变），
 * 要它读作"在走"而不是"跳"就得慢到肉眼不再追踪起点，380ms 是
 * MotionTokens 里"强调型进出场"这一档；而它占空比只有 380ms/60s ≈ 0.6%，
 * 省电账不受影响——把补间拉到 60 秒才是灾难（那等于让 Compose 一直开帧）。
 *
 * 中间那三拍发布同一个值：`animateFloatAsState` 的目标值按 key 比较，
 * 值没变就不会重启动画，所以一分钟内只起一次补间，不会叠成"走两步退一步"。
 * reduce-motion 与"治理判定本机跑不动玻璃"两条开关都从 `motionSpec` 生效（ snapping 到位）。
 *
 * ⚠️ 位移走 `graphicsLayer`，不走 `Modifier.offset(Dp)`：offset 是布局修饰符，
 * 补间期间等于每帧重测这条线；layer 只改变换矩阵，且 `glide.value` 只在
 * layer 块里读——**这条链每帧不产生一次重组**（对照 WeekView 里 pulse 的同款约束）。
 * 落点两边同口径：`offset(Dp)` 走 roundToPx，这里 roundToInt 后按整像素平移。
 */
@Composable
internal fun NowGlideLine(fraction: Float, totalHeight: Dp) {
    if (fraction <= 0f || fraction >= 1f) return
    val glide = animateFloatAsState(
        targetValue = fraction,
        animationSpec = motionSpec<Float>(MotionTokens.DURATION_LONG),
        label = "nowLineGlide",
    )
    val lineHeightPx = with(LocalDensity.current) { totalHeight.toPx() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .graphicsLayer { translationY = (lineHeightPx * glide.value).roundToInt().toFloat() }
            .background(MaterialTheme.colorScheme.error),
    )
}
