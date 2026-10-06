package com.buaa.schedule.data.undo

import com.buaa.schedule.domain.model.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T183②：撤销条目「失败还得回来」那一族栈语义（纯 JVM，真栈真条目，不是源码核对）。
 *
 * 病灶：[pop] 是消费，而它跑在落库**之前** —— `applyUndo` 抛时 DB 回滚、条目却已经从栈里摘走，
 * 而进程内这枚栈是那份快照的唯一副本 ⇒ 那条撤销**再也点不回来**。
 * [restoreTop] 就是那条出路，但它有三道"不回推"（期间被 push / 被 clear / 同一枚回推第二回），
 * 三道都必须红着挡下来 —— 放错了比不放更坏（把旧表面的快照插回新盘面、或让同一门课插回库里两遍）。
 *
 * 与 `UndoManagerTest` 的分工：那一族钉的是**条目装什么**（组删除只占一条、六枚字段按位透传），
 * 本文件钉的是**条目摘走之后还能不能回来**（栈的往返语义）。
 * ⚠️ 档位如实写明：这一族是内存栈的语义，**不落 SQLite**；
 * "写库真失败之后条目回来、再点一次真把课撤回来"那一档要装机（本卡零 adb，没做）。
 * 调用点有没有真的按 [restoreTop] 的返回值定文案，由 `UndoFailureRestoreWiringGuardTest` 钉。
 */
class UndoManagerRestoreTest {

    private fun course(id: Long, name: String = "线性代数") = Course(
        id = id,
        name = name,
        location = "J3-202",
        dayOfWeek = 2,
        periods = listOf(3),
        weeks = listOf(1, 2),
    )

    @Before
    fun clearStack() {
        UndoManager.clear()
    }

    // ─────────────── ① 失败之后条目回得来，而且回到它原来那一格 ───────────────

    @Test
    fun `摘走的条目回推后仍是栈顶 下面那些的相对次序一位不换`() {
        UndoManager.pushDelete(course(11), emptyList())
        UndoManager.pushCreate(course(12))
        val popped = UndoManager.pop()
        assertNotNull("栈里有两条，摘走的那条是最近这条", popped)
        assertTrue("摘走的应当是 Create 那条", popped?.action is UndoManager.UndoAction.Create)

        assertTrue("落库失败 ⇒ 那条得回得来（本卡修的正是这一句）", UndoManager.restoreTop(popped!!))

        val again = UndoManager.pop()
        assertSame(
            "回来的必须是**同一枚实例**：重新造一份快照就等于把用户那次操作的内容换了个来源",
            popped,
            again,
        )
        // 回推之后再点一次撤销，捞到的还是它（"可以再点一次"那句的根据就在这里）
        assertTrue("回推成功的条目再点一次撤销仍然捞得着", UndoManager.restoreTop(again!!))
        val third = UndoManager.pop()
        assertSame(popped, third)
        // 它下面那一枚一直原位待着，没被挤走也没被顶替
        assertTrue(
            "压在下面的 Delete 那条一位未动：回推只在顶端放回，不许把栈重排",
            UndoManager.pop()?.action is UndoManager.UndoAction.Delete,
        )
        assertNull("栈里只该有那两条，回推不许多造出第三条", UndoManager.pop())
    }

    // ─────────────── ② 连续失败连续重试：每次都还得回来 ───────────────

    @Test
    fun `同一枚条目可以反复摘走反复回推 回推不许第二次再放一份`() {
        UndoManager.pushDeleteGroup(listOf(course(21), course(22)), emptyList())
        val entry = UndoManager.pop()
        assertNotNull(entry)

        // 用户点了撤销、写库失败、条目回来 —— 三回（磁盘一直满着）
        repeat(3) { round ->
            assertTrue("第 ${round + 1} 轮失败后仍要回得来（否则那句「可以再点一次」只兑现一轮）", UndoManager.restoreTop(entry!!))
            assertSame("第 ${round + 1} 轮再点撤销，捞到的还是同一枚", entry, UndoManager.pop())
        }
        // 回推成功的第二轮起，同一枚条目第二次回推必须被拒：栈里躺着两份同一份快照 = 点两回插两遍
        assertTrue(UndoManager.restoreTop(entry!!))
        assertFalse(
            "同一枚条目**不许回推两回**（票据在成功回推时一并抹掉）：那会在栈里留下两份同一份快照，" +
                "而 Delete 的撤销是 insert ⇒ 用户点两次就多出两门同名课",
            UndoManager.restoreTop(entry),
        )
        assertEquals("栈里此刻只该有那一枚", 1, stackSizeByPopping())
    }

    // ─────────────── ③ 期间又有新操作：旧条目不回推，新那条占住栈顶 ───────────────

