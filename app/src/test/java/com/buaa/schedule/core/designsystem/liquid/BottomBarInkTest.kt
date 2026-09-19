package com.buaa.schedule.core.designsystem.liquid

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.buaa.schedule.core.designsystem.BlueOnSurfaceVariant
import com.buaa.schedule.core.designsystem.ContentDark
import com.buaa.schedule.core.designsystem.ContentLight
import com.buaa.schedule.core.designsystem.DarkBlueOnSurfaceVariant
import com.buaa.schedule.core.designsystem.DarkGlassTint
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.core.designsystem.LightGlassTint
import com.buaa.schedule.core.designsystem.SceneLuma
import com.buaa.schedule.core.designsystem.compositeLuma
import com.buaa.schedule.core.designsystem.contrastRatio
import com.buaa.schedule.core.designsystem.legibilityAlphaFloor
import com.buaa.schedule.core.designsystem.readableLuminance
import com.buaa.schedule.core.designsystem.worstGlassSceneLuma
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [bottomBarInk]：底栏 tab 的墨色按**实际复合底色**解，而不是按主题写死（ai/T25b）。
 *
 * ## 这张靶子是 ai/T25 报回来的
 *
 * T25 把底栏表面 alpha 的夹取顺序改与 [com.buaa.schedule.core.designsystem.glassSurfaceAlpha]
 * 同构之后，0.60 的天花板第一次真生效。于是深色档 × 极白壁纸块这一格露出下一层错：
 * 暗板 `#14161C`（亮度 0.0081）以 0.60 叠在亮度 1.00 的白块上，复合亮度 **0.1722**，
 * 而 tab 的图标/文字恒用 `scheme.onSurfaceVariant` = `#C5C6D0`（亮度 0.5681）→
 * **2.78:1**，正文要求 [DesignTokens.WCAG_AA_RATIO] = 4.5:1。
 * （T25 当年报的是 0.405 与 1.36:1——那是 [compositeLuma] 还混在线性亮度那一维时算出来的板，
 * 真机上从没画出来过，口径错误见 `GlassPlateDeviceCalibrationTest`。读不清这件事本身不假，
 * 只是没到 1.36 那么邪乎。）
 *
 * 两条修法里抬天花板被否了：底栏是悬浮在整屏滚动课表之上的 overlay，抬到 AA 需要的
 * ≈0.92 等于把它压成一条实心横带（本仓口径：小玻璃好看、大玻璃板丑）。所以改的是**墨**——
 * [bottomBarInk] 按复合底色解。这是同族第三处，前两处是
 * [com.buaa.schedule.core.designsystem.contentOnLuma]（周视图课程卡）与
 * [com.buaa.schedule.core.designsystem.semanticGlassPlateOf]（语义玻璃卡，ai/T23）：
 * 值域上界与消费方需要的实底对不上时，动的是这一族自己的颜色口径，不是上界。
 *
 * ## 为什么这里只测纯函数
 *
 * 本模块的 JVM 单测没有 Compose 运行时（无 Robolectric、无 ui-test），`@Composable` 体内的
 * 取值测不到——与 `BottomBarSurfaceAlphaTest` 同一处境。接线形状（CompositionLocal →
 * `tabContent` → `NavItemContent`）与"场景亮度只读一次"（第 4 条契约）只能按源码核对，见
 * [legacyNavigationPathsDoNotAskTheBottomBarForInk] 与 [theSceneLumaIsReadOnceAndTheInkNeverReadsItAlone]，
 * 口径同 `SemanticGlassPlateTest`。
 *
 * 场景亮度靠 [SceneLuma] 驱动（风格同 `BottomBarSurfaceAlphaTest`）。
 */
class BottomBarInkTest {

    @After
    fun restoreScene() {
        SceneLuma.wallpaper = SceneLuma.Stats.Unknown
    }

