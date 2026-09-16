package com.buaa.schedule.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
        runCatching {
            when (intent.getStringExtra(EXTRA_ACTION)) {
                ACTION_START -> {
                    val courseName = intent.getStringExtra(EXTRA_COURSE_NAME) ?: "课程"
                    val location = intent.getStringExtra(EXTRA_LOCATION)
                    val section = intent.getStringExtra(EXTRA_SECTION) ?: ""
                    val end = intent.getLongExtra(EXTRA_END, 0L)

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
                        ReminderNotifications.ensureChannels(context)
                        // 先发普通常驻通知作为保底（失败也能看到课程进行中），
                        // 再启动前台服务接管为 ProgressStyle 实况；二者使用同一通知 id，
                        // 服务启动成功后自然覆盖保底通知。
                        ReminderNotifications.postClassOngoing(
                            context = context,
                            courseId = intent.getLongExtra(EXTRA_COURSE_ID, 0L),
                            courseName = courseName,
                            location = location,
                            sectionText = section,
                            startMillis = intent.getLongExtra(EXTRA_START, 0L),
                            endMillis = end,
                        )
                        CourseFluidService.start(
                            context = context,
                            courseName = courseName,
                            location = location,
                            sectionText = section,
                            startMillis = intent.getLongExtra(EXTRA_START, 0L),
                            endMillis = end,
                        )
                    }
                    ClassProgressDnd.enter(context)
                }
                ACTION_END -> {
                    CourseFluidService.stop(context)
                    ReminderNotifications.cancelClassOngoing(context)
                    ClassProgressDnd.restore(context)
                    rescheduleNextWindow(context)
                }
            }
        }.onFailure { android.util.Log.w(TAG, "课程进行中广播处理失败", it) }
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
                ClassProgressScheduler.rescheduleNextWindow(context)
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
        const val EXTRA_COURSE_ID = "extra_course_id"
        const val EXTRA_COURSE_NAME = "extra_course_name"
        const val EXTRA_LOCATION = "extra_location"
        const val EXTRA_SECTION = "extra_section"
        const val EXTRA_START = "extra_start"
        const val EXTRA_END = "extra_end"
        const val PREFS_NAME = "schedule_settings"
        const val PREF_DND = "dnd_during_class"
        const val PREF_CLASS_PROGRESS = "class_progress_enabled"
    }
}
