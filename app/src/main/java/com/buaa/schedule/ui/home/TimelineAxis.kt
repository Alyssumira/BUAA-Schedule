package com.buaa.schedule.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalTime

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
    val total = endMin - startMin
    val fraction = if (total <= 0) 0f else {
        val now = nowTickState.value.let { it.hour * 60 + it.minute }
        ((now - startMin).toFloat() / total).coerceIn(0f, 1f)
    }
    if (fraction <= 0f || fraction >= 1f) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .offset(y = totalHeight * fraction)
            .background(MaterialTheme.colorScheme.error),
    )
}
