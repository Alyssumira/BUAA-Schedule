package com.buaa.schedule.data.local

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T249：学期行「取哪一枚 / 删哪一枚 / 几枚事务」的源码形状与接线守卫（台账 #460 / #461）。
 *
 * 纯源码核对（JVM，无 Robolectric、无设备、不读时钟）。读法与小工具抄
 * `data/backup/BackupSemesterRestoreWiringGuardTest`（抹注释保字面量 + 花括号配平取块 +
 * 锚点找不到就抛）。本守卫读 `app/src/main/java` 下的源码文本 —— 那一条已在
 * `app/build.gradle.kts` 的 `guardReadWorkingTreeFiles` 里整目录进 `tasks.withType<Test>()`
 * 的 `inputs`，所以"改了源码、单跑守卫拿到 Gradle 缓存绿灯"那一档盖得住；本次没有新增目录之外的读取面。
 *
 * ## 钉法（逐格点名）
 *
 * 1. [读侧形状：那条 termCode 查询必须含 ORDER BY] —— **逐字**钉 `getByTermCode` /
 *    `getRowsByTermCode` 两枚 `@Query` 串，外加一把对照尺：`semesters` 的每一条 SELECT
 *    都必须带 `ORDER BY`（改前全文件三族里只有这一族没有，它就是这个病本体）。
 *    口径本体在 `SemesterRowPolicy.kt` 的 `pickSemesterRowForTermCode`（id 降序），两侧同尺。
 * 2. [切换那一跳按 id 精确删] —— **读形状**（取 `switchSemester` 的体）+ **数出现次数**：
 *    体内 `deleteByTermCode(` 0 处（改前是 1 处，那一枚就是"吃掉兄弟行"的刀）、
 *    `deleteById(` 1 处、`getRowsByTermCode(` 1 处、`planSemesterRowBump(` 1 处。
 * 3. [写路级 termCode 收口还在] —— 枚枚点名 `saveSemester` / `replaceSemesterCoursesInTx` 的体里
 *    `deleteByTermCode(` + `insert(` 各 1 处，加上内核里 `DeleteRowsForTermCode(target.termCode)` 1 处：
 *    「同 termCode 只有一行」今天由这一族的写路供给（数据库层面仍不禁止，见第 ⑦ 格）。
 * 4. [改代码那一跳只调仓储一次] —— **读形状**取 VM 的 `fun saveSemester(semester: Semester)` 体 +
 *    **数出现次数**：体内 `repository.` 恰好 1 处。改前是 3 处
 *    （`getCurrentSemester` + `renameSemesterCourses` + `saveSemester` = 两枚事务中间那一格死亡窗口）。
 * 5. [合并方法只裹一枚事务、写全由计划供给] —— 取 `saveSemesterWithRename` 的体：
 *    `writeMutex.withLock` 1 处且在头一枚非空字符上（锁的外层位置不许挪）、`db.withTransaction` 1 处、
 *    `applySemesterRowSteps(` 1 处、体内裸写 `semesterDao.insert(` / `deleteByTermCode(` 各 0 处。
 * 6. [拒绝档那句文案与顺序] —— 逐字 + **顺序**：`isError = true` 1 处、
 *    `_importMessage.value` → `return@launch` → `afterDataChangedInternal()` → `showMessage(...)`。
 * 7. [本卡的裁定：没动 schema] —— `SemesterEntity.kt` 里 `indices` 0 处。
 *    第 0 问的裁定是「代码级写路收口 + 确定性读」，唯一索引那一档要迁移，归编排者另卡；
 *    谁真去加唯一索引，这一格必须跟着重钉并交代重复行怎么办。
 * 8. [探测器自己认得旧那一形] —— 每一层都配一枚靶子：把改之前的逐字原文喂给探测器，
 *    它必须数得出那一形（否则"0 处"是假的绿）；合法形状（新 SQL、新 body）不许误伤。
 * 9. [内核纯度] —— 零 android / androidx、零时钟、不碰 Room；
 *    六枚判据在全 `app/src/main` 各恰好一处定义（第二份定义 = 两把尺量同一件事）。
 */
class SemesterRowShapeGuardTest {

    // ─────────────── ① 读侧形状：termCode 查询必须含 ORDER BY ───────────────

