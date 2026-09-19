package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 纯文本周课表每一行的拼法（导出后用户是往群里粘的，没有第二次机会纠正排版）。
 *
 * 这一份文案此前没有任何测试，所以两类口径一起钉住：
 * 节次齐全时**逐字**等于旧文案，节次为空时那一段连同 " · " 一起不出场。
 */
class WeeklyTextExportTest {

    private fun course(
        name: String,
        periods: List<Int>,
        location: String? = "J3-101",
    ) = Course(
        name = name,
        location = location,
        dayOfWeek = 1,
        periods = periods,
        weeks = listOf(1),
    )

    /** week=1 + 默认节次表：第 1 节 08:00 上课 */
    private fun export(c: Course) = ScheduleExporters.toWeeklyText(listOf(c), semester = null, timeSlots = emptyList(), week = 1)

    @Test
    fun `节次齐全的那一行逐字保持旧文案`() {
        assertEquals(
            "第 1 周\n\n周一\n08:00 高等数学 · J3-101 · 第1-2节",
            export(course("高等数学", listOf(1, 2))),
        )
        assertEquals(
            "第 1 周\n\n周一\n08:00 高等数学 · J3-101 · 第1-2,9-10节",
            export(course("高等数学", listOf(1, 2, 9, 10))),
        )
    }

    @Test
    fun `节次为空的课不导出第节也不留悬空分隔符`() {
        val text = export(course("高等数学", emptyList()))
        // 时刻也不给：startPeriod 在空节次时兜底成 1，那 08:00 是编出来的
        assertEquals("第 1 周\n\n周一\n高等数学 · J3-101", text)
        assertFalse(text, text.contains("第节"))
        assertFalse(text, text.trimEnd().endsWith("·"))
    }

    @Test
    fun `教室与节次同时缺席时只剩课名`() {
        assertEquals(
            "第 1 周\n\n周一\n高等数学",
            export(course("高等数学", emptyList(), location = "  ")),
        )
    }
}
