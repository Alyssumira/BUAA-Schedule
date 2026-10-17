package com.buaa.schedule.ui.course

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T224 守卫第一把：**数出现次数（先抹注释再数）** —— 界面上不许再长出第三套「一门课」的定义。
 *
 * ## 病灶与这一把尺的形状
 *
 * 全站那一把尺是 [com.buaa.schedule.domain.schedule.SemesterStats.courseGroupKey]，而
 * `ui/course/CourseManagementScreen.kt` 从前带着一枚 `groupKeyOf`（键形 `manual:名称|星期|节次`），
 * 那是**全仓第二套「一门课」的定义**。`domain/schedule/SemesterStats.kt` 的 `creditsByCourse`
 * 同文件早就写过一句禁令：「归并仍然只在这一个函数里发生一次，界面上再 groupBy 一遍就是第二套
 * '一门课'的定义」。后果是用户看得见的三句打脸（详见 `CourseUnitTwoRulersTableTest` 段首那张表）。
 *
 * 本守卫钉的是**不回潮**，五档：
 * 1. 那枚就地定义（`fun groupKeyOf(`）与它那串键形字面（`"manual:${` 起、
 *    `|${course.dayOfWeek}|${course.periods.joinToString` 尾）在代码里 0 处，而改前那一枚**逐字**
 *    仍留在管理页的 KDoc 里当靶子；
 * 1b. 反向格：`domain/schedule/CourseConflictResolution.kt` 那枚**冲突指纹**
 *    （`v:名称|星期|节次|周次`）竖线长相一样、尺不一样（它数"同一处冲突"），逐字仍在且不被
 *    上面那枚尾 needle 命中 —— 尾针带到 `joinToString` 就是为了这一格；
 * 2. 界面层（`ui/` 与 `core/`）里 `.groupBy {` 的**册子**：枚数与落点一起钉，今天恰好两枚，
 *    而且那两枚都不定义"一门课"（一枚按星期排格子、一枚吃内核交回的组键）；
 * 3. 拼组键那串表达式（`(course.semesterCode ?: "") + "|" +`）全仓只许长在 SemesterStats 一枚文件里；
 * 4. 反向格：上面那两枚合法的 `groupBy` 必须逐字仍在 —— 探测器不许见 `groupBy` 就杀
 *    （杀掉等于把周视图的格子排序也换了，那不是本卡这一格）。
 *
 * **反欺骗**（本仓守卫的老规矩）：第 1 档那几枚 needle 在**含注释**的原文里必须仍有痕迹，
 * 否则"代码 0 处"可能只是注释被删干净了。
 *
 * 只做**源码核对**（JVM，无 Robolectric、无设备、不读时钟）。输入面：`src/main/java` 整目录已由
 * `app/build.gradle.kts` 里 `guardReadWorkingTreeFiles` 声明成 `testDebugUnitTest` 的输入 ⇒ 不必新增条目。
 */
class CourseUnitSingleSourceGuardTest {

    // ─────────────── ① 那枚就地定义与它的键形：抹注释后 0 处 ───────────────

