package com.buaa.schedule.core.designsystem

import android.os.SystemClock
import com.buaa.schedule.domain.schedule.glassLowerAtForCooldown

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

    /**
     * 上次降档时刻；null = 从未降档（首次调用必须放行，不能因为「0」既是哨兵又是时刻而被冷却窗口吞掉）。
     *
     * T416丁：刻度是 `SystemClock.elapsedRealtime()`（进程内经过的时间），**不是墙上钟**，也不落盘。
     * 改前这里收的是调用点递来的 `System.currentTimeMillis()`，量的却是「离上次降档过了多久」；
     * 墙上钟会被用户改表 / 时区与闰秒校正 / NITZ 下发往回拨，
     * 而冷却判据那条差值一旦为负就恒小于 [LOWER_COOLDOWN_MS] ⇒ 冷却永不结束、降档链从此静默停摆
     * （本仓同族判据见 `domain/session/RestoreBackoffWindowGate.kt`，倒退分档的护栏在
     * `domain/schedule/GlassMonotonicWindowGate.kt`）。可空这一格本身已经是「从没降过」的表达式，
     * 与刻度起点零不共用同一格（#496 那一族在这里结构上不成立）。
     */
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

    /**
     * 用户偏好 → 经设备能力 + 运行时降档钳制后的实际生效档位。
     *
     * 判据本体在 `GlassJankDecision.kt` 的 `glassEffectiveTier`（T315 抽出，两条表达式逐字搬，
     * 阈值与夹取顺序分毫未动）：这里只把两枚设备事实当参数递进去 —— 静态能力上限 [staticCap]
     * （首次求值后固定，读的是 `Runtime.maxMemory()` 与 `availableProcessors()`）与运行时降档
     * 上限 [runtimeCap]。抽出去只为了"测试说不说得出话"：改前那一格把 `assumeTrue` 的前提写成
     * 本函数的返回值，而本函数的另一枚输入就是本机核数 ⇒ 核数判 OFF 的机器上整格静默跳过，
     * `effectiveTier` 在全测试树里当场一个字节都不说（台账 #561 那一族「唯一覆盖说不了话」）。
     * 产品行为一条没改：任何核数、任何降档状态下同一枚输入的返回值与改前逐枚相同。
     */
    fun effectiveTier(preferred: Int): Int = glassEffectiveTier(preferred, staticCap, runtimeCap)

    /**
     * 持续掉帧时的回写入口：每次调用把运行时上限压低一档（最低到 OFF），
     * 带冷却窗口防止在瞬时负载（截图/动画风暴）下一路降到底。
     * 只降不升：恢复需要用户主动调档（设置页保存会覆盖生效档位），
     * 避免在"降档→恢复→再掉帧"之间来回震荡。
     *
     * `nowMillis` 的刻度是 `elapsedRealtime()`（T416丁），与 [GlassJankMonitor] 帧回调里那枚 `now`
     * 是同一把尺：调用点显式把它递进来，两侧不许各读各的钟。上一刻的读数先过
     * [glassLowerAtForCooldown] 的倒退护栏，再交给在册的那一条 `isGlassLowerCoolingDown`
     * —— 闸门只有一处，这里只负责把「刻度」与「倒退」两件事说清楚。
     */
    fun lowerTierForJank(nowMillis: Long = SystemClock.elapsedRealtime()) {
        synchronized(this) {
            if (isGlassLowerCoolingDown(glassLowerAtForCooldown(lastLowerAt, nowMillis), nowMillis, LOWER_COOLDOWN_MS)) {
                return
            }
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
     * 刻度同 [lastLowerAt]：`elapsedRealtime()`，调用点不得换成别的钟。
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
