package com.buaa.schedule.widget

/*
 * 「玻璃背景」那条分支的圆角烘焙算术。
 *
 * 单独成文件、且不 import 任何 android 类型，是为了让这条换算能在 JVM 单测里被
 * 逐格钉住（见 `WidgetCornerRadiiTest`）——它决定的是桌面上看得见的几何形状，
 * 而这个仓库里没有 Robolectric，任何直接调 `Canvas` 的写法都测不了。
 *
 * 背景是这么一件事：位图画布固定 480x320（WidgetBackgroundRenderer.TARGET_WIDTH），
 * 而布局里那块背景 ImageView 是 `scaleType="fitXY"`（`widget_today.xml:16`），
 * 于是 Launcher 把它**非等比**拉到组件的真实尺寸：
 *
 *     scaleX = 组件宽(px) / 480      scaleY = 组件高(px) / 320
 *     屏幕上看到的半径 = 画进位图的半径 x 对应轴的 scale
 *
 * 所以想让用户选的 `N` dp 在屏幕上正好是 `N` dp，就得先把两轴各除回去。
 */

/** 烘焙进位图的两轴圆角半径（位图 px）。两轴分开是因为 `fitXY` 的拉伸本身分两轴。 */
internal data class BakedCornerRadius(val radiusX: Float, val radiusY: Float)

/** 组件在屏幕上的真实尺寸（px，已按 density 换算完）。 */
internal data class WidgetSizePx(val widthPx: Float, val heightPx: Float)

internal object WidgetCornerRadii {

    /**
     * 把用户选的 `cornerDp` 换算成要画进 `canvasWidthPx x canvasHeightPx` 位图的半径。
     *
     * [size] 的三档来源，可信度从高到低（取数口径见 [resolveSizePx]）：
     * 1. 宿主上报的组件真实尺寸 → 显示出来的半径**正好**是 `cornerDp` dp，两轴等长；
     * 2. provider 声明的 minWidth/minHeight → 声明值偏小，于是烘焙值偏大，
     *    但被"不小于画布"这条下限托住（见 [resolveSizePx]）；
     * 3. 一个都取不到 → 假设 Launcher **不缩放**这张图（scale = 1），
     *    即按"位图画多大就显示多大"来画。这是没有任何信息时唯一不掺魔法数的假设。
     *
     * 后两档共同保有一条方向性：`scaleX/scaleY` 都被托在 `>= 1`，
     * 所以烘焙值 `<= cornerDp * density`，在任何 density < 4 的设备上都比它取代的旧口径
     * `cornerDp * 4f` 更接近选的那一档——只会把圆角画得偏**方**，不会再画得偏圆。
     * 偏圆才是用户投诉的那个样子。
     */
    fun bake(
        cornerDp: Int,
        density: Float,
        canvasWidthPx: Int,
        canvasHeightPx: Int,
        size: WidgetSizePx?,
    ): BakedCornerRadius {
        // 想要的是"屏幕上看到 cornerDp dp"，屏幕上的一个 dp 就是 density 个像素。
        val requestedPx = cornerDp.coerceAtLeast(0) * density
        val (scaleX, scaleY) = stretchFactors(size, canvasWidthPx, canvasHeightPx)
        // 画布会被 fitXY 各向独立地拉大 scaleX/scaleY 倍，所以先各除回去：
        // 显示出来才正好是 requestedPx（两轴等长 => 屏幕上是个正圆，尽管画进位图的是椭圆）。
        return BakedCornerRadius(requestedPx / scaleX, requestedPx / scaleY)
    }

    /**
     * Launcher 把这张画布拉到组件真实尺寸时，两轴各自的放大倍数。
     *
     * 尺寸缺失、或某一轴不是正数（调用方手滑传了 0）时按"该轴不缩放"算：
     * 除出 Infinity/NaN 再交给 Skia，圆角会直接画不出来。
     */
    private fun stretchFactors(
        size: WidgetSizePx?,
        canvasWidthPx: Int,
        canvasHeightPx: Int,
    ): Pair<Float, Float> {
        val widthPx = size?.widthPx?.takeIf { it > 0f } ?: canvasWidthPx.toFloat()
        val heightPx = size?.heightPx?.takeIf { it > 0f } ?: canvasHeightPx.toFloat()
        return widthPx / canvasWidthPx to heightPx / canvasHeightPx
    }

    /**
     * 组件真实尺寸的取数口径，按可信度从高到低：
     *
     * 1. `getAppWidgetOptions()` 的 `OPTION_APPWIDGET_MIN_WIDTH/MIN_HEIGHT`（dp）——
     *    宿主每次调整尺寸都会上报，含用户把组件拉大后的值，与
     *    `WidgetCommon.updateTwoDayWidget` 里限行数用的同一份数据；
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
