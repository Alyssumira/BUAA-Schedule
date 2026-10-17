package com.buaa.schedule.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.buaa.schedule.reminder.ClassProgressScheduler
import java.util.concurrent.TimeUnit

private const val TAG = "WidgetFallbackWorker"

/**
 * 兜底链失败之后允许连续重试的趟数上限（#675，T400②）。
 *
 * 为什么要有上限而不是一直 retry：这一轮失败的常见形状是库读不动（`SQLiteFullException`、
 * cursor window），那是"再跑一遍也不会好"的那一类。周期任务本来 12 小时就来一趟，
 * 敲到第 3 次还在抛就按原样成功返回、把下一轮交回周期 —— 补漏买的是"多试几次"，不是"每一轮都试到坏"。
 */
private const val MAX_FALLBACK_RETRY_ATTEMPTS = 3

/**
 * 纯判据（#675）：**这一轮兜底链要不要交回 WorkManager 重试**。
 *
 * 收成顶层函数（而不是写在 `doWork` 里、也不是 companion 的方法）有两个理由，都不许被"顺手"合并掉：
 * - 顶层函数编译进 `WidgetFallbackWorkerKt`，JVM 单测调它**不会**去初始化 `WidgetFallbackWorker`
 *   （那枚类继承 `CoroutineWorker`，一碰就把 android 桩拉进来 —— 本模块没有 Robolectric）；
 * - 判据本体一个 `android.*` 都不读：两枚入参全由调用方当场量好交进来（本仓成文边界）。
 *
 * 口径：改前这里没有判据 —— `doWork` 结尾一律 `Result.success()`，
 * 于是"提醒那一步抛了"（`rescheduleReminders` 被 `getOrDefault(true)` 折成成功）与"课堂铃没人续排"
 * 一起被 WorkManager 读成"这趟干完了"，一条链对某些用户是唯一兜底、却不给第二次机会。
 * 现在抛过就 retry，但**到顶就停**（见 [MAX_FALLBACK_RETRY_ATTEMPTS] 那段的取舍）。
 *
 * @param failedStepCount 这一轮经报告口数出来的失败步数（0 = 干净跑完）
 * @param runAttemptCount WorkManager 交代的本次尝试序号（第一次是 0）
 */
internal fun shouldRetryFallbackChain(failedStepCount: Int, runAttemptCount: Int): Boolean =
    failedStepCount > 0 && runAttemptCount < MAX_FALLBACK_RETRY_ATTEMPTS

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
 *
 * ## 在册 #675：这一轮抛了，从此交回 WorkManager 重试（T400②）
 *
 * 改前的结尾是无条件 `Result.success()`，而两处调用都没有报告口
 * （[BackgroundSync.onDataChanged] 当时没有 `onStepFailed` 那一枚形参）⇒ 这条链对某些用户是
 * **唯一兜底**，却连一次重试都拿不到：抛了 = 这一轮课堂铃没人续排 = 还报告"我干完了"。
 * 现在 `doWork` 带一枚 [reportFailedStep] 形状的记账口下去（`onDataChanged` 两步都吃它），
 * 抛过几步、叫什么名字，当场一句 `Log.w` 点名，再由 [shouldRetryFallbackChain] 决定 retry 还是 success。
 *
 * ⚠️ 三件没做的事，别当顺手活：
 * - **没有**改 [BackgroundSync.rescheduleReminders] 那枚 `true` 的含义（读库失败/抛了也返回 true 是
 *   [ColdStartRebuild] 钥匙 2 的成文口径，方向是"宁可多跑一轮"）；改的只是这条链**读它的方式**。
 * - **没有**在这里无条件补排课堂铃：提醒那一步抛了的时候本轮确实仍可能没人排铃，
 *   但无条件补排正是 R5 F-12 禁的形状（把刚排上的上课铃撤了再排）—— 补齐交给 retry 那一轮整条链重跑。
 * - **没有**把 [ensure] 挂到 `doWork` 上（理由见 `doWork` 里那条 ⚠️ 注释与 R5 F-16）。
 */
class WidgetFallbackWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // 探测要跨 binder 问 Launcher，个别 ROM 上会抛；抛了按"有组件"处理 ——
        // 多刷一次组件，远好于把组件留在昨天（这条链存在的意义就是补漏）。
        val hasWidget = BackgroundSync.hasAnyWidgetSafely(applicationContext)
        // #675：这一趟链"抛了哪几步"从此留在册（T400②）。改前这两处调用都不传报告口 ——
        // `onDataChanged` 的形参表里当时根本没有那一枚 —— 于是 `rescheduleReminders` 抛那一档
        // 只剩 BackgroundSync 里一句 Log.w：这里既看不出课堂铃其实没人排（于是跳过续排），
        // 也照样在结尾返回成功（于是 WorkManager 连一次重试都不给）。
        // 现在抛过就计数，计数决定下面的重试；那枚 `true` 的含义一个字没动（它是 ColdStartRebuild 钥匙 2 的输入）。
        val failedSteps = mutableListOf<String>()
        val reportFailedStep: (label: String, error: Throwable) -> Unit = { label, error ->
            failedSteps += label
            android.util.Log.w(TAG, "兜底链这一步抛了：$label", error)
        }
        val bellsHandled = if (hasWidget) {
            // 结论带下去：onDataChanged → refreshWidgets 里那次探测问的是同一个问题，
            // 中间没有任何会改变组件数的写入（审计 §2.1 的同一笔账，这里是这条链的第二次）
            BackgroundSync.onDataChanged(applicationContext, hasAnyWidget = hasWidget, onStepFailed = reportFailedStep)
        } else {
            // 没有组件不等于没有提醒：用户可能只开了通知提醒而没放桌面组件。
            // 此时仍然要重排提醒，否则这条兜底链路对这类用户完全失效。
            BackgroundSync.rescheduleReminders(applicationContext, reportFailedStep)
        }
        // 只有提醒那条链没接手课堂铃时才补排：日历模式，或"没有下一条提醒"
        // （rescheduleAll 那条分支会把上/下课铃一起收掉，R5 F-12）。
        // 原来这里无条件再排一遍，等于同一轮多查三张表、把刚排上的上课铃撤了再排。
        // ⚠️ 不要再调 ensure()：它会因为"无组件"把自己注销，
        // 最需要兜底的这类用户第一次跑完就永久失去兜底（R5 F-16）。
        // ⚠️ 也在这里无条件补排一次（那是 R5 F-12 明令禁的形状：把刚排上的上课铃撤了再排）：
        // 提醒那一步抛了的时候，本轮确实仍可能没人排课堂铃 —— 补齐交给下面的 retry，那一轮整条链重跑。
        if (!bellsHandled) {
            ClassProgressScheduler.rescheduleNextWindow(applicationContext)
        }
        if (shouldRetryFallbackChain(failedSteps.size, runAttemptCount)) {
            android.util.Log.w(TAG, "兜底链有 ${failedSteps.size} 步抛了：${failedSteps.joinToString(" ／ ")}，交回 WorkManager 重试")
            return Result.retry()
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
         *
         * ⚠️ 重评估点名册（谁有资格调本函数）：**上面判据的两半各有一个写入点，两半都必须挂够**。
         * `hasAnyWidget` 那一半由组件的增删与冷启动负责 —— [BackgroundSync.runColdStartWidgetSteps]、
         * `WidgetCommon.registerBackgroundSyncSteps`、[BackgroundSync.cancelWidgetMidnightIfNoWidgets]；
         * [BackgroundSync.usesInAppReminders] 那一半只由**「提醒方式」那一枚分段控件**改写
         * （`ui/settings/SettingsScreen` 先 `putString(ReminderMode.PREF_KEY, mode)`，
         * 再 `ScheduleViewModel.onReminderModeChanged()`），所以那条链也必须进来调一次（在册 #679）。
         * 少任何一枚都不是崩溃，而是"某一档用户要等下一次冷启动才对得回来"——
         * 点名册整份钉在 `widget/CalendarModeClassBellCleanupTest` 的
         * `fallbackWorkerReEvaluationRosterIsTheKnownFour` 那一格，别靠记忆维护它。
         * 反过来也别顺手把本函数挂到 [BackgroundSync.onDataChanged] 那条数据变化公共链上：
         * 那里有十几枚调用点，而本函数与 [BackgroundSync.refreshWidgets] 各带一份 `hasAnyWidgetSafely`
         * 默认参数 ⇒ 同一趟链里会把"有没有组件"跨 binder 问 Launcher 两遍（#536 F2 那族已裁过）。
         */
        fun ensure(context: Context, hasAnyWidget: Boolean = BackgroundSync.hasAnyWidgetSafely(context)) {
            // 两份调用方（onEnabled 与应用启动块）都指望这里不抛：
            // 探测组件要跨 binder 问 Launcher（默认参数里的 hasAnyWidgetSafely 自己已经把异常吞成"有组件"）。
            // 注册失败的代价只是"这一轮没有兜底"，不值得用崩溃换。
            //
            // ⚠️ 必须是**接 Context 的那个重载**（T18 起 WorkManager 是按需初始化的）：
            // `getInstance(context)` 未初始化时会经由 BUAAApplication 的
            // Configuration.Provider 就地把它建起来；而无参的 `WorkManager.getInstance()`
            // 在未初始化的进程里直接抛 IllegalStateException。两者在这里长得几乎一样，
            // 换错的那一瞬间外面还套着 runCatching —— 症状不是崩溃，而是
            // **兜底任务从此再也注册不上**（HyperOS 那批吞闹钟的机型提醒跟着一起哑），
            // 是这一族最难发现的失败形状。同理，谁把 BUAAApplication 那个
            // Configuration.Provider 摘掉而不清单里的 initializer 一起改回来，
            // 下面这一整块会静默变成空操作。
            //
            // 还有一条线程上的约束：按需初始化之后"第一个调到这里的人"就是付建库钱的人，
            // 所以广播回调那条路必须先挪出主线程（见 WidgetCommon.bootstrapFromReceiver）。
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
