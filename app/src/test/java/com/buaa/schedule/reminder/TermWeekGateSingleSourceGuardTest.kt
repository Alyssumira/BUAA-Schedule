package com.buaa.schedule.reminder

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T214 病灶 A（#263）的源码形状守卫：排铃两条链是否**真的**读同一枚「这一周在不在学期内」的判据，
 * 而且那枚判据是否与展示侧同源。纯 JVM 源码核对（无 Robolectric、无设备、不读时钟）。
 *
 * 读法与小工具抄 `ui/course/CourseGroupPrimaryFragmentGuardTest` 与
 * `reminder/CountdownOwnershipAnchorGuardTest`（抹注释保字面量 + 花括号配平取块 + 原文切片不吃行号）。
 * 本守卫读 `app/src/main/java` 下的源码文本 —— 那一条已在 `app/build.gradle.kts` 的
 * `guardReadWorkingTreeFiles` 里整目录进 `tasks.withType<Test>()` 的 `inputs`（T92①，台账 #136）。
 *
 * ## 四家钉法逐格点名（卡面第 2 条要求）
 *
 * 1. [theKernelHoldsNoSecondCopyOfTheBound] —— **逐字节**（委托那一行原文）+ **读形状**（内核体里
 *    零比较式、零时钟、零库）+ **数出现次数**（`week <= 0 || week > totalWeeks` 全仓恰一处，在 WeekCalculator）
 *    + **抹注释找锚点**（第 ⑥ 层给探测器摆改前那一形）；
 * 2. [bothChainsAskTheSameGate] —— **逐字节 + 数出现次数**（两条链各含同一枚判据语句恰一处）、
 *    **读形状 + 顺序**（判据排在 `plusWeeks` 之前：先判再算日期）、**数出现次数（全 main 册子）**；
 * 3. [alarmChainsNeverTakeTheUnboundedDefault] —— **数出现次数**（`UNBOUNDED_TOTAL_WEEKS` 全仓恰两处、
 *    都在 ClassProgressScheduler：1 定义 + 1 默认值；ReminderScheduler 恰 0 处）+
 *    **逐字节**（rescheduleWindows 那一格是无默认值的必填参数）+ **读形状**（两处调用块里必须有 `totalWeeks =`）；
 * 4. [displaySideResidueIsNamedNotHidden] —— **数出现次数（全 main 册子 + 读形状取实参）**：
 *    `planNextClassWindow(` 的调用恰五处，带 bound 的两处都在 reminder/ 那两条链上，
 *    不判上界的三处逐枚点名（跨卡那一格的账，不许藏着）；
 * 5. [semesterRowIsTheOnlySourceOfTheBound] —— **逐字节**（`val totalWeeks = semester?.totalWeeks` 恰一处、
 *    接线传 `totalWeeks = semester.totalWeeks,`、缝上那一格是 `val totalWeeks: Int?,`）+ **数出现次数**；
 * 6. [probeRecognisesThePreFixShape] —— **抹注释找锚点**：改前那几形喂进同一把尺各数到该数的值，
 *    写在 KDoc 里的引用抹完必须 0 处（防「锚点拼错的假绿」）。
 */
class TermWeekGateSingleSourceGuardTest {

    // ─────────────── ① 内核里没有第二份上界比较式，也不读钟 ───────────────

