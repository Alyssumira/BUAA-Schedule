package com.buaa.schedule.data.import

import com.buaa.schedule.domain.model.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 教务 credit 的解析与合并。
 *
 * credit 是 `BuaaCourseDto` 里唯一此前被直接丢掉的字段（教务给了、我们没存）。
 * 这里钉两件事：
 * 1. 学分要落到 Course 上，且**坏值只丢学分、不丢课** —— 整学期抓取要按周打十几轮
 *    接口，一个脏值让某一周失败就是 R6 加 `code` 校验要治的那类静默缺周；
 * 2. 同一门课被拆成多个片段（1-8 / 9-16 周、按周重复返回）时，预览并成一条后
 *    学分只算一次：教务在每一行上都重写一遍整门课的学分，求和等于按片段数翻倍。
 */
class BuaaScheduleParserCreditTest {

    private fun dto(
        credit: String?,
        weeksDesc: String = "张三[1-8周]",
        name: String = "高等数学",
    ) = BuaaCourseDto(
        courseName = name,
        courseCode = "MATH101",
        teachClassId = "202620271MATH101001",
        courseSerialNo = "01",
        credit = credit,
        dayOfWeek = 1,
        beginSection = 1,
        endSection = 2,
        placeName = "J3-101",
        campusName = "学院路",
        cellDetail = listOf(CellDetailDto(text = weeksDesc)),
    )

    private fun parse(vararg items: BuaaCourseDto): List<Course> =
        BuaaScheduleParser.parseArrangedList(items.toList(), "2026-2027-1")

    @Test
    fun keepsCreditFromDto() {
        val course = parse(dto("3.5")).single()

        assertEquals(3.5, course.credit!!, 0.0)
        assertEquals("高等数学", course.name)
    }

    @Test
    fun zeroCreditStaysZeroNotMissing() {
        // 教务真实返回过 "0.0"（入学教育类）：那是「不计学分」，与「没采到」必须分开
        assertEquals(0.0, parse(dto("0.0")).single().credit!!, 0.0)
    }

    @Test
    fun creditIsCarriedOntoEveryFragmentOfOneRow() {
        // 一条记录两个教师片段：学分开在课程上，两个片段都带上同一个值
        val courses = parse(dto("4.0", weeksDesc = "张三[1-8周]/李四[9-16周]"))

        assertEquals(2, courses.size)
        courses.forEach { assertEquals(4.0, it.credit!!, 0.0) }
        assertEquals(courses[0].sourceGroupKey, courses[1].sourceGroupKey)
    }

    @Test
    fun mergedSplitWeekRangesCountCreditOnce() {
        // R6 的预览并行的就是这种数据：同键、只有周次不同（1-8 与 9-16）
        val first = dto("3.5", weeksDesc = "张三[1-8周]")
        val second = dto("3.5", weeksDesc = "张三[9-16周]")

        val merged = parse(first, second)

        assertEquals(1, merged.size)
        assertEquals((1..16).toList(), merged.single().weeks)
        assertEquals("学分取最大值，不是两段相加", 3.5, merged.single().credit!!, 0.0)
    }

    @Test
    fun mergeKeepsTheKnownCreditWhenTheOtherFragmentHasNone() {
        val known = dto("3.5", weeksDesc = "张三[1-8周]")
        val unknown = dto(null, weeksDesc = "张三[9-16周]")

        val merged = parse(known, unknown)

        assertEquals(1, merged.size)
        assertEquals(3.5, merged.single().credit!!, 0.0)
    }

    @Test
    fun repeatedWeeklyRowsOfTheSameCourseDoNotMultiplyCredit() {
        // type=week&week=N 按周返回：同一门课在它上的每个周都出现一次，19 轮汇总后
        // 并成一条 —— 学分必须还是那一份
        val row = dto("2.5", weeksDesc = "张三[1周]")

        val merged = parse(row, row.copy(), row.copy())

        assertEquals(1, merged.size)
        assertEquals(2.5, merged.single().credit!!, 0.0)
        assertEquals(listOf(1), merged.single().weeks)
    }

    @Test
    fun unparseableCreditDropsOnlyTheCredit() {
        listOf("约 3 分", "三学分", "", "   ", "null", "-2", "1e308").forEach { raw ->
            val courses = parse(dto(raw))

            assertEquals("脏学分「$raw」不能把整行课丢掉", 1, courses.size)
            assertNull("脏学分「$raw」应判成没有学分数据", courses.single().credit)
        }
    }

    @Test
    fun unitAndFullWidthCreditAreParsed() {
        // 教务页面里见过带单位的写法，接口偶尔还给全角数字
        assertEquals(3.5, parse(dto("3.5学分")).single().credit!!, 0.0)
        assertEquals(2.0, parse(dto("２")).single().credit!!, 0.0)
        assertEquals(1.5, parse(dto(" 1.5 ")).single().credit!!, 0.0)
    }

    @Test
    fun outcomeWarningCountsSurviveTheCreditChange() {
        // 没有教师/周次片段的行仍按整学期兜底并计入警告数（R6 的兜底口径不受影响）
        val outcome = BuaaScheduleParser.parseArrangedListOutcome(
            listOf(dto("3.5").copy(cellDetail = emptyList())),
            termCode = "2026-2027-1",
            totalWeeks = 19,
        )

        assertEquals(1, outcome.courses.size)
        assertEquals(19, outcome.courses.single().weeks.size)
        assertEquals(3.5, outcome.courses.single().credit!!, 0.0)
        assertEquals(1, outcome.fallbackWeekCourses)
        assertEquals(1, outcome.unknownTeacherCourses)
    }
}
