package com.buaa.schedule.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 运行时玻璃降档（R5 F-18）。
 *
 * 此前的写法是 `(runtimeCap ?: Int.MAX_VALUE) - 1`，第一次降档得到 `Int.MAX_VALUE - 1`，
 * 而钳制只认 `coerceAtMost` → "降了一档"实际什么都没降；
 * 加上调用方随即停用采样，release 包里整条回写路径只此一次且无效。
 * 这里守住：降档真的往下走、冷却窗口内不连降、最低停在 OFF、生效档位随之下降。
 *
 * ②V-14 把「增强」档删掉之后只剩两级，一次降档就等于关玻璃：原来
 * "增强→标准→关闭"的三级阶梯没有中间态可测了，冷却窗口成为唯一的防归零闸门。
 *
 * T315②：`effectiveTierFollowsTheRuntimeCap` 那一格「拿被测函数自己的返回值开道」那一形拆掉了。
 * 改前的形状是假设式 = `GlassGovernance.effectiveTier(standard) == standard` —— 假设式就是被测函数
 * 本身，而它的另一枚输入是本机核数（`glassStaticCapTier` 里那条四核判据），于是在核数 ≤4 的机器上
 * 整格**静默跳过**；`effectiveTier` 在整个测试树里的命中只有这一格内四处 = 唯一覆盖 ⇒ 把
 * `effectiveTier` 改成恒回 `GLASS_TIER_OFF`（玻璃对所有人永久关）在那种机器上永远"绿（跳过）"。
 * 现在拆成两格：
 * - 判据本体抽进 `glassEffectiveTier`（`GlassJankDecision.kt`，两条表达式逐字搬）做表驱动，
 *   两档静态上限 × 有／无运行时钳子逐格打 —— 与本机核数无关，任何机器上都要说话；
 * - 调用点那一格拿**底层那两枚探针**过一遍已有表驱动钉着的 `glassStaticCapTier` 当参照件，
 *   断的是组合而不是判据，前提不再由被读的函数自己担保。
 * ⚠️ 本机实测：改前那一格在这台机器上说了话（Inv-1 的 `GlassGovernanceTest.xml` 里 `skipped="0"`），
 * 本卡修的是"换一枚核数少的机器就整格失声"那一档，不是这台机上的读数。产品行为一条没改：
 * 任何核数、任何降档状态下，同一枚输入的 `effectiveTier` 返回值与改前逐枚相同（复算见 T315 回执）。
 * 永远红不到的那一层也点名：核数 ≤4 的 JVM 上 `effectiveTier` 对两枚偏好的真值都是 OFF，与"恒回 OFF"
 * 那枚反例在**返回值**上不可分辨 —— 那一层的钉子是 `GlassEffectiveTierWiringGuardTest` 的源码形状格。
 */
class GlassGovernanceTest {

    private val cooldown = 11L * 60_000L

    @Before
    fun setUp() {
        GlassGovernance.resetRuntimeForTest()
    }

    @Test
    fun firstLoweringTurnsGlassOff() {
        GlassGovernance.lowerTierForJank(0L)

        assertEquals(DesignTokens.GLASS_TIER_OFF, GlassGovernance.runtimeCapForTest())
    }