    @Test
    fun theKernelHoldsNoSecondCopyOfTheBound() {
        val code = withoutComments(readMainSource(SCHEDULER_FILE))
        val kernel = balancedBlock(code, "object TermWeekGate {")

        assertEquals(
            "**钉法 = 逐字节**：判据本体必须把那一周的周一喂回展示侧那把尺，并且只认「映射回来的周号就是这一周」。\n" +
                "复算：grep -Fn '$DELEGATION_LINE' app/src/main/java/$SCHEDULER_FILE\n" +
                "红了 = 有人把判据改成自己比较 week 与 totalWeeks（第二份写法），本卡要的正是「比较式全仓只有一处」",
            1,
            occurrences(kernel, DELEGATION_LINE),
        )
        assertEquals(
            "**钉法 = 逐字节**：那一周的周一只能取自 SemesterWeekDates（展示侧翻周那一份），" +
                "不许在判据里再算一遍 plusWeeks。\n复算：grep -Fn '$MONDAY_LINE' app/src/main/java/$SCHEDULER_FILE",
            1,
            occurrences(kernel, MONDAY_LINE),
        )

        // `<= totalWeeks` 与 `> totalWeeks` 两枚都要钉：第二份上界比较式的两种写法
        // （`week <= totalWeeks` 与 `week > totalWeeks` 取反）只差一枚指针，少钉一枚就是放走其中一种写法
        for (needle in listOf("> totalWeeks", "<= totalWeeks", "<= 0", "in 1..", "coerceIn", "currentWeek(", "totalWeeks >")) {
            assertEquals(
                "**钉法 = 读形状**：内核体里「$needle」必须 0 处 —— 一旦长出第二份上界或下界比较式，" +
                    "就是本仓那族「同一判据两份写法各错各的」的又一处（本卡正在收它）。实到 " +
                    occurrences(kernel, needle) + " 处",
                0,
                occurrences(kernel, needle),
            )
        }
        // 零时钟：currentWeekOrNull 的 today 形参带着一枚 LocalDate.now() 默认值，判据必须显式递日期
        for (clock in listOf("LocalDate.now(", "System.currentTimeMillis(", "Instant.", "Clock.", "Date(")) {
            assertEquals(
                "**钉法 = 读形状**：判据里自己读钟（$clock）就与同文件 KDoc 承诺的纯 JVM 内核自相矛盾" +
                    "（台账 #265 那一族），排铃也变成「今天几点」的函数。实到 " + occurrences(kernel, clock) + " 处",
                0,
                occurrences(kernel, clock),
            )
        }
        assertFalse(
            "**钉法 = 读形状**：内核不许读库（Context / Dao / repository 任一出现就不再是纯判据）",
            listOf("Context", "Dao", "repository").any { kernel.contains(it) },
        )

        val weekCalculator = withoutComments(readMainSource(WEEK_CALCULATOR_FILE))
        assertEquals(
            "**钉法 = 数出现次数**：比较式「$BOUND_EXPRESSION」全 app/src/main 只许长在 WeekCalculator 那一处" +
                "（全仓实到 " + mainOccurrences(BOUND_EXPRESSION) + " 处；WeekCalculator 内 " +
                occurrences(weekCalculator, BOUND_EXPRESSION) + " 处）。" +
                "这就是「判据与展示侧同源」的静态面：排铃读的是同一把尺，不是抄下来的第二把",
            1,
            mainOccurrences(BOUND_EXPRESSION),
        )
    }

    // ─────────────── ② 两条链各问同一枚判据，且排在算日期之前 ───────────────

