package com.buaa.schedule.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「玻璃感壁纸背景」到底有没有图源 —— 两张表：实测那件事实怎么判，判完怎么答。
 *
 * 为什么要在 JVM 里逐格钉住：这条链上出过两次「开关能拨、拨完没反应」。
 * 第一次（T31）是渲染侧与配置页各判一次；第二次（T46）是判据的**来历**错了 ——
 * 「这台设备读不读得到桌面壁纸」由一道 `SDK_INT >= 34` 闸门代答，而装机实测把那句
 * 平台断言证伪了（API 36 的镜像上 `getDrawable()` 仍返回真实壁纸，关掉闸门后组件 tile
 * 立刻跟着桌面的亮暗两区走）。闸门一关，组件就永远走"兜底"那档，把 App 内自选的那张图
 * 铺成不透明底面 —— 那次顶上来的是张纯白测试图，桌面上因此出现一块与壁纸毫无关系的
 * (69,77,97) 恒值板。用户报的「修复小部件背景，现在的样子太别扭了」就是那块板。
 *
 * 所以两张表各钉一件事：
 * 1. [WidgetGlassSource.wallpaperLooksUsable] —— 实测拿回来的那东西算不算一张壁纸
 *    （拿不到位图、尺寸退化、采样全同的纯色占位，三种坏形各一格）；
 * 2. [WidgetGlassSource.decide] —— 三态实测结论 x 一枚开关 = 六格，一格一个答案。
 *    其中「还没测」那一格是本卡新立的：它不许被折成"读得到"或"读不到"里的任何一头。
 *
 * 判据本身在 [WidgetGlassSource]（零 android import）。本模块没有 Robolectric，
 * 碰 `WallpaperManager` / `Canvas` 的写法在这里根本跑不起来 —— 抽取理由见那个文件的头注释，
 * 接线形状（渲染侧与配置页问的是同一个答案、实测没躲进主线程）钉在 `WidgetGlassSourceWiringTest`。
 */
class WidgetGlassSourceTest {

    private val nineDistinct = List(9) { 0xFF102030.toInt() + it }
    private val nineWhite = List(9) { 0xFFFFFFFF.toInt() }

    /** 「实测结论」这一枚入参的全部来历：三格实测事实（其中纯色占位与尺寸退化同答一格）+ 第三态 = 还没测过 */
    private val measurements: List<Pair<String, Boolean?>> = listOf(
        "实测到一张正常壁纸" to WidgetGlassSource.wallpaperLooksUsable(1080, 1920, nineDistinct),
        "实测拿不到位图" to WidgetGlassSource.wallpaperLooksUsable(0, 0, emptyList()),
        "实测到的位图尺寸退化" to WidgetGlassSource.wallpaperLooksUsable(1080, 0, nineDistinct),
        "实测到的是一张纯色占位图" to WidgetGlassSource.wallpaperLooksUsable(1080, 1920, nineWhite),
        "还没实测（探针没判过 / 已过采信期）" to null,
    )

    private fun answer(measurement: Boolean?, useSystemWallpaper: Boolean) =
        WidgetGlassSource.decide(systemWallpaperUsable = measurement, useSystemWallpaper = useSystemWallpaper)

    // ==================== ① 实测那件事实怎么判 ====================

    @Test
    fun aRealWallpaperBitmapPasses() {
        assertTrue(WidgetGlassSource.wallpaperLooksUsable(1080, 1920, nineDistinct))
        // 横屏/方形屏都算：判据不看宽高比，壁纸是旋转过的还是方的与"能不能糊"无关
        assertTrue(WidgetGlassSource.wallpaperLooksUsable(1920, 1080, nineDistinct))
        assertTrue(WidgetGlassSource.wallpaperLooksUsable(1080, 1080, nineDistinct))
    }

    @Test
    fun noBitmapAtAllIsNotASource() {
        // 探针在 `getDrawable()` 返回 null、或采样整条抛掉时交的就是这一格
        assertFalse(WidgetGlassSource.wallpaperLooksUsable(0, 0, emptyList()))
        assertFalse(
            "尺寸正常但一枚像素都没取到（硬件位图 getPixel 抛）也算没拿到图",
            WidgetGlassSource.wallpaperLooksUsable(1080, 1920, emptyList()),
        )
    }

