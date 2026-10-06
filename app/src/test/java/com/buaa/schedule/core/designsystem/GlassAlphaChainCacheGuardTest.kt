package com.buaa.schedule.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [GlassSurface] 的 alpha 反解链：钉它**有没有被缓住**，以及缓住之后**还跟不跟壁纸走**。
 *
 * 这一族判据是「同一枚纯表达式的输入没变，却在每次重组时重算」。同一个文件里 `material`
 * 与 `glassModifier` 两处早就 remember 化了（后者那行注释写的就是「全部输入 remember 化」），
 * 而 rawAlpha → semanticPlate → baseTint → surfaceAlpha 这五行一直是裸跑的：
 * 全站 45 枚 `GlassSurface(` 调用点，每次重组都要走一遍 [glassRawAlpha] +
 * [semanticGlassPlateOf]（内含 `listOf` / `map` / `maxByOrNull`）+ [glassSurfaceAlpha] →
 * [legibilityAlphaFloor] → [worstGlassSceneLuma] → [DesignTokens.glassAlphaFloor]（两次 `luminance()`）。
 *
 * ## 为什么这里要两把尺，而不是一把
 *
 * 把这五行塞进 `remember(variant, material, …) { }` 看着就完了，实际会**静默画错**：
 * 链上要读 [SceneLuma.wallpaper]（[worstGlassSceneLuma] 里那一读，本链经
 * [legibilityAlphaFloor] 与 [legibleSemanticPlate] 共三次触到），而带键的 `remember` 把块内的
 * State 读记在**外层组合作用域**上——换壁纸会重组外层，但键没变就直接回缓存值。
 * 后果是"壁纸换了、这块板的透明度不再跟着变"，不报错、不红、门禁全绿。
 * 本仓在小组件那一族刚犯过"一块板两个口径"的同族车，所以失效依据必须是**可证的**：
 * 落点选 `derivedStateOf`（由 snapshot 自己记依赖），于是这条尺只问形状：
 * - [alphaChainIsCachedBehindDerivedStateOf]：链必须同时在 `remember(` 与 `derivedStateOf {` 之内，
 *   且那枚 `derivedStateOf` 必须就在那枚 `remember` 的块里（不是隔壁某处）。
 * - [rememberKeysCoverEveryNonStateInputOfTheChain]：外层键必须列全六个非 State 输入——
 *   derivedStateOf 只替 State 记账，普通输入漏一位照样陈旧（`alphaScale` 漏了就是
 *   "拖透明度滑条板子不动"，与 T31 那枚"玻璃开关静默 no-op"同一形状）。
 *
 * 两枚数值测试管的是另一半：
 * - [chainMatchesTheCompositionSitesOwnFormula]：抽成 [glassAlphaChain] 之后逐格等值于调用点
 *   原来那五行（数值口径一个字没改，`GlassSurfaceAlphaTest` / `SemanticGlassPlateTest` 钉的
 *   那三家的实现本卡没碰）。
 * - [chainIsSceneLumaSensitiveSoTheCacheIsLoadBearing]：**这条失效依据不是摆设**——换壁纸
 *   必须真的改变四个变体 × 两套主题里每一档的取值。链如果对场景不敏感，那"有没有 remember"
 *   根本无所谓，上面那把尺就是一条空判据（T118 那枚套套逻辑的同族）。
 *
 * 读盘与定位源码的办法与 [SectionHeaderInkGuardTest] / [GlassSurfaceSingleChildTest] 一致
 * （`findMainJavaDir()` + 抹注释后再配平花括号）；找不到目录直接抛，不用 assumeTrue 跳过——
 * 找错路径只表现为"永远是绿的"，比红更糟。
 * 工作树输入已由 `app/build.gradle.kts` 的 `guardReadWorkingTreeFiles`（T86/T92①）声明，本卡不动构建脚本。
 */
class GlassAlphaChainCacheGuardTest {

    @After
    fun restoreScene() {
        SceneLuma.wallpaper = SceneLuma.Stats.Unknown
    }

