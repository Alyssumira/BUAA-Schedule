package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.startLocalDate
import java.time.LocalDate

/**
 * 教学周序号 → 那一周的日期。
 *
 * [WeekCalculator] 只有反方向的换算（日期 → 它所在自然周的周一），翻周时要的是
 * 「第 N 周的周一是哪天」——此前这条算式在仓里各写了一份（周课表表头、桌面组件的
 * weekRange），改学期锚点口径时要挨个找。这里收成一份，锚点归一仍然只走
 * [WeekCalculator.mondayOf]，本文件不新增第二套锚点算法。
 */
object SemesterWeekDates {

    /**
     * 第 [week] 周的周一。
     *
     * @param semesterStart 开学日期；非周一时先归一（否则整张表每周错位一到六天）
     * @param week 教学周序号，1 基；小于 1 表示没有这一周，返回 null 由调用方降级
     */
    fun mondayOf(semesterStart: LocalDate, week: Int): LocalDate? =
        if (week < 1) null else WeekCalculator.mondayOf(semesterStart).plusWeeks((week - 1).toLong())

    /** 学期或缺周号时返回 null：调用方一律退回复读今天的日期，不猜。 */
    fun mondayOf(semester: Semester?, week: Int?): LocalDate? {
        val start = semester?.startLocalDate ?: return null
        val target = week ?: return null
        return mondayOf(start, target)
    }

    /** 第 [week] 周的周一与周日；算不出来时 null */
    fun spanOf(semester: Semester?, week: Int?): Pair<LocalDate, LocalDate>? {
        val monday = mondayOf(semester, week) ?: return null
        return monday to monday.plusDays(6)
    }
}
