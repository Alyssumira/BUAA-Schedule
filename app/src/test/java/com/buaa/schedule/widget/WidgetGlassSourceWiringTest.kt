package com.buaa.schedule.widget

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「玻璃感壁纸背景」这条链的接线：判据只有一份，说人话的那一句和挑图的入口都在开关手边。
 *
 * 为什么要按源码形状核对：判定本体是 JVM 测得到的（`WidgetGlassSourceTest` 逐格钉那张表），
 * 但"渲染侧与配置页到底有没有各判一次"这件事 JVM 跑不到 —— 本模块没有 Robolectric，
 * `getSharedPreferences` / `WallpaperManager` / Compose 组合期全是抛 "not mocked" 的桩。
 * 而漏接线恰好有两种不红的形态：
 * ① 配置页继续自己写一段常驻小字（判据白写，用户照旧拨了没反应 —— 就是这张卡要修的那个 bug）；
 * ② 渲染侧留着自己那份 `if (useSystem)` 的老分支（两边各判一次，早晚对不上）。
 *
 * 手法抄 [ColdStartRebuildWiringTest]：读 .kt、按锚点配平取函数体、找不到锚点就抛
 * （静默跳过的守卫等于没有守卫）。
 */
class WidgetGlassSourceWiringTest {

    /** ① 渲染侧不再自己重排取源顺序，改为问那份共用判据 */
    @Test
    fun rendererAsksTheSharedDecisionInsteadOfRepeatingTheOrder() {
        val code = withoutCommentsKeepingLiterals(readMainSource(RENDERER_FILE))
        val availability = normalize(balancedBlock(code, "internal fun availability(context: Context): GlassSource"))
        // 返回类型是 Wallpaper 而不是 Source：T42 把"这一次要糊的那张壁纸"拆成了
        // 身份 + 惰性取图（缓存命中时不必解码），取源顺序本身一个字没动，下面的锚点就是钉它。
        val body = normalize(balancedBlock(code, "private fun wallpaperForRender(context: Context): Wallpaper?"))

        assertEquals(
            "判据被问了两遍（只许 availability 那一处问，配置页再从它拿答案）：\n$code",
            1,
            occurrences(code, "WidgetGlassSource.decide("),
        )
        // 三个入参都要来自真实状态，不许写死
        assertTrue("API 档次没接进判定：\n$availability", availability.contains("WidgetGlassSource.systemWallpaperReadable(Build.VERSION.SDK_INT)"))
        assertTrue("「使用桌面壁纸」那枚开关没接进判定：\n$availability", availability.contains("KEY_USE_SYSTEM_WALLPAPER"))
        assertTrue(
            "自选 URI「有没有」在这里又自己判了一次（应共用 WidgetGlassSource.hasPickedImage）：\n$availability",
            availability.contains("WidgetGlassSource.hasPickedImage("),
        )
        // 顺序本身：开关开着且读得到时系统源优先、自选兜底；只认自选时不碰系统源；无源时直接 null
        assertTrue("取源顺序被改掉了：\n$body", body.contains("when (availability(context))"))
        assertTrue(
            "系统源优先、自选兜底那一条被改掉了：\n$body",
            body.contains("GlassSource.SystemWallpaperThenPicked -> systemWallpaper(context) ?: picked()"),
        )
        assertTrue("只认自选那张那一条被改掉了：\n$body", body.contains("GlassSource.PickedImage -> picked()"))
        assertTrue(
            "判到没有图源时还在试着取图（应当直接返回 null，让 applyAppearance 落到纯色那条分支）：\n$body",
            body.contains("GlassSource.NoSourceWallpaperReadBlocked") && body.contains("-> null"),
        )
    }

    /** ② 「这台设备读不读得到桌面壁纸」这条版本闸门只有一个出处 */
    @Test
    fun systemWallpaperVersionGateExistsInExactlyOnePlace() {
        val renderer = withoutCommentsKeepingLiterals(readMainSource(RENDERER_FILE))

        assertTrue(
            "渲染侧不再经共用判据问版本，于是它自己那闸门与配置页那句说明可以对不上：\n$renderer",
            occurrences(renderer, "WidgetGlassSource.systemWallpaperReadable(") >= 2,
        )
        assertEquals(
            "widget 包里还留着自己写的 API 34 闸门（这个 34 只许写在 WidgetGlassSource 一处）：\n$renderer",
            0,
            occurrences(renderer, "UPSIDE_DOWN_CAKE"),
        )
    }

    /** ③ 配置页那枚开关读的是同一个答案，而不是自己写死一段说明 */
    @Test
    fun configPageSwitchIsWiredToTheSameJudgement() {
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
            panel.contains(".usable"),
        )
        assertTrue(
            "开关被改成了不可拨：这张卡选的是「照样能拨、但当场说清楚 + 就地给挑图入口」，" +
                "置灰等于把说明那段又变回没人看见：\n$panel",
            !panel.contains("enabled = false"),
        )
    }

    /** ④ 无源时开关手边就有出路：就地挑图，而不是让人去别的页面找 */
    @Test
    fun configPageOffersTheWayOutNextToTheSwitch() {
        val code = withoutCommentsKeepingLiterals(readMainSource(CONFIG_FILE))
        val panel = balancedBlock(code, PANEL_ANCHOR)

        assertTrue("无源时没有就地挑图的入口：\n$panel", panel.contains("wallpaperLauncher.launch("))
        assertTrue("挑图入口没挂在 SAF 上：\n$code", code.contains("rememberLauncherForActivityResult"))
        assertTrue(
            "挑图写的是新造的存储格式（必须经 App 内那条既有链路写 Personalization 那两个键）：\n$code",
            code.contains("applyPickedWallpaper(") && !code.contains("putString(\"wallpaper_uri\""),
        )
        // 那段常驻小字的最后一句是"这个开关看起来没反应，是在等你先挑一张图" ——
        // 它把责任说给用户，却既不分图源判定、也不给入口，正是要修的那句话。
        // 查的是抹掉注释之后的文本：本卡片的注释里正是拿它当话说的那一句。
        assertTrue(
            "还在用那段常驻小字代替判定（无源时要说具体原因，有源时不必吓唬人）",
            !code.contains("是在等你先挑一张图"),
        )
    }

    /** ⑤ 圆角档位行必须换行：6 档在 360dp 宽的屏上被那颗不换行的 Row 吞掉了最后一档 */
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

    /** ⑥ 这张卡不许动档位表与默认档（内容、顺序、个数都是判据的一部分） */
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
     * ⑦ 第 6 档（28dp）以前在配置页上根本点不到，于是它的纯色底素材也从没被验证过。
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
        const val CONFIG_FILE = "com/buaa/schedule/widget/WidgetConfigActivity.kt"

        /** 「玻璃感壁纸背景」那一块的面板锚点（标题文案变了就要跟着改这里） */
        const val PANEL_ANCHOR = "Panel(title = \"内容\")"
    }
}
