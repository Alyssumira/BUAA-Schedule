package com.buaa.schedule.ui.home

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.TimeSlotProfile
import com.buaa.schedule.domain.schedule.SlotStatus
import com.buaa.schedule.domain.schedule.TodayPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 课表界面这一面的唤醒源：什么时候醒、醒来发布什么、发布的东西翻不翻面。
 *
 * 全部时间由参数注入——没有 sleep、没有真实时钟、没有 runTest 赌时序。
 * 这里刻意把「延时算得对」与「叫醒以后确实翻面」两段一起钉：
 * 只测前者会留下一种回归——延时算准了但醒来发布的是计划时刻而不是当下时刻，
 * 界面挂着一屏旧时间；只测后者会留下另一种——节次边界根本没有一次唤醒落在上面。
 */
class NowTickTest {

    private val semester = Semester(
        termCode = "T",
        termName = "T",
        startDate = "2026-09-07", // 第 1 周周一
        totalWeeks = 20,
    )
    private val wednesday = LocalDate.of(2026, 9, 9) // 第 1 周周三
    private val monday = LocalDate.of(2026, 9, 7)

    private val morningCourse = Course(
        name = "数据结构",
        teacher = "张三",
        location = "F102",
        dayOfWeek = 3,
        periods = listOf(1, 2), // 08:00–08:45 / 08:50–09:35
        weeks = (1..16).toList(),
    )
    private val lateMorningCourse = morningCourse.copy(
        name = "离散数学",
        teacher = "李四",
        location = "F103",
        periods = listOf(3), // 09:50–10:35
    )

    // ─────────────── 分钟步长：节次边界前后各 1 秒 ───────────────

    @Test
    fun `边界前一秒 只等一秒就醒`() {
        assertEquals(1_000L, nextTickDelayMillis(LocalTime.of(10, 29, 59)))
    }

    @Test
    fun `边界后一秒 等余下的整整一分钟`() {
        assertEquals(59_000L, nextTickDelayMillis(LocalTime.of(10, 30, 1)))
    }

    @Test
    fun `正好落在边界上 等整一步而不是零`() {
        // 这一刻刚刚发布过；再立刻醒一次就是白重组，返回 0 还会让调用方变成忙循环
        assertEquals(MINUTE_TICK_MS, nextTickDelayMillis(LocalTime.of(10, 30, 0)))
    }

    @Test
    fun `毫秒余数并进延时 逐日不累积漂移`() {
        assertEquals(350L, nextTickDelayMillis(LocalTime.of(10, 29, 59, 650_000_000)))
        assertEquals(59_350L, nextTickDelayMillis(LocalTime.of(10, 30, 0, 650_000_000)))
    }

    @Test
    fun `任一时刻的延时恒在一到一步之间`() {
        for (second in 0..59) {
            for (nano in listOf(0, 1, 500_000_000, 999_999_999)) {
                val delay = nextTickDelayMillis(LocalTime.of(9, 0, second, nano))
                assertTrue("$second.$nano → $delay", delay in 1L..MINUTE_TICK_MS)
            }
        }
    }

    @Test
    fun `步长非法时也不返回零或负数`() {
        val now = LocalTime.of(9, 0, 30)
        assertTrue(nextTickDelayMillis(now, 0L) >= 1L)
        assertTrue(nextTickDelayMillis(now, -60_000L) >= 1L)
    }

    @Test
    fun `一日之内每个上下课时刻都有一次唤醒落在上面`() {
        // 起点故意取在非整分钟的中间（还有课间那一秒），走满一整天：
        // 每一次节次边界（上课线与下课线）都必须正好是一次唤醒，早一秒或晚一秒都算漏
        val wakes = wakesFrom(LocalDateTime.of(wednesday, LocalTime.of(7, 59, 37, 512_000_000)), 1_500)
            .map { it.toLocalTime() }
            .toSet()
        val boundaries = TimeSlotProfile.DEFAULT.flatMap {
            listOf(LocalTime.parse(it.startTime), LocalTime.parse(it.endTime))
        }
        assertTrue("边界总数应为 ${boundaries.size}", boundaries.size > 20)
        for (boundary in boundaries) {
            assertTrue("缺少 $boundary 这一次唤醒", wakes.contains(boundary))
        }
    }