    @Test
    fun bothChainsAskTheSameGate() {
        val reminder = withoutComments(readMainSource(REMINDER_FILE))
        val progress = withoutComments(readMainSource(SCHEDULER_FILE))

        for ((name, file) in listOf("ReminderScheduler" to REMINDER_FILE, "ClassProgressScheduler" to SCHEDULER_FILE)) {
            val code = withoutComments(readMainSource(file))
            assertEquals(
                "**钉法 = 逐字节 + 数出现次数**：$name 的周次循环里这道判据语句必须恰好一处" +
                    "（两处 = 同一条链上把判据写了两遍；0 处 = 这条链又不吃 totalWeeks 了）。实到 " +
                    occurrences(code, GATE_STATEMENT) + " 处\n" +
                    "复算：grep -Fn '$GATE_STATEMENT' app/src/main/java/$file",
                1,
                occurrences(code, GATE_STATEMENT),
            )
        }

        // 顺序：判据必须排在 plusWeeks 之前（先生成日期再判，就是「已经排完才想起来收」）
        val loops = listOf(
            "nextOccurrence" to balancedBlock(reminder, "private fun nextOccurrence("),
            "nextOrCurrentWindow" to balancedBlock(progress, "private fun nextOrCurrentWindow("),
        )
        for ((name, body) in loops) {
            val gate = body.indexOf(GATE_STATEMENT)
            val date = body.indexOf("semesterStart.plusWeeks(")
            assertTrue("$name 里找不到那道判据（循环没收进判据）：\n$body", gate >= 0)
            assertTrue("$name 里找不到日期那一行（次序判据失去对象）：\n$body", date >= 0)
            assertTrue(
                "**钉法 = 读形状 + 顺序**：$name 必须先判在不在学期内、再生成日历日期" +
                    "（判据 index=$gate 必须早于 plusWeeks index=$date）",
                gate < date,
            )
        }

        val register = mainSources()
            .map { it.relative to occurrences(withoutComments(it.text), "isInTermWeek(") }
            .filter { it.second > 0 }
            .sortedBy { it.first }
            .joinToString(separator = "") { "  ${it.first}: ${it.second} 处\n" }
        assertEquals(
            "**钉法 = 数出现次数（全 main 册子）**：isInTermWeek 全仓只许三处 = 1 定义（ClassProgressScheduler）" +
                "+ 2 调用点（两条排铃链各一枚）。第四处 = 又长出一条链或第二份门面；少一处 = 有条链又不判了。\n" +
                "实到册子：\n$register",
            "  $SCHEDULER_FILE: 2 处\n  $REMINDER_FILE: 1 处\n",
            register,
        )
    }

    // ─────────────── ③ 排铃两条链不许拿「不判上界」当默认值 ───────────────

    @Test
    fun alarmChainsNeverTakeTheUnboundedDefault() {
        val progress = withoutComments(readMainSource(SCHEDULER_FILE))
        val reminder = withoutComments(readMainSource(REMINDER_FILE))

        assertEquals(
            "**钉法 = 逐字节**：不判上界那一档全仓只许有一枚声明（`const val UNBOUNDED_TOTAL_WEEKS`）" +
                "加一处默认值挂法，实到声明 " + occurrences(progress, UNBOUNDED_CONST_DECLARATION) +
                " 处、挂法 " + occurrences(progress, UNBOUNDED_DECLARATION) + " 处",
            1,
            occurrences(progress, UNBOUNDED_CONST_DECLARATION),
        )
        assertEquals(
            "**钉法 = 数出现次数**：UNBOUNDED_TOTAL_WEEKS 全 app/src/main 恰两处（1 定义 + 1 默认值），" +
                "实到 " + mainOccurrences("UNBOUNDED_TOTAL_WEEKS") + " 处",
            2,
            mainOccurrences("UNBOUNDED_TOTAL_WEEKS"),
        )
        assertEquals(
            "**钉法 = 数出现次数**：课前提醒那条链（ReminderScheduler.kt）里 UNBOUNDED_TOTAL_WEEKS 必须 0 处 ——" +
                "它拿不到学期行时传的是 null（不放行），不是不判。实到 " + occurrences(reminder, "UNBOUNDED_TOTAL_WEEKS") + " 处",
            0,
            occurrences(reminder, "UNBOUNDED_TOTAL_WEEKS"),
        )

        for ((name, call) in listOf(
            "rescheduleWindows" to balancedBlock(progress, "        val window = planNextClassWindow("),
            "shouldTakeDownClassProgress" to balancedBlock(reminder, "        val window = ClassProgressScheduler.planNextClassWindow("),
        )) {
            assertTrue(
                "**钉法 = 读形状**：排铃链的这两处 planNextClassWindow 调用必须自己递 totalWeeks" +
                    "（少一处就是吃默认值，也就是把病灶搬回调用点）。来自 $name：\n$call",
                call.contains("totalWeeks = totalWeeks,"),
            )
        }

        val reschedule = balancedBlock(progress, "fun rescheduleWindows(")
        assertTrue(
            "**钉法 = 读形状 + 逐字节**：课堂铃链 rescheduleWindows 的 totalWeeks 必须出现在签名里且**没有默认值**" +
                "（挂上默认值就是把「屏上画不到的那一周照样排铃」写成参数表里的常态）：\n" + reschedule.take(400),
            reschedule.contains(RESCHEDULE_BOUND_PARAM_LINE),
        )
        assertFalse(
            "**钉法 = 读形状**：rescheduleWindows 的必填参数被挂了默认值：\n" + reschedule.take(400),
            reschedule.contains("totalWeeks: Int? ="),
        )
    }

