package com.buaa.schedule.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「一条页头条、一个主人」的**接线**守卫（T80）。
 *
 * 本模块没有 Compose 运行时（无 Robolectric、无 ui-test），"跳转期间那条带上到底有几套字"
 * 只能扫源码，刀法照抄 [StatsEntryWiringGuardTest] 与
 * [com.buaa.schedule.ui.stats.StatsViewModelScopeGuardTest]：读源文件文本、匹配前先
 * `blankComments` 抹注释（本卡 KDoc 里就写着「不许给默认值」这类禁令本身，连注释一起扫会红在
 * 自己人手上）、实参表按括号配平取、找不到锚点就抛、不用 assumeTrue 跳过。
 *
 * 为什么这一族账值得单独钉：用户原话是「周课表页签点击学期统计后应直接跳转且顶栏只显示返回和
 * 学期统计，避免页面顶部先显示首页页头再切入统计页、导致页头文字跳动」。装机量的形状是
 * 首页第一行 y=158..284、统计页页头 y=157..283（同一条带、同 126px 实高），而 SLIDE 转场
 * 那 260ms 两页同时在场 —— 于是"改好了"与"改坏了"在截图上只差那一瞬，只在源码层钉得住。
 *
 * 钉五档（共 9 条）：
 * 1. 页头条的**容器全仓一份**（`ScheduleHeaderBand` 定义一处、两处调用），带子的内衬那一段
 *    字面量全仓只许出现一次 —— 两条页头各写各的内衬就是这次返工的病根；
 * 2. 统计页不再自带页头（`GlassTopBar` 从这一页摘掉），但 `Text("返回")` 这个字面量必须还在：
 *    它是 Baseline Profile 交互 CUJ 的 uiautomator 锚点，换成图标那条跳不红、只静默点空；
 * 3. 主人**只在 AppNavHost 之上判一次**：两个页面自己不许读路由、不许碰 NavController；
 * 4. 两枚 `headerBandOnScreen` 参数都不许长回默认值，两个调用点都必须吃到从同一枚
 *    `headerBandOwner` 算出来的那一位；
 * 5. 不在台上那一页的占位高度只能来自**量到的高度**（onSizeChanged + `mutableStateOf<Int?>(null)`），
 *    并且带子的闸门是"干脆不求值 content"，不是"画了但透明"（后者在语义树里仍是两套字）。
 */
class HeaderBandWiringGuardTest {

    // ---- ① 带的几何只有一个来源 ----

    /** ①-a `ScheduleHeaderBand` 全仓一份定义、两处调用（首页第一行 + 统计页页头） */
    @Test
    fun bandIsDefinedOnceAndUsedByBothPages() {
        val definitions = filesUnderMain().mapNotNull { file ->
            val n = Regex("""fun ScheduleHeaderBand\(""").findAll(blankComments(file.readText())).count()
            if (n > 0) "${file.name} x$n" else null
        }
        assertEquals(
            "ScheduleHeaderBand 的定义全仓应当恰好一处（两处定义就是两条各自会漂的页头条）：$definitions",
            listOf("HomeScreen.kt x1"), definitions,
        )
        val uses = filesUnderMain().mapNotNull { file ->
            val n = invocationArgumentLists(blankComments(file.readText()), "ScheduleHeaderBand").size
            if (n > 0) "${file.name} x$n" else null
        }.sorted()
        assertEquals(
            "页头条的调用点应当恰好两处（首页第一行 + 统计页页头）。多一处就是要多看一处『这一页的带归谁』：$uses",
            listOf("HomeScreen.kt x1", "StatsScreen.kt x1"), uses,
        )
    }

    /**
     * ①-b 带子那一段内衬（start=spaceL / end=spaceS / top=spaceXS）全仓只许出现一次。
     *
     * 这一档拦的是"第二条页头又自己抄一遍 padding"：两条带的**实高**本来就同为 126px
     * （48dp 触控下限），差别全在内衬 —— 改前首页 top=spaceXS(10px)、统计页是玻璃板的
     * contentPadding=spaceS(21px)，于是上下沿差 1px，而"跳转期间带的上下沿不许动"这条
     * 验收要的就是这一px 都不许有。
     */
    @Test
    fun bandPaddingHasExactlyOneOwnerRepoWide() {
        val pattern = Regex(
            """start = DesignTokens\.spaceL,\s*end = DesignTokens\.spaceS,\s*top = DesignTokens\.spaceXS,""",
        )
        val hits = filesUnderMain().mapNotNull { file ->
            val n = pattern.findAll(blankComments(file.readText())).count()
            if (n > 0) "${file.name} x$n" else null
        }
        assertEquals(
            "页头条那一段内衬只能写在 ScheduleHeaderBand 里一次（抄第二遍就是两条会各漂的页头）：$hits",
            listOf("HomeScreen.kt x1"), hits,
        )
    }

