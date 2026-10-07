package com.buaa.schedule.ui

import com.buaa.schedule.domain.schedule.IcsExportDecision
import com.buaa.schedule.domain.schedule.IcsExportGate
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T252⑤：钉「ICS 零日程时把用户选中的文件截成 0 字节」这一族形状**不会再回来**，
 * 钉法是**读形状**（花括号配平取块 + 索引顺序），辅以**数出现次数（抹注释）**钉病灶行与档位枚数、
 * **逐字节比对**钉三句用户回执（卡面 §4 要求点名：主尺是第一枚，另两种是同一文件里的辅钉）。
 *
 * ## 为什么主尺必须是读形状而不是逐字节
 *
 * 本卡的病灶不是某一行写错，而是**三行的先后**：算内容 → 开流（截断）→ 报数。
 * 逐字节比对只能钉住"这一行今天长这样"，把开流那一手往上挪两行、判空留在下面，
 * 每一行都逐字节对得上而危险形状整块回来 ⇒ 只有索引顺序能钉它。
 * 于是核心那格判的是：**体内 `IcsExportGate.decide(` 的索引 < 体内 `writeTextToUri(` 的索引**，
 * 并且 `writeTextToUri(` 那一枚**落在 `is IcsExportDecision.WriteTruncating -> {` 的花括号块里**。
 *
 * ## 反套套逻辑（三处，不许拿"grep 没匹配到"当"全绿"）
 *
 * 1. 病灶行 `writeTextToUri(uriString, result.text)`：抹注释后整份 VM **0 处**，而含注释原文里
 *    **恰好 1 处**（VM 的 KDoc 逐字引用改前那一手）⇒ 那个 0 是"代码里真的没有"，不是"注释被删干净"。
 * 2. 档位枚数：判据文件里 ` : IcsExportDecision {` 恰好 **4** 枚，运行时那格另有反证 ——
 *    表驱动 [com.buaa.schedule.domain.schedule.IcsExportGateTest] 必须把四档**各跑出一枚**。
 * 3. 落点枚数：VM 体内四个档位锚点各 **1** 枚、`showMessage(` **3** 枚；有人把某一档并进 `else`
 *    或整档删掉，这两枚数当场分家（不是"我写的行还在"那种恒真）。
 *
 * ## 两侧的红臂（本守卫自己也得是活的）
 *
 * - **朝宽**：把体内那一手改回 `writeTextToUri(uriString, result.text)`（判空被摘掉）⇒
 *   顺序格、`writeTextToUri` 落在那一支花括号里的格、病灶行 0 处格**同时红**；
 * - **朝窄**：在 `IcsExportGate.decide` 里把 `eventCount <= 0` 换成 `icsText.isBlank()`（0 条但有骨架
 *   也照写）⇒ 判据文件那格 `icsText.isBlank()` 的**两次**读数变 1 次当场红；
 *   换掉的是 `eventCount <= 0` 那一枚则 0 处红。
 *
 * 只做**源码核对**（JVM，无 Robolectric、无设备、不读时钟）。输入面：本守卫读 `src/main/java`
 * 下的工作树文本，已由 `app/build.gradle.kts` 里 `guardReadWorkingTreeFiles` 整体声明成
 * `testDebugUnitTest` 的输入。⚠️ 未装机：`openOutputStream` 的截断行为与 Snackbar 的实屏呈现
 * 都没有设备读数（本卡零 adb）。
 */
class IcsExportGateWiringGuardTest {

