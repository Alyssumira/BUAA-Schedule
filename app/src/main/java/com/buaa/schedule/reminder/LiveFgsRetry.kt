package com.buaa.schedule.reminder

/**
 * 课程实况降级之后「还要不要再试一次」的判据内核（#119 / T78，2026-09-23）。
 *
 * 这一层**零 android 依赖、零时钟读取**：`Build.VERSION`、精确闹钟授权、现在几点、
 * 窗口过没过、这一发是课前还是课中 —— 全部由调用点算成参数递进来。
 * 「内核返回布尔、调用点各判一次 SDK_INT」这种写法是不允许的，所以那一次判断
 * 只写在 `CourseFluidService` 的 `keepsAlarmClockExemption()` 一处。
 *
 * 为什么这里没有「等 N 秒再把同一发重投一遍」这一档 —— 2026-09-23 在 emulator-5554 上的读数：
 * 直接 `am broadcast` 叫起的上课铃广播没有 FGS 后台启动豁免，AMS 判
 * `Background started FGS: Disallowed … uidState: RCVR; code:DENIED; tempAllowListReason:<null>`，
 * 之后隔 2.3 s / 5.3 s / 20.4 s 各重投一次同样的请求，三发的判据**逐字段相同**
 * （六发全 DENIED，见 docs/STATUS.md 的 T78 段）。⇒ 这一档的拒绝是**结构性的**，
 * 与等待时长无关，就地重投是纯负担。
 *
 * 有效的那一手是**换触发源**：同一节课的窗口经 `ClassProgressScheduler.rescheduleNextWindow`
 * 重排一遍之后，上课铃由 `setAlarmClock` 重新排出，它叫醒的广播拿到的是
 * `code:ALARM_MANAGER_ALARM_CLOCK; tempAllowListReason:<… duration:10000>`，AMS 放行、
 * 实况正常起来（实测 3/3，重投后约 5.3 s 落地；这台机器的 min_futurity 是 +5s，
 * 已在过去的开课时刻会被夹到此刻 +5s）。[SkipStructural] 钉的就是这条出路也断掉的那一档：
 * 精确闹钟未授权时 `scheduleClassStartBell` 降级 `setAndAllowWhileIdle`，
 * 那样排出的铃叫醒的广播实测**仍然** `not allowed due to mAllowStartForeground false`。
 */
internal enum class LiveFgsRetry(val token: String) {
    /** 重排一遍课堂窗口，让下一发从 `setAlarmClock` 的豁免档进来 */
    ArmOnce("armed"),

    /** 这一节已经下课/窗口已过：重排出来的铃没有对象，不许再试 */
    SkipWindowOver("skip:window-over"),

    /** 上课铃还没响：那条闹钟本来就排着，等它自己响就是下一发，重排只会拆了重弹 */
    SkipBellNotRung("skip:bell-not-rung"),

    /** 课前倒计时那一档：它的终点就是上课铃，而那条铃必然带着豁免再响一次，不值得为它重排 */
    SkipBeforeClass("skip:before-class"),

    /** 重排出来的那一发不再带闹钟豁免档（精确闹钟未授权）：同一枚 DENIED，白跑一趟 */
    SkipStructural("skip:no-exact-alarm"),

    /** 本窗口已经重排过一次、下一发还是失败了：到此为止，不许排第三次 */
    SkipExhausted("skip:exhausted"),
}

/**
 * 唯一的那次分档。判定顺序**就是**下面这个顺序，表驱动单测按它钉：
 * 「窗口已过」压过一切（课都下了，别的都不必问），
 * 「已经重试过」排在最后（前四档任意一条成立时，把计数花掉也换不来一发能起来的实况）。
 *
 * @param windowStillLive 这一节还没下课（调用点：`!ClassWindow.endedAt(now)`）
 * @param inClassPhase 课中那一档才有救：课前掉的是倒计时，课中掉的是一整节课
 * @param bellAlreadyRung 开课时刻已过（调用点：`!ClassWindow.startsAfter(now)`）
 * @param nextAttemptKeepsAlarmClockExemption 重排出来的铃还走不走 `setAlarmClock`
 * @param attemptsAlreadyArmed 本窗口此前已经重排过几次
 * @param maxAttempts 写死的上限（`CourseFluidService.MAX_LIVE_FGS_RETRY`）
 */
internal fun nextLiveFgsRetry(
    windowStillLive: Boolean,
    inClassPhase: Boolean,
    bellAlreadyRung: Boolean,
    nextAttemptKeepsAlarmClockExemption: Boolean,
    attemptsAlreadyArmed: Int,
    maxAttempts: Int,
): LiveFgsRetry = when {
    !windowStillLive -> LiveFgsRetry.SkipWindowOver
    !inClassPhase -> LiveFgsRetry.SkipBeforeClass
    !bellAlreadyRung -> LiveFgsRetry.SkipBellNotRung
    !nextAttemptKeepsAlarmClockExemption -> LiveFgsRetry.SkipStructural
    attemptsAlreadyArmed >= maxAttempts -> LiveFgsRetry.SkipExhausted
    else -> LiveFgsRetry.ArmOnce
}

/**
 * 「第几次」只在**同一个窗口**之内数：换了另一节课（或同一节课改过下课时刻）就从零开始。
 *
 * 窗口身份用 `(courseId, endMillis)` 这对值，与
 * [ReminderNotifications.ownsCountdownTo] 认"这条倒计时属于哪一节课"用的是同一口径
 * —— 单看 courseId 认不出改过的课，光看 endMillis 认不出同时下课的两门课。
 */
internal fun liveFgsAttemptsAlreadyArmed(
    recordedCourseId: Long,
    recordedEndMillis: Long,
    courseId: Long,
    endMillis: Long,
    recordedAttempts: Int,
): Int = if (recordedCourseId == courseId && recordedEndMillis == endMillis) recordedAttempts else 0
