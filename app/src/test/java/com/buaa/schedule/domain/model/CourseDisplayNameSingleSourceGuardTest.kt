package com.buaa.schedule.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T383③ 单一真源守卫：「别名非空才作数」这一档在 main 侧只许有一枚落点，
 * 而实体与课程管理页那两手都必须读它。
 *
 * 裁定（第⓪问的量出来的根据，复算命令逐格写在报错消息里）：
 * - 真源 = [Course] 类体的 `displayNameOrNull`（值 = f(同表参数 alias)，按本仓派生字段口径
 *   只有类体属性这一条合法落点，与 `startPeriod` / `firstPeriodOrNull` / `isManualCourse` 同为薄壳）；
 * - 实体那一手 = 同一枚类体的 `displayName`，回退本行 `name`；
 * - 组那一手 = `CourseManagementScreen.kt` 的 `groupDisplayNameOf`，扫整组、回退门面碎片，
 *   **落点留在归并点**：它既不搬进 `CourseGroup` 的类体（类体只能读到排过序的 `fragments`，
 *   两枚片段各带别名时会念出另一枚 —— 行为格在关系守卫那边），也不走「每个 copy 站点配守卫」
 *   那一档，因为该 data class 今天全仓 main 零枚 copy 站点（第 ⑥ 格实测）。
 *
 * 本文件一格都不钉「行为」：两手在 `别名只挂在非门面碎片` 那一档必须给**不同**答案，
 * 那是行为、不是重复，归 `ui/course/CourseDisplayNameRelationTableTest`。
 *
 * 纯 JVM：只读盘上的 `.kt` 文本，零 android import、不读钟、不读时区、不读权限、不读设备度量。
 * 计数分两栏打印：raw（含注释原文）与 code（抹注释、保留字符串字面量），写死的期望只钉 code 栏 ——
 * 散文能顶高 raw，顶不动尺子。
 */
class CourseDisplayNameSingleSourceGuardTest {

    // ─────────────── ① 真源本体：类体里一枚，回退形逐字钉住 ───────────────

    @Test
    fun `真源本体在 Course 类体只有一处 回退形逐字钉住`() {
        val code = blankCommentsKeepingLiterals(readMainSource(COURSE))
        val body = classBodyOf(code, COURSE_DECLARATION, COURSE_NAME)

        val declaration = occurrences(body, SINGLE_SOURCE_DECLARATION)
        assertEquals(
            "钉法 = 读形状（同级声明切块）+ 逐字节：`Course` 类体里那一枚真源必须逐字是\n" +
                "    $SINGLE_SOURCE_DECLARATION\n" +
                "实到 $declaration 处。切块原文（类体里所有 `val` 开头的那几行，抹注释后逐行）：\n" +
                siblingLinesBody(body) + "\n" +
                "红了就是有人改了 trim 或判空（那是换口径，不是搬家），或者把它挪出类体另起一形。\n" +
                "复算：grep -Fn '$SINGLE_SOURCE_DECLARATION' app/src/main/java/$COURSE",
            1,
            declaration,
        )

        val fallback = occurrences(body, ENTITY_FALLBACK)
        assertEquals(
            "钉法 = 逐字节：实体那一手的回退必须是「回退读真源」，逐字\n" +
                "    $ENTITY_FALLBACK\n" +
                "实到 $fallback 处 —— 回退自己再 trim 一遍就是第二遍。\n" +
                "复算：grep -Fn '$ENTITY_FALLBACK' app/src/main/java/$COURSE",
            1,
            fallback,
        )

        val lesionInEntity = occurrences(code, ENTITY_LESION)
        assertEquals(
            "钉法 = 数出现次数（抹注释后）：Course.kt 里不许再出现旧回退形「$ENTITY_LESION」（实到 " +
                "$lesionInEntity 处）。这一枚 0 的正控就是上面那两枚各 1 —— 真源声明与回退形都在场，" +
                "说明文件读到了、类体切出来了，不是读空才念 0。\n" +
                "复算：grep -Fn '$ENTITY_LESION' app/src/main/java/$COURSE",
            0,
            lesionInEntity,
        )
    }

