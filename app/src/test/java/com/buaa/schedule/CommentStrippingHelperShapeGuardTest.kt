package com.buaa.schedule

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抹注释／改写源码那一族助手的「返回形状」守卫（T320①）。
 *
 * ## 病灶（族别点名，不泛化）
 * 这一族的写法是：把读进来的源码摊成字符数组，逐格改写，最后再物化回字符串 ——
 * `private fun blankComments(src: String): String { val out = …toCharArray() …改写 out… return String 包 out }`。
 * 其中两枚（`WorkingTreeInputSurfaceGuardTest`、`core/designsystem/GlassEffectiveTierWiringGuardTest`）
 * 曾把最后那一句写成「out 的 toString」：JVM 的字符数组 `toString()` 交回的是数组身份串
 * （实测 11 个字符，形如 `[C@1a2b3c4d`），不含一个字符的源码 ⇒ 那两枚守卫编译绿、门禁绿、
 * 每次判据都绿，而它们从没读过一句它宣称在读的文件（T317 改回）。
 *
 * ## 第 0 问裁定：枚数尺钉绝对值，不钉相对式
 * 这一族的失效模式恰恰是「抓不到东西 ⇒ 静默通过」。只判相对式（声明枚数 == 物化枚数）时，
 * 扫描器自己瞎掉——助手返回数组身份串、或文件清单恒空——交回的是 0 == 0，照样绿；
 * 那正是本卡要钉的那枚假绿本身。绝对值是唯一能听见扫描器失明的一把。
 * 代价（如实写）：以后任何人新增一枚合法助手，本守卫会红，要回来把写死值各 +1（拆解口径见下，
 * 重钉是一行改动；报错消息会念出实到数、期望值与这两棵树的覆盖面）。
 *
 * ## 三格判据（每格都自带一枚「尺还活着」的绝对值／下限）
 * ① 枚数尺（`app/src/test`）：族内声明枚数 == 写死值、物化枚数 == 写死值、两把数同源，
 *    外加逐枚文件对账（同一枚文件里声明数 == 物化数）—— 助手被复制走样时这一格先响。
 * ② 形状尺（`app/src/test` + `app/src/main`）：每一枚族内声明往后做到它所在函数的收尾
 *    （花括号配平），那段里必须出现物化写法，且不得出现被禁写法；本格还断言「切到的枚数」
 *    等于写死的族内总枚数，一枚都没切到时不会当成绿。
 * ③ 归类尺（`app/src/test` + `app/src/main`）：每一枚被禁写法按「从这一行往上找最近的一枚
 *    `val out =` 声明」判它指的是字符数组还是别的接收者，指回字符数组的那一档必须 0 枚；
 *    本格钉的是被禁写法总枚数的**下限**（不是等式）—— 它是形状尺，增长本身不是病，
 *    读空了才是；等式那一档由 ① 负责。
 *
 * ## ⓪ 步现量（基点 13759a5，逐格 sed 回读过 5 处原文）
 * - 族内声明：`app/src/test` 131 枚（窄口径 `(src|source|xml)` 与宽口径「任一标识」同数），
 *   `app/src/main` 该形 0 枚；`var out =` 全仓 0 枚。
 * - 物化写法：`app/src/test` 131 枚，`app/src/main` 1 枚（`ScheduleOccurrences.kt` 的 md5Hex
 *   那一枚是「等号后面直接建数组」的同族构造写法，一并进形状尺，免得换个写法再从尺缝走过去）。
 * - 逐枚文件对账：128 枚文件全部声明数 == 物化数（125 枚各 1；3 枚各 2 ——
 *   `ScanSecondEngineWiringGuardTest`、`BarhopperNativeLibProbeTest`、`BackupRulesCoverCredentialStoresTest`）。
 * - 被禁写法：`app/src/test` 34 枚 + `app/src/main` 5 枚 = 39（main 那 5 枚里 4 枚是 StringBuilder 的
 *   正常返回，1 枚是 `ImportLimits.kt` 的 ByteArrayOutputStream 带字符集参数那一形）；
 *   按最近声明归类，指回字符数组的 = 0 枚。合法的 StringBuilder 站点一枚都不算病。
 *   合并态复算（T323）：守卫侧这一把仍念 34 ＋ 5 = 39，下限一字没动、③ 本趟绿；裸原文那栏是 35 ＋ 5 = 40，
 *   多的那 1 枚与物化那把多出的那 1 枚在同一枚文件的同一句散文里 —— 这就是 ④ 要钉住的那一格。
 * - 本守卫自己落在 `app/src/test`，贡献 1 枚声明 + 1 枚物化 ⇒ T320 落地时下面写死的是 132 = 基点 131 + 自扫 1。
 *
 * ## 合并态重钉（T323）：三枚写死值按「守卫内部抹注释后」的口径钉，不按裸 grep 的口径
 * 合并对象 `gate/18` 把第二枚同族助手（`ui/editor/CourseEditorRedFieldGateWiringGuardTest`）叠进了同一棵树，
 * 于是声明这把从 132 走成 133，而物化那把在盘面上交回**两个**读数，差 1 枚，差的来源是量出来的：
 * - 守卫侧（抹注释后 —— `scanTree` 逐枚走 blankComments，HelperFile 三把数全在这份文本上数）：133 枚，
 *   与声明同源，逐枚文件对账零枚不平；本趟实跑 ① 念出的就是「声明 133 ／ 物化 133」。
 * - 裸 grep（不抹注释的原文）：134 枚。多的那 1 枚不是助手，是那枚文件 KDoc 里一句散文逐字引用了正形；
 *   同一句还顺手点了病形一次，所以被禁写法那把也从 34 顶到 35（守卫侧仍是 34 ＋ main 5 = 39，下限没动）。
 * 钉守卫侧那一栏的理由：写死值必须是仪器自己观测得到的数。把 134 钉进去，交回的是「实到 133 ／ 写死 134」，
 * 一把永远读不到自己期望值的死尺 —— 那不算收紧判据。绝对值要听的那件事（扫描器失明 ⇒ 实到 0）在这一栏
 * 照样当场响，耳朵一点没钝；T320 ⓪ 步裁的「甲＝绝对值等式」一个字没改，改的是那把尺量哪一份文本。
 * 裸 grep 那一栏没有被丢掉：差值 1、差来自哪枚文件、两栏各自念什么数，由下面的 ④ 单独钉成在册量。
 * 重钉代价（仍然在册）：新增一枚合法助手 ⇒ 三枚值各 +1、④ 的差值那格不动；新增一句点名散文 ⇒
 * 只有 ④ 会红，红的是「在册散文清单长了」，不是尺子被顶走了。
 *
 * ## 重测这两把尺（两栏都要念，别只念一栏）
 * 写在这里的命令一律把括号写成方括号类，所以它们一个字都不匹配本文件自己的针 —— 这条理由从 T323 起
 * 才真正要紧：④ 那格数的是抹注释**之前**的原文，「反正注释会被抹掉」不再替这些命令免检。
 * 拼针的那几枚常量（TO_ARRAY 那一族）用「拆开来写」同一招自免，散文把自己数进册子的路只有这一条。
 * - 声明：`grep -rhoE 'val out = [A-Za-z_][A-Za-z0-9_]*[.]toCharArray[(][)]' app/src/test | wc -l` → 合并态 133
 *   这一把两栏同数：族内声明写不进注释（针要的是整行尾巴上那记数组化调用），散文顶不动它。
 * - 物化：`grep -rhoE 'String[(]out[)]' app/src/test | wc -l` → 裸原文 134 ／ 守卫侧（抹注释后）133
 *   写死值钉的是守卫侧那一栏；两栏差的那 1 枚由 ④ 单独钉成在册量，不是「已知误差」。
 * 对不上就照上面两节的拆解重钉，别把判据改成 >=。
 *
 * ## 覆盖面边界（如实写，别当它没有）
 * - ② 的函数体是「声明处往后花括号配平到收尾」切出来的。基点树 133 枚（合并态 134 枚）全部切得干净：
 *   T323 本趟实跑念的是配平 134 ／ 退回「声明处到下一枚声明」那一档 0 枚；万一将来某枚函数体里有不配平的
 *   括号混进字符串，那一枚会退回较宽的窗口并计入消息里的「退回档」枚数 —— 判据不变，窄在哪一眼看得见。
 * - 物化写法只认 `String` 包 out 这一种字面量。`out.concatToString()` 之类在盘面上是 0 枚，
 *   把它当合法写法就是把尺子放宽；真要走那条路，是同批把两把绝对值一起重钉的活。
 * - 只扫 `.kt`；`app/build/` 那棵产物树一枚都不读，零 android import、不起 Robolectric。
 */
