package com.buaa.schedule.ui.importing

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T181①：钉「导入完成那一句与 dropped 那本账同框，而且那一格的出场与否只跟着计数走」。
 *
 * ## 病
 *
 * `ScheduleViewModel.confirmPendingImport` 那句「导入完成：新增 N、更新 M（含拆行片段）」念的是
 * **预览选中的条数**，而落库那一刀（`ScheduleRepository.replaceSemesterCoursesWithHistory`）
 * 在这之后还要过一次 `CourseConstraints.normalize` —— 星期几不在 `1..7`、周次/节次全在范围外、
 * 名称空白的行当场蒸发。课表少一门、屏幕上的数还是多的，**没有任何人念过少的那一门**。
 * T181 把这本账抽成纯内核 [com.buaa.schedule.domain.schedule.ImportWriteTally]、让仓储交回
 * [com.buaa.schedule.domain.schedule.ImportWriteOutcome]、VM 端给文案，文案在
 * [importCompletionMessage] 里补那半句「（跳过 M 条无效）」。
 *
 * ## 为什么按「同框 + 枚数」取判据
 *
 * 卡面给的钉法就是这一族：**`append("导入完成：新增` 那一行必须与 dropped/skipped 同框**。
 * 只数总枚数的话，「把跳过那句搬到另一枚函数里」也算对 ⇒ 本守卫按
 * **文件 / 函数体 / 逐字 / 枚数**四把尺子取：文案两半必须在同一枚 `importCompletionMessage`
 * 的花括号里，且那半句只在一枚 `if (droppedCount > 0)` 之后出场。
 *
 * ## 两侧的红臂（本守卫自己也得是活的）
 *
 * - **朝宽**：抹掉那半句 append（回到改前形状）⇒ 档 ① 的逐字格红；
 * - **朝严**：把那半句改成无条件 append（0 条也念「跳过 0 条无效」）⇒ 档 ① 的逐字格与
 *   「跳过恰一枚」那格一起红；
 * - 另三档各盯 VM 接线（档 ②）、仓储返回类型与 copy 站点（档 ③）、内核纯度与派生属性落点（档 ④）：
 *   把 `val written =` 摘掉、把返回类型改回 `Unit`、把 `mapNotNull` 搬回仓储，都红在同一枚档上。
 *
 * 只做**源码核对**（JVM，无 Robolectric、无设备、不读时钟）。判据本身的逐格账在
 * `ImportWriteTallyTest`；本文件只管接线。⚠️ 未装机：真机上「导入一条星期 8 的课」这一格
 * 要教务侧或文本侧造出该行，本卡零 adb。
 */
class ImportCompletionSkippedCountGuardTest {

    private companion object {
        const val COPY = "com/buaa/schedule/ui/importing/ImportCompletionCopy.kt"
        const val TALLY = "com/buaa/schedule/domain/schedule/ImportWriteTally.kt"
        const val EXPORT_COPY = "com/buaa/schedule/ui/settings/WakeUpExportCopy.kt"
        const val VIEW_MODEL = "com/buaa/schedule/ui/ScheduleViewModel.kt"
        const val REPOSITORY = "com/buaa/schedule/data/repository/ScheduleRepository.kt"

        const val MESSAGE_SIGNATURE = "internal fun importCompletionMessage("

        /** 普通字符串里 `\$` 才是"字面 dollar"：原始字符串（`"""…"""`）不认反斜杠转义 */
        const val ADDED_APPEND = "append(\"导入完成：新增 \$addedCount、更新 \$changedCount\")"
        const val FRAGMENT_APPEND = "append(\"（含拆行片段）\")"
        const val SKIPPED_APPEND = "if (droppedCount > 0) append(\"（跳过 \$droppedCount 条无效）\")"

        const val CONFIRM_SIGNATURE = "fun confirmPendingImport() {"
        const val REPO_CALL = "val written = repository.replaceSemesterCoursesWithHistory("
        const val VM_MESSAGE_CALL = "importCompletionMessage("
        const val VM_DROPPED_ARG = "droppedCount = written.droppedCount,"
        const val VM_MESSAGE_IMPORT = "import com.buaa.schedule.ui.importing.importCompletionMessage"

        const val REPLACE_SIGNATURE = "suspend fun replaceSemesterCoursesWithHistory("
        const val REPLACE_TAIL = "): ImportWriteOutcome {"
        const val TALLY_CALL = "val outcome = ImportWriteTally.tally(importedCourses)"
        const val WRITE_FROM_OUTCOME = "replaceSemesterCoursesInTx(semester, outcome.writtenCourses)"
        const val HISTORY_COPY =
            "importHistoryDao.insert(history.copy(courseCount = outcome.writtenCount).toEntity())"
        const val RETURN_OUTCOME = "return outcome"
        const val MAP_NOT_NULL = "mapNotNull(CourseConstraints::normalize)"

        /** 备份恢复那一路自带的那本账（VM 早就把它念成"跳过无效数据 N 条"） */
        const val RESTORE_TALLY = "val skippedInvalid = courses.size - normalized.size"

        const val OUTCOME_DERIVED = "val writtenCount: Int get() = writtenCourses.size"
        const val TALLY_SIGNATURE = "fun tally(importedCourses: List<Course>): ImportWriteOutcome {"
    }

