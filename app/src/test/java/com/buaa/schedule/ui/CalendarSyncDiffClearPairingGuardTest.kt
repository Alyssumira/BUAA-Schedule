package com.buaa.schedule.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T100②：`CalendarSyncUiState.skippedOccurrences` 与 `diff` 是 `CalendarSyncManager.computeDiff`
 * 一次返回的**同一对**（`Pair<CalendarSyncPlanner.Diff, Int>`），清空 `diff` 的站点必须把它一起清 0。
 *
 * 病（T97 审计 §2.2 记下的那一笔相邻形状）：成对写只有一处、成对清也只有一处 ——
 * `startCalendarSync` 起手那档 `it.copy(syncing = true, message = null, diff = null)` 与
 * `confirmCalendarSync` 那档 `it.copy(diff = null, reminderMinutes = …)` 都只清了一半，
 * 于是"上一份 diff 的附属说明"会活在下一份 diff 之前。今天念不出错，只是因为唯一渲染点
 * 整块锁在 `ModalTransition(payload = calendarSync.diff)` 里 —— 那是一句**运行期巧合**，
 * 没有静态网钉着，跟 T94 那族"挂在别处的隐式前提"同一种形状。
 *
 * 为什么不收成 T95 那一刀（类体派生属性）：`skippedOccurrences` 不是 `f(diff)`（`Diff` 里没有
 * 这个数，两枚各有各的源：`CalendarSyncPlanner.compute(...)` 与 `ScheduleOccurrences.build(...).skipped`），
 * 所以它正当的落点只剩"每次成对写 / 每次成对清"这一条 —— 由本文件钉住。
 *
 * 两层：
 * 1. [everyDiffClearInViewModelAlsoClearsSkippedOccurrences] —— 写侧：**数出现次数 + 读形状**。
 *    先数 `ScheduleViewModel.kt`（抹注释后）里 `diff = null` 恰好 3 处，再逐处取出**包围它的那次
 *    `copy(…)` 实参表**（括号配平，不是"同一行"，换行写法也算），要求每一处都同时写 `skippedOccurrences = 0`。
 * 2. [thePairedProducerIsTheOnlyWriterAndTheOnlyReaderSitsInsideTheDiffModal] —— 读侧前提：
 *    生产者只有一处（`diff = computed.first` 与 `skippedOccurrences = computed.second` 各一枚），
 *    而界面唯一读点落在 `ModalTransition(payload = calendarSync.diff)` 那个窗口之内 ——
 *    有人把渲染点挪出弹窗、或再添第二处写点，本守卫当场红，逼着重新判一次"该不该成对"。
 *
 * 两枚都只做**源码核对**（JVM，无 Robolectric、无设备）。
 */
class CalendarSyncDiffClearPairingGuardTest {

    /**
     * 三处清空 `diff` 的站点（`startCalendarSync` 起手 / `confirmCalendarSync` 应用确认 /
     * `dismissCalendarSyncDiff`）必须**逐处**同时把 `skippedOccurrences` 清 0。
     *
     * 判据两头都钉：3 是"清点过、不许长出第四处漏网的清空站点"（朝松），
     * `skippedOccurrences = 0` 也是 3 是"三处一处都不许掉队"（朝紧的反面 —— 多一处单独清 0
     * 也算改了口径，要重新判一次）。
     */
    @Test
    fun everyDiffClearInViewModelAlsoClearsSkippedOccurrences() {
        val code = blankCommentsKeepingLiterals(readMainSource(SCHEDULE_VIEW_MODEL))
        val hits = indexOfAll(code, DIFF_CLEARED)
        assertEquals(
            "清空 diff 的站点从 3 处变了（现在是 startCalendarSync / confirmCalendarSync / " +
                "dismissCalendarSyncDiff 各一处）。多一处 = 多一次成对清理要写，少一处 = 有人换了写法：" +
                lineHints(code, hits),
            3,
            hits.size,
        )
        val unpaired = hits.filter { !copyArgumentList(code, it).contains(SKIPPED_CLEARED) }
        assertTrue(
            "这些 `diff = null` 站点没把配套的 `skippedOccurrences = 0` 一起写 —— " +
                "两枚是 computeDiff 一次返回的同一对，漏一半就是让上一份 diff 的附属说明活到下一份之前" +
                "（本卡 T100① 刚修掉的就是 startCalendarSync 与 confirmCalendarSync 这两处）：" +
                lineHints(code, unpaired),
            unpaired.isEmpty(),
        )
        assertEquals(
            "`skippedOccurrences = 0` 的处数不等于 diff 清空站点的处数：成对清被打乱了",
            hits.size, occurrences(code, SKIPPED_CLEARED),
        )
    }

