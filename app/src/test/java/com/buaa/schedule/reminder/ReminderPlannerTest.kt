package com.buaa.schedule.reminder

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.ReminderSetting
import com.buaa.schedule.domain.model.TimeSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class ReminderPlannerTest {

    private val semesterStart = LocalDate.of(2026, 9, 7) // 周一
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun course(id: Long, dayOfWeek: Int, startPeriod: Int) = Course(
        id = id,
        name = "课程$id",
        dayOfWeek = dayOfWeek,
        periods = listOf(startPeriod),
        weeks = (1..16).toList(),
    )

    private fun mills(dateTime: LocalDateTime): Long =
        dateTime.atZone(zone).toInstant().toEpochMilli()

    /** 周一第 1 节 = 学期第 1 周周一 08:00 */
    private val mondayFirstPeriod = LocalDateTime.of(2026, 9, 7, 8, 0)

    @Test
    fun schedulesNextCourseAfterFirstReminderFires() {
        // A 09:50 上课、提前 10 分钟（09:40 已触发）；现在 09:45：A 还没上课，
        // 但它的触发时间已过，必须跳过 A 选中 B（14:00 上课），链条不能断
        val a = course(1, dayOfWeek = 1, startPeriod = 3)  // 09:50 开始上课
        val b = course(2, dayOfWeek = 1, startPeriod = 6)  // 14:00 开始上课
        val now = LocalDateTime.of(2026, 9, 7, 9, 45)

        val plan = ReminderScheduler.planNextReminder(
            courses = listOf(a, b),
            semesterStart = semesterStart,
            timeSlots = emptyList(),
            reminders = mapOf(1L to ReminderSetting(1, true, 10)),
            now = now,
            nowMillis = mills(now),
            zone = zone,
        )

        assertEquals(2L, plan?.course?.id)
    }

    @Test
    fun ordersByTriggerTimeNotClassStartTime() {
        // A 09:50 上课但提前 180 分钟（06:50 触发），B 08:00 上课提前 10 分钟（07:50 触发）。
        // 按上课时间 B 更早，但按触发时间 A 更早，必须选 A
        val a = course(1, dayOfWeek = 1, startPeriod = 3)  // 09:50 上课
        val b = course(2, dayOfWeek = 1, startPeriod = 1)  // 08:00 上课
        val now = LocalDateTime.of(2026, 9, 7, 6, 0)

        val plan = ReminderScheduler.planNextReminder(
            courses = listOf(a, b),
            semesterStart = semesterStart,
            timeSlots = emptyList(),
            reminders = mapOf(
                1L to ReminderSetting(1, true, 180),
                2L to ReminderSetting(2, true, 10),
            ),
            now = now,
            nowMillis = mills(now),
            zone = zone,
        )

        assertEquals(1L, plan?.course?.id)
    }

    @Test
    fun skipsReminderWhoseTriggerAlreadyPassed() {
        // A 11:30 上课、提前 240 分钟（07:30 已触发）；现在 09:00：A 还没上课，
        // 但它的触发时间已过，跳过 A 选 B（15:50 上课）
        val a = course(1, dayOfWeek = 1, startPeriod = 5)  // 11:30 开始上课
        val b = course(2, dayOfWeek = 1, startPeriod = 8)  // 15:50 开始上课
        val now = LocalDateTime.of(2026, 9, 7, 9, 0)

        val plan = ReminderScheduler.planNextReminder(
            courses = listOf(a, b),
            semesterStart = semesterStart,
            timeSlots = emptyList(),
            reminders = mapOf(1L to ReminderSetting(1, true, 240)),
            now = now,
            nowMillis = mills(now),
            zone = zone,
        )

        assertEquals(2L, plan?.course?.id)
    }

    @Test
    fun skipsDisabledReminder() {
        val a = course(1, dayOfWeek = 1, startPeriod = 1)
        val b = course(2, dayOfWeek = 1, startPeriod = 6)
        val now = LocalDateTime.of(2026, 9, 7, 7, 0)

        val plan = ReminderScheduler.planNextReminder(
            courses = listOf(a, b),
            semesterStart = semesterStart,
            timeSlots = emptyList(),
            reminders = mapOf(1L to ReminderSetting(1, false, 10)),
            now = now,
            nowMillis = mills(now),
            zone = zone,
        )

        assertEquals(2L, plan?.course?.id)
    }

    @Test
    fun returnsNullWhenAllTriggersPassed() {
        // 课程只在第 1 周，且已过上课时间 → 没有可调度的提醒
        val a = course(1, dayOfWeek = 1, startPeriod = 1).copy(weeks = listOf(1)) // 08:00 上课
        val now = LocalDateTime.of(2026, 9, 7, 9, 0)

        val plan = ReminderScheduler.planNextReminder(
            courses = listOf(a),
            semesterStart = semesterStart,
            timeSlots = emptyList(),
            reminders = emptyMap(),
            now = now,
            nowMillis = mills(now),
            zone = zone,
        )

        assertNull(plan)
    }

    @Test
    fun defaultAdvanceIsTenMinutes() {
        val a = course(1, dayOfWeek = 1, startPeriod = 1)
        val now = LocalDateTime.of(2026, 9, 7, 7, 0)

        val plan = ReminderScheduler.planNextReminder(
            courses = listOf(a),
            semesterStart = semesterStart,
            timeSlots = emptyList(),
            reminders = emptyMap(),
            now = now,
            nowMillis = mills(now),
            zone = zone,
        )

        assertEquals(10, plan?.advanceMinutes)
        assertEquals(mills(mondayFirstPeriod) - 10 * 60_000L, plan?.triggerAtMillis)
    }
}
