package com.buaa.schedule.data.backup

import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.mondayOfWeekAnchor
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T204①：把「恢复时这一枚学期行到底该怎么办」那一判钉成表驱动单测（纯 JVM、零时钟）。
 *
 * 判据本体在 `data/backup/BackupSemesterRestorePolicy.kt` 的 `decideSemesterRowForRestore`
 * 与 `indexBackupSemesterRows`。病灶有两处（逐字原文见内核段首与本回执 §2）：
 * - `BackupData` 只带**一枚**学期行，而 `BackupCourse` 每门课都带 `semesterCode` ⇒ 课程跨学期、学期不跨；
 * - 读侧对库里没有的学期直接 `startDate = fallbackStartDate.toString()` + `totalWeeks = 20`，
 *   而 `fallbackStartDate` 的实参是恢复那天所在的周一 ⇒ 换机恢复后每一枚历史学期的日期与周次一起错位。
 *
 * 档位（如实写明）：`:app` 的 JVM 测试面只有 `junit`，**没有 Robolectric、没有能在 JVM 上跑的 Room 缝**，
 * 所以本文件钉的是**判据**与**配对**；"调用点有没有真的按这一判走、补建只能经内核"由源码形状守卫
 * `BackupSemesterRestoreWiringGuardTest` 钉；"恢复一份多学期备份后历史学期的日期对不对"那一档
 * **没做**（红线零设备），归编排者装机。
 *
 * 两侧都有格子（卡面 §3.4 要求的五格全在，一枚不落）：
 * - 朝**宽**扭一次（内核恒回 `InsertFromBackup`，库里已有行也去写、没日期也硬写）⇒
 *   表 ① 里 `库里已有历史学期` 那格、以及 `只能补建` 那两格红；
 * - 朝**窄**扭一次（内核恒回 `Rebuild`，备份带来的日期不用、兜底日期照旧交出去）⇒
 *   表 ① 里 `备份带了该学期行` 那一族、`老备份回落` 那几格与「日期有出处时不进补建账」整片红。
 */
class BackupSemesterRestorePolicyTest {

    // ─────────────── ① 判据表：一等一笔，卡面要求的五格全在这 ───────────────

    @Test
    fun `判据表 每一格都按预期给出结论`() {
        for (cell in table) {
            val actual = decideSemesterRowForRestore(
                termCode = cell.termCode,
                backupRow = cell.backupRow,
                existingRow = cell.existingRow,
                fallbackStartDate = cell.fallback,
                observedMaxWeek = cell.observedMaxWeek,
            )
            assertEquals(
                "格子「${cell.name}」：termCode=${cell.termCode}、备份行=${describe(cell.backupRow)}、" +
                    "库里的行=${describe(cell.existingRow)}、兜底日期=${cell.fallback}、周次证据=${cell.observedMaxWeek}" +
                    " ⇒ 期望 ${describe(cell.expected)}。实际 ${describe(actual)}。${cell.why}\n" +
                    "这一格红了说明判据朝${cell.mutantDirection}的方向跑掉了",
                cell.expected,
                actual,
            )
        }
    }

