package com.buaa.schedule.domain.schedule

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T222（#306 + #307）的**并尺守卫**：「这一周在不在学期内」这一枚判据，
 * 排铃两条链、冲突判定、三条导出链（ICS / 系统日历 / WakeUp）**是不是真的各调同一枚符号**。
 *
 * 纯 JVM 源码核对（无 Robolectric、无设备、不读时钟）。读法与小工具抄
 * `reminder/TermWeekGateSingleSourceGuardTest` 与 `ui/course/CourseGroupPrimaryFragmentGuardTest`
 * （抹注释保字面量 + 花括号配平取块 + 原文切片不吃行号）。本守卫读 `app/src/main/java` 下的源码文本 ——
 * 那一条已在 `app/build.gradle.kts` 的 `guardReadWorkingTreeFiles` 里整目录进 `tasks.withType<Test>()`
 * 的 `inputs`（T92①，台账 #136）。
 *
 * ## 卡面要的三把尺，逐枚点名哪一层是哪一把
 *
 * 1. [noSideWritesASecondBoundComparison] —— **数出现次数（先抹注释再数）**：
 *    `week <= totalWeeks` 那一形在**整个 `app/src/main`** 里除内核外 0 处（含 KDoc 里逐字登记的病灶原文，
 *    所以必须先抹注释再数）；比较式 `week <= 0 || week > totalWeeks` 全仓仍只长在 [WeekCalculator] 那一处；
 *    另两族同形但**不同事**的就地比较 —— `in 1..totalWeeks`（展示/统计侧按标称周数夹数组下标，5 枚文件 7 处）
 *    与 `>= totalWeeks` / `until totalWeeks`（组件翻周的边界、趋势图横轴）—— 按**文件册子**逐枚点名，
 *    并钉本卡那五侧文件里它们全是 0 处；册子变了就是有人又写了一遍。
 * 2. [everyChainAsksTheSameSymbol] —— **读形状（调用点逐枚点名）**：
 *    五侧各含同一枚 `TermWeekGate.` 几处、三条导出链的**上游调用点**各在哪一枚文件，全列成册子。
 * 3. [theKernelIsTheOnlyObjectAndReminderHoldsOnlyAnAlias] —— **逐字节**：
 *    内核那一行 `currentWeekOrNull(...) == week` 钉死；`object TermWeekGate` 全仓恰一处且必须在 domain；
 *    `reminder/` 那一侧只剩一行别名，别名目标的全限定名逐字节钉住（防"搬家搬出第二把尺"）。
 *
 * 另两枚是同一件事的两面：[conflictCallSitesAllCarryTheSemester]（**读形状 + 数出现次数**：
 * ViewModel 那四处 `findConflicts` 调用点每一处都得把学期两枚实参递进去 —— 少一处就是那条链又不吃学期）、
 * [probeRecognisesTheUnjoinedShapes]（**抹注释找锚点**：改前那几形喂进同一把尺各数到该数的值，
 * 写在 KDoc 里的形状抹完必须 0 处，防"锚点拼错的假绿"）。
 *
 * ## 第 ⑥ 层是 T356 续的（#602 与 #397 的交点）
 *
 * [theWakeUpMaxWeekHasTwoSourcesOnlyAndNeverAFabricatedConstant] 钉的不是"并没并尺"，而是第 ② 层那句
 * 「WakeUp 递进去的上界就是同一份 JSON 写出去的 maxWeek」里那枚数的**出处册子**：只有两等
 * （学期行自己给的上界／这份文件实际覆盖到的最晚一周），第三等（`?: CourseConstraints.MAX_TOTAL_WEEKS`
 * 那种常数兜底、把脏学期行的 0 与负数原样写出去）一概不许在场，而两本账必须是同一个局部量。
 * 编号续在末尾而物理位置排在 ④ 与 ⑤ 之间 —— 在册五层的号一格没挪（盘面有注释按号点名守卫的层号）。
 *
 * 报错消息一律点名**哪一侧没并尺**：红了就知道要回去改哪一条链。
 */
class TermWeekGateSingleRulerWiringGuardTest {

    // ─────────────── ① 数出现次数：谁都不许再写一遍上界比较式 ───────────────

