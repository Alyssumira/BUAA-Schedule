package com.buaa.schedule.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T222 病灶 2（#306）的**接线守卫**：首页那枚冲突警示的"周次"这一维，
 * 是不是真的从 `Conflict.weeks` 一路用到挂图标那一步。
 *
 * 改前的形状（本守卫第 ① 层的对照物，逐字登记在这里）：
 * `val conflictCourseIds: Set<Long> = remember(state.conflicts) { state.conflicts.flatMap { ... }.toSet() }`
 * —— 键表里**没有浏览周次**，取数里**没有周次那一维**，于是周课表翻到哪一周都挂同一批旗。
 *
 * 三把尺：
 * 1. [bannerTakesTheWeekDimensionFromTheDisplayedWeek] —— **逐字节 + 数出现次数**：
 *    页面那一格必须是 `remember(state.conflicts, displayWeekNumber)` 且体内只有纯函数那一枚调用；
 * 2. [thePureKernelUsesTheWeekDimension] —— **读形状**：`conflictCourseIdsForWeek` 的定义恰一处、
 *    体内必须真的按周次过滤（`conflict.weeks.contains(week)`），且 `week == null` 那一档原样交回全部；
 * 3. [gridStillReadsOneIdSetPerWeek] —— **数出现次数**：`WeekView` 那三处消费点（两枚 `inConflict =` 挂旗、
 *    一句读屏、一枚 `Icons.Default.Warning`）一处都没被顺手拆掉 —— 本卡改的是**送进去的那一枚数**，
 *    不是渲染那一侧；渲染侧被改动就是"顺手把展示也挪走了"。
 *
 * 报错消息点名"哪一侧没并尺"：第 ① 层红 = **首页那一维又丢了**；第 ② 层红 = **纯函数自己不再判周次**；
 * 第 ③ 层红 = **周课表那一侧被顺手改了**。
 */
class ConflictCourseIdsPerWeekWiringGuardTest {

    // ─────────────── ① 页面那一格必须吃"屏上正在画的那一周" ───────────────

    @Test
    fun bannerTakesTheWeekDimensionFromTheDisplayedWeek() {
        val home = withoutComments(readMainSource(HOME_FILE))
        assertEquals(
            "**钉法 = 逐字节**：冲突 id 那一格的 remember 键表必须连着浏览周次（漏了这一维 = 翻周不重算，" +
                "与 T56/T68/T68b 那族漏键同一形）。实到 " + occurrences(home, REMEMBER_KEYS_LINE) + " 处",
            1,
            occurrences(home, REMEMBER_KEYS_LINE),
        )
        assertEquals(
            "**钉法 = 逐字节**：那一格的取数只能过 `conflictCourseIdsForWeek`，页面自己不写判据。实到 " +
                occurrences(home, CALL_LINE) + " 处",
            1,
            occurrences(home, CALL_LINE),
        )
        // 改前那一形必须 0 处（数出现次数，先抹注释）—— 注意锚点是**整行**：
        // 同文件里「冲突归并成组」那一格合法地写着 `remember(state.conflicts) {`，只锚前半句会假红
        assertEquals(
            "**钉法 = 数出现次数（抹注释再数）**：页面里那一格改前的整行形状必须 0 处（键表里没有浏览周次），" +
                "它一旦回来就是**首页那一维又丢了**。实到 " + occurrences(home, PRE_FIX_FLAT_MAP) + " 处",
            0,
            occurrences(home, PRE_FIX_FLAT_MAP),
        )
        assertTrue(
            "**钉法 = 读形状**：页面里那一格不许退回不带周次键的写法" +
                "（键表漏一枚就是不随周次重算，与 T56/T68/T68b 那一族同形）",
            !home.contains(PRE_FIX_FLAT_MAP),
        )
        // 两个分支（宽屏并排 + 窄屏 Crossfade）必须吃同一枚数，不许各自算一遍
        assertEquals(
            "**钉法 = 数出现次数**：`conflictCourseIds = conflictCourseIds,` 的递参恰两处" +
                "（宽屏并排那一支 + 窄屏周课表那一支），第三处 = 有人在分支里自己又算了一遍",
            2,
            occurrences(home, "conflictCourseIds = conflictCourseIds,"),
        )
    }

