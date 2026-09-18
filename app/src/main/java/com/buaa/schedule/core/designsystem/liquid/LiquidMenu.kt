package com.buaa.schedule.core.designsystem.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.core.designsystem.LiquidGlassMaterial
import com.buaa.schedule.core.designsystem.MotionTokens
import com.buaa.schedule.core.designsystem.Personalization
import com.buaa.schedule.core.designsystem.contentOnLuma
import com.buaa.schedule.core.designsystem.degradedPlate
import com.buaa.schedule.core.designsystem.degradedPlateAlpha
import com.buaa.schedule.core.designsystem.innerShadow
import com.buaa.schedule.core.designsystem.motionSpec
import com.buaa.schedule.core.designsystem.outerShadow
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropRenderOptions
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.RoundedRectangle

/** 弹出菜单项（仿 SleepDown AddMenuAction） */
data class LiquidMenuItem(
    val icon: ImageVector,
    val label: String,
    val action: () -> Unit,
)

// ── SleepDown HomeAddMenu 视格（HomeAnchoredMorphOverlay.kt 常量）──────────
private val MenuTargetCorner = 30.dp      // 外壳圆角
private val SelectionCorner = 19.dp       // 选中胶囊圆角（与外壳同心：30-19=11dp inset）
private val ContentWidth = 172.dp         // 内容宽度
private val ItemHeight = 48.dp            // 单项高度 = 触控目标（整行可点，不低于 48dp）
private val ContentTopPadding = 6.dp      // 顶部留白
private val ShellPadding = 11.dp          // 同心内缩（外壳与内容之间）

/** 弹窗背景模糊半径（进 [LiquidGlassMaterial.popup]，不再是 drawBackdrop 里的裸常量） */
private val MenuBlur = 10.dp

/**
 * 菜单底板色。没有场景背景时它按**不透明度 1** 直接画出来（[degradedPlate]），
 * 有背景时只按 [surfaceAlpha] 叠一层——所以两种质感共用同一个基色，
 * 不会出现"关掉玻璃换了个颜色"。
 */
private fun solidMenuPlate(darkTheme: Boolean): Color =
    if (darkTheme) Color(0xFF1A1C22) else Color(0xFFFAFBFF)

/** 菜单外壳宽度：调用方要靠它把菜单夹进可视区，别自己再抄一份常量 */
val LiquidMenuWidth: Dp = ContentWidth + ShellPadding * 2

/** 菜单在 [count] 项时的外壳高度（同上，供调用方定位与翻转使用） */
fun liquidMenuHeight(count: Int): Dp =
    ItemHeight * count + ContentTopPadding + ShellPadding * 2

// 弹出动画：位置与尺寸各自独立的 cubic-bezier（SleepDown 02:38 调校曲线）
// 位置曲线与 FAB 图标旋转共用 MotionTokens.EasingEmphasized，不再各留一份拷贝
private val OpenSizeEasing = CubicBezierEasing(0.20f, 0.48f, 0.24f, 1.0f)
private val CloseEasing = CubicBezierEasing(0.28f, 0.06f, 0.20f, 1.0f)

/** 选中胶囊的圆角：参数是常量，原来每行每次重组都现 new 一个形状对象 */
private val MenuSelectionShape = RoundedRectangle(SelectionCorner)

/** 旧的组合期卸载阈值（进度低于它整块不出现），现在搬到绘制期当透明判据用 */
private const val MountedThreshold = 0.01f

/** 展开进度 → 内容浓度：后半段淡入（SleepDown contentStart/End 分段语义） */
private fun menuContentAlpha(progress: Float): Float =
    ((progress - 0.25f) / 0.5f).coerceIn(0f, 1f)

/**
 * 液态玻璃弹出菜单（SleepDown HomeAddMenu 样式）。
 *
 * - 贴在 FAB 旁：调用方负责对齐（右缘对齐 FAB、纵向紧邻）；
 * - 外壳 30dp G2 圆角玻璃，内容 172dp 宽、48dp/项、19dp 同心胶囊点击区；
 * - 弹出动画：从 FAB 位置以右下为锚点放大进入（位置/尺寸双曲线 + 内容后半段淡入），
 *   关闭用更短的独立曲线（SleepDown 的设计：关闭不是打开的倒放）；
 * - 液态玻璃：vibrancy + blur + 折射 + 定向高光 + 内外阴影。
 *
 * 直接组合在调用方层级中（不用 Popup），保证玻璃能采样场景背景。
 *
 * @param menuOrigin 展开动画的锚点。默认右下（从 FAB 那个角长出来）；
 *   贴在手指按压点上的菜单要传按压点那一角，否则菜单会朝反方向长开。
 */
