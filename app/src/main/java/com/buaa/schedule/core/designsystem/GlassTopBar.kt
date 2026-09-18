package com.buaa.schedule.core.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow

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
 * @param subtitle 标题下的次级说明（日期区间、当前学期这类）。为空时栏体保持 48dp 基准高。
 * @param progress 加载进度 0f..1f，渲染在栏体底部边缘；null 表示不显示。
 *   登录/扫码这类 WebView 页此前各自手写一条内联 `LinearProgressIndicator`，
 *   收敛到这里后顶栏高度、圆角、材质由同一处保证。
 * @param statusBarInset 为 true 时玻璃仍铺到状态栏后面、内容整体下移一个状态栏高度。
 *   给**非 Scaffold** 的全屏页（登录/扫码）用；Scaffold 的 topBar 槽自带 inset，别重复加。
 * @param onBack 为空时不渲染返回按钮（一级页面）
 */
@Composable
fun GlassTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    progress: Float? = null,
    statusBarInset: Boolean = false,
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
        Column(
            modifier = if (statusBarInset) Modifier.statusBarsPadding() else Modifier,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = DesignTokens.topBarHeight)
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        // 页头主名要压过页内 SectionHeader 的 titleSmall，否则标题与组标题同档（§3）
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                actions()
            }
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
