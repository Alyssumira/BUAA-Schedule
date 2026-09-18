package com.buaa.schedule.data.import

import com.buaa.schedule.domain.schedule.CourseConstraints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 教务/文本两条导入链的**节次区间上界**。
 *
 * 两个解析器都会把 `start-end` 展开成 `periods`。教务接口出现过
 * `endSection = 2147483647` 这类溢出脏数据，用户手输的文本课表同样可以写
 * `1-2000000000`。展开前若不限幅，一次导入就会分配 20 亿个 Int 直接 OOM。
 */
class PeriodRangeBoundsTest {

    // ---------- 文本导入 ----------

    private fun parseOnePeriodField(section: String): List<Int> {
        val courses = TextScheduleParser.parse(
            "高等数学,张三,J3-101,1,$section,1-16",
            "2026-2027-1",
        )
        return courses.singleOrNull()?.periods ?: error("未解析出课程：$section")
    }

    @Test
    fun textParserHugeSectionIsClamped() {
        val periods = parseOnePeriodField("1-2000000000")
        assertEquals(CourseConstraints.MAX_PERIOD, periods.size)
        assertEquals(1, periods.first())
        assertEquals(CourseConstraints.MAX_PERIOD, periods.last())
    }

    @Test
    fun textParserHugeStartCollapsesToSinglePeriod() {
        // start 与 end 都被夹到 MAX_PERIOD → 单元素区间，不是空列表也不是异常
        assertEquals(
            listOf(CourseConstraints.MAX_PERIOD),
            parseOnePeriodField("2000000000-2000000000"),
        )
    }

    @Test
    fun textParserZeroStartIsClampedToPeriodOne() {
        assertEquals(listOf(1, 2), parseOnePeriodField("0-2"))
    }

    @Test
    fun textParserLeadingMinusIsTreatedAsInvertedAndDropped() {
        // "-3-2" split('-') 的首个空串被 mapNotNull 丢掉 → 按 3..2 解析 → 空区间 → 整条丢弃。
        // 关键是「不 OOM、不抛异常」，而不是猜用户意图。
        val courses = TextScheduleParser.parse("高等数学,张三,J3-101,1,-3-2,1-16", "2026-2027-1")
        assertTrue(courses.isEmpty())
    }

    @Test
    fun textParserSingleSectionIsAccepted() {
        assertEquals(listOf(5), parseOnePeriodField("5"))
    }

    @Test
    fun textParserInvertedSectionIsDropped() {
        // 区间倒置不会 OOM（区间为空）；空 periods 的行在课表上画不出来，解析器整条丢弃
        val courses = TextScheduleParser.parse("高等数学,张三,J3-101,1,9-2,1-16", "2026-2027-1")
        assertTrue(courses.isEmpty())
    }

    // ---------- 教务导入 ----------

    private fun arranged(beginSection: Int?, endSection: Int?) = BuaaCourseDto(
        courseName = "高等数学",
        courseCode = "MATH001",
        dayOfWeek = 1,
        beginSection = beginSection,
        endSection = endSection,
        placeName = "J3-101",
        campusName = "学院路",
        weeksAndTeachers = "张三[1-16周]",
    )

    private fun parsePeriods(dto: BuaaCourseDto): List<Int> =
        BuaaScheduleParser.parseArrangedList(listOf(dto), "2026-2027-1", 20)
            .first().periods

    @Test
    fun buaaParserOverflowEndSectionIsClamped() {
        val periods = parsePeriods(arranged(1, Int.MAX_VALUE))
        assertEquals(CourseConstraints.MAX_PERIOD, periods.size)
        assertEquals(1, periods.first())
        assertEquals(CourseConstraints.MAX_PERIOD, periods.last())
    }

    @Test
    fun buaaParserOverflowBothEndsCollapseToSinglePeriod() {
        assertEquals(
            listOf(CourseConstraints.MAX_PERIOD),
            parsePeriods(arranged(Int.MAX_VALUE, Int.MAX_VALUE)),
        )
    }

    @Test
    fun buaaParserMissingEndSectionFallsBackToStart() {
        assertEquals(listOf(3), parsePeriods(arranged(3, null)))
    }

    @Test
    fun buaaParserNegativeSectionIsClampedToPeriodOne() {
        assertEquals(listOf(1, 2, 3), parsePeriods(arranged(-9, 3)))
    }

    @Test
    fun buaaParserInvertedSectionIsDropped() {
        // 反序钳制后展开为空 periods → 整条丢弃，与文本导入同一条规则
        assertTrue(
            BuaaScheduleParser.parseArrangedList(listOf(arranged(12, 3)), "2026-2027-1", 20).isEmpty()
        )
    }
}
