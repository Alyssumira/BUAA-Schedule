package com.buaa.schedule.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T266④：#344 那枚「同屏三把尺」的源码形状守卫。
 *
 * 钉的对象是**接线**：段②那张分档表钉得住内核算得对，钉不住界面上有人绕过它自己再算一把尺——
 * 本卡病的形状正是「列表按星期几、Hero 按学期＋周次、门按列表那把尺开」三处各读各的。
 *
 * 四层各用一种钉法（点名写明，红了按消息里的文件名与判据回去看）：
 * - ① 逐字节：门那一行、planner 那一行的整行字面量。
 * - ② 抹注释后找锚点：TodayPlanner 里学期那把尺的三个锚点一处都不许有（它只许执行内核的结论）。
 * - ③ 数出现次数：DayView 里 `semester == null` 零枚、内核调用恰三枚；
 *   **这一层就是拦「同屏两处又各读一把尺」的那枚**——册子按文件点名 `WeekdayOnly` 这一档落在哪几枚文件里，
 *   DayView 一旦自己判「没设学期 ⇒ 走星期几」，册子立刻多出一枚文件而红。
 * - ④ 读形状：按花括号配平取出 DayScreen 与 TodayHero 两段，读门那枚条件的写法与句子的落点。
 *
 * 判据一律不写死行号：先找锚点、再按形状取段，改动过排版也照样读得出来。
 */
class TodayHeroSingleRulerWiringGuardTest {

    private companion object {
        const val DAY_VIEW = "com/buaa/schedule/ui/home/DayView.kt"
        const val PLANNER = "com/buaa/schedule/domain/schedule/TodayPlanner.kt"
        const val RULER = "com/buaa/schedule/domain/schedule/TodayScreenRuler.kt"

        // ① 逐字节：Hero 那扇门的两行接线（整行比对，改回别的尺就断）
        const val HERO_GATE_LINE = "if (hero is TodayHeroGate.Open) {"
        const val PLANNER_CALL_LINE = "TodayPlanner.plan(dayCourses, timeSlots, date, now, scope)"

        // ③ 数出现次数：DayView 对内核的调用枚数（尺＝scope、列表＝courses、门＝hero）
        const val RULER_CALLS_IN_DAY_VIEW = 3

        // ③ 的册子：这一档「按星期几」只许落在这两枚文件里
        val WEEKDAY_ONLY_ALLOWED_FILES = setOf(
            RULER,     // 内核：判出这一档、并按它取列表
            PLANNER,   // planner：执行这一档（不判学期、不算周号）
        )
    }

    // ─────────────── ① 逐字节 ───────────────

    @Test
    fun `门的条件与计划的入参按整行钉住 不许换回别的尺`() {
        val dayView = readMainSource(DAY_VIEW)
        assertTrue(
            "$DAY_VIEW 里 Hero 那扇门不再是「吃内核交回的那一档」：应当逐字节是 $HERO_GATE_LINE，" +
                "改成 dayCourses.isNotEmpty() 或 plan != null 就是回到改前那把按列表开的门",
            dayView.contains(HERO_GATE_LINE),
        )
        assertTrue(
            "$DAY_VIEW 里 TodayPlanner 不再吃「列表那一批课 + 内核选定的那把尺」：应当逐字节是 " +
                "$PLANNER_CALL_LINE。传整库 courses 或自己算 scope 都是让第二处重新读一次尺",
            dayView.contains(PLANNER_CALL_LINE),
        )
    }

    // ─────────────── ② 抹注释后找锚点 ───────────────

    @Test
    fun `planner 体内不许再出现学期那把尺的三个锚点`() {
        val planner = withoutComments(readMainSource(PLANNER))
        for (anchor in listOf("currentWeekOrNull", "startLocalDate", "?: return EMPTY", "Semester")) {
            assertEquals(
                "$PLANNER 抹掉注释后仍写着 $anchor：学期有没有、那一周是第几周是 TodayScreenRuler 的结论，" +
                    "planner 再读一遍就是同屏第二把尺（#344 改前正是它起手 return EMPTY 与列表打架）",
                0,
                occurrences(planner, anchor),
            )
        }
        assertTrue(
            "$PLANNER 抹掉注释后找不到 when (scope)：它现在只该按内核交回的那把尺决定排不排课次",
            planner.contains("when (scope)"),
        )
    }

    @Test
    fun `DayView 只算一次学期那把尺 周号由内核当入参收走`() {
        val dayView = withoutComments(readMainSource(DAY_VIEW))
        // 顶栏那一行（weekTextFor）与 DayScreen 各读一次同一枚在册函数；第三处就是又起一把尺
        assertEquals(
            "$DAY_VIEW 里 WeekCalculator.currentWeekOrNull 的调用枚数漂了：这一屏的周号只许在 DayScreen " +
                "与顶栏那一句各算一次，且都递进内核当参数——多出来的一枚就是第二把尺",
            2,
            occurrences(dayView, "currentWeekOrNull("),
        )
        assertEquals(
            "$DAY_VIEW 里又出现 semester == null 的裸判据：没设学期走哪把尺由 TodayScreenRuler.scopeOf 交回，" +
                "界面自己判一次就会和 Hero 那侧分叉（这正是本卡那句谎话的来源）",
            0,
            occurrences(dayView, "semester == null"),
        )
    }

    // ─────────────── ③ 数出现次数 + 册子点名 ───────────────

