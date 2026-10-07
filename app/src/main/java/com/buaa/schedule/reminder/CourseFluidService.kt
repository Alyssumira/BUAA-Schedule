package com.buaa.schedule.reminder

import android.app.AlarmManager
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.buaa.schedule.BUAAApplication
import com.buaa.schedule.R
import com.buaa.schedule.domain.schedule.minutesCeil
import com.buaa.schedule.domain.schedule.snapshotRedeadlineMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 实况收尾第一步（恢复勿扰）失败的留痕口（生产那一份）。
 *
 * 收成文件顶层的属性而不是写死在 `onFailure { Log.w(...) }` 里，理由与
 * `ClassProgressScheduler.kt` 顶层那三枚 `LOG_*` 属性一字不差：
 * `android.util.Log` 在本模块 JVM 单测里是抛 "not mocked" 的桩，写死就先崩在报告口之前，
 * 那条"失败要念得出来"的断言会红得看不出原因；tag 与级别只此一份，失败原因由调用点给。
 *
 * 级别只许 `Log.w` 以上：这台机器上的 HyperOS 把 logcat 截在 Info，`Log.d` 写下去等于没写，
 * 而这一格的后果是**整机停在完全静默**直到看门狗（下课 + 30 分钟）把铃声放回来。
 *
 * tag 写成字面量而不是伴生对象里那枚 `TAG`：顶层属性看不见类作用域的常量，
 * 同一件事在 `ClassProgressScheduler.kt` 的顶层那两枚属性里已经付过并注明过一次
 * （`logcat -d -s CourseFluidService` 的过滤条件因此一字不变）。
 */
private val LOG_LIVE_RESTORE_FAILURE: (Throwable) -> Unit = {
    android.util.Log.w("CourseFluidService", "实况收尾恢复勿扰失败，只剩看门狗与冷启动自愈", it)
}

/** 实况收尾第二步（续排下一节窗口）失败的留痕口：见 [LOG_LIVE_RESTORE_FAILURE] 那一段的理由 */
private val LOG_LIVE_RESCHEDULE_FAILURE: (Throwable) -> Unit = {
    android.util.Log.w("CourseFluidService", "实况收尾续排下一节窗口失败", it)
}

/**
 * 「窗口已过期也要先挂一帧前台通知」那一手失败的留痕口（Android 12+ 的契约要求）。
 * 改前它只写了 `.onSuccess`：挂上没挂上没人念，而 `foregroundNotificationPosted` 判错一格
 * 就是 onDestroy 抢清或漏清归属（同文件那三道保留）。
 */
private val LOG_FGS_CONTRACT_FAILURE: (Throwable) -> Unit = {
    android.util.Log.w("CourseFluidService", "过期窗口的契约前台通知没挂上（停止流程照常，归属可能留在屏上）", it)
}

/** onDestroy 摘前台通知失败的留痕口：归属那一判仍然要跟着跑，所以它不许把后面带走 */
private val LOG_STOP_FOREGROUND_FAILURE: (Throwable) -> Unit = {
    android.util.Log.w("CourseFluidService", "onDestroy 摘前台通知失败（归属清理照常执行）", it)
}

/**
 * 降级重排没递出去的留痕口（`reportLiveDegrade` 里那一手）。
 *
 * ⚠️ 只收异常本身，不许在这段里碰 `applicationContext` / `applicationScope` /
 * `getSystemService` —— 它站在 `onFailure` 里，`LiveFgsDegradeWiringGuardTest` 第 ⑧ 档
 * 按"凡碰框架的调用都必须落在某枚 runCatching 之内"数过这一条路。
 */
private val LOG_DEGRADE_RESCHEDULE_FAILURE: (Throwable) -> Unit = {
    android.util.Log.w("CourseFluidService", "降级重排没递出去，这一档只剩屏幕上那条同 id 通知", it)
}

/**
 * 实况收尾那两步的**纯 JVM 内核**（零 android、零时钟；[LiveFgsRetry.kt] 同一套路数）。
 *
 * 形状为什么必须是"两步各一枚兜底"而不是"整段一枚 runCatching"：第一步（恢复勿扰）
 * 抛了原先会把第二步（续排下一节窗口）一起带走，而第二步恰恰是"下一节课还有实况"的唯一
 * 指望；反过来第二步抛了也不该让已经静音的整机失去恢复。两半各一枚结论出口，
 * 红了才知道死的是哪半条（#334 / #333 / #335 是同一族，三处一起收）。
 *
 * 内核本身不读钟、不碰 Context：那些都在调用点算好递进来，所以这张表在单测里量得到
 * （成员版要先构造 Service，而 `Handler(Looper.getMainLooper())` 在单测里直接抛异常，
 * 与本文件 [nextCourseFluidTickMs] 同一个理由）。
 */
