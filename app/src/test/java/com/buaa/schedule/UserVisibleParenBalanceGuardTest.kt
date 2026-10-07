package com.buaa.schedule

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T181②：钉「app/src/main 里用户可见串的全角括号（）逐句配平」。
 *
 * ## 病
 *
 * `ScheduleViewModel.exportWakeUpTo` 那句「WakeUp JSON 已导出（N 门课；另有 M 门没有节次、
 * 无法定位，未写入」是**两段字面量拼起来**的：前一段开「（」、后一段没有关「）」。
 * 同一枚函数的另一支（一支没有缺节次的课）是闭合的 ⇒ 病只在"有一门课没有节次"那一档才可达
 * （可达性由 `EmptyPeriodPlacementTest` 证）—— 课表里只要躺着一条空节次的课，
 * Snackbar 就念一句括号不闭合的破句。
 *
 * ## 两把尺为什么必须分开取
 *
 * 逐枚字面量配平那一把，对**拼接链**会给假阳性（`"…（" + x.joinToString("、") + "…）"` 里
 * 首尾两枚各自都不平），而假阳性一旦有人手工加豁免名单，这条尺就退化成"改前那枚病活下来的
 * 同一形状"：括号押在下一行补齐、下一行被删或被搬走就再没人发现。于是：
 * 1. **全仓那一把**按"句"取：同一对括号（或同一花括号块）里的字面量算同一句话，判总数配平。
 *    改前那枚病在这一把尺下也不闭合（`showMessage(` 那一格里开 2 枚、闭 1 枚）⇒ 照样红；
 *    而 `if/else` 两支各自落在不同的 `{ }` 块里 ⇒ 两支各算一句 ⇒ 摘掉一支的右括号当场红。
 * 2. **纯文案那几份文件**按"逐枚字面量"取：那里的每一枚字面量都得自己闭合，而且**不许出现链**
 *    （链本身就是要防的那个形状，见 `WakeUpExportCopy.kt` 的两枚整句）。
 *
 * ## 反套套逻辑（不许拿"grep 没匹配到"当"全绿"）
 *
 * 档 ① 末尾两格量的是尺子自己有没有格子吃：带全角括号的"句"枚数（实测 178）与跨枚拼接的链枚数
 * （实测 51）都钉了下限 ⇒ 词法一旦退化到什么都不匹配，那两格当场红。档 ④ 再钉扫描面本身。
 *
 * 只做**源码核对**（JVM，无 Robolectric、无设备、不读时钟）。输入面：本守卫读 `src/main/java`
 * 下的工作树文本，已由 `app/build.gradle.kts` 里 `guardReadWorkingTreeFiles` 第 ① 项整体声明成
 * `testDebugUnitTest` 的输入 ⇒ 不必新增条目（"改一行注释、class 字节不变"那一档已被它堵住）。
 * ⚠️ 未装机：Snackbar 的实屏呈现没有装机证据，本卡零 adb。
 */
class UserVisibleParenBalanceGuardTest {

    private companion object {
        const val OPEN = '（'
        const val CLOSE = '）'

        const val WAKE_UP_COPY = "com/buaa/schedule/ui/settings/WakeUpExportCopy.kt"
        const val IMPORT_COPY = "com/buaa/schedule/ui/importing/ImportCompletionCopy.kt"
        const val CONFLICT_COPY = "com/buaa/schedule/ui/importing/ImportConflictCopy.kt"
        const val STATS_CONFLICT_COPY = "com/buaa/schedule/ui/stats/StatsConflictCopy.kt"
        const val STATS_DRILL_COPY = "com/buaa/schedule/ui/stats/StatsDrillCopy.kt"
        const val VIEW_MODEL = "com/buaa/schedule/ui/ScheduleViewModel.kt"

        /** 纯文案文件：每一枚字面量都得自己闭合，且不许拆成链 */
        val COPY_FILES = listOf(WAKE_UP_COPY, IMPORT_COPY, CONFLICT_COPY, STATS_CONFLICT_COPY, STATS_DRILL_COPY)

        const val WAKEUP_LITERAL_HEAD = "WakeUp JSON 已导出"

        /** 带全角括号的"句"枚数下限（实测 178）：读到很小的数 = 词法退化，不是"今天都干净" */
        const val MIN_UNITS_WITH_PARENS = 150

        /** 一句由多枚字面量拼成（链）的枚数下限（实测 51）：改前那枚病就长在链上 */
        const val MIN_MULTI_LITERAL_CHAINS = 20
    }

    // ─────────────── ① 全仓：按"句"配平 ───────────────

