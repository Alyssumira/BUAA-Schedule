package com.buaa.schedule.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶栏「学期统计」入口的**接线**守卫（T69）。
 *
 * 本模块没有 Compose 运行时（无 Robolectric、无 ui-test），`HomeScreen` 里那颗胶囊
 * 点下去到底走不走得出去只能扫源码，刀法照抄 `SpocSignInEntryWiringGuardTest` 与
 * [DayViewOnScreenWiringGuardTest]：读源文件文本、匹配前先 `blankComments` 抹注释
 * （HomeScreen 的 KDoc 里就写着「不许给默认值 `= {}`」这句禁令本身，连注释一起扫会红在
 * 自己人手上）、实参表按括号配平取（懒正则会被 `onClick = { ... }` 里的 `) }` 提前截断）、
 * 找不到锚点就抛、不用 assumeTrue 跳过。
 *
 * 为什么这一族账值得单独钉：T41 那一次「学期统计进不去」的病因不是页面坏了，而是
 * **通往它的那条线是空的** —— 回调带着 `= {}` 默认值时，漏传一处编译器不响、界面照旧
 * 画得出那个入口、点下去什么也不发生。T69 把入口从设置页第三层搬到首页顶栏，
 * 恰好是这条线上多出来的一个新调用点，所以默认值那道门与两个落点（MainActivity 传的是
 * 真回调、胶囊的 onClick 连到参数）必须一起钉住。
 *
 * 另外 `StatsEntryPolicyTest` 的文件头把"装机实宽由谁钉"指向了本文件（内核那张表里
 * 全是合成整数，真宽度只活在调用点），所以第 ④ 档扫的是**测量那一半**：三档实宽与预算
 * 的四段事实全都来自测量点，一处裸整数常数都不许有。
 *
 * 钉四档（共 8 条）：
 * 1. `MainActivity` 的 `HomeScreen(...)` 调用点显式传的是那颗共用回调 `openStats`
 *    （不是字面量 `{}`、不是别的回调、也不许在调用点另抄一份 `navigate("stats")`），
 *    而 `openStats` 自己只有一份定义、跳的就是 `"stats"`；
 * 2. 顶栏那枚 `StatsEntryPill(...)` 的 `onClick = onOpenStats`（连到参数），且胶囊内部
 *    把它真的交给可点的容器（收了 onClick 却谁也不给 = 同一个 no-op 换了个更里面的一截）；
 * 3. `"stats"` 这条路由全仓**仍只有一处** `composable(...)` 定义（不许出现第二条复制的
 *    统计页路由），且 `HomeScreen` 的 `onOpenStats` 参数**没有默认值**；
 * 4. 宽度是量出来的不是估的：`statsEntryCandidates` / `statsEntryBudgetPx` 的实参表里
 *    一处裸整数都不许有，行宽与分段控件宽只能由 `onSizeChanged` 从画出来那一帧读，
 *    文字宽度只能出自 `TextMeasurer` 且向上取整到整 px。
 */
class StatsEntryWiringGuardTest {

    // ---- ① 调用点：MainActivity 传的是真回调 ----

