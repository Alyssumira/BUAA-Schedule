package com.buaa.schedule.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T68b 的结构守卫——「顶栏第二行不许读一个不在屏上的日子」。
 *
 * 本模块没有 Compose 运行时（无 Robolectric、无 ui-test），`HomeScreen` 里那一行
 * 「只有周页签才渲染」的事实只能扫源码，刀法照抄 [DayBrowseWiringGuardTest]：
 * `blankComments` 抹注释、`balancedBlock` 取块、找不到锚点就抛、不用 assumeTrue 跳过。
 *
 * 账是 T68 自己带来的：那一卡把「日视图这一帧画哪一天」收成全页唯一一份真相
 * `browseDateOnScreen` 并喂给四个消费方，其中一个是顶栏第二行
 * `topBarDateLabel(..., dateOnScreen = browseDateOnScreen)`。但 `dateLabel` 只活在
 * `Crossfade(targetState = selectedTab == 0)` 的 `isWeekTab` 那一支 —— 人在周课表页签时
 * 日视图根本没画出来，第二行却报日视图翻到的那一天。真机装机实测（f128bc02）：
 * 网格里高亮的今天是 9/22 周二，第二行写「9月24日 星期四」。
 * 变量名里的 "OnScreen" 在这一档是假的。
 */
class DayViewOnScreenWiringGuardTest {

    // ---- ① 复现：这一行读的日子必须经过「日视图在不在屏上」这道闸 ----

    /**
     * 缺陷本体。改前 `topBarDateLabel` 的实参只有
     * `semesterStart, state.currentWeek, browseWeek, today, browseDateOnScreen` ——
     * 五个入参里没有任何一维讲得出「日视图此刻画没画在屏上」，
     * 于是同一份 `browseDateOnScreen` 在周页签上也被端给第二行。
     *
     * 这一维不许由调用点就地 `if (selectedTab == 1 || isWide)` 现搭：判据本体在
     * `dayViewOnScreen`（页签 / 是否宽屏并排 / 首帧页签定没定），设备侧事实当参数传进去。
     */
    @Test
    fun secondLineDayIsGatedByTheOnScreenKernel() {
        val home = blankComments(source("com/buaa/schedule/ui/home/HomeScreen.kt"))
        val call = Regex("topBarDateLabel\\(([^)]*)\\)").find(home)
            ?: throw AssertionError("HomeScreen 不再调用 topBarDateLabel：顶栏第二行的算式脱钩了")
        val args = call.groupValues[1]
        assertTrue(
            "顶栏第二行只吃 browseDateOnScreen，没有任何一维说明「日视图此刻在不在屏上」。\n" +
                "窄屏周页签上日视图根本没渲染，而 dateLabel 只活在 Crossfade 的 isWeekTab 那一支" +
                "（见本文件 secondLineOnlyRenderedInTheWeekBranch），于是那一行报的是屏上没有的日子：\n" +
                "真机 f128bc02 量到「网格高亮今天 = 9/22 周二」而第二行写「9月24日 星期四」。\n" +
                "现在这五个实参是：$args",
            Regex("dayViewOnScreen\\(|dayViewIsOnScreen|dayViewDrawn").containsMatchIn(args),
        )
    }

    // ---- ② 前提：第二行只活在周页签那一支（本卡判据成立的那件事） ----

    /**
     * 上面那条断言的前提，单独钉一枚：`dateLabel` 的渲染点只有 `isWeekTab` 那一支，
     * 今日页签那一支画的是 `dayTabHeadline`（一行，没有第二行）。
     *
     * 这一档改前改后都该是绿的——它是"周页签上读 `browseDateOnScreen` 就是读一个不在
     * 屏上的日子"这句话的全部依据。哪天有人把第二行也搬进今日那一支，这条要红，
     * 那时该重问的是「日视图在不在屏上」这道闸还成不成立，而不是把闸拆了。
     */
    @Test
    fun secondLineOnlyRenderedInTheWeekBranch() {
        val home = blankComments(source("com/buaa/schedule/ui/home/HomeScreen.kt"))
        val branch = balancedBlock(home, "targetState = selectedTab == 0")
        val weekHalf = branch.substringBefore("} else {")
        val dayHalf = branch.substringAfter("} else {")
        assertTrue(
            "顶栏第二行（dateLabel）已经不在 isWeekTab 那一支里了，本卡的判据前提要重核：\n$weekHalf",
            weekHalf.contains("text = dateLabel,"),
        )
        assertFalse(
            "今日页签那一支冒出第二行日期就是重复摆日期（日视图页头紧挨着下面就写着那一天）：\n$dayHalf",
            dayHalf.contains("dateLabel"),
        )
        assertTrue(
            "今日页签那一行必须仍走 dayTabHeadline（T68 那一档，本卡不许动它）：\n$dayHalf",
            dayHalf.contains("text = dayTabHeadline("),
        )
    }

