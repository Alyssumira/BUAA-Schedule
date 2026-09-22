package com.buaa.schedule.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.core.designsystem.EmptyState
import com.buaa.schedule.core.designsystem.GlassSegmentedControl
import com.buaa.schedule.core.designsystem.GlassSurface
import com.buaa.schedule.core.designsystem.GlassVariant
import com.buaa.schedule.core.designsystem.LocalSceneBackdrop
import com.buaa.schedule.core.designsystem.LocalSemanticPlate
import com.buaa.schedule.core.designsystem.ModalTransition
import com.buaa.schedule.core.designsystem.Personalization
import com.buaa.schedule.core.designsystem.SemesterProgressLine
import com.buaa.schedule.core.designsystem.WeekDensityStrip
import com.buaa.schedule.core.designsystem.weekCourseCounts
import com.buaa.schedule.core.designsystem.liquid.CampusPickerButton
import com.buaa.schedule.core.designsystem.liquid.LiquidFab
import com.buaa.schedule.core.designsystem.liquid.LiquidMenu
import com.buaa.schedule.core.designsystem.liquid.LiquidMenuItem
import com.buaa.schedule.core.designsystem.liquid.TermPickerButton
import com.buaa.schedule.core.designsystem.liquid.buaaCampusOptions
import com.buaa.schedule.core.designsystem.motionSpec
import com.buaa.schedule.core.designsystem.MotionTokens
import com.buaa.schedule.data.import.BuaaInPageFetcher
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.startLocalDate
import com.buaa.schedule.domain.schedule.SemesterWeekDates
import com.buaa.schedule.ui.ScheduleViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 首页：背景由全局 SceneBackground 提供，这里只负责
 * 分段控件（周课表/今日）、冲突提示、内容区与液态玻璃 FAB / 弹出菜单。
 */
