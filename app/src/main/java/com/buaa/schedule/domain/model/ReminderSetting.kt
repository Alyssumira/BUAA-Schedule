package com.buaa.schedule.domain.model

/**
 * 一门课的课前提醒设置。
 *
 * ⚠️ **一行都没有**是这门课的常态而不是脏数据，所以"没有行"的口径必须写在这里而不是散在调用点：
 * 三条写库链里没有任何一条会为新课程建行 ——
 * - 教务导入（`data/repository/ScheduleRepository.kt` 的 `replaceSemesterCoursesInTx`）
 *   对 reminders 表只有一句 `deleteRemindersOfDroppedCourses`（删被顶掉的行），全链零 `upsert`；
 * - 备份/口令恢复（`restoreBackupDataInTx`）只对**备份里带了行**的那些课 `upsert`
 *   （`remindersByCourseKey.forEach`），而备份的那本账又来自导出时的 `getReminders()`（同样是"有才带"）；
 * - 手动新建课（`ui/editor/CourseEditorScreen.kt` 的 `saveCourseDraft`）
 *   写提醒那一行是包在 `if (isEditingExisting)` 里的 —— 新建那一路不写。
 *
 * 于是"从没进过编辑器保存过的课"就是"没有行"，占绝大多数。按卡面（#264）的判定，这一等是常态，
 * 所以这里**显式承认产品口径**：没有行 = 按 [DEFAULT_ENABLED] 与 [DEFAULT_ADVANCE_MINUTES] 提醒，
 * 并且这句口径必须出现在用户看得见的地方（编辑器「提醒」那一组的 summary 句，
 * 取 `ui/editor/CourseEditorScreen.kt` 的 `editorReminderSectionSummary`），不许只在日志里。
 *
 * 另一半账：这两枚默认值在全仓**只此一处字面量**（守卫
 * `reminder/ReminderDefaultSingleSourceGuardTest` 按枚数册子钉它）。
 * `data/local/ReminderEntity.kt`、`data/backup/BackupModels.kt`、`ui/editor/CourseEditorScreen.kt`
 * 与排铃侧读的都必须引用这里，不许多写一遍 `10` —— 同一枚默认值散在六处时改一处不改其余，
 * 症状是"设置页写着提前 10 分钟、实际响的是提前 5 分钟"这类查不出错的偏差。
 */
data class ReminderSetting(
    val courseId: Long,
    val enabled: Boolean = DEFAULT_ENABLED,
    val advanceMinutes: Int = DEFAULT_ADVANCE_MINUTES,
) {

    companion object {
        /** 默认提前分钟数：全仓唯一一处 `advanceMinutes` 的默认字面量 */
        const val DEFAULT_ADVANCE_MINUTES = 10

        /** 没有行时算不算"开着"：算 —— 这一档与上面那枚同属一条产品口径，见文件顶 KDoc */
        const val DEFAULT_ENABLED = true
    }
}

/**
 * 纯 JVM 内核：把「这门课的提醒到底是什么」从"读一行、读不到就各写各的兜底"收成一档判定。
 *
 * 三档（表驱动单测 `reminder/ReminderSettingResolutionTest` 逐档摆格子）：
 * 有行且开 / 有行但关 / 没有行。第三档不是"当成有行"，而是**带出处的默认**
 * （[Basis.NoRowDefaultOn]），排铃侧与编辑器侧读的都是同一份结论，
 * 于是"悄悄按默认值提醒"变成"说得出口的默认值提醒"。
 *
 * 零 android、零时钟、零库：入参只有那一行（可能为 null），默认值只从
 * [ReminderSetting] 那两枚常量来。
 */
data class EffectiveReminderSetting(
    val enabled: Boolean,
    val advanceMinutes: Int,
    /** 这一档是从哪儿来的：结论本身要说得清自己是读来的还是按默认造的 */
    val basis: Basis,
) {

    enum class Basis {
        /** 库里有一行、且写着开着 */
        RowOn,

        /** 库里有一行、且写着关了 —— 这一档排铃侧必须直接不收这门课 */
        RowOff,

        /** 库里没有这一行：按 [ReminderSetting.DEFAULT_ENABLED] + [ReminderSetting.DEFAULT_ADVANCE_MINUTES] */
        NoRowDefaultOn,
    }

    companion object {
        /**
         * 那三档的唯一取法。`setting == null` 走 [Basis.NoRowDefaultOn]（不是"当成没关"的隐式兜底，
         * 而是这条明写着的口径），其余两档照读那一行。
         */
        fun of(setting: ReminderSetting?): EffectiveReminderSetting = when {
            setting == null -> EffectiveReminderSetting(
                enabled = ReminderSetting.DEFAULT_ENABLED,
                advanceMinutes = ReminderSetting.DEFAULT_ADVANCE_MINUTES,
                basis = Basis.NoRowDefaultOn,
            )

            !setting.enabled -> EffectiveReminderSetting(
                enabled = false,
                advanceMinutes = setting.advanceMinutes,
                basis = Basis.RowOff,
            )

            else -> EffectiveReminderSetting(
                enabled = true,
                advanceMinutes = setting.advanceMinutes,
                basis = Basis.RowOn,
            )
        }
    }
}
