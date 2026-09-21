package com.buaa.schedule.widget

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「玻璃感壁纸背景」这条链的接线：判据只有一份，说人话的那一句与实测的取舍都在开关手边。
 *
 * 为什么要按源码形状核对：判定本体是 JVM 测得到的（`WidgetGlassSourceTest` 逐格钉那三张表），
 * 但"渲染侧与配置页到底有没有各判一次、实测有没有躲开主线程"这两件事 JVM 跑不到 ——
 * 本模块没有 Robolectric，`getSharedPreferences` / `WallpaperManager` / Compose 组合期
 * 全是抛 "not mocked" 的桩。而漏接线恰好有几种不红的形态：
 * ① 配置页继续自己写一段常驻小字（判据白写，用户照旧拨了没反应 —— T31 修过的那次）；
 * ② 渲染侧留着自己那份 `if (useSystem)` 的老分支（两边各判一次，早晚对不上）；
 * ③ T46 特有的两种新形态：把旧的 `SDK_INT >= 34` 闸门从别的门缝里放回来（实测白做），
 *    或者在配置页主线程上调一次 `measure`（那是一次 binder 问图 + 整屏位图取点）。
 * ④ T47 的三种新形态（每一格各钉一种）：主色档那一问根本没接上（开关仍是静默 no-op）、
 *    为了那块板把两枚相册权限声明/申请回来（本卡的硬约束正相反）、
 *    以及把「只有主色」那一格的用户话说成「糊的是壁纸」（T46 那次错话的订正本领犯的错）。
 *
 * 手法抄 [ColdStartRebuildWiringTest]：读 .kt、按锚点配平取函数体、找不到锚点就抛
 * （静默跳过的守卫等于没有守卫）。
 */
class WidgetGlassSourceWiringTest {

    /** ① 渲染侧不再自己重排取源顺序，也不再用 API 档次代答设备事实 */
    @Test
    fun rendererAsksTheSharedDecisionAndMeasuresInsteadOfGuessing() {
        val code = withoutCommentsKeepingLiterals(readMainSource(RENDERER_FILE))
        val availability = normalize(balancedBlock(code, "internal fun availability(context: Context): GlassSource"))
        val body = normalize(balancedBlock(code, RENDERER_MEASURE_ANCHOR))

        assertEquals(
            "判据被问了两遍（只许 availability 那一处问，配置页再从它拿答案）：\n$code",
            1,
            occurrences(code, "WidgetGlassSource.decide("),
        )
        // 两枚入参都要来自真实状态，不许写死
        assertTrue(
            "「使用桌面壁纸」那枚开关没接进判定：\n$availability",
            availability.contains("KEY_USE_SYSTEM_WALLPAPER"),
        )
        assertTrue(
            "实测结论没接进判定（那这句说明又是一句凭空调的）：\n$availability",
            availability.contains("WidgetWallpaperProbe.memoized()"),
        )
        listOf("?: true", "?: false").forEach { fold ->
            assertTrue(
                "memo 的「没测过」那一格被 $fold 折成了一头 —— 配置页那句说明就此没有出处：" +
                    "\n$availability",
                !availability.contains(fold),
            )
        }
        // 取源顺序：只有"用户关了开关"那一格许在实测之前拦，其余每轮实测（红线：读不到不许记死）
        assertTrue(
            "渲染侧开始信 memo 了 —— 「读不到」那一格会被锁死到配置页翻页为止：\n$body",
            body.contains("!usesSystemWallpaper(context) -> null"),
        )
        assertTrue(
            "取图源这一格不再每轮实测（桌面换壁纸没有任何广播进得来，只有每轮重问才会翻面）：\n$body",
            body.contains("WidgetWallpaperProbe.measure(context)"),
        )
        assertTrue(
            "渲染侧把 memo 当成了取图的短路（它只能当「要不要问」的参考，不能当「有没有图」的答案）：\n$body",
            !body.contains("memoized()"),
        )
    }

    /** ② 那道一刀切的版本闸门在组件这条链上已经彻底拆掉 */
    @Test
    fun theBlanketSdkGateIsGoneFromTheWholeWidgetPackage() {
        val offenders = mutableMapOf<String, MutableList<String>>()
        mainSourceFiles().forEach { file ->
            val code = withoutCommentsKeepingLiterals(file.readText())
            GATED_FORBIDDEN.forEach { needle ->
                if (code.contains(needle)) {
                    offenders.getOrPut(file.name) { mutableListOf() } += needle
                }
            }
        }
        assertTrue(
            "widget 包里又长出（或漏改）了按 API 档次代答壁纸的写法 —— T46 换掉的就是它，" +
                "论证与实测数见 WidgetWallpaperProbe 的头注释：\n$offenders",
            offenders.isEmpty(),
        )
    }

