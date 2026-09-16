package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester

/**
 * 课表展示范围的统一口径：
 * 手动添加的课程（semesterCode == null）始终显示，
 * 其余课程只显示当前学期的，避免多学期课程叠加。
 */
object CourseFilter {

    fun visibleIn(courses: List<Course>, semester: Semester?): List<Course> =
        if (semester == null) {
            courses
        } else {
            courses.filter { it.semesterCode == null || it.semesterCode == semester.termCode }
        }
}
