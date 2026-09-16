package com.buaa.schedule.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 玻璃分段控件（胶囊二选一/多选一）。
 * 用于页面内视图切换（如 周课表/今日），轻量 tint，不做实时模糊。
 *
 * ⚠️ 分段**按内容宽度**排布（`defaultMinSize`，不是 `weight`）：
 * 用 `weight` 时控件会把父级给它的可用宽度全部吃掉，
 * 与它同处一行、带 `weight(1f)` 的兄弟节点就会被挤成 0 宽（顶部"第N周"就是这么消失的）。
 */
@Composable
fun GlassSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    minSegmentWidth: Dp = 64.dp,
) {
    val haptics = LocalHapticFeedback.current
    GlassSurface(
        variant = GlassVariant.COMPACT,
        modifier = modifier,
        shape = RoundedCornerShape(DesignTokens.cornerPill),
        contentPadding = 4.dp,
    ) {
        Row {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = minSegmentWidth)
                        .padding(horizontal = 2.dp)
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.Transparent
                            },
                            shape = RoundedCornerShape(DesignTokens.cornerPill),
                        )
                        .clickable {
                            // 只有真正切换时才反馈，重复点当前项不该震动
                            if (index != selectedIndex) haptics.performTick()
                            onSelect(index)
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
