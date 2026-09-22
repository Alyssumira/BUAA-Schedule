package com.buaa.schedule.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「休/班」标注这条渲染链的**接线**守卫（T62④）。
 *
 * 用户两句投诉是同一件事的两半：第一句「节假日没有标注出来」由 T60 接了数据侧，
 * 第二句仍然是同一句 —— 因为数据到位之后，全应用只有周课表表头那一格在画它。
 * 而"多处共用一份判据"这种修法最容易得的病恰恰是**接完就散**：
 * - 某处把共享件又内联回自己的 `if (isHoliday)`（下次改配色就只改了一处），
 * - 某处拿 `today` 当依据而那一行字说的是别的日期（页头 Crossfade 滑出去那一屏
 *   先换成新日期的标注，正是 T56 顶栏刚修完的那个形状），
 * - 判据内核偷偷去读 `LocalDate` / `MaterialTheme`（那就再也没法在 JVM 里打表）。
 *
 * 手法照抄 [SpecialDayRefreshWiringGuardTest] / [TopBarDateWiringGuardTest]：读源码文本、
 * 匹配前先抹注释（内核文件的 KDoc 里就写着「零 `java.time`」这类禁令本身）、
 * 找不到文件锚点就抛（静默跳过的守卫比没有守卫更糟），且每处断言都要有命中数 > 0 的靶子。
 */
class SpecialDayBadgeWiringGuardTest {

    // ---- ① 判据内核保持纯 JVM ----

    @Test
    fun badgeKernelStaysPureJvm() {
        val raw = readMainSource(BADGE_KERNEL_FILE)
        val code = blankComments(raw)
        val imports = code.lines().filter { it.trim().startsWith("import ") }
        assertTrue("$BADGE_KERNEL_FILE 里出现了 import，这段判据就到不了 JVM：\n$imports", imports.isEmpty())
        for (
            banned in listOf(
                "android.", "androidx.", "java.time", "LocalDate", "YearMonth", "MaterialTheme",
                "colorScheme", "System.currentTimeMillis", "Build.", "Compose",
            )
        ) {
            assertFalse("判据自己去碰了日历/主题/设备（$banned）—— 这些事实必须是参数", code.contains(banned))
        }
        assertTrue("内核文件是空的？", code.length > 800)
        // 三处界面共用的那几个入口都必须在内核里
        for (entry in listOf("specialDayDateKey", "specialDayIsWeekend", "specialDayBadgeAt", "specialDayBadgeLabel")) {
            assertTrue("内核少了 $entry 这一档：", code.contains(entry))
        }
    }

    /** 「休」「班」两枚字只许定义在内核那一处，且一一对应 */
    @Test
    fun badgeWordingLivesOnlyInTheKernel() {
        val code = blankComments(readMainSource(BADGE_KERNEL_FILE))
        assertEquals("「休」的字面量份数：", 1, occurrences(code, "\"休\""))
        assertEquals("「班」的字面量份数：", 1, occurrences(code, "\"班\""))
        for (
            file in listOf(WEEK_VIEW_FILE, DAY_VIEW_FILE, HOME_SCREEN_FILE, BADGE_UI_FILE)
        ) {
            val ui = blankComments(readMainSource(file))
            for (literal in listOf("\"休\"", "\"班\"")) {
                assertEquals("$file 里自己写了一份徽标文案（那就是第二处真相）：", 0, occurrences(ui, literal))
            }
        }
        // 数据模型那份旧文案（`SpecialDay.badgeOf`）不许回来：它答不出重复/周末那两档
        val model = blankComments(readMainSource(SPECIAL_DAY_MODEL_FILE))
        assertFalse("SpecialDay 又自带徽标文案了（判据应只在内核一份）：", model.contains("badgeOf"))
    }

    // ---- ② 两处界面都真的走共用件（周表头 / 今日页页头；顶栏那两行按本卡口径不接） ----

