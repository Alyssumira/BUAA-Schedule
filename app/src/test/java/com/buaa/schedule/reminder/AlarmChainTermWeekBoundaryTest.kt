package com.buaa.schedule.reminder

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.ReminderSetting
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
 * T214 病灶 A 的**链上**行为表：把 `totalWeeks` 递给两条排铃链之后，
 * 「屏上画不到的那一周」在课前提醒、课堂窗口与自动勿扰三处一起消失。
 *
 * 与 [TermWeekGateTest] 的分工：那枚钉判据本身（五档格子），这一枚钉**两条链真的在用它**
 * （卡面："钉排铃两条链真的读同一枚在不在学期内的判据"）——
 * - 课前提醒链：[ReminderScheduler.planTiedReminders] / `planNextReminder`（体内走 `nextOccurrence`）；
 * - 课堂铃链：[ClassProgressScheduler.planNextClassWindow]（体内走 `nextOrCurrentWindow`），
 *   以及拿它判"要不要收勿扰"的 [ReminderScheduler.shouldTakeDownClassProgress]。
 *
 * 每一格都同时摆**有界**与**不判**两种输入：不判那一档就是病灶的形状，
 * 它必须还能复现出"第 17 周的课照样排铃"，否则这些格子等于没测（见 [bellsStillRingWhenTheBoundIsNotJudged]）。
 *
 * 零设备：三条入口都是纯函数（`now` / `zone` / 周数全是参数），排铃那一步要 Context，不在本文件账内。
 */
class AlarmChainTermWeekBoundaryTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    /** 学期第 1 周周一 = 2026-09-07；学期只有 16 周 ⇒ 最后一周是 12-21 那一周 */
    private val semesterStart = LocalDate.of(2026, 9, 7)
    private val totalWeeks = 16

    /** 第 17 周的周三（越上界那一周）：2026-09-07 + 16 周 = 12-28 那一周的周一，周三 = 12-30 */
    private val weekSeventeenWednesday = LocalDateTime.of(2026, 12, 30, 7, 0)

    private val slots = listOf(
        TimeSlot(number = 1, startTime = "08:00", endTime = "08:45"),
        TimeSlot(number = 2, startTime = "08:50", endTime = "09:35"),
    )

    private fun course(id: Long = 1L, weeks: List<Int>) = Course(
        id = id,
        name = "课程$id",
        dayOfWeek = 3, // 周三
        periods = listOf(1),
        weeks = weeks,
    )

    private fun mills(moment: LocalDateTime): Long = moment.atZone(zone).toInstant().toEpochMilli()

    private fun planAt(now: LocalDateTime, weeks: List<Int>, bound: Int?) =
        ReminderScheduler.planNextReminder(
            courses = listOf(course(weeks = weeks)),
            semesterStart = semesterStart,
            totalWeeks = bound,
            timeSlots = slots,
            reminders = mapOf(1L to ReminderSetting(courseId = 1, enabled = true, advanceMinutes = 10)),
            now = now,
            nowMillis = mills(now),
            zone = zone,
        )

    @Test
    fun reminderChainSkipsWeeksOutsideTheTerm() {
        val now = LocalDateTime.of(2026, 9, 8, 7, 0) // 开学第 1 周周三天还没亮

        // ① 界内：正常排上第 1 周
        val inside = planAt(now, weeks = (1..16).toList(), bound = totalWeeks)
        assertNotNull("界内的课必须照排（本卡只收越界那一档，不许多吃）", inside)
        assertEquals(1, inside?.week)

        // ② 越上界：weeks 里只有第 17 周 ⇒ 本轮没有下一条提醒（改前它会排到 12-30 那一周）
        assertNull(
            "屏上画不到的那一周不许有课前提醒",
            planAt(now, weeks = listOf(17), bound = totalWeeks),
        )

        // ③ 混着摆：[1,2,17] 在第 1 周仍然排得上，取的是界内的第 1 周而不是越界那周
        val mixed = planAt(now, weeks = listOf(1, 2, 17), bound = totalWeeks)
        assertEquals("越界周次只能被跳过，不能把整门课判死", 1, mixed?.week)

        // ④ 越下界：0 与负数周次同样不进候选（脏数据、旧版本遗留）
        assertEquals(
            "越下界的那一格要被跳过，剩下界内的照排",
            2,
            planAt(LocalDateTime.of(2026, 9, 16, 7, 0), weeks = listOf(0, -3, 2), bound = totalWeeks)?.week,
        )
        assertNull("全是脏周次时没有候选（不许凭空造一节 0 周的课）", planAt(now, weeks = listOf(0, -3), bound = totalWeeks))

        // ⑤ 学期结束之后的那一周（界内周次都已过）⇒ null，与展示侧"假期中"同判
        assertNull(
            "整学期过完后不再有课前提醒",
            planAt(LocalDateTime.of(2027, 1, 5, 7, 0), weeks = (1..16).toList(), bound = totalWeeks),
        )
    }

    /**
     * 病灶形状必须仍然复现得出来 —— 传 [TermWeekGate.UNBOUNDED_TOTAL_WEEKS]（= 不判上界）时
     * 第 17 周的课照样排得出闹钟。这一格是"探测器自己认得改前那一形"的行为面：
     * 如果两档给出同一个答案，上面那些格子就是空转。
     */
    @Test
    fun bellsStillRingWhenTheBoundIsNotJudged() {
        val now = LocalDateTime.of(2026, 9, 16, 12, 0) // 第 2 周周三的课已经上完

        val bounded = planAt(now, weeks = listOf(1, 2, 17), bound = totalWeeks)
        val unbounded = planAt(now, weeks = listOf(1, 2, 17), bound = TermWeekGate.UNBOUNDED_TOTAL_WEEKS)

        assertNull("界内两周都过了、第 17 周越界 ⇒ 学期内再没有下一条提醒", bounded)
        assertNotNull("不判上界时第 17 周照样被排上（这就是 #263 改前的形状）", unbounded)
        assertEquals(17, unbounded?.week)
        assertEquals(
            "越界那一周的日历时刻必须就是 12-30 那天 08:00（病灶响的是屏上画不到的那一节）",
            LocalDateTime.of(2026, 12, 30, 8, 0),
            unbounded?.classStart,
        )

        // 课堂窗口同一条判据：同一批输入在不判上界时挑得出窗口
        val windowBounded = ClassProgressScheduler.planNextClassWindow(
            courses = listOf(course(weeks = listOf(1, 2, 17))),
            semesterStart = semesterStart,
            timeSlots = slots,
            now = now,
            totalWeeks = totalWeeks,
        )
        val windowUnbounded = ClassProgressScheduler.planNextClassWindow(
            courses = listOf(course(weeks = listOf(1, 2, 17))),
            semesterStart = semesterStart,
            timeSlots = slots,
            now = now,
            totalWeeks = TermWeekGate.UNBOUNDED_TOTAL_WEEKS,
        )
        assertNull(windowBounded)
        assertNotNull(windowUnbounded)
        assertEquals(17, windowUnbounded?.week)
    }

    /**
     * 课堂铃链（第 2 问的另一半）：**没有学期行 / totalWeeks 取不到** ⇒ 一枚都不排。
     *
     * 排铃侧在这两档走的是"什么都不排"，展示侧走 `showAllCoursesWithoutWeek`（把课全画出来）——
     * 这一格钉的就是那道不对称：没有日历起点就没有可以吵醒用户的时刻，
     * 而"没有上界的周次"同样不能拿去承诺一次唤醒。
     */
    @Test
    fun unknownTermBoundsArmNothingOnBothChains() {
        val now = LocalDateTime.of(2026, 9, 8, 7, 0)

        assertNull(
            "totalWeeks 取不到（没有学期行）时课前提醒一枚都不排",
            planAt(now, weeks = (1..16).toList(), bound = null),
        )
        assertNull(
            "同一档下课堂窗口也挑不出来",
            ClassProgressScheduler.planNextClassWindow(
                courses = listOf(course(weeks = (1..16).toList())),
                semesterStart = semesterStart,
                timeSlots = slots,
                now = now,
                totalWeeks = null,
            ),
        )
    }

    /**
     * 勿扰那一格（卡面点名的后果："闹钟照响、勿扰照挂"）。
     *
     * 此刻正"上着"第 17 周那节课 —— 那一周屏上是空的。判据必须答**收干净**（挑不出窗口 ⇒ 没有
     * 需要保护的课堂状态），而不是"正在上课、别动"。不判上界时它答"别动"，勿扰就挂在一节
     * 不存在的课上，只能等下课铃（同样排给了不存在的课）或看门狗去解。
     */
    @Test
    fun dndIsTornDownForWeeksTheScreenCannotShow() {
        val inClassAtWeekSeventeen = LocalDateTime.of(2026, 12, 30, 8, 20) // 08:00-08:45 正在上

        val teardownWithBound = ReminderScheduler.shouldTakeDownClassProgress(
            courses = listOf(course(weeks = listOf(17))),
            semesterStart = semesterStart,
            totalWeeks = totalWeeks,
            timeSlots = slots,
            classProgressEnabled = true,
            dndEnabled = true,
            now = inClassAtWeekSeventeen,
            countingDownTo = { _, _ -> false },
        )
        val teardownWithoutBound = ReminderScheduler.shouldTakeDownClassProgress(
            courses = listOf(course(weeks = listOf(17))),
            semesterStart = semesterStart,
            totalWeeks = TermWeekGate.UNBOUNDED_TOTAL_WEEKS,
            timeSlots = slots,
            classProgressEnabled = true,
            dndEnabled = true,
            now = inClassAtWeekSeventeen,
            countingDownTo = { _, _ -> false },
        )

        assertTrue("画不到的那一周不算正在上课，勿扰必须当场收干净", teardownWithBound)
        assertEquals(
            "不判上界时它被当成正在上课（勿扰挂着没人解）—— 这一档就是 #263 的后果",
            false,
            teardownWithoutBound,
        )
    }

    /**
     * 展示侧那两枚旧调用点（`widget/WidgetCommon.kt` 的「下一节课」两处）走的是**不带 totalWeeks
     * 形参**的那一形：本卡不动它们（白名单外），形状必须与改前逐字一致，
     * 否则"跨卡那一格"就没法单独验收。
     *
     * T227 #332 之后这一格里已经没有 `reminder/` 的调用点了：实况重挂那一枚补上了学期上界
     * （它挑完窗口就排下课铃，从来不是展示侧），册子由
     * `TermWeekGateSingleSourceGuardTest` 第 ④ 层（全仓五处）与第 ⑧ 层（按目录，`reminder/`
     * 恰三处且全部带 bound）一起钉着。
     */
    @Test
    fun displaySideCallersKeepTheirCurrentShape() {
        val now = LocalDateTime.of(2026, 9, 16, 12, 0)
        val legacy = ClassProgressScheduler.planNextClassWindow(
            courses = listOf(course(weeks = listOf(1, 2, 17))),
            semesterStart = semesterStart,
            timeSlots = slots,
            now = now,
        )
        assertEquals("四形调用（默认不判上界）今天仍挑得出第 17 周", 17, legacy?.week)
        assertEquals(
            "默认值必须与显式传 UNBOUNDED 同解（否则展示侧的旧调用点在吃第二套口径）",
            legacy?.week,
            ClassProgressScheduler.planNextClassWindow(
                courses = listOf(course(weeks = listOf(1, 2, 17))),
                semesterStart = semesterStart,
                timeSlots = slots,
                now = now,
                totalWeeks = TermWeekGate.UNBOUNDED_TOTAL_WEEKS,
            )?.week,
        )
        assertTrue(weekSeventeenWednesday.isAfter(now))
    }
}
