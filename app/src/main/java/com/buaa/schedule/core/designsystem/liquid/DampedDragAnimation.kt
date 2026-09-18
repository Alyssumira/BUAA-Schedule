// Based on Kyant0/AndroidLiquidGlass catalog components, Apache-2.0.
// Modified for SleepDown-Schedule.
package com.buaa.schedule.core.designsystem.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import com.buaa.schedule.core.designsystem.motionSpringFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: DampedDragAnimation.() -> Unit,
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
    /**
     * 系统「关闭动画」开关，由调用方在组合边界读 `LocalReduceMotion` 传进来。
     *
     * 构造器不是 @Composable，读不到 CompositionLocal，所以这里收布尔而不是就地判。
     * 无默认值是有意的：漏传一次，底栏的拖拽与按压就又成了一条不受开关管束的动画。
     */
    val reduceMotion: Boolean,
) {

    // 五条弹簧此前是裸 spring()：底栏的拖拽回弹与按压缩放完全绕开了系统动画开关。
    // 走 motionSpringFor 而不是在这里重新判一次开关——策略只写在 Motion.kt 一份，
    // 阻尼/刚度/阈值这些调校过的性格值原样保留。
    private val valueAnimationSpec =
        motionSpringFor(reduceMotion, 1f, 1000f, visibilityThreshold)
    private val velocityAnimationSpec =
        motionSpringFor(reduceMotion, 0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec =
        motionSpringFor(reduceMotion, 1f, 1000f, 0.001f)
    private val scaleXAnimationSpec =
        motionSpringFor(reduceMotion, 0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec =
        motionSpringFor(reduceMotion, 0.7f, 250f, 0.001f)

    private val valueAnimation =
        Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation =
        Animatable(0f, 5f)
    private val pressProgressAnimation =
        Animatable(0f, 0.001f)
    private val scaleXAnimation =
        Animatable(initialScale, 0.001f)
    private val scaleYAnimation =
        Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()

    private val velocityTracker = VelocityTracker()

    val value: Float get() = valueAnimation.value
    val progress: Float get() = (value - valueRange.start) / (valueRange.endInclusive - valueRange.start)
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            onDragStart = { down ->
                onDragStarted(down.position)
                press()
            },
            onDragEnd = {
                onDragStopped()
                release()
            },
            onDragCancel = {
                onDragStopped()
                release()
            }
        ) { change, dragAmount ->
            onDrag(size, dragAmount)
        }
    }

    fun press() {
        velocityTracker.resetTracking()
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        animationScope.launch {
            awaitFrame()
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                snapshotFlow { valueAnimation.value }
                    .filter { abs(it - valueAnimation.targetValue) < threshold }
                    .first()
            }
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    fun updateValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        animationScope.launch {
            launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) { updateVelocity() } }
        }
    }

    fun animateToValue(value: Float) {
        animationScope.launch {
            mutatorMutex.mutate {
                press()
                val targetValue = value.coerceIn(valueRange)
                launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) }
                if (velocity != 0f) {
                    launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                }
                release()
            }
        }
    }

    fun animateToValueAndThen(value: Float, onFinished: () -> Unit) {
        animationScope.launch {
            mutatorMutex.mutate {
                press()
                val targetValue = value.coerceIn(valueRange)
                coroutineScope {
                    launch {
                        valueAnimation.animateTo(targetValue, valueAnimationSpec) { updateVelocity() }
                    }
                    if (velocity != 0f) {
                        launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                    }
                    launch {
                        awaitFrame()
                        val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                        if (abs(valueAnimation.value - targetValue) >= threshold) {
                            snapshotFlow { valueAnimation.value }
                                .filter { abs(it - targetValue) < threshold }
                                .first()
                        }
                        coroutineScope {
                            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
                            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
                        }
                    }
                }
                onFinished()
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(
            System.currentTimeMillis(),
            Offset(value, 0f)
        )
        val targetVelocity = velocityTracker.calculateVelocity().x / (valueRange.endInclusive - valueRange.start)
        animationScope.launch { velocityAnimation.animateTo(targetVelocity, velocityAnimationSpec) }
    }
}
