package com.buaa.schedule.data.repository

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T278③：源码形状守卫 —— 把「撤销重插那一手不许拿旧主键走 REPLACE、抹主键那一手不许搬家」钉在盘上。
 *
 * 病灶是三行连起来才成立的（每一行单看都「看着没问题」），所以本守卫每层只管其中一行：
 * ① 重插那一手真的经由判据取主键（**这一层就是 arm W 的那一层**）② 撤销链那四支都还走同一枚口子
 * ③ 抹主键那一手没有搬去 `normalize`、也没搬去撤销链之外的写路径（甲的边界）
 * ④ DAO 仍是 REPLACE、实体仍是 autoGenerate（改这两枚里的任一枚都会让「交 0L」失去意义）
 * ⑤ 判据内核纯 JVM、全仓只有一份、结论没被折成 Boolean ⑥ 探测器自己认得改前那一形（不空转）。
 *
 * 纯源码核对（JVM，无 Robolectric、无设备、不读时钟）。读法与小工具抄
 * `data/share/ShareRestoreVolumeGuardTest` 与 `data/backup/BackupSemesterRestoreWiringGuardTest`
 * （抹注释保字面量 + 花括号配平取块 + 原文切片）。守卫读 `app/src/main/java` 下的源码文本 ——
 * 那一目录已在 `app/build.gradle.kts` 的 `guardReadWorkingTreeFiles` 里整目录进
 * `tasks.withType<Test>()` 的 inputs（T92①，台账 #136），所以「改了源码、单跑守卫拿到 Gradle
 * 缓存绿灯」那一档盖得住。
 *
 * ## 四种钉法逐格点名
 *
 * 1. [重插那一手交的主键由判据供给] —— **逐字节**（`undoWritePrimaryKey(normalized.id, …)` 那一行
 *    与 `normalized.copy(id = primaryKey)` 那一行各按原文钉死）+ **数出现次数**（体内
 *    `courseDao.insert(` 恰一枚、`undoWritePrimaryKey(` 恰一枚）+ **读形状**（花括号配平取
 *    `insertWithReminders` 的体，判据那手必须排在那次 insert **之前**）
 *    + **抹注释找锚点**（体内 `courseDao.insert(it.toEntity())` 那一形 —— 改前那一手的逐字本体 ——
 *    必须 0 处；注释里逐字登记着它，所以只有抹了注释才归零）。
 * 2. [四支都走同一枚口子] —— **读形状**（`applyUndo` 与 `undoUpdate` 各取一块体）+
 *    **数出现次数**：`insertWithReminders(` 在仓储恰五枚（一枚声明 + 四枚调用点），
 *    `applyUndo` 体内两枚、`undoUpdate` 体内两枚；全 app/src/main 抹注释后也恰五枚
 *    （另外四枚文件只在注释里点它的名）。
 * 3. [抹主键那一手没搬家] —— **逐字节**（`normalize` 的 copy 清单八行整块钉死，清单里就是没有 `id`）+
 *    **数出现次数**（`id = 0L` 在 normalize 体内 0 处；`copy(id = 0L)` 全文件仍恰两枚 = 那两枚同形先例，
 *    第三枚就意味着有人绕过判据自己抹）+ **读形状**（`saveCourse` 体内仍有一枚裸 insert，
 *    那是 #304 那一族在册病、甲明写不收的那一格，不许被顺手改到）。
 * 4. [REPLACE 与 autoGenerate 两枚前提还在] —— **逐字节**：`CourseDao` 的
 *    `@Insert(onConflict = OnConflictStrategy.REPLACE)` + `suspend fun insert(course: CourseEntity): Long`
 *    各一枚、`CourseEntity` 的 `@PrimaryKey(autoGenerate = true)` + `val id: Long = 0L,` 各一枚、
 *    `ReminderEntity` 的 `@PrimaryKey` + `val courseId: Long,` 各一枚（最后这一枚是「被顶号那门课的
 *    提醒行跟着归给冒进来的那一门」的成因，本卡不收它、但它的存在是本卡理由的一部分）。
 * 5. [内核纯度与唯一定义] —— **读形状**（逐行扫 import）+ **数出现次数**：零 android/androidx、
 *    零时钟/时区/权限/机型/上下文/文件与 DAO、`fun undoWritePrimaryKey(` 全 main 恰一枚定义、
 *    `FRESH_ROW_PRIMARY_KEY: Long = 0L` 恰一枚、结论类型是 `Long` 而**不是** `Boolean`
 *    （卡面红线：判据不许折成 Boolean）。
 * 6. [探测器不空转] —— **逐字节**（把改前那一手与今天那一手各喂一次同一把尺）：
 *    改前那一形必须命中、今天那一形必须 0 命中，两格缺一格第 ① 层就是假的绿。
 *
 * 本守卫只读源码，**不证明**运行时行为：Room 拿 0L 到底另不另号、被顶掉那一门课的提醒行到底归了谁 ——
 * 那些归编排者装机（`:app` 的 JVM 测试面没有 Robolectric，也没有能在 JVM 上跑的 Room 缝）。
 * 判据逐格取值由 `UndoReinsertIdentityTest` 钉；本文件钉的是「写侧那一手真的按判据取主键、
 * 且抹主键这件事确实长在撤销写路径上」。
 */