    @Test
    fun noSideWritesASecondBoundComparison() {
        // 「<= totalWeeks」这一族形状（`week <= totalWeeks` 与 `!(week <= totalWeeks)` 的两个指针）
        // 先抹注释再数：本仓多处 KDoc 逐字登记着病灶原文，不抹就会把散文当证据。
        for (needle in listOf("<= totalWeeks", "week <= totalWeeks")) {
            assertEquals(
                "**钉法 = 数出现次数（抹注释再数）**：就地比较学期上界的那一形「$needle」在**整个 app/src/main** " +
                    "必须 0 处 —— 判据只在 [WeekCalculator] 那一处，其余五侧一律通过 TermWeekGate 问它。" +
                    "实到 " + mainOccurrences(needle) + " 处，出资的文件：\n" + registerFor(needle) +
                    "\n红了就是**这一侧没并尺**（它自己重新量了一遍学期）",
                0,
                mainOccurrences(needle),
            )
        }
        assertEquals(
            "**钉法 = 数出现次数**：比较式「$BOUND_EXPRESSION」全仓仍恰一处、且只在 WeekCalculator" +
                "（内核委托给它，而不是抄一份）。全仓实到 " + mainOccurrences(BOUND_EXPRESSION) +
                " 处，出资的文件：\n" + registerFor(BOUND_EXPRESSION),
            1,
            mainOccurrences(BOUND_EXPRESSION),
        )
        // 同族但**不是**"这一周在不在学期内"的两枚就地比较：它们量的是「翻页/趋势图还能不能往前」，
        // 宿主都在本卡白名单外 ⇒ 只能按册子钉（多了文件就是有人又在判周次界内时自己写了一遍上界）。
        for ((needle, expected) in listOf(
            REVERSE_BOUND_NEEDLE to listOf("  $WIDGET_COMMON_FILE: 1 处"),
            "until totalWeeks" to listOf("  $WEEKLY_LOAD_TREND_FILE: 1 处"),
        )) {
            assertEquals(
                "**钉法 = 数出现次数（全 main 册子）**：「$needle」这一形今天只有下面那一枚宿主，" +
                    "它裁的是「那一侧自己的边界」（组件翻周 / 趋势图横轴），不是「这一周在不在学期内」，" +
                    "而且宿主在本卡白名单外 ⇒ 记入跨卡那一格。册子多出来的话，就是**有人在判周次界内时" +
                    "又就地写了一遍上界比较**（那是本卡第 ② 条判据形状的红线）：\n" + occurrencesRegister(needle),
                expected.joinToString(separator = "\n") + "\n",
                occurrencesRegister(needle),
            )
            for (file in FIVE_SIDES + KERNEL_FILE) {
                assertEquals(
                    "**钉法 = 数出现次数**：并尺这五侧（+ 内核）里「$needle」必须 0 处 —— " +
                        "$file 自己量上界就是**这一侧没并尺**。实到 " +
                        occurrences(withoutComments(readMainSource(file)), needle) + " 处",
                    0,
                    occurrences(withoutComments(readMainSource(file)), needle),
                )
            }
        }
        // 既有那一族「1..totalWeeks」夹的是**数组下标**（展示/统计侧按标称周数），不是本卡的学期尺；
        // 它今天长这样，逐枚点名，本卡那五侧文件里一枚都不许有。
        val register = occurrencesRegister(RANGE_NEEDLE)
        assertEquals(
            "**钉法 = 数出现次数（全 main 册子）**：`(1..totalWeeks)` 那一族夹取今天只在展示/统计这五枚文件里" +
                "（它们量的是「标称学期有几周 ⇒ 数组开多长」，与本卡「这一周在不在学期内」不是同一件事，" +
                "改它们要动本卡白名单外的文件 ⇒ 归跨卡那一格）。" +
                "册子多了文件 = 又有人在**判周次是否界内**时自己写了一遍区间：\n$register",
            listOf(
                "  $CHARTS_FILE: 1 处",
                "  $COURSE_WEEK_SPANS_FILE: 1 处",
                "  $WEEK_DAY_SCHEDULE_FILE: 1 处",
                "  $WEEK_FREE_GRID_FILE: 2 处",
                "  $WEEKLY_LOAD_TREND_FILE: 2 处",
            ).joinToString(separator = "\n") + "\n",
            register,
        )
        for (file in FIVE_SIDES + KERNEL_FILE) {
            assertEquals(
                "**钉法 = 数出现次数**：并尺这五侧（+ 内核）里「$RANGE_NEEDLE」必须 0 处 —— " +
                    "$file 自己量区间就是**这一侧没并尺**。实到 " + occurrences(withoutComments(readMainSource(file)), RANGE_NEEDLE) + " 处",
                0,
                occurrences(withoutComments(readMainSource(file)), RANGE_NEEDLE),
            )
        }
        // 内核零时钟、零库（搬家之后这一格跟着搬进 domain，一条没松）
        val kernel = withoutComments(readMainSource(KERNEL_FILE))
        for (clock in listOf("LocalDate.now(", "System.currentTimeMillis(", "Instant.", "Clock.", "Date(")) {
            assertEquals(
                "**钉法 = 读形状**：内核里自己读钟（$clock）就不再是纯判据（台账 #265 那一族）。实到 " +
                    occurrences(kernel, clock) + " 处 ⇒ 排铃/冲突/导出三侧的结论会跟着「今天几点」漂",
                0,
                occurrences(kernel, clock),
            )
        }
        assertFalse(
            "**钉法 = 读形状**：内核不许读库（Context / Dao / repository 任一出现就不再是纯判据，" +
                "五侧就再也无法在同一批参数上对账）",
            listOf("Context", "Dao", "repository").any { kernel.contains(it) },
        )
    }

    // ─────────────── ② 读形状：五侧 + 三条导出链的调用点逐枚点名 ───────────────

