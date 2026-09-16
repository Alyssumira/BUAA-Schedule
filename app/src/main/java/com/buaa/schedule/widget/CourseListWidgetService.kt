package com.buaa.schedule.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.buaa.schedule.R
import com.buaa.schedule.core.designsystem.courseColor
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.periodLabel
import com.buaa.schedule.domain.model.startLocalDate
import com.buaa.schedule.domain.schedule.WeekCalculator
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 列表型组件（今日/明日/本周）的数据源。
 *
 * 为什么用 RemoteViewsService 而不是把课程拼进一个大 TextView：
 * - 组件高度由用户任意调整，固定文本会被截断（此前 `take(400)`）；
 * - 列表行可带课程色条、教室、节次、上课时间，信息密度远高于拼接文本；
 * - `setEmptyView` 能把"今天没有课 / 假期中"做成正式空态。
 *
 * `onDataSetChanged` 在后台线程执行，允许直接读 Room。
 */
class CourseListWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsService.RemoteViewsFactory =
        CourseListFactory(applicationContext, intent)
}

class CourseListFactory(
    private val context: Context,
    intent: Intent,
) : RemoteViewsService.RemoteViewsFactory {

    private val mode: ListWidgetMode = ListWidgetMode.entries.getOrElse(
        intent.getIntExtra(EXTRA_MODE, ListWidgetMode.TODAY.ordinal),
    ) { ListWidgetMode.TODAY }

    /** 本实例的 appWidgetId：用于读取该实例的外观配置（行文字色） */
    private val appWidgetId: Int = intent.getIntExtra(
        android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID,
        android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID,
    )

    private var rows: List<Row> = emptyList()

    /** 快照未命中时补数据用的后台作用域（onDataSetChanged 在主线程，不能自己查库） */
    private val refetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 实例级外观：行文字色需要它才能跟随配置（onDataSetChanged 里刷新） */
    private var appearance: WidgetAppearance = WidgetAppearance()

    /** 一行课程：列表项渲染所需的最小信息 */
    private data class Row(
        val courseId: Long,
        val name: String,
        val location: String?,
        val periods: List<Int>,
        val startTime: String?,
        val color: Int,
        val dayTag: String?,
    )

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        val semester: Semester?
        val courses: List<Course>
        val timeByPeriod: Map<Int, String>
        val bindingCode = WidgetBindingStore.load(context, appWidgetId).semesterCode
        // ⚠️ onDataSetChanged 跑在**应用主线程**（RemoteViewsService 的约定），
        // 这里 runBlocking 查库 = 直接卡 UI 线程，组件每次刷新都会触发一次。
        // 正常路径 provider 已先走 WidgetDataCache.get 备好数据，peek() 必定命中；
        // 未命中（多学期实例互相挤掉、冷启动首次）时渲染空态**并自己补数据再通知一次**，
        // 不能干等着没人管（R5 F-17）。
        val data = WidgetDataCache.peek(bindingCode)
        if (data == null) {
            Log.w(TAG, "快照未就绪，本次渲染空态并异步补数据")
            rows = emptyList()
            appearance = WidgetAppearanceStore.load(context, appWidgetId)
            // 只画空态是不够的：没有第二次 notify 的话，这个实例会一直"今天没有课"，
            // 直到零点闹钟或 12 小时兜底（R5 F-17）。在允许阻塞的 IO 线程里补数据，
            // 补到之后再触发一次 onDataSetChanged。
            refetchAndNotify(bindingCode)
            return
        }
        semester = data.semester
        courses = data.courses
        timeByPeriod = data.timeSlots.associateBy({ it.number }, { it.startTime })

        val today = LocalDate.now()
        // 每行取数必须用**目标日期**对应的教学周：明日组件的课要按"明天"的周次过滤，
        // 否则周日晚上看「明日课程」会显示成本周同一节次的课（单双周下内容不同）。
        val targetDate = if (mode == ListWidgetMode.TOMORROW) today.plusDays(1) else today
        val week = currentWeekOrNull(semester, if (mode == ListWidgetMode.WEEK) today else targetDate)

        val dayCourses: List<Pair<Course, String?>> = when {
            semester != null && week == null -> emptyList() // 假期中
            mode == ListWidgetMode.WEEK -> {
                val target = week
                if (target == null) emptyList()
                else courses.filter { it.weeks.contains(target) }
                    .sortedWith(compareBy({ it.dayOfWeek }, { it.startPeriod }))
                    .map { it to DAY_NAMES[it.dayOfWeek - 1] }
            }
            else -> {
                val day = targetDate.dayOfWeek.value
                courses.filter {
                    it.dayOfWeek == day && (week == null || it.weeks.contains(week))
                }.sortedBy { it.startPeriod }.map { it to null }
            }
        }

        appearance = WidgetAppearanceStore.load(context, appWidgetId)
        rows = dayCourses.map { (course, dayTag) ->
            Row(
                courseId = course.id,
                name = course.name,
                location = course.location,
                periods = course.periods,
                startTime = course.periods.minOrNull()?.let { timeByPeriod[it] },
                color = courseColor(course).toArgb(),
                dayTag = dayTag,
            )
        }
    }

    private fun refetchAndNotify(bindingCode: String?) {
        refetchScope.launch {
            runCatching { WidgetDataCache.get(context, bindingCode) }
                .onSuccess { WidgetCommon.notifyListDataChanged(context, appWidgetId) }
                .onFailure { Log.w(TAG, "异步补取组件数据失败，保持空态", it) }
        }
    }

    override fun onDestroy() {
        refetchScope.cancel()
        rows = emptyList()
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews {
        val row = rows[position]
        val views = RemoteViews(context.packageName, R.layout.widget_list_item)
        views.setTextViewText(R.id.widget_item_name, row.name)
        views.setTextViewText(
            R.id.widget_item_meta,
            listOfNotNull(
                row.dayTag,
                row.location ?: "教室未定",
                "${periodLabel(row.periods).removePrefix("第").removeSuffix("节").trim()}节",
            ).joinToString(" · "),
        )
        views.setTextViewText(R.id.widget_item_time, row.startTime ?: "")
        // 行文字色：RemoteViews 行由宿主按本应用主题 inflate，不显式指定会落到
        // 浅色主题的深色文字，与组件默认深色背景对比度极低。
        // 配置页承诺的"文字颜色"必须在这里生效，只设标题/副标题是不够的。
        val background = appearance.resolvedBackground(context)
        views.setTextColor(R.id.widget_item_name, appearance.titleColorFor(background))
        views.setTextColor(R.id.widget_item_meta, appearance.bodyColorFor(background))
        views.setTextColor(R.id.widget_item_time, appearance.bodyColorFor(background))
        // 课程色条：纯白条按课程色着色（setColorFilter 只存在于 ImageView，
        // 布局里该控件必须是 ImageView，否则反射取不到方法会抛 ActionException）
        views.setInt(R.id.widget_item_bar, "setColorFilter", row.color)
        // 点击整行 → 打开应用（模板 PendingIntent 由 WidgetCommon 统一设置）
        views.setOnClickFillInIntent(
            R.id.widget_item_row,
            Intent().putExtra(EXTRA_COURSE_ID, row.courseId),
        )
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long =
        // 加上外观指纹：只改外观不改课程时，课程部分的 id 不变，
        // 宿主会直接复用旧行视图，保存后颜色/文字还是老样子。
        rows[position].let { it.courseId * 31L + it.periods.hashCode().toLong() } +
            appearance.viewIdStamp()

    override fun hasStableIds(): Boolean = true

    private fun currentWeekOrNull(semester: Semester?, today: LocalDate): Int? {
        val start = semester?.startLocalDate ?: return null
        return WeekCalculator.currentWeekOrNull(start, semester.totalWeeks, today)
    }

    companion object {
        private const val TAG = "CourseListFactory"

        const val EXTRA_MODE = "com.buaa.schedule.widget.EXTRA_MODE"
        const val EXTRA_COURSE_ID = "com.buaa.schedule.widget.EXTRA_COURSE_ID"

        private val DAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    }
}
