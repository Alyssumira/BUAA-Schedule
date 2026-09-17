package com.buaa.schedule.reminder

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Bundle
import androidx.core.content.edit
import com.buaa.schedule.R
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 澎湃「焦点通知 / 超级岛」自定义内容模板（`miui.focus.param`）。
 *
 * ## 这条路有三分之一是确定的
 *
 * 载荷结构取自 HyperIsland 里能真正常驻上岛的样例（`ScreenRecorderHook.buildFocusBundle`），
 * 四个坑都有出处：
 * - `miui.focus.param` 是**字符串** extra，内容是 `{"param_v2": {...}}` 这层信封；
 * - 动作不能只塞在 `miui.focus.actions` 嵌套 Bundle 里 —— SystemUI 只按顶层
 *   `miui.focus.action_1 / _2` 取 `Notification.Action`，嵌套那份会被忽略；
 * - `sameWidthDigitInfo` 在样例里**只有 `timerInfo` 一个字段**，没有 title/content；
 *   文字靠通知自身的 title/text 与 `android.shortCriticalText`（见
 *   [ReminderNotifications.applyPromotedOngoingExtras]）；
 * - `timerInfo` 的语义是**计时器**而不是倒计时：`timerWhen` = 起始绝对时刻
 *   （样例里算作 `now - duration`），`timerType` 1 = 走秒、2 = 停住
 *   （样例只在录屏暂停时给 2）。填"还剩多少毫秒"会在下发那一刻被冻结。
 *
 * 计时交给系统侧自己走，应用不需要为重绘岛内容而周期性重发通知 —— 这是省电架构的硬约束。
 *
 * ## 但它默认不生效，而且不是我们的错
 *
 * 小米把自定义岛内容卡在 SystemUI 进程内的三重白名单上：
 * `NotificationSettingsManager.canShowFocus` / `canCustomFocus` 决定该 `business` 能不能显示，
 * `focus.SignatureChecker.checkSignatures` 决定发通知的那一方是不是被登记过的签名。
 * HyperIsland 能绕过，是因为它以 Xposed 模块的身份**在 SystemUI 进程里**调用 `nm.notify()`
 * ——签名检查看到的调用方就是 SystemUI 自己。第三方应用没有这条路。
 *
 * 因此这份模板：① 只在小米/Redmi 上生效；② 本版本不在设置页暴露它，默认关闭，
 * 只能由 `schedule_settings` 的 `island_custom_focus` 手动打开；③ 打开后如果岛没变化，
 * 唯一正确的解读是"该 business 尚未在小米《超级岛模板库》提报通过"，
 * 而不是代码写错了。判据只看真课表那条 [ReminderNotifications.postClassOngoing]：
 * 它上了岛而加了本模板之后没变化，就是这份 param 没被受理。
 */
object IslandFocusTemplate {

    /** 实验开关：默认关。见类注释里的白名单结论 */
    const val PREF_KEY = "island_custom_focus"

    private const val PREFS_NAME = "schedule_settings"

    /** 提报后才可能被受理的业务标识；未登记时 SystemUI 直接忽略整条 param */
    private const val BUSINESS = "schedule"
    private const val SCENE = "class_progress"

    /** 样例里是 `#RRGGBB` **字符串**，不是颜色 int */
    private const val HIGHLIGHT_COLOR = "#FF6B3F"

    /** timerType：走秒。2 是「停住」，小米没有第三种（倒计时）取值 */
    private const val TIMER_RUNNING = 1

    private const val PARAM_KEY = "miui.focus.param"
    private const val PICS_KEY = "miui.focus.pics"
    private const val TICKER_PIC_KEY = "miui.focus.pic_ticker"
    private const val ACTION_KEY_OPEN_COURSE = "miui.focus.action_1"

    /**
     * `encodeDefaults = true` 是必须的：`protocol` / `updatable` / `enableFloat` 这些
     * 固定字段在能真正常驻上岛的样例里**每次都写**，而它们在我们这边正好都是默认值 ——
     * 关掉默认值等于把 `protocol` 整个发没发，SystemUI 拿到就没有协议档位可判。
     */
    private val json = Json { encodeDefaults = true }

