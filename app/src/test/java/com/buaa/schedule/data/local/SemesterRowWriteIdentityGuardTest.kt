package com.buaa.schedule.data.local

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T304③：学期行「落那一枚新行」的落点守卫（#514 之外的在册 C1，T299 审计交回）。
 *
 * 病灶是三行连起来才成立的（每一行单看都「看着没问题」），所以本守卫每层只管其中一行：
 * ① 写侧那一手插进去的是**抹过号的那一枚**（这一层就是 arm W 的那一层）② 抹号那一手长在
 * 载体的**类体属性**里，没搬回调用点 ③ 全仓没长出第二把尺、也没把同族真源的先例顶掉
 * ④ 两枚前提（DAO 仍 REPLACE、实体仍 autoGenerate）还在 ⑤ 分档内核纯 JVM、唯一、结论没折成 Boolean
 * ⑥ 探测器自己认得改前那一形（不空转）⑦ 四枚在册 insert 站点的分档形状没漂。
 *
 * 纯源码核对（JVM，无 Robolectric、无设备、不读时钟）。读法与小工具抄
 * `data/repository/UndoReinsertIdentityGuardTest` 与本目录 `SemesterRowShapeGuardTest`
 * （抹注释保字面量 + 花括号配平取块 + 锚点找不到就抛）。读的是 `app/src/main/java` 下的源码文本 ——
 * 那一目录已在 `app/build.gradle.kts` 的 `guardReadWorkingTreeFiles` 里整目录进
 * `tasks.withType<Test>()` 的 inputs（T92①，台账 #136），所以「改了源码、单跑守卫拿到缓存绿灯」盖得住。
 *
 * ## 四种钉法逐格点名（并交代另一种为什么不行）
 *
 * 1. [写侧那一手吃类体属性] —— **逐字节**（`semesterDao.insert(step.rowToWrite.toEntity())` 整行原文
 *    在 `applySemesterRowSteps` 那一块里恰一处）+ **抹注释找锚点**（改前那一形
 *    `semesterDao.insert(step.semester.toEntity())` 抹注释后 0 处，而它在内核与本守卫的注释里逐字登记着，
 *    未抹注释必须 ≥ 1 处）+ **读形状**（`WriteSemesterRow ->` 那一支之后才出现那枚 insert，顺序反了
 *    就是分支与落点脱钩）。
 *    **数出现次数为什么不行**：`semesterDao.insert(` 改前改后都恰好一枚，枚数尺在这格完全哑火 ——
 *    量得出差异的只有那枚实参的字面，所以这一格只能逐字节。
 *    **只读形状为什么也不行**：从 `when` 分支配平取块只能证明「这里有枚 insert」，证明不了插进去的
 *    是抹过号的那一枚；抹号那一手根本不在函数体里（它在类体的属性上），任何「取函数体再扫字面量」的尺
 *    都会把它误判成缺失。
 * 2. [抹号那一手没搬家] —— **读形状**（花括号配平取 `data class WriteSemesterRow(…)` 的类体块，
 *    块内 `copy(id` 恰一枚、`FRESH_ROW_PRIMARY_KEY` 恰一枚、分档判据那手恰一枚）+ **逐字节**
 *    （类体里 `LandsOnSnapshotRow -> semester.copy(id = FRESH_ROW_PRIMARY_KEY)` 与
 *    `LandsAsFreshRow -> semester` 两支各恰一处）+ **数出现次数**（前者全 app/src/main 恰一处 = 只有
 *    类体这一处；`copy(id = 0L)` 在 `ScheduleRepository.kt` 仍恰两处 = 别人那两枚同形先例，
 *    不许被本卡顶掉也不许涨）。
 *    **只逐字节为什么不够**：整行钉死写法，钉不住「它长在谁身上」—— 有人把那行原样搬进
 *    `applySemesterRowSteps` 体内（正是「调用点自己记得调」那一形，这一族出事的原因），逐字节尺照旧绿。
 * 3. [没造第二把尺] —— **数出现次数**（全 `app/src/main`：`const val FRESH_ROW_PRIMARY_KEY: Long = 0L`
 *    恰一枚、`fun undoWritePrimaryKey(` 恰一枚定义、`undoWritePrimaryKey(` 恰两处 = 一枚定义 + 撤销链
 *    那一枚调用点 ⇒ 学期行这一族**没有**另起第三枚调用点，也没有在旁边再写一遍取法、
 *    `fun semesterWritePrimaryKeyLanding(` 恰一处定义）。
 *    **读形状为什么不行**：第二把尺可能落在全仓任何一枚文件里（换文件、换层），按块取覆盖不到「全仓」
 *    这一维；这一层的价值恰恰在跨文件。
 * 4. [两枚前提] —— **逐字节**（`SemesterDao` 的 `@Insert(onConflict = OnConflictStrategy.REPLACE)` 与
 *    `suspend fun insert(semester: SemesterEntity): Long` 各恰一处；`SemesterEntity` 的
 *    `@PrimaryKey(autoGenerate = true)` 与 `val id: Long = 0L,` 各恰一处）。
 *    另一种（数出现次数）不行：这两枚各只有一处声明，枚数与逐字在这里等价，而逐字能连带钉住
 *    「REPLACE 而不是 ABORT/IGNORE」这一维 —— 换成 ABORT 时枚数尺同样报 1 处，病灶却已换了一种。
 * 5. [分档内核纯度与唯一定义] —— **读形状**（逐行扫 import）+ **数出现次数**（两档声明各恰一处、
 *    判据函数恰一处定义、内核里 `): Boolean` 0 处 ⇒ 结论没被折成 Boolean；零 android / 时钟 / DAO / Room）。
 * 6. [探测器不空转] —— **逐字节**：把改前那一手与今天那一手各喂一次同一把尺，前者必须命中、
 *    后者必须 0 命中（缺一格第 ① 层就是假的绿）。
 * 7. [四枚在册站点没漂] —— **读形状**（按函数名配平取体）+ **数出现次数**：`semesterDao.insert(` 全文件
 *    恰六枚，逐枚对上判语（:180 显式抹号、:580/:782 原样回写、:840/:845 内核造的补建行、:667 本卡那一枚）。
 *    **只数总枚数为什么不够**：六枚这一维对「谁带号、谁抹号」完全无感，五枚在册站点全被换形也照样是六枚。
 *
 * 报错消息一律不带行号（行号随注释与 KDoc 漂；本仓这一族的规矩）。期望值是**盘面里自己写的字面量**，
 * 不吃被验对象自己的运行时输出 —— 逐字节锚点取的是磁盘上的源码文本本身，与被验代码不是同一条求值路径。
 *
 * ## 本守卫**不证明**运行时行为
 *
 * Room 拿哨兵到底另不另号、REPLACE 到底命不命中同一枚 rowid —— `:app` 的 JVM 测试面没有 Robolectric、
 * 也没有能在 JVM 上跑 Room 的缝，那一档归编排者装机（零 adb、零设备）。判据逐格取值与假库落库后果由
 * `SemesterRowPolicyTest` 的④段钉；本文件钉的是「写侧那一手真的吃类体属性、类体属性真的取了真源哨兵」。
 */