    @Test
    fun `界面上那套第二定义已经抹掉 代码零处而注释留着靶子`() {
        val all = readAllMainSources()
        val main = all.mapValues { (_, text) -> blankCommentsKeepingLiterals(text) }

        // 定义本体：管理页从前那枚 `private fun groupKeyOf(course: Course): String =`
        val definitionFiles = main.filter { occurrences(it.value, DEFINITION_NEEDLE) > 0 }.keys.toList().sorted()
        assertEquals(
            "**钉法 = 数出现次数（抹注释后）**：main 里 `$DEFINITION_NEEDLE` 的定义册子必须是空 —— " +
                "它是全仓第二套「一门课」的定义，回来一枚就意味着页头那句「N 门课」与统计页那枚 courseCount " +
                "又各自成立。\n复算：grep -rn 'fun groupKeyOf(' app/src/main --include='*.kt'\n" +
                "实到册子：" + definitionFiles,
            emptyList<String>(),
            definitionFiles,
        )

        for ((needle, who) in listOf(
            LEGACY_KEY_SHAPE to "手动组那串键形字面（`\"manual:\" + 名称 + 星期 + 节次`）",
            LEGACY_KEY_TAIL to
                "手动组键形里那枚「星期夹在两根竖线之间、后面跟 periods 的 joinToString」的写法" +
                    "（照抄改前那一枚的换名不换尺写法也算；尾巴带到 `joinToString` 是为了不吞掉" +
                    "域里那枚同形状的**冲突指纹**，那一枚见反向格）",
            LEGACY_GROUP_CALL to "把片段就地 groupBy 的那一调用（`.groupBy { groupKeyOf(it) }`）",
        )) {
            val hits = main.filter { occurrences(it.value, needle) > 0 }
                .entries.joinToString(separator = "") { "  ${it.key}: ${occurrences(it.value, needle)} 处\n" }
            assertEquals(
                "**钉法 = 数出现次数（抹注释后）**：${who}在 main 的代码里必须 0 处 —— 这一族只要存在一枚，" +
                    "界面上就同时有两把尺在数同一条数据。\n实到：\n$hits",
                0,
                main.values.sumOf { occurrences(it, needle) },
            )
        }

        // 靶子（反欺骗）：改前那一形必须还在**注释**里留痕，否则上面那些 0 是注释被搬空换来的
        val rawPage = all.getValue(PAGE_PATH)
        assertEquals(
            "管理页的 KDoc 必须还逐字引用着改前那枚定义名（含注释原文里 `groupKeyOf` ≥1 处）。读到 0 = " +
                "注释被删干净 ⇒ 上面那枚「代码 0 处」就不是「归并换源了」而是「账被抹了」。实到：" +
                occurrences(rawPage, "groupKeyOf"),
            true,
            occurrences(rawPage, "groupKeyOf") >= 1,
        )
        for ((needle, name) in listOf(
            LEGACY_KEY_SHAPE to "改前那枚键形的开头",
            LEGACY_KEY_TAIL to "改前那枚键形的「星期|节次」尾巴",
            LEGACY_GROUP_CALL to "改前那一调用",
        )) {
            val raw = all.values.sumOf { occurrences(it, needle) }
            assertEquals(
                "靶子（反欺骗）：含注释的原文里 `$needle`（$name）必须还留痕 ≥1 处 —— 上面那枚「代码 0 处」" +
                    "若是靠**把注释里的改前原文一并删干净**换来的，病灶就查无对证了。且这一枚的枚数与代码档" +
                    "数出来的 0 相对：全部留痕都在注释里。实到：" + raw,
                true,
                raw >= 1,
            )
        }
    }

    // ─────────────── ①b 反向格：同形状那枚冲突指纹不许被当成第二把尺 ───────────────

    /**
     * needle 收窄到 `course.periods.joinToString` 那一尾，是为了放过
     * `domain/schedule/CourseConflictResolution.kt` 里那枚**冲突指纹**
     * （`v:名称|星期|节次|周次`，数的是"同一处冲突"不是"同一门课"）。
     * 这一格同时自证两件事：那枚指纹逐字仍在（本守卫没有顺手把它改了），
     * 而它在**自己文件**里对 `LEGACY_KEY_TAIL` 的命中数是 0（needle 不是靠模糊糊过去的）。
     */
    @Test
    fun `冲突指纹那一枚不是第二把尺 逐字仍在且不被needle命中`() {
        val raw = readMainSource(CONFLICT_FINGERPRINT)
        val code = blankCommentsKeepingLiterals(raw)
        assertEquals(
            "**钉法 = 逐字节**：冲突指纹那一枚 `" + FINGERPRINT_LITERAL + "` 必须逐字 1 处 —— " +
                "它带着同样的竖线长相，但归并的是「同一处冲突」；把它当成「第二套一门课的定义」删掉，" +
                "冲突条目就再也对不回同一枚指纹了。0 处 = 有人为了过本守卫把它改了（那是放宽，不是修）。\n" +
                "复算：grep -n 'v:\${course.name}' app/src/main/java/com/buaa/schedule/domain/schedule/CourseConflictResolution.kt",
            1,
            occurrences(code, FINGERPRINT_LITERAL),
        )
        assertEquals(
            "**钉法 = 数出现次数**：那一枚对病灶 needle `" + LEGACY_KEY_TAIL + "` 的命中必须是 0 —— " +
                "本卡第 ① 档的 tail 收窄到此为止（再宽一枚就会把这里误判成病灶，再窄就照不出改前那一形）",
            0,
            occurrences(code, LEGACY_KEY_TAIL),
        )
    }

