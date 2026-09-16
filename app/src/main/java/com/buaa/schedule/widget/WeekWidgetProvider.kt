package com.buaa.schedule.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

class WeekWidgetProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        BackgroundSync.scheduleWidgetMidnight(context)
        WidgetFallbackWorker.ensure(context)
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
            ListWidgetMode.WEEK,
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
            WidgetCommon.updateAllOfProvider(context, WeekWidgetProvider::class.java, ListWidgetMode.WEEK)
    }
}
