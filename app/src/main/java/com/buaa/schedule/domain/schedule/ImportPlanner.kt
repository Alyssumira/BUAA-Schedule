package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course

/**
 * 导入合并计划。
 *
 * 重复导入同一学期时，按 courseKey 匹配旧课程并复用其 id，
 * 保证按 courseId 关联的提醒设置在重新导入后不丢失；
 * 用户手动修改过的课程（isManualOverride）原样保留，不被覆盖。
 */
object ImportPlanner {

    /**
     * 课程身份键：学期 + 组键 + 名称 + 教师 + 地点 + 时间。
     * 名称必须参与——文本/ICS 导入没有 sourceGroupKey，两门名称不同但
     * 教师/地点/时间相同的课不能被合并成一条。
     */
    fun courseKey(course: Course): String = listOf(
        course.semesterCode ?: "",
        course.sourceGroupKey ?: "",
        course.name.trim(),
        course.teacher ?: "",
        course.location ?: "",
        course.dayOfWeek,
        course.periods.joinToString(","),
    ).joinToString("|")

    /**
     * 返回应写入数据库的最终课程列表：
     * - 保留全部 isManualOverride 课程（原 id 不变）；
     * - 导入课程中与旧课程 key 相同的复用旧 id；
     * - 导入列表内部按 key 合并，**周次取并集**、**学分取已知值**。
     *
     * 同 key 的多条目是同一门课的多次上课片段（ICS 每次上课一个 VEVENT、
     * 无 RRULE 时尤甚），按 key 直接丢弃后续条目会把整门课压成一帧。
     */
    fun buildImportPlan(existing: List<Course>, imported: List<Course>): List<Course> {
        val matchable = existing.filter { !it.isManualOverride }.associateBy { courseKey(it) }
        val merged = LinkedHashMap<String, Course>()
        for (course in imported) {
            val key = courseKey(course)
            val prev = merged[key]
            if (prev == null) {
                merged[key] = matchable[key]?.let { old ->
                    // 落库前先整学期删行（见 ScheduleRepository.replaceSemesterCoursesInTx），
                    // 所以这里不带过去的话，用一个**没有学分**的来源（ICS/文本）重导一遍
                    // 同一门课，就会把教务采到的学分抹掉。null 是「不知道」，不该覆盖「知道」。
                    // 导入侧有值时以它为准：那是教务改了学分，正是要更新的方向。
                    course.copy(id = old.id, credit = course.credit ?: old.credit)
                } ?: course
            } else {
                merged[key] = prev.copy(
                    weeks = (prev.weeks + course.weeks).distinct().sorted(),
                    // 同上：同批片段里只有部分带学分时（按周抓取会逐轮送到这里），
                    // 已知的那个数不能被空值顶掉
                    credit = course.credit ?: prev.credit,
                )
            }
        }
        return existing.filter { it.isManualOverride } + merged.values.toList()
    }
}
