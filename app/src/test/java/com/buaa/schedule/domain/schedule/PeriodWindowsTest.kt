package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.toStartEndTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 「此刻是第几节 / 下一节 / 还差几分钟」唯一算法的确定性单测。
 *
 * 时间全部由参数注入：不读系统时钟、不 sleep、不用 `runTest` 赌时序。
 * 覆盖收敛前四份实现各自口径分叉的那几个点 —— 节次边界前后各一秒、跨零点、
 * 这天没课、节次时间未配置、迟到唤醒、脏下课时间。
 */
class PeriodWindowsTest {

    private val monday = LocalDate.of(2026, 9, 7)
    private val tuesday = LocalDate.of(2026, 9, 8)
    private val saturday = LocalDate.of(2026, 9, 12)

    /** 内置默认作息的前五节：08:00–08:45 / 08:50–09:35 / 09:50–10:35 / 10:40–11:25 / 11:30–12:15 */
    private val defaultTimes: Map<Int, Pair<LocalTime, LocalTime>> = listOf(
        TimeSlot(number = 1, startTime = "08:00", endTime = "08:45"),
        TimeSlot(number = 2, startTime = "08:50", endTime = "09:35"),
        TimeSlot(number = 3, startTime = "09:50", endTime = "10:35"),
        TimeSlot(number = 4, startTime = "10:40", endTime = "11:25"),
        TimeSlot(number = 5, startTime = "11:30", endTime = "12:15"),
    ).toStartEndTimes()

    private fun times(vararg slot: Triple<Int, String, String>) =
        slot.associate { (number, start, end) -> number to (LocalTime.parse(start) to LocalTime.parse(end)) }

    private fun course(periods: List<Int>, dayOfWeek: Int = 1) = Course(
        name = "高等数学",
        teacher = "张三",
        location = "J3-101",
        dayOfWeek = dayOfWeek,
        periods = periods,
        weeks = (1..16).toList(),
    )

    private fun windowOf(vararg periods: Int): PeriodWindow =
        periodWindowsOf(course(periods.toList()), monday, defaultTimes).single()

    // ---- 节次边界前后各 1 秒 ----

    @Test
    fun classBecomesOngoingExactlyAtItsStartSecond() {
        val window = windowOf(1, 2) // 连堂 08:00–09:35
        assertEquals(LocalTime.of(8, 0), window.begin.toLocalTime())
        assertEquals(LocalTime.of(9, 35), window.end.toLocalTime())

        assertEquals(SlotStatus.UPCOMING, window.statusAt(monday.atTime(7, 59, 59)))
        assertEquals(SlotStatus.ONGOING, window.statusAt(monday.atTime(8, 0, 0)))
        assertFalse(window.ongoingAt(monday.atTime(7, 59, 59)))
        assertTrue(window.ongoingAt(monday.atTime(8, 0, 0)))
    }

    @Test
    fun classEndsExactlyAtItsEndSecond() {
        val window = windowOf(1, 2)
        assertEquals(SlotStatus.ONGOING, window.statusAt(monday.atTime(9, 34, 59)))
        assertEquals(SlotStatus.PAST, window.statusAt(monday.atTime(9, 35, 0)))
        assertTrue(window.ongoingAt(monday.atTime(9, 34, 59)))
        assertFalse(window.ongoingAt(monday.atTime(9, 35, 0)))
    }

    @Test
    fun linkedPeriodsFormOneWindowNotTwo() {
        // 1-2 节课间只有 5 分钟：合成一个窗口，"第2节下课"不该被当成整段结束
        val windows = periodWindowsOf(course(listOf(1, 2)), monday, defaultTimes)
        assertEquals(1, windows.size)
        assertEquals(1..2, windows.single().segment)
    }

