package com.buaa.schedule.reminder

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 明日课程预告的推送集合（R5 F-14）。
 *
 * 22:00 那条预告是**每天**排的，"有没有课"完全由这份选择结果决定：
 * 返回空列表就等于不推送，返回非空就等于推一条。周末、假期、单双周错开
 * 这三种"明天其实没课"的情形只要漏一种，用户就会每晚收到一条"明天没课"。
 */
class TomorrowPreviewCoursesTest {

    private val semester = Semester(
        termCode = "2026-2027-1",
        termName = "2026-2027 秋季学期",
        startDate = "2026-09-07", // 周一，第 1 教学周
        totalWeeks = 19,
    )

    /** 周一第 1、2 节的课 */
    private val mondayCourse = Course(
        name = "高等数学",
        teacher = "王五",
        location = "J3-301",
        dayOfWeek = 1,
        periods = listOf(1, 2),
        weeks = listOf(1, 3, 5),
    )

    private fun nextMonday(): LocalDate = LocalDate.of(2026, 9, 7).nextOrSame(DayOfWeek.MONDAY)

    private fun LocalDate.nextOrSame(day: DayOfWeek): LocalDate {
        var date = this
        while (date.dayOfWeek != day) date = date.plusDays(1)
        return date
    }

    @Test
    fun selectsCoursesOnTomorrowWeekdayAndWeek() {
        val tomorrow = LocalDate.of(2026, 9, 7) // 周一，第 1 周

        assertEquals(
            listOf(mondayCourse.name),
            TomorrowPreviewReceiver.tomorrowPreviewCourses(
                courses = listOf(mondayCourse),
                semester = semester,
                week = 1,
                tomorrow = tomorrow,
            ).map { it.name },
        )
    }

    @Test
    fun nothingToPushWithoutSemester() {
        // 还没配学期：预告必须静默
        assertTrue(
            TomorrowPreviewReceiver.tomorrowPreviewCourses(
                courses = listOf(mondayCourse),
                semester = null,
                week = 1,
                tomorrow = LocalDate.of(2026, 9, 7),
            ).isEmpty(),
        )
    }

    @Test
    fun nothingToPushDuringHolidays() {
        // 假期里 week 为 null；此时"明天是周几"不再有意义
        assertTrue(
            TomorrowPreviewReceiver.tomorrowPreviewCourses(
                courses = listOf(mondayCourse),
                semester = semester,
                week = null,
                tomorrow = LocalDate.of(2026, 9, 7),
            ).isEmpty(),
        )
    }

    @Test
    fun nothingToPushWhenTomorrowIsAnUnscheduledDay() {
        assertEquals(
            emptyList<Course>(),
            TomorrowPreviewReceiver.tomorrowPreviewCourses(
                courses = listOf(mondayCourse),
                semester = semester,
                week = 1,
                tomorrow = LocalDate.of(2026, 9, 6), // 周日
            ),
        )
    }

    @Test
    fun oddEvenWeeksAreNotCrossMatched() {
        // 只有单周上的课，在双周的那天必须不推
        assertTrue(
            TomorrowPreviewReceiver.tomorrowPreviewCourses(
                courses = listOf(mondayCourse),
                semester = semester,
                week = 2,
                tomorrow = nextMonday(),
            ).isEmpty(),
        )
    }
}
