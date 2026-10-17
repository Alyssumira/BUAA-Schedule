package com.buaa.schedule.core.designsystem

import com.buaa.schedule.BuildConfig
import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import com.buaa.schedule.domain.schedule.glassLowerAtForCooldown
import com.buaa.schedule.domain.schedule.glassMonotonicElapsedMillis
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

/**
 * 玻璃/UI 性能观测层（JankStats 的轻量替代）。
 *
 * 两条工作路径（R3 审查 P2-1 的「无回写路径」补全）：
 * - **Debug**：统计丢帧帧数与总帧数并输出 logcat，供人工排查；
 * - **Release**：极简采样（只做两次整数比较），当窗口内 jank 率持续超阈值时
 *   回写给 [GlassGovernance.lowerTierForJank] 降一档玻璃档位，然后继续观察；
 *   只有已经压到 OFF（再无降级余地）才停用采样，不给正常设备留常驻每帧开销。
 *   静态档位判断拿不到的热节流/GPU 驱动差异，由这条运行时回写兜底。
 *
 * 判据本体（什么算坏窗口、要不要降档）剥在 GlassJankDecision.kt 的纯函数里，
 * 这里只做设备事实的采集与动作的分发。
 *
 * 生命周期：Activity 创建时 [attach]、销毁时 [detach]。此前只 attach 不 detach，
 * 静态的 "已启动" 标志会让旋转/重建后的新 Activity 一次采样都拿不到。
 * 注册不发生在 [attach] 的调用栈里，而是延到窗口真正附加那一刻（见 [attach] 注释）。
 */
object GlassJankMonitor {

    private const val TAG = "GlassJank"
    private const val SAMPLE_WINDOW_MS = 10_000L

    /** Release 降档判定：窗口内掉帧占比达到该值（含）视为坏窗口 */
    private const val RELEASE_JANK_RATE_PERCENT = 25L

    /** Release 降档判定：窗口至少要有这么多帧才可信（静止画面帧数太少容易误判） */
    private const val RELEASE_MIN_FRAMES = 100L

    /**
     * 用弱引用持有宿主，避免静态字段长期拽住 Activity。
     *
     * T416乙：这两枚走的是**双向**跨线程通道——主线程在 [attach] 里写、延出去的 `decorView.post` 读出来验身份；
     * 后台线程在降档收尾时经 [detachInternal] 把它们写 null。缺可见性时旧 Window 的每帧监听摘不掉、
     * 也可能摘错人，从此一直每帧回调（耗电），而 release 侧「压到 OFF 就停采样」永不落地。
     */
    @Volatile private var attachedActivity: WeakReference<Activity>? = null

    /** 当前挂在 Window 上的那枚监听：摘除时按它找人，读它的线程与写它的线程不同（见 [attachedActivity]） */
    @Volatile private var listener: Window.OnFrameMetricsAvailableListener? = null

    /** 监听器真正挂上了 Window 才允许摘：没挂上就去 remove 会撞上平台的「never added」 */
    @Volatile private var registered = false

    /** FrameMetrics 回调线程的 Handler：进程内建一次，跨宿主切换复用（见 [frameMetricsHandler]） */
    @Volatile private var callbackHandler: Handler? = null

    /**
     * T416丙：这两枚是「读-改-写」，`@Volatile` 只给可见性不给原子性——后台线程的自增与主线程
     * attach / detachInternal 的整枚清零不成对，会丢更新、把窗口起点复活，于是
     * [RELEASE_MIN_FRAMES] 与 [SAMPLE_WINDOW_MS] 那两把尺按错的帧数算。换成原子量：
     * incrementAndGet / set 都不阻塞，帧回调里仍然零锁（在册约束：回调里引可阻塞的锁本身就造 jank）。
     */
    private val totalFrames = AtomicLong()

    private val jankFrames = AtomicLong()

    /** 窗口起点只被赋值、不被自增，volatile 赋值够用；刻度是 `elapsedRealtime()`（见 [glassMonotonicElapsedMillis]，T416丁） */
    @Volatile private var windowStart = 0L

    /** release 降档完成后置 true：停止采样，进程内不再有每帧回调 */
    @Volatile private var releaseDeactivated = false

