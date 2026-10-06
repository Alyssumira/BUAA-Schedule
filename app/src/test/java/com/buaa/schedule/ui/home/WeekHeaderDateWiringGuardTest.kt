package com.buaa.schedule.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T190 接线守卫：表头那一行日期到底**由谁给出第几周**。
 *
 * 病不在内核（内核有自己的表驱动单测 [WeekHeaderDatePolicyTest]），在**接线**：
 * [WeekView] 的同一个组合函数里并排着两枚「现在显示第几周」的派生，改前一枚带 `?: 1`
 * （表头日期用的 `displayWeekNumber`）、一枚不带（Pager 用的 `pagerWeek`）。同一判据两份表达式
 * 就是本仓最典型的形状 —— 修掉一份、另一份照错，而且**静默**：判据照对、门禁照绿，
 * 只是假期里表头照旧印第 1 周那七天的日期，同屏顶栏第一行写着「假期中」。
 *
 * 刀法照抄同目录 [WeekHeaderTodayWiringGuardTest]（T182，本卡的上半）与
 * `domain/model/ManualCourseDefinitionGuardTest`（枚数 + 落点册子）：读 `app/src/main` 的工作树文本、
 * 匹配前先抹注释保留字面量（本卡的注释与内核 KDoc 里就写着被禁的写法原文）、锚点找不到就抛、
 * 不用 assumeTrue 跳过。工作树文件已在 `app/build.gradle.kts` 的 `guardReadWorkingTreeFiles`
 * 输入面里（`src/main/java` 整目录），不必另加机制。
 *
 * 钉六层：
 * 1. 判据内核 [headerWeekNumberOrNull] 零 android import、零时钟零设备读数，两枚周次事实全由调用点
 *    当参数传；函数体里不许藏 `?: 1`（"藏在参数上"与"藏在函数体里"是同一枚病灶的两个落点）；
 * 2. 病灶原文（`?: currentWeek ?: 1` 一族、以及表头那一枚上 bolt 上去的 `?: 1`）在 `WeekView.kt`
 *    里一枚不许留；
 * 3. `val pagerWeek = displayWeek ?: currentWeek` 与新的表头派生各恰好 1 枚，且**表头那一枚必须走内核**
 *    （全文 `displayWeek ?: currentWeek` 只许 1 处 = 两份表达式不许长回来）；
 * 4. `weekStartDate` 必须由那枚可空周次派生（周次为 null ⇒ 日期起点为 null），表头消费端形状不许改：
 *    `DayHeader` 的 `weekStartDate` 参数仍是可空、那一格印的日期仍是 `cellDate`、日期那一行仍由
 *    `if (cellDate != null)` 把关 —— null 那一路只印星期名；
 * 5. 全仓只许一份这枚判据的定义与一处调用（不许 DayView / 组件侧各抄一套再各错一遍）；
 * 6. 反套套逻辑：探测器必须认得改前那一形原文（抓到 1 处），又不误伤本仓既有的合法 `?: 1`
 *    （统计页那枚 `currentWeek ?: 1`、首页翻周按钮那枚 `displayWeek ?: 1` 各 0 处）。
 *    本仓口径：**不许拿「grep 没匹配到」当全绿**。
 */
class WeekHeaderDateWiringGuardTest {

    // ─────────────── ① 内核仍是纯 JVM 判据 ───────────────

    @Test
    fun `内核是纯 JVM 判据 体里不许藏 1`() {
        val code = blankCommentsKeepingLiterals(readMainSource(KERNEL))
        for (banned in listOf(
            "import android", "import androidx", "Build.VERSION", "LocalContext", "LocalConfiguration",
            "SystemClock", "currentTimeMillis", "TimeZone", "LocalDate", "java.time", "Clock",
        )) {
            val hits = occurrences(code, banned)
            assertEquals(
                "$KERNEL_NAME 里「$banned」出现 $hits 处，期望 0 处：这一枚说的是「表头印第几周」，" +
                    "自己去读设备/时钟就只在「这台机器上的今天」成立，JVM 里打不开这张表（本仓硬口径）",
                0,
                hits,
            )
        }
        assertTrue(
            "headerWeekNumberOrNull 的签名不再收「displayWeek / currentWeek」两枚参数了：" +
                "多收一枚（例如 today / semester）就意味着内核自己去反查「今天落在第几周」，" +
                "而假期里那一天根本不对应任何学期周",
            Regex("""headerWeekNumberOrNull\(\s*displayWeek: Int\?,\s*currentWeek: Int\?,?\s*\)\s*:\s*Int\?""")
                .containsMatchIn(code),
        )
        // 语义本体：结果只能是两枚入参之一
        assertEquals(
            "内核体必须逐字是 `$KERNEL_BODY`（实到 ${occurrences(code, KERNEL_BODY)} 处，期望 1 处）：" +
                "换成 when/if 的等价写法可以，但要连同本守卫与 [WeekHeaderDatePolicyTest] 一起改",
            1,
            occurrences(code, KERNEL_BODY),
        )
        val fallback = occurrences(code, "?: 1") + occurrences(code, "?: 0")
        assertEquals(
            "内核体里藏着兜底常量「?: 1 / ?: 0」（$fallback 处，期望 0 处）：病灶从调用点搬进函数体还是病灶 —— " +
                "寒暑假里两枚周次事实都取不到，那枚常量就是「表头凭空印第 1 周」的那一笔",
            0,
            fallback,
        )
        assertEquals(
            "main 里 `internal fun headerWeekNumberOrNull(` 定义了 ${kernelDefinitions} 处，期望恰好 1 处：" +
                "长出第二份判据，表头与别处就能各印各的日期",
            1,
            kernelDefinitions,
        )
    }

