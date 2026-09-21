package com.buaa.schedule.ui.home

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「课名 Text 与别的东西同排时，必须先给同行元素留出预算」——把 T48 的宽度预算钉成结构守卫。
 *
 * 真机（buaa36，1080×2400/2.625）上踩到的现象：今日课表列表模式，课名一长，
 * 状态胶囊（「未开始」那一枚）整枚从卡片右缘消失，只剩几枚竖向裁切的字印挂在卡外；
 * 同时课名与地点之间多出一截短标题卡没有的空隙。
 *
 * 根因是量宽顺序，不是某一个调用点写错：`Row` 对非加权子节点**按顺序**把剩余全宽递下去，
 * 课名 Text 排在胶囊之前又没有 `weight`，于是长名先吃满整行并在自己末尾省略，
 * 轮到胶囊时 maxWidth 归零、被摆到行宽之外，而 PANEL 底板的 clip(shape) 把它整枚裁没
 * （GlassSurface → LiquidGlass，裁切在底板，不在胶囊自己）。竖排空隙同源于此：
 * 胶囊文字当时没锁行数，maxWidth 归零后被逐字断成三行，行高被**已经裁在卡外**的胶囊撑起来。
 *
 * 所以这里钉三件事，全部只读主源码做结构核对（本模块没有 Compose 运行时，
 * 无 Robolectric、无 ui-test——定位源码的办法与 GlassSurfaceSingleChildTest 一致，
 * 找不到目录直接抛，不用 assumeTrue 跳过——找错路径只表现为"永远是绿的"，比红更糟）：
 * 1. 全仓扫：Row 的直接子节点里，凡是以 `Text(` 开头且排的是课程名（`.displayName` /
 *    `course.name`）的，必须锁 `maxLines`；其后还挂着别的子节点的，必须带 `weight(`。
 * 2. DayView 的 CourseTimelineCard：课名必须精确是 `weight(1f, fill = false)`
 *    ——fill=true 会把短标题行的胶囊顶到行尾，那是另一桩视觉回归；胶囊文字必须锁
 *    `maxLines = 1`；两者必须同排（守卫钉的是同排互抢这个机制，靶子结构悄悄散架要红）。
 * 3. CourseManagementScreen 的 CourseGroupCard：课名 Text 必须锁 `maxLines` + 省略号，
 *    且包它的 Column 必须带 `weight(1f)`。
 */
class CourseTitleRowBudgetGuardTest {

