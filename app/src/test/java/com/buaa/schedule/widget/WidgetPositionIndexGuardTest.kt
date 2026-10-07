package com.buaa.schedule.widget

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T219②：把「两枚 `RemoteViewsFactory` 各只护住一半的越界索引」钉成静态守卫。
 *
 * ## 病灶今天的形状（卡面 T219，盘面复量于 `ai/T219`）
 *
 * 两枚文件各有**两枚按 `position` 索引**的 override（`getViewAt` / `getItemId`），基点上每枚文件
 * 只护住其中一枚：`WeekGridWidgetService` 护了 `getItemId`、`getViewAt` 裸着；`CourseListWidgetService`
 * 护了 `getViewAt`、`getItemId` 裸着。裸 `[position]` 抛出的 `IndexOutOfBoundsException`
 * 崩掉的是**整个应用进程**（`RemoteViewsFactory` 跑在我们自己的进程里），不是只有组件画不对。
 * 可达路径量在盘上：`onDataSetChanged` 跑在主线程、不许查库 ⇒ `WidgetDataCache.peek` 未命中时
 * **立刻** `cells = emptyList()` / `rows = emptyList()`（两文件的快照未命中分支），而宿主手里还拿着
 * 上一次 `getCount()` 报出来的旧 position。`peek` 那枚时限窗实测 = `WidgetDataCache.TTL_MILLIS`
 * = **5_000L**（`private const val TTL_MILLIS = 5_000L` 那一枚，`peek` 与 `get` 认同同一枚窗口：
 * `System.currentTimeMillis() - entry.at > TTL_MILLIS` 即返回 null）⇒ 距上一次同学期取数满 5 秒后
 * 任何一个组件的第一次 `onDataSetChanged` 都是未命中；`invalidate()` 之后、跨学期实例互相挤掉之后、
 * 冷启动首次刷新之后同样未命中 ⇒ 这一格装机可复现。
 *
 * ## 四家钉法逐格点名
 *
 * 1. **数出现次数（抹注释后归零）** —— 第 ① 层：`widget/` 全树里「裸索引形状」（`\w+[position]`）
 *    必须恰好 **0** 处（`getOrNull(position)` 不计，它前面是圆括号）。⚠️ 尺子**先抹注释再数**：
 *    本卡改后 `CourseListWidgetService` 与 `WeekGridWidgetService` 的注释里逐字写着 `[position]` /
 *    `cells[position]`（那两段在**叙述病灶**），不抹注释就会假红；反过来「把真裸点藏进注释」也逃得过
 *    —— 所以第 ⑤ 层拿内联样例自证「抹注释」这一步真的在抹。
 * 2. **逐字节 + 读形状** —— 第 ② 层：四枚 override 各钉两枚锚点：宿主函数签名那一行（逐字节，恰好
 *    1 处）+ **签名之后第一枚非空代码行**（读形状取法，逐字节比对）。取「第一枚非空代码行」是因为
 *    注释已被抹成空白而行结构不变 ⇒ 中间隔几段注释都不会把锚点搬跑。
 *    报错消息点名「哪一手没护」（文件 + 宿主函数名 + 期望/实到首行）。
 * 3. **数出现次数（落点册子）** —— 第 ③ 层：`getOrNull(position)` 的按文件册子必须恰好
 *    `{CourseListWidgetService.kt=2, WeekGridWidgetService.kt=2}`（合计 4 = 四手各一枚）；
 *    `oobItemKey(position)` 的册子必须恰好 `{CourseListWidgetService.kt=1, WeekGridWidgetService.kt=1}`
 *    （兜底 id 只许有两个调用点，`WidgetCommon` 里那一枚是定义、不带调用形状）。
 * 4. **抹注释找锚点** —— 第 ①②③④ 层的读取面**全部**是抹注释后的文本
 *    （`blankCommentsKeepingLiterals`，保留字符串字面量与换行位置）；第 ⑤ 层把「含注释 / 抹注释」
 *    两侧枚数摆成一对内联样例。
 *
 * ## 两侧格子（本卡实测过，不是推定）
 *
 * - **朝宽**（必须红）：把 `WeekGridWidgetService.getViewAt` 首行改回裸 `val cell = cells[position]`
 *   ⇒ 第 ① 层那把归零尺与第 ② 层 `WeekGridFactory.getViewAt` 那一格**同时红**，红名落在这两枚守卫
 *   方法上。表驱动 JVM 单测盖不到：`WeekGridFactory` 要 `Context` 与 `RemoteViews`，纯 JVM 起不来
 *   ⇒「只有守卫会红」是结构性的，不是巧合。
 * - **朝窄**（不许红）：只在注释里写那一形状 ⇒ 全绿。盘上今天自带一枚天然窄侧样本
 *   （那两段叙述病灶的注释里就有 `cells[position]` 与 `[position]`）。
 */
