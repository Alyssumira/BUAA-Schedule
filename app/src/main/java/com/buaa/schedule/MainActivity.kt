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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import androidx.navigation.NavBackStackEntry
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
import com.buaa.schedule.core.designsystem.motionSpecFor
import com.buaa.schedule.core.designsystem.rememberReduceMotion
import com.buaa.schedule.core.designsystem.BUAAScheduleTheme
import com.buaa.schedule.ui.ScheduleViewModel
import com.buaa.schedule.core.designsystem.liquid.LiquidBottomTab
import com.buaa.schedule.ui.editor.CourseEditorScreen
import com.buaa.schedule.ui.course.CourseManagementScreen
import com.buaa.schedule.core.FirstRun
import com.buaa.schedule.ui.home.HomeScreen
import com.buaa.schedule.ui.onboarding.OnboardingScreen
import com.buaa.schedule.ui.importing.BuaaLoginScreen
import com.buaa.schedule.ui.importing.ImportHistoryScreen
import com.buaa.schedule.ui.importing.ImportScreen
import com.buaa.schedule.ui.settings.SettingsScreen
import com.buaa.schedule.ui.signin.ScanChainWarmUp
import com.buaa.schedule.ui.signin.SpocLoginScreen
import com.buaa.schedule.ui.signin.SpocScanScreen
import com.buaa.schedule.ui.stats.StatsScreen
import com.buaa.schedule.widget.WidgetNavigation
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

    /**
     * 通知按钮要求直接打开的页内路由（null = 无请求）。一次性：跳完立刻清空，
     * 否则转屏或任何重组都会再跳一次，把用户从自己正在看的页面上踢走。
     */
    private val requestedRoute = mutableStateOf<String?>(null)

    /**
     * 4×2 网格组件的格子点击传入的星期序号（ISO：1=周一 … 7=周日，null = 无请求）。
     * 一格背后可能有多门课，所以点格子的语义是「跳到那一天」而不是「打开某一节课」；
     * 课程 id 从一开始就不进这个 deeplink。
     */
    private val requestedDayOfWeek = mutableStateOf<Int?>(null)

    /**
     * 引导最后一步选择的落点（"import" / "editor/-1" / null = 默认首页）；进程重建即失效。
     * "home" 与 null 等价。
     */
    private val pendingStartRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        com.buaa.schedule.core.designsystem.GlassJankMonitor.attach(this)
        if (BuildConfig.DEBUG) {
            com.kyant.backdrop.BackdropDiagnostics.observer = { name, value ->
                android.util.Log.d("GlassDiag", "$name=$value")
            }
        }
        requestedCourseId.value = courseIdFrom(intent)
        requestedRoute.value = routeFrom(intent)
        requestedDayOfWeek.value = dayOfWeekFrom(intent)
        // 老用户（引导已完成）没有自检步可走，通知权限仍在启动时补申请一次
        if (FirstRun.onboardingCompleted(this)) maybeRequestNotificationPermission()
        setContent {
            // 必须是 state：引导完成回调把它翻成 true 之后，组合要切到主界面
            var onboardingDone by remember {
                mutableStateOf(FirstRun.onboardingCompleted(this))
            }
            // 开关供在 if 之外，两棵子树都要拿到：引导页不是 NavHost 的一个 destination，
            // 而是整棵子树在这里被换进换出，此前装在 BUAAScheduleApp 里时引导页那一支
            // 永远读到默认值 false，那一支里读这颗开关的组件便都不受系统动画开关管束
            // （④M-01 的口径：全站每一条动画都拿得到，才是规则而不是补丁）。
            // 放在 setContent 这一层，LocalLifecycleOwner 仍是 Activity 本身，
            // "改完设置回来重读"的 ON_RESUME 时机不变。
            CompositionLocalProvider(
                LocalReduceMotion provides rememberReduceMotion(),
            ) {
                if (onboardingDone) {
                    BUAAScheduleApp(
                        requestedCourseId = requestedCourseId.value,
                        onCourseRequestConsumed = { requestedCourseId.value = null },
                        requestedRoute = requestedRoute.value,
                        onRouteRequestConsumed = { requestedRoute.value = null },
                        requestedDayOfWeek = requestedDayOfWeek.value,
                        onDayRequestConsumed = { requestedDayOfWeek.value = null },
                        startRoute = pendingStartRoute.value,
                    )
                } else {
                    BUAAScheduleTheme(
                        darkTheme = androidx.compose.foundation.isSystemInDarkTheme(),
                        dynamicColor = Personalization.useDynamicColor,
                        seedColorArgb = Personalization.seedColorArgb,
                        // 主题在 LocalReduceMotion 的上游，取不到就得自己传
                        reduceMotion = com.buaa.schedule.core.designsystem.rememberReduceMotion(),
                    ) {
                        OnboardingScreen(
                            onFinished = { startRoute ->
                                onboardingDone = true
                                pendingStartRoute.value = startRoute
                                // 从欢迎页/隐私页就点跳过的人不会经过自检步，权限在这里补
                                maybeRequestNotificationPermission()
                            },
                        )
                    }
                }
            }
        }

        // 扫码链预热（T18）：ML Kit 解码器的 libbarhopper_v3.so 的 dlopen 写在
        // BarhopperV3 的实例构造函数里，起手那颗 MlKitInitProvider 一点也帮不上，
        // 那一下原本砸在用户点开扫码页的瞬间。这里只是**排**两次帧回调（微秒级），
        // 真正的活在首帧画完之后由后台协程去付，判据见 ScanChainWarmUp 的类注释。
        //
        // 故意不按 onboardingCompleted 分叉：门闩是进程内一次性的，而走完引导只是把
        // 那棵子树换掉、并不会重建 Activity —— 一旦这一趟没排，整个会话就再也不会预热。
        // 作用域取 applicationScope 而不是 lifecycleScope：同理，转屏把协程掐死时
        // 门闩已经翻过去了，这次预热就等于被悄悄丢掉。
        ScanChainWarmUp.scheduleAfterFirstFrame(
            scope = (application as BUAAApplication).applicationScope,
            context = applicationContext,
        )
    }

    /** launchMode=singleTop：应用已在前台时点组件不会重建 Activity，请求从这里进来 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        courseIdFrom(intent)?.let { requestedCourseId.value = it }
        routeFrom(intent)?.let { requestedRoute.value = it }
        dayOfWeekFrom(intent)?.let { requestedDayOfWeek.value = it }
    }

    override fun onStart() {
        super.onStart()
        // 恢复保留的教务 WebView：后台暂停期间不耗电，回到前台后可再次复用会话抓取
        com.buaa.schedule.data.import.BuaaWebSession.setAppForeground(true)
        // 登记当前窗口（弱引用）：会话 WebView 的隐藏宿主必须挂在活着的窗口上，
        // 转屏后迟到的 onPageFinished 才不会把宿主挂回已销毁的旧窗口
        com.buaa.schedule.data.import.BuaaWebSession.setCurrentActivity(this)
        // 进程重启后尝试用持久化 Cookie 重建 byxt 会话，避免每次冷启动都要重新登录；
        // 已有会话时也要把隐藏宿主迁回当前 Activity（Activity 重建不会自动迁移，
        // 旧窗口销毁后 WebView 会脱离窗口，导致「复用会话刷新课表」恒定时超时，R4 P1-1）。
        //
        // 这两件事排在首帧之后：restore 会在主线程上新建一个 WebView（Chromium 视图树，
        // 冷启动里最贵的一步）、解密读一次落盘 Cookie、再发一次网络加载，而它服务的
        // 只是"用户不点导入时也能静默刷新"。挂在 decorView 上，等窗口真正附加、
        // 首帧已排期之后再动手，用户看到课表的时间不受影响。
        window.decorView.post {
            // 排到首帧之后就要认这个空窗：期间用户可能已经又退到后台
            // （点组件进、马上划走）。那时定时器已在后台档，再建 WebView 并 loadUrl
            // 等于偷偷在后台加载教务页面，与「退到后台不耗电」的约定相反。
            if (isFinishing || isDestroyed ||
                !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
            ) return@post
            com.buaa.schedule.data.import.BuaaWebSession.restore(this)
            com.buaa.schedule.data.import.BuaaWebSession.reattachTo(this)
        }
        // Sleepy 式状态驱动兜底：进前台即校准「课程进行中」实况。
        // 上课铃被 ROM 省电策略吞掉时，用户课堂中打开 App 也能当场补起实况，
        // 而不是等到下一节课（详见 reminder.LiveClassResyncer）。
        // ⚠️ 必须离开 Main：resync 里的 planNextClassWindow 是普通阻塞函数，
        // 对每门课展开「剩余周次 × 节次段」（课程上限 2000），
        // lifecycleScope 默认 Main.immediate 等于每次切回前台卡一次首帧。
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
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

    /**
     * 从启动 Intent 里取 4×2 网格格子的星期序号（ISO 1..7）。
     * 模板缺省值是 0，非组件进来的普通启动则完全没有这个键 —— 两者都按"无请求"处理。
     */
    private fun dayOfWeekFrom(intent: Intent?): Int? =
        intent?.getIntExtra(WidgetNavigation.EXTRA_DAY_OF_WEEK, 0)?.takeIf { it in 1..7 }

    /**
     * 从启动 Intent 里取「要打开哪个页内路由」。
     *
     * MainActivity 是 launcher 页，外部应用可以直接带 extra 进来，所以这里过白名单：
     * 只认通知链路真正会发的那几条。
     */
    private fun routeFrom(intent: Intent?): String? =
        intent?.getStringExtra(EXTRA_ROUTE)?.takeIf { it in ROUTABLE_FROM_INTENT }

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
        /**
         * 「打开这一节课」的 deeplink 键：MainActivity 只认这一个，
         * 组件行点击、课堂实况通知与超级岛点按都复用它。
         */
        internal const val EXTRA_COURSE_ID = "com.buaa.schedule.widget.EXTRA_COURSE_ID"

        /**
         * 「打开某个页内路由」的 deeplink 键：课前提醒的「扫码签到」按钮用它直达扫码页。
         */
        internal const val EXTRA_ROUTE = "com.buaa.schedule.EXTRA_ROUTE"

        /** [EXTRA_ROUTE] 承认的路由：见 [routeFrom] */
        private val ROUTABLE_FROM_INTENT = setOf("spoc_scan", "spoc_login")
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

