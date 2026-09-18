package com.buaa.schedule.reminder

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 唤醒锁的超时口径：全仓库每一个 [WakeLocks.withPartialWakeLock] 调用点的**有效**超时都要 ≥10 秒。
 *
 * 判据出处：`docs/AUDIT-BATTERY-2026-09-18.md` §1 表 P2 第一行与 §2.5。同一份代码里
 * 「默认 5 秒不够，会被系统提前收回」这句判断已经被写下两次
 * （`BootReceiver.kt:39-41`、`WidgetRefreshReceiver.kt:42-44`，两处都显式传 10s），
 * 而最重的四处反而吃默认 5s：`ReminderReceiver` 那把（notify + KeyStore 解密 + 4 次查库 + 全量重排）、
 * `TomorrowPreviewReceiver` 那把（4 次查库 + 一次 121 天 × 全课程搜索 + 续排）、
 * `ClassProgressReceiver` 的两把（`class_progress` 一串 binder、`class_reschedule` 3 次查库 + 全量窗口搜索）。
 * 超时到点系统收回锁，剩下的重排就跑在随时睡回去的 CPU 上 —— 等于回到「加锁之前那个故障」，
 * 而且一条日志都不留（把这条静默降级变得可查的是取证日志，形状由 WakeLockForensicsTest 钉着，
 * 随下一枚提交落地）。
 *
 * 这条扫描的关键是**「不传参 = 吃默认值」必须被解析出来判分**：只看有没有显式 `timeoutMs =`
 * 会把四处默认值调用点全判成绿，而默认值恰恰是这次改动动的那个值。所以先从
 * `WakeLocks.kt` 的签名里把默认值读出来，再逐调用点算有效超时。
 * 反过来也成立：按**位置**传的第三个实参不算"没传"，命名与位置两种写法都要认；
 * 认不出的（传表达式、换了写法）一律进 unparsed 判红，不许静默放行 ——
 * 一条扫不出东西的守卫比没有守卫更糟，它会让人以为已经钉住了。
 *
 * 本模块单测没有 Robolectric（android.jar 里全是抛 "not mocked" 的桩），拿不到 Context 也
 * 跑不了 onReceive，所以按结构核对主源码；定位与解析的套路同
 * GlassSurfaceSingleChildTest / MigrationChainTest / ClassProgressReceiverMainThreadTest。
 * 找不到源码目录就直接抛，不用 assumeTrue 跳过 —— 找错路径只表现为"永远是绿的"，比红更糟。
 */
class WakeLockTimeoutFloorTest {

    /** 下限：与 BootReceiver / WidgetRefreshReceiver 显式传的那个值同口径 */
    private val floorMs = 10_000L

    /** 1：每个调用点的实际超时都罩得住里面那串重活 */
    @Test
    fun everyWakeLockCoversAtLeastTenSeconds() {
        val scan = scanCallSites()

        // 扫描本身失效（写法变了、路径不对）也要红，否则这条守卫是空的
        assertTrue("一个 withPartialWakeLock 调用点都没扫到，八成是路径或写法变了", scan.sites.isNotEmpty())
        assertTrue(
            "有调用点的实参解析不出来，这条扫描就成了瞎子：\n" + scan.unparsed.joinToString("\n"),
            scan.unparsed.isEmpty(),
        )
        assertEquals(
            "调用点数与解析出来的对不上：出现了一个没被算进分母的调用点（新写法？）",
            scan.mentioned, scan.sites.size,
        )

        val offenders = scan.sites.filter { it.timeoutMs < floorMs }
        assertTrue(
            "这些唤醒锁罩不住里面那串查库/重排：超时到点系统收回锁，剩下的活跑在随时睡回去的 CPU 上，" +
                "正是 §2.5 数的静默降级（BootReceiver.kt:40 那句「默认 5 秒会被提前收回」是同一判断）。" +
                "默认超时 ${scan.defaultMs}ms，下限 ${floorMs}ms：\n" +
                offenders.joinToString("\n") {
                    "${it.file}:${it.line} tag=${it.tag} 有效超时=${it.timeoutMs}ms（${if (it.explicit) "显式传参" else "吃默认值"}）"
                },
            offenders.isEmpty(),
        )
    }

