package com.buaa.schedule.reminder

import android.app.Notification
import android.app.PendingIntent
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
import com.buaa.schedule.MainActivity
import com.buaa.schedule.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 课程实况前台服务（澎湃实况窗 / 流体云载体）。
 *
 * 纯 AOSP API 实现：specialUse 前台服务 + 常驻 ProgressStyle 通知。
 * 上课铃触发后由 [ClassProgressReceiver] 启动，下课铃/取消路径停止；
 * 服务在进度条每前进一格时更新一次通知（一节课最多 100 次），直到下课自动退出。
 *
 * 设计取舍：
 * - 只有用户开启「课程进行中」时才启动，普通提醒仍走 AlarmManager，不改变省电架构；
 * - 前台服务必须 `startForeground()` 后才能真正开始，因此进入服务先 post 首帧通知；
 * - 启动失败时调用方回退到普通常驻通知（`ReminderNotifications.postClassOngoing`）。
 */
class CourseFluidService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var courseName = ""
    private var location: String? = null
    private var sectionText = ""
    private var startMillis = 0L
    private var endMillis = 0L
    private val updater = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (now < endMillis) {
                postProgressNotification()
                handler.postDelayed(this, nextProgressTickMs(now))
            } else {
                // 下课：移除前台通知并停服；下课铃广播也会再兜底一次
                stopForeground(STOP_FOREGROUND_REMOVE)
                finishLiveAndReschedule()
                stopSelf()
            }
        }
    }

    /**
     * 距离「进度百分比再往前走一格」还有多久。
     *
     * 进度条固定 100 格，所以一节课最多 100 次重贴（此前是每 30 秒无脑重贴一次：
     * 90 分钟课 180 次、其中约 80 次贴的是完全相同的内容）。秒级倒计时由通知自带的
     * chronometer 渲染，不需要应用侧唤醒。
     */
    private fun nextProgressTickMs(now: Long): Long {
        val total = (endMillis - startMillis).coerceAtLeast(1L)
        val elapsed = (now - startMillis).coerceIn(0L, total)
        val progress = (elapsed * 100L) / total
        val nextStepElapsed = ((progress + 1L) * total + 99L) / 100L
        return (startMillis + nextStepElapsed - now).coerceAtLeast(MIN_TICK_DELAY_MS)
    }

    /**
     * 实况自己收尾时恢复勿扰、并续排下一节课的窗口。
     *
     * 下课铃广播通常也会做同一件事（两步都是幂等的），但 ROM 把下课铃吞掉时，
     * 这条路径是唯一的机会：否则勿扰一直挂着，实况也不再排下一节。
     */
    private fun finishLiveAndReschedule() {
        runCatching { ClassProgressDnd.restore(applicationContext) }
        ioScope.launch { ClassProgressScheduler.rescheduleNextWindow(applicationContext) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 进程内标志：供 LiveClassResyncer 判断实况是否已在跑（进程被杀则标志归 false，正确）
        isRunning = true
        courseName = intent?.getStringExtra(EXTRA_COURSE_NAME) ?: "课程"
        location = intent?.getStringExtra(EXTRA_LOCATION)
        sectionText = intent?.getStringExtra(EXTRA_SECTION) ?: ""
        startMillis = intent?.getLongExtra(EXTRA_START, 0L) ?: 0L
        endMillis = intent?.getLongExtra(EXTRA_END, 0L) ?: 0L

        if (startMillis <= 0L || endMillis <= startMillis || endMillis <= System.currentTimeMillis()) {
            // 契约要求：即使马上结束也必须先 startForeground()，否则 Android 12+ 抛异常
            runCatching {
                startForeground(NOTIFY_ID, buildProgressNotification(if (endMillis > startMillis) 100 else 0))
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            finishLiveAndReschedule()
            stopSelf()
            return START_NOT_STICKY
        }

        handler.removeCallbacks(updater)
        postProgressNotification()
        handler.postDelayed(updater, nextProgressTickMs(System.currentTimeMillis()))
        return START_NOT_STICKY
    }

    private fun postProgressNotification() {
        val now = System.currentTimeMillis()
        val total = (endMillis - startMillis).coerceAtLeast(1L)
        val elapsed = (now - startMillis).coerceIn(0L, total)
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
        }
    }

    private fun buildProgressNotification(progress: Int): Notification {
        val meta = listOfNotNull(
            timeRangeOf(startMillis, endMillis),
            location?.takeIf { it.isNotBlank() },
            sectionText.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Android 16+：直接走框架 ProgressStyle，接 tracker 图标 + Segment(100)，
        // 对齐 SleepDown 实测能上澎湃超级岛的写法（R4 P1-2）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            val builder = Notification.Builder(this, ReminderNotifications.CHANNEL_CLASS_PROGRESS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(courseName)
                .setContentText(meta)
                .setStyle(
                    Notification.ProgressStyle()
                        .setProgressTrackerIcon(
                            Icon.createWithResource(this, R.drawable.ic_progress_dot)
                        )
                        .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100)))
                        .setProgress(progress)
                )
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setWhen(endMillis)
                // 倒计时计时器：进度条只在百分比真的前进时刷新，秒级变化靠系统渲染的
                // chronometer（countdown 方向）。ROM（澎湃等）判定「倒计时实况」
                // 也依赖 ongoing + countdown chronometer 这组特征。
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setContentIntent(contentIntent)
            return builder.build().also { notification ->
                // 岛上/胶囊里的紧凑倒计时文案（shortCriticalText 无稳定公开签名，
                // 直接写 extras，SystemUI 在 Android 16+ 自行读取；低版本无副作用）。
                ReminderNotifications.applyPromotedOngoingExtras(
                    notification,
                    ReminderNotifications.countdownLabel(endMillis),
                )
            }
        }

        // 低版本：没有 tracker/segments 语义，继续用兼容 ProgressStyle。
        // 注意不 setSilent：静默通知会被 ROM 的实况/焦点提升逻辑忽略。
        val builder = NotificationCompat.Builder(this, ReminderNotifications.CHANNEL_CLASS_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(courseName)
            .setContentText(meta)
            .setStyle(NotificationCompat.ProgressStyle().setStyledByProgress(true).setProgress(progress))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setWhen(endMillis)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setContentIntent(contentIntent)
        return builder.build().also { notification ->
            ReminderNotifications.applyPromotedOngoingExtras(
                notification,
                ReminderNotifications.countdownLabel(endMillis),
            )
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
        private const val MIN_TICK_DELAY_MS = 1_000L

        /**
         * 进程内「实况是否在跑」标志：onStartCommand 置 true、onDestroy 置 false。
         * 只用于同进程判断（LiveClassResyncer 的状态驱动兜底），跨进程无意义——
         * 进程被杀时标志随之归零，语义仍然正确。
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        const val EXTRA_COURSE_NAME = "extra_course_name"
        const val EXTRA_LOCATION = "extra_location"
        const val EXTRA_SECTION = "extra_section"
        const val EXTRA_START = "extra_start"
        const val EXTRA_END = "extra_end"

        /** 上课铃到下课铃之间的常驻实况：启动前台服务（失败时静默，由调用方回退普通通知） */
        fun start(context: Context, courseName: String, location: String?, sectionText: String, startMillis: Long, endMillis: Long) {
            val intent = Intent(context, CourseFluidService::class.java).apply {
                putExtra(EXTRA_COURSE_NAME, courseName)
                putExtra(EXTRA_LOCATION, location)
                putExtra(EXTRA_SECTION, sectionText)
                putExtra(EXTRA_START, startMillis)
                putExtra(EXTRA_END, endMillis)
            }
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure {
                android.util.Log.w(TAG, "启动课程实况前台服务失败，回退普通常驻通知", it)
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

        private fun timeRangeOf(startMillis: Long, endMillis: Long): String? = runCatching {
            val zone = java.time.ZoneId.systemDefault()
            // Locale 钉死为 US：见 ReminderNotifications 同类说明（避免非 ASCII 数字）
            val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.US)
            val start = java.time.Instant.ofEpochMilli(startMillis).atZone(zone).format(fmt)
            val end = java.time.Instant.ofEpochMilli(endMillis).atZone(zone).format(fmt)
            "$start–$end"
        }.getOrNull()
    }
}