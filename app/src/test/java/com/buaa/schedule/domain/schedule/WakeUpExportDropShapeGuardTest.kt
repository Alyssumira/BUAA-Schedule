package com.buaa.schedule.domain.schedule

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T203：钉「WakeUp 导出把空周次那档判成不出场」这件事长在**源码形状**上，不长在注释里。
 *
 * ## 四家钉法各是哪一种（卡面 §2A-5 要的就是这一格）
 *
 * 1. **逐字节**：档 ① 那三行赋值整行逐字钉（`val startWeek = weeks.firstOrNull() ?: return@mapNotNull null` 等），
 *    档 ④ 那四支文案整行逐字钉 —— 改一个字（把 `weeks.last()` 换回 `lastOrNull() ?: startWeek`）当场红。
 * 2. **抹注释找锚点**：`?: 1` 与 `?: startWeek` 各钉 **0 处**，而这两枚字面量在**含注释**的原文里
 *    各恰好 1 处（第 61/62 行的尾注释逐字引用了病灶）。所以只 grep 原文的针今天会报"还有 1 处"而误判红，
 *    只读注释的针又会报 0 处而放过回潮 —— 本守卫一律先抹注释再数。
 * 3. **数出现次数**：`return@mapNotNull null` 在 `toWakeUpJson` 体内恰好 **2** 枚（两档各一枚），
 *    全文件恰好 **3** 枚（第三枚是 `toWeeklyText` 里"空课日整天不出场"那一档，与本卡无关但必须同源数清）。
 * 4. **读形状**：档 ① 末格按**索引顺序**判"那枚 `?: return@mapNotNull null` 真的排在拼 JSON 字面量之前"
 *    （写在后面等于先造出假周次再丢掉，形状看着对、数据已经脏了）；档 ② 判两档的先后（先节次后周次
 *    = 一门只落一档）；档 ③ 判 VM 里的报数只从 tally 来、不再自己 `courses.count { … }` 拼一遍口径。
 *
 * ## 两侧的红臂（本守卫自己也得是活的）
 *
 * - **朝宽**（把空周次换回猜测值 `weeks.firstOrNull() ?: 1`）⇒ 档 ① 的逐字节格、`?: 1` 的 0 处格、
 *   `return@mapNotNull null == 2` 格同时红；
 * - **朝窄**（把 `weeks.last()` 改成 `if (weeks.isEmpty()) 0 else weeks.last()` 那种"看起来更稳"的写法）
 *   ⇒ 档 ① 的逐字节格与"体内 `lastOrNull`/`isEmpty()` 各 0 处"红。
 *
 * 只做**源码核对**（JVM，无 Robolectric、无设备、不读时钟）。判据本身的逐格账在
 * `WakeUpExportDropQuadrantTest`；本文件只管形状。⚠️ 未装机：对方 App 收到空 courses 数组时
 * 显示什么，本卡零 adb 没有证据。
 */
class WakeUpExportDropShapeGuardTest {

