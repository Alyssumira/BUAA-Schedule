package com.buaa.schedule.data.import

import com.buaa.schedule.domain.schedule.WeekCalculator
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

    // ---- T362/#617：ICS 链的周号 vs 全仓那唯一一把周号尺 ----
    //
    // 四象限 = {学期起始日是周一 / 不是周一} 这两维 × {date 落在学期前 / 学期内 / 学期外} 那三档。
    // 参照物一律是 `WeekCalculator`（在册 #617 点名的"另一套"要闭合的那一枚），**不是**把算式在测试里再抄一遍：
    // 每格先字面写出「这条 RRULE 走到哪几个日期」（手推，不经被测量），再让 WeekCalculator 就那几个日期答话，
    // 两枚答案相三角互证 —— 测试里若把 `/ 7 + 1` 重述一遍就成了套套逻辑（本仓那族假绿的常见形状）。

    @Test
    fun icsWeekNumberAnswersTheSameRulerAsWeekCalculatorOnEveryQuadrant() {
        // 一格红不许把其余格挡在门外：整张表量完再一次报数，差集的**全部**形状才看得见
        val mismatches = mutableListOf<String>()
        for (cell in weekQuadrantCells) {
            val courses = IcsParser.parse(
                content = eventIcs(cell.dtStart, cell.until, weekly = cell.weekly),
                semesterStart = cell.semesterStart,
                termCode = "T",
                maxWeeks = cell.maxWeeks,
            )
            // 参照物：手推的那几个课次日期逐个问 WeekCalculator，再按调用点那套界内筛法收拢
            val fromReference = cell.occurrenceDates
                .map { WeekCalculator.currentWeek(cell.semesterStart, it) }
                .filter { it in 1..cell.maxWeeks }
                .distinct()
                .sorted()
            if (cell.expectedWeeks != fromReference) {
                mismatches += "「${cell.name}」期望表（人写的）与 WeekCalculator（参照物）不同解：" +
                    "期望 ${cell.expectedWeeks} vs 参照 $fromReference ⇒ 这张表在拿旧周号当标准答案"
                continue
            }
            val actualWeeks = courses.flatMap { it.weeks }.distinct().sorted()
            if (actualWeeks != cell.expectedWeeks) {
                mismatches += "「${cell.name}」开学日 ${cell.semesterStart}（${cell.semesterStart.dayOfWeek}）、" +
                    "课次日期 ${cell.occurrenceDates.map { it.toString() }}：" +
                    "ICS 链给出 $actualWeeks，WeekCalculator 给出 ${cell.expectedWeeks}" +
                    (if (courses.isEmpty()) "（整门课没入库）" else "")
            }
        }
        assertEquals(
            "ICS 链的周号必须与 WeekCalculator 逐格同解 —— 报出来的每一格都是「这一格曾经会算错」",
            emptyList<String>(),
            mismatches,
        )
    }

    /**
     * 差集只长在「开学日不是周一」那一维：把 2026-09-07（周一）起连续 7 天逐个当学期原点摆一遍，
     * 每一枚都必须与 WeekCalculator 同解。生产上只可能递进来周一那一枚（见执行卡 ⓪ 的传递链取证），
     * 但这一格把「非周一那一维也闭合」钉住 —— 摘掉 IcsParser 自写那一形之前，k=1..6 这 6 枚全红。
     */
    @Test
    fun anchorShiftOnlyMovesWhenSemesterStartIsNotMonday() {
        val dtStart = LocalDate.of(2026, 9, 14)          // 周一
        val occurrences = (0..4).map { dtStart.plusWeeks(it.toLong()) }
        val content = eventIcs("20260914", "20261012T235959", weekly = true)
        val mismatches = mutableListOf<String>()
        for (shift in 0..6) {
            val semesterStart = LocalDate.of(2026, 9, 7).plusDays(shift.toLong())
            val expected = occurrences
                .map { WeekCalculator.currentWeek(semesterStart, it) }
                .filter { it in 1..25 }
                .distinct()
                .sorted()
            val actual = IcsParser.parse(content, semesterStart, "T").single().weeks
            if (expected != actual) {
                mismatches += "开学日 $semesterStart（${semesterStart.dayOfWeek}，偏移 k=$shift）：" +
                    "ICS 链 $actual vs WeekCalculator $expected"
            }
        }
        assertEquals(
            "「除以 7 加 1」的起点必须与 WeekCalculator 的 mondayOf 起点同锚 —— 每一枚 k 都要同解",
            emptyList<String>(),
            mismatches,
        )
    }

    /**
     * 一条事件（浮動时间 08:00–08:45 ⇒ 落进默认节次表第 1 节）。
     * `weekly = true` 时带 `RRULE:FREQ=WEEKLY;INTERVAL=1`，课次日期因此完全由
     * (DTSTART 的星期几, INTERVAL, UNTIL) 三元定死；`weekly = false` 只展开一次。
     */
    private fun eventIcs(yyyyMmDdStart: String, until: String?, weekly: Boolean): String {
        val rrule = if (weekly) {
            "RRULE:FREQ=WEEKLY;INTERVAL=1${until?.let { ";UNTIL=$it" } ?: ""}"
        } else {
            ""
        }
        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            SUMMARY:周号对照课
            DTSTART:${yyyyMmDdStart}T080000
            DTEND:${yyyyMmDdStart}T084500
            $rrule
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
    }

    /** 四象限的一格：学期原点、事件 DTSTART、UNTIL、是否每周、周次上界、手推的课次日期、期望周号（= WeekCalculator 的答案） */
    private data class WeekQuadrantCell(
        val name: String,
        val semesterStart: LocalDate,
        val dtStart: String,
        val until: String?,
        val weekly: Boolean,
        val maxWeeks: Int,
        val occurrenceDates: List<LocalDate>,
        val expectedWeeks: List<Int>,
    )

    private companion object {
        /**
         * 手推课次日期：从 DTSTART 所在周（周一起算）的那个星期几出发，按周步进，
         * 直到 `date.isAfter(UNTIL)` 那一闸之前；`weekly = false` 就只有 DTSTART 那一天。
         * 这几串日期是本表的独立输入（写死的字面量），不从被测量推导。
         */
        val weekQuadrantCells = listOf(
            // ── 学期内 ──
            WeekQuadrantCell(
                name = "周一开学 + 课在学期内（生产那一维：两套算式本来就同解，这一格改前就绿）",
                semesterStart = LocalDate.of(2026, 9, 7),
                dtStart = "20260908", until = "20260929T235959", weekly = true, maxWeeks = 25,
                occurrenceDates = listOf(
                    LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 15),
                    LocalDate.of(2026, 9, 22), LocalDate.of(2026, 9, 29),
                ),
                expectedWeeks = listOf(1, 2, 3, 4),
            ),
            WeekQuadrantCell(
                name = "周三开学 + 课在学期内（改前整门课低 1 周：周一那节课 ICS 算第 1 周、全仓其他链算第 2 周）",
                semesterStart = LocalDate.of(2026, 9, 9),
                dtStart = "20260914", until = "20260928T235959", weekly = true, maxWeeks = 25,
                occurrenceDates = listOf(
                    LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 28),
                ),
                expectedWeeks = listOf(2, 3, 4),
            ),
            // ── 学期前 ──
            WeekQuadrantCell(
                name = "周一开学 + 首周课次在学期前（开学前那一周两边都给 0，整周丢掉）",
                semesterStart = LocalDate.of(2026, 9, 7),
                dtStart = "20260831", until = "20260914T235959", weekly = true, maxWeeks = 25,
                occurrenceDates = listOf(
                    LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 14),
                ),
                expectedWeeks = listOf(1, 2),
            ),
            WeekQuadrantCell(
                name = "周三开学 + 课次落在 mondayOf(开学日) 与开学日之间（改前判 0 ⇒ 整门课静默消失；改后进第 1 周）",
                semesterStart = LocalDate.of(2026, 9, 9),
                dtStart = "20260907", until = null, weekly = false, maxWeeks = 25,
                occurrenceDates = listOf(LocalDate.of(2026, 9, 7)),
                expectedWeeks = listOf(1),
            ),
            // ── 学期外 ──
            WeekQuadrantCell(
                name = "周一开学 + 课次越过周次上界（两边同判学期外，整条不入库）",
                semesterStart = LocalDate.of(2026, 9, 7),
                dtStart = "20261005", until = null, weekly = false, maxWeeks = 3,
                occurrenceDates = listOf(LocalDate.of(2026, 10, 5)),
                expectedWeeks = emptyList(),
            ),
            WeekQuadrantCell(
                name = "周三开学 + 课次本来越界（改前算第 3 周挤进界内，改后与全仓一致算第 4 周被裁掉）",
                semesterStart = LocalDate.of(2026, 9, 9),
                dtStart = "20260928", until = null, weekly = false, maxWeeks = 3,
                occurrenceDates = listOf(LocalDate.of(2026, 9, 28)),
                expectedWeeks = emptyList(),
            ),
        )
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
