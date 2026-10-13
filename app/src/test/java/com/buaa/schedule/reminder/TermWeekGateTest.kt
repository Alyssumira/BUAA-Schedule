package com.buaa.schedule.reminder

import com.buaa.schedule.domain.schedule.SemesterWeekDates
import com.buaa.schedule.domain.schedule.WeekCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * T214 病灶 A（#263）的判据表：「这一周在不在学期内」。
 *
 * 内核是 [TermWeekGate.isInTermWeek]，**零 android、零时钟、零库**：
 * 学期起点与总周数全是参数（卡面硬口径 —— "不许在判据里读库、读钟"）。
 * 本文件钉的是判据本身；两条排铃链有没有真的读它，由
 * `reminder/TermWeekGateSingleSourceGuardTest`（源码形状）与
 * `reminder/AlarmChainTermWeekBoundaryTest`（链上行为）分别钉。
 *
 * 五档格子逐一点名（卡面交付要求第 1 条）：
 * 1. 在界内 → 放行；
 * 2. 越上界（`week > totalWeeks`）→ 不放行 —— 这一档就是病灶：屏上那一周是空的，闹钟照响；
 * 3. 越下界（0 与负数）→ 不放行；
 * 4. 无学期行（`semesterStart = null`）→ 不放行（与展示侧的 `showAllCoursesWithoutWeek` 故意不对称）；
 * 5. `totalWeeks` 取不到（null）→ 不放行（判不出上界就不造"能吵醒人"的承诺）。
 *
 * 另附两档同源与两处反面：
 * - [kernelVerdictIsTheSameRulerTheDisplaySideUses]：逐周核与
 *   [WeekCalculator.currentWeekOrNull] / [SemesterWeekDates.mondayOf] 那两把展示侧的尺**同进同退**；
 * - [verdictDoesNotDependOnWhatTodayIs]：同一枚周次在"过去的一学期"与"未来的一学期"上判定相同
 *   ⇒ 判据里没有今天（真读了钟，这两行的结果必然随 `LocalDate.now()` 漂）。
 */
class TermWeekGateTest {

    /** 学期第 1 周周一 */
    private val start = LocalDate.of(2026, 9, 7)
    private val totalWeeks = 16

    private data class Cell(
        val name: String,
        val week: Int,
        val semesterStart: LocalDate?,
        val totalWeeks: Int?,
        val expected: Boolean,
    )

    private val cells = listOf(
        // ① 界内
        Cell("第 1 周（开学那一周）⇒ 在界内", 1, start, totalWeeks, true),
        Cell("学期中间的第 8 周 ⇒ 在界内", 8, start, totalWeeks, true),
        Cell("边界那一周 totalWeeks 本身 ⇒ 在界内（上界取闭区间，与展示侧同一档）", 16, start, totalWeeks, true),
        // ② 越上界（病灶那一格）
        Cell("第 17 周而学期只有 16 周 ⇒ 不放行：屏上画不到，闹钟也不该响", 17, start, totalWeeks, false),
        Cell("第 20 周 ⇒ 同样不放行（教务脏数据 / 手动课 / fallbackWeeks 顶出来的高周次）", 20, start, totalWeeks, false),
        Cell("第 400 周（备份里用户可控的极端值）⇒ 不放行", 400, start, totalWeeks, false),
        Cell("totalWeeks = 1 的学期里第 2 周 ⇒ 不放行（学期越短越不能吃错）", 2, start, 1, false),
        // ③ 越下界
        Cell("第 0 周 ⇒ 不放行", 0, start, totalWeeks, false),
        Cell("第 -3 周（脏数据）⇒ 不放行", -3, start, totalWeeks, false),
        Cell("Int.MIN_VALUE ⇒ 不放行，也不许绕成正数", Int.MIN_VALUE, start, totalWeeks, false),
        // ④ 无学期行
        Cell("没有学期行（semesterStart = null）⇒ 不放行", 1, null, totalWeeks, false),
        Cell("没有学期行时第 8 周同样不放行（不是只有越界才不放行）", 8, null, totalWeeks, false),
        // ⑤ totalWeeks 取不到
        Cell("totalWeeks 取不到 ⇒ 不放行（判不出上界就不承诺有这一周）", 1, start, null, false),
        Cell("totalWeeks = 0（学期行是补建的、周数没根据）⇒ 第 1 周也不放行", 1, start, 0, false),
        Cell("totalWeeks 为负 ⇒ 不放行", 1, start, -5, false),
        // 两枚判据都为真时结果与"开学日期是不是周一"无关（生产一律已过 mondayOfWeekAnchor）
        Cell("开学日是周三（历史脏行）时第 3 周仍在界内", 3, LocalDate.of(2026, 9, 9), totalWeeks, true),
        Cell("开学日是周三时第 17 周仍然越界", 17, LocalDate.of(2026, 9, 9), totalWeeks, false),
    )

