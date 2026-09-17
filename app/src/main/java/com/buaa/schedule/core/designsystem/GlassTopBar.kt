package com.buaa.schedule.core.designsystem

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 统一玻璃顶栏：二级页面的标题栏。
 *
 * 为什么不用 M3 的 `TopAppBar`：它是**不透明**的 surface 色 + 方角，
 * 浮在液态玻璃页身之上时是一条与页面割裂的色条（尤其是设置/导入这种整页都是
 * 圆角玻璃片的界面）。这里用与页身同一套液态玻璃材质（[GlassVariant.CHROME]），
 * 只在底部做圆角，视觉上与下面的玻璃内容连成一片。
 *
 * 返回按钮保持"文字按钮"而不是箭头图标：与站内其它入口（登录页顶栏）一致。
 *
 * @param title 标题，超长自动省略（避免挤压右侧 actions）
 * @param onBack 为空时不渲染返回按钮（一级页面）
 */
@Composable
fun GlassTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    GlassSurface(
        variant = GlassVariant.CHROME,
        contentPadding = DesignTokens.spaceS,
        shape = RoundedCornerShape(
            bottomStart = DesignTokens.cornerPanel,
            bottomEnd = DesignTokens.cornerPanel,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(GLASS_TOP_BAR_HEIGHT)
                .padding(horizontal = DesignTokens.spaceS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                // M3 TextButton 默认最小高 40dp：在 48dp 的栏体里仍够不到触控下限（U-10）
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.defaultMinSize(minHeight = DesignTokens.minTouchTarget),
                ) { Text("返回") }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            actions()
        }
    }
}

/**
 * 顶栏高度：各页保持一致，避免标题栏高低不一。
 *
 * 不能低于 48dp —— 这一行的高度就是内部 IconButton/TextButton 触控目标的上限，
 * 44dp 会把 Material 默认的 48dp 最小可点区域压掉一圈（R5 F-49）。
 */
private val GLASS_TOP_BAR_HEIGHT = 48.dp
