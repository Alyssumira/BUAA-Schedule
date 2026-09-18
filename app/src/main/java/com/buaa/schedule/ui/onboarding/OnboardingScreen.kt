package com.buaa.schedule.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.buaa.schedule.R
import com.buaa.schedule.core.FirstRun
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.core.designsystem.GlassSurface
import com.buaa.schedule.core.designsystem.GlassVariant
import com.buaa.schedule.core.designsystem.ModalTransition
import com.buaa.schedule.core.designsystem.SettingsRow
import com.buaa.schedule.core.designsystem.SettingsSwitchRow
import com.buaa.schedule.reminder.ClassProgressReceiver
import com.buaa.schedule.ui.settings.ReminderGuidance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 引导的四个步骤。顺序即用户看到的顺序。
 *
 * 「环境自检」与「厂商后台放行」原本是两步，这里合为一步（审查 I-03 / A-01）：
 * 二者机制上是同一件事 —— 都在决定"提醒能不能可靠送达"，且设置页里各有入口，
 * 并列摆出来只是多花用户一次「下一步」。6 步时点下一步要 5 次，验收线是 ≤4。
 *
 * 「导入课表」排在最后（真机反馈：引导的落点就该是课表本身）。它原先后面还跟着
 * 一页「完成」，那页只有实况与勿扰两个开关 —— 两个开关都属"提醒可靠不可靠"，
 * 并入上一步的可靠性页；省掉一页后点最后一步的按钮即结束引导，不必再确认一次。
 */
private const val STEP_WELCOME = 0
private const val STEP_PRIVACY = 1
private const val STEP_RELIABILITY = 2
private const val STEP_IMPORT = 3
private const val STEP_COUNT = 4

/**
 * 首启引导页：欢迎 → 隐私同意 → 提醒可靠性（环境自检 + 厂商后台放行 + 实况/勿扰开关）→ 导入课表。
 *
 * 结构直接借自 HyperIsland 的 `OnboardingPage.kt`，因为那套结构解决的正是我们的问题：
 * - 每一步只讲一件事，`HorizontalPager(userScrollEnabled = false)` 禁止用户滑过；
 *   步序有依赖（隐私没同意不该看到自检，自检没跑完不该看到导入），所以不给乱序滑动；
 * - 隐私步必须勾选才能继续（`nextEnabled` 判据在 `OnboardingPage.kt:280-282`）；
 * - 可靠性步是**逐项探针卡**（转圈 / ✓ / ✗），而不是一个"请自行去设置里开"的文字段落；
 * - 探针没过时不是死路：Next 会弹「继续（稍后配置）/ 重试」，用户永远能出去
 *   （`OnboardingPage.kt:294-301`）。
 *
 * 没有照抄的一点：它在"通知样式"步加了 3 秒阅读倒计时才允许下一步。那是为了让人
 * 看完两张截图；我们这一步是勾选框，加倒计时只会变成用户等按钮变亮，属于
 * 干扰而非保护，所以没搬。
 */