    /**
     * 先确认靶子真的存在，否则下面那条"解出的墨达标"是绿在空气上。
     * 这条与实现无关：只钉住"0.60 天花板生效之后，品牌墨在极白块上就是读不出"。
     * 括号里的两个数是 T25 那一格在**搬对混合维度之后**的读数（旧口径的 0.405 / 1.36:1
     * 是一块画不出来的板，见 `GlassPlateDeviceCalibrationTest`），搬维度不许把这一格搬成读得清。
     */
    @Test
    fun theBrandInkReallyFailsOnTheTargetScene() {
        SceneLuma.wallpaper = WHITE_BLOCK
        val floor = legibilityAlphaFloor(DarkGlassTint, DarkBlueOnSurfaceVariant, darkTheme = true)
        assertTrue("这格不再是 T25 报的那格了（floor 没越顶，天花板本来就生效）：$floor", floor > BOTTOM_BAR_SURFACE_ALPHA_CEILING)
        val composite = targetComposite()
        assertTrue("复合亮度应为这一格在编码维度下的 0.1722 一档，实际 $composite", composite in 0.17f..0.18f)
        val brand = contrastRatio(composite, DarkBlueOnSurfaceVariant.readableLuminance())
        assertTrue(
            "品牌墨的读数应为 2.78:1 一档（旧线性口径在这格报的 1.36:1 是画不出来的板），实际 $brand：" +
                "色表被改过，本测试的靶子要重新对",
            brand in 2.72f..2.84f,
        )
    }

    /**
     * 靶子场景解出的墨必须达标：ai/T25 报回的那格（深色 × 极白块 × 0.60）换成黑白较优那支之后
     * 读到 **4.72:1**（≥ 4.5:1），而 alpha 一个 number 都没动，栏体仍是 0.60 那一档玻璃。
     *
     * 较优的那支从近黑翻成了纯白：旧口径以为那块板有 0.405 亮，黑字在上面才占优（它算出 7.45:1）；
     * 真画出来只有 0.1722，黑字掉到 3.64:1，反而是白字 4.72:1 达标。翻的是"板到底几斤几两"，
     * [bottomBarInk] 的判据一个字没动——这正是本卡要的：口径搬对，解跟着对。
     */
    @Test
    fun unreadableBrandInkResolvesToAnInkThatClearsAA() {
        SceneLuma.wallpaper = WHITE_BLOCK
        val alpha = barAlpha(darkTheme = true, cardAlpha = 0.88f, plate = DarkGlassTint, text = DarkBlueOnSurfaceVariant)
        assertEquals(
            "墨色是照着 0.60 这一档复合底色解的，alpha 被顺手改了就先红这条",
            BOTTOM_BAR_SURFACE_ALPHA_CEILING, alpha, 0f,
        )
        val ink = barInk(alpha, darkTheme = true, plate = DarkGlassTint, text = DarkBlueOnSurfaceVariant)
        val ratio = contrastRatio(targetComposite(), ink.readableLuminance())
        assertTrue("解出的墨就是品牌墨本身，等于什么都没修：$ink", ink != DarkBlueOnSurfaceVariant)
        assertEquals("纯白那支（ContentLight）才是这块真板上的正解", ContentLight, ink)
        assertEquals("复合底色上的实际比值", 4.72f, ratio, 0.02f)
        assertTrue("改后 $ratio:1 仍低于 AA", ratio >= DesignTokens.WCAG_AA_RATIO)
    }

    /**
     * 保住品牌色的守卫：`onSurfaceVariant` 本来就达标时**原样返回它**，不许换黑白。
     *
     * 深色档在内置渐变上（0.20 那一档玻璃）实测 5.70:1、浅色档 5.48:1——现网绝大多数
     * 场景就是这一格。（旧线性口径给的是 5.35:1 与 5.58:1：它永远把板算得偏亮，于是在暗板上
     * 把浅色的品牌墨显得比真的更糊、在亮板上又把深色的品牌墨显得比真的更清——同一处维度错的
     * 两面。）无条件按亮度挑黑/白会把整套品牌调墨丢掉，而 tab 文字从 `#C5C6D0`
     * 变成纯白是用户看得出来的事。
     */
    @Test
    fun aBrandInkThatAlreadyClearsAAIsKeptVerbatim() {
        val cases = listOf(
            Case("深色 × 内置渐变", true, SceneLuma.Stats.Unknown),
            Case("浅色 × 内置渐变", false, SceneLuma.Stats.Unknown),
            Case("浅色 × 均匀中灰（透一档）", false, MID_GRAY, cardAlpha = 0.3f),
            Case("深色 × 内置渐变（最透一档）", true, SceneLuma.Stats.Unknown, cardAlpha = 0.3f),
        )
        var kept = 0
        for (case in cases) {
            SceneLuma.wallpaper = case.scene
            val plate = if (case.dark) DarkGlassTint else LightGlassTint
            val text = if (case.dark) DarkBlueOnSurfaceVariant else BlueOnSurfaceVariant
            val alpha = barAlpha(case.dark, case.cardAlpha, plate, text)
            val composite = compositeLuma(plate.readableLuminance(), sceneLuma(case.dark, plate, text), alpha)
            val brand = contrastRatio(composite, text.readableLuminance())
            assertTrue(
                "${case.label}：这格本该是「品牌墨读得清」的场景，现在只有 $brand:1 —— 靶子挑错了，" +
                    "下面那条「原样返回」就恒真了",
                brand >= DesignTokens.WCAG_AA_RATIO + 0.2f,
            )
            assertEquals("${case.label}：品牌墨 $brand:1 达标却被换成了黑白", text, barInk(alpha, case.dark, plate, text))
            kept++
        }
        // 守卫不许空转：四格都得真的走到「保留」分支
        assertEquals("保留品牌墨的守卫一格都没命中", 4, kept)
    }

