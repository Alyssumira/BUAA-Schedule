package com.buaa.schedule.core.designsystem

import androidx.compose.ui.graphics.Color
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GlassSurface] 的 `surfaceAlpha` 取值域闸门。
 *
 * 崩溃现场（release 真机，两次同一栈）：
 * `IllegalArgumentException: Cannot coerce value to an empty range: maximum 0.96 is less than minimum 1.0`
 * —— 下限来自 [legibilityAlphaFloor]，天花板是 0.96（玻璃不许被压成不透明板），
 * 而 `DesignTokens.glassAlphaFloor` 的值域上界是 **1f**：当「底板亮度已经站在该文字色
 * 对应的 AA 临界点的另一侧」（这块板无论压到多实都读不清）时比值 ≥ 1，下限饱和到 1.0，
 * 于是 `coerceIn(1.0, 0.96)` 当场抛。
 *
 * 781 个既有用例一个都没抓到，因为 [DesignSystemTest] 的定义域断言承认 1.0 合法
 * （那是对的，错的是消费方假设它不超过 0.96），而它的属性测试恰好跳过「板与字本来就没对比」
 * 的组合——那正是饱和到 1.0 的那一族。这里把那一族按真实色值钉成表。
 *
 * 表里的色值全是真实成员（不是构造的中间色），luma 用 [readableLuminance] 口径核对过，
 * 与 [legibilityAlphaFloor] 读的 `Color.luminance()` 在这些值上一致。
 * 场景亮度靠 [SceneLuma] 驱动：`null` = 没有真壁纸（走内置渐变常数），
 * 其余按实测分块极值给。
 *
 * 这条口径住在 internal 纯函数 [glassSurfaceAlpha] 里：本模块的 JVM 单测没有 Compose
 * 运行时（无 Robolectric、无 ui-test），它留在 `@Composable` 体内就一行也测不到。
 */
class GlassSurfaceAlphaTest {

    @After
    fun restoreScene() {
        SceneLuma.wallpaper = SceneLuma.Stats.Unknown
    }

    /**
     * 反解下限饱和到 1.0 的四族：改前 `coerceIn` 直接抛，改后走满天花板 0.96。
     *
     * 落在 0.96 的语义是「救不清的板压实到极限，仍不保证 AA」（见 [glassSurfaceAlpha] 的 KDoc），
     * 所以这里断的是**活着 + 不越过天花板**，不是断对比度达标。
     */
    @Test
    fun saturatedLegibilityFloorLandsOnTheCeilingInsteadOfThrowing() {
        for (case in unsalvageablePlates) {
            val floor = legibilityAlphaFloorOf(case)
            assertTrue(
                "${case.label}：这一族本就该饱和到 1.0，实测下限 $floor——靶子失效了",
                floor >= 1f,
            )
            val alpha = runCatching { surfaceAlphaOf(case) }.getOrElse {
                throw AssertionError("${case.label}：下限 $floor 与天花板 $SURFACE_ALPHA_CEILING 组不成区间", it)
            }
            assertEquals(
                "${case.label}：下限饱和时应该走满天花板",
                SURFACE_ALPHA_CEILING, alpha, 0f,
            )
        }
    }

    /**
     * 安全基线：读得清的板，一个 number 都不许动（±0.001）。
     *
     * 这条是防「为了修崩溃顺手把通透感调没了」——下限 0.08 压不住 0.18 / 0.34 这两档材质值，
     * 改前改后都该原样透出来。
     */
    @Test
    fun readablePlatesKeepTheirMaterialAlpha() {
        for ((case, expected) in readablePlates) {
            assertEquals(
                "${case.label}：通透感不该被这次改动碰掉",
                expected, surfaceAlphaOf(case),
                0.001f,
            )
        }
    }

