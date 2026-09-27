package com.buaa.schedule.data.repository

import com.buaa.schedule.domain.model.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * T139：把「撤销一条『编辑课程』条目时，**主行**那一格该怎么复原」钉成表驱动单测（纯 JVM）。
 *
 * 判据本体是 [mainRowUndoDisposition]，完整账（含"为什么主行选换新 id 重插、兄弟行那一族却选明确不动"
 * 与"另一支为什么不选"）写在 `MainRowUndoPolicy.kt` 段首。
 *
 * 这张表量四件事：
 * 1. **两档都会落库**，区别只在落在哪一行 —— 这一域里没有"什么都不动"那一档；
 * 2. **"读不到"与"那一 id 上已是别的课"同处置**（本卡那道判断题的答案，理由第 4 条）——
 *    表 ① 里那两格必须给出**同一个**结论，谁把它们拆成两支（例如"被占 ⇒ 不动"）表 ③ 当场红；
 * 3. **`current == null` 那一档不参与第二枚参数** —— 行都没读到，"是谁的行"无从谈起（表 ② 两格）；
 * 4. **结论域与兄弟行那一族必须不同名** —— 两枚内核形状相同、语义反向，撞名就是把判据用反（表 ④）。
 *
 * 档位（如实写明）：`:app` 的 JVM 测试面只有 `junit`，**没有 Robolectric、也没有能在 JVM 上跑 Room
 * 的缝**，所以"库里那一行今天到底是谁的"被抽成纯函数来钉。SQLite 级「真重导一次学期、改号之后再点
 * 撤销看那一格回没回」**没做**（红线零设备）。
 *
 * ⚠️ 本文件**钉不到**"调用点那枚布尔是按哪两枚参照物量的"（内核只收一枚 Boolean）——
 * 那一半在源码核对守卫 `UndoUpdateEntryGuardTest` 第 ⑨ 层：主行那一趟真的读了这道校验、
 * 参照物逐字是 `normalize(action.after)` 与 `action.before` 两枚、写回与重插两支各自可达、
 * 四步次序仍是 1→2→3→4。**只拿 before 当尺子**那一型（形状全对、内容错）在第 ⑨ 层红，不在这里。
 *
 * 两侧都要有格子：**朝宽扭**一次（把 `!stillThisEditsRow -> RestoreAsNewRow` 那一档摘掉，
 * 也就是退回本卡治的那道病 ⇒ 表 ① 里"被别的课占了"那格红）；**朝窄扭**一次
 * （把 `current == null -> RestoreAsNewRow` 写成 `RestoreInPlace` ⇒ 表 ② 整表红，
 * 更糟的是撤销会去覆盖一个根本不存在的行 / 把"读不到"翻成原地回写）。
 */
class MainRowUndoPolicyTest {

    // ─────────────── ① 逐格：四枚输入组合各自的处置 ───────────────

    @Test
    fun `四枚输入组合的处置逐格钉住`() {
        for (cell in cells) {
            assertEquals(
                "判据格子「${cell.name}」：${cell.why}\n  ${cell.reading}\n" +
                    "复算：grep -n \"current == null\\|stillThisEditsRow\" " +
                    "app/src/main/java/com/buaa/schedule/data/repository/MainRowUndoPolicy.kt",
                cell.expected,
                mainRowUndoDisposition(cell.current, cell.stillThisEditsRow),
            )
        }
    }

    // ─────────────── ② 那一行读不到 ⇒ 两枚参数里只剩一枚说话 ───────────────

    @Test
    fun `那一行读不到时第二枚参数不参与判定 两格都得是换新id重插`() {
        val givenTrue = mainRowUndoDisposition(null, true)
        val givenFalse = mainRowUndoDisposition(null, false)
        assertEquals(
            "库里没有那一行时，调用点递进来的第二枚事实**什么都没资格说**（行都没有，谈什么" +
                "「是谁的行」）。两格必须同为 RestoreAsNewRow —— 这一档正是本卡落地之前唯一活着的那一支：" +
                "读不到 ⇒ 换新 id 插回来并把提醒按新 id 重挂。" +
                "\n  (null, true)  实到 $givenTrue" +
                "\n  (null, false) 实到 $givenFalse" +
                "\n红了 = 判据长出了第二把尺子（「没读到」被翻成另一档，或更糟被翻成 RestoreInPlace ⇒ " +
                "撤销去原地回写一个根本不存在的行）",
            listOf(MainRowUndoDisposition.RestoreAsNewRow, MainRowUndoDisposition.RestoreAsNewRow),
            listOf(givenTrue, givenFalse),
        )
    }