    /**
     * 第 4 条契约的字面化：栏体 alpha 的下限与墨色**必须看同一份场景亮度**。
     *
     * [bottomBarSurfaceAlpha] 的下限走 [legibilityAlphaFloor]，它在内部读 [SceneLuma]；
     * [bottomBarInk] 拿的却是调用点显式传进去的那一份。两处判据一旦漂移（例如只有一处
     * 改成"板比字亮就怕暗斑"），解出来的墨对的就是一块并不存在的板。
     * 这条断言钉的是"两处喂进 [DesignTokens.glassAlphaFloor] 的场景亮度是同一个值"。
     */
    @Test
    fun inkAndAlphaFloorReadTheExactSameSceneLuma() {
        val scenes = listOf(SceneLuma.Stats.Unknown, WHITE_BLOCK, BLACK_BLOCK, MID_GRAY, MID_BRIGHT)
        var checked = 0
        for (scene in scenes) {
            SceneLuma.wallpaper = scene
            for ((dark, plate) in listOf(true to DarkGlassTint, false to LightGlassTint)) {
                for (text in listOf(DarkBlueOnSurfaceVariant, BlueOnSurfaceVariant)) {
                    val explicit = bottomBarSceneLuma(plate, text, dark)
                    assertEquals(
                        "壁纸=$scene 暗档=$dark 字=$text：下限看的是另一档亮度，墨就解在了一块不存在的板上",
                        legibilityAlphaFloor(plate, text, dark),
                        DesignTokens.glassAlphaFloor(
                            surfaceLuma = plate.luminance(),
                            sceneLuma = explicit,
                            textLuma = text.luminance(),
                        ),
                        0f,
                    )
                    assertEquals(
                        "壁纸=$scene 暗档=$dark 字=$text：bottomBarSceneLuma 与 worstGlassSceneLuma 分家了",
                        worstGlassSceneLuma(dark, plateIsDark = plate.luminance() < text.luminance()),
                        explicit,
                        0f,
                    )
                    checked++
                }
            }
        }
        assertEquals("参数化测试退化：组合数没对上", 5 * 2 * 2, checked)
    }

