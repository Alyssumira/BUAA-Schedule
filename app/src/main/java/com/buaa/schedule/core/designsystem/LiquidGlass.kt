package com.buaa.schedule.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
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

/**
 * 材质 → 外阴影。
 *
 * 别把 [LiquidGlassMaterial.shadowAlpha] 直接传给 `Shadow(alpha = …)`：
 * kyant 的实现里 **alpha 是图层倍率、color 才是画刷浓度**（`ShadowNode` 记
 * `layer.alpha = shadow.alpha`，画刷用 `shadow.color`），而默认色的 alpha 本身就是 0.1。
 * 传进 alpha 等于 0.1 × shadowAlpha，浓度掉到 1/10；把材质当唯一真源接线时，
 * 这个尺度差会让课程卡的阴影凭空消失。材质的语义是**总浓度**，所以它落在 color 上。
 */
fun LiquidGlassMaterial.outerShadow(): Shadow =
    Shadow(color = Color.Black.copy(alpha = shadowAlpha))

/** 材质 → 内阴影；同 [outerShadow]，浓度走 color（[InnerShadow] 的默认色自带 0.15）。 */
fun LiquidGlassMaterial.innerShadow(): InnerShadow =
    InnerShadow(radius = innerShadowRadius, color = Color.Black.copy(alpha = innerShadowAlpha))

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
        // 降级路径：不采样背景，走全站统一的降级底板（②V-11）
        val fallbackShape = shape()
        return degradedPlate(
            shape = fallbackShape,
            tint = surfaceTint.copy(alpha = surfaceAlpha.coerceIn(0.55f, 1f)),
            // 描边色跟着**底板**的亮度走，不是固定白：白色描边压在浅色主题的
            // 浅灰底上等于没有描边，这正是"同屏三种质感"里最难看的那一种
            borderColor = contentOnLuma(surfaceTint.luminance())
                .copy(alpha = degradedPlateAlpha(material.highlightAlpha)),
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
    // cacheDecorations：高光 / 外阴影 / 内阴影各是一块离屏图层，默认（false）情况下
    // **每一个被绘制的帧**都要 layer.record 重录一遍——滚动时壁纸在动、玻璃每帧重绘，
    // 一块表面就是 3 次离屏录制，全屏几十块表面直接把帧预算花在重新描同一个边上。
    // 打开后由 kyant 自己的 materialKey 决定重录时机（size / density / fontScale /
    // layoutDirection / outline / 装饰值 / bounds），这几项任一变化照样重录，
    // 所以画面一个像素都不变，省掉的只是"内容完全相同的那次录制"。
    val renderOptions = BackdropRenderOptions(
        effectKey = { effectKey },
        cacheDecorations = true,
    )
    return drawBackdrop(
        backdrop = backdrop,
        shape = shape,
        effects = effects,
        highlight = { Highlight.Default.copy(alpha = material.highlightAlpha) },
        shadow = { material.outerShadow() },
        innerShadow = { material.innerShadow() },
        onDrawSurface = { drawRect(surfaceTint.copy(alpha = surfaceAlpha)) },
        renderOptions = renderOptions,
    )
}

/**
 * 玻璃不可用时的降级底板：不透明底 + 1dp 分界描边（审查②V-11）。
 *
 * 三条降级路径（[GlassSurface] 的面板、[liquidGlass] 的 fallback、周视图课程卡）
 * 以前各画各的——主题灰描边、白色描边、没描边，关掉玻璃后同一屏出现三种质感。
 * 结构在这里统一；**描边颜色**仍由调用方给：拿得到主题就传 `outlineVariant`，
 * 拿不到 `ColorScheme` 的地方按底板亮度取黑白两侧（见 [contentOnLuma]）。
 *
 * 不是 `@Composable`：修饰符链要在 `remember { }` 里稳定住，
 * 进组合会把几十张卡的玻璃节点一起拖进重组。
 */
fun Modifier.degradedPlate(shape: Shape, tint: Color, borderColor: Color): Modifier =
    clip(shape).background(tint).border(1.dp, borderColor, shape)

/**
 * 降级底板的描边浓度：从材质的 [LiquidGlassMaterial.highlightAlpha] 派生，
 * 夹在 0.08~0.3 —— 低于 0.08 看不见，高于 0.3 会让降级卡片比真玻璃还重。
 */
fun degradedPlateAlpha(highlightAlpha: Float): Float = (highlightAlpha * 4f).coerceIn(0.08f, 0.3f)