    /**
     * 全族扫一遍：任何（板 × 字 × 壁纸 × 变体 × 用户透明度）都不许抛，且永远落在 [0.08, 0.96]。
     *
     * 灰阶是重灾区（板与字的亮度差可以任意小，span 一过 0.001 就翻到饱和），
     * 所以三轴都扫网格，而不是只扫上面四个点。
     * 顺带断言扫到的饱和组数 > 0——不然这条守卫是空的（网格退化成"什么都没测到"）。
     */
    @Test
    fun everyPlateTextSceneCombinationStaysInTheRange() {
        val grays = (0..10).map { Color(it / 10f, it / 10f, it / 10f) }
        val scenes = listOf(
            null,
            SceneLuma.Stats(0.5f, 0.01f, 0.08f),
            SceneLuma.Stats(0.5f, 0.05f, 0.60f),
            SceneLuma.Stats(0.5f, 0.30f, 0.85f),
        )
        val scales = listOf(0.18f, 1f, 1.25f)
        var checked = 0
        var saturated = 0
        for (plate in grays) for (text in grays) for (scene in scenes) {
            for (variant in GlassVariant.entries) for (scale in scales) {
                SceneLuma.wallpaper = scene ?: SceneLuma.Stats.Unknown
                for (darkTheme in listOf(true, false)) {
                    val floor = legibilityAlphaFloor(plate, text, darkTheme)
                    if (floor > SURFACE_ALPHA_CEILING) saturated++
                    val alpha = runCatching {
                        glassSurfaceAlpha(
                            variant = variant,
                            material = DesignTokens.glassMaterial(variant),
                            semanticTint = null,
                            alphaScale = scale,
                            baseTint = plate,
                            text = text,
                            darkTheme = darkTheme,
                        )
                    }.getOrElse {
                        throw AssertionError(
                            "板=$plate 字=$text 暗主题=$darkTheme 壁纸=$scene 变体=$variant " +
                                "倍率=$scale → 下限 $floor 与天花板 $SURFACE_ALPHA_CEILING 组不成区间",
                            it,
                        )
                    }
                    assertTrue(
                        "板=$plate 字=$text 壁纸=$scene → 算出 $alpha 越界",
                        alpha in DesignTokens.GLASS_HARD_MIN_ALPHA..SURFACE_ALPHA_CEILING,
                    )
                    checked++
                }
            }
        }
        assertTrue("参数化测试退化：只检查了 $checked 组", checked == 11 * 11 * 4 * 4 * 3 * 2)
        assertTrue("扫描没有覆盖到饱和下限的组合，这条守卫是空的（saturated=$saturated）", saturated > 0)
    }

    // ---- 表 ----------------------------------------------------------------

    private class Case(
        val label: String,
        val plate: Color,
        val text: Color,
        val darkTheme: Boolean,
        val variant: GlassVariant,
        /** 语义色 tint：非 null 时它同时也是底板色，与真实调用点一致 */
        val semanticTint: Color?,
        /** 实测壁纸分块极值；null = 没有真壁纸，场景是内置渐变 */
        val wallpaper: SceneLuma.Stats?,
    )

