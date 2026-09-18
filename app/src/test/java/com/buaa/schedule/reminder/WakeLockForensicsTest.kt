package com.buaa.schedule.reminder

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `WakeLocks` 那条取证日志的形状（`docs/AUDIT-BATTERY-2026-09-18.md` §2.5 末句要求的就这一条：
 * 「本轮不建议动超时到点后 block 不中断的语义，只建议对齐超时取值 + 加一条跑完用时日志，
 * 为下一轮的判断提供数据」）。
 *
 * 这条日志的价值全在形状上，所以逐条钉：
 * 1. 耗时用 `SystemClock.elapsedRealtime()` 掐在 block 两头 —— `currentTimeMillis()` 是墙上时钟，
 *    用户改时间/时区跳变（「改时间」正是这些锁的触发场景之一）会把耗时算成负数或几十亿；
 * 2. `isHeld` 在 **block 返回之后、release 之前**读，且全文件只读那一次 —— 本函数在此之前从不
 *    release，所以此刻 `isHeld == false` 只有一个解释（超时到点，锁已被系统收回）。这是精确信号，
 *    读早了（block 之前）它恒为 true，那条 W 永远不会响，守卫就成了装饰；
 * 3. 写日志的 helper 不许 inline：inline 的函数体逐调用点展开，六个调用点各烘一份消息常量是白付
 *    dex 体积（release 包的字节数是有账的，见 `docs/RELEASE.md`「包体与 ABI」）；它也不能变成
 *    public（只是内部实现），且要真的被调用；
 * 4. W 的触发条件必须是那位布尔量，不是「耗时 ≥ 上限」的比较 —— 后者在「超时刚触发、block 恰好
 *    返回」那格竞态里会说谎，而谎报的代价是下一轮据此把超时又调小（§2.5 数过的正是这个方向）。
 *
 * 只核对 `WakeLocks.kt` 一个文件：`ReminderReceiver.kt:24` 那类调用点用 `currentTimeMillis()`
 * 是正当的（量的是"离上课还有多久"，本来就该跟着墙上时钟），别处扫这一条会自己把自己判红。
 *
 * 本模块单测没有 Robolectric（android.jar 里全是抛 "not mocked" 的桩），既拿不到 Context 也
 * 造不出真实的 PowerManager，所以按结构核对主源码；定位源码的套路同
 * GlassSurfaceSingleChildTest / MigrationChainTest / ClassProgressReceiverMainThreadTest，
 * 找不到源码目录就直接抛，不用 assumeTrue 跳过 —— 找错路径只表现为"永远是绿的"，比红更糟。
 */
class WakeLockForensicsTest {

    /** 1：耗时量的是 block 的实际用时，读的是单调时钟 */
    @Test
    fun holdDurationIsMeasuredOnTheMonotonicClock() {
        val code = code()
        val hits = indicesOf(code, CLOCK)
        assertEquals(
            "掐表要两头各读一次 $CLOCK：只在末尾读一次算不出耗时，只在开头读一次恒为 0",
            2, hits.size,
        )
        val block = blockCall(code)
        assertTrue("起点必须在 block 之前：block 之后才读就量不到它跑了多久", hits[0] < block)
        assertTrue("终点必须在 block 之后（finally 里）：否则量到的不是 block 的实际耗时", hits[1] > block)
        assertTrue(
            "耗时不许读墙上时钟：用户改系统时间/时区跳变会把 elapsed 变成负数或几十亿，" +
                "这条日志就成了噪声而不是证据（WidgetRefreshReceiver.kt:100 为同一件事站过台）",
            !code.contains("currentTimeMillis"),
        )
    }

