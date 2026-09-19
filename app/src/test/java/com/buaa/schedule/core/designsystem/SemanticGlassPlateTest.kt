package com.buaa.schedule.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 语义玻璃卡（`GlassSurface(semanticTint = …)`）的**配对**闸门（ai/T23）。
 *
 * ai/T22 按住的是 `coerceIn(legibilityAlphaFloor(…), 0.96f)` 的空区间；按住之后露出来的
 * 是配对本身错了：深色主题首页那条冲突提示是一块**实心粉底**压着**浅粉字**。
 * 真机读数（buaa36 · 深色 + 有课程冲突）：
 *
 * | 配对 | 实测 |
 * |---|---|
 * | `onErrorContainer` #FFE2DE 压在 `error` #FFB4AB 上 | **1.39:1** |
 * | alpha 下限的参照 `onSurfaceVariant` #C5C6D0 压在同一块板上 | **1.00:1** ← 下限饱和到 1.0 的根源 |
 * | M3 正解 `onErrorContainer` 压在 `errorContainer` #8C1D18 上 | 7.46:1 |
 *
 * 三处错，一处套一处：
 * 1. 底板用了 `error` 本体。`error`（#FFB4AB，luma 0.5684）在 M3 里是**中性面上的强调色**
 *    （图标、边框、标签），从来不是大面积填充色；大面积填充是 `errorContainer`。
 * 2. 文字用了 `onErrorContainer`，它是 `errorContainer` 的前景，与 `error` 不成对。
 * 3. [GlassSurface] 夹 alpha 时参照的文字色恒为 `onSurfaceVariant`，而这块板实际要画的字
 *    是另一支——下限因此算错，恰好撞上 T22 那条饱和。
 *
 * 修法只有一条站得住：**配对只有一处**（[semanticGlassPlateOf]），卡位不再自己挑文字色。
 * 本测试的数值口径与 [GlassSurfaceAlphaTest] 同一套：真实主题成员、内置渐变场景常数、
 * `alphaScale = 1f`（用户透明度默认档），并同时核对玻璃实际画出来的板与退化档的实心板。
 */
class SemanticGlassPlateTest {

    @After
    fun restoreScene() {
        SceneLuma.wallpaper = SceneLuma.Stats.Unknown
    }

    /**
     * 靶子（先确认它真的存在，否则下面那条断言永远绿）：
     * 旧配对的深色读数必须仍然是 1.39:1，旧的下限参照必须仍然饱和到 1.0。
     * 这两条与实现无关，只钉住"这四个色值就是这么配不上"这件事。
     */
    @Test
    fun theOldPairingIsReallyBroken() {
        val onPlate = contrastRatio(DarkError.readableLuminance(), DarkOnErrorContainer.readableLuminance())
        assertTrue(
            "旧配对的深色读数应为 1.39:1 一档，实际 $onPlate：色表被改过，本测试的靶子要重新对",
            onPlate in 1.30f..1.49f,
        )
        val onReference = contrastRatio(
            DarkError.readableLuminance(),
            DarkBlueOnSurfaceVariant.readableLuminance(),
        )
        assertTrue(
            "下限参照（onSurfaceVariant）与 error 底板本该同亮度（1.00:1），实际 $onReference",
            onReference in 0.99f..1.02f,
        )
        SceneLuma.wallpaper = SceneLuma.Stats.Unknown
        val floor = legibilityAlphaFloor(DarkError, DarkBlueOnSurfaceVariant, darkTheme = true)
        assertTrue("旧口径的下限本该饱和到 1.0（T22 崩的就是它），实际 $floor", floor >= 1f)
    }

