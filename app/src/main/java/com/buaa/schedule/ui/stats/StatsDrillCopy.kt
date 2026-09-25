package com.buaa.schedule.ui.stats

import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.joinMeta
import com.buaa.schedule.domain.model.periodLabelOf
import com.buaa.schedule.domain.model.weekdayLabel
import com.buaa.schedule.domain.schedule.WeekDaySchedule

/**
 * 统计页「按周细看」那一块的措辞判据（T82 的钻取）。
 *
 * 零 android import、零时钟读取（本仓口径）：输入是 [WeekDaySchedule] 算好的事实，
 * 外加调用点递进来的节次表。哪一周是"当前周"、今天是几号，都由调用点当参数传，
 * 这里不读 —— 读了这张表在 JVM 里就打不开，而"这一周有没有课"恰好是最容易
 * 拿设备当下的一刻猜错的那件事。
 *
 * ## 一句话里那两个"课数"必须分得开
 *
 * [WeekDaySchedule.DaySchedule.meetingCount] 是**这一周这天几堂课**（片段数），
 * [WeekDaySchedule.DaySchedule.courseCount] 是**几门课**（按课程身份键去重）。
 * 而「每周负载」那张卡上那句「这学期里，周一有 4 门不同的课」说的是
 * [com.buaa.schedule.domain.schedule.SemesterStats.DayLoad.courseCount] ——
 * **全学期并集、不分周次**那一档（SemesterStats.kt 里 groupsByDay）。
 * 三个数天生不等（一门 1-8 周的课在并集里算 1 门、在第 12 周算 0 堂），
 * 所以这里的每一句都必须自带「第 N 周」这个限定，措辞也不许照抄那一句。
 */

/** 最多画几天：19 周里挑出来的那一周通常 5 天，全铺会把这块撑成一屏 */
internal const val MAX_DRILL_DAYS = 7

/**
 * 收起那一档的提示行：告诉用户这里能点开，以及点开看到什么。
 *
 * `currentWeek` 是设备事实，由调用点从 `uiState.currentWeek` 递进来（null = 学期原点缺失
 * 或正在假期，那时这句不该硬说"当前第几周"）。
 */
internal fun drillCollapsedHint(currentWeek: Int?): String =
    if (currentWeek != null) "挑一周，看那一周具体排了什么课（现在是第 $currentWeek 周）"
    else "挑一周，看那一周具体排了什么课"

/** 「第 3-4 节 · J3-101 · 张三」；节次/教师/教室任一项缺席就整段不写（含分隔符） */
internal fun drillMeetingLine(meeting: WeekDaySchedule.Meeting, timeSlots: List<TimeSlot>): String =
    joinMeta(
        periodLabelOf(meeting.course.periods, timeSlots),
        meeting.location,
        meeting.teacher,
    )

/**
 * 一条实排末尾那个「· 1 门课两段」之类的补充：只在同门课在这天排了不止一段时说。
 *
 * 这是这一卡里唯一容易被读成"数错了"的一档：用户看到周三两行都写着同一门课，
 * 而门数那一句说 1 —— 不点破"这是同一门课的两段"就会被当成 bug。
 */
internal fun drillSameCourseNote(day: WeekDaySchedule.DaySchedule): String? {
    val doubled = day.meetings.groupBy { it.groupKey }.filterValues { it.size > 1 }
    if (doubled.isEmpty()) return null
    val extra = day.meetingCount - day.courseCount
    return "其中有同一门课排成的 $extra 段"
}

/**
 * 一天的收尾那句：`3 堂课 · 4 小时`。
 *
 * 分钟数不可信的那一档（节次查不到时长）不硬凑一个数：[WeekDaySchedule.minutesKnown]
 * 全为真才说总时长，否则只说几堂课 —— 与「负载趋势」那张卡"这条线偏低"的处理同一条路，
 * 但这里更保守：偏低的数宁可不给。
 */
internal fun drillDayNote(day: WeekDaySchedule.DaySchedule): String {
    val parts = listOfNotNull(
        "${day.meetingCount} 堂课",
        if (day.meetings.isNotEmpty() && day.meetings.all { it.minutesKnown }) humanMinutes(day.minutes) else null,
    )
    return parts.joinToString(" · ")
}

