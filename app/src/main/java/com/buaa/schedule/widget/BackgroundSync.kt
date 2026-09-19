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
import com.buaa.schedule.reminder.ClassProgressReceiver
import com.buaa.schedule.reminder.ClassProgressScheduler
import com.buaa.schedule.reminder.ReminderScheduler
import com.buaa.schedule.reminder.TomorrowPreviewReceiver
import com.buaa.schedule.reminder.TomorrowPreviewScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

private const val LOG_TAG = "BackgroundSync"

/**
 * "哪一步抛了"的默认留痕口：什么都不做。
 *
 * 存在只为服务冷启动判据（[ColdStartRebuild]）：它要区分"整链干净地跑完"与
 * "里面某一步抛了"，因为前者才配写"上次成功"的时间戳。
 * 各步骤自己那句 `Log.w` 原地保留（审计 §4.4 的取证过滤条件不许变），所以默认值是无操作。
 * 写成顶层属性而不是签名里的 lambda：本仓库的源码形状守卫按"函数体第一个 `{`"切函数体，
 * 签名里出现 `{ _, _ -> }` 它会切到默认值上去。
 */
internal val NO_STEP_FAILURE: (label: String, error: Throwable) -> Unit = { _, _ -> }

/**
 * 冷启动组件那四步的留痕口：与它们改动前各自那句 `Log.w` 一字不差
 * （四步收进 [BackgroundSync.runColdStartWidgetSteps] 时就是这样，日志文案是
 * 老过滤条件继续能用的前提）。同样收成顶层属性，理由同 [NO_STEP_FAILURE]。
 */
internal val LOG_COLD_START_STEP_FAILURE: (label: String, error: Throwable) -> Unit = { label, error ->
    Log.w(LOG_TAG, "后台链路初始化失败：$label", error)
}

/**
 * 后台任务统一入口：提醒重排、Widget 刷新与零点刷新调度。
 *
 * 设计原则（事件驱动，无固定轮询）：
 * - 提醒：长期最多一个 AlarmManager 闹钟（下一条触发时间）；
 * - Widget：数据/设置变化即时刷新，每日零点刷一次“今天/明天”，
 *   无 Widget 实例时取消全部后台任务；
 * - 只有事件（数据变化、开机、时间/时区变化、应用升级、闹钟权限变化）才重算。
 *
 * 例外中的例外：`Application.onCreate` 那条**冷启动**链现在会按三把钥匙跳过
 * （[ColdStartRebuild]）—— 跳过的前提与它买到的东西写在那里的类注释里。
 */
object BackgroundSync {

    private const val TAG = LOG_TAG
    private const val MIDNIGHT_REQUEST_CODE = 10_001
    private const val LEGACY_PERIODIC_WORK = "widget_refresh"

