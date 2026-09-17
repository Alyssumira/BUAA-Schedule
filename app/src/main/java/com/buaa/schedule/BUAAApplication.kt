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
        Personalization.load(this)
        // 通知渠道要在任何通知发出前就位（渠道重要性一旦创建只能由用户改）
        com.buaa.schedule.reminder.ReminderNotifications.ensureChannels(this)
        // 应用启动/升级（升级会清掉已注册闹钟）：重排提醒并调度零点刷新。
        // WorkManager 第一次 getInstance 会初始化它自己的数据库，属于磁盘活，
        // 因此连同"旧版周期轮询退出"和兜底任务注册一起放这里，不占 onCreate 主线程。
        applicationScope.launch {
            // 这个作用域是 SupervisorJob 且没有 CoroutineExceptionHandler：块内任何
            // 未捕获异常都会落到线程的默认处理器，直接杀进程 —— 表现为"一打开就闪退"，
            // 而自建更新通道对这类失败无效。WorkManager 首次 getInstance 要建自己的库、
            // hasAnyWidget 要跨 binder 问 Launcher（后者在 MIUI 上会抛 DeadObjectException），
            // 两条都不是"不可能发生"，所以整块兜住只留痕。
            runCatching {
                // 旧版本的固定周期 Widget 轮询退出，改为事件驱动 + 每日零点刷新
                BackgroundSync.cancelLegacyPeriodicWork(this@BUAAApplication)
                BackgroundSync.rescheduleReminders(this@BUAAApplication)
                BackgroundSync.refreshWidgets(this@BUAAApplication)
                BackgroundSync.scheduleWidgetMidnight(this@BUAAApplication)
                BackgroundSync.scheduleTomorrowPreview(this@BUAAApplication)
                // Widget 兜底刷新：对抗澎湃 HyperOS 等系统清理第三方精确闹钟（无 Widget 时自动不干活）
                com.buaa.schedule.widget.WidgetFallbackWorker.ensure(this@BUAAApplication)
            }.onFailure {
                android.util.Log.w("BUAAApplication", "后台链路初始化失败，本次启动不再有提醒/组件刷新兜底", it)
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
