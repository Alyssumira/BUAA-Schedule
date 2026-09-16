package com.buaa.schedule.core.designsystem.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.buaa.schedule.core.designsystem.LocalReduceMotion
import com.buaa.schedule.core.designsystem.MotionTokens
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
import com.kyant.shapes.Capsule

/**
 * 液态玻璃胶囊按钮（主页悬浮 FAB）。
 *
 * 仿 SleepDown HomeActionCapsule 的观感：胶囊玻璃（vibrancy + blur + 折射 +
 * 定向高光 + 内外阴影），按下按 3/42 比例微放大（液态按压手感），图标随
 * 菜单开合旋转。玻璃不可用（低版本/关闭）时降级为主题色实心胶囊。
 */
@Composable
fun LiquidFab(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    iconSize: Dp = 26.dp,
    expanded: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale = remember { Animatable(1f) }
    LaunchedEffect(pressed) {
        pressScale.animateTo(
            targetValue = if (pressed) 1f + 3f / 42f else 1f,
            animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        )
    }

    // 图标旋转必须与菜单开合同步：此前是 0f/45f 瞬间跳变，
    // 与 LiquidMenu 420ms 的展开动画割裂，会看到“菜单在长、加号瞬间变叉”
    val reduceMotion = LocalReduceMotion.current
    val iconRotation by animateFloatAsState(
        targetValue = if (expanded) 45f else 0f,
        animationSpec = tween(
            durationMillis = if (reduceMotion) {
                0
            } else if (expanded) {
                MotionTokens.DURATION_LONG
            } else {
                MotionTokens.DURATION_MEDIUM
            },
            easing = MotionTokens.EasingEmphasized,
        ),
        label = "fabIconRotation",
    )

    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // 表面色叠在"采样到的背景 + 折射"之上，alpha 越高越像实心色块。
    // 关键：这里必须跟随用户的「卡片透明度」（Personalization.cardAlpha），
    // 与 GlassSurface 用同一套映射 —— 否则用户把透明度调低时，卡片透了、
    // 加号/菜单/底栏却还是不透（反馈里"液态玻璃不会透明"就是这么来的）。
    val alphaScale = (Personalization.cardAlpha / 0.88f).coerceIn(0.5f, 1.25f)
    val surfaceAlpha = (0.30f * alphaScale).coerceIn(0.08f, 0.55f)
    val surface = if (darkTheme) {
        Color(0xFF121212).copy(alpha = surfaceAlpha)
    } else {
        Color(0xFFFAFAFA).copy(alpha = surfaceAlpha)
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale.value
                scaleY = pressScale.value
            }
            .then(
                if (backdrop != null) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            vibrancy()
                            blur(4.dp.toPx())
                            lens(12.dp.toPx(), 24.dp.toPx())
                        },
                        // 高光必须给具体 alpha：Highlight.Default 的默认 alpha 是 1f，
                        // 直接用会给整颗按钮镶一圈死白的边（看起来像实心白圆片）。
                        // 参考 SleepDown 的按钮：highlight 0.08、innerShadow 6dp/0.18。
                        highlight = { Highlight.Default.copy(alpha = 0.10f) },
                        shadow = { Shadow.Default },
                        innerShadow = { InnerShadow(radius = 6.dp, alpha = 0.18f) },
                        onDrawSurface = { drawRect(surface) },
                        // effectKey 必须非 null：为 null 时 kyant 会在**每一帧**重建
                        // blur/lens RenderEffect（按下动画期间尤其明显）。这里 effect 的输入
                        // 全是常量，按下反馈走 graphicsLayer，因此可以长期命中缓存。
                        renderOptions = FabRenderOptions,
                    )
                } else {
                    Modifier
                        .clip(Capsule())
                        .background(MaterialTheme.colorScheme.primary)
                }
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .size(size),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (backdrop != null) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onPrimary
            },
            modifier = Modifier
                .size(iconSize)
                .rotate(iconRotation),
        )
    }
}

/**
 * FAB 的玻璃渲染选项：effect 的输入全是常量（vibrancy/blur/lens 参数固定），
 * 给出固定的 effectKey 让 kyant 长期命中缓存，避免每帧重建 RenderEffect。
 */
private val FabRenderOptions = BackdropRenderOptions(effectKey = { "liquid-fab" })
