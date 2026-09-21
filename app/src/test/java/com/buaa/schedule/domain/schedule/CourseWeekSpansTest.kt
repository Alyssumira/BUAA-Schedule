package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CourseWeekSpans] 的周次覆盖口径（统计页 Gantt 式的判据内核）。
 *
 * 钉四件事：
 * 1. 一行 = 一门课，不是一条排课片段 —— 同组两段的周次取并集，不各画一条；
 * 2. 单周 / 双周 / 离散周次不许被"顺手并成"连续段（并了就等于说这门课整段都在上）；
 * 3. **没有周次数据 ≠ 整学期没课**：这类条目要能被界面认出来，而不是画一条空轨；
 * 4. 当前周拿不到时不下"已结课"的结论，排序也不许随传入顺序变。
 */
class CourseWeekSpansTest {

    private val semester = Semester(
        termCode = "2026-2027-1",
        termName = "2026-2027-1",
        startDate = "2026-09-07",
        totalWeeks = 16,
    )

    private fun course(
        name: String,
        groupKey: String? = null,
        weeks: List<Int> = (1..16).toList(),
        dayOfWeek: Int = 1,
        periods: List<Int> = listOf(1, 2),
        alias: String? = null,
    ) = Course(
        name = name,
        alias = alias,
        dayOfWeek = dayOfWeek,
        periods = periods,
        weeks = weeks,
        sourceGroupKey = groupKey,
        semesterCode = semester.termCode,
    )

    /** 断言里只关心组键的后半段（前缀是学期码，见 [SemesterStats.courseGroupKey]） */
    private fun groupOf(coverage: CourseWeekSpans.Coverage): String = coverage.groupKey.substringAfter('|')

    // ---- 归并口径 ----

    @Test
    fun sameGroupFragmentsMergeIntoOneRowWithWeekUnion() {
        val board = CourseWeekSpans.board(
            listOf(
                course("高等数学", "g1", weeks = (1..8).toList()),
                course("高等数学", "g1", weeks = (9..16).toList(), dayOfWeek = 3),
            ),
            semester,
            currentWeek = 5,
        )

        assertEquals("同组两段是一门课，不许排成两行", 1, board.rows.size)
        val row = board.rows.first()
        assertEquals(2, row.fragmentCount)
        // 周次并集是连续的 1..16，两段各自一根条就会把一门课读成两门
        assertEquals(listOf(1..16), row.spans)
        assertEquals(1, row.firstWeek)
        assertEquals(16, row.lastWeek)
    }

    @Test
    fun differentGroupsStayOnSeparateRows() {
        val board = CourseWeekSpans.board(
            listOf(
                course("高等数学", "g1", weeks = (1..8).toList()),
                course("大学物理", "g2", weeks = (9..16).toList()),
            ),
            semester,
            currentWeek = 5,
        )

        assertEquals(2, board.rows.size)
        // 结课周小的在前：第 8 周结课的那门排在第 16 周的那门前面
        assertEquals(listOf("高等数学", "大学物理"), board.rows.map { it.label })
    }

    // ---- 非连续周次 ----

    @Test
    fun oddWeekCourseStaysAsSeparateSpans() {
        val spans = CourseWeekSpans.spansOf(listOf(1, 3, 5, 7))

        assertEquals("单周课并成 1..7 就是在撒谎", listOf(1..1, 3..3, 5..5, 7..7), spans)
    }

    @Test
    fun mixedEvenWeeksMergeOnlyWhereConsecutive() {
        assertEquals(
            listOf(2..2, 4..6, 10..10),
            CourseWeekSpans.spansOf(listOf(10, 4, 5, 2, 6, 4)),
        )
    }

    @Test
    fun unsortedInputAndDuplicatesDoNotChangeSpans() {
        val shuffled = CourseWeekSpans.spansOf(listOf(5, 1, 3, 4, 2, 3))
        assertEquals(listOf(1..5), shuffled)
        assertEquals(emptyList<IntRange>(), CourseWeekSpans.spansOf(emptyList()))
    }

    @Test
    fun sparseWeeksBecomeOneSpanPerRun() {
        val board = CourseWeekSpans.board(
            listOf(course("体育选项", "pe", weeks = listOf(2, 3, 4, 11, 12))),
            semester,
            currentWeek = 1,
        )

        assertEquals(listOf(2..4, 11..12), board.rows.single().spans)
        assertEquals(2, board.rows.single().firstWeek)
        assertEquals(12, board.rows.single().lastWeek)
    }

    // ---- 空数据 / 脏数据 ----

    @Test
    fun emptyCourseListYieldsEmptyBoard() {
        val board = CourseWeekSpans.board(emptyList(), semester, currentWeek = 3)

        assertTrue(board.isEmpty)
        assertEquals(0, board.knownCount)
        assertEquals(16, board.totalWeeks)
        assertNull(board.nextToEnding)
    }

    @Test
    fun missingSemesterFallsBackToMaxWeekInData() {
        val board = CourseWeekSpans.board(
            listOf(course("无学期行", "x", weeks = listOf(3, 7, 9))),
            semester = null,
            currentWeek = null,
        )

        // 横轴长度与 SemesterStats 同一口径：没有学期行就用数据里出现过的最大周次
        assertEquals(9, board.totalWeeks)
        assertEquals(listOf(3..3, 7..7, 9..9), board.rows.single().spans)
    }

