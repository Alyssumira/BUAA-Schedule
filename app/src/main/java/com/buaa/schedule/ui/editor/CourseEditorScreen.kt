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
import com.buaa.schedule.core.designsystem.swatchPlaceholderColor
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.CourseSaveOptions
import com.buaa.schedule.domain.model.EffectiveReminderSetting
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
    initialCourse: Course?, requestedCourseId: Long,
    onSave: suspend (Course, CourseSaveOptions) -> Long?,
    onDelete: suspend (Course) -> Boolean,
    onBack: () -> Unit,
    onSaveReminder: (Long, Boolean, Int) -> Unit = { _, _, _ -> },
    initialReminder: ReminderSetting? = null,
    /**
     * 目的地层按 VM 那一枚结论旗算出来的「这一页有一趟写库在飞」（T260）。
     *
     * 它与本页那枚 `saving` 是**两把尺量两件事**：`saving` 长在组合上、转屏归零，
     * 而这一枚读的是跨配置变化存活的状态。写库在飞的那一刻转屏，新 composition 里
     * `saving` 已经是 false —— 只认 `saving` 的那颗按钮就会放开，第二趟 `id = 0L`
     * 起手，库里多出第二门同样的课（台账 #498 那枚「重」正落在这里）。
     */
    saveInFlight: Boolean,
    /**
     * 页面这一支（跑在 `rememberCoroutineScope()` 上）**活着走到了收尾**的报到点（T260）。
     *
     * 它是「收尾所在的 scope 还在不在」的盘上证据：报到执行得到 ⇒ 退栈归这一支，
     * 目的地层据此**不**跟着弹（否则对着 navController 连 pop 两格）；
     * 被组合取消 ⇒ 这一行压根不执行 ⇒ 退栈归目的地层。两枚认领点结构上互斥，
     * 不需要按时序猜「那一支这会儿还在不在」。没有默认值 —— 漏接就等于把这一族放回来。
     */
    onSaveTailReached: () -> Unit,
) {
    // 下面 17 枚草稿字段逐枚带两枚键：位置实参 = 本屏请求的那门课、`key` 前缀 = 同一枚（跨转屏存活、跨课程不串；T348）
    var name by rememberSaveable(requestedCourseId, key = "kn-$requestedCourseId") { mutableStateOf(initialCourse?.name ?: "") }
    var teacher by rememberSaveable(requestedCourseId, key = "kt-$requestedCourseId") { mutableStateOf(initialCourse?.teacher ?: "") }
    var location by rememberSaveable(requestedCourseId, key = "klo-$requestedCourseId") { mutableStateOf(initialCourse?.location ?: "") }
    var campus by rememberSaveable(requestedCourseId, key = "kca-$requestedCourseId") { mutableStateOf(initialCourse?.campus ?: "") }
    var day by rememberSaveable(requestedCourseId, key = "kd-$requestedCourseId") { mutableIntStateOf(initialCourse?.dayOfWeek ?: 1) }
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
    var startSection by rememberSaveable(requestedCourseId, key = "ks-$requestedCourseId") { mutableStateOf(initialStartSection) }
    var endSection by rememberSaveable(requestedCourseId, key = "ke-$requestedCourseId") { mutableStateOf(initialEndSection) }
    var extraPeriods by rememberSaveable(requestedCourseId, key = "kx-$requestedCourseId") { mutableStateOf(initialExtraPeriods) }
    var weeksText by rememberSaveable(requestedCourseId, key = "kw-$requestedCourseId") {
        mutableStateOf(
            initialCourse?.weeks?.let { WeekParser.toDisplayString(it).removeSuffix("周") }
                ?: "1-16"
        )
    }
    var alias by rememberSaveable(requestedCourseId, key = "kal-$requestedCourseId") { mutableStateOf(initialCourse?.alias ?: "") }
    // 学分留空 = 不知道，0 = 教务明说这门课 0 学分，两者在统计页是两个说法，
    // 所以初值只在非 null 时才写成字符串（空串不能被 toDoubleOrNull 吃成 0.0）
    var creditText by rememberSaveable(requestedCourseId, key = "kcr-$requestedCourseId") {
        mutableStateOf(initialCourse?.credit?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: "")
    }
    var colorIndex by rememberSaveable(requestedCourseId, key = "kci-$requestedCourseId") { mutableIntStateOf(initialCourse?.colorIndex ?: 0) }
    var customColor by rememberSaveable(requestedCourseId, key = "kcc-$requestedCourseId") { mutableStateOf(initialCourse?.customColorArgb) }
    var reminderEnabled by rememberSaveable(requestedCourseId, key = "kre-$requestedCourseId") { mutableStateOf(initialReminder?.enabled ?: ReminderSetting.DEFAULT_ENABLED) }
    // 提前量初值 = 那一行里的值；没有那一行时**念得出默认值**（原来这里是一枚裸字面量 "10"，
    // 与 domain 那枚同源却各写各的 —— 枚数册子由 reminder/ReminderDefaultSingleSourceGuardTest 钉）
    var advanceMinutes by rememberSaveable(requestedCourseId, key = "kam-$requestedCourseId") {
        mutableStateOf(initialReminder?.advanceMinutes?.toString() ?: ReminderSetting.DEFAULT_ADVANCE_MINUTES.toString())
    }
    var partialWeeks by rememberSaveable(requestedCourseId, key = "kp-$requestedCourseId") { mutableStateOf(false) }
    var applyToGroup by rememberSaveable(requestedCourseId, key = "kag-$requestedCourseId") { mutableStateOf(false) }
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

    // 字段级校验：本体在 editorFieldVerdict（纯 JVM 内核）。改前这里是六枚就地算好的 Boolean，
    // 闸门却不吃它们 —— 于是这一段注释承诺的"红框和存不存得进去永远同步"从未成立过。
    // 现在 isError、supportingText、顶部汇总横幅、保存闸门四处消费的是同一本红名单。
    // 六条判据逐条与改前同口径（本卡改的是"红不进闸门"，不是任何一格"什么算红"）：
    // 额外节次/学分/提前分钟三格都带 isNotBlank() 前件 —— 留空那一档不判红。
    val fields = editorFieldVerdict(
        startSection = startSection,
        endSection = endSection,
        extraPeriods = extraPeriods,
        weeks = weeks,
        creditText = creditText,
        advanceMinutes = advanceMinutes,
    )

    // 没有节次就没有"这门课几点上"，保存必须禁用（本体在 editorCanSave，单测钉住）
    // T260：`saving` 只管这一棵组合，转屏就归零；写库在飞那一刻转屏时放开这颗按钮的就是
    // `saveInFlight` 那一枚（读的是跨配置存活的 VM 结论旗）。两枚取并，不放宽任何一格判据。
    // T321：红名单一起递进去 —— 改前闸门只吃 weeks/periods，于是"标了红却能保存、
    // 存进去的是归一化之后的另一个值"（台账 #499 = #379 升档）。
    val canSave = editorCanSave(weeks = weeks, periods = periods, saving = saving || saveInFlight,
        redFields = fields.redFields)

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
            credit = CourseConstraints.normalizeCredit(fields.creditNumber),
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
            // T260：收尾第一手是**向 VM 报到**（「这一支活着跑到了这里」）。它是目的地层唯一
            // 能读到「退栈归页面」的证据：这一行执行得到 ⇒ 下面那枚 onBack() 归这一支弹，
            // 目的地层不跟着弹；被组合取消（转屏 / 摘页）⇒ 这一行连同下面的退栈一行都不执行，
            // 而写库那一半早在 VM 的 viewModelScope 里跑完了整趟 ⇒ 由目的地层替这一页弹。
            onSaveTailReached()
            if (saved) {
                onBack()
            } else {
                saveError = "保存失败，请重试；草稿已保留"
            }
            saving = false
        }
    }

    // 中途返回不再静默丢草稿（P2）：顶部箭头与系统返回键都过这道闸。
    // ⚠️ 上面那一句在 T345 之前**不成立**：两扇各写各的，而那颗 BackHandler 的 enabled 里多了半句
    // `!saving` ⇒ 写库那几秒系统返回**直接放行**（不弹确认、草稿当场没了），同屏那颗箭头却照样弹
    // "丢弃确认"—— 同一件事在两扇门上方向相反。现在两扇都调下面这一枚 `requestBack()`，
    // enabled 只决定"这一扇要不要拦下来交给它"，拦下来之后怎么办由同一处说了算。
    // 形状抄 BuaaLoginScreen 那枚 `safeBack()`（全仓唯一另一处"在飞窗＋两扇返回门"，两扇同调一具名 lambda）。
    // 保存成功走的是 onBack() 本身，不经过这里（这一支活着的时候才走得到那一手 ——
    // 它被组合取消时退栈归目的地层，凭据就是上面那一次 `onSaveTailReached()` 报到，T260）；
    // 删除成功压根不在这页退栈 —— 那一档由 MainActivity 编辑器目的地那一侧认领
    // （见删除确认框里那句 ⚠️ 注释，T166）。
    // ⚠️ `saving` 不再参与返回闸（T345 收掉 #383 后半：一枚旗标兼管三件事的那一半）—— 它现在的读者
    // 是底栏那句「保存中...」、删除那颗按钮的 `enabled = !saving` 与 editorCanSave 实参表里那次折并。
    fun requestBack() {
        if (isDraftDirty) showDiscardDialog = true else onBack()
    }
    BackHandler(enabled = isDraftDirty) { requestBack() }

    // 键与写法收口在 courseSharedElementModifier（T52④）；null = 新增课程，那一格没有配对的卡
    val editorSharedModifier = Modifier.courseSharedElementModifier(initialCourse?.id)

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
            // T321：条件改吃内核那本红名单。原来会亮的两档仍然亮（periods 空 ⇒ 开始/结束节次
            // 至少一格红，weeks 空 ⇔ 周次那一格红），加上三枚"红着却能保存"的新档 ——
            // 甲档的代价（看不出为什么按不下去）就是在这里出声的。
            if (fields.anyRed) {
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
                    isError = fields.isRed(EditorRedField.Credit),
                    supportingText = fieldError(
                        fields.isRed(EditorRedField.Credit),
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
                        isError = fields.isRed(EditorRedField.StartSection),
                        supportingText = fieldError(
                            fields.isRed(EditorRedField.StartSection),
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
                        isError = fields.isRed(EditorRedField.EndSection),
                        supportingText = fieldError(
                            fields.isRed(EditorRedField.EndSection),
                            sectionSupportingText(
                                text = endSection,
                                orderReversed = fields.orderReversed,
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
                    isError = fields.isRed(EditorRedField.ExtraPeriods),
                    supportingText = fieldError(
                        fields.isRed(EditorRedField.ExtraPeriods),
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
                    isError = fields.isRed(EditorRedField.Weeks),
                    supportingText = fieldError(
                        fields.isRed(EditorRedField.Weeks),
                        "周次解析为空，可用 1-16 / 1-16单 / 1,3,5",
                    ),
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
                        // 还没选过自定义色：占位点与设置页「默认」那颗同源取色
                        color = customColor?.let { Color(it) } ?: swatchPlaceholderColor(),
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
                        // 「没有那一行」是常态（三条写库链都不建行），所以这句口径必须念给用户听，
                        // 不许只留在日志里 —— 取法与排铃侧同一份，见 editorReminderSectionSummary
                        summary = editorReminderSectionSummary(initialReminder),
                    )
                    OutlinedTextField(
                        value = advanceMinutes,
                        onValueChange = { advanceMinutes = it },
                        label = { Text("提前分钟（0-${CourseConstraints.MAX_ADVANCE_MINUTES}）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = fieldImeOptions(numeric = true, last = true),
                        keyboardActions = doneActions,
                        isError = fields.isRed(EditorRedField.Advance),
                        supportingText = fieldError(
                            fields.isRed(EditorRedField.Advance),
                            "提前量取 0–${CourseConstraints.MAX_ADVANCE_MINUTES} 分钟，" +
                                "留空按默认 ${ReminderSetting.DEFAULT_ADVANCE_MINUTES} 分钟",
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
                    // 页内次动作同样是 M3 的 40 基准（§8，与上方「保存」那颗同一口径）。
                    // 它自己就在滚动流里整宽独占一行，确认对话框是点下去之后才开的，
                    // 不算"对话框内的按钮行"那条例外。
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = DesignTokens.minTouchTarget),
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
                            // 与 performSave() 起手那两行是同一枚仪式（立旗标 + 收回上一句错）：
                            // 底栏那条红条常驻、读者不在删除弹窗里，漏清这一半就会在删课途中
                            // 继续念着上一次保存的失败。形状钉在 CourseEditorSaveErrorClearPairingGuardTest。
                            saveError = null
                            // ⚠️ 删成那一档**故意不在这里退栈**（T166）：这一枚 `scope` 是 `:129` 的
                            // `rememberCoroutineScope()`，绑在这次组合上。删除事务 commit 之后 VM 还要跑
                            // `afterDataChangedInternal()` 才交回结论，这期间新的 `uiState` 一落地，
                            // MainActivity 编辑器目的地那道 `if (!uiState.loading && !courseMissing)` 闸门
                            // 就把这一整棵摘出组合 —— 同一刻这枚 scope 被取消，挂在它后面的 `onBack()`
                            // 一行都不会执行，于是栈顶留着一页渲染成空的编辑器（整屏空白、且因为栈顶不是
                            // topLevel 连底栏都没有）。退栈现在由那条闸门所在的**目的地层**认领。
                            val deleted = onDelete(target)
                            if (!deleted) {
                                // 删不到 ⇒ 课程还在 ⇒ 那道闸门不摘页，这一支活着，红条念得出来
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

/**
 * 「提醒」那一组开关底下那一句。
 *
 * 病灶（#264）：库里**没有那一行**才是常态 —— 教务导入链对 reminders 表只删不建、
 * 备份恢复只带库里已有的那些行、而下面 [saveCourseDraft] 写那一行时包着
 * `if (isEditingExisting)`（新建那一路不写）。于是"没设过"被排铃读成"设了、且开着"，
 * 屏幕上一个字都没说。这里把它说出来：口径照旧（默认开启 + 默认提前量，
 * 两枚常量只有一处字面量，见 [ReminderSetting] 顶部 KDoc），改的是**它必须让用户看得见**。
 *
 * 抽成纯函数是为了 JVM 表驱动单测（`ui/editor/EditorReminderSectionSummaryTest`）：
 * 三档各摆一格，其中"没有行"那一格必须非空 —— 空了就等于这句口径又回到日志里。
 * 已有行时返回 null：那一档用户在开关与提前量两格里看得见，不许多占一行。
 */
internal fun editorReminderSectionSummary(initialReminder: ReminderSetting?): String? {
    val effective = EffectiveReminderSetting.of(initialReminder)
    return if (effective.basis == EffectiveReminderSetting.Basis.NoRowDefaultOn) {
        "还没为这门课单独设过提醒：按默认口径" +
            "${if (effective.enabled) "开启" else "不开"}提醒、提前 ${effective.advanceMinutes} 分钟。" +
            "点保存才会为它写入设置。"
    } else {
        null
    }
}

/**
 * 保存编排（抽成纯函数以便单测）：
 * 1. 调 [onSave] 落库，并拿到**真正写入的那一行 id**；
 * 2. 仅当编辑既有课程时，把提醒写到该行。
 *
 * 第 2 步必须用 [onSave] 的返回值、而不是原课程 id：部分周次编辑会把选中周次
 * 拆成新行，此时原 id 代表的是“剩余周次”的课程，写回去会让用户实际编辑的
 * 课程丢掉提醒设置（history P1）。
 *
 * ⚠️ 第 2 步外面那层 `if (isEditingExisting)` 是「新建的课没有提醒行」这个常态的**第三个出处**
 * （前两个是导入链与恢复链），本卡有意不收它 —— 收了就是给新建课凭空写一行，
 * 属于写库链的行为变更，要另开卡配装机验收。它改变的只是"什么时候才有行"，
 * 不是"没有行时按什么口径"（那一档的口径与用户可见的说明见 [editorReminderSectionSummary]）。
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
                advanceMinutesText.toIntOrNull() ?: ReminderSetting.DEFAULT_ADVANCE_MINUTES
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

