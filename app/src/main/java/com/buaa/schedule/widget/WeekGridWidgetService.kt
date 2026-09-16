package com.buaa.schedule.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.buaa.schedule.R
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.startLocalDate
import com.buaa.schedule.domain.schedule.WeekCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 4x2 周网格组件的数据源：按教学日返回 7 个单元格。
 */
class WeekGridWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsService.RemoteViewsFactory =
        WeekGridFactory(applicationContext, intent)
}

class WeekGridFactory(
    private val context: Context,
    intent: Intent,
) : RemoteViewsService.RemoteViewsFactory {

    private val appWidgetId = intent.getIntExtra(
        android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID,
        android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID,
    )

    private data class DayCell(val dayName: String, val summary: String)

    private var cells: List<DayCell> = emptyList()
    private var appearance: WidgetAppearance = WidgetAppearance()

    /** 快照未命中时补数据用的后台作用域（onDataSetChanged 在主线程，不能自己查库） */
    private val refetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        val semester: Semester?
        val courses: List<com.buaa.schedule.domain.model.Course>
        val bindingCode = WidgetBindingStore.load(context, appWidgetId).semesterCode
        // 同 CourseListFactory：onDataSetChanged 在主线程，不能在这里阻塞查库。
        // 未命中就渲染空网格，**并自己补一次数据再通知**，不能干等着没人管（R5 F-17）。
        val data = WidgetDataCache.peek(bindingCode)
        appearance = WidgetAppearanceStore.load(context, appWidgetId)
        if (data == null) {
            Log.w(TAG, "快照未就绪，本次渲染空网格并异步补数据")
            cells = emptyList()
            refetchAndNotify(bindingCode)
            return
        }
        semester = data.semester
        courses = data.courses

        val week = semester?.startLocalDate?.let {
            WeekCalculator.currentWeekOrNull(it, semester.totalWeeks, LocalDate.now())
        }
        val names = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        cells = if (semester == null || week == null || courses.isEmpty()) {
            names.map { DayCell(it, "") }
        } else {
            names.mapIndexed { index, dayName ->
                val day = index + 1
                val dayCourses = courses.filter { it.dayOfWeek == day && it.weeks.contains(week) }
                    .sortedBy { it.startPeriod }
                val summary = dayCourses.joinToString("\n") { course ->
                    buildString {
                        course.location?.takeIf { it.isNotBlank() }?.let { append("$it ") }
                        append(course.name)
                    }
                }
                DayCell(dayName, summary)
            }
        }
    }

    private fun refetchAndNotify(bindingCode: String?) {
        refetchScope.launch {
            runCatching { WidgetDataCache.get(context, bindingCode) }
                .onSuccess { WidgetCommon.notifyGridDataChanged(context, appWidgetId) }
                .onFailure { Log.w(TAG, "异步补取组件数据失败，保持空态", it) }
        }
    }

    override fun onDestroy() {
        refetchScope.cancel()
        cells = emptyList()
    }

    override fun getCount(): Int = cells.size

    override fun getViewAt(position: Int): RemoteViews {
        val cell = cells[position]
        val views = RemoteViews(context.packageName, R.layout.widget_week_grid_item)
        views.setTextViewText(R.id.widget_grid_day, cell.dayName)
        views.setTextViewText(R.id.widget_grid_courses, cell.summary)
        val bg = appearance.resolvedBackground(context)
        views.setTextColor(R.id.widget_grid_day, appearance.titleColorFor(bg))
        views.setTextColor(R.id.widget_grid_courses, appearance.bodyColorFor(bg))
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    // 位置之外再叠加外观指纹：只改外观时位置不变，宿主会复用旧单元格视图，
    // 保存后看到的还是改之前的配色（同 CourseListFactory.getItemId）。
    override fun getItemId(position: Int): Long =
        position.toLong() + appearance.viewIdStamp()

    override fun hasStableIds(): Boolean = true

    private companion object {
        private const val TAG = "WeekGridFactory"
    }
}