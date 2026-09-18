package com.buaa.schedule.core.designsystem.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.buaa.schedule.core.designsystem.ChromeSurfaceDark
import com.buaa.schedule.core.designsystem.ChromeSurfaceLight
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.core.designsystem.LocalReduceMotion
import com.buaa.schedule.core.designsystem.MotionTokens
import com.buaa.schedule.core.designsystem.Personalization
import com.buaa.schedule.core.designsystem.motionSpec
import com.buaa.schedule.core.designsystem.motionSpringFor
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
    // 规格本身也要 remember：motionSpring 每次重组都返回新实例，而它是下面
    // LaunchedEffect 的 key——等于"重组一次 = 按压弹簧从当前值重跑一遍"。
    // reduce-motion 的判定仍然只有一份（motionSpringFor 就是 motionSpring 的
    // 非组合入口，开关在组合边界读出来传进去），没有新增动画、也没有绕过兜底。
    val reduceMotion = LocalReduceMotion.current
    val pressSpec: AnimationSpec<Float> = remember(reduceMotion) {
        motionSpringFor<Float>(reduceMotion, dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)
    }
    LaunchedEffect(pressed, pressSpec) {
        pressScale.animateTo(
            targetValue = if (pressed) 1f + 3f / 42f else 1f,
            animationSpec = pressSpec,
        )
    }

    // 图标旋转必须与菜单开合同步：时长与缓动直接取菜单用的那一组令牌，
    // 于是"菜单在长、加号还在转"变成"加号转完的一刻菜单正好停住"（④M-02）。
    // reduce-motion 由 motionSpec 统一处理：系统要求无动画时瞬时到位。
    val iconRotation by animateFloatAsState(
        targetValue = if (expanded) 45f else 0f,
        animationSpec = motionSpec<Float>(
            durationMillis = if (expanded) MotionTokens.DURATION_MENU else MotionTokens.DURATION_MENU_CLOSE,
            easing = MotionTokens.EasingEmphasized,
        ),
        label = "fabIconRotation",
    )

    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // 表面色叠在"采样到的背景 + 折射"之上，alpha 越高越像实心色块。
    // 倍率必须与面板/底栏/菜单同源（DesignTokens.cardAlphaScale，②V-12）：
    // 此前这里下限写死 0.5f，用户把透明度拉到底时卡片透了、加号还差一半幅度。
    val alphaScale = DesignTokens.cardAlphaScale(Personalization.cardAlpha)
    val surfaceAlpha = (0.30f * alphaScale).coerceIn(0.08f, 0.55f)
    val surface = if (darkTheme) {
        ChromeSurfaceDark.copy(alpha = surfaceAlpha)
    } else {
        ChromeSurfaceLight.copy(alpha = surfaceAlpha)
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
                        shape = { FabCapsuleShape },
                        effects = {
                            vibrancy()
                            blur(4.dp.toPx())
                            lens(12.dp.toPx(), 24.dp.toPx())
                        },
                        // 高光必须给具体 alpha：Highlight.Default 的默认 alpha 是 1f，
                        // 直接用会给整颗按钮镶一圈死白的边（看起来像实心白圆片）。
                        // 参考 SleepDown 的按钮：highlight 0.08、innerShadow 6dp/0.18。
                        // 两个值都是常量，提到文件级，免得每帧绘制时各 new 一个。
                        highlight = { FabHighlight },
                        shadow = { Shadow.Default },
                        innerShadow = { FabInnerShadow },
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
                // rotate(angle) 就是 graphicsLayer { rotationZ = angle }，换成块写法是为了
                // 把 iconRotation 的读取挪到绘制期：图标转那 260/240ms 里，原来每一帧
                // 都在重组整个 LiquidFab（修饰符链、remember 键、表面色全走一遍）
                .graphicsLayer { rotationZ = iconRotation },
        )
    }
}

/**
 * FAB 的玻璃渲染选项：effect 的输入全是常量（vibrancy/blur/lens 参数固定），
 * 给出固定的 effectKey 让 kyant 长期命中缓存，避免每帧重建 RenderEffect。
 *
 * cacheDecorations 同理作用在装饰图层上：高光/外阴影/内阴影三块离屏图层
 * 只在几何或装饰值变化时重录，胶囊停在原地时每帧省下 3 次 layer.record。
 */
private val FabRenderOptions = BackdropRenderOptions(
    effectKey = { "liquid-fab" },
    cacheDecorations = true,
)

/**
 * 胶囊的装饰与几何常量：三者参数都写死，本来却放在 drawBackdrop 的回调里，
 * 每帧各 new 一个对象。`shape = { Capsule() }` 更贵——ShapeProvider 拿实例比
 * （`_shape != shape`），每帧一个新的 Capsule 等于把它缓存的 outline 丢掉重算。
 */
private val FabCapsuleShape = Capsule()
private val FabHighlight = Highlight.Default.copy(alpha = 0.10f)
private val FabInnerShadow = InnerShadow(radius = 6.dp, alpha = 0.18f)
