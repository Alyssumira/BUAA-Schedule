package com.buaa.schedule.ui.course

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.buaa.schedule.core.designsystem.ColorSwatch
import com.buaa.schedule.core.designsystem.CourseColors
import com.buaa.schedule.ui.courseSharedElementModifier
import com.buaa.schedule.core.designsystem.ModalTransition
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.core.designsystem.EmptyState
import com.buaa.schedule.core.designsystem.GlassSurface
import com.buaa.schedule.core.designsystem.GlassVariant
import com.buaa.schedule.core.designsystem.contentOn
import com.buaa.schedule.core.designsystem.motionSpec
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.CourseSaveOptions
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.creditLabel
import com.buaa.schedule.domain.model.joinMeta
import com.buaa.schedule.domain.model.periodLabelOf
import com.buaa.schedule.domain.model.unwrappedPeriodLabel
import com.buaa.schedule.domain.model.weekdayLabel
import com.buaa.schedule.domain.schedule.GroupDeletionRequest
import com.buaa.schedule.domain.schedule.GroupDeletionScope
import com.buaa.schedule.domain.schedule.SemesterStats
import com.buaa.schedule.domain.schedule.courseFragmentMatchesQuery
import com.buaa.schedule.domain.schedule.courseGroupDeletionScope
import com.buaa.schedule.domain.schedule.normalizedCourseQuery
import com.buaa.schedule.ui.ScheduleViewModel
import kotlinx.coroutines.launch

