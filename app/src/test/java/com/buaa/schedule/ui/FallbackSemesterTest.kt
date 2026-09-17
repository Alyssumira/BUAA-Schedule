package com.buaa.schedule.ui

import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.startLocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalDate

/**
 * 学期行兜底构造（R5 §8-9）。
 *
 * `buildFallbackSemester` 决定"第 1 教学周是哪一天"，导入的周次、周视图、
 * 提醒排程全都以它为原点；此前它是 ViewModel 的私有方法，0 覆盖。
 */
class FallbackSemesterTest {

    private fun semester(
        termCode: String,
        startDate: String,
        totalWeeks: Int = 19,
    ) = Semester(
        id = 7L,
        termCode = termCode,
        termName = "$termCode 学期",
        startDate = startDate,
        totalWeeks = totalWeeks,
    )

    /** 2026-09-10 是周四，所在教学周从 2026-09-07（周一）开始 */
    private val thursday = LocalDate.of(2026, 9, 10)

    @Test
    fun keepsTheExistingRowWhenTermCodeMatches() {
        val current = semester("2026-2027-1", "2026-09-07")

        val result = buildFallbackSemester("2026-2027-1", current, thursday)

        assertSame("同一学期不能新建行，否则 id/周次原点全丢", current, result)
    }

    @Test
    fun anchorsNewTermToTodaysMondayNotTheOldStartDate() {
        val previous = semester("2025-2026-3", "2026-02-23", totalWeeks = 16)

        val result = buildFallbackSemester("2026-2027-1", previous, thursday)

        assertNotSame(previous, result)
        assertEquals("2026-2027-1", result.termCode)
        assertEquals("termName 用 termCode 占位，等教务数据回来再改", "2026-2027-1", result.termName)
        // 旧学期开学日（2 月）当新学期原点是错的：ICS 的 dateToWeek 整表错位、
        // 课次全落到 1..maxWeeks 之外，用户只看到误导性的「解析结果为空」
        assertEquals("新学期兜底原点应是今天的周一", "2026-09-07", result.startDate)
        assertEquals(16, result.totalWeeks)
    }

    @Test
    fun fallsBackToCurrentMondayWithoutAnySemester() {
        val result = buildFallbackSemester("2026-2027-1", null, thursday)

        assertEquals(LocalDate.of(2026, 9, 7), result.startLocalDate)
        assertEquals(20, result.totalWeeks)
    }

    @Test
    fun malformedStartDateDoesNotPoisonTheFallback() {
        // 老版本可能写入过非法日期：这里必须退到本周一，而不是把非法串继续传下去
        val broken = semester("2025-2026-3", "2026年2月23日")

        val result = buildFallbackSemester("2026-2027-1", broken, thursday)

        assertEquals("2026-09-07", result.startDate)
        assertEquals(19, result.totalWeeks)
    }

    @Test
    fun mondayOfAnyDayInTheWeekIsTheSame() {
        val monday = LocalDate.of(2026, 9, 7)
        for (offset in 0L..6L) {
            assertEquals("偏移 $offset 天也应算出同一个周一", monday, mostRecentMonday(monday.plusDays(offset)))
        }
    }
}