    // ─────────────── ② 第二遍探测器：全仓 main 只剩一枚手写落点 ───────────────

    @Test
    fun `别名自己 trim 判空的手写落点 全仓 main 只剩一枚`() {
        val codeLedger = mainCodeByFile().mapValues { occurrences(it.value, ALIAS_TRIM) }.filterValues { it > 0 }
        val rawLedger = mainRawByFile().mapValues { occurrences(it.value, ALIAS_TRIM) }.filterValues { it > 0 }
        val expected = mapOf(COURSE_NAME to 1)

        assertEquals(
            "钉法 = 数出现次数（落点册子，抹注释后）：全仓 main 里「别名自己 trim 再判空」那一档" +
                "（针 = `$ALIAS_TRIM`）只许**一枚**落点，就是 Course 类体的真源。" +
                "期望册子 $expected，实到 code 栏 $codeLedger ／ raw 栏 $rawLedger（写死只钉 code 栏）。" +
                "多出来的一枚就是第二遍；少一枚就是真源被换了写法。\n" +
                "复算（raw 栏那一把）：grep -rn '$ALIAS_TRIM' app/src/main --include=*.kt\n" +
                "改前这一把在 main 侧念 2 枚：Course 的 `displayName` 一枚、管理页的 `groupDisplayNameOf` 一枚" +
                "（T383①② 落成 1 枚）。写侧那枚「保存前把别名裁成 null」不在这根针上（它不 trim 可空串、" +
                "是编辑器存库那一手），普查见本卡回执。",
            expected,
            codeLedger,
        )
        assertEquals(
            "册子总枚数单独再数一次（防「文件名对不上但和数恰好也是 1」那种假绿）：期望 " +
                expected.values.sum() + " 枚，实到 code 栏 " + codeLedger.values.sum() +
                " 枚 ／ raw 栏 " + rawLedger.values.sum() + " 枚",
            expected.values.sum(),
            codeLedger.values.sum(),
        )
    }

    // ─────────────── ③ 管理页那一手：读真源、病灶写形零处 ───────────────

    @Test
    fun `管理页那一手读真源 组扫描语义一字未动`() {
        val code = blankCommentsKeepingLiterals(readMainSource(PAGE))

        val scan = occurrences(code, GROUP_SCAN)
        val fallback = occurrences(code, GROUP_FALLBACK)
        assertEquals(
            "钉法 = 逐字节：组那一手的「扫整组」那一半必须逐字是 `$GROUP_SCAN`（实到 $scan 处），" +
                "回退那一半必须逐字是 `$GROUP_FALLBACK`（实到 $fallback 处）—— 两半各一枚。" +
                "这两半是管理页自己的语义（谁的别名作数、不作数时念门面碎片的名字），本卡没动、" +
                "也不许被拿去跟实体那一手统一。\n" +
                "复算：grep -Fn '$GROUP_SCAN' app/src/main/java/$PAGE 与 grep -Fn '$GROUP_FALLBACK' app/src/main/java/$PAGE",
            1 to 1,
            scan to fallback,
        )

        val handWrittenAliasRule = occurrences(code, PAGE_LESION)
        assertEquals(
            "钉法 = 数出现次数（抹注释后）：管理页里「片段自己的别名」那一枚读法 `$PAGE_LESION` 必须 0 处" +
                "（实到 $handWrittenAliasRule 处）—— 这一枚 0 的正控是上面那两枚各 1，" +
                "再加下面两枚反欺骗尺。长出一枚就是第二遍又回来了。\n" +
                "复算：grep -Fn '$PAGE_LESION' app/src/main/java/$PAGE",
            0,
            handWrittenAliasRule,
        )

        assertEquals(
            "反欺骗（不许把上面那枚 0 当成「文件被读空」）：归并入口 `internal fun groupCourses(` " +
                "在管理页必须恰好一枚，实到 " + occurrences(code, "internal fun groupCourses(") + " 处",
            1,
            occurrences(code, "internal fun groupCourses("),
        )
        assertEquals(
            "反欺骗第二枚：真源那枚具名读数必须被这一页读到（`it.displayNameOrNull` 恰一枚，实到 " +
                occurrences(code, "it.displayNameOrNull") + " 处）—— 它与上面那枚 0 同时归零，" +
                "就是这一页整枚被读空了，不许当成绿",
            1,
            occurrences(code, "it.displayNameOrNull"),
        )
    }

