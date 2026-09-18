package com.buaa.schedule.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.buaa.schedule.widget.WidgetCommon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 课程进行中通知的触发器：上课铃发常驻进度通知（可选联动勿扰），下课铃撤掉并恢复。
 *
 * 勿扰联动要求用户授予「勿扰访问权限」（特殊访问权限，非运行时权限），
 * 未授予时只发通知、不动勿扰——降级安全。
 */
class ClassProgressReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 广播回调跑在主线程，任何未捕获异常都会直接终止进程
        // （此前 scheduleEnd 的 SecurityException 正是这条路径）。
        // 这里做的都是"尽力而为"的副作用，失败只记录，不影响系统其它广播处理。
        // 外层再包一把部分唤醒锁：闹钟触发后系统派发广播自带的锁在 onReceive 返回时
        // 就可能松开，Doze 下这一段（发通知 + 排闹钟）会跑在随时睡回去的 CPU 上。
        WakeLocks.withPartialWakeLock(context, "class_progress") {
            runCatching {
                when (intent.getStringExtra(EXTRA_ACTION)) {
                    ACTION_START -> {
                        val window = windowOf(intent)
                        val end = window.endMillis

                        // 「课程进行中通知」与「上课自动勿扰」是两个独立开关：
                        // 常驻通知/前台服务受 PREF_CLASS_PROGRESS 控制；
                        // 勿扰与下课铃无论通知开关如何都要执行 ——
                        // 此前通知一关，勿扰也被连坐（这是"勿扰没用"的第二处根因）。
                        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        val classProgress = prefs.getBoolean(PREF_CLASS_PROGRESS, true)
                        // 下课铃**先排**：它是后面这一切（常驻通知 / 前台服务 / 勿扰）唯一的恢复路径。
                        // 排在 enter() 之后时，中间任何一步抛异常都会留下
                        // "手机已被静音、却没有下课铃来解除" 的状态（R5 F-31）。
                        ClassProgressScheduler.scheduleEnd(context, end)
                        if (classProgress) {
                            // 同一条实况窗口：先发 promoted 兜底通知，再交给前台服务接管。
                            // 两者共用同一通知 id，服务启动成功后自然覆盖兜底那条。
                            ReminderNotifications.startLiveWindow(
                                context = context,
                                window = window,
                                phase = LivePhase.IN_CLASS,
                            )
                        }
                        // 下课铃只是首选恢复路径，ROM 清闹钟时它会被吞：把下课时刻一并交给
                        // enter()，由它落恢复期限 + 排看门狗闹钟，静音不至于没有终点。
                        ClassProgressDnd.enter(context, end)
                        // 上课铃这一刻正是"上一节课 → 这一节课"的转折点：
                        // 今日列表里的「进行中」标记和「下一节课」的倒计时都必须跟着翻面。
                        WidgetCommon.requestLiveRefresh(context)
                        // 这条课的课前提醒到点作废：它只在下发时求值过一次，没人会再发第二遍。
                        // 撤销的活儿归这一刻，因为这里本来就是它使命结束的地方。
                        ReminderNotifications.cancelCourseReminder(context, window.courseId)
                    }
                    ACTION_END -> {
                        CourseFluidService.stop(context)
                        ReminderNotifications.cancelClassOngoing(context)
                        ClassProgressDnd.restore(context)
                        rescheduleNextWindow(context)
                        // 同 ACTION_START：下课即"这一节不再是进行中"，且下一节课换了对象。
                        WidgetCommon.requestLiveRefresh(context)
                    }
                    // 看门狗：下课铃被省电策略吞掉时的强制恢复。
                    // selfCheck 内部才判"有残留记录 + 已过期限"，正常情况下它什么都不做。
                    ACTION_DND_WATCHDOG -> ClassProgressDnd.selfCheck(context)
                }
            }.onFailure { android.util.Log.w(TAG, "课程进行中广播处理失败", it) }
        }
    }

    /**
     * 下课之后续排下一个课堂窗口（要查库，不能在主线程的广播回调里做）。
     *
     * 对关掉了课前提醒的用户，这是唯一的再排程入口 ——
     * [ReminderScheduler.rescheduleAll] 由课前提醒闹钟驱动，提醒全关时它不排课堂窗口。
     * 少了这一步，实况与上课自动勿扰在第一节课之后就再也不会出现。
     */
    private fun rescheduleNextWindow(context: Context) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // goAsync 只保证进程存活、不保证 CPU 持续唤醒：续排要查三张表再重排闹钟，
                // Doze 下没锁就会中途睡回去，链条断在这里（同 ReminderReceiver）
                WakeLocks.withPartialWakeLock(context, "class_reschedule") {
                    ClassProgressScheduler.rescheduleNextWindow(context)
                }
            } catch (e: CancellationException) {
                // 协程取消是控制流信号，必须继续向上传播，不能吞掉
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "续排下一节课堂窗口失败", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ClassProgressReceiver"
        const val EXTRA_ACTION = "extra_action"
        const val ACTION_START = "class_start"
        const val ACTION_END = "class_end"

        /**
         * 勿扰看门狗：由 [ClassProgressDnd.enter] 排在「下课 + 宽限」时刻，
         * 下课铃被 ROM 吞掉时把整机从「完全静默」里拉回来。
         * 与上/下课铃共用本接收器（同一进程边界，不需要新的清单声明）。
         */
        const val ACTION_DND_WATCHDOG = "class_dnd_watchdog"
        const val PREFS_NAME = "schedule_settings"
        const val PREF_DND = "dnd_during_class"
        const val PREF_CLASS_PROGRESS = "class_progress_enabled"

        /**
         * 从闹钟 extras 还原出这一节课的窗口。
         *
         * 键表与序列化只在 [ClassProgressScheduler.ClassWindow.from] 一处：实况通知、
         * 超级岛模板、桌面组件三条渲染链都要同一批字段，每处各取一次就各漏一次
         * （teacher 正是这样在整个实况链路上消失的）。
         */
        fun windowOf(intent: Intent): ClassProgressScheduler.ClassWindow =
            ClassProgressScheduler.ClassWindow.from(intent.extras)
    }
}