    /**
     * 链必须在 `remember(` 之内，且里面的块是 `derivedStateOf { … }`。
     *
     * 变异自证（本卡跑过，两向都算）：把 `derivedStateOf { }` 摘掉、只留
     * `remember(…) { glassAlphaChain(…) }` ⇒ 这一枚必须红；装回去必须绿。
     */
    @Test
    fun alphaChainIsCachedBehindDerivedStateOf() {
        val body = glassSurfaceBody()
        val chainAt = body.blanked.indexOf("glassAlphaChain(")
        assertTrue(
            "$GLASS_RELATIVE 的 GlassSurface 函数体里找不到 glassAlphaChain( 调用——" +
                "整条链又被搬回组合期裸跑，或者被内联掉了：\n${body.raw.trim()}",
            chainAt >= 0,
        )

        val cached = wrappingCall(body.blanked, "remember", chainAt)
        assertTrue(
            "alpha 反解链没被 remember 住：全站 45 枚 GlassSurface 调用点每次重组都裸跑一遍 " +
                "glassRawAlpha + semanticGlassPlateOf + glassSurfaceAlpha → legibilityAlphaFloor → " +
                "worstGlassSceneLuma（同文件的 material / glassModifier 两处早就缓存了，" +
                "那句「全部输入 remember 化」不该只盖一半）",
            cached != null,
        )
        val derived = wrappingCall(body.blanked, "derivedStateOf", chainAt)
        assertTrue(
            "alpha 反解链不在 derivedStateOf 之内。链上要读 SceneLuma.wallpaper（经 " +
                "legibilityAlphaFloor 与 legibleSemanticPlate 共三次触到 worstGlassSceneLuma），" +
                "而带键的 remember 把块内 State 读记在外层组合作用域上：换壁纸会重组外层、" +
                "键却没变，于是直接回缓存值——板子的透明度从此不再跟壁纸走，不报错也不红。" +
                "要么用 derivedStateOf 让 snapshot 记账，要么把 SceneLuma.wallpaper 显式读成" +
                "一枚局部 val 再列进键（口径见 GlassSegmentedControl 的 wallpaperStats），" +
                "只改一半不算改完",
            derived != null,
        )
        assertTrue(
            "derivedStateOf 必须就在那枚 remember 的块里（否则 remember 缓存的是别的东西，" +
                "链仍在每次重组裸跑）：remember = [${cached!!.start}..${cached.end})，" +
                "derivedStateOf = [${derived!!.start}..${derived.end})",
            cached.start <= derived.start && derived.end <= cached.end,
        )
    }

    /**
     * 外层键必须列全六个非 State 输入。
     *
     * 这一条管的是 derivedStateOf 盖不住的那一半：State 由 snapshot 记账，
     * 普通输入（`variant` / `material` / `semanticTint` / `alphaScale` / `darkTheme` /
     * `colorScheme`）漏一位，捕获进 lambda 的就是旧值，链从此不跟它们走。
     * 变异自证：从键表里删掉 `alphaScale` ⇒ 这一枚必须红。
     */
    @Test
    fun rememberKeysCoverEveryNonStateInputOfTheChain() {
        val body = glassSurfaceBody()
        val chainAt = body.blanked.indexOf("glassAlphaChain(")
        assertTrue("$GLASS_RELATIVE 的函数体里找不到 glassAlphaChain( 调用", chainAt >= 0)
        val cached = wrappingCall(body.blanked, "remember", chainAt)
        assertTrue("alpha 反解链没被 remember 住，键表无从谈起", cached != null)

        val args = cached!!.args
        val missing = NON_STATE_INPUTS.filterNot { Regex("\\b$it\\b").containsMatchIn(args) }
        assertTrue(
            "remember( 的键表缺了这些**非 State** 输入：$missing。" +
                "derivedStateOf 只替 State 记账，普通输入漏一位就是另一族陈旧" +
                "（漏 alphaScale = 拖透明度滑条板子不动，漏 colorScheme = 换主题不改配对）。" +
                "\n现在的键表：${args.trim()}",
            missing.isEmpty(),
        )
    }

