package com.buaa.schedule.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶栏第二行「校区切换」的**位置**守卫（#118 / T76）。
 *
 * 台账 #113（T72）之后，周课表页签上校区回到了屏内，但改前装机实测出另一笔账：
 * 切一次页签，「校区切换」的文字节点从 `x=42` 跳到 `x=847` —— **横跳 805px**。
 * 根因还是"谁有权伸展"，只是方向反过来：`AnimatedVisibility` 在 `visible=false`
 * 且退场动画跑完之后**整枚退出组合**，它那份 `weight(1f)` 跟着没了 ⇒ 这一行只剩
 * 学期与校区两枚无权重子件、按默认 `Start` 挤在左端。
 *
 * 本模块没有 Compose 运行时（无 Robolectric、无 ui-test），这类布局账只能钉源码形状，
 * 刀法照抄 [ToolbarCampusWidthGuardTest] 与 [StatsEntryWiringGuardTest]：读源文件文本、
 * 匹配前先 `blankComments` 抹注释、括号配平取实参表与内容块、找不到锚点就抛、不用 assumeTrue。
 *
 * 钉七档（①–④ 钉形状，⑤ 钉算术，⑥ 反向自证，⑦ 钉调用点前提）：
 * 1. 行的**直接子件**里恰好一枚带 `weight`，且那一枚不许自带进出条件（`visible =` / `if (`）
 *    —— 伸展权必须由一枚**常驻**件拿着，这正是 T72 那一版缺的那一维；
 * 2. 那枚常驻件的内容里必须有周次簇（`AnimatedVisibility` + `showWeekNav`）：
 *    簇在时槽宽 = 簇的布局位，#113 那笔账一分不动；
 * 3. 校区是行的**最后一枚**直接子件、自身无权重也无门槛；学期那一端排在最前，
 *    学期与校区之间就是①那枚常驻槽；
 * 4. 行内不许对 `termSlot` 判空 —— 装了教务会话的真机是「学期 + 校区」两枚的形状；
 * 5. 把①–④读出的形状喂进 Row 分布模型（非加权件按声明顺序吃固有宽、加权件吃掉全部余量）：
 *    1080/720 两档宽 × 学期槽在与不在 × 两个页签，共八格，校区左缘全等，
 *    且恒等于「行宽 − 校区固有宽」= 贴右端，且拿得到自己的 233px（#113 不回归）；
 * 6. 反向自证：同一枚模型喂 T72 那一版（加权的就是簇本身）算出 **805px** 横跳，
 *    喂 #113 那一版（权重在簇内部）算出校区可用宽 **0** ⇒ ⑤ 不是同义反复；
 * 7. 调用点两端的实参不许按页签分叉（否则"同一个控件跳位置"这笔账的前提就没了）。
 */
class ToolbarCampusAnchorGuardTest {

    // ---- ①–③ 形状：伸展权在谁手上 ----

    /**
     * ① 行的直接子件里恰好一枚加权，且那一枚不随页签进出。
     *
     * 0 枚 = 余量没人吃，切到今日页签时校区跟着回到行首（#118 的本体）；
     * 2 枚 = 余量被摊薄（各 1/2 时 1080 宽下周次标题那一格从 449px 只剩 46px，
     * 720 宽下两枚箭头直接压到校区身上 —— 那是 #113 换了一副面孔）；
     * 加权那枚自带 `visible` = 它早晚会被摘掉，#118 就还在（T72 那一版正是这个形状）。
     */
    @Test
    fun exactlyOneWeightedDirectChildAndItIsUnconditional() {
        val children = directChildShapes()
        val weighted = children.filter { it.weighted }
        assertEquals(
            "顶栏第二行带 weight 的直接子件应当恰好一枚（多一枚就是有人在摊薄这一排的余量）。" +
                "实读到的直接子件：${children.map { it.name + if (it.weighted) "(加权)" else "" }}",
            1, weighted.size,
        )
        val slot = weighted[0]
        assertFalse(
            "唯一那枚加权件自带进出条件（${slot.name}）：AnimatedVisibility 收起后整枚退出组合、" +
                "权重视同没有 ⇒ 校区从右端跳回行首（#118，改前装机横跳 805px）：${slot.args}",
            slot.gated,
        )
        assertFalse(
            "加权件的实参表里不许按页签决定份量（`weight(if …)` 那种补位写法）：${slot.args}",
            Regex("""weight\([^)]*(?:if\s*\(|showWeekNav)""").containsMatchIn(slot.args),
        )
    }

