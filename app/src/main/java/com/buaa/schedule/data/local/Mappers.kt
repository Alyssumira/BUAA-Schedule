package com.buaa.schedule.data.local

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.ImportHistory
import com.buaa.schedule.domain.model.ReminderSetting
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot

fun CourseEntity.toDomain() = Course(
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
)

fun Course.toEntity() = CourseEntity(
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
)

fun SemesterEntity.toDomain() = Semester(
    id = id,
    termCode = termCode,
    termName = termName,
    startDate = startDate,
    totalWeeks = totalWeeks,
)

fun Semester.toEntity() = SemesterEntity(
    id = id,
    termCode = termCode,
    termName = termName,
    startDate = startDate,
    totalWeeks = totalWeeks,
)

fun TimeSlotEntity.toDomain() = TimeSlot(
    id = id,
    number = number,
    startTime = startTime,
    endTime = endTime,
)

fun TimeSlot.toEntity() = TimeSlotEntity(
    id = id,
    number = number,
    startTime = startTime,
    endTime = endTime,
)

fun ImportHistoryEntity.toDomain() = ImportHistory(
    id = id,
    source = source,
    importedAt = importedAt,
    termCode = termCode,
    courseCount = courseCount,
    message = message,
)

fun ImportHistory.toEntity() = ImportHistoryEntity(
    id = id,
    source = source,
    importedAt = importedAt,
    termCode = termCode,
    courseCount = courseCount,
    message = message,
)

fun ReminderEntity.toDomain() = ReminderSetting(
    courseId = courseId,
    enabled = enabled,
    advanceMinutes = advanceMinutes,
)

fun ReminderSetting.toEntity() = ReminderEntity(
    courseId = courseId,
    enabled = enabled,
    advanceMinutes = advanceMinutes,
)
