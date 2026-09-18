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
 * [SemesterStats] 的统计口径。
 *
 * 重点钉两件事：
 * 1. 「一门课」按 [SemesterStats.courseGroupKey] 归并 —— 拆成两段周次的课
 *    不能被算成两门、学分也不能加成两份（教务在每一行上都重写一遍整门课的学分）；
 * 2. 所有指标对空输入都不抛：统计页是最后一步，崩在这里等于用户看整页错误。
 */
class SemesterStatsTest {

    private val semester = Semester(
        termCode = "2026-2027-1",
        termName = "2026-2027-1",
        startDate = "2026-09-07",
        totalWeeks = 16,
    )

    /** 默认作息里第 1、2 节各 45 分钟，连上 = 90 分钟 */
    private fun course(
        name: String,
        groupKey: String? = null,
        dayOfWeek: Int = 1,
        periods: List<Int> = listOf(1, 2),
        weeks: List<Int> = (1..16).toList(),
        credit: Double? = null,
    ) = Course(
        name = name,
        teacher = "张三",
        location = "J3-101",
        dayOfWeek = dayOfWeek,
        periods = periods,
        weeks = weeks,
        sourceGroupKey = groupKey,
        semesterCode = semester.termCode,
        credit = credit,
    )

    // ---- 学分 ----

    @Test
    fun emptyCourseListYieldsZeroedSummary() {
        val summary = SemesterStats.summarize(emptyList(), semester, emptyList())

        assertEquals(0.0, summary.totalCredits, 0.0)
        assertEquals(0, summary.fragmentCount)
        assertEquals(0, summary.courseCount)
        assertTrue(summary.perCourse.isEmpty())
        assertEquals(0, summary.creditsKnown)
        assertEquals(0, summary.creditsMissing)
        assertEquals(SemesterStats.TOTAL_DAYS, summary.dayLoads.size)
        assertTrue(summary.dayLoads.all { it.isFree })
        assertNull(summary.busiestDayOfWeek)
        assertNull(summary.quietestBusyDay)
        // 一节没上：全学期 7 天 × 默认 14 节都是空档
        assertEquals(14, summary.periodsPerDay)
        assertEquals(98, summary.freeSlotCount)
        assertEquals(98, summary.totalSlotCount)
        assertEquals(16, summary.weekCount)
    }

    @Test
    fun splitWeekRangesCountCreditOnce() {
        // 同一门课拆成 1-8 与 9-16 两段（教务按周返回，并完预览就是一条课两个片段）
        val firstHalf = course("高等数学", groupKey = "G1", weeks = (1..8).toList(), credit = 3.5)
        val secondHalf = course("高等数学", groupKey = "G1", weeks = (9..16).toList(), credit = 3.5)

        val credits = SemesterStats.creditsByCourse(listOf(firstHalf, secondHalf))

        assertEquals(1, credits.size)
        assertEquals(2, credits.single().fragmentCount)
        assertEquals(3.5, credits.single().credit!!, 0.0)
        // 并成一条预览行/两个片段都只算一次：3.5 而不是 7
        assertEquals(3.5, SemesterStats.totalCredits(listOf(firstHalf, secondHalf)), 0.0)
    }

    @Test
    fun lectureAndLabOnDifferentDaysShareOneCredit() {
        // 理论课周一 1-2 节、实验课周五 5-6 节，同一个教学班 = 同一门课
        val lecture = course("大学物理", groupKey = "G2", dayOfWeek = 1, periods = listOf(1, 2), credit = 4.0)
        val lab = course("大学物理", groupKey = "G2", dayOfWeek = 5, periods = listOf(5, 6), credit = 4.0)

        val credits = SemesterStats.creditsByCourse(listOf(lecture, lab))

        assertEquals(1, credits.size)
        assertEquals(4.0, SemesterStats.totalCredits(listOf(lecture, lab)), 0.0)
    }

    @Test
    fun sameDayDifferentCoursesAreNotMerged() {
        val a = course("高等数学", groupKey = "GA", credit = 3.0)
        val b = course("线性代数", groupKey = "GB", credit = 2.0)

        assertEquals(2, SemesterStats.creditsByCourse(listOf(a, b)).size)
        assertEquals(5.0, SemesterStats.totalCredits(listOf(a, b)), 0.0)
    }

