// Based on Kyant0/AndroidLiquidGlass catalog components, Apache-2.0.
// Modified for SleepDown-Schedule.
package com.buaa.schedule.core.designsystem.liquid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule

internal val LocalLiquidBottomTabScale =
    staticCompositionLocalOf { { 1f } }

/**
 * MovingAccent 隐藏层为指示器提供采样内容时置 true：
 * 该层内容按主题色渲染（Compose 1.7 的 graphicsLayer 无 colorFilter 参数，
 * 用 CompositionLocal 替代 SleepDown 的 ColorFilter.tint 方案）。
 */
val LocalLiquidBottomTabAccentTint =
    staticCompositionLocalOf { false }

/**
 * 底栏 tab 该用哪支墨（图标与文字同一支），由 [LiquidBottomTabs] 按**这块板实际画出来
 * 有多亮/多暗**解出来交给内容（[bottomBarInk]，ai/T25b）。
 *
 * 走 CompositionLocal 而不是让 `tabContent` 再算一次，理由与 `LocalSemanticPlate` 完全相同：
 * 墨色依赖栏体 alpha，而栏体 alpha 又在 [LiquidBottomTabs] 里被用户透明度偏好、壁纸亮度
 * 现场夹出来——第二处算的就是第二块板。形状照 [LocalLiquidBottomTabAccentTint] 那一族命名。
 *
 * **默认值是 null，含义是「底栏没说话」**：`NavItemContent` 还被旧式底栏与宽屏导航栏复用
 * （那两条走 `GlassSurface(CHROME)`，表面 alpha 口径是 0.96 不是 0.60），它们不该吃到这里
 * 解出来的墨，null 时那两处继续逐字用主题的 `onSurfaceVariant`。
 */
val LocalLiquidBottomTabInk =
    staticCompositionLocalOf<Color?> { null }

/**
 * 底栏**选中态**那一族该用的墨（图标与文字同一支），由 [LiquidBottomTabs] 按它实际坐在的
 * 那块板解出来（[bottomBarAccentInk]，ai/T32）。
 *
 * 与 [LocalLiquidBottomTabInk] 分成两根线，是因为它们坐在**两块板**上：未选中的中性墨坐在
 * 栏体板上，选中墨坐在被指示器那层 wash 罩过的板上（同一条 0.60 的栏体，两块对照物）。
 *
 * **默认 null = 「底栏没说话」**：旧式底栏与宽屏导航栏走 `GlassSurface(CHROME)`，表面口径
 * 是 0.96 不是 0.60，那两处的选中态继续逐字用主题 `primary`（与 [LocalLiquidBottomTabInk]
 * 同一条契约，见 `BottomBarInkTest.legacyNavigationPathsDoNotAskTheBottomBarForInk`）。
 */
val LocalLiquidBottomTabAccentInk =
    staticCompositionLocalOf<Color?> { null }

@Composable
fun RowScope.LiquidBottomTab(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scale = LocalLiquidBottomTabScale.current
    Column(
        modifier
            .clip(Capsule())
            // selectable 而不是 clickable：只给 Role.Tab 的话屏幕阅读器念不出
            // "已选中/未选中"，用户无从知道自己停在哪一页。
            // 反馈仍交给玻璃高光（indication = null），不叠 Material ripple。
            .selectable(
                selected = selected,
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val scale = scale()
                scaleX = scale
                scaleY = scale
            },
        verticalArrangement = Arrangement.spacedBy(2f.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}
