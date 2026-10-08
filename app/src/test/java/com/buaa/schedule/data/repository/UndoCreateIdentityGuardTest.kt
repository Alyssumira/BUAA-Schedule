package com.buaa.schedule.data.repository

import com.buaa.schedule.ui.UndoFailureKind
import com.buaa.schedule.ui.undoFailureKindOf
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T287③：源码形状守卫 —— 把「撤销『新增课程』那一支不许不查身份就删行，且拒删那一档的条目还得回得来」
 * 钉在盘上（#527，甲的接线 + 丁的处置）。
 *
 * 病灶是三行连起来才成立的（每一行单看都「看着没问题」），所以本守卫逐层只管其中一行：
 * ① Create 支的删行那一手必须排在判据之后（**这一层就是 arm W 的那一层**）
 * ② 删行的入参只能来自判据交回的那一行（不许拿手里那份快照去删）
 * ③ 拒删那一支的条目回得来（全仓只有 VM 那一枚消费点，本卡零裸 pop、零自建回推）
 * ④ 判据内核纯 JVM、结论域恰好三档、全仓只有一份
 * ⑤ 在册守卫的枚数没被本卡搬动（动了哪一块逐枚声明）
 * ⑥ 探测器自己认得改前那一形（不空转）。
 *
 * 纯源码核对（JVM，无 Robolectric、无设备、不读时钟）。读法与小工具抄
 * `data/repository/UndoReinsertIdentityGuardTest`（抹注释保字面量 + 花括号配平取块 + 原文切片）。
 * 守卫读 `app/src/main/java` 下的工作树文本 —— 那一目录已在 `app/build.gradle.kts` 的
 * `guardReadWorkingTreeFiles` 里整目录进 `tasks.withType<Test>()` 的 inputs（T92①，台账 #136），
 * 所以「改了源码、单跑守卫拿到 Gradle 缓存绿灯」那一档盖得住。
 *
 * ## 四种钉法逐格点名
 *
 * 1. [Create 支删行必须过判据] —— **逐字节**（`is UndoManager.UndoAction.Create -> undoCreate(action)`
 *    那一行整枚钉死）+ **抹注释找锚点**（改前那一手 `?.toDomain()?.let { deleteCourseRow(it) }` 的
 *    逐字本体必须 0 处 —— 它逐字登记在 ① 的 KDoc 里，所以只有抹了注释才归零）+
 *    **数出现次数**（`undoCreate` 体内 `undoCreateDeletion(` / `deleteCourseRow(` / `throw
 *    UndoCreateRefusedException(` 各恰一枚）+ **读形状**（配平取出那一段体，判那手必须排在删那手之前）。
 * 2. [删行的入参来自判据] —— **逐字节**（`deleteCourseRow(disposition.row)` 整枚）+
 *    **数出现次数**（三枚"拿手里那份删"的形状各 0 处；全文件 `deleteCourseRow(` 恰四枚）。
 * 3. [拒删那一档的条目还回得来] —— **数出现次数**（仓储里 `UndoManager.pop()` / `restoreTop(` 各 0 枚、
 *    全 main 各恰一枚）+ **逐字节**（VM 的 `undo()` 仍只有一枚 `repository.applyUndo(`）+
 *    **读形状**（按 FQN 走 `undoFailureKindOf` 那张表，本异常必须落进回推那一档）+
 *    **抹注释找锚点**（`UndoFailureRecovery.kt` 抹注释后不许出现本异常的类名 —— 表里一旦点名它，
 *    判成 `RejectedBySnapshot` 就正好犯下 #183 那次的病）。
 * 4. [内核纯度与结论域] —— **读形状**（逐行扫 import）+ **数出现次数**（禁词表 0 处、
 *    `fun undoCreateDeletion(` 全 main 恰一枚、` : UndoCreateDeletion` 恰三枚 = 恰好三档、
 *    `): Boolean` 0 处 = 判据没折成 Boolean）。
 * 5. [枚数没被搬动] —— **数出现次数**：`insertWithReminders(` 全文件恰五枚且 `applyUndo` 体内恰两枚
 *    （`UndoReinsertIdentityGuardTest` 的锚点）、`if (action.afterId != null && …` 恰一枚 +
 *    `ImportPlanner.courseKey(` 恰十一枚（`UndoUpdateEntryGuardTest` 的锚点）、
 *    `courseDao.getById(` 恰七枚。本卡把 Create 那一枚 getById 从 `applyUndo` 体搬进 `undoCreate` 体，
 *    **枚数一枚没变**，两枚在册守卫因此都不需重钉（这一层就是那句话说给下一位读者的地方）。
 * 6. [探测器不空转] —— **逐字节**：改前那一形喂进同一把尺必须命中恰一处、今天那一形必须 0 命中。
 *
 * 本守卫只读源码 + 走那张纯 JVM 的类名表，**不证明**运行时代码真的执行到：Room 那一趟事务到底删没删行、
 * 提示条在屏上念成什么样 —— 那些归编排者装机（`:app` 的 JVM 测试面没有 Robolectric，也没有能在 JVM
 * 上跑的 Room 缝）。判据逐格取值由 `UndoCreateIdentityTest` 钉。
 */
