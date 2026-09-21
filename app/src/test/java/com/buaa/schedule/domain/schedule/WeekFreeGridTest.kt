package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WeekFreeGrid] 的逐周判定口径（统计页 7 × 节次热力格的判据内核）。
 *
 * 这张图最容易骗人的地方就是"把全学期并集当这一周"，所以主钉一件事：
 * **一门 1-8 周的课在第 12 周不该亮着**。另外几枚钉子守着退路：
 * 当前周拿不到时退成并集口径并显式标出来（weekUnresolved）、
 * 作息表缺节次时那些格子要能被界面说出来而不是凭空消失、
 * 以及"整周都没课"不许被说成"周一最空"。
 */
class WeekFreeGridTest {

    private val semester = Semester(
        termCode = "2026-2027-1",
        termName = "2026-2027-1",
        startDate = "2026-09-07",
        totalWeeks = 16,
    )

    /** 默认作息 14 节；这里刻意传空表，顺带钉住"作息兜底"这一条 */
    private fun course(
        dayOfWeek: Int,
        periods: List<Int>,
        weeks: List<Int> = (1..16).toList(),
        name: String = "课",
    ) = Course(
        name = name,
        dayOfWeek = dayOfWeek,
        periods = periods,
        weeks = weeks,
        semesterCode = semester.termCode,
    )

    private fun dayOf(grid: WeekFreeGrid.Grid, dayOfWeek: Int, period: Int): Boolean {
        val row = grid.rows.firstOrNull { it.period == period }
        assertEquals("节次 $period 应该出现在行轴上", true, row != null)
        return row!!.occupiedDays[dayOfWeek - 1]
    }

    // ---- 逐周判定：这张图的立身之本 ----

    @Test
    fun courseOutsideGivenWeekDoesNotOccupyCell() {
        val courses = listOf(course(dayOfWeek = 1, periods = listOf(1, 2), weeks = (1..8).toList()))

        val week3 = WeekFreeGrid.gridOf(courses, semester, emptyList(), week = 3)
        assertTrue("第 3 周周一第 1 节有课", dayOf(week3, 1, 1))
        assertTrue(dayOf(week3, 1, 2))
        assertFalse("第 3 周周二没课", dayOf(week3, 2, 1))

        val week12 = WeekFreeGrid.gridOf(courses, semester, emptyList(), week = 12)
        assertEquals("1-8 周的课在第 12 周不该占任何格子", 0, week12.occupiedCellCount)
        assertTrue("第 12 周整周没课", week12.emptyWeek)
        assertEquals(week12.cellCount, week12.freeCellCount)
    }

    @Test
    fun singleWeekParityCourseOccupiesOnlyItsWeeks() {
        val courses = listOf(course(dayOfWeek = 2, periods = listOf(3), weeks = listOf(1, 3, 5)))

        val grid = WeekFreeGrid.gridOf(courses, semester, emptyList(), week = 3)
        assertTrue(dayOf(grid, 2, 3))
        val evenWeek = WeekFreeGrid.gridOf(courses, semester, emptyList(), week = 4)
        assertFalse("双周那周不该亮", dayOf(evenWeek, 2, 3))
    }

    @Test
    fun missingCurrentWeekFallsBackToSemesterUnionAndIsFlagged() {
        val courses = listOf(course(dayOfWeek = 1, periods = listOf(1, 2), weeks = (1..8).toList()))

        val union = WeekFreeGrid.gridOf(courses, semester, emptyList(), week = null)
        assertTrue("并集口径下这门课仍然占格", dayOf(union, 1, 1))
        assertTrue("界面必须知道这不是『这一周』的图", union.weekUnresolved)
        assertNull(union.week)

        val outOfRange = WeekFreeGrid.gridOf(courses, semester, emptyList(), week = 99)
        assertTrue("周次越出学期范围同样按并集口径出图", outOfRange.weekUnresolved)
        assertTrue(dayOf(outOfRange, 1, 1))
    }

    // ---- 行轴 / 分母 ----

