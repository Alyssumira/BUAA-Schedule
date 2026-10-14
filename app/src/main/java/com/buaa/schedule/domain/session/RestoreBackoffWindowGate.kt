package com.buaa.schedule.domain.session

/**
 * 教务会话「冷启动恢复」那一道失败退避窗口的**唯一一份**判据（T378①，在册 #495／#496 那一族的第四枚落点）。
 *
 * 纯判据：零 android / androidx import、**不读时钟、不读时区、不读权限、不读 `Build.VERSION`** ——
 * 三枚事实（`elapsedRealtime` 的本次读数、上一次恢复失败时的读数、退避窗口）全部由调用点
 * （`data/import/BuaaWebSession` 的 `restore` 与 `abandonRestore`）当参数递进来，
 * 本文件一枚都不许自己去取。
 *
 * ## 病在哪（基点 `f928dea` 的盘面，两枚原文逐字留在这里做取证；源码形状守卫按
 * ## 「代码里 0 处、含注释恰好 1 处」双向复算，取证搬走 ⇒ 那些 0 就成了假绿）
 *
 * ```
 * @Volatile private var lastRestoreFailureAt: Long = 0L
 * val now = System.currentTimeMillis()
 * if (now - lastRestoreFailureAt < RESTORE_RETRY_INTERVAL_MS) return
 * ```
 *
 * 两头各有一枚病，而且是同一件事的两面：
 *
 * 1. **拿的是墙上钟**。`System.currentTimeMillis()` 会被用户改表、时区/闰秒校正、NITZ 下发往回拨。
 *    差值不夹下限 ⇒ 一旦 `now < lastRestoreFailureAt`，差就是**负数**，负数恒小于 60_000 ⇒
 *    这一档恒早退，而 `lastRestoreFailureAt` 再也没人往前推 ⇒ 本进程**再也不尝试恢复会话**，
 *    表现是"登录态明明还在、刷新课表却一直说没有"，且一句日志都不留（那一档是全函数里唯一一句话不说的早退）。
 * 2. **初始值与时间戳共用同一格**（在册 #496 钉的就是这一形）。换成单调钟之后如果照旧拿 `0L` 当初始值，
 *    就撞上 `elapsedRealtime()` 开机那一刻正好从 0 起走：开机后 60 秒之内的第一次恢复尝试会被判成
 *    「一分钟前刚失败过」而整段跳过 —— 同仓两枚先例（组件补注册、宿主重绑补绘）修的都是这一格。
 *
 * ## 修法
 *
 * 「从没失败过」要能被**表达**出来，而不是借一枚恰好落在刻度起点上的数字：
 * [RestoreBackoffSentinel.NEVER_FAILED_MILLIS] = `-1L` —— 单调钟结构上取不出负数，
 * 所以这一枚哨兵与任何真实读数在类型上不可混，窗口比较根本轮不到它（短路排在窗口判据**之前**）。
 * 读数倒退单独成一档 [RestoreBackoffWindow.GrantedClockInverted]，不许混进「刚刚失败过」那句话里；
 * 方向裁成**偏向放行**（与组件补绘那一族相反），理由见下面那一节。
 *
 * ## 判据顺序（顺序即语义）
 *
 * 1. `lastFailureAt == 哨兵` ⇒ [RestoreBackoffWindow.GrantedNeverFailed]：**这条必须排在窗口比较之前**。
 *    排在后面就是 #496 复发（开机刻度之内的第一次恢复又被压掉，而且这一次连"上次失败"都没发生过）。
 * 2. `now - lastFailureAt < 0` ⇒ [RestoreBackoffWindow.GrantedClockInverted]：递进来的读数取自单调钟，
 *    同进程内结构上不该出现；真出现就是调用点递错了尺（拿墙上钟或别的刻度来）。这一档偏向放行。
 * 3. `now - lastFailureAt < windowMillis` ⇒ [RestoreBackoffWindow.SuppressedWithinWindow]：
 *    退避本来要压的那一路（`onStart` 每次回到前台都会敲一次 `restore`，不压就是反复新建 WebView 直到 OOM）。
 * 4. 其余 ⇒ [RestoreBackoffWindow.GrantedAfterWindow]：早已出窗，照常再试一次。
 *
 * ## 为什么读数倒退这一档在这里放行、在补绘那一族压掉（不是笔误）
 *
 * 补绘那一族领到的配额换来的是一次读库 ＋ 拼 RemoteViews ＋ 可能生成位图的重绘，多做的代价压得过；
 * 本卡这一手是"再试一次冷启动恢复"，而且它已经自己带着**两道**真正的节流：
 * `restoreInFlight` 单飞旗（一次只允许一个在跑）与 `hasSession() || sessionWebView != null`（有会话就不试）。
 * 少试一次的代价是"这一整个进程都不再尝试恢复"（上面第 1 枚病体就是这一格），
 * 多试一次的代价是一枚 WebView 加一次页面加载 —— 方向只能偏向放行。
 *
 * ## 为什么不设「旗与哨兵互相打脸」那一档（与两枚在册先例的刻意不同）
 *
 * 补注册那一族的旗是 `AtomicLong` 之外的一枚独立载体、补绘那一族的旗是「`HashMap` 里那一格在不在」，
 * 两者都**可能与读数分居两处**，所以那一档有真靶子。本卡的载体只有一枚 Long：
 * 「失败过没有」与「什么时候失败的」在类型上就是同一格，造一枚旗出来只能靠调用点自己说谎才有靶子 ——
 * 在册口径：不许造没有输入能到达的档（那一档只能靠删它来保持绿色，是假绿的一种）。
 */
