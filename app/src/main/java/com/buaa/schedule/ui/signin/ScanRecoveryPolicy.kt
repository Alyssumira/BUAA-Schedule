package com.buaa.schedule.ui.signin

/**
 * 扫码链"坏了一帧"与"整条坏了"的分界（T59①②）。纯判据内核，零 android/androidx import。
 *
 * 这一页被用户报「扫码没反应」两次，两次都是**判定**写成了不可恢复的事实：
 * - T44 拆掉的是 `consumed: Boolean` 那颗只关不开的闸门；
 * - 这一轮拆的是 `scannerWorking` 这颗同样只关不开的开关：ML Kit 的 `onFailure` 与
 *   `process()` 同步抛两条出口都只写 `false`，而全仓没有任何一处写回 `true` ——
 *   **一帧瞬时失败就把相机这条判死到整页结束**，还说用户"这台设备用不了相机扫码"。
 *   绑定那一侧同型：`bindToLifecycle` 抛过一次之后 `cameraError` 有值，但它不是那颗
 *   effect 的键，于是没有任何东西会再跑一次绑定。
 *
 * 所以判据收在这里：**关得掉，也要按有界的条件开回来**，而且"为什么关、还剩几次额度"
 * 必须是一个说得出口的值（进那一行取证日志）。设备侧的事实（帧号、墙钟以外的单调时钟、
 * 权限、绑定失败的原因原文）一律由调用点当参数传进来 —— 仓库口径，见 [ScanSubmissionGate]
 * 与 `GlassJankDecision`：判据一旦自己去读 `Build.VERSION`/系统时钟，JVM 表驱动单测就到位了。
 *
 * ⚠️ 有界这一条不是修辞：允许"试回来"就可能变成拿一帧一帧去撞一颗真的坏掉的解码器。
 * 三道界都在本文件里：连续失败才停用（[ConsecutiveDecodeFailureLimit]）、停用-试回的轮数
 * （[MaxDecodeSuspensionCycles]）、以及"回到前台再给一次机会"的次数
 * （[MaxPageVisibleRecoveries]）。用完就是用完 —— 那一档界面继续显示既有降级文案，
 * 不假装还有救。
 */

/**
 * 连续多少帧解码失败才算"这台设备的解码器在坏"。
 *
 * 3 帧的账：单帧失败是常态（画面糊、没对准、曝光不足），连错 3 帧在正常取景里几乎不发生。
 * 再小（1）就是把一次抖动判成永久；再大就是在坏掉的解码器上多撞几帧、白烧电。
 */
internal const val ConsecutiveDecodeFailureLimit = 3

/** 一轮停用先压掉多少帧（第 N 轮压 `N+1` 倍，见 [suspendWindowFrames]）：20 帧按 20-30fps 算是 1 秒弱 */
internal const val DecodeFailureSuspendWindowFrames = 20

/** 停用-试回最多几轮，用完就放弃这一页剩下的时间：3 轮之后还在坏，就不是"抖一下" */
internal const val MaxDecodeSuspensionCycles = 3

/** 回到前台（ON_RESUME）最多给几次"再试一轮"的额度：权限弹框回来、切回来各算一次，2 次足够 */
internal const val MaxPageVisibleRecoveries = 2

/**
 * 解码器的状态。整枚换引用、字段全不可变：写它的是分析流那一个线程，
 * 读它的是主线程（取 `scannerWorking` 与原因），两颗线程各拿一份真相比各拿一半好。
 *
 * 停用与放弃**只用帧号表达**，不记墙钟：读墙钟的口子在这一页要钉死成三处
 * （[ScanSubmissionGate] 那三处，见 `ScanSubmissionGateTest` ⑨），
 * 而"帧还在到达"本来就是这里的观测量 —— 用帧数当时间轴，既不需要取钟，也顺带把
 * 时钟回摆那一类意外排除在外。
 *
 * @param consecutiveFailures 当前连续失败的帧数（同一帧二次报错不重复计）
 * @param framesAtLastFailure 上一次失败落在第几帧，-1 = 还没失败过
 * @param suspendUntilFrame 停用到第几帧为止，-1 = 没停用；过了这一帧就放一帧去探
 * @param suspensionCycles 已经停用-试回过几轮
 * @param pageVisibleRecoveries 已经用掉几次"回到前台再试"的额度
 * @param giveUpReason 非空 = 本轮判死、不再自动试；内容是给用户以外的取证看的原因
 */