    @Test
    fun everyChainAsksTheSameSymbol() {
        val expectedRegister = listOf(
            "$CONFLICT_FILE -> 冲突侧（weeksInsideTerm）",
            "$WAKE_UP_FILE -> 导出侧·WakeUp（weeksInsideTerm）",
            "$OCCURRENCES_FILE -> 导出侧·ICS 与系统日历共用那一枚（isInTermWeek）",
            "$SCHEDULER_FILE -> 排铃侧·课堂铃（别名 + isInTermWeek）",
            "$REMINDER_FILE -> 排铃侧·课前提醒（别名 + isInTermWeek）",
        ).sorted().joinToString(separator = "\n")
        val counted = mutableListOf<String>()
        for (file in FIVE_SIDES) {
            val code = withoutComments(readMainSource(file))
            val shape = when (file) {
                CONFLICT_FILE -> "冲突侧（weeksInsideTerm）"
                WAKE_UP_FILE -> "导出侧·WakeUp（weeksInsideTerm）"
                OCCURRENCES_FILE -> "导出侧·ICS 与系统日历共用那一枚（isInTermWeek）"
                SCHEDULER_FILE -> "排铃侧·课堂铃（别名 + isInTermWeek）"
                else -> "排铃侧·课前提醒（别名 + isInTermWeek）"
            }
            counted += "$file -> $shape（" + occurrences(code, "TermWeekGate.") + " 处）"
        }
        val actual = counted.sorted().joinToString(separator = "\n") { it.substringBeforeLast("（") }
        assertEquals(
            "**钉法 = 读形状（逐枚点名调用点）**：引用同一枚判据的必须正好是这五侧 —— " +
                "少一侧 = **那一侧没并尺**（又走各的）；多一侧 = 有第二条链挂了进来（要么它是该并的第三条导出路，" +
                "要么它是第二枚门面）。\n实到册子：\n$actual",
            expectedRegister,
            actual,
        )
        for ((file, side, expected) in listOf(
            Triple(CONFLICT_FILE, "冲突侧", 1),
            Triple(OCCURRENCES_FILE, "导出侧（ICS + 系统日历）", 1),
            Triple(WAKE_UP_FILE, "导出侧（WakeUp）", 1),
            Triple(SCHEDULER_FILE, "排铃侧·课堂铃", 2),
            Triple(REMINDER_FILE, "排铃侧·课前提醒", 1),
        )) {
            val code = withoutComments(readMainSource(file))
            assertEquals(
                "**钉法 = 数出现次数**：$side（$file）里 `TermWeekGate.` 恰 $expected 处。" +
                    "0 处 = **$side 没并尺**；多了 = 同一条链上写了第二遍",
                expected,
                occurrences(code, "TermWeekGate."),
            )
        }

        // 逐字节：每一侧那一道判据语句的原文
        val conflict = balancedBlock(withoutComments(readMainSource(CONFLICT_FILE)), "fun findConflicts(")
        assertTrue(
            "**钉法 = 逐字节 + 读形状**：冲突侧必须在 `findConflicts` 体内过学期尺（在函数外头调 = 裁的不是交集周次）：\n" +
                conflict.lines().filter { it.contains("TermWeekGate.") }.joinToString("\n"),
            conflict.contains(CONFLICT_GATE),
        )
        val build = balancedBlock(withoutComments(readMainSource(OCCURRENCES_FILE)), "fun build(")
        assertTrue(
            "**钉法 = 逐字节 + 顺序**：ICS 与系统日历共用的课次展开必须**先判周次再算日期**" +
                "（判据排在 `plusWeeks` 之前）：红了就是导出侧（ICS / 系统日历）把判据挪到了日期之后或摘掉了：\n" +
                build.lines().filter { it.contains("TermWeekGate.") || it.contains("plusWeeks(") }.joinToString("\n"),
            build.contains(OCCURRENCES_GATE) && build.indexOf(OCCURRENCES_GATE) < build.indexOf(".plusWeeks("),
        )
        val wakeUp = balancedBlock(withoutComments(readMainSource(WAKE_UP_FILE)), "fun toWakeUpJson(")
        assertTrue(
            "**钉法 = 逐字节**：WakeUp 那条导出链的周次必须过学期尺，而且判的就是**同一份 JSON 写出去的那枚 maxWeek**" +
                "（实参名对不上 = 这一侧裁的是别的数）：\n" +
                wakeUp.lines().filter { it.contains("TermWeekGate.") }.joinToString("\n"),
            wakeUp.contains(WAKE_UP_GATE),
        )

        // 三条导出链的上游调用点：build 两枚（ICS、系统日历）、toWakeUpJson 一枚（ViewModel）
        assertEquals(
            "**钉法 = 数出现次数（全 main 册子）**：`ScheduleOccurrences.build(` 的调用点恰两处 = ICS 一枚 + 系统日历一枚，" +
                "两条链共用同一枚展开 ⇒ 裁一次两条都收口。\n实到：\n" + occurrencesRegister(OCCURRENCES_CALL),
            listOf("  $CALENDAR_FILE: 1 处", "  $ICS_FILE: 1 处").joinToString(separator = "\n") + "\n",
            occurrencesRegister(OCCURRENCES_CALL),
        )
        assertEquals(
            "**钉法 = 数出现次数**：第三条导出链（WakeUp）的调用点恰一处（ViewModel 的 exportWakeUpTo），" +
                "实到 " + mainOccurrences(WAKE_UP_CALL) + " 处",
            2,
            mainOccurrences(WAKE_UP_CALL),
        )
        assertEquals(
            "**钉法 = 读形状**：`toWakeUpJson` 的定义与调用点各恰一处（两处都在 `ScheduleExporters.kt` = 定义被抄过）",
            1,
            occurrences(withoutComments(readMainSource(WAKE_UP_FILE)), WAKE_UP_CALL),
        )
        assertEquals(
            "**钉法 = 读形状**：ICS 那一枚调用点必须把学期行原样递给 build（一/系统日历那条链的学期出处）",
            true,
            withoutComments(readMainSource(ICS_FILE)).contains("ScheduleOccurrences.build(semester, courses, timeSlots)"),
        )
    }

    // ─────────────── ③ 逐字节：内核只有一枚，reminder 侧只剩一行别名 ───────────────

