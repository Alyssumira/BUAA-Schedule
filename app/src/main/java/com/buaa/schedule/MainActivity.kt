package com.buaa.schedule

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.core.designsystem.GlassSurface
import com.buaa.schedule.core.designsystem.GlassVariant
import com.buaa.schedule.core.designsystem.LocalAnimatedVisibilityScope
import com.buaa.schedule.core.designsystem.LocalReduceMotion
import com.buaa.schedule.core.designsystem.LocalSharedTransitionScope
import com.buaa.schedule.core.designsystem.MotionTokens
import com.buaa.schedule.core.designsystem.Personalization
import com.buaa.schedule.core.designsystem.SceneBackground
import com.buaa.schedule.core.designsystem.BUAAScheduleTheme
import com.buaa.schedule.ui.ScheduleViewModel
import com.buaa.schedule.core.designsystem.liquid.LiquidBottomTab
import com.buaa.schedule.ui.editor.CourseEditorScreen
import com.buaa.schedule.ui.course.CourseManagementScreen
import com.buaa.schedule.ui.home.HomeScreen
import com.buaa.schedule.ui.importing.BuaaLoginScreen
import com.buaa.schedule.ui.importing.ImportHistoryScreen
import com.buaa.schedule.ui.importing.ImportScreen
import com.buaa.schedule.ui.settings.SettingsScreen
import com.buaa.schedule.core.openExternalUrl
import com.buaa.schedule.update.RELEASES_PAGE_URL
import com.buaa.schedule.update.UpdateCheck
import com.buaa.schedule.update.UpdateDialog
import com.buaa.schedule.update.UpdateUiState

class MainActivity : ComponentActivity() {

    /** Android 13+ 通知权限：启动即申请一次（用户拒绝后不再骚扰，可在设置里开） */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 拒绝后由 设置 → 提醒可靠性 引导开启 */ }

    /**
     * 桌面组件行点击传入的课程 id（null = 无请求）。
     * 之前这个 extra 只被写入、没有任何消费方，"点行跳到对应课程"实际上是空功能。
     */
    private val requestedCourseId = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        com.buaa.schedule.core.designsystem.GlassJankMonitor.attach(this)
        if (BuildConfig.DEBUG) {
            com.kyant.backdrop.BackdropDiagnostics.observer = { name, value ->
                android.util.Log.d("GlassDiag", "$name=$value")
            }
        }
        maybeRequestNotificationPermission()
        requestedCourseId.value = courseIdFrom(intent)
        setContent {
            BUAAScheduleApp(
                requestedCourseId = requestedCourseId.value,
                onCourseRequestConsumed = { requestedCourseId.value = null },
            )
        }
    }

    /** launchMode=singleTop：应用已在前台时点组件不会重建 Activity，请求从这里进来 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        courseIdFrom(intent)?.let { requestedCourseId.value = it }
    }

    override fun onStart() {
        super.onStart()
        // 恢复保留的教务 WebView：后台暂停期间不耗电，回到前台后可再次复用会话抓取
        com.buaa.schedule.data.import.BuaaWebSession.setAppForeground(true)
        // 登记当前窗口（弱引用）：会话 WebView 的隐藏宿主必须挂在活着的窗口上，
        // 转屏后迟到的 onPageFinished 才不会把宿主挂回已销毁的旧窗口
        com.buaa.schedule.data.import.BuaaWebSession.setCurrentActivity(this)
        // 进程重启后尝试用持久化 Cookie 重建 byxt 会话，避免每次冷启动都要重新登录
        com.buaa.schedule.data.import.BuaaWebSession.restore(this)
        // 已有会话时也要把隐藏宿主迁回当前 Activity：
        // Activity 重建不会自动迁移，旧窗口销毁后 WebView 会脱离窗口，
        // 导致「复用会话刷新课表」恒定时超时（R4 P1-1）。
        com.buaa.schedule.data.import.BuaaWebSession.reattachTo(this)
        // Sleepy 式状态驱动兜底：进前台即校准「课程进行中」实况。
        // 上课铃被 ROM 省电策略吞掉时，用户课堂中打开 App 也能当场补起实况，
        // 而不是等到下一节课（详见 reminder.LiveClassResyncer）。
        lifecycleScope.launch {
            com.buaa.schedule.reminder.LiveClassResyncer.resync(applicationContext)
        }
    }

    override fun onStop() {
        // 后台立刻暂停隐藏会话 WebView（onPause + pauseTimers），
        // 避免 byxt 页面残留的 JS/网络/渲染继续耗电
        com.buaa.schedule.data.import.BuaaWebSession.setAppForeground(false)
        super.onStop()
    }

    override fun onDestroy() {
        // Debug 帧率观测必须成对摘除，否则重建后的 Activity 拿不到采样
        com.buaa.schedule.core.designsystem.GlassJankMonitor.detach(this)
        super.onDestroy()
    }

    /** 从启动 Intent 里取组件行点击携带的课程 id；非法/缺失返回 null */
    private fun courseIdFrom(intent: Intent?): Long? =
        intent?.getLongExtra(EXTRA_COURSE_ID, -1L)?.takeIf { it >= 0L }

    /** Android 13+ 需要运行时申请通知权限；仅申请一次（后续由设置页引导） */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        /** 与 `widget.CourseListFactory.EXTRA_COURSE_ID` 同值：组件行点击携带的课程 id */
        private const val EXTRA_COURSE_ID = "com.buaa.schedule.widget.EXTRA_COURSE_ID"
    }
}