    @Test
    fun degenerateSizeIsNotAWallpaper() {
        assertFalse("一轴为 0", WidgetGlassSource.wallpaperLooksUsable(1080, 0, nineDistinct))
        assertFalse("一轴为负", WidgetGlassSource.wallpaperLooksUsable(-1, 1920, nineDistinct))
        assertFalse("两轴都为负", WidgetGlassSource.wallpaperLooksUsable(-1, -1, nineDistinct))
        assertFalse(
            "短到下限以下：1/4 那一步缩完就不剩东西了",
            WidgetGlassSource.wallpaperLooksUsable(
                WidgetGlassSource.MIN_WALLPAPER_EDGE_PX - 1,
                1920,
                nineDistinct,
            ),
        )
        assertTrue(
            "下限本身含在内",
            WidgetGlassSource.wallpaperLooksUsable(
                WidgetGlassSource.MIN_WALLPAPER_EDGE_PX,
                WidgetGlassSource.MIN_WALLPAPER_EDGE_PX,
                nineDistinct,
            ),
        )
    }

    @Test
    fun solidColorPlaceholderIsNotAWallpaper() {
        // 卡片点名的第三格拒绝：9 枚采样全同 = 纯色位图，不是壁纸。
        // 平台在壁纸不可读时塞给应用的就是这种占位图；装机实测里它还被一张纯白测试图
        // 顶出过 (69,77,97) 恒值死板 —— 那一块板正是这张卡修的东西。
        assertFalse(
            "纯白占位：九枚采样全同，判「没有源」，让组件走纯色半透明那条分支",
            WidgetGlassSource.wallpaperLooksUsable(1080, 1920, nineWhite),
        )
        assertFalse(
            "纯黑桌面同理 —— 而它不吃亏：纯色糊底与纯色半透明在像素上几乎同形，" +
                "后者反而是装机实测里桌面透得过来的那一形",
            WidgetGlassSource.wallpaperLooksUsable(1080, 1920, List(9) { 0xFF000000.toInt() }),
        )
        assertFalse(
            "1x1 占位位图：尺寸退化与采样全同两格同时命中",
            WidgetGlassSource.wallpaperLooksUsable(1, 1, List(9) { 0xFF808080.toInt() }),
        )
        // 只有一枚采样也算全同（采样整条崩掉的另一种出口，不许漏进"有源"）
        assertFalse(WidgetGlassSource.wallpaperLooksUsable(1080, 1920, listOf(0xFF102030.toInt())))
        assertTrue(
            "9 枚里哪怕只有 1 枚不同，就是一张有内容的图 —— 判据咬的是「全同」，不是「够花」",
            WidgetGlassSource.wallpaperLooksUsable(1080, 1920, nineWhite.toMutableList().also { it[8] = 0 }),
        )
    }

    @Test
    fun theEdgeFloorIsTheBakePipelinesOwnLimit() {
        // 改这道闸要先重算烘焙那一步：图源会被重采样到出图尺寸的 1/4，
        // 64px 的源缩完只剩 16px —— 与烘焙画布那条下限（MIN_BAKE_AXIS_PX）同一条线。
        assertEquals(64, WidgetGlassSource.MIN_WALLPAPER_EDGE_PX)
        assertEquals(
            "两条下限同出一条「1/4 之后还剩不剩东西」的账，漂开一格就说明有一头没跟着改",
            MIN_BAKE_AXIS_PX,
            WidgetGlassSource.MIN_WALLPAPER_EDGE_PX,
        )
    }

    // ==================== ② 判完怎么答（六格全钉） ====================

    @Test
    fun everyCellOfTheThreeStateTableAnswers() {
        // 三态实测 x 两档开关 = 8 格？不：开关那一档先短路，所以"开关关着"时三态同答
        // 一格 —— 这一点本身就是要钉住的（关着还去分实测结论，等于替一个没问的问题编答案）
        val expected: Map<Pair<Boolean?, Boolean>, GlassSource> = mapOf(
            (true to true) to GlassSource.SystemWallpaper,
            (false to true) to GlassSource.NoSourceSystemWallpaperUnusable,
            (null to true) to GlassSource.NotMeasuredYet,
            (true to false) to GlassSource.NoSourceSystemWallpaperOff,
            (false to false) to GlassSource.NoSourceSystemWallpaperOff,
            (null to false) to GlassSource.NoSourceSystemWallpaperOff,
        )
        assertEquals("三态 x 两档里被短路吃掉的那两格不许混进表里", 6, expected.size)
        measurements.forEach { (label, measurement) ->
            listOf(true, false).forEach { useSystem ->
                val key = measurement to useSystem
                assertEquals(
                    "$label + 桌面壁纸开关${if (useSystem) "开" else "关"} 答错了",
                    expected.getValue(key),
                    answer(measurement, useSystem),
                )
            }
        }
    }