    @Test
    fun theKernelIsTheOnlyObjectAndReminderHoldsOnlyAnAlias() {
        val kernel = balancedBlock(withoutComments(readMainSource(KERNEL_FILE)), "object TermWeekGate {")
        assertEquals(
            "**钉法 = 逐字节**：判据本体必须把那一周的周一喂回展示侧那把尺，且只认「映射回来的周号就是这一周」。\n" +
                "复算：grep -Fn '$DELEGATION_LINE' app/src/main/java/$KERNEL_FILE\n" +
                "全仓实到 " + mainOccurrences(DELEGATION_LINE) + " 处 —— 两处就是**搬家搬出了第二把尺**",
            1,
            occurrences(kernel, DELEGATION_LINE),
        )
        assertEquals(
            "**钉法 = 逐字节**：那一周的周一只能取自 SemesterWeekDates（展示侧翻周那一份）。" +
                "实到 " + occurrences(kernel, MONDAY_LINE) + " 处",
            1,
            occurrences(kernel, MONDAY_LINE),
        )
        for (needle in listOf("in 1..", "coerceIn", "totalWeeks >")) {
            assertEquals(
                "**钉法 = 读形状**：内核体里「$needle」必须 0 处（长出来就是第二把尺）。实到 " +
                    occurrences(kernel, needle) + " 处",
                0,
                occurrences(kernel, needle),
            )
        }
        // 周次表那一枚入口不许自己量区间 —— 它逐周过 isInTermWeek
        val weeksEntry = balancedBlock(withoutComments(readMainSource(KERNEL_FILE)), "fun weeksInsideTerm(")
        assertEquals(
            "**钉法 = 逐字节**：`weeksInsideTerm` 的判据本体必须就是逐周过 `isInTermWeek`（同一枚尺的第二层用法，" +
                "不是第二把尺）。实到 " + occurrences(weeksEntry, WEEKS_ENTRY_DELEGATION) + " 处",
            1,
            occurrences(weeksEntry, WEEKS_ENTRY_DELEGATION),
        )
        // 全仓只有这一枚 object，而且它必须在 domain（reminder 侧只剩别名）
        assertEquals(
            "**钉法 = 数出现次数**：`object TermWeekGate` 全 app/src/main 恰一处，实到 " +
                mainOccurrences("object TermWeekGate {") + " 处，出资：\n" + occurrencesRegister("object TermWeekGate {"),
            1,
            mainOccurrences("object TermWeekGate {"),
        )
        assertEquals(
            "**钉法 = 数出现次数**：那一枚必须在 domain 的 $KERNEL_FILE 里（搬回 reminder/ = 导出侧与冲突侧" +
                "要反向 import 排铃那一侧，依赖方向又歪一次）。实到 " +
                occurrences(withoutComments(readMainSource(KERNEL_FILE)), "object TermWeekGate {") + " 处",
            1,
            occurrences(withoutComments(readMainSource(KERNEL_FILE)), "object TermWeekGate {"),
        )
        assertEquals(
            "**钉法 = 逐字节**：`reminder/` 那一侧只许剩这一行别名，且目标必须逐字节是 domain 那一枚 —— " +
                "这一行就是「排铃侧与冲突/导出侧引用的是同一枚符号」的静态面证据。实到 " +
                occurrences(withoutComments(readMainSource(SCHEDULER_FILE)), ALIAS_LINE) + " 处",
            1,
            occurrences(withoutComments(readMainSource(SCHEDULER_FILE)), ALIAS_LINE),
        )
        assertEquals(
            "**钉法 = 数出现次数**：别名目标的全限定名全仓恰一处（第二处 = 又有人直接引用了一条私有副本）。" +
                "实到 " + mainOccurrences(ALIAS_TARGET) + " 处，出资：\n" + occurrencesRegister(ALIAS_TARGET),
            1,
            mainOccurrences(ALIAS_TARGET),
        )
        // 搬家不是重写：两条排铃链那道判据语句逐字节没动，全 main 恰两处
        assertEquals(
            "**钉法 = 数出现次数**：排铃那两道 `if (!TermWeekGate.isInTermWeek(week, semesterStart, totalWeeks)) continue`" +
                " 搬家前后一字未改（这是「reminder/ 侧零行为改动」的静态面，行为面证据是 reminder/TermWeekGateTest 那 17 格照样绿）",
            2,
            mainOccurrences(GATE_STATEMENT),
        )
    }

    // ─────────────── ④ 冲突侧那四处调用点必须自己把学期递进来 ───────────────

    @Test
    fun conflictCallSitesAllCarryTheSemester() {
        val viewModel = withoutComments(readMainSource(VIEW_MODEL_FILE))
        assertEquals(
            "**钉法 = 数出现次数（全 main 册子）**：`findConflicts(` 全仓恰五处 = 1 定义（ConflictDetector）" +
                "+ 4 调用点（ViewModel：uiState 那一格、逐条勾选两站、教务/ICS 预览那一站）。" +
                "少一处 = 有一条链又不判学期；多一处 = 又长出一条不读学期行的冲突判定路。\n实到：\n" +
                occurrencesRegister("findConflicts("),
            listOf("  $CONFLICT_FILE: 1 处", "  $VIEW_MODEL_FILE: 4 处").joinToString(separator = "\n") + "\n",
            occurrencesRegister("findConflicts("),
        )
        // 三形（两站逐条勾选写的是同一形 ⇒ 形是三枚、调用点是四枚）
        for (anchor in listOf(UI_STATE_CALL, TOGGLE_CALL, SET_ALL_CALL, PREVIEW_CALL).distinct()) {
            assertTrue(
                "**钉法 = 读形状 + 逐字节**：冲突侧的调用点少了「$anchor」这一形，" +
                    "就是**那一处没把学期递进来**（判据退回无学期形状 = 病灶回潮）。" +
                    "实到次数：" + occurrences(viewModel, anchor),
                viewModel.contains(anchor),
            )
        }
        // 四处调用点各数一遍（两站逐条勾选同一形 ⇒ 那一枚锚点数到 2）：合起来必须是四枚，
        // 少一枚就是有一条链又不吃学期行，多一枚就是长出一条不判学期的冲突路。
        val perSite = listOf(
            "uiState 那一格" to occurrences(viewModel, UI_STATE_CALL),
            "逐条勾选那两站" to occurrences(viewModel, TOGGLE_CALL),
            "教务/ICS 预览那一站" to occurrences(viewModel, PREVIEW_CALL),
        )
        assertEquals(
            "**钉法 = 数出现次数（逐枚点名调用点）**：ViewModel 里带学期实参的 `findConflicts` 调用恰四处" +
                "（uiState 1 + 逐条勾选 2 + 预览 1），实到册子：\n" +
                perSite.joinToString(separator = "\n") { "  ${it.first}: ${it.second} 枚" } +
                "\n三形之和 " + perSite.sumOf { it.second } + " 枚",
            listOf("  uiState 那一格: 1 枚", "  逐条勾选那两站: 2 枚", "  教务/ICS 预览那一站: 1 枚").joinToString(separator = "\n") +
                "\n三形之和 4 枚",
            perSite.joinToString(separator = "\n") { "  ${it.first}: ${it.second} 枚" } +
                "\n三形之和 " + perSite.sumOf { it.second } + " 枚",
        )
        assertEquals(
            "**钉法 = 数出现次数**：ViewModel 里 `findConflicts` 的调用点总数必须与带学期那四处相等 —— " +
                "不相等就是有一处调用点又回到不吃学期的形状了",
            occurrences(viewModel, "ConflictDetector.findConflicts("),
            perSite.sumOf { it.second },
        )
        // 内核的判据参数表不许长出默认值（本仓那一族"默认参数口子"账：#264 / T214b）
        val detector = withoutComments(readMainSource(CONFLICT_FILE))
        for (default in listOf("semesterStart: LocalDate? =", "totalWeeks: Int? =")) {
            assertEquals(
                "**钉法 = 数出现次数**：冲突判据的参数表里不许出现默认值「$default」（挂了默认值 = " +
                    "调用点忘了递学期也不会红）。实到 " + occurrences(detector, default) + " 处",
                0,
                occurrences(detector, default),
            )
        }
    }