/**
 * 课表管理总览页：按"同一门课"归并展示全部排课片段，提供 整门课改色 / 整门课删除 / 进入编辑器。
 *
 * 为什么需要这页：课表网格上同一门课拆出的多个片段（如 1-8 周与 9-16 周各一段）
 * 分散在不同位置，用户很难意识到它们是同一门课，也改不到"整门课的颜色"。
 *
 * 「一门课」这把尺本页**不自己定义**：归并发生在 [SemesterStats.creditsByCourse]
 * （见 [groupCourses]，T224 换源）。这一页从前带着全仓第二套定义
 * （`manual:名称|星期|节次`），于是同一批数据在管理页页头念「2 门课」、在统计页念「1 门课 · 2 段排课」，
 * 而那句「删除整门课」删完之后的那个下午，统计页那门课照旧在列。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseManagementScreen(
    viewModel: ScheduleViewModel,
    onBack: () -> Unit,
    onEditCourse: (Course) -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<CourseGroup?>(null) }
    var colorTargetKey by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    val groups = remember(state.courses, query) { groupCourses(state.courses, query) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            com.buaa.schedule.core.designsystem.GlassTopBar(
                title = "课表管理（${groups.size} 门课）",
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = DesignTokens.spaceL),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceM),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("搜索课程 / 教师") },
                modifier = Modifier.fillMaxWidth(),
                // 结果是边打字边过滤的，「搜索」没有额外动作可做——收掉键盘，把列表让出来
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            )

            // 空态 ↔ 列表不做硬切：搜索边打字边过滤，两种状态会高频互切，
            // 用 Crossfade 收敛（与日视图空态同一件写法）
            Crossfade(
                targetState = groups.isEmpty(),
                animationSpec = motionSpec<Float>(),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { isEmpty ->
                if (isEmpty) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        if (state.courses.isEmpty()) {
                            EmptyState(
                                icon = Icons.Filled.School,
                                title = "还没有课程",
                                description = "去首页新增一门课，或者导入一份教务课表。",
                            )
                        } else {
                            EmptyState(
                                icon = Icons.Filled.Search,
                                title = "没有匹配「$query」的课程",
                                description = "换个课程名或教师名试试，清空搜索框就能看到全部。",
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceM),
                    ) {
                        items(
                            groups,
                            key = { it.key },
                            // 这一枚容器混排两种形状：组卡（N 枚）与最末那枚垫高的 Spacer（1 枚）。
                            // 不给 contentType 时两者共用一桶，滚出视口的 Spacer 槽位可能被派去渲染
                            // 组卡（结构不符，这次复用当场作废）。组卡自身仍旧只占一桶——按内容
                            // 分细（比如按展开态）会让行在桶之间搬，是纯亏。
                            contentType = { "course-group-card" },
                        ) { group ->
                            CourseGroupCard(
                                group = group,
                                timeSlots = state.timeSlots,
                                colorExpanded = colorTargetKey == group.key,
                                onToggleColor = {
                                    colorTargetKey = if (colorTargetKey == group.key) null else group.key
                                },
                                onPickColor = { index ->
                                    val primary = group.fragments.first()
                                    // T124a：接收者从本页 `rememberCoroutineScope()` 换成 `viewModel.viewModelScope` ——
                                    // 组外观那一笔写库挂在 composition scope 上时，这一页被卸载（切 tab 走
                                    // popUpTo+saveState）就会把这一手带动取消：库里改了、撤销条目没压、
                                    // 课前铃/桌面组件/明日预告不重排，UI 上静默。本体只有这一次写库，整块搬。
                                    viewModel.viewModelScope.launch {
                                        viewModel.updateCourse(
                                            primary.copy(colorIndex = index, customColorArgb = null),
                                            CourseSaveOptions(applyToGroup = true),
                                        )
                                    }
                                },
                                onEdit = { onEditCourse(group.fragments.first()) },
                                onRequestDelete = { pendingDelete = group },
                                // 删课/换色分组时整列重排有过渡，不是瞬间抽走
                                modifier = Modifier.animateItem(),
                            )
                        }
                        // 第二枚形状：整列最末的垫高（滚到底才出场，总共就一枚），
                        // 单独占一桶，别让它去顶掉一张组卡的槽位。
                        item(contentType = "course-group-list-footer") {
                            Spacer(modifier = Modifier.height(DesignTokens.spaceXL))
                        }
                    }
                }
            }
        }
    }

    // 走 payload 版而不是 `pendingDelete?.let`：清空的那一刻整棵子树就没了，收场一帧都播不出来
    ModalTransition(payload = pendingDelete) { group, modal ->
        // T253 甲档：这一次删除**删哪几段**与弹窗**念哪个数**只由这一枚判据产物决定，两支都读它。
        // 从前这一手直接把 `group.fragments` 交出去，而过滤态下那是「命中片段」而非整组 ——
        // 按钮念「删除整门课」、弹窗念「全部 N 个片段」，落库的却只有搜索命中的那几段。
        // 判据本体在 `domain/schedule/CourseGroupDeletionScope.kt`（纯 JVM，可单测）。
        val deletion = courseGroupDeletionScope(
            wholeGroupFragments = group.fragments,
            query = query,
            request = GroupDeletionRequest.WholeCourse,
        )
        AlertDialog(
            modifier = modal,
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除「${group.displayName}」？") },
            text = {
                Text(text = deleteGroupConfirmText(deletion, state.timeSlots))
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = group
                    pendingDelete = null
                    scope.launch {
                        val deleted = viewModel.deleteCourseGroupAndAwait(deletion.fragments)
                        val result = snackbarHostState.showSnackbar(
                            message = if (deleted) "已删除「${target.displayName}」" else "删除失败：${target.displayName} 还在课表里",
                            actionLabel = "撤销".takeIf { deleted },
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            viewModel.undoDeleteCourse()
                        }
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun CourseGroupCard(
    group: CourseGroup,
    timeSlots: List<TimeSlot>,
    colorExpanded: Boolean,
    onToggleColor: () -> Unit,
    onPickColor: (Int) -> Unit,
    onEdit: () -> Unit,
    onRequestDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = group.fragments.first()
    val color = com.buaa.schedule.core.designsystem.courseColor(primary)
    // 键与写法收口在 courseSharedElementModifier（与周视图课程格、编辑器同源，T52④）
    val sharedModifier = Modifier.courseSharedElementModifier(primary.id)

    GlassSurface(
        variant = GlassVariant.PANEL,
        contentPadding = DesignTokens.spaceL,
        shape = RoundedCornerShape(DesignTokens.cornerPanel),
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(motionSpec<IntSize>())
            .then(sharedModifier),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceS)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 色标：点击展开调色板（整门课生效）
                Box(
                    modifier = Modifier
                        .size(DesignTokens.minTouchTarget)
                        .clickable(onClick = onToggleColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(DesignTokens.iconLarge)
                            .background(color, CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = "更改颜色",
                            tint = contentOn(color),
                            modifier = Modifier.size(DesignTokens.iconSmall),
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = DesignTokens.spaceS),
                ) {
                    // 与今日课表卡同一口径：课名列已带 weight(1f) 吃剩余宽，但改前这里
                    // 连 maxLines 都没有——长课名（带括号后缀的研讨课、不可断词的英文课名）
                    // 会换行把整卡撑高。省略号收进一行，行高回到短标题卡的节奏。
                    Text(
                        text = group.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Text(
                        text = joinMeta(
                            group.teacher,
                            // 别名生效时才提一句原名：管理页得能看出这个别名挂在哪门课上
                            if (group.name != group.displayName) "原名 ${group.name}" else null,
                            fragmentSummary(group.fragments, timeSlots),
                            // 学分是课程级的（解析器按周合并时整组取同一个最大值，片段间不该分歧），
                            // 但手动/部分导入的组里确实可能出现"只有某个片段带学分"——
                            // 取第一个非空片段的就是这门课的学分，全空则 null，
                            // joinMeta 让整段连同分隔符一起缺席（缺项不占位）。
                            creditLabel(group.fragments.firstNotNullOfOrNull { it.credit }),
                            "${group.fragments.size} 段",
                        ),
                        // 空串也要滤：只判 null 的话，摘要为空就拼出「 · 5 段」这种悬空分隔符
                        // （joinMeta 就是这条约定的唯一实现）
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑")
                }
                // 两颗 48dp 的按钮紧贴着排，删除还是不可逆的那一颗：中间垫一档间距，
                // 让"改"与"删"之间有一个不会误触的缝
                Spacer(modifier = Modifier.width(DesignTokens.spaceS))
                IconButton(onClick = onRequestDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除整门课",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (colorExpanded) {
                Text(
                    text = groupColorScopeNote(
                        fragmentCount = group.fragments.size,
                        hasSourceGroupKey = primary.sourceGroupKey != null,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 触达目标 48dp 后 8 个色板一行放不下，用 FlowRow 自动换行
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(DesignTokens.spaceS),
                ) {
                    CourseColors.forEachIndexed { index, swatch ->
                        ColorSwatch(
                            color = swatch,
                            selected = primary.customColorArgb == null &&
                                Math.floorMod(primary.colorIndex, CourseColors.size) == index,
                            onClick = { onPickColor(index) },
                        )
                    }
                }
            }
        }
    }
}

/** 摘要里最多铺开几段课次；剩下的用「 …」表示，总数由调用方的「N 段」说明 */
private const val MaxSummaryFragments = 3

