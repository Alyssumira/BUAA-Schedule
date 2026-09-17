package com.buaa.schedule.reminder

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课前倒计时的归属判据（「课前倒计时不上岛」的机制级回归）。
 *
 * 课前实况与"下课铃被吞的遗留清理"共用同一条常驻通知：
 * [ClassProgressScheduler.rescheduleWindows] 与 [LiveClassResyncer] 此前都把
 * "课还没开始"当成遗留，课前提醒自己触发的重排会在倒计时下发后一秒内把它拆掉。
 * 现在两处共用 [ReminderNotifications.isCountingDownTo]，这里钉住四种情形：
 * 1. 课程 id 与开课毫秒都对上、课还没开始 → 是自己的倒计时，不许拆；
 * 2. 开课时刻已过（交接给上课铃之后）→ 归属自动过期，不再保护任何东西；
 * 3. 课被删/改时间后重排出的窗口对不上 → 照常回收（R5 F-11 的清理语义不变）；
 * 4. 根本没有倒计时挂着的空归属 → 与旧行为一致，直接收。
 */
class LiveCountdownOwnershipTest {

    private val minute = 60_000L

    @After
    fun clearOwnership() {
        ReminderNotifications.countdownCourseId = 0L
        ReminderNotifications.countdownClassStart = 0L
    }

    private fun armCountdown(courseId: Long, classStartMillis: Long) {
        ReminderNotifications.countdownCourseId = courseId
        ReminderNotifications.countdownClassStart = classStartMillis
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
}