/**
 * 三个导航条（悬浮玻璃底栏 / 旧式底栏 / 宽屏导航栏）共用的图标 + 标签。
 *
 * 同一条目此前有三份拷贝，而两条修复只落在了主分支上：
 * ①选中态主题色要在**看得见的那一排**上就成立（旧拷贝只差两个字重，强光下等于没有）；
 * ②图标不能再带 contentDescription —— 下面那行 Text 已经播报过标签，
 *   两处都给就会念成「首页 首页」（①C-01）。
 *
 * 定义在 `ColumnScope` 上：三处调用点本身就在 Column 内容位，不必为共用再包一层节点。
 *
 * @param accentTint 玻璃底栏的折射指示器压在这一项上时也要主题色：
 *   它比用户的手指更快到位，画成灰色会读成"没选中"
 */
@Composable
private fun ColumnScope.NavItemContent(
    item: NavItem,
    selected: Boolean,
    accentTint: Boolean = false,
) {
    val emphasized = selected || accentTint
    val itemColor = if (emphasized) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Icon(
        imageVector = item.icon,
        contentDescription = null,
        tint = itemColor,
    )
    Text(
        text = stringResource(item.labelRes),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
        color = itemColor,
    )
}

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
    /** 通知按钮要求直接打开的页内路由（null = 无请求） */
    requestedRoute: String? = null,
    onRouteRequestConsumed: () -> Unit = {},
    /** 4×2 网格格子点击要打开的那一天（ISO 1..7，null = 无请求） */
    requestedDayOfWeek: Int? = null,
    onDayRequestConsumed: () -> Unit = {},
    /** 引导结束时选择的落点；null = 首页 */
    startRoute: String? = null,
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
    // remember 住身份：这个 lambda 一路传到 NavHost 的各个目的地，根组合每次重组
    // 都换新实例的话，整棵导航树跟着重组（下载进度原先就是这里的噪声源，P2）。
    val onDarkThemeChange: (DarkModePreference) -> Unit = remember(prefs) {
        { pref ->
            darkModePref = pref
            prefs.edit {
                putString(DarkModePreference.PREF_KEY, pref.name)
                putBoolean("dark_theme", pref == DarkModePreference.DARK)
            }
        }
    }
    val navController = rememberNavController()
    // 引导最后一步「手动添加课程」交回来的是 editor/-1 这种**一次性**路由，不能当
    // startDestination：NavHost 找起点是按 route 模板（editor/{courseId}）匹配的，
    // 传具体值根本找不到起点；而且编辑器成了根栈就没有可返回的上一级，
    // 返回键等于直接退出应用。所以起点仍是首页，编辑器在后面用一次 navigate 叠上去，
    // 消费完立刻清空 —— 转屏或重组都不会再跳第二次（P2）。
    val navStartRoute = startRoute?.takeUnless { it.startsWith("editor/") }
    var pendingEditorRoute by remember(startRoute) {
        mutableStateOf(startRoute?.takeIf { it.startsWith("editor/") })
    }
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
        // launchSingleTop：Activity 是 singleTop，连着点两次组件的同一行会送来两次
        // 请求，不加这个参数编辑器就会叠在编辑器自己身上，返回键要按两下才出得去。
        navController.navigate("editor/$id") { launchSingleTop = true }
        onCourseRequestConsumed()
    }
    // 通知按钮 → 直接打开某个页面（课前提醒上的「扫码签到」）。
    // 不像课程 id 那样等 uiState.loading：扫码页不读课表，等一下只会让跳转慢半拍。
    LaunchedEffect(requestedRoute) {
        val route = requestedRoute ?: return@LaunchedEffect
        navController.navigate(route) { launchSingleTop = true }
        onRouteRequestConsumed()
    }
    // 桌面组件 4×2 格子 → 那一天的日视图（T-26）。
    // 这里只负责把人带回首页：具体落哪个日期要等课表数据到位（周次决定日期），
    // 那是 HomeScreen 的事，它只在 home 这一条 destination 上存在，所以先导航过去。
    LaunchedEffect(requestedDayOfWeek) {
        if (requestedDayOfWeek == null) return@LaunchedEffect
        navController.navigateTopLevel("home")
    }
    val reminders by viewModel.reminders.collectAsState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val topLevelRoutes = remember { navItems.map { it.route }.toSet() }
    val showBottomBar = currentDestination?.hierarchy?.any { it.route in topLevelRoutes } == true

    // 更新检测的状态**不在这里 collect**：下载时每来一个数据块就有一个新百分比，
    // 挂在根组合上等于每一块重画一次主题 + 整棵导航树（P2）。
    // 见下方 UpdateDialogHost：它自己 collect，重组只限这一小块。

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
        // 玻璃档位为 OFF 时这一整层是纯白付：SharedBlurBackdrop 一挂载就创建
        // GraphicsLayer，并在每一帧录制里保留那张降采样 + blur 的离屏纹理
        // （1080×2400 的 0.48x ≈ 2.3MB RGBA，另加一张整屏记录层）。
        // 而 OFF 恰恰发生在"内存 ≤128MB / 4 核以下"的静态钳制与运行时掉帧降档之后 ——
        // 最吃不起的机型付这笔。玻璃消费端本就按该层是否为 null 走降级绘制。
        val glassTierOn = com.buaa.schedule.core.designsystem.GlassGovernance
            .effectiveTier(com.buaa.schedule.core.designsystem.Personalization.glassTier) >=
            com.buaa.schedule.core.designsystem.DesignTokens.GLASS_TIER_STANDARD
        val sharedCourseBackdrop: com.kyant.backdrop.backdrops.SharedBlurBackdrop? =
            remember(sceneBackdrop, density, glassTierOn) {
            if (!glassTierOn) null
            else com.kyant.backdrop.backdrops.SharedBlurBackdrop(
                source = sceneBackdrop,
                radiusPx = with(density) { 14.dp.toPx() },
                vibrant = true,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    sharedCourseBackdrop?.preRenderModifier {
                        // 背景仅在壁纸 / 主题变化时重录
                        com.buaa.schedule.core.designsystem.Personalization.wallpaperUri to darkTheme
                    } ?: Modifier
                )
        ) {
            SceneBackground(darkTheme = darkTheme, backdrop = sceneBackdrop)
            // LocalReduceMotion 不在这里供：它装在 setContent 的 if 之外（见 onCreate），
            // 因为引导页那一支不是 NavHost 的 destination，供在这一层它就拿不到。
            androidx.compose.runtime.CompositionLocalProvider(
                com.buaa.schedule.core.designsystem.LocalSceneBackdrop provides sceneBackdrop,
                com.buaa.schedule.core.designsystem.LocalSharedCourseBackdrop provides sharedCourseBackdrop,
            ) {
                BoxWithConstraints {
                if (maxWidth >= DesignTokens.breakpointWide) {
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
                                startRoute = navStartRoute,
                                requestedDayOfWeek = requestedDayOfWeek,
                                onDayRequestConsumed = onDayRequestConsumed,
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
                            startRoute = navStartRoute,
                            requestedDayOfWeek = requestedDayOfWeek,
                            onDayRequestConsumed = onDayRequestConsumed,
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
                                    // 左右内缩以左侧时间列为准（真机反馈：底栏遮住了「08:00」那一列）
                                    .padding(
                                        horizontal = DesignTokens.bottomBarHorizontalInset,
                                        vertical = DesignTokens.spaceS,
                                    ),
                            )
                        }
                    }
                }
                }
            }
        }
        // 引导落点里的一次性 editor 路由。刻意声明在 NavHost **之后**：side effect
        // 按组合顺序执行，排在后面才能保证 NavHost 已经装好 navigator，
        // 否则这里 navigate 会撞上"NavController 尚未与 NavigationHost 关联"。
        LaunchedEffect(pendingEditorRoute) {
            val route = pendingEditorRoute ?: return@LaunchedEffect
            // 先清后跳：这条路由只生效这一次
            pendingEditorRoute = null
            navController.navigate(route) { launchSingleTop = true }
        }
        // 更新弹窗挂在主题层而不是某个页面：自动检测可能在任意页面弹出，
        // 下载进度也要在用户离开设置页后继续可见。
        // （状态 collect 也在弹窗自己的宿主里，理由见 UpdateDialogHost）
        UpdateDialogHost(context)
    }
}

