package com.buaa.schedule.data.import

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester

/** 整学期抓取结果：学期 + 课程 + 供导入预览展示的警告 */
data class SemesterCourses(
    val semester: Semester,
    val courses: List<Course>,
    val warnings: List<String> = emptyList(),
    /**
     * 抓取失败的周次。非空表示结果**不完整**：此时绝不能走"先清空该学期再写入"
     * 的覆盖导入，否则没抓到的周次会被连带删掉（静默数据丢失）。
     */
    val failedWeeks: List<Int> = emptyList(),
) {
    /** 结果是否完整（可以安全覆盖既有课表） */
    val isComplete: Boolean get() = failedWeeks.isEmpty()
}

/**
 * 北航课表导入器。
 *
 * 北航 `type=week&week=N` 接口按周返回，因此需要遍历整个学期后合并：
 * - 同一门课、同一教师、同一地点、同一时间段的多个周次合并为一条 Course；
 * - 不同教师/地点/时间的片段保持独立，符合“理论课/实验课拆行”的数据模型。
 */
class BuaaScheduleImporter(
    private val api: BuaaApi = BuaaApi(),
) {

    /**
     * 先获取学期周信息（开学日期、总周数），再按周拉取整学期课表。
     */
    suspend fun fetchSemesterWithCourses(
        termCode: String,
        cookie: String,
        campusCode: String = "",
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Result<SemesterCourses> {
        val weeksResult = api.fetchTermWeeks(termCode, cookie)
        val weeks = weeksResult.getOrElse { return Result.failure(it) }
        val semester = BuaaScheduleParser.parseTermWeeks(weeks)
            ?: return Result.failure(IllegalStateException("无法从 getTermWeeks 响应解析学期信息"))
        val coursesResult = fetchFullSemesterOutcome(
            termCode = termCode,
            totalWeeks = semester.totalWeeks,
            cookie = cookie,
            campusCode = campusCode,
            onProgress = onProgress,
        )
        return coursesResult.fold(
            onSuccess = { (courses, warnings) ->
                Result.success(SemesterCourses(semester, courses, warnings))
            },
            onFailure = { Result.failure(it) },
        )
    }

    suspend fun fetchFullSemester(
        termCode: String,
        totalWeeks: Int,
        cookie: String,
        campusCode: String = "",
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Result<List<Course>> =
        fetchFullSemesterOutcome(termCode, totalWeeks, cookie, campusCode, onProgress)
            .map { it.first }

    private suspend fun fetchFullSemesterOutcome(
        termCode: String,
        totalWeeks: Int,
        cookie: String,
        campusCode: String,
        onProgress: (Int, Int) -> Unit,
    ): Result<Pair<List<Course>, List<String>>> {
        val allCourses = mutableListOf<Course>()
        var fallbackWeekCourses = 0
        var unknownTeacherCourses = 0
        for (week in 1..totalWeeks) {
            onProgress(week, totalWeeks)
            val weekResult = api.fetchSchedule(
                termCode = termCode,
                cookie = cookie,
                campusCode = campusCode,
                type = "week",
                week = week,
            )
            val dtoList = weekResult.getOrElse { return Result.failure(it) }
            val outcome = BuaaScheduleParser.parseArrangedListOutcome(dtoList, termCode, totalWeeks)
            allCourses += outcome.courses
            fallbackWeekCourses += outcome.fallbackWeekCourses
            unknownTeacherCourses += outcome.unknownTeacherCourses
        }
        val warnings = buildList {
            if (fallbackWeekCourses > 0) {
                add("$fallbackWeekCourses 条课程缺少教师/周次信息，已按整学期展示，请核对")
            }
            if (unknownTeacherCourses > 0 && fallbackWeekCourses == 0) {
                add("$unknownTeacherCourses 条课程缺少教师信息")
            }
        }
        return Result.success(merge(allCourses) to warnings)
    }

    private fun merge(courses: List<Course>): List<Course> {
        val merged = LinkedHashMap<String, Course>()
        for (course in courses) {
            val key = listOf(
                course.sourceGroupKey ?: "",
                course.name,
                course.teacher ?: "",
                course.location ?: "",
                course.dayOfWeek,
                course.periods.joinToString(","),
            ).joinToString("|")

            val existing = merged[key]
            if (existing == null) {
                merged[key] = course
            } else {
                merged[key] = existing.copy(
                    weeks = (existing.weeks + course.weeks).distinct().sorted(),
                )
            }
        }
        return merged.values.toList()
    }
}
