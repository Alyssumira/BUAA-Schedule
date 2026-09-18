package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.TimeSlotProfile
import com.buaa.schedule.domain.model.periodGapMinutesOf
import com.buaa.schedule.domain.model.periodLabel
import com.buaa.schedule.domain.model.startLocalDate
import com.buaa.schedule.domain.model.toPeriodSegments
import com.buaa.schedule.domain.model.toStartEndTimes
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime

/** 一次实际上课：课程 × 教学周 × 连续节次段 */
data class Occurrence(
    val course: Course,
    val date: LocalDate,
    val start: LocalTime,
    val end: LocalTime,
    val segment: IntRange,
    /** 稳定身份：课程内容不变时重复生成得到相同 ID */
    val stableId: String,
    /** 内容摘要：字段变化时改变，用于判断日历事件是否需要更新 */
    val contentHash: String,
) {
    /** 日程标题（ICS 与系统日历事件共用）：与课表卡片、通知同一套别名口径 */
    val title: String get() = course.displayName

    /**
     * 日程描述（未转义；ICS 导出时另行转义）。
     *
     * 节次只写**本段** [segment]，不写整门课的 `course.periods`：一个 VEVENT 只覆盖
     * 一个节次段，写全量标签会让 `[5,6]` 这种隔着午饭拆成两段的课导出两条
     * 「第5-6节」、而各自只有 45 分钟（P1-2）。
     */
    val description: String
        get() = buildString {
            course.teacher?.let { append("教师: $it · ") }
            append(periodLabel(segment))
            append(" · ${com.buaa.schedule.domain.schedule.WeekParser.toDisplayString(course.weeks)}")
            course.campus?.let { append(" · $it") }
        }
}

data class OccurrenceBuildResult(
    val occurrences: List<Occurrence>,
    /** 因节次时间缺失/非法而跳过的课次数量 */
    val skipped: Int,
)

/**
 * 把课程表展开为“每次实际上课”的课次列表。
 * ICS 导出与系统日历同步共用，保证两者的身份与内容语义一致。
 */
object ScheduleOccurrences {

    private const val STABLE_ID_SUFFIX = "@buaa-schedule"

    fun build(
        semester: Semester,
        courses: List<Course>,
        timeSlots: List<TimeSlot>,
    ): OccurrenceBuildResult {
        val semesterStart = semester.startLocalDate
            ?: return OccurrenceBuildResult(emptyList(), 0)

        val slots = (timeSlots.ifEmpty { TimeSlotProfile.DEFAULT }).toStartEndTimes()
        // 节次号相邻 ≠ 连堂：中间隔着午休的 [5,6] 必须切成两个课次
        val gapMinutes = periodGapMinutesOf(slots)

        val result = mutableListOf<Occurrence>()
        // 撞 id 的课次按出现顺序追加序号后缀，见下方注释
        val usedIds = mutableSetOf<String>()
        var skipped = 0
        for (course in courses) {
            for (week in course.weeks) {
                for (segment in course.periods.toPeriodSegments(gapMinutes)) {
                    val startSlot = slots[segment.first]
                    val endSlot = slots[segment.last]
                    if (startSlot == null || endSlot == null) {
                        skipped++
                        continue
                    }
                    val date = semesterStart
                        .plusWeeks((week - 1).toLong())
                        .plusDays((course.dayOfWeek - 1).toLong())
                    result.add(
                        Occurrence(
                            course = course,
                            date = date,
                            start = startSlot.first,
                            end = endSlot.second,
                            segment = segment,
                            stableId = uniqueStableId(course, week, segment, usedIds),
                            contentHash = contentHashFor(course, week, segment, date, startSlot.first, endSlot.second),
                        )
                    )
                }
            }
        }
        return OccurrenceBuildResult(result, skipped)
    }

    /**
     * [stableIdFor] 外加一层去重（R5 F-33）。
     *
     * 身份键是 学期|组键|星期|节次，而理论课与实验课在教务数据里**共用同一个
     * teachClassId**（见 BuaaScheduleParser），于是同周同段会展开出两条 id 完全相同的
     * 课次：日历映射以 occurrenceId 为主键，撞车的那条再也删不掉（幽灵事件），
     * ICS 里则是重复 UID。后缀加在 `@buaa-schedule` 之后，不会与任何基础 id 相同。
     * 依赖输入顺序 —— 课程列表按 id 排序取出，同一份课表两次展开结果一致。
     */
    private fun uniqueStableId(
        course: Course,
        week: Int,
        segment: IntRange,
        usedIds: MutableSet<String>,
    ): String {
        val base = stableIdFor(course, week, segment)
        if (usedIds.add(base)) return base
        var index = 2
        while (!usedIds.add("$base#$index")) index++
        return "$base#$index"
    }

