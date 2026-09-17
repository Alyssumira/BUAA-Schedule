package com.buaa.schedule.data.import

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuaaScheduleParserTest {

    @Test
    fun parseArrangedListSplitsTeacherWeekPairs() {
        val dto = BuaaCourseDto(
            courseName = "高等数学",
            courseCode = "MATH101",
            dayOfWeek = 1,
            beginSection = 1,
            endSection = 2,
            placeName = "J3-101",
            campusName = "学院路",
            cellDetail = listOf(
                CellDetailDto(text = "张三[1-8周]"),
                CellDetailDto(text = "李四[9-16周]"),
            ),
        )

        val courses = BuaaScheduleParser.parseArrangedList(listOf(dto), "2025-2026-1")

        assertEquals(2, courses.size)
        assertEquals("张三", courses[0].teacher)
        assertEquals((1..8).toList(), courses[0].weeks)
        assertEquals("李四", courses[1].teacher)
        assertEquals((9..16).toList(), courses[1].weeks)
        assertTrue(courses[0].sourceGroupKey == courses[1].sourceGroupKey)
    }

    @Test
    fun parseArrangedListFallbackWhenNoCellDetail() {
        val dto = BuaaCourseDto(
            courseName = "大学物理",
            courseCode = "PHY101",
            dayOfWeek = 3,
            beginSection = 3,
            endSection = 4,
        )

        val courses = BuaaScheduleParser.parseArrangedList(listOf(dto), "2025-2026-1")

        assertEquals(1, courses.size)
        assertEquals(20, courses[0].weeks.size)
    }

    @Test
    fun parseArrangedListFallbackRespectsTotalWeeks() {
        val dto = BuaaCourseDto(
            courseName = "大学物理",
            courseCode = "PHY101",
            dayOfWeek = 3,
            beginSection = 3,
            endSection = 4,
        )

        val courses = BuaaScheduleParser.parseArrangedList(listOf(dto), "2025-2026-1", totalWeeks = 10)

        assertEquals(1, courses.size)
        assertEquals((1..10).toList(), courses[0].weeks)
    }

    @Test
    fun sameSlotDifferentWeeksMergeIntoOneRow() {
        // 教务的"教学班分排"会把同一门课同一老师返回成两条同槽记录、只有周次不同。
        // 此前原样入库 = 同一格里叠两张一模一样的课卡。
        val firstHalf = BuaaCourseDto(
            courseName = "高等数学",
            courseCode = "MATH101",
            dayOfWeek = 1,
            beginSection = 1,
            endSection = 2,
            placeName = "J3-101",
            cellDetail = listOf(CellDetailDto(text = "张三[1-8周]")),
        )
        val secondHalf = firstHalf.copy(
            cellDetail = listOf(CellDetailDto(text = "张三[9-16周]")),
        )

        val courses = BuaaScheduleParser.parseArrangedList(listOf(firstHalf, secondHalf), "2025-2026-1")

        assertEquals(1, courses.size)
        assertEquals((1..16).toList(), courses[0].weeks)
        assertEquals("张三", courses[0].teacher)
    }

    @Test
    fun parseOutcomeCountsFallbackAndUnknownTeacher() {
        val fallbackDto = BuaaCourseDto(
            courseName = "大学物理",
            courseCode = "PHY101",
            dayOfWeek = 3,
            beginSection = 3,
            endSection = 4,
        )
        val normalDto = BuaaCourseDto(
            courseName = "高等数学",
            courseCode = "MATH101",
            dayOfWeek = 1,
            beginSection = 1,
            endSection = 2,
            cellDetail = listOf(CellDetailDto(text = "张三[1-8周]")),
        )

        val outcome = BuaaScheduleParser.parseArrangedListOutcome(
            listOf(fallbackDto, normalDto),
            "2025-2026-1",
        )

        assertEquals(2, outcome.courses.size)
        assertEquals(1, outcome.fallbackWeekCourses)
        assertEquals(1, outcome.unknownTeacherCourses)
    }

    @Test
    fun parseJgListFromBitmap() {
        val dto = GsmisCourseDto(
            courseCode = "CS101",
            courseName = "算法",
            dayOfWeek = "2",
            weekBitmap = "01010101000000000000",
            placeName = "A101",
            teacherNames = "张三,李四",
            startSection = "6",
        )

        val courses = BuaaScheduleParser.parseJgList(listOf(dto), "20261")

        assertEquals(1, courses.size)
        assertEquals(listOf(2, 4, 6, 8), courses[0].weeks)
        assertEquals("张三,李四", courses[0].teacher)
    }
}
