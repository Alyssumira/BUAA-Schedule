package com.buaa.schedule.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「玻璃背景同一份像素只烤一遍」那三件判据：键怎么构造、出图尺寸怎么推、缓存在什么边界逐出。
 *
 * 背景是一笔实测账：改前每个实例每次刷新都新造一张 480x320 ARGB_8888
 * （= 614,400 B，与真机 `dumpsys appwidget` 报的 `views_bitmap_memory=614400` 逐字吻合），
 * 桌面上 N 个组件在同一轮刷新里把同一张壁纸糊 N 遍 —— 壁纸解码 + 两次重采样 + 一张画布，
 * 一遍都不省。现在同一枚键只有第一遍会跑。
 *
 * 为什么这里测的就是生产跑的那一条：[GlassBakeCache] 是泛型容器、
 * [GlassBakeKey] / [BakeSize] / [wallpaperSamplePoints] 全是纯 Kotlin，
 * 设备度量（真实尺寸、壁纸像素、uiMode）由 `render` 当参数交进来，而 `render` 走的
 * 就是这个 [GlassBakeCache.obtain]。烘焙本身（`Bitmap` / `Canvas`）在本模块没有
 * Robolectric，造不出来，所以注入的是**一个会数趟数的假件**：它数的正是生产那个
 * `bakeGlass` 被调了几次 —— 而 T46 起图源只剩「实测到手的那张桌面壁纸」一种，
 * 它的身份（宽高 + 9 枚采样）与像素出自同一次实测，所以这里没有"解了几趟壁纸"那笔
 * 分开的账可数（改前那一条挂在自选那张的 `decodeSampledWallpaper` 上，路已拆）。
 */
class WidgetBackgroundRendererCacheTest {

    // ---- 造键的公共底版：每一格只改它点名的那枚分量，其余全部照抄 ----

    /** 一次实测到手的桌面壁纸：尺寸正常、9 枚采样互不相同。 */
    private val measuredSource = GlassSourceIdentity(
        widthPx = 1080,
        heightPx = 1920,
        samples = List(9) { 0x11223344 + it },
    )

    private val baseSize = BakeSize(480, 320)
    private val baseRadii = BakedCornerRadius(52.5f, 43.75f)
    private fun glass(alphaPercent: Int) = glassTintArgb(0xFF16203A.toInt(), alphaPercent)
    private val baseGlass = glass(80)

    private fun key(
        source: GlassSourceIdentity = measuredSource,
        size: BakeSize = baseSize,
        radii: BakedCornerRadius = baseRadii,
        glassArgb: Int = baseGlass,
        nightMode: Boolean = false,
    ): GlassBakeKey? = glassBakeKey(source, size, radii, glassArgb, nightMode)

    /** 上限就是改前那两枚常量：出图永远不许比它还大（binder 事务尺寸风险）。 */
    private fun bakeAtCeiling(size: WidgetSizePx?): BakeSize =
        bakeSizePx(size, WidgetBackgroundRenderer.TARGET_WIDTH, WidgetBackgroundRenderer.TARGET_HEIGHT)

    /** 假烘焙：每被调一次就是一趟「取图源 + 解码 + 三次位图分配 + 画一张画布」。 */
    private class Trips {
        var count = 0
        fun bake(result: String): () -> String = { count++; result }
        fun fail(): () -> String? = { count++; null }
    }

    // ==================== ① 键的每一项分量变化都必须判成未命中 ====================

    /**
     * 逐枚分量各钉一格：换一张桌面壁纸（同尺寸、9 个采样里有 1 点不同）/ 换了尺寸 /
     * 换出图宽高 / 改透明度 / 改两轴圆角 / 换深浅色。
     *
     * 漏一枚就是一个"陈旧画面"的形：用户拨了滑杆而桌面不动 —— 那正是
     * `fed750f`（alpha 真的进颜色）与 `c05b98f`+`085ef42`（圆角取数）刚还掉的债，
     * 不许缓存把它原样还回去。
     */
    @Test
    fun everyComponentThatChangesPixelsIsAMiss() {
        val baseline = requireNotNull(key())
        val cases = listOf(
            "壁纸换了、尺寸一模一样（9 个采样点里有 1 点不同）" to requireNotNull(
                key(source = measuredSource.copy(samples = measuredSource.samples.toMutableList().also { it[4] += 1 })),
            ),
            "桌面壁纸换了尺寸" to requireNotNull(key(source = measuredSource.copy(widthPx = 1440))),
            "换出图宽" to requireNotNull(key(size = BakeSize(479, 320))),
            "换出图高" to requireNotNull(key(size = BakeSize(480, 319))),
            "透明度滑杆从 80 拨到 60" to requireNotNull(key(glassArgb = glass(60))),
            "横轴圆角变了 1px" to requireNotNull(key(radii = BakedCornerRadius(53.5f, 43.75f))),
            "纵轴圆角变了 1px" to requireNotNull(key(radii = BakedCornerRadius(52.5f, 44.75f))),
            "桌面从浅色翻到深色" to requireNotNull(key(nightMode = true)),
        )
        for ((label, other) in cases) {
            assertNotEquals("$label：被判成同一格，缓存会把旧画面发出去", baseline, other)
        }
        // 签名少一格就不许还是同一张壁纸：换壁纸时那一点差别可能就是唯一看得出来的证据
        assertNotEquals(
            "签名少一格就被判成同一张壁纸",
            baseline,
            baseline.copy(source = baseline.source.copy(samples = baseline.source.samples.drop(1))),
        )
    }

