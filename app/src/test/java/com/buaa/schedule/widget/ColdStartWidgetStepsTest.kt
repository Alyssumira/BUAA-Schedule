package com.buaa.schedule.widget

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一次冷唤醒里"桌面上到底有没有我们的组件"只跨 binder 问一遍（审计 §2.1）。
 *
 * 改动前 `BUAAApplication.onCreate` 那条后台链问三遍同一个问题：
 * [BackgroundSync.refreshWidgets] 一遍、[BackgroundSync.scheduleWidgetMidnight] 一遍、
 * [WidgetFallbackWorker.ensure] 一遍。`hasAnyWidget` 是对 6 个 Provider 逐个
 * `getAppWidgetIds`（`any { }` 短路，最坏 6 趟），三处落在同一协程同一时刻附近，
 * 中间没有任何会改变组件数的写入 —— 结论不可能不同，多付的都是 binder 往返。
 *
 * 为什么测 [BackgroundSync.runColdStartWidgetSteps] 这个不接 Context 的编排入口，
 * 而不是真的跑一次 `BUAAApplication`：本模块单测没有 Robolectric（android.jar 里全是抛
 * "not mocked" 的桩），`AppWidgetManager` / `Context` 都拿不到。这条改动的风险面恰好只有
 * "探测问了几遍、没有组件时重活有没有短路、有组件时三步是否都跑到"，
 * 这三件事就是注入的 lambda 被调的次数与顺序，所以钉住次数等于钉住口径。
 * 形状抄 `WidgetSnapshotPlanTest`（它钉的是"节次表整轮只查一次"）。
 *
 * 生产接线（哪一步该拿到结论、哪一步仍然自己探测）是源码形状的事，JVM 侧跑不到，
 * 所以另外按源码核对 —— 定位源码的办法与 GlassSurfaceSingleChildTest /
 * ClassProgressReceiverMainThreadTest 一致，找不到目录或文件就直接抛，不用 assumeTrue 跳过：
 * 找错路径只表现为"永远是绿的"，比红更糟。
 */
class ColdStartWidgetStepsTest {

    /** 一条链路的执行记录：探测被问了几遍、每个动作跑没跑、按什么顺序 */
    private class Trace {
        val ran = mutableListOf<String>()
        var probeCalls = 0
        val failures = mutableListOf<String>()

        fun steps(): String = ran.joinToString(",")
    }

    /** 跑一遍链路；[failing] 那一步（按名字）抛异常，用来验逐步吞异常 */
    private fun runChain(verdict: Boolean, trace: Trace, failing: String? = null, probeThrows: Boolean = false) =
        runBlocking {
            BackgroundSync.runColdStartWidgetSteps(
                probeHasWidgets = {
                    trace.probeCalls += 1
                    trace.ran += "probe"
                    if (probeThrows) throw IllegalStateException("DeadObjectException")
                    verdict
                },
                invalidateWidgetCache = { act(trace, failing, "invalidate") },
                refreshWidgetData = { act(trace, failing, "refresh") },
                scheduleMidnightAlarm = { act(trace, failing, "midnight") },
                cancelMidnightAlarm = { act(trace, failing, "cancelMidnight") },
                scheduleTomorrowPreview = { act(trace, failing, "preview") },
                ensureFallbackWorker = { hasWidgets -> act(trace, failing, "fallback:$hasWidgets") },
                reportStepFailure = { label, _ -> trace.failures += label },
            )
        }

    private suspend fun act(trace: Trace, failing: String?, name: String) {
        trace.ran += name
        if (failing == name) throw IllegalStateException("$name 这一步抛了")
    }

    /** 有组件：探测一次，重活 + 零点闹钟 + 明日预告 + 兜底登记全部跑到，顺序与改动前逐条一致 */
    @Test
    fun `一次链路只问一遍组件，三个下游步骤全都跑得到`() {
        val trace = Trace()

        val verdict = runChain(verdict = true, trace = trace)

        assertTrue("探测说有组件，链路的结论却是 false", verdict)
        assertEquals("一条链路里跨 binder 问了几遍 Launcher", 1, trace.probeCalls)
        assertEquals(
            "有组件时的步骤与顺序：少一步就是组件不再刷新或零点闹钟没排上，多一步就是探测又没共享",
            "probe,invalidate,refresh,midnight,preview,fallback:true",
            trace.steps(),
        )
        assertTrue("正常跑完不该有失败留痕", trace.failures.isEmpty())
    }

    /** 没有组件：三个重活一个都不跑，只撤零点闹钟；兜底登记仍要跑（没有组件不等于没有提醒，R5 F-16） */
    @Test
    fun `探测为 false 时组件侧的重活一个都不跑`() {
        val trace = Trace()

        val verdict = runChain(verdict = false, trace = trace)

        assertFalse(verdict)
        assertEquals("一条链路里跨 binder 问了几遍 Launcher", 1, trace.probeCalls)
        assertEquals(
            "没有组件时不许碰全学期快照 sync 与 6 次重绘，也不许排零点闹钟（那是给组件日期滚动用的）",
            "probe,invalidate,cancelMidnight,preview,fallback:false",
            trace.steps(),
        )
    }

