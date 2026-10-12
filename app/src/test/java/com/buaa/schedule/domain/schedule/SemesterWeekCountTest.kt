package com.buaa.schedule.domain.schedule

import com.buaa.schedule.core.designsystem.weekCourseCounts
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.ui.home.weekChoicesFor
import com.buaa.schedule.ui.home.weekCountsOf
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SemesterWeekCount]（「这学期多少周」的全仓唯一**具名读数**）的表驱动单测（T332③，钉 T331⓪①②）。
 *
 * ## 期望值的纪律（本仓反复犯的那两病，这里逐格避开）
 * 期望值一律**手写死**：测试里不出现 `coerceIn` / `takeIf { it > 0 }` 那一式的第二份实现，
 * 也不拿被测函数自己的返回值当期望（自证）。每一格的右边那一列都是人算出来的数，
 * 写在 `scene` 里的是它为什么是该数。读数本体（[SemesterWeekCount.readable]）在测试里只出现在
 * **实到**那一侧 —— 除了册子第③列（顶栏读数）那一格，那里它是被递进下一把尺的**输入**，
 * 而期望是下一把尺物化出来的格数，见 [fourRulersAnswerTheRegisteredLedger] 的注释。
 *
 * ## 三件事
 * 1. [readableAnswersItsOwnTable] + [readableNeverInventsAWeekCount] —— 读数本体的判据：
 *    没学期行 / 0 / 负数 ⇒ null（「这一档压根没有这枚事实」），1..30 ⇒ 原值，31 与更大 ⇒ 30。
 * 2. [fourRulersAnswerTheRegisteredLedger] —— 内核 KDoc 那张**差异册子**的可执行版：同一批输入
 *    喂给①展示尺 [SemesterStats.weekAxisLength]、②判据尺 [TermWeekGate.isInTermWeek]、
 *    ③顶栏读数（+ 它下游物化的密度条格数）、④跳周清单 [weekChoicesFor]、⑤Pager 页数与分母
 *    [weekCountsOf]，逐格断言。册子的**行号**写在每一格的 `scene` 里（第 1..4 行），
 *    两格「故意不同向」的账（①有课程周号兜底 / ②没有；②不夹上界）由
 *    [displayRulerAndGateMustKeepAnsweringDifferently] 单独钉死，抹平就红。
 * 3. [dirtyRowNoLongerOpensADoorTheGateDenies] —— ② 那半档行为变更的病灶格（在册 #452）：
 *    跳周清单里点得开的那一周，必须是判据尺认的那一周；脏学期行那一档改前清单是 `[1]`、
 *    改后是空，而判据尺改前改后同答「一周都不在学期内」。
 *
 * ## 输入统一（照内核 KDoc 册子那一段的口径）
 * 开学日 = 2026-09-07（那一天的的确确是周一：`WeekCalculator.mondayOf` 归一后仍是它自己，
 * 于是 `isInTermWeek(w, 2026-09-07, bound)` 在这批格子里退化成 `1 <= w <= bound`，可手算）；
 * 课程 = 一门只上到第 8 周的课（`weeks = 1..8`）。
 *
 * 纯 JVM：零 android import、不起 Robolectric、不读钟、不碰设备（本仓收单硬口径）。
 * 生产接线那一半不在这里，在 `SemesterWeekCountWiringGuardTest`（T332④）。
 */
class SemesterWeekCountTest {

    // ─────────────── ① 读数本体：判据逐格对号 ───────────────

    private data class ReadableRow(
        val scene: String,
        val totalWeeks: Int?,
        val expected: Int?,
    )

