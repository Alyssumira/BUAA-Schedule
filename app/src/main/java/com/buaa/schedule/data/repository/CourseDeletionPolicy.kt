package com.buaa.schedule.data.repository

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.ReminderSetting

/**
 * 「这一趟删除到底删没删到东西」的判据内核（T121）。
 *
 * 纯判据：零 android import、零时钟读取、不碰 DAO 也不碰数据库 —— 事务内读回来的**课程行**、
 * 以及那一行当时挂着的**提醒条数**，一律由调用点 [ScheduleRepository.deleteCourse] 量好了
 * 当参数递进来（仓库口径，见 `ui/home/DayBrowsePolicy.kt` 那一族）。"返回布尔以后调用点再各自
 * 判一次"在这一族里算违反，所以 [deletionRemovedSomething] 是全仓唯一一份判据本体，
 * 结论只经由 [courseDeletionOf] 造出来。
 *
 * ## 病（用户看得见的那一句：删一件已经不在的东西，仍然报告"删掉了"）
 *
 * 旧 `ScheduleRepository.deleteCourse` 返回 `List<ReminderSetting>`，而那份表只装得下提醒，
 * 装不下"那一行到底存不存在" ⇒ 调用点区分不开两件事：
 * - 「这门课本来就没有」：行不在 ⇒ 提醒也不在 ⇒ 空表；
 * - 「这门课有、但没设提醒」：行在 ⇒ 提醒为空 ⇒ **同样是空表**。
 * 于是 `ScheduleViewModel` 的 `deleteCourse` 无条件 `UndoManager.pushDelete(...)`、
 * 无条件报 `true`（只有抛异常才 false），首页 `handleCourseDelete` 就拿这枚布尔同时决定
 * 提示条文案与那颗「撤销」按钮在不在。
 *
 * ## 缺陷序列（连点两次删同一门课；或刷新/导入把行换号以后，屏幕上那份过期对象被删第二次）
 *
 * 1. 第一次删：行在 ⇒ 真删掉了，栈 = `[Delete(那一行)]`，提示条「已删除」+「撤销」。
 * 2. 第二次删同一份过期对象：库里已经没有那一行，`courseDao.delete` 与
 *    `reminderDao.deleteByCourse` 都是 0 行的 no-op，可旧实现照删照返回 ⇒ VM 仍报 `true`
 *    ⇒ 压进一条**指向已不存在之行的 phantom Delete**，栈 = `[真 Delete, phantom Delete]`。
 * 3. 用户点「撤销」：`undo()` 是 `UndoManager.pop()` —— 无参、捞栈顶 ⇒ 捞到的正是那条假的，
 *    [ScheduleRepository.applyUndo] 的 `Delete` 分支会把那门课 `insertWithReminders`
 *    **重新插回去**（新行 id 由数据库另发），屏幕上念一句"已撤销"，
 *    而第 1 步那次真正的删除还压在栈底、再也捞不回来。
 *
 * 卡面原判最后一环是"什么都没回来"，实测**比它更糟**：撤销一条 phantom Delete 会把那门课
 * 真的插回库里 —— 用户刚删掉的东西因为一次根本没发生的删除而复活；这期间若导入/撤销已经把
 * 同一门课以新 id 插回，屏幕上就同时留着新旧两行（重复课程）。所以本卡两头一起收：
 * 报真话（false）+ **不许压栈**（phantom 记录从一开始就不该存在）。
 * 至于第 3 步"撤销捞的是栈顶而不是本次那一笔"（先删 A、再删 B、再点 A 那条提示条上的「撤销」
 * 会把 B 捞回来），那是 `undo()` 无参这一族另有其账，本卡按红线未动，登记给编排者。
 *
 * ## 形状为何选"返回一份含行的结论"，而不是另两型
 *
 * - **返回 Boolean（删除行数）**：VM 拿到的只是"删了几行"，撤销要的**那一行本身**还得另开一趟查，
 *   两趟之间正好是导入能插队的窗口（[ScheduleRepository.applyUndo] 的 KDoc 记的就是这笔账）；
 *   而且它天然让"删掉了哪一行"留在调用点手里那份可能已过期的对象上 —— 那正是 phantom 复活时
 *   被插回库里的内容。
 * - **另开一枚新方法、保留旧签名**：留一枚会继续说谎的公开入口在原地，下一处调用点抄哪一面全凭运气。
 * - **现在这一型**：事务内读回来的行本身随结论一起出来（[CourseDeletion.Removed]），
 *   没读到行就整个事务什么都不动（[CourseDeletion.NothingRemoved]）。
 *   形状照同文件里既有的范本 [ScheduleRepository.deleteCourseGroup]：
 *   `val rows = courses.mapNotNull { courseDao.getById(it.id)?.toDomain() }` +
 *   `if (rows.isEmpty()) return@withTransaction CourseGroupSnapshot(emptyList(), emptyList())`
 *   —— 删掉的**行本身**进了快照，所以"没删到东西"在仓储层就是可判的，VM 只读结论、不再自己猜。
 *
 * ## 改形状要一起钉住的调用点账（本仓有过一次真回归就是这么合进 master 的）
 *
 * `repository.deleteCourse` 的调用点**全仓恰好一处**（逐处复算过，不是数出现次数）：
 * `ScheduleViewModel` 的 `deleteCourse` 体内那一行 `val … = repository.deleteCourse(course)`。
 * 另有两处同名函数是**另一枚函数**（VM 那一步的调用点：签名不变，返回值语义变了）——
 * `MainActivity` 的编辑器入口 `onDelete = { viewModel.deleteCourse(it) }` :892，
 * 以及 `HomeScreen` 的 `val deleted = viewModel.deleteCourse(course)` :453。
 *
 * 有没有哪一处拿返回值当"这课存在过"的证据去做**不止提示条文案**的决定？有，两处，都在 VM 那一层：
 * 1. `HomeScreen` 的 `handleCourseDelete` —— 同一枚布尔同时决定文案
 *    （「已删除「…」」/「删除失败：… 还在课表里」）和 `actionLabel = "撤销".takeIf { deleted }` :456；
 * 2. `CourseEditorScreen` 的 `if (onDelete(target)) { onBack() } else { saveError = … }` :619
 *    —— 它决定的是**要不要退出编辑页**，与提示条无关。改形状之前那条 else 支路从来没走过
 *    （不抛异常就是 true）；修完以后它会走：删不到 ⇒ 留在编辑页亮既有那句红条，
 *    随后 `MainActivity` 那枚 `val courseMissing = courseId >= 0 && course == null` :870
 *    闸门把目标行已不在屏上的编辑页弹出栈。
 *
 * 这三处源码一字未改（红线：不许顺手改文案、不许加动画），全部由源码核对守卫
 * `CourseDeletionWiringGuardTest` 逐处、按位置钉住。
 *
 * ## 证据档位（如实写明）
 *
 * 本卡只有两档证据：JVM 表驱动单测 `CourseDeletionPolicyTest`（判据两侧各四格全覆盖）+
 * 上面那枚源码核对守卫。`:app` 的 JVM 测试依赖面只有 `junit` 一枚，**没有 Robolectric、
 * 没有能在 JVM 上跑 Room 的缝**，所以"删到 / 没删到"这一判被抽成纯函数来钉 ——
 * **没有装机级、DB 级证据**（红线零设备）。仓里确实存在真 Room 的仪器化测试面
 * （`app/src/androidTest/java/com/buaa/schedule/ScheduleRepositoryTransactionTest.kt` 等 8 枚，
 * 且其中**没有任何一枚**调用过 `deleteCourse`），但本卡按红线没跑、也没为它补仪器化用例，
 * 那一档仍然欠着：改后的真实行为只推到"判据 + 接线"两层，没推到 SQLite 上真删一行。
 */

