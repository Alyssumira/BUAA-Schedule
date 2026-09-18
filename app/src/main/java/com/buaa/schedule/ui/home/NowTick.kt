package com.buaa.schedule.ui.home

import java.time.LocalTime

/** 一分钟：日视图 Hero 与「下节课倒计时」的刷新步长 */
internal const val MINUTE_TICK_MS = 60_000L

/** 15 秒：周视图「当前时间线」那一步的刷新步长（只为红线有点实时感，节次翻面不靠它） */
internal const val TIMELINE_TICK_MS = 15_000L

/**
 * 下一次 tick 还要等多久：**对齐到 [stepMillis] 的墙钟边界**。
 *
 * 界面这边只回答一件事——"这一节的高亮什么时候该翻"，答案是节次的下课那一刻，
 * 而所有节次的下课时间都落在整分钟上，所以对齐到分钟边界就够，不需要更勤的轮询。
 *
 * 两个视图此前各写一套：日视图按 `60_000 - (second*1000 + nano/1e6)` 自己算，
 * 周视图干脆固定 `delay(15_000)`（对齐的是进程启动的那一刻，不是墙钟），
 * 于是同一次亮屏里 Hero 的"还有 N 分钟"与网格里的红线相差最多 15 秒。
 * 现在同一个函数只差一个步长参数。
 *
 * 纯函数、时间由参数注入：调用方读一次 `LocalTime.now()`，
 * 把同一个值既发布出去又用来算下一次唤醒 —— 读两次的话，
 * 两次之间正好跨过边界就会"发布了旧时刻、却按新时刻算要醒的时间"，
 * 那一分钟的翻面直接晚一整分钟。
 */
internal fun nextTickDelayMillis(now: LocalTime, stepMillis: Long = MINUTE_TICK_MS): Long {
    val step = stepMillis.coerceAtLeast(1L)
    val millisIntoStep = (now.toNanoOfDay() / 1_000_000L) % step
    // 正好落在边界上（millisIntoStep == 0）时等**整一步**：这一刻刚刚发布过，
    // 再立刻醒一次就是白重组。
    return step - millisIntoStep
}

/** 5 秒：越过零点再醒，避开时钟回调边界的抖动，也让日期切换不落在 00:00:00.000 那一瞬 */
internal const val DAY_TICK_OVERFLOW_MS = 5_000L

/** 60 秒：一次日滴答最短的等待，避免在零点附近醒来后立刻又排一次几乎为零的等待 */
internal const val MIN_DAY_TICK_MS = 60_000L

private const val MILLIS_OF_DAY = 24L * 60L * 60L * 1_000L

/**
 * 下一次「今天变成别的一天」要等多久：对齐到下一个零点，补 [DAY_TICK_OVERFLOW_MS]，
 * 并且不短于 [MIN_DAY_TICK_MS]。
 *
 * 与 [nextTickDelayMillis] 同一个形状（都是"对齐到墙钟边界"），只差步长：
 * 分钟步长管的是节次翻面（谁在上课、还剩几分钟），零点步长管的是「今天」这一格
 * （顶栏日期与周次、日/周两视图的今日高亮、「回到今天」那颗按钮）。
 * 两个步长合起来才是完整的界面唤醒源——只做分钟那一步时，课表挂着不动跨过零点，
 * 今日页仍会停在昨天。
 *
 * 恒为正，且一定**晚于**下一个零点（最坏情形是本日只剩几秒时按 [MIN_DAY_TICK_MS] 醒，
 * 那时也已经过了零点）。调用方在醒来之后重新读一次时钟再发射，
 * 所以 Doze 把唤醒推迟（迟到）还是提前（冻结期间 monotonic 计时不走）都不会发错日期：
 * 早醒时发射的还是同一天，下游 `distinctUntilChanged()` 把它吞掉，代价只是多醒一次。
 */
internal fun nextDayTickDelayMillis(now: LocalTime): Long {
    val millisToMidnight = MILLIS_OF_DAY - now.toNanoOfDay() / 1_000_000L
    return (millisToMidnight + DAY_TICK_OVERFLOW_MS).coerceAtLeast(MIN_DAY_TICK_MS)
}
