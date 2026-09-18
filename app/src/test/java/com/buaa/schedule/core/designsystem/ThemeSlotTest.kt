package com.buaa.schedule.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 颜色槽位门禁（T-15 / V-07）。
 *
 * `lightColorScheme()` / `darkColorScheme()` 的 36 个颜色参数**每一个都有默认值**：
 * 漏写一个不会编译失败，只会静默落到 M3 的 baseline 上 —— 而 baseline 是紫调
 * （tertiary 340°、outlineVariant 270°、surfaceContainerHigh 276°、surfaceTint = baseline primary）。
 * 本项目基调 214° 蓝，于是关玻璃后所有降级面板泛紫（surfaceTint 就是底板色）、
 * 借用 tertiary 的警告文字是玫紫色。这类缺陷没有任何编译器会报。
 *
 * 所以这里遍历槽位本身，而不是逐个手写断言：material3 升级新增槽位时，
 * 数量断言会失败并强制有人回头看一眼。
 *
 * 排版侧是同一个洞、同一套做法：见 [typographyDefinesEveryLevel]。
 */
class ThemeSlotTest {

    @Test
    fun lightSchemeDefinesEverySlot() {
        assertNoBaselineLeak(LightColors, lightColorScheme(), "浅色")
    }

    @Test
    fun darkSchemeDefinesEverySlot() {
        assertNoBaselineLeak(DarkColors, darkColorScheme(), "深色")
    }

    @Test
    fun seededSchemeInheritsTheFullSlotCoverage() {
        // 种子主题是从 Light/Dark copy 出来的，理论上不可能漏槽位，但它同时改了 9 个值：
        // 一旦哪天改成 `ColorScheme(...)` 重新构造，这里就是唯一还能兜住的那道线。
        GREEN_SEEDS.forEach { seed ->
            assertNoBaselineLeak(seedColorScheme(darkTheme = false, argb = seed), lightColorScheme(), "种子 $seed")
            assertNoBaselineLeak(seedColorScheme(darkTheme = true, argb = seed), darkColorScheme(), "种子 $seed")
        }
    }

    @Test
    fun seededSchemeMovesTheSecondaryFamilyToo() {
        // 只换 primary 会留下"新主色 + 旧蓝灰"的半新半旧状态（chip 底、标签底仍是蓝灰）
        val seeded = seedColorScheme(darkTheme = false, argb = GREEN_SEEDS.first())
        assertNotEquals(
            "secondary 仍跟着北航蓝，说明种子只派生了一半",
            LightColors.secondary,
            seeded.secondary,
        )
        assertTrue(
            "绿色种子派生出的 secondary ${seeded.secondary} 不是绿系",
            seeded.secondary.green > seeded.secondary.red &&
                seeded.secondary.green > seeded.secondary.blue,
        )
    }

    @Test
    fun surfaceTintMatchesPrimarySoDegradedGlassReadsAsTheTheme() {
        // LiquidGlass 的降级路径画的是 surfaceTint（见 `liquidGlass` 的 fallback 分支）
        assertEquals(LightColors.primary, LightColors.surfaceTint)
        assertEquals(DarkColors.primary, DarkColors.surfaceTint)
        val seeded = seedColorScheme(darkTheme = false, argb = GREEN_SEEDS.first())
        assertEquals(seeded.primary, seeded.surfaceTint)
    }

    @Test
    fun semanticColorsAreReadableOnTheirOwnTheme() {
        val pairs = listOf(
            "warning/浅" to (LightSemanticColors.warning to LightColors.surface),
            "warning/深" to (DarkSemanticColors.warning to DarkColors.surface),
            "success/浅" to (LightSemanticColors.success to LightColors.surface),
            "success/深" to (DarkSemanticColors.success to DarkColors.surface),
        )
        pairs.forEach { (name, pair) ->
            val ratio = contrast(pair.first, pair.second)
            assertTrue("$name 只有 $ratio，低于 AA 4.5:1", ratio >= DesignTokens.WCAG_AA_RATIO)
        }
    }

    /**
     * 排版槽位门禁（③P3-6），与 [assertNoBaselineLeak] 同一套做法。
     *
     * `Typography` 的 15 个参数每一个都有 Material 3 默认值，少写一级不会编译失败，
     * 只会静默落到 baseline 的字号上 —— 项目里出过一次"名义 12sp、实际 11sp"，
     * 这次是 headlineMedium：统计页的大数字一直在用 M3 的那 28sp/400 字重。
     *
     * 排版侧不需要颜色侧那份"与规范同值"白名单：baseline 的 TextStyle 自带
     * `fontFamily=SansSerif`、`letterSpacing`、`platformStyle`，本项目的刻度只写
     * 字重/字号/行高，所以**显式写过的档位永远不可能与 baseline 等值**，
     * 相等只可能是"这一级根本没写、拿到的就是默认对象本身"。
     */
    @Test
    fun typographyDefinesEveryLevel() {
        val ours = typeSlots(ScheduleTypography)
        val theirs = typeSlots(Typography())
        assertEquals(
            "Typography 槽位数量变了（${ours.size}），material3 升级后需要回头补全定义",
            EXPECTED_TYPOGRAPHY_SLOT_COUNT,
            ours.size,
        )
        val leaked = ours.entries.filter { (name, style) -> style == theirs[name] }
        assertTrue(
            "排版刻度里有 ${leaked.size} 级没写，正在静默使用 M3 baseline 的默认值：${leaked.map { it.key }}",
            leaked.isEmpty(),
        )
    }

