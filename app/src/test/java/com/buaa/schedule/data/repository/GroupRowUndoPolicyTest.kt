package com.buaa.schedule.data.repository

import com.buaa.schedule.domain.model.Course
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T137：把「撤销一条『编辑课程』条目时，组那一支改掉的兄弟行该怎么复原」钉成表驱动单测（纯 JVM）。
 *
 * 判据本体是 [groupRowUndoDisposition]，完整账（含"为什么这一族选跳过、主行那一族却选重插"
 * 与"另一支为什么不选"）写在 `GroupRowUndoPolicy.kt` 段首。
 *
 * 这张表量三件事：
 * 1. **只有「那一行还在、且仍是同一门课」才写库** —— 另外两档都是明确的不动；
 * 2. **`current == null` 那一档不参与第二枚参数** —— 行都没读到，"是不是同一门课"无从谈起，
 *    也就绝不该把"没读到"翻成"被别的课占了"（表 ② 里那两格：同一样 null，
 *    `sameCourseAtId` 给真是跳过、给假也是跳过，两格都得是 SkipMissing）；
 * 3. **这一族只有三档** —— 第四档「读不到就重插」（主行那一手）本卡判成不选，
 *    枚举行里压根没有它；谁把它加回来，这一格先红（表 ③）。
 *
 * 档位（如实写明）：`:app` 的 JVM 测试面只有 `junit`，**没有 Robolectric、也没有能在 JVM 上跑 Room
 * 的缝**，所以"库里那一行今天到底在不在"被抽成纯函数来钉。SQLite 级「真重导一次学期、再点撤销看
 * 那一格颜色回没回」**没做**（红线零设备），那一档由源码核对守卫 `UndoUpdateEntryGuardTest`
 * 第 ⑦⑧ 层（条目带得回 / 按 id 读 / 读的次序）代一半、剩下的一半仍然欠着。
 *
 * 两侧都要有格子：**朝宽扭**一次（把 `!sameCourseAtId -> SkipOccupiedByOtherCourse` 那一档摘掉
 * ⇒ 表 ① 里"被别的课占用"那格红）；**朝窄扭**一次（`current == null -> SkipMissing` 写成
 * `Restore`，也就是让撤销去覆盖一个根本不存在的 id ⇒ 表 ② 整表红）。
 */
class GroupRowUndoPolicyTest {

    // ─────────────── ① 逐格：四枚输入组合各自的处置 ───────────────

    @Test
    fun `四枚输入组合的处置逐格钉住`() {
        for (cell in cells) {
            assertEquals(
                "判据格子「${cell.name}」：${cell.why}\n  ${cell.reading}\n" +
                    "复算：grep -n \"current == null\\|sameCourseAtId\" " +
                    "app/src/main/java/com/buaa/schedule/data/repository/GroupRowUndoPolicy.kt",
                cell.expected,
                groupRowUndoDisposition(cell.current, cell.sameCourseAtId),
            )
        }
    }

    // ─────────────── ② 那一行读不到 ⇒ 两枚参数里只剩一枚说话 ───────────────

    @Test
    fun `那一行读不到时 sameCourseAtId 不参与判定 两格都得是跳过`() {
        val givenTrue = groupRowUndoDisposition(null, true)
        val givenFalse = groupRowUndoDisposition(null, false)
        assertEquals(
            "库里没有那一行时，调用点递进来的第二枚事实**什么都没资格说**（行都没有，" +
                "谈什么「是不是同一门课」）。两格必须同为 SkipMissing：" +
                "\n  (null, true)  实到 $givenTrue" +
                "\n  (null, false) 实到 $givenFalse" +
                "\n红了 = 判据长出了第二把尺子（「没读到」被翻成「被别的课占了」，" +
                "或更糟被翻成 Restore ⇒ 撤销去按 id 覆盖一个根本不存在的行）",
            listOf(GroupRowUndoDisposition.SkipMissing, GroupRowUndoDisposition.SkipMissing),
            listOf(givenTrue, givenFalse),
        )
    }

