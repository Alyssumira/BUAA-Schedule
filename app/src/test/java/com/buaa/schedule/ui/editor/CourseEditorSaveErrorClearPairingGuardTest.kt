package com.buaa.schedule.ui.editor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T104②：`CourseEditorScreen` 里 `saving`（底栏按钮在不在"保存中..."）与 `saveError`（底栏上方那条
 * **常驻**红条）是同一枚"底栏操作状态"的两半 —— 凡是**立起 `saving` 旗标**（= 开始一项会写库的操作）
 * 的站点，都必须把配套的 `saveError = null` 一起写。
 *
 * 病（T103 审计 §6.3 记下的那一枚，全仓那一遍唯一的"真漏清"）：保存链起手两行连号
 * （`saving = true` / `saveError = null`），删除链**只抄了一半**（立旗标、不收回上一句错）。
 * 于是"保存失败 → 不重试 → 改点同屏『删除课程』→ 确认"这条路走进去时，红条还在说
 * 「保存失败，请重试；草稿已保留」，而此刻课正在被删掉 —— 那句是假话。
 *
 * 这与刚合掉的 T100 是**同一族病换了载体**：不是 `copy()` 少点一枚实参，而是同一段仪式在两处操作里
 * 被复制成两半。判据形状、抹注释保字面量的读法、报错话术都照
 * `app/src/test/java/com/buaa/schedule/ui/CalendarSyncDiffClearPairingGuardTest.kt`。
 *
 * 两层：
 * 1. [everySavingTrueSiteAlsoClearsSaveErrorInItsLaunchBlock] —— 写侧：**数出现次数 + 读形状**。
 *    先数 `CourseEditorScreen.kt`（抹注释后）里 `saving = true` 恰好 2 处，再逐处取出**包围它的那段
 *    `scope.launch { … }` 体**（花括号配平，不是"同一行"，也不是整个函数体），要求每一处都恰好清一次
 *    `saveError`，且**清点排在该段第一次写错误文案之前**。
 * 2. [theSaveErrorMessageReaderSitsUngatedInThePersistentBottomBar] —— 读侧前提：那句"漏清看得见"
 *    的解释靠的是红条长在底栏、**不套在任何 `saving` / `canSave` / `ModalTransition` 闸门里**。
 *    钉住它不是为了永久豁免，而是**挪动它就必须重新判一次**：读侧哪天加了闸门，漏清就从念给用户的
 *    假话降级成看不见的旧值，本卡的档位要跟着改（T100 那枚守卫的第二层判的是同一件事）。
 *
 * 两枚都只做**源码核对**（JVM，无 Robolectric、无设备、不读时钟）。
 */
class CourseEditorSaveErrorClearPairingGuardTest {

