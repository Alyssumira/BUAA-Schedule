package com.buaa.schedule.data.backup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T204②③：源码形状守卫 —— 把「跨学期恢复的两枚病灶」与「补建只能经内核 + 两枚写侧真的带全
 * 学期列表 + 补建账真的被念出来」钉死在盘上。
 *
 * 纯源码核对（JVM，无 Robolectric、无设备、不读时钟）。读法与小工具抄
 * `data/import/BuaaSemesterStartWiringGuardTest`（抹注释保字面量 + 花括号配平取块 + 原文切片不吃行号）。
 * 本守卫读 `app/src/main/java` 下的源码文本 —— 那一条已在 `app/build.gradle.kts` 的
 * `guardReadWorkingTreeFiles` 里整目录进 `tasks.withType<Test>()` 的 `inputs`（T92①，台账 #136），
 * 所以"改了源码、单跑守卫拿到 Gradle 缓存绿灯"那一档盖得住；本次没有新增目录之外的读取面。
 *
 * ## 钉法（四家逐格点名，卡面 §3.5 要求）
 *
 * 1. [病灶两行全树 0 处] —— **抹注释找锚点**：`startDate = fallbackStartDate.toString(),` 与
 *    `totalWeeks = 20,` 在 `app/src/main` **抹掉注释之后**各 0 处。这一层必须抹注释：
 *    `ScheduleRepository` 的 KDoc 与本内核段首都**逐字引用着这两行**（登记旧形状用），
 *    只 grep 字面量会假绿 —— 所以另有 [探测器自己认得旧那一形] 那一格做双向自检。
 * 2. [补建只能经内核] —— **读形状**（花括号配平取 `replaceCoursesForExistingSemesterInTx`
 *    与 `restoreBackupDataInTx` 的体）+ **数出现次数**：体内 `Semester(` 造 0 处、
 *    `decideSemesterRowForRestore(` 1 处、`semesterDao.insert(` 2 处且两处都由结论供给。
 * 3. [内核纯度] —— **读形状**（逐行扫 `import`）+ **数出现次数**：零 android/androidx、
 *    零时钟（`LocalDate.now(` / `mostRecentMonday(` / `System.currentTimeMillis` / `Calendar.`）、
 *    两枚判据函数全树各恰好一处定义、三等结论各恰好一处声明。
 * 4. [两枚写侧真的填了列表] —— **读形状**（取每一枚 `BackupData(` 的实参括号体）+ **逐字节**：
 *    两枚构造体里都必须有 `semesters = `，且 `repository.getAllSemesters()` 在 VM 里恰好 2 处。
 * 5. [格式版本没涨] —— **逐字节**：`const val BACKUP_FORMAT_VERSION = 2` 恰好 1 处（红线），
 *    新字段那行 `val semesters: List<BackupSemester> = emptyList(),` 恰好 1 处。
 * 6. [读侧配对 + 补建账真的被念出来] —— **逐字节** + **数出现次数** + **顺序**：
 *    `backupSemesterRows = data.semesters.map { it.toDomain() },` 1 处、
 *    `rebuiltSemesters = rebuiltSemesters,` 1 处、文案那一句与 `Log.i(RESTORE_LOG_TAG` 各 1 处，
 *    且留痕排在 `_importMessage` 之前。
 * 7. [口令那条链没有第二套序列化口径] —— **数出现次数**：`ScheduleShareCodec` 仍只做
 *    JSON ↔ deflate ↔ Base64，`BackupData(` 构造 0 处（它只搬运，不裁剪字段）。
 *
 * 本守卫只读源码，**不证明**运行时行为：真实换机恢复一份多学期备份后历史学期的日期对不对、
 * 恢复文案那一句在屏幕上长什么样，都归编排者装机（见回执的未验到清单）。
 */
class BackupSemesterRestoreWiringGuardTest {

    // ─────────────── ① 病灶两行：抹注释后全树 0 处 ───────────────

