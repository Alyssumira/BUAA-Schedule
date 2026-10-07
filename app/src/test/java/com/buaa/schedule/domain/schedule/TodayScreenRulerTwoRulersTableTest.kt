package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * T266②：#344 那枚「同屏三把尺」的分档表（纯 JVM 表驱动，零 android 运行面、零装机）。
 *
 * ## 为什么是一张表而不是一句断言
 *
 * 病灶不是「算错了」，是**同一屏上三处各读一把尺**：列表读星期几、Hero 读学期＋周次、
 * 门读列表。单独钉一句文案永远只钉得住那一句（这一族在本仓栽过：#398「四把尺各自被钉子钉住、
 * 跨尺合取全仓零握」、T224 的「门/段」四本账）。所以这里把每一档都摆成一行，
 * 并且**两侧都要有对照格**：
 *
 * - 朝宽（两把尺并成一把＝听星期几的）：会错哪一档 → 假期 / `totalWeeks = 0` 那一格会画出课。
 * - 朝窄（门按学期那把尺收）：会吞掉哪一档 → 没设学期、库里真有 14:00 那一节课的那格会连 Hero
 *   带卡一起消失（那是改前 :605 那档自己写明的产品意图）。
 *
 * ⚠️ 「句子与卡是否真的同屏」要装机静止帧才判得实（本卡零 adb）——本表钉的是
 * **三处吃同一枚结论**这件事，屏上读数归编排者。
 */
class TodayScreenRulerTwoRulersTableTest {

    // ─────────────── fixtures ───────────────

    /** 库里 14:00 那节课：周三第 5-6 节，本卡的重点格就靠它 */
    private val afternoon = Course(
        name = "编译原理",
        teacher = "李老师",
        location = "F102",
        dayOfWeek = 3,
        periods = listOf(5, 6),
        weeks = (1..16).toList(),
    )
    private val morning = Course(
        name = "体育",
        teacher = "王老师",
        location = "操场",
        dayOfWeek = 3,
        periods = listOf(1, 2),
        weeks = listOf(2, 4, 6),
    )

    /** 只在星期几那把尺上命中的两门（周三：两门都在） */
    private val weekdayCourses = listOf(morning, afternoon)

    /** 学期＋周次那把尺在第 1 周只命中 14:00 那门（体育是双周） */
    private val termWeekCoursesFirstWeek = listOf(afternoon)

    private fun slot(course: Course, status: SlotStatus) = TodayCourseSlot(
        course = course,
        start = LocalTime.of(14, 0),
        end = LocalTime.of(15, 40),
        segment = 5..6,
        status = status,
    )

    private val upcoming = slot(afternoon, SlotStatus.UPCOMING)
    private val ongoing = slot(afternoon, SlotStatus.ONGOING)
    private val past = slot(afternoon, SlotStatus.PAST)

    /** 当前时刻落点由 TodayPlanner 算好后交回：这里手工摆同一形状，内核不读钟 */
    private fun planWithNext(minutes: Long?) = TodayPlan(
        slots = listOf(upcoming),
        ongoing = null,
        next = upcoming,
        minutesToNext = minutes,
        minutesRemaining = null,
    )

    private fun planWithOngoing(minutes: Long?) = TodayPlan(
        slots = listOf(ongoing),
        ongoing = ongoing,
        next = null,
        minutesToNext = null,
        minutesRemaining = minutes,
    )

    private fun planAllPast() = TodayPlan(
        slots = listOf(past),
        ongoing = null,
        next = null,
        minutesToNext = null,
        minutesRemaining = null,
    )

    private fun planNoSlots() = TodayPlan(emptyList(), null, null, null, null)

    // ─────────────── ① 走哪把尺：有没有学期 × 那一周取不取得到 ───────────────

    private data class ScopeRow(
        val scene: String,
        val hasSemester: Boolean,
        val termWeek: Int?,
        val expected: DayCourseScope,
    )

