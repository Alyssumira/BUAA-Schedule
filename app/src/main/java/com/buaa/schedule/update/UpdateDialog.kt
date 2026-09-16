package com.buaa.schedule.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 更新流程的唯一弹窗。挂在应用根节点上，因此自动检测和设置页手动检查共用一个出口。
 *
 * 只在"有东西可说"时被宿主渲染：自动检查的失败/无更新是静默的，
 * 手动检查才要求给出反馈 —— 判据在 [UpdateUiState.manual] 上，由宿主决定。
 */
@Composable
fun UpdateDialog(
    state: UpdateUiState,
    currentVersion: String,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onIgnore: () -> Unit,
    onOpenReleasePage: () -> Unit,
) {
    when (state) {
        is UpdateUiState.Available -> AvailableDialog(state, onDismiss, onDownload, onIgnore, onOpenReleasePage)
        is UpdateUiState.Downloading -> DownloadingDialog(state, onCancelDownload)
        is UpdateUiState.UpToDate -> SimpleDialog(
            title = "已是最新版本",
            body = "当前 v$currentVersion，Gitee 上没有更新的版本。",
            onDismiss = onDismiss,
            onOpenReleasePage = onOpenReleasePage,
        )
        is UpdateUiState.Failed -> SimpleDialog(
            title = "检查更新失败",
            body = state.message,
            onDismiss = onDismiss,
            onOpenReleasePage = onOpenReleasePage,
        )
        UpdateUiState.Idle, UpdateUiState.Checking -> Unit
    }
}

@Composable
private fun AvailableDialog(
    state: UpdateUiState.Available,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onIgnore: () -> Unit,
    onOpenReleasePage: () -> Unit,
) {
    val info = state.info
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本 ${info.version}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = info.title.takeIf { it != "v${info.version}" && it != info.version } ?: info.version,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = info.notes.ifBlank { "发布者没有填写更新说明。" },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (info.apkSize > 0) {
                    Text(
                        text = "安装包约 ${info.apkSize / 1024 / 1024 + 1} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (info.apkUrl == null) {
                    Text(
                        text = "这个版本没有可直接下载的安装包附件，将打开发布页下载。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        },
        confirmButton = {
            if (info.apkUrl != null) {
                TextButton(onClick = onDownload) { Text("立即下载") }
            } else {
                TextButton(onClick = onOpenReleasePage) { Text("打开发布页") }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onIgnore) { Text("忽略此版本") }
                TextButton(onClick = onDismiss) { Text("稍后") }
            }
        },
    )
}

@Composable
private fun DownloadingDialog(state: UpdateUiState.Downloading, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("正在下载 ${state.info.version}") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.percent >= 0) {
                    LinearProgressIndicator(
                        progress = { state.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${state.percent}%", style = MaterialTheme.typography.bodySmall)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("准备中…", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )
}

@Composable
private fun SimpleDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    onOpenReleasePage: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
        dismissButton = { TextButton(onClick = onOpenReleasePage) { Text("打开发布页") } },
    )
}
