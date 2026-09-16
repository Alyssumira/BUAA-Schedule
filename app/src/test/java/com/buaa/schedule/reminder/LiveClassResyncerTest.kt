package com.buaa.schedule.reminder

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.TimeSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 前台状态驱动校准的决策口径（R5 F-11 / F-12 / F-15）。
 *
 * 「下课铃被 ROM 吞掉」是本应用最难复现的一类故障：手机停在勿扰 + 一条滑不掉的常驻通知，
 * 一直等到下一节课上课铃。这里把决策从 Android 依赖里拆出来测，
 * 保证三条回归都被守住：
 * 1. 此刻没有课在进行（含空课表）→ 收遗留，而不是原地 return；
 * 2. 只有真的在课堂窗口内才补实况；
 * 3. 一次课结束后，「尚未结束的最早一次课」会顺到下一节 —— 下课铃续排的依据。
 */
class LiveClassResyncerTest {

    private val semesterStart = LocalDate.of(2026, 9, 7) // 周一
    private val slots = listOf(
        TimeSlot(number = 1, startTime = "08:00", endTime = "08:45"),
        TimeSlot(number = 2, startTime = "08:50", endTime = "09:35"),
        TimeSlot(number = 3, startTime = "10:00", endTime = "10:45"),
    )

    private fun millisOf(dateTime: LocalDateTime): Long =
        dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun window(start: LocalDateTime, end: LocalDateTime) = ClassProgressScheduler.ClassWindow(
        courseId = 1L,
        courseName = "高等数学",
        location = "J3-101",
        sectionText = "1-3节",
        startMillis = millisOf(start),
        endMillis = millisOf(end),
    )

    private fun course(name: String, periods: List<Int>) = Course(
        id = 1L,
        name = name,
        location = "J3-101",
        dayOfWeek = 1,
        periods = periods,
        weeks = listOf(1),
    )

    @Test
    fun clearsLeftoversWhenNothingIsInProgress() {
        val start = LocalDateTime.of(2026, 9, 7, 10, 0)
        val now = LocalDateTime.of(2026, 9, 7, 9, 40)

        assertEquals(
            LiveClassResyncer.ResyncAction.ClearLeftovers,
            LiveClassResyncer.decide(window(start, start.plusMinutes(45)), millisOf(now)),
        )
    }

    @Test
    fun clearsLeftoversWhenScheduleIsEmpty() {
        // 空课表（F-11 的场景）同样不能留着实况与勿扰：决策必须是"收干净"而不是"什么都不做"
        val window = ClassProgressScheduler.planNextClassWindow(
            courses = emptyList(),
            semesterStart = semesterStart,
            timeSlots = slots,
            now = LocalDateTime.of(2026, 9, 7, 8, 20),
        )
        assertNull(window)
        assertEquals(
            LiveClassResyncer.ResyncAction.ClearLeftovers,
            LiveClassResyncer.decide(window, System.currentTimeMillis()),
        )
    }

    @Test
    fun startsLiveOnlyInsideTheWindow() {
        val start = LocalDateTime.of(2026, 9, 7, 8, 0)
        val target = window(start, start.plusMinutes(95))

        val action = LiveClassResyncer.decide(target, millisOf(LocalDateTime.of(2026, 9, 7, 8, 20)))

        assertTrue(action is LiveClassResyncer.ResyncAction.StartLive)
        assertEquals(target, (action as LiveClassResyncer.ResyncAction.StartLive).window)
    }

    @Test
    fun windowEndingNowStopsBeingAClass() {
        val start = LocalDateTime.of(2026, 9, 7, 8, 0)
        val target = window(start, start.plusMinutes(95))

        assertEquals(
            LiveClassResyncer.ResyncAction.ClearLeftovers,
            LiveClassResyncer.decide(target, millisOf(start.plusMinutes(95))),
        )
    }

    @Test
    fun nextWindowAfterClassEndIsTheFollowingOne() {
        val mondayFirst = course("高等数学", listOf(1, 2))
        val mondayLater = course("大学物理", listOf(3)).copy(id = 2L)

        val window = ClassProgressScheduler.planNextClassWindow(
            courses = listOf(mondayLater, mondayFirst),
            semesterStart = semesterStart,
            timeSlots = slots,
            // 第一节 08:00–09:35 刚下课：续排应落到 10:00 那一节
            now = LocalDateTime.of(2026, 9, 7, 9, 40),
        )

        assertEquals("大学物理", window?.courseName)
        assertEquals(millisOf(LocalDateTime.of(2026, 9, 7, 10, 0)), window?.startMillis)
    }
}
