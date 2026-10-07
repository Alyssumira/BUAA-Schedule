package com.buaa.schedule.data.backup

import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.mondayOfWeekAnchor
import com.buaa.schedule.domain.model.startLocalDate
import com.buaa.schedule.domain.schedule.CourseConstraints
import java.time.LocalDate

/**
 * 「恢复时这一枚学期行到底该怎么办」那一判的内核（T204 / 原 T186）。
 *
 * 纯判据：零 android import、**零时钟读数**、不碰 Room 也不碰 ViewModel ——
 * 「库里有没有这一学期」「备份里有没有带这一 termCode 的学期行」「只能猜时拿哪一天当兜底」
 * 「这一枚是不是这次恢复的标的（[decideSemesterRowForRestore] 的 `mainSemester`）」
 * 全部由调用点（`data/repository/ScheduleRepository` 的
 * `replaceCoursesForExistingSemesterInTx` 与 `restoreBackupDataInTx` 里主学期那一路）当参数递进来。
 * 仓库口径同 `data/import/BuaaSemesterStartPolicy.kt`（T180）与
 * `data/repository/CourseDeletionPolicy.kt` 那一族。
 *
 * ## 病（用户看不见的那一笔损坏：恢复一次，整学期日期与周次错位 —— 历史学期与主学期各一路）
 *
 * 备份文件里 `BackupData.semester` 只有**一枚**学期行（主学期），而 `BackupCourse` 每一门课
 * 都带自己的 `semesterCode` —— 课程列表是跨学期的，学期行却不是。T204 那一轮管住了
 * `semesterGroups` 里 `code != mainCode` 的**历史学期**；**主学期那一路当时没并进这一判**：
 * `restoreBackupDataInTx` 里的 `safeSemester` 自己 parse 备份带来的 `startDate`，
 * 认不出来时就地 `?: fallbackStartDate`（实参是恢复那天所在的周一）再无条件 delete+insert 落库。
 * 后果比历史学期那一档更重：整学期的日期与周次编号一起错位，而**补建账里没有它** ⇒
 * 恢复文案此时念"没有任何学期行是补建出来的"，这一档是谎话（T223 收的就是这一格）。
 * 可达性不是理论：`domain/model/Semester.kt` 的 `startLocalDate` KDoc 自己写着
 * "数据库中可能存在用户输入的非法格式（旧版本未校验）"（本仓该文件第 25 行，
 * 归一到周一的理由在第 28–31 行）—— 库里与备份里都留得下坏串，而备份是用户能手改的输入。
 *
 * 历史学期那一档的后果沿 `startDate` 这一枚锚点铺开：课次日期是
 * `startDate.plusWeeks(w-1).plusDays(dow-1)`（`domain/model/Semester` 的 `startLocalDate` 那一段），
 * 开学日被顶到恢复那天，学期里每一门课的日期与周次编号一起错位；`totalWeeks = 20` 又是没根据的那一等
 * （#236 那一族：没有依据就报"这学期 20 周"）。全程一句提示都没有。
 *
 * **同机恢复无损**（历史学期那一侧）—— 库里已经有那一枚学期行，旧代码的 `if (getByTermCode(...) == null)`
 * 根本不进补建分支。这一档差别正是它至今没被看见的原因；主学期那一路没有这一层保护
 * （它无条件写），所以同机恢复也会把当前学期的好日期顶成恢复那天。
 *
 * ## 为什么这么分档（三等的次序不是随便排的）
 *
 * 1. **库里已有 ⇒ 一个字节都不动**（[SemesterRowDecision.KeepExistingInDb]）。
 *    这是"同机恢复无损"那一档的形状，也是旧 KDoc 自己写的理由：替换课程时**不改写学期行**，
 *    免得把历史学期顶成"当前学期"（当前学期 = id 最大的那一行，重写会把它的 id 顶到最大）。
 *    库里那一行是这台设备此刻的事实（用户在设置页订正过就用的是订正后的值），
 *    拿备份去盖它反而是在删用户的修改。
 *    **唯一例外**（T223 的两问结论之第一问）：这一枚是恢复的标的（主学期）**且**备份带来的日期
 *    认得出来 ⇒ 让备份顶掉它，因为"顶掉它"正是用户点恢复要的事；日期认不出来时这一等照旧生效。
 * 2. **备份带了这一 termCode 且日期认得出来 ⇒ 用备份带来的日期**（[SemesterRowDecision.InsertFromBackup]）。
 *    换机 / 卸载重装正是这一档：日期有出处，只是过去没人把它带过来。`startDate` 过
 *    [startLocalDate]（parse 失败即 null，同时完成归一到周一），`totalWeeks` 过
 *    [CourseConstraints.normalizeTotalWeeks] 限幅 —— 备份是用户可编辑、可被构造的输入，
 *    与主学期那一路同一口径（T223 之后主学期那一路就是**走的这一等**，不再是仓库里自己 parse）。
 * 3. **两边都没有 ⇒ 只能补建，且必须说实话**（[SemesterRowDecision.Rebuild]）。
 *    开学日只能由调用点递进来的兜底日期供给（内核自己一枚时钟都不读）；
 *    周数**不再静默造 20**：改取「这批课程里出现过的最大周次」这一枚**有出处**的下界
 *    （课程能上到第 16 周，这学期就至少有 16 周），连周次证据都没有时给 1 并如实标
 *    [RebuiltWeeksBasis.NO_BASIS]。两种来历都要跟着结论走，一路报到恢复文案里。
 *    主学期一样进这一等、一样进补建账（T223）：库里没有那一行而备份的开学日认不出来时，
 *    旧代码写的是"就地兜底一个日历值 + 账本干净"，那是本卡唯一一处会让文案说谎的形状。
 *
 * 第 2 等排在第 3 等之前、且**不拿兜底日期去"抢救"坏日期**：备份里那枚串 parse 不出来
 * 就是没出处（[RebuiltStartOrigin.BACKUP_DATE_UNREADABLE]），把它折算成任何一个日期都等于
 * 重新犯本卡的病 —— 与 T180 那条「认不出来就不动」同口径。区别只在：这一路手里**有**课程要落库，
 * 不建行就等于让那一组课挂在一枚不存在的学期上（周课表画不出、学期切换器里也看不见），
 * 所以补建 + 说实话，而不是拒收。
 *
 * ## 两问的结论：主学期与历史学期（T223，这一节就是结论本体，不只长在代码注释里）
 *
 * 卡面问两件事，两侧必须同答，或把不同答的理由写成结论。这里的裁定是**一问同答、一问不同答**，
 * 分岔只允许发生在「备份的日期认得出来」那一格里（`mainSemester` 参数），判据仍然是同一枚、
 * 结论仍然只有三等（**没有第四等**）：
 *
 * - **第二问「认不出来就不动，还是补建并留痕」：同一个答案。**
 *   备份带来的 `startDate` parse 不出来（[Semester.startLocalDate] 给 null）时，两侧都退到
 *   第一问之外的同一套次序：库里有那一行 ⇒ [SemesterRowDecision.KeepExistingInDb]（一个字节都不动，
 *   一个猜的日期都不写），库里没有 ⇒ [SemesterRowDecision.Rebuild] 并标
 *   [RebuiltStartOrigin.BACKUP_DATE_UNREADABLE] / [RebuiltStartOrigin.NOT_IN_BACKUP]、进补建账、
 *   被 [rebuiltSemestersNote] 念出来。主学期在这一问上**没有任何特权**：旧代码的
 *   「就地兜底一个日历值」从来不是"主学期该有不同的答复"，它只是没走这一判。
 * - **第一问「库里那一行 vs 备份那一枚，谁赢」：不同答，理由是两条都能落到盘上的事实。**
 *   历史学期那一行只是给一组课提供日期锚点，库里的值是这台设备此刻的事实 ⇒ 库里赢。
 *   主学期那一行是**这次恢复的标的**：① 备份的单字段 `semester` 按 `BackupModels` 自己的 KDoc
 *   就写着它是"恢复时主学期配置（节次、周数）的出处"，顶掉它才是恢复；② 当前学期是
 *   `data/local/SemesterDao` 的 `getCurrent()` / `observeCurrent()` 那两句 `ORDER BY id DESC LIMIT 1`
 *   —— 主学期行必须**最后写**（delete+insert 把 id 顶到最大）才能让用户落在恢复出来的那一学期上，
 *   不写它就是把首页停在另一个学期上，看起来"恢复没起作用"（`restoreBackupDataInTx` 的
 *   幂等性 KDoc 早写着这条，本卡只是把它和"日期认不出来"分开处理）。
 *   ⇒ 主学期只在**备份的日期认得出来**时让备份顶掉库里那一行；日期认不出来时立刻退回与历史学期
 *   同一答复（库里赢 / 补建留痕），因为那时"顶掉它"顶去的是一枚猜的日期，比留坏值更危险 ——
 *   这正是旧代码犯过的错，也是本卡的病灶。
 *
 * 被否掉的两种写法（留档，免得下一轮再试）：
 * - 把主学期也改成「库里赢」= 恢复不再恢复学期名与总周数，且当前学期指针不动，用户落在旧学期上
 *   看不到恢复结果；这是功能倒退，不是并尺。
 * - 让「库里的那一行」在日期认不出来时也被 delete+insert 顶成当前学期 = 内容一个字节没变但 id 变了，
 *   第一问的答案就此掺进"为了跳页而重写行"这一等；本卡宁可少跳一次页，也不给「不动」这条加例外。
 *
 * ## 证据档位（如实写明）
 *
 * JVM 表驱动单测 `BackupSemesterRestorePolicyTest`（卡面要求的五格全在，且**两侧各有格子**：
 * 同一枚判据按 `mainSemester = true / false` 各跑一遍，每格另给"落库的那一行 + 补建账里有没有它 +
 * 文案念什么"三问）+ 源码形状守卫 `BackupSemesterRestoreWiringGuardTest`（钉住两枚病灶字面量与
 * T223 那一形「日期 parse 不出来 ⇒ 就地兜底一个日历值」在 `app/src/main` 里各 0 处、
 * 主学期与历史学期两条路都调同一枚判据、内核零 android 零时钟、两枚写侧真的填了列表）。
 * `:app` 的 JVM 测试面只有 `junit`，**没有 Robolectric、没有能在 JVM 上跑的 Room 缝**，
 * 所以"真的恢复一份坏日期的备份再看库里那一行长什么样"那一档**没做**（红线零设备），
 * 归编排者装机 —— 见回执的未验到清单。
 */
