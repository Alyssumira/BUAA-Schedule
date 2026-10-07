package com.buaa.schedule.data.import

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T229 ①（#301）静态守卫：教务导入的**节次缺失**那一手，形状与账本册子。
 *
 * 纯 JVM，零 android、零时钟、零设备：读的是 `app/src/main/java` 的工作树文本。
 * 输入面已在 `app/build.gradle.kts` 的 `guardReadWorkingTreeFiles` 里整目录进
 * `tasks.withType<Test>()` 的 `inputs`（T92①），"改了源码、单跑守卫拿到 Gradle 缓存绿灯"那一档盖得住；
 * 本守卫没有新增**目录之外**的读取面，不动构建脚本。
 *
 * 读法与小工具抄姊妹守卫 `IcsSectionPolicyGuardTest` / `BuaaScheduleParserTotalWeeksGuardTest`
 * （抹注释保字面量 + 花括号配平取块 + 失败消息带实到枚数与文件册子）。
 *
 * ## 钉法四家逐格点名
 *
 * 1. **数出现次数（册子）** —— 第 ① 层：`missingSectionCourses` 在全 `app/src/main` 的落点册子
 *    恰好 `{BuaaScheduleParser.kt}`、枚数写死；`droppedNoSection` 同样只在这份文件里。
 *    册子里多一枚文件 = 有人在别处自己数了一遍丢弃条数（两本账），少一枚 = 计数被摘掉。
 * 2. **逐字节** —— 第 ② 层：那三行判据本体（`val day = item.dayOfWeek ?: return@forEachIndexed` /
 *    `val start = item.beginSection` / `return@forEachIndexed`）逐字各一枚。
 *    只改名字不改行为的话第 ① 层会红而第 ② 层会告诉你红在哪一行。
 * 3. **读形状** —— 第 ③ 层：按**花括号配平**取出 `items.forEachIndexed { … }` 的体，
 *    断言「星期那一手」与「节次那一手」两种死法**都**收在 `return@forEachIndexed` 上（同尺的形状判据），
 *    而体内的 `item.beginSection` 后面**不挂任何兜底**。
 * 4. **抹注释找锚点** —— 第 ④ 层：`missingSectionCourses` 在 `BuaaLoginScreen.kt` 的
 *    **含注释 / 抹注释两侧都是 0 处**。这一格钉的是**今天的事实**（计数进了 `ParseOutcome` 但还没进
 *    用户可见报数），不是"已修好"的凭证 —— 与 `BuaaScheduleParserTotalWeeksGuardTest` 第 ④ 层同一手法：
 *    把缺口钉成册子而不是钉成绿灯，接它的那张卡必须显式把这里的 0 改成非 0。
 *
 * ## 两侧的红臂
 *
 * - **朝松（把丢弃换成兜底）**：`val start = item.beginSection ?: 1` ⇒ 第 ②③ 层同时红
 *   （逐字那行变了 + 体内 `?: 1` 又长回来）；行为侧由 `BuaaMissingSectionDropTest` 那两格钉。
 * - **朝窄（把丢弃扩到缺 end）**：`val end = item.endSection ?: return@forEachIndexed` ⇒
 *   第 ③ 层「有出处的那一等不许跟着丢」红，`PeriodRangeBoundsTest.buaaParserMissingEndSectionFallsBackToStart`
 *   也红（两把尺互为备份）。
 * - **计数搬家**：把 `missingSectionCourses = droppedNoSection` 摘掉 ⇒ 第 ① 层册子红。
 */
class BuaaMissingSectionGuardTest {

    // ─────────────── ① 计数的落点册子 ───────────────

    @Test
    fun `节次账只许长在解析器那一枚文件里`() {
        val byFile = readMainSources()

        assertEquals(
            "**钉法 = 数出现次数（册子）**：`$COUNTER` 在全 app/src/main 恰好 " + MAIN_COUNTER_TOTAL +
                " 处（实到 " + occurrencesOfAll(byFile, COUNTER) + " 处，册子 " +
                ledger(byFile, COUNTER) + "）：声明一枚 + 交回 `ParseOutcome` 一枚，两枚都在 `$PARSER`。" +
                "多一枚落点 = 有人在别处又数了一遍丢弃条数（两本账迟早对不上）；少一枚 = 计数被摘掉，" +
                "而这一格正是「整条丢」与「静默吞掉」的唯一区分处",
            MAIN_COUNTER_TOTAL,
            occurrencesOfAll(byFile, COUNTER),
        )
        assertEquals(
            "**钉法 = 数出现次数（落点册子）**：`$COUNTER` 的落点必须只有 `$PARSER_NAME`（实到 " +
                filesHolding(byFile, COUNTER) + "）",
            listOf(PARSER_NAME),
            filesHolding(byFile, COUNTER),
        )
        assertEquals(
            "**钉法 = 数出现次数**：计数器局部变量 `$LOCAL` 在 `$PARSER` 恰好 " + PARSER_LOCAL_TOTAL +
                " 处（声明 / 自增 / 交回；实到 " + occurrences(source(PARSER), LOCAL) + " 处）：" +
                "少一枚就是「丢了但没记」，多一枚就是有人在别处也往账里加",
            PARSER_LOCAL_TOTAL,
            occurrences(source(PARSER), LOCAL),
        )
        assertEquals(
            "**钉法 = 数出现次数**：`$THIN_SHELL_DOC`（薄壳那句「只回 courses」）里点名丢掉的计数枚数" +
                "必须是**三**枚（改前写的是「两枚」，加上这一枚就漏了一枚 ⇒ 散文与盘面对不上）",
            1,
            occurrences(rawSource(PARSER), "三枚警告计数在这里全部丢掉"),
        )
    }