    // ─────────────── ④ 展示侧残留逐枚点名（跨卡那一格不许藏着） ───────────────

    @Test
    fun displaySideResidueIsNamedNotHidden() {
        val calls = mutableListOf<String>()
        for (file in mainSources()) {
            val code = withoutComments(file.text)
            var from = 0
            while (true) {
                val at = code.indexOf(PLAN_CALL, from)
                if (at < 0) break
                from = at + PLAN_CALL.length
                val lineStart = code.lastIndexOf('\n', at) + 1
                if (code.substring(lineStart, at).contains("fun ")) continue // 定义那一句不算调用
                val close = matchingParen(code, code.indexOf('(', at))
                    ?: error("planNextClassWindow 调用的括号没配平：${file.relative}")
                val args = code.substring(at + PLAN_CALL.length, close)
                val verdict = if (args.contains("totalWeeks")) WITH_BOUND else WITHOUT_BOUND
                calls += "${file.relative} -> $verdict"
            }
        }
        val register = calls.sorted().joinToString(separator = "\n")
        assertEquals(
            "**钉法 = 数出现次数（全 main 册子 + 读形状取实参）**：planNextClassWindow 的调用点恰五处 —— " +
                "带 bound 的两处必须都在 reminder/ 那两条链上（课堂铃链 rescheduleWindows、勿扰判据 " +
                "shouldTakeDownClassProgress），不判上界的三处是展示侧的历史包袱：WidgetCommon 两枚「下一节课」" +
                "＋ LiveClassResyncer 一枚实况重挂。它们要收得动本卡白名单外的文件 ⇒ 已写进回执「需要跨卡配合」，" +
                "不许在这里顺手改（顺手改会把展示行为一起挪走）。\n实到册子：\n$register",
            listOf(
                "$LIVE_RESYNCER_FILE -> $WITHOUT_BOUND",
                "$SCHEDULER_FILE -> $WITH_BOUND",
                "$REMINDER_FILE -> $WITH_BOUND",
                "$WIDGET_FILE -> $WITHOUT_BOUND",
                "$WIDGET_FILE -> $WITHOUT_BOUND",
            ).sorted().joinToString(separator = "\n"),
            register,
        )
    }

    // ─────────────── ⑤ 学期行是 bound 的唯一出处 ───────────────

    @Test
    fun semesterRowIsTheOnlySourceOfTheBound() {
        val reminder = withoutComments(readMainSource(REMINDER_FILE))
        val progress = withoutComments(readMainSource(SCHEDULER_FILE))

        assertEquals(
            "**钉法 = 逐字节**：整轮重排的那枚 totalWeeks 只能从学期行取一次，实到 " +
                occurrences(reminder, SEMESTER_BOUND_LINE) + " 处。两处 = 同一枚数各读各的；" +
                "0 处 = 递进判据的是别的东西（例如硬写的周数，台账 #236 那族 `?: 20`）",
            1,
            occurrences(reminder, SEMESTER_BOUND_LINE),
        )
        assertEquals(
            "**钉法 = 数出现次数**：ReminderScheduler 把这一枚数往下透传恰四处" +
                "（`rescheduleAll` → `takeDownClassProgressIfNeeded`、`rescheduleAll` → " +
                "`ClassProgressScheduler.rescheduleWindows`、`takeDownClassProgressIfNeeded` → " +
                "`shouldTakeDownClassProgress`、`shouldTakeDownClassProgress` → " +
                "`ClassProgressScheduler.planNextClassWindow` —— 勿扰那条是两跳，所以是四枚而不是三枚），" +
                "实到 " + occurrences(reminder, "totalWeeks = totalWeeks,") + " 处。" +
                "多一处 = 又长出一条不读学期行的排铃路；少一处 = 有一条链改回吃默认值了",
            4,
            occurrences(reminder, "totalWeeks = totalWeeks,"),
        )
        assertEquals(
            "**钉法 = 逐字节**：课前提醒的内核调用必须把 bound 递给 nextOccurrence（位置实参那一手），实到 " +
                occurrences(reminder, NEXT_OCCURRENCE_CALL) + " 处",
            1,
            occurrences(reminder, NEXT_OCCURRENCE_CALL),
        )
        assertEquals(
            "**钉法 = 逐字节**：rescheduleNextWindow 的生产接线传 `totalWeeks = semester.totalWeeks,`，实到 " +
                occurrences(progress, WIRING_LINE) + " 处",
            1,
            occurrences(progress, WIRING_LINE),
        )
        assertEquals(
            "**钉法 = 读形状**：续排缝上那一格必须是可空的 `val totalWeeks: Int?,`（判不出 ⇒ 不放行那一档" +
                "要在缝上摆得出来），不许写成 `Int = 20` 那种没出处的假周数（台账 #236）。实到 " +
                occurrences(progress, INPUT_FIELD_LINE) + " 处",
            1,
            occurrences(progress, INPUT_FIELD_LINE),
        )
    }

