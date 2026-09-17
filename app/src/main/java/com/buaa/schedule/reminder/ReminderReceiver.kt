package com.buaa.schedule.reminder

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.buaa.schedule.MainActivity
import com.buaa.schedule.R
import com.buaa.schedule.data.local.AppDatabase
import com.buaa.schedule.data.repository.ScheduleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val courseId = intent.getLongExtra(EXTRA_COURSE_ID, 0L)
        val courseName = intent.getStringExtra(EXTRA_COURSE_NAME) ?: "课程"
        val location = intent.getStringExtra(EXTRA_LOCATION) ?: ""
        val sectionText = intent.getStringExtra(EXTRA_SECTION) ?: ""
        val classStartAt = intent.getLongExtra(EXTRA_CLASS_START_AT, 0L)
        val now = System.currentTimeMillis()

        // 开启“课程进行中”时，课前提醒同时起一段**倒计时实况**（流体云 / 超级岛载体），
        // 到上课时刻由 ClassProgressReceiver 用同一个通知 id 接手为课程进度。
        // 走 startLiveWindow 而不是直接起服务：服务可能被"后台启动前台服务受限"挡下，
        // 那条路径必须先发同 id 的 promoted 兜底通知，否则课前什么实况都没有——
        // 这正是"课中能上岛、课前倒计时上不了岛"的根因。
        val livePhase = if (
            classProgressEnabled(context) && classStartAt > now
        ) LivePhase.BEFORE_CLASS else null
        // 这两步跑在 goAsync() 之前、在接收器的主线程上：任何逃出的异常都会直接杀进程
        // （与 ClassProgressReceiver 同口径，那边早已整体兜住）。这里失败只意味着这一节课
        // 没有实况/通知，链式重排照旧要跑。
        runCatching {
            if (livePhase != null) {
                ReminderNotifications.startLiveWindow(
                    context = context,
                    courseId = courseId,
                    courseName = courseName,
                    location = location.ifBlank { null },
                    sectionText = sectionText,
                    startMillis = now,
                    endMillis = classStartAt,
                    phase = livePhase,
                )
            }
            notifyCourse(context, courseId, courseName, location, sectionText, classStartAt)
        }.onFailure { android.util.Log.w(TAG, "课前提醒展示失败（实况/通知）", it) }

        // 闹钟触发后链式调度下一次提醒；goAsync 保证广播进程存活到调度完成，
        // 唤醒锁保证 Doze 下 CPU 不会在 DB 查询/重排中途再度入睡（进程活着 ≠ CPU 醒着）
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                WakeLocks.withPartialWakeLock(context, "reminder_reschedule") {
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

    private fun notifyCourse(
        context: Context,
        courseId: Long,
        courseName: String,
        location: String,
        sectionText: String,
        classStartAt: Long,
    ) {
        // 渠道统一由 ReminderNotifications 创建（分级：课程提醒 / 明日预告）
        ReminderNotifications.ensureChannels(context)
        val pendingIntent = ReminderNotifications.courseReminderLaunchPendingIntent(context)
        val now = System.currentTimeMillis()

        val builder = NotificationCompat.Builder(context, ReminderNotifications.CHANNEL_COURSE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("课程提醒：$courseName")
            .setContentText(buildString {
                if (location.isNotBlank()) append("$location ")
                if (sectionText.isNotBlank()) append("$sectionText ")
                // 横幅自己带一句静态倒计时：用户不展开实况也能知道大概还剩多久
                if (classStartAt > now) append(liveCountdownLine(LivePhase.BEFORE_CLASS, classStartAt, now))
            }.trim())
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setShowWhen(false)

        // 这里刻意**不用** `setUsesChronometer` / `setChronometerCountDown`：
        // 会走的倒计时已经归实况载体（每分钟重发一次，岛与胶囊都靠它），
        // 横幅再挂一个系统计时器只会在同屏出现两个各走各的倒计时，
        // 而 chronometer 恰恰是 SleepDown 取证里"顶掉岛上那一格"的形状。

        try {
            // 用 tag 携带课程 id，而不是 courseId.toInt()：
            // Long→Int 截断会让不同课程的通知共用同一个 id 而互相覆盖。
            NotificationManagerCompat.from(context)
                .notify(notificationTag(courseId), NOTIFY_ID_COURSE, builder.build())
        } catch (_: SecurityException) {
            // 用户未授予通知权限，静默忽略。
        }
    }

    companion object {
        private const val TAG = "ReminderReceiver"
        const val CHANNEL_ID = "course_reminder"
        const val EXTRA_COURSE_ID = "extra_course_id"
        const val EXTRA_COURSE_NAME = "extra_course_name"
        const val EXTRA_LOCATION = "extra_location"
        const val EXTRA_SECTION = "extra_section"
        const val EXTRA_CLASS_START_AT = "extra_class_start_at"

        /** 课程提醒的固定通知 id，课程维度由 tag 区分 */
        private const val NOTIFY_ID_COURSE = 20_260_003

        /** 通知 tag（纯函数，便于单测） */
        fun notificationTag(courseId: Long): String = "course_$courseId"
    }
}