    // ─────────────── ② 判据本体逐字节 ───────────────

    @Test
    fun `星期与节次两手逐字节各一枚 节次那一手不许挂兜底`() {
        val parser = source(PARSER)

        for (anchor in listOf(DAY_LINE, START_LINE, END_LINE, COUNTER_HANDOVER)) {
            assertEquals(
                "**钉法 = 逐字节**：「$anchor」在 `$PARSER` 恰好一处（实到 " + occurrences(parser, anchor) +
                    " 处）：两把尺收成一把，靠的就是星期与节次这两行**同一种死法** —— " +
                    "逐字钉住才拦得住「换个写法又兜回去」那一手（`return@forEachIndexed` 那一枚针" +
                    "全文件有 " + occurrences(parser, DROP_LINE) + " 处，枚数不是这一格的判据，" +
                    "它归第 ③ 层在循环体里数）",
                1,
                occurrences(parser, anchor),
            )
        }
        assertEquals(
            "改前那一形（`item.beginSection ?: 1`）必须 0 处（实到 " + occurrences(parser, OLD_SHAPE) +
                " 处）。⚠️ 姊妹守卫 `IcsSectionPolicyGuardTest` 第 ① 层按**形状**也钉这一枚（T229 起" +
                "`beginSection` 已进那把尺）—— 这里再钉一次逐字，是因为形状那把尺改天可能被写窄，" +
                "而这两枚落点是同一件事的两半",
            0,
            occurrences(parser, OLD_SHAPE),
        )
        assertEquals(
            "缺 end 而有 start 是**有出处**的单节，不许跟着一起丢：`$END_LINE` 恰好一处（实到 " +
                occurrences(parser, END_LINE) + " 处）。它一红就说明有人把两等混成一条规则" +
                "（`PeriodRangeBoundsTest.buaaParserMissingEndSectionFallsBackToStart` 是行为侧的备份）",
            1,
            occurrences(parser, END_LINE),
        )
    }

    // ─────────────── ③ 读形状：同一枚块里两手同尺 ───────────────

    @Test
    fun `循环体内两手都走同一条 return 且节次取法后面没有兜底`() {
        val parser = source(PARSER)
        val body = loopBody(parser)

        assertEquals(
            "体内 `$DROP_LINE` 恰好 " + BODY_RETURN_TOTAL + " 处（实到 " + occurrences(body, DROP_LINE) +
                " 处）：星期一枚 + 节次一枚 + 空 periods 一枚，**三手同一条 return**。" +
                "变成两枚就说明有一手被换成兜底或换成抛",
            BODY_RETURN_TOTAL,
            occurrences(body, DROP_LINE),
        )
        val dayAt = body.indexOf(DAY_ANCHOR)
        val startAt = body.indexOf(START_ANCHOR)
        val counterAt = body.indexOf("$LOCAL++")
        assertTrue(
            "顺序判据（本仓「顺序决定生死」那一族的老规矩）：`$START_ANCHOR`（index=$startAt）必须" +
                "长在 `$COUNTER_HANDOVER` 之前、且自增（index=$counterAt）必须紧在它后面 —— " +
                "自增跑到 return 之后就是**永远加不上**的死代码，账本空着而屏幕照旧不说话",
            dayAt in 0 until startAt && counterAt in 0 until body.indexOf(DROP_LINE, startAt),
        )
        // 体内的节次取法后面不许再挂任何东西（兜底 / 非空断言 / 默认值都算）
        val afterStart = body.substring(startAt + START_ANCHOR.length)
        val sameLine = afterStart.lineSequence().first()
        assertEquals(
            "`$START_ANCHOR` 那一行取完就到行尾（实到行尾「${sameLine.trim()}」）：" +
                "挂 `?: 1` 是病灶本体，挂 `!!` 是把假课换成崩溃，挂 `?: 0` 只是换个假节次 —— 三形都在这格红",
            "",
            sameLine.trim(),
        )
    }