/**
 * 更新检测的状态宿主：collect 与弹窗单独成一小撮组合。
 *
 * 下载期间 UpdateCheck 每收到一块就发一个新的百分比，此前这个 collect 挂在
 * BUAAScheduleApp 的根上 —— 等于每一块都要重画一遍主题、场景背景、共享模糊层
 * 和整棵导航树（P2）。挪进来之后进度变化只重组这一小块，弹窗关掉时它整体退出组合。
 * 首次自动检查也一起搬过来：宿主与主题同生命周期，LaunchedEffect(Unit) 仍然只跑一次
 * （真正的每日节流在 UpdateCheck 内部），设置页的"手动检查"复用同一个状态源。
 */
@Composable
private fun UpdateDialogHost(context: Context) {
    val updateState by UpdateCheck.state.collectAsState()
    LaunchedEffect(Unit) { UpdateCheck.check(context, force = false) }
    // 不再写 `if (updateState.visible)`：那个 if 在状态回到 Idle 的同一刻就把子树摘走，
    // 弹窗的收场动画一帧都播不出来。可见性闸门在 UpdateDialog 内部（它用 ModalTransition
    // 把「该不该画」和「还挂不挂」分开），这里保持无条件组合。
    val info = when (val s = updateState) {
        is UpdateUiState.Available -> s.info
        is UpdateUiState.NeedsInstallPermission -> s.info
        is UpdateUiState.InstallBlocked -> s.info
        else -> null
    }
    UpdateDialog(
        state = updateState,
        currentVersion = BuildConfig.VERSION_NAME,
        onDismiss = { UpdateCheck.dismiss() },
        onDownload = { (updateState as? UpdateUiState.Available)?.info?.let { UpdateCheck.startDownload(context, it) } },
        onCancelDownload = { UpdateCheck.cancelDownload() },
        onIgnore = { info?.let { UpdateCheck.ignore(context, it) } },
        onOpenReleasePage = {
            openExternalUrl(context, info?.pageUrl ?: RELEASES_PAGE_URL)
            UpdateCheck.dismiss()
        },
        onOpenInstallPermissionSettings = { UpdateCheck.openInstallPermissionSettings(context) },
        onRetryInstall = { UpdateCheck.retryInstall(context) },
    )
}