internal data class ScanDecoderHealth(
    val consecutiveFailures: Int = 0,
    val framesAtLastFailure: Long = -1L,
    val suspendUntilFrame: Long = -1L,
    val suspensionCycles: Int = 0,
    val pageVisibleRecoveries: Int = 0,
    val giveUpReason: String? = null,
)

/** 这一帧能不能喂给解码器。`Probe` 与 `Decode` 都做，区别只在前一帧是停用中的最后一帧。 */
internal enum class DecoderFrameAction { Decode, Probe, Skip }

/** 绑定失败分成两支：只有"可能瞬时"那一支才配占重试额度 */
internal enum class CameraBindFailure {
    /** 这台设备就没有后置摄像头（平板、部分模拟器）：重试治不好它，一次也不要多试 */
    NoBackCamera,

    /** 其余都按"可能是瞬时的"处理：相机服务正被别的进程占着、CameraX 内部状态没就绪 */
    Retryable,
}

/**
 * 绑定失败的原因分类。判据是失败原因的**原文**（调用点从 `Throwable.message` 读出来传进来），
 * 所以这一条仍然能在 JVM 里逐支打表 —— 拿异常类名当判据就得引 android 的类。
 *
 * 认不出的那一档一律算可重试：少重试一次的代价是用户回一次前台，
 * 少错判一次的代价是把"这台设备没有后置摄像头"说成"相机暂时不可用"，后者本来就是既有文案。
 */
internal fun classifyCameraBindFailure(message: String?): CameraBindFailure {
    val text = message?.lowercase() ?: return CameraBindFailure.Retryable
    val noCamera = listOf(
        "not available", "no available", "no camera", "no supported",
        "cannot be opened", "in use", "lens facing", "incompatible",
    )
    return if (noCamera.any { text.contains(it) }) CameraBindFailure.NoBackCamera else CameraBindFailure.Retryable
}

/** 相机绑定的尝试次数上限（含首试）：3 次已经盖住"服务正忙"那一档，再多就是刷绑定 */
internal const val MaxCameraBindAttempts = 3

/** 绑定的退避基数；第 N 次失败后等 `N * 基数`，最长 400+800 = 1.2 秒，远短于用户会盯住的耐心 */
internal const val CameraBindBackoffMillis = 400L

/** 第 [attempt] 次（从 1 数起，1 = 首试）失败之后还要不要再试一次 */
internal fun cameraBindRetryAllowed(attempt: Int, failure: CameraBindFailure): Boolean =
    failure == CameraBindFailure.Retryable && attempt in 1 until MaxCameraBindAttempts

/** 第 [attempt] 次失败之后的退避；0（还没失败过）不退避 */
internal fun cameraBindBackoffMillis(attempt: Int): Long =
    if (attempt < 1) 0L else CameraBindBackoffMillis * attempt

/** 停用窗口随轮数线性变长：越是一直坏，越不值得马上去撞 */
internal fun suspendWindowFrames(cycles: Int): Long =
    DecodeFailureSuspendWindowFrames.toLong() * (cycles.coerceAtLeast(0) + 1)

/** 这一档相机解码还算不算"活着"（界面 `scannerWorking` 的取值，也是 [scanCameraLive] 的一项输入） */
internal fun scannerWorkingOf(health: ScanDecoderHealth): Boolean =
    health.giveUpReason == null && health.suspendUntilFrame < 0L

/**
 * `scannerWorking == false` 的两种病因在这里分档（T59b②）：**暂时**（停用窗口内）还是**已判死**。
 *
 * 停用窗口内（`suspendUntilFrame >= 0` 且没判死）最多压 20/40/60 帧，窗口过完放一帧去探，
 * 解出来一次就整枚清零 —— 这是暂时的，而且正在自己试回来；已判死（连错满
 * [ConsecutiveDecodeFailureLimit] 帧 × 自动试回 [MaxDecodeSuspensionCycles] 轮仍不成，
 * 或"这一档坏到额度用完"那类把 [ScanDecoderHealth.giveUpReason] 写上的路径）才是事实。
 * 界面的文案与取景框按这一颗分档，⚠️ UI 侧不许绕过这里直接读 giveUpReason 拼分支 ——
 * 判据一散，"暂时"就又会在整个界面上长成"这台设备用不了"。
 */
internal fun scannerGiveUp(health: ScanDecoderHealth): Boolean = health.giveUpReason != null

