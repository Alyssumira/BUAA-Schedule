package com.buaa.schedule.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.os.Bundle

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

    private fun rerenderOnRebind(context: Context, appWidgetIds: IntArray) {
        val due = appWidgetIds.filterNot { throttled(it) }
        if (due.isEmpty()) return
        rerenderAfterRebind(context, due.toIntArray(), takeRebindPendingResult())
    }

    /**
     * 一次拖拽改尺寸会让宿主连着发好几轮 optionsChanged。
     * 按实例夹一道时间闸：重绘要读库、拼 RemoteViews，开了壁纸模糊还要生成位图，
     * 排成一串毫无意义。
     */
    private fun throttled(appWidgetId: Int): Boolean = synchronized(lastRerenderAt) {
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = lastRerenderAt[appWidgetId] ?: 0L
        if (now - previous < RERENDER_DEDUPE_MS) return@synchronized true
        lastRerenderAt[appWidgetId] = now
        false
    }

    private companion object {
        const val RERENDER_DEDUPE_MS = 1_500L

        /** 组件实例数量级很小，不需要 LRU；键是 appWidgetId，取值范围天然有限 */
        val lastRerenderAt = HashMap<Int, Long>()
    }
}