    /** ③ 组件的图源只剩实测到的桌面本身：App 内自选那张不再参与（它不是桌面） */
    @Test
    fun pickedImageIsNoLongerAWidgetSourceAnywhere() {
        val renderer = withoutCommentsKeepingLiterals(readMainSource(RENDERER_FILE))
        val probe = withoutCommentsKeepingLiterals(readMainSource(PROBE_FILE))
        listOf(renderer, probe).forEach { code ->
            FORBIDDEN_SOURCE_NEEDLES.forEach { needle ->
                assertTrue(
                    "组件这条链还在读自选那张（$needle）—— 它铺出来的就是那块 (69,77,97) 恒值板：\n$needle",
                    !code.contains(needle),
                )
            }
        }        // GlassSource 也不许留"有自选图"这一档：留着就有人往里接
        val source = withoutCommentsKeepingLiterals(readMainSource(GLASS_SOURCE_FILE))
        assertEquals(
            "图源判据又被加了一档自选图：\n$source",
            0,
            occurrences(source, "PickedImage"),
        )
    }

    /** ④ 实测点只有一处，且 memo 的三枚写点都在这同一处里 */
    @Test
    fun theProbeIsTheOnlyPlaceThatAsksTheDeviceAndItWritesItsMemoOnBothAnswers() {
        val probe = withoutCommentsKeepingLiterals(readMainSource(PROBE_FILE))
        val measure = normalize(balancedBlock(probe, "fun measure(context: Context): Measurement"))

        assertEquals(
            "探针里判据被问了两遍（判据只许 WidgetGlassSource.wallpaperLooksUsable 那一份）：\n$probe",
            1,
            occurrences(probe, "WidgetGlassSource.wallpaperLooksUsable("),
        )
        assertTrue("实测结论没写进 memo：\n$measure", measure.contains("memoUsable = usable"))
        assertTrue("memo 没时间戳（那 60s 的采信期是空的）：\n$measure", measure.contains("memoAtElapsed ="))
        assertEquals(
            "三枚 memo 字段少一枚 @Volatile —— 六家 Provider 各自在 goAsync 的 IO 协程上写它：\n$probe",
            3,
            occurrences(probe, "@Volatile"),
        )
        assertTrue(
            "判「没有源」时那张我们自画的渲染目标没回收（一次约 8MB）：\n$measure",
            measure.contains("recycleIfOwned()"),
        )
    }

    /** ⑤ 配置页那枚开关读的是同一个答案，而且实测没落到主线程上 */
    @Test
    fun configPageSwitchIsWiredToTheSameJudgementOffTheMainThread() {
        val code = withoutCommentsKeepingLiterals(readMainSource(CONFIG_FILE))
        val panel = balancedBlock(code, PANEL_ANCHOR)

        assertEquals(
            "图源判定被问了两遍（一处问、另一处自己判，就会有两个答案）：\n$code",
            1,
            occurrences(code, "WidgetBackgroundRenderer.availability("),
        )
        assertTrue(
            "「玻璃感壁纸背景」这个 Panel 没用上图源判定 —— 那段说明就又是一句凭空调的：\n$panel",
            panel.contains("glassSource"),
        )
        assertTrue(
            "说明文字没有跟着判定分叉（判到无源时要说实话，有源时不必吓唬人）：\n$panel",
            panel.contains("when (glassSource)"),
        )
        assertTrue(
            "开关被改成了不可拨：这张卡的取舍是「照样能拨、但说明说实话」，" +
                "置灰等于把说明那段又变回没人看见：\n$panel",
            !panel.contains("enabled = false"),
        )
        // 红线：配置页那一屏不许在主线程解全屏壁纸 / 问 binder
        assertEquals(
            "配置页的实测没整段裹进 Dispatchers.IO（那里面是一次 binder 问图 + 整屏位图取点）：\n$code",
            occurrences(code, "WidgetWallpaperProbe.measure("),
            occurrences(code, "withContext(Dispatchers.IO)"),
        )
    }