class CommentStrippingHelperShapeGuardTest {

    /**
     * ① 枚数尺：这一族在 `app/src/test` 里的声明枚数与物化枚数，各自等于 ⓪ 步现量写死的期望值，
     * 两把数还同源；再逐枚文件对一遍账。抓的是「助手被复制走样／被删掉／扫描器读空」三档。
     */
    @Test
    fun helperDeclarationCountAndMaterializationCountMatchTheFrozenExpectation() {
        val tree = scanTree(TEST_ROOT)
        val decls = tree.declCount
        val materialized = tree.materializationCount
        val drift = mutableListOf<String>()
        if (decls != EXPECTED_TEST_DECLS) {
            drift.add("族内声明枚数 实到 " + decls + " ／ 写死 " + EXPECTED_TEST_DECLS)
        }
        if (materialized != EXPECTED_TEST_MATERIALIZATIONS) {
            drift.add("物化枚数 实到 " + materialized + " ／ 写死 " + EXPECTED_TEST_MATERIALIZATIONS)
        }
        if (decls != materialized) {
            drift.add("两把数不同源：声明 " + decls + " ／ 物化 " + materialized)
        }
        val unpaired = tree.files
            .filter { file -> file.decls.size != file.materializations.size }
            .map { file -> file.relative + "（声明 " + file.decls.size + " ／ 物化 " + file.materializations.size + "）" }
            .sorted()
        assertTrue(
            "钉法 = 枚数尺（绝对值，不是 >=）：" + TEST_ROOT + " 里这一族的声明枚数与「" + MATERIALIZED + "」枚数" +
                "各自都必须等于 ⓪ 步现量写死的期望值，且两把数同源 —— 只判相对式的话，扫描器自己瞎掉时 0 == 0 照样绿，" +
                "而那正是 T315 留下、T317 才改回的那枚假绿。实到：声明 " + decls + " 枚 ／ 物化 " + materialized +
                " 枚 ｜ 写死：" + EXPECTED_TEST_DECLS + " ／ " + EXPECTED_TEST_MATERIALIZATIONS +
                "（口径 = 抹注释后的文本，不是裸 grep；拆解见类 KDoc 的「合并态重钉」一节）｜ 扫过 " + tree.files.size + " 枚 .kt ｜ " +
                "逐枚对不上的文件：" + unpaired.joinToString(" ／ ") + " ｜ 漂移：" + drift.joinToString(" ／ "),
            drift.isEmpty() && unpaired.isEmpty(),
        )
    }

