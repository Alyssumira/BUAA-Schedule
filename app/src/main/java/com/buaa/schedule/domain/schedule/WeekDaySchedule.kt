package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.teacherOrNull

/**
 * 「第 N 周（这一周的某一天）实际排了哪几门课」的判据内核（T82 的钻取）。
 *
 * 纯 Kotlin：**零 android import、零时钟读取**（仓库硬口径，与
 * [CourseWeekSpans] / [WeekFreeGrid] / [WeeklyLoadTrend] 同一族）——
 * 选中的周次是**调用点的界面事实**，由参数递进来，本文件既不知道"今天几号"，
 * 也不猜"用户大概想看这一周"。
 *
 * ## 它补的是哪半句话
 *
 * [SemesterStats.dayLoads] 交出来的 `averageMinutes / courseCount` 是**全学期平均与并集**：
 * "周三平均两小时、这学期一共 4 门不同的课"这句话，对一份前紧后松的课表和对一份
 * 整学期等重的课表给出同一组数字。[WeekFreeGrid] 补的是"这一周这天占了几格"，
 * 它逐格判定但**只到格子为止**——那一格里是哪门课、第几节到第几节、在哪间教室，
 * 热力格不说话。这一件就是接着往下问的那一步：**实排明细**。
 *
 * ## 三条钉死的口径
 *
 * 1. **分母只有一份**：横轴周数走 [SemesterStats.weekAxisLength]，与三张图、
 *    统计页那句「共 N 周」同一个数。另造一套周轴，钻取里的"第 12 周"就会和趋势图
 *    的"第 12 周"不是同一周（T51 的账）。
 * 2. **逐周判，不做并集**：一条片段只有当 `week in it.weeks` 时才算在这周里，
 *    与 [WeekFreeGrid.gridOf] 的 `active` 同一句话。一门 1-8 周的课在第 12 周
 *    不该出现在明细里 —— 出现了就是在谎报一堂不存在的课。
 * 3. **"第几节到第几节"这句话不在这里写**：节次的切段与措辞是
 *    [com.buaa.schedule.domain.model.periodLabelOf] 那一份的活儿（相邻节次隔了午饭
 *    就不算一段，`[5,6]` → 两段）。这里只交片段本身，界面拿 [Meeting.course] 去调它 ——
 *    在本文件再切一遍段，就是第二套"什么算连堂"。
 *
 * 「一门课」的定义仍然只有 [SemesterStats.courseGroupKey] 那一份：同门课的两个片段
 * （理论 + 实验）撞在同一天，[DaySchedule.meetings] 是两条（那是两堂课），
 * 而 [DaySchedule.courseCount] 是 1（那是一门课）—— 两个数各自回答各自的问题，
 * 谁去界面里再 groupBy 一遍就成了第二套真相。
 *
 * 公开函数对以下输入都不抛异常、不返回 null：空课程列表、`week` 越界、
 * 脏星期序号（0 / 8）、`periods` 为空、节次不在作息表里、`semester == null`。
 */
object WeekDaySchedule {

    /**
     * 一条实排 = 这一周这一天上的**一个片段**（不是"一门课"，见文件路口径 3）。
     *
     * @param course 那条排课片段本身：界面取颜色与原始名，"第几节到第几节"也取它
     *   （交给 [com.buaa.schedule.domain.model.periodLabelOf] 那一份连堂判据去切段，
     *   这里**不**另存一份节次段 —— 那就是第二套"什么算连堂"）
     * @param label 显示名（[Course.displayName]：别名优先），界面不许自己再判一次别名
     * @param groupKey 课程身份键（[SemesterStats.courseGroupKey]），门数按它去重
     * @param dayOfWeek ISO 星期序号，1 = 周一 … 7 = 周日
     * @param periodCount 占几节（去重后的节次数），与 [minutes] 一样不按段数算
     * @param minutes 这一堂的分钟数；节次在作息表里查不到时贡献 0
     * @param minutesKnown 所有节次都在作息表里、[minutes] 才可信
     * @param periodsKnown 这条片段给了节次（false = 空节次，"第几节"那一栏整个不出场，
     *   也不许写"第节"，见 T26 那笔账）
     * @param location 教室（null = 教务没给，界面缺项整段跳过）
     * @param teacher 教师；哨兵值 `"未知教师"` 已被折成 null（与详情 Sheet、统计页明细同一口径）
     * @param campus 校区
     */
    data class Meeting(
        val course: Course,
        val label: String,
        val groupKey: String,
        val dayOfWeek: Int,
        val periodCount: Int,
        val minutes: Long,
        val minutesKnown: Boolean,
        val periodsKnown: Boolean,
        val location: String?,
        val teacher: String?,
        val campus: String?,
    )

    /**
     * 一天（星期序号）的实排。
     *
     * @param meetings 按最早节次升序、同节次按课名，**结果与传入顺序无关**
     */
    data class DaySchedule(
        val dayOfWeek: Int,
        val meetings: List<Meeting>,
    ) {
        /** 这一周这天上了几堂课：按片段数，理论课 + 实验课撞在周三是 2 */
        val meetingCount: Int get() = meetings.size

        /** 这一周这天涉及几门课：按 [groupKey] 去重，同门课两段只算一门 */
        val courseCount: Int get() = meetings.map { it.groupKey }.distinct().size

        /** 这一天占几节 */
        val periodCount: Int get() = meetings.sumOf { it.periodCount }

        /** 这一天上多少分钟 */
        val minutes: Long get() = meetings.sumOf { it.minutes }

        val isEmpty: Boolean get() = meetings.isEmpty()
    }