    // ─────────────── ⑥ T356（#602 × #397）：WakeUp 那枚 maxWeek 只有两等出处 ───────────────
    // （编号按在册那五层之后**续**一枚，物理位置排在 ④ 与 ⑤ 之间。在册五层的号一格没挪：
    //   盘面有注释按号点名「守卫第 ① 层／第 ⑤ 层」（TermWeekGate 自己的 KDoc 里两处），
    //   本卡改号就等于把那些引用指向别的东西 —— 那是"注释与守卫各说各话"那一族的形状。）

    /**
     * 第 ② 层那句"WakeUp 递进去的上界就是同一份 JSON 写出去的 maxWeek"钉的是**锚点在场**；
     * 这一层钉的是这枚数的**出处册子**：只有两等（学期行自己给的上界 / 这份文件实际覆盖到的最晚一周），
     * 第三等（拿常数顶、把脏值原样写出去）一概不许有，而两本账必须是**同一个局部量**。
     *
     * 四手各是哪一种钉法（卡面点名的那四种）：
     * - **逐字节**：那三枚赋值行各恰一枚在 `toWakeUpJson` 体内（换掉第二等的出处当场红）；
     * - **抹注释找锚点**：病灶那一形「?: CourseConstraints.MAX_TOTAL_WEEKS」在代码里 **0** 处、
     *   在含注释的原文里恰好 **1** 处（取证逐字写在导出器自己的注释里 ⇒ 那个 0 不是"注释删干净"换来的）；
     * - **数出现次数**：`MAX_TOTAL_WEEKS` 在导出器代码里 0 处（一枚都不许再当兜底上界使），
     *   `totalWeeks` 那枚名字在代码里恰 5 处、在 `toWakeUpJson` 体内恰 4 处（涨出一枚就是有人
     *   另立了第三本上界账），`"maxWeek":$totalWeeks` 代码 1 处 / 原文 2 处；
     * - **读形状**：索引顺序 取上界 → 覆盖度 → 合并 → 才递进裁剪 → 才拼 maxWeek，
     *   另加**同源那一手**：裁剪调用的第三实参名与 `maxWeek` 后面插值的那个名字必须逐字相同。
     *
     * 反套套逻辑（探测器自检）：把改前那一形与"两本账分家"那一形喂进同一把尺，各数到该数的值。
     */
    @Test
    fun theWakeUpMaxWeekHasTwoSourcesOnlyAndNeverAFabricatedConstant() {
        val raw = readMainSource(WAKE_UP_FILE)
        val code = withoutComments(raw)
        val body = balancedBlock(code, "fun toWakeUpJson(")

        for (line in listOf(CEILING_FROM_ROW, COVERAGE_FROM_ROW, MERGED_BOUND_ROW)) {
            assertEquals(
                "**钉法 = 逐字节**：`toWakeUpJson` 体内这一行必须恰好一枚（实到 " +
                    occurrences(body, line) + " 枚）：「$line」。\n" +
                    "摘掉或改写它 = 上界的两等出处少了一等 —— 那一等就会被顶成常数（#602）" +
                    "或把脏学期行原样写出去（#397 与 #602 的交点）",
                1,
                occurrences(body, line),
            )
        }

        // 抹注释找锚点：病灶那一形代码 0 处，而取证必须还在注释里（读到 0 处原文 = 上面那个 0 是删证据换来的）
        assertEquals(
            "**钉法 = 抹注释找锚点**：病灶那一形「$LEGACY_CEILING_FALLBACK」在代码里必须 **0** 处" +
                "（它是 #602 的本尊：拿学期上限常数冒充这学期的周数）。实到 " + occurrences(code, LEGACY_CEILING_FALLBACK) + " 处",
            0,
            occurrences(code, LEGACY_CEILING_FALLBACK),
        )
        assertEquals(
            "**钉法 = 数出现次数（含注释原文）**：同一形在原文里必须恰好 **1** 处 —— 那是导出器注释里逐字" +
                "留着的改前取证。读到 0 = 取证被搬走（上面那个 0 就成了假绿）；读到 2 = 病灶回潮或被引用第二遍。" +
                "实到 " + occurrences(raw, LEGACY_CEILING_FALLBACK) + " 处",
            1,
            occurrences(raw, LEGACY_CEILING_FALLBACK),
        )
        assertEquals(
            "**钉法 = 数出现次数（抹注释）**：`MAX_TOTAL_WEEKS` 这枚常量在导出器代码里必须 **0** 处 —— " +
                "它管的是写侧的限幅，不该出现在导出链上（出现在兜底位就是 #602）。实到 " +
                occurrences(code, "MAX_TOTAL_WEEKS") + " 处",
            0,
            occurrences(code, "MAX_TOTAL_WEEKS"),
        )
        assertEquals(
            "**钉法 = 数出现次数**：那枚上界的局部量名字在导出器代码里恰 " + TOTAL_WEEKS_NAME_TOTAL + " 处、" +
                "在 `toWakeUpJson` 体内恰 " + TOTAL_WEEKS_NAME_IN_BODY + " 处（取上界那一行、合并那一行、" +
                "递进裁剪那一处、拼进 maxWeek 那一处）。实到 全文件 " + occurrences(code, "totalWeeks") +
                " 枚 ／ 体内 " + occurrences(body, "totalWeeks") + " 枚 —— 涨出一枚就是有人另立了第三本上界账" +
                "（那是本卡第 ② 层防的「两把尺」形状）",
            Pair(TOTAL_WEEKS_NAME_TOTAL, TOTAL_WEEKS_NAME_IN_BODY),
            Pair(occurrences(code, "totalWeeks"), occurrences(body, "totalWeeks")),
        )
        assertEquals(
            "**钉法 = 数出现次数（两栏）**：写 maxWeek 的那一行「$MAX_WEEK_FIELD_LINE」在代码里恰 1 枚（它就是输出）、" +
                "在原文里恰 2 枚（另一枚是下面那段注释逐字引用着它）。实到 代码 " +
                occurrences(code, MAX_WEEK_FIELD_LINE) + " ／ 原文 " + occurrences(raw, MAX_WEEK_FIELD_LINE),
            Pair(1, 2),
            Pair(occurrences(code, MAX_WEEK_FIELD_LINE), occurrences(raw, MAX_WEEK_FIELD_LINE)),
        )

        // 读形状：先后顺序 —— 上界的两等先算完，才递进裁剪，最后才拼进 JSON
        val atCeiling = body.indexOf(CEILING_FROM_ROW)
        val atCoverage = body.indexOf(COVERAGE_FROM_ROW)
        val atMerged = body.indexOf(MERGED_BOUND_ROW)
        val atClip = body.indexOf(WAKE_UP_GATE)
        val atField = code.indexOf(MAX_WEEK_FIELD_LINE)
        assertTrue(
            "**钉法 = 读形状（索引顺序）**：两等出处（取上界 $atCeiling、覆盖度 $atCoverage）必须先于合并那一行 " +
                "$atMerged，合并必须先于递进裁剪 $atClip，裁剪必须先于拼 `maxWeek` $atField。" +
                "顺序一歪就是「先裁完再决定声明什么」= 声明与实际裁的两本账",
            atCeiling in 1 until atCoverage && atCoverage < atMerged && atMerged < atClip && atClip < atField,
        )

        // 同源那一手：裁剪的第三实参名 == maxWeek 后面插值的名字
        val clipBound = body.substringAfter("TermWeekGate.weeksInsideTerm(").substringBefore(")")
            .substringAfterLast(',').trim()
        val declaredBound = code.substringAfter(MAX_WEEK_FIELD_HEAD).substringBefore(',').trim()
        assertEquals(
            "**钉法 = 读形状（同源）**：递进 [TermWeekGate.weeksInsideTerm] 的上界是「$clipBound」，" +
                "而写进 `maxWeek` 的插值是「$declaredBound」—— 两枚必须逐字同名（同一枚局部量）。" +
                "分家就是这一枚 bug 的根：对方 App 收到的那份 JSON 里 endWeek 与 maxWeek 各说各话",
            clipBound,
            declaredBound,
        )
        assertEquals(
            "**钉法 = 读形状（同源的正面写法）**：同名还不够，名字必须是合并那一枚（$MERGED_BOUND_ROW 的左边）",
            "totalWeeks",
            clipBound,
        )

        // 探测器自检：改前那一形与"两本账分家"那一形都必须在同一把尺下数到该数的值
        val legacy = "val totalWeeks = semester?.totalWeeks $LEGACY_CEILING_FALLBACK"
        assertEquals(
            "**探测器自检（朝宽的反证）**：改前那一形喂进来必须数到 1 枚「$LEGACY_CEILING_FALLBACK」" +
                "（实到 " + occurrences(legacy, LEGACY_CEILING_FALLBACK) + " 枚）—— 数不到上面那个「0 处」就是空锚点",
            1,
            occurrences(legacy, LEGACY_CEILING_FALLBACK),
        )
        val twoRulers = "val weeks = TermWeekGate.weeksInsideTerm(sortedWeeks, startMonday, coveredMaxWeek)" +
            ".ifEmpty { sortedWeeks }\n\"\"\"\"maxWeek\":\$termCeiling,\"nodesPerDay\"\"\""
        val twoRulerClip = twoRulers.substringAfter("TermWeekGate.weeksInsideTerm(").substringBefore(")")
            .substringAfterLast(',').trim()
        val twoRulerDeclared = twoRulers.substringAfter(MAX_WEEK_FIELD_HEAD).substringBefore(',').trim()
        assertEquals(
            "**探测器自检（朝窄／分家的反证）**：「裁剪用 coveredMaxWeek 而 maxWeek 写 termCeiling」那一形" +
                "必须被同源那一手读出两枚**不同**的名字（实到 " + twoRulerClip + " ／ " + twoRulerDeclared +
                "），否则第 ② 层那句「判的就是同一份 JSON 写出去的那枚 maxWeek」只是空话",
            false,
            twoRulerClip == twoRulerDeclared,
        )
    }