    /** ② 那枚常驻加权槽就是周次簇的家：簇在时槽宽 = 簇的布局位，#113 的账不动 */
    @Test
    fun thePermanentSlotIsHomeOfTheWeekNavCluster() {
        val slot = weightedSlot()
        assertTrue("常驻加权槽里已经没有 AnimatedVisibility（簇搬走 ⇒ 这一排的余量算法要重算）：${slot.name}", slot.content.contains("AnimatedVisibility("))
        assertTrue(
            "槽里那枚 AnimatedVisibility 不再由 showWeekNav 决定画不画：本守卫核的是" +
                "「簇没了、槽还在」这一件事，前提变了就得跟着改",
            slot.content.contains("showWeekNav"),
        )
        assertFalse(
            "加权槽整体被罩在 if/when 里：那它同样会整枚退出组合，①的『常驻』就没了",
            Regex("""^\s*(?:if|when)\s*\(""").containsMatchIn(slot.text),
        )
    }

    /** ③ 校区排在行尾、学期排在行首，两枚都是无条件无权重的固有宽件 */
    @Test
    fun campusEndsTheRowAndTermStartsIt() {
        val children = directChildShapes()
        val campus = children.last()
        assertEquals(
            "顶栏第二行最后一枚直接子件应当是校区（它后面再多一枚无权重件，右端就被顶偏）：${campus.name}",
            "campusSlot", campus.name,
        )
        assertFalse("校区那一端不该有权重（就会跟簇抢余量）：${campus.args}", campus.weighted)
        assertFalse("校区不该跟着簇一起进出：切到今日页签就没有校区筛选入口（#113 之前的老账）", campus.gated)
        val term = children.first()
        assertEquals("顶栏第二行第一枚直接子件应当是学期：${term.name}", "termSlot", term.name)
        assertFalse("学期那一端不该有权重：${term.args}", term.weighted)
        val slotIndex = children.indexOfFirst { it.weighted }
        assertTrue(
            "学期与校区之间必须有①那枚常驻加权件（槽在两枚端件之外 ⇒ 两端先吃固有宽）：${children.map { it.name }}",
            slotIndex > 0 && slotIndex < children.lastIndex,
        )
    }

    // ---- ④ 行内不许对学期槽判空 ----

    /**
     * ④ 这一排的分布不许分「学期槽在/不在」两种形状来算。
     *
     * ⚠️ 装机取证只覆盖到 `termSlot = null` 那一档（这台 AVD 没有教务会话，
     * `buaaTermOptions` 除了教务 WebView 页面内 fetch 没有第二个写入点，注不进来），
     * 真机则是「学期切换 + 校区切换」两枚的形状。只要行内不判空、伸展权又被常驻件拿着，
     * 两档走的就是同一条分布算式 —— ⑤ 把两种形状都算了一遍。
     */
    @Test
    fun rowNeverBranchesOnTheTermSlot() {
        val body = toolbarRowBody(blankComments(source(HOME_SCREEN)))
        val mentions = Regex("""termSlot""").findAll(body).toList()
        assertEquals("ScheduleToolbarRow 体内 termSlot 只许出现一次（就是那一端本身）：${mentions.size}", 1, mentions.size)
        val said = run {
            val from = mentions[0].range.first
            val lineStart = body.lastIndexOf('\n', from) + 1
            val lineEnd = body.indexOf('\n', from).let { if (it < 0) body.length else it }
            body.substring(lineStart, lineEnd).trim()
        }
        assertEquals("那一处必须是原样的 termSlot?.invoke()：判空分支 = 按学期槽在不在换形状", "termSlot?.invoke()", said)
        val whole = blankComments(source(HOME_SCREEN))
        assertFalse(
            "出现了 `if (termSlot` / `termSlot != null` 这种判空写法",
            Regex("""if\s*\(\s*termSlot|termSlot\s*!=\s*null""").containsMatchIn(whole),
        )
    }

    // ---- ⑤ 分布模型：八格全等 ----