    @Test
    fun manualCoursesGroupByNameWhenNoSourceKey() {
        // 手动课程没有组键：同名同学期视为同一门课，异名各算一次
        val first = course("体育", dayOfWeek = 3)
        val second = course("体育", dayOfWeek = 4)
        val third = course("数学建模", dayOfWeek = 4)

        val keys = listOf(first, second, third).map { SemesterStats.courseGroupKey(it) }

        assertEquals(keys[0], keys[1])
        assertFalse(keys[0] == keys[2])
        assertEquals(2, SemesterStats.creditsByCourse(listOf(first, second, third)).size)
    }

    @Test
    fun nullCreditCourseIsReportedButNotCounted() {
        val known = course("高等数学", groupKey = "GA", credit = 3.0)
        val unknown = course("新生研讨课", groupKey = "GB")

        val summary = SemesterStats.summarize(listOf(known, unknown), semester, emptyList())

        assertEquals(3.0, summary.totalCredits, 0.0)
        assertEquals(1, summary.creditsKnown)
        assertEquals(1, summary.creditsMissing)
        assertNull(summary.perCourse.first { it.course.name == "新生研讨课" }.credit)
    }

    @Test
    fun zeroCreditIsNotTheSameAsMissingCredit() {
        // 教务确实返回过 "0.0" 的入学教育课：那是「不计学分」，不是「没采到」
        val zero = course("入学教育", groupKey = "G0", credit = 0.0)

        val credits = SemesterStats.creditsByCourse(listOf(zero))

        assertEquals(0.0, credits.single().credit!!, 0.0)
        assertEquals(1, SemesterStats.summarize(listOf(zero), semester, emptyList()).creditsKnown)
        assertEquals(0, SemesterStats.summarize(listOf(zero), semester, emptyList()).creditsMissing)
    }

    @Test
    fun dirtyCreditValuesFallBackToMissing() {
        // 坏值（负数、越界、NaN）不能污染求和：一律判成「没有学分数据」
        val negative = course("A", groupKey = "NA", credit = -2.0)
        val huge = course("B", groupKey = "NB", credit = 1e308)
        val notANumber = course("C", groupKey = "NC", credit = Double.NaN)

        val summary = SemesterStats.summarize(listOf(negative, huge, notANumber), semester, emptyList())

        assertEquals(0.0, summary.totalCredits, 0.0)
        assertEquals(0, summary.creditsKnown)
        assertEquals(3, summary.creditsMissing)
    }

    @Test
    fun totalCreditsIsRoundedToAvoidFloatTail() {
        val a = course("A", groupKey = "FA", credit = 3.3)
        val b = course("B", groupKey = "FB", credit = 3.4)

        // 裸 Double 相加是 6.700000000000001，统计页不能直接印
        assertEquals(6.7, SemesterStats.totalCredits(listOf(a, b)), 0.0)
    }

    // ---- 每周分钟负载 ----

    @Test
    fun weeklyLoadOnlyCountsWeeksWithClass() {
        // 只上 1-8 周的课：学期 16 周，平均分钟数应折半，峰值仍是单次上课的 90 分钟
        val shortLived = course("短学期课", groupKey = "GS", weeks = (1..8).toList())

        val monday = SemesterStats.dayLoads(listOf(shortLived), semester, emptyList()).first()

        assertEquals(90L, monday.peakMinutes)
        assertEquals(45L, monday.averageMinutes)
    }

    @Test
    fun peakCountsTheBusiestSingleWeekNotTheSemesterTotal() {
        // 两段周次不重叠（1-8 / 9-16）：任何一周都只上一节 90 分钟，峰值不能是 180
        val first = course("高等数学", groupKey = "GP", weeks = (1..8).toList())
        val second = course("高等数学", groupKey = "GP", weeks = (9..16).toList())

        val monday = SemesterStats.dayLoads(listOf(first, second), semester, emptyList()).first()

        assertEquals(90L, monday.peakMinutes)
        assertEquals(90L, monday.averageMinutes)
        // 归并成"一门课"，但那天确实占了格子
        assertEquals(1, monday.courseCount)
    }

