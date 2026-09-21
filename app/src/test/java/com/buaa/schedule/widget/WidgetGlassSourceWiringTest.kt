package com.buaa.schedule.widget

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「玻璃感壁纸背景」这条链的接线：判据只有一份，说人话的那一句与实测的取舍都在开关手边。
 *
 * 为什么要按源码形状核对：判定本体是 JVM 测得到的（`WidgetGlassSourceTest` 逐格钉那两张表），
 * 但"渲染侧与配置页到底有没有各判一次、实测有没有躲开主线程"这两件事 JVM 跑不到 ——
 * 本模块没有 Robolectric，`getSharedPreferences` / `WallpaperManager` / Compose 组合期
 * 全是抛 "not mocked" 的桩。而漏接线恰好有几种不红的形态：
 * ① 配置页继续自己写一段常驻小字（判据白写，用户照旧拨了没反应 —— T31 修过的那次）；
 * ② 渲染侧留着自己那份 `if (useSystem)` 的老分支（两边各判一次，早晚对不上）；
 * ③ T46 特有的两种新形态：把旧的 `SDK_INT >= 34` 闸门从别的门缝里放回来（实测白做），
 *    或者在配置页主线程上调一次 `measure`（那是一次 binder 问图 + 整屏位图取点）。
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

    /** ⑥ 四格答案各说一句人话，而且不许再挂那颗兑现不了的挑图按钮 */
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

    private companion object {
        const val RENDERER_FILE = "com/buaa/schedule/widget/WidgetBackgroundRenderer.kt"
        const val PROBE_FILE = "com/buaa/schedule/widget/WidgetWallpaperProbe.kt"
        const val GLASS_SOURCE_FILE = "com/buaa/schedule/widget/WidgetGlassSource.kt"
        const val CONFIG_FILE = "com/buaa/schedule/widget/WidgetConfigActivity.kt"

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

        /** 「玻璃感壁纸背景」那一块的面板锚点（标题文案变了就要跟着改这里） */
        const val PANEL_ANCHOR = "Panel(title = \"内容\")"
    }
}
