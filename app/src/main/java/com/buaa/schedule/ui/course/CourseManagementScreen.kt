package com.buaa.schedule.ui.course

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Palette
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.buaa.schedule.core.designsystem.CourseColors
import com.buaa.schedule.core.designsystem.LocalAnimatedVisibilityScope
import com.buaa.schedule.core.designsystem.LocalSharedTransitionScope
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.core.designsystem.GlassSurface
import com.buaa.schedule.core.designsystem.GlassVariant
import com.buaa.schedule.core.designsystem.contentOn
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.CourseSaveOptions
import com.buaa.schedule.domain.model.periodLabel
import com.buaa.schedule.ui.ScheduleViewModel
import kotlinx.coroutines.launch

private val DAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

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
            )

            if (groups.isEmpty()) {
                GlassSurface(
                    variant = GlassVariant.PANEL,
                    contentPadding = DesignTokens.spaceL,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = if (state.courses.isEmpty()) "还没有课程，去首页新增或导入一份课表吧。"
                        else "没有匹配「$query」的课程。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceM)) {
                    items(groups, key = { it.key }) { group ->
                        CourseGroupCard(
                            group = group,
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
                        )
                    }
                    item { Spacer(modifier = Modifier.height(DesignTokens.spaceXL)) }
                }
            }
        }
    }

    pendingDelete?.let { group ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除「${group.name}」？") },
            text = {
                Text(
                    text = "将删除这门课的全部 ${group.fragments.size} 个片段" +
                        "（${group.fragments.joinToString("、") { periodLabel(it.periods) }}）。" +
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
                            message = "已删除「${target.fragments.firstOrNull()?.name ?: "课程"}」",
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
    colorExpanded: Boolean,
    onToggleColor: () -> Unit,
    onPickColor: (Int) -> Unit,
    onEdit: () -> Unit,
    onRequestDelete: () -> Unit,
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
        modifier = Modifier.fillMaxWidth().then(sharedModifier),
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
                            .size(22.dp)
                            .background(color, CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = "更改颜色",
                            tint = contentOn(color),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = DesignTokens.spaceS),
                ) {
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = listOfNotNull(
                            group.teacher,
                            fragmentSummary(group.fragments),
                            "${group.fragments.size} 段",
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑")
                }
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
                        val selected = primary.customColorArgb == null &&
                            Math.floorMod(primary.colorIndex, CourseColors.size) == index
                        // 触达目标是 48dp 的透明外壳（Material 无障碍最低触达），
                        // 视觉色块保持 30dp：48dp 的色球排在 8 色一行会溢出
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clickable(
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    indication = null,
                                    role = androidx.compose.ui.semantics.Role.Button,
                                ) { onPickColor(index) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .background(swatch, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "当前颜色",
                                        tint = contentOn(swatch),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun fragmentSummary(fragments: List<Course>): String =
    fragments.joinToString(" ") { fragment ->
        "${DAY_NAMES.getOrElse(fragment.dayOfWeek - 1) { "周?" }} ${
            periodLabel(fragment.periods).removePrefix("第").removeSuffix("节").trim()
        }"
    }.take(60)

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
            it.name.contains(keyword, ignoreCase = true) ||
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
                teacher = primary.teacher,
                fragments = fragments.sortedWith(
                    compareBy({ it.dayOfWeek }, { it.startPeriod })
                ),
            )
        }
        .sortedBy { it.name }
}

internal data class CourseGroup(
    val key: String,
    val name: String,
    val teacher: String?,
    val fragments: List<Course>,
)

private fun groupKeyOf(course: Course): String =
    course.sourceGroupKey
        ?: "manual:${course.name}|${course.dayOfWeek}|${course.periods.joinToString(",")}"
