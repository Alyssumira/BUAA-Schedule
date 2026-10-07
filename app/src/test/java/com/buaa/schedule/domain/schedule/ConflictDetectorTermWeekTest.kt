package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.ui.home.ConflictShiftWeekScope
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T222 病灶 1 + 2（#306）冲突侧的表驱动格子：`findConflicts` 现在吃学期两枚事实。
 *
 * 卡面第 3 条要求「表驱动两侧各摆格子」，冲突侧这一枚摆的是
 * **界内 / 越上界 / 越下界 / 空周次 / 无学期行 / `totalWeeks` 取不到** 六档，
 * 外加冲突侧独有的两档：**「只在学期外那一周撞」（改后必须不再报冲突）** 与
 * **「界内撞」**（改后必须照报，而且交回的周次串就是那几周）。
 *
 * 判据本体不在本文件里写第二遍：裁周次走 [TermWeekGate.weeksInsideTerm]，
 * 而它逐周委托 [TermWeekGate.isInTermWeek]，最终仍然只认
 * `WeekCalculator.currentWeekOrNull(...) == week` 那一枚比较式（守卫第 ①②③ 层钉着）。
 *
 * 与 `ConflictDetectorTest` 的分工：那枚钉的是**改前就在的那些格**（两两配对、同日、节次重叠、
 * 自己撞自己），本卡只给它补两枚 null 实参、一格没删 ⇒ 那是"判据没被重写"的证据；
 * 本文件摆的是**学期这一维**的格子。
 */
class ConflictDetectorTermWeekTest {

    /** 学期第 1 周周一 */
    private val start = LocalDate.of(2026, 9, 7)
    private val termWeeks = 19

    private fun course(id: Long, weeks: List<Int>, day: Int = 1, periods: List<Int> = listOf(1, 2)) = Course(
        id = id,
        name = "C$id",
        dayOfWeek = day,
        periods = periods,
        weeks = weeks,
    )

    private data class Cell(
        val name: String,
        val firstWeeks: List<Int>,
        val secondWeeks: List<Int>,
        val semesterStart: LocalDate?,
        val totalWeeks: Int?,
        /** 期望交回的那一串周次；空表 = 这一对根本不报冲突 */
        val expectedWeeks: List<Int>,
    )

    private val cells = listOf(
        // ① 界内
        Cell(
            "界内：整学期都撞 ⇒ 19 周逐周交回（上界取闭区间，与展示侧同一档）",
            (1..termWeeks).toList(), (1..termWeeks).toList(), start, termWeeks, (1..termWeeks).toList(),
        ),
        Cell("界内那一周的边界：第 19 周而学期正好 19 周 ⇒ 照报", listOf(19), listOf(19), start, termWeeks, listOf(19)),
        Cell("界内非边界：第 1 周 ⇒ 照报", listOf(1), listOf(1), start, termWeeks, listOf(1)),
        // ② 越上界（病灶那一格）
        Cell(
            "只在学期外那一周撞：两门课只在第 20 周（学期 19 周）撞 ⇒ 改后不再报冲突",
            listOf(20), listOf(20), start, termWeeks, emptyList(),
        ),
        Cell(
            "越上界混着界内：只在界内那三周报冲突，20/21 从交回的周次串里消失（向导「只改这些周」吃的就是这一串）",
            listOf(1, 2, 17, 20, 21), listOf(1, 2, 17, 20, 21), start, termWeeks, listOf(1, 2, 17),
        ),
        Cell(
            "一门课越上界不连坐：A 的 20 周被裁掉后与 B 仍在第 1、2 周相认",
            listOf(1, 2, 20), listOf(1, 2), start, termWeeks, listOf(1, 2),
        ),
        Cell("极端越界（备份里用户可控）：第 400 周 ⇒ 不报", listOf(400), listOf(400), start, termWeeks, emptyList()),
        // ③ 越下界
        Cell("第 0 周 ⇒ 不报（屏上压根没有第 0 周）", listOf(0), listOf(0), start, termWeeks, emptyList()),
        Cell("负周次（脏数据）⇒ 不报", listOf(-3), listOf(-3), start, termWeeks, emptyList()),
        Cell("越下界混着界内：只交回第 3 周", listOf(-1, 3), listOf(3), start, termWeeks, listOf(3)),
        // ④ 空周次
        Cell("两门都没有周次 ⇒ 不报（与改前同：交集本就是空）", emptyList(), emptyList(), start, termWeeks, emptyList()),
        // ⑤ 无学期行（semesterStart = null ⇒ 判不出 ⇒ **不裁**，与排铃侧故意不对称，理由见 findConflicts 的 KDoc）
        Cell(
            "无学期行：第 20 周的冲突照报 —— 没有学期行时周课表走 showAllCoursesWithoutWeek 全画，那一周确实在屏上",
            listOf(20), listOf(20), null, termWeeks, listOf(20),
        ),
        Cell(
            "无学期行时越下界也照报（判不出就一律不动原样，不是「只裁上界」）",
            listOf(0, 5), listOf(0, 5), null, termWeeks, listOf(0, 5),
        ),
        // ⑥ totalWeeks 取不到
        Cell(
            "totalWeeks 取不到（没有学期行可问）⇒ 不裁，第 20 周照报",
            listOf(20), listOf(20), start, null, listOf(20),
        ),
        // 尺跟着学期走：同一枚周次在更长的学期里回到界内（防"把 19 硬写进判据"）
        Cell(
            "同一周次、学期 20 周 ⇒ 回到界内：上界是递进来的那枚数，不是硬写的 19",
            listOf(20), listOf(20), start, 20, listOf(20),
        ),
    )