    // ---- ② 统计页的页头：换容器，但锚点一个字都不许动 ----

    /** ②-a 这一页不再自带 GlassTopBar（第二条页头的旧形状不许长回来） */
    @Test
    fun statsPageNoLongerDrawsItsOwnTopBar() {
        val stats = blankComments(source(STATS_SCREEN))
        assertFalse(
            "统计页又挂回自己的 GlassTopBar 了：那条玻璃板的内衬与首页第一行不同源，" +
                "跳转期间就是两层页头交叠（本卡的病根）",
            Regex("""\bGlassTopBar\(""").containsMatchIn(stats),
        )
        val sites = invocationArgumentLists(stats, "ScheduleHeaderBand")
        assertEquals("统计页这一处 ScheduleHeaderBand 调用点应当恰好一处：${sites.size}", 1, sites.size)
        assertTrue(
            "统计页的页头条没吃 headerBandOnScreen —— 那就等于这一页永远在画自己的页头：" + sites[0],
            sites[0].contains("drawnOnScreen = headerBandOnScreen"),
        )
    }

    /**
     * ②-b `Text("返回")` 这个字面量必须还在统计页上，而且 `GlassTopBar` 里那一份也要在。
     *
     * 它是 Baseline Profile 交互 CUJ 的 uiautomator 锚点：
     * `InteractionBaselineProfileGenerator.kt` 的 `private const val LABEL_BACK = "返回"`，
     * 等待条件是 `device.findObject(inText(LABEL_BACK))`。改成箭头图标以后那条跳**不会红**，
     * 只会 `waitUntil` 空转到超时 —— 基准测试不会为这种漂移报错，所以只能在这里钉。
     */
    @Test
    fun backLabelStaysATextLiteralOnBothHeaders() {
        val stats = blankComments(source(STATS_SCREEN))
        assertEquals(
            "统计页页头条上「返回」这枚文字按钮只许一处（第二处就是又长了一套页头）：" +
                allOccurrences(stats, "Text(\"返回\")"),
            1, allOccurrences(stats, "Text(\"返回\")").size,
        )
        val glass = blankComments(source(GLASS_TOP_BAR))
        assertEquals(
            "GlassTopBar 里那枚「返回」也被顺手改了？其它二级页（设置 / 导入历史 / 课程管理）的" +
                "CUJ 锚点就是它：" + allOccurrences(glass, "Text(\"返回\")"),
            1, allOccurrences(glass, "Text(\"返回\")").size,
        )
        assertTrue(
            "统计页页头条不再写「学期统计」这一枚主名：用户要的是『顶栏只显示返回和学期统计』",
            stats.contains("学期统计"),
        )
    }

    // ---- ③ 主人只判一次 ----

    /**
     * ③ 路由只在 `MainActivity` 读一次，两个页面都不许自己去判"我是不是在台上"。
     *
     * 这一档是本卡的全部意义：两页各读一次 `currentBackStackEntryAsState()` 的话，
     * 谁先重组谁就先画，过渡期那一帧就两家同写（或者两家都不写）。
     */
    @Test
    fun ownershipIsJudgedOnceAboveTheNavHost() {
        val activity = blankComments(source(MAIN_ACTIVITY))
        assertEquals(
            "headerBandOwnerOf 的调用点全仓只许一处（判两次就是两套真相）：",
            1, Regex("""headerBandOwnerOf\(""").findAll(activity).count(),
        )
        assertEquals(
            "MainActivity 里读 currentBackStackEntryAsState 只许一处：页头条的主人必须与" +
                "底栏可见性吃同一枚当前路由，否则两者会差一帧",
            1, Regex("""currentBackStackEntryAsState\(""").findAll(activity).count(),
        )
        val ownerDefinition = Regex("""val headerBandOwner = headerBandOwnerOf\(([^)]*)\)""").find(activity)
        check(ownerDefinition != null) { "MainActivity 里已经没有 `val headerBandOwner = headerBandOwnerOf(...)`：本守卫要跟着改" }
        assertTrue(
            "主人不再由当前路由算出来（吃别的输入就是拿一帧旧路由承诺板上画谁）：" + ownerDefinition.value,
            ownerDefinition.groupValues[1].contains("currentDestination"),
        )
        assertEquals(
            "两枚 Boolean 只许从同一枚 headerBandOwner 算出来，各算一次就是各判各的：",
            2, Regex("""headerBandDrawnOnScreen\(\s*headerBandOwner,""").findAll(activity).count(),
        )
        for (file in listOf(HOME_SCREEN, STATS_SCREEN)) {
            val code = blankComments(source(file))
            for (banned in listOf("currentBackStackEntry", "currentDestination", "navController", "headerBandOwnerOf")) {
                assertFalse(
                    "$file 自己伸手去读导航状态（$banned）—— 页头条的主人只许由调用点当参数传进来",
                    code.contains(banned),
                )
            }
        }
    }

