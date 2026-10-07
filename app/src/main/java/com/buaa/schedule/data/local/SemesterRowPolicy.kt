package com.buaa.schedule.data.local

import com.buaa.schedule.domain.model.Semester

/**
 * semesters 表「同一枚 termCode 到底有几枚行、取哪一枚、删哪一枚」那一族的判据内核（T249，台账 #460 / #461）。
 *
 * 纯 JVM：零 android / androidx import、零时钟读数、不碰 Room —— 库里的行与「新代码下有没有课」
 * 全部由调用点（`data/repository/ScheduleRepository` 的 `switchSemester` / `saveSemesterWithRename` /
 * `renameSemesterCourses`）当参数递进来。口径同 `data/backup/BackupReminderPairing.kt` 与
 * `data/backup/BackupSemesterRestorePolicy.kt`。
 *
 * ## 病 1（#460）：「每个 termCode 只有一行」只活在注释里，而读它是**无序**的
 *
 * `SemesterEntity` 没有任何 `indices`、`termCode` 只是普通非空列 ⇒ 数据库层面不禁止同 termCode 多行；
 * 而 `SemesterDao.getByTermCode` 改前是 `SELECT * FROM semesters WHERE termCode = :termCode LIMIT 1`
 * —— 全文件三族查询里**只有这一条没有 `ORDER BY`**（另两族是 `ORDER BY id DESC LIMIT 1` 与
 * `ORDER BY startDate DESC`）。一旦库里真的落下两枚同 termCode 的行，`LIMIT 1` 交出哪一枚
 * 由 SQLite 的扫描顺序决定，同一个问题在两次读数里可以给出两个答案。
 *
 * 而 `switchSemester` 改前是「读任一行 + `deleteByTermCode`（删掉**全部**同键行）+ 只插回读到的那一行」
 * ⇒ 用户切一次学期，另一枚行的开学日与总周数就被吃掉了，而那枚行他从没在屏上看过。
 *
 * ## 判据（三条，逐条可测）
 *
 * 1. **termCode 级读数只有确定性一种取法**：[pickSemesterRowForTermCode] 恒交 **id 最大**的那一枚，
 *    与 `SemesterDao.getCurrent()` 的 `ORDER BY id DESC LIMIT 1` 同一口径（「最近写入的那一行 = 当前学期」
 *    是本仓从 v1 就有的语义，读侧不许再拿第二把尺）。内核**自己排序**，不吃查询返回的行序
 *    （`BackupReminderPairing` 同族的「两侧各自排定序后再判」）—— SQL 那一头的形状由守卫钉
 *    （`SemesterRowShapeGuardTest`），两边必须同尺。
 * 2. **「顶成当前学期」这一跳不许连带吃掉兄弟行**：[planSemesterRowBump] 把「删哪几枚」写成数据，
 *    `deleteIds` 恒只含被顶的那一枚的 id，同 termCode 的其它行进 `untouchedIds`。
 *    旧形状那里是 `DELETE FROM semesters WHERE termCode = ?` —— 一枚都不留。
 * 3. **「改学期代码」是一枚事务，不是两枚**：[planSemesterRenameSave] 把 rename 与落新行收成**一份有序步骤**，
 *    调用方必须在同一枚事务里跑完整组（#461：旧形状是 rename 一枚事务、`saveSemester` 另一枚，
 *    中间被杀 ⇒ 旧行已删、新行未插 ⇒ 落回「没设学期」，开学日与总周数永久丢且屏上一句话没有）。
 *    拒绝档（新代码下已有别的课表）交回的是**空步骤**：一个字都不写，所以既不落新行也不动学期。
 *
 * ## 这一判「同 termCode 只有一行」升到哪一级（第 0 问的裁定落在代码上的那半）
 *
 * 库里已经有的重复行（若有）本卡不猜、也不迁移：**写路级收口** —— 每一枚**写内容**的
 * （`saveSemester` / `replaceSemesterCoursesInTx` / 合并事务）都在同一趟里
 * `deleteByTermCode(termCode)` 再插一枚；只有「顶成当前学期」那一跳（不写新内容）按 id 精确删。
 *
 * 表驱动单测 `SemesterRowPolicyTest`（同 termCode 两行交出哪一枚 / 兄弟行不被吃掉 /
 * 拒绝档零步骤 / 中途抛 ⇒ 提交集为空）+ 形状与接线守卫 `SemesterRowShapeGuardTest`。
 * Room 真库那一档**没做**：`:app` 的 JVM 测试面没有 Robolectric、没有能在 JVM 上跑的 Room 缝，
 * 「库里到底有没有重复行」只能由编排者在真机/模拟器上取（卡面 §6.5）。
 */