private data class NavItem(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
)

private val navItems = listOf(
    NavItem("home", R.string.tab_home, Icons.Default.CalendarMonth),
    NavItem("import", R.string.tab_import, Icons.Default.FileDownload),
    NavItem("settings", R.string.tab_settings, Icons.Default.Settings),
)

/** 深色模式偏好：跟随系统 / 强制浅色 / 强制深色 */
enum class DarkModePreference(val label: String) {
    FOLLOW_SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色");

    companion object {
        const val PREF_KEY = "dark_mode_preference"

        fun load(prefs: android.content.SharedPreferences): DarkModePreference {
            // 旧版布尔 dark_theme=true 迁移为强制深色
            val legacy = prefs.getBoolean("dark_theme", false)
            return when (prefs.getString(PREF_KEY, null)) {
                "LIGHT" -> LIGHT
                "DARK" -> DARK
                "FOLLOW_SYSTEM" -> FOLLOW_SYSTEM
                else -> if (legacy) DARK else FOLLOW_SYSTEM
            }
        }
    }
}

@Composable
private fun BUAAScheduleApp(
    requestedCourseId: Long? = null,
    onCourseRequestConsumed: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = context.getSharedPreferences("schedule_settings", Context.MODE_PRIVATE)
    var darkModePref by remember {
        mutableStateOf(DarkModePreference.load(prefs))
    }
    // 跟随系统时，系统深色切换会触发重组（isSystemInDarkTheme 读取配置状态）
    val darkTheme = when (darkModePref) {
        DarkModePreference.FOLLOW_SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
        DarkModePreference.LIGHT -> false
        DarkModePreference.DARK -> true
    }
    // Material You 动态取色（Android 12+），设置里可开关
    val dynamicColor = Personalization.useDynamicColor
    // 深色偏好要按**枚举**回传：以前这里收的是 Boolean，设置页选「跟随系统」
    // 会被折算成 LIGHT 写回 prefs —— 之后系统切换深浅色自然毫无反应，
    // 而且这个错误选择还落了盘，重启也不会自愈。
    val onDarkThemeChange: (DarkModePreference) -> Unit = { pref ->
        darkModePref = pref
        prefs.edit {
            putString(DarkModePreference.PREF_KEY, pref.name)
            putBoolean("dark_theme", pref == DarkModePreference.DARK)
        }
    }
    val navController = rememberNavController()
    val viewModel: ScheduleViewModel = viewModel(
        factory = ScheduleViewModel.Factory(context.applicationContext as android.app.Application),
    )
    val uiState by viewModel.uiState.collectAsState()
    // 桌面组件行点击 → 打开对应课程的编辑页。
    // 等课表加载完成再跳：编辑器在查不到该课程时会立刻回退，
    // 数据还没到就跳会表现为"点了没反应"。
    LaunchedEffect(requestedCourseId, uiState.loading) {
        val id = requestedCourseId ?: return@LaunchedEffect
        if (uiState.loading) return@LaunchedEffect
        navController.navigate("editor/$id")
        onCourseRequestConsumed()
    }
    val reminders by viewModel.reminders.collectAsState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val topLevelRoutes = remember { navItems.map { it.route }.toSet() }
    val showBottomBar = currentDestination?.hierarchy?.any { it.route in topLevelRoutes } == true

    // 更新检测：每天第一次打开自动查一次（节流在 UpdateCheck 内部），
    // 结果统一由下面的全局弹窗表达；设置页的"手动检查"复用同一个状态源。
    val updateState by UpdateCheck.state.collectAsState()
    val updateScope = rememberCoroutineScope()
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(Unit) { UpdateCheck.check(context, force = false) }

    // 深色主题用亮色状态栏图标，浅色主题用暗色图标，与 XML 主题的固定配置解耦
    val view = LocalView.current
    DisposableEffect(darkTheme) {
        val window = (view.context as? android.app.Activity)?.window
        window?.let {
            WindowCompat.getInsetsController(it, view)
                .isAppearanceLightStatusBars = !darkTheme
        }
        onDispose { }
    }

    BUAAScheduleTheme(
        darkTheme = darkTheme,
        dynamicColor = dynamicColor,
        seedColorArgb = Personalization.seedColorArgb,
    ) {
        // 全局场景背景：渐变 / 壁纸 + 明度遮罩，所有一级页面共享；
        // 背景录制进共享层，供液态玻璃做折射采样
        val sceneBackdrop = com.buaa.schedule.core.designsystem.rememberSceneBackdrop()
        // 课程卡共享模糊前缀：整屏 0.48x 降采样 + blur/vibrancy 一次烘焙，
        // 各课程卡走直采样快路径（仅保留自身 lens）
        val density = androidx.compose.ui.platform.LocalDensity.current
        val sharedCourseBackdrop = remember(sceneBackdrop, density) {
            com.kyant.backdrop.backdrops.SharedBlurBackdrop(
                source = sceneBackdrop,
                radiusPx = with(density) { 14.dp.toPx() },
                vibrant = true,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    sharedCourseBackdrop.preRenderModifier {
                        // 背景仅在壁纸 / 主题变化时重录
                        com.buaa.schedule.core.designsystem.Personalization.wallpaperUri to darkTheme
                    }
                )
        ) {
            SceneBackground(darkTheme = darkTheme, backdrop = sceneBackdrop)
            androidx.compose.runtime.CompositionLocalProvider(
                com.buaa.schedule.core.designsystem.LocalSceneBackdrop provides sceneBackdrop,
                com.buaa.schedule.core.designsystem.LocalSharedCourseBackdrop provides sharedCourseBackdrop,
                com.buaa.schedule.core.designsystem.LocalReduceMotion provides
                    com.buaa.schedule.core.designsystem.rememberReduceMotion(),
            ) {
                BoxWithConstraints {
                if (maxWidth >= 600.dp) {
                    // 宽屏：左侧玻璃导航栏 + 内容
                    Row(modifier = Modifier.fillMaxSize()) {
                        GlassNavRail(
                            currentRoute = currentDestination,
                            onNavigate = { route -> navController.navigateTopLevel(route) },
                        )
                        // 宽屏分支没有 Scaffold，必须自己补窗口内边距，
                        // 否则内容会被状态栏 / 系统导航栏压住
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .navigationBarsPadding(),
                        ) {
                            AppNavHost(
                                navController = navController,
                                viewModel = viewModel,
                                uiState = uiState,
                                reminders = reminders,
                                onDarkThemeChange = onDarkThemeChange,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                            )
                        }
                    }
                } else {
                    // 手机：悬浮玻璃底部导航。
                    // 不能用 Scaffold：Scaffold 会把内容"避让"到底栏上方（内容永远到不了栏体背后），
                    // 悬浮玻璃要的恰恰相反——内容延伸到底栏下方滚动，玻璃浮在内容之上。
                    // 因此这里用 overlay Box；底部让位由各一级页面的滚动容器加 clearance。
                    Box(modifier = Modifier.fillMaxSize()) {
                        AppNavHost(
                            navController = navController,
                            viewModel = viewModel,
                            uiState = uiState,
                            reminders = reminders,
                            onDarkThemeChange = onDarkThemeChange,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                            bottomBarVisible = showBottomBar,
                        )
                        if (showBottomBar) {
                            FloatingGlassBottomBar(
                                currentRoute = currentDestination,
                                onNavigate = { route -> navController.navigateTopLevel(route) },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .navigationBarsPadding()
                                    .padding(horizontal = DesignTokens.spaceL, vertical = DesignTokens.spaceS),
                            )
                        }
                    }
                }
                }
            }
        }
        // 更新弹窗挂在主题层而不是某个页面：自动检测可能在任意页面弹出，
        // 下载进度也要在用户离开设置页后继续可见。
        if (updateState.visible) {
            val available = (updateState as? UpdateUiState.Available)?.info
            UpdateDialog(
                state = updateState,
                currentVersion = BuildConfig.VERSION_NAME,
                onDismiss = { UpdateCheck.dismiss() },
                onDownload = {
                    if (available != null) {
                        downloadJob = updateScope.launch { UpdateCheck.download(context, available) }
                    }
                },
                onCancelDownload = {
                    downloadJob?.cancel()
                    downloadJob = null
                    UpdateCheck.dismiss()
                },
                onIgnore = { if (available != null) UpdateCheck.ignore(context, available) },
                onOpenReleasePage = {
                    openExternalUrl(context, available?.pageUrl ?: RELEASES_PAGE_URL)
                    UpdateCheck.dismiss()
                },
            )
        }
    }
}

