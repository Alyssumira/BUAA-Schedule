package com.buaa.schedule.reminder

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ai/T14 的生产接线（源码形状）：课堂铃兜底续排那一步的失败报告口**只**接在冷启动闸门那条链上。
 *
 * 为什么需要按形状核对（手法抄
 * [com.buaa.schedule.widget.ColdStartRebuildWiringTest]，本文件与它同一个理由）：
 * 报告口本身是 JVM 测得到的（`ClassProgressRescheduleFailureReportTest` 数的是"抛了几次、
 * 报的什么标签"），但"生产上到底有没有把闸门那个 `onStepFailed` 传下去"JVM 跑不到 ——
 * 本模块没有 Robolectric，`BackgroundSync.rescheduleRemindersAndBells` 要 Context。
 * 接错有两种形态，都不红：
 * ① 漏传（照旧 `rescheduleNextWindow(context)`）⇒ 那道失败出不了它自己的 `runCatching`，
 *    闸门把这一轮记成成功、写下指纹，最长 24 小时不再重跑（正是 ai/T13 登记的那条残余）；
 * ② 传了个常量（`NO_STEP_FAILURE`）⇒ 形状上像接了，实际永远无人留痕；
 * ③ 顺手给另外那几个没有报告口的调用点也接上 ⇒ 那几处（下课铃广播 / 前台服务 /
 *    12 小时兜底 Worker —— T78 之后前台服务是三处）
 *    本来就没有 `failures` 清单可写，接上去只会把同一道失败在日志之外再报一遍。
 *
 * 与 `WakeLockTimeoutFloorTest` / `ColdStartRebuildWiringTest` 同一口径：读 .kt、
 * 按"锚点之后第一个左括号"配平取整段（内部嵌套一起数）、**找不到就抛** ——
 * `assumeTrue` 式的跳过等于没有守卫。整份文件级扫描一律先抹注释**再**抹字符串字面量
 * （[blankCommentsAndLiterals]）：只抹注释的那份会被写在字符串字面量里的「斜杠紧跟星号」
 * 骗过，一口气吞掉后面的真代码。
 */
class ClassProgressRescheduleWiringTest {

    /** ① 闸门那条链：续排吃的必须是 `rescheduleRemindersAndBells` 自己那个 `onStepFailed` */
    @Test
    fun wrapperPassesItsOwnReportPortToTheBellReschedule() {
        val source = withoutComments(readMainSource(BACKGROUND_SYNC_FILE))
        val wrapper = normalize(balancedBlock(source, "suspend fun rescheduleRemindersAndBells("))

        assertTrue(
            "兜底续排没有带上闸门那个报告口（ai/T13 登记的那条残余会原地复活：这一轮仍被记成成功）：\n$wrapper",
            wrapper.contains("ClassProgressScheduler.rescheduleNextWindow(context, onStepFailed)"),
        )
        assertEquals(
            "rescheduleRemindersAndBells 里续排被走了不止一遍（多一遍等于把刚排上的上课铃撤了重排）：\n$wrapper",
            1,
            occurrences(wrapper, "rescheduleNextWindow("),
        )
        // 接了报告口 ≠ 接了个真口：常量与漏传在形状上都能骗过上一行
        assertTrue("传的是无操作常量，闸门仍然看不见：\n$wrapper", !wrapper.contains("rescheduleNextWindow(context, NO_STEP_FAILURE)"))
        assertTrue("漏传（回到改动前的裸调用）：\n$wrapper", !wrapper.contains("rescheduleNextWindow(context)"))
    }