    @Test
    fun `termCode 级读数在 SQL 里就是确定的那一枚`() {
        val dao = bodyOf(source(DAO))

        assertEquals(
            "`$GET_BY_TERM_CODE_QUERY` 必须逐字还在（实到 " + occurrences(dao, GET_BY_TERM_CODE_QUERY) +
                " 处）：#460 的读侧确定性就长在这一枚 `ORDER BY id DESC` 上。摘掉 ORDER BY 之后" +
                "`LIMIT 1` 交出哪一枚由 SQLite 的扫描顺序决定 —— 同一问题两次读数可以给出两个答案。" +
                "口径本体是 `SemesterRowPolicy.kt` 的 `pickSemesterRowForTermCode`（同样 id 降序），" +
                "两边同尺才有意义",
            1,
            occurrences(dao, GET_BY_TERM_CODE_QUERY),
        )
        assertEquals(
            "`$GET_ROWS_BY_TERM_CODE_QUERY` 必须逐字还在（实到 " + occurrences(dao, GET_ROWS_BY_TERM_CODE_QUERY) +
                " 处）：切换那一跳读的是**全部**同键行，枚数与顺序都由内核判，" +
                "所以这条也必须 ORDER BY id DESC（没有 LIMIT）",
            1,
            occurrences(dao, GET_ROWS_BY_TERM_CODE_QUERY),
        )
        assertEquals(
            "`$DELETE_BY_ID_QUERY` 必须逐字还在（实到 " + occurrences(dao, DELETE_BY_ID_QUERY) +
                " 处）：这就是「按 id 精确删」那把刀，替代 `WHERE termCode = ?` 的连坐删除",
            1,
            occurrences(dao, DELETE_BY_ID_QUERY),
        )

        // 对照尺：改前「全文件三族查询里只有 getByTermCode 没有 ORDER BY」，现在一条都不许剩
        val raw = source(DAO)
        val unordered = mutableListOf<String>()
        SELECT_QUERY.findAll(dao).forEach { match ->
            if (!match.value.contains("ORDER BY")) {
                unordered += "第 ${lineOf(raw, raw.indexOf(match.value))} 行：${match.value}"
            }
        }
        assertEquals(
            "`semesters` 的 SELECT 里不许留下一条没有 ORDER BY 的（实到 " + unordered.joinToString("; ") + "）：" +
                "无 ORDER BY 的 `LIMIT 1` / 无 ORDER BY 的列表都是「读哪一枚由扫描顺序决定」那一档",
            emptyList<String>(),
            unordered,
        )
    }

    @Test
    fun `探测器自己认得改前那一条无序查询`() {
        val oldShape = """
            @Query("SELECT * FROM semesters WHERE termCode = :termCode LIMIT 1")
            suspend fun getByTermCode(termCode: String): SemesterEntity?
        """.trimIndent()
        assertEquals(
            "探测器必须抓得出改前那一条（实到 " +
                occurrences(blankCommentsKeepingLiterals(oldShape), OLD_GET_BY_TERM_CODE_QUERY) + " 处）：" +
                "0 处就说明上面那句「ORDER BY 逐字还在」是假的绿",
            1,
            occurrences(blankCommentsKeepingLiterals(oldShape), OLD_GET_BY_TERM_CODE_QUERY),
        )
        assertEquals(
            "而盘上新那一形**不许**被算成旧形（实到 " + occurrences(bodyOf(source(DAO)), OLD_GET_BY_TERM_CODE_QUERY) +
                " 处）：把 ORDER BY 那条也数进去的话，这一层永远绿在错的地方",
            0,
            occurrences(bodyOf(source(DAO)), OLD_GET_BY_TERM_CODE_QUERY),
        )
        assertTrue(
            "`$DAO` 的注释里必须仍逐字登记着旧那一形（抹注释后为 0 处才算修复）。实到未抹注释 " +
                occurrences(source(DAO), OLD_GET_BY_TERM_CODE_QUERY) + " 处",
            occurrences(source(DAO), OLD_GET_BY_TERM_CODE_QUERY) >= 1,
        )
    }

    // ─────────────── ② 切换那一跳：按 id 精确删，不再连坐 ───────────────

