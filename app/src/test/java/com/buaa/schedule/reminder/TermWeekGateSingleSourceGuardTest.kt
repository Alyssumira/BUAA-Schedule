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
 *    `planNextClassWindow(` 的调用恰五处，带 bound 的三处都在 `reminder/`（课堂铃链、勿扰判据、
 *    前台校准），不判上界的只剩展示侧那一处宿主的两枚「下一节课」（T227 #332 把实况重挂那一枚
 *    从这一格里摘出去了 ⇒ 这本册子跟着变窄，不是变松）；
 * 5. [semesterRowIsTheOnlySourceOfTheBound] —— **逐字节**（`val totalWeeks = semester?.totalWeeks` 恰一处、
 *    接线传 `totalWeeks = semester.totalWeeks,`、缝上那一格是 `val totalWeeks: Int?,`）+ **数出现次数**；
 * 6. [probeRecognisesThePreFixShape] —— **抹注释找锚点**：改前那几形喂进同一把尺各数到该数的值，
 *    写在 KDoc 里的引用抹完必须 0 处（防「锚点拼错的假绿」）；
 * 7. 第 ⑦ 层（`alarmChainsAndTheConflictAndExportSidesResolveToOneSymbol`）钉搬家后只有一枚符号；
 * 8. [everyCallSiteInsideReminderPassesTheTermBound] —— **数出现次数（按目录，抹注释）+ 读形状**：
 *    `reminder/` 目录里 `planNextClassWindow(` 的调用块**全部**带 bound，且该目录里
 *    `UNBOUNDED_TOTAL_WEEKS` 的枚数恰等于默认值声明那一枚（= 没有一处调用点拿它当实参）。
 *    这一层是 T227 #332 的加严：第 ③ 层原先只点名两枚文件，实况重挂那枚"会排闹钟却吃默认值"
 *    的调用点因此被第 ④ 层写死成"展示侧历史包袱"而一直绿着。
 */
class TermWeekGateSingleSourceGuardTest {

    // ─────────────── ① 内核里没有第二份上界比较式，也不读钟 ───────────────

    @Test
    fun theKernelHoldsNoSecondCopyOfTheBound() {
        // 宿主挪了家（T222 第 ① 步「内核搬家」）：`object TermWeekGate` 从 reminder/ClassProgressScheduler.kt
        // 搬进 domain/schedule/TermWeekGate.kt —— 本层那一格逐字没改，只把**读哪一枚文件**跟着挪。
        val kernel = balancedBlock(withoutComments(readMainSource(KERNEL_FILE)), "object TermWeekGate {")

        assertEquals(
            "**钉法 = 逐字节**：判据本体必须把那一周的周一喂回展示侧那把尺，并且只认「映射回来的周号就是这一周」。\n" +
                "复算：grep -Fn '$DELEGATION_LINE' app/src/main/java/$KERNEL_FILE\n" +
                "红了 = 有人把判据改成自己比较 week 与 totalWeeks（第二份写法），本卡要的正是「比较式全仓只有一处」",
            1,
            occurrences(kernel, DELEGATION_LINE),
        )
        assertEquals(
            "**钉法 = 逐字节**：那一周的周一只能取自 SemesterWeekDates（展示侧翻周那一份），" +
                "不许在判据里再算一遍 plusWeeks。\n复算：grep -Fn '$MONDAY_LINE' app/src/main/java/$KERNEL_FILE",
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
            "**钉法 = 数出现次数（全 main 册子）**：`isInTermWeek(` 全仓只许这五处 —— " +
                "1 定义 + 1 内核里周次表入口的逐周委托（都在 $KERNEL_FILE）" +
                "+ 2 调用点（两条排铃链各一枚）+ 1 调用点（ICS 与系统日历共用的课次展开，T222 #307）。" +
                "少一处 = 有条链又不判学期了；册子里长出第六处 = 又一条链挂进来（或是第二份门面）。" +
                "**冲突侧走的是周次表那一枚入口（`weeksInsideTerm(`），所以它不在这把尺的账上，" +
                "由第 ⑦ 层与 domain 那枚并尺守卫逐枚点名。**\n实到册子：\n$register",
            listOf(
                "  $OCCURRENCES_FILE: 1 处",
                "  $KERNEL_FILE: 2 处",
                "  $SCHEDULER_FILE: 1 处",
                "  $REMINDER_FILE: 1 处",
            ).joinToString(separator = "\n") + "\n",
            register,
        )
    }

