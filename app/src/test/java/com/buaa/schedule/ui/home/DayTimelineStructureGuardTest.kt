package com.buaa.schedule.ui.home

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T49（日视图时间轴美化）的结构守卫——本模块没有 Compose 运行时
 * （无 Robolectric、无 ui-test），布局改动的回归守卫只能走纯 JVM 源码结构扫描，
 * 刀法与 [CourseTitleRowBudgetGuardTest] / GlassSurfaceSingleChildTest 一致：
 * 先抹注释（字符串保留——这里要找的靶子一半是字符串键名），再按函数体切块；
 * 找不到源码目录直接抛，不用 assumeTrue 跳过（找错路径只表现为"永远是绿的"，比红更糟）。
 *
 * 钉的是机制而不是像素：
 * 1. 判据内核 DayTimelineAxis.kt 零 android/androidx import（收单硬判据）。
 * 2. 时间轴版面确实接上了刻度列/现在线/课间/滚动锚点这五根线（摘掉任何一条都要红）。
 * 3. 色块前景推导链 legibleTintPlate + BlockTintAlpha + coursePlateSceneLuma 原样在场，
 *    且块没有偷偷改套 GlassSurface、没有写死十六进制色（真机校准账见 T23/T25b/T29）。
 * 4. 模式选择走 Personalization 读写盘；DayView 里不许再出现 rememberSaveable；
 *    新键不许长出 week_grid_mode_declared 那种一次性收敛。
 * 5. HourLabels / NowLine 全仓只定义一次（共享件），周视图调用点还在——
 *    防的是"把副本复制回来"而不是提取失败。
 */
class DayTimelineStructureGuardTest {

