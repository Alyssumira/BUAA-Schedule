package com.buaa.schedule.ui.editor

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T367③：编辑器那两支页 scope 协程的「旗标必须被收到」形状守卫（在册 #381 的尺）。
 *
 * ## 病灶（族别点名，不泛化）
 * `CourseEditorScreen` 的保存支（裸 :246 起）与删除支（裸 :674 起）各自在一枚 `rememberCoroutineScope()`
 * 供给的 scope 上起协程：起手立旗标、收尾收旗标。改前收旗标只有**一条**路走得通 —— 一路跑到行尾。
 * 中途任何一手抛出，收旗标那一步就被跳过，而这棵组合还活着（没转屏、没退栈）：
 * 那颗删除按钮按这枚旗标取反放闸、保存那颗把它折进判据实参，于是两颗同时死、底栏那句进行中文案常驻。
 * T258 当年判这一枚「形状在且零尺」，T367① 补上成对的收尾块，本文件补上那张「支线 × 结局」的形状表。
 *
 * ## 为什么这里没有「纯判据内核」那一格
 * 「抛出该不该把旗标收回」的答案就是这个控制流本身，不是一道判据：要抽成内核，就得让生产去调一枚
 * 只做「立旗 → 跑体 → 收旗」的助手 —— 那既是把语言语义再测一遍的套套逻辑，又会先把两支行里的字面
 * 收旗换成一次调用，「这一支到底有没有一枚真的收尾块」这件事就没人可量了。
 * 所以 ② 那张「支线 × 结局」的表落在**本文件**，按源码形状逐格钉（红线写明：判据天然非纯时不许硬包装成"纯内核"）。
 *
 * ## 两层八格（[everyBranchOutcomeClearsTheSavingFlagThroughItsFinallyBlock]）
 * 两枚支线（名册认出的那两支）× 四档结局：正常返回 / 返回假结论 / 抛出 / 被取消。
 * 每格各有一条独立判别力，没有一格是另一格的复述：
 * - 正常返回：收旗标那一手必须长在收尾块里，且**这一支里只剩这一枚**收旗标（行尾那枚搬进来后不许留残桩）。
 * - 返回假结论：写给用户看的那一句必须留在 try 里、不在收尾块里 —— 搬进收尾块就成了「取消也念失败」的假话。
 * - 抛出：该支全部在册外注调用点必须被 try 罩住（名册见 [SAVE_HANDOFFS] / [DELETE_HANDOFFS]）；
 *   有一枚漏在 try 之外 ⇒ 那一枚抛出时收尾块压根跑不到，本卡的修法对它无效。
 * - 被取消：两支体内零 catch、零裸兜底（把取消吞掉就是把它折成正常收尾，同 #536 那一族的裁法），
 *   且收旗标那一手不被任何前件短路（押上「协程还在不在」这类判据，取消那一档恰好走不到它）。
 *
 * ## 覆盖面边界（如实写，别当它没有）
 * - 只读 `CourseEditorScreen.kt` 一枚文件，判的是**源码形状**：运行期的取消时序、写在已摘组合上的可见性
 *   都不在本格射程（那两件事在 T367⓪ 的取证段按文档与结构判，本卡零设备、零运行期探针）。
 * - 转屏归零那一半**不在**本守卫射程（在册 #498 = T258-04，T367 红线 (a)）：本格钉「抛出或取消时收尾块跑得到」，
 *   不钉「旗标跨配置存活」；哪天有人给它加跨存活的载体，本卡既挡不住也不为它作证。
 * - 本格只做源码核对（JVM，无 Robolectric、无设备、函数体不读系统时钟与时区）。
 */
class CourseEditorSavingFinallyGuardTest {

    /** ② 表驱动：两枚支线 × 四档结局 = 八格，红在哪一格直接念在消息里。 */
    @Test
    fun everyBranchOutcomeClearsTheSavingFlagThroughItsFinallyBlock() {
        val code = withoutCommentsKeepingLiterals(readEditorSource())
        val problems = mutableListOf<String>()
        for (branch in recognisedBranches(code)) {
            if (!branch.shapeCut) {
                problems.add(branch.label + " 切不出形状 —— " + branch.problem)
                continue
            }
            for (row in OUTCOME_ROWS) {
                row.check(branch)?.let { problems.add(branch.label + " × " + row.name + " —— " + it) }
            }
        }
        assertTrue(
            "编辑器那两支页 scope 协程的「四档结局都要收到旗标」这张表红了（实到 " + problems.size +
                " 格 ／ 在册 8 格 = 2 支 × 4 档）。这一族的病是**行尾那一手成了唯一的收旗点**：中途抛出的话" +
                "那枚在飞旗标永久停在真，两颗按钮（一颗按它取反、一颗把它折进保存判据）一起死，" +
                "而这棵组合还活着 —— 没转屏、没退栈，用户只看见那句进行中文案挂着、红条一句都念不出来" +
                "（在册 #381，改前这一族零尺）。逐格：" + problems.joinToString(" ／ "),
            problems.isEmpty(),
        )
    }