    /**
     * 六个卡位 × 深色/浅色 × 玻璃/退化两条路径，正文对比度一律 ≥ [DesignTokens.WCAG_AA_RATIO]。
     *
     * 两条渲染路径一起断，缺一条都可能是假的绿：
     * - **玻璃合成板**：`tint` 以实际 alpha 叠在场景上之后的合成色压文字。亮斑与暗斑各算一次
     *   取较差的那头（一块玻璃底下同时压着两种斑，平均值会低估风险）。
     *   PANEL 淡染卡（success 那几张）在 0.18 档就是"场景 + 一点语义色"，
     *   成员级对它在物理上不成立——所以成员级只作为读数打印，不当闸门（见下）。
     * - **退化实心板**：玻璃关掉 / 配额耗尽 / API < 31 时画的是不透明平板
     *   （`lerp(surfaceContainerHigh, tint, .16)`）。四张 success 状态卡默认就走这条。
     */
    @Test
    fun everySemanticCardPairIsLegibleInBothSchemes() {
        for ((slot, theme) in semanticCases()) {
            val intent = slot.intent(theme.scheme, theme.semantic)
            val plate = semanticGlassPlateOf(
                tint = intent,
                alpha = slot.alphaOf(intent),
                darkTheme = theme.dark,
                scheme = theme.scheme,
                semantic = theme.semantic,
            )
            val ink = plate.foreground.readableLuminance()
            val memberLevel = contrastRatio(plate.tint.readableLuminance(), ink)
            for (surface in GlassSurfaceKind.entries) {
                val ratio = when (surface) {
                    GlassSurfaceKind.COATED -> {
                        val alpha = glassSurfaceAlpha(
                            variant = slot.variant,
                            material = DesignTokens.glassMaterial(slot.variant),
                            semanticTint = intent,
                            alphaScale = 1f,
                            baseTint = plate.tint,
                            text = plate.foreground,
                            darkTheme = theme.dark,
                        )
                        minOf(
                            contrastRatio(
                                compositeLuma(
                                    plate.tint.readableLuminance(),
                                    sceneExtreme(theme.dark, plateIsDark = true),
                                    alpha,
                                ),
                                ink,
                            ),
                            contrastRatio(
                                compositeLuma(
                                    plate.tint.readableLuminance(),
                                    sceneExtreme(theme.dark, plateIsDark = false),
                                    alpha,
                                ),
                                ink,
                            ),
                        )
                    }

                    GlassSurfaceKind.DEGRADED_PLATE -> contrastRatio(
                        lerp(theme.scheme.surfaceContainerHigh, plate.tint, 0.16f).readableLuminance(), ink,
                    )
                }
                assertTrue(
                    "${theme.name} · ${slot.label} · ${surface.label}：底板 ${plate.tint.toHex()} " +
                        "压文字 ${plate.foreground.toHex()} 只有 $ratio:1（成员级 $memberLevel:1），" +
                        "正文要求 ≥ ${DesignTokens.WCAG_AA_RATIO}:1",
                    ratio >= DesignTokens.WCAG_AA_RATIO,
                )
            }
        }
    }

    /**
     * 底板与文字必须是**同一次解出来的一对**，且 error 卡的底板不许是 `error` 本体——
     * 那是 1.39:1 的形状本身。同时钉住"配对改对之后这一族不再落进 T22 的饱和靶子"：
     * 下限 < 0.96 才有解，落在 1.0 意思是这块板压到多实都读不清。
     */
    @Test
    fun semanticAlertsStopUsingTheErrorColorAsTheirPlate() {
        for (theme in schemes) {
            val intent = theme.scheme.error
            val raw = glassRawAlpha(
                variant = GlassVariant.ALERT,
                material = DesignTokens.glassMaterial(GlassVariant.ALERT),
                semanticTint = intent,
                alphaScale = 1f,
            )
            val plate = semanticGlassPlateOf(
                tint = intent,
                alpha = raw,
                darkTheme = theme.dark,
                scheme = theme.scheme,
                semantic = theme.semantic,
            )
            assertTrue(
                "${theme.name}：error 卡把 ${intent.toHex()} 本体当底板——它就是那条实心粉底",
                plate.tint != intent,
            )
            val memberLevel = contrastRatio(plate.tint.readableLuminance(), plate.foreground.readableLuminance())
            assertTrue(
                "${theme.name}：底板成员 ${plate.tint.toHex()} 压文字成员 ${plate.foreground.toHex()} " +
                    "只有 $memberLevel:1，要求 ≥ ${DesignTokens.WCAG_AA_RATIO}:1",
                memberLevel >= DesignTokens.WCAG_AA_RATIO,
            )
            val floor = legibilityAlphaFloor(plate.tint, plate.foreground, theme.dark)
            assertTrue(
                "${theme.name}：配对改对后下限仍到 $floor，等于又回到 T22 那一族（救不清的板）",
                floor < SURFACE_ALPHA_CEILING,
            )
        }
    }

