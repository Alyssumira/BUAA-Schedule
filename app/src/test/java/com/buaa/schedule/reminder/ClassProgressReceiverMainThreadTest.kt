package com.buaa.schedule.reminder

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上/下课铃的主体不许同步跑在广播主线程上（审计 `docs/AUDIT-BATTERY-2026-09-18.md` §2.6）。
 *
 * 判据抄自同一个仓库里已经写下结论的那一处（`ReminderReceiver.kt:34-37`）：ACTION_START 一条
 * 分支要连发 5 次以上 binder —— AlarmManager 排程、ensureChannels + notify、
 * startForegroundService、setInterruptionFilter、prefs 落盘。闹钟叫醒的往往是冷进程，
 * 这些全落在 `onReceive` 的主线程上就是在吃 10 秒配额，超了系统直接掐广播：
 * 用户看到的"这一节课没有提醒、勿扰也没恢复"就是这么来的。
 * 修法只有一条 —— `goAsync()` 之后把主体交给协程，主线程只留"领 PendingResult + 起协程"。
 *
 * 这里锁的是四件事，各自一条断言（混在一条里，红了就分不清是哪件事退化）：
 * 1. 每个 binder 入口的**每一个**调用点都在 `launch { }` 里面；
 * 2. 主线程那一段除了 goAsync 与起协程之外一个调用都不许有；
 * 3. R5 F-31 的顺序：`scheduleEnd` 仍是 ACTION_START 里第一个 binder 动作，
 *    且它没有靠"另起一条协程"来维持（那条路会让它与 startLiveWindow/enter 并发）；
 * 4. `pendingResult.finish()` 恰好一处，且落在 `finally` 里（异常/取消路径也归还）。
 *
 * 本模块单测没有 Robolectric（android.jar 里全是抛 "not mocked" 的桩），真跑一次 onReceive
 * 要设备，所以按结构核对主源码。定位源码的办法与 MigrationChainTest /
 * GlassSurfaceSingleChildTest 一致；找不到目录就直接抛，不用 assumeTrue 跳过 ——
 * 找错路径只表现为"永远是绿的"，比红更糟。
 */
class ClassProgressReceiverMainThreadTest {

