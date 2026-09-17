package com.buaa.schedule.widget

import com.buaa.schedule.reminder.ClassProgressScheduler.ClassWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 「下一节课」组件顶行那一句话。
 *
 * 这个组件以前只会说"下一节课 · 周三 08:00"，而它的数据源给的是"尚未结束的最早一次课"
 * （**含正在上的这一节**）—— 课都上了四十分钟，它还在说明白要上什么。
 * 分家之后要钉住两条：下课铃那一刻起就该改口（结束时刻取开区间），
 * 以及标签上只有墙钟时刻、没有"还剩几分钟"（组件没有分钟级重绘，会走的数字停在下发的那一分钟，
 * 反而更容易被当成不准）。
 */
class WidgetNextLabelTest {

    @Test
    fun `上课铃到下课铃之间才算正在上`() {
        assertTrue(WidgetCommon.nextWidgetInClass(lesson(), millis(9, 0)))
        // 09:29 是下课铃前的最后一分钟
        assertTrue(WidgetCommon.nextWidgetInClass(lesson(), millis(9, 29)))
    }

    @Test
    fun `下课铃那一分钟起改口`() {
        // 结束时刻取开区间：09:30 铃已经响过，还挂着"正在上课"就是在说假话
        assertFalse(WidgetCommon.nextWidgetInClass(lesson(), millis(9, 30)))
        assertFalse(WidgetCommon.nextWidgetInClass(lesson(), millis(7, 59)))
        assertFalse(WidgetCommon.nextWidgetInClass(null, millis(9, 0)))
    }

    @Test
    fun `正在上课的标签写的是下课时刻`() {
        assertEquals("正在上课 · 09:30 下课", WidgetCommon.nextWidgetLabel(true, lesson()))
        assertEquals("下一节课", WidgetCommon.nextWidgetLabel(false, lesson()))
        // 判成进行中就必须给得出窗口，否则回常态而不是拼出半句话
        assertEquals("下一节课", WidgetCommon.nextWidgetLabel(true, null))
        assertEquals("下一节课", WidgetCommon.nextWidgetLabel(false, null))
    }

    private fun lesson(): ClassWindow = ClassWindow(
        courseId = 1L,
        courseName = "高等数学",
        location = "J3-101",
        sectionText = "第1-2节",
        startMillis = millis(8, 0),
        endMillis = millis(9, 30),
    )

    private fun millis(hour: Int, minute: Int): Long =
        DAY.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private companion object {
        private val DAY = LocalDate.of(2026, 9, 14)
    }
}
