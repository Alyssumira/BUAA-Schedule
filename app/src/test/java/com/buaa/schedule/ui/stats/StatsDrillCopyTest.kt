package com.buaa.schedule.ui.stats

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.schedule.WeekDaySchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「按周细看」那几句措辞的判据（T82）。
 *
 * 表驱动钉的是"话说得对不对"，尤其是三个"课数"各说各的那一档：
 * 这一周这天几堂课、几门课，与「每周负载」卡片上那句全学期并集的门数
 * 天生不等，所以措辞必须自带周次限定、且不许照抄那一句。
 */
class StatsDrillCopyTest {

    private val slots = listOf(
        TimeSlot(number = 1, startTime = "08:00", endTime = "08:45"),
        TimeSlot(number = 2, startTime = "08:55", endTime = "09:40"),
        TimeSlot(number = 3, startTime = "10:00", endTime = "10:45"),
        TimeSlot(number = 4, startTime = "10:55", endTime = "11:40"),
    )

    private fun meetingOf(
        name: String,
        periods: List<Int>,
        group: String = name,
        location: String? = null,
        teacher: String? = null,
        day: Int = 1,
        weeks: List<Int> = listOf(1),
    ): WeekDaySchedule.Meeting {
        val course = Course(
            name = name,
            teacher = teacher,
            location = location,
            dayOfWeek = day,
            periods = periods,
            weeks = weeks,
            sourceGroupKey = group,
            semesterCode = "2026-2027-1",
        )
        return WeekDaySchedule.scheduleOf(listOf(course), null, slots, week = 1)
            .days[day - 1]
            .meetings
            .single()
    }

    private fun dayOf(vararg meetings: WeekDaySchedule.Meeting) =
        WeekDaySchedule.DaySchedule(dayOfWeek = 1, meetings = meetings.toList())

    // ---- 收起那一档 ----

    @Test
    fun collapsedHintNamesTheCurrentWeekOnlyWhenItIsKnown() {
        val cases = listOf(
            7 to "挑一周，看那一周具体排了什么课（现在是第 7 周）",
            // 原点缺失 / 假期：不硬说"现在是第几周"，但这一行还得能点开
            null to "挑一周，看那一周具体排了什么课",
        )
        cases.forEach { (current, expected) -> assertEquals(expected, drillCollapsedHint(current)) }
    }

    // ---- 一条实排 ----

    @Test
    fun meetingLineDropsEveryAbsentPiece() {
        val cases = listOf<Triple<WeekDaySchedule.Meeting, String, String>>(
            Triple(
                meetingOf("高等数学", listOf(3, 4), location = "J3-101", teacher = "张三"),
                "第3-4节 · J3-101 · 张三", "三项齐全",
            ),
            Triple(
                meetingOf("高等数学", listOf(3, 4)),
                "第3-4节", "只有节次：分隔符不能悬空",
            ),
            Triple(
                meetingOf("高等数学", listOf(3, 4), location = "J3-101"),
                "第3-4节 · J3-101", "缺教师",
            ),
            Triple(
                meetingOf("自习", emptyList(), location = "M2-201"),
                "M2-201", "空节次：不许写成「第节」，教室照说",
            ),
            Triple(
                meetingOf("自习", emptyList()),
                "", "什么都没有就是空串，整行由界面跳过",
            ),
            Triple(
                meetingOf("实验", listOf(1, 2), teacher = "未知教师"),
                "第1-2节", "哨兵教师不成为一段（内核已折成 null）",
            ),
        )
        cases.forEach { (meeting, expected, why) ->
            assertEquals(why, expected, drillMeetingLine(meeting, slots))
        }
    }

    @Test
    fun meetingLineUsesTheSharedPeriodSegmentation() {
        // 3、4 节连着；1、2 与 4 之间空了一节 → 两段，措辞由 periodLabelOf 那份决定
        val meeting = meetingOf("高等数学", listOf(1, 2, 4))
        assertEquals("第1-2,4节", drillMeetingLine(meeting, slots))
    }

    // ---- 天与周的收尾那句 ----

    @Test
    fun dayNoteSaysMeetingCountAndOnlyTrustworthyMinutes() {
        val known = meetingOf("高等数学", listOf(3, 4))               // 90 分钟
        val morning = meetingOf("大学物理", listOf(1, 2))              // 90 分钟
        val offProfile = meetingOf("讲座", listOf(3, 9))              // 第 9 节不在作息表里
        val cases = listOf<Pair<WeekDaySchedule.DaySchedule, String>>(
            dayOf(known) to "1 堂课 · 1 小时 30 分钟",
            dayOf(known, morning) to "2 堂课 · 3 小时",
            dayOf(offProfile) to "1 堂课",
            dayOf(known, offProfile) to "2 堂课",
            dayOf() to "0 堂课",
        )
        cases.forEach { (day, expected) -> assertEquals(expected, drillDayNote(day)) }
    }

