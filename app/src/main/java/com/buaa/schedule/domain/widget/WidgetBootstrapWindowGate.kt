package com.buaa.schedule.domain.widget

/**
 * 桌面组件「后台刷新链补注册」那一道单调钟窗口的**唯一一份**判据（T368，台账 #496）。
 *
 * 纯判据：零 android / androidx import、**不读时钟、不读时区、不读权限、不读 `Build.VERSION`** ——
 * 四枚事实（`elapsedRealtime` 读数、上一次补注册的读数、去重窗口、
 * 「本进程补注册过没有」）全部由调用点（`widget/WidgetCommon`）当参数递进来，
 * 本文件里一个都不许自己去取（本仓明文口径，与在册先例那一族同一条规矩）。
 *
 * ## 病在哪
 *
 * 改前那两手长这样（原文逐字留在这里做取证，源码形状守卫按「代码里 0 处、含注释恰好 1 处」双向复算）：
 *
 * ```
 * @Volatile private var lastBootstrapAt: Long = 0L
 * if (now - lastBootstrapAt < BOOTSTRAP_INTERVAL_MS) return
 * ```
 *
 * `0L` 同时被当成两件事用：① 「从没补注册过」的初始值，② 窗口判据里「上一次补注册的时刻」。
 * 而判据拿的那只钟是 `SystemClock.elapsedRealtime()` —— 开机以来的毫秒数，开机那一刻它正好从 0 起走。
 * 于是「从没补注册过」与「一分钟前刚刚补注册过」在类型上不可区分：本次进程第一枚补注册只要落在
 * 开机后 60 秒之内，就会被判成「刚刚注册过了」而整段跳过，而且那一支一条日志都不留
 * （体内仅有的两枚 `Log.w` 挂在后面两手各自的 `onFailure` 上，与被咽掉那一路无关）。
 *
 * 咽掉之后少做的两手续排（调用点逐字点名）：
 * - 自排的零点闹钟没排 ⇒ 跨天只剩系统那三条时间类广播，而本仓成文记着这类 ROM 会吞它们；
 * - 那条 12 小时的周期兜底任务没登记 ⇒ 整条兜底链没了。
 *
 * 可达性口径（⓪ 段裁定，别把后果说过头）：这两手续排在同一次进程创建里还有一条补漏入口
 * （`BUAAApplication` 那条后台链的组件四步），所以本卡判为**结构可达、后果中档**，
 * 不是「组件挂前一天」那种重档。真正的残账是：补漏那条链既不持广播票也不持唤醒锁、
 * 以及被 ROM 掐掉开机广播的那批人根本没有那条补漏 —— 加上这一路**完全静默**，
 * 事后连「这次是谁注册的」都判不出来。
 *
 * ## 修法（第 ⓪ 问裁的是甲档）
 *
 * 「没有上一次」要能被**表达**出来，而不是借一个恰好落在刻度起点上的数字。
 * [NEVER_BOOTSTRAPPED_MILLIS] = `-1L`：`elapsedRealtime()` 结构上取不出负数，
 * 所以这一枚哨兵与任何真实读数在类型上不可混。窗口判据只在「确实领过」时才参与，
 * 而「领过没有」这一格还要与哨兵**互相印证**（对不上就是调用点在说谎，单独一档，
 * 见 [WidgetBootstrapWindow.SentinelContradiction]）—— 于是「初始值与时间戳又共用一格」
 * 这一手既拦在源码形状守卫里，也拦在判据自己的分档里。
 *
 * ## 判据顺序（顺序即语义）
 *
 * 1. 「领过没有」那枚旗与哨兵读数互相打脸 ⇒ [WidgetBootstrapWindow.SentinelContradiction]：
 *    这一档**照做补注册**并要调用点留一条告警 —— 这一族是调用点的形状病了，不是今天真的不需要注册。
 * 2. 确实从没补注册过 ⇒ [WidgetBootstrapWindow.GrantedFirstBootstrap]：**这条排在窗口判据之前**，
 *    它就是本卡修的那一格。窗口判据在前 = 开机后第一分钟内的第一次补注册又被压掉（原缺陷复发）。
 * 3. `now - last < 0` ⇒ [WidgetBootstrapWindow.GrantedClockInverted]：递进取的是单调钟，
 *    同进程内结构上不该出现；真出现就是调用点递错了尺。这一档**偏向放行**（见下面那条方向裁定）。
 * 4. 窗口之内 ⇒ [WidgetBootstrapWindow.SuppressedAsDuplicate]：这是去重本来要压的那一路
 *    （六个 Provider 的同一次广播会各进来一次），它与第 2 档是两种结论，必须分两句念。
 * 5. 其余 ⇒ [WidgetBootstrapWindow.GrantedAfterWindow]：早已出窗，照常补注册。
 *
 * ## 为什么钟倒退那一档在这里放行、在另一族里压掉（不是笔误）
 *
 * 在册先例那一族（跨天全量刷新）把倒退判成「跳过但单独说一句」，因为它的代价是一次全量刷库与重绘。
 * 本卡这两手是**幂等注册**：周期任务那一条用的是「已存在就留着」那种登记策略，
 * 零点闹钟那一条重排即覆盖 —— 多注册一次的代价是几次 binder 往返，
 * 少注册一次的代价是「整条兜底链没了」。方向只能偏向放行。
 *
 * ## 为什么不折成一枚 Boolean
 *
 * 调用点要做两件不同的事：领到了就注册、没领到就**说清为什么没注册**。
 * 五档里三档注册、一档跳过、一档跳过本来就不该被叫到 —— 折成 Boolean 就把「去重成功」与
 * 「判据说不出话」念成同一句话，正是本卡第 ③ 段要消灭的那种静默支路。
 */