    @Test
    fun `跨学期恢复的两行病灶在源码里零命中`() {
        val startShape = mutableListOf<String>()
        val weeksShape = mutableListOf<String>()
        for (file in mainSources()) {
            if (occurrences(file.code, OLD_START_DATE_SHAPE) > 0) {
                startShape += "${file.path} ${lineOf(file.code, file.code.indexOf(OLD_START_DATE_SHAPE))}"
            }
            if (occurrences(file.code, OLD_TOTAL_WEEKS_SHAPE) > 0) {
                weeksShape += "${file.path} ${lineOf(file.code, file.code.indexOf(OLD_TOTAL_WEEKS_SHAPE))}"
            }
        }
        assertEquals(
            "`$OLD_START_DATE_SHAPE` 在 app/src/main（抹掉注释后）必须 0 处 —— 它就是 T204 的病灶 1B：" +
                "库里没有那一枚学期行时，开学日被补成恢复那天。实到 ${startShape.size} 处：" +
                startShape.joinToString("; ") +
                "。红了说明补建那一等绕开内核自己裸写兜底日期；注意本仓 KDoc 逐字引用着这两行，" +
                "所以只 grep 字面量的守卫会假绿，这一层是抹注释之后数的",
            0,
            startShape.size,
        )
        assertEquals(
            "`$OLD_TOTAL_WEEKS_SHAPE` 在 app/src/main（抹掉注释后）必须 0 处 —— 病灶 1C：#236 那一族" +
                "（没依据就报「这学期 20 周」）在本仓的落点。实到 ${weeksShape.size} 处：" +
                weeksShape.joinToString("; ") +
                "。周数要么从备份 / 库里拿，要么走补建那一等并按 COURSE_WEEKS / NO_BASIS 如实标出来" +
                "（判据表那几格钉的就是这个）",
            0,
            weeksShape.size,
        )

        // 注释里确实逐字写着这两行（上面那两层是抹注释之后数的）—— 这一格是"本层不是空转"的证据：
        // 哪天有人把 KDoc 里的引用删了，本层仍成立；但要是探测器连真代码都抓不住，②那一格会红。
        val rawRepo = source(REPOSITORY)
        assertTrue(
            "`$REPOSITORY` 的原文里必须仍能在**注释**里找到 `$OLD_START_DATE_SHAPE`（登记旧形状用，" +
                "抹注释后为 0 处才算修复）。实到未抹注释 " + occurrences(rawRepo, OLD_START_DATE_SHAPE) + " 处",
            occurrences(rawRepo, OLD_START_DATE_SHAPE) >= 1,
        )
    }

    // ─────────────── ② 探测器自检：认得旧那一形，也不误伤合法形状 ───────────────

