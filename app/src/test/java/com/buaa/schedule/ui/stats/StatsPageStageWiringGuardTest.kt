package com.buaa.schedule.ui.stats

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统计页三态判定的**接线**守卫（T74，台账 #115）。
 *
 * 本模块没有 Compose 运行时（无 Robolectric、无 ui-test），"这一页到底按什么开口"
 * 只能扫源码，刀法照抄 [StatsChartsStructureGuardTest] 与
 * [com.buaa.schedule.ui.home.StatsEntryWiringGuardTest]：读源文件文本、匹配前先
 * `blankComments` 抹注释（本卡的 KDoc 里就写着「还没有课程可统计」这句被钉的文案本身，
 * 连注释一起扫会红在自己人手上）、实参表与函数体按括号/花括号配平取、
 * 找不到锚点就抛、不用 assumeTrue 跳过（"没找到就算过"的守卫会在下一次改名时静默变绿）。
 *
 * 为什么这一族账值得单独钉：`StatsPageStageTest` 那 9 档钉的是**内核本身**答得对不对，
 * 而 #115 的病恰恰不在内核 —— 内核当时还不存在，页面是拿 `summary.courseCount == 0`
 * 当分支判据的。也就是说：把内核写对、调用点却忘了吃它，症状一模一样。
 * 所以这里钉的是"这一页确实吃的是它"，一处判据漂移就拦在编译产物之外。
 *
 * 钉五档：
 * 1. 空态那一档的判据来自 [statsPageStageOf]，且 `ready` 那一枚实参只能来自
 *    `uiState.loading`（换成读时钟、读条数，就又是"没读到当成没课"）；
 *    分支判据里不许再出现裸的 `courses.isEmpty()` / `courseCount == 0`；
 * 2. `Crossfade` 的 `targetState` 是内核答出的那一档，三档各自画各自的面；
 * 3. 加载中那一档不许复用空态文案（那句断言全页只出现一次，且只在 `EmptyStatsCard` 里）；
 * 4. 内核保持纯 JVM：零 android/androidx import、零时钟与设备读取，两枚事实由调用点传入；
 * 5. 表现层自查：这一页不起自有无限动画、淡入仍走 `motionSpec`（reduce-motion 下 snap），
 *    加载中与真的空共用同一个居中槽位。
 */
class StatsPageStageWiringGuardTest {

    // ---- ① 判据来自内核，且吃的是"就绪"这件事实 ----

    /**
     * ① 缺陷本体：`statsPageStageOf` 的调用点与它的两枚实参。
     *
     * 三种漂法各拦一次：调用点整个被摘掉（页面回到 `courseCount == 0`，症状原样复发）、
     * `ready` 换成"看起来像就绪"的东西（读时钟、读条数 —— 内核零时钟那条口径也会一起破），
     * `courseCount` 换成片段数（18 门课在库里是 22 段，档会走对、数会说错）。
     */
    @Test
    fun emptyBranchPredicateComesFromTheKernel() {
        val code = blankComments(source(STATS_SCREEN))
        val sites = callArgumentLists(code, "statsPageStageOf(")
        assertEquals("统计页里 statsPageStageOf 的调用点数应当恰好一处：${sites.size}", 1, sites.size)
        val args = sites[0]
        val ready = Regex("""ready\s*=\s*([^,)"]+)""").find(args)?.groupValues?.get(1)?.trim()
        assertEquals(
            "就绪那一枚实参不再是 uiState 的 loading 取反。改成读条数就等于把「还没读到」与「没课」" +
                "重新并成一档（#115 的成因本体），改成读时钟则连内核的零时钟口径一起破了。现在传的是：$ready",
            "!state.loading", ready,
        )
        val count = Regex("""courseCount\s*=\s*([^,)"]+)""").find(args)?.groupValues?.get(1)?.trim()
        assertEquals(
            "条数那一枚必须吃归并到整门课之后的 summary.courseCount（片段数是 22 段那一档）：$count",
            "summary.courseCount", count,
        )
        for (bare in listOf("courses.isEmpty()", "courseCount == 0", "courseCount <= 0", "fragmentCount == 0")) {
            assertFalse(
                "统计页里又出现了裸的「$bare」判据：空态与「还没读到」从此共用一句话（台账 #115）。" +
                    "分支判据只能吃 statsPageStageOf 的答案",
                code.contains(bare),
            )
        }
    }

    // ---- ② 三档各自的面：Crossfade 换的是档，不是布尔 ----

