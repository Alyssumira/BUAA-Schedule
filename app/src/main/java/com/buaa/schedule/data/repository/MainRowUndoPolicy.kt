package com.buaa.schedule.data.repository

import com.buaa.schedule.domain.model.Course

/**
 * 「撤销一条『编辑课程』条目时，**主行**那一格该怎么复原」的判据内核（T139）。
 *
 * 纯判据：零 android import、零时钟读取、不碰 DAO 也不碰数据库 —— 那个 id 上现在读不读得到东西、
 * 读到的那一行还是不是这次编辑留下的那一版，一律由调用点 [ScheduleRepository.undoUpdate]
 * 在同一趟事务里量好了当参数递进来（本仓口径，同 `GroupRowUndoPolicy.kt`、`CourseDeletionPolicy.kt`）。
 *
 * ## 这一判要回答的那一格
 *
 * `undoUpdate` 第 3 步（复原主行）此前**只问读没读到**：`current != null` 那一支直接把
 * `action.before` 写到「此刻持有 `before.id` 的那一行」上。而它自己头上的注释（裸 :356 那一句）
 * 早就写着「before.id 同理，可能已被占用于其他课程，此时应改为新增而不是覆盖」——
 * **承诺过、从未落地**。第 1 步（`afterId` 那道守卫）与第 2 步（兄弟行那一族）都量了
 * `courseKey`，只有主行这一趟没量 ⇒ 教务整学期重导（`deleteBySemester` 后重插 ⇒ id 会换人）
 * 之后再点撤销，就会把一门**无关的课**覆盖成快照里那一版。本内核收的就是这一格。
 *
 * ## 判断题的答案：读回来却已是别的课 ⇒ **以新 id 重插**（[MainRowUndoDisposition.RestoreAsNewRow]），
 * ## 与"读不到"那一档同处置，绝不原地覆盖
 *
 * 理由四条：
 * 1. **主行是这次编辑的靶子，也是条目里唯一带着提醒快照的那一行**。撤销之后屏幕上那一格既不该
 *    留着用户的改动、更不该整门消失；`current != null` 在旧写法下被当成"这一行归我"，
 *    而 id 换人以后那个"归我"根本没有依据 —— 它只是"有人正占着这个号"。
 * 2. **不覆盖是硬的**：把 `before` 写到一个无关课程的行上，破坏面是「改坏了另一门课」
 *    （用户没点过它、条目里也没有它的改前值 ⇒ 这一坏**没有任何一条撤销记录能撤回来**）。
 *    换到新 id 那一手，最坏只是"屏幕上多一行"，两种坏的量级不同。
 * 3. **重插这一手本来就在，而且是这一族的正解**：`insertWithReminders` 会把课插成新 id、
 *    并把 `action.reminders` 里属于 `before.id` 的那几条**按新 id 重挂**（同函数 `Delete` /
 *    `DeleteGroup` 两支走的也是这一手）。所以"换号"这一判不会把提醒丢在半路 ——
 *    这正是兄弟行那一族拿不到的好处（它们的提醒没进条目，见下面第三节）。
 * 4. **"读不到"那一档今天已经是重插**（旧写法 `current == null` 那一支）。就本仓能观测到的事实而言，
 *    "那一 id 上没行"与"那一 id 上是别人的行"是**同一件事**：我要复原的那一行不在它应在的位置上。
 *    两枚事实给出两种处置，等于让"是否恰好有人换号占位"这一枚无关细节决定用户看不看得到那门课。
 *
 * ## 另一支为什么不选
 *
 * **(b)「明确不动」（照抄 T137 给兄弟行选的那一档）不选**：兄弟行那一族的病灶最轻是"某一格颜色没撤动"，
 * 因为那一支只改外观字段、内容本来就还是用户的；主行换的是**时间/地点/教师/名称**这一族。
 * 选 (b) 的净效果是「用户点了撤销、提示条念了『已撤销：编辑课程』、而他改的那门课停在改完之后那一版，
 * 并且这一次编辑的记录被消费掉了 —— 再点也没有」：一次被吞掉的撤销，比现状更贵。
 * 而 (b) 换来的好处只有"少一次重复行的可能" —— 这一可能今天在 `current == null` 那一支上**已经付掉了**
 * （整学期重导最常见的那一型恰恰是"读不到"：旧 id 随 `deleteBySemester` 一起没了），
 * 所以 (b) 根本不消除重复行风险，只是把换号那一型单独挑出来少插一次。
 *
 * **(c)「复用 `groupRowUndoDisposition` 那三档」不选**，见下一节。
 *
 * ## 与第 2 步那三档判据的关系：同形状、结论域**反向**，所以另开一内核
 *
 * 两枚内核**长得一样**（都收 `current: Course?` + 一枚调用点量好的布尔），差别全在结论域：
 * - `groupRowUndoDisposition` 的非写库两档叫 `SkipMissing` / `SkipOccupiedByOtherCourse` ——
 *   语义是**明确的不动**；
 * - `mainRowUndoDisposition` 的对应两档是**同一支** [MainRowUndoDisposition.RestoreAsNewRow] ——
 *   语义是**换到新的位置上去复原**，压根不是"跳过"。
 *
 * 于是复用会把判据用反：调用点得写成 `SkipMissing -> 重插` 与
 * `SkipOccupiedByOtherCourse -> 重插`，两枚名字里的 "Skip" 与实际动作相反，
 * 下一次读到 `Skip` 就少写一次插行（本仓登记过的"名字教人犯错"那一族）。
 * `GroupRowUndoPolicy.kt` 段首那四条代价账（兄弟行的提醒没进条目 / 重插出 phantom 重复行 /
 * 因果带不回来）正是主行**不成立**的四条：主行的提醒在条目里、主行不重插就是整门消失、
 * 主行的因果由它自己是这次编辑之靶子这一事实给足。
 * 两枚内核共享的是**尺子**（`ImportPlanner.courseKey`，全仓"算不算同一门课"只有这一把），
 * 不共享的是结论域。
 *
 * ## 调用点那枚布尔为什么**不能**照抄兄弟行的量法（本卡最容易做错的一格）
 *
 * 兄弟行拿快照（改前版）当参照物；主行**不行**：`action.before` 与库里那一行本来就该不同，
 * 那 differing 恰恰是这次编辑的内容。拿 before 当尺子会把"我自己刚改过的那一行"判成别人的课 ⇒
 * **每一次正常的撤销都多插一行**。所以调用点递进来的 `stillThisEditsRow` 量的是
 * 「那一 id 上的行是不是这次编辑那一族留下的」，参照物**两枚、取或**：
 * - `ImportPlanner.courseKey(normalize(action.after))` —— 原地改写那一支：库里就是改完之后那一版；
 *   尺子取 `normalize` 后的 `after`（写库走的就是这一手），而不是用户草稿本体，
 *   免得 `periods` 未经排序这一类归一化漂移造成假"被占"；
 * - `ImportPlanner.courseKey(action.before)` —— 部分周次拆行那一支：新行落在 `afterId` 上、
 *   已被第 1 步删掉，而 `before.id` 上留的恰是**改之前**那一版（`updateCoursePartialWeeks`
 *   把剩余周次写回原行）。
 *
 * 两枚都不符，才叫"这个号换人了"。
 *
 * ## 证据档位（如实写明）
 *
 * 两档：JVM 表驱动单测 `MainRowUndoPolicyTest`（结论域只有两档、`current == null` 时第二枚参数
 * 不参与判定、两枚参照物各自的可达性）+ 源码核对守卫 `UndoUpdateEntryGuardTest` 第 ⑨ 层
 * （主行那一趟真的读了这道校验、参照物是那两枚对象、写回与重插两支各自可达、四步次序仍 1→2→3→4）。
 * `:app` 的 JVM 测试面只有 `junit`，**没有 Robolectric、也没有能在 JVM 上跑 Room 的缝**，
 * 所以"库里那一行今天到底是谁的"这一判被抽成纯函数来钉 —— **没有 SQLite 级、更没有装机级证据**
 * （红线零设备）：真重导一次学期、改号之后再点撤销看那一格回没回，本卡没做。
 */