    /**
     * ② 形状尺：每一枚族内声明做到它所在函数的收尾，那段里必须物化、不得出现被禁写法。
     * 本格同时断言切到的枚数等于两棵树的族内总枚数（合并态 134），一枚都没切到时不会当成绿。
     */
    @Test
    fun everyCharArrayHelperMaterializesItsArrayInsteadOfCallingToStringOnIt() {
        val test = scanTree(TEST_ROOT)
        val main = scanTree(MAIN_ROOT)
        val offenders = mutableListOf<String>()
        var braced = 0
        var narrowed = 0
        for (tree in listOf(test, main)) {
            for (file in tree.files) {
                val starts = file.decls
                for (index in starts.indices) {
                    val start = starts[index]
                    val label = file.relative + " :" + lineOf(file.text, start)
                    val window = sliceEnd(file.text, start).let { end ->
                        if (end >= 0) {
                            braced++
                            file.text.substring(start, end)
                        } else {
                            narrowed++
                            file.text.substring(start, if (index + 1 < starts.size) starts[index + 1] else file.text.length)
                        }
                    }
                    if (!window.contains(MATERIALIZED)) {
                        offenders.add(label + " 的函数体里读不到物化写法（该有「" + MATERIALIZED + "」）")
                    }
                    val forbiddenAt = window.indexOf(FORBIDDEN)
                    if (forbiddenAt >= 0) {
                        offenders.add(
                            label + " 的函数体里出现被禁写法「" + FORBIDDEN + "」于 :" +
                                lineOf(file.text, start + forbiddenAt),
                        )
                    }
                }
            }
        }
        val checked = braced + narrowed
        val coverage = mutableListOf<String>()
        if (checked != EXPECTED_ALL_HELPER_DECLS) {
            coverage.add("切到的族内声明枚数 实到 " + checked + " ／ 写死 " + EXPECTED_ALL_HELPER_DECLS)
        }
        if (test.declCount != EXPECTED_TEST_DECLS) {
            coverage.add(TEST_ROOT + " 声明枚数 实到 " + test.declCount + " ／ 写死 " + EXPECTED_TEST_DECLS)
        }
        if (main.declCount != EXPECTED_MAIN_DECLS) {
            coverage.add(MAIN_ROOT + " 声明枚数 实到 " + main.declCount + " ／ 写死 " + EXPECTED_MAIN_DECLS)
        }
        assertTrue(
            "钉法 = 形状尺 + 覆盖面枚数尺：每一枚族内声明做到它所在函数收尾（花括号配平），那段里必须出现" +
                "「" + MATERIALIZED + "」，且一枚都不许出现「" + FORBIDDEN + "」（字符数组的 toString 交回的是" +
                "数组身份串，等于那句「从没读过一句它宣称在读的文件」）。合法的 StringBuilder 站点由 ③ 那把归类" +
                "尺放行，本格只在族内声明的函数体里说话，不朝宽的判红。实到：切到 " + checked + " 枚（配平 " + braced +
                " ／ 退回「声明处到下一枚声明」那一档 " + narrowed + "）｜ 写死 " + EXPECTED_ALL_HELPER_DECLS +
                " 枚 = " + TEST_ROOT + " " + EXPECTED_TEST_DECLS + "（含本守卫与各枚后续同族助手）+ " + MAIN_ROOT +
                " " + EXPECTED_MAIN_DECLS + " ｜ 覆盖面漂移：" + coverage.joinToString(" ／ ") +
                " ｜ 病枚：" + offenders.take(12).joinToString(" ／ ") +
                "（共 " + offenders.size + " 枚）",
            offenders.isEmpty() && coverage.isEmpty(),
        )
    }