    // ─────────────── ⑤ 探测器自检：认得"没并尺"那一形 ───────────────

    /**
     * 只报「锚点几处」而锚点其实拼错，等于没有守卫。两个方向各一头：
     * - **朝松**：改前那几形（导出侧不裁、冲突侧不吃学期、reminder 侧自己写 object）喂进同一把尺各数到该数的值；
     * - **朝窄**：同一句写在 KDoc 里、抹完注释必须 0 处（main 与本文件的注释都逐字登记着那些形状）。
     */
    @Test
    fun probeRecognisesTheUnjoinedShapes() {
        val untrimmedBuild = """
            for (course in courses) {
                for (week in course.weeks) {
                    val date = semesterStart.plusWeeks((week - 1).toLong())
                }
            }
        """.trimIndent()
        assertEquals(
            "探测器必须抓得出改前那条展开（判据 0 处才说明第 ② 层那句「恰一处」不是空锚点；实到 " +
                occurrences(untrimmedBuild, OCCURRENCES_GATE) + " 处）",
            0,
            occurrences(untrimmedBuild, OCCURRENCES_GATE),
        )
        assertEquals("改前那条展开里日期照生成（plusWeeks 一处），次序判据才有对照物", 1, occurrences(untrimmedBuild, ".plusWeeks("))

        val conflictWithoutSemester = "val overlapWeeks = a.weeks.intersect(b.weeks.toSet()).sorted()"
        assertEquals(
            "探测器必须抓得出「冲突侧不吃学期」那一形（改前原文，`TermWeekGate.` 0 处；实到 " +
                occurrences(conflictWithoutSemester, "TermWeekGate.") + " 处）",
            0,
            occurrences(conflictWithoutSemester, "TermWeekGate."),
        )
        val secondRulerShape = "object TermWeekGate { fun isInTermWeek(week: Int, totalWeeks: Int?) = week <= totalWeeks }"
        assertEquals(
            "探测器必须抓得出「搬家搬出第二把尺」那一形（`<= totalWeeks` 数到 1 处；实到 " +
                occurrences(secondRulerShape, "<= totalWeeks") + " 处）—— 第 ① 层那句「0 处」的对照物",
            1,
            occurrences(secondRulerShape, "<= totalWeeks"),
        )
        val upstreamCopy = "if (week <= 0 || week > totalWeeks) return null"
        assertEquals(
            "同一把尺也必须抓得出展示侧原文那一形（`$BOUND_EXPRESSION` 数到 1 处）—— " +
                "第 ① 层那句「全仓恰一处」靠的就是它",
            1,
            occurrences(upstreamCopy, BOUND_EXPRESSION),
        )
        val secondObject = "typealias TermWeekGate = com.buaa.schedule.domain.schedule.TermWeekGate\nobject TermWeekGate {"
        assertEquals(
            "别名与 object 同时在场时探测器各数到一枚（别名 " + occurrences(secondObject, ALIAS_LINE) +
                " 处、object " + occurrences(secondObject, "object TermWeekGate {") + " 处）",
            Pair(1, 1),
            Pair(occurrences(secondObject, ALIAS_LINE), occurrences(secondObject, "object TermWeekGate {")),
        )

        val quotedInComment = """
            /**
             * 旧形状登记：week <= totalWeeks 与 a.weeks.intersect(b.weeks.toSet()).sorted() 今天都不许再出现。
             */
            fun noop() = Unit
        """.trimIndent()
        assertEquals(
            "注释里逐字引用的那一形**不许**进第 ①④ 层的账：未抹注释时它确实在原文里（实到 " +
                occurrences(quotedInComment, "<= totalWeeks") + " 处），抹完必须 0 处（实到 " +
                occurrences(withoutComments(quotedInComment), "<= totalWeeks") + " 处）",
            0,
            occurrences(withoutComments(quotedInComment), "<= totalWeeks"),
        )
        // 反向自检：本卡五侧今天的形状必须真在场（锚点拼错的假绿在这一格当场红）
        for (side in listOf(CONFLICT_FILE, OCCURRENCES_FILE, WAKE_UP_FILE, SCHEDULER_FILE, REMINDER_FILE, KERNEL_FILE)) {
            assertTrue(
                "第 ②③ 层的锚点宿主必须读得到：$side 读空了就是探测器自己拼错了路径",
                withoutComments(readMainSource(side)).isNotEmpty(),
            )
        }
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

    private fun mainOccurrences(needle: String): Int = mainSources().sumOf { occurrences(withoutComments(it.text), needle) }

    /** 数出现次数的册子：只列有命中的文件，逐枚点名"是哪一侧写的"，红了当场就能读出来。 */
    private fun occurrencesRegister(needle: String): String = mainSources()
        .map { it.relative to occurrences(withoutComments(it.text), needle) }
        .filter { it.second > 0 }
        .sortedBy { it.first }
        .joinToString(separator = "") { "  ${it.first}: ${it.second} 处\n" }

    private fun registerFor(needle: String): String =
        if (occurrencesRegister(needle).isEmpty()) "（无）" else occurrencesRegister(needle)

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
            val hit = listOf("src/main/java", "app/src/main/java").map { File(dir, it) }.firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }

