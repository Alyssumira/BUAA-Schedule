package com.buaa.schedule.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context

/**
 * 2x1「下一节课」极简组件：一屏只回答「下一节是什么课、几点在哪」。
 * 外观配置与列表组件共用（背景色/透明度/圆角/文字颜色），数据走同一事件驱动刷新。
 */
class NextClassWidgetProvider : ScheduleAppWidgetProvider() {

    /** 宿主重绑后按「下一节课」口径重绘（见 [ScheduleAppWidgetProvider]） */
    override fun rerenderAfterRebind(
        context: Context,
        appWidgetIds: IntArray,
        pendingResult: BroadcastReceiver.PendingResult?,
    ) {
        WidgetCommon.goAsyncUpdateNext(context, appWidgetIds, pendingResult)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // 与 TodayWidgetProvider 同口径：onEnabled 在广播主线程上跑，而
        // hasAnyWidget 的 binder 调用与 WorkManager.getInstance 都可能抛异常，
        // 必须走带逐步 runCatching 的那一份。
        WidgetCommon.bootstrapBackgroundSync(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // 幂等补注册由 WidgetCommon 在后台协程里做，不占 onUpdate 的主线程
        WidgetCommon.goAsyncUpdateNext(
            context,
            appWidgetIds,
            goAsync(),
        )
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        WidgetAppearanceStore.remove(context, appWidgetIds)
        // appWidgetId 会被系统复用，残留的课表绑定会让新组件显示成旧学期
        WidgetBindingStore.remove(context, appWidgetIds)
    }

    override fun onDisabled(context: Context) {
        BackgroundSync.cancelWidgetMidnightIfNoWidgets(context)
    }

    companion object {
        suspend fun updateAll(context: Context) =
            WidgetCommon.updateAllOfProviderNext(context, NextClassWidgetProvider::class.java)
    }
}