    /**
     * 全族扫描：深浅主题 × 三档 cardAlpha × 五档壁纸极值 × 灰阶文字网格（动态取色与
     * 换主题动画途中的字色都从 onSurfaceVariant 上经过），逐格要求：
     *
     * 1. 品牌墨达标 ⇒ 原样保留它（不许无条件换成黑白）；
     * 2. 品牌墨不达标 ⇒ 交出来的必须是黑/白两候选之一（不许留着那支读不清的）；
     * 3. 只要三支候选里还有能到 AA 的（有解），交出来的就必须到 AA（留一格浮点容差）；
     *    三支都不到 AA 的无解格单独计数，格数与所属族一起钉死。
     *
     * 搬对混合维度之后第 3 条的无解格数是 **0**：暗板推到 0.60 天花板顶多到复合 0.1722，
     * 已经在 0.183~0.225 那条带子下面，而带子以下的格子下限反解本来就会把它们正好推到 AA 线上。
     * 旧口径在"均匀中灰 × 深色板"那一族里虚构出 36 格无解，是因为它把那块板算成了 0.205。
     * 格数与归属照旧钉死：谁动了板色、天花板或下限，把格子重新推进带子，这里当场报数。
     *
     * 第 3 条带着"有解却交不出"的比值回显，所以判据写坏成恒真时它会当场报数；
     * 计数器（含无解格数与归属）钉住参数化没退化、两个分支都不是空转。
     */
    @Test
    fun inkStaysLegibleAcrossTheWholeFamily() {
        val scenes = listOf(SceneLuma.Stats.Unknown, WHITE_BLOCK, BLACK_BLOCK, MID_GRAY, MID_BRIGHT)
        val cardAlphas = listOf(0.3f, 0.88f, 1.0f) // 滑条定义域 0.3..1 的两端 + 默认档
        val texts = (0..10).map { Color(it / 10f, it / 10f, it / 10f) } +
            listOf(DarkBlueOnSurfaceVariant, BlueOnSurfaceVariant)
        var checked = 0
        var keptBrand = 0
        var switched = 0
        var dead = 0
        val deadWhere = mutableListOf<String>()
        val deadKinds = mutableSetOf<String>()
        for (scene in scenes) {
            SceneLuma.wallpaper = scene
            for (cardAlpha in cardAlphas) {
                for ((dark, plate) in listOf(true to DarkGlassTint, false to LightGlassTint)) {
                    for (text in texts) {
                        val alpha = barAlpha(dark, cardAlpha, plate, text)
                        val luma = sceneLuma(dark, plate, text)
                        val composite = compositeLuma(plate.readableLuminance(), luma, alpha)
                        val ink = bottomBarInk(plate, alpha, text, dark, luma)
                        val ratio = contrastRatio(composite, ink.readableLuminance())
                        val brand = contrastRatio(composite, text.readableLuminance())
                        val bestOfTwo = maxOf(
                            contrastRatio(composite, ContentLight.readableLuminance()),
                            contrastRatio(composite, ContentDark.readableLuminance()),
                        )
                        val where = "壁纸=${sceneLabel(scene)} cardAlpha=$cardAlpha ${if (dark) "暗" else "浅"}档 " +
                            "字=${lumaOf(text)} α=$alpha 复合=$composite"
                        if (brand >= DesignTokens.WCAG_AA_RATIO) {
                            assertEquals("$where：品牌墨本来就达标（$brand:1）却被换掉了", text, ink)
                            keptBrand++
                        } else {
                            assertTrue(
                                "$where：品牌墨 $brand:1 不达标时只许退到黑/白两候选，实际 $ink",
                                ink == ContentLight || ink == ContentDark,
                            )
                            switched++
                        }
                        // 「无解」必须按容差判：反解 alpha 会把复合底色正好推到 AA 线上，那几格
                        // 品牌墨实测就是 4.4999995（差浮点最后一位），退到黑/白那支又落到 4.44:1。
                        // 这是舍入不是救不回来——真无解的那一族比 AA 低 0.38 档，容差吞不掉。
                        val slack = DesignTokens.WCAG_AA_RATIO - AA_SLACK
                        if (maxOf(brand, bestOfTwo) < slack) {
                            dead++
                            deadKinds += "${sceneLabel(scene)}×${if (dark) "暗" else "浅"}"
                            deadWhere += "$where 品牌=$brand → 黑白较优也只剩 $bestOfTwo:1，交出的 $ink 是 $ratio:1"
                            assertTrue(
                                "$where：三支候选都不到 AA，交出来的比值却反超了 AA（判据大概被改松了）",
                                ratio < DesignTokens.WCAG_AA_RATIO,
                            )
                        } else {
                            assertTrue(
                                "$where：明明有解（品牌 $brand:1 / 黑白较优 $bestOfTwo:1），交出来的却只有 $ratio:1",
                                ratio >= slack,
                            )
                        }
                        checked++
                    }
                }
            }
        }
        assertEquals("参数化测试退化：组合数没对上", 5 * 3 * 2 * 13, checked)
        // keptBrand / switched 不钉死数字：上面那格 AA 容差里的浮点噪声就能让它们挪几格。
        // 这里只要求两个分支都非空（0 就是守卫空转），参数化退化由 checked 与死带那两条把住。
        assertTrue("保留品牌墨的格数是 0：这条扫描没测到「达标就留着」那一支", keptBrand > 0)
        assertTrue("换成黑白的格数是 0：这条扫描没测到「不达标才退」那一支", switched > 0)
        assertEquals("两个分支之外还多出了一格", checked, keptBrand + switched)
        // 混合维度搬对之后：全族**没有一格**落在 0.183~0.225 的无解带里。暗板 `#14161C`
        // 推到 0.60 天花板最多到复合 0.1722（旧线性口径在这里算成 0.405，那是一块画不出来的板，
        // 正因如此它才编出过 36 格"黑白都读不清"），而带子下面那些格子的下限反解会把板
        // 正好推到 AA 线上。格数与归属一起钉：天花板、板色或下限谁被挪回去，这里当场报数。
        assertEquals(
            "0.60 天花板下又冒出无解格了：${deadWhere.joinToString(" | ")}",
            0,
            dead,
        )
        assertEquals("无解格的归属族不再只有这一族：$deadKinds", setOf<String>(), deadKinds)
    }