    @Test
    fun lunchBreakSplitsPeriodsIntoSeparateWindows() {
        // 5、6 节节次号相邻但隔着午饭：合在一起会罩住整个午休（P1-2 同源问题）
        val windows = periodWindowsOf(course(listOf(2, 3, 5)), monday, defaultTimes)
        assertEquals(listOf(2..3, 5..5), windows.map { it.segment })
        assertEquals(LocalTime.of(8, 50), windows.first().begin.toLocalTime())
        assertEquals(LocalTime.of(10, 35), windows.first().end.toLocalTime())
    }

    @Test
    fun betweenTwoWindowsDayStatusIsUpcomingNotPast() {
        val windows = periodWindowsOf(course(listOf(1, 5)), monday, defaultTimes)
        val ten = monday.atTime(10, 0)
        assertEquals(SlotStatus.PAST, windows[0].statusAt(ten))
        assertEquals(SlotStatus.UPCOMING, windows[1].statusAt(ten))
        // 课间不是"今天的课结束了"：整门课仍是未开始
        assertEquals(SlotStatus.UPCOMING, dayStatusOf(course(listOf(1, 5)), monday, defaultTimes, ten))
    }

    @Test
    fun ongoingSegmentWinsWholeCourseStatus() {
        val ten = monday.atTime(10, 0)
        assertEquals(
            SlotStatus.ONGOING,
            dayStatusOf(course(listOf(1, 3)), monday, defaultTimes, ten),
        )
        assertEquals(
            SlotStatus.PAST,
            dayStatusOf(course(listOf(1, 2)), monday, defaultTimes, monday.atTime(12, 0)),
        )
    }

    // ---- 跨零点 ----

    @Test
    fun midnightRolloverFlipsYesterdayToPastAndTodayToUpcoming() {
        val yesterday = monday.atTime(23, 59, 59)
        val justAfterMidnight = tuesday.atTime(0, 0, 1)

        val lastWindow = periodWindowsOf(course(listOf(1)), monday, defaultTimes).single()
        assertEquals(SlotStatus.PAST, lastWindow.statusAt(yesterday))
        assertEquals(SlotStatus.PAST, lastWindow.statusAt(justAfterMidnight))

        // 新的一天用新的 date 重算窗口：零点刚过这一节是"还没开始"，而不是"昨天的那一节"
        val fresh = periodWindowsOf(course(listOf(1)), tuesday, defaultTimes).single()
        assertEquals(monday.plusDays(1), fresh.begin.toLocalDate())
        assertEquals(SlotStatus.UPCOMING, fresh.statusAt(justAfterMidnight))
    }

    @Test
    fun dayStatusOnTheNewDayIsUpcomingEvenAfterYesterdayEnded() {
        assertEquals(
            SlotStatus.UPCOMING,
            dayStatusOf(course(listOf(1)), tuesday, defaultTimes, tuesday.atTime(0, 0, 1)),
        )
        assertEquals(
            SlotStatus.PAST,
            dayStatusOf(course(listOf(1)), monday, defaultTimes, tuesday.atTime(0, 0, 1)),
        )
    }

    @Test
    fun minutesUntilCrossesMidnight() {
        // 跨零点只有 LocalDateTime 版能算：LocalTime 版不回绕（同日口径）
        assertEquals(6L, minutesUntil(monday.atTime(23, 59, 0), tuesday.atTime(0, 5, 0)))
        assertEquals(1L, minutesUntil(LocalTime.of(7, 59, 30), LocalTime.of(8, 0)))
        assertEquals(0L, minutesUntil(LocalTime.of(9, 0), LocalTime.of(8, 0)))
    }

    // ---- 周末与假期（这天没课） ----

    @Test
    fun courseWithoutPeriodsYieldsNoWindow() {
        assertTrue(periodWindowsOf(course(emptyList()), saturday, defaultTimes).isEmpty())
    }

    @Test
    fun holidayDayPlansNothing() {
        val semester = Semester(termCode = "T", termName = "T", startDate = "2026-09-07", totalWeeks = 20)
        val plan = TodayPlanner.plan(
            courses = listOf(course(listOf(1, 2), dayOfWeek = 1)),
            semester = semester,
            timeSlots = emptyList(),
            today = LocalDate.of(2027, 2, 1), // 学期之外
            now = LocalTime.of(8, 0),
        )
        assertEquals(0, plan.slots.size)
        assertNull(plan.ongoing)
        assertNull(plan.next)
    }