    /**
     * 卡面点名的第 4 格那一族：**只能补建**那一等必须"说实话"。
     *
     * 两处不许退回去：① 结论里必须带着开学日的来历（[RebuiltStartReason]）与周数的来历
     * （[RebuiltWeeksBasis]），恢复结果与文案才念得出"这一枚是猜的";② 周数**不许是静默的 20** ——
     * 有周次证据就用证据（课程上到第 16 周 ⇒ 至少 16 周），没证据就给最小值并标 NO_BASIS。
     */
    @Test
    fun `补建那一等一定带着来历 且周数不是静默的二十`() {
        val rebuilds = table.filter { it.expected is SemesterRowDecision.Rebuild }
        assertTrue("表里必须至少有一格「只能补建」（卡面第 4 格），实到 ${rebuilds.size} 格", rebuilds.isNotEmpty())
        for (cell in rebuilds) {
            val actual = decideSemesterRowForRestore(
                termCode = cell.termCode,
                backupRow = cell.backupRow,
                existingRow = cell.existingRow,
                fallbackStartDate = cell.fallback,
                observedMaxWeek = cell.observedMaxWeek,
            ) as SemesterRowDecision.Rebuild

            val expectedRebuild = cell.expected as SemesterRowDecision.Rebuild
            assertEquals(
                "「${cell.name}」的开学日来历必须是 ${expectedRebuild.startReason}（实到 ${actual.startReason}）：" +
                    "库里没有、备份也没带 / 带了但日期认不出来，这两档在用户那边是完全不同的两句话，" +
                    "合成一枚「反正都是猜」就没人能分清是老备份还是坏备份",
                expectedRebuild.startReason,
                actual.startReason,
            )
            assertEquals(
                "「${cell.name}」的周数来历必须是 ${expectedRebuild.weeksBasis}（实到 ${actual.weeksBasis} / " +
                    "${actual.totalWeeks} 周）：周数从哪儿来是要报得出的 —— 有周次证据就是 COURSE_WEEKS，" +
                    "没有就是 NO_BASIS，任何一等都不许是「顺手一枚 20」",
                expectedRebuild.weeksBasis,
                actual.weeksBasis,
            )
            assertEquals(
                "「${cell.name}」写进库的 totalWeeks 必须是 ${expectedRebuild.totalWeeks}" +
                    "（实到 ${actual.totalWeeks}），整枚结论 ${describe(actual)} 要与 ${describe(expectedRebuild)} 等价",
                expectedRebuild,
                actual,
            )
            assertFalse(
                "「${cell.name}」不许把 totalWeeks 造回 20 —— 旧代码那枚静默的 20 是 #236 那一族" +
                    "（没依据就报「这学期 20 周」）在本仓的落点。实到 ${actual.totalWeeks} 周，" +
                    "周次证据 = ${cell.observedMaxWeek}",
                actual.totalWeeks == 20 && cell.observedMaxWeek != 20,
            )
        }
    }

    /**
     * 卡面第 2 格与第 3 格的分界：库里**已有**那一行时，备份带来的日期也不许拿去覆盖它。
     *
     * 这一格钉的是"同机恢复无损"那一条，也是旧 KDoc 自己写的理由（不改写学期行，免得把历史学期
     * 顶成当前学期）。朝宽那一扭（备份优先）会先在这里红。
     */
    @Test
    fun `库里已有那一行时一个字节都不动`() {
        for (cell in table.filter { it.existingRow != null }) {
            val actual = decideSemesterRowForRestore(
                termCode = cell.termCode,
                backupRow = cell.backupRow,
                existingRow = cell.existingRow,
                fallbackStartDate = cell.fallback,
                observedMaxWeek = cell.observedMaxWeek,
            )
            assertEquals(
                "「${cell.name}」库里既然有 ${cell.termCode} 这一行，结论必须是 KeepExistingInDb " +
                    "且带的就是库里那一枚（实到 ${describe(actual)}）：拿备份去盖 = 把用户在设置页" +
                    "订正过的开学日与周数冲掉，同机恢复就不再无损",
                SemesterRowDecision.KeepExistingInDb(requireNotNull(cell.existingRow)),
                actual,
            )
        }
    }

    // ─────────────── ② 日期归一：写进库的每一枚 startDate 都是周一 ───────────────

