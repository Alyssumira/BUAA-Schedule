package com.buaa.schedule.data.local

import com.buaa.schedule.domain.model.Semester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T249（台账 #460 / #461）：semesters 行级判据的表 —— 纯 JVM，零 android、零时钟、零库。
 *
 * 病灶两句话：
 * - #460 —— `getByTermCode` 改前是 `… WHERE termCode = :termCode LIMIT 1`，**连 `ORDER BY` 都没有**，
 *   而 `semesters` 没有 termCode 唯一索引 ⇒ 同 termCode 两行时"交出哪一枚"由扫描顺序决定；
 *   `switchSemester` 又按 termCode 删全部，于是切一次学期把另一枚行的开学日/总周数一起吃掉。
 * - #461 —— 改学期代码是 rename 一枚事务 + `saveSemester` 另一枚事务，中间被杀 ⇒ 旧行已删、新行未插。
 *
 * Room 真库这一档**没做**（`:app` 的 JVM 测试面没有 Robolectric、没有能在 JVM 上跑的 Room 缝，
 * 红线零设备）：这里每一格都用 [Semester] 真实构造行、直接跑内核；
 * SQL 那一头的形状由 `SemesterRowShapeGuardTest` 逐字钉住，两把尺必须同口径。
 */
class SemesterRowPolicyTest {

    private companion object {
        const val TERM = "2026-2027-1"
        const val OTHER = "2025-2026-2"
    }

    private fun row(
        id: Long,
        termCode: String = TERM,
        startDate: String = "2026-08-31",
        totalWeeks: Int = 20,
        termName: String = "2026 秋",
    ) = Semester(
        id = id,
        termCode = termCode,
        termName = termName,
        startDate = startDate,
        totalWeeks = totalWeeks,
    )

    // ─────────────── ① 读侧确定性：同 termCode 多行时交出哪一枚 ───────────────

    @Test
    fun `同 termCode 两行时读数交出 id 最大的那一枚`() {
        // 库里同 termCode 落下两枚行（数据库层面允许）：一枚是用户后来在设置页改过的 24 周，
        // 一枚是更早写入的 19 周。改前 `LIMIT 1` 无 ORDER BY ⇒ 交哪枚没人能答。
        val older = row(id = 7L, startDate = "2026-02-23", totalWeeks = 19, termName = "2026 春")
        val newer = row(id = 9L, startDate = "2026-09-07", totalWeeks = 24)

        assertEquals(
            "同 termCode 两行时读数必须恒交 id 最大那一枚（= 最近写入的那一行，" +
                "与 `SemesterDao.getCurrent()` 的 `ORDER BY id DESC LIMIT 1` 同口径）。实到 " +
                pickSemesterRowForTermCode(TERM, listOf(older, newer)),
            newer,
            pickSemesterRowForTermCode(TERM, listOf(older, newer)),
        )
        assertEquals(
            "行序不作依据：把两枚行倒过来递进来必须给同一枚答案（改前那一条查询守的就是这一条）",
            pickSemesterRowForTermCode(TERM, listOf(older, newer)),
            pickSemesterRowForTermCode(TERM, listOf(newer, older)),
        )
    }