/** 撤销时「这次编辑的主行」的处置结论。唯一的造法是 [mainRowUndoDisposition]。 */
internal enum class MainRowUndoDisposition {
    /** 那个 id 上仍是这次编辑留下的那一行 ⇒ 按 id 原地回写它的改前版（提醒本来就挂着，无需重挂） */
    RestoreInPlace,

    /** 库里读不到那个 id，或它已被**别的课**占用 ⇒ 以**新 id** 重插，并把提醒按新 id 重挂 */
    RestoreAsNewRow,
}

/**
 * 判据本体：**两支都会落库**，区别只在落在哪一行上 —— [MainRowUndoDisposition.RestoreInPlace]
 * 用原来那个 id，[MainRowUndoDisposition.RestoreAsNewRow] 换新 id。
 * 这一域里**没有**"什么都不动"那一档（那是兄弟行那一族的结论，别顺手搬过来）。
 *
 * @param current 事务内按 `before.id` **读回来**的那一行；null = 库里已经没有它。
 * @param stillThisEditsRow [current] 非空时，那一行算不算「这次编辑那一族留下的行」，
 *                          由调用点按 [ImportPlanner.courseKey] 对**两枚**参照物量
 *                          （`normalize(action.after)` 或 `action.before`，逐字见段首）。
 *                          **[current] 为 null 时它不参与判定** —— 行都没读到，
 *                          "是谁的行"无从谈起，也就绝不该把"没读到"翻成另一档。
 */
internal fun mainRowUndoDisposition(
    current: Course?,
    stillThisEditsRow: Boolean,
): MainRowUndoDisposition = when {
    current == null -> MainRowUndoDisposition.RestoreAsNewRow
    !stillThisEditsRow -> MainRowUndoDisposition.RestoreAsNewRow
    else -> MainRowUndoDisposition.RestoreInPlace
}