    /**
     * 三个等里两等会写库（InsertFromBackup / Rebuild），它们交出的 `startDate` 必须是周一。
     *
     * 全仓课次日期都是 `startDate.plusWeeks(w-1).plusDays(dow-1)`，开学日不是周一就整表偏移
     * 且周次编号错位（R7 P2-12 那一族：同一节课最多错开 6 天）。归一公式只许引
     * `domain/model` 那唯一一份 [mondayOfWeekAnchor]。
     */
    @Test
    fun `写进库的结论一律落在周一`() {
        for (cell in table) {
            val actual = decideSemesterRowForRestore(
                termCode = cell.termCode,
                backupRow = cell.backupRow,
                existingRow = cell.existingRow,
                fallbackStartDate = cell.fallback,
                observedMaxWeek = cell.observedMaxWeek,
            )
            val written: Semester = when (actual) {
                is SemesterRowDecision.KeepExistingInDb -> null // 这一等不写库，查不到新值
                is SemesterRowDecision.InsertFromBackup -> actual.semester
                is SemesterRowDecision.Rebuild -> actual.toSemester()
            } ?: continue
            val parsed = LocalDate.parse(written.startDate)
            assertEquals(
                "「${cell.name}」写进库的 startDate=${written.startDate} 必须是周一（ISO dayOfWeek=1）。" +
                    "红了说明归一被拆掉：备份里那枚串或兜底日期不是周一时，整学期每一节课的日期与周次一起偏",
                1,
                parsed.dayOfWeek.value,
            )
            assertEquals(
                "「${cell.name}」的 startDate 必须等于 mondayOfWeekAnchor(它自己)（幂等，别再二次偏移）",
                written.startDate,
                mondayOfWeekAnchor(parsed).toString(),
            )
        }
    }

    /** 兜底日期本身不是周一时也要归一：调用点写法变了（直接给 LocalDate.now()）不静默收下 */
    @Test
    fun `兜底日期不是周一时补建行仍落在周一`() {
        val notMonday = LocalDate.parse("2026-10-07") // 周三
        val decision = decideSemesterRowForRestore(
            termCode = "2024-2025-1",
            backupRow = null,
            existingRow = null,
            fallbackStartDate = notMonday,
            observedMaxWeek = 18,
        ) as SemesterRowDecision.Rebuild
        assertEquals(
            "周三 2026-10-07 递进来时，补建行的开学日必须是该自然周的周一 2026-10-05（实到 " +
                "${decision.startDate}）：旧代码是 `fallbackStartDate.toString()` 裸着写，" +
                "调用点哪天不先算周一了就把周三当开学日写进库",
            "2026-10-05",
            decision.startDate,
        )
    }

    // ─────────────── ③ 配对表：老备份回落、重复 termCode、空白 termCode ───────────────

    @Test
    fun `配对表 老备份回落与重复行都按预期`() {
        for (cell in indexTable) {
            val actual = indexBackupSemesterRows(cell.rows, cell.legacy)
            assertEquals(
                "格子「${cell.name}」：列表=${cell.rows.map { it.termCode }}、单字段=" +
                    cell.legacy?.termCode + " ⇒ 期望索引 ${cell.expectedKeys}。实际 ${actual.keys}",
                cell.expectedKeys,
                actual.keys.toList(),
            )
            for ((key, expectedRow) in cell.expectedRows) {
                assertEquals(
                    "格子「${cell.name}」里 termCode=$key 应当配到「${expectedRow.termName} / " +
                        "${expectedRow.startDate}」，实到 ${describe(actual[key])}：配错一枚 = 恢复时" +
                        "拿另一学期的开学日去建这一学期，比不配还坏",
                    expectedRow,
                    actual[key],
                )
            }
        }
    }

    /** 卡面第 5 格单独再钉一遍：主学期在 `semesters` 里重复出现时**首枚为准**，不许后枚覆盖 */
    @Test
    fun `同一 termCode 重复出现时首枚为准`() {
        val first = semester("2026-2027-1", "2026-2027学年第一学期", "2026-09-07", 19)
        val second = semester("2026-2027-1", "被改过的那一枚", "2026-02-23", 20)
        val index = indexBackupSemesterRows(listOf(first, second), legacy = second)
        assertEquals(
            "重复 termCode 必须首枚为准（实到 ${describe(index["2026-2027-1"])}）：备份文件是用户能" +
                "手改的，「后一枚覆盖前一枚」会让列表顺序悄悄决定恢复结果，而顺序没有任何语义" +
                "（写侧取的是 `getAllSemesters()` 的 startDate 倒序）",
            first,
            index["2026-2027-1"],
        )
        assertEquals(
            "索引只许一枚 2026-2027-1（实到 ${index.size} 枚）：两枚都留在索引里 = 同一学期两个开学日",
            1,
            index.size,
        )
    }

