package com.buaa.schedule.ui.home

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T55 的守卫：「现在」这条线不许再压在课程卡上。
 *
 * 装机实测（buaa36，周一 17:41）两张证据图：今日页时间轴那条 2dp 红线从 17:30–18:15
 * 「思想政治」的标题中间横过去；周课表网格同一条线把第 10 节三张卡的名字各划一道。
 * 修法是**层级**：Box 的子项按声明顺序叠放，把线挪到课程块/七天列**之前**，
 * 卡片盖住它、空档处照旧露出来。
 *
 * 本模块没有 Robolectric / Compose 布局测试，量不了"谁盖住谁"，只能钉**声明顺序**——
 * 刀法照抄 [DayTimelineStructureGuardTest] / [NowLineGlideGuardTest]：
 * 抹注释（字符串保留）、按函数体切块、一个靶子都没扫到也判红、
 * 找不到源码目录直接抛而不是 assumeTrue（跳过的守卫比没有守卫更糟）。
 *
 * 钉住四条：
 * 1. 日视图时间轴：NowLine 的声明落在 `blocks.forEachIndexed` 之前、且在课间线之后
 *    （线该在整点线与课间虚线之上、课程块之下，两头都不许倒过来）。
 * 2. 周课表：两条现在线（24h 与节次行模式）的声明落在 `dayNames.forEachIndexed` 之前，
 *    且仍在七天区那层 Box 里（挪出滚动容器就是另一回事了）。
 * 3. 每个视图各只有一处现在线调用点——不许"底下画一条、顶上再叠一条"。
 * 4. 注释不许反过来骗人：源码里再出现"线压在块之上/线在最后画"这类断言，
 *    要么真把顺序改回去了（那 1、2 先红），要么注释与接线不符（这里红）。
 */
class NowLineUnderCardsGuardTest {

    @Test
    fun dayTimelineDrawsNowLineBeneathCourseBlocks() {
        val body = timelineBody()
        val nowLine = at(body, "NowLine(")
        val blocks = at(body, "blocks.forEachIndexed")
        val gapLine = at(body, "dayTimelineGaps(")
        assertTrue(
            "「现在」线必须画在课程块之前（Box 子项按声明顺序叠放，声明在前＝在底下），" +
                "否则 2dp 的红线就从课程名中间横过去——装机实测把「思想政治」划成了删掉的字。" +
                "现在线 @$nowLine 块 @$blocks",
            nowLine < blocks,
        )
        assertTrue(
            "线仍要在整点网格线与课间虚线**之上**（那两层是轴的底纹，被线盖住才对）：@$gapLine",
            gapLine < nowLine,
        )
    }

    @Test
    fun weekGridDrawsNowLineBeneathDayColumns() {
        val code = blankComments(source(WEEK_VIEW))
        val area = at(code, "daysAreaModifier")
        val nowLine = at(code, "NowLine(")
        val periodLine = at(code, "NowLinePeriod(")
        val columns = at(code, "dayNames.forEachIndexed")
        assertTrue("周视图的七天区调用点没了（daysAreaModifier）", area in 0 until nowLine)
        assertTrue(
            "24h 模式那条线必须画在七天列之前：@$nowLine 列 @$columns",
            nowLine < columns,
        )
        assertTrue(
            "节次行模式那条线同样要在七天列之前——实测被划掉的就是这一档（第 10 节三张卡）：@$periodLine",
            periodLine < columns,
        )
    }

    /** ③：一处收口。"底下补一条、原来那条留着"会让线在卡片边缘露出双影 */
    @Test
    fun eachViewCallsTheNowLineExactlyOnce() {
        val day = blankComments(source(DAY_VIEW))
        val week = blankComments(source(WEEK_VIEW))
        val dayHits = countCalls(day, "NowLine(")
        // NowLinePeriod( 里也含 "NowLine"，但括号前还跟着标识符，所以它不会被算进 24h 那一档
        val weekGlideHits = countCalls(week, "NowLine(")
        val weekPeriodHits = countCalls(week, "NowLinePeriod(")
        assertTrue("日视图该恰有一处现在线调用点，实际 $dayHits 处", dayHits == 1)
        assertTrue(
            "周视图的两条现在线（24h 一条 + 节次行一条）该各恰有一处调用点，" +
                "实际 24h $weekGlideHits 处、节次行 $weekPeriodHits 处",
            weekGlideHits == 1 && weekPeriodHits == 1,
        )
    }

    /** ④：注释与 z 序一致——旧结论"线在最后画"正是这次要改掉的接线 */
    @Test
    fun staleOverlayCommentsAreNotClaimedBack() {
        for ((relative, stale) in listOf(DAY_VIEW to "线压在块之上", WEEK_VIEW to "线在最后画")) {
            val raw = source(relative)
            assertTrue(
                "$relative 的注释又断言「$stale」——它和上面的声明顺序钉子只能有一个是对的",
                !raw.contains(stale),
            )
        }
    }

    // ---- 靶子定位与词法小工具（与 DayTimelineStructureGuardTest 同一套）----------------

    /** 靶子必须存在：找不到就红，而不是返回 -1 让上面的大小比较"恰好"成立 */
    private fun at(code: String, needle: String): Int {
        val idx = code.indexOf(needle)
        assertTrue("找不到 $needle —— 靶子没了，这条钉子的前提已经不成立", idx >= 0)
        return idx
    }

    /**
     * 数**调用点**：
     * - `NowLine(` 不算 `NowLinePeriod(` 的那一次（后者括号前还跟着标识符）；
     * - `fun NowLinePeriod(` 这种定义行也不算（`NowLinePeriod` 就定义在 WeekView 里）。
     */
    private fun countCalls(code: String, needle: String): Int = Regex(
        "(?<![A-Za-z0-9_])(?<!fun )" + Regex.escape(needle),
    ).findAll(code).count()

    /** DayTimelineCourseList 的函数体（注释已抹，字符串保留） */
    private fun timelineBody(): String {
        val code = blankComments(source(DAY_VIEW))
        val at = code.indexOf("private fun DayTimelineCourseList(")
        assertTrue("找不到 DayTimelineCourseList：靶子没了", at >= 0)
        val brace = code.indexOf('{', at)
        val end = matchingClose(code, brace)
            ?: throw AssertionError("DayTimelineCourseList 的花括号配不上对，解析器该修了")
        assertTrue("DayTimelineCourseList 函数体终点在起点之前？", end > brace)
        return code.substring(brace, end)
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
    }
}
