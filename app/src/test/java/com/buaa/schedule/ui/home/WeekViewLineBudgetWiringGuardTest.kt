package com.buaa.schedule.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T296③：周网格行预算的**源码形状**守卫（在册 #537 F1/F2/F3 的落点）。
 *
 * 本模块没有 Compose 运行时，"这一行画不画真的由内核说了算"这种事实只能读主源码核对；
 * 刀法照抄 [DayTimelineBlockLinesWiringGuardTest] / [DayTimelineStructureGuardTest]：
 * 先抹注释（字符串保留、长度与换行位置不变），再按函数体切块；找不到源码目录直接抛，
 * 一个靶子都没扫到也判红（跳过的守卫比没有守卫更糟）。报错消息里**不写行号**——
 * 行号会随上一次改动一起过期，那种消息读起来像线索、实际上是假账。
 *
 * 每格标了钉法：**逐字节**（锚点原文比对）/ **抹注释找锚点**（注释里再出现一次不算数）/
 * **数出现次数**（枚数台账）/ **读形状**（先后与包含关系）。
 *
 * 钉住六件事：
 * 1. 课程卡那条路上没有 `?: 16.dp` 型的标称预算（F1）。
 * 2. 节次轴的判定吃**三行**，两行时间不再无条件在场（F2）。
 * 3. `weekFitViewport` 那一档的行高**照吃** `weekRowScale`，且视口是量出来的（F3 第一笔）。
 * 4. 设置页那一行把这一档的字段与代价说实话（F3 第二笔：屏上不能两句各说各的）。
 * 5. 内核零 import、不读设备事实（本仓成文禁令）。
 * 6. 两处保留定高的容器都补了 clipToBounds，且落在 padding/height **之后**（先量后裁）。
 */
class WeekViewLineBudgetWiringGuardTest {

    /** ①：课程卡路径里没有标称行高预算（抹注释找锚点 + 逐字节） */
    @Test
    fun cardPathHasNoNominalLineHeightBudgetLeft() {
        val week = blankComments(source(WEEK_VIEW))
        val leftovers = NOMINAL_BUDGET_SHAPES.filter { it in week }
        assertTrue(
            "**抹注释找锚点**：周网格（含课程卡与节次轴）里回来了标称行高预算 —— 这些算式吃的是 " +
                "TextStyle.lineHeight，而 Compose 的 lineHeight 是**下限**不是行盒（装机标称 16dp / " +
                "实画 17.14dp，每行朝宽松一侧多承诺 ~7%），T61b① 判死过一次、#537 F1 就是它在周网格的落点：" +
                leftovers.joinToString("、"),
            leftovers.isEmpty(),
        )
        assertTrue(
            "**逐字节**：课程卡的预算必须由 planWeekCourseCard 回答，行高得是递进来的实测值",
            week.contains("planWeekCourseCard(") &&
                week.contains("titleLineHeightDp = labelLineHeightDp") &&
                week.contains("metaLineHeightDp = labelLineHeightDp"),
        )
        assertTrue(
            "**读形状**：递给内核的卡高与内边距必须是同一处真值（写死 5.dp 就是"预算与可用高度分叉"的老路）",
            week.contains("cardHeightDp = cardHeight.value.toDouble()") &&
                week.contains("contentVerticalPaddingDp = CourseCellContentPadding.value.toDouble()"),
        )
    }

    /** ②：一台设备一枚样式量一次 —— 尺只造在一处，逐卡不许各量一遍（数出现次数） */
    @Test
    fun measuredRulerIsBuiltOnceAtTheGridLayer() {
        val week = blankComments(source(WEEK_VIEW))
        assertEquals(
            "**数出现次数**：TextMeasurer 在 WeekView 里只许造一枚（网格层），逐卡各造就成了" +
                "「两处各造就会各信各的字体测量值」（本仓对 StatsEntry 同一族写的判语）",
            1,
            occurrences(week, "rememberTimelineTextMeasurer("),
        )
        assertEquals(
            "**数出现次数**：实测行高在 WeekView 里只下一次尺（labelMedium），往下递",
            1,
            occurrences(week, "timelineLineHeightDp("),
        )
        assertEquals(
            "**数出现次数**：这枚实测行高要有两枚形参收下（节次轴 TimeLabels + 课程卡 CourseCell 各一枚），" +
                "调用点三处（轴一处、卡两处）",
            2,
            occurrences(week, "labelLineHeightDp: Double,"),
        )
        assertEquals(
            "**数出现次数**：labelLineHeightDp 往下递的调用点 = 3（TimeLabels 一处 + 两种画法各一处）",
            3,
            occurrences(week, "labelLineHeightDp = labelLineHeightDp,"),
        )
    }