    /**
     * 黑白都读不清的那一档（复合亮度 0.183~0.225，[com.buaa.schedule.core.designsystem.contentOnLuma]
     * 的文档里记着同一个带）：这一带按主题方向取墨。
     *
     * 先证明它确实无解（两支都不到 AA），"较优"才不携带可读性信息：这一格几何较优是近黑
     * （4.38:1），纯白是 3.93:1，差半档、两支都读不出，代价却让 tab 文字在壁纸亮度扫过
     * 0.203 那一条时整个从浅翻成近黑。带外一律走 [contentOnLuma] 的几何较优解（上一条扫描钉）。
     *
     * 这一档现在得**手动喂**：搬对混合维度之后生产链路再也走不进带子里——暗板推到 0.60
     * 天花板只到 0.1722，其余格子被下限反解正好推到 AA 线上（所以上一条扫描里无解格数是 0）。
     * 于是这里给 [bottomBarInk] 喂它自己那两样输入（栏体基准档 alpha × 一档真能造出带内板的
     * 均匀灰阶壁纸），保住"带内跟主题走"这条分支的覆盖，并把"链路不会给出那档 alpha"
     * 与"链路选的那档 alpha 跳出了带子"一起钉住：带子是旧口径算错维度时虚构出来的，
     * 但墨色那条判据分支还站在生产代码里，得有人守着它。
     */
    @Test
    fun theDeadBandTakesTheThemeInkInsteadOfACoincidentallyDarkerOne() {
        SceneLuma.wallpaper = uniformGray(155)
        val scene = sceneLuma(darkTheme = true, plate = DarkGlassTint, text = DarkBlueOnSurfaceVariant)
        val alpha = DesignTokens.CHROME_SURFACE_ALPHA // 栏体基准档：链路在这格不会停在它上面，见下面两条
        val composite = compositeLuma(DarkGlassTint.readableLuminance(), scene, alpha)
        val white = contrastRatio(composite, ContentLight.readableLuminance())
        val dark = contrastRatio(composite, ContentDark.readableLuminance())
        assertTrue("这一格不再是无解档了（白 $white:1）", white < DesignTokens.WCAG_AA_RATIO)
        assertTrue("这一格不再是无解档了（黑 $dark:1）", dark < DesignTokens.WCAG_AA_RATIO)
        assertTrue("几何较优解已经跟着主题走了，这条守卫是空的（白 $white / 黑 $dark）", dark > white)
        assertEquals(
            "深色档在无解档上该留浅墨",
            ContentLight,
            bottomBarInk(
                containerColor = DarkGlassTint,
                effectiveAlpha = alpha,
                text = DarkBlueOnSurfaceVariant,
                darkTheme = true,
                sceneLuma = scene,
            ),
        )
        // 链路自己解的那档 alpha 不是基准档：下限反解在这一格要 0.54 才够把板压出来
        val chainAlpha = barAlpha(
            darkTheme = true,
            cardAlpha = 0.88f,
            plate = DarkGlassTint,
            text = DarkBlueOnSurfaceVariant,
        )
        assertNotEquals("链路现在就把这一格停在基准档上，那带内格就是生产口径自己造的：$chainAlpha",
            alpha, chainAlpha)
        val chainComposite = compositeLuma(DarkGlassTint.readableLuminance(), scene, chainAlpha)
        assertTrue(
            "链路现解 $chainAlpha 把板推到 $chainComposite，品牌墨在上面只有 " +
                "${contrastRatio(chainComposite, DarkBlueOnSurfaceVariant.readableLuminance())}:1——本该跳出无解带",
            contrastRatio(chainComposite, DarkBlueOnSurfaceVariant.readableLuminance()) >=
                DesignTokens.WCAG_AA_RATIO - AA_SLACK,
        )
    }

