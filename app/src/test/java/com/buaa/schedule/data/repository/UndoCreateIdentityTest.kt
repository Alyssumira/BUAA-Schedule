package com.buaa.schedule.data.repository

import com.buaa.schedule.domain.model.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T287②：把「撤销一条『新增课程』条目时，那一枚 id 上那一行到底该不该删」钉成表驱动单测（纯 JVM）。
 *
 * 判据本体是 [undoCreateDeletion]，完整账（含第⓪问的甲/乙/丙/丁四解怎么裁的、可达性那笔账）写在
 * `UndoCreateIdentity.kt` 段首。这张表量四件事：
 *
 * 1. **「新建之后一个字没动」那一格必须真的判成删**（表 ① 第一格）—— 收紧判据的代价不能由用户付：
 *    一枚撤销链里最常见的形状（建完就撤）如果被拦下来，本卡就从"少删一门课"换成"撤销撤不动了"。
 *    这一格就是变异臂 N 的靶子。
 * 2. **同一枚 id 上坐着别的课那一格必须拦下来**（表 ① 第二格，#527 的占用场景逐字复刻）—— 这一格
 *    是变异臂 W（朝宽：不查身份照删）的靶子。
 * 3. **尺子只有一枚**：身份由 `ImportPlanner.courseKey` 的七维说了算。不参与身份的那几维
 *    （weeks / credit / colorIndex / customColorArgb / remark / campus / alias）漂了**不许**翻成
 *    "被别的课占了"（表 ① 第 ④⑤ 格）；参与身份的七维每一维各有一格（第 ⑦…⑬ 格）。
 * 4. **参照物取 `normalize(条目快照)`，不取草稿本体**（表 ① 第 ⑥ 格）：条目里那枚
 *    `Course` 是 `ScheduleViewModel.saveCourse` 在归一化**之前**压的草稿
 *    （`pushCreate(course.copy(id = savedId))`），而库里那一行是 normalize 之后的产物 ——
 *    拿草稿本体逐字段比就是第⓪问的乙，它会把"periods 未经排序"这一类归一化漂移判成别的课。
 *
 * 两侧都要有格子：**朝宽扭**（`RefuseNotTheCreatedCourse` 那一档摘掉、或把调用支改回不查身份的
 * `deleteCourseRow(held)`）红在第 ② 格与守卫那一层；**朝窄扭**（把尺子换成全字段相等 / 把
 * normalize 那一步摘掉 / 让 `NothingToDelete` 也去写库）红在第 ①④⑤⑥ 格。
 *
 * 档位（如实写明）：`:app` 的 JVM 测试面只有 `junit`，**没有 Robolectric、也没有能在 JVM 上跑 Room
 * 的缝**，所以"那一格上今天坐着谁"被抽成纯函数来钉。SQLite 级与装机级都**没做**（红线零 adb、零设备）：
 * 真建一门课、真重导一次学期、再点撤销看那一格回没回 —— 那一档仍然欠着，逐字写在内核段首。
 * 「结论域恰好三档」这一枚由源码形状守卫 `UndoCreateIdentityGuardTest` 数（`sealed interface` 没有
 * `.entries`，枚举行在 JVM 侧数不出来，这是如实的口径而不是省写）。
 */
class UndoCreateIdentityTest {

    // ─────────────── ① 逐格：那一格上坐着谁 × 条目里那门课是谁 ───────────────

    @Test
    fun `判据逐格钉住 只有对得上号那一格才删行`() {
        for (cell in cells) {
            val result = undoCreateDeletion(cell.current, cell.snapshot)
            assertEquals(
                "判据格子「${cell.name}」：${cell.why}\n  ${cell.reading}\n" +
                    "复算：grep -n 'ImportPlanner.courseKey' " +
                    "app/src/main/java/com/buaa/schedule/data/repository/UndoCreateIdentity.kt",
                cell.expected,
                tierName(result),
            )
            when (result) {
                is UndoCreateDeletion.DeleteRow -> assertSame(
                    "格子「${cell.name}」：删的必须是**读回来的那一行**，不是条目里那枚快照 —— " +
                        "按手里那份删就把「库里那一行今天是谁的」这件事又交回调用方猜了",
                    cell.current,
                    result.row,
                )
                is UndoCreateDeletion.RefuseNotTheCreatedCourse -> assertSame(
                    "格子「${cell.name}」：拒删那一档带的必须是那一格上读回来的那一行（文案要念它的名字）",
                    cell.current,
                    result.occupant,
                )
                UndoCreateDeletion.NothingToDelete -> Unit
            }
        }
    }