    // ─────────────── ④ 报数：钉今天的事实，不钉愿望 ───────────────

    @Test
    fun `节次账今天还没进用户可见报数 接上它时必须改这一格`() {
        val screen = source(LOGIN_SCREEN)
        val session = source(WEB_SESSION)

        for ((text, path) in listOf(screen to LOGIN_SCREEN, session to WEB_SESSION)) {
            assertEquals(
                "`$COUNTER` 在 `$path` 必须 0 处（实到 " + occurrences(text, COUNTER) +
                    " 处）：计数今天只到 `ParseOutcome` 为止。**这一格不是「已修好」的凭证** —— " +
                    "卡面收口要求它「进报数」，而报数那一跳长在 `BuaaLoginScreen` 的 `parseWarnings` " +
                    "与 `BuaaWebSession` 的刷新链上，两份文件都在本卡白名单外 ⇒ 登记给编排者。" +
                    "**谁把它接上去，必须把这里的 0 改成非 0**（同 `BuaaScheduleParserTotalWeeksGuardTest` " +
                    "第 ④ 层的口径：把缺口钉成册子而不是钉成绿灯）",
                0,
                occurrences(text, COUNTER),
            )
        }
        // 含注释 / 抹注释两侧都是 0 ⇒ 连"历史说明里提一句"都没有（钉法④：两侧一起数）
        val raw = rawSource(LOGIN_SCREEN)
        assertEquals(
            "**钉法 = 抹注释找锚点**：同一枚针在**含注释**的 `$LOGIN_SCREEN` 原文里也是 0 处（实到 " +
                occurrences(raw, COUNTER) + " 处）：两侧一起钉是因为「代码没接、注释里先写上了」" +
                "那一形既骗得过抹注释的尺、也骗得过读人的眼睛",
            0,
            occurrences(raw, COUNTER),
        )
        assertEquals(
            "既有那两枚计数的册子不许被这次改动带跑：`fallbackWeekCourses` 全 main 恰好 " +
                SIBLING_TOTAL + " 处（实到 " + occurrencesOfAll(readMainSources(), "fallbackWeekCourses") +
                " 处）—— 它是姊妹守卫第 ③ 层的宿主，这里只做一次防串改的交叉核对",
            SIBLING_TOTAL,
            occurrencesOfAll(readMainSources(), "fallbackWeekCourses"),
        )
    }

    // ─────────────── ⑤ 反套套逻辑：探测器不是空转 ───────────────

    @Test
    fun `块体切分器与旧形探测器都不是空转`() {
        val sick = """
            items.forEachIndexed { index, item ->
                val day = item.dayOfWeek ?: return@forEachIndexed
                val start = item.beginSection ?: 1
                val end = item.endSection ?: start
            }
        """.trimIndent()
        val sickBody = braceBodyFrom(sick, sick.indexOf('{'))
        assertEquals(
            "内联正对照｜朝松：改前那三行喂进探测器，`$OLD_SHAPE` 必须被抓成 1 处（实到 " +
                occurrences(sickBody, OLD_SHAPE) + " 处）—— 抓不到的话第 ② 层那句「必须 0 处」是假的绿",
            1,
            occurrences(sickBody, OLD_SHAPE),
        )
        assertEquals(
            "内联正对照｜同一枚块里 `$DROP_LINE` 只有星期一枚（实到 " + occurrences(sickBody, DROP_LINE) +
                " 处）：病灶那一行走的不是 return ⇒ 第 ③ 层那格对改前的形状本来就是红的，它不是套套逻辑",
            1,
            occurrences(sickBody, DROP_LINE),
        )

        val fixed = """
            items.forEachIndexed { index, item ->
                val day = item.dayOfWeek ?: return@forEachIndexed
                val start = item.beginSection
                if (start == null) {
                    droppedNoSection++
                    return@forEachIndexed
                }
                val end = item.endSection ?: start
            }
        """.trimIndent()
        val fixedBody = braceBodyFrom(fixed, fixed.indexOf('{'))
        assertEquals(
            "内联负对照：修完之后那三形分别是 旧形 0 处 / return 2 处 / 自增 1 处（实到 " +
                occurrences(fixedBody, OLD_SHAPE) + " / " + occurrences(fixedBody, DROP_LINE) + " / " +
                occurrences(fixedBody, "$LOCAL++") + " 处）",
            listOf(0, 2, 1),
            listOf(occurrences(fixedBody, OLD_SHAPE), occurrences(fixedBody, DROP_LINE),
                occurrences(fixedBody, "$LOCAL++")),
        )
        assertTrue(
            "取块工具必须真能配平：盘面 `$PARSER_NAME` 的循环体非空（实到 " + loopBody(source(PARSER)).length +
                " 个字符）",
            loopBody(source(PARSER)).length > 800,
        )
    }

