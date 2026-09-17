package com.buaa.schedule.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * 分组堆叠容器（参考 FolkPatch/APatch 的 SplicedColumnGroup 模式）。
 *
 * 与"一个大卡片包住整组内容"不同，这里**组内每条设置是独立的玻璃片**：
 * 首尾 18dp 圆角，中间 6dp 圆角 + 2dp 间隙，视觉上是一摞叠起来的卡片；
 * 组标题放在卡片外面，而不是卡片里的一行文字。
 *
 * 好处：一条设置展开/收起/隐藏时，相邻两片的圆角会自动"接管"，
 * 不会出现大卡片里空一大块的情况。
 *
 * **可折叠抽屉**：默认每组是一层可折叠的"抽屉"——点标题展开/收起，
 * 展开状态跨旋转保留。设置项多的时候整页不再是平铺的长列表，而是分类抽屉叠在一起；
 * 收起时组内条目不参与组合，顺带省掉其中的玻璃节点开销（设置页也因此更省电）。
 *
 * 用法：
 * ```
 * SettingsGroup(title = "外观") {
 *     item { SettingsSwitchRow(...) }
 *     item { SettingsRow(...) }
 * }
 * ```
 */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    /** 当前页是否应该渲染这一组。设置页改成"分类子界面"后，用它把非当前分类整组跳过 */
    visibleWhen: Boolean = true,
    /** 是否做成可折叠抽屉；只有一两条的分组可设 false 保持常驻 */
    collapsible: Boolean = false,
    /** 抽屉初始是否展开 */
    initiallyExpanded: Boolean = false,
    content: SettingsGroupScope.() -> Unit,
) {
    if (!visibleWhen) return
    val scope = SettingsGroupScope().apply(content)
    if (scope.items.isEmpty()) return
    val visibleCount = scope.items.count { it.visible }
    if (visibleCount == 0) return

    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    val isOpen = !collapsible || expanded
    val chevronRotation by animateFloatAsState(
        targetValue = if (isOpen) 0f else -90f,
        animationSpec = settingsFloatSpring(),
        label = "settingsGroupChevron",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DesignTokens.spaceL, vertical = DesignTokens.spaceS),
    ) {
        if (!title.isNullOrBlank()) {
            if (collapsible) {
                SettingsGroupHeader(
                    title = title,
                    itemCount = visibleCount,
                    collapsible = true,
                    expanded = expanded,
                    chevronRotation = chevronRotation,
                    onToggle = { expanded = !expanded },
                )
            } else {
                // 子界面里每组就是页面的主体，用普通小节标题即可
                SectionHeader(title)
            }
        }
        AnimatedVisibility(
            visible = isOpen,
            enter = expandVertically(settingsIntSizeSpring()) + fadeIn(settingsFloatSpring()),
            exit = shrinkVertically(settingsIntSizeSpring()) + fadeOut(settingsFloatSpring()),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                val firstIndex = scope.items.indexOfFirst { it.visible }
                val lastIndex = scope.items.indexOfLast { it.visible }
                scope.items.forEachIndexed { index, item ->
                    key(item.key) {
                        // 条目显隐走弹簧展开/收起（FolkPatch 同款体感）：
                        // 隐藏一条时相邻两片的圆角会随之"接管"，不会突然空出一块
                        AnimatedVisibility(
                            visible = item.visible,
                            enter = expandVertically(
                                animationSpec = settingsIntSizeSpring(),
                                expandFrom = Alignment.Top,
                            ) + fadeIn(settingsFloatSpring()),
                            exit = shrinkVertically(
                                animationSpec = settingsIntSizeSpring(),
                                shrinkTowards = Alignment.Top,
                            ) + fadeOut(settingsFloatSpring()),
                        ) {
                            val isFirst = index == firstIndex
                            val isLast = index == lastIndex
                            val shape = RoundedCornerShape(
                                topStart = if (isFirst) DesignTokens.cornerPanel else SETTINGS_CONNECTION_RADIUS,
                                topEnd = if (isFirst) DesignTokens.cornerPanel else SETTINGS_CONNECTION_RADIUS,
                                bottomStart = if (isLast) DesignTokens.cornerPanel else SETTINGS_CONNECTION_RADIUS,
                                bottomEnd = if (isLast) DesignTokens.cornerPanel else SETTINGS_CONNECTION_RADIUS,
                            )
                            GlassSurface(
                                variant = GlassVariant.PANEL,
                                contentPadding = SETTINGS_ITEM_PADDING,
                                shape = shape,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = if (isFirst) 0.dp else SETTINGS_ITEM_GAP),
                            ) {
                                item.content()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 抽屉标题：分类名 + 条目数 + 展开指示箭头。
 * 收起时把条目数露出来，避免"折叠后完全不知道里面有什么"。
 */
@Composable
private fun SettingsGroupHeader(
    title: String,
    itemCount: Int,
    collapsible: Boolean,
    /** 抽屉当前是否展开——语义判断只看这个布尔值，不看 chevron 的动画中间值 */
    expanded: Boolean,
    chevronRotation: Float,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (collapsible) {
                    Modifier
                        .clip(RoundedCornerShape(DesignTokens.cornerPanel))
                        .defaultMinSize(minHeight = DesignTokens.minTouchTarget)
                        // 只给 Role.Button 的话，读屏念得出"按钮"却读不出现在是展开还是收起
                        .toggleable(value = expanded, role = Role.DropdownList) { onToggle() }
                        .semantics {
                            stateDescription = if (expanded) "已展开" else "已收起"
                        }
                } else {
                    Modifier
                }
            )
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = DesignTokens.spaceS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$itemCount 项",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        if (collapsible) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = DesignTokens.spaceS)
                    .size(20.dp)
                    .rotate(chevronRotation),
            )
        }
    }
}

/** 组内条目的收集器；`visible = false` 的条目不占位（相邻片自动接管圆角） */
class SettingsGroupScope {
    val items = mutableListOf<SettingsGroupItem>()

    fun item(
        key: Any? = null,
        visible: Boolean = true,
        content: @Composable () -> Unit,
    ) {
        items += SettingsGroupItem(key ?: items.size, visible, content)
    }
}

/**
 * ⚠️ 这里**不能**标 `@Immutable`（此前标了）：[content] 是一个
 * `@Composable () -> Unit` 的可变引用，每次重组传入的都是新的 lambda 实例，
 * 而且它捕获的 state 完全可能在对象"不变"时发生变化。
 * 标上 @Immutable 等于向编译器撒谎 —— 编译器会据此跳过必要的重组，
 * 表现为"设置项改了值但界面不刷新"这类极难排查的问题。
 */
class SettingsGroupItem(
    val key: Any,
    val visible: Boolean,
    val content: @Composable () -> Unit,
)

/** 组外标题：FolkPatch 的 SectionHeader 口径（小号加粗、次级色、左缩进） */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(start = 4.dp, bottom = DesignTokens.spaceS),
    )
}