class WidgetPositionIndexGuardTest {

    // ─────────────── ① 裸索引形状：抹注释后归零 ───────────────

    @Test
    fun `widget 全树抹注释后不许留下任何裸 position 索引`() {
        val byFile = readWidgetDir()
        val bare = bareIndexHits(byFile)

        assertEquals(
            "**钉法 = 数出现次数（先抹注释再数）**：`app/src/main/java/com/buaa/schedule/widget/` 全树里" +
                "「裸索引形状」（`某个列表[position]`）必须恰好 **0** 处，实到 " + bare.size + " 处：" + bare +
                "。RemoteViewsFactory 跑在我们的进程里，越界的 position 索引抛出 " +
                "IndexOutOfBoundsException 崩的是整个应用；两枚工厂的四手（getViewAt/getItemId × 2）" +
                "有一手裸着就红。命中的那一行会带着「文件 ⇒ 该行原文」一起报出来。" +
                "`getOrNull(position)` 不在计数里（它前面是圆括号，正是护住的那一手）。" +
                "⚠️ 计数取自**抹注释后**的文本：叙述病灶的注释（本卡留了两段，里面有 `cells[position]` " +
                "这种形状）不算裸点，见第 ⑤ 层那对内联样例",
            emptyList<String>(),
            bare,
        )
    }

    // ─────────────── ② 四手各钉：宿主函数名 + 首行取法 ───────────────

    @Test
    fun `两枚工厂的四手都必须是 getOrNull 加兜底早返回 逐字节钉首行`() {
        for (hand in HANDS) {
            val code = source(hand.file)

            assertEquals(
                "**钉法 = 逐字节**：`" + hand.name + "` 里宿主函数签名 `" + hand.signature + "` " +
                    "必须恰好 1 处（实到 " + occurrences(code, hand.signature) + " 处）。" +
                    "签名挪过家 / 改了返回类型 / 把这一手整枚删掉，这一格先红 —— " +
                    "宿主函数名一并钉住，是为了让报错能指到是哪一枚 override 在动",
                1,
                occurrences(code, hand.signature),
            )

            val first = firstCodeLineAfter(code, hand.signature)
            assertEquals(
                "**钉法 = 读形状 + 逐字节**：`" + hand.name + "` 的 `" + hand.host +
                    "` 里，签名之后**第一枚非空代码行**必须是那一行带防护的取法。" +
                    "哪一手没护：「" + hand.hand + "」—— 期望 `" + hand.firstLine + "`，实到 `" + first + "`。" +
                    "四手清单 = WeekGridFactory.getViewAt / WeekGridFactory.getItemId / " +
                    "CourseListFactory.getViewAt / CourseListFactory.getItemId；" +
                    "把 `getOrNull(position)` 换回 `[position]`、或把兜底那半枚 `?: return …` 摘掉，" +
                    "都会让实到与期望不等（换成裸索引那一手时，第 ① 层那把归零尺同一趟也会红）",
                hand.firstLine,
                first,
            )
        }
    }

    // ─────────────── ③ 落点册子：护住的取法与兜底 id 各几枚、长在哪 ───────────────