    /** 2：`isHeld` 采样的位置就是证据所在 —— 晚于 block、早于 release，且只采一次 */
    @Test
    fun heldFlagIsSampledAfterTheBlockAndBeforeTheRelease() {
        val code = code()
        val samples = indicesOf(code, "isHeld")
        assertEquals(
            "isHeld 只许采样一次：日志读一遍、release 再读一遍，两次中间锁可能被收回，" +
                "日志说的与代码做的就不是同一件事了",
            1, samples.size,
        )
        val block = blockCall(code)
        assertTrue(
            "isHeld 必须在 block 返回之后才读：block 之前它刚 acquire 完必然为 true，" +
                "那条 W 就永远不会响，取证成了装饰",
            samples[0] > block,
        )
        assertTrue(
            "isHeld 必须早于 release：先 release 再读，读到的永远是 false，正常跑完也会被记成没跑赢",
            samples[0] < code.indexOf("release()"),
        )
        val sample = Regex("(\\w+)\\s*=\\s*lock\\.$HELD").find(code)?.groupValues?.get(1)
        assertTrue("那次采样要存在局部量里（日志与 release 共用同一个值）", sample != null)
        val guard = Regex("if\\s*\\(\\s*(\\w+)\\s*\\)\\s*lock\\.release\\(\\)").find(code)?.groupValues?.get(1)
        assertEquals("release 的判据必须就是 block 之后那一次采样本身", sample, guard)
    }

    /** 3：取证写在一份非 inline 的 helper 里，并且真的接在 block 之后被调用一次 */
    @Test
    fun forensicLogLivesInOneNonInlinedHelper() {
        val code = code()
        assertEquals(
            "WakeLocks 里只许有一处 inline fun（withPartialWakeLock 本身）：日志 helper 一旦被 inline，" +
                "六份消息常量就烘进 dex，体积地板得再核一遍",
            1, indicesOf(code, "inline fun ").size,
        )

        val declared = code.indexOf(HELPER)
        assertTrue(
            "取证日志 helper 没了或改了名（现在只有 $HELPER 这一个靶子）：§2.5 要的『跑赢没跑赢』就没数据了",
            declared >= 0,
        )
        val modifiers = code.substring(0, declared).lines().last().trim()
        assertTrue("logHold 不许带 inline（见上面那条计数）：这一行的修饰符是 $modifiers", !modifiers.contains("inline"))
        assertTrue(
            "logHold 必须是 internal —— 它是 WakeLocks 的内部实现，公开出去等于给全模块加一枚日志门面" +
                "（public inline 函数碰不到 private 成员，所以要配 @PublishedApi）。现在是：$modifiers",
            modifiers.contains("internal"),
        )

        // 声明自身那处 `fun logHold(` 不是调用，先筛掉：不筛的话这里数出的两处里下标靠后的那个正是声明，
        // 下面两条"在 block 之后""在 finally 里"量的都是声明的位置，把调用点挪到 block 之前也不会红。
        val calls = indicesOf(code, CALL).filterNot { code.substring(0, it).trimEnd().endsWith("fun") }
        assertEquals(
            "取证日志要恰好被调用一次（$CALL 出现两处 = 声明 + 一次调用）：零处是没接上，多了是重复记录",
            1, calls.size,
        )
        val call = calls[0]
        assertTrue("取证日志必须在 block 返回之后打：之前既没有耗时，也没有 isHeld 的结论", call > blockCall(code))
        assertTrue(
            "取证日志要落在 finally 里：block 抛异常的那一轮同样要留痕，否则统计永远偏乐观",
            call > code.indexOf("finally {"),
        )
    }