    @Test
    fun cooldownBlocksASecondLowering() {
        GlassGovernance.lowerTierForJank(0L)
        GlassGovernance.lowerTierForJank(cooldown / 2)

        assertEquals(
            "冷却窗口内不应连降两档",
            DesignTokens.GLASS_TIER_OFF,
            GlassGovernance.runtimeCapForTest(),
        )
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

    /**
     * 判据内核表驱动：两把钳子的合取逐格打，期望值全部手写在盘上（不调用被测判据算期望）。
     *
     * 这一格才是"唯一覆盖"该有的形状：它不吃本机核数、不吃本机内存，所以在四核 CI 机与开发机上
     * 都照样断言 —— 改前那一格把同一件事押在开道针上，核数一少就整格失声。
     */
    @Test
    fun effectiveTierKernelClampsBothCapsRegardlessOfCoreCount() {
        val off = DesignTokens.GLASS_TIER_OFF
        val standard = DesignTokens.GLASS_TIER_STANDARD
        // 穷举 2 枚偏好 × 2 档静态上限 × 3 档运行时上限（null = 未降档）= 12 格
        val cells = listOf(
            TierCell("两把都放行：拿满标准档", standard, standard, null, standard),
            TierCell("运行时压到 OFF：静态放行也没用", standard, standard, off, off),
            TierCell("运行时等于标准档：不顶高任何东西", standard, standard, standard, standard),
            TierCell("静态判 OFF：最高档也拿不到", standard, off, null, off),
            TierCell("静态 OFF、运行时 OFF", standard, off, off, off),
            TierCell("运行时上限抬不动静态 OFF", standard, off, standard, off),
            TierCell("偏好本就 OFF：静态放行", off, standard, null, off),
            TierCell("偏好 OFF、运行时 OFF", off, standard, off, off),
            TierCell("偏好 OFF、运行时放行", off, standard, standard, off),
            TierCell("偏好 OFF、静态 OFF", off, off, null, off),
            TierCell("三样全 OFF", off, off, off, off),
            TierCell("偏好 OFF、静态 OFF、运行时放行", off, off, standard, off),
        )
        assertEquals(
            "自证：表格必须是 2 枚偏好 × 2 档静态上限 × 3 档运行时上限 = 12 格（实到 " + cells.size + " 格）",
            12,
            cells.size,
        )
        assertEquals(
            "自证：期望为最高档的格子只许 2 枚（偏好与静态上限都放行、且运行时没压下来），" +
                "其余 10 枚都必须是 OFF —— 抄一份期望值给所有格子的那种表，这一格先红（实到 " +
                cells.count { cell -> cell.expected == standard } + " 枚）",
            2,
            cells.count { cell -> cell.expected == standard },
        )
        for (cell in cells) {
            assertEquals(
                cell.name + "（偏好=" + cell.preferred + " 静态上限=" + cell.staticCap +
                    " 运行时上限=" + cell.runtimeCap + "）",
                cell.expected,
                glassEffectiveTier(cell.preferred, cell.staticCap, cell.runtimeCap),
            )
        }
    }

    /**
     * 调用点接线：`effectiveTier` 交回来的必须是判据内核对**本机**两把钳子的结果。
     *
     * 参照件是底层探针 + 已表驱动的静态判据（[staticCapOfThisJvm]），不是被读的 `effectiveTier`；
     * 于是这一格不再有任何"前提不成立就不说话"的分支。第二、三条断言与核数无关：运行时钳子压到
     * OFF 之后 `min(min(1, 静态), 0)` 恒等于 OFF，静态判哪一档都一样要说。
     */
    @Test
    fun effectiveTierFollowsTheRuntimeCap() {
        val off = DesignTokens.GLASS_TIER_OFF
        val standard = DesignTokens.GLASS_TIER_STANDARD
        val staticCap = staticCapOfThisJvm()

        assertEquals(
            "未降档时生效档位 = 判据内核对这两把钳子的结果（本机静态上限判成 " + staticCap + " 档）",
            glassEffectiveTier(standard, staticCap, null),
            GlassGovernance.effectiveTier(standard),
        )

        // 旧写法基准是 Int.MAX_VALUE：第一次得到 MAX-1，钳制后仍是最高档 → 下面两条抓住该回归
        GlassGovernance.lowerTierForJank(0L)
        assertEquals(off, GlassGovernance.runtimeCapForTest())
        assertEquals(
            "降档之后生效档位必须落到 OFF —— 这一条与核数无关",
            off,
            GlassGovernance.effectiveTier(standard),
        )
        assertEquals(
            "低于上限的用户偏好不受影响",
            glassEffectiveTier(off, staticCap, off),
            GlassGovernance.effectiveTier(off),
        )
    }

    /**
     * 本机静态上限 = 与生产 `staticCap` 同源的**底层探针**（JVM 报的可用内存与核数）过一遍
     * `glassStaticCapTier` —— 参照件读的是探针，不是被断言的那个函数。
     */
    private fun staticCapOfThisJvm(): Int = glassStaticCapTier(
        Runtime.getRuntime().maxMemory() / 1024L / 1024L,
        Runtime.getRuntime().availableProcessors(),
        DesignTokens.GLASS_TIER_OFF,
        DesignTokens.GLASS_TIER_STANDARD,
    )

    /** 表驱动用的格子：判据内核的输入与**手写**的期望档位 */
    private data class TierCell(
        val name: String,
        val preferred: Int,
        val staticCap: Int,
        val runtimeCap: Int?,
        val expected: Int,
    )
}
