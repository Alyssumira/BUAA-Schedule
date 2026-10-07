package com.buaa.schedule.reminder

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.schedule.TermWeekGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * 保证四条回归都被守住：
 * 1. 此刻没有课在进行（含空课表）→ 收遗留，而不是原地 return；
 * 2. 只有真的在课堂窗口内才补实况；
 * 3. 一次课结束后，「尚未结束的最早一次课」会顺到下一节 —— 下课铃续排的依据；
 * 4. （T227 #332）挑窗口时递了学期上界 ⇒ 周课表画不到的那一周既不冒实况、也不补那枚
 *    会撤掉真课实况与勿扰的下课铃；界内那一周两档同解，加严没有伤到正常课。
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

    /**
     * T227 #332：前台校准挑窗口时**必须**递学期上界，因为 [LiveClassResyncer] 挑中之后
     * 紧接着就 `scheduleEnd` 补一枚下课铃 —— 而那枚铃到点撤的是全仓唯一那枚课堂实况通知 id
     * 并恢复勿扰。不判上界时，一节**周课表上画不到**的课能靠"打开 App"补起实况，
     * 并把另一节真课的实况与勿扰一起拆掉（T214/#263 那本账的跨卡残账）。
     *
     * 表驱动两侧：同一批输入，越界那一周在两档下答案相反（界内那一周四档同解，
     * 证明"加严"没有顺手把正常课的实况也摘掉）。时刻全部由用例给（零设备时钟）。
     */
    @Test
    fun resyncWindowsAreJudgedAgainstTheTermBoundOnBothSides() {
        val courses = listOf(course("高等数学", listOf(1, 2)).copy(weeks = listOf(3, 17)))
        val insideWeekThree = LocalDateTime.of(2026, 9, 21, 8, 20) // 第 3 周周一 08:00 那一节正在上
        val insideWeekSeventeen = LocalDateTime.of(2026, 12, 28, 8, 20) // 第 17 周周一：屏上那一周是空的

        data class Case(
            val label: String,
            val now: LocalDateTime,
            /** 递给判据的学期上界；[TermWeekGate.UNBOUNDED_TOTAL_WEEKS] = 明说不判上界那一档 */
            val totalWeeks: Int?,
            /** false = 挑不出窗口 ⇒ decide 交回 ClearLeftovers，既没有实况也没有那枚下课铃 */
            val expectWindow: Boolean,
        )

        val cases = listOf(
            Case("越界那一周 + 递上界（今天那一档）", insideWeekSeventeen, totalWeeks = SIXTEEN, expectWindow = false),
            Case("越界那一周 + 不判上界（病灶那一档）", insideWeekSeventeen, totalWeeks = TermWeekGate.UNBOUNDED_TOTAL_WEEKS, expectWindow = true),
            Case("界内那一周 + 递上界", insideWeekThree, totalWeeks = SIXTEEN, expectWindow = true),
            Case("界内那一周 + 不判上界", insideWeekThree, totalWeeks = TermWeekGate.UNBOUNDED_TOTAL_WEEKS, expectWindow = true),
        )

        for (case in cases) {
            val window = ClassProgressScheduler.planNextClassWindow(
                courses = courses,
                semesterStart = semesterStart,
                timeSlots = slots,
                now = case.now,
                totalWeeks = case.totalWeeks,
            )
            val action = LiveClassResyncer.decide(window, millisOf(case.now))
            if (case.expectWindow) {
                assertNotNull("${case.label}：这一档必须挑得出窗口（否则加严加到了正常课上）", window)
                assertEquals("${case.label}：挑得出窗口就该补实况并补排下课铃", LiveClassResyncer.ResyncAction.StartLive(window), action)
                assertEquals("${case.label}：补的那枚铃排在窗口终点上", window!!.endMillis, (action as LiveClassResyncer.ResyncAction.StartLive).window.endMillis)
            } else {
                assertNull(
                    "${case.label}：周课表画不到的那一周不许有实况，更不许有那枚会撤掉真课实况与勿扰的下课铃",
                    window,
                )
                assertEquals("${case.label} ⇒ 收遗留而不是补实况", LiveClassResyncer.ResyncAction.ClearLeftovers, action)
            }
        }

        // 越界那一档挑出来的正是第 17 周（病灶响的是屏上画不到的那一节），这条把"两档确实分岔"钉死
        val unboundedWindow = ClassProgressScheduler.planNextClassWindow(
            courses = courses,
            semesterStart = semesterStart,
            timeSlots = slots,
            now = insideWeekSeventeen,
            totalWeeks = TermWeekGate.UNBOUNDED_TOTAL_WEEKS,
        )
        assertEquals("不判上界时挑的是第 17 周", 17, unboundedWindow?.week)
    }

    private companion object {
        /** 一学期的总周数：短到第 17 周必然越界，又不至于让用例读成魔法数 */
        const val SIXTEEN = 16
    }
}