    @Test
    fun saturdayPlansNothingForWeekdayCourse() {
        val semester = Semester(termCode = "T", termName = "T", startDate = "2026-09-07", totalWeeks = 20)
        val plan = TodayPlanner.plan(
            courses = listOf(course(listOf(1, 2), dayOfWeek = 1)),
            semester = semester,
            timeSlots = emptyList(),
            today = saturday,
            now = LocalTime.of(8, 30),
        )
        assertEquals(0, plan.slots.size)
        assertNull(plan.ongoing)
    }

    @Test
    fun missingSlotsNeverReadAsEverythingFinished() {
        // 节次表没配 → 一个窗口也构造不出来。这是"判不了"，
        // 不能表现成"今天的课全上完了"（组件与界面会整列抹灰）
        assertEquals(
            SlotStatus.UPCOMING,
            dayStatusOf(course(listOf(1)), monday, emptyMap(), monday.atTime(23, 0)),
        )
    }

    // ---- 节次时间未配置 ----

    @Test
    fun emptySlotTimesYieldEmptyWindowList() {
        assertTrue(periodWindowsOf(course(listOf(1, 2)), monday, emptyMap()).isEmpty())
    }

    @Test
    fun emptyTimeSlotListFallsBackToDefaultProfile() {
        // List<TimeSlot> 版：空表退到内置默认作息，与闹钟链同一口径
        val windows = periodWindowsOf(course(listOf(1, 2)), monday, emptyList<TimeSlot>())
        assertEquals(1, windows.size)
        assertEquals(LocalTime.of(8, 0), windows.single().begin.toLocalTime())
        assertEquals(LocalTime.of(9, 35), windows.single().end.toLocalTime())
    }

    @Test
    fun segmentWithoutStartTimeIsSkipped() {
        // 节次表里没有第 6 节：缺**上课**时间不能兜底成 08:00，那会凭空造出一节早八的课
        val windows = periodWindowsOf(
            course(listOf(1, 6)),
            monday,
            times(Triple(1, "08:00", "08:45")),
        )
        assertEquals(listOf(1..1), windows.map { it.segment })
    }

    // ---- 段末下课时间缺失的兜底口径 ----

    @Test
    fun missingEndLooksBackToEarlierPeriodInSameSegment() {
        // 只有 1、2 节配了时间，第 3 节缺失：段末该回退到第 2 节的下课时间
        val windows = periodWindowsOf(
            course(listOf(1, 2, 3)),
            monday,
            times(Triple(1, "08:00", "08:45"), Triple(2, "08:50", "09:35")),
        )
        assertEquals(LocalTime.of(9, 35), windows.single().end.toLocalTime())
    }

    @Test
    fun dirtyEndNotAfterStartFallsBackToSingleClassLength() {
        for (bad in listOf("08:00", "07:30")) {
            val windows = periodWindowsOf(
                course(listOf(1)),
                monday,
                times(Triple(1, "08:00", bad)),
            )
            assertEquals(
                "endTime=$bad 是脏数据，窗口长度应回退成一节 $DIRTY_FALLBACK_MINUTES 分钟",
                monday.atTime(8, 0).plusMinutes(DIRTY_FALLBACK_MINUTES),
                windows.single().end,
            )
            assertTrue(windows.single().end.isAfter(windows.single().begin))
        }
    }

    // ---- 迟到唤醒（闹钟比预定晚几分钟才投到） ----

    @Test
    fun lateWakeUpStillSeesTheOngoingClass() {
        // 上课铃晚投 3 分钟：08:03 醒来仍应认定 08:00–09:35 正在进行
        val window = windowOf(1, 2)
        assertEquals(SlotStatus.ONGOING, window.statusAt(monday.atTime(8, 3)))
        assertTrue(window.ongoingAt(monday.atTime(8, 3)))
        // 剩余分钟数按向上取整，与实况那条链读数一致
        assertEquals(92L, minutesUntil(monday.atTime(8, 3), window.end))
    }

