package com.buaa.schedule.ui.stats

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.schedule.ConflictDetector
import com.buaa.schedule.domain.schedule.CourseConflictResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 统计页「课程冲突」那一块的措辞判据（T82）。
 *
 * 这一块的立身之本是**一个判据都不新写**：哪些课算一组、撞在哪几周，
 * 全部来自 [CourseConflictResolution.groupConflicts]；这里只把它的结果说成话。
 * 所以本文件里最要紧的一档是"三门课两两互撞 = 一组，不是三组" ——
 * 那句「N 组」在两处（这一页与首页那条横幅）念出来的必须是同一个数。
 */
class StatsConflictCopyTest {

    private val slots = listOf(
        TimeSlot(number = 1, startTime = "08:00", endTime = "08:45"),
        TimeSlot(number = 2, startTime = "08:55", endTime = "09:40"),
        TimeSlot(number = 3, startTime = "10:00", endTime = "10:45"),
        TimeSlot(number = 4, startTime = "10:55", endTime = "11:40"),
        // 第 5、6 节之间隔着整个午饭：连着写 [5,6] 也不该被折成一堂 3 小时的课
        TimeSlot(number = 5, startTime = "11:45", endTime = "12:30"),
        // 第 5、6 节之间隔着整个午饭：节次号相邻也不算一段
        TimeSlot(number = 6, startTime = "14:00", endTime = "14:45"),
    )

    private fun course(name: String, day: Int, periods: List<Int>, weeks: List<Int>, id: Long) = Course(
        id = id,
        name = name,
        dayOfWeek = day,
        periods = periods,
        weeks = weeks,
        semesterCode = "2026-2027-1",
    )

    private fun group(day: Int, periods: List<Int>, courses: List<Course>, weeks: List<Int> = (1..8).toList()) =
        CourseConflictResolution.ConflictGroup(dayOfWeek = day, courses = courses, weeks = weeks)

    // ---- ① 「几组」那一枚数字的口径 ----

    @Test
    fun headlineSpeaksGroupCountAndAlwaysSaysSomething() {
        val cases = listOf(
            0 to "这学期没有撞课的时段",
            1 to "有 1 组时间冲突",
            4 to "有 4 组时间冲突",
            // 内核交不出负数，但措辞也不许在这里冒出一句"有 -1 组"
            -2 to "这学期没有撞课的时段",
        )
        cases.forEach { (count, expected) -> assertEquals(expected, conflictHeadlineNote(count)) }
    }

    /**
     * 三门课两两互撞：`findConflicts` 交回 3 条配对，`groupConflicts` 归并成 1 组。
     *
     * 这一档钉的是"统计页那句组数走的是归并后的组"，与首页点开向导看到的行数一致。
     * 配对数当组数用是改前首页那句话的写法，三门课会念成「3 组」而向导里只有一档。
     */
    @Test
    fun threeCoursesCollidingPairwiseAreOneGroup() {
        val a = course("高等数学", day = 1, periods = listOf(3, 4), weeks = (1..8).toList(), id = 1L)
        val b = course("大学物理", day = 1, periods = listOf(3, 4), weeks = (1..8).toList(), id = 2L)
        val c = course("线性代数", day = 1, periods = listOf(3, 4), weeks = (1..8).toList(), id = 3L)
        val conflicts = ConflictDetector.findConflicts(listOf(a, b, c))
        assertEquals("两两配对是 3 条", 3, conflicts.size)
        val groups = CourseConflictResolution.groupConflicts(conflicts)
        assertEquals("归并成组是 1 组", 1, groups.size)
        assertEquals("有 1 组时间冲突", conflictHeadlineNote(groups.size))
        assertEquals("三门课一组也要一起数清楚", 3, groups.single().courses.size)
    }

    @Test
    fun differentDaysStayDifferentGroups() {
        val mon = course("高等数学", day = 1, periods = listOf(3, 4), weeks = (1..8).toList(), id = 1L)
        val clashMon = course("大学物理", day = 1, periods = listOf(3, 4), weeks = (1..8).toList(), id = 2L)
        val wed = course("体育", day = 3, periods = listOf(3, 4), weeks = (1..8).toList(), id = 3L)
        val clashWed = course("毛概", day = 3, periods = listOf(3, 4), weeks = (1..8).toList(), id = 4L)
        val groups = CourseConflictResolution.groupConflicts(
            ConflictDetector.findConflicts(listOf(mon, clashMon, wed, clashWed)),
        )
        assertEquals(2, groups.size)
        assertEquals("有 2 组时间冲突", conflictHeadlineNote(groups.size))
    }