@Composable
private fun AppNavHost(
    navController: androidx.navigation.NavHostController,
    viewModel: ScheduleViewModel,
    uiState: com.buaa.schedule.ui.ScheduleUiState,
    reminders: List<com.buaa.schedule.domain.model.ReminderSetting>,
    onDarkThemeChange: (DarkModePreference) -> Unit,
    /** 引导结束时选定的落点页；null = home */
    startRoute: String? = null,
    /** 4×2 网格格子点击要打开的那一天（ISO 1..7，null = 无请求） */
    requestedDayOfWeek: Int? = null,
    onDayRequestConsumed: () -> Unit = {},
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    /** 手机端当前路由是否显示悬浮底栏（宽屏导航栏分支恒为 false） */
    bottomBarVisible: Boolean = false,
) {
    val reduceMotion = LocalReduceMotion.current
    // 刚从编辑器保存的课程：首页给那张卡做一次"定位脉冲"（④机会#4）。
    // 放在这一层是因为编辑器与首页分属两个 destination，返回时唯一的公共祖先就是这里。
    // 拆成「待送达 / 已送达」两格：编辑器也可能从课程管理页打开，保存后落点是管理页，
    // 直接把 id 交给首页会攒到用户下次进首页时才闪一下——那一次脉冲毫无来由。
    var pendingPulseCourseId by remember { mutableLongStateOf(-1L) }
    var pulseCourseId by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.collect { entry ->
            when {
                entry.destination.route == "home" -> {
                    if (pendingPulseCourseId >= 0L) {
                        pulseCourseId = pendingPulseCourseId
                    }
                    pendingPulseCourseId = -1L
                }
                // 编辑器自己不参与判定：保存与返回之间它仍是当前页
                entry.destination.route?.startsWith("editor/") != true -> {
                    pendingPulseCourseId = -1L
                }
            }
        }
    }
    // 首页加号与设置页共用一条入口：没有 token 时扫码必然失败，先进登录页，
    // 登录页成功后自己 popUpTo 换成扫码页。两处各写一份判断迟早会走岔。
    val openSpocSignIn: () -> Unit = {
        navController.navigate(
            if (com.buaa.schedule.data.import.SpocSession.hasSession()) "spoc_scan" else "spoc_login",
        )
    }
    @OptIn(ExperimentalSharedTransitionApi::class)
    SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
        val sharedTransitionScope = this
        CompositionLocalProvider(
            LocalSharedTransitionScope provides sharedTransitionScope,
            LocalAnimatedVisibilityScope provides null,
        ) {
        NavHost(
        navController = navController,
        startDestination = startRoute ?: "home",
        // 一级页面没有 TopAppBar，状态栏内边距要自己补（Scaffold 的 padding 不含它）
        modifier = Modifier
            .padding(contentPadding)
            .statusBarsPadding(),
        enterTransition = {
            navEnter(navMotionFor(initialState, targetState), reduceMotion, enterFromRight = true)
        },
        exitTransition = {
            navExit(navMotionFor(initialState, targetState), reduceMotion, exitToRight = false)
        },
        popEnterTransition = {
            navEnter(navMotionFor(initialState, targetState), reduceMotion, enterFromRight = false)
        },
        popExitTransition = {
            navExit(navMotionFor(initialState, targetState), reduceMotion, exitToRight = true)
        },
    ) {
        composable("home") {
            CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
                HomeScreen(
                    onAddCourse = { navController.navigate("editor/-1") },
                    onImportBuaa = { navController.navigate("buaa_login") },
                    onCourseManagement = { navController.navigate("course_management") },
                    onSpocSignIn = openSpocSignIn,
                    onCourseClick = { course -> navController.navigate("editor/${course.id}") },
                    bottomBarVisible = bottomBarVisible,
                    highlightCourseId = pulseCourseId,
                    onHighlightConsumed = { pulseCourseId = -1L },
                    widgetDayOfWeek = requestedDayOfWeek,
                    onWidgetDayConsumed = onDayRequestConsumed,
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
                            // 写库返回最终落库行 id，null = 失败（编辑器据此保留草稿）
                            val savedId = if (edited.id == 0L) {
                                viewModel.saveCourse(edited)
                            } else {
                                viewModel.updateCourse(edited, options)
                            }
                            // 新增的课程也有真实 id 了，能定位到刚建的那张卡；失败则不脉冲
                            if (savedId != null) pendingPulseCourseId = savedId
                            savedId
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
                    // 引导以「导入课表」收尾时会把 startRoute 定成 import，那一刻 import
                    // 就是起始目的地：栈里没有更低的条目，popBackStack 返回 false，
                    // 左上角返回箭头点了没反应（用户读作"卡在导入页"）。弹不动就回首页。
                    onBack = {
                        if (!navController.popBackStack()) navController.navigateTopLevel("home")
                    },
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
                    onImportPrepared = {
                        navController.openImportPreviewAfterFetch()
                    },
                    viewModel = viewModel,
                )
            }
        }
        composable("spoc_login") {
            CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
                SpocLoginScreen(
                    onBack = { navController.popBackStack() },
                    // 登录成功 → 直接把登录页换成扫码页：再退回首页让用户点第二次没有意义，
                    // 而留在栈里会让「返回」把用户又丢回一个已经用完的登录页
                    onLoggedIn = {
                        navController.navigate("spoc_scan") {
                            popUpTo("spoc_login") { inclusive = true }
                        }
                    },
                )
            }
        }
        composable("spoc_scan") {
            CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
                SpocScanScreen(
                    onBack = { navController.popBackStack() },
                    onNeedLogin = { navController.navigate("spoc_login") },
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
                    onOpenCourseManagement = { navController.navigate("course_management") },
                    onOpenStats = { navController.navigate("stats") },
                    onOpenSpocSignIn = openSpocSignIn,
                    bottomBarVisible = bottomBarVisible,
                )
            }
        }
        composable("stats") {
            CompositionLocalProvider(LocalAnimatedVisibilityScope provides this) {
                StatsScreen(
                    onBack = { navController.popBackStack() },
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
                    onOpenCourseManagement = { navController.navigate("course_management") },
                    onOpenSpocSignIn = openSpocSignIn,
                )
            }
        }
        }
        }
    }
}

