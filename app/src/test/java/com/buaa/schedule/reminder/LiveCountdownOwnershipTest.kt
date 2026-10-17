package com.buaa.schedule.reminder

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课前倒计时的归属判据（「课前倒计时不上岛」的机制级回归）。
 *
 * 课前实况与"下课铃被吞的遗留清理"共用同一条常驻通知：
 * [ClassProgressScheduler.rescheduleWindows] 与 [LiveClassResyncer] 此前都把
 * "课还没开始"当成遗留，课前提醒自己触发的重排会在倒计时下发后一秒内把它拆掉。
 * 两处的口径由 [ReminderNotifications] 那**两枚**谓词承担（T227 #338 把"用哪只钟"钉死了）：
 * - 只判归属的 `ownsCountdownTo`：调用点自己已经用注入的 `now` 判过"课还没开始"
 *   （课堂铃链 `rescheduleWindows`、`ReminderScheduler` 的接线、服务的上课铃补救）；
 * - 归属 + 时间的 `isCountingDownTo`：调用点**没有**第二判可用时（前台校准那一路的
 *   `ClearLeftovers` 也包含"这一节已经结束"，只看归属就永远收不掉遗留）。
 *   时间判那一手现在有三枚参数的纯 JVM 版，钟由调用点递 —— 两枚参数的旧版只在它自己的
 *   体里读真钟，链上不许再用（`CountdownClockSingleReadGuardTest` 按宿主逐枚点名）。
 *
 * 这里钉住四种情形（1~4 走两枚参数的旧入口，为的是把"时间判的语义"留在盘面上；
 * 那张表在注入钟的那一枚上再走一遍，见第 5 格 —— 两侧都要有格子）：
 * 1. 课程 id 与开课毫秒都对上、课还没开始 → 是自己的倒计时，不许拆；
 * 2. 开课时刻已过（交接给上课铃之后）→ 归属自动过期，不再保护任何东西；
 * 3. 课被删/改时间后重排出的窗口对不上 → 照常回收（R5 F-11 的清理语义不变）；
 * 4. 根本没有倒计时挂着的空归属 → 与旧行为一致，直接收；
 * 5. 表驱动（零设备时钟）：同一批 (归属, 开课时刻, 那只钟) 在三枚参数版下逐格对账，
 *    并核一次"两枚谓词不是同一把尺"——归属对、课已过 ⇒ 归属仍真、倒计时已假。
 */
class LiveCountdownOwnershipTest {

    private val minute = 60_000L

    @After
    fun clearOwnership() {
        ReminderNotifications.countdownOwnership = null
    }

    private fun armCountdown(courseId: Long, classStartMillis: Long) {
        ReminderNotifications.countdownOwnership =
            ReminderNotifications.CountdownOwnership(courseId, classStartMillis)
    }

    @Test
    fun `countdown for the upcoming class is left alone by a reschedule`() {
        val classStart = System.currentTimeMillis() + 5 * minute
        armCountdown(courseId = 7L, classStartMillis = classStart)

        assertTrue(ReminderNotifications.isCountingDownTo(7L, classStart))
    }

    @Test
    fun `ownership expires the moment the class has started`() {
        val classStart = System.currentTimeMillis() - minute
        armCountdown(courseId = 7L, classStartMillis = classStart)

        // 铃响之后归属必须立刻失去保护力：ACTION_START 若被 ROM 吞掉，
        // 遗留的那条倒计时通知不能反过来阻止下一次重排把它收掉
        assertFalse(ReminderNotifications.isCountingDownTo(7L, classStart))
    }

    @Test
    fun `a moved or replaced class is not covered by the live countdown`() {
        val classStart = System.currentTimeMillis() + 5 * minute
        armCountdown(courseId = 7L, classStartMillis = classStart)

        // 用户把倒计时指向的课删了：下一节课是别的课程 id
        assertFalse(ReminderNotifications.isCountingDownTo(8L, classStart))
        // 同一门课但改到了别的时间（毫秒级精确匹配才作数）
        assertFalse(ReminderNotifications.isCountingDownTo(7L, classStart + 45 * minute))
    }

