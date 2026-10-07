package com.buaa.schedule.domain.widget

/**
 * 桌面小组件「跨天刷新」那一条去重判据的**唯一一份**（T256，台账 #478）。
 *
 * 纯判据：零 android / androidx import、**不读时钟、不读时区、不读权限、不读 `Build.VERSION`** ——
 * 五枚事实（这一次是哪种动作、`elapsedRealtime` 读数、上一次领取的读数、去重窗口、
 * 「本进程领过没有」）全部由调用点（`widget/WidgetRefreshReceiver.kt`）当参数递进来，
 * 本文件里一个都不许自己去取（本仓明文口径）。
 *
 * ## 病在哪
 *
 * 改前那两格长这样（原文逐字留在调用点的 KDoc 取证里）：
 *
 * ```
 * private val lastRolloverAt = java.util.concurrent.atomic.AtomicLong(0L)
 * if (now - prev < ROLLOVER_SUPPRESS_MS) return false
 * ```
 *
 * `0L` 同时被当成两件事用：① 「从没领过」的初始值，② 窗口判据里「上一次领取的时刻」。
 * 而判据拿的那只钟是 `SystemClock.elapsedRealtime()` —— **开机以来的毫秒数**，
 * 开机那一刻它正好从 0 起走。于是「从没刷过」与「刚刚在开机那一瞬刷过」在类型上不可区分：
 * 开机后窗口之内到达的跨天广播（`android.intent.action.DATE_CHANGED`、
 * `android.intent.action.TIME_SET`、`android.intent.action.TIMEZONE_CHANGED`、
 * 以及自排的 `com.buaa.schedule.action.WIDGET_MIDNIGHT_REFRESH`）一律被判成
 * 「刚刚刷过了」而整段跳过全量刷新，而且那一支一条日志都不留。
 * 跳过之后紧接的续排那一手把下一跳排到**再下一天的零点**（调用点同段那句
 * `BackgroundSync.scheduleWidgetMidnight`），于是桌面组件把前一天的课挂满新的一天，
 * 只剩 12 小时一次的兜底 Worker 可能把它捞回来。
 *
 * ## 修法（第 ⓪ 问裁的是甲档）
 *
 * 「没有上一次」要能被**表达**出来，而不是借一个恰好落在刻度起点上的数字。
 * [NEVER_CLAIMED_MILLIS] = `-1L`：`elapsedRealtime()` 结构上取不出负数，
 * 所以这一枚哨兵与任何真实读数在类型上不可混。窗口判据只在「确实领过」时才参与，
 * 而「领过没有」这一格还要与哨兵**互相印证**（对不上就是调用点在说谎，单独一档，
 * 见 [WidgetRolloverClaim.SentinelContradiction]）—— 于是「初始值与时间戳又共用一格」
 * 这一手既拦在源码形状守卫里，也拦在判据自己的分档里。
 *
 * ## 判据顺序（顺序即语义）
 *
 * 1. 不是跨天那一族的动作 ⇒ [WidgetRolloverClaim.NotRolloverAction]：连「要不要领配额」都没问，
 *    别的时间格一律不参与。
 * 2. `hasEverRefreshed` 与哨兵读数对不上 ⇒ [WidgetRolloverClaim.SentinelContradiction]：
 *    这一档**照做全量刷新**并要调用点留一条告警 —— 宁可多刷一遍，不可把真跨天咽掉。
 * 3. 确实从没领过 ⇒ [WidgetRolloverClaim.GrantedFirstClaim]：**这条排在窗口判据之前**，
 *    它就是本卡修的那一格。窗口判据在前 = 开机窗口内的第一次跨天又被压掉（原缺陷复发）。
 * 4. `now - last < 0` ⇒ [WidgetRolloverClaim.SuppressedClockInverted]：递进取的是单调钟，
 *    同进程内结构上不该出现；真出现就是调用点递错了尺（把墙上时钟当单调钟递进来），
 *    跳过但不许冒充「刚刚刷过」，要单独说一句实话。
 * 5. 窗口之内 ⇒ [WidgetRolloverClaim.SuppressedAsDuplicate]：这是去重本来要压的那一路
 *    （系统零点广播与自排闹钟前后各到一次）。
 * 6. 其余 ⇒ [WidgetRolloverClaim.GrantedAfterWindow]：真跨天，照常全量刷新。
 *
 * ## 为什么不折成一枚 Boolean
 *
 * 调用点要做两件不同的事：领到了就刷、没领到就**说清为什么没刷**。
 * 六档里两档刷新、两档跳过、一档跳过但要告警、一档根本不该走到这里 ——
 * 折成 Boolean 就把「去重成功」与「判据说不出话」念成同一句话，
 * 正是本卡第 ③ 段要消灭的那种静默支路。
 */
enum class WidgetRolloverActionKind {
    /** 自排的零点闹钟（`ACTION_MIDNIGHT_REFRESH`）：对抗 ROM 吞系统广播的那一手 */
    SelfMidnightAlarm,