    @Test
    fun overlappingFragmentsAddUpWithinOneWeek() {
        val lecture = course("A", groupKey = "OA", dayOfWeek = 2, periods = listOf(1, 2))
        val lab = course("B", groupKey = "OB", dayOfWeek = 2, periods = listOf(3, 4))

        val tuesday = SemesterStats.dayLoads(listOf(lecture, lab), semester, emptyList())[1]

        assertEquals(180L, tuesday.peakMinutes)
        assertEquals(2, tuesday.courseCount)
    }

    @Test
    fun coursesPerWeekdayDeduplicateByCourseIdentity() {
        // 同一门课的两个片段都在周三：天数上是 1 门课，不是 2 门
        val first = course("C", groupKey = "TC", dayOfWeek = 3, periods = listOf(1))
        val second = course("C", groupKey = "TC", dayOfWeek = 3, periods = listOf(9))

        val wednesday = SemesterStats.dayLoads(listOf(first, second), semester, emptyList())[2]

        assertEquals(1, wednesday.courseCount)
    }

    @Test
    fun weeksBeyondSemesterRangeAreIgnored() {
        // 学期只有 16 周，第 17-20 周的数据多半是下一学期串过来的脏行
        val overflowing = course("超范围课", groupKey = "GO", weeks = (1..20).toList())

        val monday = SemesterStats.dayLoads(listOf(overflowing), semester, emptyList()).first()

        // 1-20 周被裁成 1-16 周：分母还是 16 周，每周依旧 90 分钟
        assertEquals(90L, monday.averageMinutes)
        assertEquals(90L, monday.peakMinutes)
    }

    @Test
    fun courseEntirelyOutsideSemesterCountsAsFree() {
        val nextTerm = course("下学期课", groupKey = "GN", weeks = (17..20).toList())

        val summary = SemesterStats.summarize(listOf(nextTerm), semester, emptyList())

        assertTrue(summary.dayLoads.all { it.isFree })
        assertEquals(0, summary.dayLoads.first().courseCount)
        // 归并后仍是一门课（学分口径不因周次越界而消失），只是不占任何格子
        assertEquals(98, summary.freeSlotCount)
    }

    @Test
    fun busiestAndQuietestDayTieBreakOnEarliestWeekday() {
        val monday = course("M", groupKey = "BM", dayOfWeek = 1, periods = listOf(1, 2))
        val wednesday = course("W", groupKey = "BW", dayOfWeek = 3, periods = listOf(1, 2))
        val friday = course("F", groupKey = "BF", dayOfWeek = 5, periods = listOf(1, 2, 3, 4))

        val loads = SemesterStats.dayLoads(listOf(monday, wednesday, friday), semester, emptyList())

        assertEquals(5, SemesterStats.busiestDay(loads)?.dayOfWeek)
        // 90 分钟的两天并列：取更早的那个，结果不能随列表顺序变
        assertEquals(1, SemesterStats.quietestBusyDay(loads)?.dayOfWeek)
    }

    @Test
    fun freeSlotsCountCellsOccupiedInAnyWeek() {
        // 周一第 1、2 节全学期有课，第 3 节只在第 1 周有课 → 第 3 节也不算空档
        val allTerm = course("A", groupKey = "SA", dayOfWeek = 1, periods = listOf(1, 2))
        val singleWeek = course("B", groupKey = "SB", dayOfWeek = 1, periods = listOf(3), weeks = listOf(1))

        val loads = SemesterStats.dayLoads(listOf(allTerm, singleWeek), semester, emptyList())

        assertEquals(14 - 3, loads.first().freePeriodCount)
        assertEquals(14, loads[1].freePeriodCount)
        assertEquals(14 * 7 - 3, SemesterStats.freeSlotCount(listOf(allTerm, singleWeek), semester, emptyList()))
    }

    // ---- 学期缺失 / 非法 ----

    @Test
    fun nullSemesterDerivesWeekCountFromCourseData() {
        val shortLived = course("短课", groupKey = "NS", weeks = (1..4).toList())

        val loads = SemesterStats.dayLoads(listOf(shortLived), null, emptyList())
        val summary = SemesterStats.summarize(listOf(shortLived), null, emptyList())

        // 没有学期行时用数据里的最大周次当分母：第 1-4 周，每周 90 分钟
        assertEquals(4, summary.weekCount)
        assertEquals(90L, loads.first().averageMinutes)
        assertEquals(90L, loads.first().peakMinutes)
        assertFalse(summary.semesterAnchored)
        // 连课程都没有时周数兜到 1：它要当除数，0 会直接算术异常
        assertEquals(1, SemesterStats.summarize(emptyList(), null, emptyList()).weekCount)
    }

