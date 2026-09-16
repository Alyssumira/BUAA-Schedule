package com.buaa.schedule.core.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 统一玻璃材质组件（BUAA Flow Glass · 液态玻璃版）。
 *
 * 四种档位语义（API 与旧版完全兼容）：
 * - [GlassVariant.CHROME]  底部导航 / 顶栏：pill 材质，折射最强；
 * - [GlassVariant.PANEL]   Hero 卡 / 分组面板 / 预览：dialog 材质；
 * - [GlassVariant.COMPACT] 分段控件 / Chip：pill 材质轻量档；
 * - [GlassVariant.ALERT]   冲突 / 错误提示：dialog 材质 + 语义色 tint。
 *
 * API 31+ 且玻璃开启时通过 AGSL 渲染真液态玻璃（背景折射 + 高光 + 内外阴影）；
 * 26-30 或玻璃关闭时降级为“tint + 高光描边”。
 */
enum class GlassVariant { CHROME, PANEL, COMPACT, ALERT }

@Composable
fun GlassSurface(
    variant: GlassVariant,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    semanticTint: Color? = null,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = 12.dp,
    /** 预览用：显式覆盖用户透明度偏好，避免"为看效果而每帧写全局状态" */
    alphaOverride: Float? = null,
    content: @Composable () -> Unit,
) {
    val tier = GlassGovernance.effectiveTier(Personalization.glassTier)
    val userAlpha = alphaOverride ?: Personalization.cardAlpha
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val resolvedShape = shape ?: RoundedCornerShape(DesignTokens.cornerPanel)
    val backdrop = LocalSceneBackdrop.current

    // 档位 → 液态玻璃材质（增强档整体强度 ×1.3，更通透）
    // 映射统一走 DesignTokens.glassMaterial，避免与测试各用一套表
    val material = remember(variant, tier) { DesignTokens.glassMaterial(variant, tier) }

    val baseTint = semanticTint ?: if (darkTheme) DarkGlassTint else LightGlassTint
    // 用户透明度偏好映射到 tint：cardAlpha 越低玻璃越透
    val alphaScale = (userAlpha / 0.88f).coerceIn(0.5f, 1.25f)
    val surfaceAlpha = when (variant) {
        GlassVariant.ALERT -> (if (semanticTint != null) 0.45f else material.surfaceAlpha) * alphaScale
        else -> material.surfaceAlpha * alphaScale
    }.coerceIn(0.10f, 0.96f)
    val wantsGlass = tier >= DesignTokens.GLASS_TIER_STANDARD
    var glassEnabled by remember { mutableStateOf(false) }
    DisposableEffect(wantsGlass) {
        val acquired = wantsGlass && GlassRegistry.acquire()
        glassEnabled = acquired
        onDispose { if (acquired) GlassRegistry.release() }
    }

    // 全部输入 remember 化：无关重组不再触发玻璃节点链重建（元素相等即跳过 update）
    val shapeBlock = remember(resolvedShape) { { resolvedShape } }
    val glassModifier = remember(
        backdrop, shapeBlock, material, baseTint, surfaceAlpha, glassEnabled,
    ) {
        Modifier.liquidGlass(
            backdrop = backdrop,
            shape = shapeBlock,
            material = material,
            surfaceTint = baseTint,
            surfaceAlpha = surfaceAlpha,
            enabled = glassEnabled,
            // effect 输入只有材质本身（tint / alpha 只影响 onDrawSurface，不是 effect）。
            // 拖动透明度滑条时 surfaceAlpha 会变、Modifier 会重建（需要重绘），
            // 但 effectKey 不变 → blur/lens 不再重算。
            effectKey = material,
        )
    }

    Box(
        modifier = modifier
            .then(glassModifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Box(modifier = Modifier.padding(contentPadding)) {
            content()
        }
    }
}