    /**
     * 抽成 [glassAlphaChain] 之后逐格等值于五行原文。
     *
     * 对照物就是 [GlassSurface] 里那五行本来的写法（在这里按同样的顺序再走一遍同样的三个
     * internal 入口）——所以这条测的是"搬家没改口径"，不是第二份数值闸门：
     * 数值口径仍由 `GlassSurfaceAlphaTest` 与 `SemanticGlassPlateTest` 钉。
     * 逐格比原始位（`!=` 直接算红），一个 ULP 都不许差。
     */
    @Test
    fun chainMatchesTheCompositionSitesOwnFormula() {
        val drift = mutableListOf<String>()
        for (cell in schemeCells) {
            for (variant in GlassVariant.entries) {
                val material = materialOf(variant, cell.dark)
                for (scale in ALPHA_SCALES) for (tint in tintsOf(cell)) {
                    SceneLuma.wallpaper = midScene
                    val chain = glassAlphaChain(variant, material, tint, scale, cell.dark, cell.scheme)
                    val rawAlpha = glassRawAlpha(variant, material, tint, scale)
                    val plate = tint?.let { semanticGlassPlateOf(it, rawAlpha, cell.dark, cell.scheme) }
                    val baseTint = plate?.tint ?: if (cell.dark) DarkGlassTint else LightGlassTint
                    val expected = glassSurfaceAlpha(
                        variant = variant,
                        material = material,
                        semanticTint = tint,
                        alphaScale = scale,
                        baseTint = baseTint,
                        text = plate?.foreground ?: cell.scheme.onSurfaceVariant,
                        darkTheme = cell.dark,
                    )
                    val label = "${cell.name}/$variant/scale=$scale/${if (tint == null) "中性板" else "语义板"}"
                    if (chain.plate != plate) drift += "$label：plate ${chain.plate} ≠ $plate"
                    if (chain.baseTint != baseTint) drift += "$label：baseTint ${chain.baseTint} ≠ $baseTint"
                    if (chain.surfaceAlpha != expected) drift += "$label：surfaceAlpha ${chain.surfaceAlpha} ≠ $expected"
                    if (chain.surfaceAlpha !in DesignTokens.GLASS_HARD_MIN_ALPHA..SURFACE_ALPHA_CEILING) {
                        drift += "$label：surfaceAlpha ${chain.surfaceAlpha} 出了 [0.08, 0.96] 值域（T22 那族）"
                    }
                }
            }
        }
        assertTrue(
            "glassAlphaChain 与调用点原来的五行不再等值，数值口径被动过了：\n" + drift.joinToString("\n"),
            drift.isEmpty(),
        )
    }

    /**
     * 失效依据是 load-bearing 的：换壁纸必须真的改到每一档的取值。
     *
     * 每个（变体 × 主题）都要求至少一格在"无壁纸（内置渐变常数）"与"一张有亮斑也有暗斑的
     * 真壁纸"之间算出不同的 surfaceAlpha 或不同的 plate。全都相同的话，说明这条链根本不读
     * 场景，那 [alphaChainIsCachedBehindDerivedStateOf] 就成了枚空判据（T118 那族套套逻辑）。
     * 红了直接把不敏感的那几档念出来，不用人去猜网格。
     */
    @Test
    fun chainIsSceneLumaSensitiveSoTheCacheIsLoadBearing() {
        val blind = mutableListOf<String>()
        for (cell in schemeCells) {
            for (variant in GlassVariant.entries) {
                val material = materialOf(variant, cell.dark)
                var differs = false
                for (scale in ALPHA_SCALES) for (tint in tintsOf(cell)) {
                    SceneLuma.wallpaper = SceneLuma.Stats.Unknown
                    val calm = glassAlphaChain(variant, material, tint, scale, cell.dark, cell.scheme)
                    SceneLuma.wallpaper = hotScene
                    val hot = glassAlphaChain(variant, material, tint, scale, cell.dark, cell.scheme)
                    if (calm.surfaceAlpha != hot.surfaceAlpha || calm.plate != hot.plate) differs = true
                }
                if (!differs) blind += "${cell.name}/$variant：换壁纸后 surfaceAlpha 与 plate 全都没变"
            }
        }
        assertTrue(
            "alpha 链对场景不敏感，derivedStateOf 那一半就是装饰（这条失效依据必须是有内容的）：\n" +
                blind.joinToString("\n"),
            blind.isEmpty(),
        )
    }