    /** 老备份（没有 semesters 键）必须等价于「只有单字段那一枚」，这是不涨版本号的兼容面 */
    @Test
    fun `老备份只有单字段时回落得到那一枚`() {
        val legacy = semester("2026-2027-1", "2026-2027学年第一学期", "2026-09-07", 19)
        val index = indexBackupSemesterRows(emptyList(), legacy)
        assertEquals(listOf("2026-2027-1"), index.keys.toList())
        assertEquals(legacy, index["2026-2027-1"])
        assertNull(
            "历史学期 2025-2026-2 在老备份里就是没有出处（实到 ${describe(index["2025-2026-2"])}）：" +
                "索引里查不到它，判据才走「只能补建」那一等 —— 这正是本卡要说实话的那一格",
            index["2025-2026-2"],
        )
    }

    // ─────────────── ④ 内核不读时钟：同一批输入连判两次同结论 ───────────────

    @Test
    fun `判据不读时钟 同一输入连判两次同结论`() {
        for (cell in table) {
            val first = decideSemesterRowForRestore(
                cell.termCode, cell.backupRow, cell.existingRow, cell.fallback, cell.observedMaxWeek,
            )
            val second = decideSemesterRowForRestore(
                cell.termCode, cell.backupRow, cell.existingRow, cell.fallback, cell.observedMaxWeek,
            )
            assertEquals(
                "「${cell.name}」两次判定不一致 = 判据里有状态（时钟或随机）：同一次恢复里" +
                    "同一枚学期行会先「用备份的日期」再「补建」，落库结果取决于第几次调用",
                first,
                second,
            )
        }
    }

    // ─────────────── ⑤ 补建账那一句：为空时不给文案，非空时必须念出来历 ───────────────

    @Test
    fun `补建账文案 空账不给句子 有账就念出开学日不是备份里的`() {
        assertNull(
            "没有任何补建时必须返回 null（实到 ${rebuiltSemestersNote(emptyList())}）：" +
                "恢复成功后面挂一句「有学期行是猜的」是假话",
            rebuiltSemestersNote(emptyList()),
        )
        val one = SemesterRowDecision.Rebuild(
            termCode = "2025-2026-2",
            termName = "2025-2026-2",
            startDate = "2026-10-05",
            totalWeeks = 16,
            startReason = RebuiltStartReason.NOT_IN_BACKUP,
            weeksBasis = RebuiltWeeksBasis.COURSE_WEEKS,
        )
        val note = rebuiltSemestersNote(listOf(one))!!
        assertTrue("必须念出补建枚数（实到「$note」）", note.contains("1 枚"))
        assertTrue("必须念出是哪一学期（实到「$note」）", note.contains("2025-2026-2"))
        assertTrue(
            "必须明说开学日不是备份里的、而是按恢复那天推算（实到「$note」）：" +
                "这句话是本卡「不许静默」那一面的唯一出口",
            note.contains("不在备份里") && note.contains("恢复那天"),
        )
        val many = rebuiltSemestersNote(
            listOf(one) + (1..5).map {
                one.copy(termCode = "TERM-$it", startReason = RebuiltStartReason.BACKUP_DATE_UNREADABLE)
            },
        )!!
        assertTrue("超过点名列举上限时要折算成「等 N 枚」，且枚数是全量（实到「$many」）", many.contains("等 6 枚"))
    }

    // ─────────────── 表 ───────────────

    private fun semester(code: String, name: String, start: String, weeks: Int) =
        Semester(termCode = code, termName = name, startDate = start, totalWeeks = weeks)

    private data class Cell(
        val name: String,
        val termCode: String,
        val backupRow: Semester?,
        val existingRow: Semester?,
        val fallback: LocalDate,
        val observedMaxWeek: Int?,
        val expected: SemesterRowDecision,
        val why: String,
        val mutantDirection: String,
    )

    private data class IndexCell(
        val name: String,
        val rows: List<Semester>,
        val legacy: Semester?,
        val expectedKeys: List<String>,
        val expectedRows: Map<String, Semester>,
    )