    /** ⑥ 那四格答案各说一句人话，而且不许再挂那颗兑现不了的挑图按钮（第五格 = 主色档，连同「不许说什么」一起钉在 ⑯） */
    @Test
    fun configPageSaysOneTruthPerAnswerAndStopsOfferingAPicker() {
        val code = withoutCommentsKeepingLiterals(readMainSource(CONFIG_FILE))
        val panel = normalize(balancedBlock(code, PANEL_ANCHOR))

        assertTrue(
            "无源那一档还在许诺「去挑一张图」：自选那张已经不是组件图源，这颗按钮按下去只会改 App 内背景：\n$panel",
            !panel.contains("选择壁纸图片"),
        )
        assertTrue(
            "配置页还留着 SAF 挑图链路（它写的那个键不再影响组件）：\n$code",
            !code.contains("wallpaperLauncher") && !code.contains("rememberLauncherForActivityResult"),
        )
        // 每一格的答案都要**在自己那一支里**说出自己的来历：挪到别支就等于没说
        listOf(
            GlassSource.SystemWallpaper.name to "实测读得到",
            GlassSource.NotMeasuredYet.name to "正在确认",
            GlassSource.NoSourceSystemWallpaperUnusable.name to "实测读不到",
            GlassSource.NoSourceSystemWallpaperOff.name to "使用桌面壁纸",
        ).forEach { (branch, phrase) ->
            val text = branchText(panel, branch)
            assertTrue(
                "$branch 那一支的说明里丢了「$phrase」这格来历：\n$text",
                text.contains(phrase),
            )
        }
    }

    /**
     * 取 `GlassSource.<branch> ->` 那一支的文字（到下一支或这段结束为止）。
     * 整段 `when` 一起含混地查会让四句话互相顶包 —— 少一句也能过，而那正是会被漏掉的那一句。
     */
    private fun branchText(whenBlock: String, branch: String): String {
        val at = whenBlock.indexOf("GlassSource.$branch ->")
        check(at >= 0) { "配置页那句 when 里没有 GlassSource.$branch 这一支：图源加了格要说实话就得跟着加" }
        val next = whenBlock.indexOf("GlassSource.", at + "GlassSource.$branch ->".length)
        return if (next < 0) whenBlock.substring(at) else whenBlock.substring(at, next)
    }

    /** ⑦ 圆角档位行必须换行：6 档在 360dp 宽的屏上被那颗不换行的 Row 吞掉了最后一档 */
    @Test
    fun cornerBucketRowWrapsSoAllSixBucketsAreReachable() {
        val code = withoutCommentsKeepingLiterals(readMainSource(CONFIG_FILE))
        val chipRow = balancedBlock(code, "private fun ChipRow(")

        assertTrue(
            "ChipRow 还是那颗不换行的 Row：实测 360dp 宽的屏上 6 档只渲染出 5 颗，" +
                "28dp 那档既看不见也点不到：\n$chipRow",
            chipRow.contains("FlowRow("),
        )
        // "Row(" 这个词在本段里必然出现两次以上：函数名 ChipRow( 和 FlowRow( 各自都含它。
        // 所以判据只能是"每一次 Row( 都得有个来历"，多出来的那次就是又套了一颗不换行的 Row。
        assertEquals(
            "FlowRow 外面又套了一层不换行的 Row，等于没换行：\n$chipRow",
            occurrences(chipRow, "FlowRow(") + occurrences(chipRow, "ChipRow("),
            occurrences(chipRow, "Row("),
        )
    }

    /** ⑧ 这张卡不许动档位表与默认档（内容、顺序、个数都是判据的一部分） */
    @Test
    fun cornerBucketsAndTheirLabelsStayUntouched() {
        assertEquals(
            "圆角档位的内容与顺序（改了就要跟着改配置页与烘焙那两头）",
            listOf(0, 8, 16, 20, 24, 28),
            WidgetAppearance.CORNER_RADII_DP,
        )
        assertEquals("每档都得有一个标签", WidgetAppearance.CORNER_RADII_DP.size, WidgetAppearance.CORNER_LABELS.size)
        assertEquals("默认档还是那一档", 20, WidgetAppearance.CORNER_RADII_DP[WidgetAppearance.DEFAULT_CORNER_BUCKET])
    }