    private companion object {
        const val EXPORTERS = "com/buaa/schedule/domain/schedule/ScheduleExporters.kt"
        const val TALLY = "com/buaa/schedule/domain/schedule/WakeUpDropTally.kt"
        const val COPY = "com/buaa/schedule/ui/settings/WakeUpExportCopy.kt"
        const val VIEW_MODEL = "com/buaa/schedule/ui/ScheduleViewModel.kt"

        const val TO_WAKE_UP = "fun toWakeUpJson("
        const val EXPORT_CALL = "fun exportWakeUpTo(uriString: String) {"
        const val TALLY_SIGNATURE = "fun tally(courses: List<Course>): WakeUpDropCounts {"

        /** 逐字节那三行（改前分别是 `?: 1` / `?: startWeek` / 空周次照样出场） */
        const val START_NODE_DROP = "val startNode = course.firstPeriodOrNull ?: return@mapNotNull null"
        const val START_WEEK_DROP = "val startWeek = weeks.firstOrNull() ?: return@mapNotNull null"
        const val END_WEEK_PLAIN = "val endWeek = weeks.last()"

        /**
         * 形状锚点：排序 → 丢掉 → 才拼字面量。
         *
         * T222（#307）在"排序"与"丢掉"之间插了一刀裁周次（`val sortedWeeks = course.weeks.sorted()` →
         * `TermWeekGate.weeksInsideTerm(...)` → `val weeks = ...`），那枚 `val weeks = course.weeks.sorted()`
         * 因此改不了名 —— 本锚点按**新形状**重钉，顺序判据一格没松（排序仍然排在丢弃与拼字面量之前）。
         */
        const val WEEKS_SORTED = "val sortedWeeks = course.weeks.sorted()"
        const val START_WEEK_FIELD = """"startWeek":${'$'}startWeek,"endWeek":${'$'}endWeek"""

        const val RETURN_DROP = "return@mapNotNull null"
        const val LAST_OR_NULL = "lastOrNull"
        const val WEEKS_IS_NOT_EMPTY = "weeks.isNotEmpty()"

        /** 判据两档：顺序即互斥（先节次后周次 ⇒ 两档都缺的那门只算进节次那一档） */
        const val DROP_NO_PERIOD = "course.periods.isEmpty() -> WakeUpDrop.NO_PERIOD"
        const val DROP_NO_WEEK = "course.weeks.isEmpty() -> WakeUpDrop.NO_WEEK"
        const val DROPPED_DERIVED = "val droppedCourses: Int get() = noPeriodCourses + noWeekCourses"
        const val ENUM_LINE = "enum class WakeUpDrop { NO_PERIOD, NO_WEEK }"

        /** VM 接线：账只从 tally 来，口径不许在 VM 里重拼一遍 */
        const val VM_TALLY_CALL = "WakeUpDropTally.tally(courses)"
        const val VM_MESSAGE_CALL = "wakeUpExportMessage(courses.size, drops.noPeriodCourses, drops.noWeekCourses)"
        const val VM_OLD_HAND_TALLY = "courses.count { it.periods.isEmpty() }"

        /** 文案四支（每支一条完整字面量，逐字节钉住；T224 起单位念「段排课」，形参名跟着改口） */
        const val COPY_PLACED = "val placed = totalFragments - noPeriodFragments - noWeekFragments"
        const val COPY_BRANCH_NONE = "noPeriodFragments == 0 && noWeekFragments == 0 -> \"WakeUp JSON 已导出（${'$'}placed 段排课）\""
        const val COPY_BRANCH_PERIOD = "noWeekFragments == 0 -> \"WakeUp JSON 已导出（${'$'}placed 段排课；另有 ${'$'}noPeriodFragments 段没有节次、无法定位，未写入）\""
        const val COPY_BRANCH_WEEK = "noPeriodFragments == 0 -> \"WakeUp JSON 已导出（${'$'}placed 段排课；另有 ${'$'}noWeekFragments 段没有周次、无法定位，未写入）\""
        const val COPY_BRANCH_BOTH = "else -> \"WakeUp JSON 已导出（${'$'}placed 段排课；另有 ${'$'}noPeriodFragments 段没有节次、${'$'}noWeekFragments 段没有周次，都无法定位，未写入）\""
        const val COPY_LITERAL_HEAD = "WakeUp JSON 已导出"

        /** T224 的单位词：这一句吃的三枚数全数在排课片段行上，「门」那一枚必须 0 处 */
        const val COPY_UNIT = "段排课"
        const val COPY_UNIT_DRIFT = "门课"
    }

    // ─────────────── ① 导出器：空周次那档真的走 return@mapNotNull null ───────────────

