package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [#524]（T275 审计 N1）的表驱动单测：星期序号 → 日期偏移那一判的档位表，
 * 外加一枚「同一门课在改前会被推成哪一天」的端到端格。
 *
 * 纯 JVM：入参是一枚枚 `Int`，出来的是 sealed 档位，全程不读钟、不读时区、不碰安卓。
 * 学期起点固定 2026-09-07（周一）、总周数 19 —— 卡面「真实复现配置」那一枚，日期全是硬编码，
 * 所以这一格红的时候只能是因为**判据变了**，不会因为跑在别的日子上。
 */
class WeekdayDateOffsetTest {

    /**
     * @param expectedTier 该当的档位（`"Accepted"` / `"BeforeWeek"` / `"AfterWeek"`）
     * @param expectedOffset 该当的偏移天数（Rejected 两档交回的是「改前那一手本来会加上的数」）
     * @param expectedLegalInRange 写侧 [CourseConstraints.normalize] 是否留得下这一行
     */
    private data class Cell(
        val name: String,
        val dayOfWeek: Int,
        val expectedTier: String,
        val expectedOffset: Long,
        val expectedLegalInRange: Boolean,
    )

    private val cells = listOf(
        // ── 合法区：1 与 7 是两枚边界，各取「边界本身 + 内侧一格」──
        Cell("下界本身 1（周一）", 1, "Accepted", 0L, true),
        Cell("下界内侧 2（周二）", 2, "Accepted", 1L, true),
        Cell("正中间 4（周四）", 4, "Accepted", 3L, true),
        Cell("上界内侧 6（周六）", 6, "Accepted", 5L, true),
        Cell("上界本身 7（周日）", 7, "Accepted", 6L, true),
        // ── 越下界：1 的左邻一格一格往外 ──
        Cell("越下界一格 0（教务真返回过）", 0, "BeforeWeek", -1L, false),
        Cell("越下界 0 的左邻 -1", -1, "BeforeWeek", -2L, false),
        Cell("越下界很深 -30", -30, "BeforeWeek", -31L, false),
        // ── 越上界：7 的右邻一格一格往外 ──
        Cell("越上界一格 8（教务真返回过）", 8, "AfterWeek", 7L, false),
        Cell("越上界 8 的右邻 9", 9, "AfterWeek", 8L, false),
        Cell("一周之外 30", 30, "AfterWeek", 29L, false),
        // ── 两枚极值：档位不许被数值本身带跑 ──
        Cell("Int 上极", Int.MAX_VALUE, "AfterWeek", (Int.MAX_VALUE - 1).toLong(), false),
        Cell("Int 下极（改前那一手在这里溢出）", Int.MIN_VALUE, "BeforeWeek", Int.MIN_VALUE.toLong() - 1L, false),
    )

    @Test
    fun tierTableMatchesEveryCell() {
        val mismatches = mutableListOf<String>()
        for (cell in cells) {
            val tier = WeekdayDateOffset.dayOffsetOrReject(cell.dayOfWeek)
            val tierName = when (tier) {
                is WeekdayOffset.Accepted -> "Accepted"
                is WeekdayOffset.BeforeWeek -> "BeforeWeek"
                is WeekdayOffset.AfterWeek -> "AfterWeek"
            }
            if (tierName != cell.expectedTier || tier.offsetDays != cell.expectedOffset || tier.dayOfWeek != cell.dayOfWeek) {
                mismatches += "${cell.name}：dayOfWeek=${cell.dayOfWeek} → 档位=$tierName" +
                    "(期望 ${cell.expectedTier})、偏移=${tier.offsetDays}(期望 ${cell.expectedOffset})"
            }
        }
        assertEquals("档位表逐格核对（钉法 = 表驱动）：\n" + mismatches.joinToString("\n"), emptyList<String>(), mismatches)
    }

    @Test
    fun zeroAndEightAreNotSymmetricAndTheKernelSaysWhichSide() {
        // 卡面点名要量的一格：0 与 8 不是「同一个越界」的两个符号，两档各说各的落点。
        // 差出方向的那一行就是 `(dayOfWeek - 1)` 那一步的符号：没有任何一处做夹取。
        fun tierName(dayOfWeek: Int) = when (val tier = WeekdayDateOffset.dayOffsetOrReject(dayOfWeek)) {
            is WeekdayOffset.Accepted -> "Accepted"
            is WeekdayOffset.BeforeWeek -> "BeforeWeek"
            is WeekdayOffset.AfterWeek -> "AfterWeek"
        }
        assertEquals("dayOfWeek=0 该判成越下界", "BeforeWeek", tierName(0))
        assertEquals("dayOfWeek=8 该判成越上界", "AfterWeek", tierName(8))

        val zero = WeekdayDateOffset.dayOffsetOrReject(0)
        val eight = WeekdayDateOffset.dayOffsetOrReject(8)
        assertEquals("dayOfWeek = 0 的偏移（改前那一手：推到上一周的周日）", -1L, zero.offsetDays)
        assertEquals("dayOfWeek = 8 的偏移（改前那一手：推到下一周的周一）", 7L, eight.offsetDays)

        val semesterStart = LocalDate.of(2026, 9, 7)
        val weekMonday = semesterStart.plusWeeks(2) // 第 3 周的周一
        assertEquals("0 那一档改前落在哪一天：上一周（第 2 周）的周日", LocalDate.of(2026, 9, 20), weekMonday.plusDays(zero.offsetDays))
        assertEquals("8 那一档改前落在哪一天：下一周（第 4 周）的周一", LocalDate.of(2026, 9, 28), weekMonday.plusDays(eight.offsetDays))
    }

    @Test
    fun kernelDoesNotOverflowAtIntMinWhereThePreFixFormDid() {
        // 改前那一形 `(course.dayOfWeek - 1).toLong()` 先在 Int 里算减法：
        // Int.MIN_VALUE - 1 溢出成 Int.MAX_VALUE，"往上一周偏"被说成"往下一周偏"。
        // 内核先升 Long 再减，所以那一档交回的是负偏移 —— 档位与数得同时说得出口。
        val preFixOffset = (Int.MIN_VALUE - 1).toLong()
        val kernelOffset = WeekdayDateOffset.dayOffsetOrReject(Int.MIN_VALUE).offsetDays

        assertEquals("改前那一手在 Int.MIN_VALUE 上溢出成正数（这是本格的前提，不是结论）", 2147483647L, preFixOffset)
        assertEquals("内核在同一个输入上交回真负数", -2147483649L, kernelOffset)
    }

    @Test
    fun legalRangeIsWhatTheKernelAcceptsAndNothingElse() {
        assertEquals("合法下界（ISO 周一 = 1）", 1, WeekdayDateOffset.FIRST_DAY_INDEX)
        assertEquals(
            "合法上界必须**取自** SemesterStats.TOTAL_DAYS（展示侧那四枚数组内核用的是同一个数），不许再写一遍 7",
            SemesterStats.TOTAL_DAYS,
            WeekdayDateOffset.LAST_DAY_INDEX,
        )
        assertEquals("LEGAL_DAY_RANGE 就是 1..上界", (1..SemesterStats.TOTAL_DAYS).toList(), WeekdayDateOffset.LEGAL_DAY_RANGE.toList())

        val accepted = (0..10).filter { WeekdayDateOffset.dayOffsetOrReject(it) is WeekdayOffset.Accepted }
        assertEquals("0..10 这一扫里只有 1..7 该被放行", (1..7).toList(), accepted)
        assertEquals(
            "区间外的两枚极值都必须判死（放行其中任何一枚就是 W 臂那条「把脏星期推成真实日子」）",
            listOf<Int>(),
            listOf(Int.MIN_VALUE, -1, 0, 8, 30, Int.MAX_VALUE).filter {
                WeekdayDateOffset.dayOffsetOrReject(it) is WeekdayOffset.Accepted
            },
        )
    }

    private val semester = Semester(
        termCode = "T",
        termName = "T",
        startDate = "2026-09-07",
        totalWeeks = 19,
    )

    private fun course(dayOfWeek: Int, weeks: List<Int> = listOf(3)) = Course(
        name = "脏星期测试",
        teacher = "张三",
        location = "J3-101",
        dayOfWeek = dayOfWeek,
        periods = listOf(3),
        weeks = weeks,
    )

    /** 卡面「真实复现配置」：学期起始 2026-09-07、totalWeeks=19、weeks=[3]、第 3 节 */
    @Test
    fun endToEndDirtyWeekdayDrawsNoOccurrenceAfterTheFix() {
        val beforeFixShapeOf = { dayOfWeek: Int ->
            // 改前那一手，逐字照抄（本卡要的"改前"读数只能来自这里）：
            // val date = semesterStart.plusWeeks((week - 1).toLong()).plusDays((course.dayOfWeek - 1).toLong())
            LocalDate.of(2026, 9, 7).plusWeeks(2L).plusDays((dayOfWeek - 1).toLong())
        }

        // ── 改前：脏星期推出两枚真实日子，且都落在周课表画不出来的那一格之外 ──
        assertEquals("改前 dayOfWeek=0 会被写成系统日历里的那一天", LocalDate.of(2026, 9, 20), beforeFixShapeOf(0))
        assertEquals("改前 dayOfWeek=8 会被写成系统日历里的那一天", LocalDate.of(2026, 9, 28), beforeFixShapeOf(8))
        // ── 改后：同一门课一枚课次都不产，日历与 ICS 里那两天不存在 ──
        assertEquals(
            "改后 dayOfWeek=0 的课次（本格是修好与否的判据：非空 = 修复没落地）",
            emptyList<String>(),
            ScheduleOccurrences.build(semester, listOf(course(0)), emptyList()).occurrences.map { it.date.toString() },
        )
        assertEquals(
            "改后 dayOfWeek=8 的课次",
            emptyList<String>(),
            ScheduleOccurrences.build(semester, listOf(course(8)), emptyList()).occurrences.map { it.date.toString() },
        )
        assertEquals(
            "改后 dayOfWeek=-1 的课次",
            emptyList<String>(),
            ScheduleOccurrences.build(semester, listOf(course(-1)), emptyList()).occurrences.map { it.date.toString() },
        )
        // 同一门课换成合法星期，日期一格不差地回来（证明上面三格是**星期那一判**在起作用）
        assertEquals(
            "同一门课 dayOfWeek=3（合法）仍按第 3 周的周三产出",
            listOf("2026-09-23"),
            ScheduleOccurrences.build(semester, listOf(course(3)), emptyList()).occurrences.map { it.date.toString() },
        )
    }

    @Test
    fun endToEndLegalWeekdaysStillSpanTheWholeWeek() {
        val produced = (1..7).associateWith { day ->
            ScheduleOccurrences.build(semester, listOf(course(day)), emptyList()).occurrences.map { it.date.toString() }
        }
        val expected = (1..7).associateWith { day ->
            listOf(LocalDate.of(2026, 9, 7).plusWeeks(2L).plusDays((day - 1).toLong()).toString())
        }
        assertEquals("七列合法星期必须逐一产课次，日期 = 第 3 周周一 + (day-1)（N 臂把 7 判死就红这一格）", expected, produced)
        // 第 1 周 + 脏 0：改前会推到**开学日之前**那一天，属于屏上与学期外都无根据的日程
        assertEquals(
            "改前 weeks=[1]、dayOfWeek=0 会落在 2026-09-06（开学日 2026-09-07 之前）",
            LocalDate.of(2026, 9, 6),
            LocalDate.of(2026, 9, 7).plusWeeks(0L).plusDays(-1L),
        )
        assertEquals(
            "改后这一门课一枚都不产",
            emptyList<String>(),
            ScheduleOccurrences.build(semester, listOf(course(0, weeks = listOf(1))), emptyList()).occurrences.map { it.date.toString() },
        )
    }

    @Test
    fun dirtyWeekdayDoesNotInflateTheMissingSlotTally() {
        // OccurrenceBuildResult.skipped 的册子逐字写着「因节次时间缺失被跳过」，界面那句文案照这一句念。
        // 脏星期跳过的行**不许**混进那一枚，否则一句实话变成一句混话（要念脏星期得单开一本册子）。
        val dirty = ScheduleOccurrences.build(semester, listOf(course(0)), emptyList())
        assertEquals("脏星期不产课次", 0, dirty.occurrences.size)
        assertEquals("脏星期不进「节次时间缺失」那本册子", 0, dirty.skipped)

        val missingSlot = ScheduleOccurrences.build(
            semester,
            listOf(course(3).copy(periods = listOf(1, 9))),
            listOf(com.buaa.schedule.domain.model.TimeSlot(number = 1, startTime = "08:00", endTime = "08:45")),
        )
        assertEquals("缺时间那一档照旧进册子（本卡没动它）", 1, missingSlot.skipped)
    }

    @Test
    fun writeBoundaryAsksTheSameRulerAsTheReadSide() {
        // 丁档的写侧那一半：normalize 的星期那一判现在问同一枚内核，判死/留下的分界必须与档位表一致。
        for (cell in cells) {
            val normalized = CourseConstraints.normalize(course(cell.dayOfWeek))
            if (cell.expectedLegalInRange) {
                assertNotNull("${cell.name}：合法星期该留得下这一行", normalized)
                assertEquals("留下的行星期不变", cell.dayOfWeek, normalized?.dayOfWeek)
            } else {
                assertNull("${cell.name}：越界星期该整行判死", normalized)
            }
        }
        // 其余判据一个字节没动（防止把这一判顺手改成夹取/兜底）
        assertNull(CourseConstraints.normalize(course(3).copy(name = "  ")))
        assertNull(CourseConstraints.normalize(course(3).copy(periods = emptyList())))
    }
}