    /**
     * ⑨ 第 6 档（28dp）以前在配置页上根本点不到，于是它的纯色底素材也从没被验证过。
     *
     * 换行之后它可达了，就得当场钉住"点它真的画出 28dp"：纯色那条分支的圆角完全由
     * `widget_bg_rN.xml` 里的 `<corners android:radius>` 决定（见 `WidgetAppearance` 的
     * `CORNER_DRAWABLES`），漏一张或写错一档都会静默画成别的形状。
     */
    @Test
    fun everyBucketHasItsOwnSolidBackgroundDrawableWithThatRadius() {
        WidgetAppearance.CORNER_RADII_DP.forEachIndexed { index, dp ->
            assertTrue("档位下标越界：$index -> ${dp}dp", index < WidgetAppearance.CORNER_DRAWABLES.size)
            val name = "widget_bg_r$dp.xml"
            val file = File(findResDir(), "drawable/$name")
            assertTrue("第 $index 档（${dp}dp）找不到素材 ${file.path}", file.isFile)
            val xml = file.readText()
            assertTrue(
                "${name} 画出来的不是 ${dp}dp：点这一档看到的圆角会与档位标签对不上\n$xml",
                xml.contains("android:radius=\"${dp}dp\""),
            )
        }
    }

    /**
     * ⑩ 主色档的读点：探针必须真的去问那次零权限的颜色，而且只问桌面那一种。
     *
     * 这一格是整张卡的目的所在 —— 本机位图档永远撞空（权限 + app-op 两道闸，
     * 见 docs/KNOWN_ISSUES.md §1），于是 `getWallpaperColors` 是唯一还活着的桌面信息。
     * 读点丢了，"玻璃感壁纸背景"就又退回那颗静默 no-op（实测的 `w8.blur` true/false
     * 像素差 0 / 530100），而编译与前面几格守卫都不会红。
     */
    @Test
    fun theProbeAsksTheZeroPermissionPaletteOnceAndOnlyForTheDesktop() {
        val probe = withoutCommentsKeepingLiterals(readMainSource(PROBE_FILE))
        val paletteOf = normalize(balancedBlock(probe, "private fun paletteOf(context: Context): Palette?"))
        val measure = normalize(balancedBlock(probe, "fun measure(context: Context): Measurement"))

        assertTrue(
            "主色档那一问没接上：这一档的立论就是「零权限也要有答案」，不读 colors 就没有第二档：\n$paletteOf",
            paletteOf.contains("getWallpaperColors(WallpaperManager.FLAG_SYSTEM)"),
        )
        assertTrue(
            "只把 primary 交回来的话，判据第 1 步（较暗那格定明度）与第 2 步（平局才投票）都无从算起：" +
                "\n$paletteOf",
            paletteOf.contains("secondaryColor") && paletteOf.contains("colorHints"),
        )
        // 装机实测：`FLAG_SYSTEM or FLAG_LOCK` 抛 IllegalArgumentException（一次只让问一种）。
        // 这条链上也没有任何一档要读锁屏壁纸 —— 组件贴在桌面上。
        assertEquals("问色那一问混进了第二种壁纸的旗标（平台一次只让读一种，混着传直接抛）：\n$paletteOf", 1, occurrences(paletteOf, "FLAG_"))
        listOf("FLAG_LOCK", "FLAG_DIM_BEHIND").forEach { needle ->
            assertTrue(
                "问色那一问读的是 $needle —— 组件贴在桌面上，跟着桌面那张走才是这个问题的答案：" +
                    "\n$paletteOf",
                !paletteOf.contains(needle),
            )
        }
        // 位图档赢的那一轮不许去问色（一次 binder 就够），也不许反过来把主色档当成位图的替代品
        assertTrue(
            "问色不再以「位图档撞空」为前提：位图能用那一轮它压根不该被问到（判据的优先级要对得上）：" +
                "\n$measure",
            measure.contains("if (usable) null else runCatching { paletteOf(context) }"),
        )
        assertEquals(
            "探针里问色的写点不止一处（memo 的两格结论要同一轮一起翻）：\n$probe",
            1,
            occurrences(probe, "paletteOf(context)"),
        )
    }

    /** ⑪ memo 第三格的写序与读序：红线是「先写结论、后写时间戳」，读侧反过来 */
    @Test
    fun thePaletteMemoKeepsTheWriteOrderRedLine() {
        val probe = withoutCommentsKeepingLiterals(readMainSource(PROBE_FILE))
        val measure = normalize(balancedBlock(probe, "fun measure(context: Context): Measurement"))
        val readsPalette = measure.indexOf("memoPalette = palette")
        val writesStamp = measure.indexOf("memoAtElapsed =")
        assertTrue("measure 里没写主色那一格 memo：\n$measure", readsPalette >= 0)
        assertTrue(
            "时间戳写在结论之前了 —— 读侧就可能拿到「新戳 + 旧结论」这一对配错的答案：" +
                "\n$measure",
            readsPalette < writesStamp,
        )
        assertTrue(
            "位图那一格结论也得排在时间戳之前：\n$measure",
            measure.indexOf("memoUsable = usable") < writesStamp,
        )
        listOf("memoized()", "memoizedPalette()").forEach { reader ->
            val body = normalize(balancedBlock(probe, "fun $reader"))
            assertTrue(
                "$reader 的第一眼看的不是时间戳（写序红线有一半是靠读侧这一眼成立的：看不见新戳就退回旧戳那一格）：" +
                    "\n$body",
                body.substringAfter("{").trim().startsWith("if (memoAtElapsed == Long.MIN_VALUE) return null"),
            )
        }
    }

