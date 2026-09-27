package com.buaa.schedule.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * T131 表驱动单测：「只改这些周」那一次落库**该改哪几周**这一判（纯 JVM，零 android、零时钟）。
 *
 * 判据本体在 [ConflictShiftWeekScope.weeksToShift]，两串周次全部由调用点当参数递进来 ——
 * 组周与 target.weeks 都不是内核自己去课表里嗅的（本仓硬口径）。
 * "VM 那一步有没有真的用上它"由源码核对守卫 [ConflictShiftWeekScopeWiringGuardTest] 钉，
 * 本文件只钉判据本身。⚠️ 与 [ConflictWizardWeekScopeReachabilityTest] 一样，
 * 这里复跑的是**结论**，不是 Room、不是设备：**未装机**。
 *
 * 两侧都有格子（这一族的规矩）：
 * - 朝**更宽**扭（把 no-op 那三支改成照样返交集）⇒ ②③④⑤ 各档红：
 *   收窄在"整门课都在冲突里"那一格上本该什么都不做，返一份重排过的同款周次会因
 *   列表逐元素相等而翻真 `updateCourse` 那一判，凭空拆出一行（另发 id、清同组兄弟片段）；
 * - 朝**更窄**扭（拿组周直接当作用域，不交回 target）⇒ ④ 那一档红：
 *   组周是并查集闭包里全组两两重叠的**并集**，可能盖到 target 根本没排的周，
 *   照抄就等于给这门课凭空造出它没有的上课周。
 */
class ConflictShiftWeekScopeTest {

    private class Cell(
        val name: String,
        val groupWeeks: List<Int>,
        val targetWeeks: List<Int>,
        val expected: List<Int>,
        /** 期望判据把调用点那份**原样**送回（收窄是 no-op），而不是另造一份等值列表 */
        val verbatim: Boolean,
        val why: String,
    )

    /** 判据的返回必须是"写点直接可用的那串周次"，本表拿它当唯一读数 */
    private fun judge(cell: Cell): List<Int> =
        ConflictShiftWeekScope.weeksToShift(cell.groupWeeks, cell.targetWeeks)

    @Test
    fun `每一档都判对该收窄的收窄 该不动的原样不动`() {
        for (cell in table) {
            val actual = judge(cell)
            assertEquals(
                "档位「${cell.name}」：组周 ${cell.groupWeeks} × target ${cell.targetWeeks} " +
                    "应得 ${cell.expected} —— ${cell.why}",
                cell.expected,
                actual,
            )
            if (cell.verbatim) {
                // 逐元素相等（不是集合相等）：`updateCourse` 的第三判比的是 List 相等，
                // 一份"内容相同但重排/去过重"的返回值会把它翻真 ⇒ 凭空拆行
                assertSame(
                    "档位「${cell.name}」判成 no-op 却返了一份新列表：这一判在写点会翻真，" +
                        "整门课都在冲突里的那一格也要被拆成两行。${cell.why}",
                    cell.targetWeeks,
                    actual,
                )
            }
        }
    }

    /**
     * 真子集那一族（这一族红了就是"药没吃"）：判据被扭成"永远原样返回 target.weeks"
     * 就等于回到今天那记空枪 —— 按钮继续写着「只改这些周」，落库照样整行覆盖。
     */
    @Test
    fun `收窄确实排得出来 三档各自少改了周次`() {
        val narrowed = table.filterNot { it.verbatim }
        assertEquals(
            "表里「非 no-op」的档位应当恰好四格（连续段 / 单周 / 交集剔掉组周外的周 / 乱序去重），" +
                "少一格就是收窄那一侧被并进了 no-op 分支：" + narrowed.joinToString { it.name },
            4,
            narrowed.size,
        )
        narrowed.forEach { cell ->
            assertNarrowed(cell)
        }
    }

    private fun assertNarrowed(cell: Cell) {
        val result = judge(cell)
        // 每一档都必须"真的少几周"，否则它压根没在收窄
        if (result.size >= cell.targetWeeks.size) {
            throw AssertionError("档位「${cell.name}」没有真的收窄：结果 $result 不比 target ${cell.targetWeeks} 少")
        }
        assertEquals("档位「${cell.name}」的作用域应当就是交集", cell.expected, result)
    }

