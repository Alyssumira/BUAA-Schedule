package com.buaa.schedule.reminder

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.buaa.schedule.R
import com.buaa.schedule.data.import.SpocSession
import com.buaa.schedule.data.local.AppDatabase
import com.buaa.schedule.data.repository.ScheduleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 与上课铃共用一份 extras 反序列化：这里曾自己摊着读五个 getStringExtra，
        // 于是排程侧新加的教师/周次/课程色在这条链上永远读不到。
        val scheduled = ClassProgressScheduler.ClassWindow.from(intent.extras)
        val classStartAt = scheduled.endMillis
        val now = System.currentTimeMillis()

        // 开启“课程进行中”时，课前提醒同时起一段**倒计时实况**（流体云 / 超级岛载体），
        // 到上课时刻由 ClassProgressReceiver 用同一个通知 id 接手为课程进度。
        // 走 startLiveWindow 而不是直接起服务：服务可能被"后台启动前台服务受限"挡下，
        // 那条路径必须先发同 id 的 promoted 兜底通知，否则课前什么实况都没有——
        // 这正是"课中能上岛、课前倒计时上不了岛"的根因。
        val livePhase = if (
            classProgressEnabled(context) && classStartAt > now
        ) LivePhase.BEFORE_CLASS else null
        // 实况启动与通知展示整体挪到 goAsync() 之后：notifyCourse 里的
        // SpocSession.hasSession() 要过 AndroidKeyStore 解密 + SharedPreferences 读盘，
        // 闹钟唤醒常是冷进程，这笔 binder + 解密全落在广播主线程上就是在吃 10 秒配额，
        // 超配额系统直接掐广播 —— 用户看到的"这一节课没有提醒"就是这么来的。
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                WakeLocks.withPartialWakeLock(context, "reminder_show_and_reschedule") {
                    // 失败只意味着这一节课没有实况/通知，链式重排照旧要跑
                    runCatching {
                        if (livePhase != null) {
                            ReminderNotifications.startLiveWindow(
                                context = context,
                                // 课前这一段进度条量的是"这段等待"，所以起点是此刻而不是上课时间
                                window = scheduled.copy(startMillis = now),
                                phase = livePhase,
                            )
                        }
                        notifyCourse(context, scheduled)
                    }.onFailure { android.util.Log.w(TAG, "课前提醒展示失败（实况/通知）", it) }
                    // 闹钟触发后链式调度下一次提醒；goAsync 保证广播进程存活到调度完成，
                    // 唤醒锁保证 Doze 下 CPU 不会在 DB 查询/重排中途再度入睡（进程活着 ≠ CPU 醒着）
                    runCatching {
                        val repository = ScheduleRepository(AppDatabase.getInstance(context))
                        val semester = repository.getCurrentSemester()
                        val courses = repository.getDisplayCourses(semester)
                        val timeSlots = repository.getTimeSlots()
                        val reminders = repository.getReminders().associateBy { it.courseId }
                        ReminderScheduler.rescheduleAll(context, courses, semester, timeSlots, reminders)
                    }.onFailure { android.util.Log.w(TAG, "提醒触发后重排下一次失败", it) }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    /** 「课程进行中」开关：关掉它就不该有实况窗口，普通课前提醒照发 */
    private fun classProgressEnabled(context: Context): Boolean = context
        .getSharedPreferences(ClassProgressReceiver.PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(ClassProgressReceiver.PREF_CLASS_PROGRESS, true)

    /** 课前提醒是否带「扫码签到」按钮（与设置页同一键） */
    private fun spocSignHintEnabled(context: Context): Boolean = context
        .getSharedPreferences(ClassProgressReceiver.PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(PREF_SPOC_SIGN_HINT, false)

    /**
     * 课前提醒的横幅通知。
     *
     * 折叠那一行是用户在锁屏与状态栏上唯一必看的一句，所以只装"几点上课 + 在哪"，
     * 节次与教师交给 BigText。**这里刻意不放"还有 N 分钟上课"**：
     * 这条通知从下发到被点掉之间没有任何唤醒源会重发它（课前闹钟下次响是这门课下次上课，
     * 上课铃过去也只撤实况那条不同 id 的通知），相对数字会把用户带去迟到的教室。
     * 会走的倒计时归 [CourseFluidService]，文案本身在
     * [courseReminderHeadline] / [courseReminderDetail]（纯函数，可单测）。
     */
    private fun notifyCourse(context: Context, window: ClassProgressScheduler.ClassWindow) {
        // 渠道统一由 ReminderNotifications 创建（分级：课程提醒 / 明日预告）
        ReminderNotifications.ensureChannels(context)
        val pendingIntent = ReminderNotifications.courseReminderLaunchPendingIntent(context)
        val headline = courseReminderHeadline(window)
        val detail = courseReminderDetail(window)

        val builder = NotificationCompat.Builder(context, ReminderNotifications.CHANNEL_COURSE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("课程提醒：${window.courseName}")
            .setContentText(headline)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setShowWhen(false)
            // 与课表卡片同色：着色此前交给系统按应用图标取主色，
            // 于是同一门课在课表上是绿的、在通知栏里是蓝的，扫一眼认不出是哪一节。
            .setColor(window.colorArgb ?: Notification.COLOR_DEFAULT)

        // 这里刻意**不用** `setUsesChronometer` / `setChronometerCountDown`：
        // 会走的倒计时已经归实况载体（每分钟重发一次，岛与胶囊都靠它），
        // 横幅再挂一个系统计时器只会在同屏出现两个各走各的倒计时，
        // 而 chronometer 恰恰是 SleepDown 取证里"顶掉岛上那一格"的形状。
        // 折叠行因此写成绝对时刻（永不过期），而不是靠计时器去救一个快照数字。

        // 开关在这里读，而不是排程时烘进闹钟 extras：闹钟是几十分钟前就排好的，
        // 用户临上课前把这行关掉，烘死的标志仍会让这一节带着按钮。
        // 没登录时不挂：点下去只会跳到扫码页再当场报「还没有登录」，比没有按钮更糟。
        if (spocSignHintEnabled(context) && SpocSession.hasSession()) {
            builder.addAction(0, "扫码签到", ReminderNotifications.spocScanPendingIntent(context))
        }

        try {
            // 用 tag 携带课程 id，而不是 courseId.toInt()：
            // Long→Int 截断会让不同课程的通知共用同一个 id 而互相覆盖。
            NotificationManagerCompat.from(context)
                .notify(notificationTag(window.courseId), NOTIFY_ID_COURSE, builder.build())
        } catch (_: SecurityException) {
            // 用户未授予通知权限，静默忽略。
        }
    }

    companion object {
        private const val TAG = "ReminderReceiver"
        const val CHANNEL_ID = "course_reminder"

        /**
         * 课程提醒的固定通知 id，课程维度由 tag 区分。
         *
         * 公开是因为撤销方是上课铃那条链（[ReminderNotifications.cancelCourseReminder]）：
         * 撤销与下发必须读同一个常量，同值再写一份就是事故 A 的形状。
         */
        const val NOTIFY_ID_COURSE = 20_260_003

        /** 「课前提醒带扫码签到按钮」开关（prefs 走 [ClassProgressReceiver.PREFS_NAME]） */
        const val PREF_SPOC_SIGN_HINT = "spoc_sign_hint"

        /** 通知 tag（纯函数，便于单测） */
        fun notificationTag(courseId: Long): String = "course_$courseId"
    }
}
