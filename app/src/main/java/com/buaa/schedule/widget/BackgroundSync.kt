package com.buaa.schedule.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.WorkManager
import com.buaa.schedule.data.repository.scheduleRepository
import com.buaa.schedule.domain.model.ReminderMode
import com.buaa.schedule.domain.model.startLocalDate
import com.buaa.schedule.reminder.ReminderScheduler
import com.buaa.schedule.reminder.TomorrowPreviewReceiver
import com.buaa.schedule.reminder.TomorrowPreviewScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 后台任务统一入口：提醒重排、Widget 刷新与零点刷新调度。
 *
 * 设计原则（事件驱动，无固定轮询）：
 * - 提醒：长期最多一个 AlarmManager 闹钟（下一条触发时间）；
 * - Widget：数据/设置变化即时刷新，每日零点刷一次“今天/明天”，
 *   无 Widget 实例时取消全部后台任务；
 * - 只有事件（数据变化、开机、时间/时区变化、应用升级、闹钟权限变化）才重算。
 */
object BackgroundSync {

    private const val TAG = "BackgroundSync"
    private const val MIDNIGHT_REQUEST_CODE = 10_001
    private const val LEGACY_PERIODIC_WORK = "widget_refresh"

    /**
     * 重排下一条课程提醒（按最新数据库状态）。
     *
     * @return 本轮 [ReminderScheduler.rescheduleAll] 是否已经把「上/下课铃」一起接手。
     *   返回 false 时课堂窗口没人排，需要兜底链自己续排一次：
     *   ①「系统日历提醒」模式（应用内一个闹钟都不排）；
     *   ② 没有下一条提醒（课表清空 / 学期已结束 / 提醒全关）—— 那条分支里
     *     rescheduleAll 会把课堂铃一起 cancelAll（否则会留下永不消失的常驻通知 + 永久勿扰），
     *     而 R5 F-12 要的「课程进行中 / 上课自动勿扰」还得独立续排回来。
     *   读库失败也返回 true：那种情况下调用方不该再补一遍同样的查询。
     */
    suspend fun rescheduleReminders(context: Context): Boolean {
        if (!usesInAppReminders(context)) {
            // 日历模式：应用内闹钟与上下课铃一起清干净（口径同
            // ScheduleViewModel.afterDataChangedInternal），只清课前提醒会留下
            // 永不消失的常驻通知与永久勿扰。
            runCatching {
                ReminderScheduler.cancelAll(context)
                com.buaa.schedule.reminder.ClassProgressScheduler.cancelAll(context)
            }.onFailure { Log.w(TAG, "日历模式下清理应用内闹钟失败", it) }
            return false
        }
        return runCatching {
            val repository = context.scheduleRepository()
            val semester = repository.getCurrentSemester()
            val courses = repository.getDisplayCourses(semester)
            val timeSlots = repository.getTimeSlots()
            val reminders = repository.getReminders().associateBy { it.courseId }
            ReminderScheduler.rescheduleAll(context, courses, semester, timeSlots, reminders)
            // 与上面 rescheduleAll 用的是同一批已读出的数据，不再查库
            val semesterStart = semester?.startLocalDate
            val willRemind = semesterStart != null && courses.isNotEmpty() &&
                ReminderScheduler.planNextReminder(
                    courses = courses,
                    semesterStart = semesterStart,
                    timeSlots = timeSlots,
                    reminders = reminders,
                    now = LocalDateTime.now(),
                    nowMillis = System.currentTimeMillis(),
                ) != null
            willRemind
        }.onFailure { Log.w(TAG, "重排提醒失败", it) }.getOrDefault(true)
    }

    /**
     * 刷新全部桌面组件。
     *
     * ⚠️ 必须先让组件数据源失效并重写快照。组件与 RemoteViewsFactory 读的都是
     * [WidgetDataCache] → [WidgetDataSynchronizer] 快照，而快照此前只在
     * [onDataChanged] 里写过。改课表走的是 `ScheduleViewModel.afterDataChangedInternal()`，
     * 那里只调本方法：缓存与快照都不会失效，组件会一直渲染上一次 sync 时的旧课表
     * （最长要等到 12 小时的兜底 Worker），重启应用也不自愈。
     */
    suspend fun refreshWidgets(context: Context) {
        runCatching {
            WidgetDataCache.invalidate()
            // 一个组件都没放时，下面的全量快照 sync（逐个学期查库 + JSON + upsert）与
            // 5 次 getAppWidgetIds 都没有读者 —— 开机/改时间/12 小时兜底每次都白跑一遍。
            if (!hasAnyWidgetSafely(context)) return
            WidgetDataSynchronizer.sync(context)
            TodayWidgetProvider.updateAll(context)
            TomorrowWidgetProvider.updateAll(context)
            WeekWidgetProvider.updateAll(context)
            WeekGridWidgetProvider.updateAll(context)
            NextClassWidgetProvider.updateAll(context)
        }.onFailure { Log.w(TAG, "刷新 Widget 失败", it) }
    }