    /** 周表头与今日页页头都得调用同一件渲染口，一处不落 */
    @Test
    fun bothSurfacesDrawThroughTheSharedBadge() {
        for (
            file in listOf(WEEK_VIEW_FILE, DAY_VIEW_FILE)
        ) {
            val ui = blankComments(readMainSource(file))
            assertTrue("$file 不再调用共用徽标（它又自己画了一遍）：", occurrences(ui, "SpecialDayBadgeText(") >= 1)
            assertTrue("$file 没有把标注折算成判据的形态（那就是根本没接上数据）：", ui.contains("specialDayMarksOf("))
        }
        val ui = blankComments(readMainSource(BADGE_UI_FILE))
        assertEquals("渲染口只许有一处定义：", 1, occurrences(ui, "internal fun SpecialDayBadgeText("))
        assertEquals("折算口只许有一处定义：", 1, occurrences(ui, "internal fun specialDayMarksOf("))
        // 配色：休=error / 班=primary 这条既有配对收在共用件里，别处不许各挑一次
        assertEquals("徽标配色定义份数：", 1, occurrences(ui, "internal fun specialDayBadgeColor("))
        for (file in listOf(WEEK_VIEW_FILE, DAY_VIEW_FILE)) {
            assertFalse("$file 还自己去问 isHoliday（配色分叉就是这么来的）：", blankComments(readMainSource(file)).contains("isHoliday"))
        }
    }

    /** WeekView 不再自己拼判据：既没有 `isHoliday` 分支，也没有按日期 firstOrNull 的那份局部映射 */
    @Test
    fun weekViewNoLongerHandRollsTheDecision() {
        val weekView = blankComments(readMainSource(WEEK_VIEW_FILE))
        assertFalse("WeekView 里还留着 `if (day.isHoliday)` 那一档：", weekView.contains("isHoliday"))
        assertFalse("WeekView 还在自己按日期 firstOrNull（判据的第二份）：", weekView.contains("firstOrNull { it.date =="))
        assertFalse("WeekView 还在用旧的列下标字典：", weekView.contains("specialDaysOfWeek"))
        assertTrue("WeekView 没把标注喂给表头：", weekView.contains("specialDayMarks = specialDayMarks"))
        // 表头那一格只交版面事实（压在主题色底上）与日期，决定权在内核
        val headerCall = Regex("SpecialDayBadgeText\\(([^)]*)\\)").findAll(weekView).toList()
        assertEquals("表头只该有一处徽标渲染口：", 1, headerCall.size)
        val args = headerCall.single().groupValues[1]
        for (arg in listOf("date = cellDate", "marks = specialDayMarks", "onAccentSurface = isToday")) {
            assertTrue("表头那一处的实参不对（$arg）：$args", args.contains(arg))
        }
    }

    // ---- ③ 今日页跟着"在看的日期"，不跟 today ----
    // 顶栏那两行**不在本卡范围**（与日头信息重复，调度定死不做）：这里特意不钉顶栏，
    // 而 `topBarDateLabel` 保持 T56 落地的原样——哪天要接顶栏，改这条决定连同卡一起开。

    @Test
    fun dayViewBadgeFollowsTheBrowsedDate() {
        val dayView = blankComments(readMainSource(DAY_VIEW_FILE))
        val call = Regex("SpecialDayBadgeText\\(([^)]*)\\)").findAll(dayView).toList()
        assertEquals("今日页这一处渲染口应当恰好一处：", 1, call.size)
        val args = call.single().groupValues[1]
        // Crossfade 的 lambda 参数才是"这一屏正在渲染的那一天"；写 date 是外层状态、
        // 写 today 更是直接违背"跟着在看的日期"这条本卡要求
        assertTrue("今日页徽标没跟着页头 Crossfade 那一格（date = day）：$args", args.contains("date = day"))
        assertFalse("今日页徽标去读 today：", args.contains("date = today"))
        assertTrue("今日页没接标注数据（DayView 少了 specialDays 入参）：", dayView.contains("specialDays: List<"))
        assertTrue("今日页没有折算标注：", dayView.contains("specialDayMarksOf(specialDays)"))
        // HomeScreen 两处分栏/页签布局都要把数据传进去：漏一处就是那条布局下没标注
        val home = blankComments(readMainSource(HOME_SCREEN_FILE))
        assertEquals("HomeScreen 里 `specialDays = specialDays` 该出现 4 次（周视图两处 + 日视图两处）：",
            4, occurrences(home, "specialDays = specialDays"))
    }