    /**
     * 两处立旗标的站点（`performSave()` 的保存链 / 删除确认框的删除链）必须**逐处**在自己的
     * `scope.launch { … }` 体里把 `saveError` 清成 null，且清在写文案之前。
     *
     * 判据两头都钉：`saving = true` 恰好 2 是"清点过、不许长出第三处漏网的站点"，
     * `saveError = null` 也恰好 2 是"两把尺子一一对应"（多一次单独清 = 改了口径，同样要重新判）。
     */
    @Test
    fun everySavingTrueSiteAlsoClearsSaveErrorInItsLaunchBlock() {
        val code = blankCommentsKeepingLiterals(readMainSource(COURSE_EDITOR_SCREEN))
        val raised = indexOfAll(code, SAVING_RAISED)
        assertEquals(
            "立 `saving = true` 的站点从 2 处变了（现在只有 performSave() 的保存链与删除确认框的删除链" +
                "各一处）。**多一处** = 又添了一项会写库的操作，它的起手必须同样成对清 `saveError`" +
                "（这一族漏清已经数第二回了，T100 是 diff/skippedOccurrences，本卡是 saving/saveError）；" +
                "**少一处** = 有人换了旗标的写法（`saving = !saving` / 抽成函数 / 改了名），" +
                "本守卫的字面判据跟不上了，得重判：" +
                lineHints(code, raised),
            RAISED_SITES,
            raised.size,
        )
        val blocks = raised.map { at -> at to scopeLaunchBody(code, at) }
        val unpaired = blocks.filter { (_, block) -> !block.contains(SAVE_ERROR_CLEARED) }.map { it.first }
        assertTrue(
            "这些 `saving = true` 站点所在的那段 scope.launch 里根本没有 `saveError = null` —— " +
                "两枚是同一次「开始一项写库操作」的两半，漏一半就是让上一次操作的错话活在本次操作期间。" +
                "本卡 T104① 修掉的正是删除链那一处（立了旗标没收回「保存失败，请重试；草稿已保留」，" +
                "而课正在被删掉）—— 拆掉它就是让那条路重新通：" +
                lineHints(code, unpaired),
            unpaired.isEmpty(),
        )
        val doubledUp = blocks.filter { (_, block) -> occurrences(block, SAVE_ERROR_CLEARED) > 1 }
        assertTrue(
            "同一段 scope.launch 里清了两次 `saveError`：一次就够。多出来的那次说明这段里塞进了" +
                "第二趟操作 —— 那趟操作自己也得有立旗标的那一半，得回来重判配对：" +
                lineHints(code, doubledUp.map { it.first }),
            doubledUp.isEmpty(),
        )
        val clearedLate = blocks.filter { (_, block) -> !clearedBeforeFirstMessageWrite(block) }
        assertTrue(
            "这些站点的 `saveError = null` 排在了本段**第一次写错误文案之后** —— 清点必须紧跟立旗标" +
                "（操作开始前收场）。排在收尾分支里等于整段操作期间红条还在念上一次的话，" +
                "本卡那一枚「删课途中还在说保存失败」就没修：" +
                lineHints(code, clearedLate.map { it.first }),
            clearedLate.isEmpty(),
        )
        assertEquals(
            "`saveError = null` 的处数与立旗标的处数不再一一对应（现在两处立旗标各配一次清场）。" +
                "**多出来的一次清点** = 有人新加了一条不立旗标就清场的路径，异步链换了写法，" +
                "要重判谁在挡那颗按钮；**少一次** = 两项操作共用了同一次清场，一次清场挡不住两段并发",
            raised.size,
            occurrences(code, SAVE_ERROR_CLEARED),
        )
        assertEquals(
            "写错误文案的站点应当恰好 2 处（保存失败 / 删除失败各一句，出自各自那条链的失败分支）。" +
                "多一处 = 多一项要配套清场的操作；少一处 = 有一条链不再对用户说实话",
            MESSAGE_SITES,
            occurrences(code, SAVE_ERROR_MESSAGE_PREFIX),
        )
    }

