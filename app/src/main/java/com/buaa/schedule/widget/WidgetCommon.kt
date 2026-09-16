package com.buaa.schedule.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.buaa.schedule.MainActivity
import com.buaa.schedule.R
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.startLocalDate
import com.buaa.schedule.domain.schedule.WeekCalculator
import com.buaa.schedule.reminder.ClassProgressScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

private val DAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** Widget 内容模式 */
enum class ListWidgetMode { TODAY, TOMORROW, WEEK }

/**
 * 三种列表型 Widget（今日/明日/周课表）的共享渲染与更新逻辑。
 */
object WidgetCommon {

    /** 头部点击的 requestCode 偏移：避免与列表模板共用同一个 appWidgetId 而互相覆盖 */
    private const val HEADER_REQUEST_CODE_OFFSET = 100_000

    private const val BOOTSTRAP_INTERVAL_MS = 60_000L
    @Volatile private var lastBootstrapAt: Long = 0L

    private const val TAG = "WidgetCommon"

    /**
     * 组件刷新的即发协程：异常一律就地留下日志，绝不让它跑到默认处理器。
     *
     * `goAsync()` 的协程只写 `try/finally` 时（R5 F-24），Room 抛出的异常或
     * 精确闹钟权限被撤销都会顺着协程默认处理器把整个进程打死 ——
     * `finally` 只保证 `PendingResult.finish()` 执行，拦不住崩溃。
     */
    private fun launchRefresh(
        pendingResult: BroadcastReceiver.PendingResult?,
        work: suspend () -> Unit,
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                work()
            } catch (e: CancellationException) {
                // 取消是控制流，不是错误：吞掉它会让上游的 cancel/超时失效
                throw e
            } catch (e: SecurityException) {
                Log.w(TAG, "组件刷新被系统拒绝（权限类失败）", e)
            } catch (e: Exception) {
                Log.w(TAG, "组件刷新失败", e)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    /**
     * 「部分 ROM（HyperOS / MIUI 等）不回调 onEnabled」的幂等补注册。
     *
     * 原先这行写在 5 个 Provider 的 `onUpdate` 里，也就是每次广播都在**主线程**
     * 做 5 次 AlarmManager/WorkManager binder 调用（R5 F-25）。挪到后台协程里，
     * 注册时机不变，只是不再占用主线程。
     *
     * 再加一层时间闸门：一次广播会让 5 个 Provider 各进来一次，而
     * [BackgroundSync.scheduleWidgetMidnight] 内部就有一次 `hasAnyWidget`
     * （最多 5 次 `getAppWidgetIds` 往返），不拦一道等于每广播 25 次 binder 调用。
     * 不能简化成"进程内只跑一次"：用户可能先开应用、过很久才放第一个组件，
     * 那次补注册正是唯一会注册零点闹钟的地方。
     */
    private fun bootstrapBackgroundSync(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBootstrapAt < BOOTSTRAP_INTERVAL_MS) return
        lastBootstrapAt = now
        BackgroundSync.scheduleWidgetMidnight(context)
        WidgetFallbackWorker.ensure(context)
    }

    fun goAsyncUpdate(
        context: Context,
        appWidgetIds: IntArray,
        pendingResult: BroadcastReceiver.PendingResult?,
        mode: ListWidgetMode,
    ) {
        // 一个协程串行更新全部 widget，全部完成后再 finish，
        // 避免第一个更新完成就把广播标记结束导致其余更新被系统回收
        launchRefresh(pendingResult) {
            bootstrapBackgroundSync(context)
            val manager = AppWidgetManager.getInstance(context)
            appWidgetIds.forEach { updateListWidget(context, manager, it, mode) }
        }
    }

    /**
     * 只通知「列表数据变了」，不重画 RemoteViews 骨架。
     *
     * `RemoteViewsFactory.onDataSetChanged()` 跑在主线程、不能查库：未命中快照时它先渲染
     * 空态，异步补到数据后调用这里，触发第二次 `onDataSetChanged`（R5 F-17）。
     */
    fun notifyListDataChanged(context: Context, appWidgetId: Int) =
        notifyDataChanged(context, appWidgetId, R.id.widget_list)