    @Test
    fun crossfadeTargetsTheStageAndEveryArmHasItsOwnFace() {
        val code = blankComments(source(STATS_SCREEN))
        val at = code.indexOf("Crossfade(")
        assertTrue("统计页没有 Crossfade 了：那一页换面方式改过，本守卫要跟着改", at >= 0)
        val args = balancedArguments(code, at + "Crossfade".length)
        assertTrue(
            "Crossfade 的 targetState 不再是内核答出的那一档（换回布尔就是两档并一档）：\n$args",
            Regex("""targetState\s*=\s*stage\b""").containsMatchIn(args),
        )
        val screen = functionBody(STATS_SCREEN, "fun StatsScreen(")
        val faces = listOf(
            "StatsPageStage.Loading" to "StatsLoadingCard(",
            "StatsPageStage.Empty" to "EmptyStatsCard(",
            "StatsPageStage.Ready" to "CreditHeadline(",
        )
        val starts = faces.map { (arm, _) ->
            val armAt = screen.indexOf("$arm ->")
            assertTrue("when 里少了 $arm 那一档（少一档 = 有一档没人画，页面会画空）", armAt >= 0)
            armAt
        }
        faces.forEachIndexed { index, (arm, face) ->
            // 一档的面切到下一档的 -> 为止：Ready 那一档画的是整叠卡片，固定窗口量不到底
            val slice = screen.substring(starts[index], starts.getOrNull(index + 1) ?: screen.length)
            assertTrue(
                "$arm 那一档画的面不是 $face —— 三档共用一面的话，「加载中」与「真的空」又混回一句话：\n" +
                    slice.take(200),
                slice.contains(face),
            )
        }
    }

    // ---- ③ 加载中那一档不许复用空态文案 ----

    /**
     * ③ 台账 #115 用户看到的那两句假话，只许在"读到了、真的是零门"那一档出现。
     *
     * 全页只许出现一次：多一处就是又一档复用上了它。注意扫的是抹掉注释之后的文本 ——
     * 本文件的 KDoc 里也写着这句（它正是被钉的对象），连注释一起扫会红在自己人手上。
     */
    @Test
    fun loadingArmNeverReusesTheEmptyCopy() {
        val code = blankComments(source(STATS_SCREEN))
        val assertion = "还没有课程可统计"
        val guidance = "先在首页导入或添加一门课"
        assertEquals(
            "「$assertion」在统计页里只许出现一次（那一档才是真话）：${allOccurrences(code, assertion)} 处",
            1, allOccurrences(code, assertion).size,
        )
        val emptyCard = functionBody(STATS_SCREEN, "private fun EmptyStatsCard(")
        assertTrue("那句断言不在 EmptyStatsCard 里，说明它被搬进了别的面：\n$emptyCard", emptyCard.contains(assertion))
        val loadingCard = functionBody(STATS_SCREEN, "private fun StatsLoadingCard(")
        assertFalse("加载中那一档复用了空态文案（就绪之前就在下断言）：\n$loadingCard", loadingCard.contains(assertion))
        assertFalse(
            "加载中那一档还在教用户去导入课程——那是「真的空」才说得出口的话：\n$loadingCard",
            loadingCard.contains(guidance),
        )
        assertTrue("加载中那一档没说要等什么，用户只会看到一片静止：\n$loadingCard", loadingCard.contains("正在读取"))
    }

    // ---- ④ 内核纯度：判据不许自己去读时钟与设备 ----

    @Test
    fun kernelStaysPureJvmAndTakesBothFactsAsParameters() {
        val code = blankComments(source(KERNEL))
        for (banned in listOf(
            "import android", "import androidx", "import java.time", "import kotlin.time",
            "SystemClock", "currentTimeMillis", "nanoTime", "LocalDate", "Clock",
        )) {
            assertFalse(
                "三态内核里出现了「$banned」：判据自己去读设备或时钟，这张表在 JVM 里就打不开，" +
                    "而且「就绪」会变成内核自己猜的值（本仓硬口径）",
                code.contains(banned),
            )
        }
        assertTrue(
            "内核的签名不再把两枚事实当参数收：那意味着它又开始从别处嗅「就绪」与「条数」",
            Regex("""statsPageStageOf\(\s*ready: Boolean,\s*courseCount: Int,?\s*\)""").containsMatchIn(code),
        )
        assertEquals("statsPageStageOf 的定义处数应当恰好一处：", 1, Regex("fun statsPageStageOf\\(").findAll(code).count())
    }