    @Test
    fun `五档格子逐条对账`() {
        for (cell in cells) {
            assertEquals(
                "${cell.name}（week=${cell.week}, start=${cell.semesterStart}, totalWeeks=${cell.totalWeeks}）",
                cell.expected,
                TermWeekGate.isInTermWeek(cell.week, cell.semesterStart, cell.totalWeeks),
            )
        }
    }

    /**
     * 同源核：把 1..(totalWeeks + 6) 每一周都摆一遍，内核的答复必须与展示侧那两把尺逐周一致 ——
     * 「屏上画得出这一周」⟺「这一周放行」。这一格就是卡面第 ② 问要的那句"判据与展示侧必须同源"
     * 的行为面证据（静态面在守卫里钉"比较式只有一处"）。
     */
    @Test
    fun kernelVerdictIsTheSameRulerTheDisplaySideUses() {
        for (week in 1..(totalWeeks + 6)) {
            val monday = SemesterWeekDates.mondayOf(start, week)
            // 展示侧：那一周的周一落在学期内，currentWeekOrNull 才会把它映射回同一个周号
            val drawable = monday != null && WeekCalculator.currentWeekOrNull(start, totalWeeks, monday) == week
            assertEquals("第 $week 周：排铃判据与展示判据必须同进同退", drawable, TermWeekGate.isInTermWeek(week, start, totalWeeks))
        }
        // 且界内/界外的分界线恰好是 totalWeeks（不是 +1 也不是 -1）
        assertTrue(TermWeekGate.isInTermWeek(totalWeeks, start, totalWeeks))
        assertFalse(TermWeekGate.isInTermWeek(totalWeeks + 1, start, totalWeeks))
    }

    /**
     * 零时钟：判据不许读"今天"。
     *
     * 做法是把同一枚周次放到**一整年前后的两学期**上各判一次，四次答复必须两两相同；
     * 只要内部读了 `LocalDate.now()`（`currentWeekOrNull` 的 `today` 形参在 T358 之前带一枚默认值，
     * 现在是必填参数，那一支已经走不进去了），
     * "过去学期"与"未来学期"的同一年纪就会给出不同的数。
     */
    @Test
    fun verdictDoesNotDependOnWhatTodayIs() {
        val past = start.minusYears(1)
        val future = start.plusYears(1)
        for (week in listOf(1, 8, 16, 17, 0)) {
            val here = TermWeekGate.isInTermWeek(week, start, totalWeeks)
            assertEquals("第 $week 周在去年那一学期上必须同判", here, TermWeekGate.isInTermWeek(week, past, totalWeeks))
            assertEquals("第 $week 周在明年那一学期上必须同判", here, TermWeekGate.isInTermWeek(week, future, totalWeeks))
        }
    }

    /**
     * "不判上界"那一档只能是展示侧的历史包袱：传 [TermWeekGate.UNBOUNDED_TOTAL_WEEKS] 时
     * 越上界也放行（这就是病灶回潮的形状），而 null 与它必须不同 —— 两档混成一件，
     * 守卫就没法数"哪一处调用点在拿它当默认值"。
     */
    @Test
    fun unboundedIsNotTheSameAsNoSemesterRow() {
        assertTrue(
            "UNBOUNDED 那一档就是不判上界（展示侧那三处旧调用点今天的形状）",
            TermWeekGate.isInTermWeek(400, start, TermWeekGate.UNBOUNDED_TOTAL_WEEKS),
        )
        assertFalse("null = 判不出上界 ⇒ 不放行，与不判不是一回事", TermWeekGate.isInTermWeek(1, start, null))
        assertEquals(
            "而 semesterStart 缺失时连日期都算不出来，两档都得闭嘴",
            false,
            TermWeekGate.isInTermWeek(1, null, TermWeekGate.UNBOUNDED_TOTAL_WEEKS),
        )
    }
}