    @Test
    fun `导出器里那三行逐字节钉住`() {
        val body = balancedBlock(blankComments(source(EXPORTERS)), TO_WAKE_UP, "ScheduleExporters.toWakeUpJson")
        for (anchor in listOf(START_NODE_DROP, START_WEEK_DROP, END_WEEK_PLAIN)) {
            assertEquals(
                "toWakeUpJson 体内这一行必须逐字在场（恰好一枚）：「$anchor」。" +
                    "\n  实到枚数：" + occurrences(body, anchor) +
                    "\n  摘掉它 = 那一档又换成猜测值顶上（改前那枚 `?: 1` 就是这个形状）",
                1,
                occurrences(body, anchor),
            )
        }
        // 不可达支不许以另一种写法回来
        assertEquals("体内 `lastOrNull` 必须 0 处（`?: startWeek` 那枚不可达兜底拆掉了）", 0, occurrences(body, LAST_OR_NULL))
        assertEquals(
            "体内 `weeks.isNotEmpty()` 必须 0 处：startWeek 那行已判过非空，type 那两支再带一次就是死格",
            0,
            occurrences(body, WEEKS_IS_NOT_EMPTY),
        )
    }

    @Test
    fun `病灶那两枚字面量在代码里是零处_注释里逐字引用着`() {
        val stripped = blankComments(source(EXPORTERS))
        val raw = source(EXPORTERS)
        for (needle in listOf("?: 1", "?: startWeek")) {
            assertEquals(
                "ScheduleExporters.kt 抹注释后「$needle」必须 0 处（那是本卡的病灶，不是兜底）。" +
                    "\n  复算：把 $EXPORTERS 的 // 与块注释抹成空格后数「$needle」的枚数" +
                    "\n  实到枚数：" + occurrences(stripped, needle),
                0,
                occurrences(stripped, needle),
            )
            // 反套套逻辑：含注释的原文里这两枚各恰好 1 处（注释逐字引用病灶）⇒ 抹注释那一步不是白做的，
            // 而"grep 原文没匹配到"绝不能被当成"代码干净"
            assertEquals(
                "含注释的原文里「$needle」必须恰好 1 处（注释引用着它）。读到 0 = 注释被搬走，" +
                    "读到 >1 = 病灶回潮或被引用第二遍。实到：" + occurrences(raw, needle),
                1,
                occurrences(raw, needle),
            )
        }
    }

    @Test
    fun `两档丢课的枚数各是一枚`() {
        val stripped = blankComments(source(EXPORTERS))
        val body = balancedBlock(stripped, TO_WAKE_UP, "ScheduleExporters.toWakeUpJson")
        assertEquals(
            "toWakeUpJson 体内『$RETURN_DROP』恰好 2 枚：节次一枚（T27）、周次一枚（#237）。" +
                "读到 1 = 有一档又被换成猜测值；读到 3+ = 有人把别的判据也塞进同一枚 mapNotNull。" +
                "\n  实到枚数：" + occurrences(body, RETURN_DROP),
            2,
            occurrences(body, RETURN_DROP),
        )
        assertEquals(
            "整枚文件『$RETURN_DROP』恰好 3 枚：上面那两枚 + `toWeeklyText` 里" +
                "「这一天空课就整天不出场」那枚（同口径、与本卡无关，一并数清免得被当成漏网）。" +
                "\n  复算：grep -c 'return@mapNotNull null' " + EXPORTERS.replace("com/buaa/schedule/domain/schedule/", "app/src/main/java/com/buaa/schedule/domain/schedule/") +
                "（含注释原文 3 行）\n  实到枚数：" + occurrences(stripped, RETURN_DROP),
            3,
            occurrences(stripped, RETURN_DROP),
        )
    }

    @Test
    fun `空周次那档排在拼字面量之前`() {
        val body = balancedBlock(blankComments(source(EXPORTERS)), TO_WAKE_UP, "ScheduleExporters.toWakeUpJson")
        val sorted = body.indexOf(WEEKS_SORTED)
        val drop = body.indexOf(START_WEEK_DROP)
        val literal = body.indexOf(START_WEEK_FIELD)
        assertTrue(
            "顺序必须是「排序 → 丢掉 → 才拼字面量」：先排序 $sorted、再判空丢弃 $drop、最后拼 startWeek $literal。" +
                "写在拼字面量之后等于假周次已经进过字符串了",
            sorted in 1 until drop && drop < literal,
        )
        assertTrue(
            "endWeek 那行必须排在 startWeek 的丢弃之后（它靠那一行保证的非空）",
            drop < body.indexOf(END_WEEK_PLAIN) && body.indexOf(END_WEEK_PLAIN) < literal,
        )
    }

