package com.buaa.schedule.ui.editor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T321③：源码形状守卫 —— 把「调用点确实把判红字段递进了保存闸门」钉在盘上
 * （台账 #499 = #379 升档的接线侧）。
 *
 * 为什么这张卡必须有静态守卫：判据本体（[editorCanSave] 的第四枚合取 `redFields.isEmpty()`）
 * 有默认值 `emptySet()`，于是**生产调用点不递它照样编译、照样全绿** ——
 * `CourseEditorRedFieldGateTest` 那 25 行闸门表与 22 行红名单表全是直接调内核的，
 * 一枚都抓不到"调用点没接线"。本仓在册教训逐字就是这一条：**判据写得再对，调用点不递就是第二本账**。
 *
 * ## 五种钉法逐格点名
 *
 * 1. [红名单实参必须被调用点递进去]（题眼）—— **逐字节 + 数出现次数 + 探测器自检**：
 *    `redFields = fields.redFields` 在编辑器文件恰好一处、全仓 main 里 `editorCanSave(` 恰好两处
 *    （声明 1 + 调用 1）、屏幕文件里不许再长第二枚 `internal fun editorCanSave`；
 *    自检那一格把实参从**内存副本**里摘掉再数一次 —— 摘完数不到才算这枚针看得见 W 臂。
 * 2. [六格判红全部消费同一本红账] —— **数出现次数**：`fields.isRed(EditorRedField.` 恰好 12 处
 *    （六格 × isError + supportingText 首参各一枚），六枚枚举名各 2 处；
 *    同时**改前那六枚就地 Boolean 的标识符在全仓 main 归零**（换文件再算一遍 = 第二把尺）。
 * 3. [顶部汇总横幅与红账同源] —— **逐字节 + 归零**：`if (fields.anyRed) {` 一处、
 *    旧的两把尺 replica `periods.isEmpty() || weeks.isEmpty()` 归零、汇总那句话全仓 main 一处。
 * 4. [内核是纯 JVM 判据且没有第二份红账] —— **读形状（逐行扫 import）+ 数出现次数**：
 *    零 android/androidx import、红名单构造式一处、`EditorFieldVerdict(` 两处（声明 1 + 构造 1）、
 *    对 verdict 用 `.copy(` 归零（派生字段铁律：只有类体属性与逐构造点守卫两条落点，本卡走前者）。
 * 5. [没把邻居那两枚锚点吃进来] —— **逐字节**：`saving = saving || saveInFlight` 与
 *    `editorCanSave(weeks = weeks, periods = periods, saving = ` 各一处 —— 在册 #498 的修法（T260）
 *    与本卡同用这一行，本卡只往后添 `redFields`，一字没动它那两位。
 *
 * 通篇**不写死行号**：报错消息只给 文件 + 判据 + 实到枚数。只读源码文本，不证明运行期行为
 * （那一半归 [CourseEditorRedFieldGateTest]）；读法与小工具抄同仓 `data/share/ShareCodeEntryGateWiringGuardTest`
 * （抹注释保字面量 + 花括号配平），`app/src/main/java` 整目录已在 `app/build.gradle.kts` 的
 * `guardReadWorkingTreeFiles` 里进 `tasks.withType<Test>()` 的 inputs（T92①），
 * 所以"改了源码、单跑守卫拿到缓存绿灯"那一档盖得住。
 */
class CourseEditorRedFieldGateWiringGuardTest {

    // ─────────────── ① 题眼：调用点把红名单递进闸门 ───────────────

