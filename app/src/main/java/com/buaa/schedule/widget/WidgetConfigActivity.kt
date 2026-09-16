package com.buaa.schedule.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.buaa.schedule.DarkModePreference
import com.buaa.schedule.data.repository.ScheduleRepository
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.core.designsystem.BUAAScheduleTheme
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.core.designsystem.GlassSurface
import com.buaa.schedule.core.designsystem.GlassVariant
import com.buaa.schedule.core.designsystem.LocalSceneBackdrop
import com.buaa.schedule.core.designsystem.SceneBackground
import com.buaa.schedule.core.designsystem.contentOn
import com.buaa.schedule.core.designsystem.rememberSceneBackdrop

/**
 * 桌面组件外观配置页。
 *
 * 由 Launcher 在「拖放组件到桌面」或「长按组件 → 编辑」时调用：
 * intent 里带 `AppWidgetManager.EXTRA_APPWIDGET_ID`，配置结果通过
 * `setResult(RESULT_OK, intent)` 回传；返回 RESULT_CANCELED 表示放弃添加。
 *
 * 只调 RemoteViews 真正支持的属性（见 [WidgetAppearance]），因此这里的预览
 * 与桌面上的实际效果一一对应。
 */
class WidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            // 不是由 Launcher 配置流程拉起：没有实例可配置，直接退出
            finish()
            return
        }

        val darkTheme = resolveDarkTheme(this)
        // 这一次 binder 查询留在主线程是有意的：它决定第一帧画哪种组件预览
        // （列表 / 周网格 / 2x1 下一节），挪到后台只会让首帧先画错再重画。
        // R5 F-25 要治的是"每次广播 5 个 Provider 各查一遍"和保存路径，不在这行。
        val providerClassName = AppWidgetManager.getInstance(this)
            .getAppWidgetInfo(appWidgetId)
            ?.provider
            ?.className
        val mode = WidgetCommon.modeOfProvider(providerClassName) ?: ListWidgetMode.TODAY
        val initial = WidgetAppearanceStore.load(this, appWidgetId)
        val initialBinding = WidgetBindingStore.load(this, appWidgetId)
        val repository = (application as? com.buaa.schedule.BUAAApplication)?.repository
            ?: ScheduleRepository(com.buaa.schedule.data.local.AppDatabase.getInstance(application))

        setContent {
            BUAAScheduleTheme(darkTheme = darkTheme) {
                // 与主界面一致的场景背景 + 玻璃材质（无 backdrop 时 GlassSurface 自动降级）
                val backdrop = rememberSceneBackdrop()
                Box(modifier = Modifier.fillMaxSize()) {
                    SceneBackground(darkTheme = darkTheme, backdrop = backdrop)
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalSceneBackdrop provides backdrop
                    ) {
                        WidgetConfigScreen(
                            initial = initial,
                            initialBinding = initialBinding,
                            repository = repository,
                            previewTitle = sampleTitle(mode),
                            previewBody = sampleBody(mode),
                            onCancel = {
                                setResult(RESULT_CANCELED)
                                finish()
                            },
                            onSave = { appearance, binding ->
                                // 落盘 + 补注册 + 重绘整体挪到后台协程：这些原先都压在
                                // 点击后的主线程上（含 hasAnyWidget 的多次 binder 往返），
                                // 而此刻正要跑窗口退出动画。协程持有 applicationContext，
                                // 配置页 finish 之后照样会跑完（R5 F-25）。
                                WidgetCommon.saveConfigAndRefresh(
                                    this@WidgetConfigActivity,
                                    appWidgetId,
                                    appearance,
                                    binding,
                                    providerClassName,
                                    mode,
                                )
                                setResult(
                                    RESULT_OK,
                                    android.content.Intent().putExtra(
                                        AppWidgetManager.EXTRA_APPWIDGET_ID,
                                        appWidgetId,
                                    ),
                                )
                                finish()
                            },
                        )
                    }
                }
            }
        }
    }

    /** 跟随应用内的深色偏好（与 MainActivity 共用同一份 prefs / 枚举） */
    private fun resolveDarkTheme(context: Context): Boolean {
        val prefs = context.getSharedPreferences("schedule_settings", Context.MODE_PRIVATE)
        return when (DarkModePreference.load(prefs)) {
            DarkModePreference.LIGHT -> false
            DarkModePreference.DARK -> true
            DarkModePreference.FOLLOW_SYSTEM ->
                (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
        }
    }

    private fun sampleTitle(mode: ListWidgetMode): String = when (mode) {
        ListWidgetMode.TODAY -> "今日课程"
        ListWidgetMode.TOMORROW -> "明日课程"
        ListWidgetMode.WEEK -> "第 3 周课表"
    }

    private fun sampleBody(mode: ListWidgetMode): String = when (mode) {
        ListWidgetMode.TODAY -> "高等数学 J3-101 第1-2节\n线性代数 SH3-103 第3-5节"
        ListWidgetMode.TOMORROW -> "工程图学(1) J5-210 第3-4节"
        ListWidgetMode.WEEK -> "周一 高数(1-2) 线代(3-5)\n周二 工图(3-4)\n周三 体育(6-7)"
    }
}

