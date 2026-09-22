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
 * 钉住七件事：
 * 1. 内核 DayTimelineBlockLines.kt **零 import**（比"零 android"更严：它连 java.time 都不该要，
 *    所有事实都得由调用点量成数字递进来）。
 * 2. 色块里画哪几行确实由 `planDayTimelineBlockLines(...)` 回答，
 *    而 `38.dp` / `58.dp` 那两枚定值已经从 DayView.kt 里彻底消失。
 * 3. 递给内核的行高是**装机实测**的（TextMeasurer 量一行 CJK 样例、按样式记忆），
 *    不是排版表里那枚标称 lineHeight 乘 fontScale——标称只是下限，按它记账只剩 1px 余量（T61b①）。
 * 4. 真超了也不许越出内容区：块把自己的文字裁第二道（T61b② 的那条安全带）。
 * 5. 纵向容量是唯一限制项：三/四行仍各自锁 `maxLines = 1` + 省略号（横向由省略号收口），
 *    宽度那枚投机参数已经从内核与调用点一起摘掉（T61b③，连带 `LocalConfiguration` 那条 lint）。
 * 6. 课名那一行不在任何 `if` 里——它是内核里唯一 pinned 的行，接线不许把它也判掉。
 * 7. 教师走 joinMeta（缺项连同分隔符一起缺席），备注只在内核点头时才画。
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

    /**
     * ③：行高是**量**出来的。T61 头一版把排版表的 `lineHeight × fontScale` 当行高递进来，
     * 而 Compose 的 lineHeight 只是下限：装机量到 labelMedium 17.14dp 而非标称 16，
     * 三行 54.1dp 而不是 52dp —— 按标称挑的门槛就此只剩 1px 真余量（T61b① 修的就是它）。
     */
    @Test
    fun lineHeightsAreMeasuredFromTheFontNotReadFromTheTypographyToken() {
        val body = timelineBody()
        assertTrue("行高必须现取排版表，不许抄成常量", body.contains("MaterialTheme.typography.labelLarge"))
        assertTrue(body.contains("MaterialTheme.typography.labelMedium"))
        assertTrue("行高要按 fontScale 换算（dp 门槛不长、行高长，这就是 38/58 两头错的根）", body.contains("fontScale"))
        val dayView = blankComments(source(DAY_VIEW))
        assertTrue(
            "换算这件事要有名字，别散成四处各乘一遍",
            dayView.contains("private fun timelineLineHeightDp(") &&
                dayView.contains("timelineLineHeightDp(MaterialTheme.typography.labelLarge") &&
                dayView.contains("timelineLineHeightDp(MaterialTheme.typography.labelMedium"),
        )
        assertTrue(
            "行高要下尺去量（TextMeasurer 量一行、取首行的行框），而不是读排版表那枚下限：" +
                "标称 52dp vs 实画 54.1dp 的差就是 T61b① 那一px",
            dayView.contains("textMeasurer.measure(") && dayView.contains("getLineBottom(0)"),
        )
        assertTrue(
            "测量要按样式记忆、一台设备一枚样式一次：每块各量一次 = 一屏十几节课白排十几遍文本",
            Regex("remember\\(style, fontScale, density").containsMatchIn(dayView) ||
                dayView.contains("remember(style, fontScale"),
        )
        assertTrue(
            "量的样例必须含汉字：拉丁样例在走 fallback 字体时少报行高，少的正是会被裁掉的那一行",
            dayView.contains("TimelineLineSample"),
        )
        // 内边距与摆放读同一枚数：改了 .padding 忘了改预算 = 每块都差 3dp
        assertTrue(
            "块的上下内边距必须与递给内核的那枚同源（BlockTextVerticalPadding）",
            body.contains("vertical = BlockTextVerticalPadding") &&
                body.contains("contentVerticalPaddingDp = BlockTextVerticalPadding.value"),
        )
    }

    /** ②：真超了也不许越出内容区——块把自己的文字裁第二道 */
    @Test
    fun residualOvershootIsClippedToThePlate() {
        val body = timelineBody()
        val padAt = body.indexOf("vertical = BlockTextVerticalPadding")
        val clipAt = body.indexOf(".clipToBounds()")
        assertTrue(
            "色块的文字没有第二道裁切：预算之外的残余会压在描边与圆角那一条带上，" +
                "把「这块到哪儿结束」糊掉（T54 的形状）。块要 clipToBounds（T61b②）",
            clipAt >= 0,
        )
        assertTrue(
            "clipToBounds 要落在 .padding(vertical = BlockTextVerticalPadding) 之后：裁的才是行预算" +
                "那枚内容上限（padding 之前裁就把内边距那一圈也算进去了），实际 clip@$clipAt pad@$padAt",
            clipAt > padAt,
        )
    }

    /** ③：宽度那枚投机参数——内核不许再收，调用点也不许再去读 screenWidthDp */
    @Test
    fun widthIsNotABudgetInputAnymore() {
        val kernel = blankComments(source(KERNEL))
        val dayView = blankComments(source(DAY_VIEW))
        assertTrue(
            "availableWidthDp 回来了：宽度从来不是这里的限制项（Column + 每行 maxLines=1 + 省略号），" +
                "量它要读 LocalConfiguration.screenWidthDp，白多一条 ConfigurationScreenWidthHeight 警告",
            !kernel.contains("availableWidthDp"),
        )
        assertTrue(
            "DayView 不该再读 screenWidthDp / LocalConfiguration（T61b③ 摘掉的就是它）",
            !dayView.contains("screenWidthDp") && !dayView.contains("LocalConfiguration"),
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

    /** 令牌：按**实测行高**挑的那一档；旧的两档（1.05 挤不出第三行、1.35 只剩 1px）都不许回来 */
    @Test
    fun perMinuteHeightIsTheNewDerivedValue() {
        val tokens = blankComments(source(TOKENS))
        assertTrue(
            "dayHeightPerMinute 该是 1.40.dp（45 分钟 → 63dp ≥ 实测三行 54.10dp + 上下内边距 6dp + 2dp 真余量）",
            Regex("val dayHeightPerMinute = 1\\.40\\.dp").containsMatchIn(tokens),
        )
        assertTrue("旧的 1.05 还留在令牌里", !tokens.contains("1.05.dp"))
        assertTrue("1.35 是按标称行高挑的那一档，只剩 1px 余量，不许留着", !tokens.contains("1.35.dp"))
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
