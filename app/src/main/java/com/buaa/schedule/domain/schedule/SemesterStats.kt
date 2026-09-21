package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.TimeSlotProfile
import com.buaa.schedule.domain.model.startLocalDate
import com.buaa.schedule.domain.model.toStartEndTimes
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.math.round

/**
 * 学期统计（纯 Kotlin：没有 Compose、没有 Android 依赖，可直接 JVM 单测）。
 *
 * 只回答四件事：多少学分、每周上多少分钟、每天几门课、哪里空。
 * 输入全部来自既有字段 —— 新增的 [Course.credit] 加上排课本体
 * `dayOfWeek / periods / weeks`，没有为此再往模型里塞任何东西。
 *
 * ## 贯穿全文件的两个口径
 *
 * 1. **「一门课」≠「一条 Course」**：一条 Course 只是一个排课片段（理论/实验、
 *    1-8 周与 9-16 周、不同教师/教室），[Course.sourceGroupKey] 才是把同一门课的
 *    片段串起来的键。学分是**课程级**属性，教务在它返回的每一行上都把整门课的学分
 *    重写一遍，所以片段既不能各算一次学分（[creditsByCourse] 按 [courseGroupKey] 归并）、
 *    归并时也不能求和（组内取最大值，与 `BuaaScheduleParser.mergeSameSlotOccurrences`
 *    并预览行的口径一致）。求和等于把总学分乘上片段数。
 * 2. **只按教学周号算，不碰日历**：所有指标只用到"第几周有没有课"，所以学期开学
 *    日期缺失或非法（[Semester.startDate] 解析不出来）不影响数值 —— 需要日历原点的
 *    是"现在是第几周"，那是 `WeekCalculator` 与 `buildFallbackSemester` 的职责
 *    （后者保证送进来的学期总带一个合法的周一原点）。
 *
 * 公开函数对以下输入都不抛异常、不返回 null：空课程列表、`credit == null`、
 * 周次越出学期范围、`semester == null`、开学日期非法、作息表为空。
 *
 * T51 给统计页新加的三张图（[CourseWeekSpans] / [WeekFreeGrid] / [WeeklyLoadTrend]）
 * 沿用本文件的 [courseGroupKey]、[weekAxisLength] 与作息表取数口径：上面那两个口径
 * 对它们同样成立，谁另起一套"一门课"或"横轴多少周"，三张图就会各说各话。
 */
object SemesterStats {

    /** 星期数（周一..周日）；[dayLoads] 恒为此长度，下标 0 = 周一 */
    const val TOTAL_DAYS: Int = 7

    /**
     * 一门课（按 [courseGroupKey] 归并后的一个组）的学分。
     *
     * @param groupKey 课程身份键，见 [courseGroupKey]
     * @param course 组内第一个片段，供展示层取名称/别名/颜色（统计本身不用外观字段）
     * @param credit 该门课学分；组内没有任何片段带学分时为 null（= 不知道，不是 0）
     * @param fragmentCount 该门课占了几条排课片段（UI 可据此说明"由 N 个片段合并"）
     */
    data class CourseCredit(
        val groupKey: String,
        val course: Course,
        val credit: Double?,
        val fragmentCount: Int,
    )

    /**
     * 一个星期几的负载。
     *
     * @param dayOfWeek ISO 星期序号，1 = 周一 … 7 = 周日
     * @param averageMinutes 学期内平均每周上课分钟数（向下取整）。只上 1-8 周的课在
     *   第 9 周起贡献 0，所以平均值如实反映"这门课中途结束"
     * @param peakMinutes 单周最高分钟数（全学期都在的课它与 [averageMinutes] 相同）
     * @param courseCount 该天有课的门数（按 [courseGroupKey] 去重，同天两个片段不重复计）
     * @param freePeriodCount 该天空闲的节次格数 = 作息表节次数 − 该天全学期占用过的格数
     */
    data class DayLoad(
        val dayOfWeek: Int,
        val averageMinutes: Long,
        val peakMinutes: Long,
        val courseCount: Int,
        val freePeriodCount: Int,
    ) {
        /**
         * 该天没有任何课。用 peak 而不是 average 判定：一门 45 分钟的课摊到 19 周里
         * 平均值会取整成 2，但它确实占着格子。
         */
        val isFree: Boolean get() = peakMinutes == 0L
    }

