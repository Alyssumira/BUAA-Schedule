package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleOccurrencesTest {

    private val semester = Semester(
        termCode = "T",
        termName = "T",
        startDate = "2026-09-07",
        totalWeeks = 19,
    )

    private fun course(
        location: String? = "J3-101",
        periods: List<Int> = listOf(1, 2),
        weeks: List<Int> = listOf(1, 3),
    ) = Course(
        name = "高数",
        teacher = "张三",
        location = location,
        dayOfWeek = 1,
        periods = periods,
        weeks = weeks,
    )

    @Test
    fun stableIdAndHashAreDeterministic() {
        val first = ScheduleOccurrences.build(semester, listOf(course()), emptyList())
        val second = ScheduleOccurrences.build(semester, listOf(course()), emptyList())

        assertEquals(first.occurrences.map { it.stableId }, second.occurrences.map { it.stableId })
        assertEquals(first.occurrences.map { it.contentHash }, second.occurrences.map { it.contentHash })
        assertEquals(2, first.occurrences.size)
        assertEquals(0, first.skipped)
    }

    @Test
    fun contentHashChangesWhenCourseContentChanges() {
        val original = ScheduleOccurrences.build(semester, listOf(course()), emptyList())
        val moved = ScheduleOccurrences.build(semester, listOf(course(location = "J5-202")), emptyList())

        assertEquals(original.occurrences.map { it.stableId }, moved.occurrences.map { it.stableId })
        assertNotEquals(original.occurrences.map { it.contentHash }, moved.occurrences.map { it.contentHash })
    }

    @Test
    fun occurrenceDatesFollowWeekAndDay() {
        // 第 1 周周一、第 3 周周一
        val build = ScheduleOccurrences.build(semester, listOf(course()), emptyList())
        val dates = build.occurrences.map { it.date.toString() }

        assertEquals(listOf("2026-09-07", "2026-09-21"), dates)
    }

    @Test
    fun missingSlotTimeIsSkippedAndCounted() {
        val slots = listOf(TimeSlot(number = 1, startTime = "08:00", endTime = "08:45"))
        val build = ScheduleOccurrences.build(
            semester,
            listOf(course(periods = listOf(1, 9), weeks = listOf(1))),
            slots,
        )

        // 第 1 节可用、第 9 节缺时间 → 1 个课次 + 1 个跳过
        assertEquals(1, build.occurrences.size)
        assertEquals(1, build.skipped)
    }

    @Test
    fun invalidSemesterStartYieldsNothing() {
        val build = ScheduleOccurrences.build(
            semester.copy(startDate = "bad"),
            listOf(course()),
            emptyList(),
        )
        assertTrue(build.occurrences.isEmpty())
    }

    @Test
    fun stableIdDoesNotCollideWhenIdentityDiffersOnlyAfterTruncation() {
        // 两门课的身份键前缀完全相同、差异落在被截断的部分之后。
        // 此前实现直接 take(80)，两者会得到同一个 UID（日历映射互相覆盖）；
        // 现在拼了内容摘要，必须能区分开。
        val longName = "超长课程名称".repeat(12)
        val first = course(weeks = listOf(1)).copy(name = longName + "甲")
        val second = course(weeks = listOf(1)).copy(name = longName + "乙")

        val firstId = ScheduleOccurrences.stableIdFor(first, week = 1, segment = 1..2)
        val secondId = ScheduleOccurrences.stableIdFor(second, week = 1, segment = 1..2)

        assertNotEquals(firstId, secondId)
        // 同一门课重复生成必须稳定
        assertEquals(secondId, ScheduleOccurrences.stableIdFor(second, week = 1, segment = 1..2))
    }
}
