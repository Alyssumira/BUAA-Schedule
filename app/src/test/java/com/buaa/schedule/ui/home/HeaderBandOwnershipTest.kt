package com.buaa.schedule.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页头条归属判据内核的表驱动单测（T80）。
 *
 * 这一页要防的缺陷形状只有一个：**同一条页头带上同时摆着两套文字**。装机量下来这不是
 * 观感问题而是几何问题 —— 首页第一行占 y=158..284、统计页页头占 y=157..283，实高都是
 * 126px（48dp 触控下限），也就是同一条带；而 `"stats"` 走 `NavMotion.SLIDE` 的 260ms 里
 * 新旧两页同时在场。内核答的就是"这一条带此刻归谁、谁在板上画字、占位占多高"。
 *
 * 表里的路由名与高度全是**合成整数与合成路由**：内核吃的就是调用点量好的整数与
 * `currentDestination?.route` 那枚字符串，这里掺进装机读数就等于把两条不相干的账绑在一起钉
 * （口径同 [StatsEntryPolicyTest]）。
 */
class HeaderBandOwnershipTest {

    // ---- ① 归属：只有 stats 抢得走这条带 ----

    @Test
    fun onlyTheStatsRouteTakesTheBandAwayFromHome() {
        assertEquals(HeaderBandOwner.Stats, headerBandOwnerOf("stats"))
        assertEquals(HeaderBandOwner.Home, headerBandOwnerOf("home"))
    }

    /**
     * 除 `"stats"` 之外一律归首页，含 null 与没见过的路由。
     *
     * 这一档钉的是**保守方向**：默认归首页意味着首页之外那些页面（编辑器 / 导入 / 设置 …）
     * 跳转期间首页画不画字都影响不到它们，首页的页头行为与改前一个字都不差。
     * 反过来若默认归"没人"，任何一次跳转都会把首页顶栏抹白一格。
     */
    @Test
    fun unknownAndMissingRoutesFallBackToHomeConservatively() {
        for (route in listOf(null, "", "editor/{courseId}", "settings/{section}", "import", "settings",
            "course_management", "buaa_login", "spoc_scan", "import_history", "STATS", "stats/",
        )) {
            assertEquals(
                "路由「$route」不该把页头条从首页手里抢走（只有恰好等于 stats 那条才抢）",
                HeaderBandOwner.Home, headerBandOwnerOf(route),
            )
        }
    }

    /** 主人这一维是个**闭合集合**：恰好两位，加第三位时必须先把下面那条"永远只有一位在画"的判据想清楚 */
    @Test
    fun ownerSetIsClosedAtExactlyTwoPages() {
        assertEquals(
            "页头条现在只有首页与统计页两位主人；加第三位要先回答『跳转期间那条带归谁』",
            listOf(HeaderBandOwner.Home, HeaderBandOwner.Stats),
            HeaderBandOwner.entries.toList(),
        )
    }

    // ---- ② 只有一位在画：不许两家同写，也不许一家都不写 ----

    /**
     * 缺陷本体。对**每一个**主人取值，"该在板上画字"的那一位都恰好一位。
     *
     * 这一档就是用户那句「避免页面顶部先显示首页页头再切入统计页」的形式化：
     * 转场期间两页同时在场是 NavHost 的事实，改不了；能改的是"两家都往同一条带上写"。
     * 数出 0 就是首页顶栏空白一格，数出 2 就是两套文字叠在一起。
     */
    @Test
    fun exactlyOnePageDrawsOnTheBandForEveryOwner() {
        for (owner in HeaderBandOwner.entries) {
            val drawn = HeaderBandOwner.entries.filter { headerBandDrawnOnScreen(owner, it) }
            assertEquals(
                "主人是 $owner 时，板上该画字的那一位应当恰好一个，实际是 $drawn",
                listOf(owner), drawn,
            )
        }
    }

    /** 两位主人各自只认领自己那一格：交叉组合恒为 false */
    @Test
    fun crossOwnershipIsAlwaysFalse() {
        assertFalse(headerBandDrawnOnScreen(HeaderBandOwner.Home, HeaderBandOwner.Stats))
        assertFalse(headerBandDrawnOnScreen(HeaderBandOwner.Stats, HeaderBandOwner.Home))
        assertTrue(headerBandDrawnOnScreen(HeaderBandOwner.Home, HeaderBandOwner.Home))
        assertTrue(headerBandDrawnOnScreen(HeaderBandOwner.Stats, HeaderBandOwner.Stats))
    }

    // ---- ③ 占位高度：带子的上下沿一动不动是靠这一枚买来的 ----

    /**
     * 不在台上时按**上一次量到的实高**占位，而不是按 48dp 下限猜一个。
     *
     * 首页左列在周课表页签是两行字（「第4周」+「9月24日 星期四」），系统字号调大以后
     * 会高过 48dp 下限；按下限占位就等于跳转期间整页正文往上塌一截 —— 那又是一次跳动。
     */
    @Test
    fun placeholderUsesTheLastMeasuredHeightWhenThereIsOne() {
        assertEquals(126, headerBandPlaceholderHeightPx(measuredHeightPx = 126, fallbackHeightPx = 126))
        assertEquals(151, headerBandPlaceholderHeightPx(measuredHeightPx = 151, fallbackHeightPx = 126))
        assertEquals(
            "还没量到（首帧 / 这一页从没在台上待过）才退到兜底那一枚",
            126, headerBandPlaceholderHeightPx(measuredHeightPx = null, fallbackHeightPx = 126),
        )
    }