    @Test
    fun emptyTimeSlotListFallsBackToDefaultProfile() {
        val grid = WeekFreeGrid.gridOf(emptyList(), semester, emptyList(), week = 1)

        assertEquals("默认作息 14 节", (1..14).toList(), grid.rows.map { it.period })
        grid.rows.forEach { row -> assertEquals(7, row.occupiedDays.size) }
        assertEquals(14 * 7, grid.cellCount)
        assertTrue(grid.rows.all { it.freeAcrossWeek })
        assertEquals((1..14).toList(), grid.freePeriods)
    }

    @Test
    fun customTimeSlotsDefineTheRowAxis() {
        val slots = listOf(
            TimeSlot(number = 1, startTime = "08:00", endTime = "08:45"),
            TimeSlot(number = 2, startTime = "09:00", endTime = "09:45"),
            TimeSlot(number = 4, startTime = "10:00", endTime = "10:45"),
        )

        val grid = WeekFreeGrid.gridOf(listOf(course(1, listOf(2))), semester, slots, week = 1)

        assertEquals("行轴只用作息表里解析得出时间的节次，且按节次号升序", listOf(1, 2, 4), grid.rows.map { it.period })
        assertTrue(dayOf(grid, 1, 2))
        assertEquals(3 * 7, grid.cellCount)
    }

    @Test
    fun periodMissingFromProfileIsCountedNotSilentlyDropped() {
        val slots = listOf(TimeSlot(number = 1, startTime = "08:00", endTime = "08:45"))
        val grid = WeekFreeGrid.gridOf(
            listOf(course(1, listOf(1, 9)), course(2, listOf(9))),
            semester,
            slots,
            week = 1,
        )

        assertEquals("作息表里没有的第 9 节画不出来，但得让界面说\"另有 N 格\"", 2, grid.offProfilePeriodCount)
        assertEquals(1, grid.occupiedCellCount)
        assertEquals(listOf(1), grid.rows.map { it.period })
    }

    @Test
    fun sameCellTwiceStillCountsAsOne() {
        val grid = WeekFreeGrid.gridOf(
            listOf(course(1, listOf(3), name = "A"), course(1, listOf(3), name = "B")),
            semester,
            emptyList(),
            week = 1,
        )

        assertEquals("两门课撞同一格只占一格", 1, grid.occupiedCellCount)
        assertEquals(1, grid.occupiedByDay.first())
    }

    // ---- 结论行：哪天最空、哪些时段全周空 ----

    @Test
    fun freeDayReportsEmptiestBusyAwareDay() {
        val courses = listOf(
            course(1, listOf(1, 2)),
            course(3, listOf(1)),
            course(5, listOf(1, 2, 3)),
        )

        val grid = WeekFreeGrid.gridOf(courses, semester, emptyList(), week = 1)

        assertEquals("有空的一天里取星期序号最小的", 2, grid.freeDayOfWeek)
        assertEquals(listOf(2, 4, 6, 7), (1..7).filter { grid.occupiedByDay[it - 1] == 0 })
    }

    @Test
    fun freeDayIsNullWhenWholeWeekIsEmpty() {
        val grid = WeekFreeGrid.gridOf(emptyList(), semester, emptyList(), week = 12)

        assertTrue(grid.emptyWeek)
        assertNull("整周都没课时\"最空的一天\"没有信息量", grid.freeDayOfWeek)
    }

    @Test
    fun everyPeriodUsedLeavesNoFreePeriod() {
        val all = (1..14).toList()
        val grid = WeekFreeGrid.gridOf(
            listOf(course(1, all), course(2, all), course(3, all), course(4, all), course(5, all), course(6, all), course(7, all)),
            semester,
            emptyList(),
            week = 1,
        )

        assertEquals(14 * 7, grid.occupiedCellCount)
        assertEquals(0, grid.freeCellCount)
        assertTrue(grid.freePeriods.isEmpty())
        assertNull(grid.freeDayOfWeek)
    }