    // ─────────────── ② 界面层 groupBy 的册子：枚数 + 落点一起钉 ───────────────

    @Test
    fun `界面层的就地分组册子恰好两枚 都不定义一门课`() {
        val uiAndCore = readSourcesUnder("ui") + readSourcesUnder("core")
        val code = uiAndCore.mapValues { (_, text) -> blankCommentsKeepingLiterals(text) }

        val booklet = code
            .filter { occurrences(it.value, GROUP_BY) > 0 }
            .entries
            .sortedBy { it.key }
            .joinToString(separator = "") { entry ->
                val raw = uiAndCore.getValue(entry.key)
                indexOfAll(entry.value, GROUP_BY).joinToString(separator = "") { at ->
                    "  ${entry.key} | " + raw.lineSequence().elementAtOrNull(lineOf(raw, at) - 1)?.trim() + "\n"
                }
            }
        assertEquals(
            "**钉法 = 数出现次数 + 读形状（册子逐枚列落点与整行原文）**：`ui/` 与 `core/` 里 `.groupBy {` " +
                "今天恰好两枚，都不定义「一门课」—— 周视图那枚按**星期**排格子、统计钻取那枚吃内核交回的 " +
                "`groupKey`。\n长出第三枚 = 界面上又就地归并了一遍（第二套「一门课」的定义，SemesterStats " +
                "文件头那句禁令防的就是它）；少一枚 = 那两处合法的分组被顺手改了（本守卫不许朝这个方向收窄）。\n" +
                "复算：grep -rn '\\.groupBy {' app/src/main/java/com/buaa/schedule/ui app/src/main/java/" +
                "com/buaa/schedule/core --include='*.kt'\n实到册子：\n$booklet",
            EXPECTED_GROUP_BY_BOOKLET,
            booklet,
        )
        assertEquals(
            "**钉法 = 数出现次数**：册子的枚数单独再数一遍（只钉落点的话，「同一行里两处 groupBy」这一型数不出来）",
            2,
            code.values.sumOf { occurrences(it, GROUP_BY) },
        )
    }

    /** 反向格：那两枚合法的 groupBy 逐字仍在（探测器不许把它们当病灶杀掉） */
    @Test
    fun `两枚合法的分组逐字仍在 探测器不许见groupBy就杀`() {
        val weekView = blankCommentsKeepingLiterals(readMainSource(WEEK_VIEW))
        val drill = blankCommentsKeepingLiterals(readMainSource(STATS_DRILL_COPY))

        assertEquals(
            "**钉法 = 逐字节**：周视图那句 `coursesForContent.groupBy { it.dayOfWeek }` 必须逐字一枚 —— " +
                "它按星期分格子，与「一门课」无关；本守卫第 ② 档把它列进册子是因为它**合法**，" +
                "把它顺手改掉等于动了周视图的排格那把尺（不属于本卡）",
            1,
            occurrences(weekView, "groupBy { it.dayOfWeek }"),
        )
        assertEquals(
            "**钉法 = 逐字节**：统计钻取那句 `day.meetings.groupBy { it.groupKey }` 必须逐字一枚 —— " +
                "它吃的是内核（WeekDaySchedule）交回的组键，不是自己拼一枚新键；" +
                "把它换成「按名字再归并一遍」就是第二套定义的换壳",
            1,
            occurrences(drill, "groupBy { it.groupKey }"),
        )
    }

    // ─────────────── ③ 拼组键那串表达式只许长在内核 ───────────────