/**
 * termCode 级读数的唯一取法：**id 最大**的那一枚（= 最近写入的那一行），与
 * `SemesterDao.getCurrent()` / `observeCurrent()` 的 `ORDER BY id DESC LIMIT 1` 同口径。
 *
 * 输入是「库里读到的那批行」，两件事都由内核自己判、都不吃查询那边的顺序与范围：
 * - 先按 [termCode] 收窄（[semesterRowsForTermCode]）—— 别的 termCode 的行不能算数；
 * - 再自己排成确定序 —— 所以 `[a, b]` 与 `[b, a]` 必须给出同一枚答案。
 *   改前那一条 `LIMIT 1`（无 `ORDER BY`）守的就是这一条。
 *
 * `id` 是主键，正常盘面上两行不可能同 id；比较器仍把内容字段排在后面，是为了让
 * 「同一枚输入必得同一枚输出」在全序上成立（防御性，不参与正常判定）。
 */
internal fun pickSemesterRowForTermCode(termCode: String, rows: List<Semester>): Semester? =
    semesterRowsForTermCode(termCode, rows).sortedWith(SEMESTER_ROW_ORDER).firstOrNull()

/**
 * termCode 收窄那一步（唯一一份）：SQL 里 `WHERE termCode = :termCode` 就是它，
 * 内核这一份是「查询条件被人改了也不肯拿别的学期的行凑答案」的那道闸。
 *
 * 比的是**逐字的 termCode**，不做大小写折叠、不 trim —— 与
 * `data/backup/BackupSemesterRestorePolicy.kt` 的 `indexBackupSemesterRows` 同一口径。
 */
internal fun semesterRowsForTermCode(termCode: String, rows: List<Semester>): List<Semester> =
    rows.filter { it.termCode == termCode }

/** 确定序：id 降序在前（这一维就是判据本体），其余字段只在 id 相等时把序补成全序 */
private val SEMESTER_ROW_ORDER: Comparator<Semester> = compareByDescending<Semester> { it.id }
    .thenBy { it.termCode }
    .thenBy { it.termName }
    .thenBy { it.startDate }
    .thenBy { it.totalWeeks }

/**
 * 「切换当前学期」那一跳的写侧计划：把目标行删掉重插，自增把它顶成 id 最大 = 当前学期。
 *
 * @param deleteIds 精确删的那几枚 id —— **恒等于 [target] 一枚**。旧形状在这里删的是
 *   「该 termCode 的全部行」，于是同键的兄弟行连着开学日/总周数一起蒸发（#460）。
 * @param untouchedIds 同 termCode 但本跳一枚都不许碰的其它行 id（升序）。非空 = 库里确实有重复行，
 *   这一档由表驱动单测钉住「它们还在」，而不是被悄悄收拢成一行。
 */
internal sealed interface SemesterRowBumpPlan {

    /** 库里这一 termCode 一枚行都没有 ⇒ `switchSemester` 返回 false，一个字都不写 */
    data object NothingToSwitch : SemesterRowBumpPlan

    data class Bump(
        val target: Semester,
        val deleteIds: List<Long>,
        val untouchedIds: List<Long>,
    ) : SemesterRowBumpPlan
}

/**
 * 只读地把「读到的那批行 + 要切的 termCode」折算成切换计划。
 *
 * 库里这一 termCode 有多枚行时，**顶上去的永远是 [pickSemesterRowForTermCode] 那一枚**（id 最大），
 * 其余一枚不动：切换不改写任何内容，所以它没有权力替用户丢掉他从没看过的那一行。
 */
internal fun planSemesterRowBump(termCode: String, rows: List<Semester>): SemesterRowBumpPlan {
    val target = pickSemesterRowForTermCode(termCode, rows) ?: return SemesterRowBumpPlan.NothingToSwitch
    return SemesterRowBumpPlan.Bump(
        target = target,
        deleteIds = listOf(target.id),
        untouchedIds = semesterRowsForTermCode(termCode, rows)
            .filter { it.id != target.id }
            .map { it.id }
            .sorted(),
    )
}

/**
 * 「改学期代码」这一跳的单步写：每一枚都是库里的一次真实写，
 * 整组必须跑在**同一枚**事务里（顺序就是这里给的顺序）。
 */
