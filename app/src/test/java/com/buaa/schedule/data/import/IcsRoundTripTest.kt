package com.buaa.schedule.data.import

import com.buaa.schedule.data.export.IcsExporter
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.TimeSlotProfile
import com.buaa.schedule.domain.schedule.ImportPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 导出 → 解析 → 导入合并 的往返一致性。
 *
 * 这条链路的两个断点都在真实数据上发生过：
 * - ICS 每次上课一个 VEVENT、不带 RRULE，解析回来是同一门课的多条单周记录，
 *   合并阶段任何「按 key 丢弃后续条目」都会把整门课压成一帧；
 * - DESCRIPTION 里的教师名用贪婪正则捕获，会把节次/周次一起吞进教师字段，
 *   而污染串含周次 → 同门课各次上课的 key 互不相同，合并根本触发不了。
 */
class IcsRoundTripTest {

    private val termCode = "2026-2027-1"
    private val semesterStart = LocalDate.of(2026, 9, 7)

    private val semester = Semester(
        termCode = termCode,
        termName = termCode,
        startDate = semesterStart.toString(),
        totalWeeks = 19,
    )

    private fun course(
        name: String,
        dayOfWeek: Int,
        periods: List<Int>,
        weeks: List<Int>,
    ) = Course(
        name = name,
        teacher = "张三",
        location = "J3-101",
        dayOfWeek = dayOfWeek,
        periods = periods,
        weeks = weeks,
    )

    @Test
    fun roundTripKeepsCoursesAndWeeks() {
        val original = listOf(
            course("高等数学", dayOfWeek = 1, periods = listOf(1, 2), weeks = listOf(1, 3, 5)),
            course("大学英语", dayOfWeek = 3, periods = listOf(6, 7), weeks = (2..6).toList()),
        )

        val exported = IcsExporter.export(semester, original, emptyList())
        assertEquals(3 + 5, exported.eventCount)
        assertEquals(0, exported.skippedOccurrences)

        val plan = ImportPlanner.buildImportPlan(
            existing = emptyList(),
            imported = IcsParser.parse(exported.text, semesterStart, termCode),
        )

        assertEquals(original.map { it.name }.sorted(), plan.map { it.name }.sorted())
        for (before in original) {
            val after = plan.first { it.name == before.name }
            assertEquals("教师名不能把 DESCRIPTION 后续字段带进来", before.teacher, after.teacher)
            assertEquals(before.location, after.location)
            assertEquals(before.dayOfWeek, after.dayOfWeek)
            assertEquals(before.periods, after.periods)
            assertEquals("周次必须完整回来", before.weeks, after.weeks)
        }
    }

    @Test
    fun reimportReusesIdsSoRemindersSurvive() {
        val text = IcsExporter.export(
            semester,
            listOf(course("高等数学", dayOfWeek = 1, periods = listOf(1, 2), weeks = listOf(1, 2, 3))),
            emptyList(),
        ).text

        val first = ImportPlanner
            .buildImportPlan(emptyList(), IcsParser.parse(text, semesterStart, termCode))
            .mapIndexed { index, course -> course.copy(id = index + 1L) }
        val second = ImportPlanner.buildImportPlan(first, IcsParser.parse(text, semesterStart, termCode))

        assertEquals(listOf(1L), second.map { it.id })
        assertEquals(first.map { it.weeks }, second.map { it.weeks })
    }

    /** 第 3 节在默认作息是 09:50，在这份自定义作息里是 15:00 */
    private val customSlots = listOf(
        TimeSlot(number = 1, startTime = "09:00", endTime = "09:50"),
        TimeSlot(number = 2, startTime = "10:00", endTime = "10:50"),
        TimeSlot(number = 3, startTime = "15:00", endTime = "15:50"),
    )

    @Test
    fun customTimeTableDrivesBothExportAndImport() {
        val original = listOf(course("测量实习", dayOfWeek = 4, periods = listOf(3), weeks = listOf(1)))

        val text = IcsExporter.export(semester, original, customSlots).text
        assertTrue("15:00 上课的课次必须按自定义作息导出", text.contains("DTSTART:20260910T150000"))

        val withSlots = IcsParser.parse(text, semesterStart, termCode, timeSlots = customSlots)
        assertEquals(listOf(3), withSlots.single().periods)

        // 用默认作息反查同一份文件：15:00–15:50 横跨默认第 7 节（14:50–15:35）
        // 和第 8 节（15:50 起）—— 节次排错，还会多占一节
        val withDefault = IcsParser.parse(text, semesterStart, termCode)
        assertEquals(listOf(7, 8), withDefault.single().periods)
    }

    @Test
    fun periodsAcrossLunchExportAsTwoWindows() {
        // 第 5、6 节节次号相邻，中间却隔着 105 分钟午饭：单条 VEVENT 会变成
        // 11:30→14:45 的 3h15m 长课（R5 F-30）
        val original = listOf(course("体育", dayOfWeek = 2, periods = listOf(5, 6), weeks = listOf(1)))
        val exported = IcsExporter.export(semester, original, TimeSlotProfile.DEFAULT)

        assertEquals(2, exported.eventCount)
        assertEquals(
            listOf("20260908T113000", "20260908T140000"),
            Regex("DTSTART:(\\S+)").findAll(exported.text).map { it.groupValues[1] }.toList(),
        )
        assertEquals(
            listOf("20260908T121500", "20260908T144500"),
            Regex("DTEND:(\\S+)").findAll(exported.text).map { it.groupValues[1] }.toList(),
        )
    }

    @Test
    fun rfcEscapesSurviveTheRoundTrip() {
        // 转义集必须对称：解码只认 \n 与 \, 的话，含反斜杠/分号的教室名会一路损坏
        val tricky = Course(
            name = "算法与程序\\设计",
            teacher = "李; 强",
            location = "主楼A,3层;301",
            dayOfWeek = 5,
            periods = listOf(1, 2),
            weeks = listOf(1),
        )
        val text = IcsExporter.export(semester, listOf(tricky), emptyList()).text
        val back = IcsParser.parse(text, semesterStart, termCode)

        assertEquals(tricky.name, back.single().name)
        assertEquals(tricky.location, back.single().location)
        assertEquals(tricky.teacher, back.single().teacher)
    }
}
