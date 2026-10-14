package com.buaa.schedule.domain.widget

/**
 * 桌面组件「宿主重绑之后补一次重绘」那一道按实例夹的单调钟窗口的**唯一一份**判据（T368，台账 #495）。
 *
 * 纯判据：零 android / androidx import、**不读时钟、不读时区、不读权限、不读 `Build.VERSION`** ——
 * 四枚事实（`elapsedRealtime` 读数、该实例上一次的读数、去重窗口、
 * 「这一枚实例补绘过没有」）全部由调用点（`widget/ScheduleAppWidgetProvider`）当参数递进来，
 * 本文件里一个都不许自己去取（本仓明文口径，与在册先例那一族同一条规矩）。
 *
 * ## 病在哪
 *
 * 改前那两手长这样（原文逐字留在这里做取证，源码形状守卫按「代码里 0 处、含注释恰好 1 处」双向复算）：
 *
 * ```
 * val previous = lastRerenderAt[appWidgetId] ?: 0L
 * if (now - previous < RERENDER_DEDUPE_MS) return@synchronized true
 * ```
 *
 * `0L` 同时被当成两件事用：① 「这一枚实例从没补绘过」的缺失兜底，② 窗口判据里「上一次补绘的时刻」。
 * 判据拿的那只钟又是 `SystemClock.elapsedRealtime()` —— 开机以来的毫秒数，开机那一刻正好从 0 起走。
 * 于是「从没补绘过」与「刚刚在开机那一瞬补绘过」在类型上不可区分：
 * 开机后 1.5 秒之内到达的第一枚 optionsChanged / onRestored 会被整段压掉，而且两条结论都不留日志。
 *
 * ## 可达性（⓪ 段裁定：**这一枚只有形状，没有可达性**）
 *
 * 窗口只有 1_500 毫秒，而两个入口都在宿主侧：`onAppWidgetOptionsChanged`（宿主带 options 的 UPDATE）
 * 与 `onRestored`（API 34+）。走到这两条之前必须先完成「系统起完 → 桌面起完 → 组件实例恢复出来 →
 * 宿主投递 → 本应用进程被这次投递拉起来」这一整串，1.5 秒的开机刻度内做到这件事本仓零设备读数
 * （本卡零 adb，不许编造）。拖拽改尺寸那一族必然落在开机 1.5 秒之外，所以**不主张用户可见后果**。
 *
 * 那为什么还要钉：这一族的病灶是「初始值与时间戳共用一格」的**形状**，而形状不会因为这一处
 * 撞不到就自己消失 —— 它已经在同仓里长出过一个不可达的同类支路（把不适用值当哨兵那一族）。
 * 所以本卡把这一枚按卫生档处理：换哨兵、抽内核、两档各留痕、顺序钉死，KDoc 里如实写「今天撞不到」。
 *
 * ## 修法（第 ⓪ 问裁的是甲档）
 *
 * 「没有上一次」要能被**表达**出来，而不是借一个恰好落在刻度起点上的数字。
 * [NEVER_RERENDERED_MILLIS] = `-1L`：`elapsedRealtime()` 结构上取不出负数，
 * 所以这一枚哨兵与任何真实读数在类型上不可混。窗口判据只在「确实补绘过」时才参与，
 * 而「补绘过没有」这一格还要与哨兵**互相印证**（对不上就是调用点在说谎，单独一档，
 * 见 [WidgetRerenderWindow.SentinelContradiction]）。
 *
 * ## 判据顺序（顺序即语义）
 *
 * 1. 「补绘过没有」那枚旗与哨兵读数互相打脸 ⇒ [WidgetRerenderWindow.SentinelContradiction]：
 *    偏向补绘并告警 —— 画错一帧的代价远小于让组件停在 `initialLayout` 那白底白字上。
 * 2. 确实从没补绘过 ⇒ [WidgetRerenderWindow.GrantedFirstRerender]：**这条排在窗口判据之前**，
 *    它就是本卡修的那一格。窗口判据在前 = 开机窗口内的第一枚重绑又被压掉（原缺陷复发）。
 * 3. `now - last < 0` ⇒ [WidgetRerenderWindow.SuppressedClockInverted]：递进取的是单调钟，
 *    同进程内结构上不该出现；真出现就是调用点递错了尺。压掉，但不许冒充「刚刚补绘过」。
 * 4. 窗口之内 ⇒ [WidgetRerenderWindow.SuppressedAsDuplicate]：去重本来要压的那一路
 *    （一次拖拽改尺寸会让宿主连着发好几轮 optionsChanged）。
 * 5. 其余 ⇒ [WidgetRerenderWindow.GrantedAfterWindow]：真重绑，照常补绘。
 *
 * ## 与另一族的两处刻意不同（别当笔误）
 *
 * - **没有「动作种类」那一档**：本卡这一处只有一种事件（宿主重绑），造一枚假的「不属于这一族」
 *   输入只会把判据撑成第二把尺。
 * - **钟倒退那一档压掉**（另一族放行）：这一处领到的配额换来的是一次读库＋拼 RemoteViews＋
 *   可能生成位图的重绘，那是本卡两处里唯一「多做得起、也真该省」的代价；
 *   另一族那两手是幂等注册，方向只能反过来。
 * - **记忆单元是按实例分键的**：调用点拿 `HashMap` 查该实例那一格，「补绘过没有」由
 *   「那一格在不在」现推，所以旗与哨兵天然同源 —— 不许改成进程级单值（那会把六个实例并成一格，
 *   一次拖拽就只有一枚实例能补绘）。
 */