    @Test
    fun `main 源码里每一句带全角括号的话都闭合`() {
        val report = ArrayList<String>()
        var unitsWithParens = 0
        var chains = 0
        for (path in mainSourcePaths()) {
            for (unit in unitsOf(path)) {
                if (unit.opens == 0 && unit.closes == 0) continue
                unitsWithParens++
                if (unit.literalCount > 1) chains++
                if (unit.opens != unit.closes) {
                    report += "  " + path + " 起于 L" + unit.startLine + "：开「（」" + unit.opens +
                        " 枚、闭「）」" + unit.closes + " 枚（这一句由 " + unit.literalCount +
                        " 枚字面量拼成）⇒ " + unit.preview()
                }
            }
        }
        assertEquals(
            "app/src/main 里用户可见串的全角括号必须逐句配平（期望违例 0 枚，实际 " + report.size + " 枚）：\n" +
                report.joinToString(separator = "\n") +
                "\n改前那枚病是 \"WakeUp JSON 已导出（N 门课；\" + \"另有 M 门…未写入\" —— " +
                "前一段开括号、后一段没关。整句都得自己闭合（写法见 WakeUpExportCopy.kt）",
            0,
            report.size,
        )
        assertTrue(
            "反套套逻辑：带全角括号的句至少 " + MIN_UNITS_WITH_PARENS + " 枚，实到 " + unitsWithParens +
                " 枚 ⇒ 读到很小的数就说明词法退化（别拿『没匹配到』当『全绿』）",
            unitsWithParens >= MIN_UNITS_WITH_PARENS,
        )
        assertTrue(
            "反套套逻辑：跨枚拼接的链至少 " + MIN_MULTI_LITERAL_CHAINS + " 枚，实到 " + chains +
                " 枚 ⇒ 读到 0 就说明『并成一句』那一层根本没在合并（改前那枚病正是靠链躲过逐枚尺子的）",
            chains >= MIN_MULTI_LITERAL_CHAINS,
        )
    }

    // ─────────────── ② 纯文案文件：逐枚字面量闭合、且不许是链 ───────────────

    @Test
    fun `纯文案文件里每一枚字面量自己闭合`() {
        val unbalanced = ArrayList<String>()
        val chained = ArrayList<String>()
        for (path in COPY_FILES) {
            val file = File(findMainJavaDir(), path)
            assertTrue("$path 不在盘上（改名/挪家要回来重钉本守卫）", file.isFile)
            for (token in tokensOf(path)) {
                if (token.joinedToPrevious) {
                    chained += "  $path 起于 L" + token.startLine + "：一句被 `+` 拆成两段，本枚是后一段 ⇒ " + token.preview()
                }
                if (token.opens == 0 && token.closes == 0) continue
                if (token.opens != token.closes) {
                    unbalanced += "  $path 起于 L" + token.startLine + "：开 " + token.opens +
                        " 枚、闭 " + token.closes + " 枚 ⇒ " + token.preview()
                }
            }
        }
        assertEquals(
            "纯文案文件里每枚字面量的全角括号必须自给自足（期望违例 0 枚，实际 " + unbalanced.size + " 枚）：\n" +
                unbalanced.joinToString(separator = "\n") +
                "\n这些文件是『整句由 JVM 单测逐字钉住』的那一层：句子拆两段，单测的期望值会跟着一起错",
            0,
            unbalanced.size,
        )
        assertEquals(
            "纯文案文件里不许出现拼接链（期望 0 枚，实际 " + chained.size + " 枚）：\n" +
                chained.joinToString(separator = "\n") +
                "\n链 = 开括号押在上一行、闭括号押在下一行，正是 T181② 那枚病活下来的形状",
            0,
            chained.size,
        )
    }

    // ─────────────── ③ WakeUp 那一句的形状：四枚、都在纯文案文件里 ───────────────

    @Test
    fun `WakeUp 导出那一句只有四枚且不在 VM 里`() {
        val inCopy = tokensOf(WAKE_UP_COPY).filter { WAKEUP_LITERAL_HEAD in it.text }
        assertEquals(
            "「WakeUp JSON 已导出」那句在 WakeUpExportCopy.kt 里恰好四枚（T181② 两枚 + T203 补的" +
                "两档：无缺失 / 只缺节次 / 只缺周次 / 两档都有），实到 " + inCopy.size + " 枚",
            4,
            inCopy.size,
        )
        for (token in inCopy) {
            assertEquals(
                "那四枚各自都得闭合（实到 开 " + token.opens + " 枚 / 闭 " + token.closes + " 枚）：" +
                    token.preview(),
                token.opens,
                token.closes,
            )
        }
        val inVm = tokensOf(VIEW_MODEL).count { WAKEUP_LITERAL_HEAD in it.text }
        assertEquals(
            "VM 里『WakeUp JSON 已导出』的字面枚数必须是 0（搬回 VM 就等于把它移出 JVM 单测可达的范围），" +
                "实到 " + inVm + " 枚",
            0,
            inVm,
        )
    }

    // ─────────────── ④ 反证：扫描面确实盖到那两枚新文件 ───────────────

    @Test
    fun `扫描面盖到那两枚新文件`() {
        val paths = mainSourcePaths()
        for (path in listOf(WAKE_UP_COPY, IMPORT_COPY)) {
            assertTrue("$path 必须出现在 walkTopDown 的扫描面里", path in paths)
        }
        assertTrue(
            "全仓 .kt 枚数要大于 150（读到很小的数 = findMainJavaDir 找错了目录，整把尺子是中空的），" +
                "实到 " + paths.size + " 枚",
            paths.size > 150,
        )
    }