    /**
     * ⑤ 把源码读出的形状喂进 Row 分布模型：校区左缘与页签、与学期槽、与屏宽档无关。
     *
     * 模型只有两句话（也就是 Compose `Row` 的算法里本行用得上的那两句话）：非加权子件按
     * 声明顺序各拿 `min(固有宽, 剩下)`，加权子件再按份数分掉余下的全部。本行只有一枚加权件
     * （①钉着）⇒ 它的份就是全部余量，整数算术、无舍入。
     * 固有宽取装机实测：校区热区 233px（「校区切换」labelLarge 四枚 CJK 144 + 箭头 18dp 47
     * + `spaceS`×2 = 42），学期按钮同字面量长 ⇒ 同 233px；行内容宽 1080−42 = 1038、720−42 = 678。
     */
    @Test
    fun campusLeftEdgeIsTabIndependentAndTermIndependent() {
        val shape = directChildShapes().map { Slot(it.name, it.weighted, it.gated) }
        assertEquals("①–③ 读出的直接子件顺序不是「学期 / 常驻槽 / 校区」：${shape.map { it.name }}", 3, shape.size)
        for (rowWidth in listOf(1038, 678)) {
            for (termWidth in listOf(0, 233)) {
                val today = layoutRow(rowWidth, shape, onWeekTab = false, termWidth = termWidth)
                val week = layoutRow(rowWidth, shape, onWeekTab = true, termWidth = termWidth)
                val a = today.getValue("campusSlot")
                val b = week.getValue("campusSlot")
                assertEquals(
                    "两个页签之间校区左缘横跳：行宽 $rowWidth、学期槽宽 $termWidth ⇒ ${a.x1} vs ${b.x1}（#118 改前是 805px）",
                    b.x1, a.x1,
                )
                assertEquals(
                    "校区不再贴右端（应 = 行宽 − 校区固有宽 + 内容左起点 = ${rowWidth - CampusPx + ContentLeftPx}）",
                    rowWidth - CampusPx + ContentLeftPx, a.x1,
                )
                assertEquals("校区拿不到自己的固有宽（#113 的账）：", CampusPx, a.x2 - a.x1)
                assertTrue(
                    "校区右缘越出屏外：x2 = ${a.x2} > ${rowWidth + ContentLeftPx}",
                    a.x2 <= rowWidth + ContentLeftPx,
                )
            }
        }
    }

    // ---- ⑥ 反向自证：同一枚模型要能算出历史上那两笔账 ----

    /** ⑥-a T72 那一版（加权的就是簇本身）：模型算出改前装机实测的 805px 横跳 */
    @Test
    fun thePreviousShapeStillJumpedHorizontally() {
        val shape = listOf(Slot("termSlot", false, false), Slot("weekNav", true, true), Slot("campusSlot", false, false))
        val today = layoutRow(1038, shape, onWeekTab = false, termWidth = 0)
        val week = layoutRow(1038, shape, onWeekTab = true, termWidth = 0)
        // 改前实测：今日页签「校区切换」文字节点 x=42、周课表页签 x=847 ⇒ 热区左缘 21 与 826
        assertEquals(21, today.getValue("campusSlot").x1)
        assertEquals(826, week.getValue("campusSlot").x1)
        assertEquals("T72 那一版两签之间的横跳应当是装机量到的 805px", 805, week.getValue("campusSlot").x1 - today.getValue("campusSlot").x1)
        // 学期槽在场那一档（真机）同样跳，只是跳得少 233px —— 卡面说的"两枚挤在左端"
        val weekWithTerm = layoutRow(1038, shape, onWeekTab = true, termWidth = 233)
        val todayWithTerm = layoutRow(1038, shape, onWeekTab = false, termWidth = 233)
        assertEquals(572, weekWithTerm.getValue("campusSlot").x1 - todayWithTerm.getValue("campusSlot").x1)
    }

    /** ⑥-b #113 那一版（权重在簇内部，簇自身无权重）：模型算出校区可用宽 0 */
    @Test
    fun theShapeBeforeTheWidthFixCrushedTheCampus() {
        val shape = listOf(Slot("termSlot", false, false), Slot("weekNav", false, false), Slot("campusSlot", false, false))
        val week = layoutRow(1038, shape, onWeekTab = true, termWidth = 0, fillNames = setOf("weekNav"))
        val campus = week.getValue("campusSlot")
        assertEquals("周课表页签上校区拿到的可用宽应当是 0（#113 的缺陷本体）", 0, campus.x2 - campus.x1)
        assertEquals(1059, campus.x1)
        assertTrue("校区整块落在内容区右缘之外才对得上 #113", campus.x1 >= 1038 + ContentLeftPx)
    }

    // ---- ⑦ 调用点前提：两端不按页签分叉 ----

