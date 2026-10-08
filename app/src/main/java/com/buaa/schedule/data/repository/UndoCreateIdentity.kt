package com.buaa.schedule.data.repository

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.schedule.CourseConstraints
import com.buaa.schedule.domain.schedule.ImportPlanner

/**
 * 「撤销一条『新增课程』条目时，那一枚 id 上的行到底能不能删」的判据内核（#527，T287）。
 *
 * 纯判据：零 android import、零时钟、不碰 DAO 也不碰数据库 —— 那一格上此刻坐着谁、条目里那枚快照
 * 归一化之后是什么样子，一律由调用点 [ScheduleRepository.applyUndo] 的 Create 支在同一趟事务里
 * 量好了当参数递进来（本仓口径，同 `MainRowUndoPolicy.kt`、`GroupRowUndoPolicy.kt`、
 * `CourseDeletionPolicy.kt`、`UndoReinsertIdentity.kt` 那一族）。
 *
 * ## 病：撤销链四支里只有这一支删行不查身份
 *
 * `ScheduleRepository.applyUndo` 的 Create 支（改前逐字）：
 * ```
 * is UndoManager.UndoAction.Create ->
 *     courseDao.getById(action.course.id)?.toDomain()?.let { deleteCourseRow(it) }
 * ```
 * 它按**主键**删那一行，然后 `deleteCourseRow` 顺手 `reminderDao.deleteByCourse(course.id)` ——
 * 全程没问过「那一枚 id 上现在坐着的，是不是用户当初新建的那门课」。而同一条链上另外三支**都问**：
 * - `undoUpdate` 第 1 步（拆行另发那一行）：`takeIf { ImportPlanner.courseKey(it) == ImportPlanner.courseKey(action.after) }`
 *   —— 注释逐字写着「按 id 盲删会删掉无关课程；必须校验 courseKey 仍是这次撤销的目标」；
 * - `undoUpdate` 第 2 步（兄弟行）：`groupRowUndoDisposition(held, sameCourseAtId)`，第三档就叫
 *   `SkipOccupiedByOtherCourse`；
 * - `undoUpdate` 第 3 步（主行）：`mainRowUndoDisposition(current, stillThisEditsRow)`，
 *   那一格「已是别的课」⇒ 换新 id 重插，绝不原地覆盖。
 *
 * 于是 Create 支是**唯一一枚自己没长出尺子的那一支**。它不是没人知道这枚事实 —— #520（T278）刚把
 * 同一个占用事实从 `INSERT OR REPLACE` 那一侧收掉（`UndoReinsertIdentity.kt`），而这一支根本不经过
 * 那枚重插口子，所以那一刀的防线盖不到这里：同一枚「重导之后 id 换人」的病，从 DELETE 这一侧又进来了。
 *
 * ## 第⓪问的裁定：**甲**（`ImportPlanner.courseKey`），失败那一档的**处置**取丁（拒删 + 说话 + 条目留着）
 *
 * 尺子只有甲一枚候选。`MainRowUndoPolicy.kt` 段首逐字：「两枚内核共享的是**尺子**
 * （`ImportPlanner.courseKey`，全仓"算不算同一门课"只有这一把），不共享的是结论域」——
 * 本卡改的正是结论域，不是尺子。
 *
 * **乙（与条目快照逐字段相等）不选**，两重死因：
 * 1. 条目里的快照是**归一化之前**的编辑器草稿（`ScheduleViewModel.saveCourse`：
 *    `UndoManager.pushCreate(course.copy(id = savedId))`，那枚 `course` 就是 `CourseEditorScreen.performSave`
 *    现搭的对象），而库里那一行是 `CourseConstraints.normalize` 之后的产物 —— 逐字段相等**结构性不成立**，
 *    最常见的「新建之后什么都没动」那一档都会被判成"不是那门课"。这一格与 `MainRowUndoPolicy` 段首
 *    「尺子取 `normalize` 后的 `after`（写库走的就是这一手），而不是用户草稿本体，免得 `periods`
 *    未经排序这一类归一化漂移造成假『被占』」是同一枚账。
 * 2. 比 courseKey 更宽的那几维（`weeks` / `credit` / `colorIndex` / `customColorArgb` / `remark` /
 *    `campus` / `alias`）里没有一枚参与身份，把它们拉进判据 ⇒ 用户改个颜色再撤销建课就撤不动了。
 *
 * **丙（id + semesterCode + name）不选**：它给同一件事长出第二把尺（本仓红线），而且**更松**——
 * 同名不同教师/不同节次的两门课在它眼里是同一门，那正是「删掉一门无关的课」的入口；
 * 名字还是用户最容易改过的一维。
 *
 * **丁（证明不了就不删 + 告诉用户为什么）不选为尺子、选为失败那一档的处置**：丁要说的是
 * 「拿什么当身份」，而它自己不带尺子；把丁的处置部分收进来是对的 —— 见下面「失败那一支」。
 *
 * ## 结论域为什么不是两档（与 `GroupRowUndoDisposition` 同形状、语义不同）
 *
 * 「读不到那一格」与「那一格上是别人」在这一支里**必须分开**：
 * - [UndoCreateDeletion.NothingToDelete]：那一枚 id 上压根没有行 ⇒ 用户新建的那门课**已经不在库里**，
 *   撤销要达到的状态本来就成立 ⇒ 一个字节都不动、条目照常消费（这与 `deleteCourse` 那句
 *   「没读到行就什么都不动」同一手）。
 * - [UndoCreateDeletion.RefuseNotTheCreatedCourse]：那一格上坐着一行**对不上号**的东西 ⇒ 删它就是
 *   删掉用户从没打算删的那门课，而且这一坏**没有任何一条撤销记录能撤回来**
 *   （`MainRowUndoPolicy` 段首理由第 2 条逐字），所以这一档不写库、并把整件事说出去。
 *
 * 快照归一化不过那一档不单开：`saveCourse` 里 `normalize` 不过就不返回 id、也就不压 Create 条目，
 * 所以「条目带着枚归一化不过的快照」在这条链上结构上到不了 ⇒ 不为它造一枚死档，
 * 只让它落进同一拒删档（话术因此写「对不上号」而不是「它是另一门课」）。
 *
 * ## 失败那一支：条目不能被安静吃掉（复用 T183 那一枚，不新造）
 *
 * 条目在落库**之前**就被 `UndoManager.pop()` 消费掉（`UndoManager` 段首 T183 那一节），
 * 而 `applyUndo` 交回 `Unit`。所以拒删这一档**不能**安静返回：安静返回等于屏幕上什么都没变、
 * 提示条却念一句「已撤销：新增课程」，同时那条撤销永久没了 —— 正是 #183 记过的形状。
 * 修法也不新造机械：抛 [UndoCreateRefusedException]，走 `ScheduleViewModel.undo()` 里**已经存在**的
 * 失败通道 —— `undoFailureKindOf` 按类名查表，这张表**没有**点名本异常 ⇒ 落进 `Unexplained` 档
 * （`restoreEntry = true`）⇒ `UndoManager.restoreTop(last)` 凭 `lastPopped` 那张票据把条目放回栈顶，
 * 而那半句「还留着」只在 `restoreTop` 真的回了 `true` 时才念（票据作废那一档退回改前那句、不宣称留着）。
 * 文案本体由 [UndoCreateDeletion.RefuseNotTheCreatedCourse.reason] 供给，前缀与 `heldSuffix` 都归 VM 那一侧。
 *
 * 为什么不给本异常在 `ui/UndoFailureRecovery.kt` 那张表里单点名成一档：那张卡的测试明钉
 * 「结论域恰好四档」+「表里每一枚类名都有格子」+「榜上的档序恰好那三档」，加第五档要连表行、
 * 榜序、格子与 docs 那格一起重钉，而 `ui/` 那两枚文件在本卡红线之外。本异常**故意不被点名** ⇒
 * 保守落进「留条目、不许诺重试」那一档，这正是本卡要的处置。点名成 `RejectedBySnapshot` 会把条目
 * 吃掉（#183 的病），点名成 `WriteLockBusy` 会许一句「可以再点一次」而重试永远是同一个结果 ——
 * 两格都由 `UndoCreateIdentityGuardTest` 钉住。
 *
 * ## 可达性（如实写明，这条要紧）
 *
 * 台账 #527 那句「重导之后再撤一次建课就会删掉一门无关的课」，按今天盘面上的写路**走不通**：
 * 撤销栈只在内存里（`UndoManager` 的 `stack` 是 `object` 私有字段，进程退出即空），而四枚会换号的
 * 写路在同一趟里全都清了栈（`switchSemester`、`replaceSemesterCourses`、`replaceSemesterCoursesWithHistory`、
 * 备份恢复那三枚 `UndoManager.clear()`）；新建课在编辑器里 `semesterCode = initialCourse?.semesterCode`
 * 即 null（手动课），`deleteBySemester` 那句 `WHERE semesterCode = :semesterCode` 碰不到它；
 * `courses.id` 是 `INTEGER PRIMARY KEY AUTOINCREMENT`，自动号永不复用；唯一两处**显式带号**的写手
 * 是 `ImportPlanner.buildImportPlan` 的「同 key 复用旧 id」（顶号那行与它同 key ⇒ 本判据必然放行）
 * 与恢复侧的 `insertAll`（`BackupCourse` 压根没有 `id` 字段 ⇒ 交的是 0L ⇒ 另号）。
 * 剩下的窄缝只有一枚：`pop()` 跑在 `applyUndo` 取 `writeMutex` **之前**，期间导入/恢复正好提交并清栈
 * （#521 F-05 那枚「事务内外两形」的账），或双点撤销让两支并发排队 —— 那时那一格上可能是
 * 「被改过的同一门课」或「另一门课」。本卡因此按**防线**收，而不按**已复现的用户可见故障**收：
 * 这一支依赖的「没人能把无关行放到活着的 id 上」这一枚不变量，今天**没有任何东西钉着**，
 * 而 #520 那一侧的同类不变量已经被证明会破。
 *
 * ## 证据档位（如实写明）
 *
 * 三档：JVM 表驱动单测 `UndoCreateIdentityTest`（三档各自独立、同一枚 id 上坐别人那一格、
 * 以及「新建之后一个字没动」那一格必须真的判成删）+ 源码形状守卫 `UndoCreateIdentityGuardTest`
 * （Create 支不查身份就够不着 `deleteCourseRow`；拒删那一支没有裸 `pop`；本异常落不进不回推那两档）
 * + 两枚变异自证臂（朝宽 / 朝窄）。**没有** SQLite 级、更没有装机级证据（红线零 adb、零设备）：
 * 真建一门课、重导一次学期、再点撤销看那一格回没回，本卡没做。
 */

