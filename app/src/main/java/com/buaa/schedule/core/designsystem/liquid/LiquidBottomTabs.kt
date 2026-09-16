// Based on Kyant0/AndroidLiquidGlass catalog components, Apache-2.0.
// Modified for SleepDown-Schedule, then ported for BUAA-Schedule.
//
// BUAA 移植说明：Compose 1.7 的 GraphicsLayer.record 块必须以自身接收者绘制，
// 上游 MovingAccent 生产者（依赖 record 内调用外层 drawContent 的画布交换语义）
// 录制为空。因此改为：指示器直接携带当前 tab 内容（玻璃上叠内容，iOS 风格），
// 拖拽/按压物理（DampedDragAnimation）与边缘折射保持不变。
package com.buaa.schedule.core.designsystem.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import com.buaa.schedule.core.designsystem.Personalization
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
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
    containerColor: Color = Color(0xFFFAFAFA),
    tabContent: @Composable ColumnScope.(index: Int) -> Unit
) {
    val themeBlend by animateFloatAsState(
        targetValue = if (isLightTheme) 1f else 0f,
        animationSpec = tween(220),
        label = "LiquidBottomTabsThemeBlend"
    )
    // 与 GlassSurface / FAB 口径一致：底栏表面 alpha 也跟随用户「卡片透明度」。
    // containerAlpha 只作为基准值，用户可以在设置里继续往更透/更实的方向调。
    val effectiveContainerAlpha = (
        containerAlpha * (Personalization.cardAlpha / 0.88f).coerceIn(0.5f, 1.25f)
        ).coerceIn(0.06f, 0.60f)
    val lightContainerSurface = containerColor.copy(alpha = effectiveContainerAlpha)
    val darkContainerSurface = Color(0xFF121212).copy(alpha = effectiveContainerAlpha)

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
        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedTabIndex().toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 48f.dp / 40f.dp,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = targetValue.fastRoundToInt().coerceIn(0, tabsCount - 1)
                    currentIndex = targetIndex
                    animateToValue(targetIndex.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(
                            0f,
                            spring(1f, 300f, 0.5f)
                        )
                    }
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
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentIndex }
                .drop(1)
                .collectLatest { index ->
                    dampedDragAnimation.animateToValue(index.toFloat())
                    onTabSelected(index)
                }
        }

        val interactiveHighlight = remember(animationScope) {
            InteractiveHighlight(
                animationScope = animationScope,
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
        // 1) 容器胶囊的 effect 只由这几个参数决定，与按下进度无关 → 固定 key，长期命中。
        val containerEffectKey = remember(blurRadius, lensHeight, lensAmount, chromaticAberrationEnabled) {
            "${blurRadius.value}|${lensHeight.value}|${lensAmount.value}|$chromaticAberrationEnabled"
        }
        val containerRenderOptions = remember(containerEffectKey) {
            BackdropRenderOptions(effectKey = { containerEffectKey })
        }
        // 2) 索引层 / 指示器的折射强度乘了按下进度，必须把进度写进 key 才能在按下时正确变化；
        //    空闲时进度恒为 0，key 不变 → 不重建（这正是这里能省下开销的关键）。
        val pressRenderOptions = remember {
            BackdropRenderOptions(effectKey = { dampedDragAnimation.pressProgress })
        }

        // 1) 容器胶囊 + 各 tab
        Row(
            Modifier
                .graphicsLayer {
                    translationX = panelOffset
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
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
                        drawRect(darkContainerSurface, alpha = 1f - themeBlend)
                        drawRect(lightContainerSurface, alpha = themeBlend)
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
                        shape = { Capsule() },
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
                            drawRect(darkContainerSurface, alpha = 1f - themeBlend)
                            drawRect(lightContainerSurface, alpha = themeBlend)
                        }
                    )
                    .then(interactiveHighlight.modifier)
                    .height(indicatorHeight)
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 该层叠在最上层且可命中，点击必须真实生效（同上游）
                repeat(tabsCount) { index ->
                    LiquidBottomTab(
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
                    shape = { Capsule() },
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
