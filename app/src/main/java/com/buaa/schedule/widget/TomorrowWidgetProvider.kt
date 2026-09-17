package com.buaa.schedule.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context

class TomorrowWidgetProvider : ScheduleAppWidgetProvider() {

    /** 宿主重绑后按「明日课程」口径重绘（见 [ScheduleAppWidgetProvider]） */
    override fun rerenderAfterRebind(
        context: Context,
        appWidgetIds: IntArray,
        pendingResult: BroadcastReceiver.PendingResult?,
    ) {
        WidgetCommon.goAsyncUpdate(context, appWidgetIds, pendingResult, ListWidgetMode.TOMORROW)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // 与 TodayWidgetProvider 同口径：onEnabled 在广播主线程上跑，而
        // hasAnyWidget 的 binder 调用与 WorkManager.getInstance 都可能抛异常。
        WidgetCommon.bootstrapBackgroundSync(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // 幂等补注册由 WidgetCommon 在后台协程里做，不占 onUpdate 的主线程
        WidgetCommon.goAsyncUpdate(
            context,
            appWidgetIds,
            goAsync(),
            ListWidgetMode.TOMORROW,
        )
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        WidgetAppearanceStore.remove(context, appWidgetIds)
        // appWidgetId 会被系统复用，残留的课表绑定会让新组件显示成旧学期
        WidgetBindingStore.remove(context, appWidgetIds)
    }

    override fun onDisabled(context: Context) {
        // 最后一个实例被移除：没有任何 Widget 时取消全部后台刷新任务
        BackgroundSync.cancelWidgetMidnightIfNoWidgets(context)
    }

    companion object {
        suspend fun updateAll(context: Context) =
            WidgetCommon.updateAllOfProvider(context, TomorrowWidgetProvider::class.java, ListWidgetMode.TOMORROW)
    }
}
