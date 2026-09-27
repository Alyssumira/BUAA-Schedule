package com.buaa.schedule.ui

import com.buaa.schedule.data.repository.GroupAppearanceEdit
import com.buaa.schedule.domain.model.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T128：把「这一次保存该不该压一枚『编辑课程』撤销条目」钉成表驱动单测（纯 JVM）。
 *
 * 判据本体是 [updateUndoWorthRecording]，完整账写在 `UndoUpdateAdmission.kt` 段首。
 * 收的是 `docs/derived-field-audit.md` §9.5② 那一格：旧压栈点写完库**无条件**压一条、
 * 不比 before/after ⇒ 同值重写也压。T122 当时判 deferred，理由是"这一判在压栈点算不出来"，
 * 因为组外观那一支返回 `Unit`；本卡（前置）让它交出 [GroupAppearanceEdit] 之后，
 * 这一判四把维度都齐了。
 *
 * 表 ① 那两格是卡面点名的必答题：
 * - **主行同值、组里确实改了兄弟行 ⇒ 条目不许丢**（把它判成"没动"就是静默丢掉一次真编辑的撤销记录，
 *   T122 算过这笔账：比现状更贵）；
 * - **四把维度全空 ⇒ 不许压**（那是一条净效果为零的假"已撤销：编辑课程"）。
 *
 * 两侧都要有格子：判据**朝宽扭**（比如 `group == null ||` 写成 `|| true`，或干脆恒 true）
 * 红在表 ① 的"不许压"那几格；**朝窄扭**（少算一维，例如漏掉 `removedCount > 0`）
 * 红在表 ② 对应的单独维度格。每一格失败消息都带那一格的四个读数。
 *
 * 档位：`:app` 的 JVM 测试面只有 `junit`，本文件钉的是**判据**；
 * "VM 那一支有没有真的按它闸"由源码核对守卫 `UndoUpdateEntryGuardTest` 第 ①⑥ 层钉（零设备）。
 */
class UndoUpdateAdmissionTest {

    // ─────────────── ① 卡面点名的两格 ───────────────

    @Test
    fun `主行同值而组里确实改了兄弟行 撤销条目不许丢`() {
        val original = row(id = 11L, colorIndex = 2)
        val sibling = row(id = 12L, colorIndex = 2)
        val group = GroupAppearanceEdit(beforeRows = listOf(sibling))
        assertTrue(
            "管理页给整组换色：主行本来就是那个色（original == after、没拆行），" +
                "但组那一支改掉了兄弟行 ⇒ 这一趟动过东西，条目必须照压。" +
                "红了 = 判据朝窄扭成「主行同值就不压」，那正是 T122 驳回过的第二种读法：" +
                "一次真改了兄弟行的编辑，撤销记录被静默丢掉，比现状更贵。\n" +
                "  original=${describe(original)} / after=${describe(original)} / savedId=${original.id} / " +
                "removedCount=0 / 组交回 ${group.beforeRows.size} 行",
            updateUndoWorthRecording(
                original = original,
                after = original,
                savedId = original.id,
                removedCount = 0,
                group = group,
            ),
        )
    }

    @Test
    fun `四把维度全空 不许压一枚假的已撤销编辑课程`() {
        val original = row(id = 11L, colorIndex = 2)
        assertFalse(
            "主行逐字段同值 + 没拆行 + 没清掉兄弟片段 + 组那一支交回空表 ⇒ 这一趟什么都没写到，" +
                "不许压条目。红了 = 判据朝宽扭（比如把「跑过组写」当成「动过东西」），" +
                "那枚净效果为零的条目又回栈里躺着 —— 用户点掉唯一那颗「撤销」时捞的就是它。\n" +
                "  复算：grep -n \"updateUndoWorthRecording\" app/src/main/java/com/buaa/schedule/ui/UndoUpdateAdmission.kt",
            updateUndoWorthRecording(original, original, original.id, 0, GroupAppearanceEdit(emptyList())),
        )
        assertFalse(
            "同一格的第二种落点：这一次压根没走组那一支（group == null，例如首页那记改时间的编辑之外" +
                "所有 applyToGroup=false 的路径）⇒ 仍只看主行那一维",
            updateUndoWorthRecording(original, original, original.id, 0, null),
        )
    }

