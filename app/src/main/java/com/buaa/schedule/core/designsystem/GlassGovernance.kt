package com.buaa.schedule.core.designsystem

/**
 * 玻璃渲染治理：低端机自动降档，避免液态玻璃在弱 SoC 上掉帧/发热。
 *
 * 两级钳制：
 * 1. **静态档位**（启动即可判）：按应用可用内存与 CPU 核心数估算设备能力，
 *    把用户选择的玻璃档位钳制到安全上限 —— 不依赖厂商私有 SDK，也不要求人工配置；
 * 2. **运行时降档**（R3 审查 P2-1）：[GlassJankMonitor] 在真机上持续采样帧时长，
 *    连续掉帧时调用 [lowerTierForJank] 把上限再往下压一档。静态判断拿不到的
 *    热节流、GPU 驱动差异等真实负载信息由这条回写路径兜底。
 */
object GlassGovernance {

    /**
     * 两次运行时降档之间的最小间隔：给降档后的渲染一个观察窗口，避免一路压到 OFF。
     * internal 不是放宽语义：[GlassJankMonitor] 要把它连同上次降档时刻一起传进
     * 纯判据内核，让冷却窗口内的 Demote 在发起前就被拦下（判据本体在 GlassJankDecision.kt）。
     */
    internal const val LOWER_COOLDOWN_MS = 10 * 60_000L

    /** 运行时降档上限（null = 未降档）；由 [lowerTierForJank] 维护 */
    @Volatile private var runtimeCap: Int? = null

    /** 上次降档时刻；null = 从未降档（首次调用必须放行，不能因为「0」既是哨兵又是时刻而被冷却窗口吞掉） */
    @Volatile private var lastLowerAt: Long? = null

    /**
     * 静态能力上限（启动后不变）：`Runtime.maxMemory()` 与 `availableProcessors()`
     * 都是 JNI 调用，而 [effectiveTier] 会被周视图几十张卡在拖动期间每帧各读一次
     * （实测约 4.8k 次/秒），因此首次求值后即固定。
     */
    private val staticCap: Int by lazy {
        val maxMemoryMb = Runtime.getRuntime().maxMemory() / 1024L / 1024L
        val cores = Runtime.getRuntime().availableProcessors()
        // 档位只有 OFF / STANDARD 两级（②V-14 删掉了「增强」），判据提取进
        // glassStaticCapTier（零 android import，表驱动单测能逐边界打），阈值分毫未动：
        // 128MB 内存或 4 核以下直接关闭 AGSL 玻璃走 tint 降级，其余设备拿满标准档。
        glassStaticCapTier(maxMemoryMb, cores, DesignTokens.GLASS_TIER_OFF, DesignTokens.GLASS_TIER_STANDARD)
    }

    /** 用户偏好 → 经设备能力 + 运行时降档钳制后的实际生效档位 */
    fun effectiveTier(preferred: Int): Int {
        val runtime = runtimeCap
        val clamped = preferred.coerceAtMost(staticCap)
        return if (runtime != null) clamped.coerceAtMost(runtime) else clamped
    }

    /**
     * 持续掉帧时的回写入口：每次调用把运行时上限压低一档（最低到 OFF），
     * 带冷却窗口防止在瞬时负载（截图/动画风暴）下一路降到底。
     * 只降不升：恢复需要用户主动调档（设置页保存会覆盖生效档位），
     * 避免在"降档→恢复→再掉帧"之间来回震荡。
     */
    fun lowerTierForJank(nowMillis: Long = System.currentTimeMillis()) {
        synchronized(this) {
            if (isGlassLowerCoolingDown(lastLowerAt, nowMillis, LOWER_COOLDOWN_MS)) return
            lastLowerAt = nowMillis
            // 基准必须是可用的最高档：从 Int.MAX_VALUE 往下减得到的 MAX-1 经 coerceAtMost 恒等，
            // 于是"降了一档"实际什么都没降，低端机/热节流场景永不降级（R5 F-18）。
            // 档位只剩两级后，这一步等价于"持续掉帧就关掉玻璃"——冷却窗口是唯一闸门。
            val from = runtimeCap ?: DesignTokens.GLASS_TIER_STANDARD
            runtimeCap = (from - 1).coerceAtLeast(DesignTokens.GLASS_TIER_OFF)
        }
    }

    /** 当前运行时上限（测试与诊断用；null 表示未因掉帧降档） */
    fun runtimeCapForTest(): Int? = runtimeCap

    /**
     * 上次降档时刻（null = 从未降档）：[GlassJankMonitor] 把它连同 [LOWER_COOLDOWN_MS]
     * 传进纯判据内核，冷却窗口在发起 Demote 之前就该被看见。读一次 volatile 字段，
     * 不加锁——它只用于决策参考，真正的闸门仍在本对象 [lowerTierForJank] 的同步块里。
     */
    internal fun lastLowerAtMillis(): Long? = lastLowerAt

    /** 测试用：清掉运行时降档状态（单例在 JVM 测试里会跨用例存活） */
    internal fun resetRuntimeForTest() {
        synchronized(this) {
            runtimeCap = null
            lastLowerAt = null
        }
    }
}