internal sealed interface SemesterRenameSaveStep {

    /** 挂在旧代码下的课程整体改挂到新代码（否则 CourseFilter 一过滤，课表在用户眼前"消失"） */
    data class ReassignCourses(val fromCode: String, val toCode: String) : SemesterRenameSaveStep

    /** 写路级 termCode 收口：这一 termCode 已有的行全删，随后插的那一枚成为唯一一行 */
    data class DeleteRowsForTermCode(val termCode: String) : SemesterRenameSaveStep

    /** 落那一枚新行（id 恒 0，由 Room 自增 ⇒ 「最近写入的 = 当前学期」） */
    data class WriteSemesterRow(val semester: Semester) : SemesterRenameSaveStep
}

/**
 * 「给当前课表改代码」的结论（`renameSemesterCourses` 与合并事务**共用这一枚判据**，
 * 免得「新代码下已有别的课表就拒」长出第二把尺）。
 */
internal sealed interface SemesterRenamePlan {

    /** 新代码下已有别的课表 ⇒ 零步骤。两份课表搅成一份这件事不许发生 */
    data object Rejected : SemesterRenamePlan

    data class Apply(val steps: List<SemesterRenameSaveStep>) : SemesterRenamePlan
}

/**
 * 改代码的写侧步骤：先把课程整体改挂过去、再删掉旧学期行。
 *
 * `oldCode == newCode` 时步骤照旧给出（改挂是空操作、删旧行由后面那一枚 `DeleteRowsForTermCode`
 * 同一 termCode 再收一次）—— 这不是疏漏，是 `renameSemesterCourses` 从 v1 起的既有行为，
 * 卡面 §5 要求它的调用方语义一个字不许变，所以这里不另开一档「不需要改代码」。
 */
internal fun planSemesterRename(
    oldCode: String,
    newCode: String,
    hasCoursesUnderNewCode: Boolean,
): SemesterRenamePlan {
    if (hasCoursesUnderNewCode) return SemesterRenamePlan.Rejected
    return SemesterRenamePlan.Apply(
        listOf(
            SemesterRenameSaveStep.ReassignCourses(oldCode, newCode),
            SemesterRenameSaveStep.DeleteRowsForTermCode(oldCode),
        ),
    )
}

/** 合并事务的结论：要么一份有序步骤，要么零步骤（拒绝档）。 */
internal sealed interface SemesterRenameSavePlan {

    /** 新代码下已有别的课表 ⇒ 调用方返回 false 且**不落新行、不动学期**，文案保持那一句 */
    data object Rejected : SemesterRenameSavePlan

    data class Apply(val steps: List<SemesterRenameSaveStep>) : SemesterRenameSavePlan
}

/**
 * 「保存学期设置」这一跳的完整写侧计划（#461 的修法本体）：
 * 改代码需要的步骤 + 落新行的步骤，**一份列表、一枚事务**。
 *
 * - 当前那一枚学期行存在且 termCode 变了 ⇒ 先走 [planSemesterRename]（同一枚判据，第二把尺就此不存在），
 *   再收口新 termCode、插新行；
 * - termCode 没变（或库里压根没有学期行）⇒ 只有收口 + 插新行两枚步骤，与 `saveSemester` 一字不差；
 * - 拒绝档 ⇒ [SemesterRenameSavePlan.Rejected]，步骤一枚都没有。
 *
 * @param hasCoursesUnderNewCode 由调用点在事务**内**去问 `courseDao.getBySemester(newCode)`；
 *   内核不碰库，也不许由调用点在事务外先问一遍（那又是一枚死亡窗口）。
 */
internal fun planSemesterRenameSave(
    currentRow: Semester?,
    target: Semester,
    hasCoursesUnderNewCode: Boolean,
): SemesterRenameSavePlan {
    val saveSteps = listOf(
        SemesterRenameSaveStep.DeleteRowsForTermCode(target.termCode),
        SemesterRenameSaveStep.WriteSemesterRow(target),
    )
    val oldCode = currentRow?.termCode?.takeIf { it != target.termCode }
        ?: return SemesterRenameSavePlan.Apply(saveSteps)
    return when (val rename = planSemesterRename(oldCode, target.termCode, hasCoursesUnderNewCode)) {
        SemesterRenamePlan.Rejected -> SemesterRenameSavePlan.Rejected
        is SemesterRenamePlan.Apply -> SemesterRenameSavePlan.Apply(rename.steps + saveSteps)
    }
}
