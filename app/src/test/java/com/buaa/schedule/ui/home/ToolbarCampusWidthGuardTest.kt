package com.buaa.schedule.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶栏第二行「校区切换」的**宽度**守卫（#113 / T72）。
 *
 * 用户报的是效果：「周课表页签上校区切换整块不在屏上」。根因只有一行代码的形状：
 * `ScheduleToolbarRow` 里那枚可伸展的 `weight(1f)` 写在 `AnimatedVisibility` **内部**，
 * 于是这一枚是外层 `Row` 的**无权重**子节点 —— Row 按声明顺序量非加权子节点，
 * 簇先按整份可用宽（装机量到 1038px）吃掉，排在它后面的 `campusSlot` 拿到的
 * `maxWidth` 就是 0：不是"挤到边上"，是**量出来就没宽度**，所以今日页签（簇收起）它好好的、
 * 周课表页签整块消失。这类账读代码只看得见"写了 weight"，看不见"weight 挂在哪一层"，
 * 所以钉在源码文本上（本模块没有 Compose 运行时：无 Robolectric、无 ui-test，
 * 刀法照抄 [StatsEntryWiringGuardTest] 与 [DayViewOnScreenWiringGuardTest]：
 * 读源文件文本、匹配前先 `blankComments` 抹注释、括号配平取实参表与内容块、
 * 找不到锚点就抛、不用 assumeTrue 跳过）。
 *
 * 六档钉的是"谁有权伸展"这一件事，不是"这一排长什么样"：
 * 1. 伸展权只能挂在 `AnimatedVisibility` **自己**那一层（`modifier = Modifier.weight(…)`），
 *    且实参表里恰好一枚 `weight(` —— 多一枚就是有人把簇又撑满；
 * 2. `campusSlot` 排在簇**之后**、且不在簇内部：Row 先量它，它才拿得到固有宽；
 *    顺带钉住"校区不跟着簇一起收起"（那是 T69 之前那一版工具条的老形状）；
 * 3. 簇内部不许出现 `fillMaxWidth(`：那是同一个 0 宽缺陷换一件衣服（外层权重哪天被摘掉，
 *    这一句立刻把 #113 原样复现，而它现在什么也不多做）；
 * 4. 周次标题必须还能缩（`maxLines = 1` + `TextOverflow.Ellipsis`）：窄屏/大字号下
 *    这一排唯一能让宽的部件就是它，缩不动就轮到校区被顶出屏（装机实测：720px 宽时
 *    标题那一格从 449px 缩到 126px、校区仍是 233px 完整在屏）。
 * 5. `termSlot` 排在簇之前（同一件事的另一半：两端都先量，中间的份才是可让的那一份）；
 * 6. 调用点仍按 `selectedTab == 0` 决定簇画不画 —— 上面四档的前提是"哪一签有簇"，
 *    这一维漂了本文件全部按错的场景在核。
 */
class ToolbarCampusWidthGuardTest {

    /** ① 伸展权挂在簇自己那一层：`AnimatedVisibility` 的实参表里恰好一枚 `weight(` */
    @Test
    fun theClusterItselfCarriesTheRowWeight() {
        val row = toolbarRow()
        val (args, _) = weekNavCluster(row)
        val weights = Regex("""\.weight\(""").findAll(args).count()
        assertEquals(
            "周次簇的 AnimatedVisibility 实参表里 weight( 的枚数应当恰好一枚（0 枚 = #113 的缺陷本体：" +
                "簇变成无权重子节点并按整份可用宽铺开，后面的校区拿到 maxWidth = 0；" +
                "两枚 = 有人又在同一层撑了一次宽）：\n$args",
            1,
            weights,
        )
        assertTrue(
            "那一枚 weight 必须挂在 modifier 上（挂在 enter/exit 之类的表达式里等于没挂）：\n$args",
            Regex("""modifier\s*=\s*Modifier\.weight\(""").containsMatchIn(args),
        )
    }

