package com.buaa.schedule.ui.settings

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.view.LayoutInflater
import androidx.annotation.LayoutRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.buaa.schedule.core.designsystem.DesignTokens

/**
 * 「一键添加桌面组件」行：组件预览 + 添加按钮 + 添加结果的可见反馈。
 *
 * 分成三件事做：
 * 1. 预览直接复用 Launcher 组件选择器里的同一批静态布局（`@layout/widget_preview_*`），
 *    而不是在 Compose 里另画一份。此前设置页只有五个纯文字按钮，用户要凭
 *    「4×2 列表」这种描述猜组件长什么样，选错了得回桌面删掉重来。
 * 2. 说清桌面**到底能不能**接受应用内添加（[AppWidgetManager.isRequestPinAppWidgetSupported]）。
 * 3. 请求之后把"接下来该看哪里"写在按钮下面，被 ROM 拦住时给出直达授权的入口。
 *
 * 关于「权限申请」：Launcher 的 `INSTALL_SHORTCUT` 是 normal 级权限，安装期即授予，
 * `ActivityResultContracts.RequestPermission` 只会同步回调同一个答案，
 * **永远不会弹窗**——所以这里不再走那条假流程，
 * 改用 [ReminderGuidance.openShortcutPermissionSettings] 把用户送到真正的开关面前。
 */
@Composable
fun PinWidgetRow(
    label: String,
    provider: Class<out AppWidgetProvider>,
    @LayoutRes previewLayout: Int,
    previewHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var requested by remember { mutableStateOf(false) }
    var showGuidance by remember { mutableStateOf(false) }
    val pinSupported = remember(context) {
        runCatching { AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported }
            .getOrDefault(false)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceS)) {
        WidgetPreviewTile(layoutRes = previewLayout, height = previewHeight)
        OutlinedButton(
            onClick = {
                requested = true
                if (!pinWidget(context, provider)) showGuidance = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
        when {
            !pinSupported -> Text(
                text = "当前桌面不支持从应用内添加：长按桌面空白处 →「小组件」→ 北航课程表，" +
                    "选一个尺寸拖到桌面即可。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            requested -> Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceXS)) {
                Text(
                    text = "已向桌面发起添加：桌面会弹出「添加到主屏」确认框，按住图标放到想要的位置。" +
                        "若桌面毫无反应，是 ROM 拦住了快捷方式创建。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { ReminderGuidance.openShortcutPermissionSettings(context) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = DesignTokens.spaceS,
                        end = DesignTokens.spaceS,
                    ),
                ) {
                    Text("去放行「创建桌面快捷方式」", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }

    if (showGuidance) {
        AlertDialog(
            onDismissRequest = { showGuidance = false },
            title = { Text("桌面拒绝了添加请求") },
            text = {
                Text(
                    "当前桌面需要手动放行「创建桌面快捷方式 / 小组件」。" +
                        "在系统设置里为本应用开启后回到这里重试，" +
                        "或长按桌面空白处 →「小组件」→ 北航课程表直接添加。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showGuidance = false
                    ReminderGuidance.openShortcutPermissionSettings(context)
                }) { Text("去授权") }
            },
            dismissButton = {
                TextButton(onClick = { showGuidance = false }) { Text("知道了") }
            },
        )
    }
}

/**
 * 发起一次固定请求。返回 false 表示桌面不支持或已静默拒绝
 * （[AppWidgetManager.requestPinAppWidget] 只在 Launcher 接受请求时返回 true）。
 */
private fun pinWidget(context: Context, provider: Class<out AppWidgetProvider>): Boolean =
    runCatching {
        AppWidgetManager.getInstance(context)
            .requestPinAppWidget(ComponentName(context, provider), null, null)
    }.getOrDefault(false)

/** 组件预览：把静态预览布局原样画出来，圆角裁切后与桌面观感一致。 */
@Composable
fun WidgetPreviewTile(
    @LayoutRes layoutRes: Int,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(DesignTokens.cornerCourse))
            // 预览布局自身带深色底；这里再垫一层，避免布局加载失败时留一块透明空洞
            .background(Color(0xFF161C2C)),
    ) {
        AndroidView(
            factory = { ctx ->
                LayoutInflater.from(ctx).inflate(layoutRes, null, false)
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