@Composable
fun OnboardingScreen(
    onFinished: (startRoute: String?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { STEP_COUNT })

    var checks by remember { mutableStateOf<List<CheckItem>?>(null) }
    var checking by remember { mutableStateOf(false) }
    var privacyChecked by remember { mutableStateOf(FirstRun.privacyAccepted(context)) }
    var vendorConfirmed by remember { mutableStateOf(false) }
    var showEnvironmentDialog by remember { mutableStateOf(false) }

    // 实况与勿扰：原「完成」页删掉后住进可靠性页（见文件头步序注释）。
    // 直接读写 prefs —— 这两项的运行时消费方本来就是读 prefs 的通知链路
    val progressPrefs = remember(context) {
        context.getSharedPreferences(ClassProgressReceiver.PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }
    var liveEnabled by remember {
        mutableStateOf(progressPrefs.getBoolean(ClassProgressReceiver.PREF_CLASS_PROGRESS, true))
    }
    var dndEnabled by remember {
        mutableStateOf(progressPrefs.getBoolean(ClassProgressReceiver.PREF_DND, false))
    }

    fun goTo(page: Int) {
        scope.launch { pagerState.animateScrollToPage(page.coerceIn(0, STEP_COUNT - 1)) }
    }

    fun runChecks() {
        if (checking) return
        checking = true
        scope.launch {
            // 探针里是 Settings.System 查询、build.prop 读文件、NotificationManager 取活跃通知
            // ——全是阻塞 IO。此前直接在主线程跑 runAll（只 delay(1L) 让转圈先画出来），
            // 首启进这一步就会掉帧甚至 ANR：换 Default 调度器把它们挪离主线程（P1）。
            // 调度器切换本身就会让主线程有机会画出 spinner，原来的 delay(1L) 不再需要。
            checks = withContext(Dispatchers.Default) { EnvironmentCheck.runAll(context) }
            checking = false
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { runChecks() }

    // 进入可靠性步才申请通知权限，并顺手跑一遍探针：
    // 冷启动就弹权限框等于在用户还没知道这应用是干什么的时候就索要权限。
    // 引导内的申请点只有这一个 —— 自检与厂商放行的合页只动布局，
    // 没多出弹窗，也没把索要权限提前到欢迎/隐私步
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == STEP_RELIABILITY) {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                runChecks()
            }
        }
    }

    // 自检项与厂商项都会把用户送到系统页，回来时结论已经变了。两步时代靠翻页改变
    // currentPage 重触发上面的 LaunchedEffect，合并后页面序号不动，所以显式挂 ON_RESUME
    // 重跑只读探针。checks 非空是防重入门闩：首次那一趟由权限回调负责，别抢它
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                pagerState.currentPage == STEP_RELIABILITY &&
                checks != null
            ) {
                runChecks()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        OnboardingHeader(
            currentPage = pagerState.currentPage,
            onClose = {
                FirstRun.completeOnboarding(context)
                onFinished(null)
            },
        )
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = false,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { page ->
            OnboardingStepPage(
                page = page,
                privacyChecked = privacyChecked,
                onPrivacyCheckedChange = { privacyChecked = it },
                checking = checking,
                checks = checks,
                onRetryChecks = { runChecks() },
                onFixCheck = { item ->
                    EnvironmentCheck.openFix(context, item.id)
                    // 跳系统页时页面序号不变：回到本页由上面的 ON_RESUME 监听重跑探针
                },
                vendorConfirmed = vendorConfirmed,
                onVendorConfirmedChange = { vendorConfirmed = it },
                liveEnabled = liveEnabled,
                onLiveEnabledChange = {
                    liveEnabled = it
                    progressPrefs.edit { putBoolean(ClassProgressReceiver.PREF_CLASS_PROGRESS, it) }
                },
                dndEnabled = dndEnabled,
                onDndEnabledChange = {
                    dndEnabled = it
                    progressPrefs.edit { putBoolean(ClassProgressReceiver.PREF_DND, it) }
                },
                onImportBuaa = {
                    FirstRun.completeOnboarding(context)
                    onFinished("import")
                },
                onAddCourse = {
                    FirstRun.completeOnboarding(context)
                    // 「手动添加课程」此前只是回首页：按钮写着添加，点了什么也不会发生。
                    // editor/-1 与首页 FAB 用的是同一条路由（-1 = 新建课程）。
                    onFinished("editor/-1")
                },
            )
        }
        OnboardingControls(
            currentPage = pagerState.currentPage,
            nextEnabled = !checking && when (pagerState.currentPage) {
                STEP_PRIVACY -> privacyChecked
                else -> true
            },
            onPrevious = { goTo(pagerState.currentPage - 1) },
            onNext = {
                when (pagerState.currentPage) {
                    STEP_PRIVACY -> {
                        if (privacyChecked) FirstRun.acceptPrivacy(context)
                        goTo(STEP_RELIABILITY)
                    }
                    STEP_RELIABILITY -> {
                        val unmet = EnvironmentCheck.unmetBlockers(checks.orEmpty())
                        if (unmet.isEmpty()) goTo(STEP_IMPORT) else showEnvironmentDialog = true
                    }
                    STEP_IMPORT -> {
                        // 最后一步点「下一步」= 现在不导入。引导到此为止，
                        // 落回首页（导入页随时能从底栏再进）
                        FirstRun.completeOnboarding(context)
                        onFinished(null)
                    }
                    else -> goTo(pagerState.currentPage + 1)
                }
            },
        )
    }

    ModalTransition(open = showEnvironmentDialog) { modal ->
        EnvironmentUnmetDialog(
            modifier = modal,
            onDismiss = { showEnvironmentDialog = false },
            onContinue = {
                showEnvironmentDialog = false
                goTo(STEP_IMPORT)
            },
            onRetry = {
                showEnvironmentDialog = false
                runChecks()
            },
        )
    }
}