    /** 数据/提醒/学期/外观变化后的统一收尾：重排提醒 + 刷新 Widget（返回值见 [rescheduleReminders]） */
    suspend fun onDataChanged(context: Context): Boolean {
        val bellsHandled = rescheduleReminders(context)
        refreshWidgets(context)
        return bellsHandled
    }

    /**
     * 调度下一次零点刷新（“今天/明天”Widget 的日期滚动）。
     * 没有 Widget 实例时不注册任何闹钟。
     */
    fun scheduleWidgetMidnight(context: Context) {
        if (!hasAnyWidget(context)) {
            cancelWidgetMidnight(context)
            return
        }
        val nextMidnight = LocalDate.now().plusDays(1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        // getSystemService(Class) 在个别定制 ROM / 低内存实例上会返回 null，
        // 直接点调用会 NPE 崩在广播里
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        // setAndAllowWhileIdle 在极端情况下仍可能抛 SecurityException
        // （闹钟权限被运行期撤销），不能让它带着崩溃逃出广播回调
        runCatching {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                nextMidnight,
                midnightPendingIntent(context),
            )
        }.onFailure { Log.w(TAG, "调度零点刷新失败", it) }
    }

    /**
     * 调度明日课程预告（每天 22:00）。用户关闭开关时取消闹钟。
     */
    fun scheduleTomorrowPreview(context: Context) {
        val enabled = context
            .getSharedPreferences("schedule_settings", Context.MODE_PRIVATE)
            .getBoolean(TomorrowPreviewReceiver.PREF_ENABLED, true)
        if (enabled) {
            TomorrowPreviewScheduler.schedule(context)
        } else {
            TomorrowPreviewScheduler.cancel(context)
        }
    }

    fun cancelWidgetMidnight(context: Context) {
        context.getSystemService(AlarmManager::class.java)
            ?.cancel(midnightPendingIntent(context))
    }

    /** 所有 Widget 都被移除时调用：取消零点闹钟与旧版周期任务，并让兜底轮询重新判定自己是否还需要 */
    fun cancelWidgetMidnightIfNoWidgets(context: Context) {
        if (!hasAnyWidget(context)) {
            cancelWidgetMidnight(context)
            cancelLegacyPeriodicWork(context)
            // 兜底轮询交回 ensure() 判定：组件没了但提醒还走应用内闹钟时要留着
            WidgetFallbackWorker.ensure(context)
        }
    }

    /** 旧版本的固定周期刷新任务在升级后应退出 */
    fun cancelLegacyPeriodicWork(context: Context) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(LEGACY_PERIODIC_WORK) }
    }

    fun hasAnyWidget(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return listOf(
            TodayWidgetProvider::class.java,
            TomorrowWidgetProvider::class.java,
            WeekWidgetProvider::class.java,
            WeekGridWidgetProvider::class.java,
            NextClassWidgetProvider::class.java,
        ).any { clazz -> manager.getAppWidgetIds(ComponentName(context, clazz)).isNotEmpty() }
    }

    /**
     * [hasAnyWidget] 的不可抛版本：探测要跨 binder 问 Launcher，个别 ROM 上会抛
     * （DeadObjectException）。失败时按"有组件"处理 —— 宁可多刷一次，
     * 也不能因为探测失败就把组件留在昨天。
     */
    fun hasAnyWidgetSafely(context: Context): Boolean =
        runCatching { hasAnyWidget(context) }
            .onFailure { Log.w(TAG, "探测桌面组件失败，按有组件处理", it) }
            .getOrDefault(true)

    /**
     * 提醒是否还走应用内闹钟。
     *
     * 切到「系统日历提醒」后应用内不再注册闹钟，兜底轮询对这类用户没有意义。
     */
    fun usesInAppReminders(context: Context): Boolean =
        context
            .getSharedPreferences("schedule_settings", Context.MODE_PRIVATE)
            .getString(ReminderMode.PREF_KEY, ReminderMode.APP) != ReminderMode.CALENDAR

    private fun midnightPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WidgetRefreshReceiver::class.java).apply {
            action = WidgetRefreshReceiver.ACTION_MIDNIGHT_REFRESH
        }
        return PendingIntent.getBroadcast(
            context,
            MIDNIGHT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
