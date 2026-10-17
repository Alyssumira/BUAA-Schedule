package com.buaa.schedule.core.designsystem

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 玻璃运行时降档那枚 monitor 的**线程纪律与刻度**守卫（T416②，在册 #747）。
 *
 * ## 为什么要有这一枚（覆盖面读数）
 * 在册的 `MonotonicWindowGateWiringGuardTest` 名册只有 widget 那四枚文件，GlassJankMonitor 不在册；
 * `GlassJankMonitorWiringGuardTest` 与 `GlassJankDecisionTest` 钉的是挂接时机与判据表，
 * 零线程面、零时刻面 ⇒ 这一簇原本零尺。四格病灶（三格跨线程可见性/原子性、一格进程内时长用墙上钟）
 * 都是「编译绿、门禁绿、跑起来才默默不对」那一形，只有源码形状能当场拦住。
 *
 * ## 钉法（主尺＝形状，逐字节只用在能说话的地方）
 * ① 两枚文件（monitor 与 governance）抹注释后 `System.currentTimeMillis()` 归零；含注释那一栏恰好 1 处
 *    （取证原文留在 governance 的 KDoc 里）—— 那个 0 是「代码里真的没有」，不是「把证据删干净」。
 * ② 两侧同一把刻度：两枚文件都各读 `SystemClock.elapsedRealtime()`，且 monitor 把自己那枚 `now`
 *    **显式**递给 `lowerTierForJank(now)`；裸默认调用 0 处（只换一侧就是跨一秒读两只钟，#338 的同形）。
 * ③④ 可见性与原子性：三枚字段声明行各带 `@Volatile`；两枚计数器是 `AtomicLong`，
 *    自增形（`++`）与「`@Volatile private var` 去做读-改-写」那一形各归零，清零站点按枚数钉。
 * ⑤ 帧回调里零可阻塞锁（在册硬约束：回调里引锁本身就会造 jank）——按花括号配平切出回调体再数。
 * ⑥ 新内核照旧纯 JVM：零 android import、自己一枚钟都不读。
 *
 * 刀法与同包 `GlassJankMonitorWiringGuardTest` 一致：找不到源码目录直接抛，不用 assumeTrue 跳过。
 * 抹注释走 StringBuilder 方言（抄 `ui/settings/SettingsIclassReadOffCompositionGuardTest`），
 * 不新造字符数组那一族助手 —— 那会动在册的助手形状尺 `CommentStrippingHelperShapeGuardTest`。
 */
class GlassJankThreadDisciplineGuardTest {

    private companion object {
        const val MONITOR = "com/buaa/schedule/core/designsystem/GlassJankMonitor.kt"
        const val GOVERNANCE = "com/buaa/schedule/core/designsystem/GlassGovernance.kt"
        const val KERNEL = "com/buaa/schedule/domain/schedule/GlassMonotonicWindowGate.kt"

        const val WALL_CLOCK = "System.currentTimeMillis()"
        const val MONOTONIC_CLOCK = "SystemClock.elapsedRealtime()"

        // 现量（T416 落盘）：钉枚数，不钉行号
        const val MONITOR_READER_COUNT = 2
        const val GOVERNANCE_READER_COUNT = 1
        const val WALL_CLOCK_IN_EVIDENCE = 1
        const val TOTAL_RESET_COUNT = 3
    }

    /** ①：进程内时长不许用墙上钟；取证那一处只许活在注释里 */
    @Test
    fun inProcessDurationsNoLongerReadTheWallClock() {
        val monitorCode = withoutComments(source(MONITOR))
        val governanceCode = withoutComments(source(GOVERNANCE))
        assertEquals("$MONITOR 里用于进程内时长的墙上钟读数必须归零", 0, count(monitorCode, WALL_CLOCK))
        assertEquals("$GOVERNANCE 里用于进程内时长的墙上钟读数必须归零", 0, count(governanceCode, WALL_CLOCK))

        val evidence = count(source(MONITOR), WALL_CLOCK) + count(source(GOVERNANCE), WALL_CLOCK)
        assertEquals(
            "含注释的取证原文恰好 " + WALL_CLOCK_IN_EVIDENCE + " 处（改前两枚读钟点用的就是墙上钟，" +
                "这一处是留在 KDoc 里的病形；多一处说明又长回来了，少一处说明证据被抹干净了）",
            WALL_CLOCK_IN_EVIDENCE,
            evidence,
        )
    }