    /**
     * 钉这条链的读侧：红条只有那一张嘴，而且那张嘴长在**没有闸门**的常驻底栏里。
     *
     * 第二格（`Text(if (saving) "保存中..." else "保存")` 恰好 1 处）是**明留登记**：删除链复用 `saving`
     * 那枚旗标，所以删课期间那颗保存按钮写着"保存中..."。这一枚本卡按红线没动，
     * 谁哪天改它，这里必须红一次、逼着回来把 §6.3 那笔账一起收掉。
     */
    @Test
    fun theSaveErrorMessageReaderSitsUngatedInThePersistentBottomBar() {
        val code = blankCommentsKeepingLiterals(readMainSource(COURSE_EDITOR_SCREEN))
        val lifePoints = indexOfAll(code, FIELD)
        assertEquals(
            "`saveError` 在这颗文件里的生命点不再是 7 处（1 声明 + 2 清 + 2 写文案 + 2 读）。" +
                "**多一处读者** = 给这枚字段添了第二张嘴，「漏清会不会被念给用户」要按新读者重算；" +
                "**少一处** = 有半条链被换掉了（写法或载体都变了），上面的成对判据得跟着重钉：\n" +
                lineHints(code, lifePoints),
            FIELD_LIFE_POINTS,
            lifePoints.size,
        )
        val gate = indexOfAll(code, READER_GATE)
        val render = indexOfAll(code, READER_RENDER)
        assertEquals("红条的判据行应当恰好一处（`if (saveError != null) {`）：", 1, gate.size)
        assertEquals("红条的渲染行应当恰好一处（`text = saveError ?: \"\"`）：", 1, render.size)
        for (at in gate + render) {
            val heads = enclosingBlockHeads(code, at)
            assertTrue(
                "红条那张嘴不在底栏（`bottomBar = {`）里了 —— 常驻/临时换了载体，" +
                    "「漏清一半会不会被念出来」这笔账要重算（T100 那一族的分档就看这一格）",
                heads.any { it.contains(BOTTOM_BAR) },
            )
            val wrapped = heads.firstOrNull {
                it.contains(SAVING_FLAG) || it.contains(CAN_SAVE) || it.contains(MODAL_WRAPPER)
            }
            assertTrue(
                "红条的读者外面套上了闸门（块头 `$wrapped`）—— 若它真的只在 `saving` / 弹窗为真时才组合，" +
                    "漏清就从看得见的假话降级成看不见的旧值，本卡的档位要跟着改；" +
                    "`canSave` 也算闸门（它就是 `!saving` 的别名，见 editorCanSave 的 saving 实参）：",
                wrapped == null,
            )
        }
        assertEquals(
            "明留那一枚口径缺陷的形状变了：底栏那颗按钮的字面上还写着 " +
                "`Text(if (saving) \"保存中...\" else \"保存\")`，而 `saving` 被删除链借用来表示\"正在删\"，" +
                "于是删除时说\"保存中...\"。本卡按红线没动它 —— 改它（或删除链不再复用 `saving`）的时候" +
                "这里该红一次，请连同 docs/derived-field-audit.md §6.3 那一格一起收账",
            1,
            occurrences(code, BUTTON_LABEL),
        )
        assertEquals(
            "「删除课程」那颗按钮的可达性是这条用户路径的一环：保存失败后 `saving` 已落回 false，" +
                "它此刻可点。它现在只有 `enabled = !saving` 一道闸，且**不**要求 `saveError == null` —— " +
                "哪天给它补一道 `&& saveError == null` 也算这一族的改法（闸住按钮 vs 清场，两选一要一起判），" +
                "这里该红一次",
            1,
            occurrences(code, DELETE_BUTTON_GATE),
        )
    }

