package com.buaa.schedule.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T179 接线守卫：病不在内核（内核有自己的表驱动单测 [WeekChangeSummaryTest]），
 * 在**首页那一句到底把哪一串东西当"门"数**。这一断线也是静默的：
 * 判据照对、文案照念、门禁照绿，只是横幅从此在每一个交接周（理论 1-8 + 实验 9-16，
 * 教务最常见形态）多念一句「比上周多 1 门、少 1 门」，而按门的真相是没变。
 *
 * 刀法照抄 [ConflictShiftWeekScopeWiringGuardTest] / [TopBarDateWiringGuardTest]：读源文件文本、
 * 匹配前先 `blankComments` 抹注释（本卡 KDoc 里就写着被禁的写法本身）、锚点找不到就抛、
 * 不用 assumeTrue 跳过。工作树文件已在 `app/build.gradle.kts` 的 `guardReadWorkingTreeFiles`
 * 输入面里（`src/main/java` 整目录），不必另加机制。
 *
 * 钉五档：
 * 1. 判据内核 [WeekChangeSummary] 零 android/androidx import，两串周号由调用点当参数传
 *    （本仓收单硬判据；"返回布尔后调用点各判一次"这一族同样算违反）；
 * 2. 首页那个 `remember` 块必须把比对**整个交出去**（调 `WeekChangeSummary.summaryText(` 恰好一处），
 *    块内**不许再出现 `.id`**，也不许自己按周筛课（`weeks.contains(`）—— 那就是病灶本体；
 * 3. `remember` 的键表必须列全内核的全部三枚输入（`visibleCourses` / `browseWeek` /
 *    `state.currentWeek`）：带键的 remember 里读了没进键的状态就是静默拿旧缓存
 *    （T41/T43/T68 那一族，界面错而门禁全绿）；
 * 4. 显隐与文案同源：横幅的 `visible` 与那句 `Text` 消费的是同一个 `weekChangeSummary`；
 * 5. "门"的口径全站只有一份 —— `fun courseGroupKey(` 在 main 里恰好一枚（SemesterStats），
 *    内核只取它、不许自己拼 `sourceGroupKey ?: name` 那第二套。
 */
class WeekChangeSummaryWiringGuardTest {

    // ---- ① 内核纯度：零设备依赖，周号一律当参数 ------------------------------------------------

    @Test
    fun theKernelStaysPureJvmAndTakesBothWeekNumbersAsParameters() {
        val code = blankComments(source(KERNEL))
        for (banned in listOf(
            "import android", "import androidx", "Build.VERSION", "LocalConfiguration",
            "LocalContext", "SystemClock", "currentTimeMillis", "LocalDate", "Clock",
        )) {
            val hits = occurrences(code, banned).size
            assertEquals(
                "$KERNEL 里「$banned」出现 $hits 处，期望 0 处：判据自己去读设备/时钟，" +
                    "这张表在 JVM 里就打不开，而本周与上周的周号只许调用点当参数传（本仓硬口径）",
                0,
                hits,
            )
        }
        assertTrue(
            "summaryText 的签名不再收两枚可空周号了：那一判就没人在调用点递周号、只能自己去课表里嗅",
            Regex("""summaryText\(\s*courses: List<Course>,\s*thisWeek: Int\?,\s*lastWeek: Int\?\s*\)""")
                .containsMatchIn(code),
        )
        assertTrue(
            "counts 的签名不再收两枚周号了：交接周那一档的比对失去参数本体",
            Regex("""counts\(\s*courses: List<Course>,\s*thisWeek: Int,\s*lastWeek: Int\s*\)""")
                .containsMatchIn(code),
        )
    }

    // ---- ② 调用点：比对整个交出去，块内不许再数片段 ------------------------------------------

    @Test
    fun homeScreenHandsTheWholeComparisonToTheKernel() {
        val block = weekChangeBlock()
        // 三枚读数一起交出去（不是第一枚不对就短路）：回退成旧写法时，
        // "没调内核 + 块内数了 id + 块内自己按周筛课"三件事要一次报全，枚数都在消息里
        val red = mutableListOf<String>()
        val calls = occurrences(block, "WeekChangeSummary.summaryText(").size
        if (calls != 1) {
            red += "`WeekChangeSummary.summaryText(` 有 $calls 处，期望恰好 1 处 —— " +
                "少了就是摘要不再由内核派生（回到就地数集合），多了就是两处各判一次，" +
                "两条横幅能各念各的门数"
        }
        val ids = Regex("""\.id\b""").findAll(block).toList().size
        if (ids != 0) {
            red += "`.id` 有 $ids 处，期望 0 处 —— 这就是病灶本体：`Course.id` 是**一条排课片段**" +
                "的主键，理论 1-8 周与实验 9-16 周各有各的 id，拿它当「门」就会在每一个交接周" +
                "同时念「多 1 门、少 1 门」"
        }
        val weekFilters = occurrences(block, "weeks.contains(").size
        if (weekFilters != 0) {
            red += "自己按周筛课（`weeks.contains(`）有 $weekFilters 处，期望 0 处 —— " +
                "比对已经交给内核了，留在 UI 层就是第二套能各自写错的账"
        }
        if (!Regex("""summaryText\([^)]*lastWeek\s*=\s*week\?\.minus\(1\)""").containsMatchIn(block)) {
            red += "递进去的 lastWeek 不再是「本周退一格」（`lastWeek = week?.minus(1)`）—— " +
                "摘要会在某个周号上跟错上周，或者干脆不再显示"
        }
        assertTrue(
            "HomeScreen 的 weekChangeSummary 块有 ${red.size} 项不合规，期望 0 项：\n" +
                red.joinToString("\n") +
                "\n复算：grep -n \"mapTo(mutableSetOf()) { it.id }\" " +
                "app/src/main/java/com/buaa/schedule/ui/home/HomeScreen.kt",
            red.isEmpty(),
        )
    }