    /**
     * 只报"全树 0 命中"而探测器其实什么都抓不到，等于没有守卫。两个方向各一头：
     * 朝松（抹注释把真代码一起抹了 / 锚点拼错）红在第一手；
     * 朝窄（任何 `startDate =` 都被算成命中）红在第二手 —— 那样的探测器会把
     * `restoreBackupDataInTx` 里主学期那一枚**合法**的 `startDate = monday.toString(),` 一起拆掉。
     */
    @Test
    fun `探测器自己认得旧那一形 也不误伤合法形状`() {
        // 旧那一形（`8caf3f7` 上 `replaceCoursesForExistingSemesterInTx` 的补建分支，逐字）
        val oldShape = """
            private suspend fun replaceCoursesForExistingSemesterInTx(
                semesterCode: String,
                imported: List<Course>,
                fallbackStartDate: LocalDate,
            ) {
                if (semesterDao.getByTermCode(semesterCode) == null) {
                    semesterDao.insert(
                        Semester(
                            termCode = semesterCode,
                            termName = semesterCode,
                            startDate = fallbackStartDate.toString(),
                            totalWeeks = 20,
                        ).toEntity()
                    )
                }
            }
        """.trimIndent()
        val blankedOld = blankCommentsKeepingLiterals(oldShape)
        assertEquals(
            "探测器必须抓得出改之前那一形的开学日裸写（实到 " +
                occurrences(blankedOld, OLD_START_DATE_SHAPE) + " 处）：0 处说明第 ① 层那句「全树 0 命中」是假的绿",
            1,
            occurrences(blankedOld, OLD_START_DATE_SHAPE),
        )
        assertEquals(
            "探测器必须抓得出改之前那一形的静默 20 周（实到 " +
                occurrences(blankedOld, OLD_TOTAL_WEEKS_SHAPE) + " 处）",
            1,
            occurrences(blankedOld, OLD_TOTAL_WEEKS_SHAPE),
        )
        assertEquals(
            "探测器必须抓得出改之前那一形的手造学期行（`Semester(` 换行起头；实到 " +
                occurrences(blankCommentsKeepingLiterals(oldShape), SEMESTER_CONSTRUCTION) + " 处）—— " +
                "第 ③ 层那句「体内 0 处」全靠这一枚锚点，锚点拼错它就永远绿",
            1,
            occurrences(blankCommentsKeepingLiterals(oldShape), SEMESTER_CONSTRUCTION),
        )
        assertEquals(
            "探测器必须抓得出改之前那一形的 `termName = semesterCode,`（实到 " +
                occurrences(blankCommentsKeepingLiterals(oldShape), OLD_TERM_NAME_SHAPE) + " 处）",
            1,
            occurrences(blankCommentsKeepingLiterals(oldShape), OLD_TERM_NAME_SHAPE),
        )

        // 合法一：主学期那一路的归一写法（有出处，只是过了一遍 mondayOf）
        val legitMainTerm = """
            s.copy(
                startDate = monday.toString(),
                totalWeeks = CourseConstraints.normalizeTotalWeeks(s.totalWeeks),
            )
        """.trimIndent()
        assertEquals(
            "`restoreBackupDataInTx` 里主学期那一枚 `startDate = monday.toString(),` **不许**被算成命中：" +
                "它是备份带来的日期归一后的值（有出处）。实到 " +
                occurrences(blankCommentsKeepingLiterals(legitMainTerm), OLD_START_DATE_SHAPE) + " 处",
            0,
            occurrences(blankCommentsKeepingLiterals(legitMainTerm), OLD_START_DATE_SHAPE),
        )
        // 合法二：注释里的逐字引用（本仓登记旧形状的写法）—— 抹注释之后不该计数
        val quotedInComment = """
            /**
             * 旧形状：`startDate = fallbackStartDate.toString(),` 加 `totalWeeks = 20,`（T204）。
             */
            fun noop() = Unit
        """.trimIndent()
        val blankedQuote = blankCommentsKeepingLiterals(quotedInComment)
        assertEquals(
            "注释里逐字引用的那两行**不许**进第 ① 层的账（抹注释之后必须 0 处；实到 " +
                occurrences(blankedQuote, OLD_START_DATE_SHAPE) + " 处）。未抹注释时它们确实在原文里：" +
                occurrences(quotedInComment, OLD_START_DATE_SHAPE) + " 处 —— 这一对照就是" +
                "「只 grep 字面量会假绿」的实物证据",
            0,
            occurrences(blankedQuote, OLD_START_DATE_SHAPE),
        )
    }

    // ─────────────── ③ 补建只能经内核（读形状 + 数出现次数） ───────────────

