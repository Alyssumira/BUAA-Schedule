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
    fun autoTextOnFullyTransparentBackdropPicksHigherContrastSide() {
        // 全透明时不看背景色，按中灰背板（0.5）折算 → #808080，相对亮度 0.214：
        // 黑字 5.3:1 对白字 4.0:1，所以取深色。
        // 旧断言期望白字，前提是被 U-01 推翻的那条"亮度 > 0.45 才用深字"——
        // 黑/白等对比度的真正交点在 0.203，0.203~0.45 这一段原本全部判错。
        val appearance = WidgetAppearance(backgroundColor = 0xFFF3F5FA.toInt(), alphaPercent = 0)
        assertEquals(0xFF14161C.toInt(), appearance.titleColor())
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

    @Test
    fun viewIdStampTracksAppearance() {
        // getItemId 的偏移量：改外观必须换指纹，否则宿主按 hasStableIds 复用旧行视图，
        // 配置页保存后组件仍是改之前的配色。
        val base = WidgetAppearance()
        assertNotEquals(
            "只改文字颜色也要能反映在指纹上",
            base.viewIdStamp(),
            base.copy(textMode = WidgetAppearance.TEXT_DARK).viewIdStamp(),
        )
        assertNotEquals(
            "圆角同样参与指纹",
            base.viewIdStamp(),
            base.copy(cornerBucket = base.cornerBucket + 1).viewIdStamp(),
        )
        // 反过来：外观没变时指纹必须逐次一致，不然每次 onDataSetChanged 都会全量重绑
        assertEquals(
            "同一份外观的指纹要稳定",
            base.viewIdStamp(),
            WidgetAppearance().viewIdStamp(),
        )
        // 整批行共用一个偏移：行间唯一性不受影响（base 不同 → id 不同）
        val stamp = base.copy(textMode = WidgetAppearance.TEXT_DARK).viewIdStamp()
        val ids = listOf(0L, 31L, 62L, 4_711L).map { it + stamp }
        assertEquals("加偏移后行 id 仍要互不相同", ids.size, ids.toSet().size)
    }
}

/**
 * 4×2「今天」表头的高亮配色（审查 3.4）。
 *
 * 硬约束是**颜色必须由既有的取色管线推导**：用户可以把组件背景设成云白，
 * 这时任何硬编码的白胶囊都会变成白字白底、表头整列看不见。
 */
class WidgetTodayHighlightTest {

    private val darkBg = 0xFF16203A.toInt()
    private val lightBg = 0xFFF3F5FA.toInt()
    private val backgrounds = listOf(darkBg, lightBg)
    private val textModes = listOf(
        WidgetAppearance.TEXT_AUTO, WidgetAppearance.TEXT_LIGHT, WidgetAppearance.TEXT_DARK,
    )

    @Test
    fun pillAndItsLabelAlwaysOnOppositeSides() {
        textModes.forEach { mode ->
            backgrounds.forEach { bg ->
                val appearance = WidgetAppearance(backgroundColor = bg, alphaPercent = 100, textMode = mode)
                assertNotEquals(
                    "胶囊与它上面那几个字同色（模式 $mode / 背景 $bg）",
                    appearance.todayHighlightFor(bg),
                    appearance.onTodayHighlightFor(bg),
                )
            }
        }
    }

    @Test
    fun pillRidesTheSameInkPipelineAsTheTitle() {
        // 胶囊底色与标题墨色同源，才保证它一定浮在用户设的背景上
        textModes.forEach { mode ->
            backgrounds.forEach { bg ->
                val appearance = WidgetAppearance(backgroundColor = bg, alphaPercent = 100, textMode = mode)
                assertEquals(appearance.titleColorFor(bg), appearance.todayHighlightFor(bg))
            }
        }
    }

    @Test
    fun pillFlipsBetweenLightAndDarkBackgrounds() {
        val appearance = WidgetAppearance(alphaPercent = 100)
        assertNotEquals(
            "浅色背景下高亮必须自动翻面，否则白胶囊压白底",
            appearance.todayHighlightFor(darkBg),
            appearance.todayHighlightFor(lightBg),
        )
        assertEquals(0xFFFFFFFF.toInt(), appearance.todayHighlightFor(darkBg))
        assertEquals(0xFF14161C.toInt(), appearance.todayHighlightFor(lightBg))
    }

    @Test
    fun alphaStaysInRangeAndMatchesTheSharedConstant() {
        val appearance = WidgetAppearance()
        assertTrue(appearance.todayHighlightAlpha > 0f)
        assertTrue(appearance.todayHighlightAlpha <= 1f)
        assertEquals(WidgetAppearance.TODAY_HIGHLIGHT_ALPHA, appearance.todayHighlightAlpha, 0.001f)
    }
}

/** 测试辅助：自定义配色模式下，"实际背景色"就是 backgroundColor 本身 */
private fun WidgetAppearance.titleColor(): Int = titleColorFor(backgroundColor)
private fun WidgetAppearance.bodyColor(): Int = bodyColorFor(backgroundColor)