class UndoReinsertIdentityGuardTest {

    // ─────────────── ① 重插那一手：主键由判据供给（arm W 的红在这里）───────────────

    @Test
    fun `撤销重插那一手的主键由判据供给 不是 normalize 原样带回的那枚旧号`() {
        val repo = blankCommentsKeepingLiterals(source(REPOSITORY))
        val funnelHits = indexOfAll(repo, FUNNEL_ANCHOR)
        assertEquals(
            "锚点「$FUNNEL_ANCHOR」在仓储里恰好一处（本层取块的宿主）：实到 ${funnelHits.size} 处。" +
                "它一旦被改名或拆成两枚口子，第 ① 层的其余格子全部失去落脚点",
            1,
            funnelHits.size,
        )
        val body = braceBodyFrom(repo, repo.indexOf('{', funnelHits.first() + FUNNEL_ANCHOR.length))
        // 数出现次数：这一手里只许有一次 insert、一次取主键
        assertEquals(
            "insertWithReminders 体内「courseDao.insert(」恰好一处（实到 " +
                occurrences(body, "courseDao.insert(") + " 处）：这一族唯一的那次入库。" +
                "两枚就意味着有人往同一枚口子里塞了第二趟带旧号的 insert",
            1,
            occurrences(body, "courseDao.insert("),
        )
        assertEquals(
            "体内「undoWritePrimaryKey(」恰好一处（实到 " + occurrences(body, "undoWritePrimaryKey(") +
                " 处）：主键的取法只有这一枚来源。0 处 = 这一手不再问判据（本卡的病灶换了一种写法回来）",
            1,
            occurrences(body, "undoWritePrimaryKey("),
        )
        // 逐字节：今天那一手的两行原文
        assertEquals(
            "体内「$TAKE_PRIMARY_KEY_LINE」恰好一处（实到 " + occurrences(body, TAKE_PRIMARY_KEY_LINE) +
                " 处）：逐字节钉住「把 normalize 的那枚 id 交给判据、并明写 landsOnSnapshotRow = false」那一手",
            1,
            occurrences(body, TAKE_PRIMARY_KEY_LINE),
        )
        assertEquals(
            "体内「$COPY_PRIMARY_KEY_LINE」恰好一处（实到 " + occurrences(body, COPY_PRIMARY_KEY_LINE) +
                " 处）：逐字节钉住「交出去的是抹过的那枚、不是 normalize 原样带回的那枚」",
            1,
            occurrences(body, COPY_PRIMARY_KEY_LINE),
        )
        // 抹注释找锚点：改前那一手的逐字本体
        assertEquals(
            "体内「$LEGACY_INSERT」必须 0 处（实到 " + occurrences(body, LEGACY_INSERT) +
                " 处）：那是改前那一手 —— normalize 的 copy 清单不清 id（CourseConstraints 里逐字登记着），" +
                "而 CourseDao.insert 是 OnConflictStrategy.REPLACE ⇒ SQL 实为 " +
                "INSERT OR REPLACE INTO courses (id, …) VALUES (<before.id>) ⇒ 此刻正持有那一枚 id 的" +
                "**无关课程被整行删掉**、快照顶号进来，reminders 的主键就是 courseId ⇒ 那门课的提醒行" +
                "跟着归给冒进来的那一门。红了就说明有人把这一手接回去了",
            0,
            occurrences(body, LEGACY_INSERT),
        )
        // 读形状 + 顺序：判据必须排在那次 insert 之前
        val takeAt = body.indexOf("undoWritePrimaryKey(")
        val insertAt = body.indexOf("courseDao.insert(")
        assertTrue(
            "顺序判据：取主键必须排在那次 insert **之前**（实到 取主键 index=$takeAt、" +
                "insert index=$insertAt）。反了就是先插再算，那枚算出来的号没人吃",
            takeAt in 0 until insertAt,
        )
    }

