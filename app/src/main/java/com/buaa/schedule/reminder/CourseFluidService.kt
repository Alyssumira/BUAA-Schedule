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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.buaa.schedule.BUAAApplication
import com.buaa.schedule.R
import com.buaa.schedule.domain.schedule.minutesCeil
import com.buaa.schedule.domain.schedule.snapshotRedeadlineMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
     */
    private fun finishLiveAndReschedule() {
        if (phase == LivePhase.BEFORE_CLASS) {
            recoverMissedClassStart()
            return
        }
        runCatching { ClassProgressDnd.restore(applicationContext) }
        ioScope.launch { ClassProgressScheduler.rescheduleNextWindow(applicationContext) }
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
     */
    private fun recoverMissedClassStart() {
        val window = liveWindow
        ioScope.launch {
            delay(START_BELL_GRACE_MS)
            if (!ReminderNotifications.ownsCountdownTo(window.courseId, window.endMillis)) return@launch
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
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "CourseFluidService"
        private const val NOTIFY_ID = 20_260_002

        /** 两次进度更新之间的最小间隔：避免异常短的课堂窗口把 handler 打成忙等 */
        // 与下面文件级 nextCourseFluidTickMs 同处一文件，常量放在那边。

        /**
         * 进程内「实况是否在跑」标志：onStartCommand 置 true、onDestroy 置 false。
         * 只用于同进程判断（LiveClassResyncer 的状态驱动兜底），进程被杀则标志归零，正确。
         */
        @Volatile
        var isRunning: Boolean = false
            private set

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
         */
        private fun keepsAlarmClockExemption(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return false
            return runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
        }

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

        /** 下课/取消：请求停止前台服务并移除通知 */
        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, CourseFluidService::class.java))
            }.onFailure {
                // 某些系统 stopService 失败；至少把通知清掉，由广播路径兜底
                NotificationManagerCompat.from(context).cancel(NOTIFY_ID)
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