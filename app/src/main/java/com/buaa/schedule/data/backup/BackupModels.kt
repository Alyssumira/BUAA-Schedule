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

/**
 * 备份里带全的学期行（T204）。**可选、带默认值 ⇒ 线上兼容，`BACKUP_FORMAT_VERSION` 不涨**，
 * 理由与 [BackupCourse.credit] 那一段同口径（读 [BACKUP_FORMAT_VERSION] 的注释）：
 * - 老备份（v1/v2）没有 `semesters` 这个键 → 解析成空列表，读侧回落到单字段 [semester] 那一枚，
 *   恢复照旧，不报「版本过高」；
 * - 新备份被老应用读时，`semesters` 是它不认得的键，`ignoreUnknownKeys` 直接忽略 →
 *   它照旧按单字段 [semester] 恢复主学期（也就是**旧行为原样**，跨学期那一档的偏移老应用修不了，
 *   但我们不产出它读不了的备份、也不改变它已能读的那些内容）；
 * - 把版本涨到 3 只会让老应用对这份文件直接拒绝恢复（`data.version > BACKUP_FORMAT_VERSION`），
 *   而字段本身两边都认得 —— 涨版本只换来一条更窄的升级路。
 *
 * 为什么必须有这一枚列表：[courses] 里每一门课都带 [BackupCourse.semesterCode]，课程是跨学期的，
 * 单字段 [semester] 只装得下主学期那一枚 ⇒ 历史学期在恢复时无据可依（见
 * `BackupSemesterRestorePolicy` 段首那一笔损坏账）。[semester] 保留不删：它同时是
 * 老应用的唯一学期来源，也是恢复时主学期配置（节次、周数）的出处。
 */
@Serializable
data class BackupData(
    val version: Int = BACKUP_FORMAT_VERSION,
    val semester: BackupSemester? = null,
    val semesters: List<BackupSemester> = emptyList(),
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
