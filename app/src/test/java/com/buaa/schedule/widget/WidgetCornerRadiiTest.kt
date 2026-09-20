package com.buaa.schedule.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「玻璃化背景」那条分支烘焙圆角的算术。
 *
 * 钉的是一条不变量：**桌面上看到的圆角半径 == 用户选的那一档 dp**。
 *
 * 为什么需要专门钉：背景层 ImageView 是 `scaleType="fitXY"`
 * （`app/src/main/res/layout/widget_today.xml:16`），Launcher 会把那张固定的 480x320
 * 画布**非等比**拉到组件的真实尺寸，于是"画进位图的半径"与"屏幕上看到的半径"之间隔着
 * 两个各自独立的倍数。少除一次，20dp 就画成 53dp（真机反馈的「圆角太大」「调节后不生效」）。
 * 而同一档在纯色底那条分支里是精确的（`setImageResource` → `<corners android:radius="20dp"/>`），
 * 于是开不开「壁纸模糊」看到的圆角完全是两回事。
 *
 * "屏幕上看到的半径"按 Launcher 的口径算回去：`烘焙半径 x 该轴的 (组件px / 画布px)`。
 */
class WidgetCornerRadiiTest {

    /**
     * 一格 = 一个档位 x 一个组件尺寸 x 一个屏幕密度。
     *
     * 尺寸取的是这 6 个组件真会被放到的大小：名义 4×2 在窄屏上是 250dp 宽、在
     * density 2.75 的主流机上接近 320dp，4×4 的周课表更高，2×1 的「下一节课」很扁。
     */
    private data class Cell(
        val cornerDp: Int,
        val widgetWidthDp: Int,
        val widgetHeightDp: Int,
        val density: Float,
    ) {
        val widgetWidthPx: Float get() = widgetWidthDp * density
        val widgetHeightPx: Float get() = widgetHeightDp * density
        val scaleX: Float get() = widgetWidthPx / CANVAS_WIDTH
        val scaleY: Float get() = widgetHeightPx / CANVAS_HEIGHT

        /** 手机尺寸的格子（兜底路径只在这些上谈"不离谱"，平板拉大的那一档不在此列） */
        val phoneSized: Boolean get() = widgetWidthDp <= 360 && widgetHeightDp <= 240

        /**
         * 这一格物理上画得出来吗。
         *
         * Skia 在 `2*rx > 宽` 或 `2*ry > 高` 时会把四角整体等比缩小，所以半径不可能
         * 超过组件短边的一半。化简后这条判据与 density、画布尺寸都无关：
         * `cornerDp * 2 <= min(宽dp, 高dp)`。
         */
        val drawable: Boolean get() = cornerDp * 2 <= minOf(widgetWidthDp, widgetHeightDp)

        override fun toString(): String =
            "选 ${cornerDp}dp / 组件 ${widgetWidthDp}x${widgetHeightDp}dp / density $density"
    }

    private val sizes = listOf(
        110 to 40,   // 2×1「下一节课」
        250 to 120,  // 4×2，窄屏手机
        320 to 200,  // 4×2，density 2.75 那一档的主流机（用户报的那一格）
        480 to 320,  // 与画布等大：两轴倍数都是 1
        300 to 430,  // 4×4「周课表」
        600 to 400,  // 平板上被拉大的「今日课程」
    )

    private val densities = listOf(1.0f, 1.5f, 2.0f, 2.625f, 2.75f, 3.5f)

    private val cells: List<Cell> = WidgetAppearance.CORNER_RADII_DP.flatMap { dp ->
        sizes.flatMap { (w, h) -> densities.map { d -> Cell(dp, w, h, d) } }
    }

    private fun bake(cell: Cell, size: WidgetSizePx?) = WidgetCornerRadii.bake(
        cornerDp = cell.cornerDp,
        density = cell.density,
        canvasWidthPx = CANVAS_WIDTH,
        canvasHeightPx = CANVAS_HEIGHT,
        size = size,
    )

    /** 走正常路径：宿主上报了组件真实尺寸 */
    private fun bakeAtRealSize(cell: Cell) =
        bake(cell, WidgetSizePx(cell.widgetWidthPx, cell.widgetHeightPx))

    /** Launcher 屏幕上真正看到的两轴半径（px）：烘焙值 x 该轴的 fitXY 倍数 */
    private fun displayedPx(cell: Cell, baked: BakedCornerRadius): Pair<Float, Float> =
        baked.radiusX * cell.scaleX to baked.radiusY * cell.scaleY

    /** 屏幕上看到的半径，换算回 dp —— 用户眼里的那个数 */
    private fun displayedDp(cell: Cell, baked: BakedCornerRadius): Pair<Float, Float> =
        displayedPx(cell, baked).let { it.first / cell.density to it.second / cell.density }