    @Test
    fun `getOrNull 与兜底 id 的落点册子必须正好是那四手`() {
        val byFile = readWidgetDir()

        assertEquals(
            "**钉法 = 数出现次数（落点册子）**：`getOrNull(position)` 按文件的册子必须恰好 " +
                "{CourseListWidgetService.kt=2, WeekGridWidgetService.kt=2}（实到 " +
                ledger(byFile, GUARDED_TAKE) + "，合计 " + occurrencesOfAll(byFile, GUARDED_TAKE) +
                " 处）：两枚工厂各两枚 override、一手一枚。少一枚 = 那一手又被改回裸索引" +
                "（第 ①② 层同一趟会红）；多一枚 = 有人把防护搬进别的路径或包了一层壳，" +
                "口径从此不再由这四手自己决定",
            listOf(LIST_NAME to 2, GRID_NAME to 2),
            filesWithCount(byFile, GUARDED_TAKE),
        )
        assertEquals(
            "**钉法 = 数出现次数（册子）**：兜底 id `oobItemKey(position)` 的落点册子必须恰好 " +
                "{CourseListWidgetService.kt=1, WeekGridWidgetService.kt=1}（实到 " +
                ledger(byFile, OOB_KEY_CALL) + "）：两枚 `getItemId` 各一处调用。" +
                "`WidgetCommon.kt` 里那一枚是**定义**、不带这枚调用形状；越界兜底若被就地写成 " +
                "`itemKey(position, position.toLong())` 或随手一枚常量，撞号推算" +
                "（见 `WidgetCommon.oobItemKey` 的 KDoc）就脱离了出处",
            listOf(LIST_NAME to 1, GRID_NAME to 1),
            filesWithCount(byFile, OOB_KEY_CALL),
        )
    }

    // ─────────────── ④ 旧 position 从哪来：getCount 与被索引列表同源 ───────────────

    /**
     * 这一层钉「为什么旧 position 会打进来」那半边：`getCount()` 报的就是被索引那枚列表的长度，
     * 而快照未命中分支会把它清空。哪天 `getCount()` 改成报一个与列表无关的固定枚数，
     * 越界就从「偶发」变成「常态」，而第 ①② 层照样全绿 ⇒ 这一格补的是那个方向。
     */
    @Test
    fun `getCount 报的就是被索引那枚列表的长度 未命中时它被清空`() {
        for (hand in HANDS.distinctBy { it.file }) {
            val code = source(hand.file)
            val countLine = "override fun getCount(): Int = " + hand.sizeExpr
            assertEquals(
                "**钉法 = 逐字节**：`" + hand.name + "` 的 `$countLine` 必须逐字在此（实到 " +
                    occurrences(code, countLine) + " 处）。宿主手里的 position 上界就来自这一句 ⇒ " +
                    "它报的枚数与被索引的 " + hand.sizeExpr + " 必须同源",
                1,
                occurrences(code, countLine),
            )
            val clearExpr = hand.sizeExpr.substringBefore(".size") + " = emptyList()"
            assertEquals(
                "**钉法 = 数出现次数**：`$clearExpr`（快照未命中分支 + `onDestroy` 各一次清空）在 " +
                    hand.name + " 恰好 " + CLEAR_COUNT + " 处（实到 " + occurrences(code, clearExpr) +
                    " 处）：`onDataSetChanged` 在主线程不许查库，未命中时就地把列表清空再异步补数据，" +
                    "旧 position 打在空表上必抛 ⇒ 这就是这一族缺陷的可达路径本身",
                CLEAR_COUNT,
                occurrences(code, clearExpr),
            )
        }
    }

    // ─────────────── ⑤ 反套套逻辑：三枚探测器都不许空转 ───────────────

