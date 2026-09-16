package com.buaa.schedule.data.import

import com.buaa.schedule.domain.model.Semester
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抓取完整性契约。
 *
 * 按周抓取的课表只要有一周失败，结果就不能拿去覆盖落库 ——
 * `replaceSemesterCourses` 是"先清空该学期再写入"，
 * 部分结果会把没抓到的周次直接删掉（静默数据丢失）。
 */
class SemesterCoursesCompletenessTest {

    private val semester = Semester(
        termCode = "2026-2027-1",
        termName = "2026-2027-1",
        startDate = "2026-09-07",
        totalWeeks = 19,
    )

    @Test
    fun completeWhenNoWeekFailed() {
        val result = SemesterCourses(semester = semester, courses = emptyList())
        assertTrue(result.isComplete)
    }

    @Test
    fun incompleteWhenAnyWeekFailed() {
        val result = SemesterCourses(
            semester = semester,
            courses = emptyList(),
            failedWeeks = listOf(5, 6, 12),
        )
        assertFalse(result.isComplete)
    }

    @Test
    fun incompleteResultCarriesFailureDetails() {
        val result = SemesterCourses(
            semester = semester,
            courses = emptyList(),
            warnings = listOf("3 个教学周抓取失败（5、6、12），本次结果不完整"),
            failedWeeks = listOf(5, 6, 12),
        )
        // 界面据此提示用户，而不是把部分课表当成功
        assertFalse(result.isComplete)
        assertTrue(result.warnings.isNotEmpty())
        assertTrue(result.warnings.first().contains("不完整"))
    }
}
