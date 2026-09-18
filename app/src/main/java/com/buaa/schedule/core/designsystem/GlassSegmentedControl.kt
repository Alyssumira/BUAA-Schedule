package com.buaa.schedule.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.isRenderEffectSupported
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 玻璃分段控件（胶囊二选一/多选一）。
 * 用于页面内视图切换（如 周课表/今日）。
 *
 * 选中段也是一**真玻璃**（折射背景 + primary 色 tint），而不是一块贴死的
 * 不透明 primary：整条控件本来就浮在场景背景上，实心色块会让它看起来
 * 像"贴了张纸"，与其余玻璃语言割裂。
 *
 * 选中胶囊是**一个覆盖层在滑**，不是"每段各自带一块底板、切换时换一块"：
 * 后者在切换那一瞬间有两块玻璃同时在场（旧的淡出、新的淡入），中间帧看得见
 * 两条边。覆盖层走的是一条连续位移，"我在哪一格"才读得出来。
 * 落点来自每段实测矩形（各段按内容宽度排布、互不相等，见下方 weight 警告），
 * 所以将来加第三段、或段里换成图标，这里都不需要改。
 *
 * ⚠️ 分段**按内容宽度**排布（`defaultMinSize`，不是 `weight`）：
 * 用 `weight` 时控件会把父级给它的可用宽度全部吃掉，
 * 与它同处一行、带 `weight(1f)` 的兄弟节点就会被挤成 0 宽（顶部"第N周"就是这么消失的）。
 */
@Composable
fun GlassSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    minSegmentWidth: Dp = 64.dp,
) {
    val haptics = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    val darkTheme = scheme.background.luminance() < 0.5f
    val backdrop = LocalSceneBackdrop.current
    val segmentShape = remember { RoundedCornerShape(DesignTokens.cornerPill) }
    // 不读档位：分段控件属于"关闭档也保留小面积玻璃"的那一类（②V-14 后材质本身也不随档位变）
    val material = remember(darkTheme) {
        val base = DesignTokens.glassMaterial(GlassVariant.COMPACT)
        if (darkTheme) base.copy(useVibrancy = false) else base
    }
    // 选中胶囊要一块自己的配额：它折射的是场景层，不是父玻璃。
    // 但先问"画得出玻璃吗"再占格子（口径同 GlassSurface 的 `wantsGlass && acquire()`）：
    // GlassRegistry 是全局稀缺资源，前提不成立时这块玻璃永远不会画出来，
    // 占着配额就等于把真玻璃名额让给一个用不上的表面。
    val canRenderGlass = backdrop != null && isRenderEffectSupported()
    var acquired by remember { mutableStateOf(false) }
    DisposableEffect(canRenderGlass) {
        val ok = canRenderGlass && GlassRegistry.acquire()
        acquired = ok
        onDispose { if (ok) GlassRegistry.release() }
    }
    val glassEnabled = acquired
    // primary 做底板时，onPrimary 文字能不能读取决于透上来多少壁纸——按同一口径兜底
    val wallpaperStats = SceneLuma.wallpaper
    val segmentAlpha = remember(scheme.primary, scheme.onPrimary, darkTheme, material, wallpaperStats) {
        legibilityAlphaFloor(scheme.primary, scheme.onPrimary, darkTheme)
            .coerceAtLeast(material.surfaceAlpha)
            .coerceAtMost(0.82f)
    }
    val pillModifier = remember(backdrop, material, scheme.primary, segmentAlpha, glassEnabled, segmentShape) {
        Modifier.liquidGlass(
            backdrop = backdrop,
            shape = { segmentShape },
            material = material,
            surfaceTint = scheme.primary,
            surfaceAlpha = segmentAlpha,
            enabled = glassEnabled,
            effectKey = material,
        )
    }

    // 每段的实测矩形，父 Row 的像素坐标——覆盖层共用同一套坐标，不做单位换算。
    val rects = remember { mutableStateListOf<IntRect>() }
    var measured by remember { mutableIntStateOf(0) }
    val target = rects.getOrNull(selectedIndex)

    // 覆盖层画在哪。四根 Float 各自补间，而不是 animateRectAsState：
    // 后者只能读到已收敛的值，首帧会先在错误位置画一帧（"闪一下再滑过去"）。
    val left = remember { Animatable(0f) }
    val top = remember { Animatable(0f) }
    val width = remember { Animatable(0f) }
    val height = remember { Animatable(0f) }
    var seated by remember { mutableStateOf(false) }
    // 规格在组合期取好：motionSpec 是 @Composable，带不进 LaunchedEffect
    val slideSpec: FiniteAnimationSpec<Float> = motionSpec(MotionTokens.DURATION_SNAP)
    LaunchedEffect(target, measured) {
        val to = target ?: return@LaunchedEffect
        val bounds = floatArrayOf(
            to.left.toFloat(),
            to.top.toFloat(),
            to.width.toFloat(),
            to.height.toFloat(),
        )
        if (to.width <= 0 || to.height <= 0) return@LaunchedEffect
        if (!seated) {
            // 首帧直接落位：进场时胶囊该已经在正确位置，而不是从左上角飞进去
            left.snapTo(bounds[0])
            top.snapTo(bounds[1])
            width.snapTo(bounds[2])
            height.snapTo(bounds[3])
            seated = true
            return@LaunchedEffect
        }
        launch { left.animateTo(bounds[0], slideSpec) }
        launch { top.animateTo(bounds[1], slideSpec) }
        launch { width.animateTo(bounds[2], slideSpec) }
        launch { height.animateTo(bounds[3], slideSpec) }
    }

    GlassSurface(
        variant = GlassVariant.COMPACT,
        modifier = modifier,
        shape = segmentShape,
        contentPadding = 4.dp,
    ) {
        Box {
            Row {
                options.forEachIndexed { index, label ->
                    Segment(
                        label = label,
                        selected = index == selectedIndex,
                        minSegmentWidth = minSegmentWidth,
                        onMeasured = { rect ->
                            while (rects.size <= index) rects.add(rect)
                            if (rects[index] != rect) {
                                rects[index] = rect
                                measured++
                            }
                        },
                        onClick = {
                            // 只有真正切换时才反馈，重复点当前项不该震动
                            if (index != selectedIndex) haptics.performTick()
                            onSelect(index)
                        },
                    )
                }
            }
            if (seated) {
                Box(
                    modifier = Modifier
                        // 落位从来没错过：IntOffset 要的就是像素，left/top 存的也是父坐标像素，
                        // 中间不存在可错的换算。出过错的只有旁边那一路尺寸
                        .offset { IntOffset(left.value.roundToInt(), top.value.roundToInt()) }
                        // 尺寸改在测量期读（见 pillSizeOf）：这里不再要组合期的
                        // pillWidth/pillHeight，那两行就是"胶囊滑动 140ms = 整条控件
                        // 重组 9 帧"的来源
                        .pillSizeOf(widthOf = { width.value }, heightOf = { height.value })
                        .then(pillModifier),
                )
            }
        }
    }
}

