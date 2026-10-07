package com.buaa.schedule.reminder

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.TimeSlot
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T206 主问题的**运行期对账**：真写侧（[ReminderNotifications.recordCountdownOwnership]）
 * 与真读侧（[ReminderNotifications.isCountingDownTo] / [ReminderNotifications.ownsCountdownTo]，
 * 外加真实的 [ClassProgressScheduler.planNextClassWindow]）第一次被放在同一格子里比过。
 *
 * ## 为什么以前没有这种格子
 *
 * JVM 侧那条判据一直是**注入的假谓词**：`ReminderScheduler.takeDownClassProgressIfNeeded`
 * 的 `countingDownTo` 参数（生产才传 `ReminderNotifications::ownsCountdownTo`）、
 * `ClassProgressCleanupDecisionTest` 里的 `FakeCountdown`、`LiveCountdownOwnershipTest`
 * 里手搓的 `armCountdown(courseId, classStartMillis)` —— 三处都在替对方作证，
 * 于是"写侧存的是哪一格"与"读侧传的是哪一格"**从来没有对过账**：
 * 写侧那一句取 `endMillis`、读侧三处取 `startMillis`，盘面差一个字段名，
 * 而没有任何一枚测试会因为把它们弄反而红。卡面要的那张判据表，这一文件是它的可执行版本。
 *
 * ## 判据表（这一文件钉的就是这四格；表在卡面 §主问题）
 *
 * | 哪一等的窗口 | startMillis | endMillis |
 * |---|---|---|
 * | 课前实况窗口（`ReminderScheduler.createPendingIntent` 造、`ReminderReceiver` 改起点） | 收铃此刻 | **上课时刻** |
 * | 课堂计划窗口（`planNextClassWindow`，"课还没开始"那一等） | **上课时刻** | 下课时刻 |
 *
 * 写侧从第一型的 `endMillis` 取、三处读侧从第二型的 `startMillis` 取 ⇒ 两格同值 ⇒
 * 判据今天处处对得上（卡面三种可能的第 3 种：依赖窗口形状的巧合、今天成立）。
 * 因此下面既有"对得上"的格子，也有一格**把那个巧合拆掉**（[窗口形状换成自然那一型 真读侧当场恒假]）：
 * 恒假那一等不是假想，它只差一次改窗形状，而那正是 [CountdownOwnershipAnchorGuardTest] 要拦的。
 *
 * 时钟一律由用例自己算（真实墙上钟 + 15 分钟），因为 [ReminderNotifications.isCountingDownTo]
 * 内部读 `System.currentTimeMillis()`；把开课时刻钉死在某个日期上会让这一族用例
 * 在那一天的夜里翻红（跑一次红一次、还看不出为什么）。
 */
class CountdownOwnershipWriterReaderTest {

    private val minute = 60_000L

    /**
     * 今天之后约 15 分钟、**对齐到整分钟**的那节课。
     *
     * 对齐不是口味：`planNextClassWindow` 的开课时刻是 `date.atTime(slot.startTime)`，
     * 而节次表的时刻只有"时:分"两格 —— 用例若拿一个带秒的 LocalDateTime 当期望值，
     * 真读侧传来的永远是**抹掉秒**的那一枚，两处毫秒级不同 ⇒ 归属判据恒假。
     * 这一格本身就是"两侧毫秒必须一字不差"的口径对照物（差 59 秒就红）。
     */
    private val classStart: LocalDateTime =
        LocalDateTime.now().plusMinutes(15).truncatedTo(ChronoUnit.MINUTES)

    /** 开课那一刻落在的教学周的周一 */
    private val semesterStart: LocalDate =
        classStart.toLocalDate().minusDays((classStart.dayOfWeek.value - 1).toLong())

    private val slots = listOf(
        // 只用第 1 节：整条链只需要"这一段的开课时刻"与"这一段的下课时刻"两格，
        // 而夜里跑用例时第 2 节的表时刻会跨过午夜（00:05 排在 23:50 之前），
        // 那会把窗口长度变成兜底值 ⇒ 用例的期望值就不再是它自己算的那一格。
        TimeSlot(number = 1, startTime = hhmm(classStart), endTime = hhmm(classStart.plusMinutes(45))),
    )

    private val course = Course(
        id = 7L,
        name = "高等数学",
        location = "J3-101",
        dayOfWeek = classStart.dayOfWeek.value,
        periods = listOf(1),
        weeks = listOf(1),
    )