class UndoCreateIdentityGuardTest {

    // ─────────────── ① Create 支的删行那一手必须排在判据之后（arm W 的红在这里）───────────────

    @Test
    fun `Create 支只剩接线 改前那一手在代码里归零`() {
        val repo = blankCommentsKeepingLiterals(source(REPOSITORY))
        val applyUndo = braceBodyFrom(repo, repo.indexOf('{', repo.indexOf(UNDO_ENTRY_ANCHOR)))
        assertEquals(
            "applyUndo 的 Create 支必须逐字节是「$CREATE_ARM」（实到 " +
                occurrences(applyUndo, CREATE_ARM) + " 处）：判据挪进 undoCreate 之后，" +
                "这一支如果又长出 ?.let 之类的直接删行，就是有人绕开了那枚内核",
            1,
            occurrences(applyUndo, CREATE_ARM),
        )
        assertEquals(
            "改前那一手「$LEGACY_CREATE_TAIL」在仓储代码里必须 0 处（实到 " +
                occurrences(repo, LEGACY_CREATE_TAIL) + " 处）：它逐字登记在 undoCreate 的 KDoc 里" +
                "（登记旧形状用），所以**抹了注释**才该归零 —— 不抹注释就数是假的绿",
            0,
            occurrences(repo, LEGACY_CREATE_TAIL),
        )
        assertEquals(
            "「$UNDO_CREATE_ANCHOR」恰好一处定义（实到 " + indexOfAll(repo, UNDO_CREATE_ANCHOR).size +
                " 处）：Create 那一支的落点只许一枚，两处就说明撤销链上长了第二支新建课",
            1,
            indexOfAll(repo, UNDO_CREATE_ANCHOR).size,
        )
    }

    @Test
    fun `undoCreate 体内三手各一枚 且判据排在删行之前`() {
        val repo = blankCommentsKeepingLiterals(source(REPOSITORY))
        val body = braceBodyFrom(repo, repo.indexOf('{', repo.indexOf(UNDO_CREATE_ANCHOR)))
        assertEquals(
            "体内「$RULER_TAKE」恰一枚（实到 " + occurrences(body, RULER_TAKE) +
                " 处）：判据本体只调用一次，两处就是量了两回、中间那一趟可能被库里别的手改过",
            1,
            occurrences(body, RULER_TAKE),
        )
        assertEquals(
            "体内「deleteCourseRow(」恰一枚（实到 " + occurrences(body, "deleteCourseRow(") +
                " 处）：那一支唯一的写库出口",
            1,
            occurrences(body, "deleteCourseRow("),
        )
        assertEquals(
            "体内「$THROW_CALL」恰一枚（实到 " + occurrences(body, THROW_CALL) +
                " 处）：拒删那一档必须把话说出去。摘掉它 = 屏幕没变却念「已撤销：新增课程」，" +
                "而条目已经在落库之前被 pop 消费掉（#183 的形状）",
            1,
            occurrences(body, THROW_CALL),
        )
        val readAt = body.indexOf("courseDao.getById(action.course.id)")
        val rulerAt = body.indexOf(RULER_TAKE)
        val deleteAt = body.indexOf("deleteCourseRow(")
        assertTrue(
            "那一格必须先**读回来**、再交判据、最后才删：读行 → 判据 → 删行三拍不许换序" +
                "（「先删再判」等于判据不参与写库，与 CourseDeletionWiringGuardTest 钉 deleteCourse " +
                "那一支的同族判据一个口径）。实到 readAt=$readAt / rulerAt=$rulerAt / deleteAt=$deleteAt",
            readAt in 0 until rulerAt && rulerAt < deleteAt,
        )
    }

