package com.buaa.schedule.ui

import android.app.Application
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.buaa.schedule.BUAAApplication
import com.buaa.schedule.data.backup.BACKUP_FORMAT_VERSION
import com.buaa.schedule.data.backup.BackupData
import com.buaa.schedule.data.backup.BackupReminder
import com.buaa.schedule.data.backup.toBackup
import com.buaa.schedule.data.backup.toDomain
import com.buaa.schedule.data.repository.PartialWeeksEdit
import com.buaa.schedule.data.repository.RestoreResult
import com.buaa.schedule.data.undo.UndoManager
import android.webkit.CookieManager
import com.buaa.schedule.data.import.IcsParser
import com.buaa.schedule.data.import.TextScheduleParser
import com.buaa.schedule.data.share.ScheduleShareCodec
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.CourseSaveOptions
import com.buaa.schedule.domain.model.ImportHistory
import com.buaa.schedule.domain.model.ReminderSetting
import com.buaa.schedule.domain.model.ReminderMode
import com.buaa.schedule.reminder.ReminderScheduler
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.TimeSlotProfile
import com.buaa.schedule.domain.model.startLocalDate
import com.buaa.schedule.domain.schedule.ConflictDetector
import com.buaa.schedule.domain.schedule.CourseFilter
import com.buaa.schedule.domain.schedule.ImportPlanner
import com.buaa.schedule.domain.schedule.WeekCalculator
import com.buaa.schedule.ui.home.nextDayTickDelayMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * @Immutable：[conflicts] 是 `List<ConflictDetector.Conflict>`，而 Conflict 内部
 * 含 `List<Course>`，编译器无法自动推断为稳定类型，导致主页每次下发新 state
 * 都会整页重组。这里显式声明不可变（三份列表都由 combine 块整体重建，不原地改）。
 */
@androidx.compose.runtime.Immutable
data class ScheduleUiState(
    val courses: List<Course> = emptyList(),
    val semester: Semester? = null,
    val timeSlots: List<TimeSlot> = emptyList(),
    val currentWeek: Int? = null,
    val conflicts: List<ConflictDetector.Conflict> = emptyList(),
    val loading: Boolean = true,
    /**
     * 真实"今天"，由 `ScheduleViewModel.dayTicker` 在跨午夜后推进。
     *
     * 必须进 state 而不能让页面各自 `LocalDate.now()`：一是组合期读时钟不是快照订阅，
     * 跨过零点没有任何东西因此重组；二是滴答值一旦不进字段，
     * combine 发射的新 state 与旧 state 相等，下游 `distinctUntilChanged()` 直接吞掉，
     * 顶栏「第 N 周」照样停在上一周。
     */
    val today: LocalDate = LocalDate.now(),
)

/**
 * 系统日历同步的界面状态（设置页只渲染它 + 消费 VM 的方法）。
 *
 * 权限申请框必须由页面发起（Activity result API），因此"是否显示对话框"这类
 * 标志也放这里，页面只负责把结果回灌给 VM。
 */
@androidx.compose.runtime.Immutable
data class CalendarSyncUiState(
    val syncing: Boolean = false,
    val calendars: List<com.buaa.schedule.data.calendar.CalendarSyncManager.CalendarInfo> = emptyList(),
    val calendarsLoaded: Boolean = false,
    val targetId: Long = -1L,
    val targetName: String? = null,
    val reminderMinutes: String = "10",
    val diff: com.buaa.schedule.data.calendar.CalendarSyncPlanner.Diff? = null,
    val skippedOccurrences: Int = 0,
    val message: AppMessage? = null,
    val showPicker: Boolean = false,
    val showRemoveConfirm: Boolean = false,
    val permissionPermanentlyDenied: Boolean = false,
)

/** 检索不到可写日历时的排查提示：状态行与选择器对话框共用 */
internal const val NO_WRITABLE_CALENDAR_MESSAGE =
    "没有检索到可写的日历。请确认已授予日历权限，且系统日历 App 里存在可见的日历账户（本机账户也算），然后重试"

/**
 * 一次性操作反馈（[ScheduleViewModel.importMessage] 与日历同步状态行共用）。
 *
 * 级别由**发出方**显式标注：界面此前靠嗅探文案里的「失败」「无法」来染色
 * （R7 ⑥），改一句文案配色就悄悄变了，而且「刷新完成，但教务系统没有返回课程」
 * 这类不含关键字的错误从来没红过。文案与样式解耦后，新增提示在发射点就把级别定死。
 *
 * [isSuccess] 单独占一档，不复用"非错误即成功"：同一个卡位还承载「正在抓取…」这类
 * 中性进度，把它们一并染成 success 就等于把 R7 §五.4 驳掉的"恒 ALERT"换个颜色重犯。
 * 所以成功只在事情真的做完的发射点上标注。
 */
data class AppMessage(
    val text: String,
    val isError: Boolean = false,
    val isSuccess: Boolean = false,
)

data class PendingImport(
    val semester: Semester,
    val courses: List<Course>,
    val existingCount: Int,
    val addedCount: Int,
    val changedCount: Int,
    val conflicts: List<ConflictDetector.Conflict>,
    /** 解析阶段的警告（缺教师/周次兜底等），在确认卡片中展示 */
    val warnings: List<String> = emptyList(),
    /** 逐条预览中被取消勾选的课程 key（[ImportPlanner.courseKey]） */
    val excludedKeys: Set<String> = emptySet(),
    /** 取消勾选且本地已存在、将被"原样保留"的课程数 */
    val keptCount: Int = 0,
    /** 导入来源（buaa/ics/text），随确认落库写进导入历史；此前历史页所有条目都硬编码成 buaa */
    val source: String = "buaa",
)

/**
 * 逐条勾选的结果：实际要写入库的课程列表与计数。
 *
 * 语义很重要：**取消勾选 ≠ 删除**。覆盖导入会先清空该学期再写入，
 * 因此取消勾选的已存在课程必须原样塞回去，否则用户只是想"这次别动它"，
 * 结果课表里这门课被删了。
 */
internal data class ImportSelection(
    val toWrite: List<Course>,
    val addedCount: Int,
    val changedCount: Int,
    val keptCount: Int,
)

internal fun resolveImportSelection(
    imported: List<Course>,
    excludedKeys: Set<String>,
    existing: List<Course>,
): ImportSelection {
    val existingMap = existing.associateBy { ImportPlanner.courseKey(it) }
    val selected = imported.filter { ImportPlanner.courseKey(it) !in excludedKeys }
    val excludedExisting = imported
        .filter { ImportPlanner.courseKey(it) in excludedKeys }
        .mapNotNull { existingMap[ImportPlanner.courseKey(it)] }
    val selectedMap = selected.associateBy { ImportPlanner.courseKey(it) }
    return ImportSelection(
        toWrite = selected + excludedExisting,
        addedCount = selected.count { ImportPlanner.courseKey(it) !in existingMap },
        changedCount = selected.count { course ->
            val old = existingMap[ImportPlanner.courseKey(course)]
            old != null && old.weeks != course.weeks
        },
        keptCount = excludedExisting.size,
    )
}

/** 没有任何学期可沿用时的周数兜底（北航一学期 19~20 周） */
private const val DEFAULT_TOTAL_WEEKS = 20

/**
 * 学期行兜底构造：教务给出的 termCode 本地还没有对应学期行时，沿用当前学期的
 * 起始日与周数，只换 termCode 与名称；连当前学期都没有，就以本周周一为起点。
 *
 * 它决定"第一次导入时第 1 教学周是哪天"，也就是所有周次计算的原点，
 * 所以从 ViewModel 里下沉成纯函数（R5 §8-9）。
 */