    // ---- 判据本体 --------------------------------------------------------------

    private class Call(val start: Int, val end: Int, val args: String, val block: String)

    /**
     * 包住 [inside] 下标的那一枚 `name` 调用（跨度最小的那一枚 = 最内层）。
     *
     * `remember(a, b) { … }` 的 args 取括号内那一段（就是键表），block 取尾随 lambda；
     * `derivedStateOf { … }` 没有参数表，args 为空串。判据读的是**抹掉注释与字面量**的文本，
     * 否则被读文件注释里那些 "remember" / "derivedStateOf" 字样会自己把自己钉绿
     * （T163 卡面 §4 那条"绿在散文上"的警告，同一族）。
     */
    private fun wrappingCall(code: String, name: String, inside: Int): Call? {
        var best: Call? = null
        var from = 0
        while (true) {
            val at = code.indexOf(name, from)
            if (at < 0) break
            from = at + name.length
            if (at > 0 && (code[at - 1].isLetterOrDigit() || code[at - 1] == '_')) continue
            val tail = code.substring(at + name.length)
            val head = tail.length - tail.trimStart().length
            val call = when (tail.trimStart().firstOrNull()) {
                '(' -> {
                    val open = at + name.length + head
                    val argsClose = matchingClose(code, open) ?: continue
                    val afterArgs = code.substring(argsClose + 1)
                    val blockStart = argsClose + 1 + (afterArgs.length - afterArgs.trimStart().length)
                    val blockClose = if (code.getOrNull(blockStart) == '{') matchingClose(code, blockStart) else null
                    if (blockClose == null) {
                        Call(at, argsClose + 1, code.substring(open + 1, argsClose), "")
                    } else {
                        Call(
                            at,
                            blockClose + 1,
                            code.substring(open + 1, argsClose),
                            code.substring(blockStart + 1, blockClose),
                        )
                    }
                }

                '{' -> {
                    val blockStart = at + name.length + head
                    val blockClose = matchingClose(code, blockStart) ?: continue
                    Call(at, blockClose + 1, "", code.substring(blockStart + 1, blockClose))
                }

                else -> null
            } ?: continue
            if (call.start <= inside && inside < call.end) {
                if (best == null || call.end - call.start < best.end - best.start) best = call
            }
        }
        return best
    }

    private class Body(val blanked: String, val raw: String)

    private fun glassSurfaceBody(): Body {
        val source = glassSurfaceSource()
        val slice = bodyOfTopLevelFun(source, "GlassSurface")
        return Body(slice.blanked, slice.raw)
    }