    // ─────────────── ② 删行的入参只能来自判据交回的那一行 ───────────────

    @Test
    fun `删的是判据交回的那一行 不是手里那份快照`() {
        val repo = blankCommentsKeepingLiterals(source(REPOSITORY))
        val body = braceBodyFrom(repo, repo.indexOf('{', repo.indexOf(UNDO_CREATE_ANCHOR)))
        assertEquals(
            "体内必须逐字节出现「$DELETE_CALL」恰一枚（实到 " + occurrences(body, DELETE_CALL) +
                " 处）：DeleteRow 那一档交回的就是事务里读回来的那一行",
            1,
            occurrences(body, DELETE_CALL),
        )
        for (needle in listOf("deleteCourseRow(action.course)", "deleteCourseRow(course)", "deleteCourseRow(held)")) {
            assertEquals(
                "体内不许出现「$needle」实到 " + occurrences(body, needle) +
                    " 处：按调用方手里那份对象删，就把「那一格上今天是谁的」这件事又交回调用方猜了" +
                    "（同文件 deleteCourse 那支的守卫钉的是同一枚病）",
                0,
                occurrences(body, needle),
            )
        }
        assertEquals(
            "仓储文件里「deleteCourseRow(」恰四枚（声明 + deleteCourse + undoUpdate 拆行那一步 + " +
                "undoCreate；实到 " + occurrences(repo, "deleteCourseRow(") +
                " 处）：枚数按声明与调用点算，不按行号算。涨一枚就说明有人给撤销链添了第二手不查身份的删行",
            4,
            occurrences(repo, "deleteCourseRow("),
        )
    }

    // ─────────────── ③ 拒删那一支：条目还回得来（零裸 pop、零自建回推）───────────────

    @Test
    fun `仓储不自己摘条目也不自己回推 消费点全仓只有 VM 那一枚`() {
        val repo = blankCommentsKeepingLiterals(source(REPOSITORY))
        for (needle in listOf("UndoManager.pop()", "UndoManager.restoreTop(", "stack.removeLast")) {
            assertEquals(
                "仓储里「$needle」必须 0 处（实到 " + occurrences(repo, needle) +
                    " 处）：回推唯一的凭据是 UndoManager.lastPopped 那张票据，只有 VM 那一枚消费点有资格动。" +
                    "仓储自己 pop/回推 = 第二把尺，也正是 #183 那格的病根（条目被吃掉而没人负责还）",
                0,
                occurrences(repo, needle),
            )
        }
        assertEquals(
            "全 app/src/main 里「UndoManager.pop()」恰一枚调用点（实到 " +
                mainOccurrenceCount("UndoManager.pop()") + " 处）",
            1,
            mainOccurrenceCount("UndoManager.pop()"),
        )
        assertEquals(
            "全 app/src/main 里「UndoManager.restoreTop(」恰一枚调用点（实到 " +
                mainOccurrenceCount("UndoManager.restoreTop(") + " 处）：本卡复用那一枚，没新造出口",
            1,
            mainOccurrenceCount("UndoManager.restoreTop("),
        )
        val viewModel = blankCommentsKeepingLiterals(source(SCHEDULE_VIEW_MODEL))
        assertEquals(
            "VM 里「repository.applyUndo(」恰一枚（实到 " + occurrences(viewModel, "repository.applyUndo(") +
                " 处）：applyUndo 仍只有一枚读者，本卡没为「这句话怎么说」另开一条返回值通道",
            1,
            occurrences(viewModel, "repository.applyUndo("),
        )
    }