    // ---- ③ 内核纯度：判据本体不许自己读设备与表现层 ----

    /**
     * 本仓的收单硬判据：纯 JVM 内核零 android import、零时钟读取，设备/表现层事实
     * （当前页签、是否宽屏、密度那一类）一律由调用点当参数传进来。
     *
     * 这一档专门拦两种漂法：把 `LocalConfiguration` 搬进内核（内核就没法在 JVM 里裸测），
     * 以及"内核返回布尔、调用点再各判一次 `selectedTab`/宽度"（判据从此有两份，一定漂）。
     */
    @Test
    fun onScreenKernelReadsNoDeviceFactsItself() {
        val kernel = blankComments(source("com/buaa/schedule/ui/home/DayBrowsePolicy.kt"))
        val imports = kernel.lines().map { it.trimStart() }
            .filter { it.startsWith("import android") || it.startsWith("import androidx") }
        assertTrue(
            "DayBrowsePolicy.kt 混进设备依赖就没法在 JVM 单测里裸跑：\n" + imports.joinToString("\n"),
            imports.isEmpty(),
        )
        // 表现层事实只能从参数进来：这些符号出现在**代码**里（注释已被抹掉）就是内核自己伸手去读
        val reaching = listOf(
            "LocalConfiguration", "screenWidthDp", "breakpointWide", "LocalDensity",
            "DisplayMetrics", "MaterialTheme", "LocalContext", "isComposable", "isWideScreen",
            "@Composable",
        ).flatMap { name -> kernel.lines().map { it.trim() }.filter { it.contains(name) }.map { "$name -> $it" } }
        assertTrue("判据内核自己读了设备/表现层事实，参数那一维就成了摆设：\n" + reaching.joinToString("\n"), reaching.isEmpty())
        // 三档输入一个不许少（少了就没有任何输入能区分窄屏周课表与宽屏并排）
        val signature = Regex("internal fun dayViewOnScreen\\(([^)]*)\\)\\s*:\\s*Boolean").find(kernel)
            ?: throw AssertionError(
                "dayViewOnScreen 的签名漂了：三档输入（页签 / 是否并排 / 首帧定没定）要一起改这条守卫。\n" +
                    "现在内核里是：" + kernel.lines().filter { it.contains("dayViewOnScreen") }.joinToString(" | "),
            )
        for (input in listOf("selectedTab: Int", "wideSplitLayout: Boolean", "tabDecided: Boolean")) {
            assertTrue("判据少了 $input 这一维，那一维就不参与判定：$signature", signature.value.contains(input))
        }
    }

    // ---- ④ 反 no-op：闸真的长在顶栏第二行上 ----

    /**
     * 历史上白烧过的写法就是"判据抽出来了，没人调用"（本仓为这类账立过档）。
     * 这一档逐点点名：内核在 `HomeScreen` 恰好调一次、三档实参齐全、
     * 结论既进 `topBarDateLabel` 的实参表、也进那颗 `remember` 的键表。
     *
     * 键表那一枚不是形式主义：漏 `dayViewIsOnScreen` 就是"翻到 9/24 → 切回周课表，
     * 顶栏第二行还写着 9月24日"——缓存住旧日期，与 T41/T43 那族同一条因果链。
     */
    @Test
    fun gateIsWiredIntoTheSecondLineAndItsKeyTable() {
        val home = blankComments(source("com/buaa/schedule/ui/home/HomeScreen.kt"))
        assertEquals(
            "dayViewOnScreen 在 HomeScreen 里恰好调一次（多一处就是判据被抄第二遍了）：",
            1,
            Regex("dayViewOnScreen\\(").findAll(home).count(),
        )
        val call = Regex("dayViewOnScreen\\(([^)]*)\\)").find(home)
            ?: throw AssertionError("HomeScreen 不再调用 dayViewOnScreen：抽出来的判据没人调用（静默 no-op）")
        for (input in listOf("selectedTab", "isWideScreen", "tabDecided")) {
            assertTrue("dayViewOnScreen 的实参少了 $input，这一维不参与判定：$call", call.groupValues[1].contains(input))
        }
        val args = Regex("topBarDateLabel\\(([^)]*)\\)").find(home)?.groupValues
            ?: throw AssertionError("HomeScreen 不再调用 topBarDateLabel：顶栏第二行的算式脱钩了")
        assertTrue(
            "顶栏第二行的实参里没有闸的结论——它又在无脑读 browseDateOnScreen 了：" + args[1],
            args[1].contains("dayViewIsOnScreen"),
        )
        assertEquals(
            "topBarDateLabel 的实参应当恰好六个（学期起点/当前周/浏览周/今天/屏上那一天/闸）：\n" + args[1],
            6,
            args[1].split(',').count { it.isNotBlank() },
        )
        val remember = Regex("remember\\(([^)]*)\\)\\s*\\{\\s*topBarDateLabel\\(").find(home)
            ?: throw AssertionError("dateLabel 不再由 remember 包住")
        assertTrue(
            "remember 的键漏了 dayViewIsOnScreen：切页签时顶栏缓存住旧日期：\n" + remember.groupValues[1],
            remember.groupValues[1].contains("dayViewIsOnScreen"),
        )
        assertEquals(
            "remember 的键必须与实参一一对应（六个）：\n" + remember.groupValues[1],
            6,
            remember.groupValues[1].split(',').count { it.isNotBlank() },
        )
        // 判据不许在调用点被就地重搭一遍（那是"把表现层事实混进判据"的另一种写法）
        val labelBlock = balancedBlock(home, "val dateLabel = remember(")
        assertFalse(
            "顶栏这一段就地写 if (selectedTab == 1 || isWideScreen) 这类判据——判据本体在 dayViewOnScreen：\n$labelBlock",
            Regex("selectedTab\\s*==|isWideScreen\\s*\\|\\||\\|\\|\\s*isWideScreen").containsMatchIn(labelBlock),
        )
    }