    private fun displayedDpAtRealSize(cell: Cell): Pair<Float, Float> =
        displayedDp(cell, bakeAtRealSize(cell))

    /** 旧口径：`cornerDp * 4f`，与 density、组件尺寸都无关。只用来对照"有没有变得更离谱" */
    private fun legacyDisplayedDp(cell: Cell): Pair<Float, Float> =
        displayedDp(cell, BakedCornerRadius(cell.cornerDp * 4f, cell.cornerDp * 4f))

    /** 允许的误差：不到半个物理像素，任何密度上肉眼都不可能分辨 */
    private val tolerancePx = 0.5f

    @Test
    fun gridCoversEveryBucketAndBothStretchAxesDifferSomewhere() {
        // 防呆：格子表要是被改空了，下面那些 forEach 里的断言会全体空转、测试照样绿
        assertEquals(
            "每个档位都得有格子",
            WidgetAppearance.CORNER_RADII_DP.size,
            cells.map { it.cornerDp }.toSet().size,
        )
        assertEquals("格子数 = 档位 x 尺寸 x 密度", 6 * sizes.size * densities.size, cells.size)
        assertTrue("得有能画的最大档位", cells.any { it.drawable && it.cornerDp == 28 })
        assertTrue(
            "得有直角以外的档位落在两轴倍数不等的格子上（否则椭圆角那条断言永远不会被触发）",
            cells.any { it.drawable && it.cornerDp > 0 && it.scaleX != it.scaleY },
        )
    }

    @Test
    fun displayedRadiusIsExactlyTheChosenBucketOnBothAxes() {
        cells.filter { it.drawable }.forEach { cell ->
            val (displayedX, displayedY) = displayedPx(cell, bakeAtRealSize(cell))
            val wantPx = cell.cornerDp * cell.density
            assertEquals(
                "$cell 横轴画成了 ${displayedX / cell.density}dp（选的是 ${cell.cornerDp}dp）",
                wantPx,
                displayedX,
                tolerancePx,
            )
            assertEquals(
                "$cell 纵轴画成了 ${displayedY / cell.density}dp（选的是 ${cell.cornerDp}dp）",
                wantPx,
                displayedY,
                tolerancePx,
            )
        }
    }

    @Test
    fun sameBucketLooksTheSameOnEveryWidgetSize() {
        // 「调节后不生效」的另一半：同一档在不同组件上必须画成同一个大小。
        // 旧口径的烘焙值与 density、组件尺寸都无关，于是显示值随组件一路散开几十 dp，
        // 同一档在桌面上有六副样子。
        WidgetAppearance.CORNER_RADII_DP.forEach { dp ->
            val displayed = cells.filter { it.cornerDp == dp && it.drawable }
                .flatMap { cell -> displayedDpAtRealSize(cell).let { (x, y) -> listOf(x, y) } }
            assertTrue("${dp}dp 至少得一格可画", displayed.isNotEmpty())
            val spread = displayed.maxOrNull()!! - displayed.minOrNull()!!
            assertTrue(
                "${dp}dp 这一档在各组件上看到的圆角散了 ${spread}dp：$displayed",
                spread < 0.5f,
            )
        }
    }

    @Test
    fun bakedCornerIsEllipticalButDisplaysAsACircle() {
        // fitXY 两轴倍数不等，画进位图的就必须是椭圆，屏幕上才刚好是正圆。
        // 两头都要断言：只烘焙成椭圆（或只显示成正圆）都不算修对。
        val cell = cells.first { it.scaleX != it.scaleY && it.drawable && it.cornerDp > 0 }
        val baked = bakeAtRealSize(cell)
        assertNotEquals(
            "$cell 两轴倍数不等（${cell.scaleX} vs ${cell.scaleY}），烘焙值就该是椭圆而不是圆",
            baked.radiusX.toDouble(),
            baked.radiusY.toDouble(),
            0.001,
        )
        val (x, y) = displayedDp(cell, baked)
        assertEquals("显示出来的两轴必须等长，否则圆角是斜的", x.toDouble(), y.toDouble(), 0.01)
        assertEquals(cell.cornerDp.toFloat().toDouble(), x.toDouble(), 0.5 / cell.density)
    }

    @Test
    fun zeroBucketStaysSquare() {
        val cell = Cell(0, 320, 200, 2.75f)
        val baked = bakeAtRealSize(cell)
        assertEquals(0f, baked.radiusX, 0.0001f)
        assertEquals(0f, baked.radiusY, 0.0001f)
        val (x, y) = displayedDp(cell, baked)
        assertEquals(0f, x, 0.0001f)
        assertEquals(0f, y, 0.0001f)
    }

