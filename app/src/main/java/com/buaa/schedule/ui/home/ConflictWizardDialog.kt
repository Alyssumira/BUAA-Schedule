package com.buaa.schedule.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.periodLabel
import com.buaa.schedule.domain.schedule.CourseConflictResolution

private val DAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/**
 * LazyColumn 的稳定 key。
 *
 * 此前用 `it.hashCode()`：hashCode 由**内容**算出，用户应用一次平移建议后
 * 内容就变了 → key 跟着变 → LazyColumn 认为是一组全新的冲突而销毁重建该行，
 * 等价于没给 key（滚动位置丢失、输入框失焦、出现无谓的重组闪烁）。
 * 这里改用「星期 + 组内课程身份」，只要还是同一批课冲突，key 就不变。
 */
private fun CourseConflictResolution.ConflictGroup.stableKey(): String = buildString {
    append(dayOfWeek)
    append('|')
    append(
        courses
            .map { it.wizardKey() }
            .sorted()
            .joinToString(",")
    )
}

/** 课程在弹窗生命周期内的稳定身份：未落库的课（id=0）只能退到名字。 */
private fun Course.wizardKey(): String = if (id != 0L) id.toString() else "n:$name"

/**
 * 冲突处理向导。
 *
 * 只做「建议 + 一键应用」，不做复杂拖拽：建议来自
 * [CourseConflictResolution.suggestNearestFreeShift]（同一天内最近空位，
 * 保持节次数量不变），落库走 partialWeeks——只改冲突周次，其余周不动。
 */
@Composable
fun ConflictWizardDialog(
    groups: List<CourseConflictResolution.ConflictGroup>,
    allCourses: List<Course>,
    onApplyShift: (Course, List<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    // 「已应用过位移」记在弹窗这一层、按课程身份（wizardKey）索引：
    // 挂在 ConflictGroupRow 里的 remember(group) 会随 group 换实例而复位，
    // 而应用位移本身就会改课表 → 分组内容变 → 新 group → 标记又变回 false，
    // 于是「重复位移保护」形同虚设，连点会把同一门课越挪越远；
    // 行滚出 LazyColumn 视口时同样会丢标记（R5 F-54）。
    var shiftedCourses by remember { mutableStateOf(setOf<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("课程冲突处理") },
        text = {
            if (groups.isEmpty()) {
                Text("当前没有冲突了。")
            } else {
                LazyColumn(
                    modifier = Modifier.height(360.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(groups, key = { it.stableKey() }) { group ->
                        ConflictGroupRow(
                            group = group,
                            allCourses = allCourses,
                            shiftedCourses = shiftedCourses,
                            onShiftApplied = { id -> shiftedCourses = shiftedCourses + id },
                            onApplyShift = onApplyShift,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

@Composable
private fun ConflictGroupRow(
    group: CourseConflictResolution.ConflictGroup,
    allCourses: List<Course>,
    shiftedCourses: Set<String>,
    onShiftApplied: (String) -> Unit,
    onApplyShift: (Course, List<Int>) -> Unit,
) {
    val target = group.courses.firstOrNull() ?: return
    // 建议基于"处理前的课表"计算：一旦应用过一次就不再重复给建议，
    // 避免连续点击把同一门课越挪越远
    val applied = target.wizardKey() in shiftedCourses
    val suggestion = remember(group, allCourses, applied) {
        if (applied) null else CourseConflictResolution.suggestNearestFreeShift(target, allCourses)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "${DAY_NAMES.getOrElse(group.dayOfWeek - 1) { "周?" }} · " +
                "第 ${group.weeks.joinToString(",")} 周",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        group.courses.forEach { course ->
            val locationSuffix = if (course.location.isNullOrBlank()) "" else "，${course.location}"
            Text(
                text = "• ${course.displayName}（${periodLabel(course.periods)}$locationSuffix）",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        when {
            suggestion != null -> {
                Text(
                    text = "建议：${target.displayName} 移到 ${periodLabel(suggestion.periods)}" +
                        if (suggestion.shiftedBy == 0) "" else
                            "（${if (suggestion.shiftedBy > 0) "后" else "前"}挪 ${kotlin.math.abs(suggestion.shiftedBy)} 节）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = {
                        onShiftApplied(target.wizardKey())
                        onApplyShift(target, suggestion.periods)
                    }) { Text("只改这些周") }
                }
            }
            applied -> {
                Text(
                    text = "已应用，冲突列表会随之更新。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            else -> {
                Text(
                    text = "同一天没有可用空位，请手动调整或删课。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
