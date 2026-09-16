package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class TodayPlannerTest {

    private val semester = Semester(
        termCode = "T",
        termName = "T",
        startDate = "2026-09-07",
        totalWeeks = 20,
    )
    private val today = LocalDate.of(2026, 9, 9) // 第 1 周周三
    private val course = Course(
        name = "数据结构",
        teacher = "张三",
        location = "F102",
        dayOfWeek = 3, // 周三
        periods = listOf(1, 2), // 08:00–09:35
        weeks = (1..16).toList(),
    )
    private val otherDayCourse = course.copy(name = "体育", dayOfWeek = 5, periods = listOf(6))

    @Test
    fun detectsOngoingCourse() {
        val plan = TodayPlanner.plan(
            courses = listOf(course, otherDayCourse),
            semester = semester,
            timeSlots = emptyList(),
            today = today,
            now = LocalTime.of(8, 30),
        )
        assertEquals(1, plan.slots.size)
        assertNotNull(plan.ongoing)
        assertEquals("数据结构", plan.ongoing!!.course.name)
        assertEquals(65L, plan.minutesRemaining)
        assertNull(plan.next)
    }

    @Test
    fun detectsNextCourse() {
        val plan = TodayPlanner.plan(
            courses = listOf(course),
            semester = semester,
            timeSlots = emptyList(),
            today = today,
            now = LocalTime.of(7, 0),
        )
        assertNull(plan.ongoing)
        assertNotNull(plan.next)
        assertEquals(60L, plan.minutesToNext)
        assertEquals(SlotStatus.UPCOMING, plan.slots.single().status)
    }

    @Test
    fun pastCourseIsNotOngoingOrNext() {
        val plan = TodayPlanner.plan(
            courses = listOf(course),
            semester = semester,
            timeSlots = emptyList(),
            today = today,
            now = LocalTime.of(10, 0),
        )
        assertEquals(SlotStatus.PAST, plan.slots.single().status)
        assertNull(plan.ongoing)
        assertNull(plan.next)
    }

    @Test
    fun vacationYieldsEmptyPlan() {
        val plan = TodayPlanner.plan(
            courses = listOf(course),
            semester = semester,
            timeSlots = emptyList(),
            today = LocalDate.of(2027, 2, 1), // 超出 20 周
            now = LocalTime.of(8, 0),
        )
        assertEquals(0, plan.slots.size)
        assertNull(plan.ongoing)
    }

    @Test
    fun courseNotThisWeekIsExcluded() {
        val plan = TodayPlanner.plan(
            courses = listOf(course.copy(weeks = listOf(2))), // 只第 2 周
            semester = semester,
            timeSlots = emptyList(),
            today = today, // 第 1 周
            now = LocalTime.of(8, 0),
        )
        assertEquals(0, plan.slots.size)
    }

    @Test
    fun noSemesterYieldsEmptyPlan() {
        val plan = TodayPlanner.plan(
            courses = listOf(course),
            semester = null,
            timeSlots = emptyList(),
            today = today,
            now = LocalTime.of(8, 0),
        )
        assertEquals(0, plan.slots.size)
    }
}
