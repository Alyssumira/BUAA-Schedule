package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.toStartEndTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class TodayPlannerTest {

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
            timeSlots = emptyList(),
            today = today,
            now = LocalTime.of(8, 30),
            scope = DayCourseScope.TermWeek(1),
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
            timeSlots = emptyList(),
            today = today,
            now = LocalTime.of(7, 0),
            scope = DayCourseScope.TermWeek(1),
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
            timeSlots = emptyList(),
            today = today,
            now = LocalTime.of(10, 0),
            scope = DayCourseScope.TermWeek(1),
        )
        assertEquals(SlotStatus.PAST, plan.slots.single().status)
        assertNull(plan.ongoing)
        assertNull(plan.next)
    }

    @Test
    fun vacationYieldsEmptyPlan() {
        val plan = TodayPlanner.plan(
            courses = listOf(course),
            timeSlots = emptyList(),
            today = LocalDate.of(2027, 2, 1),  // 超出 20 周
            now = LocalTime.of(8, 0),
            scope = DayCourseScope.OutOfTerm,
        )
        assertEquals(0, plan.slots.size)
        assertNull(plan.ongoing)
    }

    @Test
    fun courseNotThisWeekIsExcluded() {
        val plan = TodayPlanner.plan(
            courses = listOf(course.copy(weeks = listOf(2))),  // 只第 2 周
            timeSlots = emptyList(),
            today = today, // 第 1 周
            now = LocalTime.of(8, 0),
            scope = DayCourseScope.TermWeek(1),
        )
        assertEquals(0, plan.slots.size)
    }

    @Test
    fun weekdayOnlyScopePlansThatDayByClock() {
        // 甲档（T266 的裁定）：没设学期时 Hero 与列表同吃「星期几」那把尺 ——
        // 周三那门第 1-2 节的课照样产出课次，于是"14:00 那张卡还挂着"与"今天没有更多课了"
        // 不可能再同屏。改前这一格是 `assertEquals(0, plan.slots.size)`：
        // 学期缺失时 planner 起手 `?: return EMPTY`，而列表按星期几放行同一批课（#344）
        val plan = TodayPlanner.plan(
            courses = listOf(course),
            timeSlots = emptyList(),
            today = today,
            now = LocalTime.of(8, 30),
            scope = DayCourseScope.WeekdayOnly,
        )
        assertEquals(1, plan.slots.size)
        assertEquals(SlotStatus.ONGOING, plan.slots.single().status)
    }

    @Test
    fun outOfTermScopeRefusesTheSameBatch() {
        // 同一批课、同一时刻，换一枚 scope 就一门都不排：排不排由内核那把尺说了算，
        // planner 不再自己判学期（假期 / 开学日脏数据 / totalWeeks = 0 走的就是这一档）
        val batch = listOf(course)
        val planned = TodayPlanner.plan(
            courses = batch,
            timeSlots = emptyList(),
            today = today,
            now = LocalTime.of(8, 30),
            scope = DayCourseScope.TermWeek(1),
        )
        val refused = TodayPlanner.plan(
            courses = batch,
            timeSlots = emptyList(),
            today = today,
            now = LocalTime.of(8, 30),
            scope = DayCourseScope.OutOfTerm,
        )
        assertEquals(1, planned.slots.size)
        assertEquals(0, refused.slots.size)
        assertNull(refused.ongoing)
    }

    @Test
    fun partialMinuteCountsAsOneMinute() {
        // 07:59:30 距 08:00 只剩 30 秒：按分钟向下取整会读出"0 分钟后开始"，
        // 而实况通知的 minutesLeft 是向上取整，同一节课两处差一分钟。
        val upcoming = TodayPlanner.plan(
            courses = listOf(course),
            timeSlots = emptyList(),
            today = today,
            now = LocalTime.of(7, 59, 30),
            scope = DayCourseScope.TermWeek(1),
        )
        assertEquals(1L, upcoming.minutesToNext)

        // 09:34:30 → 09:35 下课，同理不能显示"还有 0 分钟下课"
        val whileOngoing = TodayPlanner.plan(
            courses = listOf(course),
            timeSlots = emptyList(),
            today = today,
            now = LocalTime.of(9, 34, 30),
            scope = DayCourseScope.TermWeek(1),
        )
        assertEquals(1L, whileOngoing.minutesRemaining)
    }

    @Test
    fun segmentMissingEndTimeStillYieldsASlot() {
        // 节次表只配了第 1 节的下课时间：第 1-2 节这段以前在这里被 `?: continue`
        // 整段丢掉 —— Hero 上"没有这一节"，而组件与闹钟链上它清清楚楚在进行中。
        // 收敛后同一份兜底：段内更早一节的下课时间（08:45）优先，否则按 45 分钟。
        val plan = TodayPlanner.plan(
            courses = listOf(course),
            timeSlots = listOf(TimeSlot(number = 1, startTime = "08:00", endTime = "08:45")),
            today = today,
            now = LocalTime.of(8, 30),
            scope = DayCourseScope.TermWeek(1),
        )
        val slot = plan.slots.single()
        assertEquals(LocalTime.of(8, 0), slot.start)
        assertEquals(LocalTime.of(8, 45), slot.end)
        assertEquals(SlotStatus.ONGOING, slot.status)
        assertEquals(15L, plan.minutesRemaining)
    }

    @Test
    fun planRowsAgreeWithTheSingleWindowAlgorithm() {
        // 界面这一路不该再有第二套判定：Hero 的每一行必须与 PeriodWindows 逐一对得上
        val timeSlots = listOf(
            TimeSlot(number = 1, startTime = "08:00", endTime = "08:45"),
            TimeSlot(number = 2, startTime = "08:50", endTime = "09:35"),
            TimeSlot(number = 5, startTime = "11:30", endTime = "12:15"),
        )
        val now = LocalTime.of(9, 0)
        val plan = TodayPlanner.plan(
            courses = listOf(course.copy(periods = listOf(1, 2, 5))),
            timeSlots = timeSlots,
            today = today,
            now = now,
            scope = DayCourseScope.TermWeek(1),
        )
        val windows = periodWindowsOf(
            course.copy(periods = listOf(1, 2, 5)),
            today,
            timeSlots.toStartEndTimes(),
        )
        assertEquals(windows.size, plan.slots.size)
        for ((slot, window) in plan.slots.zip(windows)) {
            assertEquals(window.segment, slot.segment)
            assertEquals(window.begin.toLocalTime(), slot.start)
            assertEquals(window.end.toLocalTime(), slot.end)
            assertEquals(window.statusAt(LocalDateTime.of(today, now)), slot.status)
        }
        assertEquals(SlotStatus.ONGOING, plan.slots[0].status)   // 第 1-2 节 08:00–09:35
        assertEquals(SlotStatus.UPCOMING, plan.slots[1].status)  // 第 5 节 11:30–12:15
        assertEquals(plan.slots[0], plan.ongoing)
        assertEquals(plan.slots[1], plan.next)
        assertEquals(35L, plan.minutesRemaining)
    }
}
