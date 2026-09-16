package com.buaa.schedule.reminder

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.buaa.schedule.ui.settings.ReminderGuidance

/**
 * 实况样式在各家 ROM 上的产品名。
 *
 * **只用于把文案说对口，不改变下发的形状**：三条路径（[CourseFluidService] 的两个分支、
 * [ReminderNotifications.postClassOngoing]）发的都是同一套 Android 16+ promoted ongoing
 * 通知，澎湃超级岛与 ColorOS 流体云各自去渲染它。ColorOS 因此不需要私有 SDK 就归到
 * "标准通道"这一侧（依据公开报道，见 `VENDOR_NOTES.md`；我们在小米机器上验，
 * ColorOS 那一侧的效果无法在本机复现）。
 */
enum class LiveIslandSurface(val displayName: String) {
    /** 澎湃 HyperOS 的超级岛 —— 本项目唯一真机验证过的渲染方 */
    HYPER_ISLAND("超级岛"),

    /** ColorOS（OPPO / 一加 / realme）的流体云 */
    FLUID_CLOUD("流体云"),

    /** 原生 Android 16+ Live Updates，以及认不出归属的 ROM */
    NATIVE("实况通知"),
}

/**
 * 实况样式（澎湃超级岛 / 流体云 / 原生 promoted ongoing）的能力探针。
 *
 * 此前实况这块最大的问题不是"没写对"，而是**写完之后无从知道系统采纳了没有**：
 * 通知发出去就完了，上没上岛在应用内完全不可观测，只能靠肉眼 + logcat 猜。
 * 这里把"系统给了多少支持"变成可显示、可自查的两件事：
 *
 * 1. [promotedOngoingState]：Android 16+ 的官方三态（允许 / 用户关闭 / 系统不支持）；
 *    Android 17 起还会折进 `POST_PROMOTED_NOTIFICATIONS` 的授权态 ——
 *    那条权限没到手时，`canPostPromotedNotifications()` 说"允许"也不会真的上岛；
 * 2. [focusProtocolVersion]：澎湃公布在 `Settings.System` 上的焦点通知协议档位，
 *    **普通应用可读**（HyperIsland 的引导页就是读它做环境自检）。
 *
 * 注意"读得到档位"不等于"写得了内容"：小米把**自定义岛内容**（`miui.focus.param`）
 * 卡在 SystemUI 进程内的 `canShowFocus` / `canCustomFocus` / `SignatureChecker`
 * 三重白名单上，第三方应用凭自己的权限改不动，只能走官方《超级岛模板库》提报。
 * 因此本项目走的是标准 promoted ongoing 通知被 ROM 自动渲染成岛样式这条路：
 * 澎湃按这条路渲染成超级岛（已真机验证），ColorOS 16 按公开报道全面接入同一套
 * 安卓实时活动 API、无需提报即可渲染成流体云（**本仓库没有 ColorOS 真机，
 * 这一侧只做到"形状与小米一致 + 文案说对产品名"，不声称已验证**）。
 * 本文件只负责把"这条路在这台机器上通不通"如实告诉用户。
 */
object IslandDiagnostics {

    /** 焦点通知协议在 Settings.System 里的键（MIUI / 澎湃 HyperOS 私有） */
    private const val SETTINGS_KEY_FOCUS_PROTOCOL = "notification_focus_protocol"

    /** 小米系品牌；`Build.MANUFACTURER` 一律是 Xiaomi，品牌才有 Redmi / Poco */
    private val XIAOMI_BRANDS = setOf("xiaomi", "redmi", "poco")

    /** ColorOS 系品牌：realme 与一加已并入 OPPO 的系统线，流体云同源 */
    private val OPPO_BRANDS = setOf("oppo", "oneplus", "realme")

    /** ColorOS 自己的版本串（公开报道里"流体云接入安卓实时活动 API"说的是 ColorOS 16 起） */
    private const val PROP_COLOROS_VERSION = "ro.build.version.opporom"

    /** 反射读私有 SystemProperties，读不到就是 null；lazy 缓存一次，避免每帧设置页都反射 */
    private val colorOsPropCache: String? by lazy {
        runCatching {
            //noinspection PrivateApi 只用于给文案补一个版本号，全程 runCatching、
            // 拒绝访问的 ROM 上退回 null；品牌判断本身不依赖它。
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("get", String::class.java, String::class.java)
            ((get.invoke(null, PROP_COLOROS_VERSION, "") as? String)?.trim())
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    /** 只在小米 / Redmi 上探测澎湃专有探针，其它机型返回 false */
    fun isXiaomiRom(): Boolean = liveIslandSurface() == LiveIslandSurface.HYPER_ISLAND

    /** ColorOS（OPPO / 一加 / realme）：实况那一格叫「流体云」，走的仍是标准提升通知 */
    fun isColorOsRom(): Boolean = liveIslandSurface() == LiveIslandSurface.FLUID_CLOUD

    /** 当前机型上「岛」的产品名 */
    fun liveIslandSurface(): LiveIslandSurface =
        liveIslandSurfaceOf(Build.BRAND, Build.MANUFACTURER, Build.DISPLAY, colorOsVersion())

    /**
     * ROM 归属判定的纯函数（[liveIslandSurface] 的可测版本）。
     *
     * 参数全部传入而不读 `Build`：单测里 `Build.*` 是 null，这条判断又是唯一
     * 决定用户看到哪句产品名的入口，值得钉住。
     */
    internal fun liveIslandSurfaceOf(
        brand: String?,
        manufacturer: String?,
        display: String?,
        colorOsProp: String?,
    ): LiveIslandSurface {
        val b = brand?.trim()?.lowercase()
        val m = manufacturer?.trim()?.lowercase()
        val d = display?.lowercase().orEmpty()
        return when {
            b in XIAOMI_BRANDS || m == "xiaomi" || d.contains("hyperos") || d.contains("miui") ->
                LiveIslandSurface.HYPER_ISLAND
            b in OPPO_BRANDS || !colorOsProp.isNullOrBlank() || d.contains("coloros") ->
                LiveIslandSurface.FLUID_CLOUD
            else -> LiveIslandSurface.NATIVE
        }
    }

    /** ColorOS 版本串（如 `V16.0.0.0`）；读不到返回 null，不参与任何行为判断 */
    fun colorOsVersion(): String? = colorOsPropCache

    /**
     * 设置页/引导页那一行的标题：按 ROM 说对口产品名，不拿别家的名词许诺别家的行为。
     */
    fun promotedRowTitle(): String = when (liveIslandSurface()) {
        LiveIslandSurface.HYPER_ISLAND -> "实况通知（超级岛）"
        LiveIslandSurface.FLUID_CLOUD -> "实况通知（流体云）"
        LiveIslandSurface.NATIVE -> "实况通知（Live Updates）"
    }

    /**
     * 澎湃焦点通知协议档位；读不到（非小米 / 键不存在 / ROM 拒绝）返回 null。
     *
     * 整体 runCatching：这是个探针，绝不能把引导页或设置页打崩。
     */
    fun focusProtocolVersion(context: Context): Int? {
        if (!isXiaomiRom()) return null
        return runCatching {
            Settings.System.getString(context.contentResolver, SETTINGS_KEY_FOCUS_PROTOCOL)
        }.getOrNull()?.trim()?.toIntOrNull()
    }

    /** Android 16+ promoted ongoing 三态，与设置页「实况通知」同一口径 */
    fun promotedOngoingState(context: Context): Boolean? = ReminderGuidance.promotedOngoingState(context)
}