    @Test
    fun 红名单实参必须被调用点递进保存闸门() {
        val editor = blankCommentsKeepingLiterals(source(EDITOR_SCREEN))
        assertEquals(
            "$FILE_EDITOR：`$GATE_ARG` 恰好一处（实到 " + occurrences(editor, GATE_ARG) + " 处）。" +
                "**朝宽扭就在这一格红**：把 `redFields` 那一位摘掉，内核的默认值 `emptySet()` 就是" +
                "「没有一格判红」，行为一字不差退回基点（学分 " + "\"3,5\"、提前分钟 \"999\"、额外节次 \"abc\" " +
                "三格红着照样按得下去），而 `CourseEditorRedFieldGateTest` 那 25 行闸门表一枚都不会红 —— " +
                "判据写得再对，调用点不递就是第二本账",
            1,
            occurrences(editor, GATE_ARG),
        )
        assertEquals(
            "$FILE_EDITOR：`$GATE_CALL_PREFIX` 恰好一处（实到 " + occurrences(editor, GATE_CALL_PREFIX) +
                " 处）：本卡只在这枚调用点后头添了 `redFields`，前两位的形状一字未动",
            1,
            occurrences(editor, GATE_CALL_PREFIX),
        )
        assertEquals(
            "$FILE_EDITOR：屏幕文件里 `$GATE_DEFINITION` 必须归零（实到 " + occurrences(editor, GATE_DEFINITION) +
                " 处）：闸门定义搬进 $FILE_GATE 之后就只有一枚，屏幕文件里再长一枚就是两本账",
            0,
            occurrences(editor, GATE_DEFINITION),
        )
        val main = mainSourcesBlanked()
        assertEquals(
            "全仓 main 里 `editorCanSave(` 恰好 2 处（声明 1 + 调用 1，实到 " +
                occurrences(main, "editorCanSave(") + " 处）：多一处 = 有人另起一枚闸门；" +
                "少一处 = 生产侧断链",
            2,
            occurrences(main, "editorCanSave("),
        )
        // 探测器自检：把实参从内存副本里摘掉，上面那枚针必须数不到（摘完还数得到 = 针是假的）
        val widened = editor.replace(GATE_ARG, "")
        assertEquals(
            "自检：摘掉 `$GATE_ARG` 之后本守卫必须归零（实到 " + occurrences(widened, GATE_ARG) + " 处）",
            0,
            occurrences(widened, GATE_ARG),
        )
    }

    // ─────────────── ② 六格判红不许有第二本账 ───────────────

    @Test
    fun 六格判红全部消费同一本红账() {
        val editor = blankCommentsKeepingLiterals(source(EDITOR_SCREEN))
        assertEquals(
            "$FILE_EDITOR：`$IS_RED_PREFIX` 恰好 12 处（六格 × isError + supportingText 首参，实到 " +
                occurrences(editor, IS_RED_PREFIX) + " 处）：一处 = 有一格还自己判红；" +
                "两处 = 红框与闸门又分成两把尺（就是台账 #499 的改前形状）",
            12,
            occurrences(editor, IS_RED_PREFIX),
        )
        for (name in ENUM_NAMES) {
            val needle = "EditorRedField.$name"
            assertEquals(
                "$FILE_EDITOR：六枚枚举名逐枚各 2 处（isError + fieldError 首参），" +
                    "「$needle」实到 " + occurrences(editor, needle) + " 处",
                2,
                occurrences(editor, needle),
            )
        }
        val main = mainSourcesBlanked()
        // 改前那六枚就地 Boolean 的标识符：换文件再算一遍也算第二本账，所以按全仓 main 归零
        for (old in OLD_INLINE_FLAG_NAMES) {
            assertEquals(
                "全仓 main 里「$old」必须归零（实到 " + occurrences(main, old) +
                    " 处）：红只在 editorFieldVerdict 那一处判，任何文件再算一遍都是第二把尺",
                0,
                occurrences(main, old),
            )
        }
        assertEquals(
            "$FILE_EDITOR：`$CREDIT_NUMBER_USE` 恰好一处（实到 " + occurrences(editor, CREDIT_NUMBER_USE) +
                " 处）：学分数值由红账交回，performSave 不许再 toDoubleOrNull 第二遍",
            1,
            occurrences(editor, CREDIT_NUMBER_USE),
        )
        val inlineCreditParse = occurrences(main, "creditText.trim().toDoubleOrNull()")
        assertEquals(
            "全仓 main 里 `creditText.trim().toDoubleOrNull()` 恰好 1 处（内核那一枚，实到 " +
                inlineCreditParse + " 处）：调用点第二次 parse = 同一问两把尺",
            1,
            inlineCreditParse,
        )
    }

    // ─────────────── ③ 顶部汇总横幅与红账同源 ───────────────

    @Test
    fun 顶部汇总横幅与红账同源而旧那把尺归零() {
        val editor = blankCommentsKeepingLiterals(source(EDITOR_SCREEN))
        assertEquals(
            "$FILE_EDITOR：`$BANNER_ANCHOR` 恰好一处（实到 " + occurrences(editor, BANNER_ANCHOR) +
                " 处）：甲档的代价（看不出为什么按不下去）就由这一格出声 —— 它必须吃红账，" +
                "不吃红账就等于把三枚新入闸的红留在「静默灰掉」那一档",
            1,
            occurrences(editor, BANNER_ANCHOR),
        )
        assertEquals(
            "$FILE_EDITOR：旧 replica `$OLD_BANNER_CONDITION` 必须归零（实到 " +
                occurrences(editor, OLD_BANNER_CONDITION) +
                " 处）：那一枚只覆盖节次/周次两问，红着的学分与提前分钟它念不出来",
            0,
            occurrences(editor, OLD_BANNER_CONDITION),
        )
        val main = mainSourcesBlanked()
        assertEquals(
            "全仓 main 里汇总那句「$BANNER_COPY」恰好一处（实到 " + occurrences(main, BANNER_COPY) +
                " 处）：同句两写是本仓在册那一族病",
            1,
            occurrences(main, BANNER_COPY),
        )
    }

