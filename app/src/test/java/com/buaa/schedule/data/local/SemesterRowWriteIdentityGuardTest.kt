package com.buaa.schedule.data.local

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T304③：学期行「落那一枚新行」的落点守卫（#514 之外的在册 C1，T299 审计交回）。
 * T316③ 扩到九层：剩下四枚在册站点逐枚裁过之后，⑦ 层钉的「落库原文」升格为「判语 × 原文」的册子（⑨），
 * 并给三枚判「取不到号」的站点把**链条本体**钉出齿（⑧）—— ⑦ 层只数原文，原文背后那句判语本来零尺。
 *
 * 病灶是三行连起来才成立的（每一行单看都「看着没问题」），所以本守卫每层只管其中一行：
 * ① 写侧那一手插进去的是**抹过号的那一枚**（这一层就是 arm W 的那一层）② 抹号那一手长在
 * 载体的**类体属性**里，没搬回调用点 ③ 全仓没长出第二把尺、也没把同族真源的先例顶掉
 * ④ 两枚前提（DAO 仍 REPLACE、实体仍 autoGenerate）还在 ⑤ 分档内核纯 JVM、唯一、结论没折成 Boolean
 * ⑥ 探测器自己认得改前那一形（不空转）⑦ 四枚在册 insert 站点的分档形状没漂
 * ⑧（T316 新）三枚判「取不到号」的站点，其**上游链条**在盘上有齿（备份模型根本没有主键那一列 /
 * `copy` 与构造清单不含 `id =` / 域模型的号排首位且有默认值），外加 `ScheduleRepository.kt` 的 `:791`
 * 那一枚**带号链在册**的物证
 * ⑨（T316 新）五枚在册站点的「判语 × 落库原文 × 应有枚数」册子与盘上落库口子**逐枚对账并求和**。
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
 *    恰六枚，逐枚对上判语（`ScheduleRepository.kt` 的 `:180` 显式抹号、`:580` 与 `:791` 原样回写、
 *    `:849` 与 `:854` 内核造的补建行、`:676` T304 那一枚；T316 按这棵树现量重钉，改前那批 `:782` / `:840` /
 *    `:845` / `:667` 是 `49faae1` 之前的旧读数，留在台账里当"号对而原文漂"那一族的实物）。
 *    **只数总枚数为什么不够**：六枚这一维对「谁带号、谁抹号」完全无感，五枚在册站点全被换形也照样是六枚。
 * 8. [三枚「取不到号」判语的链条 + 带号链在册的物证]（T316 新）—— **读形状**（`parameterBlock` 按**圆括号**
 *    配平取声明的参数块与构造清单；`functionBody` 按函数名取体）+ **数出现次数**（块内 `val id` / `id = `
 *    各 0 处、四项字段名各恰一处；`currentSemester?.takeIf { it.termCode == termCode }` 全 main 恰一处）+
 *    **抹注释找锚点**（`data/backup/` 那两枚文件的 KDoc 逐字写着「id 留 0 由 Room 自增」这类话，
 *    不抹注释就会把注释里的 `id =` 也数进病灶）+ **逐字节**（写侧抹号那一形作为探测器喂进同一把尺）。
 *    **只逐字节为什么不行**：这一层的判语是「某枚字段**不在**某段清单里」。逐字节钉死整行只钉得住今天这一种
 *    排版（`Semester(` 的参数折成一行就误红），而给 `BackupSemester` 加一枚 `val id: Long = 0L,` 是**新增**
 *    一行 —— 任何"原样钉死旧行"的尺不会为它红，只有「块内枚数」这一把会。
 *    **只数全文件枚数也不行**：`data/backup/BackupModels.kt` 全文有别的模型带 `id`，全文件枚数既可能虚高
 *    也可能被抵消；尺必须切进那一枚声明自己的参数块 —— 这正是"块"这一维存在的理由。
 * 9. [五枚在册站点的判语册子与盘上对账]（T316 新）—— **数出现次数**（逐枚 handoff 的枚数 == 册子给的期望，
 *    且**册子期望之和 == 全文件 `semesterDao.insert(` 枚数**）+ **读形状**（第七枚落库口子的探测器：
 *    往尺里再喂一手 insert，求和格必须为它红）。
 *    **只逐枚数为什么不够**：逐枚格对「册子里少登一枚站点」完全无感 —— 站点还在盘上、被登那几枚的期望也照旧
 *    命中，红只可能来自**求和**那一格（5 != 盘上 6）。这就是 arm N 朝窄的落点：摘掉这一族析取的其中一枚
 *    参照物，只有求和格接得住（①–⑧ 一枚都不红 —— 那是设计边界不是缝隙：①–⑥ 量的是 T304 那一枚站点与内核
 *    本体，⑦ 量的是各枚原文的枚数，它们都不读这本册子）。
 *
 * 报错消息一律不带行号（行号随注释与 KDoc 漂；本仓这一族的规矩）。期望值是**盘面里自己写的字面量**，
 * 不吃被验对象自己的运行时输出 —— 逐字节锚点取的是磁盘上的源码文本本身，与被验代码不是同一条求值路径。
 *
 * ## 本守卫**不证明**运行时行为
 *
 * Room 拿哨兵到底另不另号、REPLACE 到底命不命中同一枚 rowid —— `:app` 的 JVM 测试面没有 Robolectric、
 * 也没有能在 JVM 上跑 Room 的缝，那一档归编排者装机（零 adb、零设备）。判据逐格取值与假库落库后果由
 * `SemesterRowPolicyTest` 的④段钉；本文件钉的是「写侧那一手真的吃类体属性、类体属性真的取了真源哨兵」。
 *
 * ## T316 补记的两条覆盖面边界（如实写明，不当成已验）
 *
 * 1. **假库今天不再替生产者抹号**：`SemesterRowPolicyTest` 的 `FakeSemesterTable.insertHanded` 按
 *    `handed.id == FRESH_ROW_PRIMARY_KEY` 分两档（哨兵 ⇒ 另号；非哨兵 ⇒ 命中同 id 的 rowid、整行顶掉、
 *    行数不涨），落点吃的是 `handedRow(step)`（默认 `it.rowToWrite`，与写侧同一枚）—— 同文件那格
 *    `同一份计划两趟落下的是两枚不同盘面` 就是这一判的实物对照（今天那趟 12L / 改前那趟仍是 9L 与 11L）。
 *    **但它只覆盖 step 那一形**：`saveSemester` / `replaceSemesterCoursesInTx` / 恢复那两枚 insert
 *    都不走 `SemesterRenameSaveStep`，假库一格也盖不到它们 ⇒ 那四枚站点的"测试绿"从来不是假库给的，
 *    而是**根本没有 JVM 落库靶面**（第⓪问的裁定全部靠链条 + 本文件的静态尺，不靠运行时绿）。
 * 2. **本守卫的尺只读 `app/src/main/java`**（与 `app/build.gradle.kts` 的 `guardReadWorkingTreeFiles` 同面）：
 *    `app/src/androidTest` 里那两枚 `repository.saveSemester(` 种子（`WidgetRefreshFreshnessTest` /
 *    `WidgetRenderContractTest`，两枚都按名构造 `Semester(termCode…, totalWeeks…)` ⇒ 号是默认 0L）
 *    在这一副尺的覆盖面**之外** —— 它们哪天改成带号回写，本守卫一格也不会红。那不是缝隙而是装机面的账：
 *    零 adb、零设备，instrumentation 跑不了，判语「main 零调用点」钉的是可达性，不是"全仓没人这么调"。
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

        // `ScheduleRepository.kt` 的 `:180` —— 显式抹号那一枚（本卡的对照面，一字未动）
        val switch = functionBody(repo, SWITCH_ANCHOR)
        assertEquals(
            "`switchSemester` 体内「$SWITCH_HANDOFF」恰一处（实到 " + occurrences(switch, SWITCH_HANDOFF) +
                " 处）：带 id 的行经 `copy(id = 0L)` 再插 ⇒ 自增把它顶成 id 最大 = 当前学期。" +
                "这一枚是本卡给 `applySemesterRowSteps` 找的同形先例，不许被顺手改掉或被删" +
                "（`SemesterRowPolicy.kt` 里 `:78` 与 `:114-117` 那两把尺就压在这句话上）",
            1,
            occurrences(switch, SWITCH_HANDOFF),
        )

        // `:580` 与 `:791` —— 同一形（原样回写），判语不同：前者今天恒收新鲜号，后者在册待收
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
                "若哪天有 main 调用点把库里读出的行递进来，这一枚就与 `applySemesterRowSteps` 改前同病",
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
                occurrences(replaceInTx, PLAIN_HANDOFF) + " 处）：这一枚判语是**带号回写、T316 裁过仍未收** —— " +
                "它的 semester 出自 `buildFallbackSemester` 那一手" +
                "`currentSemester?.takeIf { it.termCode == termCode }`，重导当前学期那一档" +
                "**直接交回库里读出的那一枚带真号的行**，与 `applySemesterRowSteps` 改前同一枚病（链条的齿在" +
                "第 ⑧ 层）。T316 不收它**不是**因为「不在 step 载体上所以只能留着」，而是白名单之外没有落点：" +
                "甲（把这跳换成内核排出的 `DeleteRowsForTermCode` + `WriteSemesterRow` 两枚步骤、走 " +
                "`applySemesterRowSteps`）要同批改 `data/local/SemesterRowShapeGuardTest` 第 ③ 层" +
                "「该体内 `deleteByTermCode(` / `semesterDao.insert(` 各恰一处」与 `data/import/" +
                "BuaaSemesterStartWiringGuardTest` 第 ⑤ 层那句体内原文；乙（写侧自己 `copy` 抹号）要同改后者" +
                "那一枚逐字节锚点，而它同时是本仓红线否掉的「调用点记得调」那一形。两臂自证里 W 臂量的就是" +
                "这一格（见回执）—— 那一形下去本层与 ⑦⑨ 同时红，而白名单外的守卫也一起红，所以改不动",
            1,
            occurrences(replaceInTx, PLAIN_HANDOFF),
        )

        // `:849` 与 `:854` —— 内核造的补建行，构造里就没有 id（链条本体钉在第 ⑧ 层）
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

    // ─────────────── ⑧ 三枚「取不到号」判语的链条 + 带号链在册的物证（T316 新） ───────────────

    /**
     * ⑦ 层钉的是「落库那一手的原文没漂」，可原文背后那句判语（这枚站点**拿不到**非零号）本来零尺：
     * 判语靠的是链条 —— 备份模型根本没有主键那一列、`copy` 与构造清单不含 `id =`、域模型的号排首位且有默认值。
     * 这一层把这些链条一环一枚钉成有齿的尺；顺带把 `replaceSemesterCoursesInTx` 那一枚**反例**（带号链在盘上、
     * 而写侧今天没有替上游抹号）也钉住 —— 它红了有两种可能，两种都要重钉（见报错消息）。
     */
    @Test
    fun `恢复那两枚站点的号在链条上就取不到 备份模型根本没有主键那一列`() {
        val models = blankCommentsKeepingLiterals(source(BACKUP_MODELS))

        // ⑧-1 读形状（圆括号配平取参数块）+ 数出现次数：备份学期件里就没有 id 这一列
        val backupParams = parameterBlock(models, BACKUP_ROW_DECL)
        assertEquals(
            "`BackupSemester` 的参数块里「$ID_FIELD_NEEDLE」必须 0 处（实到 " +
                occurrences(backupParams, ID_FIELD_NEEDLE) + " 处）：恢复那两枚 insert 站点的号就是从这里来的 —— " +
                "备份件**根本没有主键这一列**，`toDomain()` 也就无从递回一枚号。它涨数（有人给备份格式加 id）" +
                "⇒ ⑦ 层那句「新鲜行」判语当场作废，`decision.semester.toEntity()` 就与 `replaceSemesterCoursesInTx` " +
                "同一枚病，要按带号回写重裁",
            0,
            occurrences(backupParams, ID_FIELD_NEEDLE),
        )
        assertEquals(
            "同一块里「val 」恰好四处（termCode / termName / startDate / totalWeeks；实到 " +
                occurrences(backupParams, "val ") + " 处）：这一格给上一枚补" +
                "「谁涨的」那一维 —— 只数「$ID_FIELD_NEEDLE」知道有人加了主键，数枚数知道整块换了形状",
            4,
            occurrences(backupParams, "val "),
        )

        // ⑧-2 读形状 + 数出现次数：`toDomain()` 的构造清单不含 `id =`（四枚字段名逐枚点）
        val toDomainArgs = parameterBlock(models, BACKUP_TO_DOMAIN)
        assertNoIdAndEveryField(toDomainArgs, "BackupSemester.toDomain()", models)

        // ⑧-3 内核那一环：`backupRow.copy(` 只改日期与周数，清单不含 id ⇒ 号跟着原行（而原行是 0L）
        val restorePolicy = blankCommentsKeepingLiterals(source(RESTORE_POLICY))
        val copiedArgs = parameterBlock(restorePolicy, BACKUP_COPY_SITE)
        assertEquals(
            "`backupRow.copy(` 的参数块里「id = 」必须 0 处（实到 " + occurrences(copiedArgs, "id = ") +
                " 处）：`InsertFromBackup.semester` 的号**只能是** backupRow 那一枚的号 —— 而 backupRow 出自上面" +
                "那两格（备份件无 id 列 ⇒ 默认 0L）。有人往 copy 清单里补一枚 `id =` 就是把 ⑦ 层的判语换成猜的",
            0,
            occurrences(copiedArgs, "id = "),
        )
        for (needle in listOf("startDate = ", "totalWeeks = ")) {
            assertEquals(
                "同一块里「$needle」恰好一处（实到 " + occurrences(copiedArgs, needle) +
                    " 处）：这一族 copy 只许归一开学日与总周数两项（T204 的口径），第三项就是顺手改内容",
                1,
                occurrences(copiedArgs, needle),
            )
        }

        // ⑧-4 补建那一环：`Rebuild.toSemester()` 按名构造四项，同样不含 id
        val rebuiltArgs = parameterBlock(restorePolicy, REBUILD_TO_SEMESTER)
        assertNoIdAndEveryField(rebuiltArgs, "Rebuild.toSemester()", restorePolicy)

        // ⑧-5 域模型那一环：号有默认值、排首位 ⇒ 按名构造不写 `id =` 就取不到号（:580 那两枚种子的同一依据）
        val domain = blankCommentsKeepingLiterals(source(DOMAIN_SEMESTER))
        val domainParams = parameterBlock(domain, DOMAIN_SEMESTER_DECL)
        assertEquals(
            "`domain/model/Semester.kt` 的参数块里「$DOMAIN_ID_DEFAULT」恰好一处（实到 " +
                occurrences(domainParams, DOMAIN_ID_DEFAULT) + " 处）：这是「按名构造的四枚 `Semester(` 都拿不到号」" +
                "那一判的唯一依据。与第 ④ 层在 `SemesterEntity.kt` 里数的同名声明是**两枚不同的类**" +
                "（各读各的文件，没有第二把尺量同一件事）",
            1,
            occurrences(domainParams, DOMAIN_ID_DEFAULT),
        )
        assertTrue(
            "那一枚默认值必须排在 `val termCode: String,` **之前**（实到 id 位置 " +
                domainParams.indexOf(DOMAIN_ID_DEFAULT) + "、termCode 位置 " +
                domainParams.indexOf("val termCode: String,") + "）：号挪到末尾后，" +
                "「按名构造就不带号」这一判要重新数一遍每一枚构造点",
            domainParams.indexOf(DOMAIN_ID_DEFAULT) in 0 until domainParams.indexOf("val termCode: String,"),
        )

        // ⑧-6 探测器不空转：把「清单里真带 id」那一形喂进同一把尺，`id = ` 必须命中
        assertEquals(
            "探测器格：`Semester(id = 9L, termCode = termCode, …)` 喂进同一把尺，「id = 」必须命中恰一处（实到 " +
                occurrences(
                    blankCommentsKeepingLiterals("Semester(id = 9L, termCode = termCode, termName = t, startDate = s, totalWeeks = 20)"),
                    "id = ",
                ) + " 处）：0 处就说明上面那几枚「0 处」是锚点拼出来的假绿",
            1,
            occurrences(
                blankCommentsKeepingLiterals("Semester(id = 9L, termCode = termCode, termName = t, startDate = s, totalWeeks = 20)"),
                "id = ",
            ),
        )
    }

    @Test
    fun `整学期替换那一枚站点确实带号回写 而写侧今天没有替上游抹号`() {
        // ⑧-7 带号的唯一来源（逐字节 + 数出现次数）：VM 的 `buildFallbackSemester` 把库里读出的那枚行原样带回
        assertEquals(
            "全 app/src/main 里「$FALLBACK_TAKE_IF」恰好一处（实到 " + mainOccurrenceCount(FALLBACK_TAKE_IF) +
                " 处）：`ui/ScheduleViewModel.kt` 的 `buildFallbackSemester` 只有这一手会" +
                "「termCode 相同时把 `repository.getCurrentSemester()` 读出的那一枚行**整枚**交回去」—— " +
                "它就是 `replaceSemesterCoursesInTx` 那枚 insert 拿到真号的唯一通道。0 处 = 通道被换形（判语要重裁），" +
                "2 处 = 第二枚带号通道长了出来",
            1,
            mainOccurrenceCount(FALLBACK_TAKE_IF),
        )

        // ⑧-8 可达性账（数出现次数）：那条链上的入口枚数就是这三枚
        assertEquals(
            "全 app/src/main 里「$IMPORT_CALL_PLAIN」恰好两处（ICS/文本预览确认那一跳 + 教务静默刷新那一跳；实到 " +
                mainOccurrenceCount(IMPORT_CALL_PLAIN) + " 处）：这一族的入口账，第四枚入口 = 又长出一条写学期行的路",
            2,
            mainOccurrenceCount(IMPORT_CALL_PLAIN),
        )
        assertEquals(
            "全 app/src/main 里「$IMPORT_CALL_HISTORY」恰好一处（`confirmPendingImport`；实到 " +
                mainOccurrenceCount(IMPORT_CALL_HISTORY) + " 处）：它与上一枚同落 `replaceSemesterCoursesInTx`",
            1,
            mainOccurrenceCount(IMPORT_CALL_HISTORY),
        )

        // ⑧-9 病灶的物证（读形状取体 + 数出现次数）：写侧体内没有 copy ⇒ 带号那一手照原样落库
        val repo = blankCommentsKeepingLiterals(source(REPOSITORY))
        val body = functionBody(repo, REPLACE_IN_TX_ANCHOR)
        assertEquals(
            "`replaceSemesterCoursesInTx` 体内「copy(id」必须 0 处（实到 " + occurrences(body, "copy(id") +
                " 处）：这一格钉的是**今天盘面**——写侧没有替上游抹号，所以 ⑦ 层那句「带号回写」是真的在册。" +
                "它涨数有两种红法，都必须重钉而不是顺手放行：① 有人按「调用点自己记得调」那一形在这里 `copy` " +
                "抹号（本仓红线否掉的第三条路，且 `BuaaSemesterStartWiringGuardTest` 第 ⑤ 层的逐字节锚点同批红）；" +
                "② 有人把这一跳换成内核排出的步骤（甲档修法，`SemesterRowShapeGuardTest` 第 ③ 层的" +
                "「体内 deleteByTermCode / semesterDao.insert 各一处」同批红）。两形都在白名单里改不动 ⇒ 在册",
            0,
            occurrences(body, "copy(id"),
        )

        // ⑧-10 探测器不空转：写侧抹号那一形喂进同一把尺（证明上一格不是哑火，也证明 ⑦ 层会一起红）
        val wideProbe = blankCommentsKeepingLiterals(WIDE_WRITE_SITE_HANDOFF)
        assertEquals(
            "「写侧抹号那一形」喂同一把尺，「copy(id」必须命中恰一处（实到 " + occurrences(wideProbe, "copy(id") +
                " 处）：上一格那句 0 处才有齿",
            1,
            occurrences(wideProbe, "copy(id"),
        )
        assertEquals(
            "同一形里「$PLAIN_HANDOFF」必须 0 处（实到 " + occurrences(wideProbe, PLAIN_HANDOFF) +
                " 处）：这就是 W 臂那一刀会连带打红的 ⑦ 层格（在册站点的原文换形），本层与它各管一维",
            0,
            occurrences(wideProbe, PLAIN_HANDOFF),
        )
    }

    // ─────────────── ⑨ 判语册子与盘上落库口子逐枚对账（T316 新，arm N 朝窄的那一层） ───────────────

    @Test
    fun `五枚在册站点的判语册子与盘上枚数求和对得上 摘掉任一枚参照物就红`() {
        val repo = blankCommentsKeepingLiterals(source(REPOSITORY))
        val onDisk = occurrences(repo, WRITE_NEEDLE)
        val roster = listOf(
            SiteRosterRow(
                handoff = SWITCH_HANDOFF,
                expected = 1,
                verdict = "switchSemester 显式抹号（参照件，一字不许动）⇒ LandsAsFreshRow",
            ),
            SiteRosterRow(
                handoff = PLAIN_HANDOFF,
                expected = 2,
                verdict = "saveSemester 新鲜号（main 零调用点，⑦层钉）+ replaceSemesterCoursesInTx **带号在册**（⑧层钉链条）",
            ),
            SiteRosterRow(
                handoff = FRESH_HANDOFF,
                expected = 1,
                verdict = "applySemesterRowSteps 吃类体属性 rowToWrite（T304 已收，①②层钉）",
            ),
            SiteRosterRow(
                handoff = BACKUP_HANDOFF,
                expected = 1,
                verdict = "恢复历史学期 decision.semester ⇒ 备份件无主键那一列（⑧-1…⑧-3 钉）",
            ),
            SiteRosterRow(
                handoff = REBUILD_HANDOFF,
                expected = 1,
                verdict = "恢复补建 decision.toSemester() ⇒ 按名构造四项、号取默认值（⑧-4、⑧-5 钉）",
            ),
        )

        // ⑨-1 逐枚：原文在盘上的枚数 == 册子给的期望（数出现次数）
        val mismatched = roster.filter { occurrences(repo, it.handoff) != it.expected }
        assertEquals(
            "五枚在册站点逐枚对账，偏差枚数必须 0（实到 " + mismatched.size + " 枚）：" +
                mismatched.joinToString("; ") { "「${it.handoff}」册子写 ${it.expected} 处、盘上 " +
                    occurrences(repo, it.handoff) + " 处 —— 判语「${it.verdict}」"} +
                "。红了有两种：换形（原文不再是这一族的那一手）或涨枚（第七枚写口长出来），两种都要重钉",
            emptyList<SiteRosterRow>(),
            mismatched,
        )

        // ⑨-2 求和：册子覆盖的枚数必须等于盘上落库口子总数（这一格就是 arm N 的唯一接手者）
        val covered = roster.sumOf { it.expected }
        assertEquals(
            "册子覆盖的落库口子枚数（$covered）必须等于 `ScheduleRepository.kt` 里「$WRITE_NEEDLE」的盘上枚数" +
                "（$onDisk）：学期行的写口一共就这些，**从册子里摘掉任一枚参照物**（哪怕盘上一字未改）都会让" +
                "这一格红 —— 而 ①–⑧ 层一枚都不会红（它们不读这本册子），这就是本层存在的理由。" +
                "反过来，有人新枚写口却没登册子，红的也是这一格",
            onDisk,
            covered,
        )

        // ⑨-3 册子自身：同一枚原文不许登两遍（否则求和虚高，恰好把「少登一枚」抵掉）
        assertEquals(
            "册子里 handoff 去重后枚数必须与行数相等（实到 " + roster.map { it.handoff }.distinct().size +
                " / " + roster.size + "）：两行写同一枚原文 = 求和被同一枚站点顶两次",
            roster.size,
            roster.map { it.handoff }.distinct().size,
        )

        // ⑨-4 求和格不空转：往尺里喂一枚「第七枚落库口子」，它必须为那枚红
        val seventhSite = "\n        semesterDao.insert(seventh.toEntity())\n"
        val widened = repo + seventhSite
        assertEquals(
            "探测器格：喂进第七枚落库口子后盘上枚数必须是 " + (onDisk + 1) + "（实到 " +
                occurrences(widened, WRITE_NEEDLE) + " 处）—— 册子的覆盖和仍是 $covered，" +
                "所以 ⑨-2 那格在这一形下必红（这一格证明的不是别的，正是「求和」不是套套逻辑）",
            onDisk + 1,
            occurrences(widened, WRITE_NEEDLE),
        )
        assertTrue(
            "同一形下「册子覆盖 != 盘上」必须成立（实到覆盖 " + roster.sumOf { occurrences(widened, it.handoff) } +
                "、盘上 " + occurrences(widened, WRITE_NEEDLE) + "）：⑨-2 那一格对新增站点真的有反应",
            roster.sumOf { occurrences(widened, it.handoff) } != occurrences(widened, WRITE_NEEDLE),
        )
    }

    /**
     * 逐枚核 `Semester(...)` 的构造清单：不含 `id =`，且 termCode / termName / startDate / totalWeeks
     * 四项各恰一处（少一项就是"顺手又改了内容"，多一项就是清单里长了第五枚字段 —— 号也可能藏在里面）。
     */
    private fun assertNoIdAndEveryField(args: String, label: String, wholeFile: String) {
        assertEquals(
            "`$label` 的参数块里「id = 」必须 0 处（实到 " + occurrences(args, "id = ") +
                " 处）：这一族构造清单不含号 ⇒ 交出去的恒是域模型的默认值（第 ⑧-5 层钉那一枚默认值）",
            0,
            occurrences(args, "id = "),
        )
        for (field in listOf("termCode = ", "termName = ", "startDate = ", "totalWeeks = ")) {
            assertEquals(
                "`$label` 的参数块里「$field」恰好一处（实到 " + occurrences(args, field) +
                    " 处）：四项就是这一枚行的全部内容，谁缺席谁多写都要重裁判语",
                1,
                occurrences(args, field),
            )
        }
        assertEquals(
            "`$label` 这一段在整个文件里必须恰好一处（实到 " + occurrences(wholeFile, labelAnchorOf(label)) +
                " 处）：第二份同名构造 = 有人把这一手复制了一遍，判语要按两枚站点各裁一次",
            1,
            occurrences(wholeFile, labelAnchorOf(label)),
        )
    }

    private fun labelAnchorOf(label: String): String = when (label) {
        "BackupSemester.toDomain()" -> BACKUP_TO_DOMAIN
        "Rebuild.toSemester()" -> REBUILD_TO_SEMESTER
        else -> throw AssertionError("未知的构造清单标签：$label")
    }

    // ---------------- 源码核对小工具（与仓内其它静态守卫同一口径）----------------

    /**
     * 取某一枚**声明/构造**的参数列表（锚点自带的那一枚 `(` 起、到与之配平的 `)` 止）：
     * 第 ⑧ 层的判语全是「某枚字段**不在**这一段清单里」，所以尺必须切到块内 —— 锚点找不到、撞出多枚、
     * 配平失败同样抛（静默返回空串就把这一层变成永远绿的空尺，与 [functionBody] 同一规矩）。
     */
    private fun parameterBlock(code: String, anchor: String): String {
        val hits = indexOfAll(code, anchor)
        if (hits.isEmpty()) {
            throw AssertionError("锚点 `$anchor` 在源码里找不到：声明换形或整段被搬走，本守卫要跟着重钉，不许静默绿")
        }
        assertEquals("锚点 `$anchor` 必须恰好一处（实到 ${hits.size} 处）：第二份同名声明 = 两把尺", 1, hits.size)
        val open = hits.first() + anchor.length - 1
        if (code[open] != '(') throw AssertionError("`$anchor` 的收尾不是开花括号：切不出一段清单")
        val close = matchingBracket(code, open, '(', ')') ?: throw AssertionError("圆括号没配平：从 $open 起")
        if (close <= open || close >= code.length) {
            throw AssertionError("取块越界：open=$open、close=$close、文件长 ${code.length}")
        }
        return code.substring(open + 1, close)
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

        /** ⑧层（T316 新）：链条本体的文件、锚点与判语依据 */
        const val BACKUP_MODELS = "com/buaa/schedule/data/backup/BackupModels.kt"
        const val RESTORE_POLICY = "com/buaa/schedule/data/backup/BackupSemesterRestorePolicy.kt"
        const val DOMAIN_SEMESTER = "com/buaa/schedule/domain/model/Semester.kt"
        const val BACKUP_ROW_DECL = "data class BackupSemester("
        const val BACKUP_TO_DOMAIN = "fun BackupSemester.toDomain() = Semester("
        const val BACKUP_COPY_SITE = "backupRow.copy("
        const val REBUILD_TO_SEMESTER = "fun toSemester(): Semester = Semester("
        const val DOMAIN_SEMESTER_DECL = "data class Semester("
        const val DOMAIN_ID_DEFAULT = "val id: Long = 0L,"
        const val ID_FIELD_NEEDLE = "val id"
        const val FALLBACK_TAKE_IF = "currentSemester?.takeIf { it.termCode == termCode }"
        const val IMPORT_CALL_PLAIN = "repository.replaceSemesterCourses("
        const val IMPORT_CALL_HISTORY = "repository.replaceSemesterCoursesWithHistory("

        /** ⑧层探测器：「写侧自己抹号」那一形（看着也说得通的第二实现，本仓红线否掉的那一条） */
        const val WIDE_WRITE_SITE_HANDOFF =
            "semesterDao.insert(semester.copy(id = FRESH_ROW_PRIMARY_KEY).toEntity())"
    }

    /** ⑨层册子的一行：落库原文 + 它在盘上该有的枚数 + 判语（红了念得出是哪一枚站点换形） */
    private data class SiteRosterRow(
        val handoff: String,
        val expected: Int,
        val verdict: String,
    )
}