    // ─────────────── ② 撤销链四支都走同一枚口子 ───────────────

    @Test
    fun `撤销链四支共用唯一重插口子 没有第五枚绕过去的路`() {
        val repo = blankCommentsKeepingLiterals(source(REPOSITORY))
        assertEquals(
            "仓储里「insertWithReminders(」恰好五处（一枚声明 + 四枚调用点：applyUndo 的 Delete 与 " +
                "DeleteGroup、undoUpdate 的 RestoreAsNewRow 与 removed 片段；实到 " +
                occurrences(repo, "insertWithReminders(") + " 处）：四支共用一枚口子，" +
                "所以抹主键那一手只须写一次就盖住四支。第六处 = 有人另起了一枚重插口子",
            5,
            occurrences(repo, "insertWithReminders("),
        )
        val applyUndo = braceBodyFrom(repo, repo.indexOf('{', repo.indexOf(UNDO_ENTRY_ANCHOR)))
        assertEquals(
            "applyUndo 体内「insertWithReminders(」恰好两处（Delete / DeleteGroup；实到 " +
                occurrences(applyUndo, "insertWithReminders(") + " 处）",
            2,
            occurrences(applyUndo, "insertWithReminders("),
        )
        val undoUpdate = braceBodyFrom(repo, repo.indexOf('{', repo.indexOf(UNDO_UPDATE_ANCHOR)))
        assertEquals(
            "undoUpdate 体内「insertWithReminders(」恰好两处（RestoreAsNewRow / removed 片段；实到 " +
                occurrences(undoUpdate, "insertWithReminders(") + " 处）：本卡最要紧的那一支就在其中",
            2,
            occurrences(undoUpdate, "insertWithReminders("),
        )
        // 判据的那一枚调用点必须落在重插口子之内（挪去 saveCourse 就红）
        assertEquals(
            "全 app/src/main 里「undoWritePrimaryKey(」恰好两处（一枚定义 + 一枚调用点；实到 " +
                mainOccurrenceCount(TAKE_NEEDLE) + " 处）：取法全仓只有一份，写侧也只问它一次",
            2,
            mainOccurrenceCount(TAKE_NEEDLE),
        )
        // saveCourse 是表达式体（= writeMutex.withLock { … }），所以这里取的那一块就是它的函数体
        val saveCourse = braceBodyFrom(repo, repo.indexOf('{', repo.indexOf(SAVE_COURSE_ANCHOR)))
        assertEquals(
            "saveCourse 那一段里「undoWritePrimaryKey(」必须 0 处（实到 " +
                occurrences(saveCourse, "undoWritePrimaryKey(") + " 处）：甲的边界是「只在撤销写侧抹主键」，" +
                "把判据接到这条不在撤销链上的写路径 = 换掉了另一条链持久化的东西（#304 是另一张卡）",
            0,
            occurrences(saveCourse, "undoWritePrimaryKey("),
        )
        assertEquals(
            "saveCourse 那一段里「$LEGACY_INSERT」仍恰一处（实到 " + occurrences(saveCourse, LEGACY_INSERT) +
                " 处）：那是**在册没收**的那一枚同族病（#304），甲明写不许顺手改它。" +
                "它归零 = 有人把撤销链的修法扩到了本卡范围之外（或者整手删掉了 saveCourse 的入库）",
            1,
            occurrences(saveCourse, LEGACY_INSERT),
        )
    }

