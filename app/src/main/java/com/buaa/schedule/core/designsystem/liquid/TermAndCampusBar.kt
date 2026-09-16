package com.buaa.schedule.core.designsystem.liquid

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.core.designsystem.GlassSurface
import com.buaa.schedule.core.designsystem.GlassVariant
import com.buaa.schedule.core.designsystem.performTick
import com.buaa.schedule.data.import.BuaaInPageFetcher

/** 北航校区筛选选项，index 0 表示全部。 */
val buaaCampusOptions = listOf("全部校区", "学院路", "沙河", "杭州")

/**
 * 学期选择按钮：点击弹出**液态玻璃**列表。
 *
 * 与原来的 [androidx.compose.material3.DropdownMenu] 相比：
 * DropdownMenu 自带不透明 surface 背景，和整页的液态玻璃语言割裂；
 * 这里改用 [Popup] + [GlassSurface]，列表也是玻璃片，和页面连成一片。
 *
 * 按钮文案按参考稿固定为"学期切换"，当前学期名显示在弹出列表的第一行。
 */
@Composable
fun TermPickerButton(
    termOptions: List<BuaaInPageFetcher.TermOption>,
    currentTerm: String,
    refreshing: Boolean,
    onTermSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val currentName = termOptions.firstOrNull { it.code == currentTerm }?.name ?: currentTerm

    Box(modifier = modifier) {
        PickerButtonLabel(text = if (refreshing) "刷新中…" else "学期切换", enabled = !refreshing) {
            open = true
        }
        GlassDropdownPopup(
            expanded = open,
            onDismiss = { open = false },
            alignment = Alignment.TopStart,
        ) {
            PopupCaption("当前学期：$currentName")
            termOptions.forEach { option ->
                GlassPickerItem(
                    label = option.name,
                    selected = option.code == currentTerm,
                    onClick = {
                        open = false
                        if (option.code != currentTerm) onTermSelected(option.code)
                    },
                )
            }
        }
    }
}

/**
 * 校区选择按钮：点击弹出液态玻璃列表。
 * 选中项由外部持有（[selectedCampusIndex]），切换即回调。
 */
@Composable
fun CampusPickerButton(
    selectedCampusIndex: Int,
    onCampusSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    Box(modifier = modifier) {
        PickerButtonLabel(text = "校区切换") { open = true }
        GlassDropdownPopup(
            expanded = open,
            onDismiss = { open = false },
            alignment = Alignment.TopEnd,
        ) {
            // getOrElse 的兜底分支里再取 [0] 并不能兜底：
            // 列表为空时 `buaaCampusOptions[0]` 直接抛 IndexOutOfBoundsException。
            // 而且 selectedCampusIndex 来自外部 state，越界是可能的（改过校区配置后残留）。
            val currentCampusLabel = buaaCampusOptions.getOrNull(selectedCampusIndex)
                ?: buaaCampusOptions.firstOrNull()
                ?: "未选择"
            PopupCaption("当前：$currentCampusLabel")
            buaaCampusOptions.forEachIndexed { index, label ->
                GlassPickerItem(
                    label = label,
                    selected = index == selectedCampusIndex,
                    onClick = {
                        open = false
                        if (index != selectedCampusIndex) {
                            onCampusSelected(index)
                            haptics.performTick()
                        }
                    },
                )
            }
        }
    }
}

/** 工具条上的选择按钮：文字 + 下拉箭头，整块可点 */
@Composable
private fun PickerButtonLabel(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(DesignTokens.cornerPanel))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = DesignTokens.spaceS, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 列表首行的说明文字 */
@Composable
private fun PopupCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = DesignTokens.spaceM, end = DesignTokens.spaceM, top = 6.dp, bottom = 2.dp),
    )
}

/** 玻璃列表项：选中项用主题色 + 勾选图标 */
@Composable
private fun GlassPickerItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DesignTokens.cornerPanel))
            .clickable(onClick = onClick)
            .padding(horizontal = DesignTokens.spaceM, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 液态玻璃下拉浮层。
 *
 * 用 [Popup] 而不是 DropdownMenu：后者会带一层不透明的 Material surface 背景。
 * 这里浮层本体就是一块玻璃片（PANEL 档），列表内容超出时内部滚动。
 *
 * @param alignment 相对父容器对齐（学期按钮靠左用 TopStart，校区按钮靠右用 TopEnd）
 */
@Composable
private fun GlassDropdownPopup(
    expanded: Boolean,
    onDismiss: () -> Unit,
    alignment: Alignment,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    val density = LocalDensity.current
    Popup(
        alignment = alignment,
        // 往下挪一点，避免盖住触发它的那一行按钮
        offset = with(density) { IntOffset(0, 44.dp.roundToPx()) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        GlassSurface(
            variant = GlassVariant.PANEL,
            shape = RoundedCornerShape(DesignTokens.cornerPanel),
            contentPadding = DesignTokens.spaceS,
            modifier = Modifier.widthIn(min = 180.dp, max = 300.dp),
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 340.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                content()
            }
        }
    }
}
