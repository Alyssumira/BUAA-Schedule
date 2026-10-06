package com.buaa.schedule.domain.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T184③：静态守卫 —— 「手动课程」这一判**全仓只有一份 Kotlin 落点**（数出现次数钉法）。
 *
 * ## 病与本守卫的对应
 *
 * 备份那两句此前一边写 `it.isManualOverride`（预览句「其中手动课程 N 条」）、一边写
 * `it.semesterCode == null`（恢复侧 `manual = normalized.filter {...}` → 「新增手动课程 M 条」），
 * 同一个词两套定义 ⇒ 同一份备份的两句数对不上。裁定收成 `Course.isManualCourse` 之后，
 * 本守卫钉三件事：
 * 1. **判据本体只有一处**，且逐字就是 `semesterCode == null`（改字面 ⇒ 红）；
 * 2. **调用点册子枚数钉死**：预览侧一处、恢复侧一处，多长一处（第二份表达式）⇒ 红；
 * 3. **病灶表达式不许回到预览那一句**：`it.isManualOverride` 在 ScheduleViewModel 里 0 处。
 *
 * ## 同一判据在 SQL 里的孪生落点（本仓唯一读不到 Kotlin 属性的那一面）
 *
 * Room 的 `@Query` 串是编译期字符串，引用不到 `Course.isManualCourse`，所以
 * `data/local/CourseDao` 那三条 `semesterCode IS NULL` 是**同一判据的第二语言落点**，
 * 不是第三份 Kotlin 表达式。第 ⑥ 层把它的枚数与 `getManualCourses()` 的配对钉住：
 * 谁改了 SQL 谓词而 Kotlin 属性没跟着改，这一层就红。
 *
 * ## 其余两枚 `semesterCode == null` 为什么合法
 *
 * 第 ⑤ 层的册子按文件点名：`CourseFilter` 那枚说的是「手动课**或**本学期课」（展示范围），
 * `WidgetConfigActivity` 那枚的接收者是组件绑定配置里的 `semesterCode`（「跟随当前学期」），
 * 两者都不是"这行挂不挂学期"这一判，不许与 `isManualCourse` 合并，也不许被拿去数手动课程。
 *
 * 只做**源码核对**（JVM，无 Robolectric、无设备、不读时钟）。
 * 输入面：本守卫读 `src/main/java` 下的工作树文本，已由 `app/build.gradle.kts` 里
 * `guardReadWorkingTreeFiles` 第 ① 项（整目录 `src/main/java`）声明成 `testDebugUnitTest` 的输入
 * ⇒ 不必新增条目，照 `ui/CourseDeletionWiringGuardTest` 的写法读文件。
 */
class ManualCourseDefinitionGuardTest {

    // ─────────────── ① 判据本体：一处，且字面就是 semesterCode == null ───────────────

    @Test
    fun `判据本体在 Course 类体只有一处 字面逐字钉住`() {
        val code = blankCommentsKeepingLiterals(readMainSource(COURSE))

        assertEquals(
            "`Course.isManualCourse` 的声明在 ${COURSE_NAME} 里应当恰好 1 处（这一判的全仓唯一 Kotlin 落点），实到 " +
                occurrences(code, PROPERTY_NAME) + " 处",
            1,
            occurrences(code, PROPERTY_NAME),
        )
        assertEquals(
            "声明那一句必须逐字是 `$DECLARATION`（实到 " + occurrences(code, DECLARATION) +
                " 处）：字面改成 isNullOrBlank、换成 isManualOverride、挪进构造函数参数都算换口径",
            1,
            occurrences(code, DECLARATION),
        )
        assertEquals(
            "属性体里的 `semesterCode == null` 在本文件应当恰好 1 处（就是判据本体），实到 " +
                occurrences(code, RAW_EXPRESSION) + " 处",
            1,
            occurrences(code, RAW_EXPRESSION),
        )
        assertTrue(
            "判据必须是类体属性（值 = f(同表参数) 的合法落点），不许写成 extension function 或 policy object：" +
                "${COURSE_NAME} 里 `val isManualCourse: Boolean get()` 形态缺一处",
            code.contains("val isManualCourse: Boolean get() = semesterCode == null"),
        )
    }

