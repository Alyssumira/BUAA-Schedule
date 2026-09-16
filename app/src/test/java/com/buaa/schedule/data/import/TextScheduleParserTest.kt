package com.buaa.schedule.data.import

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextScheduleParserTest {

    @Test
    fun parseSimpleLine() {
        val content = """
            高等数学,张三,J3-101,1,1-2,1-16
            大学物理,李四,J3-205,3,3-4,1-16单
        """.trimIndent()

        val courses = TextScheduleParser.parse(content, "2026-2027-1")

        assertEquals(2, courses.size)
        assertEquals("高等数学", courses[0].name)
        assertEquals(1, courses[0].dayOfWeek)
        assertEquals((1..16).toList(), courses[0].weeks)
        assertEquals(listOf(1, 3, 5, 7, 9, 11, 13, 15), courses[1].weeks)
        assertEquals("2026-2027-1", courses[1].semesterCode)
    }

    @Test
    fun parseChineseDay() {
        val content = "英语,王,教3-101,周一,1-2,1-8"
        val courses = TextScheduleParser.parse(content, "T")
        assertEquals(1, courses.size)
        assertEquals(1, courses[0].dayOfWeek)
    }

    @Test
    fun emptyTeacherFieldKeepsColumnsAligned() {
        // 空教师字段必须占位，地点/星期等字段不能整体左移
        val content = "高数,,J3-101,1,1-2,1-16"
        val courses = TextScheduleParser.parse(content, "T")

        assertEquals(1, courses.size)
        assertEquals("高数", courses[0].name)
        assertNull(courses[0].teacher)
        assertEquals("J3-101", courses[0].location)
        assertEquals(1, courses[0].dayOfWeek)
        assertEquals((1..16).toList(), courses[0].weeks)
    }

    @Test
    fun singleDigitWeekNumberNotTreatedAsBitmap() {
        val content = "讲座,张三,J3-101,1,1-2,10"
        val courses = TextScheduleParser.parse(content, "T")

        assertEquals(1, courses.size)
        assertEquals(listOf(10), courses[0].weeks)
    }

    @Test
    fun everySundaySpellingMapsToDay7() {
        // 「周天」此前被写成「周日天」，粘贴这种写法的课表会整行被丢弃
        for (label in listOf("7", "周日", "周天", "星期日")) {
            val courses = TextScheduleParser.parse("体育,王,体育馆,$label,1-2,1-8", "T")

            assertEquals("$label 应识别为周日", 1, courses.size)
            assertEquals(7, courses[0].dayOfWeek)
        }
    }
}
