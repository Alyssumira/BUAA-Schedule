package com.buaa.schedule.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.buaa.schedule.widget.BackgroundSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机 / 升级后的闹钟与组件重建。
 *
 * - BOOT_COMPLETED：重启后 AlarmManager 闹钟全部丢失；
 * - MY_PACKAGE_REPLACED：覆盖安装同样会清掉闹钟；
 * - LOCKED_BOOT_COMPLETED 需要接收器 directBootAware 且数据在设备加密区，
 *   本应用不满足，开机后由 BOOT_COMPLETED 兜底即可。
 *
 * 注意：DATE_CHANGED / TIME_SET / TIMEZONE_CHANGED **不在这里处理**。
 * 此前 [BootReceiver] 与 WidgetRefreshReceiver 同时订阅这三个广播，
 * 一次改时间会并发跑两遍"重排提醒 + 刷新组件 + 排零点闹钟"。
 * 现在统一由 WidgetRefreshReceiver 负责（时间类变化的唯一入口）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 重启把闹钟全清了，遗留的勿扰记录再没有下课铃来恢复 —— 开机即无条件清一次
                // （没进过勿扰时 restore 本身就是 no-op，不会动用户自己的勿扰设置）
                ClassProgressDnd.restore(context)
                BackgroundSync.rescheduleReminders(context)
                BackgroundSync.refreshWidgets(context)
                BackgroundSync.scheduleWidgetMidnight(context)
                BackgroundSync.scheduleTomorrowPreview(context)
                ReminderNotifications.ensureChannels(context)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 协程取消是控制流信号，必须继续向上传播，不能吞掉
                throw e
            } catch (e: Exception) {
                // 广播 goAsync 里的未捕获异常会直接杀死进程；这里兜底并留痕
                Log.w(TAG, "开机/升级后台重建失败", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"

        private val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
