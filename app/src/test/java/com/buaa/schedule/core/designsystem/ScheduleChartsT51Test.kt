package com.buaa.schedule.core.designsystem

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T51 三张增密图的几何侧（`ganttSpanFraction` / `ganttWeekStartFraction` / `weeklyLoadPoints`）。
 *
 * 图形本身不在 JVM 里测（与 [WeekCourseCountsTest] 同一分工），但横条/折线的坐标
 * 是纯算式，必须钉住"越出学期的周次被夹住而不是丢"这一条真坑——丢会让跨到学期末的课
 * 看起来提前结课，画出界会把条截断在画布外。
 */
class ChartGeometryTest {

    @Test
    fun `span maps to week index boundaries on the axis`() {
        // 第 3-8 周从第 2 周末起笔、到第 8 周末收笔
        assertEquals(0.125f to 0.5f, ganttSpanFraction(3..8, totalWeeks = 16))
    }

    @Test
    fun `span reaching the semester end ends at one`() {
        assertEquals(0f to 1f, ganttSpanFraction(1..16, totalWeeks = 16))
    }

    @Test
    fun `out of range span is clamped instead of leaving the canvas`() {
        // 教务给过 1-20 周而学期只有 16 周：直接除会得出 1.25 这种画出画布的数
        assertEquals(0f to 1f, ganttSpanFraction(1..20, totalWeeks = 16))
        assertEquals(13f / 16f to 1f, ganttSpanFraction(14..20, totalWeeks = 16))
    }

    @Test
    fun `dirty empty span never produces an inverted bar`() {
        // IntRange(9, 5) 是空区间，但 to 不许落在 from 左边（画出来就是从右往左的条）
        val (from, to) = ganttSpanFraction(9..5, totalWeeks = 16)
        assertTrue(to >= from)
    }

    @Test
    fun `single week semester uses the whole canvas`() {
        assertEquals(0f to 1f, ganttSpanFraction(1..1, totalWeeks = 1))
    }

    @Test
    fun `non positive axis lengths collapse to zero instead of dividing by zero`() {
        assertEquals(0f to 0f, ganttSpanFraction(1..3, totalWeeks = 0))
        assertEquals(0f to 0f, ganttSpanFraction(1..3, totalWeeks = -4))
        assertEquals(0f, ganttWeekStartFraction(3, totalWeeks = 0), 0.0001f)
    }

    @Test
    fun `over long semesters stay inside the unit interval`() {
        // 判据层把周轴夹到 30（CourseConstraints.MAX_TOTAL_WEEKS），几何函数自己也要夹得住
        val (from, to) = ganttSpanFraction(1..45, totalWeeks = 31)
        assertEquals(0f, from, 0.0001f)
        assertEquals(1f, to, 0.0001f)
    }

    @Test
    fun `week start cursor uses previous boundary and clamps into canvas`() {
        assertEquals(0f, ganttWeekStartFraction(1, totalWeeks = 16), 0.0001f)
        assertEquals(0.5f, ganttWeekStartFraction(9, totalWeeks = 16), 0.0001f)
        // 越界的周号（脏 currentWeek）夹进来，游标不许画到图外
        assertEquals(1f, ganttWeekStartFraction(17, totalWeeks = 16), 0.0001f)
        assertEquals(0f, ganttWeekStartFraction(0, totalWeeks = 16), 0.0001f)
    }

    @Test
    fun `peak week touches ceiling and empty week sits on floor`() {
        // y 以顶部为 0：地板那一档是 1，"这周没课"要看得见（画在地板上）而不是消失
        assertEquals(
            listOf(0f to 1f, 0.5f to 0.5f, 1f to 0f),
            weeklyLoadPoints(listOf(0L, 60L, 120L)),
        )
    }

    @Test
    fun `single week lands mid canvas so the point is visible`() {
        val point = weeklyLoadPoints(listOf(90L)).single()
        assertEquals(0.5f, point.first, 0.0001f)
        assertEquals(0f, point.second, 0.0001f)
    }

    @Test
    fun `all zero series still produces one floor point per week`() {
        // 全 0 是"整学期没课"，画一条贴地板的线；返回空表才是不画
        val points = weeklyLoadPoints(listOf(0L, 0L, 0L))
        assertEquals(3, points.size)
        assertTrue(points.all { it.second == 1f })
    }