    @Test
    fun courseWithoutUsableWeeksIsMarkedUnknownNotEmpty() {
        val board = CourseWeekSpans.board(
            listOf(
                course("周次没填", "a", weeks = emptyList()),
                course("周次越界", "b", weeks = listOf(40, 41)),
                course("正常课", "c", weeks = (1..16).toList()),
            ),
            semester,
            currentWeek = 6,
        )

        val unknown = board.rows.filter { it.weeksUnknown }
        assertEquals(2, board.unknownCount)
        assertEquals(2, unknown.size)
        // 这两条画不出横条：spans 为空，界面据 weeksUnknown 画"周次未知"而不是空轨道
        assertTrue(unknown.all { it.spans.isEmpty() && it.firstWeek == null && it.lastWeek == null })
        assertFalse(unknown.any { it.finished })
        assertEquals(1, board.knownCount)
        // 未知的一律垫到最后，别混在"往下扫就是剩下的学期"那条线里
        assertEquals(listOf("c", "a", "b"), board.rows.map { groupOf(it) })
    }

    @Test
    fun outOfRangeWeeksAreClippedToSemesterAxis() {
        val board = CourseWeekSpans.board(
            listOf(course("跨学期脏数据", "d", weeks = (1..20).toList())),
            semester,
            currentWeek = 2,
        )

        val row = board.rows.single()
        assertEquals(listOf(1..16), row.spans)
        assertEquals("越界的 17-20 周不算结课周", 16, row.lastWeek)
        assertFalse(row.finished)
    }

    @Test
    fun dirtyWeekNumbersDoNotCrashAndAreIgnored() {
        val board = CourseWeekSpans.board(
            listOf(course("带 0 和负数", "e", weeks = listOf(-3, 0, 5, 5, 6))),
            semester,
            currentWeek = 1,
        )

        assertEquals(listOf(5..6), board.rows.single().spans)
    }

    // ---- 当前周相关的结论 ----

    @Test
    fun finishedFlagOnlyWhenCurrentWeekIsKnown() {
        val courses = listOf(course("上半程课", "f", weeks = (1..8).toList()))

        val inWeek12 = CourseWeekSpans.board(courses, semester, currentWeek = 12)
        assertTrue("第 12 周时第 8 周结课的课该标成已结课", inWeek12.rows.single().finished)
        assertEquals(1, inWeek12.finishedCount)

        val noAnchor = CourseWeekSpans.board(courses, semester, currentWeek = null)
        assertFalse("拿不到当前周就不下『已结课』的结论", noAnchor.rows.single().finished)
        assertEquals(0, noAnchor.finishedCount)
        assertNull(noAnchor.nextToEnding)

        val week4 = CourseWeekSpans.board(courses, semester, currentWeek = 4)
        assertFalse("还在上的课不算结课", week4.rows.single().finished)
    }

    @Test
    fun nextToEndingPicksTheEarliestUpcomingFinish() {
        val board = CourseWeekSpans.board(
            listOf(
                course("已经结了的", "g", weeks = (1..4).toList()),
                course("第 12 周结课", "h", weeks = (1..12).toList()),
                course("整学期", "i", weeks = (1..16).toList()),
                course("周次未知", "j", weeks = emptyList()),
            ),
            semester,
            currentWeek = 6,
        )

        assertEquals("h", board.nextToEnding?.let { groupOf(it) })
        // 往后挪：第 13 周时"第 12 周结课"的也过去了，剩下整学期那门
        assertEquals("i", CourseWeekSpans.board(
            listOf(
                course("已经结了的", "g", weeks = (1..4).toList()),
                course("第 12 周结课", "h", weeks = (1..12).toList()),
                course("整学期", "i", weeks = (1..16).toList()),
            ),
            semester,
            currentWeek = 13,
        ).nextToEnding?.let { groupOf(it) })
    }

    @Test
    fun boardSortsByFinishThenStartAndIgnoresInputOrder() {
        val early = course("早结课", "k", weeks = (1..6).toList())
        val late = course("晚结课", "l", weeks = (1..15).toList())
        val midStart = course("后半程", "m", weeks = (9..15).toList())
        val forward = CourseWeekSpans.board(listOf(early, late, midStart), semester, currentWeek = 2)
        val backward = CourseWeekSpans.board(listOf(midStart, late, early), semester, currentWeek = 2)

        assertEquals(
            listOf("k", "l", "m"),
            forward.rows.map { groupOf(it) },
        )
        assertEquals("同结课周按开课周分先后，且与传入顺序无关", forward.rows, backward.rows)
    }

    @Test
    fun aliasWinsOverOriginalName() {
        val board = CourseWeekSpans.board(
            listOf(course("MAT1001 高等数学（A）", "n", alias = "高数", weeks = (1..4).toList())),
            semester,
            currentWeek = 1,
        )

        assertEquals("高数", board.rows.single().label)
    }
}