    @Test
    fun `内核调用恰三枚 少一枚就是有一处还各读一把尺`() {
        val dayView = withoutComments(readMainSource(DAY_VIEW))
        assertEquals(
            "$DAY_VIEW 对 TodayScreenRuler 的引用枚数不是 $RULER_CALLS_IN_DAY_VIEW：" +
                "尺（scopeOf）/ 列表（coursesFor）/ 门（heroGateOf）各一枚，" +
                "少了就是把哪一处交回界面自己算，多了就是同屏第二次判尺",
            RULER_CALLS_IN_DAY_VIEW,
            occurrences(dayView, "TodayScreenRuler."),
        )
    }

    @Test
    fun `没设学期那一档只落在内核与 planner 两枚文件里`() {
        val register = mainSources()
            .map { it.relative to occurrences(withoutComments(it.text), "DayCourseScope.WeekdayOnly") }
            .filter { it.second > 0 }
            .sortedBy { it.first }
        assertEquals(
            "「没设学期走星期几」这一档的落点漂了（应只有内核判它、planner 执行它）。现册子：\n" +
                register.joinToString(separator = "") { "  ${it.first}: ${it.second} 处\n" } +
                "DayView 里一旦也出现这一档，就是同屏两处各读一把尺——本卡的病灶形状",
            WEEKDAY_ONLY_ALLOWED_FILES,
            register.map { it.first }.toSet(),
        )
    }

    // ─────────────── ④ 读形状 ───────────────

    @Test
    fun `DayScreen 里门那把尺的唯一来源是列表结论本身`() {
        val screen = balancedBlock(withoutComments(readMainSource(DAY_VIEW)), "private fun DayScreen(")
        // 门那一档只许把「列表有没有卡」当事实递给内核，不许拿它当条件
        assertEquals(
            "DayScreen 里 dayCourses.isNotEmpty() 的枚数不是 1：它只该作为 hasCards 入参递进 heroGateOf，" +
                "第二次出现就是界面自己开了一次门",
            1,
            occurrences(screen, "dayCourses.isNotEmpty()"),
        )
        assertTrue(
            "DayScreen 里那一枚 dayCourses.isNotEmpty() 没挂在 hasCards = 上：门又开始按列表那把尺自己开",
            screen.contains("hasCards = dayCourses.isNotEmpty()"),
        )
        assertEquals(
            "DayScreen 里 plan != null 又变成了判据：改前它和 dayCourses.isNotEmpty() 一起构成那扇开错尺的门",
            0,
            occurrences(screen, "plan != null"),
        )
    }

    @Test
    fun `Hero 那张卡吃档位 不再自己读 plan 也不再自己判 null`() {
        val hero = balancedBlock(withoutComments(readMainSource(DAY_VIEW)), "private fun TodayHero(")
        assertTrue(
            "TodayHero 的入参不再是内核交回的那一档（TodayHeroGate.Open）：它一旦收 TodayPlan，" +
                "念哪一句就又回到界面自己判 ongoing/next 那一手",
            hero.contains("hero: TodayHeroGate.Open"),
        )
        assertFalse(
            "TodayHero 的签名里又出现了 TodayPlan：这张卡只许吃档位带回来的那一批课次",
            hero.substringBefore("{").contains("TodayPlan"),
        )
        val sentence = "今天没有更多课了"
        assertEquals(
            "DayView 里那句「今天没有更多课了」的枚数不是 1：多一处就是多一条不吃内核结论的出口",
            1,
            occurrences(withoutComments(readMainSource(DAY_VIEW)), "\"$sentence\""),
        )
        assertTrue(
            "TodayHero 里那句「今天没有更多课了」不再挂在 NothingMore 那一档下面：它必须只在" +
                "TodayHeroLine.NothingMore 里出声（那一档由内核核过「列表有卡且课次真排完了」）",
            hero.indexOf("TodayHeroLine.NothingMore ->") in 0 until hero.indexOf(sentence),
        )
    }

    // ---------------- 源码核对小工具（与本仓守卫同一套写法）----------------

    private class SourceFile(val relative: String, val text: String)

    private fun readMainSource(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    private fun mainSources(): List<SourceFile> {
        val root = findMainJavaDir()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${root.path} 下一个 .kt 都没有，路径不对", files.isNotEmpty())
        return files.map {
            SourceFile(
                root.toPath().relativize(it.toPath()).toString().replace(File.separatorChar, '/'),
                it.readText().replace("\r\n", "\n"),
            )
        }
    }

    /** 按花括号配平从签名起取整段（含签名），读的是形状不是行号 */
    private fun balancedBlock(source: String, signature: String): String {
        val at = source.indexOf(signature)
        check(at >= 0) { "找不到 $signature：改过名或挪过家就要回来重钉本守卫" }
        val open = source.indexOf('{', at)
        check(open >= at) { "$signature 之后找不到左括号" }
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(at, i + 1)
                }
            }
        }
        throw IllegalStateException("$signature 的花括号没配平")
    }

    private fun occurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var at = haystack.indexOf(needle)
        while (at >= 0) {
            count++
            at = haystack.indexOf(needle, at + needle.length)
        }
        return count
    }

    /** 只抹注释、保留字符串字面量：main 与本文件的注释都逐字写着那些病灶形状，不抹就会把散文当证据 */
    private fun withoutComments(source: String): String {
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
        repeat(6) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { File(dir, it) }
                .firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 src/main/java：本守卫按工作树里的源码读形状")
    }
}