    /**
     * 钉这条链的两端：写侧只有一对、读侧只在弹窗里。
     *
     * 第二枚断言是"今天看不出错"的那句解释本身 —— 唯一读点长在 payload 驱动的弹窗块里，
     * `diff == null` 时整块不组合。把它钉住不是为了永久豁免，而是**挪动它就必须重新判一次**：
     * 渲染口径一改（比如把"跳过了 N 个课次"变成常驻状态行），漏清就从看不见的账变成念给用户的旧数。
     */
    @Test
    fun thePairedProducerIsTheOnlyWriterAndTheOnlyReaderSitsInsideTheDiffModal() {
        val code = blankCommentsKeepingLiterals(readMainSource(SCHEDULE_VIEW_MODEL))
        assertEquals(
            "diff 的写点应当恰好一处、且与 skippedOccurrences 同处（`computed.first` / `computed.second` " +
                "出自同一次 computeDiff）：写点变两处就是两把尺子",
            1, occurrences(code, "diff = computed.first"),
        )
        assertEquals(
            "skippedOccurrences 的生产者应当恰好一处，且与上面那枚 diff 写点成对",
            1, occurrences(code, "skippedOccurrences = computed.second"),
        )
        assertEquals(
            "computeDiff 的返回形状变了（Pair<Diff, Int> 是这两枚成对的唯一凭据）：$SHAPE_ANCHOR",
            1, occurrences(blankCommentsKeepingLiterals(readMainSource(CALENDAR_SYNC_MANAGER)), SHAPE_ANCHOR),
        )

        val screen = blankCommentsKeepingLiterals(readMainSource(SETTINGS_SCREEN))
        val reads = indexOfAll(screen, "calendarSync.skippedOccurrences")
        assertEquals("界面读 skippedOccurrences 的点数变了（现在只有弹窗里那一行的判据与文案两处）：", 2, reads.size)
        val modalAt = screen.indexOf(MODAL_ANCHOR)
        check(modalAt >= 0) { "找不到 $MODAL_ANCHOR：弹窗那层的驱动方式换过了，本守卫要跟着改" }
        val nextModal = screen.indexOf("ModalTransition(", modalAt + MODAL_ANCHOR.length)
        check(nextModal > modalAt) { "diff 弹窗之后找不到下一个 ModalTransition 边界，窗口切不出来" }
        val windowLength = nextModal - modalAt
        check(windowLength in WINDOW_MIN..WINDOW_MAX) {
            "diff 弹窗那一段的长度越界（$windowLength 不在 $WINDOW_MIN..$WINDOW_MAX）：窗口不是原来那块了，得重判"
        }
        for (at in reads) {
            assertTrue(
                "界面读 skippedOccurrences 的点跑到了 diff 弹窗之外 —— 那等于给一枚只在 diff 期间有意义的" +
                    "字段加了常驻读者，漏清的旧值就会真的念给用户听",
                at in modalAt until nextModal,
            )
        }
    }

    // ---- 源码核对小工具（抄 SpecialDayRefreshWiringGuardTest / PendingImportConflictGroupTest）----

    /** 每次命中所在行的行号与原文：失败消息要能把人带到那一处，只报个数等于没有守卫 */
    private fun lineHints(source: String, positions: List<Int>): String {
        if (positions.isEmpty()) return ""
        return "\n" + positions.joinToString("") { at ->
            val start = source.lastIndexOf('\n', at).let { if (it < 0) 0 else it + 1 }
            val end = source.indexOf('\n', at).let { if (it < 0) source.length else it }
            "  L${source.substring(0, at).count { it == '\n' } + 1}: " +
                source.substring(start, end).trim() + "\n"
        }
    }

