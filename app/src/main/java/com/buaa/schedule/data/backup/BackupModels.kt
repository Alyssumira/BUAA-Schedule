package com.buaa.schedule.data.backup

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.schedule.CourseConstraints
import kotlinx.serialization.Serializable

/**
 * 备份文件的格式版本：写侧的默认值与读侧的「高于此版本即拒绝恢复」共用这一处（P2-3）。
 *
 * 此前两边各写了一份字面量 `2`，升版本时漏改一边就会产出自己读不回的备份。
 * 新增可选字段**不要**动这个数——见 [BackupCourse.credit] 那段：涨了版本号只换来一条更窄的升级路。
 */
const val BACKUP_FORMAT_VERSION = 2

@Serializable
data class BackupData(
    val version: Int = BACKUP_FORMAT_VERSION,
    val semester: BackupSemester? = null,
    val timeSlots: List<BackupTimeSlot> = emptyList(),
    val courses: List<BackupCourse> = emptyList(),
    val reminders: List<BackupReminder> = emptyList(),
)

@Serializable
data class BackupSemester(
    val termCode: String,
    val termName: String,
    val startDate: String,
    val totalWeeks: Int,
)

@Serializable
data class BackupTimeSlot(
    val number: Int,
    val startTime: String,
    val endTime: String,
)

@Serializable
data class BackupCourse(
    val name: String,
    val alias: String? = null,
    val teacher: String? = null,
    val location: String? = null,
    val campus: String? = null,
    val dayOfWeek: Int,
    val periods: List<Int> = emptyList(),
    val weeks: List<Int>,
    val colorIndex: Int = 0,
    val customColorArgb: Long? = null,
    val remark: String? = null,
    val sourceGroupKey: String? = null,
    val semesterCode: String? = null,
    val isManualOverride: Boolean = false,
    /**
     * 学分。带默认值的可选字段 = 线上兼容：
     * - 老备份（v1/v2）没有这个键 → 解析成 null，恢复照旧，不报「版本过高」；
     * - `BackupData.version` 因此**不 +1**：它涨到 3 会让老应用
     *   （`data.version > [BACKUP_FORMAT_VERSION]` 直接拒绝恢复）打不开新备份，
     *   而字段本身两边都认得，涨版本只换来一条更窄的升级路。
     */
    val credit: Double? = null,
    /** v1 备份的旧字段（连续节次），仅在 periods 为空时用于兼容恢复 */
    val startSection: Int? = null,
    /** v1 备份的旧字段 */
    val endSection: Int? = null,
)

/**
 * 提醒设置按 courseKey（课程内容签名）而不是数据库 id 备份，
 * 这样恢复后即使课程 id 重新生成，提醒也能对回正确的课程。
 */
@Serializable
data class BackupReminder(
    val courseKey: String,
    val enabled: Boolean = true,
    val advanceMinutes: Int = 10,
)

fun Semester.toBackup() = BackupSemester(
    termCode = termCode,
    termName = termName,
    startDate = startDate,
    totalWeeks = totalWeeks,
)

fun BackupSemester.toDomain() = Semester(
    termCode = termCode,
    termName = termName,
    startDate = startDate,
    totalWeeks = totalWeeks,
)

fun TimeSlot.toBackup() = BackupTimeSlot(
    number = number,
    startTime = startTime,
    endTime = endTime,
)

fun BackupTimeSlot.toDomain() = TimeSlot(
    number = number,
    startTime = startTime,
    endTime = endTime,
)

fun Course.toBackup() = BackupCourse(
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

fun BackupCourse.toDomain() = Course(
    name = name,
    alias = alias,
    teacher = teacher,
    location = location,
    campus = campus,
    dayOfWeek = dayOfWeek,
    periods = if (periods.isEmpty() && startSection != null) {
        // v1 备份没有 periods，只有连续的 startSection..endSection。
        // 备份文件是用户可控输入（可被改坏/构造），展开前必须钳制，
        // 否则 "startSection": 1, "endSection": 2000000000 直接 OOM。
        (startSection.coerceIn(1, CourseConstraints.MAX_PERIOD)..
            (endSection ?: startSection).coerceIn(1, CourseConstraints.MAX_PERIOD)).toList()
    } else {
        CourseConstraints.normalizePeriods(periods)
    },
    weeks = weeks,
    colorIndex = colorIndex,
    customColorArgb = customColorArgb,
    remark = remark,
    sourceGroupKey = sourceGroupKey,
    semesterCode = semesterCode,
    isManualOverride = isManualOverride,
    // 与 customColorArgb 同口径：这里原样带回，越界学分统一由
    // CourseConstraints.normalize → normalizeCredit 在写库前判成 null
    credit = credit,
)