    /** ConflictDetector 早就排除掉的形态：这里不许把它们重新算成冲突 */
    @Test
    fun detectorAlreadyRejectedShapesStayRejected() {
        val cases = listOf<List<Course>>(
            // 同 id 自己撞自己
            listOf(course("同一门课", 1, listOf(3), (1..8).toList(), 9L), course("同一门课", 1, listOf(3), (1..8).toList(), 9L)),
            // 周次不相交
            listOf(course("小学期", 1, listOf(3), (1..8).toList(), 1L), course("小学期B", 1, listOf(3), (9..16).toList(), 2L)),
            // 不同天
            listOf(course("周一", 1, listOf(3), (1..8).toList(), 1L), course("周二", 2, listOf(3), (1..8).toList(), 2L)),
            // 节次不重叠
            listOf(course("第一节", 1, listOf(1), (1..8).toList(), 1L), course("第三节", 1, listOf(3), (1..8).toList(), 2L)),
        )
        cases.forEach { courses ->
            val groups = CourseConflictResolution.groupConflicts(ConflictDetector.findConflicts(courses))
            assertEquals(0, groups.size)
            assertEquals("没有冲突时那一块也要开口", "这学期没有撞课的时段", conflictHeadlineNote(groups.size))
        }
    }

    // ---- ② 一组的标题：星期 + 周次折段 ----

    @Test
    fun groupTitleFoldsWeeksWithTheSameKernelTheGanttUses() {
        val a = course("高等数学", day = 3, periods = listOf(3, 4), weeks = (1..8).toList(), id = 1L)
        val b = course("大学物理", day = 3, periods = listOf(3, 4), weeks = (1..8).toList(), id = 2L)
        val cases = listOf<Triple<CourseConflictResolution.ConflictGroup, String, String>>(
            Triple(group(3, listOf(3, 4), listOf(a, b)), "周三 · 第 1-8 周", "连续周次折成一段"),
            Triple(
                group(3, listOf(3, 4), listOf(a, b), weeks = listOf(1, 2, 3, 5)),
                "周三 · 第 1-3、5 周",
                "断开的周次不许并进去（那是谎报）",
            ),
            Triple(group(3, listOf(3, 4), listOf(a, b), weeks = listOf(7)), "周三 · 第 7 周", "单周不写区间"),
            Triple(group(3, listOf(3, 4), listOf(a, b), weeks = emptyList()), "周三", "周次为空：只说天，不编一个范围"),
            Triple(group(0, listOf(3, 4), listOf(a, b)), "周? · 第 1-8 周", "脏星期序号：不许顶成周一"),
            Triple(group(8, listOf(3, 4), listOf(a, b)), "周? · 第 1-8 周", "同上，越界另一侧"),
        )
        cases.forEach { (item, expected, why) -> assertEquals(why, expected, conflictGroupTitle(item)) }
    }

    // ---- ③ 卡片摆几组、每组摆几门 ----

    @Test
    fun inlineCoursesCapAtThreeAndSayHowManyAreHidden() {
        val four = (1..4).map { course("课$it", day = 1, periods = listOf(3), weeks = (1..8).toList(), id = it.toLong()) }
        val big = group(1, listOf(3), four)
        assertEquals(listOf("课1", "课2", "课3"), inlineConflictCourses(big).map { it.name })
        assertEquals("另有 1 门课也在这一组里", hiddenCourseNote(big))

        val three = group(1, listOf(3), four.take(3))
        assertEquals(3, inlineConflictCourses(three).size)
        assertNull("三门全都画出来了，不该再说'另有'", hiddenCourseNote(three))
        assertNull(hiddenCourseNote(group(1, listOf(3), emptyList())))
    }

    @Test
    fun hiddenGroupNotePointsAtTheWizard() {
        val cases = listOf(
            0 to null,
            1 to "还有 1 组没有列出，点「按建议处理」一次看全",
            MAX_INLINE_CONFLICT_GROUPS to "还有 $MAX_INLINE_CONFLICT_GROUPS 组没有列出，点「按建议处理」一次看全",
        )
        cases.forEach { (hidden, expected) -> assertEquals(expected, hiddenGroupNote(hidden)) }
    }

    // ---- ④ 组内节次那句：只在真的完全一样时才说 ----

    @Test
    fun sharedPeriodNoteOnlyWhenEveryFragmentSitsInTheSameSlots() {
        val cases = listOf<Triple<CourseConflictResolution.ConflictGroup, String?, String>>(
            Triple(
                group(
                    1,
                    listOf(3, 4),
                    listOf(
                        course("A", 1, listOf(3, 4), (1..8).toList(), 1L),
                        course("B", 1, listOf(3, 4), (1..8).toList(), 2L),
                    ),
                ),
                "第3-4节",
                "两门课排在同样的两节：可以说这一句",
            ),
            Triple(
                group(
                    1,
                    listOf(3, 4),
                    listOf(
                        course("A", 1, listOf(1, 2), (1..8).toList(), 1L),
                        course("B", 1, listOf(3, 4), (1..8).toList(), 2L),
                    ),
                ),
                null,
                "节次不同：这里没有'把两门课的节次折成一个区间'的判据，不编",
            ),
            Triple(
                group(1, emptyList(), listOf(course("A", 1, emptyList(), (1..8).toList(), 1L))),
                null,
                "空节次：不许写「第节」",
            ),
            Triple(
                group(1, listOf(5, 6), listOf(course("A", 1, listOf(5, 6), (1..8).toList(), 1L))),
                "第5,6节",
                "相邻节次号隔着午饭 → 两段（措辞由 periodLabelOf 那份决定，这里照抄）",
            ),
        )
        cases.forEach { (item, expected, why) -> assertEquals(why, expected, sharedPeriodNote(item, slots)) }
    }
}
