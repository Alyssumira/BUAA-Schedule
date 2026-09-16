package com.buaa.schedule.data.undo

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.ReminderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 组删除的撤销记录数（R5 F-20）。
 *
 * 「删除整门课」此前在 UI 层按片段循环 pushDelete：一门课 3 个片段推 3 条记录，
 * 而提示条只有一个「撤销」按钮 —— 点一次只回来 1 个片段，另外 2 个永久丢失。
 * 快照里还必须带上每个片段的提醒设置，否则恢复出来的课是"没有提醒"的半成品。
 */
class UndoManagerTest {

    private fun course(id: Long, name: String = "高等数学") = Course(
        id = id,
        name = name,
        location = "J3-101",
        dayOfWeek = 1,
        periods = listOf(1),
        weeks = listOf(1),
    )

    @Before
    fun clearStack() {
        UndoManager.clear()
    }

    @Test
    fun groupDeleteProducesASingleEntry() {
        val fragments = listOf(course(11), course(12), course(13))
        val reminders = listOf(ReminderSetting(11), ReminderSetting(13, enabled = false, advanceMinutes = 20))

        UndoManager.pushDeleteGroup(fragments, reminders)

        val action = UndoManager.pop()?.action
        assertTrue("组删除应记录为 DeleteGroup", action is UndoManager.UndoAction.DeleteGroup)
        action as UndoManager.UndoAction.DeleteGroup
        assertEquals(fragments, action.courses)
        assertEquals(reminders, action.reminders)
        // 关键点：一次撤销即整组复原，栈里不该残留兄弟片段的记录
        assertNull("整组只应占一条记录", UndoManager.pop())
    }

    @Test
    fun perFragmentDeletesNeedOneUndoEach() {
        // 与上面形成对照：逐条删除正是修复前的调用形态
        listOf(course(11), course(12)).forEach { UndoManager.pushDelete(it) }

        assertTrue(UndoManager.pop()?.action is UndoManager.UndoAction.Delete)
        assertTrue(UndoManager.pop()?.action is UndoManager.UndoAction.Delete)
        assertNull("两条记录要按两次才能撤完", UndoManager.pop())
    }
}