    // ─────────────── ② 调用点册子：全仓 3 处，落点唯一 ───────────────

    @Test
    fun `这一判的 Kotlin 落点全仓只有一枚声明加两枚调用`() {
        val byFile = codeByFile()
        val ledger = mapOf(
            COURSE_NAME to 1,
            SCHEDULE_VIEW_MODEL_NAME to 1,
            SCHEDULE_REPOSITORY_NAME to 1,
        )
        val actual = byFile.mapValues { occurrences(it.value, PROPERTY_NAME) }.filterValues { it > 0 }

        assertEquals(
            "全仓 main 的 `isManualCourse` 总枚数应当恰好 3（类体声明 1 + 预览侧调用 1 + 恢复侧调用 1）：" +
                "期望 " + ledger.values.sum() + " 枚，实到 " +
                actual.values.sum() + " 枚。多出来的一枚就是第二份调用（本卡禁「两份表达式」），" +
                "少一枚就是某侧已经不读判据了。\n" + filesHint(actual),
            ledger.values.sum(),
            actual.values.sum(),
        )
        assertEquals(
            "`isManualCourse` 的落点册子（文件名 → 枚数）漂了：期望 " + ledger +
                "，实到 " + actual + "\n" + filesHint(byFile, PROPERTY_NAME),
            ledger,
            actual,
        )
    }

    // ─────────────── ③ 两侧各自怎么读它（逐字接线） ───────────────

    @Test
    fun `预览侧与恢复侧各按逐字接线读这一判`() {
        val viewModel = blankCommentsKeepingLiterals(readMainSource(SCHEDULE_VIEW_MODEL))
        val repository = blankCommentsKeepingLiterals(readMainSource(SCHEDULE_REPOSITORY))

        assertEquals(
            "预览那一句必须逐字是 `$VM_WIRING`（实到 " + occurrences(viewModel, VM_WIRING) +
                " 处）：换写法可以，但要连同本守卫一起改 —— 这一句念的就是备份里的 `手动课程` 枚数",
            1,
            occurrences(viewModel, VM_WIRING),
        )
        assertEquals(
            "备份清单在预览里只许数一次（`data.courses.count {` 恰好 1 处，实到 " +
                occurrences(viewModel, VM_COUNT_HEAD) + " 处）：第二枚 count 就是第二套口径的入口",
            1,
            occurrences(viewModel, VM_COUNT_HEAD),
        )
        assertEquals(
            "恢复那一句必须逐字是 `$REPO_WIRING`（实到 " + occurrences(repository, REPO_WIRING) +
                " 处）：它是 insertedManual 的唯一来源，摘掉它两句数又对不上",
            1,
            occurrences(repository, REPO_WIRING),
        )
        // 「新增」二字保的是去重后的数：本地已有的手动课程不进 manualToInsert
        assertEquals(
            "恢复侧的「新增」必须仍是去重后的枚数（`$REPO_INSERTED` 恰好 1 处，实到 " +
                occurrences(repository, REPO_INSERTED) + " 处）",
            1,
            occurrences(repository, REPO_INSERTED),
        )
    }

    // ─────────────── ④ 病灶表达式不许回到预览那一句 ───────────────

    @Test
    fun `预览侧不许再用 isManualOverride 数手动课程`() {
        val viewModel = blankCommentsKeepingLiterals(readMainSource(SCHEDULE_VIEW_MODEL))

        assertEquals(
            "`it.isManualOverride` 在 ${SCHEDULE_VIEW_MODEL_NAME} 里应当 0 处，实到 " +
                occurrences(viewModel, WRONG_EXPRESSION) + " 处（落在第 " +
                lineOf(readMainSource(SCHEDULE_VIEW_MODEL), viewModel.indexOf(WRONG_EXPRESSION)) + " 行附近）：" +
                "那一枚说的是「被用户手改过的教务行」（T133 之后 semesterCode 照样非空），" +
                "拿它数手动课程就是本卡的病灶原文，预览句会把拖过的教务课念成手动课程",
            0,
            occurrences(viewModel, WRONG_EXPRESSION),
        )
    }