    /** ⑦ 学期与校区两端的实参都不许出现 `selectedTab`：两枚在两个页签上是同一件东西 */
    @Test
    fun callSitePassesBothEndsOnEveryTab() {
        val home = blankComments(source(HOME_SCREEN))
        val sites = invocationArgumentLists(home, "ScheduleToolbarRow")
        assertEquals("HomeScreen 里 ScheduleToolbarRow 的调用点数应当恰好一处：${sites.size}", 1, sites.size)
        val args = sites[0]
        val termArg = namedArgumentBlock(args, "termSlot")
        val campusArg = namedArgumentBlock(args, "campusSlot")
        assertFalse("学期槽开始按页签给不给 ⇒ ④⑤ 那笔「两档同式」的账要重算：$termArg", termArg.contains("selectedTab"))
        assertFalse("校区开始按页签给不给 ⇒ 这一排在两个页签上不是同一件东西：$campusArg", campusArg.contains("selectedTab"))
        assertTrue("校区实参里已经没有 CampusPickerButton：$campusArg", campusArg.contains("CampusPickerButton"))
        assertTrue("学期实参不再按教务学期列表决定（装机这一档恒为空，见④的说明）：$termArg", termArg.contains("termOptions.isNotEmpty()"))
    }

    // ---- 行分布模型（⑤⑥ 共用；只长在测试里，不进生产码）----

    /** 一枚行内子件：名字 + 有没有权重 + 会不会整枚退出组合（①–③ 从源码读出的形状） */
    private class Slot(val name: String, val weighted: Boolean, val gated: Boolean)

    /** 一行摆完之后的落位（屏上像素；内容左起点 = [ContentLeftPx]） */
    private class Placed(val x1: Int, val x2: Int)

    /**
     * Compose `Row` 的横向分布，只取本行用得上的那两条规则：非加权子件按声明顺序拿
     * `min(固有宽, 剩下)`，加权子件按份数分掉余下的全部（①钉死了"只有一枚加权件"⇒ 整数无舍入）。
     *
     * @param onWeekTab 周课表页签？`gated` 那一枚（周次簇）只在这一签在场
     * @param termWidth 学期槽固有宽，0 = 这台 AVD 那一档（没有教务学期列表 ⇒ `termSlot = null`）
     */
    private fun layoutRow(
        rowWidthPx: Int,
        shape: List<Slot>,
        onWeekTab: Boolean,
        termWidth: Int,
        fillNames: Set<String> = emptySet(),
    ): Map<String, Placed> {
        val children = shape.filterNot { it.gated && !onWeekTab }
        var remaining = rowWidthPx
        val widths = LinkedHashMap<String, Int>()
        val weightedNames = ArrayList<String>()
        for (slot in children) {
            val intrinsic = when (slot.name) {
                "termSlot" -> termWidth
                "campusSlot" -> CampusPx
                else -> if (slot.name in fillNames) FillPx else 0
            }
            if (slot.weighted) {
                weightedNames += slot.name
                widths[slot.name] = 0
            } else {
                val taken = minOf(intrinsic, remaining.coerceAtLeast(0))
                widths[slot.name] = taken
                remaining -= taken
            }
        }
        val share = if (weightedNames.isEmpty()) 0 else remaining.coerceAtLeast(0) / weightedNames.size
        for (name in weightedNames) widths[name] = share
        var x = ContentLeftPx
        val out = LinkedHashMap<String, Placed>()
        for (slot in children) {
            val width = widths.getValue(slot.name)
            out[slot.name] = Placed(x, x + width)
            x += width
        }
        return out
    }

    // ---- 源码核对工具（与 ToolbarCampusWidthGuardTest 同一套刀法）----

    /** 行内一枚直接子件：整段文本、名字、实参表、内容块；加权/门槛判据只看它自己那一层 */
    private class Child(val text: String, val name: String, val args: String, val content: String) {
        val weighted get() = Regex("""\.weight\(""").containsMatchIn(args)
        val gated get() = Regex("""visible\s*=""").containsMatchIn(args) ||
            Regex("""^\s*(?:if|when)\s*\(""").containsMatchIn(text)
    }

