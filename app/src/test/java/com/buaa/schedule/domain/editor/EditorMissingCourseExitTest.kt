package com.buaa.schedule.domain.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T335③：`EditorMissingCourseExit` 的表驱动逐格账（纯 JVM，无 Robolectric、无设备、不读钟）。
 *
 * 钉的是在册 #388 那一档的**四象限**：三枚布尔（数据到位 / 目标不在屏上 / 本页发起过删除）一共 2³ = 8 行，
 * 每一行都要落到四档里的一档，且**只有**「不是本页删的」那一档欠用户一句话。
 *
 * 为什么这一族账值得单独表驱动（而不是就在 MainActivity 里写两行 `if`）：
 * - 题眼是**两档都退栈、只有一档念话**。把「本页自己删的」那一档折进「也念一句已不在课表里」，
 *   就是对着用户亲手删掉的课谎报死因（`CourseEditorScreen` 的删除链成功那一路零句、失败那一路才写红条），
 *   而这句话在屏上会与首页那一句打脸；把它折成「不念」又把本卡要修的那一格放回静默。
 *   两半只能各钉一格。
 * - 退栈那一维必须与**基点逐格等价**（[退栈那一维与基点逐格等价] 那一格）：本卡加的是「念不念」这一维，
 *   不许顺手改「退不退」——那两条协程（这一档与删除链那一档）的认领次序是在册盘面，动它就是另一张卡。
 * - `dataLoaded` 排在判据第一位：数据没到位时「列表里没有」不是结论，只是还没读到（R5 F-41 的原意）。
 *   这一格的顺序若被扭掉，白帧那一页会在首帧就被弹掉，而课程下一秒就到。
 *
 * 变异自证的两侧都落在本文件：内核朝宽（把「课程不存在」折成存在 ⇒ [PopWithMessage] 那两格当场变红）与
 * 内核朝窄（把旗那一档判死成永不为真 ⇒ [PopWithoutMessage] 那格变红）都切得到体，不必靠注释自证。
 */
class EditorMissingCourseExitTest {

    private data class Cell(
        val label: String,
        val dataLoaded: Boolean,
        val courseMissing: Boolean,
        val deletionRequestedByThisEntry: Boolean,
        val expected: MissingCourseExitVerdict,
    )

    /** 8 行 = 三枚布尔的全部组合，没有第二把尺参与。 */
    private val table = listOf(
        // ── 题眼：目标没了、数据到位了，这一页自己有没有删过它就是两档 ──
        Cell(
            "数据到位 + 目标不在屏上 + 本页没删过 ⇒ 先念后退（在册 #388 那一档，组件行撞上刷新）",
            dataLoaded = true, courseMissing = true, deletionRequestedByThisEntry = false,
            expected = MissingCourseExitVerdict.PopWithMessage,
        ),
        Cell(
            "数据到位 + 目标不在屏上 + 本页自己删的 ⇒ 只退不念（念了就是谎报死因 + 同屏两句打脸）",
            dataLoaded = true, courseMissing = true, deletionRequestedByThisEntry = true,
            expected = MissingCourseExitVerdict.PopWithoutMessage,
        ),
        // ── 课还在：旗怎么摆都不影响这一档 ──
        Cell(
            "数据到位 + 课还在列表上 + 旗 false ⇒ 页面照常组合，不弹也不念",
            dataLoaded = true, courseMissing = false, deletionRequestedByThisEntry = false,
            expected = MissingCourseExitVerdict.CourseIsOnScreen,
        ),
        Cell(
            "数据到位 + 课还在列表上 + 旗 true（删除刚发起、新 uiState 还没落地的中间帧）⇒ 同上，一个字节都不动",
            dataLoaded = true, courseMissing = false, deletionRequestedByThisEntry = true,
            expected = MissingCourseExitVerdict.CourseIsOnScreen,
        ),
        // ── 数据没到位：另两枚布尔无论怎么摆都不出结论（判据第一位）──
        Cell(
            "数据没到位 + 列表里没有 ⇒ 不算结论，等 loading 翻面那一手（R5 F-41）",
            dataLoaded = false, courseMissing = true, deletionRequestedByThisEntry = false,
            expected = MissingCourseExitVerdict.DataNotReadyYet,
        ),
        Cell(
            "数据没到位 + 列表里没有 + 旗 true（删除在途又赶上首帧）⇒ 仍不许由这一档认领",
            dataLoaded = false, courseMissing = true, deletionRequestedByThisEntry = true,
            expected = MissingCourseExitVerdict.DataNotReadyYet,
        ),
        Cell(
            "数据没到位 + 列表里有（空列表也算没到位那一档的输入）⇒ 不动",
            dataLoaded = false, courseMissing = false, deletionRequestedByThisEntry = false,
            expected = MissingCourseExitVerdict.DataNotReadyYet,
        ),
        Cell(
            "数据没到位 + 列表里有 + 旗 true ⇒ 不动",
            dataLoaded = false, courseMissing = false, deletionRequestedByThisEntry = true,
            expected = MissingCourseExitVerdict.DataNotReadyYet,
        ),
    )