    @Test
    fun `补建学期行只能经内核 不许仓储层自己造`() {
        val code = blankCommentsKeepingLiterals(source(REPOSITORY))
        val hits = indexOfAll(code, REPLACE_ANCHOR)
        assertEquals("`$REPLACE_ANCHOR` 在仓储里恰好一处（本层取块的宿主）：实到 " + hits.size + " 处", 1, hits.size)
        val body = braceBodyFrom(code, code.indexOf('{', hits.first() + REPLACE_ANCHOR.length))

        assertEquals(
            "体内 `decideSemesterRowForRestore(` 恰好一处（实到 " +
                occurrences(body, "decideSemesterRowForRestore(") +
                " 处）：这一判在仓储里只许有一个入口。摘掉它 = 本卡那一族白修（第 ① 层立刻红回来）",
            1,
            occurrences(body, "decideSemesterRowForRestore("),
        )
        assertEquals(
            "体内手造学期行那一形（`Semester(` 直接换行起头的那一枚构造）必须 0 处（实到 " +
                occurrences(body, SEMESTER_CONSTRUCTION) + " 处）：仓储层不许再自己拼一枚学期行 —— " +
                "旧那一形就是这么把「恢复那天 + 20 周」造出来的。" +
                "要写的行只能来自结论的 `decision.semester` / `decision.toSemester()`",
            0,
            occurrences(body, SEMESTER_CONSTRUCTION),
        )
        assertEquals(
            "体内 `$OLD_TERM_NAME_SHAPE`（旧那一形给 termName 编的一个出处）必须 0 处（实到 " +
                occurrences(body, OLD_TERM_NAME_SHAPE) + " 处）：名字没有出处时只能由内核退成 termCode，" +
                "在仓储层里再写一遍就是第二套口径",
            0,
            occurrences(body, OLD_TERM_NAME_SHAPE),
        )
        assertEquals(
            "体内 `semesterDao.insert(` 恰好两处（InsertFromBackup / Rebuild 各一枚，实到 " +
                occurrences(body, "semesterDao.insert(") +
                " 处）：库里已有那一等（KeepExistingInDb）必须一枚都不写，写第三处就是把历史学期顶成当前学期",
            2,
            occurrences(body, "semesterDao.insert("),
        )
        for (needle in listOf("decision.semester.toEntity()", "decision.toSemester().toEntity()")) {
            assertEquals(
                "体内 `$needle` 恰好一处（实到 " + occurrences(body, needle) +
                    " 处）：两枚 insert 都必须由内核结论供给",
                1,
                occurrences(body, needle),
            )
        }
        assertEquals(
            "体内 `getByTermCode(` 恰好一处（实到 " + occurrences(body, "getByTermCode(") +
                " 处）：库里有没有这一学期是**当参数递给判据**的，不是判据自己去问数据库（那会让内核不可断言）",
            1,
            occurrences(body, "getByTermCode("),
        )
        assertEquals(
            "体内 `fallbackStartDate.toString()` 必须 0 处（实到 " +
                occurrences(body, "fallbackStartDate.toString()") +
                " 处）：兜底日期只能作为参数 `fallbackStartDate = fallbackStartDate,` 递进内核",
            0,
            occurrences(body, "fallbackStartDate.toString()"),
        )
        assertEquals(
            "体内 `totalWeeks` 必须 0 处（实到 " + occurrences(body, "totalWeeks") +
                " 处）：这一段的活儿是「把结论递给内核、按结论写行」，周数由内核从备份 / 库里 / " +
                "周次证据里定（`observedMaxWeek = ...` 是证据不是周数）。这里一旦出现 totalWeeks，" +
                "就说明有人又开始在仓储层造数了" +
                "（`domain/schedule/CourseConstraints` 那把尺才是唯一的限幅处）",
            0,
            occurrences(body, "totalWeeks"),
        )
    }

    @Test
    fun `恢复那一路真的按 termCode 配对并把补建账交回去`() {
        val code = blankCommentsKeepingLiterals(source(REPOSITORY))
        val hits = indexOfAll(code, RESTORE_TX_ANCHOR)
        assertEquals("`$RESTORE_TX_ANCHOR` 恰好一处（本层取块的宿主）：实到 " + hits.size + " 处", 1, hits.size)
        val body = braceBodyFrom(code, code.indexOf('{', hits.first() + RESTORE_TX_ANCHOR.length))

        assertEquals(
            "体内 `indexBackupSemesterRows(` 恰好一处（实到 " + occurrences(body, "indexBackupSemesterRows(") +
                " 处）：备份里的学期行只能按 termCode 配一次对，两处配对就会有两把尺",
            1,
            occurrences(body, "indexBackupSemesterRows("),
        )
        assertEquals(
            "体内 `backupRows[code]` 与 `backupRows[mainCode]` 各恰好一处（实到 " +
                occurrences(body, "backupRows[code]") + " + " + occurrences(body, "backupRows[mainCode]") +
                " 处）：历史学期与主学期两条路都要把手里那一枚配对结果递给仓储层的补建函数，" +
                "少递一枚 = 那一等永远只能走补建",
            2,
            occurrences(body, "backupRows[code]") + occurrences(body, "backupRows[mainCode]"),
        )
        assertTrue(
            "体内 `rebuiltSemesters` 必须成对：攒账（mutableListOf + 两处 add）与交回 " +
                "`rebuiltSemesters = rebuiltSemesters,` 都在（实到 " + occurrences(body, "rebuiltSemesters") +
                " 处，至少 5 处才算成对：声明 1 + add 引用 2 + 交回左右各 1）。" +
                "账攒了不交回 = 内核标出来的「说实话」在半路被丢掉，文案那头永远是空列表",
            occurrences(body, "rebuiltSemesters") >= 5,
        )
        assertEquals(
            "体内 `RestoreResult.Applied(` 恰好一处（实到 " + occurrences(body, "RestoreResult.Applied(") + " 处）",
            1,
            occurrences(body, "RestoreResult.Applied("),
        )
        // 签名面：新参数带默认值 ⇒ 老调用点不涨编译负担，也不改变既有语义
        val signature = code.substring(hits.first()).lineSequence().take(12).joinToString("\n")
        assertEquals(
            "`restoreBackupDataInTx` 的签名里 `backupSemesterRows: List<Semester>,` 恰好一处（实到 " +
                occurrences(signature, "backupSemesterRows: List<Semester>,") + " 处）",
            1,
            occurrences(signature, "backupSemesterRows: List<Semester>,"),
        )
    }

