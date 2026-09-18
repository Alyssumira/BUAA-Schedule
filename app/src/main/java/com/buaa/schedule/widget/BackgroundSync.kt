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
import com.buaa.schedule.reminder.ClassProgressScheduler
import com.buaa.schedule.reminder.ReminderScheduler
import com.buaa.schedule.reminder.TomorrowPreviewReceiver
import com.buaa.schedule.reminder.TomorrowPreviewScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
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
                ClassProgressScheduler.cancelAll(context)
            }.onFailure { Log.w(TAG, "日历模式下清理应用内闹钟失败", it) }
            return false
        }
        return runCatching {
            val repository = context.scheduleRepository()
            val semester = repository.getCurrentSemester()
            val courses = repository.getDisplayCourses(semester)
            val timeSlots = repository.getTimeSlots()
            val reminders = repository.getReminders().associateBy { it.courseId }
            // rescheduleAll 的返回值就是本轮那一次全量搜索的结论：
            // 再搜一遍既白跑上千次窗口构造，又要多读一次时钟（同源约束见 ReminderScheduler）
            ReminderScheduler.rescheduleAll(context, courses, semester, timeSlots, reminders) != null
        }.onFailure { Log.w(TAG, "重排提醒失败", it) }.getOrDefault(true)
    }

    /**
     * 「重排提醒」+「课堂铃兜底」的完整一次调用：[rescheduleReminders] 的返回值契约
     * 由这里就地兑现，调用方不必（也没法）再各自记得补那一步。
     *
     * 为什么要有这个包装：`false` 的含义是"本轮课堂铃没人排"（① 系统日历提醒模式；
     * ② 没有下一条提醒，rescheduleAll 在那条分支里连上/下课铃一起收掉了），
     * 而 [rescheduleReminders] 是个返回 Boolean 的 suspend 函数，
     * 广播接收器 / ViewModel / Application 那几处调用点全都在 `launch { }` 里直接丢弃了返回值 ——
     * 丢弃的后果不是少一行日志，而是这两类用户此后再也不会有「课程进行中」实况与
     * 上课自动勿扰（R5 F-12 的兜底链断在调用点）。把补排收进同一个函数，
     * 新调用点忘接返回值也不会再出错。
     */
    suspend fun rescheduleRemindersAndBells(context: Context) {
        if (!rescheduleReminders(context)) {
            // 与 WidgetFallbackWorker 同一口径：只在提醒那条链没接手课堂铃时才补排，
            // 无条件再排一遍等于把刚排上的上课铃撤了重排。
            ClassProgressScheduler.rescheduleNextWindow(context)
        }
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
            // 6 次 getAppWidgetIds 都没有读者 —— 开机/改时间/12 小时兜底每次都白跑一遍。
            if (!hasAnyWidgetSafely(context)) return
            WidgetDataSynchronizer.sync(context)
            TodayWidgetProvider.updateAll(context)
            TomorrowWidgetProvider.updateAll(context)
            WeekWidgetProvider.updateAll(context)
            WeekGridWidgetProvider.updateAll(context)
            NextClassWidgetProvider.updateAll(context)
            TwoDayWidgetProvider.updateAll(context)
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
        // 安全版：探测跨 binder 问 Launcher，MIUI 上会抛 DeadObjectException。
        // 探测失败按"有组件"处理 = 多注册一次幂等闹钟，无害。
        if (!hasAnyWidgetSafely(context)) {
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
     *
     * 事件入口也要看课表：此前这里只读一个 prefs 开关就调 `schedule(context)`，
     * 而那个重载写死"明天"。寒暑假与周末里，每次改课表（ScheduleViewModel）、每次开机、
     * 每次改时间都会重新武装一次**必然空转**的 22:00 精确闹钟 —— 醒来查四张表、
     * 再跑一遍 121 天 × 全课程的搜索，然后链条停下。判定与广播续排共用
     * [TomorrowPreviewReceiver.nextScheduledPreviewDay]（含 22:00 已过时的顺延复核），
     * 它给 null 就是"往后没有可推的内容"，这一次什么都不排：链条停下，
     * 等下一次事件重新对齐 —— 与广播里那条续排同一个取舍，没有闹钟在空转等着醒。
     *
     * 读课表要在 IO 上，而这个入口有一个非挂起的调用点（设置页那枚开关的回调），
     * 因此内部起一个 IO 协程；关掉开关那条路不查库，当场撤销。
     * 协程晚几步不破坏正确性：排出去的时刻永远按执行那一刻重算（22:00 过了就顺延），
     * 最坏是这一次没排上，等下一次事件。
     */
    fun scheduleTomorrowPreview(context: Context) {
        val enabled = context
            .getSharedPreferences("schedule_settings", Context.MODE_PRIVATE)
            .getBoolean(TomorrowPreviewReceiver.PREF_ENABLED, true)
        if (!enabled) {
            TomorrowPreviewScheduler.cancel(context)
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val repository = context.scheduleRepository()
                val semester = repository.getCurrentSemester()
                val fireDay = TomorrowPreviewReceiver.nextScheduledPreviewDay(
                    courses = repository.getDisplayCourses(semester),
                    semester = semester,
                    today = LocalDate.now(),
                )
                if (fireDay == null) {
                    Log.d(TAG, "往后没有可推的明日预告，事件入口不排闹钟")
                } else {
                    TomorrowPreviewScheduler.schedule(context, fireDay)
                }
            }.onFailure { Log.w(TAG, "调度明日预告失败", it) }
        }
    }

    fun cancelWidgetMidnight(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        // 取消这一头只查不造，理由见 ReminderScheduler.cancelAll 的注释；
        // 查不到就是没排过，本来就是 no-op
        existingMidnightPendingIntent(context)?.let { alarmManager.cancel(it) }
    }

    /** 所有 Widget 都被移除时调用：取消零点闹钟与旧版周期任务，并让兜底轮询重新判定自己是否还需要 */
    fun cancelWidgetMidnightIfNoWidgets(context: Context) {
        // 六个 Provider 的 onDisabled 都直连这里，跑在广播主线程上：
        // 可抛版 hasAnyWidget 会让"拖掉最后一个组件"变成一次进程崩溃。
        // 探测失败按"有组件"处理 = 不取消零点闹钟，零点白刷一次，无害。
        if (!hasAnyWidgetSafely(context)) {
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
            TwoDayWidgetProvider::class.java,
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

    /** 排与取消共用的只是那个 Intent；flag 各用各的，见下面两个 builder */
    private fun midnightBaseIntent(context: Context): Intent =
        Intent(context, WidgetRefreshReceiver::class.java).apply {
            action = WidgetRefreshReceiver.ACTION_MIDNIGHT_REFRESH
        }

    /** 排闹钟这一头必须是 FLAG_UPDATE_CURRENT：还没有 PI 时它负责把那个 PI 造出来 */
    private fun midnightPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            MIDNIGHT_REQUEST_CODE,
            midnightBaseIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 取消那一头只查不造（判等靠 Intent.filterEquals，不含 extras，因此不需要拼 extras） */
    private fun existingMidnightPendingIntent(context: Context): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            MIDNIGHT_REQUEST_CODE,
            midnightBaseIntent(context),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
}