internal fun runLiveWrapUpSteps(
    restoreDnd: () -> Unit,
    rescheduleWindow: () -> Unit,
    onRestoreFailed: (Throwable) -> Unit,
    onRescheduleFailed: (Throwable) -> Unit,
) {
    runCatching(restoreDnd).onFailure { onRestoreFailed(it) }
    runCatching(rescheduleWindow).onFailure { onRescheduleFailed(it) }
}

/**
 * 课程实况前台服务（澎湃实况窗 / 流体云载体）。
 *
 * 纯 AOSP API 实现：specialUse 前台服务 + 常驻 ProgressStyle 通知。
 * **同一条服务承载两种阶段**（[LivePhase]）：课前由 [ReminderReceiver] 拉起来
 * 倒计到上课铃，课中由 [ClassProgressReceiver] 接手倒计到下课铃；两段的形状、
 * 通知 id、文案口径完全一致，只差第二行说"上课"还是"下课"。
 * 下课铃/取消路径停止；服务按 [nextTickMs] 重发通知：进度条前进一格、
 * 或岛上的倒计时小字翻一分钟，取更早的那个（一节课约 100 次），直到下课自动退出。
 *
 * 设计取舍：
 * - 只有用户开启「课程进行中」时才启动，普通提醒仍走 AlarmManager，不改变省电架构；
 * - 前台服务必须 `startForeground()` 后才能真正开始，因此进入服务先 post 首帧通知；
 * - 启动失败时调用方回退到普通常驻通知（`ReminderNotifications.startLiveWindow`
 *   在起服务之前就已经发过同 id 的 promoted 兜底）。
 *   **两处吞异常的地方现在都过 [reportLiveDegrade] 走一遍**：它固定吐一行
 *   `liveFgsDegraded site=… action=…`（`logcat -d -s CourseFluidService` 看得见），
 *   并按 [nextLiveFgsRetry] 的分档决定要不要把课堂窗口重排一遍、让下一发从闹钟豁免档进来。
 *   账与读数在 `docs/STATUS.md` 的 T78 段。
 */