    /**
     * [summarize] 的结果。
     *
     * @param fragmentCount 参与统计的排课片段条数（= 传入的 courses.size）
     * @param courseCount 归并后的课程门数
     * @param totalCredits 总学分：只累加有学分数据的课程，一条都没有时为 0.0
     * @param creditsKnown 有学分数据的课程门数
     * @param creditsMissing 缺学分数据的课程门数（UI 得能说清总学分为什么偏小）
     * @param perCourse 每门课的学分，保持课程首次出现的顺序
     * @param dayLoads 7 项，下标 0 = 周一
     * @param busiestDayOfWeek 平均分钟数最大的一天；整周都没有课时为 null
     * @param quietestBusyDay 有课的几天里最轻的一天；整周都没有课时为 null
     * @param periodsPerDay 作息表一天的节次数，也就是空档格数的分母
     * @param freeSlotCount 全学期都不落课的 (星期 × 节次) 格数（= 各天 freePeriodCount 之和）
     * @param weekCount 参与计算的周数
     * @param semesterAnchored 学期原点是否可用；仅信息位，本文件所有数值都不依赖它
     */
    data class SemesterSummary(
        val fragmentCount: Int,
        val courseCount: Int,
        val totalCredits: Double,
        val creditsKnown: Int,
        val creditsMissing: Int,
        val perCourse: List<CourseCredit>,
        val dayLoads: List<DayLoad>,
        val busiestDayOfWeek: Int?,
        val quietestBusyDay: Int?,
        val periodsPerDay: Int,
        val freeSlotCount: Int,
        val weekCount: Int,
        val semesterAnchored: Boolean,
    ) {
        /** 全学期课格总数 = 7 × [periodsPerDay]，与 [freeSlotCount] 同分母 */
        val totalSlotCount: Int get() = TOTAL_DAYS * periodsPerDay
    }

    /**
     * 课程身份键：把同一门课的多个排课片段归并成「一门课」。
     *
     * 有 [Course.sourceGroupKey] 时直接沿用 —— 它就是教务给的分组键（学期 + 教学班 +
     * 课序号），与 `ScheduleOccurrences.courseIdentityKey` 同源，但**刻意不含
     * dayOfWeek/periods**：那个键要精确到"哪一格课次"，而学分属于整门课，
     * 理论课（周一 1-2 节）与实验课（周五 5-6 节）共用一个组键时只能算一次。
     *
     * 没有组键的（手动课程、文本/ICS 导入）退化成 `学期|课程名`，与上面那个键一样是
     * "名字即身份"的最粗口径；这类来源本来就没有学分数据，不会因此算错总学分。
     */
    fun courseGroupKey(course: Course): String =
        (course.semesterCode ?: "") + "|" + (course.sourceGroupKey ?: course.name.trim())

    /**
     * 每门课的学分：按 [courseGroupKey] 归并，组内**取最大值**（口径 1），
     * 全组都没有学分时为 null。
     */
    fun creditsByCourse(courses: List<Course>): List<CourseCredit> {
        val grouped = LinkedHashMap<String, MutableList<Course>>()
        courses.forEach { course ->
            grouped.getOrPut(courseGroupKey(course)) { mutableListOf() }.add(course)
        }
        return grouped.map { (key, fragments) ->
            CourseCredit(
                groupKey = key,
                course = fragments.first(),
                credit = fragments.mapNotNull { CourseConstraints.normalizeCredit(it.credit) }.maxOrNull(),
                fragmentCount = fragments.size,
            )
        }
    }

