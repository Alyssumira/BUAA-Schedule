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
 * [WeeklyLoadTrend] 的跨周聚合口径（统计页折线/面积图的判据内核）。
 *
 * 主钉三件事：
 * 1. 只上 1-8 周的课在第 9 周之后贡献 0 —— 这是这张图存在的理由
 *    （`SemesterStats.dayLoads` 的全学期平均会把这半件事抹平）；
 * 2. **结课周按门计、不按片段计**，且学期最后一周不标旗（那是学期结束，不是结课）；
 * 3. 作息表查不到时长的那一格贡献 0 但会被计数，界面得能说"这条线偏低"。
 */
class WeeklyLoadTrendTest {

    private val semester = Semester(
        termCode = "2026-2027-1",
        termName = "2026-2027-1",
        startDate = "2026-09-07",
        totalWeeks = 16,
    )

    /** 默认作息里第 1、2 节各 45 分钟 */
    private fun course(
        name: String,
        groupKey: String? = null,
        dayOfWeek: Int = 1,
        periods: List<Int> = listOf(1, 2),
        weeks: List<Int> = (1..16).toList(),
    ) = Course(
        name = name,
        dayOfWeek = dayOfWeek,
        periods = periods,
        weeks = weeks,
        sourceGroupKey = groupKey,
        semesterCode = semester.termCode,
    )

    private fun trendOf(courses: List<Course>, currentWeek: Int? = 1, slots: List<TimeSlot> = emptyList()) =
        WeeklyLoadTrend.trendOf(courses, semester, slots, currentWeek)

    // ---- 基本形状 ----

    @Test
    fun emptyCourseListIsNoDataNotAFloorLine() {
        val trend = trendOf(emptyList())

        assertEquals(16, trend.totalWeeks)
        assertTrue("没有课 = 无数据，界面不许画成\"整学期一节课都没有\"", trend.isEmpty)
        assertFalse(trend.hasAnyWeeksData)
        assertNull(trend.peakWeek)
        // 无数据时 freeWeeks 是"全周都是 0 分钟"，界面必须先查 isEmpty 再谈"哪一周空"
        assertEquals((1..16).toList(), trend.freeWeeks)
    }

    @Test
    fun fullSemesterCourseGivesFlatLine() {
        val trend = trendOf(listOf(course("高数", "g1", periods = listOf(1, 2))))

        assertEquals(16, trend.minutes.size)
        assertTrue(trend.minutes.all { it == 90L })
        assertEquals("并列取周号最小的", 1, trend.peakWeek)
        assertEquals(90L, trend.peakMinutes)
        assertEquals(90L, trend.averageMinutes)
        assertTrue(trend.freeWeeks.isEmpty())
        assertTrue("整学期都上的课没有\"结课周\"可标", trend.endings.isEmpty())
    }

    @Test
    fun halfSemesterCourseCollapsesAfterItEnds() {
        val trend = trendOf(
            listOf(
                // 周一 1-2 节（90 分钟）只上到第 8 周，周四第 3 节（45 分钟）整学期
                course("上半程", "a", dayOfWeek = 1, periods = listOf(1, 2), weeks = (1..8).toList()),
                course("整学期", "b", dayOfWeek = 4, periods = listOf(3)),
            ),
        )

        assertEquals("前 8 周两门都在", listOf(135L), trend.minutes.take(8).distinct())
        assertEquals("第 9 周起塌回剩下那一门的 45 分钟", listOf(45L), trend.minutes.takeLast(8).distinct())
        assertEquals("最忙的是还有两门在上的第 1 周", 1, trend.peakWeek)
        assertEquals(8, trend.endings.keys.single())
        assertEquals(1, trend.endings[8])
        assertEquals(135L, trend.peakMinutes)
    }

    @Test
    fun weeksWithoutAnyCourseAreReportedFree() {
        val trend = trendOf(listOf(course("短学期", "a", weeks = (1..3).toList())))

        assertEquals(listOf(1, 2, 3), (1..16).filter { trend.minutes[it - 1] > 0L })
        assertEquals((4..16).toList(), trend.freeWeeks)
        assertEquals("平均值只按有课的周算，不被空周摊薄", 90L, trend.averageMinutes)
        assertEquals(mapOf(3 to 1), trend.endings)
    }

    // ---- 结课周 ----