    @Test
    fun `拒删抛的那一枚落进回推那一档 而那张表永远不许点名它`() {
        val kind = undoFailureKindOf(listOf(EXCEPTION_FQN))
        assertEquals(
            "「$EXCEPTION_FQN」按类名查表必须落进兜底档（实到 $kind）：兜底档才是「留条目、不许诺重试」。" +
                "判成 RejectedBySnapshot ⇒ 条目被吃掉，正是 #183 记过的那次事故；" +
                "判成 WriteLockBusy ⇒ 对用户许诺一句「可以再点一次」而重试永远是同一个结果",
            UndoFailureKind.Unexplained,
            kind,
        )
        assertTrue(
            "兜底档必须真的回推条目（restoreEntry 实到 ${kind.restoreEntry}）：判据说是留、" +
                "栈却不还回去，本卡那句「条目还回得来」就是空话",
            kind.restoreEntry,
        )
        assertEquals(
            "兜底档不许诺再点一次（retryInvitation 实到 ${kind.retryInvitation}）：那一格上坐着别人时，" +
                "环境不变再点也是同一个答复",
            false,
            kind.retryInvitation,
        )
        val recovery = blankCommentsKeepingLiterals(source(FAILURE_RECOVERY))
        assertEquals(
            "UndoFailureRecovery.kt 的代码里不许出现「UndoCreateRefusedException」（实到 " +
                occurrences(recovery, "UndoCreateRefusedException") + " 处）：那张类名表一点名它，" +
                "上面三格就有一格当场变红，而本卡的处置（留条目 + 不许诺）得跟着重判 —— " +
                "所以钉成源码级：改表必须同时看见这一层",
            0,
            occurrences(recovery, "UndoCreateRefusedException"),
        )
    }

    // ─────────────── ④ 判据内核：纯、唯一、结论域恰好三档 ───────────────