    /** ② 校区排在簇之后、且不在簇内部：非加权子节点先被量到，才拿得到固有宽 */
    @Test
    fun campusIsLaidOutAfterTheWeightedCluster() {
        val row = toolbarRow()
        val (_, content) = weekNavCluster(row)
        val clusterEnd = row.indexOf(content) + content.length
        val invocations = Regex("""campusSlot\s*\?\.invoke\(\)""").findAll(row).toList()
        assertEquals(
            "campusSlot 在 ScheduleToolbarRow 里应当恰好调用一次（两次就是有一枚会被另一枚顶成 0 宽）：${invocations.size}",
            1,
            invocations.size,
        )
        val at = invocations[0].range.first
        assertTrue(
            "campusSlot 不再排在周次簇之后 —— Row 按声明顺序量非加权子节点，排到簇前面去就是" +
                "让校区先吃、簇再吃剩下的一整份，本守卫核的那笔账不成立了",
            at > clusterEnd,
        )
        assertFalse(
            "campusSlot 被搬进了 AnimatedVisibility 里面：切到今日页签它跟着簇一起没了（#113 之前的老形状）",
            content.contains("campusSlot"),
        )
    }

    /** ③ 簇内部不许自己撑满整行：那是 0 宽缺陷换一件衣服 */
    @Test
    fun clusterNeverClaimsTheRowByItself() {
        val row = toolbarRow()
        val (_, content) = weekNavCluster(row)
        assertFalse(
            "周次簇内部出现了 fillMaxWidth( —— 外层那枚 weight 一旦被人摘掉，这一句就把 #113 原样复现，" +
                "而它挂着外层权重时什么也不多做：\n" +
                content.lines().filter { it.contains("fillMaxWidth") }.joinToString("\n"),
            content.contains("fillMaxWidth("),
        )
    }

    /** ④ 周次标题必须还能缩：它是这一排唯一能让宽的部件 */
    @Test
    fun weekHeadlineCanAlwaysGiveItsWidthBack() {
        val row = toolbarRow()
        val (_, content) = weekNavCluster(row)
        val headline = textArgumentsFor(content, "weekHeadline")
        assertTrue("周次标题没有 maxLines = 1（换行就把整行顶高、把校区挤下去）：\n$headline", headline.contains("maxLines = 1"))
        assertTrue(
            "周次标题没有 overflow = TextOverflow.Ellipsis：窄屏/大字号下缩不动的就是校区" +
                "（装机实测 720px 宽时标题那一格从 449px 缩到 126px，校区仍是 233px 完整在屏）：\n$headline",
            headline.contains("overflow = TextOverflow.Ellipsis"),
        )
    }

    /** ⑤ 学期那一端排在簇之前：两端都先按固有宽量，中间的份才是可让的那一份 */
    @Test
    fun termIsLaidOutBeforeTheCluster() {
        val row = toolbarRow()
        val termAt = row.indexOf("termSlot?.invoke()")
        val clusterAt = row.indexOf("AnimatedVisibility(")
        assertTrue("termSlot 的调用点没了或不再排在周次簇之前：term@$termAt cluster@$clusterAt", termAt in 0 until clusterAt)
    }

    /** ⑥ 调用点仍按页签决定簇画不画：本文件其余五档都以此为前提 */
    @Test
    fun callSiteStillGatesTheClusterOnTheWeekTab() {
        val home = blankComments(source(HOME_SCREEN))
        val sites = invocationArgumentLists(home, "ScheduleToolbarRow")
        assertEquals("HomeScreen 里 ScheduleToolbarRow 的调用点数应当恰好一处：${sites.size}", 1, sites.size)
        val passed = Regex("""showWeekNav\s*=\s*([^\n,]*)""").findAll(sites[0]).map { it.groupValues[1].trim() }.toList()
        assertEquals(
            "第二行的周次簇不再由「selectedTab == 0」这一维决定画不画（今日页签不许有 ‹ 周次 ›，" +
                "周课表页签必须有校区）：$passed",
            listOf("selectedTab == 0"),
            passed,
        )
    }