    @Test
    fun nonPositiveSemesterWeeksFallsBackToCourseData() {
        // totalWeeks = 0 是脏学期行：分母改从课程数据推（第 1-3 周），也不抛
        val brokenSemester = semester.copy(totalWeeks = 0)
        val c = course("高等数学", groupKey = "Z1", weeks = (1..3).toList())

        val summary = SemesterStats.summarize(listOf(c), brokenSemester, emptyList())
        val monday = summary.dayLoads.first()

        assertEquals(3, summary.weekCount)
        assertEquals(90L, monday.averageMinutes)
        assertEquals(90L, monday.peakMinutes)
    }

    @Test
    fun unsetStartDateDoesNotChangeAnyNumber() {
        // 开学日期解析不出来（老库里的非法串）时，统计口径全部按周次号成立
        val broken = semester.copy(startDate = "2026年9月7日")
        val good = course("高等数学", groupKey = "D1", credit = 3.5)

        val brokenSummary = SemesterStats.summarize(listOf(good), broken, emptyList())
        val goodSummary = SemesterStats.summarize(listOf(good), semester, emptyList())

        assertFalse(brokenSummary.semesterAnchored)
        assertTrue(goodSummary.semesterAnchored)
        assertEquals(goodSummary.dayLoads, brokenSummary.dayLoads)
        assertEquals(goodSummary.totalCredits, brokenSummary.totalCredits, 0.0)
        assertEquals(goodSummary.freeSlotCount, brokenSummary.freeSlotCount)
    }

    @Test
    fun allWeeksBelowOneStillDividesByPositiveWeekCount() {
        // 周次全是 0 / 空列表的脏数据 + 脏学期行：分母兜到 1（0 会算术异常），
        // 这些课一格也不占
        val dirty = listOf(
            course("A", groupKey = "DA", weeks = listOf(0, -3)),
            course("B", groupKey = "DB", weeks = emptyList()),
        )

        val summary = SemesterStats.summarize(dirty, semester.copy(totalWeeks = 0), emptyList())

        assertEquals(1, summary.weekCount)
        assertTrue(summary.dayLoads.all { it.isFree })
        assertEquals(98, summary.freeSlotCount)
    }

    @Test
    fun customTimeSlotsDriveTheMinuteMath() {
        val slots = listOf(
            TimeSlot(number = 1, startTime = "08:00", endTime = "09:30"),
            TimeSlot(number = 2, startTime = "09:30", endTime = "10:00"),
            // 倒挂的节次：不能算出负分钟
            TimeSlot(number = 3, startTime = "11:00", endTime = "10:00"),
            // 时间非法的节次：整格都不作数（既没时间也不占空档分母）
            TimeSlot(number = 4, startTime = "乱码", endTime = "12:00"),
        )
        val c = course("A", groupKey = "CT", periods = listOf(1, 2, 3))

        val monday = SemesterStats.dayLoads(listOf(c), semester, slots).first()

        assertEquals(120L, monday.peakMinutes)
        // 节次表只剩 3 行可用（第 4 节时间非法），第 1-3 节都被占 → 没有空档
        assertEquals(0, monday.freePeriodCount)
        assertEquals(3, SemesterStats.summarize(listOf(c), semester, slots).periodsPerDay)
    }

    @Test
    fun dirtyWeekdayIsSkippedInsteadOfCrashing() {
        // 教务脏数据里出现过 dayOfWeek = 0 / 8：越界星期不能索引数组
        val bogus = listOf(
            course("A", groupKey = "W0", dayOfWeek = 0),
            course("B", groupKey = "W8", dayOfWeek = 8),
            course("C", groupKey = "W9", dayOfWeek = 7),
        )

        val summary = SemesterStats.summarize(bogus, semester, emptyList())

        // 只有 dayOfWeek = 7 的那条进得来：周一到周六全空，周日 90 分钟
        assertTrue(summary.dayLoads.take(6).all { it.courseCount == 0 && it.isFree })
        assertEquals(90L, summary.dayLoads.last().peakMinutes)
        assertEquals(1, summary.dayLoads.last().courseCount)
        // 归并口径不受影响：三条课仍是三门
        assertEquals(3, summary.courseCount)
    }
}