    /** 一枚不是高度的数（0 或负）不许当成"量到了 0 高"：那会把正文顶到屏沿 */
    @Test
    fun nonPositiveMeasuredHeightFallsBackInsteadOfCollapsingTheBand() {
        for (bad in listOf(0, -1, -4_096)) {
            assertEquals(
                "量到 $bad 只能是还没画出来，按 0 高占位会把正文顶到屏沿：",
                126, headerBandPlaceholderHeightPx(measuredHeightPx = bad, fallbackHeightPx = 126),
            )
        }
        assertEquals(1, headerBandPlaceholderHeightPx(1, 126))
    }

    /** 兜底那一枚是调用点从 DesignTokens 换算出来的整数，内核自己不换算：48dp × 2.625 = 126 */
    @Test
    fun kernelOnlyPicksBetweenTheTwoIntegersItIsGiven() {
        // 量到过就认量到的那枚，谁大谁小都不参与判断（内核不知道 48dp 是多少）
        assertEquals(9, headerBandPlaceholderHeightPx(9, 7))
        assertEquals(7, headerBandPlaceholderHeightPx(7, 9))
        assertEquals(3, headerBandPlaceholderHeightPx(3, 0))
        for (measured in listOf(null, 0, -5)) {
            val answer = headerBandPlaceholderHeightPx(measured, 126)
            assertTrue(
                "答案只能是内核收到的那两枚之一（不许自己换算 dp、不许读 48dp 这个数）：$measured -> $answer",
                answer == 126,
            )
        }
    }

    // ---- ④ 内核纯度：这张表要在 JVM 里裸跑 ----

    @Test
    fun kernelReadsNoDeviceFactsAndNoClockItself() {
        val text = blankComments(kernelSource())
        val offenders = text.lines().map { it.trimStart() }
            .filter { it.startsWith("import android") || it.startsWith("import androidx") || it.startsWith("import kotlinx") }
        assertTrue(
            "HeaderBandOwnership.kt 混进设备/框架依赖就没法在 JVM 单测里裸跑" +
                "（当前路由、实高、48dp 兜底全得当参数传进来）：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
        // 表现层/设备侧事实只能从参数进来：出现在代码里（注释已被抹掉）就是内核自己伸手去读
        val reaching = listOf(
            "LocalConfiguration", "LocalDensity", "MaterialTheme", "LocalContext", "DesignTokens",
            "BuildConfig", "@Composable", "Modifier", "NavController", "NavHost", "NavBackStackEntry",
            "currentBackStackEntry", "currentDestination", "toPx()", "R.string", "dp.",
        ).flatMap { name ->
            text.lines().map { it.trim() }.filter { it.contains(name) }.map { "$name -> $it" }
        }
        assertTrue("判据内核自己读了设备/导航事实，参数那一维就成了摆设：\n" + reaching.joinToString("\n"), reaching.isEmpty())
        assertFalse(
            "内核不许读时钟",
            text.lines().any {
                it.contains("System.currentTimeMillis") || it.contains("LocalDate.now(") ||
                    it.contains("nanoTime") || it.contains("Clock")
            },
        )
    }

    /**
     * `"stats"` 这一枚路由常数只服务本内核。
     *
     * 反向也要钉：内核不许把自己变成路由名的第二个定义处 —— `MainActivity` 那份
     * `navigate("stats")` / `composable("stats")` 由 `StatsEntryWiringGuardTest` ①-b / ③-a
     * 按字面量钉着，换成常量会让那两档红在"锚点找不到"上而不是红在真缺陷上。
     */
    @Test
    fun kernelDoesNotBecomeASecondHomeForTheRouteName() {
        val text = blankComments(kernelSource())
        assertEquals(
            "路由名在本内核里不许出现 composable(...)/navigate(...) 那种写法（它们属于 MainActivity）：",
            0, Regex("""composable\(|navigate\(""").findAll(text).count(),
        )
        assertTrue(
            "STATS_ROUTE_NAME 的值不再是 stats：这一枚常数与 MainActivity 那条路由一旦脱钩，" +
                "整条『一条带一个主人』的判据就在飘",
            Regex("""STATS_ROUTE_NAME: String = "stats"""").containsMatchIn(text),
        )
    }

    // ---- 源码核对工具（与各 *PolicyTest 同一套刀法）----

    private fun kernelSource(): String {
        val file = File(findMainJavaDir(), "com/buaa/schedule/ui/home/HeaderBandOwnership.kt")
        assertTrue("找不到 HeaderBandOwnership.kt：挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

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

    /** 把 `//` 与 `/* */` 注释抹成空格（字符串保留、长度与换行位置不变）：钉的是判据，不是白话 */
    private fun blankComments(src: String): String {
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
}
