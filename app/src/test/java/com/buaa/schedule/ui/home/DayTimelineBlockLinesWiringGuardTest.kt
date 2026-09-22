package com.buaa.schedule.ui.home

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T61（时间轴色块文字密度）的接线守卫。
 *
 * 本模块没有 Compose 运行时（无 Robotic、无 ui-test），"这一行的出现与否真的由内核说了算"
 * 这种事实只能读主源码核对；刀法照抄 [DayTimelineStructureGuardTest] /
 * [CourseTitleRowBudgetGuardTest]：先抹注释（字符串保留），再按函数体切块，
 * 找不到源码目录直接抛、一个靶子都没扫到也判红（跳过的守卫比没有守卫更糟）。
 *
 * 钉住六件事：
 * 1. 内核 DayTimelineBlockLines.kt **零 import**（比"零 android"更严：它连 java.time 都不该要，
 *    所有事实都得由调用点量成数字递进来）。
 * 2. 色块里画哪几行确实由 `planDayTimelineBlockLines(...)` 回答，
 *    而 `38.dp` / `58.dp` 那两枚定值已经从 DayView.kt 里彻底消失。
 * 3. 递给内核的行高是从 MaterialTheme.typography 现取、按 fontScale 换算的，不是抄的常量。
 * 4. 纵向容量是唯一限制项：三/四行仍各自锁 `maxLines = 1` + 省略号（横向由省略号收口）。
 * 5. 课名那一行不在任何 `if` 里——它是内核里唯一 pinned 的行，接线不许把它也判掉。
 * 6. 教师走 joinMeta（缺项连同分隔符一起缺席），备注只在内核点头时才画。
 */
class DayTimelineBlockLinesWiringGuardTest {

    @Test
    fun kernelTakesEveryFactAsANumberAndImportsNothing() {
        val kernel = source(KERNEL)
        val imports = kernel.lines().map { it.trimStart() }.filter { it.startsWith("import") }
        assertTrue(
            "行预算内核要能在纯 JVM 上表驱动跑：它自己不许读 MaterialTheme / Density / Build，" +
                "也不许量文本——所有事实都得由调用点算成数字递进来。现在的 import：\n" +
                imports.joinToString("\n"),
            imports.isEmpty(),
        )
        assertTrue("内核里找不到 planDayTimelineBlockLines 的定义", kernel.contains("internal fun planDayTimelineBlockLines("))
    }

    @Test
    fun blockLinesComeFromTheKernelNotFromMagicHeights() {
        val body = timelineBody()
        assertTrue("DayTimelineCourseList 没再调行预算内核", body.contains("planDayTimelineBlockLines("))
        val dayView = blankComments(source(DAY_VIEW))
        val leftovers = listOf("38.dp", "58.dp", "blockHeight >=").filter { it in dayView }
        assertTrue(
            "按单一 fontScale 标定的定值门槛回来了：小字号下把教室那一行永远藏掉（本次修的就是它），" +
                "大字号下反过来把课名顶出去（T54）。行该不该画只问 planDayTimelineBlockLines：" +
                leftovers.joinToString("、"),
            leftovers.isEmpty(),
        )
    }

    @Test
    fun lineHeightsAreMeasuredFromTypographyAndFontScale() {
        val body = timelineBody()
        assertTrue("行高必须现取排版表，不许抄成常量", body.contains("MaterialTheme.typography.labelLarge"))
        assertTrue(body.contains("MaterialTheme.typography.labelMedium"))
        assertTrue("行高要按 fontScale 换算（dp 门槛不长、行高长，这就是 38/58 两头错的根）", body.contains("fontScale"))
        val dayView = blankComments(source(DAY_VIEW))
        assertTrue(
            "换算 sp → dp 的那件小事要有名字，别散成四处各乘一遍",
            dayView.contains("private fun timelineLineHeightDp(") &&
                dayView.contains("timelineLineHeightDp(MaterialTheme.typography.labelLarge") &&
                dayView.contains("timelineLineHeightDp(MaterialTheme.typography.labelMedium"),
        )
        // 内边距与摆放读同一枚数：改了 .padding 忘了改预算 = 每块都差 3dp
        assertTrue(
            "块的上下内边距必须与递给内核的那枚同源（BlockTextVerticalPadding）",
            body.contains("vertical = BlockTextVerticalPadding") &&
                body.contains("contentVerticalPaddingDp = BlockTextVerticalPadding.value"),
        )
    }

