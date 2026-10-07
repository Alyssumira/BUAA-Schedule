package com.buaa.schedule.ui.home

import com.buaa.schedule.domain.schedule.CourseConflictResolution
import com.buaa.schedule.ui.stats.conflictGroupTitle
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T250③（#457 与 #458 那一族措辞/尺寸）：钉「冲突向导正文里的间距/高度**按在册 token 取**，裸 dp
 * 字面量只许减不许增」与「行头那一句周次走**在册折段内核**，不许再自己裸串」——
 * 纯源码核对 + 真调用（JVM，无 Robolectric、无设备、不读时钟）。
 *
 * 台账 #457 记的是本文件里两枚手写 dp：`Modifier.height(360.dp)` 那一枚（全树弹窗正文高度唯一一枚
 * 裸 dp）与明细行那句 `padding(top = 2.dp)`（与在册 token `spaceMicro = 2.dp` **同值不同源**）。
 * 本卡只收了后者（换 token，零像素变化）；前者**没动**，理由写在下面第 ③ 档的登记里：
 * 在册那一档 `dialogListMaxHeight` 是 240.dp，换上去就是 -120.dp 的可见高度改动，而复量归编排者；
 * 另立一档要改 `core/designsystem/DesignTokens.kt`，越出本卡 §4 的文件界。
 *
 * 台账 #458 记的是同一行话里的两种口径：明细行的节次一直是折好的「第1-2,9-10节」（`periodLabelOf`），
 * 行头的周次却直接 `group.weeks.joinToString(",")` 裸串 —— 同一组冲突在统计页冲突卡念 `1-8、10`、
 * 在向导念 `1,2,3,4,5,6,7,8,10`。收法：折段走 [CourseWeekSpans.spansOf]（那张「周次覆盖」图与
 * 统计页冲突卡共用的同一件内核），显示侧不再自己折。
 *
 * 钉法（七档，两侧都有格子）：
 * 1. 明细行那句间距逐字取 `DesignTokens.spaceMicro`，而 `padding(top = 2.dp)` 0 处；
 * 2. 裸 dp 棘轮：本文件里 `\d+(\.\d+)?\.dp` 只许 **1 枚**，且逐枚点名 行号 + 原文（只许减不许增）；
 * 3. 那一枚在册例外逐字登记成 `modifier = Modifier.height(360.dp),`：它要是被换成别的数（= 有人改了
 *    可见高度却没换 token），本格与第 ② 档一起红；
 * 4. 靶子：同一把尺喂一枚"多长出一枚裸 dp"的片段，必须量到 2 枚 —— 证明第 ②③ 档不是按 `360.dp`
 *    这串字面量匹配的（换个数就绕过去了，那正是本卡②档堵掉的那类洞）；
 * 5. 行头那句逐字取 `conflictGroupHeaderLabel(group.dayOfWeek, group.weeks)`，`group.weeks.joinToString(`
 *    0 处，而那枚内核体内 `CourseWeekSpans.spansOf(` 恰好一枚（读形状 + 签名两把尺）；
 * 6. 真调用表驱动：整段/断周/乱序重复/脏星期序号/空周次各档念成什么；
 * 7. 与统计页冲突卡**同串**：同一组冲突在两页那句行头逐字相等（分家就是 #458 复发）。
 */
class ConflictWizardCopyTokenWiringGuardTest {

    /** ⑥ 那一档表驱动的一行：星期序号 + 周次 → 行头那一句应当念成的样子 */
    private class Row(val label: String, val dayOfWeek: Int, val weeks: List<Int>, val want: String)

    // ---- ① 明细行那句间距走在册 token ----

    @Test
    fun `明细行那句行距取 spaceMicro 而裸 2dp 一处不许多`() {
        val code = blankComments(source(WIZARD))
        assertEquals(
            "$WIZARD 里明细行那句间距必须逐字是 `$SPACING_TOKEN_USE`（spaceMicro 本就是 2.dp，" +
                "同值两源就是 #457 那一格）——实到 " + occurrences(code, SPACING_TOKEN_USE) + " 处：",
            1,
            occurrences(code, SPACING_TOKEN_USE),
        )
        assertEquals(
            "$WIZARD 里 `$SPACING_LITERAL` 必须 0 处（实到 " + occurrences(code, SPACING_LITERAL) +
                " 处）：换回裸字面量 = 又一处能跟 token 各改各的地方",
            0,
            occurrences(code, SPACING_LITERAL),
        )
    }

    // ---- ②③ 裸 dp 棘轮：只许减不许增，那一枚在册例外逐字登记 ----