    // ─────────────── ④ 同级声明册子：Course 类体长出／换形的每一枚都点名 ───────────────

    @Test
    fun `Course 类体的同级声明册子 新长出的那枚在册`() {
        val code = blankCommentsKeepingLiterals(readMainSource(COURSE))
        val body = classBodyOf(code, COURSE_DECLARATION, COURSE_NAME)
        val actual = SIBLING_DECLARATION.findAll(body).map { it.groupValues[1] }.toList()
        val expected = listOf(
            "startPeriod",
            "endPeriod",
            "firstPeriodOrNull",
            "lastPeriodOrNull",
            "displayName",
            "displayNameOrNull",
            "isManualCourse",
        )

        assertEquals(
            "钉法 = 读形状（同级声明切块）：`Course` 类体里以 `val` 开头的同级属性，按声明次序应当逐枚是 " +
                expected + "（实到 " + actual + "）。\n" +
                "本格就是「守卫按同级声明切块」那条规矩的落点：T383① 在 `displayName` 之后新长出**一枚同名族薄壳**，" +
                "凡按声明块／字面串切 Course 类体的守卫都得跟着重钉，这本册子就是那把尺本身。" +
                "次序也钉 —— 真源必须排在 `displayName` 之后、`isManualCourse` 之前。\n" +
                "切块原文（类体里 `val` 开头的那几行，取自抹注释后的文本）：\n" + siblingLinesBody(body) + "\n" +
                "复算：grep -nE '^    val [A-Za-z]' app/src/main/java/$COURSE",
            expected,
            actual,
        )
    }

    // ─────────────── ⑤ 门面向名的落点：留在归并点，字段仍在参数表 ───────────────

    @Test
    fun `门面向名的落点留在归并点 字段仍在参数表`() {
        val code = blankCommentsKeepingLiterals(readMainSource(PAGE))

        assertEquals(
            "钉法 = 逐字节：归并点必须逐字写 `$GROUP_CALL_SITE`（实到 " + occurrences(code, GROUP_CALL_SITE) +
                " 处）—— 门面名是在这里、用**未排序**的那一手片段表算出来的。有人把它搬进 `CourseGroup` " +
                "的类体（改读排过序的 `fragments`）就红这一格：两枚片段各带一枚不同别名时会念出另一枚，" +
                "那一档的行为账在 `ui/course/CourseDisplayNameRelationTableTest`。\n" +
                "复算：grep -Fn '$GROUP_CALL_SITE' app/src/main/java/$PAGE",
            1,
            occurrences(code, GROUP_CALL_SITE),
        )
        assertEquals(
            "钉法 = 逐字节 + 读形状：`CourseGroup` 的这枚字段仍在**参数表**里，逐字 `$GROUP_FIELD`（实到 " +
                occurrences(code, GROUP_FIELD) + " 处），且声明头 `$GROUP_DECLARATION` 仍在（实到 " +
                occurrences(code, GROUP_DECLARATION) + " 处）。两枚正控同在此格 —— 参数表字段与类体属性" +
                "是互斥形状，逐字针在就退不到类体那一形。\n" +
                "复算：grep -Fn '$GROUP_FIELD' app/src/main/java/$PAGE 与 grep -n '$GROUP_DECLARATION' app/src/main/java/$PAGE",
            1 to 1,
            occurrences(code, GROUP_FIELD) to occurrences(code, GROUP_DECLARATION),
        )
    }