    // ─────────────── ② 纯函数本体必须真的判那一维 ───────────────

    @Test
    fun thePureKernelUsesTheWeekDimension() {
        val home = withoutComments(readMainSource(HOME_FILE))
        val body = balancedBlock(home, "internal fun conflictCourseIdsForWeek(")
        assertEquals(
            "**钉法 = 数出现次数**：这一枚纯函数全仓恰一处定义（抄第二份 = 两处各判各的）。实到 " +
                occurrences(home, "internal fun conflictCourseIdsForWeek(") + " 处",
            1,
            occurrences(home, "internal fun conflictCourseIdsForWeek("),
        )
        assertTrue(
            "**钉法 = 读形状**：函数体必须按**周次**过滤（这一维就是本卡的病），体内实到：\n$body",
            body.contains(WEEK_FILTER_LINE),
        )
        assertTrue(
            "**钉法 = 读形状**：`week == null`（没有学期行 ⇒ 网格全画）那一档必须**全部交回**，" +
                "不许在这里把冲突整条关掉：\n$body",
            body.contains(NULL_WEEK_BRANCH),
        )
        for (banned in listOf("LocalDate.now(", "System.currentTimeMillis(", "repository", "Context")) {
            assertEquals(
                "**钉法 = 读形状**：这一格是纯 JVM 判据（$banned 一出现就不再是），实到 " +
                    occurrences(body, banned) + " 处",
                0,
                occurrences(body, banned),
            )
        }
        // 摊平 id 那一手只能住在纯函数里（全 main 恰一处）
        assertEquals(
            "**钉法 = 数出现次数（全 main 册子）**：`flatMap { conflict -> listOf(conflict.first.id, conflict.second.id) }` " +
                "全仓恰一处 = 那一枚纯函数体内。两处 = 页面与函数各摊一遍，改一处漏一处。\n实到：\n" +
                occurrencesRegister(FILL_LINE),
            1,
            mainOccurrences(FILL_LINE),
        )
    }

    // ─────────────── ③ 渲染那一侧没被顺手改动 ───────────────

    @Test
    fun gridStillReadsOneIdSetPerWeek() {
        val weekView = withoutComments(readMainSource(WEEK_VIEW_FILE))
        assertEquals(
            "**钉法 = 数出现次数**：周课表两枚挂旗点（网格卡 + 时间轴卡）各读一次传入的那一枚集合，" +
                "恰两处 ⇒ 本卡把「这一周」递进去之后，渲染侧形状一字未动",
            2,
            occurrences(weekView, "inConflict = course.id in conflictCourseIds,"),
        )
        assertEquals(
            "**钉法 = 数出现次数**：读屏那句「与其他课程时间冲突」恰一处（改了它 = 动了无障碍那一侧，不在本卡）",
            1,
            occurrences(weekView, "if (inConflict) append(\"，与其他课程时间冲突\")"),
        )
        assertEquals(
            "**钉法 = 数出现次数**：`Icons.Default.Warning` 那枚冲突图标 + 它的 contentDescription 各恰一处",
            1,
            occurrences(weekView, "contentDescription = \"时间冲突\""),
        )
        assertEquals(
            "**钉法 = 读形状**：`WeekView` 的参数表里那一枚 `conflictCourseIds: Set<Long>` 仍是**页面递进来的**" +
                "（网格自己判周次 = 第二把尺，而且它拿不到 ViewModel 那侧的学期）",
            1,
            occurrences(weekView, "conflictCourseIds: Set<Long>,"),
        )
        assertTrue(
            "**钉法 = 读形状**：`WeekView` 里不许出现 `ConflictDetector`（展示侧自己不算冲突，本卡也没给它开这个口子）",
            !weekView.contains("ConflictDetector"),
        )
    }

    // ---------------- 源码核对小工具（与本仓守卫同一套写法）----------------