    // ─────────────── ② 那一格上没行 ⇒ 条目里那门课是谁都不参与判定 ───────────────

    @Test
    fun `那一格上读不到行时快照不参与判定 两格都得是一个字节都不动`() {
        val matchingSnapshot = storedRow(id = 7L)
        val unrelatedSnapshot = storedRow(id = 7L, name = "模拟电子技术", teacher = "李老师")
        val givenMatching = undoCreateDeletion(null, matchingSnapshot)
        val givenUnrelated = undoCreateDeletion(null, unrelatedSnapshot)
        assertEquals(
            "那一格上压根没有行 ⇒ 用户新建的那门课**已经不在库里**，撤销要达到的状态本来就已成立：" +
                "两格都必须是不动，既不许去删一枚不存在的行（那会顺手清掉挂在旧 id 上的提醒行），" +
                "也不许把「没读到」翻成「被别的课占了」而抛一句失败给用户。\n" +
                "  (null, 同一门课)   实到 $givenMatching\n" +
                "  (null, 无关的一门) 实到 $givenUnrelated\n" +
                "红了就是有人给这一族添了「读不到就动手」那一档 —— 那一档在主行那边成立" +
                "（`MainRowUndoDisposition.RestoreAsNewRow`），在删除这边恰恰相反",
            listOf(UndoCreateDeletion.NothingToDelete, UndoCreateDeletion.NothingToDelete),
            listOf(givenMatching, givenUnrelated),
        )
    }

    // ─────────────── ③ 两枚会写库的档之间没有第三手：拒删那一档不交出可删的行 ───────────────

    @Test
    fun `拒删那一档交出的行只为把名字念给用户 不为删它`() {
        val occupant = storedRow(id = 7L, name = "线性代数", teacher = "王老师", location = "二教")
        val result = undoCreateDeletion(occupant, storedRow(id = 7L, name = "高等数学"))
        assertTrue(
            "这一格钉的是形状：拒删那一档只能是 RefuseNotTheCreatedCourse（不是某个带 DeleteRow 的复合档）。" +
                "实到 $result",
            result is UndoCreateDeletion.RefuseNotTheCreatedCourse,
        )
        result as UndoCreateDeletion.RefuseNotTheCreatedCourse
        assertSame(
            "那一档手里的行是**占用者**，调用点除了 reason 之外没有第二手拿它去写库 —— " +
                "删行那一句只长在 DeleteRow 那一档上（守卫 UndoCreateIdentityGuardTest 第 ① 层数死）",
            occupant,
            result.occupant,
        )
    }

    // ─────────────── ④ 那句为什么：不许替内核多判一层，也不许念两句打脸的话 ───────────────

    @Test
    fun `拒删那句只说对不上号 不宣称已撤销也不宣称条目还留着`() {
        val occupant = storedRow(id = 7L, name = "  线性代数  ")
        val reason = (undoCreateDeletion(occupant, storedRow(id = 7L, name = "高等数学"))
            as UndoCreateDeletion.RefuseNotTheCreatedCourse).reason
        assertTrue(
            "那句必须把**那一格上现在是谁**念出来（用户据此知道该先处理哪门课），而且是 trim 过的名字：" +
                "实到「$reason」",
            reason.contains("线性代数"),
        )
        assertFalse(
            "不许念「已撤销」：这一档屏幕上一个字节都没变，那句就是谎（而 VM 成功支正是这么念的，" +
                "所以本卡必须抛出去而不是安静返回 —— 账在 UndoCreateIdentity.kt「失败那一支」）：$reason",
            reason.contains("已撤销"),
        )
        assertFalse(
            "不许宣称「还留着」：条目回不回栈顶由 UndoManager.restoreTop 那张票据说了算（期间任何 " +
                "push / clear 都会作废它），VM 那句 heldSuffix 只在真的回了 true 时才念。" +
                "内核抢在那之前宣称留着 = 拿一句谎盖住「这条撤销点不回来了」：$reason",
            reason.contains("还留着"),
        )
        assertEquals(
            "occupantName 必须取 trim 过的那一维 —— courseKey 用的也是 name.trim()，" +
                "文案与尺子不许两把口径",
            "线性代数",
            (undoCreateDeletion(occupant, storedRow(id = 7L)) as UndoCreateDeletion.RefuseNotTheCreatedCourse)
                .occupantName,
        )
    }

    // ─────────────── 格子表 ───────────────

    private class Cell(
        val name: String,
        val current: Course?,
        val snapshot: Course,
        val expected: String,
        val why: String,
        val reading: String,
    )