    // ---- 源码核对工具（与各 *WiringGuardTest 同一套刀法）----

    /** `ScheduleToolbarRow` 的函数体（从签名到配平的右花括号，注释已抹） */
    private fun toolbarRow(): String = balancedBlock(blankComments(source(HOME_SCREEN)), "private fun ScheduleToolbarRow(")

    /**
     * 那一枚 `visible = showWeekNav` 的 AnimatedVisibility：返回（实参表, 内容块）。
     *
     * 认人靠 `showWeekNav` 而不是靠"第几枚 AnimatedVisibility"：这一文件里 AnimatedVisibility
     * 有六枚（冲突条、周次异动条……），按序号取哪天有人在上面插一枚就量错了对象。
     */
    private fun weekNavCluster(row: String): Pair<String, String> {
        for (match in Regex("""\bAnimatedVisibility\(""").findAll(row)) {
            val open = match.range.last
            val args = balancedArguments(row, open)
            if (!args.contains("showWeekNav")) continue
            val content = balancedBraces(row, row.indexOf('{', open + args.length - 1))
            return args to content
        }
        throw AssertionError("ScheduleToolbarRow 里没有 visible = showWeekNav 的 AnimatedVisibility：簇整个没了，本守卫要重新核")
    }

    /** 簇里 `text` 含 [needle] 那一枚 `Text(...)` 的实参表 */
    private fun textArgumentsFor(code: String, needle: String): String {
        for (match in Regex("""\bText\(""").findAll(code)) {
            val args = balancedArguments(code, match.range.last)
            if (args.contains(needle)) return args
        }
        throw AssertionError("簇里已经没有 text 含 $needle 的 Text(...)：标题换地方了，本守卫要跟着改")
    }

    /**
     * 每一次**调用**（`Name(`）的实参表，配平到与之匹配的右括号（含两端）。
     *
     * 跳过 `fun` 打头的那些：`private fun ScheduleToolbarRow(` 那枚声明含同名文本，
     * 按声明取会把「形参表」当成「实参表」量（假红比假绿更容易骗人）。
     */
    private fun invocationArgumentLists(code: String, name: String): List<String> {
        val out = ArrayList<String>()
        for (match in Regex("""\b${Regex.escape(name)}\(""").findAll(code)) {
            val at = match.range.first
            if (code.substring(0, at).trimEnd().endsWith("fun")) continue
            out += balancedArguments(code, match.range.last)
        }
        check(out.isNotEmpty()) { "一个 $name 调用点都没有：整条没了，本守卫要重新核" }
        return out
    }

    /** 从 [open] 那枚左括号起配平到与之匹配的右括号（含两端） */
    private fun balancedArguments(code: String, open: Int): String {
        var depth = 0
        for (index in open until code.length) {
            when (code[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return code.substring(open, index + 1)
                }
            }
        }
        throw IllegalStateException("第 $open 个字符之后的左括号没配平：${code.substring(open, minOf(open + 60, code.length))}")
    }

    /** 从 [signature] 之后第一个 `{` 起配平到对应右括号（含）；找不到锚点就抛，静默跳过等于没有守卫 */
    private fun balancedBlock(source: String, signature: String): String {
        val at = source.indexOf(signature)
        check(at >= 0) { "找不到 $signature：写法换过了，这条守卫要跟着改" }
        return balancedBraces(source, source.indexOf('{', at))
    }

    /** 从 [open] 那枚左花括号起配平到对应右括号（含两端） */
    private fun balancedBraces(source: String, open: Int): String {
        check(open >= 0) { "找不到左花括号" }
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
        }
        throw IllegalStateException("第 $open 个字符之后的左花括号没配平")
    }

    private fun source(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /** 把 `//` 与 `/* */` 注释抹成空格（字符串保留、长度与换行位置不变）：钉的是接线，不是白话 */
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
                    i = j
                }

                else -> i++
            }
        }
        return String(out)
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

    private companion object {
        const val HOME_SCREEN = "com/buaa/schedule/ui/home/HomeScreen.kt"
    }
}
