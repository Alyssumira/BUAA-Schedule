package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WeekDaySchedule] 的判据口径（统计页「按周细看」的内核，T82）。
 *
 * 这个内核存在的理由就是"别把全学期当这一周"，所以主钉的是**逐周判定**：
 * 一门 1-8 周的课在第 12 周不许出现在明细里。其余几档守着退路：
 * 周号越界不许被说成"这周没课"、同门课两段撞同一天要能同时说出"2 堂课 / 1 门课"、
 * 学期没锚定与作息表缺节次都不许改变事实或抛异常。
 *
 * 全部跑在 JVM 上：内核零 android import、零时钟读取，"现在是第几周"由调用点当参数递
 * （这里就是那个调用点，每一档都自己写死周号）。
 */
class WeekDayScheduleTest {

    /** 两节的课每节 45 分钟；第 9、10 节故意留空，用来钉"作息表查不到"那一档 */
    private val slots = listOf(
        TimeSlot(number = 1, startTime = "08:00", endTime = "08:45"),
        TimeSlot(number = 2, startTime = "08:55", endTime = "09:40"),
        TimeSlot(number = 3, startTime = "10:00", endTime = "10:45"),
        TimeSlot(number = 4, startTime = "10:55", endTime = "11:40"),
        TimeSlot(number = 5, startTime = "14:00", endTime = "14:45"),
    )

    private fun semester(weeks: Int, startDate: String) = Semester(
        termCode = "2026-2027-1",
        termName = "2026 秋季学期",
        startDate = startDate,
        totalWeeks = weeks,
    )

    private fun course(
        name: String,
        day: Int,
        periods: List<Int>,
        weeks: List<Int>,
        group: String? = null,
        location: String? = null,
        teacher: String? = null,
        id: Long = 0L,
        alias: String? = null,
    ) = Course(
        id = id,
        name = name,
        alias = alias,
        teacher = teacher,
        location = location,
        dayOfWeek = day,
        periods = periods,
        weeks = weeks,
        sourceGroupKey = group,
        semesterCode = "2026-2027-1",
    )

    /** 周一 3-4 节、整学期都上的那门课，是本文件反复用到的样板 */
    private fun monday(weeks: List<Int> = (1..16).toList()) =
        course("高等数学", day = 1, periods = listOf(3, 4), weeks = weeks, location = "J3-101")

    private fun dayOf(schedule: WeekDaySchedule.WeekSchedule, day: Int) = schedule.days[day - 1]

    // ---- ① 分母只有一份：与 SemesterStats.weekAxisLength 同一个数 ----

    @Test
    fun weekAxisComesFromTheSharedDenominator() {
        val cases = listOf<Triple<List<Course>, Semester?, Int>>(
            // 学期行在：按 totalWeeks
            Triple(listOf(monday()), semester(16, "2026-09-07"), 16),
            // 学期行在但课程越出去：还是学期的数（越界周次不参与撑大分母）
            Triple(listOf(monday((1..40).toList())), semester(16, "2026-09-07"), 16),
            // 没有学期行：退回数据里出现过的最大周次
            Triple(listOf(monday((1..12).toList())), null, 12),
            // 一门课都没有：分母至少是 1，不许是 0（下面要拿它当范围）
            Triple(emptyList(), null, 1),
            // 学期写了 0 周：与上面同一档，weekAxisLength 自己钳到 1
            Triple(listOf(monday((1..3).toList())), semester(0, "2026-09-07"), 3),
            // 开学日期非法（未锚定）：分母不变 —— 这一件只看教学周号，不碰日历
            Triple(listOf(monday()), semester(16, "不是个日期"), 16),
        )
        cases.forEach { (courses, term, expected) ->
            val schedule = WeekDaySchedule.scheduleOf(courses, term, slots, week = 1)
            assertEquals(
                "分母与 SemesterStats.weekAxisLength 分岔了：${SemesterStats.weekAxisLength(courses, term)} vs ${schedule.totalWeeks}",
                expected,
                schedule.totalWeeks,
            )
            assertEquals(SemesterStats.weekAxisLength(courses, term), schedule.totalWeeks)
        }
    }

