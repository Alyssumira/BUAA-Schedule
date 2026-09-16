package com.buaa.schedule.core.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.isRenderEffectSupported

/**
 * 玻璃分段控件（胶囊二选一/多选一）。
 * 用于页面内视图切换（如 周课表/今日）。
 *
 * 选中段也是一**真玻璃**（折射背景 + primary 色 tint），而不是一块贴死的
 * 不透明 primary：整条控件本来就浮在场景背景上，实心色块会让它看起来
 * 像"贴了张纸"，与其余玻璃语言割裂。
 *
 * ⚠️ 分段**按内容宽度**排布（`defaultMinSize`，不是 `weight`）：
 * 用 `weight` 时控件会把父级给它的可用宽度全部吃掉，
 * 与它同处一行、带 `weight(1f)` 的兄弟节点就会被挤成 0 宽（顶部"第N周"就是这么消失的）。
 */
@Composable
fun GlassSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    minSegmentWidth: Dp = 64.dp,
) {
    val haptics = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    val darkTheme = scheme.background.luminance() < 0.5f
    val tier = GlassGovernance.effectiveTier(Personalization.glassTier)
    val backdrop = LocalSceneBackdrop.current
    val segmentShape = remember { RoundedCornerShape(DesignTokens.cornerPill) }
    val material = remember(tier, darkTheme) {
        val base = DesignTokens.glassMaterial(GlassVariant.COMPACT, tier)
        if (darkTheme) base.copy(useVibrancy = false) else base
    }
    // 选中胶囊要一块自己的配额：它折射的是场景层，不是父玻璃
    var acquired by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val ok = GlassRegistry.acquire()
        acquired = ok
        onDispose { if (ok) GlassRegistry.release() }
    }
    val glassEnabled = acquired && backdrop != null && isRenderEffectSupported()
    // primary 做底板时，onPrimary 文字能不能读取决于透上来多少壁纸——按同一口径兜底
    val wallpaperStats = SceneLuma.wallpaper
    val segmentAlpha = remember(scheme.primary, scheme.onPrimary, darkTheme, material, wallpaperStats) {
        legibilityAlphaFloor(scheme.primary, scheme.onPrimary, darkTheme)
            .coerceAtLeast(material.surfaceAlpha)
            .coerceAtMost(0.82f)
    }
    val segmentModifier = remember(
        backdrop, material, scheme.primary, segmentAlpha, glassEnabled, segmentShape,
    ) {
        Modifier.liquidGlass(
            backdrop = backdrop,
            shape = { segmentShape },
            material = material,
            surfaceTint = scheme.primary,
            surfaceAlpha = segmentAlpha,
            enabled = glassEnabled,
            effectKey = material,
        )
    }
    GlassSurface(
        variant = GlassVariant.COMPACT,
        modifier = modifier,
        shape = segmentShape,
        contentPadding = 4.dp,
    ) {
        Row {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = minSegmentWidth)
                        .padding(horizontal = 2.dp)
                        .then(if (selected) segmentModifier else Modifier)
                        .clickable {
                            // 只有真正切换时才反馈，重复点当前项不该震动
                            if (index != selectedIndex) haptics.performTick()
                            onSelect(index)
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) scheme.onPrimary else scheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