/**
 * 一门课的课次摘要，如「周一 1-2 周三 3-4」。
 *
 * 原来是要素拼完再 `.take(60)` 按**字符**硬截：60 会砍在「周三 3-」这种半截上，
 * 读者既不知道被截了、也不知道还剩几段（审查①V-03）。限量单位改成"段"。
 *
 * 段落里的「第…节」按 [unwrappedPeriodLabel] 剥掉包装，剩下的裸节次号与星期之间
 * 用 [joinMeta] 连：片段没有节次时只写星期，既不拖一个空格也不留「节」字。
 */
internal fun fragmentSummary(fragments: List<Course>, timeSlots: List<TimeSlot>): String {
    if (fragments.isEmpty()) return ""
    val shown = fragments.take(MaxSummaryFragments).joinToString(" ") { fragment ->
        joinMeta(
            weekdayLabel(fragment.dayOfWeek) ?: "周?",
            unwrappedPeriodLabel(periodLabelOf(fragment.periods, timeSlots)),
            separator = " ",
        )
    }
    return if (fragments.size > MaxSummaryFragments) "$shown …" else shown
}

/**
 * 删除确认的正文（纯函数，可单测）：说清删掉几个片段、都排在第几节，以及**过滤态在这件事上说了什么**。
 *
 * 括号里那串节次走 [joinMeta]：没有节次的片段整段缺席，一个也没有时连「（）」
 * 一起不出场 —— 以前它长成「（第节、第3节）」，那既不是节次也不是省略。
 *
 * ## 入参为什么是判据产物而不是一段片段表（T253）
 *
 * 改前这一枚吃的是 `group.fragments`，句里的数就是 `fragments.size`。问题不在句子里，在**调用点**：
 * 过滤态下 `group.fragments` 装的是命中片段，于是那句「将删除这门课的全部 N 个片段」念的是 N=2
 * 而整组有 5 段 —— 句子跟着子集走，按钮那句「删除整门课」跟着整组走，同一屏两把尺。
 * 现在数与集合都来自 [GroupDeletionScope]（由 `domain/schedule/CourseGroupDeletionScope.kt` 判定），
 * 与交给仓储层的那一手**同一枚产物**：谁改判据，这句话跟着变；谁只改句子不改判据，这句话不跟着变，
 * 于是第 ⑤ 层那枚守卫会先红。
 *
 * 两档各有句子（[GroupDeletionRequest.WholeCourse] / [GroupDeletionRequest.MatchedFragmentsOnly]）：
 * 乙档在今天的 main 里没有调用方（本卡裁定走甲档），那一支留在这里是为了**换成乙档时那句
 * 「全部 N 个片段」不会继续谎称整门课** —— 卡面红线不许把两档折成一枚 Boolean，也就不许让
 * 其中一档没有句子。它由表驱动单测逐格踩过（`domain/schedule/CourseGroupDeletionScopeTest`），
 * 不是没人验的分支。
 */
