package com.buaa.schedule.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.buaa.schedule.R
import com.buaa.schedule.domain.model.Course
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
                DayCell(dayName, weekGridDaySummary(dayCourses))
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

    // 位置 + 外观之外还要折进这一格真正画出来的文字：宿主按 id 缓存单元格，
    // id 不变就不看工厂新算的内容（改课后网格还是老课表，见 WidgetCommon.itemKey）。
    override fun getItemId(position: Int): Long {
        val cell = cells.getOrNull(position) ?: return WidgetCommon.itemKey(position, position.toLong())
        val content = (cell.dayName + cell.summary).hashCode()
        return WidgetCommon.itemKey(position, content.toLong() + appearance.viewIdStamp())
    }

    override fun hasStableIds(): Boolean = true

    private companion object {
        private const val TAG = "WeekGridFactory"
    }
}

/**
 * 4×2 紧凑网格一天最多画几行。
 *
 * 与 `widget_week_grid_item.xml` 的 `maxLines` 保持一致：这里按行数截断，
 * 布局按行数显示，两处不一致就会出现"最后一行被省略号吃掉"的错觉。
 */
internal const val WEEK_GRID_MAX_LINES = 5

/** 一格能容下的课程名长度（列宽约 30dp，9sp 下三四个字就到边了） */
internal const val WEEK_GRID_NAME_CHARS = 3

/**
 * 短名：别名优先（用户自己起的通常已经很短），去掉教务原名里的括号补充与空格，
 * 再截到 [WEEK_GRID_NAME_CHARS] 个字。
 */
internal fun weekGridShortName(name: String, maxChars: Int = WEEK_GRID_NAME_CHARS): String =
    name.substringBefore('(')
        .substringBefore('（')
        .replace(" ", "")
        .trim()
        .take(maxChars)

/**
 * 一格（一天）的摘要：每节课一行「节次号 + 短名」，装不下时最后一行换成「＋N」。
 *
 * 刻意**不带教室**：这一列只有约 30dp 宽，旧写法把「主楼A-101 高等数学 A」整句塞进去，
 * 在 9sp 下要折三四行，而 `maxLines` 一截断，用户看到的就是"一天只有一节课"（真机反馈）。
 * 教室在 4×2 这个尺寸里放不下，也不是这一格的价值——它回答"今天有几节课、第几节上什么"。
 */
internal fun weekGridDaySummary(
    courses: List<Course>,
    maxLines: Int = WEEK_GRID_MAX_LINES,
): String {
    if (courses.isEmpty()) return ""
    val shown = courses.take(maxLines).map { "${it.startPeriod}${weekGridShortName(it.displayName)}" }
    val hidden = courses.size - shown.size
    val lines = if (hidden > 0) shown.dropLast(1) + "＋$hidden" else shown
    return lines.joinToString("\n")
}