package com.buaa.schedule.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Widgets
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.core.designsystem.GlassSurface
import com.buaa.schedule.core.designsystem.GlassVariant
import com.buaa.schedule.core.designsystem.Personalization
import com.buaa.schedule.core.designsystem.SettingsGroup
import com.buaa.schedule.core.designsystem.SettingsRow
import com.buaa.schedule.core.designsystem.SettingsSwitchRow
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlotProfile
import com.buaa.schedule.domain.schedule.CourseConstraints
import com.buaa.schedule.domain.schedule.SmartPeriods
import com.buaa.schedule.reminder.ReminderNotifications
import com.buaa.schedule.reminder.TomorrowPreviewReceiver
import com.buaa.schedule.ui.NO_WRITABLE_CALENDAR_MESSAGE
import com.buaa.schedule.ui.ScheduleViewModel
import com.buaa.schedule.widget.BackgroundSync
import com.buaa.schedule.widget.NextClassWidgetProvider
import com.buaa.schedule.widget.TodayWidgetProvider
import com.buaa.schedule.widget.TomorrowWidgetProvider
import com.buaa.schedule.widget.WeekGridWidgetProvider
import com.buaa.schedule.widget.WeekWidgetProvider
import com.buaa.schedule.BuildConfig
import com.buaa.schedule.R
import com.buaa.schedule.core.openExternalUrl
import com.buaa.schedule.update.AUTHOR_GITEE_URL
import com.buaa.schedule.update.AUTHOR_GITHUB_URL
import com.buaa.schedule.update.PROJECT_GITEE_URL
import com.buaa.schedule.update.PROJECT_GITHUB_URL
import com.buaa.schedule.update.UpdateCheck
import com.buaa.schedule.update.UpdateUiState
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * 设置页的分类。每个分类是一个独立**子界面**（不再是同页折叠），
 * 根界面只列分类入口，点进去才渲染该分类的设置项。
 */
