package com.buaa.schedule.data.import

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class IcsParserTest {

    @Test
    fun parseWeeklyEvent() {
        val content = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            SUMMARY:高等数学
            LOCATION:J3-101
            DESCRIPTION:教师:张三
            DTSTART;TZID=Asia/Shanghai:20260907T080000
            DTEND;TZID=Asia/Shanghai:20260907T084500
            RRULE:FREQ=WEEKLY;INTERVAL=1;UNTIL=20261221T235959Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val semesterStart = LocalDate.of(2026, 9, 7)
        val courses = IcsParser.parse(content, semesterStart, "2026-2027-1")

        assertEquals(1, courses.size)
        val course = courses[0]
        assertEquals("高等数学", course.name)
        assertEquals("张三", course.teacher)
        assertEquals(1, course.dayOfWeek)
        assertEquals(listOf(1), course.periods)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16), course.weeks)
    }

    @Test
    fun parseSingleEventOnlyOneWeek() {
        val content = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:讲座
            DTSTART:20260908T140000
            DTEND:20260908T144500
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val courses = IcsParser.parse(content, LocalDate.of(2026, 9, 7), "T")

        assertEquals(1, courses.size)
        assertEquals(listOf(1), courses[0].weeks)
    }

    @Test
    fun missingDtendFallsBackToStartTime() {
        // 没有 DTEND 时结束节次应与开始节次一致，而不是错算成第 1 节（08:00）
        val content = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:算法
            DTSTART:20260907T140000
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val courses = IcsParser.parse(content, LocalDate.of(2026, 9, 7), "T")

        assertEquals(1, courses.size)
        assertEquals(6, courses[0].startPeriod)
        assertEquals(6, courses[0].endPeriod)
    }

    @Test
    fun teacherExtractionStopsAtNewline() {
        // DESCRIPTION 里的换行用 \n 转义，教师名不能把后续行（地点）带进来
        val content = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:数据结构
            DESCRIPTION:教师:Nancy Xu\n地点:J3-101
            DTSTART:20260907T080000
            DTEND:20260907T084500
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val courses = IcsParser.parse(content, LocalDate.of(2026, 9, 7), "T")

        assertEquals(1, courses.size)
        assertEquals("Nancy Xu", courses[0].teacher)
    }

    @Test
    fun teacherNameKeepsUnspacedMiddleDot() {
        // 少数民族姓名的「·」两侧不带空格，属于姓名本身，不能被当成字段分隔符
        val content = icsWithDescription("教师: 买买提·艾力")

        val courses = IcsParser.parse(content, LocalDate.of(2026, 9, 7), "T")

        assertEquals("买买提·艾力", courses[0].teacher)
    }

    @Test
    fun teacherExtractionStopsAtAppDescriptionSeparator() {
        // 本应用导出的 DESCRIPTION 形如「教师: 张三 · 第1-2节 · 第1,3,5周」。
        // 教师名若吃掉后半段，污染串里的周次会让同一门课的各次上课生成不同
        // courseKey，重新导入时合并失效（F-01 的另一半）。
        val content = icsWithDescription("教师: 张三 · 第1-2节 · 第1,3,5周")

        val courses = IcsParser.parse(content, LocalDate.of(2026, 9, 7), "T")

        assertEquals("张三", courses[0].teacher)
    }

    private fun icsWithDescription(description: String) = """
        BEGIN:VCALENDAR
        BEGIN:VEVENT
        SUMMARY:结构力学
        DESCRIPTION:$description
        DTSTART:20260907T080000
        DTEND:20260907T084500
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    @Test
    fun rruleCountLimitsOccurrences() {
        val content = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:体育
            DTSTART:20260907T080000
            DTEND:20260907T084500
            RRULE:FREQ=WEEKLY;COUNT=3
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val courses = IcsParser.parse(content, LocalDate.of(2026, 9, 7), "T")

        assertEquals(1, courses.size)
        assertEquals(listOf(1, 2, 3), courses[0].weeks)
    }

    @Test
    fun rruleWithoutUntilCappedAtMaxWeeks() {
        // 无 UNTIL/COUNT 的无限重复必须被学期总周数截断，不能膨胀到 200 周
        val content = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:英语
            DTSTART:20260907T080000
            DTEND:20260907T084500
            RRULE:FREQ=WEEKLY
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val courses = IcsParser.parse(
            content,
            LocalDate.of(2026, 9, 7),
            "T",
            maxWeeks = 16,
        )

        assertEquals(1, courses.size)
        assertEquals((1..16).toList(), courses[0].weeks)
    }

    @Test
    fun noTeacherFieldYieldsNullTeacher() {
        val content = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:自习
            DTSTART:20260907T080000
            DTEND:20260907T084500
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val courses = IcsParser.parse(content, LocalDate.of(2026, 9, 7), "T")

        assertEquals(1, courses.size)
        assertNull(courses[0].teacher)
    }

    @Test
    fun utcDtstartConvertedToLocalZone() {
        // 2026-09-07 00:00 UTC = 08:00 北京时间 → 周一第 1 节
        val content = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:高数
            DTSTART:20260907T000000Z
            DTEND:20260907T004500Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val courses = IcsParser.parse(
            content,
            LocalDate.of(2026, 9, 7),
            "T",
            zone = java.time.ZoneId.of("Asia/Shanghai"),
        )

        assertEquals(1, courses.size)
        assertEquals(1, courses[0].dayOfWeek)
        assertEquals(listOf(1), courses[0].periods)
    }

    @Test
    fun nonWeeklyRruleTreatedAsSingleOccurrence() {
        // DAILY 重复不能被当成每周重复展开
        val content = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:晨跑
            DTSTART:20260907T080000
            DTEND:20260907T084500
            RRULE:FREQ=DAILY;COUNT=20
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val courses = IcsParser.parse(content, LocalDate.of(2026, 9, 7), "T")

        assertEquals(1, courses.size)
        assertEquals(listOf(1), courses[0].weeks)
    }

    @Test
    fun bydayExpandsToSeparateWeekdayRows() {
        // RRULE:FREQ=WEEKLY;BYDAY=MO,WE 一周上两天。此前 BYDAY 被无视，
        // 整门课只落在 DTSTART 的周一，周三那一半直接消失。
        val content = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:大学物理
            DTSTART:20260907T080000
            DTEND:20260907T084500
            RRULE:FREQ=WEEKLY;BYDAY=MO,WE;UNTIL=20260930
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val courses = IcsParser.parse(content, LocalDate.of(2026, 9, 7), "T", maxWeeks = 4)

        assertEquals(2, courses.size)
        assertEquals(listOf(1, 3), courses.map { it.dayOfWeek }.sorted())
        // 两条序列各自覆盖 4 个教学周（9/7、9/9 起，UNTIL 到 9/30）
        courses.forEach { assertEquals((1..4).toList(), it.weeks) }
    }

    // ---- 畸形 / 边界 RRULE：这些输入此前能让解析器死循环 ----

    @Test
    fun zeroRruleIntervalDoesNotHang() {
        // INTERVAL=0 会让 date.plusWeeks(0) 原地踏步。此前实现会无限循环
        // （weeks 还会无限增长直到 OOM）；钳到 ≥1 后等价于"每周重复"。
        val content = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:畸形间隔
            DTSTART:20260907T080000
            DTEND:20260907T084500
            RRULE:FREQ=WEEKLY;INTERVAL=0
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val courses = IcsParser.parse(content, LocalDate.of(2026, 9, 7), "T", maxWeeks = 3)

        assertEquals(1, courses.size)
        assertEquals(listOf(1, 2, 3), courses[0].weeks)
    }

    @Test
    fun oversizedCountIsBoundedBySemesterWeeks() {
        // COUNT 极大时不能把内存吃光：迭代次数受硬上限与学期周数双重约束
        val content = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:超大计数
            DTSTART:20260907T080000
            DTEND:20260907T084500
            RRULE:FREQ=WEEKLY;COUNT=999999
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val courses = IcsParser.parse(content, LocalDate.of(2026, 9, 7), "T", maxWeeks = 3)

        assertEquals(1, courses.size)
        assertEquals(listOf(1, 2, 3), courses[0].weeks)
    }

    @Test
    fun zeroCountTreatedAsUnspecified() {
        // COUNT=0 是非法值（RFC 要求 ≥1）：按"未指定"处理，由学期周数收尾
        val content = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:零计数
            DTSTART:20260907T080000
            DTEND:20260907T084500
            RRULE:FREQ=WEEKLY;COUNT=0
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val courses = IcsParser.parse(content, LocalDate.of(2026, 9, 7), "T", maxWeeks = 3)

        assertEquals(1, courses.size)
        assertEquals(listOf(1, 2, 3), courses[0].weeks)
    }

    @Test
    fun tzidIsConvertedToTargetZone() {
        // 此前 TZID 被完全忽略、一律按浮动时间解析，跨时区导出会整体错位。
        // 这里显式钉住目标时区，断言"带 TZID"与"按浮动时间"结果不同。
        val floating = parseOne(
            "DTSTART:20260907T080000",
            "DTEND:20260907T084500",
        )
        val withTzid = parseOne(
            "DTSTART;TZID=America/Los_Angeles:20260907T080000",
            "DTEND;TZID=America/Los_Angeles:20260907T084500",
        )

        // 08:00 无 TZID → 就是本地 08:00（第 1 节）
        assertEquals(1, floating.size)
        assertEquals(listOf(1), floating[0].periods)
        // 08:00 洛杉矶时间 = 23:00 北京时间 → 落到当天靠后的节次，不该等于第 1 节
        assertEquals(1, withTzid.size)
        assertNotEquals(listOf(1), withTzid[0].periods)
    }

    @Test
    fun unknownTzidFallsBackToFloatingTime() {
        // 无法识别的 TZID 不能导致整条课程被丢掉，应按浮动时间继续解析
        val courses = parseOne(
            "DTSTART;TZID=Not/AZone:20260907T080000",
            "DTEND;TZID=Not/AZone:20260907T084500",
        )

        assertEquals(1, courses.size)
        assertEquals(listOf(1), courses[0].periods)
    }

    /** 用固定的目标时区解析单个事件，避免断言依赖运行环境的默认时区 */
    private fun parseOne(dtStartLine: String, dtEndLine: String): List<com.buaa.schedule.domain.model.Course> {
        val content = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:跨时区课
            $dtStartLine
            $dtEndLine
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        return IcsParser.parse(
            content,
            LocalDate.of(2026, 9, 7),
            "T",
            zone = java.time.ZoneId.of("Asia/Shanghai"),
        )
    }
}