@Composable
fun LiquidMenu(
    items: List<LiquidMenuItem>,
    visible: Boolean,
    onDismiss: () -> Unit,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    menuOrigin: TransformOrigin = TransformOrigin(1f, 1f),
) {
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // 材质取自 LiquidGlassMaterial.popup()（②V-10：这两档以前是"假真源"，
    // 菜单自己抄了一份已经漂移的参数——highlight 抄成 0.08、表面抄成 0.26，
    // 改令牌不会作用到菜单）。表面浓度仍走用户的「卡片透明度」，
    // 但上限压在 0.5：菜单要看得见底下的内容，这是有意的偏离，不是漏抄。
    val material = remember { LiquidGlassMaterial.popup(MenuBlur) }
    // 同 GlassSurface：表面 alpha 跟随用户「卡片透明度」，四类表面共用一个倍率口径（②V-12）
    val alphaScale = DesignTokens.cardAlphaScale(Personalization.cardAlpha)
    val surfaceAlpha = (material.surfaceAlpha * alphaScale).coerceIn(0.08f, 0.5f)
    val surface = solidMenuPlate(darkTheme).copy(alpha = surfaceAlpha)
    val baseText = MaterialTheme.colorScheme.onSurface

    // 展开进度：0 = 收拢在锚点角上，1 = 完全展开
    // 初值恒为 0：调用方也可以在菜单已经可见时才把它组合进来（课程卡长按菜单就是这么用的），
    // 那样下面的 animateTo 会补上展开动画，而不是凭空出现一个已经全开的菜单。
    val expansion = remember { Animatable(0f) }
    // 卸载时机。原来这一判据是在组合期读 expansion.value（!visible && value < 0.01），
    // 于是开合那 260/240ms 里**每一帧**都把整棵菜单子树重组一遍：
    // Column、每行的 Box/Icon/Text、每行的 clip 形状全走一次。
    // 改成由动画收尾写一个布尔值——它一轮动画只变一次；进度本身留给 graphicsLayer 读。
    var shown by remember { mutableStateOf(visible) }
    // 时长/缓动/是否瞬到，全部交给 motionSpec 与 MotionTokens：
    // FAB 的图标旋转读的是同一组值（④M-02 的"两段动画各走各的"）
    val openSpec = motionSpec<Float>(MotionTokens.DURATION_MENU, MotionTokens.EasingEmphasized)
    val closeSpec = motionSpec<Float>(MotionTokens.DURATION_MENU_CLOSE, CloseEasing)
    // key 只留 visible：两个 spec 每次重组都是新实例，拿它们当 key 等于每次重组
    // 都把展开动画从头跑一遍
    LaunchedEffect(visible) {
        if (visible) {
            shown = true
            expansion.animateTo(1f, openSpec)
        } else {
            expansion.animateTo(0f, closeSpec)
            shown = false
        }
    }
    // 「不可见但可点」的残留：评估过，判定不修，理由钉在这里。
    // - 是哪一段：关闭是 240ms 的 tween（CloseEasing 收尾是平的），progress 掉到
    //   [MountedThreshold] 以下≈208ms 时 alpha 已经为 0，而 animateTo 要到 240ms 才返回、
    //   shown 才翻 false、节点才离开组合——中间约 32ms（≈2 帧）就是这个残留。
    // - 期间点得到什么：只有锚点角上那一块菜单行——尺寸曲线此刻已经缩到 0.12（约 23x32dp），
    //   alpha 为 0。关闭要么由行点击发起（那一次手势已消费完，动作是导航走人），
    //   要么由点外面发起（手指此刻在菜单footprint 之外）；要点着残留就得在 32ms 内起一次
    //   新的 down 并且正好落进那一小块。
    // - 为什么不修：改之前读组合期判据，`return` 之后节点也要一次重组 + 一次布局才脱落，
    //   残留本来就存在、只差约一帧——52c3e04 挪的是尾部两帧，不是一类新问题。
    //   能区分这段窗口的现成布尔只有 `visible`，拿它门控命中等于把 240ms 关合动画整段
    //   变成穿点（下面就是 FAB / 课程格），那是每回关菜单都能复现的误触，比两帧的隐形误点贵得多；
    //   `shown` 当命中门是恒等式（它正是"节点还挂着"本身）。再收紧就得加状态或改动画阈值。
    if (!(visible || shown)) return

    val menuShape = remember { RoundedRectangle(MenuTargetCorner) }
    // 装饰值只由 remember 过的材质决定，但 drawBackdrop 的几个回调每帧都会被节点
    // 各调一次：提到组合期求一次，省下每帧 3 个数据类 + 2 次 Color.copy 的分配。
    val menuHighlight = Highlight.Default.copy(alpha = material.highlightAlpha)
    val menuShadow = material.outerShadow()
    val menuInnerShadow = material.innerShadow()

    Box(
        modifier = modifier
            .graphicsLayer {
                val progress = expansion.value
                // 内容淡入在展开后半段（SleepDown contentStart/End 分段语义）
                val contentAlpha = menuContentAlpha(progress)
                // 旧的卸载判据搬到这里：那一帧起整层画成透明，和原来直接 return 同一个画面
                val hidden = !visible && progress < MountedThreshold
                alpha = if (hidden) 0f else contentAlpha.coerceAtLeast(0.04f)
                // 尺寸曲线略滞后（挤压生长感），最小 0.12 避免完全消失
                val sizeProgress = OpenSizeEasing.transform(progress).coerceIn(0.12f, 1f)
                scaleX = sizeProgress
                scaleY = sizeProgress
                transformOrigin = menuOrigin
            }
            .then(
                if (backdrop != null) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { menuShape },
                        effects = {
                            vibrancy()
                            blur(material.blur.toPx())
                            lens(material.lensHeight.toPx(), material.lensAmount.toPx())
                        },
                        highlight = { menuHighlight },
                        shadow = { menuShadow },
                        innerShadow = { menuInnerShadow },
                        onDrawSurface = { drawRect(surface) },
                        // effect 输入全是常量（材质 remember 过、开合动画走 graphicsLayer），
                        // 固定 effectKey 即可长期缓存
                        renderOptions = MenuRenderOptions,
                    )
                } else {
                    // 没有场景背景（低档位 / 治理强制关玻璃）时，表面色本来只由
                    // drawBackdrop 的 onDrawSurface 画——少了它就只剩浮空的文字。
                    // 这里必须**实心**：没有背景采样就没有折射，半透明底板等于把文字直接压在壁纸上。
                    Modifier.degradedPlate(
                        shape = menuShape,
                        tint = solidMenuPlate(darkTheme),
                        borderColor = contentOnLuma(solidMenuPlate(darkTheme).luminance())
                            .copy(alpha = degradedPlateAlpha(material.highlightAlpha)),
                    )
                }
            )
            .padding(ShellPadding)
    ) {
        Column(modifier = Modifier.padding(top = ContentTopPadding)) {
            items.forEach { item ->
                LiquidMenuRow(
                    item = item,
                    baseText = baseText,
                    // 传求值函数而不是算好的 Float：值在每行自己的 graphicsLayer 里读，
                    // 动画期间重跑的是绘制 lambda，不是组合
                    contentAlpha = { menuContentAlpha(expansion.value) },
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun LiquidMenuRow(
    item: LiquidMenuItem,
    baseText: Color,
    contentAlpha: () -> Float,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(ContentWidth)
            .height(ItemHeight)
            .graphicsLayer { alpha = contentAlpha() }
            .clip(MenuSelectionShape)
            // 整行只有文字，不给 role 的话读屏只会念标签，听不出"这是一项可点的菜单"
            .clickable(role = Role.Button) {
                onDismiss()
                item.action()
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = baseText,
                modifier = Modifier.size(DesignTokens.iconMedium),
            )
            Text(
                text = item.label,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = baseText,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
            )
        }
    }
}

/**
 * 菜单玻璃渲染选项：effect 输入为常量，固定 effectKey 避免每帧重建 RenderEffect。
 *
 * cacheDecorations 让高光/内外阴影三块离屏图层只在几何或装饰值变化时重录
 * （kyant 按 size/outline/highlight/… 组 key）——菜单常驻期间不再每帧重描同一圈边。
 */
private val MenuRenderOptions = BackdropRenderOptions(
    effectKey = { "liquid-menu" },
    cacheDecorations = true,
)