    // ─────────────── ② 判据内核：两档互斥、顺序即口径 ───────────────

    @Test
    fun `判据两档的先后与枚数`() {
        val code = blankComments(source(TALLY))
        val body = balancedBlock(code, TALLY_SIGNATURE, "WakeUpDropTally.tally")
        val atPeriod = code.indexOf(DROP_NO_PERIOD)
        val atWeek = code.indexOf(DROP_NO_WEEK)
        assertTrue("判据『$DROP_NO_PERIOD』逐字节一枚在场", atPeriod >= 0)
        assertTrue("判据『$DROP_NO_WEEK』逐字节一枚在场", atWeek >= 0)
        assertTrue(
            "节次那一档必须排在周次那一档之前（先节次后周次 ⇒ 两档都缺的门只落一档，报数不重复）。" +
                "实到 $atPeriod 与 $atWeek",
            atPeriod < atWeek,
        )
        assertEquals("『$DROP_NO_PERIOD』恰一枚", 1, occurrences(code, DROP_NO_PERIOD))
        assertEquals("『$DROP_NO_WEEK』恰一枚", 1, occurrences(code, DROP_NO_WEEK))
        assertEquals(
            "判据枚举逐字节一枚：`enum class WakeUpDrop { NO_PERIOD, NO_WEEK }`。第三档 = 又一个" +
                "『不进导出件却没人报数』的落点，要它就先补文案与格子",
            1,
            occurrences(code, ENUM_LINE),
        )
        assertEquals("枚举实际枚数必须是 2（跑在 JVM 侧的反证，不靠 grep）", 2, WakeUpDrop.entries.size)
        assertEquals(
            "droppedCourses 是同表派生值，必须写成类体属性（本仓口径，见 ImportWriteTally 同一格）",
            1,
            occurrences(code, DROPPED_DERIVED),
        )
        assertEquals("tally 的签名逐字一枚：全仓只有这一处数这两档", 1, occurrences(body, "groupingBy { wakeUpDrop(it) }.eachCount()"))
        val offenders = source(TALLY).lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("import ") }
            .filter { it.contains("android.") || it.contains("androidx.") }
            .toList()
        assertEquals("$TALLY 必须零 android / androidx import。实到：" + offenders, emptyList<String>(), offenders)
    }

    // ─────────────── ③ 调用方：报数只从 tally 来 ───────────────

    @Test
    fun `调用方那两个数取自同一本账`() {
        val code = blankComments(source(VIEW_MODEL))
        val body = balancedBlock(code, EXPORT_CALL, "ScheduleViewModel.exportWakeUpTo")
        assertEquals("exportWakeUpTo 体内必须接住 tally 交回的那本账（『$VM_TALLY_CALL』）", 1, occurrences(body, VM_TALLY_CALL))
        assertEquals("文案在体内恰好一枚调用点，且三个数都从 courses / drops 来", 1, occurrences(body, VM_MESSAGE_CALL))
        assertEquals(
            "改前那枚『只数没节次那一档』的手拼口径必须 0 处：口径有两处写死就会漂（导出器改了 VM 没改）。" +
                "\n  实到枚数：" + occurrences(body, VM_OLD_HAND_TALLY),
            0,
            occurrences(body, VM_OLD_HAND_TALLY),
        )
        assertEquals(
            "全仓『$COPY_LITERAL_HEAD』的字面枚数必须是 0（句子只有一处真相，在 $COPY）。" +
                "搬回 VM = 把它移出 JVM 单测可达的范围",
            0,
            occurrences(code, COPY_LITERAL_HEAD),
        )
    }

    // ─────────────── ④ 文案：四支各自一条完整字面量、各跟着自己那一档 ───────────────

    @Test
    fun `文案那四支逐字节钉住`() {
        val code = blankComments(source(COPY))
        for (anchor in listOf(COPY_BRANCH_NONE, COPY_BRANCH_PERIOD, COPY_BRANCH_WEEK, COPY_BRANCH_BOTH)) {
            assertEquals(
                "文案这一支必须逐字在场（恰好一枚）：「$anchor」。" +
                    "\n  实到枚数：" + occurrences(code, anchor) +
                    "\n  摘掉任何一支 = 那一档的『未写入』又没人念；改写成拼接链 = T181② 那枚缺右括号的形状",
                1,
                occurrences(code, anchor),
            )
        }
        assertEquals(
            "『$COPY_LITERAL_HEAD』在文案文件里恰好 4 枚（无缺失 / 只缺节次 / 只缺周次 / 两档都有）",
            4,
            occurrences(code, COPY_LITERAL_HEAD),
        )
        assertEquals(
            "placed 必须是总数减两档（两档互斥，所以减两次不会把同一门减两遍）",
            1,
            occurrences(code, COPY_PLACED),
        )
        assertEquals(
            "『没有节次』那半句只许长在节次那一支与两档那一支里（共 2 枚）。读到 3 = 有人又加了一本册子；" +
                "读到 0 = 注释里那几句被当成代码（本守卫抹注释那一步没做到）。实到：" + occurrences(code, "没有节次"),
            2,
            occurrences(code, "没有节次"),
        )
        assertEquals(
            "『没有周次』那半句只许长在周次那一支与两档那一支里（共 2 枚）。实到：" + occurrences(code, "没有周次"),
            2,
            occurrences(code, "没有周次"),
        )
        // T224：单位词这一格钉的是「门 / 段」这一族的回潮（三枚数全数在排课片段行上）
        assertEquals(
            "『段排课』在代码里恰好 4 枚（四支各一枚，枚住的正是「写出去的段数」那一格）。" +
                "读到 <4 = 某一支又漂回旧单位；实到：" + occurrences(code, COPY_UNIT),
            4,
            occurrences(code, COPY_UNIT),
        )
        assertEquals(
            "**钉法 = 数出现次数（抹注释后）**：代码里一枚「门课」都不许有 —— 这一句吃的数是片段行枚数，" +
                "念「门」就与统计页那句「N 门课 · M 段排课」两把尺（T224 第四处）。\n" +
                "复算：把 " + COPY + " 的注释抹成空格后 grep -c '门课'（应为 0；含注释的原文里它还在，" +
                "那是 KDoc 抄的改前原文）\n  实到枚数：" + occurrences(code, COPY_UNIT_DRIFT) +
                "；含注释原文：" + occurrences(source(COPY), COPY_UNIT_DRIFT),
            0,
            occurrences(code, COPY_UNIT_DRIFT),
        )
        assertTrue(
            "反套套逻辑：改前那两句「$COPY_UNIT_DRIFT」必须在**注释**里留痕（KDoc 讲的就是旧单位）。" +
                "读到 0 说明下面那枚 0 是「注释被删干净」而不是「代码改对了」。实到：" +
                occurrences(source(COPY), COPY_UNIT_DRIFT),
            occurrences(source(COPY), COPY_UNIT_DRIFT) >= 1,
        )
    }

    // ---------------- helpers（与 ImportCompletionSkippedCountGuardTest 同写法） ----------------

    private fun source(relative: String): String = File(findMainJavaDir(), relative).readText()

    private fun occurrences(hay: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var at = hay.indexOf(needle)
        while (at >= 0) {
            count++
            at = hay.indexOf(needle, at + needle.length)
        }
        return count
    }

    /** 从锚点那一句起按花括号配平取整块（含锚点自己）；锚点找不到就抛 */
    private fun balancedBlock(code: String, anchor: String, label: String): String {
        val at = code.indexOf(anchor)
        assertTrue("$label 的锚点没找到（改名/挪家要回来重钉本守卫）：「$anchor」", at >= 0)
        val open = code.indexOf('{', at)
        assertTrue("$label 找不到函数体的开括号", open >= 0)
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(at, i + 1)
                }
            }
        }
        throw IllegalStateException("$label 的花括号没配平")
    }

    /** 把 `//` 与块注释抹成空格（字符串保留、长度与换行位置不变）：钉的是接线，不是白话 */
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
}
