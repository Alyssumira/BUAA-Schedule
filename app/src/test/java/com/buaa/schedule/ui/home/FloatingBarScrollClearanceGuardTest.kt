package com.buaa.schedule.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #214 的静态守卫：**课表两页（今日页 / 周页）的每一枚纵向滚动容器都带悬浮玻璃底栏的底部 clearance**。
 *
 * 缺陷本体：悬浮玻璃底栏是 overlay Box 而不是 Scaffold 排布（口径写在
 * `core/designsystem/DesignTokens.kt` 的 floatingBottomBarClearance 注释里），内容延伸到栏体背后
 * 滚动才是这种玻璃的视觉来源 —— 于是**滚动容器要自己让位**。改前全仓只有三处引用这枚口径：
 * HomeScreen 的 `fabBottomPadding`（那是 FAB 与菜单让位用的，不是滚动容器）、
 * ImportScreen、SettingsScreen ⇒ 课表两页一枚都没加。装机实证：今日页 3 节课时最后一张卡
 * 被栏体切在屏底，12 趟 swipe 只出 1 帧。
 *
 * 本模块没有 Robolectric / Compose 布局测试，钉不了像素，只能钉**结构**。刀法照抄
 * LazyRecycleGuardTest / DayTimelineStructureGuardTest：抹注释（字符串保留）→ 按括号配对取
 * 修饰链与实参表 → 一个靶子都没扫到也判红 → 找不到源码目录直接抛而不是 assumeTrue
 * （跳过的守卫比没有守卫更糟）。
 *
 * ── 钉法选的是「**逐枚配对 + 枚数台账**」这一族，不是按符号名/行号点名集合 ──
 * 每枚容器由 token 现场扫出来（`verticalScroll(` / `LazyColumn(`），再要求**它自己那条修饰链 /
 * 它自己那份实参表**里带 clearance；枚数台账（日 2 枚、周 1 枚）保证「扫到 0 枚」不会变成绿。
 * 两把尺同时挂着才封得住：只数枚数则「把旧容器的留白摘掉」没反应，只逐枚配对则「整页被搬空」会绿。
 * 不钉行号是刻意的：本仓为「行号锚点漂了要重登」付过太多次账（见 docs 的锚点普查一族）。
 *
 * 假阳（会红，但该改的是台账/普查表而不是代码）：
 * 1. 这两页新增一枚**确实不需要** clearance 的纵向容器 —— 弹层/对话框里的滚动区（不在栏体之下）、
 *    或高度被夹住永不滚动的容器。⑤ 与 ①② 的枚数都要人工 +1，并把豁免理由写进本格 KDoc。
 * 2. 纵向容器换了本卡 token 表之外的写法（`Modifier.scrollable(…)`、`BottomSheetScaffold` 的
 *    内容槽等）⇒ 表现为「枚数对不上」或干脆扫不到，实质是普查面要 widen。
 * 3. 有人在这几枚文件里加了**不配 clearance** 的 `if (bottomBarVisible)`（比如给某个动画 gating）
 *    ⇒ ③ 那把 uses==gated 的尺会红，要重登的是分母口径。
 * 4. 别的卡在这两页合法加了一枚带 clearance 的容器 ⇒ ⑤ 与 ③ 的台账都要跟着改（本卡只按今天的
 *    盘面登台账，不预测下一张卡）。
 *
 * 假阴（改了代码但本卡看不见）：
 * 1. **扫描面只有这两枚文件**：把时间轴/周网格拆到新文件，或给别的页面加滚动容器，都盖不住
 *    ⇒ 新落点漏了 clearance 照样绿。要 widen 就把文件加进 SCHEDULE_PAGES。
 * 2. clearance 挂到**同一条链更外面的兄弟节点**上（例如挂外层视口 Box 而不是滚动 Box）：文本扫描
 *    会把它算进链尾而放行，实际只缩了视口、滚动范围一点没变。这一档只有装机能量。
 * 3. 运行期才决定的可见性（例如把旗兜底成 `?: true`）：静态看是 gated 的，真机上照样白留一段空。
 * 4. 底部留白用「尾部 item { Spacer(…) }」那种等效形状（CourseManagementScreen 用的就是它）：
 *    本卡要求 token 口径，那种形状会红 —— 这是**刻意的假阳**，因为 Spacer 定值不跟栏体高度与
 *    系统导航栏 insets 走，正是 #214 的一半成因。
 */
