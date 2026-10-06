package com.buaa.schedule.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T182 接线守卫：病不在内核（内核有自己的表驱动单测 [WeekHeaderTodayPolicyTest]），
 * 在**表头那一句到底把什么当"今天到了"的证据**。这一断线同样是静默的：判据照对、
 * 寒暑假里那一格照样亮着胶囊、门禁照绿 —— 只是屏幕上顶栏写「假期中」而表头把学期
 * 第 1 周的某一天标成今天（同屏自相矛盾，与 [WeekGrid.coursesForContent] 那支空网格
 * 早已认过的账是同一枚函数）。
 *
 * 刀法照抄 [WeekChangeSummaryWiringGuardTest] / [TopBarDateWiringGuardTest]：读源文件文本、
 * 匹配前先 `blankComments` 抹注释（本卡与内核的注释里就写着被禁的写法本身）、锚点找不到就抛、
 * 不用 assumeTrue 跳过。工作树文件已在 `app/build.gradle.kts` 的 `guardReadWorkingTreeFiles`
 * 输入面里（`src/main/java` 整目录），不必另加机制。
 *
 * 钉五档：
 * 1. 判据内核 [shouldHighlightToday] 零 android/androidx import、零时钟零设备读数，
 *    浏览周次 / 当前教学周 / 有没有学期三样事实全由调用点当参数传（本仓收单硬判据）；
 * 2. `WeekView` 里 `DayHeader(highlightToday = …)` 那一处必须整条交给内核（恰好一枚调用），
 *    实参表里三枚事实一枚不许少 —— 尤其 `semester != null` 那一枚是"表头到底印不印日期"的依据，
 *    换成常量就是把方向感或真相关掉；
 * 3. 病灶原文两串（`isBrowsingCurrentWeek` 与 `|| currentWeek == null`）在 `WeekView.kt` 里
 *    一枚不许留：那枚局部布尔在两个 null 上自己为真，光删析取支拦不住回来；
 * 4. 表头的消费端不许改形状：胶囊仍由 `highlightToday` 决定（`isToday` 那一行逐字钉住），
 *    而那一格印的日期仍是 `weekStartDate` 推出来的同一个 `index` —— 亮与印必须是同一格，
 *    否则本卡那半句"扣在错的那一格上"就不是被修掉了而是换了个形式；
 * 5. 全仓只许一份这枚判据的定义与一处调用（不许 DayView / 组件侧各抄一套再各错一遍）。
 */
class WeekHeaderTodayWiringGuardTest {

    @Test
    fun theKernelStaysPureJvmAndTakesEveryFactAsParameter() {
        val code = blankComments(source(KERNEL))
        for (banned in listOf(
            "import android", "import androidx", "Build.VERSION", "LocalContext", "LocalConfiguration",
            "SystemClock", "currentTimeMillis", "TimeZone", "LocalDate", "java.time", "Clock",
        )) {
            val hits = occurrences(code, banned).size
            assertEquals(
                "$KERNEL 里「$banned」出现 $hits 处，期望 0 处：判据自己去读设备/时钟，" +
                    "寒暑假那一档就只能在这台机器上的今天成立，JVM 里打不开这张表（本仓硬口径）",
                0,
                hits,
            )
        }
        assertTrue(
            "shouldHighlightToday 的签名不再收「displayWeek / currentWeek / hasSemester」三枚参数了：" +
                "少一枚就意味着内核自己去嗅当前周次或学期，而那一嗅读的是时钟",
            Regex("""shouldHighlightToday\(\s*displayWeek: Int\?,\s*currentWeek: Int\?,\s*hasSemester: Boolean,?\s*\)\s*:\s*Boolean""")
                .containsMatchIn(code),
        )
        val escapeHatch = occurrences(code, "|| currentWeek == null").size
        assertEquals(
            "内核体里留着析取支「|| currentWeek == null」（$escapeHatch 处，期望 0 处）：寒暑假与学期结束后 " +
                "currentWeek 正是 null，这一支一开，表头就把学期第 1 周的同星期几那一格说成今天",
            0,
            escapeHatch,
        )
        assertTrue(
            "内核里那一枚「当前教学周未知就不许亮」的闸（`currentWeek != null`）不见了：" +
                "摘掉析取支之后这就是唯一的窄处，没了它就等于把这一判又放回\"两个 null 相等\"",
            code.contains("currentWeek != null"),
        )
        assertEquals(
            "main 里 `internal fun shouldHighlightToday(` 定义了 $kernelDefinitions 处，期望恰好 1 处：" +
                "长出第二份判据，表头与别处就能各亮各的",
            1,
            kernelDefinitions,
        )
    }

    @Test
    fun weekViewHandsTheHighlightDecisionToTheKernel() {
        val week = blankComments(source(WEEK_VIEW))
        val wholeFileCalls = occurrences(week, "shouldHighlightToday(").size
        val args = dayHeaderArguments(week)
        val calls = occurrences(args, "shouldHighlightToday(").size
        assertEquals(
            "`DayHeader(highlightToday = …)` 的实参里 `shouldHighlightToday(` 有 $calls 处，期望恰好 1 处 —— " +
                "0 处就是判据不再由内核派生（回到就地兜底 / 写死 true / 写死 false），" +
                "2 处就是两处各判一次能各亮各的",
            1,
            calls,
        )
        for (fact in listOf("displayWeek", "currentWeek", "semester != null")) {
            val hit = occurrences(args, fact).size
            assertTrue(
                "递进去的实参里没有 $fact（当前实参表：$args）—— 少这一枚内核就只能自己猜那一样事实；" +
                    "把 `semester != null` 换成常量尤其不许：它按学期换成 true/false 正是" +
                    "「无学期时表头不印日期、只亮星期名」那半档方向感，或「有学期时不许谎报今天」那半档真相",
                hit > 0,
            )
        }
        assertEquals(
            "WeekView.kt 全文里 `shouldHighlightToday(` 出现 $wholeFileCalls 处，期望恰好 1 处：" +
                "判据只许表头一处消费",
            1,
            wholeFileCalls,
        )
    }

