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
import com.buaa.schedule.domain.schedule.ChainReadSnapshot
import com.buaa.schedule.reminder.ClassProgressReceiver
import com.buaa.schedule.reminder.ClassProgressScheduler
import com.buaa.schedule.reminder.ReminderScheduler
import com.buaa.schedule.reminder.TomorrowPreviewReceiver
import com.buaa.schedule.reminder.TomorrowPreviewScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
 * 后台链入口那一次读：**成功交回载体，失败交回 null**（null = 下游各步改回自己查库）。
 *
 * 为什么失败要折成 null 而不是往上抛：这一读的用途只是"省掉同一批表的第二遍查询"，
 * 它自己抛了（Room 的 SQLiteFullException、cursor window 都是现实存在的）绝不能把整条重建链
 * 带停 —— 交回 null 之后，`rescheduleReminders` / `refreshWidgets` / `scheduleTomorrowPreview`
 * 各自照旧查自己的那份，用户侧一个字节都不会少读（宁可多读一趟，不许少读）。
 *
 * 一个例外**必须**往上抛：协程取消。它是控制流而不是失败，吞在这里就等于把"调用方已经不想
 * 跑这条链了"折成"继续往下跑"（同 [com.buaa.schedule.reminder.BootReceiver] 自己那句
 * `catch (e: CancellationException) { throw e }`、`UpdateCheck` 那处注释写的同一个口径）。
 *
 * [com.buaa.schedule.widget.ColdStartRebuild] 不用这一手：那条链的失败要进 `failures` 清单，
 * 否则闸门会写下"上次成功"的指纹、把这一轮白跳 24 小时（ai/T14 那笔账），所以它直接注入
 * `loadChainReadSnapshot()` 并自己报失败。
 */
internal suspend fun readChainSnapshotOrNull(context: Context): ChainReadSnapshot? = try {
    context.scheduleRepository().loadChainReadSnapshot()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w(LOG_TAG, "读后台链快照失败，链上各步改回自己查库", e)
    null
}

/**
 * 冷启动组件那四步的留痕口：与它们改动前各自那句 `Log.w` 一字不差
 * （四步收进 [BackgroundSync.runColdStartWidgetSteps] 时就是这样，日志文案是
 * 老过滤条件继续能用的前提）。同样收成顶层属性，理由同 [NO_STEP_FAILURE]。
 */
internal val LOG_COLD_START_STEP_FAILURE: (label: String, error: Throwable) -> Unit = { label, error ->
    Log.w(LOG_TAG, "后台链路初始化失败：$label", error)
}

/**
 * 日历模式那一步清理失败的那句 `Log.w` —— 原样搬来，tag 与文案一字未改
 * （审计 §4.4 的取证过滤条件不许变），只是挪成了 [BackgroundSync.cleanUpInCalendarMode]
 * 编排本体的日志钩子默认值。
 *
 * 为什么非挪不可：`android.util.Log` 在本模块 JVM 单测里是抛 "not mocked" 的桩，
 * 留在 `onFailure { }` 里第一句就让报告口根本轮不到被调用，那条"失败要能被闸门看见"的
 * 断言会红得看不出原因（同 [com.buaa.schedule.reminder.ClassProgressScheduler] 里
 * `LOG_RESCHEDULE_WINDOW_FAILURE` 那一份，理由一字不差）。
 * 同样写成顶层属性而不是签名里的 lambda：本仓库的源码形状守卫按"锚点后第一个 `{`"切函数体。
 */
internal val LOG_CALENDAR_CLEANUP_FAILURE: (Throwable) -> Unit = {
    Log.w(LOG_TAG, "日历模式下清理应用内闹钟失败", it)
}

/**
 * [BackgroundSync.syncAndRedrawAllWidgets] 逐层试完之后，"有哪几层抛了"那份清单的载体（#676）。
 *
 * 为什么要有这么一枚异常而不让各层自己吞掉：分层闸门解决的是**连坐**（一家抛 = 六家不画），
 * 但它顺手会解决掉第二件事 —— 外层再也看不见里面抛过。这条链的外层有两道，
 * 一道是 [BackgroundSync.refreshWidgets] 自己那把闸门（兜底 Worker 走这一头），
 * 另一道是 [BackgroundSync.runColdStartWidgetSteps] 里那枚 `runChainStep("refreshWidgets")`
 * （冷启动走这一头，而那一头报出来的失败正是 [ColdStartRebuild] 决定"要不要写下上次成功的指纹"的凭据）。
 * 分层之后不把这声交回去，冷启动那一条就会把"六家里有五家没画成"记成干净、白跳最长 24 小时 ——
 * 那是拿一个新病换一件旧病，本卡不接受。
 *
 * 它**不是** `CancellationException` 的子类，也不许有谁把它当失败折成 true：
 * 取消那一族的口径见 [readChainSnapshotOrNull] 与 [BackgroundSync.rescheduleReminders]。
 */