sealed interface SemesterRowDecision {

    /**
     * 库里已经有这一枚学期行 ⇒ 沿用，不写。
     *
     * 同机恢复无损的那一档，也是「不把历史学期顶成当前学期」的那一档：
     * [existing] 只是把"没人动它"这件事说清楚，调用方拿到这一等**不许**再 insert
     * （主学期那一路拿到它时同样不许 —— T223：坏日期档下库里那一行原样留着，只换课程）。
     *
     * 历史学期恒进这一等；主学期只在**备份带来的日期认不出来**时才进（[decideSemesterRowForRestore]
     * 的 `mainSemester`，两问的结论见文件 KDoc 那一节）。
     */
    data class KeepExistingInDb(val existing: Semester) : SemesterRowDecision

    /**
     * 库里没有、或这一枚是主学期且备份的日期认得出来 ⇒ 用备份带来的日期建行。
     *
     * [semester] 已经把 `startDate` 归一到周一、`totalWeeks` 限过幅；这一等写进库的每一枚
     * 字段都有出处，因此**不进**恢复结果的补建账。
     */
    data class InsertFromBackup(val semester: Semester) : SemesterRowDecision

    /**
     * 库里没有、备份也没带（或带了但日期认不出来）⇒ 只能补建：开学日按调用点递进来的兜底日期猜，
     * **这一等必须留痕**（恢复结果的补建账 + 文案，见 [RebuiltStartOrigin] / [RebuiltWeeksBasis]）。
     *
     * [startDate] 恒为周一（兜底日期再过一遍 [mondayOfWeekAnchor]，在周一上是幂等的）；
     * [termName] 退成 [termCode]（没有出处就是没有，不许编一个好看的名字）；
     * [totalWeeks] 的来源由 [weeksBasis] 交代，**不再是硬写的 20**。
     *
     * 主学期同样进这一等（T223）：库里没有那一行、而备份的开学日认不出来时，
     * 补建 + 进账是唯一的老实写法 —— 恢复文案里"另有 N 枚学期行是补建出来的"那一句
     * 从此包含主学期这一档。
     */
    data class Rebuild(
        val termCode: String,
        val termName: String,
        val startDate: String,
        val totalWeeks: Int,
        val startOrigin: RebuiltStartOrigin,
        val weeksBasis: RebuiltWeeksBasis,
    ) : SemesterRowDecision {

        /** 要写库的那一行（id 留 0 由 Room 自增，与仓里其余补建点同一形状） */
        fun toSemester(): Semester = Semester(
            termCode = termCode,
            termName = termName,
            startDate = startDate,
            totalWeeks = totalWeeks,
        )
    }
}

