package com.buaa.schedule.core.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
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
    /**
     * 这张卡位想表达**哪种语义色**（主题成员，如 `colorScheme.error`），
     * 不是"请把这个颜色当成最终底色"——大面积填充该用哪个成员、上面该写哪个颜色的字，
     * 由 [semanticGlassPlateOf] 一次解出来（[LocalSemanticPlate] 把配好的文字色交给内容）。
     *
     * 把这两件事拆开挑，就是 ai/T23 修的那条 1.39:1：`error` 本体当底板 + `onErrorContainer` 写字。
     */
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

    // 用户透明度偏好映射到 tint：cardAlpha 越低玻璃越透。
    // 倍率口径全站统一在 DesignTokens.cardAlphaScale（②V-12）——下限必须是 0.18 而不是 0.5，
    // 否则滑条 0.3~0.44 那一段算出来是同一个值，用户往左拖到底看不到任何变化。
    val alphaScale = DesignTokens.cardAlphaScale(userAlpha)
    // 语义卡的底板与文字**成对**解出来：semanticTint 只是"这张卡想表达哪种语义色"的意图，
    // 实际染哪支、上面写哪支，一次定（[semanticGlassPlateOf]）。
    // 夹 alpha 的参照文字色也必须用它——恒传 onSurfaceVariant 等于按一块并不存在的板算下限，
    // 那块板压不压得实、压到多实，全跟着错的配对走（ai/T23 的 0.96 实心粉底就是这么来的）。
    val rawAlpha = glassRawAlpha(variant, material, semanticTint, alphaScale)
    val semanticPlate = semanticTint?.let {
        semanticGlassPlateOf(it, rawAlpha, darkTheme, colorScheme)
    }
    val baseTint = semanticPlate?.tint ?: if (darkTheme) DarkGlassTint else LightGlassTint
    val surfaceAlpha = glassSurfaceAlpha(
        variant = variant,
        material = material,
        semanticTint = semanticTint,
        alphaScale = alphaScale,
        baseTint = baseTint,
        text = semanticPlate?.foreground ?: colorScheme.onSurfaceVariant,
        darkTheme = darkTheme,
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
    val plateColor = remember(colorScheme, semanticPlate) {
        semanticPlate?.let { lerp(colorScheme.surfaceContainerHigh, it.tint, 0.16f) }
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
            // 卡位的文字色从这里取，不再自己挑：它必须与**这一块板**成对，
            // 而不是与"卡作者以为的那块板"成对（ai/T23）。
            CompositionLocalProvider(LocalSemanticPlate provides semanticPlate) {
                content()
            }
        }
    }
}

/**
 * 玻璃底板 tint 的 alpha 天花板：再实也不许把玻璃压成一块不透明板（产品口径）。
 *
 * 各个手写表面另有更严的自有天花板（分段控件 0.82、底栏 0.60、FAB 0.55），
 * 这一档只管 [GlassSurface] 的绝对上限。
 */
internal const val SURFACE_ALPHA_CEILING = 0.96f

/**
 * [GlassSurface] 的 tint alpha：材质档位 × 用户透明度偏好，下限托到读得清、上限压住通透感。
 *
 * ## 下限要先对天花板取小，否则区间会翻过来
 *
 * 下限是 [legibilityAlphaFloor] 反解出来的，而那条反解**能饱和到 1.0**
 * （[DesignTokens.glassAlphaFloor] 的值域上界就是 1f）：底板亮度已经站在该文字色对应的
 * AA 临界点另一侧时——暗玻璃板 0.0081 底下垫着 0.08 的亮块配中灰文字、
 * DarkError 0.5684 配 0.5681 的浅色正文、浅主题下红底板 0.1125 压在 0.05 的暗斑上——
 * 解出来的需要 alpha 大于 1，含义是**这块板无论压到多实都读不清**。
 * 此时下限比 0.96 的天花板还高，`coerceIn(1.0, 0.96f)` 当场抛
 * `Cannot coerce value to an empty range`，而且是主线程 doFrame 上的 FATAL。
 *
 * ## 落到 0.96 是什么语义
 *
 * 救不清的板走满天花板：玻璃还剩 4% 透光，**仍然不保证 AA**，只是不再炸。
 * 真要救回来得改的是文字色或底板色，那是 [legibleTintPlate] 的职责（它连前景一起解，
 * 必要时压 tint），不是在这里把数字调大的理由。0.96 本身是产品口径——玻璃再实
 * 也不许变成一块不透明板，别把它抬到 1f 当作修法。
 *
 * 从 composable 里抽出来只为一个理由：本模块的 JVM 单测没有 Compose 运行时
 * （无 Robolectric、无 ui-test），留在 `@Composable` 体内测不到这条数值口径。
 * 饱和族与安全基线钉在 GlassSurfaceAlphaTest 的表里。
 *
 * @see legibilityAlphaFloor 下限的唯一来源
 */