    /** 只有小米机型 + 用户明确打开，才值得往通知里塞这堆私有 extra */
    fun enabled(context: Context): Boolean =
        IslandDiagnostics.isXiaomiRom() &&
            prefs(context).getBoolean(PREF_KEY, false)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(PREF_KEY, value) }
    }

    /**
     * 把岛模板挂到一条已构建的通知上。
     *
     * 全程 runCatching：这是**附加**信息，任何一步失败都不能让真正要显示的课堂实况发不出去。
     */
    fun attach(
        context: Context,
        notification: Notification,
        notificationId: Int,
        window: ClassProgressScheduler.ClassWindow,
    ) {
        if (!enabled(context)) return
        runCatching {
            val extras = notification.extras
            extras.putString(
                PARAM_KEY,
                paramJson(
                    packageName = context.packageName,
                    notificationId = notificationId,
                    courseName = window.courseName,
                    sectionText = window.sectionText,
                    startMillis = window.startMillis,
                    nowMillis = System.currentTimeMillis(),
                    highlightColor = highlightColorOf(window.colorArgb),
                ),
            )
            extras.putBundle(
                PICS_KEY,
                Bundle().apply {
                    putParcelable(TICKER_PIC_KEY, Icon.createWithResource(context, R.drawable.ic_notification))
                },
            )
            // 摊平到顶层：嵌套 Bundle 里那份 SystemUI 不读
            extras.putParcelable(
                ACTION_KEY_OPEN_COURSE,
                Notification.Action.Builder(
                    null,
                    "查看这节课",
                    ReminderNotifications.courseLaunchPendingIntent(context, window.courseId),
                ).build(),
            )
        }
    }

    /**
     * 岛上的高亮色 = 这门课在课表上的颜色。
     *
     * 小米规定这里是 `#RRGGBB` **字符串**（不是颜色 int），所以丢掉 alpha 通道；
     * 没有课程色时沿用默认橙，而不是发一个 `#000000` 过去把岛上染成黑的。
     */
    internal fun highlightColorOf(argb: Int?): String = argb?.let {
        "#%06X".format(it and 0xFFFFFF)
    } ?: HIGHLIGHT_COLOR

    /**
     * 纯函数版载荷，供 JVM 单测直接断言字段名与 timer 语义（不需要 Android 运行时）。
     *
     * `startMillis <= 0` 时退化成"从这一刻开始走秒"：宁可岛上从 00:00 走，
     * 也不能把 0 当成 1970 年——那会让计时器显示成一个荒谬的天数。
     */
    internal fun paramJson(
        packageName: String,
        notificationId: Int,
        courseName: String,
        sectionText: String,
        startMillis: Long,
        nowMillis: Long,
        highlightColor: String = HIGHLIGHT_COLOR,
    ): String {
        val ticker = sectionText.ifBlank { courseName }
        val timerWhen = if (startMillis > 0L) startMillis else nowMillis
        val param = FocusParam(
            business = BUSINESS,
            scene = SCENE,
            content = courseName,
            ticker = ticker,
            notifyId = "$packageName$notificationId",
            paramIsland = FocusIslandSection(
                islandPriority = 1,
                islandTimeout = Int.MAX_VALUE,
                islandProperty = 2,
                highlightColor = highlightColor,
                bigIslandArea = FocusBigArea(
                    imageTextInfoLeft = FocusImageText(
                        type = 1,
                        picInfo = FocusPic(type = 1, pic = TICKER_PIC_KEY),
                    ),
                    sameWidthDigitInfo = FocusDigits(
                        timerInfo = FocusTimerInfo(
                            timerWhen = timerWhen,
                            timerType = TIMER_RUNNING,
                            timerSystemCurrent = nowMillis,
                        ),
                    ),
                ),
                smallIslandArea = FocusSmallArea(picInfo = FocusPic(type = 1, pic = TICKER_PIC_KEY)),
            ),
        )
        return json.encodeToString(FocusEnvelope.serializer(), FocusEnvelope(param))
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- 载荷结构：字段名是小米定的，不是我们能挑的 ----

    @Serializable
    internal data class FocusEnvelope(@SerialName("param_v2") val param: FocusParam)

    @Serializable
    internal data class FocusParam(
        val protocol: Int = 1,
        val updatable: Boolean = true,
        val enableFloat: Boolean = false,
        val business: String,
        val scene: String,
        val content: String,
        val notifyId: String,
        val islandFirstFloat: Boolean = false,
        val ticker: String,
        val tickerPic: String = TICKER_PIC_KEY,
        @SerialName("tickerPicDark") val tickerPicDark: String = TICKER_PIC_KEY,
        @SerialName("param_island") val paramIsland: FocusIslandSection,
    )

    @Serializable
    internal data class FocusIslandSection(
        val islandPriority: Int,
        val islandTimeout: Int,
        val islandProperty: Int,
        val highlightColor: String,
        @SerialName("bigIslandArea") val bigIslandArea: FocusBigArea,
        @SerialName("smallIslandArea") val smallIslandArea: FocusSmallArea,
    )

    @Serializable
    internal data class FocusBigArea(
        @SerialName("imageTextInfoLeft") val imageTextInfoLeft: FocusImageText,
        @SerialName("sameWidthDigitInfo") val sameWidthDigitInfo: FocusDigits,
    )

    @Serializable
    internal data class FocusSmallArea(@SerialName("picInfo") val picInfo: FocusPic)

    @Serializable
    internal data class FocusImageText(val type: Int, val picInfo: FocusPic)

    @Serializable
    internal data class FocusPic(val type: Int, val pic: String)

    /** 等宽数字区：只放系统自己走的计时器，文字另有归属（见类注释） */
    @Serializable
    internal data class FocusDigits(val timerInfo: FocusTimerInfo)

    @Serializable
    internal data class FocusTimerInfo(
        val timerWhen: Long,
        val timerType: Int,
        val timerSystemCurrent: Long,
    )
}
