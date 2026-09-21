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

/** 24h 时间轴模式的小时刻度列 */
@Composable
internal fun HourLabels(startHour: Int, endHour: Int, hourHeight: Dp) {
    Column(modifier = Modifier.fillMaxSize()) {
        (startHour until endHour).forEach { hour ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(hourHeight),
                contentAlignment = Alignment.Center,
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