    @Test
    fun `empty series produces no point`() {
        assertTrue(weeklyLoadPoints(emptyList()).isEmpty())
    }

    @Test
    fun `extreme minutes never push a point off the canvas`() {
        val points = weeklyLoadPoints(listOf(0L, 45L, Long.MAX_VALUE / 4, 1L))
        assertTrue(points.all { it.first in 0f..1f && it.second in 0f..1f })
    }
}

/**
 * 读屏文案侧（`ganttRowDescription` / `heatGridDayDescription`）。
 *
 * Canvas 画的东西读屏拿不到，这两条函数就是读屏用户的全部信息——
 * 尤其"没有周次数据"必须被说成数据缺失而不是"整学期没课"，与内核 [CourseWeekSpans]
 * 那条 weeksUnknown ≠ 空轨道的口径同一张嘴。
 */
class ChartDescriptionTest {

    private fun ganttRow(
        label: String = "高等数学",
        spans: List<IntRange> = listOf(1..8),
        weeksUnknown: Boolean = false,
        endsAtWeek: Int? = 8,
    ) = GanttRow(
        label = label,
        color = Color(0xFF112233),
        spans = spans,
        weeksUnknown = weeksUnknown,
        endsAtWeek = endsAtWeek,
    )

    private fun heatDay(occupied: List<Boolean>, label: String = "周三") =
        HeatGridDay(label = label, occupiedPeriods = occupied)

    @Test
    fun `unknown weeks says data missing not no classes`() {
        val text = ganttRowDescription(
            ganttRow(spans = emptyList(), weeksUnknown = true, endsAtWeek = null),
            totalWeeks = 16,
            currentWeek = 5,
        )
        assertTrue(text, text.contains("没有可用的周次数据"))
        assertTrue(text, !text.contains("第 5 周"))
    }

    @Test
    fun `disconnected spans are listed one by one`() {
        val text = ganttRowDescription(
            ganttRow(spans = listOf(1..1, 3..3, 5..7), endsAtWeek = 7),
            totalWeeks = 16,
            currentWeek = null,
        )
        assertTrue(text, text.contains("第 1 周、第 3 周、第 5-7 周"))
        assertTrue(text, text.contains("第 7 周结课"))
        assertTrue("没有当前周就不说『还剩几周』", !text.contains("还剩"))
    }

    @Test
    fun `remaining weeks are counted inclusively`() {
        val text = ganttRowDescription(
            ganttRow(spans = listOf(1..13), endsAtWeek = 13),
            totalWeeks = 16,
            currentWeek = 3,
        )
        assertTrue(text, text.contains("还剩 11 周"))
    }

    @Test
    fun `past end week is flagged as finished`() {
        val text = ganttRowDescription(
            ganttRow(spans = listOf(1..8), endsAtWeek = 8),
            totalWeeks = 16,
            currentWeek = 12,
        )
        assertTrue(text, text.contains("已结束"))
    }

    @Test
    fun `clipped empty spans still get an honest sentence`() {
        val text = ganttRowDescription(
            ganttRow(spans = emptyList(), endsAtWeek = null),
            totalWeeks = 16,
            currentWeek = 2,
        )
        assertTrue(text, text.contains("本学期没有落在周次范围内的排课"))
    }

    @Test
    fun `occupied cells are counted in the week under review`() {
        assertEquals(
            "周三：第 12 周 上 2 节",
            heatGridDayDescription(heatDay(listOf(true, false, true, false)), currentWeek = 12),
        )
    }

    @Test
    fun `a fully free day says so in the same scope`() {
        assertEquals(
            "周三：第 12 周 一节都没有",
            heatGridDayDescription(heatDay(List(4) { false }), currentWeek = 12),
        )
    }

    @Test
    fun `missing current week switches scope to whole semester`() {
        assertEquals(
            "周三：全学期 上 3 节",
            heatGridDayDescription(heatDay(listOf(true, true, true, false)), currentWeek = null),
        )
    }

    @Test
    fun `fully occupied day counts every cell`() {
        assertEquals(
            "周五：第 1 周 上 14 节",
            heatGridDayDescription(heatDay(List(14) { true }, label = "周五"), currentWeek = 1),
        )
    }
}