    @Test
    fun `no ownership protects nothing`() {
        val classStart = System.currentTimeMillis() + 5 * minute

        // 没发过课前实况（0 归属）时行为与修复前完全一致：该收就收
        assertFalse(ReminderNotifications.isCountingDownTo(0L, 0L))
        assertFalse(ReminderNotifications.isCountingDownTo(7L, classStart))
    }

    /**
     * T227 #338 的表驱动那一格：三枚参数版（钟由调用点递）逐格对账，
     * 并核一次"只判归属"与"归属 + 时间"不是同一把尺 —— 有一格两枚答案相反。
     *
     * 那一格就是本卡的病灶现场：跨秒的那一瞬，注入的那只钟说"还没开始"、
     * 真钟说"已经开始"，两枚谓词各读各的 ⇒ 守卫失效，刚上岛的倒计时与刚生效的勿扰被拆。
     * 现在时间只由递进来的那一只钟判一次，两枚谓词的答案由同一只钟决定，掉不了个儿。
     */
    @Test
    fun `the injected clock decides both halves on every cell`() {
        val classStart = 1_800_000_000_000L // 固定值：这一张表不许碰设备时钟
        val ownedCourse = 7L

        data class Cell(
            val label: String,
            val askedCourseId: Long,
            val askedClassStart: Long,
            val nowMillis: Long,
            val expectCountingDown: Boolean,
        )

        armCountdown(courseId = ownedCourse, classStartMillis = classStart)
        val cells = listOf(
            Cell("归属对上 + 课还没开始 ⇒ 正数着这节课，不许拆", ownedCourse, classStart, classStart - minute, true),
            Cell("归属对上 + 开课那一刻已到 ⇒ 倒计时过期，遗留照收", ownedCourse, classStart, classStart, false),
            Cell("归属对上 + 课已上了一节 ⇒ 同上（前台校准的 ClearLeftovers 也含这一档）", ownedCourse, classStart, classStart + 45 * minute, false),
            Cell("课被删/换课：课程 id 对不上", ownedCourse + 1, classStart, classStart - minute, false),
            Cell("课被改了时间：开课毫秒对不上（要毫秒级精确才作数）", ownedCourse, classStart + 45 * minute, classStart + 30 * minute, false),
            Cell("空归属（进程刚被杀）⇒ 谁都不保护", 0L, 0L, classStart - minute, false),
        )

        for (cell in cells) {
            assertEquals(
                cell.label,
                cell.expectCountingDown,
                ReminderNotifications.isCountingDownTo(cell.askedCourseId, cell.askedClassStart, cell.nowMillis),
            )
        }

        // 两枚谓词各管半件事：归属是清得掉的事实，时间是那只钟的函数 —— 有一格必然相反
        assertTrue(
            "开课之后归属仍然挂在这节课上（ACTION_START 没落地时它是唯一证据）",
            ReminderNotifications.ownsCountdownTo(ownedCourse, classStart),
        )
        assertFalse(
            "而同一格里「还在数着这节课」已经不成立 ⇒ 这两枚不许合并成一枚",
            ReminderNotifications.isCountingDownTo(ownedCourse, classStart, classStart),
        )

        // 跨秒那一格：只许由递进来的那只钟定，不许由真钟再定一遍
        assertTrue(
            "注入的钟说还没开始 ⇒ 守卫生效（此前真钟可能已经跨过开课那一秒，守卫当场失效）",
            ReminderNotifications.isCountingDownTo(ownedCourse, classStart, classStart - 1L),
        )
        assertFalse(
            "同一格把注入的钟往前推一秒 ⇒ 结论跟着翻，翻的次数与真钟无关",
            ReminderNotifications.isCountingDownTo(ownedCourse, classStart, classStart),
        )
    }
}
