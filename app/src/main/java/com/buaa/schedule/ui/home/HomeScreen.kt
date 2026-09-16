package com.buaa.schedule.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.core.designsystem.GlassSegmentedControl
import com.buaa.schedule.core.designsystem.GlassSurface
import com.buaa.schedule.core.designsystem.GlassVariant
import com.buaa.schedule.core.designsystem.LocalSceneBackdrop
import com.buaa.schedule.core.designsystem.Personalization
import com.buaa.schedule.core.designsystem.liquid.CampusPickerButton
import com.buaa.schedule.core.designsystem.liquid.LiquidFab
import com.buaa.schedule.core.designsystem.liquid.LiquidMenu
import com.buaa.schedule.core.designsystem.liquid.LiquidMenuItem
import com.buaa.schedule.core.designsystem.liquid.TermPickerButton
import com.buaa.schedule.core.designsystem.liquid.buaaCampusOptions
import com.buaa.schedule.data.import.BuaaInPageFetcher
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.startLocalDate
import com.buaa.schedule.ui.ScheduleViewModel
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
    onCourseClick: (Course) -> Unit,
    /** 手机端悬浮玻璃底栏是否显示：显示时 FAB / 菜单要在底部让位 */
    bottomBarVisible: Boolean = false,
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
    val today = LocalDate.now()
    val hasTodayCourses = remember(state.courses, state.semester) {
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
    var selectedTab by remember { mutableIntStateOf(0) }
    // 首帧不要"先画周课表、再跳到今日"：数据到位前先决定页签，
    // 决定完成之前不渲染课表内容，这样第一帧画出来就是正确的页签。
    // （此前是先把 selectedTab=0 的周视图画出来，LaunchedEffect 再改成 1，肉眼可见地闪一下。）
    var tabDecided by remember { mutableStateOf(false) }
    LaunchedEffect(state.loading, hasTodayCourses) {
        if (!state.loading && !tabDecided) {
            selectedTab = if (hasTodayCourses) 1 else 0
            tabDecided = true
        }
    }
    // 进入主页若有保留教务会话，拉取学期列表供选择器使用
    LaunchedEffect(Unit) {
        viewModel.refreshBuaaTerms()
        // 假期/调休标注：先读缓存，再尝试联网补抓（无教务会话时静默跳过）
        viewModel.refreshSpecialDays()
    }
    // null = 跟随当前教学周/今天
    var browseWeek by remember { mutableStateOf<Int?>(null) }
    var browseDate by remember { mutableStateOf<LocalDate?>(null) }
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

    // 顶栏左侧：当前浏览到第几周 + 今天日期（参考稿版式）
    val displayWeekNumber = browseWeek ?: state.currentWeek
    val weekHeadline = when {
        state.semester == null -> "未设置学期"
        displayWeekNumber == null -> "假期中"
        browseWeek == null -> "第${displayWeekNumber}周"
        else -> "第${displayWeekNumber}周（浏览）"
    }
    val todayLabel = remember(today) {
        today.format(
            java.time.format.DateTimeFormatter.ofPattern("M月d日 EEEE", java.util.Locale.CHINA),
        )
    }

    // 冲突课程 id 集合：周视图/日视图两个分支各算一次（此前是两处重复的 flatMap+toSet），
    // 而且每次重组都重算。这里派生为一个 remember 值，两个分支共用同一份。
    val conflictCourseIds: Set<Long> = remember(state.conflicts) {
        state.conflicts.flatMap { listOf(it.first.id, it.second.id) }.toSet()
    }

    // 首次导入前的空状态：没有课程时在内容区叠一张引导卡，告诉新用户从哪开始。
    val showFirstRunEmpty = !state.loading && state.courses.isEmpty()

    // 拖拽移动课程：宽屏/窄屏两个分支共用同一份逻辑（此前是两段完全重复的 lambda）
    // remember 住 lambda 身份：否则每次重组都是新实例，会把 WeekView 整棵子树拖着一起重组。
    val moveWeek = browseWeek
    val moveCurrentWeek = state.currentWeek
    val handleCourseMove: (Course, Int, Int, Boolean) -> Unit = remember(viewModel, scope, moveWeek, moveCurrentWeek) {
        { course, newDayIndex, newStartPeriod, thisWeekOnly ->
            val delta = newStartPeriod - course.startPeriod
            val shifted = course.copy(
                dayOfWeek = newDayIndex + 1,
                periods = course.periods.map { it + delta }.sorted(),
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

    // 长按菜单删除：直接走统一删除逻辑（含撤销栈）
    val handleCourseDelete: (Course) -> Unit = remember(viewModel, scope) {
        { course -> scope.launch { viewModel.deleteCourse(course) } }
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
                        top = DesignTokens.spaceS,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // 今日页签：日期/周次由 DayView 页头负责（那里带 ‹ › 日期导航），
                    // 这里再写一遍就会出现三个「第 N 周」+ 两个日期。
                    if (selectedTab == 0) {
                        Text(
                            text = weekHeadline,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = todayLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    } else {
                        Text(
                            text = "今日课表",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
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
                onBrowseWeekChange = { browseWeek = it },
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
                        .clickable { showConflictWizard = true },
                ) {
                    Text(
                        text = "存在 ${state.conflicts.size} 组课程时间冲突，点这里按建议处理。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                // 宽屏（≥600dp）：周视图 | 日视图 双栏并排，平板不再来回切页签
                val isWideScreen =
                    androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
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
                            onBrowseWeekChange = { browseWeek = it },
                            onCourseClick = onCourseClick,
                            conflictCourseIds = conflictCourseIds,
                            specialDays = specialDays,
                            onCourseMove = handleCourseMove,
                            onCourseResize = handleCourseResize,
                            onCourseDelete = handleCourseDelete,
                            modifier = Modifier.weight(3f),
                        )
                        DayView(
                            courses = visibleCourses,
                            semester = state.semester,
                            timeSlots = state.timeSlots,
                            date = browseDate ?: LocalDate.now(),
                            onDateChange = { browseDate = it },
                            onCourseClick = onCourseClick,
                            modifier = Modifier.weight(2f),
                        )
                    }
                } else {
                    // 周/日切换用淡入淡出过渡，避免生硬跳变（参考 SleepDown / 拾光的页面切换质感）
                    Crossfade(
                        targetState = selectedTab,
                        modifier = Modifier.fillMaxSize(),
                    ) { tab ->
                        when (tab) {
                            0 -> WeekView(
                                courses = visibleCourses,
                                semester = state.semester,
                                timeSlots = state.timeSlots,
                                currentWeek = state.currentWeek,
                                displayWeek = browseWeek ?: state.currentWeek,
                                onBrowseWeekChange = { browseWeek = it },
                                onCourseClick = onCourseClick,
                                conflictCourseIds = conflictCourseIds,
                                specialDays = specialDays,
                                onCourseMove = handleCourseMove,
                                onCourseResize = handleCourseResize,
                                onCourseDelete = handleCourseDelete,
                            )
                            1 -> DayView(
                                courses = visibleCourses,
                                semester = state.semester,
                                timeSlots = state.timeSlots,
                                date = browseDate ?: LocalDate.now(),
                                onDateChange = { browseDate = it },
                                onCourseClick = onCourseClick,
                            )
                        }
                    }
                }

                // 首启空状态：内容区叠一层引导卡，覆盖空白周/日视图
                if (showFirstRunEmpty) {
                    FirstRunEmptyState(
                        onAddCourse = onAddCourse,
                        onImportBuaa = onImportBuaa,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
        }

        // 菜单打开时点击空白处关闭（必须在 FAB 之下，否则挡住 FAB 的开合点击）
        if (showConflictWizard) {
            val conflictScope = rememberCoroutineScope()
            ConflictWizardDialog(
                groups = com.buaa.schedule.domain.schedule.CourseConflictResolution
                    .groupConflicts(state.conflicts),
                allCourses = state.courses,
                onApplyShift = { target, newPeriods ->
                    // 只改冲突周：partialWeeks 会拆出新行，其余周保持原排课
                    conflictScope.launch {
                        viewModel.updateCourse(
                            target.copy(periods = newPeriods),
                            com.buaa.schedule.domain.model.CourseSaveOptions(partialWeeks = true),
                        )
                    }
                },
                onDismiss = { showConflictWizard = false },
            )
        }
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
            contentDescription = "添加课程",
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
                    browseWeek = null
                    browseDate = null
                },
                LiquidMenuItem(Icons.AutoMirrored.Filled.ListAlt, "课表管理") { onCourseManagement() },
            ),
            visible = menuOpen,
            onDismiss = { menuOpen = false },
            backdrop = sceneBackdrop,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = DesignTokens.spaceL, bottom = fabBottomPadding)
                .offset(y = -(56 + 4).dp),
        )
    }
}

/** 首启空状态：无课程时的一块引导卡，避免新用户对着空白网格不知所措 */
@Composable
private fun FirstRunEmptyState(
    onAddCourse: () -> Unit,
    onImportBuaa: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(
            variant = GlassVariant.PANEL,
            contentPadding = DesignTokens.spaceXL,
            shape = RoundedCornerShape(DesignTokens.cornerPage),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DesignTokens.spaceL),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceM),
            ) {
                Icon(
                    imageVector = Icons.Filled.School,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
                Text(
                    text = "还没有课程",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "从北航教务一键导入，或先手动添加第一节课。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
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
    onBrowseWeekChange: (Int?) -> Unit,
    termSlot: (@Composable () -> Unit)?,
    campusSlot: (@Composable () -> Unit)?,
) {
    var showJumpDialog by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val timeMode = Personalization.weekGridMode == Personalization.WEEK_GRID_TIME_24H

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DesignTokens.spaceS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        termSlot?.invoke()
        if (showWeekNav) {
            // 原来是一颗没有字面的时钟图标：点一下就把整张周课表换成 24 小时
            // 时间轴，用户看不出自己改了什么，只会觉得"默认就是时间轴"。
            // 现在把当前模式写在按钮上（课次 = 按节次分行，默认；时间 = 连续时间轴）。
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (timeMode) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .clickable {
                        Personalization.weekGridMode = if (timeMode) {
                            Personalization.WEEK_GRID_PERIOD
                        } else {
                            Personalization.WEEK_GRID_TIME_24H
                        }
                        Personalization.save(context)
                    }
                    .semantics { contentDescription = if (timeMode) "切换到课次行视图" else "切换到 24 小时时间轴" }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    text = if (timeMode) "时间" else "课次",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (timeMode) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = { onBrowseWeekChange(((displayWeek ?: 1) - 1).coerceAtLeast(1)) },
                enabled = (displayWeek ?: 1) > 1,
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一周")
            }
            Text(
                text = weekHeadline,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (displayWeek == currentWeek) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(DesignTokens.cornerPanel))
                    .clickable { showJumpDialog = true }
                    .padding(vertical = 6.dp),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(
                onClick = { onBrowseWeekChange(((displayWeek ?: totalWeeks) + 1).coerceAtMost(totalWeeks)) },
                enabled = (displayWeek ?: totalWeeks) < totalWeeks,
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一周")
            }
        } else {
            // 顶上原周次文字的权重，保持「学期靠左、校区靠右」
            Spacer(modifier = Modifier.weight(1f))
        }
        campusSlot?.invoke()
    }

    if (showJumpDialog) {
        AlertDialog(
            onDismissRequest = { showJumpDialog = false },
            title = { Text("跳转到周次") },
            text = {
                LazyColumn(modifier = Modifier.height(320.dp)) {
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
                                .clickable {
                                    onBrowseWeekChange(week)
                                    showJumpDialog = false
                                }
                                .padding(vertical = 10.dp, horizontal = 8.dp),
                        )
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