/** 补建那一等里「开学日为什么是这一天」的出处 */
enum class RebuiltStartOrigin {

    /** 备份里根本没有这一 termCode 的学期行（老备份 / 老口令 / 手改过的备份） */
    NOT_IN_BACKUP,

    /** 备份带了这一 termCode 的行，但它的 `startDate` parse 不出来 ⇒ 有行不等于有日期 */
    BACKUP_DATE_UNREADABLE,
}

/** 补建那一等里「总周数是几、凭什么」的出处 */
enum class RebuiltWeeksBasis {

    /** 这批课程里出现过的最大周次：课程上得到第 N 周，这学期就至少有 N 周 */
    COURSE_WEEKS,

    /** 连周次证据都没有（空周次那一档）⇒ 按最小值建，并如实标出来 */
    NO_BASIS,
}

/**
 * 判据本体：库里 / 备份里 / 兜底日期 / 「这一枚是不是这次恢复的标的」四样输入，
 * 给出这一枚学期行的处置结论 —— 结论仍然只有三等（T223 把主学期并进来时**没有**开第四等）。
 *
 * 全仓**唯一一份**这一判。不许在这里读时钟：兜底日期是参数，「恢复那天」归调用点决定，
 * 否则这一判在 JVM 侧就不可断言，而且会顺手把 `mostRecentMonday()` 请回判据里 ——
 * 那正是本卡要消灭的形状。
 *
 * @param termCode 这一组课程的 semesterCode（也是待建行的 termCode）。
 * @param backupRow 备份里与该 termCode 配对上的学期行（[indexBackupSemesterRows] 的结果），没有则 null。
 * @param existingRow 库里该 termCode 的学期行（`semesterDao.getByTermCode(...)`），没有则 null。
 * @param fallbackStartDate 只能补建时使用的兜底日期（今天所在周一由调用点算好后递进来）。
 *   内核仍然会过一遍 [mondayOfWeekAnchor]：参数不是周一时（调用点写法变了）不静默收下偏移。
 * @param observedMaxWeek 该组课程里出现过的最大周次，没有任何周次证据时传 null。
 *   只用于补建那一等给 `totalWeeks` 一枚有出处的下界；库里 / 备份里有行时它不参与判定。
 * @param mainSemester 这一枚是不是**这次恢复的标的**：主学期那一路传 true、历史学期传 false
 *   （没有默认值，每一枚调用点都必须自己表态）。它只买通**第一问**（库里那一行 vs 备份那一枚谁赢），
 *   而且只在"备份带来的日期认得出来"那一格生效：那时主学期让备份顶掉库里那一行 ——
 *   `BackupModels` 的 KDoc 自己写着单字段那一枚是"恢复时主学期配置（节次、周数）的出处"，
 *   而当前学期是 `data/local/SemesterDao.getCurrent()` 那一句 `ORDER BY id DESC LIMIT 1`，
 *   主学期行必须最后写才落得下来。日期认不出来时它一个字都不改判 ——
 *   **第二问（认不出来就不动 / 补建并留痕）两侧同答**：库里有就整行不动，库里没有才补建并留痕。
 *   整格裁定与被否掉的两种写法都在文件 KDoc 的「两问的结论」那一节。
 */