    /**
     * 判断题的答案本体：「那一行读回来了、却已是别的课」与「那一行读不到」必须**同处置**。
     *
     * 旧写法只问了读没读到 ⇒ `current != null` 被当成"这一行归我"，而 id 换人以后这个"归我"
     * 根本没有依据：教务整学期重导是 `deleteBySemester` 之后重插，`before.id` 完全可能落到
     * 一门无关的课头上，原地 `update` 就是把那门课改成了快照那一版 —— 而这一坏没有任何一条
     * 撤销记录撤得回来（条目里没有它的改前值）。
     */
    @Test
    fun `读不到与被别的课占了同处置 都是换新id重插`() {
        val missing = mainRowUndoDisposition(null, true)
        val occupiedByOther = mainRowUndoDisposition(row(id = 12L, name = "体育"), false)
        assertEquals(
            "就本仓能观测到的事实而言，「那一 id 上没行」与「那一 id 上是别人的行」是同一件事：" +
                "我要复原的那一行不在它应在的位置上 ⇒ 必须同处置（换新增）。" +
                "两枚事实给出两种处置，等于让「是否恰好有人换号占位」这一枚无关细节决定" +
                "用户看不看得到那门课。\n  missing=$missing / occupied=$occupiedByOther",
            missing,
            occupiedByOther,
        )
        assertNotEquals(
            "被别的课占了那一档**不许**是原地回写：写回的对象是快照里的 `before`，落点却是别人那一行" +
                " ⇒ 覆盖无关课程，本卡治的就是这一格",
            MainRowUndoDisposition.RestoreInPlace,
            occupiedByOther,
        )
    }

    // ─────────────── ③ 结论域只有两档，而且没有"什么都不动"那一档 ───────────────

    /**
     * 与兄弟行那一族（[GroupRowUndoDisposition]，三档、两档是明确的不动）对照着钉：
     * 两枚内核形状相同、结论域**反向**，所以各自一枚，不许合并、不许改名去对齐。
     */
    @Test
    fun `这一族只有两档 而且两档都要落库`() {
        assertEquals(
            "结论域必须是「原地回写改前版 / 换新 id 重插 + 重挂提醒」两档。" +
                "长出 Skip 之类第三档 = 本卡那笔「一次被吞掉的撤销」的账被推翻（主行不动的净效果是" +
                "用户改的那门课停在改完之后那一版、而这条撤销记录已被消费掉，再点也没有），" +
                "要推翻它得先带着 SQLite 级或装机级证据来",
            listOf("RestoreInPlace", "RestoreAsNewRow"),
            MainRowUndoDisposition.entries.map { it.name },
        )
        assertEquals(
            "兄弟行那一族仍是三档（`SkipMissing` / `SkipOccupiedByOtherCourse` 那两档是明确的不动）：" +
                "两族结论域若被并成一枚枚举，段首那四条不对称的代价账就得重判",
            listOf("Restore", "SkipMissing", "SkipOccupiedByOtherCourse"),
            GroupRowUndoDisposition.entries.map { it.name },
        )
        val intersection = MainRowUndoDisposition.entries.map { it.name }
            .intersect(GroupRowUndoDisposition.entries.map { it.name }.toSet())
        assertEquals(
            "两族结论域**不得重名**（重名 = 读调用点时分不清「跳过」与「换位置复原」，判据就用反了）：" +
                "\n  交集实到 $intersection",
            emptyList<String>(),
            intersection.toList(),
        )
    }

    // ─────────────── ④ RestoreInPlace 只认"这一行还是这次编辑留下的"，与内容无关 ───────────────

