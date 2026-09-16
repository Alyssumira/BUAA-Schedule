package com.buaa.schedule.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.buaa.schedule.R
import com.buaa.schedule.core.FirstRun
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.core.designsystem.GlassSurface
import com.buaa.schedule.core.designsystem.GlassVariant
import com.buaa.schedule.reminder.ClassProgressReceiver
import com.buaa.schedule.ui.settings.ReminderGuidance
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 引导的六个步骤。顺序即用户看到的顺序 */
private const val STEP_WELCOME = 0
private const val STEP_PRIVACY = 1
private const val STEP_ENVIRONMENT = 2
private const val STEP_VENDOR = 3
private const val STEP_IMPORT = 4
private const val STEP_DONE = 5
private const val STEP_COUNT = 6

/**
 * 首启引导页：欢迎 → 隐私同意 → 环境自检 → 厂商后台放行 → 导入课表 → 完成。
 *
 * 结构直接借自 HyperIsland 的 `OnboardingPage.kt`，因为那套结构解决的正是我们的问题：
 * - 每一步只讲一件事，`HorizontalPager(userScrollEnabled = false)` 禁止用户滑过；
 * - 隐私步必须勾选才能继续（`nextEnabled` 判据在 `OnboardingPage.kt:280-282`）；
 * - 环境步是**逐项探针卡**（转圈 / ✓ / ✗），而不是一个"请自行去设置里开"的文字段落；
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

    fun goTo(page: Int) {
        scope.launch { pagerState.animateScrollToPage(page.coerceIn(0, STEP_COUNT - 1)) }
    }

    fun runChecks() {
        if (checking) return
        checking = true
        scope.launch {
            // 探针里有 Settings.System / PowerManager 查询，让它们离开主线程
            delay(1L)
            checks = EnvironmentCheck.runAll(context)
            checking = false
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { runChecks() }

    // 进入自检步才申请通知权限，并顺手跑一遍探针：
    // 冷启动就弹权限框等于在用户还没知道这应用是干什么的时候就索要权限
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == STEP_ENVIRONMENT) {
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
                    // 授权页返回后结果已变：离开时由 LaunchedEffect 重跑，这里先给即时反馈
                },
                vendorConfirmed = vendorConfirmed,
                onVendorConfirmedChange = { vendorConfirmed = it },
                onImportBuaa = {
                    FirstRun.completeOnboarding(context)
                    onFinished("import")
                },
                onAddCourse = {
                    FirstRun.completeOnboarding(context)
                    onFinished("home")
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
                        goTo(STEP_ENVIRONMENT)
                    }
                    STEP_ENVIRONMENT -> {
                        val unmet = EnvironmentCheck.unmetBlockers(checks.orEmpty())
                        if (unmet.isEmpty()) goTo(STEP_VENDOR) else showEnvironmentDialog = true
                    }
                    STEP_DONE -> {
                        FirstRun.completeOnboarding(context)
                        onFinished(null)
                    }
                    else -> goTo(pagerState.currentPage + 1)
                }
            },
        )
    }

    if (showEnvironmentDialog) {
        EnvironmentUnmetDialog(
            onDismiss = { showEnvironmentDialog = false },
            onContinue = {
                showEnvironmentDialog = false
                goTo(STEP_VENDOR)
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
            .height(56.dp)
            .padding(horizontal = DesignTokens.spaceL),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (currentPage > STEP_WELCOME) {
            Text(
                text = "BUAA 课表",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.weight(1f))
        // 右上角永远给一条退路：引导不是必须走完才能用应用
        TextButton(onClick = onClose) {
            Text("跳过", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    onImportBuaa: () -> Unit,
    onAddCourse: () -> Unit,
) {
    // 宽屏（平板 / 折叠屏展开）不把正文拉到整屏宽，一行 60 个汉字没法读
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val maxWidth = if (screenWidth >= 600.dp) screenWidth * 0.6f else 520.dp
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
                STEP_ENVIRONMENT -> EnvironmentStep(checking, checks, onRetryChecks, onFixCheck)
                STEP_VENDOR -> VendorStep(vendorConfirmed, onVendorConfirmedChange)
                STEP_IMPORT -> ImportStep(onImportBuaa, onAddCourse)
                else -> DoneStep()
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
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(
            text = "我已阅读并同意",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun EnvironmentStep(
    checking: Boolean,
    checks: List<CheckItem>?,
    onRetry: () -> Unit,
    onFix: (CheckItem) -> Unit,
) {
    StepHeading(
        "关键开关自检",
        "提醒能不能准时响、课堂实况能不能出现，取决于这几项",
        Icons.Filled.Settings,
    )
    if (checking && checks == null) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceM)) {
        checks.orEmpty().forEach { item ->
            CheckCard(item, enabled = !checking, onFix = { onFix(item) })
        }
        TextButton(onClick = onRetry, enabled = !checking) {
            Text(if (checking) "正在检测…" else "重新检测")
        }
    }
}

@Composable
private fun CheckCard(item: CheckItem, enabled: Boolean, onFix: () -> Unit) {
    GlassSurface(variant = GlassVariant.PANEL, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusMark(item.status, enabled)
            if (item.actionLabel != null) {
                TextButton(onClick = onFix, enabled = enabled) {
                    Text(item.actionLabel, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun StatusMark(status: CheckStatus, enabled: Boolean) {
    if (!enabled) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
        )
        return
    }
    val (icon, tint) = when (status) {
        CheckStatus.Passed -> Icons.Filled.Check to MaterialTheme.colorScheme.primary
        CheckStatus.Failed -> Icons.Filled.Close to MaterialTheme.colorScheme.error
        CheckStatus.Unknown -> Icons.AutoMirrored.Filled.HelpOutline to MaterialTheme.colorScheme.outline
    }
    Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
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
private fun VendorStep(confirmed: Boolean, onConfirmedChange: (Boolean) -> Unit) {
    StepHeading(
        "厂商后台放行",
        "国产 ROM 会主动清理后台，这一步不做，上课铃可能整个学期都不响",
        Icons.Filled.Settings,
    )
    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceM)) {
        VendorRow(
            title = "自启动与后台管理",
            summary = "允许本应用在后台自启动，避免重启手机后提醒失效",
            onOpen = { ReminderGuidance.openAutoStartSettings(it) },
        )
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = confirmed, onCheckedChange = onConfirmedChange)
            Text(
                text = "我已在系统设置里确认（或不需要）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun VendorRow(title: String, summary: String, onOpen: (android.content.Context) -> Unit) {
    val context = LocalContext.current
    GlassSurface(
        variant = GlassVariant.PANEL,
        modifier = Modifier.fillMaxWidth(),
        onClick = { onOpen(context) },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
private fun DoneStep() {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(ClassProgressReceiver.PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }
    var liveEnabled by remember { mutableStateOf(prefs.getBoolean(ClassProgressReceiver.PREF_CLASS_PROGRESS, true)) }
    var dndEnabled by remember { mutableStateOf(prefs.getBoolean(ClassProgressReceiver.PREF_DND, false)) }
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StepHeading("准备好了", "下面两项随时能在「设置 → 提醒」里改", Icons.Filled.Check)
        Spacer(Modifier.height(DesignTokens.spaceL))
        ToggleRow(
            title = "课程进行中实况",
            summary = "上课期间常驻进度通知，尽量显示在岛 / 流体云上",
            checked = liveEnabled,
            onCheckedChange = {
                liveEnabled = it
                prefs.edit { putBoolean(ClassProgressReceiver.PREF_CLASS_PROGRESS, it) }
            },
        )
        Spacer(Modifier.height(DesignTokens.spaceS))
        ToggleRow(
            title = "上课自动勿扰",
            summary = "上课期间自动进入勿扰，下课恢复原设置",
            checked = dndEnabled,
            onCheckedChange = {
                dndEnabled = it
                prefs.edit { putBoolean(ClassProgressReceiver.PREF_DND, it) }
            },
        )
    }
}

@Composable
private fun ToggleRow(title: String, summary: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    GlassSurface(
        variant = GlassVariant.PANEL,
        modifier = Modifier.fillMaxWidth(),
        onClick = { onCheckedChange(!checked) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun StepHeading(title: String, subtitle: String, icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(40.dp),
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
            Text(if (currentPage == STEP_DONE) "开始使用" else "下一步")
        }
    }
}

@Composable
private fun EnvironmentUnmetDialog(onDismiss: () -> Unit, onContinue: () -> Unit, onRetry: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("有阻塞项未通过") },
        text = {
            Text(
                "通知权限没开时，上课提醒与课堂实况都不会出现。" +
                    "可以先去开，也可以选择稍后在设置页里配置。",
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
        "两处会联网：① 你主动登录教务系统抓取课表时，账号与教务接口数据只在你手机与 " +
        "buaa.edu.cn 之间往返；② 「检查更新」每天向 Gitee 问一次有没有新版本，" +
        "只发送版本号，不发送任何个人数据。"