/** 一次跳转该配哪种转场，取值由 [navMotionFor] 判定 */
private enum class NavMotion {
    FADE_THROUGH,
    CONTAINER_YIELD,
    SLIDE,
}

/** 课程卡会同时出现在两端、因而触发 sharedElement 的那一族页面 */
private fun isCourseCardPage(entry: NavBackStackEntry?): Boolean {
    val route = entry?.destination?.route ?: return false
    return route == "home" || route == "course_management" ||
        // 只有已存在的课程才有配对的卡片；新增课程（id = -1）那一格没有
        (route == "editor/{courseId}" && (entry.arguments?.getLong("courseId") ?: -1L) >= 0L)
}

/**
 * 转场编排（④M-07a / §7.2）：先问"这两个页面是什么关系"，再决定容器怎么动，
 * 而不是把一套 slide 发给所有 destination。
 *
 * - 两个一级 tab 之间：**Fade through**。首页 / 导入 / 设置是平行跳转，横滑会读成层级。
 * - 课程卡在两端都可见：**容器让位**，位移交给 sharedElement，整页再滑一次就是
 *   两股动画抢注意力。
 * - 其余（登录页、导入历史、设置子页）：继续 slide，那才是"进入下一层"。
 *
 * `initialState` / `targetState` 在进出场两侧读到的是同一次跳转的两端，
 * 所以一个判定就能同时约束进页与出页，不会出现"新页淡入、旧页还在滑"。
 */