    // ─────────────── ② 四把维度各自独立：每一维单亮都得压 ───────────────

    @Test
    fun `四把维度各自独立 每一维单亮都得压`() {
        for (cell in positiveCells) {
            assertTrue(
                "判据格子「${cell.name}」应当判成要压条目，实到 false = 那一维被漏算了。\n" +
                    "  ${cell.reading}\n" +
                    "红了就回到 UndoUpdateAdmission.kt 数一遍那四把维度：savedId / removedCount / " +
                    "主行 before-after / 组那一支交回的行",
                updateUndoWorthRecording(
                    original = cell.original,
                    after = cell.after,
                    savedId = cell.savedId,
                    removedCount = cell.removedCount,
                    group = cell.group,
                ),
            )
        }
    }

    /**
     * 反向表：只有"四把维度全空"这一族才允许不压。
     *
     * 特别注意 `savedId` 那一格：拆行**另发一行**时 `afterId != before.id`，
     * 这时候两个值对象逐字段一样也说明不了库里没动（T122 第 ② 层那三条真凭据里的第二条）。
     */
    @Test
    fun `只有四把维度全空那一格不压 其余组合一律压`() {
        val original = row(id = 11L)
        for (cell in negativeCells) {
            assertEquals(
                "判据格子「${cell.name}」应当判成 ${cell.expected}：${cell.why}\n  ${cell.reading}",
                cell.expected,
                updateUndoWorthRecording(
                    original = cell.original,
                    after = cell.after,
                    savedId = cell.savedId,
                    removedCount = cell.removedCount,
                    group = cell.group,
                ),
            )
        }
    }

    // ─────────────── ③ 早退不丢任何可复原的东西：与撤销落库那三块结构对齐 ───────────────

    @Test
    fun `同值条目本来就复原不出新东西 早退不是丢记录`() {
        val original = row(id = 11L)
        assertFalse(
            "把「库里那一行不是归一化后的形状」这一族也算进来：同值条目的撤销走的是 " +
                "`CourseConstraints.normalize(action.before)` 原地回写（见 UndoUpdateEntryGuardTest 第 ⑤ 层），" +
                "回写的就是刚刚写进去的那一版 ⇒ 净效果为零。" +
                "所以这一格判成不压，丢掉的不是「一次可复原的编辑」，是一枚白占栈位的假记录",
            updateUndoWorthRecording(original, original, original.id, 0, null),
        )
        assertTrue(
            "同一族的反面：内容一样但**换了行号**（拆行另发），撤销要删掉那一行、补回 before 那一行，" +
                "净效果不为零 ⇒ 必须压",
            updateUndoWorthRecording(original, original, savedId = 99L, removedCount = 0, group = null),
        )
    }

    // ─────────────── ④ 组那一支的结论只读它自己交回的表 ───────────────

    @Test
    fun `组那一支动没动只读它交回的那张表 不许在调用点再判一次`() {
        val original = row(id = 11L)
        val oneRow = GroupAppearanceEdit(beforeRows = listOf(row(id = 12L)))
        val twoRows = GroupAppearanceEdit(beforeRows = listOf(row(id = 12L), row(id = 13L)))
        val readings = listOf(
            GroupAppearanceEdit(emptyList()),
            oneRow,
            twoRows,
        ).map {
            updateUndoWorthRecording(original, original, original.id, 0, it)
        }
        assertEquals(
            "空表 / 一行 / 两行 三档的判定必须是 假 · 真 · 真（判据只看那张表的空与非空，" +
                "行数不参与 —— 多算一枚「改了几行才够数」的阈值就是第二把尺子）",
            listOf(false, true, true),
            readings,
        )
    }

    // ─────────────── 格子表 ───────────────

