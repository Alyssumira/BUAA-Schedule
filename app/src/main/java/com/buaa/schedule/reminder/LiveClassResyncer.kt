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
            // 一次判断一只钟（#338）：decide 与下面那条"是不是还在数这节课"共用这一次读数。
            // 此前谓词自己在体内读真钟，而这里已经有一只钟 —— 两次读数之间是查库与整轮
            // 窗口搜索（毫秒到几十毫秒级），跨秒那一瞬两处会先掉个儿：注入的那只说还在数、
            // 真钟说已经开始 ⇒ 刚上岛的课前倒计时被当成遗留收掉（勿扰同点被放开）。
            val nowMillis = System.currentTimeMillis()
            when (val action = decide(window, nowMillis)) {
                ResyncAction.ClearLeftovers -> {
                    // "课还没开始"不等于遗留：正为数着这节课的课前倒计时就挂在岛上，
                    // 判据与 rescheduleWindows 共用，口径不能两边各写一份。
                    // 取哪一格是上课时刻也不各写一份：这里的 window 是
                    // planNextClassWindow 那种"start=上课 / end=下课"的课堂计划窗口，
                    // 一律走 ReminderNotifications.classStartOf（写侧那一型走 countdownAnchorOf）。
                    // 与那一处的分工也不同：这里时间判还得留着 —— ClearLeftovers 也包含
                    // "这一节已经结束"那一档，只看归属会让下课铃被吞的遗留永远收不掉（R5 F-11）。
                    val countingDown = window != null &&
                        ReminderNotifications.isCountingDownTo(
                            window.courseId,
                            ReminderNotifications.classStartOf(window),
                            nowMillis,
                        )
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

    /**
     * 此刻「尚未结束的最早一次课」，含正在上的这一节；口径与调度器完全一致。
     *
     * "完全一致"到今天为止才真是这句话：排铃那两条链（`rescheduleWindows` 与
     * `shouldTakeDownClassProgress`）一直显式递学期上界，这一枚却吃了
     * `planNextClassWindow` 的默认值那一档（= 明说不判上界）。默认值是**展示侧**的
     * 历史包袱，而这里是一枚会排闹钟的调用点 —— [startLive] 紧随其后就 `scheduleEnd`
     * 补一枚下课铃，那枚铃到点撤的是全仓唯一那枚课堂实况通知 id 并恢复勿扰
     * （`ReminderNotifications.NOTIFY_ID_CLASS_PROGRESS` + `ClassProgressReceiver.ACTION_END`）。
     * 于是 `totalWeeks` 少报时，一节**周课表上根本画不到**的课能靠"打开 App"补起实况，
     * 并把另一节真课的实况与勿扰一起拆掉（#332，T214/#263 那本账的跨卡残账）。
     *
     * 取不到的那一档照 [com.buaa.schedule.domain.schedule.TermWeekGate] 的三条口径答：
     * 没有学期行 / 学期起始日解析不出来 ⇒ 上面两枚 `?: return null`，判不出就是不挑窗口
     * （走 ClearLeftovers 收干净）；学期行在但 `totalWeeks` 是脏值 ⇒ 由判据裁掉越界周次。
     * 这里**不许**退到不判上界那一档：排铃侧宁漏的代价是"少挂一次实况"，
     * 而误挂的代价是"把真课的勿扰拆了"。
     */
    private suspend fun nextWindow(context: Context): ClassProgressScheduler.ClassWindow? {
        val repository = context.scheduleRepository()
        val semester = repository.getCurrentSemester() ?: return null
        val semesterStart = semester.startLocalDate ?: return null
        return ClassProgressScheduler.planNextClassWindow(
            courses = repository.getDisplayCourses(semester),
            semesterStart = semesterStart,
            timeSlots = repository.getTimeSlots(),
            now = LocalDateTime.now(),
            totalWeeks = semester.totalWeeks,
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
