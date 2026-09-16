package com.buaa.schedule.data.undo

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.ReminderSetting

/**
 * 课程编辑撤销栈（内存态）。
 *
 * 覆盖增删改三类操作：
 * - Delete：删除课程，撤销 = 以新 id 重新入库；
 * - DeleteGroup：删除同一门课的全部片段，撤销 = 整组重新入库并把提醒挂回新行；
 * - Create：新增课程，撤销 = 按 id 删除；
 * - Update：更新课程，撤销 = 恢复修改前快照。
 *
 * 只保留最近 [CAPACITY] 条；应用进程退出即清空——撤销是「刚操作错了马上恢复」的即时操作。
 */
object UndoManager {

    sealed interface UndoAction {
        data class Delete(val course: Course) : UndoAction

        /**
         * 一次删掉同一门课的全部片段：整组连同各自的提醒设置只有**一条**记录。
         *
         * 此前按片段推 N 条，而提示条只有一个「撤销」按钮，
         * 用户点一次只回来一个片段，其余永久丢失。
         */
        data class DeleteGroup(
            val courses: List<Course>,
            val reminders: List<ReminderSetting>,
        ) : UndoAction

        data class Create(val course: Course) : UndoAction
        data class Update(
            val before: Course,
            val after: Course,
            /** 部分周次编辑时最终写入/新建的行 id；等于 before.id 表示没有拆行 */
            val afterId: Long? = null,
            /** 这次编辑顺手清掉的同类旧片段：只记 before/after 会把它们永久丢掉（R5 F-35） */
            val removed: List<Course> = emptyList(),
            /** [removed] 与 before 各自的提醒设置，按 courseId 关联 */
            val reminders: List<ReminderSetting> = emptyList(),
        ) : UndoAction
    }

    data class UndoEntry(
        val action: UndoAction,
        val label: String,
    )

    private const val CAPACITY = 10
    private val stack = ArrayDeque<UndoEntry>()

    @Synchronized
    fun push(action: UndoAction, label: String) {
        stack.addLast(UndoEntry(action, label))
        while (stack.size > CAPACITY) stack.removeFirst()
    }

    @Synchronized
    fun pushDelete(course: Course) = push(UndoAction.Delete(course), "删除课程")

    @Synchronized
    fun pushDeleteGroup(courses: List<Course>, reminders: List<ReminderSetting>) =
        push(UndoAction.DeleteGroup(courses, reminders), "删除整门课程")

    @Synchronized
    fun pushCreate(course: Course) = push(UndoAction.Create(course), "新增课程")

    @Synchronized
    fun pushUpdate(
        before: Course,
        after: Course,
        afterId: Long? = null,
        removed: List<Course> = emptyList(),
        reminders: List<ReminderSetting> = emptyList(),
    ) = push(UndoAction.Update(before, after, afterId, removed, reminders), "编辑课程")

    @Synchronized
    fun pop(): UndoEntry? = stack.removeLastOrNull()

    @Synchronized
    fun clear() = stack.clear()
}