    // ─────────────── ④ 内核纯度：零 android、零时钟、判据只有一份 ───────────────

    @Test
    fun `判据内核零 android 依赖 不读时钟 全仓只有一份`() {
        val raw = source(POLICY)
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
        for (clock in listOf("LocalDate.now(", "mostRecentMonday(", "System.currentTimeMillis", "Calendar.")) {
            assertEquals(
                "内核里 `$clock` 必须 0 处（实到 " + occurrences(code, clock) +
                    " 处）：判据一旦能读时钟，「恢复那天所在周一」就会顺着参数爬回判据本体 —— " +
                    "那正是本卡否掉的那一等猜法，而且它让这一判在 JVM 侧不可断言（表驱动单测会跟着失真）。" +
                    "「今天」归调用点算，作为 fallbackStartDate 参数递进来",
                0,
                occurrences(code, clock),
            )
        }
        assertEquals(
            "内核里不许自己 parse 日期（`LocalDate.parse(` 0 处；实到 " + occurrences(code, "LocalDate.parse(") +
                " 处）：认不认得出来只有 `Semester.startLocalDate` 那一把尺（它自带归一到周一），" +
                "第二枚 parse 就是第二套口径（R7 P2-12 那一族：同一节课最多错开 6 天）",
            0,
            occurrences(code, "LocalDate.parse("),
        )
        assertEquals(
            "归一必须仍引 `mondayOfWeekAnchor` 那唯一一份（本文件里 `mondayOfWeekAnchor(` 恰好一处，实到 " +
                occurrences(code, "mondayOfWeekAnchor(") + " 处）：兜底日期不是周一时也要归一",
            1,
            occurrences(code, "mondayOfWeekAnchor("),
        )
        for (fn in listOf("fun decideSemesterRowForRestore(", "fun indexBackupSemesterRows(")) {
            assertEquals(
                "`$fn` 在本文件里恰好一处（实到 " + occurrences(code, fn) +
                    " 处），全 app/src/main 也只许一处（实到 " + mainDefinitionCount(fn) +
                    " 处）：第二份定义 = 两把尺量同一件事，改其一不改其余",
                1,
                occurrences(code, fn),
            )
            assertEquals(
                "`$fn` 全 app/src/main 只许一处定义（实到 " + mainDefinitionCount(fn) + " 处）",
                1,
                mainDefinitionCount(fn),
            )
        }
        assertEquals(
            "三等结论各恰好一处声明（KeepExistingInDb / InsertFromBackup / Rebuild；实到 " +
                occurrences(code, "data class KeepExistingInDb(") + " + " +
                occurrences(code, "data class InsertFromBackup(") + " + " +
                occurrences(code, "data class Rebuild(") + " 处）：第四枚结论（比如 `GuessedSilently`）" +
                "会绕过本卡的补建账，坏日期又有了入口",
            3,
            occurrences(code, "data class KeepExistingInDb(") +
                occurrences(code, "data class InsertFromBackup(") +
                occurrences(code, "data class Rebuild("),
        )
        for (reason in listOf("NOT_IN_BACKUP", "BACKUP_DATE_UNREADABLE", "COURSE_WEEKS", "NO_BASIS")) {
            assertTrue(
                "来历档位 `$reason` 必须在内核里出现（合并来历 = 用户分不清「老备份」与「坏备份」、" +
                    "分不清「按课程周次推的」与「一点依据都没有的」）。实到 " +
                    occurrences(code, reason) + " 处",
                occurrences(code, reason) >= 1,
            )
        }
        for (decl in listOf("enum class RebuiltStartReason", "enum class RebuiltWeeksBasis")) {
            assertEquals(
                "`$decl` 恰好一处声明（实到 " + occurrences(code, decl) +
                    " 处）：两枚来历枚举就是「补建那一等不许静默」的那两面，第三枚来历枚举会稀释它们",
                1,
                occurrences(code, decl),
            )
        }
        assertEquals(
            "内核里不许有硬编码的周数常量（` = 20` 0 处；实到 " + occurrences(code, " = 20") +
                " 处）：那正是被本卡判死的那一等静默值",
            0,
            occurrences(code, " = 20"),
        )
    }