    /** 同一切输入第二次进来必须判成**同一格**（否则缓存等于没有）。 */
    @Test
    fun identicalInputsBuildTheSameKey() {
        assertEquals(key(), key())
        assertEquals(
            "身份逐字段抄一份都该还是同一格",
            requireNotNull(key(source = measuredSource)),
            requireNotNull(key(source = measuredSource.copy())),
        )
    }

    /**
     * 内容签名取不到时**拒绝**用缓存：那时身份上只剩宽高，
     * 用户换一张**同尺寸**的壁纸会被判成同一格 —— 宁可白烤，不可糊错。
     *
     * 顺带钉住 T46 那件被重新审过的账：这一格不是"无源"那一格。判到没有图源时
     * `wallpaperForRender` 直接回 null，`render` 在那条键构造之前就返回了，
     * 所以缓存里永远不会存下一格陈旧的"无源"；这里 null 兜的是"取到了图但签不出名"。
     */
    @Test
    fun sourceWithoutContentSignatureBypassesTheCache() {
        assertNull(key(source = measuredSource.copy(samples = emptyList())))
        // 「无源」根本造不出键：空身份（宽高都是 0、没有采样）与签名缺失走同一条 return
        assertNull(key(source = GlassSourceIdentity(0, 0, emptyList())))
    }

    private fun requireNotNull(label: String, value: GlassBakeKey?): GlassBakeKey =
        requireNotNull(value) { label }

    // ==================== ② 同键第二次进来不重复烘焙 ====================

    /** 单实例连续两轮刷新：第二轮一趟都不许跑。 */
    @Test
    fun sameKeySecondTripDoesNotBakeAgain() {
        val cache = GlassBakeCache<String>()
        val trips = Trips()
        val k = requireNotNull(key())

        val first = cache.obtain(k, trips.bake("glass"))
        val second = cache.obtain(k, trips.bake("glass"))

        assertEquals("同键第二次仍在烘焙（也就仍在解码壁纸）", 1, trips.count)
        assertSame("两次交回的不是同一张位图", first, second)
        assertEquals(1, cache.entryCount())
    }

    /**
     * 一轮刷新里三个同壁纸同参数的实例（桌面上最常见的那一形）。
     *
     * 这就是收益的分母：改前 3 趟解码 3 趟烘焙、瞬时 3 张 614,400 B；
     * 改后 1 趟 1 趟 1 张，另外两格拿的是同一个引用。
     */
    @Test
    fun threeInstancesSameKeyBakeOncePerRound() {
        val cache = GlassBakeCache<String>()
        val trips = Trips()
        val k = requireNotNull(key())

        repeat(3) { assertEquals("glass", cache.obtain(k, trips.bake("glass"))) }

        assertEquals("改前 3 趟，改后必须是 1 趟", 1, trips.count)
        assertEquals(1, cache.entryCount())
    }

    /** 三个实例分属两档尺寸（2×1 与 4×2 并排那一形）：两趟，不是三趟，也不来回弹。 */
    @Test
    fun twoBakeSizesOnScreenCostTwoTripsNotThree() {
        val cache = GlassBakeCache<String>()
        val trips = Trips()
        val small = requireNotNull(key(size = bakeSizePx(WidgetSizePx(288.75f, 105f), 480, 320)))
        val large = requireNotNull(key(size = bakeSizePx(WidgetSizePx(946f, 588f), 480, 320)))
        assertEquals(BakeSize(288, 105), BakeSize(small.bakeWidthPx, small.bakeHeightPx))
        assertEquals(BakeSize(480, 320), BakeSize(large.bakeWidthPx, large.bakeHeightPx))

        repeat(2) {
            cache.obtain(small, trips.bake("s"))
            cache.obtain(large, trips.bake("l"))
            cache.obtain(small, trips.bake("s"))
        }

        assertEquals("两档尺寸之间来回弹，说明第二条容量没留住", 2, trips.count)
        assertEquals(2, cache.entryCount())
    }