    @Test
    fun notMeasuredIsItsOwnAnswerAndIsNeverFolded() {
        // 卡片点名的那一格：默认值折成任何一头都是替设备编答案
        assertEquals(GlassSource.NotMeasuredYet, answer(null, true))
        assertNotEquals(
            "把「还没测」折成「读得到」：新进程的组件配置页会承诺一个还没问出来的结果",
            GlassSource.SystemWallpaper,
            answer(null, true),
        )
        assertNotEquals(
            "把「还没测」折成「读不到」：那台设备其实读得到，只是我们还没问",
            GlassSource.NoSourceSystemWallpaperUnusable,
            answer(null, true),
        )
    }

    @Test
    fun theFourAnswersStayDistinct() {
        // 每一格都要能被配置页单独指认：合并任意两格都会少说一句话或多许诺一条出路
        val all = listOf(
            answer(true, true),
            answer(false, true),
            answer(null, true),
            answer(true, false),
        )
        assertEquals("四格答案撞成了 ${all.distinct().size} 格", 4, all.distinct().size)
        assertEquals(
            "枚举与判据对不上了（加一格就要连着改配置页那句 when）",
            setOf(
                GlassSource.SystemWallpaper,
                GlassSource.NotMeasuredYet,
                GlassSource.NoSourceSystemWallpaperUnusable,
                GlassSource.NoSourceSystemWallpaperOff,
            ),
            GlassSource.entries.toSet(),
        )
    }

    @Test
    fun measuredUnusableSaysSoAndPointsAtTheDeviceNotAtTheUser() {
        val source = answer(false, true)
        assertEquals(GlassSource.NoSourceSystemWallpaperUnusable, source)
        assertNotEquals(
            "「设备读不到」与「用户自己关了」合并成一格，就会对前者许诺它兑现不了的出路",
            GlassSource.NoSourceSystemWallpaperOff,
            source,
        )
    }

    @Test
    fun switchTurnedOffWinsBeforeAnythingIsMeasured() {
        // 顺序本身也要钉：这一格在渲染侧连那次 binder 问图都省掉，
        // 反过来（先测再判开关）会把一笔没有意义的设备开销留在每条刷新路上。
        listOf(true, false, null).forEach { measurement ->
            assertEquals(
                "开关关着时实测结论不许翻面答案（measurement=$measurement）",
                GlassSource.NoSourceSystemWallpaperOff,
                answer(measurement, false),
            )
        }
    }

    // ==================== ③ 换壁纸开关那一格 ====================

    @Test
    fun saveOnlyNeedsAWidgetRedrawWhenTheSwitchTheWidgetReadsFlips() {
        // 组件读的全局键只剩 wallpaper_use_system：自选那张从 T46 起不再是组件图源，
        // 换它不换组件一个像素都不该动。而 save() 是十几个调用点共用的落盘口，
        // 分不出这一格的话每拖一次滑块都要重绘六个组件。
        assertFalse(
            "开关没动就不该重绘",
            WidgetGlassSource.wallpaperSwitchChanged(savedUseSystem = true, currentUseSystem = true),
        )
        assertFalse(
            "关着再关一次也不该重绘",
            WidgetGlassSource.wallpaperSwitchChanged(savedUseSystem = false, currentUseSystem = false),
        )
        assertTrue(
            "关 -> 开：这一枪还得负责把「读不到」那格旧结论冲掉（重绘会无条件重测）",
            WidgetGlassSource.wallpaperSwitchChanged(savedUseSystem = false, currentUseSystem = true),
        )
        assertTrue(
            "开 -> 关",
            WidgetGlassSource.wallpaperSwitchChanged(savedUseSystem = true, currentUseSystem = false),
        )
    }
}
