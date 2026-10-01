package com.buaa.schedule.core.designsystem

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 参照件那一枚形状钉成守卫：公共件 [SectionHeader] 必须仍是「`titleSmall` + 淡墨 `onSurfaceVariant`」。
 *
 * 依据是 docs/DESIGN_SYSTEM.md §4 那条裁定（T157 落地）：页头与区块标题这一对**不靠字号差**
 * （`titleMedium` 16 与 `titleSmall` 14 只差 2sp，撑不起层级），改由两维分开——
 * 墨色：区块标题保持淡墨 `onSurfaceVariant`、页头用正常字色 `onSurface`；
 * 字重：页头维持比区块更重的字重，区块停在 `titleSmall` SemiBold、页头不得再往淡里退。
 * 层级信息全压在墨色这一维上，所以参照件要是丢了淡墨，"区块标题该长什么样"就没有实物可对，
 * 两枚自造件（`OnboardingScreen.SectionHeading`、`ConflictWizardDialog` 的组卡头）也就没有可抄的形状。
 *
 * **判据只有一条，而且只管参照件那一枚函数的函数体**：
 * 不许写成"全仓所有 `titleSmall` 都必须淡墨"。页头、设置组内小标题、对话框里的组名都在这把尺的
 * 射程边上——"这一枚到底是页级还是区块级"是语义判断题（见 T163 卡面 §3 那两枚落点，
 * 同一形状在两处判语并不相同），静态尺判不了；写进去只会得到一枚常年红、下周就被删的守卫。
 *
 * 切片口径是本仓吃过亏的地方（T163 卡面 §4 的警告）：**从"块之后那一行哨兵"往回切**，
 * 也就是从函数自己那枚闭合花括号配平回来，而不是取"源码里最后一个 `}` 的位置"（`closes[-1]`）。
 * [SectionHeader] 后面还跟着 `SettingsRow` 等私有件，而 `SettingsStack.kt` 整文件有 8 处
 * `onSurfaceVariant`——取歪了会切进邻居的体里，于是"参照件丢了淡墨"被邻居那一行补成绿（假绿）。
 * 这条不靠宣称：[theRulerReadsTheReferenceBodyNotAnywhereInTheFile] 用一枚合成样本把它演出来
 * （参照件缺墨、邻居带墨），[theBodySliceStopsAtTheReferenceComponentsOwnCloseBrace]
 * 在真盘上复验切到的确实是那一枚函数的体。
 *
 * 读盘与定位源码的办法与 [GlassSurfaceSingleChildTest] 一致（`findMainJavaDir()` + `walkTopDown`），
 * 函数体切片与 [DesignSystemTest] 的 `bodyOfTopLevelFun` 同口径；找不到目录就直接抛，
 * 不用 assumeTrue 跳过——找错路径只表现为"永远是绿的"，比红更糟。
 * 工作树输入已由 `app/build.gradle.kts` 的 `guardReadWorkingTreeFiles`（T86/T92①）声明，本卡不动构建脚本。
 */
class SectionHeaderInkGuardTest {

    /**
     * 唯一的判据本体：参照件的函数体里必须**同时**出现字号槽与淡墨那一枚色值。
     *
     * 断言消息带上切到的函数体原文——红了要一眼看得见是少了哪一半，而不是让人去猜切片。
     */
    @Test
    fun referenceSectionHeaderKeepsTitleSmallWithFadedInk() {
        val slice = sectionHeaderBody()
        val missing = missingShapeTokens(slice.blanked)

        assertTrue(
            "公共件 SectionHeader 是「层级靠墨色与字重、不靠字号差」那条裁定的参照形状" +
                "（docs/DESIGN_SYSTEM.md §4）。它自己的函数体里下面这些找不到了——参照件一变形的话，" +
                "全仓区块标题就没有可抄的那一件了：\n" +
                missing.joinToString("\n") +
                "\n\n切到的函数体原文（${slice.relative}:${slice.line} 起）：\n${slice.raw}",
            missing.isEmpty(),
        )
    }