class CourseFluidService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    /**
     * 续排用的作用域**不能**挂在本服务上：[finishLiveAndReschedule] 之后紧跟着就是
     * `stopSelf()`，onDestroy 会在协程还没被调度起来时到达 —— 挂在自己作用域上
     * 要么取消掉下一节课的窗口（只剩 12 小时兜底），要么干脆永远不取消而泄漏一个作用域。
     * 应用级作用域两个问题都没有，且闭包只捕获 applicationContext，不会牵住服务实例。
     */
    private val ioScope: CoroutineScope
        get() = (application as BUAAApplication).applicationScope
    private var liveWindow: ClassProgressScheduler.ClassWindow = EMPTY_WINDOW
    /** 现在数到哪：只影响卡片第二行的措辞，不影响重发节拍与进度算法 */
    private var phase = LivePhase.IN_CLASS
    /**
     * 本实例真的挂上过前台通知（`startForeground` 成功过）。
     *
     * onDestroy 要不要把归属摘掉，只看这一件事：**通知还在屏上，归属就还得护着它**。
     * `startForeground` 被拒的那一路（后台启动前台服务受限 / ROM 省电拦截）里，
     * `stopForeground(STOP_FOREGROUND_REMOVE)` 摘不掉任何东西 —— 屏幕上留着的是广播侧
     * 用同一枚 id 发的兜底倒计时，那正是"课前倒计时"此刻唯一的载体；那时把归属清掉，
     * 紧随其后的重排就会对着一条**确实还挂着**的倒计时走遗留清理（`cancelClassOngoing`
     * 当场撤掉它），修好的"课前倒计时上不了岛"就此原地复发（卡面第 2 种可能的反面）。
     */
    private var foregroundNotificationPosted = false
    private val updater = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (!liveWindow.endedAt(now)) {
                postProgressNotification()
                handler.postDelayed(this, nextTickMs(now))
            } else {
                // 下课：移除前台通知并停服；下课铃广播也会再兜底一次
                stopForeground(STOP_FOREGROUND_REMOVE)
                finishLiveAndReschedule()
                stopSelf()
            }
        }
    }

    /** 见 [nextCourseFluidTickMs]：进度格与岛上小字哪个先到就按它醒 */
    private fun nextTickMs(now: Long): Long =
        nextCourseFluidTickMs(liveWindow.startMillis, liveWindow.endMillis, now)

    /**
     * 实况自己收尾时恢复勿扰、并续排下一节课的窗口。
     *
     * 下课铃广播通常也会做同一件事（两步都是幂等的），但 ROM 把下课铃吞掉时，
     * 这条路径是唯一的机会：否则勿扰一直挂着，实况也不再排下一节。
     *
     * **课前那一段走 [recoverMissedClassStart]**：它倒计到上课铃为止，收尾时刻正是
     * [ClassProgressReceiver] 的 ACTION_START 在处理的时刻。这里若无条件再走一遍
     * `rescheduleNextWindow`，会先 `cancel()` 掉刚排好的下课铃、再把"开始时间已在过去"
     * 的上课铃重新点一次，用户看到的是课刚上岛就掉一下又重弹；勿扰恢复同理会把
     * ACTION_START 随后的 `enter()` 抵消掉（顺序一旦反过来就是"上课了却没静音"）。
     * 但**直接 return 也是错的**：ACTION_START 被省电策略吞掉时，课前岛在上课瞬间消失、
     * 整节课再也不出现，勿扰也不会静音 —— 那条广播恰恰是这条链上最不可靠的一环。
     *
     * ⚠️ 改前这里是一枚**没有 onFailure 的 `runCatching`**（#334，台账 #217 那本账里最贵的一格）：
     * `restore` 挂了整机就停在「完全静默」，`logcat -d -s CourseFluidService` 一个字都不留，
     * 只剩 `enter` 排的看门狗在「下课 + 30 分钟」把铃声/震动放回来，而现场取证读不出
     * "恢复勿扰失败过"。两步现在各有一枚结论出口，且**互不连坐**（内核与表见
     * [runLiveWrapUpSteps]，留痕口是文件顶层那两枚属性 —— 与本文件同族的理由写在
     * `ClassProgressScheduler.kt` 顶层那几枚属性上：JVM 单测里 `android.util.Log` 是抛
     * "not mocked" 的桩，写死就先崩在报告口之前）。
     */
    private fun finishLiveAndReschedule() {
        if (phase == LivePhase.BEFORE_CLASS) {
            recoverMissedClassStart()
            return
        }
        runLiveWrapUpSteps(
            restoreDnd = { ClassProgressDnd.restore(applicationContext) },
            rescheduleWindow = { ioScope.launch { ClassProgressScheduler.rescheduleNextWindow(applicationContext) } },
            onRestoreFailed = LOG_LIVE_RESTORE_FAILURE,
            onRescheduleFailed = LOG_LIVE_RESCHEDULE_FAILURE,
        )
    }

    /**
     * 上课铃疑似被吞时的补救：把这一节课的窗口重新排一遍。
     *
     * 判据沿用课前倒计时的归属状态（[ReminderNotifications.ownsCountdownTo]）：
     * ACTION_START 一旦落地就会以 IN_CLASS 重新下发实况、顺带把归属清零，
     * 所以"归属还在"就是"上课铃没跑过"的证据；重排后到期的上课铃会立刻触发，
     * 把课中实况、勿扰进入与下课铃整套补上，本服务不需要自己做任何展示。
     *
     * 为什么要先等 [START_BELL_GRACE_MS]：本帧与 ACTION_START 都在主线程消息队列里排队，
     * 谁先跑只看这一次调度 —— 正常送达时也可能差几十毫秒，抢在广播之前判就变成"证据还在、
     * 于是把刚排好的下课铃撤了重排"，正是要避免的那种抖。
     *
     * **这笔证据是本函数借来的、不是全进程的**：倒计时收尾时通知已经不在屏上，
     * 归属按不变量本该立刻归零（见 `ReminderNotifications.cancelClassOngoing` 的注释），
     * 只有"上课铃落地没有"这一判还在读它。所以这里把宽限期挂进
     * [startBellGracePending]（挡住 onDestroy 提前把证据清掉），判完**立刻自己清**：
     * 判据成立 ⇒ 随后 ACTION_START 会归零；判据不成立 ⇒ 归属早就是 0，清它是 no-op。
     * 两条出口都不留"屏上没倒计时、归属却一直挂着"的残账 —— 那种残账会让
     * `ReminderScheduler.shouldTakeDownClassProgress` 一直答"岛上正数着这节课、别收"，
     * 而它保护的那条通知早就不存在了。
     *
     * 清它走 CAS 版 [ReminderNotifications.releaseCountdownOwnership] 而不是无条件归零：
     * 这 5 秒里若已经有**另一节**课的倒计时把归属接了过去（同一枚通知 id 的正常换手），
     * 本函数那一判的证据与那份新账无关，不该由这里抹掉。
     */
    private fun recoverMissedClassStart() {
        val window = liveWindow
        // 归属读的是课前实况那一型窗口的上课时刻（口径单份在 ReminderNotifications.countdownAnchorOf）
        val anchor = ReminderNotifications.countdownAnchorOf(window)
        startBellGracePending = true
        ioScope.launch {
            delay(START_BELL_GRACE_MS)
            val bellMissed = ReminderNotifications.ownsCountdownTo(window.courseId, anchor)
            startBellGracePending = false
            ReminderNotifications.releaseCountdownOwnership(window.courseId, anchor)
            if (!bellMissed) return@launch
            android.util.Log.d(TAG, "上课铃未在窗口内落地，补排课堂窗口：${window.courseName}")
            ClassProgressScheduler.rescheduleNextWindow(applicationContext)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 进程内标志：供 LiveClassResyncer 判断实况是否已在跑（进程被杀则标志归 false，正确）
        isRunning = true
        liveWindow = ClassProgressScheduler.ClassWindow.from(intent?.extras)
        // 认不出的阶段值退回课中：课前/课中只差一句文案，为这个把实况停掉不值得
        phase = intent?.getStringExtra(EXTRA_PHASE)?.let { name ->
            runCatching { LivePhase.valueOf(name) }.getOrNull()
        } ?: LivePhase.IN_CLASS

        val window = liveWindow
        if (window.startMillis <= 0L || window.endMillis <= window.startMillis ||
            window.endMillis <= System.currentTimeMillis()
        ) {
            // 契约要求：即使马上结束也必须先 startForeground()，否则 Android 12+ 抛异常
            runCatching {
                startForeground(NOTIFY_ID, buildProgressNotification(if (window.endMillis > window.startMillis) 100 else 0))
            }.onSuccess {
                // 挂上过、下面立刻 stopForeground(REMOVE) 摘掉 ⇒ 这条实况确实已经不在屏上
                foregroundNotificationPosted = true
            }.onFailure {
                // #334 同族：改前这里只有 `.onSuccess`，失败连一句都没有。而 `foregroundNotificationPosted`
                // 正是 onDestroy 那三道保留的第一道 —— 这一手到底成没成读不出来，
                // 归属是该抢清还是该留着就只能猜（猜错 = 把刚上岛的倒计时拆掉，或留一笔没人收的残账）。
                LOG_FGS_CONTRACT_FAILURE(it)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            finishLiveAndReschedule()
            stopSelf()
            return START_NOT_STICKY
        }

        handler.removeCallbacks(updater)
        postProgressNotification()
        handler.postDelayed(updater, nextTickMs(System.currentTimeMillis()))
        return START_NOT_STICKY
    }

    private fun postProgressNotification() {
        val now = System.currentTimeMillis()
        val window = liveWindow
        val total = (window.endMillis - window.startMillis).coerceAtLeast(1L)
        val elapsed = (now - window.startMillis).coerceIn(0L, total)
        val progress = ((elapsed * 100L) / total).toInt().coerceIn(0, 100)
        // startForeground 可能抛 ForegroundServiceStartNotAllowedException（后台启动前台服务受限）
        // 或 SecurityException（FGS 类型/通知权限异常）。失败就记录并退出服务：
        // 广播侧已经先发过一条普通常驻通知兜底，用户仍然能看到"课程进行中"，
        // 但不能让异常从这里逃出去（会让调用方/服务崩溃）。
        runCatching {
            startForeground(NOTIFY_ID, buildProgressNotification(progress))
        }.onSuccess {
            // 这一格是 onDestroy 那句判据的前提：只有真的挂上过前台通知，
            // "本实例销毁"才等于"这条实况不在屏上了"
            foregroundNotificationPosted = true
        }.onFailure {
            android.util.Log.w(TAG, "startForeground 失败，回退普通常驻通知", it)
            stopSelf()
            reportLiveDegrade(
                context = applicationContext,
                window = window,
                phase = phase,
                site = SITE_START_FOREGROUND,
                error = it,
            )
        }
    }

    private fun buildProgressNotification(progress: Int): Notification {
        val window = liveWindow
        val now = System.currentTimeMillis()
        val body = liveBody(
            window.sectionText, window.startMillis, window.endMillis, window.location, phase, now,
        )
        val contentIntent = ReminderNotifications.courseLaunchPendingIntent(this, window.courseId)
        // 小字只算一次：setter 与 extras 兜底必须给同一句，否则读到哪一份说不清
        val chipText = ReminderNotifications.chipCountdownLabel(window.endMillis)
        val subText = liveSubText(window.week, window.dayOfWeek, window.teacher)
        // 课程色与课表卡片一致。没取到色时必须交回 COLOR_DEFAULT 而不是 0：
        // `Notification.COLOR_DEFAULT` 恰好就是 0，但把它写进 Segment 会被当成一格
        // 全透明的进度，整条进度条直接消失，而不是"这条通知没着色"。
        val accent = window.colorArgb

        // Android 16+：直接走框架 ProgressStyle，接 tracker 图标 + Segment(100)，
        // 对齐 SleepDown 实测能上澎湃超级岛的写法（R4 P1-2）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            val segment = Notification.ProgressStyle.Segment(100)
            accent?.let { segment.setColor(it) }
            val builder = Notification.Builder(this, ReminderNotifications.CHANNEL_CLASS_PROGRESS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(window.courseName)
                .setContentText(body)
                .setSubText(subText)
                .setStyle(
                    Notification.ProgressStyle()
                        .setProgressTrackerIcon(
                            Icon.createWithResource(this, R.drawable.ic_progress_dot)
                        )
                        .setProgressSegments(listOf(segment))
                        .setProgress(progress)
                )
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                // 不给 chronometer：SleepDown 取证写明系统渲染的倒计时会**顶掉 promoted chip**
                // （岛上那一格），所以它用 setShowWhen(false) + 应用侧每分钟重发。
                // 倒计时文案由下面 nextTickMs 的重发节拍驱动，精度到分钟即可。
                .setShowWhen(false)
                .setColor(accent ?: Notification.COLOR_DEFAULT)
                .setContentIntent(contentIntent)
            // 小字必须走真正的 setter（对齐 SleepDown）：框架的读回口是 getShortCriticalText()，
            // 我们此前只往 extras 里塞同一个键，岛上一格显示的是 ROM 通用文案「进行中」。
            ReminderNotifications.applyShortCriticalText(builder, chipText)
            return builder.build().also { notification ->
                // extras 仍双写一份作兜底；requestPromotedOngoing 在 API 36 的框架 builder 上
                // 没有对应 setter（只有 androidx 兼容层有，已核实），extras 是那条的唯一手段。
                ReminderNotifications.applyPromotedOngoingExtras(notification, chipText)
                ReminderNotifications.logPromotionShape("fluidService", notification)
                IslandFocusTemplate.attach(this, notification, NOTIFY_ID, window)
            }
        }

        // 低版本：没有 tracker/segments 语义，继续用兼容 ProgressStyle。
        // 注意不 setSilent：静默通知会被 ROM 的实况/焦点提升逻辑忽略。
        val compatSegment = NotificationCompat.ProgressStyle.Segment(100)
        accent?.let { compatSegment.setColor(it) }
        val builder = NotificationCompat.Builder(this, ReminderNotifications.CHANNEL_CLASS_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(window.courseName)
            .setContentText(body)
            .setSubText(subText)
            .setStyle(
                NotificationCompat.ProgressStyle()
                    .setProgressSegments(listOf(compatSegment))
                    .setStyledByProgress(true)
                    .setProgress(progress)
            )
            // 一条通知只能有一个 style：这里留给进度条，两行正文在低版本上会被折叠成一行。
            // 完整两行效果由上面 Android 16 的框架 ProgressStyle 承担（对齐 SleepDown 的
            // `setStyle(null) + setProgress` 取舍：宁可保住进度条这一格）。
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setColor(accent ?: Notification.COLOR_DEFAULT)
            .setContentIntent(contentIntent)
        ReminderNotifications.applyShortCriticalText(builder, chipText)
        return builder.build().also { notification ->
            ReminderNotifications.applyPromotedOngoingExtras(notification, chipText)
            ReminderNotifications.logPromotionShape("fluidServiceCompat", notification)
            // 澎湃焦点通知早于 Android 16 就存在，这条分支同样值得挂模板
            IslandFocusTemplate.attach(this, notification, NOTIFY_ID, window)
        }
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(updater)
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }.onFailure {
            // 摘不掉不代表可以不清归属：下面那三道保留照跑，但这一手失败必须念得出来
            // （屏幕上到底还剩什么，是读这条链的残骸时唯一的问题；#334 同族第④格）
            LOG_STOP_FOREGROUND_FAILURE(it)
        }
        // stopForeground(REMOVE) 把通知摘掉之后，归属就不能再"保护"任何一节课 ——
        // 这条不变量写在 ReminderNotifications.cancelClassOngoing 的注释里，而这一格此前是漏的：
        // 服务被系统/ROM 掐掉、或 stopService 之后走到这里，通知已经随前台状态一起没了，
        // 归属却会一直挂到下一次某枚 cancelClassOngoing 调用点被撞到；中间那一段里
        // ReminderScheduler.shouldTakeDownClassProgress 答的是"岛上正数着这节课、别收"，
        // 而它保护的那条通知早就不存在了 —— 于是真遗留（上一节课那条滑不掉的常驻通知 +
        // 勿扰记录 + 看门狗）也一并被这条不存在的倒计时挡回去（R5 F-11 / F-15 那两条的语义）。
        // 三道保留，缺一就把还挂在屏上的倒计时判成"已经不在了"：
        //  1) 本实例真的挂上过前台通知（foregroundNotificationPosted）—— startForeground 被拒
        //     那一路里 stopForeground 摘不掉任何东西，屏上留着的是广播侧同 id 的兜底倒计时，
        //     那份归属护的正是它，摘掉它 = 卡面第 2 种可能（课前倒计时刚上岛就被拆掉）；
        //  2) 课前那一段刚刚数到上课铃 ⇒ 归属换了用途（"上课铃没落地"的唯一证据），
        //     由 recoverMissedClassStart 判完立刻清；这里抢清会把补救判成"铃已经落地"。
        //  3) 归属记的已经不是本实例这一节 ⇒ 那是下一段实况的账（同一枚通知 id 被接手过），
        //     不归这里清 —— 所以走条件版 releaseCountdownOwnership，而不是无条件归零。
        // 为什么不走整包的 cancelClassOngoing：它会 cancel 同一枚通知 id，而此刻那条通知
        // 可能正是上课铃之后那条课中实况的，本实例没有撤它的资格（撤法只归它自己的宿主）。
        if (foregroundNotificationPosted && !startBellGracePending) {
            ReminderNotifications.releaseCountdownOwnership(
                courseId = liveWindow.courseId,
                classStartMillis = ReminderNotifications.countdownAnchorOf(liveWindow),
            )
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "CourseFluidService"

        /**
         * 与课堂实况那条通知**同一枚 id**（唯一字面量在 `ReminderNotifications` 的
         * `NOTIFY_ID_CLASS_PROGRESS` 上）：服务起来后正是靠同 id 覆盖掉广播侧先发的兜底那条，
         * 岛模板与 `stop()` 撤的也必须是同一条。这里曾是第二份 `20_260_002` 字面量
         * （同一枚事实两本账，改一枚不改另一枚 ⇒ "撤的不是发出去的那条"，零报错）。
         */
        private const val NOTIFY_ID = ReminderNotifications.NOTIFY_ID_CLASS_PROGRESS

        /** 两次进度更新之间的最小间隔：避免异常短的课堂窗口把 handler 打成忙等 */
        // 与下面文件级 nextCourseFluidTickMs 同处一文件，常量放在那边。

        /**
         * 进程内「实况是否在跑」标志：onStartCommand 置 true、onDestroy 置 false。
         * 只用于同进程判断（LiveClassResyncer 的状态驱动兜底），进程被杀则标志归零，正确。
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * 「上课铃落地没有」那一判还在读课前倒计时的归属（见
         * [com.buaa.schedule.reminder.CourseFluidService.recoverMissedClassStart]）。
         *
         * 挂 companion 而不是实例字段：判据跑在应用级作用域里，而这条服务的实例
         * 在 stopSelf 之后随时可能被重建（同一枚通知 id 换人接手），实例字段会在
         * "旧实例已销毁、宽限期还没判完"那一档把证据错交给下一段实况。
         * 进程被杀 ⇒ 归 false，而那时归属也一起归零（同一进程内状态），语义仍对。
         */
        @Volatile
        private var startBellGracePending = false

        /** [LivePhase] 的名字；缺省按课中处理 */
        const val EXTRA_PHASE = "extra_phase"

        /** 收不到 extras 时的空窗口：只用于 stopSelf 前那一帧，不会显示成有课 */
        private val EMPTY_WINDOW = ClassProgressScheduler.ClassWindow(0L, "", null, "", 0L, 0L)

        /**
         * 课前倒计时收尾后，给上课铃广播留的投递宽限（见 [recoverMissedClassStart]）。
         * 同一进程的主线程消息排队最多晚几十毫秒，5 秒足够把"在路上"和"被吞了"分开，
         * 又远小于一节课的长度 —— 补排晚这几秒，用户不会察觉。
         */
        private const val START_BELL_GRACE_MS = 5_000L

        /** [reportLiveDegrade] 的两枚站名：进 `liveFgsDegraded site=` 那一格，grep 时分档用 */
        private const val SITE_START_FOREGROUND = "startForeground"
        private const val SITE_START_SERVICE = "startService"

        /**
         * 一个课堂窗口最多因为降级重排几次。
         *
         * 写死 1，不做"多试几次看运气"：隔 2.3 / 5.3 / 20.4 秒重投同一发，AMS 的判据
         * 逐字段相同（`uidState: RCVR; code:DENIED; tempAllowListReason:<null>`，六发全 DENIED），
         * 说明这一档的拒绝与等待时长无关 —— 多试只是多几发注定 DENIED 的 binder。
         * 上限在这里唯一的用处，是挡住"重排 → 新的一发又降级 → 再重排"那条自激回路。
         */
        private const val MAX_LIVE_FGS_RETRY = 1

        /** 本进程内「最近为哪个窗口重排过几次」；窗口身份口径见 [liveFgsAttemptsAlreadyArmed] */
        @Volatile
        private var retriedCourseId = 0L

        @Volatile
        private var retriedEndMillis = 0L

        @Volatile
        private var retriedTimes = 0

        /** 账本的读改写要成一把：`:159` 与 `:310` 两处都往这里报 */
        private val retryLedger = Any()

        /**
         * 实况降级的那一笔账：留一行能 grep 的痕迹，并按 [nextLiveFgsRetry] 决定要不要把课堂
         * 窗口重排一遍 —— 换一条带闹钟豁免的触发源再进来，而不是原地重投。
         *
         * 三条约束都是这台机器上量出来的，不是设计偏好：
         * - **不重投同一发** `startForegroundService`（判据与时长无关，见 [MAX_LIVE_FGS_RETRY]）；
         * - **不新增通知、不改通知 id、不弹 toast**：屏幕上留下的就是广播侧先发出的那条同 id
         *   兜底常驻通知，这是本 app「少打扰」的口味，一次降级不该再骚扰用户第二遍；
         * - **整条路不许抛**：这里已经站在 `onFailure` 里面，从这儿逃出去的异常会把下课铃广播
         *   （[ClassProgressReceiver] 整段 runCatching 之内）或服务主线程直接打崩。
         */
        private fun reportLiveDegrade(
            context: Context,
            window: ClassProgressScheduler.ClassWindow,
            phase: LivePhase,
            site: String,
            error: Throwable,
        ) {
            val app = runCatching { context.applicationContext }.getOrDefault(context)
            val now = System.currentTimeMillis()
            val attempts: Int
            val decision: LiveFgsRetry
            synchronized(retryLedger) {
                attempts = liveFgsAttemptsAlreadyArmed(
                    recordedCourseId = retriedCourseId,
                    recordedEndMillis = retriedEndMillis,
                    courseId = window.courseId,
                    endMillis = window.endMillis,
                    recordedAttempts = retriedTimes,
                )
                decision = nextLiveFgsRetry(
                    windowStillLive = !window.endedAt(now),
                    inClassPhase = phase == LivePhase.IN_CLASS,
                    bellAlreadyRung = !window.startsAfter(now),
                    nextAttemptKeepsAlarmClockExemption = keepsAlarmClockExemption(app),
                    attemptsAlreadyArmed = attempts,
                    maxAttempts = MAX_LIVE_FGS_RETRY,
                )
                if (decision == LiveFgsRetry.ArmOnce) {
                    retriedCourseId = window.courseId
                    retriedEndMillis = window.endMillis
                    retriedTimes = attempts + 1
                }
            }
            // 这一行是 #119 的取证口：出问题先 `logcat -d -s CourseFluidService` 看它。
            // action=armed 说明链子已经自己重排过一遍；action=skip:* 说明这一档重排也没用，
            // 屏幕上那条同 id 通知就是这节课实况的全部残骸。栈由上面两行 WARN 负责带，
            // 这里只留一行可 grep 的判据，不重复第二份 trace。
            android.util.Log.w(
                TAG,
                "liveFgsDegraded site=$site action=${decision.token} course=${window.courseId} attempts=$attempts",
                error,
            )
            if (decision != LiveFgsRetry.ArmOnce) return
            // 重排挂应用级作用域：这里可能正站在一个马上 onDestroy 的服务里，也可能站在
            // 一条广播的临时 Context 上。形状同 finishLiveAndReschedule()。
            runCatching {
                (app as? BUAAApplication)?.applicationScope?.launch {
                    ClassProgressScheduler.rescheduleNextWindow(app)
                }
            }.onFailure {
                // 改前这一枚也是裸吞：`liveFgsDegraded action=armed` 那行刚刚念完"已经重排过了"，
                // 递出去那一手却可能没成 —— 于是取证读到的是一句没有下游的承诺（#334 同族第⑤格）。
                // 这里只收异常，不碰 Context / 作用域：那条路的框架调用一律要在 runCatching 之内
                //（`LiveFgsDegradeWiringGuardTest` 第 ⑧ 档数的就是这一条）。
                LOG_DEGRADE_RESCHEDULE_FAILURE(it)
            }
        }

        /**
         * 重排出来的那一发还带不带「闹钟豁免档」。
         *
         * 全服务只在这一处判 `Build.VERSION` 与权限，[nextLiveFgsRetry] 收的是算好的布尔
         * —— 判据内核不许被调用点各判一遍 SDK_INT。
         *
         * 2026-09-23 实测：`appops set com.buaa.schedule SCHEDULE_EXACT_ALARM deny` 之后，
         * 重排出去的那条铃叫醒的广播里 `CourseFluidService.start` 依旧
         * `not allowed due to mAllowStartForeground false` —— 未授权时
         * [ClassProgressScheduler.scheduleClassStartBell] 换的是 `setAndAllowWhileIdle`，
         * 它不是 `setAlarmClock`，AMS 不给 `ALARM_MANAGER_ALARM_CLOCK` 那一档豁免。
         *
         * **整段包住 `runCatching`，判不出来时按"没豁免"答**（2026-09-23 复核第 2 件事时补的）：
         * `getSystemService` 收的是调用点递来的 Context，而 `SITE_START_SERVICE` 那一处递的是广播的
         * Context（[ClassProgressReceiver] 的临时收件上下文），它站在这条唯一不许抛的路上。
         * 判不出来时答 `false` = 这一档判成 [LiveFgsRetry.SkipStructural]（少排一次铃），
         * 而让它抛出去 = 广播或服务主线程当场崩 —— 两害相权取前者。
         */
        private fun keepsAlarmClockExemption(context: Context): Boolean = runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@runCatching true
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return@runCatching false
            alarmManager.canScheduleExactAlarms()
        }.getOrDefault(false)

        /**
         * 启动一段课程实况。
         *
         * 课中传 `startMillis = 上课铃, endMillis = 下课铃`；课前倒计时传
         * `startMillis = 此刻, endMillis = 上课铃`——同一条服务、同一个通知 id，
         * 只是进度条量的是"这段等待过去了多少"、第二行数到上课而不是下课。
         *
         * extras 的读写只在 [ClassProgressScheduler.ClassWindow] 一处：这条链上四个进程边界
         * 各写一套键名时，加字段就会只有加的那一处看得到（周次/教师/课程色此前这样丢过）。
         */
        fun start(
            context: Context,
            window: ClassProgressScheduler.ClassWindow,
            phase: LivePhase,
        ) {
            val intent = Intent(context, CourseFluidService::class.java).apply {
                putExtras(window.toExtras())
                putExtra(EXTRA_PHASE, phase.name)
            }
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure {
                android.util.Log.w(TAG, "启动课程实况前台服务失败，回退普通常驻通知", it)
                reportLiveDegrade(
                    context = context,
                    window = window,
                    phase = phase,
                    site = SITE_START_SERVICE,
                    error = it,
                )
            }
        }

        /**
         * 下课/取消：请求停止前台服务并移除通知。
         *
         * `stopService` 失败那一支走 [ReminderNotifications.cancelClassOngoing]，不自己
         * `NotificationManagerCompat.cancel` —— "撤这条通知"与"清这份归属"是同一件事的两半
         * （不变量写在 `ReminderNotifications.cancelClassOngoing` 的注释里）。此前这里只撤通知、
         * 不清归属，绿得全靠三枚调用点（`ClassProgressReceiver` 的 ACTION_END、
         * `ClassProgressScheduler` 的 `rescheduleWindows` / `cancelAll`）都紧跟一句
         * `cancelClassOngoing` —— 那是调用点在替它成对；这一卡把它们收成一处，
         * 第四枚调用点漏掉后半句也不会再静默破掉不变量。
         */
        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, CourseFluidService::class.java))
            }.onFailure {
                // 某些系统 stopService 失败；至少把通知清掉，由广播路径兜底
                ReminderNotifications.cancelClassOngoing(context)
            }
        }
    }
}

