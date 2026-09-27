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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.CourseSaveOptions
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.joinMeta
import com.buaa.schedule.domain.model.periodLabelOf
import com.buaa.schedule.domain.model.weekdayLabel
import com.buaa.schedule.domain.schedule.CourseConflictResolution
import com.buaa.schedule.ui.ScheduleViewModel
import kotlinx.coroutines.launch

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
 * 「只改这些周」那一次落库，**首页与统计页共用这一份**（T82）。
 *
 * 抽出来的理由是这条链上有两处一旦写错就静默的账：
 * - 作用域必须是 `viewModelScope`，不能用 `rememberCoroutineScope()`：后者绑在对话框这次
 *   composition 上，用户点完「只改这些周」顺手划走对话框，协程就被取消 ——
 *   界面已经显示「已应用」，库里其实没写（P1）。统计页那一枚对话框同样会被人随手划走，
 *   所以它不能再抄一遍这段（抄漏这一条正是最难查的那种漏）。
 * - `join` 而非 fire-and-forget：调用方要拿到落库结果才能决定这一行是标成「已应用」
 *   还是把按钮还原让用户重试。join 只等完成、不传播取消，所以对话框关了也不会打断写入。
 * - 写的是 [CourseSaveOptions.partialWeeks]，而作用域由 [ConflictShiftWeekScope.weeksToShift]
 *   算：**组周 ∩ target.weeks** 一起进 `copy`。只带 `periods` 的那一版（T131 之前）不动 `weeks`，
 *   于是 `updateCourse` 那一判恒假、这枚旗标空转，落库退成整行覆盖 —— 连不冲突的周一起被挪走，
 *   而按钮写着「只改这些周」。T131 起才真的只改这几周。
 * - `copy` 必须带上 `isManualOverride = true`（T133）：这一记改的是 `periods`，而
 *   `ImportPlanner.courseKey` 把 `dayOfWeek`/`periods` 算在身份钥匙里 ⇒ 不标 manual 的行在下次
 *   教务刷新时钥匙对不上，被**整行丢掉**（挂在它上面的提醒也随 `deleteRemindersOfDroppedCourses`
 *   清掉），用户看到的是"向导里点完当场生效、下次刷新按教务原时刻冲回来"。代价：这一行从此退出
 *   教务刷新的匹配与覆盖，刷新会把教务那一版当新课补进来（同一门课两张卡）。
 *   注意**只有拆出来的那一行带这枚旗标**：`updateCoursePartialWeeks` 同时把原行的 `weeks` 收窄，
 *   那一行留 `false` 是有意的 —— 它的 `periods` 仍是教务给的那一份，标了 manual 就等于让教务
 *   那一版在**其余每一周**都补一张重复卡（而现状只重复被挪走的那几周）。
 *   T133b 逐条复核过**这一枚没有同值可达的路**，所以它照旧无条件为真（不像拖课那一枚要改判据）：
 *   `newPeriods` 来自 [CourseConflictResolution.suggestNearestFreeShift]，而它在枚举候选时有一记
 *   `if (candidate == currentPeriods) continue`（`CourseConflictResolution.kt:126`，`currentPeriods`
 *   正是 `target.periods.sorted()`）⇒ **原地压根不算建议**，那一行的按钮也就不会出场。
 *   `scopedWeeks` 那一维确实能判回原值（[ConflictShiftWeekScope.weeksToShift] 的两记 no-op 分支在
 *   `:63`、`:65` 把调用点递进来的 `targetWeeks` 原样送回），但周次既不在 `courseKey` 里、
 *   同一趟的 `periods` 又必然已经换掉 ⇒ "只动周次、不动时间"这一格在向导里组不出来。
 *
 * @param groupWeeks 那一组冲突实际涉及的周次（行头「第 N 周」念的就是它），来自
 *   [com.buaa.schedule.domain.schedule.CourseConflictResolution.ConflictGroup.weeks]
 * @return true = 确实写进了库（`updateCourse` 返回了最终行 id）
 */
suspend fun applyConflictShift(
    viewModel: ScheduleViewModel,
    target: Course,
    newPeriods: List<Int>,
    groupWeeks: List<Int>,
): Boolean {
    var saved = false
    // 判据只在调用点取一次，然后把算好的那几周交给写点（"各判一次"在本仓算违反）
    val scopedWeeks = ConflictShiftWeekScope.weeksToShift(groupWeeks, target.weeks)
    val job = viewModel.viewModelScope.launch {
        saved = viewModel.updateCourse(
            target.copy(periods = newPeriods, weeks = scopedWeeks, isManualOverride = true),
            CourseSaveOptions(partialWeeks = true),
        ) != null
    }
    job.join()
    return saved
}

/**
 * 冲突处理向导。
 *
 * 只做「建议 + 一键应用」，不做复杂拖拽：建议来自
 * [CourseConflictResolution.suggestNearestFreeShift]（同一天内最近空位，
 * 保持节次数量不变），落库走 partialWeeks，作用域是「组周 ∩ target.weeks」——
 * 只改行头那一串「第 N 周」里的周次，其余周不动（T131 才真的不动，见 [applyConflictShift]）。
 *
 * T82 起统计页也挂这一枚（同一个 composable、同一份 [applyConflictShift]）：
 * 冲突的处置 UI 全站只有这一套，两页只是入口不同。
 */