    /**
     * 1. 深色主题动画途中：`Theme.animatedColor` 让 onSurfaceVariant 从 0.063 连续插值到 0.568，
     *    而 darkTheme 判据（background.luminance() < 0.5）在 t≈0.26 就翻过去了——此刻文字还是
     *    中灰（#696969，luma 0.141 ≤ 0.211），暗板 0.0081 底下又垫着内置深色渐变的 0.08 亮块，
     *    反解出的需要 alpha > 1 → 崩。动画跑完（> 0.211）自愈，所以重启后看起来正常。
     * 2. 深色主题红 ALERT（稳定态）：DarkError 0.5684 与浅色正文 0.5681 亮度几乎相同，
     *    暗块 0.01 那头永远解不出 4.5:1。
     * 3. 浅色主题 + 暗壁纸块 + 红 ALERT（稳定态）：LightError 0.1125 压不住 0.05 的暗斑，
     *    近黑的 onSurfaceVariant 在它上面本来就差着一大截。
     * 4. 亮斑上的成功色：DarkSuccess 0.5467 配 0.60 的亮块，板比场景还暗，
     *    压实只会把复合亮度推向 0.5467，离浅字的 AA 临界更远。
     */
    private val unsalvageablePlates = listOf(
        Case(
            label = "切深色动画途中（暗玻璃板 + 中灰文字）",
            plate = DarkGlassTint, // #14161C → 0.0081
            text = Color(0xFF696969), // 插值中的中灰 → 0.141
            darkTheme = true,
            variant = GlassVariant.PANEL,
            semanticTint = null,
            wallpaper = null, // 内置深色渐变：brightest 0.08 > 0.009
        ),
        Case(
            label = "深色主题红 ALERT",
            plate = DarkError, // #FFB4AB → 0.5684
            text = DarkBlueOnSurfaceVariant, // #C5C6D0 → 0.5681
            darkTheme = true,
            variant = GlassVariant.ALERT,
            semanticTint = DarkError,
            wallpaper = null, // 内置深色渐变：darkest 0.01 ≤ 0.567
        ),
        Case(
            label = "浅色主题 + 暗壁纸块 + 红 ALERT",
            plate = LightError, // #BA1A1A → 0.1125
            text = BlueOnSurfaceVariant, // #44474F → 0.0630
            darkTheme = false,
            variant = GlassVariant.ALERT,
            semanticTint = LightError,
            wallpaper = SceneLuma.Stats(mean = 0.5f, darkest = 0.05f, brightest = 0.90f),
        ),
        Case(
            label = "亮壁纸块上的成功色",
            // DarkSemanticColors.success = #7BD69B → 0.5467
            plate = Color(0xFF7BD69B),
            text = DarkBlueOnSurfaceVariant, // #C5C6D0 → 0.5681
            darkTheme = true,
            variant = GlassVariant.ALERT,
            semanticTint = Color(0xFF7BD69B),
            wallpaper = SceneLuma.Stats(mean = 0.5f, darkest = 0.02f, brightest = 0.60f),
        ),
    )

    /** 读得清的两条基线：暗玻璃板配浅色正文、浅玻璃板配深色正文，下限都落在 0.08 的绝对地板 */
    private val readablePlates = listOf(
        Case(
            label = "深色主题 PANEL（暗玻璃板 + 浅色正文）",
            plate = DarkGlassTint,
            text = DarkBlueOnSurfaceVariant,
            darkTheme = true,
            variant = GlassVariant.PANEL,
            semanticTint = null,
            wallpaper = null,
        ) to DesignTokens.PANEL_SURFACE_ALPHA, // 0.18
        Case(
            label = "深色主题 ALERT（无语义色）",
            plate = DarkGlassTint,
            text = DarkBlueOnSurfaceVariant,
            darkTheme = true,
            variant = GlassVariant.ALERT,
            semanticTint = null,
            wallpaper = null,
        ) to LiquidGlassMaterial.dialog().surfaceAlpha, // 0.34
    )

    // ---- 取值 --------------------------------------------------------------

    private fun legibilityAlphaFloorOf(case: Case): Float {
        SceneLuma.wallpaper = case.wallpaper ?: SceneLuma.Stats.Unknown
        return legibilityAlphaFloor(case.plate, case.text, case.darkTheme)
    }

    private fun surfaceAlphaOf(case: Case): Float {
        SceneLuma.wallpaper = case.wallpaper ?: SceneLuma.Stats.Unknown
        return glassSurfaceAlpha(
            variant = case.variant,
            material = DesignTokens.glassMaterial(case.variant),
            semanticTint = case.semanticTint,
            alphaScale = 1f, // 用户偏好默认档（cardAlpha 0.88）
            baseTint = case.plate,
            text = case.text,
            darkTheme = case.darkTheme,
        )
    }
}
