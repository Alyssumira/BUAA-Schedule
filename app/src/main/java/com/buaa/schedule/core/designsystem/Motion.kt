package com.buaa.schedule.core.designsystem

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * 动效令牌。
 *
 * 项目此前没有集中的 duration / easing 定义，各处散落魔法数
 * （liquid 组件 420/240ms、liquidGlass 各处自定），新增动画无从对齐。
 * 这里统一三级时长与两条标准缓动，供页面转场与后续组件复用。
 */
object MotionTokens {
    /** 轻微状态切换：按压反馈、开关 */
    const val DURATION_SHORT = 180

    /** 常规页面进出场 */
    const val DURATION_MEDIUM = 260

    /** 强调型进出场：弹窗、大面板 */
    const val DURATION_LONG = 380

    /**
     * 弹出菜单**与其 FAB 的同源时长**（展开 / 收起）。
     *
     * 此前菜单展开 260、FAB 图标旋转 380：同一个动作两段时长，
     * 收尾那 120ms 里菜单已经停住、加号还在转（④M-02）。
     * 260/240 是 U-13 调校过的值（小控件展开不超过 300ms，SleepDown 原调校 420 偏"等待"），
     * 这里把它命名成令牌，而不是让两处各自抄一遍数字。
     */
    const val DURATION_MENU = 260
    const val DURATION_MENU_CLOSE = 240

    /**
     * Fade through（一级 tab 互切）的两段时长：旧页先退净，新页再淡入。
     *
     * 两段相加与 [DURATION_MEDIUM] 同档，只是不再有横向位移——首页 / 导入 / 设置
     * 之间没有空间关系，横滑会把它们读成"排在一条线上"（④§7.2）。
     */
    const val DURATION_FADE_THROUGH_EXIT = 90
    const val DURATION_FADE_THROUGH_ENTER = 180

    /** 标准缓动：起步快、收尾稳，用于位移与淡入淡出 */
    val EasingStandard = CubicBezierEasing(0.20f, 0.0f, 0.0f, 1.0f)

    /** 强调缓动：起步更慢、到位更利落，用于需要“弹出感”的场合 */
    val EasingEmphasized = CubicBezierEasing(0.16f, 0.78f, 0.18f, 1.0f)
}

/**
 * 系统是否要求关闭非必要动画，或者本机已被治理判定为"跑不动玻璃"。
 *
 * Compose 不会自动读取该项——此前 WeekView 里「Compose 动画自动遵循系统减少动态效果
 * 设置」的注释是错误假设，项目内并没有任何相关实现。
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    var reduce by remember { mutableStateOf(readSystemAnimationSwitches(context)) }
    // 用户在设置里改动后回到本应用时重读；动画缩放不会在应用前台期间变化
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                reduce = readSystemAnimationSwitches(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // 治理降档是运行期可变的（掉帧时压低上限），所以放在组合期读，
    // 不等 ON_RESUME；它跟着最近一次重组走，而不是瞬时——与玻璃本身的降级时机一致。
    return reduce || governanceForcedGlassOff()
}

/**
 * 动画开关要看**三颗**，不是一颗。
 *
 * Android 把"动画"拆成三个独立的全局缩放：
 * - `ANIMATOR_DURATION_SCALE`：属性动画，Compose 的 `animate*AsState` 走的正是这一颗
 *   （框架自己会乘上去，所以我们读到 0 时必须显式 snap，否则两边口径不一致）；
 * - `TRANSITION_ANIMATION_SCALE`：窗口转场；
 * - `WINDOW_ANIMATION_SCALE`：窗口开合。
 *
 * 只读 TRANSITION 等于把"关闭所有动画"这个开关拨到了 Compose 不看的那颗上——
 * 用户关了动画，我们的 Crossfade / 玻璃位移照跑不误（④M-08）。
 */
private fun readSystemAnimationSwitches(context: Context): Boolean =
    // 个别定制 ROM 会移除这些全局项，读不到的一项按 1（= 动画开启）处理
    animationScales(context).any { it <= 0f }

/**
 * [GlassGovernance] 判定本机带不动玻璃时（低内存 / 少核 / 持续掉帧）一并关掉非必要动画：
 * 会触发降档的都是渲染受限的机器，玻璃省下来的开销不该再花在位移上。
 *
 * 用户在设置页**主动**关玻璃不算——那是审美选择，不是能力问题。
 */
private fun governanceForcedGlassOff(): Boolean =
    Personalization.glassTier != DesignTokens.GLASS_TIER_OFF &&
        GlassGovernance.effectiveTier(Personalization.glassTier) == DesignTokens.GLASS_TIER_OFF

private fun animationScales(context: Context): List<Float> {
    val resolver = context.contentResolver
    return listOf(
        Settings.Global.ANIMATOR_DURATION_SCALE,
        Settings.Global.TRANSITION_ANIMATION_SCALE,
        Settings.Global.WINDOW_ANIMATION_SCALE,
    ).map { key ->
        runCatching { Settings.Global.getFloat(resolver, key, 1f) }.getOrDefault(1f)
    }
}

/**
 * 状态变化的动画规格：系统要求减少动态效果时**瞬时到位**，否则 tween。
 *
 * 泛型覆盖被动画化的量（Float 位移、Dp 圆角、IntSize 展开高度），
 * 因此 `animate*AsState` 与 `fadeIn`/`expandVertically` 都取得到同一份口径——
 * 出现第二个"某某Spec"就是这条规则失守的开始。
 *
 * 全站每一条动画都从这里取规格，"尊重 reduce-motion"才是规则而不是 6 处补丁
 * （④M-01：Crossfade、日程切换、冲突条、设置页展开、课程卡让位、底栏胶囊此前全部漏判）。
 */
@Composable
fun <T> motionSpec(
    durationMillis: Int = MotionTokens.DURATION_MEDIUM,
    easing: Easing = MotionTokens.EasingStandard,
): FiniteAnimationSpec<T> =
    if (LocalReduceMotion.current) snap() else tween(durationMillis, easing = easing)

/** [motionSpec] 的弹簧版本：调用方给的是弹簧参数（阻尼/刚度），落点仍然是"要么弹、要么瞬到" */
@Composable
fun <T> motionSpring(
    dampingRatio: Float = 1f,
    stiffness: Float = Spring.StiffnessMediumLow,
    visibilityThreshold: T? = null,
): FiniteAnimationSpec<T> =
    if (LocalReduceMotion.current) snap() else spring(dampingRatio, stiffness, visibilityThreshold)

/** 轻反馈：翻页、切换、滑动这类“位置变化”用与系统文本拖柄一致的档位。 */
fun HapticFeedback.performTick() {
    performHapticFeedback(HapticFeedbackType.TextHandleMove)
}

/** 重反馈：长按进入拖拽、执行破坏性操作。 */
fun HapticFeedback.performThud() {
    performHapticFeedback(HapticFeedbackType.LongPress)
}