    /** 烤失败（图源撞空 / 尺寸算崩）不入库：否则一次瞬时失败会被缓存成一整轮的纯色底。 */
    @Test
    fun failedBakeIsNotCached() {
        val cache = GlassBakeCache<String>()
        val trips = Trips()
        val k = requireNotNull(key())

        repeat(2) { assertNull(cache.obtain(k, trips.fail())) }
        assertEquals("失败被缓存下来了，下一次还在直接交 null", 2, trips.count)

        // 下一刻图源好了（用户刚挑了张图），才正常入库
        assertEquals("ok", cache.obtain(k, trips.bake("ok")))
        assertEquals("ok", cache.obtain(k) { error("入库之后不该再烤") })
    }

    // ==================== ③ LRU 逐出边界 ====================

    /** 容量 2 的边界：满两条时不许逐出；第三条进来顶掉最久没被碰的那一条。 */
    @Test
    fun thirdKeyEvictsTheLeastRecentlyUsedOne() {
        val cache = GlassBakeCache<String>()
        val a = requireNotNull(key(glassArgb = glass(10)))
        val b = requireNotNull(key(glassArgb = glass(20)))
        val c = requireNotNull(key(glassArgb = glass(30)))

        cache.obtain(a) { "A" }
        cache.obtain(b) { "B" }
        assertEquals("两条还没满就开始逐出", 2, cache.entryCount())

        assertEquals("C", cache.obtain(c) { "C" })
        assertEquals("超过 2 条还留着", 2, cache.entryCount())

        val trips = Trips()
        // a 是最久未用的那一条：它已经不在缓存里
        assertEquals("A2", cache.obtain(a, trips.bake("A2")))
        assertEquals(1, trips.count)
        assertEquals(2, cache.entryCount())
    }

    /** 命中也算"用过"：读过 a 一次，该被逐出的就是 b 而不是 a。 */
    @Test
    fun cacheReadCountsAsUse() {
        val cache = GlassBakeCache<String>()
        val a = requireNotNull(key(glassArgb = glass(10)))
        val b = requireNotNull(key(glassArgb = glass(20)))
        val c = requireNotNull(key(glassArgb = glass(30)))

        cache.obtain(a) { "A" }
        cache.obtain(b) { "B" }
        assertEquals("A", cache.obtain(a) { error("a 命中了不该再烤") })
        cache.obtain(c) { "C" }

        val trips = Trips()
        assertEquals("A", cache.obtain(a, trips.bake("A2")))
        assertEquals("读过的 a 被当成最久未用逐出了，逐出的方向反了", 0, trips.count)
        assertEquals("B2", cache.obtain(b, trips.bake("B2")))
        assertEquals(1, trips.count)
    }

    /** 容量真做成 1 时逐出更狠：第二条进来第一条就没了（这条是"常量确实被用上"的证据）。 */
    @Test
    fun capacityOneEvictsEveryPreviousKey() {
        val cache = GlassBakeCache<String>(maxEntries = 1)
        val a = requireNotNull(key(glassArgb = glass(10)))
        val b = requireNotNull(key(glassArgb = glass(20)))
        val trips = Trips()

        cache.obtain(a, trips.bake("A"))
        cache.obtain(b, trips.bake("B"))
        assertEquals(1, cache.entryCount())

        cache.obtain(a, trips.bake("A2"))
        assertEquals("容量 1 时第二条没把第一条顶掉", 3, trips.count)
    }

    /** 容量常量钉住：论证写在 [GLASS_CACHE_MAX_ENTRIES] 的注释里，别悄悄改成 1 或 3。 */
    @Test
    fun cacheHoldsAtMostTwoBitmaps() {
        assertEquals(2, GLASS_CACHE_MAX_ENTRIES)
        // 进程内驻留上限：两条 × 480x320 ARGB_8888
        assertEquals(1_228_800L, bytesOf(BakeSize(480, 320)) * GLASS_CACHE_MAX_ENTRIES)
    }

    // ==================== ② 的另一半：按实例真实尺寸出图 ====================