    /**
     * 切片自证：切到的应当是那一枚函数的体，不多不少。
     *
     * 两条都关于尺子本身、不是关于被钉的代码，所以不构成"第二条判据"：
     * - 体内得有 `Text(`：锚点写歪、切到空区间的话，第一条会因为"什么都没查到"而假绿；
     * - 体内不许出现第二个 `fun`：`closes[-1]` 那种取法会把 `SectionHeader` 后面的邻居
     *   一起卷进来（这正是本仓栽过的那个坑），卷进来之后第一条就会被邻居的墨色补绿。
     *   真给参照件加了嵌套局部函数不属于这两条想防的事，届时按消息把切片口径一起改掉即可。
     */
    @Test
    fun theBodySliceStopsAtTheReferenceComponentsOwnCloseBrace() {
        val slice = sectionHeaderBody()

        assertTrue(
            "切到的区间里没有 Text( 调用，八成是锚点或切片写歪了（那样第一条会一直绿在空区间上）：\n${slice.raw}",
            slice.blanked.contains("Text("),
        )
        assertTrue(
            "切片切进了邻居的体里：SectionHeader 的函数体内不该出现第二处 `fun` 声明。" +
                "别改成「取最后一个闭合花括号往回切」——SettingsStack.kt 里 SectionHeader 后面还跟着 " +
                "SettingsRow 等私有件，而整文件有 8 处 onSurfaceVariant，切歪了会把缺墨色判成绿：\n${slice.raw}",
            !slice.blanked.contains("fun "),
        )
    }

    /**
     * 排除条件是不是装饰？切片会不会被邻居补绿？这两件事用同一枚合成样本演出来。
     *
     * 样本是"参照件自己丢了淡墨、同一份文件里邻居还留着淡墨"——正是 `closes[-1]` 的盲区：
     * 那种切法会把邻居那一行卷进函数体，于是"文件里有淡墨"被当成"SectionHeader 有淡墨"。
     * 反向对照（把淡墨补回去就该放行）证明判的确实是墨色这一维，而不是随手写死的一句串。
     */
    @Test
    fun theRulerReadsTheReferenceBodyNotAnywhereInTheFile() {
        val fadedInkElsewhere = listOf(
            "@Composable",
            "fun SectionHeader(text: String, modifier: Modifier = Modifier) {",
            "    Text(",
            "        text = text,",
            "        style = MaterialTheme.typography.titleSmall,",
            "        color = MaterialTheme.colorScheme.onSurface,",
            "        fontWeight = FontWeight.SemiBold,",
            "        modifier = modifier.padding(start = 4.dp),",
            "    )",
            "}",
            "",
            "@Composable",
            "fun SettingsRow(title: String) {",
            "    // 邻居本来就有淡墨，缺墨色的参照件不该借到它",
            "    Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant)",
            "}",
        ).joinToString("\n")

        val sliced = bodyOfTopLevelFun(fadedInkElsewhere, "SectionHeader", "Sample.kt")
        assertEquals(
            "切片越过了 SectionHeader 自己的闭合花括号，切进 SettingsRow 的体里了：\n${sliced.raw}",
            false,
            sliced.blanked.contains("SettingsRow"),
        )
        assertEquals(
            "尺子判的是函数体，不是「这枚文件里出现过淡墨」：邻居带着 onSurfaceVariant 时，" +
                "缺墨色的参照件必须仍然被报出来：\n${sliced.raw}",
            listOf(missingInk),
            missingShapeTokens(sliced.blanked),
        )

        val inkRestored = fadedInkElsewhere.replace(
            "        color = MaterialTheme.colorScheme.onSurface,",
            "        color = MaterialTheme.colorScheme.onSurfaceVariant,",
        )
        assertEquals(
            "把淡墨补回去之后同一把尺还报红，说明它钉的不是墨色这一维（那就是枚常年红的尺）：\n" +
                bodyOfTopLevelFun(inkRestored, "SectionHeader", "Sample.kt").raw,
            emptyList<String>(),
            missingShapeTokens(bodyOfTopLevelFun(inkRestored, "SectionHeader", "Sample.kt").blanked),
        )
    }