    // ─────────────── ⑥ copy 站点册子（派生字段铁律的那一半） ───────────────

    @Test
    fun `这枚字段唯一的漂移方式今天零枚 copy 站点`() {
        val copyLedger = mainCodeByFile().mapValues { occurrences(it.value, COPY_CALL) }.filterValues { it > 0 }
        val pageCode = copyLedger[PAGE_NAME] ?: 0
        val courseDecls = occurrences(mainCodeByFile().getValue(COURSE_NAME), "data class Course(")

        assertEquals(
            "钉法 = 数出现次数（落点册子，抹注释后）：管理页今天的 `.copy(` 只许**一枚**，就是换色那枚 " +
                "`primary.copy(colorIndex = index, customColorArgb = null),`（它 copy 的是 Course 片段，" +
                "与门面名无关）。实到管理页 $pageCode 枚 ／ 全册 $copyLedger。" +
                "「每个 copy 站点配守卫」这一档在 `CourseGroup` 上是空集：给 `CourseGroup` 长出第二枚 copy " +
                "而不重算门面名，就是这枚参数表字段唯一的漂移方式，所以这一格不许退成 0 也不许多出一枚。\n" +
                "这一枚的非空证据（防「册子读空了才念小数字」）：同一把尺在 $COURSE_NAME 上念 " +
                "`data class Course(` 为 $courseDecls 枚（不为零），main 册子共 " +
                "${mainCodeByFile().size} 枚文件。\n" +
                "复算：grep -rn '\\.copy(' app/src/main/java/$PAGE",
            1,
            pageCode,
        )
        assertTrue(
            "册子不许读空：main 侧扫到的 .kt 枚数必须大于 100（实到 " + mainCodeByFile().size + " 枚）—— " +
                "读空了上面那枚 1 就是假的",
            mainCodeByFile().size > 100,
        )
    }

    // ─────────────── ⑦ 现量行号（写死之前在这棵树上 grep -n 量的） ───────────────

    @Test
    fun `现量行号 真源与两手各在其位`() {
        val courseLines = readMainSource(COURSE).lines().map { it.trim() }
        val pageLines = readMainSource(PAGE).lines().map { it.trim() }

        assertEquals(
            "现量行号（$COURSE_NAME）：实体回退必须落在裸 :$COURSE_ENTITY_LINE，逐字 `$ENTITY_FALLBACK`。" +
                "实到该行：「${courseLines.getOrElse(COURSE_ENTITY_LINE - 1) { "<越界>" }}」\n" +
                "复算：awk 'NR==$COURSE_ENTITY_LINE' app/src/main/java/$COURSE",
            ENTITY_FALLBACK,
            courseLines[COURSE_ENTITY_LINE - 1],
        )
        assertEquals(
            "现量行号（$COURSE_NAME）：真源声明必须落在裸 :$COURSE_SOURCE_LINE，逐字 `$SINGLE_SOURCE_DECLARATION`。" +
                "实到该行：「${courseLines.getOrElse(COURSE_SOURCE_LINE - 1) { "<越界>" }}」\n" +
                "复算：awk 'NR==$COURSE_SOURCE_LINE' app/src/main/java/$COURSE",
            SINGLE_SOURCE_DECLARATION,
            courseLines[COURSE_SOURCE_LINE - 1],
        )
        assertEquals(
            "现量行号（$PAGE_NAME）：组那一手的签名在裸 :$PAGE_SCAN_LINE、体在裸 :$PAGE_BODY_LINE、" +
                "回退在裸 :$PAGE_FALLBACK_LINE。实到：「${pageLines.getOrElse(PAGE_BODY_LINE - 1) { "<越界>" }}」\n" +
                "复算：awk 'NR>=$PAGE_SCAN_LINE && NR<=$PAGE_FALLBACK_LINE' app/src/main/java/$PAGE",
            GROUP_SCAN,
            pageLines[PAGE_BODY_LINE - 1],
        )
        assertEquals(
            "现量行号（$PAGE_NAME）：`CourseGroup` 的声明头在裸 :$PAGE_GROUP_LINE。" +
                "实到该行：「${pageLines.getOrElse(PAGE_GROUP_LINE - 1) { "<越界>" }}」\n" +
                "复算：grep -n '$GROUP_DECLARATION' app/src/main/java/$PAGE",
            GROUP_DECLARATION,
            pageLines[PAGE_GROUP_LINE - 1],
        )
    }