    // ---- ⑤ 表现层自查：不起了自有动画，两档共用居中槽位 ----

    @Test
    fun loadingFaceCarriesNoMotionOfItsOwnAndSharesTheSlot() {
        val code = blankComments(source(STATS_SCREEN))
        for (banned in listOf("rememberInfiniteTransition", "infiniteRepeatable", "tween(")) {
            assertFalse(
                "统计页里出现了「$banned」：这一页的动画只能走 motionSpec / MotionTokens" +
                    "（reduce-motion 下要 snap，裸 tween 不会跟着降级），" +
                    "而加载中那一档尤其不许起一圈转不完的圈——转不停的进度条比静止更容易被读成卡死",
                code.contains(banned),
            )
        }
        val at = code.indexOf("Crossfade(")
        val args = balancedArguments(code, at + "Crossfade".length)
        assertTrue("Crossfade 的动画规格不再走 motionSpec：\n$args", args.contains("motionSpec<Float>()"))
        val screen = functionBody(STATS_SCREEN, "fun StatsScreen(")
        for ((arm, face) in listOf("Loading" to "StatsLoadingCard", "Empty" to "EmptyStatsCard")) {
            assertTrue(
                "StatsPageStage.$arm 不再走 CenteredStatsCard 那个共用居中槽位：" +
                    "两档换面时卡片会跳一下位置（改前空态居中那条口径就是这么立的）",
                Regex("""StatsPageStage\.$arm -> CenteredStatsCard \{ $face\(\) \}""").containsMatchIn(screen),
            )
        }
    }

    // ---- 靶子定位与词法小工具（与 StatsChartsStructureGuardTest / StatsEntryWiringGuardTest 同族）----

    private fun source(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 $relative：挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /** 从 [signature] 之后第一个 `{` 起配平到对应右括号（含两端）的函数体 */
    private fun functionBody(relative: String, signature: String): String {
        val code = blankComments(source(relative))
        val at = code.indexOf(signature)
        assertTrue("找不到 $signature：靶子没了（$relative）", at >= 0)
        val brace = code.indexOf('{', at)
        val end = matchingClose(code, brace) ?: throw AssertionError("$relative 的花括号配不上对，解析器该修了")
        assertTrue("$relative 函数体终点在起点之前？", end > brace)
        return code.substring(brace, end)
    }

    /** 每一次调用（`Name(`）的实参表：一个命中都没有就抛，静默返回空表等于这条守卫不跑 */
    private fun callArgumentLists(code: String, call: String): List<String> {
        require(call.endsWith("(")) { "$call 不是以左括号结尾的调用锚点" }
        val out = ArrayList<String>()
        var from = 0
        while (true) {
            val at = code.indexOf(call, from)
            if (at < 0) break
            out += balancedArguments(code, at + call.length - 1)
            from = at + call.length
        }
        check(out.isNotEmpty()) { "一个 $call 调用点都没有：判据整个没接上，本守卫要重新核" }
        return out
    }

    /** 从 [open] 那枚左括号起配平到与之匹配的右括号（含两端） */
    private fun balancedArguments(code: String, open: Int): String {
        var depth = 0
        for (index in open until code.length) {
            when (code[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return code.substring(open, index + 1)
                }
            }
        }
        throw IllegalStateException("第 $open 个字符之后的左括号没配平")
    }

    /** [needle] 在 [hay] 里的全部起点 */
    private fun allOccurrences(hay: String, needle: String): List<Int> {
        val hits = mutableListOf<Int>()
        var at = hay.indexOf(needle)
        while (at >= 0) {
            hits += at
            at = hay.indexOf(needle, at + needle.length)
        }
        return hits
    }

    /** 把 `//` 与 `/* */` 注释抹成空格（字符串保留、长度与换行位置不变）：钉的是接线，不是白话 */
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

    /** 从 [open] 处的括号走到配平的那个闭合括号 */
    private fun matchingClose(code: String, open: Int): Int? {
        if (open < 0 || code[open] !in "([{") return null
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    /** 单测的工作目录是模块目录还是仓库根不由这里决定：几种布局都试一遍，全落空就抛 */
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

    private companion object {
        const val STATS_SCREEN = "com/buaa/schedule/ui/stats/StatsScreen.kt"
        const val KERNEL = "com/buaa/schedule/ui/stats/StatsPageStage.kt"
    }
}
