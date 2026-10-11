package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.ReminderSetting
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChainReadSnapshot.covers] 的那一枚判据（#536 F2 / 台账 #218）。
 *
 * 这件事为什么值得单独钉：链上「组件快照」那一步是**按 key 排产**的（每个学期一行），
 * 载体里那一份课程册子只回答了它上游那一问 —— `getDisplayCourses(semester)` 里的
 * `semester` 就是入口读到的那一枚学期行。所以「这一行的课程能不能从载体里拿」
 * 是一条**只在 termCode 上成立**的判据：拿宽了（比如"载体有课程就直接给"）就会把
 * 上学期的课画进这学期，拿窄了（比如"必须是同一个实例"）载体就一律用不上、
 * 这张卡等于没改。判据本体长在类体里（不是扩展函数、不在调用点），因为它是
 * "载体自己知道答案覆盖了哪一问"这件事 —— 谁调用都不该重算一遍。
 *
 * 表驱动的格子按仓储层那一问的实际入参摆（`ScheduleRepository.getDisplayCourses`）：
 * `row == null` 走 `courseDao.getAll()`，非 null 走 `courseDao.getDisplay(row.termCode)`，
 * 于是"同一问"⟺"同一档 + 同一枚 termCode"。null/null 与 null/非 null 两档都在，
 * 因为方向相反的两臂各只能被一侧的格子红到。
 *
 * 零 android、零时钟、零库：输入全是已经造好的行 —— 这正是 #536 第⓪问裁定「甲」之后
 * 允许存在的唯一一枚判据（其余全是接线，接线由 `ChainReadSnapshotWiringGuardTest` 按源码形状核对）。
 */
class ChainReadSnapshotTest {

    private fun semester(
        code: String,
        id: Long = 0L,
        startDate: String = "2026-09-07",
        totalWeeks: Int = 16,
    ) = Semester(
        id = id,
        termCode = code,
        termName = "学期 $code",
        startDate = startDate,
        totalWeeks = totalWeeks,
    )

    private fun course(name: String) = Course(
        name = name,
        dayOfWeek = 1,
        periods = listOf(1),
        weeks = listOf(1),
    )

    private val timeSlots = listOf(TimeSlot(number = 1, startTime = "08:00", endTime = "08:45"))

    /** 载体的构造 helper：只关心学期那一格时其余三格给空 */
    private fun carrier(
        held: Semester?,
        courses: List<Course> = listOf(course("高等数学")),
    ) = ChainReadSnapshot(
        semester = held,
        courses = courses,
        timeSlots = timeSlots,
        remindersByCourseId = mapOf(1L to ReminderSetting(courseId = 1L)),
    )

    /**
     * 表驱动：每一格的期望都是手写的（不是从判据里推出来的，推出来就是套套逻辑）。
     * `held` = 载体装的那枚学期行，`row` = 排产这一步要问的那枚。
     */
    @Test
    fun `covers 只认同一个 termCode 那一问`() {
        val current = semester("2026-2027-1")
        val other = semester("2025-2026-2")
        // getAllSemesters() 每轮新造对象：同 termCode、另一枚实例，是本仓最常见的真实形状
        val sameCodeOtherInstance = semester("2026-2027-1", id = 7L, startDate = "2026-09-06", totalWeeks = 18)

        val cells = listOf(
            // 档 1：两边都没有学期行 ⇒ 两侧都是 courseDao.getAll()，同一问
            Cell(held = null, row = null, expect = true, why = "没有学期那一档也是同一道查询"),
            // 档 2：一边没学期、一边有 ⇒ getAll() vs getDisplay(code)，两道不同的查询
            Cell(held = null, row = current, expect = false, why = "载体问的是 getAll()，这一行问的是按学期码"),
            Cell(held = current, row = null, expect = false, why = "反方向同样不成立：不能把按学期的册子当成全库"),
            // 档 3：同码同实例
            Cell(held = current, row = current, expect = true, why = "同一枚行当然同一问"),
            // 档 4：同码、另一枚实例，且 id/startDate/totalWeeks 三格全不同
            Cell(
                held = current,
                row = sameCodeOtherInstance,
                expect = true,
                why = "查询只吃 termCode，其余字段改不出另一个答案",
            ),
            Cell(
                held = sameCodeOtherInstance,
                row = current,
                expect = true,
                why = "方向反过来仍是同一问（判据不许偏袒载体装的那枚是'新'的）",
            ),
            // 档 5：不同码
            Cell(held = current, row = other, expect = false, why = "上学期的课不是这学期的"),
            Cell(held = other, row = current, expect = false, why = "同上，反方向"),
            // 边界：termCode 为空串的退化行——判据就是纯字符串相等，不许夹"空就当没学期"的兜底
            Cell(held = semester(""), row = semester(""), expect = true, why = "两枚空码行的 getDisplay('') 也是同一问"),
            Cell(held = semester(""), row = null, expect = false, why = "空码 ≠ 没有学期：那是 getDisplay('') vs getAll()"),
            // 大小写敏感：教务码形如 2026-2027-1，没有别名，但判据不能顺手 equals(ignoreCase)
            Cell(held = semester("ABC"), row = semester("abc"), expect = false, why = "termCode 按字面比"),
        )

        for (cell in cells) {
            val snapshot = carrier(cell.held)

            if (cell.expect) {
                assertTrue("${cell.why}（载体=${cell.held?.termCode} 问=${cell.row?.termCode}）", snapshot.covers(cell.row))
            } else {
                assertFalse("${cell.why}（载体=${cell.held?.termCode} 问=${cell.row?.termCode}）", snapshot.covers(cell.row))
            }
        }
    }

    private class Cell(val held: Semester?, val row: Semester?, val expect: Boolean, val why: String)

    /**
     * 判据不看载荷：载体里的课程册子换成别的、空的，都不改变"覆盖了哪一问"的结论。
     *
     * 这一格防的是把判据写成"载体里非空就复用"：空册子（这学期真的一门课都没有）时
     * 那种写法会回库再查一遍同一问，而"没课"恰恰是最常见的状态之一（寒暑假）。
     */
    @Test
    fun `判据只看问的是哪一道不看答案是什么`() {
        val current = semester("2026-2027-1")

        assertTrue(carrier(current, courses = emptyList()).covers(current))
        assertTrue(carrier(current, courses = listOf(course("线性代数"), course("大学英语"))).covers(current))
        // 同一枚载体对别的学期码仍然不复用，与册子内容无关
        assertFalse(carrier(current, courses = emptyList()).covers(semester("2025-2026-2")))
    }

    /** 没有学期行时载体带的是空册子：covers(null) 成立，但 covers(任何学期) 不成立 */
    @Test
    fun `没有学期行时只回答没有学期那一问`() {
        val empty = carrier(held = null, courses = emptyList())

        assertTrue(empty.covers(null))
        assertFalse(empty.covers(semester("2026-2027-1")))
    }
}
