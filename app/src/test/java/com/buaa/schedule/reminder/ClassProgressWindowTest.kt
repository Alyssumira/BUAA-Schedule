package com.buaa.schedule.reminder

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.TimeSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 「上课窗口」选取：必须包含**正在上的那节课**。
 *
 * 此前只找 `now` 之后的开始时间，于是上课进行中会返回再下一节 ——
 * 与"下课铃撤销本次课通知"的口径不一致，也让 2x1「下一节课」组件在上课期间显示错课。
 */
class ClassProgressWindowTest {

    private val semesterStart = LocalDate.of(2026, 9, 7) // 周一
    private val slots = listOf(
        TimeSlot(number = 1, startTime = "08:00", endTime = "08:45"),
        TimeSlot(number = 2, startTime = "08:50", endTime = "09:35"),
    )

    private fun course(weeks: List<Int> = listOf(1)) = Course(
        id = 1L,
        name = "高等数学",
        location = "J3-101",
        dayOfWeek = 1,
        periods = listOf(1, 2),
        weeks = weeks,
    )

    private fun millisOf(dateTime: LocalDateTime): Long =
        dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun returnsInProgressClassInsteadOfNextOne() {
        val now = LocalDateTime.of(2026, 9, 7, 8, 20) // 正上着 08:00-09:35

        val window = ClassProgressScheduler.planNextClassWindow(
            courses = listOf(course()),
            semesterStart = semesterStart,
            timeSlots = slots,
            now = now,
        )

        assertNotNull(window)
        val nowMillis = millisOf(now)
        assertTrue("返回的窗口应覆盖当前时刻", window!!.startMillis <= nowMillis)
        assertTrue("返回的窗口应尚未结束", nowMillis < window.endMillis)
        assertTrue("窗口结束时间应晚于开始时间", window.endMillis > window.startMillis)
    }

    @Test
    fun plansTheNextClassWhenCurrentOneEnds() {
        // 下课铃响的那一刻再排程：必须落到下一节，不能返回 null（链路就此断掉），
        // 也不能返回刚结束的那节（R5 F-12）
        val afternoon = listOf(
            TimeSlot(number = 3, startTime = "10:00", endTime = "10:45"),
            TimeSlot(number = 4, startTime = "10:50", endTime = "11:35"),
        )
        val next = course().copy(id = 2L, name = "大学物理", periods = listOf(3, 4))
        val now = LocalDateTime.of(2026, 9, 7, 9, 35) // 08:00-09:35 这节刚好下课

        val window = ClassProgressScheduler.planNextClassWindow(
            courses = listOf(course(), next),
            semesterStart = semesterStart,
            timeSlots = slots + afternoon,
            now = now,
        )

        assertNotNull("下课后续排必须找到下一节", window)
        assertEquals("大学物理", window!!.courseName)
        assertEquals(millisOf(LocalDateTime.of(2026, 9, 7, 10, 0)), window.startMillis)
        assertTrue("窗口必须还没结束", window.endMillis > millisOf(now))
    }

    @Test
    fun returnsFutureClassBeforeItStarts() {
        val now = LocalDateTime.of(2026, 9, 7, 7, 30)

        val window = ClassProgressScheduler.planNextClassWindow(
            courses = listOf(course()),
            semesterStart = semesterStart,
            timeSlots = slots,
            now = now,
        )

        assertNotNull(window)
        assertTrue(window!!.startMillis > millisOf(now))
    }

    @Test
    fun returnsNullWhenAllWeeksPassed() {
        // 第 8 天：第 1 周的周一早已结束，课程也不再排课
        val now = LocalDateTime.of(2026, 9, 8, 10, 0)

        val window = ClassProgressScheduler.planNextClassWindow(
            courses = listOf(course()),
            semesterStart = semesterStart,
            timeSlots = slots,
            now = now,
        )

        assertNull(window)
    }

    @Test
    fun picksEarliestWindowAcrossCourses() {
        val later = course(weeks = listOf(1)).copy(id = 2L, name = "大学物理", dayOfWeek = 2)
        val now = LocalDateTime.of(2026, 9, 7, 7, 30)

        val window = ClassProgressScheduler.planNextClassWindow(
            courses = listOf(later, course()),
            semesterStart = semesterStart,
            timeSlots = slots,
            now = now,
        )

        // 周一那节更早，应被选中
        assertTrue(window!!.courseName == "高等数学")
    }
}