    // ---- 接线形状（没有 Compose 运行时，只能按源码核对）----------------------

    /**
     * 三条导航路径共用一个 `NavItemContent`，而**只有**悬浮玻璃底栏该交出解出来的墨：
     * 旧式底栏与宽屏导航栏走 `GlassSurface(CHROME)`，表面 alpha 口径不同（0.96 而非 0.60），
     * 它们的墨色不在本卡范围内，必须继续吃主题色。
     *
     * 这条同时钉住第 3 条契约的形状：local 默认 `null` =「底栏没说话」，且 `NavItemContent`
     * 的中性墨写成 `ink ?: onSurfaceVariant` —— local 为 null 时输出颜色逐字不变。
     */
    @Test
    fun legacyNavigationPathsDoNotAskTheBottomBarForInk() {
        val main = File(rootMainJava(), "com/buaa/schedule/MainActivity.kt")
        assertTrue("${main.path} 读不到，路径不对", main.isFile)
        val code = normalized(main.readText())

        val definition = NAV_DEFINITION.find(code)
        assertTrue("NavItemContent 的定义没扫到（签名形如 `fun ColumnScope.NavItemContent(` 变了），守卫是空的", definition != null)
        val params = definition!!.groupValues[1]
        assertTrue(
            "中性墨要能整格交还给底栏，且默认值必须是 null（=底栏没说话）：$params",
            Regex("ink\\s*:\\s*Color\\?\\s*=\\s*null") in params,
        )
        val flat = definition.groupValues[2].replace(Regex("\\s+"), "")
        assertTrue(
            "local 为 null 时必须逐字用回主题正文色，否则旧式底栏/宽屏导航栏就被顺带改掉了",
            "ink?:MaterialTheme.colorScheme.onSurfaceVariant" in flat,
        )
        assertTrue(
            "选中态仍用 primary（本卡只改中性墨，不动选中态与指示器那一族）",
            "MaterialTheme.colorScheme.primary" in flat,
        )

        val calls = NAV_CALL.findAll(code).toList()
        assertEquals("NavItemContent 的调用点数对不上（三条路径共用一条，形状变了要先确认）：${calls.map { it.value }}", 3, calls.size)
        val withInk = calls.filter { NAV_INK_ARG.containsMatchIn(it.value) }
        assertEquals("只有悬浮玻璃底栏该交出墨色：${calls.map { it.value }}", 1, withInk.size)
        assertTrue(
            "底栏那条没读 local 本身，而是在外面自己算了一份第二墨色：${withInk[0].value}",
            "LocalLiquidBottomTabInk" in withInk[0].value || "barInk" in withInk[0].value,
        )
        for (call in calls.filterNot { it in withInk }) {
            assertTrue("旧式底栏/宽屏导航栏不许被本卡顺带改掉观感：${call.value}", !NAV_INK_ARG.containsMatchIn(call.value))
        }
        assertTrue(
            "tabContent 里必须读 LocalLiquidBottomTabInk（透到 NavItemContent 的那根线断了）",
            "LocalLiquidBottomTabInk.current" in code,
        )

        val tabs = File(rootMainJava(), "com/buaa/schedule/core/designsystem/liquid/LiquidBottomTabs.kt")
        val tabsCode = normalized(tabs.readText())
        assertEquals(
            "两行 tab 内容（可见层 + MovingAccent 隐藏层）都得拿到同一支墨，否则指示器里的鬼影与外面不一致",
            2,
            Regex("LocalLiquidBottomTabInk provides").findAll(tabsCode).count(),
        )
    }