    // ─────────────── ③ 抹主键那一手没搬家：normalize 的 copy 清单仍不含 id ───────────────

    /**
     * 第⓪问裁定「甲」的那道边界：`id = 0L` 不许写进 `CourseConstraints.normalize` 的 copy 清单 ——
     * 它另服务 `saveCourse`、`updateCoursePartialWeeks`（`@Update` 按主键匹配 + 把 `savedId` 交回编辑器
     * 写提醒）、`updateCourse`、`RestoreInPlace` 那一支与覆盖导入那条按 courseKey 复用旧 id 的路。
     * 抹了那五枚全变（逐枚账写在 `UndoReinsertIdentity.kt` 段首）。
     */
    @Test
    fun `抹主键那一手仍长在撤销写路径上 没有搬进 normalize`() {
        val constraints = blankCommentsKeepingLiterals(source(CONSTRAINTS))
        val hits = indexOfAll(constraints, NORMALIZE_ANCHOR)
        assertEquals(
            "锚点「$NORMALIZE_ANCHOR」在 CourseConstraints 里恰好一处（本层取块的宿主）：实到 " +
                "${hits.size} 处",
            1,
            hits.size,
        )
        val body = braceBodyFrom(constraints, constraints.indexOf('{', hits.first() + NORMALIZE_ANCHOR.length))
        // 逐字节：整块 copy 清单钉死（清单里就是没有 id）
        assertEquals(
            "normalize 的 copy 清单必须逐字节还是那八行（实到命中 " + occurrences(body, NORMALIZE_COPY_LIST) +
                " 处）：六项 name/periods/weeks/colorIndex/customColorArgb/credit —— **不含 id**。" +
                "往里加一枚 `id = 0L` 就是把撤销链之外的四枚写路径一起改掉（甲明令不许）",
            1,
            occurrences(body, NORMALIZE_COPY_LIST),
        )
        assertEquals(
            "normalize 体内「id = 0L」必须 0 处（实到 " + occurrences(body, "id = 0L") + " 处）：与上一格同一判据，" +
                "换行写法也盖得住",
            0,
            occurrences(body, "id = 0L"),
        )
        // 数出现次数：裸 0L 仍只有那两枚同形先例
        val repo = source(REPOSITORY)
        assertEquals(
            "仓储里「copy(id = 0L)」恰好两处（updateCoursePartialWeeks 拆行那一路 + saveSemesterWithRename " +
                "那一路，都是本仓既有的同形先例；实到 " + occurrences(repo, "copy(id = 0L)") +
                " 处）：撤销那一族不许在这里再写一遍裸 0L —— 取法只有判据那一枚",
            2,
            occurrences(repo, "copy(id = 0L)"),
        )
        assertEquals(
            "仓储里「copy(id = primaryKey)」恰好一处（就是撤销重插那一手，判据交回的值落在这里；实到 " +
                occurrences(repo, "copy(id = primaryKey)") + " 处）",
            1,
            occurrences(repo, COPY_PRIMARY_KEY_LINE.trim().let { it.substring(it.indexOf("copy(")) }),
        )
        // 读形状：原地回写那一支仍走 update（没被顺手改成重插）
        val undoUpdate = blankCommentsKeepingLiterals(repo)
        assertEquals(
            "仓储里「MainRowUndoDisposition.RestoreInPlace ->」恰好一处，且它后面跟的是 courseDao.update" +
                "（实到声明 " + occurrences(undoUpdate, "MainRowUndoDisposition.RestoreInPlace ->") +
                " 处、update 那一手 " + occurrences(undoUpdate, RESTORE_IN_PLACE_LINE) +
                " 处）：原地那一档的主键就是它的全部身份，抹了它就一个字也写不进去",
            1,
            occurrences(undoUpdate, RESTORE_IN_PLACE_LINE),
        )
    }

