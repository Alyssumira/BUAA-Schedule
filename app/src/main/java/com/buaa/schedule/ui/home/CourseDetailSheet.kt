package com.buaa.schedule.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.creditLabel
import com.buaa.schedule.domain.model.joinMeta
import com.buaa.schedule.domain.model.peProjectOf
import com.buaa.schedule.domain.model.periodLabelOf
import com.buaa.schedule.domain.schedule.WeekParser

/**
 * 课程详情底部弹层：不跳编辑器即可查看教师/地点/周次/备注，并快速编辑或删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CourseDetailSheet(
    course: Course,
    /** 节次表：详情里的节次文案必须与网格切段同口径，否则出现「写着 5-6 节、格子上是两张卡」 */
    timeSlots: List<TimeSlot>,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    /** [com.buaa.schedule.core.designsystem.ModalTransition] 的进出场修饰符，见其 KDoc 第 2 条 */
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        modifier = modifier,
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DesignTokens.spaceL)
                .padding(bottom = DesignTokens.spaceXL),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceS),
        ) {
            Text(
                text = course.displayName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = courseDetailSubtitle(course, timeSlots),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = DesignTokens.spaceS))
            DetailRow("教师", course.teacher)
            DetailRow("地点", course.location)
            DetailRow("校区", course.campus)
            // 学分：null 时**整行缺席**，不走 DetailRow 的「未设置」占位——那占位是给
            // 教师/地点这种"用户没填"准备的；学分没有是"教务就没给"（手动/文本/ICS 导入
            // 或字段缺失），写「未设置」会让人以为是数据坏了。0 学分是真值，会照常画出来。
            creditLabel(course.credit)?.let { DetailRow("学分", it) }
            // 体育项目：一律从 course.name 判、从 course.name 剥——displayName 是别名优先
            // （Course.kt:84），用户把别名改成「体育课」项目就被自己的显示层弄丢了。
            // 非体育课 peProjectOf 返回 null，整行同样不出现。
            peProjectOf(course.name)?.let { DetailRow("体育项目", it) }
            if (!course.remark.isNullOrBlank()) DetailRow("备注", course.remark)
            Spacer(modifier = Modifier.height(DesignTokens.spaceM))
            Button(
                onClick = onEdit,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("编辑课程") }
            TextButton(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("删除课程", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * 详情 Sheet 标题下那一行：节次 · 周次（纯函数，可单测）。
 *
 * 走 [joinMeta] 而不是 `节次 + " · " + 周次`：没有节次的课（教务给空、备份恢复回来空的）
 * 以前整行是「第节 · 1-8周」，只把标签改成空串又会在行首留下一个悬空的 " · "。
 * 周次那边 [WeekParser.toDisplayString] 自带「无周次」，永远不为空，所以这一行不会整行消失。
 */
internal fun courseDetailSubtitle(course: Course, timeSlots: List<TimeSlot>): String =
    joinMeta(periodLabelOf(course.periods, timeSlots), WeekParser.toDisplayString(course.weeks))

@Composable
private fun DetailRow(label: String, value: String?) {
    // 占位与真值必须一眼分得开：「未设置」以前走全浓度正文，读起来就像真有一个
    // 叫"未设置"的教师。淡墨档位与课程卡上的「教室未定」同一条口径（0.55）。
    val placeholder = value.isNullOrBlank()
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        Text(
            text = value ?: "未设置",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
                .copy(alpha = if (placeholder) DesignTokens.PLACEHOLDER_INK_ALPHA else 1f),
            modifier = Modifier.weight(1f),
        )
    }
}
