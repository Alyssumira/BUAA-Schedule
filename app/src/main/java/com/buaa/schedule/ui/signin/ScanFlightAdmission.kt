package com.buaa.schedule.ui.signin

/**
 * 「一次扫码递进来了，这一手到底投不投、要不要念、念哪一句」的判据内核（T257①）。
 *
 * ## 这一颗管的是哪一段
 *
 * 页面上有两道闸：闸门（[shouldSubmitScan]）管**按帧的重投风暴**，状态机
 * （`SignInViewModel` 的 `flight`）管**一次只发一发**。这两道闸各按各的账，中间那段缝
 * 就是本卡的病灶：一次提交正在飞的时候，投影上那枚滚动码自己换了脸，闸门看见
 * "payload 变了"就按「换了一张码」那一档放行，并把它记进冷却账；紧接着状态机
 * `if (flight.value) return` 把这一手丢掉 —— 用户扫到了、闸门记下了、屏幕上什么都没发生，
 * 而这一枚在冷却期内不会再补投。手输签到码那条退路早已整条删除，所以这一丢就是**无路可走**的那种丢。
 *
 * ## 三档修法里选了丙（仍然丢，但界面与日志都要说实话）
 *
 * - 甲（记账推迟到真的发出去那一发之后）不要：闸门"先记账再回调"那一句次序是
 *   [ScanSubmissionGateTest] ⑨a 钉着的钉子（回调里就开始发请求，这期间进来的新帧晚一步置位
 *   就是双重提交）。而且不记这一笔就等于把同一枚码按帧重投 —— 三十帧一秒的那一条路，
 *   冷却账一撤就复活成日志与卡片的风暴。
 * - 乙（留一发待发，收尾自动补投）不要：排队被 [ScanSilentBranchGuardTest] ⑤ 明确钉死
 *   （`Channel<` 与 `enqueue` 零命中），本仓既有的裁定是"重复提交比这次没吃进去更糟"；
 *   补投还等于替用户签到一次，而这一页没有任何资格替用户决定再签一次。
 * - 丙要：丢仍然丢（不发第二发），但**这一手必须有名字**：状态机按档给一句人话进结果卡，
 *   并按档留一行 `Log.w` 取证。用户在屏上读到的是"这一张没投出去、为什么、下一步按哪颗"，
 *   而不是"我扫了，然后页面沉默"。
 *
 * ## 口径
 *
 * 零 android import，也不读墙钟/时区/权限/`Build.VERSION`（本仓明文规矩：设备事实留在调用点
 * 当参数传进来，同 [shouldSubmitScan] 与 `WidgetDataSynchronizer.planSnapshotWrites`）：
 * 在飞与否来自调用点那一次**原子领取**的结果（T395①：递交入口不止两条、也不都在主线程，
 * 一次 `flight.value` 的读在两颗线程之间会过期，所以这里递进来的不是读而是
 * `flight.compareAndSet(false → true)` 的反面）、是否同一枚 payload 来自状态机自己那枚 `dispatchingPayload`、
 * 冷却剩余来自闸门那颗刚写下的账、这一手是不是重试来自入口是相机帧还是相册。
 * 输出**不折成一枚 Boolean**：五档，其中"丢"那一族三档各配一句自己的话，档位与句子的对应
 * 由 [droppedArrivalCardText] / [droppedArrivalTraceText] 一处给出，调用点一个字都不拼。
 */

/**
 * 相册那一路的"冷却剩余"取值：那条路根本没有闸门账（[ScanSubmissionGateTest] ⑪ 钉着
 * "相册那条入口不受闸门影响"），所以递进来的那一手没有任何"冷却期内不再补投"可言。
 *
 * ⚠️ 这一枚 0 说的是"没有账"，不是"冷却已经过完"。读本参数的下一档必须先看
 * [isRetryHand]，别拿 `cooldownRemainingMillis == 0L` 去反推"再投一次就投得出去"。
 */
internal const val NoLedgerCooldownRemainingMillis = 0L

/**
 * 一次扫码递交的处境（五档，全可达；没有一档是留给"以后也许用得上"的）。
 */
internal sealed interface ScanFlightAdmission {

