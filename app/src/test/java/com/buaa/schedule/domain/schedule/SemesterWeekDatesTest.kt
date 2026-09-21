package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Semester
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 教学周序号 → 那一周的周一（T56）。
 *
 * 学期起点 2026-08-31 是周一，第 3 周必须是 9/14——装机实测里网格表头写的就是它，
 * 顶栏第二行当时却写着 9/21（第 4 周的周一）。钉的是这条换算本身，
 * 以及「开学日期不是周一要先归一」「无解时返回 null 让调用方降级」两条边界。
 */
class SemesterWeekDatesTest {

    private val mondayStart = LocalDate.of(2026, 8, 31)

    @Test
    fun `周号按整周往前推`() {
        val cases = listOf(
            1 to LocalDate.of(2026, 8, 31),
            2 to LocalDate.of(2026, 9, 7),
            3 to LocalDate.of(2026, 9, 14),
            4 to LocalDate.of(2026, 9, 21),
            16 to LocalDate.of(2026, 12, 14),
            // 跨年学期：北航秋季学期正是这样一路排到次年 1 月
            20 to LocalDate.of(2027, 1, 11),
            25 to LocalDate.of(2027, 2, 15),
        )
        for ((week, expected) in cases) {
            assertEquals("第${week}周", expected, SemesterWeekDates.mondayOf(mondayStart, week))
        }
    }

    @Test
    fun `开学日期不是周一时先归一到所在自然周`() {
        // 与 WeekCalculator.mondayOf 同口径：周三开学的第 1 周仍从那个周一算起
        val wednesdayStart = mondayStart.plusDays(2)
        for (offset in 0L..4L) {
            assertEquals(
                "起点偏移 $offset 天",
                mondayStart,
                SemesterWeekDates.mondayOf(mondayStart.plusDays(offset), 1),
            )
        }
        // 周日是一周的最后一天，要往前退 6 天回到**上一个**周一（不能 nextOrPrevious）
        assertEquals(mondayStart.minusWeeks(1), SemesterWeekDates.mondayOf(mondayStart.minusDays(1), 1))
        assertEquals(
            mondayStart.minusWeeks(1).plusWeeks(2),
            SemesterWeekDates.mondayOf(mondayStart.minusDays(1), 3),
        )
    }

    @Test
    fun `第 0 周与负数周没有对应日期`() {
        assertNull(SemesterWeekDates.mondayOf(mondayStart, 0))
        assertNull(SemesterWeekDates.mondayOf(mondayStart, -3))
    }

    @Test
    fun `学期与周号缺任一个都算不出来`() {
        val semester = semesterStartingOn(mondayStart)
        assertNull(SemesterWeekDates.mondayOf(semester, null))
        assertNull(SemesterWeekDates.mondayOf(null, 3))
        // 库里留有用户手写的脏日期：按「未设置学期」降级，不抛
        assertNull(SemesterWeekDates.mondayOf(semester.copy(startDate = "2026/08/31"), 3))
        assertNull(SemesterWeekDates.mondayOf(semester.copy(startDate = "不是日期"), 3))
    }

    @Test
    fun `整周区间是周一到周日那七天`() {
        val semester = semesterStartingOn(mondayStart)
        val (monday, sunday) = SemesterWeekDates.spanOf(semester, 3)!!
        assertEquals(LocalDate.of(2026, 9, 14), monday)
        assertEquals(LocalDate.of(2026, 9, 20), sunday)
        assertNull(SemesterWeekDates.spanOf(semester, 0))
        assertNull(SemesterWeekDates.spanOf(null, 3))
    }

    private fun semesterStartingOn(start: LocalDate) = Semester(
        termCode = "2026-2027-1",
        termName = "2026秋季",
        startDate = start.toString(),
        totalWeeks = 20,
    )
}