    /** ②：两枚文件同一把刻度，且 monitor 把自己那枚读数递给 governance —— 不许各读各的 */
    @Test
    fun bothSidesReadTheSameMonotonicRuler() {
        val monitor = withoutComments(source(MONITOR))
        val governance = withoutComments(source(GOVERNANCE))
        assertEquals("monitor 的采样窗与收尾时刻都读单调钟", MONITOR_READER_COUNT, count(monitor, MONOTONIC_CLOCK))
        assertEquals(
            "governance 的默认刻度同批换成单调钟（只换一侧就是两把刻度算同一个差值）",
            GOVERNANCE_READER_COUNT,
            count(governance, MONOTONIC_CLOCK),
        )
        assertEquals(
            "monitor 必须把后台线程那枚 now 显式递进 lowerTierForJank",
            1,
            count(monitor, "GlassGovernance.lowerTierForJank(now)"),
        )
        assertEquals("不许裸默认调用：那样两侧各读各的钟", 0, count(monitor, "lowerTierForJank()"))
        assertTrue(
            "governance 的倒退还得过在册护栏，不许在本地重算第二把冷却尺",
            governance.contains("glassLowerAtForCooldown("),
        )
    }

    /** ③：三枚跨线程字段各带 @Volatile（同块兄弟字段早已全带，这三枚原本是漏网） */
    @Test
    fun crossThreadFieldsAreVolatile() {
        val monitor = withoutComments(source(MONITOR))
        for (name in listOf("attachedActivity", "listener", "lastWindowBad")) {
            val line = monitor.lines().firstOrNull { it.trim().contains("private var $name:") || it.trim().endsWith("private var $name") }
                ?: monitor.lines().firstOrNull { it.contains("private var $name") }
            assertTrue("$name 的声明行找不到了：字段被搬走的话这条守卫要跟着改", line != null)
            assertTrue(
                "$name 又回到没有 @Volatile 的写法：它的读与写分属主线程与 FrameMetrics 后台线程，" +
                    "缺可见性时「连续两个坏窗口」的记忆与「摘哪一扇监听」的判据都不成对",
                line!!.trimStart().startsWith("@Volatile"),
            )
        }
    }

    /** ④：两枚计数器是原子量，自增形与 volatile-读-改-写形都不许复活 */
    @Test
    fun frameCountersAreAtomicNotVolatileReadModifyWrite() {
        val monitor = withoutComments(source(MONITOR))
        for (name in listOf("totalFrames", "jankFrames")) {
            assertEquals("$name 必须是原子量", 1, count(monitor, "private val $name = AtomicLong()"))
            assertEquals("$name 不许换回 volatile 标量（volatile 不给原子性）", 0, count(monitor, "private var $name"))
            assertEquals("$name 的裸自增形必须归零", 0, count(monitor, "$name++"))
            val resets = count(monitor, "$name.set(0L)")
            assertEquals(
                "$name 的清零站点必须还是三枚（attach 起窗、回调收尾、detachInternal 摘扇），少一枚就是清场漏了一处",
                TOTAL_RESET_COUNT,
                resets,
            )
        }
        assertEquals("窗口起点仍然只被赋值：它没有读-改-写，volatile 足够", 1, count(monitor, "private var windowStart"))
    }

    /** ⑤：帧回调体内零可阻塞锁、零阻塞调用（在册约束：回调里引锁本身就造 jank） */
    @Test
    fun frameCallbackBodyTakesNoBlockingLock() {
        val monitor = withoutComments(source(MONITOR))
        val head = "Window.OnFrameMetricsAvailableListener {"
        val headAt = monitor.indexOf(head)
        assertTrue("监听器字面量找不到了：靶子没了", headAt >= 0)
        val open = headAt + head.length - 1
        val close = matchingClose(monitor, open) ?: throw AssertionError("回调体的花括号配不上对，解析器该修了")
        val body = monitor.substring(open + 1, close)
        assertTrue("回调体切出来太短，解析没吃到东西", body.length > 500)
        for (offender in listOf("synchronized(", "runBlocking", "Thread.sleep", ".lock(", "Any()")) {
            assertEquals("帧回调里出现阻塞原语 $offender：每帧都可能被别针住，降档链自己就先造 jank", 0, count(body, offender))
        }
        // 注册路径上那枚 synchronized 属于「只在注册时走」，不许被顺手挪进回调
        assertTrue(
            "frameMetricsHandler 的双检锁留在注册路径上（回调体外）",
            monitor.indexOf("synchronized(this)").let { it >= 0 && it > close },
        )
    }