class SemesterRowWriteIdentityGuardTest {

    // ─────────────── ① 写侧那一手吃的是类体属性（arm W 的红在这里） ───────────────

    @Test
    fun `学期行写侧插进去的是抹过号的那一枚 不是步骤带进来的旧号`() {
        val repo = blankCommentsKeepingLiterals(source(REPOSITORY))
        val body = functionBody(repo, STEPS_ANCHOR)

        assertEquals(
            "`applySemesterRowSteps` 体内 `$WRITE_NEEDLE` 恰好一处（实到 " + occurrences(body, WRITE_NEEDLE) +
                " 处）：`WriteSemesterRow` 这一枚步骤只许落一次库，两枚就意味着有人把补建与收口拆成两趟",
            1,
            occurrences(body, WRITE_NEEDLE),
        )
        assertEquals(
            "体内「$FRESH_HANDOFF」恰好一处（实到 " + occurrences(body, FRESH_HANDOFF) +
                " 处）：逐字节钉住「交出去的是类体属性 rowToWrite（主键恒为真源哨兵）⇒ Room 另号 ⇒ " +
                "『最近写入的 = 当前学期』成立」那一手。0 处 = 落点与承诺又分家了（本卡的病灶换写法回来）",
            1,
            occurrences(body, FRESH_HANDOFF),
        )
        assertEquals(
            "体内「$SNAPSHOT_HANDOFF」必须 0 处（实到 " + occurrences(body, SNAPSHOT_HANDOFF) +
                " 处）：那是改前那一手 —— `semesterDao.insert` 是 OnConflictStrategy.REPLACE、" +
                "SemesterEntity 的主键是 @PrimaryKey(autoGenerate = true) ⇒ 带旧号去插走的是**显式主键**，" +
                "同一趟 DeleteRowsForTermCode 刚删过那枚行 ⇒ 命中同一个 rowid、不新增行，" +
                "那句「由 Room 自增」根本没发生；那一枚号此刻若被别的 termCode 的行持有就被整行顶掉" +
                "（#520/T281 课程侧、#527/T287 建课侧同族）。红了就说明有人把这一手接回去了",
            0,
            occurrences(body, SNAPSHOT_HANDOFF),
        )
        // 读形状 + 顺序：那枚 insert 必须排在 WriteSemesterRow 那一支之后
        val branchAt = body.indexOf("is SemesterRenameSaveStep.WriteSemesterRow ->")
        val insertAt = body.indexOf(FRESH_HANDOFF)
        assertTrue(
            "顺序判据：`WriteSemesterRow ->` 那一支必须排在落库那一手**之前**（实到 分支 index=$branchAt、" +
                "insert index=$insertAt）。反了就是分支与落点脱钩，when 的两支互相吃掉",
            branchAt in 0 until insertAt,
        )
        // 写侧不许自己抹号（那才是「调用点记得调」那一形）
        assertEquals(
            "体内「copy(id」必须 0 处（实到 " + occurrences(body, "copy(id") +
                " 处）：抹号那一手的落点是载体的类体属性（第 ② 层）。写侧再抹一次就是两枚地方管同一枚主键",
            0,
            occurrences(body, "copy(id"),
        )
    }

