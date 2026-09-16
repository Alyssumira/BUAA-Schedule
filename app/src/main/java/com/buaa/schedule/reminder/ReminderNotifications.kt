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

    /** 实况提升诊断日志的 tag：`adb logcat -s BUAA-LiveUpdate` 只看这条链 */
    private const val LIVE_TAG = "BUAA-LiveUpdate"

    /**
     * 拉起一段课程实况：**先**发同 id 的 promoted 兜底通知，**再**交棒给前台服务。
     *
     * 三个入口（课前提醒广播、上课铃广播、进前台状态校准）以前各写各的：只有上课铃
     * 那条按这个顺序做，课前那条只 `CourseFluidService.start()` 就完事。于是服务
     * 起不来时（后台启动前台服务受限、ROM 省电拦截）课前什么实况都没有，
     * 屏幕上只剩同一条路径发的、带 chronometer 的普通提醒——而 chronometer
     * 恰好是会**顶掉岛上那一格**的形状（见 [postClassOngoing] 的取证）。
     * 用户看到的现象就是"课中能上岛、课前倒计时上不了岛"。
     *
     * 顺序不能反：服务启动成功后会以同一通知 id 覆盖这条兜底，反过来则可能出现
     * 服务已出进度、又被兜底的静态版本盖回去。
     */
    fun startLiveWindow(
        context: Context,
        courseId: Long,
        courseName: String,
        location: String?,
        sectionText: String,
        startMillis: Long,
        endMillis: Long,
        phase: LivePhase,
    ) {
        ensureChannels(context)
        postClassOngoing(
            context = context,
            courseId = courseId,
            courseName = courseName,
            location = location,
            sectionText = sectionText,
            startMillis = startMillis,
            endMillis = endMillis,
            phase = phase,
        )
        CourseFluidService.start(
            context = context,
            courseId = courseId,
            courseName = courseName,
            location = location,
            sectionText = sectionText,
            startMillis = startMillis,
            endMillis = endMillis,
            phase = phase,
        )
    }

    /**
     * 课程实况的常驻通知（课前倒计时与课中进度共用一条形状）。
     *
     * 通知的标题/文本只在下发时写入一次，之后不会自己刷新，因此这里**不下发进度条**：
     * 之前用 `setProgress(now/总时长)` 只在开课瞬间求值一次，进度条会永久停在约 0%。
     * 倒计时文案同样只在下发时求值，所以这条只是"服务起不来时的兜底常驻"，
     * 真正每分钟刷新的是 [CourseFluidService]（它才是实况载体）。
     * Android 16+ 通过 `setRequestPromotedOngoing` 请求提升为实况窗/流体云样式。
     *
     * **不用 `setUsesChronometer` / `setChronometerCountDown`**：SleepDown 源码里明确写着
     * 系统渲染的 chronometer 会**顶掉 promoted chip**（也就是岛上那一格），它因此改为
     * 应用侧对齐整分钟重发。我们此前反过来依赖 chronometer，正是"通知在下拉栏里、
     * 就是不上岛"的形状差异。相应地这里 `setShowWhen(false)`，不给状态栏留时间戳。
     *
     * 点按落到这节课的编辑页（[courseLaunchPendingIntent]）。
     */
    fun postClassOngoing(
        context: Context,
        courseId: Long,
        courseName: String,
        location: String?,
        sectionText: String,
        startMillis: Long,
        endMillis: Long,
        phase: LivePhase,
    ) {
        ensureChannels(context)
        val body = liveBody(sectionText, startMillis, endMillis, location, phase, System.currentTimeMillis())
        val chip = chipCountdownLabel(endMillis)

        val builder = NotificationCompat.Builder(context, CHANNEL_CLASS_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(courseName)
            .setContentText(body)
            // 两行正文必须配 BigText：默认折叠样式只渲染一行，第二行的倒计时会被整个吃掉，
            // 卡片"简陋"的一半原因是信息根本没地方显示（对齐 SleepDown 的 BigText 基座）
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(courseLaunchPendingIntent(context, courseId))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            // 不 setSilent：静默通知会被 ROM 的实况/焦点提升逻辑忽略（对齐 SleepDown）。
            // 广播这条与前台服务那条共用同一通知 id，setOnlyAlertOnce 保证只响第一声。
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            // SleepDown 实况形状里我们唯一没跟的一项：COLOR_DEFAULT = 交给渲染方按
            // 应用图标取主色，而不是把这条当成"没有着色的普通通知"。
            .setColor(Notification.COLOR_DEFAULT)
        // androidx.core 1.17+：请求系统把这条常驻通知提升为「实况窗/流体云」样式
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            runCatching { builder.setRequestPromotedOngoing(true) }
        }
        // 小字走真正的 setter（对齐 SleepDown），applyPromotedOngoingExtras 里的 extras 只是兜底
        applyShortCriticalText(builder, chip)
        try {
            val notification = builder.build()
            applyPromotedOngoingExtras(notification, chip)
            logPromotionShape("postClassOngoing", notification)
            // 小米专属、默认关闭的自定义岛内容；内部全程 runCatching
            IslandFocusTemplate.attach(
                context,
                notification,
                NOTIFY_ID_CLASS_PROGRESS,
                courseId,
                courseName,
                sectionText,
                startMillis,
            )
            NotificationManagerCompat.from(context).notify(NOTIFY_ID_CLASS_PROGRESS, notification)
        } catch (_: SecurityException) {
        }
    }

    /**
     * 点通知（以及由它渲染出的超级岛 / 实况窗）→ 打开这一节课。
     *
     * 键必须是 [MainActivity.EXTRA_COURSE_ID]：MainActivity 的 onCreate / onNewIntent
     * 只认这一个键，写别的名字等于"点了没反应"。取不到课程 id（<= 0）时不放 extra，
     * 退回首屏，而不是打开一个查不到课程的空编辑器。
     *
     * requestCode 固定 0 是安全的：屏幕上同一时刻只有一条课堂实况，
     * FLAG_UPDATE_CURRENT 会把 extra 刷成当前这节课。
     * （组件那边必须区分 requestCode，因为列表行与头部点击要并存。）
     */
    fun courseLaunchPendingIntent(context: Context, courseId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            if (courseId > 0L) putExtra(MainActivity.EXTRA_COURSE_ID, courseId)
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Android 16+ promoted ongoing（实况窗 / 流体云 / 超级岛）的双保险 extras。
     *
     * - `android.requestPromotedOngoing`：与 `setRequestPromotedOngoing(true)` 等价，
     *   直接写 extras 兜底，防 androidx 兼容层在个别版本上漏写；
     * - `android.shortCriticalText`：岛/胶囊里展示的紧凑文案（如「38分钟」）。
     *   extras 只是**兜底**：小字应当由 [applyShortCriticalText] 走真正的 setter 写入，
     *   因为框架的读回口是 [shortCriticalTextOf]（`Notification.getShortCriticalText()`），
     *   我们此前只写 extras、从没调用过 setter。
     */
    fun applyPromotedOngoingExtras(notification: Notification, shortText: CharSequence) {
        notification.extras.putBoolean("android.requestPromotedOngoing", true)
        notification.extras.putCharSequence("android.shortCriticalText", shortText)
    }

    /** androidx 路径：`NotificationCompat.Builder.setShortCriticalText(String)` 是公开 API。 */
    fun applyShortCriticalText(builder: NotificationCompat.Builder, shortText: CharSequence) {
        runCatching { builder.setShortCriticalText(shortText.toString()) }
    }

    /**
     * 框架路径：SleepDown 对同一个 setter 做 `CharSequence` / `String` 双签名反射
     * （原文："Android's promoted chip API is String on some releases and CharSequence on
     * newer releases"），故两个签名都试一次，优先 `CharSequence`。
     */
    fun applyShortCriticalText(builder: Notification.Builder, shortText: CharSequence) {
        runCatching {
            builder.javaClass.getMethod("setShortCriticalText", CharSequence::class.java)
                .invoke(builder, shortText)
        }.recoverCatching {
            builder.javaClass.getMethod("setShortCriticalText", String::class.java)
                .invoke(builder, shortText.toString())
        }
    }

    /**
     * 框架自己读回的小字；null 表示这台机器没有该 getter（低于 Android 12）或没写进去。
     *
     * 用它区分两种"岛上是 ROM 通用文案「进行中」"的解释：extras 与 setter 等价（读得到），
     * 还是 SystemUI 只认 setter 写的那一份（读不到）。
     */
    fun shortCriticalTextOf(notification: Notification): String? {
        if (Build.VERSION.SDK_INT < 31) return null
        return runCatching {
            notification.javaClass.getMethod("getShortCriticalText").invoke(notification) as? String
        }.getOrNull()
    }

    /**
     * 这台机器的框架 `Notification.Builder` 上 `setShortCriticalText` 的真实签名。
     *
     * 只查类、不建实例，因此可以在自检里顺带验证真课表那条分支的反射调用能不能落到方法上
     * （`CourseFluidService` 的前台服务未导出，adb 起不动，没有第二个办法在真机上走那条路）。
     */
    fun frameworkChipSignature(): String? {
        if (Build.VERSION.SDK_INT < 31) return null
        val builder = Notification.Builder::class.java
        return when {
            runCatching { builder.getMethod("setShortCriticalText", CharSequence::class.java) }
                .isSuccess -> "CharSequence"
            runCatching { builder.getMethod("setShortCriticalText", String::class.java) }
                .isSuccess -> "String"
            else -> null
        }
    }

    /**
     * 框架对"这条通知有没有资格被提升为实况"的自答；null 表示这台机器没有该方法
     * （低于 Android 16）。它是 `Notification` 上的公开 API，但 android-36 的 jar 里没有
     * `isRequestPromotedOngoing()`，故走反射、拿不到就记 null 而不是猜。
     */
    fun promotableCharacteristics(notification: Notification): Boolean? = runCatching {
        notification.javaClass.getMethod("hasPromotableCharacteristics")
            .invoke(notification) as? Boolean
    }.getOrNull()

    /**
     * 把「平台自己怎么看这条实况」算成一句话，既打日志也**返回给调用方显示**。
     *
     * [promotableCharacteristics] 返回 false 时再去改 ROM 白名单、载荷格式都是白费。
     * 必须返回值而不是只写 logcat：真机核实这台 HyperOS 会把第三方应用的 logcat
     * 整条吞掉（`logcat --pid` 读到 0 行），只看日志的诊断等于没有诊断。
     */
    fun logPromotionShape(source: String, notification: Notification): String {
        val promotable = promotableCharacteristics(notification)
        val requested = runCatching {
            notification.javaClass.getMethod("isRequestPromotedOngoing")
                .invoke(notification) as? Boolean
        }.getOrNull() ?: notification.extras.getBoolean("android.requestPromotedOngoing", false)
        // `when` 是 Kotlin 关键字，而框架只把它暴露成属性（getWhen() 不可直接调用），
        // 只能反引号转义取出来。
        val whenAt = notification.`when`
        val verdict = "$source: promotable=$promotable requested=$requested " +
            "flags=${notification.flags} style=${notification.extras.getString("android.template")} " +
            "chip=${notification.extras.getCharSequence("android.shortCriticalText")} " +
            "chipField=${shortCriticalTextOf(notification)} " +
            "frameworkChipSetter=${frameworkChipSignature()} " +
            "when=$whenAt " +
            "chronometer=${notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)}" +
            "/countDown=${notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)}"
        android.util.Log.d(LIVE_TAG, verdict)
        return verdict
    }

    /** 距下课的倒计时文案，用于通知正文（向上取整到分钟）。 */
    fun countdownLabel(endMillis: Long): String {
        val minutes = minutesLeft(endMillis, System.currentTimeMillis())
        return if (minutes <= 0L) "即将下课" else "还有 $minutes 分钟下课"
    }

    /**
     * 岛/胶囊上的紧凑小字（`shortCriticalText`）。
     *
     * 刻意对齐 SleepDown 的纯文本「N分钟」：官方渲染器对带 span 的小字会整段丢弃或不保留
     * 样式（其源码注释里写明这曾让倒计时"渲染不正常"），且这一格宽度很窄，
     * 「还有 N 分钟下课」这种完整句式在这里只会变成省略号。
     */
    fun chipCountdownLabel(endMillis: Long): String =
        "${minutesLeft(endMillis, System.currentTimeMillis())}分钟"

    fun cancelClassOngoing(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFY_ID_CLASS_PROGRESS)
    }
}

