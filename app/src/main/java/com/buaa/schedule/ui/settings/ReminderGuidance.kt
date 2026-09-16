package com.buaa.schedule.ui.settings

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import com.buaa.schedule.reminder.ReminderNotifications

/**
 * 提醒可靠性的引导入口。
 *
 * 课表提醒的生命线是「精确闹钟 + 通知权限 + 厂商后台放行」，任何一环被系统/用户
 * 掐掉，提醒就会迟到或消失。三家的通行做法是把诊断和跳转入口直接给用户
 * （参照 SleepDown `ScheduleConfigScreenUi.kt` 的保活引导）。
 */
object ReminderGuidance {

    /** 精确闹钟是否可用（Android 12+ 为特殊权限，默认拒绝） */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(AlarmManager::class.java) ?: return true
        return manager.canScheduleExactAlarms()
    }

    /**
     * 当前能否发出通知（含"渠道被用户关掉"的情况）。
     *
     * @param channelId 指定渠道时只判断该渠道；不指定时按"全部渠道都关才算关"的聚合语义，
     *   与 [ReminderNotifications.canPostNotifications] 一致。
     */
    fun canPostNotifications(context: Context, channelId: String? = null): Boolean =
        ReminderNotifications.canPostNotifications(context, channelId)

    /**
     * Android 16+ 实况通知（promoted ongoing，澎湃超级岛/流体云的载体）可用性三态：
     * - null：系统低于 16，无此概念（该行在设置页隐藏）；
     * - true：系统允许把常驻通知提升为实况样式；
     * - false：用户在通知设置里关掉了「提升式通知」，实况只能显示为普通常驻。
     *
     * `canPostPromotedNotifications()` 为 API 36 新增（SleepDown 同款三态探针）；
     * 个别 ROM 未实现该方法时也归入 null，避免误报"已关闭"。
     */
    fun promotedOngoingState(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return null
        val manager = context.getSystemService(NotificationManager::class.java) ?: return null
        return runCatching { manager.canPostPromotedNotifications() }.getOrNull()
    }

    /** 跳转到本应用的通知设置页 */
    fun openNotificationSettings(context: Context) {
        // minSdk 26 = O，直接走应用通知设置页即可
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** 跳转到精确闹钟授权页（Android 12+） */
    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData("package:${context.packageName}".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * 跳转厂商自启动管理页：依次尝试 MIUI / ColorOS / 华为，全部失败回退应用详情。
     * 这些 ComponentName 在非对应机型上会抛异常，逐个吞掉即可。
     */
    fun openAutoStartSettings(context: Context) {
        val candidates = listOf(
            // MIUI / 澎湃 HyperOS：安全中心 → 自启动
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            // OPPO ColorOS：自启动管理
            "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oppo.safe/com.oppo.safe.permission.startup.StartupAppListActivity",
            // 华为 EMUI / HarmonyOS：启动管理
            "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        )
        candidates.forEach { component ->
            runCatching {
                val parts = component.split('/')
                context.startActivity(
                    Intent().setClassName(parts[0], parts[1]).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return
            }
        }
        openAppDetails(context)
    }

    /**
     * 跳转「创建桌面快捷方式」授权页（MIUI / 澎湃：安全中心 → 应用管理 → 权限 → 其他权限）。
     *
     * 为什么不是 `requestPermissions`： Launcher 的 `INSTALL_SHORTCUT` 是 **normal 级**
     * 权限，安装期就授予了，运行时申请只会立刻回调同一个答案，永远不弹窗。
     * 真正拦住小组件钉选的是 ROM 自己的私有开关，被它拦住时
     * `requestPinAppWidget` 仍然返回 true，但桌面一个确认框都不弹
     * ——用户看到的就是"点了没反应"。这里能做的不是"申请"，
     * 而是把用户直接送到那个开关面前。
     */
    fun openShortcutPermissionSettings(context: Context) {
        val candidates = listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity",
                )
                .putExtra("extra_pkgname", context.packageName),
            // 部分 MIUI 版本只认 action，不认组件名
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .putExtra("extra_pkgname", context.packageName),
        )
        candidates.forEach { intent ->
            runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
        }
        openAppDetails(context)
    }

    /** 引导用户把应用加入电池优化白名单（系统会弹确认框） */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData("package:${context.packageName}".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.recoverCatching {
            // 个别 ROM 禁止第三方请求白名单：退回电池优化设置列表
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** 兜底：本应用详情页（用户可从那里进自启动/电池设置） */
    fun openAppDetails(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData("package:${context.packageName}".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