    @Test
    fun `除起点外每次唤醒都落在整分钟上`() {
        val wakes = wakesFrom(LocalDateTime.of(wednesday, LocalTime.of(7, 59, 37, 512_000_000)), 200)
        for (wake in wakes.drop(1)) {
            assertEquals(0, wake.second)
            assertEquals(0, wake.nano)
        }
    }

    // ─────────────── 迟到唤醒（Doze / 冻结后解冻） ───────────────

    @Test
    fun `迟到唤醒按醒来那一刻算下一次 不连锁补醒`() {
        // 计划 12:30:00 醒，实际被 Doze 拖到 12:37:20。延时是按**醒来那一刻**重算的，
        // 所以下一次是 12:38:00；若按计划时刻排（planned += step），这里会连醒 7 次补账。
        val wakes = wakesFrom(LocalDateTime.of(wednesday, LocalTime.of(12, 37, 20)), 3)
        assertEquals(LocalTime.of(12, 37, 20), wakes[0].toLocalTime())
        assertEquals(LocalTime.of(12, 38, 0), wakes[1].toLocalTime())
        assertEquals(LocalTime.of(12, 39, 0), wakes[2].toLocalTime())
    }

    @Test
    fun `迟到之后仍覆盖剩下的每个边界`() {
        val late = LocalDateTime.of(wednesday, LocalTime.of(12, 37, 20))
        val wakes = wakesFrom(late, 700).map { it.toLocalTime() }.toSet()
        val missed = TimeSlotProfile.DEFAULT
            .flatMap { listOf(LocalTime.parse(it.startTime), LocalTime.parse(it.endTime)) }
            .filter { it.isAfter(LocalTime.of(12, 37, 20)) && it !in wakes }
        assertEquals(emptyList<LocalTime>(), missed)
    }

    @Test
    fun `相邻两次唤醒的间隔恰好是一步`() {
        val wakes = wakesFrom(LocalDateTime.of(wednesday, LocalTime.of(8, 3, 17)), 300)
        // 首跳是"补到下一个整分钟"的那 43 秒，之后每一步都正好一步：
        // 迟到唤醒不会引起连锁补醒，见 `迟到唤醒按醒来那一刻算下一次 不连锁补醒`
        assertEquals(43_000L, Duration.between(wakes[0], wakes[1]).toMillis())
        for ((before, after) in wakes.zipWithNext().drop(1)) {
            assertEquals(MINUTE_TICK_MS, Duration.between(before, after).toMillis())
        }
    }

    // ─────────────── 跨零点 ───────────────

    @Test
    fun `分钟步长跨零点 前一秒只等一秒`() {
        assertEquals(1_000L, nextTickDelayMillis(LocalTime.of(23, 59, 59)))
        assertEquals(MINUTE_TICK_MS, nextTickDelayMillis(LocalTime.MIDNIGHT))
    }

    @Test
    fun `分钟步长的唤醒序列自己跨过零点`() {
        val wakes = wakesFrom(LocalDateTime.of(wednesday, LocalTime.of(23, 58, 30)), 3)
        assertEquals(LocalDateTime.of(wednesday, LocalTime.of(23, 59, 0)), wakes[1])
        assertEquals(LocalDateTime.of(wednesday.plusDays(1), LocalTime.MIDNIGHT), wakes[2])
    }

