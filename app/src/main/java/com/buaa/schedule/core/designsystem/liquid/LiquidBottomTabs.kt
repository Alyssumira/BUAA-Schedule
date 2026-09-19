// Based on Kyant0/AndroidLiquidGlass catalog components, Apache-2.0.
// Modified for SleepDown-Schedule, then ported for BUAA-Schedule.
//
// BUAA 移植说明：Compose 1.7 的 GraphicsLayer.record 块必须以自身接收者绘制，
// 上游 MovingAccent 生产者（依赖 record 内调用外层 drawContent 的画布交换语义）
// 录制为空。因此改为：指示器直接携带当前 tab 内容（玻璃上叠内容，iOS 风格），
// 拖拽/按压物理（DampedDragAnimation）与边缘折射保持不变。
package com.buaa.schedule.core.designsystem.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.core.designsystem.ChromeSurfaceLight
import com.buaa.schedule.core.designsystem.LocalReduceMotion
import com.buaa.schedule.core.designsystem.Personalization
import com.buaa.schedule.core.designsystem.legibilityAlphaFloor
import com.buaa.schedule.core.designsystem.motionSpec
import com.buaa.schedule.core.designsystem.motionSpring
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.BackdropRenderOptions
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * 液态玻璃底部导航（Kyant catalog 的 LiquidBottomTabs 移植版）。
 *
 * 结构：
 * 1. 容器胶囊：vibrancy + blur + 折射，按下整条微放大；
 * 2. 选中指示器：lens 折射 + 按压缩放 + 速度挤压形变 + 内阴影，
 *    并直接承载当前 tab 内容（玻璃上叠内容）。
 *
 * @param tabContent 单个 tab 的内容（图标/文字），[LiquidBottomTab] 提供列布局。
 */