    @Test
    fun `本文件裸dp字面量只许剩下册登记的那一枚`() {
        val raw = source(WIZARD)
        val code = blankComments(raw)
        val found = bareDpLiterals(code)
        assertEquals(
            "$WIZARD 里裸 dp 字面量（`\\d+(\\.\\d+)?\\.dp`，注释除外）必须**恰好一枚**，实到 " +
                found.size + " 枚：\n" + found.joinToString("\n") {
                    "  L" + lineOf(raw, it.second) + "：「" + lineText(raw, it.second) + "」"
                } +
                "\n判据是只许减不许增：#457 收掉明细行那一枚之后只剩「弹窗正文高度」那一枚在册例外；" +
                "谁再长出一枚，就要连同第 ③ 档那句在册例外一起重判（在册 token 里有没有对应那一档）。",
            1,
            found.size,
        )
        val offset = found.first().second
        assertEquals(
            "在册那枚例外必须逐字仍是 `$HEIGHT_LITERAL`（实到 L" + lineOf(raw, offset) +
                "：「" + lineText(raw, offset) + "」）：" +
                "它是台账 #457 **没收的那一半** —— 在册那一档 dialogListMaxHeight 是 240.dp，" +
                "换上去就是 -120.dp 的可见高度改动（复量归编排者），另立一档要动 core/designsystem" +
                "（越出本卡 §4 的文件界）。所以这一格今天登记为「数值未动、来源未归一」，" +
                "不许悄悄换成别的数：换了数就等于在没人复量的情况下改了像素。",
            HEIGHT_LITERAL,
            lineText(raw, offset),
        )
    }

    // ---- ④ 靶子：这把尺不是按 360.dp 那串字面量匹配的 ----

    @Test
    fun `裸dp那把尺按形状量 换个数的新一枚也照样点名`() {
        val target = blankComments(BARE_DP_TARGET)
        val found = bareDpLiterals(target)
        assertEquals(
            "靶子里明明长着两枚裸 dp（24.dp 与 380.dp），尺子只许量到 2 枚 —— 实到 " + found.size +
                " 枚（" + found.map { it.first } + "）：量到 1 枚或 0 枚就说明这把尺退化成了按 " +
                "`360.dp` 那串字面量匹配，那正是本卡②档堵掉的那类洞（换个数就绕过）",
            listOf("24.dp", "380.dp"),
            found.map { it.first },
        )
        assertTrue(
            "尺子必须把每一枚的**数值**端出来（登记与复算都靠它），实到 " + found.map { it.first },
            found.map { it.first }.containsAll(listOf("24.dp", "380.dp")),
        )
    }

    // ---- ⑤ 行头那一句走折段内核（#458：节次折了、周次不许再裸串） ----

    @Test
    fun 行头那句周次走折段内核而不再自己裸串() {
        val code = blankComments(source(WIZARD))
        assertEquals(
            "$WIZARD 里行头那一句必须逐字取 `conflictGroupHeaderLabel(group.dayOfWeek, group.weeks)`" +
                "（恰好一枚；实到 " + occurrences(code, HEADER_CALL) + " 枚）：",
            1,
            occurrences(code, HEADER_CALL),
        )
        assertEquals(
            "$WIZARD 里 `group.weeks.joinToString(` 必须 0 处（实到 " + occurrences(code, RAW_WEEKS_NEEDLE) +
                " 处）：显示侧再自己裸串一次，就是与统计页冲突卡两张嘴（台账 #458 那一格）",
            0,
            occurrences(code, RAW_WEEKS_NEEDLE),
        )
        val helper = functionBody(code, HEADER_SIGNATURE, "conflictGroupHeaderLabel")
        assertEquals(
            "折段那一判必须走 CourseWeekSpans.spansOf（与「周次覆盖」那张图、统计页冲突卡同一件内核；" +
                "实到 " + occurrences(helper, SPANS_OF) + " 枚）：自己再写一遍怎么折段，" +
                "两张图上「第 8 周结束」就会一个折成 1-8、一个念成 1,2,3,4,5,6,7,8",
            1,
            occurrences(helper, SPANS_OF),
        )
        assertTrue(
            "内核签名必须把星期序号与周次当参数收（不许自己去课表里嗅、不许读钟）：实到 " +
                Regex(HEADER_SIGNATURE_SHAPE).containsMatchIn(helper),
            Regex(HEADER_SIGNATURE_SHAPE).containsMatchIn(helper),
        )
    }

    // ---- ⑥ 真调用：行头那一句到底念成什么 ----

