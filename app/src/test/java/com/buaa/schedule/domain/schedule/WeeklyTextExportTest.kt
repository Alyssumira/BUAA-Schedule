package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 纯文本周课表每一行的拼法（导出后用户是往群里粘的，没有第二次机会纠正排版）。
 *
 * 这一份文案此前没有任何测试，所以两类口径一起钉住：
 * 节次齐全时**逐字**等于旧文案，节次为空时那一段连同 " · " 一起不出场。
 */
class WeeklyTextExportTest {

    private fun course(
        name: String,
        periods: List<Int>,
        location: String? = "J3-101",
        weeks: List<Int> = listOf(1),
    ) = Course(
        name = name,
        location = location,
        dayOfWeek = 1,
        periods = periods,
        weeks = weeks,
    )

    /**
     * 三枚**固定**的「今天」（T351 裁 甲：文本导出器自己一枚钟都不读，这三档才第一次成为可测的输入）。
     * 学期 2026-09-07（周一）起 19 周 ⇒ 第 2 周是 09-14..20，期末那一周是 2027-01-11..17。
     */
    private val todayInTerm = LocalDate.of(2026, 9, 15)
    private val todayBeforeTerm = LocalDate.of(2026, 8, 31)
    private val todayAfterTerm = LocalDate.of(2027, 2, 1)

    private fun row(startDate: String = "2026-09-07", totalWeeks: Int = 19) = Semester(
        termCode = "T",
        termName = "本学期",
        startDate = startDate,
        totalWeeks = totalWeeks,
    )

    /** week=1 + 默认节次表：第 1 节 08:00 上课（这一格里 week 是显式给的 ⇒ today 进不了输出） */
    private fun export(c: Course) = ScheduleExporters.toWeeklyText(
        listOf(c), semester = null, timeSlots = emptyList(), week = 1, today = todayInTerm,
    )

    @Test
    fun `节次齐全的那一行逐字保持旧文案`() {
        assertEquals(
            "第 1 周\n\n周一\n08:00 高等数学 · J3-101 · 第1-2节",
            export(course("高等数学", listOf(1, 2))),
        )
        assertEquals(
            "第 1 周\n\n周一\n08:00 高等数学 · J3-101 · 第1-2,9-10节",
            export(course("高等数学", listOf(1, 2, 9, 10))),
        )
    }

    @Test
    fun `节次为空的课不导出第节也不留悬空分隔符`() {
        val text = export(course("高等数学", emptyList()))
        // 时刻也不给：startPeriod 在空节次时兜底成 1，那 08:00 是编出来的
        assertEquals("第 1 周\n\n周一\n高等数学 · J3-101", text)
        assertFalse(text, text.contains("第节"))
        assertFalse(text, text.trimEnd().endsWith("·"))
    }

    @Test
    fun `教室与节次同时缺席时只剩课名`() {
        assertEquals(
            "第 1 周\n\n周一\n高等数学",
            export(course("高等数学", emptyList(), location = "  ")),
        )
    }

