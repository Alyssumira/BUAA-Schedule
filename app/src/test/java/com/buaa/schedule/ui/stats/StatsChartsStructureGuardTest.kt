package com.buaa.schedule.ui.stats

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T51（学期可视化三件套）的结构守卫——本模块没有 Compose 运行时（无 Robolectric、
 * 无 ui-test），"图到底接没接上"只能走纯 JVM 源码结构扫描，刀法与
 * [com.buaa.schedule.ui.home.DayTimelineStructureGuardTest] 一致：
 * 先抹注释（字符串保留——要找的靶子一半是文案字面量），再按花括号配对只取函数体；
 * 找不到源码目录直接抛、零命中直接失败，不用 assumeTrue 跳过
 * （"没找到就算过"的守卫会在下一次改名时静默变绿）。
 *
 * 钉两件事：
 * 1. 三张新图（CourseWeekGantt / WeekFreeHeatGrid / WeeklyLoadTrendChart）确实都
 *    挂在 StatsScreen 的卡片里，且判据内核是在 remember 里算的（不许组合期全学期重算）、
 *    Gantt 的课程色走 legibleTintPlate 那条推导链（不许裸铺）；
 * 2. ganttRowDescription / heatGridDayDescription 两处读屏文案都挂在 semantics 上——
 *    Canvas 画的色块读屏拿不到，摘掉挂钩这一层，读屏用户就只剩一片色块。
 */
class StatsChartsStructureGuardTest {

    @Test
    fun statsScreenMountsAllThreeNewCharts() {
        val code = blankComments(source("com/buaa/schedule/ui/stats/StatsScreen.kt"))
        val required = listOf(
            "CourseWeekGantt(",        // 周次覆盖
            "WeekFreeHeatGrid(",       // 空档分布
            "WeeklyLoadTrendChart(",   // 负载趋势
            "SectionHeader(\"周次覆盖\")",
            "SectionHeader(\"空档分布\")",
            "SectionHeader(\"负载趋势\")",
        )
        val missing = required.filterNot { it in code }
        assertTrue(
            "统计页少了 T51 的图或它的卡头，接线被摘了：\n" + missing.joinToString("\n"),
            missing.isEmpty(),
        )
    }

    @Test
    fun kernelsRunInsideRememberNotDuringComposition() {
        val code = blankComments(source("com/buaa/schedule/ui/stats/StatsScreen.kt"))
        for (call in listOf("CourseWeekSpans.board(", "WeekFreeGrid.gridOf(", "WeeklyLoadTrend.trendOf(")) {
            val at = code.indexOf(call)
            assertTrue("$call 根本不在统计页里——判据算完没人画？", at >= 0)
            val before = code.substring(0, at)
            // 调用点上溯：最近的 remember( 必须比最近的 } 更靠后，才算待在 remember 的 lambda 里
            assertTrue(
                "$call 没包在 remember(...) 里——全学期重算不许在组合期反复跑",
                before.lastIndexOf("remember(") > before.lastIndexOf('}'),
            )
        }
        // 取色推导链：Gantt 的课程色不许是裸的 courseColor(course)（T23/T25b/T29 真机校准的账）
        val card = statsBody("private fun WeekCoverageCard(")
        assertTrue(
            "WeekCoverageCard 没过 legibleTintPlate + coursePlateSceneLuma 链，色块是裸铺的",
            "legibleTintPlate(" in card && "coursePlateSceneLuma(" in card,
        )
    }

    @Test
    fun canvasDescriptionsAreWiredIntoSemantics() {
        val gantt = chartBody("fun CourseWeekGantt(")
        assertTrue(
            "Gantt 行的读屏文案没挂 semantics：ganttRowDescription 算出来的东西读屏拿不到",
            "ganttRowDescription(" in gantt &&
                "contentDescription" in gantt &&
                "semantics {" in gantt,
        )
        val heat = chartBody("fun WeekFreeHeatGrid(")
        assertTrue(
            "热力格行名的读屏文案没挂 semantics：heatGridDayDescription 同上",
            "heatGridDayDescription(" in heat &&
                "contentDescription" in heat &&
                "semantics {" in heat,
        )
    }

    // ---- 靶子定位与词法小工具（与 DayTimelineStructureGuardTest 同族） ----------------

    /** ScheduleCharts.kt 里指定组件的函数体（注释已抹，字符串保留） */
    private fun chartBody(signature: String): String =
        functionBody("com/buaa/schedule/core/designsystem/ScheduleCharts.kt", signature)

    private fun statsBody(signature: String): String =
        functionBody("com/buaa/schedule/ui/stats/StatsScreen.kt", signature)

    private fun functionBody(relative: String, signature: String): String {
        val code = blankComments(source(relative))
        val at = code.indexOf(signature)
        assertTrue("找不到 $signature：靶子没了（$relative）", at >= 0)
        // 签名的尖括号/圆括号里没有花括号，第一个 { 就是函数体开头
        val brace = code.indexOf('{', at)
        val end = matchingClose(code, brace)
            ?: throw AssertionError("$relative 的花括号配不上对，解析器该修了")
        assertTrue("$relative 函数体终点在起点之前？", end > brace)
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
}
