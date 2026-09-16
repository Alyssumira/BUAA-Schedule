package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course

object ConflictDetector {

    /**
     * @Immutable：字段都是 val 且 List 构造后不再变（由 findConflicts 整体重建）。
     * 与 [com.buaa.schedule.domain.model.Course] 一起让主页的 state 成为稳定类型。
     */
    @androidx.compose.runtime.Immutable
    data class Conflict(
        val first: Course,
        val second: Course,
        val weeks: List<Int>,
    )

    /**
     * 检测课程列表中同一时间、同一周次的冲突。
     * 忽略 id 相同（自己与自己）的课程。
     */
    fun findConflicts(courses: List<Course>): List<Conflict> {
        val result = mutableListOf<Conflict>()
        val sorted = courses.sortedWith(
            compareBy<Course> { it.dayOfWeek }
                .thenBy { it.startPeriod }
                .thenBy { it.name }
        )
        for (i in sorted.indices) {
            for (j in i + 1 until sorted.size) {
                val a = sorted[i]
                val b = sorted[j]
                if (a.id == b.id && a.id != 0L) continue
                if (a.dayOfWeek != b.dayOfWeek) continue
                if (!periodsOverlap(a.periods, b.periods)) continue
                val overlapWeeks = a.weeks.intersect(b.weeks.toSet()).sorted()
                if (overlapWeeks.isNotEmpty()) {
                    result.add(Conflict(first = a, second = b, weeks = overlapWeeks))
                }
            }
        }
        return result
    }

    private fun periodsOverlap(a: List<Int>, b: List<Int>): Boolean =
        a.any { it in b }
}