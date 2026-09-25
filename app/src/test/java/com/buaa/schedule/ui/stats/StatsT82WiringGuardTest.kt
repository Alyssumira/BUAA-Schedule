package com.buaa.schedule.ui.stats

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T82 的**接线**守卫：冲突卡与按周钻取这两块，病不在内核（内核有自己的表驱动单测），
 * 在"这一页到底吃了哪一件判据、有没有自己再算一遍、点一下会不会把整页重算"。
 *
 * 刀法照抄 [StatsPageStageWiringGuardTest] 与 [StatsChartsStructureGuardTest]：
 * 读源文件文本、匹配前先 `blankComments` 抹注释（本卡的 KDoc 里就写着被禁的那种写法本身，
 * 连注释一起扫会红在自己人手上）、函数体按花括号配平取、找不到锚点就抛、
 * 不用 assumeTrue 跳过（"没找到就算过"的守卫会在下一次改名时静默变绿）。
 *
 * 钉七档：
 * 1. 冲突判据只有那两份：归并走 `groupConflicts`，页面里不许出现第二次 `findConflicts`，
 *    也不许把两两配对的条数当组数念（`state.conflicts.size`）；
 * 2. 「几组」在两页是同一个数：首页那条横幅念的也是归并后的组数；
 * 3. 处置 UI 全站一枚：统计页挂的是首页那枚 [com.buaa.schedule.ui.home.ConflictWizardDialog]，
 *    而不是自己重画一套（重画的那一套里"移到第几节""只改这些周"会各算各的）；
 * 4. 三枚新内核保持纯 JVM：零 android/androidx import、零时钟与设备读取；
 * 5. 钻取的内核调用点在 `remember(...)` 里，且 memoize 键带着选中周次；
 * 6. 选中态的三枚 state 全在钻取那一块里面，`StatsScreen` 自己身上没有 ——
 *    点一下胶囊不该把整页（含四枚全学期重算）拖下地；
 * 7. 重排不许删内容：八枚卡头一枚不少，课程学分那一列确实在末尾。
 */
class StatsT82WiringGuardTest {

    // ---- ① 冲突：不许多算一遍，也不许拿配对数当组数 ----

    @Test
    fun statsPageNeverRecomputesConflicts() {
        val code = blankComments(source(STATS_SCREEN))
        assertTrue(
            "统计页的分组没走 CourseConflictResolution.groupConflicts：那一件内核负责\"谁和谁算一组\"，" +
                "这里再判一次就是第二套真相（还会和首页那枚向导各说各的组数）",
            code.contains("CourseConflictResolution.groupConflicts("),
        )
        for (banned in listOf("ConflictDetector.findConflicts(", "periodsOverlap(", "suggestNearestFreeShift(")) {
            assertFalse(
                "统计页里出现了「$banned」：那是把已经在 ConflictDetector / 建议内核里算过的事再来一遍",
                code.contains(banned),
            )
        }
        assertFalse(
            "「state.conflicts.size」是两两配对的条数，不是组数：三门课互撞 = 3 条配对、1 组。" +
                "卡片那句「几组」必须念归并后的组数，否则这一页与首页那枚向导会给出两个数",
            code.contains("state.conflicts.size"),
        )
    }

    // ---- ② 两页同一个数 ----

    @Test
    fun homeBannerAndStatsCardSpeakTheSameCount() {
        val home = blankComments(source(HOME_SCREEN))
        assertTrue(
            "首页那条冲突横幅不再吃归并后的组数了：它会与统计页、与点开向导看到的行数分岔",
            home.contains("conflictGroups.size"),
        )
        assertFalse(
            "首页横幅回到了配对数那一档（三门课互撞会念成「3 组」而向导里只有一档）",
            home.contains("state.conflicts.size"),
        )
        val stats = blankComments(source(STATS_SCREEN))
        assertEquals(
            "conflictHeadlineNote 的调用点数应当恰好一处（那句「几组」的措辞不许在页内被旁路）：" +
                allOccurrences(stats, "conflictHeadlineNote(").size,
            1, allOccurrences(stats, "conflictHeadlineNote(").size,
        )
    }

    // ---- ③ 处置入口复用首页那一枚向导 ----

    @Test
    fun disposalEntryReusesTheHomeWizardInsteadOfADSecondOne() {
        val stats = blankComments(source(STATS_SCREEN))
        assertTrue("统计页没挂 ConflictWizardDialog：冲突卡说了事却不给出口", stats.contains("ConflictWizardDialog("))
        assertTrue(
            "统计页的落库回调没走 applyConflictShift：作用域（viewModelScope）、join、partialWeeks " +
                "三条账都在那一处，抄一份到这一页就是三件能各自写错的事",
            stats.contains("applyConflictShift("),
        )
        for (banned in listOf("只改这些周", "写入中…", "已应用，冲突列表")) {
            assertFalse(
                "统计页里出现了「$banned」——那是向导自己的处置 UI，本卡明令不许在这里重画一套",
                stats.contains(banned),
            )
        }
    }

    // ---- ④ 内核纯度 ----

