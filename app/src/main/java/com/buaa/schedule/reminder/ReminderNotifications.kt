package com.buaa.schedule.reminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.buaa.schedule.MainActivity
import com.buaa.schedule.R
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.periodLabel
import java.time.LocalDate

/**
 * 通知渠道与通知发布的统一入口。
 *
 * 此前只有一个渠道（"课程提醒"），用户无法单独关闭某类通知；
 * 现在按功能分级，用户可以在系统设置里逐类控制。
 */
object ReminderNotifications {

    /** 课程提醒（高优先级：横幅 + 声音） */
    const val CHANNEL_COURSE = "course_reminder"

    /** 明日课程预告（默认优先级：每天 22:00 一条概览） */
    const val CHANNEL_TOMORROW = "tomorrow_preview"

    /**
     * 课程进行中（常驻，实时同步课程进度）。
     *
     * **v2**：原渠道 `class_progress` 创建时用了 IMPORTANCE_LOW，而渠道重要性
     * 一旦创建就无法由程序修改。低优先级 + 静默的组合最容易被 ROM 的
     * 实况/焦点通知提升逻辑忽略（澎湃 OS 上尤其明显）。换新 id 以
     * DEFAULT 重新创建，旧渠道在 ensureChannels 里删除。
     *
     * **通知级 setSilent 已移除（2026-09-15）**：对照 SleepDown（实测能上澎湃
     * 超级岛的同类课表 App）取证，其通知从不 setSilent，只靠 setOnlyAlertOnce
     * 抑制重复响声——静默标记会让实况提升逻辑忽略该通知。本渠道保持
     * DEFAULT + 默认提示音，与 SleepDown 一致：首帧响一声属预期行为。
     */
    const val CHANNEL_CLASS_PROGRESS = "class_progress_v2"

    private data class ChannelSpec(
        val id: String,
        val name: String,
        val description: String,
        val importance: Int,
    )

    private val CHANNEL_SPECS = listOf(
        ChannelSpec(CHANNEL_COURSE, "课程提醒", "上课前的提醒与倒计时", NotificationManager.IMPORTANCE_HIGH),
        ChannelSpec(CHANNEL_TOMORROW, "明日课程预告", "每天晚上推送第二天的课程概览", NotificationManager.IMPORTANCE_DEFAULT),
        ChannelSpec(CHANNEL_CLASS_PROGRESS, "课程进行中", "上课期间常驻的课程进度", NotificationManager.IMPORTANCE_DEFAULT),
    )

    /** 幂等创建全部渠道（渠道一旦创建，重要性只能由用户在系统设置里改） */
    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // 旧版「课程进行中」渠道为 IMPORTANCE_LOW，重要性无法程序修改，直接删除。
        // 用户在系统设置里会看到旧条目消失、新条目（同名）出现。
        manager.deleteNotificationChannel("class_progress")
        CHANNEL_SPECS.forEach { (id, name, description, importance) ->
            val channel = NotificationChannel(id, name, importance).apply {
                this.description = description
            }
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * 当前是否真的能发通知：先看系统总开关；再看渠道。
     *
     * @param channelId 指定渠道时只判断该渠道是否被用户关掉；
     *   不指定时判断「是否所有渠道都被关掉」——只要还有一个渠道能发，就认为通知仍可用，
     *   避免设置页把“只关了某一个渠道”误报成“通知权限整体关闭”（R4 P2-1）。
     */
    fun canPostNotifications(context: Context, channelId: String? = null): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false
        val ids = channelId?.let { listOf(it) } ?: CHANNEL_SPECS.map { it.id }
        val allChannelsDisabled = ids.all { id ->
            manager.getNotificationChannel(id)?.importance == NotificationManager.IMPORTANCE_NONE
        }
        return !allChannelsDisabled
    }

