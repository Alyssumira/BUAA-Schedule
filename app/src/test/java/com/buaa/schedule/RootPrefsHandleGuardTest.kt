package com.buaa.schedule

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 根组合（`BUAAScheduleApp`）不许在组合期重复取 SharedPreferences 句柄。
 *
 * 这一族判据是「同一枚纯表达式的输入没变，却在每次重组时重算」。`getSharedPreferences`
 * 是一次跨进程 Binder 调用（结果由框架缓存，但仍有开销），本仓已经把"不许在组合期裸取"
 * 写成规矩，落点在 `ui/settings/SettingsScreen.kt` 的 `remember(context) { … }`——
 * 根组合那一处是这条规矩漏掉的第二处：它下面的 `remember { mutableStateOf(DarkModePreference.load(prefs)) }`
 * 与 `remember(prefs) { onDarkThemeChange }` 都把 `prefs` 当键/当入参用，句柄身份必须稳。
 *
 * 判据按**形状**核（本模块没有 Robolectric，`getSharedPreferences` 要 Context，跑不了组合期）：
 * `BUAAScheduleApp` 函数体内**每一处** `getSharedPreferences(` 都必须被一枚 `remember(` 包住，
 * 且那枚 remember 的键表里有 `context`。逐处扫而不是只扫第一处，是因为第二枚裸取的句柄
 * 会藏在同函数体后面而这条尺照样绿——那是"规矩只盖了一半"的原形状。
 *
 * 变异自证（本卡跑过，两向都算）：把 `remember(context) { … }` 摘掉、还原成
 * `val prefs = context.getSharedPreferences(...)` ⇒ 这一枚必须红；装回去必须绿。
 *
 * 读盘与切片口径同 `SectionHeaderInkGuardTest` / `GlassAlphaChainCacheGuardTest`
 * （`findMainJavaDir()` + 抹注释与字面量后再配平括号）；找不到目录直接抛，不用 assumeTrue
 * 跳过——找错路径只表现为"永远是绿的"，比红更糟。
 * 工作树输入已由 `app/build.gradle.kts` 的 `guardReadWorkingTreeFiles`（T86/T92①）声明，本卡不动构建脚本。
 */
class RootPrefsHandleGuardTest {

    /**
     * 函数体内每一处 `getSharedPreferences(` 都得在 `remember(context)` 里。
     *
     * 顺带钉住"取的还是那枚文件"：`schedule_settings` ——挪走偏好文件是另一张卡的事，
     * 这条尺不该跟着改（改了它就该红在这里，而不是静默换个文件继续绿）。
     */
    @Test
    fun rootCompositionNeverFetchesPrefsHandleDuringRecomposition() {
        val body = bodyOfFun(mainActivitySource(), "BUAAScheduleApp", MAIN_ACTIVITY_RELATIVE)
        val sites = occurrences(body.blanked, "getSharedPreferences(")
        assertTrue(
            "$MAIN_ACTIVITY_RELATIVE 的 BUAAScheduleApp 函数体里一处 getSharedPreferences( 都找不到了——" +
                "根组合不再读偏好？那是另一件事，这条尺要跟着改口径，不许留成空判据",
            sites > 0,
        )

        val offenders = mutableListOf<String>()
        var from = 0
        repeat(sites) {
            val at = body.blanked.indexOf("getSharedPreferences(", from)
            check(at >= 0)
            from = at + 1
            val cached = wrappingCall(body.blanked, "remember", at)
            val line = body.blanked.substring(0, at).count { it == '\n' } + 1
            when {
                cached == null ->
                    offenders += "第 $line 行：getSharedPreferences( 裸在组合期，每次重组都取一次句柄"
                !Regex("\\bcontext\\b").containsMatchIn(cached.args) ->
                    offenders += "第 $line 行：remember( 的键表里没有 context（${cached.args.trim()}）" +
                        "——LocalContext 换了（Activity 重建）就该重新取一次句柄"
            }
            if (!body.raw.substring(at, (at + 60).coerceAtMost(body.raw.length)).contains(PREFS_FILE)) {
                offenders += "第 $line 行：取的不再是「$PREFS_FILE」（换偏好文件是另一张卡的事，别改这条尺）"
            }
        }
        assertTrue(
            "根组合在组合期重复取 SharedPreferences 句柄（口径见 ui/settings/SettingsScreen.kt 的 " +
                "remember(context)）：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    // ---- 读盘与切片小工具（口径同 SectionHeaderInkGuardTest）--------------------

    private class Call(val start: Int, val end: Int, val args: String)
    private class Slice(val blanked: String, val raw: String)

    private fun mainActivitySource(): String {
        val file = File(findMainJavaDir(), MAIN_ACTIVITY_RELATIVE)
        assertTrue("找不到 ${file.path}：MainActivity 挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    private fun bodyOfFun(source: String, name: String, relative: String): Slice {
        val code = blankCommentsAndLiterals(source)
        val signature = code.indexOf("fun $name(")
        assertTrue("$relative 里找不到 fun $name( ——根组合被改名或拆掉了", signature >= 0)
        val paramsClose = matchingClose(code, code.indexOf('(', signature))
        check(paramsClose != null) { "$relative: $name 的参数表括号配不上对，解析器该修了" }
        val open = code.indexOf('{', paramsClose)
        check(open > paramsClose) { "$relative: $name 的参数表之后找不到函数体的起始花括号" }
        val close = matchingClose(code, open)
        check(close != null) { "$relative: $name 的大括号没配对" }
        return Slice(code.substring(open + 1, close), source.substring(open + 1, close))
    }

    /**
     * 包住 [inside] 的那一枚 `name(` 调用（跨度最小 = 最内层）。
     *
     * 跨度要**连尾随 lambda 一起算**：`remember(context) { context.getSharedPreferences(...) }`
     * 里取句柄那一处在块里、不在括号里，只配括号的话这条尺会把已经缓存好的那一处判成裸取
     * （本卡第一次跑就红在这儿，报了个反的）。
     */
    private fun wrappingCall(code: String, name: String, inside: Int): Call? {
        var best: Call? = null
        var from = 0
        while (true) {
            val open = code.indexOf("$name(", from)
            if (open < 0) break
            from = open + name.length
            if (open > 0 && (code[open - 1].isLetterOrDigit() || code[open - 1] == '_')) continue
            val argsClose = matchingClose(code, open + name.length) ?: continue
            val afterArgs = code.substring(argsClose + 1)
            val blockStart = argsClose + 1 + (afterArgs.length - afterArgs.trimStart().length)
            val blockClose = if (code.getOrNull(blockStart) == '{') matchingClose(code, blockStart) else null
            val end = if (blockClose == null) argsClose + 1 else blockClose + 1
            if (open <= inside && inside < end) {
                if (best == null || end - open < best.end - best.start) {
                    best = Call(open, end, code.substring(open + name.length + 1, argsClose))
                }
            }
        }
        return best
    }

    private fun occurrences(haystack: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(haystack).count()

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

    /** 把注释与字符串/字符字面量的内容抹成等长空格（换行位置不动），判据只读代码 */
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

    private companion object {
        const val MAIN_ACTIVITY_RELATIVE = "com/buaa/schedule/MainActivity.kt"
        const val PREFS_FILE = "schedule_settings"
    }
}
