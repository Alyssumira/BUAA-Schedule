package com.buaa.schedule.data.repository

/**
 * 「撤销链重插那一手该把哪一枚主键交给 DAO」的判据内核（#520 F-06，T278）。
 *
 * 纯判据：零 android import、零时钟、不碰 DAO 也不碰数据库 —— 与同目录 `MainRowUndoPolicy.kt`、
 * `GroupRowUndoPolicy.kt`、`CourseDeletionPolicy.kt` 同一口径（本仓红线：`:app` 的 JVM 测试面只有
 * junit，没有 Robolectric、也没有能在 JVM 上跑 Room 的缝，所以"这一手写库该带哪一枚主键"被抽成
 * 纯函数来钉）。
 *
 * ## 病：内核判的是「换新 id」，执行层交回的还是旧 id
 *
 * 判据内核那一档的承诺（`MainRowUndoPolicy.kt`，逐字）:
 * ```
 * /** 库里读不到那个 id，或它已被**别的课**占用 ⇒ 以**新 id** 重插，并把提醒按新 id 重挂 */
 * RestoreAsNewRow,
 * ```
 * 段首理由第 3 条直接点名执行层：「`insertWithReminders` 会把课插成新 id、并把 `action.reminders`
 * 里属于 `before.id` 的那几条**按新 id 重挂**」。执行层也确实接了这一档
 * （`ScheduleRepository.undoUpdate`，逐字）:
 * ```
 * MainRowUndoDisposition.RestoreAsNewRow ->
 *     insertWithReminders(listOf(action.before), action.reminders)
 * ```
 * 而 `insertWithReminders` 体内那一手是：
 * ```
 * val newId = CourseConstraints.normalize(course)?.let { courseDao.insert(it.toEntity()) }
 *     ?: return@forEach
 * ```
 * `CourseConstraints.normalize` 的 copy 清单（`domain/schedule/CourseConstraints.kt`）只有
 * `name / periods / weeks / colorIndex / customColorArgb / credit` 六项 —— **不清 `id`** ⇒
 * 交回的那一行仍带 `before.id`；而 DAO 是（`data/local/CourseDao.kt`，逐字）:
 * ```
 * @Insert(onConflict = OnConflictStrategy.REPLACE)
 * suspend fun insert(course: CourseEntity): Long
 * ```
 * ⇒ 生成的 SQL 实为 `INSERT OR REPLACE INTO courses (id, …) VALUES (<before.id>, …)`：
 * `id` 非 0 走的是显式主键那一手，不是 autoGenerate 新号 ⇒ **此刻正持有那一枚 id 的无关课程被整行
 * 删掉、快照顶号进来**。`reminders` 的主键就是 courseId（`data/local/ReminderEntity.kt`，逐字
 * `@PrimaryKey` / `val courseId: Long`）⇒ 被顶掉那一门课自己的提醒行原样归给冒进来的那一门，
 * 而屏上念的是「已撤销」。`RestoreAsNewRow` 与 `RestoreInPlace` 在写库上成了同一只手，
 * 内核第四维（"换号"）买到的东西为零。
 *
 * ## 第⓪问的裁定：**甲**（只在撤销写侧把主键抹成 0L）
 *
 * **乙（改 DAO 冲突策略 + 存在性前置检查）不选**：
 * - `courseDao.insert` 在 main 有三枚调用点：`ScheduleRepository.saveCourse`（"新课程行 id"那一手）、
 *   `updateCoursePartialWeeks` 拆行那一路（已经是 `normalized.copy(id = 0L)` 的形状）、以及本卡这一路。
 *   换策略会同时改到 `saveCourse` 那一枚**不在撤销链上**的写路径 ⇒ 甲"不许动别的写路径"这一条就破了；
 * - `applyUndo` 交回 `Unit`，撞键那一档今天**没有**能把话说给用户的通道（`undoUpdate` 一路走到
 *   `insertWithReminders`，返回值没人吃）⇒ "collision 要有真实可见的结果"得先造一枚跨四层的返回值，
 *   那是另一张卡的量级，而且本卡红线之外还得改 VM 与提示条；
 * - 按甲落地之后 `id = 0L` ⇒ Room 走 `CourseEntity` 的 `@PrimaryKey(autoGenerate = true)` 另号，
 *   **撞键这一档结构性不会发生** ⇒ 乙那套机械（ABORT/IGNORE + 前置查询 + 报话）是死支。
 *
 * **丙（档位 + DAO 报回来的占用情况 → 插新 / 中止回话 的判据内核）不选**：这一链的判据内核已经存在，
 * 就是 `MainRowUndoPolicy.kt`，它的结论域只有两档（`RestoreInPlace` / `RestoreAsNewRow`，且段首明写
 * "这一域里**没有**「什么都不动」那一档"）。丙要往结论域里加第三档，而那第三档吃的两样东西
 * （DAO 占用事实、一条用户文案通道）都不在这张卡的盘面上 ⇒ 新开一枚内核只会给同一格长出第二把尺，
 * 并与 `GroupRowUndoPolicy` 的三档形状撞名（本仓登记过"名字教人犯错"那一族）。
 *
 * ## 甲的边界：**为什么不把 `id = 0L` 写进 `normalize` 本体**（本文件最要紧的一格）
 *
 * `CourseConstraints.normalize` 在 main 有七枚调用点（`ScheduleRepository` 六枚 +
 * `ImportWriteTally.tally` 一枚）。若把 `id = 0L` 塞进它的 copy 清单，**撤销链之外**的写路径会跟着变：
 * - `updateCoursePartialWeeks`：`normalized` 在「覆盖整门课」那一支走 `courseDao.update(normalized.toEntity())`
 *   （`@Update` 按主键匹配 ⇒ 主键一抹平**一个字也写不进去**），而且那一支把 `normalized.id` 当
 *   `PartialWeeksEdit.savedId` 交回，其 KDoc 明写「调用方（编辑器）必须用 [PartialWeeksEdit.savedId]
 *   去写提醒」⇒ 提醒会挂到 id 0 上；
 * - `updateCourse`：同一形（`courseDao.update(normalized.toEntity())` + 交回 `normalized.id`）
 *   ⇒ **用户编辑一门课变成静默 no-op**；
 * - `undoUpdate` 的 `RestoreInPlace` 那一支：`normalize(action.before)?.let { courseDao.update(it.toEntity()) }`
 *   ⇒ **最常见的撤销（那一行还是它的）整条失效**；
 * - `ImportWriteTally.tally`：交回的行喂给 `insertAll`，而 `ImportPlanner.buildImportPlan` 的契约是
 *   「保留全部 isManualOverride 课程（原 id 不变）；导入课程中与旧课程 key 相同的**复用旧 id**」
 *   ⇒ 覆盖导入会全部换号，挂在行上的提醒与手动旗标跟着错位。
 * 另有一枚只读点（`normalize(action.after)` 只为算 `ImportPlanner.courseKey`）—— `courseKey` 的七维里
 * 不含 `id`，那一枚倒是抹了也无感；但上面四枚已经足够判死这条路。
 * `normalize` 是"给进来的用户数据把门的"，不该替撤销链决定身份（`undoUpdate` 段首那句原话）。
 *
 * 于是本卡只动 `insertWithReminders` 那一手 —— 它是撤销链四支共用的**唯一**重插口子
 * （`applyUndo` 的 `Delete` / `DeleteGroup` 两支 + `undoUpdate` 的 `RestoreAsNewRow` 支 +
 * 补回被清掉片段的 `removed` 支），四支的身份本来就该是「新行」，前两支的契约还写在
 * `UndoManager.kt` 段首：「Delete：删除课程，撤销 = 以新 id 重新入库」。
 * 同文件 `updateCoursePartialWeeks` 拆行那一路的 `normalized.copy(id = 0L)`、以及
 * `saveSemesterWithRename` 那一路的 `plan.target.copy(id = 0L)` 是本仓已有的两处同形先例 ——
 * 本卡不新造写法，只把第四枚漏掉的补上，并把取法收在这枚内核里（写侧不许再各写一遍裸 `0L`）。
 *
 * ## 在册同族、本卡**有意不收**的两枚（如实写明，不是遗漏）
 *
 * - `saveCourse` 那一枚 `normalize(course)?.let { courseDao.insert(it.toEntity()) }`：它的调用点
 *   （`ScheduleViewModel.saveCourse`）今天只在"编辑器新建那一路"给 `id = 0L` 的行压 Create 条目，
 *   但插行那一手本身**没有**"入参 id 必须是 0"的闸门 —— 同一族病（在册 #304 那一串）。改它会换掉
 *   另一条写路径持久化的东西，超出甲的范围，本卡不动。
 * - #521 F-01（`updateCoursePartialWeeks` 两支 `removed` 循环删课程行却不清提醒行）与
 *   #521 F-05（`UndoManager.clear()` 事务内外两形）：本卡的改动**没有**落进那两枚函数体
 *   （只落在 `insertWithReminders`），按卡面"只在改动触及同一枚函数时才并卡"的条件不成立 ⇒ 不改，
 *   `SemesterRowShapeGuardTest` 因此无需重钉。
 *
 * ## 档位（如实写明）
 *
 * 两枚：JVM 表驱动单测 `UndoReinsertIdentityTest`（这一格的四枚输入组合、两侧边界）+
 * 源码形状守卫 `UndoReinsertIdentityGuardTest`（写侧那一手真的按这枚内核取主键、
 * `normalize` 的 copy 清单里仍不出现 `id`、DAO 仍是 REPLACE）。
 * **没有** SQLite 级、更没有装机级证据（红线零 adb、零设备）：真重导一次学期、改号之后再点撤销
 * 看那一格回没回、那一门被顶掉的课回没回来，本卡没做。
 */

/**
 * Room 那一手的字面值：`@PrimaryKey(autoGenerate = true)` 的行带 0L 去插 ⇒ 不走显式主键、由 Room
 * 另号。库里任何一行都拿不到这一枚 id（`id` 从 1 起），所以"交 0L"与"撞上一门课的号"互斥。
 */
internal const val FRESH_ROW_PRIMARY_KEY: Long = 0L

/**
 * 撤销落库那一手该交给 DAO 的主键。
 *
 * @param snapshotId 快照里那一枚旧主键 —— **它此刻可能正被一门无关的课持有**
 *                   （教务整学期重导是 `deleteBySemester` 之后 `insertAll` ⇒ 号全换）。
 * @param landsOnSnapshotRow 这一趟是不是**按原 id 落回它自己那一行**：
 *                           `MainRowUndoDisposition.RestoreInPlace` 与兄弟行回写那一族才是（它们走
 *                           `courseDao.update`，`@Update` 按主键匹配 ⇒ 主键一抹平就一个字也写不进去）；
 *                           落进 `insertWithReminders` 的那四支（`Delete` / `DeleteGroup` /
 *                           `RestoreAsNewRow` / `removed` 片段）**一律不是** ⇒ 一律另号。
 * @return 重插族：[FRESH_ROW_PRIMARY_KEY]（绝不去碰此刻持有旧号的那一行）；
 *         原地回写族：[snapshotId] 原样带回（这一档的主键就是它的全部身份，不许被顺手抹掉）。
 */
internal fun undoWritePrimaryKey(snapshotId: Long, landsOnSnapshotRow: Boolean): Long =
    if (landsOnSnapshotRow) snapshotId else FRESH_ROW_PRIMARY_KEY