    // ---- ④ 小组件那一档仍然没接（本卡③的结论，别悄悄半接） ----

    /**
     * 桌面组件这一族今天**不**画「休/班」：它的周网格是按星期几摆的（`DayCell(dayName=…)`，
     * 一格没有日期），要标注就得先给组件侧引入"当前浏览周每一天"的口径 + 快照新键 +
     * RemoteViews 布局，那是另一张卡（T62③ 报的成本）。
     * 这条断言钉的是"没接"这个决定本身：半接（只把数据塞进快照、没有落点）比不接更糟。
     * 真要做那一卡时，连同这条断言一起改。
     */
    @Test
    fun widgetSideIsDeliberatelyNotWiredYet() {
        val widgetDir = File(findMainJavaDir(), "com/buaa/schedule/widget")
        assertTrue("找不到组件目录：${widgetDir.path}", widgetDir.isDirectory)
        val offenders = widgetDir.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { blankComments(it.readText()).contains("SpecialDay") }
            .map { it.name }
        assertTrue("组件侧出现了 SpecialDay（本卡③定的是另立一卡）：\n$offenders", offenders.isEmpty())
    }

    // ---- 工具：读源码、抹注释、配平取块 ----

    private fun readMainSource(relativeFromJava: String): String {
        val file = File(findMainJavaDir(), relativeFromJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /** 从 [signature] 之后第一个 `{` 起配平到对应右括号（含）；找不到锚点就抛 */
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
        throw IllegalStateException("$signature 的花括号没配平")
    }

    /** 注释里写着禁令本身（"不碰 `LocalDate`"），所以匹配前先抹掉；字符串保留 */
    private fun blankComments(source: String): String {
        val out = source.toCharArray()
        var i = 0
        while (i < out.size) {
            when {
                source.startsWith("//", i) -> {
                    val nl = source.indexOf('\n', i).let { if (it < 0) out.size else it }
                    for (k in i until nl) out[k] = ' '
                    i = nl
                }

                source.startsWith("/*", i) -> {
                    var depth = 1
                    var j = i + 2
                    while (j < out.size && depth > 0) {
                        when {
                            source.startsWith("/*", j) -> { depth++; j += 2 }
                            source.startsWith("*/", j) -> { depth--; j += 2 }
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
                            source[j] == '\\' -> j += 2
                            source[j] == quote -> { j++; break }
                            source[j] == '\n' -> break
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

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    /** 单测的 cwd 是 :app 模块目录，也可能是仓库根：两种布局都试，全落空就抛 */
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

    private companion object {
        const val BADGE_KERNEL_FILE = "com/buaa/schedule/ui/SpecialDayBadgePolicy.kt"
        const val BADGE_UI_FILE = "com/buaa/schedule/ui/home/SpecialDayBadge.kt"
        const val SPECIAL_DAY_MODEL_FILE = "com/buaa/schedule/domain/model/SpecialDay.kt"
        const val WEEK_VIEW_FILE = "com/buaa/schedule/ui/home/WeekView.kt"
        const val DAY_VIEW_FILE = "com/buaa/schedule/ui/home/DayView.kt"
        const val HOME_SCREEN_FILE = "com/buaa/schedule/ui/home/HomeScreen.kt"
    }
}