    @Test
    fun `组键那串拼接表达式全仓只在内核一处 界面只许调用它`() {
        val main = readAllMainSources()
        val files = main
            .filter { occurrences(blankCommentsKeepingLiterals(it.value), KEY_EXPRESSION) > 0 }
            .keys
            .toList()
            .sorted()
        val total = main.values.sumOf { occurrences(blankCommentsKeepingLiterals(it), KEY_EXPRESSION) }
        assertEquals(
            "**钉法 = 读形状 + 数出现次数**：「学期码 + 竖线 + 组键（没有就退化成课程名）」那一串组键拼接表达式" +
                "在 main 的代码里恰好 1 处、且落在 SemesterStats.kt。多一处 = 又一枚键在别处被重新拼出来" +
                "（两把尺的机制本体）；0 处 = 内核那一处被挪家，本守卫与所有按组键数门的账都要重钉。\n" +
                "复算：grep -rn 'semesterCode ?: \"\"' app/src/main --include='*.kt'\n" +
                "实到 $total 处，册子：$files",
            listOf("SemesterStats.kt"),
            files.map { it.substringAfterLast('/') },
        )
        assertEquals(
            "**钉法 = 数出现次数**：上面那枚落点之外，枚数本身也必须恰好 1 —— 落点册子与枚数是两把尺，" +
                "只钉落点会放过「同一文件里拼两遍」那一型。实到：" + total,
            1,
            total,
        )
    }

    // ─────────────── ④ 内核取数落点的册子：界面只许叫这几个名字 ───────────────

    @Test
    fun `界面层归并入口册子 管理页一枚 统计页与设置页各读同一本账`() {
        val code = readAllMainSources()
            .filter { it.key.startsWith("ui/") }
            .mapValues { (_, text) -> blankCommentsKeepingLiterals(text) }

        val groupingCallers = code.filter { occurrences(it.value, CREDITS_BY_COURSE) > 0 }
            .entries.sortedBy { it.key }
            .joinToString(separator = "") { "  ${it.key}: ${occurrences(it.value, CREDITS_BY_COURSE)} 处\n" }
        assertEquals(
            "**钉法 = 读形状（落点册子）+ 数出现次数**：`SemesterStats.creditsByCourse(` 在 `ui/` 下恰好落在" +
                "课表管理页一枚文件、一枚调用点 —— 归并只发生一次，交回的 `CourseCredit.fragments` 就是那张卡。" +
                "长出第二处 = 又有一条界面链自己归并一遍（要连本卡第 ①② 档一起重判）；少一处 = 管理页不回内核了，" +
                "页头那句「N 门课」重新变成第二把尺。\n" +
                "复算：grep -rn 'SemesterStats.creditsByCourse(' app/src/main/java/com/buaa/schedule/ui --include='*.kt'\n" +
                "实到册子：\n$groupingCallers",
            "  $PAGE_PATH: 1 处\n",
            groupingCallers,
        )

        val summarizeCallers = code.filter { occurrences(it.value, SUMMARIZE_CALL) > 0 }.keys.toList().sorted()
        assertEquals(
            "**钉法 = 数出现次数（落点册子）**：`ui/` 下拿 `SemesterStats.summarize` 当「门与段的账」的页面今天两枚" +
                "—— 统计页与设置页（后者自 T224 起吃同一本账的 courseCount / fragmentCount，不再拿 `.size` 念门）。" +
                "第三枚进来要先回答「它念的那两句吃的是哪格具名读数」，不许顺手拿 `.size` 顶。\n" +
                "复算：grep -rn 'SemesterStats.summarize(' app/src/main/java/com/buaa/schedule/ui --include='*.kt'\n" +
                "实到册子：" + summarizeCallers,
            listOf("ui/settings/SettingsScreen.kt", "ui/stats/StatsScreen.kt"),
            summarizeCallers,
        )
    }

    // ---------------- helpers（与本仓守卫同一套写法） ----------------