    @Test
    fun singleWeekSemesterStillAnswersWeekOne() {
        val term = semester(1, "2026-09-07")
        val schedule = WeekDaySchedule.scheduleOf(listOf(monday(listOf(1))), term, slots, week = 1)
        assertFalse(schedule.outOfRange)
        assertEquals(1, schedule.meetingCount)
        // 只有 1 周的学期里，第 2 周是"问错了周"，不是"那周没课"
        val next = WeekDaySchedule.scheduleOf(listOf(monday(listOf(1))), term, slots, week = 2)
        assertTrue(next.outOfRange)
        assertFalse("越界不许被折成 emptyWeek", next.isEmptyWeek)
    }

    // ---- ② 学期锚不锚定，明细一个字都不该变（锚定只影响"今天是第几周"）----

    @Test
    fun anchoringDoesNotChangeTheSchedule() {
        val courses = listOf(monday(), monday((1..8).toList()).copy(name = "大学物理", dayOfWeek = 3))
        val anchored = WeekDaySchedule.scheduleOf(courses, semester(16, "2026-09-07"), slots, week = 5)
        val unanchored = WeekDaySchedule.scheduleOf(courses, semester(16, "非法日期"), slots, week = 5)
        val noSemester = WeekDaySchedule.scheduleOf(courses, null, slots, week = 5)
        // SemesterSummary.semesterAnchored 那一枚只说"原点可用不可用"，本内核不读它
        assertEquals(anchored.days.map { it.meetings.map { m -> m.label } },
            unanchored.days.map { it.meetings.map { m -> m.label } })
        assertEquals(anchored.days.map { it.meetings.map { m -> m.label } },
            noSemester.days.map { it.meetings.map { m -> m.label } })
        assertEquals(anchored.meetingCount, noSemester.meetingCount)
        // 但归并后的 summary 会照实标出锚定状态：这一对比钉住"两者互不干涉"
        assertFalse(SemesterStats.summarize(courses, semester(16, "非法日期"), slots).semesterAnchored)
        assertTrue(SemesterStats.summarize(courses, semester(16, "2026-09-07"), slots).semesterAnchored)
    }

    // ---- ③ 逐周判定：跨周次的课只在该在的那几周里出现 ----

    @Test
    fun courseIsListedOnlyInItsOwnWeeks() {
        val courses = listOf(monday((1..8).toList()))
        val inside = WeekDaySchedule.scheduleOf(courses, semester(16, "2026-09-07"), slots, week = 8)
        val outside = WeekDaySchedule.scheduleOf(courses, semester(16, "2026-09-07"), slots, week = 9)
        assertEquals(1, inside.meetingCount)
        assertEquals("高等数学", dayOf(inside, 1).meetings.single().label)
        assertEquals("1-8 周的课在第 9 周不该出现", 0, outside.meetingCount)
        assertTrue("第 9 周是真没课（不是越界）", outside.isEmptyWeek)
        assertFalse(outside.outOfRange)
    }

    @Test
    fun weeksOutsideTheSemesterNeverAppear() {
        // 教务给过越界周次：19 周的学期里冒出第 40 周，那一周根本无从选中
        val courses = listOf(monday(listOf(1, 40)))
        val term = semester(19, "2026-09-07")
        assertEquals(1, WeekDaySchedule.scheduleOf(courses, term, slots, week = 1).meetingCount)
        val at40 = WeekDaySchedule.scheduleOf(courses, term, slots, week = 40)
        assertTrue(at40.outOfRange)
        assertEquals(0, at40.meetingCount)
    }

    // ---- ④ 同门课多片段撞在同一天：两堂课、一门课 ----