    @Test
    fun `走哪把尺逐格对号 没学期退到星期几 那一周取不到两把都不放行`() {
        val table = listOf(
            ScopeRow("有学期 + 那一周取得到 ⇒ 学期＋周次那把尺（正常档不许动）", true, 1, DayCourseScope.TermWeek(1)),
            ScopeRow("有学期 + 第 16 周 ⇒ 周号原样交回，内核不夹取不重述比较式", true, 16, DayCourseScope.TermWeek(16)),
            // 朝宽侧的哨兵格：并成「星期几」一把就会在这一格画出假课
            ScopeRow("有学期 + 假期里（那一周 null）⇒ OutOfTerm，不许听星期几那把宽尺", true, null, DayCourseScope.OutOfTerm),
            ScopeRow("有学期 + 开学前（那一周 null）⇒ 同一档", true, null, DayCourseScope.OutOfTerm),
            ScopeRow("有学期 + 开学日解析失败（旧库脏数据）⇒ 同一档，与同屏下一格同形状", true, null, DayCourseScope.OutOfTerm),
            ScopeRow("有学期 + totalWeeks = 0（在册 #452 隔壁洞）⇒ 仍 OutOfTerm，本卡零改动", true, null, DayCourseScope.OutOfTerm),
            // 朝窄侧的哨兵格：门跟着学期那把尺收就会在这一格吞掉用户的课
            ScopeRow("没学期 ⇒ WeekdayOnly，手动课对新用户仍可见（改前 :605 的产品意图）", false, null, DayCourseScope.WeekdayOnly),
        )
        for (row in table) {
            assertEquals(
                row.scene,
                row.expected,
                TodayScreenRuler.scopeOf(hasSemester = row.hasSemester, termWeek = row.termWeek),
            )
        }
    }

    @Test
    fun `同一批数据 有学期与没学期两格只差在走哪把尺`() {
        // 两枚输入事实（学期在不在 / 那一周取不取得到）互换时答案必须互换，
        // 这是「一屏一把尺」的实数版：内核不会把两把尺并着答，也不会把一把折成布尔
        val withSemesterButNoWeek = TodayScreenRuler.scopeOf(hasSemester = true, termWeek = null)
        val withoutSemester = TodayScreenRuler.scopeOf(hasSemester = false, termWeek = null)
        assertEquals(DayCourseScope.OutOfTerm, withSemesterButNoWeek)
        assertEquals(DayCourseScope.WeekdayOnly, withoutSemester)
        assertEquals(
            emptyList<Course>(),
            TodayScreenRuler.coursesFor(withSemesterButNoWeek, weekdayCourses, termWeekCoursesFirstWeek),
        )
        assertEquals(
            weekdayCourses,
            TodayScreenRuler.coursesFor(withoutSemester, weekdayCourses, termWeekCoursesFirstWeek),
        )
    }

    // ─────────────── ② 列表那几张卡：内核按尺取一份 ───────────────

    @Test
    fun `列表按内核选定的那把尺取 学期尺只交回那一周命中的`() {
        assertEquals(
            termWeekCoursesFirstWeek,
            TodayScreenRuler.coursesFor(DayCourseScope.TermWeek(1), weekdayCourses, termWeekCoursesFirstWeek),
        )
        assertEquals(
            weekdayCourses,
            TodayScreenRuler.coursesFor(DayCourseScope.WeekdayOnly, weekdayCourses, termWeekCoursesFirstWeek),
        )
        assertTrue(
            "OutOfTerm 那一格列表必须收干净——假期里画出来的课就是 Hero 与列表打架的那一手",
            TodayScreenRuler.coursesFor(DayCourseScope.OutOfTerm, weekdayCourses, termWeekCoursesFirstWeek).isEmpty(),
        )
    }