    /** ⑥：新内核照旧纯 JVM —— 零 android import，且自己一枚钟都不读 */
    @Test
    fun monotonicKernelStaysAndroidAndClockFree() {
        val raw = source(KERNEL)
        val offenders = raw.lines().map { it.trimStart() }
            .filter { it.startsWith("import android") || it.startsWith("import androidx") }
        assertTrue("纯判据内核混进设备依赖就没法在 JVM 里逐边界打表：\n${offenders.joinToString("\n")}", offenders.isEmpty())
        val kernel = withoutComments(raw)
        for (clock in listOf(WALL_CLOCK, "elapsedRealtime", "Date(", "Instant", "LocalDateTime", "System.nanoTime")) {
            assertEquals("内核自己去取钟了（$clock）：读数必须由调用点当参数递进来", 0, count(kernel, clock))
        }
        assertEquals("倒退档仍是唯一一枚专属值，且不与任何真实读数同格", 1, count(kernel, "Long.MAX_VALUE"))
    }

    // ---- 靶子定位与词法小工具（与同包既有守卫同一把刀） ----------------------------------

    private fun source(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 $relative：挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
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

    private fun count(haystack: String, needle: String): Int {
        var at = 0
        var seen = 0
        while (true) {
            val hit = haystack.indexOf(needle, at)
            if (hit < 0) return seen
            seen++
            at = hit + needle.length
        }
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

    /**
     * 只抹注释、保留字面量与换行位置（长度不变 ⇒ 行号仍可信）。走 StringBuilder 方言，
     * 不写「先把源串 toCharArray()、收尾再把字符数组折回字符串」那一族（收尾那枚写法被在册的助手形状尺
     * 逐字点名，逐字抄进注释会把它的裸原文栏顶高一枚，见 CommentStrippingHelperShapeGuardTest 的 ④）。
     */
    private fun withoutComments(src: String): String {
        val builder = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            when {
                src.startsWith("//", i) -> {
                    var j = i
                    while (j < src.length && src[j] != '\n') j++
                    repeat(j - i) { builder.append(' ') }
                    i = j
                }

                src.startsWith("/*", i) -> {
                    var depth = 1
                    var j = i + 2
                    while (j < src.length && depth > 0) {
                        when {
                            src.startsWith("/*", j) -> { depth++; j += 2 }
                            src.startsWith("*/", j) -> { depth--; j += 2 }
                            else -> j++
                        }
                    }
                    for (k in i until minOf(j, src.length)) builder.append(if (src[k] == '\n') '\n' else ' ')
                    i = j
                }

                src.startsWith("\"\"\"", i) -> {
                    val end = src.indexOf("\"\"\"", i + 3)
                    val stop = if (end < 0) src.length else end + 3
                    builder.append(src, i, stop)
                    i = stop
                }

                src[i] == '"' || src[i] == '\'' -> i = copyLiteral(src, i, builder)

                else -> {
                    builder.append(src[i])
                    i++
                }
            }
        }
        return builder.toString()
    }

    /** 原样抄回一枚字符串字面量（含转义），交回下一个待读位置 */
    private fun copyLiteral(src: String, start: Int, into: StringBuilder): Int {
        val quote = src[start]
        into.append(quote)
        var j = start + 1
        while (j < src.length) {
            val ch = src[j]
            when {
                ch == '\\' && j + 1 < src.length -> {
                    into.append(ch)
                    into.append(src[j + 1])
                    j += 2
                }

                ch == quote -> {
                    into.append(quote)
                    return j + 1
                }

                ch == '\n' -> return j
                else -> {
                    into.append(ch)
                    j++
                }
            }
        }
        return j
    }
}
