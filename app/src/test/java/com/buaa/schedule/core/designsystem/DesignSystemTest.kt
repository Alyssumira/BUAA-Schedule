package com.buaa.schedule.core.designsystem

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
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
}