@Composable
private fun AppNavHost(
    navController: androidx.navigation.NavHostController,
    viewModel: ScheduleViewModel,
    uiState: com.buaa.schedule.ui.ScheduleUiState,
    reminders: List<com.buaa.schedule.domain.model.ReminderSetting>,
    onDarkThemeChange: (DarkModePreference) -> Unit,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    /** 手机端当前路由是否显示悬浮底栏（宽屏导航栏分支恒为 false） */
    bottomBarVisible: Boolean = false,
) {
    val reduceMotion = LocalReduceMotion.current
    val motion = MotionTokens.DURATION_MEDIUM
    @OptIn(ExperimentalSharedTransitionApi::class)
    SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
        val sharedTransitionScope = this
        CompositionLocalProvider(
            LocalSharedTransitionScope provides sharedTransitionScope,
            LocalAnimatedVisibilityScope provides null,
        ) {
        NavHost(
        navController = navController,
        startDestination = "home",
        // 一级页面没有 TopAppBar，状态栏内边距要自己补（Scaffold 的 padding 不含它）
        modifier = Modifier
            .padding(contentPadding)
            .statusBarsPadding(),
        enterTransition = {
            if (reduceMotion) EnterTransition.None else {
                slideInHorizontally(
                    animationSpec = tween(motion, easing = MotionTokens.EasingStandard),
                    initialOffsetX = { it / 6 },
                ) + fadeIn(tween(motion))
            }
        },
        exitTransition = {
            if (reduceMotion) ExitTransition.None else {
                slideOutHorizontally(
                    animationSpec = tween(motion, easing = MotionTokens.EasingStandard),
                    targetOffsetX = { -it / 6 },
                ) + fadeOut(tween(motion))
            }
        },
        popEnterTransition = {
            if (reduceMotion) EnterTransition.None else {
                slideInHorizontally(
                    animationSpec = tween(motion, easing = MotionTokens.EasingStandard),
                    initialOffsetX = { -it / 6 },
                ) + fadeIn(tween(motion))
            }
        },
        popExitTransition = {
            if (reduceMotion) ExitTransition.None else {
                slideOutHorizontally(
                    animationSpec = tween(motion, easing = MotionTokens.EasingStandard),
                    targetOffsetX = { it / 6 },
                ) + fadeOut(tween(motion))
            }
        },
    ) {
        composable("home") {
            CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
                HomeScreen(
                    onAddCourse = { navController.navigate("editor/-1") },
                    onImportBuaa = { navController.navigate("buaa_login") },
                    onCourseManagement = { navController.navigate("course_management") },
                    onCourseClick = { course -> navController.navigate("editor/${course.id}") },
                    bottomBarVisible = bottomBarVisible,
                    viewModel = viewModel,
                )
            }
        }
        composable("course_management") {
            CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
                CourseManagementScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onEditCourse = { course -> navController.navigate("editor/${course.id}") },
                )
            }
        }
        composable(
            route = "editor/{courseId}",
            arguments = listOf(
                navArgument("courseId") { type = NavType.LongType }
            ),
        ) { backStackEntry ->
            val courseId = backStackEntry.arguments?.getLong("courseId") ?: -1L
            val course = uiState.courses.firstOrNull { it.id == courseId }
            // 目标行还没到（首帧课程列表为空 / 刚切学期）：编辑器会以 initialCourse=null
            // 组合并恢复草稿，保存时用 id=0L 插出一条重复课程（R5 F-41）—— 这种帧直接不渲染
            val courseMissing = courseId >= 0 && course == null
            androidx.compose.runtime.LaunchedEffect(courseId, uiState.loading) {
                if (!uiState.loading && courseMissing) {
                    navController.popBackStack()
                }
            }
            CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
                if (!uiState.loading && !courseMissing) {
                    CourseEditorScreen(
                        initialCourse = course,
                        initialReminder = course?.let { c -> reminders.firstOrNull { it.courseId == c.id } },
                        onSave = { edited, options ->
                            if (edited.id == 0L) {
                                viewModel.saveCourse(edited)
                            } else {
                                viewModel.updateCourse(edited, options)
                            }
                        },
                        onDelete = { viewModel.deleteCourse(it) },
                        onBack = { navController.popBackStack() },
                        onSaveReminder = { id, enabled, minutes ->
                            viewModel.saveReminder(
                                com.buaa.schedule.domain.model.ReminderSetting(id, enabled, minutes)
                            )
                        },
                    )
                }
            }
        }
        composable("import") {
            CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
                ImportScreen(
                    onBack = { navController.popBackStack() },
                    onStartBuaaLogin = { navController.navigate("buaa_login") },
                    onOpenHistory = { navController.navigate("import_history") },
                    bottomBarVisible = bottomBarVisible,
                    viewModel = viewModel,
                )
            }
        }
        composable("import_history") {
            CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
                ImportHistoryScreen(onBack = { navController.popBackStack() })
            }
        }
        composable("buaa_login") {
            CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
                BuaaLoginScreen(
                    onBack = { navController.popBackStack() },
                    // 抓取完成 → 落到导入页看预览卡片并确认，确认前不落库。
                    // 注意：登录成功后**不能**在这里 popBackStack：
                    // 页面内抓取还在这个页面的协程里跑，提前返回会把预览链路一起取消。
                    onImportPrepared = { navController.navigateTopLevel("import") },
                    viewModel = viewModel,
                )
            }
        }
        composable("settings") {
            CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel,
                    onDarkThemeChange = onDarkThemeChange,
                    // 分类入口 → 打开独立的设置子界面（不再是同页折叠）
                    onOpenSection = { section ->
                        navController.navigate("settings/${section.id}")
                    },
                    bottomBarVisible = bottomBarVisible,
                )
            }
        }
        composable("settings/{section}") { entry ->
            CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel,
                    onDarkThemeChange = onDarkThemeChange,
                    section = com.buaa.schedule.ui.settings.SettingsSection
                        .fromId(entry.arguments?.getString("section")),
                )
            }
        }
        }
        }
    }
}