    // ---- ④ 两枚参数都不许有静默兜底，且都接到对的那一位 ----

    /** ④-a 默认值一长回来，"忘了接线"就又变成一次观感 bug 而不是编译错误 */
    @Test
    fun headerBandFlagHasNoSilentDefaultOnEitherPage() {
        for (file in listOf(HOME_SCREEN, STATS_SCREEN)) {
            val code = blankComments(source(file))
            val parameter = Regex("""headerBandOnScreen: Boolean(\s*=\s*[^\n,]*)?""").find(code)
            check(parameter != null) { "$file 的参数表里已经没有 headerBandOnScreen 了：入口换地方了，本守卫要跟着改" }
            assertEquals(
                "headerBandOnScreen 又长出默认值了。默认 true = 跳转期间两套页头叠在一起（本卡的病根），" +
                    "默认 false = 首页顶栏整条空白。现在的参数是「${parameter.value.trim()}」",
                "", parameter.groupValues[1].trim(),
            )
        }
    }

    /** ④-b 两个调用点各吃到从同一枚主人算出的那一位，且两位互不相同 */
    @Test
    fun bothCallSitesReceiveTheirOwnFlag() {
        val activity = blankComments(source(MAIN_ACTIVITY))
        val home = callArgumentLists(activity, "HomeScreen(")[0]
        val statsRoute = balancedBlock(activity, "composable(\"stats\")")
        val stats = invocationArgumentLists(statsRoute, "StatsScreen")[0]
        val homeFlag = onlyFlag("HomeScreen", home)
        val statsFlag = onlyFlag("StatsScreen", stats)
        assertEquals(
            "首页的页头条没接 homeHeaderBandOnScreen（写死 true / 漏传 / 接成都那一枚，" +
                "都会让跳转期间两套页头叠在同一条带上）",
            "homeHeaderBandOnScreen", homeFlag,
        )
        assertEquals("统计页的页头条没接 statsHeaderBandOnScreen", "statsHeaderBandOnScreen", statsFlag)
        assertTrue(
            "两页吃到了同一枚标志位：那等于两页同时画、或同时不画（$homeFlag）",
            homeFlag != statsFlag,
        )
    }

    private fun onlyFlag(what: String, args: String): String {
        val passed = Regex("""headerBandOnScreen\s*=\s*([A-Za-z_][\w.]*)""").findAll(args)
            .map { it.groupValues[1] }.toList()
        assertEquals("$what 的 headerBandOnScreen 只许传一枚命名好的量（不许现场写 true/false）：$passed", 1, passed.size)
        return passed[0]
    }

    // ---- ⑤ 占位高度来自测量，闸门是"不求值"而不是"透明" ----

    /** ⑤-a 不在台上那一页按**上一次量到的实高**占位：初值必须是"还没量到"的 null */
    @Test
    fun placeholderHeightComesFromTheRenderedFrame() {
        for ((file, name) in listOf(HOME_SCREEN to "topRowHeightPx", STATS_SCREEN to "statsBandHeightPx")) {
            val code = blankComments(source(file))
            assertTrue(
                "$name 不再由 onSizeChanged 从画出来那一帧读高度（换成常数就是 T48 换个维度重演）：\n" +
                    code.lines().filter { it.contains(name) }.joinToString("\n"),
                Regex("""onSizeChanged \{[^}]*$name = it\.height""").containsMatchIn(code),
            )
            assertTrue(
                "$name 的初值必须是「还没量到」的 null：兜一个常数就等于向那条带承诺一个没量过的高度",
                Regex("""var $name by remember \{ mutableStateOf<Int\?>\(null\) \}""").containsMatchIn(code),
            )
            assertTrue(
                "$name 没有递进 ScheduleHeaderBand 的 measuredHeightPx：占位就退化成按 48dp 猜",
                Regex("""measuredHeightPx = $name""").containsMatchIn(code),
            )
        }
    }

