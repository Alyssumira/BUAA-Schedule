package com.buaa.schedule.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T52③ 的守卫：「现在」线的一分钟一跳补成一段滑行。
 *
 * 这个模块没有 Robolectric / Compose 布局测试，动画只能钉**机制**：
 * 刀法照抄 [DayTimelineStructureGuardTest] / CourseTitleRowBudgetGuardTest ——
 * 抹注释（字符串保留）、按大括号配对截函数体、一个靶子都没扫到也判红、
 * 找不到源码目录直接抛而不是 assumeTrue（跳过的守卫比没有守卫更糟）。
 *
 * 钉住的是四条：
 * 1. 规格走 `motionSpec`，时长取 MotionTokens 档位 —— 不许出现裸 `tween(` / 字面毫秒，
 *    否则 reduce-motion 与"治理判定跑不动玻璃"那两条开关在这里静默失效。
 * 2. 位移走 `graphicsLayer`，且动画值只在 layer 块里读 —— 走 `offset(Dp)` 或组合期读
 *    等于补间期间每帧重测/重组这条线（仓库里同族约束：WeekView 的 pulse、分段控件的胶囊）。
 * 3. 不加心跳、不加轮询：滑行的时长来源只能是**已有的** 15 秒/60 秒步长与令牌档。
 * 4. 周视图节次行模式那条线也接上了同一件共享画线件（两条线不该各修一次）。
 */
class NowLineGlideGuardTest {

    // ---- 纯判据：nowLineFraction --------------------------------------------

    @Test
    fun nowLineFractionKeepsOldBoundaries() {
        // 窗口 480..1320（08:00–22:00）
        assertEquals(0f, nowLineFraction(480, 480, 1320), 0f)
        assertEquals(1f, nowLineFraction(1320, 480, 1320), 0f)
        assertEquals(0.25f, nowLineFraction(690, 480, 1320), 0.0001f)
        // 窗口外夹到端点：调用点按 ≤0 / ≥1 决定画不画，越界不能画到轴外
        assertEquals(0f, nowLineFraction(300, 480, 1320), 0f)
        assertEquals(1f, nowLineFraction(2000, 480, 1320), 0f)
        // 脏节次表：窗口高 ≤0 时取 0（= 不画），与 T49 搬动前的 `if (total <= 0) 0f` 一致
        assertEquals(0f, nowLineFraction(600, 600, 600), 0f)
        assertEquals(0f, nowLineFraction(600, 700, 600), 0f)
    }

