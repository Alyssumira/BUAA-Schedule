package com.buaa.schedule.domain.schedule

/**
 * 玻璃采样链那两枚「进程内时长」的单调钟护栏（T416丁，在册 #747）。
 *
 * 纯判据：零 android / androidx import、**不读钟** —— `SystemClock.elapsedRealtime()` 的读数一律由调用点
 * （`core/designsystem/GlassJankMonitor` 的帧回调与 `core/designsystem/GlassGovernance` 的降档入口）
 * 当参数递进来，本文件一枚都不许自己去取。
 *
 * ## 病在哪（基点 `5173dcc` 的盘面，原判据只看差值正负，从不问差值是不是倒着长的）
 *
 * 采样窗（10 秒）与降档冷却（10 分钟）量的都是**进程内经过的时间**，而改前取的是墙上钟，
 * 且都不落盘。在册判据（#350，原句在 `domain/session/RestoreBackoffWindowGate.kt` 的病灶一节）说的是同一件事：
 * 墙上钟会被用户改表、时区/闰秒校正、NITZ 下发往回拨，而「差值不夹下限」一旦碰上回拨就恒为负 ——
 * 负数既 `< 10_000`（采样窗永不收尾，降档整条静默停摆）又 `< LOWER_COOLDOWN_MS`
 * （冷却永不结束，`isGlassLowerCoolingDown` 从此恒真）。两头都是「一次改表就把自保护闸门焊死」，
 * 而这条链的失效在屏上只表现为「玻璃质感莫名没了／一直没换过来」，零句日志。
 *
 * ## 修法两格，方向都裁成偏向放行
 *
 * 1. 刻度换成 `elapsedRealtime()`（本文件不参与，那是调用点的事）；
 * 2. 倒退单独成档：[GLASS_CLOCK_INVERTED_ELAPSED_MILLIS] 取 `Long.MAX_VALUE`，
 *    于是「倒退」在两条判据里都念成**已经过了足够久** —— 窗口当场收尾、冷却当场作废。
 *    为什么偏向放行而不是偏向拦：这两道闸门拦的是「应用自己少干一件事」（少采样一次、晚降一档），
 *    放行多付的代价是一次窗口收尾或一次降档（可逆、有日志），拦下则整条运行时自保护静默停摆 ——
 *    与 #350 那一族的裁法同向（组件补绘那一族偏向拦是因为它拦的是重复发放，这里的代价方向相反）。
 */

/**
 * 读数倒退时交回的「已经过多久」：`Long.MAX_VALUE` 让任何窗口都算收尾、任何冷却都算已过。
 * 它不是一枚 `elapsedRealtime()` 读数，单调钟结构上取不出它，所以与任何真实时刻在类型上不可混。
 */
internal const val GLASS_CLOCK_INVERTED_ELAPSED_MILLIS: Long = Long.MAX_VALUE

/**
 * 同一把单调刻度上的两枚读数之间过了多久：`later - earlier`，倒退则交回
 * [GLASS_CLOCK_INVERTED_ELAPSED_MILLIS]（偏向放行）。
 */
internal fun glassMonotonicElapsedMillis(
    laterElapsedMillis: Long,
    earlierElapsedMillis: Long,
): Long = if (laterElapsedMillis < earlierElapsedMillis) {
    GLASS_CLOCK_INVERTED_ELAPSED_MILLIS
} else {
    laterElapsedMillis - earlierElapsedMillis
}

/**
 * 喂给冷却判据的上次降档时刻：`null` 在本仓的语义是「从未降档 ⇒ 不在冷却」。
 * 上次读数比现在晚（倒退）时把它折成 `null`，冷却闸门当场作废，走的还是 `GlassJankDecision.kt`
 * 里那一条 `isGlassLowerCoolingDown`，不许在调用点重算第二把尺。
 */
internal fun glassLowerAtForCooldown(
    lastLowerAtElapsedMillis: Long?,
    nowElapsedMillis: Long,
): Long? = if (lastLowerAtElapsedMillis != null && nowElapsedMillis < lastLowerAtElapsedMillis) {
    null
} else {
    lastLowerAtElapsedMillis
}