internal fun glassSurfaceAlpha(
    variant: GlassVariant,
    material: LiquidGlassMaterial,
    semanticTint: Color?,
    alphaScale: Float,
    baseTint: Color,
    text: Color,
    darkTheme: Boolean,
): Float {
    val ceiling = SURFACE_ALPHA_CEILING
    return glassRawAlpha(variant, material, semanticTint, alphaScale).coerceIn(
        legibilityAlphaFloor(baseTint, text, darkTheme).coerceAtMost(ceiling),
        ceiling,
    )
}

/**
 * 未经可读性下限与天花板夹取的 tint alpha：材质档位 × 用户透明度偏好。
 *
 * 逐字从 [glassSurfaceAlpha] 里抽出来，数值口径一个字没改（T22 的
 * `GlassSurfaceAlphaTest` 仍按原样钉住它）。抽出来只为一个理由：语义卡的**文字色**
 * 要按"这块板实际画出来有多实"来解，而最终 alpha 又按下限反依赖文字色，
 * 于是需要一个不打折的起点当参照；ALERT 那一档的 0.45 更不能有第二份。
 */
internal fun glassRawAlpha(
    variant: GlassVariant,
    material: LiquidGlassMaterial,
    semanticTint: Color?,
    alphaScale: Float,
): Float = when (variant) {
    GlassVariant.ALERT -> (if (semanticTint != null) 0.45f else material.surfaceAlpha) * alphaScale
    else -> material.surfaceAlpha * alphaScale
}

/**
 * 一块玻璃底板最少要多实，才能让 [text] 在它上面读得清。
 *
 * 底板要多实，取决于玻璃底下到底有多亮/多暗：玻璃采样的是**未压暗的原始壁纸**
 * （场景 scrim 刻意不进录制层，见 [SceneBackground]），主题只知道深浅、看不到壁纸。
 * 于是亮斑上的浅色文字、暗斑上的深色文字都会被透上来的壁纸吃掉对比度——
 * 这里按最不利分块亮度反推下限：场景安全时放行通透，场景危险时才压实。
 *
 * 值域上界是 **1.0**（不是 0.96，也不是任何天花板）：1.0 的意思是这块板无论压到
 * 多实都读不清。调用方夹区间时要先跟自己的天花板取小再喂给 `coerceIn`，
 * 否则下限会翻到天花板上面去（[glassSurfaceAlpha] 是唯一收口点）。
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

/**
 * 一张语义玻璃卡的成对颜色：玻璃底板实际染的 [tint] + 压在上面读得清的 [foreground]。
 *
 * 两个字段必须成对用。把它们拆开、底板归底板挑一个色、文字归文字挑一个色，
 * 就是 ai/T23 修的那条 1.39:1（见 [semanticGlassPlateOf]）。
 */
@Immutable
data class SemanticPlate(
    val tint: Color,
    val foreground: Color,
)

/**
 * [semanticGlassPlateOf] 解出来的那一对，由 [GlassSurface] 交给卡片内容。
 *
 * 走 CompositionLocal 而不是让卡位再调一次配对函数，理由就是这张卡在犯的错：
 * 两处各算各的，输入一漂移（例如预览用 `alphaOverride` 覆盖了用户透明度偏好），
 * 底板与文字就又成了两件事。中性玻璃下它是 null，卡位照旧用主题的中性正文色。
 */