@Composable
fun LiquidBottomTabs(
    selectedTabIndex: () -> Int,
    onTabSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    modifier: Modifier = Modifier,
    containerHeight: Dp = 56f.dp,
    indicatorHeight: Dp = 48f.dp,
    horizontalPadding: Dp = 4f.dp,
    blurRadius: Dp = 4f.dp,
    containerAlpha: Float = 0.5f,
    lensHeight: Dp = 10f.dp,
    lensAmount: Dp = 40f.dp,
    indicatorLensHeight: Dp = 12f.dp,
    indicatorLensAmount: Dp = 17f.dp,
    officialHighlightAlpha: Float = 0.07f,
    officialShadowAlpha: Float = 0.05f,
    officialInnerShadowAlpha: Float = 0.08f,
    pressedContentScale: Float = 1.15f,
    // 参考 SleepDown 的 LiquidBottomTabs：官方参数下底栏默认**不开色散**
    // （色散只留给"选中指示器"那一片），整条底栏开色散会泛出彩虹边、显得脏
    chromaticAberrationEnabled: Boolean = false,
    isLightTheme: Boolean,
    containerColor: Color = ChromeSurfaceLight,
    tabContent: @Composable ColumnScope.(index: Int) -> Unit
) {
    val themeBlend by animateFloatAsState(
        targetValue = if (isLightTheme) 1f else 0f,
        // 220ms 的裸 tween 归入 DURATION_MEDIUM，同时接上 reduce-motion（④M-05）
        animationSpec = motionSpec<Float>(),
        label = "LiquidBottomTabsThemeBlend"
    )
    // 拖拽松手后的回弹规格必须在组合期取（motionSpring 读 LocalReduceMotion）：
    // 系统要求无动画时直接 snap 归位，不再走 300ms 的弹簧
    val settleSpec: AnimationSpec<Float> = motionSpring<Float>(
        dampingRatio = 1f,
        stiffness = 300f,
        visibilityThreshold = 0.5f,
    )
    // 底栏的两条"物理"动画（拖拽回弹 / 光斑跟手）在 DampedDragAnimation 与
    // InteractiveHighlight 里构造规格，而它们不是 @Composable，读不到开关，
    // 所以在这里把 LocalReduceMotion 读出来传下去（口径同 navEnter 的 reduceMotion）。
    // 它同时是这两个 remember 的 key：这颗开关运行期会变（改完设置回来重读、玻璃治理降档），
    // 不进 key 就等于把它冻结在首次组合那一刻——重建的代价只是指示器落回当前 tab，
    // 而那正是无动画模式下该看到的样子。
    val reduceMotion = LocalReduceMotion.current
    // 与 GlassSurface / FAB 口径一致：底栏表面 alpha 也跟随用户「卡片透明度」。
    // containerAlpha 只作为基准值，倍率口径统一在 DesignTokens.cardAlphaScale（②V-12）。
    val userAlphaScale = DesignTokens.cardAlphaScale(Personalization.cardAlpha)
    // tab 的图标/文字用的是 onSurfaceVariant，玻璃底下是**未压暗的原始壁纸**，
    // 所以这条栏要多实只能看壁纸有多亮/多暗——原来按主题写死 0.06 / 0.34，
    // 浅色主题配一张暗壁纸时 6% 的底板等于没有，近黑的 tab 文字直接糊在壁纸上。
    val scheme = MaterialTheme.colorScheme
    // 栏体只认调用方给的 containerColor —— 调用方已经按主题挑好色（Light/DarkGlassTint）。
    // 旧实现在深色档另写死 0xFF121212，把 MainActivity 传入的深色 tint 静默吞掉：
    // 同一条栏两处决定颜色，哪处都不作数（审查 V-组件层裸色）。
    val effectiveContainerAlpha = bottomBarSurfaceAlpha(
        containerAlpha = containerAlpha,
        userAlphaScale = userAlphaScale,
        containerColor = containerColor,
        text = scheme.onSurfaceVariant,
        darkTheme = !isLightTheme,
    )
    val containerSurface = containerColor.copy(alpha = effectiveContainerAlpha)

    BoxWithConstraints(
        modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - horizontalPadding.toPx() * 2f) / tabsCount
        }
        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth).fastCoerceIn(-1f, 1f)
                with(density) {
                    4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var currentIndex by remember {
            mutableIntStateOf(selectedTabIndex().coerceIn(0, tabsCount - 1))
        }
        val dampedDragAnimation = remember(animationScope, reduceMotion) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedTabIndex().toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 48f.dp / 40f.dp,
                reduceMotion = reduceMotion,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = targetValue.fastRoundToInt().coerceIn(0, tabsCount - 1)
                    // 只在真的落在别的 tab 上时才切换。inspectDragGestures 对一次普通
                    // 点击也会回调（零位移拖拽），无条件回调就会和 tab 的 onClick 各发一次
                    // navigateTopLevel —— 用户点一下，底栏替他跳两次。
                    val changed = targetIndex != currentIndex
                    currentIndex = targetIndex
                    animateToValue(targetIndex.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, settleSpec)
                    }
                    if (changed) onTabSelected(targetIndex)
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / tabWidth * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            )
        }
        LaunchedEffect(selectedTabIndex()) {
            val index = selectedTabIndex().coerceIn(0, tabsCount - 1)
            if (currentIndex != index || abs(dampedDragAnimation.targetValue - index.toFloat()) > 0.01f) {
                currentIndex = index
                dampedDragAnimation.animateToValue(index.toFloat())
            }
        }

        val interactiveHighlight = remember(animationScope, reduceMotion) {
            InteractiveHighlight(
                animationScope = animationScope,
                reduceMotion = reduceMotion,
                position = { size, offset ->
                    Offset(
                        if (isLtr) (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset,
                        size.height / 2f
                    )
                }
            )
        }

        // —— 玻璃渲染缓存 key ——
        // effectKey 为 null 时 kyant 会在**每一帧**重新求值 effects 并重建 RenderEffect
        // （见 DrawBackdropModifier.updateEffects：key 为 null 直接短路掉缓存判断）。
        // 此前这三处都没传 renderOptions，于是切页 / 拖底栏 / 按下时每帧重建 3 组
        // blur+lens（底层还会分配 effect 对象），是底栏掉帧的主因。
        // cacheDecorations 管的是另一半：高光/外阴影/内阴影各是一块离屏图层，
        // 关掉时**每个重绘帧**都 layer.record 重录一遍（容器 2 块 + 索引层 2 块 + 指示器 3 块
        // = 每帧 7 次离屏录制）。
        // 打开后由 kyant 的 materialKey（size/density/fontScale/layoutDirection/outline/
        // 装饰值/bounds）决定重录时机——按下进度会改 highlight 的 alpha，那时照样重录，
        // 所以省掉的只有"录出来的内容和上一帧完全相同"的那几次。
        // 1) 容器胶囊的 effect 只由这几个参数决定，与按下进度无关 → 固定 key，长期命中。
        val containerEffectKey = remember(blurRadius, lensHeight, lensAmount, chromaticAberrationEnabled) {
            "${blurRadius.value}|${lensHeight.value}|${lensAmount.value}|$chromaticAberrationEnabled"
        }
        val containerRenderOptions = remember(containerEffectKey) {
            BackdropRenderOptions(effectKey = { containerEffectKey }, cacheDecorations = true)
        }
        // 2) 索引层 / 指示器的折射强度乘了按下进度，必须把进度写进 key 才能在按下时正确变化；
        //    空闲时进度恒为 0，key 不变 → 不重建（这正是这里能省下开销的关键）。
        val pressRenderOptions = remember {
            BackdropRenderOptions(
                effectKey = { dampedDragAnimation.pressProgress },
                cacheDecorations = true,
            )
        }

        // 1) 容器胶囊 + 各 tab
        Row(
            Modifier
                .graphicsLayer {
                    translationX = panelOffset
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { TabsCapsuleShape },
                    effects = {
                        vibrancy()
                        blur(blurRadius.toPx())
                        lens(
                            lensHeight.toPx(),
                            lensAmount.toPx(),
                            chromaticAberration = chromaticAberrationEnabled
                        )
                    },
                    highlight = { Highlight.Default },
                    shadow = { Shadow.Default },
                    renderOptions = containerRenderOptions,
                    layerBlock = {
                        val progress = dampedDragAnimation.pressProgress
                        val scale = lerp(1f, 1f + 12f.dp.toPx() / size.width, progress)
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = {
                        drawRect(containerSurface)
                    }
                )
                .then(interactiveHighlight.modifier)
                .height(containerHeight)
                .fillMaxWidth()
                .padding(horizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(tabsCount) { index ->
                LiquidBottomTab(
                    selected = selectedTabIndex() == index,
                    onClick = { onTabSelected(index) }
                ) {
                    tabContent(index)
                }
            }
        }

        // 2) MovingAccent 隐藏层：把 tab 内容录进 tabsBackdrop
        //    （Compose 1.11 的 record 画布交换语义，指示器可折射 tab 图标）
        val tabsBackdrop = rememberLayerBackdrop()
        CompositionLocalProvider(
            LocalLiquidBottomTabScale provides {
                lerp(1f, pressedContentScale, dampedDragAnimation.pressProgress)
            },
            LocalLiquidBottomTabAccentTint provides true,
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer {
                        translationX = panelOffset
                    }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { TabsCapsuleShape },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            vibrancy()
                            blur(blurRadius.toPx())
                            lens(
                                lensHeight.toPx() * progress,
                                lensAmount.toPx() * progress,
                                chromaticAberration = chromaticAberrationEnabled
                            )
                        },
                        highlight = {
                            Highlight.Default.copy(alpha = dampedDragAnimation.pressProgress * officialHighlightAlpha)
                        },
                        shadow = { Shadow.Default },
                        renderOptions = pressRenderOptions,
                        onDrawSurface = {
                            drawRect(containerSurface)
                        }
                    )
                    .then(interactiveHighlight.modifier)
                    .height(indicatorHeight)
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 该层叠在最上层且可命中，点击必须真实生效（同上游）；
                // 语义整层清空，"已选中"由可见层那一份播报，不重复念两遍。
                repeat(tabsCount) { index ->
                    LiquidBottomTab(
                        selected = selectedTabIndex() == index,
                        onClick = { onTabSelected(index) },
                    ) {
                        tabContent(index)
                    }
                }
            }
        }

        // 3) 选中指示器：lens 折射 combined(场景背景, tab 内容)
        val indicatorBackdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop)
        Box(
            Modifier
                .padding(horizontal = horizontalPadding)
                .graphicsLayer {
                    translationX =
                        if (isLtr) dampedDragAnimation.value * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 1f) * tabWidth + panelOffset
                }
                .then(interactiveHighlight.gestureModifier)
                .then(dampedDragAnimation.modifier)
                .drawBackdrop(
                    backdrop = indicatorBackdrop,
                    shape = { TabsCapsuleShape },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        lens(
                            indicatorLensHeight.toPx() * progress,
                            indicatorLensAmount.toPx() * progress,
                            chromaticAberration = chromaticAberrationEnabled
                        )
                    },
                    highlight = {
                        Highlight.Default.copy(alpha = dampedDragAnimation.pressProgress * officialHighlightAlpha)
                    },
                    shadow = {
                        Shadow(alpha = dampedDragAnimation.pressProgress * officialShadowAlpha)
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(radius = 8f.dp * progress, alpha = progress * officialInnerShadowAlpha)
                    },
                    renderOptions = pressRenderOptions,
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(
                            Color.White.copy(alpha = 0.1f),
                            alpha = (1f - themeBlend) * (1f - progress)
                        )
                        drawRect(
                            Color.Black.copy(alpha = 0.1f),
                            alpha = themeBlend * (1f - progress)
                        )
                        drawRect(Color.Black.copy(alpha = 0.03f * progress))
                    }
                )
                .height(indicatorHeight)
                .width(with(density) { tabWidth.toDp() })
        )
    }
}