    /** ⑫ 本卡的硬约束：一块背景板不值两枚相册权限 —— 声明与运行时申请都不许出现 */
    @Test
    fun theGalleryPermissionsStayUnaskedEverywhereInMainSources() {
        val manifest = File(findMainJavaDir(), "../AndroidManifest.xml")
        assertTrue("找不到应用清单：${manifest.path}，这条守卫等于没跑", manifest.isFile)
        val declared = manifest.readText()
        val offenders = mutableListOf<String>()
        GALLERY_PERMISSIONS.forEach { permission ->
            if (declared.contains(permission)) offenders += "清单声明了 $permission"
            allMainKotlinSources().forEach { file ->
                // 注释里写着这两个名字是论证（T46 那次错话的订正就得点名它们），
                // 所以只扫抹掉注释之后的代码：任何声明、任何运行时申请都会留下这个名字。
                if (withoutCommentsKeepingLiterals(file.readText()).contains(permission)) {
                    offenders += "${file.name} 的代码里有 $permission"
                }
            }
        }
        assertTrue(
            "为一块半透明底板上架相册权限 = 向用户要「你的全部照片」，代价大于收益；" +
                "位图档因此只能停在「这台设备恰好给了权限才成立」那一格（取舍论证见 WidgetWallpaperProbe 头注释）。" +
                "\n$offenders",
            offenders.isEmpty(),
        )
    }

    /** ⑬ 判据的边界：主色档这张表整个能在 JVM 里钉，靠的就是这个文件不 import 任何东西 */
    @Test
    fun thePaletteJudgementStaysFreeOfAndroidTypes() {
        val code = withoutCommentsKeepingLiterals(readMainSource(GLASS_SOURCE_FILE))
        assertEquals(
            "判据文件里长出了 import（`Color` / `WallpaperColors` 一旦进来，这张表在 JVM 单测里就再也跑不到）：" +
                "\n${linesStartingWith(code, "import ")}",
            emptyList<String>(),
            linesStartingWith(code, "import "),
        )
        assertTrue(
            "设备事实渗进了判据（这里只收 Int / Int? / Boolean / Float，翻译在调用点做）：\n$code",
            !code.contains("android.") && !code.contains("WallpaperManager")
        )
        assertEquals(
            "主色档的入参从四格漂了（primary / secondary / hints / preset 少一格就没法在 JVM 里逐格钉）",
            4,
            parametersOf(code, "fun palettePlateArgb("),
        )
    }

    /** ⑭ 渲染侧：位图撞空才吃板色，开关关掉时一口都不许吃 */
    @Test
    fun theRendererEatsTheDerivedPlateOnlyWhenTheBitmapTierMisses() {
        val code = withoutCommentsKeepingLiterals(readMainSource(RENDERER_FILE))
        val body = normalize(balancedBlock(code, "internal fun render("))
        val paletteExit = normalize(balancedBlock(code, "private fun palettePlateOrPreset("))

        val offGuard = body.indexOf("if (!appearance.blurBackground) return GlassRender.Preset")
        assertTrue("渲染侧不再先看那枚开关（关掉后桌面主色照样会吃到）：\n$body", offGuard >= 0)
        assertTrue(
            "开关那一闸被挪到了实测之后 —— 关掉时连那次 binder 问图都不该付，更不许吃板色：" +
                "\n$body",
            offGuard < body.indexOf("wallpaperForRender(context)"),
        )
        assertTrue(
            "位图档撞空时没接上主色档（本卡的全部目的：让开关第一次拨得出可见变化）：\n$body",
            body.contains("measured.captured ?: return@runCatching palettePlateOrPreset(measured, tintArgb)"),
        )
        assertEquals(
            "板色判据被问了不止一遍（只许出口那一处问，本体在 WidgetGlassSource）：\n$code",
            1,
            occurrences(code, "WidgetGlassSource.palettePlateArgb("),
        )
        assertTrue(
            "主色缺席那一格没退回纯色（用户自己选的色是两头都撞空时的答案）：\n$paletteExit",
            paletteExit.contains("measured.palette ?: return GlassRender.Preset"),
        )
        listOf("primaryArgb", "secondaryArgb", "colorHints", "presetArgb = tintArgb").forEach { needle ->
            assertTrue(
                "板色判据少吃到一格事实（$needle）—— 少 secondary 就没有明度来历，少 hints 平局那一格就没话说：\n$paletteExit",
                paletteExit.contains(needle),
            )
        }
    }

