package com.buaa.schedule.ui.course

import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.buaa.schedule.core.designsystem.ColorSwatch
import com.buaa.schedule.core.designsystem.CourseColors
import com.buaa.schedule.core.designsystem.LocalAnimatedVisibilityScope
import com.buaa.schedule.core.designsystem.LocalSharedTransitionScope
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
import com.buaa.schedule.domain.model.periodLabelOf
import com.buaa.schedule.domain.model.weekdayLabel
import com.buaa.schedule.ui.ScheduleViewModel
import kotlinx.coroutines.launch

/**
 * 课表管理总览页：按"同一门课"（sourceGroupKey）归并展示全部排课片段，
 * 提供 整门课改色 / 整门课删除 / 进入编辑器。
 *
 * 为什么需要这页：课表网格上同一门课拆出的多个片段（如 1-8 周与 9-16 周各一段）
 * 分散在不同位置，用户很难意识到它们是同一门课，也改不到"整门课的颜色"。
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
                        items(groups, key = { it.key }) { group ->
                            CourseGroupCard(
                                group = group,
                                timeSlots = state.timeSlots,
                                colorExpanded = colorTargetKey == group.key,
                                onToggleColor = {
                                    colorTargetKey = if (colorTargetKey == group.key) null else group.key
                                },
                                onPickColor = { index ->
                                    val primary = group.fragments.first()
                                    scope.launch {
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
                        item { Spacer(modifier = Modifier.height(DesignTokens.spaceXL)) }
                    }
                }
            }
        }
    }

    // 走 payload 版而不是 `pendingDelete?.let`：清空的那一刻整棵子树就没了，收场一帧都播不出来
    ModalTransition(payload = pendingDelete) { group, modal ->
        AlertDialog(
            modifier = modal,
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除「${group.displayName}」？") },
            text = {
                Text(
                    text = "将删除这门课的全部 ${group.fragments.size} 个片段" +
                        "（${group.fragments.joinToString("、") { periodLabelOf(it.periods, state.timeSlots) }}）。" +
                        "删除后可在提示条里撤销。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = group
                    pendingDelete = null
                    scope.launch {
                        viewModel.deleteCourseGroup(target.fragments)
                        val result = snackbarHostState.showSnackbar(
                            message = "已删除「${target.displayName}」",
                            actionLabel = "撤销",
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
    val sharedScope = LocalSharedTransitionScope.current
    val animScope = LocalAnimatedVisibilityScope.current
    val sharedModifier = if (sharedScope != null && animScope != null) {
        @OptIn(ExperimentalSharedTransitionApi::class)
        with(sharedScope) {
            Modifier.sharedElement(
                sharedContentState = rememberSharedContentState(key = "course_${primary.id}"),
                animatedVisibilityScope = animScope,
            )
        }
    } else {
        Modifier
    }

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
                    Text(
                        text = group.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = listOfNotNull(
                            group.teacher,
                            // 别名生效时才提一句原名：管理页得能看出这个别名挂在哪门课上
                            if (group.name != group.displayName) "原名 ${group.name}" else null,
                            fragmentSummary(group.fragments, timeSlots),
                            "${group.fragments.size} 段",
                        )
                            // 空串也要滤：只判 null 的话，摘要为空就拼出「 · 5 段」这种悬空分隔符
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
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
                    text = "颜色会应用到这门课的全部片段",
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
 */
private fun fragmentSummary(fragments: List<Course>, timeSlots: List<TimeSlot>): String {
    if (fragments.isEmpty()) return ""
    val shown = fragments.take(MaxSummaryFragments).joinToString(" ") { fragment ->
        "${weekdayLabel(fragment.dayOfWeek) ?: "周?"} ${
            periodLabelOf(fragment.periods, timeSlots).removePrefix("第").removeSuffix("节").trim()
        }"
    }
    return if (fragments.size > MaxSummaryFragments) "$shown …" else shown
}

/**
 * 按"同一门课"归并：优先用 [Course.sourceGroupKey]（导入课程），
 * 手动课程没有 groupKey，用 名称+星期+节次 兜底，避免把同名不同时间的课并到一起。
 * 纯函数，便于单测。
 */
internal fun groupCourses(courses: List<Course>, query: String): List<CourseGroup> {
    val keyword = query.trim()
    val filtered = if (keyword.isEmpty()) {
        courses
    } else {
        courses.filter {
            // 两个名字都要能命中：日常界面看到的是别名，用户到这里只会打别名；
            // 但教务原名才是这门课的"真名"，按它搜也该出来（别名可以是「物理」这种无信息量的词）
            it.name.contains(keyword, ignoreCase = true) ||
                it.displayName.contains(keyword, ignoreCase = true) ||
                it.teacher?.contains(keyword, ignoreCase = true) == true
        }
    }
    return filtered
        .groupBy { groupKeyOf(it) }
        .map { (key, fragments) ->
            val primary = fragments.minByOrNull { it.startPeriod } ?: fragments.first()
            CourseGroup(
                key = key,
                name = primary.name,
                displayName = groupDisplayNameOf(fragments, primary),
                teacher = primary.teacher,
                fragments = fragments.sortedWith(
                    compareBy({ it.dayOfWeek }, { it.startPeriod })
                ),
            )
        }
        .sortedBy { it.displayName }
}

/**
 * 一节课的门面名：别名可能只登记在某个片段上（编辑器逐条保存），
 * 所以整组里只要有一个非空别名就以它为准，不能只看 primary。
 */
private fun groupDisplayNameOf(fragments: List<Course>, primary: Course): String =
    fragments.firstNotNullOfOrNull { it.alias?.trim()?.takeIf { a -> a.isNotEmpty() } }
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

private fun groupKeyOf(course: Course): String =
    course.sourceGroupKey
        ?: "manual:${course.name}|${course.dayOfWeek}|${course.periods.joinToString(",")}"