/**
 * 标准设置行：图标 + 标题/说明 + 尾部控件。
 *
 * 容器透明——放进 [SettingsGroup] 后由外层的玻璃片负责背景；
 * 单独使用时请自行包一层 GlassSurface。
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    /** 是否在尾部显示 ">"：可进入下级页面/触发动作的行用它表达"可点" */
    showChevron: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    /**
     * 开关型行专用（审查 U-07）：给了它，整行就是 `toggleable(role = Role.Switch)`
     * 而不是 `clickable(role = Role.Button)` —— 屏幕阅读器才会播报"开关，已开启/已关闭"。
     * 触摸行为不变：点行内任意处都翻转。
     */
    onToggle: ((Boolean) -> Unit)? = null,
    checked: Boolean = false,
) {
    // onToggle 优先于 onClick：两者都给时按开关处理，避免出现两套语义
    val interactionModifier = when {
        onToggle != null -> Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onToggle,
        )
        onClick != null -> Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        else -> Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 只有标题的行是 24dp 文字 + 2×4dp = 32dp，够不到 48dp 触控下限。
            // 下限必须写在 clickable **之前**：写在后面撑出来的高度不在命中区里。
            .defaultMinSize(minHeight = DesignTokens.minTouchTarget)
            .then(interactionModifier)
            .padding(vertical = SETTINGS_ROW_VERTICAL_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                modifier = Modifier.size(DesignTokens.iconMedium),
            )
            Box(modifier = Modifier.width(DesignTokens.spaceL))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
            if (!summary.isNullOrBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        trailing?.invoke()
        if (showChevron) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = if (trailing == null) 0.dp else DesignTokens.spaceS)
                    .size(20.dp),
            )
        }
    }
}

/** 开关行：整行可点，语义为 Role.Switch */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    SettingsRow(
        title = title,
        summary = summary,
        icon = icon,
        enabled = enabled,
        onToggle = { if (enabled) onCheckedChange(it) },
        checked = checked,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = null, // 整行已可切换，避免双重触发
                enabled = enabled,
            )
        },
        modifier = modifier,
    )
}

/** 中间片的小圆角：与首尾 18dp 形成"叠起来"的层次感 */
private val SETTINGS_CONNECTION_RADIUS = 6.dp

/** 中间片之间的间隙：露出页面背景，是"堆叠"观感的关键 */
private val SETTINGS_ITEM_GAP = 2.dp

/** 组内条目内容留白：比面板默认略紧，行高由内容决定 */
private val SETTINGS_ITEM_PADDING = 14.dp

/** 条目显隐的弹簧刚度（Spring.StiffnessMediumLow） */
private const val SETTINGS_SPRING_STIFFNESS = 400f

// 抽屉的每一段动画共用这一组弹簧参数。走 motionSpring 而不是裸 spring()：
// 系统要求减少动态效果时瞬时到位（④M-01 点名的"设置页展开"就在漏判名单里）。

/** 展开/收起高度用的弹簧 */
@Composable
private fun settingsIntSizeSpring(): FiniteAnimationSpec<IntSize> =
    motionSpring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = SETTINGS_SPRING_STIFFNESS)

/** 淡入淡出与箭头旋转用的弹簧（同为 Float） */
@Composable
private fun settingsFloatSpring(): FiniteAnimationSpec<Float> =
    motionSpring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = SETTINGS_SPRING_STIFFNESS)

private val SETTINGS_ROW_VERTICAL_PADDING = 4.dp

/**
 * 字段级错误提示槽（①C-04：校验结果要落在出错的那一格上，不是落在整张表单下面）。
 *
 * 与 `isError = invalid` 成对使用：边框指出是哪一格，红字说明为什么错。
 * 返回 null 时字段不会为它留一行高度——正常填写的表单不该为"万一出错"变胖。
 *
 * ⚠️ 那个带类型的局部变量不是啰嗦：`if (…) null else @Composable { … }`
 * 会被推成普通 lambda，调用点就报"不能在 @Composable 位置传非组合 lambda"。
 */
@Composable
fun fieldError(invalid: Boolean, message: String): (@Composable () -> Unit)? {
    if (!invalid) return null
    val content: @Composable () -> Unit = {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    return content
}