    // ─────────────── 读文件与文本工具 ───────────────

    private data class KotlinFile(val name: String, val text: String)

    /** 全仓 main 的 `.kt` 原文（文件名 → 文本），读空了当场红 */
    private fun mainFiles(): List<KotlinFile> {
        val root = File(findMainJavaDir(), "com/buaa/schedule")
        assertTrue("找不到 $root：包结构挪过家，本守卫要跟着改", root.isDirectory)
        val files = root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
            .map { KotlinFile(it.name, it.readText()) }
            .toList()
        assertTrue("扫 main 源码扫出 0 份文件：$root —— 读空了不许当绿", files.isNotEmpty())
        return files
    }

    /** 抹注释、保留字符串字面量那一份册子（文件名 → 文本）：写死的期望只钉这一栏 */
    private fun mainCodeByFile(): Map<String, String> =
        mainFiles().associate { it.name to blankCommentsKeepingLiterals(it.text) }

    /** 同一把尺的 raw 栏（含注释原文）：只并排打印，不写死期望 */
    private fun mainRawByFile(): Map<String, String> = mainFiles().associate { it.name to it.text }

    /** 类体：声明头的参数表收尾括号之后那枚 `{` 起，配平到对应 `}`，取其间整段 */
    private fun classBodyOf(code: String, declaration: String, label: String): String {
        val at = code.indexOf(declaration)
        assertTrue(
            "$label 里找不到声明头 `$declaration`：切不出类体就无法按同级声明说话（不许退回裸 grep 计数）。\n" +
                "复算：grep -n '$declaration' app/src/main/java/$COURSE",
            at >= 0,
        )
        val closeParen = balancedEnd(code, at + declaration.length - 1, '(', ')')
        val openBrace = code.indexOf('{', closeParen)
        assertTrue(
            "$label 的声明头之后找不到类体的 `{`（参数表收在 :" + closeParen + "，那枚位置实到字符「" +
                code.getOrNull(openBrace) + "」）",
            openBrace >= 0,
        )
        val closeBrace = balancedEnd(code, openBrace, '{', '}')
        return code.substring(openBrace + 1, closeBrace)
    }

    /** 从 [open] 那枚左括号起配平到对应右括号，返回其下标 */
    private fun balancedEnd(text: String, open: Int, left: Char, right: Char): Int {
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                left -> depth++
                right -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        throw IllegalStateException("从 :$open 起配不平「$left$right」那一对括号")
    }

    /** 切块原文用：把类体里以 `val ` 开头的那几行逐行贴出来（报错消息里要的正是这份原文） */
    private fun siblingLinesBody(body: String): String = buildString {
        body.lines().forEach { line ->
            val text = line.trim()
            if (text.startsWith("val ")) append("    ").append(text).append('\n')
        }
    }

