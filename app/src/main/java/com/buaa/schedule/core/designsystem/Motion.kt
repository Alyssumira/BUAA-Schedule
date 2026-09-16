package com.buaa.schedule.core.designsystem

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
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

    /** 标准缓动：起步快、收尾稳，用于位移与淡入淡出 */
    val EasingStandard = CubicBezierEasing(0.20f, 0.0f, 0.0f, 1.0f)

    /** 强调缓动：起步更慢、到位更利落，用于需要“弹出感”的场合 */
    val EasingEmphasized = CubicBezierEasing(0.16f, 0.78f, 0.18f, 1.0f)
}

/**
 * 系统「移除动画」/ 开发者选项「过渡动画缩放」是否要求关闭非必要动画。
 *
 * Compose 不会自动读取该项——此前 WeekView 里「Compose 动画自动遵循系统减少动态效果
 * 设置」的注释是错误假设，项目内并没有任何相关实现。
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    var reduce by remember { mutableStateOf(readReduceMotion(context)) }
    // 用户在设置里改动后回到本应用时重读；动画缩放不会在应用前台期间变化
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                reduce = readReduceMotion(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return reduce
}

private fun readReduceMotion(context: Context): Boolean {
    // 个别定制 ROM 会移除该全局项，读不到时按“动画开启”处理
    val scale = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            1f,
        )
    }.getOrDefault(1f)
    return scale <= 0f
}

/** 轻反馈：翻页、切换、滑动这类“位置变化”用与系统文本拖柄一致的档位。 */
fun HapticFeedback.performTick() {
    performHapticFeedback(HapticFeedbackType.TextHandleMove)
}

/** 重反馈：长按进入拖拽、执行破坏性操作。 */
fun HapticFeedback.performThud() {
    performHapticFeedback(HapticFeedbackType.LongPress)
}