    @Test
    fun `列表取的是同一批对象 不是另一把尺重算的副本`() {
        // 门与列表同源的前提：内核交回的是入参那一份引用，没有第三次过滤、没有重排。
        // 一旦有人把 coursesFor 改回 filter／sortedBy，这里拿到的是新对象，同一格就红。
        assertSame(
            weekdayCourses,
            TodayScreenRuler.coursesFor(DayCourseScope.WeekdayOnly, weekdayCourses, termWeekCoursesFirstWeek),
        )
        assertSame(
            termWeekCoursesFirstWeek,
            TodayScreenRuler.coursesFor(DayCourseScope.TermWeek(1), weekdayCourses, termWeekCoursesFirstWeek),
        )
        assertTrue(
            TodayScreenRuler.coursesFor(DayCourseScope.OutOfTerm, weekdayCourses, termWeekCoursesFirstWeek).isEmpty(),
        )
    }

    // ─────────────── ③ 门 + Hero 念哪句：重点格与两侧格子 ───────────────

    @Test
    fun `重点格 有课 无学期 当前时刻在课之前 门开着且念下一节`() {
        // 这一格就是卡面上那句谎话被压住的位置：库里真有 14:00 那节课、用户只是没设学期。
        // 修完之后：列表 2 张卡（星期几那把尺）、Hero 念「下一节 编译原理 · 25 分钟后开始」、
        // 「今天没有更多课了」不出现。
        val verdict = TodayScreenRuler.heroGateOf(
            scope = DayCourseScope.WeekdayOnly,
            isToday = true,
            hasCards = true,
            plan = planWithNext(minutes = 25L),
        )
        val expected = TodayHeroGate.Open(TodayHeroLine.Next(slot = upcoming, minutesToNext = 25L), slots = listOf(upcoming))
        assertEquals(expected, verdict)
        assertTrue("甲档：门必须由内核交回为开，调用点不许自己算", verdict.isOpen)
        assertFalse(
            "这一格不许折成 NothingMore（那正是改前的句子）",
            (verdict as TodayHeroGate.Open).line is TodayHeroLine.NothingMore,
        )
    }

    @Test
    fun `没有更多课了只在真的排完时念`() {
        val byWeekday = TodayScreenRuler.heroGateOf(
            DayCourseScope.WeekdayOnly, isToday = true, hasCards = true, plan = planAllPast(),
        )
        val byTermWeek = TodayScreenRuler.heroGateOf(
            DayCourseScope.TermWeek(1), isToday = true, hasCards = true, plan = planAllPast(),
        )
        assertEquals(TodayHeroGate.Open(TodayHeroLine.NothingMore, listOf(past)), byWeekday)
        assertEquals(TodayHeroGate.Open(TodayHeroLine.NothingMore, listOf(past)), byTermWeek)
        assertTrue(byWeekday.isOpen && byTermWeek.isOpen)
    }

    @Test
    fun `正在上课那一档两把尺同格`() {
        assertEquals(
            TodayHeroGate.Open(TodayHeroLine.Ongoing(slot = ongoing, minutesRemaining = 15L), listOf(ongoing)),
            TodayScreenRuler.heroGateOf(
                DayCourseScope.WeekdayOnly, isToday = true, hasCards = true, plan = planWithOngoing(15L),
            ),
        )
        assertEquals(
            TodayHeroGate.Open(TodayHeroLine.Ongoing(slot = ongoing, minutesRemaining = 45L), listOf(ongoing)),
            TodayScreenRuler.heroGateOf(
                DayCourseScope.TermWeek(1), isToday = true, hasCards = true, plan = planWithOngoing(45L),
            ),
        )
    }

    @Test
    fun `列表有卡而课次判不了 门关着且不许念没有更多课了`() {
        // 这是本卡的另一面：节次表空 / 段没有上课时间时，plan.slots 是空的，
        // 而列表还挂着卡。念「今天没有更多课了」＝判不了冒充上完了。
        for (scope in listOf(DayCourseScope.WeekdayOnly, DayCourseScope.TermWeek(1))) {
            val closed = TodayScreenRuler.heroGateOf(scope, isToday = true, hasCards = true, plan = planNoSlots())
            assertEquals(
                "$scope 这一格必须关门：判不了不是没有更多课了",
                TodayHeroGate.Closed(TodayHeroAbsence.PLAN_CANNOT_SPEAK),
                closed,
            )
            assertFalse(closed.isOpen)
        }
        // 同格的另一手：调用点连 plan 都没递（该递漏递）
        assertEquals(
            TodayHeroGate.Closed(TodayHeroAbsence.PLAN_CANNOT_SPEAK),
            TodayScreenRuler.heroGateOf(DayCourseScope.WeekdayOnly, isToday = true, hasCards = true, plan = null),
        )
    }