    // ---- 取不到宿主上报尺寸时的两条兜底 ----

    @Test
    fun optionsMissingFallsBackToProviderSizeWithoutGoingAbsurdlyRound() {
        // options 全 0（宿主没上报）→ 退到 provider 声明的 minWidth/minHeight。
        // 声明值是**可放置下限**而不是绘制尺寸：today_widget_info 给 4×2 声明 180x40dp，
        // 画出来是 320x200dp。所以这条兜底只会把圆角画得偏**方**，不许比选的那一档更圆。
        val cases = listOf(
            // 4×2「今日课程」：provider 声明 180x40dp，实际画成 320x200dp
            Triple(180, 40, Cell(20, 320, 200, 2.75f)),
            Triple(180, 40, Cell(28, 320, 200, 2.75f)),
            // 2×1「下一节课」：provider 声明与实际一致
            Triple(110, 40, Cell(16, 110, 40, 2.75f)),
            // 4×2「今明两栏」：声明 180x80dp，实际 250x120dp
            Triple(180, 80, Cell(20, 250, 120, 2.625f)),
            // 4×2「紧凑周视图」：声明 180x80dp，实际 320x200dp
            Triple(180, 80, Cell(24, 320, 200, 3.0f)),
        )
        cases.forEach { (infoWidthDp, infoHeightDp, cell) ->
            val size = WidgetCornerRadii.resolveSizePx(
                optionsWidthDp = 0,
                optionsHeightDp = 0,
                infoWidthDp = infoWidthDp,
                infoHeightDp = infoHeightDp,
                density = cell.density,
            )
            assertTrue("${cell}：provider 声明还在时不该退到 null", size != null)
            val (x, y) = displayedDp(cell, bake(cell, size))
            assertTrue("${cell}：兜底后横轴看到 ${x}dp，选的才 ${cell.cornerDp}dp（比要修的这个 bug 还圆）", x <= cell.cornerDp * 2f)
            assertTrue("${cell}：兜底后纵轴看到 ${y}dp，选的才 ${cell.cornerDp}dp（比要修的这个 bug 还圆）", y <= cell.cornerDp * 2f)
            assertTrue("${cell}：兜底不该把圆角直接抹平成直角", x > cell.cornerDp / 8f && y > cell.cornerDp / 8f)
        }
    }

    @Test
    fun noSizeAtAllAssumesNoStretchAndNeverEndsUpRounderThanTheOldFormula() {
        // 连 provider 都取不到：只能按"位图不缩放"这个明写的假设算（见 [WidgetCornerRadii.bake]）。
        // 它不精确，但一定不炸、不 NaN、不比被它取代的旧口径更圆。
        assertNull(WidgetCornerRadii.resolveSizePx(0, 0, 0, 0, 2.75f))
        cells.filter { it.drawable }.forEach { cell ->
            val baked = requireNotNull(runCatching { bake(cell, null) }.getOrNull()) {
                "$cell 的兜底路径抛了"
            }
            assertTrue("$cell 烘焙出了负半径", baked.radiusX >= 0f && baked.radiusY >= 0f)
            assertTrue("$cell 烘焙出了非有限半径", baked.radiusX.isFinite() && baked.radiusY.isFinite())
            val (x, y) = displayedDp(cell, baked)
            val (legacyX, legacyY) = legacyDisplayedDp(cell)
            assertTrue("$cell：兜底横轴 $x 比旧口径 $legacyX 还圆", x <= legacyX)
            assertTrue("$cell：兜底纵轴 $y 比旧口径 $legacyY 还圆", y <= legacyY)
            if (cell.phoneSized) {
                assertTrue("$cell：手机尺寸上兜底横轴看到 ${x}dp，选的才 ${cell.cornerDp}dp", x <= cell.cornerDp * 2f)
                assertTrue("$cell：手机尺寸上兜底纵轴看到 ${y}dp，选的才 ${cell.cornerDp}dp", y <= cell.cornerDp * 2f)
            }
        }
    }

    @Test
    fun providerSizeFallbackAlsoNeverEndsUpRounderThanTheOldFormula() {
        // 同上那条判据，但走 provider 声明那一档：这一档对每一轴都独立成立，
        // 因为 4×4「周课表」的声明高度(110dp) 与实际(430dp) 差了四倍，
        // 误差是"偏方"还是"偏圆"取决于声明写得有多保守 —— 所以只能钉住"不比旧的更圆"。
        cells.filter { it.drawable }.forEach { cell ->
            val size = WidgetCornerRadii.resolveSizePx(
                optionsWidthDp = 0,
                optionsHeightDp = 0,
                infoWidthDp = cell.widgetWidthDp / 2,
                infoHeightDp = cell.widgetHeightDp / 4,
                density = cell.density,
            )
            val (x, y) = displayedDp(cell, bake(cell, size))
            val (legacyX, legacyY) = legacyDisplayedDp(cell)
            assertTrue("$cell：provider 兜底横轴 $x 比旧口径 $legacyX 还圆", x <= legacyX)
            assertTrue("$cell：provider 兜底纵轴 $y 比旧口径 $legacyY 还圆", y <= legacyY)
        }
    }