    /** ③：节次轴的判定吃三行，两行时间在 showTwoLineTime 那一支里（读形状 + 数出现次数） */
    @Test
    fun axisDecisionCountsBothTimeLines() {
        val body = funBody(blankComments(source(WEEK_VIEW)), "private fun TimeLabels(")
        assertEquals(
            "**数出现次数**：轴上的行取舍只许问一次内核",
            1,
            occurrences(body, "planWeekTimeAxis("),
        )
        assertTrue(
            "**读形状**：节次号那一支的门必须读内核答复，改前那是 `height >= labelLineHeight * 3`" +
                "（只管第一行、且吃标称尺）",
            body.contains("if (axisPlan.showPeriodNumber)"),
        )
        val gateBack = listOf("labelLineHeight * 3", "height >=", "?: 16.dp").filter { it in body }
        assertTrue(
            "**抹注释找锚点**：旧的单行门槛回来了（它从不决定两行时间画不画）：" + gateBack.joinToString("、"),
            gateBack.isEmpty(),
        )
        val whenAt = body.indexOf("when {")
        val twoLineAt = body.indexOf(TWO_LINE_LITERAL)
        val startOnlyAt = body.indexOf("axisPlan.showStartOnly -> Text(")
        assertTrue(
            "**读形状**：两行起止时间必须待在 when 的分支里，而不是无条件在场（maxLines = 2 管的是行数上限、" +
                "Ellipsis 收的是横向超宽，两者都不管纵向预算）",
            whenAt >= 0 && twoLineAt > whenAt && startOnlyAt > twoLineAt,
        )
        assertEquals(
            "**读形状**：两行时间那一支只许它自己一个 Text（不许再挂一枚不受预算管的第二行）",
            1,
            countBetween(body, "axisPlan.showTwoLineTime -> Text(", TWO_LINE_LITERAL),
        )
        assertTrue(
            "**逐字节**：容量只够一行时只画起时（48dp 定宽列里并一行横向本来就放不下，见内核那段说明）",
            body.contains("text = slot.startTime,"),
        )
        assertTrue(
            "**逐字节**：一行都不够时整档不画 —— 而不是把两行数字甩在彼此身上",
            body.contains("else -> Unit"),
        )
    }

    /** ④：两处保留定高的容器都裁了，且裁在 padding/height 之后（读形状 + 数出现次数） */
    @Test
    fun fixedHeightContainersAreClippedAfterTheirPadding() {
        val week = blankComments(source(WEEK_VIEW))
        assertEquals(
            "**数出现次数**：WeekView 的两枚定高容器（课程卡内容列、节次轴行）各补一枚 clipToBounds —— " +
                "枚数变了就是有人新加/删掉了一处定高，那处也要按「先量后裁」补账",
            2,
            occurrences(week, ".clipToBounds()"),
        )
        val cardBody = funBody(week, "private fun CourseCell(")
        val padAt = cardBody.indexOf(".padding(CourseCellContentPadding)")
        val clipAt = cardBody.indexOf(".clipToBounds()")
        assertTrue(
            "**读形状**：课程卡的 clip 要在 .padding(CourseCellContentPadding) **之后**" +
                "（早于 padding 等于没裁，口径同 DayTimelineBlockLinesWiringGuardTest 的那格）：clip@$clipAt pad@$padAt",
            padAt >= 0 && clipAt > padAt,
        )
        val axisBody = funBody(week, "private fun TimeLabels(")
        val heightAt = axisBody.indexOf(".height(height)")
        val axisClipAt = axisBody.indexOf(".clipToBounds()")
        assertTrue(
            "**读形状**：节次轴的 clip 要挂在定高 .height(height) 之后（定高＋居中＝超出量对称甩到行外）：" +
                "clip@$axisClipAt height@$heightAt",
            heightAt >= 0 && axisClipAt > heightAt,
        )
    }