    // ─────────────── ② 病灶那一形不许回潮 ───────────────

    @Test
    fun `表头那一枚不许再带回兜底`() {
        val week = blankCommentsKeepingLiterals(readMainSource(WEEK_VIEW))
        val raw = readMainSource(WEEK_VIEW)

        for (banned in listOf(LEGACY_HEAD_LINE, "?: currentWeek ?: 1", "displayWeek ?: currentWeek ?: 1")) {
            val hits = occurrences(week, banned)
            assertEquals(
                "WeekView.kt 里「$banned」出现 $hits 处，期望 0 处 —— 那枚 `?: 1` 就是本卡病灶：假期里" +
                    "pagerWeek 回 null 走单页网格（屏幕上没有第 1 周、顶栏写「假期中」），表头却退成 1、" +
                    "印第 1 周那七天的日期。判据在 WeekHeaderTodayPolicy.kt 的 headerWeekNumberOrNull",
                0,
                hits,
            )
        }
        // 把 `?: 1` bolt 在内核调用后面（"绕过 null 那一档"的另一种写法）同样是病灶
        val bolted = Regex("""currentWeek\)?\s*\?:\s*1""").findAll(week).toList()
        assertEquals(
            "WeekView.kt 里有周次派生后面 bolt 着 `?: 1`（${bolted.size} 处，期望 0 处，落点在第 " +
                "${bolted.firstOrNull()?.let { lineOf(raw, raw.indexOf(it.value)) } ?: -1} 行附近）：" +
                "内核给出 null 之后再补 1，与当年写在内联表达式里没有区别",
            0,
            bolted.size,
        )
    }

    // ─────────────── ③ 同一判据只许两份派生：Pager 一枚 + 表头一枚 ───────────────