    /** 三/四行各自都要经过内核点头，且横向仍由 maxLines + 省略号收口 */
    @Test
    fun everyOptionalLineIsGatedByThePlanAndEllipsed() {
        val body = timelineBody()
        val missing = listOf(
            "DayTimelineBlockLine.TimeAndTeacher in linePlan",
            "DayTimelineBlockLine.Room in linePlan",
            "DayTimelineBlockLine.Remark in linePlan",
        ).filterNot { it in body }
        assertTrue("这些行没接进行预算：\n" + missing.joinToString("\n"), missing.isEmpty())
        val gated = listOf("TimeAndTeacher", "Room", "Remark").count { "DayTimelineBlockLine.$it in linePlan" in body }
        val ellipsed = Regex("maxLines = 1,\\s*overflow = TextOverflow\\.Ellipsis").findAll(body).count()
        assertTrue(
            "时间轴一个块该有 4 行文字、其中 3 行受行数预算管，且每一行都锁一行 + 省略号" +
                "（色块通栏，横向唯一的收口就是省略号）：受管 $gated 行、省略 $ellipsed 行",
            gated == 3 && ellipsed == 4,
        )
    }

    /** 课名永不裁：它在 Column 里排在任何 if 之前 */
    @Test
    fun courseNameLineIsNeverTheOneDropped() {
        val body = timelineBody()
        val planAt = body.indexOf("planDayTimelineBlockLines(")
        assertTrue("内核调用点没了", planAt >= 0)
        val columnAt = body.indexOf("Column {", planAt)
        val nameAt = body.indexOf("text = course.displayName", columnAt)
        val firstGate = body.indexOf("if (", columnAt)
        assertTrue(
            "课名那一行不许被包进行数预算的 if 里：它是内核里唯一 pinned 的行，" +
                "画不下也画（宁可被块的裁切吃掉一角，也不让这块颜色变成不知道是什么的东西）",
            columnAt in 0 until nameAt && firstGate > nameAt,
        )
    }

    /** 教师与备注：措辞走既有约定，缺席走既有机制 */
    @Test
    fun teacherRidesJoinMetaAndRemarkIsOptInByHeight() {
        val body = timelineBody()
        val joinAt = body.indexOf("joinMeta(")
        assertTrue("第二行不再走 joinMeta：悬空 \" · \" 的老毛病会回来（列表模式 dayCourseMetaLine 同一条账）", joinAt >= 0)
        val joinOpen = joinAt + "joinMeta".length
        val joinClose = matchingClose(body, joinOpen)
            ?: throw AssertionError("joinMeta 的括号配不上对，解析器该修了")
        val joinArgs = body.substring(joinOpen + 1, joinClose)
        assertTrue("教师要用上（这就是本次要补的内容之一）", "course.teacher" in joinArgs)
        assertTrue("时间段要用上", "hhmm(block.start)" in joinArgs)
        assertTrue(
            "备注要经过 takeIf { isNotBlank() } 才成为候选（空备注不该占掉一枚候选行）",
            body.contains("course.remark?.takeIf { it.isNotBlank() }"),
        )
        assertTrue("教室的占位口径要与列表模式一致（③C-05）", body.contains("?: \"教室未定\""))
    }

    /** 令牌：1.05 那一档留着说明注释与代码已经分家 */
    @Test
    fun perMinuteHeightIsTheNewDerivedValue() {
        val tokens = blankComments(source(TOKENS))
        assertTrue(
            "dayHeightPerMinute 该是 1.35.dp（45 分钟 → 60.75dp ≥ 三行 52dp + 上下内边距 6dp）",
            Regex("val dayHeightPerMinute = 1\\.35\\.dp").containsMatchIn(tokens),
        )
        assertTrue("旧的 1.05 还留在令牌里", !tokens.contains("1.05.dp"))
    }

    // ---- 靶子定位与词法小工具（与 DayTimelineStructureGuardTest 同一套） --------

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
}

private const val DAY_VIEW = "com/buaa/schedule/ui/home/DayView.kt"
private const val KERNEL = "com/buaa/schedule/ui/home/DayTimelineBlockLines.kt"
private const val TOKENS = "com/buaa/schedule/core/designsystem/DesignTokens.kt"
