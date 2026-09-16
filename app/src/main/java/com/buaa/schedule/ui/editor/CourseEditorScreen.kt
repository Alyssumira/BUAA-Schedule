package com.buaa.schedule.ui.editor

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.buaa.schedule.core.designsystem.CourseColors
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.core.designsystem.GlassSurface
import com.buaa.schedule.core.designsystem.GlassVariant
import com.buaa.schedule.core.designsystem.LocalAnimatedVisibilityScope
import com.buaa.schedule.core.designsystem.LocalSharedTransitionScope
import com.buaa.schedule.core.designsystem.contentOn
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.CourseSaveOptions
import com.buaa.schedule.domain.model.ReminderSetting
import com.buaa.schedule.domain.schedule.CourseConstraints
import com.buaa.schedule.domain.schedule.WeekParser
import kotlinx.coroutines.launch

/**
 * 课程编辑器：分组玻璃面板（基本信息 / 时间 / 周次 / 外观 / 提醒 / 修改范围）
 * + 固定底部保存栏；保存成功才返回。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseEditorScreen(
    initialCourse: Course?,
    onSave: suspend (Course, CourseSaveOptions) -> Long?,
    onDelete: suspend (Course) -> Boolean,
    onBack: () -> Unit,
    onSaveReminder: (Long, Boolean, Int) -> Unit = { _, _, _ -> },
    initialReminder: ReminderSetting? = null,
) {
    // 草稿字段全部用 rememberSaveable：旋转/进程恢复后不丢输入
    var name by rememberSaveable { mutableStateOf(initialCourse?.name ?: "") }
    var teacher by rememberSaveable { mutableStateOf(initialCourse?.teacher ?: "") }
    var location by rememberSaveable { mutableStateOf(initialCourse?.location ?: "") }
    var campus by rememberSaveable { mutableStateOf(initialCourse?.campus ?: "") }
    var day by rememberSaveable { mutableIntStateOf(initialCourse?.dayOfWeek ?: 1) }
    var startSection by rememberSaveable { mutableStateOf(initialCourse?.startPeriod?.toString() ?: "1") }
    var endSection by rememberSaveable { mutableStateOf(initialCourse?.endPeriod?.toString() ?: "2") }
    var extraPeriods by rememberSaveable { mutableStateOf("") }
    var weeksText by rememberSaveable {
        mutableStateOf(
            initialCourse?.weeks?.let { WeekParser.toDisplayString(it).removeSuffix("周") }
                ?: "1-16"
        )
    }
    var alias by rememberSaveable { mutableStateOf(initialCourse?.alias ?: "") }
    var colorIndex by rememberSaveable { mutableIntStateOf(initialCourse?.colorIndex ?: 0) }
    var customColor by rememberSaveable { mutableStateOf(initialCourse?.customColorArgb) }
    var reminderEnabled by rememberSaveable { mutableStateOf(initialReminder?.enabled ?: true) }
    var advanceMinutes by rememberSaveable { mutableStateOf(initialReminder?.advanceMinutes?.toString() ?: "10") }
    var partialWeeks by rememberSaveable { mutableStateOf(false) }
    var applyToGroup by rememberSaveable { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    val periods = remember(startSection, endSection, extraPeriods) {
        parsePeriods(startSection, endSection, extraPeriods)
    }
    val weeks = remember(weeksText) { WeekParser.parse(weeksText) }
    val canSave = weeks.isNotEmpty() && periods.isNotEmpty() && !saving

    fun performSave() {
        val course = Course(
            id = initialCourse?.id ?: 0L,
            name = name.trim().ifEmpty { "未命名课程" },
            alias = alias.trim().ifEmpty { null },
            teacher = teacher.trim().ifEmpty { null },
            location = location.trim().ifEmpty { null },
            campus = campus.trim().ifEmpty { null },
            dayOfWeek = day,
            periods = periods,
            weeks = weeks,
            colorIndex = colorIndex,
            customColorArgb = customColor,
            sourceGroupKey = initialCourse?.sourceGroupKey,
            semesterCode = initialCourse?.semesterCode,
            isManualOverride = initialCourse?.isManualOverride == true || initialCourse?.sourceGroupKey != null,
        )
        val options = CourseSaveOptions(partialWeeks = partialWeeks, applyToGroup = applyToGroup)
        scope.launch {
            saving = true
            saveError = null
            val saved = saveCourseDraft(
                course = course,
                options = options,
                isEditingExisting = initialCourse != null,
                reminderEnabled = reminderEnabled,
                advanceMinutesText = advanceMinutes,
                onSave = onSave,
                onSaveReminder = onSaveReminder,
            )
            if (saved) {
                onBack()
            } else {
                saveError = "保存失败，请重试；草稿已保留"
            }
            saving = false
        }
    }

    val sharedScope = LocalSharedTransitionScope.current
    val animScope = LocalAnimatedVisibilityScope.current
    val editorSharedModifier = if (initialCourse != null && sharedScope != null && animScope != null) {
        @OptIn(ExperimentalSharedTransitionApi::class)
        with(sharedScope) {
            Modifier.sharedElement(
                sharedContentState = rememberSharedContentState(key = "course_${initialCourse.id}"),
                animatedVisibilityScope = animScope,
            )
        }
    } else {
        Modifier
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().then(editorSharedModifier),
        containerColor = Color.Transparent,
        topBar = {
            com.buaa.schedule.core.designsystem.GlassTopBar(
                title = if (initialCourse == null) "添加课程" else "编辑课程",
                onBack = onBack,
            )
        },
        bottomBar = {
            // 固定底部玻璃操作栏：保存按钮不被键盘或长表单顶走
            GlassSurface(
                variant = GlassVariant.CHROME,
                contentPadding = DesignTokens.spaceM,
                shape = RoundedCornerShape(
                    topStart = DesignTokens.cornerPage,
                    topEnd = DesignTokens.cornerPage,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    if (saveError != null) {
                        Text(
                            text = saveError ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = DesignTokens.spaceS),
                        )
                    }
                    Button(
                        onClick = { performSave() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = canSave,
                    ) {
                        Text(if (saving) "保存中..." else "保存")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = DesignTokens.spaceL),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceM),
        ) {
            if (periods.isEmpty()) {
                Text(
                    text = "节次无效，请检查开始/结束节次与额外节次",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (weeks.isEmpty()) {
                Text(
                    text = "周次解析为空，请检查格式",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            EditorSection(title = "基本信息") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("课程名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text("课程别名（可选，仅改显示）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = teacher,
                    onValueChange = { teacher = it },
                    label = { Text("教师") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("地点") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = campus,
                    onValueChange = { campus = it },
                    label = { Text("校区") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            EditorSection(title = "时间安排") {
                DayDropdown(day = day, onDayChange = { day = it })
                Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.spaceM)) {
                    OutlinedTextField(
                        value = startSection,
                        onValueChange = { startSection = it },
                        label = { Text("开始节次") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = endSection,
                        onValueChange = { endSection = it },
                        label = { Text("结束节次") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }
                OutlinedTextField(
                    value = extraPeriods,
                    onValueChange = { extraPeriods = it },
                    label = { Text("额外节次（可选，如 9-10 或 9,10）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            EditorSection(title = "周次") {
                OutlinedTextField(
                    value = weeksText,
                    onValueChange = { weeksText = it },
                    label = {
                        Text(
                            if (partialWeeks) "本次修改仅作用于这些周次"
                            else "周次（如 1-16 / 1-16单 / 1,3,5）"
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            EditorSection(title = "课程外观") {
                Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.spaceS)) {
                    repeat(8) { index ->
                        ColorDot(
                            color = CourseColors[index],
                            selected = customColor == null && colorIndex == index,
                            onClick = {
                                colorIndex = index
                                customColor = null
                            },
                        )
                    }
                    ColorDot(
                        color = customColor?.let { Color(it) } ?: Color(0xFF9E9E9E),
                        selected = customColor != null,
                        label = "自定义",
                        onClick = { showColorPicker = true },
                    )
                }
            }

            if (initialCourse != null) {
                EditorSection(title = "提醒") {
                    SwitchRow(
                        label = "开启课前提醒",
                        checked = reminderEnabled,
                        onCheckedChange = { reminderEnabled = it },
                    )
                    OutlinedTextField(
                        value = advanceMinutes,
                        onValueChange = { advanceMinutes = it },
                        label = { Text("提前分钟（0-${CourseConstraints.MAX_ADVANCE_MINUTES}）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }

            if (initialCourse != null && initialCourse.weeks.size > 1) {
                EditorSection(title = "修改范围") {
                    SwitchRow(
                        label = "仅修改选中周次（原课程保留其余周次）",
                        checked = partialWeeks,
                        onCheckedChange = { partialWeeks = it },
                    )
                }
            }
            if (initialCourse != null && initialCourse.sourceGroupKey != null) {
                EditorSection(title = "修改范围") {
                    SwitchRow(
                        label = "同步修改本课程其他片段（名称/地点/校区/颜色）",
                        checked = applyToGroup,
                        onCheckedChange = { applyToGroup = it },
                    )
                }
            }

            if (initialCourse != null) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            saving = true
                            if (onDelete(initialCourse)) {
                                onBack()
                            } else {
                                saveError = "删除失败，请重试"
                            }
                            saving = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving,
                ) {
                    Text("删除课程", color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.size(DesignTokens.spaceXL))
            }
        }
    }

    if (showColorPicker) {
        ColorPickerDialog(
            initialColor = customColor,
            onConfirm = { argb ->
                customColor = argb
                showColorPicker = false
            },
            onClear = {
                customColor = null
                showColorPicker = false
            },
            onDismiss = { showColorPicker = false },
        )
    }
}

/** 分组玻璃面板：一个容器 + 组内多个设置项，不逐项套卡 */
@Composable
private fun EditorSection(
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
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 提醒默认提前分钟（与编辑器初始值保持一致） */
private const val DEFAULT_ADVANCE_MINUTES = 10

/**
 * 保存编排（抽成纯函数以便单测）：
 * 1. 调 [onSave] 落库，并拿到**真正写入的那一行 id**；
 * 2. 仅当编辑既有课程时，把提醒写到该行。
 *
 * 第 2 步必须用 [onSave] 的返回值、而不是原课程 id：部分周次编辑会把选中周次
 * 拆成新行，此时原 id 代表的是“剩余周次”的课程，写回去会让用户实际编辑的
 * 课程丢掉提醒设置（history P1）。
 *
 * @return true 表示保存成功，调用方据此退出编辑器
 */
internal suspend fun saveCourseDraft(
    course: Course,
    options: CourseSaveOptions,
    isEditingExisting: Boolean,
    reminderEnabled: Boolean,
    advanceMinutesText: String,
    onSave: suspend (Course, CourseSaveOptions) -> Long?,
    onSaveReminder: (Long, Boolean, Int) -> Unit,
): Boolean {
    val savedId = onSave(course, options) ?: return false
    if (isEditingExisting) {
        onSaveReminder(
            savedId,
            reminderEnabled,
            CourseConstraints.normalizeAdvanceMinutes(
                advanceMinutesText.toIntOrNull() ?: DEFAULT_ADVANCE_MINUTES
            ),
        )
    }
    return true
}

/**
 * 合并主区间与额外节次为非连续节次列表，如 start=1 end=2 extra="9-10" -> [1,2,9,10]。
 * 节次限制在 1..[CourseConstraints.MAX_PERIOD]，输入无效时返回空列表。
 */
internal fun parsePeriods(startText: String, endText: String, extraText: String): List<Int> {
    val start = startText.trim().toIntOrNull() ?: return emptyList()
    val end = endText.trim().toIntOrNull() ?: return emptyList()
    if (start < 1 || end < start || end > CourseConstraints.MAX_PERIOD) return emptyList()
    val base = (start..end).toMutableList()
    val extra = if (extraText.isBlank()) emptyList() else WeekParser.parse(extraText)
    if (extra.any { it < 1 || it > CourseConstraints.MAX_PERIOD }) return emptyList()
    return (base + extra).distinct().sorted()
}

@Composable
private fun ColorPickerDialog(
    initialColor: Long?,
    onConfirm: (Long) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val hsv = remember {
        val argb = initialColor ?: 0xFF5B8DEF
        val floats = FloatArray(3)
        android.graphics.Color.colorToHSV(argb.toInt(), floats)
        floats
    }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var value by remember { mutableFloatStateOf(hsv[2].coerceIn(0.5f, 1f)) }
    val preview = android.graphics.Color.HSVToColor(
        floatArrayOf(hue, 1f, value)
    ).toLong() and 0xFFFFFFFFL

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义颜色") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceM)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .size(48.dp)
                        .background(Color(preview), shape = CircleShape),
                )
                Text("色相：${hue.toInt()}°", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = hue,
                    onValueChange = { hue = it },
                    valueRange = 0f..360f,
                )
                Text("明度：${(value * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    valueRange = 0.5f..1f,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(preview) }) { Text("确定") }
        },
        dismissButton = {
            Row {
                if (initialColor != null) {
                    TextButton(onClick = onClear) { Text("清除") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayDropdown(
    day: Int,
    onDayChange: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = when (day) {
                1 -> "周一"
                2 -> "周二"
                3 -> "周三"
                4 -> "周四"
                5 -> "周五"
                6 -> "周六"
                7 -> "周日"
                else -> "周一"
            },
            onValueChange = {},
            label = { Text("星期") },
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            listOf(1 to "周一", 2 to "周二", 3 to "周三", 4 to "周四", 5 to "周五", 6 to "周六", 7 to "周日")
                .forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onDayChange(value)
                            expanded = false
                        },
                    )
                }
        }
    }
}

/** 色块：48dp 触控区域 + 30dp 内部色块 + 选中勾号 */
@Composable
private fun ColorDot(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    label: String? = null,
) {
    Box(
        modifier = Modifier
            .size(DesignTokens.minTouchTarget)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(color, shape = CircleShape)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = MaterialTheme.colorScheme.onSurface,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected && label == null) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "已选择",
                    tint = contentOn(color),
                    modifier = Modifier.size(18.dp),
                )
            }
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
    }
}