/**
 * 一天的标题：`周三 · 2 堂课`。脏星期序号不会到这里（内核只产 1..7），
 * 但 [weekdayLabel] 自己就返回可空，所以这里保留那一句兜底的措辞。
 */
internal fun drillDayTitle(day: WeekDaySchedule.DaySchedule): String =
    "${weekdayLabel(day.dayOfWeek) ?: "周?"} · ${drillDayNote(day)}"

/**
 * 整周的收尾那句：`5 天有课 · 共 12 堂课 · 4 门课`。
 *
 * 「几门课」按课程身份键去重（跨天也算一门）：周一和周三都上的那门课是一门课，
 * 这与 [com.buaa.schedule.domain.schedule.SemesterStats] 的学分归并同一句承诺 ——
 * 一门课的定义只有一份。
 */
internal fun drillWeekNote(schedule: WeekDaySchedule.WeekSchedule): String = joinMeta(
    "${schedule.busyDayCount} 天有课",
    "${schedule.meetingCount} 堂课",
    if (schedule.courseCount == schedule.meetingCount) null else "${schedule.courseCount} 门课",
)

/**
 * 「这一周什么都没有」那两档的话。
 *
 * 两件事必须分开说，否则就是在谎报：
 * - 周号越界（换学期之后选中值还停在旧周号）：`第 25 周不在这学期里，这学期共 19 周`；
 * - 真的没课：`第 12 周没有排课`。
 * 有课时返回 null，那两句话都不该出场。
 */
internal fun drillEmptyNote(schedule: WeekDaySchedule.WeekSchedule): String? = when {
    !schedule.outOfRange && schedule.meetingCount > 0 -> null
    schedule.outOfRange -> "第 ${schedule.week} 周不在这学期里，这学期共 ${schedule.totalWeeks} 周"
    else -> "第 ${schedule.week} 周没有排课"
}

/**
 * 「这一天的另 N 堂课没画」那一句；全画出来了返回 null。
 *
 * `shown` 是界面**实际画了几行**，由调用点递进来 —— 这一件函数不猜"界面上有几行"，
 * 那是排版的事，内核里也没有。两者对不上只会红在单测里，不会红在用户的屏幕上。
 */
internal fun drillHiddenMeetingsNote(day: WeekDaySchedule.DaySchedule, shown: Int): String? {
    val hidden = day.meetingCount - shown
    return if (hidden > 0) "另有 $hidden 堂课" else null
}

/**
 * 点了没课的那一天：`第 12 周周三没有排课`。
 *
 * 这一档**必须**有一句话：空白一片会被读成"这一页没渲染出来"（同一件事在
 * [com.buaa.schedule.domain.schedule.WeekFreeGrid] 那里是靠"空格子也画出来"兜的）。
 */
internal fun drillDayEmptyNote(day: WeekDaySchedule.DaySchedule, week: Int): String =
    "第 $week 周${weekdayLabel(day.dayOfWeek) ?: "周?"}没有排课"

/**
 * 「有 N 堂课排在作息表之外」那一句，0 堂时 null。
 *
 * 与「负载趋势」那张卡那句"另有 N 格查不到节次时长，这条线偏低"是同一件事：
 * 作息表被裁剪时这里的分钟数是真的算不出来，藏着不说是谎报。
 */
internal fun drillMinutesUnknownNote(count: Int): String? =
    if (count > 0) "另有 $count 堂课排在作息表之外的节次，没算进时长" else null

/**
 * 「有 N 门课整门都没有周次数据」那一句，0 门时 null。
 *
 * 数字吃的是「周次覆盖」那张卡的 `Board.unknownCount`（[com.buaa.schedule.domain.schedule.CourseWeekSpans]
 * 的产物），这里**不重新判**哪些课算"没周次"：那一判在 CourseWeekSpans 只有一份，
 * 两张卡各自数一遍就会出现"这张说 1 门、那张说 2 门"。
 */
internal fun weeksUnknownNote(count: Int): String? =
    if (count > 0) "另有 $count 门课没有周次数据，按周看不到它们" else null

/** 分钟数说成人话（与本页 [StatsScreen] 那句同一档措辞，量级只到小时） */
private fun humanMinutes(minutes: Long): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h <= 0L -> "$m 分钟"
        m <= 0L -> "$h 小时"
        else -> "$h 小时 $m 分钟"
    }
}