/**
 * 第 [frameSerial] 帧该不该解。
 *
 * 停用期间帧**照旧到达**（分析流不解绑，只是不再喂解码器）：这是本判据的观测前提 ——
 * 帧还在流就证明"坏的是解码器不是相机"，而帧流本身停了则要把恢复交给回到前台那一档。
 */
internal fun decoderFrameAction(health: ScanDecoderHealth, frameSerial: Long): DecoderFrameAction = when {
    health.giveUpReason != null -> DecoderFrameAction.Skip
    health.suspendUntilFrame < 0L -> DecoderFrameAction.Decode
    frameSerial < health.suspendUntilFrame -> DecoderFrameAction.Skip
    else -> DecoderFrameAction.Probe
}

/**
 * 一次解码失败之后健康度怎么变。
 *
 * 同一帧的二次报错（`process()` 同步抛之后，那一帧的异步回调也可能再落一次）只算一次，
 * 否则一轮抖动就能把退避窗口白白翻倍。
 */
internal fun healthAfterDecodeFailure(health: ScanDecoderHealth, frameSerial: Long): ScanDecoderHealth {
    if (health.giveUpReason != null) return health
    val sameFrame = frameSerial == health.framesAtLastFailure
    val consecutive = if (sameFrame) health.consecutiveFailures else health.consecutiveFailures + 1
    // 停用窗口过完后的那一帧（Probe）又失败 ⇒ 这一轮"试回来"用掉了
    val cycles = if (!sameFrame && health.suspendUntilFrame >= 0L) health.suspensionCycles + 1 else health.suspensionCycles
    if (cycles >= MaxDecodeSuspensionCycles) {
        return health.copy(
            consecutiveFailures = consecutive,
            framesAtLastFailure = frameSerial,
            suspensionCycles = cycles,
            giveUpReason = "连续 $consecutive 帧解码失败、自动试回 $cycles 轮仍不成",
        )
    }
    if (health.suspendUntilFrame < 0L && consecutive < ConsecutiveDecodeFailureLimit) {
        // 偶发一帧：不停用任何东西，这正是旧写法把 false 写进 scannerWorking 的那一档
        return health.copy(consecutiveFailures = consecutive, framesAtLastFailure = frameSerial)
    }
    return health.copy(
        consecutiveFailures = consecutive,
        framesAtLastFailure = frameSerial,
        suspensionCycles = cycles,
        suspendUntilFrame = frameSerial + suspendWindowFrames(cycles),
    )
}

/**
 * 一次"解码任务正常返回"（有没有解出码都算）之后：解码器证明了自己能用，
 * 停用与轮数一起清零。坏掉的解码器不会走到这里，所以这不是把预算白送给它。
 */
internal fun healthAfterDecodeSuccess(health: ScanDecoderHealth): ScanDecoderHealth {
    if (health.consecutiveFailures == 0 && health.suspendUntilFrame < 0L && health.suspensionCycles == 0) {
        return health
    }
    return ScanDecoderHealth(pageVisibleRecoveries = health.pageVisibleRecoveries)
}

/**
 * 页面重新可见（ON_RESUME）：给不给再试一轮。
 *
 * 额度用完了就原样返回 —— 那一档界面上留着的是既有文案「这台设备用不了相机扫码，请改用相册识别」，
 * 而它说的是这一页此刻的真实处境。
 */
internal fun healthAfterPageVisible(health: ScanDecoderHealth): ScanDecoderHealth {
    if (scannerWorkingOf(health)) return health
    if (health.pageVisibleRecoveries >= MaxPageVisibleRecoveries) return health
    return ScanDecoderHealth(pageVisibleRecoveries = health.pageVisibleRecoveries + 1)
}

/**
 * 解出一枚条码、但那枚条码**没有可读原文**：这种帧既不产码也不算"什么都没解出来"，
 * 旧写法（`?.let {}`）让它静默吞掉，于是它与"这一帧真的什么都没看见"在证据上完全一样。
 * 计数留痕的节流判据在这里，调用点只管数。
 */
internal const val ValuelessBarcodeLogStride = 50L

/** 第 [count] 次命中要不要留一行日志：第一次必留（否则"从来没发生过"与"发生过一次"读不出来），之后每 [ValuelessBarcodeLogStride] 次一次 */
internal fun shouldLogValuelessBarcode(count: Long): Boolean =
    count == 1L || (count > 0L && count % ValuelessBarcodeLogStride == 0L)