    @Test
    fun twoFragmentsOfOneCourseOnSameDay() {
        val theory = course("高等数学", day = 1, periods = listOf(1, 2), weeks = (1..16).toList(), group = "G1")
        val lab = course("高等数学", day = 1, periods = listOf(3, 4), weeks = (1..16).toList(), group = "G1")
        val schedule = WeekDaySchedule.scheduleOf(
            listOf(theory, lab),
            semester(16, "2026-09-07"),
            slots,
            week = 3,
        )
        val monday = dayOf(schedule, 1)
        assertEquals("两条排课片段就是两堂课", 2, monday.meetingCount)
        assertEquals("同一门课（组键相同）只算一门", 1, monday.courseCount)
        assertEquals("整周也只有这一门课（跨天同样按组键去重）", 1, schedule.courseCount)
        assertEquals(
            "按最早节次排：1-2 节在前",
            listOf(listOf(1, 2), listOf(3, 4)).map { it.first() },
            monday.meetings.map { it.course.periods.min() },
        )
        assertEquals(4, monday.periodCount)
        assertEquals(4 * 45L, monday.minutes)
    }

    @Test
    fun sameNameWithoutGroupKeyIsStillOneCourse() {
        // 手动课没有组键：courseGroupKey 退到「学期|课名」，所以同名同周次仍是**一门课**；
        // 换个学期代码就是两门。这里钉的是"去重键只有一份"，不是界面自己 groupBy
        val a = course("线性代数", day = 2, periods = listOf(1), weeks = (1..16).toList())
        val b = a.copy(id = 7L, dayOfWeek = 5)
        val schedule = WeekDaySchedule.scheduleOf(listOf(a, b), semester(16, "2026-09-07"), slots, week = 2)
        assertEquals(2, schedule.meetingCount)
        assertEquals("同名的两段在两天里仍是一门课", 1, schedule.courseCount)
        assertEquals(1, dayOf(schedule, 2).courseCount)
        assertEquals(1, dayOf(schedule, 5).courseCount)
    }

    // ---- ⑤ 天数轴与"点的是没课的那天" ----

    @Test
    fun dayAxisIsAlwaysSevenAndEmptyDaysAreMarked() {
        val schedule = WeekDaySchedule.scheduleOf(listOf(monday()), semester(16, "2026-09-07"), slots, week = 2)
        assertEquals("恒 7 项，界面按星期序号取值", 7, schedule.days.size)
        assertEquals((1..7).toList(), schedule.days.map { it.dayOfWeek })
        assertTrue(dayOf(schedule, 2).isEmpty)
        assertEquals(0, dayOf(schedule, 2).courseCount)
        assertEquals(listOf(1), schedule.dayOfWeeksWithCourses)
        assertEquals(1, schedule.busyDayCount)
    }

    // ---- ⑥ 节次与时长：查不到就说不可信，不猜 ----

    @Test
    fun minutesAndPeriodFacts() {
        val cases = listOf<Triple<Course, Boolean, Long>>(
            // 3、4 节各 45 分钟
            Triple(course("A", 1, listOf(3, 4), (1..16).toList()), true, 90L),
            // 重复节次只算一次（[1,1,3] 不是三节）
            Triple(course("B", 1, listOf(1, 1, 3), (1..16).toList()), true, 90L),
            // 第 9 节不在作息表里：贡献 0，且这条时长不可信
            Triple(course("C", 1, listOf(3, 9), (1..16).toList()), false, 45L),
            // 空节次：没得说，periodsKnown = false
            Triple(course("D", 1, emptyList(), (1..16).toList()), false, 0L),
        )
        cases.forEach { (item, known, minutes) ->
            val meeting = WeekDaySchedule
                .scheduleOf(listOf(item), semester(16, "2026-09-07"), slots, week = 1)
                .days[item.dayOfWeek - 1].meetings.single()
            assertEquals("${item.name} 的分钟数", minutes, meeting.minutes)
            assertEquals("${item.name} 的时长可信度", known, meeting.minutesKnown)
            assertEquals("${item.name} 有没有节次", item.periods.isNotEmpty(), meeting.periodsKnown)
        }
    }