    @Test
    fun `六档格子逐条对账`() {
        for (cell in cells) {
            val conflicts = ConflictDetector.findConflicts(
                listOf(course(1L, cell.firstWeeks), course(2L, cell.secondWeeks)),
                cell.semesterStart,
                cell.totalWeeks,
            )
            assertEquals(
                "${cell.name}（A=${cell.firstWeeks} B=${cell.secondWeeks} start=${cell.semesterStart} totalWeeks=${cell.totalWeeks}）" +
                    " —— 交回的周次串不对就是**冲突侧没并尺**（或裁错了方向）",
                cell.expectedWeeks,
                conflicts.firstOrNull()?.weeks ?: emptyList<Int>(),
            )
            assertEquals(
                "${cell.name} —— 配对条数（空表那档必须一整对都不报）",
                if (cell.expectedWeeks.isEmpty()) 0 else 1,
                conflicts.size,
            )
        }
    }

    /**
     * 交回的周次串必须**升序**：向导行头那句「第 N 周」按 `joinToString(",")` 原样印它，
     * 而 `ConflictShiftWeekScope.weeksToShift` 拿它去拆行写库 —— 顺序漂了用户看到的是乱序周次。
     */
    @Test
    fun keptWeeksStaySortedAscending() {
        val weeks = listOf(21, 2, 17, 5, 20, 1)
        val conflicts = ConflictDetector.findConflicts(
            listOf(course(1L, weeks), course(2L, weeks)),
            start,
            termWeeks,
        )
        assertEquals(
            "越界的 20/21 必须不在场（在场 = 冲突侧那把尺没接到学期）",
            0,
            conflicts.flatMap { it.weeks }.count { !TermWeekGate.isInTermWeek(it, start, termWeeks) },
        )
        assertEquals(listOf(1, 2, 5, 17), conflicts.single().weeks)
    }

    /**
     * 病灶那一格的最硬证据：**冲突侧与排铃侧是同一把尺**。
     * 把 1..(totalWeeks + 4) 每一周都摆成"两门课只在这一周撞"，
     * 冲突报不报必须与 [TermWeekGate.isInTermWeek] 逐周同进同退 ——
     * 屏上画得出的那一周才报冲突，画不出的那一周连向导都不摆它。
     */
    @Test
    fun conflictSideAsksTheSameRulerTheAlarmChainsAsk() {
        for (week in 1..(termWeeks + 4)) {
            val reported = ConflictDetector.findConflicts(
                listOf(course(1L, listOf(week)), course(2L, listOf(week))),
                start,
                termWeeks,
            ).isNotEmpty()
            assertEquals(
                "第 $week 周：冲突判定与排铃判据必须同进同退（不同 = 冲突侧另长了一把尺）",
                TermWeekGate.isInTermWeek(week, start, termWeeks),
                reported,
            )
        }
    }

    /**
     * 向导写库那一路（卡面点名的"更重的一格"）：交回给 `groupConflicts` 的那一串组周
     * 就是 `ConflictShiftWeekScope.weeksToShift(groupWeeks, target.weeks)` 的第一个实参，
     * 再进 `updateCourse(..., weeks = scopedWeeks, CourseSaveOptions(partialWeeks = true))`。
     * 学期外的周次必须在这一串里就消失，否则用户点「只改这些周」会把**没学过的第 20、21 周**
     * 拆成一行写进库。
     */
    @Test
    fun wizardWeekStringCarriesNoPhantomWeeks() {
        val target = course(1L, listOf(1, 2, 17, 20, 21))
        val other = course(2L, listOf(1, 2, 17, 20, 21))
        val groups = CourseConflictResolution.groupConflicts(
            ConflictDetector.findConflicts(listOf(target, other), start, termWeeks),
        )
        val group = groups.single()
        assertEquals("向导行头那一串「第 N 周」", listOf(1, 2, 17), group.weeks)
        assertTrue(
            "组周里还留着学期外的周次 = 冲突侧没并尺，那句「只改这些周」照旧按假周次写库",
            group.weeks.none { it > termWeeks },
        )
        // 顺着写库那一步再走一遍：weeksToShift 交回的同样不该有 20/21
        val scoped = ConflictShiftWeekScope.weeksToShift(group.weeks, target.weeks)
        assertEquals(listOf(1, 2, 17), scoped)
    }
}