    @Test
    fun timelineAxisKernelStaysAndroidFree() {
        val text = source("com/buaa/schedule/ui/home/DayTimelineAxis.kt")
        val offenders = text.lines().map { it.trimStart() }
            .filter { it.startsWith("import android") }
        assertTrue(
            "DayTimelineAxis.kt 是要在 JVM 单测里跑裸的判据内核，混进设备依赖就没法测" +
                "（时刻/尺寸/密度必须当参数传进来）：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun timelineScreenWiresEveryNewMechanism() {
        val body = timelineBody()
        val required = listOf(
            "HourLabels(",                 // A：小时刻度列（共享件）
            "dayTimelineHourLineOffsets(", // A：整点网格线
            "NowLine(",                    // B：现在线（与周视图同一组件）
            "TIMELINE_TICK_MS",            // B：15 秒链
            "dayTimelineBlockContains(",   // B：当前块命中的纯判据
            "dayTimelineScrollTargetPx(",  // C：自动滚动的纯算式
            "onSizeChanged",               // C：视口高——滚动算式的输入（尾随 lambda 形态，不带括号）
            "verticalScroll(",             // C：滚动容器还在（锚点才有作用对象）
            "dayTimelineGaps(",            // D：课间分段的纯判据
        )
        val missing = required.filterNot { it in body }
        assertTrue(
            "日视图时间轴少了这些线，T49 的对应条目被摘了：\n" + missing.joinToString("\n"),
            missing.isEmpty(),
        )
    }

    @Test
    fun blockForegroundChainUntouchedAndStaysPlateNotGlass() {
        val body = timelineBody()
        val missing = listOf("legibleTintPlate(", "BlockTintAlpha", "coursePlateSceneLuma(")
            .filterNot { it in body }
        assertTrue(
            "色块前景对比度是 T23/T25b/T29 用真机数据校准出来的推导链，不许换、不许\"顺手简化\"：\n" +
                missing.joinToString("\n"),
            missing.isEmpty(),
        )
        // F 的边界：块是中等尺寸、数量多的重复元素——用户实测口径"小玻璃好看、大玻璃板丑"，
        // 给每块糊一层 GlassSurface 厚板就是把这条账踩回去
        assertTrue(
            "时间轴色块不许改套 GlassSurface（收口只做描边＋轻投影）",
            !body.contains("GlassSurface("),
        )
        // 网格线/虚线/文字色全部走主题令牌；写死十六进制色在深色档必然翻车
        assertTrue(
            "时间轴里出现了写死的十六进制色，改走 MaterialTheme.colorScheme.*",
            !Regex("Color\\(0x").containsMatchIn(body),
        )
        assertTrue(
            "整点网格线颜色必须走主题 outlineVariant（不写死灰）",
            body.contains("colorScheme.outlineVariant"),
        )
    }

    @Test
    fun modeChoicePersistsThroughPersonalizationNotSaveable() {
        val dayView = blankComments(source("com/buaa/schedule/ui/home/DayView.kt"))
        assertTrue(
            "「列表/时间轴」的读取与落盘要从 DayView 走 Personalization.dayTimelineMode/save",
            dayView.contains("Personalization.dayTimelineMode") &&
                dayView.contains("Personalization.save("),
        )
        assertTrue(
            "模式选择已落盘，DayView 里再出现 rememberSaveable 说明两套状态源并存",
            !dayView.contains("rememberSaveable"),
        )
        val perso = blankComments(source("com/buaa/schedule/core/designsystem/Personalization.kt"))
        assertTrue(
            "day_timeline_mode 要在 load 里读、save 里写（与 week_grid_mode 同一收口）",
            perso.contains("getBoolean(\"day_timeline_mode\"") && perso.contains("putBoolean(\"day_timeline_mode\""),
        )
        // 新键的一次性收敛是负资产：入口从第一天起就是带标签的分段控件，
        // 盘里的 true 只可能是用户明确点出来的（对照 week_grid_mode_declared 的误触史）
        assertTrue(
            "day_timeline_mode 是全新键，不许照抄 week_grid_mode_declared 那种一次性收敛",
            !perso.contains("day_timeline_mode_declared"),
        )
    }

    @Test
    fun sharedAxisComposablesAreDefinedExactlyOnce() {
        val root = findMainJavaDir()
        val hits = mutableMapOf<String, MutableList<String>>()
        for (rel in listOf("com/buaa/schedule/ui/home/TimelineAxis.kt", "com/buaa/schedule/ui/home/WeekView.kt", "com/buaa/schedule/ui/home/DayView.kt")) {
            val file = File(root, rel)
            if (!file.isFile) continue
            val code = blankComments(file.readText())
            for (fn in listOf("fun HourLabels(", "fun NowLine(")) {
                var from = 0
                while (true) {
                    val at = code.indexOf(fn, from)
                    if (at < 0) break
                    // 排除调用点：定义形态是 "@Composable ... internal fun" / "private fun"，
                    // 简单按"前面紧跟 fun 关键字的整行"计——调用点行首是标识符不是修饰符
                    val lineStart = code.lastIndexOf('\n', at) + 1
                    val line = code.substring(lineStart, at + fn.length).trimStart()
                    if (line.startsWith("internal fun") || line.startsWith("private fun")) {
                        hits.getOrPut(fn) { mutableListOf() } += rel
                    }
                    from = at + 1
                }
            }
        }
        for (fn in listOf("fun HourLabels(", "fun NowLine(")) {
            val where = hits[fn].orEmpty()
            assertTrue(
                "$fn 应全仓恰有一处定义且在 TimelineAxis.kt（两处各留一份，下次调口径必只改一边），实际：$where",
                where == listOf("com/buaa/schedule/ui/home/TimelineAxis.kt"),
            )
        }
        val week = blankComments(source("com/buaa/schedule/ui/home/WeekView.kt"))
        assertTrue(
            "周视图调用点必须还接着共享件（提取改坏了周视图渲染的话这里该红）",
            week.contains("HourLabels(") && week.contains("NowLine("),
        )
    }

    // ---- 靶子定位与词法小工具 ---------------------------------------------------

    /** DayTimelineCourseList 的函数体（注释已抹，字符串保留） */
    private fun timelineBody(): String {
        val code = blankComments(source("com/buaa/schedule/ui/home/DayView.kt"))
        val at = code.indexOf("private fun DayTimelineCourseList(")
        assertTrue("找不到 DayTimelineCourseList：靶子没了", at >= 0)
        // 签名的尖括号/圆括号里没有花括号，第一个 { 就是函数体开头
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