    /**
     * 「兄弟片段读不到就重插」那一支（照抄主行那一手）本卡**判成不选**，理由四条都在
     * `GroupRowUndoPolicy.kt` 段首。这里钉的是它的**形状**：判据的结论域里没有"插"这一档 ——
     * 谁要往那一支走，得先给这一枚枚举加一档，那一格当场红。
     */
    @Test
    fun `这一族只有三档 没有第四档重插`() {
        assertEquals(
            "结论域必须是「按 id 回写 / 读不到就跳过 / 被别的课占了就跳过」三档。" +
                "长出 Insert 之类第四档 = 本卡那笔代价账被推翻（重插的最坏结果是屏幕上多一行重复课、" +
                "或者为一条没进条目的提醒插出个孤儿行），要推翻它得先带着 SQLite 级或装机级证据来",
            listOf("Restore", "SkipMissing", "SkipOccupiedByOtherCourse"),
            GroupRowUndoDisposition.entries.map { it.name },
        )
    }

    // ─────────────── ③ Restore 那一档读的是"那一行本身"，不是快照的副本 ───────────────

    @Test
    fun `Restore 那一档只认读回来的那一行 与内容无关`() {
        val snapshot = row(id = 12L, colorIndex = 3)
        // 库里那一行**已经**是新色（组写留下的）—— 内容不同恰恰是要复原的理由，不许据此判成"别的课"
        val sameCourseDifferentColor = row(id = 12L, colorIndex = 7)
        assertEquals(
            "同一门课换了颜色还叫同一门课：那一 id 上的行与快照内容不同（正是要撤掉的那一改）时，" +
                "判据不许因此翻成 SkipOccupiedByOtherCourse —— " +
                "「是不是同一门课」由调用点按 ImportPlanner.courseKey 量（不含颜色维），本内核只路由。" +
                "\n  快照 colorIndex=3 / 读回来的 colorIndex=7",
            GroupRowUndoDisposition.Restore,
            groupRowUndoDisposition(sameCourseDifferentColor, true),
        )
        assertEquals(
            "反过来：读回来的那一行是**别的课**（调用点量出来的 false）⇒ 一律不许写，" +
                "哪怕它的 id 与快照相同（id 相同恰恰是危险的地方）",
            GroupRowUndoDisposition.SkipOccupiedByOtherCourse,
            groupRowUndoDisposition(snapshot.copy(name = "线性代数"), false),
        )
    }

    // ─────────────── 格子表 ───────────────

    private class Cell(
        val name: String,
        val current: Course?,
        val sameCourseAtId: Boolean,
        val expected: GroupRowUndoDisposition,
        val why: String,
        val reading: String,
    )

    private val cells = listOf(
        Cell(
            name = "那一行还在、而且仍是同一门课",
            current = row(id = 12L),
            sameCourseAtId = true,
            expected = GroupRowUndoDisposition.Restore,
            why = "这一档才写库：按 id 原地回写快照那一版（提醒本来就挂在这一行上，不用重挂）",
            reading = "current=id12 / sameCourseAtId=true",
        ),
        Cell(
            name = "那一行还在、但 id 上已经是别的课",
            current = row(id = 12L, name = "体育"),
            sameCourseAtId = false,
            expected = GroupRowUndoDisposition.SkipOccupiedByOtherCourse,
            why = "按 id 盲写会把一门无关的课改成快照那一版 —— 与 afterId 那道守卫同尺（courseKey）",
            reading = "current=id12 / sameCourseAtId=false",
        ),
        Cell(
            name = "整学期重导以后那一行没了（快照里的旧 id 不再存在）",
            current = null,
            sameCourseAtId = true,
            expected = GroupRowUndoDisposition.SkipMissing,
            why = "重导以后同一门课已经在库里换了新 id ⇒ 再插一遍就是屏幕上多一行重复课；跳过",
            reading = "current=null / sameCourseAtId=true（不参与判定）",
        ),
        Cell(
            name = "用户在这之后自己删掉了那个片段",
            current = null,
            sameCourseAtId = false,
            expected = GroupRowUndoDisposition.SkipMissing,
            why = "那一次删除另有它自己的撤销条目（它的身份账归 T123），不该由这条换色记录代劳",
            reading = "current=null / sameCourseAtId=false（不参与判定）",
        ),
    )

    private fun row(id: Long, name: String = "高等数学", colorIndex: Int = 1): Course = Course(
        id = id,
        name = name,
        teacher = "张老师",
        location = "主楼",
        campus = "学院路",
        dayOfWeek = 1,
        periods = listOf(1, 2),
        weeks = (1..16).toList(),
        colorIndex = colorIndex,
        sourceGroupKey = "G-1",
        semesterCode = "2026-2027-1",
        credit = 3.0,
    )
}