internal fun buildFallbackSemester(
    termCode: String,
    currentSemester: Semester?,
    today: LocalDate = LocalDate.now(),
): Semester {
    // 新学期代码不能沿用旧学期的开学日：ICS 的 dateToWeek 会整表错位，
    // 课次落到 1..maxWeeks 之外，用户只看到误导性的「解析结果为空」。
    // 导入动作发生在当下，今天的周一是更可信的兜底原点。
    val startDate = if (currentSemester?.termCode == termCode) {
        currentSemester.startLocalDate ?: mostRecentMonday(today)
    } else {
        mostRecentMonday(today)
    }
    return currentSemester?.takeIf { it.termCode == termCode } ?: Semester(
        termCode = termCode,
        termName = termCode,
        startDate = startDate.toString(),
        totalWeeks = currentSemester?.totalWeeks ?: DEFAULT_TOTAL_WEEKS,
    )
}

/** `today` 所在教学周的第一天（周一）。公式只在 [com.buaa.schedule.domain.model.mondayOfWeekAnchor] 有一份 */
internal fun mostRecentMonday(today: LocalDate = LocalDate.now()): LocalDate =
    com.buaa.schedule.domain.model.mondayOfWeekAnchor(today)

private val prettyJson = Json { prettyPrint = true }
private val lenientJson = Json { ignoreUnknownKeys = true }

/**
 * 挂起操作用版的 `runCatching`：[CancellationException] 原样抛出（P2-1）。
 *
 * 标准库那个版本连取消一起兜，于是 ViewModel 被清除（用户划掉最近任务）那一刻
 * 正在写库的协程会弹出一条「保存课程失败：Job was cancelled」，
 * 并且取消信号就此断掉——调用方拿到的是"失败"而不是"被取消"，
 * 会继续走它不该走的分支（保留草稿、再排一次闹钟）。
 * 取消是控制流，不是错误：这条区分是结构化并发的地基。
 */
private suspend inline fun <T> suspendCatching(crossinline block: suspend () -> T): Result<T> =
    runCatching {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        }
    }

class ScheduleViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as BUAAApplication).repository

    val importHistory = repository.importHistory.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    /** 全部已存储学期（多课表切换），按开学日期倒序 */
    val allSemesters = repository.allSemesters.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    /**
     * 切换"当前学期"（多课表）。
     * 切换后 uiState 经 currentSemester 流自动刷新，提醒与桌面组件由
     * [afterDataChangedInternal] 统一收尾。
     */
    fun switchSemester(termCode: String) {
        viewModelScope.launch {
            val ok = repository.switchSemester(termCode)
            showMessage(
                if (ok) "已切换到 $termCode"
                else "本地没有「$termCode」的数据，请先导入该学期的课表",
                isError = !ok,
            )
            if (ok) afterDataChangedInternal()
        }
    }

    // Eagerly：编辑器打开时提醒列表必须已就绪，避免用初始空值固化提醒设置
    val reminders = repository.reminders.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList(),
    )

    /** 假期/调休标注（只标注不跳过：不影响周次计算与提醒调度） */
    private val _specialDays = MutableStateFlow<List<com.buaa.schedule.domain.model.SpecialDay>>(emptyList())
    val specialDays: kotlinx.coroutines.flow.StateFlow<List<com.buaa.schedule.domain.model.SpecialDay>> =
        _specialDays.asStateFlow()

    init {
        // 先读缓存（无网也可标注），联网抓取由首页在有会话时触发
        viewModelScope.launch(Dispatchers.IO) {
            _specialDays.value = com.buaa.schedule.data.local.SpecialDayCache.loadAll(getApplication())
        }
    }

    /**
     * 抓取本月与下月的「学习日程」标注（需保留的教务会话）。
     * 失败静默：标注是增强能力，不影响主流程。
     */
    fun refreshSpecialDays() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val now = java.time.YearMonth.now()
            // 本月/下月：没有缓存、或缓存已超过 TTL 才联网抓取。标注按“月”粒度落盘，
            // 加 TTL 是因为教务的调休常在月初之后才公布——只按“文件存在”判定会永久跳过本月。
            val wanted = listOf(now, now.plusMonths(1))
            val stale = com.buaa.schedule.data.local.SpecialDayCache.staleMonths(app, wanted)
            val months = wanted.filter { it in stale }
            if (months.isEmpty()) return@launch
            val fetched = com.buaa.schedule.data.import.BuaaWebSession.fetchTeachingSchedule(months)
                ?: return@launch
            fetched.forEach { (month, raw) ->
                com.buaa.schedule.data.local.SpecialDayCache.put(app, month, raw)
            }
            _specialDays.value = com.buaa.schedule.data.local.SpecialDayCache.loadAll(app)
        }
    }

    /**
     * 系统日期滴答：只在跨午夜后发射一次。周次只随日期变化，
     * 此前 uiState 的三个源全是数据流——课表挂着不动、跨过午夜，
     * 顶栏「第 N 周」会停在上一周，与今日页现算的周次自相矛盾。
     */
    private val dayTicker: kotlinx.coroutines.flow.Flow<java.time.LocalDate> = flow {
        while (true) {
            // 一次读取同时给出"发射哪一天"和"下一次什么时候醒"。读两次（旧写法
            // LocalDate.now() 与 LocalDateTime.now() 各读各的）时，两次之间正好跨过零点，
            // 就会发射新的一天、却按上一天算出接近一整天的延时——第二天的顶栏周次
            // 与今日高亮要等到后天才翻面。
            val now = java.time.LocalDateTime.now()
            emit(now.toLocalDate())
            // 对齐到下一个零点再越过 5 秒，与日/周视图那圈分钟滴答同形（见 NowTick）：
            // 等的是边界不是轮次，进程被 Doze 冻结时这次延时会迟到，
            // 而醒来第一件事就是重新读时钟发射当天日期，所以迟到不影响显示对的东西。
            delay(nextDayTickDelayMillis(now.toLocalTime()))
        }
    }

    val uiState = combine(
        repository.courses,
        repository.currentSemester,
        repository.timeSlots,
        dayTicker,
    ) { courses, semester, timeSlots, today ->
        // 只展示手动课程 + 当前学期课程，避免多学期叠加；
        // 开学日期非法时按“无学期周次”降级，而不是崩溃
        val visibleCourses = CourseFilter.visibleIn(courses, semester)
        // 周次按滴答出来的 today 算，不再让 WeekCalculator 自己读时钟：
        // 否则"用哪一刻算周次"和"界面显示哪一天"是两次独立读取，
        // 正好跨过零点时顶栏周次与今日页会各说一半。
        val currentWeek = semester?.startLocalDate?.let { start ->
            WeekCalculator.currentWeekOrNull(start, semester.totalWeeks, today)
        }
        ScheduleUiState(
            courses = visibleCourses,
            semester = semester,
            timeSlots = timeSlots,
            currentWeek = currentWeek,
            conflicts = ConflictDetector.findConflicts(visibleCourses),
            loading = false,
            today = today,
        )
    }
        // 冲突检测是 O(n²)，且 combine 的三个源里 timeSlots / currentSemester 的
        // 一次写入常常导致整块重算。搬到 Default 线程后端 + 值不变不下发，
        // 可以避免"改一个节次时间 → 整页课程列表重组"。
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()
        .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ScheduleUiState(),
    )

    /**
     * 写操作返回**最终落库行 id**，null 表示失败；
     * 编辑器据此决定是否退出（失败保留草稿重试），并把提醒写到正确的行。
     */
    suspend fun saveCourse(course: Course): Long? = suspendCatching {
        val savedId = repository.saveCourse(course) ?: return@suspendCatching null
        // 只对“新增”记录撤销；编辑器里已存在的课程走 updateCourse
        if (course.id == 0L) {
            UndoManager.pushCreate(course.copy(id = savedId))
        }
        afterDataChangedInternal()
        savedId
    }.getOrElse { e ->
        _importMessage.value = AppMessage("保存课程失败：${e.message}", isError = true)
        null
    }

    suspend fun updateCourse(course: Course, options: CourseSaveOptions = CourseSaveOptions()): Long? =
        suspendCatching {
            val original = repository.getCourseById(course.id)
            val edit = if (options.partialWeeks && original != null && original.weeks != course.weeks) {
                repository.updateCoursePartialWeeks(original, course)
            } else {
                repository.updateCourse(course)?.let {
                    PartialWeeksEdit(it, emptyList(), emptyList())
                }
            }
            // 归一化失败（null）表示本次输入非法：不写库、也不上报成功，UI 保留草稿并提示
            if (edit == null) return@suspendCatching null
            if (original != null) {
                // removed/reminders 也必须进快照：部分周次拆行会顺手清掉同组的兄弟片段，
                // 只记 before/after 的话撤销之后它们永久消失（R5 F-35）
                UndoManager.pushUpdate(
                    before = original,
                    after = course,
                    afterId = edit.savedId,
                    removed = edit.removed,
                    reminders = edit.reminders,
                )
            }
            if (options.applyToGroup && course.sourceGroupKey != null) {
                repository.updateCourseGroupAppearance(course)
            }
            afterDataChangedInternal()
            edit.savedId
        }.getOrElse { e ->
            _importMessage.value = AppMessage("更新课程失败：${e.message}", isError = true)
            null
        }

    suspend fun deleteCourse(course: Course): Boolean = suspendCatching {
        // 先删库、成功才压栈：反过来会让"删除失败"留下一条悬空撤销记录，
        // 之后任意一次撤销都会按新 id 重插这门根本没删掉的课
        val removedReminders = repository.deleteCourse(course)
        UndoManager.pushDelete(course, removedReminders)
        afterDataChangedInternal()
    }.fold({ true }, { e ->
        _importMessage.value = AppMessage("删除课程失败：${e.message}", isError = true)
        false
    })

    /**
     * 删除同一门课的全部片段（课表管理页的「删除整门课」）。
     *
     * 与逐个 [deleteCourse] 的差别正是这个入口存在的理由：
     * 整组只推**一条**撤销记录，快照由删除事务原样返回（含每段的提醒设置），
     * 一次撤销即可完整恢复。
     */
    suspend fun deleteCourseGroup(courses: List<Course>): Boolean {
        if (courses.isEmpty()) return false
        return suspendCatching {
            val snapshot = repository.deleteCourseGroup(courses)
            if (snapshot.courses.isEmpty()) return@suspendCatching false
            UndoManager.pushDeleteGroup(snapshot.courses, snapshot.reminders)
            afterDataChangedInternal()
            true
        }.fold({ it }, { e ->
            _importMessage.value = AppMessage("删除课程失败：${e.message}", isError = true)
            false
        })
    }

    /**
     * 撤销最近一次课程操作（新增/编辑/删除）。
     * 栈在内存里（应用进程存活期间有效），不做持久化——撤销是即时操作。
     *
     * 落库由 [com.buaa.schedule.data.repository.ScheduleRepository.applyUndo] 在
     * 单个事务里完成：这里的逐步执行曾让"删除拆出来的行"和"重新插入原行"
     * 之间存在一个可被导入插队的窗口（R5 F-34）。
     */
    fun undo() {
        val last = UndoManager.pop() ?: run {
            _importMessage.value = AppMessage("没有可撤销的操作")
            return
        }
        viewModelScope.launch {
            suspendCatching {
                repository.applyUndo(last.action)
                afterDataChangedInternal()
                _importMessage.value = AppMessage("已撤销：${last.label}")
            }.onFailure { _importMessage.value = AppMessage("撤销失败：${it.message}", isError = true) }
        }
    }

    /** 兼容旧命名：仍用于课表管理页的“撤销删除”入口 */
    fun undoDeleteCourse() = undo()

    fun saveSemester(semester: Semester) {
        viewModelScope.launch {
            val old = repository.getCurrentSemester()
            if (old != null && old.termCode != semester.termCode) {
                // 改代码 = 给当前课表重命名：课程必须跟着改挂，否则 CourseFilter
                // 按新学期代码一过滤，用户看到的是一保存课表就"空了"
                if (!repository.renameSemesterCourses(old.termCode, semester.termCode)) {
                    _importMessage.value = AppMessage(
                        "学期代码 ${semester.termCode} 下已有另一份课表，请先切换学期再改代码",
                        isError = true,
                    )
                    return@launch
                }
            }
            repository.saveSemester(semester)
            afterDataChangedInternal()
            showMessage("学期设置已保存", isSuccess = true)
        }
    }

    fun saveTimeSlots(slots: List<TimeSlot>) {
        viewModelScope.launch {
            repository.saveTimeSlots(slots)
            // 节次时间变化会影响「上课时刻」与组件时间轴，必须和课程变更走同一套收尾
            afterDataChangedInternal()
            showMessage("节次时间已保存", isSuccess = true)
        }
    }

    fun saveDefaultTimeSlots() {
        viewModelScope.launch {
            repository.saveTimeSlots(TimeSlotProfile.DEFAULT)
            afterDataChangedInternal()
            showMessage("已恢复北航默认节次时间", isSuccess = true)
        }
    }

    fun importCourses(semester: Semester, courses: List<Course>) {
        viewModelScope.launch {
            withImportLock {
                repository.replaceSemesterCourses(semester, courses)
                afterDataChangedInternal()
            }
        }
    }

    /**
     * 导入流程级互斥：快速重复点击不会并发发起多次网络请求/整表替换。
     * 返回 null 表示已有导入在进行。
     *
     * ⚠️ 这把锁**不负责**数据层的写串行化 —— 那已经下沉到
     * [com.buaa.schedule.data.repository.ScheduleRepository] 的写锁（R5 F-34）：
     * 撤销、编辑器保存并不走这里，如果只靠本锁，一次导入能把刚撤销回来的行抹掉。
     *
     * 块体统一切到 [Dispatchers.Default]：`viewModelScope` 默认跑在 Main 上，
     * 而块内包含 JSON 反序列化（整份备份）、冲突检测（O(n²)）等纯 CPU 工作，
     * 留在主线程会直接掉帧/ANR。Room 与网络调用自身会再切线程，不受影响。
     */
    private suspend fun <T> withImportLock(block: suspend () -> T): T? {
        if (!importMutex.tryLock()) {
            _importMessage.value = AppMessage("已有导入正在进行，请稍候")
            return null
        }
        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { block() }
        } finally {
            importMutex.unlock()
        }
    }

    private val importMutex = kotlinx.coroutines.sync.Mutex()

    companion object {
        const val DEFAULT_BUAA_TERM = "2026-2027-1"
    }

    private val _buaaTermCode = MutableStateFlow(DEFAULT_BUAA_TERM)
    val buaaTermCode: StateFlow<String> = _buaaTermCode.asStateFlow()

    fun setBuaaTermCode(termCode: String) {
        _buaaTermCode.value = termCode.trim()
    }
    // 学年学期下拉选项（登录教务后可获得）
    private val _buaaTermOptions = kotlinx.coroutines.flow.MutableStateFlow<List<com.buaa.schedule.data.import.BuaaInPageFetcher.TermOption>>(emptyList())
    val buaaTermOptions: StateFlow<List<com.buaa.schedule.data.import.BuaaInPageFetcher.TermOption>> = _buaaTermOptions.asStateFlow()

    /** 防止返回首页时重复发起学期列表抓取 */
    private var buaaTermsFetching = false

    /** 拉取学年学期列表（需保留的教务登录会话），结果存入 [buaaTermOptions] */
    fun refreshBuaaTerms() {
        if (!com.buaa.schedule.data.import.BuaaWebSession.hasSession()) return
        // 进程内已拉取过就不再重复调用 WebView 页面内 fetch。
        // 每次回到首页都触发本方法，缓存可避免无谓的页面 JS 执行与网络请求。
        if (_buaaTermOptions.value.isNotEmpty() || buaaTermsFetching) return
        buaaTermsFetching = true
        viewModelScope.launch {
            try {
                val terms = com.buaa.schedule.data.import.BuaaWebSession.fetchTermList()
                if (terms.isNotEmpty()) {
                    _buaaTermOptions.value = terms
                    terms.firstOrNull { it.selected }?.let { _buaaTermCode.value = it.code }
                }
            } finally {
                buaaTermsFetching = false
            }
        }
    }


    private val _importMessage = MutableStateFlow<AppMessage?>(null)
    val importMessage: StateFlow<AppMessage?> = _importMessage.asStateFlow()

    private val _pendingImport = MutableStateFlow<PendingImport?>(null)
    val pendingImport: StateFlow<PendingImport?> = _pendingImport.asStateFlow()

    /**
     * 「教务会话失效，需要重新登录」——**一次性事件**，不是状态。
     *
     * 此前它是一个 `StateFlow<Boolean>`：抓取要跑几十秒，用户在途中切去别的页面时
     * 没有订阅者，标志会一直留在 true；等他下次进导入页，导入页一组合就看到
     * `loginRequired == true`，于是自动跳去登录页、并连带开始整学期抓取
     * （表现即"点了一下别的键，怎么又自己开始导入了"）。
     * `replay = 0` 的 SharedFlow 在没有订阅者时直接丢弃事件——没人听见的重登录
     * 请求早就过期了，让用户下一次自己点。
     */
    private val _buaaReloginRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val buaaReloginRequests: SharedFlow<Unit> = _buaaReloginRequests.asSharedFlow()

    fun clearImportMessage() {
        _importMessage.value = null
    }

    fun showMessage(message: String?, isError: Boolean = false, isSuccess: Boolean = false) {
        _importMessage.value = message?.let { AppMessage(it, isError, isSuccess) }
    }

    fun confirmPendingImport() {
        val pending = _pendingImport.value ?: return
        viewModelScope.launch {
            withImportLock {
                val existing = repository.getCoursesBySemester(pending.semester.termCode)
                val selection = resolveImportSelection(
                    imported = pending.courses,
                    excludedKeys = pending.excludedKeys,
                    existing = existing,
                )
                if (selection.toWrite.isEmpty()) {
                    _importMessage.value = AppMessage("没有可导入的课程")
                    return@withImportLock
                }
                // 课程替换与导入历史必须同事务落库，否则中途失败会出现
                // 「课表已换、历史无记录」的错位
                repository.replaceSemesterCoursesWithHistory(
                    semester = pending.semester,
                    importedCourses = selection.toWrite,
                    history = ImportHistory(
                        source = pending.source,
                        importedAt = System.currentTimeMillis(),
                        termCode = pending.semester.termCode,
                        courseCount = selection.toWrite.size,
                        message = "导入成功",
                    ),
                )
                // 导入可能复用/生成新 id，从数据库重读后再重排提醒
                afterDataChangedInternal()
                _pendingImport.value = null
                _importMessage.value = AppMessage(buildString {
                    append("导入完成：新增 ${selection.addedCount}、更新 ${selection.changedCount}")
                    if (selection.keptCount > 0) append("、保留 ${selection.keptCount}")
                    append("（含拆行片段）")
                }, isSuccess = true)
            }
        }
    }

    /**
     * 逐条预览里勾选/取消某门课。
     * 冲突与计数按"勾选后的子集"重算，保证卡片数字与实际落库一致。
     */
    fun togglePendingImportCourse(course: Course) {
        val pending = _pendingImport.value ?: return
        viewModelScope.launch {
            val key = ImportPlanner.courseKey(course)
            val excluded = if (key in pending.excludedKeys) {
                pending.excludedKeys - key
            } else {
                pending.excludedKeys + key
            }
            val existing = repository.getCoursesBySemester(pending.semester.termCode)
            val selection = resolveImportSelection(pending.courses, excluded, existing)
            _pendingImport.value = pending.copy(
                excludedKeys = excluded,
                addedCount = selection.addedCount,
                changedCount = selection.changedCount,
                keptCount = selection.keptCount,
                conflicts = ConflictDetector.findConflicts(selection.toWrite),
            )
        }
    }

    /** 逐条预览：全选 / 全不选 */
    fun setAllPendingImportSelected(selected: Boolean) {
        val pending = _pendingImport.value ?: return
        viewModelScope.launch {
            val excluded = if (selected) {
                emptySet()
            } else {
                pending.courses.mapTo(mutableSetOf()) { ImportPlanner.courseKey(it) }
            }
            val existing = repository.getCoursesBySemester(pending.semester.termCode)
            val selection = resolveImportSelection(pending.courses, excluded, existing)
            _pendingImport.value = pending.copy(
                excludedKeys = excluded,
                addedCount = selection.addedCount,
                changedCount = selection.changedCount,
                keptCount = selection.keptCount,
                conflicts = ConflictDetector.findConflicts(selection.toWrite),
            )
        }
    }

    /**
     * 重新计算下一次课程提醒闹钟（从数据库读最新状态）。
     *
     * [report] 只有「开启/更新课程提醒」那颗按钮会传：另外两个调用方分别是权限回调
     * （被拒绝时报"已开启"就是假话）和开关行（翻转本身就是反馈）。提示排在收尾之后，
     * 说的是"闹钟真的重排过了"，不是"我点了"。
     */
    fun rescheduleReminders(report: String? = null) {
        viewModelScope.launch {
            afterDataChangedInternal()
            if (report != null) showMessage(report, isSuccess = true)
        }
    }

    fun saveReminder(setting: ReminderSetting) {
        viewModelScope.launch {
            repository.saveReminder(setting)
            afterDataChangedInternal()
        }
    }

    fun deleteReminder(courseId: Long) {
        viewModelScope.launch {
            repository.deleteReminder(courseId)
            afterDataChangedInternal()
        }
    }

    /**
     * 课程/提醒/学期/节次等数据变化后的统一收尾：
     * 重排下一条提醒 + 刷新全部桌面组件（事件驱动，无轮询）。
     * 提醒模式为“系统日历”时不注册应用内闹钟，避免双重通知。
     */
    private suspend fun afterDataChangedInternal() {
        val app = getApplication<Application>()
        val mode = app
            .getSharedPreferences("schedule_settings", android.content.Context.MODE_PRIVATE)
            .getString(ReminderMode.PREF_KEY, ReminderMode.APP)
        if (mode == ReminderMode.CALENDAR) {
            // 切到"系统日历提醒"时，应用内闹钟与"上课铃/下课铃"必须一起撤掉：
            // 只撤前者会留下永不消失的常驻通知和永久勿扰状态。
            ReminderScheduler.cancelAll(app)
            com.buaa.schedule.reminder.ClassProgressScheduler.cancelAll(app)
        } else {
            // 必须兑现 rescheduleReminders 的 Boolean 契约（false=课堂铃没人排、当场补排），
            // 此前这里丢弃返回值：改一节课后上/下课铃被 cancelAll 清掉且不再续排，
            // 实况岛最长 12 小时不出现（只有兜底 Worker 兑现了契约）
            com.buaa.schedule.widget.BackgroundSync.rescheduleRemindersAndBells(app)
        }
        com.buaa.schedule.widget.BackgroundSync.refreshWidgets(app)
        // 明日预告的续排此前只挂在开机广播与设置开关上：假期结束回到有课周，
        // 预告链条不会自己回来。数据每次变化都重新判定一次（内部按开关决定排/撤）
        com.buaa.schedule.widget.BackgroundSync.scheduleTomorrowPreview(app)
    }

    /** 用户切换提醒模式后调用：立即按新模式重排/取消应用内闹钟 */
    fun onReminderModeChanged() {
        viewModelScope.launch {
            afterDataChangedInternal()
        }
    }

    fun cancelPendingImport() {
        _pendingImport.value = null
        _importMessage.value = AppMessage("已取消导入")
    }

    /** 清空导入历史（导入历史独立页调用） */
    fun clearImportHistory() {
        viewModelScope.launch { repository.clearImportHistory() }
    }

    /** 刷新中状态（主页菜单按钮显示进度） */
    private val _buaaRefreshing = kotlinx.coroutines.flow.MutableStateFlow(false)
    val buaaRefreshing: kotlinx.coroutines.flow.StateFlow<Boolean> = _buaaRefreshing.asStateFlow()

    /**
     * 静默刷新课表：复用保留的登录 WebView（BuaaWebSession）页面内重拉整学期。
     * 无保留会话时返回 false，调用方引导用户重新登录。
     */
    /** 正在进行的教务刷新任务；用户可在抓取中途取消（已抓到的数据不落库） */
    private var buaaRefreshJob: kotlinx.coroutines.Job? = null

    fun refreshFromBuaa(termCode: String = ""): Boolean {
        if (_buaaRefreshing.value) return true
        if (!com.buaa.schedule.data.import.BuaaWebSession.hasSession()) return false
        if (termCode.isNotBlank()) _buaaTermCode.value = termCode.trim()
        _buaaRefreshing.value = true
        buaaRefreshJob = viewModelScope.launch {
            // 必须与导入/恢复共用同一把锁：此前 refreshFromBuaa 绕开了 withImportLock，
            // 用户点了"刷新"立刻又去导入页触发一次导入时，两个流程会并发读写同一学期，
            // 后者覆盖前者的结果（表现为"刚刷新完课表又变回去了"）。
            //
            // 锁与「正在刷新」旗位一律在 finally 里放（P2-4）：抓取中途抛异常时，
            // 顺序写下来的收尾跑不到，于是旗位永久为真、刷新再也进不来，
            // 而 withImportLock 那把互斥锁没人归还——导入与恢复会一起卡在锁后面。
            try {
                withImportLock {
                    // 页面上下文 fetch（原生栈复刻不出凭证 → 401，必须走保留的 byxt WebView）
                    val result = com.buaa.schedule.data.import.BuaaWebSession.refreshSchedule(
                        existingStartDate = repository.getCurrentSemester()?.startLocalDate,
                        onProgress = { week, total -> _importMessage.value = AppMessage("正在刷新课表：第 $week/$total 周...") },
                    )
                    when (result) {
                        null -> {
                            // WebView 会话没了：把"去重新登录"的入口直接交给界面
                            // （ImportScreen 收集 buaaReloginRequests 后跳登录页）。
                            _buaaReloginRequests.tryEmit(Unit)
                            _importMessage.value = AppMessage("登录已失效，请重新从导入页登录教务系统", isError = true)
                        }
                        else -> result
                            .onSuccess { fetched ->
                                when {
                                    // 结果不完整（有周次抓取失败）：绝不能走覆盖导入，
                                    // 否则"先清空该学期再写入"会把没抓到的周次直接删掉。
                                    !fetched.isComplete -> {
                                        _importMessage.value = AppMessage(
                                            buildString {
                                                append("刷新未完成：")
                                                append(fetched.warnings.firstOrNull() ?: "部分教学周抓取失败")
                                                append("。已保留原课表未做改动，请稍后重试")
                                            },
                                            isError = true,
                                        )
                                    }
                                    fetched.courses.isEmpty() -> {
                                        _importMessage.value = AppMessage("刷新完成，但教务系统没有返回课程", isError = true)
                                    }
                                    else -> {
                                        repository.replaceSemesterCourses(fetched.semester, fetched.courses)
                                        afterDataChangedInternal()
                                        _importMessage.value = AppMessage(
                                            "课表已刷新：${fetched.courses.size} 条课程（${fetched.semester.termName}）",
                                            isSuccess = true,
                                        )
                                    }
                                }
                            }
                            .onFailure { e ->
                                // 不要在这里 clear() 会话：刷新失败多为网络抖动 / 教务端限流，
                                // 此时登录会话往往仍然有效；一旦清掉，用户被迫重新登录
                                // （且 CookieManager 里的 SSO TGT 会被一并删除）。
                                // 会话确实失效时，界面上的"退出教务登录"可手动清理。
                                _importMessage.value = AppMessage("刷新失败：${e.message ?: "未知错误"}，可稍后重试", isError = true)
                            }
                    }
                }
            } finally {
                _buaaRefreshing.value = false
            }
        }
        return true
    }

    /**
     * 取消正在进行的课表刷新。
     *
     * 抓取阶段逐周请求，取消后协程立即结束，`replaceSemesterCourses` 不会执行，
     * 因此不会出现"半份课表"。登录会话保留，可稍后重试。
     */
    fun cancelRefreshFromBuaa() {
        val job = buaaRefreshJob ?: return
        buaaRefreshJob = null
        if (job.isActive) {
            job.cancel()
            _importMessage.value = AppMessage("已取消刷新")
        }
        _buaaRefreshing.value = false
    }

    /**
     * 教务 WebView 登录链路拿到课表后进入**导入预览**，不直接落库。
     *
     * 所有导入共用这套"解析 → 预览 → 确认写入"管线：
     * 此前登录链路直接调 [importCourses] 立即覆盖写库，
     * 用户看不到新增/更新/冲突明细，也无法逐条勾选。
     *
     * 用 suspend 而不是 `viewModelScope.launch`：调用方（登录页）算完就要立刻导航到
     * 导入页，异步返回会让页面在预览还没就绪时先渲染一次——用户看到的正是"跳过去
     * 却是空的"。这里只读库不改数据，调用方被取消可以安全中止。
     */
    suspend fun previewBuaaCourses(
        semester: Semester,
        courses: List<Course>,
        warnings: List<String> = emptyList(),
    ) {
        withImportLock {
            _pendingImport.value = null
            if (courses.isEmpty()) {
                _importMessage.value = AppMessage("教务系统未返回该学期课程（可能未选课）", isError = true)
                return@withImportLock
            }
            val pending = showPendingImport(semester, courses, warnings)
            _importMessage.value = AppMessage(
                "解析完成：新增 ${pending.addedCount}，更新 ${pending.changedCount}，" +
                "冲突 ${pending.conflicts.size} 组，请确认导入。",
            )
        }
    }

    private suspend fun showPendingImport(
        semester: Semester,
        courses: List<Course>,
        warnings: List<String> = emptyList(),
        source: String = "buaa",
    ): PendingImport {
        val existing = repository.getCoursesBySemester(semester.termCode)
        val existingMap = existing.associateBy { ImportPlanner.courseKey(it) }
        val addedCount = courses.count { ImportPlanner.courseKey(it) !in existingMap }
        val changedCount = courses.count { course ->
            val old = existingMap[ImportPlanner.courseKey(course)]
            old != null && old.weeks != course.weeks
        }
        val conflicts = ConflictDetector.findConflicts(courses)
        val pending = PendingImport(
            semester = semester,
            courses = courses,
            existingCount = existing.size,
            addedCount = addedCount,
            changedCount = changedCount,
            conflicts = conflicts,
            warnings = warnings,
            source = source,
        )
        _pendingImport.value = pending
        return pending
    }

    /**
     * 解析 ICS 文本并进入导入预览确认。
     */
    fun importIcs(termCode: String, content: String) {
        viewModelScope.launch {
            withImportLock {
                _pendingImport.value = null
                _importMessage.value = AppMessage("正在解析 ICS 文件...")
                val currentSemester = repository.getCurrentSemester()
                val semester = buildFallbackSemester(termCode, currentSemester)
                val semesterStart = semester.startLocalDate ?: mostRecentMonday()
                val courses = IcsParser.parse(
                    content = content,
                    semesterStart = semesterStart,
                    termCode = termCode,
                    maxWeeks = semester.totalWeeks,
                    timeSlots = repository.getTimeSlots(),
                )
                if (courses.isEmpty()) {
                    _importMessage.value = AppMessage("ICS 解析结果为空，请检查文件格式", isError = true)
                    return@withImportLock
                }
                val pending = showPendingImport(semester, courses, source = "ics")
                _importMessage.value = AppMessage(
                    "ICS 解析完成：新增 ${pending.addedCount}，更新 ${pending.changedCount}，" +
                    "冲突 ${pending.conflicts.size} 组，请确认导入。",
                )
            }
        }
    }

    /**
     * 解析文本课表并进入导入预览确认。
     */
    fun importText(termCode: String, content: String) {
        viewModelScope.launch {
            withImportLock {
                _pendingImport.value = null
                _importMessage.value = AppMessage("正在解析文本课表...")
                val currentSemester = repository.getCurrentSemester()
                val semester = buildFallbackSemester(termCode, currentSemester)
                val courses = TextScheduleParser.parse(content, termCode)
                if (courses.isEmpty()) {
                    _importMessage.value = AppMessage(
                        "文本解析结果为空，请检查格式：课程名,教师,地点,星期,开始节-结束节,周次",
                        isError = true,
                    )
                    return@withImportLock
                }
                val pending = showPendingImport(semester, courses, source = "text")
                _importMessage.value = AppMessage(
                    "文本解析完成：新增 ${pending.addedCount}，更新 ${pending.changedCount}，" +
                    "冲突 ${pending.conflicts.size} 组，请确认导入。",
                )
            }
        }
    }

    /** 备份导出：整表序列化，放 Default 上跑，避免调用方用 Main 调度器时卡界面 */
    suspend fun exportBackup(): String = withContext(Dispatchers.Default) {
        val semester = repository.getCurrentSemester()
        val courses = repository.getAllCourses()
        val timeSlots = repository.getTimeSlots()
        val reminders = repository.getReminders()
        val courseById = courses.associateBy { it.id }
        val backup = BackupData(
            semester = semester?.toBackup(),
            timeSlots = timeSlots.map { it.toBackup() },
            courses = courses.map { it.toBackup() },
            reminders = reminders.mapNotNull { setting ->
                courseById[setting.courseId]?.let { course ->
                    BackupReminder(
                        courseKey = ImportPlanner.courseKey(course),
                        enabled = setting.enabled,
                        advanceMinutes = setting.advanceMinutes,
                    )
                }
            },
        )
        prettyJson.encodeToString(backup)
    }

    /**
     * 导出为 ICS 日历文本（一次性导出，用户自行选择日历应用导入）。
     * 返回 null 表示当前没有学期或课程可导出。
     *
     * 课次展开 + 折行是列表级的字符串构建（一学期上千个课次），
     * 放在 Dispatchers.Default 上算，避免调用方用 Main 调度器时卡住界面。
     */
    suspend fun exportIcs(): com.buaa.schedule.data.export.IcsExporter.ExportResult? =
        withContext(Dispatchers.Default) {
            val semester = repository.getCurrentSemester() ?: return@withContext null
            if (semester.startLocalDate == null) return@withContext null
            val courses = repository.getDisplayCourses(semester)
            if (courses.isEmpty()) return@withContext null
            com.buaa.schedule.data.export.IcsExporter.export(
                semester = semester,
                courses = courses,
                timeSlots = repository.getTimeSlots(),
            )
        }

    fun importBackup(json: String, allowEmptyReplacement: Boolean = false) {
        viewModelScope.launch {
            withImportLock {
                runCatching {
                    lenientJson.decodeFromString<BackupData>(json)
                }.onSuccess { data ->
                    restoreFromData(data, "备份恢复", json, allowEmptyReplacement)
                }.onFailure { e ->
                    _importMessage.value = AppMessage("备份恢复失败：${e.message}", isError = true)
                }
            }
        }
    }

    /**
     * 备份里一条课程都没有：继续会清空当前课表，必须由用户明确点头。
     * 口令导入没有预览环节，直接拒绝就够了（空口令本来也没有恢复价值）。
     */
    data class PendingEmptyRestore(val raw: String)

    private val _pendingEmptyRestore = MutableStateFlow<PendingEmptyRestore?>(null)
    val pendingEmptyRestore: StateFlow<PendingEmptyRestore?> = _pendingEmptyRestore.asStateFlow()

    fun confirmEmptyRestore() {
        val pending = _pendingEmptyRestore.value ?: return
        _pendingEmptyRestore.value = null
        importBackup(pending.raw, allowEmptyReplacement = true)
    }

    fun dismissEmptyRestore() {
        _pendingEmptyRestore.value = null
    }

    /** 备份恢复前的只读预览（不写库） */
    data class BackupPreview(
        val versionTooNew: Boolean,
        val semesterName: String?,
        val startDate: String?,
        val totalWeeks: Int?,
        val courseCount: Int,
        val manualCourseCount: Int,
        val timeSlotCount: Int,
        val reminderCount: Int,
    )

    /**
     * 解析备份内容生成预览。返回 null 表示文件不是有效的备份 JSON。
     *
     * suspend + `Dispatchers.Default`：调用方在 Compose 里选完文件就立刻调它，
     * 一份几万条的备份 JSON 反序列化要几百毫秒，留在主线程上就是可感知的卡顿。
     */
    suspend fun parseBackupPreview(json: String): BackupPreview? =
        withContext(Dispatchers.Default) {
            val data = runCatching { lenientJson.decodeFromString<BackupData>(json) }.getOrNull()
                ?: return@withContext null
            BackupPreview(
                versionTooNew = data.version > BACKUP_FORMAT_VERSION,
                semesterName = data.semester?.termName,
                startDate = data.semester?.startDate,
                totalWeeks = data.semester?.totalWeeks,
                courseCount = data.courses.size,
                manualCourseCount = data.courses.count { it.isManualOverride },
                timeSlotCount = data.timeSlots.size,
                reminderCount = data.reminders.size,
            )
        }

    /**
     * 生成课表分享口令（当前学期 + 手动课程 + 节次时间）。
     * 返回 null 表示当前没有可分享的课表。
     */
    suspend fun buildShareCode(): String? {
        val semester = repository.getCurrentSemester() ?: return null
        val courses = repository.getDisplayCourses(semester)
        if (courses.isEmpty()) return null
        val backup = BackupData(
            semester = semester.toBackup(),
            timeSlots = repository.getTimeSlots().map { it.toBackup() },
            courses = courses.map { it.toBackup() },
        )
        return ScheduleShareCodec.encode(backup)
    }

    /**
     * 通过分享口令导入课表。
     */
    fun importShareCode(code: String) {
        viewModelScope.launch {
            withImportLock {
                when (val data = ScheduleShareCodec.decode(code)) {
                    null -> _importMessage.value = AppMessage("口令无法识别，请检查是否完整复制", isError = true)
                    else -> restoreFromData(data, "口令导入", raw = null)
                }
            }
        }
    }

    /**
     * @param raw 原始备份内容；非 null 表示这是"导入备份文件"路径，
     *   空备份被拒时可以把它交给用户二次确认。口令没有预览界面，传 null 直接拒绝。
     */
    private suspend fun restoreFromData(
        data: BackupData,
        sourceLabel: String,
        raw: String?,
        allowEmptyReplacement: Boolean = false,
    ) {
        if (data.version > BACKUP_FORMAT_VERSION) {
            _importMessage.value = AppMessage(
                "${sourceLabel}失败：备份来自更新版本的应用（v${data.version}），请先升级后再恢复",
                isError = true,
            )
            return
        }
        // 备份/口令都是用户可构造的输入：条数超限直接拒绝，
        // 否则一次恢复就能把课表页拖到不可用（每门课都要参与冲突检测与渲染）。
        if (data.courses.size > com.buaa.schedule.domain.schedule.CourseConstraints.MAX_COURSE_COUNT) {
            _importMessage.value = AppMessage(
                "${sourceLabel}失败：课程数超过上限" +
                "（${com.buaa.schedule.domain.schedule.CourseConstraints.MAX_COURSE_COUNT} 条），" +
                "请确认文件是否正确",
                isError = true,
            )
            return
        }
        val result = repository.restoreBackupData(
            backupSemester = data.semester?.toDomain(),
            courses = data.courses.map { it.toDomain() },
            timeSlots = data.timeSlots.map { it.toDomain() },
            remindersByCourseKey = data.reminders.associate {
                it.courseKey to (it.enabled to it.advanceMinutes)
            },
            fallbackStartDate = mostRecentMonday(),
            allowEmptyReplacement = allowEmptyReplacement,
        )
        when (result) {
            is RestoreResult.EmptyBackup -> {
                if (raw == null) {
                    _importMessage.value = AppMessage("${sourceLabel}已取消：备份里没有课程，继续会清空当前课表")
                } else {
                    // 交回用户判断：这确实是他想要的"清空"，还是拿错了文件
                    _pendingEmptyRestore.value = PendingEmptyRestore(raw)
                    _importMessage.value = AppMessage("该备份不含任何课程，请确认后再继续")
                }
                return
            }
            is RestoreResult.Applied -> {
                // 恢复也是会改课表来源的操作：不记历史，用户翻「导入记录」时
                // 完全无法解释"课表怎么变了"
                suspendCatching {
                    repository.addImportHistory(
                        ImportHistory(
                            source = "backup",
                            importedAt = System.currentTimeMillis(),
                            termCode = data.semester?.termCode,
                            courseCount = result.restoredCourses,
                            message = sourceLabel,
                        ),
                    )
                }
                afterDataChangedInternal()
                _importMessage.value = AppMessage(buildString {
                    append("${sourceLabel}成功：${result.restoredCourses} 条课程")
                    if (result.insertedManual > 0) append("（新增手动课程 ${result.insertedManual} 条）")
                    if (result.skippedInvalid > 0) append("，跳过无效数据 ${result.skippedInvalid} 条")
                }, isSuccess = true)
            }
        }
    }


    // ---- 系统日历同步 / 备份文件读写 ----
    //
    // 整块状态机放在 viewModelScope 而不是设置页的 rememberCoroutineScope()：
    // 后者在旋转、切分类页、返回时会被取消，而 CalendarSyncManager.apply() 是分块
    // 提交日历事件的，掐在两块之间会留下"事件已写入、映射未回写"的脏状态
    // （正好是需要部分失败保护的那个格子）；页面级 `syncing` 也会永远停在 true。

    private val settingsPrefs by lazy {
        getApplication<Application>()
            .getSharedPreferences("schedule_settings", android.content.Context.MODE_PRIVATE)
    }

    private val calendarSyncManager by lazy {
        com.buaa.schedule.data.calendar.CalendarSyncManager(getApplication(), repository)
    }

    private val _calendarSync = MutableStateFlow(
        CalendarSyncUiState(
            targetId = settingsPrefs.getLong("calendar_sync_target_id", -1L),
            targetName = settingsPrefs.getString("calendar_sync_target_name", null),
            reminderMinutes = settingsPrefs.getInt("calendar_sync_reminder_minutes", 10).toString(),
        )
    )
    val calendarSync: StateFlow<CalendarSyncUiState> = _calendarSync.asStateFlow()

    fun hasCalendarPermission(): Boolean = calendarSyncManager.hasPermission()

    /** 同步入口：读日历列表 → 没有目标日历就打开选择器 → 否则算差异等用户确认 */
    fun startCalendarSync() {
        viewModelScope.launch {
            _calendarSync.update { it.copy(syncing = true, message = null, diff = null) }
            ensureCalendarsLoaded()
            val current = _calendarSync.value
            when {
                current.calendars.isEmpty() -> _calendarSync.update {
                    it.copy(syncing = false, message = AppMessage(NO_WRITABLE_CALENDAR_MESSAGE, isError = true))
                }
                current.targetId == -1L -> _calendarSync.update { it.copy(syncing = false, showPicker = true) }
                else -> {
                    val computed = calendarSyncManager.computeDiff(current.targetId)
                    _calendarSync.update {
                        if (computed == null) it.copy(
                            syncing = false,
                            message = AppMessage("暂无可同步的课表，请先导入课程并设置学期", isError = true),
                        ) else it.copy(
                            syncing = false,
                            diff = computed.first,
                            skippedOccurrences = computed.second,
                        )
                    }
                }
            }
        }
    }

    /** 应用用户已确认的差异 */
    fun confirmCalendarSync() {
        val pending = _calendarSync.value.diff ?: return
        val calendarId = _calendarSync.value.targetId
        val minutes = _calendarSync.value.reminderMinutes.toIntOrNull()?.coerceIn(0, 24 * 60) ?: 10
        settingsPrefs.edit { putInt("calendar_sync_reminder_minutes", minutes) }
        _calendarSync.update { it.copy(diff = null, reminderMinutes = minutes.toString()) }
        viewModelScope.launch {
            _calendarSync.update { it.copy(syncing = true) }
            val result = suspendCatching { calendarSyncManager.apply(calendarId, pending, minutes) }.getOrNull()
            _calendarSync.update {
                it.copy(
                    syncing = false,
                    message = when {
                        result == null -> AppMessage("同步失败：日历写入异常，请重试或检查日历权限", isError = true)
                        result.succeeded -> AppMessage(
                            "同步完成：新增 ${result.inserted}，更新 ${result.updated}，删除 ${result.deleted}",
                            isSuccess = true,
                        )
                        else -> AppMessage("同步失败：${result.failed} 个日程未写入，请重试或检查日历权限", isError = true)
                    },
                )
            }
        }
    }

    fun dismissCalendarSyncDiff() {
        _calendarSync.update { it.copy(diff = null, skippedOccurrences = 0) }
    }

    fun openCalendarPicker() {
        _calendarSync.update { it.copy(showPicker = true) }
        viewModelScope.launch { ensureCalendarsLoaded() }
    }

    fun dismissCalendarPicker() {
        _calendarSync.update { it.copy(showPicker = false) }
    }

    fun selectCalendarTarget(calendarId: Long, displayName: String) {
        settingsPrefs.edit {
            putLong("calendar_sync_target_id", calendarId)
            putString("calendar_sync_target_name", displayName)
        }
        _calendarSync.update { it.copy(targetId = calendarId, targetName = displayName, showPicker = false) }
        startCalendarSync()
    }

    fun setCalendarReminderMinutes(value: String) {
        _calendarSync.update { it.copy(reminderMinutes = value) }
    }

    fun requestRemoveSyncedEvents() {
        _calendarSync.update { it.copy(showRemoveConfirm = true) }
    }

    fun dismissRemoveSyncedEvents() {
        _calendarSync.update { it.copy(showRemoveConfirm = false) }
    }

    fun removeSyncedEvents() {
        _calendarSync.update { it.copy(showRemoveConfirm = false) }
        viewModelScope.launch {
            _calendarSync.update { it.copy(syncing = true) }
            val removed = suspendCatching { calendarSyncManager.removeAllSyncedEvents() }.getOrNull()
            _calendarSync.update {
                it.copy(
                    syncing = false,
                    message = removed?.let { n -> AppMessage("已移除 $n 个日程") }
                        ?: AppMessage("移除失败：日历写入异常，请检查权限后重试", isError = true),
                )
            }
        }
    }

    /** 授权被拒：区分「还能再弹一次」与「已勾不再询问」，后者只能去系统设置 */
    fun onCalendarPermissionDenied(canAskAgain: Boolean) {
        _calendarSync.update {
            it.copy(
                permissionPermanentlyDenied = !canAskAgain,
                message = AppMessage(
                    if (canAskAgain) "未授予日历权限，无法同步"
                    else "日历权限已被永久拒绝，请到系统设置手动开启",
                    isError = true,
                ),
            )
        }
    }

    fun onCalendarPermissionGranted() {
        _calendarSync.update { it.copy(permissionPermanentlyDenied = false) }
    }

    private suspend fun ensureCalendarsLoaded() {
        if (_calendarSync.value.calendarsLoaded) return
        val loaded = suspendCatching { calendarSyncManager.queryCalendars() }
            .getOrElse {
                _calendarSync.update { it.copy(
                    calendarsLoaded = true,
                    message = AppMessage("读取日历列表失败，请检查日历权限", isError = true),
                ) }
                return
            }
        val current = _calendarSync.value
        // 目标日历可能已被用户删除：留在状态里会让同步静默失败
        val targetGone = current.targetId != -1L && loaded.none { it.id == current.targetId }
        _calendarSync.update {
            it.copy(
                calendars = loaded,
                calendarsLoaded = true,
                targetId = if (targetGone) -1L else it.targetId,
                targetName = if (targetGone) null else it.targetName,
            )
        }
    }

    /** 待用户确认的备份恢复：内容随预览一起保存在 VM，旋转后对话框仍能恢复 */
    data class PendingBackup(val raw: String, val preview: BackupPreview)

    private val _pendingBackup = MutableStateFlow<PendingBackup?>(null)
    val pendingBackup: StateFlow<PendingBackup?> = _pendingBackup.asStateFlow()

    fun dismissPendingBackup() {
        _pendingBackup.value = null
    }

    fun restorePendingBackup() {
        val pending = _pendingBackup.value ?: return
        _pendingBackup.value = null
        importBackup(pending.raw)
    }

    private fun contentResolverForIO() = getApplication<Application>().contentResolver

    private fun readTextFromUri(uriString: String): String? = runCatching {
        contentResolverForIO().openInputStream(uriString.toUri())
            ?.use { it.readTextLimited(MAX_IMPORT_BYTES) }
    }.getOrNull()

    private fun writeTextToUri(uriString: String, text: String): Boolean = runCatching {
        contentResolverForIO().openOutputStream(uriString.toUri())
            ?.bufferedWriter()
            ?.use { it.write(text); true }
            ?: false
    }.getOrDefault(false)

    /** 导出备份文件：整表序列化 + 写 Uri，全程在 viewModelScope */
    fun exportBackupTo(uriString: String) {
        viewModelScope.launch {
            val backup = exportBackup()
            val written = withContext(Dispatchers.IO) { writeTextToUri(uriString, backup) }
            showMessage(
                if (written) "备份已导出" else "备份导出失败：无法写入所选位置",
                isError = !written,
            )
        }
    }

    fun exportIcsTo(uriString: String) {
        viewModelScope.launch {
            val result = exportIcs()
            if (result == null) {
                showMessage("暂无可导出的课表，请先导入课程并设置学期", isError = true)
                return@launch
            }
            val written = withContext(Dispatchers.IO) { writeTextToUri(uriString, result.text) }
            showMessage(
                if (written) {
                    buildString {
                        append("已导出 ${result.eventCount} 个日程，可在系统日历中导入")
                        if (result.skippedOccurrences > 0) {
                            append("（${result.skippedOccurrences} 个课次因节次时间缺失被跳过）")
                        }
                    }
                } else {
                    "导出失败：无法写入所选位置"
                },
                isError = !written,
            )
        }
    }

    /**
     * WakeUp 兼容 JSON 导出。
     *
     * 此前这段跑在设置页 ActivityResult 回调所在的**主线程**上，并且 `runCatching`
     * 把失败静默吞掉：用户选完保存位置就以为成功了，实际可能写出一个空文件。
     */
    fun exportWakeUpTo(uriString: String) {
        viewModelScope.launch {
            val semester = repository.getCurrentSemester()
            val courses = semester?.let { repository.getDisplayCourses(it) }.orEmpty()
            if (courses.isEmpty()) {
                showMessage("当前没有可导出的课程", isError = true)
                return@launch
            }
            val json = withContext(Dispatchers.Default) {
                com.buaa.schedule.domain.schedule.ScheduleExporters.toWakeUpJson(
                    courses,
                    semester,
                    repository.getTimeSlots(),
                )
            }
            val written = withContext(Dispatchers.IO) { writeTextToUri(uriString, json) }
            showMessage(
                if (written) "WakeUp JSON 已导出（${courses.size} 门课）"
                else "导出失败：无法写入所选位置",
                isError = !written,
            )
        }
    }

    /** 读备份文件 → 解析 → 交给预览确认对话框，不直接写库 */
    fun readBackupAndPreview(uriString: String) {
        viewModelScope.launch {
            val content = withContext(Dispatchers.IO) { readTextFromUri(uriString) }
            when {
                // null 有两种来源：流打开/读取失败，或超过 MAX_IMPORT_BYTES 被限读拒绝
                content == null -> showMessage(
                    "备份文件过大（上限 ${MAX_IMPORT_BYTES / (1024 * 1024)}MB）或无法读取，" +
                        "请确认选择的是本 App 导出的备份",
                    isError = true,
                )
                content.isBlank() -> showMessage("无法读取备份文件", isError = true)
                else -> {
                    val preview = parseBackupPreview(content)
                    if (preview == null) {
                        showMessage("备份恢复失败：文件不是有效的备份 JSON", isError = true)
                    } else {
                        _pendingBackup.value = PendingBackup(content, preview)
                    }
                }
            }
        }
    }

    /** 「立即刷新全部组件」：查库 + 逐个 notify，不能在组合作用域里跑 */
    fun refreshWidgetsNow() {
        viewModelScope.launch {
            runCatching { com.buaa.schedule.widget.BackgroundSync.refreshWidgets(getApplication()) }
                .onSuccess { showMessage("桌面组件已刷新") }
                .onFailure { showMessage("组件刷新失败：${it.message}", isError = true) }
        }
    }


    class Factory(
        private val application: Application,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ScheduleViewModel::class.java)) {
                return ScheduleViewModel(application) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}