    private companion object {
        const val GATE = "com/buaa/schedule/domain/schedule/IcsExportGate.kt"
        const val COPY = "com/buaa/schedule/ui/settings/IcsExportCopy.kt"
        const val VIEW_MODEL = "com/buaa/schedule/ui/ScheduleViewModel.kt"

        const val EXPORT_CALL = "fun exportIcsTo(uriString: String) {"
        const val DECIDE_ANCHOR = "fun decide("
        const val DECIDE_SIGNATURE = "): IcsExportDecision = when {"

        const val GATE_DECIDE_CALL = "IcsExportGate.decide("
        const val OPEN_STREAM = "writeTextToUri("
        const val WRITE_BRANCH = "is IcsExportDecision.WriteTruncating -> {"

        /** 改前那一手（病灶行）：VM 的 KDoc 里逐字留着，代码里必须一枚都不许有 */
        const val DANGEROUS_LINE = "writeTextToUri(uriString, result.text)"

        const val ARM_NO_FILE = "IcsExportDecision.NoFileSelected ->"
        const val ARM_EMPTY = "is IcsExportDecision.RefuseEmpty ->"
        const val ARM_INCONSISTENT = "is IcsExportDecision.RefuseInconsistent ->"
        const val MESSAGE_CALL = "showMessage("

        /** 判据三条 + 顺序一条：少任何一枚都是把两档折成一档 */
        const val RULE_NOT_SELECTED = "!fileSelected -> IcsExportDecision.NoFileSelected"
        const val RULE_ZERO_EVENTS = "eventCount <= 0 -> IcsExportDecision.RefuseEmpty(skippedOccurrences)"
        const val RULE_BLANK_TEXT = "icsText.isBlank() -> IcsExportDecision.RefuseInconsistent(eventCount)"
        const val RULE_WRITE = "else -> IcsExportDecision.WriteTruncating(icsText, eventCount, skippedOccurrences)"

        /** 档位枚数与落盘模式枚数 */
        const val TIER_SUPER = " : IcsExportDecision {"
        const val MUTATION_TRUNCATE = "get() = IcsTargetFileMutation.TRUNCATE_AND_WRITE"
        const val MUTATION_UNTOUCHED = "get() = IcsTargetFileMutation.LEAVE_UNTOUCHED"

        /** 文案三枚签名（逐字节）与两支各自的成功句 */
        const val COPY_WRITTEN = "internal fun icsExportWrittenMessage(decision: IcsExportDecision.WriteTruncating): String ="
        const val COPY_REFUSED = "internal fun icsExportRefusedMessage(decision: IcsExportDecision.RefuseEmpty): String ="
        const val COPY_INCONSISTENT =
            "internal fun icsExportInconsistentMessage(decision: IcsExportDecision.RefuseInconsistent): String ="
        const val SUCCESS_TAIL_BRANCH =
            "\"已导出 ${'$'}{decision.eventCount} 个日程，可在系统日历中导入（${'$'}{decision.skippedOccurrences} 个课次因节次时间缺失被跳过）\""
        const val SUCCESS_PLAIN_BRANCH = "\"已导出 ${'$'}{decision.eventCount} 个日程，可在系统日历中导入\""
        const val REFUSAL_HEAD = "没有可导出的日程"
        const val HONEST_TAIL = "未写出文件"

        /** 纯度：本仓口径 —— 判据文件里这些读取一枚都不许有 */
        val FORBIDDEN_READS = listOf(
            "Instant.now",
            "System.currentTimeMillis",
            "LocalDate.now",
            "LocalDateTime",
            "TimeZone",
            "ZoneId",
            "Build.VERSION",
            "Calendar.getInstance",
        )
    }

    // ─────────────── ① 主尺（读形状）：判空排在开流之前 ───────────────

