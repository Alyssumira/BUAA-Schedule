package com.buaa.schedule.domain.editor

/**
 * 编辑器挂载闸门里「提醒那一半」的判据 —— 这一份判定的唯一一本（T381①，收在册 #660 的主体）。
 *
 * ## 纯判据（本仓硬规则）
 *
 * 零 android / androidx import、不读时钟、不读权限、不读 `Build.VERSION`、不碰 Compose、不碰
 * `StateFlow` 本身。两枚事实（这一页开的是不是一门**已在库上**的课、提醒列表发过第一行了没有）
 * 全部由调用点（`MainActivity` 编辑器目的地那一层）算好递进来，本文件一枚都不许自己去够。
 *
 * ## 病在哪
 *
 * `MainActivity` 的挂载闸门原读两半：`uiState.loading` 与 `courseMissing`。两半都翻过之后编辑器就挂上，
 * 而 `initialReminder` 是从 `ScheduleViewModel` 那枚 `reminders` StateFlow 现读的
 * （`reminders.firstOrNull { it.courseId == c.id }`）。那条 `stateIn` 的 `initialValue` 是
 * `emptyList()` —— Room 第一次返回之前**它就是当前值**，「列表还没发过第一行」与「这门课没有提醒行」
 * 在首帧上折成同一个读数。编辑器那两格提醒字段的键是 `rememberSaveable(requestedCourseId)`
 * （键里没有列表本身）⇒ 首帧读到什么就固化成什么、列表后到不回灌；而保存那一趟对已存在的课是
 * **无条件**写回（`saveCourseDraft` 里 `if (isEditingExisting) { onSaveReminder(...) }`）
 * ⇒ 用户关掉的提醒被写回成默认值（`ReminderSetting` 的 `DEFAULT_ENABLED` / `DEFAULT_ADVANCE_MINUTES`）。
 *
 * ## 为什么必须是一枚显式的「未就绪」档
 *
 * 「空列表」在这条流上有两种来路：库里真的一行提醒都没有（合法常态 —— 教务导入链对 reminders 表只删不建），
 * 以及还没读到第一行。拿前者当后者就是把尺子读反：那样「什么都没设」那一档会被永久当成「还没到」，
 * 编辑器对没有提醒行的课再也不挂载。所以就绪位只能由**上游那一次发射**翻真 ——
 * 载体是 `ScheduleViewModel` 里那枚从 `false` 起步的位，不是列表的长度。
 *
 * ## 三档（两档放行，理由不同）
 *
 * | 开的是已有课 | 提醒列表发过第一行 | 档 | 闸门 |
 * |---|---|---|---|
 * | 否（新建那一路） | 是／否 | [ReminderMountDecision.MountForNewCourse] | 挂 |
 * | 是 | 是 | [ReminderMountDecision.MountWithReminderLoaded] | 挂 |
 * | 是 | 否 | [ReminderMountDecision.WaitReminderRows] | **不挂** |
 *
 * 第一行与第二行都放行，但**不能折成一行**：新建那一路压根不写提醒行（`saveCourseDraft` 外面那层
 * `if (isEditingExisting)` 是「新建的课没有提醒行」这个常态的第三个出处），列表到没到都不必等它 ——
 * 把新建那一路也拦住就是拿一枚闸门去关一扇不写库的门，白帧归零的代价换 0 收益。
 * 第三行拦的是「此刻挂上去就会把首帧的空读数固化成用户设置」那一档，等一帧就好。
 *
 * 判据逐格账见 `domain/editor/EditorReminderMountGateTest`（三档 + 两侧边界），
 * 接线形状由 `ui/RemindersEagerlySharingGuardTest` 的 `editorSideReadinessGateIsOnTheMountGate`
 * 那一格钉着（T373 的 KDoc 早已写明：闸门落地时那一格必须红，红了改散文与判据方向，不许把它改松）。
 */

/** 挂载闸门问内核时拿到的三档读数。 */
enum class ReminderMountDecision {
    /** 新建那一路：这一趟不写提醒行，列表到没到都不必等 */
    MountForNewCourse,

    /** 编辑已有课，而且提醒列表已经发过第一行：`initialReminder` 读到的就是那一行的真值 */
    MountWithReminderLoaded,

    /** 编辑已有课，而提醒列表还没发过第一行：现在挂上去就会把空初值固化成用户设置 —— 拦住 */
    WaitReminderRows,
}

/** [ReminderMountDecision] 的判定本体。 */
object EditorReminderMountGate {

    /**
     * @param editingExistingCourse 这一页开的是不是一门已在库上的课。调用点递的是
     *   `course != null`，与编辑器 `saveCourseDraft(isEditingExisting = initialCourse != null)`
     *   同源于同一枚 `initialCourse` —— 不许在这里重算 `courseId >= 0L`，那是另一把尺
     * @param reminderRowsListed 提醒列表发过第一行了没有（`ScheduleViewModel` 那枚显式的就绪位）
     */
    fun decisionOf(
        editingExistingCourse: Boolean,
        reminderRowsListed: Boolean,
    ): ReminderMountDecision = when {
        !editingExistingCourse -> ReminderMountDecision.MountForNewCourse
        reminderRowsListed -> ReminderMountDecision.MountWithReminderLoaded
        else -> ReminderMountDecision.WaitReminderRows
    }

    /** 闸门只问这一枚：两档放行合起来就是「不是 WaitReminderRows」（本函数只是把那张表读成一个布尔） */
    fun allowsMount(
        editingExistingCourse: Boolean,
        reminderRowsListed: Boolean,
    ): Boolean =
        decisionOf(editingExistingCourse, reminderRowsListed) != ReminderMountDecision.WaitReminderRows
}
