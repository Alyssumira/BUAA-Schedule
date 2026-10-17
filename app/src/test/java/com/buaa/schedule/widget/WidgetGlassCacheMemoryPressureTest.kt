package com.buaa.schedule.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 卡 T423（甲档）：内存压力「哪一档该交出玻璃烘焙缓存」的档位判据，加上那记清入口的语义。
 *
 * ## 为什么这一族能在纯 JVM 里钉住
 * [widgetGlassCacheReleasesAtTrimLevel] 两枚分量都是参数：`level` 是系统发给本进程的那一档，
 * `backgroundFloor` 是调用点手里的 `ComponentCallbacks2.TRIM_MEMORY_BACKGROUND` 本体 ——
 * 本文件零 android import、不 import `ComponentCallbacks2`，所以那枚常量的数值只是**表里的参数**，
 * 判据本体不认死它。[GlassBakeCache] 是泛型容器，注入的是计数假件（与
 * `WidgetBackgroundRendererCacheTest` 同一写法）：真位图在本模块的单测里造不出来。
 *
 * ## 数值口径（如实）
 * 表里 3 / 6 / 7 / 10 / 20 这五格按 `ComponentCallbacks2` 的在册档位序取（仓内在册的那道门
 * `BUAAApplication.onTrimMemory` 用的就是 BACKGROUND 那一档），本卡不许联网，一手出处没取；
 * 顺序（RUNNING_CRITICAL < UI_HIDDEN < BACKGROUND < MODERATE < COMPLETE）才是判据真正吃的东西 ——
 * 调用点传进来的地板线是符号本体而非这里抄的数，两处若不一致由
 * `WidgetGlassCacheMemoryPressureGuardTest` 那一格点名。
 *
 * ## 钉不住的行为（明说，不许用「覆盖了」这个词）
 * 真机上 HyperOS／MIUI 到底发不发 `onTrimMemory`、发给谁，Launcher 侧对已交付位图的引用何时
 * 松开，位图像素住在哪一片内存 —— 三件都不在本卡的判据面内（本卡零 adb、零设备、不许联网）。
 */
class WidgetGlassCacheMemoryPressureTest {

    // ---- 造键的公共底版：与 WidgetBackgroundRendererCacheTest 同一份分量 ----

    private val measuredSource = GlassSourceIdentity(
        widthPx = 1080,
        heightPx = 1920,
        samples = List(9) { 0x11223344 + it },
    )

    private val baseSize = BakeSize(480, 320)
    private val baseRadii = BakedCornerRadius(52.5f, 43.75f)
    private fun glass(alphaPercent: Int) = glassTintArgb(0xFF16203A.toInt(), alphaPercent)
    private fun key(glassArgb: Int = glass(50)): GlassBakeKey =
        requireNotNull(glassBakeKey(measuredSource, baseSize, baseRadii, glassArgb, nightMode = false))

    // ---- ① 档位表：五档各一格，含「前台两档绝不清」 ----

    private class TierCase(
        val label: String,
        val level: Int,
        val shouldRelease: Boolean,
        val why: String,
    )

    @Test
    fun trimTierTablePinsWhichLevelsReleaseTheGlassCache() {
        val table = listOf(
            // 前台两档：进程还在用户眼前，这一档的位图可能正被 Launcher 显示 —— 绝不清。
            TierCase("RUNNING_CRITICAL", RUNNING_CRITICAL, false, "发给前台进程，用户可能正在抓课表"),
            TierCase("UI_HIDDEN", UI_HIDDEN, false, "切出去又立刻回来时，现烤那笔 CPU 比省下的驻留贵"),
            // 后台三档：只会发给已在后台的进程，组件全被拆掉之后这 1.2 MB 也该放手。
            TierCase("BACKGROUND", BACKGROUND, true, "后台起步档：缺口就是在这儿被点名的"),
            TierCase("MODERATE", MODERATE, true, "系统准备动后台进程"),
            TierCase("COMPLETE", COMPLETE, true, "最狠那一档"),
        )
        for (case in table) {
            assertEquals(
                "${case.label}（level=${case.level}）不该走这条路：${case.why}",
                case.shouldRelease,
                widgetGlassCacheReleasesAtTrimLevel(case.level, BACKGROUND),
            )
        }
    }

    /** 地板线本身那一格：`>=` 而不是 `>` —— 停在 BACKGROUND 那一档就得清。 */
    @Test
    fun theFloorLevelItselfReleasesAndTheLevelJustBelowDoesNot() {
        assertEquals(
            "判据把地板线那一档排除了（写成严格大于）：停在 BACKGROUND 就不清，缺口原样留着",
            true,
            widgetGlassCacheReleasesAtTrimLevel(BACKGROUND, BACKGROUND),
        )
        assertEquals(
            "判据吃进了地板线以下一格：UI_HIDDEN 那一档也会清",
            false,
            widgetGlassCacheReleasesAtTrimLevel(BACKGROUND - 1, BACKGROUND),
        )
    }

