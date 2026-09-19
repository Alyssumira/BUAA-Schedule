package com.buaa.schedule.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
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

    /**
     * 下限的"承诺"必须兑现在**生产那条混合式**上：拿 [compositeLuma] 而不是测试自己抄的一份
     * 线性混合——否则改了生产口径而这里还绿着，就是判据在替一个已经不存在的模型背书。
     * 穷尽版（紧不紧、退化格、NaN）见 [glassAlphaFloorInvertsCompositeLumaOnEveryPlateSceneTextCell]。
     */
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
            val ratio = contrastRatio(compositeLuma(plate, scene, floor), text)
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
        val tints = coursePlateTintGrid()
        // 关玻璃时恒为 0.92；开玻璃时 tint 在 0.26~0.68 之间随滑条线性变化
        val alphas = COURSE_PLATE_ALPHAS
        var checked = 0
        for (tint in tints) for (scene in COURSE_PLATE_SCENES) for (alpha in alphas) {
            val plate = legibleTintPlate(tint, alpha, scene)
            val plateLuma = compositeLuma(plate.tint.readableLuminance(), scene, plate.alpha)
            val primary = contrastRatio(plateLuma, plate.foreground.readableLuminance())
            assertTrue(
                "课程色 $tint 叠在亮度 $scene 上（alpha=$alpha）主文字只有 $primary",
                primary >= DesignTokens.WCAG_AA_RATIO - 0.02f,
            )
            // 次级文字自带 alpha，要按叠完的亮度算——叠的口径仍是生产那一条 compositeLuma，
            // 不再在测试里手写第二份混合式（那正是本卡拆掉的东西）。
            val subtle = plate.secondaryForeground
            val subtleLuma = compositeLuma(subtle.readableLuminance(), plateLuma, subtle.alpha)
            val secondary = contrastRatio(plateLuma, subtleLuma)
            assertTrue(
                "同组次级文字只有 $secondary（alpha=${subtle.alpha}）",
                secondary >= DesignTokens.WCAG_AA_RATIO - 0.02f,
            )
            checked++
        }
        assertTrue("参数化测试退化：只检查了 $checked 组", checked == tints.size * 3 * alphas.size)
    }

    /**
     * 这片网格的**经济**判据，与上一条的可读判据正交：不许出现"另一支墨明明更省 alpha，
     * 却选了贵的"。[contentOnLuma] 只看初始板，而 [DesignTokens.glassAlphaFloor] 抬完 alpha
     * 之后板已经挪位——先定墨再抬价的走法在暗部尤其容易选错（近黑压近黑本来就无解，
     * 解出的下限饱和到 1.0，白墨那一支却只要一半的 alpha）。
     *
     * 判据用的是生产那把尺 [alphaNeededByInk]（两支候选墨量同一个量才叫"谁更省"），
     * 比的是**所选墨所需**与**另一支所需**：这条只钉选择，改回"先 contentOnLuma 再抬 alpha"
     * 立刻炸（实测 276 格里 4 格选贵了）。
     */
    @Test
    fun legibleTintPlateNeverPicksTheInkThatCostsMoreAlpha() {
        val otherInk = mapOf(ContentLight to ContentDark, ContentDark to ContentLight)
        val pricier = mutableListOf<String>()
        var checked = 0
        var ties = 0
        for (tint in coursePlateTintGrid()) for (scene in COURSE_PLATE_SCENES) for (alpha in COURSE_PLATE_ALPHAS) {
            val plate = legibleTintPlate(tint, alpha, scene)
            val chosen = plate.foreground
            val other = otherInk.getValue(chosen)
            val tintLuma = tint.readableLuminance()
            val chosenNeeds = alphaNeededByInk(tintLuma, scene, alpha, chosen)
            val otherNeeds = alphaNeededByInk(tintLuma, scene, alpha, other)
            if (chosenNeeds > otherNeeds + NEEDED_ALPHA_TOLERANCE) {
                pricier += "$tint x $scene x $alpha：选 $chosen 要 $chosenNeeds，更省的 $other 只要 $otherNeeds"
            }
            if (abs(chosenNeeds - otherNeeds) <= NEEDED_ALPHA_TOLERANCE) ties++
            checked++
        }
        assertTrue("参数化测试退化：只检查了 $checked 组", checked > 200)
        // 一次报全：撞第一格就停的话，就没人知道这片网格到底错了多少格
        assertTrue(
            "${pricier.size}/$checked 格选到了更贵的那支墨（选墨该跟压实同解，不许先定墨再抬价）：\n" +
                pricier.take(8).joinToString("\n"),
            pricier.isEmpty(),
        )
        // 等档格（两支墨一样省）由 contentOnLuma 的偏好收掉，也得有覆盖，否则这条只在少数格上说话
        assertTrue("没有任何一格两支墨同档（$ties）：比较口径退化成单选", ties > 0)

        // 报修那一格：灰 12/255（亮度 0.047）以 0.47 叠在亮度 0.83 的场景上。
        // 先定墨的走法判给近黑 → 无解 → alpha 顶到 1.0 → 板 0.0037、对比度 1.14；
        // 同解之后该落白墨、alpha ≈0.52、恰好 4.5:1。
        val gray12 = Color(12 / 255f, 12 / 255f, 12 / 255f)
        val fixed = legibleTintPlate(gray12, 0.47f, 0.83f)
        val fixedPlateLuma = compositeLuma(fixed.tint.readableLuminance(), 0.83f, fixed.alpha)
        val fixedRatio = contrastRatio(fixedPlateLuma, fixed.foreground.readableLuminance())
        assertEquals("这一格该换白墨", ContentLight, fixed.foreground)
        assertTrue("白墨只要 ≈0.52，解到 ${fixed.alpha} 说明还在追无解的近黑", fixed.alpha < 0.9f)
        assertTrue("同解之后这一格的主文字只有 $fixedRatio", fixedRatio >= DesignTokens.WCAG_AA_RATIO)
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

    /**
     * 下限与复合亮度是**同一件事的两面**：把 [DesignTokens.glassAlphaFloor] 解出的 alpha
     * 喂回 [compositeLuma]，对比度必须正好落在它承诺的那条 AA 线上——两个方向都要成立
     * （暗板怕亮斑、亮板怕暗斑），退化格（板与场景同亮度）与 NaN 格各留一条说法。
     *
     * 这条钉的是"两个函数不许各自漂移"：改了 [compositeLuma] 的混合维度而忘了改反解，
     * 或者反过来，这里立刻炸（实测：只改一边时 960 格里 238 格对不上）。
     * 它**不**问"哪一维才是平台真正混合的那一维"——那是下面两条物理往返的事。
     */
    @Test
    fun glassAlphaFloorInvertsCompositeLumaOnEveryPlateSceneTextCell() {
        val plates = listOf(0.008f, 0.03f, 0.06f, 0.30f, 0.60f, 0.90f)
        val scenes = listOf(0.01f, 0.05f, 0.13f, 0.30f, 0.50f, 0.70f, 0.90f)
        val texts = listOf(0.011f, 0.06f, 0.25f, 0.55f, 0.77f, 0.95f)
        var checked = 0
        var darkPlates = 0
        var brightPlates = 0
        var exactlyOnTheLine = 0
        for (plate in plates) for (scene in scenes) for (text in texts) {
            // 板与字之间本来就没对比：任何 alpha 都救不了，那是调用方选错了 tint
            if (contrastRatio(plate, text) < DesignTokens.WCAG_AA_RATIO) continue
            if (plate < text) darkPlates++ else brightPlates++
            val floor = DesignTokens.glassAlphaFloor(plate, scene, text)
            val ratio = contrastRatio(compositeLuma(plate, scene, floor), text)
            assertTrue(
                "板=$plate 景=$scene 字=$text → 下限 $floor 处复合亮度只有 $ratio，反解没跟着混合式走",
                ratio >= DesignTokens.WCAG_AA_RATIO - TOLERANT_RATIO,
            )
            // 反解是**紧**的：往下松一格就不达标（被硬底/饱和夹住的那些格除外）
            if (floor > DesignTokens.GLASS_HARD_MIN_ALPHA + SLACK_ALPHA && floor < 1f) {
                val looser = contrastRatio(compositeLuma(plate, scene, floor - SLACK_ALPHA), text)
                assertTrue(
                    "板=$plate 景=$scene 字=$text：下限 $floor 松一格仍有 $looser，白拿了通透感",
                    looser < DesignTokens.WCAG_AA_RATIO,
                )
                if (abs(ratio - DesignTokens.WCAG_AA_RATIO) < 0.01f) exactlyOnTheLine++
            }
            checked++
        }
        assertTrue("属性测试退化：只检查了 $checked 组", checked > 60)
        assertTrue("暗板方向一格没测（$darkPlates）：反解的另一支失去覆盖", darkPlates > 0)
        assertTrue("亮板方向一格没测（$brightPlates）：反解的另一支失去覆盖", brightPlates > 0)
        assertTrue("没有任何一格恰好压在 AA 线上（$exactlyOnTheLine）：解算退成了夹取", exactlyOnTheLine > 0)

        // 退化格：板与场景同亮度时 alpha 不作数，下限退到绝对硬底
        val sameTone = DesignTokens.glassAlphaFloor(0.30f, 0.30f, 0.95f)
        assertEquals("板与场景同亮度时该退到硬底", DesignTokens.GLASS_HARD_MIN_ALPHA, sameTone, 0.001f)
        assertTrue(
            "板景同亮度时 alpha 竟能动亮度：${compositeLuma(0.30f, 0.30f, 1f)} vs ${compositeLuma(0.30f, 0.30f, 0f)}",
            abs(compositeLuma(0.30f, 0.30f, 1f) - compositeLuma(0.30f, 0.30f, 0f)) < 0.001f,
        )
        // NaN 格：场景未知时两头都退回"不假装知道"——下限给硬底，复合给 NaN，谁都不许抛
        assertEquals(
            DesignTokens.GLASS_HARD_MIN_ALPHA,
            DesignTokens.glassAlphaFloor(0.90f, Float.NaN, 0.011f),
            0.001f,
        )
        assertTrue(compositeLuma(0.90f, Float.NaN, 0.5f).isNaN())
        assertTrue(compositeLuma(Float.NaN, 0.5f, 0.5f).isNaN())
    }

    /**
     * 上面那条只保证"自己跟自己一致"。这一条问的是**平台画下去的那片像素**：
     * 中性灰 tint 以解出的下限叠在中性灰场景上，Skia 逐通道插值出来的那块板，
     * 亮度必须真的让文字读到 AA。
     *
     * 为什么用中性灰：灰的三条通道相同，而亮度权重 0.2126+0.7152+0.0722 = 1，
     * 于是"灰的相对亮度"与"灰的编码通道值"互为解析反函数——**混合发生在哪一维**
     * 这件事在中性灰上没有代理误差，模型说什么就是什么。深色档玻璃板叠在白壁纸上
     * 那格实测差到 2.36 倍（见 [GlassPlateDeviceCalibrationTest]），错的正是这一维。
     *
     * 界为什么写成"AA − 浮点容差 − [frameBufferSlack]"而不是干脆 ≥ AA：下限是**反解**出来的，
     * 它把模型板正好推到 4.5:1 那一格上，而混合出来的通道值只能存成 8 bit 整数格
     * （`灰 0 × 灰 136 @ 0.1275` 要的是 118.656，帧缓冲里躺着的是 119）。差半格在交点附近就值
     * 0.02 档，"正好压线"这种断言在物理上不可能成立。预算按格现算（见 [frameBufferSlack]），
     * 上限一整个 1/255，最多 0.03 档；而混合维度错了是 0.23 亮度、两三档的量级——
     * 这道预算连它的零头都盖不住（按旧口径重算这 960 格，越界的仍有 234 格）。
     */
    @Test
    fun glassAlphaFloorDeliversAAOnThePlateThePlatformActuallyRenders() {
        var checked = 0
        var unfixable = 0
        var darkPlates = 0
        var brightPlates = 0
        var nearLine = 0
        for (tintStep in GRAY_STEPS) for (sceneStep in GRAY_STEPS) for (text in INK_LUMAS) {
            val plate = gray(tintStep).readableLuminance()
            val scene = gray(sceneStep).readableLuminance()
            if (contrastRatio(plate, text) < DesignTokens.WCAG_AA_RATIO) {
                unfixable++
                continue
            }
            if (plate < text) darkPlates++ else brightPlates++
            val floor = DesignTokens.glassAlphaFloor(plate, scene, text)
            val rendered = renderedGrayPlate(tintStep, sceneStep, floor)
            val ratio = contrastRatio(rendered, text)
            val slack = frameBufferSlack(tintStep, sceneStep, floor, rendered, text)
            assertTrue(
                "灰 $tintStep 以解出的下限 $floor 叠在灰 $sceneStep 上：模型板 ${compositeLuma(plate, scene, floor)}，" +
                    "真实板 $rendered → 文字只有 $ratio（AA 减浮点容差再减一格帧缓冲预算 $slack）",
                ratio >= DesignTokens.WCAG_AA_RATIO - TOLERANT_RATIO - slack,
            )
            if (ratio < DesignTokens.WCAG_AA_RATIO + NEAR_LINE_BAND) {
                nearLine++
                // 一格量化在交点附近值 0.064 档。它要是连"贴着 AA 线"这条带子本身都装不下，
                // 那它记的就不是量化，而是能在界上随便挑地方填的数了。
                assertTrue(
                    "灰 $tintStep 叠在灰 $sceneStep 上（$ratio）：一格预算 $slack 超过了近线带本身",
                    slack < NEAR_LINE_BAND,
                )
            }
            checked++
        }
        assertTrue("物理往返只跑了 $checked 格：网格被跳过条件吃光了", checked > 900)
        assertTrue("没有'任何 alpha 都救不了'的格（$unfixable）：跳过条件形同虚设", unfixable > 0)
        assertTrue("暗板方向零格（$darkPlates）", darkPlates > 0)
        assertTrue("亮板方向零格（$brightPlates）", brightPlates > 0)
        // 预算本身也得有界：交点附近一格 1/255 值 0.064 档（实测最大值，见下面这条），
        // 而混合维度错了是 0.23 亮度、两档起步。只约束贴着 AA 线的那批格——板本来就到
        // 15:1 时一格量化就值 0.2 档，那与"读得清"根本不是一个尺度，也不该由这条管。
        assertTrue("一格都没压在 AA 线附近（$nearLine）：这条预算闸门是空的", nearLine > 0)
    }

    /**
     * 反过来的那一半：下限**不许比真实需要更实**。松一格（[SLACK_ALPHA]）就应当掉出 AA，
     * 否则这条下限是在白拿通透感——用户看到的玻璃板比必要的更闷。
     *
     * 旧的线性口径正是这一条的靶子：暗板叠亮景时模型把板算得偏亮，于是多要了一档 alpha，
     * 真实板松一格仍然稳稳读得清（实测最大仍有 10.9:1）。
     */
    @Test
    fun theSolvedFloorIsNeverPackedTighterThanTheRenderedPlateNeeds() {
        var checked = 0
        for (tintStep in GRAY_STEPS) for (sceneStep in GRAY_STEPS) for (text in INK_LUMAS) {
            val plate = gray(tintStep).readableLuminance()
            val scene = gray(sceneStep).readableLuminance()
            if (contrastRatio(plate, text) < DesignTokens.WCAG_AA_RATIO) continue
            val floor = DesignTokens.glassAlphaFloor(plate, scene, text)
            // 被硬底托住 / 已经饱和到 1 的格子谈不上"最小"：那两处夹取是设计口径
            if (floor <= DesignTokens.GLASS_HARD_MIN_ALPHA + SLACK_ALPHA || floor >= 1f) continue
            val looser = contrastRatio(renderedGrayPlate(tintStep, sceneStep, floor - SLACK_ALPHA), text)
            assertTrue(
                "灰 $tintStep 叠在灰 $sceneStep 上：下限 $floor 松一格后真实板仍有 $looser，多要了 alpha",
                looser < DesignTokens.WCAG_AA_RATIO,
            )
            checked++
        }
        assertTrue("一格都没比成：全网格的下限都被夹取口径挡住了（$checked）", checked > 100)
    }

    /**
     * 次级文字（[TintPlate.secondaryForeground]）的 alpha 也是同一条反解的产物，
     * 所以它必须在**真实渲染出来的板**上读到 AA：把墨按自己的 alpha 叠到板上，逐通道算完再量。
     *
     * 走 [legibleTintPlate] 的 alpha=1 那一档，是为了让板亮度恰好等于板色本身
     * （不必在测试里重算一遍复合），场景参数在这条路上不起作用。
     */
    @Test
    fun theSubtleInkReachesAAOnTheRenderedPlate() {
        var checked = 0
        var lightInk = 0
        var darkInk = 0
        for (step in GRAY_STEPS) {
            val plate = legibleTintPlate(gray(step), 1f, 0.5f)
            val subtle = plate.secondaryForeground
            val plateLuma = plate.tint.readableLuminance()
            if (plate.foreground == ContentLight) lightInk++ else darkInk++
            val rendered = renderInkOnPlate(subtle, neutralPlateChannel(plate))
            val ratio = contrastRatio(rendered, plateLuma)
            assertTrue(
                "灰 $step 的板（$plateLuma）上次级墨 alpha=${subtle.alpha} 叠出来只有 $ratio",
                ratio >= DesignTokens.WCAG_AA_RATIO - TOLERANT_RATIO,
            )
            checked++
        }
        assertEquals("参数化退化：灰阶扫描应为 ${GRAY_STEPS.size} 格", GRAY_STEPS.size, checked)
        assertTrue("两支候选墨里有一支零覆盖：浅墨=$lightInk 暗墨=$darkInk", darkInk > 0 && lightInk > 0)
    }

    /**
     * 次级墨不许比"读得清"更实：少给一格 1/255 就该落回不达标。
     *
     * 这一条管的是层次——写死 alpha 与多要 alpha 是同一种病的两面：字越实，
     * 主次层级越平，12sp 的节次行会跟正文抢。留 [ONE_STEP_SLACK] 档宽容差是因为
     * alpha 向上取整到 1/255（见 [legibleTintPlate] 那条补偿），退一格可能恰好退回解本身。
     */
    @Test
    fun theSubtleInkIsNotPackedBeyondWhatTheRenderedPlateNeeds() {
        val quantisedHardMin = ceil(MIN_SUBTLE_ALPHA * 255f) / 255f
        var checked = 0
        for (step in GRAY_STEPS) {
            val plate = legibleTintPlate(gray(step), 1f, 0.5f)
            val subtle = plate.secondaryForeground
            if (subtle.alpha <= quantisedHardMin + 1e-6f || subtle.alpha >= 1f) continue
            val lower = renderInkOnPlate(subtle.copy(alpha = subtle.alpha - 1f / 255f), neutralPlateChannel(plate))
            val ratio = contrastRatio(lower, plate.tint.readableLuminance())
            assertTrue(
                "灰 $step 的次级墨 alpha=${subtle.alpha} 少一格仍有 $ratio：虚化被多扣了一档",
                ratio < DesignTokens.WCAG_AA_RATIO + ONE_STEP_SLACK,
            )
            checked++
        }
        assertTrue("一格都没比成：次级墨恒被硬底/不透明夹住（$checked）", checked > 5)
    }

    /**
     * 保住"向上取整到 1/255"那格补偿：Color 每个通道按 8 bit 存，alpha 会被截到最近的
     * 1/255，向下截半档就把"刚好 4.5:1"变成实测 4.47:1。搬维度时这行最容易顺手丢掉。
     */
    @Test
    fun theSubtleInkStaysOnTheOneTwoFiveFifthAlphaGrid() {
        val quantisedHardMin = ceil(MIN_SUBTLE_ALPHA * 255f) / 255f
        for (step in GRAY_STEPS) {
            val plate = legibleTintPlate(gray(step), 1f, 0.5f)
            val alpha = plate.secondaryForeground.alpha
            val scaled = alpha * 255f
            assertTrue(
                "灰 $step 的次级墨 alpha=$alpha 不在 1/255 网格上（画下去会被截到 ${scaled.toInt()}/255）",
                abs(scaled - scaled.roundToInt()) < 0.01f,
            )
            assertTrue("次级墨突破了硬底：$alpha", alpha >= quantisedHardMin - 1e-6f)
            assertTrue("次级墨超过了不透明：$alpha", alpha <= 1f)
        }
    }

    /**
     * 形状守卫：次级墨**不许再自己解一遍复合亮度**。
     *
     * 数值口径只有一份这件事，光靠注释守不住——[dimForeground] 与
     * [DesignTokens.glassAlphaFloor] 的解长得很像（都是"往极限值插值再除跨度"），
     * 抄一份出去就等于给下一次维度搬家留下第二处要改的地方（两处各改一半就是这一族 bug 的成因）。
     * 这里按源码文本钉住三件事：调了那一条反解、没再出现 WCAG 常量的第二次展开、
     * 1/255 那格补偿还在。
     */
    @Test
    fun theSubtleInkReusesTheSharedFloorInsteadOfResolvingItAgain() {
        val body = bodyOfTopLevelFun(colorSource(), "dimForeground")
        assertTrue(
            "次级墨没有复用同一条反解（glassAlphaFloor），而是自己解了一遍：\n$body",
            body.contains("glassAlphaFloor"),
        )
        assertTrue(
            "次级墨里又展开了一次 WCAG 极限值——AA 判据出现第二个真源：\n$body",
            !body.contains("WCAG_AA_RATIO"),
        )
        assertTrue(
            "次级墨里又出现了 (luma + .05) 的对比度极限式：\n$body",
            !body.contains("+ 0.05f"),
        )
        assertTrue(
            "1/255 的向上取整补偿不见了，画下去会被截半档：\n$body",
            body.contains("255f"),
        )
    }

    /**
     * 课程底板扫描用的那批 tint：自定义色由取色器任意挑，所以除默认调色板外再扫一片灰阶
     * （重灾区：中间亮度黑白两支都嫌不够），末尾四支是真实用户挑出来的补色。
     * 可读性守卫与"选墨不许选贵"守卫共用这一份，两边各自列一份就会漂移。
     */
    private fun coursePlateTintGrid(): List<Color> = CourseColors + (0..10).map { v ->
        val channel = (v * 25 + 12).coerceAtMost(255)
        Color(channel / 255f, channel / 255f, channel / 255f)
    } + listOf(
        Color(0xFFF2994A), Color(0xFF00B8D4), Color(0xFFE0E0E0), Color(0xFF3B0764),
    )

    // ---- 渲染侧口径 -------------------------------------------------------

    /**
     * 一格帧缓冲的量化预算：把已经画出来的那块板**再往靠近墨的方向挪一整格 1/255**，
     * 对比度掉多少就记多少。
     *
     * 反解出的 alpha 是连续值，混合出来的通道值（[drawnPlateLsb]）落在两个整数格之间，
     * 帧缓冲四舍五入之后最多偏半格；这里给的是**一整格**，而且是照着"更不利"的方向给的，
     * 所以它必然盖得住真实的取整误差——同时又是一整格，而不是凭手感调出来的数。
     */
    private fun frameBufferSlack(tintStep: Int, sceneStep: Int, alpha: Float, plate: Float, ink: Float): Float {
        val step = if (plate > ink) -1 else +1 // 朝墨的方向挪一格才是往界上踩
        val shifted = grayAt((drawnPlateLsb(tintStep, sceneStep, alpha) + step).coerceIn(0, 255).toFloat())
            .readableLuminance()
        return abs(contrastRatio(plate, ink) - contrastRatio(shifted, ink))
    }

    /** 下限解出的那档 alpha 混出来的板，在 8 bit 帧缓冲上落在哪一格（0..255）。 */
    private fun drawnPlateLsb(tintStep: Int, sceneStep: Int, alpha: Float): Int =
        (alpha * tintStep + (1f - alpha) * sceneStep).roundToInt().coerceIn(0, 255)

    /**
     * 平台真正画出来的那块中性灰板：三条通道各按 alpha 插值，再折回相对亮度。
     * 灰的通道值与它的相对亮度互为解析反函数（亮度权重之和为 1），所以这里不需要自己写曲线。
     */
    private fun renderedGrayPlate(tintStep: Int, sceneStep: Int, alpha: Float): Float =
        grayAt(drawnPlateLsb(tintStep, sceneStep, alpha).toFloat()).readableLuminance()

    /** 把一块墨按它自带的 alpha 叠到中性灰板上：逐通道插值之后再量亮度（平台口径）。 */
    private fun renderInkOnPlate(ink: Color, plateChannel: Float): Float = Color(
        red = ink.alpha * ink.red + (1f - ink.alpha) * plateChannel,
        green = ink.alpha * ink.green + (1f - ink.alpha) * plateChannel,
        blue = ink.alpha * ink.blue + (1f - ink.alpha) * plateChannel,
    ).readableLuminance()

    /**
     * 靶板的编码通道值。[legibleTintPlate] 在"黑白都读不出"的中间亮度带上会把灰 tint 往
     * 黑/白压（压完仍是中性灰），所以取红通道当板值就够——真被压出彩度来这条就算不成了。
     */
    private fun neutralPlateChannel(plate: TintPlate): Float {
        val tint = plate.tint
        assertTrue(
            "中性灰靶板被压成了彩色（r=${tint.red} g=${tint.green} b=${tint.blue}），逐通道口径失效",
            abs(tint.red - tint.green) < 1e-6f && abs(tint.red - tint.blue) < 1e-6f,
        )
        return tint.red
    }

    private fun gray(channel: Int): Color = grayAt(channel.toFloat())

    private fun grayAt(channel: Float): Color = Color(channel / 255f, channel / 255f, channel / 255f)

    /** 读被测源码：测试工作目录可能是仓库根也可能是 app/，逐级向上找（口径同既有源码核对测试）。 */
    private fun colorSource(): String {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { File(dir, it) }
                .map { File(it, "com/buaa/schedule/core/designsystem/Color.kt") }
                .firstOrNull { it.isFile }
            if (hit != null) return hit.readText()
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 Color.kt：当前目录 ${File("").absolutePath}")
    }

    /** 取出某个顶层函数的函数体（含大括号），花括号配对扫描，不猜行号。 */
    private fun bodyOfTopLevelFun(source: String, name: String): String {
        val signature = source.indexOf("fun $name(")
        assertTrue("源码里找不到 $name：函数被改名或内联掉了", signature >= 0)
        val open = source.indexOf('{', signature)
        var depth = 0
        var index = open
        while (index < source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, index)
                }
            }
            index++
        }
        throw IllegalStateException("$name 的大括号没配对")
    }

    private fun contrastRatio(lumaA: Float, lumaB: Float): Float {
        val hi = maxOf(lumaA, lumaB)
        val lo = minOf(lumaA, lumaB)
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private companion object {
        /** 扫描用的中性灰阶：每 8 档一格，覆盖黑到白 */
        val GRAY_STEPS = (0..31).map { it * 8 }

        /** 课程底板扫描的场景亮度档：内置暗渐变、中间档、白壁纸 */
        val COURSE_PLATE_SCENES = listOf(0.03f, 0.50f, 0.83f)

        /** 课程底板扫描的 tint alpha 档：滑条最透的一档到关玻璃的恒定值 */
        val COURSE_PLATE_ALPHAS = listOf(0.26f, 0.47f, 0.68f, 0.92f)

        /** 两支候选墨的相对亮度：就是 [contentOnLuma] 手里那对 [ContentDark] / [ContentLight] */
        val INK_LUMAS = listOf(ContentDark.readableLuminance(), ContentLight.readableLuminance())

        /** 次级墨的硬底，与 Color.kt 里那条同源；这里只用来认出"被夹住"的格子 */
        const val MIN_SUBTLE_ALPHA = 0.72f

        /** 对比度断言的宽容差：浮点最后一位与 1/255 量化 */
        const val TOLERANT_RATIO = 0.02f

        /** 下限"松一格"的步长：比 1/255 大得多，免得量化噪声冒充口径错误 */
        const val SLACK_ALPHA = 0.05f

        /**
         * 比较"两支墨谁更省 alpha"的容差：差不到 [SLACK_ALPHA] 十分之一的两支算同档，
         * 交给 [contentOnLuma] 的偏好收掉——真的选贵了的那一支差的是一整档、半档，不会落进这里。
         */
        const val NEEDED_ALPHA_TOLERANCE = 0.005f

        /**
         * "贴着 AA 线"的带子有多宽：比值落在 [DesignTokens.WCAG_AA_RATIO] 起 0.1 档以内。
         * 同一个数也是这条带里**一格量化预算的上限**——预算要是连这条带子都装不下，
         * 它就已经不是 8 bit 取整，而是能拿来越界的东西了。
         */
        const val NEAR_LINE_BAND = 0.1f

        /** 次级墨退一格时的宽容差：alpha 是向上取整的，退一格可能恰好退回解本身 */
        const val ONE_STEP_SLACK = 0.05f
    }
}
