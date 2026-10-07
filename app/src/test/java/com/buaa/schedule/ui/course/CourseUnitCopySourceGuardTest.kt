package com.buaa.schedule.ui.course

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T224 守卫第二把：**读形状 / 逐字节** —— 钉住这几句文案各吃哪一枚**具名读数**，
 * 报错消息点名「哪一句又换回了 `.size`」。
 *
 * ## 为什么这一把不数「门」这个字，而数取数落点
 *
 * 「N 门课」这句话本身永远不会错，错的是那个 N 从哪儿来。本卡三处落点（#308）的共同形状就是：
 * 句子写的是门，数拿的是 `state.courses.size`（排课片段数）。所以这一把尺按**句**摆格子，
 * 每格钉三件事：
 * 1. 那句话的宿主调用逐字在此（换了写法 = 红）；
 * 2. 它吃的具名读数逐字在此（`courseCounts.courseCount` / `courseCounts.fragmentCount` /
 *    `groups.size`（其上游是 `SemesterStats.creditsByCourse`）/ `counts[i]`）；
 * 3. 病灶那一形（拿 `.size` 顶门数、把段数念成门）在代码里 0 处，而**含注释的原文里必须还有痕迹**
 *    —— 那枚 0 才是"改对了"，不是"注释被删了"。
 *
 * 表驱动：一行一句，`copyName` 直接进失败消息，红了当场就知道是哪一句漂了。
 *
 * 只做**源码核对**（JVM，无 Robolectric、无设备、不读时钟）。
 * ⚠️ 「两句同屏」要装机才判得实（本卡零 adb）：这一把钉的是取数与措辞同源，屏幕读数归编排者。
 */
class CourseUnitCopySourceGuardTest {

    private class Row(
        /** 哪一句（进失败消息，红了当场认得出是谁） */
        val copyName: String,
        /** 相对 app/src/main/java 的文件 */
        val path: String,
        /** 宿主调用 / 文案本体 / 具名读数：每枚逐字，期望枚数一并给出 */
        val verbatim: List<Pair<String, Int>>,
        /** 病灶那一形：代码里必须 0 处 */
        val lesions: List<String>,
        /** 落在同一行上的取数锚点（`.size` 就在那一行上找） */
        val callAnchor: String,
        /**
         * 那一行上允许的 `.size` 枚数。今天只有一枚例外：课表管理页的 `groups.size` ——
         * 它合法是因为同一行册子里钉着 `groups` 的上游是 `SemesterStats.creditsByCourse(filtered)`
         * （归并只发生一次，那枚 size 数的就是门）。其余四句的 N 都必须来自具名读数，一律 0。
         */
        val expectSizeInCallLine: Int = 0,
    )

