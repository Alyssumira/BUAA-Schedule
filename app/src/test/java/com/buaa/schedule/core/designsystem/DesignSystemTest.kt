package com.buaa.schedule.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
        // 紫：亮度 0.182，白字 4.5:1、近黑只有 3.8:1
        val purple = Color(0xFF9B51E0)
        assertEquals(Color.White, contentOn(purple))
    }

    @Test
    fun midToneCourseColorUsesDarkTextNotWhite() {
        // 旧口径按亮度 0.45 切黑白，把 0.203~0.45 一整段判给了白字。
        // #5B8DEF 亮度 0.275：白字 3.2:1（不达 AA），近黑 5.3:1。
        assertEquals(Color(0xFF1A1B20), contentOn(Color(0xFF5B8DEF)))
        // 真正的交点在 0.203：亮度 0.228 的绿仍该用近黑
        // （一颗刚好落在交点上方的构造色，不是调色板成员——调色板的绿是 #27AE60，亮度 0.315）
        assertEquals(Color(0xFF1A1B20), contentOn(Color(0xFF219653)))
        // 交点下方该用白字
        assertEquals(Color.White, contentOn(Color(0xFF2D2D2D)))
    }

    @Test
    fun pureWhiteAndBlackTextColors() {
        assertEquals(Color(0xFF1A1B20), contentOn(Color.White))
        assertEquals(Color.White, contentOn(Color.Black))
    }

    @Test
    fun materialIsFixedByVariantAlone() {
        // ②V-14：档位调到材质里的那条路已删除——能到得了的档位只有开/关，
        // 关档由 surfaceUsesGlass 决定"要不要玻璃"，而不是把同一块玻璃调得更折。
        // 用值相等把映射表钉住：谁再往 glassMaterial 塞回档位分支，这里先炸。
        assertEquals(
            LiquidGlassMaterial.pill(DesignTokens.CHROME_BASE_INTENSITY),
            DesignTokens.glassMaterial(GlassVariant.CHROME),
        )
        assertEquals(LiquidGlassMaterial.pill(), DesignTokens.glassMaterial(GlassVariant.COMPACT))
        val dialog = LiquidGlassMaterial.dialog()
        // 反馈：大卡片要"透明 + 可调高斯模糊"，PANEL 已经不是 dialog 材质了
        assertEquals(
            DesignTokens.panelMaterial(Personalization.DEFAULT_PANEL_BLUR_DP),
            DesignTokens.glassMaterial(GlassVariant.PANEL),
        )
        assertEquals(dialog, DesignTokens.glassMaterial(GlassVariant.ALERT))
    }

    @Test
    fun panelMaterialIsFrostedNotRefracted() {
        val panel = DesignTokens.glassMaterial(GlassVariant.PANEL)
        // 折射是"一摞玻璃板"那种观感的来源，面板这一档必须彻底关掉
        assertEquals(0.dp, panel.lensHeight)
        assertEquals(0.dp, panel.lensAmount)
        // tint 要比 dialog 透，否则模糊再强也还是一块实心灰板
        assertTrue(panel.surfaceAlpha < LiquidGlassMaterial.dialog().surfaceAlpha)
        // 中性磨砂：vibrancy 会跟着壁纸颜色晃正文，底板这么薄时不能开
        assertFalse(panel.useVibrancy)
        // 提示条用的还是 dialog：错误提示不能被糊成背景的一部分
        assertTrue(
            DesignTokens.glassMaterial(GlassVariant.ALERT).surfaceAlpha > panel.surfaceAlpha,
        )
    }

    @Test
    fun panelBlurFollowsTheSliderAndStaysInRange() {
        assertEquals(
            9.dp,
            DesignTokens.panelMaterial(9f).blur,
        )
        // 滑杆定义域之外的值（脏数据、旧版本存下的数）夹回边界，不给负半径的机会
        assertEquals(
            Personalization.MIN_PANEL_BLUR_DP.dp,
            DesignTokens.panelMaterial(-5f).blur,
        )
        assertTrue(
            DesignTokens.panelMaterial(999f).blur == Personalization.MAX_PANEL_BLUR_DP.dp,
        )
        // 0 = 只剩一层薄 tint：blur 归零后 effect 里就不再挂高斯模糊
        assertEquals(0.dp, DesignTokens.panelMaterial(0f).blur)
    }

    @Test
    fun chromeVariantIsStrongerThanCompact() {
        val chrome = DesignTokens.glassMaterial(GlassVariant.CHROME)
        val compact = DesignTokens.glassMaterial(GlassVariant.COMPACT)
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

    @Test
    fun coursePlateIsReadableAcrossEveryColorAndWallpaper() {
        // 自定义色由取色器任意挑，所以扫一片网格；灰阶是重灾区（中间亮度黑白都嫌不够）
        val tints = CourseColors + (0..10).map { v ->
            val channel = (v * 25 + 12).coerceAtMost(255)
            Color(channel / 255f, channel / 255f, channel / 255f)
        } + listOf(
            Color(0xFFF2994A), Color(0xFF00B8D4), Color(0xFFE0E0E0), Color(0xFF3B0764),
        )
        // 关玻璃时恒为 0.92；开玻璃时 tint 在 0.26~0.68 之间随滑条线性变化
        val alphas = listOf(0.26f, 0.47f, 0.68f, 0.92f)
        var checked = 0
        for (tint in tints) for (scene in listOf(0.03f, 0.50f, 0.83f)) for (alpha in alphas) {
            val plate = legibleTintPlate(tint, alpha, scene)
            val plateLuma = composite(plate.tint.readableLuminance(), scene, plate.alpha)
            val primary = contrastRatio(plateLuma, plate.foreground.readableLuminance())
            assertTrue(
                "课程色 $tint 叠在亮度 $scene 上（alpha=$alpha）主文字只有 $primary",
                primary >= DesignTokens.WCAG_AA_RATIO - 0.02f,
            )
            // 次级文字自带 alpha，要按叠完的亮度算
            val subtle = plate.secondaryForeground
            val subtleLuma = subtle.readableLuminance() * subtle.alpha + plateLuma * (1f - subtle.alpha)
            val secondary = contrastRatio(plateLuma, subtleLuma)
            assertTrue(
                "同组次级文字只有 $secondary（alpha=${subtle.alpha}）",
                secondary >= DesignTokens.WCAG_AA_RATIO - 0.02f,
            )
            checked++
        }
        assertTrue("参数化测试退化：只检查了 $checked 组", checked == tints.size * 3 * alphas.size)
    }

    @Test
    fun legibleTintPlateDoesNotOverCorrect() {
        // 反解不许白拿观感：本来就读得清时，玻璃该多透就多透，课程色一个字都不许改。
        // 深绿叠暗壁纸、滑条拖到最透的一档——白字 6.8:1，没有任何理由压实。
        val green = Color(0xFF27AE60)
        val plate = legibleTintPlate(green, 0.26f, 0.03f)
        assertEquals(0.26f, plate.alpha, 0.0001f)
        assertEquals(green, plate.tint)
        // 只有落到"黑白都读不出"的中间亮度带（实测 0.183~0.225）才允许动颜色
        val midGray = Color(0xFF7C7C7C)
        assertTrue(
            "亮度 ${midGray.readableLuminance()} 本该触发压色兜底",
            legibleTintPlate(midGray, 1f, 0.03f).tint != midGray,
        )
    }

    private fun composite(surfaceLuma: Float, sceneLuma: Float, alpha: Float): Float =
        surfaceLuma * alpha + sceneLuma * (1f - alpha)

    private fun contrastRatio(lumaA: Float, lumaB: Float): Float {
        val hi = maxOf(lumaA, lumaB)
        val lo = minOf(lumaA, lumaB)
        return (hi + 0.05f) / (lo + 0.05f)
    }
}
