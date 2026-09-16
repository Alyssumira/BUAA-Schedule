package com.buaa.schedule.core.designsystem

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignSystemTest {

    @Test
    fun lightCourseColorUsesDarkText() {
        // 浅黄课程色上白字对比度不足，应选深色文字
        val yellow = Color(0xFFF2C94C)
        assertEquals(Color(0xFF1A1B20), contentOn(yellow))
    }

    @Test
    fun darkCourseColorUsesWhiteText() {
        val blue = Color(0xFF5B8DEF)
        assertEquals(Color.White, contentOn(blue))
    }

    @Test
    fun pureWhiteAndBlackTextColors() {
        assertEquals(Color(0xFF1A1B20), contentOn(Color.White))
        assertEquals(Color.White, contentOn(Color.Black))
    }

    @Test
    fun glassIntensityScalesOnlyAtEnhancedTier() {
        assertEquals(1f, DesignTokens.glassIntensity(DesignTokens.GLASS_TIER_OFF), 0.001f)
        assertEquals(1f, DesignTokens.glassIntensity(DesignTokens.GLASS_TIER_STANDARD), 0.001f)
        assertEquals(1.3f, DesignTokens.glassIntensity(DesignTokens.GLASS_TIER_ENHANCED), 0.001f)
        // 越界档位按增强档处理
        assertEquals(1.3f, DesignTokens.glassIntensity(99), 0.001f)
    }

    @Test
    fun enhancedTierScalesEveryVariantBySameRatio() {
        // 同一档位下各变体的强度变化幅度必须一致。
        // 此前 CHROME 走 pill(1.3f * intensity.coerceAtMost(1.2f))，
        // 增强档被截断到 1.5/1.3≈1.15 倍，而其他变体是 1.3 倍。
        GlassVariant.entries.forEach { variant ->
            val standard = DesignTokens.glassMaterial(variant, DesignTokens.GLASS_TIER_STANDARD)
            val enhanced = DesignTokens.glassMaterial(variant, DesignTokens.GLASS_TIER_ENHANCED)
            assertTrue("$variant 增强档应强于标准档", enhanced.lensHeight > standard.lensHeight)
            val ratio = enhanced.lensHeight.value / standard.lensHeight.value
            assertEquals("$variant 档位倍率应为 1.3", 1.3f, ratio, 0.01f)
        }
    }

    @Test
    fun chromeVariantIsStrongerThanCompactAtSameTier() {
        val chrome = DesignTokens.glassMaterial(GlassVariant.CHROME, DesignTokens.GLASS_TIER_STANDARD)
        val compact = DesignTokens.glassMaterial(GlassVariant.COMPACT, DesignTokens.GLASS_TIER_STANDARD)
        assertTrue(chrome.lensHeight > compact.lensHeight)
    }

    @Test
    fun offTierKeepsOnlySmallAreaGlass() {
        // 关闭档＝大面板退化成实心卡片，小面积玻璃留着（用户要的"小部分玻璃才好看"）
        assertFalse(DesignTokens.surfaceUsesGlass(DesignTokens.GLASS_TIER_OFF, GlassVariant.PANEL))
        GlassVariant.entries.filter { it != GlassVariant.PANEL }.forEach {
            assertTrue("$it 在关闭档应仍是玻璃", DesignTokens.surfaceUsesGlass(DesignTokens.GLASS_TIER_OFF, it))
        }
        // 开启档不加区分
        GlassVariant.entries.forEach {
            assertTrue(DesignTokens.surfaceUsesGlass(DesignTokens.GLASS_TIER_STANDARD, it))
        }
    }

    @Test
    fun glassAlphaFloorBuysBackTheContrastItPromises() {
        val plates = listOf(0.008f, 0.03f, 0.06f, 0.30f, 0.60f, 0.90f)
        val scenes = listOf(0.01f, 0.05f, 0.13f, 0.30f, 0.50f, 0.70f, 0.90f)
        val texts = listOf(0.011f, 0.06f, 0.25f, 0.55f, 0.77f, 0.95f)
        var checked = 0
        for (plate in plates) for (scene in scenes) for (text in texts) {
            // 底板与文字之间本来就没对比（暗字配暗板），任何 alpha 都救不了：
            // 那是调用方选错了 tint，不是下限算错，跳过。
            if (contrastRatio(plate, text) < DesignTokens.WCAG_AA_RATIO) continue
            val floor = DesignTokens.glassAlphaFloor(plate, scene, text)
            val ratio = contrastRatio(composite(plate, scene, floor), text)
            assertTrue(
                "板=$plate 景=$scene 字=$text → 下限=$floor 后对比度只有 $ratio",
                ratio >= DesignTokens.WCAG_AA_RATIO - 0.02f,
            )
            checked++
        }
        assertTrue("属性测试退化：只检查了 $checked 组", checked > 60)
    }

    @Test
    fun glassAlphaFloorStaysTransparentWhenSceneIsSafe() {
        // 浅色板 + 深色文字 + 亮壁纸：本来就读得清，不该被强行压实
        assertEquals(
            DesignTokens.GLASS_HARD_MIN_ALPHA,
            DesignTokens.glassAlphaFloor(0.90f, 0.80f, 0.011f),
            0.001f,
        )
        // 未知场景（内置渐变之外的 null）：退回绝对下限
        assertEquals(
            DesignTokens.GLASS_HARD_MIN_ALPHA,
            DesignTokens.glassAlphaFloor(0.90f, Float.NaN, 0.011f),
            0.001f,
        )
        // 越危险（背景越暗）越实
        val overBright = DesignTokens.glassAlphaFloor(0.90f, 0.60f, 0.011f)
        val overDark = DesignTokens.glassAlphaFloor(0.90f, 0.02f, 0.011f)
        assertTrue("暗壁纸下的下限 $overDark 应高于亮背景 $overBright", overDark > overBright)
        // 定义域永远在 [hardMin, 1]
        for (scene in listOf(0f, 0.2f, 0.5f, 1f)) {
            val floor = DesignTokens.glassAlphaFloor(0.02f, scene, 0.8f)
            assertTrue(floor in DesignTokens.GLASS_HARD_MIN_ALPHA..1f)
        }
    }

    private fun composite(surfaceLuma: Float, sceneLuma: Float, alpha: Float): Float =
        surfaceLuma * alpha + sceneLuma * (1f - alpha)

    private fun contrastRatio(lumaA: Float, lumaB: Float): Float {
        val hi = maxOf(lumaA, lumaB)
        val lo = minOf(lumaA, lumaB)
        return (hi + 0.05f) / (lo + 0.05f)
    }
}