    @Test
    fun readableAnswersItsOwnTable() {
        val table = listOf(
            ReadableRow(
                "没有学期行可问 ⇒ null：这一档压根没有「这学期多少周」这枚事实（册子第 1 行）",
                null,
                null,
            ),
            ReadableRow(
                "**在册 #452 那一格**：学期行 = 0 ⇒ null。改前顶栏 `0.coerceIn(1, 30)` 折成 1、" +
                    "清单真能点出「第 1 周」，而判据尺对同一学期一周都不放行（册子第 2 行）",
                0,
                null,
            ),
            ReadableRow("学期行 = -5（负数脏值）⇒ 同一档 null，不许一个折成 1、另一个原样漏下去", -5, null),
            ReadableRow("学期行 = Int.MIN_VALUE ⇒ null（不许绕成别的意思，也不许折成正数）", Int.MIN_VALUE, null),
            ReadableRow("合法下界 1 ⇒ 原样 1（闭区间的下端不许多削）", 1, 1),
            ReadableRow("正常档 16 ⇒ 原样 16（册子第 3 行：这一档四把尺逐格不许变）", 16, 16),
            ReadableRow("合法上界 30 ⇒ 原样 30（夹取是闭区间，别把边界那格削成 29）", 30, 30),
            ReadableRow("上界外第一格 31 ⇒ 30", 31, 30),
            ReadableRow("999 ⇒ 30（「兼容历史脏数据：总周数限幅，避免跳周列表物化超大列表」那笔账，册子第 4 行）", 999, 30),
            ReadableRow(
                "Int.MAX_VALUE ⇒ 30 —— ⚠️ 正因为这一格会夹，判据尺 TermWeekGate 不许读这枚读数：" +
                    "它的 UNBOUNDED_TOTAL_WEEKS 就是 Int.MAX_VALUE，一夹就把「明说不判上界」变成 30 周",
                Int.MAX_VALUE,
                30,
            ),
        )
        for (row in table) {
            val actual = SemesterWeekCount.readable(row.totalWeeks)
            assertEquals("${row.scene} —— 实到 $actual", row.expected, actual)
        }
    }

    @Test
    fun readableNeverInventsAWeekCount() {
        // 「零造值」的实数版：非 null 的答案只能来自参数本身（合法档恒等），
        // 越上界只许落到 30 那一格，脏值只许答 null —— 三个方向各钉一排，一格都不许换写法。
        for (weeks in listOf(1, 2, 7, 16, 20, 30)) {
            assertEquals("合法档 $weeks 不许被改写（30 是全仓上界，但 20 周就是 20 周）", weeks, SemesterWeekCount.readable(weeks))
        }
        for (weeks in listOf(31, 32, 60, 999, Int.MAX_VALUE)) {
            assertEquals("上界外 $weeks 必须落在 30 这一格", 30, SemesterWeekCount.readable(weeks))
        }
        for (weeks in listOf<Int?>(0, -1, -999, Int.MIN_VALUE, null)) {
            assertNull("脏值 $weeks 被折成了一枚周数：那枚「这学期多少周」就是读数自己造的", SemesterWeekCount.readable(weeks))
        }
    }

    // ─────────────── ② 差异册子：同一批输入，四把尺 + 第五处落点 ───────────────

    /**
     * 一格 = 一枚学期行读数（null = 没有学期行），六枚答案：
     * ① 展示尺 [display]；② 判据尺对第 1 周 [gateWeek1] 与对这批课的最后一周第 8 周
     * [gateLastCourseWeek]；③ 顶栏读数 [toolbar] 与它下游物化的密度条格数 [toolbarCells]；
     * ④ 跳周清单 [choices]；⑤ Pager 页数 [pagerPages] 与「不是每周都有」的分母 [denominator]。
     *
     * ③ 那一列说清楚（免得被读成自证）：`ui/home/HomeScreen.kt:701-702` 递给工具条的实参
     * **就是** [SemesterWeekCount.readable] 的答案本体，工具条自己没有第二把尺 ⇒
     * 「读数 → 工具条」那一跳由静态守卫按源码形状钉（`SemesterWeekCountWiringGuardTest` 第 ② 层），
     * 这张表里 [toolbar] 只是把册子第③列为对账而抄全；**能让这一格变红的是 [toolbarCells]** ——
     * 它是工具条下游 `weekCourseCounts(courseWeeks, totalWeeks ?: 0)`（`HomeScreen.kt:1052-1053`）
     * 物化出的格数，另一枚函数、另一把尺，期望值 0 / 1 / 8 / 16 / 30 全是手算的。
     *
     * ⚠️ 清单与分母两列吃的是 `hasSemester`（= 有没有学期行），与首页 `state.semester != null` 同源：
     * 脏学期行**有**学期行，所以 hasSemester 为真而读数取不到 —— 正是 T331 收的那一格。
     */
    private data class LedgerRow(
        val scene: String,
        val totalWeeks: Int?,
        val display: Int,
        val gateWeek1: Boolean,
        val gateLastCourseWeek: Boolean,
        val toolbar: Int?,
        val toolbarCells: Int,
        val choices: List<Int>,
        val pagerPages: Int,
        val denominator: Int?,
    )