    // ---- ⑤ 反向钉：闸只许管顶栏第二行，别的一处在屏上是真的 ----

    /**
     * T68 的四位消费方里只有顶栏第二行接错了，其余三处（日视图两处 `date =`、
     * 今日页标题、屏上月份）**本来就在屏上**，加闸时顺手把它们一起关掉就是改错地方：
     * - `dayTabHeadline(today, browseDateOnScreen)` 今日页签那一行原样；
     * - 宽屏并排那一支仍与顶栏共用同一枚 `isWideScreen`（宽度只许量一次）；
     * - `DAY_TAB_INDEX` 必须仍与内容区 `1 -> DayView(` 那一支同源。
     */
    @Test
    fun gateStaysOnTheSecondLineOnly() {
        val home = blankComments(source("com/buaa/schedule/ui/home/HomeScreen.kt"))
        val headline = Regex("text = dayTabHeadline\\(([^)]*)\\)").find(home)
            ?: throw AssertionError("今日页签那一行不再走 dayTabHeadline（T68 那一档被拆了）")
        assertTrue("标题必须仍读 body 那一天：" + headline.groupValues[1], headline.groupValues[1].contains("browseDateOnScreen"))
        assertFalse(
            "闸不许顺手把 dayTabHeadline 一起管了——人在今日页签时日视图真的在屏上：" + headline.groupValues[1],
            headline.groupValues[1].contains("dayViewIsOnScreen"),
        )
        assertEquals(
            "宽度只许量一次（两处各读一遍 LocalConfiguration 就会各信各的答案）：",
            1,
            home.lines().count { it.contains("screenWidthDp") },
        )
        assertEquals(
            "日视图仍只有两处 date = 且都吃同一个 browseDateOnScreen（本卡一处都不动）：",
            2,
            home.lines().filter { it.trim().startsWith("date = browseDateOnScreen") }.size,
        )
        assertTrue(
            "宽屏并排那一支仍由同一个 isWideScreen 决定（不许换成自己再量一遍）",
            home.contains("} else if (isWideScreen && selectedTab == 0) {"),
        )
        assertTrue(
            "内容区日视图那一支的页签下标漂了（DAY_TAB_INDEX 与它必须同源）：",
            Regex("1\\s*->\\s*DayView\\(").containsMatchIn(home),
        )
        val dayTabIndex = Regex("internal const val DAY_TAB_INDEX = (\\d+)").find(
            blankComments(source("com/buaa/schedule/ui/home/DayBrowsePolicy.kt")),
        ) ?: throw AssertionError("DAY_TAB_INDEX 不再是个整型常量：页签这一维的判据无从可验")
        assertEquals("今日页签的下标必须仍是 1，与 HomeScreen 的 when (tab) 分支同源：", "1", dayTabIndex.groupValues[1])
    }

    // ---- ⑥ DayView 一行不碰（T70 刚改过它） ----

    @Test
    fun dayViewStaysOutOfThisCard() {
        val dayView = blankComments(source("com/buaa/schedule/ui/home/DayView.kt"))
        assertTrue(
            "DayView 不许读这道闸（本卡的靶心只有 HomeScreen 顶栏那一处，T70 刚改过这个文件）",
            !dayView.contains("dayViewOnScreen") && !dayView.contains("topBarDateLabel"),
        )
    }

    // ---- 靶子定位与词法小工具 ---------------------------------------------------

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
        throw IllegalStateException("$signature 的括号没配平")
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
}