    /**
     * 重排下一条课程提醒（按最新数据库状态）。
     *
     * @return 本轮 [ReminderScheduler.rescheduleAll] 是否已经把「上/下课铃」一起接手。
     *   返回 false 时课堂窗口没人排，需要兜底链自己续排一次：
     *   ①「系统日历提醒」模式（应用内课前提醒一个闹钟都不排）。返回 false 这条契约不变，
     *     但那一支里的课堂铃清理**不再无条件**：只撤应用内提醒闹钟，课堂铃要等
     *     [shouldCancelAllInCalendarMode] 那两枚开关都关才当场收，否则整块跳过、
     *     清理交给这个 false 兑来的续排链（①与②走的是同一条收口，见下面那句 R5 F-12）；
     *   ② 没有下一条提醒（课表清空 / 学期已结束 / 提醒全关）—— 那条分支里 rescheduleAll
     *     只在"此刻没有课在进行"时才把课堂铃一起 cancelAll（否则会留下永不消失的常驻通知 +
     *     永久勿扰）；正上着课就不收，免得把勿扰记录与看门狗闹钟一起抹掉（见
     *     [com.buaa.schedule.reminder.ReminderScheduler.shouldTakeDownClassProgress]），
     *     清理留给这条兜底链 —— R5 F-12 要的续排一步都不能少。
     *   读库失败也返回 true：那种情况下调用方不该再补一遍同样的查询。
     *
     * 这个返回值同时回答另一个问题（[ColdStartRebuild] 的钥匙 2 要的就是它）：
     * **true ⟺ 本轮排上了一条课前提醒闹钟**。`rescheduleAll` 只有在挑出 plan 时才返回非空，
     * 而挑出 plan 就意味着它把那条闹钟排了（唯一的不一致是 AlarmManager 拿不到那条路：
     * 那种情况下仍然返回 true，于是钥匙 2 探不到闹钟、下一次冷启动照跑 —— 方向是安全的）。
     *
     * @param onStepFailed "哪一步抛了"的留痕口，默认 [NO_STEP_FAILURE] 等于无操作：
     *   各步自己那句 `Log.w` 原地保留（审计 §4.4 的取证过滤条件不许变），
     *   多出来的这个口只服务一件事 —— [ColdStartRebuild] 决定要不要写下"上次成功"的时间戳。
     */
    suspend fun rescheduleReminders(
        context: Context,
        onStepFailed: (label: String, error: Throwable) -> Unit = NO_STEP_FAILURE,
    ): Boolean {
        if (!usesInAppReminders(context)) {
            // 日历模式：应用内课前提醒那条通道一个闹钟都不排，已经把排好的那些清干净。
            runCatching {
                ReminderScheduler.cancelAll(context)
                // 课堂铃（上课实况 + 自动勿扰）**不跟着一起撤** —— 这一枚是同族第四处：
                // 无条件 cancelAll 的第一步就是撤双铃 + restore + 抹掉勿扰记录与看门狗，
                // 正在上课时每重排一次，用户就被放开勿扰约 5 秒，而且那份记录正是当下
                // 要靠的自愈凭据（同 ai/T11 / ai/T11b 那两处的账，只多不少）。
                // 「系统日历提醒」管的是**课前提醒从哪条通道下发**，课堂链没有日历等价物，
                // 所以这条链在日历模式下照样该跑，闸门只有那两枚课堂开关。
                // 两枚都关（= 用户自己把本功能关了）才当场收；只要有一枚还开着就整块跳过，
                // 清理交给这个 `return false` 兑现的那条续排链 —— 它自己那份带判据的清理
                // （[ClassProgressScheduler.rescheduleWindows]）管得了"挑不出窗口"与
                // "课还没开始且不在数课前倒计时"两种遗留，这里不复用它的全量窗口搜索
                // （紧接着就再搜一遍，纯白付；口径见 [shouldCancelAllInCalendarMode]）。
                val prefs = context.getSharedPreferences(
                    ClassProgressReceiver.PREFS_NAME,
                    Context.MODE_PRIVATE,
                )
                val classProgress = prefs.getBoolean(ClassProgressReceiver.PREF_CLASS_PROGRESS, true)
                val dndEnabled = prefs.getBoolean(ClassProgressReceiver.PREF_DND, false)
                if (shouldCancelAllInCalendarMode(classProgress, dndEnabled)) {
                    ClassProgressScheduler.cancelAll(context)
                }
            }.onFailure {
                Log.w(TAG, "日历模式下清理应用内闹钟失败", it)
                onStepFailed("cleanUpInCalendarMode", it)
            }
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
        }.onFailure {
            Log.w(TAG, "重排提醒失败", it)
            onStepFailed("rescheduleReminders", it)
        }.getOrDefault(true)
    }