    @Test
    fun `归零尺与首行定位器都不是空转`() {
        // 朝窄样例：同一枚形状只长在注释里 ⇒ 含注释数得到、抹注释后数不到（第 ① 层靠这才不是套套）
        val narrow = """
            override fun getViewAt(position: Int): RemoteViews {
                // 叙述病灶：以前是 val cell = cells[position]，抛出去崩的是整个进程
                val cell = cells.getOrNull(position) ?: return RemoteViews(pkg, R.layout.x)
                return views
            }
        """.trimIndent()
        assertEquals(
            "内联样例（朝窄，含注释那一侧）：`[position]` 只长在注释里时原文必须数到 1 处（实到 " +
                bareIndexHits(mapOf("Inline.kt" to narrow)).size +
                " 处）—— 数不到的话下面那一格「抹注释后 0 处」就是靠看不见抓的，等于没有尺",
            1,
            bareIndexHits(mapOf("Inline.kt" to narrow)).size,
        )
        assertEquals(
            "内联样例（朝窄，抹注释那一侧）：同一份文本抹注释之后必须数到 **0** 处（实到 " +
                bareIndexHits(mapOf("Inline.kt" to blankCommentsKeepingLiterals(narrow))).size +
                " 处）⇒ 归零尺放得过注释、藏不住代码，这正是「只在注释里写那一形状不许红」那一格",
            0,
            bareIndexHits(mapOf("Inline.kt" to blankCommentsKeepingLiterals(narrow))).size,
        )

        // 朝宽样例：同一枚形状长在代码里 ⇒ 归零尺必须抓到，首行定位器必须原样报出它
        val wide = """
            override fun getViewAt(position: Int): RemoteViews {
                val cell = cells[position]
                return views
            }
        """.trimIndent()
        assertEquals(
            "内联样例（朝宽）：代码里的裸索引必须被归零尺抓到 1 处（实到 " +
                bareIndexHits(mapOf("Inline.kt" to wide)).size + " 处）—— 抓不到的话第 ① 层就是空转",
            1,
            bareIndexHits(mapOf("Inline.kt" to wide)).size,
        )
        assertEquals(
            "内联样例（朝宽）：首行定位器必须交出那一行裸取法本身（实到 `" +
                firstCodeLineAfter(wide, VIEWAT_SIGNATURE) +
                "`）⇒ 第 ② 层比对的是取法，不是「文件里有没有 getOrNull」这种印象",
            "val cell = cells[position]",
            firstCodeLineAfter(wide, VIEWAT_SIGNATURE),
        )

        // 摘掉兜底那一半：getOrNull 还在、`?: return …` 没了 ⇒ 归零尺抓不到，必须靠第 ② 层
        val noFallback = """
            override fun getViewAt(position: Int): RemoteViews {
                val cell = cells.getOrNull(position)
                return views
            }
        """.trimIndent()
        assertEquals(
            "内联样例（摘掉兜底）：首行定位器必须原样交出这一行（实到 `" +
                firstCodeLineAfter(noFallback, VIEWAT_SIGNATURE) +
                "`），它与锚点不等 ⇒ 第 ② 层红；而这一形的 `[position]` 计数仍为 0，" +
                "说明第 ①② 两层盖的是两种拆法、不是同一把尺数两遍",
            "val cell = cells.getOrNull(position)",
            firstCodeLineAfter(noFallback, VIEWAT_SIGNATURE),
        )
    }

    // ---------------- 源码核对小工具（与仓内其它静态守卫同一口径）----------------

    /** 裸索引形状：`某个东西[position]`（`getOrNull(position)` 这类圆括号取法不计） */
    private fun bareIndex(source: String): List<String> =
        Regex("""[A-Za-z0-9_.]+\[position]""").findAll(source).map { it.value }.toList()

    /** 按文件给出「文件 ⇒ 该行原文」的命中清单（报错时要能直接看见是哪一行、在哪份文件） */
    private fun bareIndexHits(byFile: Map<String, String>): List<String> =
        byFile.flatMap { (name, code) ->
            bareIndex(code).map { "$name ⇒ ${code.lineOf(it).trim()}" }.distinct()
        }

    /** 签名之后第一枚非空代码行（注释已被抹成空白、行结构不变 ⇒ 中间隔几段注释都搬不跑锚点） */
    private fun firstCodeLineAfter(code: String, signature: String): String {
        val at = code.indexOf(signature)
        assertTrue("`$signature` 找不到：宿主函数改名或整枚被删掉了", at >= 0)
        return code.substring(at + signature.length).split('\n')
            .asSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?: "<签名之后没有任何代码行>"
    }

    /** 命中所在的那一行原文（行号可以从这里的下标再数一遍，报错消息先给原文） */
    private fun String.lineOf(needle: String): String {
        val at = indexOf(needle)
        if (at < 0) return "<找不到>"
        val lineIndex = substring(0, at).count { it == '\n' }
        return split('\n').getOrElse(lineIndex) { "<越界>" }
    }

    private fun filesWithCount(byFile: Map<String, String>, needle: String): List<Pair<String, Int>> =
        byFile.filter { occurrences(it.value, needle) > 0 }
            .map { it.key to occurrences(it.value, needle) }
            .sortedBy { it.first }

    private fun ledger(byFile: Map<String, String>, needle: String): String =
        byFile.entries.toList().sortedBy { it.key }
            .filter { occurrences(it.value, needle) > 0 }
            .joinToString(prefix = "{", postfix = "}") { "${it.key}=${occurrences(it.value, needle)}" }

