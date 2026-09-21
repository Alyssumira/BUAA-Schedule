package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot

/**
 * 学期负载跨周趋势的判据内核（统计页那条折线吃它）。
 *
 * 纯 Kotlin：没有 Compose、没有 Android 依赖，可直接 JVM 单测。
 * "现在是第几周"当参数传进来，本文件不读时钟。
 *
 * 它回答的是 [SemesterStats.DayLoad.averageMinutes] 回答不了的那半句话：
 * 日负载说"周三平均两小时"，是**把 19 周一平均**之后的数 ——
 * 前紧后松的课表（一半课在第 8 周结课）和整学期等重的课表会给出同一组数字。
 * 按周摊开以后"第几周最忙""从第几周开始塌下去"才看得见。
 *
 * 口径与 [SemesterStats] 保持一致：
 * - 分钟数 = 该周实际有课的那些片段的节次时长之和（`endTime <= startTime` 的节次不算，
 *   见 `SemesterStats` 的 slotMinutes），越出 1..totalWeeks 的周次一律不计；
 * - **节次时长查不到就是 0，不是猜一个**：作息表被裁剪时折线会偏低，
 *   这个偏差由 [Trend.unschedulablePeriodCellCount] 报给界面说清楚；
 * - 脏星期序号（0 / 8）整条片段跳过，与 `SemesterStats.dayLoads` 同一处理。
 *
 * "结课周"只标**有依据**的那些：某门课（按 [SemesterStats.courseGroupKey] 归并）
 * 最后一次上课的那一周，且那一周不是学期最后一周。第 16 周（学期终点）不标——
 * 那不叫结课，那叫学期结束，标上去等于给每份课表都加一个假旗子。
 */
object WeeklyLoadTrend {

    /**
     * [trendOf] 的结果。
     *
     * @param totalWeeks 横轴长度，与 [SemesterStats.weekAxisLength] 同一口径
     * @param minutes 一条一项，下标 0 = 第 1 周，单位分钟。0 是**真的没课**，
     *   不是"缺数据"——缺数据的那几门课不会从横轴上消失，它们只是不贡献分钟
     * @param currentWeek 当前教学周（1..totalWeeks），null = 原点缺失或假期
     * @param peakWeek 最忙的一周（并列取周号最小的），全 0 时 null
     * @param peakMinutes 最忙那周的分钟数，全 0 时 0
     * @param averageMinutes 有课的那些周的平均分钟数（向下取整）；一周都没课时 0
     * @param freeWeeks 该周分钟数为 0 的周号（"第 12 周起整个学期空了"靠它说）
     * @param endings 周号 → 那一周结课的门数（升序键，界面标旗用）
     * @param unschedulablePeriodCellCount (星期 × 节次) 里节次时长在作息表查不到的**格数**
     *   （两门课撞同一格只算一格，与 `SemesterStats` 的空档同一维度）：
     *   这些格贡献 0 分钟，折线因此偏低，"偏低了"这件事必须能说
     * @param hasAnyWeeksData 至少有一条片段带着落在范围内的周次；false 时整张图是
     *   "无数据"，而不是"整学期一条线贴地板"
     */
    data class Trend(
        val totalWeeks: Int,
        val minutes: List<Long>,
        val currentWeek: Int?,
        val peakWeek: Int?,
        val peakMinutes: Long,
        val averageMinutes: Long,
        val freeWeeks: List<Int>,
        val endings: Map<Int, Int>,
        val unschedulablePeriodCellCount: Int,
        val hasAnyWeeksData: Boolean,
    ) {
        val isEmpty: Boolean get() = minutes.isEmpty() || !hasAnyWeeksData
    }

    /**
     * 按周聚合分钟数 + 结课周。
     *
     * @param currentWeek 只用于 [Trend.currentWeek] 原样带下去给画游标用；
     *   所有数值都不读它 —— 与 [SemesterStats] "只按教学周号算"那条口径一致
     */
    fun trendOf(
        courses: List<Course>,
        semester: Semester?,
        timeSlots: List<TimeSlot>,
        currentWeek: Int?,
    ): Trend {
        val totalWeeks = SemesterStats.weekAxisLength(courses, semester)
        val slotMinutes = SemesterStats.slotMinutes(timeSlots)

        val perWeek = LongArray(totalWeeks)
        // 查不到时长的 (星期 × 节次) 格：与 SemesterStats 的"格"同一维度，两门课撞同一格只算一格
        val unschedulableCells = Array(SemesterStats.TOTAL_DAYS) { mutableSetOf<Int>() }
        var hasWeeksData = false
        // 每门课（归并后）最后一次上课的周号：结课周按门计，不按片段计
        val lastWeekByGroup = LinkedHashMap<String, Int>()

        for (course in courses) {
            val day = course.dayOfWeek
            if (day !in 1..SemesterStats.TOTAL_DAYS) continue
            val inRangeWeeks = course.weeks.asSequence().filter { it in 1..totalWeeks }.distinct().toList()
            if (inRangeWeeks.isEmpty()) continue
            hasWeeksData = true
            val groupKey = SemesterStats.courseGroupKey(course)
            val courseLastWeek = inRangeWeeks.maxOrNull() ?: 0
            lastWeekByGroup[groupKey] = maxOf(lastWeekByGroup[groupKey] ?: 0, courseLastWeek)

            val minutesPerMeeting = course.periods.distinct().sumOf { period ->
                slotMinutes[period] ?: run {
                    // 作息表里没有这一节（或被裁掉了）：这门课在这一格贡献 0 分钟，
                    // 折线因此偏低——偏多少不知道，但"偏低了"这件事要能被界面说出来
                    unschedulableCells[day - 1].add(period)
                    0L
                }
            }
            if (minutesPerMeeting <= 0L) continue
            inRangeWeeks.forEach { week -> perWeek[week - 1] += minutesPerMeeting }
        }

        // 学期最后一周不标旗：那是学期结束，不是某门课结课
        val endings = lastWeekByGroup.values
            .filter { it in 1 until totalWeeks }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedBy { it.key }
            .associate { (week, count) -> week to count }

        val peak = perWeek.indices.maxByOrNull { perWeek[it] }?.plus(1)
        val peakMinutes = if (perWeek.isEmpty()) 0L else perWeek.max()
        val busyWeeks = perWeek.filter { it > 0L }
        return Trend(
            totalWeeks = totalWeeks,
            minutes = perWeek.toList(),
            currentWeek = currentWeek?.takeIf { it in 1..totalWeeks },
            peakWeek = if (peakMinutes > 0L) peak else null,
            peakMinutes = if (peakMinutes > 0L) peakMinutes else 0L,
            averageMinutes = if (busyWeeks.isEmpty()) 0L else busyWeeks.sum() / busyWeeks.size,
            freeWeeks = perWeek.indices.filter { perWeek[it] == 0L }.map { it + 1 },
            endings = endings.toSortedMap(),
            unschedulablePeriodCellCount = unschedulableCells.sumOf { it.size },
            hasAnyWeeksData = hasWeeksData,
        )
    }
}
