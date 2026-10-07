package com.buaa.schedule.ui.home

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.schedule.ConflictDetector
import com.buaa.schedule.domain.schedule.TermWeekGate
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T222 病灶 2（#306）：**首页那一维**。
 *
 * 改前 `HomeScreen` 里那一格是
 * `remember(state.conflicts) { state.conflicts.flatMap { listOf(it.first.id, it.second.id) }.toSet() }`
 * —— 交回的是**一枚 id 集合**，`Conflict.weeks` 里的周次信息就地丢掉 ⇒ 周课表在**任何一周**
 * 都给那张卡挂红色警示图标（`WeekView` 里 `inConflict = course.id in conflictCourseIds` 那一处），
 * 读屏照念「与其他课程时间冲突」，而那一周的 `coursesForContent` 里根本没有与它撞的课。
 *
 * 现在页面按**屏上正在画的那一周**取数（[conflictCourseIdsForWeek]），
 * 键表连着 `displayWeekNumber`。本文件摆的是这一枚纯函数的格子；
 * 「页面真的把这一维用上了」由 [ConflictCourseIdsPerWeekWiringGuardTest] 按源码钉。
 *
 * 零 android、零时钟：学期起点与总周数都当参数递进来（本仓内核口径）。
 */
class ConflictCourseIdsPerWeekTest {

    private val start = LocalDate.of(2026, 9, 7)
    private val termWeeks = 19

    private fun course(id: Long, weeks: List<Int>, day: Int = 1, periods: List<Int> = listOf(1, 2)) = Course(
        id = id,
        name = "C$id",
        dayOfWeek = day,
        periods = periods,
        weeks = weeks,
    )

    /** 一对在第 1、2 周（以及被裁掉的 20、21 周）都撞的课 + 一对只在第 5 周撞的课。 */
    private val clashFirstTwoWeeks = listOf(course(11L, listOf(1, 2, 20, 21)), course(12L, listOf(1, 2, 20, 21)))
    private val clashWeekFive = listOf(course(21L, listOf(5), day = 3), course(22L, listOf(5), day = 3))

    private fun conflictsWithTerm() = ConflictDetector.findConflicts(
        clashFirstTwoWeeks + clashWeekFive,
        start,
        termWeeks,
    )

    private val pairOne = setOf(11L, 12L)
    private val pairTwo = setOf(21L, 22L)

    private data class Cell(
        val name: String,
        val week: Int?,
        val expected: Set<Long>,
    )

    @Test
    fun bannerWeekByWeek() {
        val conflicts = conflictsWithTerm()
        val cells = listOf(
            Cell("第 1 周：真撞的那一对挂旗", 1, pairOne),
            Cell("第 2 周：同一对，仍挂", 2, pairOne),
            Cell("第 5 周：换另一对（周三那一对）", 5, pairTwo),
            Cell("第 3 周：谁也没撞 ⇒ 一张旗都不挂（改前这里给整屏的旧 id 集合挂旗）", 3, emptySet()),
            Cell("第 19 周（学期最后一周）：没有课在这一周撞", 19, emptySet()),
            Cell(
                "第 20 周（学期只有 19 周）：改前也挂旗 —— 屏上这一周是空网格，卡上却写着「与其他课程时间冲突」",
                20, emptySet(),
            ),
            Cell("第 400 周（用户可控的极端值）同样不挂", 400, emptySet()),
            Cell("null = 没有学期行那一档：网格走 showAllCoursesWithoutWeek 全画 ⇒ 两对都挂", null, pairOne + pairTwo),
        )
        for (cell in cells) {
            assertEquals(
                "${cell.name}（week=${cell.week}）—— 挂旗的课不对就是**首页那一维没接上**" +
                    "（冲突周次被扔掉 / 或裁过头连界内都不挂了）",
                cell.expected,
                conflictCourseIdsForWeek(conflicts, cell.week),
            )
        }
    }

    /**
     * 与冲突侧、排铃侧同一把尺的**行为面**证据：把 1..(totalWeeks + 3) 每一周都摆一遍，
     * 「这一周挂旗」必须与 [TermWeekGate.isInTermWeek] 同进同退（那一对撞的周次里只有 1、2 在界内）。
     */
    @Test
    fun bannerFollowsTheSameRulerAsTheAlarmChains() {
        val conflicts = conflictsWithTerm()
        for (week in 1..(termWeeks + 3)) {
            val flagged = conflictCourseIdsForWeek(conflicts, week).contains(11L)
            assertEquals(
                "第 $week 周：首页那一维与学期尺必须同进同退",
                TermWeekGate.isInTermWeek(week, start, termWeeks) && week <= 2,
                flagged,
            )
        }
    }

    /**
     * 判不出学期（没有学期行）时那一格**不裁**，首页那一维因此也不能凭空把旗全摘掉：
     * `findConflicts(..., null, null)` 交回的周次串里仍有 20、21，而网格那时是全画。
     */
    @Test
    fun withoutSemesterRowNothingGetsSilentlyUnflagged() {
        val unclipped = ConflictDetector.findConflicts(clashFirstTwoWeeks, null, null)
        assertEquals("改前的形状原样保留（第 20 周也在串里）", listOf(1, 2, 20, 21), unclipped.single().weeks)
        assertEquals(pairOne, conflictCourseIdsForWeek(unclipped, 20))
        assertEquals(pairOne, conflictCourseIdsForWeek(unclipped, null))
        assertTrue(
            "而学期尺判得出的那一周（第 1 周）当然照挂",
            conflictCourseIdsForWeek(unclipped, 1).isNotEmpty(),
        )
    }

    /** 空冲突表：任何一周都不挂，也不许抛。 */
    @Test
    fun emptyConflictListFlagsNothing() {
        for (week in listOf<Int?>(1, 20, null)) {
            assertEquals(emptySet<Long>(), conflictCourseIdsForWeek(emptyList(), week))
        }
    }
}
