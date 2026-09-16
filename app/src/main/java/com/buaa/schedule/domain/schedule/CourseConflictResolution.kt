package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import kotlin.math.abs

/**
 * 冲突处理建议 —— 全部纯函数，不触碰数据库与 UI。
 *
 * 与 [ConflictDetector] 配套：检测给出"哪里冲突"，这里给出"怎么挪"。
 * UI 侧把建议落到库上时用 [com.buaa.schedule.domain.model.CourseSaveOptions.partialWeeks]，
 * 只改冲突周次，其余周保持原排课。
 */
object CourseConflictResolution {

    /** 一组互相冲突的课程（同一星期、同一周次范围内时段重叠） */
    data class ConflictGroup(
        val dayOfWeek: Int,
        val courses: List<Course>,
        /** 该组课程两两重叠的周次并集 */
        val weeks: List<Int>,
    )

    /** 平移建议：新节次列表 + 相对原位置的偏移（负 = 提前） */
    data class ShiftSuggestion(
        val periods: List<Int>,
        val shiftedBy: Int,
    )

    /**
     * 把冲突对归并成组：A 与 B 冲突、B 与 C 冲突 → A/B/C 同组。
     * 课程身份键用 id（手动/导入课程都有），id 为 0（未落库）时退回内容签名。
     */
    fun groupConflicts(conflicts: List<ConflictDetector.Conflict>): List<ConflictGroup> {
        val parent = HashMap<String, String>()

        fun root(key: String): String {
            var current = key
            while (parent[current] != current) current = parent.getValue(current)
            return current
        }

        fun register(key: String) {
            if (parent[key] == null) parent[key] = key
        }

        fun union(a: String, b: String) {
            val ra = root(a)
            val rb = root(b)
            if (ra != rb) parent[rb] = ra
        }

        conflicts.forEach { conflict ->
            val a = identityKey(conflict.first)
            val b = identityKey(conflict.second)
            register(a)
            register(b)
            union(a, b)
        }

        val coursesByKey = HashMap<String, MutableList<Course>>()
        val weeksByKey = HashMap<String, MutableSet<Int>>()
        val dayByKey = HashMap<String, Int>()
        conflicts.forEach { conflict ->
            listOf(conflict.first, conflict.second).forEach { course ->
                val key = root(identityKey(course))
                coursesByKey.getOrPut(key) { mutableListOf() }.let { list ->
                    if (list.none { identityKey(it) == identityKey(course) }) list += course
                }
                weeksByKey.getOrPut(key) { mutableSetOf() } += conflict.weeks
                dayByKey.putIfAbsent(key, course.dayOfWeek)
            }
        }

        return parent.keys
            .map { root(it) }
            .distinct()
            .map { key ->
                ConflictGroup(
                    dayOfWeek = dayByKey[key] ?: 1,
                    courses = (coursesByKey[key] ?: emptyList()).sortedBy { it.startPeriod },
                    weeks = (weeksByKey[key] ?: emptySet()).sorted(),
                )
            }
            .sortedWith(compareBy({ it.dayOfWeek }, { it.courses.firstOrNull()?.startPeriod ?: 0 }))
    }

    /**
     * 在同一天内为 [target] 找一个不与 [others] 冲突的最近时段。
     *
     * 搜索顺序：原地 → +1 → -1 → +2 → -2 …；**同距离时优先往后挪**（尽量保住早课）。
     * 保持节次数量不变，因此卡片高度不变；只平移。
     *
     * @param others 同一天、且与 [target] 共享周次的其它课程（调用方可直接传全天课程）
     * @param maxPeriod 节次上限
     * @return [target] 原地并无冲突、或找不到空位时返回 null
     */
    fun suggestNearestFreeShift(
        target: Course,
        others: List<Course>,
        maxPeriod: Int = CourseConstraints.MAX_PERIOD,
    ): ShiftSuggestion? {
        val span = target.periods.size
        if (span == 0) return null
        val currentStart = target.periods.min()
        val currentPeriods = target.periods.sorted()

        val blockers = others.filter { other ->
            if (other.id != 0L && other.id == target.id) return@filter false
            if (other.dayOfWeek != target.dayOfWeek) return@filter false
            other.weeks.any { it in target.weeks }
        }

        // 原地无冲突就不该给建议：调用方只在确实冲突时才需要挪动，
        // 但这里也兜底防住"误把空闲课传进来"的情况
        val currentlyBlocked = blockers.any { other -> other.periods.any { it in currentPeriods } }
        if (!currentlyBlocked) return null

        val lastStart = maxPeriod - span + 1
        if (lastStart < 1) return null
        val candidates = (1..lastStart).sortedWith(
            compareBy<Int> { abs(it - currentStart) }.thenByDescending { it }
        )

        for (start in candidates) {
            val candidate = (start until start + span).toList()
            if (candidate == currentPeriods) continue // 原地不算建议
            val blocked = blockers.any { other -> other.periods.any { it in candidate } }
            if (!blocked) return ShiftSuggestion(periods = candidate, shiftedBy = start - currentStart)
        }
        return null
    }

    /** 课程身份键：落库课程用 id，未落库课程用内容签名 */
    private fun identityKey(course: Course): String =
        if (course.id != 0L) {
            "id:${course.id}"
        } else {
            "v:${course.name}|${course.dayOfWeek}|${course.periods}|${course.weeks}"
        }
}
