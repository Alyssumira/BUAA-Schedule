package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T222 病灶 3（#307）**导出侧**的表驱动格子 —— ICS 与系统日历共用的那一枚课次展开
 * （[ScheduleOccurrences.build] 同时喂 `data/export/IcsExporter` 与
 * `data/calendar/CalendarSyncManager`，两条链一处改动一起收口）。
 *
 * 卡面第 3 条要的六档都在这儿：**界内 / 越上界 / 越下界 / 空周次 / 无学期行 / 上界取不到**。
 * 判据是 [TermWeekGate.isInTermWeek]（与排铃两条链、冲突判定同一枚符号），
 * 学期两枚事实一律从参数递进来：`Semester.startLocalDate` 与 `Semester.totalWeeks`，
 * 本文件与内核都不读钟、不读库。
 *
 * 与 `ScheduleOccurrencesTest` 的分工：那枚钉的是身份与摘要（stableId / contentHash / 撞 id 去重 /
 * 缺节次时间的 skipped），它的夹具本来就是 19 周学期 + 第 1、3 周 ⇒ **一格没改、照样绿**，
 * 那是"并尺没有把界内课次一起裁掉"的证据。本文件摆的是学期这一维。
 */
class ScheduleOccurrencesTermTrimTest {

    // 夹具的两枚基准值放在伴生对象里：下面那枚 `Cell` 的默认实参要用它们，
    // 而嵌套类拿不到外层实例（`Outer class ... cannot be used as receiver`）。
    private companion object {
        val start = LocalDate.of(2026, 9, 7)
        const val termWeeks = 19
    }

    private val semester = Semester(
        termCode = "T",
        termName = "T",
        startDate = start.toString(),
        totalWeeks = termWeeks,
    )

    private fun course(weeks: List<Int>, day: Int = 1, periods: List<Int> = listOf(1, 2)) = Course(
        name = "高数",
        teacher = "张三",
        location = "J3-101",
        dayOfWeek = day,
        periods = periods,
        weeks = weeks,
    )

    private data class Cell(
        val name: String,
        val weeks: List<Int>,
        /** 期望真正展开出的教学周（升序）；这就是"进得了对方日历的那几周" */
        val expectedWeeks: List<Int>,
        val totalWeeks: Int = termWeeks,
        val startDate: String = start.toString(),
    )

    private val cells = listOf(
        // ① 界内
        Cell("界内：第 1、3 周 ⇒ 两节课次都出场，一节都不少", listOf(1, 3), listOf(1, 3)),
        Cell("界内那一周的边界：第 19 周而学期 19 周 ⇒ 出场（上界闭区间，与展示侧同一档）", listOf(19), listOf(19)),
        Cell("界内整学期：1..19 ⇒ 19 节课次全在", (1..19).toList(), (1..19).toList()),
        // ② 越上界（病灶那一格：对方日历里的幽灵日程就是它造出来的）
        Cell("越上界：第 20 周而学期只有 19 周 ⇒ 一节都不写", listOf(20), emptyList()),
        Cell(
            "越上界混着界内：学期外那三周不进导出件，界内那两周照旧（**跳过**而不是截断整门课）",
            listOf(1, 2, 20, 21, 22), listOf(1, 2),
        ),
        Cell("极端越界（备份里用户可控）：第 400 周 ⇒ 不写", listOf(400), emptyList()),
        // ③ 越下界
        Cell("第 0 周 ⇒ 没有这一周，不写", listOf(0), emptyList()),
        Cell("负周次（教务脏数据）⇒ 不写", listOf(-3), emptyList()),
        Cell("越下界混着界内 ⇒ 只交回第 3 周", listOf(-1, 0, 3), listOf(3)),
        // ④ 空周次
        Cell("空周次 ⇒ 零课次（与改前同：循环本就不进）", emptyList(), emptyList()),
        // ⑤⑥ 上界那一枚的各种形状
        Cell("学期只有 1 周时第 2 周 ⇒ 不写（尺跟着学期行走，不是硬写的 19）", listOf(2), emptyList(), totalWeeks = 1),
        Cell("学期 20 周时第 20 周 ⇒ 出场（同一枚周次换个学期就回到界内）", listOf(20), listOf(20), totalWeeks = 20),
        Cell("totalWeeks = 0（补建的学期行、周数没根据）⇒ 一律不写", listOf(1), emptyList(), totalWeeks = 0),
    )

    /** 交回课次里那些周次对应的**星期一**反推回教学周，用来核对"到底是哪几周进了导出件"。 */
    private fun weeksOf(build: OccurrenceBuildResult): List<Int> = build.occurrences
        .map { occurrence ->
            // 周一（dayOfWeek = 1）的课次：date 与学期起点的周差 + 1 就是那一周的教学周
            ChronoWeek.weekIndexOf(start, occurrence.date)
        }
        .distinct()
        .sorted()

