package com.buaa.schedule.core.designsystem.liquid

import androidx.compose.ui.graphics.Color
import com.buaa.schedule.core.designsystem.BlueOnSurfaceVariant
import com.buaa.schedule.core.designsystem.DarkBlueOnSurfaceVariant
import com.buaa.schedule.core.designsystem.DarkGlassTint
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.core.designsystem.LightGlassTint
import com.buaa.schedule.core.designsystem.SceneLuma
import com.buaa.schedule.core.designsystem.legibilityAlphaFloor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [bottomBarSurfaceAlpha]：底栏栏体表面 alpha 的值域闸门。
 *
 * 改前那段内联实现（`LiquidBottomTabs` 里）的毛病在夹取的**顺序**：
 * `.coerceAtMost(0.60).coerceAtLeast(floor)` 等于"下限比天花板高时天花板不作数"——
 * 深色档在极白壁纸块下能把 [legibilityAlphaFloor] 顶到 0.920，底栏在那种场景里
 * 实际一直以 0.92 上下的实心条绘制，0.60 的天花板在深色场景从来没生效过。
 * [legibilityAlphaFloor] 自己的文档写着它的值域上界是 1.0、调用方"要先跟自己的
 * 天花板取小再喂给 coerceIn"（与 T22 修过一次的那条 `coerceIn` 空区间崩溃同一族账），
 * `glassSurfaceAlpha` 就照这个约定写。下面的守卫钉的正是这条顺序。
 *
 * 场景亮度靠 [SceneLuma] 驱动（风格同 `GlassSurfaceAlphaTest`）：
 * Unknown = 没有真壁纸，走内置渐变常数。板色/字色取 MainActivity 底栏调用点
 * 实际配对的那两支（DarkGlassTint+DarkBlueOnSurfaceVariant / LightGlassTint+BlueOnSurfaceVariant）。
 */
class BottomBarSurfaceAlphaTest {

    @After
    fun restoreScene() {
        SceneLuma.wallpaper = SceneLuma.Stats.Unknown
    }

    /**
     * 钉顺序的本体：下限越过天花板时，结果停在天花板，而不是被下限无声顶穿。
     * 改前（先 coerceAtMost 再 coerceAtLeast）这里算出 ≈0.920，断言应红；
     * 改后（下限先与天花板取小、再 coerceIn）恒等于 0.60。
     */
    @Test
    fun legibilityFloorAboveCeilingStopsAtTheCeilingInsteadOfPunchingThrough() {
        // 深色档 × 极白壁纸块：暗板 0.0081 配浅色正文 0.5681，反解下限 0.920 > 0.60
        SceneLuma.wallpaper = SceneLuma.Stats(mean = 0.45f, darkest = 0.02f, brightest = 1.0f)
        val floor = legibilityAlphaFloor(DarkGlassTint, DarkBlueOnSurfaceVariant, darkTheme = true)
        assertTrue(
            "白壁纸块场景本该把下限顶到天花板之上，实测 floor=$floor —— 靶子失效了",
            floor > BOTTOM_BAR_SURFACE_ALPHA_CEILING,
        )
        for (cardAlpha in listOf(0.3f, 0.88f, 1.0f)) {
            val alpha = bottomBarSurfaceAlpha(
                containerAlpha = DesignTokens.CHROME_SURFACE_ALPHA,
                userAlphaScale = DesignTokens.cardAlphaScale(cardAlpha),
                containerColor = DarkGlassTint,
                text = DarkBlueOnSurfaceVariant,
                darkTheme = true,
            )
            assertEquals(
                "cardAlpha=$cardAlpha：floor=$floor 越顶时应走满天花板 0.60，而不是顶穿它",
                BOTTOM_BAR_SURFACE_ALPHA_CEILING, alpha, 0f,
            )
        }
    }

