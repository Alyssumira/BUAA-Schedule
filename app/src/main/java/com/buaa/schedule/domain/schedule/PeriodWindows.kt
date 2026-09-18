package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.periodGapMinutesOf
import com.buaa.schedule.domain.model.toPeriodSegments
import com.buaa.schedule.domain.model.toStartEndTimes
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 「此刻是第几节 / 下一节是什么时候 / 还差几分钟」的**唯一算法**。
 *
 * ## 为什么要有这个文件
 * 收敛之前，同一件事在三个面各写了一份：
 * - [TodayPlanner]（界面今日 Hero）：按节次段切窗口，**缺下课时间就整段丢掉**；
 * - `ClassProgressScheduler.nextOrCurrentWindow`（闹钟与实况）：缺下课时间往段内
 *   更早的节回溯，回溯不到就按 45 分钟兜底；
 * - `CourseListWidgetService.widgetRowStatus`（今日组件那一行的进行中/已结束）：
 *   自己又写了一遍"段末下课时间"，兜底条件还多了个 `isAfter(start)`。
 *
 * 三份实现的**取时刻**也不同：窗口用一次 `LocalDateTime.now()`、"是否正在上课"
 * 用另一处晚几毫秒的 `System.currentTimeMillis()` —— 同一次渲染里跨过节次边界，
 * 就会画出"下一节 08:00"却在别的行标"正在进行"这种自相矛盾的组件。
 *
 * ## 收敛后的分工
 * 本文件只管**怎么算**（纯函数，时间一律由参数注入，不读系统时钟）。
 * 三个面之间的差异只允许剩下**谁把 now 递进来**：
 * - 界面：`NowTick` 对齐分钟边界的一次性唤醒；
 * - 组件：`WidgetCommon` 每次重绘读一次 `LocalDateTime.now()` 并传给整条渲染链；
 * - 通知：闹钟投递本身就是唤醒，用广播里那一个 `now`。
 *
 * ## 兜底口径（三份实现里挑一份，其余两处向它对齐）
 * 段末下课时间取段内**最后一个有配置的节**；一个都没有时按
 * [DEFAULT_CLASS_MINUTES] 兜底成"一节 45 分钟"。缺**上课**时间的段直接跳过 ——
 * 兜底成 08:00 会凭空造出一节早上 8 点的课（这条在闹钟链上踩过，注释仍留在
 * `ClassProgressScheduler`）。
 */
object PeriodWindows {

    /** 节次表缺下课时间时的单节兜底时长（原来 reminder 与 widget 各写一份同值常量） */
    const val DEFAULT_CLASS_MINUTES: Long = 45L

    /** 一分钟的毫秒数：[minutesCeil] 与"最迟什么时候必须重发一次"的判据共用 */
    const val MINUTE_MILLIS: Long = 60_000L
}

/** 一个连续节次段在某一天构成的上课窗口 */
data class PeriodWindow(
    val segment: IntRange,
    val begin: LocalDateTime,
    val end: LocalDateTime,
)

/**
 * 某门课在 [date] 这一天的各个节次段窗口，按节次升序。
 *
 * [slotTimes] 取 `List<TimeSlot>.toStartEndTimes()` 的结果；传空表时返回空列表
 * （= 节次时间未配置，调用方按"判不了"处理，而不是按"今天上完了"处理）。
 */
fun periodWindowsOf(
    course: Course,
    date: LocalDate,
    slotTimes: Map<Int, Pair<LocalTime, LocalTime>>,
): List<PeriodWindow> = periodWindowsOf(
    course = course,
    date = date,
    slotTimes = slotTimes,
    segments = course.periods.toPeriodSegments(periodGapMinutesOf(slotTimes)),
)

/**
 * [periodWindowsOf] 的"段已切好"版：闹钟链是按 周次 × 课程 遍历的，
 * 段切分对同一门课是循环不变量 —— 让它跟着周次重算等于每多排一周多切一次段
 * （这条搜索的量级是 课程数 × 剩余周次，功耗审计专门点过）。
 */
fun periodWindowsOf(
    course: Course,
    date: LocalDate,
    slotTimes: Map<Int, Pair<LocalTime, LocalTime>>,
    segments: List<IntRange>,
): List<PeriodWindow> {
    if (slotTimes.isEmpty()) return emptyList()
    return segments.mapNotNull { segment ->
        // 缺上课时间的段整段跳过：兜底成 08:00 会凭空造出一节早八的课
        val start = slotTimes[segment.first]?.first ?: return@mapNotNull null
        val begin = date.atTime(start)
        PeriodWindow(segment, begin, segmentEnd(segment, date, slotTimes, begin))
    }
}

/** 节次表未配置（空表）时退到内置默认作息，与闹钟链同一口径 */
fun periodWindowsOf(
    course: Course,
    date: LocalDate,
    timeSlots: List<TimeSlot>,
): List<PeriodWindow> = periodWindowsOf(
    course,
    date,
    (if (timeSlots.isNotEmpty()) timeSlots else com.buaa.schedule.domain.model.TimeSlotProfile.DEFAULT)
        .toStartEndTimes(),
)