class FloatingBarScrollClearanceGuardTest {

    // ---- ① 每一枚 verticalScroll：链尾必须有底部 clearance，且形状与参照页一致 ----

    @Test
    fun everyVerticalScrollContainerInSchedulePagesCarriesBottomClearance() {
        val scanned = mutableListOf<String>()
        SCHEDULE_PAGES.forEach { rel ->
            val code = blankComments(source(rel))
            indicesOf(code, VERTICAL_SCROLL).forEach { at ->
                val where = "$rel:${lineOf(code, at)}"
                scanned += where
                val tail = chainTail(code, at, VERTICAL_SCROLL)
                assertTrue(
                    "$where 这枚 verticalScroll 的修饰链上没有底部 clearance：overlay 排布下滚动内容的尾部" +
                        "让不出栏体高度，最后一项被底栏切在屏底（#214 的原样）。形状要与 ImportScreen / " +
                        "SettingsScreen 那两枚已有落点一致 —— .verticalScroll(…) **之后**接 " +
                        ".padding(bottom = if (bottomBarVisible) { floatingBottomBarClearance() } else 0.dp)；" +
                        "挂到 verticalScroll 之前只是缩小视口，滚动范围一点没变。\n链尾：$tail",
                    ".padding(" in tail && "bottom" in tail && CLEARANCE in tail,
                )
                assertTrue(
                    "$where 的 clearance 没挂在 bottomBarVisible 上：宽屏走导航栏那一档根本没有栏体，" +
                        "硬留白是净负（白占一段滚不掉的空）。不许用「永远留白」换少改文件。\n链尾：$tail",
                    GATE in tail,
                )
            }
        }
        assertEquals(
            "verticalScroll 台账：日视图时间轴那枚 + 周视图网格那枚 = 2 枚，一枚都不许掉 clearance。" +
                "枚数对不上说明这两页新加/删掉了一枚纵向滚动容器 —— 先按卡面三条判据判它该不该让位，" +
                "再重登本台账：${scanned.sorted()}",
            2,
            scanned.size,
        )
    }

    // ---- ② 每一枚 LazyColumn：实参表里的 contentPadding 必须带底部 clearance ----

    @Test
    fun everyLazyColumnInSchedulePagesCarriesBottomContentPadding() {
        val scanned = mutableListOf<String>()
        SCHEDULE_PAGES.forEach { rel ->
            val code = blankComments(source(rel))
            indicesOf(code, LAZY_COLUMN).forEach { at ->
                val where = "$rel:${lineOf(code, at)}"
                scanned += where
                val args = callArgsAt(code, at, LAZY_COLUMN)
                val padding = args.substringAfter("contentPadding", "")
                assertTrue(
                    "$where 这枚 LazyColumn 没有 contentPadding：lazy 容器的底部让位只能走 contentPadding" +
                        "（口径与 ImportScreen / SettingsScreen 相同）。整枚容器 .padding() 会把视口一起缩掉，" +
                        "最后一项照样滚不出栏体：$args",
                    padding.isNotBlank(),
                )
                assertTrue(
                    "$where 的 contentPadding 只管了别的边、没管 bottom：底栏在底部，只有底部留白才是让位：" +
                        padding,
                    "bottom" in padding && CLEARANCE in padding,
                )
                assertTrue(
                    "$where 的 contentPadding 不许换成 PaddingValues 之外的形状（与已有两枚落点同形）：$padding",
                    "PaddingValues(" in padding,
                )
                assertTrue(
                    "$where 的 clearance 没挂在 bottomBarVisible 上（底栏不在屏上那一档白留一段空）：$padding",
                    GATE in padding,
                )
            }
        }
        assertEquals(
            "LazyColumn 台账：课表两页只有今日页列表模式那枚（周视图的网格走 verticalScroll，" +
                "空态那枚 Column 根本不可滚）：${scanned.sorted()}",
            1,
            scanned.size,
        )
    }

    // ---- ③ clearance 一枚都不许脱开旗（防「硬编码永远留白」） ----

