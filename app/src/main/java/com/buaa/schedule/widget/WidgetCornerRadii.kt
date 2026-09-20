package com.buaa.schedule.widget

/**
 * 「玻璃背景」那条分支的圆角烘焙算术。
 *
 * 单独成文件、且不 import 任何 android 类型，是为了让这条换算能在 JVM 单测里被
 * 逐格钉住（见 `WidgetCornerRadiiTest`）——它决定的是桌面上看得见的几何形状，
 * 而这个仓库里没有 Robolectric，任何直接调 `Canvas` 的写法都测不了。
 *
 * 背景是这么一件事：位图画布固定 480x320（[WidgetBackgroundRenderer.TARGET_WIDTH]），
 * 而布局里那块背景 ImageView 是 `scaleType="fitXY"`（`widget_today.xml:16`），
 * 于是 Launcher 把它**非等比**拉到组件的真实尺寸：
 *
 * ```
 * scaleX = 组件宽(px) / 480      scaleY = 组件高(px) / 320
 * 屏幕上看到的半径 = 画进位图的半径 x 对应轴的 scale
 * ```
 *
 * 所以想让用户选的 `N` dp 在屏幕上正好是 `N` dp，就得先把两轴各除回去。
 */

/** 烘焙进位图的两轴圆角半径（位图 px）。两轴分开是因为 `fitXY` 的拉伸本身分两轴。 */
internal data class BakedCornerRadius(val radiusX: Float, val radiusY: Float)

/** 组件在屏幕上的真实尺寸（px，已按 density 换算完）。 */
internal data class WidgetSizePx(val widthPx: Float, val heightPx: Float)

/** Launcher 把这张画布拉到组件真实尺寸时，两轴各自的放大倍数。 */
internal fun stretchFactors(size: WidgetSizePx?, canvasWidthPx: Int, canvasHeightPx: Int): Pair<Float, Float> {
    // 没有尺寸信息时的兜底口径见 [WidgetCornerRadii.bake]：按"不缩放"算
    val scaleX = size?.let { it.widthPx / canvasWidthPx } ?: 1f
    val scaleY = size?.let { it.heightPx / canvasHeightPx } ?: 1f
    return scaleX to scaleY
}

internal object WidgetCornerRadii {

    /**
     * 把用户选的 `cornerDp` 换算成要画进 `canvasWidthPx x canvasHeightPx` 位图的半径。
     *
     * @param size 组件真实尺寸；null = 一个都取不到，走 [stretchFactors] 的"不缩放"兜底。
     */
    fun bake(
        cornerDp: Int,
        density: Float,
        canvasWidthPx: Int,
        canvasHeightPx: Int,
        size: WidgetSizePx?,
    ): BakedCornerRadius {
        // 旧口径（尚未接线）：整张画布按"480dp 宽 -> 4x 密度"折算，两轴同值。
        // 它既不随 density 变、也不随组件尺寸变，所以同一档圆角在
        // 纯色底那条分支（精确 dp）与这条分支之间对不上——单测把它钉成红灯，
        // 修法是把 requestedPx 按两轴的拉伸倍数各除回去。
        val legacy = cornerDp * 4f
        return BakedCornerRadius(legacy, legacy)
    }

    /**
     * 组件真实尺寸的取数口径，按可信度从高到低：
     *
     * 1. `getAppWidgetOptions()` 的 `OPTION_APPWIDGET_MIN_WIDTH/MIN_HEIGHT`（dp）——
     *    宿主每次调整尺寸都会上报，含用户把组件拉大后的值，与
     *    [WidgetCommon] 里算行数用的同一份数据（`WidgetCommon.kt:618`）；
     * 2. `getAppWidgetInfo()` 的 `minWidth/minHeight`（dp）—— provider 声明值。
     *
     * 第 2 条是**可放置下限**而不是绘制尺寸：`today_widget_info.xml` 给一个 4×2 组件声明的
     * 是 `minHeight="40dp"`，而它画出来接近 200dp 高。照单用会把圆角半径烘焙成画布的
     * 一整条短边、显示出来就是一块胶囊，比要修的这个 bug 还夸张。所以这一档只当**下限**用：
     * 假定"绘制尺寸不小于我们这张画布本身"，即半径不超过 `cornerDp * density`。
     * 方向是可控的——只会把圆角画得偏方，不会再偏圆。
     *
     * 两轴各取各的：某一轴为 0/负数时另一轴照样用最好的那个来源。两轴都取不到返回 null，
     * 交给 [bake] 的兜底口径。
     */
    fun resolveSizePx(
        optionsWidthDp: Int,
        optionsHeightDp: Int,
        infoWidthDp: Int,
        infoHeightDp: Int,
        density: Float,
    ): WidgetSizePx? {
        if (density <= 0f) return null
        val widthPx = axisPx(optionsWidthDp, infoWidthDp, WidgetBackgroundRenderer.TARGET_WIDTH, density)
            ?: return null
        val heightPx = axisPx(optionsHeightDp, infoHeightDp, WidgetBackgroundRenderer.TARGET_HEIGHT, density)
            ?: return null
        return WidgetSizePx(widthPx, heightPx)
    }

    private fun axisPx(optionsDp: Int, infoDp: Int, canvasPx: Int, density: Float): Float? = when {
        optionsDp > 0 -> optionsDp * density
        infoDp > 0 -> (infoDp * density).coerceAtLeast(canvasPx.toFloat())
        else -> null
    }
}
