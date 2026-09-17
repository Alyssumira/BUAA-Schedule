package com.buaa.schedule.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.buaa.schedule.MainActivity
import com.buaa.schedule.R
import com.buaa.schedule.core.designsystem.courseColor
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.periodLabel
import com.buaa.schedule.domain.model.startLocalDate
import com.buaa.schedule.domain.schedule.WeekCalculator
import com.buaa.schedule.domain.schedule.WeekParser
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
        val teacher: String?,
        val location: String?,
        val periods: List<Int>,
        /** 周次摘要；**每周都上时为空**——每行都挂个「1-16周」是零信息量的噪音 */
        val weeksText: String?,
        val startTime: String?,
        val color: Int,
        val dayTag: String?,
    )

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        val semester: Semester?
        val courses: List<Course>
        val timeByPeriod: Map<Int, String>
        val binding = WidgetBindingStore.load(context, appWidgetId)
        // ⚠️ onDataSetChanged 跑在**应用主线程**（RemoteViewsService 的约定），
        // 这里 runBlocking 查库 = 直接卡 UI 线程，组件每次刷新都会触发一次。
        // 正常路径 provider 已先走 WidgetDataCache.get 备好数据，peek() 必定命中；
        // 未命中（多学期实例互相挤掉、冷启动首次）时渲染空态**并自己补数据再通知一次**，
        // 不能干等着没人管（R5 F-17）。
        val data = WidgetDataCache.peek(binding.semesterCode)
        if (data == null) {
            Log.w(TAG, "快照未就绪，本次渲染空态并异步补数据")
            rows = emptyList()
            appearance = WidgetAppearanceStore.load(context, appWidgetId)
            // 只画空态是不够的：没有第二次 notify 的话，这个实例会一直"今天没有课"，
            // 直到零点闹钟或 12 小时兜底（R5 F-17）。在允许阻塞的 IO 线程里补数据，
            // 补到之后再触发一次 onDataSetChanged。
            refetchAndNotify(binding.semesterCode)
            return
        }
        semester = data.semester
        courses = data.courses
        timeByPeriod = data.timeSlots.associateBy({ it.number }, { it.startTime })

        val today = LocalDate.now()
        // 每行取数必须用**目标日期**对应的教学周：明日组件的课要按"明天"的周次过滤，
        // 否则周日晚上看「明日课程」会显示成本周同一节次的课（单双周下内容不同）。
        val targetDate = if (mode == ListWidgetMode.TOMORROW) today.plusDays(1) else today
        val calendarWeek = currentWeekOrNull(semester, if (mode == ListWidgetMode.WEEK) today else targetDate)
        // 本周模式支持翻周（审查 3.5）。偏移从 store 现读而不是从 adapter intent 的 extras 拿：
        // 宿主用 Intent.filterEquals 判等，它不含 extras，只改 extras 根本不会重建工厂。
        val week = if (mode == ListWidgetMode.WEEK) {
            displayWeekOf(semester, today, binding)
        } else {
            calendarWeek
        }
        val totalWeeks = semester?.totalWeeks ?: 0

        val dayCourses: List<Pair<Course, String?>> = when {
            semester != null && week == null -> emptyList() // 假期中
            mode == ListWidgetMode.WEEK -> {
                val target = week
                if (target == null) emptyList()
                else courses.filter { it.weeks.contains(target) }
                    .sortedWith(compareBy({ it.dayOfWeek }, { it.startPeriod }))
                    // 读路径不做归一化（getDisplayCourses），脏数据里出现 1..7 之外的
                    // dayOfWeek 时这里绝不能越界：工厂抛异常后 RemoteViewsService 不重试，
                    // 组件会一直停在"已崩溃"灰块。取不到就不给日标签。
                    .map { it to DAY_NAMES.getOrNull(it.dayOfWeek - 1) }
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
                // 别名优先（审查 3.1）：用户起短名就是为了在这种窄地方用，
                // 之前只有 4×2 组件认别名，今日/明日/本周一直显示会被截断的教务原名
                name = course.displayName,
                teacher = course.teacher,
                location = course.location,
                periods = course.periods,
                weeksText = if (totalWeeks > 0 && course.weeks.size >= totalWeeks) {
                    null
                } else {
                    WeekParser.toDisplayString(course.weeks)
                },
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
        // 副字段由用户勾选（审查 3.7）：默认口径就是这行原本的「地点 · 节次」，
        // 教师/周次要用户自己要求才占这一行——窄屏上多一段就是一次截断。
        val fields = appearance.effectiveRowFields(WidgetAppearance.DEFAULT_LIST_ROW_FIELDS)
        views.setTextViewText(
            R.id.widget_item_meta,
            widgetRowMeta(
                fields,
                WidgetRowFields(
                    dayTag = row.dayTag,
                    teacher = row.teacher,
                    location = row.location,
                    periodsText = widgetPeriodsText(row.periods),
                    weeksText = row.weeksText,
                ),
            ),
        )
        views.setTextViewText(R.id.widget_item_time, row.startTime ?: "")
        views.setViewVisibility(
            R.id.widget_item_time,
            if (WidgetRowField.TIME in fields) View.VISIBLE else View.GONE,
        )
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

    override fun getItemId(position: Int): Long {
        val row = rows[position]
        // 宿主按这个 id 缓存行视图，id 不变就不看工厂新算的内容。
        // 旧式只折了 courseId + 节次：改课名、换教室、换上课时间都不换 id，
        // 于是"编辑后组件不动"、"手动刷新好了、一点击又变回原样"。
        // 教师与周次摘要同样是画出来的内容，必须一起折进来。
        val content = listOf(
            row.name,
            row.location ?: "",
            row.teacher ?: "",
            row.weeksText ?: "",
            row.startTime ?: "",
            row.dayTag ?: "",
            row.periods.joinToString(","),
            row.color,
        ).hashCode()
        return WidgetCommon.itemKey(position, content.toLong() + appearance.viewIdStamp())
    }

    override fun hasStableIds(): Boolean = true

    private fun currentWeekOrNull(semester: Semester?, today: LocalDate): Int? {
        val start = semester?.startLocalDate ?: return null
        return WeekCalculator.currentWeekOrNull(start, semester.totalWeeks, today)
    }

    companion object {
        private const val TAG = "CourseListFactory"

        const val EXTRA_MODE = "com.buaa.schedule.widget.EXTRA_MODE"
        const val EXTRA_COURSE_ID = MainActivity.EXTRA_COURSE_ID

        private val DAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    }
}

/** 一行课程可提供的副字段（[widgetRowMeta] 的输入，与渲染解耦便于单测） */
internal data class WidgetRowFields(
    val dayTag: String? = null,
    val teacher: String? = null,
    val location: String? = null,
    val periodsText: String? = null,
    val weeksText: String? = null,
)

/**
 * 列表行的副字段拼接（审查 3.7）：按用户勾选的顺序拼，没值的段自动缺席。
 *
 * [dayTag] 恒在最前 —— 本周模式一列靠它分组，不该被勾选顺序打散。
 * 地点单独选中时兜底成「教室未定」，沿用旧口径：否则没教室的行会整段变空。
 */
internal fun widgetRowMeta(
    fields: List<WidgetRowField>,
    data: WidgetRowFields,
): String = (
    listOfNotNull(data.dayTag?.takeIf { it.isNotBlank() }) +
        fields.mapNotNull { field ->
            when (field) {
                WidgetRowField.TEACHER -> data.teacher?.takeIf { it.isNotBlank() }
                WidgetRowField.LOCATION -> data.location ?: "教室未定"
                WidgetRowField.PERIODS -> data.periodsText?.takeIf { it.isNotBlank() }
                WidgetRowField.WEEKS -> data.weeksText?.takeIf { it.isNotBlank() }
                // 时间走布局右侧那一列，不拼进 meta（见 CourseListFactory.getViewAt）
                WidgetRowField.TIME -> null
            }
        }
    ).joinToString(" · ")

/** 节次的短标签：第1-2节 → 1-2节（与改动前的 meta 口径逐字一致） */
internal fun widgetPeriodsText(periods: List<Int>): String =
    periodLabel(periods).removePrefix("第").removeSuffix("节").trim() + "节"