    // ─────────────── ⑤ 第二份表达式不许长出来 ───────────────

    @Test
    fun `裸表达式 semesterCode 等 null 全仓只剩三处且落点在册`() {
        val byFile = codeByFile()
        val ledger = mapOf(
            // 判据本体
            COURSE_NAME to 1,
            // 「手动课 或 本学期课」的展示范围口径，另一件事
            COURSE_FILTER_NAME to 1,
            // 组件绑定配置里的 semesterCode（「跟随当前学期」），接收者不是 Course
            WIDGET_CONFIG_ACTIVITY_NAME to 1,
        )
        val actual = byFile.mapValues { occurrences(it.value, RAW_EXPRESSION) }.filterValues { it > 0 }

        assertEquals(
            "全仓 main 的裸 `semesterCode == null` 应当恰好 3 处（判据本体 1 + 两枚不同含义的既有写法）：" +
                "期望 " + ledger.values.sum() + " 枚，实到 " + actual.values.sum() + " 枚。" +
                "多出来的一枚就是「留了两份表达式」的那一枚；少一枚说明判据本体被改写字面、" +
                "或上面两枚既有写法被并进了这一判（那是另一件事，见段首）。\n" + filesHint(actual),
            ledger.values.sum(),
            actual.values.sum(),
        )
        assertEquals(
            "裸表达式的落点册子漂了：期望 " + ledger + "，实到 " + actual +
                "\n" + filesHint(byFile, RAW_EXPRESSION),
            ledger,
            actual,
        )
        assertEquals(
            "恢复侧 ${SCHEDULE_REPOSITORY_NAME} 里不许再现写裸表达式（判据只有 Course 那一枚属性），实到 " +
                occurrences(byFile.getValue(SCHEDULE_REPOSITORY_NAME), RAW_EXPRESSION) + " 处",
            0,
            occurrences(byFile.getValue(SCHEDULE_REPOSITORY_NAME), RAW_EXPRESSION),
        )
        assertEquals(
            "预览侧 ${SCHEDULE_VIEW_MODEL_NAME} 里同样不许现写裸表达式，实到 " +
                occurrences(byFile.getValue(SCHEDULE_VIEW_MODEL_NAME), RAW_EXPRESSION) + " 处",
            0,
            occurrences(byFile.getValue(SCHEDULE_VIEW_MODEL_NAME), RAW_EXPRESSION),
        )
        // 另两枚既有写法各自仍在原位（它们是**不同含义**的判据，本卡不许合并它们）
        assertEquals(
            "展示口径那枚（「手动课 或 本学期课」）在 ${COURSE_FILTER_NAME} 里应当仍是 1 处，实到 " +
                occurrences(blankCommentsKeepingLiterals(readMainSource(COURSE_FILTER)), RAW_EXPRESSION) + " 处",
            1,
            occurrences(blankCommentsKeepingLiterals(readMainSource(COURSE_FILTER)), RAW_EXPRESSION),
        )
        assertEquals(
            "组件绑定配置那枚（「跟随当前学期」，接收者不是 Course）在 ${WIDGET_CONFIG_ACTIVITY_NAME} 里应当仍是 1 处，实到 " +
                occurrences(blankCommentsKeepingLiterals(readMainSource(WIDGET_CONFIG_ACTIVITY)), RAW_EXPRESSION) + " 处",
            1,
            occurrences(blankCommentsKeepingLiterals(readMainSource(WIDGET_CONFIG_ACTIVITY)), RAW_EXPRESSION),
        )
    }

    // ─────────────── ⑥ SQL 孪生落点：Room 读不到属性，那一面单独钉 ───────────────