    @Test
    fun clearanceIsNeverHardcodedOutsideTheBarFlag() {
        val ledger = LEDGER_FILES.associateWith { rel ->
            val code = blankComments(source(rel))
            occurrences(code, CLEARANCE) to occurrences(code, GATE)
        }
        ledger.forEach { (rel, pair) ->
            val (uses, gated) = pair
            assertEquals(
                "$rel：每一枚 floatingBottomBarClearance() 都该包在 if (bottomBarVisible) 里 —— 底栏不在" +
                    "屏上那一档留白是净负。uses 比 gated 多 = 有人把口径硬编码成「永远留白」；" +
                    "gated 比 uses 多 = 有人加了枚不配 clearance 的旗（那是别的功能，要重登本台账）。",
                gated,
                uses,
            )
        }
        assertEquals(
            "clearance 引用枚数台账（HomeScreen 的 FAB 一枚 + 课表两页三枚容器 + 两枚参照页各一枚）：" +
                "对不上就先把卡面第 1 步的普查表重跑一遍，再改期望值与这句话里的数字",
            mapOf(
                HOME_SCREEN to 1,
                DAY_VIEW to 2,
                WEEK_VIEW to 1,
                IMPORT_SCREEN to 1,
                SETTINGS_SCREEN to 1,
            ),
            ledger.mapValues { it.value.first },
        )
    }

    // ---- ④ 横向容器一枚都不许带 clearance（底栏在底部） ----

    @Test
    fun horizontalContainersCarryNoBottomClearance() {
        val scanned = mutableListOf<String>()
        SCHEDULE_PAGES.forEach { rel ->
            val code = blankComments(source(rel))
            indicesOf(code, HORIZONTAL_SCROLL).forEach { at ->
                val where = "$rel:${lineOf(code, at)}"
                scanned += where
                val tail = chainTail(code, at, HORIZONTAL_SCROLL)
                assertTrue(
                    "$where 这枚 horizontalScroll 带了底部 clearance：底栏在**底部**，横向容器让位没有意义，" +
                        "只会把这一行的可用高度吃掉一截：\n$tail",
                    CLEARANCE !in tail,
                )
            }
        }
        assertEquals(
            "横向容器台账：周视图 compactScroll 那一档的七天横滚共 2 枚（网格内的七天区 + 表头那一行，" +
                "两处共用同一枚 dayScrollState，必须同生同灭）；日视图的翻日期是手写 pointerInput，" +
                "不是 horizontalScroll，不在 token 表里：" + scanned.sorted().joinToString(),
            2,
            scanned.size,
        )
    }

    // ---- ⑤ 纵向容器普查台账（抓住「将来新加一枚漏了 clearance 的容器」） ----

    @Test
    fun verticalContainerCensusMatchesTheLedger() {
        val census = SCHEDULE_PAGES.associateWith { rel ->
            val code = blankComments(source(rel))
            VERTICAL_TOKENS.sumOf { occurrences(code, it) }
        }
        assertEquals(
            "课表两页的纵向滚动容器 = 日视图 2 枚（列表模式 LazyColumn + 时间轴 verticalScroll）+ " +
                "周视图 1 枚（网格 verticalScroll）。枚数对不上 ⇒ 这两页动过滚动容器：新加的那枚要么按" +
                "三条判据补 clearance，要么把「这一档没有栏体 / 它不滚」的理由写进本台账，再改期望值。" +
                "逐枚配对（①②）与本格同时挂着：只看枚数会漏「改了旧容器」，只看配对会漏「整页被搬空」：" +
                census,
            mapOf(DAY_VIEW to 2, WEEK_VIEW to 1),
            census,
        )
    }

    // ---- ⑥ 旗一路传到持有容器的那几枚函数，且一枚都不许带默认值 ----

