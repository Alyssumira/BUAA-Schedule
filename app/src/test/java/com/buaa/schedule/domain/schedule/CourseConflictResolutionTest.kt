package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 冲突处理建议的纯函数测试。
 *
 * 这些建议会直接改用户的课表（只改冲突周），算错一步就是把课挪到不该挪的地方，
 * 因此搜索顺序、避让规则与身份归组都要钉死。
 */
class CourseConflictResolutionTest {

    private fun course(
        id: Long = 0L,
        name: String = "课",
        day: Int = 1,
        periods: List<Int> = listOf(1, 2),
        weeks: List<Int> = (1..16).toList(),
    ) = Course(
        id = id,
        name = name,
        dayOfWeek = day,
        periods = periods,
        weeks = weeks,
    )

    private fun conflict(a: Course, b: Course, weeks: List<Int>) =
        ConflictDetector.Conflict(first = a, second = b, weeks = weeks)

    // ---- suggestNearestFreeShift ----

    @Test
    fun shiftsToNextFreeSlotWhenBlocked() {
        // 目标 1-2 与 2-3 冲突（第 2 节重叠）；候选 2-3、3-4 都被占，最近空位是 4-5
        val target = course(id = 1, periods = listOf(1, 2))
        val blockers = listOf(course(id = 2, periods = listOf(2, 3)))
        val suggestion = CourseConflictResolution.suggestNearestFreeShift(target, blockers)

        assertEquals(listOf(4, 5), suggestion!!.periods)
        assertEquals(3, suggestion.shiftedBy)
    }

    @Test
    fun takesNearestFreeWindowOnEitherSide() {
        // 目标 3-4 与 2-3 冲突；±1 都被占，最近空位是 4-5（往后挪一节）
        val target = course(id = 1, periods = listOf(3, 4))
        val blockers = listOf(
            course(id = 2, periods = listOf(2, 3)),
            course(id = 3, periods = listOf(1, 2)),
        )
        val suggestion = CourseConflictResolution.suggestNearestFreeShift(target, blockers)
        assertEquals(listOf(4, 5), suggestion!!.periods)
        assertEquals(1, suggestion.shiftedBy)
    }

    @Test
    fun prefersLaterSlotOnEqualDistance() {
        // 目标 3-4，±1 都被单节课程占死，±2 都空闲：
        // 同距离时选更靠后的一侧（尽量保住早课）
        val target = course(id = 1, periods = listOf(3, 4))
        val blockers = listOf(
            course(id = 2, periods = listOf(3)),
            course(id = 3, periods = listOf(4)),
        )
        val suggestion = CourseConflictResolution.suggestNearestFreeShift(target, blockers)
        assertEquals(listOf(5, 6), suggestion!!.periods)
    }

    @Test
    fun returnsNullWhenTargetIsNotBlocked() {
        // 不同天、或周次不相交：原地并无冲突，不应给出任何挪动建议
        val target = course(id = 1, day = 1, periods = listOf(1, 2))
        val blockers = listOf(
            course(id = 2, day = 2, periods = listOf(1, 2)),
            course(id = 3, day = 1, weeks = listOf(20, 21), periods = listOf(1, 2)),
        )
        assertNull(CourseConflictResolution.suggestNearestFreeShift(target, blockers))
    }

    @Test
    fun returnsNullWhenOnlyBlockerIsSelf() {
        val target = course(id = 1, periods = listOf(1, 2))
        assertNull(CourseConflictResolution.suggestNearestFreeShift(target, listOf(target)))
    }

    @Test
    fun keepsPeriodCountUnchanged() {
        // 保持节次数量 → 卡片高度不变；即便被迫挪到很后面也要保持形状
        val target = course(id = 1, periods = listOf(2, 3))
        val blockers = listOf(
            course(id = 2, periods = listOf(1, 2)),
            course(id = 3, periods = listOf(3, 4)),
            course(id = 4, periods = listOf(5, 6)),
            course(id = 5, periods = listOf(7, 8)),
        )
        val suggestion = CourseConflictResolution.suggestNearestFreeShift(target, blockers)
        assertEquals(target.periods.size, suggestion!!.periods.size)
        assertTrue(suggestion.periods.none { p -> blockers.any { p in it.periods } })
    }

    @Test
    fun returnsNullWhenNowhereToGo() {
        // 一天内被塞满：1-2 与 2-3 冲突，3-4、5-6 也被占，节次上限 6 → 无解
        val target = course(id = 1, periods = listOf(1, 2))
        val blockers = listOf(
            course(id = 2, periods = listOf(2, 3)),
            course(id = 3, periods = listOf(3, 4)),
            course(id = 4, periods = listOf(5, 6)),
        )
        val suggestion = CourseConflictResolution.suggestNearestFreeShift(
            target, blockers, maxPeriod = 6,
        )
        assertNull(suggestion)
    }

    // ---- groupConflicts ----

    @Test
    fun mergesChainedConflictsIntoOneGroup() {
        val a = course(id = 1, name = "A", periods = listOf(1, 2))
        val b = course(id = 2, name = "B", periods = listOf(2, 3))
        val c = course(id = 3, name = "C", periods = listOf(3, 4))
        val groups = CourseConflictResolution.groupConflicts(
            listOf(conflict(a, b, listOf(5)), conflict(b, c, listOf(6))),
        )
        assertEquals(1, groups.size)
        assertEquals(3, groups.first().courses.size)
        assertEquals(listOf(5, 6), groups.first().weeks)
        assertEquals(1, groups.first().dayOfWeek)
    }