/**
 * 一段。
 *
 * 单独成为一个 composable 是为了**收窄失效范围**：选中段的墨色是一条 140ms 的
 * 颜色补间，而 `.value` 只能读在组合期——读在控件主体里，一次切换的动画就把
 * 整条控件（每一段、每一个 Text、覆盖层、所有 remember 的键）重组九遍。
 * 读在段内，动画期间重组的只有正在换色的那一段。
 */
@Composable
private fun Segment(
    label: String,
    selected: Boolean,
    minSegmentWidth: Dp,
    onMeasured: (IntRect) -> Unit,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val ink by animateColorAsState(
        targetValue = if (selected) scheme.onPrimary else scheme.onSurface,
        animationSpec = motionSpec(MotionTokens.DURATION_SNAP),
        label = "segmentInk",
    )
    Box(
        modifier = Modifier
            // 高度下限要在 clickable **之前**：写后面只会撑大内容区，点不到的还是点不到
            .defaultMinSize(
                minWidth = minSegmentWidth,
                minHeight = DesignTokens.minTouchTarget,
            )
            .padding(horizontal = 2.dp)
            .onGloballyPositioned { node ->
                // boundsInParent() 给的是**像素**矩形（不是 dp）：这里只做 Float→Int 的取整，
                // 不乘密度——覆盖层拿到的就是这个数本身
                val r = node.boundsInParent()
                onMeasured(
                    IntRect(
                        r.left.roundToInt(),
                        r.top.roundToInt(),
                        r.right.roundToInt(),
                        r.bottom.roundToInt(),
                    )
                )
            }
            .selectable(
                selected = selected,
                // 与底栏、顶栏「周课表/今日」同一套语义：分段切换回答的是
                // "我在哪一格"，而此前这里只有 clickable，念不出"已选中"
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = ink,
            maxLines = 1,
        )
    }
}

/**
 * 覆盖层的尺寸：把对两根补间 Animatable 的读取从组合期挪到**测量期**。
 *
 * 单位口径是 **px 进、px 出，全程不经过 Dp**（见 [pillSizePx]）：
 * 两端读的都是同一个 `boundsInParent()`，那本来就是父 Row 的像素坐标。
 *
 * 约束算法与 `Modifier.width(pixels)` / `height(pixels)` 逐步对应
 * （含"想要的尺寸超出父级上限就夹住"那一步），只是夹的是 px 与 px——
 * 同量纲，这一步才真的成立。
 */
private fun Modifier.pillSizeOf(widthOf: () -> Float, heightOf: () -> Float): Modifier =
    layout { measurable, constraints ->
        val target = pillSizePx(widthOf(), heightOf(), constraints)
        if (target.width <= 0 || target.height <= 0) {
            layout(0, 0) {}
        } else {
            val placeable = measurable.measure(Constraints.fixed(target.width, target.height))
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
    }

/**
 * 覆盖层要占的整数像素尺寸：[widthPx]/[heightPx] 是补间出来的父坐标像素，
 * 夹进 [constraints] 给的上限，再抬到它的下限。
 * 任一维夹出非正数即返回 0×0（这一帧不画）。
 *
 * 拆成独立纯函数只为了**能被单测钉住**：`:app` 的 JVM 测试里没有 Robolectric，
 * 也没有 `ui-test-junit4`（`testImplementation` 只有 junit），跑不起真实的
 * measure pass——那么"分段实测 W px，胶囊就该量出 W px"这条口径就得有个
 * 不用上设备也能断言的落点。
 */
internal fun pillSizePx(widthPx: Float, heightPx: Float, constraints: Constraints): IntSize {
    val requestedWidth = widthPx.roundToInt()
    val requestedHeight = heightPx.roundToInt()
    val maxWidth =
        if (constraints.hasBoundedWidth) requestedWidth.coerceAtMost(constraints.maxWidth) else requestedWidth
    val maxHeight =
        if (constraints.hasBoundedHeight) requestedHeight.coerceAtMost(constraints.maxHeight) else requestedHeight
    return if (maxWidth <= 0 || maxHeight <= 0) {
        IntSize.Zero
    } else {
        IntSize(
            maxWidth.coerceAtLeast(constraints.minWidth),
            maxHeight.coerceAtLeast(constraints.minHeight),
        )
    }
}