    /**
     * 日历模式那条早退分支要不要连「上/下课铃」一起撤 —— **只认两枚课堂开关**。
     *
     * 判据口径与 [ClassProgressScheduler.rescheduleNextWindow] 开头读的那一份逐字相同
     * （`PREF_CLASS_PROGRESS` 默认 true、`PREF_DND` 默认 false，同一份 prefs）：
     * 续排链自己认为"两个开关都关 = 用户关了本功能"，那这里就跟着当场收干净；
     * 只要有一枚还开着，这条链在日历模式下**照样该跑**，一个字都不许撤 ——
     * 「系统日历提醒」这个选项管的是**课前提醒从哪条通道下发**（模式选择器自己的文案就是
     * 「提醒方式」），课堂实况与自动勿扰没有日历等价物，收掉等于从一整类用户手里拿走主打功能。
     *
     * 为什么这里是"两枚开关"而不是 [com.buaa.schedule.reminder.ReminderScheduler.shouldTakeDownClassProgress]
     * 那份完整判据（同族前三处用的就是它）：那一份要先做一轮
     * [ClassProgressScheduler.planNextClassWindow] 全量搜索才知道"课还在上 / 还在数倒计时"，
     * 而这个 `false` 紧随其后就要经由续排再做**同一轮**搜索并按那份判据清理
     * （`rescheduleWindows`：挑不出窗口 → cancelAll；课还没开始且不在数课前倒计时 →
     * 停服务 + 撤常驻 + restore）。在这里再搜一遍是纯白付的 O(课程数 × 剩余周次 × 节次段)
     * （学期中段上千次窗口构造，且挂在每一次冷唤醒上），买到的结论一秒钟后就会被再算一遍。
     *
     * 收成不接 Context 的两个 Boolean 参数，为的是这条判据在 JVM 单测里数得出来
     * （本模块没有 Robolectric，`getSharedPreferences` 是抛 "not mocked" 的桩）——
     * 与 [runColdStartWidgetSteps] 那个注入版本同一个理由。
     */
    internal fun shouldCancelAllInCalendarMode(classProgress: Boolean, dndEnabled: Boolean): Boolean =
        !classProgress && !dndEnabled

    /**
     * 「重排提醒」+「课堂铃兜底」的完整一次调用：[rescheduleReminders] 的返回值契约
     * 由这里就地兑现，调用方不必（也没法）再各自记得补那一步。
     *
     * 为什么要有这个包装：`false` 的含义是"本轮课堂铃没人排"（① 系统日历提醒模式；
     * ② 没有下一条提醒，那条分支的课堂铃清理按判据决定、剩下的归这里，
     * 口径见 [rescheduleReminders] 的 @return），
     * 而 [rescheduleReminders] 是个返回 Boolean 的 suspend 函数，
     * 广播接收器 / ViewModel / Application 那几处调用点全都在 `launch { }` 里直接丢弃了返回值 ——
     * 丢弃的后果不是少一行日志，而是这两类用户此后再也不会有「课程进行中」实况与
     * 上课自动勿扰（R5 F-12 的兜底链断在调用点）。把补排收进同一个函数，
     * 新调用点忘接返回值也不会再出错。
     *
     * 返回值原样转达 [rescheduleReminders] 的那个结论（"本轮排上了课前提醒闹钟"），
     * 是给 [ColdStartRebuild] 用的：钥匙 2 下一次该探哪一头闹钟由它决定。
     * 其余调用点照旧丢弃它。
     *
     * 兜底续排那一步（[ClassProgressScheduler.rescheduleNextWindow]）自己吞异常，但失败现在
     * 报给同一个 [onStepFailed]。ai/T13 在两处注释里登记的那条"已知残余 / 方向安全"就是这里，
     * 本卡（ai/T14）把它收掉。
     *
     * 为什么那句"方向安全"不够：它只在**铃确实被撤了却没排上**这半边成立 —— 那时钥匙 2 探
     * [ClassProgressScheduler.hasPendingClassBells] 得到"不在"，下一次冷启动照跑。
     * 可续排那一步真正会抛的点全在 [ClassProgressScheduler.rescheduleWindows] 的第一句
     * `cancel`（撤上/下课铃）**之前**：读 prefs、`scheduleRepository()`、`getCurrentSemester()`、
     * `getDisplayCourses` / `getTimeSlots` 任意一处抛（Room 的 `SQLiteFullException`、
     * cursor window 都是现实存在的），此时**上一轮那对课堂铃还挂在那里**，
     * 而本轮的清理与重排一个字都没执行。于是：
     * - 钥匙 2 探到"闹钟在"、钥匙 1 与 3 也放行 ⇒ 这一整轮被跳过，**最长 24 小时**；
     * - 被跳过的这 24 小时里用户用的是**上一轮的窗口** —— 课被删了还在响、
     *   改过时间了还按旧的时刻响。
     * 只有把这道失败记进 [ColdStartRebuild] 的 `failures` 清单（⇒ 不写指纹 ⇒
     * 下一次冷启动无条件重跑）才补得上这个洞。
     */
    suspend fun rescheduleRemindersAndBells(
        context: Context,
        onStepFailed: (label: String, error: Throwable) -> Unit = NO_STEP_FAILURE,
    ): Boolean {
        val reminderArmed = rescheduleReminders(context, onStepFailed)
        if (!reminderArmed) {
            // 与 WidgetFallbackWorker 同一口径：只在提醒那条链没接手课堂铃时才补排，
            // 无条件再排一遍等于把刚排上的上课铃撤了重排。
            // 报告口必须传下去（ai/T14）：这里不传，续排那道抛在 cancel 之前的失败就出不了
            // 它自己那个 runCatching，闸门会把这一轮记成成功并写下指纹。
            ClassProgressScheduler.rescheduleNextWindow(context, onStepFailed)
        }
        return reminderArmed
    }