internal fun deleteGroupConfirmText(scope: GroupDeletionScope, timeSlots: List<TimeSlot>): String {
    val periods = joinMeta(
        scope.fragments.map { periodLabelOf(it.periods, timeSlots) },
        separator = "、",
    )
    val where = periods.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""
    val head = when (scope.request) {
        GroupDeletionRequest.WholeCourse ->
            "将删除这门课的全部 ${scope.announcedFragmentCount} 个片段$where"
        GroupDeletionRequest.MatchedFragmentsOnly ->
            "只删除这门课的 ${scope.announcedFragmentCount} 个片段$where"
    }
    // 过滤态与删除范围的关系必须写在屏上，而不是藏在"搜索框里刚好有字"这件事里
    val filterNote = when {
        !scope.narrowedByFilter -> ""
        scope.request == GroupDeletionRequest.WholeCourse ->
            "。搜索只命中其中 ${scope.matchedFragmentCount} 段，删除按整门课执行、不受搜索影响"
        else ->
            "。这门课另有 ${scope.wholeGroupFragmentCount - scope.matchedFragmentCount} 段不在搜索结果里，它们不会被动到"
    }
    return "$head$filterNote。删除后可在提示条里撤销。"
}

/**
 * 调色板展开时那一句「点一颗色板会改到哪儿」（纯函数，可单测，格子在 `ui/course/CourseUnitTwoRulersTableTest`）。
 *
 * 改前它是一句**无条件**的「颜色会应用到这门课的全部片段」，而写库那一手是
 * `updateCourse(primary, CourseSaveOptions(applyToGroup = true))`；VM 那一侧
 * （`ui/ScheduleViewModel.kt` 里 `options.applyToGroup && course.sourceGroupKey != null` 那一判）
 * 只给**有教务组键**的课铺整组 ⇒ 手动课程那一组只有门面这一枚片段改得到色，其余各段原地不动。
 * T224 把归并换源到 [SemesterStats.creditsByCourse] 之后，手动组可以把同名课的不同天不同节并到
 * 同一张卡上，这一格就从"偶尔对不上"长成"看得出来对不上" ⇒ 按档说实话，不改 VM（那是本卡红线）。
 *
 * **「设计如此」配开关**：让手动组也整组生效的那枚字段是 [Course.sourceGroupKey]
 * （`domain/model/Course.kt`，写入方是 `data/import/BuaaScheduleParser.kt`）—— 给手动课程也登记一枚
 * 组键，VM 那一判自己就成立，`CourseSaveOptions.applyToGroup` 一字不用改。代价：写侧要补一次赋值 +
 * 一趟数据迁移，统计页「N 门课」与本页的门数会跟着一起重算（同名不同时的手动课从今往后本就并成一门）。
 */
internal fun groupColorScopeNote(fragmentCount: Int, hasSourceGroupKey: Boolean): String = when {
    !hasSourceGroupKey && fragmentCount > 1 ->
        "手动课程没有教务组键：颜色只会改当前那一段，另外 ${fragmentCount - 1} 段要逐段改"
    !hasSourceGroupKey -> "颜色会改的是当前那一段排课"
    fragmentCount > 1 -> "颜色会应用到这门课的全部 $fragmentCount 段排课"
    else -> "颜色会应用到这门课那唯一的一段排课"
}

