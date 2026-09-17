package com.buaa.schedule.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 今日组件里"哪一行正在进行、哪些已经上完"的判定。
 *
 * 踩过的坑都在这几条里：跨午饭的课不能整段算进行中（否则下午那节提前亮底）、
 * 缺节次表时不能把一天的课抹成灰的（那是数据问题，不是"今天没课了"）。
 */
class WidgetRowStatusTest {

    private val monday: LocalDate = LocalDate.of(2026, 9, 14)

    /** 作息：1/2 连堂、5/6 之间隔午饭、9/10 晚课 */
    private val slots = mapOf(
        1 to (LocalTime.of(8, 0) to LocalTime.of(8, 45)),
        2 to (LocalTime.of(8, 55) to LocalTime.of(9, 40)),
        5 to (LocalTime.of(10, 40) to LocalTime.of(11, 25)),
        6 to (LocalTime.of(14, 45) to LocalTime.of(15, 30)),
        9 to (LocalTime.of(16, 0) to LocalTime.of(16, 45)),
        10 to (LocalTime.of(16, 55) to LocalTime.of(17, 40)),
    )

    private fun at(hour: Int, minute: Int = 0) = monday.atTime(hour, minute)

    private fun status(periods: List<Int>, now: LocalDateTime, table: Map<Int, Pair<LocalTime, LocalTime>> = slots) =
        widgetRowStatus(periods, monday, table, now)

    @Test
    fun `连堂的课间十分钟仍算进行中`() {
        // 08:50：第 1 节刚下课、第 2 节还没上课，1-2 是一个连续段，课间十分钟底色不该闪断
        assertEquals(WidgetRowStatus.ONGOING, status(listOf(1, 2), at(8, 50)))
    }

    @Test
    fun `隔午饭的5-6节不在中午亮成进行中`() {
        // 13:00 落在 11:25→14:45 那段午饭里：整段区间会判成"正在上"，切段后前半已过去
        assertEquals(WidgetRowStatus.UPCOMING, status(listOf(5, 6), at(13, 0)))
        assertEquals(WidgetRowStatus.ONGOING, status(listOf(5, 6), at(11, 0)))
        assertEquals(WidgetRowStatus.ONGOING, status(listOf(5, 6), at(15, 0)))
    }

    @Test
    fun `一天的课全上完了才标已结束`() {
        assertEquals(WidgetRowStatus.PAST, status(listOf(1, 2), at(18, 0)))
        // 17:00：上午的课早过去了，晚课（16:00-17:40）还在上，不能被前面的行一起灰掉
        assertEquals(WidgetRowStatus.ONGOING, status(listOf(9, 10), at(17, 0)))
    }

    @Test
    fun `没有节次表时保持常态而不是整列抹灰`() {
        assertEquals(WidgetRowStatus.UPCOMING, status(listOf(1, 2), at(23, 0), emptyMap()))
        // 只缺这一节的时间：其它段照常判，别把"读不到下课时间"当成"今天结束了"
        assertEquals(WidgetRowStatus.UPCOMING, status(listOf(3), at(23, 0)))
    }

    @Test
    fun `下课时间早于上课时间的脏数据回落到单节时长`() {
        // 结束时刻不可信时按 45 分钟一节课兜底，此刻仍在 08:30 < 08:00+45，算进行中
        val broken = mapOf(1 to (LocalTime.of(8, 0) to LocalTime.of(8, 0)))
        assertEquals(WidgetRowStatus.ONGOING, status(listOf(1), at(8, 30), broken))
        assertEquals(WidgetRowStatus.PAST, status(listOf(1), at(9, 0), broken))
    }

    @Test
    fun `进行中的标记放在句首`() {
        // 窄屏会截断行尾，状态在句首才留得住
        assertEquals("进行中 · J3-101 · 1-2节", widgetRowStatusMark(WidgetRowStatus.ONGOING, "J3-101 · 1-2节"))
        assertEquals("进行中", widgetRowStatusMark(WidgetRowStatus.ONGOING, ""))
        assertEquals("进行中", widgetRowStatusMark(WidgetRowStatus.ONGOING, "   "))
    }

    @Test
    fun `未开始与已结束的行列内容一个字都不改`() {
        val meta = "J3-101 · 1-2节"
        assertEquals(meta, widgetRowStatusMark(WidgetRowStatus.UPCOMING, meta))
        assertEquals(meta, widgetRowStatusMark(WidgetRowStatus.PAST, meta))
    }
}