    /**
     * 每个槽位都必须是我们显式定义的值。
     *
     * 允许与 baseline 相同的情况只有两种：
     * 1. **该槽位本身就是纯黑/纯白**（`onPrimary`、`scrim` 这类，谁算都是一个数）；
     * 2. 值本身在 [SPEC_BASELINE_IDENTICAL] 里登记过。
     * 这条规则不维护"哪个槽位算正常"的大名单，因此新增槽位默认落到紫调时照样会被抓出来。
     */
    private fun assertNoBaselineLeak(scheme: ColorScheme, baseline: ColorScheme, label: String) {
        val ours = colorSlots(scheme)
        val theirs = colorSlots(baseline)
        assertEquals(
            "ColorScheme 槽位数量变了（${ours.size}），material3 升级后需要回头补全定义",
            EXPECTED_SLOT_COUNT,
            ours.size,
        )
        // 防过期：名单里的值必须仍然是 baseline 的某个取值。规范漂了就该删条目，
        // 而不是让它继续把真实的漏写静音掉。
        SPEC_BASELINE_IDENTICAL.forEach { spec ->
            assertTrue(
                "$spec 登记为「与 $label baseline 规范同值」，但 baseline 里已经没有这个值了——条目该删",
                ours.values.none { it == spec } || theirs.values.contains(spec),
            )
        }
        val leaked = ours.entries.filter { (name, color) ->
            color == theirs[name] &&
                color != Color.White &&
                color != Color.Black &&
                color !in SPEC_BASELINE_IDENTICAL
        }
        assertTrue(
            "$label 主题里有 ${leaked.size} 个槽位仍是 M3 baseline 的默认值（紫调）：$leaked",
            leaked.isEmpty(),
        )
    }

    private fun colorSlots(scheme: ColorScheme): Map<String, Color> =
        ColorScheme::class.java.methods
            .filter {
                it.name.startsWith("get") &&
                    it.parameterCount == 0 &&
                    it.returnType == java.lang.Long.TYPE
            }
            .associate { method ->
                val name = method.name.removePrefix("get").substringBefore('-')
                    .replaceFirstChar { it.lowercase() }
                name to Color((method.invoke(scheme) as Long).toULong())
            }

    private fun typeSlots(typo: Typography): Map<String, TextStyle> =
        Typography::class.java.methods
            .filter {
                it.name.startsWith("get") &&
                    it.parameterCount == 0 &&
                    it.returnType == TextStyle::class.java
            }
            .associate { method ->
                val name = method.name.removePrefix("get").substringBefore('-')
                    .replaceFirstChar { it.lowercase() }
                name to method.invoke(typo) as TextStyle
            }

    private fun contrast(foreground: Color, background: Color): Float {
        val hi = maxOf(foreground.luminance(), background.luminance())
        val lo = minOf(foreground.luminance(), background.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }

    companion object {
        /** material3 1.3.1：ColorScheme 的 36 个颜色槽位 */
        private const val EXPECTED_SLOT_COUNT = 36

        /** material3 1.3.1：Typography 的 15 级排版槽位 */
        private const val EXPECTED_TYPOGRAPHY_SLOT_COUNT = 15

        /**
         * 「与 baseline 同值但不算漏写」的两块红：Material 3 规范给深色 error 族定的就是
         * #601410 / #8C1D18，而 material3 1.3.1 的 `darkColorScheme()` 默认值与规范同数。
         * Theme.kt 里这两格是**显式写过**的（onError = DarkOnError / errorContainer = DarkErrorContainer），
         * 所以按值放行。
         *
         * 按值而不是按槽位名：浅色的 onError 与它的 baseline 并不同值，
         * 按名豁免会顺手把那种真实漏写一起放过；而这两块具体的红，别的槽位撞上就等于回到 error 族。
         */
        private val SPEC_BASELINE_IDENTICAL = setOf(DarkOnError, DarkErrorContainer)

        private val GREEN_SEEDS = listOf(0xFF2E7D32.toInt(), 0xFF1B5E20.toInt())
    }
}