    /**
     * ② 全仓库的调用点集合 = 已知那几处（T14 那轮五处，T78 之后六处），而且只有一处带报告口。
     *
     * 没有报告口的那五处（`WidgetFallbackWorker` 1 处、`CourseFluidService` 3 处、`ClassProgressReceiver` 1 处）
     * 吃默认值：它们没有 `failures` 清单可写，接上去也无处落。哪天多出一个冷启动侧的驱动者，
     * 这条守卫当场红（同一件事第二份实现的形状，审计 §2.2 数过）。
     *
     * 2026-09-23 T78（台账 #119）真的把第六处接了进来，而且是**按这张守卫的意思接的**：
     * 实况前台服务起不来时，唯一的出路是把课堂窗口重排一遍、让下一发从 `setAlarmClock` 的
     * 闹钟豁免档进来（装机实测 5.203 / 5.310 / 5.435 秒三发起死回生），而重排只能走
     * `ClassProgressScheduler.rescheduleNextWindow` 这一条既有编排 —— 所以这里从五处变六处，
     * 新增那一处落在 `CourseFluidService`（第三枚实参 `app`：降级报告手可能站在广播的临时
     * Context 上，用它自己算好的那枚应用级 Context，实参见 `reportLiveDegrade`）。
     * 它吃的仍是默认报告口（没有 `failures` 清单可写），所以 ②③ 两条判据一个字没松。
     */
    @Test
    fun bellRescheduleCallSitesAreTheKnownSixRepoWide() {
        val callSites = callSitesWithArguments()

        assertEquals(
            "续排的调用点集合变了（本卡只许动 BackgroundSync 那一处；T78 那处按上面的说明入账）：\n${callSites.keys.sorted()}",
            listOf(
                "$MAIN_PREFIX/com/buaa/schedule/reminder/ClassProgressReceiver.kt",
                "$MAIN_PREFIX/com/buaa/schedule/reminder/CourseFluidService.kt",
                "$MAIN_PREFIX/com/buaa/schedule/widget/BackgroundSync.kt",
                "$MAIN_PREFIX/com/buaa/schedule/widget/WidgetFallbackWorker.kt",
            ).sorted(),
            callSites.keys.sorted(),
        )
        assertEquals(
            "续排的调用点总数不再是 6 处（BackgroundSync 1 + WidgetFallbackWorker 1 + " +
                "CourseFluidService 3 + ClassProgressReceiver 1）：${callSites.values}",
            6,
            callSites.values.sumOf { it.size },
        )
        // 第六处只许出现在 T78 那条降级路上：CourseFluidService 从 2 枚变 3 枚就是它，多一枚是有人在别处又排了一遍
        assertEquals(
            "CourseFluidService 里的续排调用点应当恰好三枚（finishLiveAndReschedule 两枚 + " +
                "reportLiveDegrade 一枚）：${callSites.values.flatten().count { it.isNotEmpty() }}",
            3,
            callSites["$MAIN_PREFIX/com/buaa/schedule/reminder/CourseFluidService.kt"]?.size,
        )
        val reported = callSites.filterValues { args -> args.count { it.contains(',') } > 0 }
        assertEquals(
            "带报告口的调用点不是恰好一处：${reported.keys}",
            listOf("$MAIN_PREFIX/com/buaa/schedule/widget/BackgroundSync.kt"),
            reported.keys.sorted(),
        )
    }

    /** ③ 那五处照旧吃默认值：一个字都不许多 */
    @Test
    fun otherCallSitesStillTakeTheNoOpDefault() {
        val expected = mapOf(
            "$MAIN_PREFIX/com/buaa/schedule/reminder/ClassProgressReceiver.kt" to listOf("context"),
            "$MAIN_PREFIX/com/buaa/schedule/reminder/CourseFluidService.kt" to
                listOf("app", "applicationContext", "applicationContext"),
            "$MAIN_PREFIX/com/buaa/schedule/widget/WidgetFallbackWorker.kt" to listOf("applicationContext"),
        )
        val actual = callSitesWithArguments().filterKeys { it in expected.keys }.mapValues { it.value.sorted() }
        assertEquals(
            "没有报告口的那五处被顺手接上了别的东西（它们各自在广播/服务链路里，多报一遍没有清单可落）：\n$actual",
            expected.mapValues { it.value.sorted() },
            actual,
        )
    }