/**
 * 课程实况现在数到哪：课前倒计到上课铃，课中倒计到下课铃。
 *
 * 两段的载体（同一条通知 id、同一套 promoted 形状）**必须完全一致**，只差文案；
 * 课前那段以前是另发一条带 chronometer 的普通提醒，因此永远提不上岛。
 */
enum class LivePhase { BEFORE_CLASS, IN_CLASS }

/**
 * 还剩多少分钟：向上取整。
 *
 * 这个口径被 [nextCourseFluidTickMs] 用来算"下一次小字翻转还要等多久"，
 * 两处必须一致，否则岛上的数字会晚一分钟才跳。
 */
internal fun minutesLeft(endMillis: Long, nowMillis: Long): Long =
    ((endMillis - nowMillis + 59_999L) / 60_000L).coerceAtLeast(0L)

/** 卡片第一行：节次 · 时间区间 · 地点。缺项整段跳过，绝不留悬空的 " · " */
internal fun liveMetaLine(sectionText: String, timeRange: String?, location: String?): String =
    listOfNotNull(
        sectionText.takeIf { it.isNotBlank() },
        timeRange?.takeIf { it.isNotBlank() },
        location?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

/** 卡片第二行：这一段里唯一自己会变的数字，所以它值得单独占一行 */
internal fun liveCountdownLine(phase: LivePhase, endMillis: Long, nowMillis: Long): String {
    val minutes = minutesLeft(endMillis, nowMillis)
    if (minutes > 0L) {
        return "还有 $minutes 分钟${if (phase == LivePhase.BEFORE_CLASS) "上课" else "下课"}"
    }
    return if (phase == LivePhase.BEFORE_CLASS) "马上上课" else "即将下课"
}

/** 实况正文两行：静态的课次信息 + 会走的倒计时 */
internal fun liveBody(
    sectionText: String,
    startMillis: Long,
    endMillis: Long,
    location: String?,
    phase: LivePhase,
    nowMillis: Long,
): String = liveMetaLine(sectionText, liveTimeRange(startMillis, endMillis), location) +
    "\n" + liveCountdownLine(phase, endMillis, nowMillis)

/** 起止时间区间（HH:mm–HH:mm）；时钟异常等解析失败时返回 null，正文少一段而不是整个发不出去 */
internal fun liveTimeRange(startMillis: Long, endMillis: Long): String? = runCatching {
    val zone = java.time.ZoneId.systemDefault()
    // 必须钉死 Locale.US：默认 locale 的 DecimalStyle 在部分语言下会输出
    // 非 ASCII 数字（如 ar / fa 的 ٠١٢），而这里是给通知与时间戳用的，
    // 一旦变成非 ASCII 数字，下游解析与展示都会出错。
    val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.US)
    val start = java.time.Instant.ofEpochMilli(startMillis).atZone(zone).format(fmt)
    val end = java.time.Instant.ofEpochMilli(endMillis).atZone(zone).format(fmt)
    "$start–$end"
}.getOrNull()
