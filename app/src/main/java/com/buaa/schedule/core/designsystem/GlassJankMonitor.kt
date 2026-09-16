package com.buaa.schedule.core.designsystem

import com.buaa.schedule.BuildConfig
import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import java.lang.ref.WeakReference

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
 * 生命周期：Activity 创建时 [attach]、销毁时 [detach]。此前只 attach 不 detach，
 * 静态的 "已启动" 标志会让旋转/重建后的新 Activity 一次采样都拿不到。
 */
object GlassJankMonitor {

    private const val TAG = "GlassJank"
    private const val SAMPLE_WINDOW_MS = 10_000L

    /** Release 降档判定：窗口内掉帧占比超过该值视为持续卡顿 */
    private const val RELEASE_JANK_RATE_PERCENT = 25

    /** Release 降档判定：窗口至少要有这么多帧才可信（静止画面帧数太少容易误判） */
    private const val RELEASE_MIN_FRAMES = 100L

    /** 用弱引用持有宿主，避免静态字段长期拽住 Activity */
    private var attachedActivity: WeakReference<Activity>? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null

    @Volatile private var totalFrames = 0L

    @Volatile private var jankFrames = 0L

    @Volatile private var windowStart = 0L

    /** release 降档完成后置 true：停止采样，进程内不再有每帧回调 */
    @Volatile private var releaseDeactivated = false

    fun attach(activity: Activity) {
        if (releaseDeactivated && !BuildConfig.DEBUG) return
        if (attachedActivity?.get() === activity) return
        // 宿主变了（旋转/重建）：先摘掉旧的监听，再挂新的
        attachedActivity?.get()?.let { detachInternal(it) }
        totalFrames = 0L
        jankFrames = 0L
        windowStart = System.currentTimeMillis()
        val debug = BuildConfig.DEBUG
        val newListener = Window.OnFrameMetricsAvailableListener { _, frameMetrics, _ ->
            totalFrames++
            val duration = frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION)
            // 视觉帧预算 16ms；超过 32ms 算明显掉帧
            if (duration > 32_000_000L) jankFrames++
            val now = System.currentTimeMillis()
            if (now - windowStart >= SAMPLE_WINDOW_MS) {
                val frames = totalFrames
                val jank = jankFrames
                totalFrames = 0L
                jankFrames = 0L
                windowStart = now
                if (debug) {
                    Log.d(
                        TAG,
                        "frames=$frames jank=$jank jankRate=${if (frames == 0L) 0.0 else jank * 100.0 / frames}",
                    )
                } else {
                    val badWindow = frames >= RELEASE_MIN_FRAMES &&
                        jank * 100L >= frames * RELEASE_JANK_RATE_PERCENT
                    if (badWindow) {
                        // release：连续两个窗口超标才降档，进一步排除瞬时负载
                        if (isConsecutiveBadWindow()) {
                            GlassGovernance.lowerTierForJank()
                            Log.w(TAG, "持续掉帧，玻璃档位已运行时降档")
                            if (GlassGovernance.runtimeCapForTest() == DesignTokens.GLASS_TIER_OFF) {
                                // 已经压到最低档，再无降级余地：停止采样，进程内不再留每帧回调
                                releaseDeactivated = true
                                attachedActivity?.get()?.let { detachInternal(it) }
                            } else {
                                // 还能再降一档：观察窗口继续开着。
                                // 此前这里也一并停用采样，而 attach() 遇到该标志就直接 return，
                                // 于是 release 包里整条回写路径只此一次（R5 F-18）。
                                lastWindowBad = false
                            }
                        }
                    } else {
                        // 窗口恢复达标：清空连击计数，重新要求"连续两个坏窗口"
                        lastWindowBad = false
                    }
                }
            }
        }
        listener = newListener
        attachedActivity = WeakReference(activity)
        runCatching { activity.window.addOnFrameMetricsAvailableListener(newListener, null) }
    }

    /** 记录上一个窗口是否同样超标：release 需要连续两个坏窗口才降档 */
    private var lastWindowBad = false

    private fun isConsecutiveBadWindow(): Boolean {
        val consecutive = lastWindowBad
        lastWindowBad = true
        return consecutive
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
        if (current != null) {
            runCatching { activity.window.removeOnFrameMetricsAvailableListener(current) }
        }
        totalFrames = 0L
        jankFrames = 0L
    }
}