    private fun describe(value: Any?): String = when (value) {
        null -> "null"
        is Semester -> "Semester(${value.termCode}/${value.termName}/${value.startDate}/${value.totalWeeks}周)"
        is SemesterRowDecision.KeepExistingInDb -> "KeepExistingInDb(${value.existing.termCode})"
        is SemesterRowDecision.InsertFromBackup ->
            "InsertFromBackup(${value.semester.termCode}/${value.semester.startDate}/${value.semester.totalWeeks}周)"
        is SemesterRowDecision.Rebuild ->
            "Rebuild(${value.termCode}/${value.startDate}/${value.totalWeeks}周," +
                "开学日来历=${value.startReason},周数来历=${value.weeksBasis})"
        else -> value.toString()
    }

    private companion object {

        /** 恢复那天所在的周一（`ui/ScheduleViewModel` 递给旧代码的那一枚实参） */
        val RESTORE_MONDAY: LocalDate = LocalDate.parse("2026-10-05")

        /** 主学期（当前学期）：库里与备份里都有它 */
        val MAIN_ROW: Semester = Semester(
            termCode = "2026-2027-1",
            termName = "2026-2027学年第一学期",
            startDate = "2026-09-07",
            totalWeeks = 19,
        )

        /** 历史学期在备份里的那一枚（真实开学日，正是本卡要带过来的东西） */
        val HISTORY_BACKUP_ROW: Semester = Semester(
            termCode = "2025-2026-2",
            termName = "2025-2026学年第二学期",
            startDate = "2026-02-23",
            totalWeeks = 18,
        )

        /**
         * 卡面 §3.4 点名的五格 = ① 老备份（无 semesters）、② 新备份 + 库里有历史学期、
         * ③ 新备份 + 库里没有（备份带了该学期行 ⇒ 用备份的日期）、④ 新备份 + 库里没有且备份没带
         * （只能补建 ⇒ 说实话那一档）、⑤ `semesters` 里有主学期重复出现（在表 ③ 那组里钉）。
         * 其余是同一判据的补格：备份带了行但日期是坏串、开学日不是周一、周数越界、
         * 没有周次证据、库里那枚与备份那枚同 termCode 但不同日期（次序冲突）。
         */
        val table: List<Cell> = listOf(
            // ── 卡面第 1 格：老备份（没有 semesters）→ 历史学期无出处 → 只能补建 ──
            Cell(
                name = "①老备份恢复历史学期：索引里没有该 termCode（判据侧收到 backupRow = null）",
                termCode = "2025-2026-2",
                backupRow = null,
                existingRow = null,
                fallback = RESTORE_MONDAY,
                observedMaxWeek = 16,
                expected = SemesterRowDecision.Rebuild(
                    termCode = "2025-2026-2",
                    termName = "2025-2026-2",
                    startDate = "2026-10-05",
                    totalWeeks = 16,
                    startReason = RebuiltStartReason.NOT_IN_BACKUP,
                    weeksBasis = RebuiltWeeksBasis.COURSE_WEEKS,
                ),
                why = "老备份只有主学期那一枚（回落见配对表），历史学期确实没出处 ⇒ 只能补建，" +
                    "但开学日标 NOT_IN_BACKUP、周数标 COURSE_WEEKS（这批课上到第 16 周），恢复文案念得出来",
                mutantDirection = "更宽的方向跑掉了（把兜底日期说成有出处，或干脆不标来历）",
            ),
            // ── 卡面第 2 格：新备份 + 库里有历史学期 → 沿用库里那一枚，不写 ──
            Cell(
                name = "②同机恢复：库里已有历史学期，备份也带着它 ⇒ 库里为准（同机无损那一档）",
                termCode = "2025-2026-2",
                backupRow = HISTORY_BACKUP_ROW,
                existingRow = Semester(
                    termCode = "2025-2026-2",
                    termName = "2025-2026学年第二学期（我自己在设置页改过开学日）",
                    startDate = "2026-03-02",
                    totalWeeks = 17,
                ),
                fallback = RESTORE_MONDAY,
                observedMaxWeek = 16,
                expected = SemesterRowDecision.KeepExistingInDb(
                    Semester(
                        termCode = "2025-2026-2",
                        termName = "2025-2026学年第二学期（我自己在设置页改过开学日）",
                        startDate = "2026-03-02",
                        totalWeeks = 17,
                    ),
                ),
                why = "库里那一行是这台设备此刻的事实（用户订正过就用订正后的）。拿备份去盖 = 同机恢复" +
                    "不再无损，而且 insert 会把这行的 id 顶到最大、历史学期变成「当前学期」",
                mutantDirection = "更宽的方向跑掉了（备份优先于库里既有行）",
            ),
            // ── 卡面第 3 格：新备份 + 库里没有（备份带了该学期行）→ 用备份的日期 ──
            Cell(
                name = "③换机恢复：库里没有、备份带着历史学期 ⇒ 用备份的 2026-02-23",
                termCode = "2025-2026-2",
                backupRow = HISTORY_BACKUP_ROW,
                existingRow = null,
                fallback = RESTORE_MONDAY,
                observedMaxWeek = 16,
                expected = SemesterRowDecision.InsertFromBackup(HISTORY_BACKUP_ROW),
                why = "这就是本卡的修复本体：日期有出处就用出处，不进补建账。旧代码在这一格写的是" +
                    "「2026-10-05 开学、20 周」—— 恢复那天，与这一学期毫无关系",
                mutantDirection = "更窄的方向跑掉了（不用备份带来的日期，退回补建那一等）",
            ),
            Cell(
                name = "③′备份带来的开学日不是周一 ⇒ 归一到该自然周的周一",
                termCode = "2025-2026-2",
                backupRow = HISTORY_BACKUP_ROW.copy(startDate = "2026-02-22"), // 周日（报到日）
                existingRow = null,
                fallback = RESTORE_MONDAY,
                observedMaxWeek = 16,
                expected = SemesterRowDecision.InsertFromBackup(
                    HISTORY_BACKUP_ROW.copy(startDate = "2026-02-16"),
                ),
                why = "全仓课次日期都是 startDate.plusWeeks(w-1).plusDays(dow-1)，开学日不是周一" +
                    "整张表就按星期几错位（R7 P2-12 那一族：同一节课最多错开 6 天）",
                mutantDirection = "更宽的方向跑掉了（坏锚点原样入库）",
            ),
            Cell(
                name = "③″备份带来的周数越界 ⇒ 限幅到 MAX_TOTAL_WEEKS",
                termCode = "2025-2026-2",
                backupRow = HISTORY_BACKUP_ROW.copy(totalWeeks = 400),
                existingRow = null,
                fallback = RESTORE_MONDAY,
                observedMaxWeek = 16,
                expected = SemesterRowDecision.InsertFromBackup(
                    HISTORY_BACKUP_ROW.copy(totalWeeks = 30),
                ),
                why = "备份是用户可控输入：400 周会让周次选择器长到不可用、currentWeekOrNull 报越界周次，" +
                    "与主学期那一路同一口径（CourseConstraints.normalizeTotalWeeks）",
                mutantDirection = "更宽的方向跑掉了（越界值直接入库）",
            ),
            Cell(
                name = "③‴备份带了行但日期是坏串 ⇒ 有行不等于有日期，走补建并标 BACKUP_DATE_UNREADABLE",
                termCode = "2025-2026-2",
                backupRow = HISTORY_BACKUP_ROW.copy(startDate = "2026/2/23"),
                existingRow = null,
                fallback = RESTORE_MONDAY,
                observedMaxWeek = 18,
                expected = SemesterRowDecision.Rebuild(
                    termCode = "2025-2026-2",
                    termName = "2025-2026学年第二学期",
                    startDate = "2026-10-05",
                    totalWeeks = 18,
                    startReason = RebuiltStartReason.BACKUP_DATE_UNREADABLE,
                    weeksBasis = RebuiltWeeksBasis.COURSE_WEEKS,
                ),
                why = "斜杠单位 parse 不出来就不许「抢救」成日期 —— 认不出来就是没出处（同 T180 口径）。" +
                    "termName 仍可以用备份那一枚（名字是有出处的，日期才是没有的）",
                mutantDirection = "更宽的方向跑掉了（把认不出来的串折算成任何一个日期）",
            ),
            // ── 卡面第 4 格：新备份 + 库里没有且备份没带 → 只能补建（说实话那一档） ──
            Cell(
                name = "④库里没有、备份也没带、且没有周次证据 ⇒ 补建 + NO_BASIS（不许静默 20）",
                termCode = "2024-2025-1",
                backupRow = null,
                existingRow = null,
                fallback = RESTORE_MONDAY,
                observedMaxWeek = null,
                expected = SemesterRowDecision.Rebuild(
                    termCode = "2024-2025-1",
                    termName = "2024-2025-1",
                    startDate = "2026-10-05",
                    totalWeeks = 1,
                    startReason = RebuiltStartReason.NOT_IN_BACKUP,
                    weeksBasis = RebuiltWeeksBasis.NO_BASIS,
                ),
                why = "两样都没出处时，旧代码报的是「2026-10-05 开学、20 周」且一句提示都没有。" +
                    "这一格钉的是：周数没有依据时给最小值并把 NO_BASIS 交回文案，绝不造一枚看起来合理的 20",
                mutantDirection = "更宽的方向跑掉了（没依据也报一个像样的周数）",
            ),
            Cell(
                name = "④′只能补建但有周次证据 ⇒ 周数取这批课的最大周次（有出处的下界）",
                termCode = "2024-2025-1",
                backupRow = null,
                existingRow = null,
                fallback = RESTORE_MONDAY,
                observedMaxWeek = 19,
                expected = SemesterRowDecision.Rebuild(
                    termCode = "2024-2025-1",
                    termName = "2024-2025-1",
                    startDate = "2026-10-05",
                    totalWeeks = 19,
                    startReason = RebuiltStartReason.NOT_IN_BACKUP,
                    weeksBasis = RebuiltWeeksBasis.COURSE_WEEKS,
                ),
                why = "课程上到第 19 周，这学期就至少 19 周：这是从备份内容里读出来的，不是造的。" +
                    "开学日仍然是猜的（startReason 明说），这一等整枚都在补建账里",
                mutantDirection = "更宽/更窄都会红（周数被换成常量就不再是「有出处」）",
            ),
            Cell(
                name = "④″周次证据越界 ⇒ 限幅后再记账",
                termCode = "2024-2025-1",
                backupRow = null,
                existingRow = null,
                fallback = RESTORE_MONDAY,
                observedMaxWeek = 999,
                expected = SemesterRowDecision.Rebuild(
                    termCode = "2024-2025-1",
                    termName = "2024-2025-1",
                    startDate = "2026-10-05",
                    totalWeeks = 30,
                    startReason = RebuiltStartReason.NOT_IN_BACKUP,
                    weeksBasis = RebuiltWeeksBasis.COURSE_WEEKS,
                ),
                why = "observedMaxWeek 来自用户可改的备份（weeks 列表已被 normalize 钳在 1..30，" +
                    "但判据自己不依赖那一层）：越界值照样限幅，别把 999 写进库",
                mutantDirection = "更宽的方向跑掉了（越界证据直接入库）",
            ),
            // ── 卡面第 5 格：主学期在 semesters 里重复出现（判据侧看到的是配对表的产物） ──
            Cell(
                name = "⑤主学期重复出现后配到的是首枚 ⇒ 判据拿到的那一枚日期是 2026-09-07",
                termCode = "2026-2027-1",
                backupRow = MAIN_ROW,
                existingRow = null,
                fallback = RESTORE_MONDAY,
                observedMaxWeek = 19,
                expected = SemesterRowDecision.InsertFromBackup(MAIN_ROW),
                why = "配对表里同 termCode 出现两枚（另一枚 startDate 是 2026-02-23）时首枚为准，" +
                    "所以判据收到的备份行是 2026-09-07 那一枚。次序规则见 indexBackupSemesterRows 与那一格单测",
                mutantDirection = "更宽的方向跑掉了（后一枚覆盖前一枚，同一学期两个开学日）",
            ),
            // 主学期在库里已有 ⇒ 仍然沿用库里的（这一等走的是 restoreBackupDataInTx 的主学期分支，
            // 但补建判据不许插嘴把它顶掉）
            Cell(
                name = "主学期库里已有 ⇒ 不写（主学期配置由 replaceSemesterCoursesInTx 那一支负责）",
                termCode = "2026-2027-1",
                backupRow = MAIN_ROW,
                existingRow = MAIN_ROW.copy(termName = "当前学期（库里那一枚）"),
                fallback = RESTORE_MONDAY,
                observedMaxWeek = 19,
                expected = SemesterRowDecision.KeepExistingInDb(MAIN_ROW.copy(termName = "当前学期（库里那一枚）")),
                why = "补建判据只管「库里没有这一学期」那一档；主学期的写法（deleteByTermCode + insert）" +
                    "在 replaceSemesterCoursesInTx 里，两条路各管各的，混起来会把当前学期顶成历史学期",
                mutantDirection = "更宽的方向跑掉了（补建判据去改主学期行）",
            ),
        )

        /**
         * 配对表：卡面第 1 格与第 5 格的**读侧**那一半在这里钉（判据表钉的是它们喂进
         * `decideSemesterRowForRestore` 之后长什么样）。
         */
        val indexTable: List<IndexCell> = listOf(
            IndexCell(
                name = "新备份：列表两枚 + 单字段主学期（主学期两枚同源，索引仍是两枚）",
                rows = listOf(HISTORY_BACKUP_ROW, MAIN_ROW),
                legacy = MAIN_ROW,
                expectedKeys = listOf("2025-2026-2", "2026-2027-1"),
                expectedRows = mapOf(
                    "2025-2026-2" to HISTORY_BACKUP_ROW,
                    "2026-2027-1" to MAIN_ROW,
                ),
            ),
            IndexCell(
                name = "老备份：列表为空 ⇒ 回落到单字段那一枚（卡面第 1 格）",
                rows = emptyList(),
                legacy = MAIN_ROW,
                expectedKeys = listOf("2026-2027-1"),
                expectedRows = mapOf("2026-2027-1" to MAIN_ROW),
            ),
            IndexCell(
                name = "主学期在列表里重复出现 ⇒ 首枚为准（卡面第 5 格），单字段那枚更靠后也不许顶掉它",
                rows = listOf(
                    MAIN_ROW,
                    MAIN_ROW.copy(termName = "手改过的第二枚", startDate = "2026-02-23", totalWeeks = 20),
                    HISTORY_BACKUP_ROW,
                ),
                legacy = MAIN_ROW.copy(startDate = "2020-01-01", totalWeeks = 8),
                expectedKeys = listOf("2026-2027-1", "2025-2026-2"),
                expectedRows = mapOf(
                    "2026-2027-1" to MAIN_ROW,
                    "2025-2026-2" to HISTORY_BACKUP_ROW,
                ),
            ),
            IndexCell(
                name = "老备份且单字段也没有 ⇒ 空索引（补建那一等唯一进得来的路）",
                rows = emptyList(),
                legacy = null,
                expectedKeys = emptyList(),
                expectedRows = emptyMap(),
            ),
            IndexCell(
                name = "列表里混进空白 termCode 的行 ⇒ 不进索引",
                rows = listOf(
                    Semester(termCode = "   ", termName = "手改出来的空行", startDate = "2026-09-07", totalWeeks = 19),
                    MAIN_ROW,
                ),
                legacy = null,
                expectedKeys = listOf("2026-2027-1"),
                expectedRows = mapOf("2026-2027-1" to MAIN_ROW),
            ),
            IndexCell(
                name = "列表非空但主学期只在单字段里 ⇒ 单字段补进索引（列表在前，不冲突）",
                rows = listOf(HISTORY_BACKUP_ROW),
                legacy = MAIN_ROW,
                expectedKeys = listOf("2025-2026-2", "2026-2027-1"),
                expectedRows = mapOf(
                    "2025-2026-2" to HISTORY_BACKUP_ROW,
                    "2026-2027-1" to MAIN_ROW,
                ),
            ),
        )
    }
}