/**
 * 归并 + 过滤 + 排序，交回页面上那一张张组卡。
 *
 * ## 归并这一步不在这里做（T224 换源）
 *
 * 改前这里写着 `.groupBy { groupKeyOf(it) }`，而 `groupKeyOf` 是**全仓第二套「一门课」的定义**，
 * 逐字（改前那一枚键）：`"manual:${course.name}|${course.dayOfWeek}|${course.periods.joinToString(",")}"`。它带出的三句同屏矛盾：页头念「课表管理（2 门课）」、
 * 确认框念「将删除这门课的全部 1 个片段」、按钮念「删除整门课」，而统计页按
 * [SemesterStats.courseGroupKey] 那把尺只认 1 门 —— **删完之后那门课在统计页照旧在列**，
 * 因为两套定义下它们不枚同一枚。`domain/schedule/SemesterStats.kt` 的 `creditsByCourse`
 * 同文件那段（口径 1 结尾）早就明文禁过这一手：「界面上再 groupBy 一遍就是第二套『一门课』的定义」。
 *
 * 今天按 [SemesterStats.creditsByCourse] 走：它按 [SemesterStats.courseGroupKey] 归并一次、
 * 并把**组内全部片段**（[SemesterStats.CourseCredit.fragments]）交出来，所以本页拿到的组
 * 与统计页那本账是同一枚产物，页头那句「N 门课」与统计页的 `courseCount` 由构造保证相等。
 *
 * **代价（换源换来的，不是白拿的）**：
 * 1. 手动课程从前按「名称+星期+节次」分，同名不同时间的两枚各占一张卡；现在它们并成一张卡
 *    （教务口径下同名同周次就是一门课），于是"删一张卡"会删掉**同名课的全部片段**，
 *    改色同理。撤销仍然只在提示条那颗按钮上（T127 那把闸一字未动）。
 *    能把这一档改回去的字段是 [SemesterStats.courseGroupKey] 里那枚 `sourceGroupKey`
 *    （`domain/model/Course.kt`）：手动课程没有它才退化成「学期|课程名」。要恢复"按时间分家"
 *    得给手动课程也登记一枚组键（写侧 + 迁移 + 统计页的门数一起变），不是本页能顺手改的。
 * 2. 组卡的门面（组名 / 教师 / 别名回退名）由 [primaryFragmentOf] 决定，而并入的片段现在
 *    **跨星期跨节次**，那一族的四档次序（有节次 → 节次 → 星期 → id）从"偶尔用得上"变成
 *    "每枚手动组都靠它"，T207 那枚守卫因此更不许放宽。
 *
 * ## 过滤只决定这张卡出不出场，决定不了删除范围（T253 甲档）
 *
 * 改前这里是**先过滤再归并**（`SemesterStats.creditsByCourse(filtered)`），于是搜索态下交回的
 * `CourseGroup.fragments` 装的是「命中片段」而不是上面那句承诺的「组内**全部**片段」
 * （[SemesterStats.CourseCredit.fragments] 的 KDoc 就写着"组内全部"）。带出的三句同屏矛盾：
 * 按钮念「删除整门课」、确认框念「将删除这门课的全部 N 个片段」、交给仓储层的却是命中的那几段 ⇒
 * 搜索命中 2 / 整组 5 时**删 2 留 3**，而屏幕上没有一处说过这件事。
 *
 * 现在**先归并、后过滤，且过滤只筛组**：一枚组内只要有任意一段命中，这张卡就整张出场，
 * 卡上的 `fragments` 仍是整组全部片段。于是
 * 「N 段」/「全部 N 个片段」/「删除整门课」/ 换色那句「全部 N 段排课」/ 交给仓储层的那一手，
 * 五处读的是同一枚集合；删除范围与搜索框里写着什么**无关**。
 * 判据本体（删哪几段 + 弹窗念哪个数）在 [courseGroupDeletionScope]，页头那句「N 门课」数的
 * 是"命中的门数"—— 那是过滤本来就该管的事。
 *
 * ⚠️ 命中判据 [courseFragmentMatchesQuery] 与关键词归一 [normalizedCourseQuery] 都在 domain：
 * 列表面（这里，决定哪张卡出场）与判据面（`courseGroupDeletionScope`，决定删哪几段）共吃同一枚，
 * 在界面里再写一遍 `contains` 就是第二把"搜索态"的尺。
 */