    /**
     * 每一轴各自取真实尺寸，上限压在改前那两枚常量上。
     *
     * 尺寸取的是真会被放到的大小：2×1「下一节课」110x40dp、4×2「今日课程」在
     * density 2.625 的机上是 360.4x224dp（装机反推过的那一格）。
     */
    @Test
    fun bakeSizeFollowsTheInstancesOwnAxesBelowTheLegacyCeiling() {
        val cells = listOf(
            // 2×1「下一节课」110x40dp @2.625 -> 288.75x105px：两轴都在上限以下，照实出图
            WidgetSizePx(288.75f, 105f) to BakeSize(288, 105),
            // 4×2 真身 946x588px：两轴都超上限，仍是 480x320（与改前逐字一致）
            WidgetSizePx(946f, 588f) to BakeSize(480, 320),
            // 一轴超一轴不超：横轴夹住、纵轴照实 —— "两轴一起粗"的那一档不再出现
            WidgetSizePx(946f, 224f) to BakeSize(480, 224),
            // 窄屏 4×2：250x120dp @2.625 -> 656.25x315px
            WidgetSizePx(656.25f, 315f) to BakeSize(480, 315),
        )
        for ((size, expected) in cells) {
            assertEquals("$size -> $expected", expected, bakeAtCeiling(size))
        }
    }

    /**
     * 尺寸缺数时必须与改前逐字一致：一律 480x320。
     *
     * 这一格是整个改动的"兜底不劣化"承诺 —— [WidgetCornerRadii.resolveSizePx] 四档
     * 全落空时（宿主没报 options、也没有 provider 声明值），出的还得是原来那张图，
     * 一个像素都不许漂。
     */
    @Test
    fun missingSizeFallsBackToTheLegacyCanvasExactly() {
        assertEquals(BakeSize(480, 320), bakeAtCeiling(null))
        assertEquals(BakeSize(480, 320), bakeAtCeiling(WidgetSizePx(0f, 0f)))
        assertEquals(
            "负数是崩掉的度量，该走同一条兜底",
            BakeSize(480, 320),
            bakeAtCeiling(WidgetSizePx(-1f, -1f)),
        )
    }

    /** 出图短边短到模糊要崩的程度（1/4 之后剩不了几个像素）就退回上限，别烤一张噪声。 */
    @Test
    fun degenerateAxisFallsBackInsteadOfBakingANoiseCanvas() {
        val tiny = bakeAtCeiling(WidgetSizePx(30f, 500f))
        assertEquals(BakeSize(480, 320), tiny)
        assertTrue("退回去的那一轴仍然要够 1/4 那一步缩", tiny.widthPx / 4 > 0 && tiny.heightPx / 4 > 0)
    }

    /** 向下取整：出图尺寸永远不许超过该轴真实尺寸，也不许超过上限。 */
    @Test
    fun bakeSizeNeverExceedsTheRealAxisSize() {
        for (axisPx in listOf(288.99f, 320.5f, 481.2f, 1000f)) {
            val size = bakeAtCeiling(WidgetSizePx(axisPx, axisPx))
            assertTrue("$axisPx -> 横轴 ${size.widthPx} 超了真实尺寸", size.widthPx.toFloat() <= axisPx)
            assertTrue("$axisPx -> 纵轴 ${size.heightPx} 超了真实尺寸", size.heightPx.toFloat() <= axisPx)
            val legacyCeiling = BakeSize(
                WidgetBackgroundRenderer.TARGET_WIDTH,
                WidgetBackgroundRenderer.TARGET_HEIGHT,
            )
            assertTrue(
                "上限那一档漏夹了",
                size.widthPx <= legacyCeiling.widthPx && size.heightPx <= legacyCeiling.heightPx,
            )
        }
    }