    /**
     * 记录上一个窗口是否同样超标：release 需要连续两个坏窗口才降档。
     *
     * T416甲：读它在后台线程（每帧收尾那一次），写它分两头——同一枚后台线程的收尾分支与主线程
     * [detachInternal] 的归位。缺可见性时「连续两个坏窗口」这枚记忆跨线程不成对，
     * 单个坏窗口就能把玻璃压到 OFF，而 [GlassGovernance] 只降不升 ⇒ 用户看到玻璃质感莫名消失且回不来。
     * 赋值都是绝对值（不是自增），所以 @Volatile 与同块其余兄弟字段齐平即为充分修法。
     */
    @Volatile private var lastWindowBad = false

    fun attach(activity: Activity) {
        if (releaseDeactivated && !BuildConfig.DEBUG) return
        if (attachedActivity?.get() === activity) return
        // 宿主变了（旋转/重建）：先摘掉旧的监听，再挂新的
        attachedActivity?.get()?.let { detachInternal(it) }
        totalFrames.set(0L)
        jankFrames.set(0L)
        windowStart = SystemClock.elapsedRealtime()
        val debug = BuildConfig.DEBUG
        val newListener = Window.OnFrameMetricsAvailableListener { _, frameMetrics, _ ->
            // 原子自增顺手交回自增后的值：改前是「先 ++ 再读 volatile」，两记之间能被清零插进来（T416丙）
            val frames = totalFrames.incrementAndGet()
            val duration = frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION)
            // 视觉帧预算 16ms；超过 32ms 算明显掉帧
            if (duration > 32_000_000L) jankFrames.incrementAndGet()
            // T416丁：量的全是进程内经过的时间（10 秒采样窗 + 10 分钟降档冷却），走单调刻度，
            // 且与 GlassGovernance.lowerTierForJank 的默认刻度是同一把尺——只换一侧就是跨一秒读两只钟
            val now = SystemClock.elapsedRealtime()
            val jank = jankFrames.get()
            // 每帧只多两次纯函数调用与一次 volatile 读：窗口没收尾就原样返回 WindowOpen，
            // 不分配、不打日志、不加锁；收尾动作（判据见 glassJankWindowAction）10 秒才走一遍。
            val action = glassJankWindowAction(
                debug = debug,
                windowElapsedMillis = glassMonotonicElapsedMillis(now, windowStart),
                sampleWindowMillis = SAMPLE_WINDOW_MS,
                frames = frames,
                jankFrames = jank,
                minFrames = RELEASE_MIN_FRAMES,
                jankRatePercent = RELEASE_JANK_RATE_PERCENT,
                previousWindowBad = lastWindowBad,
                nowMillis = now,
                lastLowerAtMillis = glassLowerAtForCooldown(GlassGovernance.lastLowerAtMillis(), now),
                lowerCooldownMillis = GlassGovernance.LOWER_COOLDOWN_MS,
            )
            if (action == GlassJankWindowAction.WindowOpen) return@OnFrameMetricsAvailableListener
            totalFrames.set(0L)
            jankFrames.set(0L)
            windowStart = now
            when (action) {
                GlassJankWindowAction.WindowOpen -> Unit
                GlassJankWindowAction.DebugReport -> {
                    // 字面量 BuildConfig.DEBUG 在这里再挡一道，别当成冗余 if 顺手删：
                    // DebugReport 只在 debug == true 时由判据内核返回，release 运行时永远走不到
                    // 这一支，但分支选择取决于运行时的枚举值，R8 折叠不动——没有这道字面量闸门，
                    // "frames=/jank=/jankRate=" 就留在 release dex 的常量池里（T53 把判据剥成
                    // 纯函数后真漏进去过）。ReleaseForensicLogSurvivalTest 的反向对照
                    // DEBUG_ONLY_FRAGMENTS 判的正是 "jankRate=" 在 release 产物里查无此文，
                    // 它一泄漏，那整个第 3 层「取证行还活着」的证明力就废掉。
                    // 注意闸门必须是 BuildConfig.DEBUG 字面量本身，局部 val debug 折叠不掉。
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            TAG,
                            "frames=$frames jank=$jank jankRate=${if (frames == 0L) 0.0 else jank * 100.0 / frames}",
                        )
                    }
                }
                GlassJankWindowAction.GoodWindow -> lastWindowBad = false
                GlassJankWindowAction.FirstBadWindow -> lastWindowBad = true
                GlassJankWindowAction.CooldownHold -> {
                    // 冷却期内不许连击：过了冷却重新要求「连续两个坏窗口」再降
                    lastWindowBad = false
                }
                GlassJankWindowAction.Demote -> {
                    lastWindowBad = false
                    GlassGovernance.lowerTierForJank(now)
                    Log.w(TAG, "持续掉帧，玻璃档位已运行时降档")
                    if (GlassGovernance.runtimeCapForTest() == DesignTokens.GLASS_TIER_OFF) {
                        // 已经压到最低档，再无降级余地：停止采样，进程内不再留每帧回调
                        releaseDeactivated = true
                        attachedActivity?.get()?.let { detachInternal(it) }
                    } else {
                        // 还能再降一档：观察窗口继续开着。
                        // 此前这里也一并停用采样，而 attach() 遇到该标志就直接 return，
                        // 于是 release 包里整条回写路径只此一次（R5 F-18）。
                    }
                }
            }
        }
        listener = newListener
        attachedActivity = WeakReference(activity)
        // 注册延到窗口真正附加的那一刻，而不是 attach() 的调用栈里——原写法在 onCreate、
        // setContent 之前就地注册，实测整条链是死的（T52 基线：debug 包同一进程 GlassDiag
        // 1197 行、GlassJank 0 行、ps -T 里没有 FrameMetrics 线程），而 runCatching 把
        // 注册异常吞得看不见。机制取证（本机唯一带实现的 sources 包 android-37.0，
        // 完整命令与结论在 GlassJankMonitorWiringGuardTest 的类注释和 T53 提交消息里）：
        // 平台链 View.addFrameMetricsListener → FrameMetricsObserver → HardwareRendererObserver
        // 现在对 null Handler 直接抛 NPE（HardwareRendererObserver.java:69），老的「null 就
        // 自起 FrameMetrics HandlerThread」兜底已删；Window 层另有一条 decor 为空抛
        // IllegalStateException 的分支（Window.java:1046）。对 decorView post 会在
        // dispatchAttachedToWindow 之后执行：那一刻两条死路都不再可走。
        activity.window.decorView.post {
            // post 的空窗期里宿主可能已被换掉（旋转）或销毁：验身份再注册，
            // 旧监听绝不挂去新窗口，也不给已死宿主起回调线程
            if (attachedActivity?.get() !== activity || listener !== newListener) return@post
            if (releaseDeactivated && !debug) return@post
            registerFrameMetricsListener(activity, newListener)
        }
    }

    private fun registerFrameMetricsListener(
        activity: Activity,
        frameListener: Window.OnFrameMetricsAvailableListener,
    ) {
        // 注册失败必须留痕、release 也要看得见：这道闸门是「运行时自保护的自保护」，
        // 静默失败等于降档链再次死而无证（T53 收的就是这条无声债）。
        runCatching {
            activity.window.addOnFrameMetricsAvailableListener(frameListener, frameMetricsHandler())
        }.onSuccess {
            registered = true
            Log.d(TAG, "FrameMetrics 监听已注册，每 ${SAMPLE_WINDOW_MS / 1000}s 出一份采样窗口")
        }.onFailure {
            Log.e(TAG, "FrameMetrics 监听注册失败，运行时降档对本窗口不可用", it)
        }
    }

    /**
     * FrameMetrics 回调线程：平台不再替应用兜底 null Handler（老的隐式 HandlerThread
     * 兜底已删，见 [attach] 注释），只能自己建。进程内建一次、跨宿主切换复用，
     * 名字沿用平台旧行为叫 "FrameMetrics"，装机取证还能按同一条 `ps -T` 过滤。
     * 只在注册时调用，每帧热路径不经过这里。
     */
    private fun frameMetricsHandler(): Handler =
        callbackHandler ?: synchronized(this) {
            callbackHandler ?: Handler(HandlerThread("FrameMetrics").apply { start() }.looper)
                .also { callbackHandler = it }
        }

    /** Activity 销毁时必须调用，否则监听会一直挂在已废弃的 Window 上 */
    fun detach(activity: Activity) {
        if (attachedActivity?.get() !== activity) return
        detachInternal(activity)
    }

    private fun detachInternal(activity: Activity) {
        val current = listener
        listener = null
        attachedActivity = null
        lastWindowBad = false
        val wasRegistered = registered
        registered = false
        if (current != null && wasRegistered) {
            runCatching { activity.window.removeOnFrameMetricsAvailableListener(current) }
                .onFailure { Log.e(TAG, "FrameMetrics 监听摘除失败", it) }
        }
        totalFrames.set(0L)
        jankFrames.set(0L)
    }
}
