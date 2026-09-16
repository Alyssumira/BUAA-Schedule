package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportPlannerTest {

    private fun course(
        id: Long,
        name: String,
        weeks: List<Int> = (1..16).toList(),
        isManualOverride: Boolean = false,
    ) = Course(
        id = id,
        name = name,
        teacher = "张三",
        location = "J3-101",
        dayOfWeek = 1,
        periods = listOf(1, 2),
        weeks = weeks,
        isManualOverride = isManualOverride,
        sourceGroupKey = "G",
        semesterCode = "T",
    )

    @Test
    fun reusesExistingIdForSameCourseKey() {
        val existing = listOf(course(id = 5, name = "数学"))
        val imported = listOf(course(id = 0, name = "数学", weeks = (1..8).toList()))

        val plan = ImportPlanner.buildImportPlan(existing, imported)

        assertEquals(1, plan.size)
        assertEquals(5L, plan[0].id)
        assertEquals((1..8).toList(), plan[0].weeks)
    }

    @Test
    fun keepsManualOverrideCoursesUntouched() {
        val existing = listOf(course(id = 5, name = "数学", isManualOverride = true))
        val imported = listOf(course(id = 0, name = "数学"))

        val plan = ImportPlanner.buildImportPlan(existing, imported)

        // 手动修改过的课程保留，同时导入课程作为新行加入
        assertEquals(2, plan.size)
        assertTrue(plan.any { it.isManualOverride && it.id == 5L })
    }

    @Test
    fun doesNotReuseIdOfManualOverrideCourse() {
        val existing = listOf(course(id = 5, name = "数学", isManualOverride = true))
        val imported = listOf(course(id = 0, name = "数学"))

        val plan = ImportPlanner.buildImportPlan(existing, imported)

        val importedRow = plan.first { !it.isManualOverride }
        assertEquals(0L, importedRow.id)
    }

    @Test
    fun deduplicatesImportedListByCourseKey() {
        val imported = listOf(
            course(id = 0, name = "数学"),
            course(id = 0, name = "数学"),
        )

        val plan = ImportPlanner.buildImportPlan(emptyList(), imported)

        assertEquals(1, plan.size)
    }

    @Test
    fun sameCourseDifferentWeeksUnionsWeeks() {
        // ICS 导出是「一次上课一个 VEVENT」且不带 RRULE，解析回来就是同一门课的
        // 多条单周记录。按 key 去重时若丢弃后续条目，一门 16 周的课会被压成 1 周。
        val imported = listOf(
            course(id = 0, name = "数学", weeks = listOf(1)),
            course(id = 0, name = "数学", weeks = listOf(8)),
        )

        val plan = ImportPlanner.buildImportPlan(emptyList(), imported)

        assertEquals(1, plan.size)
        assertEquals(listOf(1, 8), plan[0].weeks)
    }

    @Test
    fun unionSortsAndDeduplicatesWeeks() {
        val imported = listOf(
            course(id = 0, name = "数学", weeks = listOf(3, 1)),
            course(id = 0, name = "数学", weeks = listOf(2, 1)),
        )

        val plan = ImportPlanner.buildImportPlan(emptyList(), imported)

        assertEquals(listOf(1, 2, 3), plan[0].weeks)
    }

    @Test
    fun sameSlotDifferentNamesAreDistinctCourses() {
        // 文本/ICS 导入没有 sourceGroupKey：教师、地点、时间全相同但名称不同的
        // 两门课不能被合并成一条
        val imported = listOf(
            course(id = 0, name = "数学"),
            course(id = 0, name = "物理"),
        )

        val plan = ImportPlanner.buildImportPlan(emptyList(), imported)

        assertEquals(2, plan.size)
    }
}

class CourseFilterTest {

    private fun course(name: String, semesterCode: String?) = Course(
        name = name,
        dayOfWeek = 1,
        periods = listOf(1),
        weeks = listOf(1),
        semesterCode = semesterCode,
    )

    private val semester = Semester(
        termCode = "2026-2027-1",
        termName = "2026-2027-1",
        startDate = "2026-09-07",
        totalWeeks = 20,
    )

    @Test
    fun showsManualAndCurrentSemesterCoursesOnly() {
        val courses = listOf(
            course("手动", semesterCode = null),
            course("本学期", semesterCode = "2026-2027-1"),
            course("其他学期", semesterCode = "2025-2026-1"),
        )

        val visible = CourseFilter.visibleIn(courses, semester)

        assertEquals(listOf("手动", "本学期"), visible.map { it.name })
    }

    @Test
    fun showsAllCoursesWhenNoSemester() {
        val courses = listOf(
            course("手动", semesterCode = null),
            course("历史", semesterCode = "2025-2026-1"),
        )

        val visible = CourseFilter.visibleIn(courses, null)

        assertEquals(2, visible.size)
    }
}