    /** ⑤：视口均分那一档真的吃 weekRowScale，且视口是量出来的（逐字节 + 数出现次数） */
    @Test
    fun fittedViewportConsumesTheScaleAndMeasuresItsOwnViewport() {
        val week = blankComments(source(WEEK_VIEW))
        val grid = funBody(week, "private fun WeekGrid(")
        val fitAt = grid.indexOf("if (Personalization.weekFitViewport)")
        assertTrue("找不到 weekFitViewport 那一档：靶子换了形状就要重登本台账", fitAt >= 0)
        val elseAt = grid.indexOf("} else {", fitAt)
        assertTrue(
            "**逐字节**：视口均分那一档的行高出自内核，且把 weekRowScale 递了进去 —— 改前这一整段不消费那支滑杆，" +
                "而设置页照样念「行高 130%」（#537 F3 的第二笔）",
            grid.contains("weekFitViewportRowHeightDp(") &&
                grid.contains("rowScale = Personalization.weekRowScale.toDouble()"),
        )
        val branch = grid.substring(fitAt, if (elseAt > fitAt) elseAt else grid.length)
        assertTrue(
            "**读形状**：这一档的视口要来自网格自己 onSizeChanged 量到的实高（gridViewportPx），" +
                "不是 screenHeightDp 减一枚写死的页头条抄本",
            "gridViewportPx" in branch && "minimumRowHeightDp =" in branch,
        )
        val dpSnapshotShapes = listOf("screenHeightDp", "LocalConfiguration", "headerSpace", "windowHeightDp")
            .filter { it in week }
        assertTrue(
            "**抹注释找锚点**：原始设备事实/页头条 dp 抄本回来了（#537 F3 第一笔：字号调大时页头条会长，" +
                "那枚 96 不会）：" + dpSnapshotShapes.joinToString("、"),
            dpSnapshotShapes.isEmpty(),
        )
        assertEquals(
            "**数出现次数**：weekFitViewport 在 main 里只许这一处读者（唯一入口）",
            1,
            occurrences(week, "Personalization.weekFitViewport"),
        )
    }

    /** ⑥：设置页那一行说实话 —— 点名改它的字段与代价（逐字节 + 读形状） */
    @Test
    fun settingsRowNamesTheKnobAndItsCost() {
        val settings = blankComments(source(SETTINGS_SCREEN))
        val titleAt = settings.indexOf("title = \"视口均分行高\"")
        assertTrue("「视口均分行高」那一行没了：本卡的第二笔账就没人念了", titleAt >= 0)
        val summaryAt = settings.indexOf(FIT_VIEWPORT_SUMMARY)
        val sliderAt = settings.indexOf("weekRowScaleDraft", titleAt)
        assertTrue(
            "**逐字节**：那一行要带这句 summary（点名改它的是下面那支「行高」滑杆、代价是托底生效时改为滚动）。" +
                "本仓成文裁定：叫「设计如此」就得说出改它的那枚字段与代价 —— summary@$summaryAt title@$titleAt slider@$sliderAt",
            summaryAt > titleAt && sliderAt > summaryAt,
        )
        assertTrue(
            "**读形状**：这句话必须挂在「视口均分行高」那一行的区间里，不能飘到别的行上",
            settings.substring(titleAt, summaryAt).length < 800,
        )
    }

    /** ⑦：内核零 import、不读设备事实（数出现次数 + 抹注释找锚点） */
    @Test
    fun budgetKernelIsPureJvm() {
        val kernelRaw = source(KERNEL)
        val imports = kernelRaw.lines().map { it.trimStart() }.filter { it.startsWith("import") }
        assertTrue(
            "**数出现次数**：行预算内核要能在纯 JVM 上表驱动跑，import 清单必须是空的（含 java.*）：" +
                imports.joinToString("\n"),
            imports.isEmpty(),
        )
        val kernel = blankComments(kernelRaw)
        val deviceReads = FORBIDDEN_DEVICE_READS.filter { it in kernel }
        assertTrue(
            "**抹注释找锚点**：内核自己去读了设备事实（密度 / fontScale / displayMetrics / Build / 排版表 / " +
                "量文本）—— 判据一旦自己读这些，JVM 表驱动单测就到位不了（本仓三处成文禁令同一条）：" +
                deviceReads.joinToString("、"),
            deviceReads.isEmpty(),
        )
        assertTrue(
            "**逐字节**：三档答复与三本入口都在（少一枚就是形状被换掉了，本卡的账要重算）",
            kernel.contains("data class Fit(") &&
                kernel.contains("data class ShortBy(") &&
                kernel.contains("object NothingFits") &&
                kernel.contains("internal fun planWeekCourseCard(") &&
                kernel.contains("internal fun planWeekTimeAxis(") &&
                kernel.contains("internal fun weekFitViewportRowHeightDp("),
        )
    }