    // ─────────────── ⑤ 两枚写侧真的填了列表 + 格式版本没涨 ───────────────

    @Test
    fun `两枚写侧的 BackupData 构造都填了学期列表`() {
        val code = blankCommentsKeepingLiterals(source(VIEW_MODEL))
        val hits = indexOfAll(code, BACKUP_DATA_CONSTRUCTION)
        assertEquals(
            "`$BACKUP_DATA_CONSTRUCTION` 在 `ScheduleViewModel` 里恰好两处（备份文件 + 分享口令两枚写侧）：" +
                "实到 " + hits.size + " 处。少一枚就是其中一条链仍在只带单字段",
            2,
            hits.size,
        )
        for ((index, at) in hits.withIndex()) {
            val args = parenBodyFrom(code, at + BACKUP_DATA_CONSTRUCTION.length - 1)
            assertEquals(
                "第 ${index + 1} 枚 `$BACKUP_DATA_CONSTRUCTION` 的实参里 `semesters = ` 恰好一处（实到 " +
                    occurrences(args, "semesters = ") + " 处）。摘掉它 = 这一条链回到只带一枚学期行，" +
                    "换机恢复的历史学期又只能靠补建",
                1,
                occurrences(args, "semesters = "),
            )
            assertTrue(
                "第 ${index + 1} 枚写侧的 `semesters = ` 右侧必须真的取库里全部学期" +
                    "（含 `getAllSemesters` 或从中取出的那枚局部变量；实到 " + args + "）",
                args.contains("getAllSemesters") || args.contains("semesters.map"),
            )
        }
        assertEquals(
            "`repository.getAllSemesters()` 在 VM 里恰好两处（两枚写侧各一枚；实到 " +
                occurrences(code, "repository.getAllSemesters()") + " 处）",
            2,
            occurrences(code, "repository.getAllSemesters()"),
        )
    }

    @Test
    fun `格式版本没涨 新字段带默认值`() {
        val models = blankCommentsKeepingLiterals(source(BACKUP_MODELS))
        assertEquals(
            "红线：`const val BACKUP_FORMAT_VERSION = 2` 必须逐字还在（实到 " +
                occurrences(models, "const val BACKUP_FORMAT_VERSION = 2") +
                " 处）。涨到 3 会让老应用对这份文件直接拒绝恢复（`data.version > BACKUP_FORMAT_VERSION`），" +
                "而字段本身两边都认得 —— 口径同 `BackupCourse.credit` 那一段（本文件自己的注释写着它）",
            1,
            occurrences(models, "const val BACKUP_FORMAT_VERSION = 2"),
        )
        assertEquals(
            "新字段那行 `val semesters: List<BackupSemester> = emptyList(),` 恰好一处（实到 " +
                occurrences(models, NEW_FIELD) + " 处）：**带默认值**才是线上兼容的那一半 —— " +
                "老备份没有这个键时解成空列表，读侧据此回落到单字段那一枚",
            1,
            occurrences(models, NEW_FIELD),
        )
        assertEquals(
            "单字段 `val semester: BackupSemester? = null,` 必须保留（实到 " +
                occurrences(models, LEGACY_FIELD) + " 处）：它是老应用唯一的学期来源，删了就断兼容",
            1,
            occurrences(models, LEGACY_FIELD),
        )
    }