/**
 * 段末下课时间：先取段末那一节，取不到就往段内更早的节回溯，都没有按 45 分钟兜底。
 *
 * 回溯到的时刻必须**晚于开课时刻**才算数：`endTime <= startTime` 是脏数据
 * （节次表校验之外的历史行），拿它当下课时间会画出一个长度为 0 或负的窗口，
 * 让整门课在闹钟链上凭空消失。
 */
private fun segmentEnd(
    segment: IntRange,
    date: LocalDate,
    slotTimes: Map<Int, Pair<LocalTime, LocalTime>>,
    begin: LocalDateTime,
): LocalDateTime {
    val raw = slotTimes[segment.last]?.second
        ?: (segment.first until segment.last).toList().asReversed()
            .firstNotNullOfOrNull { slotTimes[it]?.second }
    val end = raw?.let { date.atTime(it) }
    return if (end != null && end.isAfter(begin)) end
    else begin.plusMinutes(PeriodWindows.DEFAULT_CLASS_MINUTES)
}

/** 这个窗口相对 [now] 的位置。边界口径：开课那一秒起算进行中，下课那一秒起算已结束 */
fun PeriodWindow.statusAt(now: LocalDateTime): SlotStatus = when {
    now < begin -> SlotStatus.UPCOMING
    now < end -> SlotStatus.ONGOING
    else -> SlotStatus.PAST
}

/** 此刻是否正在进行（开课含、下课不含） */
fun PeriodWindow.ongoingAt(now: LocalDateTime): Boolean = statusAt(now) == SlotStatus.ONGOING

/**
 * 一门课在 [date] 这一天的整体状态：任一段在进行中就是进行中，
 * 否则有未开始的段就是未开始，全过了才是已结束。
 *
 * 一个段都构造不出来（节次表未配置）时返回 [SlotStatus.UPCOMING]：
 * 那是数据缺失，不该表现成"今天的课全上完了"（整列被抹灰）。
 */
fun dayStatusOf(
    course: Course,
    date: LocalDate,
    slotTimes: Map<Int, Pair<LocalTime, LocalTime>>,
    now: LocalDateTime,
): SlotStatus {
    val windows = periodWindowsOf(course, date, slotTimes)
    if (windows.isEmpty()) return SlotStatus.UPCOMING
    return when {
        windows.any { it.ongoingAt(now) } -> SlotStatus.ONGOING
        windows.any { it.statusAt(now) == SlotStatus.UPCOMING } -> SlotStatus.UPCOMING
        else -> SlotStatus.PAST
    }
}

/**
 * 还剩多少分钟：不足一分钟按一分钟算，永不为负。
 *
 * **向上取整而不是向下截断**：向下截断会在下课（上课）前的最后一分钟显示
 * "还有 0 分钟"，且与实况那条链的读数恒定差一分钟。
 * 这个口径同时被 `nextCourseFluidTickMs` 用来算"岛上小字还要等多久才翻"，
 * 两处一旦分叉，数字就会晚一分钟才跳。
 */
fun minutesCeil(deltaMillis: Long): Long =
    ((deltaMillis + PeriodWindows.MINUTE_MILLIS - 1) / PeriodWindows.MINUTE_MILLIS)
        .coerceAtLeast(0L)

/** [from] 到 [to] 的分钟数（向上取整，永不为负） */
fun minutesUntil(from: LocalDateTime, to: LocalDateTime): Long =
    minutesCeil(ChronoUnit.MILLIS.between(from, to))

/** [from] 到 [to] 的分钟数（向上取整，永不为负），LocalTime 版 */
fun minutesUntil(from: LocalTime, to: LocalTime): Long =
    minutesCeil(ChronoUnit.MILLIS.between(from, to))

/**
 * 一条**只在下发时求值一次**的相对时间文案，最迟什么时候必须再发一次。
 *
 * 文案里写着"N 分钟"，那它在 `toMillis - (N-1)*60s` 这一秒就翻成了 N-1 —— 这就是
 * 它的保质期终点。判据由此而来：
 * **只有这个时刻本来就有人叫醒我们，相对时间才允许出现在文案里**；
 * 否则要么改成绝对时刻（永不过期），要么就去加一条闹钟（受"稳态 ≤6 条"守卫约束）。
 *
 * [minutesCeil] 已经算出屏幕上那个 N，所以这里只是把同一口径反过来用一次。
 */
fun snapshotRedeadlineMillis(shownMinutes: Long, toMillis: Long): Long =
    if (shownMinutes <= 0L) toMillis
    else toMillis - (shownMinutes - 1L) * PeriodWindows.MINUTE_MILLIS

// ---- 墙钟毫秒 ↔ LocalDateTime：唯一一份换算 ----
// 闹钟排程要的是 epoch 毫秒，窗口算法要的是 LocalDateTime。此前两处各自 `ZoneId.systemDefault()`
// 现算，于是同一次渲染/同一次重排里会读两次时钟；两次之间正好跨过节次边界时，
// "此刻正在上第几节"与"离下一节还有多久"就会各说各话。

/** [LocalDateTime] → epoch 毫秒 */
fun LocalDateTime.toEpochMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
    atZone(zone).toInstant().toEpochMilli()

/** epoch 毫秒 → [LocalDateTime]；<=0 是"这一端时刻缺失"的哨兵，返回 null 而不是 1970 年 */
fun epochMillisToLocalDateTime(
    millis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): LocalDateTime? = if (millis <= 0L) null else runCatching {
    java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime()
}.getOrNull()