    /**
     * ③ 归类尺：每一枚被禁写法按「往上找最近的一枚 `val out =` 声明」归到它真正的接收者，
     * 指回字符数组的那一档必须 0 枚。盘面 39 枚全部归到 StringBuilder／ByteArrayOutputStream，
     * 一枚都不算病；被禁写法总枚数低于下限就说明扫描器读空了。
     */
    @Test
    fun forbiddenReturnNeverRefersToACharArrayDeclaration() {
        val trees = listOf(scanTree(TEST_ROOT), scanTree(MAIN_ROOT))
        val offenders = mutableListOf<String>()
        val kinds = linkedMapOf<String, Int>()
        var sites = 0
        for (tree in trees) {
            for (file in tree.files) {
                for (at in file.arrayToStrings) {
                    sites++
                    val expr = nearestDeclExpr(file.text, at)
                    val kind = classifyDecl(expr)
                    kinds[kind] = (kinds[kind] ?: 0) + 1
                    if (kind == CHAR_ARRAY_KIND) {
                        offenders.add(
                            file.relative + " :" + lineOf(file.text, at) + " 往上最近的一枚声明是「" +
                                DECL_PREFIX + expr + "」",
                        )
                    }
                }
            }
        }
        val vacuity = sites >= EXPECTED_TO_STRING_SITE_FLOOR
        assertTrue(
            "钉法 = 归类尺（口径与 ⓪ 步一致：从这一行往上找最近的一枚 " + DECL_PREFIX + "声明，判它指的是字符数组" +
                "还是别的接收者）：指回字符数组的那一档必须 " + EXPECTED_TO_STRING_ON_CHAR_ARRAY + " 枚 —— " +
                "T315 那两枚就是把这一档写成了病。合法的 StringBuilder 返回（" + MAIN_ROOT + " 的三条导出链与 " +
                TEST_ROOT + " 里那一族助手）一枚都不算病，朝宽的别把它判进去。实到：被禁写法 " + sites + " 枚" +
                "（下限 " + EXPECTED_TO_STRING_SITE_FLOOR + "，低于下限＝扫描器读空）｜ 归类账：" +
                kinds.entries.sortedBy { entry -> entry.key }.joinToString(" ／ ") { entry -> entry.key + " ×" + entry.value } +
                " ｜ 写死期望：字符数组型 " + EXPECTED_TO_STRING_ON_CHAR_ARRAY + " 枚 ｜ 病枚：" +
                offenders.take(12).joinToString(" ／ ") + "（共 " + offenders.size + " 枚）",
            vacuity && offenders.isEmpty(),
        )
    }