    /**
     * 逐步吞异常：任何一步抛了，后面的步骤照跑。
     *
     * 这条口径原先写在 `BUAAApplication` 那段注释里（"整块兜时它一抛，后面的明日预告与
     * WidgetFallbackWorker 整轮不注册"，而 HyperOS 那批机型恰恰全靠兜底刷新活着）。
     * 四步收进链路之后保护只有这里一份，所以挪进 [BackgroundSync.runColdStartWidgetSteps]。
     */
    @Test
    fun `任何一步抛异常都不许断掉后面的步骤`() {
        val cases = listOf(
            true to "invalidate",
            true to "refresh",
            true to "midnight",
            true to "preview",
            true to "fallback:true",
            false to "cancelMidnight",
            false to "preview",
            false to "fallback:false",
        )
        for ((verdict, failing) in cases) {
            val trace = Trace()

            runChain(verdict = verdict, trace = trace, failing = failing)

            // 探测 + 该分支上全部 4~5 个动作都要被尝试过，一个都不能因为前一步抛了就消失
            val expected = if (verdict) 6 else 5
            assertEquals(
                "$failing 抛了，后面的步骤没跑完：\n${trace.steps()}",
                expected,
                trace.ran.size,
            )
            assertEquals("失败的留痕只该有一处：$failing", listOf(failingLabelOf(failing)), trace.failures)
        }
    }

    /**
     * 探测失败的口径：按"有组件"处理（宁可多刷一次，不能因探测失败把组件留在昨天）。
     *
     * 这条是 `hasAnyWidgetSafely` 定的方向，反过来就是"探测一抛、组件停在昨天"。
     */
    @Test
    fun `探测抛异常时按有组件处理，组件侧的重活照跑`() {
        val trace = Trace()

        val verdict = runChain(verdict = false, trace = trace, probeThrows = true)

        assertTrue("探测抛了就该按有组件处理，不能反过来当成没组件", verdict)
        assertEquals(1, trace.probeCalls)
        assertEquals(
            "探测失败后走的是有组件那条分支",
            "probe,invalidate,refresh,midnight,preview,fallback:true",
            trace.steps(),
        )
        assertEquals(listOf("probeHasWidgets"), trace.failures)
    }

    /** 生产接线：整条链只出现一次探测，三个下游各自拿到结论，没有一处还在自己问 Launcher */
    @Test
    fun `冷启动那四步的接线只留一次探测`() {
        val source = readMainSource(BACKGROUND_SYNC_FILE)
        val wiring = declarationBlock(source, "suspend fun runColdStartWidgetSteps(")
        val flat = normalize(wiring)

        assertEquals(
            "冷启动链路里跨 binder 的探测只能有一次（多一次就是审计 §2.1 那笔白付的往返）：\n$flat",
            1,
            occurrences(flat, "hasAnyWidgetSafely("),
        )
        assertTrue(
            "探测没有交给链路的开头：\n$flat",
            flat.contains("probeHasWidgets = { hasAnyWidgetSafely(context) }"),
        )
        assertTrue(
            "刷组件这一步没共享结论（裸调 refreshWidgets(context) 会自己再问一遍 Launcher）：\n$flat",
            !flat.contains("refreshWidgets(context)") &&
                flat.contains("refreshWidgetData = { syncAndRedrawAllWidgets(context) }"),
        )
        assertTrue(
            "零点闹钟这一步没共享结论：\n$flat",
            flat.contains("scheduleWidgetMidnight(context, hasAnyWidget = true)") &&
                !flat.contains("scheduleWidgetMidnight(context)"),
        )
        assertTrue(
            "兜底任务这一步没共享结论（裸调 ensure(context) 会自己再问一遍 Launcher）：\n$flat",
            !flat.contains("ensure(context)") && flat.contains("ensure(context, hasAnyWidget = hasWidgets)"),
        )
        assertTrue(
            "撤残留零点闹钟那一步没了（没有组件时这条还得跑）：\n$flat",
            flat.contains("cancelMidnightAlarm = { cancelWidgetMidnight(context) }"),
        )

        // 兜底 Worker 自己那条链同理：doWork 开头已经问了一遍
        val worker = readMainSource(FALLBACK_WORKER_FILE)
        val doWork = normalize(declarationBlock(worker, "override suspend fun doWork(): Result"))
        assertTrue(
            "doWork 开头的探测结果没传给 onDataChanged，refreshWidgets 会再问一遍 Launcher：\n$doWork",
            !doWork.contains("onDataChanged(applicationContext)"),
        )
        assertEquals(
            "doWork 里跨 binder 的探测只能有一次：\n$doWork",
            1,
            occurrences(doWork, "hasAnyWidgetSafely("),
        )
    }

