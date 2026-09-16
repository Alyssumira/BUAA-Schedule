package com.buaa.schedule.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

class TodayWidgetProvider : AppWidgetProvider() {

    /**
     * 首个实例被添加时注册后台刷新链路。
     *
     * 此前只在 `onDisabled`（全部移除）里取消任务，却从没有注册的地方：
     * 零点闹钟与 12 小时兜底轮询都只在“数据变化/开机/时间变化”等事件里调度，
     * 冷启动装完组件后如果用户不再改课表，组件会一直停在放置当天的日期。
     */
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

    override fun onDisabled(context: Context) {
        // 最后一个实例被移除：没有任何 Widget 时取消全部后台刷新任务
        BackgroundSync.cancelWidgetMidnightIfNoWidgets(context)
    }

    companion object {
        suspend fun updateAll(context: Context) =
            WidgetCommon.updateAllOfProvider(context, TodayWidgetProvider::class.java, ListWidgetMode.TODAY)
    }
}