    // ---- 读盘与小工具 ------------------------------------------------------------------------------

    /**
     * 抹注释保行号、字符串不动：与 `WorkingTreeInputSurfaceGuardTest` 同一写法。
     * 最后那一句是本守卫自己钉着的那一档物化写法 —— 换成「out 的 toString」就是本卡点名的病，
     * 而 ① 那格会当场念出「实到 0 枚／写死 133 枚」（W 臂量过的正是这一档）。
     */
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
                            src.startsWith("/*", j) -> {
                                depth++
                                j += 2
                            }

                            src.startsWith("*/", j) -> {
                                depth--
                                j += 2
                            }

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
                            src[j] == quote -> {
                                j++
                                break
                            }

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

    private fun scanTree(rootRelative: String): HelperTree {
        val root = repoRoot()
        val dir = File(root, rootRelative)
        assertTrue("找不到 " + rootRelative + "：这一族的覆盖面就是这两棵树，挪过家要回来改这里", dir.isDirectory)
        val files = dir.walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .map { file ->
                HelperFile(
                    file.relativeTo(root).path.replace(File.separatorChar, '/'),
                    blankComments(file.readText()),
                )
            }
            .toList()
            .sortedBy { file -> file.relative }
        assertTrue(
            rootRelative + " 里一枚 .kt 都没扫到（实到 " + files.size + " 枚）：扫描器已经读空了，" +
                "不要拿后面那几格的「实到 0」当绿",
            files.isNotEmpty(),
        )
        return HelperTree(rootRelative, files)
    }

    /** 工作目录是模块目录还是仓库根不由这里决定：几种布局都试一遍（同 `WorkingTreeInputSurfaceGuardTest`） */
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        var hops = 0
        while (dir != null && hops < 5) {
            val current = dir!!
            if (File(current, "app/build.gradle.kts").isFile) return current
            val parent = current.parentFile
            if (parent != null && File(parent, "app/build.gradle.kts").isFile) return parent
            dir = parent
            hops++
        }
        throw IllegalStateException("找不到仓库根：当前目录 ${File("").absolutePath}")
    }
}

private class HelperFile(val relative: String, val text: String) {
    val decls: List<Int> = helperDeclStarts(text)
    val materializations: List<Int> = occurrencesOf(text, MATERIALIZED)
    val arrayToStrings: List<Int> = occurrencesOf(text, FORBIDDEN)
}

private class HelperTree(val root: String, val files: List<HelperFile>) {
    val declCount: Int get() = files.sumOf { file -> file.decls.size }
    val materializationCount: Int get() = files.sumOf { file -> file.materializations.size }
}

/** 逐枚声明扫过去，只留族内那一档：数组化调用在表达式尾巴上，或同族那枚构造写法在开头 */
private fun helperDeclStarts(text: String): List<Int> {
    val hits = mutableListOf<Int>()
    var from = 0
    while (true) {
        val at = text.indexOf(DECL_PREFIX, from)
        if (at < 0) return hits
        if (isCharArrayFamily(declExprAt(text, at))) hits.add(at)
        from = at + 1
    }
}

private fun isCharArrayFamily(expr: String): Boolean =
    expr.endsWith(TO_ARRAY) || expr.startsWith(CHAR_ARRAY_CTOR)

