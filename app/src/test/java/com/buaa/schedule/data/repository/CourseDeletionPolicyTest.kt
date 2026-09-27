package com.buaa.schedule.data.repository

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.ReminderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T121③a：把「这一趟删除到底删没删到东西」那一判钉成表驱动单测（纯 JVM）。
 *
 * 用户报的是效果：**删一件已经不在的东西，仍然报告"删掉了"**，于是那一次真正的删除被假记录
 * 压在撤销栈底、用户点「撤销」什么也没回来（实测这一环比"什么都没回来"更糟：撤销那条
 * phantom Delete 会走 [ScheduleRepository.applyUndo] 的 `Delete` 分支把那门课**重新插回库里**）。
 * 完整账见 `CourseDeletionPolicy.kt` 段首。
 *
 * 档位（如实写明，别拿它当 DB 级证据）：`:app` 的 JVM 测试依赖面只有 `junit` 一枚，
 * **没有 Robolectric、没有能在 JVM 上跑 Room 的缝**，所以本文件钉的是**判据**，
 * "VM 那一步有没有真的用上它"由源码核对守卫 `CourseDeletionWiringGuardTest` 钉，
 * **装机级 / SQLite 级的"真删一行再删一次"这一档没做**（红线零设备）。
 * 下面第 ⑤ 组那枚连点两次的用例摆的是**同一枚判据在两次调用上的差别**（用一份内存行表当库），
 * 它复跑的是结论，不是 Room。
 *
 * 表 ① 的四格是这一族规矩要求的"两侧都要有格子"：判据朝"更宽"扭一次
 * （`foundRows.isNotEmpty() || reminderCount > 0`，即拿提醒当存在的证据）必有格子红，
 * 朝"更窄"扭一次（`&& reminderCount > 0`，即空提醒判成没删到）也必有格子红。
 */
class CourseDeletionPolicyTest {

    // ─────────────── ① 判据四格：行在不在 × 提醒有没有 ───────────────

    /**
     * 一格一笔，四格全摆。前两格是"行不在"那一侧（本来就没有 / 只剩孤儿提醒），
     * 后两格是"行在"那一侧（没设提醒 / 设了提醒）。
     */
    @Test
    fun `删没删到只看读回来的行本身 四格全判对`() {
        for (cell in table) {
            assertEquals(
                "判据格子 ${cell.name}：读回 ${cell.foundRows} 行 / 提醒 ${cell.reminderCount} 条 " +
                    "应当判成 ${cell.expected} —— ${cell.why}\n" +
                    "这一格红了说明判据朝" + cell.mutantDirection + "的方向跑掉了：" +
                    "deletionRemovedSomething(foundRows = ${cell.foundRows} 行, reminderCount = ${cell.reminderCount})",
                cell.expected,
                deletionRemovedSomething(rowsOf(cell.foundRows), cell.reminderCount),
            )
        }
    }

    /**
     * 「更宽」那一侧的那一格：提醒表里还剩一行，课程行却早就没了。
     *
     * 这一格不是凑数：`reminders` 表没有外键级联，导入走 `deleteBySemester` 只动 courses，
     * 孤儿提醒行是真会留下的（见 ScheduleRepository 的 deleteRemindersOfDroppedCourses KDoc，
     * 它还专门写着"孤儿行还可能撞上下一次复用的 id"）。旧签名返回的**只有**这份提醒表，
     * 于是"有一行提醒"在调用点看起来就像"确实删掉了东西" —— 判据一旦染上这个方向，
     * phantom Delete 就又是合法输入了。
     */
    @Test
    fun `孤儿提醒不算删到 判据不许拿提醒当存在的证据`() {
        assertFalse(
            "库里没有那一行、提醒表里却还留着一行 ⇒ 这一趟什么都没删到。" +
                "红了就说明判据变宽了（`|| reminderCount > 0`）：那条指向已不存在之行的 phantom " +
                "Delete 又会进栈，用户点「撤销」时捞到它、把那门课重新插回库里",
            deletionRemovedSomething(emptyList(), reminderCount = 1),
        )
        assertFalse(deletionRemovedSomething(emptyList(), reminderCount = 7))
    }

    /**
     * 「更窄」那一侧的那一格：一门没设提醒的课，删得掉。
     *
     * 这恰恰是**最常见**的一档（绝大多数课压根没设课前提醒）。判成"没删到"就是反向说谎：
     * 真删掉的课被报成"删除失败：… 还在课表里"，而且撤销记录不进栈，用户再也捞不回来。
     * 旧实现的问题不是"把这一格判错"，而是它**给不出**这一格 —— 返回值只装得下提醒。
     */
    @Test
    fun `没设提醒的课照样删得到 判据不许拿空提醒当没删到`() {
        assertTrue(
            "读回来一行、提醒零条 ⇒ 删到了。红了就说明判据变窄了（`&& reminderCount > 0`），" +
                "等于把一次真删除报成失败、还把撤销记录整个丢掉",
            deletionRemovedSomething(listOf(row), reminderCount = 0),
        )
    }

