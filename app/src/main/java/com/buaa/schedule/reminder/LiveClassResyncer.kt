package com.buaa.schedule.reminder

import android.content.Context
import android.util.Log
import com.buaa.schedule.data.repository.scheduleRepository
import com.buaa.schedule.domain.model.startLocalDate
import java.time.LocalDateTime

/**
 * Sleepy 式「状态驱动兜底」（调研结论见 BUAA_ROM_ADAPTATION_2026-09-15 的 P1-A）。
 *
 * 此前「课程进行中」实况完全依赖上课铃那一秒的闹钟投递：国产 ROM 的省电策略
 * 把闹钟吞掉后，用户哪怕在上课期间打开 App，实况也永远不会出现。
 * 这里的补救思路（与 [ReminderScheduler] 的闹钟重排互补）：
 * **不猜闹钟是否送达，只看当下状态**——App 进入前台的此刻如果正处于课堂窗口内、
 * 用户开着「课程进行中」、而实况服务没在跑，就当场把实况补起来，
 * 并把多半同样被吞的下课铃补排上。
 *
 * 反向同样要补：此刻已经没有课在进行，却还挂着常驻通知 / 勿扰记录，
 * 说明下课铃也被吞了 —— 顺手收干净，否则手机会静默一整天。
 *
 * 在 MainActivity.onStart 里调用；全部失败路径静默（尽力而为的副作用）。
 */
object LiveClassResyncer {

    private const val TAG = "LiveClassResyncer"

    /** 前台校准的动作决定（纯函数，见 LiveClassResyncerTest） */
    internal sealed interface ResyncAction {
        /** 此刻没有课在进行：收掉下课铃被吞后残留的实况与勿扰 */
        data object ClearLeftovers : ResyncAction

        /** 正在上课：补起实况并补排下课铃 */
        data class StartLive(val window: ClassProgressScheduler.ClassWindow) : ResyncAction
    }

    suspend fun resync(context: Context) {
        runCatching {
            // 服务在跑就说明上课铃正常送达（或上一轮已补过），直接退出，避免每次进前台都查库
            if (CourseFluidService.isRunning) return
            val window = nextWindow(context)
            when (val action = decide(window, System.currentTimeMillis())) {
                ResyncAction.ClearLeftovers -> {
                    // "课还没开始"不等于遗留：正为数着这节课的课前倒计时就挂在岛上，
                    // 判据与 rescheduleWindows 共用，口径不能两边各写一份
                    val countingDown = window != null &&
                        ReminderNotifications.isCountingDownTo(window.courseId, window.startMillis)
                    if (!countingDown) {
                        ClassProgressDnd.restore(context)
                        ReminderNotifications.cancelClassOngoing(context)
                    }
                }
                is ResyncAction.StartLive -> if (liveWorthPosting(context)) startLive(context, action.window)
            }
        }.onFailure { Log.w(TAG, "状态驱动实况校准失败", it) }
    }

    /**
     * 纯函数：由「此刻尚未结束的最早一次课」与当前时刻决定要不要补实况。
     *
     * 没有窗口（无课表 / 学期已结束）或窗口还没开始，都说明此刻没有课在进行 ——
     * 此时若还挂着常驻通知与勿扰记录，就是下课铃被 ROM 吞掉的证据，必须收干净；
     * 少这一步，手机会一直静默到下一节课的上课铃（R5 F-15）。
     */
    internal fun decide(
        window: ClassProgressScheduler.ClassWindow?,
        nowMillis: Long,
    ): ResyncAction = when {
        // "不在进行中"只有一句：开课含、下课不含的口径在 ClassWindow.ongoingAt 里
        window == null || !window.ongoingAt(nowMillis) -> ResyncAction.ClearLeftovers
        else -> ResyncAction.StartLive(window)
    }

    private fun startLive(context: Context, window: ClassProgressScheduler.ClassWindow) {
        // 与上课铃、课前提醒同一条入口：兜底通知 + 前台服务，课前课中课后共用一套形状
        ReminderNotifications.startLiveWindow(
            context = context,
            window = window,
            phase = LivePhase.IN_CLASS,
        )
        // 上课铃被吞时下课铃多半也丢了：补一个，否则通知与勿扰只能等服务自杀收场
        ClassProgressScheduler.scheduleEnd(context, window.endMillis)
        Log.d(TAG, "课堂窗口内补起课程实况：${window.courseName}")
    }

    /** 此刻「尚未结束的最早一次课」，含正在上的这一节；口径与调度器完全一致 */
    private suspend fun nextWindow(context: Context): ClassProgressScheduler.ClassWindow? {
        val repository = context.scheduleRepository()
        val semester = repository.getCurrentSemester() ?: return null
        val semesterStart = semester.startLocalDate ?: return null
        return ClassProgressScheduler.planNextClassWindow(
            courses = repository.getDisplayCourses(semester),
            semesterStart = semesterStart,
            timeSlots = repository.getTimeSlots(),
            now = LocalDateTime.now(),
        )
    }

    /** 开关关着或通知发不出去时，补实况是空转；但遗留状态的清理不受这两者约束 */
    private fun liveWorthPosting(context: Context): Boolean {
        val prefs =
            context.getSharedPreferences(ClassProgressReceiver.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(ClassProgressReceiver.PREF_CLASS_PROGRESS, true)) return false
        // 通知权限没开时，实况既发不出也提不上岛，补了也是空的。
        // 只检查「课程进行中」渠道：用户关掉普通课程提醒不应连累实况兜底（R4 P2-1）。
        return ReminderNotifications.canPostNotifications(
            context,
            ReminderNotifications.CHANNEL_CLASS_PROGRESS,
        )
    }
}
