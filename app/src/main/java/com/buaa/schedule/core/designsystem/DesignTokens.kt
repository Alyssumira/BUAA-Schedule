package com.buaa.schedule.core.designsystem

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 设计 Token：圆角、间距、玻璃材质档位。
 * 业务页面不再各自传透明度/描边/模糊参数，统一从这里取。
 */
object DesignTokens {

    /** 手机悬浮底栏的栏体高度（LiquidBottomTabs containerHeight，悬浮让位共用这个口径） */
    val bottomBarHeight = 64.dp

    /** 页面大容器圆角 */
    val cornerPage = 24.dp

    /** 普通面板圆角 */
    val cornerPanel = 18.dp

    /** 课程格圆角 */
    val cornerCourse = 10.dp

    /** 胶囊控件圆角（50%） */
    val cornerPill = 50

    /** 间距刻度 */
    val spaceXS = 4.dp
    val spaceS = 8.dp
    val spaceM = 12.dp
    val spaceL = 16.dp
    val spaceXL = 24.dp

    /** 最小触控区域 */
    val minTouchTarget = 48.dp

    /** 玻璃材质档位：0 关闭（普通 surface）/ 1 标准 / 2 增强 */
    const val GLASS_TIER_OFF = 0
    const val GLASS_TIER_STANDARD = 1
    const val GLASS_TIER_ENHANCED = 2

    /**
     * 档位 → 材质强度倍率。所有变体共用同一倍率，
     * 避免同一档位下不同控件的强度变化幅度不一致。
     */
    fun glassIntensity(tier: Int): Float = if (tier >= GLASS_TIER_ENHANCED) 1.3f else 1f

    /**
     * 变体 + 档位 → 液态玻璃材质。**这是档位映射的唯一真源**。
     *
     * 此前存在两套并行映射（`glassSpec()` 与 `LiquidGlassMaterial`），
     * 改一处不会同步，测试通过但行为不变。现在 [GlassSurface] 与测试都走这里。
     */
    fun glassMaterial(variant: GlassVariant, tier: Int): LiquidGlassMaterial {
        val intensity = glassIntensity(tier)
        return when (variant) {
            // 底部导航 / 顶栏更强调折射，基准强度高于其他变体
            GlassVariant.CHROME -> LiquidGlassMaterial.pill(CHROME_BASE_INTENSITY * intensity)
            GlassVariant.PANEL -> LiquidGlassMaterial.dialog(intensity)
            GlassVariant.COMPACT -> LiquidGlassMaterial.pill(intensity)
            GlassVariant.ALERT -> LiquidGlassMaterial.dialog(intensity)
        }
    }

    /** CHROME 变体在标准档下的基准强度 */
    const val CHROME_BASE_INTENSITY = 1.3f
}

/**
 * 手机悬浮底栏让出的底部滚动空隙：
 * 栏体高度 + 上下 spaceS 留白 + 一点呼吸空间 + 系统导航栏高度。
 *
 * 悬浮玻璃底栏用 overlay Box 而不是 Scaffold 排布：内容要延伸到底栏背后滚动，
 * 因此一级页面的滚动容器需要自己加上这段 clearance，保证最后一项能滚出底栏区域。
 */
@Composable
fun floatingBottomBarClearance(): Dp =
    DesignTokens.bottomBarHeight + DesignTokens.spaceS * 3 +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
