package com.buaa.schedule.ui.signin

/**
 * 「判死之后，帧流还要不要继续跑」这一档的判据内核（T67）。纯判据，零 import
 * （仓库口径，范本 [ScanRecoveryPolicy] 与 [ScanCameraAidPolicy]）：帧流此刻绑没绑、
 * 解码器健康度长什么样、回到前台的额度还剩几次 —— 一律由调用点读出来当参数传进来，
 * 本文件不认识 `CameraProvider`、不认识 `ImageProxy`，也不自己去读 `Build.VERSION`。
 *
 * 这一卡收的是 T59① **有意没收回**的那笔残账。当时的取舍写在绑定那颗 effect 的注释里：
 * `if (!granted || !scannerWorking) return@LaunchedEffect` 排在 `unbindAll()` 之前，
 * 为的是「停用窗口里不解绑也不重绑」—— 窗口过完没有的唯一观测量就是帧还在不到达，
 * 掐了帧流就把那条自动活路掐死了。**这条一个字都没动**（守卫钉着，见
 * `ScanFrameFlowGuardTest` ④b）。
 *
 * 问题是同一句把**判死**也一起盖住了：[scannerGiveUp] 成立之后
 * [decoderFrameAction] 永远返回 [DecoderFrameAction.Skip]（连一帧都不再喂解码器），
 * 而 `scannerWorking` 在判死时早已停在 false、不再翻面 ⇒ 那颗 effect 的键表从此不动 ⇒
 * 从判死到用户退出这一页，相机还在按帧送、分析器还在一帧一帧 `image.close()` ——
 * 一颗**绑着但什么都不解**的分析流。这就是本卡的账 —— 模拟器实测该页 **2.80 帧/秒**
 * （临时探针包 180 帧 / 64.4 s；那一档还挂着 ML Kit 解码、是限流项，判死档不喂解码器只会更快），
 * "真机按 30 fps 送帧"是**未量的上限口径**，别当实测数引。
 *
 * 所以这里只问三件事，而且都是吃参数返回结论：
 * ① 这一档是**暂时**（停用窗口，正在自己试回来）还是**判死** —— 判据仍是 [scannerGiveUp]
 *    那一颗，本文件不另立第二份（「暂时」绝不停帧，那是 T59① 的观测前提）；
 * ② 判死了该不该停帧 —— 只有**真的还绑着**才停（见 [FrameFlowStop.Keep] 那一档的
 *    「不许解第二次」，重复推送同一个判死必须落在这里）；
 * ③ 停了之后还剩什么活路 —— [frameFlowWayBack]，取证行的后半句由它出，
 *    页面与分析器都不许自己拼（措辞两处不同就是有一处在说谎）。
 *
 * ⚠️ 本内核**不执行**任何解绑，也不被分析器调用去改健康度：判死那一档由调用点
 * （`SpocScanScreen` 里那颗以 `scannerGiveUp` 为键的 effect）执行。理由是 `image.close()`：
 * 判死的推进点在 ML Kit 的回调里，而那枚 Task 后面还挂着 `addOnCompleteListener { image.close() }`
 * —— 就地 unbindAll 会把「这一帧的 proxy 关没关」变成回调次序的依赖，
 * 这一页踩过的那个坑（finally 里过早 close 把帧废掉）就是同族。
 */

/**
 * 帧流这一档怎么处理。只有两档，且都不含"怎么停"的细节 —— 那留在调用点（它才知道 provider 在哪）。
 */
internal enum class FrameFlowStop {
    /**
     * 什么都不做。三种处境共用这一档，各自的原因都成立：
     * - 解码器还活着（健康）；
     * - **停用窗口内**（[scannerGiveUp] 不成立）：帧必须继续到达，内核才有"窗口过完没有"
     *   这个观测量 —— 这一档正是 T59① 那条取舍，本卡不许动；
     * - 判死但帧流本来就没绑着：已经停过了，**不许解第二次**（重复推送同一个判死、
     *   或者判死发生在绑定失败那一档时都走这里）。
     */
    Keep,