    // ─────────────── ⑥ 探测器自检：认得改前那一形 ───────────────

    /**
     * 只报「锚点几处」而锚点其实拼错，等于没有守卫。两个方向各一头：
     * - **朝松**：改前那几形（链上没判据、判据自己写比较式、签名里挂默认值）喂进同一把尺必须数到该数的值；
     * - **朝窄**：同一句写在 KDoc 里、抹完注释必须 0 处 —— main 与本文件的 KDoc 都逐字登记着那些形状。
     */
    @Test
    fun probeRecognisesThePreFixShape() {
        val preFixLoop = """
            for (week in course.weeks.sorted()) {
                val date = semesterStart.plusWeeks((week - 1).toLong())
                    .plusDays((course.dayOfWeek - 1).toLong())
                for (segment in segments) { }
            }
        """.trimIndent()
        assertEquals(
            "探测器必须抓得出改前那条循环（判据 0 处才算第 ② 层那句「恰一处」不是空锚点；实到 " +
                occurrences(preFixLoop, GATE_STATEMENT) + " 处）",
            0,
            occurrences(preFixLoop, GATE_STATEMENT),
        )
        assertEquals(
            "改前那条循环里日期照生成（plusWeeks 一处），第 ② 层的次序判据才有对照物",
            1,
            occurrences(preFixLoop, "semesterStart.plusWeeks("),
        )

        // 第二份上界比较式的**那一形今天长什么样**：`week <= totalWeeks`（下界另有写法）。
        // 探测器第 ① 层的负针里有 `<= totalWeeks` 与 `> totalWeeks` 两枚，这里两头各喂一形 ——
        // 只喂 `> totalWeeks` 那一形而拿 `<= totalWeeks` 去数是本层原先的红臂（锚点与样例对错形）。
        val secondCopyShape = "fun inTerm(week: Int, totalWeeks: Int): Boolean = week > 0 && week <= totalWeeks"
        assertEquals(
            "探测器必须抓得出「链上自己写比较式」那一形（「<= totalWeeks」数到 1 处；实到 " +
                occurrences(secondCopyShape, "<= totalWeeks") + " 处）—— 第 ① 层那句「0 处」的对照物",
            1,
            occurrences(secondCopyShape, "<= totalWeeks"),
        )
        val upstreamCopy = "if (week <= 0 || week > totalWeeks) return null"
        assertEquals(
            "探测器必须同样抓得出上界取反那一形（「> totalWeeks」数到 1 处；实到 " +
                occurrences(upstreamCopy, "> totalWeeks") + " 处）—— 它与展示侧 WeekCalculator 原文同形，" +
                "第 ① 层那枚「全仓恰一处 BOUND_EXPRESSION」靠的就是它",
            1,
            occurrences(upstreamCopy, "> totalWeeks"),
        )

        val defaulted = "fun plan(courses: List<Int>, totalWeeks: Int? = TermWeekGate.UNBOUNDED_TOTAL_WEEKS) = Unit"
        assertEquals(
            "探测器必须抓得出「排铃签名里挂默认值」那一形（实到 " + occurrences(defaulted, UNBOUNDED_DECLARATION) +
                " 处）：第 ③ 层那两把尺靠的就是它",
            1,
            occurrences(defaulted, UNBOUNDED_DECLARATION),
        )

        val quotedInComment = """
            /**
             * 旧形状登记：if (week <= 0 || week > totalWeeks) return null 这一形今天在排铃判据里也写过一份。
             */
            fun noop() = Unit
        """.trimIndent()
        assertEquals(
            "注释里逐字引用的那一形**不许**进第 ① 层的账：未抹注释时它确实在原文里（实到 " +
                occurrences(quotedInComment, BOUND_EXPRESSION) + " 处），抹完必须 0 处（实到 " +
                occurrences(withoutComments(quotedInComment), BOUND_EXPRESSION) + " 处）",
            0,
            occurrences(withoutComments(quotedInComment), BOUND_EXPRESSION),
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
            SourceFile(root.toPath().relativize(it.toPath()).toString().replace(File.separatorChar, '/'), it.readText().replace("\r\n", "\n"))
        }
    }

