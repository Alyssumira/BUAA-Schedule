package com.buaa.schedule.core.designsystem

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 课程调色板的硬性门禁（调色板的唯一职责是**区分课程**，好看是次要的）。
 *
 * 之前 8 色里 #27AE60 与 #219653 的 CIEDE2000 只有 7.5 —— 在窄屏周视图 43dp 宽的
 * 单列上就是"两门课一个色"，而这两色相邻排在索引 2 和 7，用户完全可能同时拿到。
 * 换成 #E75FA3 后全表最小色差抬到 19.4（#F2994A ↔ #F2C94C）。
 *
 * 阈值定 15 而不是实测的 19.4：留出微调空间，同时任何"再塞一个近似色"的改动都会撞线。
 * 换色时如果撞线，正确做法是换到更远的色相，而不是调低阈值。
 */
class CoursePaletteTest {

    @Test
    fun paletteKeepsEveryCourseVisuallyDistinct() {
        var bindingPair = ""
        var bindingDelta = Double.MAX_VALUE
        for (i in CourseColors.indices) {
            for (j in i + 1 until CourseColors.size) {
                val delta = deltaE2000(CourseColors[i], CourseColors[j])
                if (delta < bindingDelta) {
                    bindingDelta = delta
                    bindingPair = "[$i]=${hex(CourseColors[i])} ↔ [$j]=${hex(CourseColors[j])}"
                }
            }
        }
        assertTrue(
            "调色板里最接近的一对是 $bindingPair，ΔE2000 = $bindingDelta，下限 $MIN_DELTA_E。" +
                "小色块上 ΔE 低于 15 就已经分不出两门课了。",
            bindingDelta >= MIN_DELTA_E,
        )
    }

    @Test
    fun everyCourseColorHasAnAccessibleTextPick() {
        CourseColors.forEachIndexed { index, color ->
            val foreground = contentOn(color)
            val ratio = contrastRatio(color.readableLuminance(), foreground.readableLuminance())
            assertTrue(
                "课程色 [$index] ${hex(color)} 上 ${hex(foreground)} 只有 $ratio，低于 AA",
                ratio >= DesignTokens.WCAG_AA_RATIO,
            )
        }
    }

    @Test
    fun paletteIsEightDistinctSlots() {
        // 8 是容量上限：多一门课就多一个色相，少一个就会让相邻课程撞色；
        // 索引又要落库（Course.colorIndex），所以既不能重也不能随手改长度。
        assertEquals(8, CourseColors.size)
        assertEquals("调色板里有重复色", CourseColors.size, CourseColors.toSet().size)
    }

    @Test
    fun ciede2000MatchesThePublishedReferenceData() {
        // 门禁自己也得是对的：这几组是 Sharma 等 (2005) 的公开算例，不是本项目的数。
        // 少了这一条，"最小色差 19.4" 完全可以是被算错出来的。
        REFERENCE_PAIRS.forEach { (first, second, expected) ->
            assertEquals(
                "参照对 ${first.joinToString()} vs ${second.joinToString()}",
                expected,
                deltaE2000FromLab(first[0], first[1], first[2], second[0], second[1], second[2]),
                0.0005,
            )
        }
        assertEquals(
            0.0,
            deltaE2000FromLab(50.0, 2.6772, -79.7751, 50.0, 2.6772, -79.7751),
            0.0005,
        )
    }

    private fun hex(color: Color): String {
        fun channel(value: Float) = (value * 255f).toInt().toString(16).uppercase().padStart(2, '0')
        return "#${channel(color.red)}${channel(color.green)}${channel(color.blue)}"
    }