    @Test
    fun freePeriodsListTheWeekWideGaps() {
        val courses = listOf(course(1, listOf(1, 2)), course(5, listOf(2, 3)))

        val grid = WeekFreeGrid.gridOf(courses, semester, emptyList(), week = 1)

        // 第 1 节周二..周日空、周一有课 → 不算"整周空"；第 4 节起整周没人占
        assertEquals((4..14).toList(), grid.freePeriods)
        assertFalse(grid.rows.first { it.period == 2 }.freeAcrossWeek)
    }

    // ---- 脏数据 ----

    @Test
    fun dirtyDayOfWeekIsSkippedWithoutCrashing() {
        val grid = WeekFreeGrid.gridOf(
            listOf(course(0, listOf(1)), course(8, listOf(2)), course(1, listOf(3))),
            semester,
            emptyList(),
            week = 1,
        )

        assertEquals(1, grid.occupiedCellCount)
        assertEquals(7, grid.occupiedByDay.size)
    }

    @Test
    fun noSemesterRowStillDrawsAxisFromData() {
        val grid = WeekFreeGrid.gridOf(
            listOf(course(1, listOf(1), weeks = listOf(3, 9))),
            semester = null,
            timeSlots = emptyList(),
            week = 9,
        )

        assertEquals("没有学期行时横轴长度用数据里出现过的最大周次（与 SemesterStats 同口径）", 9, grid.totalWeeks)
        assertFalse(grid.weekUnresolved)
        assertTrue(dayOf(grid, 1, 1))
    }

    // ---- 转置视图（T51③ 热力格界面按"周几 × 节次"画，取的就是这一层） ----

    @Test
    fun dayRowsFlipTheSameMatrixWithoutRecomputingOccupancy() {
        val grid = WeekFreeGrid.gridOf(
            listOf(course(1, listOf(1, 2), weeks = (1..8).toList()), course(3, listOf(2), weeks = (1..16).toList())),
            semester,
            emptyList(),
            week = 3,
        )

        val dayRows = grid.dayRows
        assertEquals("恒 7 项，下标 0 = 周一", 7, dayRows.size)
        assertEquals(1, dayRows.first().dayOfWeek)
        // 逐格与主口径（rows 的 occupiedDays）核对，防转置时把某个索引写反
        dayRows.forEach { day ->
            day.occupiedPeriods.forEachIndexed { periodIndex, occupied ->
                assertEquals(
                    "周${day.dayOfWeek} 第 $periodIndex 列不该在两个视图里给出不同答案",
                    grid.rows[periodIndex].occupiedDays[day.dayOfWeek - 1],
                    occupied,
                )
            }
        }
        assertTrue(dayRows.first().occupiedPeriods.first())
        assertEquals(2, dayRows.first().occupiedCount)
        assertEquals(0, dayRows[1].occupiedCount)
        assertEquals("周一的 freeCount = 总节次数 - 占用数", grid.rows.size - 2, dayRows.first().freeCount)
    }

    @Test
    fun aFullyFreeDayAndAFullyBusyWeekBothReadTheirOwnRow() {
        val grid = WeekFreeGrid.gridOf(
            listOf(course(1, listOf(1), weeks = (1..16).toList())),
            semester,
            emptyList(),
            week = 3,
        )

        val freeDay = grid.dayRows.first { it.dayOfWeek == 5 }
        assertTrue("周五整天空", freeDay.isFree)
        assertEquals(grid.rows.size, freeDay.freeCount)
        assertTrue(grid.dayRows.first { it.dayOfWeek == 1 }.occupiedPeriods.first())
    }

    @Test
    fun allCellsOccupiedKeepsEveryRowBusyAndGivesNoEmptiestDay() {
        // 极端档：全部格子都被占（默认作息 14 节 × 7 天）——不许崩、也不许选出"最空的一天"
        val everyDay = (1..7).map { course(it, (1..14).toList()) }
        val grid = WeekFreeGrid.gridOf(everyDay, semester, emptyList(), week = 2)

        assertTrue(grid.dayRows.all { it.occupiedCount == grid.rows.size && !it.isFree })
        assertEquals(grid.cellCount, grid.occupiedCellCount)
        assertNull("七天排得一模一样时不下『最空的是周几』的结论", grid.freeDayOfWeek)
    }
}
