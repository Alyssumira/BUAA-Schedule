package com.buaa.schedule.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.buaa.schedule.core.designsystem.DesignTokens

/**
 * 更新流程的唯一弹窗。挂在应用根节点上，因此自动检测和设置页手动检查共用一个出口。
 *
 * 只在"有东西可说"时被宿主渲染：自动检查的失败/无更新是静默的，
 * 手动检查才要求给出反馈 —— 判据在 [UpdateUiState.manual] 上，由宿主决定。
 *
 * 容器沿用 Material3 [AlertDialog]：本应用所有对话框（冲突向导、取色器、设置页确认）
 * 都是它，玻璃容器反而会让更新弹窗成为全站唯一的一处不一致。
 * 对齐的重点放在信息密度与健壮性上——changelog 可滚、版本三行对照、失败不吞配额。
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
    onOpenInstallPermissionSettings: () -> Unit,
    onRetryInstall: () -> Unit,
) {
    when (state) {
        is UpdateUiState.Available -> AvailableDialog(
            state = state,
            currentVersion = currentVersion,
            onDismiss = onDismiss,
            onDownload = onDownload,
            onIgnore = onIgnore,
            onOpenReleasePage = onOpenReleasePage,
        )
        is UpdateUiState.Downloading -> DownloadingDialog(state, onCancelDownload)
        is UpdateUiState.NeedsInstallPermission -> NeedsInstallDialog(
            onOpenSettings = onOpenInstallPermissionSettings,
            onOpenReleasePage = onOpenReleasePage,
            onRetryInstall = onRetryInstall,
            onDismiss = onDismiss,
        )
        is UpdateUiState.InstallBlocked -> InstallBlockedDialog(
            reason = state.reason,
            onDismiss = onDismiss,
            onOpenReleasePage = onOpenReleasePage,
        )
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
    currentVersion: String,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onIgnore: () -> Unit,
    onOpenReleasePage: () -> Unit,
) {
    val info = state.info
    val lines = changelogLines(info.notes)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本 v${info.version}") },
        text = {
            // AlertDialog 的正文槽本身不滚：release 正文一长就把按钮顶出屏幕，
            // 按钮看不见时用户唯一的出路是点外部关闭，等于"弹窗一闪就没了"。
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceM),
            ) {
                VersionRows(info, currentVersion)
                if (lines.isEmpty()) {
                    Text(
                        text = "发布者没有填写更新说明。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ChangelogList(lines)
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

/** 当前版本 / 最新版本 / 发布日期 / 安装包大小：让用户在装之前就知道装的是什么 */
@Composable
private fun VersionRows(info: UpdateInfo, currentVersion: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        InfoRow("当前版本", "v$currentVersion")
        InfoRow("最新版本", "v${info.version}")
        formatReleaseDate(info.publishedAt)?.let { InfoRow("发布日期", it) }
        if (info.apkSize > 0) {
            // Gitee 的 assets 实测只有 name 与 browser_download_url，没有 size，
            // 所以这行通常不出现——体积改由下载弹窗按 Content-Length 给。
            // 保留判断：哪天接口带上体积，这里自动生效。向上取整：3.02 MB 说成 "3 MB"
            // 会让用户按 3MB 估算剩余空间。
            InfoRow("安装包", "~${(info.apkSize + MB - 1) / MB} MB")
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ChangelogList(lines: List<ChangelogLine>) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = ChangelogMaxHeight),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(lines.size) { index -> ChangelogRow(lines[index]) }
    }
}

@Composable
private fun ChangelogRow(line: ChangelogLine) {
    when (line) {
        is ChangelogLine.Heading -> Text(
            text = line.text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 6.dp),
        )
        is ChangelogLine.Bullet -> Row {
            Text(
                text = "•",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(14.dp),
            )
            Text(
                text = line.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        is ChangelogLine.Paragraph -> Text(
            text = line.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        ChangelogLine.Blank -> Text(
            text = " ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun DownloadingDialog(state: UpdateUiState.Downloading, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("正在下载 v${state.info.version}") },
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
                    Text(
                        text = buildString {
                            append("${state.percent}%")
                            if (state.totalBytes > 0) {
                                // 接口拿不到附件体积，Content-Length 是唯一体积来源；
                                // 向上取整，避免 3.02MB 显示成 3MB 让用户按 3MB 估空间
                                val megabytes = (state.totalBytes + MB - 1) / MB
                                append(" · 约 ${megabytes} MB")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = "准备中…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "下载在应用后台继续，离开这个页面也不会中断。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )
}

@Composable
private fun NeedsInstallDialog(
    onOpenSettings: () -> Unit,
    onOpenReleasePage: () -> Unit,
    onRetryInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("需要安装权限") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceM)) {
                Text(
                    text = "安装包已经下载好了。系统还没允许本应用安装未知来源的应用：" +
                        "点「去开启」，在打开的页面里把「安装未知应用」放开，" +
                        "再回来点「重试安装」就能直接装 —— 不必重新下载。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "若授权后系统仍不弹安装界面，检查厂商的「纯净模式 / 安装守护」是否拦截。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenReleasePage) { Text("改用浏览器下载") }
            }
        },
        confirmButton = { TextButton(onClick = onRetryInstall) { Text("重试安装") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onOpenSettings) { Text("去开启") }
                TextButton(onClick = onDismiss) { Text("稍后") }
            }
        },
    )
}

/**
 * 包没问题、装不上：签名密钥和机器上已装的那份不是同一个。
 *
 * 这条路径一定会发生在新版本换密钥的时候（比如从调试签名切到正式签名），
 * 唯一的出路是先卸载再装，而卸载要清数据 —— 所以正文必须先把备份讲清楚，
 * 而不是只丢一句"安装失败"。
 */
@Composable
private fun InstallBlockedDialog(
    reason: String,
    onDismiss: () -> Unit,
    onOpenReleasePage: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("这个包无法覆盖安装") },
        text = {
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
        dismissButton = { TextButton(onClick = onOpenReleasePage) { Text("打开发布页") } },
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
        text = {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
        dismissButton = { TextButton(onClick = onOpenReleasePage) { Text("打开发布页") } },
    )
}

/** changelog 限高：再长也留出两个按钮的高度，同时让用户看得见"这里还能滚" */
private val ChangelogMaxHeight = 240.dp

private const val MB = 1024L * 1024L