    @Test
    fun emptyPeriodSortsLastAndKeepsItsRow() {
        // 没有节次的课也是用户数据，不许人间蒸发；但它没有"第几节"可说，排到最后
        val noPeriod = course("空节次", day = 1, periods = emptyList(), weeks = (1..16).toList())
        val early = course("早课", day = 1, periods = listOf(1), weeks = (1..16).toList())
        val schedule = WeekDaySchedule.scheduleOf(
            listOf(noPeriod, early),
            semester(16, "2026-09-07"),
            slots,
            week = 1,
        )
        assertEquals(listOf("早课", "空节次"), dayOf(schedule, 1).meetings.map { it.label })
        assertFalse(dayOf(schedule, 1).meetings.last().periodsKnown)
    }

    // ---- ⑦ 脏数据与空输入：不抛、不编 ----

    @Test
    fun dirtyInputNeverThrows() {
        val dirty = listOf(
            course("脏周", day = 0, periods = listOf(1), weeks = (1..16).toList()),
            course("脏周八", day = 8, periods = listOf(1), weeks = (1..16).toList()),
            course("空周次", day = 1, periods = listOf(1), weeks = emptyList()),
        )
        val schedule = WeekDaySchedule.scheduleOf(dirty, semester(1, "2026-09-07"), slots, week = 1)
        assertEquals("星期序号越界的片段整个跳过", 0, schedule.meetingCount)
        assertTrue(schedule.isEmptyWeek)
        // weeks 为空的片段在任何一周都不出现（它没说自己上哪几周）
        val emptyWeeks = WeekDaySchedule.scheduleOf(
            listOf(course("空周次", 1, listOf(1), emptyList())),
            semester(4, "2026-09-07"),
            slots,
            week = 1,
        )
        assertEquals(0, emptyWeeks.meetingCount)
        // 一门课、一个节次都没有：不抛，7 天全空
        val nothing = WeekDaySchedule.scheduleOf(emptyList(), null, emptyList(), week = 1)
        assertEquals(7, nothing.days.size)
        assertTrue(nothing.isEmptyWeek)
        assertEquals(1, nothing.totalWeeks)
    }

    // ---- ⑧ 结果与传入顺序无关（同一周点两次不该换一副样子）----

    @Test
    fun orderingIsIndependentOfInputOrder() {
        val courses = listOf(
            course("大学物理", day = 1, periods = listOf(3, 4), weeks = (1..16).toList(), id = 3L),
            course("高等数学", day = 1, periods = listOf(1, 2), weeks = (1..16).toList(), id = 1L),
            course("高等数学", day = 1, periods = listOf(5), weeks = (1..16).toList(), id = 2L),
        )
        val term = semester(16, "2026-09-07")
        val forward = WeekDaySchedule.scheduleOf(courses, term, slots, week = 4)
        val backward = WeekDaySchedule.scheduleOf(courses.reversed(), term, slots, week = 4)
        assertEquals(
            forward.days.map { it.meetings.map { m -> m.label to m.course.id } },
            backward.days.map { it.meetings.map { m -> m.label to m.course.id } },
        )
        assertEquals(listOf(1L, 3L, 2L), dayOf(forward, 1).meetings.map { it.course.id })
    }

    // ---- ⑨ 展示字段的三条口径 ----

    @Test
    fun displayFields() {
        val item = monday().copy(
            alias = "高数（A 班）",
            teacher = "未知教师",
            location = "  J3-101  ",
        )
        val meeting = WeekDaySchedule
            .scheduleOf(listOf(item), semester(16, "2026-09-07"), slots, week = 1)
            .days[0].meetings.single()
        assertEquals("别名优先，与课表卡片同一句", "高数（A 班）", meeting.label)
        assertNull("哨兵教师不许印成一位叫「未知教师」的人", meeting.teacher)
        assertEquals("教室两侧空白去掉", "J3-101", meeting.location)
        assertEquals(SemesterStats.courseGroupKey(item), meeting.groupKey)
        assertEquals(item, meeting.course)
    }

    @Test
    fun teacherSurvivesWhenItIsReal() {
        val meeting = WeekDaySchedule
            .scheduleOf(
                listOf(monday().copy(teacher = "张三", campus = " 学院路 ")),
                semester(16, "2026-09-07"),
                slots,
                week = 1,
            )
            .days[0].meetings.single()
        assertEquals("张三", meeting.teacher)
        assertEquals("学院路", meeting.campus)
    }
}
