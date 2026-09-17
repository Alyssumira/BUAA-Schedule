package com.buaa.schedule.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 顶栏周次标题（真机反馈：「本来是第二周，切换第三周后回到第二周，也会多一个（浏览）」）。
 *
 * 旧实现看的是"用户有没有手动翻过周"那个标记，翻回来标记还挂着；
 * 现在标题只由「看到的是哪一周」和「当前是哪一周」比出来。
 */
class WeekHeadlineTest {

    @Test
    fun `跟随本周时不带浏览标记`() {
        assertEquals("第2周", weekHeadline(hasSemester = true, displayWeek = 2, currentWeek = 2))
    }

    @Test
    fun `翻到别的周才带浏览标记`() {
        assertEquals("第3周（浏览）", weekHeadline(hasSemester = true, displayWeek = 3, currentWeek = 2))
    }

    @Test
    fun `翻出去再翻回本周 标记应当消失`() {
        // 这就是反馈里那条路径：2 → 3 → 2，最后一步 displayWeek 仍是显式设进去的 2
        val backToCurrent = weekHeadline(hasSemester = true, displayWeek = 2, currentWeek = 2)
        assertEquals("第2周", backToCurrent)
    }

    @Test
    fun `假期中没有周可看`() {
        assertEquals("假期中", weekHeadline(hasSemester = true, displayWeek = null, currentWeek = null))
    }

    @Test
    fun `假期里手动翻到某周仍算浏览`() {
        // currentWeek 为 null（学期外），此时看任何一周都不是"本周"
        assertEquals("第1周（浏览）", weekHeadline(hasSemester = true, displayWeek = 1, currentWeek = null))
    }

    @Test
    fun `没设置学期优先提示设置`() {
        assertEquals("未设置学期", weekHeadline(hasSemester = false, displayWeek = null, currentWeek = null))
        assertEquals("未设置学期", weekHeadline(hasSemester = false, displayWeek = 3, currentWeek = 2))
    }
}