    /** ④ 那句 `Log.w` 一个字没改，而且仍然排在报告口之前；默认值仍是顶层的无操作属性 */
    @Test
    fun forensicLogStatementSurvivesAndStillComesFirst() {
        val source = withoutComments(readMainSource(SCHEDULER_FILE))
        assertTrue(
            "取证日志的 tag 或文案被改动了（审计 §4.4 的老过滤条件会直接失效）",
            source.contains("Log.w(\"ClassProgressScheduler\", \"下课后续排课堂窗口失败\", it)"),
        )
        assertEquals(
            "取证日志在调度器里出现了不止一份（同一道失败被打两遍，§4.4 的计数就废了）",
            1,
            occurrences(readMainSource(SCHEDULER_FILE), "下课后续排课堂窗口失败"),
        )
        assertTrue(
            "报告口的默认值被写进了签名里（形状守卫会切到默认值上去）：\n$source",
            source.contains("onStepFailed: (label: String, error: Throwable) -> Unit = NO_RESCHEDULE_STEP_FAILURE"),
        )
        assertEquals(
            "无操作默认值必须是**一份**顶层属性；出现两遍就是有人在签名里又写了一份 lambda",
            1,
            occurrences(source, "private val NO_RESCHEDULE_STEP_FAILURE: (label: String, error: Throwable) -> Unit"),
        )

        val seam = normalize(balancedBlock(source, "internal suspend fun rescheduleNextWindow("))
        val log = seam.indexOf("logFailure(it)")
        val report = seam.indexOf("onStepFailed(\"rescheduleNextWindow\", it)")
        assertTrue("编排本体里找不到那句日志钩子：\n$seam", log >= 0)
        assertTrue("编排本体不再向报告口报失败（本卡的全部目的）：\n$seam", report >= 0)
        assertTrue("日志与报告的次序反了（审计那边先按日志过滤）：$log vs $report", log < report)
        assertTrue("续排的标签不是 rescheduleNextWindow（与 rescheduleReminders 那一族对不上）：\n$seam", report >= 0)
        assertTrue("runCatching 被改成向外抛了（五个没有报告口的调用点会崩在广播里）：\n$seam", !seam.contains("throw "))
    }

    /**
     * ⑤ 三条「不是失败」的早退：生产接线把它们一律走成 null / return，绝不碰报告口。
     *
     * 记成失败的代价不是多一行日志，而是闸门那一轮不写指纹 ⇒ 每一次冷启动都无条件重跑，
     * ai/T13 那张卡的省电量级整个被抹掉。
     */
    @Test
    fun productionEarlyReturnsGoToTheSeamNotToTheReportPort() {
        val source = withoutComments(readMainSource(SCHEDULER_FILE))
        val load = normalize(balancedBlock(source, "loadWindowInput = load@{"))
        assertEquals(
            "学期与学期起始日期那两条早退少了/多了（null = 编排本体的「没有可排的窗口」那条 return）：\n$load",
            2,
            occurrences(load, "?: return@load null"),
        )
        assertTrue("早退那两条开始自己报失败（那是「没有内容」不是「排失败」）：\n$load", !load.contains("onStepFailed"))
        assertTrue("早退那两条开始打取证日志：\n$load", !load.contains("logFailure"))
        assertTrue("查库的两道判据本体没了：\n$load", load.contains("getCurrentSemester()") && load.contains("startLocalDate"))

        val seam = normalize(balancedBlock(source, "internal suspend fun rescheduleNextWindow("))
        val switches = seam.indexOf("if (!classProgress && !dndEnabled) return")
        val missingInput = seam.indexOf("val input = loadWindowInput() ?: return")
        val onFailure = seam.indexOf(".onFailure")
        assertTrue("两个开关都关那条早退不在了（会把两个开关都关的人拉去查三张表）：\n$seam", switches >= 0)
        assertTrue("输入为 null 那条早退改成了往下走：\n$seam", missingInput >= 0)
        assertTrue("两条早退跑到了 onFailure 之后（那里已经开始报失败）：$onFailure", missingInput < onFailure)
        assertTrue("两条早退跑到了 onFailure 之后：$onFailure", switches < onFailure)
    }

