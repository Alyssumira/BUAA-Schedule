package com.buaa.schedule

import android.app.Application
import android.content.ComponentCallbacks2
import com.buaa.schedule.core.designsystem.Personalization
import com.buaa.schedule.data.local.AppDatabase
import com.buaa.schedule.data.repository.ScheduleRepository
import com.buaa.schedule.widget.BackgroundSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BUAAApplication : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val repository: ScheduleRepository by lazy { ScheduleRepository(database) }

    /**
     * 进程级作用域：给"活不过调用方"的后台收尾用（例如实况服务 stopSelf 前
     * 排下的下一节课窗口）。服务/广播Receiver 不要自建常驻作用域。
     */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        com.buaa.schedule.data.import.BuaaWebSession.init(this)
        // 智学北航只寄存 Context：读盘是懒的，冷启动不为此多解一次密文
        com.buaa.schedule.data.import.SpocSession.init(this)
        Personalization.load(this)
        // 通知渠道要在任何通知发出前就位（渠道重要性一旦创建只能由用户改）
        com.buaa.schedule.reminder.ReminderNotifications.ensureChannels(this)
        // 应用启动/升级（升级会清掉已注册闹钟）：重排提醒并调度零点刷新。
        // WorkManager 第一次 getInstance 会初始化它自己的数据库，属于磁盘活，
        // 因此连同"旧版周期轮询退出"和兜底任务注册一起放这里，不占 onCreate 主线程。
        applicationScope.launch {
            // 这个作用域是 SupervisorJob 且没有 CoroutineExceptionHandler：块内任何
            // 未捕获异常都会落到线程的默认处理器，直接杀进程。
            // ⚠️ 逐步兜住而不是整块一个 runCatching：探测桌面组件要跨 binder 问 Launcher
            // （MIUI 上抛 DeadObjectException），整块兜时它一抛，后面的明日预告与
            // WidgetFallbackWorker 整轮不注册 —— 而 HyperOS 清掉第三方精确闹钟的机型，
            // 恰恰全靠那条兜底刷新活着。每步自己吞异常 + 留痕（同 WidgetCommon 的口径；
            // 组件那四步的逐步兜住已经挪进 BackgroundSync.runColdStartWidgetSteps 里面）。
            suspend fun step(label: String, block: suspend () -> Unit) {
                runCatching { block() }.onFailure {
                    android.util.Log.w("BUAAApplication", "后台链路初始化失败：$label", it)
                }
            }
            // 旧版本的固定周期 Widget 轮询退出，改为事件驱动 + 每日零点刷新
            step("cancelLegacyPeriodicWork") {
                BackgroundSync.cancelLegacyPeriodicWork(this@BUAAApplication)
            }
            // 冷启动自愈：勿扰记录已过恢复期限还没等到下课铃（含旧版本无期限的残留）时恢复；
            // 正在上课的那节课期限未到，不会被误恢复
            step("dndSelfCheck") {
                com.buaa.schedule.reminder.ClassProgressDnd.selfCheck(this@BUAAApplication)
            }
            step("rescheduleReminders") {
                BackgroundSync.rescheduleRemindersAndBells(this@BUAAApplication)
            }
            // 刷组件 + 零点闹钟 + 明日预告 + 兜底任务登记：这四步原先各自探测一次
            // "桌面上有没有组件"（最多 6 趟 getAppWidgetIds × 3 次），改由被调方问一遍、
            // 结论传给三个下游共用（审计 §2.1）。步骤顺序就是原来的顺序。
            step("coldStartWidgetSteps") {
                BackgroundSync.runColdStartWidgetSteps(this@BUAAApplication)
            }
        }
    }

    /**
     * 内存回收信号：交出最大的两块常驻内存。
     *
     * 隐藏会话 WebView 挂在 Chromium 堆上（几十 MB 量级），是全应用最大的单个常驻块；
     * Cookie 已经由 [com.buaa.schedule.data.import.BuaaCookieStore] 落盘，
     * 销毁后下次回前台按 Cookie 重建即可。
     *
     * 只处理 BACKGROUND / MODERATE / COMPLETE 这三档 —— 它们只会发给**已在后台**的进程。
     * RUNNING_CRITICAL 虽然名字更吓人，却是发给前台进程的，而前台正是用户可能正在
     * 抓课表的时候（[com.buaa.schedule.data.import.BuaaWebSession] 的取数就走这个
     * WebView），那时拆掉等于把这次导入打断。UI_HIDDEN 同样不动：
     * 切出去又立刻回来时，重跑一遍 SSO 页面比省下的那点内存更贵。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) return
        com.buaa.schedule.data.import.BuaaWebSession.releaseForMemory()
        com.buaa.schedule.widget.WidgetDataCache.invalidate()
    }
}