/**
 * 撤销「新增课程」时对**那一枚 id 上当前那一行**的处置结论。唯一的造法是 [undoCreateDeletion]。
 *
 * 结论里带上读回来的那一行，是为了让调用点除了 [UndoCreateDeletion.DeleteRow] 那一支之外
 * 根本没有第二枚删行的入参可拿（`deleteCourseRow` 吃的就是这一枚 `Course`）。
 */
internal sealed interface UndoCreateDeletion {

    /** 那一格上就是这次新建的那门课（按 `ImportPlanner.courseKey` 量）⇒ 删那一行，并清掉挂在它 id 上的提醒 */
    data class DeleteRow(val row: Course) : UndoCreateDeletion

    /** 那一格上读不到行 ⇒ 用户新建的那门课早就不在库里，撤销要达到的状态已成立 ⇒ 一个字节都不动 */
    data object NothingToDelete : UndoCreateDeletion

    /** 那一格上是一行**对不上号**的东西 ⇒ 不许删：删掉的就是用户从没打算删的那门课，且这一坏撤不回来 */
    data class RefuseNotTheCreatedCourse(val occupant: Course) : UndoCreateDeletion {
        /**
         * 那句为什么：值 = f(同表参数) ⇒ 按本仓口径长在类体里，不给调用点二次拼装的机会。
         * 只说「对不上号」不说「它是另一门课」—— 被改过的同一门课与别人占号在这一档里给的是同一个
         * 答复（都不许删），而两者对用户是两种事实，文案不许替内核多判一层。
         */
        val reason: String
            get() = "撤销「新增课程」没有执行：那门课当初那一格里现在坐着〈" + occupantName + "〉，" +
                "与这条撤销记录里的课程对不上号，不能拿它替用户删掉一门他没打算删的课"