    /** 1：三条分支的 binder 入口全部在协程里，主线程一个都不留 */
    @Test
    fun everyBellSideEffectRunsInsideTheCoroutine() {
        val body = onReceiveBody()
        val async = coroutineRange(body)
        assertTrue("onReceive 里没有 launch { }：三条分支的主体还在广播主线程上", !async.isEmpty())

        val offenders = mutableListOf<String>()
        for (call in BELL_SIDE_EFFECTS) {
            val hits = indicesOf(body, call)
            // 扫描本身失效（入口被删、被改名）也要红，否则这条守卫是空的
            assertTrue("$call 在 onReceive 里一次都没出现：分支被删了还是改了名？", hits.isNotEmpty())
            offenders += hits.filterNot { it in async }.map { "$call（onReceive 内第 ${lineOf(body, it)} 行）" }
        }
        assertTrue(
            "这些调用还在广播主线程上同步跑 —— 配额一紧张整条广播被掐，这一节课的提醒与勿扰恢复就没了：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** 2：主线程只剩"领 PendingResult + 起协程"两件事 */
    @Test
    fun onlyGoAsyncAndTheCoroutineStartStayOnTheMainThread() {
        val body = onReceiveBody()
        val at = body.indexOf(LAUNCH)
        assertTrue("onReceive 不再走 launch { }", at >= 0)
        val mainThread = normalize(body.substring(0, at))

        assertTrue("goAsync() 必须在起协程之前：否则 onReceive 一返回广播就被判定结束", mainThread.contains("goAsync()"))
        assertEquals(
            "主线程那一段现在只剩两件事，多一个调用就是回到吃 10 秒配额的老路：\n$mainThread",
            "val pendingResult = goAsync() CoroutineScope(Dispatchers.IO)",
            mainThread,
        )
    }

    /** 3：R5 F-31 —— 下课铃先排，它是"手机已静音"唯一的解除路径 */
    @Test
    fun endBellIsScheduledBeforeTheStepsThatDependOnIt() {
        val body = onReceiveBody()
        val branch = actionBranch(body, "ACTION_START")
        val scheduleEnd = branch.indexOf("ClassProgressScheduler.scheduleEnd(")
        assertTrue("ACTION_START 不再排下课铃：手机静音之后就没有解除的人了", scheduleEnd >= 0)
        assertTrue(
            "startLiveWindow 不许挪到 scheduleEnd 之前（R5 F-31：中间任何一步抛异常都会留下静音且无下课铃）",
            branch.indexOf("ReminderNotifications.startLiveWindow(") > scheduleEnd,
        )
        assertTrue(
            "ClassProgressDnd.enter 不许挪到 scheduleEnd 之前（同上：enter 才是真的把整机静音的那一步）",
            branch.indexOf("ClassProgressDnd.enter(") > scheduleEnd,
        )
        val head = branch.substring(0, scheduleEnd)
        assertTrue(
            "scheduleEnd 之前只许有取窗口/读开关这类本地准备，出现别的 binder 动作就是顺序破了：\n" +
                normalize(head),
            BELL_SIDE_EFFECTS
                .filter { it != "ClassProgressScheduler.scheduleEnd(" && head.contains(it) }
                .isEmpty(),
        )
        assertEquals(
            "三条分支要共用 onReceive 那一条协程：scheduleEnd 被挪进另起的 launch 就是把它并发了",
            1,
            occurrences(body, LAUNCH),
        )
    }

    /** 4：PendingResult 恰好归还一次，且在任何路径上都归还 */
    @Test
    fun pendingResultIsFinishedExactlyOnceOnEveryPath() {
        val code = receiverCode()

        assertEquals(
            "goAsync() 全文件只能有一处：一条广播只有一个 PendingResult，" +
                "第二次调用返回的是 null，finish() 当场 NPE、那一步根本不会跑",
            1,
            occurrences(code, "goAsync()"),
        )
        assertEquals(
            "finish() 只能有一处：两处是重复归还，零处是广播生命周期泄漏",
            1,
            occurrences(code, "pendingResult.finish()"),
        )
        val at = code.indexOf("finally {")
        assertTrue("没有 finally：主体抛异常或协程被取消时广播永不归还（系统日志持续报 timed out）", at >= 0)
        assertTrue(
            "finish() 必须是 finally 的第一句，否则前面一句抛异常就轮不到它",
            normalize(code.substring(at + "finally {".length)).startsWith("pendingResult.finish()"),
        )
    }

    /**
     * 契约：action 串只认 [ClassProgressReceiver] 里的那一份。
     *
     * 2026-09-17 那次事故就是发端与收端各写了一份 action 字符串常量 —— 改了一边，
     * 另一边的闹钟照样发得出去，收端 `when` 落空，上/下课铃静默失效。
     * 值本身也要钉住：升级前就排好的闹钟带的是旧串，改了值等于把用户已排的铃全部作废。
     */
    @Test
    fun actionStringsAreDeclaredInExactlyOnePlace() {
        val text = receiverFile().readText()
        for (declaration in listOf(
            "const val EXTRA_ACTION = \"extra_action\"",
            "const val ACTION_START = \"class_start\"",
            "const val ACTION_END = \"class_end\"",
            "const val ACTION_DND_WATCHDOG = \"class_dnd_watchdog\"",
        )) {
            assertTrue("常量声明变了，发端排好的闹钟会对不上号：$declaration", text.contains(declaration))
        }

        val offenders = mainSources()
            .filter { it.relative != RECEIVER_FROM_JAVA }
            .filter { source -> ACTION_LITERALS.any { source.text.contains(it) } }
            .map { source ->
                "${source.relative}: " + ACTION_LITERALS.filter { source.text.contains(it) }.joinToString()
            }
        assertTrue("action 串在别处又被写了一份（发端/收端各一份就是那次事故）：\n" + offenders.joinToString("\n"),
            offenders.isEmpty())

        // 发端仍然引用收端 companion 里的常量，而不是自己拼
        val scheduler = normalize(readMainSource("com/buaa/schedule/reminder/ClassProgressScheduler.kt"))
        assertTrue("排程侧不再引用 ClassProgressReceiver.ACTION_START：它开始自己拼串了",
            scheduler.contains("ClassProgressReceiver.ACTION_START"))
        assertTrue("排程侧不再引用 ClassProgressReceiver.ACTION_END：它开始自己拼串了",
            scheduler.contains("ClassProgressReceiver.ACTION_END"))
    }

    /** 契约：对外形状不许变（可见性与签名），改法只许发生在方法体里 */
    @Test
    fun receiverSurfaceKeepsItsVisibilityAndSignature() {
        val code = receiverCode()

        val windowOf = funModifiers(code, "fun windowOf(intent: Intent)")
        assertTrue("windowOf(intent) 不再是公开伴生函数（实况链/组件三条渲染链都在用它）",
            windowOf.none { it == "private" || it == "internal" || it == "protected" })
        // 锚点只到左括号：ai/T14 给同名函数加了默认参数（另一模块那份），写死整条签名的锚点
        // 会找不到函数；这里钉的是**可见性**，语义与锚点宽度无关（先例：scheduleWidgetMidnight）
        val reschedule = funModifiers(code, "fun rescheduleNextWindow(")
        assertTrue("rescheduleNextWindow(context) 的可见性被放宽了：它只是内部实现",
            reschedule.contains("private"))
    }

    // ---- 源码定位与解析 --------------------------------------------------------

    private class Source(val relative: String, val text: String)

    /** onReceive 的花括号内部（不含外层 `{}`） */
    private fun onReceiveBody(): String {
        val code = receiverCode()
        val at = code.indexOf("override fun onReceive(")
        assertTrue("${RECEIVER_RELATIVE} 里找不到 onReceive：靶子没了", at >= 0)
        val open = code.indexOf('{', code.indexOf(')', at))
        val close = matchingClose(code, open)
        check(close != null) { "$RECEIVER_RELATIVE: onReceive 的花括号配不上对，解析器该修了" }
        return code.substring(open + 1, close)
    }

    /** 协程体的区间（相对 [body]）；没有 `launch {` 时返回空区间，由调用方判红 */
    private fun coroutineRange(body: String): IntRange {
        val at = body.indexOf(LAUNCH)
        if (at < 0) return IntRange.EMPTY
        val open = body.indexOf('{', at + LAUNCH.length - 1)
        val close = matchingClose(body, open)
        check(close != null) { "$RECEIVER_RELATIVE: launch 的花括号配不上对，解析器该修了" }
        return (open + 1) until close
    }

    /** `ACTION_X -> { ... }` 那条分支的花括号内部 */
    private fun actionBranch(body: String, action: String): String {
        val at = body.indexOf("$action ->")
        assertTrue("onReceive 里少了 $action 分支：这条守卫就是空的", at >= 0)
        val open = body.indexOf('{', at)
        val close = matchingClose(body, open)
        check(close != null) { "$RECEIVER_RELATIVE: $action 分支的花括号配不上对" }
        return body.substring(open + 1, close)
    }

    /** 某个函数声明前面的修饰符（`private suspend` 这种就是 ["private", "suspend"]） */
    private fun funModifiers(code: String, declaration: String): List<String> {
        val at = code.indexOf(declaration)
        assertTrue("$declaration 没了：方法被改名或被删，对外形状就变了", at >= 0)
        val head = code.substring(0, at).lines().last()
        return head.trim().split(" ").filter { it.isNotEmpty() && it != "fun" }
    }

    private fun receiverCode(): String = blankCommentsAndLiterals(receiverFile().readText())

    private fun receiverFile(): File {
        val file = File(findMainJavaDir(), RECEIVER_FROM_JAVA)
        assertTrue("找不到 ${file.path}：ClassProgressReceiver 挪过家的话这条守卫要跟着改路径", file.isFile)
        return file
    }

    private fun readMainSource(relativeFromJava: String): String {
        val file = File(findMainJavaDir(), relativeFromJava)
        assertTrue("找不到 ${file.path}：这条守卫就是空的", file.isFile)
        return file.readText()
    }

    private fun mainSources(): List<Source> {
        val root = findMainJavaDir()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${root.path} 下一个 .kt 都没有，路径不对", files.isNotEmpty())
        return files.map { Source(it.relativeTo(root).path.replace('\\', '/'), it.readText()) }
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

    /** 注释与字面量抹成等长空格，长度和换行位置不变：下标既能切代码也能切原文 */
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

    private fun indicesOf(haystack: String, needle: String): List<Int> {
        val hits = mutableListOf<Int>()
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return hits
            hits += at
            from = at + needle.length
        }
    }

    private fun occurrences(haystack: String, needle: String): Int = indicesOf(haystack, needle).size

    private fun lineOf(code: String, index: Int): Int =
        code.substring(0, index.coerceAtLeast(0)).count { it == '\n' } + 1

    /** 折行与缩进不算内容：注释已被抹成空格，这里再把连续空白压成一个 */
    private fun normalize(code: String): String = code.replace(Regex("\\s+"), " ").trim()

    private companion object {
        /** 给人看的仓库根路径；定位文件用的是下面那个 java 根口径 */
        const val RECEIVER_RELATIVE = "app/src/main/java/com/buaa/schedule/reminder/ClassProgressReceiver.kt"
        const val RECEIVER_FROM_JAVA = "com/buaa/schedule/reminder/ClassProgressReceiver.kt"
        const val LAUNCH = ".launch {"

        /** onReceive 里那些"跑在主线程就是吃配额"的入口，逐条点名 */
        val BELL_SIDE_EFFECTS = listOf(
            // ACTION_START：AlarmManager / NotificationManager + startForegroundService /
            // 勿扰 binder + prefs 落盘 / 组件刷新 / 撤销课前提醒
            "ClassProgressScheduler.scheduleEnd(",
            "ReminderNotifications.startLiveWindow(",
            "ClassProgressDnd.enter(",
            "WidgetCommon.requestLiveRefresh(",
            "ReminderNotifications.cancelCourseReminder(",
            // ACTION_END：停前台服务 / 撤常驻通知 / 恢复勿扰 / 查库续排
            "CourseFluidService.stop(",
            "ReminderNotifications.cancelClassOngoing(",
            "ClassProgressDnd.restore(",
            "rescheduleNextWindow(context)",
            // 看门狗：到期强制恢复勿扰
            "ClassProgressDnd.selfCheck(",
        )

        val ACTION_LITERALS = listOf("\"class_start\"", "\"class_end\"", "\"class_dnd_watchdog\"", "\"extra_action\"")
    }
}
