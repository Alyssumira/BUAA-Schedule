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
        notifyCourse(context, courseId, courseName, location, sectionText, classStartAt)

        // 开启“课程进行中”时，课前提醒同时启动一个倒计时实况（流体云），
        // 到上课时刻由 ClassProgressReceiver 继续接管为课程进行中进度。
        val classProgressEnabled = context
            .getSharedPreferences(ClassProgressReceiver.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(ClassProgressReceiver.PREF_CLASS_PROGRESS, true)
        val now = System.currentTimeMillis()
        if (classProgressEnabled && classStartAt > now) {
            CourseFluidService.start(
                context = context,
                courseName = courseName,
                location = location.ifBlank { null },
                sectionText = sectionText,
                startMillis = now,
                endMillis = classStartAt,
            )
        }

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
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, ReminderNotifications.CHANNEL_COURSE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("课程提醒：$courseName")
            .setContentText(buildString {
                if (location.isNotBlank()) append("$location ")
                if (sectionText.isNotBlank()) append(sectionText)
            })
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        // 上课时间已知时用倒计时计时器展示“还有多久上课”
        if (classStartAt > System.currentTimeMillis()) {
            builder.setWhen(classStartAt)
            builder.setUsesChronometer(true)
            builder.setChronometerCountDown(true)
        }

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