    // ---- 靶子定位与词法小工具（与同族守卫同一套刀法） ----------------------------

    /**
     * 从 [signature]（以 `(` 结尾）起切出函数体。
     *
     * 先把参数表的圆括号配平、再取下一个花括号：`onClick: () -> Unit = {}` 这种**默认值里带 `{}`** 的参数
     * 会让"签名后第一个 {"停在默认值上"，切出来的就是两枚括号而不是函数体（本卡第一版就栽在这里，
     * 于是 CourseCell 那一格的锚点全成了假阴）。扫描时跳过字符串字面量，与同族守卫同一套词法。
     */
    private fun funBody(code: String, signature: String): String {
        val at = code.indexOf(signature)
        assertTrue("找不到 $signature：靶子挪过家这条守卫要跟着改", at >= 0)
        val open = at + signature.length - 1
        val close = matchBracket(code, open, '(', ')')
            ?: throw AssertionError("$signature 的圆括号配不上对，解析器该修了")
        val brace = code.indexOf('{', close)
        assertTrue("$signature 后面没有函数体？", brace >= 0)
        val end = matchBracket(code, brace, '{', '}')
            ?: throw AssertionError("$signature 的花括号配不上对，解析器该修了")
        return code.substring(brace, end)
    }

    /** 从 [open] 处的括号走到配平的那个闭合括号（跳过 `"..."` / `'...'` 字面量） */
    private fun matchBracket(code: String, open: Int, left: Char, right: Char): Int? {
        if (open < 0 || code[open] != left) return null
        var depth = 0
        var i = open
        while (i < code.length) {
            val c = code[i]
            when {
                c == '"' || c == '\'' -> {
                    val quote = c
                    var j = i + 1
                    while (j < code.length) {
                        when {
                            code[j] == '\\' -> j += 2
                            code[j] == quote -> { j++; break }
                            code[j] == '\n' -> break
                            else -> j++
                        }
                    }
                    i = j
                    continue
                }

                c == left -> depth++
                c == right -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    /** [needle] 在 [fromMarker] 与下一个 [toLiteral] 之间出现的次数（枚数配平，不靠行号） */
    private fun countBetween(hay: String, fromMarker: String, toLiteral: String): Int {
        val from = hay.indexOf(fromMarker)
        val to = hay.indexOf(toLiteral, from)
        assertTrue("$fromMarker 或 $toLiteral 找不到", from >= 0 && to > from)
        return occurrences(hay.substring(from, to), "Text(")
    }

    private fun occurrences(hay: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var from = 0
        while (true) {
            val at = hay.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
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

    private companion object {
        const val WEEK_VIEW = "com/buaa/schedule/ui/home/WeekView.kt"
        const val KERNEL = "com/buaa/schedule/ui/home/WeekViewLineBudget.kt"
        const val SETTINGS_SCREEN = "com/buaa/schedule/ui/settings/SettingsScreen.kt"

        /** 改前那三枚标称预算的形状（代码里出现即判红，注释里再提一次不算） */
        val NOMINAL_BUDGET_SHAPES = listOf(
            "?: 16.dp",
            "?: 14.dp",
            "takeIf { it.isSp }",
            ".lineHeight.takeIf",
        )

        /** 两行起止时间那枚源串（自带 `\n`，两行都在 maxLines = 2 限额内，Ellipsis 一克不起） */
        const val TWO_LINE_LITERAL = "\"${'$'}{slot.startTime}\\n${'$'}{slot.endTime}\""

        /** 内核不许自己读的设备事实（名单形状照 StatsEntryWiringGuardTest 那一族） */
        val FORBIDDEN_DEVICE_READS = listOf(
            "LocalDensity",
            "LocalConfiguration",
            "DisplayMetrics",
            "Build.VERSION",
            "fontScale",
            "MaterialTheme",
            "TextStyle",
            "TextMeasurer",
            "Modifier",
            "@Composable",
            ".dp",
            ".sp",
            "toDp()",
            "remember(",
        )

        /** 设置页那句 summary 的锚点原文（屏上那句话与代价） */
        const val FIT_VIEWPORT_SUMMARY =
            "summary = \"行高＝网格可视高度均分给每一节，再乘下面的「行高」比例；不足一行文字时按一行托底，此时整表改为滚动\""
    }
}
