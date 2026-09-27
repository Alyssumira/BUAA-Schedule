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
 * - Update：更新课程，撤销 = 按 id 恢复修改前快照 —— 含这一次「整组换外观」改掉的**兄弟行**
 *   （[UndoAction.Update.groupBeforeRows]，T137），不只主行那一枚。
 *
 * 只保留最近 [CAPACITY] 条；应用进程退出即清空——撤销是「刚操作错了马上恢复」的即时操作。
 *
 * 快照存的是**整个 [Course] 值对象**，不是逐字段拷贝：Course 新增字段（如 credit）
 * 会自动跟着撤销往返，这里不需要同步改（对比 `WidgetSnapshotModels` / `BackupModels`
 * 那两处逐字段镜像，它们才需要跟）。
 */
object UndoManager {

    sealed interface UndoAction {
        /** [reminders] 是删除时被连带清掉的提醒设置；缺了它撤销只回得来课程、回不来提醒 */
        data class Delete(val course: Course, val reminders: List<ReminderSetting> = emptyList()) : UndoAction

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
            /**
             * 「整组换外观」那一支（`ScheduleRepository.updateCourseGroupAppearance`）**动过的行**，
             * 连同它们**改之前**的样子 —— 组写在它自己的事务里按 `sourceGroupKey` 逐行读回来的那一版，
             * 原样交回，就是这张表（T128 把内容送到调用点，T137 让它进得了条目）。
             *
             * ## 为什么条目必须带得下它（用户看得见的那一句）
             *
             * [before]/[after] 只装得下**主行**一枚值对象，而组写改的是**别的行**。缺了这一族，
             * 「管理页给整组换色、而主行本来就是那个色」那一格（判据第四维命中、第三维不命中）
             * 条目照压、撤销却只回写主行 ⇒ 兄弟片段留着新色 = **一次改色撤不干净**。
             *
             * ## 表里为什么容得下主行自己，而复原次序因此是「兄弟行在前、主行在后」
             *
             * 组写覆盖整组 ⇒ 这张表**可能**含主行那一 id，但它那一版是「改完之后」的：
             * 同一次编辑里 `ScheduleViewModel.updateCourse` 先写了主行，组写才在它后面读回来。
             * 所以 `undoUpdate` 先按这张表逐行回写、主行那一趟再用 [before] 收尾 ——
             * 次序反了，主行就停在「改完之后」那一版，撤销对主行等于没撤销。钉在
             * `UndoUpdateEntryGuardTest` 第 ⑦⑧ 层。
             *
             * ## 为什么这里不带兄弟行的提醒
             *
             * 那一支只改外观字段、**不删行** ⇒ 挂在原行上的提醒一直跟着它，按 id 原地回写既不需要、
             * 也无从重挂（对比 [reminders] 那一族：[removed] 是**被删掉**的行，不记提醒就只回得来课）。
             */
            val groupBeforeRows: List<Course> = emptyList(),
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
    fun pushDelete(course: Course, reminders: List<ReminderSetting> = emptyList()) =
        push(UndoAction.Delete(course, reminders), "删除课程")

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
        groupBeforeRows: List<Course> = emptyList(),
    ) = push(UndoAction.Update(before, after, afterId, removed, reminders, groupBeforeRows), "编辑课程")

    @Synchronized
    fun pop(): UndoEntry? = stack.removeLastOrNull()

    @Synchronized
    fun clear() = stack.clear()
}