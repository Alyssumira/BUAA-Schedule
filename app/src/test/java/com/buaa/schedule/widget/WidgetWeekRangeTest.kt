package com.buaa.schedule.widget

import com.buaa.schedule.domain.model.Semester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * 周课表类组件副标题的那一段日期区间。
 *
 * 标题已经写了「第 N 周课表」，副标题再念一遍周号是白占一行；
 * 这里钉住的是替代它的区间口径，以及三种拿不到区间时的降级。
 */
class WidgetWeekRangeTest {

    private val start = LocalDate.of(2026, 9, 7) // 周一

    private fun semester(weeks: Int = 16) = Semester(
        termCode = "2026-2027-1",
        termName = "2026秋季",
        startDate = start.toString(),
        totalWeeks = weeks,
    )

    private val week3 = start.plusWeeks(2).plusDays(3)

    @Test
    fun `一周就是周一到周日那七天`() {
        val (monday, sunday) = weekRange(semester(), 3)!!
        assertEquals(start.plusWeeks(2), monday)
        assertEquals(start.plusWeeks(3).minusDays(1), sunday)
        assertEquals("9/21–9/27", weekRangeLabel(semester(), 3))
    }

    @Test
    fun `开学日期不是周一时区间按自然周对齐`() {
        // 课表整体排在自然周上，所以周三开学的第 1 周仍然从那个周一算起
        val wednesdayStart = semester().copy(startDate = start.plusDays(2).toString())
        assertEquals("9/7–9/13", weekRangeLabel(wednesdayStart, 1))
    }

    @Test
    fun `标题开着时副标题只报日期`() {
        assertEquals(
            "9/21–9/27",
            weekRangeSubtitle(semester(), 3, week3, showTitle = true),
        )
    }

    @Test
    fun `标题关掉后周次必须由副标题带上`() {
        // showTitle 是用户可关的：关掉之后组件上一点周次信息都不剩，翻到哪周就没人知道了
        assertEquals(
            "第 3 周 · 9/21–9/27",
            weekRangeSubtitle(semester(), 3, week3, showTitle = false),
        )
    }

    @Test
    fun `假期里浏览照样给出区间并标出假期`() {
        assertEquals(
            "9/21–9/27 · 假期中",
            weekRangeSubtitle(semester(), 3, start.minusDays(30), showTitle = true),
        )
        assertEquals("假期中", weekRangeSubtitle(semester(), null, week3, showTitle = true))
    }

    @Test
    fun `算不出区间时退回周号而不是留空`() {
        val broken = semester().copy(startDate = "不是日期")
        assertNull(weekRange(broken, 3))
        assertEquals("", weekRangeLabel(broken, 3))
        // 脏日期不等于假期，不能顺手补一句「假期中」
        assertEquals("第 3 周", weekRangeSubtitle(broken, 3, week3, showTitle = true))
        assertEquals("第 3 周", weekRangeSubtitle(null, 3, week3, showTitle = true))
    }
}