    /** 全仓：与兄弟节点同排的课程名 Text 不许裸吃剩余宽度 */
    @Test
    fun courseTitleSharingRowWithTrailingElementMustTakeWeight() {
        val offenders = mutableListOf<String>()
        var matched = 0
        for (source in mainSources()) {
            val code = blankCommentsAndLiterals(source.text)
            forEachRowLambda(code) { rowLine, children ->
                val titleIndexes = children.indices.filter { i ->
                    children[i].statement.startsWith("Text(") &&
                        (children[i].statement.contains(".displayName") || children[i].statement.contains("course.name"))
                }
                for (i in titleIndexes) {
                    matched++
                    val statement = children[i].statement
                    val hasTrailingSibling = children.indices.any { j -> j > i }
                    val missing = mutableListOf<String>()
                    if (!statement.contains("maxLines")) missing += "maxLines"
                    if (hasTrailingSibling && !statement.contains("weight(")) missing += "weight("
                    if (missing.isNotEmpty()) {
                        offenders += "${source.relative}:$rowLine 课程名 Text 缺 ${missing.joinToString(" 和 ")}"
                    }
                }
            }
        }
        // 扫描本身失效（课程名不再走 displayName、同排行改成别的容器）也要红，否则这条守卫是空的：
        // 现存两处靶子——日视图列表卡与周视图课程格
        assertTrue(
            "同排课程名 Text 一个都没扫到（现在该有日/周视图两处），这条守卫就是空的",
            matched >= 2,
        )
        assertTrue(
            "Row 把剩余全宽按顺序递给非加权子节点：课程名不带 weight 就会吃满整行，" +
                "后面的固定元素被挤到行外、被父级 clip 裁掉。\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /**
     * CourseTimelineCard：课名与状态胶囊同排的三枚钉子。
     *
     * `weight(1f, fill = false)` 逐字符钉死：不带 weight 是改前那个「胶囊出卡」的 bug；
     * fill 翻回 true，短标题行的胶囊就会从"紧贴课名之后"变成"钉在行尾"，那是拿修 bug 的名义改排版。
     */
    @Test
    fun timelineCardNameAndStatusPillShareOneRowBudget() {
        val file = File(findMainJavaDir(), "com/buaa/schedule/ui/home/DayView.kt")
        assertTrue("找不到 DayView.kt：挪过家的话这条守卫要跟着改路径", file.isFile)
        val text = file.readText()
        val code = blankCommentsAndLiterals(text)
        // 靶子必须锚在 CourseTimelineCard 函数体内：`text = course.displayName` 在时间轴模式
        // （DayTimelineCourseList）里还有一份，那是下一张卡的范围，不能串靶
        val cardAt = code.indexOf("private fun CourseTimelineCard(")
        assertTrue("找不到 CourseTimelineCard：靶子没了", cardAt >= 0)
        val cardEnd = matchingClose(code, code.indexOf('{', cardAt))
        check(cardEnd != null && cardEnd > cardAt)
        val nameAt = code.indexOf("text = course.displayName", cardAt)
        val pillAt = code.indexOf("text = statusLabel", cardAt)
        assertTrue(
            "CourseTimelineCard 的课名/胶囊靶子没了",
            nameAt in cardAt until cardEnd && pillAt in cardAt until cardEnd,
        )

        // 两处必须还在同一个 Row 里，且课名在前——守卫钉的是「同排互抢」这个机制，结构变了要红
        val (rowOpen, rowClose) = enclosingRow(code, nameAt)
        assertTrue(
            "课名与胶囊不在同一个 Row：同排互抢的靶子散了架，去把守卫和被守的写法对一遍",
            nameAt < pillAt && pillAt < rowClose,
        )
        check(rowOpen < nameAt)

        val nameArgs = nearestCallArgs(text, "Text(", nameAt)
        assertTrue(
            "课名必须精确是 weight(1f, fill = false)：不带 weight 会吃满整行把胶囊挤出卡外，" +
                "fill=true 则把短标题行的胶囊顶到行尾：$nameArgs",
            nameArgs.contains("weight(1f, fill = false)"),
        )
        val pillArgs = nearestCallArgs(text, "Text(", pillAt)
        assertTrue(
            "胶囊文字必须锁 maxLines = 1：maxWidth 被压归零时它会逐字竖排，" +
                "把整行高度撑出短标题卡没有的那截空隙：$pillArgs",
            pillArgs.contains("maxLines = 1"),
        )
    }

    /** CourseGroupCard：课名锁一行，且包它的 Column 带着 weight 吃剩余宽 */
    @Test
    fun managementCardTitleIsEllipsedInsideWeightedColumn() {
        val file = File(findMainJavaDir(), "com/buaa/schedule/ui/course/CourseManagementScreen.kt")
        assertTrue("找不到 CourseManagementScreen.kt", file.isFile)
        val text = file.readText()
        val code = blankCommentsAndLiterals(text)
        val titleAt = code.indexOf("text = group.displayName")
        assertTrue("CourseGroupCard 的课名靶子没了", titleAt >= 0)
        val titleArgs = nearestCallArgs(text, "Text(", titleAt)
        assertTrue(
            "管理页课名不锁行数就会换行撑高卡片：$titleArgs",
            titleArgs.contains("maxLines") && titleArgs.contains("TextOverflow.Ellipsis"),
        )
        val columnArgs = nearestCallArgs(code, "Column(", titleAt)
        assertTrue(
            "那层 Column 必须带 weight(1f)——色标与两颗按钮都是固定宽，" +
                "课名列不吃剩余宽就会撞行：$columnArgs",
            columnArgs.contains("weight(1f)"),
        )
    }

    // ---- 源码解析：Row 的直接子节点 --------------------------------------------

    private class Child(val statement: String)

    /** 对每个带尾随 lambda 的 `Row(` 调用点，交出它花括号里的顶层子节点语句 */
    private fun forEachRowLambda(code: String, visit: (line: Int, children: List<Child>) -> Unit) {
        var from = 0
        while (true) {
            val at = code.indexOf("Row(", from)
            if (at < 0) return
            from = at + 1
            if (at >= 4 && code.substring(at - 4, at) == "fun ") continue
            val argsClose = matchingClose(code, at + 3) ?: continue
            val tail = code.substring(argsClose + 1)
            val offset = tail.indexOfFirst { !it.isWhitespace() }
            if (offset < 0 || tail[offset] != '{') continue
            val braceStart = argsClose + 1 + offset
            val bodyEnd = matchingClose(code, braceStart) ?: continue
            val children = topLevelChildren(code, braceStart + 1, bodyEnd)
            if (children.isNotEmpty()) {
                visit(code.substring(0, at).count { it == '\n' } + 1, children)
            }
        }
    }

    /**
     * [from until to) 花括号里的顶层语句：深度为 0 的换行处切一刀。
     *
     * 与 GlassSurfaceSingleChildTest 同一把刀法；那里剔 val/var 是因为 Box 子节点只数摆放物，
     * 这里不剔也不影响"课程名 Text 后面还有没有兄弟"的判断（Row 里没有夹在子节点中间的顶层 val）。
     */
    private fun topLevelChildren(code: String, from: Int, to: Int): List<Child> {
        val children = mutableListOf<Child>()
        val current = StringBuilder()
        var depth = 0
        var at = from
        while (at < to) {
            val ch = code[at]
            when (ch) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
            }
            if (ch == '\n' && depth == 0) {
                val statement = current.toString().trim()
                if (statement.isNotEmpty()) children += Child(statement)
                current.setLength(0)
            } else {
                current.append(ch)
            }
            at++
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty()) children += Child(tail)
        return children
    }

    /** 回找到包住 [marker] 的最近一个带尾随 lambda 的 `Row(`，交出它 (起点, 花括号终点) */
    private fun enclosingRow(code: String, marker: Int): Pair<Int, Int> {
        var open = code.lastIndexOf("Row(", marker)
        while (open >= 0) {
            val argsClose = matchingClose(code, open + 3)
            if (argsClose != null) {
                val tail = code.substring(argsClose + 1)
                val offset = tail.indexOfFirst { !it.isWhitespace() }
                if (offset >= 0 && tail[offset] == '{') {
                    val braceStart = argsClose + 1 + offset
                    val bodyEnd = matchingClose(code, braceStart)
                    if (bodyEnd != null && bodyEnd > marker) return open to bodyEnd
                }
            }
            open = if (open == 0) -1 else code.lastIndexOf("Row(", open - 1)
        }
        throw IllegalStateException("marker 处不在任何 Row 的花括号里，解析器该修了")
    }

    /**
     * 从 [marker] 回找最近的 `[callPrefix](`，交出它那一层括号里的参数原文。
     *
     * 只在三个小靶子上用（课名/胶囊/课名列），它们的参数里不含带括号的字符串字面量，
     * 所以直接在原文上配平；全仓扫描那一路走的是抹好字面量的代码，不经过这里。
     */
    private fun nearestCallArgs(code: String, callPrefix: String, marker: Int): String {
        val at = code.lastIndexOf(callPrefix, marker)
        check(at >= 0) { "找不到 $callPrefix：靶子写法变了" }
        val open = at + callPrefix.length - 1
        val close = matchingClose(code, open)
        check(close != null) { "$callPrefix 的括号配不上对" }
        return code.substring(open + 1, close)
    }

    // ---- 词法小工具（与 GlassSurfaceSingleChildTest 同一套） ---------------------

    private class Source(val relative: String, val text: String)

    private fun mainSources(): List<Source> {
        val root = findMainJavaDir()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${root.path} 下一个 .kt 都没有，路径不对", files.isNotEmpty())
        return files.map { Source(it.relativeTo(root).path.replace('\\', '/'), it.readText()) }
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

    /** 把注释与字符串/字符字面量的内容抹成空格，长度与换行位置一律不变 */
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