    /** 系统 `android.intent.action.DATE_CHANGED`（代码里读的是 `Intent.ACTION_DATE_CHANGED`） */
    SystemDateChanged,

    /** 系统 `android.intent.action.TIME_SET`（代码里的别名是 `Intent.ACTION_TIME_CHANGED`） */
    SystemTimeChanged,

    /** 系统 `android.intent.action.TIMEZONE_CHANGED` */
    SystemTimezoneChanged,

    /** 跨天那一族之外的动作（例如精确闹钟权限变化）：不领配额 */
    NotRollover,
}

/** 单调钟刻度上「从没领过」的哨兵：`elapsedRealtime()` 取不出负数，所以与任何真实读数不可混 */
object WidgetRolloverSentinel {
    const val NEVER_CLAIMED_MILLIS = -1L
}

/** 跨天全量刷新配额的领取结论：六档，调用点按档分派，不许折叠 */
sealed interface WidgetRolloverClaim {

    /**
     * 本进程第一次领到配额（改前正是这一档被窗口判据压掉）。
     * [claimedAtElapsedRealtime] 是调用点该写回哨兵的那一枚读数。
     */
    data class GrantedFirstClaim(
        val claimedAtElapsedRealtime: Long,
        val actionKind: WidgetRolloverActionKind,
    ) : WidgetRolloverClaim

    /** 领过、但早已出了去重窗口：真跨天，照常全量刷新 */
    data class GrantedAfterWindow(
        val claimedAtElapsedRealtime: Long,
        val elapsedSinceLastClaimMillis: Long,
        val actionKind: WidgetRolloverActionKind,
    ) : WidgetRolloverClaim

    /** 窗口之内的重复那一路（系统广播与自排闹钟各到一次）：跳过全量刷新，续排照做 */
    data class SuppressedAsDuplicate(
        val elapsedSinceLastClaimMillis: Long,
        val windowMillis: Long,
        val actionKind: WidgetRolloverActionKind,
    ) : WidgetRolloverClaim

    /**
     * 单调钟倒进取（`now < lastClaimedAt`）：同进程内结构上不该发生，
     * 发生就是调用点递了别的尺。跳过全量刷新，但这一档必须单独说句话，
     * 不许与 [SuppressedAsDuplicate] 共用「刚刚刷过」那句。
     */
    data class SuppressedClockInverted(
        val nowElapsedRealtime: Long,
        val lastClaimedAt: Long,
        val actionKind: WidgetRolloverActionKind,
    ) : WidgetRolloverClaim

    /**
     * 「领过没有」那枚旗与哨兵读数互相打脸（例如旗说是领过、读数却还是初始哨兵）。
     * 结论与改前相反地**偏向刷新**：这一档是调用点的形状病了，
     * 而不是今天真的没有课要重画 —— 多刷一遍的代价远小于把真跨天咽掉。
     * [nowElapsedRealtime] 原样交回，调用点据此把哨兵推正（此后旗与读数重新同源）。
     */
    data class SentinelContradiction(
        val hasEverRefreshed: Boolean,
        val lastClaimedAt: Long,
        val nowElapsedRealtime: Long,
        val actionKind: WidgetRolloverActionKind,
    ) : WidgetRolloverClaim

    /** 压根不是跨天那一族的动作：不领配额，也不该走到这条链上 */
    data class NotRolloverAction(
        val actionKind: WidgetRolloverActionKind,
    ) : WidgetRolloverClaim
}

/** [WidgetRolloverGate] 的入口：把五枚已算好的事实折成一档结论。 */
object WidgetRolloverGate {

    fun decide(
        actionKind: WidgetRolloverActionKind,
        nowElapsedRealtime: Long,
        lastClaimedAt: Long,
        windowMillis: Long,
        hasEverRefreshed: Boolean,
    ): WidgetRolloverClaim = when {
        actionKind == WidgetRolloverActionKind.NotRollover ->
            WidgetRolloverClaim.NotRolloverAction(actionKind)

        hasEverRefreshed == (lastClaimedAt == WidgetRolloverSentinel.NEVER_CLAIMED_MILLIS) ->
            WidgetRolloverClaim.SentinelContradiction(
                hasEverRefreshed,
                lastClaimedAt,
                nowElapsedRealtime,
                actionKind,
            )

        !hasEverRefreshed ->
            WidgetRolloverClaim.GrantedFirstClaim(nowElapsedRealtime, actionKind)

        nowElapsedRealtime - lastClaimedAt < 0L ->
            WidgetRolloverClaim.SuppressedClockInverted(nowElapsedRealtime, lastClaimedAt, actionKind)

        nowElapsedRealtime - lastClaimedAt < windowMillis ->
            WidgetRolloverClaim.SuppressedAsDuplicate(
                nowElapsedRealtime - lastClaimedAt,
                windowMillis,
                actionKind,
            )

        else ->
            WidgetRolloverClaim.GrantedAfterWindow(
                nowElapsedRealtime,
                nowElapsedRealtime - lastClaimedAt,
                actionKind,
            )
    }
}