    @Test
    fun `判据内核零 android 零时钟 三档恰好 结论没折成 Boolean`() {
        val raw = source(KERNEL)
        val code = blankCommentsKeepingLiterals(raw)
        val offenders = raw.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("import ") }
            .filter { it.contains("android.") || it.contains("androidx.") }
            .toList()
        assertEquals(
            "纯 JVM 判据必须**零 android / androidx import**（仓库硬规矩：设备面一律由调用点当参数传进来）。" +
                "实到：" + offenders,
            emptyList<String>(),
            offenders,
        )
        for (probe in listOf(
            "LocalDate.now(", "System.currentTimeMillis", "System.", "Calendar.", "java.time.Clock",
            "Instant.", "ZonedDateTime", "TimeZone", "ZoneId", "Date(", "Build.VERSION", "Context",
            "File(", "readText", "room.", "Room.", "CourseDao", "courseDao", "reminderDao",
        )) {
            assertEquals(
                "内核里「$probe」必须 0 处（实到 " + occurrences(code, probe) +
                    " 处）：判据一旦能读钟/时区/权限/机型/上下文/DAO/文件系统，同一枚输入就会因" +
                "「今天几点、在哪台机器上、库里此刻有什么」给出不同结论，表驱动单测随之失真",
                0,
                occurrences(code, probe),
            )
        }
        assertEquals(
            "「fun undoCreateDeletion(」在 app/src/main 恰好一处定义（实到 " +
                mainOccurrenceCount("fun undoCreateDeletion(") + " 处）：第二份定义 = 两把尺量同一件事",
            1,
            mainOccurrenceCount("fun undoCreateDeletion("),
        )
        assertEquals(
            "结论域恰好三档（内核里「 : UndoCreateDeletion」恰三枚实现，实到 " +
                occurrences(code, " : UndoCreateDeletion") + " 枚）：sealed interface 在 JVM 侧没有 " +
                ".entries，枚数只能在这里数。长出第四档 = 有人给删除这一族添了「读不到就插回去」那一手" +
                "（那是主行的结论，代价账在 GroupRowUndoPolicy 段首被判过一次）",
            3,
            occurrences(code, " : UndoCreateDeletion"),
        )
        assertEquals(
            "「class UndoCreateRefusedException」全 main 恰一枚定义（实到 " +
                mainOccurrenceCount("class UndoCreateRefusedException") + " 处）",
            1,
            mainOccurrenceCount("class UndoCreateRefusedException"),
        )
        assertEquals(
            "内核的判据本体不许折成 Boolean（「): Boolean」实到 " + occurrences(code, "): Boolean") +
                " 处）：三档里「读不到」与「被占了」的处置对用户是两件事（一个静默、一个要说话），" +
                "收成 Boolean 就把那一格抹平",
            0,
            occurrences(code, "): Boolean"),
        )
        assertEquals(
            "身份尺子只有全仓那一枚（内核里「ImportPlanner.courseKey(」恰两枚：量那一格上的、量快照的；" +
                "实到 " + occurrences(code, "ImportPlanner.courseKey(") + " 处）：本卡不许另立第二把尺",
            2,
            occurrences(code, "ImportPlanner.courseKey("),
        )
    }

    // ─────────────── ⑤ 在册守卫的枚数没被本卡搬动 ───────────────

    @Test
    fun `撤销链上在册守卫钉着的枚数一枚没变`() {
        val repo = blankCommentsKeepingLiterals(source(REPOSITORY))
        val applyUndo = braceBodyFrom(repo, repo.indexOf('{', repo.indexOf(UNDO_ENTRY_ANCHOR)))
        assertEquals(
            "仓储里「insertWithReminders(」恰五枚（一枚声明 + 四枚调用点；实到 " +
                occurrences(repo, "insertWithReminders(") + " 处）—— " +
                "UndoReinsertIdentityGuardTest 第 ② 层的尺，本卡一枚没动",
            5,
            occurrences(repo, "insertWithReminders("),
        )
        assertEquals(
            "applyUndo 体内「insertWithReminders(」恰两枚（Delete / DeleteGroup；实到 " +
                occurrences(applyUndo, "insertWithReminders(") + " 处）：Create 那一支不许去插行 —— " +
                "撤销一条「新增」插出第二行就是 phantom 重复行那一族",
            2,
            occurrences(applyUndo, "insertWithReminders("),
        )
        assertEquals(
            "undoUpdate 的拆行守卫锚点「$AFTER_ID_ANCHOR」恰一枚（实到 " +
                occurrences(repo, AFTER_ID_ANCHOR) + " 处）—— UndoUpdateEntryGuardTest 第 ⑧⑨ 层的尺",
            1,
            occurrences(repo, AFTER_ID_ANCHOR),
        )
        assertEquals(
            "仓储里「ImportPlanner.courseKey(」恰十一枚（实到 " + occurrences(repo, "ImportPlanner.courseKey(") +
                " 处）：Create 那一支**没有**在仓储里自己量尺子（量在 kernel 里），" +
                "所以撤销链上「谁在量这枚尺」的枚数一枚没涨 —— 涨了就是第二把尺的落点变了位置",
            11,
            occurrences(repo, "ImportPlanner.courseKey("),
        )
        assertEquals(
            "仓储里「courseDao.getById(」恰七枚（实到 " + occurrences(repo, "courseDao.getById(") +
                " 处）：本卡把 Create 那一枚从 applyUndo 体搬进 undoCreate 体，枚数没变 —— " +
                "搬动都在这层登记，别让它静默漂进别的守卫里",
            7,
            occurrences(repo, "courseDao.getById("),
        )
    }

    // ─────────────── ⑥ 探测器不空转：改前那一形必须被认出来 ───────────────

    @Test
    fun `探测器认得改前那一形 也不误伤今天那一形`() {
        val legacyBranch = """
            is UndoManager.UndoAction.Create ->
                courseDao.getById(action.course.id)?.toDomain()?.let { deleteCourseRow(it) }
        """.trimIndent()
        assertEquals(
            "把改前那一支喂给同一把尺，「$LEGACY_CREATE_TAIL」必须命中恰一处（实到 " +
                occurrences(legacyBranch, LEGACY_CREATE_TAIL) + " 处）：命不中就说明这把尺根本不认得" +
                "要防的那个形状，第 ① 层那句「必须 0 处」就是假的绿",
            1,
            occurrences(legacyBranch, LEGACY_CREATE_TAIL),
        )
        val repo = blankCommentsKeepingLiterals(source(REPOSITORY))
        val today = braceBodyFrom(repo, repo.indexOf('{', repo.indexOf(UNDO_ENTRY_ANCHOR)))
        assertEquals(
            "今天那一支喂给同一把尺必须 0 命中（实到 " + occurrences(today, LEGACY_CREATE_TAIL) + " 处）",
            0,
            occurrences(today, LEGACY_CREATE_TAIL),
        )
    }

    // ---------------- 源码核对小工具（与仓内其它静态守卫同一口径）----------------

    private fun source(relativeFromMainJava: String): String {
        val file = File(findMainJavaDir(), relativeFromMainJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    private fun mainFiles(): List<String> {
        val root = findMainJavaDir()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${root.path} 下一个 .kt 都没有：路径不对，本守卫就是空的", files.isNotEmpty())
        return files.map { blankCommentsKeepingLiterals(it.readText().replace("\r\n", "\n")) }
    }

    private fun mainOccurrenceCount(needle: String): Int = mainFiles().sumOf { occurrences(it, needle) }

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
        return hits
    }

    private fun occurrences(haystack: String, needle: String): Int = indexOfAll(haystack, needle).size

    /** 从已知开括号位按花括号配平取出那一段体 */
    private fun braceBodyFrom(source: String, open: Int): String {
        check(source.getOrNull(open) == '{') {
            "锚点末尾不是花括号：写法变了，切不出一段体（实到「${source.getOrNull(open)}」）"
        }
        return source.substring(
            open + 1,
            (matchingBracket(source, open, '{', '}') ?: error("花括号没配平：锚点认错了")),
        )
    }

    /** 括号配平（跳过字符串与字符字面量）；不配平返回 null */
    private fun matchingBracket(src: String, open: Int, openChar: Char, closeChar: Char): Int? {
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
                c == '\'' -> {
                    var j = i + 1
                    if (src.getOrNull(j) == '\\') j += 2 else j += 1
                    if (src.getOrNull(j) == '\'') i = j
                }

                c == openChar -> depth++
                c == closeChar -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    /**
     * **只抹注释、保留字符串字面量内容**，长度与换行位置不变（本守卫的报错消息一律不带行号，
     * 行号会随注释与 KDoc 漂；这里保长度是为了让切片与配平仍然对得上）。
     */
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

    private companion object {
        const val REPOSITORY = "com/buaa/schedule/data/repository/ScheduleRepository.kt"
        const val KERNEL = "com/buaa/schedule/data/repository/UndoCreateIdentity.kt"
        const val SCHEDULE_VIEW_MODEL = "com/buaa/schedule/ui/ScheduleViewModel.kt"
        const val FAILURE_RECOVERY = "com/buaa/schedule/ui/UndoFailureRecovery.kt"

        const val UNDO_ENTRY_ANCHOR = "suspend fun applyUndo(action: UndoManager.UndoAction)"
        const val UNDO_CREATE_ANCHOR = "private suspend fun undoCreate(action: UndoManager.UndoAction.Create)"
        const val CREATE_ARM = "is UndoManager.UndoAction.Create -> undoCreate(action)"
        const val RULER_TAKE = "undoCreateDeletion(held, action.course)"
        const val DELETE_CALL = "deleteCourseRow(disposition.row)"
        const val THROW_CALL = "throw UndoCreateRefusedException(disposition.reason)"
        const val AFTER_ID_ANCHOR = "if (action.afterId != null && action.afterId != action.before.id) {"

        /** 改前那一手的逐字尾巴（① 层与 ⑥ 层共用；今天只在 KDoc 里逐字登记着） */
        const val LEGACY_CREATE_TAIL = "?.toDomain()?.let { deleteCourseRow(it) }"

        /** 拒删那一档抛的那一枚：FQN 逐字，因为 UndoFailureRecovery 那张表按 FQN 查 */
        const val EXCEPTION_FQN = "com.buaa.schedule.data.repository.UndoCreateRefusedException"
    }
}