    // ---- 判据本体 --------------------------------------------------------------

    private class Slice(val relative: String, val line: Int, val raw: String, val blanked: String)

    /** 缺了哪一半就报哪一半：两条都在，返回空表 */
    private fun missingShapeTokens(body: String): List<String> {
        val missing = mutableListOf<String>()
        if (!body.contains(titleSmallToken)) missing += missingStyle
        if (!body.contains(fadedInkToken)) missing += missingInk
        return missing
    }

    private fun sectionHeaderBody(): Slice = bodyOfTopLevelFun(settingsStackSource().text, "SectionHeader", settingsStackRelative)

    // ---- 读盘小工具（口径同 GlassSurfaceSingleChildTest / DesignSystemTest）------

    private data class Source(val relative: String, val text: String)

    private val settingsStackRelative = "com/buaa/schedule/core/designsystem/SettingsStack.kt"

    private val titleSmallToken = "MaterialTheme.typography.titleSmall"

    private val fadedInkToken = "MaterialTheme.colorScheme.onSurfaceVariant"

    private val missingStyle = "  · 缺字号槽：$titleSmallToken"

    private val missingInk = "  · 缺淡墨：$fadedInkToken"

    private fun settingsStackSource(): Source {
        val root = findMainJavaDir()
        val file = File(root, settingsStackRelative)
        assertTrue("找不到 ${file.path}：公共件挪过家的话这条守卫要跟着改路径", file.isFile)
        return Source(settingsStackRelative, file.readText())
    }

    /**
     * 取出顶层函数的函数体：先从参数表那对括号配平出闭合 `)`，再取它后面的第一枚 `{`，
     * 然后**从这枚 `{` 走到配平的那枚 `}`**（哨兵就是这一行），中间就是函数体。
     *
     * 两处都不猜行号、也不取"最后一个 `}`"：花括号配对在抹掉注释与字面量之后的文本上做，
     * 免得注释里的 `[SectionHeader]`、字符串里的 `${...}` 带着括号把边界带到别处。
     * 返回的 [Slice.raw] 是原文（给断言消息看），[Slice.blanked] 是抹过的（给判据读）。
     */
    private fun bodyOfTopLevelFun(source: String, name: String, relative: String): Slice {
        val code = blankCommentsAndLiterals(source)
        val signature = code.indexOf("fun $name(")
        assertTrue("$relative 里找不到 fun $name( ——参照件被改名或内联掉了", signature >= 0)
        val paramsClose = matchingClose(code, code.indexOf('(', signature))
        check(paramsClose != null) { "$relative: $name 的参数表括号配不上对，解析器该修了" }
        val open = code.indexOf('{', paramsClose)
        check(open > paramsClose) { "$relative: $name 的参数表之后找不到函数体的起始花括号" }
        val close = matchingClose(code, open)
        check(close != null) { "$relative: $name 的大括号没配对" }
        return Slice(
            relative = relative,
            line = code.substring(0, signature).count { it == '\n' } + 1,
            raw = source.substring(open + 1, close).trim(),
            blanked = code.substring(open + 1, close),
        )
    }

    /** 单测的工作目录是模块目录还是仓库根不由这里决定：几种布局都试一遍，全落空就抛 */
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

    /**
     * 把注释与字符串/字符字面量的**内容**抹成空格，长度与换行位置一律不变（同 GlassSurfaceSingleChildTest）。
     *
     * 不抹有两处会算错：注释与 KDoc 里也可能有花括号（本文件与 SettingsStack.kt 都是），
     * 会把"函数体到这儿结束"的判断带到别处去；而参照件的 KDoc 本来就写着"次级色"这类话，
     * 拿原文去查墨色会绿在散文上。抹成等长空格后，同一套下标既能切代码也能切原文。
     */
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

    /** 从 [open] 处的括号走到配平的那个闭合括号 */
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
}