    // ─────────────── ① 文案本体：那两半同框、那半句只在计数非零时出场 ───────────────

    @Test
    fun `导入完成那句与跳过那半句长在同一枚函数体里`() {
        val code = blankComments(source(COPY))
        val body = balancedBlock(code, MESSAGE_SIGNATURE, "importCompletionMessage")

        assertEquals(
            "「$ADDED_APPEND」在 importCompletionMessage 体内恰好一枚。" +
                "\n复算：grep -Fn 「导入完成：新增」 app/src/main/java/com/buaa/schedule/ui/importing/ImportCompletionCopy.kt" +
                "\n实到枚数：" + occurrences(body, ADDED_APPEND),
            1,
            occurrences(body, ADDED_APPEND),
        )
        assertEquals(
            "「（含拆行片段）」那一枚 append 必须还在体内（改前那句的既有形状不许被顺手删掉）",
            1,
            occurrences(body, FRAGMENT_APPEND),
        )
        assertEquals(
            "dropped 那一半句必须**逐字**长在体内、且只在 droppedCount 非零那一闸之后出场。" +
                "\n  期望逐字：" + SKIPPED_APPEND +
                "\n  实到枚数：" + occurrences(body, SKIPPED_APPEND) +
                "\n整格抹掉（改前那种『没人念少的那一门』）与无条件 append（0 条也念跳过）都算红",
            1,
            occurrences(body, SKIPPED_APPEND),
        )
        assertEquals(
            "体内『跳过』只许出现一枚：多出来就是第二本册子（措辞与判据各写一遍）",
            1,
            occurrences(body, "跳过"),
        )
        assertEquals(
            "体内 append 恰好四枚：新增/更新、保留（kept>0）、含拆行片段、跳过（dropped>0）",
            4,
            occurrences(body, "append("),
        )
    }

    // ─────────────── ② VM 接线：账从仓储端来，句子里那个数只能取自那本账 ───────────────

    @Test
    fun `确认导入那一条链把账端给文案`() {
        val code = blankComments(source(VIEW_MODEL))
        assertEquals(
            "VM 必须 import 那枚纯文案函数（否则句子又长回 VM 里、JVM 侧就再也断言不到它）。" +
                "实到枚数：" + occurrences(code, VM_MESSAGE_IMPORT),
            1,
            occurrences(code, VM_MESSAGE_IMPORT),
        )
        val body = balancedBlock(code, CONFIRM_SIGNATURE, "confirmPendingImport")
        assertEquals(
            "confirmPendingImport 体内必须**接住**仓储交回的那本账（「$REPO_CALL」）——" +
                "改前它是裸调用、返回值直接扔掉，等于丢掉的行无人知晓",
            1,
            occurrences(body, REPO_CALL),
        )
        assertEquals(
            "那半句的数只能取自仓储交回的 outcome（「$VM_DROPPED_ARG」），不许在 VM 里另算一遍",
            1,
            occurrences(body, VM_DROPPED_ARG),
        )
        assertEquals(
            "那句文案在 VM 体内恰好一枚调用点",
            1,
            occurrences(body, VM_MESSAGE_CALL),
        )
        assertEquals(
            "整枚 VM 里『导入完成：新增』的字面枚数必须是 0：文案只有一处真相（在 $COPY）。" +
                "搬回 VM 就等于把它移出 JVM 单测可达的范围，实到枚数：" + occurrences(code, "导入完成：新增"),
            0,
            occurrences(code, "导入完成：新增"),
        )
    }

    // ─────────────── ③ 仓储形状：交回账、写的就是数过的那一份、历史行也吃同一本账 ───────────────

