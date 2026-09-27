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
 *
 * 第二档（T137）：`Update` 那一族条目**带得下**组写改掉的行（`groupBeforeRows`），
 * 且六枚字段按位置透传时一位都不许多、一位都不许串 —— 账写在下面那两枚 `updateEntry*` 格子里。
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

    /**
     * T137：条目**带得下**组写改掉的行。
     *
     * 这一格按"每一枚实参都给一个独一无二的值、弹出来逐位对账"来钉 —— 因为
     * `pushUpdate` 收尾那一处透传是**按位置**写的
     * （`UndoAction.Update(before, after, afterId, removed, reminders, groupBeforeRows)`），
     * 任何两位错位都悄悄成立、照样编译、照样跑绿；`reminders` 与 `groupBeforeRows`
     * 这对同族（都是 List）尤其容易串。静态那一半（新字段真的进了调用点、且三榜逐位对齐）
     * 在 `UndoUpdateEntryGuardTest` 第 ⑦ 层，两边合起来才是"被接收"。
     */
    @Test
    fun updateEntryCarriesTheGroupRowsThroughInOrder() {
        val before = course(11, "改前主行")
        val after = course(11, "改后主行")
        val removed = listOf(course(21, "被清掉的片段"))
        val reminders = listOf(ReminderSetting(11, enabled = false, advanceMinutes = 5))
        val groupRows = listOf(course(12, "兄弟行 A"), course(13, "兄弟行 B"))

        UndoManager.pushUpdate(
            before = before,
            after = after,
            afterId = 99L,
            removed = removed,
            reminders = reminders,
            groupBeforeRows = groupRows,
        )

        val entry = UndoManager.pop()
        val action = entry?.action
        assertTrue("编辑应记为 Update", action is UndoManager.UndoAction.Update)
        action as UndoManager.UndoAction.Update
        assertEquals(
            "六枚字段逐位对账：串任何一位，撤销就会把某一族的行塞进另一族的字段里" +
                "（而条目照样非空、照样压进栈、照样念一句「已撤销：编辑课程」）",
            listOf<Any>(before, after, 99L, removed, reminders, groupRows),
            listOf<Any>(
                action.before,
                action.after,
                action.afterId ?: -1L,
                action.removed,
                action.reminders,
                action.groupBeforeRows,
            ),
        )
        assertEquals("编辑课程", entry?.label)
    }

    /** 新字段带默认值 = 条目向后兼容：不带它的那一型旧形状照压，且它空着（不是 null、不是别人的行） */
    @Test
    fun updateEntryStillWorksWithoutTheGroupRowsArgument() {
        UndoManager.pushUpdate(course(11, "甲"), course(11, "乙"), afterId = 11L)

        val action = UndoManager.pop()?.action
        assertTrue(action is UndoManager.UndoAction.Update)
        action as UndoManager.UndoAction.Update
        assertEquals(emptyList<Course>(), action.groupBeforeRows)
        assertEquals(emptyList<Course>(), action.removed)
    }
}
