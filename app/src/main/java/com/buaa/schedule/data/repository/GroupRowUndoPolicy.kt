package com.buaa.schedule.data.repository

import com.buaa.schedule.domain.model.Course

/**
 * 「撤销一条『编辑课程』条目时，组那一支改掉的**兄弟行**该怎么复原」的判据内核（T137，
 * 收的是 T128 在 `ui/UndoUpdateAdmission.kt` 段首如实登记的那一半残账）。
 *
 * 纯判据：零 android import、零时钟读取、不碰 DAO 也不碰数据库 —— 那一行**现在**在库里读不读得到、
 * 读到的那一行还是不是同一门课，一律由调用点 [ScheduleRepository.undoUpdate] 在同一趟事务里
 * 量好了当参数递进来（本仓口径，同 `CourseDeletionPolicy.kt`、`CourseGroupAppearancePolicy.kt` 那一族）。
 * 条目本体是 [com.buaa.schedule.data.undo.UndoManager.UndoAction.Update.groupBeforeRows]；
 * "调用点有没有真的按这一判复原"由源码核对守卫 `UndoUpdateEntryGuardTest` 第 ⑦⑧ 层钉，本文件只管判据本身。
 *
 * ## 这一判要回答的那一格
 *
 * 兄弟片段**可能在用户按下「撤销」之前就已经不在库里了**。两个真实成因：
 * - 教务整学期重导：`replaceSemesterCourses` 先 `deleteBySemester` 再重插 ⇒ 同一门课换了行 id
 *   （T137 卡面点名的那一个）；
 * - 用户自己又动手删了那个片段（撤销栈按红线捞的是栈顶，那笔身份账归 T123，本卡不动）。
 *
 * 于是"按 id 复原"在这一族里有三型可选，本内核立在第二型上：**读得到、而且那一 id 上仍是同一门课
 * ⇒ 原地回写它的改前版；读不到 ⇒ 什么都不动；读到的已经是别的课 ⇒ 也不动**。
 *
 * ## 为什么是"跳过"，而主行那一支恰恰相反（"读不到就重插"，`undoUpdate` 裸 :348-353）
 *
 * 两族的**代价不对称**，不是风格差异：
 * 1. **主行读不到时不重插 = 那门课从课表上消失。**主行是这次编辑的靶子、用户屏幕上正看着那一格，
 *    条目里也带着它的提醒快照（[com.buaa.schedule.data.undo.UndoManager.UndoAction.Update.reminders]），
 *    所以 `insertWithReminders` 那一手能把课与提醒一起接回来 —— 重插是唯一不丢数据的支。
 * 2. **兄弟行读不到时重插 = 屏幕上多出一行重复课。**重导那一族里那一门课**还在**，只是换了 id：
 *    再插一遍就同时留着新旧两行。这正是 `CourseDeletionPolicy.kt` 段首记过的 phantom 复活病
 *    （"撤销一条根本没发生的删除，把用户刚删掉的东西真插回库里"），同一族修法在那儿被判过一次。
 * 3. **兄弟行的提醒没进条目**（那一支只改外观字段、不删行，挂在原行上的提醒一直跟着它）。
 *    重插出来的是新 id ⇒ 提醒跟不过去：用户会为一件"撤不干净"的小事换来一次**课前提醒丢失**，
 *    比留着旧色更疼。原地回写同一 id 则天然带着它自己的提醒，无需也不该重挂。
 * 4. 另一成因（用户随后自己删了那个片段）里，重插等于替用户复活他刚删掉的东西 ——
 *    那一次删除另有它自己的撤销条目，不该由这条换色记录代劳。
 *
 * **另一支为什么不选**：「缺失就重插」（照抄主行那一手）只在"那一行真的没了、且库里没有等价行"时
 * 才对，而这种情形在本族里恰恰判不出来 —— 条目只带得回行的**内容**，带不回"它是被重导换号了
 * 还是被用户删了"这一笔因果。要把因果也带回来，得先给撤销条目加身份（T123 那张需要装机证据的卡），
 * 本卡按红线不越过去。跳过那一支的最坏结果是"某一格颜色没撤动"，重插那一支的最坏结果是
 * "多一行重复课"或"丢一条提醒" —— 两边不同量级。
 *
 * ## 第三档（那个 id 上已是别的课）为什么单独存在
 *
 * 只判"读没读到"会漏掉 id 被**占用**的情形：按 id 盲写就把一门无关的课改成了快照里那一版 ——
 * 破坏面比"没撤动"大得多。这一档照搬同函数里 `afterId` 那道守卫的尺子（[ImportPlanner.courseKey]，
 * 全仓"算不算同一门课"只有它一份），本内核不另立第二把尺子，只把两枚事实**路由**成结论。
 *
 * ## 证据档位（如实写明）
 *
 * 两档：JVM 表驱动单测 `GroupRowUndoPolicyTest`（判据两侧：三档各自独立、`current == null` 时
 * 第二枚参数不参与判定）+ 源码核对守卫第 ⑦⑧ 层（条目带得回 / 调用点按 id 读 / 读的次序）。
 * `:app` 的 JVM 测试面只有 `junit`，**没有 Robolectric、也没有能在 JVM 上跑 Room 的缝**，
 * 所以"库里那一行今天到底在不在"这一判被抽成纯函数来钉 —— **没有 SQLite 级、更没有装机级证据**
 * （红线零设备）：真重导一次学期、再点撤销看那一格颜色回没回，本卡没做。
 */

/** 撤销时「组那一支改掉的某一行」的处置结论。唯一的造法是 [groupRowUndoDisposition]。 */
internal enum class GroupRowUndoDisposition {
    /** 那一行还在、而且那个 id 上仍是同一门课 ⇒ 按 id 原地回写它的改前版 */
    Restore,

    /** 库里读不到那一行（整学期重导换号 / 用户随后删了它）⇒ 什么都不动，也**不**重插 */
    SkipMissing,

    /** 那个 id 上现在是**别的课** ⇒ 不许覆盖无关课程那一行（与 `afterId` 那道守卫同尺） */
    SkipOccupiedByOtherCourse,
}

/**
 * 判据本体：三档里只有 [GroupRowUndoDisposition.Restore] 会写库，其余两档都是**明确的不动**。
 *
 * @param current 事务内按快照的行 id **读回来**的那一行；null = 库里已经没有它。
 * @param sameCourseAtId 那一行（[current] 非空时）与快照是不是**同一门课**，由调用点按
 *                       [ImportPlanner.courseKey] 量。**[current] 为 null 时它不参与判定** ——
 *                       行都没读到，"是不是同一门课"无从谈起，也就绝不该把"没读到"翻成
 *                       [GroupRowUndoDisposition.SkipOccupiedByOtherCourse]（那一格在表驱动单测里）。
 */
internal fun groupRowUndoDisposition(
    current: Course?,
    sameCourseAtId: Boolean,
): GroupRowUndoDisposition = when {
    current == null -> GroupRowUndoDisposition.SkipMissing
    !sameCourseAtId -> GroupRowUndoDisposition.SkipOccupiedByOtherCourse
    else -> GroupRowUndoDisposition.Restore
}