    /**
     * 第 4 条契约：栏体 alpha 与 tab 的墨色读的是**同一档**场景亮度，而且这档亮度一帧里
     * 只许读一次。JVM 测试没有 Compose 运行时，只能按源码核对（口径同上一条守卫）：
     *
     * - 整份文件里 [com.buaa.schedule.core.designsystem.worstGlassSceneLuma] 只许出现一次，
     *   且就在 [com.buaa.schedule.core.designsystem.liquid.bottomBarSceneLuma] 内部；
     *   `bottomBarSceneLuma` 只许"定义 + 唯一调用点"两处。
     *   多一处读取就是"一处读现在、另一处读别的"——换壁纸时两次读落在不同帧上，
     *   解出来的墨对的是块并不存在的板。
     * - [com.buaa.schedule.core.designsystem.liquid.bottomBarInk] 的亮度必须是**入参**，
     *   函数体里不许自己碰 `SceneLuma` 全局。
     * - 那次调用必须把画板真正用的 `effectiveContainerAlpha`（= [bottomBarSurfaceAlpha] 的
     *   返回值，`containerColor.copy(alpha = ...)` 用的就是它）交进去，不许另算一份 alpha。
     */
    @Test
    fun theSceneLumaIsReadOnceAndTheInkNeverReadsItAlone() {
        val tabs = File(rootMainJava(), "com/buaa/schedule/core/designsystem/liquid/LiquidBottomTabs.kt")
        val code = normalized(tabs.readText())

        assertEquals(
            "场景亮度被读了不止一处（栏体 alpha 与墨色就会各读一次全局）",
            1,
            Regex("worstGlassSceneLuma\\(").findAll(code).count(),
        )
        assertEquals(
            "bottomBarSceneLuma 只许「定义 + 唯一调用点」两处",
            2,
            Regex("bottomBarSceneLuma\\(").findAll(code).count(),
        )

        val ink = INK_DEFINITION.find(code)
        assertTrue("bottomBarInk 的定义没扫到（签名形如 `internal fun bottomBarInk(` 变了），守卫是空的", ink != null)
        assertTrue(
            "那档场景亮度必须是入参，而不是函数自己回头去读全局：${ink!!.groupValues[1]}",
            "sceneLuma:Float" in ink.groupValues[1].replace(Regex("\\s+"), ""),
        )
        assertTrue(
            "bottomBarInk 自己读了场景全局/最差亮度，与栏体那次读不同源",
            "SceneLuma" !in ink.groupValues[2] && "worstGlassSceneLuma" !in ink.groupValues[2],
        )

        val calls = Regex("bottomBarInk\\(([^)]*)\\)", RegexOption.DOT_MATCHES_ALL)
            .findAll(code)
            .map { it.groupValues[1] }
            .filter { " = " in it }
            .toList()
        assertEquals("bottomBarInk 只许有一个调用点：$calls", 1, calls.size)
        assertTrue(
            "墨色没用画板那一份 alpha，而是另算了一份（一块板两个口径）：${calls[0]}",
            "effectiveAlpha = effectiveContainerAlpha" in calls[0],
        )
        assertTrue("墨色没接那次唯一的场景亮度读：${calls[0]}", "sceneLuma = sceneLuma" in calls[0])
        assertTrue(
            "底板必须钉在 bottomBarSurfaceAlpha 解出的那一份 alpha 上（与墨色同一格）",
            "containerColor.copy(alpha = effectiveContainerAlpha)" in code,
        )
    }

    // ---- 取值 --------------------------------------------------------------

    private fun barAlpha(darkTheme: Boolean, cardAlpha: Float, plate: Color, text: Color): Float =
        bottomBarSurfaceAlpha(
            containerAlpha = DesignTokens.CHROME_SURFACE_ALPHA,
            userAlphaScale = DesignTokens.cardAlphaScale(cardAlpha),
            containerColor = plate,
            text = text,
            darkTheme = darkTheme,
        )

    private fun sceneLuma(darkTheme: Boolean, plate: Color, text: Color): Float =
        bottomBarSceneLuma(plate, text, darkTheme)

    private fun barInk(alpha: Float, darkTheme: Boolean, plate: Color, text: Color): Color =
        bottomBarInk(
            containerColor = plate,
            effectiveAlpha = alpha,
            text = text,
            darkTheme = darkTheme,
            sceneLuma = sceneLuma(darkTheme, plate, text),
        )

    /** T25 报回的那一格：深色档 × 极白壁纸块 × 0.60 天花板，复合亮度 0.1722（旧线性口径给 0.405） */
    private fun targetComposite(): Float {
        SceneLuma.wallpaper = WHITE_BLOCK
        val alpha = barAlpha(darkTheme = true, cardAlpha = 0.88f, plate = DarkGlassTint, text = DarkBlueOnSurfaceVariant)
        return compositeLuma(
            DarkGlassTint.readableLuminance(),
            sceneLuma(darkTheme = true, plate = DarkGlassTint, text = DarkBlueOnSurfaceVariant),
            alpha,
        )
    }