    @Test
    fun dayTitleCarriesTheWeekday() {
        val day = WeekDaySchedule.DaySchedule(dayOfWeek = 5, meetings = listOf(meetingOf("体育课", listOf(1))))
        assertEquals("周五 · 1 堂课 · 45 分钟", drillDayTitle(day))
    }

    @Test
    fun sameCourseNoteOnlySpeaksWhenTwoFragmentsCollideOnTheSameDay() {
        val theory = meetingOf("高等数学", listOf(1, 2), group = "G1")
        val lab = meetingOf("高等数学", listOf(3, 4), group = "G1")
        val other = meetingOf("大学物理", listOf(3, 4), group = "G2")
        val cases = listOf<Triple<WeekDaySchedule.DaySchedule, String?, String>>(
            Triple(dayOf(theory, lab), "其中有同一门课排成的 1 段", "同门课两段"),
            Triple(dayOf(theory, lab, other), "其中有同一门课排成的 1 段", "两段 + 另一门"),
            Triple(dayOf(theory, other), null, "各一段：不说这句"),
            Triple(dayOf(), null, "空天：不说这句"),
        )
        cases.forEach { (day, expected, why) -> assertEquals(why, expected, drillSameCourseNote(day)) }
    }

    @Test
    fun weekNoteSkipsTheCourseCountWhenItEqualsTheMeetingCount() {
        fun fragment(name: String, day: Int, periods: List<Int>, group: String) = Course(
            name = name,
            dayOfWeek = day,
            periods = periods,
            weeks = listOf(1),
            sourceGroupKey = group,
            semesterCode = "2026-2027-1",
        )
        val twoCourses = WeekDaySchedule.scheduleOf(
            listOf(
                fragment("高等数学", 1, listOf(1, 2), "G1"),
                fragment("高等数学", 2, listOf(3, 4), "G1"),
                fragment("大学物理", 3, listOf(1), "G2"),
            ),
            semester(16),
            slots,
            week = 1,
        )
        assertEquals("3 天有课 · 3 堂课 · 2 门课", drillWeekNote(twoCourses))

        val oneEach = WeekDaySchedule.scheduleOf(
            listOf(
                fragment("高等数学", 1, listOf(1, 2), "G1"),
                fragment("大学物理", 2, listOf(3, 4), "G2"),
            ),
            semester(16),
            slots,
            week = 1,
        )
        assertEquals("两堂课就是两门课时，不重复报一遍门数", "2 天有课 · 2 堂课", drillWeekNote(oneEach))
    }

    // ---- 三种"什么都没有" ----

    @Test
    fun emptyNoteSeparatesNoClassFromWrongWeek() {
        val cases = listOf<Triple<WeekDaySchedule.WeekSchedule, String?, String>>(
            Triple(
                WeekDaySchedule.scheduleOf(
                    listOf(Course(name = "课", dayOfWeek = 1, periods = listOf(1), weeks = listOf(1))),
                    semester(16),
                    slots,
                    week = 2,
                ),
                "第 2 周没有排课",
                "课上完第 1 周就没了：第 2 周在学期里、但真的没课",
            ),
            Triple(
                WeekDaySchedule.scheduleOf(
                    listOf(Course(name = "课", dayOfWeek = 1, periods = listOf(1), weeks = listOf(1))),
                    semester(16),
                    slots,
                    week = 25,
                ),
                "第 25 周不在这学期里，这学期共 16 周",
                "换学期之后旧周号还挂着：不许说成没课",
            ),
            Triple(
                WeekDaySchedule.scheduleOf(
                    listOf(Course(name = "课", dayOfWeek = 1, periods = listOf(3, 4), weeks = listOf(1))),
                    null, slots, week = 1,
                ),
                null,
                "有课就整段不出场",
            ),
        )
        cases.forEach { (schedule, expected, why) -> assertEquals(why, expected, drillEmptyNote(schedule)) }
    }

    private fun semester(weeks: Int) = Semester(
        termCode = "2026-2027-1",
        termName = "2026 秋季学期",
        startDate = "2026-09-07",
        totalWeeks = weeks,
    )
}