    @Test
    fun `逐格 三枚布尔到四档的表驱动账`() {
        assertEquals("表必须把 2³ 个输入组合数满（缺一行就是有一行没人钉）", 8, table.size)
        for (cell in table) {
            assertEquals(
                cell.label,
                cell.expected,
                EditorMissingCourseExit.verdictOf(
                    dataLoaded = cell.dataLoaded,
                    courseMissing = cell.courseMissing,
                    deletionRequestedByThisEntry = cell.deletionRequestedByThisEntry,
                ),
            )
        }
    }

    /**
     * 本卡的边界声明：只加「念不念」这一维，「退不退」与基点**逐格等价** ——
     * 基点那行是 `if (!uiState.loading && courseMissing) navController.popBackStack()`，
     * 也就是「数据到位且目标不在屏上」两真就弹，与旗无关。这一格松掉就是本卡偷偷改了退栈认领次序。
     */
    @Test
    fun `退栈那一维与基点逐格等价`() {
        for (cell in table) {
            val verdict = EditorMissingCourseExit.verdictOf(
                dataLoaded = cell.dataLoaded,
                courseMissing = cell.courseMissing,
                deletionRequestedByThisEntry = cell.deletionRequestedByThisEntry,
            )
            assertEquals(
                "${cell.label}：退栈那一维应当等于「数据到位 && 目标不在屏上」",
                cell.dataLoaded && cell.courseMissing,
                EditorMissingCourseExit.popsBackStack(verdict),
            )
        }
    }

    /**
     * 四档里只有一档欠一句话，而欠话的那一档**一定**退栈 —— 反过来不许出现
     * 「念了却把人留在白帧上」那一格（话与退栈之间没有挂起点，是接线那一侧的次序，形状归守卫钉）。
     */
    @Test
    fun `念话只有一档 且念话那一档必定退栈`() {
        val owes = MissingCourseExitVerdict.entries.filter {
            EditorMissingCourseExit.owesAMessage(it)
        }
        assertEquals(
            "四档里欠一句话的档数应当恰好一枚（多一枚 = 把本页自己删的那一档也念成被刷新移除；" +
                "少一枚 = 回到在册 #388 那句「一句话不说」）：\n" + owes.joinToString(),
            listOf(MissingCourseExitVerdict.PopWithMessage),
            owes,
        )
        assertTrue(
            "欠话的每一档都必须同时退栈：念完还把用户留在一页渲染成空的编辑器上，比静默更糟",
            owes.all { EditorMissingCourseExit.popsBackStack(it) },
        )
        // 「只退不念」那一档必须还在（它不是死档，也不是被合并掉的档）
        assertFalse(
            "PopWithoutMessage 那一档不该欠话：删除链成功那一路页面本来就一个字不念",
            EditorMissingCourseExit.owesAMessage(MissingCourseExitVerdict.PopWithoutMessage),
        )
        val popping = MissingCourseExitVerdict.entries.filter {
            EditorMissingCourseExit.popsBackStack(it)
        }
        assertEquals(
            "退栈的档数应当恰好两枚：\n" + popping.joinToString(),
            listOf(MissingCourseExitVerdict.PopWithoutMessage, MissingCourseExitVerdict.PopWithMessage),
            popping,
        )
    }

    /**
     * 死档检查（本仓口径：不许把某一档写成盘上到不了的那一等）：表里出现过的档必须等于全枚举，
     * 而 8 行输入里每一行都得有个档接着 —— 两头一起数，才既是「全覆盖」又是「零死档」。
     */
    @Test
    fun `四档全可达且没有一档没人认领`() {
        val reached = table.map { it.expected }.toSet()
        assertEquals(
            "表的期望档必须铺满全枚举（少一枚就是有一档在 2³ 输入里到不了 = 死档，" +
                "多一枚就是表里写了枚举中没有的档）：\n" +
                "reached=${reached.joinToString()} all=${MissingCourseExitVerdict.entries.joinToString()}",
            MissingCourseExitVerdict.entries.toSet(),
            reached,
        )
        assertEquals("四档应当正好四枚", 4, MissingCourseExitVerdict.entries.size)
        // 每一行输入都有档接住（verdictOf 不许对任何组合抛或返回 null 语义的兜底档）
        for (cell in table) {
            assertTrue(
                cell.label,
                EditorMissingCourseExit.verdictOf(
                    dataLoaded = cell.dataLoaded,
                    courseMissing = cell.courseMissing,
                    deletionRequestedByThisEntry = cell.deletionRequestedByThisEntry,
                ) in MissingCourseExitVerdict.entries,
            )
        }
    }
}