    private fun readMainSource(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /** 全仓 main 源码：相对 `com/buaa/schedule/` 的路径 → 内容 */
    private fun readAllMainSources(): Map<String, String> = readSourcesUnder(".")

    /** 某个子树（相对 `app/src/main/java/com/buaa/schedule/`）下的 .kt 源码；"." = 整棵 main */
    private fun readSourcesUnder(subtree: String): Map<String, String> {
        val root = File(findMainJavaDir(), "com/buaa/schedule")
        assertTrue("找不到 $root：包结构挪过家，本守卫要跟着改", root.isDirectory)
        val target = if (subtree == ".") root else File(root, subtree)
        assertTrue("找不到子树 $target", target.isDirectory)
        val files = target.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val out = linkedMapOf<String, String>()
        files.forEach {
            val relative = root.toPath().relativize(it.toPath()).toString().replace(File.separatorChar, '/')
            check(!out.containsKey(relative)) { "main 源码里有同一路径 $relative，建册子会读错份" }
            out[relative] = it.readText()
        }
        assertEquals("扫 $target 的文件数与建出来的册子条目数不等", files.size, out.size)
        assertTrue("扫 $target 扫出 0 份文件", out.isNotEmpty())
        return out
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

    /** 那一处落在原文的第几行（失败消息要把人带到那一处，只报个数等于没有守卫） */
    private fun lineOf(rawSource: String, position: Int): Int =
        if (position < 0) -1 else rawSource.substring(0, position).count { it == '\n' } + 1

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
        const val PAGE_PATH = "ui/course/CourseManagementScreen.kt"
        const val WEEK_VIEW = "com/buaa/schedule/ui/home/WeekView.kt"
        const val STATS_DRILL_COPY = "com/buaa/schedule/ui/stats/StatsDrillCopy.kt"

        /** 病灶：管理页那枚就地定义与它的键形（钉的是代码，注释里留着靶子） */
        const val DEFINITION_NEEDLE = "fun groupKeyOf("
        const val LEGACY_KEY_SHAPE = "\"manual:\${"

        /**
         * 「星期夹在两根竖线之间」那一形。尾巴带到 `course.periods.joinToString` 是**故意的**：
         * 改前病灶那一枚（管理页的 `manual:名称|星期|节次`）正好写在 `joinToString` 上，收在这一尾
         * 才照得出它，而不再往前吞别处合法的写法。
         *
         * `domain/schedule/CourseConflictResolution.kt` 里那枚**冲突指纹**（逐字节钉在下面的
         * `FINGERPRINT_LITERAL`，反向格见 `冲突指纹那一枚不是第二把尺 反向格逐字仍在`）自 T412/#394
         * 起委托 `ImportPlanner.courseKey` 再追加周次：它吃的字段集与「同一门课」那份**是同一份**，
         * 但它仍是**冲突侧的键**（数的是"同一处冲突"），不是界面归并那把尺 ⇒ 依旧不在本尺的
         * `groupBy` 普查面上。星期与节次都挪进了 `courseKey`，那枚「星期夹在两根竖线之间」的
         * 就地插值形状在冲突指纹文件里已不存在 ⇒ 对这枚尾针的命中仍是 0（这一格同时在数它）。
         */
        const val LEGACY_KEY_TAIL = "|\${course.dayOfWeek}|\${course.periods.joinToString"
        const val LEGACY_GROUP_CALL = ".groupBy { groupKeyOf(it) }"

        /**
         * 反向格：那枚该活着的冲突指纹逐字。它与「同一门课」共用 `ImportPlanner.courseKey` 那份
         * 字段集 + 周次，但仍是冲突侧那把尺（数"同一处冲突"）。放过它是按"尺不同"判的，
         * 本守卫的 needle 收窄不是为了糊过去。
         */
        const val CONFLICT_FINGERPRINT = "com/buaa/schedule/domain/schedule/CourseConflictResolution.kt"
        const val FINGERPRINT_LITERAL = "\"v:\" + ImportPlanner.courseKey(course) + \"|\" + course.weeks.joinToString(\",\")"

        /** 合法的界面分组只有这两枚（一枚按星期、一枚吃内核组键） */
        const val GROUP_BY = ".groupBy {"
        const val KEY_EXPRESSION = "(course.semesterCode ?: \"\") + \"|\" +"
        const val CREDITS_BY_COURSE = "SemesterStats.creditsByCourse("
        const val SUMMARIZE_CALL = "SemesterStats.summarize("

        /**
         * 第 ② 档那本册子的期望值：路径 + 那一行整行原文（**不写行号** ——
         * 行号会被上面那批插行推着走，而这一档钉的是"谁在就地分组"这件事）。
         * 复算：grep -rn '\.groupBy {' app/src/main/java/com/buaa/schedule/{ui,core} --include='*.kt'
         */
        const val EXPECTED_GROUP_BY_BOOKLET =
            "  ui/home/WeekView.kt | val coursesByDay = remember(coursesForContent) { " +
            "coursesForContent.groupBy { it.dayOfWeek } }\n" +
            "  ui/stats/StatsDrillCopy.kt | val doubled = day.meetings.groupBy { it.groupKey }" +
            ".filterValues { it.size > 1 }\n"
    }
}
