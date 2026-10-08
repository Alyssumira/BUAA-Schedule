package com.buaa.schedule.data.backup

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.ReminderSetting
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
 * 备份/口令 JSON **顶层**那六枚键的册子（台账 #530 发现 3）。
 *
 * 唯一真源是 [BackupData] 自己的序列化描述符（`elementNames`），不是在 domain 里手抄的第二份 ——
 * 加字段/改字段名时这一枚会跟着动，`ShareCodeEnvelopeReview` 的"读不懂的键"判据才不会漂。
 * 口令的信封判据（`data/share/ScheduleShareCodec`）要问"这条口令里有我不认得的键吗"，
 * 问的就是这份清单。
 */
val BACKUP_ENVELOPE_KEYS: List<String> = BackupData.serializer().descriptor.elementNames.toList()

/** [BackupData.version] 的线上键名（**在不在**这一问用得上：缺键不等于版本低，见下面那两行） */
const val BACKUP_VERSION_KEY = "version"

/**
 * [BackupData.semester] 的线上键名。⚠️ 它**不是**列表表，条数闸门不管它，
 * 但"这条口令带没带东西"那一问要把它算进"认得的键"，否则老口令的 `{"semester":null}` 会被误判成
 * 读不懂的键（`explicitNulls` 默认 true ⇒ 单字段学期总是写出来的）。
 */
const val BACKUP_SEMESTER_KEY = "semester"

/** [BackupData.semesters] 的线上键名（T204 加的表；条数闸门不数它，但"带没带东西"要数） */
const val BACKUP_SEMESTERS_KEY = "semesters"

/** [BackupData.timeSlots] 的线上键名（节次表，闸门上限 500） */
const val BACKUP_TIME_SLOTS_KEY = "timeSlots"

/** [BackupData.courses] 的线上键名（课程表，闸门上限沿用 [CourseConstraints.MAX_COURSE_COUNT]） */
const val BACKUP_COURSES_KEY = "courses"

/** [BackupData.reminders] 的线上键名（提醒表，闸门上限跟课程那一枚） */
const val BACKUP_REMINDERS_KEY = "reminders"

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
 *
 * ⚠️ 备份**只带库里有的那些行**（导出侧读的是 `getReminders()`），所以"这份备份里没有这门课的提醒"
 * 与"库里没有那一行"是同一件事，恢复后仍按 [ReminderSetting] 顶部那条默认口径走 ——
 * 这里的两枚默认值因此必须与 [ReminderSetting] 同源，不许自己写一遍 `10`。
 *
 * ## 那枚键不够用（T229 ②，台账 #303）
 *
 * `courseKey` 的七维是 学期 + 组键 + 名称 + 教师 + 地点 + 星期 + 节次，**里面没有 `weeks`**
 * （定义在 `domain/schedule/ImportPlanner.courseKey`）⇒ 同一门课被编辑器「这周调课/代课」拆成的
 * 多枚片段**共用同一枚键**，而拆行那一手本来就给两行各挂一枚提醒
 * （`ScheduleRepository.updateCoursePartialWeeks`）。所以只有 [courseKey] 时，读侧无论怎么写都
 * 得回答"这一枚设置是给哪一行片段的"—— 改前它答不上，只能"后写吃前写"。
 *
 * [fragmentWeeks] 就是补上去的那枚**片段依据**：导出时把这一行当时的周次一起带上，
 * 恢复时按 (键, 周次签名) 配对，判据在 [BackupReminderPairing]。
 *
 * ## 兼容性判据（补键会不会让老备份配不上新键？）
 *
 * **不会**，两侧都验过（`data/backup/BackupReminderFragmentKeyCompatTest`）：
 * - **老备份 → 新应用**：老文件根本没有 `fragmentWeeks` 这个键 ⇒ 解析成 `null`（带默认值的可选字段），
 *   读侧把它当成"这一枚没有片段依据"，退到**同键组内逐枚配对**（[BackupReminderPairing.Basis.LegacyNoSignature]）
 *   而不是退到"这一枚丢掉"。配对结论与改前一样能落库，只是不再有一枚片段被静默吞掉；
 * - **新备份 → 老应用**：`fragmentWeeks` 是它不认得的键，而两边解码用的都是
 *   `Json { ignoreUnknownKeys = true }` ⇒ 直接忽略，老应用照旧按 `courseKey` 恢复（旧行为原样）。
 *
 * 因此 [BACKUP_FORMAT_VERSION] **不涨**：口径与 T204 那两格（[BackupData.semesters]、
 * [BackupCourse.credit]）一致 —— 新增带默认值的可选字段两边都认得，涨到 3 只会让老应用对这份文件
 * 直接拒绝恢复（`data.version > BACKUP_FORMAT_VERSION`），换来的是一条**更窄**的升级路，不是更兼容。
 */
@Serializable
data class BackupReminder(
    val courseKey: String,
    val enabled: Boolean = ReminderSetting.DEFAULT_ENABLED,
    val advanceMinutes: Int = ReminderSetting.DEFAULT_ADVANCE_MINUTES,
    /**
     * 片段依据：导出时这一行课程的周次（去重升序）。老备份没这个键 ⇒ `null`，读侧退到组内逐枚配对。
     * 带默认值的可选字段 ⇒ 线上兼容，见上面那段「兼容性判据」，[BACKUP_FORMAT_VERSION] 不涨。
     */
    val fragmentWeeks: List<Int>? = null,
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
