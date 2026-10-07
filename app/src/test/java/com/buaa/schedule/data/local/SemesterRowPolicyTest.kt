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

    private fun permutations(items: List<Semester>): List<List<Semester>> {
        if (items.size <= 1) return listOf(items)
        val head = items.first()
        return permutations(items.drop(1)).flatMap { tail ->
            (0..tail.size).map { index -> tail.subList(0, index) + head + tail.subList(index, tail.size) }
        }
    }
}