    @Test
    fun `开流那一手排在判据之后 且只在写出那一档里`() {
        val code = blankComments(source(VIEW_MODEL))
        val body = balancedBlock(code, EXPORT_CALL, "ScheduleViewModel.exportIcsTo")

        val decideAt = body.indexOf(GATE_DECIDE_CALL)
        val openAt = body.indexOf(OPEN_STREAM)
        assertTrue(
            "exportIcsTo 体内必须找到判据那一手（「$GATE_DECIDE_CALL」）：找不到就是整条链换载体了，" +
                "本守卫的锚点要跟着重钉",
            decideAt >= 0,
        )
        assertTrue(
            "exportIcsTo 体内必须还有一处开流（「$OPEN_STREAM」）：一枚都没有就是导出这条路整个没了" +
                "（那不是本卡裁的方向）",
            openAt >= 0,
        )
        assertTrue(
            "**判据必须排在开流之前**：decide 的索引 $decideAt、开流的索引 $openAt。" +
                "开流挪到判据前面 = 文件已经先被截断、才发现这一趟没东西可写 —— 那正是 #309 的形状" +
                "（三行逐字节都对得上，错的先后）",
            decideAt < openAt,
        )

        val writeBranch = balancedBlock(code, WRITE_BRANCH, "exportIcsTo 里的 WriteTruncating 那一支")
        assertEquals(
            "整枚 exportIcsTo 体内「$OPEN_STREAM」恰好 **1** 枚，而它必须整个长在 WriteTruncating 那一支里" +
                "（那一支里读到 " + occurrences(writeBranch, OPEN_STREAM) + " 枚）：" +
                "两支都有 = 不落盘的档位也在开用户的文件；一支都没有 = 写出那一档被顺手摘了路",
            1,
            occurrences(body, OPEN_STREAM),
        )
        assertEquals("开流那一手必须发生在 WriteTruncating 支内", 1, occurrences(writeBranch, OPEN_STREAM))
        assertEquals(
            "写出去的正文明必须是判据交回的那一枚（「decision.text」），实到 " + occurrences(writeBranch, "decision.text") +
                " 枚：读回 result.text = 判据与写盘两头各拿一份真相",
            1,
            occurrences(writeBranch, "decision.text"),
        )

        assertEquals(
            "改前那一手「$DANGEROUS_LINE」在代码里必须 **0** 处（它是本卡病灶：把没有内容折成截断）。" +
                "\n  复算：把 " + VIEW_MODEL + " 的 // 与块注释抹成空格后数这一行的枚数" +
                "\n  实到枚数：" + occurrences(code, DANGEROUS_LINE),
            0,
            occurrences(code, DANGEROUS_LINE),
        )
        // 反套套逻辑：含注释的原文里它恰好 1 处（VM 的 KDoc 逐字引用着改前那一手）
        assertEquals(
            "含注释的原文里「$DANGEROUS_LINE」必须恰好 **1** 处 —— 那是 KDoc 里的改前取证。" +
                "读到 0 = 取证被搬走，上面那个 0 就成了「注释删干净」的假绿；读到 >1 = 病灶回潮。" +
                "实到：" + occurrences(source(VIEW_MODEL), DANGEROUS_LINE),
            1,
            occurrences(source(VIEW_MODEL), DANGEROUS_LINE),
        )
    }

    // ─────────────── ② 四档在调用点各有落点（不许并进 else） ───────────────

    @Test
    fun `四档各有落点 调用点不兜底`() {
        val code = blankComments(source(VIEW_MODEL))
        val body = balancedBlock(code, EXPORT_CALL, "ScheduleViewModel.exportIcsTo")
        for (anchor in listOf(ARM_NO_FILE, ARM_EMPTY, ARM_INCONSISTENT, WRITE_BRANCH)) {
            assertEquals(
                "exportIcsTo 体内档位锚点「$anchor」必须恰好一枚（实到 " + occurrences(body, anchor) + "）。" +
                    "少一枚 = 那一档在屏幕上没有自己的句子；多一枚 = 同一档被两处分别处置，" +
                    "用户读到的取决于走哪条路",
                1,
                occurrences(body, anchor),
            )
        }
        assertEquals(
            "体内「$MESSAGE_CALL」恰好 4 枚：无学期/无课程那一档 + 不落盘的两档各一句实话 + " +
                "写出那一档的成功/失败回执。实到 " + occurrences(body, MESSAGE_CALL) + " 枚 —— " +
                "涨到 5 枚就是有人给取消那一档编了句假失败；掉下来就是某一档没了自己的句子",
            4,
            occurrences(body, MESSAGE_CALL),
        )
        assertEquals(
            "句子只有一处真相：VM 体内「$SUCCESS_TAIL_BRANCH」那半句的开头「已导出」必须 0 处（都在 $COPY）。" +
                "搬回 VM = 把它移出 JVM 单测可达的范围，也是 T181② 那句破括号的原产地",
            0,
            occurrences(body, "已导出"),
        )
        for (anchor in listOf("icsExportWrittenMessage(decision)", "icsExportRefusedMessage(decision)", "icsExportInconsistentMessage(decision)")) {
            assertEquals(
                "文案函数在体内恰好一枚调用点「$anchor」（实到 " + occurrences(body, anchor) + " 枚）",
                1,
                occurrences(body, anchor),
            )
        }
    }

