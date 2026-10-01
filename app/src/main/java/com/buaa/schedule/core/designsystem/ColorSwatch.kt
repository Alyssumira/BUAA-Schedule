package com.buaa.schedule.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * 色板「此处还没有颜色」那颗占位点的取色——**唯一来源**。
 *
 * 收口前同一件事有两页两种灰：设置页「主题种子色 / 默认」那颗是 `0xFFBDBDBD`，
 * 课程编辑器「自定义」那颗是 `0xFF9E9E9E`。两颗都是固定值，于是切到深色主题时
 * 谁都不会跟着换档，并排看就是一深一浅、且在暗底上发闷。
 *
 * 判据是**这颗点不需要与主题无关**：它表达的是"空"，而 M3 的中性色阶里"空"
 * 本来就有一档 —— [androidx.compose.material3.ColorScheme.outlineVariant] 是
 * 低强调中性面/描边的槽位，浅色与深色两套各钉了一值，主题换档它就跟着换档，
 * 所以复用槽位即可，不再新增 token。
 *
 * 两条下限同时满足：点上的文字（"自定义"）经 [contentOn] 选墨后，本项目深浅两套
 * 都在 9:1 以上（浅底走近黑、深底走纯白，两支落点离 [contentOnLuma] 的黑白交点都远，
 * 换主题或换动态取色都不会翻转）；反过来它也不亮到被读成一门真课的颜色——
 * 占位点的职责是"这里没值"，不是"这里有个灰"。
 */
@Composable
fun swatchPlaceholderColor(): Color = MaterialTheme.colorScheme.outlineVariant

/**
 * 色板选点：调色板里的一个圆点，选中时加粗描边 + 勾。
 *
 * 审查 V-色板：编辑器、课程管理、自定义色入口此前各有一份同款圆点（尺寸 30/28/32、
 * 描边 3/2dp 都不一致），收敛到这一个实现。
 * 外圈 48dp 是触控下限，视觉点只有 30dp —— 点与点排成一行时读起来是"色点"，
 * 但每一颗都点得着。
 *
 * @param label 非调色板色的语义描述（如"自定义"）。给了它就不再画勾（勾在灰底上表达不了"这是自定义"），
 *              改为在圈内画这个小字——灰点自己说不出"我能做什么"；
 *              同时也是 contentDescription 的来源。
 */
@Composable
fun ColorSwatch(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    Box(
        modifier = modifier
            .size(DesignTokens.minTouchTarget)
            .clickable(
                role = if (label != null) Role.Button else Role.RadioButton,
                onClick = onClick,
            )
            .semantics { contentDescription = label ?: "颜色" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(color, shape = CircleShape)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = MaterialTheme.colorScheme.onSurface,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                label != null -> Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = contentOn(color),
                )
                selected -> Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "已选择",
                    tint = contentOn(color),
                    modifier = Modifier.size(DesignTokens.iconMedium),
                )
            }
        }
    }
}
