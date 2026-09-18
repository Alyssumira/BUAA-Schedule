package com.buaa.schedule.core.designsystem

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 周次密度条的数据侧（`weekCourseCounts`）。
 *
 * 图形本身不在 JVM 里测，但"每根柱子的高度从哪来"必须钉住：这条函数一旦
 * 在越界周次或中间起排的周次上算错，图上看不出来、只有真机上对着课表才发现。
 */
class WeekCourseCountsTest {

    @Test
    fun `full semester counts every week`() {
        val courses = listOf(setOf(1, 2, 3, 4), setOf(1, 2, 3, 4))
        assertArrayEquals(intArrayOf(2, 2, 2), weekCourseCounts(courses, totalWeeks = 3))
    }

    @Test
    fun `weeks starting mid semester land on their own columns`() {
        // WeekParser 允许 {3,4,5} 这种从中间起排的周次
        val courses = listOf(setOf(3, 4, 5))
        assertArrayEquals(
            intArrayOf(0, 0, 1, 1, 1, 0),
            weekCourseCounts(courses, totalWeeks = 6),
        )
    }

    @Test
    fun `out of range weeks are dropped instead of crashing`() {
        // 教务数据里出现过周次大于学期总周数的行（R6 的跨周合并同源）
        val courses = listOf(setOf(0, 1, 19, 20))
        assertArrayEquals(intArrayOf(1, 0, 0), weekCourseCounts(courses, totalWeeks = 3))
    }

    @Test
    fun `non positive semester lengths produce an empty series`() {
        assertTrue(weekCourseCounts(listOf(setOf(1)), totalWeeks = 0).isEmpty())
        assertTrue(weekCourseCounts(listOf(setOf(1)), totalWeeks = -3).isEmpty())
    }

    @Test
    fun `no courses still gives one zero column per week`() {
        // 长度必须等于总周数：柱子少画几根，用户会以为学期变短了，
        // 而"一节课都没有"由 WeekDensityStrip 自己退化成文字提示
        assertArrayEquals(IntArray(3), weekCourseCounts(emptyList(), totalWeeks = 3))
    }

    @Test
    fun `duplicate week entries in one course count once`() {
        // Set 输入天然去重；这条钉的是"传进来的形态"，防止将来改成 List 时静默双计
        assertEquals(1, weekCourseCounts(listOf(setOf(2, 2, 2)), totalWeeks = 2)[1])
    }
}

/**
 * 今日时间带的数据侧（`dayTimelineSegments` / `dayFractionOfMinute`）。
 *
 * 分层与夹边这两件事画出来都"看着挺对"，只有断言能证明：
 * 换教室的两门课叠成一根、或者早八的课被窗口裁掉，都是真机上才暴露的错。
 */
class DayTimelineSegmentsTest {

    private fun seg(start: Int, end: Int, color: Int = 0) = Triple(start, end, color)

    @Test
    fun `window maps first slot to zero and last to one`() {
        assertEquals(0f, dayFractionOfMinute(7 * 60 + 30), 0.0001f)
        assertEquals(1f, dayFractionOfMinute(22 * 60 + 30), 0.0001f)
        assertEquals(0.5f, dayFractionOfMinute(15 * 60), 0.0001f)
    }

    @Test
    fun `slots outside the window clamp instead of disappearing`() {
        // 6:00 的早课与 23:00 的晚课都必须可见——看不见等于告诉用户"今天没课"
        assertEquals(0f, dayFractionOfMinute(6 * 60), 0.0001f)
        assertEquals(1f, dayFractionOfMinute(23 * 60), 0.0001f)
    }

    @Test
    fun `sequential slots share one lane`() {
        val segments = dayTimelineSegments(
            listOf(seg(8 * 60, 9 * 60 + 40), seg(10 * 60, 11 * 60), seg(14 * 60, 15 * 60)),
        )
        assertEquals(listOf(0, 0, 0), segments.map { it.lane })
    }

    @Test
    fun `overlapping slots get separate lanes`() {
        // 8:50 与 8:00-9:40 真重叠（换教室/分段课），压一起就看不清
        val segments = dayTimelineSegments(
            listOf(seg(8 * 60, 9 * 60 + 40), seg(8 * 60 + 50, 10 * 60 + 30)),
        )
        assertEquals(listOf(0, 1), segments.map { it.lane })
    }

    @Test
    fun `a lane is reused once the earlier slot ends`() {
        val segments = dayTimelineSegments(
            listOf(seg(8 * 60, 9 * 60), seg(10 * 60, 11 * 60), seg(8 * 60 + 30, 9 * 60 + 30)),
        )
        // 入参会先按开始时间排序，所以顺序是 8:00 / 8:30 / 10:00：
        // 8:30 与 8:00 真重叠只能另起一层，10:00 谁都不挡，把第一层复用回来
        assertEquals(listOf(0, 1, 0), segments.map { it.lane })
    }

    @Test
    fun `exactly overlapping slots keep a minimum visible width`() {
        val segments = dayTimelineSegments(
            listOf(seg(8 * 60, 9 * 60), seg(8 * 60, 9 * 60)),
        )
        assertTrue(segments.all { it.to > it.from })
        assertEquals(2, segments.map { it.lane }.distinct().size)
    }

    @Test
    fun `unordered input is sorted by start and empty input yields nothing`() {
        val reversed = dayTimelineSegments(
            listOf(seg(14 * 60, 15 * 60), seg(8 * 60, 9 * 60)),
        )
        assertTrue(reversed.first().from < reversed.last().from)
        assertTrue(dayTimelineSegments(emptyList()).isEmpty())
    }

    @Test
    fun `inverted slot does not produce a negative width`() {
        // 脏数据（end 早于 start）在库里出现过：夹成 from，再由最小宽度兜住
        val segments = dayTimelineSegments(listOf(seg(10 * 60, 9 * 60)))
        assertTrue(segments.single().to >= segments.single().from)
    }
}