internal class WidgetRedrawLayersFailed(val failedLayers: List<String>) :
    Exception("组件重绘有 ${failedLayers.size} 层抛了：${failedLayers.joinToString(" ／ ")}")

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
        snapshot: ChainReadSnapshot? = null,
    ): Boolean {
        if (!usesInAppReminders(context)) {
            // 日历模式：应用内课前提醒那条通道一个闹钟都不排，已经把排好的那些清干净。
            // 撤哪几样、按什么顺序、失败报给谁全写在编排本体 [cleanUpInCalendarMode] 里
            // （那一份不接 Context，JVM 单测数得出来"撤了几遍、报了几次"）；这里只把三件要
            // Context 的事读成参数注进去 —— 手法同 [runColdStartWidgetSteps] 与
            // [com.buaa.schedule.reminder.ClassProgressScheduler.rescheduleNextWindow]。
            cleanUpInCalendarMode(
                cancelInAppReminderAlarms = { ReminderScheduler.cancelAll(context) },
                // 那一轮 prefs 仍然只读一遍（同一轮里两次磁盘，两次结论可以不一致）
                readClassSwitches = {
                    val prefs = context.getSharedPreferences(
                        ClassProgressReceiver.PREFS_NAME,
                        Context.MODE_PRIVATE,
                    )
                    Pair(
                        prefs.getBoolean(ClassProgressReceiver.PREF_CLASS_PROGRESS, true),
                        prefs.getBoolean(ClassProgressReceiver.PREF_DND, false),
                    )
                },
                // 完整那一份：cancelAll 才带 restore 与撤常驻
                cancelClassBells = { ClassProgressScheduler.cancelAll(context) },
                onStepFailed = onStepFailed,
            )
            return false
        }
        // #677：这一枚原来是 `runCatching { … }.onFailure { … }.getOrDefault(true)`。
        // 块内有两处挂起点（`loadChainReadSnapshot()` 与 `rescheduleAll`），于是"调用方已经把这条链摘掉"
        // 那一次 CancellationException 会被折成一次普通失败，再被末尾那记兜底折成 true ——
        // "别跑了"被读成"排上了"。口径与上面 [readChainSnapshotOrNull] 逐字相同：**取消是控制流，不是错误**，必须复投。
        // ⚠️ 复投只能写成现在这个样子（把外面那一层换掉）：在 `runCatching { try … catch { throw 折出来的那枚 } }`
        // 里补复投是空操作，同一层会把自己 catch 里抛出去的东西再吞一遍（`ui/ScheduleViewModel` 的
        // `suspendCatching` 就是那个形状的活反例，本卡不动它，只登记为"不许照抄"）。
        // 第二臂吃 Throwable 而不是 Exception：原来那一层本来吃 Throwable，收窄会把 Error 那一档
        // （定制 ROM 的 NoSuchMethodError 之类）放逃到广播与 Worker 里去崩。
        return try {
            // 一枚链只读一遍：入口把四张表的结论装进载体递进来，这里不再各自回头查。
            // 没有载体（单独调用这一步的调用点）照旧自己读，一次也不比别人少。
            val read = snapshot ?: context.scheduleRepository().loadChainReadSnapshot()
            // rescheduleAll 的返回值就是本轮那一次全量搜索的结论：
            // 再搜一遍既白跑上千次窗口构造，又要多读一次时钟（同源约束见 ReminderScheduler）
            ReminderScheduler.rescheduleAll(
                context,
                read.courses,
                read.semester,
                read.timeSlots,
                read.remindersByCourseId,
            ) != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "重排提醒失败", e)
            onStepFailed("rescheduleReminders", e)
            // 结论仍然是 true，一字不改（硬约束）：那枚 `true` 是 [ColdStartRebuild] 钥匙 2 的输入，
            // "读库失败也返回 true"与它的方向性都写在上面的 @return 里，改窄就是让钥匙 2 探不到。
            // #675 要修的是**读法**（[WidgetFallbackWorker] 把这一档读成"课堂铃有人接手"）与留痕，
            // 报告口现在交给调用方，见 [onDataChanged] 的 `onStepFailed`。
            true
        }
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
     * [rescheduleReminders] 日历模式那一支的编排本体：**不接 Context，三件副作用全是注入的 lambda**。
     *
     * 形状抄 [runColdStartWidgetSteps] 与
     * [com.buaa.schedule.reminder.ClassProgressScheduler.rescheduleNextWindow] 那两份 seam ——
     * 本模块单测没有 Robolectric，`ReminderScheduler.cancelAll` / `ClassProgressScheduler.cancelAll`
     * 与 `getSharedPreferences` 都要 Context，真跑一次这条清理要设备。而这张卡要钉的恰好是
     * 数得出来的那三件事：应用内闹钟撤了几遍、课堂铃在四种开关组合下撤没撤、抛了有没有向闸门
     * 报一声（见 `CalendarModeClassBellCleanupTest`）。放在这里的代价是"接线到底注没注对那三样"
     * 只能按源码形状核对 —— 与前一处 seam 完全同一套取舍。
     *
     * 语义与抽出前逐字相同，一步不多、一步不少：
     * - 应用内课前提醒那条闹钟**一律**撤（那就是这个模式的本意），而且撤在判据**之前**；
     * - 课堂铃（上课实况 + 自动勿扰）**不跟着一起撤** —— 这一枚是同族第四处：无条件 cancelAll
     *   的第一步就是撤双铃 + restore + 抹掉勿扰记录与看门狗，正在上课时每重排一次，用户就被
     *   放开勿扰约 5 秒，而且那份记录正是当下要靠的自愈凭据（同 ai/T11 / ai/T11b 那两处的账，
     *   只多不少）。只有 [shouldCancelAllInCalendarMode] 认的两枚开关都关（= 用户自己把本功能
     *   关了）才当场收；只要有一枚还开着就整块跳过，清理交给调用方那个 `return false` 兑现的
     *   那条续排链 —— 它自己那份带判据的清理（[ClassProgressScheduler.rescheduleWindows]）管得了
     *   "挑不出窗口"与"课还没开始且不在数课前倒计时"两种遗留，这里不复用它的全量窗口搜索
     *   （紧接着就再搜一遍，纯白付；口径见 [shouldCancelAllInCalendarMode]）；
     * - 两枚开关只读一遍（同一轮里两次磁盘，两次结论可以不一致）；
     * - 整块仍然吞异常（改成向外抛，等于把这条清理链变成调用方的崩溃），失败同时留两处：
     *   原样那句 `Log.w`（默认值 [LOG_CALENDAR_CLEANUP_FAILURE]，tag 与文案一字未改）先走，
     *   然后 [onStepFailed] 那**一个**标签 `cleanUpInCalendarMode` —— 闸门（[ColdStartRebuild]）
     *   靠它把这一轮记成失败，标签换个名它就数不到这一格。
     *
     * @param cancelInAppReminderAlarms 撤应用内课前提醒那条闹钟（生产：`ReminderScheduler.cancelAll`）
     * @param readClassSwitches 两枚课堂开关，顺序是 `classProgress to dndEnabled`
     *   （生产：读一遍 `ClassProgressReceiver.PREFS_NAME` 那份 prefs）
     * @param cancelClassBells 撤课堂铃那一份完整的收（生产：`ClassProgressScheduler.cancelAll`，
     *   只有它带 restore 与撤常驻；只 `cancel` 双铃就是"只撤一半"的新残留）
     * @param onStepFailed "这一步抛了"的留痕口，由 [rescheduleReminders] 把它自己那个原样传下来
     * @param logFailure 那句 `Log.w` 的位置，默认 [LOG_CALENDAR_CLEANUP_FAILURE]（= 抽出前那一行，
     *   一字不差）；收成参数的理由同 [runColdStartWidgetSteps] 那个参数，一字不差。
     */
    internal fun cleanUpInCalendarMode(
        cancelInAppReminderAlarms: () -> Unit,
        readClassSwitches: () -> Pair<Boolean, Boolean>,
        cancelClassBells: () -> Unit,
        onStepFailed: (label: String, error: Throwable) -> Unit,
        logFailure: (Throwable) -> Unit = LOG_CALENDAR_CLEANUP_FAILURE,
    ) {
        runCatching {
            cancelInAppReminderAlarms()
            val (classProgress, dndEnabled) = readClassSwitches()
            if (shouldCancelAllInCalendarMode(classProgress, dndEnabled)) {
                cancelClassBells()
            }
        }.onFailure {
            logFailure(it)
            onStepFailed("cleanUpInCalendarMode", it)
        }
    }

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
        snapshot: ChainReadSnapshot? = null,
    ): Boolean {
        val reminderArmed = rescheduleReminders(context, onStepFailed, snapshot)
        if (!reminderArmed) {
            // 与 WidgetFallbackWorker 同一口径：只在提醒那条链没接手课堂铃时才补排，
            // 无条件再排一遍等于把刚排上的上课铃撤了重排。
            // 报告口必须传下去（ai/T14）：这里不传，续排那道抛在 cancel 之前的失败就出不了
            // 它自己那个 runCatching，闸门会把这一轮记成成功并写下指纹。
            // 载体也必须传下去（#536 F2）：续排那一步读的正是上面那三张表。
            ClassProgressScheduler.rescheduleNextWindow(context, onStepFailed, snapshot)
        }
        return reminderArmed
    }

    /**
     * 「App 内改了壁纸」这条路的组件重绘：**即发即忘**，调用线程只付一次 launch。
     *
     * 存在的理由只有一个：[com.buaa.schedule.core.designsystem.Personalization.save] 是从
     * 设置页/首页/组件配置页的点击回调里调的，而 [refreshWidgets] 是挂起函数，整条链
     * （问 Launcher 有没有组件 + 全学期快照重写 + 六个 Provider 逐个重绘）不能压回主线程。
     * 收口也只有 save() 那一处 —— 十几个调用点各自复制一段刷新代码，早晚漏一处，
     * 而且没人复制得到的那几处就是「换完壁纸、桌面还在糊旧图」。
     *
     * 覆盖范围是**全部**已绑定的实例（[refreshWidgets] 走六个 Provider 的 `updateAll`），
     * 包括组件配置页正开着的那一个。
     */
    fun refreshWidgetsAsync(context: Context) {
        val appContext = context.applicationContext
        // 协程里这一手是最后一道闸门：默认参数那次跨 binder 探测（MIUI 上会抛
        // DeadObjectException）与协程默认处理器接不住就是杀进程 —— 同 WidgetCommon.launchRefresh
        // 那笔 R5 F-24 的账，只是这里没有 PendingResult 要 finish。
        // #677 把外面那一层换成带复投的 try/catch：吞异常那一半一个字没少，
        // 只是"调用方把这一颗协程摘掉"从此不再被折成一次普通失败（复投排在闸门之外，理由见 [rescheduleReminders]）。
        CoroutineScope(Dispatchers.IO).launch {
            try {
                refreshWidgets(appContext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "壁纸变更后刷新组件失败", e)
            }
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
     *
     * [hasAnyWidget] 是"桌面有没有组件"这个结论的入口，默认值就是本函数此前的行为 ——
     * 自己跨 binder 问一次 Launcher（`hasAnyWidgetSafely`），所以广播侧/Provider 侧的
     * 调用点一个字都不用改。它存在只为服务冷启动那条链：审计 §2.1 数出来同一次唤醒里
     * 这个问题被问了 3 遍（这里一遍、[scheduleWidgetMidnight] 一遍、
     * [WidgetFallbackWorker.ensure] 一遍），三处之间没有任何会改变组件数的写入，
     * 结论不可能不同。共享方式是把结论**当参数传下去**而不是缓存起来 ——
     * 没有缓存就没有陈旧问题（组件刚被拖上/拆掉的 `onEnabled` / `onDisabled` 那两条路
     * 拿到的仍然是自己当场探测的结果，见 [runColdStartWidgetSteps] 的注释）。
     *
     * [onStepFailed] 是"这一整步抛了"的留痕口，默认 [NO_STEP_FAILURE] = 只留原来那句 `Log.w`
     * （审计 §4.4 的取证过滤条件不许变）。它存在的理由只有一条：#675 之后
     * [WidgetFallbackWorker] 那条链要能分清"组件刷新干净地跑完了"与"里面有一层抛了"，
     * 而这条链对某些用户是唯一兜底 —— 把失败折成静默成功，就等于把兜底摘掉还不留案底。
     */
    suspend fun refreshWidgets(
        context: Context,
        hasAnyWidget: Boolean = hasAnyWidgetSafely(context),
        snapshot: ChainReadSnapshot? = null,
        onStepFailed: (label: String, error: Throwable) -> Unit = NO_STEP_FAILURE,
    ) {
        // #677：块内两处挂起（快照 sync 与六家重绘），所以复投排在闸门之外，形状与理由见 [rescheduleReminders]。
        // #675：这枚闸门现在会把结论交给调用方的报告口 —— 默认 [NO_STEP_FAILURE]，
        // 所以那十几处调用点一个字没变；只有兜底 Worker 那一条链要的是"抛了必须让我知道"。
        try {
            WidgetDataCache.invalidate()
            // 一个组件都没放时，下面的全量快照 sync（逐个学期查库 + JSON + upsert）与
            // 6 次 getAppWidgetIds 都没有读者 —— 开机/改时间/12 小时兜底每次都白跑一遍。
            if (!hasAnyWidget) return
            syncAndRedrawAllWidgets(context, snapshot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "刷新 Widget 失败", e)
            onStepFailed("refreshWidgets", e)
        }
    }

    /**
     * [refreshWidgets] 探测之后的那段重活：全学期快照重写 + 6 个 Provider 各自重绘。
     *
     * 单独成一个函数是为了让冷启动那条链在**问过一遍 Launcher** 之后整步跳过它
     * （见 [runColdStartWidgetSteps]），而不是再各自探测一次。6 次 `updateAll` 的
     * **顺序**仍然只有这一份实现（组件刷新时序是验证过的口径，别在这里挪）。
     *
     * 「调用路里有三条课表数据一个字没改（开机 / 改时间 / 12 小时兜底，加每日零点那一条），
     * 要不要把这里收窄」—— 评估过，两条都**不做**，理由记在这里免得下一个人再量一遍：
     *
     * 1. **「数据没变就跳过快照重写」这一半已经由快照侧自己收掉了**（见
     *    [WidgetDataSynchronizer.planSnapshotWrites]）：一轮 K = 学期数 + 1 行里，
     *    内容逐字节相同且表里那行还被读侧认账的，一行 upsert 都不发，
     *    而这四条路一行都不用发 ⇒ 这里再判一次"数据变没变"买 0 行。
     *    反过来，"这里干脆整步不调 [WidgetDataSynchronizer.sync]"是**不安全**的：
     *    [refreshWidgets] 开头已经无条件 `WidgetDataCache.invalidate()`，
     *    不 sync 就没有任何一行带着新时刻的快照，六个 Provider 的每一次读都会掉进
     *    `WidgetDataCache.get` 的主库回退分支（`WidgetDataCache.kt:78-88`，
     *    每次 3 趟查询 + 1 次补写），比这一次性 sync 贵得多。
     * 2. **「按受影响 provider 收窄重绘」拿不到"受影响"这个结论。** 六个 provider 读的都是
     *    同一份 [WidgetData]（今日 / 明日 / 周课表 / 周网格 / 下一节课 / 今明两栏都按教学周
     *    过滤课程），任一字段变化六家都可能变；要判"只有今日变"得先有一套"哪个实例绑了哪个
     *    学期、画的是哪一天"的枚举与依赖图，[WidgetBindingStore] 只有逐 `appWidgetId` 读写、
     *    没有枚举接口（同 [WidgetDataSynchronizer.sync] 类注释第 1 条取证）。
     *    而省下来的的量级本来就不大：`updateAll` 每家第一件事就是 `getAppWidgetIds`，
     *    **没有实例的那几家就地返回**（`WidgetCommon.updateAllOfProvider*`），
     *    一轮 6 次跨 binder 探测之后真正付渲染的只有桌面上那几家的实例数；
     *    真数据变化（[onDataChanged]）六家都得重绘，收窄收益为 0、风险为"漏一家"。
     *
     * ⚠️ 另有一条路**不归这里管、也不许被任何收窄顺路收进去**：
     * [WidgetCommon.requestLiveRefresh]（上/下课铃驱动的轻重绘，只碰今日/下一节课/今明两栏）。
     * 它刻意不走 [refreshWidgets]，因为那一刻课表没改、只是时间翻面 ——
     * 今日列表的「进行中」高亮正是随铃翻面的，任何"数据没变就不用重绘"的判据落在它身上
     * 都是功能回归。
     *
     * ## 在册 #676：这一段的闸门从"六家共用一把"换成"逐层各一把"（T400②）
     *
     * 改动前的形状是七手裸排（一枚快照 sync + 六家 `updateAll`），整段一把闸门都没有，
     * 唯一的兜在调用点：`refreshWidgets` 那一把，与冷启动 `runChainStep("refreshWidgets")` 那一把。
     * 后果是**三层连坐**，一层比一层安静：
     * - 第一家某枚实例渲染抛 ⇒ 那一家剩下的实例不画（`WidgetCommon.updateAllOfProvider*` 体内那记裸 `ids.forEach`）；
     * - 那一家任何一手抛 ⇒ 后面五家整轮不画（就是这一段）；
     * - 而日志只有一句"刷新 Widget 失败"，分不出断在哪一层，这条链又没有重试。
     *
     * 现在这一段把闸门分成**七层**（顺序一字未挪，见上面那条"别在这里挪"），每层各一句 `Log.w` 点名是谁家，
     * 层体与层名全部交给 [runIsolatedRedrawLayers] 那枚不接 Context 的执行体 ——
     * 收成一个 seam 是为了让"第一家抛了后面五家画没画"这件事在 JVM 单测里数得出来（本模块没有 Robolectric）。
     * 各层试完之后仍然把失败清单经 [WidgetRedrawLayersFailed] 交回外层（那一手的理由与"分层不许把失败吃干净"
     * 写在那枚异常自己的 KDoc 里）。
     *
     * ⚠️ **这条链收到这里为止**：第一层连坐（同一家内部的逐实例）在 [WidgetCommon] 那四枚 `ids.forEach` 体内，
     * 不在本卡的射程里 ⇒ 现在"一家抛 = 后面五家整轮不画"收掉了，
     * 而"一家抛 = 同家剩下的实例不画"仍然成立，只是它从此**有名字**（那一家会留下一整条 label）。
     * 谁把这一格报成"连坐已收干净"就是谎报。
     */
    internal suspend fun syncAndRedrawAllWidgets(context: Context, snapshot: ChainReadSnapshot? = null) {
        val failedLayers = runIsolatedRedrawLayers(
            layers = listOf(
                "WidgetDataSynchronizer.sync" to { WidgetDataSynchronizer.sync(context, snapshot) },
                "TodayWidgetProvider.updateAll" to { TodayWidgetProvider.updateAll(context) },
                "TomorrowWidgetProvider.updateAll" to { TomorrowWidgetProvider.updateAll(context) },
                "WeekWidgetProvider.updateAll" to { WeekWidgetProvider.updateAll(context) },
                "WeekGridWidgetProvider.updateAll" to { WeekGridWidgetProvider.updateAll(context) },
                "NextClassWidgetProvider.updateAll" to { NextClassWidgetProvider.updateAll(context) },
                "TwoDayWidgetProvider.updateAll" to { TwoDayWidgetProvider.updateAll(context) },
            ),
            reportLayer = { label, error ->
                // 每层一句、句里带层名：改前那一句"刷新 Widget 失败"分不出断在哪一层，
                // 而这条链的取证全靠 grep（审计 §4.4）—— 一句到不了层，就等于七层共用一张案底。
                Log.w(TAG, "组件重绘这一层抛了，后面的层照跑：$label", error)
            },
        )
        if (failedLayers.isNotEmpty()) throw WidgetRedrawLayersFailed(failedLayers)
    }

    /**
     * [syncAndRedrawAllWidgets] 那一段的执行体：**逐层试完，一层抛了后面的照跑**，把抛过的层名按顺序交回调用方。
     *
     * 不接 Context、层名与层体全是注入的 —— 形状照 [cleanUpInCalendarMode] 与 [runColdStartWidgetSteps]
     * 那两份 seam，理由一字不差：本模块 JVM 单测没有 Robolectric，而这张卡要钉的恰好是数得出来的那件事
     * （"第一家抛了后面五家画没画"、"抛过的层叫什么名字"、"取消算不算一层失败"）。
     * 代价照旧在册：接线注的是哪七层、顺序对不对，只能按源码形状核对（见
     * `WidgetBackgroundChainGateTest`）。
     *
     * 三条判据都写在这七行里，逐条都有格子钉着：
     * - **各层都不许连坐**：循环体不 break、不 rethrow 真失败，试完为止（#676 收的就是这一条）；
     * - **取消不是失败**：[CancellationException] 一律复投，且**复投排在这枚执行体自己的 catch 臂里**，
     *   不套任何吞异常的外层（#677 的口径；把外面那层换掉才有用，在 runCatching 里补复投是空操作）；
     *   复投之后后面的层不再跑 —— 那是调用方已经不想跑了，不是这一层抛了。
     * - **失败要留痕也要能数**：每抛一层调用 [reportLayer] 一次（生产接线给它的是一句带层名的 `Log.w`），
     *   层名同时进返回清单，交回外层由外层的闸门决定怎么上报。
     *
     * 第二臂吃 [Throwable]：这条链上"抛"的真实形状包含 Error（RemoteViews 在个别 ROM 上抛的就是
     * NoSuchMethodError 那一族），换成只吃 Exception 就等于把那一档放回连坐里。
     *
     * @param layers 层名到层体，**顺序就是执行顺序**（组件刷新时序是验证过的口径）
     * @param reportLayer 每一层抛了说一次的口子；默认值给不了（谁在调用它谁就得自己决定留痕去向），
     *   这也是它能被单测数出"报了几次、报的是哪个名字"的原因
     * @return 抛过的层名，按执行顺序
     */
    internal suspend fun runIsolatedRedrawLayers(
        layers: List<Pair<String, suspend () -> Unit>>,
        reportLayer: (label: String, error: Throwable) -> Unit,
    ): List<String> {
        val failedLayers = mutableListOf<String>()
        for ((label, body) in layers) {
            try {
                body()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failedLayers += label
                reportLayer(label, e)
            }
        }
        return failedLayers
    }

    /**
     * 数据/提醒/学期/外观变化后的统一收尾：重排提醒 + 刷新 Widget（返回值见 [rescheduleReminders]）。
     *
     * [hasAnyWidget] 同 [refreshWidgets]：默认自己探测；[WidgetFallbackWorker.doWork] 开头已经
     * 问过一遍，把结论带进来就省掉这条链里的第二次探测。
     *
     * 这里也是**一枚链**（兜底 Worker 每 12 小时那一趟就是这两步连着跑），所以它自己就是入口：
     * 载体在这里造一次，两步共用（#536 F2）。造失败就当没有载体 —— 那两步照旧各自读，
     * 一个字节都不会少读（宁可多读，不许少读）。
     *
     * ## 在册 #675：这里为什么多了一枚 [onStepFailed]（T400②）
     *
     * 改动前 [WidgetFallbackWorker] 调进来的就是这一枚函数，而它的形参表里**根本没有报告口**
     * —— 于是 [rescheduleReminders] 抛那一档（`true` 交回去、课堂铃没人续排）在 Worker 侧
     * 只剩一句 `Log.w`，结构上不可见：Worker 既补不了续排，也照样 `Result.success()` 让 WorkManager
     * 不再重试。现在两步的结论都递进出口，默认 [NO_STEP_FAILURE] ⇒ 除 Worker 之外的调用点一个字没变。
     *
     * ⚠️ 这里**没有**改那枚 `true` 的含义（它是 [ColdStartRebuild] 钥匙 2 的输入，方向性是成文的：
     * 宁可多跑一轮）。修的是**读法** —— 见 [WidgetFallbackWorker.doWork] 里那枚 `reportFailedStep`。
     */
    suspend fun onDataChanged(
        context: Context,
        hasAnyWidget: Boolean = hasAnyWidgetSafely(context),
        onStepFailed: (label: String, error: Throwable) -> Unit = NO_STEP_FAILURE,
    ): Boolean {
        val snapshot = readChainSnapshotOrNull(context)
        val bellsHandled = rescheduleReminders(context, onStepFailed, snapshot)
        refreshWidgets(context, hasAnyWidget, snapshot, onStepFailed)
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
            // 在册 #680 C-13 同族（T400②）：这一支改前一句留痕都没有。它是正常路径，所以走 Info 级
            // 而不是 Warning —— 但**不许走 Debug**：本仓的取证口径写着真机 logcat 被 HyperOS 截在 Info 级
            // （见 `MonotonicWindowGateWiringGuardTest` 与 `WidgetRolloverClaimWiringGuardTest` 那两格
            // 「这一族零枚 Log 得不许」），写了等于没写的留痕不是留痕。
            Log.i(TAG, "桌面没有组件：撤掉残留的零点闹钟，这一轮不排新闹钟")
            cancelWidgetMidnight(context)
            return
        }
        val nextMidnight = LocalDate.now().plusDays(1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        // getSystemService(Class) 在个别定制 ROM / 低内存实例上会返回 null，
        // 直接点调用会 NPE 崩在广播里
        // 同一族第二支（改前也是静默 return）：这条链是"今天/明晚翻面"唯一的闹钟，
        // 拿不到服务就是"组件停在昨天"的一条真因，必须在案底上留下名字。控制流一个字不动（继续 return，
        // 不抛 ⇒ "别崩在广播里"那句既有理由原样保留）。
        val alarmManager = context.getSystemService(AlarmManager::class.java)
            ?: run {
                Log.w(TAG, "拿不到 AlarmManager：这一轮的零点闹钟没排上，组件会停在今天直到下一次事件")
                return
            }
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
     * 关掉开关那条短路不查库，仍在锁内当场撤销。
     *
     * 设置页那枚开关（唯一非挂起的调用点）**不许**再用本页 `rememberCoroutineScope()` 接
     * （#536 F3，本卡收掉）：那一枚 scope 绑在组合上，按返回键就整颗取消，而排闹钟那一手前面
     * 还隔着查库 —— 于是 prefs 已经落盘、开关下次进来照样亮着、闹钟一颗也没排上。
     * 现在它挂在 `ScheduleViewModel.onTomorrowPreviewToggled` 的 `viewModelScope` 上。
     *
     * [snapshot] 是同一条链上游读好的那一份（#536 F2）；带着一颗链的载体进来就不再回头查
     * 学期行与课程表，只有单独调用这一步时才自己读。
     */
    suspend fun scheduleTomorrowPreview(context: Context, snapshot: ChainReadSnapshot? = null) {
        val enabled = context
            .getSharedPreferences("schedule_settings", Context.MODE_PRIVATE)
            .getBoolean(TomorrowPreviewReceiver.PREF_ENABLED, true)
        if (!enabled) {
            TomorrowPreviewScheduler.cancel(context)
            return
        }
        // #677：块内有挂起点（`loadChainReadSnapshot()` 与 `TomorrowPreviewScheduler.schedule`），
        // 所以外面那一层整个换掉：取消复投排在 catch 臂里，真失败仍然就地吞（这条链跑在广播的
        // 部分唤醒锁里，抛出去就是崩广播 —— 改前那句 `Log.w` 的 tag 与文案一字未动，审计 §4.4）。
        try {
            val read = snapshot ?: context.scheduleRepository().loadChainReadSnapshot()
            val fireDay = TomorrowPreviewReceiver.nextScheduledPreviewDay(
                courses = read.courses,
                semester = read.semester,
                today = LocalDate.now(),
            )
            if (fireDay == null) {
                Log.d(TAG, "往后没有可推的明日预告，事件入口不排闹钟")
            } else {
                TomorrowPreviewScheduler.schedule(context, fireDay)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "调度明日预告失败", e)
        }
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
        snapshot: ChainReadSnapshot? = null,
    ): Boolean = runColdStartWidgetSteps(
        probeHasWidgets = { hasAnyWidgetSafely(context) },
        invalidateWidgetCache = { WidgetDataCache.invalidate() },
        refreshWidgetData = { syncAndRedrawAllWidgets(context, snapshot) },
        scheduleMidnightAlarm = { scheduleWidgetMidnight(context, hasAnyWidget = true) },
        cancelMidnightAlarm = { cancelWidgetMidnight(context) },
        scheduleTomorrowPreview = { scheduleTomorrowPreview(context, snapshot) },
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
        // 按"有组件"处理：方向只能是多刷一次，不能是少刷一次。
        // #677：这一枚原来也是 `runCatching { … }.getOrDefault(true)`，而 `probeHasWidgets` 是 suspend ——
        // 调用方把这条链摘掉时那一次取消会被折成"探测抛了 ⇒ 按有组件处理"，
        // 于是四步照样跑完、还多报一枚 `probeHasWidgets` 的假失败。复投排在 catch 臂里，形状同 [rescheduleReminders]。
        val hasWidgets = try {
            probeHasWidgets()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            reportStepFailure("probeHasWidgets", e)
            true
        }
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
        // #677：这一枚是冷启动那四步的公共闸门，`block` 是 suspend ⇒ 取消真的会从这里进来。
        // 改前 `runCatching { block() }.onFailure { report(label, it) }` 把它折成一次普通步骤失败：
        // 于是"用户已经退出这条链"会让 [runColdStartWidgetSteps] 把剩下的步骤继续跑完，
        // 还会向 [ColdStartRebuild] 报一枚假失败（那一轮本该是"被摘掉"，不是"跑坏了"）。
        // 外层那把 runCatching 是整个换掉的，不是在它块里补 catch —— 后者是空操作（见 rescheduleReminders 那段旁白）。
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            report(label, e)
        }
    }

    fun cancelWidgetMidnight(context: Context) {
        // 同一族第三支（卡面未列、T400② 一并收）：撤不掉残留闹钟也是"组件停在昨天"的一条来路，
        // 而它改前同样是静默 return。留痕级别与排闹钟那支一致（拿不到服务不是正常路径 ⇒ Warning）。
        val alarmManager = context.getSystemService(AlarmManager::class.java)
            ?: run {
                Log.w(TAG, "拿不到 AlarmManager：残留的零点闹钟没撤掉")
                return
            }
        // 取消这一头只查不造，理由见 ReminderScheduler.cancelAll 的注释；
        // 查不到就是没排过，本来就是 no-op
        existingMidnightPendingIntent(context)?.let { alarmManager.cancel(it) }
    }

    /** 所有 Widget 都被移除时调用：取消零点闹钟与旧版周期任务，并让兜底轮询重新判定自己是否还需要 */
    fun cancelWidgetMidnightIfNoWidgets(context: Context) {
        // 这条链现在只从 WidgetCommon.cancelMidnightIfNoWidgetsFromReceiver 进来
        // （六个 Provider 的 onDisabled 已在 T18 收进基类的那一份）：活跑在 goAsync()
        // 续命的那条 Dispatchers.IO 协程上，不再占广播的主线程。
        // 之所以必须挪：WorkManager 改成按需初始化之后，进程里第一个
        // `WorkManager.getInstance` 的人要就地建它自己的库，而这条链一次带上两处
        // getInstance（旧版周期任务退出 + 兜底重判定）外加一次跨 binder 探测。
        // 探测仍要走不可抛版本：挪线程只解决"钱付在哪个线程"，不解决"抛了会崩广播"。
        // 探测失败按"有组件"处理 = 不取消零点闹钟，零点白刷一次，无害。
        if (!hasAnyWidgetSafely(context)) {
            cancelWidgetMidnight(context)
            cancelLegacyPeriodicWork(context)
            // 兜底轮询交回 ensure() 判定：组件没了但提醒还走应用内闹钟时要留着
            WidgetFallbackWorker.ensure(context)
        }
    }

    /**
     * 旧版本的固定周期刷新任务在升级后应退出。
     *
     * T18 之后它多了一重身份：`BUAAApplication.onCreate` 那条后台链的第一就是这一步，
     * 而 WorkManager 的自动初始化已从清单摘掉 —— 也就是**进程里第一次
     * `getInstance` 由这里发起**，建库的钱付在 Dispatchers.IO 上。
     * 这一步刻意留在 [ColdStartRebuild] 的闸门外面（论证见那里的类注释），
     * 所以每一次冷启动都会把这道预热走一遍，不依赖"这次到底有没有事要做"。
     * 自己那句 `runCatching` 是这条链不崩的唯一原因：初始化抛出来就是杀进程。
     */
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