object WidgetRerenderSentinel {
    /** 单调钟刻度上「这一枚实例从没补绘过」的哨兵：`elapsedRealtime()` 取不出负数，所以与任何真实读数不可混 */
    const val NEVER_RERENDERED_MILLIS = -1L
}

/** 这一次补绘配额的领取结论：五档，调用点按档分派，不许折叠 */
sealed interface WidgetRerenderWindow {

    /**
     * 该实例本进程第一次领到补绘配额（改前正是这一档在开机刻度之内被窗口判据压掉）。
     * [claimedAtElapsedRealtime] 是调用点该写回那一格的那一枚读数。
     */
    data class GrantedFirstRerender(
        val claimedAtElapsedRealtime: Long,
    ) : WidgetRerenderWindow

    /** 补绘过、但早已出了去重窗口：真重绑，照常补绘 */
    data class GrantedAfterWindow(
        val claimedAtElapsedRealtime: Long,
        val elapsedSinceLastClaimMillis: Long,
    ) : WidgetRerenderWindow

    /** 窗口之内的重复那一路（一次拖拽连发好几轮 optionsChanged）：压掉，但要留一条读得到的痕迹 */
    data class SuppressedAsDuplicate(
        val elapsedSinceLastClaimMillis: Long,
        val windowMillis: Long,
    ) : WidgetRerenderWindow

    /**
     * 单调钟倒进取（`now < lastClaimedAt`）：同进程内结构上不该发生，发生就是调用点递了别的尺。
     * 压掉，但这一档必须单独说句话，不许与 [SuppressedAsDuplicate] 共用「刚刚补绘过」那句。
     */
    data class SuppressedClockInverted(
        val nowElapsedRealtime: Long,
        val lastClaimedAt: Long,
    ) : WidgetRerenderWindow

    /**
     * 「补绘过没有」那枚旗与哨兵读数互相打脸（例如旗说是补绘过、那一格却还是初始哨兵）。
     * 结论偏向补绘：这一档是调用点的形状病了，而不是这一帧真的画过了。
     * [nowElapsedRealtime] 原样交回，调用点据此把那一格推正（此后旗与读数重新同源）。
     */
    data class SentinelContradiction(
        val hasEverRerendered: Boolean,
        val lastClaimedAt: Long,
        val nowElapsedRealtime: Long,
    ) : WidgetRerenderWindow
}

/** [WidgetRerenderWindowGate] 的入口：把四枚已算好的事实折成一档结论。 */
object WidgetRerenderWindowGate {

    fun decide(
        nowElapsedRealtime: Long,
        lastClaimedAt: Long,
        windowMillis: Long,
        hasEverRerendered: Boolean,
    ): WidgetRerenderWindow = when {
        hasEverRerendered == (lastClaimedAt == WidgetRerenderSentinel.NEVER_RERENDERED_MILLIS) ->
            WidgetRerenderWindow.SentinelContradiction(
                hasEverRerendered,
                lastClaimedAt,
                nowElapsedRealtime,
            )

        !hasEverRerendered ->
            WidgetRerenderWindow.GrantedFirstRerender(nowElapsedRealtime)

        nowElapsedRealtime - lastClaimedAt < 0L ->
            WidgetRerenderWindow.SuppressedClockInverted(nowElapsedRealtime, lastClaimedAt)

        nowElapsedRealtime - lastClaimedAt < windowMillis ->
            WidgetRerenderWindow.SuppressedAsDuplicate(
                nowElapsedRealtime - lastClaimedAt,
                windowMillis,
            )

        else ->
            WidgetRerenderWindow.GrantedAfterWindow(
                nowElapsedRealtime,
                nowElapsedRealtime - lastClaimedAt,
            )
    }
}