/** 两次进度更新之间的最小间隔：避免异常短的课堂窗口把 handler 打成忙等 */
private const val MIN_TICK_DELAY_MS = 1_000L

/** 小字翻转后再多等一点，确保 `now` 真的越过了取整边界（与 SleepDown 同一做法） */
private const val CHIP_TICK_EPSILON_MS = 150L

/**
 * 距离下一次真正需要重发实况通知还有多久。
 *
 * 去掉 chronometer 之后，岛上的小字（`shortCriticalText`）是**静态文本**，
 * 不重发就不会自己走，所以取两个事件里更早的那个：
 * - 进度条前进一格（一节课最多 100 次）；
 * - 小字减少一分钟。翻转时刻取自 [snapshotRedeadlineMillis]（"这条文案最迟什么时候
 *   必须再发一次"的判据，与 [com.buaa.schedule.domain.schedule.minutesCeil] 的向上取整
 *   口径同一份实现），而不是 SleepDown 那样对齐整分钟墙钟——后者相对 `endMillis`
 *   有最多一分钟错位，小字会晚一分钟才翻。
 *
 * 文件级纯函数（而不是成员方法）是为了能被 JVM 单测钉住：成员版要先构造 Service，
 * 而 `Handler(Looper.getMainLooper())` 在单测里直接抛异常。
 */
