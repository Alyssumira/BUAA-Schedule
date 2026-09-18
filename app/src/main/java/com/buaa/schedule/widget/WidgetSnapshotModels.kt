package com.buaa.schedule.widget

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import kotlinx.serialization.Serializable

/** Widget 快照的持久化 DTO */
@Serializable
data class WidgetSnapshotDto(
    val semester: SemesterDto? = null,
    val courses: List<CourseDto> = emptyList(),
    val timeSlots: List<TimeSlotDto> = emptyList(),
)

@Serializable
data class SemesterDto(
    val termCode: String,
    val termName: String,
    val startDate: String,
    val totalWeeks: Int,
)

@Serializable
data class CourseDto(
    val id: Long = 0L,
    val name: String,
    val alias: String? = null,
    val teacher: String? = null,
    val location: String? = null,
    val campus: String? = null,
    val dayOfWeek: Int,
    val periods: List<Int>,
    val weeks: List<Int>,
    val colorIndex: Int = 0,
    val customColorArgb: Long? = null,
    val remark: String? = null,
    val sourceGroupKey: String? = null,
    val semesterCode: String? = null,
    val isManualOverride: Boolean = false,
    /**
     * 学分。这个 DTO 是 [Course] 的逐字段镜像，少一列就等于快照与主库不是同一门课
     * （读快照的一侧永远看不到学分）。可空 + 有默认值：升级前写下的快照没有这个键，
     * 解出来是 null，下一次 `WidgetDataSynchronizer` 覆盖快照时就补上了。
     */
    val credit: Double? = null,
)

@Serializable
data class TimeSlotDto(
    val number: Int,
    val startTime: String,
    val endTime: String,
)

fun WidgetSnapshotDto.toWidgetData(): WidgetData = WidgetData(
    semester = semester?.toDomain(),
    courses = courses.map { it.toDomain() },
    timeSlots = timeSlots.map { it.toDomain() },
)

fun WidgetData.toSnapshotDto(): WidgetSnapshotDto = WidgetSnapshotDto(
    semester = semester?.toDto(),
    courses = courses.map { it.toDto() },
    timeSlots = timeSlots.map { it.toDto() },
)

private fun Semester.toDto() = SemesterDto(termCode, termName, startDate, totalWeeks)
private fun SemesterDto.toDomain() = Semester(termCode = termCode, termName = termName, startDate = startDate, totalWeeks = totalWeeks)

private fun Course.toDto() = CourseDto(
    id = id,
    name = name,
    alias = alias,
    teacher = teacher,
    location = location,
    campus = campus,
    dayOfWeek = dayOfWeek,
    periods = periods,
    weeks = weeks,
    colorIndex = colorIndex,
    customColorArgb = customColorArgb,
    remark = remark,
    sourceGroupKey = sourceGroupKey,
    semesterCode = semesterCode,
    isManualOverride = isManualOverride,
    credit = credit,
)

private fun CourseDto.toDomain() = Course(
    id = id,
    name = name,
    alias = alias,
    teacher = teacher,
    location = location,
    campus = campus,
    dayOfWeek = dayOfWeek,
    periods = periods,
    weeks = weeks,
    colorIndex = colorIndex,
    customColorArgb = customColorArgb,
    remark = remark,
    sourceGroupKey = sourceGroupKey,
    semesterCode = semesterCode,
    isManualOverride = isManualOverride,
    credit = credit,
)

private fun TimeSlot.toDto() = TimeSlotDto(number, startTime, endTime)
private fun TimeSlotDto.toDomain() = TimeSlot(number = number, startTime = startTime, endTime = endTime)