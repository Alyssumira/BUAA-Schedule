package com.buaa.schedule.ui.importing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.core.designsystem.GlassSurface
import com.buaa.schedule.core.designsystem.GlassVariant
import com.buaa.schedule.domain.model.ImportHistory
import com.buaa.schedule.ui.ScheduleViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val HISTORY_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA)

/**
 * 导入历史独立页。
 *
 * 导入页里只折叠展示最近 5 条；这里给全量列表 + 清空入口。
 * 记录本身只用于追溯（哪次导入、哪个学期、多少条），不参与任何业务逻辑。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportHistoryScreen(
    onBack: () -> Unit,
    viewModel: ScheduleViewModel = viewModel(
        factory = ScheduleViewModel.Factory(
            androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application,
        ),
    ),
) {
    val history by viewModel.importHistory.collectAsState(initial = emptyList())

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            com.buaa.schedule.core.designsystem.GlassTopBar(
                title = "导入历史（${history.size}）",
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = DesignTokens.spaceL),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceM),
        ) {
            if (history.isEmpty()) {
                GlassSurface(
                    variant = GlassVariant.PANEL,
                    contentPadding = DesignTokens.spaceL,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "还没有导入记录。导入一次课表后，这里会记录来源、学期与课程数。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceM),
                ) {
                    items(history, key = { it.id }) { entry ->
                        ImportHistoryRow(entry)
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = DesignTokens.spaceS),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    item {
                        TextButton(
                            onClick = { viewModel.clearImportHistory() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "清空全部历史记录",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportHistoryRow(entry: ImportHistory) {
    GlassSurface(
        variant = GlassVariant.PANEL,
        contentPadding = DesignTokens.spaceL,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceXS)) {
            Text(
                text = sourceLabel(entry.source) + (entry.termCode?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = java.time.Instant.ofEpochMilli(entry.importedAt)
                    .atZone(ZoneId.systemDefault())
                    .format(HISTORY_TIME_FORMAT) +
                    " · ${entry.courseCount} 条课程",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entry.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun sourceLabel(source: String): String = when (source) {
    "buaa" -> "北航教务导入"
    "ics" -> "ICS 文件导入"
    "text" -> "文本导入"
    "backup" -> "备份恢复"
    else -> source
}