internal fun groupCourses(courses: List<Course>, query: String): List<CourseGroup> {
    val keyword = normalizedCourseQuery(query)
    return SemesterStats.creditsByCourse(courses)
        .map { credit ->
            val fragments = credit.fragments
            val primary = primaryFragmentOf(fragments)
            CourseGroup(
                key = credit.groupKey,
                name = primary.name,
                displayName = groupDisplayNameOf(fragments, primary),
                teacher = primary.teacher,
                fragments = fragments.sortedWith(
                    compareBy({ it.dayOfWeek }, { it.startPeriod })
                ),
            )
        }
        // 筛的是**组**，不是组里的片段：一段命中就整组出场，fragments 一枚不许被过滤掉
        .filter { group -> group.fragments.any { courseFragmentMatchesQuery(it, keyword) } }
        .sortedBy { it.displayName }
}

/**
 * 一节课的门面名：与 [Course.displayName] 的关系是**一半同源、一半天生不同**。
 *
 * 同源的那一半：「别名 trim 之后非空才作数」只有一枚落点，就是 [Course.displayNameOrNull] ——
 * 这一手过去在管理页里自己写第二遍（拿片段自己的别名 trim 再判空），全仓 main 侧那一档因此有两枚，
 * 本卡把它落成 1 枚（枚数尺见 `domain/model/CourseDisplayNameSingleSourceGuardTest`）。
 *
 * 不同的那一半：**谁的**别名作数。整组里任一片段有非空别名就以它为准，不能只看 primary ——
 * 编辑器逐片段保存，别名确实可以只挂在非 primary 的那一片上；而 [Course.displayName] 只认这一行自己。
 * 这一档两手天生要给两个答案，把它"统一"掉就等于把挂在非 primary 片段上的别名念丢，
 * 逐档的账摆在 `ui/course/CourseDisplayNameRelationTableTest`。
 *
 * ⚠️ [fragments] 必须是**归并点那一手**（调用点传的是 `SemesterStats.CourseCredit.fragments`，
 * 即输入次序），不是卡面上排过序的 [CourseGroup.fragments]（星期 → 节次）：组里两枚以上各带非空别名时，
 * `firstNotNullOfOrNull` 念的是次序，两处会给出不同答案。所以这枚字段留在构造点、
 * 不搬进 [CourseGroup] 的类体当属性 —— 搬进去就把那一档改了。
 */
private fun groupDisplayNameOf(fragments: List<Course>, primary: Course): String =
    fragments.firstNotNullOfOrNull { it.displayNameOrNull }
        ?: primary.displayName

internal data class CourseGroup(
    val key: String,
    /** 教务原名：分组真源，也是搜索与"别名指向哪门课"的兜底 */
    val name: String,
    /** 给用户看的名字：别名优先 */
    val displayName: String,
    val teacher: String?,
    val fragments: List<Course>,
)