    @Test
    fun barFlagReachesEveryScrollContainerOwner() {
        val day = blankComments(source(DAY_VIEW))
        val week = blankComments(source(WEEK_VIEW))
        val owners = listOf(
            day to "fun DayView(",
            day to "fun DayScreen(",
            day to "fun DayTimelineCourseList(",
            week to "fun WeekView(",
            week to "fun WeekGrid(",
        )
        owners.forEach { (code, signature) ->
            val args = declArgs(code, signature)
            assertTrue(
                "$signature 的参数表里没有 bottomBarVisible：这枚函数持有（或转交）课表页的滚动容器，" +
                    "旗断了这一页就不让位（#214 的原始漏法）：$args",
                "bottomBarVisible: Boolean" in args,
            )
            assertTrue(
                "$signature 的 bottomBarVisible 长出了默认值：`= false` 买到的正是本卡修的缺陷" +
                    "（编译通过、装机才发现最后一项滚不出来），`= true` 则在宽屏那一档白留一段空。" +
                    "旗只有一个主人，调用点必须自己答：$args",
                !Regex("bottomBarVisible\\s*:\\s*Boolean\\s*=").containsMatchIn(args),
            )
        }
        assertEquals(
            "DayView.kt 把旗往下递的调用点 = DayScreen 那处 + DayTimelineCourseList 那处" +
                "（今日页两支画法都要接旗，只补看得见的那一支就是 #214 的漏法）",
            2,
            occurrences(day, "bottomBarVisible = bottomBarVisible"),
        )
        assertEquals(
            "WeekView.kt 把旗往下递的调用点 = WeekGrid 两处（翻周 Pager 那一支 + 无周次信息那一支）",
            2,
            occurrences(week, "bottomBarVisible = bottomBarVisible"),
        )
        val home = blankComments(source(HOME_SCREEN))
        assertEquals(
            "HomeScreen 调 DayView/WeekView 共 4 处（宽屏并排 2 + 手机 Crossfade 2），一枚都不许漏旗",
            4,
            occurrences(home, "bottomBarVisible = bottomBarVisible"),
        )
        // FAB 那枚口径不归本卡改（卡面点名「不许动 fabBottomPadding 的口径」），钉它还活着
        assertTrue(
            "HomeScreen 的 fabBottomPadding 不见了：FAB 与菜单的让位是另一笔账，别把它并进滚动容器这一族",
            "val fabBottomPadding" in home,
        )
        // 旗的源头：MainActivity 的 showBottomBar → AppNavHost → HomeScreen
        val main = blankComments(source(MAIN_ACTIVITY))
        assertTrue(
            "MainActivity 不再把 showBottomBar 递给 AppNavHost：这一族旗从源头断了" +
                "（宽屏导航栏分支不递是刻意的，手机那一支必须递）",
            "bottomBarVisible = showBottomBar" in main,
        )
        val homeCall = declArgs(main, "HomeScreen(")
        assertTrue(
            "AppNavHost 里的 HomeScreen( 不再带 bottomBarVisible：首页拿不到旗，" +
                "于是课表两页又一次一枚都不让位：$homeCall",
            "bottomBarVisible" in homeCall,
        )
    }

    // ---- ⑦ 形状参照页与口径定义处不许被「统一口径」顺手改掉 ----

    @Test
    fun shapeReferencePagesAndTokenDefinitionStayPut() {
        listOf(IMPORT_SCREEN, SETTINGS_SCREEN).forEach { rel ->
            val code = blankComments(source(rel))
            assertEquals(
                "$rel：这页的滚动容器是本卡逐枚照抄的形状参照（.verticalScroll(…).padding(bottom = …)），" +
                    "clearance 一枚都不许掉",
                1,
                occurrences(code, CLEARANCE),
            )
        }
        val tokens = blankComments(source(DESIGN_TOKENS))
        assertEquals(
            "floatingBottomBarClearance 只许有一个定义处：两处各写一份，下次调栏体高度必只改一边" +
                "（本仓为这类账立过档）",
            1,
            occurrences(tokens, "fun floatingBottomBarClearance("),
        )
        assertTrue(
            "定义处那段注释是「这两页为什么必须自己让位」的唯一说明（overlay Box 而不是 Scaffold），不许抹",
            source(DESIGN_TOKENS).contains("用 overlay Box 而不是 Scaffold 排布"),
        )
    }

    // ---- 靶子定位与词法小工具（与 LazyRecycleGuardTest 同一套） ----