    @Test
    fun fourRulersAnswerTheRegisteredLedger() {
        val courses = listOf(courseWithWeeks((1..8).toList()))
        val table = listOf(
            LedgerRow(
                "**册子第 1 行｜没有学期行**：① 展示尺退到课程最大周号 8（兜底）；② 判据尺两格都 false" +
                    "（判不出 ⇒ 不放行，它没有兜底）；③ 顶栏 null、密度条 0 格；④ 清单空；⑤ Pager 8 页、分母 8。" +
                    "T197 已收这一档，改前改后逐格同值",
                null, 8, false, false, null, 0, emptyList(), 8, 8,
            ),
            LedgerRow(
                "**册子第 2 行｜学期行 = 0（在册 #452，丙档那条链的入口）**：① ② 与第 1 行同答案" +
                    "（兜底同源 / 判据尺照旧一周不放行）；变的是 ③ 与 ④ —— " +
                    "**改前** ③ = 1、③′ = 1 格、④ = [1]、⑤ 分母 = 1；**改后** ③④⑤ 全改答「没有这枚事实」",
                0, 8, false, false, null, 0, emptyList(), 1, null,
            ),
            LedgerRow(
                "学期行 = -5（册子第 2 行的同一档，值不同形）：六枚答案必须与 0 那一行逐格相同",
                -5, 8, false, false, null, 0, emptyList(), 1, null,
            ),
            LedgerRow(
                "学期行 = 1：四把尺第一次同向 —— ① 1、② 第 1 周放行而第 8 周不放行、③ 1（1 格）、④ [1]、⑤ 1/1。" +
                    "清单点得到的那一周判据尺认，门是真的（册子外的一格，收 #452 的正面）",
                1, 1, true, false, 1, 1, listOf(1), 1, 1,
            ),
            LedgerRow(
                "学期行 = 8（恰好等于课程最大周号：兜底与学期行给同一个数的那一格）",
                8, 8, true, true, 8, 8, (1..8).toList(), 8, 8,
            ),
            LedgerRow(
                "**册子第 3 行｜学期行 = 16（正常档，逐格不许变）**：① 16｜② 1..16 放行｜③ 16（16 格）｜④ 1..16｜⑤ 16/16",
                16, 16, true, true, 16, 16, (1..16).toList(), 16, 16,
            ),
            LedgerRow(
                "学期行 = 30（合法上界那一格：闭区间，④ 必须是全长 30 行而不是 29 行）",
                30, 30, true, true, 30, 30, (1..30).toList(), 30, 30,
            ),
            LedgerRow(
                "**册子第 4 行｜学期行 = 999**：① ③ ④ ⑤ 都夹到 30，而 ② 判据尺把第 8 周放行、" +
                    "第 31 周也放行（它不夹上界）⇒ 册子里第二处**故意**不同向，本卡不动它，" +
                    "理由见内核 KDoc 那段 ⚠️（排铃/冲突/导出三条链的账归 #397）",
                999, 30, true, true, 30, 30, (1..30).toList(), 30, 30,
            ),
        )
        for (row in table) {
            val semester = row.totalWeeks?.let { semesterWith(totalWeeks = it) }
            val start = semester?.let { LocalDate.parse(it.startDate) }
            val toolbar = SemesterWeekCount.readable(row.totalWeeks)

            assertEquals("${row.scene} —— ① 展示尺 weekAxisLength", row.display, SemesterStats.weekAxisLength(courses, semester))
            assertEquals("${row.scene} —— ② 判据尺 isInTermWeek(第 1 周)", row.gateWeek1, TermWeekGate.isInTermWeek(1, start, row.totalWeeks))
            assertEquals(
                "${row.scene} —— ② 判据尺 isInTermWeek(这批课的最后一周 = 第 8 周)",
                row.gateLastCourseWeek,
                TermWeekGate.isInTermWeek(8, start, row.totalWeeks),
            )
            assertEquals("${row.scene} —— ③ 顶栏读数（HomeScreen 递进工具条的那枚实参）", row.toolbar, toolbar)
            assertEquals(
                "${row.scene} —— ③′ 工具条下游 weekCourseCounts 物化的格数（改前/改后见 scene）",
                row.toolbarCells,
                weekCourseCounts(listOf((1..8).toSet()), toolbar ?: 0).size,
            )
            assertEquals(
                "${row.scene} —— ④ 跳周清单 weekChoicesFor",
                row.choices,
                weekChoicesFor(hasSemester = row.totalWeeks != null, totalWeeks = row.totalWeeks),
            )
            val (pagerPages, denominator) = weekCountsOf(
                hasSemester = row.totalWeeks != null,
                semesterTotalWeeks = row.totalWeeks,
                courses = courses,
            )
            assertEquals("${row.scene} —— ⑤ Pager 页数", row.pagerPages, pagerPages)
            if (row.denominator == null) {
                assertNull("${row.scene} —— ⑤ 分母实到 $denominator（期望 null ⇒ 「不是每周都有」那枚旗不亮）", denominator)
            } else {
                assertEquals("${row.scene} —— ⑤ 分母", row.denominator, denominator)
            }
        }
    }

