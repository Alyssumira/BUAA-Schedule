package com.buaa.schedule.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.buaa.schedule.reminder.ClassProgressScheduler
import java.util.concurrent.TimeUnit

/**
 * Widget 兜底刷新（WorkManager 周期任务）。
 *
 * 我们的组件是事件驱动刷新（数据变化 + 每日零点闹钟），正常情况不需要轮询；
 * 但部分国产 ROM（澎湃 HyperOS 等）会清理第三方应用的精确闹钟，导致
 * 零点闹钟不触发、组件停留在昨天。这里用 12 小时一次的兜底轮询对抗清理。
 *
 * Worker 内部会自行判断有无 Widget 实例：有就「重排提醒 + 刷新组件」，
 * 没有也仍然重排提醒并续排课堂窗口（对只开提醒、不放组件的用户，这就是唯一兜底）。
 *
 * ⚠️ 它同样是**提醒链路的兜底**：提醒的常规调度依赖
 * 数据变化 / 开机 / 时间变化 / 应用升级这几个事件，
 * 而 HyperOS 这类 ROM 会清掉 BOOT_COMPLETED 广播与已注册闹钟，
 * 结果就是"组件还活着、提醒却全哑了"。所以这里除了刷组件，
 * 也顺手把提醒重排一遍（[BackgroundSync.onDataChanged] 已经包含这步，
 * 但无 Widget 时此前会直接 return，把提醒一并放弃了）。
 */
class WidgetFallbackWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // 探测要跨 binder 问 Launcher，个别 ROM 上会抛；抛了按"有组件"处理 ——
        // 多刷一次组件，远好于把组件留在昨天（这条链存在的意义就是补漏）。
        val hasWidget = BackgroundSync.hasAnyWidgetSafely(applicationContext)
        val bellsHandled = if (hasWidget) {
            // 结论带下去：onDataChanged → refreshWidgets 里那次探测问的是同一个问题，
            // 中间没有任何会改变组件数的写入（审计 §2.1 的同一笔账，这里是这条链的第二次）
            BackgroundSync.onDataChanged(applicationContext, hasAnyWidget = hasWidget)
        } else {
            // 没有组件不等于没有提醒：用户可能只开了通知提醒而没放桌面组件。
            // 此时仍然要重排提醒，否则这条兜底链路对这类用户完全失效。
            BackgroundSync.rescheduleReminders(applicationContext)
        }
        // 只有提醒那条链没接手课堂铃时才补排：日历模式，或"没有下一条提醒"
        // （rescheduleAll 那条分支会把上/下课铃一起收掉，R5 F-12）。
        // 原来这里无条件再排一遍，等于同一轮多查三张表、把刚排上的上课铃撤了再排。
        // ⚠️ 不要再调 ensure()：它会因为"无组件"把自己注销，
        // 最需要兜底的这类用户第一次跑完就永久失去兜底（R5 F-16）。
        if (!bellsHandled) {
            ClassProgressScheduler.rescheduleNextWindow(applicationContext)
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "widget_fallback_refresh"

        /**
         * 登记兜底任务：有 Widget **或** 提醒还走应用内闹钟。
         *
         * 只按"有 Widget"判断会把最需要兜底的用户排除在外：
         * 闹钟被 HyperOS 清掉、又没放桌面组件的人，提醒从此永久哑掉，
         * 而这正是 WorkManager 这条链要对抗的场景。
         *
         * [hasAnyWidget] 的默认值就是本函数此前的行为（自己探测），所以 `onEnabled` 与
         * `onDisabled` 那两条调用点一个字都不用改；只有冷启动那条链会把已经问到的结论传进来
         * （审计 §2.1：那次唤醒里这个问题原本被问三遍）。
         * 一个口径变化：探测抛异常此前会让整块 `runCatching` 提前退出，也就是"这一轮既不注册也不注销"；
         * 现在按"有组件"处理 = 走注册那一头。注册是幂等的（KEEP），漏注册才是这条链真正的代价。
         */
        fun ensure(context: Context, hasAnyWidget: Boolean = BackgroundSync.hasAnyWidgetSafely(context)) {
            // 两份调用方（onEnabled 与应用启动块）都指望这里不抛：
            // getInstance 在 WorkManager 尚未初始化的进程里会抛 IllegalStateException，
            // 探测组件要跨 binder 问 Launcher（默认参数里的 hasAnyWidgetSafely 自己已经把异常吞成"有组件"）。
            // 注册失败的代价只是"这一轮没有兜底"，不值得用崩溃换。
            runCatching {
                val manager = WorkManager.getInstance(context)
                if (hasAnyWidget || BackgroundSync.usesInAppReminders(context)) {
                    manager.enqueueUniquePeriodicWork(
                        WORK_NAME,
                        ExistingPeriodicWorkPolicy.KEEP,
                        PeriodicWorkRequestBuilder<WidgetFallbackWorker>(12, TimeUnit.HOURS)
                            .build(),
                    )
                } else {
                    manager.cancelUniqueWork(WORK_NAME)
                }
            }.onFailure { android.util.Log.w("WidgetFallbackWorker", "兜底任务注册失败", it) }
        }
    }
}
