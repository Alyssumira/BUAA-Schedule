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

    /** 翻周热区的 requestCode 基准：再按实例 + 方向错开，与上面两类互不覆盖 */
    private const val WEEK_BROWSE_REQUEST_CODE_BASE = 200_000

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
     *
     * [onEnabled] 也必须走这里而不是自己裸调：首启保护与逐步吞异常只有这一份实现。
     */
    internal fun bootstrapBackgroundSync(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBootstrapAt < BOOTSTRAP_INTERVAL_MS) return
        lastBootstrapAt = now
        // 每一步都得自己吞异常：Android 14+/HyperOS 默认拒绝「精确闹钟」，
        // scheduleWidgetMidnight 会抛 SecurityException。它一旦顺着协程抛出去，
        // 调用方后面的 updateAppWidget 就整轮不执行 —— 用户看到的正是
        // 「组件编辑保存后不刷新」（组件其实什么都没画错，只是没被重绘）。
        runCatching { BackgroundSync.scheduleWidgetMidnight(context) }
            .onFailure { Log.w(TAG, "零点闹钟注册失败，跨天刷新交给兜底任务", it) }
        runCatching { WidgetFallbackWorker.ensure(context) }
            .onFailure { Log.w(TAG, "组件兜底任务注册失败", it) }
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
            val manager = AppWidgetManager.getInstance(context)
            appWidgetIds.forEach { updateListWidget(context, manager, it, mode) }
            bootstrapBackgroundSync(context)
        }
    }

    /**
     * 列表/网格单元格的稳定 id：**位置在高位、内容指纹在低位**。
     *
     * 宿主（`RemoteViewsAdapter`）拿 `getItemId` 当行视图缓存的键 —— id 不变就直接把
     * 上一次那份 RemoteViews 贴回去，工厂里刚算出来的新内容根本没机会上场。
     * 此前两个工厂的 id 只含 `courseId + 节次`（网格更甚，只有 `position + 外观`），
     * 于是"改课名/换教室后组件不刷新、点一下又变回原样"：改的这两项都不进 id。
     * 位置放高位保证同一列内 id 互不相同（`hasStableIds` 下重复 id 会抛异常），
     * 内容放低位保证任何一处文字变化都会换掉 id。
     */
    fun itemKey(position: Int, contentStamp: Long): Long =
        ((position + 1L) shl 32) or (contentStamp and 0xFFFF_FFFFL)

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
        val binding = WidgetBindingStore.load(context, appWidgetId)
        val data = WidgetDataCache.get(context, binding.semesterCode)
        val semester = data.semester

        val today = LocalDate.now()
        // 本周模式可以翻周（审查 3.5）：标题/副标题/工厂取数都以「浏览周」为准。
        // 工厂那边也自己从 store 读同一个偏移，两边必须用同一个纯函数才算得一致。
        val displayWeek = if (mode == ListWidgetMode.WEEK) {
            displayWeekOf(semester, today, binding)
        } else {
            null
        }
        val title = when (mode) {
            ListWidgetMode.TODAY -> "今日课程"
            ListWidgetMode.TOMORROW -> "明日课程"
            ListWidgetMode.WEEK -> weekTitle(displayWeek)
        }
        // 副标题：周课表显示周次；今日/明日显示具体日期（与列表口径一致）
        // + 「第 N 周」——周次此前在任何组件上都不显示，用户答不出"明天那天上不上这门课"
        val subtitle = when (mode) {
            ListWidgetMode.WEEK -> weekSubtitle(semester, displayWeek, today)
            ListWidgetMode.TOMORROW -> dateLabel(today.plusDays(1)) + weekSuffix(semester, today.plusDays(1))
            ListWidgetMode.TODAY -> dateLabel(today) + weekSuffix(semester, today)
        }
        // 空态文案必须在主视图里下发：RemoteViewsFactory 拿不到 setEmptyView 指向的那个视图，
        // 此前只能靠布局硬编码，导致「明日课程」空态显示的是「今天没有课」。
        val realWeek = currentWeekOrNull(semester, today)
        val emptyText = when {
            semester != null && realWeek == null -> "假期中，暂无课表"
            mode == ListWidgetMode.TOMORROW -> "明天没有课"
            // 翻到别的周还写「本周没有课」就是在说谎
            mode == ListWidgetMode.WEEK && displayWeek != null && displayWeek != realWeek ->
                "这一周没有课"
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
        if (mode == ListWidgetMode.WEEK) {
            applyWeekBrowse(
                context = context,
                views = views,
                appWidgetId = appWidgetId,
                appearance = appearance,
                providerClass = WeekWidgetProvider::class.java,
                enabled = semester != null,
            )
        }

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
        pendingResult: BroadcastReceiver.PendingResult?,
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
        val appearance = WidgetAppearanceStore.load(context, appWidgetId)
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
            // 段落与顺序交给 rowFields（审查 3.2 + 3.7）：默认口径把教师排在节次之前，
            // 因为"第5-6节"在 14:00 里已经隐含，而教师才回答"是不是我该去的那个班"。
            val fields = appearance.effectiveRowFields(WidgetAppearance.DEFAULT_NEXT_ROW_FIELDS)
            views.setTextViewText(
                R.id.widget_next_meta,
                fields.mapNotNull { field ->
                    when (field) {
                        WidgetRowField.TIME -> "$dayName $timeText"
                        WidgetRowField.TEACHER -> window.teacher?.takeIf { it.isNotBlank() }
                        WidgetRowField.PERIODS -> window.sectionText.takeIf { it.isNotBlank() }
                        WidgetRowField.LOCATION -> window.location?.takeIf { it.isNotBlank() }
                        // 下一节课的周次由那个日期隐含，再写一遍只是占位
                        WidgetRowField.WEEKS -> null
                    }
                }.joinToString(" · "),
            )
        }

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
        // 偏移的夹取不必在这里再做一遍：displayWeekOf 内部就把「基准周 + 偏移」夹在 [1, totalWeeks]，
        // 历史脏值最坏也只是停在学期首/末周。
        val binding = WidgetBindingStore.load(context, appWidgetId)
        val data = WidgetDataCache.get(context, binding.semesterCode)
        val semester = data.semester
        val today = LocalDate.now()
        val displayWeek = displayWeekOf(semester, today, binding)
        val title = weekTitle(displayWeek)
        val subtitle = weekSubtitle(semester, displayWeek, today)

        val views = RemoteViews(context.packageName, R.layout.widget_week_grid)
        val appearance = WidgetAppearanceStore.load(context, appWidgetId)
        applyAppearance(context, views, appearance, isListLayout = true)
        applyBlurredBackground(context, views, appearance)
        views.setTextViewText(R.id.widget_title, title)
        views.setTextViewText(R.id.widget_subtitle, subtitle)
        views.setTextViewText(
            R.id.widget_empty,
            if (displayWeek != null && displayWeek != currentWeekOrNull(semester, today)) {
                "这一周没有课"
            } else {
                "本周没有课"
            },
        )
        applyWeekBrowse(
            context = context,
            views = views,
            appWidgetId = appWidgetId,
            appearance = appearance,
            providerClass = WeekGridWidgetProvider::class.java,
            enabled = semester != null,
        )

        val serviceIntent = Intent(context, WeekGridWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        views.setRemoteAdapter(R.id.widget_grid, serviceIntent)
        views.setEmptyView(R.id.widget_grid, R.id.widget_empty)

        // 格子点击（审查 3.6）：模板只给缺省值，真正的星期序号由工厂每格 fillInIntent 覆盖。
        // 缺省值必须留在这里——宿主合并 extras 前的第一次点击会直接吃模板。
        val listTapIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(WidgetNavigation.EXTRA_DAY_OF_WEEK, 0)
        }
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
     * 表头的「上一周 / 下一周」热区（审查 3.5 + P2#11）。
     *
     * 用显式组件意图发给该 Provider 自己：manifest 里不需要为此加 intent-filter
     * （显式意图不受 filter 匹配限制），组件侧也就无须改动应用级配置。
     */
    private fun applyWeekBrowse(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        appearance: WidgetAppearance,
        providerClass: Class<out AppWidgetProvider>,
        enabled: Boolean,
    ) {
        val ink = appearance.bodyColorFor(appearance.resolvedBackground(context))
        listOf(R.id.widget_week_prev to -1, R.id.widget_week_next to 1).forEach { (viewId, delta) ->
            views.setTextViewText(viewId, if (delta < 0) "上周" else "下周")
            views.setTextColor(viewId, ink)
            // 没有学期就没有可浏览的周次：留着两个点了没反应的方块比藏起来更糟
            views.setViewVisibility(viewId, if (enabled) View.VISIBLE else View.GONE)
            if (!enabled) return@forEach
            val intent = Intent(context, providerClass).apply {
                action = WidgetNavigation.ACTION_BROWSE_WEEK
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(WidgetNavigation.EXTRA_WEEK_DELTA, delta)
            }
            views.setOnClickPendingIntent(
                viewId,
                PendingIntent.getBroadcast(
                    context,
                    // requestCode 必须与标题/列表的点击区分开：PendingIntent 判等不看 extras
                    WEEK_BROWSE_REQUEST_CODE_BASE + appWidgetId + (if (delta > 0) 1 else 0),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
    }

    /**
     * 收到翻周广播：改偏移 → 落盘 → 重绘该实例。
     *
     * 由 [WeekWidgetProvider] / [WeekGridWidgetProvider] 的 `onReceive` 转发，
     * 广播回调在主线程，所以照旧走 [launchRefresh] 的后台协程。
     */
    fun goAsyncBrowseWeek(
        context: Context,
        intent: Intent,
        pendingResult: BroadcastReceiver.PendingResult?,
        mode: ListWidgetMode?,
    ) {
        val appContext = context.applicationContext
        launchRefresh(pendingResult) {
            val appWidgetId = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
            val delta = intent.getIntExtra(WidgetNavigation.EXTRA_WEEK_DELTA, 0)
            if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID || delta == 0) return@launchRefresh
            browseWeek(appContext, appWidgetId, delta, mode)
        }
    }

    private suspend fun browseWeek(
        context: Context,
        appWidgetId: Int,
        delta: Int,
        mode: ListWidgetMode?,
    ) {
        val manager = AppWidgetManager.getInstance(context)
        val binding = WidgetBindingStore.load(context, appWidgetId)
        val semester = WidgetDataCache.get(context, binding.semesterCode).semester
        val base = browseBaseWeek(semester, LocalDate.now())
        if (semester == null || base == null) {
            // 没有可翻的周：重绘一次把状态摆回去（可能刚被清掉学期）
            runCatching {
                if (mode == null) updateWeekGridWidget(context, manager, appWidgetId)
                else updateListWidget(context, manager, appWidgetId, mode)
            }.onFailure { Log.w(TAG, "组件翻周重绘失败", it) }
            return
        }
        val offset = stepWeekOffset(
            base,
            semester.totalWeeks,
            effectiveWeekOffset(binding, base),
            delta,
        )
        WidgetBindingStore.save(
            context,
            appWidgetId,
            binding.copy(weekOffset = offset, weekOffsetBase = base),
        )
        runCatching {
            if (mode == null) updateWeekGridWidget(context, manager, appWidgetId)
            else updateListWidget(context, manager, appWidgetId, mode)
        }.onFailure { Log.w(TAG, "组件翻周重绘失败", it) }
    }

    /**
     * 配置页「保存」的完整收尾：落盘外观与课表绑定（同步，见下方注释）→
     * 补注册后台刷新 → 重绘该实例。
     *
     * 后两步放进 [launchRefresh] 的后台协程，调用方可以立刻 `setResult` + `finish`：
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
        // 配置必须**同步**落盘，且早于调用方的 setResult(RESULT_OK)：桌面一收到
        // RESULT_OK 就广播一次 onUpdate，那次回调读的就是这两份配置。协程慢一步，
        // 组件就用旧外观重绘了——正是"编辑后不立刻刷新"。
        // 这里只是 SharedPreferences 的内存态写入（apply 语义，不等磁盘），
        // 真正的耗时在下面重绘与后台补注册里，那部分才需要挪出主线程（R5 F-25）。
        WidgetAppearanceStore.save(appContext, appWidgetId, appearance)
        WidgetBindingStore.save(appContext, appWidgetId, binding)
        launchRefresh(null) {
            val manager = AppWidgetManager.getInstance(appContext)
            when (providerClassName) {
                NextClassWidgetProvider::class.java.name ->
                    updateNextWidget(appContext, manager, appWidgetId)
                WeekGridWidgetProvider::class.java.name ->
                    updateWeekGridWidget(appContext, manager, appWidgetId)
                else -> updateListWidget(appContext, manager, appWidgetId, mode)
            }
            // 重绘在前：补注册与这次保存无关，不该挡在它前面。
            bootstrapBackgroundSync(appContext)
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

    /** 周课表类组件的标题：把周次写进标题，翻到下周时它不会还自称"本周" */
    private fun weekTitle(week: Int?): String = week?.let { "第 $it 周课表" } ?: "本周课表"

    /**
     * 周课表类组件的副标题。
     *
     * 「第 N 周」必须活在副标题里：标题行是用户可关的（`showTitle`），
     * 关掉之后组件上就不该一点周次信息都不剩（审查 3.5）。
     * 假期中浏览也照样给出周号 + 假期标记，而不是只留一句"假期中"。
     */
    private fun weekSubtitle(semester: Semester?, week: Int?, today: LocalDate): String {
        if (week == null) return "假期中"
        return if (currentWeekOrNull(semester, today) == null) "第 $week 周 · 假期中" else "第 $week 周"
    }

    /** 今日/明日副标题后缀的周次：未设置学期或假期里没有可标的周就不加 */
    private fun weekSuffix(semester: Semester?, date: LocalDate): String =
        currentWeekOrNull(semester, date)?.let { " · 第 $it 周" } ?: ""

    private fun currentWeekOrNull(semester: Semester?, today: LocalDate): Int? {
        val start = semester?.startLocalDate ?: return null
        return WeekCalculator.currentWeekOrNull(start, semester.totalWeeks, today)
    }
}