    // ─────────────── ② 结论只由判据造出来 ───────────────

    /** 没读到行 ⇒ [CourseDeletion.NothingRemoved]：VM 拿不到课程、也拿不到提醒可挂回栈里 */
    @Test
    fun `没读到行时结论里既没有课程也没有提醒`() {
        val deletion = courseDeletionOf(emptyList(), listOf(ReminderSetting(courseId = row.id)))
        assertSame(
            "提醒条数不参与判定（见上面那枚孤儿提醒那一格）：读回 0 行就是 NothingRemoved",
            CourseDeletion.NothingRemoved,
            deletion,
        )
        assertNull("没删到 ⇒ 结论里没有行可给：VM 那一步靠这一格早退", deletion.removedCourse)
        assertEquals(
            "没删到 ⇒ 没有提醒被清掉，撤销也就无从挂回",
            emptyList<ReminderSetting>(),
            deletion.removedReminders,
        )
        assertFalse("没删到 ⇒ removedAnything 必须是 false（提示条走「还在课表里」那一支）", deletion.removedAnything)
    }

    /** 读回一行 ⇒ 结论里带的是**那一行本身**，不是调用方手里那份可能已经过期的对象 */
    @Test
    fun `结论里带的是事务内读回来的那一行而不是调用方手里那份`() {
        val staleCopy = row.copy(name = "改名前的旧名字", location = "旧的、库里已经没有的教室")
        val deletion = courseDeletionOf(listOf(row), emptyList())
        assertEquals(
            "有课没提醒照样是删到了（判据四格里那一格）",
            CourseDeletion.Removed(row, emptyList()),
            deletion,
        )
        assertSame(
            "撤销时插回库里的是**读回来的那一行**：把它换成调用方手里那份过期对象，" +
                "phantom 复活时插回去的就是那份过期内容（本卡缺陷序列的第 3 步）",
            row,
            deletion.removedCourse,
        )
        assertFalse(
            "手里那份过期对象不许成为结论（同 id 也不行）",
            staleCopy == deletion.removedCourse,
        )
    }

    /** 删到了 ⇒ 那一行当时挂着的提醒原样跟着结论走（撤销时要按原样挂回） */
    @Test
    fun `删到时提醒快照跟着结论一起交回去`() {
        val reminders = listOf(ReminderSetting(courseId = row.id, enabled = true, advanceMinutes = 15))
        val deletion = courseDeletionOf(listOf(row), reminders)
        assertEquals(
            "缺了提醒的撤销只回得来课程、回不来课前提醒（口径与组删除一致）",
            reminders,
            deletion.removedReminders,
        )
        assertTrue(deletion.removedAnything)
    }

    // ─────────────── ③ 投影与判据不许各判一次 ───────────────

    /** VM 与仓储层读的是同一个结论：[CourseDeletion.removedAnything] 与判据逐格同值 */
    @Test
    fun `结论的读法与判据本体逐格同值 不许各判一次`() {
        for (cell in table) {
            val rows = rowsOf(cell.foundRows)
            val reminders = List(cell.reminderCount) { ReminderSetting(courseId = row.id) }
            assertEquals(
                "格子 ${cell.name}：removedAnything 与 deletionRemovedSomething 判得不一样，" +
                    "说明结论的读法长出了第二把尺子（这一族规矩：判据本体只许写一遍）",
                deletionRemovedSomething(rows, reminders.size),
                courseDeletionOf(rows, reminders).removedAnything,
            )
        }
    }

    // ─────────────── ④ 组删除那枚既有范本的口径不许漂 ───────────────

    /**
     * 本卡照抄的形状是 [ScheduleRepository.deleteCourseGroup]：`rows.isEmpty()` ⇒ 空快照。
     * 单行入口与它是同一把尺子的两个刻度，所以把"空行表"那一格一起钉在这里。
     */
    @Test
    fun `空行表这一格与组删除的早退同判`() {
        assertFalse(deletionRemovedSomething(emptyList(), reminderCount = 0))
        assertTrue(deletionRemovedSomething(listOf(row, row.copy(id = 6L)), reminderCount = 0))
    }

    // ─────────────── ⑤ 连点两次删除（缺陷序列本体） ───────────────