/**
 * 一次删除事务的结论。**唯一的造法是 [courseDeletionOf]** —— 也就是判据
 * [deletionRemovedSomething] 的投影；调用点拿不到原始事实，也就无从再判一次。
 */
sealed interface CourseDeletion {

    /** 事务内读回来、并且真的被删掉的那一行；[NothingRemoved] 时为 null */
    val removedCourse: Course?

    /** 那一行当时挂着的提醒：撤销时按原样挂回（缺了它撤销只回得来课程、回不来提醒） */
    val removedReminders: List<ReminderSetting>

    /**
     * 这一趟到底删没删到东西：提示条文案与撤销栈**都只读这一个结论**。
     *
     * 它是投影不是判据 —— 判据本体（"什么算删到了"）只有 [deletionRemovedSomething] 那一份，
     * 而本类的实例只能由 [courseDeletionOf] 造出来，所以这里不可能长出第二把尺子。
     */
    val removedAnything: Boolean
        get() = removedCourse != null

    /**
     * 删到了。[removedCourse] 是事务内按请求的行 id **读回来**的那一行本身，
     * 不是调用方手里那份可能已经过期的对象：撤销时插回去的就是它。
     */
    data class Removed(
        override val removedCourse: Course,
        override val removedReminders: List<ReminderSetting>,
    ) : CourseDeletion

    /** 库里没有那一行：整个事务什么都不动，撤销栈也不许多出一条记录 */
    data object NothingRemoved : CourseDeletion {
        override val removedCourse: Course? get() = null
        override val removedReminders: List<ReminderSetting> get() = emptyList()
    }
}

/**
 * 判据本体：这一趟删除算不算"删到了东西"。
 *
 * @param foundRows 事务内按请求的行 id 读回来的**课程行本身**；空表 = 库里已经没有这一行。
 *                  单行入口 [ScheduleRepository.deleteCourse] 传的是 `listOfNotNull(...)`，至多一条。
 * @param reminderCount 那一行当时挂着的提醒条数。**它不参与判定** —— 写成参数是为了把
 *                      "空提醒 ≠ 没删到"这条钉死在格子表里：旧实现只带得回提醒表，
 *                      正是拿它当了"这课存在过"的证据，于是有课没提醒判成没删、
 *                      课与提醒都被别人删光了又反过来判成删过。两头各是一格。
 */
internal fun deletionRemovedSomething(
    foundRows: List<Course>,
    reminderCount: Int,
): Boolean = foundRows.isNotEmpty()

/** 由判据造结论：删到了就把读回来的那一行连同它的提醒一起交回去 */
internal fun courseDeletionOf(
    foundRows: List<Course>,
    reminders: List<ReminderSetting>,
): CourseDeletion =
    if (!deletionRemovedSomething(foundRows, reminders.size)) {
        CourseDeletion.NothingRemoved
    } else {
        CourseDeletion.Removed(foundRows.first(), reminders)
    }