    // ─────────────── ② 抹号那一手长在类体属性上，没搬回调用点 ───────────────

    @Test
    fun `派生主键落在类体属性上 没有变成调用点自己记得调的那枚 helper`() {
        val policy = blankCommentsKeepingLiterals(source(POLICY))
        val body = functionBody(policy, STEP_DECL_ANCHOR)

        assertEquals(
            "载体类体内「$COPY_BRANCH」恰好一处（实到 " + occurrences(body, COPY_BRANCH) +
                " 处）：本仓派生字段只有两条落点（类体属性 / 每枚构造站点各配守卫），这一族走的是前者 —— " +
                "构造步骤的人没有「自己记得抹号」这件事可做，而这正是 #520 那一族出事的原因",
            1,
            occurrences(body, COPY_BRANCH),
        )
        assertEquals(
            "类体内「$FRESH_BRANCH」恰好一处（实到 " + occurrences(body, FRESH_BRANCH) +
                " 处）：本来就是新行那一格**原样带走**（幂等）—— 这一支要是也被接进 copy 那一支，" +
                "「本来就是新行」就会被当成违规（arm N 朝窄的那一格）",
            1,
            occurrences(body, FRESH_BRANCH),
        )
        assertEquals(
            "类体内「copy(id」恰好一处（实到 " + occurrences(body, "copy(id") + " 处）：只抹主键这一枚派生字段，" +
                "内容四项不许顺手一起重写",
            1,
            occurrences(body, "copy(id"),
        )
        assertEquals(
            "类体内「$SENTINEL」恰好一处（实到 " + occurrences(body, SENTINEL) +
                " 处）：取值口径来自同族真源那枚哨兵，类体里不落第二枚裸 0L（#455 那一族就是" +
                "「唯一真源旁边又各写一遍」）",
            1,
            occurrences(body, SENTINEL),
        )
        assertEquals(
            "类体内「$LANDING_KERNEL_CALL」恰好一处（实到 " + occurrences(body, LANDING_KERNEL_CALL) +
                " 处）：抹不抹号由分档判据说了算，而不是类体自己再解释一遍「什么算新号」",
            1,
            occurrences(body, LANDING_KERNEL_CALL),
        )
        assertEquals(
            "全 app/src/main 里「$COPY_BRANCH」恰好一处（实到 " +
                mainOccurrenceCount(COPY_BRANCH) + " 处）：搬进 `applySemesterRowSteps` 体内、" +
                "或另起一枚 helper 让调用点自己调，都会让这一格涨数 —— 而那正是本卡红线否掉的第三条路",
            1,
            mainOccurrenceCount(COPY_BRANCH),
        )
        // 别人那两枚同形先例：一枚不动、也不许涨
        val repoRaw = source(REPOSITORY)
        assertEquals(
            "`ScheduleRepository.kt` 里「copy(id = 0L)」仍恰两处（switchSemester 那一路 + " +
                "updateCoursePartialWeeks 拆行那一路；实到 " + occurrences(repoRaw, "copy(id = 0L)") +
                " 处）：这两枚是 `UndoReinsertIdentityGuardTest` 第③层钉着的既有先例，本卡一字不动；" +
                "涨第三枚就说明有人绕过类体、在写侧自己抹号",
            2,
            occurrences(repoRaw, "copy(id = 0L)"),
        )
    }