    // ─────────────── ③ 判据内核：三条规则 + 顺序 + 分支枚数 ───────────────

    @Test
    fun `判据内核三条规则一枚都不许并掉`() {
        val code = blankComments(source(GATE))
        val body = balancedBlock(code, DECIDE_ANCHOR, "IcsExportGate.decide")
        assertEquals("decide 的签名逐字节一枚在场", 1, occurrences(code, DECIDE_SIGNATURE))
        for (rule in listOf(RULE_NOT_SELECTED, RULE_ZERO_EVENTS, RULE_BLANK_TEXT, RULE_WRITE)) {
            assertEquals(
                "判据「$rule」必须恰好一枚（实到 " + occurrences(body, rule) + "）。" +
                    "少任何一枚就有两档要借同一句用户回执说话 —— 屏幕上「没东西可写」与「写出去了却报 0」" +
                    "就会念成同一句话",
                1,
                occurrences(body, rule),
            )
        }
        assertTrue(
            "**顺序钉**：「条数≤0」必须排在「正文空白」之前（实到索引 " +
                "${body.indexOf(RULE_ZERO_EVENTS)} 与 ${body.indexOf(RULE_BLANK_TEXT)}，" +
                "前者必须更小且都非负）。颠倒过来 = 导出器哪天交回合法空骨架（0 条却有文本），" +
                "就又开始截用户的文件 —— 第 ⓪ 问裁的是甲档，这一枚就是甲档的门",
            body.indexOf(RULE_ZERO_EVENTS) in 0 until body.indexOf(RULE_BLANK_TEXT),
        )
        assertEquals(
            "IcsExportDecision 的分支恰好 4 枚（实到 " + occurrences(code, TIER_SUPER) + "）：" +
                "3 枚 = 有一档被折掉（Boolean 那一折），5 枚 = 先补文案与表驱动格子再来涨",
            4,
            occurrences(code, TIER_SUPER),
        )
        assertEquals(
            "整个判据族里只有 WriteTruncating 一档交截断写（实到 " + occurrences(code, MUTATION_TRUNCATE) + " 枚）",
            1,
            occurrences(code, MUTATION_TRUNCATE),
        )
        assertEquals(
            "另外三档各交 LEAVE_UNTOUCHED（实到 " + occurrences(code, MUTATION_UNTOUCHED) + " 枚）——" +
                "少一枚就是有一档「不落盘」其实偷偷开了流",
            3,
            occurrences(code, MUTATION_UNTOUCHED),
        )
    }

    @Test
    fun `判据内核零 android 也不读钟读区读权限`() {
        val raw = source(GATE)
        val offenders = raw.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("import ") }
            .filter { it.contains("android.") || it.contains("androidx.") }
            .toList()
        assertEquals("$GATE 必须零 android / androidx import。实到：" + offenders, emptyList<String>(), offenders)