    /**
     * 判据不认死 7：地板线是参数。这里把地板线抬到 COMPLETE，BACKGROUND 就得回 false ——
     * 它证明「哪一档起步」这件事由调用点交进来的那枚符号说了算，不在本文件抄第二份。
     */
    @Test
    fun theFloorIsAParameterNotAHardcodedSeven() {
        assertEquals(false, widgetGlassCacheReleasesAtTrimLevel(BACKGROUND, COMPLETE))
        assertEquals(true, widgetGlassCacheReleasesAtTrimLevel(COMPLETE, COMPLETE))
        assertEquals(true, widgetGlassCacheReleasesAtTrimLevel(MODERATE, BACKGROUND))
    }

    // ---- ② 清入口的语义：只丢引用，下次现烤 ----

    /** 新增这一记之后 `entryCount()` 的读数口径：清过就是 0 格（不是"逐出到剩两条"）。 */
    @Test
    fun clearOnMemoryPressureEmptiesEveryEntry() {
        val cache = GlassBakeCache<String>()
        val trips = Trips()
        cache.obtain(key(glass(30)), trips.bake("A"))
        cache.obtain(key(glass(70)), trips.bake("B"))
        assertEquals(2, cache.entryCount())

        cache.clearOnMemoryPressure()
        assertEquals("清入口没把两条都放手（它还握着旧值）", 0, cache.entryCount())
        assertEquals("清场顺手多烤了一趟：清入口只做丢引用这一手", 2, trips.count)
    }

    /** 空容器与清过两次的容器上清场都是 no-op，不会凭空造出一条键。 */
    @Test
    fun clearingAnAlreadyEmptyCacheIsANoOp() {
        val cache = GlassBakeCache<String>()
        val trips = Trips()
        cache.clearOnMemoryPressure()
        cache.clearOnMemoryPressure()
        assertEquals(0, cache.entryCount())
        assertEquals(0, trips.count)
    }

    /** 键不复活：清过之后同一枚键再 obtain 一定现烤（卡面说的「下次 obtain 现烤」那一档）。 */
    @Test
    fun afterClearTheSameKeyBakesAFreshValueInsteadOfRevivingTheOldOne() {
        val cache = GlassBakeCache<String>()
        val trips = Trips()
        val first = requireNotNull(cache.obtain(key(), trips.bake("A-first")))

        cache.clearOnMemoryPressure()
        val second = requireNotNull(cache.obtain(key(), trips.bake("A-second")))

        assertEquals(1, cache.entryCount())
        assertEquals("清过之后同一枚键还回旧值：那条清场根本没丢引用", 2, trips.count)
        assertEquals("旧值与新值不是同一份", "A-first", first)
        assertEquals("A-second", second)
    }

    /**
     * 与在册那两格对齐的读数（谁改的写清楚）：容量与稳态驻留上限**没动**，
     * 动的是「压力档位下可以先到 0」—— 甲档只补清入口，丙档（容量收成 1）在本卡被驳掉。
     */
    @Test
    fun steadyStateCapacityAndResidentCeilingStayUnchanged() {
        assertEquals(2, GLASS_CACHE_MAX_ENTRIES)
        // 稳态上限这一笔仍按 `WidgetBackgroundRendererCacheTest` 的口径：两条 × 480x320 ARGB_8888
        assertEquals(1_228_800L, bytesOf(BakeSize(480, 320)) * GLASS_CACHE_MAX_ENTRIES)
        // 但压力档位一到，这一族可以先到 0：那记清入口是这条路的唯一生产口
        val cache = GlassBakeCache<String>()
        val trips = Trips()
        repeat(2) { cache.obtain(key(glass(30 + it * 40)), trips.bake("X$it")) }
        cache.clearOnMemoryPressure()
        assertEquals(0, cache.entryCount())
    }

    // ---- 小工具 ----

    private class Trips {
        var count = 0
        fun bake(result: String): () -> String = { count++; result }
    }

    private fun bytesOf(size: BakeSize): Long = size.widthPx.toLong() * size.heightPx * 4L

    private companion object {
        /** 以下五枚数值只是**表里的参数**，本文件不 import `ComponentCallbacks2`。 */
        const val RUNNING_CRITICAL = 3
        const val UI_HIDDEN = 6
        const val BACKGROUND = 7
        const val MODERATE = 10
        const val COMPLETE = 20
    }
}
