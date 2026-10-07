package com.buaa.schedule.ui.course

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T207 病灶 A 的源码形状守卫：钉住「课表管理页的门面不再由一枚不存在的节次决定」。
 *
 * 只做源码核对（JVM，无 Robolectric、无设备）。判据按四种钉法取，每一格的消息里都点名自己用的是
 * 哪一种：**逐字节 / 抹注释找锚点 / 数出现次数 / 读形状**（本仓守卫的四种写法）。
 * 行为侧的判据表在 `ui/course/CourseGroupPrimaryFragmentTest`（九格）与既有的
 * `ui/course/CourseGroupingTest`（归并规则）里，本守卫钉的是**形状不回潮**：
 * - 旧那一形 `minByOrNull { it.startPeriod } ?: fragments.first()` 抹注释后必须 0 处；
 * - 门面那格必须真的读内核（`primaryFragmentOf(fragments)` 恰一处，调用点没被绕开）；
 * - 内核的比较键必须是 `firstPeriodOrNull` 而不是 `startPeriod`（读形状）；
 * - **反向格**：`startPeriod` 在列序那一处仍合法存在（探测器不许见 `startPeriod` 就杀），
 *   且 `domain/model/Course.kt` 的 `startPeriod` 本体一字未改（本卡红线）。
 */
class CourseGroupPrimaryFragmentGuardTest {

    // ─────────────── ① 病灶不回潮：旧那一形 0 处（数出现次数 + 抹注释找锚点） ───────────────

    @Test
    fun `旧那一形在管理页抹注释后零处 而靶子仍在注释里`() {
        val raw = source(PAGE)
        val code = blankCommentsKeepingLiterals(raw)

        for (needle in listOf(
            "?: fragments.first()",
            "minByOrNull { it.startPeriod }",
            "minByOrNull",
        )) {
            assertEquals(
                "**钉法 = 数出现次数（抹注释后）**：代码里「$needle」必须 0 处 —— " +
                    "病灶那一格已经换成读内核的 `primaryFragmentOf(fragments)`。\n" +
                    "复算：把 $needle 拿 grep -Fn 打到 app/src/main/java/$PAGE 上数命中行\n" +
                    "红了 = 有人把不可达兜底或「拿 startPeriod 当比较键」那一格写回来了（T207 病灶 A 回潮）",
                0,
                occurrences(code, needle),
            )
        }

        // 靶子：被抹掉的那一段里确实还写着旧原文（防止把整枚 KDoc 删了来骗过上一条）
        assertTrue(
            "病灶原文必须在**注释**里留痕（KDoc 讲的就是改前那一形）。实到 ${occurrences(raw, "?: fragments.first()")} 处 —— " +
                "0 处说明本守卫第 ① 层的 0 是「注释被删干净」而不是「代码改对了」",
            occurrences(raw, "?: fragments.first()") >= 1,
        )
    }

    // ─────────────── ② 调用点真的读内核（逐字节 + 数出现次数） ───────────────

    @Test
    fun `门面那一格逐字读内核 全仓只管理页在用它`() {
        val code = blankCommentsKeepingLiterals(source(PAGE))

        assertEquals(
            "**钉法 = 逐字节 + 数出现次数**：调用点那一行必须逐字在此（换名 / 绕开内核直接排 fragments 都要红）。\n" +
                "复算：grep -Fn 'val primary = primaryFragmentOf(fragments)' app/src/main/java/$PAGE",
            1,
            occurrences(code, "val primary = primaryFragmentOf(fragments)"),
        )
        assertEquals(
            "**钉法 = 逐字节**：内核签名必须逐字在此（改成吃 `List<Course>?` 或返回 `Course?` 就是又挂回一枚兜底）。\n" +
                "复算：grep -Fn 'internal fun primaryFragmentOf(fragments: List<Course>): Course =' app/src/main/java/$PAGE",
            1,
            occurrences(code, "internal fun primaryFragmentOf(fragments: List<Course>): Course ="),
        )

        val readers = allMainSources().filter { occurrences(blankCommentsKeepingLiterals(it.value), "primaryFragmentOf") > 0 }
            .entries
            .sortedBy { it.key }
            .joinToString(separator = "") { "  ${it.key}: ${occurrences(blankCommentsKeepingLiterals(it.value), "primaryFragmentOf")} 处\n" }
        assertEquals(
            "**钉法 = 数出现次数（全 main 册子）**：`primaryFragmentOf` 全仓只许长在管理页一枚文件里，" +
                "恰两枚落点 = 1 定义 + 1 调用点。多一枚 = 第二处门面判据又长出来了（同一事实两本口径）；" +
                "少一枚 = 门面那格不再读内核。\n实到册子：\n$readers",
            "  $PAGE: 2 处\n",
            readers,
        )
    }

    // ─────────────── ③ 内核的比较键（读形状 + 数出现次数） ───────────────

