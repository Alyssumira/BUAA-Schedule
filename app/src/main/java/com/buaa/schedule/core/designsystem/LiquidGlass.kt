package com.buaa.schedule.core.designsystem

import androidx.compose.foundation.border
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.staticCompositionLocalOf
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.BackdropRenderOptions
import com.kyant.backdrop.backdrops.SharedBlurBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow

/**
 * 液态玻璃材质令牌（移植自 SleepDown GlassMaterialSpec 的官方参数表）。
 *
 * 一档材质 = blur + 折射(lens) + 表面 tint + 定向高光 + 外阴影 + 内阴影。
 * API 33+ 完整渲染（AGSL 折射）；31-32 只有 blur+tint；26-30 / 玻璃关闭走不透明降级。
 */
@Immutable
data class LiquidGlassMaterial(
    val blur: Dp,
    val lensHeight: Dp,
    val lensAmount: Dp,
    val surfaceAlpha: Float,
    val highlightAlpha: Float,
    val shadowAlpha: Float,
    val innerShadowAlpha: Float,
    /**
     * 内阴影半径。**必须是个小值（3–6dp）**。
     *
     * 参考 SleepDown：顶栏 6dp（选中）/ 3dp（未选中）、按钮 6dp、课程卡 5dp。
     * 我们此前一律用 24dp —— 一个 24dp 的内阴影盖在 40~56dp 的胶囊/卡片上，
     * 等于给整块玻璃蒙了一层柔光灰雾，边缘的"玻璃感"全被抹掉
     * （反馈里"玻璃好像不会透明"的观感来源之一就是这个）。
     */
    val innerShadowRadius: Dp = 6.dp,
    val chromaticAberration: Boolean = false,
    val depthEffect: Boolean = true,
    val useVibrancy: Boolean = true,
) {
    companion object {
        /** 胶囊 / 分段控件 / 底部导航等小控件 */
        fun pill(intensity: Float = 1f) = LiquidGlassMaterial(
            blur = (2.5f * intensity.coerceIn(0.4f, 1.8f)).dp,
            lensHeight = (12f * intensity.coerceIn(0.4f, 1.8f)).dp,
            lensAmount = (24f * intensity.coerceIn(0.4f, 1.8f)).dp,
            surfaceAlpha = 0.18f,
            highlightAlpha = 0.055f,
            shadowAlpha = 0.14f,
            innerShadowAlpha = 0.09f,
            innerShadowRadius = 4.dp,
        )

        /** 对话框 / 大面板 */
        fun dialog(intensity: Float = 1f) = LiquidGlassMaterial(
            blur = (4f * intensity.coerceIn(0.4f, 1.8f)).dp,
            lensHeight = (16f * intensity.coerceIn(0.4f, 1.8f)).dp,
            lensAmount = (32f * intensity.coerceIn(0.4f, 1.8f)).dp,
            surfaceAlpha = 0.34f,
            highlightAlpha = 0.06f,
            shadowAlpha = 0.18f,
            innerShadowAlpha = 0.11f,
            innerShadowRadius = 6.dp,
        )

        /** 周视图课程卡 */
        fun courseCard(blur: Float = 4f) = LiquidGlassMaterial(
            blur = blur.coerceIn(0f, 12f).dp,
            lensHeight = 10.dp,
            lensAmount = 20.dp,
            surfaceAlpha = 0.52f,
            highlightAlpha = 0.045f,
            shadowAlpha = 0.14f,
            innerShadowAlpha = 0.10f,
            innerShadowRadius = 5.dp,
            // SleepDown 的课程卡同样关掉 depthEffect：多卡同屏时它既贵又容易糊
            depthEffect = false,
        )

        /** 弹出菜单 */
        fun popup(blur: Dp) = LiquidGlassMaterial(
            blur = blur,
            lensHeight = 12.dp,
            lensAmount = 24.dp,
            surfaceAlpha = 0.34f,
            highlightAlpha = 0.06f,
            shadowAlpha = 0.16f,
            innerShadowAlpha = 0.10f,
            innerShadowRadius = 6.dp,
        )
    }
}

/** 周视图课程卡共享的模糊背景前缀（一次 0.48x 降采样烘焙 blur+vibrancy） */
val LocalSharedCourseBackdrop = staticCompositionLocalOf<SharedBlurBackdrop?> { null }

/** 玻璃表面 tint 的中性基色（浅色/深色主题） */
val LightGlassTint = Color(0xFFF2F4F8)
val DarkGlassTint = Color(0xFF14161C)

/**
 * 真液态玻璃表面：背景采样 + vibrancy + blur + 折射 + 定向高光 + 内外阴影 + tint。
 *
 * [backdrop] 为空、玻璃关闭或 API < 31 时降级为“tint + 1dp 高光描边”的轻量玻璃。
 * 供 [GlassSurface] 与 Liquid 组件统一使用；调用方应自行 remember 各参数以稳定回调身份。
 *
 * [effectKey] 必须能完整描述 [effects] 的全部输入（通常是 [material] 本身）。
 * 它是玻璃 effect 缓存的唯一依据：`DrawBackdropNode.updateEffects` 只在 key 变化时才重新
 * 求值 blur/lens；一旦为 null，缓存永久失效，每次重组都会重跑整套 effect 求值
 * （课程卡每分钟被 nowTick 唤醒一次，几十张卡同时重算会明显掉帧）。
 */
fun Modifier.liquidGlass(
    backdrop: Backdrop?,
    shape: () -> Shape,
    material: LiquidGlassMaterial,
    surfaceTint: Color,
    surfaceAlpha: Float,
    enabled: Boolean = true,
    effectKey: Any? = null,
): Modifier {
    val glassReady = enabled && backdrop != null && isRenderEffectSupported()
    if (!glassReady || backdrop == null) {
        // 降级路径：不采样背景，仅 tint + 高光描边（API 26-30 / 玻璃关闭）
        val fallbackShape = shape
        return this
            .clip(fallbackShape())
            .drawBehind {
                drawRect(surfaceTint.copy(alpha = surfaceAlpha.coerceIn(0.55f, 1f)))
            }
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = (material.highlightAlpha * 4f).coerceIn(0.08f, 0.3f)),
                shape = fallbackShape(),
            )
    }
    val effects: BackdropEffectScope.() -> Unit = {
        if (material.useVibrancy) vibrancy()
        if (material.blur > 0.dp) blur(material.blur.toPx())
        if (material.lensHeight > 0.dp && material.lensAmount > 0.dp) {
            lens(
                material.lensHeight.toPx(),
                material.lensAmount.toPx(),
                depthEffect = material.depthEffect,
                chromaticAberration = material.chromaticAberration,
            )
        }
    }
    val renderOptions = BackdropRenderOptions(effectKey = { effectKey })
    return drawBackdrop(
        backdrop = backdrop,
        shape = shape,
        effects = effects,
        highlight = { Highlight.Default.copy(alpha = material.highlightAlpha) },
        shadow = { Shadow(alpha = material.shadowAlpha) },
        innerShadow = { InnerShadow(radius = material.innerShadowRadius, alpha = material.innerShadowAlpha) },
        onDrawSurface = { drawRect(surfaceTint.copy(alpha = surfaceAlpha)) },
        renderOptions = renderOptions,
    )
}