    // ---------------- 词法：抹注释 → 取字面量（带所在括号的身份）→ 并成"句" ----------------

    private class Token(
        val startLine: Int,
        val text: String,
        /** 所在那对括号 / 那个花括号块的起点：同一块里的字面量算同一句话（负数 = 不在任何块里） */
        val scope: Int,
        /** 与上一枚字面量之间只隔着空白与 `+` ⇒ 这一枚是**拼接链**上的一段（if/else 另一支不算） */
        val joinedToPrevious: Boolean,
    ) {
        val opens: Int get() = text.count { it == OPEN }
        val closes: Int get() = text.count { it == CLOSE }
        fun preview(): String = text.replace(Regex("""\s+"""), " ").take(160)
    }

    private class Unit(val startLine: Int, val tokens: List<Token>) {
        val text: String get() = tokens.joinToString(separator = "") { it.text }
        val opens: Int get() = text.count { it == OPEN }
        val closes: Int get() = text.count { it == CLOSE }
        val literalCount: Int get() = tokens.size
        fun preview(): String = text.replace(Regex("""\s+"""), " ").take(160)
    }

    /** 同一对括号 / 同一个花括号块里的字面量算同一句话 */
    private fun unitsOf(path: String): List<Unit> {
        val groups = LinkedHashMap<Int, MutableList<Token>>()
        for (token in tokensOf(path)) {
            groups.getOrPut(token.scope) { ArrayList() } += token
        }
        return groups.values.map { Unit(startLine = it.first().startLine, tokens = it) }
    }

    private fun tokensOf(path: String): List<Token> = scan(blankComments(source(path)))

    private fun scan(code: String): List<Token> {
        val out = ArrayList<Token>()
        val stack = ArrayList<Int>()
        var i = 0
        var line = 1
        var anonymous = 0
        var previousEnd = -1
        while (i < code.length) {
            val c = code[i]
            when {
                c == '\n' -> {
                    line++
                    i++
                }

                c == '\'' -> i = skipCharLiteral(code, i)

                c == '"' -> {
                    val startLine = line
                    val start = i
                    val sink = StringBuilder()
                    val scope = stack.lastOrNull() ?: -(++anonymous)
                    val joined = previousEnd >= 0 &&
                        code.substring(previousEnd, start).all { it.isWhitespace() || it == '+' }
                    i = readString(code, i, sink)
                    line += code.substring(start, i).count { it == '\n' }
                    previousEnd = i
                    out += Token(startLine, sink.toString(), scope, joined)
                }

                c == '(' || c == '[' || c == '{' -> {
                    stack.add(i)
                    i++
                }

                c == ')' || c == ']' || c == '}' -> {
                    if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                    i++
                }

                else -> i++
            }
        }
        return out
    }

    /**
     * 读取 [at] 处那一枚字符串字面量，内容并进 [sink]，返回结束后的下标。
     *
     * 支持 `"""` 与 `"`、反斜杠转义、`${…}` 插值（插值里嵌套字面量的内容一并计入 sink，
     * 这样 `"…（${x ?: "未记"}）…"` 不会被当成两枚各带一半括号的"链"）。
     */
    private fun readString(code: String, at: Int, sink: StringBuilder): Int {
        val triple = code.startsWith("\"\"\"", at)
        val closer = if (triple) "\"\"\"" else "\""
        var i = at + closer.length
        while (i < code.length) {
            val c = code[i]
            if (c == '\\') {
                if (i + 1 < code.length) sink.append(code[i + 1])
                i += 2
                continue
            }
            if (code.startsWith(closer, i)) return i + closer.length
            if (!triple && c == '\n') return i
            if (c == '$' && i + 1 < code.length && code[i + 1] == '{') {
                i = skipInterpolation(code, i, sink)
                continue
            }
            sink.append(c)
            i++
        }
        return code.length
    }

    /** 跳过 `${…}`（按大括号深度配平），其间嵌套字面量的内容收进 [sink] */
    private fun skipInterpolation(code: String, at: Int, sink: StringBuilder): Int {
        var i = at
        var depth = 0
        while (i < code.length) {
            val c = code[i]
            if (c == '"') {
                i = readString(code, i, sink)
                continue
            }
            when (c) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
            i++
        }
        return code.length
    }

    private fun skipCharLiteral(code: String, at: Int): Int {
        var i = at + 1
        while (i < code.length) {
            when (code[i]) {
                '\\' -> i += 2
                '\'' -> return i + 1
                '\n' -> return i
                else -> i++
            }
        }
        return code.length
    }

    private fun mainSourcePaths(): List<String> {
        val root = findMainJavaDir()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).path.replace('\\', '/') }
            .toList()
            .sorted()
    }

    private fun source(relative: String): String = File(findMainJavaDir(), relative).readText()

    /** 与 ManualTimeOverrideWiringGuardTest 同写法：抹掉注释，字符串与行号位置不动 */
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
}