    @Test
    fun `日步长醒在下一个零点之后`() {
        // 白天：正好是"到零点的毫秒数 + 越界缓冲"
        assertEquals(
            MILLIS_TO_MIDNIGHT_AT_NOON + DAY_TICK_OVERFLOW_MS,
            nextDayTickDelayMillis(LocalTime.of(12, 0, 0)),
        )
        // 零点整：等一整天
        assertEquals(ONE_DAY_MS + DAY_TICK_OVERFLOW_MS, nextDayTickDelayMillis(LocalTime.MIDNIGHT))
        // 毫秒余数并进延时
        assertEquals(
            MILLIS_TO_MIDNIGHT_AT_NOON - 500L + DAY_TICK_OVERFLOW_MS,
            nextDayTickDelayMillis(LocalTime.of(12, 0, 0, 500_000_000)),
        )
    }

    @Test
    fun `日步长在零点前一秒退成最短等待 但仍在过零点之后`() {
        val delay = nextDayTickDelayMillis(LocalTime.of(23, 59, 59))
        assertEquals(MIN_DAY_TICK_MS, delay)
        // 最短等待也必须已经越过零点：否则醒来发射的还是同一天，今日页停在昨天
        assertTrue(delay > 1_000L)
    }

    @Test
    fun `任一时刻的日步长都必须越过下一个零点`() {
        for (minute in listOf(0, 1, 30, 59, 720, 1_379, 1_380, 1_439)) {
            val now = LocalTime.MIDNIGHT.plusMinutes(minute.toLong())
            val millisToMidnight = ONE_DAY_MS - now.toNanoOfDay() / 1_000_000L
            assertTrue("$now → ${nextDayTickDelayMillis(now)}", nextDayTickDelayMillis(now) > millisToMidnight)
        }
    }

    @Test
    fun `日步长迟到唤醒发射的仍是醒来那一天的日期`() {
        // 滴答在周二夜里排醒，计划醒的时刻是周三 00:00:05；被 Doze 拖到周三 03:00:05。
        // 调用方读的是醒来那一刻，两个时刻的日期相同 ——
        // 迟到只影响"晚多久看到"，不影响"看到的是哪一天"。
        val tuesdayNight = LocalDateTime.of(wednesday.minusDays(1), LocalTime.of(23, 0))
        val planned = tuesdayNight.plusSeconds(3_600 + 5)
        val late = planned.plusHours(3)
        assertEquals(wednesday, planned.toLocalDate())
        assertEquals(planned.toLocalDate(), late.toLocalDate())
    }

    @Test
    fun `日步长早醒时发射的是同一天 下游把它吞掉`() {
        // monotonic 计时在冻结期间不走 → 唤醒可能明显早于零点。
        // 这一次醒发的是**还没换天**的日期，与上一次相同 → distinctUntilChanged 吞掉，
        // 代价只是多醒一次；界面不会闪一下昨天的日期，也不会停在错误的明天。
        val early = LocalDateTime.of(wednesday, LocalTime.of(23, 59, 30))
        assertEquals(wednesday, early.toLocalDate())
        val delay = nextDayTickDelayMillis(early.toLocalTime())
        assertEquals(MIN_DAY_TICK_MS, delay)
        assertTrue(early.plusSeconds(delay / 1_000L).toLocalDate() > early.toLocalDate())
    }

    // ─────────────── 两个步长同一条墙钟（日视图与周视图） ───────────────

    @Test
    fun `两个步长在同一个整分钟边界上对齐`() {
        val start = LocalDateTime.of(wednesday, LocalTime.of(9, 59, 40))
        val timelineMinutes = wakesFrom(start, 240, TIMELINE_TICK_MS)
            .filter { it.second == 0 && it.nano == 0 }
            .map { it.toLocalTime() }
        val heroMinutes = wakesFrom(start, 61).drop(1).map { it.toLocalTime() }
        assertEquals(60, timelineMinutes.size)
        assertEquals(heroMinutes, timelineMinutes)
    }

    @Test
    fun `同一秒读出的两个延时在同一起点翻面`() {
        // 以前固定 delay(15_000) 对齐的是进程启动那一刻，
        // 于是 Hero 的「还有 N 分钟」与网格里的红线最多相差 15 秒
        val beforeEnd = LocalTime.of(10, 44, 50)
        assertEquals(10_000L, nextTickDelayMillis(beforeEnd, MINUTE_TICK_MS))
        assertEquals(10_000L, nextTickDelayMillis(beforeEnd, TIMELINE_TICK_MS))
    }