    private val cells = listOf(
        Cell(
            // 这一格就是变异臂 N 的靶子：判据收得过紧（全字段相等 / 摘掉 normalize）第一个红在这里
            name = "建完之后一个字没动 —— 撤销一条真还留在库里的新建",
            current = storedRow(id = 7L),
            snapshot = storedRow(id = 7L),
            expected = "DeleteRow",
            why = "这一档必须写库：用户点了撤销、那一格上就是他刚建的那门课，拦下来就是「撤销撤不动」",
            reading = "current=那门课本体 / snapshot=同一枚本体",
        ),
        Cell(
            // 这一格就是 #527 逐字复刻的那一格（也是变异臂 W 落进判据这一侧时的靶子）
            name = "同一枚 id 上坐着另一门课（重导换号 / 顶号）",
            current = storedRow(id = 7L, name = "模拟电子技术", teacher = "李老师", location = "三教"),
            snapshot = storedRow(id = 7L),
            expected = "RefuseNotTheCreatedCourse",
            why = "按主键盲删删掉的就是这门从没打算删的课，而这一坏没有任何一条撤销记录能撤回来" +
                "（MainRowUndoPolicy 段首理由第 2 条那笔账）",
            reading = "current=id7〈模拟电子技术〉 / snapshot=id7〈高等数学〉",
        ),
        Cell(
            name = "那一格上读不到行（那门课早就不在了）",
            current = null,
            snapshot = storedRow(id = 7L),
            expected = "NothingToDelete",
            why = "撤销要达到的状态已成立 ⇒ 一个字节都不动，条目照常消费（与 deleteCourse 的" +
                "「没读到行就什么都不动」同一手）",
            reading = "current=null / snapshot=id7〈高等数学〉",
        ),
        Cell(
            name = "重导之后同一门课复用旧 id（教务改了周次与学分）",
            current = storedRow(id = 7L, weeks = (1..18).toList(), credit = 2.0),
            snapshot = storedRow(id = 7L),
            expected = "DeleteRow",
            why = "weeks / credit 都不在 courseKey 的七维里 ⇒ 同一门课换了周次还叫同一门课。" +
                "这一档正是 ImportPlanner「同 key 复用旧 id」会造出来的形状，拦下来就是把甲判成乙",
            reading = "current=id7 weeks1..18 credit2.0 / snapshot=id7 weeks1..16 credit3.0",
        ),
        Cell(
            name = "建完之后只改了颜色、备注、校区、别名",
            current = storedRow(id = 7L, colorIndex = 6, remark = "调课过", campus = "沙湾", alias = "高数B"),
            snapshot = storedRow(id = 7L),
            expected = "DeleteRow",
            why = "那四维同样不参与身份：条目里那门课与那一格上那门课是一枚东西，撤销就该删掉它",
            reading = "current=id7 colorIndex6 / snapshot=id7 colorIndex1",
        ),
        Cell(
            name = "条目带的是归一化之前的草稿（节次乱序、周次乱序、名字带空格）",
            current = storedRow(id = 7L),
            snapshot = storedRow(id = 7L).copy(
                name = "  高等数学  ",
                periods = listOf(2, 1, 2),
                weeks = listOf(16, 3, 1, 2),
            ),
            expected = "DeleteRow",
            why = "尺子取 normalize(snapshot)：normalize 会把 periods/weeks 去重升序、把名字 trim，" +
                "而库里那一行本来就是它的产物（MainRowUndoPolicy 对 afterKey 的同一手）",
            reading = "current=归一化后 / snapshot=草稿本体",
        ),
        Cell(
            name = "那一格上的课被改了名字",
            current = storedRow(id = 7L, name = "高等数学（下）"),
            snapshot = storedRow(id = 7L),
            expected = "RefuseNotTheCreatedCourse",
            why = "名字是 courseKey 的第三维。这一格与「建完就撤」那一格的区别只在名字，" +
                "拒删的话术因此写「对不上号」而不是「它是另一门课」——两种读法内核分不出来",
            reading = "current=name≠snapshot.name",
        ),
        Cell(
            name = "那一格上的教师不同",
            current = storedRow(id = 7L, teacher = "王老师"),
            snapshot = storedRow(id = 7L),
            expected = "RefuseNotTheCreatedCourse",
            why = "教师是第四维：同名不同教师是两门课（教务系统里同名课成串）",
            reading = "current.teacher=王老师 / snapshot.teacher=张老师",
        ),
        Cell(
            name = "那一格上的地点不同",
            current = storedRow(id = 7L, location = "三教"),
            snapshot = storedRow(id = 7L),
            expected = "RefuseNotTheCreatedCourse",
            why = "地点是第五维",
            reading = "current.location=三教 / snapshot.location=主教",
        ),
        Cell(
            name = "那一格上的星期不同",
            current = storedRow(id = 7L, dayOfWeek = 4),
            snapshot = storedRow(id = 7L),
            expected = "RefuseNotTheCreatedCourse",
            why = "dayOfWeek 是第六维：星期换了就是另一节课表上的课",
            reading = "current.dayOfWeek=4 / snapshot.dayOfWeek=1",
        ),
        Cell(
            name = "那一格上的节次不同",
            current = storedRow(id = 7L, periods = listOf(3, 4)),
            snapshot = storedRow(id = 7L),
            expected = "RefuseNotTheCreatedCourse",
            why = "periods 是第七维，而且是逐序 joinToString 的：第 1-2 节与第 3-4 节不是一门课",
            reading = "current.periods=3,4 / snapshot.periods=1,2",
        ),
        Cell(
            name = "那一格上的学期归属不同（手动课与学期课撞了同一枚 id）",
            current = storedRow(id = 7L, semesterCode = "2026-2027-1"),
            snapshot = storedRow(id = 7L),
            expected = "RefuseNotTheCreatedCourse",
            why = "semesterCode 是第一维（null 折成空串），这一维正是「重导换号」那一族最容易撞开的地方",
            reading = "current.semesterCode=2026-2027-1 / snapshot.semesterCode=null",
        ),
        Cell(
            name = "那一格上的分组键不同（同一门课拆出来的另一段）",
            current = storedRow(id = 7L, sourceGroupKey = "G-2"),
            snapshot = storedRow(id = 7L, sourceGroupKey = "G-1"),
            expected = "RefuseNotTheCreatedCourse",
            why = "sourceGroupKey 是第二维：部分周次拆行留下的兄弟片段共用内容、不共用组键",
            reading = "current.sourceGroupKey=G-2 / snapshot.sourceGroupKey=G-1",
        ),
        Cell(
            name = "条目里那枚快照归一化不过（结构性到不了的那一格）",
            current = storedRow(id = 7L),
            snapshot = storedRow(id = 7L).copy(weeks = emptyList()),
            expected = "RefuseNotTheCreatedCourse",
            why = "快照量不出尺子就证明不了身份 ⇒ 落进同一拒删档。这一档在真实链上到不了" +
                "（saveCourse 里 normalize 不过就不返回 id、也就不压 Create 条目），" +
                "所以内核**不为它单开一档**（本仓不许死档），格子留在这里是为了钉住它「落进哪一档」",
            reading = "current=id7〈高等数学〉 / snapshot=weeks 空表",
        ),
    )