    /**
     * 刷新全部桌面组件。
     *
     * ⚠️ 必须先让组件数据源失效并重写快照。组件与 RemoteViewsFactory 读的都是
     * [WidgetDataCache] → [WidgetDataSynchronizer] 快照，而快照此前只在
     * [onDataChanged] 里写过。改课表走的是 `ScheduleViewModel.afterDataChangedInternal()`，
     * 那里只调本方法：缓存与快照都不会失效，组件会一直渲染上一次 sync 时的旧课表
     * （最长要等到 12 小时的兜底 Worker），重启应用也不自愈。
     *
     * [hasAnyWidget] 是"桌面有没有组件"这个结论的入口，默认值就是本函数此前的行为 ——
     * 自己跨 binder 问一次 Launcher（`hasAnyWidgetSafely`），所以广播侧/Provider 侧的
     * 调用点一个字都不用改。它存在只为服务冷启动那条链：审计 §2.1 数出来同一次唤醒里
     * 这个问题被问了 3 遍（这里一遍、[scheduleWidgetMidnight] 一遍、
     * [WidgetFallbackWorker.ensure] 一遍），三处之间没有任何会改变组件数的写入，
     * 结论不可能不同。共享方式是把结论**当参数传下去**而不是缓存起来 ——
     * 没有缓存就没有陈旧问题（组件刚被拖上/拆掉的 `onEnabled` / `onDisabled` 那两条路
     * 拿到的仍然是自己当场探测的结果，见 [runColdStartWidgetSteps] 的注释）。
     */
    suspend fun refreshWidgets(context: Context, hasAnyWidget: Boolean = hasAnyWidgetSafely(context)) {
        runCatching {
            WidgetDataCache.invalidate()
            // 一个组件都没放时，下面的全量快照 sync（逐个学期查库 + JSON + upsert）与
            // 6 次 getAppWidgetIds 都没有读者 —— 开机/改时间/12 小时兜底每次都白跑一遍。
            if (!hasAnyWidget) return
            syncAndRedrawAllWidgets(context)
        }.onFailure { Log.w(TAG, "刷新 Widget 失败", it) }
    }

    /**
     * [refreshWidgets] 探测之后的那段重活：全学期快照重写 + 6 个 Provider 各自重绘。
     *
     * 单独成一个函数是为了让冷启动那条链在**问过一遍 Launcher** 之后整步跳过它
     * （见 [runColdStartWidgetSteps]），而不是再各自探测一次。6 次 `updateAll` 的
     * **顺序**仍然只有这一份实现（组件刷新时序是验证过的口径，别在这里挪）。
     */
    internal suspend fun syncAndRedrawAllWidgets(context: Context) {
        WidgetDataSynchronizer.sync(context)
        TodayWidgetProvider.updateAll(context)
        TomorrowWidgetProvider.updateAll(context)
        WeekWidgetProvider.updateAll(context)
        WeekGridWidgetProvider.updateAll(context)
        NextClassWidgetProvider.updateAll(context)
        TwoDayWidgetProvider.updateAll(context)
    }

    /**
     * 数据/提醒/学期/外观变化后的统一收尾：重排提醒 + 刷新 Widget（返回值见 [rescheduleReminders]）。
     *
     * [hasAnyWidget] 同 [refreshWidgets]：默认自己探测；[WidgetFallbackWorker.doWork] 开头已经
     * 问过一遍，把结论带进来就省掉这条链里的第二次探测。
     */
    suspend fun onDataChanged(context: Context, hasAnyWidget: Boolean = hasAnyWidgetSafely(context)): Boolean {
        val bellsHandled = rescheduleReminders(context)
        refreshWidgets(context, hasAnyWidget)
        return bellsHandled
    }

