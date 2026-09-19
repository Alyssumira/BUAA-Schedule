package com.buaa.schedule.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 4x2 紧凑周视图组件：7 列 GridView，每列是一个教学日。
 */
class WeekGridWidgetProvider : ScheduleAppWidgetProvider() {

    /** 宿主重绑后按「4×2 网格」口径重绘（见 [ScheduleAppWidgetProvider]） */
    override fun rerenderAfterRebind(
        context: Context,
        appWidgetIds: IntArray,
        pendingResult: BroadcastReceiver.PendingResult?,
    ) {
        WidgetCommon.goAsyncUpdateWeekGrid(context, appWidgetIds, pendingResult)
    }

    /**
     * 表头「上周 / 下周」的广播走显式组件意图直达这里：
     * 显式意图不受 manifest intent-filter 约束，所以不必为它声明新 action。
     * 4×2 不是列表模式，[ListWidgetMode] 传 null 表示"重绘网格"。
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == WidgetNavigation.ACTION_BROWSE_WEEK) {
            WidgetCommon.goAsyncBrowseWeek(context, intent, goAsync(), mode = null)
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // 幂等补注册由 WidgetCommon 在后台协程里做，不占 onUpdate 的主线程
        WidgetCommon.goAsyncUpdateWeekGrid(
            context,
            appWidgetIds,
            goAsync(),
        )
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        WidgetAppearanceStore.remove(context, appWidgetIds)
        WidgetBindingStore.remove(context, appWidgetIds)
    }

    companion object {
        suspend fun updateAll(context: Context) =
            WidgetCommon.updateAllOfProviderWeekGrid(context, WeekGridWidgetProvider::class.java)
    }
}