    /**
     * 「学期起始日三档」×「今天落在学期内／前／后」= 9 格，每格**显式**喂 today
     *（T351 裁 甲的意义：改前 `toWeeklyText` 体内自己读钟，这九格无论喂什么都得到同一个输出，
     * 判据没法测；现在标题那一行完全由递进来的这两枚参数决定）。
     *
     * 钉的是标题（第一段），因为它就是那句"这一份是本周的课表 / 是全学期的课表"的实话：
     * - 学期行里是一枚好日期 ⇒ 今天在内念「第 2 周」，今天在前／在后都落进「假期中 · 全学期课程」；
     * - 学期行里的日期畸形（parse 当场拒掉）⇒ 与没有学期行同档判不出当前周，但学期名照旧出场；
     * - 没有学期行 ⇒ 标题只剩「课表 · 全学期课程」（今天递哪一枚都一样）。
     */
    @Test
    fun `学期起始日三档 乘 今天三档 九格的标题逐格钉死`() {
        val cells = listOf(
            "存在·今天落在学期内" to Triple(row(), todayInTerm, "本学期 · 第 2 周"),
            "存在·今天落在学期前" to Triple(row(), todayBeforeTerm, "本学期 · 假期中 · 全学期课程"),
            "存在·今天落在学期后" to Triple(row(), todayAfterTerm, "本学期 · 假期中 · 全学期课程"),
            "畸形·今天落在学期内" to Triple(row(startDate = "07/09/2026"), todayInTerm, "本学期 · 假期中 · 全学期课程"),
            "畸形·今天落在学期前" to Triple(row(startDate = "07/09/2026"), todayBeforeTerm, "本学期 · 假期中 · 全学期课程"),
            "畸形·今天落在学期后" to Triple(row(startDate = "07/09/2026"), todayAfterTerm, "本学期 · 假期中 · 全学期课程"),
            "缺失·今天落在学期内" to Triple(null, todayInTerm, "课表 · 全学期课程"),
            "缺失·今天落在学期前" to Triple(null, todayBeforeTerm, "课表 · 全学期课程"),
            "缺失·今天落在学期后" to Triple(null, todayAfterTerm, "课表 · 全学期课程"),
        )
        assertEquals("三档学期 × 三档今天 = 九格，一枚不许多、一枚不许少", 9, cells.size)
        for ((name, cell) in cells) {
            val (semester, today, header) = cell
            // 只有第 2 周有课的一门课：标题说"第 2 周"时它出场，说"全学期"时它也出场
            val text = ScheduleExporters.toWeeklyText(
                listOf(course("高等数学", listOf(1, 2), weeks = listOf(2))), semester, emptyList(), null, today,
            )
            assertEquals(name, header, text.lineSequence().first())
        }
    }

    /**
     * 九格的两手反证（防"三格写成同一枚期望值"那种恒绿）：
     * - 有学期行那一档：三枚 today 交出**三枚不同**标题 ⇒ today 真的进了这一档的输出；
     * - 没有学期行那一档：三枚 today 交出**同一枚**标题 ⇒ 判不出当前周时没有偷偷再读一次钟。
     * 另加一整篇逐字格（含正文那两行）钉住"标题改了、正文的裁周也跟着改"是同一枚 today 决定的。
     */
    @Test
    fun `today 只在判得出当前周那一档进标题 整篇正文也逐字钉住`() {
        val course = course("高等数学", listOf(1, 2), weeks = listOf(2))
        val headers = listOf(todayInTerm, todayBeforeTerm, todayAfterTerm).map {
            ScheduleExporters.toWeeklyText(listOf(course), row(), emptyList(), null, it).lineSequence().first()
        }
        assertEquals("三枚 today 在三档学期内／外上给出三种说法：$headers", 3, headers.distinct().size)
        val noSemester = listOf(todayInTerm, todayBeforeTerm, todayAfterTerm).map {
            ScheduleExporters.toWeeklyText(listOf(course), null, emptyList(), null, it).lineSequence().first()
        }
        assertEquals("没有学期行时三枚 today 说法一致（判不出当前周 ⇒ 全学期，绝不读第二次钟）：$noSemester", 1, noSemester.distinct().size)
        assertEquals(
            "整篇逐字（第 2 周那一格）：标题与正文裁的周次来自同一枚 today",
            "本学期 · 第 2 周\n\n周一\n08:00 高等数学 · J3-101 · 第1-2节",
            ScheduleExporters.toWeeklyText(listOf(course), row(), emptyList(), null, todayInTerm),
        )
        assertEquals(
            "整篇逐字（学期前那一格）：列出全学期 ⇒ 那句「本学期没有课」也不能写成「本周没有课」",
            "本学期 · 假期中 · 全学期课程\n\n周一\n08:00 高等数学 · J3-101 · 第1-2节",
            ScheduleExporters.toWeeklyText(listOf(course), row(), emptyList(), null, todayBeforeTerm),
        )
        assertEquals(
            "整篇逐字（第 3 周没课那一格）：标题说第 3 周，正文就得如实说本周没有课",
            "本学期 · 第 3 周\n\n本周没有课",
            ScheduleExporters.toWeeklyText(listOf(course), row(), emptyList(), null, LocalDate.of(2026, 9, 21)),
        )
    }
}