    /**
     * 投：这一手**领到了**闸门（状态机此刻空着，`compareAndSet(false → true)` 赢在这一手上），
     * 可以往下走（解析、发那一发请求）。
     *
     * 这一档成立时其余四枚输入一律不看 —— 不是遗漏，是本颗的优先级本身（没有东西在飞，
     * 就没有什么可丢的），表驱动单测把这一格钉成"朝宽扭不动"。
     * ⚠️ T395① 之后"置在飞"这一手不在这里了：闸门在问判据**之前**就已经被领走，
     * 所以拿到这一档的调用点不许再去写那颗旗（写了就是第二次领，正是本卡收的那一枚）。
     */
    data object Dispatch : ScanFlightAdmission

    /**
     * 不投，而且要在屏上说得出"为什么不投"。
     *
     * 这一族与下面 [DropAlreadyNoticed] 的区别只有一件：族内三档各有一句要念的话，
     * [DropAlreadyNoticed] 没有 —— 屏幕上那句话还站着。用 sealed 子接口把这层区别做成**类型**，
     * 调用点就再也拿不到"念一句空话"的那一支。
     */
    sealed interface Dropped : ScanFlightAdmission

    /**
     * 在飞 + 这一张与正在飞的那一枚**不是**同一份原文（滚动码自己换了脸）。
     *
     * 这就是本卡那枚缺陷的档位：闸门已把它记进冷却账，状态机不发那一发。
     *
     * @param cooldownRemainingMillis 这一枚在闸门账上还要压多久才能再投；负数按 0 读
     *   （时钟倒退那一档的方向与 [shouldSubmitScan] 一致：宁可少投一次）
     */
    data class DropNewCodeWhileDispatching(val cooldownRemainingMillis: Long) : Dropped

    /**
     * 在飞 + 这一张与正在飞的那一枚**是**同一份原文。
     *
     * 可达路径写清楚：一次请求飞过了 [RescanCooldownMillis]（校园网里这是常态），同一份原文
     * 就被冷却判据放行第二次；两引擎各解一次的同一帧也算这一档。丢掉它是**故意的**
     * ——重复提交一次签到比这一手没吃进去更糟，屏上那句必须把这份故意说出来，
     * 不然用户只会以为"又扫不上"。
     */
    data object DropSameCodeWhileDispatching : Dropped

    /**
     * 在飞 + 这一手是用户主动递进来的再一次动作（相册那张图）。
     *
     * 它排在 [DropAlreadyNoticed] **之前**判：按次的那一下必须自己回答"我刚点的那一下去哪了"，
     * 哪怕屏幕上已经挂着按帧那一句。
     */
    data object DropUserHandWhileDispatching : Dropped

    /**
     * 在飞 + 同一次在飞已经念过一句了 + 按帧重投。
     *
     * 不念、不写日志：屏幕上那一句说的就是这件事，再说一遍只是把同一张卡按帧重写
     * （口径同 [ScanSubmissionGate] 里那句"相等的状态值不重发"）。
     * ⚠️ 这一档**不是**静默支路：它的对偶是"屏上此刻有一张会说这句话的卡"，
     * 而那三档 [Dropped] 才是"卡刚立起来"的那一刻。
     */
    data object DropAlreadyNoticed : ScanFlightAdmission
}

/**
 * 这一手投不投、投不成时念哪一句。
 *
 * 判据次序即本颗的形状，改动任何一条都要连带改表驱动单测
 * （`ScanFlightAdmissionTest`）：
 * 1. 没在飞 ⇒ [ScanFlightAdmission.Dispatch]；
 * 2. 在飞 + 用户那一路递进来的 ⇒ [ScanFlightAdmission.DropUserHandWhileDispatching]；
 * 3. 在飞 + 这一次在飞已经念过 ⇒ [ScanFlightAdmission.DropAlreadyNoticed]；
 * 4. 在飞 + 同一枚 payload ⇒ [ScanFlightAdmission.DropSameCodeWhileDispatching]；
 * 5. 其余 ⇒ [ScanFlightAdmission.DropNewCodeWhileDispatching]，把冷却剩余带进句子里。
 *
 * @param dispatching 这一手**没有**领到闸门（T395①）：调用点递进来的是
 *        `flight.compareAndSet(false → true)` 的反面，不是一次 `flight.value` 的读 ——
 *        相机兜底那一手在 CameraX 的分析线程上、ML Kit 与相册那两路在主线程，
 *        一份读在两颗线程之间会过期，而它正是"两发真请求一起飞"那个后果的入口。
 *        线程本身不在参数表里：它是调用点属性，不是判据事实
 *        （同一处境下两颗线程的正确档位一模一样，递线程进去只能让调用点拿到结论之后再判一次）
 * @param isRetryHand 这一手是不是用户主动递进来的再一次动作（相册那张图 = true；相机按帧 = false）
 * @param samePayloadAsDispatching 这一手与正在飞的那一枚是不是同一份原文；
 *        不投的那几档它才有意义，所以调用点没在飞时传什么都行
 * @param alreadyNoticedForThisDispatch 这一次在飞之间已经念过一句了没有
 * @param cooldownRemainingMillis 这一枚在闸门冷却账上的剩余毫秒（相册那一路传 [NoLedgerCooldownRemainingMillis]）
 */