    @Test
    fun separatesGroupsByDay() {
        val a = course(id = 1, day = 1, periods = listOf(1, 2))
        val b = course(id = 2, day = 2, periods = listOf(1, 2))
        // day 不同时不会产生冲突对，这里直接构造一个跨天对来验证归组不串
        val groups = CourseConflictResolution.groupConflicts(
            listOf(conflict(a, course(id = 3, day = 1, periods = listOf(1, 2)), listOf(1))),
        )
        assertEquals(1, groups.size)
        assertEquals(1, groups.first().dayOfWeek)
        assertTrue(groups.first().courses.all { it.dayOfWeek == 1 })
        // b 与 a 不同天，不应被并进同一组
        assertTrue(groups.first().courses.none { it.id == 2L })
    }

    @Test
    fun emptyInputYieldsNoGroups() {
        assertTrue(CourseConflictResolution.groupConflicts(emptyList()).isEmpty())
    }

    // ---- 在册 #394：未落库那枚档的内容签名要吃「同一门课」那份字段集 ----

    @Test
    fun keepsTwoUnsavedCoursesWithDifferentTeachersAsTwoCourses() {
        // 两门未落库课除教师外逐字段相同（理论班＋实验班是最常见的那种），真码会交回一对。
        // 组里必须两门都在：改前那枚内容签名不吃教师 ⇒ 第二门被从组里丢掉，
        // 而它的周次仍并进门课 ⇒ 检测两门、展示一门，那句「只改这些周」却按两门的账写库。
        val lecture = course(name = "物理实验").copy(teacher = "甲老师")
        val lab = course(name = "物理实验").copy(teacher = "乙老师")
        val groups = CourseConflictResolution.groupConflicts(
            listOf(conflict(lecture, lab, (1..16).toList())),
        )
        assertEquals(1, groups.size)
        assertEquals("教师不同的两门未落库课都得在组里", 2, groups.single().courses.size)
        assertEquals(
            "组里逐门数教师（只数组不数人，第二门被丢掉时枚数恰好看不出来）",
            setOf("甲老师", "乙老师"),
            groups.single().courses.map { it.teacher }.toSet(),
        )
    }

    @Test
    fun keepsTwoUnsavedCoursesWithDifferentLocationOrSemesterAsTwoCourses() {
        // 同一形的两枚账：换教室一枚、换学期码一枚，各自都该在组里留下两门（改前各只剩一门）。
        val mainBuilding = course(name = "高等数学").copy(teacher = "丙老师", location = "主楼")
        val secondHall = course(name = "高等数学").copy(teacher = "丙老师", location = "二教")
        val byLocation = CourseConflictResolution.groupConflicts(
            listOf(conflict(mainBuilding, secondHall, (1..16).toList())),
        )
        assertEquals(1, byLocation.size)
        assertEquals("教室不同的两门未落库课都得在组里", 2, byLocation.single().courses.size)

        // 学期码那一档吃的是键的字段集本身：两个学期的课在真码里不会互撞，
        // 所以这里直接把配对喂给归并，不过检测器。
        val lastTerm = course(name = "线性代数").copy(semesterCode = "2025-2026-2")
        val thisTerm = course(name = "线性代数").copy(semesterCode = "2026-2027-1")
        val bySemester = CourseConflictResolution.groupConflicts(
            listOf(conflict(lastTerm, thisTerm, (1..16).toList())),
        )
        assertEquals(1, bySemester.size)
        assertEquals("学期码不同的两门未落库课都得在组里", 2, bySemester.single().courses.size)
    }

    @Test
    fun keepsTheTwoWeekSpansOfOneUnsavedCourseAsTwoEntriesInOneGroup() {
        // 同一门未落库课的两个片段：除周次外逐字段相同，两片段各自与满学期的第三门课撞。
        // 这里手写的两对就是检测器交回的形状（每对的周次 = 那个片段与它重叠的周次）。
        // 组仍是一枚（传递闭包），但两个片段必须各占一条处置对象、组的周次是两段的并集：
        // 把周次从键里摘掉，第二个片段就会人间蒸发，那句「只改这些周」随之把两段并成一刀。
        val firstSpan = course(name = "机械设计", weeks = (1..8).toList())
            .copy(teacher = "丁老师", location = "工三", sourceGroupKey = "G1")
        val secondSpan = course(name = "机械设计", weeks = (9..16).toList())
            .copy(teacher = "丁老师", location = "工三", sourceGroupKey = "G1")
        val allTerm = course(name = "机械制图", weeks = (1..16).toList())
        val groups = CourseConflictResolution.groupConflicts(
            listOf(
                conflict(firstSpan, allTerm, (1..8).toList()),
                conflict(secondSpan, allTerm, (9..16).toList()),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals("两个片段各是一条处置对象，加上满学期那门共三条", 3, groups.single().courses.size)
        assertEquals("组的周次是两段周次的并集", (1..16).toList(), groups.single().weeks)
    }
}