    @Test
    fun `这一天真没课时门收着 空态卡已经把那句话说了`() {
        assertEquals(
            TodayHeroGate.Closed(TodayHeroAbsence.NO_CARDS),
            TodayScreenRuler.heroGateOf(DayCourseScope.TermWeek(1), isToday = true, hasCards = false, plan = planNoSlots()),
        )
        assertEquals(
            TodayHeroGate.Closed(TodayHeroAbsence.NO_CARDS),
            TodayScreenRuler.heroGateOf(DayCourseScope.WeekdayOnly, isToday = true, hasCards = false, plan = planNoSlots()),
        )
    }

    @Test
    fun `OutOfTerm 那一格门跟着列表一起收`() {
        // 正常那一手：列表同源 ⇒ hasCards 必为 false
        val normal = TodayScreenRuler.heroGateOf(DayCourseScope.OutOfTerm, isToday = true, hasCards = false, plan = planNoSlots())
        assertEquals(TodayHeroGate.Closed(TodayHeroAbsence.OUT_OF_TERM), normal)
        // 哨兵格：假如调用点又拿另一把尺（星期几）的命中数来开门，内核仍然不许开——
        // 「同屏两处各读一把尺」在这一档被拒收
        val againstWidening = TodayScreenRuler.heroGateOf(DayCourseScope.OutOfTerm, isToday = true, hasCards = true, plan = planWithNext(25L))
        assertEquals(TodayHeroGate.Closed(TodayHeroAbsence.OUT_OF_TERM), againstWidening)
        assertFalse(againstWidening.isOpen)
    }

    @Test
    fun `翻到别的一天时门永远收着 与有没有课无关`() {
        for (hasCards in listOf(true, false)) {
            for (scope in listOf(DayCourseScope.TermWeek(1), DayCourseScope.WeekdayOnly, DayCourseScope.OutOfTerm)) {
                assertEquals(
                    "$scope / hasCards=$hasCards",
                    TodayHeroGate.Closed(TodayHeroAbsence.NOT_TODAY),
                    TodayScreenRuler.heroGateOf(scope, isToday = false, hasCards = hasCards, plan = planWithNext(25L)),
                )
            }
        }
    }

    // ─────────────── ④ 门的档位与「念哪句」不可能各自成立 ───────────────

    @Test
    fun `门开不开就是档位本身 三档开的与四档关的各有唯一的诚实说法`() {
        val open: List<TodayHeroGate> = listOf(
            TodayHeroGate.Open(TodayHeroLine.Ongoing(ongoing, 1L), listOf(ongoing)),
            TodayHeroGate.Open(TodayHeroLine.Next(upcoming, 1L), listOf(upcoming)),
            TodayHeroGate.Open(TodayHeroLine.NothingMore, listOf(past)),
        )
        val closed: List<TodayHeroGate> = TodayHeroAbsence.entries.map { TodayHeroGate.Closed(it) }
        for (gate in open) {
            assertTrue("${gate} 该是开着的档", gate.isOpen)
            assertTrue("${gate} 该是 Open 那一支", gate is TodayHeroGate.Open)
        }
        for (gate in closed) {
            assertFalse("${gate} 该是关着的档", gate.isOpen)
            assertTrue("${gate} 该是 Closed 那一支", gate is TodayHeroGate.Closed)
        }
        // 分档不许折成 Boolean：开着的三档彼此不同（念的句子不同）、关着的四档彼此不同（原因不同）
        assertEquals(3, open.toSet().size)
        assertEquals(TodayHeroAbsence.entries.size, closed.toSet().size)
    }
}
