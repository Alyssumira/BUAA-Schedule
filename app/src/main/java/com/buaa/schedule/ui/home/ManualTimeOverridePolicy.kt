package com.buaa.schedule.ui.home

/**
 * 「移动课程」这一趟落库**该不该把那一行标成 manual** 的判据内核（T133b）。
 *
 * 纯判据：零 android import、零时钟读取、不碰 ViewModel 也不碰数据库 —— 要写的上课日与节次、
 * 那一行现在的上课日与节次、以及那一行**已经带没带旗标**，一律由调用点
 * [com.buaa.schedule.ui.home.HomeScreen] 的 `handleCourseMove` 量好了当参数递进来
 * （本仓口径，同 [ConflictShiftWeekScope]、`DayBrowsePolicy` 那一族）。
 * "有没有真的用上它"由源码核对守卫 `ManualTimeOverrideWiringGuardTest` 钉，本文件只管判据本身。
 *
 * ## 为什么这一枚不能恒为真（T133 的账要还的部分）
 *
 * 标 `isManualOverride = true` 的代价很贵：那一行**从此退出教务刷新的匹配与覆盖**
 * （教务之后改课名/教师/地点/周次都不再传导），而且下一次刷新会把教务那一版当新课补进来
 * ⇒ **同一门课两张卡**（`ImportPlanner.courseKey` 把 `dayOfWeek`/`periods` 算进身份钥匙，
 * `buildImportPlan` 又只交回"标了 manual 的 existing + 本批 imported"）。
 * T133 把三处 copy 无条件标了真，理由写成"落点侧已经闸在'时间真的变了'上"。那句前提
 * 只覆盖了三分之二的落点：
 * - **拖拽**：`WeekView.kt:899-902`（窄屏分支）与 `:1030-1033`（宽屏分支）的 `onDragEnd`
 *   只在 `d.targetDayIndex != d.originDayIndex || d.targetStartPeriod != d.originStartPeriod`
 *   时才立 `pendingMove` ⇒ 同值拖不回放到 handler。这一支前提成立。
 * - **缩放**：`WeekView.kt:1113` 的 `if (merged != r.course.periods)` ⇒ 同值缩放飞不到
 *   `handleCourseResize`。这一支也成立。
 * - **长按菜单/卡片动作的「移动到…」**：**没有同值闸**。`WeekView.kt:1188`（以及 `:951`、
 *   `:1097` 两枚卡片入口）立 `movePickerFor` ⇒ `:1234` 那扇 `ModalTransition` 的
 *   `onConfirm`（`:1244-1255`）**无条件**把选中的 `dayIndex / startPeriod` 装成
 *   `CourseMoveRequest` ⇒ `:1260` 那扇确认窗的「所有周」（`:1276`）与「仅本周」（`:1292`）
 *   再**无条件**回调 `onCourseMove`。而选择框的初值就是**出发那一格**
 *   （`CourseMovePickerDialog` 的 `remember(request)` 拿 `request.dayIndex` 与
 *   `request.segment.first` 起手，`:1370-1373`），所以"打开、什么都不改、下一步、确认"
 *   是一笔**逐字段什么都没改**的写库 —— 在 T133 之后它会把一门好端端的课标成 manual。
 *   用户只是点了两下、什么也没改，就换来上面那两件事；改前这一记空操作是无害的。
 *
 * 判据收在 handler 这一枚而不是选择框那一侧，是因为选择框那条路还要经过确认弹窗、落点分散，
 * 而 `handleCourseMove` 是拖拽与选择框**两条路的共同收口** —— 判据只需要一处。
 *
 * ## 为什么旗标是粘的
 *
 * 判据只回答"**这一趟**动没动时间"。那一行原本已经标着 manual（编辑器保存过、组外观同步过）
 * 时，一次空操作**不许**把它洗回 false —— 那等于让一行用户早已改过的课重新掉进教务匹配，
 * 与本卡要挡的病同一种、方向相反。所以 [originalIsManualOverride] 参与析取，与编辑器
 * `CourseEditorScreen.kt:227` 的 `initialCourse?.isManualOverride == true || …` 同一形状。
 *
 * ## 撤销链不在本判据里
 *
 * 撤销条目 `before = original` 带着原行自己的 flag（`ScheduleViewModel.updateCourse` 推栈，
 * `ScheduleRepository.undoUpdate` 用 `normalize(before)` 整行复原），所以撤销本来就会把旗标
 * 带回原样；判据不参与那一趟，本文件也不许被拿去改撤销那一路。
 */
object ManualTimeOverridePolicy {

    /**
     * @param originalIsManualOverride 那一行**现在**带没带旗标（粘住用，不许被空操作洗掉）
     * @param newDayOfWeek 这一次要写进去的上课日（handler 里的 `newDayIndex + 1`）
     * @param originalDayOfWeek 那一行现在的上课日
     * @param newPeriods 这一次要写进去的节次（整课平移后的 `shiftedPeriods`）
     * @param originalPeriods 那一行现在的节次
     * @return 落库那一行的 `isManualOverride` 该是什么
     */
    fun forCourseMove(
        originalIsManualOverride: Boolean,
        newDayOfWeek: Int,
        originalDayOfWeek: Int,
        newPeriods: List<Int>,
        originalPeriods: List<Int>,
    ): Boolean = originalIsManualOverride ||
        newDayOfWeek != originalDayOfWeek ||
        newPeriods != originalPeriods
}