    /** [code] 里 [token]（如 `verticalScroll(`）之后**这条 Modifier 链**的剩余部分 */
    private fun chainTail(code: String, at: Int, token: String): String {
        val open = at + token.length - 1
        val close = matchingClose(code, open)
            ?: throw AssertionError("$token 的括号配不上对，解析器该修了")
        val out = StringBuilder()
        var depth = 0
        var i = close + 1
        while (i < code.length) {
            val ch = code[i]
            // 深度 0 上的 , / { / ) / } / ] / ; 是这条修饰链的终点
            // （实参表结束 / 内容 lambda 开始 / 外层调用结束 / if-else 块或数组下标收尾 / 语句结束）
            if (depth == 0 && (ch == ',' || ch == '{' || ch == ')' || ch == '}' || ch == ']' || ch == ';')) break
            when (ch) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
            }
            assertTrue("$token 的链上括号多出来一层，解析器该修了", depth >= 0)
            out.append(ch)
            i++
        }
        return out.toString().trim()
    }

    /** 声明 / 调用 [signature]（如 `fun DayView(`、`HomeScreen(`）括号里的参数表 / 实参表 */
    private fun declArgs(code: String, signature: String): String {
        val at = code.indexOf(signature)
        assertTrue("找不到 $signature：靶子没了（改名/搬家的话这条守卫要跟着重登）", at >= 0)
        return callArgsAt(code, at, signature)
    }

    private fun callArgsAt(code: String, at: Int, token: String): String {
        val open = at + token.length - 1
        val close = matchingClose(code, open)
            ?: throw AssertionError("$token 的括号配不上对，解析器该修了")
        return code.substring(open + 1, close)
    }

    private fun indicesOf(haystack: String, needle: String): List<Int> {
        val out = mutableListOf<Int>()
        var at = haystack.indexOf(needle)
        while (at >= 0) {
            out += at
            at = haystack.indexOf(needle, at + needle.length)
        }
        return out
    }

    private fun occurrences(haystack: String, needle: String): Int = indicesOf(haystack, needle).size

    /** 报错消息里的行号：守卫挂了要能直接走到那一枚容器，不让人拿字符偏移去数（期望值里不钉它） */
    private fun lineOf(code: String, index: Int): Int = code.take(index).count { it == '\n' } + 1

    private fun source(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 $relative：挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
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

    /** 把 `//` 与 `/* */` 注释抹成空格（字符串保留、长度与换行位置不变） */
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

    private companion object {
        const val DAY_VIEW = "com/buaa/schedule/ui/home/DayView.kt"
        const val WEEK_VIEW = "com/buaa/schedule/ui/home/WeekView.kt"
        const val HOME_SCREEN = "com/buaa/schedule/ui/home/HomeScreen.kt"
        const val MAIN_ACTIVITY = "com/buaa/schedule/MainActivity.kt"
        const val IMPORT_SCREEN = "com/buaa/schedule/ui/importing/ImportScreen.kt"
        const val SETTINGS_SCREEN = "com/buaa/schedule/ui/settings/SettingsScreen.kt"
        const val DESIGN_TOKENS = "com/buaa/schedule/core/designsystem/DesignTokens.kt"

        /** 本卡的扫描面 = 课表两页（假阴 ①：别的文件里的纵向容器不在册） */
        val SCHEDULE_PAGES = listOf(DAY_VIEW, WEEK_VIEW)

        /** ③ 的 uses==gated 尺要读的落点：两页 + FAB + 两枚形状参照页 */
        val LEDGER_FILES = listOf(HOME_SCREEN, DAY_VIEW, WEEK_VIEW, IMPORT_SCREEN, SETTINGS_SCREEN)

        const val CLEARANCE = "floatingBottomBarClearance("
        const val GATE = "if (bottomBarVisible)"

        const val VERTICAL_SCROLL = "verticalScroll("
        const val HORIZONTAL_SCROLL = "horizontalScroll("
        const val LAZY_COLUMN = "LazyColumn("

        /**
         * 纵向滚动容器的 token 表（⑤ 的普查面）。不含 LazyRow / horizontalScroll —— 那些是横向的；
         * 也不含 Modifier.scrollable / BottomSheetScaffold（假阴 ②：这两页今天没有这两种形状）。
         */
        val VERTICAL_TOKENS = listOf(
            VERTICAL_SCROLL,
            LAZY_COLUMN,
            "LazyVerticalGrid(",
            "LazyVerticalStaggeredGrid(",
        )
    }
}