private fun navMotionFor(
    from: androidx.navigation.NavBackStackEntry?,
    to: androidx.navigation.NavBackStackEntry?,
): NavMotion = when {
    navItems.any { it.route == from?.destination?.route } &&
        navItems.any { it.route == to?.destination?.route } -> NavMotion.FADE_THROUGH
    isCourseCardPage(from) && isCourseCardPage(to) -> NavMotion.CONTAINER_YIELD
    else -> NavMotion.SLIDE
}

/**
 * @param reduceMotion 由组合期捕获：转场 lambda 不在组合里求值，取不到 LocalReduceMotion
 * @param enterFromRight push 时新页从右侧推入，pop 时从左侧回来
 *
 * 规格一律走 [motionSpecFor]：这里此前把 `tween(MotionTokens.X)` 重写了 8 遍，
 * 等于把"关了动画就瞬到"这条策略在第二个地方又表达了一次（此处靠 reduceMotion
 * 参数兜住，但下一处新转场就会漏）。时长与缓动的配对逐条照搬，未作调整。
 */
private fun navEnter(
    style: NavMotion,
    reduceMotion: Boolean,
    enterFromRight: Boolean,
): EnterTransition {
    if (reduceMotion) return EnterTransition.None
    return when (style) {
        // 旧页先退净，新页才进来，中间不留两张半透明的页叠在一起
        NavMotion.FADE_THROUGH -> fadeIn(
            motionSpecFor(
                reduceMotion,
                MotionTokens.DURATION_FADE_THROUGH_ENTER,
                delayMillis = MotionTokens.DURATION_FADE_THROUGH_EXIT,
            ),
        )
        // 让位不等于设 None：那样旧页会在动画收尾的一刻硬切消失
        NavMotion.CONTAINER_YIELD ->
            fadeIn(motionSpecFor(reduceMotion, MotionTokens.DURATION_SHORT))
        NavMotion.SLIDE -> slideInHorizontally(
            animationSpec = motionSpecFor(
                reduceMotion,
                MotionTokens.DURATION_MEDIUM,
                easing = MotionTokens.EasingStandard,
            ),
            initialOffsetX = { if (enterFromRight) it / 6 else -it / 6 },
        ) + fadeIn(motionSpecFor(reduceMotion, MotionTokens.DURATION_MEDIUM))
    }
}

