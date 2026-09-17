package com.buaa.schedule.widget

import com.buaa.schedule.domain.model.Semester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 组件表头「上一周 / 下一周」的纯逻辑（审查 3.5 + P2#11）。
 *
 * 三个必须钉住的边界：
 * - 点到底要**停住**，不能绕回学期另一头（那是日历翻页器不是课表）；
 * - 假期中也要能翻：寒假里提前看第 1 周安排是真实需求，基准周要退到最近的学期内周；
 * - 学期数据可能脏（未设置学期 / 开学日期解析失败 / totalWeeks 为 0），一律降级而不是抛。
 */
class WidgetWeekBrowseTest {

    private val start = LocalDate.of(2026, 9, 7) // 周一

    private fun semester(weeks: Int = 16) = Semester(
        termCode = "2026-2027-1",
        termName = "2026秋季",
        startDate = start.toString(),
        totalWeeks = weeks,
    )

    /** 第 3 周里的任意一天 */
    private val week3 = start.plusWeeks(2).plusDays(3)

    @Test
    fun `偏移为 0 就是当前周`() {
        assertEquals(3, displayWeekOf(semester(), week3, 0))
    }

    @Test
    fun `往后翻与往前翻`() {
        assertEquals(4, displayWeekOf(semester(), week3, 1))
        assertEquals(2, displayWeekOf(semester(), week3, -1))
        assertEquals(1, stepWeekOffset(baseWeek = 3, totalWeeks = 16, offset = 0, delta = 1))
    }

    @Test
    fun `点到底停住而不是绕回`() {
        // 基准第 3 周、共 16 周：往后最多 +13，再点还是第 16 周
        assertEquals(13, clampWeekOffset(3, 16, 99))
        assertEquals(16, displayWeekOf(semester(), week3, 99))
        assertEquals(13, stepWeekOffset(baseWeek = 3, totalWeeks = 16, offset = 13, delta = 1))
        // 往前同理：最多 -2 到第 1 周
        assertEquals(-2, clampWeekOffset(3, 16, -99))
        assertEquals(1, displayWeekOf(semester(), week3, -99))
        assertEquals(-2, stepWeekOffset(baseWeek = 3, totalWeeks = 16, offset = -2, delta = -1))
    }

    @Test
    fun `学期首尾的基准周只有一边可翻`() {
        assertEquals(0, clampWeekOffset(1, 16, -5))
        assertEquals(15, clampWeekOffset(1, 16, 99))
        assertEquals(-15, clampWeekOffset(16, 16, -99))
        assertEquals(0, clampWeekOffset(16, 16, 5))
    }

    @Test
    fun `假期里基准周退到最近的学期内周`() {
        val vacationBefore = start.minusDays(30)
        val vacationAfter = start.plusWeeks(20)
        assertEquals(1, browseBaseWeek(semester(), vacationBefore))
        assertEquals(16, browseBaseWeek(semester(), vacationAfter))
        // 寒假里想往前翻也停在第 1 周，而不是翻成第 0 周 / 负数
        assertEquals(1, displayWeekOf(semester(), vacationBefore, 0))
        assertEquals(2, displayWeekOf(semester(), vacationBefore, 1))
        assertEquals(1, displayWeekOf(semester(), vacationBefore, -3))
        assertEquals(16, displayWeekOf(semester(), vacationAfter, 5))
    }

    @Test
    fun `脏数据一律降级不抛异常`() {
        val midSemester = week3
        assertNull("未设置学期", displayWeekOf(null, midSemester, 0))
        assertNull("未设置学期时翻周也不该凭空造出周次", browseBaseWeek(null, midSemester))
        val broken = semester().copy(startDate = "不是日期")
        assertNull(displayWeekOf(broken, midSemester, 1))
        // totalWeeks = 0 会让 coerceIn(min > max) 直接抛，兜成 1 周
        assertEquals(1, displayWeekOf(semester(weeks = 0), midSemester, 0))
        assertEquals(0, clampWeekOffset(baseWeek = 0, totalWeeks = 0, offset = -7))
    }

    @Test
    fun `连点三次下一周等价于偏移 3`() {
        var offset = 0
        repeat(3) { offset = stepWeekOffset(baseWeek = 3, totalWeeks = 16, offset = offset, delta = 1) }
        assertEquals(3, offset)
        assertEquals(6, displayWeekOf(semester(), week3, offset))
    }

    @Test
    fun `真实周推进后翻周偏移自动作废`() {
        val binding = WidgetBinding(weekOffset = 1, weekOffsetBase = 3)
        // 还在取它的那一周里：偏移照旧生效
        assertEquals(1, effectiveWeekOffset(binding, 3))
        assertEquals(4, displayWeekOf(semester(), week3, binding))
        // 基准周变成第 4 周：那次预览已经完成使命，回到「跟本周」
        assertEquals(0, effectiveWeekOffset(binding, 4))
        assertEquals(0, effectiveWeekOffset(WidgetBinding(), 3))
        assertEquals(0, effectiveWeekOffset(binding, null))
        // 周日晚上点「下周」预览周一的课：到了周一，第 4 周本身就是那一周，
        // 偏移作废后显示的正是用户当时想看的内容
        assertEquals(4, displayWeekOf(semester(), week3.plusWeeks(1), binding))
    }

    @Test
    fun `组件显示的周次始终落在学期内`() {
        (-40..40).forEach { offset ->
            val week = displayWeekOf(semester(), week3, offset)!!
            assertTrue("偏移 $offset 翻出了第 $week 周", week in 1..16)
        }
    }
}