    // ─────────────── ③ #452 那一扇门：清单与判据尺必须同向 ───────────────

    @Test
    fun dirtyRowNoLongerOpensADoorTheGateDenies() {
        // 这一档的病灶就是「清单能点到一扇判据尺说没有的门」（在册 #452）。
        // 改前：学期行 0 ⇒ weekChoicesFor 交回 [1]，屏上真列一行「第 1 周」、真点得下去，
        //       而 TermWeekGate.isInTermWeek(1, 开学日, 0) 改前改后都答 false —— 门开在没有的那一周上。
        // 改后：脏学期行（0 / -1 / -5）三档清单都为空 ⇒ 一扇都开不出来；判据尺同答不放行。
        val start = LocalDate.parse(SEMESTER_START)
        for (dirty in listOf(0, -1, -5)) {
            val choices = weekChoicesFor(hasSemester = true, totalWeeks = dirty)
            assertTrue("脏学期行 $dirty 的清单不为空（实到 $choices）：改前这里是 [1]，那扇门判据尺说没有", choices.isEmpty())
            assertFalse("脏学期行 $dirty 却放行第 1 周（判据尺跟着清单一起放宽就是抹平册子）", TermWeekGate.isInTermWeek(1, start, dirty))
        }
        // 正面那一格：清单点得开的第 1 周，判据尺必须也放行第 1 周 —— 两把尺同向才叫收口
        val oneWeek = weekChoicesFor(hasSemester = true, totalWeeks = 1)
        assertEquals(listOf(1), oneWeek)
        assertTrue(
            "清单能点到第 1 周而判据尺不放行 —— 两把尺又分家了（#452 的反方向那一档）",
            TermWeekGate.isInTermWeek(1, start, 1),
        )
    }

    // ─────────────── ④ 册子里那两格「故意不同向」：不许被抹平成一把尺 ───────────────

