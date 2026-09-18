package com.buaa.schedule.core.designsystem

import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 分段控件滑动胶囊的尺寸换算（P：胶囊大 2.625 倍的回归闸门）。
 *
 * 口径一句话：**分段实测宽度 W px，胶囊量出来就该还是 W px**。
 * 两端读的都是同一个 `boundsInParent()`，那是父 Row 的**像素**坐标；
 * 而这里曾经是 `widthOf().roundToInt().dp.roundToPx()` —— 把整数像素当 dp 值，
 * 再乘一次密度换回像素，于是在 420dpi（density 2.625）的机器上胶囊大 2.625 倍。
 *
 * 为什么测的是这个纯函数而不是真实测量：`:app` 的 JVM 测试里既没有 Robolectric
 * 也没有 `ui-test-junit4`（`testImplementation` 只有 junit），`Modifier.layout` 的
 * measure pass 起不来。而这条口径的全部风险都在"数值对不对"，数值现在就是
 * [pillSizePx] 的返回值，所以钉住它等于钉住那条口径。
 */
class GlassSegmentedControlPillSizeTest {

    /** 覆盖层拿到的约束：没给上限（Row 里那个空 Box 的常见情形） */
    private val unbounded = Constraints()

    @Test
    fun measuredPixelsPassThroughUnchanged() {
        // 一段实测 368x127 px（420dpi 上约 140x48dp 的触控格）——
        // 换算修掉之后，胶囊拿到的还是这两个数本身，一个像素都不该长
        assertEquals(IntSize(368, 127), pillSizePx(368f, 127f, unbounded))
    }

    @Test
    fun outputDoesNotDependOnDeviceDensity() {
        // 同一段在一台机器上量出多少 px，胶囊就该占多少 px：
        // px→px 的路上根本没有密度可乘，所以换 dpi 不会换出尺寸
        for (density in listOf(1f, 2.625f, 3.5f)) {
            val px = with(Density(density, 1f)) { 140.dp.roundToPx() }
            assertEquals(
                "density $density：胶囊宽度应等于实测像素 $px",
                IntSize(px, px),
                pillSizePx(px.toFloat(), px.toFloat(), unbounded),
            )
        }
    }

    @Test
    fun oldFormulaScaledByTheDensity() {
        // 把老公式留在测试里当反面判据：整数像素过一遍「当 Dp 再 roundToPx()」，
        // 结果就是乘上了密度——420dpi 上 368 px 会被画成约 966 px
        val density = 2.625f
        val requestedPx = 368
        val buggyPx = with(Density(density, 1f)) { requestedPx.dp.roundToPx() }
        assertEquals(density, buggyPx.toFloat() / requestedPx, 0.01f)
        // 修好后同一条输入不再放大
        assertEquals(
            requestedPx,
            pillSizePx(requestedPx.toFloat(), requestedPx.toFloat(), unbounded).width,
        )
    }

    @Test
    fun clampsToParentBoundsInSameUnit() {
        // 夹约束这一段本来就是 px 与 px 比；改之前是「px 当 dp 放大后再跟 px 比」，
        // 上限几乎永远夹不住，超出的部分把同排的兄弟节点挤出屏幕
        val bounded = Constraints(maxWidth = 200, maxHeight = 60)
        assertEquals(IntSize(200, 60), pillSizePx(368f, 127f, bounded))
        // 没超上限就原样通过
        assertEquals(IntSize(120, 48), pillSizePx(120f, 48f, bounded))
    }

    @Test
    fun raisesToParentMinButCollapsesOnNonPositive() {
        val withMin = Constraints(minWidth = 90, minHeight = 90, maxWidth = 400, maxHeight = 400)
        assertEquals(IntSize(90, 127), pillSizePx(50f, 127f, withMin))
        // 首帧补间还没值（0 px）→ 0x0，覆盖层这一帧不画（等价于原来的整块缺席）
        assertEquals(IntSize.Zero, pillSizePx(0f, 127f, unbounded))
        assertEquals(IntSize.Zero, pillSizePx(-1f, 127f, unbounded))
    }
}