    @Test
    fun nowLineFractionLivesInAndroidFreeKernel() {
        val kernel = source(KERNEL)
        val offenders = kernel.lines().map { it.trimStart() }.filter { it.startsWith("import android") }
        assertTrue(
            "nowLineFraction 是这条线唯一的位置算式，它必须在纯 JVM 内核里（混进设备依赖就测不了）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
        assertTrue("nowLineFraction 的定义没了", kernel.contains("internal fun nowLineFraction("))
    }

    // ---- 结构：NowLine / NowGlideLine ---------------------------------------

    @Test
    fun nowLineDrawsThroughGlideHelperNotRawOffset() {
        val body = functionBody(AXIS, "internal fun NowLine(")
        assertTrue("NowLine 要把落点交给 nowLineFraction（纯判据）", body.contains("nowLineFraction("))
        assertTrue("NowLine 要把画线交给 NowGlideLine（两条线一处收口）", body.contains("NowGlideLine("))
        assertTrue(
            "NowLine 里不许再留裸 offset：那正是「一分钟瞬移两三个像素」的来源",
            !body.contains("offset("),
        )
    }

    @Test
    fun glideUsesTokenSpecAndNeverBareTween() {
        val body = functionBody(AXIS, "internal fun NowGlideLine(")
        assertTrue("补间必须走 animateFloatAsState（可打断：第二次输入从当前值继续）", body.contains("animateFloatAsState("))
        assertTrue("规格必须走 motionSpec，reduce-motion 才在这一条上生效", body.contains("motionSpec<Float>"))
        assertTrue(
            "时长必须是 MotionTokens 档位——不许新增时长常数（对照 MotionTokens.DURATION_LONG 的注释）",
            body.contains("MotionTokens.DURATION_LONG"),
        )
        assertTrue("出现了裸 tween(", !body.contains("tween("))
        assertTrue("出现了裸 spring(", !body.contains("spring("))
        assertTrue(
            "spec 里出现了字面毫秒，改走 MotionTokens：\n" + literalMillis(body).joinToString("\n"),
            literalMillis(body).isEmpty(),
        )
        assertTrue(
            "越界不画的边界行为只能有一份（NowGlideLine 自己判），NowLine 不再重复判",
            body.contains("fraction <= 0f || fraction >= 1f"),
        )
    }

    /** ②：位移走 layer、动画值只在绘制期读——这两条是同一条性能账的两面 */
    @Test
    fun glideTranslatesByLayerAndReadsValueOnlyInDrawPhase() {
        val body = functionBody(AXIS, "internal fun NowGlideLine(")
        val layerLines = body.lines().filter { "graphicsLayer" in it }
        assertTrue("NowGlideLine 该有一处 graphicsLayer，实际：$layerLines", layerLines.isNotEmpty())
        val offenders = body.lines().map { it.trim() }.filter { line ->
            line.contains(".value") && !line.contains("graphicsLayer") && !line.contains("animateFloatAsState")
        }
        assertTrue(
            "动画值只能在 graphicsLayer 块里读：组合期读等于补间期间每帧重组这条线" +
                "（同族约束见 WeekView 的 pulse 注释、分段控件的胶囊尺寸）：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
        assertTrue(
            "落点取整口径要和 Modifier.offset(Dp) 的 roundToPx 对齐，否则静止位置差半像素",
            body.contains("roundToInt()"),
        )
    }

    /** ③：这条链不许多付心跳——滑行不是把 15 秒改成 1 秒的理由 */
    @Test
    fun glideAddsNoNewTickOrPolling() {
        val axis = blankComments(source(AXIS))
        assertTrue(
            "TimelineAxis.kt 不该出现任何定时/轮询原语：滑行的时长来自令牌档，节奏仍由调用方的 15 秒链给",
            !axis.contains("delay(") && !axis.contains("while (") && !axis.contains("LaunchedEffect"),
        )
        val dayView = blankComments(source(DAY_VIEW))
        assertTrue(
            "日视图的链仍然只按 TIMELINE_TICK_MS 那一拍发布（不许为了动画把频率调高）",
            dayView.contains("TIMELINE_TICK_MS") && !dayView.contains("MINUTE_TICK_MS"),
        )
    }

    /** ④：周视图节次行那条线接上同一件画线件 */
    @Test
    fun weekPeriodLineSharesTheGlide() {
        val week = blankComments(source(WEEK_VIEW))
        val body = functionBody(WEEK_VIEW, "private fun NowLinePeriod(")
        assertTrue("节次行模式的现在线也要走 NowGlideLine", body.contains("NowGlideLine("))
        assertTrue(
            "两条现在线不许各画一份 offset + background(error)：越界边界行为此前就分叉过一次（T49 提取的动机）",
            !body.contains("offset(y = totalHeight"),
        )
        assertTrue("周视图还要接着用共享的 NowLine（24h 模式那条）", week.contains("NowLine("))
    }

    // ---- 靶子定位与词法小工具（与 DayTimelineStructureGuardTest 同一套）----------------

    private fun functionBody(relative: String, signature: String): String {
        val code = blankComments(source(relative))
        val at = code.indexOf(signature)
        assertTrue("找不到 $signature —— 靶子没了（$relative）", at >= 0)
        val brace = code.indexOf('{', at)
        val end = matchingClose(code, brace)
            ?: throw AssertionError("$relative 里 $signature 的花括号配不上对，解析器该修了")
        assertTrue("$signature 的函数体终点在起点之前？", end > brace)
        return code.substring(brace, end)
    }

    /** spec 参数里的字面毫秒（`durationMillis = 380` / `delayMillis = 90`）：令牌引用不算 */
    private fun literalMillis(body: String): List<String> = body.lines()
        .map { it.trim() }
        .filter { Regex("""(duration|delay)Millis\s*=\s*\d""").containsMatchIn(it) }

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
        const val AXIS = "com/buaa/schedule/ui/home/TimelineAxis.kt"
        const val KERNEL = "com/buaa/schedule/ui/home/DayTimelineAxis.kt"
        const val DAY_VIEW = "com/buaa/schedule/ui/home/DayView.kt"
        const val WEEK_VIEW = "com/buaa/schedule/ui/home/WeekView.kt"
    }
}