    // ---- ③ remember 键表列全内核的三枚输入（Compose 陷阱）------------------------------------

    @Test
    fun theRememberKeysCoverEveryKernelInput() {
        val home = blankComments(source(HOME_SCREEN))
        val at = home.indexOf(ANCHOR)
        assertTrue("找不到 `$ANCHOR`：改名/挪家要回来重钉本守卫", at >= 0)
        val openParen = home.indexOf('(', at)
        val closeParen = home.indexOf(')', openParen)
        assertTrue("weekChangeSummary 的 remember 参数括号找不到", openParen >= 0 && closeParen > openParen)
        val keys = home.substring(openParen + 1, closeParen)
        for (key in listOf("visibleCourses", "browseWeek", "state.currentWeek")) {
            val hit = occurrences(keys, key).size
            assertTrue(
                "weekChangeSummary 的 remember 键表漏了 $key（当前键表：$keys）—— " +
                    "带键的 remember 里读了没进键的状态就是静默拿旧缓存：翻周/换校区后横幅还写着上一周的门数，" +
                    "而门禁全绿（T41/T43/T68 同一族坑）",
                hit > 0,
            )
        }
        val listed = listOf("visibleCourses", "browseWeek", "state.currentWeek").count { keys.contains(it) }
        assertEquals(
            "键表列出内核输入 3 枚中的 $listed 枚，期望 3 枚：少一枚就有某一维变化重算不出来",
            3,
            listed,
        )
    }

    // ---- ④ 显隐与文案同源 -------------------------------------------------------------------

    @Test
    fun theBannerVisibilityAndTheCopyReadTheSameValue() {
        val home = blankComments(source(HOME_SCREEN))
        assertTrue(
            "周次异动条的 visible 不再消费 weekChangeSummary（与上一条冲突横幅同族那一行是 " +
                "`visible = selectedTab == 0 && weekChangeSummary != null`）：显隐与文案分家就是" +
                "要么永远不显示、要么显示一句空话",
            home.contains("visible = selectedTab == 0 && weekChangeSummary != null,"),
        )
        assertTrue(
            "那句 Text 不再念 weekChangeSummary：摘要算好了没人念，等于没算",
            home.contains("text = \"这一周的课程与上周不同：\${weekChangeSummary.orEmpty()}\""),
        )
    }

    // ---- ⑤ "门"的口径全站只有一份 -------------------------------------------------------------

    @Test
    fun thereIsExactlyOneDoorKeyDefinitionInTheRepo() {
        val definitions = mainFilesWith("fun courseGroupKey(")
        val total = definitions.sumOf { it.second }
        assertEquals(
            "main 里 `fun courseGroupKey(` 定义了 $total 处（${definitions.joinToString { "${it.first}:${it.second}" }}），" +
                "期望恰好 1 处（SemesterStats）：长出第二套「门」的口径，学分归并、周次覆盖图、" +
                "每日门数、趋势结课周与这条横幅就会各说各话",
            1,
            total,
        )
        val code = blankComments(source(KERNEL))
        val taken = occurrences(code, "SemesterStats.courseGroupKey(").size
        assertTrue(
            "内核取 SemesterStats.courseGroupKey 的枚数是 $taken，期望 ≥1：摘要与统计页那一族" +
                "就不是同一个「门」了",
            taken >= 1,
        )
        // 内核不许自己退化成"名字即身份"（那是 SemesterStats 已经写下并接受的粗口径，只许在那一处）
        val inlined = occurrences(blankComments(code), "sourceGroupKey ?").size
        assertEquals(
            "内核里自己拼了组键兜底（`sourceGroupKey ?…`）$inlined 处，期望 0 处：" +
                "退化档由 SemesterStats 一处承担，这里再抄一份就等于给同一件事写了两套能各自漂移的兜底",
            0,
            inlined,
        )
    }

    // ---------------- helpers ----------------

    /** 首页那一整块 `val weekChangeSummary = remember(...) { … }`（含键表与 lambda） */
    private fun weekChangeBlock(): String {
        val code = blankComments(source(HOME_SCREEN))
        val at = code.indexOf(ANCHOR)
        assertTrue("找不到 `$ANCHOR`：摘要的落点被挪走了，本守卫要跟着重钉", at >= 0)
        val open = code.indexOf('{', at)
        assertTrue("weekChangeSummary 找不到 lambda 的开括号", open >= 0)
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(at, i + 1)
                }
            }
        }
        throw IllegalStateException("weekChangeSummary 的花括号没配平")
    }

    private fun source(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 $relative：挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /** 全仓 main 里扫某串文本，返回 (相对路径, 命中次数)，只挑出有命中的文件 */
    private fun mainFilesWith(needle: String): List<Pair<String, Int>> {
        val root = findMainJavaDir()
        val hits = mutableListOf<Pair<String, Int>>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val count = occurrences(blankComments(file.readText()), needle).size
            if (count > 0) hits += file.relativeTo(root).path.replace('\\', '/') to count
        }
        return hits
    }

    private fun occurrences(hay: String, needle: String): List<Int> {
        val found = mutableListOf<Int>()
        var at = hay.indexOf(needle)
        while (at >= 0) {
            found += at
            at = hay.indexOf(needle, at + needle.length)
        }
        return found
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
        const val KERNEL = "com/buaa/schedule/ui/home/WeekChangeSummary.kt"
        const val HOME_SCREEN = "com/buaa/schedule/ui/home/HomeScreen.kt"
        const val ANCHOR = "val weekChangeSummary = remember("
    }
}
