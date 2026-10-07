package com.buaa.schedule.ui.onboarding

import com.buaa.schedule.canBeNavRoot
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引导出口的落点册子 + 引导结束对三枚一次性请求的表态（T244④，台账 #391 与 #392）。
 *
 * 基点上这一格四家守卫皆零格：`grep -rn "onFinished" app/src/test --include=*.kt` 零命中，
 * `requestedRoute` 在 `app/src/test` 的命中全在 `core/` 那一族，`ui/onboarding/` 一条没有。
 * 也就是说引导页交回的落点有哪些、宿主拿它们怎么办，从来没有尺量过。
 *
 * 刀法照抄 [com.buaa.schedule.core.LaunchRequestWiringGuardTest]：读源文件文本、匹配前先
 * `blankComments` 抹注释、找不到锚点就抛、不用 assumeTrue 跳过。落点集合 ⊆ 在册集合这一档
 * 走的是真判据 `com.buaa.schedule.canBeNavRoot`（纯 JVM、零 android import），
 * 不是在测试里另抄一份名单 —— 另抄就是第二本账。
 *
 * 两侧变异都有格子压着：
 * - **朝窄**（`navStartRoute` / `pendingOneShotRoute` 退回按前缀摘 `editor/`）⇒
 *   [com.buaa.schedule.nav.NavRootStartDestinationGuardTest] ④ 亮红；
 * - **朝宽**（把在册集合放宽到允许所有落点当根）⇒ 本文件 ② 亮红（`import` 成了根栈安全的出口，
 *   报错消息点出是哪一枚落点破了册子），[com.buaa.schedule.nav.NavRootStartRoutePolicyTest] ①③
 *   同批亮红。
 */
class OnboardingExitRouteGuardTest {

    /** ① 出口枚数册子：`ui/onboarding/` 里 `onFinished(` 交回的落点就这一张表，多一枚少一枚都算漂。 */
    @Test
    fun onboardingHasExactlyTheRegisteredExits() {
        val exits = exitLiterals()
        assertEquals(
            "OnboardingScreen.kt 里 onFinished( 的调用处数应当恰好 4 枚" +
                "（关掉引导 / 最后一步点下一步 / 导入你的课表 / 手动添加课程）：\n" + exits.joinToString("\n"),
            4,
            exits.size,
        )
        assertEquals(
            "$EXIT_ANCHOR 交回的落点集合与册子对不上（期望：两枚 null = 落回首页、" +
                "一枚 import、一枚 editor/-1）：\n" + exits.joinToString("\n"),
            listOf("editor/-1", "import", "null", "null"),
            exits.sorted(),
        )
    }

    /**
     * ② 读形状：每一枚字面量出口都必须被宿主分过类 —— 要么在册当根栈，要么走一次性 navigate，
     * 而**引导的任何一枚字面量出口都不该是根栈安全的那一枚**（首页那一档走的是 null，不是字面量）。
     */
    @Test
    fun everyLiteralExitIsClassifiedByTheHost() {
        val registered = registeredRoutes()
        for (literal in exitLiterals().filter { it != "null" }) {
            assertFalse(
                "$EXIT_ANCHOR 交回的落点 $literal 现在被判成可以当 NavHost 的根栈 —— 破了 " +
                    "MainActivity.ROOT_SAFE_START_ROUTES 那本册子。它一旦当上 startDestination，" +
                    "navigateTopLevel 的 popUpTo(graph.findStartDestination().id) 就指回它自己，" +
                    "返回键在这一页与首页之间无限对倒（台账 #391）",
                canBeNavRoot(literal),
            )
            assertTrue(
                "$EXIT_ANCHOR 交回的落点 $literal 在 MainActivity 的 NavHost 里没有任何一条 " +
                    "composable 与之对应，一次性 navigate 会把它交给 NavHost 而那边抛 " +
                    "IllegalArgumentException。在册路由：" + registered.sorted().joinToString(", "),
            resolvesToRegisteredRoute(literal, registered),
            )
        }
    }

