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
                text = periodLabelOf(course.periods, timeSlots) +
                    " · " + WeekParser.toDisplayString(course.weeks),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = DesignTokens.spaceS))
            DetailRow("教师", course.teacher)
            DetailRow("地点", course.location)
            DetailRow("校区", course.campus)
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