    /**
     * ⑤-b 带子的闸门是"干脆不求值 content"。
     *
     * `Modifier.alpha(0f)` / `visible = false` 那一类写法在**语义树里仍然留着一套文字**：
     * 读屏会念出不在台上的那一页页头，`uiautomator dump` 也照样拿得到两段子节点 ——
     * 本卡的验收判据正是 dump 里那条带上只有一套 Text。
     */
    @Test
    fun blankedBandRemovesNodesInsteadOfPaintingThemInvisible() {
        val code = blankComments(source(HOME_SCREEN))
        val body = balancedBlock(code, "internal fun ScheduleHeaderBand(")
        assertTrue(
            "带子的内容不再由 drawnOnScreen 这一道闸门求值（闸门一散，两页就又能同写）：\n$body",
            Regex("""if \(drawnOnScreen\) content\(\)""").containsMatchIn(body),
        )
        for (banned in listOf("Modifier.alpha", "graphicsLayer", "ViewProperties")) {
            assertFalse(
                "带子改用「画了但看不见」来消重叠？语义树里那一套字还在，读屏与 uiautomator 都拿得到：$banned",
                body.contains(banned),
            )
        }
    }

    // ---- ⑥ 两页同一个高：参照只能量出来，且只许从首页流向统计页（T80-C）----

    /**
     * ⑥-a 带子的下限高不许写死成常数。
     *
     * 装机读数是首页 148px / 统计页 126px，可这一枚 148 是**分段控件那层玻璃衬里**（COMPACT
     * 上下各 4dp）在当前字号档下算出来的：系统字号调大时首页那一支还要长，写死 57dp / 148px
     * 当天就穿。所以带子的几何只许吃 `headerBandFloorHeightPx(参照, 触控下限)`，
     * 函数体里一个 `数字 + dp/sp/px` 都不许出现。
     */
    @Test
    fun bandFloorComesFromMeasurementNotFromALiteral() {
        val code = blankComments(source(HOME_SCREEN))
        val body = balancedBlock(code, "internal fun ScheduleHeaderBand(")
        val literals = Regex("""\b\d+(\.\d+)?(dp|sp|px)\b""").findAll(body).map { it.value }.toList()
        assertTrue(
            "ScheduleHeaderBand 的函数体里出现了写死的尺寸（带的几何只许有一份，而那份只吃 token 与量到的数）：$literals",
            literals.isEmpty(),
        )
        assertTrue(
            "台上那一档的下限不再走 headerBandFloorHeightPx —— 又变成『这一页的内容多高就多高』，" +
                "两页的带当场分家：\n$body",
            Regex("""headerBandFloorHeightPx\(\s*referenceHeightPx,\s*fallbackHeightPx\s*\)""").containsMatchIn(body),
        )
        assertTrue(
            "参照没吃进 heightIn 的 min：那等于统计页递了参照也没人认领",
            Regex("""heightIn\(\s*min = with\(density\) \{[\s\S]*?headerBandFloorHeightPx""").containsMatchIn(body),
        )
    }