    /**
     * 调度下一次零点刷新（“今天/明天”Widget 的日期滚动）。
     * 没有 Widget 实例时不注册任何闹钟。
     *
     * [hasAnyWidget] 同 [refreshWidgets]：默认自己探测，冷启动那条链传结论进来。
     */
    fun scheduleWidgetMidnight(context: Context, hasAnyWidget: Boolean = hasAnyWidgetSafely(context)) {
        // 安全版：探测跨 binder 问 Launcher，MIUI 上会抛 DeadObjectException。
        // 探测失败按"有组件"处理 = 多注册一次幂等闹钟，无害。
        if (!hasAnyWidget) {
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
     * ⚠️ 必须挂起、就地跑完这三步（查库 → 判定 → 排闹钟），不许在这里自建协程：
     * 开机与改时间那两条广播把这段活圈在部分唤醒锁里，而锁的
     * [com.buaa.schedule.reminder.WakeLocks.withPartialWakeLock] 是 inline 的同步 block、
     * `finally` 当场 release —— 一 `launch` 出去 block 就返回、锁随之松开，
     * 剩下那两次查库和排闹钟就没有唤醒保证（故障面 `WakeLocks` 的类注释里写透了）。
     * 关掉开关那条短路不查库，仍在锁内当场撤销；唯一非挂起的调用点（设置页那枚开关）
     * 自己用 `rememberCoroutineScope()` 接。
     */
    suspend fun scheduleTomorrowPreview(context: Context) {
        val enabled = context
            .getSharedPreferences("schedule_settings", Context.MODE_PRIVATE)
            .getBoolean(TomorrowPreviewReceiver.PREF_ENABLED, true)
        if (!enabled) {
            TomorrowPreviewScheduler.cancel(context)
            return
        }
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

    /**
     * 冷启动（`Application.onCreate`）那条后台链里组件相关的四步：**一次唤醒只问一遍
     * "桌面上有没有我们的组件"**，结论给三个下游步骤共用（审计 §2.1）。
     *
     * 改动前这四步各自探测：[refreshWidgets] 一次、[scheduleWidgetMidnight] 一次、
     * [WidgetFallbackWorker.ensure] 一次。`hasAnyWidget` 是对 6 个 Provider 逐个
     * `getAppWidgetIds`（`any { }` 短路，最坏 6 趟 binder），三次探测又落在同一协程、
     * 同一时刻附近，中间没有任何会改变组件数的写入 —— 结论不可能不同，多出来的都是白付的 binder 往返。
     *
     * 口径（**不要**往下面几个方向顺手改）：
     * - 结论是**当参数传下去**的，不是缓存。没有跨调用的缓存，就不存在陈旧问题：
     *   用户刚拖上第一个组件走的 `onEnabled` → [WidgetCommon.bootstrapBackgroundSync]，
     *   与刚拆掉最后一个组件走的 [cancelWidgetMidnightIfNoWidgets]，两条都仍然自己当场探测，
     *   拿不到这里的结果（想让它们共用就得引入缓存，而那正是"漏一次 invalidate 就让新组件
     *   永远不刷新"那类事故的形状 —— 上一轮 F-26 是同一族）。
     * - 探测失败按"有组件"处理（口径同 [hasAnyWidgetSafely]）：宁可多刷一次，
     *   不能因探测失败把组件留在昨天。
     * - 每一步自己吞异常：这条链跑在 `SupervisorJob` 作用域里，块内未捕获的异常会落到线程的
     *   默认处理器、直接杀进程（原先这个保护写在 `BUAAApplication` 的 `step()` 里，
     *   四步收进来之后只有这里一份）。
     * - 步骤顺序与改动前逐条一致，一步都没挪。
     *
     * @return 这一次探测的结论（给调用方留痕用）
     */
    suspend fun runColdStartWidgetSteps(
        context: Context,
        reportStepFailure: (label: String, error: Throwable) -> Unit = LOG_COLD_START_STEP_FAILURE,
    ): Boolean = runColdStartWidgetSteps(
        probeHasWidgets = { hasAnyWidgetSafely(context) },
        invalidateWidgetCache = { WidgetDataCache.invalidate() },
        refreshWidgetData = { syncAndRedrawAllWidgets(context) },
        scheduleMidnightAlarm = { scheduleWidgetMidnight(context, hasAnyWidget = true) },
        cancelMidnightAlarm = { cancelWidgetMidnight(context) },
        scheduleTomorrowPreview = { scheduleTomorrowPreview(context) },
        ensureFallbackWorker = { hasWidgets -> WidgetFallbackWorker.ensure(context, hasAnyWidget = hasWidgets) },
        reportStepFailure = reportStepFailure,
    )

    /**
     * [runColdStartWidgetSteps] 的编排本体：不接 Context，七个动作与失败留痕全是注入的 lambda。
     *
     * 形状抄 [WidgetDataSynchronizer.planSnapshotRows] —— 本模块单测没有 Robolectric
     * （android.jar 里全是 "not mocked" 的桩），真跑一次这条链要设备，所以把"探测问了几遍"
     * 与"没组件时重活有没有短路"这两件事收进一个能用假 lambda 数调用次数与顺序的函数里
     * （见 `ColdStartWidgetStepsTest`）。
     */
    internal suspend fun runColdStartWidgetSteps(
        probeHasWidgets: suspend () -> Boolean,
        /** 探测之前先让进程内组件数据失效：改动前是 `refreshWidgets` 的第一件事，两条分支都要 */
        invalidateWidgetCache: suspend () -> Unit,
        /** 有组件才跑：全学期快照重写 + 6 个 Provider 重绘 */
        refreshWidgetData: suspend () -> Unit,
        /** 有组件才跑：排下一次零点闹钟 */
        scheduleMidnightAlarm: suspend () -> Unit,
        /** 没有组件才跑：撤掉可能残留的零点闹钟（组件被 ROM 连数据一起清掉、onDisabled 没来时唯一的自愈口） */
        cancelMidnightAlarm: suspend () -> Unit,
        /** 与组件数无关，照旧跑 */
        scheduleTomorrowPreview: suspend () -> Unit,
        /** 两条分支都要跑：没有组件不等于没有提醒（R5 F-16），登记还是注销由它自己按结论判断 */
        ensureFallbackWorker: suspend (hasWidgets: Boolean) -> Unit,
        /**
         * 失败留痕口，默认 [LOG_COLD_START_STEP_FAILURE]（= 原来那行 `Log.w`，一字不差）。
         * 之所以也收成一个参数：本模块 JVM 单测里 `android.util.Log` 是抛 "not mocked" 的桩，
         * 不把它隔开的代价是"某一步抛了后面的照跑"这条断言根本测不了
         * （一测就被桩自己的异常带偏，红得看不出原因）。
         */
        reportStepFailure: (label: String, error: Throwable) -> Unit = LOG_COLD_START_STEP_FAILURE,
    ): Boolean {
        // 注入的探测若抛（生产接线给的是 hasAnyWidgetSafely，正常不会走到这里），
        // 按"有组件"处理：方向只能是多刷一次，不能是少刷一次
        val hasWidgets = runCatching { probeHasWidgets() }
            .onFailure { reportStepFailure("probeHasWidgets", it) }
            .getOrDefault(true)
        runChainStep("invalidateWidgetCache", reportStepFailure) { invalidateWidgetCache() }
        if (hasWidgets) {
            runChainStep("refreshWidgets", reportStepFailure) { refreshWidgetData() }
            runChainStep("scheduleWidgetMidnight", reportStepFailure) { scheduleMidnightAlarm() }
        } else {
            runChainStep("cancelWidgetMidnight", reportStepFailure) { cancelMidnightAlarm() }
        }
        runChainStep("scheduleTomorrowPreview", reportStepFailure) { scheduleTomorrowPreview() }
        runChainStep("widgetFallbackWorker", reportStepFailure) { ensureFallbackWorker(hasWidgets) }
        return hasWidgets
    }

    /** 逐步吞异常 + 留痕：一步抛了后面的照跑（日志文案与 `BUAAApplication` 里那条一致，便于老过滤条件继续用） */
    private suspend fun runChainStep(
        label: String,
        report: (label: String, error: Throwable) -> Unit,
        block: suspend () -> Unit,
    ) {
        runCatching { block() }.onFailure { report(label, it) }
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