    @Test
    fun `十五秒步长不超过一分钟也不会为零`() {
        for (second in 0..59) {
            val delay = nextTickDelayMillis(LocalTime.of(9, 30, second), TIMELINE_TICK_MS)
            assertTrue("$second → $delay", delay in 1L..TIMELINE_TICK_MS)
        }
    }

    // ─────────────── 叫醒之后翻面对得上：有课的一天 ───────────────

    @Test
    fun `下课边界前后各一秒 今日安排翻面`() {
        val courses = listOf(morningCourse, lateMorningCourse)
        val before = TodayPlanner.plan(courses, semester, TimeSlotProfile.DEFAULT, wednesday, LocalTime.of(9, 34, 59))
        val after = TodayPlanner.plan(courses, semester, TimeSlotProfile.DEFAULT, wednesday, LocalTime.of(9, 35, 0))

        // requireNotNull 而非 JUnit assertNotNull：后者不产生智能转换，
        // 接一句 `!!` 就是把同一个判空抄两遍。为空时这里直接抛，测试照样红。
        val ongoingBefore = requireNotNull(before.ongoing)
        assertEquals("数据结构", ongoingBefore.course.name)
        assertEquals(LocalTime.of(8, 0), ongoingBefore.start)
        assertEquals(LocalTime.of(9, 35), ongoingBefore.end)
        assertEquals(1L, before.minutesRemaining)

        // 下课那一秒翻面：第 1-2 节结束，下一节是 09:50 的离散数学
        assertNull(after.ongoing)
        val nextAfterEnd = requireNotNull(after.next)
        assertEquals("离散数学", nextAfterEnd.course.name)
        assertEquals(LocalTime.of(9, 50), nextAfterEnd.start)
        assertEquals(15L, after.minutesToNext)
    }

    @Test
    fun `唤醒时刻正是翻面时刻`() {
        // 延时的终点必须与窗口判据翻面的那一秒重合：
        // 早醒一次是白重组，晚醒一次就是界面挂着一节已经下课的"进行中"
        val boundary = LocalTime.of(9, 35, 0)
        val wakeAt = LocalTime.of(9, 34, 59).plusNanos(nextTickDelayMillis(LocalTime.of(9, 34, 59)) * 1_000_000L)
        assertEquals(boundary, wakeAt)

        val courses = listOf(morningCourse, lateMorningCourse)
        assertEquals(SlotStatus.ONGOING, statusOf(courses, LocalTime.of(9, 34, 59)))
        assertEquals(SlotStatus.PAST, statusOf(courses, boundary))
    }

    @Test
    fun `上课边界那一秒翻成进行中`() {
        val courses = listOf(morningCourse, lateMorningCourse)
        val beforeStart = TodayPlanner.plan(courses, semester, TimeSlotProfile.DEFAULT, wednesday, LocalTime.of(9, 49, 59))
        assertNull(beforeStart.ongoing)
        assertEquals(1L, beforeStart.minutesToNext)

        val atStart = TodayPlanner.plan(courses, semester, TimeSlotProfile.DEFAULT, wednesday, LocalTime.of(9, 50, 0))
        assertNotNull(atStart.ongoing)   // 开课那一秒含在内
        assertEquals(45L, atStart.minutesRemaining)
        assertEquals(1_000L, nextTickDelayMillis(LocalTime.of(9, 49, 59)))
    }

    // ─────────────── 叫醒之后翻面对得上：没课 / 节次未配置 ───────────────

