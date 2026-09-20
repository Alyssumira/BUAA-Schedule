package com.buaa.schedule.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「玻璃感壁纸背景」到底有没有图源 —— 那条判据的整张表。
 *
 * 为什么要在 JVM 里逐格钉住：这个开关在 Android 14+ 且用户没有 App 内自选壁纸时
 * **彻底静默**（真机实测：把开关从关拨到开，整块组件 tile 的像素差 0 / 258258）。
 * 渲染侧那时返回 null，`applyAppearance` 落到纯色那条分支，而配置页照旧把开关留给用户拨、
 * 只留一段常驻小字，没有任何入口把人引向"去挑一张图"。这就是用户报的「小组件设置调节后不生效」。
 *
 * 所以这张表要同时钉住两件事：
 * 1. **会不会生效**（`usable`）—— 决定配置页那句话是不是在骗人；
 * 2. **不生效是因为哪一条** —— 决定给用户的出路是哪一条（挑图，还是重新打开「使用桌面壁纸」）。
 *
 * 判据本身在 [WidgetGlassSource]（不 import 任何 android 类型），本模块没有 Robolectric，
 * 碰 `WallpaperManager` / `Canvas` 的写法在这里根本跑不起来 —— 抽取的理由见那个文件的头注释。
 */
class WidgetGlassSourceTest {

    /** 一格 = 一个 API 档 x 一枚「使用桌面壁纸」开关 x 有没有自选图 */
    private data class Cell(
        val api34OrNewer: Boolean,
        val useSystemWallpaper: Boolean,
        val hasPickedImage: Boolean,
    ) {
        val systemWallpaperReadable: Boolean get() = !api34OrNewer

        fun decide(): GlassSource = WidgetGlassSource.decide(
            systemWallpaperReadable = systemWallpaperReadable,
            useSystemWallpaper = useSystemWallpaper,
            hasPickedImage = hasPickedImage,
        )

        override fun toString(): String =
            "API ${if (api34OrNewer) "34+" else "≤33"} / 桌面壁纸${if (useSystemWallpaper) "开" else "关"}" +
                " / 自选图${if (hasPickedImage) "有" else "无"}"
    }

    private val cells = listOf(
        Cell(false, true, false),
        Cell(false, true, true),
        Cell(false, false, false),
        Cell(false, false, true),
        Cell(true, true, false),
        Cell(true, true, true),
        Cell(true, false, false),
        Cell(true, false, true),
    )

    /** 卡片点名的那四种组合，一格一个结论，谁也别想靠兜底糊过去 */
    private val expected = mapOf(
        // API<34 + 允许系统壁纸 → 糊桌面壁纸（自选图只作兜底）
        Cell(false, true, false) to GlassSource.SystemWallpaperThenPicked,
        Cell(false, true, true) to GlassSource.SystemWallpaperThenPicked,
        // API>=34 + 无自选 URI → 什么都没有，而且原因必须点名"平台读不到"
        Cell(true, true, false) to GlassSource.NoSourceWallpaperReadBlocked,
        // API>=34 + 有自选 URI → 就糊那一张
        Cell(true, true, true) to GlassSource.PickedImage,
        // 用户关掉「使用桌面壁纸」+ 无自选 URI → 什么都没有，原因是他自己关的
        Cell(false, false, false) to GlassSource.NoSourceSystemWallpaperOff,
        Cell(true, false, false) to GlassSource.NoSourceSystemWallpaperOff,
        // 关掉桌面壁纸但有自选图 → 照旧有图源（这条与上一条的差别只在"他挑过图没有"）
        Cell(false, false, true) to GlassSource.PickedImage,
        Cell(true, false, true) to GlassSource.PickedImage,
    )

    @Test
    fun tableCoversEveryCombinationExactlyOnce() {
        // 防呆：格子表要是被改空/改重复，下面那些 forEach 会全体空转、测试照样绿
        assertEquals("三枚布尔变量 = 8 格", 8, cells.size)
        assertEquals("8 格不重复", 8, cells.toSet().size)
        assertEquals("每格都得有预期结论", 8, expected.size)
        assertEquals("格子表与预期表得是同一批格子", cells.toSet(), expected.keys.toSet())
    }

    @Test
    fun everyCellAnswersTheSourceTable() {
        cells.forEach { cell ->
            assertEquals("$cell 的图源判错了", expected.getValue(cell), cell.decide())
        }
    }

    @Test
    fun apiBelow34WithSystemWallpaperAllowedGivesGlass() {
        // 卡片点名的组合 1
        val source = WidgetGlassSource.decide(
            systemWallpaperReadable = true,
            useSystemWallpaper = true,
            hasPickedImage = false,
        )
        assertEquals(GlassSource.SystemWallpaperThenPicked, source)
        assertTrue("$source：这条组合上玻璃该生效", source.usable)
    }

    @Test
    fun api34WithoutPickedImageGivesNoGlassAndSaysWhy() {
        // 卡片点名的组合 2 —— 真机实测那 0/258258 像素差来自这一格
        val source = WidgetGlassSource.decide(
            systemWallpaperReadable = false,
            useSystemWallpaper = true,
            hasPickedImage = false,
        )
        assertEquals(
            "Android 14+ 读不到桌面壁纸、用户又没挑图时，判据说有图 = 配置页在骗人",
            GlassSource.NoSourceWallpaperReadBlocked,
            source,
        )
        assertFalse("$source：这一格玻璃画不出来，不许答 usable", source.usable)
    }