    /** 判死且帧流还开着：解绑，帧到此为止（分析器里那一串 `image.close()` 也随之消失） */
    Unbind,
}

/**
 * 判死了要不要把帧流停下来。
 *
 * 次序就是判据本体，两处都不许漂：
 * 1. [analysisFlowBound] 为 false 先落 Keep —— 它盖住"同一个判死被推第二次"那一档；
 *    停帧这件事的代价（预览不再刷新）只该付一次。
 * 2. 停不停只看 [scannerGiveUp]，**不看** [scannerWorkingOf]：停用窗口里后者同样是 false，
 *    照它的值停帧就是把 T59① 的自动活路掐了。判死与停用同时成立时判死说话
 *    （[scannerGiveUp] 本来就压倒停用，见 [ScanRecoveryPolicy] 那一档的注释）。
 */
internal fun frameFlowStop(health: ScanDecoderHealth, analysisFlowBound: Boolean): FrameFlowStop = when {
    !analysisFlowBound -> FrameFlowStop.Keep
    !scannerGiveUp(health) -> FrameFlowStop.Keep
    else -> FrameFlowStop.Unbind
}

/**
 * 停帧之后还剩什么活路 —— 一句话，取证行专用（页面文案不归这里：这一档不新增任何 UI 措辞）。
 *
 * ⚠️ 只许说**当下真做得到的动作**：本页没有「手输签到码」（2026-09-21 整条删除）、
 * 没有手电/补光（2026-09-22 整条拆除），而"退出重进"更不是活路 —— 恢复那条路是
 * 回到前台（[healthAfterPageVisible]，额度 [MaxPageVisibleRecoveries] 次），
 * 它会把 `scannerWorking` 翻回 true、驱动绑定那颗 effect 重新 bind，帧流自己回来。
 * 额度用完那一档就说实话：相机这条没有了，相册那条还在（它能在这里被说出来，
 * 前提是分析器活着，而分析器活着就等价于 scanner 建得出来 —— 见页面上那颗
 * `remember(decoderMissing)`）。
 */
internal fun frameFlowWayBack(health: ScanDecoderHealth): String {
    val used = health.pageVisibleRecoveries
    return if (used >= MaxPageVisibleRecoveries) {
        "回到前台的 $MaxPageVisibleRecoveries 次机会已用完，这一页只剩相册识别一条路"
    } else {
        "回到前台还剩第 ${used + 1}/$MaxPageVisibleRecoveries 次机会（那一次会把相机重新绑回来），相册识别照旧可用"
    }
}

/**
 * 「判死停帧」那一行取证的**全文**（措辞唯一来源；调用点只许 `Log.w(TAG, 本函数)`，
 * 自己拼半句就是第二份口径 —— `ScanFrameFlowGuardTest` ④a 钉着）。
 *
 * 三件事一枚不少，这是这一页修过三轮"静默 no-op"之后的硬口径：
 * ① 帧流到此为止（说了"新的帧不再到达分析器"，并明说 close 的时机没被本卡动过）；
 * ② 为什么 —— 第几帧判的死 + [ScanDecoderHealth.giveUpReason] 原文；
 * ③ 还剩什么活路 —— [frameFlowWayBack]。
 *
 * 级别是 Warn：这一档是"本轮到此为止"的终局判据（与第二引擎那三档同一口径），
 * 而用户那台机器（HyperOS）把 logcat 砍到 Info，`Log.d` 等于没写。
 * 帧号未记录（-1）那一档说实话写"帧号未记录"，不许编一个 0 出来。
 */
internal fun frameFlowStopLogText(health: ScanDecoderHealth): String {
    val frame = health.framesAtLastFailure
    val where = if (frame >= 0L) "第 $frame 帧判死" else "判死（帧号未记录）"
    return "判死之后停止帧流：$where（原因 ${health.giveUpReason ?: "未记"}）—— " +
        "分析流已解绑，新的帧不再到达分析器（已经交出去的那一发仍按原来的 close 路径收掉，" +
        "本卡没动它的时机）。${frameFlowWayBack(health)}"
}
