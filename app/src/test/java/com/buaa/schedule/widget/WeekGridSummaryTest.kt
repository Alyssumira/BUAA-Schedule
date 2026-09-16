package com.buaa.schedule.widget

import com.buaa.schedule.domain.model.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4×2 紧凑网格一格里到底画几节课，以及宿主缓存键会不会让改完的课表"变回原样"。
 *
 * 两条都是真机反馈直接钉下来的：
 * - 「一天只显示一节课」= 旧写法把「教室 + 全名」塞进约 30dp 的列，一行折三四屏宽，
 *   `maxLines` 一截就只剩第一节课；
 * - 「手动刷新后一点击又变回原样」= 宿主按 `getItemId` 缓存行视图，
 *   旧 id 不含课名/教室，内容变了 id 不变 → 贴回旧行。
 */
class WeekGridSummaryTest {

    private fun course(
        name: String,
        day: Int,
        vararg periods: Int,
        alias: String? = null,
    ) = Course(
        id = day * 100L + (periods.firstOrNull() ?: 0),
        name = name,
        alias = alias,
        dayOfWeek = day,
        periods = periods.toList().ifEmpty { listOf(1) },
        weeks = (1..16).toList(),
        location = "主楼A-101",
    )

    @Test
    fun `每节课一行且不带教室`() {
        val summary = weekGridDaySummary(
            listOf(
                course("高等数学 A", 1, 1, 2),
                course("线性代数", 1, 3),
                course("大学英语听说", 1, 5, 6),
            ),
        )
        assertEquals("1高等数\n3线性代\n5大学英", summary)
    }

    @Test
    fun `别名优先于教务原名`() {
        val summary = weekGridDaySummary(
            listOf(course("高等数学（A）", 2, 1, alias = "高数")),
        )
        assertEquals("1高数", summary)
    }

    @Test
    fun `装不下的部分收成一行加号`() {
        val many = (1..7).map { course("课程$it", 3, it) }
        val lines = weekGridDaySummary(many).lines()
        assertEquals(WEEK_GRID_MAX_LINES, lines.size)
        assertEquals("＋2", lines.last())
    }

    @Test
    fun `空的一天是空串而不是占位文本`() {
        assertEquals("", weekGridDaySummary(emptyList()))
    }

    @Test
    fun `短名去掉括号补充与空格后截到三个字`() {
        assertEquals("物理实", weekGridShortName("物理实验（大学物理）"))
        assertEquals("体育与", weekGridShortName("体育 与健康"))
    }
}

/** [WidgetCommon.itemKey] 的两条不变式：同列内不撞号、内容变了号就变。 */
class WidgetItemKeyTest {

    @Test
    fun `位置不同绝不会得到同一个键`() {
        val stamps = listOf(0L, 1L, -7L, 1L shl 40, Long.MAX_VALUE)
        stamps.forEach { stamp ->
            assertNotEquals(WidgetCommon.itemKey(0, stamp), WidgetCommon.itemKey(1, stamp))
        }
    }

    @Test
    fun `同一位置内容一变键就变`() {
        assertNotEquals(WidgetCommon.itemKey(2, 111L), WidgetCommon.itemKey(2, 222L))
    }

    @Test
    fun `键始终落在非负区间`() {
        // 高位是 position+1（最多 7），低 32 位是内容指纹，符号位取不到 1。
        (0 until 7).forEach { position ->
            assertTrue(WidgetCommon.itemKey(position, -1L) >= 0L)
        }
    }
}
