package com.buaa.schedule.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context

class TodayWidgetProvider : ScheduleAppWidgetProvider() {

    /** 宿主重绑后按「今日」口径重绘（见 [ScheduleAppWidgetProvider]） */
    override fun rerenderAfterRebind(
        context: Context,
        appWidgetIds: IntArray,
        pendingResult: BroadcastReceiver.PendingResult?,
    ) {
        WidgetCommon.goAsyncUpdate(context, appWidgetIds, pendingResult, ListWidgetMode.TODAY)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // 幂等补注册（部分 ROM 不回调 onEnabled）由 WidgetCommon 在后台协程里做，
        // 别在 onUpdate 的主线程上敲 AlarmManager/WorkManager
        WidgetCommon.goAsyncUpdate(
            context,
            appWidgetIds,
            goAsync(),
            ListWidgetMode.TODAY,
        )
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // 实例被移除：清掉它的外观与课表绑定配置，避免 prefs 无限增长。
        // 绑定也必须清：appWidgetId 会被系统复用，残留的绑定会让新组件
        // 悄悄显示成一个已经不存在的学期。
        WidgetAppearanceStore.remove(context, appWidgetIds)
        WidgetBindingStore.remove(context, appWidgetIds)
    }

    companion object {
        suspend fun updateAll(context: Context) =
            WidgetCommon.updateAllOfProvider(context, TodayWidgetProvider::class.java, ListWidgetMode.TODAY)
    }
}