/** 顶部导航复用同一套跳转语义 */
private fun androidx.navigation.NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** 悬浮液态玻璃底部导航（LiquidBottomTabs + 折射指示器） */
@Composable
private fun FloatingGlassBottomBar(
    currentRoute: androidx.navigation.NavDestination?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val backdrop = com.buaa.schedule.core.designsystem.LocalSceneBackdrop.current
    if (backdrop == null) {
        // 没有场景背景时：保留旧式玻璃卡底栏
        GlassSurface(
            variant = GlassVariant.CHROME,
            shape = RoundedCornerShape(DesignTokens.cornerPage),
            contentPadding = DesignTokens.spaceS,
            modifier = modifier,
        ) {
            LegacyBottomBarContent(currentRoute, onNavigate)
        }
        return
    }
    com.buaa.schedule.core.designsystem.liquid.LiquidBottomTabs(
        selectedTabIndex = {
            navItems.indexOfFirst { item -> currentRoute?.hierarchy?.any { it.route == item.route } == true }
                .let { if (it < 0) 0 else it }
        },
        onTabSelected = { index -> onNavigate(navItems[index].route) },
        backdrop = backdrop,
        tabsCount = navItems.size,
        modifier = modifier,
        containerHeight = 64.dp,
        indicatorHeight = 56.dp,
                        isLightTheme = !darkTheme,
                        // 底栏表面色：过高会像不透明色条，0.20 让背景能透出来
                        containerAlpha = 0.20f,
                        containerColor = if (darkTheme) {
                            com.buaa.schedule.core.designsystem.DarkGlassTint
                        } else {
                            com.buaa.schedule.core.designsystem.LightGlassTint
                        },
        tabContent = { index ->
            val item = navItems[index]
            // 主行 tab 一律中性色；选中态内容由指示器以主题色承载（玻璃上叠内容）
            val accentTint = com.buaa.schedule.core.designsystem.liquid.LocalLiquidBottomTabAccentTint.current
            val selected = currentRoute?.hierarchy?.any { it.route == item.route } == true
            val itemColor = if (accentTint) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Icon(
                imageVector = item.icon,
                contentDescription = stringResource(item.labelRes),
                tint = itemColor,
            )
            Text(
                text = stringResource(item.labelRes),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected || accentTint) FontWeight.SemiBold else FontWeight.Normal,
                color = itemColor,
            )
        },
    )
}

@Composable
private fun LegacyBottomBarContent(
    currentRoute: androidx.navigation.NavDestination?,
    onNavigate: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        navItems.forEach { item ->
            val selected = currentRoute?.hierarchy?.any { it.route == item.route } == true
            Column(
                modifier = Modifier
                    .clickable { onNavigate(item.route) }
                    .padding(horizontal = DesignTokens.spaceL, vertical = DesignTokens.spaceS),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = stringResource(item.labelRes),
                    tint = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = stringResource(item.labelRes),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** 宽屏玻璃导航栏 */
@Composable
private fun GlassNavRail(
    currentRoute: androidx.navigation.NavDestination?,
    onNavigate: (String) -> Unit,
) {
    GlassSurface(
        variant = GlassVariant.CHROME,
        contentPadding = DesignTokens.spaceS,
        modifier = Modifier
            .fillMaxHeight()
            .width(84.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            navItems.forEach { item ->
                val selected = currentRoute?.hierarchy?.any { it.route == item.route } == true
                Column(
                    modifier = Modifier
                        .clickable { onNavigate(item.route) }
                        .padding(vertical = DesignTokens.spaceL),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = stringResource(item.labelRes),
                        tint = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        text = stringResource(item.labelRes),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