    /**
     * 修的是配对，不是通透感：
     * - 语义卡实际 alpha 不许顶到 0.96 天花板（那是"压成实心板"的形状）；
     * - 也不许被可读性下限**抬高**——内置渐变场景下改对配对就该够读，靠压实救场是走错路；
     * - 中性玻璃（`semanticTint = null`）两档数值逐位不变——小玻璃好看、大玻璃板丑，
     *   把玻璃改实从来不是解决方案。
     */
    @Test
    fun platesStayFrostedAndNeutralGlassIsUntouched() {
        for (theme in schemes) for (slot in slots) {
            val intent = slot.intent(theme.scheme, theme.semantic)
            val raw = slot.alphaOf(intent)
            val plate = semanticGlassPlateOf(intent, raw, theme.dark, theme.scheme, theme.semantic)
            val alpha = glassSurfaceAlpha(
                variant = slot.variant,
                material = DesignTokens.glassMaterial(slot.variant),
                semanticTint = intent,
                alphaScale = 1f,
                baseTint = plate.tint,
                text = plate.foreground,
                darkTheme = theme.dark,
            )
            assertTrue(
                "${theme.name} · ${slot.label}：这块语义板被压到 $alpha，已经是一块实心板",
                alpha < SURFACE_ALPHA_CEILING,
            )
            assertTrue(
                "${theme.name} · ${slot.label}：材质档位 $raw 被可读性下限抬到 $alpha——" +
                    "该改的是配对，不是把玻璃压实",
                alpha <= raw + 0.0001f,
            )
        }
        // 中性玻璃：与 GlassSurfaceAlphaTest.readablePlates 同一组靶子，一个 number 都不许动
        for ((variant, expected) in listOf(
            GlassVariant.PANEL to DesignTokens.PANEL_SURFACE_ALPHA,
            GlassVariant.ALERT to LiquidGlassMaterial.dialog().surfaceAlpha,
        )) {
            for (theme in schemes) {
                val tint = if (theme.dark) DarkGlassTint else LightGlassTint
                assertEquals(
                    "${theme.name} · $variant 中性玻璃的 alpha 被顺手改了",
                    expected,
                    glassSurfaceAlpha(
                        variant = variant,
                        material = DesignTokens.glassMaterial(variant),
                        semanticTint = null,
                        alphaScale = 1f,
                        baseTint = tint,
                        text = theme.scheme.onSurfaceVariant,
                        darkTheme = theme.dark,
                    ),
                    0.001f,
                )
            }
        }
    }