    // ---- 源码核对工具（抄 ColdStartRebuildWiringTest）------------------------

    /** 全主源码里 `ClassProgressScheduler.rescheduleNextWindow(` 的调用点：文件 → 实参文本（按出现次序） */
    private fun callSitesWithArguments(): Map<String, List<String>> {
        val sites = linkedMapOf<String, MutableList<String>>()
        for (source in mainSources()) {
            for (at in occurrencesOf(source.text, CALL)) {
                val lineStart = source.text.lastIndexOf('\n', at) + 1
                if (source.text.substring(lineStart, at).contains("fun ")) continue // 声明自身
                sites.getOrPut(source.relative) { mutableListOf() } += argumentsAfter(source.text, at)
            }
        }
        return sites
    }

    /** [needle] 处那对圆括号之间的实参文本（配平，嵌套括号一起收） */
    private fun argumentsAfter(source: String, needleEnd: Int): String {
        val open = source.indexOf('(', needleEnd)
        check(open >= needleEnd) { "$CALL 之后找不到左括号" }
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, index).trim()
                }
            }
        }
        throw IllegalStateException("$CALL 的圆括号没配平")
    }

    private class Source(val relative: String, val text: String)

    private fun readMainSource(relativeFromJava: String): String {
        val file = File(findMainJavaDir(), relativeFromJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /** 只扫主源码：测试目录里那些注入的假实现不该算进调用点集合 */
    private fun mainSources(): List<Source> {
        val javaDir = findMainJavaDir()
        val files = javaDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${javaDir.path} 下一个 .kt 都没有，路径不对", files.isNotEmpty())
        return files.map { Source("$MAIN_PREFIX/${it.relativeTo(javaDir).path.replace('\\', '/')}", blankCommentsAndLiterals(it.readText())) }
    }

    /**
     * 从 [signature] 之后第一个 `{` 起配平到对应的右括号（含），返回**整段**（内部嵌套分支一起数）。
     * 找不到锚点就抛 —— 锚点失效必须显式失败，静默跳过等于没有守卫。
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

    /** 抹注释、留字面量：切单个函数体用这份（字面量里的中文还要参与断言） */
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

    /**
     * 注释与字符串字面量**都**抹成空白、长度与换行位置不变：整份文件的计数扫描用这份。
     *
     * 只抹注释的那份在 `ui/settings/SettingsScreen.kt` 上会出事：那里有一个作为字符串字面量
     * 出现的「斜杠紧跟星号」，会被当成块注释开头一口气吞掉后面的真代码。
     */
    private fun blankCommentsAndLiterals(src: String): String {
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
                    val end = src.indexOf("\"\"\"", i + 3).let { if (it < 0) out.size else it + 3 }
                    for (k in (i + 3) until (end - 3).coerceAtLeast(i + 3)) if (out[k] != '\n') out[k] = ' '
                    i = end
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
                    for (k in (i + 1) until (j - 1).coerceAtLeast(i + 1)) out[k] = ' '
                    i = j
                }

                else -> i++
            }
        }
        return String(out)
    }

    private fun occurrencesOf(haystack: String, needle: String): List<Int> {
        val hits = mutableListOf<Int>()
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return hits
            hits += at
            from = at + needle.length
        }
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

    private fun occurrences(haystack: String, needle: String): Int = occurrencesOf(haystack, needle).size

    private fun normalize(code: String): String = code.replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val BACKGROUND_SYNC_FILE = "com/buaa/schedule/widget/BackgroundSync.kt"
        const val SCHEDULER_FILE = "com/buaa/schedule/reminder/ClassProgressScheduler.kt"

        /** 给人看的路径前缀：报告里的文件是人一眼认得出的主源码路径 */
        const val MAIN_PREFIX = "app/src/main/java"

        /** 带限定名的调用形状：seam 自己那份重载与类内声明都不含这个前缀，不会混进调用点集合 */
        const val CALL = "ClassProgressScheduler.rescheduleNextWindow("
    }
}