    @Test
    fun `切换学期不再按 termCode 连坐删除`() {
        val repo = blankCommentsKeepingLiterals(source(REPOSITORY))
        val body = functionBody(repo, SWITCH_ANCHOR)

        assertEquals(
            "`switchSemester` 体内 `$OLD_SWITCH_SHAPE` 必须 0 处（实到 " +
                occurrences(body, OLD_SWITCH_SHAPE) + " 处）：那一枚就是「读任一行 + 删全部同键行 + " +
                "只插回读到的那一行」的连坐删除 —— 用户切一次学期，另一枚行的开学日/总周数就没了",
            0,
            occurrences(body, OLD_SWITCH_SHAPE),
        )
        assertEquals(
            "体内 `deleteByTermCode(` 必须 0 处（实到 " + occurrences(body, "deleteByTermCode(") +
                " 处）：这一跳不写新内容，没有权力按 termCode 收口",
            0,
            occurrences(body, "deleteByTermCode("),
        )
        assertEquals(
            "体内 `deleteById(` 恰好一处（实到 " + occurrences(body, "deleteById(") +
                " 处）：删的就是内核点出来的那一枚 id（`planSemesterRowBump` 的 deleteIds）",
            1,
            occurrences(body, "deleteById("),
        )
        for (needle in listOf("getRowsByTermCode(", "planSemesterRowBump(", "SemesterRowBumpPlan.NothingToSwitch")) {
            assertEquals(
                "体内 `$needle` 恰好一处（实到 " + occurrences(body, needle) + " 处）：" +
                    if (needle == "planSemesterRowBump(") {
                        "读到的全部同键行必须交给内核定「顶哪一枚、留哪几枚」，仓储层不许自己 maxByOrNull —— " +
                            "那会长出第二把尺"
                    } else {
                        "切换的读侧入口只有这一条路"
                    },
                1,
                occurrences(body, needle),
            )
        }
        assertSingleTransactionShape("switchSemester", body)
    }

    @Test
    fun `探测器自己认得切换那一跳的旧连坐形状`() {
        val oldBody = """
            suspend fun switchSemester(termCode: String): Boolean = writeMutex.withLock {
                db.withTransaction {
                    val target = semesterDao.getByTermCode(termCode) ?: return@withTransaction false
                    semesterDao.deleteByTermCode(termCode)
                    semesterDao.insert(target.copy(id = 0L))
                    UndoManager.clear()
                    true
                }
            }
        """.trimIndent()
        val blanked = blankCommentsKeepingLiterals(oldBody)
        assertEquals(
            "探测器必须抓得出改前那一手的连坐删除（实到 " + occurrences(blanked, OLD_SWITCH_SHAPE) +
                " 处）：0 处就是第 ② 层那句「0 处」在空转",
            1,
            occurrences(blanked, OLD_SWITCH_SHAPE),
        )
        assertEquals(
            "靶子里 `deleteByTermCode(` 必须恰好一处（实到 " + occurrences(blanked, "deleteByTermCode(") + " 处）",
            1,
            occurrences(blanked, "deleteByTermCode("),
        )
    }

    // ─────────────── ③ 写路级 termCode 收口（「同 termCode 只有一行」今天靠这个） ───────────────

    @Test
    fun `每一枚写内容的学期行都先按 termCode 收口`() {
        val repo = blankCommentsKeepingLiterals(source(REPOSITORY))
        for ((anchor, label) in listOf(
            SAVE_SEMESTER_ANCHOR to "设置页保存学期",
            REPLACE_IN_TX_ANCHOR to "整学期替换课程（导入 / 恢复主学期）",
        )) {
            val body = functionBody(repo, anchor)
            assertEquals(
                "`$anchor` 体内 `deleteByTermCode(` 恰好一处（$label；实到 " +
                    occurrences(body, "deleteByTermCode(") + " 处）：这一枚就是「每个 termCode 只保留一行」" +
                    "从注释级升到**写路级**的那一半 —— 数据库层面仍不禁止重复行（见第 ⑦ 格）",
                1,
                occurrences(body, "deleteByTermCode("),
            )
            assertEquals(
                "`$anchor` 体内 `semesterDao.insert(` 恰好一处（$label；实到 " +
                    occurrences(body, "semesterDao.insert(") + " 处）：收口之后只插一枚",
                1,
                occurrences(body, "semesterDao.insert("),
            )
        }

        val kernel = blankCommentsKeepingLiterals(source(POLICY))
        assertEquals(
            "内核里 `DeleteRowsForTermCode(target.termCode)` 恰好一处（实到 " +
                occurrences(kernel, "DeleteRowsForTermCode(target.termCode)") +
                " 处）：合并事务的那一枚收口步骤由内核排出来，不是仓储层自己写的。" +
                "删掉它 = 合并事务不再收口 = 同一 termCode 又能动辄两枚行",
            1,
            occurrences(kernel, "DeleteRowsForTermCode(target.termCode)"),
        )
        assertEquals(
            "内核里 `DeleteRowsForTermCode(oldCode)` 恰好一处（实到 " +
                occurrences(kernel, "DeleteRowsForTermCode(oldCode)") +
                " 处）：改代码那一跳删旧行的步骤也在内核里（与 renameSemesterCourses 共用）",
            1,
            occurrences(kernel, "DeleteRowsForTermCode(oldCode)"),
        )
    }