    /**
     * 配对只准有一处：凡是给 [GlassSurface] 传了 `semanticTint` 的文件，
     * 必须同时从 [semanticGlassPlate] 取文字色。
     *
     * 本模块的 JVM 单测没有 Compose 运行时（无 Robolectric、无 ui-test），
     * 卡位上那句 `color = …` 只能按源码核对；定位目录的办法与 GlassSurfaceSingleChildTest 一致。
     */
    @Test
    fun noCardPicksItsOwnInkAnymore() {
        val offenders = mutableListOf<String>()
        var users = 0
        for (file in mainSources()) {
            val code = blankComments(file.readText())
            if (code.contains("fun GlassSurface(")) continue
            if (!code.contains("semanticTint =")) continue
            users++
            if (!code.contains("semanticGlassPlate(")) {
                offenders += "${file.relativeTo(rootMainJava())}: 传了 semanticTint 却还在自己挑文字色"
            }
            // 旧配对的残留：卡内文字直接引用 onErrorContainer（它是 errorContainer 的前景，
            // 卡位自己挑它就等于绕过配对表）
            for (stale in listOf("color = MaterialTheme.colorScheme.onErrorContainer")) {
                if (code.contains(stale)) offenders += "${file.relativeTo(rootMainJava())}: 仍有 $stale"
            }
        }
        assertTrue("一个语义卡位都没扫到，八成是写法变了，这条守卫是空的（users=$users）", users >= 6)
        assertTrue(
            "底板与文字必须成对从一个入口取（配对有两处就会再裂开一次）：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    // ---- 表 ----------------------------------------------------------------

    private class Scheme(val name: String, val scheme: ColorScheme, val semantic: SemanticColors, val dark: Boolean)

    private val schemes = listOf(
        Scheme("深色", DarkColors, DarkSemanticColors, dark = true),
        Scheme("浅色", LightColors, LightSemanticColors, dark = false),
    )

    /**
     * 一个卡位：变体 + 「这张卡想表达哪种语义色」 + 取未夹取 alpha 的办法。
     *
     * [alphaOf] 走 [glassRawAlpha] 而不是写死 0.45/0.18：这两个数已经在实现里了，
     * 测试再抄一份就成了第三处。
     */
    private class Slot(
        val label: String,
        val variant: GlassVariant,
        val intent: (ColorScheme, SemanticColors) -> Color,
        val alphaOf: (Color) -> Float,
    )

    private fun rawAlphaOf(variant: GlassVariant): (Color) -> Float = { tint ->
        glassRawAlpha(
            variant = variant,
            material = DesignTokens.glassMaterial(variant),
            semanticTint = tint,
            alphaScale = 1f, // 用户透明度默认档（cardAlpha 0.88 → 倍率 1.0）
        )
    }

    private val errorIntent = { scheme: ColorScheme, _: SemanticColors -> scheme.error }
    private val successIntent = { _: ColorScheme, semantic: SemanticColors -> semantic.success }

    /** 六个卡位（三张状态卡同款 error/success 两形，各按变体计入；共 10 形） */
    private val slots = listOf(
        Slot("首页冲突横幅（HomeScreen）", GlassVariant.ALERT, errorIntent, rawAlphaOf(GlassVariant.ALERT)),
        Slot("导入页抓取消息·失败（ImportScreen）", GlassVariant.ALERT, errorIntent, rawAlphaOf(GlassVariant.ALERT)),
        Slot("导入页抓取消息·成功（ImportScreen）", GlassVariant.PANEL, successIntent, rawAlphaOf(GlassVariant.PANEL)),
        Slot("教务登录状态卡·失败（BuaaLoginScreen）", GlassVariant.ALERT, errorIntent, rawAlphaOf(GlassVariant.ALERT)),
        Slot("教务登录状态卡·完成（BuaaLoginScreen）", GlassVariant.PANEL, successIntent, rawAlphaOf(GlassVariant.PANEL)),
        Slot("SPOC 登录状态卡·失败（SpocLoginScreen）", GlassVariant.ALERT, errorIntent, rawAlphaOf(GlassVariant.ALERT)),
        Slot("SPOC 登录状态卡·已保存（SpocLoginScreen）", GlassVariant.PANEL, successIntent, rawAlphaOf(GlassVariant.PANEL)),
        Slot("SPOC 签到状态卡·失败（SpocScanScreen）", GlassVariant.ALERT, errorIntent, rawAlphaOf(GlassVariant.ALERT)),
        Slot("SPOC 签到状态卡·已签到（SpocScanScreen）", GlassVariant.PANEL, successIntent, rawAlphaOf(GlassVariant.PANEL)),
        Slot("课表编辑校验汇总（CourseEditorScreen）", GlassVariant.ALERT, errorIntent, rawAlphaOf(GlassVariant.ALERT)),
    )

    private fun semanticCases(): List<Pair<Slot, Scheme>> = slots.flatMap { slot -> schemes.map { slot to it } }

    // ---- 取值 --------------------------------------------------------------

    private enum class GlassSurfaceKind(val label: String) {
        COATED("玻璃合成板"),
        DEGRADED_PLATE("退化实心板"),
    }

    /**
     * 内置渐变（无壁纸）在两个方向上的最不利分块亮度。
     *
     * 判据与 [legibilityAlphaFloor] 一致：[plateIsDark] 说的是"这块板相对文字是暗的"，
     * 暗板配浅字怕亮斑、亮板配深字怕暗斑。
     */
    private fun sceneExtreme(darkTheme: Boolean, plateIsDark: Boolean): Float {
        SceneLuma.wallpaper = SceneLuma.Stats.Unknown
        return worstGlassSceneLuma(darkTheme, plateIsDark)
    }

    private fun Color.toHex(): String = " #%02X%02X%02X".format(
        (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
    )

    // ---- 源码扫描 ----------------------------------------------------------

    private fun rootMainJava(): File {
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

    private fun mainSources(): List<File> {
        val root = rootMainJava()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${root.path} 下一个 .kt 都没有，路径不对", files.isNotEmpty())
        return files
    }

    /** 抹掉 `//` 与 `/* */` 注释的内容，长度与换行不动：KDoc 里写着 `semanticTint` 不该被算成调用点 */
    private fun blankComments(src: String): String {
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