    // ─────────────── ④ 两枚前提：DAO 仍 REPLACE、实体仍 autoGenerate ───────────────

    @Test
    fun `DAO 仍是 REPLACE 实体仍是 autoGenerate 两枚前提都没被偷偷换掉`() {
        val dao = blankCommentsKeepingLiterals(source(COURSE_DAO))
        val insertDeclHits = indexOfAll(dao, COURSE_INSERT_DECL)
        assertEquals(
            "CourseDao 里「$COURSE_INSERT_DECL」恰好一处（实到 ${insertDeclHits.size} 处）：交出去的主键" +
                "到底怎么落库，由这一枚声明决定",
            1,
            insertDeclHits.size,
        )
        // 逐字节：insert 那一枚 @Insert 必须仍是 REPLACE（乙那一解被否掉的同一枚理由）
        val at = insertDeclHits.first()
        val preceding = dao.substring(0, at)
        assertEquals(
            "紧接 insert 声明之前的冲突策略必须是「$REPLACE_ON_INSERT」实到 " +
                occurrences(preceding, REPLACE_ON_INSERT) +
                " 处：换成 ABORT/IGNORE 会连带改掉 saveCourse 那枚不在撤销链上的写路径（第⓪问否掉的乙）；" +
                "而按今天这一手（交 0L）撞键这一档结构性不会发生，REPLACE 只是无害地留在那儿",
            1,
            occurrences(preceding, REPLACE_ON_INSERT),
        )
        assertEquals(
            "CourseDao 里「$REPLACE_ON_INSERT」总共两处（insert + insertAll；实到 " +
                occurrences(dao, REPLACE_ON_INSERT) + " 处）：枚数按声明数，不按行号",
            2,
            occurrences(dao, REPLACE_ON_INSERT),
        )
        val entity = blankCommentsKeepingLiterals(source(COURSE_ENTITY))
        assertEquals(
            "CourseEntity 的主键声明必须逐字还是「$AUTOGENERATE_PK」（实到 " +
                occurrences(entity, AUTOGENERATE_PK) + " 处）：这就是「交 0L ⇒ Room 另号」的全部依据。" +
                "摘掉 autoGenerate 之后本卡的修法会静默失效，而判据与守卫都看不见",
            1,
            occurrences(entity, AUTOGENERATE_PK),
        )
        assertEquals(
            "CourseEntity 里「val id: Long = 0L,」恰好一处（实到 " + occurrences(entity, "val id: Long = 0L,") +
                " 处）：默认值就是那枚哨兵，FRESH_ROW_PRIMARY_KEY 与它同源",
            1,
            occurrences(entity, "val id: Long = 0L,"),
        )
        val reminder = blankCommentsKeepingLiterals(source(REMINDER_ENTITY))
        assertEquals(
            "ReminderEntity 里「@PrimaryKey」恰好一处且紧跟「val courseId: Long,」（实到 " +
                occurrences(reminder, "@PrimaryKey") + " + " + occurrences(reminder, "val courseId: Long,") +
                " 处）：reminders 的主键就是 courseId ⇒ 被顶号那一门课的提醒行会原样归给冒进来的那一门。" +
                "本卡不收它（甲的修法让撞键这一档不再发生），但它是本卡理由的一部分，漂过要有人红",
            2,
            occurrences(reminder, "@PrimaryKey") + occurrences(reminder, "val courseId: Long,"),
        )
    }

    // ─────────────── ⑤ 判据内核：纯、唯一、结论没折成 Boolean ───────────────

