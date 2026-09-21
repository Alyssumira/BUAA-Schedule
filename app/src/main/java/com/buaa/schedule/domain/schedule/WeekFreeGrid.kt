package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot

/**
 * 一周空闲热力格（星期 × 节次矩阵）的判据内核。
 *
 * 纯 Kotlin：没有 Compose、没有 Android 依赖，可直接 JVM 单测。
 * "现在是第几周"由调用方当参数传进来（`ScheduleUiState.currentWeek`），
 * 本文件不读时钟 —— 所以"画的是这一周的真实状态"这件事是可测的。
 *
 * 它补的是 [SemesterStats.dayLoads] 说不清的那半句话：那边的 `freePeriodCount`
 * 是**全学期并集**口径（某格只要在任意一周有过课就不算空），于是"周三 9-10 节
 * 全学期只出现过一次"和"周三 9-10 节每周都排满"是同一个数字。
 * 热力格按**指定教学周**逐格判定，一眼能看出的是"我这周三下午是不是真的空"。
 *
 * ## 三种"没有课"必须分得开
 *
 * - 该周真的没排：格子是空的，这是好消息；
 * - 一门 1-8 周的课在第 12 周：它**不该**占格子（[occupied] 逐周判，不做全学期并集）；
 * - 当前周拿不到（假期 / 学期原点缺失）：整张图退到"全学期任意一周"的并集口径，
 *   并由 [Grid.weekUnresolved] 让界面把标题改成"全学期占用"——
 *   没有原点时画一张看起来像"这周很空"的图，比不画更糟。
 */
object WeekFreeGrid {

    /**
     * 一行 = 一个节次（矩阵的一横排），[occupiedDays] 下标 0 = 周一。
     *
     * @param period 节次号（作息表的 `number`，不是分钟数）
     * @param occupiedDays 7 项，true = 这一周那一天的这一节有课
     * @param freeAcrossWeek 整周七天都没课
     */
    data class Row(
        val period: Int,
        val occupiedDays: List<Boolean>,
    ) {
        val freeAcrossWeek: Boolean get() = occupiedDays.none { it }
        val occupiedDayCount: Int get() = occupiedDays.count { it }
    }

    /**
     * [gridOf] 的结果。
     *
     * @param week 判定的教学周；null 表示原点缺失，此时 [rows] 是全学期并集口径
     * @param weekUnresolved 同 `week == null`，单独给界面一个显式开关（读代码时不必猜）
     * @param rows 一条一项，顺序与作息表节次号升序一致；作息表为空时为空表
     * @param totalWeeks 横轴周数，与 [SemesterStats.weekAxisLength] 同一口径
     * @param occupiedByDay 7 项，下标 0 = 周一：该天在这一周占了几节
     * @param freeDayOfWeek 该周最空的一天（ISO 1..7，并列取星期序号最小的）；
     *   七天占用一模一样（全空 / 全满）时为 null —— 那时候"最空的是周几"是个废话
     * @param freePeriods 整周都不落课的节次号（"这个时段全周都空"）
     * @param occupiedCellCount 有课的格子数
     * @param offProfilePeriodCount 课确实占了、但节次号不在作息表里的**格子数**
     *   （作息表被裁剪时会出现，界面要如实说"另有 N 格未画出"而不是当作没有）
     * @param emptyWeek 这一周一节课都没有（并集口径下整学期都没有课也算）
     */
    data class Grid(
        val week: Int?,
        val weekUnresolved: Boolean,
        val rows: List<Row>,
        val totalWeeks: Int,
        val occupiedByDay: List<Int>,
        val freeDayOfWeek: Int?,
        val freePeriods: List<Int>,
        val occupiedCellCount: Int,
        val offProfilePeriodCount: Int,
    ) {
        val cellCount: Int get() = rows.size * SemesterStats.TOTAL_DAYS
        val freeCellCount: Int get() = cellCount - occupiedCellCount
        val emptyWeek: Boolean get() = occupiedCellCount == 0 && offProfilePeriodCount == 0
    }

    /**
     * 逐格判定。
     *
     * @param week 判定的教学周；传 null（或越出 1..totalWeeks）时按全学期并集口径出图，
     *   并用 [Grid.weekUnresolved] 告知界面
     * @param timeSlots 节次表；为空时退回 `TimeSlotProfile.DEFAULT`（与全应用同一兜底口径）
     */
    fun gridOf(
        courses: List<Course>,
        semester: Semester?,
        timeSlots: List<TimeSlot>,
        week: Int?,
    ): Grid {
        val totalWeeks = SemesterStats.weekAxisLength(courses, semester)
        val slotTimes = SemesterStats.slotTimes(timeSlots)
        val periods = slotTimes.keys.sorted()
        val resolvedWeek = week?.takeIf { it in 1..totalWeeks }

        // occupied[星期 - 1][节次号]：按格存，最后一次性折成行/列汇总，
        // 免得"哪些天有课"这件事在两个地方各判一遍
        val occupied = Array(SemesterStats.TOTAL_DAYS) { mutableSetOf<Int>() }
        val offProfile = Array(SemesterStats.TOTAL_DAYS) { mutableSetOf<Int>() }
        for (course in courses) {
            val day = course.dayOfWeek
            if (day !in 1..SemesterStats.TOTAL_DAYS) continue
            // 逐周判：这是整张图的立身之本。1-8 周的课在第 12 周不该亮着；
            // 拿不到周次时退成"任意一周有课"，那是并集口径，界面会照实说
            val active = resolvedWeek != null && resolvedWeek in course.weeks
            val unionModeActive = resolvedWeek == null && course.weeks.any { it in 1..totalWeeks }
            if (!active && !unionModeActive) continue
            val distinctPeriods = course.periods.distinct()
            distinctPeriods.forEach { period ->
                // 作息表被裁剪时确实会出现"有课但没有那一节"的格子：另开一份集合数着，
                // 两门课撞在同一格也只算一格（界面说的是"另有 N 格未画出"）
                if (period in slotTimes) occupied[day - 1].add(period) else offProfile[day - 1].add(period)
            }
        }

        val rows = periods.map { period ->
            Row(
                period = period,
                occupiedDays = (1..SemesterStats.TOTAL_DAYS).map { occupied[it - 1].contains(period) },
            )
        }
        val occupiedByDay = (1..SemesterStats.TOTAL_DAYS).map { day -> occupied[day - 1].size }

        return Grid(
            week = resolvedWeek,
            weekUnresolved = resolvedWeek == null,
            rows = rows,
            totalWeeks = totalWeeks,
            occupiedByDay = occupiedByDay,
            freeDayOfWeek = emptiestDay(occupiedByDay),
            freePeriods = rows.filter { it.freeAcrossWeek }.map { it.period },
            occupiedCellCount = occupiedByDay.sum(),
            offProfilePeriodCount = offProfile.sumOf { it.size },
        )
    }

    /**
     * 该周最空的一天：占用节数最少的那天，并列取星期序号最小的（与
     * [SemesterStats.busiestDay] 同一条并列规则）。
     * 七天占用完全一样时不给结论（min == max）—— 全空、全满、以及"每天都排得
     * 一样满"，说"最空的是周一"都没有信息量，全空那档更是白送一句废话。
     */
    private fun emptiestDay(occupiedByDay: List<Int>): Int? {
        val min = occupiedByDay.minOrNull() ?: return null
        val max = occupiedByDay.maxOrNull() ?: return null
        if (min == max) return null
        return occupiedByDay.indexOfFirst { it == min } + 1
    }
}