    /** ⑮ 出口那三格在调用点各自落地，而且不带 else（加一格必须被编译期点名） */
    @Test
    fun theThreeRenderExitsLandAtTheCallSiteWithoutAFold() {
        val code = withoutCommentsKeepingLiterals(readMainSource(COMMON_FILE))
        val apply = normalize(balancedBlock(code, "private fun applyAppearance("))

        assertTrue(
            "三格出口被 when 的 else 兜成一格 —— 以后加一档就会悄悄落到旧行为里：\n$apply",
            !apply.contains("else ->"),
        )
        listOf(
            "is GlassRender.FromBitmap -> glass.bitmap to presetBackground" to "有位图时画位图、着色仍交回用户那块板（改前行为，一格像素都不许多动）",
            "is GlassRender.FromPalette -> null to glass.plateArgb" to "只有主色时走纯色那条支，只换 setColorFilter 的色",
            "GlassRender.Preset -> null to presetBackground" to "两档都没有时画用户自己选的色",
        ).forEach { (branch, why) ->
            assertTrue("$why —— 落地那一行变了：$branch\n$apply", apply.contains(branch))
        }
        // 这一档改的是"板是什么颜色"，不是"板怎么画"：三条纯色指令与圆角/透明度口径不许跟着漂
        listOf(
            "views.setInt(bgViewId, \"setImageResource\", appearance.cornerDrawableRes)",
            "views.setInt(bgViewId, \"setColorFilter\", background)",
            "views.setFloat(bgViewId, \"setAlpha\", appearance.alphaFraction)",
        ).forEach { needle ->
            assertTrue("纯色那条支少了一条指令（$needle）：\n$apply", apply.contains(needle))
        }
    }

    /** ⑯ 配置页那句 when：五格全覆盖、不带 else，一格一句人话且不许互相顶包 */
    @Test
    fun theConfigPageSpeaksOncePerCellAndCoversAllFiveWithoutAnElse() {
        val code = withoutCommentsKeepingLiterals(readMainSource(CONFIG_FILE))
        val block = normalize(balancedBlock(code, "text = when (glassSource) {"))

        GlassSource.entries.forEach { cell ->
            assertTrue(
                "图源判据加了格（${cell.name}）而配置页那句没跟着说 —— 不带 else 的 when 本该在编译期拦下，" +
                    "这一格是拦不住时的兜底：\n$block",
                block.contains("${cell.name} ->"),
            )
        }
        assertTrue("那句 when 又写回 else 了：\n$block", !block.contains("else ->"))

        // 每格自己的话（⑥ 钉过四格"该说什么"，这里补上"不该说什么"：
        // 主色档那句最容易被写成"糊的是壁纸"，而那正是本卡要订正的那类许诺）
        listOf(
            CellCopy(GlassSource.SystemWallpaper.name, required = listOf("实测读得到"), forbidden = listOf("没有糊任何图", "桌面主色")),
            CellCopy(
                GlassSource.SystemWallpaperPaletteOnly.name,
                required = listOf("没有糊任何图", "颜色取自桌面主色", "本应用不申请"),
                forbidden = listOf("糊的是系统桌面壁纸", "糊的是一张图"),
            ),
            CellCopy(GlassSource.NotMeasuredYet.name, required = listOf("正在确认"), forbidden = listOf("桌面主色", "读不到那张位图")),
            CellCopy(
                GlassSource.NoSourceSystemWallpaperUnusable.name,
                required = listOf("实测读不到", "也没问到桌面主色"),
                forbidden = listOf("颜色取自桌面主色"),
            ),
            CellCopy(GlassSource.NoSourceSystemWallpaperOff.name, required = listOf("使用桌面壁纸", "重新打开它就好"), forbidden = listOf("读不到", "桌面主色")),
        ).forEach { copy ->
            val text = branchText(block, copy.branch)
            copy.required.forEach { phrase ->
                assertTrue("${copy.branch} 那一支丢了「$phrase」：\n$text", text.contains(phrase))
            }
            copy.forbidden.forEach { phrase ->
                assertTrue("${copy.branch} 那一支说了「$phrase」—— 这句对不上这一格的来历：\n$text", !text.contains(phrase))
            }
        }
    }