/** @param exitToRight push 时旧页退向左侧，pop 时退向右侧；规格来源同 [navEnter] */
private fun navExit(
    style: NavMotion,
    reduceMotion: Boolean,
    exitToRight: Boolean,
): ExitTransition {
    if (reduceMotion) return ExitTransition.None
    return when (style) {
        NavMotion.FADE_THROUGH ->
            fadeOut(motionSpecFor(reduceMotion, MotionTokens.DURATION_FADE_THROUGH_EXIT))
        NavMotion.CONTAINER_YIELD ->
            fadeOut(motionSpecFor(reduceMotion, MotionTokens.DURATION_SHORT))
        NavMotion.SLIDE -> slideOutHorizontally(
            animationSpec = motionSpecFor(
                reduceMotion,
                MotionTokens.DURATION_MEDIUM,
                easing = MotionTokens.EasingStandard,
            ),
            targetOffsetX = { if (exitToRight) it / 6 else -it / 6 },
        ) + fadeOut(motionSpecFor(reduceMotion, MotionTokens.DURATION_MEDIUM))
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

/**
 * 教务抓取完成后的落点：回到导入页看那张「待确认导入」。
 *
 * 原先两条路径都走 [navigateTopLevel]，而它是 `popUpTo(home){saveState}` + `restoreState`：
 * 首页 → 导入页 → 登录页这条最常见路径下，登录页会被一并存进返回栈，用户反映
 * "点底部的查看导入预览没反应、页面也不自动跳"。预览是**已经落在导入页上的一件内容**，
 * 不是新的一次导航，所以 import 已在栈上时直接回退过去 —— 不出栈、不存状态，
 * 同时把登录页清掉（返回键回到课表而不是再进一次登录）。
 */
private fun androidx.navigation.NavHostController.openImportPreviewAfterFetch() {
    // import 已在栈上时返回 true 并停在它上面（登录页随之出栈）；
    // 不在栈上（从别处直接抓完）返回 false，退回常规的一级跳转。
    if (!popBackStack("import", inclusive = false)) navigateTopLevel("import")
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
        containerHeight = DesignTokens.bottomBarHeight,
        indicatorHeight = DesignTokens.bottomBarIndicatorHeight,
        isLightTheme = !darkTheme,
        // 底栏表面色：过高会像不透明色条，0.20 让背景能透出来
        containerAlpha = DesignTokens.CHROME_SURFACE_ALPHA,
        containerColor = if (darkTheme) {
            com.buaa.schedule.core.designsystem.DarkGlassTint
        } else {
            com.buaa.schedule.core.designsystem.LightGlassTint
        },
        tabContent = { index ->
            val item = navItems[index]
            val accentTint = com.buaa.schedule.core.designsystem.liquid.LocalLiquidBottomTabAccentTint.current
            val selected = currentRoute?.hierarchy?.any { it.route == item.route } == true
            NavItemContent(item, selected = selected, accentTint = accentTint)
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
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        onClick = { onNavigate(item.route) },
                    )
                    .padding(horizontal = DesignTokens.spaceL, vertical = DesignTokens.spaceS),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                NavItemContent(item, selected = selected)
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
            .width(DesignTokens.navRailWidth),
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
                        // 与底栏同一套 tab 语义：这里此前是 clickable，屏幕阅读器
                        // 只念得出「标签，按钮」，念不出「已选中」（①C-01 的宽屏半边）
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = { onNavigate(item.route) },
                        )
                        .padding(vertical = DesignTokens.spaceL),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    NavItemContent(item, selected = selected)
                }
            }
        }
    }
}