@Composable
fun HomeScreen(
    onAddCourse: () -> Unit,
    onImportBuaa: () -> Unit,
    onCourseManagement: () -> Unit,
    /** 菜单里的「扫码签到」：智学北航课堂二维码 */
    onSpocSignIn: () -> Unit,
    onCourseClick: (Course) -> Unit,
    /** 手机端悬浮玻璃底栏是否显示：显示时 FAB / 菜单要在底部让位 */
    bottomBarVisible: Boolean = false,
    /** 刚从编辑器保存返回的课程：这张卡要做一次定位脉冲（④机会#4）；-1 = 无 */
    highlightCourseId: Long = -1L,
    /** 脉冲已取走，别让下次回到首页再播一遍 */
    onHighlightConsumed: () -> Unit = {},
    /** 桌面组件 4×2 格子点进来要看的那一天（ISO 1..7，null = 无请求） */
    widgetDayOfWeek: Int? = null,
    /** 日期已经落到日视图，别让回到首页再跳一次 */
    onWidgetDayConsumed: () -> Unit = {},
    viewModel: ScheduleViewModel = viewModel(
        factory = ScheduleViewModel.Factory(
            androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application
        ),
    ),
) {
    val state by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val termOptions by viewModel.buaaTermOptions.collectAsState()
    val currentTerm by viewModel.buaaTermCode.collectAsState()
    val refreshing by viewModel.buaaRefreshing.collectAsState()
    val specialDays by viewModel.specialDays.collectAsState()
    // "今天"来自 ViewModel 的跨午夜滴答，不在组合期读时钟：读时钟不是快照订阅，
    // 课表挂着不动跨过零点时没有任何东西因此重组，顶栏日期、「今天有没有课」
    // （它决定首帧落在周视图还是今日页）会一起停在昨天。
    val today = state.today
    val hasTodayCourses = remember(state.courses, state.semester, today) {
        val semester = state.semester
        val semesterStart = semester?.startLocalDate
        val week = semesterStart?.let {
            com.buaa.schedule.domain.schedule.WeekCalculator.currentWeekOrNull(
                it, semester.totalWeeks, today,
            )
        }
        when {
            semesterStart == null -> state.courses.any { it.dayOfWeek == today.dayOfWeek.value }
            week == null -> false
            else -> state.courses.any { it.dayOfWeek == today.dayOfWeek.value && it.weeks.contains(week) }
        }
    }
    // 定位脉冲（T-36）：只在"刚从编辑器返回"的那一次生效，播完就清，
    // 否则每次切回首页都会对着同一张卡再呼吸一遍。
    var pulseCourseId by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(highlightCourseId) {
        if (highlightCourseId >= 0L) {
            pulseCourseId = highlightCourseId
            onHighlightConsumed()
            delay(PulseHoldMs)
            pulseCourseId = -1L
        }
    }
    // 转屏 = Activity 重建。这里几项一律 rememberSaveable（下面 menuOpen / 冲突向导 /
    // 校区筛选早就是 saveable 的）：用 remember 的话转一次屏，用户翻到的那一周、
    // 选中的页签、挑中的那一天全被静默清零，读起来像"应用把我的课表重置了"（P2）。
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    // 首帧不要"先画周课表、再跳到今日"：数据到位前先决定页签，
    // 决定完成之前不渲染课表内容，这样第一帧画出来就是正确的页签。
    // （此前是先把 selectedTab=0 的周视图画出来，LaunchedEffect 再改成 1，肉眼可见地闪一下。）
    var tabDecided by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.loading, hasTodayCourses) {
        if (!state.loading && !tabDecided) {
            selectedTab = if (hasTodayCourses) 1 else 0
            tabDecided = true
        }
    }
    // 进入主页若有保留教务会话，拉取学期列表供选择器使用
    LaunchedEffect(Unit) {
        viewModel.refreshBuaaTerms()
        // 假期/调休标注：这一路只覆盖"回首页"这一个时机。真正把关口的两趟补上的是
        // 会话刚建立（ScheduleViewModel 收 BuaaWebSession.sessionRetained）与下面那条
        // 屏上月份 —— 冷启动时这一趟多半跑在会话恢复之前，判据会留下一行「无可用教务会话」
        viewModel.refreshSpecialDays()
    }
    // 「浏览到哪一周 / 哪一天」null = 跟随当前教学周、今天。
    // 转屏要活下来，所以存的是**非空原始类型**：rememberSaveable 的类型参数上界是 Any，
    // 直接写 mutableStateOf<Int?>(null) / <LocalDate?> 一个能存进 Bundle 的都没有
    // （LocalDate 连编译都过不去，Int? 会静默不落盘），一律用 -1 当"没手动选过"。
    var browseWeekState by rememberSaveable { mutableIntStateOf(-1) }
    val browseWeek: Int? = browseWeekState.takeIf { it >= 0 }
    fun setBrowseWeek(week: Int?) {
        browseWeekState = week ?: -1
    }
    var browseDateEpochDay by rememberSaveable { mutableLongStateOf(-1L) }
    // 这一笔浏览是**哪一天按下的**：与浏览日成对落盘，跨午夜以后判据靠它把旧浏览日作废。
    // 只存浏览日、不存按下日，就没有任何输入能区分「今天刚翻的」与「昨天翻的」——
    // 于是 rememberSaveable（它比进程活得长）把昨天那一屏一直活到今天早上，
    // 用户读到的就是「凌晨还是显示前一天的课表」（T68）。
    var browseDateAnchorEpochDay by rememberSaveable { mutableLongStateOf(-1L) }
    val browseDate: LocalDate? =
        if (browseDateEpochDay < 0L) null else LocalDate.ofEpochDay(browseDateEpochDay)
    val browseDateAnchoredOn: LocalDate? =
        if (browseDateAnchorEpochDay < 0L) null else LocalDate.ofEpochDay(browseDateAnchorEpochDay)
    fun setBrowseDate(date: LocalDate?, on: LocalDate = today) {
        browseDateEpochDay = date?.toEpochDay() ?: -1L
        // 两个槽位在同一处写完：绕过去单写浏览日，就会留下一笔永不过期的浏览
        browseDateAnchorEpochDay = if (date == null) -1L else on.toEpochDay()
    }
    // 全页唯一一份「body 这一帧画哪一天」：日视图的 date、顶栏第二行、今日页标题、屏上月份
    // 都读它，不再各自 `browseDate ?: today` 算一遍——各算一遍就是各说一天（T56 那一族的另一半）。
    val browseDateOnScreen = dayViewDate(today, browseDateAnchoredOn, browseDate)
    // 屏上月份 → 标注补抓（T60）。周视图那一周可以横跨两个自然月，日视图又是单独一个月，
    // 所以是"至多三个"而不是一个。
    // 为什么这件事要界面上报：补抓判据以前只看"本月 + 下月"，用户翻到跨月的那一周
    // （或寒假那一周）时那个月从没进过缓存、也从没被请求过，表头上的「休/班」就一直不出，
    // 而这一档在日志里连一行痕迹都没有。"看得见"本身就是唯一可靠的触发条件。
    // 键必须把浏览状态全列出来：漏 browseWeek 就是翻出跨月的那一周不补抓，
    // 漏 browseDate / browseDateOnScreen 是日视图翻月不补抓（同一颗坑的另一半）——
    // 报的必须是**真的画在屏上**的那一天，读没过期判据管过的 raw browseDate 会替一个
    // 界面上根本没有的月份去补抓。学期没读到时算不出周区间，
    // 就只报今天那一个月 —— 与 WeekView 没有 weekStartDate 就不画标注的降级方向一致。
    val specialDayMonths = remember(
        state.semester,
        browseWeek,
        state.currentWeek,
        browseDate,
        browseDateOnScreen,
        today,
    ) {
        buildList {
            val span = SemesterWeekDates.spanOf(state.semester, browseWeek ?: state.currentWeek)
            span?.let { (monday, sunday) ->
                add(java.time.YearMonth.from(monday))
                add(java.time.YearMonth.from(sunday))
            }
            add(java.time.YearMonth.from(browseDateOnScreen))
        }.distinct()
    }
    LaunchedEffect(specialDayMonths) {
        viewModel.onSpecialDayVisibleMonths(specialDayMonths)
    }
    // 桌面组件 4×2 格子 → 那一天的日视图（T-26）。
    // 必须等数据到位：日期是从"当前浏览的那一教学周"推出来的，学期没读到就会算错周。
    LaunchedEffect(widgetDayOfWeek, state.loading) {
        val dow = widgetDayOfWeek ?: return@LaunchedEffect
        if (state.loading) return@LaunchedEffect
        val week = browseWeek ?: state.currentWeek
        val semesterStart = state.semester?.startLocalDate
        // 周 → 那一周周一的换算走 SemesterWeekDates（全仓只此一份），这里只补「第几天」
        val jumpedDate = if (semesterStart != null && week != null) {
            SemesterWeekDates.mondayOf(semesterStart, week)?.plusDays((dow - 1).toLong())
        } else {
            null
        }
        setBrowseDate(jumpedDate ?: run {
            // 假期 / 未设学期：没有周可依据，退成"最近的那个星期几"（今天也算）
            today.minusDays(((today.dayOfWeek.value - dow + 7) % 7).toLong())
        })
        selectedTab = 1
        // 首帧页签决策不能再把这次跳转改回周视图
        tabDecided = true
        onWidgetDayConsumed()
    }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    // 冲突处理向导
    var showConflictWizard by rememberSaveable { mutableStateOf(false) }
    // 校区筛选：0 = 全部；此前按钮只改组件内状态，选了不影响课表
    var campusFilter by rememberSaveable { mutableIntStateOf(0) }

    val visibleCourses = remember(state.courses, campusFilter) {
        val target = buaaCampusOptions.getOrNull(campusFilter)
        if (campusFilter == 0 || target == null) {
            state.courses
        } else {
            state.courses.filter { it.campus?.contains(target) == true }
        }
    }

    // 顶栏左侧：当前浏览到第几周 + 这一周的日期（参考稿版式）
    val displayWeekNumber = browseWeek ?: state.currentWeek
    val weekHeadline = weekHeadline(
        hasSemester = state.semester != null,
        displayWeek = displayWeekNumber,
        currentWeek = state.currentWeek,
    )
    // 第二行跟第一行同源：浏览别的周时显示那一周的周一，日视图真的画着别的那一天时显示那一天，
    // 见 TopBarDateLabel.kt。
    // 键必须把五个入参全列出来——漏 browseWeek 就是翻周时日期停在今天（T56 修的就是它），
    // 漏 browseDateOnScreen 就是顶栏写着今天、body 画着用户翻到的那一天（T68 修的就是它），
    // 漏掉学期/今天则是换学期、跨午夜后仍显示旧日期（T41/T43 同类坑）。
    val semesterStart = state.semester?.startLocalDate
    val dateLabel = remember(semesterStart, state.currentWeek, browseWeek, today, browseDateOnScreen) {
        topBarDateLabel(semesterStart, state.currentWeek, browseWeek, today, browseDateOnScreen)
    }

    // 冲突课程 id 集合：周视图/日视图两个分支各算一次（此前是两处重复的 flatMap+toSet），
    // 而且每次重组都重算。这里派生为一个 remember 值，两个分支共用同一份。
    val conflictCourseIds: Set<Long> = remember(state.conflicts) {
        state.conflicts.flatMap { listOf(it.first.id, it.second.id) }.toSet()
    }

    // 周次异动摘要（③C-02）：周次此前在课表界面完全不可见，单周课、只上到第 8 周的课
    // 看起来都像"课没了"。按课程 id 集合比对本周与上周，两段课只算一门。
    val weekChangeSummary = remember(visibleCourses, browseWeek, state.currentWeek) {
        val week = browseWeek ?: state.currentWeek
        if (week == null || week <= 1) {
            null
        } else {
            val thisWeek = visibleCourses.filter { it.weeks.contains(week) }
                .mapTo(mutableSetOf()) { it.id }
            val lastWeek = visibleCourses.filter { it.weeks.contains(week - 1) }
                .mapTo(mutableSetOf()) { it.id }
            val added = (thisWeek - lastWeek).size
            val removed = (lastWeek - thisWeek).size
            when {
                added == 0 && removed == 0 -> null
                added > 0 && removed > 0 -> "比上周多 $added 门、少 $removed 门"
                added > 0 -> "比上周多 $added 门"
                else -> "比上周少 $removed 门"
            }
        }
    }

    // 首次导入前的空状态：没有课程时在内容区叠一张引导卡，告诉新用户从哪开始。
    val showFirstRunEmpty = !state.loading && state.courses.isEmpty()

    // 拖拽移动课程：宽屏/窄屏两个分支共用同一份逻辑（此前是两段完全重复的 lambda）
    // remember 住 lambda 身份：否则每次重组都是新实例，会把 WeekView 整棵子树拖着一起重组。
    val moveWeek = browseWeek
    val moveCurrentWeek = state.currentWeek
    val handleCourseMove: (Course, Int, Int, Boolean) -> Unit = remember(viewModel, scope, moveWeek, moveCurrentWeek) {
        // move@ 具名标签：这里的早退要退出的是"这一次移动"，而不是 remember 的
        // lambda 本身（那个 lambda 的返回值是 handler，不能空手 return）。
        move@{ course, newDayIndex, newStartPeriod, thisWeekOnly ->
            val delta = newStartPeriod - course.startPeriod
            val shiftedPeriods = course.periods.map { it + delta }.sorted()
            // 拖动的是非首段时，整课平移会把别的段推出课表（1..MAX_PERIOD）之外。
            // 越界就静默取消这次移动：卡片自己回弹即可，写库报错弹窗只会平白吓人。
            if (shiftedPeriods.any { it < 1 || it > com.buaa.schedule.domain.schedule.CourseConstraints.MAX_PERIOD }) {
                return@move
            }
            val shifted = course.copy(
                dayOfWeek = newDayIndex + 1,
                periods = shiftedPeriods,
            )
            scope.launch {
                if (thisWeekOnly) {
                    val week = moveWeek ?: moveCurrentWeek
                    if (week == null) {
                        viewModel.updateCourse(shifted)
                    } else {
                        // 只改冲突/本周：partialWeeks 会拆出新行，其余周保持原排课
                        viewModel.updateCourse(
                            shifted.copy(weeks = listOf(week)),
                            com.buaa.schedule.domain.model.CourseSaveOptions(partialWeeks = true),
                        )
                    }
                } else {
                    viewModel.updateCourse(shifted)
                }
            }
        }
    }

    // 缩放改节次：松手后更新该课程的连续节次段（仅作用于整门课）
    val handleCourseResize: (Course, List<Int>) -> Unit = remember(viewModel, scope) {
        { course, newPeriods ->
            scope.launch { viewModel.updateCourse(course.copy(periods = newPeriods.sorted())) }
        }
    }

    // 长按菜单删除：走统一删除逻辑（含撤销栈），并在**这条路径上**给出撤销入口。
    // 删除确认框承诺了"本会话内可撤销"，而首页此前既没有 SnackbarHost 也不渲染
    // viewModel.importMessage —— 承诺在界面上根本没有落点（U-02）。
    val snackbarHostState = remember { SnackbarHostState() }
    val handleCourseDelete: (Course) -> Unit = remember(viewModel, scope, snackbarHostState) {
        { course ->
            scope.launch {
                val deleted = viewModel.deleteCourse(course)
                val result = snackbarHostState.showSnackbar(
                    message = if (deleted) "已删除「${course.displayName}」" else "删除失败：${course.displayName} 还在课表里",
                    actionLabel = "撤销".takeIf { deleted },
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) viewModel.undo()
            }
        }
    }

    // 首页操作写进 importMessage 的反馈（拖课/改节次失败、撤销结果、刷新进度）
    // 此前只有导入/设置页会渲染 —— 同一块 StateFlow，用户在课表页上看不见（U-04）。
    // 桥接到本页已有的 SnackbarHost；读完即清，避免下次回到首页再弹一遍陈旧提示。
    val importMessage by viewModel.importMessage.collectAsState()
    LaunchedEffect(importMessage) {
        val message = importMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message.text)
        viewModel.clearImportMessage()
    }

    val sceneBackdrop = LocalSceneBackdrop.current
    // 悬浮玻璃底栏存在时，FAB 与菜单要在底部让出"栏体 + 系统导航栏"的高度；
    // 课表内容本身保持全幅（延伸到栏体背后滚动），这正是悬浮玻璃的视觉来源
    val fabBottomPadding = DesignTokens.spaceXL +
        if (bottomBarVisible) {
            com.buaa.schedule.core.designsystem.floatingBottomBarClearance()
        } else 0.dp

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── 紧凑顶栏（参考稿布局）──
            // 第一行：左「第 N 周 / 今天日期」，右「周课表 | 今日」分段控件。
            // 之前分段控件单独占一行、学期/校区又占一行，顶部一共吃掉三行高度。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = DesignTokens.spaceL,
                        end = DesignTokens.spaceS,
                        top = DesignTokens.spaceXS,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        // 周侧两行、今日侧一行：不做尺寸过渡的话切换瞬间整行高度塌掉一半，
                        // 右边的分段控件跟着跳位（M1）
                        .animateContentSize(motionSpec<IntSize>()),
                ) {
                    // 今日页签：日期/周次由 DayView 页头负责（那里带 ‹ › 日期导航），
                    // 这里再写一遍就会出现三个「第 N 周」+ 两个日期。
                    Crossfade(
                        targetState = selectedTab == 0,
                        animationSpec = motionSpec<Float>(),
                    ) { isWeekTab ->
                        if (isWeekTab) {
                            Column {
                                Text(
                                    text = weekHeadline,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = dateLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        } else {
                            Text(
                                // 标题与 body 同源：画着别的那一天时不许自称「今日」（T68）。
                                // 措辞沿用本仓「（浏览）」这一记号，不写具体日期 ——
                                // 日视图页头紧挨着下面就写着那一天，再摆一遍是以前那张
                                // 「三个第 N 周 + 两个日期」返工单要治的东西。
                                text = dayTabHeadline(today, browseDateOnScreen),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                GlassSegmentedControl(
                    options = listOf("周课表", "今日"),
                    selectedIndex = selectedTab,
                    onSelect = { selectedTab = it },
                )
            }

            // 第二行：学期切换 | 时间模式 | ‹ 周次 › | 校区切换
            // 切到「今日」时只保留学期与校区：周次步进翻的是周课表，日视图按日期翻页，
            // 时间模式开关也只改周课表网格——留在这一行里点了没反应，反而显得顶栏杂乱。
            ScheduleToolbarRow(
                showWeekNav = selectedTab == 0,
                weekHeadline = weekHeadline,
                displayWeek = browseWeek ?: state.currentWeek,
                currentWeek = state.currentWeek,
                totalWeeks = (state.semester?.totalWeeks ?: 20)
                    .coerceIn(1, com.buaa.schedule.domain.schedule.CourseConstraints.MAX_TOTAL_WEEKS),
                // remember：这条映射每次翻周都会重建十几个 Set，而顶栏本身就在重组
                courseWeeks = remember(visibleCourses) {
                    visibleCourses.map { it.weeks.toSet() }
                },
                onBrowseWeekChange = { setBrowseWeek(it) },
                termSlot = if (termOptions.isNotEmpty()) {
                    {
                        TermPickerButton(
                            termOptions = termOptions,
                            currentTerm = currentTerm,
                            refreshing = refreshing,
                            onTermSelected = { code -> viewModel.refreshFromBuaa(code) },
                        )
                    }
                } else {
                    null
                },
                campusSlot = {
                    CampusPickerButton(
                        selectedCampusIndex = campusFilter,
                        onCampusSelected = { campusFilter = it },
                    )
                },
            )

            AnimatedVisibility(
                visible = state.conflicts.isNotEmpty(),
                // 默认进出场不读 reduce-motion（④M-01 漏判的六处之一）
                enter = fadeIn(motionSpec<Float>()) + expandVertically(motionSpec<IntSize>()),
                exit = fadeOut(motionSpec<Float>()) + shrinkVertically(motionSpec<IntSize>()),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DesignTokens.spaceL, vertical = DesignTokens.spaceS),
            ) {
                GlassSurface(
                    variant = GlassVariant.ALERT,
                    semanticTint = MaterialTheme.colorScheme.error,
                    contentPadding = DesignTokens.spaceM,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            role = Role.Button,
                            onClickLabel = "按建议处理冲突",
                        ) { showConflictWizard = true },
                ) {
                    Text(
                        text = "存在 ${state.conflicts.size} 组课程时间冲突，点这里按建议处理。",
                        style = MaterialTheme.typography.bodyMedium,
                        // 卡位只声明意图（这张卡是 error），底板与文字成对由 GlassSurface 解
                        color = LocalSemanticPlate.current?.foreground
                            ?: MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // 周次异动条（③C-02）：刻意不做成玻璃卡——上面那条冲突提示要用户去处理，
            // 这一条只是给"换周了"留个痕迹，做成同款 ALERT 就成了噪声。
            AnimatedVisibility(
                visible = selectedTab == 0 && weekChangeSummary != null,
                enter = fadeIn(motionSpec<Float>()) + expandVertically(motionSpec<IntSize>()),
                exit = fadeOut(motionSpec<Float>()) + shrinkVertically(motionSpec<IntSize>()),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DesignTokens.spaceL),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(DesignTokens.iconSmall),
                    )
                    Spacer(modifier = Modifier.width(DesignTokens.spaceS))
                    Text(
                        // 收成一行：这一条只是"换周了"的痕迹，换成两行会把顶栏再垫高一层，
                        // 而"完整周次"在课程详情里本来就有（真机反馈：顶栏有点厚）
                        text = "这一周的课程与上周不同：${weekChangeSummary.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                // 宽屏（≥breakpointWide）：周视图 | 日视图 双栏并排，平板不再来回切页签
                val isWideScreen =
                    androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp >=
                        DesignTokens.breakpointWide
                if (!tabDecided) {
                    // 页签还没定下来：先什么都不画（背景仍在），避免闪一下周课表再跳今日
                } else if (isWideScreen && selectedTab == 0) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        WeekView(
                            courses = visibleCourses,
                            semester = state.semester,
                            timeSlots = state.timeSlots,
                            currentWeek = state.currentWeek,
                            displayWeek = browseWeek ?: state.currentWeek,
                            today = today,
                            onBrowseWeekChange = { setBrowseWeek(it) },
                            onCourseClick = onCourseClick,
                            conflictCourseIds = conflictCourseIds,
                            specialDays = specialDays,
                            onCourseMove = handleCourseMove,
                            onCourseResize = handleCourseResize,
                            onCourseDelete = handleCourseDelete,
                            pulseCourseId = pulseCourseId,
                            modifier = Modifier.weight(3f),
                        )
                        DayView(
                            courses = visibleCourses,
                            semester = state.semester,
                            timeSlots = state.timeSlots,
                            date = browseDateOnScreen,
                            today = today,
                            onDateChange = { setBrowseDate(it) },
                            onCourseClick = onCourseClick,
                            specialDays = specialDays,
                            modifier = Modifier.weight(2f),
                        )
                    }
                } else {
                    // 周/日切换用淡入淡出过渡，避免生硬跳变（参考 SleepDown / 拾光的页面切换质感）
                    Crossfade(
                        targetState = selectedTab,
                        // 默认 1000ms 且不看 reduce-motion：切一页要等一秒，
                        // 而同屏的导航转场是 260ms，两者读起来像两套应用（④M-01）
                        animationSpec = motionSpec<Float>(),
                        modifier = Modifier.fillMaxSize(),
                    ) { tab ->
                        when (tab) {
                            0 -> WeekView(
                                courses = visibleCourses,
                                semester = state.semester,
                                timeSlots = state.timeSlots,
                                currentWeek = state.currentWeek,
                                displayWeek = browseWeek ?: state.currentWeek,
                                today = today,
                                onBrowseWeekChange = { setBrowseWeek(it) },
                                onCourseClick = onCourseClick,
                                conflictCourseIds = conflictCourseIds,
                                specialDays = specialDays,
                                onCourseMove = handleCourseMove,
                                onCourseResize = handleCourseResize,
                                onCourseDelete = handleCourseDelete,
                                pulseCourseId = pulseCourseId,
                            )
                            1 -> DayView(
                                courses = visibleCourses,
                                semester = state.semester,
                                timeSlots = state.timeSlots,
                                date = browseDateOnScreen,
                                today = today,
                                onDateChange = { setBrowseDate(it) },
                                onCourseClick = onCourseClick,
                                specialDays = specialDays,
                            )
                        }
                    }
                }

                // 首启空状态：内容区叠一层引导卡，覆盖空白周/日视图。
                // 导入成功后这张卡是"淡走"的——裸 if 会在课表出现的同时把卡片瞬间抽掉。
                // 写成全限定：Box 嵌在 Column 里，外层 ColumnScope 接收者已被遮蔽，
                // 裸名会被解析成 ColumnScope.AnimatedVisibility 而报"隐式接收者"错
                androidx.compose.animation.AnimatedVisibility(
                    visible = showFirstRunEmpty,
                    enter = fadeIn(motionSpec<Float>()),
                    exit = fadeOut(motionSpec<Float>()),
                    modifier = Modifier.matchParentSize(),
                ) {
                    FirstRunEmptyState(
                        onAddCourse = onAddCourse,
                        onImportBuaa = onImportBuaa,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        ModalTransition(open = showConflictWizard) { modal ->
            ConflictWizardDialog(
                groups = com.buaa.schedule.domain.schedule.CourseConflictResolution
                    .groupConflicts(state.conflicts),
                allCourses = state.courses,
                timeSlots = state.timeSlots,
                modifier = modal,
                onApplyShift = { target, newPeriods ->
                    // 只改冲突周：partialWeeks 会拆出新行，其余周保持原排课。
                    // 作用域必须是 viewModelScope，不能用 rememberCoroutineScope()：
                    // 后者绑在对话框这次 composition 上，用户点完「只改这些周」顺手划走
                    // 对话框，协程就被取消——界面已经显示「已应用」，库里其实没写（P1）。
                    var saved = false
                    val job = viewModel.viewModelScope.launch {
                        saved = viewModel.updateCourse(
                            target.copy(periods = newPeriods),
                            com.buaa.schedule.domain.model.CourseSaveOptions(partialWeeks = true),
                        ) != null
                    }
                    // join 而非 fire-and-forget：调用方要拿到落库结果才能决定
                    // 这一行是标成「已应用」还是把按钮还原让用户重试。
                    // join 只等完成、不传播取消，所以对话框关了也不会打断写入。
                    job.join()
                    saved
                },
                onDismiss = { showConflictWizard = false },
            )
        }
        // 菜单打开时点击空白处关闭（必须在 FAB 之下，否则挡住 FAB 的开合点击）
        if (menuOpen) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { menuOpen = false }
            )
        }

        // 液态玻璃 FAB（右下角，避开底部导航）
        LiquidFab(
            icon = Icons.Default.Add,
            // 按下去是"展开菜单"，不是直接添加：播报必须跟真实行为一致
            contentDescription = if (menuOpen) "关闭添加菜单" else "打开添加菜单",
            onClick = { menuOpen = !menuOpen },
            backdrop = sceneBackdrop,
            expanded = menuOpen,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = DesignTokens.spaceL, bottom = fabBottomPadding),
        )

        // 弹出菜单（SleepDown 样式）：右缘与 FAB 对齐、紧贴 FAB 上方（gap 4dp），
        // 从 FAB 位置以右下锚点生长展开
        LiquidMenu(
            items = listOf(
                LiquidMenuItem(Icons.Default.EditCalendar, "新增课程") { onAddCourse() },
                LiquidMenuItem(Icons.Default.Refresh, "刷新课表") {
                    // 复用保留的登录会话静默重拉；无会话则回退到登录入口
                    if (!viewModel.refreshFromBuaa()) onImportBuaa()
                },
                LiquidMenuItem(Icons.Default.EventAvailable, "跳到本周") {
                    setBrowseWeek(null)
                    setBrowseDate(null)
                },
                LiquidMenuItem(Icons.AutoMirrored.Filled.ListAlt, "课表管理") { onCourseManagement() },
                LiquidMenuItem(Icons.Default.QrCode2, "扫码签到") { onSpocSignIn() },
            ),
            visible = menuOpen,
            onDismiss = { menuOpen = false },
            backdrop = sceneBackdrop,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = DesignTokens.spaceL, bottom = fabBottomPadding)
                .offset(y = -FabLift),
        )

        // 撤销提示条：坐在 FAB 上方那条带子里（与弹出菜单同一个抬升口径）。
        // 这样它不会盖住 FAB，也就不必复刻 Scaffold "提示条出现时顶起 FAB" 那一套。
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = DesignTokens.spaceM,
                    end = DesignTokens.spaceM,
                    bottom = fabBottomPadding + FabLift,
                ),
        )
    }
}