    /**
     * 明日课程预告：一条概览通知。
     * 渠道 importance=DEFAULT，不抢横幅，用户想看的时候能看全。
     */
    fun postTomorrowPreview(context: Context, courses: List<Course>, date: LocalDate, timeSlots: List<TimeSlot>) {
        ensureChannels(context)
        val text = buildTomorrowPreviewText(courses, date, timeSlots)
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_TOMORROW)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("明天的课程（${date.monthValue}月${date.dayOfMonth}日）")
            .setContentText(text.lineSequence().firstOrNull() ?: "明天没有课")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
        try {
            // 同一天重复触发时覆盖旧通知
            NotificationManagerCompat.from(context).notify(NOTIFY_ID_TOMORROW, builder.build())
        } catch (_: SecurityException) {
            // 用户未授予通知权限
        }
    }

    /** 预览正文（纯函数，可单测）：按节次排序，每行「08:00 高等数学 · SH3-101」 */
    fun buildTomorrowPreviewText(
        courses: List<Course>,
        date: LocalDate,
        timeSlots: List<TimeSlot>,
    ): String {
        if (courses.isEmpty()) return "明天没有课"
        val slots = if (timeSlots.isNotEmpty()) timeSlots else com.buaa.schedule.domain.model.TimeSlotProfile.DEFAULT
        return courses
            .sortedBy { it.startPeriod }
            .joinToString("\n") { course ->
                val startTime = slots.firstOrNull { it.number == course.startPeriod }?.startTime ?: ""
                buildString {
                    if (startTime.isNotBlank()) append("$startTime ")
                    append(course.name)
                    course.location?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                    append(" · ").append(periodLabel(course.periods))
                }
            }
    }

    private const val NOTIFY_ID_TOMORROW = 20_260_001

    /** 课程进行中常驻通知的固定 id（下课铃撤的就是它） */
    private const val NOTIFY_ID_CLASS_PROGRESS = 20_260_002

    /**
     * 课程进行中常驻通知。
     *
     * 通知的标题/文本只在下发时写入一次，之后不会自己刷新，因此这里**不下发进度条**：
     * 之前用 `setProgress(now/总时长)` 只在开课瞬间求值一次，进度条会永久停在约 0%，
     * 与"实时同步课程进度"的注释不符。真正实时的是系统渲染的倒计时
     * （`setUsesChronometer` + `setChronometerCountDown`），并额外用起止时间表达进度区间。
     * Android 16+ 通过 `setRequestPromotedOngoing` 请求提升为实况窗/流体云样式。
     *
     * 不引入周期任务重发通知，是为了守住"无后台轮询"的耗电约束。
     */
    fun postClassOngoing(
        context: Context,
        courseId: Long,
        courseName: String,
        location: String?,
        sectionText: String,
        startMillis: Long,
        endMillis: Long,
    ) {
        ensureChannels(context)
        val meta = listOfNotNull(
            timeRangeOf(startMillis, endMillis),
            location?.takeIf { it.isNotBlank() },
            sectionText.takeIf { it.isNotBlank() },
        ).joinToString(" · ")

        val builder = NotificationCompat.Builder(context, CHANNEL_CLASS_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(courseName)
            .setContentText(meta)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            // 不 setSilent：静默通知会被 ROM 的实况/焦点提升逻辑忽略（对齐 SleepDown）。
            // 广播这条与前台服务那条共用同一通知 id，setOnlyAlertOnce 保证只响第一声。
            .setOnlyAlertOnce(true)
            .setWhen(endMillis)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
        // androidx.core 1.17+：请求系统把这条常驻通知提升为「实况窗/流体云」样式
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            runCatching { builder.setRequestPromotedOngoing(true) }
        }
        try {
            val notification = builder.build()
            applyPromotedOngoingExtras(notification, countdownLabel(endMillis))
            NotificationManagerCompat.from(context).notify(NOTIFY_ID_CLASS_PROGRESS, notification)
        } catch (_: SecurityException) {
        }
    }

    /**
     * Android 16+ promoted ongoing（实况窗 / 流体云 / 超级岛）的双保险 extras。
     *
     * - `android.requestPromotedOngoing`：与 `setRequestPromotedOngoing(true)` 等价，
     *   直接写 extras 兜底，防 androidx 兼容层在个别版本上漏写；
     * - `android.shortCriticalText`：岛/胶囊里展示的紧凑文案（如「还有 8 分钟下课」）。
     *   该 setter 没有跨版本稳定的公开签名（String / CharSequence 并存，SleepDown 用
     *   双签名反射也印证了这点），参照其做法直接写 extras，SystemUI 自行读取；
     *   Android 16 以下这些 extras 无人消费，写入无副作用。
     */
    fun applyPromotedOngoingExtras(notification: Notification, shortText: CharSequence) {
        notification.extras.putBoolean("android.requestPromotedOngoing", true)
        notification.extras.putCharSequence("android.shortCriticalText", shortText)
    }

    /** 距下课的紧凑倒计时文案，用于实况样式的小字（向上取整到分钟）。 */
    fun countdownLabel(endMillis: Long): String {
        val minutesLeft = ((endMillis - System.currentTimeMillis() + 59_999L) / 60_000L).coerceAtLeast(0L)
        return if (minutesLeft <= 0L) "即将下课" else "还有 ${minutesLeft} 分钟下课"
    }

    /** 起止时间区间文案（HH:mm–HH:mm）；解析失败返回 null，不影响通知下发 */
    private fun timeRangeOf(startMillis: Long, endMillis: Long): String? = runCatching {
        val zone = java.time.ZoneId.systemDefault()
        // 必须钉死 Locale.US：默认 locale 的 DecimalStyle 在部分语言下会输出
        // 非 ASCII 数字（如 ar / fa 的 ٠١٢），而这里是给通知与时间戳用的，
        // 一旦变成非 ASCII 数字，下游解析与展示都会出错。
        val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.US)
        val start = java.time.Instant.ofEpochMilli(startMillis).atZone(zone).format(fmt)
        val end = java.time.Instant.ofEpochMilli(endMillis).atZone(zone).format(fmt)
        "$start–$end"
    }.getOrNull()

    fun cancelClassOngoing(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFY_ID_CLASS_PROGRESS)
    }
}
