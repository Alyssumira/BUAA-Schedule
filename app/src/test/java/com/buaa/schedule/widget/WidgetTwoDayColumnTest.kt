package com.buaa.schedule.widget

import com.buaa.schedule.domain.model.Course
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * 「今明课表」一栏的正文：每行一节课的「上课时刻 + 短名」。
 *
 * 这一栏的宽度只有 110dp 出头，所以它的每一条排版决定都是硬约束：
 * 时刻必须是 ASCII 数字、名字只能留四个字、行首要占位否则两栏对不齐。
 */
class WidgetTwoDayColumnTest {

    private val monday = LocalDate.of(2026, 9, 14)

    /** 每节 45 分钟、课间 5 分钟：连堂并成一段，午休那种大间隔天然断开 */
    private val slots = (1..10).associateWith { period ->
        val start = LocalTime.of(8, 0).plusMinutes((period - 1) * 50L)
        start to start.plusMinutes(45)
    }

    private fun course(name: String, vararg periods: Int) = Course(
        name = name,
        teacher = "张三",
        location = "J3-101",
        dayOfWeek = 1,
        periods = periods.toList(),
        weeks = (1..16).toList(),
    )

    private fun lines(courses: List<Course>, at: LocalTime) =
        twoDayColumnLines(courses, slots, monday, monday.atTime(at))

    @Test
    fun `每行是上课时刻加四字短名`() {
        val out = lines(
            listOf(course("高等数学", 1, 2), course("线性代数", 5, 6)),
            at = LocalTime.of(7, 0),
        )
        // 行首那个空格是给 ▸ 留的槽位，不是排版失误
        assertEquals(" 08:00 高等数学\n 11:20 线性代数", out)
    }

    @Test
    fun `正在上的那节行首标三角`() {
        // 09:00 在 1-2 节这段里（08:00–09:35，含课间那五分钟）
        val out = lines(
            listOf(course("高等数学", 1, 2), course("线性代数", 5, 6)),
            at = LocalTime.of(9, 0),
        )
        assertEquals("▸08:00 高等数学\n 11:20 线性代数", out)
    }

    @Test
    fun `第五节折进末行的加号`() {
        val five = listOf(
            course("高等数学", 1),
            course("线性代数", 3),
            course("大学英语", 5),
            course("毛概", 7),
            course("体育", 9),
        )
        val out = lines(five, at = LocalTime.of(7, 0))
        assertEquals(4, out.lines().size)
        // 末行整个被计数器占掉，所以没点名的是 2 节（第 4、5 节），不是 1 节
        assertEquals(" 08:00 高等数学\n 09:40 线性代数\n 11:20 大学英语\n＋2", out)
    }

    @Test
    fun `节次表缺这一节时退回节次号而不是空行`() {
        val out = twoDayColumnLines(
            courses = listOf(course("高等数学", 1)),
            slotTimes = emptyMap(),
            date = monday,
            now = monday.atTime(9, 0),
        )
        assertEquals(" 第1节 高等数学", out)
    }

    @Test
    fun `四字预算里不泄漏括号`() {
        // 三格主干 + 一格括号首字；左括号本身不能占一格（两栏的 4 字档才会暴露这个问题）
        val out = lines(listOf(course("体育(篮球)", 1)), at = LocalTime.of(7, 0))
        assertEquals(" 08:00 体育篮", out)
    }

    @Test
    fun `空的一天交给调用方措辞`() {
        assertEquals("", lines(emptyList(), at = LocalTime.of(7, 0)))
    }
}