    private class SourceFile(val relative: String, val text: String)

    private fun readMainSource(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    private fun mainSources(): List<SourceFile> {
        val root = findMainJavaDir()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${root.path} 下一个 .kt 都没有，路径不对", files.isNotEmpty())
        return files.map {
            SourceFile(
                root.toPath().relativize(it.toPath()).toString().replace(File.separatorChar, '/'),
                it.readText().replace("\r\n", "\n"),
            )
        }
    }

    private fun mainOccurrences(needle: String): Int = mainSources().sumOf { occurrences(withoutComments(it.text), needle) }

    private fun occurrencesRegister(needle: String): String = mainSources()
        .map { it.relative to occurrences(withoutComments(it.text), needle) }
        .filter { it.second > 0 }
        .sortedBy { it.first }
        .joinToString(separator = "") { "  ${it.first}: ${it.second} 处\n" }

    /** 按花括号配平从签名起取整段（含签名）；签名里不许出现 lambda 默认值 */
    private fun balancedBlock(source: String, signature: String): String {
        val at = source.indexOf(signature)
        check(at >= 0) { "找不到 $signature：改过名或挪过家就要回来重钉本守卫" }
        val open = source.indexOf('{', at)
        check(open >= at) { "$signature 之后找不到左括号" }
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(at, i + 1)
                }
            }
        }
        throw IllegalStateException("$signature 的花括号没配平")
    }

    private fun occurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var at = haystack.indexOf(needle)
        while (at >= 0) {
            count++
            at = haystack.indexOf(needle, at + needle.length)
        }
        return count
    }

    /** 只抹注释、保留字符串字面量 */
    private fun withoutComments(source: String): String {
        val out = source.toCharArray()
        var i = 0
        while (i < out.size) {
            when {
                source.startsWith("//", i) -> {
                    val nl = source.indexOf('\n', i).let { if (it < 0) out.size else it }
                    for (k in i until nl) out[k] = ' '
                    i = nl
                }

                source.startsWith("/*", i) -> {
                    var depth = 1
                    var j = i + 2
                    while (j < out.size && depth > 0) {
                        when {
                            source.startsWith("/*", j) -> { depth++; j += 2 }
                            source.startsWith("*/", j) -> { depth--; j += 2 }
                            else -> j++
                        }
                    }
                    for (k in i until j.coerceAtMost(out.size)) if (out[k] != '\n') out[k] = ' '
                    i = j
                }

                source.startsWith("\"\"\"", i) -> {
                    i = source.indexOf("\"\"\"", i + 3).let { if (it < 0) out.size else it + 3 }
                }

                out[i] == '"' || out[i] == '\'' -> {
                    val quote = out[i]
                    var j = i + 1
                    while (j < out.size) {
                        when {
                            source[j] == '\\' -> j += 2
                            source[j] == quote -> { j++; break }
                            source[j] == '\n' -> break
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
        repeat(6) {
            val hit = listOf("src/main/java", "app/src/main/java").map { File(dir, it) }.firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }

    private companion object {
        const val HOME_FILE = "com/buaa/schedule/ui/home/HomeScreen.kt"
        const val WEEK_VIEW_FILE = "com/buaa/schedule/ui/home/WeekView.kt"

        const val REMEMBER_KEYS_LINE = "val conflictCourseIds: Set<Long> = remember(state.conflicts, displayWeekNumber) {"
        const val CALL_LINE = "conflictCourseIdsForWeek(state.conflicts, displayWeekNumber)"
        /** 改前那一形（本卡不许它回来）：整行，含"键表里没有周次"那一截 */
        const val PRE_FIX_FLAT_MAP = "val conflictCourseIds: Set<Long> = remember(state.conflicts) {"
        const val WEEK_FILTER_LINE = ".filter { conflict -> week == null || conflict.weeks.contains(week) }"
        const val NULL_WEEK_BRANCH = "week == null ||"
        const val FILL_LINE = "flatMap { conflict -> listOf(conflict.first.id, conflict.second.id) }"
    }
}
