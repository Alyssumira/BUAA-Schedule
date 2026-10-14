package com.buaa.schedule.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.buaa.schedule.domain.widget.WidgetRerenderSentinel
import com.buaa.schedule.domain.widget.WidgetRerenderWindow
import com.buaa.schedule.domain.widget.WidgetRerenderWindowGate

/**
 * 各桌面组件 Provider 的共同底座。
 *
 * 只为一件事存在（真机反馈：「编辑保存后会刷新，但是长按后又会变为原样」）：
 * 长按进编辑模式、宿主恢复、拖拽改尺寸这些动作会让 Launcher **重新绑定**这个实例，
 * 重绑的第一帧画的是 `initialLayout` —— 我们那里放的是带示例课程的静态预览
 * （R5 F-27：线上布局是纯白底 + 运行时着色，静态渲染出来是白底白字），
 * 于是组件看着像"配置被改了回去"。
 *
 * 宿主重绑后为数不多的可靠通知点是 [onAppWidgetOptionsChanged]（宿主带着
 * `EXTRA_OPTIONS` 发 UPDATE 时走这里）与 [onRestored]（API 34+，需 manifest 里
 * 声明 `ACTION_APPWIDGET_RESTORED`）。两个回调每个 Provider 都要写、内容又完全同构，
 * 所以收在这里：子类只交出"重绑之后怎么重绘我这一类组件"。
 */
abstract class ScheduleAppWidgetProvider : AppWidgetProvider() {

    /**
     * 重绑后补一次重绘。实现里**不要**碰 `goAsync()` —— 见 [takeRebindPendingResult]。
     */
    protected abstract fun rerenderAfterRebind(
        context: Context,
        appWidgetIds: IntArray,
        pendingResult: BroadcastReceiver.PendingResult?,
    )

    /**
     * 一次广播只有一份 PendingResult，而宿主把"改尺寸/改 options"发成
     * **带 EXTRA_OPTIONS 的 UPDATE** 时，AOSP 的 onReceive 会先派
     * [onAppWidgetOptionsChanged] 再派 `onUpdate` —— 两个回调都要重绘同一批实例。
     * 票只给先到的那一个，后一个拿到的就是 null（`goAsync()` 第二次调用只会返回 null，
     * 不会抛），重绘照样跑，只是不再替进程续命。
     *
     * 续命窗口对这次补绘够用：数据走 [WidgetDataCache] 的快照，渲染是毫秒级；
     * 真被回收了，下一次事件或宿主再次绑定会补上。
     */
    private var rebindPendingResultTaken = false

    private fun takeRebindPendingResult(): BroadcastReceiver.PendingResult? {
        if (rebindPendingResultTaken) return null
        rebindPendingResultTaken = true
        return goAsync()
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        rerenderOnRebind(context, intArrayOf(appWidgetId))
    }

    // 不调 super：AppWidgetProvider 的默认实现是空的，而 onRestored 在 API 34
    // 以下的框架里根本不存在，老机器上留一个 super 调用只是埋雷。
    override fun onRestored(
        context: Context,
        appWidgetIds: IntArray,
        restoredAppWidgetIds: IntArray,
    ) {
        rerenderOnRebind(context, restoredAppWidgetIds)
    }