    // ─────────────── ⑥ 读侧接线 + 补建账真的被念出来（含顺序） ───────────────

    @Test
    fun `读侧把学期列表递给仓储 并把补建账念成文案与日志`() {
        val code = blankCommentsKeepingLiterals(source(VIEW_MODEL))

        assertEquals(
            "读侧 `backupSemesterRows = data.semesters.map { it.toDomain() },` 恰好一处（实到 " +
                occurrences(code, READ_WIRING) + " 处）：递不到 = 内核永远收不到备份行，" +
                "第 ③ 层全树 0 命中也会白成立",
            1,
            occurrences(code, READ_WIRING),
        )
        assertEquals(
            "文案那一句 `$NOTE_APPEND…`（把补建账接在「恢复成功」那一句后面）恰好一处（实到 " +
                occurrences(code, NOTE_APPEND) + " 处）：账不念出来就是把偏移重新藏回静默里",
            1,
            occurrences(code, NOTE_APPEND),
        )
        assertEquals(
            "取账那一行恰好一处（实到 " + occurrences(code, NOTE_CALL) + " 处）",
            1,
            occurrences(code, NOTE_CALL),
        )
        assertEquals(
            "取证日志 `Log.i(RESTORE_LOG_TAG` 恰好一处（实到 " + occurrences(code, LOG_CALL) +
                " 处）：恢复成功的 toast 会自己消失，这笔账要能在事后被读出来" +
                "（级别 Info，口径同 `SPECIAL_DAY_LOG_TAG` 那一段）",
            1,
            occurrences(code, LOG_CALL),
        )
        assertEquals(
            "tag 常量声明恰好一处（实到 " + occurrences(code, "RESTORE_LOG_TAG = \"BackupRestore\"") + " 处）",
            1,
            occurrences(code, "RESTORE_LOG_TAG = \"BackupRestore\""),
        )
        val noteAt = code.indexOf(NOTE_CALL)
        val logAt = code.indexOf(LOG_CALL, noteAt)
        val messageAt = code.indexOf(MESSAGE_AT, logAt)
        assertTrue(
            "次序必须是 **取账 → 留痕日志 → 写文案**：取账落在 index=$noteAt、`$LOG_CALL` 落在 $logAt、" +
                "`$MESSAGE_AT` 落在 $messageAt。本仓「顺序决定生死」那一族的老规矩：" +
                "把日志搬到写文案之后，一旦那段字符串构建抛异常，这笔账两头都不剩",
            noteAt in 0 until logAt && logAt < messageAt,
        )
        // 预览面：恢复前就能看见带了几枚学期行
        assertEquals(
            "预览里 `semesterCount = data.semesters.size,` 恰好一处（实到 " +
                occurrences(code, "semesterCount = data.semesters.size,") + " 处）",
            1,
            occurrences(code, "semesterCount = data.semesters.size,"),
        )
        val settings = blankCommentsKeepingLiterals(source(SETTINGS_SCREEN))
        assertEquals(
            "`SettingsScreen` 里念 `preview.semesterCount` 的那一句恰好两处（>0 与回落那一档各一枚；实到 " +
                occurrences(settings, "preview.semesterCount") + " 处）：写了没人念 = 第 ⑥ 层只是内存里的一个字段",
            2,
            occurrences(settings, "preview.semesterCount"),
        )
    }

    // ─────────────── ⑦ 口令那条链：搬运，不自建第二套口径 ───────────────