    private fun contrastRatio(lumaA: Float, lumaB: Float): Float {
        val hi = maxOf(lumaA, lumaB)
        val lo = minOf(lumaA, lumaB)
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private fun deltaE2000(a: Color, b: Color): Double {
        val l1 = lab(a)
        val l2 = lab(b)
        return deltaE2000FromLab(l1[0], l1[1], l1[2], l2[0], l2[1], l2[2])
    }

    /**
     * sRGB → Lab（D65）。
     *
     * ⚠️ Compose 的 `Color.red/green/blue` **已经是 0..1** 的 sRGB 分量，这里不能再除 255：
     * 除了就把八色全压成近黑，任何一对的 ΔE 都趋近 0（第一版把 红↔粉 报成 0.11 即此）。
     * 同理，线性分量要直接进 XYZ 矩阵，中途乘 255 会把每一个 ΔE 都放大。
     */
    private fun lab(color: Color): DoubleArray {
        fun toLinear(component: Float): Double {
            val s = component.toDouble()
            return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        val r = toLinear(color.red)
        val g = toLinear(color.green)
        val b = toLinear(color.blue)
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
        val y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883
        fun pivot(t: Double): Double = if (t > 0.008856) t.pow(1.0 / 3.0) else 7.787 * t + 16.0 / 116.0
        val fx = pivot(x)
        val fy = pivot(y)
        val fz = pivot(z)
        return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    private fun deltaE2000FromLab(
        l1: Double,
        a1: Double,
        b1: Double,
        l2: Double,
        a2: Double,
        b2: Double,
    ): Double {
        val c1 = hypot(a1, b1)
        val c2 = hypot(a2, b2)
        val cBar = (c1 + c2) / 2
        val twentyFivePow7 = 25.0.pow(7)
        val g = 0.5 * (1 - sqrt(cBar.pow(7) / (cBar.pow(7) + twentyFivePow7)))
        val c1p = hypot(a1 * (1 + g), b1)
        val c2p = hypot(a2 * (1 + g), b2)
        val h1p = hue(a1 * (1 + g), b1)
        val h2p = hue(a2 * (1 + g), b2)
        val deltaL = l2 - l1
        val deltaC = c2p - c1p
        val deltaH = when {
            c1p * c2p == 0.0 -> 0.0
            abs(h2p - h1p) <= 180 -> h2p - h1p
            h2p - h1p > 180 -> h2p - h1p - 360
            else -> h2p - h1p + 360
        }
        val deltaHp = 2 * sqrt(c1p * c2p) * sin(toRad(deltaH) / 2)
        val lBar = (l1 + l2) / 2
        val cBarp = (c1p + c2p) / 2
        val hBarp = when {
            c1p * c2p == 0.0 -> h1p + h2p
            abs(h1p - h2p) <= 180 -> (h1p + h2p) / 2
            h1p + h2p < 360 -> (h1p + h2p + 360) / 2
            else -> (h1p + h2p - 360) / 2
        }
        val t = 1 -
            0.17 * cos(toRad(hBarp - 30)) +
            0.24 * cos(toRad(2 * hBarp)) +
            0.32 * cos(toRad(3 * hBarp + 6)) -
            0.20 * cos(toRad(4 * hBarp - 63))
        val sL = 1 + 0.015 * (lBar - 50).pow(2) / sqrt(20 + (lBar - 50).pow(2))
        val sC = 1 + 0.045 * cBarp
        val sH = 1 + 0.015 * cBarp * t
        val rT = -2 * sqrt(cBarp.pow(7) / (cBarp.pow(7) + twentyFivePow7)) *
            sin(toRad(60 * exp(-((hBarp - 275) / 25).pow(2))))
        return sqrt(
            (deltaL / sL).pow(2) + (deltaC / sC).pow(2) + (deltaHp / sH).pow(2) +
                rT * (deltaC / sC) * (deltaHp / sH),
        )
    }

    private fun toRad(degrees: Double): Double = degrees * PI / 180
    private fun hue(aPrime: Double, b: Double): Double = ((atan2(b, aPrime) * 180 / PI) % 360 + 360) % 360

    companion object {
        private const val MIN_DELTA_E = 15.0

        /** Sharma et al. (2005) 的公开算例，用来校验上面的实现本身 */
        private val REFERENCE_PAIRS = listOf(
            Triple(doubleArrayOf(50.0, 2.6772, -79.7751), doubleArrayOf(50.0, 0.0, -82.7485), 2.0425),
            Triple(doubleArrayOf(50.0, 3.1571, -77.2803), doubleArrayOf(50.0, 0.0, -82.7485), 2.8615),
            Triple(doubleArrayOf(50.0, 2.8361, -74.0200), doubleArrayOf(50.0, 0.0, -82.7485), 3.4412),
            Triple(doubleArrayOf(50.0, -1.3802, -84.2814), doubleArrayOf(50.0, 0.0, -82.7485), 1.0000),
            Triple(doubleArrayOf(60.2574, -34.0099, 36.2677), doubleArrayOf(60.4626, -34.1751, 39.4387), 1.2644),
        )
    }
}