@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
private fun WidgetConfigScreen(
    initial: WidgetAppearance,
    initialBinding: WidgetBinding,
    repository: ScheduleRepository,
    previewTitle: String,
    previewBody: String,
    onCancel: () -> Unit,
    onSave: (WidgetAppearance, WidgetBinding) -> Unit,
) {
    var appearance by remember { mutableStateOf(initial) }
    var binding by remember { mutableStateOf(initialBinding) }
    var semesters by remember { mutableStateOf<List<Semester>>(emptyList()) }
    LaunchedEffect(repository) {
        semesters = repository.getAllSemesters()
    }
    val isDefault = appearance == WidgetAppearance()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("桌面组件外观") },
                navigationIcon = {
                    TextButton(onClick = onCancel) { Text("返回") }
                },
            )
        },
        bottomBar = {
            GlassSurface(
                variant = GlassVariant.CHROME,
                contentPadding = DesignTokens.spaceM,
                shape = RoundedCornerShape(
                    topStart = DesignTokens.cornerPage,
                    topEnd = DesignTokens.cornerPage,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.spaceM)) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                    ) { Text("取消") }
                    Button(
                        onClick = { onSave(appearance, binding) },
                        modifier = Modifier.weight(1f),
                    ) { Text("保存") }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DesignTokens.spaceL),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceM),
        ) {
            // 精确闹钟被拒（Android 14 起默认拒绝）时，组件的零点跨天刷新会退化。
            // 组件本身不需要运行时权限，但这个前置条件坏掉用户只会觉得"组件不准"，
            // 在配置页直接给出诊断 + 跳转，比让用户翻设置页快得多。
            val configContext = androidx.compose.ui.platform.LocalContext.current
            val exactAlarmOk = remember {
                com.buaa.schedule.ui.settings.ReminderGuidance.canScheduleExact(configContext)
            }
            if (!exactAlarmOk) {
                Panel(title = "提示") {
                    Text(
                        text = "系统未授予「精确闹钟」权限（Android 14 起默认拒绝），" +
                            "桌面组件的日期跨天刷新可能不准。点击下方按钮前往授权。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(
                        onClick = {
                            com.buaa.schedule.ui.settings.ReminderGuidance
                                .openExactAlarmSettings(configContext)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("去授权精确闹钟") }
                }
            }

            Panel(title = "预览") {
                WidgetPreview(appearance = appearance, title = previewTitle, body = previewBody)
                Text(
                    text = "背景色与透明度请按桌面壁纸实际效果微调；预览按中灰背景折算。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 一键样式预设（SleepDown/Sleepy 的"主题"思路）：
            // 快路径先行——大部分用户不想逐项调参，点一个预设即得完整观感，
            // 预览与下方所有微调项实时联动，微调后仍可继续保存。
            Panel(title = "样式预设") {
                Text(
                    text = "点一个预设直接套用整套外观（配色、透明度、圆角、文字颜色），下方仍可逐项微调。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(DesignTokens.spaceS),
                    verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceXS),
                ) {
                    WidgetAppearance.STYLE_PRESETS.forEach { preset ->
                        FilterChip(
                            selected = appearance == preset.appearance,
                            onClick = { appearance = preset.appearance },
                            label = { Text(preset.label) },
                        )
                    }
                }
            }

            Panel(title = "课表绑定") {
                Text(
                    text = "选择该组件固定显示的学期；选“跟随当前学期”则与 App 内当前学期一致。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = binding.semesterCode == null,
                        onClick = { binding = WidgetBinding(null) },
                        label = { Text("跟随当前学期") },
                    )
                    semesters.forEach { semester ->
                        FilterChip(
                            selected = binding.semesterCode == semester.termCode,
                            onClick = { binding = WidgetBinding(semester.termCode) },
                            label = { Text(semester.termName.ifBlank { semester.termCode }) },
                        )
                    }
                }
            }

            Panel(title = "配色来源") {
                ChipRow(
                    items = WidgetAppearance.COLOR_MODE_LABELS,
                    selectedIndex = appearance.colorMode,
                    onSelect = { appearance = appearance.copy(colorMode = it) },
                )
                Text(
                    text = if (appearance.colorMode == WidgetAppearance.COLOR_MODE_SYSTEM) {
                        "跟随 Material You：从桌面壁纸提取颜色，随系统深浅色自动适配（Android 12+）。"
                    } else {
                        "使用下面自定义的背景色。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (appearance.colorMode == WidgetAppearance.COLOR_MODE_CUSTOM) {
                Panel(title = "背景色") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(DesignTokens.spaceS),
                    ) {
                        WidgetAppearance.PRESET_COLORS.take(4).forEach { (argb, label) ->
                            ColorSwatch(
                                argb = argb,
                                label = label,
                                selected = appearance.backgroundColor == argb,
                                onClick = { appearance = appearance.copy(backgroundColor = argb) },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(DesignTokens.spaceS),
                    ) {
                        WidgetAppearance.PRESET_COLORS.drop(4).forEach { (argb, label) ->
                            ColorSwatch(
                                argb = argb,
                                label = label,
                                selected = appearance.backgroundColor == argb,
                                onClick = { appearance = appearance.copy(backgroundColor = argb) },
                            )
                        }
                    }
                }
            }

            Panel(title = "背景不透明度 ${appearance.alphaPercent}%") {
                Slider(
                    value = appearance.alphaPercent.toFloat(),
                    onValueChange = { appearance = appearance.copy(alphaPercent = it.toInt()) },
                    valueRange = 0f..100f,
                )
                Text(
                    text = "0% 完全透出壁纸，100% 完全遮挡。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Panel(title = "圆角") {
                ChipRow(
                    items = WidgetAppearance.CORNER_LABELS,
                    selectedIndex = appearance.cornerBucket,
                    onSelect = { appearance = appearance.copy(cornerBucket = it) },
                )
            }

            Panel(title = "文字颜色") {
                ChipRow(
                    items = WidgetAppearance.TEXT_MODE_LABELS,
                    selectedIndex = appearance.textMode,
                    onSelect = { appearance = appearance.copy(textMode = it) },
                )
                Text(
                    text = "「自动」按背景亮度选用黑/白文字，保证对比度。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Panel(title = "内容") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "玻璃感壁纸背景",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = appearance.blurBackground,
                        onCheckedChange = { appearance = appearance.copy(blurBackground = it) },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "显示标题行",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = appearance.showTitle,
                        onCheckedChange = { appearance = appearance.copy(showTitle = it) },
                    )
                }
            }

            OutlinedButton(
                onClick = { appearance = WidgetAppearance() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isDefault,
            ) { Text("恢复默认外观") }

            Spacer(modifier = Modifier.height(DesignTokens.spaceXL))
        }
    }
}

/** 所见即所得预览：与实际 RemoteViews 用同一套颜色/透明度/圆角数值 */
@Composable
private fun WidgetPreview(
    appearance: WidgetAppearance,
    title: String,
    body: String,
) {
    val radius = WidgetAppearance.CORNER_RADII_DP[
        appearance.cornerBucket.coerceIn(0, WidgetAppearance.CORNER_RADII_DP.lastIndex)
    ].dp
    // 跟随系统取色时，预览也要解析真实的 Material You 动态色
    val baseColor = if (appearance.colorMode == WidgetAppearance.COLOR_MODE_SYSTEM) {
        resolveSystemWidgetColor(androidx.compose.ui.platform.LocalContext.current)
            ?: appearance.backgroundColor
    } else {
        appearance.backgroundColor
    }
    // 预览必须带透明度，否则用户看不到 alpha 的真实效果
    val bg = Color(baseColor.toLong()).copy(alpha = appearance.alphaFraction)
    // 预览底：中灰代表壁纸，背景层按配置透明度叠上去
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .background(
                color = Color(0xFF8A8F99),
                shape = RoundedCornerShape(DesignTokens.cornerCourse),
            )
            .padding(DesignTokens.spaceM),
    ) {
        Row(
            modifier = Modifier
                .width(300.dp)
                .height(96.dp)
                .background(color = bg, shape = RoundedCornerShape(radius))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(radius))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                if (appearance.showTitle) {
                    Text(
                        text = title,
                        color = Color(appearance.titleColorFor(baseColor).toLong()),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Text(
                    text = body,
                    color = Color(appearance.bodyColorFor(baseColor).toLong()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Text(
            text = "壁纸",
            color = contentOn(Color(0xFF8A8F99)),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}

@Composable
private fun Panel(
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    GlassSurface(
        variant = GlassVariant.PANEL,
        contentPadding = DesignTokens.spaceL,
        shape = RoundedCornerShape(DesignTokens.cornerPanel),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceM)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
private fun ChipRow(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DesignTokens.spaceS),
    ) {
        items.forEachIndexed { index, label ->
            FilterChip(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                label = { Text(label) },
            )
        }
    }
}

/** 色块：48dp 触控区 + 30dp 色块 + 选中勾号（与课程编辑页保持一致） */
@Composable
private fun ColorSwatch(
    argb: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(DesignTokens.minTouchTarget)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(Color(argb.toLong()), shape = CircleShape)
                    .border(
                        width = if (selected) 3.dp else 1.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "已选择",
                        tint = contentOn(Color(argb.toLong())),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
