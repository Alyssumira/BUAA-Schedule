package com.buaa.schedule.widget

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.buaa.schedule.R
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.WEEKDAY_LABELS
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

    private data class DayCell(
        val dayName: String,
        val summary: String,
        /** 这一列是不是「今天」（审查 3.4）。只在正在看本周时为 true */
        val isToday: Boolean,
        /**
         * 课表有数据、而这一格恰好没课：标一句淡化的「无课」。
         * 没有学期 / 假期时不能这么标——那会在七列上各写一遍"无课"，
         * 把"我还没导入课表"说成"我这周什么都没有"。
         */
        val isBlankDay: Boolean = false,
    )

    private var cells: List<DayCell> = emptyList()
    private var appearance: WidgetAppearance = WidgetAppearance()

    /** 本次渲染的教学周与「今天」所在列：进 itemId，见 [getItemId] */
    private var weekOfGrid: Int = 0
    private var todayColumn: Int = 0

    /** 快照未命中时补数据用的后台作用域（onDataSetChanged 在主线程，不能自己查库） */
    private val refetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        val semester: Semester?
        val courses: List<com.buaa.schedule.domain.model.Course>
        val binding = WidgetBindingStore.load(context, appWidgetId)
        // 同 CourseListFactory：onDataSetChanged 在主线程，不能在这里阻塞查库。
        // 未命中就渲染空网格，**并自己补一次数据再通知**，不能干等着没人管（R5 F-17）。
        val data = WidgetDataCache.peek(binding.semesterCode)
        appearance = WidgetAppearanceStore.load(context, appWidgetId)
        if (data == null) {
            Log.w(TAG, "快照未就绪，本次渲染空网格并异步补数据")
            cells = emptyList()
            refetchAndNotify(binding.semesterCode)
            return
        }
        semester = data.semester
        courses = data.courses

        val today = LocalDate.now()
        // 浏览周从 store 现读，而不是从 adapter intent 的 extras 拿：
        // 宿主用 Intent.filterEquals 判等，它**不含 extras**，只改 extras 根本不会重建工厂，
        // 于是翻周点下去还是老那一周（真机最容易踩的 RemoteViews 坑之一）。
        val week = displayWeekOf(semester, today, binding)
        weekOfGrid = week ?: 0
        // 「今天」只在正在看本周时才高亮：翻到下周还亮着周四，用户会以为那天就是今天。
        val realWeek = semester?.startLocalDate?.let {
            WeekCalculator.currentWeekOrNull(it, semester.totalWeeks, today)
        }
        todayColumn = if (week != null && week == realWeek) today.dayOfWeek.value else 0
        val names = WEEKDAY_LABELS
        cells = if (semester == null || week == null || courses.isEmpty()) {
            names.map { DayCell(it, "", false) }
        } else {
            names.mapIndexed { index, dayName ->
                val day = index + 1
                val dayCourses = courses.filter { it.dayOfWeek == day && it.weeks.contains(week) }
                    .sortedBy { it.startPeriod }
                DayCell(
                    dayName = dayName,
                    summary = weekGridDaySummary(dayCourses, maxLines = appearance.gridMaxLines),
                    isToday = day == todayColumn,
                    isBlankDay = dayCourses.isEmpty(),
                )
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
        views.setTextViewText(
            R.id.widget_grid_courses,
            // 空着的工作日标一句淡色的「无课」：这一族组件回答的第二问就是"哪天是空的"，
            // 完全留白和"数据还没到"看不出区别。周末不标——多数人周末本来就没课，
            // 周六周日各一个「无课」只是噪音。
            if (cell.isBlankDay && position < WEEKDAY_COLUMNS) NO_CLASS_LABEL else cell.summary,
        )
        views.setFloat(
            R.id.widget_grid_courses,
            "setAlpha",
            if (cell.isBlankDay) BLANK_DAY_ALPHA else 1f,
        )
        // U-09：宽松档（每格 3 节）把课程行从 9sp 提到 10sp——密集档是列宽所限，
        // 但既然只列三节，就没有理由继续用接近不可读的字号。
        // RemoteViews 改字号只有 setTextViewTextSize，而它要 API 33：26~32 上这一档仍然
        // 只列 3 节、字号留在布局默认的 9sp。为两档字号再造一份只差 textSize 的布局，
        // 换来的是两处会互相漂移的字号定义，不值当。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            views.setTextViewTextSize(
                R.id.widget_grid_courses,
                TypedValue.COMPLEX_UNIT_SP,
                appearance.gridRowTextSizeSp,
            )
        }
        val bg = appearance.resolvedBackground(context)
        // 今天的表头（审查 3.4）：胶囊底是一块纯白圆角 ImageView，运行时按配色口径着色
        // ——TextView 上直接 setBackgroundColor 会把背景换成方角色块、圆角就没了。
        if (cell.isToday) {
            views.setInt(R.id.widget_grid_day_pill, "setColorFilter", appearance.todayHighlightFor(bg))
            views.setFloat(R.id.widget_grid_day_pill, "setAlpha", appearance.todayHighlightAlpha)
            views.setViewVisibility(R.id.widget_grid_day_pill, View.VISIBLE)
            views.setTextColor(R.id.widget_grid_day, appearance.onTodayHighlightFor(bg))
        } else {
            views.setViewVisibility(R.id.widget_grid_day_pill, View.GONE)
            views.setTextColor(R.id.widget_grid_day, appearance.titleColorFor(bg))
        }
        views.setTextColor(R.id.widget_grid_courses, appearance.bodyColorFor(bg))
        // 格子点击（审查 3.6）：模板 PendingIntent 由 WidgetCommon 下发，这里只带行级 extras。
        // 一格代表一天，点它就到那一天的日视图——列了多达 5 节课的格子不该替用户决定打开哪一门。
        views.setOnClickFillInIntent(
            R.id.widget_grid_item_root,
            Intent().putExtra(WidgetNavigation.EXTRA_DAY_OF_WEEK, position + 1),
        )
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    /**
     * 位置 + 外观之外还要折进这一格真正画出来的文字：宿主按 id 缓存单元格，
     * id 不变就不看工厂新算的内容（改课后网格还是老课表，见 WidgetCommon.itemKey）。
     *
     * 「今天」与浏览周也必须折进来：两者都不在 [appearance] 里，跨零点后 isToday 翻面、
     * 翻周后 weekOfGrid 变化，若某天两格的文字恰好一样，id 不变 → 高亮停在昨天。
     * [DayCell.isBlankDay] 同理：没课表时的空格子与"有课表但今天没课"的空格子文字一样
     * （都是空串），不折进来就永远刷不出那句「无课」。
     */
    override fun getItemId(position: Int): Long {
        val cell = cells.getOrNull(position) ?: return WidgetCommon.itemKey(position, position.toLong())
        val content = (cell.dayName + cell.summary + cell.isToday + cell.isBlankDay + weekOfGrid).hashCode()
        return WidgetCommon.itemKey(position, content.toLong() + appearance.viewIdStamp())
    }

    override fun hasStableIds(): Boolean = true

    private companion object {
        private const val TAG = "WeekGridFactory"

        /** 周一..周五的列数：只有工作日才标「无课」 */
        private const val WEEKDAY_COLUMNS = 5
        private const val NO_CLASS_LABEL = "无课"

        /** 空格子的淡度：要到"看得见但不参与扫读"的程度 */
        private const val BLANK_DAY_ALPHA = 0.35f
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
 * 短名：别名优先（用户自己起的通常已经很短），再把**括号里的区分字内联**进来截断。
 *
 * 旧写法先 `substringBefore('(')` 再 take(3)，于是「大学物理(上)」与「大学物理(下)」、
 * 「体育(篮球)」与「体育(游泳)」全都变成同一个名字——括号里那几个字恰恰是唯一
 * 的区分信息（审查 3.3）。这里让主干让出一个字给括号首字：
 * 「大学上」「大学下」「体育篮」「体育游」「毛概1」。
 *
 * 另一类旧写法治不到的撞名：`高等数学A` / `高等数学B` 这种**没有括号、班型写在末尾**
 * 的北航公共课，截断后都是「高等数」（统筹文档 §2.2 实测补的规则）。
 * 末位是 ASCII 字母/数字时同样让一个字给后缀：「高等A」「高等B」。
 */
internal fun weekGridShortName(name: String, maxChars: Int = WEEK_GRID_NAME_CHARS): String {
    val cleaned = name.replace(" ", "").trim()
    val trunkChars = (maxChars - 1).coerceAtLeast(1)
    parenInner(cleaned)?.let { inner ->
        // 主干要**截到左括号之前**再取字：直接从原名取，maxChars=4 时
        // 「体育(篮球)」会取到「体育(」，把一个标点当字留在短名里
        val trunk = cleaned.beforeFirstParen().take(trunkChars)
        return trunk + inner
    }
    asciiSuffix(cleaned)?.let { suffix ->
        return cleaned.dropLast(suffix.length).take(trunkChars) + suffix
    }
    return cleaned.take(maxChars)
}

/** 截到第一个中/英文左括号之前；没有括号时原样返回 */
private fun String.beforeFirstParen(): String {
    val open = indexOfFirst { it == '(' || it == '（' }
    return if (open < 0) this else take(open)
}

/** 首个中英文括号里的第一个字；括号在最前、括号为空时返回 null */
private fun parenInner(cleaned: String): String? {
    val open = cleaned.indexOfFirst { it == '(' || it == '（' }
    if (open <= 0) return null
    val tail = cleaned.substring(open + 1)
    // 教务导出的脏数据里有半截括号（「大学物理(上」），没有闭括号就取到结尾
    val inner = tail.substringBefore(')').substringBefore('）')
    return inner.firstOrNull()?.takeUnless { it == '(' || it == '（' }?.toString()
}

/**
 * 末尾的 ASCII 字母/数字尾巴（班型后缀），只取**最后一个字**；
 * 尾巴之前必须还有非 ASCII 文字，否则「Math101」这种整名都是拉丁的课
 * 让位后只剩「Ma1」，比直接截「Mat」更糟。
 *
 * 多位尾巴（「高等数学2024」）不能整段留下：短名的预算就是 maxChars 个字。
 */
private fun asciiSuffix(cleaned: String): String? {
    if (cleaned.isEmpty()) return null
    if (!cleaned.last().isClassSuffixChar()) return null
    val trunkEnd = cleaned.indexOfLast { !it.isClassSuffixChar() }
    if (trunkEnd <= 0) return null
    return cleaned.substring(trunkEnd + 1).takeLast(1)
}

private fun Char.isClassSuffixChar(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'

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
): String = foldDayLines(
    courses.map { "${it.startPeriod}${weekGridShortName(it.displayName)}" },
    maxLines,
)

/**
 * 把"每节课一行"的整段结果折进 [maxLines] 行以内，多出来的收成末行「＋N」。
 *
 * N 是**没有点名显示的节数**：末行整个被计数器占掉，所以它等于 `总行数 - 还留着几行名字`，
 * 不是 `总行数 - 容量`。7 节课 / 5 行档要写「＋3」而不是「＋2」——
 * 少算的那一节正是被计数器顶掉的那一行，用户按数字加一遍会对不上总数。
 */
internal fun foldDayLines(allLines: List<String>, maxLines: Int): String {
    if (allLines.size <= maxLines) return allLines.joinToString("\n")
    val shown = allLines.take(maxLines - 1)
    return (shown + "＋${allLines.size - shown.size}").joinToString("\n")
}