    @Test
    fun `写库期间又有新条目进栈 ⇒ 旧那条不回推`() {
        UndoManager.pushDelete(course(31))
        val popped = UndoManager.pop()
        assertNotNull(popped)

        // 用户在这趟写库期间又删了另一门课（另一枚协程 push 进栈）
        UndoManager.pushDelete(course(32))

        assertFalse(
            "期间被 push 过 ⇒ 票据作废、不回推。硬把它按回顶上就是把 LIFO 的顺序改乱：" +
                "用户下一步要撤的是**刚删的那门课**，不是这条早就失败的旧条目",
            UndoManager.restoreTop(popped!!),
        )
        val top = UndoManager.pop()
        assertTrue("栈顶此刻归那条新操作", top?.action is UndoManager.UndoAction.Delete)
        assertEquals(
            "栈顶必须归那条新操作（不回推的那一条不许把它盖住）",
            32L,
            (top?.action as UndoManager.UndoAction.Delete).course.id,
        )
    }

    // ─────────────── ④ 期间整栈被清：旧条目不回推（那四枚 clear() 的账）───────────────

    @Test
    fun `写库期间栈被清空 ⇒ 旧那条不回推`() {
        UndoManager.pushDeleteGroup(listOf(course(41), course(42)), emptyList())
        val popped = UndoManager.pop()
        assertNotNull(popped)

        // 换学期 / 整学期替换 / 备份恢复那四枚落点都清栈，理由是"快照指向的行已经不属于当前这张表"
        UndoManager.clear()

        assertFalse(
            "clear 之后票据一并作废：把旧表面的快照回推，等于在新一轮数据上插回旧数据" +
                "（与 ScheduleRepository 那四枚 UndoManager.clear() 同一笔账）",
            UndoManager.restoreTop(popped!!),
        )
        assertNull("清过的栈里不该因为一次失败的回推又多出东西", UndoManager.pop())
    }

    // ─────────────── ⑤ 连点两次撤销：票据只还最近摘走的那一枚 ───────────────

    @Test
    fun `连点两次时票据换人 先摘走那一条不被第二次冒领`() {
        UndoManager.pushDelete(course(51))
        UndoManager.pushUpdate(course(52, "改前"), course(52, "改后"))
        val newest = UndoManager.pop()
        val older = UndoManager.pop()
        assertNotNull(newest)
        assertNotNull(older)

        assertFalse(
            "第二次 pop 已经把票据换到 older 那枚上 ⇒ newest 的回推必须被拒（否则两趟失败各回推一次，" +
                "回推次序由协程调度决定，等于把栈重排）",
            UndoManager.restoreTop(newest!!),
        )
        assertTrue("票据当前那一枚才回得来", UndoManager.restoreTop(older!!))
        assertSame(older, UndoManager.pop())
        assertNull("另一枚（已经落库失败的那条）留在栈外，这是本卡认下的窄残余", UndoManager.pop())
    }

    // ─────────────── ⑥ 容量与驱逐语句：回推不许长出第二把尺 ───────────────

    @Test
    fun `塞满一栈再摘一条回推 条目数与顺序原样`() {
        val pushed = (1..10L).map {
            UndoManager.pushDelete(course(60 + it))
            it
        }
        val popped = UndoManager.pop()
        assertNotNull(popped)
        assertTrue(UndoManager.restoreTop(popped!!))

        val emptied = (1..11).map { UndoManager.pop() }
        val back = emptied.filterNotNull().map { it.action as UndoManager.UndoAction.Delete }
        assertEquals(
            "回推后栈里仍然数得出那 10 条（一条不多、一条不少），实到 " + back.size + " 条",
            10,
            back.size,
        )
        assertEquals(
            "回推不是「多塞一条」：满栈摘走一条再放回去，条数与内容逐位与压入时同序（倒着弹）",
            pushed.reversed(),
            back.map { it.course.id - 60 },
        )
        assertNull(
            "回推不许把栈撑过 CAPACITY（驱逐语句只在 push 那一处，票据保证回推永远有位置）",
            UndoManager.pop(),
        )
    }

    // ─────────────── ⑦ 空栈与凭空条目 ───────────────

    @Test
    fun `没摘走过东西时任何条目都回不来`() {
        val fabricated = UndoManager.UndoEntry(UndoManager.UndoAction.Create(course(71)), "新增课程")
        assertFalse(
            "没 pop 过就没有票据：任何凭空的条目都塞不进栈（回推这条路只认刚摘走的那一枚）",
            UndoManager.restoreTop(fabricated),
        )
        assertNull(UndoManager.pop())
        assertFalse(
            "pop 到空栈也会把票据置空 ⇒ 上一条摘走的条目此刻起回不来（保守方向：宁可丢这一条）",
            UndoManager.restoreTop(fabricated),
        )
    }

    private fun stackSizeByPopping(): Int {
        var size = 0
        while (UndoManager.pop() != null) size++
        return size
    }
}