    private companion object {
        const val EDITOR = "com/buaa/schedule/ui/editor/CourseEditorScreen.kt"

        // 形状针（都在抹注释之后的代码文本上数）
        const val LAUNCH = "scope.launch {"
        const val TRY = "try {"
        const val FINALLY = "finally"
        const val FINALLY_OPEN = "finally {"
        const val CATCH = "catch"
        const val RUN_CATCHING = "runCatching"
        const val FLAG_CLEARED = "saving = false"
        const val FLAG_RAISED = "saving = true"
        val CANCELLATION_GATES = listOf("if (", "isActive", "coroutineContext", "ensureActive")

        // 收窄后的绝对值：本文件这一对页 scope 协程
        const val LAUNCH_SITES = 2
        const val BRANCHES = 2
        const val CATCH_SITES = 0

        // 名册：两支各自的身份参照、失败文案与外注调用点（N 臂删掉任何一枚都当场红）
        const val SAVE_IDENTITY = "val saved = saveCourseDraft("
        const val DELETE_IDENTITY = "val deleted = onDelete(target)"
        const val SAVE_COPY = "saveError = \"保存失败"
        const val DELETE_COPY = "saveError = \"删除失败"
        val SAVE_HANDOFFS = listOf("saveCourseDraft(", "onSaveTailReached()", "onBack()")
        val DELETE_HANDOFFS = listOf("onDelete(target)")

        /** 支线名册：身份参照 → 那一支的标签。两支各一条，摘掉一条就少测一整支。 */
        val BRANCH_ROSTER = listOf(SAVE_IDENTITY to "保存支", DELETE_IDENTITY to "删除支")
        const val HANDOFF_SITES = 4

        val OUTCOME_ROWS = listOf(
            Row("正常返回") { branch ->
                when {
                    occurrences(branch.finallyPart, FLAG_CLEARED) != 1 ->
                        "收尾块里收到 " + occurrences(branch.finallyPart, FLAG_CLEARED) +
                            " 枚收旗标 expected:<1>"
                    occurrences(branch.tryPart, FLAG_CLEARED) != 0 ->
                        "try 里还留着 " + occurrences(branch.tryPart, FLAG_CLEARED) +
                            " 枚收旗标 expected:<0>（行尾那枚搬进收尾块之后不许留残桩 —— " +
                            "留着就是抛出那一档照样收不到，本卡白修）"
                    occurrences(branch.body, FLAG_RAISED) != 1 ->
                        "这一支立旗标实到 " + occurrences(branch.body, FLAG_RAISED) + " 枚 expected:<1>"
                    else -> null
                }
            },
            Row("返回假结论") { branch ->
                when {
                    occurrences(branch.body, branch.copy) != 1 ->
                        "写给用户看的那一句实到 " + occurrences(branch.body, branch.copy) +
                            " 枚 expected:<1>（编排函数交回空 id、删库交回假的时候全靠它出声）"
                    occurrences(branch.finallyPart, branch.copy) != 0 ->
                        "失败文案被搬进了收尾块（实到 " + occurrences(branch.finallyPart, branch.copy) +
                            " 枚 expected:<0>）—— 收尾块连取消都会跑，那一档课还在、草稿还在，" +
                            "却当场念一句失败，是假话"
                    else -> null
                }
            },
            Row("抛出") { branch ->
                val missing = branch.handoffs.filter { needle -> occurrences(branch.body, needle) == 0 }
                val outside = branch.handoffs.filter { needle -> !insideTry(branch, needle) }
                when {
                    missing.isNotEmpty() ->
                        "在册外注调用点在本支读不到：" + missing.joinToString(" ／ ") +
                            "（换了写法就是本守卫看不见的那一手 ⇒ 名册要回来重钉）"
                    outside.isNotEmpty() ->
                        "这些外注调用点没被 try 罩住：" + outside.joinToString(" ／ ") +
                            "（那一手抛出时收尾块压根跑不到，旗标又会停在真）"
                    else -> null
                }
            },
            Row("被取消") { branch ->
                val gate = CANCELLATION_GATES.firstOrNull { marker -> branch.finallyPart.contains(marker) }
                when {
                    occurrences(branch.body, CATCH) != 0 ->
                        "这一支里有 catch（实到 " + occurrences(branch.body, CATCH) + " 枚 expected:<0>）：" +
                            "接住异常就是把抛出折成正常收尾，取消那一档还会被一起吞掉"
                    occurrences(branch.body, RUN_CATCHING) != 0 ->
                        "这一支里有裸兜底（实到 " + occurrences(branch.body, RUN_CATCHING) +
                            " 枚 expected:<0>），同一理由"
                    occurrences(branch.finallyPart, FLAG_CLEARED) != 1 ->
                        "取消那一档走的收尾块里没有收旗标那一手"
                    gate != null ->
                        "收旗标那一手被前件短路了（收尾块里读到「" + gate + "」）—— " +
                            "被取消恰好是走不到它的那一档，这一格等于没修"
                    else -> null
                }
            },
        )
    }