    /**
     * 组件真的被增删的那两条路必须仍然自己当场探测 —— 这是"传参数而不是上缓存"的代价所在。
     *
     * `onEnabled`（拖上第一个组件）与 `onDisabled`（拆掉最后一个组件）是仅有的两处
     * "组件数刚刚变了"的地方：它们要的是**变化之后**的结论。谁要是把这两条也接到某个
     * 先前的探测结果上（缓存、或者从别处传进来的布尔），新组件就会既没有零点闹钟也没有
     * 兜底轮询、一直停在放置当天（上一轮 F-26 是同一族事故）。
     */
    @Test
    fun `组件被增删的两条路径仍然自己当场探测`() {
        val common = readMainSource(WIDGET_COMMON_FILE)
        val bootstrap = normalize(declarationBlock(common, "internal fun bootstrapBackgroundSync(context: Context)"))
        assertTrue(
            "onEnabled 的补注册要自己现问 Launcher，不接受别处传进来的结论：\n$bootstrap",
            bootstrap.contains("scheduleWidgetMidnight(context)") && bootstrap.contains("ensure(context)"),
        )

        val background = readMainSource(BACKGROUND_SYNC_FILE)
        val disabled = normalize(declarationBlock(background, "fun cancelWidgetMidnightIfNoWidgets(context: Context)"))
        assertTrue(
            "onDisabled 那条路自己探测（六个 Provider 的 onDisabled 直连这里，没有上游可共享）：\n$disabled",
            disabled.contains("hasAnyWidgetSafely(context)"),
        )
        assertEquals(
            "onDisabled 这条链里探测只能有一次（ensure 走默认参数是第二次现问，那是它自己的口径，链路不该把结论递过去）：\n$disabled",
            1,
            occurrences(disabled, "hasAnyWidgetSafely("),
        )
    }

    // ---- 源码核对 ------------------------------------------------------------

    /**
     * 某个声明从签名那一行起到下一个同级声明为止（object 成员的缩进是 4 格）。
     *
     * 表达式体的函数（`fun f(...) = expr`）没有花括号可配平，而这条链路的接线正是表达式体，
     * 所以按行切：注释已被抹成空白，遇到下一位成员的起始行就收。
     */
    private fun declarationBlock(source: String, signature: String): String {
        val code = withoutComments(source)
        val at = code.indexOf(signature)
        check(at >= 0) { "找不到 $signature：函数改名或挪过家，这条守卫要跟着改" }
        val lines = code.substring(at).lines()
        val block = StringBuilder(lines.first())
        for (line in lines.drop(1)) {
            if (isSiblingDeclaration(line)) break
            block.append('\n').append(line)
        }
        return block.toString()
    }

    /** `local fun`、缩进更深的续行与调用参数的闭包都还在函数体里，不算同级声明 */
    private fun isSiblingDeclaration(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("local ") || trimmed.startsWith(")")) return false
        if (line.takeWhile { it == ' ' }.length > SIBLING_INDENT) return false
        return listOf("private ", "internal ", "public ", "override ", "suspend ")
            .fold(trimmed) { rest, modifier -> rest.removePrefix(modifier) }
            .let { it.startsWith("fun ") || it.startsWith("val ") || it.startsWith("companion ") }
    }

    /** 注释里提到函数名不算调用（KDoc 里正是拿它当话说的那种提及） */
    private fun withoutComments(source: String): String {
        val out = StringBuilder(source)
        var block = out.indexOf("/*")
        while (block >= 0) {
            val end = out.indexOf("*/", block + 2)
            if (end < 0) break
            out.replace(block, end + 2, " ")
            block = out.indexOf("/*")
        }
        val text = out.toString()
        return text.lines().joinToString("\n") { line ->
            val slash = line.indexOf("//")
            if (slash >= 0) line.substring(0, slash) else line
        }
    }

    private fun readMainSource(relative: String): String {
        val file = File(findMainJavaDir(), relative)
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

    private fun failingLabelOf(name: String): String = when (name) {
        "invalidate" -> "invalidateWidgetCache"
        "refresh" -> "refreshWidgets"
        "midnight" -> "scheduleWidgetMidnight"
        "cancelMidnight" -> "cancelWidgetMidnight"
        "preview" -> "scheduleTomorrowPreview"
        else -> "widgetFallbackWorker"
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

    /** 折行与缩进不算内容：注释已被抹成空格，这里再把连续空白压成一个 */
    private fun normalize(code: String): String = code.replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val BACKGROUND_SYNC_FILE = "com/buaa/schedule/widget/BackgroundSync.kt"
        const val FALLBACK_WORKER_FILE = "com/buaa/schedule/widget/WidgetFallbackWorker.kt"
        const val WIDGET_COMMON_FILE = "com/buaa/schedule/widget/WidgetCommon.kt"

        /** object 的成员缩进就是 4 格，比它深的一定还在某个函数体里 */
        const val SIBLING_INDENT = 4
    }
}