    @Test
    fun displayRulerAndGateMustKeepAnsweringDifferently() {
        val courses = listOf(courseWithWeeks((1..8).toList()))
        val broken = semesterWith(totalWeeks = 0)

        // 反向格一（册子第 1、2 行）：① 有课程周号兜底、② 没有。
        // 展示侧画横轴时「没学期行 / 学期行是脏的」也要画得出这批课真的上过的 8 周 ——
        // ui/home/WeekView.kt 的 showAllCoursesWithoutWeek = semester == null 把课全画出来，是同一枚兜底的下游；
        // 判据侧宁可判不出也不放行才是它的第三条口径。谁把两把抹平成一把，这一格当场红。
        assertEquals(
            "册子第 2 行：① 展示尺在脏学期行那一格不该跟着判据尺答「没有这一周」——横轴仍要画得出这批课真的上过的 8 周",
            8,
            SemesterStats.weekAxisLength(courses, broken),
        )
        assertFalse(
            "册子第 2 行：② 判据尺在脏学期行那一格不该跟着展示尺放行第 8 周——判不出就不放行是它的口径",
            TermWeekGate.isInTermWeek(8, LocalDate.parse(SEMESTER_START), broken.totalWeeks),
        )

        // 反向格二（册子第 4 行）：② 不夹上界。UNBOUNDED_TOTAL_WEEKS = Int.MAX_VALUE 一经夹取就变 30，
        // widget 与 reminder 那几处既有口径会被这枚读数改掉 ⇒ 读数刻意不铺到那一侧（#397/#398/#404 的账）。
        assertEquals("册子第 4 行：① 展示尺把 999 夹到 30", 30, SemesterStats.weekAxisLength(courses, semesterWith(totalWeeks = 999)))
        assertTrue(
            "册子第 4 行：② 判据尺不许把上界夹住 —— 第 31 周在 999 周的学期里照样放行；" +
                "它一旦改读 SemesterWeekCount.readable，这一格与 widget/reminder 那几处会一起改口",
            TermWeekGate.isInTermWeek(31, LocalDate.parse(SEMESTER_START), 999),
        )
    }

    // ─────────────── ⑤ 展示尺那枚留在原地的夹取：防的是课程周号，不是学期行 ───────────────

    @Test
    fun displayRulerStillClampsTheCourseWeekFallback() {
        // T331② 换源时 SemesterStats.weekAxisLength 末尾那句 coerceIn(1, MAX_TOTAL_WEEKS) **故意没删**：
        // 读数只担保「学期行」那一档落在 1..30，退路（课程周号的最大值）可能越界（教务脏数据给到第 45 周），
        // 那一夹防的是它。删掉它 = 横轴长度能长出 45 格，与统计页那句「共 N 周」、三张图各说各话。
        val wild = listOf(courseWithWeeks((1..45).toList()))
        assertEquals("没有学期行 ⇒ 退到课程最大周号 45，再夹到 30", 30, SemesterStats.weekAxisLength(wild, null))
        assertEquals("学期行是脏的 0 ⇒ 同一枚退路，同答 30", 30, SemesterStats.weekAxisLength(wild, semesterWith(totalWeeks = 0)))
        assertEquals(
            "学期行 16 合法 ⇒ 课程越界到 45 也不作数：展示尺跟着学期行走（读数答 16，末尾那一夹对 16 是恒等）",
            16,
            SemesterStats.weekAxisLength(wild, semesterWith(totalWeeks = 16)),
        )
        assertEquals("学期行 999 脏得离谱 ⇒ 读数先夹到 30", 30, SemesterStats.weekAxisLength(wild, semesterWith(totalWeeks = 999)))
    }

    // ---------------- helpers ----------------

    private companion object {
        const val SEMESTER_START = "2026-09-07" // 周一（册子的输入口径）
    }

    private fun semesterWith(totalWeeks: Int) = Semester(
        termCode = "2026-2027-1",
        termName = "2026-2027-1",
        startDate = SEMESTER_START,
        totalWeeks = totalWeeks,
    )

    private fun courseWithWeeks(weeks: List<Int>) = Course(
        id = 1L,
        name = "册子用的课",
        teacher = "张三",
        location = "J3-101",
        dayOfWeek = 1,
        periods = listOf(1, 2),
        weeks = weeks,
        semesterCode = "2026-2027-1",
    )
}