    @Test
    fun `表头那一枚走内核而 Pager 那一枚仍走原表达式`() {
        val week = blankCommentsKeepingLiterals(readMainSource(WEEK_VIEW))

        assertEquals(
            "`Pager` 那一枚 `$PAGER_WEEK_LINE` 出现 ${occurrences(week, PAGER_WEEK_LINE)} 处，期望恰好 1 处：" +
                "它是「没有周次信息就退回单页网格」那半档的主人，内核的口径就是照着它对齐的",
            1,
            occurrences(week, PAGER_WEEK_LINE),
        )
        assertEquals(
            "全文里 `displayWeek ?: currentWeek` 出现 ${occurrences(week, "displayWeek ?: currentWeek")} 处，" +
                "期望恰好 1 处（就是上面那枚 pagerWeek）：2 处就是表头又把同一个判据现写了一遍 —— " +
                "同一判据两份表达式正是本卡的病灶形状，两处能各自写错",
            1,
            occurrences(week, "displayWeek ?: currentWeek"),
        )
        assertEquals(
            "表头那一枚必须逐字是 `$HEAD_WIRING`（实到 ${occurrences(week, HEAD_WIRING)} 处，期望恰好 1 处）：" +
                "0 处＝表头不再走内核（回到就地兜底或写死周号），1 枚都不许多 —— 判据只许一处消费",
            1,
            occurrences(week, HEAD_WIRING),
        )
        assertEquals(
            "WeekView.kt 全文里 `headerWeekNumberOrNull(` 出现 ${occurrences(week, "headerWeekNumberOrNull(")} 处，" +
                "期望恰好 1 处：表头这一枚判据只许这一处消费点",
            1,
            occurrences(week, "headerWeekNumberOrNull("),
        )
    }

    // ─────────────── ④ 周次为 null ⇒ 日期起点为 null，表头那一行只剩星期名 ───────────────

    @Test
    fun `weekStartDate 跟着周次一起留空而消费端形状不改`() {
        val week = blankCommentsKeepingLiterals(readMainSource(WEEK_VIEW))

        assertEquals(
            "`$START_WIRING` 出现 ${occurrences(week, START_WIRING)} 处，期望恰好 1 处：" +
                "weekStartDate 必须由那枚**可空**周次派生 —— 周次为 null 时它就是 null，" +
                "表头那一行什么都不印。写成 semester?.startLocalDate?.plusDays(...) 不带 ?.let 的那一形，" +
                "等于把 `?: 1` 换成「永远从开学日算」，病灶回潮",
            1,
            occurrences(week, START_WIRING),
        )
        assertEquals(
            "递给表头的 `weekStartDate = weekStartDate,` 出现 ${dayHeaderArgumentOccurrences(week)} 处，期望恰好 1 处",
            1,
            dayHeaderArgumentOccurrences(week),
        )
        assertEquals(
            "DayHeader 的 `weekStartDate: LocalDate? = null,` 参数不在（枚数 " +
                "${occurrences(week, "weekStartDate: LocalDate? = null,")}，期望 1 处）：签名被改成非空就是" +
                "把 null 那一档堵死 —— 本卡的修法正是让表头接得住 null",
            1,
            occurrences(week, "weekStartDate: LocalDate? = null,"),
        )
        assertEquals(
            "那一格印的日期仍必须是 `val cellDate = weekStartDate?.plusDays(index`（枚数 " +
                "${occurrences(week, "val cellDate = weekStartDate?.plusDays(index")}，期望 1 处）：换源就换了一套口径",
            1,
            occurrences(week, "val cellDate = weekStartDate?.plusDays(index"),
        )
        assertEquals(
            "日期那一行必须仍由 `if (cellDate != null) {` 把关（枚数 ${occurrences(week, "if (cellDate != null)")}，" +
                "期望恰好 1 处）：摘掉这枚闸，null 那一路就会印出「1 号 / 月名」之类的东西 —— 本卡要的" +
                "「假期里表头只剩星期名」全靠它",
            1,
            occurrences(week, "if (cellDate != null)"),
        )
    }

    // ─────────────── ⑤ 全仓只一份定义与一处调用 ───────────────

    @Test
    fun `nobodyElseCopiesTheWeekDerivation`() {
        val ledger = mapOf(
            KERNEL_NAME to 1,
            WEEK_VIEW_NAME to 1,
        )
        val actual = mainHits("headerWeekNumberOrNull(").associate { File(it.first).name to it.second }

        assertEquals(
            "main 里 `headerWeekNumberOrNull(` 的落点册子漂了：期望 $ledger，实到 $actual —— " +
                "定义一枚 + 表头调用一处。第二枚调用就是第二份能各自写错的账（DayView / 组件侧抄一份" +
                "就会各错各的，与 T182 那条「两处界面各答一遍」是同一族）",
            ledger,
            actual,
        )
        assertEquals(
            "main 里 `headerWeekNumberOrNull(` 的总枚数不是 2（定义 1 + 调用 1）：实到 " +
                "${mainHits("headerWeekNumberOrNull(").sumOf { it.second }} 处",
            2,
            mainHits("headerWeekNumberOrNull(").sumOf { it.second },
        )
    }

    // ─────────────── ⑥ 反套套逻辑：探测器认得改前那一形，也不误伤合法 `?: 1` ───────────────

    /**
     * 探测器就是上面第②层那一句 `?: currentWeek ?: 1`。它必须：
     * - 在**改前那一行原文**上抓到 1 处（否则"0 处"是因为它什么都抓不到）；
     * - 在本仓既有的两枚合法周次兜底上抓 0 处（普查表里最像的两枚：统计页按周细看的首次落点、
     *   首页「上一周」按钮），以及一枚与周次完全无关的合法 `?: 1`（图表纵轴 `maxOrNull() ?: 1L`）；
     * - 在内核文件的**原文**里抓到那一处（KDoc 引了病灶原文），抹注释后 0 处 —— 证明"抹注释保字面量"
     *   这一步真的在挡假阳性，而不是随手写的一句注释。
     */
    @Test
    fun `探测器抓得住病灶原文 也不误伤合法兜底`() {
        val probe = Regex("""\?:\s*currentWeek\s*\?:\s*1""")

        assertEquals(
            "探测器在改前那一行原文上抓到 ${probe.findAll(LEGACY_HEAD_LINE).toList().size} 处，期望 1 处：" +
                "它连病灶都不认得的话，上面那几层「0 处」全是空的",
            1,
            probe.findAll(LEGACY_HEAD_LINE).toList().size,
        )
        for (legit in listOf(
            "selectedWeek = (currentWeek ?: 1).coerceIn(1, totalWeeks.coerceAtLeast(1))",
            "onClick = { onBrowseWeekChange(((displayWeek ?: 1) - 1).coerceAtLeast(1)) }",
            "val top = max(1L, minutes.maxOrNull() ?: 1L)",
        )) {
            assertEquals(
                "探测器在本仓既有的合法写法「$legit」上抓到 ${probe.findAll(legit).toList().size} 处，期望 0 处：" +
                    "把合法兜底一起扫红就会逼下一张卡把守卫改松（普查表交的是判据，不是一把糊尺）",
                0,
                probe.findAll(legit).toList().size,
            )
        }
        val raw = readMainSource(KERNEL)
        val blanked = blankCommentsKeepingLiterals(raw)
        assertEquals(
            "内核文件原文里应当留着那行被引用的病灶写法（KDoc 逐字引的，枚数 ${occurrences(raw, LEGACY_HEAD_LINE)}，" +
                "期望 1 处）：这一枚是「探测器能看见真东西」的对照组",
            1,
            occurrences(raw, LEGACY_HEAD_LINE),
        )
        assertEquals(
            "同一枚内核文件抹注释之后应当 0 处（实到 ${occurrences(blanked, LEGACY_HEAD_LINE)} 处）：" +
                "注释里写着被禁写法是本文档的常态，不抹掉就到处是假阳性",
            0,
            occurrences(blanked, LEGACY_HEAD_LINE),
        )
    }

    // ---------------- helpers ----------------

    /** 内核里 `internal fun headerWeekNumberOrNull(` 的定义枚数（全 main） */
    private val kernelDefinitions: Int
        get() = mainHits("fun headerWeekNumberOrNull(").sumOf { it.second }

    /** `DayHeader(` 那一处实参表里 `weekStartDate = weekStartDate` 的枚数 */
    private fun dayHeaderArgumentOccurrences(blankedWeekView: String): Int {
        val at = blankedWeekView.indexOf("DayHeader(")
        assertTrue("找不到 `DayHeader(`：表头的落点被挪走了，本守卫要跟着重钉", at >= 0)
        val open = at + "DayHeader".length
        var depth = 0
        for (i in open until blankedWeekView.length) {
            when (blankedWeekView[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        return occurrences(blankedWeekView.substring(open + 1, i), "weekStartDate = weekStartDate")
                    }
                }
            }
        }
        throw IllegalStateException("DayHeader 的实参括号没配平")
    }

    private fun readMainSource(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 $relative：挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /** 全仓 main 里扫某串文本（抹注释后），返回 (相对路径, 命中次数)，只挑有命中的文件 */
    private fun mainHits(needle: String): List<Pair<String, Int>> {
        val root = findMainJavaDir()
        val hits = mutableListOf<Pair<String, Int>>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val count = occurrences(blankCommentsKeepingLiterals(file.readText()), needle)
            if (count > 0) hits += file.relativeTo(root).path.replace('\\', '/') to count
        }
        return hits
    }

    private fun occurrences(haystack: String, needle: String): Int {
        var from = 0
        var found = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return found
            found++
            from = at + needle.length
        }
    }

    /** 失败消息把人带到那一处：needle 在**原文**（不是抹注释后的文本）里的行号 */
    private fun lineOf(rawSource: String, position: Int): Int =
        if (position < 0) -1 else rawSource.substring(0, position).count { it == '\n' } + 1

    /** 只抹注释、保留字符串字面量，长度与换行位置不变（行号因此仍然可信） */
    private fun blankCommentsKeepingLiterals(source: String): String {
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

                source.startsWith("\"\"\"", i) -> {
                    i = source.indexOf("\"\"\"", i + 3).let { if (it < 0) out.size else it + 3 }
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
        const val KERNEL = "com/buaa/schedule/ui/home/WeekHeaderTodayPolicy.kt"
        const val WEEK_VIEW = "com/buaa/schedule/ui/home/WeekView.kt"
        const val KERNEL_NAME = "WeekHeaderTodayPolicy.kt"
        const val WEEK_VIEW_NAME = "WeekView.kt"

        /** 改前表头那一枚的原文（探测器与"病灶不回潮"两层的共同标尺） */
        const val LEGACY_HEAD_LINE = "val displayWeekNumber = displayWeek ?: currentWeek ?: 1"

        /** 内核体那一枚表达式（语义 = displayWeek ?: currentWeek，零 ?: 1） */
        const val KERNEL_BODY = "= displayWeek ?: currentWeek"

        const val PAGER_WEEK_LINE = "val pagerWeek = displayWeek ?: currentWeek"
        const val HEAD_WIRING = "val displayWeekNumber = headerWeekNumberOrNull(displayWeek, currentWeek)"
        const val START_WIRING =
            "val weekStartDate = displayWeekNumber?.let { semester?.startLocalDate?.plusDays((it - 1).toLong() * 7L) }"
    }
}