    /** 同 [notifyListDataChanged]，针对周网格组件的 `widget_grid` */
    fun notifyGridDataChanged(context: Context, appWidgetId: Int) =
        notifyDataChanged(context, appWidgetId, R.id.widget_grid)

    private fun notifyDataChanged(context: Context, appWidgetId: Int, viewId: Int) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        runCatching {
            AppWidgetManager.getInstance(context)
                .notifyAppWidgetViewDataChanged(appWidgetId, viewId)
        }
    }

    suspend fun updateAllOfProvider(
        context: Context,
        providerClass: Class<out AppWidgetProvider>,
        mode: ListWidgetMode,
    ): Boolean = withContext(Dispatchers.IO) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, providerClass))
        ids.forEach { updateListWidget(context, manager, it, mode) }
        ids.isNotEmpty()
    }

    suspend fun updateListWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        mode: ListWidgetMode,
    ) {
        // 走同步层快照（手动课程 + 当前学期/实例绑定学期），与主界面保持一致；
        // 实例绑定优先于当前学期，允许不同组件显示不同课表
        val bindingCode = WidgetBindingStore.load(context, appWidgetId).semesterCode
        val data = WidgetDataCache.get(context, bindingCode)
        val semester = data.semester

        val today = LocalDate.now()
        val title = when (mode) {
            ListWidgetMode.TODAY -> "今日课程"
            ListWidgetMode.TOMORROW -> "明日课程"
            ListWidgetMode.WEEK -> weekTitle(semester)
        }
        // 副标题：周课表显示周次；今日/明日显示具体日期（与列表口径一致）
        val subtitle = when (mode) {
            ListWidgetMode.WEEK -> currentWeekOrNull(semester, today)?.let { "第 $it 周" } ?: "假期中"
            ListWidgetMode.TOMORROW -> dateLabel(today.plusDays(1))
            ListWidgetMode.TODAY -> dateLabel(today)
        }
        // 空态文案必须在主视图里下发：RemoteViewsFactory 拿不到 setEmptyView 指向的那个视图，
        // 此前只能靠布局硬编码，导致「明日课程」空态显示的是「今天没有课」。
        val emptyText = when {
            semester != null && currentWeekOrNull(semester, today) == null -> "假期中，暂无课表"
            mode == ListWidgetMode.TOMORROW -> "明天没有课"
            mode == ListWidgetMode.WEEK -> "本周没有课"
            else -> "今天没有课"
        }
        val layoutRes = if (mode == ListWidgetMode.WEEK) R.layout.widget_week else R.layout.widget_today

        val views = RemoteViews(context.packageName, layoutRes)
        val appearance = WidgetAppearanceStore.load(context, appWidgetId)
        applyAppearance(context, views, appearance, isListLayout = true)
        applyBlurredBackground(context, views, appearance)
        views.setTextViewText(R.id.widget_title, title)
        views.setTextViewText(R.id.widget_subtitle, subtitle)
        views.setTextViewText(R.id.widget_empty, emptyText)

        // 列表数据：交给 RemoteViewsService（行内含课程色条/教室/节次/上课时间）
        val serviceIntent = Intent(context, CourseListWidgetService::class.java).apply {
            putExtra(CourseListFactory.EXTRA_MODE, mode.ordinal)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        views.setRemoteAdapter(R.id.widget_list, serviceIntent)
        views.setEmptyView(R.id.widget_list, R.id.widget_empty)

        // 行点击模板：Factory 里每行用 fillInIntent 带上课程 id。
        // - 集合组件的模板必须 FLAG_MUTABLE，否则系统无法把行 extras 合进来（点行无反应）；
        // - requestCode 必须与下方头部点击区分：PendingIntent 判等基于 Intent.filterEquals
        //   （不含 extras），同一个 appWidgetId 会让两者互相覆盖。
        val listTapIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(CourseListFactory.EXTRA_COURSE_ID, -1L)
        }
        views.setPendingIntentTemplate(
            R.id.widget_list,
            PendingIntent.getActivity(
                context,
                appWidgetId,
                listTapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            ),
        )

        // 头部点击 → 打开应用
        val headerIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            appWidgetId + HEADER_REQUEST_CODE_OFFSET,
            headerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_header, pendingIntent)
        appWidgetManager.updateAppWidget(appWidgetId, views)
        // 列表数据变了必须通知，否则 Factory 不会重新拉取
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_list)
    }

    /**
     * 把实例级外观套到 RemoteViews 上。
     *
     * 只用 RemoteViews 支持的几类调用（见 [WidgetAppearance] 的说明）：
     * 背景层 ImageView 重新着色 + 调透明度 + 换圆角 drawable，文字改色，
     * 标题行按需隐藏。
     *
     * 背景层 id 用官方约定的 `@android:id/background`，
     * 这样「点组件启动应用」才有 Android 12+ 的平滑过渡动画。
     *
     * @param isListLayout 「下一节课」布局没有标题/副标题/空态这几个控件，
     *   对这些 id 下发指令只会产生无效 action（浪费 IPC），因此按布局区分。
     */
    private fun applyAppearance(
        context: Context,
        views: RemoteViews,
        appearance: WidgetAppearance,
        isListLayout: Boolean,
    ) {
        val bgViewId = android.R.id.background
        val background = appearance.resolvedBackground(context)
        views.setInt(bgViewId, "setImageResource", appearance.cornerDrawableRes)
        views.setInt(bgViewId, "setColorFilter", background)
        views.setFloat(bgViewId, "setAlpha", appearance.alphaFraction)
        if (isListLayout) {
            views.setTextColor(R.id.widget_title, appearance.titleColorFor(background))
            views.setTextColor(R.id.widget_subtitle, appearance.bodyColorFor(background))
            views.setTextColor(R.id.widget_empty, appearance.bodyColorFor(background))
            views.setViewVisibility(
                R.id.widget_title,
                if (appearance.showTitle) View.VISIBLE else View.GONE,
            )
        }
        views.setInt(R.id.widget_root, "setBackgroundColor", android.graphics.Color.TRANSPARENT)
    }

    /** 开启玻璃感背景时，用壁纸模糊位图替换纯色圆角底 */
    private fun applyBlurredBackground(
        context: Context,
        views: RemoteViews,
        appearance: WidgetAppearance,
    ) {
        if (!appearance.blurBackground) return
        WidgetBackgroundRenderer.render(context, appearance)?.let { bitmap ->
            views.setImageViewBitmap(android.R.id.background, bitmap)
        }
    }

    suspend fun updateAllOfProviderNext(context: Context, providerClass: Class<*>) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, providerClass))
        ids.forEach { updateNextWidget(context, manager, it) }
    }

    fun goAsyncUpdateNext(
        context: Context,
        appWidgetIds: IntArray,
        pendingResult: BroadcastReceiver.PendingResult,
    ) {
        launchRefresh(pendingResult) {
            bootstrapBackgroundSync(context)
            val manager = AppWidgetManager.getInstance(context)
            appWidgetIds.forEach { updateNextWidget(context, manager, it) }
        }
    }

    /**
     * 2x1「下一节课」极简组件：显示下一个上课窗口的课程/时间/教室。
     * 数据用 [ClassProgressScheduler.planNextClassWindow]（与课程进行中通知同一口径）。
     */
    suspend fun updateNextWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
    ) {
        val bindingCode = WidgetBindingStore.load(context, appWidgetId).semesterCode
        val data = WidgetDataCache.get(context, bindingCode)
        val semester = data.semester
        val courses = data.courses
        val slots = data.timeSlots

        val window = semester?.startLocalDate?.let { start ->
            ClassProgressScheduler.planNextClassWindow(courses, start, slots, LocalDateTime.now())
        }
        val layoutRes = R.layout.widget_next
        val views = RemoteViews(context.packageName, layoutRes)
        if (window == null) {
            views.setTextViewText(R.id.widget_next_name, "暂无课程")
            views.setTextViewText(R.id.widget_next_meta, "导入课表后显示下一节课")
        } else {
            val zone = ZoneId.systemDefault()
            val start = java.time.Instant.ofEpochMilli(window.startMillis).atZone(zone)
            val dayName = DAY_NAMES[start.dayOfWeek.value - 1]
            // Locale 钉死为 US：避免部分语言环境下输出非 ASCII 数字
            val timeText = start.format(
                java.time.format.DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.US)
            )
            views.setTextViewText(R.id.widget_next_name, window.courseName)
            views.setTextViewText(
                R.id.widget_next_meta,
                listOfNotNull(
                    "$dayName $timeText",
                    window.sectionText,
                    window.location?.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
            )
        }

        val appearance = WidgetAppearanceStore.load(context, appWidgetId)
        applyAppearance(context, views, appearance, isListLayout = false)
        applyBlurredBackground(context, views, appearance)
        val bg = appearance.resolvedBackground(context)
        views.setTextColor(R.id.widget_next_name, appearance.titleColorFor(bg))
        views.setTextColor(R.id.widget_next_label, appearance.bodyColorFor(bg))
        views.setTextColor(R.id.widget_next_meta, appearance.bodyColorFor(bg))
        views.setViewVisibility(R.id.widget_next_label, if (appearance.showTitle) View.VISIBLE else View.GONE)

        val pendingIntent = PendingIntent.getActivity(
            context,
            appWidgetId + HEADER_REQUEST_CODE_OFFSET,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    /** 只刷新某一个实例（配置页保存后立即生效） */
    suspend fun updateSingle(context: Context, appWidgetId: Int, mode: ListWidgetMode) =
        withContext(Dispatchers.IO) {
            updateListWidget(context, AppWidgetManager.getInstance(context), appWidgetId, mode)
        }

    /**
     * 触发式刷新（不挂调用方生命周期）：配置页保存后要立刻重绘组件，
     * 但配置页马上 finish，用 lifecycleScope 会被取消。
     */
    fun requestUpdate(context: Context, appWidgetId: Int, mode: ListWidgetMode) {
        val appContext = context.applicationContext
        launchRefresh(null) {
            updateListWidget(appContext, AppWidgetManager.getInstance(appContext), appWidgetId, mode)
        }
    }

    /** 2x1 下一节组件的触发式刷新（同 [requestUpdate] 的理由） */
    fun requestUpdateNext(context: Context, appWidgetId: Int) {
        val appContext = context.applicationContext
        launchRefresh(null) {
            updateNextWidget(appContext, AppWidgetManager.getInstance(appContext), appWidgetId)
        }
    }

    // ---- 4x2 紧凑周视图 ----

    fun goAsyncUpdateWeekGrid(
        context: Context,
        appWidgetIds: IntArray,
        pendingResult: BroadcastReceiver.PendingResult?,
    ) {
        launchRefresh(pendingResult) {
            bootstrapBackgroundSync(context)
            val manager = AppWidgetManager.getInstance(context)
            appWidgetIds.forEach { updateWeekGridWidget(context, manager, it) }
        }
    }

    suspend fun updateAllOfProviderWeekGrid(
        context: Context,
        providerClass: Class<*>,
    ): Boolean = withContext(Dispatchers.IO) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(android.content.ComponentName(context, providerClass))
        ids.forEach { updateWeekGridWidget(context, manager, it) }
        ids.isNotEmpty()
    }

    private suspend fun updateWeekGridWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
    ) {
        val bindingCode = WidgetBindingStore.load(context, appWidgetId).semesterCode
        val data = WidgetDataCache.get(context, bindingCode)
        val semester = data.semester
        val title = "本周课表"
        val subtitle = semester?.startLocalDate?.let {
            WeekCalculator.currentWeekOrNull(it, semester.totalWeeks, LocalDate.now())
        }?.let { "第 $it 周" } ?: "假期中"

        val views = RemoteViews(context.packageName, R.layout.widget_week_grid)
        val appearance = WidgetAppearanceStore.load(context, appWidgetId)
        applyAppearance(context, views, appearance, isListLayout = true)
        applyBlurredBackground(context, views, appearance)
        views.setTextViewText(R.id.widget_title, title)
        views.setTextViewText(R.id.widget_subtitle, subtitle)
        views.setTextViewText(R.id.widget_empty, "本周没有课")

        val serviceIntent = Intent(context, WeekGridWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        views.setRemoteAdapter(R.id.widget_grid, serviceIntent)
        views.setEmptyView(R.id.widget_grid, R.id.widget_empty)

        val listTapIntent = Intent(context, MainActivity::class.java)
        views.setPendingIntentTemplate(
            R.id.widget_grid,
            PendingIntent.getActivity(
                context,
                appWidgetId,
                listTapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            ),
        )

        val headerIntent = Intent(context, MainActivity::class.java)
        views.setOnClickPendingIntent(
            R.id.widget_header,
            PendingIntent.getActivity(
                context,
                appWidgetId + HEADER_REQUEST_CODE_OFFSET,
                headerIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )

        appWidgetManager.updateAppWidget(appWidgetId, views)
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_grid)
    }

    fun requestUpdateWeekGrid(context: Context, appWidgetId: Int) {
        val appContext = context.applicationContext
        launchRefresh(null) {
            updateWeekGridWidget(appContext, AppWidgetManager.getInstance(appContext), appWidgetId)
        }
    }

    /**
     * 配置页「保存」的完整收尾：落盘外观与课表绑定 → 补注册后台刷新 → 重绘该实例。
     *
     * 整体放进 [launchRefresh] 的后台协程，调用方可以立刻 `setResult` + `finish`：
     * 协程持有 applicationContext，不随配置页销毁。
     */
    fun saveConfigAndRefresh(
        context: Context,
        appWidgetId: Int,
        appearance: WidgetAppearance,
        binding: WidgetBinding,
        providerClassName: String?,
        mode: ListWidgetMode,
    ) {
        val appContext = context.applicationContext
        launchRefresh(null) {
            WidgetAppearanceStore.save(appContext, appWidgetId, appearance)
            WidgetBindingStore.save(appContext, appWidgetId, binding)
            bootstrapBackgroundSync(appContext)
            val manager = AppWidgetManager.getInstance(appContext)
            when (providerClassName) {
                NextClassWidgetProvider::class.java.name ->
                    updateNextWidget(appContext, manager, appWidgetId)
                WeekGridWidgetProvider::class.java.name ->
                    updateWeekGridWidget(appContext, manager, appWidgetId)
                else -> updateListWidget(appContext, manager, appWidgetId, mode)
            }
        }
    }

    /** 由 Provider 类名反查内容模式；未知 Provider 返回 null */
    fun modeOfProvider(providerClassName: String?): ListWidgetMode? = when (providerClassName) {
        TodayWidgetProvider::class.java.name -> ListWidgetMode.TODAY
        TomorrowWidgetProvider::class.java.name -> ListWidgetMode.TOMORROW
        WeekWidgetProvider::class.java.name -> ListWidgetMode.WEEK
        else -> null
    }

    private fun dateLabel(date: LocalDate): String =
        date.format(
            java.time.format.DateTimeFormatter.ofPattern("M月d日 EEEE", java.util.Locale.CHINA),
        )

    private fun weekTitle(semester: Semester?): String {
        val week = currentWeekOrNull(semester, LocalDate.now())
        return week?.let { "第 $it 周课表" } ?: "本周课表"
    }

    private fun currentWeekOrNull(semester: Semester?, today: LocalDate): Int? {
        val start = semester?.startLocalDate ?: return null
        return WeekCalculator.currentWeekOrNull(start, semester.totalWeeks, today)
    }
}