    // ─────────────── ③ 没造第二把尺、也没把真源的调用点摊开 ───────────────

    @Test
    fun `同族真源只有一枚哨兵 学期行这一族没有另起 undoWritePrimaryKey 的第二枚调用点`() {
        assertEquals(
            "全 app/src/main 里「$SENTINEL_DECL」恰好一处（实到 " + mainOccurrenceCount(SENTINEL_DECL) +
                " 处）：『库里任何一行都拿不到的那枚号』全仓只有一份定义。学期行这一族复用它，" +
                "不自造第二枚哨兵",
            1,
            mainOccurrenceCount(SENTINEL_DECL),
        )
        assertEquals(
            "全 app/src/main 里「fun undoWritePrimaryKey(」恰好一处定义（实到 " +
                mainOccurrenceCount("fun undoWritePrimaryKey(") + " 处）：撤销链那枚判据没被复制",
            1,
            mainOccurrenceCount("fun undoWritePrimaryKey("),
        )
        assertEquals(
            "全 app/src/main 里「$TAKE_NEEDLE」恰好两处（一枚定义 + 撤销链那一枚调用点；实到 " +
                mainOccurrenceCount(TAKE_NEEDLE) + " 处）：本卡**故意不调**它 —— " +
                "它那根 landsOnSnapshotRow 轴量的是撤销链的两档（RestoreInPlace 走 @Update 按主键匹配 / " +
                "重插族另号），而 WriteSemesterRow 只有一档「落新行」，递一枚恒为 false 的旗进去等于把" +
                "分档折回调用点自己判（卡面红线）。第三枚调用点 = 有人把两族的轴混成一根",
            2,
            mainOccurrenceCount(TAKE_NEEDLE),
        )
        assertEquals(
            "全 app/src/main 里「fun semesterWritePrimaryKeyLanding(」恰好一处定义（实到 " +
                mainOccurrenceCount("fun semesterWritePrimaryKeyLanding(") +
                " 处）：学期行这一族的全仓唯一一枚分档尺（第二枚定义 = 两把尺量同一件事）",
            1,
            mainOccurrenceCount("fun semesterWritePrimaryKeyLanding("),
        )
    }

    // ─────────────── ④ 两枚前提：DAO 仍 REPLACE、实体仍 autoGenerate ───────────────

