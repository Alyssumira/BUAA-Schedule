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
 * 全部由调用点（`data/repository/ScheduleRepository` 的 `replaceCoursesForExistingSemesterInTx`）
 * 当参数递进来。仓库口径同 `data/import/BuaaSemesterStartPolicy.kt`（T180）与
 * `data/repository/CourseDeletionPolicy.kt` 那一族。
 *
 * ## 病（用户看不见的那一笔损坏：换机恢复一次，历史学期的日期与周次整体错位）
 *
 * 备份文件里 `BackupData.semester` 只有**一枚**学期行（主学期），而 `BackupCourse` 每一门课
 * 都带自己的 `semesterCode` —— 课程列表是跨学期的，学期行却不是。恢复时主学期那一路是好的
 * （`restoreBackupDataInTx` 把备份带来的 `startDate` 过一遍 mondayOf 再写回），但
 * `semesterGroups` 里那些 `code != mainCode` 的**历史学期**走的是另一路：库里查不到该 termCode
 * 时，旧代码直接补建一行 `startDate = fallbackStartDate.toString()`、`termName = termCode`、
 * `totalWeeks = 20`，而 `fallbackStartDate` 的实参是 `ui/ScheduleViewModel` 的
 * `restoreFromData` 传下来的 `mostRecentMonday()` —— **恢复那天所在的周一**。
 *
 * 后果沿 `startDate` 这一枚锚点铺开：课次日期是 `startDate.plusWeeks(w-1).plusDays(dow-1)`
 * （`domain/model/Semester` 的 `startLocalDate` 那一段），开学日被顶到恢复那天，
 * 历史学期里每一门课的日期与周次编号一起错位；`totalWeeks = 20` 又是没根据的那一等
 * （#236 那一族：没有依据就报"这学期 20 周"）。全程一句提示都没有。
 *
 * **同机恢复无损** —— 库里已经有那一枚学期行，旧代码的 `if (getByTermCode(...) == null)`
 * 根本不进补建分支。这一档差别正是它至今没被看见的原因。
 *
 * ## 为什么这么分档（三等的次序不是随便排的）
 *
 * 1. **库里已有 ⇒ 一个字节都不动**（[SemesterRowDecision.KeepExistingInDb]）。
 *    这是"同机恢复无损"那一档的形状，也是旧 KDoc 自己写的理由：替换课程时**不改写学期行**，
 *    免得把历史学期顶成"当前学期"（当前学期 = id 最大的那一行，重写会把它的 id 顶到最大）。
 *    库里那一行是这台设备此刻的事实（用户在设置页订正过就用的是订正后的值），
 *    拿备份去盖它反而是在删用户的修改。
 * 2. **库里没有、备份带了这一 termCode ⇒ 用备份带来的日期**（[SemesterRowDecision.InsertFromBackup]）。
 *    换机 / 卸载重装正是这一档：日期有出处，只是过去没人把它带过来。`startDate` 过
 *    [startLocalDate]（parse 失败即 null，同时完成归一到周一），`totalWeeks` 过
 *    [CourseConstraints.normalizeTotalWeeks] 限幅 —— 备份是用户可编辑、可被构造的输入，
 *    与主学期那一路同一口径。
 * 3. **两边都没有 ⇒ 只能补建，且必须说实话**（[SemesterRowDecision.Rebuild]）。
 *    开学日只能由调用点递进来的兜底日期供给（内核自己一枚时钟都不读）；
 *    周数**不再静默造 20**：改取「这批课程里出现过的最大周次」这一枚**有出处**的下界
 *    （课程能上到第 16 周，这学期就至少有 16 周），连周次证据都没有时给 1 并如实标
 *    [RebuiltWeeksBasis.NO_BASIS]。两种来历都要跟着结论走，一路报到恢复文案里。
 *
 * 第 2 等排在第 3 等之前、且**不拿兜底日期去"抢救"坏日期**：备份里那枚串 parse 不出来
 * 就是没出处（[RebuiltStartOrigin.BACKUP_DATE_UNREADABLE]），把它折算成任何一个日期都等于
 * 重新犯本卡的病 —— 与 T180 那条「认不出来就不动」同口径。区别只在：这一路手里**有**课程要落库，
 * 不建行就等于让那一组课挂在一枚不存在的学期上（周课表画不出、学期切换器里也看不见），
 * 所以补建 + 说实话，而不是拒收。
 *
 * ## 证据档位（如实写明）
 *
 * JVM 表驱动单测 `BackupSemesterRestorePolicyTest`（卡面要求的五格全在，两侧各有格子）+
 * 源码形状守卫 `BackupSemesterRestoreWiringGuardTest`（钉住两枚病灶字面量在 `app/src/main`
 * 里 0 处、内核零 android 零时钟、两枚写侧真的填了列表）。`:app` 的 JVM 测试面只有 `junit`，
 * **没有 Robolectric、没有能在 JVM 上跑的 Room 缝**，所以"真的恢复一份多学期备份再看日期"
 * 那一档**没做**（红线零设备），归编排者装机 —— 见回执的未验到清单。
 */
sealed interface SemesterRowDecision {

    /**
     * 库里已经有这一枚学期行 ⇒ 沿用，不写。
     *
     * 同机恢复无损的那一档，也是「不把历史学期顶成当前学期」的那一档：
     * [existing] 只是把"没人动它"这件事说清楚，调用方拿到这一等**不许**再 insert。
     */
    data class KeepExistingInDb(val existing: Semester) : SemesterRowDecision

    /**
     * 库里没有、备份带了这一 termCode 的学期行且它的日期认得出来 ⇒ 用备份带来的日期建行。
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
 * 判据本体：库里 / 备份里 / 兜底日期三样输入，给出这一枚学期行的处置结论。
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
 */
internal fun decideSemesterRowForRestore(
    termCode: String,
    backupRow: Semester?,
    existingRow: Semester?,
    fallbackStartDate: LocalDate,
    observedMaxWeek: Int?,
): SemesterRowDecision {
    // ① 库里已有 ⇒ 不写。次序不能反过来：拿备份盖库里 = 把用户在设置页订正过的开学日冲掉。
    if (existingRow != null) return SemesterRowDecision.KeepExistingInDb(existingRow)

    // ② 备份带了这一 termCode 且日期认得出来 ⇒ 用备份的日期。
    //    startLocalDate 失败给 null（并顺手归一到周一），这是"认不出来"的唯一判据，不再另起一把尺。
    val backupStart = backupRow?.startLocalDate
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
 */
internal fun rebuiltSemestersNote(rebuilt: List<SemesterRowDecision.Rebuild>): String? {
    if (rebuilt.isEmpty()) return null
    val listed = rebuilt.take(MAX_LISTED_REBUILT_TERMS).joinToString("、") { it.termCode }
    val rest = if (rebuilt.size > MAX_LISTED_REBUILT_TERMS) " 等 ${rebuilt.size} 枚" else ""
    return "另有 ${rebuilt.size} 枚学期行是补建出来的（$listed$rest）：" +
        "它们的开学日不在备份里、是按恢复那天推算的，请到设置页核对"
}