    private fun glassSurfaceSource(): String {
        val file = File(findMainJavaDir(), GLASS_RELATIVE)
        assertTrue("找不到 ${file.path}：GlassSurface 挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    // ---- 数值小格（真实主题成员，与 GlassSurfaceAlphaTest 同一套色表）------------

    private class SchemeCell(val name: String, val scheme: ColorScheme, val dark: Boolean)

    /** 与 [GlassSurface] 里 material 那一步同形（深色档关 vibrancy），否则两边算的不是同一块板 */
    private fun materialOf(variant: GlassVariant, darkTheme: Boolean): LiquidGlassMaterial =
        DesignTokens.glassMaterial(variant).let { if (darkTheme) it.copy(useVibrancy = false) else it }

    private fun tintsOf(cell: SchemeCell): List<Color?> = listOf(null, cell.scheme.error)

    // ---- 读盘小工具（口径同 SectionHeaderInkGuardTest / GlassSurfaceSingleChildTest）

    private class Slice(val blanked: String, val raw: String)

    private fun bodyOfTopLevelFun(source: String, name: String): Slice {
        val code = blankCommentsAndLiterals(source)
        val signature = code.indexOf("fun $name(")
        assertTrue("$GLASS_RELATIVE 里找不到 fun $name( ——组件被改名或内联掉了", signature >= 0)
        val paramsClose = matchingClose(code, code.indexOf('(', signature))
        check(paramsClose != null) { "$GLASS_RELATIVE: $name 的参数表括号配不上对，解析器该修了" }
        val open = code.indexOf('{', paramsClose)
        check(open > paramsClose) { "$GLASS_RELATIVE: $name 的参数表之后找不到函数体的起始花括号" }
        val close = matchingClose(code, open)
        check(close != null) { "$GLASS_RELATIVE: $name 的大括号没配对" }
        return Slice(code.substring(open + 1, close), source.substring(open + 1, close))
    }

    private fun findMainJavaDir(): File {
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

    /** 把注释与字符串/字符字面量的内容抹成等长空格（换行位置不动），判据只读代码 */
    private fun blankCommentsAndLiterals(src: String): String {
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
                    var depth = 1
                    var j = i + 2
                    while (j < out.size && depth > 0) {
                        when {
                            src.startsWith("/*", j) -> { depth++; j += 2 }
                            src.startsWith("*/", j) -> { depth--; j += 2 }
                            else -> j++
                        }
                    }
                    for (k in i until j.coerceAtMost(out.size)) if (out[k] != '\n') out[k] = ' '
                    i = j
                }

                src.startsWith("\"\"\"", i) -> {
                    val end = src.indexOf("\"\"\"", i + 3).let { if (it < 0) out.size else it + 3 }
                    for (k in (i + 3) until (end - 3).coerceAtLeast(i + 3)) if (out[k] != '\n') out[k] = ' '
                    i = end
                }

                out[i] == '"' || out[i] == '\'' -> {
                    val quote = out[i]
                    var j = i + 1
                    while (j < out.size) {
                        when {
                            src[j] == '\\' -> j += 2
                            src[j] == quote -> { j++; break }
                            src[j] == '\n' -> break
                            else -> j++
                        }
                    }
                    for (k in (i + 1) until (j - 1).coerceAtLeast(i + 1)) out[k] = ' '
                    i = j
                }

                else -> i++
            }
        }
        return String(out)
    }

    private fun matchingClose(code: String, open: Int): Int? {
        if (open < 0 || code[open] !in "([{") return null
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    private companion object {
        const val GLASS_RELATIVE = "com/buaa/schedule/core/designsystem/GlassSurface.kt"

        /** 链上的非 State 输入：derivedStateOf 盖不住它们，必须进键 */
        val NON_STATE_INPUTS = listOf("variant", "material", "semanticTint", "alphaScale", "darkTheme", "colorScheme")

        /** 用户透明度偏好的三档实测端点（DesignTokens.cardAlphaScale 的值域 0.18..1.25） */
        val ALPHA_SCALES = listOf(0.18f, 1f, 1.25f)

        val schemeCells = listOf(
            SchemeCell("深色", DarkColors, dark = true),
            SchemeCell("浅色", LightColors, dark = false),
        )

        /** 一张两头都极端：亮斑糊浅字、暗斑糊深字 */
        val hotScene = SceneLuma.Stats(mean = 0.5f, darkest = 0.0f, brightest = 1.0f)

        /** 一枚普通壁纸（与无壁纸的渐变常数都不同），等值测试用它把场景钉在同一档 */
        val midScene = SceneLuma.Stats(mean = 0.42f, darkest = 0.05f, brightest = 0.60f)
    }
}