    private class Cell(
        val name: String,
        val original: Course,
        val after: Course,
        val savedId: Long,
        val removedCount: Int,
        val group: GroupAppearanceEdit?,
        val reading: String,
    )

    private val positiveCells = listOf(
        Cell(
            name = "主行自己变了（改时间/改名/改颜色都算）",
            original = row(id = 11L, colorIndex = 2),
            after = row(id = 11L, colorIndex = 5),
            savedId = 11L,
            removedCount = 0,
            group = null,
            reading = "savedId=11 == original.id / removedCount=0 / 组=null / 只有主行那一维亮",
        ),
        Cell(
            name = "拆行另发一行（before/after 逐字段一样也算动过）",
            original = row(id = 11L),
            after = row(id = 11L),
            savedId = 12L,
            removedCount = 0,
            group = null,
            reading = "savedId=12 != original.id=11",
        ),
        Cell(
            name = "部分周次拆行顺手清掉了同组兄弟片段（R5 F-35）",
            original = row(id = 11L),
            after = row(id = 11L),
            savedId = 11L,
            removedCount = 2,
            group = null,
            reading = "removedCount=2",
        ),
        Cell(
            name = "组那一支改掉了兄弟行、主行同值",
            original = row(id = 11L),
            after = row(id = 11L),
            savedId = 11L,
            removedCount = 0,
            group = GroupAppearanceEdit(listOf(row(id = 12L))),
            reading = "组交回 1 行",
        ),
        Cell(
            name = "两维同时亮（主行改了 + 组也改了）",
            original = row(id = 11L, colorIndex = 2),
            after = row(id = 11L, colorIndex = 6),
            savedId = 11L,
            removedCount = 1,
            group = GroupAppearanceEdit(listOf(row(id = 12L))),
            reading = "四维里亮了三样",
        ),
    )

    private class NegativeCell(
        val name: String,
        val original: Course,
        val after: Course,
        val savedId: Long,
        val removedCount: Int,
        val group: GroupAppearanceEdit?,
        val expected: Boolean,
        val why: String,
        val reading: String,
    )

    private val negativeCells = listOf(
        NegativeCell(
            name = "反复点当前已选中那块色板：全等 + 组交回空表",
            original = row(id = 11L, colorIndex = 2),
            after = row(id = 11L, colorIndex = 2),
            savedId = 11L,
            removedCount = 0,
            group = GroupAppearanceEdit(emptyList()),
            expected = false,
            why = "§9.5② 那一枚 no-op 条目，本卡收的就是它",
            reading = "四把维度全空",
        ),
        NegativeCell(
            name = "没走组那一支、主行同值",
            original = row(id = 11L),
            after = row(id = 11L),
            savedId = 11L,
            removedCount = 0,
            group = null,
            expected = false,
            why = "group == null 不等于「动过」：没跑过那一支就是那一支什么都没写",
            reading = "savedId 同、removed 空、主行同值、group=null",
        ),
        NegativeCell(
            name = "主行同值 + removedCount=0 + 组交回空表，但 after 是同一份内容的新对象",
            original = row(id = 11L, weeks = (1..16).toList()),
            after = row(id = 11L, weeks = (1..16).toList()),
            savedId = 11L,
            removedCount = 0,
            group = GroupAppearanceEdit(emptyList()),
            expected = false,
            why = "判的是值对象的逐字段相等（Course 是 data class），不是引用同一",
            reading = "两个等价实例",
        ),
    )

    private fun row(
        id: Long,
        colorIndex: Int = 1,
        weeks: List<Int> = (1..16).toList(),
    ): Course = Course(
        id = id,
        name = "高等数学",
        teacher = "张老师",
        location = "主楼",
        campus = "学院路",
        dayOfWeek = 1,
        periods = listOf(1, 2),
        weeks = weeks,
        colorIndex = colorIndex,
        sourceGroupKey = "G-1",
        semesterCode = "2026-2027-1",
        credit = 3.0,
    )

    private fun describe(course: Course): String =
        "id=${course.id}, colorIndex=${course.colorIndex}, name=${course.name}, manual=${course.isManualOverride}"
}