    // ─────────────── ④ + ⑥ VM 那一跳：一次仓储调用 + 那句文案与顺序 ───────────────

    @Test
    fun `改代码那一跳在 VM 里只调仓储一次`() {
        val vm = blankCommentsKeepingLiterals(source(VIEW_MODEL))
        val body = functionBody(vm, VM_SAVE_SEMESTER_ANCHOR)

        assertEquals(
            "VM 的 `saveSemester` 那一跳体内 `repository.` 恰好一处（实到 " + occurrences(body, "repository.") +
                " 处）：#461 的病灶就是这一跳里有**两枚独立事务**（rename 一枚、saveSemester 另一枚），" +
                "中间被杀 ⇒ 旧行已删、新行未插 ⇒ 落回「没设学期」。改前这里是 3 处" +
                "（`repository.getCurrentSemester()` + `repository.renameSemesterCourses(` + " +
                "`repository.saveSemester(`）。涨了就说明那格窗口被重新打开",
            1,
            occurrences(body, "repository."),
        )
        assertEquals(
            "那一处必须是合并事务 `$MERGED_CALL`（实到 " + occurrences(body, MERGED_CALL) + " 处）",
            1,
            occurrences(body, MERGED_CALL),
        )
        for (needle in listOf("repository.saveSemester(", "repository.renameSemesterCourses(", "repository.getCurrentSemester(")) {
            assertEquals(
                "体内 `$needle` 必须 0 处（实到 " + occurrences(body, needle) +
                    " 处）：半截形状（合并事务 + 单独一枚 saveSemester）比两枚事务更难看出来，" +
                    "所以三枚旧调用点一枚都不许留",
                0,
                occurrences(body, needle),
            )
        }
    }

    @Test
    fun `拒绝档那句文案逐字未改且排在任何收尾刷新之前`() {
        val vm = blankCommentsKeepingLiterals(source(VIEW_MODEL))
        val body = functionBody(vm, VM_SAVE_SEMESTER_ANCHOR)

        assertEquals(
            "拒绝档那句 `$REJECT_COPY` 恰好一处（实到 " + occurrences(body, REJECT_COPY) +
                " 处）：卡面 §5 点名这一档的文案与 isError 不许改（「新代码下已有别的课表」那一挡）",
            1,
            occurrences(body, REJECT_COPY),
        )
        assertEquals(
            "体内 `isError = true` 恰好一处（实到 " + occurrences(body, "isError = true") + " 处）",
            1,
            occurrences(body, "isError = true"),
        )
        val rejectAt = body.indexOf(REJECT_COPY)
        val returnAt = body.indexOf("return@launch", rejectAt)
        val refreshAt = body.indexOf("afterDataChangedInternal()")
        val doneAt = body.indexOf(SUCCESS_COPY)
        assertTrue(
            "顺序必须是 **报错文案 → return@launch → afterDataChangedInternal() → 成功文案**：" +
                "实到 reject=$rejectAt、return=$returnAt、refresh=$refreshAt、done=$doneAt。" +
                "被拒那一档跑过 afterDataChangedInternal 就等于宣称「保存过了」，" +
                "而合并事务在拒绝档一个字都没写",
            rejectAt in 0 until returnAt && returnAt < refreshAt && refreshAt < doneAt,
        )
        assertEquals(
            "成功那一句 `$SUCCESS_COPY` 恰好一处（实到 " + occurrences(body, SUCCESS_COPY) + " 处）",
            1,
            occurrences(body, SUCCESS_COPY),
        )
    }