    @Test
    fun newKernelsStayPureJvmAndTakeDeviceFactsAsParameters() {
        for (relative in listOf(DRILL_KERNEL, CONFLICT_COPY, DRILL_COPY)) {
            val code = blankComments(source(relative))
            for (banned in listOf(
                "import android", "import androidx", "import java.time", "import kotlin.time",
                "SystemClock", "currentTimeMillis", "nanoTime", "LocalDate", "LocalDateTime",
                "Clock", "Date(",
            )) {
                assertFalse(
                    "$relative 里出现了「$banned」：判据自己去读设备或时钟，这张表在 JVM 里就打不开，" +
                        "而\"现在第几周/今天哪天\"这类事实按口径只能由调用点当参数传（本仓硬口径）",
                    code.contains(banned),
                )
            }
        }
        // 周次与当前周都得是参数，不是内核自己去嗅
        val kernel = source(DRILL_KERNEL)
        assertTrue(
            "WeekDaySchedule.scheduleOf 的签名不收 week 参数了：那一周的实际排课就没人判得了",
            Regex("""scheduleOf\([\s\S]*?week: Int,""").containsMatchIn(kernel),
        )
    }

    // ---- ⑤ 钻取的重算锁在 remember 里 ----

    @Test
    fun drillKernelRunsInsideRememberKeyedByTheSelection() {
        val code = blankComments(source(STATS_SCREEN))
        val at = code.indexOf("WeekDaySchedule.scheduleOf(")
        assertTrue("WeekDaySchedule.scheduleOf 根本不在统计页里：判据算完没人画？", at >= 0)
        val before = code.substring(0, at)
        assertTrue(
            "scheduleOf 没包在 remember(...) 里：换一周的重算也要锁在 memoize 里，" +
                "不许躺在组合期每帧跑一遍",
            before.lastIndexOf("remember(") > before.lastIndexOf('}'),
        )
        val memo = before.substring(before.lastIndexOf("remember("))
        assertTrue(
            "memoize 键里没有 selectedWeek：选中换了还吃着上一周的结果（画的是别的一周）",
            memo.contains("selectedWeek"),
        )
        for (key in listOf("courses", "semester", "timeSlots")) {
            assertTrue("memoize 键少了 $key：课表变了而钻取不重算", memo.contains(key))
        }
    }

    // ---- ⑥ 选中态不外溢 ----

    @Test
    fun selectionStateLivesInsideTheDrillCardNotThePage() {
        val screen = functionBody(STATS_SCREEN, "fun StatsScreen(")
        for (leak in listOf("selectedWeek", "selectedDay", "expanded")) {
            assertFalse(
                "「$leak」跑到了 StatsScreen 自己身上：那一枚 state 一响，整页（连同四枚全学期重算）" +
                    "都在重组范围里，T75 复用 Activity 那枚 VM 省下来的东西就这么还回去了",
                screen.contains(leak),
            )
        }
        val card = functionBody(STATS_SCREEN, "private fun WeekDrillCard(")
        assertEquals(
            "钻取那一块自己持有三枚 state（展开 / 周 / 天）：${allOccurrences(card, "mutableStateOf").size}",
            3, allOccurrences(card, "mutableStateOf").size,
        )
    }

    // ---- ⑦ 重排不删内容 ----

    @Test
    fun reorderedColumnStillCarriesEveryCardAndKeptItsHeaders() {
        val code = blankComments(source(STATS_SCREEN))
        assertEquals(
            "统计页的卡头数变了（现有六块 + 冲突 + 按周细看）：折下几块是排布的事，" +
                "少一块就是删内容",
            8, allOccurrences(code, "SectionHeader(").size,
        )
        val ready = code.substring(code.indexOf("StatsPageStage.Ready -> Column("))
        val order = listOf(
            "CreditHeadline(", "DayLoadCard(", "WeekDrillCard(", "ConflictCard(", "LoadTrendCard(",
            "WeekCoverageCard(", "FreeSlotsCard(", "FreeSlotsGridCard(", "CreditListCard(",
        )
        var cursor = 0
        for (call in order) {
            val at = ready.indexOf(call, cursor)
            assertTrue("这一列里少了 $call，或者它排到了 $call 之前（见注释里那笔折叠账）", at >= 0)
            cursor = at
        }
    }

    // ---- 靶子定位与词法小工具（与 StatsPageStageWiringGuardTest 同族）-------------------

    private fun source(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 $relative：挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /** 从 [signature] 之后第一个 `{` 起配平到对应右括号（含两端）的函数体 */
    private fun functionBody(relative: String, signature: String): String {
        val code = blankComments(source(relative))
        val at = code.indexOf(signature)
        assertTrue("找不到 $signature：靶子没了（$relative）", at >= 0)
        val brace = code.indexOf('{', at)
        val end = matchingClose(code, brace) ?: throw AssertionError("$relative 的花括号配不上对，解析器该修了")
        assertTrue("$relative 函数体终点在起点之前？", end > brace)
        return code.substring(brace, end)
    }

    private fun allOccurrences(hay: String, needle: String): List<Int> {
        val hits = mutableListOf<Int>()
        var at = hay.indexOf(needle)
        while (at >= 0) {
            hits += at
            at = hay.indexOf(needle, at + needle.length)
        }
        return hits
    }

    /** 把 `//` 与 `/* */` 注释抹成空格（字符串保留、长度与换行位置不变）：钉的是接线，不是白话 */
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
        const val STATS_SCREEN = "com/buaa/schedule/ui/stats/StatsScreen.kt"
        const val HOME_SCREEN = "com/buaa/schedule/ui/home/HomeScreen.kt"
        const val DRILL_KERNEL = "com/buaa/schedule/domain/schedule/WeekDaySchedule.kt"
        const val CONFLICT_COPY = "com/buaa/schedule/ui/stats/StatsConflictCopy.kt"
        const val DRILL_COPY = "com/buaa/schedule/ui/stats/StatsDrillCopy.kt"
    }
}