    /** `ScheduleToolbarRow` 里那枚外层 `Row` 的内容块（注释已抹） */
    /** ①–③ 取同一枚加权直接子件；没有加权件就抛（静默跳过等于没有守卫） */
    private fun weightedSlot(): Child {
        val children = directChildShapes()
        return children.firstOrNull { it.weighted }
            ?: throw AssertionError("顶栏第二行没有一枚加权的直接子件（#118 的本体）：" +
                "①钉的那一枚常驻槽没了，余量没人吃 ⇒ 校区跟着簇回到行首。" +
                "直接子件：${children.map { it.name + if (it.gated) "(随页签进出)" else "" }}")
    }

    private fun toolbarRowBody(source: String): String {
        val function = balancedBlock(source, "private fun ScheduleToolbarRow(")
        val open = Regex("""\bRow\(""").find(function)
            ?: throw AssertionError("ScheduleToolbarRow 里已经没有 Row(...)：这一排整个换写法了，本守卫要跟着改")
        val args = balancedArguments(function, open.range.last)
        check(args.contains("fillMaxWidth(")) { "外层 Row 不再 fillMaxWidth，取错了对象：$args" }
        val brace = function.indexOf('{', open.range.last + args.length)
        check(brace >= 0) { "外层 Row 没有内容块：$args" }
        return balancedBraces(function, brace)
    }

    /** 外层 Row 的**直接**子件：只在括号深度 0 处按行切段 ⇒ 嵌套里的件不会混进来 */
    private fun directChildShapes(): List<Child> {
        val block = toolbarRowBody(blankComments(source(HOME_SCREEN)))
        val inner = block.substring(1, block.length - 1)
        val segments = ArrayList<String>()
        var depth = 0
        var lineStart = 0
        var index = 0
        while (index < inner.length) {
            val c = inner[index]
            if (c == '"' || c == '\'') {
                val quote = c
                var j = index + 1
                while (j < inner.length) {
                    when {
                        inner[j] == '\\' -> j += 2
                        inner[j] == quote -> { j++; break }
                        inner[j] == '\n' -> break
                        else -> j++
                    }
                }
                index = j
                continue
            }
            when (c) {
                '(', '{', '[' -> depth++
                ')', '}', ']' -> {
                    depth--
                    check(depth >= 0) { "内容块在第 $index 个字符处多出一枚右括号" }
                }

                '\n' -> if (depth == 0) {
                    segments += inner.substring(lineStart, index)
                    lineStart = index + 1
                }
            }
            index++
        }
        if (lineStart < inner.length) segments += inner.substring(lineStart)
        val children = segments.filter { it.isNotBlank() }.map { segment ->
            val paren = segment.indexOf('(')
            val head = segment.indexOf('{')
            check(paren > 0 || head > 0) { "行内出现一枚读不出名字的子件：[${segment.trim()}]" }
            // `Row {` 这种不带实参表的直接子件：名字取到左花括号为止，别把里面的调用当成它的实参表
            if (head in 1 until paren) {
                Child(
                    text = segment,
                    name = segment.substring(0, head).trim(),
                    args = "",
                    content = balancedBraces(segment, head),
                )
            } else {
                val args = balancedArguments(segment, paren)
                val brace = segment.indexOf('{', paren + args.length - 1)
                Child(
                    text = segment,
                    name = segment.substring(0, paren).trim().substringBefore("?."),
                    args = args,
                    content = if (brace < 0) "" else balancedBraces(segment, brace),
                )
            }
        }
        check(children.isNotEmpty()) { "顶栏第二行一枚直接子件都没读到" }
        return children
    }

    /** 具名实参那一段：从 `name =` 起，到同一深度上的下一枚逗号或实参表末尾 */
    private fun namedArgumentBlock(args: String, name: String): String {
        val at = args.indexOf("$name =")
        check(at >= 0) { "调用点没有 $name 这一枚实参：$args" }
        var depth = 0
        for (index in at until args.length) {
            when (args[index]) {
                '(', '{', '[' -> depth++
                ')', '}', ']' -> depth--
                ',' -> if (depth == 0) return args.substring(at, index)
            }
        }
        return args.substring(at)
    }

    /** 每一次**调用**（`Name(`）的实参表；`fun` 打头的声明跳过（按声明取会把形参表当实参表量） */
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

        /** 校区按钮固有宽：装机实测 233px（见 ⑤ 的 KDoc） */
        const val CampusPx = 233

        /** 行内容左起点：`spaceS` = 8dp = 21px @420dpi */
        const val ContentLeftPx = 21

        /** 无权重却带着加权后代的件：按父级给的整份铺开（#113 那一版的簇） */
        const val FillPx = 1 shl 20
    }
}