    @Test
    fun `探测器自己认得改前两枚独立事务那一形`() {
        val oldShape = """
            fun saveSemester(semester: Semester) {
                viewModelScope.launch {
                    val old = repository.getCurrentSemester()
                    if (old != null && old.termCode != semester.termCode) {
                        if (!repository.renameSemesterCourses(old.termCode, semester.termCode)) {
                            _importMessage.value = AppMessage(
                                "学期代码 ${'$'}{semester.termCode} 下已有另一份课表，请先切换学期再改代码",
                                isError = true,
                            )
                            return@launch
                        }
                    }
                    repository.saveSemester(semester)
                    afterDataChangedInternal()
                    showMessage("学期设置已保存", isSuccess = true)
                }
            }
        """.trimIndent()
        val blanked = blankCommentsKeepingLiterals(oldShape)
        assertEquals(
            "探测器必须在改前那一手上数出 3 枚 `repository.`（实到 " + occurrences(blanked, "repository.") +
                " 处）：第 ④ 层那句「恰好一处」若连 3 处都数不出来，它就是永远绿的空尺",
            3,
            occurrences(blanked, "repository."),
        )
        assertEquals(
            "改前那一手必须同时含 `$MERGED_CALL` 之外的两枚旧调用（实到 " +
                occurrences(blanked, "repository.renameSemesterCourses(") + " + " +
                occurrences(blanked, "repository.saveSemester(") + " 处）",
            2,
            occurrences(blanked, "repository.renameSemesterCourses(") + occurrences(blanked, "repository.saveSemester("),
        )
        // 合法形状不许误伤：合并调用里嵌着 `repository.saveSemester` 的前缀，但不是那一枚旧调用
        val merged = "if (!repository.saveSemesterWithRename(semester)) {"
        assertEquals(
            "`repository.saveSemesterWithRename(` **不许**被算成 `repository.saveSemester(`（实到 " +
                occurrences(merged, "repository.saveSemester(") + " 处）：那把针得带左括号才认得出旧那一形",
            0,
            occurrences(merged, "repository.saveSemester("),
        )
    }

    // ─────────────── ⑤ 合并方法：一枚事务、锁在外层、写全由计划供给 ───────────────

    @Test
    fun `合并事务只裹一枚 withTransaction 且每一枚写都由计划供给`() {
        val repo = blankCommentsKeepingLiterals(source(REPOSITORY))
        val body = functionBody(repo, MERGED_ANCHOR)

        assertSingleTransactionShape("saveSemesterWithRename", body)
        assertEquals(
            "体内 `applySemesterRowSteps(` 恰好一处（实到 " + occurrences(body, "applySemesterRowSteps(") +
                " 处）：整组步骤一次交出去跑，这才是「一枚事务」的意思",
            1,
            occurrences(body, "applySemesterRowSteps("),
        )
        for (needle in listOf("semesterDao.insert(", "semesterDao.deleteByTermCode(", "courseDao.reassignSemester(")) {
            assertEquals(
                "合并方法体内裸写 `$needle` 必须 0 处（实到 " + occurrences(body, needle) +
                    " 处）：这一跳的每一枚写都必须来自内核排好的步骤列表（`applySemesterRowSteps`）—— " +
                    "在方法里再裸写一枚，计划与落库就成了两把尺，表驱动单测盖不住实际写的那几枚",
                0,
                occurrences(body, needle),
            )
        }
        assertEquals(
            "体内 `planSemesterRenameSave(` 恰好一处（实到 " + occurrences(body, "planSemesterRenameSave(") + " 处）",
            1,
            occurrences(body, "planSemesterRenameSave("),
        )
        assertEquals(
            "拒绝档 `return@withTransaction false` 恰好一处（实到 " +
                occurrences(body, "return@withTransaction false") + " 处）：被拒时整组不写，" +
                "调用方据此保持那句「已有另一份课表」并 return",
            1,
            occurrences(body, "return@withTransaction false"),
        )
        assertEquals(
            "体内 `semesterDao.getCurrent(` 恰好一处（实到 " + occurrences(body, "semesterDao.getCurrent(") +
                " 处）：「库里那一枚行」必须在事务**内**读 —— 在事务外读就是又一枚 TOCTOU 窗口",
            1,
            occurrences(body, "semesterDao.getCurrent("),
        )
    }

