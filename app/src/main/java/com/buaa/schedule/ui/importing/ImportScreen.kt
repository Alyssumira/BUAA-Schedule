package com.buaa.schedule.ui.importing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.core.designsystem.GlassSurface
import com.buaa.schedule.core.designsystem.GlassVariant
import com.buaa.schedule.core.designsystem.SettingsGroup
import com.buaa.schedule.core.designsystem.SettingsRow
import com.buaa.schedule.domain.schedule.ImportPlanner
import com.buaa.schedule.ui.ScheduleViewModel
import com.buaa.schedule.ui.MAX_IMPORT_BYTES
import com.buaa.schedule.ui.readTextLimited
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SOURCE_NONE = -1
private const val SOURCE_ICS = 1
private const val SOURCE_TEXT = 2
private const val SOURCE_SHARE = 3

/**
 * 导入页。
 *
 * 层级设计（对齐 FolkPatch 的设置页布局与"拼接堆叠"分组）：
 * - **北航教务导入是唯一主入口**，独占一张 Hero 卡（更大图标、更大圆角、主色徽章、
 *   整宽主按钮），不与其他导入方式平级；
 * - 其余导入方式降级为「其他导入方式」拼接组里的行（图标 + 标题 + 说明 + 箭头），
 *   点击行在原地用弹簧展开该来源的输入区，不再用等权重的分段控件；
 * - 解析结果预览与导入历史沿用既有玻璃卡。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    onBack: () -> Unit,
    onStartBuaaLogin: () -> Unit,
    onOpenHistory: () -> Unit = {},
    /** 手机端悬浮玻璃底栏是否显示：显示时滚动内容要在底部让位 */
    bottomBarVisible: Boolean = false,
    viewModel: ScheduleViewModel = viewModel(
        factory = ScheduleViewModel.Factory(LocalContext.current.applicationContext as android.app.Application),
    ),
) {
    val context = LocalContext.current
    var termCode by remember { mutableStateOf("2026-2027-1") }
    // -1 = 未展开任何次要来源；北航不再占用该状态（它有自己的 Hero 区）
    var expandedSource by remember { mutableIntStateOf(SOURCE_NONE) }
    var shareCode by remember { mutableStateOf("") }
    val importMessage by viewModel.importMessage.collectAsState()
    val buaaRefreshing by viewModel.buaaRefreshing.collectAsState()
    val pendingImport by viewModel.pendingImport.collectAsState()
    val importHistory by viewModel.importHistory.collectAsState()
    // 会话状态只用于展示（Hero 上的"已连接/未登录"），不是可观察数据源，
    // 每次重组读一次即可（操作后必然伴随重组）
    val hasBuaaSession = com.buaa.schedule.data.import.BuaaWebSession.hasSession()

    // 「会话失效 → 去登录页」是一次性事件：只有此刻用户正看着导入页，跳过去才是
    // 对他刚才那次操作的回应。以前用的是 StateFlow 标志位，失败的瞬间若人已经在
    // 别的页面，标志会一直留到下次进导入页再触发——那就是"没点导入却自己开始导入"。
    LaunchedEffect(Unit) {
        viewModel.buaaReloginRequests.collect { onStartBuaaLogin() }
    }
    val scope = rememberCoroutineScope()
    val icsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val content = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)
                            ?.use { it.readTextLimited(MAX_IMPORT_BYTES) }
                    }.getOrNull()
                }
                if (content == null) {
                    viewModel.showMessage("ICS 文件过大（上限 8MB）或无法读取")
                } else if (content.isBlank()) {
                    viewModel.showMessage("无法读取 ICS 文件")
                } else {
                    viewModel.importIcs(termCode = termCode.trim(), content = content)
                }
            }
        }
    }
    val textLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val content = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)
                            ?.use { it.readTextLimited(MAX_IMPORT_BYTES) }
                    }.getOrNull()
                }
                if (content == null) {
                    viewModel.showMessage("文本文件过大（上限 8MB）或无法读取")
                } else if (content.isBlank()) {
                    viewModel.showMessage("无法读取文本文件")
                } else {
                    viewModel.importText(termCode = termCode.trim(), content = content)
                }
            }
        }
    }

    fun toggleSource(source: Int) {
        expandedSource = if (expandedSource == source) SOURCE_NONE else source
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            com.buaa.schedule.core.designsystem.GlassTopBar(
                title = "导入",
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
                // 手机悬浮玻璃底栏为 overlay 布局：内容延伸到栏体背后滚动，
                // 底部需要让出"栏体高度 + 系统导航栏"的空隙
                .padding(
                    bottom = DesignTokens.spaceL + if (bottomBarVisible) {
                        com.buaa.schedule.core.designsystem.floatingBottomBarClearance()
                    } else 0.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceS),
        ) {
            // ---- 主入口：北航教务导入（Hero，视觉权重高于所有其他导入方式）----
            BuaaImportHero(
                termCode = termCode,
                onTermCodeChange = { termCode = it },
                hasSession = hasBuaaSession,
                refreshing = buaaRefreshing,
                onLogin = {
                    viewModel.setBuaaTermCode(termCode)
                    viewModel.clearImportMessage()
                    onStartBuaaLogin()
                },
                onRefresh = {
                    if (viewModel.refreshFromBuaa(termCode.trim())) {
                        viewModel.showMessage("正在刷新课表...")
                    } else {
                        viewModel.showMessage("没有保留的登录会话，请先登录导入一次")
                    }
                },
                onCancelRefresh = { viewModel.cancelRefreshFromBuaa() },
                onLogout = {
                    com.buaa.schedule.data.import.BuaaWebSession.clear()
                    viewModel.showMessage("已退出教务登录")
                },
            )

            // ---- 状态提示（成功/失败消息统一在 Hero 下方一条）----
            importMessage?.let { message ->
                val isError = message.contains("失败") || message.contains("未完成") ||
                    message.contains("没有保留") || message.contains("无法")
                GlassSurface(
                    variant = if (isError) GlassVariant.ALERT else GlassVariant.PANEL,
                    semanticTint = if (isError) MaterialTheme.colorScheme.error else null,
                    shape = RoundedCornerShape(DesignTokens.cornerPanel),
                    contentPadding = DesignTokens.spaceM,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DesignTokens.spaceL),
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isError) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }

            // ---- 次要导入方式：拼接堆叠分组，逐行展开输入区 ----
            SettingsGroup(title = "其他导入方式") {
                item(key = "ics") {
                    SettingsRow(
                        icon = Icons.AutoMirrored.Filled.EventNote,
                        title = "ICS 日历文件",
                        summary = "从日历导出的 .ics 导入，支持每周重复事件",
                        showChevron = true,
                        onClick = { toggleSource(SOURCE_ICS) },
                    )
                }
                item(key = "icsInput", visible = expandedSource == SOURCE_ICS) {
                    Button(
                        onClick = { icsLauncher.launch(arrayOf("text/calendar", "text/plain")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("选择 ICS 文件") }
                }

                item(key = "text") {
                    SettingsRow(
                        icon = Icons.Filled.Description,
                        title = "文本课表",
                        summary = "每行一条：课程名,教师,地点,星期,开始节-结束节,周次",
                        showChevron = true,
                        onClick = { toggleSource(SOURCE_TEXT) },
                    )
                }
                item(key = "textInput", visible = expandedSource == SOURCE_TEXT) {
                    Button(
                        onClick = { textLauncher.launch(arrayOf("text/plain")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("选择文本文件") }
                }

                item(key = "share") {
                    SettingsRow(
                        icon = Icons.Filled.Share,
                        title = "课表分享口令",
                        summary = "粘贴同学分享的口令，或把自己的课表分享出去",
                        showChevron = true,
                        onClick = { toggleSource(SOURCE_SHARE) },
                    )
                }
                item(key = "shareInput", visible = expandedSource == SOURCE_SHARE) {
                    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceS)) {
                        OutlinedTextField(
                            value = shareCode,
                            onValueChange = { shareCode = it },
                            label = { Text("粘贴分享口令") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4,
                        )
                        Button(
                            onClick = {
                                viewModel.clearImportMessage()
                                viewModel.importShareCode(shareCode)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = shareCode.isNotBlank(),
                        ) { Text("口令导入") }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val code = viewModel.buildShareCode()
                                    if (code == null) {
                                        viewModel.showMessage("当前没有可分享的课表，请先导入课程")
                                    } else {
                                        val sendIntent = android.content.Intent(
                                            android.content.Intent.ACTION_SEND,
                                        ).apply {
                                            type = "text/plain"
                                            putExtra(android.content.Intent.EXTRA_TEXT, code)
                                        }
                                        context.startActivity(
                                            android.content.Intent.createChooser(sendIntent, "分享课表口令")
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("分享本课表（口令）") }
                    }
                }

                item(key = "history") {
                    SettingsRow(
                        icon = Icons.Filled.History,
                        title = "导入历史",
                        summary = if (importHistory.isEmpty()) {
                            "暂无导入记录"
                        } else {
                            "共 ${importHistory.size} 条记录"
                        },
                        showChevron = true,
                        onClick = onOpenHistory,
                    )
                }
            }

            // ---- 待确认导入预览 ----
            pendingImport?.let { pending ->
                GlassSurface(
                    variant = GlassVariant.PANEL,
                    contentPadding = DesignTokens.spaceL,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DesignTokens.spaceL),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceS)) {
                        Text("待确认导入", style = MaterialTheme.typography.titleMedium)
                        Text("学期：${pending.semester.termName}")
                        Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.spaceL)) {
                            Text("新增 ${pending.addedCount}")
                            Text("更新 ${pending.changedCount}")
                            Text("已有 ${pending.existingCount}")
                        }
                        if (pending.conflicts.isEmpty()) {
                            Text("无时间冲突", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(
                                "存在 ${pending.conflicts.size} 组时间冲突",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            pending.conflicts.take(3).forEach { conflict ->
                                Text(
                                    text = "${conflict.first.name} ↔ ${conflict.second.name}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        pending.warnings.forEach { warning ->
                            Text(
                                text = "⚠ $warning",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        if (pending.keptCount > 0) {
                            Text(
                                text = "另保留 ${pending.keptCount} 门已存在课程（本次不改动）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // 逐条勾选：取消勾选的课程这次导入不包含；
                        // 若它本地已存在，会原样保留（不会被删）
                        val selectedCount = pending.courses.size - pending.excludedKeys.size
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "课程预览（已选 $selectedCount / ${pending.courses.size}）：",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { viewModel.setAllPendingImportSelected(true) }) {
                                Text("全选", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { viewModel.setAllPendingImportSelected(false) }) {
                                Text("全不选", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            pending.courses.forEach { course ->
                                val excluded = ImportPlanner.courseKey(course) in pending.excludedKeys
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.togglePendingImportCourse(course) },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = !excluded,
                                        onCheckedChange = {
                                            viewModel.togglePendingImportCourse(course)
                                        },
                                    )
                                    Text(
                                        text = "周${course.dayOfWeek} " +
                                            "${com.buaa.schedule.domain.model.periodLabel(course.periods)} " +
                                            "${course.name} ${course.location ?: ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (excluded) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                        Button(
                            onClick = viewModel::confirmPendingImport,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = selectedCount > 0,
                        ) { Text("确认导入（$selectedCount 门）") }
                        OutlinedButton(
                            onClick = viewModel::cancelPendingImport,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("取消") }
                    }
                }
            }
        }
    }
}

/**
 * 北航教务导入 Hero。
 *
 * 刻意与「其他导入方式」拉开层级：更大的图标徽章、页面级圆角、
 * 主色"推荐"徽章、整宽主按钮；次要操作（刷新 / 退出）用小号文字按钮收在下方。
 */
@Composable
private fun BuaaImportHero(
    termCode: String,
    onTermCodeChange: (String) -> Unit,
    hasSession: Boolean,
    refreshing: Boolean,
    onLogin: () -> Unit,
    onRefresh: () -> Unit,
    onCancelRefresh: () -> Unit,
    onLogout: () -> Unit,
) {
    GlassSurface(
        variant = GlassVariant.PANEL,
        shape = RoundedCornerShape(DesignTokens.cornerPage),
        contentPadding = DesignTokens.spaceL,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DesignTokens.spaceL),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceM)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 图标徽章：52dp / 28dp 图标，明显大于组内行的 22dp，强化"主入口"
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(16.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.School,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Box(modifier = Modifier.width(DesignTokens.spaceM))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "北航教务导入",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Box(modifier = Modifier.width(DesignTokens.spaceS))
                        HeroBadge("推荐")
                    }
                    Text(
                        text = "统一身份认证登录，自动拉取整学期课表",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 会话状态：让"能不能一键刷新"一眼可见
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = if (hasSession) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            shape = RoundedCornerShape(DesignTokens.cornerPill),
                        ),
                )
                Box(modifier = Modifier.width(DesignTokens.spaceS))
                Text(
                    text = if (hasSession) "已连接教务系统，可直接刷新" else "未登录教务系统",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasSession) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            Button(
                onClick = onLogin,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Login,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Box(modifier = Modifier.width(DesignTokens.spaceS))
                Text("登录教务系统并导入")
            }

            if (refreshing) {
                // 19 周逐周请求在网络差时可能很久
                OutlinedButton(onClick = onCancelRefresh, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Box(modifier = Modifier.width(DesignTokens.spaceS))
                    Text("取消刷新", color = MaterialTheme.colorScheme.error)
                }
            } else {
                OutlinedButton(
                    onClick = onRefresh,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = hasSession,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Box(modifier = Modifier.width(DesignTokens.spaceS))
                    Text("刷新课表（复用登录会话）")
                }
            }

            OutlinedTextField(
                value = termCode,
                onValueChange = onTermCodeChange,
                label = { Text("学期代码") },
                supportingText = { Text("其他导入方式也使用该学期代码") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onLogout) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Logout,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Box(modifier = Modifier.width(DesignTokens.spaceXS))
                    Text("退出教务登录", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** Hero 上的主色小徽章（如"推荐"） */
@Composable
private fun HeroBadge(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                shape = RoundedCornerShape(DesignTokens.cornerPill),
            )
            .padding(horizontal = DesignTokens.spaceS, vertical = 2.dp),
    )
}