    @Test
    fun `CourseDao 里同一判据的 SQL 落点仍是对称的三条`() {
        val dao = blankCommentsKeepingLiterals(readMainSource(COURSE_DAO))

        assertEquals(
            "`semesterCode IS NULL` 在 ${COURSE_DAO_NAME} 里应当恰好 3 处（getDisplay / getManualCourses / " +
                "getBySemestersOrManual），实到 " + occurrences(dao, SQL_EXPRESSION) +
                " 处：哪一条换了谓词（例如补上 isManualOverride），SQL 侧与 Kotlin 侧就分家了",
            3,
            occurrences(dao, SQL_EXPRESSION),
        )
        assertEquals(
            "取本地手动课程的那条查询必须逐字仍是 `$DAO_QUERY`（实到 " + occurrences(dao, DAO_QUERY) + " 处）",
            1,
            occurrences(dao, DAO_QUERY),
        )
        assertEquals(
            "`$DAO_FUN` 声明应当恰好 1 处，实到 " + occurrences(dao, DAO_FUN) + " 处",
            1,
            occurrences(dao, DAO_FUN),
        )
        val queryAt = dao.indexOf(DAO_QUERY)
        val funAt = dao.indexOf(DAO_FUN)
        assertTrue(
            "那条 @Query 必须紧贴在 getManualCourses() 声明之上（配对关系一变，" +
                "上面那 3 处枚数就对不上）：query@$queryAt / fun@$funAt",
            queryAt in 0 until funAt && !dao.substring(queryAt + DAO_QUERY.length, funAt).contains("fun "),
        )
        // 恢复流程真的按这条查询取本地手动课（不是 getAll 再在内存里过滤）
        val repository = blankCommentsKeepingLiterals(readMainSource(SCHEDULE_REPOSITORY))
        assertEquals(
            "恢复侧靠 `courseDao.getManualCourses()` 取本地手动课程，册子上是 2 处" +
                "（去重取键 + codes 为空时的兜底回查），实到 " + occurrences(repository, DAO_CALL) + " 处",
            2,
            occurrences(repository, DAO_CALL),
        )
    }

    // ─────────────── ⑦ 同一个词的两枚措辞各只与一个表达式同框 ───────────────

    @Test
    fun `手动课程那两枚措辞各只出现一次`() {
        val byFile = codeByFile()
        val total = byFile.values.sumOf { occurrences(it, PREVIEW_WORDING) }
        val totalInserted = byFile.values.sumOf { occurrences(it, RESTORE_WORDING) }

        assertEquals(
            "预览句的措辞 `$PREVIEW_WORDING` 全仓 main 应当恰好 1 处（渲染点 ${SETTINGS_SCREEN_NAME}），实到 " +
                total + " 处：一枚词两处同框 = 两套定义回来的样子",
            1,
            total,
        )
        assertEquals(
            "渲染点仍念判据喂出来的那个字段（`${SETTINGS_SCREEN_NAME}` 里 `其中手动课程` 与 " +
                "`manualCourseCount` 同框，各 1 处），实到 " +
                occurrences(blankCommentsKeepingLiterals(readMainSource(SETTINGS_SCREEN)), PREVIEW_WORDING) +
                " / " + occurrences(blankCommentsKeepingLiterals(readMainSource(SETTINGS_SCREEN)), PREVIEW_FIELD) + " 处",
            1,
            occurrences(blankCommentsKeepingLiterals(readMainSource(SETTINGS_SCREEN)), PREVIEW_WORDING),
        )
        assertEquals(
            "恢复句的措辞 `$RESTORE_WORDING` 全仓 main 应当恰好 1 处（装配点 ${SCHEDULE_VIEW_MODEL_NAME}），实到 " +
                totalInserted + " 处",
            1,
            totalInserted,
        )
        assertEquals(
            "`manualCourseCount` 这个字段在 main 里应当 3 处（BackupPreview 声明 + 赋值 + 渲染），实到 " +
                byFile.values.sumOf { occurrences(it, PREVIEW_FIELD) } + " 处",
            3,
            byFile.values.sumOf { occurrences(it, PREVIEW_FIELD) },
        )
    }

    // ─────────────── 读文件与文本工具（照 ui/CourseDeletionWiringGuardTest 的写法） ───────────────

