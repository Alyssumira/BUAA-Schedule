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

    /**
     * 正常路径：宿主上报了组件真实尺寸。
     *
     * 走 [WidgetCornerRadii.resolveSizePx] 拿尺寸再烘，与 `widgetSizePx` -> `bake`
     * 的实际调用链同形（dp -> px 那一步也在覆盖范围内），而不是自己拼一个 WidgetSizePx。
     */
    private fun bakeAtRealSize(cell: Cell) = bake(
        cell,
        requireNotNull(
            WidgetCornerRadii.resolveSizePx(
                optionsMaxWidthDp = 0,
                optionsMaxHeightDp = 0,
                optionsMinWidthDp = cell.widgetWidthDp,
                optionsMinHeightDp = cell.widgetHeightDp,
                infoWidthDp = 0,
                infoHeightDp = 0,
                density = cell.density,
            ),
        ) { "$cell 的尺寸按 options 上报了却解不出来" },
    )

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
                optionsMaxWidthDp = 0,
                optionsMaxHeightDp = 0,
                optionsMinWidthDp = 0,
                optionsMinHeightDp = 0,
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
        // 于是残余误差正好等于 Launcher 实际的拉伸倍数——手机尺寸上不到 2.4x，
        // 而旧口径是**不管什么尺寸都 4x**。这条兜底不是精确，是"绝不比要修的 bug 更糟"。
        assertNull(WidgetCornerRadii.resolveSizePx(0, 0, 0, 0, 0, 0, 2.75f))
        cells.filter { it.drawable }.forEach { cell ->
            val baked = requireNotNull(runCatching { bake(cell, null) }.getOrNull()) {
                "$cell 的兜底路径抛了"
            }
            assertTrue("$cell 烘焙出了负半径", baked.radiusX >= 0f && baked.radiusY >= 0f)
            assertTrue("$cell 烘焙出了非有限半径", baked.radiusX.isFinite() && baked.radiusY.isFinite())
            // "不缩放"这个假设落到算术上就是：烘焙值不超过 dp->px 那一步本身
            val requestedPx = cell.cornerDp * cell.density
            assertTrue("$cell：兜底烘出了比 dp->px 更大的半径 ${baked.radiusX}", baked.radiusX <= requestedPx + 0.001f)
            assertTrue("$cell：兜底烘出了比 dp->px 更大的半径 ${baked.radiusY}", baked.radiusY <= requestedPx + 0.001f)
            val (x, y) = displayedDp(cell, baked)
            val (legacyX, legacyY) = legacyDisplayedDp(cell)
            assertTrue("$cell：兜底横轴 $x 比旧口径 $legacyX 还圆", x <= legacyX)
            assertTrue("$cell：兜底纵轴 $y 比旧口径 $legacyY 还圆", y <= legacyY)
            if (cell.cornerDp > 0) {
                // 不缩放这个假设不该把圆角整个抹掉：显示出来多扁是一回事（2×1 在 density 1.0
                // 上会被缩到不到 1px），烘成 0 是另一回事
                assertTrue("$cell：兜底把 ${cell.cornerDp}dp 烘成了直角", baked.radiusX > 0f && baked.radiusY > 0f)
            }
            if (cell.phoneSized) {
                assertTrue("$cell：手机尺寸上兜底横轴看到 ${x}dp，选的才 ${cell.cornerDp}dp", x <= cell.cornerDp * 2.5f)
                assertTrue("$cell：手机尺寸上兜底纵轴看到 ${y}dp，选的才 ${cell.cornerDp}dp", y <= cell.cornerDp * 2.5f)
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
                optionsMaxWidthDp = 0,
                optionsMaxHeightDp = 0,
                optionsMinWidthDp = 0,
                optionsMinHeightDp = 0,
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
                optionsMaxWidthDp = 0,
                optionsMaxHeightDp = 0,
                optionsMinWidthDp = 320,
                optionsMinHeightDp = 200,
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
                optionsMaxWidthDp = 0,
                optionsMaxHeightDp = 0,
                optionsMinWidthDp = 320,
                optionsMinHeightDp = 0,
                infoWidthDp = 180,
                infoHeightDp = 40,
                density = density,
            ),
        )
        assertEquals(320f * density, mixed.widthPx, 0.001f)
        assertEquals(CANVAS_HEIGHT.toFloat(), mixed.heightPx, 0.001f)

        // 两轴全无 → null，由 bake 走"不缩放"兜底
        assertNull(WidgetCornerRadii.resolveSizePx(0, 0, 0, 0, 0, 0, density))
        // density 都不合法（未初始化）时不硬算
        assertNull(WidgetCornerRadii.resolveSizePx(0, 0, 320, 200, 180, 40, 0f))
    }

    // ---- T38：取数口径的档位表（MAX -> MIN -> provider info -> null，两轴各取各的）----

    private val tierDensity = 2.75f

    @Test
    fun tierOneMaxOverridesMinOnBothAxes() {
        // MAX 盖 MIN：宿主同时上报两档时取 MAX（真实尺寸落在 [MIN, MAX] 里，见判据注释）。
        listOf(250 to 120, 360 to 224, 600 to 400).forEach { (maxDp, minDp) ->
            val size = requireNotNull(
                WidgetCornerRadii.resolveSizePx(
                    optionsMaxWidthDp = maxDp,
                    optionsMaxHeightDp = minDp + 40,
                    optionsMinWidthDp = minDp,
                    optionsMinHeightDp = minDp,
                    infoWidthDp = 180,
                    infoHeightDp = 40,
                    density = tierDensity,
                ),
            )
            assertEquals("横轴该取 MAX 的 $maxDp", maxDp * tierDensity, size.widthPx, 0.001f)
            assertEquals("纵轴该取 MAX 的 ${minDp + 40}", (minDp + 40) * tierDensity, size.heightPx, 0.001f)
        }
    }

    @Test
    fun tierTwoMinOverridesProviderInfo() {
        // MIN 盖 info：MAX 缺数时落到 MIN，仍然不许落到 provider 声明值上。
        val size = requireNotNull(
            WidgetCornerRadii.resolveSizePx(
                optionsMaxWidthDp = 0,
                optionsMaxHeightDp = 0,
                optionsMinWidthDp = 320,
                optionsMinHeightDp = 200,
                infoWidthDp = 180,
                infoHeightDp = 110,
                density = tierDensity,
            ),
        )
        assertEquals(320f * tierDensity, size.widthPx, 0.001f)
        assertEquals(200f * tierDensity, size.heightPx, 0.001f)
    }

    @Test
    fun tierThreeProviderInfoStillFlooredAtTheCanvasAndTierFourIsNull() {
        // 第 3 档：provider 声明值是可放置下限、不是绘制尺寸，所以仍要被
        // `coerceAtLeast(画布)` 托住（T30 那条论证一个字节都不能变）：
        // 180dp x 2.75 = 495px > 画布宽 480 -> 原样；40dp x 2.75 = 110px < 画布高 320 -> 托到 320。
        val fromInfo = requireNotNull(
            WidgetCornerRadii.resolveSizePx(
                optionsMaxWidthDp = 0,
                optionsMaxHeightDp = 0,
                optionsMinWidthDp = 0,
                optionsMinHeightDp = 0,
                infoWidthDp = 180,
                infoHeightDp = 40,
                density = tierDensity,
            ),
        )
        assertEquals(180f * tierDensity, fromInfo.widthPx, 0.001f)
        assertEquals(CANVAS_HEIGHT.toFloat(), fromInfo.heightPx, 0.001f)
        // 声明值大于画布时不许被"托底"反向削小
        val bigInfo = requireNotNull(
            WidgetCornerRadii.resolveSizePx(0, 0, 0, 0, 300, 200, tierDensity),
        )
        assertEquals(300f * tierDensity, bigInfo.widthPx, 0.001f)
        assertEquals(200f * tierDensity, bigInfo.heightPx, 0.001f)

        // 第 4 档：四枚全缺 -> null，交给 bake 的"不缩放"兜底
        assertNull(
            WidgetCornerRadii.resolveSizePx(0, 0, 0, 0, 0, 0, tierDensity),
        )
        // 负数（Bundle 没这个键时 getInt 给 0，手滑传负数按缺数算）也不算尺寸
        assertNull(WidgetCornerRadii.resolveSizePx(-1, -1, -1, -1, -1, -1, tierDensity))
    }

    @Test
    fun tiersAreResolvedPerAxisIndependently() {
        // 两轴各取各的：横轴只有 MAX、纵轴只有 MIN，两轴各自落到自己有数的那一档，
        // 谁也不把谁拉下来。
        val size = requireNotNull(
            WidgetCornerRadii.resolveSizePx(
                optionsMaxWidthDp = 400,
                optionsMaxHeightDp = 0,
                optionsMinWidthDp = 0,
                optionsMinHeightDp = 150,
                infoWidthDp = 180,
                infoHeightDp = 40,
                density = tierDensity,
            ),
        )
        assertEquals(400f * tierDensity, size.widthPx, 0.001f)
        assertEquals(150f * tierDensity, size.heightPx, 0.001f)

        // 同一格换一轴：横轴只有 MIN、纵轴只有 MAX
        val swapped = requireNotNull(
            WidgetCornerRadii.resolveSizePx(
                optionsMaxWidthDp = 0,
                optionsMaxHeightDp = 260,
                optionsMinWidthDp = 360,
                optionsMinHeightDp = 0,
                infoWidthDp = 180,
                infoHeightDp = 40,
                density = tierDensity,
            ),
        )
        assertEquals(360f * tierDensity, swapped.widthPx, 0.001f)
        assertEquals(260f * tierDensity, swapped.heightPx, 0.001f)

        // 一轴落到第 3 档（并被画布托底）、另一轴落到第 1 档
        val mixedTiers = requireNotNull(
            WidgetCornerRadii.resolveSizePx(
                optionsMaxWidthDp = 500,
                optionsMaxHeightDp = 0,
                optionsMinWidthDp = 0,
                optionsMinHeightDp = 0,
                infoWidthDp = 180,
                infoHeightDp = 40,
                density = tierDensity,
            ),
        )
        assertEquals(500f * tierDensity, mixedTiers.widthPx, 0.001f)
        assertEquals(CANVAS_HEIGHT.toFloat(), mixedTiers.heightPx, 0.001f)
    }

    @Test
    fun measuredDeviceShapeNoLongerDisplaysAnEllipseRounderThanTheBucket() {
        // 真机实测那一形（emulator-5554, API 36, density 2.625, 组件 id=8「今日课程」4×2）：
        // tile 真实绘制矩形 946x588px = 360.4x224.0dp，而 options 的 MIN_* 只有 (>=360, ~137)dp。
        // 改口径前纵轴烘出来是 85px=32.4dp（选 20dp 那一档），比横轴的 52px 长了 1.63 倍。
        val density = 2.625f
        val realWidthPx = 946f
        val realHeightPx = 588f
        val cell = Cell(20, 360, 224, density)

        // MAX 的真实值我没量到过（要等编排者装机反推）。这里只取"比实测 MIN 更大的一档"，
        // 因为真实尺寸必然落在 [MIN, MAX] 里，MAX 必然 >= 224dp 那条真实高。
        val maxWdp = 361
        val maxHdp = 240
        val size = requireNotNull(
            WidgetCornerRadii.resolveSizePx(
                optionsMaxWidthDp = maxWdp,
                optionsMaxHeightDp = maxHdp,
                optionsMinWidthDp = 360,
                optionsMinHeightDp = 137,
                infoWidthDp = 180,
                infoHeightDp = 40,
                density = density,
            ),
        )
        val baked = bake(cell, size)
        // 屏幕上真正看到的：烘焙值 x 该轴实际的 fitXY 倍数（用实测的 946x588，不用 MAX）
        val displayedX = baked.radiusX * (realWidthPx / CANVAS_WIDTH)
        val displayedY = baked.radiusY * (realHeightPx / CANVAS_HEIGHT)
        val wantPx = cell.cornerDp * density

        assertTrue("纵轴显示 ${displayedY / density}dp，超过选的 ${cell.cornerDp}dp：取数口径又偏圆了", displayedY <= wantPx + 0.5f)
        assertTrue("横轴显示 ${displayedX / density}dp，超过选的 ${cell.cornerDp}dp", displayedX <= wantPx + 0.5f)
        val ratio = maxOf(displayedX, displayedY) / minOf(displayedX, displayedY)
        assertTrue("两轴之比 $ratio，还是个椭圆", ratio <= 1.15f)
        // 对照：改口径前用的是 MIN 的 (360, 137)，纵轴倍数被低估 -> 显示值 32.4dp
        val beforeSize = requireNotNull(
            WidgetCornerRadii.resolveSizePx(0, 0, 360, 137, 180, 40, density),
        )
        val before = bake(cell, beforeSize)
        val beforeY = before.radiusY * (realHeightPx / CANVAS_HEIGHT)
        assertTrue("旧口径的纵轴 $beforeY 本该比新口径 ${displayedY}dp 更圆", beforeY > displayedY)
    }

    // ---- T39：options 上报的 dp 各自夹到「这块屏幕的物理尺寸」这一上限 ----
    //
    // 地面真相（emulator-5554 / AVD buaa36 / API 36 / density 2.625 / 1080x2400px，
    // 组件 id=8「今日课程」4×2，玻璃背景开；由编排者装机量像素得到）：
    // - tile 真实绘制矩形 946x588px = 360.4x224.0dp，屏幕本身 411.4x914.3dp；
    // - options 的 `MAX_HEIGHT` ≈ 229dp 恰好贴着真实高（所以 `ai/T38` 把纵轴从 45.7dp 修到 27.4dp），
    //   但 `MAX_WIDTH` ≥ 509dp —— **比整块屏还宽**，组件物理上不可能有这么大。
    //   它的语义更像「用户能把它拖到多大」而不是「它现在多大」。
    // 于是 `ai/T38` 之后 28dp 那一格实测 rx=52px(19.8dp) / ry=72px(27.4dp)，
    // 两轴之比 1.385，桌面上还是个椭圆，只是歪到了横轴那一侧。
    //
    // 上限的立论（为什么加了它方向性仍然是「只会偏方」）：
    // 真实绘制尺寸 ≤ 屏幕尺寸，且 MAX ≥ 真实尺寸 => `min(MAX, 屏)` 两个入参都 ≥ 真实尺寸
    // => 夹完仍然 ≥ 真实尺寸 => 拉伸倍数仍被高估 => 烘出来的半径仍**不超过**用户选的那一档。
    // 只是估得更准，方向一格没变。

    private val measuredDensity = 2.625f
    private val measuredScreenWidthDp = 411.4f
    private val measuredScreenHeightDp = 914.3f
    private val measuredTileWidthPx = 946f
    private val measuredTileHeightPx = 588f

    /** 实测那一格：宿主只报了 MAX，四枚 provider/MIN 用装机量到的值当陪衬。 */
    private fun resolveMeasured(
        maxWDp: Int,
        maxHDp: Int,
        minWDp: Int = 360,
        minHDp: Int = 137,
        screenWDp: Float = measuredScreenWidthDp,
        screenHDp: Float = measuredScreenHeightDp,
    ): WidgetSizePx = requireNotNull(
        WidgetCornerRadii.resolveSizePx(
            optionsMaxWidthDp = maxWDp,
            optionsMaxHeightDp = maxHDp,
            optionsMinWidthDp = minWDp,
            optionsMinHeightDp = minHDp,
            infoWidthDp = 180,
            infoHeightDp = 40,
            density = measuredDensity,
            displayWidthDp = screenWDp,
            displayHeightDp = screenHDp,
        ),
    ) { "max=($maxWDp,$maxHDp) min=($minWDp,$minHDp) 这一格按说该解出尺寸" }

    /** 屏幕上真正看到的两轴半径（px）：烘焙值 x **实测** tile 的 fitXY 倍数（不用 MAX 算倍数） */
    private fun displayedOnMeasuredTile(size: WidgetSizePx?, cornerDp: Int): Pair<Float, Float> {
        val baked = WidgetCornerRadii.bake(
            cornerDp, measuredDensity, CANVAS_WIDTH, CANVAS_HEIGHT, size,
        )
        return baked.radiusX * (measuredTileWidthPx / CANVAS_WIDTH) to
            baked.radiusY * (measuredTileHeightPx / CANVAS_HEIGHT)
    }

    private fun ratioOf(axes: Pair<Float, Float>): Float =
        maxOf(axes.first, axes.second) / minOf(axes.first, axes.second)

    @Test
    fun optionsMaxWidthIsCappedAtThePhysicalScreenNotDroppedToMin() {
        // 上限生效那一格：MAX_W=509 > 屏宽 411.4 ⇒ 该轴用 411.4。
        // 关键是「取到的是夹后的 411.4」而不是「夹不动了就退到 MIN 的 360」——
        // 退到 MIN 会把横轴倍数重新低估，正是 ai/T30 那一版的病。
        val size = resolveMeasured(maxWDp = 509, maxHDp = 229)
        assertEquals(
            "横轴该被夹到屏宽 411.4dp",
            measuredScreenWidthDp * measuredDensity,
            size.widthPx,
            0.05f,
        )
        assertTrue(
            "横轴夹完却掉到了 MIN 的口径 ${size.widthPx / measuredDensity}dp：那不是夹上限，是换了档位",
            size.widthPx > 360f * measuredDensity,
        )
    }

    @Test
    fun optionsMaxHeightBelowTheScreenIsPassedThroughUntouched() {
        // 上限不该生效那一轴：MAX_H=229 < 屏高 914.3 ⇒ 原样 229，一个像素都不许动
        // （ai/T38 刚修好的那 27.4dp 就是靠它）。
        val size = resolveMeasured(maxWDp = 509, maxHDp = 229)
        assertEquals(
            "纵轴本来就在屏高之内，不该被这道上限碰",
            229f * measuredDensity,
            size.heightPx,
            0.05f,
        )
        // 横轴同理：MAX_W=250 远小于屏宽，原样 250
        val small = resolveMeasured(maxWDp = 250, maxHDp = 120)
        assertEquals(250f * measuredDensity, small.widthPx, 0.05f)
        assertEquals(120f * measuredDensity, small.heightPx, 0.05f)
    }

    @Test
    fun displayCapBoundariesCoverExactlyTheScreenFarBelowItAndNoScreenAtAll() {
        // 恰好 == 屏宽：coerceAtMost 是闭的，这个数必须原样过去，不能被"夹"成下一档
        val equal = resolveMeasured(maxWDp = 411, maxHDp = 914)
        assertEquals(411f * measuredDensity, equal.widthPx, 0.05f)
        assertEquals(914f * measuredDensity, equal.heightPx, 0.05f)

        // 远小于屏宽：原样
        val farBelow = resolveMeasured(maxWDp = 100, maxHDp = 60)
        assertEquals(100f * measuredDensity, farBelow.widthPx, 0.05f)
        assertEquals(60f * measuredDensity, farBelow.heightPx, 0.05f)

        // 拿不到屏幕度量（0 或负数，未初始化/异常都归到这里）：**不做这道夹取**，
        // 原样交给档位表。这里 509 必须整个过去（1336px），而不是被夹成 0 或退档。
        val unknown = resolveMeasured(maxWDp = 509, maxHDp = 1200, screenWDp = 0f, screenHDp = 0f)
        assertEquals(509f * measuredDensity, unknown.widthPx, 0.05f)
        assertEquals(1200f * measuredDensity, unknown.heightPx, 0.05f)
        val negative = resolveMeasured(maxWDp = 509, maxHDp = 1200, screenWDp = -1f, screenHDp = -1f)
        assertEquals(509f * measuredDensity, negative.widthPx, 0.05f)
        assertEquals(1200f * measuredDensity, negative.heightPx, 0.05f)
    }

    @Test
    fun displayCapsEveryOptionsTierButNeverTheProviderDeclaredTier() {
        // 四枚 options dp（MAX_W/MAX_H/MIN_W/MIN_H）各自都要过这道闸：
        // MAX 缺数落到 MIN 时，MIN 同样不可能比屏宽还宽。
        val minOnly = resolveMeasured(maxWDp = 0, maxHDp = 0, minWDp = 509, minHDp = 1200)
        assertEquals(measuredScreenWidthDp * measuredDensity, minOnly.widthPx, 0.05f)
        assertEquals(measuredScreenHeightDp * measuredDensity, minOnly.heightPx, 0.05f)

        // 第 3 档 provider 声明值**不过**这道闸：它自带的是「不小于画布」那条托底
        // （axisPx 的 coerceAtLeast，ai/T30 的论证，一个字节都不许动），
        // 给它加上限会把那条托底的方向整个反掉。500dp 的声明值在 411.4dp 屏上原样过去。
        val fromInfo = requireNotNull(
            WidgetCornerRadii.resolveSizePx(
                optionsMaxWidthDp = 0,
                optionsMaxHeightDp = 0,
                optionsMinWidthDp = 0,
                optionsMinHeightDp = 0,
                infoWidthDp = 500,
                infoHeightDp = 40,
                density = measuredDensity,
                displayWidthDp = measuredScreenWidthDp,
                displayHeightDp = measuredScreenHeightDp,
            ),
        )
        assertEquals(500f * measuredDensity, fromInfo.widthPx, 0.05f)
        assertEquals(CANVAS_HEIGHT.toFloat(), fromInfo.heightPx, 0.05f)
    }

    @Test
    fun displayCapOnlyMovesTowardSquareNeverTowardRound() {
        // 方向性不变（这条是全卡的立论落到数上）：夹完之后
        //   解出的尺寸 ≥ 用 MIN 时解出的尺寸（夹的只是上沿，不会夹到下沿以下）
        //   显示半径 ≤ 所选档位，且 ≤ 用 MIN 时的显示值（只会更方，不会更圆）
        val wantPx = 28f * measuredDensity
        val minOnly = resolveMeasured(maxWDp = 0, maxHDp = 0)
        val capped = resolveMeasured(maxWDp = 509, maxHDp = 229)

        assertTrue(
            "夹完的尺寸 ${capped.widthPx} 反倒小于用 MIN 的 ${minOnly.widthPx}：那说明这道上限在往下夹",
            capped.widthPx >= minOnly.widthPx && capped.heightPx >= minOnly.heightPx,
        )

        val fromMin = displayedOnMeasuredTile(minOnly, 28)
        val fromCapped = displayedOnMeasuredTile(capped, 28)
        assertTrue(
            "夹过之后横轴显示 ${fromCapped.first}dp 超过了选的 28dp",
            fromCapped.first <= wantPx + 0.5f && fromCapped.second <= wantPx + 0.5f,
        )
        assertTrue(
            "夹过之后反而比用 MIN 时更圆（${fromCapped.first} vs ${fromMin.first}）",
            fromCapped.first <= fromMin.first + 0.001f && fromCapped.second <= fromMin.second + 0.001f,
        )
    }

    @Test
    fun displayCapAppliesToEachAxisIndependently() {
        // 一轴被夹、一轴不被夹，互不牵连：横轴 509->411.4，纵轴 229 原样。
        val oneClamped = resolveMeasured(maxWDp = 509, maxHDp = 229)
        assertEquals(measuredScreenWidthDp * measuredDensity, oneClamped.widthPx, 0.05f)
        assertEquals(229f * measuredDensity, oneClamped.heightPx, 0.05f)

        // 换一轴：横轴 250 原样、纵轴 1200->914.3
        val otherClamped = resolveMeasured(maxWDp = 250, maxHDp = 1200)
        assertEquals(250f * measuredDensity, otherClamped.widthPx, 0.05f)
        assertEquals(measuredScreenHeightDp * measuredDensity, otherClamped.heightPx, 0.05f)

        // 只有一轴拿得到屏幕度量（另一轴传 0）时，有数那轴夹、没数那轴原样
        val halfKnown = resolveMeasured(maxWDp = 509, maxHDp = 1200, screenWDp = measuredScreenWidthDp, screenHDp = 0f)
        assertEquals(measuredScreenWidthDp * measuredDensity, halfKnown.widthPx, 0.05f)
        assertEquals(1200f * measuredDensity, halfKnown.heightPx, 0.05f)
    }

    @Test
    fun measuredTileWithTheRealMaxNowDisplaysTheBucketOnBothAxes() {
        // 装机反推的那一格（28dp 档 = bucket 5，实测 MAX_W≥509、MAX_H≈229、tile 946x588）：
        // 改之前 rx=52px / ry=72px（比 1.385，还是椭圆）；
        // 加这道上限之后 rx=28x946/411.4≈**64.4px**(24.5dp)、ry 不动 71.9px(27.4dp)，
        // 两轴之比收到 1.117（<=1.15），且两轴都仍 <= 所选的 28dp=73.5px。
        val capped = displayedOnMeasuredTile(resolveMeasured(maxWDp = 509, maxHDp = 229), 28)
        assertEquals("横轴显示值", 64.4f, capped.first, 0.5f)
        assertEquals("纵轴显示值（与装机量到的 72px 同一格）", 71.9f, capped.second, 0.5f)
        assertTrue("两轴都该 <= 所选 28dp：${capped.first}/${capped.second}", capped.second <= 28f * measuredDensity + 0.5f)
        val ratio = ratioOf(capped)
        assertTrue("两轴之比 $ratio，桌上还是个椭圆", ratio <= 1.15f)

        // 对照：同一格不做这道夹取（屏幕度量传 0，= ai/T38 那一版），椭圆必须又回来，
        // 否则就是这道上限根本没在起作用。
        val uncapped = displayedOnMeasuredTile(
            resolveMeasured(maxWDp = 509, maxHDp = 229, screenWDp = 0f, screenHDp = 0f),
            28,
        )
        assertEquals("不夹时的横轴就是装机量到的 52px", 52.0f, uncapped.first, 0.5f)
        assertTrue("不夹时的两轴之比 ${ratioOf(uncapped)} 本该还是椭圆那一形", ratioOf(uncapped) > 1.15f)

        // 20dp 那一格：两轴都被夹到 <=52px，Pixel launcher 自己那层约 52px 的圆角遮罩
        // 会把它们托成 52/52，屏幕上就是正圆角 —— 也就是"烘得比遮罩小看不出区别"那一侧。
        val bucket20 = displayedOnMeasuredTile(resolveMeasured(maxWDp = 509, maxHDp = 229), 20)
        assertTrue("20dp 档两轴都该 <= 遮罩那 52px：${bucket20.first}/${bucket20.second}", bucket20.first <= 52f && bucket20.second <= 52f)
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