object RestoreBackoffSentinel {

    /** 单调钟刻度上「本进程从没恢复失败过」的哨兵：`elapsedRealtime()` 取不出负数，所以与任何真实读数不可混 */
    const val NEVER_FAILED_MILLIS = -1L
}

/** 这一次恢复尝试配额的领取结论：四档，调用点按档分派，不许折叠 */
sealed interface RestoreBackoffWindow {

    /**
     * 本进程从没失败过（改前这一档在开机刻度之内会被窗口判据压掉）。
     * [nowElapsedRealtime] 是调用点写回那一格时该用的读数。
     */
    data class GrantedNeverFailed(
        val nowElapsedRealtime: Long,
    ) : RestoreBackoffWindow

    /** 失败过、而退避窗口早就过了：照常再试一次 */
    data class GrantedAfterWindow(
        val nowElapsedRealtime: Long,
        val elapsedSinceFailureMillis: Long,
    ) : RestoreBackoffWindow

    /**
     * 读数倒进取（`now < lastFailureAt`）：递进来的是单调钟，同进程内结构上不该发生，
     * 发生就是调用点递了别的尺。这一档**照做放行**，但必须单独说一句实话，
     * 不许与 [SuppressedWithinWindow] 共用「刚刚失败过」那句 —— 改前那一句谎（恒早退且静默）就在这里。
     */
    data class GrantedClockInverted(
        val nowElapsedRealtime: Long,
        val lastFailureAt: Long,
    ) : RestoreBackoffWindow

    /** 退避窗口之内那一路：压掉这一趟，但留一条读得到的痕迹 */
    data class SuppressedWithinWindow(
        val elapsedSinceFailureMillis: Long,
        val windowMillis: Long,
    ) : RestoreBackoffWindow
}

/** [RestoreBackoffWindowGate] 的入口：把三枚已算好的事实折成一档结论。 */
object RestoreBackoffWindowGate {

    fun decide(
        nowElapsedRealtime: Long,
        lastFailureAt: Long,
        windowMillis: Long,
    ): RestoreBackoffWindow = when {
        lastFailureAt == RestoreBackoffSentinel.NEVER_FAILED_MILLIS ->
            RestoreBackoffWindow.GrantedNeverFailed(nowElapsedRealtime)

        nowElapsedRealtime - lastFailureAt < 0L ->
            RestoreBackoffWindow.GrantedClockInverted(nowElapsedRealtime, lastFailureAt)

        nowElapsedRealtime - lastFailureAt < windowMillis ->
            RestoreBackoffWindow.SuppressedWithinWindow(
                nowElapsedRealtime - lastFailureAt,
                windowMillis,
            )

        else ->
            RestoreBackoffWindow.GrantedAfterWindow(
                nowElapsedRealtime,
                nowElapsedRealtime - lastFailureAt,
            )
    }
}