/** FAB 与其上方浮层（弹出菜单 / 撤销提示条）的抬升量：取值见 [DesignTokens.fabLift] */
private val FabLift = DesignTokens.fabLift

/** 定位脉冲的窗口：1.0→1.04→1.0 两段各 DURATION_SHORT(180ms)，再留一点收尾 */
private const val PulseHoldMs = 420L

/** 首启空状态：无课程时的一块引导卡，避免新用户对着空白网格不知所措 */
@Composable
private fun FirstRunEmptyState(
    onAddCourse: () -> Unit,
    onImportBuaa: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = DesignTokens.SCRIM_ALPHA))
            // 遮罩必须吃掉手势：只画半透明底不消费点击的话，底下那张空网格
            // 仍然能被点中（长按/点击都会穿透引导卡）。不用 clickable 是因为
            // 那会在无障碍树里凭空多出一颗没有行为的按钮。
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        EmptyState(
            icon = Icons.Filled.School,
            title = "还没有课程",
            description = "从北航教务一键导入，或先手动添加第一节课。",
            modifier = Modifier.padding(horizontal = DesignTokens.spaceL),
        ) {
            Button(
                onClick = onImportBuaa,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("从北航教务导入") }
            OutlinedButton(
                onClick = onAddCourse,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("手动添加课程") }
        }
    }
}