    /**
     * ①-a 缺陷本体：`HomeScreen(...)` 的 `onOpenStats` 实参只许是共用回调 `openStats`。
     *
     * 三种漂法各拦一次：漏传（参数没有默认值 → 编译期就红，可一旦哪天有人把默认值加回来，
     * 这一档就是唯一的拦阻）、现场传字面量 `{}`（编译通过、点了没反应）、
     * 传另一颗回调（两处各写一份 `navigate("stats")` 迟早走岔，这正是 `openStats` 存在的理由）。
     */
    @Test
    fun homeScreenCallSitePassesTheSharedCallback() {
        val activity = blankComments(source(MAIN_ACTIVITY))
        val sites = callArgumentLists(activity, "HomeScreen(")
        assertEquals("MainActivity 里的 HomeScreen 调用点数应当恰好一处：${sites.size}", 1, sites.size)
        val args = sites[0]
        val passed = Regex("""onOpenStats\s*=\s*([A-Za-z_][\w.]*)""").findAll(args).map { it.groupValues[1] }.toList()
        assertEquals(
            "顶栏那枚胶囊接的不是 MainActivity 里那颗共用回调 openStats（= {} 或别的回调都算 no-op，" +
                "T41「界面上摆着入口、点下去没反应」就是这么漏出来的）。" +
                "调用点实际传的是：$passed\n实参表：$args",
            listOf("openStats"),
            passed,
        )
        assertFalse(
            "HomeScreen 的调用点现场又抄了一份 navigate(\"stats\")——路由名从此有两份，改一处漏一处：$args",
            Regex("""navigate\(\s*"stats"""").containsMatchIn(args),
        )
    }

    /** ①-b 那颗回调本身：全仓一份定义，跳的是 `"stats"` 这条路由 */
    @Test
    fun theSharedCallbackNavigatesToTheStatsRoute() {
        val activity = blankComments(source(MAIN_ACTIVITY))
        assertEquals(
            "val openStats 的定义处数应当恰好一处（多一处就是有人另起了一条通往统计页的通路）：",
            1,
            Regex("""val openStats: \(\) -> Unit =""").findAll(activity).count(),
        )
        val definition = balancedBlock(activity, "val openStats: () -> Unit =")
        assertTrue(
            "openStats 不再跳 stats 这条路由（设置页那一行与顶栏胶囊都靠它，漂了就是两处一起断）：\n$definition",
            Regex("""navigate\(\s*"stats"""").containsMatchIn(definition),
        )
    }

    // ---- ② 落点：顶栏那枚胶囊真的把点击接到了参数上 ----

    /**
     * ②-a `StatsEntryPill(...)` 的 `onClick` 必须是 `onOpenStats` 这颗参数。
     *
     * 顺带钉住"它在顶栏第一行、两个页签都看得见"这一件事：Crossfade 的那一支只画
     * 日期文字，胶囊若哪天被搬进去，今日页签上入口就没了（本卡的产品要求是两页签共用一行）。
     */
    @Test
    fun topBarPillClickIsWiredToTheParameter() {
        val home = blankComments(source(HOME_SCREEN))
        val sites = invocationArgumentLists(home, "StatsEntryPill")
        assertEquals(
            "HomeScreen 里 StatsEntryPill 的调用点数应当恰好一处（声明处不算）：${sites.size}",
            1,
            sites.size,
        )
        val args = sites[0]
        val passed = Regex("""onClick\s*=\s*([A-Za-z_][\w.]*)""").findAll(args).map { it.groupValues[1] }.toList()
        assertEquals(
            "顶栏胶囊的 onClick 没连到 HomeScreen 的参数 onOpenStats：画得出来、点下去没反应（T41 那一族）。" +
                "现在它连的是：$passed\n实参表：$args",
            listOf("onOpenStats"),
            passed,
        )
        val tabBranch = balancedBlock(home, "targetState = selectedTab == 0")
        assertFalse(
            "学期统计入口被搬进了 Crossfade 的某个页签分支里 —— 今日页签上它就没了：\n$tabBranch",
            tabBranch.contains("StatsEntryPill"),
        )
    }

    /**
     * ②-b 反"里面那一截断线"：胶囊收了 onClick 就得交给一个可点的容器。
     *
     * 这一档看着像废话，实则是本族最省事的一种死法：外层组件的 `onClick` 形参换了名字、
     * 或者内衬那一层把点击吞了，编译期一声不响，界面照旧画得出那颗胶囊。
     */
    @Test
    fun pillForwardsItsOnClickToAClickable() {
        val home = blankComments(source(HOME_SCREEN))
        val body = balancedBlock(home, "private fun StatsEntryPill(")
        assertTrue(
            "StatsEntryPill 的 onClick 没交给任何可点的容器（界面照旧画得出、点下去没反应）：\n$body",
            Regex("""onClick\s*=\s*onClick\b""").containsMatchIn(body),
        )
        assertTrue(
            "胶囊的档位不再来自参数 tier（宽度预算那一档就白量了）：\n$body",
            Regex("""text = tier\.label""").containsMatchIn(body),
        )
    }

    // ---- ③ 路由与默认值：不许有第二条，也不许有静默兜底 ----

    /**
     * ③-a `"stats"` 这条路由全仓只有一处 `composable(...)`。
     *
     * 扫的是 app/src/main/java **整棵树**而不是单个文件：这条卡要求的是"不许出现第二条
     * 复制的统计页路由"，只盯 MainActivity 就拦不住有人在别的 NavHost 里再注册一份。
     */
    @Test
    fun statsRouteIsDefinedExactlyOnceRepoWide() {
        val dir = findMainJavaDir()
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList().sortedBy { it.path }
        assertTrue("$dir 下一个 .kt 都没有，扫描范围取错了", files.isNotEmpty())
        val hits = files.mapNotNull { file ->
            val count = Regex("""composable\(\s*(?:route\s*=\s*)?"stats"""").findAll(blankComments(file.readText())).count()
            if (count > 0) "${file.name} x$count" else null
        }
        assertEquals(
            "composable(\"stats\") 全仓应当恰好一处定义（多一处就是有人复制了一条统计页路由，" +
                "两条路由会各自漂：back 栈、转场作用域、以后加的参数只补上一条）：$hits",
            listOf("MainActivity.kt x1"),
            hits,
        )
        val block = balancedBlock(blankComments(source(MAIN_ACTIVITY)), "composable(\"stats\")")
        assertTrue("那条路由注册了却没渲染 StatsScreen：\n$block", block.contains("StatsScreen("))
    }

    /**
     * ③-b `onOpenStats` 参数不许有默认值：`= {}` 一长回来，T41 那一族就全回来了。
     *
     * 只钉 `HomeScreen` 这一族。设置页那颗参数（`SettingsScreen` 的 `onOpenStats`）**改前就带着**
     * `= {}`，本卡不顺手修它 —— 它今天没有落成 no-op，因为 MainActivity 的三处调用点
     * （首页 + 设置根页 + 设置分类页）都显式传了 `onOpenStats = openStats`；摘掉那颗默认值
     * 会牵动 T41 那一族的其它回调（`onOpenCourseManagement` 同样带着默认值），那是另一张卡的账。
     */
    @Test
    fun openStatsParameterHasNoSilentDefault() {
        val home = blankComments(source(HOME_SCREEN))
        val parameter = Regex("""onOpenStats: \(\) -> Unit(\s*=\s*[^\n,]*)?""").find(home)
        check(parameter != null) { "HomeScreen 的参数表里已经没有 onOpenStats 了：入口换地方了，本守卫要跟着改" }
        assertFalse(
            "这颗回调又长回了默认值：漏传时编译器不响、顶栏照旧画得出那颗胶囊、点下去什么都不发生" +
                "（T41 的成因，KDoc 里那句「不许给默认值」就是它）。现在的参数是：${parameter.value.trim()}",
            parameter.value.contains("= {"),
        )
    }

    // ---- ④ 宽度是量出来的：内核那张表里全是合成整数，真宽度只活在调用点 ----

    /**
     * ④-a `statsEntryCandidates` / `statsEntryBudgetPx` 的实参表里一处裸整数都不许有。
     *
     * 这一档拦的是"内核改成吃常数"那种漂法：把 `fullLabelWidthPx = 240` 这样的数写进调用点，
     * 判据照样绿（内核只做减法与一次 `<=`），但那道 `<=` 比的已经不是屏上真实占掉的宽度 ——
     * 于是 T48 那一笔（长课名把状态胶囊整枚裁到卡外）换个尺寸重演。`StatsEntryPolicyTest`
     * 的文件头把"装机实宽由谁钉"指到本文件，指的就是这一档。
     */
    @Test
    fun ladderAndBudgetTakeOnlyMeasuredFacts() {
        val home = blankComments(source(HOME_SCREEN))
        val ladderSites = callArgumentLists(home, "statsEntryCandidates(")
        assertEquals("HomeScreen 里 statsEntryCandidates 的调用点数应当恰好一处：${ladderSites.size}", 1, ladderSites.size)
        val ladder = ladderSites[0]
        for (name in listOf("fullLabelWidthPx", "shortLabelWidthPx", "horizontalPaddingPx", "iconBlockPx", "minPillWidthPx")) {
            assertTrue("候选表的实参表少了 $name 这一段（少一档就是拿上一档的宽度凑数）：$ladder", ladder.contains("$name ="))
        }
        assertEquals(
            "两档文案的宽度必须各自由 statsEntryTextWidthPx 量一次（「学期统计」与「统计」各一枚）：$ladder",
            2,
            Regex("statsEntryTextWidthPx\\(").findAll(ladder).count(),
        )
        noConstantWidth("候选表 statsEntryCandidates", ladder)
        val budgetSites = callArgumentLists(home, "statsEntryBudgetPx(")
        assertEquals("HomeScreen 里 statsEntryBudgetPx 的调用点数应当恰好一处：${budgetSites.size}", 1, budgetSites.size)
        val budget = budgetSites[0]
        for (name in listOf("rowWidthPx", "segmentedWidthPx", "leftColumnWidthPx", "reservedGapPx")) {
            assertTrue("预算的实参表少了 $name 这一段（少一段减法就凭空多出一截宽度）：$budget", budget.contains("$name ="))
        }
        noConstantWidth("预算 statsEntryBudgetPx", budget)
        // 左列那一档取的是 Crossfade 两支的最大值：按当前页签那一支量，过渡帧里就把日期裁了
        val column = callArgumentLists(home, "maxOf(").firstOrNull { it.contains("statsEntryTextWidthPx(") }
            ?: throw AssertionError(
                "左列的自然宽度不再由 maxOf(...) 把几行实测取最大 —— 按「当前页签那一支」量，" +
                    "过渡帧里两支同时在屏上，那一档就会把日期裁掉半截",
            )
        assertEquals(
            "左列宽度必须由三行文字的实测取最大（周页签两行 + 今日页签一行），少一行那一行就漏量：\n$column",
            3,
            Regex("statsEntryTextWidthPx\\(").findAll(column).count(),
        )
    }

    /**
     * ④-b 两段邻居宽度只能从**画出来那一帧**读，文字宽度只能出自 `TextMeasurer`。
     *
     * `onSizeChanged` 与 `mutableStateOf<Int?>(null)` 是一对：初值为 null ⇒ 首帧预算答 null
     * ⇒ [planStatsEntry] 偏「少占」那一档。把初值改成任何一个数，就是拿一个没量过的宽度
     * 去承诺同行那一列（本仓为这类账栽过 T48 / T61 两次）。
     */
    @Test
    fun neighbourWidthsComeFromTheRenderedFrame() {
        val home = blankComments(source(HOME_SCREEN))
        for (name in listOf("topRowWidthPx", "segmentedWidthPx")) {
            assertTrue(
                "$name 不再由 onSizeChanged 从画出来那一帧读宽度（改回按常数承诺就是 T48）：\n" +
                    home.lines().filter { it.contains(name) }.joinToString("\n"),
                Regex("""onSizeChanged\s*\{\s*$name\s*=\s*it\.width""").containsMatchIn(home),
            )
            assertTrue(
                "$name 的初值必须是「还没量到」的 null：兜一个常数就等于首帧按那个数向同行要宽度：\n" +
                    home.lines().filter { it.contains("var $name") }.joinToString("\n"),
                Regex("""var $name by remember \{ mutableStateOf<Int\?>\(null\) \}""").containsMatchIn(home),
            )
        }
        assertEquals(
            "顶栏这一段只许造一枚 TextMeasurer（两处各造就会各信各的字体测量值）：",
            1,
            invocationArgumentLists(home, "rememberStatsEntryTextMeasurer").size,
        )
        val measurer = balancedBlock(home, "private fun rememberStatsEntryTextMeasurer(")
        assertTrue(
            "measurer 不再按 (resolver, density, direction) 现造 —— 换字体或系统字号变了测量值不会跟着换：\n$measurer",
            Regex("""\bTextMeasurer\(""").containsMatchIn(measurer) && measurer.contains("remember(resolver"),
        )
        val width = balancedBlock(home, "private fun statsEntryTextWidthPx(")
        assertTrue(
            "量出来的宽度没有向上取整到整 px（预算与实宽最后就是在整数上比大小，两头各留半 px 足够裁掉半个字）：\n$width",
            width.contains("ceil("),
        )
    }

    /** 实参表里不许出现 `= 123` 这样的裸整数宽度（chrome 只能从 DesignTokens 换算，见调用点那段注释） */
    private fun noConstantWidth(what: String, args: String) {
        val offenders = Regex("""=\s*-?\d""").findAll(args).map { it.value.trim() }.toList()
        assertTrue(
            "$what 的实参表里出现了裸整数常数（宽度必须是调用点量出来的整数，猜一个数就是下一笔 T48）：$offenders\n$args",
            offenders.isEmpty(),
        )
    }

    // ---- 源码核对工具（与各 *WiringGuardTest 同一套刀法）----

    private fun source(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /**
     * 每一次**调用**（`Name(`）的实参表，配平到与之匹配的右括号（含两端）。
     *
     * 与 [callArgumentLists] 的差别只有一处：`private fun StatsEntryPill(` 那枚声明也含
     * `StatsEntryPill(`，按声明取会把「形参表」当成「实参表」量，`onClick = onOpenStats`
     * 那条断言就会红在一个不存在的调用点上（假红比假绿更容易骗人）。这里跳过 `fun` 打头的那些。
     */
    private fun invocationArgumentLists(code: String, name: String): List<String> {
        val out = ArrayList<String>()
        for (match in Regex("""\b${Regex.escape(name)}\(""").findAll(code)) {
            val at = match.range.first
            if (code.substring(0, at).trimEnd().endsWith("fun")) continue
            out += balancedArguments(code, match.range.last)
        }
        return out
    }

    /** 每一次调用的实参表（不区分声明）；一个都没有就抛 —— 静默返回空表等于这条守卫不跑 */
    private fun callArgumentLists(code: String, call: String): List<String> {
        require(call.endsWith("(")) { "$call 不是以左括号结尾的调用锚点" }
        val out = ArrayList<String>()
        var from = 0
        while (true) {
            val at = code.indexOf(call, from)
            if (at < 0) break
            out += balancedArguments(code, at + call.length - 1)
            from = at + call.length
        }
        check(out.isNotEmpty()) { "一个 $call 调用点都没有：入口整条没了，本守卫要重新核" }
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
        val open = source.indexOf('{', at)
        check(open >= at) { "$signature 之后找不到左括号" }
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(at, index + 1)
                }
            }
        }
        throw IllegalStateException("$signature 的花括号没配平")
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
        const val MAIN_ACTIVITY = "com/buaa/schedule/MainActivity.kt"
        const val HOME_SCREEN = "com/buaa/schedule/ui/home/HomeScreen.kt"
    }
}