    @Test
    fun `DAO 仍是 REPLACE 实体仍是 autoGenerate 两枚前提都没被偷偷换掉`() {
        val dao = blankCommentsKeepingLiterals(source(DAO))
        assertEquals(
            "SemesterDao 里「$REPLACE_ON_INSERT」必须逐字还是恰一处（实到 " +
                occurrences(dao, REPLACE_ON_INSERT) + " 处）：换成 ABORT/IGNORE 之后" +
                "「交旧号会整行顶掉那一枚行」这条病灶就换了形状，本卡与 ①②层的话要跟着重写",
            1,
            occurrences(dao, REPLACE_ON_INSERT),
        )
        assertEquals(
            "SemesterDao 里「$SEMESTER_INSERT_DECL」恰一处（实到 " + occurrences(dao, SEMESTER_INSERT_DECL) +
                " 处）：交出去的主键到底怎么落库，由这一枚声明决定",
            1,
            occurrences(dao, SEMESTER_INSERT_DECL),
        )
        val entity = blankCommentsKeepingLiterals(source(ENTITY))
        assertEquals(
            "SemesterEntity 的主键声明必须逐字还是「$AUTOGENERATE_PK」（实到 " +
                occurrences(entity, AUTOGENERATE_PK) + " 处）：这就是「交哨兵 ⇒ Room 另号」的全部依据。" +
                "摘掉 autoGenerate 之后本卡的修法会静默失效，而判据与守卫都看不见",
            1,
            occurrences(entity, AUTOGENERATE_PK),
        )
        assertEquals(
            "SemesterEntity 里「val id: Long = 0L,」恰一处（实到 " + occurrences(entity, "val id: Long = 0L,") +
                " 处）：默认值与哨兵同源",
            1,
            occurrences(entity, "val id: Long = 0L,"),
        )
    }

    // ─────────────── ⑤ 分档内核：纯 JVM、唯一、结论没折成 Boolean ───────────────