    private val classStartMillis = epochMillis(classStart)
    private val classEndMillis = epochMillis(classStart.plusMinutes(45))

    @After
    fun clearOwnership() {
        ReminderNotifications.countdownCourseId = 0L
        ReminderNotifications.countdownClassStart = 0L
    }

    // ───────────────────────── 判据表的可执行版本 ─────────────────────────

    /**
     * 生产那条链造**课前实况窗口**的方式，两格凭据逐格对着源码抄（改形状必须同步改这里，
     * 而那一步由 [CountdownOwnershipAnchorGuardTest] 的第 ③ 层先把源码钉红）：
     *
     * 1. `ReminderScheduler.createPendingIntent`：`startMillis` 与 `endMillis` **都**是上课时刻
     *    （它自己的注释："收铃时刻就是上课时刻，所以 start/end 先都填上课时间"）；
     * 2. `ReminderReceiver`：`window = scheduled.copy(startMillis = now)` —— 只有起点被改成收铃那一刻，
     *    `endMillis` 一个字没动 ⇒ 交给写侧的那一格 `endMillis` 就是上课绝对时刻。
     */
    private fun preClassLiveWindow(bellAtMillis: Long): ClassProgressScheduler.ClassWindow =
        ClassProgressScheduler.ClassWindow(
            courseId = course.id,
            courseName = course.displayName,
            location = course.location,
            sectionText = "第 1-2 节",
            startMillis = classStartMillis,
            endMillis = classStartMillis,
        ).copy(startMillis = bellAtMillis)

    @Test
    fun `判据表四格：两型窗口的 start 与 end 各等于什么`() {
        val plan = requireNotNull(
            ClassProgressScheduler.planNextClassWindow(
                courses = listOf(course),
                semesterStart = semesterStart,
                timeSlots = slots,
                now = classStart.minusMinutes(1),
            ),
        )
        val live = preClassLiveWindow(bellAtMillis = classStartMillis - 10 * minute)

        // 课堂计划窗口那一型：课还没开始 ⇒ start = 上课、end = 下课
        assertEquals("planNextClassWindow 的 startMillis 必须是上课时刻", classStartMillis, plan.startMillis)
        assertEquals("planNextClassWindow 的 endMillis 必须是下课时刻（不是上课时刻）", classEndMillis, plan.endMillis)
        assertTrue("这一等窗口必须还没开始", plan.startsAfter(epochMillis(classStart.minusMinutes(1))))

        // 课前实况窗口那一型：start 被改成收铃此刻、end 留在上课时刻
        assertEquals("课前实况窗口的 endMillis 才是上课时刻", classStartMillis, live.endMillis)
        assertEquals(
            "课前实况窗口的 startMillis 是收铃那一刻，与计划窗口的同一格**不同值** —— " +
                "两型窗口的同一枚字段名装的不是同一件事",
            classStartMillis - 10 * minute,
            live.startMillis,
        )
        assertNotEquals(
            "两型窗口的 startMillis 若哪天同值了，说明有人不再改起点：那时读侧传 start 就不再是上课时刻",
            live.startMillis,
            plan.startMillis,
        )

        // 收束成一格：写侧那一格与读侧那一格必须是同一枚时刻
        assertEquals(
            "整张判据表就这一句承重：写侧 countdownAnchorOf(课前窗口) == 读侧 classStartOf(计划窗口)",
            ReminderNotifications.countdownAnchorOf(live),
            ReminderNotifications.classStartOf(plan),
        )
    }

    /**
     * 真写侧 × 真读侧，四处调用口径逐一过一遍（三处传课堂计划窗口的上课时刻、
     * 一处传课前实况窗口的上课时刻）。这一格在改之前是**跑不出来**的：
     * 写侧那一步以前埋在要 Context 的 [ReminderNotifications.startLiveWindow] 里。
     */
    @Test
    fun `四处读侧都能从真写侧记下的归属里对上账`() {
        val plan = requireNotNull(
            ClassProgressScheduler.planNextClassWindow(
                courses = listOf(course),
                semesterStart = semesterStart,
                timeSlots = slots,
                now = classStart.minusMinutes(1),
            ),
        )
        val live = preClassLiveWindow(bellAtMillis = classStartMillis - 10 * minute)

        ReminderNotifications.recordCountdownOwnership(live, LivePhase.BEFORE_CLASS)
        assertEquals("写侧存的必须是上课绝对时刻", classStartMillis, ReminderNotifications.countdownClassStart)
        assertEquals(course.id, ReminderNotifications.countdownCourseId)

        val cases = listOf(
            "ClassProgressScheduler.rescheduleWindows 那一读（带时间判）" to
                ReminderNotifications.isCountingDownTo(plan.courseId, ReminderNotifications.classStartOf(plan)),
            "LiveClassResyncer 那一读（带时间判）" to
                ReminderNotifications.isCountingDownTo(plan.courseId, ReminderNotifications.classStartOf(plan)),
            "ReminderScheduler.shouldTakeDownClassProgress 那一读（只判归属）" to
                ReminderNotifications.ownsCountdownTo(plan.courseId, ReminderNotifications.classStartOf(plan)),
            "CourseFluidService.recoverMissedClassStart 那一读（只判归属）" to
                ReminderNotifications.ownsCountdownTo(live.courseId, ReminderNotifications.countdownAnchorOf(live)),
        )
        for ((label, verdict) in cases) {
            assertTrue("$label：课前倒计时正挂着时判据必须为真，否则重排会当场拆掉刚上岛的倒计时", verdict)
        }
    }