    private companion object {
        const val KERNEL_FILE = "com/buaa/schedule/domain/schedule/TermWeekGate.kt"
        const val CONFLICT_FILE = "com/buaa/schedule/domain/schedule/ConflictDetector.kt"
        const val OCCURRENCES_FILE = "com/buaa/schedule/domain/schedule/ScheduleOccurrences.kt"
        const val WAKE_UP_FILE = "com/buaa/schedule/domain/schedule/ScheduleExporters.kt"
        const val SCHEDULER_FILE = "com/buaa/schedule/reminder/ClassProgressScheduler.kt"
        const val REMINDER_FILE = "com/buaa/schedule/reminder/ReminderScheduler.kt"
        const val VIEW_MODEL_FILE = "com/buaa/schedule/ui/ScheduleViewModel.kt"
        const val ICS_FILE = "com/buaa/schedule/data/export/IcsExporter.kt"
        const val CALENDAR_FILE = "com/buaa/schedule/data/calendar/CalendarSyncManager.kt"

        // 第 ① 层那一族"既有夹取"的宿主（展示/统计侧，本卡不动，逐枚点名）
        const val CHARTS_FILE = "com/buaa/schedule/core/designsystem/ScheduleCharts.kt"
        const val COURSE_WEEK_SPANS_FILE = "com/buaa/schedule/domain/schedule/CourseWeekSpans.kt"
        const val WEEK_DAY_SCHEDULE_FILE = "com/buaa/schedule/domain/schedule/WeekDaySchedule.kt"
        const val WEEK_FREE_GRID_FILE = "com/buaa/schedule/domain/schedule/WeekFreeGrid.kt"
        const val WEEKLY_LOAD_TREND_FILE = "com/buaa/schedule/domain/schedule/WeeklyLoadTrend.kt"
        const val WIDGET_COMMON_FILE = "com/buaa/schedule/widget/WidgetCommon.kt"

        /** 并尺的五侧（第 ② 层的册子就是它） */
        val FIVE_SIDES = listOf(
            CONFLICT_FILE,
            OCCURRENCES_FILE,
            WAKE_UP_FILE,
            SCHEDULER_FILE,
            REMINDER_FILE,
        )

        // ① 比较式与夹取那一族
        const val BOUND_EXPRESSION = "week <= 0 || week > totalWeeks"
        const val RANGE_NEEDLE = "in 1..totalWeeks"
        const val REVERSE_BOUND_NEEDLE = ">= totalWeeks"

        // ② 各侧那道判据语句（逐字节）
        const val CONFLICT_GATE = "TermWeekGate.weeksInsideTerm("
        const val OCCURRENCES_GATE = "if (!TermWeekGate.isInTermWeek(week, semesterStart, semester.totalWeeks)) continue"
        const val WAKE_UP_GATE = "TermWeekGate.weeksInsideTerm(sortedWeeks, startMonday, totalWeeks).ifEmpty { sortedWeeks }"
        const val GATE_STATEMENT = "if (!TermWeekGate.isInTermWeek(week, semesterStart, totalWeeks)) continue"
        const val OCCURRENCES_CALL = "ScheduleOccurrences.build("
        const val WAKE_UP_CALL = "toWakeUpJson("

        // ⑥（T356，#602 × #397）：WakeUp 那枚 maxWeek 的两等出处与"不许拿常数顶"那一形
        const val CEILING_FROM_ROW = "val termCeiling = semester?.totalWeeks?.takeIf { it > 0 }"
        const val COVERAGE_FROM_ROW = "val coveredMaxWeek = courses.filter { WakeUpDropTally.wakeUpDrop(it) == null }"
        const val MERGED_BOUND_ROW = "val totalWeeks = termCeiling ?: coveredMaxWeek"
        const val LEGACY_CEILING_FALLBACK = "?: CourseConstraints.MAX_TOTAL_WEEKS"
        const val MAX_WEEK_FIELD_LINE = "\"maxWeek\":${'$'}totalWeeks"
        const val MAX_WEEK_FIELD_HEAD = "\"maxWeek\":${'$'}"

        /** 那枚上界局部量的名字在导出器里今天出现的枚数（全文件／`toWakeUpJson` 体内） */
        const val TOTAL_WEEKS_NAME_TOTAL = 5
        const val TOTAL_WEEKS_NAME_IN_BODY = 4

        // ③ 内核本体那一行 + 别名
        const val DELEGATION_LINE = "WeekCalculator.currentWeekOrNull(start, bound, weekMonday) == week"
        const val MONDAY_LINE = "val weekMonday = SemesterWeekDates.mondayOf(start, week) ?: return false"
        const val WEEKS_ENTRY_DELEGATION = "weeks.filter { isInTermWeek(it, semesterStart, totalWeeks) }"
        const val ALIAS_LINE = "typealias TermWeekGate = com.buaa.schedule.domain.schedule.TermWeekGate"
        const val ALIAS_TARGET = "com.buaa.schedule.domain.schedule.TermWeekGate"

        // ④ 冲突侧四处调用点
        const val UI_STATE_CALL =
            "ConflictDetector.findConflicts(visibleCourses, semester?.startLocalDate, semester?.totalWeeks)"
        const val TOGGLE_CALL =
            "ConflictDetector.findConflicts(selection.toWrite, pending.semester.startLocalDate, pending.semester.totalWeeks)"
        const val SET_ALL_CALL = TOGGLE_CALL
        const val PREVIEW_CALL = "ConflictDetector.findConflicts(courses, semester.startLocalDate, semester.totalWeeks)"
    }
}