    /** 病灶本体那两串原文一枚不许留：析取支与那枚"两个 null 自己相等"的局部布尔 */
    @Test
    fun theDefectiveShapeCannotComeBack() {
        val week = blankComments(source(WEEK_VIEW))
        for (banned in listOf("|| currentWeek == null", "isBrowsingCurrentWeek", "highlightToday = true", "highlightToday = false")) {
            val hits = occurrences(week, banned).size
            assertEquals(
                "WeekView.kt 里「$banned」出现 $hits 处，期望 0 处 —— " +
                    "假期里 currentWeek 为 null 不是「今天到了某一格」的证据：那一格印的日期来自 " +
                    "displayWeek ?: currentWeek ?: 1，此时退成学期第 1 周，而同屏顶栏写着「假期中」。" +
                    "写死 true/false 那一档更不行：方向感与真相各有半档会被整个抹掉（判据在 WeekHeaderTodayPolicy.kt）",
                0,
                hits,
            )
        }
        val constantSemester = occurrences(week, "hasSemester = true").size +
            occurrences(week, "hasSemester = false").size
        assertEquals(
            "调用点把 hasSemester 写成常量（$constantSemester 处），期望 0 处：那一档只能由 semester 自己说话",
            0,
            constantSemester,
        )
    }

    /** 表头消费端：亮的那一格与印日期的那一格必须是同一个 index */
    @Test
    fun theHeaderStillPutsThePillOnTheSameCellThatPrintsTheDate() {
        val week = blankComments(source(WEEK_VIEW))
        assertEquals(
            "`val isToday = highlightToday && today.dayOfWeek.value == index + 1` 出现 " +
                "${occurrences(week, "val isToday = highlightToday").size} 处，期望恰好 1 处：" +
                "胶囊不再由这枚旗决定（就地重算、或按 cellDate 各判一次），本守卫钉的调用点就悬空了",
            1,
            occurrences(week, "val isToday = highlightToday && today.dayOfWeek.value == index + 1").size,
        )
        assertEquals(
            "表头那一格印的日期不再是 weekStartDate 按同一个 index 推出来的（当前枚数：" +
                "${occurrences(week, "val cellDate = weekStartDate?.plusDays(index").size}，期望 1 处）—— " +
                "亮与印分家就换一种形式继续谎报哪一天是今天",
            1,
            occurrences(week, "val cellDate = weekStartDate?.plusDays(index").size,
        )
        assertEquals(
            "DayHeader 的 `highlightToday: Boolean,` 参数不在（枚数 " +
                "${occurrences(week, "highlightToday: Boolean,").size}，期望 1 处）：判据被搬进消费端就地算，" +
                "就是第二份能各自写错的账",
            1,
            occurrences(week, "highlightToday: Boolean,").size,
        )
    }

    @Test
    fun nobodyElseCopiesThePredicate() {
        assertEquals(
            "main 里 `shouldHighlightToday(` 的调用枚数不是 1（WeekView 表头那一处）：" +
                "${mainHits("shouldHighlightToday(").joinToString(" | ")} —— 同一枚判据抄第二份，" +
                "假期那一档就会在一处修好、另一处继续错",
            1,
            mainHits("shouldHighlightToday(").sumOf { it.second } - kernelDefinitions,
        )
        assertEquals(
            "main 里 `highlightToday =` 的赋值点不是 1（WeekView 交给内核那一处）：" +
                "${mainHits("highlightToday =").joinToString(" | ")}",
            1,
            mainHits("highlightToday =").sumOf { it.second },
        )
    }

    // ---------------- helpers ----------------

    /** 内核里 `internal fun shouldHighlightToday(` 的定义枚数（全 main） */
    private val kernelDefinitions: Int
        get() = mainHits("fun shouldHighlightToday(").sumOf { it.second }

    /** `DayHeader(` 那一处的实参表（配平的括号内文本，去掉前后空白） */
    private fun dayHeaderArguments(blankedWeekView: String): String {
        val at = blankedWeekView.indexOf("DayHeader(")
        assertTrue("找不到 `DayHeader(`：表头的落点被挪走了，本守卫要跟着重钉", at >= 0)
        assertTrue(
            "`DayHeader(` 第一枚命中就落在定义上（前四个字符是 fun）：调用点被删掉了？",
            !blankedWeekView.substring(maxOf(0, at - 4), at).endsWith("fun "),
        )
        val open = at + "DayHeader".length
        var depth = 0
        for (i in open until blankedWeekView.length) {
            when (blankedWeekView[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return blankedWeekView.substring(open + 1, i).trim()
                }
            }
        }
        throw IllegalStateException("DayHeader 的实参括号没配平")
    }

    private fun source(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 $relative：挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /** 全仓 main 里扫某串文本（抹注释后），返回 (相对路径, 命中次数)，只挑有命中的文件 */
    private fun mainHits(needle: String): List<Pair<String, Int>> {
        val root = findMainJavaDir()
        val hits = mutableListOf<Pair<String, Int>>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val count = occurrences(blankComments(file.readText()), needle).size
            if (count > 0) hits += file.relativeTo(root).path.replace('\\', '/') to count
        }
        return hits
    }

    private fun occurrences(hay: String, needle: String): List<Int> {
        val found = mutableListOf<Int>()
        var at = hay.indexOf(needle)
        while (at >= 0) {
            found += at
            at = hay.indexOf(needle, at + needle.length)
        }
        return found
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
    }
}
