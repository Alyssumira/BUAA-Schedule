package com.buaa.schedule.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import com.buaa.schedule.core.designsystem.ColorSwatch
import com.buaa.schedule.core.designsystem.CourseColors
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.ui.courseSharedElementModifier
import com.buaa.schedule.core.designsystem.GlassSurface
import com.buaa.schedule.core.designsystem.GlassVariant
import com.buaa.schedule.core.designsystem.LocalSemanticPlate
import com.buaa.schedule.core.designsystem.ModalTransition
import com.buaa.schedule.core.designsystem.SettingsSwitchRow
import com.buaa.schedule.core.designsystem.fieldError
import com.buaa.schedule.core.designsystem.fieldImeActions
import com.buaa.schedule.core.designsystem.fieldImeOptions
import com.buaa.schedule.core.designsystem.motionSpec
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.CourseSaveOptions
import com.buaa.schedule.domain.model.NO_PERIOD_GAP
import com.buaa.schedule.domain.model.ReminderSetting
import com.buaa.schedule.domain.model.toPeriodSegments
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
    // 节次按**连续段**回填：此前用 startPeriod(min)/endPeriod(max) 初始化，于是
    // 一门 1-2 + 9-10 的课只改一下老师的名字，保存时 parsePeriods 就会把 1..10
    // 展开成连续块——非连续节次被静默拉直（P0）。
    // 首段进「开始/结束节次」，其余段拼成 parsePeriods 认得的 "9-10" 文本进「额外节次」。
    // 算法本体在 [editorPeriodDraft]：内联在 composable 里 JVM 单测碰不到（同 parsePeriods）。
    val periodDraft = remember(initialCourse) { editorPeriodDraft(initialCourse) }
    val initialStartSection = periodDraft.startSection
    val initialEndSection = periodDraft.endSection
    val initialExtraPeriods = periodDraft.extraPeriods
    // 「这门课本身就没有节次」与「用户把格子清空了」是两件事，提示分得开才不误导
    val courseHadNoPeriods = periodDraft.courseHadNoPeriods
    var startSection by rememberSaveable { mutableStateOf(initialStartSection) }
    var endSection by rememberSaveable { mutableStateOf(initialEndSection) }
    var extraPeriods by rememberSaveable { mutableStateOf(initialExtraPeriods) }
    var weeksText by rememberSaveable {
        mutableStateOf(
            initialCourse?.weeks?.let { WeekParser.toDisplayString(it).removeSuffix("周") }
                ?: "1-16"
        )
    }
    var alias by rememberSaveable { mutableStateOf(initialCourse?.alias ?: "") }
    // 学分留空 = 不知道，0 = 教务明说这门课 0 学分，两者在统计页是两个说法，
    // 所以初值只在非 null 时才写成字符串（空串不能被 toDoubleOrNull 吃成 0.0）
    var creditText by rememberSaveable {
        mutableStateOf(initialCourse?.credit?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: "")
    }
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

    // 脏判定基线：打开那一刻的逐项初值。提醒那两个字段**故意**不在名单里——
    // reminders 是独立的一条流，可能在编辑器首帧之后才到位，纳进来会让"什么都没改"
    // 也被判成有改动、平白多弹一次确认框。
    val baselineDraft = remember(
        initialCourse,
        initialStartSection,
        initialEndSection,
        initialExtraPeriods,
    ) {
        listOf(
            initialCourse?.name ?: "",
            initialCourse?.alias ?: "",
            initialCourse?.teacher ?: "",
            initialCourse?.location ?: "",
            initialCourse?.campus ?: "",
            initialCourse?.dayOfWeek ?: 1,
            initialStartSection,
            initialEndSection,
            initialExtraPeriods,
            initialCourse?.weeks?.let { WeekParser.toDisplayString(it).removeSuffix("周") } ?: "1-16",
            initialCourse?.colorIndex ?: 0,
            initialCourse?.customColorArgb,
            false,
            false,
        )
    }
    val isDraftDirty = listOf(
        name, alias, teacher, location, campus, day,
        startSection, endSection, extraPeriods, weeksText,
        colorIndex, customColor, partialWeeks, applyToGroup,
    ) != baselineDraft
    var showDiscardDialog by remember { mutableStateOf(false) }
    // 删除要过一道确认：管理页的删除是"确认 + 撤销"双保险，编辑器一点即删是同一库里的两种口径（H2）
    var showDeleteDialog by remember { mutableStateOf(false) }

    val periods = remember(startSection, endSection, extraPeriods) {
        parsePeriods(startSection, endSection, extraPeriods)
    }
    val weeks = remember(weeksText) { WeekParser.parse(weeksText) }
    // 没有节次就没有"这门课几点上"，保存必须禁用（本体在 editorCanSave，单测钉住）
    val canSave = editorCanSave(weeks = weeks, periods = periods, saving = saving)

    // 字段级校验：判定与 parsePeriods 一一对应，这样"红框"和"存不存得进去"永远同步，
    // 不会出现标了红却能保存、或者没标红却被拦住。
    val startPeriodNumber = startSection.trim().toIntOrNull()
    val endPeriodNumber = endSection.trim().toIntOrNull()
    val sectionOrderReversed = startPeriodNumber != null && endPeriodNumber != null &&
        endPeriodNumber < startPeriodNumber
    val startSectionInvalid = startPeriodNumber == null ||
        startPeriodNumber !in 1..CourseConstraints.MAX_PERIOD
    val endSectionInvalid = endPeriodNumber == null ||
        endPeriodNumber !in 1..CourseConstraints.MAX_PERIOD ||
        sectionOrderReversed
    // 额外节次是可选的：留空不算错，填了就必须能解析成 1..MAX_PERIOD 的节次
    val extraPeriodsInvalid = extraPeriods.isNotBlank() &&
        WeekParser.parse(extraPeriods).let { list ->
            list.isEmpty() || list.any { it !in 1..CourseConstraints.MAX_PERIOD }
        }
    val weeksInvalid = weeks.isEmpty()
    // 学分可选：留空 = 不知道（统计页会单列"未计入"），填了就必须是 0..MAX_CREDIT 的数值。
    // 坏值判错而不是静默丢掉，否则用户填了 "3,5"（逗号）却以为存进去了。
    val creditNumber = creditText.trim().toDoubleOrNull()
    val creditInvalid = creditText.isNotBlank() &&
        CourseConstraints.normalizeCredit(creditNumber) == null
    // 提前分钟留空是合法的（保存时回退默认值），填了就必须是 0..MAX 的整数。
    // 这一格是全站唯一一个"错了不标红"的字段：填 999 会静默变成 60（审查：表单一致性）
    val advanceInvalid = advanceMinutes.isNotBlank() &&
        (advanceMinutes.trim().toIntOrNull() == null ||
            advanceMinutes.trim().toIntOrNull() !in 0..CourseConstraints.MAX_ADVANCE_MINUTES)

    // 键盘流转：与设置页共用同一套字段声明（审查⑦V-表单）。
    // 表单是一条竖向 Column，所以「下一个」直接用 FocusDirection.Down，
    // 比给 11 个字段各挂一个 focusRequester 少一半代码，也不会漏配。
    val nextFieldOptions = fieldImeOptions()
    val doneFieldOptions = fieldImeOptions(last = true)
    val numberFieldOptions = fieldImeOptions(numeric = true)
    val nextFieldActions = fieldImeActions()
    val doneActions = fieldImeActions(last = true)

    fun performSave() {
        val course = Course(
            id = initialCourse?.id ?: 0L,
            name = name.trim().ifEmpty { "未命名课程" },
            alias = alias.trim().ifEmpty { null },
            teacher = teacher.trim().ifEmpty { null },
            location = location.trim().ifEmpty { null },
            campus = campus.trim().ifEmpty { null },
            credit = CourseConstraints.normalizeCredit(creditNumber),
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

    // 中途返回不再静默丢草稿（P2）：顶部箭头与系统返回键都过这道闸。
    // 保存成功/删除成功走的是 onBack() 本身，不经过这里。
    fun requestBack() {
        if (isDraftDirty) showDiscardDialog = true else onBack()
    }
    BackHandler(enabled = isDraftDirty && !saving) { showDiscardDialog = true }

    // 键与写法收口在 courseSharedElementModifier（T52④）；null = 新增课程，那一格没有配对的卡
    val editorSharedModifier = courseSharedElementModifier(initialCourse?.id)

    Scaffold(
        modifier = Modifier.fillMaxSize().then(editorSharedModifier),
        containerColor = Color.Transparent,
        topBar = {
            com.buaa.schedule.core.designsystem.GlassTopBar(
                title = if (initialCourse == null) "添加课程" else "编辑课程",
                onBack = { requestBack() },
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
                        // 这里不套 GlassSurface(ALERT)：底栏本身就是一层 CHROME 玻璃，
                        // 玻璃叠玻璃既吃配额又糊（同首页冲突横幅的分层规则）。
                        // 用 M3 errorContainer 平板拿到与 ALERT 同源的语义色。
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = DesignTokens.spaceS)
                                .clip(RoundedCornerShape(DesignTokens.cornerChip))
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(horizontal = DesignTokens.spaceM, vertical = DesignTokens.spaceS),
                        ) {
                            Text(
                                text = saveError ?: "",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Button(
                        onClick = { performSave() },
                        // 清单外同类补漏（M4 的同一口径）：页内主动作也是 M3 Button 的
                        // 40dp 默认高，全站这一轮过 48dp 筛，底栏这颗是漏网的
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = DesignTokens.minTouchTarget),
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
            // 详细原因已经落到出错的字段旁边（isError + supportingText），
            // 顶部只留一条汇总——它负责"为什么保存按钮是灰的"，不负责指出是哪一格。
            // 呈现与首页冲突横幅同档（ALERT + error 语义色），不再是一行裸文本。
            if (periods.isEmpty() || weeks.isEmpty()) {
                GlassSurface(
                    variant = GlassVariant.ALERT,
                    semanticTint = MaterialTheme.colorScheme.error,
                    contentPadding = DesignTokens.spaceM,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "还有字段未通过校验，请检查标红的输入框",
                        // 卡位只声明意图（error），底板与文字成对解出
                        color = LocalSemanticPlate.current?.foreground
                            ?: MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            EditorSection(title = "基本信息") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("课程名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = nextFieldOptions,
                    keyboardActions = nextFieldActions,
                )
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text("课程别名（可选，仅改显示）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = nextFieldOptions,
                    keyboardActions = nextFieldActions,
                )
                OutlinedTextField(
                    value = teacher,
                    onValueChange = { teacher = it },
                    label = { Text("教师") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = nextFieldOptions,
                    keyboardActions = nextFieldActions,
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("地点") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = nextFieldOptions,
                    keyboardActions = nextFieldActions,
                )
                OutlinedTextField(
                    value = campus,
                    onValueChange = { campus = it },
                    label = { Text("校区") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = nextFieldOptions,
                    keyboardActions = nextFieldActions,
                )
                // 教务导入会自动带上学分，手动课程以前没有录入口，
                // 统计页就只能一直显示"N 门课没有学分数据，未计入"
                OutlinedTextField(
                    value = creditText,
                    onValueChange = { creditText = it },
                    label = { Text("学分（可选，如 3 或 3.5）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    // Decimal 而不是 Number：小数点要打得出来，"3.5" 是北航最常见的学分
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = nextFieldActions,
                    isError = creditInvalid,
                    supportingText = fieldError(
                        creditInvalid,
                        "学分要是 0–${CourseConstraints.MAX_CREDIT.toInt()} 之间的数字",
                    ),
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
                        keyboardOptions = numberFieldOptions,
                        keyboardActions = nextFieldActions,
                        isError = startSectionInvalid,
                        supportingText = fieldError(
                            startSectionInvalid,
                            sectionSupportingText(
                                text = startSection,
                                orderReversed = false,
                                courseHadNoPeriods = courseHadNoPeriods,
                            ),
                        ),
                    )
                    OutlinedTextField(
                        value = endSection,
                        onValueChange = { endSection = it },
                        label = { Text("结束节次") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = numberFieldOptions,
                        keyboardActions = nextFieldActions,
                        isError = endSectionInvalid,
                        supportingText = fieldError(
                            endSectionInvalid,
                            sectionSupportingText(
                                text = endSection,
                                orderReversed = sectionOrderReversed,
                                courseHadNoPeriods = courseHadNoPeriods,
                            ),
                        ),
                    )
                }
                // 额外节次/周次**故意**不用数字键盘：值里有 '-'、','（乃至「1-16单」的汉字），
                // 数字面板打不出来，为了"看起来统一"而换键盘只会让人无法输入。
                OutlinedTextField(
                    value = extraPeriods,
                    onValueChange = { extraPeriods = it },
                    label = { Text("额外节次（可选，如 9-10 或 9,10）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = nextFieldOptions,
                    keyboardActions = nextFieldActions,
                    isError = extraPeriodsInvalid,
                    supportingText = fieldError(
                        extraPeriodsInvalid,
                        "无法解析，且每一项都要在 1–${CourseConstraints.MAX_PERIOD} 内",
                    ),
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
                    // 新建课程时「提醒」那一组不存在，周次就是最后一个字段——
                    // 这时给"下一个"等于按了没反应，应该是收起键盘。
                    keyboardOptions = if (initialCourse == null) doneFieldOptions else nextFieldOptions,
                    keyboardActions = if (initialCourse == null) doneActions else nextFieldActions,
                    isError = weeksInvalid,
                    supportingText = fieldError(weeksInvalid, "周次解析为空，可用 1-16 / 1-16单 / 1,3,5"),
                )
            }

            EditorSection(title = "课程外观") {
                // 9 颗 48dp 色板 + 8 档间距要 496dp：360dp 屏上最后一颗「自定义」
                // 直接被裁到屏外点不到，而它是唯一能选自己颜色的入口（H1）。
                // 换 FlowRow，与课程管理页的调色板同一套解法。
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(DesignTokens.spaceS),
                ) {
                    CourseColors.forEachIndexed { index, swatch ->
                        ColorSwatch(
                            color = swatch,
                            selected = customColor == null && colorIndex == index,
                            onClick = {
                                colorIndex = index
                                customColor = null
                            },
                        )
                    }
                    ColorSwatch(
                        color = customColor?.let { Color(it) } ?: Color(0xFF9E9E9E),
                        selected = customColor != null,
                        label = "自定义",
                        onClick = { showColorPicker = true },
                    )
                }
            }

            if (initialCourse != null) {
                EditorSection(title = "提醒") {
                    SettingsSwitchRow(
                        title = "开启课前提醒",
                        checked = reminderEnabled,
                        onCheckedChange = { reminderEnabled = it },
                    )
                    OutlinedTextField(
                        value = advanceMinutes,
                        onValueChange = { advanceMinutes = it },
                        label = { Text("提前分钟（0-${CourseConstraints.MAX_ADVANCE_MINUTES}）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = fieldImeOptions(numeric = true, last = true),
                        keyboardActions = doneActions,
                        isError = advanceInvalid,
                        supportingText = fieldError(
                            advanceInvalid,
                            "提前量取 0–${CourseConstraints.MAX_ADVANCE_MINUTES} 分钟，留空按默认",
                        ),
                    )
                }
            }

            val showPartialWeeks = initialCourse != null && initialCourse.weeks.size > 1
            val showGroupSync = initialCourse != null && initialCourse.sourceGroupKey != null
            if (showPartialWeeks || showGroupSync) {
                // 以前这两个条件各配一个标题为「修改范围」的面板，同时成立时同屏出现
                // 两个同名分组，第二个读起来像是重复渲染（M10）。合成一组两问。
                EditorSection(title = "修改范围") {
                    if (showPartialWeeks) {
                        SettingsSwitchRow(
                            title = "仅修改选中周次（原课程保留其余周次）",
                            checked = partialWeeks,
                            onCheckedChange = { partialWeeks = it },
                        )
                    }
                    if (showGroupSync) {
                        SettingsSwitchRow(
                            title = "同步修改本课程其他片段（名称/地点/校区/颜色/学分）",
                            checked = applyToGroup,
                            onCheckedChange = { applyToGroup = it },
                        )
                    }
                }
            }

            if (initialCourse != null) {
                OutlinedButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving,
                ) {
                    Text("删除课程", color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.size(DesignTokens.spaceXL))
            }
        }
    }

    ModalTransition(open = showDiscardDialog) { modal ->
        AlertDialog(
            modifier = modal,
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("放弃修改？") },
            text = { Text("这门课的改动还没有保存，返回后这些输入就没了。") },
            confirmButton = {
                TextButton(onClick = { showDiscardDialog = false; onBack() }) {
                    // 破坏性确认统一 error 字色（§8）：这一步同样是"丢掉东西"，
                    // 以前它和「继续编辑」同色，两颗按钮分不出主次
                    Text("放弃修改", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("继续编辑") }
            },
        )
    }

    ModalTransition(open = showDeleteDialog) { modal ->
        AlertDialog(
            modifier = modal,
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除这门课？") },
            text = {
                Text(
                    "「${initialCourse?.displayName.orEmpty()}」的排课与课前提醒会一起删掉。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        val target = initialCourse ?: return@TextButton
                        scope.launch {
                            saving = true
                            if (onDelete(target)) {
                                onBack()
                            } else {
                                saveError = "删除失败，请重试"
                            }
                            saving = false
                        }
                    },
                ) { Text("删除课程", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            },
        )
    }

    ModalTransition(open = showColorPicker) { modal ->
        ColorPickerDialog(
            initialColor = customColor,
            modifier = modal,
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
        // material3 自己不动画 supportingText 的出现，字段报错时整块面板在这里平滑长高
        Column(
            modifier = Modifier.animateContentSize(motionSpec<IntSize>()),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceM),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
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

/** 编辑器「时间安排」那一组里三格节次字段的回填值，外加一句"这门课本来有没有节次"。 */
internal data class EditorPeriodDraft(
    val startSection: String,
    val endSection: String,
    val extraPeriods: String,
    /** true = 打开的这门课 `periods` 为空。与"用户自己把格子清空"分得开（提示口径不同）。 */
    val courseHadNoPeriods: Boolean,
)

/**
 * 把一门课（或"新建"）回填成编辑器里的节次三格。
 *
 * 这里是**编辑器**的分段，不是课次：改的是节次本身，按节次号相邻切才对。
 * 若套上时间表把 [5,6] 拆成两段，用户只是改个老师名字就会把这门课存成两段。
 *
 * **两条"没有节次"要分开**：
 * - `initialCourse == null`（新建）：给 1-2 节是"用户的起点"，此时库里还没有任何一行
 *   会被这句话篡改，照旧保留。
 * - 已存在的课 `periods` 为空（备份/分享口令里的 `"periods":[]`、被改写的 .db 行）：
 *   两格回填**空串**。以前这里写的是 `?: 1` / `?: 2`，于是打开编辑器就等于先把
 *   「开始节次 1 / 结束节次 2」填进格子，而 `parsePeriods("1","2","")` 非空恰好把
 *   `editorCanSave` 的 `periods.isNotEmpty()` 喂满——用户只是改个教师名字点保存，
 *   这门课就凭空多出第 1-2 节（08:00 上课）并写进数据库。
 *   空串 → 解析为空 → 保存禁用 + 字段判红，要存就得自己明确选一节。
 */
internal fun editorPeriodDraft(initialCourse: Course?): EditorPeriodDraft {
    if (initialCourse == null) {
        return EditorPeriodDraft(
            startSection = "1",
            endSection = "2",
            extraPeriods = "",
            courseHadNoPeriods = false,
        )
    }
    val segments = initialCourse.periods.toPeriodSegments(NO_PERIOD_GAP)
    val first = segments.firstOrNull()
    return EditorPeriodDraft(
        startSection = first?.first?.toString() ?: "",
        endSection = first?.last?.toString() ?: "",
        extraPeriods = segments.drop(1).joinToString(",") { segment ->
            if (segment.first == segment.last) "${segment.first}"
            else "${segment.first}-${segment.last}"
        },
        courseHadNoPeriods = first == null,
    )
}

/**
 * 保存闸门。`periods.isNotEmpty()` 这一项不许放宽：节次解析为空时点保存
 * 会写出一门没有节次的课（或被解析器吞掉），而这两条都该由界面先拦住。
 */
internal fun editorCanSave(weeks: List<Int>, periods: List<Int>, saving: Boolean): Boolean =
    weeks.isNotEmpty() && periods.isNotEmpty() && !saving

/**
 * 「开始/结束节次」两格的 supportingText 措辞（[fieldError] 只在判红时才用它）。
 *
 * 三种"说不通"要分得开：顺序反了说顺序；**这门课根本没有节次**要说"还没有节次"，
 * 而不是用一句格式提示暗示"填个 1 就行"；其余（用户清空、填了非数字、越界）
 * 才是格式口径。第三分支只在格子上还空着时成立——用户已经动手填了就按格式说。
 */
internal fun sectionSupportingText(
    text: String,
    orderReversed: Boolean,
    courseHadNoPeriods: Boolean,
): String = when {
    orderReversed -> "结束需晚于开始"
    courseHadNoPeriods && text.isBlank() -> "这门课还没有节次，请先选一节"
    else -> "节次范围 1–${CourseConstraints.MAX_PERIOD}"
}

@Composable
private fun ColorPickerDialog(
    initialColor: Long?,
    modifier: Modifier = Modifier,
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
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text("自定义颜色") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceM)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .size(DesignTokens.minTouchTarget)
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

