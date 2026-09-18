package com.buaa.schedule.core.designsystem

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GlassSurface] 的内容容器是 Box —— 这条事实决定了一张玻璃卡里能挂几个顶层子节点。
 *
 * 真机（420dpi）上踩到的现象：日视图「下一节」Hero 卡，第一行本该是灰色小字「下一节」，
 * 实际被全天疏密带子（一排彩色圆头短横）穿过去，卡片底部反而空着一截。
 * 根因不在带子的规格，而在挂载点：文本当初按"Box 里要包一层 Column"包进了 Column，
 * `TodayTimelineStrip` 却成了**同一个 Box 的第二个子节点**。Box 的子节点都从 top-start 摆起，
 * 于是带子落在卡片顶部、正对首行，它自己那句 `padding(top = spaceM)` 只是往下推一格，推不开重叠。
 *
 * 这里不锁"带子长什么样"，锁**结构**：玻璃卡的 content lambda 里，除 val/var/return 之外的
 * 顶层语句只能有 1 条。这样"再往 Box 里塞第二个子节点"在评审时必然红一条，
 * 而不是等真机截图才发现。
 *
 * 为什么不给带子加 offset/padding 了事：那只是给第二个子节点勉强找一个能画的位置，
 * Box 的语义一点没变，下一个子节点照样压上来；收进 Column 之后卡片自己是唯一排布者，
 * 再加一节内容天然排到下一格。
 *
 * 本模块的单元测试没有 Compose 运行时（无 Robolectric、无 ui-test），所以这条只能读主源码做结构核对。
 * 定位源码的办法与 MigrationChainTest 一致；找不到目录就直接抛，不用 assumeTrue 跳过——
 * 找错路径只表现为"永远是绿的"，比红更糟。
 */
class GlassSurfaceSingleChildTest {