    @Test
    fun 行头那一句按在册折段内核念周次() {
        val rows = listOf(
            Row("整段之后断开一周", 3, (1..8).toList() + 10, "周三 · 第 1-8、10 周"),
            Row("单周", 1, listOf(5), "周一 · 第 5 周"),
            Row("全断的周次不许并成一整段", 3, listOf(1, 3, 5), "周三 · 第 1、3、5 周"),
            Row("乱序与重复由内核收拢", 2, listOf(12, 10, 11, 10), "周二 · 第 10-12 周"),
            Row("整学期", 2, (1..18).toList(), "周二 · 第 1-18 周"),
            Row("脏星期序号退回周?（与统计页同一档）", 0, listOf(2), "周? · 第 2 周"),
            Row("脏星期序号 8", 8, listOf(2), "周? · 第 2 周"),
            Row("周次为空那一档不留「第  周」这句悬空的话", 4, emptyList(), "周四"),
        )
        for (row in rows) {
            assertEquals(
                row.label + "：conflictGroupHeaderLabel(" + row.dayOfWeek + ", " + row.weeks + ")",
                row.want,
                conflictGroupHeaderLabel(row.dayOfWeek, row.weeks),
            )
        }
    }

    // ---- ⑦ 两页同串：同一组冲突在向导与统计页冲突卡念同一句话 ----

    @Test
    fun 同一组冲突在向导与统计页冲突卡念同一串行头() {
        val weeks = listOf(1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 13)
        for (day in listOf(1, 3, 7, 0)) {
            val group = CourseConflictResolution.ConflictGroup(
                dayOfWeek = day,
                courses = emptyList(),
                weeks = weeks,
            )
            assertEquals(
                "星期序号 $day 这一档：统计页冲突卡那句与向导行头分家了 —— 台账 #458 记的就是这一格" +
                    "（两处一折一段不折，用户看到的是同一组冲突跨了不同的周）",
                conflictGroupTitle(group),
                conflictGroupHeaderLabel(group.dayOfWeek, group.weeks),
            )
        }
    }

    // ---------------- helpers（刀法照抄 ConflictShiftWeekScopeWiringGuardTest） ----------------

    /** 形状尺：每一枚裸 `<数>.dp`（连同数值与偏移端出来，不按某串字面量匹配） */
    private fun bareDpLiterals(code: String): List<Pair<String, Int>> =
        BARE_DP.findAll(code).map { it.value to it.range.first }.toList()

    /** 从签名那句起按花括号配平取整个函数（含签名自己）；锚点找不到就失败，不静默跳过 */
    private fun functionBody(code: String, signature: String, label: String): String {
        val at = code.indexOf(signature)
        assertTrue("$label 的签名锚点没找到（改名/挪家要回来重钉本守卫）：$signature", at >= 0)
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

    private fun source(relative: String): String = File(findMainJavaDir(), relative).readText()

    private fun occurrences(hay: String, needle: String): Int {
        var count = 0
        var at = hay.indexOf(needle)
        while (at >= 0) {
            count++
            at = hay.indexOf(needle, at + needle.length)
        }
        return count
    }

    private fun lineOf(raw: String, index: Int): Int =
        raw.substring(0, index.coerceAtLeast(0)).count { it == '\n' } + 1

    /** 偏移 → 那一行的原文（去首尾空白）：报错消息点的行与它上面的实到字面要一起端出来 */
    private fun lineText(raw: String, index: Int): String =
        raw.lines().getOrElse(lineOf(raw, index) - 1) { "<越界>" }.trim()

    /** 把 `//` 与 `/* */` 注释抹成空格（字符串保留、长度与换行位置不变）：钉的是接线，不是白话 */
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

    private companion object {
        const val WIZARD = "com/buaa/schedule/ui/home/ConflictWizardDialog.kt"

        const val SPACING_TOKEN_USE = "Modifier.padding(top = DesignTokens.spaceMicro)"
        const val SPACING_LITERAL = "Modifier.padding(top = 2.dp)"
        const val HEIGHT_LITERAL = "modifier = Modifier.height(360.dp),"

        // —— #458：行头那一句周次的取法 ——
        const val HEADER_CALL = "conflictGroupHeaderLabel(group.dayOfWeek, group.weeks)"
        const val RAW_WEEKS_NEEDLE = "group.weeks.joinToString("
        const val SPANS_OF = "CourseWeekSpans.spansOf("
        const val HEADER_SIGNATURE = "internal fun conflictGroupHeaderLabel("
        const val HEADER_SIGNATURE_SHAPE =
            """internal fun conflictGroupHeaderLabel\(\s*dayOfWeek: Int,\s*weeks: List<Int>\s*\)"""

        val BARE_DP = Regex("""(?<![\w.])\d+(?:\.\d+)?\.dp\b""")

        /** 靶子：多长出一枚裸 dp（数值与在册那枚都不同）—— 形状尺必须两枚都点名 */
        val BARE_DP_TARGET = """
            private val ChangelogMaxHeight = 24.dp
            fun body() = Modifier.height(380.dp)
        """.trimIndent()
    }
}
