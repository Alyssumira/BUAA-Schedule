package com.buaa.schedule.nav

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「引导落点被宿主当成栈根」那一族的接线守卫（T244④，台账 #391）。
 *
 * 刀法照抄 [com.buaa.schedule.core.LaunchRequestWiringGuardTest]：读源文件文本、匹配前先
 * `blankComments` 抹注释（本卡的注释里就写着 `navigateTopLevel` 这类被禁写法本身，
 * 连注释一起扫会红在自己人手上）、找不到锚点就抛、不用 assumeTrue 跳过。
 * 本模块没有 Compose 运行时，`AppNavHost` 那一行到底吃了谁只能扫源码。
 *
 * 为什么这一族值得单独钉：基点上的四枚在册守卫（`HeaderBandOwnershipTest`、
 * `StatsEntryWiringGuardTest`、`ScanSignInEntryWiringGuardTest`、
 * `CourseEditorDeleteExitReceiverGuardTest`）数的是入口接线与带归属，
 * **没有一枚断言过 `startDestination` 与 `navigateTopLevel` 的 `popUpTo` 目标同源**。
 * 于是 `import` 当了 startDestination 之后，`popUpTo(graph.findStartDestination().id)` 指回它自己，
 * 返回键在导入页与首页之间无限对倒。本文件钉的是这条不变式的五个侧面：
 *
 * 1. 判据纯度：`canBeNavRoot` 只吃字符串，体内零 android、零读钟、不碰 NavController。
 * 2. **`startDestination` 那一行不许再吃原始 `startRoute`**（卡 §3.4 的 b 档，数出现次数）。
 * 3. `AppNavHost` 的形参名必须是过了判据那一枚（`rootStartRoute`），两个调用点传的必须是
 *    `navStartRoute`。
 * 4. 判据两半都在册：`takeIf { canBeNavRoot(it) }` 与 `takeUnless { canBeNavRoot(it) }`
 *    各恰好一处，谁退回按前缀摘 `editor/` 就在这一档亮红（**朝窄变异的红名落点**）。
 * 5. 一次性链的顺序（先清后跳）与 import 的 `onBack` 兜底（不再无条件跳首页）。
 */
class NavRootStartDestinationGuardTest {

    // ---- ① 判据纯度 ----

    @Test
    fun canBeNavRootIsPureJvmPredicate() {
        // 这一枚是表达式体函数，没有花括号可配平：按「锚点起到第一个空行为止」切
        val fromAnchor = lines().dropWhile { !it.contains("internal fun canBeNavRoot") }
        assertFalse("找不到 internal fun canBeNavRoot：判据挪过家或被拆开了", fromAnchor.isEmpty())
        val body = fromAnchor.takeWhile { it.isNotBlank() }
        val offenders = body.map { it.trim() }.filter {
            it.startsWith("import android") || it.startsWith("import androidx")
        }
        assertTrue("canBeNavRoot 体内混进了设备侧依赖：\n" + offenders.joinToString("\n"), offenders.isEmpty())
        val clock = body.map { it.trim() }.filter {
            it.contains("System.currentTimeMillis") || it.contains("Calendar") ||
                it.contains("LocalDate.now(") || it.contains("LocalDateTime") || it.contains("Clock.")
        }
        assertTrue("canBeNavRoot 体内读了钟：\n" + clock.joinToString("\n"), clock.isEmpty())
        // 判据只许读那一枚字符串与册子：碰 NavController 就是换了个地方各判一次
        val bypass = body.map { it.trim() }.filter {
            it.contains("navController") || it.contains("NavController") || it.contains("savedInstanceState")
        }
        assertTrue(
            "canBeNavRoot 不许碰 NavController / savedInstanceState，只接抽好的字符串：\n" +
                bypass.joinToString("\n"),
            bypass.isEmpty(),
        )
        assertTrue(
            "$PREDICATE_ANCHOR 体内只许引用 ROOT_SAFE_START_ROUTES 那一本册子，不许自带第二份名单：\n" +
                body.joinToString("\n"),
            body.any { it.contains("ROOT_SAFE_START_ROUTES") },
        )
    }

    // ---- ② startDestination 那一行吃的是过了判据的值（数出现次数）----

