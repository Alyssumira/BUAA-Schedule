package com.buaa.schedule.widget

import com.buaa.schedule.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桌面组件外观的纯逻辑测试。
 *
 * 这些规则直接决定桌面上组件的可读性（自动文字色）与 RemoteViews 能接受的
 * 取值边界（圆角档位、不透明度），出错的代价是"组件变砖"或文字看不清，
 * 因此全部钉在单测里。
 */
class WidgetAppearanceTest {

    @Test
    fun defaultsMatchStaticGlassLook() {
        val appearance = WidgetAppearance()
        assertEquals(80, appearance.alphaPercent)
        assertEquals("不透明度应按百分比换算", 0.8f, appearance.alphaFraction, 0.001f)
        assertEquals("默认圆角应与原静态素材一致（20dp）", R.drawable.widget_bg_r20, appearance.cornerDrawableRes)
        assertTrue(appearance.showTitle)
        assertEquals(WidgetAppearance.TEXT_AUTO, appearance.textMode)
    }

    @Test
    fun autoTextIsLightOnDarkBackground() {
        // 深蓝玻璃底 → 白字
        val appearance = WidgetAppearance(backgroundColor = 0xFF16203A.toInt(), alphaPercent = 100)
        assertEquals(0xFFFFFFFF.toInt(), appearance.titleColor())
        assertEquals(0xFFE8F0FE.toInt(), appearance.bodyColor())
    }

    @Test
    fun autoTextIsDarkOnLightBackground() {
        // 云白底 → 深字（浅底配白字会看不清）
        val appearance = WidgetAppearance(backgroundColor = 0xFFF3F5FA.toInt(), alphaPercent = 100)
        assertEquals(0xFF14161C.toInt(), appearance.titleColor())
        assertEquals(0xFF3A3F4B.toInt(), appearance.bodyColor())
    }

    @Test
    fun autoTextFallsBackToLightWhenFullyTransparent() {
        // 全透明时不看背景色，按中灰背板折算 → 取浅色文字（与预览一致）
        val appearance = WidgetAppearance(backgroundColor = 0xFFF3F5FA.toInt(), alphaPercent = 0)
        assertEquals(0xFFFFFFFF.toInt(), appearance.titleColor())
    }

    @Test
    fun explicitTextModeOverridesAuto() {
        val lightOnLightBg = WidgetAppearance(
            backgroundColor = 0xFFF3F5FA.toInt(),
            alphaPercent = 100,
            textMode = WidgetAppearance.TEXT_LIGHT,
        )
        assertEquals(0xFFFFFFFF.toInt(), lightOnLightBg.titleColor())

        val darkOnDarkBg = WidgetAppearance(
            backgroundColor = 0xFF16203A.toInt(),
            alphaPercent = 100,
            textMode = WidgetAppearance.TEXT_DARK,
        )
        assertEquals(0xFF14161C.toInt(), darkOnDarkBg.titleColor())
    }

    @Test
    fun alphaIsClampedToValidRange() {
        assertEquals(1f, WidgetAppearance(alphaPercent = 150).alphaFraction, 0.001f)
        assertEquals(0f, WidgetAppearance(alphaPercent = -20).alphaFraction, 0.001f)
    }

    @Test
    fun cornerBucketIsClampedInsteadOfCrashing() {
        // 越界档位（历史数据 / 损坏配置）必须夹取，不能让 RemoteViews 拿到非法下标
        val tooLarge = WidgetAppearance(cornerBucket = 99)
        assertEquals(R.drawable.widget_bg_r28, tooLarge.cornerDrawableRes)
        val negative = WidgetAppearance(cornerBucket = -1)
        assertEquals(R.drawable.widget_bg_r0, negative.cornerDrawableRes)
    }

    @Test
    fun everyCornerBucketMapsToADistinctDrawable() {
        val drawables = WidgetAppearance.CORNER_DRAWABLES
        assertEquals(
            "圆角档位数必须与 drawable 表一一对应，多一个标签就会越界",
            WidgetAppearance.CORNER_RADII_DP.size,
            drawables.size,
        )
        assertEquals(
            "圆角 drawable 不能重复（复制粘贴最容易错在这里）",
            drawables.size,
            drawables.toSet().size,
        )
    }

    @Test
    fun presetColorsAreOpaqueArgb() {
        assertTrue("预设色至少要有 4 个可用项", WidgetAppearance.PRESET_COLORS.size >= 4)
        WidgetAppearance.PRESET_COLORS.forEach { (argb, label) ->
            val alpha = (argb ushr 24) and 0xFF
            assertEquals("预设色 $label 必须是完全不透明（透明度交给 alphaPercent）", 0xFF, alpha)
            assertNotEquals("预设色 $label 不能是透明黑", 0, argb)
        }
    }

    @Test
    fun textModeLabelsCoverEveryMode() {
        assertEquals(3, WidgetAppearance.TEXT_MODE_LABELS.size)
        assertEquals(WidgetAppearance.TEXT_AUTO, 0)
        assertEquals(WidgetAppearance.TEXT_LIGHT, 1)
        assertEquals(WidgetAppearance.TEXT_DARK, 2)
    }
}

/** 测试辅助：自定义配色模式下，"实际背景色"就是 backgroundColor 本身 */
private fun WidgetAppearance.titleColor(): Int = titleColorFor(backgroundColor)
private fun WidgetAppearance.bodyColor(): Int = bodyColorFor(backgroundColor)