    /**
     * 可复跑的缺陷序列：同一门课连点两次删除。
     *
     * 用一份内存行表当"库"（**没有 Room、没有设备**，见段首档位说明）：第一趟读得到行 ⇒
     * 判删到并把行摘掉；第二趟同一份对象再来 ⇒ 读不回任何行 ⇒ 判没删到。
     * 第二趟的结论就是 phantom Delete 的成因：旧实现在这里照样返回一份（空）提醒表，
     * VM 看不出差别，于是报 `true` 并把一条指向已不存在之行的记录压进栈，
     * 用户点「撤销」时捞到的是这条假的、真正那次删除被压在栈底。
     *
     * ⚠️ 这一枚钉的是**判据**在两次调用上给得出差别。"VM 那一步真的读它、真的不压栈"
     *    不在本文件里，由 CourseDeletionWiringGuardTest 的第 ② 层按位置钉。
     *    另：这一枚复跑用例杀不掉"更宽"那一臂（提醒按**读回来的那一行**取，第二趟行都没读回来，
     *    提醒表必然是空的）—— 专杀它的是上面那第②格（0 行 / 1 条孤儿提醒），
     *    而"提醒不许按调用方手里那个 id 去取"这半条由守卫第 ① 层逐字钉着。
     */
    @Test
    fun `同一门课连点两次删除 第二趟必须判成什么都没删到`() {
        val db = mutableListOf(row)
        val remindersOf = { id: Long -> listOf(ReminderSetting(courseId = id)) }

        val first = deleteOnce(db, target = row, remindersOf = remindersOf)
        assertTrue(
            "第一趟：行在库里 ⇒ 真的删到了，VM 该报 true、该压撤销记录",
            first.removedAnything,
        )
        assertEquals("第一趟删掉以后库里不该再有这一行", emptyList<Long>(), db.map { it.id })
        assertEquals(
            "第一趟的提醒要跟着快照走，撤销时原样挂回",
            listOf(row.id),
            first.removedReminders.map { it.courseId },
        )

        // 隔壁那门课还活着：第二趟（指向已不存在之行的那一笔）谁都不许碰
        val bystander = row.copy(id = 6L, name = "编译原理")
        db += bystander

        val second = deleteOnce(db, target = row, remindersOf = remindersOf)
        assertEquals(
            "第二趟：库里已经没有那一行了 ⇒ 结论必须是 NothingRemoved。" +
                "红了就说明这条 phantom Delete 又能进撤销栈 —— 那一次真正的删除会被它压在栈底",
            CourseDeletion.NothingRemoved,
            second,
        )
        assertFalse(
            "第二趟不许报 true：首页提示条因此走「删除失败：… 还在课表里」那一支，" +
                "那颗「撤销」按钮也不许长出来（takeIf { deleted } 读的就是这一枚布尔）",
            second.removedAnything,
        )
        assertNull("第二趟的结论里不许带行", second.removedCourse)
        assertTrue("第二趟没有清掉任何提醒 ⇒ 结论里不许带提醒（旧实现正是拿这份空表当返回值的）", second.removedReminders.isEmpty())
        assertEquals("第二趟什么都没删到 ⇒ 库里别的行一根汗毛都不许动", listOf(bystander.id), db.map { it.id })
    }

    /** 一次删除事务的判据部分：读回那一行 → 造结论 → 判到了才把行摘掉（与仓储层同形状） */
    private fun deleteOnce(
        db: MutableList<Course>,
        target: Course,
        remindersOf: (Long) -> List<ReminderSetting>,
    ): CourseDeletion {
        val rows = listOfNotNull(db.firstOrNull { it.id == target.id })
        val reminders = rows.flatMap { remindersOf(it.id) }
        val deletion = courseDeletionOf(rows, reminders)
        if (deletion.removedAnything) db.removeAll { it.id == target.id }
        return deletion
    }

    // ─────────────────────────── 格 子 表 ───────────────────────────

    private class Cell(
        val name: String,
        val foundRows: Int,
        val reminderCount: Int,
        val expected: Boolean,
        val mutantDirection: String,
        val why: String,
    )

    private val table = listOf(
        Cell(
            name = "①行不在+无提醒", foundRows = 0, reminderCount = 0, expected = false,
            mutantDirection = "更宽（一行都没读回来也报删到）",
            why = "这门课本来就没有：什么都没删到",
        ),
        Cell(
            name = "②行不在+有孤儿提醒", foundRows = 0, reminderCount = 1, expected = false,
            mutantDirection = "更宽（拿提醒当存在的证据）",
            why = "导入只动 courses 表、提醒表留孤儿行那一档：提醒不是证据",
        ),
        Cell(
            name = "③行在+无提醒", foundRows = 1, reminderCount = 0, expected = true,
            mutantDirection = "更窄（拿空提醒当没删到）",
            why = "最常见那一档：没设课前提醒的课照样删得掉，判成没删到就是把真删除报成失败",
        ),
        Cell(
            name = "④行在+有提醒", foundRows = 1, reminderCount = 1, expected = true,
            mutantDirection = "更窄（读回行了还报没删到）",
            why = "删到了，提醒随结论一起交回去（撤销要挂回）",
        ),
    )

    /** 表里的"读回几行"换成真对象：0 行 = 空表，1 行 = 那一行本身，2 行 = 组删除那一档 */
    private fun rowsOf(count: Int): List<Course> = when (count) {
        0 -> emptyList()
        1 -> listOf(row)
        else -> List(count) { row.copy(id = row.id + it) }
    }

    private val row = Course(
        id = 5L,
        name = "算法设计与分析",
        teacher = "王教授",
        location = "北主楼 314",
        campus = "学院路",
        dayOfWeek = 3,
        periods = listOf(3, 4),
        weeks = listOf(1, 2, 3, 4),
    )
}