    @Test
    fun `六档格子逐条对账`() {
        for (cell in cells) {
            val row = Semester(
                termCode = "T",
                termName = "T",
                startDate = cell.startDate,
                totalWeeks = cell.totalWeeks,
            )
            val build = ScheduleOccurrences.build(row, listOf(course(cell.weeks)), emptyList())
            assertEquals(
                "${cell.name}（weeks=${cell.weeks} totalWeeks=${cell.totalWeeks}）" +
                    " —— 交回的周次不对就是**导出侧没并尺**：多出来的那几周会进对方日历当幽灵日程，" +
                    "少掉的那几周是本该导出的真课",
                cell.expectedWeeks,
                weeksOf(build),
            )
            assertEquals(
                "${cell.name} —— 课次数（每周一节课次，界内那几周各一枚）",
                cell.expectedWeeks.size,
                build.occurrences.size,
            )
        }
    }

    /**
     * **无学期行**那一档（导出口径与排铃侧同一边：判不出 ⇒ 不写）：
     * 学期行在而日期解析不出来时，整批课次交回空表、`skipped` 保持 0 ——
     * ICS 那条链的调用点（`ScheduleViewModel.exportIcs`）本来就在 `startLocalDate == null` 时早退，
     * 系统日历那条（`CalendarSyncManager.computeDiff`）同；这一格钉的是**内核自己也不兜底**。
     */
    @Test
    fun brokenSemesterStartDateExportsNothing() {
        val broken = Semester(termCode = "T", termName = "T", startDate = "2026-13-45", totalWeeks = termWeeks)
        val build = ScheduleOccurrences.build(broken, listOf(course((1..19).toList())), emptyList())
        assertEquals("学期日期解析不出来 ⇒ 一条课次都不写（离开本应用的数据不猜起点）", 0, build.occurrences.size)
        assertEquals(0, build.skipped)
    }

    /**
     * 裁的是**学期外那几周**，不是"整门课"：越界周次是跳过而不是把课丢掉 ——
     * 同一门课界内那几周照旧逐周展开，且 **stableId 与日期与改前逐字节相同**
     * （`ScheduleOccurrences.stableIdFor` 那一份没动过一个字节，这是"并尺不重建身份"的证据）。
     */
    @Test
    fun inTermOccurrencesKeepTheirIdentityAndDates() {
        val weeks = listOf(1, 2, 17)
        val trimmed = ScheduleOccurrences.build(semester, listOf(course(weeks + listOf(20, 21))), emptyList())
        val plain = ScheduleOccurrences.build(semester, listOf(course(weeks)), emptyList())

        assertEquals(
            "学期外那两周被裁掉之后，交回的课次必须与「压根没有那两周」的输入逐字节相同",
            plain.occurrences.map { it.stableId },
            trimmed.occurrences.map { it.stableId },
        )
        assertEquals(
            plain.occurrences.map { it.contentHash },
            trimmed.occurrences.map { it.contentHash },
        )
        assertEquals(
            "第 1、2、17 周那三天的日期一根线都没挪（周次仍是 semesterStart.plusWeeks(w-1)）",
            listOf("2026-09-07", "2026-09-14", "2026-12-28"),
            trimmed.occurrences.map { it.date.toString() },
        )
        // 且**没有任何一条**落在学期结束之后（这就是用户在那边日历里看到的"学期后的幽灵日程"）
        val termEndExclusive = start.plusWeeks(termWeeks.toLong())
        assertTrue(
            "导出件里出现了学期结束之后的课次日期 = 导出侧没并尺：" +
                trimmed.occurrences.filter { !it.date.isBefore(termEndExclusive) }.map { it.date.toString() },
            trimmed.occurrences.all { it.date.isBefore(termEndExclusive) },
        )
    }

    /**
     * 与冲突侧/排铃侧**同进同退**：逐周摆一遍，「这一周进得了导出件」⟺「这一周放行」。
     * 这条就是卡面第 2 条要的"多处判定共用同一枚判据"的行为面证据（静态面在守卫里钉）。
     */
    @Test
    fun exportChainAsksTheSameRulerTheAlarmChainsAsk() {
        for (week in 1..(termWeeks + 4)) {
            val exported = ScheduleOccurrences
                .build(semester, listOf(course(listOf(week))), emptyList())
                .occurrences
                .isNotEmpty()
            assertEquals(
                "第 $week 周：导出判据与排铃判据必须同进同退（不同 = 导出侧另长了一把尺）",
                TermWeekGate.isInTermWeek(week, start, termWeeks),
                exported,
            )
        }
    }

    /**
     * `skipped` 那一枚数的**口径不许被本卡挪走**：它数的是"节次时间在作息表里找不到"的课次，
     * 学期外被裁掉的那些周不进这一数（否则 ICS 那句「N 个课次因节次时间缺失被跳过」会开始说谎）。
     */
    @Test
    fun trimmedWeeksAreNotReportedAsMissingSlotSkips() {
        val build = ScheduleOccurrences.build(semester, listOf(course(listOf(1, 20, 21))), emptyList())
        assertEquals("界内那一周照常展开", 1, build.occurrences.size)
        assertEquals("学期外那两周**不**进 skipped 那一数（它专指缺节次时间）", 0, build.skipped)
    }
}

/** 从日期反推教学周（只用于把断言写成"进导出件的那几周"这种可读形状）。 */
private object ChronoWeek {
    fun weekIndexOf(semesterStart: LocalDate, date: LocalDate): Int =
        (java.time.temporal.ChronoUnit.DAYS.between(WeekCalculator.mondayOf(semesterStart), date) / 7).toInt() + 1
}
