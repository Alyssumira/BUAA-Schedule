package com.buaa.schedule.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.buaa.schedule.data.backup.BackupReminderPairing
import com.buaa.schedule.data.backup.SemesterRowDecision
import com.buaa.schedule.data.backup.decideSemesterRowForRestore
import com.buaa.schedule.data.backup.indexBackupSemesterRows
import com.buaa.schedule.data.local.AppDatabase
import com.buaa.schedule.data.local.SemesterRenamePlan
import com.buaa.schedule.data.local.SemesterRenameSavePlan
import com.buaa.schedule.data.local.SemesterRenameSaveStep
import com.buaa.schedule.data.local.SemesterRowBumpPlan
import com.buaa.schedule.data.local.planSemesterRename
import com.buaa.schedule.data.local.planSemesterRenameSave
import com.buaa.schedule.data.local.planSemesterRowBump
import com.buaa.schedule.data.local.semesterRenameSourceCode
import com.buaa.schedule.data.local.toDomain
import com.buaa.schedule.data.local.toEntity
import com.buaa.schedule.data.undo.UndoManager
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.ImportHistory
import com.buaa.schedule.domain.model.ReminderSetting
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.schedule.CourseConstraints
import com.buaa.schedule.domain.schedule.ImportPlanner
import com.buaa.schedule.domain.schedule.ImportWriteOutcome
import com.buaa.schedule.domain.schedule.ImportWriteTally
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/** 备份/口令恢复结果 */
sealed interface RestoreResult {
    /**
     * 已写入数据库。
     *
     * [rebuiltSemesters] 是 T204 那笔"说实话"的账：本次恢复里**补建**出来的学期行
     * （库里没有、备份也没带该 termCode，或带了但开学日认不出来），它们的 `startDate` 是按恢复
     * 那天推出来的、不是备份里的值 —— 念恢复文案那一侧必须把它念出来，不念就是把偏移藏回静默里。
     * 空列表 = 这一次没有任何学期行是靠猜建出来的。
     *
     * **T223：账本的来源从一枚扩成两枚** —— 历史学期（`semesterGroups` 里 `code != mainCode`）
     * 之外，**主学期那一枚也算**：旧代码里主学期走 `safeSemester` 的 `?: fallbackStartDate` 就地
     * 兜底一个日历值，绕开内核、不进这本账，于是账本空着而屏幕上那句
     * "没有任何学期行是补建出来的"就成了谎话（措辞唯一来源见
     * [com.buaa.schedule.data.backup.rebuiltSemestersNote]）。现在两侧走同一判、进同一本账。
     */
    data class Applied(
        val restoredCourses: Int,
        val skippedInvalid: Int,
        val insertedManual: Int,
        val rebuiltSemesters: List<SemesterRowDecision.Rebuild> = emptyList(),
        /**
         * 备份里有、恢复后**对不出任何课程行**的提醒条数（T229 ②/#303）。
         *
         * 与 [skippedInvalid] 同一条口径：吃了用户的东西就要念得出吃了多少。改前这一档不存在 ——
         * 读侧 `associate` 先把同键多枚合成一枚，被吃掉的那些**连"没配上"都算不上**。
         */
        val remindersUnplaced: Int = 0,
    ) : RestoreResult

    /**
     * 备份里没有课程或没有节次：继续下去是「删光课表 + 删光节次表」的破坏性操作，
     * 必须由调用方拿到用户明确的二次确认后再带 allowEmptyReplacement 重试。
     */
    data object EmptyBackup : RestoreResult
}

/** 组删除的快照：整组被删掉的行连同各自的提醒，撤销时原样复原 */
data class CourseGroupSnapshot(
    val courses: List<Course>,
    val reminders: List<ReminderSetting>,
)

/** 部分周次编辑的落库结果 */
data class PartialWeeksEdit(
    /** 本次编辑真正落库的那一行 id */
    val savedId: Long,
    /** 被清掉的同类旧片段（原本会被"这周调课"覆盖） */
    val removed: List<Course>,
    /** [removed] 各自的提醒设置 */
    val reminders: List<ReminderSetting>,
)

/**
 * `IN (:ids)` 的分块大小（R5 F-32）：部分设备 SQLite 只允许 999 个绑定变量，
 * 一学期的日历映射（600–2000 行）一次性下发会抛 "too many SQL variables"。
 */
private const val SQL_IN_CHUNK = 500

