package com.buaa.schedule.core.designsystem.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.buaa.schedule.core.designsystem.Personalization
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropRenderOptions
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.RoundedRectangle

/** 弹出菜单项（仿 SleepDown AddMenuAction） */
data class LiquidMenuItem(
    val icon: ImageVector,
    val label: String,
    val action: () -> Unit,
)

// ── SleepDown HomeAddMenu 视格（HomeAnchoredMorphOverlay.kt 常量）──────────
private val MenuTargetCorner = 30.dp      // 外壳圆角
private val SelectionCorner = 19.dp       // 选中胶囊圆角（与外壳同心：30-19=11dp inset）
private val ContentWidth = 172.dp         // 内容宽度
private val ItemHeight = 48.dp            // 单项高度 = 触控目标（整行可点，不低于 48dp）
private val ContentTopPadding = 6.dp      // 顶部留白
private val ShellPadding = 11.dp          // 同心内缩（外壳与内容之间）

// 弹出动画：位置与尺寸各自独立的 cubic-bezier（SleepDown 02:38 调校曲线）
private val OpenPositionEasing = CubicBezierEasing(0.16f, 0.78f, 0.18f, 1.0f)
private val OpenSizeEasing = CubicBezierEasing(0.20f, 0.48f, 0.24f, 1.0f)
private val CloseEasing = CubicBezierEasing(0.28f, 0.06f, 0.20f, 1.0f)
private const val OpenDuration = 420
private const val CloseDuration = 240

/**
 * 液态玻璃弹出菜单（SleepDown HomeAddMenu 样式）。
 *
 * - 贴在 FAB 旁：调用方负责对齐（右缘对齐 FAB、纵向紧邻）；
 * - 外壳 30dp G2 圆角玻璃，内容 172dp 宽、48dp/项、19dp 同心胶囊点击区；
 * - 弹出动画：从 FAB 位置以右下为锚点放大进入（位置/尺寸双曲线 + 内容后半段淡入），
 *   关闭用更短的独立曲线（SleepDown 的设计：关闭不是打开的倒放）；
 * - 液态玻璃：vibrancy + blur + 折射 + 定向高光 + 内外阴影。
 *
 * 直接组合在调用方层级中（不用 Popup），保证玻璃能采样场景背景。
 */
@Composable
fun LiquidMenu(
    items: List<LiquidMenuItem>,
    visible: Boolean,
    onDismiss: () -> Unit,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
) {
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // 同 LiquidFab：表面 alpha 跟随用户「卡片透明度」，与 GlassSurface 口径一致
    val alphaScale = (Personalization.cardAlpha / 0.88f).coerceIn(0.5f, 1.25f)
    val surfaceAlpha = (0.26f * alphaScale).coerceIn(0.08f, 0.50f)
    val surface = if (darkTheme) {
        Color(0xFF1A1C22).copy(alpha = surfaceAlpha)
    } else {
        Color(0xFFFAFBFF).copy(alpha = surfaceAlpha)
    }
    val baseText = MaterialTheme.colorScheme.onSurface

    // 展开进度：0 = 收拢在 FAB 位置，1 = 完全展开
    val expansion = remember { Animatable(if (visible) 1f else 0f) }
    LaunchedEffect(visible) {
        if (visible) {
            expansion.animateTo(1f, tween(OpenDuration, easing = OpenPositionEasing))
        } else {
            expansion.animateTo(0f, tween(CloseDuration, easing = CloseEasing))
        }
    }
    if (!visible && expansion.value < 0.01f) return

    // 内容淡入在展开后半段（SleepDown contentStart/End 分段语义）
    val contentAlpha = ((expansion.value - 0.25f) / 0.5f).coerceIn(0f, 1f)
    // 尺寸曲线略滞后（挤压生长感），最小 0.12 避免完全消失
    val sizeProgress = OpenSizeEasing.transform(expansion.value).coerceIn(0.12f, 1f)
    val menuShape = remember { RoundedRectangle(MenuTargetCorner) }

    Box(
        modifier = modifier
            .graphicsLayer {
                alpha = contentAlpha.coerceAtLeast(0.04f)
                scaleX = sizeProgress
                scaleY = sizeProgress
                transformOrigin = TransformOrigin(1f, 1f)
            }
            .then(
                if (backdrop != null) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { menuShape },
                        effects = {
                            vibrancy()
                            blur(10.dp.toPx())
                            lens(16.dp.toPx(), 28.dp.toPx())
                        },
                        // 对齐 SleepDown 的 popup 材质：highlight 0.06、innerShadow 6dp/0.10
                        highlight = { Highlight.Default.copy(alpha = 0.08f) },
                        shadow = { Shadow.Default },
                        innerShadow = { InnerShadow(radius = 6.dp, alpha = 0.10f) },
                        onDrawSurface = { drawRect(surface) },
                        // effect 输入全是常量（开合动画走 graphicsLayer），固定 effectKey 即可长期缓存
                        renderOptions = MenuRenderOptions,
                    )
                } else {
                    Modifier
                }
            )
            .padding(ShellPadding)
    ) {
        Column(modifier = Modifier.padding(top = ContentTopPadding)) {
            items.forEach { item ->
                LiquidMenuRow(
                    item = item,
                    baseText = baseText,
                    contentAlpha = contentAlpha,
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun LiquidMenuRow(
    item: LiquidMenuItem,
    baseText: Color,
    contentAlpha: Float,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(ContentWidth)
            .height(ItemHeight)
            .graphicsLayer { alpha = contentAlpha }
            .clip(RoundedRectangle(SelectionCorner))
            .clickable {
                onDismiss()
                item.action()
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = baseText,
                modifier = Modifier.size(21.dp),
            )
            Text(
                text = item.label,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = baseText,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
            )
        }
    }
}

/** 菜单玻璃渲染选项：effect 输入为常量，固定 effectKey 避免每帧重建 RenderEffect */
private val MenuRenderOptions = BackdropRenderOptions(effectKey = { "liquid-menu" })