internal fun decideSemesterRowForRestore(
    termCode: String,
    backupRow: Semester?,
    existingRow: Semester?,
    fallbackStartDate: LocalDate,
    observedMaxWeek: Int?,
    mainSemester: Boolean,
): SemesterRowDecision {
    // 「认得出来」只有 startLocalDate 这一把尺：parse 失败即 null，并顺手归一到周一。
    // 第一问与第二问读的是同一枚它，别处不许再 parse 一遍（第二把尺 = R7 P2-12 那一族）。
    val backupStart = backupRow?.startLocalDate

    // ① 库里已有 ⇒ 不写。次序不能反过来：拿备份盖库里 = 把用户在设置页订正过的开学日冲掉。
    //    唯一例外是「这一枚是恢复的标的（主学期）**且**备份的日期认得出来」；
    //    日期认不出来时主学期退回与历史学期同一答复 —— 那时"顶掉它"顶下去的是一枚猜的日期。
    if (existingRow != null && (!mainSemester || backupStart == null)) {
        return SemesterRowDecision.KeepExistingInDb(existingRow)
    }

    // ② 备份带了这一 termCode 且日期认得出来 ⇒ 用备份的日期。
    if (backupRow != null && backupStart != null) {
        return SemesterRowDecision.InsertFromBackup(
            backupRow.copy(
                startDate = backupStart.toString(),
                totalWeeks = CourseConstraints.normalizeTotalWeeks(backupRow.totalWeeks),
            ),
        )
    }

    // ③ 只能补建 ⇒ 开学日按兜底日期、周数按课程里见过的最大周次，两样的来历都如实标出来。
    val weeks = observedMaxWeek?.takeIf { it > 0 }
    return SemesterRowDecision.Rebuild(
        termCode = termCode,
        termName = backupRow?.termName?.takeIf { it.isNotBlank() } ?: termCode,
        startDate = mondayOfWeekAnchor(fallbackStartDate).toString(),
        totalWeeks = CourseConstraints.normalizeTotalWeeks(weeks ?: MIN_TOTAL_WEEKS_WITHOUT_BASIS),
        startOrigin = if (backupRow == null) {
            RebuiltStartOrigin.NOT_IN_BACKUP
        } else {
            RebuiltStartOrigin.BACKUP_DATE_UNREADABLE
        },
        weeksBasis = if (weeks != null) RebuiltWeeksBasis.COURSE_WEEKS else RebuiltWeeksBasis.NO_BASIS,
    )
}

