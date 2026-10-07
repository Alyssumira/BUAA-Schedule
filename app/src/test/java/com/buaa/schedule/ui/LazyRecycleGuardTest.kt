package com.buaa.schedule.ui

import com.buaa.schedule.update.ChangelogLine
import com.buaa.schedule.update.changelogContentType
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T170 的守卫：六枚 LazyColumn 的**身份**（key）与**回收桶**（contentType）各就各位。
 *
 * 这个模块没有 Robolectric / Compose 布局测试，回收行为只能钉**结构**：刀法照抄
 * CourseEntranceWiringGuardTest / NowLineGlideGuardTest —— 抹注释、按括号配对取实参表、
 * 一个靶子都没扫到也判红、找不到源码目录直接抛而不是 assumeTrue（跳过的守卫比没有守卫更糟）。
 *
 * 五格判据，各钉一件事：
 * 1. 周次跳转清单：`items(` 带 key，且清单来源是 `remember(totalWeeks)` 记住的那份列表
 *    （改前是每次重组新建一份、且完全没 key）。
 * 2. changelog 清单：key 同时带**位置**与**内容**（只带 index 等于没带；只带内容会撞 key），
 *    contentType 按行形状给、不按位置给。
 * 3. 形状台账：contentType 只许挂在真混排的容器上，逐枚落点数与判据登在 ③ 下面的 KDoc 里
 *    ——同质的容器加了 contentType 只是把回收池切成更多桶，净负。
 * 4. 六枚容器的 key 一枚都不许掉（改前 4 带 2 不带 ⇒ 现在 6 带 0 不带）。
 * 5. [ChangelogLine] 四档 → 两桶的映射本身（纯函数，正向+反向都钉：形状不同要分桶，
 *    同形状的文本不同不许分桶）。
 *
 * 另外钉一枚**不许动**的正例：ConflictWizardDialog 的 `key = { it.stableKey() }` 与它上面
 * 那段"key 为什么必须含内容摘要、不能只含 id"的论证注释——本仓有"统一口径"把正例改坏的先例。
 */
class LazyRecycleGuardTest {

    // ---- ①：周次跳转清单的 key 与记住的列表 --------------------------------

    @Test
    fun weekJumpListCarriesKeyAndRememberedListSource() {
        val code = blankComments(source(HOME_SCREEN))
        val calls = callArgs(code, "items(")
        assertEquals("HomeScreen 里就该有一枚 items(（跳转周次那枚），扫到 ${calls.size} 枚", 1, calls.size)

        val args = calls[0]
        assertTrue(
            "周次清单又没 key 了：对话框每次重组都会把整列销毁重建，滚动位置与高亮跟丢",
            namedArg(args, "key") != null,
        )
        assertTrue(
            "清单来源又回到 items 实参里现建：每重组一次新建一份 List，得走 remember(totalWeeks)",
            !args.contains("toList()") && !args.contains("..totalWeeks"),
        )
        assertEquals(
            "记住的列表只许一处：remember(totalWeeks) { weekChoicesFor(hasSemester, totalWeeks) }" +
                "（T197 把来源换进内核，键仍是 totalWeeks —— 键与来源配对那笔账不许漂，" +
                "改完记得同步 WeekNavigationAvailabilityWiringGuardTest 的第④层）",
            1,
            occurrences(code, "remember(totalWeeks) { weekChoicesFor(hasSemester, totalWeeks) }"),
        )
        assertTrue(
            "items 读的不许是别的来源，必须是那枚记住的列表",
            args.trim().startsWith("weekChoices"),
        )
    }

    // ---- ②：changelog 清单的 key（位置+内容）与 contentType（按形状） --------

    @Test
    fun changelogListCarriesPositionAndContentKeyPlusShapeContentType() {
        val code = blankComments(source(UPDATE_DIALOG))
        val calls = callArgs(code, "items(")
        assertEquals("UpdateDialog 里就该有一枚 items(（changelog 那枚），扫到 ${calls.size} 枚", 1, calls.size)

        val args = calls[0]
        val rawKey = namedArg(args, "key")
        assertTrue("changelog 的 key 掉了（计数重载的 index 等价于没给 key）", rawKey != null)
        val key = rawKey!!
        assertTrue(
            "key 里要带位置：changelog 允许出现两行一模一样的文本、多个空行，纯内容的 key 会撞 → 直接抛",
            Regex("""\$\{?index""").containsMatchIn(key),
        )
        assertTrue(
            "key 里要带内容：只按 index 算的 key 让「同一格换了另一行」被当成同一项继续复用",
            key.contains("lines[index]"),
        )

        val rawContentType = namedArg(args, "contentType")
        assertTrue("changelog 的 contentType 掉了：这一枚容器混排两种行形状，见 ③ 的台账", rawContentType != null)
        val contentType = rawContentType!!
        assertTrue(
            "contentType 必须走 changelogContentType(…)——桶的归属只有一个定义处",
            contentType.contains("changelogContentType("),
        )
        // 只看 lambda 体：形参名 `index` 声明在 `->` 左边，不该被判成「按位置分桶」
        val body = contentType.substringAfter("->", contentType)
        val withoutShapeLookup = body.replace(
            Regex("""changelogContentType\(\s*lines\[index\]\s*\)"""),
            "",
        )
        assertTrue(
            "contentType 不许掺位置（按 index 分桶 = 每行独占一桶 = 零复用）：$withoutShapeLookup",
            !withoutShapeLookup.contains("index") && !withoutShapeLookup.contains("it"),
        )
    }

