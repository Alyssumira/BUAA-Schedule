package com.buaa.schedule.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T52② 的接线守卫：三处课程卡进场（今日列表 / 今日时间轴 / 周课表网格）确实挂着，
 * 且几条设计决定不许在改动中静默流失。
 *
 * 这个模块没有 Robolectric / Compose 布局测试，动画只能钉**结构**：刀法照抄
 * [NowLineGlideGuardTest] / CourseTitleRowBudgetGuardTest —— 抹注释、按大括号配对
 * 截函数体、一个靶子都没扫到也判红、找不到源码目录直接抛而不是 assumeTrue
 * （跳过的守卫比没有守卫更糟）。
 *
 * 钉住的是六条：
 * 1. playKey 只能是常量（EntrancePlaybook.X 或字符串字面量）——键里掺进日期/课程数，
 *    "只播一次"立刻退化成"改一次重播一遍"。
 * 2. 三把键各就各位且各一次：今日页两模式在 DayView.kt，周网格在 WeekView.kt。
 * 3. 一屏只有一条驱动：整条主源码链上 `label = "courseEntrance"` 只许出现一次
 *    （WeekDensityStrip 否掉过"同一次进场各起一个动画"）。
 * 4. 今日页两种模式逐卡各挂一层 graphicsLayer 调 applyTo；周网格反过来——CourseCell
 *    自带按压/脉冲的缩放层，进场必须并进那同一层（applyTo），叠第二层 graphicsLayer 判红。
 * 5. 动画值只在绘制期读：drive.value 只活在 applyTo 里，整条链不出现裸 tween/spring/
 *    字面毫秒（时长只有 MotionTokens 一个来源）。
 * 6. 判据内核零 android import；resetForTests 在主源码里只许有定义那一处
 *    （生产代码清记忆 = 每次调用都把"只播一次"作废）。
 */
class CourseEntranceWiringGuardTest {

    // ---- ①②：playKey 是常量，三把键各就各位 --------------------------------