    @Test
    fun `renameSemesterCourses 的签名与锁的位置没被挪`() {
        val repo = blankCommentsKeepingLiterals(source(REPOSITORY))
        val body = functionBody(repo, RENAME_ANCHOR)

        assertEquals(
            "`$RENAME_ANCHOR` 恰好一处（实到 " + occurrences(repo, RENAME_ANCHOR) +
                " 处）：卡面 §5 —— 三枚既有签名的调用方语义不许变，新增合并方法可以，" +
                "把这一枚改成半截行为不行",
            1,
            occurrences(repo, RENAME_ANCHOR),
        )
        assertSingleTransactionShape("renameSemesterCourses", body)
        assertEquals(
            "体内 `planSemesterRename(` 恰好一处（实到 " + occurrences(body, "planSemesterRename(") +
                " 处）：「新代码下已有别的课表就拒」这一判与合并事务共用同一枚内核（两把尺 = R7 P2-12 那一族）",
            1,
            occurrences(body, "planSemesterRename("),
        )
        assertEquals(
            "体内 `applySemesterRowSteps(` 恰好一处（实到 " + occurrences(body, "applySemesterRowSteps(") +
                " 处）：落库的三枚 DAO 调用只许有一份实现",
            1,
            occurrences(body, "applySemesterRowSteps("),
        )
        assertEquals(
            "体内 `$OLD_RESHAPE_CHECK` 必须 0 处（实到 " + occurrences(body, OLD_RESHAPE_CHECK) +
                " 处）：那一枚就地判据已经从这一路搬进内核，留着就是第二把尺",
            0,
            occurrences(body, OLD_RESHAPE_CHECK),
        )
    }

    // ─────────────── ⑦ 第 0 问的裁定落在 schema 上的那半：本卡没动 schema ───────────────

    @Test
    fun `本卡按第 0 问的裁定没有动 schema`() {
        val entity = blankCommentsKeepingLiterals(source(ENTITY))
        assertEquals(
            "`SemesterEntity.kt` 里 `indices` 必须 0 处（实到 " + occurrences(entity, "indices") +
                " 处）：第 0 问裁定的是「今天没有哪条写路能造出第一枚重复行，但库存里到底有没有" +
                "重复行只能由编排者在真机/模拟器上取」⇒ 本卡**不加唯一索引、不写迁移**，" +
                "把「同 termCode 只有一行」升到**写路级**（第 ③ 格）+ 读侧确定性（第 ① 格）。" +
                "要加 `Index(unique = true)` 的那张卡必须先做一次去重（库里只要有重复行，" +
                "CREATE UNIQUE INDEX 当场失败），并连这一格一起重钉",
            0,
            occurrences(entity, "indices"),
        )
        assertEquals(
            "`@Entity(tableName = \"semesters\")` 逐字还在（实到 " +
                occurrences(entity, "@Entity(tableName = \"semesters\")") + " 处）",
            1,
            occurrences(entity, "@Entity(tableName = \"semesters\")"),
        )
    }

    // ─────────────── ⑨ 内核纯度与「只有一份」 ───────────────