    /** 全仓库：每个 GlassSurface 的 content lambda 只能挂一个顶层子节点 */
    @Test
    fun glassCardContentHasExactlyOneTopLevelChild() {
        val offenders = mutableListOf<String>()
        var parsed = 0
        var mentioned = 0
        for (source in mainSources()) {
            val code = blankCommentsAndLiterals(source.text)
            mentioned += occurrences(code, "GlassSurface(") - occurrences(code, "fun GlassSurface(")
            for (lambda in contentLambdas(code)) {
                parsed++
                if (lambda.children.size != 1) {
                    offenders += "${source.relative}:${lambda.line} 挂了 ${lambda.children.size} 个顶层子节点：" +
                        lambda.children.joinToString(" | ") { "${lineOf(code, it.start)}:${firstLine(it.statement)}" }
                }
            }
        }

        // 扫描本身失效（路径写错、content 改成命名实参传）也要红，否则这条守卫是空的
        assertTrue("一个 GlassSurface 调用点都没扫到，八成是路径或写法变了", mentioned > 0)
        assertEquals(
            "调用点数和对不上：有 GlassSurface 的 content 没被解析出来（写法变了？），这条守卫就是空的",
            mentioned,
            parsed,
        )
        assertTrue(
            "玻璃卡的 content 容器是 Box：第二个顶层子节点必然压在第一个上面。收进容器即可。\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** TodayHero 的那一个子节点必须是 Column：文本块和疏密带子共用同一个纵向容器 */
    @Test
    fun todayHeroStacksTextAndTimelineStripInOneColumn() {
        val hero = todayHero()
        val children = hero.content.children

        assertEquals(
            "TodayHero 的玻璃卡里只该有一个顶层子节点，实际是：" +
                children.joinToString(" | ") { firstLine(it.statement) },
            1,
            children.size,
        )
        val stack = children.single().statement
        assertTrue("那个唯一的子节点应当是纵向容器，实际是：${firstLine(stack)}", stack.startsWith("Column"))
        assertTrue(
            "疏密带子必须是这个 Column 的后代：挂在 Box 上就会压在首行「下一节」上",
            stack.contains("TodayTimelineStrip("),
        )
    }

    /**
     * 段间距口径：文本块与带子之间正好一份 spaceM。
     *
     * 既不能是 0（带子贴着最后一行文字），也不能翻倍（外层容器再 spacedBy 一次、
     * 带子又保留自己的 padding）——当初那句 padding 的语义要原样带进 Column。
     */
    @Test
    fun timelineStripKeepsExactlyOneSpaceMGapBelowTheTextBlock() {
        val hero = todayHero()
        val stack = hero.content.children.single().statement
        val header = stack.substringBefore('{')

        assertTrue(
            "外层容器的间距全靠带子自己的 padding，这里再加 arrangement 就会和它叠成两倍：${firstLine(header)}",
            !header.contains("spacedBy") && !header.contains("verticalArrangement"),
        )
        assertEquals(
            "带子上应当只剩一处 padding(top = DesignTokens.spaceM)：多一处是翻倍，少一处是贴脸",
            1,
            occurrences(hero.raw, "padding(top = DesignTokens.spaceM)"),
        )
        assertEquals(
            "文本块内部的行距仍是一处 spacedBy(spaceMicro)，与带子的段间距各管各的",
            1,
            occurrences(hero.raw, "spacedBy(DesignTokens.spaceMicro)"),
        )
    }

    /**
     * 带子的视觉规格一个字不许改：入参口径不变，时钟只有一处来源。
     *
     * nowFraction 必须继续用 TodayPlanner 算好的 slot 时刻。另起一处 LocalTime.now() 的话，
     * 这条带子和卡上"还有 N 分钟下课"会互相矛盾。
     * 时钟那条检查用的是抹掉注释之后的代码——注释里本来就写着"不再读一次 LocalTime.now()"，
     * 拿原文去查会自己把自己判红。
     */
    @Test
    fun timelineStripKeepsItsInputsAndSingleClockSource() {
        val hero = todayHero()
        val args = hero.raw.substringAfter("TodayTimelineStrip(").parenArgsClosed()

        assertTrue("segments 仍由 dayTimelineSegments(...) 供给", args.contains("dayTimelineSegments("))
        assertTrue(
            "nowFraction 仍由 dayFractionOfMinute(分钟数) 供给，且分钟数来自入参 now",
            args.contains("nowFraction = dayFractionOfMinute(") && args.contains("now.hour * 60 + now.minute"),
        )
        assertEquals(
            "带子只许挂一个 modifier，且它就是那一份段间距：带子的绘制与高度在 TodayTimelineStrip 内部，不在调用点",
            1,
            occurrences(args, "modifier = Modifier.padding(top = DesignTokens.spaceM)"),
        )
        assertTrue(
            "TodayHero 不许再读第二次时钟：全天疏密的口径以入参 now 为准",
            !hero.blanked.contains("LocalTime.now()") && !hero.blanked.contains("System.currentTimeMillis"),
        )
    }

    // ---- 源码解析：把"Box 的第几个子节点"还原成语句列表 ------------------------

    private class Source(val relative: String, private val file: File) {
        val text: String get() = file.readText()
    }

    private class Child(val statement: String, val start: Int)

    private class Lambda(val line: Int, val children: List<Child>)

    private class Hero(val blanked: String, val raw: String, val content: Lambda)

    private fun todayHero(): Hero {
        val source = Source("app/src/main/java/com/buaa/schedule/ui/home/DayView.kt", dayViewFile())
        val text = source.text
        val code = blankCommentsAndLiterals(text)
        val hero = code.indexOf("fun TodayHero(")
        assertTrue("${source.relative} 里找不到 TodayHero：靶子没了", hero >= 0)
        val bodyStart = code.indexOf('{', hero)
        val bodyEnd = matchingClose(code, bodyStart)
        check(bodyEnd != null) { "${source.relative}: TodayHero 的花括号配不上对，解析器该修了" }
        val blankedBody = code.substring(bodyStart, bodyEnd + 1)
        val lambdas = contentLambdas(blankedBody)
        assertEquals("TodayHero 里就该有一张玻璃卡，实际扫到 ${lambdas.size} 处", 1, lambdas.size)
        return Hero(blankedBody, text.substring(bodyStart, bodyEnd + 1), lambdas.single())
    }

    /** 代码里每个 `GlassSurface(` 调用点的尾随 content lambda（定义自身除外） */
    private fun contentLambdas(code: String): List<Lambda> {
        val lambdas = mutableListOf<Lambda>()
        var from = 0
        while (true) {
            val at = code.indexOf("GlassSurface(", from)
            if (at < 0) break
            from = at + 1
            if (at >= 4 && code.substring(at - 4, at) == "fun ") continue
            val argsClose = matchingClose(code, code.indexOf('(', at)) ?: continue
            // 只认尾随 lambda 的写法（全仓库都是这一种）；换写法时上面那条「点数对不上」会红
            val tail = code.substring(argsClose + 1)
            val offset = tail.indexOfFirst { !it.isWhitespace() }
            if (offset < 0 || tail[offset] != '{') continue
            val braceStart = argsClose + 1 + offset
            val bodyEnd = matchingClose(code, braceStart) ?: continue
            lambdas += Lambda(
                line = lineOf(code, at),
                children = topLevelChildren(code, braceStart + 1, bodyEnd),
            )
        }
        return lambdas
    }

    /**
     * [from until to) 这段花括号里的顶层语句：深度为 0 的换行处切一刀，每刀就是一个直接子节点。
     *
     * val/var/return 不产生布局节点，不算子节点——TodayHero 的 lambda 里本来就有两条 val。
     */
    private fun topLevelChildren(code: String, from: Int, to: Int): List<Child> {
        val children = mutableListOf<Child>()
        val current = StringBuilder()
        var depth = 0
        var start = from
        var at = from
        while (at < to) {
            val ch = code[at]
            when (ch) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
            }
            val closesStatement = ch == '\n' && depth == 0
            if (closesStatement) {
                val statement = current.toString().trim()
                if (statement.isNotEmpty() && !isDeclaration(statement)) children += Child(statement, start)
                current.setLength(0)
            } else {
                if (current.isEmpty() && !ch.isWhitespace()) start = at
                current.append(ch)
            }
            at++
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty() && !isDeclaration(tail)) children += Child(tail, start)
        return children
    }

    private fun isDeclaration(statement: String) =
        statement.startsWith("val ") || statement.startsWith("var ") || statement.startsWith("return")

    private fun lineOf(code: String, index: Int): Int = code.substring(0, index.coerceAtLeast(0)).count { it == '\n' } + 1

    private fun dayViewFile(): File {
        val file = File(findMainJavaDir(), "com/buaa/schedule/ui/home/DayView.kt")
        assertTrue("找不到 ${file.path}：DayView 挪过家的话这条守卫要跟着改路径", file.isFile)
        return file
    }

    private fun mainSources(): List<Source> {
        val root = findMainJavaDir()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${root.path} 下一个 .kt 都没有，路径不对", files.isNotEmpty())
        return files.map { Source(it.relativeTo(root).path.replace('\\', '/'), it) }
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

    // ---- 词法小工具 ----------------------------------------------------------

    /**
     * 把注释与字符串/字符字面量的**内容**抹成空格，长度与换行位置一律不变。
     *
     * 不抹的话两处会算错：字面量里的 `${...}` 带花括号、注释里也可能有括号，
     * 都会把"这条语句到这儿结束"的判断带到别处去。抹成等长空格后，
     * 同一套下标既能切代码也能切原文。
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

    /** 本串以一个已消费的 `(` 开头：返回它那一层括号里的内容 */
    private fun String.parenArgsClosed(): String {
        var depth = 1
        var i = 0
        while (i < length && depth > 0) {
            when (this[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            if (depth > 0) i++
        }
        return substring(0, i.coerceIn(0, length))
    }

    private fun occurrences(haystack: String, needle: String): Int =
        haystack.windowed(needle.length).count { it == needle }

    private fun firstLine(statement: String): String = statement.lineSequence().first().trim().take(72)
}
