package com.buaa.schedule.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T68 的结构守卫——本模块没有 Compose 运行时（无 Robolectric、无 ui-test），
 * `HomeScreen` 的回归只能走纯 JVM 源码扫描，刀法与 [TopBarDateWiringGuardTest] /
 * [DayTimelineStructureGuardTest] 一致：先抹注释（字符串保留），找不到源码目录直接抛，
 * 不用 assumeTrue 跳过。
 *
 * 钉的是机制不是文案。用户报的是效果（「凌晨还是显示前一天的课表」），
 * 而效果落在这几条链路上，断掉任何一条都会让 T68 的判据变成死代码：
 * 1. 判据内核 [DayBrowsePolicy] 零 android import、零时钟读取（今天与锚定日都由调用点传）。
 * 2. 日视图 `date =` 那一处必须吃内核算出来的那一天，不许再留 `browseDate ?: today`。
 * 3. 浏览日必须与「这笔浏览是哪一天按下的」成对落盘——没有锚定日，跨午夜就没有判据。
 * 4. 顶栏第二行与今日页标题都必须由同一个 `browseDateOnScreen` 派生（不许各算一遍）。
 * 5. DayView 的「回到今天」仍然只认 `date != today`：它读的必须就是 body 那一天。
 */
class DayBrowseWiringGuardTest {

    private val home get() = blankComments(source("com/buaa/schedule/ui/home/HomeScreen.kt"))
    private val dayView get() = blankComments(source("com/buaa/schedule/ui/home/DayView.kt"))

    // ---- ① 内核纯度 ----