/**
 * 底栏三处玻璃共用的胶囊形状实例。
 *
 * 写在 `shape = { Capsule() }` 里时它每帧被 ShapeProvider 调一次，除了白分配一个
 * Shape，更要紧的是 ShapeProvider 按实例比 shape（`_shape != shape`），每帧一个新的
 * 就把它缓存的 outline 判成过期、每帧重算一遍轮廓路径。
 */
private val TabsCapsuleShape = Capsule()

/**
 * 底栏栏体的表面 alpha 天花板。
 *
 * 比卡片的 [com.buaa.schedule.core.designsystem.SURFACE_ALPHA_CEILING]（0.96）低一档：
 * 底栏是悬浮 overlay，课表网格在它底下整屏滚动，栏体一实心就变成"一条永久盖住课表的
 * 横带"（基准 0.20 的来由见 DesignTokens.CHROME_SURFACE_ALPHA）。0.60 是"还读得出是
 * 玻璃"的界线，也就是那条注释里列的"分段控件 0.82、底栏 0.60、FAB 0.55"中的底栏档。
 */
internal const val BOTTOM_BAR_SURFACE_ALPHA_CEILING = 0.60f

/**
 * 底栏栏体表面 alpha：原始值（containerAlpha × 用户透明度倍率）夹在"读得清的下限"
 * 与"这条栏的天花板"之间。
 *
 * 从 composable 里抽出来的理由与 [com.buaa.schedule.core.designsystem.glassSurfaceAlpha]
 * 相同：本模块的 JVM 单测没有 Compose 运行时，留在 `@Composable` 体内测不到。
 *
 * ## 夹区间的顺序与 [glassSurfaceAlpha] 同构：下限先跟天花板取小，再喂 coerceIn
 *
 * 旧写法是 `.coerceAtMost(0.60).coerceAtLeast(floor)`。[legibilityAlphaFloor] 的值域
 * 上界是 1.0（那块板无论压到多实都读不清时会饱和），于是下限一旦越过 0.60，
 * 两步夹取等于"天花板不作数"：深色档 × 亮壁纸块实测 floor 可到 0.920，
 * 0.60 这条上限在深色场景从来没真正生效过，产出的 alpha 两处口径都不认
 * （约定本身写在 [legibilityAlphaFloor] 的文档里；T22 修过的 `coerceIn` 空区间
 * 崩溃是同一族账）。换成先取小再夹，越顶的下限收敛为"走满天花板"——
 * 读不清就如实透着一档，而不是无声地把栏压成实心条。
 * 代价与 0.60 是否够用的量化账在 BottomBarSurfaceAlphaTest 与 T25 报告里。
 */
internal fun bottomBarSurfaceAlpha(
    containerAlpha: Float,
    userAlphaScale: Float,
    containerColor: Color,
    text: Color,
    darkTheme: Boolean,
): Float {
    val ceiling = BOTTOM_BAR_SURFACE_ALPHA_CEILING
    return (containerAlpha * userAlphaScale).coerceIn(
        legibilityAlphaFloor(containerColor, text, darkTheme).coerceAtMost(ceiling),
        ceiling,
    )
}