    /** 只抹注释、保留字符串字面量，长度与换行位置一字不动（行号因此仍然可信） */
    private fun blankCommentsKeepingLiterals(source: String): String {
        val blanked = BooleanArray(source.length)
        var i = 0
        while (i < source.length) {
            when {
                source.startsWith("//", i) -> {
                    val nl = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                    for (k in i until nl) blanked[k] = true
                    i = nl
                }

                source.startsWith("/*", i) -> {
                    var depth = 1
                    var j = i + 2
                    while (j < source.length && depth > 0) {
                        when {
                            source.startsWith("/*", j) -> { depth++; j += 2 }
                            source.startsWith("*/", j) -> { depth--; j += 2 }
                            else -> j++
                        }
                    }
                    for (k in i until j.coerceAtMost(source.length)) blanked[k] = true
                    i = j
                }

                source.startsWith("\"\"\"", i) -> {
                    i = source.indexOf("\"\"\"", i + 3).let { if (it < 0) source.length else it + 3 }
                }

                source[i] == '"' || source[i] == '\'' -> {
                    val quote = source[i]
                    var j = i + 1
                    while (j < source.length) {
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
        return buildString(source.length) {
            for (index in 0 until source.length) {
                val char = source[index]
                append(if (blanked[index] && char != '\n') ' ' else char)
            }
        }
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

    private companion object {
        const val COURSE = "com/buaa/schedule/domain/model/Course.kt"
        const val PAGE = "com/buaa/schedule/ui/course/CourseManagementScreen.kt"
        const val COURSE_NAME = "Course.kt"
        const val PAGE_NAME = "CourseManagementScreen.kt"

        const val COURSE_DECLARATION = "data class Course("

        /** 真源本体（值 = f(同表参数 alias) 的类体属性那一形） */
        const val SINGLE_SOURCE_DECLARATION =
            "val displayNameOrNull: String? get() = alias?.trim()?.takeIf { it.isNotEmpty() }"

        /** 实体那一手：回退读真源 */
        const val ENTITY_FALLBACK = "get() = displayNameOrNull ?: name"

        /** 改前实体那一手的旧形：真源抽走之后必须 0 处 */
        const val ENTITY_LESION = "?.takeIf { it.isNotEmpty() } ?: name"

        /** 第二遍探测器那根针（写侧那枚「保存前裁成 null」不在这根针上，普查见卡面） */
        const val ALIAS_TRIM = "alias?.trim()"

        const val GROUP_SCAN = "fragments.firstNotNullOfOrNull { it.displayNameOrNull }"
        const val GROUP_FALLBACK = "?: primary.displayName"

        /** 改前管理页那一手的读法：现在必须 0 处 */
        const val PAGE_LESION = "it.alias"

        const val GROUP_CALL_SITE = "displayName = groupDisplayNameOf(fragments, primary),"
        const val GROUP_DECLARATION = "internal data class CourseGroup("
        const val GROUP_FIELD = "val displayName: String,"

        /** 参数表字段唯一会「改了片段却不改门面名」的那条形 */
        const val COPY_CALL = ".copy("

        val SIBLING_DECLARATION = Regex("""^ {4}val ([A-Za-z][A-Za-z0-9]*)""", RegexOption.MULTILINE)

        // ⓪ 步现量（写死之前在同一棵树上逐条 grep -n / awk 'NR==N' 量的；插一行删一行都要回来重钉）：
        //   grep -n 'displayNameOrNull'  $COURSE  ⇒ 85（实体回退那两行的第二行）与 107（真源声明）
        //   grep -n 'groupDisplayNameOf' $PAGE    ⇒ 542（归并点调用）与 571（函数头）、体 572、回退 573
        //   grep -n 'internal data class CourseGroup(' $PAGE ⇒ 575
        const val COURSE_ENTITY_LINE = 85
        const val COURSE_SOURCE_LINE = 107
        const val PAGE_SCAN_LINE = 571
        const val PAGE_BODY_LINE = 572
        const val PAGE_FALLBACK_LINE = 573
        const val PAGE_GROUP_LINE = 575
    }
}