    /**
     * [scheduleOf] 的结果。
     *
     * @param week 判定的那一周（就是调用点递进来的那枚，不夹取、不改写）
     * @param totalWeeks 分母，与 [SemesterStats.weekAxisLength] 同一口径
     * @param outOfRange [week] 不在 1..[totalWeeks] 里：选中值在换学期之后会留在旧的
     *   周号上，此时**不能**画成"这一周没课"（那是假话），也不能抛
     * @param days 恒 7 项，下标 0 = 周一；没课的那些天给一条空的 [DaySchedule]，
     *   这样"周几有课"是下标的事，界面不许按 meetings 非空反推星期序号
     * @param dayOfWeeksWithCourses 有课的那些天的 ISO 星期序号（升序）：
     *   界面用它决定"整天没课"这句话对哪几天说
     */
    data class WeekSchedule(
        val week: Int,
        val totalWeeks: Int,
        val outOfRange: Boolean,
        val days: List<DaySchedule>,
    ) {
        /** 这一周一共几堂课（片段数） */
        val meetingCount: Int get() = days.sumOf { it.meetingCount }

        /** 这一周几门课：跨天也按 [Meeting.groupKey] 去重（周一和周三都上是一门） */
        val courseCount: Int get() = days.flatMap { it.meetings }.map { it.groupKey }.distinct().size

        val totalMinutes: Long get() = days.sumOf { it.minutes }

        /**
         * 有几堂课的时长**不可信**（节次号在作息表里查不到）。
         *
         * 与 [WeeklyLoadTrend.Trend.unschedulablePeriodCellCount] 同一族的那件事：
         * 作息表被裁剪时这些课贡献 0 分钟，"总时长"就偏低。偏低多少不知道，
         * 但"有几堂没算进来"是数得清的，界面得能说。
         */
        val minutesUnknownCount: Int get() = days.flatMap { it.meetings }.count { !it.minutesKnown }

        /** 这一周有几天有课 */
        val busyDayCount: Int get() = days.count { it.meetings.isNotEmpty() }

        val dayOfWeeksWithCourses: List<Int> get() =
            days.filter { it.meetings.isNotEmpty() }.map { it.dayOfWeek }

        /**
         * 这一周没有任何一堂课**且**不是"周号越界"：越界那一档不是空周，是问错了周，
         * 两者必须分得开，否则换学期的一瞬间界面会把"第 25 周"报成"第 25 周没课"。
         */
        val isEmptyWeek: Boolean get() = !outOfRange && meetingCount == 0
    }

    /**
     * 逐周判定 + 逐片段展开。
     *
     * @param week 选中的教学周；越界不抛，见 [WeekSchedule.outOfRange]
     * @param timeSlots 节次表，为空时退回 `TimeSlotProfile.DEFAULT`（全应用同一兜底口径，
     *   走的是 [SemesterStats.slotMinutes]，不在这里另写一遍取表逻辑）
     */
    fun scheduleOf(
        courses: List<Course>,
        semester: Semester?,
        timeSlots: List<TimeSlot>,
        week: Int,
    ): WeekSchedule {
        val totalWeeks = SemesterStats.weekAxisLength(courses, semester)
        // 节次 → 分钟数走 SemesterStats 那一份：「哪一节多少分钟」在这里再算一遍，
        // 就会多出"哪张图用了另一套作息"的口径分岔
        val slotMinutes = SemesterStats.slotMinutes(timeSlots)
        val inRange = week in 1..totalWeeks
        // 越界那一档连扫都不扫：天数与节次表都还在（恒 7 项），但一堂课都不该画
        val active = inRange && courses.isNotEmpty()

        val dayLists = Array(SemesterStats.TOTAL_DAYS) { mutableListOf<Meeting>() }
        if (active) {
            for (course in courses) {
                val day = course.dayOfWeek
                if (day !in 1..SemesterStats.TOTAL_DAYS) continue
                if (week !in course.weeks) continue
                val distinctPeriods = course.periods.distinct().sorted()
                dayLists[day - 1] += Meeting(
                    course = course,
                    label = course.displayName,
                    groupKey = SemesterStats.courseGroupKey(course),
                    dayOfWeek = day,
                    periodCount = distinctPeriods.size,
                    minutes = distinctPeriods.sumOf { slotMinutes[it] ?: 0L },
                    // 「查不到」与「查到但就是 0 分钟」不是一件事：前者这一条时长不可信。
                    // 一节都没有也归到不可信 —— 0 分钟在这里是"没得说"，不是"这堂课 0 分钟"
                    minutesKnown = distinctPeriods.isNotEmpty() &&
                        distinctPeriods.all { slotMinutes.containsKey(it) },
                    periodsKnown = distinctPeriods.isNotEmpty(),
                    location = course.location?.trim()?.takeIf { it.isNotEmpty() },
                    teacher = teacherOrNull(course.teacher),
                    campus = course.campus?.trim()?.takeIf { it.isNotEmpty() },
                )
            }
        }
        val days = (1..SemesterStats.TOTAL_DAYS).map { day ->
            DaySchedule(
                dayOfWeek = day,
                meetings = dayLists[day - 1].sortedWith(
                    // 排序键走 periods.minOrNull()，不写 course.startPeriod：后者空表兜底成 1
                    // （Course.kt:60 那段账），拿它排序没问题、当"第 1 节有课"用不行，
                    // 这里要的是"空节次的那条排到最后"，minOrNull 直接就是这个意思
                    compareBy<Meeting> { it.course.periods.minOrNull() ?: Int.MAX_VALUE }
                        .thenBy { it.label }
                        .thenBy { it.course.id },
                ),
            )
        }
        return WeekSchedule(
            week = week,
            totalWeeks = totalWeeks,
            outOfRange = !inRange,
            days = days,
        )
    }
}