object WidgetBootstrapSentinel {
    /** 单调钟刻度上「从没补注册过」的哨兵：`elapsedRealtime()` 取不出负数，所以与任何真实读数不可混 */
    const val NEVER_BOOTSTRAPPED_MILLIS = -1L
}

/** 这一次补注册配额的领取结论：五档，调用点按档分派，不许折叠 */
sealed interface WidgetBootstrapWindow {

    /**
     * 本进程第一次领到补注册配额（改前正是这一档被窗口判据压掉）。
     * [claimedAtElapsedRealtime] 是调用点该写回哨兵的那一枚读数。
     */
    data class GrantedFirstBootstrap(
        val claimedAtElapsedRealtime: Long,
    ) : WidgetBootstrapWindow

    /** 领过、但早已出了去重窗口：照常补注册 */
    data class GrantedAfterWindow(
        val claimedAtElapsedRealtime: Long,
        val elapsedSinceLastClaimMillis: Long,
    ) : WidgetBootstrapWindow

    /**
     * 单调钟倒进取（`now < lastClaimedAt`）：同进程内结构上不该发生，发生就是调用点递了别的尺。
     * 这一档**照做补注册**，但必须单独说一句实话，不许与 [SuppressedAsDuplicate] 共用「刚刚注册过」那句。
     */
    data class GrantedClockInverted(
        val nowElapsedRealtime: Long,
        val lastClaimedAt: Long,
    ) : WidgetBootstrapWindow

    /** 窗口之内的重复那一路（同一次广播里六个 Provider 各进来一次）：压掉补注册，但要留一条读得到的痕迹 */
    data class SuppressedAsDuplicate(
        val elapsedSinceLastClaimMillis: Long,
        val windowMillis: Long,
    ) : WidgetBootstrapWindow

    /**
     * 「领过没有」那枚旗与哨兵读数互相打脸（例如旗说是领过、读数却还是初始哨兵）。
     * 结论偏向补注册：这一档是调用点的形状病了，而不是今天真的不需要那两手续排。
     * [nowElapsedRealtime] 原样交回，调用点据此把哨兵推正（此后旗与读数重新同源）。
     */
    data class SentinelContradiction(
        val hasEverBootstrapped: Boolean,
        val lastClaimedAt: Long,
        val nowElapsedRealtime: Long,
    ) : WidgetBootstrapWindow
}

/** [WidgetBootstrapWindowGate] 的入口：把四枚已算好的事实折成一档结论。 */
object WidgetBootstrapWindowGate {

    fun decide(
        nowElapsedRealtime: Long,
        lastClaimedAt: Long,
        windowMillis: Long,
        hasEverBootstrapped: Boolean,
    ): WidgetBootstrapWindow = when {
        hasEverBootstrapped == (lastClaimedAt == WidgetBootstrapSentinel.NEVER_BOOTSTRAPPED_MILLIS) ->
            WidgetBootstrapWindow.SentinelContradiction(
                hasEverBootstrapped,
                lastClaimedAt,
                nowElapsedRealtime,
            )

        !hasEverBootstrapped ->
            WidgetBootstrapWindow.GrantedFirstBootstrap(nowElapsedRealtime)

        nowElapsedRealtime - lastClaimedAt < 0L ->
            WidgetBootstrapWindow.GrantedClockInverted(nowElapsedRealtime, lastClaimedAt)

        nowElapsedRealtime - lastClaimedAt < windowMillis ->
            WidgetBootstrapWindow.SuppressedAsDuplicate(
                nowElapsedRealtime - lastClaimedAt,
                windowMillis,
            )

        else ->
            WidgetBootstrapWindow.GrantedAfterWindow(
                nowElapsedRealtime,
                nowElapsedRealtime - lastClaimedAt,
            )
    }
}
