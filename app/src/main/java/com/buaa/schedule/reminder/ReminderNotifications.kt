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
import androidx.core.content.edit
import androidx.core.net.toUri
import com.buaa.schedule.MainActivity
import com.buaa.schedule.R
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.periodLabelOf
import com.buaa.schedule.domain.model.weekdayLabel
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

    /** 旧版「课程进行中」渠道 id：IMPORTANCE_LOW，只能删掉重建（见 [CHANNEL_CLASS_PROGRESS]） */
    private const val LEGACY_CHANNEL_CLASS_PROGRESS = "class_progress"

    /** 上面那条迁移只做一次用的标志位（与开关设置同一份 prefs） */
    private const val KEY_LEGACY_CHANNEL_DELETED = "legacy_class_progress_channel_deleted"

    /** 幂等创建全部渠道（渠道一旦创建，重要性只能由用户在系统设置里改） */
    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val prefs = context.getSharedPreferences(ClassProgressReceiver.PREFS_NAME, Context.MODE_PRIVATE)
        // 旧版「课程进行中」渠道为 IMPORTANCE_LOW，重要性无法程序修改，直接删除。
        // 用户在系统设置里会看到旧条目消失、新条目（同名）出现。
        // 这是一次性迁移，不是每轮的常规动作：ensureChannels 在**每条通知下发前**都会跑，
        // 无条件删一个早已不存在的渠道等于每次发通知都白送一次 binder 调用 + 一条系统告警。
        if (!prefs.getBoolean(KEY_LEGACY_CHANNEL_DELETED, false)) {
            manager.deleteNotificationChannel(LEGACY_CHANNEL_CLASS_PROGRESS)
            // apply（非 commit）：这条路径会在主线程发通知时走到，同步落盘等于主线程磁盘 I/O。
            // 标志位万一丢失的最坏后果只是"下次再多删一次不存在的渠道"，无损正确性。
            prefs.edit { putBoolean(KEY_LEGACY_CHANNEL_DELETED, true) }
        }
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
        val pendingIntent = launchActivityPendingIntent(context, REQUEST_TOMORROW_PREVIEW)
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
                    append(course.displayName)
                    course.location?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                    append(" · ").append(periodLabelOf(course.periods, slots))
                }
            }
    }

    private const val NOTIFY_ID_TOMORROW = 20_260_001

    /** 课程进行中常驻通知的固定 id（下课铃撤的就是它） */
    private const val NOTIFY_ID_CLASS_PROGRESS = 20_260_002

    /**
     * 通知内容意图的 requestCode —— **每一族一个码段**，不能都写 0。
     *
     * PendingIntent 的判等只看 `(requestCode, Intent.filterEquals)`，而 `filterEquals`
     * **不含 extras**：三族都指向 MainActivity、都用码 0 时，系统在它们之间看到的是
     * 同一个待处理意图，`FLAG_UPDATE_CURRENT` 让后发的那族把先发的整份覆盖掉 ——
     * 用户点"下一节课要上"拿到的是课堂实况的目标（或一个没带课程 id 的空启动），
     * 落到 MainActivity 就是"点了没反应"。码段之外还各带一个 `data`，见
     * [launchActivityPendingIntent]。
     *
     * 每族只用**一个**固定码（不按 courseId 展开）是刻意的：同一族屏幕上同一时刻
     * 只有一条，码随课程 id 增长会在系统里攒出无法回收的 PendingIntent。
     */
    private const val REQUEST_COURSE_REMINDER = 310_000
    private const val REQUEST_TOMORROW_PREVIEW = 320_000
    private const val REQUEST_CLASS_LIVE = 300_000
    private const val REQUEST_SPOC_SCAN = 330_000

    /**
     * 课前倒计时正在数的那节课（0 = 没有）。进程内状态，与 [CourseFluidService.isRunning]
     * 同一套路：进程被杀即归零，语义仍然正确 —— 课中实况由状态驱动校准补起，
     * 课前那一段则等下一次课前提醒重新下发。
     */
    @Volatile
    internal var countdownCourseId = 0L

    /** [countdownCourseId] 对应的上课绝对时刻（毫秒） */
    @Volatile
    internal var countdownClassStart = 0L

    /**
     * 屏上的实况是不是"正在数这节即将到来的课"。
     *
     * [ClassProgressScheduler.rescheduleWindows] 与 [LiveClassResyncer] 共用的判据：
     * 二者都会把"课还没开始"当成下课铃被吞的遗留来收，少了这一步区分，
     * 课前倒计时刚上岛就会被拆掉。课程 id 与开课毫秒必须同时对上、
     * 且课还没开始才算数 —— 课被删或改时间后重排自然对不上，照常回收。
     */
    fun isCountingDownTo(courseId: Long, classStartMillis: Long): Boolean =
        countdownCourseId == courseId &&
            countdownClassStart == classStartMillis &&
            classStartMillis > System.currentTimeMillis()

    /**
     * 课前倒计时的**归属**是否还挂在这节课上 —— [isCountingDownTo] 去掉时间那一判。
     *
     * 调用场景是"倒计时数到上课铃的那一秒"：此刻 `classStart > now` 必然为假，
     * 用 [isCountingDownTo] 判"上课铃到没到"会得到永远相同的答案，等于没有守卫。
     * 归属是清得掉的（ACTION_START 以 IN_CLASS 重新下发实况时归零），所以只有它能区分
     * "广播处理过了"与"广播被省电策略吞了"。
     */
    internal fun ownsCountdownTo(courseId: Long, classStartMillis: Long): Boolean =
        countdownCourseId == courseId && countdownClassStart == classStartMillis

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
        window: ClassProgressScheduler.ClassWindow,
        phase: LivePhase,
    ) {
        ensureChannels(context)
        // 先记归属再下发：紧随其后的重排链（ReminderReceiver 的 goAsync 块）
        // 要靠这份状态区分"课前倒计时"和"下课铃被吞的遗留"。
        if (phase == LivePhase.BEFORE_CLASS) {
            countdownCourseId = window.courseId
            countdownClassStart = window.endMillis
        } else {
            countdownCourseId = 0L
            countdownClassStart = 0L
        }
        postClassOngoing(context, window, phase)
        CourseFluidService.start(context, window, phase)
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
        window: ClassProgressScheduler.ClassWindow,
        phase: LivePhase,
    ) {
        ensureChannels(context)
        val now = System.currentTimeMillis()
        val body = liveBody(
            window.sectionText, window.startMillis, window.endMillis, window.location, phase, now,
        )
        val chip = chipCountdownLabel(window.endMillis)
        val accent = window.colorArgb ?: Notification.COLOR_DEFAULT

        val builder = NotificationCompat.Builder(context, CHANNEL_CLASS_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(window.courseName)
            .setContentText(body)
            // 标题右侧那一格一直没被用过：周次、星期、教师三项都在数据里，
            // 但两行正文的位置已经被"节次 · 时间 · 地点"和倒计时占满，
            // 硬塞只会把最该看的数字挤掉。subText 正是给这类辅助标识留的槽位。
            .setSubText(liveSubText(window.week, window.dayOfWeek, window.teacher))
            // 两行正文必须配 BigText：默认折叠样式只渲染一行，第二行的倒计时会被整个吃掉，
            // 卡片"简陋"的一半原因是信息根本没地方显示（对齐 SleepDown 的 BigText 基座）
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(courseLaunchPendingIntent(context, window.courseId))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            // 不 setSilent：静默通知会被 ROM 的实况/焦点提升逻辑忽略（对齐 SleepDown）。
            // 广播这条与前台服务那条共用同一通知 id，setOnlyAlertOnce 保证只响第一声。
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            // 课程色与课表卡片一致；拿不到颜色时才交回 COLOR_DEFAULT
            // （= 让渲染方按应用图标取主色，而不是"这条没有着色"）
            .setColor(accent)
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
            IslandFocusTemplate.attach(context, notification, NOTIFY_ID_CLASS_PROGRESS, window)
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
     * 走课堂实况码段：屏幕上同一时刻只有一条实况，族内固定一个码即可（见
     * [REQUEST_CLASS_LIVE] 上那段说明），但**不能**再和课前提醒、明日预告共用 0。
     */
    fun courseLaunchPendingIntent(context: Context, courseId: Long): PendingIntent =
        launchActivityPendingIntent(context, REQUEST_CLASS_LIVE, courseId)

    /** 课前提醒通知的内容意图：打开应用即可，具体是哪门课已由通知正文给出，不跳编辑器 */
    fun courseReminderLaunchPendingIntent(context: Context): PendingIntent =
        launchActivityPendingIntent(context, REQUEST_COURSE_REMINDER)

    /**
     * 「扫码签到」按钮的落点：直接进智学北航扫码页，不落回首页再让人点一次加号。
     *
     * 用户是在上课前两三分钟点它的，多一次跳转就是多一次「我到底签上没有」的悬空。
     */
    fun spocScanPendingIntent(context: Context): PendingIntent =
        launchActivityPendingIntent(context, REQUEST_SPOC_SCAN, route = "spoc_scan")

    /**
     * 各通知族共用的启动意图工厂：**码段 + data 双重分家**。
     *
     * requestCode 已经能把它们拆开，但仍加上各自的 `data`：PendingIntent 的取消与
     * 判等在某些 ROM 上按 `Intent.filterEquals` 匹配，而它只看 action/data/type/
     * class/categories —— 光凭码分家，将来谁改了重建逻辑就会重新撞在一起。
     */
    private fun launchActivityPendingIntent(
        context: Context,
        requestCode: Int,
        courseId: Long = 0L,
        route: String? = null,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            data = "buaa://launch/$requestCode".toUri()
            if (courseId > 0L) putExtra(MainActivity.EXTRA_COURSE_ID, courseId)
            route?.let { putExtra(MainActivity.EXTRA_ROUTE, it) }
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
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
        // 撤了通知就不再保护任何一节课：归属必须同点清掉，
        // 否则下一次重排会对着一条已经不存在的实况手下留情。
        countdownCourseId = 0L
        countdownClassStart = 0L
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

/**
 * 标题右侧那一格的辅助标识：第 N 周 · 周X · 教师。
 *
 * 三项都在课表数据里，此前在实况上却一个字都没出现——正文两行已被
 * "节次 · 时间 · 地点"和倒计时占满，硬挤只会把最该看的数字挤掉。
 * 全空时返回 null（`setSubText(null)` 即不显示这一格，而不是显示一个空串）。
 */
internal fun liveSubText(week: Int?, dayOfWeek: Int?, teacher: String?): String? =
    listOfNotNull(
        week?.let { "第 $it 周" },
        dayOfWeek?.let(::weekdayLabel),
        teacher?.takeIf { it.isNotBlank() },
    ).joinToString(" · ").takeIf { it.isNotBlank() }

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

/** 起止时间区间（HH:mm–HH:mm）；任一端解析失败时返回 null，正文少一段而不是整个发不出去 */
internal fun liveTimeRange(startMillis: Long, endMillis: Long): String? {
    val start = clockOf(startMillis) ?: return null
    val end = clockOf(endMillis) ?: return null
    return "$start–$end"
}

/**
 * 毫秒 → HH:mm：通知正文、实况卡片与桌面组件共用的同一口径。
 *
 * 必须钉死 Locale.US：默认 locale 的 DecimalStyle 在部分语言下会输出
 * 非 ASCII 数字（如 ar / fa 的 ٠١٢），而这里是给通知与时间戳用的，
 * 一旦变成非 ASCII 数字，下游解析与展示都会出错。
 * 解析失败返回 null，让调用方整段跳过，而不是显示一个空时间。
 *
 * **`<= 0` 是"这一端时刻缺失"的哨兵，同样返回 null**：0 在 `ofEpochMilli` 那里不是
 * "没有值"而是 1970-01-01，东八区读出来正好是「08:00」——覆盖安装后旧闹钟 extras 缺
 * end 键、[ClassProgressScheduler.scheduleEnd] 用的空窗口都会走到这里，
 * 少一个守卫就是往通知正文里凭空写一个上课时间。
 */
internal fun clockOf(millis: Long): String? {
    if (millis <= 0L) return null
    return runCatching {
        java.time.Instant.ofEpochMilli(millis)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.US))
    }.getOrNull()
}