    @Test
    fun startDestinationNeverTakesTheRawStartRoute() {
        val decls = lines().filter { it.trim().startsWith("startDestination =") }
        assertEquals(
            "全文件 `startDestination =` 的赋值处数应当恰好一处（多一处就是 NavHost 之外又冒出一处起点）：" +
                "\n" + decls.joinToString("\n"),
            1,
            decls.size,
        )
        val line = decls.single().trim()
        assertTrue(
            "$DEST_ANCHOR 读的是 $line —— 它必须吃过了 canBeNavRoot 的那一枚。原始 startRoute 里" +
                "import 与 editor/-1 都在；它一路走到 startDestination 就是台账 #391 的根因：那一页当了" +
                "返回栈的地板，而 navigateTopLevel 的 popUpTo(graph.findStartDestination().id) 弹的正是它自己",
            line.contains("rootStartRoute"),
        )
        assertFalse(
            "$DEST_ANCHOR 退回了原始 startRoute（引导交回的值，未经任何判据）：\n$line",
            line.contains("= startRoute"),
        )
    }

    // ---- ③ AppNavHost 的形参名就是那道契约 ----

    @Test
    fun appNavHostParameterIsTheFilteredValue() {
        val all = lines()
        val at = all.indexOfFirst { it.contains("private fun AppNavHost") }
        check(at >= 0) { "找不到 private fun AppNavHost：签名换过了，这条守卫要跟着改" }
        val params = all.drop(at + 1).takeWhile { !it.trim().startsWith(") {") }
        check(params.isNotEmpty()) { "private fun AppNavHost 之后取不到形参表" }
        val named = params.filter { it.trim().startsWith("rootStartRoute:") }
        assertEquals(
            "AppNavHost 的形参 rootStartRoute 应当恰好一枚（名字本身就是「只收过了判据的值」这一档契约）：\n" +
                params.joinToString("\n"),
            1,
            named.size,
        )
        assertFalse(
            "AppNavHost 形参表里不许同时留着 startRoute 那一枚：与 rootStartRoute 同表就是两本账，" +
                "而原始值一路走到 startDestination 就是台账 #391：\n" + params.joinToString("\n"),
            params.any { it.trim().startsWith("startRoute:") },
        )
        val forwarded = all.map { it.trim() }.filter { it.startsWith("rootStartRoute =") }
        assertEquals(
            "rootStartRoute 的实参处数应当恰好两处（宽屏导航栏 + 手机底栏那两个 AppNavHost 调用点）：\n" +
                forwarded.joinToString("\n"),
            2,
            forwarded.size,
        )
        for (line in forwarded) {
            assertTrue(
                "$APPHOST_ANCHOR 传的不是 navStartRoute —— 那就是把原始落点又递回 NavHost 了：\n$line",
                line.contains("= navStartRoute"),
            )
        }
    }

    // ---- ④ 判据两半都在册（朝窄变异的红名落点）----

    @Test
    fun bothHalvesOfTheSplitUseTheRegistryPredicate() {
        val all = lines().map { it.trim() }
        for ((anchor, expected) in listOf(
            "val navStartRoute =" to "takeIf { canBeNavRoot(it) }",
            "mutableStateOf(startRoute?." to "takeUnless { canBeNavRoot(it) }",
        )) {
            val hits = all.filter { it.startsWith(anchor) }
            assertEquals(
                "$SPLIT_ANCHOR 这一族的赋值处数应当恰好一处（多一处就是同一句判据被抄成两份）：\n" +
                    hits.joinToString("\n"),
                1,
                hits.size,
            )
            val line = hits.single()
            assertTrue(
                "$line 退回了按前缀摘 editor/ —— 于是 import 又被放行当根栈，" +
                    "台账 #391 那对 [import, home] 与 [import] 的无限对倒就回来了。" +
                    "期望这一句里带着 $expected：",
                line.contains(expected),
            )
            assertFalse(
                "这一句里还剩着按前缀分家的写法：\n$line",
                line.contains(PREFIX_FORM),
            )
        }
    }

    // ---- ⑤ 一次性链的顺序：先清后跳 ----