internal fun nextCourseFluidTickMs(startMillis: Long, endMillis: Long, now: Long): Long {
    val total = (endMillis - startMillis).coerceAtLeast(1L)
    val elapsed = (now - startMillis).coerceIn(0L, total)
    val progress = (elapsed * 100L) / total
    val nextStepElapsed = ((progress + 1L) * total + 99L) / 100L
    val progressTick = startMillis + nextStepElapsed - now

    val minutesLeft = minutesCeil(endMillis - now)
    // 翻转时刻就是"这条文案最迟什么时候必须再发一次"的判据本身（见 snapshotRedeadlineMillis）。
    // 这里此前手抄了一遍 `end - (minutesLeft-1)*60_000`，同一判据两份实现，
    // 改一处就会让服务醒来时屏幕上那个数字还没翻 —— 晚一分钟才跳。
    //
    // epsilon 只能加在真实时刻上：Long.MAX_VALUE 哨兵再 +150 会溢出成极小负数，
    // minOf 选中它、coerceAtLeast 再把间隔钉回 1 秒 —— 一旦外部条件哪天挡不住
    // minutesLeft<=0 这支，就是每秒重绘一次的忙轮询。
    val chipTick =
        if (minutesLeft <= 0L) Long.MAX_VALUE
        else snapshotRedeadlineMillis(minutesLeft, endMillis) - now + CHIP_TICK_EPSILON_MS

    return minOf(progressTick, chipTick).coerceAtLeast(MIN_TICK_DELAY_MS)
}