    /**
     * 从 [hit] 往前找包围它的那次 `copy(`，再按括号配平（跳过字符串字面量）取出实参表。
     *
     * 不用"同一行"当判据：本仓这种写法换行很常见（T100① 那两处就是为了不让单行过宽而折的行）。
     * 找不到 `copy(` 就抛 —— 静默跳过等于没有守卫。
     */
    private fun copyArgumentList(source: String, hit: Int): String {
        val open = source.lastIndexOf("copy(", hit)
        check(open >= 0) { "`diff = null` 这一处往前找不到包围它的 copy(：写法换过了，本守卫要跟着改" }
        var depth = 0
        var index = open + "copy".length
        var inString = false
        var escaped = false
        while (index < source.length) {
            val c = source[index]
            when {
                inString -> when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }

                c == '"' -> inString = true
                c == '(' -> depth++
                c == ')' -> {
                    depth--
                    if (depth == 0) {
                        val args = source.substring(open + "copy(".length, index)
                        check(hit in (open + 5)..index) { "取出的 copy 实参表不含那一处 diff = null：配平跑偏了" }
                        return args
                    }
                }
            }
            index++
        }
        throw IllegalStateException("copy( 的括号没配平：$DIFF_CLEARED 那一处切不出实参表")
    }

    private fun indexOfAll(haystack: String, needle: String): List<Int> {
        val hits = mutableListOf<Int>()
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return hits
            hits += at
            from = at + needle.length
        }
    }

    private fun occurrences(haystack: String, needle: String): Int = indexOfAll(haystack, needle).size

    private fun readMainSource(relativeFromJava: String): String {
        val file = File(findMainJavaDir(), relativeFromJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
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

    /**
     * **只抹注释、保留字符串字面量内容**，长度与换行位置不变（行号因此仍然可信）。
     *
     * 两种现成的刀法在这张卡上都不可用，所以自己走一遍：
     * - `SpecialDayRefreshWiringGuardTest.blankComments`（块注释整段换成一个空格）会挪动行号，
     *   失败消息里的行号就成了假的；
     * - `CalendarModeClassBellCleanupTest.blankCommentsAndLiterals`（连字面量内容一起抹）会把
     *   `SettingsScreen.kt:1916` 那句 `"有 ${calendarSync.skippedOccurrences} 个课次…"` 一起抹掉，
     *   界面唯一读点的两处就数出一处。
     * 反过来，`SettingsScreen.kt:1167` 那个**写在字符串字面量里**的块注释开头两个字符，若按
     * "不认字面量的行扫描"处理会把后面的代码整段吞掉（同族那条警告说的就是这件事），所以这里
     * 照 `blankCommentsAndLiterals` 的走法认字面量，只是**不抹它的内容**。
     *
     * 留下的口子如实记：若有人把 `diff = null` 写进字符串字面量，本守卫会把它数成一处清空站点。
     * 数不对时失败消息会连行号与原文一起打出来，一眼能看出数是哪儿来的。
     */
    private fun blankCommentsKeepingLiterals(source: String): String {
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

    private companion object {
        const val SCHEDULE_VIEW_MODEL = "com/buaa/schedule/ui/ScheduleViewModel.kt"
        const val SETTINGS_SCREEN = "com/buaa/schedule/ui/settings/SettingsScreen.kt"
        const val CALENDAR_SYNC_MANAGER = "com/buaa/schedule/data/calendar/CalendarSyncManager.kt"
        const val DIFF_CLEARED = "diff = null"
        const val SKIPPED_CLEARED = "skippedOccurrences = 0"
        const val MODAL_ANCHOR = "ModalTransition(payload = calendarSync.diff)"
        const val SHAPE_ANCHOR = "fun computeDiff(calendarId: Long): Pair<CalendarSyncPlanner.Diff, Int>?"

        /** diff 弹窗那一段的合理长度带：现在实测 1,740 字符（含标题到下一个 ModalTransition） */
        const val WINDOW_MIN = 200
        const val WINDOW_MAX = 6_000
    }
}
