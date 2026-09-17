package com.buaa.schedule.widget

import com.buaa.schedule.domain.model.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 4×2 一格里的课程短名（审查 3.3 + 统筹 §2.2）。
 *
 * 一格约 30dp、只容三个字，而北航课名里"哪个班"的信息恰恰写在末尾：
 * 括号补充（`大学物理(上)`）或班型字母（`高等数学A`）。旧写法先砍括号再截断，
 * 于是上下午、篮球与游泳、A 班与 B 班在格子里长成同一个名字——
 * 用户看到的"课表少了半天的课"其实是被同名盖掉了。
 */
class WidgetShortNameTest {

    @Test
    fun `括号里的区分字内联进来`() {
        assertEquals("大学上", weekGridShortName("大学物理(上)"))
        assertEquals("大学下", weekGridShortName("大学物理(下)"))
        assertEquals("体育篮", weekGridShortName("体育(篮球)"))
        assertEquals("体育游", weekGridShortName("体育(游泳)"))
    }

    @Test
    fun `中文括号与数字班型同样生效`() {
        assertEquals("毛概1", weekGridShortName("毛概（1班）"))
        assertEquals("毛概2", weekGridShortName("毛概（2班）"))
    }

    @Test
    fun `没有括号时班型写在末尾也要让位`() {
        // 统筹 §2.2 实测补的规则：审查只写了括号那一类，这类截断后全是「高等数」
        assertEquals("高等B", weekGridShortName("高等数学B"))
        assertEquals("高等A", weekGridShortName("高等数学A"))
        assertEquals("线性1", weekGridShortName("线性代数1"))
    }

    @Test
    fun `撞名的两门课绝不会再得到同一个短名`() {
        val pairs = listOf(
            "大学物理(上)" to "大学物理(下)",
            "体育(篮球)" to "体育(游泳)",
            "高等数学A" to "高等数学B",
            "毛概（1班）" to "毛概（2班）",
        )
        pairs.forEach { (left, right) ->
            assertNotEquals("$left / $right 又撞成同一个短名", weekGridShortName(left), weekGridShortName(right))
        }
    }

    @Test
    fun `半截括号与空括号这些脏数据不炸`() {
        // 教务导出里确实有没闭合的括号；括号里没字时退回直接截断
        assertEquals("大学上", weekGridShortName("大学物理(上"))
        assertEquals("大学物", weekGridShortName("大学物理()"))
        assertEquals("C语言", weekGridShortName("C语言程序设计"))
    }

    @Test
    fun `整名都是拉丁时不按后缀让位`() {
        // 「Math101」让位后只剩「Ma1」，比直接截成「Mat」更认不出来
        assertEquals("Mat", weekGridShortName("Math101"))
    }

    @Test
    fun `多位尾巴只留最后一字守住字数预算`() {
        assertEquals("高等4", weekGridShortName("高等数学2024"))
        listOf(
            "大学物理(上)", "高等数学A", "高等数学2024", "Math101", "体育 与健康",
        ).forEach {
            assertEquals("$it 超出一格的字数预算", WEEK_GRID_NAME_CHARS, weekGridShortName(it).length)
        }
    }

    @Test
    fun `短到不用截的名字原样保留`() {
        assertEquals("高数", weekGridShortName("高数"))
        assertEquals("", weekGridShortName(""))
    }

    @Test
    fun `摘要里同一天上下午两门课看得出区别`() {
        val summary = weekGridDaySummary(
            listOf(
                course("大学物理(上)", 1, 1, 2),
                course("大学物理(下)", 1, 3, 4),
            ),
        )
        assertEquals("1大学上\n3大学下", summary)
    }

    private fun course(name: String, day: Int, vararg periods: Int) = Course(
        id = day * 100L + (periods.firstOrNull() ?: 0),
        name = name,
        dayOfWeek = day,
        periods = periods.toList().ifEmpty { listOf(1) },
        weeks = (1..16).toList(),
    )
}