        val code = blankComments(raw)
        for (needle in FORBIDDEN_READS) {
            assertEquals(
                "判据文件里「$needle」必须 0 处：读了它，「零日程该不该动文件」这一判就不再是纯函数 —— " +
                    "同一次导出的两次点击可能给出不同结论（本仓明文口径：这些留在调用点当参数传进来）。" +
                    "\n  复算：把 $GATE 的注释抹成空格后数「$needle」\n  实到枚数：" + occurrences(code, needle),
                0,
                occurrences(code, needle),
            )
        }
        // 反套套逻辑：KDoc 里逐字承诺了这条口径（读到 0 说明承诺被搬走、上面那串 0 就没人负责）
        assertEquals(
            "KDoc 里那句「不读时钟」的承诺必须留痕（含注释原文恰好 1 处）。实到：" +
                occurrences(raw, "不读时钟、不读时区、不读权限、不读设备度量"),
            1,
            occurrences(raw, "不读时钟、不读时区、不读权限、不读设备度量"),
        )
    }

    // ─────────────── ④ 文案：三句各归各档（逐字节比对） ───────────────

    @Test
    fun `三句回执各归各档`() {
        val code = blankComments(source(COPY))
        for (signature in listOf(COPY_WRITTEN, COPY_REFUSED, COPY_INCONSISTENT)) {
            assertEquals(
                "文案函数签名逐字节一枚在场（恰好一枚）：「$signature」。" +
                    "改签名 = 那一档的句子不再由档位类型决定，档位与文案两头就会分家",
                1,
                occurrences(code, signature),
            )
        }
        assertEquals(
            "成功句两支各自一条**完整字面量**（拼接链是 T181② 那枚破括号的产地）：" +
                "带跳过尾巴那枚与不带那枚各 1（实到 " + occurrences(code, SUCCESS_TAIL_BRANCH) + " 与 " +
                occurrences(code, SUCCESS_PLAIN_BRANCH) + "）",
            2,
            occurrences(code, SUCCESS_TAIL_BRANCH) + occurrences(code, SUCCESS_PLAIN_BRANCH),
        )
        assertEquals(
            "「$REFUSAL_HEAD」那句必须恰好 2 枚（有跳过 / 无跳过两支）——那是「零日程」唯一的说法",
            2,
            occurrences(code, REFUSAL_HEAD),
        )
        assertEquals(
            "「$HONEST_TAIL」恰好 3 枚：不落盘的两档各两支…（有跳过/无跳过 + 打架那一档一支）。" +
                "实到 " + occurrences(code, HONEST_TAIL) + " 枚。少一枚 = 有一档不动盘却不说这一手，" +
                "用户会以为屏幕后头那份文件已经换了内容",
            3,
            occurrences(code, HONEST_TAIL),
        )
        // 反套套逻辑：成功句那三个字只许长在写出那一档的函数里，整份文件恰好 2 枚
        assertEquals(
            "「已导出」在文案文件里恰好 2 枚（成功那一档的两支）。实到 " + occurrences(code, "已导出") +
                " 枚 —— 涨到 3 枚就是有人给「没写出去」那一档又编了一句成功回执（#309 后半句的形状）",
            2,
            occurrences(code, "已导出"),
        )
    }

    // ─────────────── ⑤ 运行时反证：内核与文案函数都是活的 ───────────────

    @Test
    fun `运行时那一侧也认得四档`() {
        // 这一格不吃 grep：真跑一遍判据，确认它今天仍交得出四枚不同结论（守卫自己不是死代码）
        val decisions = listOf(
            IcsExportGate.decide(12, 0, "BEGIN:VCALENDAR", true),
            IcsExportGate.decide(0, 0, "", true),
            IcsExportGate.decide(3, 0, "", true),
            IcsExportGate.decide(12, 0, "BEGIN:VCALENDAR", false),
        )
        assertEquals(
            "四枚输入必须交出四枚不同档位（实到 " + decisions.map { it.javaClass.simpleName } + "）",
            4,
            decisions.map { it.javaClass.simpleName }.distinct().size,
        )
        assertEquals(
            "表驱动里 0 条那一格今天必须仍是不落盘那一档（实到 ${decisions[1].javaClass.simpleName}）",
            IcsExportDecision.RefuseEmpty::class.simpleName,
            decisions[1].javaClass.simpleName,
        )
    }

    // ---------------- helpers（与 WakeUpExportDropShapeGuardTest 同写法） ----------------

    private fun source(relative: String): String = File(findMainJavaDir(), relative).readText()

    private fun occurrences(hay: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var at = hay.indexOf(needle)
        while (at >= 0) {
            count++
            at = hay.indexOf(needle, at + needle.length)
        }
        return count
    }

    /** 从锚点那一句起按花括号配平取整块（含锚点自己）；锚点找不到就抛 */
    private fun balancedBlock(code: String, anchor: String, label: String): String {
        val at = code.indexOf(anchor)
        assertTrue("$label 的锚点没找到（改名/挪家要回来重钉本守卫）：「$anchor」", at >= 0)
        val open = code.indexOf('{', at)
        assertTrue("$label 找不到函数体的开括号", open >= 0)
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(at, i + 1)
                }
            }
        }
        throw IllegalStateException("$label 的花括号没配平")
    }

    /** 把 `//` 与块注释抹成空格（字符串保留、长度与换行位置不变）：钉的是接线，不是白话 */
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