    @Test
    fun everyEntranceSitePassesAConstantPlayKey() {
        val sites = callArgs(DAY_VIEW, "rememberCourseEntrance(") + callArgs(WEEK_VIEW, "rememberCourseEntrance(")
        assertTrue("三处进场一个靶子都没扫到——接线被拆了", sites.isNotEmpty())
        val constKey = Regex("""EntrancePlaybook\.(DAY_LIST|DAY_TIMELINE|WEEK_GRID)|"[A-Za-z0-9_-]+"""")
        sites.forEach { arg ->
            assertTrue(
                "playKey 必须是常量：掺进日期/课程数的键 = 改一次数据重播一次（$arg）",
                constKey.matches(arg.trim()),
            )
        }
        val keys = sites.map { it.trim() }.toSet()
        assertEquals("每个键只许有一个挂接点（今日列表/今日时间轴/周网格）：$keys", 3, sites.size)
        assertTrue("DayView 该挂 day-list", keys.any { it.contains("DAY_LIST") })
        assertTrue("DayView 该挂 day-timeline", keys.any { it.contains("DAY_TIMELINE") })
        assertTrue("WeekView 该挂 week-grid", keys.any { it.contains("WEEK_GRID") })
        assertTrue(
            "今日页两把键在 DayView.kt、周网格在 WeekView.kt，不许搬家到别的文件",
            callArgs(DAY_VIEW, "rememberCourseEntrance(").size == 2 &&
                callArgs(WEEK_VIEW, "rememberCourseEntrance(").size == 1,
        )
    }

    // ---- ③：一屏一条驱动 ------------------------------------------------------

    @Test
    fun driverIsCreatedExactlyOnceForWholeApp() {
        val entrances = source(COURSE_ENTRANCE)
        val code = blankComments(entrances)
        assertEquals(
            "驱动动画只许在 rememberCourseEntrance 里起一条（逐卡各起是 WeekDensityStrip 否掉的写法）",
            1,
            occurrences(code, "animateFloatAsState("),
        )
        assertEquals(
            "label = \"courseEntrance\" 只许出现一次：出现第二处说明有人又起了第二条同族驱动",
            1,
            occurrences(code, "label = \"courseEntrance\""),
        )
        assertTrue(
            "时长必须走 motionSpec<Float>(MotionTokens.DURATION_LONG)，reduce-motion 才在这条上生效",
            code.contains("motionSpec<Float>(MotionTokens.DURATION_LONG)"),
        )
        assertTrue("出现了裸 tween(", !code.contains("tween("))
        assertTrue("出现了裸 spring(", !code.contains("spring("))
        assertTrue(
            "出现了字面毫秒，改走 MotionTokens：\n" + literalMillis(code).joinToString("\n"),
            literalMillis(code).isEmpty(),
        )
    }

    // ---- ④：挂接方式——今日页逐卡 modifier，周网格并进同一层 ------------------

    @Test
    fun dayViewAttachesPerCardLayerInBothModes() {
        val code = blankComments(source(DAY_VIEW))
        assertEquals(
            "今日页列表 + 时间轴各一处逐卡挂层（graphicsLayer + applyTo）：少一处就是又回到整屏一起淡",
            2,
            occurrences(code, "entrance.applyTo(this,"),
        )
        // 名次取自渲染循环的下标、总数取自被渲染的那个列表——两头都得钉住
        assertTrue("列表模式的名次该取 itemsIndexed 的下标", code.contains("entrance.applyTo(this, index, rows.size)"))
        assertTrue("时间轴模式的名次该取 forEachIndexed 的下标", code.contains("entrance.applyTo(this, blockIndex, blocks.size)"))
        assertEquals("两处挂层都得包在 graphicsLayer 块里（绘制期读）", 2, occurrences(code, "Modifier.graphicsLayer {"))
    }

    @Test
    fun weekGridMergesIntoCourseCellsExistingLayer() {
        val code = blankComments(source(WEEK_VIEW))
        assertEquals(
            "周网格不许另起 entrance.modifier：CourseCell 已有按压/脉冲的 graphicsLayer，" +
                "两层各持一份 alpha 没法合账——必须走 applyTo 并进同一层",
            0,
            occurrences(code, "entrance.modifier("),
        )
        val body = functionBody(WEEK_VIEW, "private fun CourseCell(")
        val layer = graphicsLayerBlocks(body)
        assertTrue("CourseCell 的缩放层没了——解析器或靶子该修了", layer.isNotEmpty())
        val merged = layer.filter { it.contains("entrance.applyTo(this") }
        assertEquals("进场变换必须并进那张既有的 graphicsLayer，恰好一处", 1, merged.size)
        assertEquals(
            "两处 CourseCell 挂接点（24h 与节次行模式）都要把名次传下去",
            2,
            occurrences(code, "entranceSlotCount = entranceCardCount"),
        )
        assertEquals(2, occurrences(code, "entranceSlot = entranceRanks["))
    }

    // ---- ⑤：动画值只在绘制期读 ------------------------------------------------

    @Test
    fun driveValueIsReadOnlyInsideLayerScope() {
        val body = functionBody(COURSE_ENTRANCE, "fun applyTo(")
        assertEquals(
            "drive.value 只许在 applyTo（graphicsLayer 块内）读：组合期读 = 补间期间每张卡每帧重组",
            1,
            occurrences(body, "drive.value"),
        )
        val code = blankComments(source(COURSE_ENTRANCE))
        assertEquals("全文件 drive.value 就那一处", 1, occurrences(code, "drive.value"))
    }

    // ---- ⑥：内核纯净 + resetForTests 不进生产 --------------------------------

    @Test
    fun kernelStaysAndroidFree() {
        val kernel = source(PLAYBOOK)
        val offenders = kernel.lines().map { it.trimStart() }.filter { it.startsWith("import android") }
        assertTrue(
            "进场判据是纯 JVM 内核，混进设备依赖就测不了（设备事实当参数传）：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
        assertTrue("entranceSlotProgress 的定义没了", kernel.contains("internal fun entranceSlotProgress("))
        assertTrue("entranceSettleFraction 的定义没了", kernel.contains("internal fun entranceSettleFraction("))
    }

    @Test
    fun resetForTestsHasNoProductionCaller() {
        val mainDir = findMainJavaDir()
        val callers = mainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { occurrences(blankComments(it.readText()), "resetForTests") > 0 }
            .map { it.name }
            .toList()
        assertEquals(
            "resetForTests 只许活在 EntrancePlaybook.kt 的定义里：生产代码清记忆 = 「每进程播一次」被作废",
            listOf("EntrancePlaybook.kt"),
            callers,
        )
    }

    // ---- 靶子定位与词法小工具（与 NowLineGlideGuardTest 同一套）----------------

    /** [relative] 里每处 [call]（如 `rememberCourseEntrance(`）括号内的实参文本 */
    private fun callArgs(relative: String, call: String): List<String> {
        val code = blankComments(source(relative))
        val out = mutableListOf<String>()
        var at = code.indexOf(call)
        while (at >= 0) {
            val open = at + call.length - 1
            val close = matchingClose(code, open)
                ?: throw AssertionError("$relative 里 $call 的括号配不上对，解析器该修了")
            out += code.substring(open + 1, close)
            at = code.indexOf(call, at + call.length)
        }
        return out
    }

    /** 函数体里每个 `graphicsLayer { … }` 块的完整文本（含外层花括号） */
    private fun graphicsLayerBlocks(body: String): List<String> {
        val out = mutableListOf<String>()
        var at = body.indexOf("graphicsLayer")
        while (at >= 0) {
            val open = body.indexOf('{', at)
            if (open < 0) break
            val close = matchingClose(body, open) ?: throw AssertionError("graphicsLayer 块花括号配不上对")
            out += body.substring(open, close + 1)
            at = body.indexOf("graphicsLayer", close)
        }
        return out
    }

    private fun functionBody(relative: String, signature: String): String {
        val code = blankComments(source(relative))
        val at = code.indexOf(signature)
        assertTrue("找不到 $signature —— 靶子没了（$relative）", at >= 0)
        // 参数表里就可能有花括号（CourseCell 的 `onClick: () -> Unit = {}`）：
        // 函数体的 `{` 只从配平的 `)` 之后找起，否则截到的是某个默认参数
        val paren = matchingClose(code, at + signature.length - 1)
            ?: throw AssertionError("$relative 里 $signature 的参数括号配不上对，解析器该修了")
        val brace = code.indexOf('{', paren)
        val end = matchingClose(code, brace)
            ?: throw AssertionError("$relative 里 $signature 的花括号配不上对，解析器该修了")
        assertTrue("$signature 的函数体终点在起点之前？", end > brace)
        return code.substring(brace, end)
    }

    /** spec 参数里的字面毫秒（`durationMillis = 380` / `delayMillis = 90`）：令牌引用不算 */
    private fun literalMillis(body: String): List<String> = body.lines()
        .map { it.trim() }
        .filter { Regex("""(duration|delay)Millis\s*=\s*\d""").containsMatchIn(it) }

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var at = haystack.indexOf(needle)
        while (at >= 0) {
            count++
            at = haystack.indexOf(needle, at + needle.length)
        }
        return count
    }

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
        const val COURSE_ENTRANCE = "com/buaa/schedule/ui/home/CourseEntrance.kt"
        const val PLAYBOOK = "com/buaa/schedule/ui/home/EntrancePlaybook.kt"
    }
}