    private fun mainOccurrences(needle: String): Int = mainSources().sumOf { occurrences(withoutComments(it.text), needle) }

    /** 按花括号配平从签名起取整段（含签名）；签名里不许出现 lambda 默认值，见 ClassProgressScheduler 顶层那三枚属性 */
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

    private fun matchingParen(source: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            if (depth == 0) return i
            i++
        }
        return null
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

    /** 只抹注释、保留字符串字面量：main 与本文件的 KDoc 都逐字写着那些病灶形状，不抹就会把散文当证据 */
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
            val hit = listOf("src/main/java", "app/src/main/java").map { File(dir, it) }.firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }

    private companion object {
        const val SCHEDULER_FILE = "com/buaa/schedule/reminder/ClassProgressScheduler.kt"
        const val REMINDER_FILE = "com/buaa/schedule/reminder/ReminderScheduler.kt"
        const val WEEK_CALCULATOR_FILE = "com/buaa/schedule/domain/schedule/WeekCalculator.kt"
        const val LIVE_RESYNCER_FILE = "com/buaa/schedule/reminder/LiveClassResyncer.kt"
        const val WIDGET_FILE = "com/buaa/schedule/widget/WidgetCommon.kt"

        // ① 内核本体
        const val DELEGATION_LINE = "WeekCalculator.currentWeekOrNull(start, bound, weekMonday) == week"
        const val MONDAY_LINE = "val weekMonday = SemesterWeekDates.mondayOf(start, week) ?: return false"
        const val BOUND_EXPRESSION = "week <= 0 || week > totalWeeks"

        // ② 两条链共用的那一枚判据语句
        const val GATE_STATEMENT = "if (!TermWeekGate.isInTermWeek(week, semesterStart, totalWeeks)) continue"

        // ③ 不判上界那一档
        const val UNBOUNDED_CONST_DECLARATION = "const val UNBOUNDED_TOTAL_WEEKS"
        const val UNBOUNDED_DECLARATION = "totalWeeks: Int? = TermWeekGate.UNBOUNDED_TOTAL_WEEKS"
        const val RESCHEDULE_BOUND_PARAM_LINE = "totalWeeks: Int?,"

        // ④ 展示侧册子
        const val PLAN_CALL = "planNextClassWindow("
        const val WITH_BOUND = "带 bound（排铃链）"
        const val WITHOUT_BOUND = "不判上界（跨卡那一格）"

        // ⑤ 学期行那一枚出处 + 缝上那一格 + 内核的位置实参
        const val SEMESTER_BOUND_LINE = "val totalWeeks = semester?.totalWeeks"
        const val NEXT_OCCURRENCE_CALL = "nextOccurrence(course, semesterStart, totalWeeks, slots, gapMinutes, now)"
        const val WIRING_LINE = "totalWeeks = semester.totalWeeks,"
        const val INPUT_FIELD_LINE = "val totalWeeks: Int?,"
    }
}