    // ─────────────── ④ 内核纯度与派生字段落点 ───────────────

    @Test
    fun 内核是纯JVM判据而红名单只有一处构造() {
        val gateRaw = source(EDITOR_GATE)
        val gate = blankCommentsKeepingLiterals(gateRaw)
        // 前缀 "android" 把 `android.*` 与 `androidx.*` 一并罩住（后者是前者的延长）
        val androidImports = importLinesStartingWith(gateRaw, "android")
        assertEquals(
            "$FILE_GATE：零 android / androidx import（实到 ${androidImports.size} 行：" +
                androidImports.joinToString() + "）。组合期不许读钟／权限／设备度量那条成文禁令的" +
                "另一半是「判据得能在 JVM 里跑」—— 读进来一行就废了",
            0,
            androidImports.size,
        )
        assertEquals(
            "$FILE_GATE：import 总数恰好 2（CourseConstraints + WeekParser，实到 " +
                gateRaw.lines().count { it.startsWith("import ") } + " 行）",
            2,
            gateRaw.lines().count { it.startsWith("import ") },
        )
        for (declaration in listOf("internal fun editorCanSave(", "internal fun editorFieldVerdict(")) {
            assertEquals(
                "$FILE_GATE：`$declaration` 恰好一处（实到 " + occurrences(gate, declaration) + " 处）",
                1,
                occurrences(gate, declaration),
            )
        }
        assertEquals(
            "$FILE_GATE：红名单的构造式 `$RED_SET_BUILD` 恰好一处（实到 " + occurrences(gate, RED_SET_BUILD) +
                " 处）：redFields 是 EditorFieldVerdict 的唯一那本账，第二处构造点就是第二本账",
            1,
            occurrences(gate, RED_SET_BUILD),
        )
        val main = mainSourcesBlanked()
        assertEquals(
            "全仓 main 里 `EditorFieldVerdict(` 恰好 2 处（类声明 1 + 内核 return 1，实到 " +
                occurrences(main, "EditorFieldVerdict(") + " 处）：派生字段铁律只承认两条落点 —— " +
                "类体属性，或每一个 copy/构造站点配守卫。本卡走前者，所以多一枚构造站点当场红",
            2,
            occurrences(main, "EditorFieldVerdict("),
        )
        assertEquals(
            "全仓 main 里 `$VERDICT_COPY` 必须归零（实到 " + occurrences(main, VERDICT_COPY) +
                " 处）：对红账用 copy 就是「值 = f(同表参数)」绕过守卫的第二条腿",
            0,
            occurrences(main, VERDICT_COPY),
        )
        assertEquals(
            "$FILE_GATE：闸门形参册子 —— `$GATE_RED_PARAM` 恰好一处（声明位）。" +
                "这一枚针**故意不钉那枚合取的原文**：N 臂摘掉 `&& redFields.isEmpty()` 时它必须仍然绿，" +
                "红只许落在表驱动层（两臂各红一层的归属才分得开）。实到 " + occurrences(gate, GATE_RED_PARAM) + " 处",
            1,
            occurrences(gate, GATE_RED_PARAM),
        )
    }

    // ─────────────── ⑤ 邻居那两枚锚点没被本卡吃进来 ───────────────

    @Test
    fun 本卡只往闸门后头添一位而没有动T260那两位() {
        val editor = blankCommentsKeepingLiterals(source(EDITOR_SCREEN))
        assertEquals(
            "$FILE_EDITOR：`$SAVE_IN_FLIGHT_UNION` 逐字节恰好一处（实到 " +
                occurrences(editor, SAVE_IN_FLIGHT_UNION) +
                " 处）：在册 #498 的修法（T260 的 saveInFlight）与本卡共用这一行，本卡一字未动（硬约束 4）",
            1,
            occurrences(editor, SAVE_IN_FLIGHT_UNION),
        )
        assertEquals(
            "$FILE_EDITOR：`$SAVE_IN_FLIGHT_PARAM` 恰好一处（形参声明）：本卡没碰它",
            1,
            occurrences(source(EDITOR_SCREEN), SAVE_IN_FLIGHT_PARAM),
        )
        assertEquals(
            "$FILE_EDITOR：`editorCanSave(` 的调用形状仍以前缀 `$GATE_CALL_PREFIX` 在场（实到 " +
                occurrences(editor, GATE_CALL_PREFIX) + " 处）：在册 " +
                "EditorSaveChainTailReceiverGuardTest 钉的就是这枚前缀，本卡添参数只许添在它尾巴后面",
            1,
            occurrences(editor, GATE_CALL_PREFIX),
        )
    }