    /** 三档的名字就是这张表的期望值：`sealed interface` 没有 `.entries`，这里按 when 展开逐档点名 */
    private fun tierName(result: UndoCreateDeletion): String = when (result) {
        is UndoCreateDeletion.DeleteRow -> "DeleteRow"
        UndoCreateDeletion.NothingToDelete -> "NothingToDelete"
        is UndoCreateDeletion.RefuseNotTheCreatedCourse -> "RefuseNotTheCreatedCourse"
    }

    /**
     * 库里那一行的样子（**已过 normalize**：名字无空串、periods 升序去重、weeks 连续）。
     * 默认是一枚手动新建课：`semesterCode = null`、`sourceGroupKey = null` ——
     * 与 `CourseEditorScreen.performSave` 在 `initialCourse == null` 那一路搭出来的对象同一形。
     */
    private fun storedRow(
        id: Long,
        name: String = "高等数学",
        teacher: String? = "张老师",
        location: String? = "主教 301",
        campus: String? = "学院路",
        alias: String? = null,
        remark: String? = null,
        dayOfWeek: Int = 1,
        periods: List<Int> = listOf(1, 2),
        weeks: List<Int> = (1..16).toList(),
        colorIndex: Int = 1,
        credit: Double? = 3.0,
        sourceGroupKey: String? = null,
        semesterCode: String? = null,
    ): Course = Course(
        id = id,
        name = name,
        alias = alias,
        teacher = teacher,
        location = location,
        campus = campus,
        dayOfWeek = dayOfWeek,
        periods = periods,
        weeks = weeks,
        colorIndex = colorIndex,
        remark = remark,
        sourceGroupKey = sourceGroupKey,
        semesterCode = semesterCode,
        credit = credit,
    )
}