    @Test
    fun `窗口形状换成自然那一型 真读侧当场恒假`() {
        // 卡面第 2 种可能（某一等恒假）的实物：把课前窗口改成它的"自然形状"（end = 下课时刻），
        // 写侧就存成下课时刻，而三处读侧仍传上课时刻 ⇒ 归属判据恒假。
        val naturalShape = preClassLiveWindow(bellAtMillis = classStartMillis - 10 * minute)
            .copy(endMillis = classEndMillis)
        ReminderNotifications.recordCountdownOwnership(naturalShape, LivePhase.BEFORE_CLASS)

        val plan = requireNotNull(
            ClassProgressScheduler.planNextClassWindow(
                courses = listOf(course),
                semesterStart = semesterStart,
                timeSlots = slots,
                now = classStart.minusMinutes(1),
            ),
        )
        assertFalse(
            "这一格是**反例**：形状一改，判据立刻恒假（后果就是 isCountingDownTo 自己 KDoc 里那句" +
                "「课前倒计时刚上岛就会被拆掉」）。它必须一直红着才对得起这张卡 —— " +
                "哪天有人把课前窗口的 endMillis 改成下课时刻，卡面第 2 种可能就是从现在这格开始的",
            ReminderNotifications.isCountingDownTo(plan.courseId, ReminderNotifications.classStartOf(plan)),
        )
        assertFalse(
            "同一条链上只判归属那一读也一起恒假 ⇒ 上课铃被吞时不再补排（recoverMissedClassStart）",
            ReminderNotifications.ownsCountdownTo(plan.courseId, ReminderNotifications.classStartOf(plan)),
        )
        // 而按写侧那一型自己读自己（end 对 end）反倒是"对得上"的：正是"两侧各挑一格"最阴的形状
        assertTrue(
            "两侧各自挑字段时，改完形状仍能自洽 —— 所以口径必须由 countdownAnchorOf/classStartOf 收成一份",
            ReminderNotifications.ownsCountdownTo(naturalShape.courseId, naturalShape.endMillis),
        )
    }

    // ───────────────────────── 归属的生命周期（写、过期、换手、清） ─────────────────────────

    @Test
    fun `课已开始那一秒 带时间判的那读立刻失效 只判归属的那读仍然算数`() {
        // 这一格必须用**真实钟的过去**：isCountingDownTo 内部读 System.currentTimeMillis()，
        // 而本文件的 classStart 是"约 15 分钟之后"（否则其余格子过不了时间判）。
        // 直接拿真钟往前推一分钟当"已经开始的那节课"，才与产品里那一秒同一口径。
        val startedClassMillis = System.currentTimeMillis() - minute
        val live = ClassProgressScheduler.ClassWindow(
            courseId = course.id,
            courseName = course.displayName,
            location = course.location,
            sectionText = "第 1 节",
            startMillis = startedClassMillis - 10 * minute,
            endMillis = startedClassMillis,
        )
        ReminderNotifications.recordCountdownOwnership(live, LivePhase.BEFORE_CLASS)
        assertEquals(startedClassMillis, ReminderNotifications.countdownAnchorOf(live))

        // 归属记的是上课时刻 ⇒ 到点之后 classStart > now 必然为假
        assertFalse(
            "isCountingDownTo 在上课铃那一刻之后必须失效（否则遗留永远收不掉，R5 F-11）",
            ReminderNotifications.isCountingDownTo(course.id, startedClassMillis),
        )
        assertTrue(
            "ownsCountdownTo 仍然算数：它正是用来区分「ACTION_START 跑过了」与「ACTION_START 被省电吞了」",
            ReminderNotifications.ownsCountdownTo(course.id, startedClassMillis),
        )
    }