    /** 4：W 由「锁还在不在手上」这个精确信号触发，不拿耗时跟上限比大小 */
    @Test
    fun warningIsKeyedToTheSampledFlagNotToATimeComparison() {
        val code = code()
        val declared = code.indexOf(HELPER)
        assertTrue("找不到 $HELPER：取证日志没了", declared >= 0)
        val open = code.indexOf('{', code.indexOf(')', declared))
        val close = matchingClose(code, open)
        check(close != null) { "$FILE：logHold 的花括号配不上对，解析器该修了" }
        val helper = code.substring(open + 1, close)
        val raw = withoutComments(wakeLocksText().substring(open + 1, close))
        val signature = normalize(code).substringAfter(HELPER).substringBefore(")")

        assertEquals("没跑赢超时的 W 要恰好一处：多了就是有一轮明明跑赢也被判成故障", 1, indicesOf(helper, "Log.w(").size)
        assertEquals(
            "跑赢了也要留一行 D（tag + 耗时 + 是否仍在持锁）：只有 W 的话，审计 §4.3 那边数不到「一共醒了几次」",
            1, indicesOf(helper, "Log.d(").size,
        )

        val bools = Regex("(\\w+)\\s*:\\s*Boolean").findAll(signature).map { it.groupValues[1] }.toList()
        assertEquals(
            "取证日志必须接住「退出时锁还在手上」那一位布尔量（它来自 block 之后那次 isHeld 采样）：" +
                "不接它就退化成拿耗时猜，$bools",
            1, bools.size,
        )
        val longs = Regex("(\\w+)\\s*:\\s*Long").findAll(signature).map { it.groupValues[1] }.toList()
        assertTrue("取证日志要同时拿到实际耗时与超时上限，缺一个就定不了「跑没跑赢」：$longs", longs.size >= 2)
        assertTrue(
            "W 的触发条件必须是那位布尔量，出现 $bools 之外的判断说明精度已被换掉",
            Regex("if\\s*\\(\\s*!?\\s*${bools[0]}(\\s|\\))").containsMatchIn(helper),
        )
        for (a in longs) {
            for (b in longs) {
                assertTrue(
                    "不许拿耗时跟上限比大小来代替 isHeld 那个精确信号（超时刚触发、block 恰好返回的那格竞态里" +
                        "它会说谎，而谎报的代价是下一轮据此把超时调小）：$a vs $b",
                    !Regex("${Regex.escape(a)}\\s*[<>]=?\\s*${Regex.escape(b)}").containsMatchIn(helper),
                )
            }
        }

        assertTrue(
            "两条日志都要把 tag 与实际耗时写进消息（对不上是哪个锁、跑了多久，这数据就没法用）",
            raw.contains("=\$tag") && raw.contains("=\${elapsedMs}") && raw.contains("=\${timeoutMs}"),
        )
        assertTrue(
            "held=true / held=false 是给 `adb logcat -s WakeLocks` 之后 grep 的判据字段，两条各留一个",
            raw.contains("held=true") && raw.contains("held=false"),
        )
    }

    // ---- 源码定位与解析 --------------------------------------------------------

    /** withPartialWakeLock 里真正跑业务的那次 block() 调用（不含无 PowerManager 时的兜底调用） */
    private fun blockCall(code: String): Int {
        val tryAt = code.indexOf("return try {")
        assertTrue("block 不再包在 try 里（finally 那套归还逻辑的形状变了）", tryAt >= 0)
        val at = code.indexOf(BLOCK_CALL, tryAt)
        assertTrue("try 里找不到 $BLOCK_CALL：block 没被调用，锁罩住了个空？", at >= 0)
        return at
    }

    private fun code(): String = blankCommentsAndLiterals(wakeLocksText())

    private fun wakeLocksText(): String {
        val file = File(findMainJavaDir(), FILE_FROM_JAVA)
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

    /** 抹注释、留字面量：检查消息正文用的是这一份（注释里也可能写着 held= 这种字样） */
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
     * 结构判断（谁在谁前面、出现几次）只看代码：注释里本来就写着「此时 isHeld=false」这类话，
     * 拿原文去查会自己把自己判红；字面量里也可能有括号。抹成等长空格后，
     * 同一套下标既能切代码也能切原文。
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

    /** 折行与缩进不算内容：注释与字面量已被抹成空格，这里再把连续空白压成一个 */
    private fun normalize(code: String): String = code.replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val FILE = "app/src/main/java/com/buaa/schedule/reminder/WakeLocks.kt"
        const val FILE_FROM_JAVA = "com/buaa/schedule/reminder/WakeLocks.kt"
        const val CLOCK = "SystemClock.elapsedRealtime()"
        const val BLOCK_CALL = "block()"
        const val HELPER = "fun logHold("
        const val CALL = "logHold("
        const val HELD = "isHeld"
    }
}