    @Test
    fun `仓储交回那本账且写库的就是数过的那一份`() {
        val code = blankComments(source(REPOSITORY))
        assertEquals(
            "replaceSemesterCoursesWithHistory 的签名收尾必须交回那本账（改前是 Unit）。" +
                "\n  期望：" + REPLACE_TAIL +
                "\n  实到枚数：" + occurrences(code, REPLACE_TAIL),
            1,
            occurrences(code, REPLACE_TAIL),
        )
        val body = balancedBlock(code, REPLACE_SIGNATURE, "replaceSemesterCoursesWithHistory")
        for (anchor in listOf(TALLY_CALL, WRITE_FROM_OUTCOME, HISTORY_COPY, RETURN_OUTCOME)) {
            assertEquals(
                "该函数体内这一行必须逐字在场（每样一枚）：「$anchor」。" +
                    "\n  实到枚数：" + occurrences(body, anchor) +
                    "\n$TALLY_CALL 与 $WRITE_FROM_OUTCOME 少任何一枚 = 写库的那一份与数过的那一份" +
                    "不是同一份；$HISTORY_COPY 摘掉 = 导入记录页那句『N 条课程』继续念预览数" +
                    "（跨表派生值 ⇒ 本仓规矩是给每个 copy 站点配一枚守卫，就是这一格）",
                1,
                occurrences(body, anchor),
            )
        }
        assertEquals(
            "体内不许再有 mapNotNull 那种『丢掉了没人知道』的形状",
            0,
            occurrences(body, MAP_NOT_NULL),
        )
        assertEquals(
            "全仓 `$MAP_NOT_NULL` 必须恰好剩两枚，且两枚都得有人记账：" +
                "\n  ① `replaceSemesterCourses` 那一枚 = 同族第三处（教务刷新 / 口令导入那一路念的是" +
                "`fetched.courses.size`，T181 卡面没点它，账登记在回执的残账格里）；" +
                "\n  ② `restoreBackupDataInTx` 那一枚 = 备份恢复那一路，紧跟着一行就写着 " +
                "`val skippedInvalid = courses.size - normalized.size`，那本账 VM 早就念给用户听了。" +
                "\n  谁把 ① 也换成 ImportWriteTally，这一格要跟着改成 1 并补上那句文案的调用点。" +
                "\n  实到枚数：" + occurrences(code, MAP_NOT_NULL),
            2,
            occurrences(code, MAP_NOT_NULL),
        )
        assertEquals(
            "备份恢复那一路的那本账必须还在（它是本卡的对照物：姊妹落点早就念这一笔了）。" +
                "实到枚数：" + occurrences(code, RESTORE_TALLY),
            1,
            occurrences(code, RESTORE_TALLY),
        )
    }

    // ─────────────── ④ 内核纯度与派生属性落点 ───────────────

    @Test
    fun `纯内核零 android import 且派生值写在类体属性里`() {
        for (path in listOf(TALLY, COPY, EXPORT_COPY)) {
            val offenders = source(path).lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("import ") }
                .filter { it.contains("android.") || it.contains("androidx.") }
                .toList()
            assertEquals(
                "$path 必须零 android / androidx import（本仓口径：判据要能在 JVM 侧断言，" +
                    "设备侧事实由调用点当参数递进来）。实到：" + offenders,
                emptyList<String>(),
                offenders,
            )
        }
        val tally = blankComments(source(TALLY))
        assertEquals(
            "writtenCount 是**同表派生值**，必须写成类体属性（本仓规矩：值 = f(同表参数) 就进类体，" +
                "不给它第二次 copy 走漏的机会）。期望逐字：$OUTCOME_DERIVED",
            1,
            occurrences(tally, OUTCOME_DERIVED),
        )
        assertEquals(
            "tally 的签名逐字一枚：全仓只有这一处「过一遍 normalize + 数清两本册子」",
            1,
            occurrences(tally, TALLY_SIGNATURE),
        )
    }

    // ---------------- helpers（与 ManualTimeOverrideWiringGuardTest 同写法） ----------------

    private fun source(relative: String): String = File(findMainJavaDir(), relative).readText()

    private fun occurrences(hay: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var at = hay.indexOf(needle)
        while (at >= 0) {
            count++
            at = hay.indexOf(needle, at + needle.length)
        }
        return count
    }

    /** 从锚点那一句起按花括号配平取整块（含锚点自己）；锚点找不到就抛 */
    private fun balancedBlock(code: String, anchor: String, label: String): String {
        val at = code.indexOf(anchor)
        assertTrue("$label 的锚点没找到（改名/挪家要回来重钉本守卫）：「$anchor」", at >= 0)
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

    /** 把 `//` 与块注释抹成空格（字符串保留、长度与换行位置不变）：钉的是接线，不是白话 */
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
}