    @Test
    fun `分档内核零 android 零时钟 结论是两档而不是 Boolean`() {
        val raw = source(POLICY)
        val code = blankCommentsKeepingLiterals(raw)

        val offenders = raw.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("import ") }
            .filter { it.contains("android.") || it.contains("androidx.") || it.contains("kotlinx.coroutines") }
            .toList()
        assertEquals(
            "纯 JVM 判据必须零 android / androidx / 协程 import（仓库硬规矩：设备面一律由调用点当参数传进来）。" +
                "实到：" + offenders,
            emptyList<String>(),
            offenders,
        )
        for (probe in listOf(
            "LocalDate.now(", "System.", "Calendar.", "java.time.Clock", "Instant.", "ZonedDateTime",
            "TimeZone", "Build.VERSION", "Context", "File(", "readText", "semesterDao", "courseDao",
            "withTransaction", "Room",
        )) {
            assertEquals(
                "内核里「$probe」必须 0 处（实到 " + occurrences(code, probe) +
                    " 处）：判据一旦能读钟/机型/上下文/DAO/文件系统，同一枚输入就会因" +
                    "「今天几点、在哪台机器上、库里此刻有什么」给出不同结论，表驱动单测随之失真",
                0,
                occurrences(code, probe),
            )
        }
        assertEquals(
            "两枚档名必须各恰一处声明（「$LANDING_TIER_FRESH」+「$LANDING_TIER_SNAPSHOT」实到 " +
                occurrences(code, LANDING_TIER_FRESH) + " + " + occurrences(code, LANDING_TIER_SNAPSHOT) +
                " 处）：分档是本卡的结论域，别处不许把同一件事再枚举一遍",
            2,
            occurrences(code, LANDING_TIER_FRESH) + occurrences(code, LANDING_TIER_SNAPSHOT),
        )
        assertEquals(
            "判据的结论类型必须是分档而**不是** Boolean（「): Boolean」实到 " + occurrences(code, "): Boolean") +
                " 处）：卡面红线 —— 返回布尔之后调用点各自再判一次就是第二把尺",
            0,
            occurrences(code, "): Boolean"),
        )
    }

    // ─────────────── ⑥ 探测器不空转 ───────────────

    @Test
    fun `探测器认得改前那一形 也不误伤今天那一形`() {
        val legacyBranch = """
            is SemesterRenameSaveStep.WriteSemesterRow ->
                semesterDao.insert(step.semester.toEntity())
        """.trimIndent()
        assertEquals(
            "把改前那一手喂给同一把尺，「$SNAPSHOT_HANDOFF」必须命中恰一处（实到 " +
                occurrences(blankCommentsKeepingLiterals(legacyBranch), SNAPSHOT_HANDOFF) +
                " 处）：0 处就说明第 ① 层那句「体内 0 处」是假的绿（锚点拼错）",
            1,
            occurrences(blankCommentsKeepingLiterals(legacyBranch), SNAPSHOT_HANDOFF),
        )
        val todayBranch = """
            is SemesterRenameSaveStep.WriteSemesterRow ->
                semesterDao.insert(step.rowToWrite.toEntity())
        """.trimIndent()
        assertEquals(
            "今天那一手不许被算成病灶（「$SNAPSHOT_HANDOFF」实到 " +
                occurrences(blankCommentsKeepingLiterals(todayBranch), SNAPSHOT_HANDOFF) +
                " 处）：见 insert 就杀的尺会把判据本身一起拆掉",
            0,
            occurrences(blankCommentsKeepingLiterals(todayBranch), SNAPSHOT_HANDOFF),
        )
        assertEquals(
            "今天那一手里「$FRESH_HANDOFF」恰一处（实到 " +
                occurrences(blankCommentsKeepingLiterals(todayBranch), FRESH_HANDOFF) + " 处）：同一把尺" +
                "在两个方向都数得出来，第 ① 层那句「恰一处」才不是空转",
            1,
            occurrences(blankCommentsKeepingLiterals(todayBranch), FRESH_HANDOFF),
        )
        // 改前那一形在**注释**里逐字登记着（在册不许接回去）；不抹注释就还在，抹了才归零
        val policyRaw = source(POLICY)
        assertTrue(
            "内核注释里必须仍逐字登记着改前那一形（未抹注释时「$SNAPSHOT_HANDOFF」实到 " +
                occurrences(policyRaw, SNAPSHOT_HANDOFF) + " 处）：抹注释后第 ① 层才归零 —— " +
                "这就是「只 grep 字面量会假红」的实物证据，也是本守卫一律先抹注释的理由",
            occurrences(policyRaw, SNAPSHOT_HANDOFF) >= 1,
        )
    }

    // ─────────────── ⑦ 四枚在册 insert 站点：判语钉在形状上 ───────────────

    @Test
    fun `semesters 表的六枚 insert 站点逐枚形状与判语对得上`() {
        val repo = blankCommentsKeepingLiterals(source(REPOSITORY))
        val total = occurrences(repo, WRITE_NEEDLE)
        assertEquals(
            "`ScheduleRepository.kt` 里 `$WRITE_NEEDLE` 恰好六处（switchSemester / saveSemester / " +
                "applySemesterRowSteps / replaceSemesterCoursesInTx / 恢复那两枚 insert；实到 $total 处）：" +
                "学期行的写侧口子一共就这六枚，第七枚要先交代它吃哪一枚主键",
            6,
            total,
        )

        // :180 —— 显式抹号那一枚（本卡的对照面，一字未动）
        val switch = functionBody(repo, SWITCH_ANCHOR)
        assertEquals(
            "`switchSemester` 体内「$SWITCH_HANDOFF」恰一处（实到 " + occurrences(switch, SWITCH_HANDOFF) +
                " 处）：带 id 的行经 `copy(id = 0L)` 再插 ⇒ 自增把它顶成 id 最大 = 当前学期。" +
                "这一枚是本卡给 :667 找的同形先例，不许被顺手改掉或被删（`:78` 与 :114-117 那两把尺就压在这句话上）",
            1,
            occurrences(switch, SWITCH_HANDOFF),
        )

        // :580 与 :782 —— 同一形（原样回写），判语不同：前者今天恒收新鲜号，后者在册待收
        assertEquals(
            "全文件里「$PLAIN_HANDOFF」恰两处（saveSemester 与 replaceSemesterCoursesInTx；实到 " +
                occurrences(repo, PLAIN_HANDOFF) + " 处）：这两枚都是**原样回写**的形状，" +
                "差别只在喂进来的号（见下面两格）",
            2,
            occurrences(repo, PLAIN_HANDOFF),
        )
        val saveSemester = functionBody(repo, SAVE_SEMESTER_ANCHOR)
        assertEquals(
            "`saveSemester` 体内「$PLAIN_HANDOFF」恰一处（实到 " + occurrences(saveSemester, PLAIN_HANDOFF) +
                " 处）：判语「新鲜行」的依据是 main 里 `repository.saveSemester(` **零枚调用点**（下一格钉），" +
                "仅剩两枚 androidTest 调用点都构造不带 id 的 Semester ⇒ 默认 0L。" +
                "若哪天有 main 调用点把库里读出的行递进来，这一枚就与 :667 同病",
            1,
            occurrences(saveSemester, PLAIN_HANDOFF),
        )
        assertEquals(
            "全 app/src/main 里「$VM_LEGACY_SAVE_CALL」必须 0 处（实到 " + mainOccurrenceCount(VM_LEGACY_SAVE_CALL) +
                " 处）：`saveSemester` 在 main 没有调用点（#461 之后 VM 那一跳只走 saveSemesterWithRename）—— " +
                "这一格就是上一枚「新鲜行」判语的依据。它涨数 = 那条写路又有人用了，判语要跟着重裁",
            0,
            mainOccurrenceCount(VM_LEGACY_SAVE_CALL),
        )
        assertEquals(
            "靶子不空转：把「VM 那一跳今天改走了合并事务」之前的调用喂给同一把尺，" +
                "「$VM_LEGACY_SAVE_CALL」必须命中恰一处（实到 " +
                occurrences(blankCommentsKeepingLiterals(VM_LEGACY_SAVE_CALL), VM_LEGACY_SAVE_CALL) + " 处）",
            1,
            occurrences(blankCommentsKeepingLiterals(VM_LEGACY_SAVE_CALL), VM_LEGACY_SAVE_CALL),
        )
        val replaceInTx = functionBody(repo, REPLACE_IN_TX_ANCHOR)
        assertEquals(
            "`replaceSemesterCoursesInTx` 体内「$PLAIN_HANDOFF」恰一处（实到 " +
                occurrences(replaceInTx, PLAIN_HANDOFF) + " 处）：这一枚判语是**带号回写**（在册待收，" +
                "不在本卡红线内）—— 它的 semester 出自 `buildFallbackSemester` 那一手" +
                "`currentSemester?.takeIf { it.termCode == termCode }`，重导当前学期／静默刷新那一档" +
                "**直接交回库里读出的那一枚带真号的行**，与 :667 同一枚病；本卡不收它是因为那一手不在 " +
                "step 载体上，收它就得给裸调用点配一枚「记得调」的 helper（卡面红线否掉的第三条路）",
            1,
            occurrences(replaceInTx, PLAIN_HANDOFF),
        )

        // :840 与 :845 —— 内核造的补建行，构造里就没有 id
        val restore = functionBody(repo, RESTORE_ANCHOR)
        assertEquals(
            "恢复那一块里「$BACKUP_HANDOFF」恰一处（实到 " + occurrences(restore, BACKUP_HANDOFF) +
                " 处）：判语「新鲜行」—— `decision.semester` 出自 `backupRow.copy(startDate…, totalWeeks…)`，" +
                "copy 清单不含 id，而 backupRow 出自 `BackupSemester.toDomain()`（备份模型根本没有 id 字段）" +
                "⇒ 交出去恒是 0L",
            1,
            occurrences(restore, BACKUP_HANDOFF),
        )
        assertEquals(
            "恢复那一块里「$REBUILD_HANDOFF」恰一处（实到 " + occurrences(restore, REBUILD_HANDOFF) +
                " 处）：判语「新鲜行」—— `Rebuild.toSemester()` 只构造 termCode/termName/startDate/totalWeeks " +
                "四项，其 KDoc 明写「id 留 0 由 Room 自增」",
            1,
            occurrences(restore, REBUILD_HANDOFF),
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

    /**
     * 取某一枚函数/类声明的**全文**（锚点起、到与它配平的那一枚闭括号止）：
     * 锚点找不到就抛、撞出多枚也抛、配平失败同样抛 —— 静默返回空串会把守卫变成永远绿的空尺。
     */
    private fun functionBody(code: String, anchor: String): String {
        val hits = indexOfAll(code, anchor)
        if (hits.isEmpty()) {
            throw AssertionError("锚点 `$anchor` 在源码里找不到：写法变了或整段被搬走，本守卫要跟着重钉，不许静默绿")
        }
        assertEquals("锚点 `$anchor` 必须恰好一处（实到 ${hits.size} 处）：第二份同名宿主 = 两把尺", 1, hits.size)
        val start = hits.first()
        val open = code.indexOf('{', start + anchor.length)
        if (open < 0) throw AssertionError("`$anchor` 之后找不到开花括号：切不出一段体")
        val close = matchingBracket(code, open, '{', '}') ?: throw AssertionError("花括号没配平：从 $open 起")
        if (close <= open || close >= code.length) {
            throw AssertionError("取块越界：open=$open、close=$close、文件长 ${code.length}")
        }
        return code.substring(start, close + 1)
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
     * **只抹注释、保留字符串字面量内容**，长度与换行位置不变（本守卫的报错消息不带行号，
     * 但切片与配平要靠长度对齐）。本卡尤其需要抹注释：内核与仓储的 KDoc 都逐字登记着改前那一形。
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
        const val POLICY = "com/buaa/schedule/data/local/SemesterRowPolicy.kt"
        const val REPOSITORY = "com/buaa/schedule/data/repository/ScheduleRepository.kt"
        const val DAO = "com/buaa/schedule/data/local/SemesterDao.kt"
        const val ENTITY = "com/buaa/schedule/data/local/SemesterEntity.kt"

        const val STEPS_ANCHOR = "private suspend fun applySemesterRowSteps("
        const val STEP_DECL_ANCHOR = "data class WriteSemesterRow(val semester: Semester) : SemesterRenameSaveStep"
        const val SWITCH_ANCHOR = "suspend fun switchSemester(termCode: String): Boolean"
        const val SAVE_SEMESTER_ANCHOR = "suspend fun saveSemester(semester: Semester)"
        const val REPLACE_IN_TX_ANCHOR = "private suspend fun replaceSemesterCoursesInTx("
        const val RESTORE_ANCHOR = "private suspend fun replaceCoursesForExistingSemesterInTx("

        /** 落库口子本体（枚数尺用） */
        const val WRITE_NEEDLE = "semesterDao.insert("

        /** 今天那一手与改前那一手（逐字节；①⑥层共用同一把尺） */
        const val FRESH_HANDOFF = "semesterDao.insert(step.rowToWrite.toEntity())"
        const val SNAPSHOT_HANDOFF = "semesterDao.insert(step.semester.toEntity())"

        /** 类体属性那一手的两支（逐字节）+ 它取值的口径 */
        const val COPY_BRANCH =
            "is SemesterWritePrimaryKeyLanding.LandsOnSnapshotRow -> semester.copy(id = FRESH_ROW_PRIMARY_KEY)"
        const val FRESH_BRANCH = "SemesterWritePrimaryKeyLanding.LandsAsFreshRow -> semester"
        const val SENTINEL = "FRESH_ROW_PRIMARY_KEY"
        const val LANDING_KERNEL_CALL = "semesterWritePrimaryKeyLanding(semester.id)"

        /** 真源与两族共用的哨兵声明 */
        const val SENTINEL_DECL = "const val FRESH_ROW_PRIMARY_KEY: Long = 0L"
        const val TAKE_NEEDLE = "undoWritePrimaryKey("

        /** 分档两档的声明（⑤层） */
        const val LANDING_TIER_FRESH = "data object LandsAsFreshRow : SemesterWritePrimaryKeyLanding"
        const val LANDING_TIER_SNAPSHOT =
            "data class LandsOnSnapshotRow(val snapshotId: Long) : SemesterWritePrimaryKeyLanding"

        /** ④层两枚前提（逐字节） */
        const val REPLACE_ON_INSERT = "@Insert(onConflict = OnConflictStrategy.REPLACE)"
        const val SEMESTER_INSERT_DECL = "suspend fun insert(semester: SemesterEntity): Long"
        const val AUTOGENERATE_PK = "@PrimaryKey(autoGenerate = true)"

        /** ⑦层：五枚在册站点的落库原文 + saveSemester 判语的依据 */
        const val SWITCH_HANDOFF = "semesterDao.insert(plan.target.copy(id = 0L).toEntity())"
        const val PLAIN_HANDOFF = "semesterDao.insert(semester.toEntity())"
        const val BACKUP_HANDOFF = "semesterDao.insert(decision.semester.toEntity())"
        const val REBUILD_HANDOFF = "semesterDao.insert(decision.toSemester().toEntity())"
        const val VM_LEGACY_SAVE_CALL = "repository.saveSemester(semester)"
    }
}