    /**
     * 总学分：先按 [courseGroupKey] 归并再相加（同门课的多个片段只算一次），
     * 缺学分数据的课程不计入。
     *
     * 结果归到两位小数：3.3 + 3.4 在 Double 里是 `6.700000000000001`，
     * 而这个数要原样印到统计页上。
     */
    fun totalCredits(courses: List<Course>): Double =
        roundCredits(creditsByCourse(courses).mapNotNull { it.credit }.sum())

    /**
     * 每个星期几的上课分钟负载 / 门数 / 空档。返回固定 7 项，下标 0 = 周一。
     *
     * @param semester 只提供学期总周数（裁掉越界周次、当平均值分母）；
     *   为 null 时周数取课程数据里出现过的最大周次
     * @param timeSlots 节次表，为空时退回 [TimeSlotProfile.DEFAULT]（全应用统一兜底口径）
     */
    fun dayLoads(
        courses: List<Course>,
        semester: Semester?,
        timeSlots: List<TimeSlot>,
    ): List<DayLoad> {
        val weeks = weekAxisLength(courses, semester)
        val slotTimes = slotTimes(timeSlots)
        val slotMinutes = slotMinutes(slotTimes)
        val periodsPerDay = slotTimes.size

        // [day][week] → 那一周那一天的上课分钟数。必须逐周摊：
        // 一门只上 1-8 周的课不该出现在第 9 周之后，而单周峰值也只有这样才算得出来
        // （两天的片段周次不重叠时，把学期总量当峰值会虚高）。
        val minutesByDayAndWeek = Array(TOTAL_DAYS + 1) { LongArray(weeks + 1) }
        val groupsByDay = Array(TOTAL_DAYS + 1) { mutableSetOf<String>() }
        val occupiedByDay = Array(TOTAL_DAYS + 1) { mutableSetOf<Int>() }

        for (course in courses) {
            val day = course.dayOfWeek
            // 脏星期序号（教务确实返回过 0 / 8）跳过这条：下面全是按下标取值的数组，
            // 让它进去就是越界，而统计页崩一次比少算一门课严重得多
            if (day !in 1..TOTAL_DAYS) continue
            // 越出学期范围的周次一律不计：按学期边界裁掉，比让一条脏周次把分母撑大安全
            val inRangeWeeks = course.weeks.asSequence().filter { it in 1..weeks }.distinct().toList()
            if (inRangeWeeks.isEmpty()) continue
            val minutesPerMeeting = course.periods.distinct().sumOf { slotMinutes[it] ?: 0L }
            inRangeWeeks.forEach { week -> minutesByDayAndWeek[day][week] += minutesPerMeeting }
            groupsByDay[day] += courseGroupKey(course)
            occupiedByDay[day] += course.periods.filter { slotTimes.containsKey(it) }
        }

        return (1..TOTAL_DAYS).map { day ->
            val perWeek = minutesByDayAndWeek[day]
            DayLoad(
                dayOfWeek = day,
                averageMinutes = perWeek.sum() / weeks,
                peakMinutes = perWeek.maxOrNull() ?: 0L,
                courseCount = groupsByDay[day].size,
                freePeriodCount = (periodsPerDay - occupiedByDay[day].size).coerceAtLeast(0),
            )
        }
    }

    /**
     * 参与计算的周数：学期总周数优先，没有学期行时用数据里出现过的最大周次。
     * 两者都钳到 [CourseConstraints.MAX_TOTAL_WEEKS]，且恒 ≥ 1（下面要拿它当除数）。
     *
     * T51 起对同目录的图表内核开放（[CourseWeekSpans.board] /
     * [WeekFreeGrid.gridOf] / [WeeklyLoadTrend.trendOf]）：横轴长度一旦有第二套算法，
     * 三张图和统计页那句"共 N 周"就会各说各话。
     */
    fun weekAxisLength(courses: List<Course>, semester: Semester?): Int {
        val weeks = semester?.totalWeeks?.takeIf { it > 0 }
            ?: courses.asSequence().flatMap { it.weeks.asSequence() }.maxOrNull()
            ?: 1
        return weeks.coerceIn(1, CourseConstraints.MAX_TOTAL_WEEKS)
    }