    @Test
    fun api34WithPickedImageGivesGlassFromThatImage() {
        // 卡片点名的组合 3
        val source = WidgetGlassSource.decide(
            systemWallpaperReadable = false,
            useSystemWallpaper = true,
            hasPickedImage = true,
        )
        assertEquals(GlassSource.PickedImage, source)
        assertTrue("$source：挑完图当场就该有玻璃", source.usable)
    }

    @Test
    fun systemWallpaperTurnedOffWithoutPickedImageGivesNoGlass() {
        // 卡片点名的组合 4：撞空的是他自己那枚开关，所以原因要指回开关，而不是"平台读不到"
        val fromOldApi = WidgetGlassSource.decide(
            systemWallpaperReadable = WidgetGlassSource.systemWallpaperReadable(33),
            useSystemWallpaper = false,
            hasPickedImage = false,
        )
        val fromNewApi = WidgetGlassSource.decide(
            systemWallpaperReadable = WidgetGlassSource.systemWallpaperReadable(34),
            useSystemWallpaper = false,
            hasPickedImage = false,
        )
        assertEquals(GlassSource.NoSourceSystemWallpaperOff, fromOldApi)
        assertEquals(
            "用户关了「使用桌面壁纸」时，撞空的原因就是他自己的选择，与 API 档次无关",
            fromOldApi,
            fromNewApi,
        )
        assertFalse("$fromOldApi：这一格玻璃画不出来", fromOldApi.usable)
    }

    @Test
    fun theTwoNoSourceReasonsStayDistinctAndEachPointsAtItsOwnWayOut() {
        // "挑一张图" vs "重新打开『使用桌面壁纸』或挑一张图"：合并成一格就少给一条出路
        val blocked = GlassSource.NoSourceWallpaperReadBlocked
        val disabled = GlassSource.NoSourceSystemWallpaperOff
        assertNotEquals("两格撞成了同一个值", blocked, disabled)
        assertFalse(blocked.usable)
        assertFalse(disabled.usable)
        val usable = listOf(GlassSource.SystemWallpaperThenPicked, GlassSource.PickedImage)
        usable.forEach { assertTrue("$it 该答 usable", it.usable) }
        assertEquals(
            "usable 只能是那两格图源（多一格少一格都说明枚举与判据对不上了）",
            usable.toSet(),
            GlassSource.entries.filter { it.usable }.toSet(),
        )
    }

    @Test
    fun pickingAnImageNeverJumpsTheQueueInFrontOfTheSystemWallpaper() {
        // 既有的取源顺序（开关开着时优先系统源）不许被这次改动换掉
        assertEquals(
            "Android 13 及以下、开关开着、也挑了图 → 仍走系统源",
            GlassSource.SystemWallpaperThenPicked,
            WidgetGlassSource.decide(
                systemWallpaperReadable = true,
                useSystemWallpaper = true,
                hasPickedImage = true,
            ),
        )
    }

    @Test
    fun sdkBoundaryIsThePlatformRule() {
        assertTrue(WidgetGlassSource.systemWallpaperReadable(33))
        assertTrue(WidgetGlassSource.systemWallpaperReadable(0))
        assertFalse(WidgetGlassSource.systemWallpaperReadable(34))
        assertFalse(WidgetGlassSource.systemWallpaperReadable(36))
        assertEquals("闸门就是 Android 14 那一档", 34, WidgetGlassSource.MIN_SDK_SYSTEM_WALLPAPER_UNREADABLE)
    }

    @Test
    fun blankUriIsNotAnImageSource() {
        assertFalse(WidgetGlassSource.hasPickedImage(null))
        assertFalse(WidgetGlassSource.hasPickedImage(""))
        assertFalse(WidgetGlassSource.hasPickedImage("   "))
        assertTrue(WidgetGlassSource.hasPickedImage("content://media/external/images/1"))
    }

    @Test
    fun saveOnlyNeedsAWidgetRedrawWhenTheTwoKeysTheWidgetReadsChange() {
        // 组件那张玻璃底只读 wallpaper_uri / wallpaper_use_system；其余键（透明度、模糊、
        // 周视图行高……）改了与桌面无关，却要拖着六个组件一起重绘一遍。
        val uri = "content://media/external/images/1"
        val other = "content://media/external/images/2"
        assertFalse(
            "两个键都没动时不该重绘：save() 是十几个调用点共用的落盘口",
            WidgetGlassSource.wallpaperKeysChanged(uri, true, uri, true),
        )
        assertTrue(
            "换了一张图（这正是「App 内换完壁纸、桌面还在糊旧图」那一格）",
            WidgetGlassSource.wallpaperKeysChanged(uri, true, other, true),
        )
        assertTrue(
            "从没有图到挑了图",
            WidgetGlassSource.wallpaperKeysChanged(null, true, uri, true),
        )
        assertTrue(
            "从有图到清除",
            WidgetGlassSource.wallpaperKeysChanged(uri, true, null, true),
        )
        assertTrue(
            "只动了「使用桌面壁纸」那枚开关",
            WidgetGlassSource.wallpaperKeysChanged(uri, true, uri, false),
        )
        assertFalse(
            "null 与空白串是同一格（没有图源），不该白重绘一次",
            WidgetGlassSource.wallpaperKeysChanged(null, false, "   ", false),
        )
        assertFalse(
            "同一条 URI 只是首尾多了空白：还是那张图",
            WidgetGlassSource.wallpaperKeysChanged(" $uri ", true, uri, true),
        )
    }
}