    /** 全仓 main 源码（抹注释、保留字符串字面量）：文件名 → 文本 */
    private fun codeByFile(): Map<String, String> {
        val root = File(findMainJavaDir(), "com/buaa/schedule")
        assertTrue("找不到 $root：包结构挪过家，本守卫要跟着改", root.isDirectory)
        val files = root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
        val out = linkedMapOf<String, String>()
        files.forEach {
            check(!out.containsKey(it.name)) { "main 源码里有同名文件 ${it.name}，按文件名建册子会读错份" }
            out[it.name] = blankCommentsKeepingLiterals(it.readText())
        }
        assertEquals("扫 main 源码的文件数与建出来的册子条目数不等", files.size, out.size)
        assertTrue("扫 main 源码扫出 0 份文件：$root", out.isNotEmpty())
        return out
    }

    private fun filesHint(byFile: Map<String, String>, needle: String): String =
        byFile.filter { occurrences(it.value, needle) > 0 }
            .entries
            .joinToString(separator = "") { "  ${it.key}: ${occurrences(it.value, needle)} 处\n" }

    /** 与 filesHint 的另一种入参配对：册子已经是「只留命中的」那一份 */
    private fun filesHint(hitLedger: Map<String, Int>): String =
        hitLedger.entries.joinToString(separator = "") { "  ${it.key}: ${it.value} 处\n" }

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

    /** 失败消息把人带到那一处：needle 在**原文**（不是抹注释后的文本）里的行号 */
    private fun lineOf(rawSource: String, position: Int): Int =
        if (position < 0) -1 else rawSource.substring(0, position).count { it == '\n' } + 1

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

    /** 只抹注释、保留字符串字面量，长度与换行位置不变（行号因此仍然可信） */
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

    private companion object {
        // 文件
        const val COURSE = "com/buaa/schedule/domain/model/Course.kt"
        const val SCHEDULE_VIEW_MODEL = "com/buaa/schedule/ui/ScheduleViewModel.kt"
        const val SCHEDULE_REPOSITORY = "com/buaa/schedule/data/repository/ScheduleRepository.kt"
        const val COURSE_FILTER = "com/buaa/schedule/domain/schedule/CourseFilter.kt"
        const val WIDGET_CONFIG_ACTIVITY = "com/buaa/schedule/widget/WidgetConfigActivity.kt"
        const val COURSE_DAO = "com/buaa/schedule/data/local/CourseDao.kt"
        const val SETTINGS_SCREEN = "com/buaa/schedule/ui/settings/SettingsScreen.kt"
        const val COURSE_NAME = "Course.kt"
        const val SCHEDULE_VIEW_MODEL_NAME = "ScheduleViewModel.kt"
        const val SCHEDULE_REPOSITORY_NAME = "ScheduleRepository.kt"
        const val COURSE_FILTER_NAME = "CourseFilter.kt"
        const val WIDGET_CONFIG_ACTIVITY_NAME = "WidgetConfigActivity.kt"
        const val COURSE_DAO_NAME = "CourseDao.kt"
        const val SETTINGS_SCREEN_NAME = "SettingsScreen.kt"

        // 判据本体与裸表达式
        const val PROPERTY_NAME = "isManualCourse"
        const val DECLARATION = "val isManualCourse: Boolean get() = semesterCode == null"
        const val RAW_EXPRESSION = "semesterCode == null"
        const val WRONG_EXPRESSION = "it.isManualOverride"

        // 两侧接线
        const val VM_WIRING = "manualCourseCount = data.courses.count { it.toDomain().isManualCourse },"
        const val VM_COUNT_HEAD = "data.courses.count {"
        const val REPO_WIRING = "val manual = normalized.filter { it.isManualCourse }"
        const val REPO_INSERTED = "insertedManual = manualToInsert.size,"

        // SQL 孪生落点
        const val SQL_EXPRESSION = "semesterCode IS NULL"
        const val DAO_QUERY = "@Query(\"SELECT * FROM courses WHERE semesterCode IS NULL\")"
        const val DAO_FUN = "suspend fun getManualCourses(): List<CourseEntity>"
        const val DAO_CALL = "courseDao.getManualCourses()"

        // 两枚措辞与预览字段
        const val PREVIEW_WORDING = "其中手动课程 "
        const val RESTORE_WORDING = "新增手动课程 "
        const val PREVIEW_FIELD = "manualCourseCount"
    }
}
