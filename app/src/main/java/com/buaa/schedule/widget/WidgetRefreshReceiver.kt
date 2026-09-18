package com.buaa.schedule.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.buaa.schedule.reminder.WakeLocks
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
                // goAsync 只保证进程存活、不保证 CPU 醒着：零点/改时间多半在 Doze 里，
                // 这段要查库重算快照并重排闹钟，中途睡回去就会留下"组件停在昨天"
                // （唤醒锁用法同 ReminderReceiver）
                WakeLocks.withPartialWakeLock(
                    context,
                    "widget_refresh",
                    // 这一段比 ReminderReceiver 的单条提醒重排重得多（全学期快照 sync +
                    // 6 个组件重绘），默认 5 秒不够，会被系统提前收回
                    timeoutMs = 10_000L,
                ) {
                    if (action == ACTION_MIDNIGHT_REFRESH || timeChanged) {
                        // 零点会醒两次：系统 00:00 的 ACTION_DATE_CHANGED 与我们自排的
                        // ACTION_MIDNIGHT_REFRESH 都落到这里，各跑一遍"全学期快照 sync +
                        // 组件重绘"。自排那条是对抗 ROM 的兜底（它同样可能吞掉系统广播），
                        // 两条都留，但重复的那次全量刷新没有意义。
                        if (claimRolloverRefresh()) {
                            BackgroundSync.refreshWidgets(context)
                        }
                        BackgroundSync.scheduleWidgetMidnight(context)
                    }
                    if (timeChanged || exactAlarmPermissionChanged) {
                        // 时间/时区变化会让已注册的触发时间失准，权限变化可升级为精确闹钟；
                        // 这里的 Boolean 同样不能丢——改完时间课堂铃窗口也要重排
                        BackgroundSync.rescheduleRemindersAndBells(context)
                    }
                    if (timeChanged) {
                        // 明日预告按 22:00 定时，时间/时区变化后要重新对齐；
                        // 通知渠道也需要确保存在（原先由 BootReceiver 顺带做）
                        BackgroundSync.scheduleTomorrowPreview(context)
                        com.buaa.schedule.reminder.ReminderNotifications.ensureChannels(context)
                    }
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

        /**
         * 跨天全量刷新的去重窗口。两个信号（系统 DATE_CHANGED、自排零点闹钟）
         * 正常都在 00:00±几十秒内到达，留足余量又不至于长到让真实的数据变化被吞掉。
         */
        private const val ROLLOVER_SUPPRESS_MS = 120_000L

        private val lastRolloverAt = java.util.concurrent.atomic.AtomicLong(0L)

        /**
         * 领取这一次跨天刷新的"全量刷新"配额；已被领走则返回 false。
         *
         * 计时用 `elapsedRealtime`（单调）而不是墙上时钟：用户改时间本身就会
         * 以 `ACTION_TIME_CHANGED` 走到这个分支，拿被改的时钟判窗口等于判不出来。
         *
         * ⚠️ 必须 CAS：DATE_CHANGED 与自排零点闹钟恰恰都在 00:00±几十秒到达，
         * 各自起的 IO 协程用 @Volatile 的"先读后写"会双双领到配额，全学期快照
         * 与 6 个组件重绘跑两遍。
         */
        private fun claimRolloverRefresh(): Boolean {
            val now = android.os.SystemClock.elapsedRealtime()
            while (true) {
                val prev = lastRolloverAt.get()
                if (now - prev < ROLLOVER_SUPPRESS_MS) return false
                if (lastRolloverAt.compareAndSet(prev, now)) return true
            }
        }
    }
}