val LocalSemanticPlate = staticCompositionLocalOf<SemanticPlate?> { null }

/**
 * [GlassSurface] 语义卡的配对表：一支"意图色" → 成对的（底板, 文字）。
 *
 * ## 底板与文字为什么不能分开挑
 *
 * 卡位传进来的是**意图**（这张卡想说"出错了"），不是一块底色。同一支意图色，
 * 在 M3 里可能既是"中性面上的强调色"（`error`，只配当图标/边框/小标签的字色），
 * 又是"大面积填充"（`errorContainer`）——把前者铺成一块横幅、再压上后者的前景
 * `onErrorContainer`，就是 ai/T23 修的那条：深色主题 `#FFB4AB` 底 `#FFE2DE` 字，
 * 实测 1.39:1（正文要求 4.5:1），而且这块板压到多实都读不清（T22 那条饱和下限）。
 *
 * 两条解法，按"语义色自己带不带成对角色"分：
 * 1. **带**（`error` 有 `errorContainer`/`onErrorContainer`）：换成 M3 的原配，
 *    深浅两套主题同构，底板与文字都是主题成员，观感由色板作者负责。
 * 2. **不带**（`success` 这类项目自己补的槽位，`SemanticColors` 里只有色相没有配套前景）：
 *    底板保留意图色，文字按**这块板实际画出来有多亮/多暗**解 —— 交给 [legibleTintPlate]
 *    （选墨与压实同解 → 两支墨都读不出才压 tint），它是这套数值口径的唯一入口。
 *
 * 第 2 条里 [alpha] 是**未夹取**的材质档位浓度（[glassRawAlpha]）：alpha 要按文字色反解下限，
 * 文字色又要按 alpha 合成后的亮度来挑，所以这里从不夹取的那一档起步，
 * 压实这一步留给 [glassSurfaceAlpha]（同一个 [legibilityAlphaFloor] 口径，且只会朝
 * "更读得清"的方向抬，见那里的推导）。
 *
 * @see SemanticGlassPlateTest 六个卡位 × 深浅两套主题的数值闸门
 * @see legibleTintPlate 第 2 条唯一的解
 */
internal fun semanticGlassPlateOf(
    tint: Color,
    alpha: Float,
    darkTheme: Boolean,
    scheme: ColorScheme,
): SemanticPlate = when (tint) {
    scheme.error -> SemanticPlate(scheme.errorContainer, scheme.onErrorContainer)
    else -> legibleSemanticPlate(tint, alpha, darkTheme)
}

/**
 * 没有 M3 成对角色的语义色：底板不动，文字在这块板**实际亮度**上解。
 *
 * 一块玻璃底下同时压着亮斑与暗斑，而"该怕哪一头"取决于文字是浅是深——先有墨才有怕，
 * 先有场景极值才挑得准墨。所以两个极端各解一次，留**两头都读得清**的那一支：
 * 只喂一头的说法（按 tint 自身亮度判深浅）在淡染档上是错的，深色主题的
 * `success` #7BD69B 自身亮度 0.5467 看着像"亮板配深字"，可它以 0.18 叠在深色渐变的最暗档
 * 上合成出来只有 0.040，这时候深字反而只有 1.5:1（旧线性口径给这块板 0.107、深字 2.6:1，
 * 是一块真机上画不出来的板）。
 */
private fun legibleSemanticPlate(tint: Color, alpha: Float, darkTheme: Boolean): SemanticPlate {
    // true = 板比字暗、怕亮斑；false = 板比字亮、怕暗斑（与 legibilityAlphaFloor 同一判据）
    val extremes = listOf(
        worstGlassSceneLuma(darkTheme, plateIsDark = true),
        worstGlassSceneLuma(darkTheme, plateIsDark = false),
    )
    val candidates = extremes.map { legibleTintPlate(tint, alpha, it) }
    val best = candidates.maxByOrNull { plate ->
        val plateLuma = plate.tint.readableLuminance()
        val ink = plate.foreground.readableLuminance()
        extremes.minOf { contrastRatio(compositeLuma(plateLuma, it, alpha), ink) }
    } ?: candidates.first()
    return SemanticPlate(best.tint, best.foreground)
}