    @Test
    fun `内核比较键是 firstPeriodOrNull 而不是 startPeriod`() {
        val code = blankCommentsKeepingLiterals(source(PAGE))
        val order = balancedBlock(code, PRIMARY_ORDER_ANCHOR, "primaryFragmentOrder")

        assertEquals(
            "**钉法 = 数出现次数（读形状块内）**：次序里读 [Course.firstPeriodOrNull] 必须恰好两枚" +
                "（左碎片一枚、右碎片一枚），换成 `startPeriod` 就回到「空节次当第 1 节」那一格。\n" +
                "实到 ${occurrences(order, "firstPeriodOrNull")} 处",
            2,
            occurrences(order, "firstPeriodOrNull"),
        )
        assertEquals(
            "**钉法 = 读形状**：次序块里一枚 `startPeriod` 都不许有（它空表兜底成 1，正是病灶的键）",
            0,
            occurrences(order, "startPeriod"),
        )
        for (needle in listOf("dayOfWeek", ".id")) {
            assertTrue(
                "**钉法 = 读形状**：并列档必须有「$needle」这一手（少一档就退回列表序 = 数据库返回序）",
                order.contains(needle),
            )
        }
        // 上一格只数得到"读了两枚 firstPeriodOrNull"，数不到**读法**：`?: 1` / `?: Int.MAX_VALUE`
        // 这类给空节次补一个假节次的 Elvis 会让探测器看起来一字未改，却把病灶原样装回来（变异自证补的针）
        assertEquals(
            "**钉法 = 读形状**：次序块里一枚 Elvis 兜底都不许有 —— 键必须直呼 firstPeriodOrNull，" +
                "补假节次（`?: 1`）与顶 MAX（`?: Int.MAX_VALUE`）都是「没节次也当有节次」那一族。\n" +
                "实到 ${occurrences(order, "?:")} 处",
            0,
            occurrences(order, "?:"),
        )

        assertEquals(
            "**钉法 = 数出现次数**：整枚内核（primaryFragmentOf + primaryFragmentOrder）里 `firstPeriodOrNull` 共两枚、" +
                "`startPeriod` 共 0 枚 ⇒ 管理页今天对 `startPeriod` 的引用只许剩**列序**那一处（见第 ④ 层）。\n" +
                "实到 firstPeriodOrNull = ${occurrences(code, "firstPeriodOrNull")} 处",
            2,
            occurrences(code, "firstPeriodOrNull"),
        )
    }

    // ─────────────── ④ 反向格：合法用法不许被探测器误伤 ───────────────

    @Test
    fun `列序那把尺照旧用 startPeriod 本卡没有顺手改它`() {
        val code = blankCommentsKeepingLiterals(source(PAGE))

        assertEquals(
            "**钉法 = 逐字节**：卡面列序（星期 → `startPeriod`）逐字仍在 —— `Course.startPeriod` 的 KDoc 明说" +
                "「全仓十来处 sortedBy { it.startPeriod } 靠的就是这个语义：没有节次的课排到最前面，是次序问题，不出错」，" +
                "本卡只动门面那一格，不许顺手把这把尺也换了（换了会挪动用户看惯的行序）。\n" +
                "复算：grep -Fn 'compareBy({ it.dayOfWeek }, { it.startPeriod })' app/src/main/java/$PAGE",
            1,
            occurrences(code, "compareBy({ it.dayOfWeek }, { it.startPeriod })"),
        )
        assertEquals(
            "**钉法 = 数出现次数**：管理页今天对 `startPeriod` 的引用恰一枚（就是上面那把列序尺）。" +
                "读到 0 = 列序被顺手改了；读到 2 = 门面那一格又回去读兜底键了。实到 " +
                occurrences(code, "startPeriod") + " 枚",
            1,
            occurrences(code, "startPeriod"),
        )
    }

    // ─────────────── ⑤ 红线：Course 本体一字未改（逐字节） ───────────────

    @Test
    fun `domain 那枚 startPeriod 与 firstPeriodOrNull 本体一字未动`() {
        val code = blankCommentsKeepingLiterals(source(COURSE_MODEL))

        for (verbatim in listOf(
            "val startPeriod: Int get() = periods.minOrNull() ?: 1",
            "val firstPeriodOrNull: Int? get() = periods.minOrNull()",
        )) {
            assertEquals(
                "**钉法 = 逐字节**：本卡红线是「只改调用点」，`Course.kt` 这两枚薄壳必须逐字原样。\n" +
                    "复算：grep -Fn '$verbatim' app/src/main/java/$COURSE_MODEL\n" +
                    "红了 = 有人动了本体语义（全仓十来处 sortedBy { it.startPeriod } 会被连环改动），" +
                    "那要走另一张卡、另一套账，不是本卡这一格",
                1,
                occurrences(code, verbatim),
            )
        }
    }

    // ---------------- helpers（与本仓守卫同一套写法） ----------------

    private fun source(relative: String): String = File(findMainJavaDir(), relative).readText()

    private fun allMainSources(): Map<String, String> {
        val root = findMainJavaDir()
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { root.toPath().relativize(it.toPath()).toString().replace(File.separatorChar, '/') to it.readText() }
            .toMap()
    }

    /** 从锚点起按花括号配平取整块（含锚点自己）；锚点找不到就抛，不允许静默空块 */
    private fun balancedBlock(code: String, anchor: String, label: String): String {
        val at = code.indexOf(anchor)
        assertTrue("$label 的锚点没找到（改名/挪家要回来重钉本守卫）：「$anchor」", at >= 0)
        val open = code.indexOf('{', at)
        assertTrue("$label 找不到块的开括号", open >= 0)
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

    private fun occurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var at = haystack.indexOf(needle)
        while (at >= 0) {
            count++
            at = haystack.indexOf(needle, at + needle.length)
        }
        return count
    }

    /** 只抹注释、保留字符串字面量，长度与换行位置不变：钉的是代码，不是白话 */
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
        const val PAGE = "com/buaa/schedule/ui/course/CourseManagementScreen.kt"
        const val COURSE_MODEL = "com/buaa/schedule/domain/model/Course.kt"
        const val PRIMARY_ORDER_ANCHOR = "private val primaryFragmentOrder: Comparator<Course> = Comparator {"
    }
}