    // ---- 切形状 ------------------------------------------------------------------------------------------

    /**
     * 两支各自的身份参照 → 往前找包围它的那枚 `scope.launch {` → 花括号配平取体 → 切成三段。
     * 切不出来的那一支不静默跳过：它带着 [Branch.problem] 进表，由调用点把话念出来。
     */
    private fun recognisedBranches(code: String): List<Branch> {
        val launches = indexOfAll(code, LAUNCH)
        val out = mutableListOf<Branch>()
        for (entry in BRANCH_ROSTER) {
            val label = entry.second
            val identity = entry.first
            val copy = if (label == "保存支") SAVE_COPY else DELETE_COPY
            val handoffs = if (label == "保存支") SAVE_HANDOFFS else DELETE_HANDOFFS
            val marker = indexOfAll(code, identity)
            if (marker.size != 1) {
                out.add(
                    Branch(label, "", "", "", "", copy, handoffs, false, false, false,
                        "身份参照 `" + identity + "` 实到 " + marker.size + " 枚 expected:<1>"),
                )
                continue
            }
            val launchAt = launches.lastOrNull { at -> at < marker.first() }
            if (launchAt == null) {
                out.add(
                    Branch(label, "", "", "", "", copy, handoffs, false, false, false,
                        "那一枚参照往前找不到包围它的 scope.launch（旗标换了载体？）"),
                )
                continue
            }
            val body = balancedBody(code, launchAt + LAUNCH.length - 1)
            if (body.isEmpty()) {
                out.add(
                    Branch(label, "", "", "", "", copy, handoffs, false, true, false,
                        "scope.launch 的花括号没配平，切不出那一段体"),
                )
                continue
            }
            val tryAt = body.indexOf(TRY)
            val finallyAt = if (tryAt >= 0) body.indexOf(FINALLY_OPEN, tryAt) else -1
            if (tryAt < 0 || finallyAt < 0) {
                out.add(
                    Branch(label, body, "", "", body, copy, handoffs, false, true, false,
                        "这一支里没有成对的 try／收尾块（try 实到 " + (if (tryAt < 0) 0 else 1) +
                            " 枚、收尾块实到 " + (if (finallyAt < 0) 0 else 1) + " 枚，各 expected:<1>）" +
                            "—— 行尾那一手又成了唯一的收旗点"),
                )
                continue
            }
            out.add(
                Branch(
                    label = label,
                    body = body,
                    tryPart = body.substring(tryAt + TRY.length, finallyAt),
                    finallyPart = body.substring(finallyAt + FINALLY_OPEN.length),
                    prologue = body.substring(0, tryAt),
                    copy = copy,
                    handoffs = handoffs,
                    shapeCut = true,
                    launchMatchesIdentity = true,
                    finallyHasClear = body.substring(finallyAt).contains(FLAG_CLEARED),
                    problem = "",
                ),
            )
        }
        return out
    }