    /**
     * 2：锁名就是审计 §4.3「按 tag 数持锁次数」的对账口径，一个都不许改名、不许重复。
     *
     * 改一个 tag 的代价不是编译错误而是下一轮 dumpsys 里那一格凭空消失（旧名字还有历史数据），
     * 改动前后的持锁次数就没法比了 —— 而次数 × 平均持锁时长正是判断这类改动省不省电的数。
     */
    @Test
    fun lockTagsAreTheAuditReconciliationKeys() {
        val tags = scanCallSites().sites.map { it.tag }

        val offenders = mutableListOf<String>()
        for (expected in LOCK_TAGS) {
            val n = tags.count { it == expected }
            if (n != 1) offenders += "$expected：扫到 $n 处（应为 1 处）"
        }
        for (extra in tags.toSet() - LOCK_TAGS.toSet()) offenders += "多出一个没登记过的 tag：$extra"
        assertEquals(
            "唤醒锁名与审计 §4.3 的对账基线不一致（改名等于把指标基线废掉）：\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * 3：对外形状不许变 —— 它必须是 inline、必须收一个**普通**的同步 `() -> T` block。
     *
     * 三个调用点在锁里直接调挂起函数（`BackgroundSync` 的注释把这条写成了契约：
     * 「inline 的同步 block、finally 当场 release —— 一 launch 出去 block 就返回、锁随之松开」）。
     * 把 block 收成 `crossinline`/`noinline`、或把函数改成非 inline，都会让那段活逃出锁外
     * （或直接编不过），而六个调用点看不出任何变化。
     */
    @Test
    fun theLockStaysAnInlineSynchronousBlock() {
        val code = blankCommentsAndLiterals(readMainSource(WAKE_LOCKS_FROM_JAVA))
        val normalized = normalize(code)
        val declared = normalized.indexOf(CALL)
        assertTrue(
            "withPartialWakeLock 的声明不见了或改了名：六个调用点用的就是这个名字，§4.3 也按它归因",
            declared >= 0,
        )
        // 函数体左括号之前的那一段 = 修饰符 + 形参表（折行与缩进已在 normalize 里压掉）
        val signature = normalized.substring(0, normalized.indexOf('{', declared))

        assertTrue(
            "withPartialWakeLock 不再 inline、或泛型返回值形状变了：block 就成了实参对象，" +
                "调用点在锁里直接调挂起函数这条路会断",
            signature.contains("inline fun <T> withPartialWakeLock("),
        )
        assertTrue(
            "block 必须还是类型为 () -> T 的形参（跨行折行不算改形状）",
            signature.contains("block: () -> T"),
        )
        assertTrue(
            "block 不许加 crossinline/noinline：那两条都会破坏「在调用方的挂起上下文里就地跑完」这件事",
            !signature.contains("crossinline") && !signature.contains("noinline"),
        )
        assertTrue(
            "block 之后必须还有 finally 归还锁（超时兜底之外还要有正常释放）",
            normalized.contains("finally {"),
        )
    }

    // ---- 源码扫描 ------------------------------------------------------------

    /**
     * 按**顶层逗号**切实参：嵌套括号里的逗号不是分隔符，尾随逗号（`timeoutMs = 10_000L,`）
     * 切出的空片段丢掉。切完每片就是一枚实参，命名/位置两条路都好认。
     */
    private fun topLevelArgs(args: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        for (ch in args) {
            if (ch == ',' && depth == 0) {
                current.toString().trim().let { if (it.isNotEmpty()) parts += it }
                current.setLength(0)
                continue
            }
            when (ch) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
            }
            current.append(ch)
        }
        current.toString().trim().let { if (it.isNotEmpty()) parts += it }
        return parts
    }

    private class Site(val file: String, val line: Int, val tag: String, val explicit: Boolean, val timeoutMs: Long)

    private class Scan(val mentioned: Int, val sites: List<Site>, val unparsed: List<String>, val defaultMs: Long)

    private class Source(val relative: String, val text: String)

    /** 逐调用点算出**有效**超时：显式传参（命名或按位置）用传的值，没传就是 WakeLocks 的默认值 */
    private fun scanCallSites(): Scan {
        val defaultMs = declaredDefaultTimeoutMs()
        val sites = mutableListOf<Site>()
        val unparsed = mutableListOf<String>()
        var mentioned = 0
        for (source in appSources()) {
            val code = blankCommentsAndLiterals(source.text)
            var from = 0
            while (true) {
                val at = code.indexOf(CALL, from)
                if (at < 0) break
                from = at + CALL.length
                val lineStart = code.lastIndexOf('\n', at) + 1
                if (code.substring(lineStart, at).contains("fun ")) continue // 声明自身，不是调用点
                mentioned++
                val line = lineOf(code, at)
                val where = "${source.relative}:$line"
                val open = at + CALL.length - 1
                val close = matchingClose(code, open)
                if (close == null) {
                    unparsed += "$where 的括号配不上对，解析器该修了"
                    continue
                }
                // 实参从原文切（只抹注释）：tag 是字符串字面量，抹掉注释与字面量的那份副本读不出名字
                val parts = topLevelArgs(withoutComments(source.text.substring(open + 1, close)))
                var tag: String? = null
                var given: String? = null // 调用点显式传的 timeoutMs；null 就是没传 → 吃默认值
                var positional = 0
                for (part in parts) {
                    val named = NAMED_ARG.matchEntire(part)
                    val name: String?
                    val value: String
                    if (named != null) {
                        name = named.groupValues[1]
                        value = named.groupValues[2].trim()
                    } else {
                        name = PARAM_ORDER.getOrNull(positional)
                        positional++
                        value = part
                    }
                    when (name) {
                        // 实参是原文，带着引号：§4.3 数的是 `buaa:schedule:<tag>` 里那个 tag
                        "tag" -> tag = value.trim('"')
                        "timeoutMs" -> given = value
                    }
                }
                if (tag == null) {
                    unparsed += "$where 解析不出 tag 实参（调用写法变了？）"
                    continue
                }
                val digits = given?.let { NUMERIC_LITERAL.matchEntire(it)?.groupValues?.get(1) }
                if (given != null && digits == null) {
                    unparsed += "$where 的 timeoutMs 实参「$given」不是数字字面量，判不了分"
                    continue
                }
                sites += Site(source.relative, line, tag, given != null, digits?.replace("_", "")?.toLong() ?: defaultMs)
            }
        }
        return Scan(mentioned, sites, unparsed, defaultMs)
    }

    /** 默认超时是从签名里读的，读不出来这条扫描就没有基准 —— 直接红，不当它不存在 */
    private fun declaredDefaultTimeoutMs(): Long {
        val code = blankCommentsAndLiterals(readMainSource(WAKE_LOCKS_FROM_JAVA))
        val given = Regex("timeoutMs\\s*:\\s*Long\\s*=\\s*([0-9][0-9_]*)\\s*L?").find(code)
        assertTrue(
            "WakeLocks 的 timeoutMs 不再有默认值：调用点不传参时有效超时无法判定，这条守卫就成了空的",
            given != null,
        )
        val value = given!!.groupValues[1].replace("_", "").toLongOrNull()
        assertTrue("默认超时 ${given.groupValues[1]} 解析不出数字", value != null)
        return value!!
    }

    private fun appSources(): List<Source> {
        val srcRoot = findMainJavaDir().parentFile.parentFile // <module>/src（或 app/src）
        val moduleRoot = srcRoot.parentFile // app —— 报告里的路径按它来算，人一眼认得出
        val files = srcRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${srcRoot.path} 下一个 .kt 都没有，路径不对", files.isNotEmpty())
        return files.map { Source(it.relativeTo(moduleRoot).path.replace('\\', '/'), it.readText()) }
    }

    private fun readMainSource(relativeFromJava: String): String {
        val file = File(findMainJavaDir(), relativeFromJava)
        assertTrue("找不到 ${file.path}：WakeLocks 挪过家的话这条守卫要跟着改路径", file.isFile)
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

    // ---- 词法小工具 ----------------------------------------------------------

    /** 抹注释、留字面量：实参里那些 `// 为什么传 10s` 的说明不能参与解析 */
    private fun withoutComments(source: String): String {
        val out = StringBuilder(source)
        var block = out.indexOf("/*")
        while (block >= 0) {
            val end = out.indexOf("*/", block + 2)
            if (end < 0) break
            out.replace(block, end + 2, " ")
            block = out.indexOf("/*")
        }
        return out.toString().lines().joinToString("\n") { line ->
            val slash = line.indexOf("//")
            if (slash >= 0) line.substring(0, slash) else line
        }
    }

    /**
     * 把注释与字符串/字符字面量的**内容**抹成等长空格，长度与换行位置一律不变。
     *
     * 不抹的话两处会算错：注释里也可能有括号（`BootReceiver` 的实参中间就写着两行说明），
     * 字面量里的 `${'$'}...` 带花括号，都会把"实参到哪儿结束"的判断带到别处去。
     * 抹成等长空格后，同一套下标既能切代码也能切原文。
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

    private fun lineOf(code: String, index: Int): Int =
        code.substring(0, index.coerceAtLeast(0)).count { it == '\n' } + 1

    /** 折行与缩进不算内容：注释与字面量已被抹成空格，这里再把连续空白压成一个 */
    private fun normalize(code: String): String = code.replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val CALL = "withPartialWakeLock("
        const val WAKE_LOCKS_FROM_JAVA = "com/buaa/schedule/reminder/WakeLocks.kt"

        /** 命名实参：`timeoutMs = 10_000L`（`(?!=)` 是为了不把 `==` 认成赋值） */
        val NAMED_ARG = Regex("(\\w+)\\s*=(?!=)\\s*(.*)", RegexOption.DOT_MATCHES_ALL)

        /** 位置实参的对照表，与 WakeLocks 的形参表一一对应 */
        val PARAM_ORDER = listOf("context", "tag", "timeoutMs", "block")

        /** 只认数字字面量（允许 `_` 分隔与尾随 `L`）：写了表达式的就是判不了分，宁可红 */
        val NUMERIC_LITERAL = Regex("([0-9][0-9_]*)\\s*L?")

        /** 审计 §4.3 对账用的六个锁名（`buaa:schedule:<tag>` 的 `<tag>`） */
        val LOCK_TAGS = listOf(
            "boot_rebuild",
            "widget_refresh",
            "reminder_show_and_reschedule",
            "class_progress",
            "class_reschedule",
            "tomorrow_preview",
        )
    }
}