enum class SettingsSection(
    val id: String,
    val title: String,
    val summary: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    SCHEDULE("schedule", "课表", "学期设置、节次时间", Icons.Default.DateRange),
    WIDGET("widget", "桌面组件", "组件添加、外观与刷新策略", Icons.Default.Widgets),
    NOTIFICATION("notification", "通知与提醒", "课程提醒、明日预告、可靠性", Icons.Default.Notifications),
    APPEARANCE("appearance", "外观", "深色模式、主题色、液态玻璃、壁纸", Icons.Default.Palette),
    DATA("data", "数据与同步", "系统日历、导出、备份", Icons.Default.CalendarMonth),
    ABOUT("about", "关于", "版本信息与开源许可", Icons.Default.Info),
    ;

    companion object {
        fun fromId(id: String?): SettingsSection? = entries.firstOrNull { it.id == id }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onDarkThemeChange: (com.buaa.schedule.DarkModePreference) -> Unit = {},
    /** null = 分类列表（根界面）；非 null = 只显示该分类的设置项 */
    section: SettingsSection? = null,
    onOpenSection: (SettingsSection) -> Unit = {},
    /** 手机端悬浮玻璃底栏是否在本页显示：显示时滚动内容要在底部让位 */
    bottomBarVisible: Boolean = false,
    viewModel: ScheduleViewModel = viewModel(
        factory = ScheduleViewModel.Factory(LocalContext.current.applicationContext as android.app.Application),
    ),
) {
    val state by viewModel.uiState.collectAsState()
    val semester = state.semester
    var termCode by remember(semester) { mutableStateOf(semester?.termCode ?: "") }
    var termName by remember(semester) { mutableStateOf(semester?.termName ?: "") }
    var startDate by remember(semester) { mutableStateOf(semester?.startDate ?: LocalDate.now().toString()) }
    var totalWeeks by remember(semester) { mutableStateOf(semester?.totalWeeks?.toString() ?: "20") }
    val context = LocalContext.current
    val importMessage by viewModel.importMessage.collectAsState()
    // 关于页的"检查更新"与全局更新弹窗共用同一个状态源：
    // 这里只负责触发和显示进度文案，弹窗由 MainActivity 统一渲染。
    val updateState by UpdateCheck.state.collectAsState()
    val settingsScope = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri?.let { viewModel.exportBackupTo(it.toString()) }
    }

    val icsExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/calendar"),
    ) { uri ->
        uri?.let { viewModel.exportIcsTo(it.toString()) }
    }

    // 备份恢复预览：先解析展示内容与影响范围，确认后再写入（状态在 VM 里，旋转不丢）
    val pendingBackup by viewModel.pendingBackup.collectAsState()

    /** 备份里没有任何课程时的二次确认（继续 = 清空当前课表） */
    val pendingEmptyRestore by viewModel.pendingEmptyRestore.collectAsState()

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { viewModel.readBackupAndPreview(it.toString()) }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.rescheduleReminders()
        // 用户勾选「不再询问」后系统不再弹框，再点申请只会静默拒绝——
        // 表现为「点了没反应」。检测到这种情况直接送去通知设置页。
        if (!granted) {
            val activity = context as? android.app.Activity
            if (Build.VERSION.SDK_INT >= 33 && activity != null &&
                !activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                ReminderGuidance.openNotificationSettings(context)
            }
        }
    }

    val wallpaperLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            // OpenDocument + 持久授权：避免临时 URI 在进程重启后失效
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.onFailure {
                // 部分选择器（"最近"列表等）返回的 URI 不支持持久授权：
                // 当场仍能显示，但重启后会失效回退渐变，必须留痕便于诊断
                android.util.Log.w("SettingsScreen", "壁纸 URI 持久授权失败，重启后可能失效", it)
            }
            Personalization.wallpaperUri = uri.toString()
            Personalization.save(context)
        }
    }

    // SharedPreferences 的获取是一次跨进程 Binder 调用（结果由框架缓存，但仍有开销），
    // 不能在组合期每次重组都取一遍。
    val prefs = remember(context) {
        context.getSharedPreferences("schedule_settings", Context.MODE_PRIVATE)
    }

    // 权限状态刷新器：用户跳到系统设置开权限后返回，页面必须反映新状态。
    // 无 key 的 remember{} 快照会把「已被关闭」钉死在进入页面那一刻，
    // 用户开完权限回来仍看到旧状态，误以为开了没用。
    var permissionResumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) permissionResumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var darkModePref by remember { mutableStateOf(com.buaa.schedule.DarkModePreference.load(prefs)) }
    var glassTier by remember { mutableIntStateOf(Personalization.glassTier) }
    var cardAlpha by remember { mutableFloatStateOf(Personalization.cardAlpha) }
    // 滑块拖动期间只改这里的局部状态：Personalization 是全局 State，
    // 每帧写它会把整个设置页（乃至整棵 UI 树）重组一次，外加一次 SharedPreferences 落盘。
    // 松手（onValueChangeFinished）时才写全局 + 持久化。
    var weekRowScaleDraft by remember { mutableFloatStateOf(Personalization.weekRowScale) }
    var weekCornerDraft by remember { mutableFloatStateOf(Personalization.weekCornerRadiusDp) }

    // ---- 系统日历同步：状态机在 ScheduleViewModel.calendarSync 里 ----
    val calendarSync by viewModel.calendarSync.collectAsState()
    var reminderMode by remember {
        mutableStateOf(
            prefs.getString(com.buaa.schedule.domain.model.ReminderMode.PREF_KEY, com.buaa.schedule.domain.model.ReminderMode.APP)
                ?: com.buaa.schedule.domain.model.ReminderMode.APP
        )
    }

    // 日历权限被勾选「不再询问」拒绝后，系统 launcher 直接回调拒绝、不再弹窗，
    // 只剩引导去系统设置这一条路。区分「可再次弹窗」与「永久拒绝」两种情况。

    /** 申请授权后要继续的动作；null = 走默认的「选日历 → 算差异」同步流程 */
    var pendingCalendarAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val action = pendingCalendarAction
        pendingCalendarAction = null
        if (grants.isNotEmpty() && grants.values.all { it }) {
            viewModel.onCalendarPermissionGranted()
            // 移除已同步日程等独立动作：授权后只做它自己；否则走默认同步流程
            (action ?: viewModel::startCalendarSync).invoke()
        } else {
            // shouldShowRequestPermissionRationale=false 且未授权 ⇒ 用户已勾「不再询问」
            val activity = context as? android.app.Activity
            val canAskAgain = activity == null ||
                activity.shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR)
            viewModel.onCalendarPermissionDenied(canAskAgain)
        }
    }

    /** 有权限直接执行；没有就先弹系统申请框，授权后继续 [action] */
    fun withCalendarPermission(action: () -> Unit) {
        if (viewModel.hasCalendarPermission()) {
            action()
        } else {
            pendingCalendarAction = action
            calendarPermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            )
        }
    }

    fun startCalendarSync() = withCalendarPermission { viewModel.startCalendarSync() }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            com.buaa.schedule.core.designsystem.GlassTopBar(
                title = section?.title ?: "设置",
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                // 横向留白由 SettingsGroup 自己负责（spaceL），这里不再叠加，
                // 否则会出现 16+16 的双重缩进
                .padding(
                    top = DesignTokens.spaceS,
                    // 手机悬浮玻璃底栏为 overlay 布局：内容延伸到栏体背后滚动，
                    // 底部需要让出"栏体高度 + 系统导航栏"的滚动空隙
                    bottom = DesignTokens.spaceXL + if (bottomBarVisible) {
                        com.buaa.schedule.core.designsystem.floatingBottomBarClearance()
                    } else 0.dp,
                ),
        ) {
            val allSemesters by viewModel.allSemesters.collectAsState()

            // ── 分类入口（根界面）──
            // 设置项太多，平铺一长列既难找也重（11 组会同时组合、含玻璃节点）。
            // 这里只列分类，点进去才是该分类的设置项子界面。
            // 各分组用 visibleWhen 过滤：section == null 时下面所有分组都不渲染。
            if (section == null) {
                // 全部入口放进同一个 SettingsGroup，才会连成一摞（首尾 18dp、中间 6dp 圆角）
                SettingsGroup(title = null) {
                    SettingsSection.entries.forEach { entry ->
                        item(key = "entry_${entry.id}") {
                            SettingsRow(
                                title = entry.title,
                                summary = entry.summary,
                                icon = entry.icon,
                                showChevron = true,
                                onClick = { onOpenSection(entry) },
                            )
                        }
                    }
                }
            }

            SettingsGroup(
                title = "学期设置",
                visibleWhen = section == SettingsSection.SCHEDULE,
                collapsible = true,
                initiallyExpanded = true,
            ) {
            item(key = "semesterForm") {
            Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceS)) {
            OutlinedTextField(
                value = termName,
                onValueChange = { termName = it },
                label = { Text("学期名称（如 2025-2026-1）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = termCode,
                onValueChange = { termCode = it },
                label = { Text("学期代码（教务接口使用）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = startDate,
                onValueChange = { startDate = it },
                label = { Text("开学日期（yyyy-MM-dd，周一）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = totalWeeks,
                onValueChange = { totalWeeks = it },
                label = { Text("总周数") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            // 非法开学日期一旦入库会让周次计算全程崩溃，保存前先校验
            val startDateValid = runCatching { LocalDate.parse(startDate) }.isSuccess
            if (!startDateValid) {
                Text(
                    text = "开学日期格式错误，应为 yyyy-MM-dd（如 2026-09-07）",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Button(
                onClick = {
                    val weeks = CourseConstraints.normalizeTotalWeeks(totalWeeks.toIntOrNull() ?: 20)
                    // 全应用的课次日期都是 startDate.plusWeeks(w-1).plusDays(dow-1)，
                    // 非周一起点会让整张课表偏移且周次编号错位，保存前按自然周归一
                    val parsed = LocalDate.parse(startDate)
                    val monday = com.buaa.schedule.domain.schedule.WeekCalculator.mondayOf(parsed)
                    if (monday != parsed) startDate = monday.toString()
                    viewModel.saveSemester(
                        Semester(
                            id = semester?.id ?: 0L,
                            termCode = termCode.ifBlank { "DEFAULT" },
                            termName = termName.ifBlank { "未命名学期" },
                            startDate = monday.toString(),
                            totalWeeks = weeks,
                        )
                    )
                    if (monday != parsed) viewModel.showMessage("开学日期已按周一对齐为 $monday")
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = startDateValid,
            ) {
                Text("保存学期设置")
            }
            }
            }

            // 多课表：在已导入的学期之间切换"当前学期"
            item(key = "multiSemester", visible = allSemesters.size > 1) {
            val activeTermCode = state.semester?.termCode
            Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceS)) {
                Text(
                    text = "多课表（已导入 ${allSemesters.size} 个学期）",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                allSemesters.forEach { semester ->
                    val isActive = semester.termCode == activeTermCode
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = semester.termName,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "${semester.startDate} · ${semester.totalWeeks} 周",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (isActive) {
                            Text(
                                text = "当前",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            TextButton(onClick = { viewModel.switchSemester(semester.termCode) }) {
                                Text("切换")
                            }
                        }
                    }
                }
            }
            }

            }

            // 编辑期间只改本地草稿，点「保存」才落库；时间格式 HH:mm（零填充），
            // 因此字符串比较等价于时间先后
            val effectiveSlots = remember(state.timeSlots) {
                if (state.timeSlots.isEmpty()) TimeSlotProfile.DEFAULT else state.timeSlots
            }
            var slotDraft by remember(effectiveSlots) { mutableStateOf(effectiveSlots) }
            // 每条节次 2 次正则匹配 × 12 条，每次重组都跑一遍纯属浪费；只在草稿变化时算。
            val hasInvalidSlot = remember(slotDraft) { slotDraft.any { !isValidTimeSlot(it) } }
            val slotsDirty = slotDraft != effectiveSlots

            SettingsGroup(
                title = "节次时间",
                visibleWhen = section == SettingsSection.SCHEDULE,
                collapsible = true,
                initiallyExpanded = true,
            ) {
            item(key = "hint") {
            Text(
                text = "可编辑每节上下课时间（只改时间、不改节次数量，避免与已排课程的节次错位）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            }
            item(key = "smart") {
            Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceS)) {
            // 智慧推算：填 4 个参数即可生成整天的节次时间，之后仍可逐节微调
            var smartFirst by remember { mutableStateOf("08:00") }
            var smartPeriod by remember { mutableStateOf("45") }
            var smartBreak by remember { mutableStateOf("10") }
            var smartLunchAfter by remember { mutableStateOf("4") }
            var smartLunch by remember { mutableStateOf("90") }
            OutlinedTextField(
                value = smartFirst,
                onValueChange = { smartFirst = it },
                label = { Text("首节开始（HH:mm）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = smartPeriod,
                    onValueChange = { smartPeriod = it },
                    label = { Text("每节(分)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = smartBreak,
                    onValueChange = { smartBreak = it },
                    label = { Text("节间(分)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = smartLunchAfter,
                    onValueChange = { smartLunchAfter = it },
                    label = { Text("午休在第几节后") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = smartLunch,
                    onValueChange = { smartLunch = it },
                    label = { Text("午休(分)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            val smartParams = remember(smartFirst, smartPeriod, smartBreak, smartLunchAfter, smartLunch) {
                runCatching {
                    SmartPeriods.Params(
                        firstStart = smartFirst.trim(),
                        periodMinutes = smartPeriod.trim().toIntOrNull() ?: 0,
                        breakMinutes = smartBreak.trim().toIntOrNull() ?: 0,
                        lunchAfterPeriod = smartLunchAfter.trim().toIntOrNull() ?: 0,
                        lunchMinutes = smartLunch.trim().toIntOrNull() ?: 0,
                        count = TimeSlotProfile.DEFAULT.size,
                    )
                }.getOrNull()
            }
            OutlinedButton(
                onClick = {
                    smartParams?.let { params ->
                        SmartPeriods.derive(params).onSuccess { derived ->
                            slotDraft = derived
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = smartParams != null,
            ) { Text("按参数推算节次时间（推算后可再微调）") }
            }
            }
            item(key = "slots") {
            Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceS)) {
            slotDraft.forEachIndexed { index, slot ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "第 ${slot.number} 节",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(60.dp),
                    )
                    OutlinedTextField(
                        value = slot.startTime,
                        onValueChange = { value ->
                            slotDraft = slotDraft.toMutableList().also {
                                it[index] = slot.copy(startTime = value.trim())
                            }
                        },
                        label = { Text("开始") },
                        singleLine = true,
                        isError = !isValidTimeSlot(slot),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = slot.endTime,
                        onValueChange = { value ->
                            slotDraft = slotDraft.toMutableList().also {
                                it[index] = slot.copy(endTime = value.trim())
                            }
                        },
                        label = { Text("结束") },
                        singleLine = true,
                        isError = !isValidTimeSlot(slot),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (hasInvalidSlot) {
                Text(
                    text = "时间需为 HH:mm 且结束晚于开始",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            }
            }
            item(key = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceS)) {
            Button(
                onClick = { viewModel.saveTimeSlots(slotDraft) },
                modifier = Modifier.fillMaxWidth(),
                enabled = slotsDirty && !hasInvalidSlot,
            ) {
                Text("保存节次时间")
            }
            OutlinedButton(
                onClick = { viewModel.saveDefaultTimeSlots() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("恢复北航默认节次")
            }

            importMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (it.startsWith("失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            }
            }
            }

            val widgetManager = remember(context) {
                android.appwidget.AppWidgetManager.getInstance(context)
            }
            SettingsGroup(title = "桌面组件", visibleWhen = section == SettingsSection.WIDGET) {
            item(key = "desc") {
                val widgetCount = remember(widgetManager) {
                    listOf(
                        TodayWidgetProvider::class.java,
                        TomorrowWidgetProvider::class.java,
                        WeekWidgetProvider::class.java,
                        WeekGridWidgetProvider::class.java,
                        NextClassWidgetProvider::class.java,
                    ).sumOf { clazz ->
                        widgetManager.getAppWidgetIds(
                            android.content.ComponentName(context, clazz)
                        ).size
                    }
                }
                Text(
                    text = "当前桌面已添加 $widgetCount 个组件。每个组件都可以单独设置外观：" +
                        "长按桌面上的组件 →「编辑」即可调背景色、不透明度、圆角与文字颜色。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item(key = "pinTitle") {
                Text(
                    text = "一键添加组件",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (widgetManager.isRequestPinAppWidgetSupported) {
                item(key = "pinToday") {
                    PinWidgetRow(
                        label = "今日课程（4×2 列表）",
                        provider = TodayWidgetProvider::class.java,
                        previewLayout = R.layout.widget_preview_today,
                        previewHeight = 150.dp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item(key = "pinTomorrow") {
                    PinWidgetRow(
                        label = "明日课程（4×2 列表）",
                        provider = TomorrowWidgetProvider::class.java,
                        previewLayout = R.layout.widget_preview_tomorrow,
                        previewHeight = 116.dp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item(key = "pinWeek") {
                    PinWidgetRow(
                        label = "本周课表（4×4 列表）",
                        provider = WeekWidgetProvider::class.java,
                        previewLayout = R.layout.widget_preview_week,
                        previewHeight = 150.dp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item(key = "pinWeekGrid") {
                    PinWidgetRow(
                        label = "本周课表（4×2 紧凑网格）",
                        provider = WeekGridWidgetProvider::class.java,
                        previewLayout = R.layout.widget_preview_week_grid,
                        previewHeight = 104.dp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item(key = "pinNext") {
                    PinWidgetRow(
                        label = "下一节课（2×1 极简）",
                        provider = NextClassWidgetProvider::class.java,
                        previewLayout = R.layout.widget_preview_next,
                        previewHeight = 78.dp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                item(key = "pinUnsupported") {
                    Text(
                        text = "当前桌面不支持应用内一键添加，请长按桌面空白处 →「小组件」→ 北航课程表。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(key = "refresh") {
                Button(
                    onClick = { viewModel.refreshWidgetsNow() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("立即刷新全部组件") }
            }
            item(key = "policy") {
                Text(
                    text = "刷新策略：数据变化即时刷新 + 每天零点跨天刷新 + 12 小时兜底轮询。" +
                        "若组件日期长时间不更新，请检查「提醒可靠性」里的精确闹钟权限是否被系统收回。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            }

            SettingsGroup(
                title = "外观",
                visibleWhen = section == SettingsSection.APPEARANCE,
                collapsible = true,
                initiallyExpanded = true,
            ) {
            item(key = "darkMode") {
            Column {
                Text(
                    text = "深色模式",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    com.buaa.schedule.DarkModePreference.entries.forEach { pref ->
                        val selected = darkModePref == pref
                        OutlinedButton(
                            onClick = {
                                darkModePref = pref
                                prefs.edit {
                                    putString(com.buaa.schedule.DarkModePreference.PREF_KEY, pref.name)
                                }
                                onDarkThemeChange(pref)
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !selected,
                        ) { Text(pref.label) }
                    }
                }
            }
            }
            item(key = "dynamicColor") {
                SettingsSwitchRow(
                    title = "Material You 动态取色",
                    summary = if (Build.VERSION.SDK_INT >= 31) {
                        "主题色跟随系统壁纸（Android 12+）"
                    } else {
                        "需要 Android 12 及以上"
                    },
                    checked = Personalization.useDynamicColor,
                    onCheckedChange = {
                        Personalization.useDynamicColor = it
                        Personalization.save(context)
                    },
                    enabled = Build.VERSION.SDK_INT >= 31,
                )
            }
            item(key = "seedColor") {
            Column {
                Text(
                    text = "主题种子色",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "手动选择主题主色；Material You 动态取色开启时优先使用动态色",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val seedOptions = listOf(
                    "默认" to null,
                    "蓝" to 0xFF1B6DE0.toInt(),
                    "绿" to 0xFF2E9E5B.toInt(),
                    "橙" to 0xFFE67E22.toInt(),
                    "紫" to 0xFF7C4DFF.toInt(),
                    "粉" to 0xFFEC6B9A.toInt(),
                    "青" to 0xFF00A8A8.toInt(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    seedOptions.forEach { (label, argb) ->
                        val selected = Personalization.seedColorArgb == argb
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable {
                                Personalization.seedColorArgb = argb
                                Personalization.save(context)
                            },
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(
                                        color = argb?.let { Color(it) } ?: Color.Transparent,
                                        shape = CircleShape,
                                    )
                                    .border(
                                        width = if (selected) 3.dp else 1.dp,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        },
                                        shape = CircleShape,
                                    ),
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
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
            item(key = "weekDensity") {
                Column {
                    Text(
                        text = "周课表高度",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "视口均分行高",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Switch(
                            checked = Personalization.weekFitViewport,
                            onCheckedChange = {
                                Personalization.weekFitViewport = it
                                Personalization.save(context)
                            },
                        )
                    }
                    Slider(
                        value = weekRowScaleDraft,
                        onValueChange = { weekRowScaleDraft = it },
                        onValueChangeFinished = {
                            Personalization.weekRowScale = weekRowScaleDraft
                            Personalization.save(context)
                        },
                        valueRange = Personalization.MIN_WEEK_ROW_SCALE..Personalization.MAX_WEEK_ROW_SCALE,
                        steps = 2,
                    )
                    Text(
                        text = "行高 ${(weekRowScaleDraft * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "课程卡圆角",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Slider(
                        value = weekCornerDraft,
                        onValueChange = { weekCornerDraft = it },
                        onValueChangeFinished = {
                            Personalization.weekCornerRadiusDp = weekCornerDraft
                            Personalization.save(context)
                        },
                        valueRange = Personalization.MIN_WEEK_CORNER..Personalization.MAX_WEEK_CORNER,
                        steps = 3,
                    )
                    Text(
                        text = if (weekCornerDraft <= 0f) {
                            "圆角自动（默认 10dp）"
                        } else {
                            "圆角 ${weekCornerDraft.roundToInt()}dp"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(key = "glassTier") {
            // 注意：SettingsGroup 的每条 item 会被包进 GlassSurface，而 GlassSurface 的
            // 内容容器是 Box —— 多个子控件不包 Column 就会全叠在左上角（文字重叠的根因）。
            Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceS)) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    text = "液态玻璃",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = glassTier >= com.buaa.schedule.core.designsystem.DesignTokens.GLASS_TIER_STANDARD,
                    onCheckedChange = { on ->
                        val tier = if (on) {
                            com.buaa.schedule.core.designsystem.DesignTokens.GLASS_TIER_STANDARD
                        } else {
                            com.buaa.schedule.core.designsystem.DesignTokens.GLASS_TIER_OFF
                        }
                        glassTier = tier
                        Personalization.glassTier = tier
                        Personalization.save(context)
                    },
                )
            }
            Text(
                // 关闭≠完全没有玻璃：小面积那几处（顶栏 / 底栏 / 页签切换）留着最好看，
                // 大面板退化成实心卡片——整屏几十个 AGSL 表面既费电又不如小玻璃通透
                text = "关闭时保留小面积玻璃（顶栏、底栏、页签切换），设置与导入页的大块面板改为实心卡片，更省电也更清晰",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            }
            }
            item(key = "glassPreview") {
            // 同上：预览卡 / 透明度文字 / 滑块三者必须有 Column 才会纵向排列
            Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceS)) {
            // 材质实时预览：随档位与透明度即时变化（CHROME 档含真实背景模糊）
            com.buaa.schedule.core.designsystem.GlassSurface(
                variant = GlassVariant.CHROME,
                alphaOverride = cardAlpha,
                contentPadding = DesignTokens.spaceL,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(DesignTokens.cornerPanel),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    Text(
                        text = "玻璃效果预览",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "高等数学 · 周一 1-2 节 · J3-101",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            // 关闭档仍保留小面积玻璃，cardAlpha 就是那些玻璃的透明度，不该被藏起来
            Text(
                text = "卡片透明度：${(cardAlpha * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = cardAlpha,
                onValueChange = { cardAlpha = it },
                // 拖动期间只动本地草稿：全局 cardAlpha 被 6 处玻璃组件在组合中读取，
                // 每帧写它等于每秒 60 次整树重组（R5 F-22）。松手后一次落全局并持久化。
                onValueChangeFinished = {
                    Personalization.cardAlpha = cardAlpha
                    Personalization.save(context)
                },
                valueRange = 0.3f..1f,
            )
            }
            }
            item(key = "systemWallpaper") {
            // ⚠️ 必须是单个 Column：SettingsGroup 把每条 item 包进 GlassSurface，
            // 而它的内容容器是 Box —— 这里的开关行和下方提示若互为兄弟节点，
            // 就会全部叠在左上角（表现为"提示文字和设置文字重叠"）。
            Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceS)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "使用桌面壁纸",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "未选自定义壁纸时，直接提取系统桌面壁纸做课表背景",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = Personalization.useSystemWallpaper,
                        onCheckedChange = {
                            Personalization.useSystemWallpaper = it
                            Personalization.save(context)
                        },
                    )
                }
                // 评审 P0-1：Android 14（API 34）起平台禁止第三方应用读取桌面壁纸，
                // decodeSystemWallpaper 恒返回 null（见 SceneBackground），背景会静默回退
                // 渐变色。不能让用户以为开关坏了 —— 在设置页显式说明并给出可操作出路。
                if (Build.VERSION.SDK_INT >= 34 && Personalization.useSystemWallpaper &&
                    Personalization.wallpaperUri == null
                ) {
                    Text(
                        text = "提示：Android 14 起系统限制第三方应用读取桌面壁纸，" +
                            "此开关不会生效，背景将回退为渐变色。" +
                            "建议改用下方「选择壁纸图片」手动指定一张图。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            }
            item(key = "wallpaperPick") {
            Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceS)) {
            OutlinedButton(
                onClick = { wallpaperLauncher.launch(arrayOf("image/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("选择壁纸图片")
            }
            if (Personalization.wallpaperUri != null) {
                OutlinedButton(
                    onClick = {
                        Personalization.wallpaperUri = null
                        Personalization.save(context)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("清除壁纸")
                }
            }
            }
            }
            item(key = "wallpaperTuning") {
            if (Personalization.hasWallpaperBackdrop) {
                // 3 个滑块 + 说明 + 恢复按钮必须在 Column 里纵向排列，否则会叠在一起
                Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceS)) {
                // 壁纸调参：改动即生效（SceneBackground 消费这些状态并触发重组）
                Text(
                    text = "壁纸调参（只影响可见背景，玻璃折射仍取原图）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                WallpaperSlider(
                    label = "模糊 ${Personalization.wallpaperBlurDp.roundToInt()}dp",
                    value = Personalization.wallpaperBlurDp,
                    range = Personalization.MIN_BLUR_DP..Personalization.MAX_BLUR_DP,
                    onCommit = { Personalization.save(context) },
                ) { Personalization.wallpaperBlurDp = it }
                WallpaperSlider(
                    label = "亮度 ${(Personalization.wallpaperBrightness * 100).roundToInt()}%",
                    value = Personalization.wallpaperBrightness,
                    range = Personalization.MIN_BRIGHTNESS..Personalization.MAX_BRIGHTNESS,
                    onCommit = { Personalization.save(context) },
                ) { Personalization.wallpaperBrightness = it }
                WallpaperSlider(
                    label = "取景缩放 ${String.format(java.util.Locale.US, "%.2f", Personalization.wallpaperZoom)}x",
                    value = Personalization.wallpaperZoom,
                    range = Personalization.MIN_ZOOM..Personalization.MAX_ZOOM,
                    onCommit = { Personalization.save(context) },
                ) { Personalization.wallpaperZoom = it }
                if (Personalization.hasWallpaperTuning) {
                    TextButton(onClick = { Personalization.resetWallpaperTuning(context) }) {
                        Text("恢复壁纸默认调参")
                    }
                }
                }
            }
            }
            }

            SettingsGroup(
                title = "课程提醒",
                visibleWhen = section == SettingsSection.NOTIFICATION,
                collapsible = true,
                initiallyExpanded = true,
            ) {
            item(key = "enable") {
            Button(
                onClick = {
                    viewModel.rescheduleReminders()
                    if (Build.VERSION.SDK_INT >= 33 &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("开启/更新课程提醒")
            }
            }
            item(key = "mode") {
            Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceS)) {
            Text(
                text = "提醒方式",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        reminderMode = com.buaa.schedule.domain.model.ReminderMode.APP
                        prefs.edit {
                            putString(
                                com.buaa.schedule.domain.model.ReminderMode.PREF_KEY,
                                com.buaa.schedule.domain.model.ReminderMode.APP,
                            )
                        }
                        viewModel.onReminderModeChanged()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = reminderMode != com.buaa.schedule.domain.model.ReminderMode.APP,
                ) { Text("应用内提醒") }
                OutlinedButton(
                    onClick = {
                        reminderMode = com.buaa.schedule.domain.model.ReminderMode.CALENDAR
                        prefs.edit {
                            putString(
                                com.buaa.schedule.domain.model.ReminderMode.PREF_KEY,
                                com.buaa.schedule.domain.model.ReminderMode.CALENDAR,
                            )
                        }
                        viewModel.onReminderModeChanged()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = reminderMode != com.buaa.schedule.domain.model.ReminderMode.CALENDAR,
                ) { Text("系统日历提醒") }
            }
            if (reminderMode == com.buaa.schedule.domain.model.ReminderMode.CALENDAR) {
                Text(
                    text = "应用内不再发提醒，由系统日历的日程提醒负责。请先完成同步，并确认日历应用的通知已开启。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            }
            }
            }

            SettingsGroup(
                title = "明日课程预告",
                visibleWhen = section == SettingsSection.NOTIFICATION,
                collapsible = true,
                initiallyExpanded = true,
            ) {
            item(key = "toggle") {
                var previewEnabled by remember {
                    mutableStateOf(
                        prefs.getBoolean(TomorrowPreviewReceiver.PREF_ENABLED, true)
                    )
                }
                SettingsSwitchRow(
                    title = "每天 22:00 推送明日课程概览",
                    summary = "明天没有课或假期中不会推送",
                    checked = previewEnabled,
                    onCheckedChange = {
                        previewEnabled = it
                        prefs.edit { putBoolean(TomorrowPreviewReceiver.PREF_ENABLED, it) }
                        BackgroundSync.scheduleTomorrowPreview(context)
                    },
                )
            }
            }

            SettingsGroup(
                title = "提醒可靠性",
                visibleWhen = section == SettingsSection.NOTIFICATION,
                collapsible = true,
                initiallyExpanded = true,
            ) {
            item(key = "notif") {
                // 挂 ON_RESUME tick：用户从系统设置开完权限回来必须看到新状态
                // 只看课程提醒渠道——这一行的文案就是"课程提醒无法弹出"，
                // 用聚合语义（所有渠道都关才算关）会在用户只关掉该渠道时报喜不报忧
                val notifOk = remember(permissionResumeTick) {
                    ReminderGuidance.canPostNotifications(
                        context,
                        ReminderNotifications.CHANNEL_COURSE,
                    )
                }
                SettingsRow(
                    title = "通知权限",
                    summary = if (notifOk) "已允许发送通知" else "已被关闭，课程提醒将无法弹出",
                    onClick = {
                        // 未授权时优先弹系统申请框（还能弹就别让用户绕远路）；
                        // 回调里检测到「不再询问」会自动跳转通知设置页
                        if (notifOk) {
                            ReminderGuidance.openNotificationSettings(context)
                        } else {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    trailing = {
                        Text(
                            text = if (notifOk) "正常" else "去开启",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (notifOk) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error,
                        )
                    },
                )
            }
            item(key = "exact") {
                val exactOk = remember(permissionResumeTick) {
                    ReminderGuidance.canScheduleExact(context)
                }
                SettingsRow(
                    title = "精确闹钟",
                    summary = if (exactOk) "提醒将准时触发"
                    else "未授权时提醒可能延迟，桌面组件的跨天刷新也会退化",
                    onClick = { if (!exactOk) ReminderGuidance.openExactAlarmSettings(context) },
                    trailing = {
                        Text(
                            text = if (exactOk) "正常" else "去授权",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (exactOk) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error,
                        )
                    },
                )
            }
            item(key = "promoted") {
                // Android 16+ 才有实况通知概念；三态探针挂 ON_RESUME tick，
                // 用户从系统通知设置改完回来能看到新状态
                val promotedState = remember(permissionResumeTick) {
                    ReminderGuidance.promotedOngoingState(context)
                }
                if (promotedState != null) {
                    SettingsRow(
                        title = "实况通知（超级岛/流体云）",
                        summary = if (promotedState) {
                            "系统允许把课程进行中提升为实况样式"
                        } else {
                            "系统已关闭提升式通知，课程进行中只会显示为普通常驻"
                        },
                        onClick = {
                            if (promotedState == false) {
                                ReminderGuidance.openNotificationSettings(context)
                            }
                        },
                        trailing = {
                            Text(
                                text = if (promotedState) "正常" else "去开启",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (promotedState) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error,
                            )
                        },
                    )
                }
            }
            item(key = "oem") {
                SettingsRow(
                    title = "厂商自启动（保活）",
                    summary = "MIUI/澎湃等系统会拦截后台闹钟，请允许自启动",
                    onClick = { ReminderGuidance.openAutoStartSettings(context) },
                    trailing = {
                        Text(
                            text = "去设置",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                )
            }
            item(key = "battery") {
                SettingsRow(
                    title = "电池优化白名单",
                    summary = "加入白名单可避免提醒被省电策略延迟",
                    onClick = { ReminderGuidance.requestIgnoreBatteryOptimizations(context) },
                    trailing = {
                        Text(
                            text = "去设置",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                )
            }
            item(key = "classProgress") {
                var classProgress by remember {
                    mutableStateOf(
                        prefs.getBoolean(
                            com.buaa.schedule.reminder.ClassProgressReceiver.PREF_CLASS_PROGRESS, true,
                        )
                    )
                }
                SettingsSwitchRow(
                    title = "课程进行中常驻提醒",
                    summary = "上课期间显示一条带倒计时的常驻通知",
                    checked = classProgress,
                    onCheckedChange = {
                        classProgress = it
                        prefs.edit {
                            putBoolean(
                                com.buaa.schedule.reminder.ClassProgressReceiver.PREF_CLASS_PROGRESS, it,
                            )
                        }
                        viewModel.rescheduleReminders()
                    },
                )
            }
            item(key = "dnd") {
                var dndEnabled by remember {
                    mutableStateOf(
                        prefs.getBoolean(
                            com.buaa.schedule.reminder.ClassProgressReceiver.PREF_DND, false,
                        )
                    )
                }
                // 挂 ON_RESUME tick：用户从系统"勿扰访问"授权页回来时必须立刻看到新状态，
                // 否则授权成功了仍一直显示"去授权"，看起来就像功能坏了
                val policyAccess = remember(permissionResumeTick, dndEnabled) {
                    context.getSystemService(android.app.NotificationManager::class.java)
                        ?.isNotificationPolicyAccessGranted == true
                }
                if (!policyAccess) {
                    SettingsRow(
                        title = "上课自动勿扰",
                        summary = "需要先授予「勿扰访问」权限",
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
                                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                        trailing = {
                            Text(
                                text = "去授权",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                } else {
                    SettingsSwitchRow(
                        title = "上课自动勿扰",
                        summary = "上课期间开启勿扰，下课后自动恢复",
                        checked = dndEnabled,
                        onCheckedChange = {
                            dndEnabled = it
                            prefs.edit {
                                putBoolean(
                                    com.buaa.schedule.reminder.ClassProgressReceiver.PREF_DND, it,
                                )
                            }
                            // 关掉开关时，若此刻正处在"上课勿扰"中要立刻恢复原状态；
                            // 否则得等到下课铃才恢复，这段时间整机会一直保持完全静默。
                            if (!it) {
                                com.buaa.schedule.reminder.ClassProgressDnd.restore(context)
                            }
                        },
                    )
                }
            }
            }

            val exportContext = LocalContext.current
            // 注意：这里必须与函数顶部的备份导出 launcher 区分命名。
            // 此前两者同名，内层声明遮蔽了外层，「导出备份」按钮实际写出 WakeUp 格式，
            // 与「导入备份」期望的 BackupData 不匹配 → 备份无法恢复。
            val wakeUpExportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/json"),
            ) { uri ->
                uri?.let { viewModel.exportWakeUpTo(it.toString()) }
            }

            SettingsGroup(
                title = "数据导出",
                visibleWhen = section == SettingsSection.DATA,
                collapsible = true,
                initiallyExpanded = true,
            ) {
            item(key = "exportWakeUp") {
                SettingsRow(
                    title = "导出 WakeUp 兼容 JSON",
                    summary = "把当前课表导出为 WakeUp 课程表可导入的文件（${state.courses.size} 门课）",
                    onClick = {
                        if (state.courses.isEmpty()) {
                            android.widget.Toast.makeText(
                                exportContext, "当前没有可导出的课程", android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            wakeUpExportLauncher.launch("buaa-schedule-wakeup.json")
                        }
                    },
                )
            }
            item(key = "shareText") {
                SettingsRow(
                    title = "复制本周课表（文本）",
                    summary = "生成纯文本周课表，可粘贴到聊天工具",
                    onClick = {
                        val text = com.buaa.schedule.domain.schedule.ScheduleExporters.toWeeklyText(
                            state.courses, state.semester, state.timeSlots, null,
                        )
                        val clipboard = exportContext.getSystemService(Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("课表", text),
                        )
                        android.widget.Toast.makeText(
                            exportContext, "已复制", android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            }
            }

            SettingsGroup(
                title = "系统日历同步",
                visibleWhen = section == SettingsSection.DATA,
                collapsible = true,
                initiallyExpanded = true,
            ) {
            item(key = "hint") {
            Text(
                text = "把每次上课作为日程写入所选日历（增量更新，不后台轮询）。" +
                    "注意：若选择云日历（Google/Exchange 等），课程名、教室、教师会上传云端。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            }
            item(key = "targetCalendar") {
            SettingsRow(
                icon = Icons.Filled.EditCalendar,
                title = "目标日历",
                summary = calendarSync.targetName ?: "未选择",
                showChevron = true,
                onClick = { withCalendarPermission { viewModel.openCalendarPicker() } },
            )
            }
            item(key = "reminderMinutes") {
            OutlinedTextField(
                value = calendarSync.reminderMinutes,
                onValueChange = { viewModel.setCalendarReminderMinutes(it) },
                label = { Text("日历提醒提前分钟") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            }
            item(key = "sync") {
            Button(
                onClick = { startCalendarSync() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !calendarSync.syncing,
            ) {
                Text(if (calendarSync.syncing) "同步中..." else "同步到系统日历")
            }
            }
            item(key = "remove") {
            SettingsRow(
                icon = Icons.Filled.DeleteSweep,
                title = "移除已同步的日程",
                summary = "只删除本应用写入的事件，不影响其他日程",
                showChevron = true,
                onClick = { withCalendarPermission { viewModel.requestRemoveSyncedEvents() } },
                trailing = {
                    Text(
                        text = "移除",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                },
            )
            }
            item(key = "status", visible = calendarSync.message != null) {
            calendarSync.message?.let {
                Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceS)) {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.startsWith("同步失败") || it.startsWith("移除失败") ||
                        it.startsWith("日历权限") || it.startsWith("未授予") ||
                        it.startsWith("没有检索到") || it.startsWith("读取日历")
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                // 「不再询问」后系统申请框不会再弹：唯一出路是系统设置页
                if (calendarSync.permissionPermanentlyDenied) {
                    TextButton(onClick = { ReminderGuidance.openAppDetails(context) }) {
                        Text("去系统设置开启日历权限")
                    }
                }
                }
            }
            }
            }

            SettingsGroup(
                title = "备份与恢复",
                visibleWhen = section == SettingsSection.DATA,
                collapsible = true,
                initiallyExpanded = true,
            ) {
            item(key = "exportBackup") {
            SettingsRow(
                icon = Icons.Filled.Save,
                title = "导出备份",
                summary = "导出为 BackupData JSON，可完整恢复课表、提醒与节次时间",
                showChevron = true,
                onClick = { exportLauncher.launch("buaa-schedule-backup.json") },
            )
            }
            item(key = "exportIcs") {
            SettingsRow(
                icon = Icons.Filled.CalendarMonth,
                title = "导出日历文件（.ics）",
                summary = "每次上课一个日程，可直接导入系统日历",
                showChevron = true,
                onClick = { icsExportLauncher.launch("buaa-schedule.ics") },
            )
            }
            item(key = "import") {
            SettingsRow(
                icon = Icons.Filled.Restore,
                title = "导入备份",
                summary = "从备份文件恢复（先预览确认再写入）",
                showChevron = true,
                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
            )
            }
            }

            val updateSummary = remember(updateState) { describeUpdateState(context, updateState) }

            SettingsGroup(title = "版本", visibleWhen = section == SettingsSection.ABOUT) {
            item(key = "aboutHeader") {
                AboutHeader()
            }
            item(key = "checkUpdate") {
            SettingsRow(
                icon = Icons.Filled.CloudDownload,
                title = "检查更新",
                summary = updateSummary,
                showChevron = true,
                onClick = { settingsScope.launch { UpdateCheck.check(context, force = true) } },
            )
            }
            }

            SettingsGroup(title = "项目与作者", visibleWhen = section == SettingsSection.ABOUT) {
            item(key = "authorGitee") {
            SettingsRow(
                icon = Icons.Filled.Person,
                title = "作者 · Gitee",
                summary = AUTHOR_GITEE_URL.substringAfter("https://"),
                showChevron = true,
                onClick = { openExternalUrl(context, AUTHOR_GITEE_URL) },
            )
            }
            item(key = "authorGithub") {
            SettingsRow(
                icon = Icons.Filled.Face,
                title = "作者 · GitHub",
                summary = AUTHOR_GITHUB_URL.substringAfter("https://"),
                showChevron = true,
                onClick = { openExternalUrl(context, AUTHOR_GITHUB_URL) },
            )
            }
            item(key = "repoGitee") {
            SettingsRow(
                icon = Icons.Filled.Link,
                title = "项目仓库 · Gitee",
                summary = "更新版本在此发布，Issues 也提在这里",
                showChevron = true,
                onClick = { openExternalUrl(context, PROJECT_GITEE_URL) },
            )
            }
            item(key = "repoGithub") {
            SettingsRow(
                icon = Icons.Filled.Code,
                title = "项目仓库 · GitHub",
                summary = "与 Gitee 同步的镜像仓库",
                showChevron = true,
                onClick = { openExternalUrl(context, PROJECT_GITHUB_URL) },
            )
            }
            }

            SettingsGroup(title = "说明", visibleWhen = section == SettingsSection.ABOUT) {
            item(key = "privacy") {
            SettingsRow(
                icon = Icons.Filled.Lock,
                title = "数据与隐私",
                summary = "课表、提醒与节次时间只保存在本机，不上传任何个人数据；" +
                    "登录教务系统仅用于拉取你自己的课表。",
            )
            }
            item(key = "license") {
            SettingsRow(
                icon = Icons.Filled.Gavel,
                title = "开源许可",
                summary = "MIT License · Copyright © 2026 Alyssumira",
            )
            }
            item(key = "updateChannel") {
            SettingsRow(
                icon = Icons.Filled.Info,
                title = "更新方式",
                summary = "每天第一次打开时自动检查一次 Gitee Releases；" +
                    "发现新版本可在应用内下载安装包，也可以跳浏览器去发布页。",
            )
            }
            }
        }
    }

    if (calendarSync.showPicker) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.dismissCalendarPicker() },
            title = { Text("选择目标日历") },
            text = {
                Column {
                    if (calendarSync.calendars.isEmpty()) {
                        // 常见原因：未授予读写权限，或设备上没有可见的日历账户。
                        // 具体的判定（权限/无账户）由 VM 给出，这里只补操作指引。
                        Text(
                            text = (calendarSync.message ?: NO_WRITABLE_CALENDAR_MESSAGE) +
                                "\n\n打开系统「日历」App 登录或添加一个账户" +
                                "（本机离线账户也可以），再回来重试。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        calendarSync.calendars.forEach { info ->
                            Text(
                                text = "${info.displayName}\n${info.accountName}",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.selectCalendarTarget(info.id, info.displayName)
                                    }
                                    .padding(vertical = 10.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { viewModel.dismissCalendarPicker() }) { Text("取消") }
            },
        )
    }

    calendarSync.diff?.let { diff ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.dismissCalendarSyncDiff() },
            title = { Text("确认日历同步") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("新增：${diff.toInsert.size} 个日程")
                    Text("更新：${diff.toUpdate.size} 个日程")
                    Text("删除：${diff.toDelete.size} 个日程")
                    Text("不变：${diff.unchangedCount} 个日程")
                    if (calendarSync.skippedOccurrences > 0) {
                        Text(
                            text = "有 ${calendarSync.skippedOccurrences} 个课次因节次时间缺失被跳过",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    if (reminderMode == com.buaa.schedule.domain.model.ReminderMode.APP) {
                        Text(
                            text = "当前提醒方式为“应用内提醒”，同步后系统日历也可能提醒，可能出现双重通知。如需以日历为准，请先切换提醒方式。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmCalendarSync() },
                ) { Text("同步") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissCalendarSyncDiff() }) { Text("取消") }
            },
        )
    }

    if (calendarSync.showRemoveConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.dismissRemoveSyncedEvents() },
            title = { Text("移除已同步的日程") },
            text = { Text("将删除本应用创建的全部日历事件，不影响课表数据。确定继续？") },
            confirmButton = {
                TextButton(onClick = { viewModel.removeSyncedEvents() }) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissRemoveSyncedEvents() }) { Text("取消") }
            },
        )
    }

    pendingBackup?.let { pending ->
        val preview = pending.preview
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.dismissPendingBackup() },
            title = { Text("恢复备份") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (preview.versionTooNew) {
                        Text(
                            text = "备份来自更新版本的应用，无法恢复。",
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Text("学期：${preview.semesterName ?: "（无）"}")
                        if (preview.startDate != null) {
                            Text("开学日期：${preview.startDate}（共 ${preview.totalWeeks} 周）")
                        }
                        Text("课程：${preview.courseCount} 条（其中手动课程 ${preview.manualCourseCount} 条）")
                        Text(
                            text = if (preview.timeSlotCount > 0) {
                                "节次时间：${preview.timeSlotCount} 条（将覆盖当前设置）"
                            } else {
                                "节次时间：备份中不含，保留当前设置"
                            },
                        )
                        if (preview.reminderCount > 0) {
                            Text("课程提醒：${preview.reminderCount} 条")
                        }
                        Text(
                            text = "恢复规则：备份学期的课程将覆盖当前同学期课程；手动课程只增不删；提醒按课程自动匹配。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.restorePendingBackup() },
                    enabled = !preview.versionTooNew,
                ) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissPendingBackup() }) { Text("取消") }
            },
        )
    }

    // 备份里一条课程都没有：直接恢复等于清空课表，必须再确认一次
    if (pendingEmptyRestore != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.dismissEmptyRestore() },
            title = { Text("该备份不含任何课程") },
            text = {
                Text(
                    text = "继续恢复会清空当前学期的全部课程（节次时间与提醒按备份内容处理）。" +
                        "如果只是拿错了文件，请取消。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmEmptyRestore() },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("仍然恢复") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissEmptyRestore() }) { Text("取消") }
            },
        )
    }
}

/** HH:mm（零填充）。零填充保证字符串比较等价于时间先后。 */
private val TIME_OF_DAY_PATTERN = Regex("^\\d{2}:\\d{2}$")

/**
 * 单条节次时间是否合法：格式必须为 HH:mm，且结束晚于开始。
 *
 * 抽成 internal 顶层函数便于单测 —— 节次时间写坏会让整个课表的时间轴错位。
 */
internal fun isValidTimeSlot(slot: com.buaa.schedule.domain.model.TimeSlot): Boolean =
    TIME_OF_DAY_PATTERN.matches(slot.startTime) &&
        TIME_OF_DAY_PATTERN.matches(slot.endTime) &&
        slot.startTime < slot.endTime

/**
 * 壁纸调参滑块：拖动即时生效，拖完一次性落盘（避免每次 move 都写 prefs）。
 */
@Composable
private fun WallpaperSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onCommit: () -> Unit,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            onValueChangeFinished = onCommit,
            valueRange = range,
        )
    }
}

/**
 * 关于页头部：作者头像 + 应用名 + 版本号。
 *
 * 版本号必须取自 BuildConfig —— 之前这里是写死的 "v0.1.0" 字面量，
 * 改 versionName 不会跟着变，等于每次发版都要手改一处文案。
 */
@Composable
private fun AboutHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = DesignTokens.spaceS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.author_avatar),
            contentDescription = "作者头像",
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape),
        )
        Column(modifier = Modifier.padding(start = DesignTokens.spaceL)) {
            Text(
                text = "北航课程表",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "v${BuildConfig.VERSION_NAME} · Alyssumira",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = "开源、本地优先的北航课表",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 检查更新行的副标题：当前状态 + 上次检查时间 */
private fun describeUpdateState(context: Context, state: UpdateUiState): String {
    val last = formatLastCheck(UpdateCheck.lastCheckAt(context))
    return when (state) {
        is UpdateUiState.Available -> "发现新版本 v${state.info.version}（当前 v${BuildConfig.VERSION_NAME}）"
        is UpdateUiState.Downloading -> if (state.percent >= 0) "下载中 ${state.percent}%" else "准备下载…"
        is UpdateUiState.UpToDate -> "已是最新版本 · 上次检查 $last"
        is UpdateUiState.Failed -> "上次检查失败：${state.message}"
        UpdateUiState.Checking -> "正在检查…"
        UpdateUiState.Idle -> "每天第一次打开自动检查 · 上次检查 $last"
    }
}

private fun formatLastCheck(epochMillis: Long): String =
    if (epochMillis <= 0L) {
        "从未进行"
    } else {
        java.time.Instant.ofEpochMilli(epochMillis)
            .atZone(java.time.ZoneId.systemDefault())
            .format(
                java.time.format.DateTimeFormatter.ofPattern(
                    "M月d日 HH:mm",
                    java.util.Locale.CHINA,
                ),
            )
    }