@Composable
private fun OnboardingHeader(currentPage: Int, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = DesignTokens.topBarHeight)
            .padding(horizontal = DesignTokens.spaceL),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (currentPage > STEP_WELCOME) {
            Text(
                text = "BUAA 课表",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.weight(1f))
        // 右上角永远给一条退路：引导不是必须走完才能用应用。
        // 文案带上「稍后」并把颜色提到 onSurface（I-03）：两个字的次级色按钮看着像
        // 装饰，用户会以为必须走完才能出去；点它和走完流程一样会落盘"引导已完成"
        TextButton(onClick = onClose) {
            Text("先跳过，稍后设置", color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun OnboardingStepPage(
    page: Int,
    privacyChecked: Boolean,
    onPrivacyCheckedChange: (Boolean) -> Unit,
    checking: Boolean,
    checks: List<CheckItem>?,
    onRetryChecks: () -> Unit,
    onFixCheck: (CheckItem) -> Unit,
    vendorConfirmed: Boolean,
    onVendorConfirmedChange: (Boolean) -> Unit,
    liveEnabled: Boolean,
    onLiveEnabledChange: (Boolean) -> Unit,
    dndEnabled: Boolean,
    onDndEnabledChange: (Boolean) -> Unit,
    onImportBuaa: () -> Unit,
    onAddCourse: () -> Unit,
) {
    // 宽屏（平板 / 折叠屏展开）不把正文拉到整屏宽，一行 60 个汉字没法读
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val maxWidth = if (screenWidth >= DesignTokens.breakpointWide) screenWidth * 0.6f else 520.dp
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = maxWidth)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DesignTokens.spaceXL, vertical = DesignTokens.spaceL),
        ) {
            when (page) {
                STEP_WELCOME -> WelcomeStep()
                STEP_PRIVACY -> PrivacyStep(privacyChecked, onPrivacyCheckedChange)
                STEP_RELIABILITY -> ReliabilityStep(
                    checking = checking,
                    checks = checks,
                    onRetryChecks = onRetryChecks,
                    onFixCheck = onFixCheck,
                    vendorConfirmed = vendorConfirmed,
                    onVendorConfirmedChange = onVendorConfirmedChange,
                    liveEnabled = liveEnabled,
                    onLiveEnabledChange = onLiveEnabledChange,
                    dndEnabled = dndEnabled,
                    onDndEnabledChange = onDndEnabledChange,
                )
                else -> ImportStep(onImportBuaa, onAddCourse)
            }
            Spacer(Modifier.height(DesignTokens.spaceXL))
        }
    }
}