@Composable
fun ConflictWizardDialog(
    groups: List<CourseConflictResolution.ConflictGroup>,
    allCourses: List<Course>,
    /** 节次表：向导里的节次文案要按真实课间切段，与课表格子同一口径（P1-2） */
    timeSlots: List<TimeSlot>,
    /** 调用方 [com.buaa.schedule.core.designsystem.ModalTransition] 给的进出场修饰符 */
    modifier: Modifier = Modifier,
    /** 平移落库：挂起直到写完，返回 true 表示确实写进了库。第三个参数是那组冲突的周次。 */
    onApplyShift: suspend (Course, List<Int>, List<Int>) -> Boolean,
    onDismiss: () -> Unit,
) {
    // 「已应用过位移」记在弹窗这一层、按课程身份（wizardKey）索引：
    // 挂在 ConflictGroupRow 里的 remember(group) 会随 group 换实例而复位，
    // 而应用位移本身就会改课表 → 分组内容变 → 新 group → 标记又变回 false，
    // 于是「重复位移保护」形同虚设，连点会把同一门课越挪越远；
    // 行滚出 LazyColumn 视口时同样会丢标记（R5 F-54）。
    var shiftedCourses by remember { mutableStateOf(setOf<String>()) }
    // 同样记在弹窗层：写入是异步的，「正在写哪几门」如果记在行内，
    // 写入过程中该行因为课表变化而重建，按钮就又变回可点的了。
    var pendingCourses by remember { mutableStateOf(setOf<String>()) }
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text("课程冲突处理") },
        text = {
            if (groups.isEmpty()) {
                Text("当前没有冲突了。")
            } else {
                LazyColumn(
                    modifier = Modifier.height(360.dp),
                    verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceL),
                ) {
                    items(groups, key = { it.stableKey() }) { group ->
                        ConflictGroupRow(
                            group = group,
                            allCourses = allCourses,
                            timeSlots = timeSlots,
                            shiftedCourses = shiftedCourses,
                            pendingCourses = pendingCourses,
                            onShiftStart = { id -> pendingCourses = pendingCourses + id },
                            onShiftEnd = { id, saved ->
                                pendingCourses = pendingCourses - id
                                // 只有真的写进库才算「已应用」；失败要把按钮还给用户重试
                                if (saved) shiftedCourses = shiftedCourses + id
                            },
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
    timeSlots: List<TimeSlot>,
    shiftedCourses: Set<String>,
    pendingCourses: Set<String>,
    onShiftStart: (String) -> Unit,
    onShiftEnd: (String, Boolean) -> Unit,
    onApplyShift: suspend (Course, List<Int>, List<Int>) -> Boolean,
) {
    val target = group.courses.firstOrNull() ?: return
    val courseKey = target.wizardKey()
    // 建议基于"处理前的课表"计算：一旦应用过一次就不再重复给建议，
    // 避免连续点击把同一门课越挪越远
    val applied = courseKey in shiftedCourses
    val pending = courseKey in pendingCourses
    val rowScope = rememberCoroutineScope()
    val suggestion = remember(group, allCourses, applied) {
        if (applied) null else CourseConflictResolution.suggestNearestFreeShift(target, allCourses)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "${weekdayLabel(group.dayOfWeek) ?: "周?"} · " +
                "第 ${group.weeks.joinToString(",")} 周",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        group.courses.forEach { course ->
            Text(
                text = conflictCourseLine(course, timeSlots),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        when {
            // 建议里没有节次时整条不出场：那句「移到 」读不通，而按它写回去等于把这门课
            // 的节次清空。冲突课必有节次（冲突就是按节次重叠判的），这条是兜底。
            suggestion != null && suggestion.periods.isNotEmpty() -> {
                Text(
                    text = conflictSuggestionLine(target, suggestion, timeSlots),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = DesignTokens.spaceXS),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        // 写完之前禁用：此前点一下按钮立刻自我标记「已应用」，
                        // 而真正的写库协程还挂在对话框的 composition 上，
                        // 用户随手划走弹窗就等于把这次保存取消了（P1）。
                        enabled = !pending,
                        onClick = {
                            onShiftStart(courseKey)
                            rowScope.launch {
                                onShiftEnd(courseKey, onApplyShift(target, suggestion.periods, group.weeks))
                            }
                        },
                    ) { Text(if (pending) "写入中…" else "只改这些周") }
                }
            }
            applied -> {
                Text(
                    text = "已应用，冲突列表会随之更新。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = DesignTokens.spaceXS),
                )
            }
            else -> {
                Text(
                    text = "同一天没有可用空位，请手动调整或删课。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = DesignTokens.spaceXS),
                )
            }
        }
    }
}

/**
 * 冲突清单里的一行：「• 课程名（节次，教室）」（纯函数，可单测）。
 *
 * 括号里的两段都可能缺：节次缺失时以前写「（第节，J3-101）」，两段都缺时写「（）」。
 * 现在交给 [joinMeta] 逐段判空，一段都没有就连括号一起不出场。
 */
internal fun conflictCourseLine(course: Course, timeSlots: List<TimeSlot>): String {
    val detail = joinMeta(
        listOf(periodLabelOf(course.periods, timeSlots), course.location),
        separator = "，",
    )
    return "• ${course.displayName}" + if (detail.isEmpty()) "" else "（$detail）"
}

/** 平移建议那一句：「建议：课程名 移到 第3-4节（后挪 2 节）」（纯函数，可单测） */
internal fun conflictSuggestionLine(
    target: Course,
    suggestion: CourseConflictResolution.ShiftSuggestion,
    timeSlots: List<TimeSlot>,
): String {
    val shift = if (suggestion.shiftedBy == 0) {
        ""
    } else {
        "（${if (suggestion.shiftedBy > 0) "后" else "前"}挪 ${kotlin.math.abs(suggestion.shiftedBy)} 节）"
    }
    return "建议：${target.displayName} 移到 ${periodLabelOf(suggestion.periods, timeSlots)}$shift"
}