    private fun occurrencesOfAll(byFile: Map<String, String>, needle: String): Int =
        byFile.values.sumOf { occurrences(it, needle) }

    /** 抹注释后的单份 widget 源码 */
    private fun source(relativeFromMainJava: String): String =
        blankCommentsKeepingLiterals(rawSource(relativeFromMainJava))

    private fun rawSource(relativeFromMainJava: String): String {
        val file = File(findMainJavaDir(), relativeFromMainJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    /** 文件名 → 抹注释后的内容（整棵 `widget/` 目录，按文件名建册子） */
    private fun readWidgetDir(): Map<String, String> {
        val root = File(findMainJavaDir(), WIDGET_RELATIVE_DIR)
        assertTrue("找不到 ${root.path}：目录挪过家的话本守卫要跟着改路径", root.isDirectory)
        val out = linkedMapOf<String, String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach {
            check(!out.containsKey(it.name)) { "widget/ 下有同名文件 ${it.name}，按文件名建册子会读错份" }
            out[it.name] = blankCommentsKeepingLiterals(it.readText().replace("\r\n", "\n"))
        }
        assertTrue("${root.path} 下一个 .kt 都没有：路径不对，本守卫就是空的", out.isNotEmpty())
        return out
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

    /** **只抹注释、保留字符串字面量内容**，长度与换行位置不变（报错消息里的行号因此可信） */
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

    /** 一手 = 一枚按 `position` 索引的 override：宿主函数名 + 首行那一带防护的取法 */
    private data class Hand(
        val file: String,
        val name: String,
        val host: String,
        val hand: String,
        val signature: String,
        val firstLine: String,
        val sizeExpr: String,
    )

    private companion object {
        const val WIDGET_RELATIVE_DIR = "com/buaa/schedule/widget"
        const val GRID = "$WIDGET_RELATIVE_DIR/WeekGridWidgetService.kt"
        const val LIST = "$WIDGET_RELATIVE_DIR/CourseListWidgetService.kt"
        const val GRID_NAME = "WeekGridWidgetService.kt"
        const val LIST_NAME = "CourseListWidgetService.kt"

        const val GUARDED_TAKE = "getOrNull(position)"
        const val OOB_KEY_CALL = "oobItemKey(position)"
        const val VIEWAT_SIGNATURE = "override fun getViewAt(position: Int): RemoteViews {"
        const val ITEMID_SIGNATURE = "override fun getItemId(position: Int): Long {"

        /** 快照未命中分支与 `onDestroy` 各清空一次同一枚列表 ⇒ 每枚文件 2 处 */
        const val CLEAR_COUNT = 2

        val HANDS: List<Hand> = listOf(
            Hand(
                file = GRID, name = GRID_NAME, host = "WeekGridFactory.getViewAt",
                hand = "WeekGridWidgetService 的 getViewAt（T219 之前裸着的这一手）",
                signature = VIEWAT_SIGNATURE,
                firstLine = "val cell = cells.getOrNull(position) ?: return RemoteViews(",
                sizeExpr = "cells.size",
            ),
            Hand(
                file = GRID, name = GRID_NAME, host = "WeekGridFactory.getItemId",
                hand = "WeekGridWidgetService 的 getItemId（基点已护住的那一手）",
                signature = ITEMID_SIGNATURE,
                firstLine = "val cell = cells.getOrNull(position) ?: return WidgetCommon.oobItemKey(position)",
                sizeExpr = "cells.size",
            ),
            Hand(
                file = LIST, name = LIST_NAME, host = "CourseListFactory.getViewAt",
                hand = "CourseListWidgetService 的 getViewAt（基点已护住的那一手）",
                signature = VIEWAT_SIGNATURE,
                firstLine = "val row = rows.getOrNull(position) ?: return RemoteViews(",
                sizeExpr = "rows.size",
            ),
            Hand(
                file = LIST, name = LIST_NAME, host = "CourseListFactory.getItemId",
                hand = "CourseListWidgetService 的 getItemId（T219 之前裸着的这一手）",
                signature = ITEMID_SIGNATURE,
                firstLine = "val row = rows.getOrNull(position) ?: return WidgetCommon.oobItemKey(position)",
                sizeExpr = "rows.size",
            ),
        )
    }
}