        /** 那一格上的名字，取 trim 过的那一维（courseKey 用的也是 `name.trim()`） */
        val occupantName: String get() = occupant.name.trim()
    }
}

/**
 * 判据本体：三档里只有 [UndoCreateDeletion.DeleteRow] 会写库。
 *
 * @param current 事务内按 `action.course.id` **读回来**的那一行；null = 那一格上已经没有行了。
 * @param snapshot 撤销条目带的那枚快照本体（[com.buaa.schedule.data.undo.UndoManager.UndoAction.Create.course]，
 *                 一枚完整 `Course`，带着当初落库的新 id）—— **归一化之前**的草稿，
 *                 所以尺子在这一点上量：`ImportPlanner.courseKey(normalize(snapshot))`，
 *                 与 `MainRowUndoPolicy` 对 `afterKey` 的处理同一手。
 *                 [current] 为 null 时它不参与判定 —— 行都没读到，"是谁的那门课"无从谈起。
 */
internal fun undoCreateDeletion(current: Course?, snapshot: Course): UndoCreateDeletion {
    if (current == null) return UndoCreateDeletion.NothingToDelete
    val snapshotKey = CourseConstraints.normalize(snapshot)?.let { ImportPlanner.courseKey(it) }
    return if (snapshotKey != null && ImportPlanner.courseKey(current) == snapshotKey) {
        UndoCreateDeletion.DeleteRow(current)
    } else {
        UndoCreateDeletion.RefuseNotTheCreatedCourse(current)
    }
}

/**
 * 拒删那一档抛出去的那一枚（话由 [UndoCreateDeletion.RefuseNotTheCreatedCourse.reason] 供给）。
 *
 * 类名**故意不进** `ui/UndoFailureRecovery.kt` 的 `UNDO_FAILURE_TABLE`：不进表就落进 `Unexplained`
 * 那一档（`restoreEntry = true`、`retryInvitation = false`），于是条目经 `UndoManager.restoreTop`
 * 回到栈顶、而那句提示不许诺「再点一次就成了」。抛 `IllegalArgumentException` /
 * `IllegalStateException` 都不行 —— 那两枚类名在表里点过名，判成 `RejectedBySnapshot`
 * （不回推），本卡就正好犯下 #183 记过的那次事故。
 */
internal class UndoCreateRefusedException(message: String) : RuntimeException(message)
