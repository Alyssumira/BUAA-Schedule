package com.buaa.schedule.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/**
 * 2x1「下一节课」极简组件：一屏只回答「下一节是什么课、几点在哪」。
 * 外观配置与列表组件共用（背景色/透明度/圆角/文字颜色），数据走同一事件驱动刷新。
 */
class NextClassWidgetProvider : AppWidgetProvider() {

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
