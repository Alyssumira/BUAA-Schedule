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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.isRenderEffectSupported

/**
 * 统一玻璃材质组件（BUAA Flow Glass · 液态玻璃版）。
 *
 * 四种档位语义（API 与旧版完全兼容）：
 * - [GlassVariant.CHROME]  底部导航 / 顶栏：pill 材质，折射最强；
 * - [GlassVariant.PANEL]   Hero 卡 / 分组面板 / 预览：dialog 材质；
 * - [GlassVariant.COMPACT] 分段控件 / Chip：pill 材质轻量档；
 * - [GlassVariant.ALERT]   冲突 / 错误提示：dialog 材质 + 语义色 tint。
 *
 * API 31+ 且该档位/该变体确实要玻璃、并且拿到 [GlassRegistry] 配额时，
 * 通过 AGSL 渲染真液态玻璃（背景折射 + 高光 + 内外阴影）；
 * 其余情况（关闭档的 [GlassVariant.PANEL]、26-30、配额耗尽）退化为**不透明平板**。
 */
enum class GlassVariant { CHROME, PANEL, COMPACT, ALERT }

@Composable
fun GlassSurface(
    variant: GlassVariant,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    semanticTint: Color? = null,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = DesignTokens.spaceM,
    /** 预览用：显式覆盖用户透明度偏好，避免"为看效果而每帧写全局状态" */
    alphaOverride: Float? = null,
    content: @Composable () -> Unit,
) {
    val tier = GlassGovernance.effectiveTier(Personalization.glassTier)
    val userAlpha = alphaOverride ?: Personalization.cardAlpha
    val colorScheme = MaterialTheme.colorScheme
    val darkTheme = colorScheme.background.luminance() < 0.5f
    val resolvedShape = shape ?: RoundedCornerShape(DesignTokens.cornerPanel)
    val backdrop = LocalSceneBackdrop.current

    // 变体 → 液态玻璃材质（映射唯一真源在 DesignTokens.glassMaterial，测试也走它）
    val panelBlurDp = Personalization.panelBlurDp
    val material = remember(variant, darkTheme, panelBlurDp) {
        // vibrancy 会把采样到的背景提亮、增饱和：浅色档这是"通透"的来源，
        // 深色档却等于往文字底下垫一块亮斑，浅色正文的对比度直接被吃掉。
        val base = DesignTokens.glassMaterial(variant, panelBlurDp = panelBlurDp)
        if (darkTheme) base.copy(useVibrancy = false) else base
    }

    val baseTint = semanticTint ?: if (darkTheme) DarkGlassTint else LightGlassTint
    // 用户透明度偏好映射到 tint：cardAlpha 越低玻璃越透。
    // 倍率口径全站统一在 DesignTokens.cardAlphaScale（②V-12）——下限必须是 0.18 而不是 0.5，
    // 否则滑条 0.3~0.44 那一段算出来是同一个值，用户往左拖到底看不到任何变化。
    val alphaScale = DesignTokens.cardAlphaScale(userAlpha)
    val surfaceAlpha = when (variant) {
        GlassVariant.ALERT -> (if (semanticTint != null) 0.45f else material.surfaceAlpha) * alphaScale
        else -> material.surfaceAlpha * alphaScale
    }.coerceIn(
        legibilityAlphaFloor(baseTint, colorScheme.onSurfaceVariant, darkTheme),
        0.96f,
    )
    val wantsGlass = DesignTokens.surfaceUsesGlass(tier, variant)
    var acquired by remember { mutableStateOf(false) }
    DisposableEffect(wantsGlass) {
        val ok = wantsGlass && GlassRegistry.acquire()
        acquired = ok
        onDispose { if (ok) GlassRegistry.release() }
    }
    // API 31 以下没有 RenderEffect，拿不到配额也只能退化
    val glassEnabled = acquired && isRenderEffectSupported()

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
    // 退化路径画**不透明平板**，而不是"半透明 tint 不带 blur"：
    // 后者等于让壁纸以完全清晰的形态透到文字底下，文字对比度直接交给壁纸。
    // 关闭档下整屏 PANEL（设置页/导入页几十条 item）都走这里，
    // 既省掉几十个 AGSL 表面的开销，也把可读性钉回主题色上。
    val plateColor = remember(colorScheme, semanticTint) {
        semanticTint?.let { lerp(colorScheme.surfaceContainerHigh, it, 0.16f) }
            ?: colorScheme.surfaceContainerHigh
    }
    val plateModifier = remember(plateColor, resolvedShape, colorScheme) {
        // 结构与另外两条降级路径共用 degradedPlate；描边色这里能用主题的 outlineVariant，
        // 是因为面板拿得到 ColorScheme（liquidGlass / 课程卡那条链上没有它）
        Modifier.degradedPlate(resolvedShape, plateColor, colorScheme.outlineVariant)
    }

    Box(
        modifier = modifier
            .then(if (glassEnabled) glassModifier else plateModifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Box(modifier = Modifier.padding(contentPadding)) {
            content()
        }
    }
}

/**
 * 一块玻璃底板最少要多实，才能让 [text] 在它上面读得清。
 *
 * 底板要多实，取决于玻璃底下到底有多亮/多暗：玻璃采样的是**未压暗的原始壁纸**
 * （场景 scrim 刻意不进录制层，见 [SceneBackground]），主题只知道深浅、看不到壁纸。
 * 于是亮斑上的浅色文字、暗斑上的深色文字都会被透上来的壁纸吃掉对比度——
 * 这里按最不利分块亮度反推下限：场景安全时放行通透，场景危险时才压实。
 *
 * 手写玻璃表面（分段控件的选中胶囊、底栏）与 [GlassSurface] 共用这一个口径。
 *
 * 不是 `@Composable`：调用方需要在 `remember { }` 里算它。
 * 组合期内读 [SceneLuma] 照样会被记录，换壁纸仍然会触发重算。
 *
 * @see legibleTintPlate 前景色**也还没定**（课程色底板这类）时用那个，它会连文字色一起解出来
 * @see DesignTokens.glassAlphaFloor 两个入口共同的数值口径
 */
internal fun legibilityAlphaFloor(surfaceTint: Color, text: Color, darkTheme: Boolean): Float {
    val surfaceLuma = surfaceTint.luminance()
    val textLuma = text.luminance()
    return DesignTokens.glassAlphaFloor(
        surfaceLuma = surfaceLuma,
        sceneLuma = worstGlassSceneLuma(darkTheme, plateIsDark = surfaceLuma < textLuma),
        textLuma = textLuma,
    )
}

