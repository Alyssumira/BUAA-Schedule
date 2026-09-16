package com.buaa.schedule.ui.onboarding

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.content.getSystemService
import com.buaa.schedule.reminder.IslandDiagnostics
import com.buaa.schedule.reminder.ReminderNotifications
import com.buaa.schedule.ui.settings.ReminderGuidance

/** 一条自检项的结论。[Unknown] 表示这台机器上根本没有这个概念，不算失败 */
enum class CheckStatus { Passed, Failed, Unknown }

data class CheckItem(
    val id: String,
    val title: String,
    val summary: String,
    val status: CheckStatus,
    /** 阻塞项没通过时，引导页会拦一次"下一步"并给出 继续 / 重试 的选择 */
    val blocking: Boolean,
    /** 是否能把用户送到对应的系统授权页；null 表示这一项只是只读探针 */
    val actionLabel: String?,
)

/**
 * 首启引导里的环境自检清单。
 *
 * 组织方式照抄 HyperIsland 引导页的「一条一项、逐项探针、整体判定」结构
 * （`OnboardingPage.kt` 的 `EnvironmentPanel` + `PermissionCard`）：以前我们把
 * 通知 / 精确闹钟 / 电池 / 自启动 / 实况五件事散在
 * 设置 → 提醒可靠性 的深层里，新用户根本不知道自己缺哪一项，
 * 而"提醒没响"的排查成本又全落在用户身上。
 *
 * 判定口径与设置页保持一致（复用 [ReminderGuidance] 的探针），不要在两处各算一遍。
 */
object EnvironmentCheck {

    const val ID_NOTIFICATION = "notification"
    const val ID_EXACT_ALARM = "exact_alarm"
    const val ID_BATTERY = "battery"
    const val ID_PROMOTED = "promoted_ongoing"
    const val ID_FOCUS_PROTOCOL = "focus_protocol"

    /** 全部自检项（按用户关心的顺序）。只读探针，不改任何系统状态 */
    fun runAll(context: Context): List<CheckItem> = listOf(
        notificationItem(context),
        exactAlarmItem(context),
        batteryItem(context),
        promotedItem(context),
        focusItem(context),
    ).filterNotNull()

    /** 阻塞项里未通过的那些；空表示可以直接下一步 */
    internal fun unmetBlockers(items: List<CheckItem>): List<CheckItem> =
        items.filter { it.blocking && it.status == CheckStatus.Failed }

    /** 跳到该项对应的系统设置；无跳转目标的项什么都不做 */
    fun openFix(context: Context, id: String) {
        when (id) {
            ID_NOTIFICATION -> ReminderGuidance.openNotificationSettings(context)
            ID_EXACT_ALARM -> ReminderGuidance.openExactAlarmSettings(context)
            ID_BATTERY -> ReminderGuidance.requestIgnoreBatteryOptimizations(context)
            ID_PROMOTED -> ReminderGuidance.openPromotedNotificationSettings(context)
            ID_FOCUS_PROTOCOL -> Unit
        }
    }

    private fun notificationItem(context: Context): CheckItem {
        val granted = ReminderNotifications.canPostNotifications(context)
        return CheckItem(
            id = ID_NOTIFICATION,
            title = "通知权限",
            summary = if (granted) {
                "课程提醒与课堂实况都依赖它"
            } else {
                "已关闭：上课提醒不会响，实况也不会出现"
            },
            status = if (granted) CheckStatus.Passed else CheckStatus.Failed,
            blocking = true,
            actionLabel = if (granted) null else "去开启",
        )
    }

    private fun exactAlarmItem(context: Context): CheckItem {
        val granted = ReminderGuidance.canScheduleExact(context)
        return CheckItem(
            id = ID_EXACT_ALARM,
            title = "精确闹钟",
            summary = if (granted) {
                "上课铃会按点触发"
            } else {
                "未授权：提醒会被系统推迟到下个批量唤醒窗口"
            },
            status = when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> CheckStatus.Unknown
                granted -> CheckStatus.Passed
                else -> CheckStatus.Failed
            },
            blocking = false,
            actionLabel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !granted) "去授权" else null,
        )
    }

    private fun batteryItem(context: Context): CheckItem {
        val manager = context.getSystemService<PowerManager>()
        val ignored = manager?.isIgnoringBatteryOptimizations(context.packageName) == true
        return CheckItem(
            id = ID_BATTERY,
            title = "电池优化豁免",
            summary = if (ignored) {
                "熄屏后后台闹钟不易被清理"
            } else {
                "未豁免：熄屏后系统可能清理本应用，导致课堂实况缺失"
            },
            status = if (ignored) CheckStatus.Passed else CheckStatus.Failed,
            blocking = false,
            actionLabel = if (ignored) null else "去豁免",
        )
    }

    private fun promotedItem(context: Context): CheckItem {
        val title = IslandDiagnostics.promotedRowTitle()
        return when (IslandDiagnostics.promotedOngoingState(context)) {
            true -> CheckItem(
                id = ID_PROMOTED,
                title = title,
                summary = if (IslandDiagnostics.isColorOsRom()) {
                    // 这一侧没有真机，能给的只有公开口径：ColorOS 16 起接安卓实时活动 API，
                    // 标准实况通知理论上直接渲染成流体云。写清楚，别冒充已验证。
                    "系统允许把课堂常驻通知提升为流体云样式；" +
                        "按公开资料 ColorOS 16 起接入安卓实时活动 API，我们无 ColorOS 真机，未实测"
                } else {
                    "系统允许把课堂常驻通知提升为${IslandDiagnostics.liveIslandSurface().displayName}样式"
                },
                status = CheckStatus.Passed,
                blocking = false,
                actionLabel = null,
            )
            false -> CheckItem(
                id = ID_PROMOTED,
                title = title,
                summary = if (!ReminderGuidance.hasPromotedPermission(context)) {
                    "本应用未拿到 Android 17 的实况权限（POST_PROMOTED_NOTIFICATIONS）：" +
                        "属应用侧问题，装上声明了该权限的版本即可"
                } else {
                    "系统设置里的「提升式通知」被关闭了，课堂进度只会显示为普通常驻通知"
                },
                status = CheckStatus.Failed,
                blocking = false,
                actionLabel = "去开启",
            )
            null -> CheckItem(
                id = ID_PROMOTED,
                title = title,
                summary = "这台机器的系统没有实况提升概念，课堂进度显示为普通常驻通知",
                status = CheckStatus.Unknown,
                blocking = false,
                actionLabel = null,
            )
        }
    }

    /**
     * 澎湃焦点通知协议档位。只读，用来看"这台机器的岛子系统在不在线"，
     * 不代表我们能自定义岛内容（那条路要小米提报）。非小米机型整项隐藏。
     */
    private fun focusItem(context: Context): CheckItem? {
        val version = IslandDiagnostics.focusProtocolVersion(context) ?: return null
        val usable = version >= 1
        return CheckItem(
            id = ID_FOCUS_PROTOCOL,
            title = "澎湃焦点通知协议",
            summary = if (usable) {
                "协议 $version：岛子系统在线，标准实况通知会被渲染成超级岛样式"
            } else {
                "协议 $version：这台机器的岛未启用"
            },
            status = if (usable) CheckStatus.Passed else CheckStatus.Unknown,
            blocking = false,
            actionLabel = null,
        )
    }
}