    @Test
    fun resolveSizePrefersOptionsAndFloorsProviderMinimaAtTheCanvas() {
        val density = 2.75f
        val fromOptions = requireNotNull(
            WidgetCornerRadii.resolveSizePx(
                optionsWidthDp = 320,
                optionsHeightDp = 200,
                infoWidthDp = 180,
                infoHeightDp = 40,
                density = density,
            ),
        )
        assertEquals(320f * density, fromOptions.widthPx, 0.001f)
        assertEquals(200f * density, fromOptions.heightPx, 0.001f)

        // 只有一轴上报了：那一轴用 options，另一轴落到 provider，
        // 并被"绘制尺寸不小于画布"这条下限托住（40dp x 2.75 = 110px < 画布高 320px）
        val mixed = requireNotNull(
            WidgetCornerRadii.resolveSizePx(
                optionsWidthDp = 320,
                optionsHeightDp = 0,
                infoWidthDp = 180,
                infoHeightDp = 40,
                density = density,
            ),
        )
        assertEquals(320f * density, mixed.widthPx, 0.001f)
        assertEquals(CANVAS_HEIGHT.toFloat(), mixed.heightPx, 0.001f)

        // 两轴全无 → null，由 bake 走"不缩放"兜底
        assertNull(WidgetCornerRadii.resolveSizePx(0, 0, 0, 0, density))
        // density 都不合法（未初始化）时不硬算
        assertNull(WidgetCornerRadii.resolveSizePx(320, 200, 180, 40, 0f))
    }

    @Test
    fun infeasibleCellsAskTheImpossibleButNeverGoNegative() {
        // 40dp 高的组件要 28dp 圆角：短边一半才 20dp，Skia 会自己把四角等比缩小。
        // 这里只保证我们不吐负数/NaN，不假装画得出来。
        val cell = Cell(28, 110, 40, 2.75f)
        assertTrue(!cell.drawable)
        val baked = bakeAtRealSize(cell)
        assertTrue(baked.radiusX >= 0f && baked.radiusY >= 0f)
        assertTrue(baked.radiusX.isFinite() && baked.radiusY.isFinite())
    }

    /**
     * 回归哨兵：把"旧口径 `cornerDp * 4f` 到底错在哪"这几个数钉死在测试里。
     *
     * 这条对旧公式为真、对新公式也为真（它算的是旧公式自己的数）。留着是因为
     * 下次有人想"顺手乘个系数"时，这几个数量级应该当场可见。
     */
    @Test
    fun legacyFourTimesFormulaIsTheNumberTheBugReportMeasured() {
        val cell = Cell(20, 320, 200, 2.75f)
        val (legacyX, legacyY) = legacyDisplayedDp(cell)
        assertEquals("旧口径在这一格里画出来就是 53dp 左右", 53.3f, legacyX, 0.1f)
        assertEquals("而且两轴不等长（圆角本身还是个椭圆）", 50.0f, legacyY, 0.1f)
        assertEquals("旧换算与 density 无关：density 1.0 的机器上烘焙值也是 80px", 80f, cell.cornerDp * 4f, 0.0001f)
        // 新口径在同一格里必须正好是选的那一档
        val (x, y) = displayedDpAtRealSize(cell)
        assertEquals(20f, x, 0.5f / cell.density)
        assertEquals(20f, y, 0.5f / cell.density)
    }

    @Test
    fun canvasStaysSmallEnoughForBinder() {
        // 修这个 bug 的另一条约束是**别把位图放大**：RemoteViews 走 binder 事务，
        // ARGB_8888 每像素 4 字节；900x550 就是 1.9MB，TransactionTooLargeException 的边缘。
        // 圆角要准靠的是按两轴反推，不是加像素。
        val bytes = CANVAS_WIDTH.toLong() * CANVAS_HEIGHT * 4L
        assertTrue(
            "画布涨到了 $bytes 字节：请把半径按两轴拉伸倍数反推，而不是把画布放大",
            bytes <= 800_000L,
        )
    }
}

private val CANVAS_WIDTH = WidgetBackgroundRenderer.TARGET_WIDTH
private val CANVAS_HEIGHT = WidgetBackgroundRenderer.TARGET_HEIGHT