/**
 * 备份里的学期行按 termCode 建索引，供恢复时配对。
 *
 * 两件事都在这里，因为它们都是"读侧怎么配对"的口径，必须只有一份：
 * - **回落**：`semesters` 为空（老备份、老口令）时用单字段 `semester` 那一枚。
 *   合并顺序是「列表在前、单字段在后」，所以两样都有而 termCode 冲突时**列表为准**
 *   —— 列表是库里全部学期，信息量严格更大。
 * - **去重**：同一 termCode 出现多枚时**首枚为准**（写侧按 `getAllSemesters()` 的
 *   startDate 倒序取，首枚就是最近的那一学期）。不许"后一枚覆盖前一枚"：那会让
 *   重复行的先后顺序悄悄决定恢复结果，而备份文件是用户能手改的。
 * - `termCode` 空白的行不进索引（配不上任何一组课程，留着只会造出一枚空 termCode 的学期行）。
 *
 * 配对是**按 termCode 精确比**，与 `semesterDao.getByTermCode` 同一口径，不做大小写折叠：
 * 学期代码在两处都出自同一批行，折叠反而会把 `2025-2026-2` 与 `2025-2026-2 ` 这类脏值配到一起。
 */
internal fun indexBackupSemesterRows(rows: List<Semester>, legacy: Semester?): Map<String, Semester> {
    val index = LinkedHashMap<String, Semester>()
    for (row in rows + listOfNotNull(legacy)) {
        if (row.termCode.isBlank()) continue
        index.putIfAbsent(row.termCode, row)
    }
    return index
}

/** 补建那一等里没有任何周次证据时的最小周数（**不是**旧代码那枚静默的 20，见 [RebuiltWeeksBasis.NO_BASIS]） */
private const val MIN_TOTAL_WEEKS_WITHOUT_BASIS = 1

/** 进文案 / 日志的补建行上限：再多也只点这几枚 termCode，其余折算成「等 N 枚」 */
private const val MAX_LISTED_REBUILT_TERMS = 3

/**
 * 补建账的那一句话（措辞唯一来源；恢复文案与取证日志都念它，同一口径同 T180 那族）。
 *
 * 为什么必须由内核产出而不是在 UI 里现拼：这一句是"补建不是备份里的日期"这笔账的**唯一**
 * 出口，UI 里少念一句、或多拼一枚没出处的数字，本卡的修复就退回静默。
 * 空列表返回 null = 这一次恢复没有任何学期行是靠猜建出来的（调用方整段不出现）。
 *
 * ⚠️ 这句"没有任何学期行是补建出来的"从前会**说谎**：主学期那一路不走内核，坏日期被就地
 * 换成恢复那天，而账本里干干净净一枚都没有（T223 收的就是这一档）。所以 [rebuilt] 的账本来源
 * 现在**两侧都算**：`semesterGroups` 里 `code != mainCode` 的历史学期 + 主学期那一路的
 * [SemesterRowDecision.Rebuild]。枚数与点名的 termCode 一视同仁，不因为主学期是"当前学期"
 * 就少念一枚 —— 少念那一枚，恢复文案就又是一句谎话。
 */
internal fun rebuiltSemestersNote(rebuilt: List<SemesterRowDecision.Rebuild>): String? {
    if (rebuilt.isEmpty()) return null
    val listed = rebuilt.take(MAX_LISTED_REBUILT_TERMS).joinToString("、") { it.termCode }
    val rest = if (rebuilt.size > MAX_LISTED_REBUILT_TERMS) " 等 ${rebuilt.size} 枚" else ""
    return "另有 ${rebuilt.size} 枚学期行是补建出来的（$listed$rest）：" +
        "它们的开学日不在备份里、是按恢复那天推算的，请到设置页核对"
}
