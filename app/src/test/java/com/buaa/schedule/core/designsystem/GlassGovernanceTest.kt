package com.buaa.schedule.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * 运行时玻璃降档（R5 F-18）。
 *
 * 此前的写法是 `(runtimeCap ?: Int.MAX_VALUE) - 1`，第一次降档得到 `Int.MAX_VALUE - 1`，
 * 而档位只有 0/1/2 → `coerceAtMost` 恒等，"降了一档"实际什么都没降；
 * 加上调用方随即停用采样，release 包里整条回写路径只此一次且无效。
 * 这里守住：降档真的往下走一档、冷却窗口内不连降、最低停在 OFF、生效档位随之下降。
 */
class GlassGovernanceTest {

    private val cooldown = 11L * 60_000L

    @Before
    fun setUp() {
        GlassGovernance.resetRuntimeForTest()
    }

    @Test
    fun firstLoweringStepsDownToStandard() {
        GlassGovernance.lowerTierForJank(0L)

        assertEquals(DesignTokens.GLASS_TIER_STANDARD, GlassGovernance.runtimeCapForTest())
    }

    @Test
    fun cooldownBlocksASecondLowering() {
        GlassGovernance.lowerTierForJank(0L)
        GlassGovernance.lowerTierForJank(cooldown / 2)

        assertEquals("冷却窗口内不应连降两档", DesignTokens.GLASS_TIER_STANDARD, GlassGovernance.runtimeCapForTest())
    }

    @Test
    fun secondLoweringAfterCooldownStepsDownToOff() {
        GlassGovernance.lowerTierForJank(0L)
        GlassGovernance.lowerTierForJank(cooldown)

        assertEquals(DesignTokens.GLASS_TIER_OFF, GlassGovernance.runtimeCapForTest())
    }

    @Test
    fun loweringNeverGoesBelowOff() {
        var now = 0L
        repeat(6) {
            GlassGovernance.lowerTierForJank(now)
            now += cooldown
        }

        assertEquals(DesignTokens.GLASS_TIER_OFF, GlassGovernance.runtimeCapForTest())
    }

    @Test
    fun effectiveTierFollowsEveryLoweringStep() {
        val enhanced = DesignTokens.GLASS_TIER_ENHANCED
        assumeTrue(
            "静态档位需要允许最高档，才能看出运行时降档的效果",
            GlassGovernance.effectiveTier(enhanced) == enhanced,
        )

        // 旧写法基准是 Int.MAX_VALUE：第一次得到 MAX-1，钳制后仍是最高档 → 这条断言抓住该回归
        GlassGovernance.lowerTierForJank(0L)
        assertEquals(DesignTokens.GLASS_TIER_STANDARD, GlassGovernance.effectiveTier(enhanced))

        GlassGovernance.lowerTierForJank(cooldown)
        assertEquals(DesignTokens.GLASS_TIER_OFF, GlassGovernance.effectiveTier(enhanced))
    }

    @Test
    fun effectiveTierFollowsTheRuntimeCap() {
        val enhanced = DesignTokens.GLASS_TIER_ENHANCED
        assumeTrue(
            "静态档位需要允许最高档，才能看出运行时降档的效果",
            GlassGovernance.effectiveTier(enhanced) == enhanced,
        )

        GlassGovernance.lowerTierForJank(0L)

        assertEquals(DesignTokens.GLASS_TIER_STANDARD, GlassGovernance.effectiveTier(enhanced))
        // 低于上限的用户偏好不受影响
        assertEquals(
            DesignTokens.GLASS_TIER_OFF,
            GlassGovernance.effectiveTier(DesignTokens.GLASS_TIER_OFF),
        )
    }
}
