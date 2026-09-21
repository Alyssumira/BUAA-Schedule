package com.buaa.schedule.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「玻璃感壁纸背景」到底有没有图源、图源给到哪一档 —— 三张表：
 * 实测那件事实怎么判、判完怎么答、只有主色可用时那块板画成什么颜色。
 *
 * 为什么要在 JVM 里逐格钉住：这条链上出过两次「开关能拨、拨完没反应」。
 * 第一次（T31）是渲染侧与配置页各判一次；第二次（T46）是判据的**来历**错了 ——
 * 「这台设备读不读得到桌面壁纸」由一道 `SDK_INT >= 34` 闸门代答。而 T46 当时写下的那句
 * 订正（"装机实测证伪了平台断言：API 36 上 `getDrawable()` 仍返回真实壁纸，关掉闸门后
 * tile 立刻跟着桌面的亮暗两区走 (43,57,88)/(21,29,51)"）**本领也是错的**：那两组数是
 * 无源时那块半透明板透出来的桌面像素，量的是桌面而不是玻璃。真因是权限 + app-op 两道闸
 * （本应用从未声明 `READ_EXTERNAL_STORAGE`），逐条读数见 `docs/KNOWN_ISSUES.md` §1 ——
 * 也就是说组件这条链从来没有糊到过一张真壁纸，"闸门白关一半设备"关掉的是一条本来就
 * 撞权限的路。T47 因此不去救那个假口径，而是给无源那一档接上**零权限问得到的主色**。
 *
 * 三张表各钉一件事：
 * 1. [WidgetGlassSource.wallpaperLooksUsable] —— 实测拿回来的那东西算不算一张壁纸
 *    （拿不到位图、尺寸退化、采样全同的纯色占位，三种坏形各一格）；
 * 2. [WidgetGlassSource.decide] —— 三态实测结论 x 主色有没有 x 一枚开关，一格一个答案。
 *    其中「还没测」那一格不许被折成"读得到"或"读不到"里的任何一头；
 * 3. [WidgetGlassSource.palettePlateArgb] —— 只有主色可用时那块板画成什么颜色
 *    （primary 定色相、两色里较暗那格定明度、平局才让 colorHints 投票、不许换墨的一侧、
 *    同侧之内夹进 AA 带）。这一张表是本卡的新判据，四格约束各钉一格。
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

    private fun answer(
        measurement: Boolean?,
        useSystemWallpaper: Boolean,
        hasPalette: Boolean = false,
    ) = WidgetGlassSource.decide(
        systemWallpaperUsable = measurement,
        wallpaperPaletteAvailable = hasPalette,
        useSystemWallpaper = useSystemWallpaper,
    )

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
    fun theFiveAnswersStayDistinct() {
        // 每一格都要能被配置页单独指认：合并任意两格都会少说一句话或多许诺一条出路
        val all = listOf(
            answer(true, true),
            answer(false, true, hasPalette = true),
            answer(false, true, hasPalette = false),
            answer(null, true),
            answer(true, false),
        )
        assertEquals("五格答案撞成了 ${all.distinct().size} 格", 5, all.distinct().size)
        assertEquals(
            "枚举与判据对不上了（加一格就要连着改配置页那句 when）",
            setOf(
                GlassSource.SystemWallpaper,
                GlassSource.SystemWallpaperPaletteOnly,
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

    // ==================== ④ 主色档：三格颜色怎么变成一块板 ====================
    //
    // 本机（buaa36 / API 36）零权限实测到的那三格事实，原样当输入用：
    // primary sRGB(0.204, 0.243, 0.396) = (52, 62, 101)、secondary (16, 15, 25)、colorHints = 6。
    // 逐格读数见 docs/KNOWN_ISSUES.md §1 —— 这张表就是把那次取证的那三格颜色钉进判据。

    @Test
    fun theDevicePaletteBecomesABluePlateInsteadOfTheUsersPreset() {
        val derived = plate(PRIMARY_DEVICE, SECONDARY_DEVICE, HINTS_DEVICE, PRESET_DARK)
        // primary 定色相（偏蓝）、较暗的 secondary 定明度：(52,62,101) 与 (16,15,25) 逐通道对分
        assertEquals(argb(34, 39, 63), derived)
        assertNotEquals(
            "本机改前画的是用户那块固定板（(22,32,58) 恒值），" +
                "推导色若还等于它就说明这一档根本没接上桌面 —— 开关照旧是静默 no-op",
            PRESET_DARK and 0xFFFFFF,
            derived and 0xFFFFFF,
        )
        // 卡片要求「文字可读性不许劣化」：改前/改后都按同一条算式复算白字对比度。
        // 16.13:1 -> 14.69:1，两头都远在 AA 之上（本机白字那一格）。
        assertEquals(16.13f, contrastVsWhite(luma(PRESET_DARK)), 0.05f)
        assertEquals(14.69f, contrastVsWhite(luma(derived)), 0.05f)
        assertTrue(
            "白字 AA：${contrastVsWhite(luma(derived))}",
            contrastVsWhite(luma(derived)) >= AA_RATIO,
        )
        assertTrue(
            "近黑字这一格在本机吃的是「跟着暗桌面走」，白字那条带不约束它，但也不许压到看不见",
            contrastVsDarkInk(luma(derived)) >= 1.0f,
        )
    }

    @Test
    fun hintsDoNotTouchPaletteWhenTheTwoColorsAlreadyDiffer() {
        // 本机 hints = 6：里面第三格（bit 4）语义根本没取证，拿它当判据就是编。
        // 判据的立场是「实测两色比一枚建议位可信」—— 两色分得出明暗时 hints 一个通道都不碰。
        val baseline = plate(PRIMARY_DEVICE, SECONDARY_DEVICE, HINTS_DEVICE, PRESET_DARK)
        (0..7).forEach { hints ->
            assertEquals(
                "hints=$hints 参与了主色档的算法（两色明明分得出明暗）",
                baseline,
                plate(PRIMARY_DEVICE, SECONDARY_DEVICE, hints, PRESET_DARK),
            )
        }
        // 反面对照：这一格确实走在「非平局」那一支上（副色与主色差得远超 0.02 的平局线）
        assertTrue(
            WidgetGlassSource.srgbLuminance(PRIMARY_DEVICE) -
                WidgetGlassSource.srgbLuminance(SECONDARY_DEVICE) >=
                WidgetGlassSource.PALETTE_TIE_LUMA_GAP,
        )
    }

    @Test
    fun aVeryBrightPrimaryNeverFlipsTheInkSide() {
        // 一张接近纯白的壁纸：primary 亮，混合色也亮。用户如果强制了浅色墨
        // （"深玻璃"那种暗板预设），把板换到亮侧就是白字压白板 —— 整格退回预设。
        val brightPrimary = rgb(242, 244, 248)
        val brightSecondary = rgb(16, 16, 20)
        assertEquals(
            "亮主色 x 暗预设：跨侧，退回用户自己选的那块板（行为与改前逐格一致）",
            PRESET_DARK and 0xFFFFFF,
            plate(brightPrimary, brightSecondary, 0, PRESET_DARK) and 0xFFFFFF,
        )
        // 同一张桌面碰上亮侧预设：不跨侧，于是桌面色进得来（这一格证明上一格不是"永远退回"）
        val onLightSide = plate(brightPrimary, rgb(216, 220, 230), 0, PRESET_LIGHT)
        assertEquals(argb(229, 232, 239), onLightSide)
        assertNotEquals(PRESET_LIGHT and 0xFFFFFF, onLightSide and 0xFFFFFF)
        assertTrue("亮侧那一格吃的是黑字：AA 地板", contrastVsDarkInk(luma(onLightSide)) >= AA_RATIO)
    }

    @Test
    fun onlyATieBetweenTheTwoColorsLetsColorHintsVote() {
        // 平局（含副色为 null）时建议位是唯一的来历：置 DARK_THEME 向黑压一档，
        // 置 DARK_TEXT 向白提一档，两枚都置或都不置 = 平台自己没话说。
        val gray = rgb(128, 128, 128)
        assertEquals(argb(96, 96, 96), plate(gray, gray, HINT_DARK_THEME, PRESET_DARKISH))
        assertEquals(
            "本机 hints=6 里的 bit 4 不许被当成第二张票",
            plate(gray, gray, HINT_DARK_THEME, PRESET_DARKISH),
            plate(gray, gray, HINT_DARK_THEME or 4, PRESET_DARKISH),
        )
        assertEquals(
            "两枚都置 = 平台没说清，原样交回（暗预设下这一格会跨侧退回，见下一格）",
            plate(gray, gray, 0, PRESET_DARKISH),
            plate(gray, gray, HINT_DARK_TEXT or HINT_DARK_THEME, PRESET_DARKISH),
        )
        assertEquals(
            "没有 DARK_THEME 这一票时，灰色主色落在亮侧，与暗预设跨侧 -> 退回预设",
            PRESET_DARKISH and 0xFFFFFF,
            plate(gray, gray, 0, PRESET_DARKISH) and 0xFFFFFF,
        )
        // 亮预设那一头：DARK_TEXT 提亮后留在亮侧并被吃到（这一票不是永远白投）
        assertEquals(argb(159, 159, 159), plate(gray, gray, HINT_DARK_TEXT, PRESET_LIGHTISH))
        assertEquals(argb(128, 128, 128), plate(gray, gray, 0, PRESET_LIGHTISH))
        assertEquals(
            "DARK_THEME 向黑压会跨到暗侧 -> 拦下，退回亮预设",
            PRESET_LIGHTISH and 0xFFFFFF,
            plate(gray, gray, HINT_DARK_THEME, PRESET_LIGHTISH) and 0xFFFFFF,
        )
        // 副色为 null（平台只算得出主色）也按平局处理：这时没有实测明暗可看，建议位是唯一来历
        assertEquals(
            "副色缺席时 hints 必须还能投票，否则这一格完全没有来历",
            plate(gray, gray, HINT_DARK_THEME, PRESET_DARKISH),
            plate(gray, null, HINT_DARK_THEME, PRESET_DARKISH),
        )
    }

    @Test
    fun theSameSideStillHasToClearTheAaBand() {
        // 同侧不等于安全：暗侧里 luma 0.191 的板离白字 AA 天花板（0.183）只差一点，
        // 亮侧里 0.205 的板也低于黑字地板（0.212）—— 两头都要在对分压里被夹进来。
        val darkSide = plate(rgb(121, 121, 121), rgb(121, 121, 121), 0, PRESET_DARKISH)
        assertEquals(argb(61, 61, 61), darkSide)
        assertTrue(
            "暗侧压不进白字 AA 带就说明第 4 步没接上：${luma(darkSide)}",
            luma(darkSide) <= WidgetGlassSource.PLATE_LIGHT_INK_MAX_LUMA,
        )
        val lightSide = plate(rgb(125, 125, 125), rgb(125, 125, 125), 0, PRESET_LIGHTISH)
        assertEquals(argb(190, 190, 190), lightSide)
        assertTrue(
            "亮侧同理：${luma(lightSide)}",
            luma(lightSide) >= WidgetGlassSource.PLATE_DARK_INK_MIN_LUMA,
        )
    }

    @Test
    fun primarySetsTheHueAndTheDarkerColorOnlySetsLightness() {
        // 一张红壁纸（primary 饱和的红）配一档很暗的蓝黑副色：
        // 色相必须是红的（primary 参与每一通道），明度被暗那档拖下去 —— 两块信息各管一件事。
        val derived = plate(rgb(200, 40, 40), rgb(10, 10, 60), 0, PRESET_DARK)
        val (r, g, b) = channels(derived)
        assertEquals(argb(105, 25, 50), derived)
        assertTrue("primary 的色相（红）不许被副色吃掉：r=$r g=$g b=$b", r > g && r > b)
        assertTrue(
            "较暗的副色负责压明度：${luma(derived)} < ${luma(rgb(200, 40, 40))}",
            luma(derived) < luma(rgb(200, 40, 40)),
        )
    }

    @Test
    fun thePaletteArithmeticIgnoresTheHighByteAndAlwaysReturnsOpaque() {
        val fromColorInt = plate(0xFF343E65.toInt(), 0xFF100F19.toInt(), HINTS_DEVICE, 0xFF16203A.toInt())
        val fromRgbInt = plate(PRIMARY_DEVICE, SECONDARY_DEVICE, HINTS_DEVICE, PRESET_DARK)
        assertEquals("高字节参与了运算（AlphaMask 会串进通道）", fromRgbInt, fromColorInt)
        listOf(
            plate(PRIMARY_DEVICE, SECONDARY_DEVICE, HINTS_DEVICE, PRESET_DARK),
            plate(rgb(128, 128, 128), rgb(128, 128, 128), 0, PRESET_LIGHTISH),
            plate(rgb(128, 128, 128), null, 0, PRESET_DARKISH),
            plate(rgb(242, 244, 248), rgb(16, 16, 20), 0, PRESET_DARK),
        ).forEachIndexed { index, argb ->
            assertEquals(
                "第 $index 格丢了 alpha：浓淡仍由 alphaFraction 那条链管，这里必须交回不透明色",
                0xFF000000.toInt() ushr 24,
                argb ushr 24,
            )
        }
    }

    @Test
    fun theThreeLumaLinesDoNotInvertAndEachSideClearsAa() {
        // 三条线的分工：交点管「换不换墨的一侧」，两条 AA 线管「同侧之内还能不能读」。
        // 顺序必须是 天花板(暗侧顶线) < 交点 < 地板(亮侧底线) —— 反过来说明有一头的墨
        // 或亮度算式改了而另一头没跟着改，第 3、4 步会互相把对方夹掉。
        val crossover = WidgetGlassSource.PLATE_LUMA_CROSSOVER
        val lightMax = WidgetGlassSource.PLATE_LIGHT_INK_MAX_LUMA
        val darkMin = WidgetGlassSource.PLATE_DARK_INK_MIN_LUMA
        assertTrue("天花板顶到交点以上，白字侧就没有可画的板了", lightMax < crossover)
        assertTrue("地板低于交点，暗侧/亮侧的分工会重叠", crossover < darkMin)
        listOf(
            "白字压在暗侧天花板上" to WidgetGlassSource.paletteContrastRatio(1f, lightMax),
            "黑字压在亮侧地板上" to
                WidgetGlassSource.paletteContrastRatio(darkMin, WidgetGlassSource.srgbLuminance(DARK_INK)),
        ).forEach { (label, ratio) ->
            assertTrue("$label 掉到 AA 以下：$ratio", ratio >= AA_RATIO)
            // 留这一句是钉「两条线是贴着 AA 算出来的」，不是随手挑的整数：
            // 真要放宽也得多写一行，而不是悄悄把带子拉宽到吃掉桌面色相。
            assertTrue("$label 离 AA 远到说明这两条线的来历漂了：$ratio", ratio <= AA_RATIO + 0.02f)
        }
    }

    @Test
    fun paletteOnlyLosesToABitmapAndToTheUsersSwitch() {
        // 优先级「位图 > 主色」：实测到位图那一轮根本不该去问色（探针在位图赢时把 palette 置 null），
        // 判据这一头也得钉住同样的排序，两头各漂一格就又会互相覆盖。
        assertEquals(GlassSource.SystemWallpaper, answer(true, true, hasPalette = true))
        assertEquals(GlassSource.SystemWallpaperPaletteOnly, answer(false, true, hasPalette = true))
        assertNotEquals(
            "主色档不许冒充位图档：那一格的用户话是「糊的是壁纸」，而这一档没糊任何东西",
            GlassSource.SystemWallpaper,
            answer(false, true, hasPalette = true),
        )
        // 开关关掉时一律走用户自己选的配色，不许吃桌面主色
        listOf(true, false).forEach { hasPalette ->
            assertEquals(
                "开关关着还去吃桌面主色（hasPalette=$hasPalette）",
                GlassSource.NoSourceSystemWallpaperOff,
                answer(false, false, hasPalette = hasPalette),
            )
        }
        assertEquals(
            "还没测过那一格不许被主色档抢先：色的结论也是实测的一部分",
            GlassSource.NotMeasuredYet,
            answer(null, true, hasPalette = true),
        )
    }

    // ---- 主色档那张表的四格输入（本机实测值 + 两支墨），名字与 KNOWN_ISSUES §1 对齐 ----

    private fun rgb(r: Int, g: Int, b: Int): Int = (r shl 16) or (g shl 8) or b

    private fun argb(r: Int, g: Int, b: Int): Int = 0xFF000000.toInt() or rgb(r, g, b)

    private fun channels(argb: Int): Triple<Int, Int, Int> = Triple(
        (argb ushr 16) and 0xFF,
        (argb ushr 8) and 0xFF,
        argb and 0xFF,
    )

    private fun luma(argb: Int): Float = WidgetGlassSource.srgbLuminance(argb and 0xFFFFFF)

    /** 白字（组件浅色墨 0xFFFFFFFF）压在这块板上的对比度 */
    private fun contrastVsWhite(plateLuma: Float): Float =
        WidgetGlassSource.paletteContrastRatio(1f, plateLuma)

    /** 近黑字（组件深色墨 0xFF14161C）压在这块板上的对比度 */
    private fun contrastVsDarkInk(plateLuma: Float): Float =
        WidgetGlassSource.paletteContrastRatio(plateLuma, WidgetGlassSource.srgbLuminance(DARK_INK))

    private fun plate(primaryRgb: Int, secondaryRgb: Int?, colorHints: Int, presetRgb: Int): Int =
        WidgetGlassSource.palettePlateArgb(
            primaryArgb = primaryRgb,
            secondaryArgb = secondaryRgb,
            colorHints = colorHints,
            presetArgb = presetRgb,
        )

    private companion object {
        /** 本机 `getWallpaperColors(FLAG_SYSTEM)` 的三格读数 */
        const val PRIMARY_DEVICE = 0x343E65
        const val SECONDARY_DEVICE = 0x100F19
        const val HINTS_DEVICE = 6

        /** `WallpaperColors` 的两枚公开建议位（值与平台常量一致，钉在判据的常量上） */
        const val HINT_DARK_TEXT = WidgetGlassSource.HINT_SUPPORTS_DARK_TEXT
        const val HINT_DARK_THEME = WidgetGlassSource.HINT_SUPPORTS_DARK_THEME

        /** 组件那两支墨与用户可选的两块板（暗/亮各一格，另加两格"中间亮度"的色） */
        const val DARK_INK = 0xFF14161C.toInt()
        const val PRESET_DARK = 0x16203A
        const val PRESET_DARKISH = 0x646464
        const val PRESET_LIGHT = 0xFAFBFF
        const val PRESET_LIGHTISH = 0x8282C8

        /** WCAG AA 正文底线，与 App 内 `DesignTokens` 那条同一数 */
        const val AA_RATIO = 4.5f
    }
}