    @Test
    fun `原地回写那一档只认调用点量出来的那一枚布尔 与内容差多少无关`() {
        val before = row(id = 12L, name = "高等数学", periods = listOf(1, 2))
        // 库里那一行已经是**改完之后**那一版（名称/节次都变了）—— 内容不同恰恰是要复原的理由，
        // 调用点按 normalize(after) 量出来仍是"这次编辑留下的行" ⇒ 原地回写。
        val afterInDb = before.copy(name = "高级语言程序设计", periods = listOf(3, 4))
        assertEquals(
            "同一枚 id、库里是改完之后那一版 ⇒ 这才是最常见的撤销：原地回写 before，" +
                "提醒本来就挂在这一行上，不需要也不应该重挂（换新 id 那一手才会把提醒搬走）。" +
                "\n  before=${before.name}${before.periods} / 读回来=${afterInDb.name}${afterInDb.periods}",
            MainRowUndoDisposition.RestoreInPlace,
            mainRowUndoDisposition(afterInDb, true),
        )
        assertEquals(
            "反过来：调用点量出来的仍是 true 时，读回来的那一行与快照**逐字段相等**也照样原地回写 —— " +
                "「这次编辑到底动没动东西」是压栈那一判的事（`updateUndoWorthRecording`），" +
                "本内核不许在这里再比一遍内容、把同值条目翻成「另插一行」",
            MainRowUndoDisposition.RestoreInPlace,
            mainRowUndoDisposition(before.copy(), true),
        )
        assertEquals(
            "id 相同恰恰是最危险的地方：读回来的那一行内容与快照无关（调用点量出来的 false）⇒ " +
                "一律不许写在那一行上",
            MainRowUndoDisposition.RestoreAsNewRow,
            mainRowUndoDisposition(afterInDb, false),
        )
    }

    // ─────────────── 格子表 ───────────────

    private class Cell(
        val name: String,
        val current: Course?,
        val stillThisEditsRow: Boolean,
        val expected: MainRowUndoDisposition,
        val why: String,
        val reading: String,
    )

    private val cells = listOf(
        Cell(
            name = "那一行还在、而且仍是这次编辑留下的那一行（原地改写那一支 / 拆行那一支库里就是 before）",
            current = row(id = 12L),
            stillThisEditsRow = true,
            expected = MainRowUndoDisposition.RestoreInPlace,
            why = "这一档写回原来那一行：按 id 原地回写改前版，提醒本来就挂着，无需重挂",
            reading = "current=id12 / stillThisEditsRow=true",
        ),
        Cell(
            name = "那一行还在、但 id 上已经是别的课（整学期重导换号以后恰好有人占了这个号）",
            current = row(id = 12L, name = "体育"),
            stillThisEditsRow = false,
            expected = MainRowUndoDisposition.RestoreAsNewRow,
            why = "按 id 盲写会把一门无关的课改成快照那一版，而那一坏没有任何撤销记录撤得回来 ⇒ 换新 id",
            reading = "current=id12 / stillThisEditsRow=false",
        ),
        Cell(
            name = "整学期重导以后那一行没了（旧 id 随 deleteBySemester 一起消失）",
            current = null,
            stillThisEditsRow = true,
            expected = MainRowUndoDisposition.RestoreAsNewRow,
            why = "本卡落地之前唯一活着的那一支：insertWithReminders 把课插成新 id、提醒按新 id 重挂",
            reading = "current=null / stillThisEditsRow=true（不参与判定）",
        ),
        Cell(
            name = "用户在这之后自己删掉了那门课",
            current = null,
            stillThisEditsRow = false,
            expected = MainRowUndoDisposition.RestoreAsNewRow,
            why = "与上一格同处置：主行是这次编辑的靶子、屏幕上用户正看着那一格，不重插就是整门消失；" +
                "兄弟行那一族才判成不动（两族代价不对称，账在 MainRowUndoPolicy.kt 段首第三节）",
            reading = "current=null / stillThisEditsRow=false（不参与判定）",
        ),
    )

    private fun row(id: Long, name: String = "高等数学", periods: List<Int> = listOf(1, 2)): Course = Course(
        id = id,
        name = name,
        teacher = "张老师",
        location = "主楼",
        campus = "学院路",
        dayOfWeek = 1,
        periods = periods,
        weeks = (1..16).toList(),
        colorIndex = 1,
        sourceGroupKey = "G-1",
        semesterCode = "2026-2027-1",
        credit = 3.0,
    )
}
