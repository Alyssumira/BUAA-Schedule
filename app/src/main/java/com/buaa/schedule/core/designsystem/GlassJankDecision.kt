package com.buaa.schedule.core.designsystem

/**
 * 运行时掉帧降档的纯判据内核（T53）。
 *
 * 零 android / androidx import：设备事实（帧数、坏帧数、窗口时长、冷却时间戳、
 * 内存/核数）一律当参数传进来，debug 分支也是参数——判据本体能在 JVM 单测里
 * 逐边界跑表，不再和窗口挂接、Handler 注册那些只能在真机上验的事纠缠在一起。
 * （T52 基线实测：整条采样链在设备上从未跑起来过一次，而判据同样无从单测，
 * 两处都是把设备事实和规则写死在同一个 lambda 里的代价。）
 * 调用点只许消费这里返回的动作，不许拿着布尔再自己补判 Build.VERSION.SDK_INT。
 */

/** 一个采样窗口收尾时能走出的动作（[glassJankWindowAction] 的返回值） */
internal enum class GlassJankWindowAction {
    /** 窗口还没满 10 秒：计数继续累积，什么都不做 */
    WindowOpen,

    /** debug：只打日志，永不降档 */
    DebugReport,

    /** release：帧数不足或坏帧占比未达阈值——清空「连续坏窗口」连击 */
    GoodWindow,

    /** release：第一个坏窗口——记下连击，再等一个窗口确认 */
    FirstBadWindow,

    /** release：连续两个坏窗口，但落在 [GlassGovernance] 的降档冷却里——连击作废重来 */
    CooldownHold,

    /** release：连续两个坏窗口且过了冷却——执行降档 */
    Demote,
}

/**
 * 一个采样窗口收尾时的完整决策。阈值不进内核当默认值：
 * minFrames / jankRatePercent / lowerCooldownMillis 由调用点传入，
 * 单测才能拿 99/100 帧、恰好 25%、冷却差 1ms 这些边界逐个打表。
 */
internal fun glassJankWindowAction(
    debug: Boolean,
    windowElapsedMillis: Long,
    sampleWindowMillis: Long,
    frames: Long,
    jankFrames: Long,
    minFrames: Long,
    jankRatePercent: Long,
    previousWindowBad: Boolean,
    nowMillis: Long,
    lastLowerAtMillis: Long?,
    lowerCooldownMillis: Long,
): GlassJankWindowAction {
    if (windowElapsedMillis < sampleWindowMillis) return GlassJankWindowAction.WindowOpen
    if (debug) return GlassJankWindowAction.DebugReport
    // 占比判定用整数乘式、阈值含边界（>=）：与改前 lambda 里的写法逐字同口径，
    // 恰好 25%、恰好 100 帧都算坏窗口。静止画面帧数太少容易误判，所以先过 minFrames。
    val badWindow = frames >= minFrames && jankFrames * 100L >= frames * jankRatePercent
    if (!badWindow) return GlassJankWindowAction.GoodWindow
    // release 要连续两个坏窗口才降档，进一步排除瞬时负载
    if (!previousWindowBad) return GlassJankWindowAction.FirstBadWindow
    return if (isGlassLowerCoolingDown(lastLowerAtMillis, nowMillis, lowerCooldownMillis)) {
        GlassJankWindowAction.CooldownHold
    } else {
        GlassJankWindowAction.Demote
    }
}

/**
 * 降档冷却判据（now - last < cooldown）：与 [GlassGovernance.lowerTierForJank] 内部
 * 那条闸门同一表达式提取——判据只写一处，冷却窗口内的 Demote 根本不会被发起，
 * 也不会再打出「已降档」的误导性日志。
 */
internal fun isGlassLowerCoolingDown(
    lastLowerAtMillis: Long?,
    nowMillis: Long,
    lowerCooldownMillis: Long,
): Boolean = lastLowerAtMillis != null && nowMillis - lastLowerAtMillis < lowerCooldownMillis

/**
 * 静态能力上限：128MB 内存或 4 核以下关闭 AGSL 玻璃走 tint 降级，其余拿满标准档。
 * 阈值原样来自 [GlassGovernance.staticCap]；档位值也当参数传，内核便不依赖
 * DesignTokens 的任何 compose 导入。
 */
internal fun glassStaticCapTier(
    maxMemoryMb: Long,
    cores: Int,
    tierOff: Int,
    tierStandard: Int,
): Int = if (maxMemoryMb <= 128L || cores <= 4) tierOff else tierStandard

/**
 * 生效档位 = 静态上限与运行时降档**两把钳子**的合取（T315）。
 *
 * 判据本体从 `GlassGovernance.effectiveTier` 的函数体逐字搬来，一条表达式都没改：
 * `preferred` 先被静态上限夹，再（仅当运行时降档存在时）被运行时上限夹。
 * 为什么要单独成函数：原先那一格唯一覆盖把 `assumeTrue` 的前提写成
 * `GlassGovernance.effectiveTier(standard) == standard` —— 假设式就是被测函数自己，
 * 而它的另一枚输入是本机核数，于是「静态判 OFF」的机器（`cores <= 4`，含本仓 CI 那档 4 vCPU）
 * 整格静默跳过，`effectiveTier` 在全测试树里当场一个字节都不说。判据抽出来当参数、
 * 表驱动逐格打之后，任何核数上这一格都要说话；设备事实（内存/核数、降档状态）仍留在调用点。
 *
 * 纯 JVM 判据边界照旧：本函数不读设备度量、不读时钟，`Runtime` 那两枚探针与
 * `availableProcessors()` 都只出现在调用点传进来的参数里。
 */
internal fun glassEffectiveTier(
    preferred: Int,
    staticCap: Int,
    runtimeCap: Int?,
): Int {
    val clamped = preferred.coerceAtMost(staticCap)
    return if (runtimeCap != null) clamped.coerceAtMost(runtimeCap) else clamped
}