    // ---------------- 源码核对小工具（与仓内其它静态守卫同一口径）----------------

    private fun source(relativeFromMainJava: String): String {
        val file = File(findMainJavaDir(), relativeFromMainJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    /** 全仓 main 的 .kt，逐枚抹注释保字面量后拼成一整段文本供数出现次数 */
    private fun mainSourcesBlanked(): String {
        val root = findMainJavaDir()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${root.path} 下一个 .kt 都没有：路径不对，本守卫就是空的", files.isNotEmpty())
        return files.joinToString("\n") { blankCommentsKeepingLiterals(it.readText().replace("\r\n", "\n")) }
    }

    private fun occurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    /** 逐行扫 import：按前缀取那些行（不吃行号，返回的是原文行内容） */
    private fun importLinesStartingWith(code: String, prefix: String): List<String> =
        code.split('\n').mapNotNull { line ->
            val t = line.trim()
            if (t.startsWith("import $prefix")) t else null
        }

    /**
     * **只抹注释、保留字符串字面量内容**，长度与换行位置不变。
     * 本卡尤其需要抹注释：两枚文件的 KDoc 都逐字引用着改前那三行形状（登记旧账用），
     * 不抹就是把自己写的说明当病灶数进去。
     * ⚠️ 返回的是 `String(out)` 而不是 `out.toString()` —— 后者交回的是数组身份串，
     * 整枚守卫当场变成替空文本作证（本仓 T317 量过的形状）。
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
                            source.startsWith("/*", j) -> {
                                depth++
                                j += 2
                            }

                            source.startsWith("*/", j) -> {
                                depth--
                                j += 2
                            }

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
                            source[j] == quote -> {
                                j++
                                break
                            }

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

    /** 单测的工作目录是模块目录还是仓库根不由这里决定：几种布局都试一遍，全落空就抛 */
    private fun findMainJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(6) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { File(dir, it) }
                .firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }

    private companion object {
        const val EDITOR_SCREEN = "com/buaa/schedule/ui/editor/CourseEditorScreen.kt"
        const val EDITOR_GATE = "com/buaa/schedule/ui/editor/EditorFieldGate.kt"
        const val FILE_EDITOR = "ui/editor/CourseEditorScreen.kt"
        const val FILE_GATE = "ui/editor/EditorFieldGate.kt"

        // ── ① 题眼 ──
        const val GATE_ARG = "redFields = fields.redFields"
        const val GATE_CALL_PREFIX = "editorCanSave(weeks = weeks, periods = periods, saving = "
        const val GATE_DEFINITION = "internal fun editorCanSave"

        // ── ② 红账消费 ──
        const val IS_RED_PREFIX = "fields.isRed(EditorRedField."
        const val CREDIT_NUMBER_USE = "normalizeCredit(fields.creditNumber)"
        val ENUM_NAMES = listOf("StartSection", "EndSection", "ExtraPeriods", "Weeks", "Credit", "Advance")
        val OLD_INLINE_FLAG_NAMES = listOf(
            "startSectionInvalid",
            "endSectionInvalid",
            "extraPeriodsInvalid",
            "weeksInvalid",
            "creditInvalid",
            "advanceInvalid",
            "sectionOrderReversed",
        )

        // ── ③ 横幅 ──
        const val BANNER_ANCHOR = "if (fields.anyRed) {"
        const val OLD_BANNER_CONDITION = "periods.isEmpty() || weeks.isEmpty()"
        const val BANNER_COPY = "还有字段未通过校验，请检查标红的输入框"

        // ── ④ 内核 ──
        const val GATE_RED_PARAM = "redFields: Set<EditorRedField> = emptySet(),"
        const val RED_SET_BUILD = ".filter { it.second }.map { it.first }.toSet()"
        const val VERDICT_COPY = "fields.copy("

        // ── ⑤ 邻居锚点（本卡只添尾巴） ──
        const val SAVE_IN_FLIGHT_UNION = "saving = saving || saveInFlight"
        const val SAVE_IN_FLIGHT_PARAM = "saveInFlight: Boolean,"
    }
}