    @Test
    fun `分享口令那条链跟着一起带全学期行 因为走的是同一个 BackupData`() {
        val codec = blankCommentsKeepingLiterals(source(SHARE_CODEC))
        assertEquals(
            "`ScheduleShareCodec` 里 `BackupData(` 构造必须 0 处（实到 " + occurrences(codec, "BackupData(") +
                " 处）：口令只做 JSON ↔ deflate ↔ Base64 的搬运，不裁剪字段 ⇒ " +
                "`semesters` 自动跟着走。它要是自己拼一枚 BackupData，就有了第二套学期口径（本卡第 2 层要红）",
            0,
            occurrences(codec, "BackupData("),
        )
        assertEquals(
            "编解码各恰好一处（`json.encodeToString(backup)` / `json.decodeFromString<BackupData>`；实到 " +
                occurrences(codec, "json.encodeToString(backup)") + " + " +
                occurrences(codec, "json.decodeFromString<BackupData>") + " 处）",
            2,
            occurrences(codec, "json.encodeToString(backup)") + occurrences(codec, "json.decodeFromString<BackupData>"),
        )
        assertEquals(
            "口令侧不许出现第二枚学期兜底（`fallbackStartDate` / `LocalDate.now(` 各 0 处；实到 " +
                occurrences(codec, "fallbackStartDate") + occurrences(codec, "LocalDate.now(") + " 处）",
            0,
            occurrences(codec, "fallbackStartDate") + occurrences(codec, "LocalDate.now("),
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

    /** 从已知开括号位按花括号配平取出那一段体 */
    private fun braceBodyFrom(source: String, open: Int): String {
        check(source.getOrNull(open) == '{') { "锚点末尾不是花括号：写法变了，切不出一段体（实到「${
            source.getOrNull(open)
        }」）" }
        return source.substring(open + 1, (matchingBrace(source, open) ?: error("花括号没配平：从 $open 起")))
    }

    /** 从已知开圆括号位按括号配平取出实参那一段（`BackupData(` 的构造体） */
    private fun parenBodyFrom(source: String, open: Int): String {
        check(source.getOrNull(open) == '(') { "锚点末尾不是圆括号：切不出实参（实到「${source.getOrNull(open)}」）" }
        return source.substring(open + 1, (matchingParen(source, open) ?: error("圆括号没配平：从 $open 起")))
    }

    private fun matchingBrace(src: String, open: Int): Int? = matchingBracket(src, open, '{', '}')

    private fun matchingParen(src: String, open: Int): Int? = matchingBracket(src, open, '(', ')')

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
                    // 字符字面量：'{' / '}' 之类不参与配平
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
     * 本卡尤其需要抹注释：`ScheduleRepository` 的 KDoc 与本内核段首都**逐字写着那两行病灶**
     * （登记旧形状用），不抹的话守卫会把自己钉的注释当成病灶。
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
        const val POLICY = "com/buaa/schedule/data/backup/BackupSemesterRestorePolicy.kt"
        const val REPOSITORY = "com/buaa/schedule/data/repository/ScheduleRepository.kt"
        const val VIEW_MODEL = "com/buaa/schedule/ui/ScheduleViewModel.kt"
        const val BACKUP_MODELS = "com/buaa/schedule/data/backup/BackupModels.kt"
        const val SHARE_CODEC = "com/buaa/schedule/data/share/ScheduleShareCodec.kt"
        const val SETTINGS_SCREEN = "com/buaa/schedule/ui/settings/SettingsScreen.kt"

        /** 病灶 1B / 1C 的逐字本体（`8caf3f7` 上 `replaceCoursesForExistingSemesterInTx` 补建那两行） */
        const val OLD_START_DATE_SHAPE = "startDate = fallbackStartDate.toString(),"
        const val OLD_TOTAL_WEEKS_SHAPE = "totalWeeks = 20,"

        /** 旧那一形在仓储层**手造**学期行的两枚可辨锚点（构造是换行起头的，`8caf3f7` 上的形状） */
        const val SEMESTER_CONSTRUCTION = "Semester(\n"
        const val OLD_TERM_NAME_SHAPE = "termName = semesterCode,"

        const val REPLACE_ANCHOR = "private suspend fun replaceCoursesForExistingSemesterInTx("
        const val RESTORE_TX_ANCHOR = "private suspend fun restoreBackupDataInTx("
        const val BACKUP_DATA_CONSTRUCTION = "val backup = BackupData("

        const val NEW_FIELD = "val semesters: List<BackupSemester> = emptyList(),"
        const val LEGACY_FIELD = "val semester: BackupSemester? = null,"

        const val READ_WIRING = "backupSemesterRows = data.semesters.map { it.toDomain() },"
        const val NOTE_CALL = "rebuiltSemestersNote(result.rebuiltSemesters)"
        const val NOTE_APPEND = "if (rebuiltNote != null) append(\"。${'$'}rebuiltNote\")"
        const val LOG_CALL = "Log.i(RESTORE_LOG_TAG"
        const val MESSAGE_AT = "AppMessage(buildString"
    }
}