    // ---- ③：形状台账——contentType 只许挂在真混排的容器上 --------------------

    /**
     * 台账（逐枚读过 item 体之后登的，动任何一格都要先把那枚容器的形状重量一遍）：
     *
     * 分桶（真混排，槽位会错配）：
     * - UpdateDialog changelog：`ChangelogRow` 的 `when` 里 Bullet 是 `Row{Text;Text}`，
     *   Heading / Paragraph / Blank 都是单枚 `Text` ⇒ 两型结构，1 处 contentType。
     * - CourseManagementScreen：N 枚 `CourseGroupCard` + 尾部那枚垫高 `Spacer` ⇒ 2 处。
     * - ImportHistoryScreen：N 枚 `ImportHistoryRow`（GlassSurface 卡）+ 尾部那枚
     *   `TextButton`「清空全部历史记录」⇒ 2 处。
     *
     * 不分桶（同质，加了只是把回收池切成更多桶 ⇒ 净负）：
     * - DayView 今日卡片列表：一枚 `CourseTimelineCard`。空态在 LazyColumn **外面**那层
     *   Crossfade 的另一支，不在这个容器里；卡内 `if (startTime != null)` 那一格时间列
     *   是同一种卡的宽窄变化，按它分桶会把同型卡劈成两桶。
     * - HomeScreen 周次跳转：一枚 `Text`，「（本周）」只换 style/color 不换节点结构。
     * - ConflictWizardDialog：一枚 `ConflictGroupRow`——组头与明细行住在**同一个 item 里**
     *   （Column 的两段），不是两种 lazy item；行尾 `when`（有建议+按钮 / 已应用 / 没空位）
     *   要在 items 调用点算出来得再跑一次 `suggestNearestFreeShift`，代价高于收益 ⇒ 不加。
     */
    @Test
    fun contentTypeBucketsMatchTheMeasuredShapes() {
        val found = LAZY_FILES.associateWith { occurrences(blankComments(source(it)), "contentType = ") }
        assertEquals(
            "contentType 的落点就是形状台账：六枚容器里只有 changelog / 组卡页 / 导入历史三枚真混排，" +
                "另外三枚（今日卡片、周次跳转、冲突向导）同质，加分桶只会让复用槽位变少——" +
                "逐枚判据见本测试的 KDoc，要改台账先把那枚容器的 item 体重量一遍：$found",
            mapOf(
                HOME_SCREEN to 0,
                UPDATE_DIALOG to 1,
                DAY_VIEW to 0,
                COURSE_MANAGEMENT to 2,
                IMPORT_HISTORY to 2,
                CONFLICT_WIZARD to 0,
            ),
            found,
        )
    }

    // ---- ④：六枚容器的 key 一枚都不许掉 ------------------------------------