    /** 一格用户话：必须出现的来历 + 不许出现的许诺（主色档不许冒充位图档） */
    private data class CellCopy(val branch: String, val required: List<String>, val forbidden: List<String>)

    // ---- 源码核对工具（抄 ColdStartRebuildWiringTest）----
    /**
     * 只抹注释、**保留字符串字面量**，长度与换行位置不变。
     *
     * 保留字面量是因为这里的锚点带中文文案（`Panel(title = "内容")`），
     * 而注释里也写着那些文案 —— 不抹注释就会把 KDoc 里的提及当成接线。
     * 必须用状态机而不是"找第一个成对标记"：本文件要读的 `WidgetConfigActivity.kt` 里
     * 有一句作为字符串出现的 `image/` 加星号，粗暴扫描会把它当块注释开头吞掉后面一片真代码。
     */
    private fun withoutCommentsKeepingLiterals(src: String): String {
        val out = src.toCharArray()
        var i = 0
        while (i < out.size) {
            when {
                src.startsWith("//", i) -> {
                    val nl = src.indexOf('\n', i).let { if (it < 0) out.size else it }
                    for (k in i until nl) out[k] = ' '
                    i = nl
                }

                src.startsWith("/*", i) -> {
                    var depth = 1
                    var j = i + 2
                    while (j < out.size && depth > 0) {
                        when {
                            src.startsWith("/*", j) -> { depth++; j += 2 }
                            src.startsWith("*/", j) -> { depth--; j += 2 }
                            else -> j++
                        }
                    }
                    for (k in i until j.coerceAtMost(out.size)) if (out[k] != '\n') out[k] = ' '
                    i = j
                }

                src.startsWith("\"\"\"", i) -> {
                    i = src.indexOf("\"\"\"", i + 3).let { if (it < 0) out.size else it + 3 }
                }

                out[i] == '"' || out[i] == '\'' -> {
                    val quote = out[i]
                    var j = i + 1
                    while (j < out.size) {
                        when {
                            src[j] == '\\' -> j += 2
                            src[j] == quote -> { j++; break }
                            src[j] == '\n' -> break
                            else -> j++
                        }
                    }
                    i = j
                }

                else -> i++
            }
        }
        return String(out)
    }