    /** 平均分钟数最大的一天（并列取星期序号最小的）；整周都没有课时返回 null */
    fun busiestDay(loads: List<DayLoad>): DayLoad? = pickExtreme(loads, busiest = true)

    /** 有课的几天里平均分钟数最小的一天（并列同样取星期序号最小的）；全空返回 null */
    fun quietestBusyDay(loads: List<DayLoad>): DayLoad? = pickExtreme(loads, busiest = false)

    /**
     * 全学期都不落课的 (星期 × 节次) 格数：某格只要在任意一周有过课就不算空。
     * 与 [dayLoads] 同口径，等于各天 [DayLoad.freePeriodCount] 之和。
     */
    fun freeSlotCount(
        courses: List<Course>,
        semester: Semester?,
        timeSlots: List<TimeSlot>,
    ): Int = dayLoads(courses, semester, timeSlots).sumOf { it.freePeriodCount }

    /** 一把算完：统计页只需要调这一个 */
    fun summarize(
        courses: List<Course>,
        semester: Semester?,
        timeSlots: List<TimeSlot>,
    ): SemesterSummary {
        val loads = dayLoads(courses, semester, timeSlots)
        val credits = creditsByCourse(courses)
        val known = credits.count { it.credit != null }
        return SemesterSummary(
            fragmentCount = courses.size,
            courseCount = credits.size,
            totalCredits = roundCredits(credits.mapNotNull { it.credit }.sum()),
            creditsKnown = known,
            creditsMissing = credits.size - known,
            perCourse = credits,
            dayLoads = loads,
            busiestDayOfWeek = busiestDay(loads)?.dayOfWeek,
            quietestBusyDay = quietestBusyDay(loads)?.dayOfWeek,
            periodsPerDay = slotTimes(timeSlots).size,
            freeSlotCount = loads.sumOf { it.freePeriodCount },
            weekCount = weekAxisLength(courses, semester),
            semesterAnchored = semester?.startLocalDate != null,
        )
    }

    // ---- 内部实现 ----

    /**
     * 作息表 → 节次号 → (上课, 下课)。
     * 时间解析不出来的行整个丢掉：它既排不进时间轴，也不该在空档分母里占一格。
     *
     * 对 [WeekFreeGrid] 开放（`internal` 而非 `private`）：热力格的"行轴"就是这份
     * 节次表，另写一套取表逻辑会多出"哪张图用了默认作息"的口径分岔。
     */
    internal fun slotTimes(timeSlots: List<TimeSlot>): Map<Int, Pair<LocalTime, LocalTime>> =
        timeSlots.ifEmpty { TimeSlotProfile.DEFAULT }.toStartEndTimes()

    /** 节次号 → 该节分钟数。`endTime <= startTime` 的节次丢掉（见 [DayLoad.isFree] 的说明） */
    private fun slotMinutes(slotTimes: Map<Int, Pair<LocalTime, LocalTime>>): Map<Int, Long> =
        slotTimes.mapNotNull { (number, span) ->
            val minutes = ChronoUnit.MINUTES.between(span.first, span.second)
            // 负/零时长当作没有这一节：留着它会让那天的负载凭空变小
            if (minutes > 0L) number to minutes else null
        }.toMap()

    /** 并列时保留星期序号更小的一天，结果不随传入顺序变化 */
    private fun pickExtreme(loads: List<DayLoad>, busiest: Boolean): DayLoad? =
        loads.filter { it.peakMinutes > 0L }.reduceOrNull { best, candidate ->
            val better = if (busiest) {
                candidate.averageMinutes > best.averageMinutes
            } else {
                candidate.averageMinutes < best.averageMinutes
            }
            val tied = candidate.averageMinutes == best.averageMinutes &&
                candidate.dayOfWeek < best.dayOfWeek
            if (better || tied) candidate else best
        }

    private fun roundCredits(value: Double): Double = round(value * 100.0) / 100.0
}