    @Test
    fun `读数对任意排列都给同一枚行 三枚行的全排列都跑一遍`() {
        val rows = listOf(
            row(id = 3L, startDate = "2025-09-01"),
            row(id = 11L, startDate = "2026-09-07"),
            row(id = 8L, startDate = "2026-02-23"),
        )
        val expected = row(id = 11L, startDate = "2026-09-07")
        val offenders = mutableListOf<String>()
        permutations(rows).forEach { shuffled ->
            val picked = pickSemesterRowForTermCode(TERM, shuffled)
            if (picked != expected) offenders += "排列 $shuffled 交出 $picked"
        }
        assertEquals(
            "3 枚行的 6 种排列必须全部交出 id = 11 那一枚（实到偏差：" + offenders.joinToString("; ") + "）。" +
                "红了说明内核开始吃输入行序 —— 那就退回「由 SQLite 扫描顺序决定」那一档",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `别的 termCode 的行不能算数`() {
        // SQL 里的 `WHERE termCode = :termCode` 在内核里有对应的一份：查询哪天被人改了范围，
        // 内核也不许拿另一学期的行凑成"这一学期有行"。
        val rows = listOf(row(id = 4L, termCode = OTHER, startDate = "2025-09-01"))

        assertEquals(
            "库里只有别的 termCode 的行时读数必须是 null（实到 ${pickSemesterRowForTermCode(TERM, rows)}）：" +
                "非 null 就意味着 switchSemester 会把别的学期顶成当前学期",
            null,
            pickSemesterRowForTermCode(TERM, rows),
        )
        assertEquals(
            "按那一学期自己问才拿得到它（收窄是逐字比 termCode）",
            row(id = 4L, termCode = OTHER, startDate = "2025-09-01"),
            pickSemesterRowForTermCode(OTHER, rows),
        )
    }

    @Test
    fun `id 相等的两枚行也要给出确定答案`() {
        // 主键上落不到同 id 两枚行，但"排序必须是全序"这件事不许靠数据库兜 ——
        // 否则同 id 时 sortedWith 的结果由实现细节决定，这张表就不可复现。
        val left = row(id = 5L, startDate = "2026-09-07", totalWeeks = 24)
        val right = row(id = 5L, startDate = "2026-08-31", totalWeeks = 20)

        val forward = pickSemesterRowForTermCode(TERM, listOf(left, right))
        val backward = pickSemesterRowForTermCode(TERM, listOf(right, left))
        assertEquals(
            "id 相等时仍要给同一枚（实到 $forward / $backward）",
            forward,
            backward,
        )
        assertEquals(
            "同 id 两枚时按内容定序（startDate 是第二维）：这一格钉的是「全序」而不是「碰巧稳定」",
            right,
            forward,
        )
    }

    // ─────────────── ② switch 不再吃掉另一行 ───────────────

    @Test
    fun `切换计划只删被顶的那一枚 同 termCode 的兄弟行一枚都不许动`() {
        val sibling = row(id = 7L, startDate = "2026-02-23", totalWeeks = 19, termName = "2026 春")
        val current = row(id = 9L, startDate = "2026-09-07", totalWeeks = 24)

        val plan = planSemesterRowBump(TERM, listOf(sibling, current))

        assertTrue(
            "同 termCode 两行时切换必须成计划（实到 $plan）：改前这里是「读任一行 + 删全部 + 只插回那一行」",
            plan is SemesterRowBumpPlan.Bump,
        )
        plan as SemesterRowBumpPlan.Bump
        assertEquals(
            "顶上去的那一枚是 id 最大的那一枚（与读数同一把尺）。实到 ${plan.target}",
            current,
            plan.target,
        )
        assertEquals(
            "删的 id 恒只含被顶的那一枚（实到 ${plan.deleteIds}）：改前这里等价于 `DELETE FROM semesters " +
                "WHERE termCode = ?`，把兄弟行的开学日与总周数一起吃掉，而用户从没看过那一枚行",
            listOf(9L),
            plan.deleteIds,
        )
        assertEquals(
            "兄弟行必须原样留在 untouchedIds 里（实到 ${plan.untouchedIds}）：这一格就是「switch 不再吃掉另一行」",
            listOf(7L),
            plan.untouchedIds,
        )
        assertEquals(
            "删除集与保留集不许有交集（一枚行既删又留 = 两把尺）",
            emptyList<Long>(),
            plan.deleteIds.filter { it in plan.untouchedIds },
        )
    }

    @Test
    fun `库里只有一枚行时切换计划与改前逐字同形`() {
        // 对照格：正常盘面（一 termCode 一行）下这一跳的产物必须和改前一模一样 ——
        // 改的是「多枚行时吃什么」，不是「一行时动不动」。
        val only = row(id = 4L)

        val plan = planSemesterRowBump(TERM, listOf(only)) as SemesterRowBumpPlan.Bump

        assertEquals("一枚行时删的就是它自己", listOf(4L), plan.deleteIds)
        assertEquals("一枚行时没有兄弟行可留", emptyList<Long>(), plan.untouchedIds)
        assertEquals("顶的那一枚就是它", only, plan.target)
    }

    @Test
    fun `切换计划也不吃行序`() {
        val rows = listOf(row(id = 2L), row(id = 6L), row(id = 5L))
        val expected = SemesterRowBumpPlan.Bump(row(id = 6L), listOf(6L), listOf(2L, 5L))
        val offenders = mutableListOf<String>()
        permutations(rows).forEach { shuffled ->
            val plan = planSemesterRowBump(TERM, shuffled)
            if (plan != expected) offenders += "排列 $shuffled 给出 $plan"
        }
        assertEquals(
            "6 种排列必须给出同一份计划（deleteIds = [6]、untouchedIds = [2,5]）。实到偏差：" +
                offenders.joinToString("; "),
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `库里没有这一 termCode 时切换交回 NothingToSwitch 且不写任何一行`() {
        val plans = listOf(
            planSemesterRowBump(TERM, listOf(row(id = 1L, termCode = OTHER))),
            planSemesterRowBump(TERM, emptyList()),
        )

        assertEquals(
            "库里没有这一学期时 switchSemester 返回 false、一个字都不写（实到 $plans）：" +
                "两档（只有别的 termCode 的行 / 表里什么都没有）都得是同一个结论",
            listOf(SemesterRowBumpPlan.NothingToSwitch, SemesterRowBumpPlan.NothingToSwitch),
            plans,
        )
    }

    // ─────────────── ③ #461：合并事务的步骤表 ───────────────

    @Test
    fun `合并事务的步骤就是改前两枚事务的步骤按顺序拼接`() {
        val old = row(id = 3L, termCode = OTHER, startDate = "2025-09-01")
        val target = row(id = 0L, termCode = TERM, startDate = "2026-09-07", totalWeeks = 24)

        val merged = planSemesterRenameSave(old, target, hasCoursesUnderNewCode = false)
        assertTrue("termCode 变了就必须成计划（实到 $merged）", merged is SemesterRenameSavePlan.Apply)
        merged as SemesterRenameSavePlan.Apply

        assertEquals(
            "合并后的写侧步骤必须**逐枚、按序**等于「改前第一枚事务（rename）+ 改前第二枚事务（saveSemester）」" +
                "（实到 ${merged.steps}）：#461 的修法只消掉两枚事务之间的那格死亡窗口，" +
                "不多写、不少写、不换序 —— 换序就是把课程改挂在了已删的行之后",
            listOf(
                SemesterRenameSaveStep.ReassignCourses(OTHER, TERM),
                SemesterRenameSaveStep.DeleteRowsForTermCode(OTHER),
                SemesterRenameSaveStep.DeleteRowsForTermCode(TERM),
                SemesterRenameSaveStep.WriteSemesterRow(target),
            ),
            merged.steps,
        )
        val renameAlone = (planSemesterRename(OTHER, TERM, hasCoursesUnderNewCode = false) as SemesterRenamePlan.Apply)
            .steps
        val saveAlone = listOf(
            SemesterRenameSaveStep.DeleteRowsForTermCode(TERM),
            SemesterRenameSaveStep.WriteSemesterRow(target),
        )
        assertEquals(
            "另一把尺复算：两枚各自算一遍再拼起来，必须与合并计划同形",
            renameAlone + saveAlone,
            merged.steps,
        )
    }

    @Test
    fun `termCode 没变时不判拒绝 也不动任何课程`() {
        // 改前 VM 是 `if (old != null && old.termCode != semester.termCode)` 才进 rename 那一跳：
        // 只改开学日 / 总周数时「新代码下已有课」这一判**根本不问**。合并之后这一档必须原样保住 ——
        // 否则用户每学期改一次开学日就会被自己已有的课表拒掉。
        val current = row(id = 8L, startDate = "2026-08-31", totalWeeks = 20)
        val target = row(id = 0L, startDate = "2026-09-07", totalWeeks = 24)

        val plan = planSemesterRenameSave(current, target, hasCoursesUnderNewCode = true)

        assertTrue(
            "termCode 没变 ⇒ 手里有课也不许拒（实到 $plan）。改前这一路压根不查课程表",
            plan is SemesterRenameSavePlan.Apply,
        )
        plan as SemesterRenameSavePlan.Apply
        assertEquals(
            "步骤与 `saveSemester` 一字不差：收口 + 插新行，两枚",
            listOf(
                SemesterRenameSaveStep.DeleteRowsForTermCode(TERM),
                SemesterRenameSaveStep.WriteSemesterRow(target),
            ),
            plan.steps,
        )
        assertEquals(
            "没有 ReassignCourses 这一步（不许动课程）",
            0,
            plan.steps.filterIsInstance<SemesterRenameSaveStep.ReassignCourses>().size,
        )
    }

    @Test
    fun `库里还没有学期行时就是第一次设学期`() {
        val target = row(id = 0L, termCode = OTHER, startDate = "2025-09-01")

        val plan = planSemesterRenameSave(null, target, hasCoursesUnderNewCode = true) as SemesterRenameSavePlan.Apply

        assertEquals(
            "库里没有当前学期（用户还没设过）⇒ 没有「旧代码」可改挂，也没有可拒的理由（实到 ${plan.steps}）",
            listOf(
                SemesterRenameSaveStep.DeleteRowsForTermCode(OTHER),
                SemesterRenameSaveStep.WriteSemesterRow(target),
            ),
            plan.steps,
        )
    }

    @Test
    fun `rename 被拒时不落新行也不写学期`() {
        val old = row(id = 3L, termCode = OTHER)
        val target = row(id = 0L, termCode = TERM)

        val merged = planSemesterRenameSave(old, target, hasCoursesUnderNewCode = true)
        val rename = planSemesterRename(OTHER, TERM, hasCoursesUnderNewCode = true)

        assertEquals(
            "新代码下已有别的课表 ⇒ 合并计划整组交回 Rejected（实到 $merged）：" +
                "调用方据此返回 false 并念那一句文案，而库里一个字节都不许多写",
            SemesterRenameSavePlan.Rejected,
            merged,
        )
        assertEquals("rename 那一判同一口径（两枚写侧共用它，没有第二把尺）", SemesterRenamePlan.Rejected, rename)

        val table = semesterTableOf(old)
        runSemesterStepsInFakeTx(merged, table, throwAt = null)
        assertEquals(
            "拒绝档落下前后必须是同一份表（实到 ${table.rows}）：不落新行、不删旧行、不动学期",
            listOf(old),
            table.rows,
        )
        assertEquals("拒绝档一步都没排进台账", 0, table.appliedSteps)
    }

    @Test
    fun `合并事务中途抛时两行都不动`() {
        // 这一格钉的是「整组步骤只在 commit 那一刻落下」这一等形状：计划里不存在分步提交那一等，
        // 所以任何一步抛在中间，落下的都是空集。
        // ⚠️ 如实写明没做的那一半：Room 真库的回滚没有 JVM 缝可跑（零 Robolectric、零设备），
        //    「真在事务里抛一次」归编排者装机；改前那两枚独立事务在这一档会留下「旧行已删、新行未插」，
        //    那才是本卡要消灭的形状。
        val sibling = row(id = 7L, termCode = OTHER, startDate = "2025-02-24", totalWeeks = 19)
        val current = row(id = 9L, termCode = OTHER, startDate = "2025-09-01", totalWeeks = 20)
        val target = row(id = 0L, termCode = TERM, startDate = "2026-09-07", totalWeeks = 24)
        val plan = planSemesterRenameSave(current, target, hasCoursesUnderNewCode = false)
        plan as SemesterRenameSavePlan.Apply
        assertEquals("这一档是四枚步骤的合并计划（改前两枚事务各两枚）", 4, plan.steps.size)

        val before = listOf(sibling, current)
        val offenders = (1..4).mapNotNull { throwAt ->
            val attempt = semesterTableOf(sibling, current)
            runSemesterStepsInFakeTx(plan, attempt, throwAt = throwAt)
            when {
                attempt.rows != before -> "抛在第 $throwAt 步 ⇒ 库里落下 ${attempt.rows}"
                attempt.appliedSteps != 0 -> "抛在第 $throwAt 步 ⇒ 却提交了 ${attempt.appliedSteps} 枚步骤"
                else -> null
            }
        }
        assertEquals(
            "四枚步骤、四种抛点，一种都不许留下半成品，也不许多提交一枚步骤" +
                "（实到偏差：" + offenders.joinToString("; ") + "）。" +
                "红了说明合并计划里出现了「某一步自己就是一次提交」的形状 —— 那就是 #461 的死亡窗口换了个入口",
            emptyList<String>(),
            offenders,
        )

        // 对照：不抛时才整组落下（否则上面那层可以靠「永远不提交」空成立）
        val happy = semesterTableOf(sibling, current)
        runSemesterStepsInFakeTx(plan, happy, throwAt = null)
        assertEquals("不抛时四枚步骤必须全部落下（对照格：上面那层不是靠空转绿的）", 4, happy.appliedSteps)
        assertEquals(
            "落下之后：同 termCode 的两枚旧行都被 termCode 收口清掉，只剩新行",
            listOf(TERM),
            happy.rows.map { it.termCode },
        )
        assertEquals("新行的内容是用户交进去的那一枚", TERM, happy.rows.single().termCode)
        assertEquals("新行拿到自增 id（= 当前学期）", 10L, happy.rows.single().id)
    }

    @Test
    fun `oldCode 与 newCode 相同时 rename 的产物与改前逐字同形`() {
        // 卡面 §5：renameSemesterCourses 的调用方语义不许变。
        // 改前 old == new 时它照样 reassign（空操作）+ 按 termCode 删旧行并返回 true —— 这里不另开一档
        // 「不需要改代码」，因为那会改掉这一枚既有调用方的落库形状。
        val plan = planSemesterRename(TERM, TERM, hasCoursesUnderNewCode = false) as SemesterRenamePlan.Apply

        assertEquals(
            "同代码的产物（实到 ${plan.steps}）",
            listOf(
                SemesterRenameSaveStep.ReassignCourses(TERM, TERM),
                SemesterRenameSaveStep.DeleteRowsForTermCode(TERM),
            ),
            plan.steps,
        )
        assertEquals(
            "同代码 + 新代码下有课 ⇒ 仍然按 rename 那一判拒（改前就是先查再拒，一步都不写）",
            SemesterRenamePlan.Rejected,
            planSemesterRename(TERM, TERM, hasCoursesUnderNewCode = true),
        )
    }

    // ─────────────── 假库：只用来把「整组才落下」这件事跑成数据 ───────────────

    /** 一枚假的库表 + 台账：步骤先排队，[commit] 才整组落下（对应一枚真事务的边界） */
    private class FakeSemesterTable(rows: List<Semester>) {
        val rows: MutableList<Semester> = rows.toMutableList()
        val courseCodes: MutableList<String> = mutableListOf(OTHER, TERM)
        private val pending = mutableListOf<SemesterRenameSaveStep>()
        var appliedSteps = 0
            private set

        fun enqueue(step: SemesterRenameSaveStep) {
            pending += step
        }

        /** 整组提交：排队期间任何一步抛在外面 ⇒ 这里一枚都不会落下 */
        fun commit() {
            val batch = pending.toList()
            pending.clear()
            batch.forEach { step ->
                when (step) {
                    is SemesterRenameSaveStep.ReassignCourses -> {
                        for (index in courseCodes.indices) {
                            if (courseCodes[index] == step.fromCode) courseCodes[index] = step.toCode
                        }
                    }

                    is SemesterRenameSaveStep.DeleteRowsForTermCode ->
                        rows.removeAll(rows.filter { it.termCode == step.termCode }.toSet())

                    is SemesterRenameSaveStep.WriteSemesterRow ->
                        rows += step.semester.copy(id = (rows.maxOfOrNull { it.id } ?: 0L) + 1L)
                }
                appliedSteps++
            }
        }
    }

    private fun semesterTableOf(vararg rows: Semester) = FakeSemesterTable(rows.toList())

    /**
     * 按生产的形状把计划跑一遍：**整组步骤进同一枚事务，commit 只发生一次**。
     *
     * @param throwAt 第几枚步骤抛（null = 不抛）。抛在 commit 之前 ⇒ 台账整组作废，库里什么都没有。
     */
    private fun runSemesterStepsInFakeTx(
        plan: SemesterRenameSavePlan,
        table: FakeSemesterTable,
        throwAt: Int?,
    ) {
        val steps = when (plan) {
            SemesterRenameSavePlan.Rejected -> emptyList()
            is SemesterRenameSavePlan.Apply -> plan.steps
        }
        try {
            steps.forEachIndexed { index, step ->
                table.enqueue(step)
                if (index + 1 == throwAt) error("第 ${index + 1} 枚步骤抛")
            }
            table.commit()
        } catch (_: IllegalStateException) {
            // 事务抛 ⇒ 回滚：这里什么都不提交，等价于 `withTransaction` 的 catch + rollback
        }
    }

    private fun permutations(items: List<Semester>): List<List<Semester>> {
        if (items.size <= 1) return listOf(items)
        val head = items.first()
        return permutations(items.drop(1)).flatMap { tail ->
            (0..tail.size).map { index -> tail.subList(0, index) + head + tail.subList(index, tail.size) }
        }
    }
}
