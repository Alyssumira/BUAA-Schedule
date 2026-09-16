package com.buaa.schedule.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 事件驱动的 Widget/提醒刷新接收器（时间类变化的唯一入口）：
 * - 自发的零点刷新（今天/明天 Widget 的日期滚动）；
 * - 系统时间被手动修改、时区变化、日期变化（BootReceiver 不再订阅这几个，
 *   避免同一事件被两个接收器各跑一遍）；
 * - 精确闹钟权限被用户授予（把提醒升级为精确闹钟）。
 */
class WidgetRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val timeChanged = action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED ||
            action == Intent.ACTION_DATE_CHANGED
        val exactAlarmPermissionChanged =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            action == "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"

        if (action != ACTION_MIDNIGHT_REFRESH && !timeChanged && !exactAlarmPermissionChanged) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (action == ACTION_MIDNIGHT_REFRESH || timeChanged) {
                    BackgroundSync.refreshWidgets(context)
                    BackgroundSync.scheduleWidgetMidnight(context)
                }
                if (timeChanged || exactAlarmPermissionChanged) {
                    // 时间/时区变化会让已注册的触发时间失准，权限变化可升级为精确闹钟
                    BackgroundSync.rescheduleReminders(context)
                }
                if (timeChanged) {
                    // 明日预告按 22:00 定时，时间/时区变化后要重新对齐；
                    // 通知渠道也需要确保存在（原先由 BootReceiver 顺带做）
                    BackgroundSync.scheduleTomorrowPreview(context)
                    com.buaa.schedule.reminder.ReminderNotifications.ensureChannels(context)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 协程取消是控制流信号，不是错误：绝不能在这里吞掉，
                // 否则上游的 cancel/超时会被当成"正常完成"，结构化并发失效。
                throw e
            } catch (e: SecurityException) {
                // 精确闹钟权限在调度中途被撤销（或 ROM 收紧）时系统会抛 SecurityException：
                // 这是要用户去设置里处理的权限问题，与一般性失败不同，单独留痕便于定位。
                // BackgroundSync 内部已有 canScheduleExactAlarms 守卫，走到这里多为非预期路径。
                Log.w(TAG, "事件刷新被系统拒绝（权限类失败）: $action", e)
            } catch (e: Exception) {
                Log.w(TAG, "事件刷新失败: $action", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "WidgetRefreshReceiver"
        const val ACTION_MIDNIGHT_REFRESH = "com.buaa.schedule.action.WIDGET_MIDNIGHT_REFRESH"
    }
}