    @Test
    fun oneShotNavigateClearsBeforeItNavigates() {
        val body = balancedBlock("LaunchedEffect(pendingOneShotRoute)")
        val clear = body.indexOf("pendingOneShotRoute = null")
        val navigate = body.indexOf("navController.navigate(route)")
        assertTrue("一次性那手里找不到 pendingOneShotRoute = null：\n$body", clear >= 0)
        assertTrue("一次性那手里找不到 navigate(route)：\n$body", navigate >= 0)
        assertTrue(
            "$ONE_SHOT_ANCHOR 的先后次序是「先清后跳」，反过来就不成立 —— 先跳再清的话，" +
                "navigate 之后任何一次重组都会再跳一遍：\n$body",
            clear < navigate,
        )
    }

    // ---- ⑥ import 的 onBack 兜底不再无条件跳首页 ----

    @Test
    fun importBackFallbackStopsExitingIntoAPingPong() {
        val body = balancedBlock("""composable("import") {""")
        val hits = body.lines().map { it.trim() }
            .filter { it.startsWith("if (!navController.popBackStack())") }
        assertEquals(
            "$IMPORT_ANCHOR 应当恰好一处弹不动兜底（多一处就是同一档写了两句）：\n" + hits.joinToString("\n"),
            1,
            hits.size,
        )
        val line = hits.single()
        assertFalse(
            "$IMPORT_ANCHOR 退回了无条件跳首页：navigateTopLevel 弹的是 graph.findStartDestination()，" +
                "import 一旦当上根栈，这一手既弹不掉 import 又把首页叠上去，返回键从此退不出去：\n$line",
            line.contains("navigateTopLevel"),
        )
        assertTrue(
            "$IMPORT_ANCHOR 的兜底必须走那枚收掉 Activity 的回调（弹不动 = 在册集合被破，" +
                "此时该退出就退出）：\n$line",
            line.contains("onExitApp()"),
        )
        // 那枚回调不许有缺省值，也不许在唯一的调用点上被漏掉：默认成 {} 就是"摆着出口、点了没反应"
        val all = lines()
        assertTrue(
            "onExitApp 不许带缺省值：AppNavHost 与 BUAAScheduleApp 两个形参都得是必填，" +
                "否则漏传一处就是静默 no-op：\n" +
                all.filter { it.trim().contains("onExitApp:") }.joinToString("\n"),
            all.none { it.trim().startsWith("onExitApp:") && it.contains("= {}") },
        )
        val wired = all.map { it.trim() }.filter { it.startsWith("onExitApp = ::finish") }
        assertEquals(
            "onExitApp 的真实实现只许在 BUAAScheduleApp 那一处接上 Activity 自己：\n" +
                all.filter { it.trim().startsWith("onExitApp =") }.joinToString("\n"),
            1,
            wired.size,
        )
        val passedDown = all.map { it.trim() }.count { it == "onExitApp = onExitApp," }
        assertEquals(
            "AppNavHost 的两个调用点（宽屏 + 手机）都必须把这枚回调递下去，漏一个就是一支静默：",
            2,
            passedDown,
        )
    }

    // ---- helpers（刀法照抄 LaunchRequestWiringGuardTest，只是本文件不需要设备侧那几枚）----

    /** 抹过注释的 MainActivity 逐行原文（字符串保留、换行位置不变） */
    private fun lines(): List<String> = blankComments(source(MAIN_ACTIVITY)).lines()

    private fun source(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /** 从 [signature] 之后第一个 `{` 起配平到对应右括号（含）；找不到锚点就抛，静默跳过等于没有守卫 */
    private fun balancedBlock(signature: String): String {
        val text = blankComments(source(MAIN_ACTIVITY))
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

        // 基点上那一句按前缀分家的写法，逐形认（注释也参与这一枚的比对，故取原文常量）
        const val PREFIX_FORM = """startsWith("editor/"""

        // 报错消息里的锚点与符号名同框（本仓行号会漂，这里不写行号）
        const val PREDICATE_ANCHOR = "MainActivity: `internal fun canBeNavRoot` 那一行"
        const val DEST_ANCHOR = "MainActivity: NavHost 的 `startDestination =` 那一行"
        const val APPHOST_ANCHOR = "MainActivity: AppNavHost 的两个调用点"
        const val SPLIT_ANCHOR = "MainActivity: BUAAScheduleApp 里那两半判据（navStartRoute / pendingOneShotRoute）"
        const val ONE_SHOT_ANCHOR = "MainActivity: `LaunchedEffect(pendingOneShotRoute)` 体内"
        const val IMPORT_ANCHOR = "MainActivity: `composable(\"import\")` 那一枚目的地的 onBack"
    }
}