    // ---- 源码核对小工具（抄 CalendarSyncDiffClearPairingGuardTest / SpecialDayRefreshWiringGuardTest）----

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
     * 从 [hit] 往前找包围它的那次 `scope.launch {`，再按花括号配平（跳过字符串字面量）取出那一段体。
     *
     * 不用"同一行"当判据（本仓这种写法换行很常见），也不用整个函数体当判据 —— 那样 `performSave()`
     * 里清的那一次会被删除链"借"去当绿灯。找不到 `scope.launch {` 就抛：静默跳过等于没有守卫。
     */
    private fun scopeLaunchBody(source: String, hit: Int): String {
        val open = source.lastIndexOf(LAUNCH_ANCHOR, hit)
        check(open >= 0) {
            "`saving = true` 这一处往前找不到包围它的 scope.launch {：旗标换了载体（协程外立旗？），本守卫要跟着改"
        }
        val brace = open + LAUNCH_ANCHOR.length - 1
        var depth = 0
        var index = brace
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
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) {
                        check(hit in (brace + 1)..index) { "取出的 scope.launch 体不含那一处 saving = true：配平跑偏了" }
                        return source.substring(brace + 1, index)
                    }
                }
            }
            index++
        }
        throw IllegalStateException("scope.launch { 的花括号没配平：$SAVING_RAISED 那一处切不出操作体")
    }

    /** 清点必须排在本段第一次写错误文案之前（排在收尾分支里就等于没清） */
    private fun clearedBeforeFirstMessageWrite(block: String): Boolean {
        val clear = block.indexOf(SAVE_ERROR_CLEARED)
        if (clear < 0) return true // 没配对那一事交给上面那枚断言报，两处一起报只会吵
        val writes = indexOfAll(block, SAVE_ERROR_MESSAGE_PREFIX)
        return writes.isEmpty() || writes.first() > clear
    }

    /**
     * [hit] 处**由外到内**每一层花括号块的"头"：那层 `{` 前面、到上一个换行 / `;` / 花括号为止的文本。
     *
     * 用来判"红条有没有被闸门套住"：`if (saving) {` / `ModalTransition(…) {` 都会成为某一层块头。
     * 只认块头、不认整段，是为了不把同一层里别的语句（例如那颗按钮的 `enabled = !saving`）误读成闸门。
     */
    private fun enclosingBlockHeads(source: String, hit: Int): List<String> {
        check(hit in source.indices) { "命中位置越界：$hit / ${source.length}" }
        check(source[hit] != '"') { "那一处落在字符串字面量里，切不出块头" }
        val opens = mutableListOf<Int>()
        var index = 0
        var inString = false
        var escaped = false
        while (index < hit) {
            val c = source[index]
            when {
                inString -> when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }

                c == '"' -> inString = true
                c == '{' -> opens += index
                c == '}' -> if (opens.isNotEmpty()) opens.removeAt(opens.size - 1)
            }
            index++
        }
        return opens.map { headBefore(source, it) }
    }

    /** `{` 前那一段块头：从最近的换行 / `;` / `}` / `{` 之后起算，压掉空白 */
    private fun headBefore(source: String, brace: Int): String {
        var from = brace
        while (from > 0) {
            val c = source[from - 1]
            if (c == '\n' || c == ';' || c == '{' || c == '}') break
            from--
        }
        return source.substring(from, brace).replace(whitespace, " ").trim()
    }

    private val whitespace = Regex("\\s+")

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

    private fun readMainSource(relativeFromMainJava: String): String {
        val file = File(findMainJavaDir(), relativeFromMainJava)
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
     * 读法与理由整段抄 `CalendarSyncDiffClearPairingGuardTest.blankCommentsKeepingLiterals`：
     * 整段换空格那把刀会挪行号（失败消息里的行号就成了假的），连字面量一起抹那把会把
     * `saveError = "…"` 的判据与按钮文案一起抹掉。这里还多一条本卡特有的理由：删除链上方那几行
     * **注释里写着 `saveError`**，不抹注释的话它会被数成第八枚生命点。
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
        const val COURSE_EDITOR_SCREEN = "com/buaa/schedule/ui/editor/CourseEditorScreen.kt"

        // 写侧
        const val SAVING_RAISED = "saving = true"
        const val SAVE_ERROR_CLEARED = "saveError = null"
        const val SAVE_ERROR_MESSAGE_PREFIX = "saveError = \""
        const val LAUNCH_ANCHOR = "scope.launch {"
        const val RAISED_SITES = 2
        const val MESSAGE_SITES = 2

        // 读侧
        const val FIELD = "saveError"
        const val FIELD_LIFE_POINTS = 7
        const val READER_GATE = "if (saveError != null) {"
        const val READER_RENDER = "text = saveError ?: \"\""
        const val BUTTON_LABEL = "Text(if (saving) \"保存中...\" else \"保存\")"
        const val DELETE_BUTTON_GATE = "enabled = !saving"
        const val BOTTOM_BAR = "bottomBar"
        const val SAVING_FLAG = "saving"
        const val CAN_SAVE = "canSave"
        const val MODAL_WRAPPER = "ModalTransition("
    }
}