    /**
     * 全族扫一遍：深浅主题 × 三档 cardAlpha × 壁纸极值 × 灰阶文字网格（动态取色与
     * 换主题动画途中的字色都从 onSurfaceVariant 上经过），结果恒落在
     * [下限∧天花板, 天花板]，任何组合都不许产出一个 > 0.60 的 alpha。
     */
    @Test
    fun everyConfigurationStaysBetweenFloorAndCeiling() {
        val scenes = listOf(
            SceneLuma.Stats.Unknown,
            SceneLuma.Stats(mean = 0.45f, darkest = 0.02f, brightest = 1.0f), // 深色档最不利：极白块
            SceneLuma.Stats(mean = 0.35f, darkest = 0.0f, brightest = 0.20f), // 浅色档最不利：极黑块
            SceneLuma.Stats(mean = 0.50f, darkest = 0.50f, brightest = 0.50f), // 均匀中灰
        )
        val cardAlphas = listOf(0.3f, 0.88f, 1.0f) // 滑条定义域 0.3..1 的两端 + 默认档
        val texts = (0..10).map { Color(it / 10f, it / 10f, it / 10f) } +
            listOf(DarkBlueOnSurfaceVariant, BlueOnSurfaceVariant)
        var checked = 0
        for (scene in scenes) {
            SceneLuma.wallpaper = scene
            for (cardAlpha in cardAlphas) {
                for ((dark, plate) in listOf(true to DarkGlassTint, false to LightGlassTint)) {
                    for (text in texts) {
                        val floor = legibilityAlphaFloor(plate, text, darkTheme = dark)
                        val alpha = bottomBarSurfaceAlpha(
                            containerAlpha = DesignTokens.CHROME_SURFACE_ALPHA,
                            userAlphaScale = DesignTokens.cardAlphaScale(cardAlpha),
                            containerColor = plate,
                            text = text,
                            darkTheme = dark,
                        )
                        val ceiling = BOTTOM_BAR_SURFACE_ALPHA_CEILING
                        assertTrue(
                            "壁纸=$scene cardAlpha=$cardAlpha 暗档=$dark 字=$text floor=$floor → $alpha 越过天花板",
                            alpha <= ceiling,
                        )
                        assertTrue(
                            "壁纸=$scene cardAlpha=$cardAlpha 暗档=$dark 字=$text floor=$floor → $alpha 低于下限∧天花板",
                            alpha >= floor.coerceAtMost(ceiling),
                        )
                        checked++
                    }
                }
            }
        }
        assertEquals("参数化测试退化：组合数没对上", 4 * 3 * 2 * 13, checked)
    }

    /**
     * 安全基线：下限没顶到天花板的场景（正常观感的大多数）改前改后一个 number 都不动。
     * 这条防"修顺序顺手把通透感改了"——期望值全部按现网口径手推（见行内注释）。
     */
    @Test
    fun scenesAtOrBelowTheCeilingKeepTheirExactAlpha() {
        val cases = listOf(
            // 内置渐变（深浅两档 floor 都落满绝对下限 0.08）：raw 直接透出
            Case("内置渐变", SceneLuma.Stats.Unknown, true, 0.88f, 0.20f), // raw = 0.20×1.0
            Case("内置渐变", SceneLuma.Stats.Unknown, true, 0.3f, 0.08f), // raw=0.068 被 0.08 抬住
            Case("内置渐变", SceneLuma.Stats.Unknown, true, 1.0f, 0.2273f),
            Case("内置渐变", SceneLuma.Stats.Unknown, false, 0.88f, 0.20f),
            Case("内置渐变", SceneLuma.Stats.Unknown, false, 0.3f, 0.08f),
            Case("内置渐变", SceneLuma.Stats.Unknown, false, 1.0f, 0.2273f),
            // 深色档亮块到 0.20：floor=0.587 仍 ≤ 0.60，两种顺序在此同值
            Case("亮块 0.20", SceneLuma.Stats(0.45f, 0.02f, 0.20f), true, 0.88f, 0.5869f),
            Case("亮块 0.20", SceneLuma.Stats(0.45f, 0.02f, 0.20f), true, 1.0f, 0.5869f),
            // 浅色档黑块：floor=0.507 < 0.60，同样不受顺序影响
            Case("黑块 0.00", SceneLuma.Stats(0.35f, 0.0f, 0.20f), false, 0.88f, 0.5074f),
            Case("黑块 0.00", SceneLuma.Stats(0.35f, 0.0f, 0.20f), false, 1.0f, 0.5074f),
        )
        for (case in cases) {
            SceneLuma.wallpaper = case.scene
            val alpha = bottomBarSurfaceAlpha(
                containerAlpha = DesignTokens.CHROME_SURFACE_ALPHA,
                userAlphaScale = DesignTokens.cardAlphaScale(case.cardAlpha),
                containerColor = if (case.dark) DarkGlassTint else LightGlassTint,
                text = if (case.dark) DarkBlueOnSurfaceVariant else BlueOnSurfaceVariant,
                darkTheme = case.dark,
            )
            assertEquals(
                "${case.label} 暗档=${case.dark} cardAlpha=${case.cardAlpha}：正常场景的观感不该被这次改动碰到",
                case.expected, alpha, 0.001f,
            )
        }
    }

    private class Case(
        val label: String,
        /** 实测壁纸分块极值；Unknown = 没有真壁纸，场景是内置渐变 */
        val scene: SceneLuma.Stats,
        val dark: Boolean,
        val cardAlpha: Float,
        val expected: Float,
    )
}