/**
 * 整组门面由哪一枚碎片供：[CourseGroup] 的 `name`、`teacher` 与门面名的兜底名都取自它。
 * 纯函数，可单测（表驱动见 `ui/course/CourseGroupPrimaryFragmentTest`）。
 *
 * 改前那一格是 `fragments.minByOrNull { it.startPeriod } ?: fragments.first()`，两病（T207）：
 *
 * 1. **拿"假第 1 节"当比较键**。[Course] 的 `startPeriod` 空表兜底成 1，它自己的 KDoc 就明写
 *    「别拿它当位置用」—— 一旦把这个 Int 当成"确实有一节在 N"来排序，**一门没有节次的碎片就凭空
 *    占掉第 1 节、赢下整组的门面**，于是组名、教师、别名回退名全取自那枚"根本没有时间"的碎片。
 *    现改读 [Course.firstPeriodOrNull]：没有节次的碎片一律排在所有有节次的之后 ⇒
 *    组里只要还有一枚有节次的，没节次的那一枚就当不上门面。与
 *    `data/import/BuaaFirstWeekPolicy.kt:112-115` 那句「没号的行不参与『谁最小』的比较」同一叙述，
 *    也与 T26/T27 定下的「缺的那一项不占位」同源。
 * 2. **`?: fragments.first()` 是不可达支**（[SemesterStats.creditsByCourse] 交出来的 `fragments`
 *    必非空，`minByOrNull` 在非空列表上必回非 null）—— 正是 T193 登记的第 2 条那一族：从未执行过的兜底把真退化盖住。
 *    这里不补第二枚兜底：列表真为空就让 `.first()` 当场响，与
 *    `domain/schedule/ScheduleExporters.kt:62` 那句「上一行已判非空 ⇒ 那枚 `?:` 是不可达支」同形。
 *
 * 「这一格真能碰到吗」按盘面读源码判的（零装机证据）：空节次的碎片有一条来路是
 * `data/import/IcsParser.kt:175` 那句 `if (endSection < startSection) emptyList()` —— 结束早于开始的
 * VEVENT 就落成空节次；而编辑器那一侧自 T28 起 `editorCanSave` 要求 `periods.isNotEmpty()`，
 * 保存被禁用 ⇒ 「组里混着一枚没节次的碎片」是 ICS 导入的形状，不是臆造的输入。
 *
 * ## 并列在这一组里可能不可能（按 [SemesterStats.courseGroupKey] 的键形判出来的，不是猜的）
 *
 * **可能，而且是常态**，两族键各有各的并列法：
 * - 导入组（`sourceGroupKey` 非空）：键是「学期|教学班|课序号」
 *   （`data/import/BuaaScheduleParser.kt:66-70`），**不含星期与节次** ⇒ 同组可以横跨几天几节；
 *   而同一条教务记录里「一个教师 × 一个周次段」拆出来的多枚片段（同文件的 `teacherWeekPairs`
 *   那一支，星期与节次都从外层同一个 `day`/`periods` 取）**必然**在这一档并列。
 * - 手动组（无组键，T224 换源后退化成「学期|课程名」）：键同样**不含星期与节次**，
 *   组里可以并着同名课在不同天不同节的片段。同名而第一枚节次相同（周一 1-2 与周一 1-2 分排
 *   两个周次段，是最常见的手工录入形状）时**整组在这一档并列**，门面只能由后续档定；
 *   节次不同时才由头两档比出来。换源前那一族键把星期与节次钉死、整组恒并列，
 *   换源后这一档变成"同名同节次才并列" —— 结论没变：**并列是常态，次序必须有出处**。
 *
 * 所以并列需要一枚有出处的次序，四档（见 [primaryFragmentOrder]）：
 * **有没有节次 → 节次 → 星期 → 行 id**。前三档都是盘面上看得见的东西；末档 [Course.id] 是
 * Room 自增主键（落库次序），DAO 取回的行两两不同 ⇒ 全序无并列，挑中哪一枚与数据库返回序、
 * 与查询计划都无关，同一份库每次刷新挑出同一枚。整组都没有节次时头两档比不出东西，
 * 仍由「星期 → id」定序，照样确定。
 *
 * ⚠️ 本函数的次序与 [CourseGroup] 里那枚**列序**（星期 → `startPeriod`）不同源：列序说的是
 * "卡面上先印哪一行"，本函数说的是"整组门面用哪一枚的 name/teacher"。把两者并成一枚要动的是
 * 列序那把尺 —— 那会换掉用户看惯的教师行、卡片色与共享元素的落点，超出本卡点名的调用点那一格，
 * 故不改（已按残账登记）。
 */
internal fun primaryFragmentOf(fragments: List<Course>): Course =
    fragments.sortedWith(primaryFragmentOrder).first()

/**
 * 见 [primaryFragmentOf]：四档字典序，前一档比出胜负就不再看后一档。
 *
 * 第一档读 [Course.firstPeriodOrNull]（**不是 `startPeriod`**）：没节次的那一枚在这一档只会被判成
 * "排在有节次的之后"，从不替它宣称"第 1 节"，也不与有节次的争"谁最先上"；
 * 第二档只在两枚都有节次时才比节次（都没节次时这一档恒为 0，不拿兜底值去撑次序）；
 * 第三、四档把并列收成一个有出处的确定次序：星期 → 行 id。
 */
private val primaryFragmentOrder: Comparator<Course> = Comparator { left, right ->
    val leftPeriod = left.firstPeriodOrNull
    val rightPeriod = right.firstPeriodOrNull
    val byHasPeriod = when {
        leftPeriod == null && rightPeriod != null -> 1
        leftPeriod != null && rightPeriod == null -> -1
        else -> 0
    }
    val byPeriod = if (leftPeriod != null && rightPeriod != null) leftPeriod.compareTo(rightPeriod) else 0
    val byDay = left.dayOfWeek.compareTo(right.dayOfWeek)
    when {
        byHasPeriod != 0 -> byHasPeriod
        byPeriod != 0 -> byPeriod
        byDay != 0 -> byDay
        else -> left.id.compareTo(right.id)
    }
}