    @Test
    fun `以 IN_CLASS 重新下发实况时归属归零`() {
        ReminderNotifications.recordCountdownOwnership(
            preClassLiveWindow(bellAtMillis = classStartMillis - 10 * minute),
            LivePhase.BEFORE_CLASS,
        )
        val inClass = ClassProgressScheduler.ClassWindow(
            courseId = course.id,
            courseName = course.displayName,
            location = course.location,
            sectionText = "第 1-2 节",
            startMillis = classStartMillis,
            endMillis = classEndMillis,
        )

        ReminderNotifications.recordCountdownOwnership(inClass, LivePhase.IN_CLASS)

        assertEquals(0L, ReminderNotifications.countdownCourseId)
        assertEquals(0L, ReminderNotifications.countdownClassStart)
        assertFalse(ReminderNotifications.ownsCountdownTo(course.id, classStartMillis))
    }

    @Test
    fun `课被删或改时间 真写侧与真读侧对不上 照常回收`() {
        ReminderNotifications.recordCountdownOwnership(
            preClassLiveWindow(bellAtMillis = classStartMillis - 10 * minute),
            LivePhase.BEFORE_CLASS,
        )

        // 改到了别的时间（同一门课，上课毫秒不再是归属那一格）
        assertFalse(
            ReminderNotifications.isCountingDownTo(course.id, epochMillis(classStart.plusMinutes(50))),
        )
        // 换成别的课（同一时刻）
        val moved = course.copy(id = 8L)
        val planOfOtherCourse = requireNotNull(
            ClassProgressScheduler.planNextClassWindow(
                courses = listOf(moved),
                semesterStart = semesterStart,
                timeSlots = slots,
                now = classStart.minusMinutes(1),
            ),
        )
        assertFalse(
            "课被换掉后重排出的窗口与归属对不上 ⇒ 遗留照收（R5 F-11 的语义不许被倒计时豁免拖住）",
            ReminderNotifications.isCountingDownTo(
                planOfOtherCourse.courseId,
                ReminderNotifications.classStartOf(planOfOtherCourse),
            ),
        )
    }

    @Test
    fun `releaseCountdownOwnership 只清仍然记着这一节的那份`() {
        val live = preClassLiveWindow(bellAtMillis = classStartMillis - 10 * minute)
        val anchor = ReminderNotifications.countdownAnchorOf(live)

        // 记的是别节课的账：不许被本条实况的收尾抹掉
        ReminderNotifications.countdownCourseId = 99L
        ReminderNotifications.countdownClassStart = classStartMillis
        assertFalse(ReminderNotifications.releaseCountdownOwnership(course.id, anchor))
        assertEquals("不属于本条实况的归属必须原样留着", 99L, ReminderNotifications.countdownCourseId)

        // 正是本条实况那份：清掉并回报 true
        ReminderNotifications.recordCountdownOwnership(live, LivePhase.BEFORE_CLASS)
        assertTrue(ReminderNotifications.releaseCountdownOwnership(course.id, anchor))
        assertEquals(0L, ReminderNotifications.countdownCourseId)
        assertEquals(0L, ReminderNotifications.countdownClassStart)

        // 空归属上再清一次：no-op，且回报 false（幂等，onDestroy 与补救那两处会重复走到这里）
        assertFalse(ReminderNotifications.releaseCountdownOwnership(course.id, anchor))
    }

    // ───────────────────────── 用例自己也得被钉住：日期不能写死 ─────────────────────────

    @Test
    fun `开课时刻在真实墙上钟上必须仍是将来 否则这一族用例只是自我确认`() {
        assertTrue(
            "本文件的日期全部由 System.currentTimeMillis() 派生（isCountingDownTo 内部读真钟）。" +
                "这一格红说明派生算错了（跨天/时区），而不是产品坏了 —— 表驱动用例的期望值" +
                "若是硬编码日期，夜里跑会一路绿得毫无意义",
            classStartMillis > System.currentTimeMillis(),
        )
        assertEquals(
            "节次表第 1 节的上课时刻必须与用例算出的开课时刻同一格",
            classStartMillis,
            epochMillis(classStart),
        )
    }

    private fun hhmm(at: LocalDateTime): String = "%02d:%02d".format(at.hour, at.minute)

    private fun epochMillis(at: LocalDateTime): Long =
        at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