class ScheduleRepository(
    private val db: AppDatabase,
) {
    private val courseDao = db.courseDao()
    private val semesterDao = db.semesterDao()
    private val timeSlotDao = db.timeSlotDao()
    private val importHistoryDao = db.importHistoryDao()
    private val reminderDao = db.reminderDao()

    /**
     * 写入口共用的互斥锁（R5 F-34）。
     *
     * 这把锁此前挂在 `ScheduleViewModel` 上，而且只有导入/恢复走它：
     * 编辑器保存、撤销、后台组件刷新分属不同作用域，一次导入的 `deleteBySemester`
     * 能把刚撤销回来的行一起抹掉。挂到 Repository（与 [AppDatabase] 实例一一对应）后，
     * 串行化与"调用方是谁"无关；对外不暴露这把锁，因此不存在嵌套获取。
     */
    private val writeMutex = Mutex()

    val courses: Flow<List<Course>> =
        courseDao.observeAll().map { list -> list.map { it.toDomain() } }

    val currentSemester: Flow<Semester?> =
        semesterDao.observeCurrent().map { it?.toDomain() }

    /** 全部已存储学期（多课表切换），按开学日期倒序 */
    val allSemesters: Flow<List<Semester>> =
        semesterDao.observeAll().map { list -> list.map { it.toDomain() } }

    /**
     * 切换"当前学期"（多课表）。
     *
     * 当前学期的判定是 semesters 表中 id 最大的一行（最近写入的学期），
     * 因此把目标学期行删除后按新 id 重新插入即可完成切换，无需 schema 变更。
     * 课程按 termCode 关联且两表无外键级联，切换不会丢任何课。
     *
     * **T249/#460：删的是「读到的那一枚行」，不再是「该 termCode 的全部行」。**
     * `semesters` 没有 termCode 唯一索引，库里同 termCode 落下两枚行是数据库层面允许的：
     * 改前这一路是「`getByTermCode` 取任一枚（那条查询连 `ORDER BY` 都没有）→ `deleteByTermCode`
     * 删掉全部同键行 → 只插回读到的那一枚」—— 用户切一次学期，另一枚行的开学日与总周数就没了，
     * 而那枚行他从没在屏上看过。现在读侧取的全部行交给内核定「顶哪一枚、留哪几枚」，
     * 删只删被顶的那一枚（见 `data/local/SemesterRowPolicy.kt`）。
     *
     * @return 本地存在该学期才返回 true
     */
    suspend fun switchSemester(termCode: String): Boolean = writeMutex.withLock {
        db.withTransaction {
            val plan = planSemesterRowBump(
                termCode,
                semesterDao.getRowsByTermCode(termCode).map { it.toDomain() },
            )
            when (plan) {
                SemesterRowBumpPlan.NothingToSwitch -> return@withTransaction false
                is SemesterRowBumpPlan.Bump -> {
                    plan.deleteIds.forEach { semesterDao.deleteById(it) }
                    semesterDao.insert(plan.target.copy(id = 0L).toEntity())
                    // 换学期后撤销栈里全是旧学期视图下的操作，pop 回来会插错学期
                    UndoManager.clear()
                    true
                }
            }
        }
    }

    val timeSlots: Flow<List<TimeSlot>> =
        timeSlotDao.observeAll().map { list -> list.map { it.toDomain() } }

    val importHistory: Flow<List<ImportHistory>> =
        importHistoryDao.observeAll().map { list -> list.map { it.toDomain() } }

    val reminders: Flow<List<ReminderSetting>> =
        reminderDao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getReminders(): List<ReminderSetting> = reminderDao.getAll().map { it.toDomain() }

    suspend fun saveReminder(setting: ReminderSetting) = writeMutex.withLock {
        reminderDao.upsert(setting.toEntity())
    }

    suspend fun deleteReminder(courseId: Long) = writeMutex.withLock {
        reminderDao.deleteByCourse(courseId)
    }

    suspend fun getCurrentSemester(): Semester? = semesterDao.getCurrent()?.toDomain()

    suspend fun getSemesterByTermCode(termCode: String): Semester? =
        semesterDao.getByTermCode(termCode)?.toDomain()

    suspend fun getAllSemesters(): List<Semester> =
        semesterDao.getAll().map { it.toDomain() }

    suspend fun getTimeSlots(): List<TimeSlot> = timeSlotDao.getAll().map { it.toDomain() }

    suspend fun getCoursesBySemester(semesterCode: String): List<Course> =
        courseDao.getBySemester(semesterCode).map { it.toDomain() }

    suspend fun getAllCourses(): List<Course> = courseDao.getAll().map { it.toDomain() }

    /**
     * 课表展示口径的课程：手动课程（semesterCode == null）+ 当前学期课程。
     * 提醒调度、Widget 等后台逻辑与界面使用同一口径。
     */
    suspend fun getDisplayCourses(semester: Semester?): List<Course> {
        val entities = if (semester == null) {
            courseDao.getAll()
        } else {
            courseDao.getDisplay(semester.termCode)
        }
        return entities.map { it.toDomain() }
    }

    /** @return 新课程行 id；入参无法归一化时返回 null 且不写库 */
    suspend fun saveCourse(course: Course): Long? = writeMutex.withLock {
        CourseConstraints.normalize(course)?.let { courseDao.insert(it.toEntity()) }
    }

    suspend fun getCourseById(id: Long): Course? = courseDao.getById(id)?.toDomain()

    /**
     * 编辑只作用于部分周次：原课程保留剩余周次，选中周次拆成新行。
     * 用于“这周调课/代课”场景，不影响其他周的原课表。
     * 拆出的新行继承原课程的提醒设置。
     *
     * @return 落库结果：真正写入的那一行 id（覆盖整门课时为原 id），
     *   连同被清掉的同类旧片段及其提醒一起返回 —— 撤销需要整组复原，
     *   只记新行会把兄弟片段永久丢掉（R5 F-35）。入参无法归一化时返回 null 且不写库。
     *   调用方（编辑器）必须用 [PartialWeeksEdit.savedId] 去写提醒，
     *   否则会把提醒写到“剩余周次”的旧行上。
     */
    suspend fun updateCoursePartialWeeks(original: Course, edited: Course): PartialWeeksEdit? {
        val normalized = CourseConstraints.normalize(edited) ?: return null
        return writeMutex.withLock {
            db.withTransaction {
                val selected = normalized.weeks.toSet()
                val remaining = original.weeks.filter { it !in selected }

                // 清掉与选中周次重叠的同类旧片段，避免重复展示。
                // 用 sourceGroupKey 索引先收窄候选（同组通常只有几行），
                // 不要再 `getAll()` 全表扫描：courseKey 的前两维就是
                // semesterCode + sourceGroupKey，有分组键时全表扫描纯属浪费；
                // 没有分组键（手动课程）才回退全表。
                // 两个分支都要跑这段：原地覆盖那一支若不查 sibling，
                // 「先拆出 5-10 周、再把回 1-20 改回」会让 5-10 那行原地留存 ——
                // 同一门课两张卡，冲突检测还报一组假冲突（R7）。
                suspend fun overlappingSiblings(exceptId: Long): List<Course> {
                    val groupKey = normalized.sourceGroupKey
                    val candidates = if (groupKey != null) {
                        courseDao.getByGroupKey(groupKey).map { it.toDomain() }
                    } else {
                        courseDao.getAll().map { it.toDomain() }
                    }
                    return candidates.filter {
                        it.id != exceptId &&
                            ImportPlanner.courseKey(it) == ImportPlanner.courseKey(normalized) &&
                            it.weeks.any { w -> w in selected }
                    }
                }

                if (remaining.isEmpty()) {
                    val removed = overlappingSiblings(exceptId = normalized.id)
                    val removedReminders = if (removed.isEmpty()) {
                        emptyList()
                    } else {
                        reminderDao.getByCourses(removed.map { it.id }).map { it.toDomain() }
                    }
                    removed.forEach { courseDao.delete(it.toEntity()) }
                    courseDao.update(normalized.toEntity())
                    return@withTransaction PartialWeeksEdit(normalized.id, removed, removedReminders)
                }
                courseDao.update(original.copy(weeks = remaining.sorted()).toEntity())
                val removed = overlappingSiblings(exceptId = original.id)
                val removedReminders = if (removed.isEmpty()) {
                    emptyList()
                } else {
                    reminderDao.getByCourses(removed.map { it.id }).map { it.toDomain() }
                }
                removed.forEach { courseDao.delete(it.toEntity()) }
                val newId = courseDao.insert(normalized.copy(id = 0L).toEntity())
                reminderDao.getByCourse(original.id)?.let {
                    reminderDao.upsert(it.copy(courseId = newId))
                }
                PartialWeeksEdit(newId, removed, removedReminders)
            }
        }
    }

    /**
     * 把名称/地点/校区/颜色/学分同步到同组全部片段（不含时间、教师、周次——
     * 同组片段本就可能有不同的时间安排）。被改动的片段标记为手动修改，
     * 不再被下次导入覆盖。
     *
     * 这是唯一不经过 [CourseConstraints.normalize] 的写路径，因此外观字段
     * 在这里单独做归一化，避免非法自定义色被扩散到同组所有片段。
     *
     * 学分必须一起同步：[com.buaa.schedule.domain.schedule.SemesterStats] 按组取**组内最大值**
     * （一门课拆成三段不能算三遍学分），不同步就等于"改小永远改不动"。但 `credit == null`
     * 在这里表示"这次没填"而不是"填了 0 分"，直接覆盖会把兄弟片段上已有的学分抹掉，
     * 所以只有非空才传播——想清空某一门课的学分就去单条编辑那一段。
     *
     * **这一支交出它自己的结论**（[GroupAppearanceEdit]，T128 = T122 排的那枚前置）：
     * 逐字段已经等于目标态的那些行**一行都不写**，改掉了的行连同它们**改之前**的值一起交回去。
     * 调用点 [com.buaa.schedule.ui.ScheduleViewModel.updateCourse] 拿它判"这一趟到底动没动东西"，
     * 判据本体在 [groupAppearanceRow]（纯函数，零 android、零时钟）。
     *
     * @return 空表 = 整组本来就是这个样子，这一次什么都没写到
     */
    suspend fun updateCourseGroupAppearance(course: Course): GroupAppearanceEdit {
        val groupKey = course.sourceGroupKey ?: return GroupAppearanceEdit(emptyList())
        val customColor = CourseConstraints.normalizeCustomColorArgb(course.customColorArgb)
        return writeMutex.withLock {
            db.withTransaction {
                val beforeRows = mutableListOf<Course>()
                courseDao.getByGroupKey(groupKey).forEach { entity ->
                    val domain = entity.toDomain()
                    val written = groupAppearanceRow(domain, course, customColor) ?: return@forEach
                    beforeRows += domain
                    courseDao.update(written.toEntity())
                }
                GroupAppearanceEdit(beforeRows.toList())
            }
        }
    }

    /** @return 课程行 id；入参无法归一化时返回 null 且不写库 */
    suspend fun updateCourse(course: Course): Long? = writeMutex.withLock {
        CourseConstraints.normalize(course)?.let { normalized ->
            courseDao.update(normalized.toEntity())
            normalized.id
        }
    }

    /**
     * 删一门课 + 连带清掉它的提醒，返回**这一趟到底删没删到**（[CourseDeletion]）：事务内读回来的行本身
     * 才算数，提醒快照不算。形状为何这样选、全仓唯一调用点、谁拿返回值当"这课存在过"的证据，逐处写在
     * `CourseDeletionPolicy.kt` 段首（判据本体 [deletionRemovedSomething]）；没读到行就什么都不动。
     */
    suspend fun deleteCourse(course: Course): CourseDeletion = writeMutex.withLock {
        db.withTransaction {
            // 先按 id 把行读回来：删的是库里那一行，不是调用方手里那份可能已经过期的对象
            val rows = listOfNotNull(courseDao.getById(course.id)?.toDomain())
            val reminders = rows.mapNotNull { reminderDao.getByCourse(it.id)?.toDomain() }
            val deletion = courseDeletionOf(rows, reminders)
            if (deletion.removedAnything) deleteCourseRow(rows.first())
            deletion
        }
    }

    /**
     * 一次删掉同一门课的多个片段，并把删掉的行连同各自的提醒作为快照返回（撤销用）。
     *
     * 调用方此前在 UI 层循环 [deleteCourse]：任意一次失败就留下"三个片段删了两个"
     * 的中间态，而且每条删除各自推一条撤销记录，一次撤销只能捞回一个片段。
     * 快照在**同一事务内**读，因此不会出现"快照里没有、实际却被删掉"的兄弟片段。
     */
    suspend fun deleteCourseGroup(courses: List<Course>): CourseGroupSnapshot {
        if (courses.isEmpty()) return CourseGroupSnapshot(emptyList(), emptyList())
        return writeMutex.withLock {
            db.withTransaction {
                val rows = courses.mapNotNull { courseDao.getById(it.id)?.toDomain() }
                if (rows.isEmpty()) return@withTransaction CourseGroupSnapshot(emptyList(), emptyList())
                val ids = rows.map { it.id }
                val reminders = reminderDao.getByCourses(ids).map { it.toDomain() }
                rows.forEach { courseDao.delete(it.toEntity()) }
                reminderDao.deleteByCourses(ids)
                CourseGroupSnapshot(rows, reminders)
            }
        }
    }

    /**
     * 执行一条撤销记录：整段在**同一个事务**里完成（R5 F-34/F-35）。
     *
     * 此前这段逻辑在 ViewModel 里逐步调用本类的公开方法：删掉拆出来的行、
     * 重新插入 before、再挂提醒，每步各自成事务 —— 中途进程被杀或撞上导入，
     * 就留下"原行已删、恢复未写入"的半截状态，且重插的 before 换新 id 后提醒失联。
     */
    suspend fun applyUndo(action: UndoManager.UndoAction) {
        writeMutex.withLock {
            db.withTransaction {
                when (action) {
                    is UndoManager.UndoAction.Delete ->
                        insertWithReminders(listOf(action.course), action.reminders)
                    is UndoManager.UndoAction.DeleteGroup ->
                        insertWithReminders(action.courses, action.reminders)
                    is UndoManager.UndoAction.Create ->
                        courseDao.getById(action.course.id)?.toDomain()?.let { deleteCourseRow(it) }
                    is UndoManager.UndoAction.Update -> undoUpdate(action)
                }
            }
        }
    }

    /**
     * 撤销一条「编辑课程」条目：四步，全在 [applyUndo] 那一趟事务里。
     *
     * 1. 拆行另发的那一行（`afterId != before.id`）按 `courseKey` 校验后删掉；
     * 2. **T137 新增**：组那一支改掉的兄弟行（[UndoManager.UndoAction.Update.groupBeforeRows]）
     *    逐行**按 id** 回写它们的改前版 —— 读不到那一行 / 那一 id 上已是别的课就**明确不动**；
     * 3. **主行**复原，判据在 `MainRowUndoPolicy.kt`（T139 补的就是这一档）：那一 id 上仍是这次编辑
     *    留下的那一行 ⇒ 原地回写改前版；**读不到** / 那一 id 上已是**别的课** ⇒ 都以**新 id** 重插
     *    并重挂它的提醒（与第 2 步相反，两族代价不对称，账逐条写在 `GroupRowUndoPolicy.kt` 段首）；
     * 4. 补回这次编辑顺手清掉的片段（R5 F-35）。
     *
     * 第 2 步排在第 3 步**之前**是**语义**不是风格：组写跑在主行写之后 ⇒ 它交回的表里那一版主行
     * 是"改完之后"的，只有拿 `before` 收尾的那一趟才回得到真正的改前值。次序由
     * `UndoUpdateEntryGuardTest` 第 ⑧ 层钉；把两趟换成"主行在前"会让撤销对主行等于没撤销。
     */
    private suspend fun undoUpdate(action: UndoManager.UndoAction.Update) {
        // 部分周次拆行时，先删掉新建/改写的那一行，再恢复原课程。两道守卫：
        // - afterId 对应的行可能在此期间被导入流程替换成了**别的课**，
        //   按 id 盲删会删掉无关课程；必须校验 courseKey 仍是这次撤销的目标。
        // - before.id 同理，可能已被占用于其他课程，此时应改为新增而不是覆盖
        //   （这一道今天才真落地，就在下面复原主行那一趟里，判据见 MainRowUndoPolicy.kt）：
        //   兄弟行那一族遇着同一枚事实选的是"明确不动"，主行选"换新 id 插"，两族结论相反。
        if (action.afterId != null && action.afterId != action.before.id) {
            courseDao.getById(action.afterId)?.toDomain()
                ?.takeIf { ImportPlanner.courseKey(it) == ImportPlanner.courseKey(action.after) }
                ?.let { deleteCourseRow(it) }
        }
        // 组那一支改掉的兄弟行（T137）：**按 id** 逐行回写它们改之前那一版。
        // 这一趟必须在下面复原主行**之前**：组写跑在主行写之后，它交回的表里若含主行那一 id，
        // 那一版其实是「改完之后」的，只有主行那一趟（拿 action.before 收尾）才回得到真正的改前值。
        // 读不到那一行、或那个 id 上已经是别的课 ⇒ 明确不动（不重插），代价账与两档取舍的理由
        // 逐条写在 GroupRowUndoPolicy.kt 段首；复原主行用的是另一手（读不到、或那一 id 上已是别的课
        // ⇒ 都换**新 id** 重插 + 重挂提醒，T139 补的就是后一半，判据在 MainRowUndoPolicy.kt）。
        action.groupBeforeRows.forEach { snapshot ->
            val held = courseDao.getById(snapshot.id)?.toDomain()
            val sameCourseAtId =
                held != null && ImportPlanner.courseKey(held) == ImportPlanner.courseKey(snapshot)
            if (groupRowUndoDisposition(held, sameCourseAtId) == GroupRowUndoDisposition.Restore) {
                // 快照是从我们自己那张表里读回来的那一版 ⇒ 原样写回，不再过 normalize
                // （normalize 是给进来的用户数据把门的，它会重排周次/节次、trim 名称、把脏学分冲成 null）
                courseDao.update(snapshot.toEntity())
            }
        }
        val current = courseDao.getById(action.before.id)?.toDomain()
        // 主行这一趟也要问「这一 id 上还是不是这次编辑那一族留下的行」（T139，收的就是本函数
        // 头上那句「before.id 同理……应改为新增而不是覆盖」此前只有承诺、没有落地的那一格）。
        // 参照物**两枚取或**，不许照抄兄弟行那一趟的量法（拿 before 当尺子会把自己刚改过的那一行
        // 判成别人的课 ⇒ 每次撤销多插一行），逐条理由与两枚参照物各自的落点在 MainRowUndoPolicy.kt 段首：
        // - normalize(after)：原地改写那一支，库里就是改完之后那一版（尺子取归一化后的，与写库同一手）
        // - before：部分周次拆行那一支，afterId 那行已被第 1 步删掉，before.id 上留的恰是改之前那一版
        val afterKey = CourseConstraints.normalize(action.after)?.let { ImportPlanner.courseKey(it) }
        val stillThisEditsRow = current != null && ImportPlanner.courseKey(current).let { key ->
            key == afterKey || key == ImportPlanner.courseKey(action.before)
        }
        // 两档都写库，区别只在落在哪一行上：读不到 / 已被别的课占用 ⇒ 换**新 id** 复原并把提醒
        // 按新 id 重挂（insertWithReminders 那一手），绝不原地覆盖无关课程那一行。
        when (mainRowUndoDisposition(current, stillThisEditsRow)) {
            MainRowUndoDisposition.RestoreInPlace ->
                CourseConstraints.normalize(action.before)?.let { courseDao.update(it.toEntity()) }
            MainRowUndoDisposition.RestoreAsNewRow ->
                insertWithReminders(listOf(action.before), action.reminders)
        }
        insertWithReminders(action.removed, action.reminders)
    }

    /** 以新 id 重新入库若干课程（原行已删除，期间 id 可能已被导入占用），并把快照里的提醒挂回新行 */
    private suspend fun insertWithReminders(
        courses: List<Course>,
        reminders: List<ReminderSetting>,
    ) {
        courses.forEach { course ->
            val newId = CourseConstraints.normalize(course)?.let { courseDao.insert(it.toEntity()) }
                ?: return@forEach
            reminders.filter { it.courseId == course.id }.forEach {
                reminderDao.upsert(it.copy(courseId = newId).toEntity())
            }
        }
    }

    /** 删行 + 清掉它的提醒，避免孤儿提醒继续触发 */
    private suspend fun deleteCourseRow(course: Course) {
        courseDao.delete(course.toEntity())
        reminderDao.deleteByCourse(course.id)
    }

    /**
     * 每个 termCode 只保留一行（旧行删除后新行 id 最大，保持
     * “最近保存的学期 = 当前学期”语义），避免重复导入累积重复行。
     *
     * **这一句从注释级升到写路级**（T249/#460）：它靠的不是数据库约束（`semesters` 没有 termCode
     * 唯一索引），而是「写内容的每一跳都先 `deleteByTermCode` 再插一枚」—— 本文件里
     * [saveSemester]、`replaceSemesterCoursesInTx`、[saveSemesterWithRename]、恢复那两枚
     * 由 `getByTermCode == null` 闸住的 insert 全按这一形写。唯一不照这一形删的是
     * [switchSemester]：那一跳不写新内容，按 termCode 删全部等于顺手吃掉用户从没看过的兄弟行。
     */
    suspend fun saveSemester(semester: Semester) {
        writeMutex.withLock {
            db.withTransaction {
                semesterDao.deleteByTermCode(semester.termCode)
                semesterDao.insert(semester.toEntity())
            }
        }
    }

    /**
     * 学期「重命名」：把挂在 [oldCode] 下的课程整体改挂到 [newCode] 并移除旧学期行。
     *
     * 设置页的学期代码是可编辑框，用户改代码的本意就是给当前课表换代码；
     * 只动 semesters 行会让课程仍挂旧代码，CourseFilter 一过滤，
     * 保存瞬间课表整体"消失"。新代码下已有别的课表时拒绝，防止两份课表搅成一份。
     *
     * T249/#461：这一枚的判据与写步骤都搬进了 `data/local/SemesterRowPolicy.kt`，
     * 与 [saveSemesterWithRename] **共用同一份计划** —— 「新代码下已有别的课表就拒」不长出第二把尺。
     * 签名、锁的位置、事务的边界、拒绝档的返回值一字未改（卡面 §5）。
     */
    suspend fun renameSemesterCourses(oldCode: String, newCode: String): Boolean = writeMutex.withLock {
        db.withTransaction {
            when (
                val plan = planSemesterRename(
                    oldCode = oldCode,
                    newCode = newCode,
                    hasCoursesUnderNewCode = courseDao.getBySemester(newCode).isNotEmpty(),
                )
            ) {
                SemesterRenamePlan.Rejected -> return@withTransaction false
                is SemesterRenamePlan.Apply -> {
                    applySemesterRowSteps(plan.steps)
                    true
                }
            }
        }
    }

    /**
     * 设置页「保存学期」那一跳的**合并事务**（T249/#461）：需要改代码时，
     * 「课程整体改挂 + 删旧学期行」与「写新学期行」在**同一枚** `db.withTransaction` 里跑完。
     *
     * 改前它是 VM 里的两枚独立事务（`renameSemesterCourses` 一枚、`saveSemester` 另一枚），
     * 中间进程被杀 ⇒ 旧行已删、新行未插 ⇒ `semesters` 落回「没设学期」，
     * 开学日与总周数永久丢，而屏上一句话都没有。合成一枚事务之后那个窗口不存在：
     * 事务要么整组落下，要么一个字都不落。
     *
     * 「要不要改代码」由 [semesterRenameSourceCode] 定（库里那一枚存在且 termCode 变了），
     * 判据本体在 `data/local/SemesterRowPolicy.kt`；库里那一枚是**在事务内读的**，
     * 所以 VM 侧再也拿不到「读到旧值、写时已经不是」的那枚半成品。
     *
     * @return false = 新代码下已有别的课表被拒（**不落新行、不动学期、一个字节都没写**），
     *   调用方保持那句用户可见文案；true = 已落库。
     */
    suspend fun saveSemesterWithRename(semester: Semester): Boolean = writeMutex.withLock {
        db.withTransaction {
            val current = semesterDao.getCurrent()?.toDomain()
            val plan = planSemesterRenameSave(
                currentRow = current,
                target = semester,
                hasCoursesUnderNewCode = semesterRenameSourceCode(current, semester.termCode) != null &&
                    courseDao.getBySemester(semester.termCode).isNotEmpty(),
            )
            when (plan) {
                SemesterRenameSavePlan.Rejected -> return@withTransaction false
                is SemesterRenameSavePlan.Apply -> {
                    applySemesterRowSteps(plan.steps)
                    true
                }
            }
        }
    }

    /**
     * 把内核给出的写侧步骤**按列表顺序**逐枚落库；顺序就是 [planSemesterRename] /
     * [planSemesterRenameSave] 里排好的顺序（先改挂课程、再删旧行、最后收口 + 插新行）。
     *
     * ⚠️ 这一枚函数自己不裹事务，也**不许被拆成两枚事务跑**：调用方必须在同一枚
     * `db.withTransaction` 里把整组跑完 —— #461 的修法本体就落在这条约束上，
     * 形状守卫 `data/local/SemesterRowShapeGuardTest` 按它钉写侧原子性。
     */
    private suspend fun applySemesterRowSteps(steps: List<SemesterRenameSaveStep>) {
        steps.forEach { step ->
            when (step) {
                is SemesterRenameSaveStep.ReassignCourses ->
                    courseDao.reassignSemester(step.fromCode, step.toCode)

                is SemesterRenameSaveStep.DeleteRowsForTermCode ->
                    semesterDao.deleteByTermCode(step.termCode)

                is SemesterRenameSaveStep.WriteSemesterRow ->
                    semesterDao.insert(step.semester.toEntity())
            }
        }
    }

    /**
     * 节次时间表整表替换，避免 id 自增导致的重复累积。
     */
    suspend fun saveTimeSlots(slots: List<TimeSlot>) {
        writeMutex.withLock {
            db.withTransaction {
                timeSlotDao.deleteAll()
                timeSlotDao.upsertAll(slots.map { it.toEntity() })
            }
        }
    }

    suspend fun addImportHistory(history: ImportHistory) {
        importHistoryDao.insert(history.toEntity())
    }

    suspend fun clearImportHistory() {
        importHistoryDao.clearAll()
    }

    // ---- 系统日历同步映射 ----

    suspend fun getCalendarSyncEntries(): List<com.buaa.schedule.data.local.CalendarSyncEntity> =
        db.calendarSyncDao().getAll()

    /** 只取某个目标日历的映射：避免全表读出后在内存里过滤 */
    suspend fun getCalendarSyncEntriesFor(
        calendarId: Long,
    ): List<com.buaa.schedule.data.local.CalendarSyncEntity> =
        db.calendarSyncDao().getByCalendar(calendarId)

    suspend fun applyCalendarSyncMappingChanges(
        upserts: List<com.buaa.schedule.data.local.CalendarSyncEntity>,
        deleteIds: List<String>,
    ) {
        db.withTransaction {
            if (upserts.isNotEmpty()) db.calendarSyncDao().upsertAll(upserts)
            deleteIds.chunked(SQL_IN_CHUNK)
                .forEach { db.calendarSyncDao().deleteByIds(it) }
        }
    }

    /**
     * 只删除指定课次的映射（成功清理的日历事件）。
     * 部分批次清理失败时调用方只传成功的 id，失败的映射保留下来供下次重试。
     */
    suspend fun removeCalendarSyncEntries(occurrenceIds: List<String>) {
        occurrenceIds.chunked(SQL_IN_CHUNK)
            .forEach { db.calendarSyncDao().deleteByIds(it) }
    }

    /**
     * 导入合并默认策略：
     * - 按学期维度覆盖导入课程；
     * - 手动修改过的导入课程（isManualOverride）保留，不被再导入覆盖；
     * - 手动新增课程（semesterCode == null）与其他学期课程不受影响；
     * - 与旧课程 key 相同的导入课程复用旧 id，保住按 courseId 关联的提醒设置。
     * - 入库前统一经过领域约束归一化，非法条目直接丢弃。
     */
    suspend fun replaceSemesterCourses(
        semester: Semester,
        importedCourses: List<Course>,
    ) {
        writeMutex.withLock {
            db.withTransaction {
                replaceSemesterCoursesInTx(semester, importedCourses.mapNotNull(CourseConstraints::normalize))
            }
            // 整学期已被替换，之前攒的撤销快照指向的都是旧行，再 pop 会插回旧数据或删掉新数据
            UndoManager.clear()
        }
    }

    /**
     * 「替换学期课程 + 写一条导入历史」在**同一个事务**里完成。
     *
     * 调用方此前分两次调 [replaceSemesterCourses] 和 [addImportHistory]：
     * 两者各自独立事务，中间失败（进程被杀、磁盘满）会留下
     * "课表已经换掉、历史里却查不到这次导入" 的不一致状态，
     * 用户看到的是「导入记录失踪但课表变了」。
     *
     * **T181①：返回值是这本账，不是 Unit。**
     * 入库前那一刀 [CourseConstraints.normalize] 会判死若干条（星期几越界、周次全越界、
     * 没有节次、名称空白），此前这些行只在事务里蒸发、调用方连"少了"都不知道 ——
     * 而确认卡那句「导入完成：新增 N」念的是预览条数，于是屏幕上的数比课表里的多。
     * 现在由 [ImportWriteTally] 在同一趟里数清留下的行与判死的条数并交回，
     * 那句文案才有"跳过 M 条无效"这一格可念（判据与措辞见
     * [com.buaa.schedule.ui.importing.importCompletionMessage]）。
     *
     * 同一条账也顺手补进**导入历史那一行**：`courseCount` 在导入记录页是当"N 条课程"念给
     * 用户听的，写进去的必须是真进了库的条数，否则修好一句、留下另一句同源的不实话。
     */
    suspend fun replaceSemesterCoursesWithHistory(
        semester: Semester,
        importedCourses: List<Course>,
        history: ImportHistory,
    ): ImportWriteOutcome {
        // normalize 是纯判据：先在锁外数好这本账，事务里写的就是数过的同一份（不许各跑一遍）
        val outcome = ImportWriteTally.tally(importedCourses)
        writeMutex.withLock {
            db.withTransaction {
                replaceSemesterCoursesInTx(semester, outcome.writtenCourses)
                importHistoryDao.insert(history.copy(courseCount = outcome.writtenCount).toEntity())
            }
            UndoManager.clear()
        }
        return outcome
    }

    private suspend fun replaceSemesterCoursesInTx(semester: Semester, importedCourses: List<Course>) {
        semesterDao.deleteByTermCode(semester.termCode)
        semesterDao.insert(semester.toEntity())
        val existing = courseDao.getBySemester(semester.termCode).map { it.toDomain() }
        courseDao.deleteBySemester(semester.termCode)
        val plan = ImportPlanner.buildImportPlan(existing, importedCourses)
        courseDao.insertAll(plan.map { it.toEntity() })
        deleteRemindersOfDroppedCourses(existing, plan)
    }

    /**
     * 删掉本次被替换掉、且 id 未被复用的课程的提醒行。
     *
     * reminders 表没有外键级联（`deleteBySemester` 只动 courses），不显式清理
     * 会让提醒表随每次导入单调增长，孤儿行还可能撞上下一次复用的 id。
     */
    private suspend fun deleteRemindersOfDroppedCourses(existing: List<Course>, plan: List<Course>) {
        val keptIds = plan.mapTo(HashSet()) { it.id }
        val droppedIds = existing.map { it.id }.filterNot { it in keptIds }
        droppedIds.chunked(SQL_IN_CHUNK).forEach { reminderDao.deleteByCourses(it) }
    }

    /**
     * 替换某学期的课程但不改写学期行（避免把历史学期顶成“当前学期”）；
     * 学期行不存在时**只能经内核**（`data/backup/BackupSemesterRestorePolicy.kt` 的
     * `decideSemesterRowForRestore`）决定用备份带来的日期、还是补建并如实标出来（T204）。
     *
     * 旧形状（`8caf3f7` 上这一段的逐字原文，守卫按"抹注释后 0 处"钉它）是：库里查不到该 termCode
     * 就补一行 `startDate = fallbackStartDate.toString(),` 加 `totalWeeks = 20,`。
     * 换机恢复多学期备份时，每一枚历史学期都被补成「恢复那天开学、20 周」，
     * 而备份里本来就可能带着那一学期的真实开学日（`BackupData.semesters`，读侧由调用方
     * 按 termCode 配对后当 [backupRow] 递进来）。同机恢复（库里已有该行）无损 —— 那正是它没被看见的原因。
     *
     * T223：主学期那一路也走同一枚内核（`restoreBackupDataInTx` 里直接判 + 分三路落库），
     * 这一枚函数继续管历史学期，差别只有 `mainSemester` 那一枚参数按各自的答复递 false / true；
     * 两问的裁定写在内核 KDoc 的「两问的结论」那一节。
     *
     * @return 只有「补建」那一等返回非 null（要落进恢复结果的补建账），其余等返回 null。
     */
    private suspend fun replaceCoursesForExistingSemesterInTx(
        semesterCode: String,
        imported: List<Course>,
        fallbackStartDate: LocalDate,
        backupRow: Semester?,
    ): SemesterRowDecision.Rebuild? {
        val decision = decideSemesterRowForRestore(
            termCode = semesterCode,
            backupRow = backupRow,
            existingRow = semesterDao.getByTermCode(semesterCode)?.toDomain(),
            fallbackStartDate = fallbackStartDate,
            // 周次证据：这批课程里出现过的最大周次（`CourseConstraints.normalize` 保证已落 1..MAX_WEEK）
            observedMaxWeek = imported.flatMap { it.weeks }.maxOrNull(),
            // 历史学期：库里那一行是这台设备此刻的事实，备份不许顶它（第一问的答案，同内核 KDoc）
            mainSemester = false,
        )
        val rebuilt = when (decision) {
            // 库里已有这一枚学期行：一个字节都不动（同机恢复无损那一档）
            is SemesterRowDecision.KeepExistingInDb -> null
            // 备份带了这一 termCode ⇒ 开学日有出处，不进补建账
            is SemesterRowDecision.InsertFromBackup -> {
                semesterDao.insert(decision.semester.toEntity())
                null
            }
            // 只能补建 ⇒ 开学日是按 [fallbackStartDate] 推的，必须留痕
            is SemesterRowDecision.Rebuild -> {
                semesterDao.insert(decision.toSemester().toEntity())
                decision
            }
        }
        replaceCoursesOnlyInTx(semesterCode, imported)
        return rebuilt
    }

    /**
     * 只换某一学期的课程，**学期行一个字都不碰**（`replaceSemesterCoursesInTx` 的对偶：那一枚连行带课一起写）。
     *
     * 两个落点：① 历史学期那一判给出 KeepExistingInDb / InsertFromBackup / Rebuild 之后都要把课程换掉；
     * ② T223 之后主学期在「库里已有那一行而备份的开学日认不出来」时也走这里 —— 那时结论是
     * KeepExistingInDb，旧代码却在这一格无条件 delete+insert 把好日期顶成恢复那天。
     */
    private suspend fun replaceCoursesOnlyInTx(semesterCode: String, imported: List<Course>) {
        val existing = courseDao.getBySemester(semesterCode).map { it.toDomain() }
        courseDao.deleteBySemester(semesterCode)
        val plan = ImportPlanner.buildImportPlan(existing, imported)
        courseDao.insertAll(plan.map { it.toEntity() })
        deleteRemindersOfDroppedCourses(existing, plan)
    }

    /**
     * 备份/口令恢复：整个过程在单个 Room 事务内，任一步失败全部回滚。
     *
     * 幂等性设计（重复恢复同一备份结果一致）：
     * - 其他学期课程先按 id 复用替换，主学期最后写入 —— "当前学期"就是 id 最大的
     *   学期行（`data/local/SemesterDao` 的 `getCurrent()` / `observeCurrent()` 就是
     *   `ORDER BY id DESC LIMIT 1`），顺序反过来会让恢复后的首页停在历史学期上（看起来"课表空了"）；
     * - **每一枚学期行都走同一判**（`data/backup/BackupSemesterRestorePolicy.kt` 的
     *   `decideSemesterRowForRestore`，T223 之后主学期不再例外）：库里已有就不动、
     *   备份带了且日期认得出来就用备份的日期、两边都没有才补建并记进补建账；
     *   补建行仍先于主学期写入，其 id 小于最后写入的主学期（见上面的次序理由）；
     * - 手动课程按合并语义，只插入本地缺失的，不删除用户手动添加的课程；
     * - 提醒按 (courseKey, 片段依据) 对回恢复后的课程，**逐枚配对**（T229 ②/#303，判据在
     *   `data/backup/BackupReminderPairing.kt`）。改前这里是「按 courseKey 归并成一枚 + 只落一行」，
     *   而那把键的七维里没有 `weeks` ⇒ 同一门课拆出的多枚片段共用一枚键、后写吃前写。
     *
     * ⚠️ 旧形状（T223 收掉的那一格）：主学期在写库前自己 parse 备份的 `startDate`，
     * 认不出来就 `?: fallbackStartDate` 就地换成"恢复那天所在的周一"再无条件 delete+insert。
     * 库里那一行是好日期时被顶成猜的、库里没有那一行时也是猜的，而两种情况都**不进补建账** ⇒
     * 恢复文案念"没有任何学期行是补建出来的"。今天这一路只有日期认得出来才写备份的值。
     *
     * @param backupReminders 备份里的提醒条目（**逐枚**递进来，不在调用点按键归并）：
     *   同键多枚 = 同一门课的多个片段各有各的设置，归并一次就少一枚。
     * @param backupSemesterRows 备份里带全的学期行（`BackupData.semesters`，老备份为空列表时
     *   由 `indexBackupSemesterRows` 回落到 [backupSemester] 那一枚）。按 termCode 与课程分组配对。
     * @param allowEmptyReplacement 备份里一条课程都没有时的开关。默认拒绝：
     *   一份字段被改名的新备份会被 ignoreUnknownKeys 静默解成空列表，
     *   照单执行就是删光课表后提示"恢复成功：0 条课程"。
     */
    suspend fun restoreBackupData(
        backupSemester: Semester?,
        courses: List<Course>,
        timeSlots: List<TimeSlot>,
        backupReminders: List<BackupReminderPairing.Setting>,
        fallbackStartDate: LocalDate,
        allowEmptyReplacement: Boolean = false,
        backupSemesterRows: List<Semester> = emptyList(),
    ): RestoreResult = writeMutex.withLock {
        restoreBackupDataInTx(
            backupSemester = backupSemester,
            courses = courses,
            timeSlots = timeSlots,
            backupReminders = backupReminders,
            fallbackStartDate = fallbackStartDate,
            allowEmptyReplacement = allowEmptyReplacement,
            backupSemesterRows = backupSemesterRows,
        )
    }

    private suspend fun restoreBackupDataInTx(
        backupSemester: Semester?,
        courses: List<Course>,
        timeSlots: List<TimeSlot>,
        backupReminders: List<BackupReminderPairing.Setting>,
        fallbackStartDate: LocalDate,
        allowEmptyReplacement: Boolean,
        backupSemesterRows: List<Semester>,
    ): RestoreResult = db.withTransaction {
        val normalized = courses.mapNotNull(CourseConstraints::normalize)
        val skippedInvalid = courses.size - normalized.size
        if (normalized.isEmpty() && !allowEmptyReplacement) return@withTransaction RestoreResult.EmptyBackup

        // 备份可能来自手工编辑或更早版本：学期原点必须归一到周一、总周数必须限幅，
        // 否则 ScheduleOccurrences 全表按 dayOfWeek-1 偏日期、currentWeekOrNull 返回越界周次
        // （今日视图与提醒静默全空）。节次表同理——坏时间会让整个时间轴错位。
        // ⚠️ T223：这一刀**不再由仓储自己 parse**。旧形状是
        // `runCatching { ... }.getOrNull() ?: fallbackStartDate` —— 备份带来的开学日认不出来时
        // 就地兜底一枚日历值，整学期日期与周次编号一起错位，且不进补建账（文案此时念
        // "没有任何学期行是补建出来的"，那一档是谎话）。归一 / 限幅 / 认不出来怎么办全在
        // `data/backup/BackupSemesterRestorePolicy.kt` 那一判里，与历史学期同一枚（守卫按
        // 「app/src/main 里 0 处」钉这一形）。
        val safeSlots = timeSlots.filter { com.buaa.schedule.domain.schedule.isValidTimeSlot(it) }

        val mainCode = backupSemester?.termCode ?: normalized.firstNotNullOfOrNull { it.semesterCode }
        val manual = normalized.filter { it.isManualCourse }
        val semesterGroups = normalized
            .mapNotNull { course -> course.semesterCode?.let { code -> code to course } }
            .groupBy({ it.first }, { it.second })

        // 备份里带全的学期行按 termCode 配对（老备份 / 老口令没有列表时回落到单字段那一枚）
        val backupRows = indexBackupSemesterRows(backupSemesterRows, backupSemester)
        // 补建账：「库里没有、备份也没（能认出来的）日期」那一等进得来，最后交给恢复文案念出来。
        // T223：主学期那一枚也算，账本来源两侧同口径（少了主学期这一档，文案就在说谎）。
        val rebuiltSemesters = mutableListOf<SemesterRowDecision.Rebuild>()

        // 历史学期在前、主学期在最后：见 KDoc 的幂等性说明
        semesterGroups.forEach { (code, group) ->
            if (code != mainCode) {
                replaceCoursesForExistingSemesterInTx(code, group, fallbackStartDate, backupRows[code])
                    ?.let(rebuiltSemesters::add)
            }
        }
        if (mainCode != null) {
            // 主学期：与历史学期**同一枚内核**（T223），差别只在 `mainSemester = true` 买通的第一问 ——
            // 备份的日期认得出来时它顶掉库里那一行（那正是"恢复"这件事本身，而且这一行必须最后写才
            // 落得下"当前学期"= id 最大的那一行）；日期认不出来时退回与历史学期同一答复：
            // 库里有就整行不动、库里没有才补建并进这本账。两问的裁定见内核 KDoc「两问的结论」。
            val mainImported = semesterGroups[mainCode].orEmpty()
            // 配对表里配不到（列表没这一项、或单字段的 termCode 是空白被索引丢掉）时，
            // 单字段那一枚就是主学期这一路的标的行 —— 不许让它退成"备份压根没带"那一等。
            val mainBackupRow = backupRows[mainCode] ?: backupSemester?.takeIf { it.termCode == mainCode }
            when (
                val decision = decideSemesterRowForRestore(
                    termCode = mainCode,
                    backupRow = mainBackupRow,
                    existingRow = semesterDao.getByTermCode(mainCode)?.toDomain(),
                    fallbackStartDate = fallbackStartDate,
                    observedMaxWeek = mainImported.flatMap { it.weeks }.maxOrNull(),
                    mainSemester = true,
                )
            ) {
                // 认不出来 + 库里已有 ⇒ 那一行一个字节都不动（旧代码正是这一格把当前学期的好日期顶掉的）
                is SemesterRowDecision.KeepExistingInDb -> replaceCoursesOnlyInTx(mainCode, mainImported)
                // 日期有出处 ⇒ 连学期配置一起写，delete+insert 让它成为最后一行 = 当前学期
                is SemesterRowDecision.InsertFromBackup ->
                    replaceSemesterCoursesInTx(decision.semester, mainImported)
                // 两边都没有 ⇒ 补建，且**必须进补建账**：这一枚不记账，恢复文案就是谎话
                is SemesterRowDecision.Rebuild -> {
                    replaceSemesterCoursesInTx(decision.toSemester(), mainImported)
                    rebuiltSemesters += decision
                }
            }
        }

        // 手动课程：只插入本地没有的（按课程身份键判断）。
        // 走 semesterCode 索引取手动课，不要 getAll() 全表扫。
        val existingManualKeys = courseDao.getManualCourses()
            .map { ImportPlanner.courseKey(it.toDomain()) }
            .toSet()
        val manualToInsert = manual.filter { ImportPlanner.courseKey(it) !in existingManualKeys }
        courseDao.insertAll(manualToInsert.map { it.toEntity() })

        // 备份没带节次表（或带的全是非法行）就保持现状：恢复课表不等于清空用户的节次时间
        if (safeSlots.isNotEmpty()) {
            timeSlotDao.deleteAll()
            timeSlotDao.upsertAll(safeSlots.map { it.toEntity() })
        }

        // 按 courseKey 回查 id 时同样不要全表扫：提醒只可能指向
        // 本次恢复涉及的学期（或手动课程），按学期收窄即可。
        // 注意 codes 为空必须用 getManualCourses 兜底（Room 的 `IN ()` 会抛错）。
        val restoredCodes = semesterGroups.keys.toList()
        val restoredCourses = if (restoredCodes.isEmpty()) {
            courseDao.getManualCourses()
        } else {
            courseDao.getBySemestersOrManual(restoredCodes)
        }
        // T229 ②/#303：这里改**逐枚配对**，不再 `associateBy { courseKey }`。
        // 改前是两把行序尺叠在一起：课程侧 associateBy 让同键只留一枚，留哪一枚由
        // `CourseDao.getBySemestersOrManual`（无 ORDER BY）的行序决定；设置侧的 associate 让同键
        // 后写吃前写，吃的顺序由 `ReminderDao.getAll`（同样无 ORDER BY）决定。结果是
        // 用户关掉的那半重新响、改过提前量的那半回默认，而且每轮恢复可能换一枚活下来。
        // 判据（含退档口径与 unplaced 那本账）全在 BackupReminderPairing 那一枚纯 JVM 内核里。
        val pairing = BackupReminderPairing.pair(
            settings = backupReminders,
            rows = restoredCourses.map { BackupReminderPairing.rowOf(it.toDomain()) },
        )
        pairing.assignments.forEach { assignment ->
            reminderDao.upsert(
                ReminderSetting(
                    courseId = assignment.courseId,
                    enabled = assignment.enabled,
                    advanceMinutes = CourseConstraints.normalizeAdvanceMinutes(assignment.advanceMinutes),
                ).toEntity()
            )
        }

        // 整表已被备份替换，内存里攒的撤销快照指向的都是替换前的行
        UndoManager.clear()

        RestoreResult.Applied(
            restoredCourses = normalized.size,
            skippedInvalid = skippedInvalid,
            insertedManual = manualToInsert.size,
            rebuiltSemesters = rebuiltSemesters,
            remindersUnplaced = pairing.unplaced.size,
        )
    }
}

/**
 * 后台路径（广播 / 服务 / 组件）取 Repository 的统一入口：
 * 优先复用 Application 级实例，避免每次事件都重新装配一层 DAO 包装。
 */
fun Context.scheduleRepository(): ScheduleRepository {
    val app = applicationContext
    return (app as? com.buaa.schedule.BUAAApplication)?.repository
        ?: ScheduleRepository(AppDatabase.getInstance(app))
}
