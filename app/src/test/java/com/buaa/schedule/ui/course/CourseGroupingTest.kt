package com.buaa.schedule.ui.course

import com.buaa.schedule.domain.model.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课表管理页的归并规则测试。
 *
 * 归并错了会直接把"另一门课"当成同一门课删掉/改色，因此：
 * - 导入课程（有 sourceGroupKey）必须按 key 归并；
 * - 手动课程（无 key）必须用 名称+星期+节次 兜底，避免同名不同时间的课被并组。
 */
class CourseGroupingTest {

    private fun course(
        id: Long,
        name: String,
        day: Int = 1,
        periods: List<Int> = listOf(1, 2),
        weeks: List<Int> = (1..16).toList(),
        groupKey: String? = null,
        teacher: String? = null,
        alias: String? = null,
    ) = Course(
        id = id,
        name = name,
        alias = alias,
        dayOfWeek = day,
        periods = periods,
        weeks = weeks,
        sourceGroupKey = groupKey,
        teacher = teacher,
    )

    @Test
    fun mergesFragmentsSharingGroupKey() {
        val groups = groupCourses(
            listOf(
                course(1, "高数", day = 1, periods = listOf(1, 2), groupKey = "G1"),
                course(2, "高数", day = 3, periods = listOf(3, 4), groupKey = "G1"),
            ),
            query = "",
        )
        assertEquals(1, groups.size)
        assertEquals(2, groups.first().fragments.size)
        assertEquals("高数", groups.first().name)
    }

    @Test
    fun keepsSameNameDifferentTimeApartForManualCourses() {
        // 手动添加的两门同名课：周几/节次不同 → 不能并组（否则删一门会误删另一门）
        val groups = groupCourses(
            listOf(
                course(1, "自习", day = 1, periods = listOf(1, 2)),
                course(2, "自习", day = 2, periods = listOf(1, 2)),
            ),
            query = "",
        )
        assertEquals(2, groups.size)
    }

    @Test
    fun mergesSameNameSameTimeForManualCourses() {
        // 同名同时间但不同周次的两个片段（如 1-8 周与 9-16 周分开录入）→ 应归并
        val groups = groupCourses(
            listOf(
                course(1, "自习", day = 1, periods = listOf(1, 2), weeks = (1..8).toList()),
                course(2, "自习", day = 1, periods = listOf(1, 2), weeks = (9..16).toList()),
            ),
            query = "",
        )
        assertEquals(1, groups.size)
        assertEquals(2, groups.first().fragments.size)
    }

    @Test
    fun filtersByCourseNameOrTeacher() {
        val groups = groupCourses(
            listOf(
                course(1, "高等数学", teacher = "张三"),
                course(2, "大学英语", teacher = "李四"),
            ),
            query = "张三",
        )
        assertEquals(1, groups.size)
        assertEquals("高等数学", groups.first().name)
    }

    @Test
    fun queryIsCaseInsensitive() {
        val groups = groupCourses(
            listOf(course(1, "C Language")),
            query = "c lang",
        )
        assertEquals(1, groups.size)
    }

    @Test
    fun aliasOnAnyFragmentBecomesGroupName() {
        // 别名是逐片段登记的：primary（起始节次那条）没有别名时，整组仍应显示兄弟片段的别名
        val groups = groupCourses(
            listOf(
                course(1, "高等数学A", day = 1, periods = listOf(1, 2), groupKey = "G1"),
                course(2, "高等数学A", day = 3, periods = listOf(6, 7), groupKey = "G1", alias = "高数"),
            ),
            query = "",
        )
        assertEquals(1, groups.size)
        assertEquals("高数", groups.first().displayName)
        // 教务原名不能丢：它是分组真源，也是卡片上「原名 X」提示的数据
        assertEquals("高等数学A", groups.first().name)
    }

    @Test
    fun blankAliasKeepsOfficialName() {
        val groups = groupCourses(
            listOf(course(1, "高等数学A", alias = "   ")),
            query = "",
        )
        assertEquals("高等数学A", groups.first().displayName)
    }

    @Test
    fun searchesByAliasAsWellAsOfficialName() {
        val courses = listOf(
            course(1, "高等数学A", alias = "高数"),
            course(2, "大学英语"),
        )
        // 用户在其它界面只见过别名，到这里必然打别名；按教务原名搜同样要能命中
        assertEquals(listOf("高数"), groupCourses(courses, "高数").map { it.displayName })
        assertEquals(listOf("高数"), groupCourses(courses, "数学").map { it.displayName })
        assertEquals(1, groupCourses(courses, "英语").size)
    }

    @Test
    fun fragmentsAreSortedByDayThenPeriod() {
        val groups = groupCourses(
            listOf(
                course(1, "高数", day = 3, periods = listOf(1, 2), groupKey = "G1"),
                course(2, "高数", day = 1, periods = listOf(6, 7), groupKey = "G1"),
            ),
            query = "",
        )
        val fragments = groups.first().fragments
        assertEquals(1, fragments.first().dayOfWeek)
        assertEquals(3, fragments.last().dayOfWeek)
    }

    @Test
    fun emptyCourseListYieldsNoGroups() {
        assertTrue(groupCourses(emptyList(), "").isEmpty())
    }
}
