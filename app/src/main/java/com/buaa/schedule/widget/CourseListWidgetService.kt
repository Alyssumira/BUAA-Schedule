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
import com.buaa.schedule.domain.model.TimeSlotProfile
import com.buaa.schedule.domain.model.WEEKDAY_LABELS
import com.buaa.schedule.domain.model.periodGapMinutesOf
import com.buaa.schedule.domain.model.periodLabel
import com.buaa.schedule.domain.model.startLocalDate
import com.buaa.schedule.domain.model.toPeriodSegments
import com.buaa.schedule.domain.model.toStartEndTimes
import com.buaa.schedule.domain.model.unwrappedPeriodLabel
import com.buaa.schedule.domain.schedule.WeekCalculator
import com.buaa.schedule.domain.schedule.WeekParser
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

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
        /**
         * 节次文案，取数时按节次表切段算好（P1-2）：同一行里的开始时间与"进行中"
         * 状态本来就是按段判的，文案再走一遍"节次号相邻即连堂"就会出现
         * 「5-6节 + 11:30 + 下午那节亮着」这种自相矛盾的一行。
         */
        val periodsText: String,
        /**
         * 周次摘要。这里**不做**「整学期就留空」的降噪：
         * 周次是 [WidgetRowField.WEEKS] 显式勾选才拼进 meta 的字段，
         * 在数据层偷偷置空会让「显示内容·周次」勾了没反应。
         */
        val weeksText: String?,
        val startTime: String?,
        val color: Int,
        val dayTag: String?,
        /** 这一节相对此刻的位置；只有今日组件会算，其余模式恒为 [WidgetRowStatus.UPCOMING] */
        val status: WidgetRowStatus = WidgetRowStatus.UPCOMING,
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
        // 进行中/已结束要按**下课时间**判断，而 timeByPeriod 只有上课时间；
        // 节次表为空时用默认节次表，与 [com.buaa.schedule.reminder.ClassProgressScheduler] 同一口径。
        val slotTimes = (if (data.timeSlots.isNotEmpty()) data.timeSlots else TimeSlotProfile.DEFAULT)
            .toStartEndTimes()
        val now = LocalDateTime.now()

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
                periodsText = widgetPeriodsText(course.periods, periodGapMinutesOf(slotTimes)),
                weeksText = WeekParser.toDisplayString(course.weeks),
                startTime = course.periods.minOrNull()?.let { timeByPeriod[it] },
                color = courseColor(course).toArgb(),
                dayTag = dayTag,
                status = if (mode == ListWidgetMode.TODAY) {
                    widgetRowStatus(course.periods, targetDate, slotTimes, now)
                } else {
                    WidgetRowStatus.UPCOMING
                },
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
        // 越界防护：宿主给的 position 与异步 refetch 替换过的 rows 错位时（本工厂的
        // onDataSetChanged 会另起协程重写 rows），抛 IndexOutOfBoundsException 死掉的是
        // 整个应用进程——RemoteViewsFactory 跑在我们的进程里。同 WeekGridWidgetService 的 getOrNull 口径。
        val row = rows.getOrNull(position) ?: return RemoteViews(
            context.packageName, R.layout.widget_list_item,
        )
        val views = RemoteViews(context.packageName, R.layout.widget_list_item)
        views.setTextViewText(R.id.widget_item_name, row.name)
        // 副字段由用户勾选（审查 3.7）：默认口径就是这行原本的「地点 · 节次」，
        // 教师/周次要用户自己要求才占这一行——窄屏上多一段就是一次截断。
        val fields = appearance.effectiveRowFields(WidgetAppearance.DEFAULT_LIST_ROW_FIELDS)
        views.setTextViewText(
            R.id.widget_item_meta,
            widgetRowStatusMark(
                row.status,
                widgetRowMeta(
                    fields,
                    WidgetRowFields(
                        dayTag = row.dayTag,
                        teacher = row.teacher,
                        location = row.location,
                        periodsText = row.periodsText,
                        weeksText = row.weeksText,
                    ),
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
        // 时间状态（只在今日组件里有意义）：正在上的这一节铺一层课程色的圆角底，
        // 已经下课的行整行压暗——一眼能看出"还剩几节"，而这正是今日组件唯一值钱的问题。
        // setColorFilter 只在 ImageView 上有，所以那一层在布局里必须是 ImageView。
        val ongoing = row.status == WidgetRowStatus.ONGOING
        views.setViewVisibility(
            R.id.widget_item_active,
            if (ongoing) View.VISIBLE else View.INVISIBLE,
        )
        if (ongoing) {
            // 底色 drawable 保持纯白不透明，半透明交给 setAlpha：与「今天」胶囊同一套约定，
            // 把 alpha 烘进 setColorFilter 的颜色里会在浅色组件上偏成一团糊。
            views.setInt(R.id.widget_item_active, "setColorFilter", row.color)
            views.setFloat(R.id.widget_item_active, "setAlpha", ACTIVE_ROW_ALPHA)
        }
        views.setFloat(
            R.id.widget_item_row,
            "setAlpha",
            if (row.status == WidgetRowStatus.PAST) PAST_ROW_ALPHA else 1f,
        )
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
            row.periodsText,
            row.color,
            // 状态也是画出来的内容：不折进来的话，下课那一刻 id 不变，
            // 宿主把缓存里那份"还亮着进行中底色"的行直接贴回来。
            row.status,
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

        private val DAY_NAMES = WEEKDAY_LABELS

        /** 已下课的行的整行透明度：还能读，但绝不与正在上的那一节抢眼球 */
        private const val PAST_ROW_ALPHA = 0.45f

        /** 进行中那一行的课程色底透明度：压到浅色组件上也不糊字的程度 */
        private const val ACTIVE_ROW_ALPHA = 0.22f
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

/**
 * 节次的短标签：第1-2节 → 1-2节（与改动前的 meta 口径逐字一致）。
 *
 * 「节」字要等确认有内容再补：以前是无条件 `... + "节"`，节次为空时（剥包装后就是空串）
 * 组件行上会剩一个光秃秃的「节」字。返回空串即"这一项缺席"，
 * [widgetRowMeta] 那一头本来就会把空段连同分隔符一起丢掉。
 */
internal fun widgetPeriodsText(
    periods: List<Int>,
    gapMinutes: (Int, Int) -> Long?,
): String {
    val numbers = unwrappedPeriodLabel(periodLabel(periods, gapMinutes))
    return if (numbers.isEmpty()) "" else "${numbers}节"
}

/** 今日组件里一行的时间状态 */
internal enum class WidgetRowStatus { UPCOMING, ONGOING, PAST }

/** 节次表缺下课时间时的单节兜底时长，与 [com.buaa.schedule.reminder] 那条链同一取值 */
private const val DEFAULT_LESSON_MINUTES = 45L

/**
 * 一行今天的课相对此刻的位置。
 *
 * 按**连续节次段**判，而不是把首节到末节拉成一个区间：`[5,6]` 这种节次号相邻、
 * 中间隔着午饭的课，用整段区间会把整个中午算成"正在进行"，于是下午那节课提前亮起底色
 * （课堂窗口那条链上早已踩过同一个坑，口径必须一致）。
 *
 * 一段都取不到时间时返回 [WidgetRowStatus.UPCOMING] 而不是 PAST：缺节次表是数据问题，
 * 不该表现成"今天的课全上完了"，那会把整列课抹成灰的。
 */
internal fun widgetRowStatus(
    periods: List<Int>,
    date: LocalDate,
    slotTimes: Map<Int, Pair<LocalTime, LocalTime>>,
    now: LocalDateTime,
): WidgetRowStatus {
    val gapMinutes = periodGapMinutesOf(slotTimes)
    var anyTimeKnown = false
    var ongoing = false
    var upcoming = false
    for (segment in periods.toPeriodSegments(gapMinutes)) {
        val start = slotTimes[segment.first]?.first ?: continue
        anyTimeKnown = true
        val stop = slotTimes[segment.last]?.second?.takeIf { it.isAfter(start) }
            ?: start.plusMinutes(DEFAULT_LESSON_MINUTES)
        val begin = date.atTime(start)
        val end = date.atTime(stop)
        when {
            now < begin -> upcoming = true
            now < end -> ongoing = true
        }
    }
    if (!anyTimeKnown) return WidgetRowStatus.UPCOMING
    return when {
        ongoing -> WidgetRowStatus.ONGOING
        upcoming -> WidgetRowStatus.UPCOMING
        else -> WidgetRowStatus.PAST
    }
}

/** 进行中的那一行在副行最前面标一句「进行中」；状态放句首，窄屏截断时它才留得住 */
internal fun widgetRowStatusMark(status: WidgetRowStatus, meta: String): String =
    if (status == WidgetRowStatus.ONGOING) {
        if (meta.isBlank()) "进行中" else "进行中 · $meta"
    } else {
        meta
    }