    @Test
    fun `这天没课时 唤醒不产出翻面 也不缩短延时`() {
        // 周一那天只有周三的课 → 空安排。空安排不是停止滴答的理由，
        // 但也不该让滴答变勤：没课的整天仍然只每分钟醒一次
        val empty = TodayPlanner.plan(
            listOf(morningCourse), semester, TimeSlotProfile.DEFAULT, monday, LocalTime.of(9, 34, 59),
        )
        assertEquals(0, empty.slots.size)
        assertNull(empty.ongoing)
        assertNull(empty.next)
        for (minute in listOf(8, 9, 12, 23)) {
            // 整分钟上仍是完整一步（刚发布过，不该立刻再醒）；分钟中间也绝不短到 1 秒以下
            assertEquals(MINUTE_TICK_MS, nextTickDelayMillis(LocalTime.of(minute, 0, 0)))
            assertTrue(nextTickDelayMillis(LocalTime.of(minute, 0, 30)) in 1L until MINUTE_TICK_MS)
        }
    }

    @Test
    fun `节次表未配置时仍按默认作息的整分钟下课线翻面`() {
        // 用户没配节次表 → TodayPlanner 退到 TimeSlotProfile.DEFAULT；
        // 界面这一面的步长是"整分钟"，只要退路给的下课时间落在整分钟上就不会错拍
        val courses = listOf(morningCourse)
        val emptySlots = TodayPlanner.plan(courses, semester, emptyList(), wednesday, LocalTime.of(9, 34, 59))
        assertNotNull(emptySlots.ongoing)
        assertEquals(1L, emptySlots.minutesRemaining)
        val atEnd = TodayPlanner.plan(courses, semester, emptyList(), wednesday, LocalTime.of(9, 35, 0))
        assertNull(atEnd.ongoing)
        assertEquals(MINUTE_TICK_MS, nextTickDelayMillis(LocalTime.of(9, 35, 0)))
    }

    @Test
    fun `脏下课时间的兜底窗口终点也在整分钟上被叫醒`() {
        // 节次表只有第 1 节配了下课时间：第 1-2 节按段内回溯退成 08:00–08:45。
        // 界面必须正好在 08:45:00 翻面，与组件、闹钟链读同一个终点
        val timeSlots = listOf(TimeSlot(number = 1, startTime = "08:00", endTime = "08:45"))
        val before = TodayPlanner.plan(listOf(morningCourse), semester, timeSlots, wednesday, LocalTime.of(8, 44, 59))
        val after = TodayPlanner.plan(listOf(morningCourse), semester, timeSlots, wednesday, LocalTime.of(8, 45, 0))
        assertNotNull(before.ongoing)
        assertNull(after.ongoing)
        assertEquals(1_000L, nextTickDelayMillis(LocalTime.of(8, 44, 59)))
    }

    @Test
    fun `没有学期时今日页为空 滴答照旧一分钟一步`() {
        val plan = TodayPlanner.plan(listOf(morningCourse), null, TimeSlotProfile.DEFAULT, wednesday, LocalTime.of(9, 0))
        assertEquals(0, plan.slots.size)
        assertEquals(MINUTE_TICK_MS, nextTickDelayMillis(LocalTime.of(9, 0)))
    }

    // ─────────────── helpers ───────────────

    /**
     * 从 [start] 起「醒来 → 发布当下 → 再算下一次要等多久」的唤醒序列（含起点）。
     * 延时一律按**醒来那一刻**重算，与 DayView/WeekView 里的循环体同一条因果链。
     */
    private fun wakesFrom(
        start: LocalDateTime,
        count: Int,
        stepMillis: Long = MINUTE_TICK_MS,
    ): List<LocalDateTime> {
        var moment = start
        return buildList {
            repeat(count) {
                add(moment)
                moment = moment.plusNanos(nextTickDelayMillis(moment.toLocalTime(), stepMillis) * 1_000_000L)
            }
        }
    }

    private fun statusOf(courses: List<Course>, now: LocalTime): SlotStatus =
        TodayPlanner.plan(courses, semester, TimeSlotProfile.DEFAULT, wednesday, now)
            .slots
            .first { it.segment.first() == 1 }
            .status

    private companion object {
        const val ONE_DAY_MS = 24L * 60L * 60L * 1_000L
        const val MILLIS_TO_MIDNIGHT_AT_NOON = ONE_DAY_MS / 2
    }
}