    private val rows = listOf(
        Row(
            copyName = "设置页「课表管理」那一行（当前 N 门课 · M 段排课）",
            path = SETTINGS_SCREEN,
            verbatim = listOf(
                "summary = courseManagementRowSummary(" to 1,
                "courseCount = courseCounts.courseCount," to 1,
                "fragmentCount = courseCounts.fragmentCount," to 1,
                // 取数落点：整页只从这一本账取，而那本账就是统计页那一枚
                "val courseCounts = remember(state.courses, state.semester, state.timeSlots) {" to 1,
                "SemesterStats.summarize(state.courses, state.semester, state.timeSlots)" to 1,
                "\"查看、编辑、删除全部课程（当前 \$courseCount 门课 · \$fragmentCount 段排课）\"" to 1,
            ),
            lesions = listOf("\${state.courses.size} 门", "（当前 \${state.courses.size} 门）"),
            callAnchor = "summary = courseManagementRowSummary(",
        ),
        Row(
            copyName = "设置页「导出 WakeUp 兼容 JSON」那一行（预览）",
            path = SETTINGS_SCREEN,
            verbatim = listOf(
                "summary = wakeUpExportRowSummary(courseCounts.fragmentCount)," to 1,
                "\"把当前课表导出为 WakeUp 课程表可导入的文件（\$fragmentCount 段排课）\"" to 1,
            ),
            lesions = listOf("文件（\${state.courses.size} 门课）", "\${state.courses.size} 门课"),
            callAnchor = "summary = wakeUpExportRowSummary(",
        ),
        Row(
            copyName = "WakeUp 导出成功那句 toast",
            path = WAKE_UP_COPY,
            verbatim = listOf(
                "val placed = totalFragments - noPeriodFragments - noWeekFragments" to 1,
                "\$placed 段排课" to 4,
            ),
            lesions = listOf("门课", "门没有节次"),
            callAnchor = "val placed = totalFragments",
        ),
        Row(
            copyName = "课表管理页页头（课表管理（N 门课））",
            path = PAGE,
            verbatim = listOf(
                // 门的那把尺：groups 的上游只能是 creditsByCourse，不是第二套键
                "title = \"课表管理（\${groups.size} 门课）\"," to 1,
                "return SemesterStats.creditsByCourse(filtered)" to 1,
                "val groups = remember(state.courses, query) { groupCourses(state.courses, query) }" to 1,
            ),
            // 改前那一形（界面上就地 groupBy 出"门"）必须 0 处；它同时是本行的靶子——
            // KDoc 里逐字抄着它，所以那枚 0 是"换源了"而不是"注释被删了"
            lesions = listOf(".groupBy { groupKeyOf(it) }"),
            callAnchor = "title = \"课表管理（\${groups.size} 门课）\",",
            // 唯一一枚合法的 `.size`：`groups.size` 数的就是门，因为上面那枚锚点钉着
            // groups 的上游是 SemesterStats.creditsByCourse（换成第二把键这一格就红了）
            expectSizeInCallLine = 1,
        ),
        Row(
            copyName = "周次密度条的读屏那句（第 N 周，M 段排课）",
            path = CHARTS,
            verbatim = listOf(
                "fun weekDensityBarDescription(week: Int, count: Int, isCurrent: Boolean): String =" to 1,
                "\"第 \$week 周，\$count 段排课\"" to 1,
                "contentDescription = weekDensityBarDescription(" to 1,
                // 柱高与读屏同枚 counts：两处都吃 forEachIndexed 交回来的那一项
                "counts.forEachIndexed { index, count ->" to 1,
            ),
            lesions = listOf("\"第 \$week 周，\$count 门课\""),
            callAnchor = "contentDescription = weekDensityBarDescription(",
        ),
    )

    @Test
    fun `五句文案各吃哪一枚具名读数 逐字节册子`() {
        for (row in rows) {
            val raw = readMainSource(row.path)
            val code = blankCommentsKeepingLiterals(raw)
            for ((needle, expected) in row.verbatim) {
                val at = code.indexOf(needle)
                assertEquals(
                    "「${row.copyName}」：这一枚取数/文案锚点必须逐字在场且恰 $expected 处 ——「$needle」。" +
                        "\n复算：grep -Fn '$needle' app/src/main/java/${row.path}" +
                        (if (at >= 0) "\n落在 L${lineOf(raw, at)}" else "\n（锚点没找到 = 那句换了写法，本守卫要跟着重钉）") +
                        "\n实到：" + occurrences(code, needle) + " 处",
                    expected,
                    occurrences(code, needle),
                )
            }
        }
    }