    @Test
    fun everyListContainerCarriesAKey() {
        val missing = mutableListOf<String>()
        var total = 0
        LAZY_FILES.forEach { relative ->
            val code = blankComments(source(relative))
            listOf("items(", "itemsIndexed(").forEach { call ->
                callArgs(code, call).forEach { args ->
                    total++
                    if (namedArg(args, "key") == null) missing += "$relative → $call$args"
                }
            }
        }
        assertEquals(
            "六枚容器各一枚 items/itemsIndexed，一枚都不许没有 key（改前 4 带 2 不带 ⇒ 现在 6 带 0 不带）",
            6,
            total,
        )
        assertTrue("掉 key 的容器：\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    // ---- ⑤：四档行 → 两桶的映射（纯函数） ----------------------------------

    @Test
    fun changelogShapeBucketsAreTwoAndDependOnStructureOnly() {
        val textish = listOf(
            ChangelogLine.Heading("标题"),
            ChangelogLine.Paragraph("正文"),
            ChangelogLine.Blank,
        )
        val bullet = changelogContentType(ChangelogLine.Bullet("项目"))
        textish.forEach { line ->
            assertNotEquals(
                "Bullet 是 Row{Text;Text}，另外三档是单枚 Text，结构不同必须分桶：$line",
                bullet,
                changelogContentType(line),
            )
        }
        assertEquals(
            "Heading / Paragraph / Blank 结构相同（同一桶），分细了只是多攒桶：${textish.map { changelogContentType(it) }}",
            1,
            textish.map { changelogContentType(it) }.toSet().size,
        )
        assertEquals(
            "同形状的文本不同不许分桶（按文本内容分 = 每行独占一桶 = 零复用）",
            changelogContentType(ChangelogLine.Paragraph("第一行")),
            changelogContentType(ChangelogLine.Paragraph("完全不同的第二行")),
        )
        assertNotEquals(
            "两桶各占一名，不许撞名",
            bullet,
            changelogContentType(ChangelogLine.Heading("x")),
        )
        assertTrue(
            "桶名必须是非空字面量（空串会被回收池当成同一档，等于没写）",
            listOf(bullet, changelogContentType(ChangelogLine.Blank)).none { it.isBlank() },
        )
    }

    // ---- ⑥：不许动的正例 ---------------------------------------------------

    @Test
    fun conflictWizardStableKeyAndItsArgumentCommentStayPut() {
        val raw = source(CONFLICT_WIZARD)
        val code = blankComments(raw)
        assertTrue(
            "ConflictWizardDialog:38-41 那段「key 为什么必须含内容摘要、不能只含 id」的论证被删了——" +
                "它是这段 key 形状的唯一说明，不许按「统一口径」顺手抹掉",
            raw.contains("此前用 `it.hashCode()`"),
        )
        assertTrue(
            "items(groups, key = { it.stableKey() }) 是别的容器抄的形状，本卡不许动它",
            code.contains("items(groups, key = { it.stableKey() })"),
        )
        assertTrue(
            "stableKey() 的定义处（星期 + 组内课程身份）不许搬走",
            code.contains("private fun CourseConflictResolution.ConflictGroup.stableKey(): String"),
        )
    }

    // ---- 靶子定位与词法小工具（与 CourseEntranceWiringGuardTest 同一套）------

    /** [code]（抹过注释的源码）里每处 [call]（如 `items(`）括号内的实参表文本 */
    private fun callArgs(code: String, call: String): List<String> {
        val out = mutableListOf<String>()
        var at = code.indexOf(call)
        while (at >= 0) {
            val open = at + call.length - 1
            val close = matchingClose(code, open)
                ?: throw AssertionError("$call 的括号配不上对，解析器该修了")
            out += code.substring(open + 1, close)
            at = code.indexOf(call, at + call.length)
        }
        return out
    }

    /** 实参表按**顶层**逗号切片：括号/花括号里的逗号（含字符串模板的 `${`）不算分隔 */
    private fun topLevelPieces(args: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = 0
        args.forEachIndexed { i, ch ->
            when (ch) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> {
                    depth--
                    assertTrue("实参表的括号配不上对：$args", depth >= 0)
                }

                ',' -> if (depth == 0) {
                    out += args.substring(start, i)
                    start = i + 1
                }

                else -> Unit
            }
        }
        out += args.substring(start)
        return out
    }

    /** 具名实参 `key = …` 右边的文本（不含前缀）；这一处调用里没有该具名实参就返回 null */
    private fun namedArg(args: String, name: String): String? = topLevelPieces(args)
        .map { it.trim() }
        .firstOrNull { it.startsWith("$name =") }
        ?.removePrefix("$name =")
        ?.trim()

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var at = haystack.indexOf(needle)
        while (at >= 0) {
            count++
            at = haystack.indexOf(needle, at + needle.length)
        }
        return count
    }

    private fun source(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 $relative：挪过家的话这条守卫要跟着改路径", file.isFile)
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

    /** 把 `//` 与 `/* */` 注释抹成空格（字符串保留、长度与换行位置不变） */
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

    private companion object {
        const val HOME_SCREEN = "com/buaa/schedule/ui/home/HomeScreen.kt"
        const val UPDATE_DIALOG = "com/buaa/schedule/update/UpdateDialog.kt"
        const val DAY_VIEW = "com/buaa/schedule/ui/home/DayView.kt"
        const val COURSE_MANAGEMENT = "com/buaa/schedule/ui/course/CourseManagementScreen.kt"
        const val IMPORT_HISTORY = "com/buaa/schedule/ui/importing/ImportHistoryScreen.kt"
        const val CONFLICT_WIZARD = "com/buaa/schedule/ui/home/ConflictWizardDialog.kt"

        /** 普查点到的那六枚 LazyColumn 所在文件，台账逐枚登在 ③ 的 KDoc 里 */
        val LAZY_FILES = listOf(
            HOME_SCREEN,
            UPDATE_DIALOG,
            DAY_VIEW,
            COURSE_MANAGEMENT,
            IMPORT_HISTORY,
            CONFLICT_WIZARD,
        )
    }
}
