package com.buaa.schedule.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.buaa.schedule.data.local.AppDatabase
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
import com.buaa.schedule.domain.schedule.WeekCalculator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/** 备份/口令恢复结果 */
sealed interface RestoreResult {
    /** 已写入数据库 */
    data class Applied(
        val restoredCourses: Int,
        val skippedInvalid: Int,
        val insertedManual: Int,
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
     * @return 本地存在该学期才返回 true
     */
    suspend fun switchSemester(termCode: String): Boolean = writeMutex.withLock {
        db.withTransaction {
            val target = semesterDao.getByTermCode(termCode) ?: return@withTransaction false
            semesterDao.deleteByTermCode(termCode)
            semesterDao.insert(target.copy(id = 0L))
            // 换学期后撤销栈里全是旧学期视图下的操作，pop 回来会插错学期
            UndoManager.clear()
            true
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
     */
    suspend fun updateCourseGroupAppearance(course: Course) {
        val groupKey = course.sourceGroupKey ?: return
        val customColor = CourseConstraints.normalizeCustomColorArgb(course.customColorArgb)
        writeMutex.withLock {
            db.withTransaction {
                courseDao.getByGroupKey(groupKey).forEach { entity ->
                    val domain = entity.toDomain()
                    courseDao.update(
                        domain.copy(
                            name = course.name,
                            location = course.location,
                            campus = course.campus,
                            credit = course.credit ?: domain.credit,
                            colorIndex = course.colorIndex.coerceAtLeast(0),
                            customColorArgb = customColor,
                            isManualOverride = true,
                        ).toEntity()
                    )
                }
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
     * 删除课程同时清掉对应提醒，避免孤儿提醒继续触发。
     * 返回被清掉的提醒快照：撤销删除时按 [com.buaa.schedule.data.undo.UndoManager.UndoAction.Delete]
     * 原样挂回，口径与组删除的 [deleteCourseGroup] 一致。
     */
    suspend fun deleteCourse(course: Course): List<ReminderSetting> {
        return writeMutex.withLock {
            db.withTransaction {
                val snapshot = reminderDao.getByCourse(course.id)?.toDomain()
                    .let { listOfNotNull(it) }
                deleteCourseRow(course)
                snapshot
            }
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

    private suspend fun undoUpdate(action: UndoManager.UndoAction.Update) {
        // 部分周次拆行时，先删掉新建/改写的那一行，再恢复原课程。两道守卫：
        // - afterId 对应的行可能在此期间被导入流程替换成了**别的课**，
        //   按 id 盲删会删掉无关课程；必须校验 courseKey 仍是这次撤销的目标。
        // - before.id 同理，可能已被占用于其他课程，此时应改为新增而不是覆盖。
        if (action.afterId != null && action.afterId != action.before.id) {
            courseDao.getById(action.afterId)?.toDomain()
                ?.takeIf { ImportPlanner.courseKey(it) == ImportPlanner.courseKey(action.after) }
                ?.let { deleteCourseRow(it) }
        }
        val current = courseDao.getById(action.before.id)?.toDomain()
        if (current == null) {
            insertWithReminders(listOf(action.before), action.reminders)
        } else {
            CourseConstraints.normalize(action.before)?.let { courseDao.update(it.toEntity()) }
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
     */
    suspend fun renameSemesterCourses(oldCode: String, newCode: String): Boolean = writeMutex.withLock {
        db.withTransaction {
            if (courseDao.getBySemester(newCode).isNotEmpty()) return@withTransaction false
            courseDao.reassignSemester(oldCode, newCode)
            semesterDao.deleteByTermCode(oldCode)
            true
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
     */
    suspend fun replaceSemesterCoursesWithHistory(
        semester: Semester,
        importedCourses: List<Course>,
        history: ImportHistory,
    ) {
        writeMutex.withLock {
            db.withTransaction {
                replaceSemesterCoursesInTx(semester, importedCourses.mapNotNull(CourseConstraints::normalize))
                importHistoryDao.insert(history.toEntity())
            }
            UndoManager.clear()
        }
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
     * 学期行不存在时用兜底日期补建。
     */
    private suspend fun replaceCoursesForExistingSemesterInTx(
        semesterCode: String,
        imported: List<Course>,
        fallbackStartDate: LocalDate,
    ) {
        if (semesterDao.getByTermCode(semesterCode) == null) {
            semesterDao.insert(
                Semester(
                    termCode = semesterCode,
                    termName = semesterCode,
                    startDate = fallbackStartDate.toString(),
                    totalWeeks = 20,
                ).toEntity()
            )
        }
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
     *   学期行，顺序反过来会让恢复后的首页停在历史学期上（看起来"课表空了"）；
     * - 缺失的学期行会被补建（其 id 仍小于最后写入的主学期）；
     * - 手动课程按合并语义，只插入本地缺失的，不删除用户手动添加的课程；
     * - 提醒按 courseKey 对回恢复后的课程。
     *
     * @param allowEmptyReplacement 备份里一条课程都没有时的开关。默认拒绝：
     *   一份字段被改名的新备份会被 ignoreUnknownKeys 静默解成空列表，
     *   照单执行就是删光课表后提示"恢复成功：0 条课程"。
     */
    suspend fun restoreBackupData(
        backupSemester: Semester?,
        courses: List<Course>,
        timeSlots: List<TimeSlot>,
        remindersByCourseKey: Map<String, Pair<Boolean, Int>>,
        fallbackStartDate: LocalDate,
        allowEmptyReplacement: Boolean = false,
    ): RestoreResult = writeMutex.withLock {
        restoreBackupDataInTx(
            backupSemester = backupSemester,
            courses = courses,
            timeSlots = timeSlots,
            remindersByCourseKey = remindersByCourseKey,
            fallbackStartDate = fallbackStartDate,
            allowEmptyReplacement = allowEmptyReplacement,
        )
    }

    private suspend fun restoreBackupDataInTx(
        backupSemester: Semester?,
        courses: List<Course>,
        timeSlots: List<TimeSlot>,
        remindersByCourseKey: Map<String, Pair<Boolean, Int>>,
        fallbackStartDate: LocalDate,
        allowEmptyReplacement: Boolean,
    ): RestoreResult = db.withTransaction {
        val normalized = courses.mapNotNull(CourseConstraints::normalize)
        val skippedInvalid = courses.size - normalized.size
        if (normalized.isEmpty() && !allowEmptyReplacement) return@withTransaction RestoreResult.EmptyBackup

        // 备份可能来自手工编辑或更早版本：学期原点必须过 mondayOf、总周数必须限幅，
        // 否则 ScheduleOccurrences 全表按 dayOfWeek-1 偏日期、currentWeekOrNull 返回越界周次
        // （今日视图与提醒静默全空）。节次表同理——坏时间会让整个时间轴错位。
        val safeSemester = backupSemester?.let { s ->
            val monday = runCatching { WeekCalculator.mondayOf(LocalDate.parse(s.startDate)) }.getOrNull()
                ?: fallbackStartDate
            s.copy(
                startDate = monday.toString(),
                totalWeeks = CourseConstraints.normalizeTotalWeeks(s.totalWeeks),
            )
        }
        val safeSlots = timeSlots.filter { com.buaa.schedule.domain.schedule.isValidTimeSlot(it) }

        val mainCode = backupSemester?.termCode ?: normalized.firstNotNullOfOrNull { it.semesterCode }
        val manual = normalized.filter { it.semesterCode == null }
        val semesterGroups = normalized
            .mapNotNull { course -> course.semesterCode?.let { code -> code to course } }
            .groupBy({ it.first }, { it.second })

        // 历史学期在前、主学期在最后：见 KDoc 的幂等性说明
        semesterGroups.forEach { (code, group) ->
            if (code != mainCode) {
                replaceCoursesForExistingSemesterInTx(code, group, fallbackStartDate)
            }
        }
        if (safeSemester != null && mainCode != null) {
            // 主学期：恢复备份中的学期配置并整体替换课程
            replaceSemesterCoursesInTx(safeSemester, semesterGroups[mainCode].orEmpty())
        } else if (mainCode != null) {
            replaceCoursesForExistingSemesterInTx(mainCode, semesterGroups[mainCode].orEmpty(), fallbackStartDate)
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
        val courseByKey = restoredCourses
            .map { it.toDomain() }
            .associateBy { ImportPlanner.courseKey(it) }
        remindersByCourseKey.forEach { (key, setting) ->
            courseByKey[key]?.let { course ->
                reminderDao.upsert(
                    ReminderSetting(
                        courseId = course.id,
                        enabled = setting.first,
                        advanceMinutes = CourseConstraints.normalizeAdvanceMinutes(setting.second),
                    ).toEntity()
                )
            }
        }

        // 整表已被备份替换，内存里攒的撤销快照指向的都是替换前的行
        UndoManager.clear()

        RestoreResult.Applied(
            restoredCourses = normalized.size,
            skippedInvalid = skippedInvalid,
            insertedManual = manualToInsert.size,
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