    @Test
    fun `病灶那一形在代码里零处 而含注释的原文留着靶子`() {
        for (row in rows) {
            val raw = readMainSource(row.path)
            val code = blankCommentsKeepingLiterals(raw)
            for (needle in row.lesions) {
                assertEquals(
                    "「${row.copyName}」：**钉法 = 数出现次数（抹注释后）** —— 病灶「$needle」在这一页的代码里必须 0 处。" +
                        "\n复算：把 ${row.path} 的注释抹成空格后 grep -c '${needle.take(24)}'" +
                        "\n实到：" + occurrences(code, needle) + " 处",
                    0,
                    occurrences(code, needle),
                )
            }
            // 反欺骗：这个文件必须还在注释里引用着本行的病灶原文（否则上面那枚 0 是删注释换来的）
            val 留痕 = row.lesions.sumOf { occurrences(raw, it) }
            assertTrue(
                "「${row.copyName}」：含注释的原文里必须还抄着本行病灶（改前那句就是它）。实到 $留痕 处 —— " +
                    "读到 0 说明上面那枚「代码 0 处」是靠删注释得到的，那本卡的账就没人认得了",
                留痕 >= 1,
            )
        }
    }

    /**
     * 卡面点名的那一格：报错消息要点名「哪一句又换回了 `.size`」。
     *
     * 判据不是"整个文件里 `.size` 的枚数"（那会把 `.fragments.size`、`CourseColors.size` 这些合法用法
     * 一起算进来，红得没有信息量），而是**那句话自己的取数行**上有没有 `.size` ——
     * 「N 门课」这句的 N 只能来自具名读数，一旦那行出现 `.size`，两把尺就又回到同一屏上了。
     *
     * ⚠️ 一枚合法例外（写在这里而不是藏在常量里）：课表管理页那句吃 `groups.size`。它成立是因为
     * 同一行册子里还钉着 `groups` 的上游 = `SemesterStats.creditsByCourse(filtered)` ——
     * 归并只发生一次，那枚 size 数的就是门。谁把上游换回第二把键，第 ① 档那格先红，
     * 这一格才跟着红；两把尺各数各的，不是拿常量比常量的套套逻辑。
     */
    @Test
    fun `每句的取数行上不许出现 size 红的时候点名是哪一句`() {
        for (row in rows) {
            val raw = readMainSource(row.path)
            val code = blankCommentsKeepingLiterals(raw)
            val hits = indexOfAll(code, row.callAnchor)
            assertEquals("「${row.copyName}」：取数锚点本身必须恰好一处，否则无从判它的落点", 1, hits.size)
            val lineIndex = lineOf(raw, hits.first()) - 1
            // 取那一句调用**自己**的那几行（到枚参数收口的那行为止），数其中的 .size
            val tail = raw.lineSequence().drop(lineIndex).take(4).joinToString(" ")
            val sizes = occurrences(blankCommentsKeepingLiterals(tail), ".size")
            assertEquals(
                "「${row.copyName}」：这一句又换回了 `.size`（实到 $sizes 枚，取数行：${tail.trim()}）。" +
                    "\n病就在这一格：句子里的单位词是「门 / 段」，而 `.size` 数是**排课片段行**，" +
                    "同屏另一句吃的具名读数就不是同一把尺（#308 那三处全是这一型）。" +
                    "\n复算：grep -n -A3 '${row.callAnchor.take(40)}' app/src/main/java/${row.path}",
                row.expectSizeInCallLine,
                sizes,
            )
        }
    }

    // ---------------- helpers（与本仓守卫同一套写法） ----------------

    private fun readMainSource(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 ${file.path}：文件挪过家的话本守卫要跟着改路径", file.isFile)
        return file.readText()
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

    private fun lineOf(rawSource: String, position: Int): Int =
        if (position < 0) -1 else rawSource.substring(0, position).count { it == '\n' } + 1

    /** 只抹注释、保留字符串字面量，长度与换行位置不变 */
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
        const val SETTINGS_SCREEN = "com/buaa/schedule/ui/settings/SettingsScreen.kt"
        const val WAKE_UP_COPY = "com/buaa/schedule/ui/settings/WakeUpExportCopy.kt"
        const val PAGE = "com/buaa/schedule/ui/course/CourseManagementScreen.kt"
        const val CHARTS = "com/buaa/schedule/core/designsystem/ScheduleCharts.kt"
    }
}