    /**
     * 首个实例被添加：补注册后台刷新链（零点闹钟 + 12 小时兜底任务）。
     *
     * 这一步不能省：零点闹钟与兜底轮询的其它事件入口只有"数据变化 / 开机 / 时间变化"，
     * 装完组件后用户不再改课表的话，一个事件都不会来 —— 组件会一直停在放置当天。
     *
     * 六家 Provider 此前各写一份、内容逐字相同，收在这里之后新增一种组件不可能漏。
     * 走 `*FromReceiver` 而不是同步那一份的原因见
     * [WidgetCommon.bootstrapFromReceiver]：`onEnabled` 跑在**广播主线程**上，
     * 而这条链要碰 `WorkManager.getInstance`（T18 起 WorkManager 是按需初始化的，
     * 谁第一个调它谁就地付建库的钱）与跨 binder 的组件探测。
     * 异常同样由那一份的 [WidgetCommon] 口径吞掉：从 `onReceive` 逃出去就是当场崩溃，
     * 组件停在半初始化态。
     */
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetCommon.bootstrapFromReceiver(this, context)
    }

    /**
     * 最后一个实例被移除：撤零点闹钟、退出旧版周期任务，并把兜底任务交回
     * `ensure()` 重新判定（组件没了但提醒仍走应用内闹钟时要留着）。
     * 线程口径与 [onEnabled] 同一条链、同一个理由。
     */
    override fun onDisabled(context: Context) {
        WidgetCommon.cancelMidnightIfNoWidgetsFromReceiver(this, context)
    }

    private fun rerenderOnRebind(context: Context, appWidgetIds: IntArray) {
        val due = appWidgetIds.filterNot { throttled(it) }
        if (due.isEmpty()) return
        rerenderAfterRebind(context, due.toIntArray(), takeRebindPendingResult())
    }

    /**
     * 一次拖拽改尺寸会让宿主连着发好几轮 optionsChanged。
     * 按实例夹一道时间闸：重绘要读库、拼 RemoteViews，开了壁纸模糊还要生成位图，
     * 排成一串毫无意义。
     *
     * 改前那两手逐字留在这里做取证（源码形状守卫按「代码里 0 处、含注释 1 处」双向复算）：
     * ```
     * val previous = lastRerenderAt[appWidgetId] ?: 0L
     * if (now - previous < RERENDER_DEDUPE_MS) return@synchronized true
     * ```
     * 那一形的病是「这一枚实例从没补绘过」与「刚刚在开机那一瞬补绘过」共用同一枚 `0L`，
     * 而 `elapsedRealtime()` 开机那一刻正好从 0 起走；两条结论还都不留日志。
     * 分档判据本体住在 `domain/widget/WidgetRerenderWindowGate`，这里只按档位分派。
     *
     * ⚠️ 可达性如实记账（⓪ 段裁定）：这道窗只有 1_500 毫秒，而两个入口都要等系统起完、
     * 桌面起完、宿主投递、本进程被拉起来才走得到 —— 开机刻度内 1.5 秒的那一格本仓零设备读数。
     * 所以这一枚按**形状卫生**处理：换哨兵、抽内核、两档各留痕、把顺序钉死，不主张用户可见后果。
     *
     * @return `true` = 这一枚实例这次不补绘
     */
    private fun throttled(appWidgetId: Int): Boolean =
        when (val window = claimRerenderWindow(appWidgetId)) {
            is WidgetRerenderWindow.GrantedFirstRerender -> {
                Log.i(TAG, "组件重绑补绘放行 本进程首次: ${rerenderWindowTrace(window)}")
                false
            }

            is WidgetRerenderWindow.GrantedAfterWindow -> {
                Log.i(TAG, "组件重绑补绘放行 出窗: ${rerenderWindowTrace(window)}")
                false
            }

            is WidgetRerenderWindow.SentinelContradiction -> {
                // 旗与哨兵互相打脸 = 领取这一族的形状病了，不是这一帧真的画过了。
                // 宁可多画一遍，也不许让组件停在 initialLayout 那白底白字上，所以照放行且告警。
                Log.w(TAG, "组件重绑补绘 哨兵自相矛盾 仍按放行处理: ${rerenderWindowTrace(window)}")
                false
            }

            is WidgetRerenderWindow.SuppressedAsDuplicate -> {
                // 去重本业：同窗口内的重复那一条。跳过重绘，但这一手要说得出话
                // （HyperOS 把 logcat 截在 Info 级 ⇒ Log.i）
                Log.i(
                    TAG,
                    "组件重绑补绘被去重压掉 同窗口内的重复: ${rerenderWindowTrace(window)}",
                )
                true
            }

            is WidgetRerenderWindow.SuppressedClockInverted -> {
                // 递进来的读数倒退：拿错尺的形状，不是「刚刚补绘过」，不许共用那一句
                Log.w(
                    TAG,
                    "组件重绑补绘跳过 单调钟读数倒退: ${rerenderWindowTrace(window)}",
                )
                true
            }
        }

    /**
     * 领取这一枚实例这一次的补绘配额，交回判据的分档结论（**不许折成一枚 Boolean**）。
     *
     * 计时用 `elapsedRealtime`（单调）而不是墙上时钟：宿主改配置与恢复都可能在改时间之后
     * 立刻到达，拿被改的时钟判窗口等于判不出来。判据不吃钟：读数在这里现取，当参数递进去。
     *
     * 「补绘过没有」这一格**由那一枚读数现推**（读不到 = 从没补绘过），不与哨兵分居两处；
     * 整块读改写在 `synchronized` 里 —— 同一次拖拽连发的那几轮必须只有一轮领得到。
     */
    private fun claimRerenderWindow(appWidgetId: Int): WidgetRerenderWindow =
        synchronized(lastRerenderAt) {
            val now = android.os.SystemClock.elapsedRealtime()
            val previous = lastRerenderAt[appWidgetId]
            val window = WidgetRerenderWindowGate.decide(
                nowElapsedRealtime = now,
                lastClaimedAt = previous ?: NEVER_RERENDERED_MILLIS,
                windowMillis = RERENDER_DEDUPE_MS,
                hasEverRerendered = previous != null,
            )
            val commitAt = rerenderCommitAtOrNull(window)
            if (commitAt != null) lastRerenderAt[appWidgetId] = commitAt
            window
        }

    /**
     * 这一档要不要把该实例那一格推到新的读数；`null` = 没占着配额，那一格一格都不许动。
     * 五档逐枚点名、不带 `else`：判据新增一档时这里必须跟着表态。
     */
    private fun rerenderCommitAtOrNull(window: WidgetRerenderWindow): Long? = when (window) {
        is WidgetRerenderWindow.GrantedFirstRerender -> window.claimedAtElapsedRealtime
        is WidgetRerenderWindow.GrantedAfterWindow -> window.claimedAtElapsedRealtime
        is WidgetRerenderWindow.SentinelContradiction -> window.nowElapsedRealtime
        is WidgetRerenderWindow.SuppressedAsDuplicate,
        is WidgetRerenderWindow.SuppressedClockInverted -> null
    }

    /** 取证行：把档位与它据以判断的读数原样念出来（Info/Warn 两级，release 里不许被削掉） */
    private fun rerenderWindowTrace(window: WidgetRerenderWindow): String = when (window) {
        is WidgetRerenderWindow.GrantedFirstRerender ->
            "本实例首次领取 nowElapsed=${window.claimedAtElapsedRealtime}"

        is WidgetRerenderWindow.GrantedAfterWindow ->
            "出窗放行 nowElapsed=${window.claimedAtElapsedRealtime} " +
                "距上次补绘=${window.elapsedSinceLastClaimMillis}ms"

        is WidgetRerenderWindow.SuppressedAsDuplicate ->
            "距上次补绘=${window.elapsedSinceLastClaimMillis}ms 窗口=${window.windowMillis}ms"

        is WidgetRerenderWindow.SuppressedClockInverted ->
            "nowElapsed=${window.nowElapsedRealtime} lastClaimed=${window.lastClaimedAt}"

        is WidgetRerenderWindow.SentinelContradiction ->
            "hasEverRerendered=${window.hasEverRerendered} lastClaimed=${window.lastClaimedAt} " +
                "nowElapsed=${window.nowElapsedRealtime}"
    }

    private companion object {
        const val TAG = "ScheduleAppWidgetProvider"

        const val RERENDER_DEDUPE_MS = 1_500L

        /**
         * 与判据同源的「没有上一次」哨兵。这里只是别名 —— 本文件不许另写一枚负数字面量，
         * 否则刻度与判据两头各有一套「从没补绘过」。
         */
        private const val NEVER_RERENDERED_MILLIS = WidgetRerenderSentinel.NEVER_RERENDERED_MILLIS

        /** 组件实例数量级很小，不需要 LRU；键是 appWidgetId，取值范围天然有限 */
        val lastRerenderAt = HashMap<Int, Long>()
    }
}