    /**
     * 从 [signature] 之后第一个 `{` 起配平到对应右括号（含），返回整段（嵌套分支一起数）。
     * 找不到锚点就抛 —— 锚点失效必须显式失败。
     */
    private fun balancedBlock(source: String, signature: String): String {
        val at = source.indexOf(signature)
        check(at >= 0) { "找不到 $signature：函数改名或挪过家，这条守卫要跟着改" }
        val open = source.indexOf('{', at)
        check(open >= at) { "$signature 之后找不到左括号" }
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(at, index + 1)
                }
            }
        }
        throw IllegalStateException("$signature 的花括号没配平")
    }

    private fun readMainSource(relativeFromJava: String): String {
        val file = File(findMainJavaDir(), relativeFromJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /** 组件这条链的全部源文件（② 那条"整包扫"要用，漏一家就等于没扫） */
    private fun mainSourceFiles(): List<File> {
        val dir = File(findMainJavaDir(), "com/buaa/schedule/widget")
        assertTrue("找不到 widget 包目录：$dir", dir.isDirectory)
        val files = dir.listFiles { file: File -> file.isFile && file.name.endsWith(".kt") }?.toList()
            .orEmpty()
        assertTrue("widget 包里一个文件都没扫到（扫空 = 没有守卫）", files.size >= 20)
        return files.sortedBy { it.name }
    }

    /** 工作目录是模块目录还是仓库根不由这里决定：两种布局都试，全落空就抛（跳过的守卫等于没守卫） */
    private fun findMainJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { File(dir, it) }
                .firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }

    /** 素材在 `res/` 下而不是 `src/main/java/` 下：找法与上面同一套，落空就抛 */
    private fun findResDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val hit = listOf("src/main/res", "app/src/main/res")
                .map { File(dir, it) }
                .firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/res：当前目录 ${File("").absolutePath}")
    }

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    private fun normalize(code: String): String = code.replace(Regex("\\s+"), " ").trim()

    /** 主源集全部 .kt（⑫ 那条"整棵树扫"要用：权限要声明就是全局声明，藏在别的包里也一样算） */
    private fun allMainKotlinSources(): List<File> {
        val root = findMainJavaDir()
        val files = root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
        assertTrue("app 主源集一个 .kt 都没扫到（扫空 = 没有守卫）：$root", files.size >= 100)
        return files.sortedBy { it.path }
    }

    /** 逐行取「以 [prefix] 开头」的行（⑬ 数 import 用；注释已被抹成空格，所以剩下的都是真语句） */
    private fun linesStartingWith(code: String, prefix: String): List<String> =
        code.lineSequence().map { it.trim() }.filter { it.startsWith(prefix) }.toList()

    /**
     * 数 [signature] 那一串形参的个数（到配对右括号为止，嵌套括号里的逗号不算）。
     * ⑬ 用它钉「四格设备事实都是从外面交进来的」—— 少一格就意味着有一格改成了在判据里读设备。
     */
    private fun parametersOf(code: String, signature: String): Int {
        val at = code.indexOf(signature)
        check(at >= 0) { "找不到 $signature：判据改名或换过写法，这条守卫要跟着改" }
        var depth = 1
        var index = at + signature.length
        while (index < code.length && depth > 0) {
            when (code[index]) {
                '(', '[' -> depth++
                ')', ']' -> depth--
            }
            index++
        }
        check(depth == 0) { "$signature 的形参列表括号没配平" }
        // 末尾那枚逗号是 Kotlin 允许的尾随逗号（本仓库的形参列表一栏一个），不算一格
        val body = code.substring(at + signature.length, index - 1).trim().removeSuffix(",")
        if (body.isEmpty()) return 0
        var nested = 0
        var commas = 0
        body.forEach { ch ->
            when (ch) {
                '(', '[' -> nested++
                ')', ']' -> nested--
                ',' -> if (nested == 0) commas++
            }
        }
        return commas + 1
    }

    private companion object {
        const val RENDERER_FILE = "com/buaa/schedule/widget/WidgetBackgroundRenderer.kt"
        const val PROBE_FILE = "com/buaa/schedule/widget/WidgetWallpaperProbe.kt"
        const val GLASS_SOURCE_FILE = "com/buaa/schedule/widget/WidgetGlassSource.kt"
        const val CONFIG_FILE = "com/buaa/schedule/widget/WidgetConfigActivity.kt"
        const val COMMON_FILE = "com/buaa/schedule/widget/WidgetCommon.kt"

        /** 渲染侧取图源那一格（签名变了这条守卫要跟着改，而它正是"每轮实测"的落点） */
        const val RENDERER_MEASURE_ANCHOR =
            "private fun wallpaperForRender(context: Context): WidgetWallpaperProbe.Measurement?"

        /**
         * 那道一刀切闸门的各种写法。`34` 这一枚只钉到"与壁纸同段出现"的程度不够狠，
         * 所以连常量名、判据名、平台代号名一起扫 —— 从任何一扇门放回来都算没拆。
         */
        val GATED_FORBIDDEN = listOf(
            "MIN_SDK_SYSTEM_WALLPAPER_UNREADABLE",
            "systemWallpaperReadable",
            "UPSIDE_DOWN_CAKE",
            "SDK_INT >= 34",
            "SDK_INT < 34",
        )

        /** 组件这条链不许再碰自选那张的任何写法（键名 + 那条解码路） */
        val FORBIDDEN_SOURCE_NEEDLES = listOf(
            "wallpaper_uri",
            "decodeSampledWallpaper",
            "KEY_WALLPAPER_URI",
        )

        /**
         * 位图档缺的那两道闸的门票。本卡的硬约束是**一枚都不申请**：为一块半透明底板
         * 要"你的全部照片"，代价大于收益（论证在 `WidgetWallpaperProbe` 头注释与
         * docs/KNOWN_ISSUES.md §1）。名字出现在代码里只有两种来路 —— 清单声明、
         * 运行时申请（`requestPermissions` / `RequestPermission` contract 都得写这个名字），
         * 所以「代码里一次都不出现」同时钉住两头。
         */
        val GALLERY_PERMISSIONS = listOf(
            "READ_EXTERNAL_STORAGE",
            "READ_MEDIA_IMAGES",
        )

        /** 「玻璃感壁纸背景」那一块的面板锚点（标题文案变了就要跟着改这里） */
        const val PANEL_ANCHOR = "Panel(title = \"内容\")"
    }
}