    private class Case(
        val label: String,
        val dark: Boolean,
        val scene: SceneLuma.Stats,
        val cardAlpha: Float = 0.88f,
    )

    private companion object {
        /**
         * 全族扫描判可读性的松弛量：反解出来的 alpha 把复合底色正好推到 AA 线上，品牌墨实测
         * 落在 4.4999995（浮点最后一位），此时退给黑/白那支反而更差 —— 那是舍入不是判据写坏。
         * 混合维度搬对之后这一族里已经没有"真的救不回来"的格子（无解格数被钉在 0），
         * 但 0.1 这个量度不跟着降：将来谁把某族重新推进 0.183~0.225 那条带，它得比 AA 低出
         * 0.1 档以上才算无解格，而不是从这道松弛量的缝里溜过去。
         */
        const val AA_SLACK = 0.1f

        val WHITE_BLOCK = SceneLuma.Stats(mean = 0.45f, darkest = 0.02f, brightest = 1.0f)
        val BLACK_BLOCK = SceneLuma.Stats(mean = 0.35f, darkest = 0.0f, brightest = 0.20f)
        val MID_GRAY = SceneLuma.Stats(mean = 0.50f, darkest = 0.50f, brightest = 0.50f)
        val MID_BRIGHT = SceneLuma.Stats(mean = 0.45f, darkest = 0.02f, brightest = 0.35f)

        /**
         * 一档**均匀灰阶壁纸**（0..255）折成 [SceneLuma.Stats]：三个极值同为该灰的相对亮度。
         * 换算口径与 `GlassPlateDeviceCalibrationTest` 表里的 `sceneLuma` 一列逐字相同
         * （那里 v=160 → 0.3515），所以喂进链路的场景亮度与真机壁纸同一个尺度。
         */
        fun uniformGray(step: Int): SceneLuma.Stats {
            val luma = Color(step / 255f, step / 255f, step / 255f).readableLuminance()
            return SceneLuma.Stats(luma, luma, luma)
        }

        val NAV_DEFINITION = Regex(
            "fun ColumnScope\\.NavItemContent\\(([^)]*)\\)\\s*\\{(.*?)\\n}",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        val NAV_CALL = Regex("^[ \\t]*NavItemContent\\(([^)]*)\\)", setOf(RegexOption.MULTILINE))
        val NAV_INK_ARG = Regex("\\bink\\s*=")
        val INK_DEFINITION = Regex(
            "internal fun bottomBarInk\\(([^)]*)\\)\\s*:\\s*Color\\s*\\{(.*?)\\n\\}",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )

        fun sceneLabel(scene: SceneLuma.Stats): String = when (scene) {
            SceneLuma.Stats.Unknown -> "内置渐变"
            WHITE_BLOCK -> "极白块"
            BLACK_BLOCK -> "极黑块"
            MID_GRAY -> "均匀中灰"
            MID_BRIGHT -> "中等亮块"
            else -> "其他"
        }

        fun lumaOf(color: Color): String = "%.3f".format(color.luminance())

        /** 仓库里是 CRLF：先把行尾归一，`$` 与 `\n` 才按 Kotlin 里写的那样工作 */
        fun normalized(src: String): String = blankComments(src).replace("\r\n", "\n")

        fun rootMainJava(): File {
            var dir: File? = File("").absoluteFile
            repeat(5) {
                val hit = listOf("src/main/java", "app/src/main/java")
                    .map { File(dir, it) }
                    .firstOrNull { it.isDirectory }
                if (hit != null) return hit
                dir = dir?.parentFile
            }
            throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
        }

        /** 抹掉 `//` 与 `/* */` 注释的内容，长度与换行不动：KDoc 里写着 `ink =` 不该被算成调用点 */
        fun blankComments(src: String): String {
            val out = src.toCharArray()
            var i = 0
            while (i < out.size) {
                when {
                    src.startsWith("//", i) -> {
                        val nl = src.indexOf('\n', i).let { if (it < 0) out.size else it }
                        for (k in i until nl) out[k] = ' '
                        i = nl
                    }

                    src.startsWith("/*", i) -> {
                        var j = i + 2
                        while (j < out.size && !src.startsWith("*/", j)) j++
                        val end = (j + 2).coerceAtMost(out.size)
                        for (k in i until end) if (out[k] != '\n') out[k] = ' '
                        i = end
                    }

                    else -> i++
                }
            }
            return String(out)
        }
    }
}