    @Test
    fun browseDateKernelReadsNoClockAndNoAndroid() {
        // 先抹注释：内核文件的 KDoc 里就会写「不读 LocalDate.now()」这类禁令本身
        val text = blankComments(source("com/buaa/schedule/ui/home/DayBrowsePolicy.kt"))
        val offenders = text.lines().map { it.trimStart() }
            .filter { it.startsWith("import android") || it.startsWith("import androidx") }
        assertTrue(
            "DayBrowsePolicy.kt 是要在 JVM 单测里跑裸的判据内核，混进设备依赖就没法测" +
                "（今天、锚定日、浏览日全得当参数传进来）：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
        // 时钟：内核自己读一次，判据就与 ViewModel 的跨午夜滴答脱钩（T41/T43 同一类坑）
        val clock = text.lines().map { it.trim() }
            .filter {
                it.contains("LocalDate.now(") || it.contains("LocalDateTime") ||
                    it.contains("System.currentTimeMillis") || it.contains("Clock.") ||
                    it.contains("TimeZone")
            }
        assertTrue(
            "内核里出现了读时钟的写法，「今天」必须是 state.today 传进来的：" +
                "\n" + clock.joinToString("\n"),
            clock.isEmpty(),
        )
        assertTrue(
            "判据必须看得见锚定日，否则没有任何输入能区分「今天翻的」与「昨天翻的」",
            Regex("anchoredOn:\\s*LocalDate").containsMatchIn(text),
        )
    }

    // ---- ② 日视图的那一天来自内核 ----

    @Test
    fun dayViewDateComesFromTheKernel() {
        val call = Regex("dayViewDate\\(([^)]*)\\)").find(home)
            ?: throw AssertionError(
                "HomeScreen 不再调用 dayViewDate：跨午夜的过期判定脱钩了。\n" +
                    "现在那一行是：" + home.lines().filter { it.contains("date = ") }.joinToString("\n"),
            )
        for (input in listOf("today", "browseDateAnchoredOn", "browseDate")) {
            assertTrue("dayViewDate 的实参少了 $input，这一维就不参与判定：$call", call.groupValues[1].contains(input))
        }
        val sites = home.lines().filter { it.trim().startsWith("date = ") }
        assertEquals(
            "日视图只有宽屏并排与今日页签两处 date =，两处都得吃同一个值：\n" + sites.joinToString("\n"),
            2,
            sites.size,
        )
        for (line in sites) {
            assertTrue(
                "这一处 date = 没走内核算出来的那一天，body 与顶栏会各说一天：$line",
                line.contains("date = browseDateOnScreen"),
            )
        }
        assertTrue(
            "`browseDate ?: today` 又回来了——它正是「凌晨还是显示前一天的课表」的表达式本体",
            !home.contains("browseDate ?: today"),
        )
    }

    // ---- ③ 浏览日与锚定日成对落盘 ----

    @Test
    fun browseDayAnchorSurvivesProcessDeathTogetherWithTheDate() {
        val saveable = Regex("var browseDateAnchorEpochDay by rememberSaveable \\{ mutableLongStateOf\\((-?\\d+)L\\) \\}")
            .find(home)
            ?: throw AssertionError(
                "锚定日不再是 saveable：转屏/进程死亡以后它就丢了，" +
                    "而 rememberSaveable 的浏览日还活着 ⇒ 冷启动那天永远回不到今天",
            )
        assertEquals("「没手动选过」的哨兵值仍应是 -1：", "-1", saveable.groupValues[1])
        val setter = balancedBlock(home, "fun setBrowseDate(")
        assertTrue("setBrowseDate 没写锚定日（判据没有输入）：\n$setter", setter.contains("browseDateAnchorEpochDay ="))
        assertEquals(
            "两个槽位必须成对写，漏一半就会出现「有浏览日没有锚定日」这种永远不过期的状态：\n$setter",
            1,
            Regex("browseDateAnchorEpochDay =").findAll(setter).count(),
        )
        assertTrue(
            "清空浏览日时必须一起清空锚定日（-1），否则下一次判据读到的是一笔已经作废的按下时刻：\n$setter",
            setter.contains("if (date == null) -1L else"),
        )
        // 「跳到本周」是全仓第二处清零点：它清浏览日时必须一起清锚定日
        val jumpToThisWeek = balancedBlock(home, "LiquidMenuItem(Icons.Default.EventAvailable")
        assertTrue("跳到本周没清浏览日：\n$jumpToThisWeek", jumpToThisWeek.contains("setBrowseDate(null)"))
        assertEquals(
            "槽位只许 setBrowseDate 一处直写（别处绕过去写日期就会漏掉锚定日，那一笔浏览就永不过期）：",
            1,
            Regex("browseDateEpochDay =").findAll(home).count(),
        )
    }

    // ---- ④ 顶栏那两行与 body 同源 ----

    @Test
    fun topBarLinesDeriveFromTheDayOnScreen() {
        val call = Regex("topBarDateLabel\\(([^)]*)\\)").find(home)
            ?: throw AssertionError("HomeScreen 不再调用 topBarDateLabel")
        val args = call.groupValues[1]
        for (input in listOf("semesterStart", "currentWeek", "browseWeek", "today", "browseDateOnScreen")) {
            assertTrue(
                "topBarDateLabel 的实参少了 $input——漏 browseDateOnScreen 就是顶栏写着今天、" +
                    "body 画着用户翻到的那一天：$args",
                args.contains(input),
            )
        }
        val remember = Regex("remember\\(([^)]*)\\)\\s*\\{\\s*topBarDateLabel\\(").find(home)
            ?: throw AssertionError("dateLabel 不再由 remember 包住")
        val keys = remember.groupValues[1]
        for (key in listOf("semesterStart", "currentWeek", "browseWeek", "today", "browseDateOnScreen")) {
            assertTrue("remember 的键漏了 $key（那一维变了却不重算）：$keys", keys.contains(key))
        }
        assertTrue(
            "今日页标题必须走 dayTabHeadline(today, body 那一天)：" +
                home.lines().filter { it.contains("今日课表") || it.contains("dayTabHeadline") }.joinToString("\n"),
            home.contains("text = dayTabHeadline("),
        )
        assertEquals(
            "「今日课表」这句字面量只许活在 dayTabHeadline 里，HomeScreen 再抄一遍就是第二处真相",
            0,
            home.lines().count { it.contains("\"今日课表\"") },
        )
    }

    // ---- ⑤ 「回到今天」读的必须就是 body 那一天 ----

    @Test
    fun backToTodayButtonSharesTheBodyDay() {
        val condition = Regex("if \\(([^)]*)\\)\\s*\\{\\s*TextButton\\(onClick = \\{ onDateChange\\(today\\) \\}")
            .find(dayView)
            ?: throw AssertionError(
                "「回到今天」的可见条件不再是「body 那一天 != 今天」：会出现" +
                    "已经在今天还留着按钮、或在别的日子却没了按钮（用户回不来）",
            )
        assertEquals("可见条件必须只看 date 与 today 这两个同源入参：", "date != today", condition.groupValues[1].trim())
        assertTrue(
            "DayView 自己读时钟的话，跨午夜滴答推过来的 today 就被架空了（回到今天会停在昨天）",
            !dayView.contains("LocalDate.now("),
        )
    }

    // ---- ⑥ 屏上月份也是 body 那一天 ----

    @Test
    fun visibleMonthsFollowTheDayActuallyDrawn() {
        val months = balancedBlock(home, "val specialDayMonths = remember(")
        assertTrue(
            "屏上月份读的是没过期判据管过的 raw browseDate：跨午夜以后那一天已经不作数了，" +
                "还替它补抓一个月＝界面上根本没有这个月：\n$months",
            months.contains("YearMonth.from(browseDateOnScreen)"),
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