/**
 * 首页工具条（第二行）：学期切换 ｜ 时间模式 ｜ ‹ 周次 › ｜ 校区切换。
 *
 * 从 WeekView 搬到页面级：这些入口切到「今日」页签时也必须可用
 * （校区筛选同时作用于周课表与今日列表），放在周视图里就会跟着消失。
 * 点中间的周次文字打开「跳转到周次」对话框。
 *
 * [showWeekNav] 为 false（今日页签）时只保留学期与校区两端：周次步进翻的是周课表，
 * 日视图按**日期**翻页（DayView 自带 ‹ 日期 › 与横滑手势），时间模式也只改周课表网格，
 * 留在这一行里既点了没反应，又和页头/日视图的周次文字凑成三份「第 N 周」。
 */
@Composable
private fun ScheduleToolbarRow(
    showWeekNav: Boolean,
    weekHeadline: String,
    displayWeek: Int?,
    currentWeek: Int?,
    totalWeeks: Int,
    courseWeeks: List<Set<Int>>,
    onBrowseWeekChange: (Int?) -> Unit,
    termSlot: (@Composable () -> Unit)?,
    campusSlot: (@Composable () -> Unit)?,
) {
    var showJumpDialog by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val timeMode = Personalization.weekGridMode == Personalization.WEEK_GRID_TIME_24H
    val weeklyCounts = remember(courseWeeks, totalWeeks) {
        weekCourseCounts(courseWeeks, totalWeeks)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DesignTokens.spaceS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        termSlot?.invoke()
        // 切到日视图时这一整簇会消失。原本是 `if` 硬切——下方的内容区在淡入，
        // 顶栏却整块闪现/消失，两个节奏不同步就被读成"卡了一下"。
        // 横向展开收的是自己那一份宽度，所以收起时校区选择器会顺势滑回来，
        // 这正是想要的：SpaceBetween 的分布变了，就得有人补位。
        AnimatedVisibility(
            visible = showWeekNav,
            enter = expandHorizontally(motionSpec(MotionTokens.DURATION_MEDIUM)) +
                fadeIn(motionSpec(MotionTokens.DURATION_MEDIUM)),
            exit = shrinkHorizontally(motionSpec(MotionTokens.DURATION_MEDIUM)) +
                fadeOut(motionSpec(MotionTokens.DURATION_MEDIUM)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
            // 原来是一颗没有字面的时钟图标：点一下就把整张周课表换成 24 小时
            // 时间轴，用户看不出自己改了什么，只会觉得"默认就是时间轴"。
            // 现在把当前模式写在按钮上（课次 = 按节次分行，默认；时间 = 连续时间轴）。
            // 热区与外观分两层：48dp 是**触摸**下限，画出来的胶囊要贴着文字。
            // 此前两者写在同一个 Box 上，而 Box 默认 TopStart 对齐 —— 于是这一排里
            // 立着一块 48dp 高的实心蓝块，「课次/时间」四个字还吊在蓝块上沿
            // （真机反馈：没对齐、有点偏上、顶栏显厚）。
            Box(
                modifier = Modifier
                    .defaultMinSize(minHeight = DesignTokens.minTouchTarget)
                    .clip(RoundedCornerShape(DesignTokens.cornerChip))
                    .clickable(
                        role = Role.Switch,
                        onClickLabel = if (timeMode) "切换到课次行视图" else "切换到 24 小时时间轴",
                    ) {
                        Personalization.weekGridMode = if (timeMode) {
                            Personalization.WEEK_GRID_PERIOD
                        } else {
                            Personalization.WEEK_GRID_TIME_24H
                        }
                        Personalization.save(context)
                    }
                    .semantics {
                        contentDescription = if (timeMode) "时间轴视图" else "课次行视图"
                        stateDescription = if (timeMode) "已选 24 小时时间轴" else "已选课次行"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(DesignTokens.cornerChip))
                        .background(
                            if (timeMode) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .padding(horizontal = DesignTokens.spaceS, vertical = DesignTokens.spaceXS),
                ) {
                    Text(
                        text = if (timeMode) "时间" else "课次",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (timeMode) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(
                onClick = { onBrowseWeekChange(((displayWeek ?: 1) - 1).coerceAtLeast(1)) },
                enabled = (displayWeek ?: 1) > 1,
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一周")
            }
            // Text 是"从顶往下画"的：直接给它 48dp 的下限，字会吊在上沿，
            // 和左右垂直居中的箭头按钮对不齐（真机反馈：偏上）。
            // 下限、点按、居中都放到外层 Box 上。
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(DesignTokens.cornerPanel))
                    .defaultMinSize(minHeight = DesignTokens.minTouchTarget)
                    .clickable(role = Role.Button, onClickLabel = "跳转到周次") { showJumpDialog = true },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.layout.Column(
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = weekHeadline,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (displayWeek == currentWeek) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 「第 3 周」只是一个数：学期过半还是还剩六周得自己算。
                    // 这条线不新增信息层级——它是这行字的图形化，所以同格、同色。
                    SemesterProgressLine(
                        currentWeek = currentWeek,
                        totalWeeks = totalWeeks,
                        modifier = Modifier.padding(top = DesignTokens.spaceXS),
                    )
                }
            }
            IconButton(
                onClick = { onBrowseWeekChange(((displayWeek ?: totalWeeks) + 1).coerceAtMost(totalWeeks)) },
                enabled = (displayWeek ?: totalWeeks) < totalWeeks,
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一周")
            }
            }
        }
        campusSlot?.invoke()
    }

    ModalTransition(open = showJumpDialog) { modal ->
        AlertDialog(
            modifier = modal,
            onDismissRequest = { showJumpDialog = false },
            title = { Text("跳转到周次") },
            text = {
                Column {
                    // 清单只能回答"第 N 周"，回答不了"哪几周其实没课"。
                    // 密度条放在清单上方：看图定位到那一周，再点清单确认——
                    // 两者选的都是同一个 onBrowseWeekChange，不会出现两套语义。
                    WeekDensityStrip(
                        counts = weeklyCounts,
                        currentWeek = currentWeek,
                        displayWeek = displayWeek,
                        onWeekSelected = { week ->
                            onBrowseWeekChange(week)
                            showJumpDialog = false
                        },
                    )
                    Spacer(modifier = Modifier.height(DesignTokens.spaceS))
                    LazyColumn(modifier = Modifier.height(DesignTokens.dialogListMaxHeight)) {
                        items((1..totalWeeks).toList()) { week ->
                            Text(
                                text = if (week == currentWeek) "第 $week 周（本周）" else "第 $week 周",
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (week == currentWeek) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // 内缩写在 clickable 之前：整行（含留白）都是命中区。
                                    // 垂直取 spaceM 而不是 spaceS：bodyLarge 行高约 32dp，
                                    // 两侧各 8dp 只有 40dp，够不到 48dp 触控下限（M3）
                                    .padding(vertical = DesignTokens.spaceM, horizontal = DesignTokens.spaceS)
                                    .clickable {
                                        onBrowseWeekChange(week)
                                        showJumpDialog = false
                                    },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (displayWeek != currentWeek) {
                    TextButton(onClick = {
                        onBrowseWeekChange(null)
                        showJumpDialog = false
                    }) { Text("回到本周") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showJumpDialog = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 顶栏周次标题。
 *
 * 「浏览」= 看到的这一周不是当前教学周，必须由两个周号比出来，不能看
 * "用户手动翻过没有"那个标记：第 2 周翻到第 3 周再翻回第 2 周，人已经回到本周了，
 * 标记却还挂着（浏览）。这也正是工具栏周次文字颜色所用的判据
 * （`displayWeek == currentWeek`），两处统一后标签和颜色不会互相矛盾。
 */
internal fun weekHeadline(
    hasSemester: Boolean,
    displayWeek: Int?,
    currentWeek: Int?,
): String = when {
    !hasSemester -> "未设置学期"
    displayWeek == null -> "假期中"
    displayWeek == currentWeek -> "第${displayWeek}周"
    else -> "第${displayWeek}周（浏览）"
}