@Composable
private fun WelcomeStep() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.onboarding_hero),
            contentDescription = "北航课表吉祥物插画",
            modifier = Modifier
                .size(180.dp)
                // 只裁四个角：画面边缘的字样与卫星发饰都在边的中段，不会被吃掉
                .clip(RoundedCornerShape(DesignTokens.cornerPage)),
        )
        Spacer(Modifier.height(DesignTokens.spaceL))
        Text(
            text = "BUAA 课表",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(DesignTokens.spaceM))
        Text(
            text = "北航校历、节次与教室，全部保存在你自己的手机上",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PrivacyStep(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    StepHeading("隐私与数据", "先说清楚我们会把什么送出这台手机", Icons.AutoMirrored.Filled.HelpOutline)
    GlassSurface(variant = GlassVariant.PANEL, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$PRIVACY_STATEMENT\n\n不点「我已阅读并同意」，以上联网行为都不会发生。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(DesignTokens.spaceM))
    // 与厂商放行那几行同壳同件：整行可点（Role.Checkbox），勾选框自身不再单独接收点击
    GlassSurface(variant = GlassVariant.PANEL, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    role = Role.Checkbox,
                    onClickLabel = if (checked) "取消同意" else "同意隐私说明",
                    onClick = { onCheckedChange(!checked) },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Text(
                text = "我已阅读并同意",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * 可靠性自检：探针 + 厂商放行 + 实况/勿扰开关合在一步。
 *
 * 合并的理由（I-03）是这两段讲的是同一件事 —— 提醒能不能可靠送达；拆成两步时用户
 * 要点两次「下一步」，而第二步没有任何新判据，只是把探针读不到的开关列一遍。
 *
 * 没有做成"探针失败才展开厂商清单"的折叠区：MIUI「后台弹出界面」这类开关根本没有
 * 探针，失败与否都不会出现在上面的清单里，折叠起来等于把最隐蔽的一项藏起来。
 * 探针还在跑时只把探针那一块换成加载圈，厂商清单照常渲染 —— 它不依赖探针结果。
 *
 * 实况与勿扰两个开关原本站在已删除的「完成」页上（导入课表改成最后一步后那一页没了）。
 * 它们决定的同样是"铃响时用户能不能被通知到"，放这里比放到导入页更顺。
 */
@Composable
private fun ReliabilityStep(
    checking: Boolean,
    checks: List<CheckItem>?,
    onRetryChecks: () -> Unit,
    onFixCheck: (CheckItem) -> Unit,
    vendorConfirmed: Boolean,
    onVendorConfirmedChange: (Boolean) -> Unit,
    liveEnabled: Boolean,
    onLiveEnabledChange: (Boolean) -> Unit,
    dndEnabled: Boolean,
    onDndEnabledChange: (Boolean) -> Unit,
) {
    StepHeading(
        "提醒可靠性",
        "上课铃响不响、课堂实况出不出得来，取决于下面这些开关",
        Icons.Filled.Settings,
    )
    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceM)) {
        if (checking && checks == null) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
        } else {
            checks.orEmpty().forEach { item ->
                CheckCard(item, enabled = !checking, onFix = { onFixCheck(item) })
            }
            TextButton(onClick = onRetryChecks, enabled = !checking) {
                Text(if (checking) "正在检测…" else "重新检测")
            }
        }
        VendorGuidance(vendorConfirmed, onVendorConfirmedChange)
        SectionHeading(
            "上课期间的行为",
            "两项随时能在「设置 → 提醒」里改，先按默认走也不影响后面的导入",
        )
        ToggleRow(
            title = "课程进行中实况",
            summary = "上课期间常驻进度通知，尽量显示在岛 / 流体云上",
            checked = liveEnabled,
            onCheckedChange = onLiveEnabledChange,
        )
        ToggleRow(
            title = "上课自动勿扰",
            summary = "上课期间自动进入勿扰，下课恢复原设置",
            checked = dndEnabled,
            onCheckedChange = onDndEnabledChange,
        )
    }
}

@Composable
private fun CheckCard(item: CheckItem, enabled: Boolean, onFix: () -> Unit) {
    // 卡片壳保留（引导页的条目本来就是独立的玻璃卡），行内容用设置页同一件 SettingsRow：
    // 标题/摘要的字号与颜色、触控下限从此不用各写一份
    GlassSurface(variant = GlassVariant.PANEL, modifier = Modifier.fillMaxWidth()) {
        SettingsRow(
            title = item.title,
            summary = item.summary,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusMark(item.status, enabled)
                    if (item.actionLabel != null) {
                        TextButton(onClick = onFix, enabled = enabled) {
                            Text(item.actionLabel, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun StatusMark(status: CheckStatus, enabled: Boolean) {
    if (!enabled) {
        CircularProgressIndicator(
            modifier = Modifier.size(DesignTokens.iconMedium),
            strokeWidth = 2.dp,
        )
        return
    }
    val (icon, tint) = when (status) {
        CheckStatus.Passed -> Icons.Filled.Check to MaterialTheme.colorScheme.primary
        CheckStatus.Failed -> Icons.Filled.Close to MaterialTheme.colorScheme.error
        CheckStatus.Unknown -> Icons.AutoMirrored.Filled.HelpOutline to MaterialTheme.colorScheme.outline
    }
    Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(DesignTokens.iconMedium))
    Spacer(Modifier.width(DesignTokens.spaceM))
}

/**
 * 厂商后台放行：这几项系统不提供任何可读探针，只能让用户自己去开、再回来确认。
 *
 * 「后台弹出界面」是澎湃/MIUI 上最隐蔽的一项：默认关闭时，闹钟能触发、通知能发，
 * 但界面类行为会被系统静默拦下 —— 真机上排查 USB 拉起 Activity 挂死就是它
 * （见 docs/VENDOR_NOTES.md 2026-09-16 记录）。既然探测不到，就老老实实问用户。
 *
 * 三行分别住在三个不同的页面里，所以各自跳自己的落点：此前共用一个跳转，
 * 用户点「省电策略」「后台弹出界面」也被扔到自启动列表，只能自己再翻一遍。
 */
@Composable
private fun VendorGuidance(confirmed: Boolean, onConfirmedChange: (Boolean) -> Unit) {
    SectionHeading(
        "下面几项系统读不到，只能自己去开",
        "国产 ROM 会主动清理后台，这几项不做，上课铃可能整个学期都不响",
    )
    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceM)) {
        VendorRow(
            title = "自启动与后台管理",
            summary = "允许本应用在后台自启动，避免重启手机后提醒失效",
            onOpen = { ReminderGuidance.openAutoStartSettings(it) },
        )
        // 与上面探针里的「电池优化豁免」跳的是同一个系统页，挡的却不是同一刀：
        // 探针读的是 AOSP 白名单位，MIUI 另有一档读不到的私有"省电策略"，
        // 它能把 AlarmManager 闹钟直接吞掉（docs/VENDOR_NOTES.md「杀后台」条），两行都得留
        VendorRow(
            title = "省电策略：无限制",
            summary = "MIUI 路径：安全中心 → 应用管理 → BUAA 课表 → 省电策略 → 无限制；" +
                "这里直接跳到系统的电池优化页，机型没有该页时退回应用详情页",
            onOpen = { ReminderGuidance.requestIgnoreBatteryOptimizations(it) },
        )
        VendorRow(
            title = "后台弹出界面（小米 / 澎湃）",
            summary = "在安全中心的应用权限页「其他权限」里；此开关系统不提供读取接口，只能人工确认",
            onOpen = { ReminderGuidance.openVendorPermissionPage(it) },
        )
        // 与上面三行同壳：确认行也住玻璃卡里，不再是一条裸行
        GlassSurface(variant = GlassVariant.PANEL, modifier = Modifier.fillMaxWidth()) {
            // 整行可点（Role.Checkbox），Checkbox 自身 onCheckedChange=null——
            // 与导入页的勾选行同一件写法，避免一次点击触发两回
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        role = Role.Checkbox,
                        onClickLabel = if (confirmed) "取消确认" else "标记为已确认",
                        onClick = { onConfirmedChange(!confirmed) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = confirmed, onCheckedChange = null)
                Text(
                    text = "我已在系统设置里确认（或不需要）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * 一步之内的分节标题。合并后的那一步里有两个小节，再各自挂一个带 40dp 图标的
 * [StepHeading] 会让一屏出现两个同级大标题，读起来像两步没拆开。
 */
@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VendorRow(title: String, summary: String, onOpen: (android.content.Context) -> Unit) {
    val context = LocalContext.current
    // 与设置页的"进入下级"行同件：标题/摘要 + 尾部 ">"，点击语义由 SettingsRow 负责
    GlassSurface(variant = GlassVariant.PANEL, modifier = Modifier.fillMaxWidth()) {
        SettingsRow(
            title = title,
            summary = summary,
            showChevron = true,
            onClick = { onOpen(context) },
        )
    }
}

@Composable
private fun ImportStep(onImportBuaa: () -> Unit, onAddCourse: () -> Unit) {
    StepHeading("导入你的课表", "两种方式，之后都能在设置里改", Icons.Filled.Check)
    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceM)) {
        Button(
            onClick = onImportBuaa,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DesignTokens.minTouchTarget),
            enabled = true,
        ) {
            Text("登录教务系统自动导入")
        }
        OutlinedButton(
            onClick = onAddCourse,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DesignTokens.minTouchTarget),
        ) {
            Text("手动添加课程")
        }
        Text(
            text = "自动导入只把课表抓回本机，账号与密码不离开手机；不想现在弄也可以先跳过。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ToggleRow(title: String, summary: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    // 壳 + 行：整行翻转由 SettingsSwitchRow 负责（Role.Switch），不再"卡一个点击、盒一个点击"
    GlassSurface(variant = GlassVariant.PANEL, modifier = Modifier.fillMaxWidth()) {
        SettingsSwitchRow(
            title = title,
            summary = summary,
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun StepHeading(title: String, subtitle: String, icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(DesignTokens.iconHero),
    )
    Spacer(Modifier.height(DesignTokens.spaceM))
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(DesignTokens.spaceXS))
    Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(DesignTokens.spaceL))
}

@Composable
private fun OnboardingControls(
    currentPage: Int,
    nextEnabled: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DesignTokens.spaceXL, vertical = DesignTokens.spaceL),
        horizontalArrangement = Arrangement.spacedBy(DesignTokens.spaceM),
    ) {
        OutlinedButton(
            onClick = onPrevious,
            enabled = currentPage > STEP_WELCOME,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = DesignTokens.minTouchTarget),
        ) {
            Text("上一步")
        }
        Button(
            onClick = onNext,
            enabled = nextEnabled,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = DesignTokens.minTouchTarget),
        ) {
            Text(if (currentPage == STEP_IMPORT) "开始使用" else "下一步")
        }
    }
}

@Composable
private fun EnvironmentUnmetDialog(
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
    onRetry: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text("有阻塞项未通过") },
        text = {
            Text(
                "通知权限没开时，上课提醒与课堂实况都不会出现。" +
                    "这一页就能跳去系统设置，也可以稍后在设置页里配置。",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = { TextButton(onClick = onRetry) { Text("重新检测") } },
        dismissButton = { TextButton(onClick = onContinue) { Text("稍后再说") } },
    )
}

/**
 * 隐私说明正文。引导页与设置页共用这一段：
 * 两处各写一份，改了引导页那份，设置页就会继续承诺旧行为。
 */
internal const val PRIVACY_STATEMENT =
    "课表、提醒、节次时间与个性化设置只写在本机应用私有目录，不上传服务器。\n\n" +
        "三处会联网：① 你主动登录教务系统抓取课表时，账号与教务接口数据只在你手机与 " +
        "buaa.edu.cn 之间往返；② 你主动扫码签到时，向智学北航发送这一次签到所需的" +
        "班级标识与你的学号，相机画面只在本机解码、不上传；③ 「检查更新」每天向 Gitee " +
        "问一次有没有新版本，只发送版本号，不发送任何个人数据。"