    @Test
    fun `学期行判据内核零 android 零时钟 判据全仓只有一份`() {
        val raw = source(POLICY)
        val code = blankCommentsKeepingLiterals(raw)

        val offenders = raw.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("import ") }
            .filter { it.contains("android.") || it.contains("androidx.") || it.contains("kotlinx.coroutines") }
            .toList()
        assertEquals(
            "纯 JVM 判据必须零 android / androidx / 协程 import（仓库硬规矩：设备面一律由调用点当参数递进来）。" +
                "实到：" + offenders,
            emptyList<String>(),
            offenders,
        )
        for (clock in listOf(
            "LocalDate.now(", "mostRecentMonday(", "System.currentTimeMillis", "Calendar.",
            "java.time.Clock", "Instant.", "ZonedDateTime", "TimeZone", "Date(",
        )) {
            assertEquals(
                "内核里 `$clock` 必须 0 处（实到 " + occurrences(code, clock) +
                    " 处）：这一判的输入是库里的行，一枚时钟读数都能让「同一份表两次判出两枚答案」",
                0,
                occurrences(code, clock),
            )
        }
        for (roomNeedle in listOf("semesterDao", "courseDao", "withTransaction", "Room")) {
            assertEquals(
                "内核里 `$roomNeedle` 必须 0 处（实到 " + occurrences(code, roomNeedle) +
                    " 处）：内核不碰库，「写哪几枚」是数据，落库归调用点",
                0,
                occurrences(code, roomNeedle),
            )
        }
        for (fn in listOf(
            "fun pickSemesterRowForTermCode(",
            "fun semesterRowsForTermCode(",
            "fun planSemesterRowBump(",
            "fun semesterRenameSourceCode(",
            "fun planSemesterRename(",
            "fun planSemesterRenameSave(",
            "fun applySemesterRowSteps(",
        )) {
            assertEquals(
                "`$fn` 全 app/src/main 只许一处定义（实到 " + mainDefinitionCount(fn) +
                    " 处）：第二份定义 = 两把尺量同一件事，改其一不改其余",
                1,
                mainDefinitionCount(fn),
            )
        }
        assertEquals(
            "排序那一维必须仍是 id 降序（`$ORDER_KEY` 恰好一处；实到 " + occurrences(code, ORDER_KEY) +
                " 处）：它与 SQL 的 `ORDER BY id DESC` 是同一把尺的两半",
            1,
            occurrences(code, ORDER_KEY),
        )
        assertEquals(
            "内核必须自己排序、不吃输入行序（`sortedWith(SEMESTER_ROW_ORDER)` 恰好一处；实到 " +
                occurrences(code, "sortedWith(SEMESTER_ROW_ORDER)") + " 处）",
            1,
            occurrences(code, "sortedWith(SEMESTER_ROW_ORDER)"),
        )
    }

    // ─────────────── 公共小件 ───────────────

    /**
     * 「锁在外层 + 只裹一枚事务」这一形（第 ②⑤ 两格共用）：
     * 卡面 §5 —— `writeMutex.withLock` 的外层位置不许挪（本仓有「锁顺序决定生死」的先例）。
     */
    private fun assertSingleTransactionShape(name: String, body: String) {
        val firstBrace = body.indexOf('{')
        val head = body.substring(0, if (firstBrace < 0) 0 else firstBrace)
        assertEquals(
            "`$name` 的**头一枚非空字符**起就得挂 `writeMutex.withLock {`（实到前缀「${head.trim()}」）：" +
                "卡面 §5 点名这枚锁的外层位置不许挪，挪锁要单独成提交并说明理由",
            1,
            occurrences(head.trimStart(), "writeMutex.withLock"),
        )
        assertEquals(
            "`$name` 体内 `db.withTransaction` 恰好一处（实到 " + occurrences(body, "db.withTransaction") +
                " 处）：#461 的病就是「两枚独立事务」。把它拆回两枚 = 死亡窗口重新打开",
            1,
            occurrences(body, "db.withTransaction"),
        )
    }

    // ---------------- 源码核对小工具（与仓内其它静态守卫同一口径）----------------

    private data class Source(val path: String, val code: String)

    private fun source(relativeFromMainJava: String): String {
        val file = File(findMainJavaDir(), relativeFromMainJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    private fun mainSources(): List<Source> {
        val root = findMainJavaDir()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${root.path} 下一个 .kt 都没有：路径不对，本守卫就是空的", files.isNotEmpty())
        return files.map {
            Source(it.relativeTo(root).path.replace('\\', '/'), blankCommentsKeepingLiterals(it.readText()))
        }
    }

    private fun mainDefinitionCount(needle: String): Int = mainSources().sumOf { occurrences(it.code, needle) }

    private fun lineOf(code: String, index: Int): Int = code.substring(0, index).count { it == '\n' } + 1

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

    /** 抹注释后的源码（字符串字面量留着），DAO 那一层只看文本形状 */
    private fun bodyOf(code: String): String = blankCommentsKeepingLiterals(code)

    /**
     * 取某一枚函数的**全文**（签名起、到与它配平的那一枚闭括号止）：
     * 锚点找不到就抛、锚点撞出多枚也抛、配平失败或段长越界同样抛 ——
     * 静默返回空串的那写法会把守卫变成永远绿的空尺。
     */
    private fun functionBody(code: String, anchor: String): String {
        val hits = indexOfAll(code, anchor)
        if (hits.isEmpty()) {
            throw AssertionError("锚点 `$anchor` 在源码里找不到：函数改过名或整段被搬走，本守卫的形状要跟着重钉，不许静默绿")
        }
        if (hits.size > 1) {
            throw AssertionError("锚点 `$anchor` 实到 ${hits.size} 处（应当恰好一处）：第二份同名函数 = 两把尺")
        }
        val start = hits.first()
        val open = code.indexOf('{', start + anchor.length)
        if (open < 0) throw AssertionError("`$anchor` 之后找不到开花括号：切不出一段体")
        val close = matchingBrace(code, open) ?: throw AssertionError("花括号没配平：从 $open 起")
        if (close <= open || close >= code.length) {
            throw AssertionError("取块越界：open=$open、close=$close、文件长 ${code.length}")
        }
        return code.substring(start, close + 1)
    }

    private fun matchingBrace(src: String, open: Int): Int? = matchingBracket(src, open, '{', '}')

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
     * **只抹注释、保留字符串字面量内容**，长度与换行位置不变（报错消息里的行号因此可信）。
     * 本卡的 KDoc 逐字登记着旧形状（`deleteByTermCode` 那一手、旧 SQL），不抹注释的守卫
     * 会把自己钉的注释当成病灶。
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
        const val DAO = "com/buaa/schedule/data/local/SemesterDao.kt"
        const val ENTITY = "com/buaa/schedule/data/local/SemesterEntity.kt"
        const val POLICY = "com/buaa/schedule/data/local/SemesterRowPolicy.kt"
        const val REPOSITORY = "com/buaa/schedule/data/repository/ScheduleRepository.kt"
        const val VIEW_MODEL = "com/buaa/schedule/ui/ScheduleViewModel.kt"

        /** 读侧形状（第 ① 格）：新那一形逐字 + 改前那一形当靶子 */
        const val GET_BY_TERM_CODE_QUERY =
            "@Query(\"SELECT * FROM semesters WHERE termCode = :termCode ORDER BY id DESC LIMIT 1\")"

        const val OLD_GET_BY_TERM_CODE_QUERY =
            "SELECT * FROM semesters WHERE termCode = :termCode LIMIT 1"

        const val GET_ROWS_BY_TERM_CODE_QUERY =
            "@Query(\"SELECT * FROM semesters WHERE termCode = :termCode ORDER BY id DESC\")"

        const val DELETE_BY_ID_QUERY = "@Query(\"DELETE FROM semesters WHERE id = :id\")"

        /** 内核的排序那一维（与 SQL 的 ORDER BY id DESC 同一把尺） */
        const val ORDER_KEY = "compareByDescending<Semester> { it.id }"

        /** 改前那一手的连坐删除（第 ② 格的 0 处尺 + 第 ⑧ 格的靶子） */
        const val OLD_SWITCH_SHAPE = "semesterDao.deleteByTermCode(termCode)"

        /** 改前 rename 那一手就地判的形状（第 ⑤ 格钉它已被内核替代） */
        const val OLD_RESHAPE_CHECK = "if (courseDao.getBySemester(newCode).isNotEmpty()) return@withTransaction false"

        const val SWITCH_ANCHOR = "suspend fun switchSemester(termCode: String): Boolean"
        const val SAVE_SEMESTER_ANCHOR = "suspend fun saveSemester(semester: Semester)"
        const val REPLACE_IN_TX_ANCHOR = "private suspend fun replaceSemesterCoursesInTx("
        const val RENAME_ANCHOR = "suspend fun renameSemesterCourses(oldCode: String, newCode: String): Boolean"
        const val MERGED_ANCHOR = "suspend fun saveSemesterWithRename(semester: Semester): Boolean"
        const val VM_SAVE_SEMESTER_ANCHOR = "fun saveSemester(semester: Semester)"

        const val MERGED_CALL = "repository.saveSemesterWithRename("
        const val REJECT_COPY = "\"学期代码 \${semester.termCode} 下已有另一份课表，请先切换学期再改代码\","
        const val SUCCESS_COPY = "showMessage(\"学期设置已保存\", isSuccess = true)"

        /** `semesters` 的每一条 SELECT（对照尺：改前只有 getByTermCode 一族没有 ORDER BY） */
        val SELECT_QUERY = Regex("SELECT \\* FROM semesters[^\\"]*")
    }
}