    /**
     * ③ 引导出口对三枚一次性请求的表态（台账 #392，第 0 问的裁定 = 清掉）：
     * 三行 `= null` 全在，且都排在 `onboardingDone = true` 之前 —— 换脸那一帧读不到旧值。
     */
    @Test
    fun onboardingExitInvalidatesTheQueuedLaunchRequests() {
        val body = balancedBlock(main(), "onFinished = { startRoute ->")
        for (state in listOf("requestedCourseId", "requestedRoute", "requestedDayOfWeek")) {
            val clears = body.lines().map { it.trim() }.filter { it == "$state.value = null" }
            assertEquals(
                "$state 在引导出口处的清零处数应当恰好一处（零处 = 那笔请求还躺在 Activity 字段里，" +
                    "出引导第一帧就会被踢到用户没有选过的目的页；两处 = 同一档写了两遍）：\n$body",
                1,
                clears.size,
            )
            assertTrue(
                "$EXIT_ANCHOR 里 $state 的清零排在 onboardingDone 翻面之后 —— 主界面那一棵子树" +
                    "先组合上了，第一帧就吃到旧值：\n$body",
                body.indexOf("$state.value = null") < body.indexOf("onboardingDone = true"),
            )
        }
    }

    /**
     * ④ 三枚状态在 MainActivity 里仍然只有 `= null` 这一族的取走处（消费方各一处 + 引导出口一处）：
     * 内核"重建那一档不认"靠的就是上一次生命周期里它已经被取走过。
     */
    @Test
    fun theThreeRequestStatesStayOneShot() {
        val text = blankComments(main())
        for (state in listOf("requestedCourseId", "requestedRoute", "requestedDayOfWeek")) {
            // 用 contains 而不是按行首匹配：消费方那一枚写在 lambda 里
            // （`onCourseRequestConsumed = { requestedCourseId.value = null }`），行首是回调名
            val clears = text.lines().count { it.contains("$state.value = null") }
            assertTrue(
                "$state 的 `= null` 取走处数应当 ≥ 2（消费方那一处 + 引导出口这一处，实到 $clears 处）" +
                    "—— 少于两处就是某一趟生命周期之后它还留着，下一次组合会再跳一遍",
                clears >= 2,
            )
        }
    }

    // ---- 在册集合的取法 ----

    /** NavHost 里注册过的路由：`composable("x")` 与带模板的 `route = "x/{y}"` 两形都认。 */
    private fun registeredRoutes(): Set<String> {
        val text = blankComments(main())
        val found = mutableSetOf<String>()
        for (pattern in listOf(Regex("""composable\("([^"]+)"\)"""), Regex("""route = "([^"]+)"""))) {
            pattern.findAll(text).forEach { found += it.groupValues[1] }
        }
        assertTrue("$HOST_ANCHOR 一条注册路由都没扫出来：NavHost 的写法换过了，这条守卫要跟着改", found.isNotEmpty())
        return found
    }

    /** 具体落点对不对得上册子里的某一条：模板段（`{...}`）当通配，按 `/` 逐段比。 */
    private fun resolvesToRegisteredRoute(literal: String, registered: Set<String>): Boolean {
        val segments = literal.split("/")
        return registered.any { template ->
            val parts = template.split("/")
            parts.size == segments.size && parts.zip(segments).all { (pattern, value) ->
                pattern.startsWith("{") || pattern == value
            }
        }
    }

    /** `ui/onboarding/` 里每一枚 `onFinished(` 交回的字面量（`null` 原样记成 "null"）。 */
    private fun exitLiterals(): List<String> {
        val text = blankComments(onboarding())
        // 引号在这里是写法的一部分而不是内容：摘掉才剩下降给宿主的落点本身
        return Regex("""onFinished\(([^)]*)\)""").findAll(text)
            .map { it.groupValues[1].trim().trim('"') }
            .toList()
    }

    // ---- helpers ----

    private fun main(): String = source(MAIN_ACTIVITY)

    private fun onboarding(): String = source(ONBOARDING)

    private fun source(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /** 从 [signature] 之后第一个 `{` 起配平到对应右括号（含）；找不到锚点就抛，静默跳过等于没有守卫 */
    private fun balancedBlock(source: String, signature: String): String {
        val text = blankComments(source)
        val at = text.indexOf(signature)
        check(at >= 0) { "找不到 $signature：写法换过了，这条守卫要跟着改" }
        val open = text.indexOf('{', at)
        check(open >= at) { "$signature 之后找不到左括号" }
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(at, index + 1)
                }
            }
        }
        throw IllegalStateException("$signature 的花括号没配平")
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
        const val MAIN_ACTIVITY = "com/buaa/schedule/MainActivity.kt"
        const val ONBOARDING = "com/buaa/schedule/ui/onboarding/OnboardingScreen.kt"
        // 报错消息里的锚点与符号名同框（本仓行号会漂，这里不写行号）
        const val EXIT_ANCHOR = "OnboardingScreen.kt 的 onFinished( 出口"
        const val HOST_ANCHOR = "MainActivity 的 NavHost"
    }
}