    /** 花括号配平取体（跨过字符串字面量）；配不平交回空串，由调用点报，不静默跳过。 */
    private fun balancedBody(source: String, brace: Int): String {
        var depth = 0
        var index = brace
        var inString = false
        var escaped = false
        while (index < source.length) {
            val c = source[index]
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
                    if (depth == 0) return source.substring(brace + 1, index)
                }
            }
            index++
        }
        return ""
    }

    /**
     * 只抹注释、保留字符串字面量，长度与换行位置都不动（行号因此仍然可信）。
     *
     * 读法与理由抄在册那一族的同名助手；⚠️ 本卡刻意**不**写成「先把源铺成字符数组、收尾再把那枚数组
     * 包成字符串返回」那一族形状 —— 在册守卫 `CommentStrippingHelperShapeGuardTest` 把那一族在
     * app/src/test 的声明枚数与物化枚数钉成了合并态写死值，多写一枚就把那三格一起顶红，而那枚守卫
     * 不在本卡过滤门禁的覆盖面里（只有全量才暴露）。走逐段拼那一族写法是上一枚卡量出来的躲法。
     */
    private fun withoutCommentsKeepingLiterals(source: String): String = buildString(source.length) {
        var i = 0
        while (i < source.length) {
            when {
                source.startsWith("//", i) -> {
                    val nl = source.indexOf('\n', i)
                    val stop = if (nl < 0) source.length else nl
                    appendBlanks(source, i, stop)
                    i = stop
                }

                source.startsWith("/*", i) -> {
                    var depth = 1
                    var j = i + 2
                    while (j < source.length && depth > 0) {
                        when {
                            source.startsWith("/*", j) -> {
                                depth++
                                j += 2
                            }

                            source.startsWith("*/", j) -> {
                                depth--
                                j += 2
                            }

                            else -> j++
                        }
                    }
                    appendBlanks(source, i, j.coerceAtMost(source.length))
                    i = j
                }

                source.startsWith("\"\"\"", i) -> {
                    val end = source.indexOf("\"\"\"", i + 3)
                    val stop = if (end < 0) source.length else end + 3
                    append(source, i, stop)
                    i = stop
                }

                source[i] == '"' || source[i] == '\'' -> {
                    val quote = source[i]
                    var j = i + 1
                    while (j < source.length) {
                        when {
                            source[j] == '\\' -> j += 2
                            source[j] == quote -> {
                                j++
                                break
                            }

                            source[j] == '\n' -> break
                            else -> j++
                        }
                    }
                    append(source, i, j.coerceAtMost(source.length))
                    i = j
                }

                else -> {
                    append(source[i])
                    i++
                }
            }
        }
    }

    /** 把 from 到 until 那一段抹成空格，换行原样留着（行数不许被尺改动） */
    private fun StringBuilder.appendBlanks(source: String, from: Int, until: Int) {
        var k = from
        while (k < until) {
            append(if (source[k] == '\n') '\n' else ' ')
            k++
        }
    }

    private fun readEditorSource(): String {
        val file = File(findMainJavaDir(), EDITOR)
        assertTrue("找不到 " + file.path + "：这枚文件挪过家的话本守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    private fun findMainJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { candidate -> File(dir, candidate) }
                .firstOrNull { candidate -> candidate.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 " + File("").absolutePath)
    }
}

/** 表里的一格：一个档结局 + 它那条独立判别力。 */
private class Row(val name: String, val check: (Branch) -> String?)

/**
 * 一枚支线切出来的三段（起手仪式 / try 之内 / 收尾块之内）加上它的名册。
 * [shapeCut] 为假 ⇒ 形状切不出来，[problem] 里写着为什么；调用点不静默跳过它。
 */
private class Branch(
    val label: String,
    val body: String,
    val tryPart: String,
    val finallyPart: String,
    val prologue: String,
    val copy: String,
    val handoffs: List<String>,
    val shapeCut: Boolean,
    val launchMatchesIdentity: Boolean,
    val finallyHasClear: Boolean,
    val problem: String,
)

private fun indexOfAll(haystack: String, needle: String): List<Int> {
    val hits = mutableListOf<Int>()
    var from = 0
    while (true) {
        val at = haystack.indexOf(needle, from)
        if (at < 0) return hits
        hits.add(at)
        from = at + needle.length
    }
}

private fun occurrences(haystack: String, needle: String): Int = indexOfAll(haystack, needle).size

/** 该支全部在册外注调用点都落在 try 之内（起手仪式那一段与收尾块那一段都不算罩住）。 */
private fun insideTry(branch: Branch, needle: String): Boolean {
    val hits = indexOfAll(branch.body, needle)
    if (hits.isEmpty()) return false
    val tryStart = branch.prologue.length
    val tryEnd = tryStart + branch.tryPart.length
    return hits.all { at -> at in tryStart..tryEnd }
}