    /**
     * ⑥-b 参照的流向只有一个方向：首页量出来 → MainActivity 存一枚 → 统计页吃下限。
     *
     * 反向（把统计页量到的实高喂给首页）会把这一族账变成只涨不落的棘轮：统计页那次量的高
     * 已经含了首页的下限，再拿它去托首页，字号调回去时带子永远停在厚的那一档。
     */
    @Test
    fun referenceHeightFlowsFromHomeToStatsAndNeverBack() {
        val home = blankComments(source(HOME_SCREEN))
        val stats = blankComments(source(STATS_SCREEN))
        val activity = blankComments(source(MAIN_ACTIVITY))
        assertTrue(
            "首页不再把**画出来那一帧**的带高递上去（换成常数就是 T48 换个维度重演）：\n" +
                home.lines().filter { it.contains("onHeaderBandHeightMeasured") }.joinToString("\n"),
            Regex("""onSizeChanged \{[^}]*onHeaderBandHeightMeasured\(it\.height\)""").containsMatchIn(home),
        )
        assertTrue(
            "首页那一支自己回收了参照（referenceHeightPx 不再写 null）：首页 → 统计页 → 首页" +
                "就成了一个只涨不落的环",
            Regex("""referenceHeightPx = null""").containsMatchIn(home),
        )
        assertTrue(
            "统计页的带没吃首页递来的参照：两页的带又会差那一截玻璃衬里（装机 11px 的页头跳动）",
            Regex("""referenceHeightPx = headerBandReferenceHeightPx""").containsMatchIn(stats),
        )
        assertFalse(
            "统计页把**自己**量到的实高当参照喂回带子 —— 那一枚已经含了首页的下限，喂回去就是棘轮",
            Regex("""referenceHeightPx = statsBandHeightPx""").containsMatchIn(stats),
        )
        assertEquals(
            "MainActivity 里那枚参照高只许声明一次（两处存就是两枚真相）：",
            1, Regex("""var homeHeaderBandHeightPx by remember \{ mutableStateOf<Int\?>\(null\) \}""")
                .findAll(activity).count(),
        )
        assertEquals(
            "那枚状态全仓只许出现三次（声明一次 + 首页写一次 + 统计页读一次）；第四次就是又一个主人：" +
                Regex("""homeHeaderBandHeightPx""").findAll(activity).toList(),
            3, Regex("""homeHeaderBandHeightPx""").findAll(activity).count(),
        )
        assertTrue(
            "首页的回调不再写进那一枚状态：接线断在这里，统计页的带就永远退到 48dp 下限",
            Regex("""onHeaderBandHeightMeasured = \{ homeHeaderBandHeightPx = it \}""").containsMatchIn(activity),
        )
        assertTrue(
            "统计页没接到那枚状态：同上，只是这次红在『两页不同高』上",
            Regex("""headerBandReferenceHeightPx = homeHeaderBandHeightPx""").containsMatchIn(activity),
        )
    }

    /** ⑥-c 两枚新参数都不许长回默认值：`= null` / `= {}` 买到的都是"忘了接线也编译过" */
    @Test
    fun bandHeightWiringHasNoSilentDefaultOnEitherPage() {
        val home = blankComments(source(HOME_SCREEN))
        val stats = blankComments(source(STATS_SCREEN))
        val callback = Regex("""onHeaderBandHeightMeasured: \(Int\) -> Unit(\s*=\s*[^\n,]*)?""").find(home)
        check(callback != null) { "HomeScreen 的参数表里已经没有 onHeaderBandHeightMeasured 了：参照换地方量了，本守卫要跟着改" }
        assertEquals(
            "首页的带高回调又长出默认值（`= {}` = 统计页永远量不到参照）：「${callback.value.trim()}」",
            "", callback.groupValues[1].trim(),
        )
        val reference = Regex("""headerBandReferenceHeightPx: Int\?(\s*=\s*[^\n,]*)?""").find(stats)
        check(reference != null) { "StatsScreen 的参数表里已经没有 headerBandReferenceHeightPx 了：参照换地方递了，本守卫要跟着改" }
        assertEquals(
            "统计页的参照高又长出默认值（`= null` = 这一页永远按 48dp 下限排，页头当场跳一格）：" +
                "「${reference.value.trim()}」",
            "", reference.groupValues[1].trim(),
        )
    }

    // ---- 源码核对工具（与 StatsEntryWiringGuardTest 同一套刀法）----

    private fun source(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    private fun filesUnderMain(): List<File> {
        val dir = findMainJavaDir()
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList().sortedBy { it.path }
        assertTrue("$dir 下一个 .kt 都没有，扫描范围取错了", files.isNotEmpty())
        return files
    }

    private fun allOccurrences(code: String, needle: String): List<Int> {
        val out = ArrayList<Int>()
        var from = 0
        while (true) {
            val at = code.indexOf(needle, from)
            if (at < 0) return out
            out += code.substring(0, at).count { c -> c == '\n' } + 1
            from = at + needle.length
        }
    }

    /** 每一次**调用**（`Name(`）的实参表，跳过 `fun Name(` 那枚声明；一个命中都没有就抛 */
    private fun invocationArgumentLists(code: String, name: String): List<String> {
        val out = ArrayList<String>()
        for (match in Regex("""\b${Regex.escape(name)}\(""").findAll(code)) {
            val at = match.range.first
            if (code.substring(0, at).trimEnd().endsWith("fun")) continue
            out += balancedArguments(code, match.range.last)
        }
        return out
    }

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
        const val STATS_SCREEN = "com/buaa/schedule/ui/stats/StatsScreen.kt"
        const val GLASS_TOP_BAR = "com/buaa/schedule/core/designsystem/GlassTopBar.kt"
    }
}