    /**
     * 这条是"最终像素不许变"里最硬的一件：**画布尺寸换了，屏幕上看到的圆角不许换**。
     *
     * 算式：`bake` 给的是 `cornerDp * density / (组件px / 画布px)`，
     * Launcher 再按 `组件px / 画布px` 拉回去 —— 两个数互为逆运算，乘出来恒等于
     * `cornerDp * density`，与画布取 480 还是取 288 无关。所以本卡动的是位图字节数，
     * 动的不是 `c05b98f` + `085ef42` 那两轮刚验收装机的圆角。
     */
    @Test
    fun displayedCornerRadiusIsInvariantToBakeSize() {
        val densities = listOf(2.625f, 2.75f, 3.5f)
        val widgetSizes = listOf(
            WidgetSizePx(288.75f, 105f), // 2×1，出图会缩小
            WidgetSizePx(946f, 588f),    // 4×2 宽屏，出图夹在上限
            WidgetSizePx(656.25f, 315f), // 只夹住一轴
        )
        var cells = 0
        for (density in densities) {
            for (cornerDp in WidgetAppearance.CORNER_RADII_DP) {
                for (size in widgetSizes) {
                    // 只谈物理上画得出来的那一格（判据与 WidgetCornerRadiiTest 同一份）
                    if (cornerDp * 2f > minOf(size.widthPx, size.heightPx) / density) continue
                    cells++
                    val bake = bakeAtCeiling(size)
                    val radii = WidgetCornerRadii.bake(cornerDp, density, bake.widthPx, bake.heightPx, size)
                    // 屏幕上看到的半径 = 画进位图的半径 x 该轴的拉伸倍数
                    val shownX = radii.radiusX * (size.widthPx / bake.widthPx)
                    val shownY = radii.radiusY * (size.heightPx / bake.heightPx)
                    val wanted = cornerDp * density
                    val label = "density=$density corner=${cornerDp}dp 组件=$size 出图=$bake"
                    assertEquals("横轴 $label", wanted, shownX, 0.01f)
                    assertEquals("纵轴 $label", wanted, shownY, 0.01f)
                    // 方向性也一并钉住：屏幕上不超过用户选的那一档（只会偏方不会偏圆）
                    assertTrue("偏圆那一侧 $label", shownX <= wanted + 0.01f)
                    assertTrue("偏圆那一侧 $label", shownY <= wanted + 0.01f)
                }
            }
        }
        assertTrue("一格都没跑到，测试写空了（cells=$cells）", cells >= 12)
    }

    // ==================== 壁纸内容签名的取样点 ====================

    /** 3x3 共 9 点，全部落在位图内，中心那枚必须在正中间（两幅壁纸最容易差出结果的一带）。 */
    @Test
    fun wallpaperSignatureSamplesNineInBoundsPoints() {
        for (dim in listOf(1080 to 1920, 1440 to 3120, 8 to 8)) {
            val (width, height) = dim
            val points = wallpaperSamplePoints(width, height)
            assertEquals("$dim 的取样点数", 9, points.size)
            for ((x, y) in points) {
                assertTrue("$dim 取样点越界 x=$x", x in 0 until width)
                assertTrue("$dim 取样点越界 y=$y", y in 0 until height)
            }
            assertTrue("$dim 没采中心", points.contains(width / 2 to height / 2))
            // 边缘那一圈不许采（状态栏压暗、启动器遮罩都在那儿，换了壁纸也不动）
            assertTrue("$dim 采到边缘了", points.none { (x, y) -> x == 0 && y == 0 && width > 8 })
        }
    }

    /** 拿不到位图尺寸就交空表 —— [glassBakeKey] 据此拒绝缓存，而不是交出一组假签名。 */
    @Test
    fun wallpaperSignatureIsEmptyWhenThereIsNoBitmapSize() {
        assertEquals(emptyList<Pair<Int, Int>>(), wallpaperSamplePoints(0, 1920))
        assertEquals(emptyList<Pair<Int, Int>>(), wallpaperSamplePoints(1080, 0))
        assertEquals(emptyList<Pair<Int, Int>>(), wallpaperSamplePoints(-1, -1))
        assertEquals(listOf(0 to 0), wallpaperSamplePoints(1, 1))
    }

    // ==================== 收益的字节账 ====================

    /**
     * 位图字节：ARGB_8888 = 4 B/px。
     *
     * 480x320 那一格必须正好是 614,400 B —— 真机 `dumpsys appwidget` 报的就是这个数，
     * 它是"线上确实走这条烘焙路"的证据，也是本卡全部收益的分母。
     */
    @Test
    fun bitmapBytesMatchTheDumpsysReading() {
        assertEquals(614_400L, bytesOf(BakeSize(480, 320)))
        assertEquals(
            "2×1「下一节课」那一档省下来的字节",
            120_960L,
            bytesOf(bakeAtCeiling(WidgetSizePx(288.75f, 105f))),
        )
        assertEquals(
            "比上限更大的组件不许再涨（binder 事务尺寸风险）",
            614_400L,
            bytesOf(bakeAtCeiling(WidgetSizePx(946f, 588f))),
        )
    }

    private fun bytesOf(size: BakeSize): Long = size.widthPx.toLong() * size.heightPx * 4L
}