    // ---------------- 源码核对小工具（与仓内其它静态守卫同一口径）----------------

    /** 按花括号配平取 `items.forEachIndexed { index, item -> … }` 的体 */
    private fun loopBody(parser: String): String {
        val hits = indexOfAll(parser, LOOP_ANCHOR)
        assertEquals("`$LOOP_ANCHOR` 恰好一处（本层取块的宿主）：实到 " + hits.size + " 处", 1, hits.size)
        return braceBodyFrom(parser, parser.indexOf('{', hits.first()))
    }

    private fun filesHolding(byFile: Map<String, String>, needle: String): List<String> =
        byFile.filter { occurrences(it.value, needle) > 0 }.keys.toList().sorted()

    private fun occurrencesOfAll(byFile: Map<String, String>, needle: String): Int =
        byFile.values.sumOf { occurrences(it, needle) }

    private fun ledger(byFile: Map<String, String>, needle: String): String =
        byFile.entries.toList().sortedBy { it.key }
            .filter { occurrences(it.value, needle) > 0 }
            .joinToString(prefix = "{", postfix = "}") { "${it.key}=${occurrences(it.value, needle)}" }

    private fun source(relativeFromMainJava: String): String =
        blankCommentsKeepingLiterals(rawSource(relativeFromMainJava))

    private fun rawSource(relativeFromMainJava: String): String {
        val file = File(findMainJavaDir(), relativeFromMainJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    private fun readMainSources(): Map<String, String> {
        val root = findMainJavaDir()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${root.path} 下一个 .kt 都没有：路径不对，本守卫就是空的", files.isNotEmpty())
        val out = linkedMapOf<String, String>()
        files.forEach {
            check(!out.containsKey(it.name)) { "main 源码里有同名文件 ${it.name}，按文件名建册子会读错份" }
            out[it.name] = blankCommentsKeepingLiterals(it.readText().replace("\r\n", "\n"))
        }
        assertEquals("扫 main 源码的文件数与建出来的册子条目数不等", files.size, out.size)
        return out
    }

    private fun indexOfAll(haystack: String, needle: String): List<Int> {
        val hits = mutableListOf<Int>()
        if (needle.isEmpty()) return hits
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return hits
            hits += at
            from = at + needle.length
        }
    }

    private fun occurrences(haystack: String, needle: String): Int = indexOfAll(haystack, needle).size

    private fun braceBodyFrom(source: String, open: Int): String {
        check(source.getOrNull(open) == '{') { "锚点末尾不是花括号：切不出一段体（实到「${source.getOrNull(open)}」）" }
        return source.substring(open + 1, (matchingBrace(source, open) ?: error("花括号没配平：从 $open 起")))
    }

    private fun matchingBrace(src: String, open: Int): Int? {
        var depth = 0
        var i = open
        var inString = false
        var escaped = false
        while (i < src.length) {
            val c = src[i]
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
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
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
        const val PARSER = "com/buaa/schedule/data/import/BuaaScheduleParser.kt"
        const val PARSER_NAME = "BuaaScheduleParser.kt"
        const val LOGIN_SCREEN = "com/buaa/schedule/ui/importing/BuaaLoginScreen.kt"
        const val WEB_SESSION = "com/buaa/schedule/data/import/BuaaWebSession.kt"

        /** 第 ① 层：计数与局部变量 */
        const val COUNTER = "missingSectionCourses"
        const val LOCAL = "droppedNoSection"
        const val MAIN_COUNTER_TOTAL = 2
        const val PARSER_LOCAL_TOTAL = 3
        const val SIBLING_TOTAL = 5

        /** 第 ② 层：判据本体（逐字节） */
        const val DAY_LINE = "val day = item.dayOfWeek ?: return@forEachIndexed"
        const val START_LINE = "val start = item.beginSection"
        const val END_LINE = "val end = item.endSection ?: start"
        const val DROP_LINE = "return@forEachIndexed"
        const val COUNTER_HANDOVER = "missingSectionCourses = droppedNoSection"
        const val OLD_SHAPE = "item.beginSection ?: 1"

        /** 第 ① 层最后一格：薄壳那句 KDoc（散文必须与盘面枚数一致） */
        const val THIN_SHELL_DOC = "parseArrangedList"

        /** 第 ③ 层：取块锚点与顺序锚点 */
        const val LOOP_ANCHOR = "items.forEachIndexed { index, item ->"
        const val DAY_ANCHOR = "val day = item.dayOfWeek"
        const val START_ANCHOR = "val start = item.beginSection"
        const val BODY_RETURN_TOTAL = 3
    }
}