    /** no-op 那三支（④⑤⑥⑦）：不收窄 ⇒ 写点那一判保持恒假 ⇒ 走今天那条整行覆盖的路 */
    @Test
    fun `不收窄的三档一律原样回传 连顺序都不重排`() {
        table.filter { it.verbatim }.forEach { cell ->
            assertSame("档位「${cell.name}」必须原样回传：${cell.why}", cell.targetWeeks, judge(cell))
        }
    }

    private companion object {
        val full = (1..16).toList()

        val table = listOf(
            // ① 组周 == target.weeks：整门课都在这组冲突里 ⇒ 收窄什么都不省 ⇒ 不拆行
            Cell(
                name = "组周 == target.weeks",
                groupWeeks = full,
                targetWeeks = full,
                expected = full,
                verbatim = true,
                why = "整门课的周次都在冲突里，挪整门课就是挪全部冲突周；拆行只是白拆",
            ),
            // ② 组周 ⊊ target.weeks：本卡的靶子（可达性由 ReachabilityTest 用真码验）
            Cell(
                name = "组周 ⊊ target.weeks",
                groupWeeks = listOf(3, 4, 5),
                targetWeeks = full,
                expected = listOf(3, 4, 5),
                verbatim = false,
                why = "只有 3-5 周在冲突里：其余 13 周必须留在原节次上",
            ),
            // ③ 单周（调课只撞一周，是最常见的格）
            Cell(
                name = "组周只有一周",
                groupWeeks = listOf(7),
                targetWeeks = full,
                expected = listOf(7),
                verbatim = false,
                why = "只改第 7 周 ⇒ 其余 15 周不动",
            ),
            // ④ 组周盖到 target 外面（并查集闭包并集）：只能取交集，不许给这门课造出没有的周
            Cell(
                name = "组周 ⊄ target.weeks 且交集恰为 target 全部",
                groupWeeks = full,
                targetWeeks = (1..8).toList(),
                expected = (1..8).toList(),
                verbatim = true,
                why = "组里别的课撞在 9-16，target 只排 1-8 ⇒ 交集=它自己，不收窄也不扩周",
            ),
            // ④b 同上一档的"部分落在外面"版：交集仍比 target 少 ⇒ 要收窄，且外面的周必须剔掉
            Cell(
                name = "组周一部分在 target 外 交集仍更小",
                groupWeeks = listOf(3, 4, 20, 21),
                targetWeeks = full,
                expected = listOf(3, 4),
                verbatim = false,
                why = "20/21 是组里别的课的周次，塞进 target 就凭空多出两周",
            ),
            // ⑤ 组周为空（真码组不出，兜底档）：不收窄，别写出一份空周次的课
            Cell(
                name = "组周为空",
                groupWeeks = emptyList(),
                targetWeeks = full,
                expected = full,
                verbatim = true,
                why = "空作用域会被 normalize 判非法而整趟什么都不写，比挪整门课更坏",
            ),
            // ⑥ 两串完全不相交（另一枚兜底档）
            Cell(
                name = "组周与 target 不相交",
                groupWeeks = (9..12).toList(),
                targetWeeks = (1..8).toList(),
                expected = (1..8).toList(),
                verbatim = true,
                why = "同上：交集为空就退回今天的行为，不造空周次课程",
            ),
            // ⑦ target 自己就是空的（脏数据）
            Cell(
                name = "target 无周次",
                groupWeeks = listOf(3),
                targetWeeks = emptyList(),
                expected = emptyList(),
                verbatim = true,
                why = "没有周次可收窄，原样送回，让写点去撞它自己的归一化守卫",
            ),
            // ⑧ 组周乱序且带重复：交集必须排序去重（写进库的那一串要能过 normalize 的账）
            Cell(
                name = "组周乱序带重复",
                groupWeeks = listOf(5, 3, 5, 4, 3),
                targetWeeks = full,
                expected = listOf(3, 4, 5),
                verbatim = false,
                why = "组周的去重与排序不能信上游，交集自己排好",
            ),
            // ⑨ target.weeks 乱序而覆盖全部组周 ⇒ 仍判 no-op，且**不许**把重排后的串送回
            Cell(
                name = "target 乱序但没一周可省",
                groupWeeks = listOf(1, 2, 3),
                targetWeeks = listOf(3, 1, 2),
                expected = listOf(3, 1, 2),
                verbatim = true,
                why = "那一判是 List 逐元素相等：返一份排序过的同款周次会把它翻真、凭空拆行",
            ),
        )
    }
}