/** 声明那一行等号后面的表达式（取到行尾，CRLF 的尾随回车一并 trim 掉） */
private fun declExprAt(text: String, declAt: Int): String {
    val start = declAt + DECL_PREFIX.length
    val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
    return text.substring(start, end).trim()
}

/** 从 `before` 往上找最近的一枚 "val out = " 声明的表达式（⓪ 步定的归类口径） */
private fun nearestDeclExpr(text: String, before: Int): String {
    var last = -1
    var from = 0
    while (true) {
        val at = text.indexOf(DECL_PREFIX, from)
        if (at < 0 || at >= before) break
        last = at
        from = at + 1
    }
    return if (last < 0) "" else declExprAt(text, last)
}

private fun classifyDecl(expr: String): String = when {
    expr.isEmpty() -> "无前置声明"
    expr.endsWith(TO_ARRAY) || expr.startsWith(CHAR_ARRAY_CTOR) -> CHAR_ARRAY_KIND
    else -> expr.substringBefore('(').substringBefore(' ').trim()
}

/** 从声明处往后做花括号配平，交回它所在函数收尾那枚右花括号的下标；配不平交回 -1（调用点退回较宽的窗） */
private fun sliceEnd(text: String, from: Int): Int {
    var depth = 0
    var index = from
    while (index < text.length) {
        when (text[index]) {
            '{' -> depth++
            '}' -> {
                if (depth == 0) return index
                depth--
            }
        }
        index++
    }
    return -1
}

private fun occurrencesOf(text: String, needle: String): List<Int> {
    val hits = mutableListOf<Int>()
    var from = 0
    while (true) {
        val at = text.indexOf(needle, from)
        if (at < 0) return hits
        hits.add(at)
        from = at + needle.length
    }
}

private fun lineOf(text: String, index: Int): Int {
    var line = 1
    var at = 0
    while (at < index && at < text.length) {
        if (text[at] == '\n') line++
        at++
    }
    return line
}

private const val TEST_ROOT = "app/src/test"
private const val MAIN_ROOT = "app/src/main"

/**
 * ⓪ 步现量写死的绝对值（T323 重钉其中三枚，EXPECTED_MAIN_DECLS 不动）——
 * 口径 = 本守卫内部「抹注释之后」的那份文本，不是裸 grep。
 * 拆解：基点 13759a5 的 131 枚 ＋ T320 本守卫自己那枚助手 1 枚 ＋ T321 带进来的同族第二枚助手 1 枚 = 133。
 * 裸 grep 在物化这把上念的是 134，多的那 1 枚是散文；它没被丢掉，由 ④ 那格按在册量单独钉住。
 * 为什么钉守卫侧那一栏而不是裸的那一栏：写死值必须是仪器自己观测得到的数，否则是一把永远读不到
 * 自己的死尺；而绝对值要听的那件事（扫描器失明 ⇒ 实到 0）在这一栏照样当场响。见类 KDoc 的重钉记录。
 */
private const val EXPECTED_TEST_DECLS = 133
private const val EXPECTED_TEST_MATERIALIZATIONS = 133
private const val EXPECTED_MAIN_DECLS = 1
private const val EXPECTED_ALL_HELPER_DECLS = 134

/** 被禁写法在 `app/src/test` + `app/src/main` 的现量下限（34 + 5）：读空了才红，长了不红 */
private const val EXPECTED_TO_STRING_SITE_FLOOR = 39

/** 按最近声明归类，指回字符数组的那一档：基点 0 枚 */
private const val EXPECTED_TO_STRING_ON_CHAR_ARRAY = 0

// 下面几枚针在源码里拆开写：拼出来的字面量只存在于运行时，本文件自己不会把它们数进册子。
private const val MATERIALIZED = "String" + "(out)"
private const val FORBIDDEN = "out." + "toString"
private const val DECL_PREFIX = "val " + "out = "
private const val TO_ARRAY = "toCharArray" + "()"
private const val CHAR_ARRAY_CTOR = "CharArray" + "("
private const val CHAR_ARRAY_KIND = "CHARARRAY"
