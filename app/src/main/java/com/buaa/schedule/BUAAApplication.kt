package com.buaa.schedule

import android.app.Application
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

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
            // 旧版本的固定周期 Widget 轮询退出，改为事件驱动 + 每日零点刷新
            BackgroundSync.cancelLegacyPeriodicWork(this@BUAAApplication)
            BackgroundSync.rescheduleReminders(this@BUAAApplication)
            BackgroundSync.refreshWidgets(this@BUAAApplication)
            BackgroundSync.scheduleWidgetMidnight(this@BUAAApplication)
            BackgroundSync.scheduleTomorrowPreview(this@BUAAApplication)
            // Widget 兜底刷新：对抗澎湃 HyperOS 等系统清理第三方精确闹钟（无 Widget 时自动不干活）
            com.buaa.schedule.widget.WidgetFallbackWorker.ensure(this@BUAAApplication)
        }
    }
}
