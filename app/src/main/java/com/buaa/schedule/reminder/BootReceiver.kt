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
                // 开机这批重建全是查库 + binder 调用；goAsync 只保证进程存活，
                // 不保证 CPU 一直醒着（同 ReminderReceiver 的口径），带超时的部分唤醒锁
                // 把整段圈进 CPU 醒着的时间，跑不完也不要留半套闹钟
                WakeLocks.withPartialWakeLock(
                    context,
                    "boot_rebuild",
                    // 开机这串重建（重排提醒 + 全量刷组件 + 排两个闹钟 + 建渠道）
                    // 远超一次单条提醒重排的开销，默认 5 秒会被提前收回
                    timeoutMs = 10_000L,
                ) {
                    // 逐步兜异常（同 BUAAApplication 的口径）：中间任何一步抛出，
                    // 后面的步骤照旧要跑 —— 整条链一把兜时，探测组件一抛
                    // 就等于这次开机既不建渠道也不排明日预告。
                    suspend fun step(label: String, block: suspend () -> Unit) {
                        runCatching { block() }.onFailure { Log.w(TAG, "开机重建失败：$label", it) }
                    }
                    // 重启把闹钟全清了：真正没主的遗留勿扰记录（下课铃与看门狗都随重启没了）
                    // 确实要开机自愈，但不能再无条件抢先恢复 —— 正在上课时那条记录是当下正要用的
                    // 自愈凭据。实测（emulator-5554，2026-09-18，一节 20:40–21:25 的课在上、
                    // dnd_during_class=true、课前提醒全关）：
                    //   20:50:47.085 ZenModeController →0（唤醒锁后 14ms，就是这一步）、
                    //   20:50:52.308 →2（5.2 秒后续排链排出已过期的上课铃重新进入）——
                    // 每收一次重建广播，正在上课的用户就被静音放开 5 秒（覆盖安装走同一接收器；
                    // 同审计 §2.9 / ai/T11 那一族的第二处）。
                    // 判据走 ClassProgressDnd 那侧唯一的期限口径（selfCheck：有记录且期限已过；
                    // 无记录照旧 no-op，绝不动用户自己的勿扰设置；旧记录缺期限照旧当场自愈）。
                    // 「记录还在但期限未到」交给紧随其后的重建链收口 —— 此刻没有课在进行时它
                    // 必然走到带判据的清理并把那枚 restore 调下去（行号取 ai/T15 之后的形状）：
                    //   日历模式 BackgroundSync.kt:107-131 一律只撤应用内课前提醒闹钟，课堂铃
                    //     那一半走两枚课堂开关的窄判据（清理的编排在 :208-225，守卫是 :218
                    //     那道 shouldCancelAllInCalendarMode）：两枚都关才走
                    //     ClassProgressScheduler.kt:519-529 那份完整撤除（:523 恢复勿扰）；
                    //     只要有一枚开着就整块跳过 —— 这条链在日历模式下照旧活着，
                    //     收口归下面最后那条 rescheduleWindows（:304-315 那份判据，不是这里）；
                    //   没配学期 / 课表为空 ReminderScheduler.kt:81-89（那条分支不看判据）→ 同上；
                    //   课前提醒全关（plan==null）ReminderScheduler.kt:100-136，
                    //     按 shouldTakeDownClassProgress 决定收不收，不收就交给续排链 → 同上；
                    //   有下一条提醒 ClassProgressScheduler.kt:283-317（挑不出窗口 :300-302 cancelAll；
                    //   课还没开始 :304-315 里的 :314 restore —— 开机新进程里不存在课前倒计时归属，
                    //   ReminderNotifications.kt:197-202 那两个变量都是进程内状态）。
                    // 真在上课则续排链重排出已过期的上课铃、立刻投递重新 enter，勿扰一秒都不掉。
                    step("dndSelfCheck") { ClassProgressDnd.selfCheck(context) }
                    // rescheduleReminders 的 Boolean 返回值代表「提醒链是否已接手课堂铃」，
                    // 开机时闹钟全清、返回值此前被直接丢弃 —— 用打包版补齐课堂铃重排
                    step("rescheduleReminders") { BackgroundSync.rescheduleRemindersAndBells(context) }
                    step("refreshWidgets") { BackgroundSync.refreshWidgets(context) }
                    step("scheduleWidgetMidnight") { BackgroundSync.scheduleWidgetMidnight(context) }
                    step("scheduleTomorrowPreview") { BackgroundSync.scheduleTomorrowPreview(context) }
                    step("ensureChannels") { ReminderNotifications.ensureChannels(context) }
                }
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