    @Test
    fun lateWakeUpAfterEndMovesOnToTheNextWindow() {
        // 下课铃晚投 3 分钟：09:38 已不能再说第 2 节（08:50–09:35）在进行中，
        // 排程/组件都要落到下一节，且剩余分钟按第 4 节的开课时刻重算
        val windows = periodWindowsOf(course(listOf(2, 4)), monday, defaultTimes)
        val late = monday.atTime(9, 38)
        assertEquals(listOf(2..2, 4..4), windows.map { it.segment })
        assertEquals(SlotStatus.PAST, windows.first().statusAt(late))
        assertEquals(SlotStatus.UPCOMING, windows.last().statusAt(late))
        assertEquals(62L, minutesUntil(late, windows.last().begin))
    }

    @Test
    fun windowsAreAlwaysOrderedAndNonOverlapping() {
        val windows = periodWindowsOf(course(listOf(1, 2, 3, 5)), monday, defaultTimes)
        assertEquals(2, windows.size)
        for (i in 1 until windows.size) {
            assertFalse(
                "窗口必须按时间升序且不重叠",
                windows[i].begin.isBefore(windows[i - 1].end),
            )
        }
    }

    // ---- 分钟口径与"最迟什么时候必须重发一次" ----

    @Test
    fun minutesCeilRoundsUpAndNeverGoesNegative() {
        assertEquals(0L, minutesCeil(0L))
        assertEquals(0L, minutesCeil(-1L))
        assertEquals(0L, minutesCeil(-60_000L))
        assertEquals(1L, minutesCeil(1L))
        assertEquals(1L, minutesCeil(59_999L))
        assertEquals(1L, minutesCeil(60_000L))
        assertEquals(2L, minutesCeil(60_001L))
    }

    @Test
    fun snapshotDeadlineIsWhenTheShownNumberWouldRollOver() {
        val end = monday.atTime(9, 35).toEpochMillis(UTC)
        val deadline = snapshotRedeadlineMillis(10L, end)
        // 文案写"还有 10 分钟"：它在 end-9min 这一秒翻成 9 —— 那就是最迟必须重发的一刻
        assertEquals(end - 9 * MINUTE, deadline)
        // 期限之前一刻，文案里的 N 仍与真实剩余一致
        assertEquals(10L, minutesCeil(end - (deadline - 1_000L)))
        // 期限这一秒起，不再重发就会晚一分钟
        assertEquals(9L, minutesCeil(end - deadline))
        // "还有 1 分钟"覆盖剩余 (0, 60s]，保质期终点就是到点那一刻
        assertEquals(end, snapshotRedeadlineMillis(1L, end))
        assertEquals(0L, minutesCeil(end - snapshotRedeadlineMillis(1L, end)))
        // 0 分钟（已到点）没有相对时间可保鲜
        assertEquals(end, snapshotRedeadlineMillis(0L, end))
    }

    // ---- 墙钟换算：唯一一份 ----

    @Test
    fun epochMillisRoundTripIsStable() {
        for (moment in listOf(
            monday.atTime(0, 0, 0),
            monday.atTime(8, 30, 15),
            monday.atTime(23, 59, 59),
        )) {
            assertEquals(moment, epochMillisToLocalDateTime(moment.toEpochMillis(UTC), UTC))
        }
    }

    @Test
    fun nonPositiveEpochMillisReadsAsMissingNotAsEpoch() {
        assertNull(epochMillisToLocalDateTime(0L, UTC))
        assertNull(epochMillisToLocalDateTime(-1L, UTC))
    }

    private companion object {
        const val DIRTY_FALLBACK_MINUTES = 45L
        const val MINUTE = 60_000L
        val UTC: ZoneId = ZoneId.of("UTC")
    }
}
