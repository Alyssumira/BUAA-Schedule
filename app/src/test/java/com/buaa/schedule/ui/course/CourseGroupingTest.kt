package com.buaa.schedule.ui.course

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.schedule.SemesterStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课表管理页的归并规则测试。
 *
 * 归并错了会直接把"另一门课"当成同一门课删掉/改色，因此：
 * - **归并这把尺全仓只有一份**（T224 换源）：本页吃 [SemesterStats.creditsByCourse]，
 *   也就是统计页那枚 `courseCount` 的同一本账。本表每格都顺手核一次
 *   「管理页归并出的组数 == 内核归并出的门数」，谁再在界面上 groupBy 一遍就红；
 * - 导入课程（有 sourceGroupKey）按那枚组键归并；
 * - 手动课程（无组键）按「学期|课程名」归并 —— 与统计页同一个口径。
 *
 * ⚠️ 换源换来的代价（不是漏网，是裁定）：改前这里有一条
 * `keepsSameNameDifferentTimeApartForManualCourses`，判的是「同名不同时间的两枚手动课各占一张卡，
 * 免得删一门误删另一门」。那一格与全站那一把尺矛盾（统计页把同名手动课数成 1 门，管理页念 2 门，
 * 而「删除整门课」删完之后统计页那门课照旧在列）。今天按内核口径并成一张卡：
 * **删一张卡 = 删掉同名课的全部片段**，改色同理；撤销仍只在提示条那颗按钮上（T127 那把闸）。
 * 要改回去的字段是 `Course.sourceGroupKey`（手动课程没有它才退化成「学期|课程名」），
 * 代价见 `ui/course/CourseManagementScreen.kt` 里 `groupCourses` 那段的第 1 条。
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
        semesterCode: String? = null,
    ) = Course(
        id = id,
        name = name,
        alias = alias,
        dayOfWeek = day,
        periods = periods,
        weeks = weeks,
        sourceGroupKey = groupKey,
        teacher = teacher,
        semesterCode = semesterCode,
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
    fun mergesSameNameDifferentTimeForManualCoursesNowThatOneRulerRules() {
        // T224 换源：同名不同时间的手动课在管理页并成一张卡（统计页本来就把它们数成 1 门）。
        // 代价如实钉在这格里 —— 删这张卡会带走两枚片段，"删一门"不再等于"删一格的课"。
        val first = course(1, "自习", day = 1, periods = listOf(1, 2))
        val second = course(2, "自习", day = 2, periods = listOf(1, 2))
        val groups = groupCourses(listOf(first, second), query = "")
        assertEquals("同名手动课应当并成门课那一枚组（与 SemesterStats 同尺）", 1, groups.size)
        assertEquals("那张卡底下挂着两枚片段，删除会一起带走", 2, groups.single().fragments.size)
        assertEquals("组键必须逐字是内核那把尺交回来的（不是本页再拼一遍）", SemesterStats.courseGroupKey(first), groups.single().key)
        assertEquals("枚组键 = 学期|课程名（手动课没有 sourceGroupKey 的退化档）", "|自习", groups.single().key)
    }

    @Test
    fun keepsSameNameFromDifferentSemestersApart() {
        // 内核那把尺带学期前缀：上学期的重修课不该与本周期的同名课并成一枚
        // （改前那一族的键不含学期，会并进来 —— 与 `ui/home/WeekChangeSummaryWiringGuardTest`
        //   给周次异动摘要记的「学期前缀」那一格同口径）
        val groups = groupCourses(
            listOf(
                course(1, "高数", day = 1, semesterCode = "2025-2026-1"),
                course(2, "高数", day = 3, semesterCode = "2025-2026-2"),
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