    @Test
    fun endingsCountCoursesNotFragments() {
        // 同一门课的两个片段：1-8 周理论 + 9-12 周实验 → 结课周是第 12 周，只算一门
        val trend = trendOf(
            listOf(
                course("综合实践", "g", weeks = (1..8).toList()),
                course("综合实践", "g", weeks = (9..12).toList(), dayOfWeek = 3),
                course("另起一段但没组键", null, weeks = (1..5).toList(), dayOfWeek = 4),
            ),
        )

        assertEquals(mapOf(12 to 1, 5 to 1), trend.endings)
    }

    @Test
    fun lastWeekOfSemesterIsNotMarkedAsEnding() {
        val trend = trendOf(listOf(course("整学期", "z", weeks = (1..16).toList())))

        assertTrue("第 16 周结课 = 学期结束，标旗是给每份课表加假旗子", trend.endings.isEmpty())
    }

    @Test
    fun endingsAreOrderedByWeekForDrawing() {
        val trend = trendOf(
            listOf(
                course("晚结课", "b", weeks = (1..12).toList()),
                course("早结课", "a", weeks = (1..4).toList()),
                course("中结课", "c", weeks = (1..8).toList()),
            ),
        )

        assertEquals(listOf(4, 8, 12), trend.endings.keys.toList())
    }

    // ---- 分钟口径 ----

    @Test
    fun periodMissingFromProfileContributesZeroButIsCounted() {
        val slots = listOf(TimeSlot(number = 1, startTime = "08:00", endTime = "08:45"))
        val trend = trendOf(listOf(course("第 9 节不存在", "a", periods = listOf(1, 9))), slots = slots)

        assertEquals("查不到时长的节次按 0 计", listOf(45L), trend.minutes.distinct())
        assertEquals("第 9 节这一格没算进分钟，但被数出来了", 1, trend.unschedulablePeriodCellCount)
    }

    @Test
    fun outOfRangeWeeksAreClippedAndDirtyDaysSkipped() {
        val trend = trendOf(
            listOf(
                course("周次越界", "a", weeks = (1..20).toList()),
                course("脏星期", "b", dayOfWeek = 0),
                course("脏星期八", "c", dayOfWeek = 8),
            ),
        )

        assertEquals(16, trend.minutes.size)
        assertEquals("越界的 17-20 周不撑长横轴", listOf(90L), trend.minutes.distinct())
    }

    @Test
    fun overlapsInsideOneWeekAllAddUp() {
        val trend = trendOf(
            listOf(
                course("A", "a", dayOfWeek = 1, periods = listOf(1, 2)),
                course("B", "b", dayOfWeek = 1, periods = listOf(3)),
            ),
        )

        assertEquals(135L, trend.minutes.first())
    }

    /** 三张图共用一套分钟口径：全学期都排的课，逐周折线之和 == 日负载平均值 × 周数 */
    @Test
    fun agreesWithSemesterStatsWhenEveryCourseSpansFullSemester() {
        val courses = listOf(
            course("A", "a", dayOfWeek = 1, periods = listOf(1)),
            course("B", "b", dayOfWeek = 3, periods = listOf(2, 3)),
        )

        val trend = trendOf(courses)
        val summary = SemesterStats.summarize(courses, semester, emptyList())

        assertEquals(16, summary.weekCount)
        assertEquals(
            "两条路算出来的学期总量必须相等，否则图与文字会各说一套",
            summary.dayLoads.sumOf { it.averageMinutes } * summary.weekCount,
            trend.minutes.sum(),
        )
    }

    // ---- 当前周 ----

    @Test
    fun currentWeekPassesThroughAndOutOfRangeBecomesNull() {
        val trend = trendOf(listOf(course("A", "a")), currentWeek = 6)
        assertEquals(6, trend.currentWeek)
        assertNull(trendOf(listOf(course("A", "a")), currentWeek = null).currentWeek)
        assertNull("越界的当前周不画游标", trendOf(listOf(course("A", "a")), currentWeek = 99).currentWeek)
    }

    @Test
    fun missingSemesterUsesMaxWeekInDataAsAxis() {
        val trend = WeeklyLoadTrend.trendOf(
            listOf(course("无学期行", "a", weeks = listOf(1, 2, 3))),
            semester = null,
            timeSlots = emptyList(),
            currentWeek = 2,
        )

        assertEquals(3, trend.totalWeeks)
        assertEquals(listOf(90L, 90L, 90L), trend.minutes)
        assertEquals("第 3 周是这份数据的终点，不标结课旗", emptyMap<Int, Int>(), trend.endings)
    }
}