    /**
     * 与 ICS 导出的 UID 同构：课程身份 + 周次 + 节次段。
     *
     * 身份键可能很长（学期 + 组键/课程名 + 节次），此前直接 `take(80)` 截断：
     * 前缀相同、差异落在第 80 个字符之后的两门课会得到**同一个 UID**，
     * 在日历同步里表现为两个课次共用一条映射、后写入的覆盖前者。
     * 这里保留可读前缀，再拼 8 位内容摘要 —— 既可读又不会因截断碰撞。
     */
    fun stableIdFor(course: Course, week: Int, segment: IntRange): String =
        "${courseStableId(course)}-w$week-p${segment.first}-${segment.last}$STABLE_ID_SUFFIX"

    /** 课程粒度的稳定 ID：可读前缀 + 8 位内容摘要，日历同步的 courseStableId 列与课次 UID 共用这一份实现 */
    fun courseStableId(course: Course): String {
        val identity = courseIdentityKey(course)
        val readable = identity.replace(ID_SANITIZE, "-").take(60)
        val digest = md5Hex(identity).take(8)
        return "$readable-$digest"
    }

    private val ID_SANITIZE = Regex("[^\\w\\u4e00-\\u9fa5-]")

    /**
     * 课程身份键：学期 + 组键（教务课程；手动课程退化为课程名）+ 星期 + 节次。
     * 刻意不含教师/地点/名称——这些字段变化时保持同一身份，
     * 日历同步会更新原事件而不是删旧建新。
     */
    fun courseIdentityKey(course: Course): String = listOf(
        course.semesterCode ?: "",
        course.sourceGroupKey ?: course.name.trim(),
        course.dayOfWeek,
        course.periods.joinToString(","),
    ).joinToString("|")

    /** 每个线程复用一个 MessageDigest：一学期上千个课次，反复 getInstance 是纯浪费 */
    private val digestHolder: ThreadLocal<MessageDigest> =
        ThreadLocal.withInitial { MessageDigest.getInstance("MD5") }

    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    /** MD5 → 小写十六进制。手写查表，避免逐个字节走 String.format（原先约 1.9 万次格式化） */
    private fun md5Hex(raw: String): String {
        val digest = digestHolder.get()
        digest.reset()
        val bytes = digest.digest(raw.toByteArray(Charsets.UTF_8))
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val value = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX_CHARS[value ushr 4]
            out[i * 2 + 1] = HEX_CHARS[value and 0x0F]
        }
        return String(out)
    }

    /**
     * 内容摘要：决定一个课次要不要向系统日历重发 update。
     *
     * 摘要里放的必须是**真的上了日历**的东西：这里曾用 `course.name`，于是用户改别名
     * 之后 [title] 变了而摘要不变，`CalendarSyncPlanner` 判定 unchanged，
     * 系统日历上的旧标题永远刷不掉（P2-5）。改成 [Course.displayName] 后，
     * 别名与原名一起受摘要覆盖。
     *
     * 刻意不含 [courseIdentityKey] 之外的身份字段，也刻意不动身份键本身：
     * 摘要变了只会走 update，身份键变了才是删旧建新 —— 后者会让用户已同步的
     * 日历事件全部重建一次（连带丢掉用户在日历 App 里加的备注）。
     * 本次摘要口径变更后，已同步的事件会在下一次同步时各自 update 一遍。
     */
    private fun contentHashFor(
        course: Course,
        week: Int,
        segment: IntRange,
        date: LocalDate,
        start: LocalTime,
        end: LocalTime,
    ): String {
        val raw = listOf(
            course.displayName,
            course.teacher ?: "",
            course.location ?: "",
            course.campus ?: "",
            course.dayOfWeek,
            course.periods.joinToString(","),
            week,
            segment.first,
            segment.last,
            date.toString(),
            start.toString(),
            end.toString(),
        ).joinToString("|")
        return md5Hex(raw)
    }
}