    @Test
    fun `判据内核零 android 零时钟 全仓只有一份 结论不是 Boolean`() {
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
        for (probe in listOf(
            "LocalDate.now(", "System.currentTimeMillis", "System.", "Calendar.", "java.time.Clock",
            "Instant.", "ZonedDateTime", "TimeZone", "ZoneId", "Date(", "Build.VERSION", "Context",
            "File(", "readText", "room.", "Room.", "CourseDao", "reminderDao", "courseDao",
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
            "「$KERNEL_SIGNATURE」在 app/src/main 恰好一处定义（实到 " +
                mainOccurrenceCount(KERNEL_SIGNATURE) + " 处）：第二份定义 = 两把尺量同一件事，" +
                "改其一不改其余（写侧那枚调用点由第 ② 层数死）",
            1,
            mainOccurrenceCount(KERNEL_SIGNATURE),
        )
        assertEquals(
            "哨兵声明必须逐字还是「const val FRESH_ROW_PRIMARY_KEY: Long = 0L」（实到 " +
                occurrences(code, SENTINEL_DECL) + " 处）：与 CourseEntity 的默认值同源，" +
                "写侧不许再各写一遍裸 0L",
            1,
            occurrences(code, SENTINEL_DECL),
        )
        assertEquals(
            "内核的结论类型必须是 Long 而**不是** Boolean（「): Boolean」实到 " +
                occurrences(code, "): Boolean") + " 处）：卡面红线 —— 判据不许折成 Boolean。" +
                "这里交出去的是 DAO 直接吃的那枚主键，两档由它在表驱动单测里往回升成档位",
            0,
            occurrences(code, "): Boolean"),
        )
        assertEquals(
            "内核文件里 `internal` 可见性恰两枚（哨兵 + 判据本体，实到 " + occurrences(code, "internal ") +
                " 处）：判据不许被 UI 层抓去当第二把尺（同目录 MainRowUndoPolicy / GroupRowUndoPolicy 同一口径）",
            2,
            occurrences(code, "internal "),
        )
    }

    // ─────────────── ⑥ 探测器不空转：改前那一形必须被认出来 ───────────────

    @Test
    fun `探测器认得改前那一形 也不误伤今天那一形`() {
        val legacyBody = """
            courses.forEach { course ->
                val newId = CourseConstraints.normalize(course)?.let { courseDao.insert(it.toEntity()) }
                    ?: return@forEach
                reminders.filter { it.courseId == course.id }.forEach {
                    reminderDao.upsert(it.copy(courseId = newId).toEntity())
                }
            }
        """.trimIndent()
        assertEquals(
            "把改前那一手喂给同一把尺，「$LEGACY_INSERT」必须命中恰一处（实到 " +
                occurrences(blankCommentsKeepingLiterals(legacyBody), LEGACY_INSERT) +
                " 处）：0 处就说明第 ① 层那句「体内 0 处」是假的绿（锚点拼错）",
            1,
            occurrences(blankCommentsKeepingLiterals(legacyBody), LEGACY_INSERT),
        )
        val todayBody = """
            courses.forEach { course ->
                val newId = CourseConstraints.normalize(course)?.let { normalized ->
                    val primaryKey = undoWritePrimaryKey(normalized.id, landsOnSnapshotRow = false)
                    courseDao.insert(normalized.copy(id = primaryKey).toEntity())
                } ?: return@forEach
            }
        """.trimIndent()
        val blanked = blankCommentsKeepingLiterals(todayBody)
        assertEquals(
            "今天那一手不许被算成病灶（「$LEGACY_INSERT」实到 " + occurrences(blanked, LEGACY_INSERT) +
                " 处）：见 normalize 就杀的尺会把**判据本身**一起拆掉",
            0,
            occurrences(blanked, LEGACY_INSERT),
        )
        assertEquals(
            "今天那一手里取主键那手恰一处（实到 " + occurrences(blanked, TAKE_PRIMARY_KEY_LINE.trim()) +
                " 处）：注意这一格吃的是**去掉缩进后**的那一行 —— 块体本身带 trimIndent 的四个空格，" +
                "第 ① 层吃的是仓储原文（十二枚空格），两格各自独立",
            1,
            occurrences(blanked, TAKE_PRIMARY_KEY_LINE.trim()),
        )
        // 注释里逐字登记着改前那一手 —— 不抹注释就是把自己的注释当成病灶（假红）
        val kernelRaw = source(POLICY)
        assertTrue(
            "内核原文的注释里必须**仍然**逐字登记着改前那一手（「$LEGACY_INSERT」未抹注释时实到 " +
                occurrences(kernelRaw, LEGACY_INSERT) + " 处）：抹注释后第 ① 层才归零 —— " +
                "这就是「只 grep 字面量会假红」的实物证据，也是本守卫一律先抹注释的理由",
            occurrences(kernelRaw, LEGACY_INSERT) >= 1,
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
            (matchingBracket(source, open, '{', '}') ?: error("花括号没配平：从 $open 起（锚点认错了）")),
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
     * **只抹注释、保留字符串字面量内容**，长度与换行位置不变（本守卫的报错消息一律不带行号，
     * 行号会随注释与 KDoc 漂；这里保长度是为了让切片与配平仍然对得上）。
     *
     * 本卡尤其需要抹注释：内核文件与仓储的 KDoc 都逐字引用着改前那一手（登记旧形状用）。
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
        const val POLICY = "com/buaa/schedule/data/repository/UndoReinsertIdentity.kt"
        const val CONSTRAINTS = "com/buaa/schedule/domain/schedule/CourseConstraints.kt"
        const val COURSE_DAO = "com/buaa/schedule/data/local/CourseDao.kt"
        const val COURSE_ENTITY = "com/buaa/schedule/data/local/CourseEntity.kt"
        const val REMINDER_ENTITY = "com/buaa/schedule/data/local/ReminderEntity.kt"

        const val FUNNEL_ANCHOR = "private suspend fun insertWithReminders("
        const val UNDO_ENTRY_ANCHOR = "suspend fun applyUndo(action: UndoManager.UndoAction)"
        const val UNDO_UPDATE_ANCHOR = "private suspend fun undoUpdate(action: UndoManager.UndoAction.Update)"
        const val SAVE_COURSE_ANCHOR = "suspend fun saveCourse(course: Course): Long? = writeMutex.withLock"
        const val NORMALIZE_ANCHOR = "fun normalize(course: Course): Course?"

        /** 改前那一手的逐字本体（第 ① 层的锚点；今天只在注释里逐字登记着） */
        const val LEGACY_INSERT = "courseDao.insert(it.toEntity())"
        const val TAKE_NEEDLE = "undoWritePrimaryKey("

        /** 今天那一手的两行原文（逐字节钉；缩进按仓储里的实际写法） */
        const val TAKE_PRIMARY_KEY_LINE =
            "                val primaryKey = undoWritePrimaryKey(normalized.id, landsOnSnapshotRow = false)"
        const val COPY_PRIMARY_KEY_LINE =
            "                courseDao.insert(normalized.copy(id = primaryKey).toEntity())"

        /** 原地回写那一支（不许被顺手改成重插） */
        const val RESTORE_IN_PLACE_LINE = "MainRowUndoDisposition.RestoreInPlace ->"

        /** 两枚前提（逐字节） */
        const val REPLACE_ON_INSERT = "@Insert(onConflict = OnConflictStrategy.REPLACE)"
        const val COURSE_INSERT_DECL = "suspend fun insert(course: CourseEntity): Long"
        const val AUTOGENERATE_PK = "@PrimaryKey(autoGenerate = true)"
        const val KERNEL_SIGNATURE = "internal fun undoWritePrimaryKey(snapshotId: Long, landsOnSnapshotRow: Boolean)"
        const val SENTINEL_DECL = "const val FRESH_ROW_PRIMARY_KEY: Long = 0L"

        /** normalize 的 copy 清单整块（逐字节；八行、含八枚空格的基础缩进） */
        val NORMALIZE_COPY_LIST = listOf(
            "        return course.copy(",
            "            name = name,",
            "            periods = periods,",
            "            weeks = weeks,",
            "            colorIndex = course.colorIndex.coerceAtLeast(0),",
            "            customColorArgb = normalizeCustomColorArgb(course.customColorArgb),",
            "            credit = normalizeCredit(course.credit),",
            "        )",
        ).joinToString("\n")
    }
}