    // ─────────────── ③ 排铃两条链不许拿「不判上界」当默认值 ───────────────

    @Test
    fun alarmChainsNeverTakeTheUnboundedDefault() {
        val progress = withoutComments(readMainSource(SCHEDULER_FILE))
        val reminder = withoutComments(readMainSource(REMINDER_FILE))

        val kernel = withoutComments(readMainSource(KERNEL_FILE))
        assertEquals(
            "**钉法 = 逐字节**：不判上界那一档全仓只许有一枚声明（`const val UNBOUNDED_TOTAL_WEEKS`）" +
                "加一处默认值挂法；声明跟着内核搬进了 $KERNEL_FILE（T222 第 ① 步），挂法仍在 $SCHEDULER_FILE。" +
                "实到声明 " + occurrences(kernel, UNBOUNDED_CONST_DECLARATION) +
                " 处、挂法 " + occurrences(progress, UNBOUNDED_DECLARATION) + " 处",
            1,
            occurrences(kernel, UNBOUNDED_CONST_DECLARATION),
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
        val register = planCallLedger().joinToString(separator = "\n")
        assertEquals(
            "**钉法 = 数出现次数（全 main 册子 + 读形状取实参）**：planNextClassWindow 的调用点恰五处 —— " +
                "带 bound 的三处必须都在 `reminder/`（课堂铃链 rescheduleWindows、勿扰判据 " +
                "shouldTakeDownClassProgress、前台校准 LiveClassResyncer.nextWindow：那一枚会补排" +
                "下课铃，所以它从来不是「展示侧」，T214 把它记错档、T227 #332 收的就是这一格）。" +
                "不判上界的只剩展示侧那一枚宿主的两处「下一节课」（`widget/WidgetCommon.kt`）：" +
                "它们要收得动本卡白名单外的文件 ⇒ 已写进回执「需要跨卡配合」，" +
                "不许在这里顺手改（顺手改会把展示行为一起挪走）。\n实到册子：\n$register",
            listOf(
                "$SCHEDULER_FILE -> $WITH_BOUND",
                "$REMINDER_FILE -> $WITH_BOUND",
                "$LIVE_RESYNCER_FILE -> $WITH_BOUND",
                "$WIDGET_FILE -> $WITHOUT_BOUND",
                "$WIDGET_FILE -> $WITHOUT_BOUND",
            ).sorted().joinToString(separator = "\n"),
            register,
        )
    }

    // ─────────────── ⑧ reminder/ 整个目录不许有"不判上界"的调用点（T227 加严） ───────────────

    /**
     * 第 ③ 层点名两枚文件、第 ④ 层数全仓五处，两把尺都放走了「第三枚会排闹钟的调用点」：
     * `reminder/LiveClassResyncer.kt` 挑窗口时吃默认值，`startLive` 却照样 `scheduleEnd`
     * 补一枚下课铃，而那枚铃到点撤的是全仓唯一那枚课堂实况通知 id 并恢复勿扰（#332）。
     * 这一层因此**按目录**数，不认文件名单：`reminder/` 里凡是排铃/挂勿扰/挂实况的调用点
     * 一律不许拿"不判上界"那一档当实参，也不许在实参位上出现 UNBOUNDED。
     */
    @Test
    fun everyCallSiteInsideReminderPassesTheTermBound() {
        val reminderDir = mainSources().filter { it.relative.startsWith(REMINDER_DIR) }
        assertTrue("`reminder/` 下一枚 .kt 都没读到，路径不对：${reminderDir.size}", reminderDir.isNotEmpty())

        val ledger = planCallLedger(REMINDER_DIR).joinToString(separator = "\n")
        assertEquals(
            "**钉法 = 数出现次数（按目录）+ 读形状**：`reminder/` 目录里 planNextClassWindow 的调用块" +
                "必须恰三处且**全部带 bound**（不判上界 0 处）。少一处 = 有条链又被摘掉判据；" +
                "多一枚「不判上界」= 有一枚会排铃/挂勿扰/挂实况的调用点退回病灶本行" +
                "（第 ④ 层那本全仓册子只会跟着一起红，这两把尺互为备份）。\n实到：\n$ledger",
            listOf(
                "$LIVE_RESYNCER_FILE -> $WITH_BOUND",
                "$SCHEDULER_FILE -> $WITH_BOUND",
                "$REMINDER_FILE -> $WITH_BOUND",
            ).sorted().joinToString(separator = "\n"),
            ledger,
        )

        // UNBOUNDED 那一档在 `reminder/` 里只许剩下"默认值声明"那一枚字面量，实参位上一处都不许有
        val unboundedInReminder = reminderDir.sumOf { occurrences(withoutComments(it.text), "UNBOUNDED_TOTAL_WEEKS") }
        val defaultDeclarations = reminderDir.sumOf { occurrences(withoutComments(it.text), UNBOUNDED_DECLARATION) }
        assertEquals(
            "**钉法 = 数出现次数（抹注释，按目录）**：`reminder/` 里 UNBOUNDED_TOTAL_WEEKS 的枚数必须" +
                "恰等于「默认值声明」的枚数（今天是一枚，长在 planNextClassWindow 的签名上）。" +
                "多出来的每一枚都是有人把「不判上界」当实参递进了排铃/实况那条链 —— " +
                "那正是 #332 的形状（实到总计 $unboundedInReminder 处、默认值声明 $defaultDeclarations 处）",
            defaultDeclarations,
            unboundedInReminder,
        )
        assertEquals(
            "**钉法 = 数出现次数**：默认值声明本身只许一枚（长在本文件签名上；第 ③ 层另按逐字节钉它）。" +
                "两枚 = 又长出第二个不判上界的入口（实到 $defaultDeclarations 处）",
            1,
            defaultDeclarations,
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

        // 第 ④⑧ 层那枚判档自己也要两头各喂一形：只报「册子五处」而判档把病灶读成合格，
        // 就是 T214 那一格能一直绿的真正原因（它把会排闹钟的调用点写死成了"展示侧"）。
        val preFixResyncerArgs = """
            courses = repository.getDisplayCourses(semester),
                semesterStart = semesterStart,
                timeSlots = repository.getTimeSlots(),
                now = LocalDateTime.now(),
        """.trimIndent()
        assertEquals(
            "判档必须认得改前那一形（四枚实参、没有学期上界 ⇒ 「不判上界」；实到 " +
                boundVerdict(preFixResyncerArgs) + "）",
            WITHOUT_BOUND,
            boundVerdict(preFixResyncerArgs),
        )
        assertEquals(
            "判档必须认得今天那一形（多一行 `totalWeeks = semester.totalWeeks` ⇒ 「带 bound」；实到 " +
                boundVerdict("$preFixResyncerArgs\n            totalWeeks = semester.totalWeeks,") + "）",
            WITH_BOUND,
            boundVerdict("$preFixResyncerArgs\n            totalWeeks = semester.totalWeeks,"),
        )
        assertEquals(
            "把「不判上界」那一档当**实参**递进来必须仍判成「不判上界」（实到 " +
                boundVerdict("totalWeeks = TermWeekGate.UNBOUNDED_TOTAL_WEEKS") + "）—— " +
                "这一形原先被判成「带 bound」，是第 ③④ 两层一起放走病灶的那只手",
            WITHOUT_BOUND,
            boundVerdict("totalWeeks = TermWeekGate.UNBOUNDED_TOTAL_WEEKS"),
        )
    }

    // ─────────────── ⑦ 搬家之后：排铃侧与冲突/导出侧引用的是**同一枚符号** ───────────────

    /**
     * T222 第 ① 步要求的补充格（卡面："并给它补一格'排铃侧与冲突/导出侧引用的是同一枚符号'"）。
     *
     * 静态面三把一起用：
     * - **数出现次数**：`object TermWeekGate` 全 `app/src/main` 恰一处，且它长在 domain 那一枚文件里
     *   （`reminder/` 里再长一枚 = 搬家搬出第二把尺）；
     * - **逐字节**：`reminder/ClassProgressScheduler.kt` 只剩一行别名，别名目标的**全限定名**逐字节钉死
     *   ⇒ 排铃那两条链写的 `TermWeekGate.isInTermWeek(...)` 编译期折到的就是 domain 那一枚，
     *     与冲突侧（`ConflictDetector`）、导出侧（`ScheduleOccurrences` / `ScheduleExporters`）
     *     import 的是同一个包同一个名；
     * - **读形状（册子）**：引用 `TermWeekGate.` 的文件恰五侧（排铃两 + 冲突一 + 导出二），
     *   一枚不多一枚不少。
     *
     * 红了点名哪一侧：册子里少 `ConflictDetector` = **冲突侧没并尺**；少导出那两枚 = **导出侧没并尺**；
     * 少 reminder 那两枚 = **排铃侧被摘掉了判据**（#263 的病灶回潮）。
     */
    @Test
    fun alarmChainsAndTheConflictAndExportSidesResolveToOneSymbol() {
        assertEquals(
            "**钉法 = 数出现次数**：`object TermWeekGate` 全 app/src/main 恰一处。" +
                "实到 " + mainOccurrences("object TermWeekGate {") + " 处，出资：\n" +
                mainSources()
                    .map { it.relative to occurrences(withoutComments(it.text), "object TermWeekGate {") }
                    .filter { it.second > 0 }
                    .sortedBy { it.first }
                    .joinToString(separator = "") { "  ${it.first}: ${it.second} 处\n" },
            1,
            mainOccurrences("object TermWeekGate {"),
        )
        assertEquals(
            "**钉法 = 逐字节**：那一枚必须在 domain 的 $KERNEL_FILE 里（搬回 reminder/ ⇒ 冲突侧与导出侧要反向 import 排铃那一侧）",
            1,
            occurrences(withoutComments(readMainSource(KERNEL_FILE)), "object TermWeekGate {"),
        )
        assertEquals(
            "**钉法 = 逐字节**：reminder/ 那一侧只剩这一行别名，目标的全限定名逐字节钉死 —— " +
                "这一行就是「两条排铃链与冲突/导出三侧引用的是同一枚符号」的证据。实到 " +
                occurrences(withoutComments(readMainSource(SCHEDULER_FILE)), ALIAS_LINE) + " 处",
            1,
            occurrences(withoutComments(readMainSource(SCHEDULER_FILE)), ALIAS_LINE),
        )
        assertEquals(
            "**钉法 = 数出现次数**：别名目标的全限定名全仓恰一处（第二处 = 有人绕过别名直接抄了一条私有副本）",
            1,
            mainOccurrences(ALIAS_TARGET),
        )

        val sides = listOf(
            SCHEDULER_FILE to "排铃侧·课堂铃",
            REMINDER_FILE to "排铃侧·课前提醒",
            CONFLICT_FILE to "冲突侧",
            OCCURRENCES_FILE to "导出侧·ICS 与系统日历",
            WAKE_UP_FILE to "导出侧·WakeUp",
        )
        val register = sides.joinToString(separator = "\n") { (file, side) ->
            "$side -> $file：${occurrences(withoutComments(readMainSource(file)), "TermWeekGate.")} 处"
        }
        for ((file, side) in sides) {
            assertTrue(
                "**钉法 = 读形状**：$side（$file）里必须引用同一枚 `TermWeekGate.` —— 0 处就是**这一侧没并尺**。" +
                    "\n实到册子：\n$register",
                occurrences(withoutComments(readMainSource(file)), "TermWeekGate.") > 0,
            )
        }
        val extra = mainSources()
            .map { it.relative }
            .filter { it !in sides.map { pair -> pair.first } }
            .filter { occurrences(withoutComments(readMainSource(it)), "TermWeekGate.") > 0 }
        assertEquals(
            "**钉法 = 数出现次数（全 main 册子）**：除这五侧之外不许再有第六枚文件引用 `TermWeekGate.`" +
                "（组件那两处「下一节课」走的是展示侧那把尺 + UNBOUNDED 那一档，跨卡那一格已登记；" +
                "实况重挂那一枚 T227 #332 起改为递学期行自己的 totalWeeks，不引用判据符号）。实到多出：\n" +
                extra.joinToString(separator = "\n") { "  $it" }.ifEmpty { "（无）" },
            "",
            extra.joinToString(separator = "\n"),
        )
    }

    // ---------------- 源码核对小工具（与本仓守卫同一套写法）----------------

    /**
     * `planNextClassWindow(` 的调用册子（第 ④ 层数全仓、第 ⑧ 层按目录数同一把尺）。
     *
     * [under] 非空时只收那一层目录之下的文件 —— 第 ⑧ 层要的就是"别按文件名单数"，
     * 否则往 `reminder/` 里再塞一枚不吃 bound 的调用点就会绕过点名式的那把尺。
     * 判据是**读形状**取实参表（配平括号），不是看那一行像不像；定义那一句（前面有 `fun`）不算调用。
     */
    private fun planCallLedger(under: String? = null): List<String> {
        val calls = mutableListOf<String>()
        for (file in mainSources()) {
            if (under != null && !file.relative.startsWith(under)) continue
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
                calls += "${file.relative} -> ${boundVerdict(args)}"
            }
        }
        return calls.sorted()
    }

    /**
     * 实参表算不算「带了学期上界」—— 第 ④⑧ 两层共用的那一枚判档。
     *
     * ⚠️ T227 顺手收紧的一手：原先只看实参表里有没有 `totalWeeks` 这个词，于是
     * `totalWeeks = TermWeekGate.UNBOUNDED_TOTAL_WEEKS` 那一形会被判成「带 bound」——
     * 而那恰好是把病灶换了个写法递回链上（第 ③ 层 KDoc 自己写着"传这一枚就是把自己退回病灶本身"）。
     * 现在两形分开判，第 ⑥ 层两头各喂一次自证。
     */
    private fun boundVerdict(arguments: String): String = when {
        arguments.contains("UNBOUNDED_TOTAL_WEEKS") -> WITHOUT_BOUND
        arguments.contains("totalWeeks") -> WITH_BOUND
        else -> WITHOUT_BOUND
    }

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

        /** 第 ⑧ 层按目录数的那一层：排铃 / 挂勿扰 / 挂实况三条链都住在这里 */
        const val REMINDER_DIR = "com/buaa/schedule/reminder/"

        // T222 搬家之后的新宿主 + 并尺那三侧（第 ⑦ 层逐枚点名）
        const val KERNEL_FILE = "com/buaa/schedule/domain/schedule/TermWeekGate.kt"
        const val CONFLICT_FILE = "com/buaa/schedule/domain/schedule/ConflictDetector.kt"
        const val OCCURRENCES_FILE = "com/buaa/schedule/domain/schedule/ScheduleOccurrences.kt"
        const val WAKE_UP_FILE = "com/buaa/schedule/domain/schedule/ScheduleExporters.kt"
        const val ALIAS_LINE = "typealias TermWeekGate = com.buaa.schedule.domain.schedule.TermWeekGate"
        const val ALIAS_TARGET = "com.buaa.schedule.domain.schedule.TermWeekGate"

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