internal fun scanFlightAdmission(
    dispatching: Boolean,
    isRetryHand: Boolean,
    samePayloadAsDispatching: Boolean,
    alreadyNoticedForThisDispatch: Boolean,
    cooldownRemainingMillis: Long,
): ScanFlightAdmission = when {
    !dispatching -> ScanFlightAdmission.Dispatch
    isRetryHand -> ScanFlightAdmission.DropUserHandWhileDispatching
    alreadyNoticedForThisDispatch -> ScanFlightAdmission.DropAlreadyNoticed
    samePayloadAsDispatching -> ScanFlightAdmission.DropSameCodeWhileDispatching
    else -> ScanFlightAdmission.DropNewCodeWhileDispatching(cooldownRemainingMillis)
}

/**
 * 结果卡上那一句（`SignInState.Failed.reason` 的正文）。
 *
 * 四件约束，每件都对应这一页修过的一轮：
 * - **说得出这一手去哪了**：不许只说"签到失败"，那等于把"这次没吃进去"糊进网络故障那一档；
 * - **不许指向任何一条不存在的退路**：手输签到码整条已删除，所以句子只能指向屏上真实存在的东西
 *   （等结果出来、按「重新扫码」/再选一次那张图）；
 * - **一个字符的原文都不许出现**：iClass 那张码带时间戳，完整原文进卡片等于把"还能再签一次"
 *   的凭证写在屏幕上（口径同 `scanRejectForensicLine` 那条"只有形状，没有原文"）；
 * - **括号配平**：全角括号逐句闭合，这是 `UserVisibleParenBalanceGuardTest` 那把尺。
 */
internal fun droppedArrivalCardText(dropped: ScanFlightAdmission.Dropped): String = when (dropped) {
    is ScanFlightAdmission.DropNewCodeWhileDispatching ->
        "上一次签到还在提交，这一张码没有投出去。它已经记进重扫冷却的账，冷却期内不会再自动补投。" +
            "等屏幕上的提交结果出来之后按「重新扫码」，再对准一次。"
    ScanFlightAdmission.DropSameCodeWhileDispatching ->
        "上一次签到还在提交，屏幕上这一张与正在提交的那一张是同一枚，所以这一手没有投出去。" +
            "重复提交一次签到比这一手被吃掉更糟。"
    ScanFlightAdmission.DropUserHandWhileDispatching ->
        "上一次签到还在提交，你刚递进来的这一次没有投出去。等屏幕上的提交结果出来之后，再选一次那张图。"
}

/**
 * 取证那一行（`Log.w` 的正文，tag 沿用 `ScanSignInParse`）。
 *
 * 与卡片同档同源：两句话都由这一颗内核给，调用点一个字都不拼 —— 拼两份就又是
 * "两份真相"，而这一页修过三轮的病恰恰叫这个名字。级别是 Warn 不是 Info：这一档确实
 * 有一次递交被丢掉了，是失败；也不是 `Log.d`（HyperOS 把 logcat 截在 Info 级以下）。
 */
internal fun droppedArrivalTraceText(dropped: ScanFlightAdmission.Dropped): String = when (dropped) {
    is ScanFlightAdmission.DropNewCodeWhileDispatching ->
        "签到提交在飞，这一张码被丢弃：它不是正在提交的那一枚，闸门已把它记进冷却账，" +
            "剩余 ${dropped.cooldownRemainingMillis.coerceAtLeast(NoLedgerCooldownRemainingMillis)} 毫秒。" +
            "同一次在飞只念这一句，界面上那句与这里同档。"
    ScanFlightAdmission.DropSameCodeWhileDispatching ->
        "签到提交在飞，这一张码被丢弃：它与正在提交的那一枚是同一份原文，重投只会长出第二次签到。"
    ScanFlightAdmission.DropUserHandWhileDispatching ->
        "签到提交在飞，相册递进来的那一次没有开始提交：用户那一下去哪了由屏幕上一句回答。"
}
