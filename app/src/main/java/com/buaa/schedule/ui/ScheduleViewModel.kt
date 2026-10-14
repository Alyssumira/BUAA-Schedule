package com.buaa.schedule.ui

import android.app.Application
import android.util.Log
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
import com.buaa.schedule.data.backup.BackupReminderPairing
import com.buaa.schedule.data.backup.toBackup
import com.buaa.schedule.data.backup.toDomain
import com.buaa.schedule.data.repository.PartialWeeksEdit
import com.buaa.schedule.data.repository.RestoreResult
import com.buaa.schedule.data.undo.UndoManager
import android.webkit.CookieManager
import com.buaa.schedule.data.import.IcsParser
import com.buaa.schedule.data.import.TextScheduleParser
import com.buaa.schedule.data.local.SpecialDayWriteOutcome
import com.buaa.schedule.data.local.SpecialDayWriteStage
import com.buaa.schedule.data.share.ScheduleShareCodec
import com.buaa.schedule.data.share.ShareCodeOutcome
import com.buaa.schedule.domain.calendar.CalendarReclaimPlanner
import com.buaa.schedule.domain.calendar.CalendarReclaimPlanner.RemovalCopy
import com.buaa.schedule.domain.editor.EditorSavePhase
import com.buaa.schedule.domain.editor.EditorSaveStage
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.CourseSaveOptions
import com.buaa.schedule.domain.model.ImportHistory
import com.buaa.schedule.domain.model.ReminderSetting
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.TimeSlotProfile
import com.buaa.schedule.domain.model.startLocalDate
import com.buaa.schedule.domain.schedule.ConflictDetector
import com.buaa.schedule.domain.schedule.CourseConflictResolution
import com.buaa.schedule.domain.schedule.CourseFilter
import com.buaa.schedule.domain.schedule.IcsExportDecision
import com.buaa.schedule.domain.schedule.IcsExportGate
import com.buaa.schedule.domain.schedule.ImportPlanner
import com.buaa.schedule.domain.schedule.RestoreCounts
import com.buaa.schedule.domain.schedule.RestoreEntry
import com.buaa.schedule.domain.schedule.RestoreVolumeDecision
import com.buaa.schedule.domain.schedule.RestoreVolumeGate
import com.buaa.schedule.domain.schedule.RestoreVolumeLimits
import com.buaa.schedule.domain.schedule.restoreShareConfirmCopy
import com.buaa.schedule.domain.schedule.restoreVolumeRejectCopy
import com.buaa.schedule.domain.schedule.shareCodeNewerFormatCopy
import com.buaa.schedule.domain.schedule.shareCodeNothingNamedCopy
import com.buaa.schedule.domain.schedule.shareCodeOverCapCopy
import com.buaa.schedule.domain.schedule.shareCodeTooManyCharsCopy
import com.buaa.schedule.domain.schedule.shareCodeUnreadableTablesCopy
import com.buaa.schedule.domain.schedule.WakeUpExportDecision
import com.buaa.schedule.domain.schedule.WakeUpExportGate
import com.buaa.schedule.domain.schedule.WeekCalculator
import com.buaa.schedule.ui.home.nextDayTickDelayMillis
import com.buaa.schedule.ui.importing.importCompletionMessage
import com.buaa.schedule.ui.importing.importParseSummary
import com.buaa.schedule.ui.settings.icsExportInconsistentMessage
import com.buaa.schedule.ui.settings.icsExportRefusedMessage
import com.buaa.schedule.ui.settings.icsExportWrittenMessage
import com.buaa.schedule.ui.settings.wakeUpExportInconsistentMessage
import com.buaa.schedule.ui.settings.wakeUpExportMessage
import com.buaa.schedule.ui.settings.wakeUpExportRefusedMessage
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
import kotlinx.coroutines.flow.onEach
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
    /**
     * 「这一份 [diff] 的附属说明」：与 `diff` 同为 `CalendarSyncManager.computeDiff` 一次返回的
     * 同一对（`Pair<Diff, Int>`），全仓唯一读点长在 `ModalTransition(payload = calendarSync.diff)`
     * 那块弹窗里 ⇒ 寿命由 `diff` 关掉。它**不是** `f(diff)`（`Diff` 里没有这个数，两枚各有各的源），
     * 收成类体派生属性那一刀在这里落不下去（T97 审计 §2.2 同一句结论），所以规矩只能钉在写侧：
     * **清空 `diff` 的每一处站点都必须同时把它清 0** —— 漏一处就是让上一份的说明活到下一份之前。
     */
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
) {
    /**
     * 归并后的冲突**组**数（T94① 引入这一枚数，T95① 把它收成派生属性）——
     * 与 [conflicts] 那两两配对的条数不是一枚数。
     *
     * 三门课挤在同一格：`findConflicts` 交回 3 条配对（A-B、A-C、B-C），
     * `groupConflicts` 归并成 1 组。确认卡那句"存在 N 组时间冲突"与三条解析完成提示
     * 念的都是这一枚，与首页横幅 / 统计页冲突卡同一把尺子（那两页吃的也是 `groupConflicts`）。
     *
     * 为什么它是**类体里的派生属性**而不是构造参数：`data class` 的 `copy()` 会把没点名的
     * 参数原样带走，而逐条勾选那两站（`togglePendingImportCourse` /
     * `setAllPendingImportSelected`）正是靠 `copy(conflicts = findConflicts(selection.toWrite))`
     * 换掉配对的。T94 把组数挂在参数表上，这两站点了 `conflicts` 却没点它 ⇒
     * 配对变小、组数冻结在上一屏（用户取消勾选一门撞车课，标题仍念旧的那几组）。
     * 挂在类体里它就**不在**参数表上、任何 `copy()` 都点不到它：每次重新构造（含每一次
     * `copy`）按当下的 [conflicts] 归并一次，之后界面读的是那枚存好的 `Int` ——
     * 归并仍然一次都不发生在组合期（这一族四处与那三条提示读的仍是同一个数）。
     * 代价：它不进 `equals`/`hashCode`/`toString`。派生值由 [conflicts] 唯一决定，
     * 参数相等它必相等，所以少了它判不出"两枚不同状态相等"。
     */
    val conflictGroupCount: Int = CourseConflictResolution.groupConflicts(conflicts).size
}

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

    /**
     * 提醒列表「发过第一行没有」那一枚就绪位（T381①，收在册 #660 的主体）。
     *
     * 为什么必须有它、又为什么只能由上游翻真：下面那枚 `reminders` 的 `initialValue` 是
     * `emptyList()`，而「库里一行提醒都没有」在这条链上是**合法常态**（教务导入链对 reminders 表只删不建，
     * 见 `ui/editor/CourseEditorScreen.kt` 的 `editorReminderSectionSummary` 段首）。于是空列表同时是两种事实的读数，
     * 拿长度当就绪就是把尺读反 —— 这一位只能由上游那一次发射翻真，起点必须是「未就绪」。
     *
     * 它的读者是编辑器挂载闸门（判据本体在 `domain/editor/EditorReminderMountGate.kt`），
     * 用途只有一个：编辑已有课而列表还没发过第一行时**不挂载**，免得首帧的空读数被
     * `rememberSaveable(requestedCourseId)` 固化成用户设置、再由保存那一趟无条件写回。
     * 这一位不参与任何写库判据，也不许被挪去当「这门课有没有提醒」的答案 —— 那是另一问。
     */
    private val _reminderRowsListed = MutableStateFlow(false)
    val reminderRowsListed: StateFlow<Boolean> = _reminderRowsListed.asStateFlow()

    /**
     * 课前提醒设置列表。
     *
     * ⚠️ 本文件四枚 `stateIn` 里**唯一一枚 Eagerly** 的就是这一枚：另外三枚
     * （`importHistory`、`allSemesters`、`uiState`）都是 `WhileSubscribed(5_000)`。
     * 这个 3∶1 是**有意为之**，不是漏改，也不许被顺手归一成 4∶0 ——
     * 守卫 `ui/RemindersEagerlySharingGuardTest` 按 3∶1 钉着，改档与"另一枚也 Eagerly"都会当场红。
     *
     * 为什么用它：编辑器目的地在挂载那一刻从这份列表里取 `initialReminder`，而编辑器那两格提醒字段
     * 是 `rememberSaveable(requestedCourseId)`（键里没有列表本身），首帧读到什么就固化成什么，
     * 之后列表到位也不回灌；保存那一趟对已存在的课**无条件**把这两格写成一行提醒。
     * Eagerly 让这条 Room 上游在 VM 构造那一刻（早于首帧之后的订阅效应）就起表，
     * 把"挂载时列表还没发过第一行"的概率压到尽可能小。
     *
     * 它买到什么、买不到什么（这句不许删，删了这段就又变成一行假话）：
     * Eagerly 只能让那趟读表**早到**，不能让它**同步** —— `initialValue = emptyList()` 在 Room
     * 第一次返回之前就是当前值。所以这一枚**不是就绪闸门**，它只把空值窗口从"整条流没人订阅"
     * 缩到"首帧之前"。真闸门在挂载那一手：上面那枚 `reminderRowsListed` 与
     * `domain/editor/EditorReminderMountGate.kt` 的三档判据，由 `MainActivity` 编辑器目的地的挂载闸门吃
     * —— T381① 已落地，守卫的第五格（`editorSideReadinessGateIsOnTheMountGate`）按 T373 那段 KDoc 写好的
     * 方向翻了面：从「钉今天还没有那道闸门」改成「钉那道闸门必须在场」，摘掉就绪位当场红。
     *
     * 代价（按现量口径，不是"感觉更重"）：
     * - 整表读：一次 `SELECT * FROM reminders`（`data/local/ReminderDao.kt` 的 `observeAll`），
     *   与 `WhileSubscribed` 相比只是时刻提前、枚数不变；
     * - 反复重放：0 档 —— 这是 Room 失效驱动的 Flow，只在 reminders 表被写之后重查，空转期间不读第二遍；
     * - collector：读表那一趟仍只一枚，从 VM 构造挂到 VM 清除。链上 T381① 新添的那一手是
     *   `onEach { _reminderRowsListed.value = true }`，它跑在**同一条**共享协程里 ⇒ 不新增读表、
     *   不新增订阅数（MutableStateFlow 对同值不再发射）。根组合那头的读者多了一枚，但它订阅的是
     *   那枚布尔而不是整表 ⇒ 净增量仍是≈0；`WhileSubscribed` 的 5 秒停挂在可达路径上凑不出来
     *   （同族判语见台账 #117）。
     *
     * 什么条件下可以改回 `WhileSubscribed(5_000)` 与其余三枚归一：
     * 1. ~~编辑器侧那道"未就绪不许写回"的闸门落地~~ —— T381① 已成立，**所以这一枚现在不再买到正确性**：
     *    正确性由那枚就绪位担保，Eagerly 现在买到的只剩「比首帧早起表」这一截空等时间；
     * 2. 或读者形状变了：根组合那枚订阅被挪进只在编辑器目的地存在的子组合 —— 那时它才是真多付。
     * 本卡按 1 的新前提重判之后**留着 Eagerly**（读者仍是不离场的根组合那一枚，换档省不下读表，
     * 而它换来的是编辑器少空一帧）；真要归一，回来把这一段与代价账一起重写并按那时的盘面重跑守卫，
     * 不许只把字面的 `Eagerly` 换掉就走。
     */
    val reminders = repository.reminders
        .onEach { _reminderRowsListed.value = true }
        .stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList(),
    )

    /** 假期/调休标注（只标注不跳过：不影响周次计算与提醒调度） */
    private val _specialDays = MutableStateFlow<List<com.buaa.schedule.domain.model.SpecialDay>>(emptyList())
    val specialDays: kotlinx.coroutines.flow.StateFlow<List<com.buaa.schedule.domain.model.SpecialDay>> =
        _specialDays.asStateFlow()

    /**
     * 补抓的单飞闸门（T60）。
     *
     * 必须存在的理由：触发点从一处变成三处（回首页 / 会话刚建立 / 翻到新月份），
     * 而每一趟都是一串按月走的页面内 JS 请求。
     *
     * ⚠️ 它也必须**关得掉**，而且是"由领到它的那个人关"：领取用 compareAndSet、
     * 归还在 finally 里、且只在 `claimed` 时归还。只开不关的闸门比只关不开更隐蔽 ——
     * 之后整个进程里这一功能一声不吭，读起来和"从来没坏过"一模一样。
     * 做成 StateFlow 而不是裸 Boolean：界面上任何时候都读得到"这一轮还在跑"，
     * 将来要给它加个转圈不用先改判据（口径同 SignInViewModel 的 inFlight，T59⑤）。
     */
    private val specialDayFetchInFlight = MutableStateFlow(false)

    /** 屏幕上此刻看得见哪几个月；初值空 = 只按默认窗口（本月/下月）判，见 [onSpecialDayVisibleMonths] */
    private val specialDayVisibleMonths = MutableStateFlow<Set<SpecialDayMonth>>(emptySet())

    init {
        // 先读缓存（无网也可标注），联网补抓另有三个触发点，见 [refreshSpecialDays]
        viewModelScope.launch(Dispatchers.IO) {
            _specialDays.value = com.buaa.schedule.data.local.SpecialDayCache.loadAll(getApplication())
        }
        // 会话刚可能出现的那一刻（T60）。这一路才是把标注真正补回来的那一路：
        // 冷启动时首页那一趟跑在 Cookie 恢复之前，`fetchTeachingSchedule` 的
        // `sessionWebView ?: return null` 当场把它判死，而那一趟是全进程唯一的一趟。
        viewModelScope.launch {
            com.buaa.schedule.data.import.BuaaWebSession.sessionRetained.collect {
                refreshSpecialDays(SpecialDayTrigger.SessionReady)
            }
        }
    }

    /**
     * 界面上此刻看得见哪几个月（[com.buaa.schedule.ui.home.HomeScreen] 在浏览周次/日期变化时推上来）。
     *
     * 为什么需要界面上报：判据原本只看"本月 + 下月"，用户翻到跨月的那一周、
     * 或者寒假那一周（在上一学期里）时，那个月从来没进过缓存，也就从来没被请求过 ——
     * 表头上的「休/班」就一直不出，而这一档在日志里连一行痕迹都没有。
     * 月份集合没变时不重发（StateFlow 的相等判定挡掉了翻同一周的重复组合）。
     */
    fun onSpecialDayVisibleMonths(months: List<java.time.YearMonth>) {
        val keys = months.map { SpecialDayMonth(it.year, it.monthValue) }.toSet()
        if (keys == specialDayVisibleMonths.value) return
        specialDayVisibleMonths.value = keys
        refreshSpecialDays(SpecialDayTrigger.BrowseMonth)
    }

    /**
     * 补抓「学习日程」标注：抓不抓、抓哪几个月由 [decideSpecialDayFetch] 判，这里只管领闸门、
     * 读设备侧事实、跑请求、落盘、留一行日志。
     *
     * 口径与旧写法的两处不同：
     * - **不再静默**。四种停法（闸门被占 / 缓存新鲜 / 无会话 / 接口空手回）各种一行 `Log.i`，
     *   并写明本轮真的看过哪几个月 —— 这一链过去的全部问题就是"停在某一档而没人知道"。
     * - **一个进程不止一次**。三个触发点：回首页、会话刚建立、翻到新月份。
     *
     * 标注始终是增强能力：任何一档都不影响主流程，也不改动周次计算与提醒调度。
     */
    internal fun refreshSpecialDays(trigger: SpecialDayTrigger = SpecialDayTrigger.PageResume) {
        // 闸门在进协程**之前**领：三个触发点可以挤在同一帧里各敲一次门，
        // 而每敲一次就是一串几十秒的页面内 JS 请求 —— 进了协程再判，两路都会在同一份
        // false 上判通过（旧写法的 buaaTermsFetching 就是这个形状，它至少有 `if` 在前头）。
        // compareAndSet 保证这一轮只有一路拿到闸门。
        val claimed = specialDayFetchInFlight.compareAndSet(expect = false, update = true)
        viewModelScope.launch {
            // ⚠️ scope 已取消时这段一体都不会跑（finally 也不跑），闸门会留在 true ——
            // 但那时 ViewModel 已经在清场，这颗闸门随它一起没了，不会有下一轮被挡住。
            try {
                runSpecialDayFetch(trigger, fetchInFlight = !claimed)
            } finally {
                // 只归还自己领到的那一份：没领到的人去清闸门，等于把正在跑的那趟的门踹开
                if (claimed) specialDayFetchInFlight.value = false
            }
        }
    }

    /** [refreshSpecialDays] 的一趟流程；拆出来是为了让闸门那对 try/finally 一眼看得全 */
    private suspend fun runSpecialDayFetch(trigger: SpecialDayTrigger, fetchInFlight: Boolean) {
        val app = getApplication<Application>()
        val nowMonth = java.time.YearMonth.now()
        val decision = decideSpecialDayFetch(
            now = SpecialDayMonth(nowMonth.year, nowMonth.monthValue),
            cachedAges = com.buaa.schedule.data.local.SpecialDayCache.cachedMonthAges(app)
                .map { (month, ageDays) ->
                    SpecialDayCacheAge(SpecialDayMonth(month.year, month.monthValue), ageDays)
                },
            visibleMonths = specialDayVisibleMonths.value.toList(),
            // 两份设备侧事实都当参数递给判据：内核自己去问墙钟、问 WebView 单例的话，
            // "缓存新鲜/过期/半新半旧、有没有会话"这几档就没法在 JVM 里逐支打表了
            sessionAvailable = com.buaa.schedule.data.import.BuaaWebSession.hasSession(),
            fetchInFlight = fetchInFlight,
        )
        when (decision) {
            is SpecialDayDecision.Skip -> Log.i(
                SPECIAL_DAY_LOG_TAG,
                specialDaySkipLog(decision.reason, decision.months, trigger),
            )

            is SpecialDayDecision.Fetch -> {
                val fetched = com.buaa.schedule.data.import.BuaaWebSession.fetchTeachingSchedule(
                    decision.months.map { java.time.YearMonth.of(it.year, it.month) },
                )
                if (fetched == null) {
                    // 会话在位、请求发出去了，一个月都没回来。这一档以前是纯静默，
                    // 而它恰恰是"登录了却还是没标注"最常见的一种成因。
                    Log.i(
                        SPECIAL_DAY_LOG_TAG,
                        specialDaySkipLog(SpecialDayFetchSkip.FetchReturnedNull, decision.months, trigger),
                    )
                    return
                }
                // 逐月落盘，且**逐月收结论**（T369，在册 #336）。改前那三行长这样（取证原文，守卫按它在注释侧恰 1 处钉）：
                //     fetched.forEach { (month, raw) ->
                //         ...SpecialDayCache.put(app, month, raw)
                //     }
                // 而 `put` 的返回类型是 Unit —— 接口把正文带回来就被下面那句念成「已更新」，
                // 落没落盘、为什么没落，调用点一条路都拿不到。
                val writes = fetched.map { (month, raw) ->
                    specialDayMonthWriteOf(
                        SpecialDayMonth(month.year, month.monthValue),
                        com.buaa.schedule.data.local.SpecialDayCache.put(app, month, raw),
                    )
                }
                val days = com.buaa.schedule.data.local.SpecialDayCache.loadAll(app)
                _specialDays.value = days
                Log.i(
                    SPECIAL_DAY_LOG_TAG,
                    specialDayFetchDoneLog(
                        requested = decision.months,
                        // 「回来的月份」单独算：逐月请求可以只成一半，说成"请求的那几月都好了"就是假话
                        returned = fetched.keys.map { SpecialDayMonth(it.year, it.monthValue) },
                        deferred = decision.deferredMonths,
                        // 落盘结论逐枚递进来。失败那一档的留痕就在这一行里加一栏，**不另起一行 Log.i**：
                        // 这一层的留痕枚数被在册守卫 SpecialDayRefreshWiringGuardTest 钉成恰 3 枚，
                        // 多写一行的代价是去改别人那张尺（措辞本来也只许出自内核那份）。
                        writes = writes,
                        totalAnnotations = days.size,
                        trigger = trigger,
                    ),
                )
            }
        }
    }

    /**
     * `SpecialDayCache.put` 的落盘结论 → 内核那一侧的字汇（T369）。
     *
     * 这是**折算缝**，不是第二道判据：与同文件里 `YearMonth ↔ SpecialDayMonth` 那几处同形 ——
     * 设备侧事实由调用点折成内核的枚举，"这一档该念哪一句"全在 `SpecialDayRefreshPolicy` 里定死。
     * ⚠️ 那两枚 `when` 都不许加 else：缓存层哪天多出一档失败，这里要编译不过，
     * 而不是把新的一档静默折成「已落盘」—— 那正是 #336 的形状换了个落点。
     */
    private fun specialDayMonthWriteOf(
        month: SpecialDayMonth,
        outcome: SpecialDayWriteOutcome,
    ): SpecialDayMonthWrite = when (outcome) {
        SpecialDayWriteOutcome.Written -> SpecialDayMonthWrite(month, SpecialDayWriteState.WRITTEN)
        is SpecialDayWriteOutcome.WriteFailed -> SpecialDayMonthWrite(
            month = month,
            state = when (outcome.stage) {
                SpecialDayWriteStage.TEMP_WRITE -> SpecialDayWriteState.TEMP_WRITE_FAILED
                SpecialDayWriteStage.PROMOTE_RENAME -> SpecialDayWriteState.PROMOTION_FAILED
            },
            cause = outcome.cause,
        )
        SpecialDayWriteOutcome.CacheDirUnreachable ->
            SpecialDayMonthWrite(month, SpecialDayWriteState.CACHE_DIR_UNREACHABLE)
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
            // 学期两枚事实递进冲突判定（T222/#306）：改前这一格只有 `findConflicts(visibleCourses)`，
            // 签名里压根没有学期 ⇒ 第 20 周（学期只有 19 周）照样报「存在时间冲突」，
            // 而同屏顶栏念「共 19 周」、统计页那句「整天没课：周三」是**裁过周次**的。
            // 递的是当前学期行本身：`semester == null` 时两枚都是 null ⇒ 内核那一档答"判不出 ⇒ 不裁"，
            // 与周课表 `showAllCoursesWithoutWeek` 的形状对得上（不许在这里兜一枚假周数，台账 #236 那族）。
            conflicts = ConflictDetector.findConflicts(visibleCourses, semester?.startLocalDate, semester?.totalWeeks),
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

    /**
     * [saveCourse] 的「等一个结论」版 —— 课程编辑器那一支**还挂在 `rememberCoroutineScope()`
     * 上**的保存链用（T124c · 编辑器两枚半搬的第一枚）。
     *
     * 形状与 [deleteCourseAndAwait] 逐字同款（那里逐条写了为什么既不整块搬、也不直接透传），
     * 这里只登记差异：编辑器那一块 body 里 `saving = true` / `saveError = …` / `onBack()` 三样都是
     * **组合本地**的回灌动作 —— `saving` 还兼管着 `BackHandler(enabled = isDraftDirty && !saving)`
     * 那颗返回闸，而 `onBack()` 对应的是 `navController.popBackStack()`：整块搬进 [viewModelScope]
     * 要么把返回闸拆断，要么多 pop 一格。所以**只搬写库那一手**：
     * 「落库 → 对新增压撤销条目 → `afterDataChangedInternal()`」那一趟放进 [viewModelScope] 的
     * 那一枚 job（页 scope 被 dispose 取消也照样跑完），`join()` 仍跑在调用者（页 scope）上，
     * 只等一个结论 —— 这枚结论是 `Long?`（真正落库的行 id）：编辑器据此把提醒写到正确的行，
     * MainActivity 据此决定要不要给那张卡打脉冲。
     *
     * ⚠️ 不许退成 `return saveCourse(course)`（透传 = 写库那一手又落回调用者的 scope，本卡白做），
     * 也不许把 `savedId` 升级成 VM 的共享可变属性（那会新造一枚没人清的状态）。它**必须**是函数内的
     * 局部捕获。先例：本卡 [deleteCourseAndAwait] 与本仓 `ConflictWizardDialog.applyConflictShift`。
     *
     * T260 在这一枚 job 体内多写了**一句**：`editorSaveSettled(...)` —— 它交回的是「结论落地与否」这
     * 一枚证据（目的地层据此决定退栈），**不是**第二枚返回通道：本函数端出去的行 id 仍只走
     * `job.join()` 之后的那个局部量。把 `savedId` 改从旗上读 = 两条路读同一件事且其中一条没人清场。
     */
    suspend fun saveCourseAndAwait(course: Course): Long? {
        var savedId: Long? = null
        val job = viewModelScope.launch {
            savedId = saveCourse(course)
            // T260：结论交回这一手**必须**长在 job 里面 —— 它是唯一一枚「页 scope 被取消也执行得到」的落点。
            // 写库那一手已经在同一枚 viewModelScope 里跑完整趟（T124c），结论却只交给 `join()` 的续体，
            // 而续体归页面管：「点保存到落库之间转一次屏」那一档里课进了库、收尾那一行没跑，
            // 页面退回「添加课程」，再点一次就是第二门同样的课。
            editorSaveSettled(landed = savedId != null)
        }
        job.join()
        return savedId
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
            val group = if (options.applyToGroup && course.sourceGroupKey != null) {
                repository.updateCourseGroupAppearance(course)
            } else null // 组那一支先跑完：它写在兄弟行上，压栈点要读它交回的结论（T128 = T122 的前置）
            if (original != null && updateUndoWorthRecording(original, course, edit.savedId, edit.removed.size, group)) {
                // removed/reminders 也必须进快照：部分周次拆行会顺手清掉同组的兄弟片段，
                // 只记 before/after 的话撤销之后它们永久消失（R5 F-35）
                // groupBeforeRows 是同一族的另一半（T137）：组那一支改的是**别的行**，
                // 条目装不下它们的话，「整组换色而主行本来就是那个色」那一格就撤不干净
                UndoManager.pushUpdate(
                    before = original,
                    after = course,
                    afterId = edit.savedId,
                    removed = edit.removed,
                    reminders = edit.reminders,
                    groupBeforeRows = group?.beforeRows.orEmpty(),
                )
            }
            afterDataChangedInternal()
            edit.savedId
        }.getOrElse { e ->
            _importMessage.value = AppMessage("更新课程失败：${e.message}", isError = true)
            null
        }

    /**
     * [updateCourse] 的「等一个结论」版 —— 与 [saveCourseAndAwait] 是**同一枚编辑器保存链**的两扇门
     * （调用方按 `edited.id == 0L` 分流：新增走上一枚，改既有课走这一枚；那道分流本身留在调用点没搬）。
     *
     * 形状、理由与两条「不许」都逐字同 [saveCourseAndAwait]，这里只登记这一枚独有的那一半：
     * [updateCourse] 体内除了主行那一趟，还带着**部分周次拆行**（`updateCoursePartialWeeks`）与
     * **整组换色**（`updateCourseGroupAppearance`）两支写库，撤销条目压的是它们交回的结论
     * （T128/T137 那两笔账）。页 scope 被 dispose 时取消点若落在「主行已提交」与「组那一支 /
     * 压撤销条目 / 重排课前铃」之间，库里就是一门改了一半的课 —— 把**整趟**放进同一枚
     * `viewModelScope` job 才是把这一族收干净：要么整趟跑完，要么整趟没开始。
     */
    suspend fun updateCourseAndAwait(course: Course, options: CourseSaveOptions): Long? {
        var savedId: Long? = null
        val job = viewModelScope.launch {
            savedId = updateCourse(course, options)
            // T260：与 [saveCourseAndAwait] 同一枚落点、同一个理由（结论由跑完那一趟的人交回）
            editorSaveSettled(landed = savedId != null)
        }
        job.join()
        return savedId
    }

    /**
     * 编辑器保存链那一趟写库的**结论旗**（T260 · 第 ⓪ 问裁的乙档）：唯一一枚跨配置变化存活、
     * 又按「哪一趟请求」配好对的状态。形状与判据本体都在 `domain/editor/EditorSaveFinishing.kt`。
     *
     * ## 为什么必须是 VM 的状态，而不是页面那一支的续体
     *
     * 写库那一半在 [viewModelScope] 的那一枚 job 里（T124c），页 scope 被 dispose 也照样跑完整趟；
     * 而「落成了 ⇒ 退栈」那一手挂在 `rememberCoroutineScope()` 的续体上。「点保存到落库之间转一次屏」
     * 那一档就是这两半的寿命差：库里多了一行、屏幕上还是发起那一刻的那一页（新建那一路身份是
     * `id = 0L`，草稿又是 `rememberSaveable` 的），用户读作「点了没反应」，再点一次插出第二门课。
     *
     * ## 为什么不是甲档（把收尾也搬进 viewModelScope）
     *
     * 那一枚协程攥的是**发起那一刻**的 `navController` / `savedStateHandle` / `mutableStateOf`，
     * 三者都属于已经被销毁的那棵 composition：换接收者改的是「谁来跑这一行」，跑的还是一枚死掉的
     * controller。本 VM 是全站唯一一枚跨配置变化活着的载体（Activity 级 ViewModelStore），
     * 所以结论只能落在这里，退栈那一手由**还活着的那一侧**读它之后执行。
     *
     * ## 为什么不是「VM 的属性当返回通道」
     *
     * 上面两枚 `...AndAwait` 的红线一条没动：`var savedId: Long? = null` 仍是方法体内的**局部捕获**，
     * 端出去的行 id 仍走 `job.join()` 之后的返回值。这一枚旗只是「结论交回了没有 + 页面那一支
     * 有没有跑到」的**证据**，不是第二枚返回通道：它不参与任何一处取值，消费点只有目的地层那一枚
     * 协程（点火即消费，见 `MainActivity` 编辑器目的地），陈旧的那一枚靠 [EditorSaveStage.requestId]
     * 配对挡掉，不会替下一 entry 退栈。
     */
    private val _editorSaveStage = MutableStateFlow<EditorSaveStage?>(null)
    val editorSaveStage: StateFlow<EditorSaveStage?> = _editorSaveStage.asStateFlow()

    /** 发号器：`EditorSaveSentinel.NO_REQUEST` 那枚 0L 永远不发，所以号与「没发起过」在类型上不可混 */
    private var editorSaveRequestSequence = 0L

    /** 领一枚本趟写库的号（只有主线程的保存链会调，故不需要原子性） */
    fun nextEditorSaveRequestId(): Long {
        editorSaveRequestSequence += 1
        return editorSaveRequestSequence
    }

    /**
     * 立旗：这一页起了一趟写库（[EditorSavePhase.Writing]）。**必须排在任何挂起点之前** ——
     * 与删除链那枚旗同一个理由：await 之后那一支多半跑不到，旗立不起来就等于没有旗。
     */
    fun editorSaveRequested(requestId: Long) {
        _editorSaveStage.value = EditorSaveStage(
            requestId = requestId,
            phase = EditorSavePhase.Writing,
            tailReported = false,
        )
    }

    /** 结论交回（落成 / 没落成）。只由那两枚 `...AndAwait` 体内的那一枚 job 调用。 */
    private fun editorSaveSettled(landed: Boolean) {
        val current = _editorSaveStage.value ?: return
        _editorSaveStage.value = current.copy(
            phase = if (landed) EditorSavePhase.Landed else EditorSavePhase.Failed,
        )
    }

    /**
     * 页面那一支（跑在组合 scope 上）报告「我活着跑到了收尾」。这一手就是
     * [EditorSaveStage.tailReported] 那枚盘上证据的唯一出处：它执行得到 ⇒ 退栈归页面；
     * 它被组合取消 ⇒ 这一格永远留在 false ⇒ 退栈归目的地层。两枚认领点因此结构上互斥，
     * 不需要按时序猜「页面那一支这会儿还在不在」。
     */
    fun editorSaveTailReached(requestId: Long) {
        val current = _editorSaveStage.value ?: return
        if (current.requestId != requestId) return
        _editorSaveStage.value = current.copy(tailReported = true)
    }

    /** 目的地层把自己已经替它弹过的那一枚结论消费掉（按号清，别处的结论一枚都不碰） */
    fun editorSaveConsumed(requestId: Long) {
        val current = _editorSaveStage.value ?: return
        if (current.requestId == requestId) _editorSaveStage.value = null
    }

    suspend fun deleteCourse(course: Course): Boolean = suspendCatching {
        val deletion = repository.deleteCourse(course)
        val removed = deletion.removedCourse ?: return@suspendCatching false // 删不到 ⇒ 不报成功、不压栈
        UndoManager.pushDelete(removed, deletion.removedReminders)           // 先删库、成功才压栈
        afterDataChangedInternal()
        true
    }.fold({ it }, { e ->
        _importMessage.value = AppMessage("删除课程失败：${e.message}", isError = true)
        false
    })

    /**
     * [deleteCourse] 的「等一个结论」版 —— 给页面上那一支**还挂在 `rememberCoroutineScope()`
     * 上**的删除链用（T124b · B 档）。
     *
     * ## 为什么不把整块搬走，也不直接透传
     *
     * [deleteCourse] 体内是三拍：删库 → 压撤销条目（`UndoManager.pushDelete`）→
     * `afterDataChangedInternal()`（重排课前铃 / 桌面组件 / 明日预告）。调用它的那一支紧接着还要
     * `snackbarHostState.showSnackbar(...)` —— 那是只在组合期成立的东西，整块搬进
     * [viewModelScope] 会把提示条的回灌链拆断。反过来**整支留在页 scope 上**就是这一族的病：
     * 目的地被 dispose（切 tab 走 `popUpTo { saveState = true }`）时页 scope 被取消，取消点若落在
     * 「删已成」与「压撤销条目 / 重排那一趟」之间 ⇒ 库里删了、撤销栈没有、那一趟没跑，而界面一句没说。
     *
     * ## 这一枚把哪一半搬走
     *
     * 写库那一手放进 [viewModelScope] 里的那一枚 job（它不归页面管，页 scope 取消也照样跑完），
     * `join()` 仍跑在**调用者的 scope** 上 —— 它只是让 UI 等一个结论：
     *  - 页面还在 ⇒ 拿到 `deleted`，提示条照常；
     *  - 页面已走 ⇒ `join()` 随调用者一起被取消，而那一枚 job 继续跑到底。
     *
     * ⚠️ 两个方向都不许改：把 `join()` 换成 `withContext` / 把本函数写成直接 `suspend` 透传，
     * 等于写库那一手又落回调用者的 scope（本卡白做）；把 `deleted` 升级成 VM 的共享可变属性，
     * 等于新造一枚没人清的状态。它**必须**是函数内的局部捕获。
     *
     * 先例是本仓 `ConflictWizardDialog.applyConflictShift`（同一形状：`val job = … .launch {` + `job.join()`）。
     */
    suspend fun deleteCourseAndAwait(course: Course): Boolean {
        var deleted = false
        val job = viewModelScope.launch { deleted = deleteCourse(course) }
        job.join()
        return deleted
    }

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
     * [deleteCourseGroup] 的「等一个结论」版 —— 课表管理页那一支**还挂在
     * `rememberCoroutineScope()` 上**的删组链用（T124b · B 档第二枚）。
     *
     * 形状与理由与 [deleteCourseAndAwait] 同一份（那里逐条写了），这里只登记差异：
     * 组删除那一支紧接着的 `showSnackbar` 与那颗「撤销」是 composition-only 的调用，
     * 所以**只搬写库那一手** —— [deleteCourseGroup] 体内「删库 → 压一条组撤销快照 →
     * `afterDataChangedInternal()`」那一整趟放进 [viewModelScope] 的那一枚 job，
     * 页 scope 被 dispose 取消时它照样跑完；`join()` 留在调用者的 scope 上，只等一个结论。
     *
     * ⚠️ 不许退成 `return deleteCourseGroup(courses)`（透传 = 写库又落回页 scope，本卡白做），
     * 也不许把 `deleted` 做成 VM 的属性（新造一枚没人清的状态）。
     */
    suspend fun deleteCourseGroupAndAwait(courses: List<Course>): Boolean {
        var deleted = false
        val job = viewModelScope.launch { deleted = deleteCourseGroup(courses) }
        job.join()
        return deleted
    }

    /**
     * 撤销最近一次课程操作（新增/编辑/删除）。
     * 栈在内存里（应用进程存活期间有效），不做持久化——撤销是即时操作。
     *
     * 落库由 [com.buaa.schedule.data.repository.ScheduleRepository.applyUndo] 在
     * 单个事务里完成：这里的逐步执行曾让"删除拆出来的行"和"重新插入原行"
     * 之间存在一个可被导入插队的窗口（R5 F-34）。
     *
     * ## 落库失败时条目还得回来（T183）
     *
     * [UndoManager.pop] 是**消费**，而它跑在落库**之前** —— 改前那一版失败只念一句「撤销失败」，
     * 快照却已经从栈里摘走（那枚栈是它在进程内的唯一副本），这条撤销永远点不回来了。
     * 现在的形状：**条目仍先 pop**（消费点不动），但失败那一支经 [UndoManager.restoreTop] 放回栈顶，
     * 放回哪一档由 `undoFailureKindOf` 判（认不出原因 / 锁 / 环境写不下 ⇒ 留；快照本身写不进去 ⇒ 不留，
     * 一枚永远失败的条目压在顶上会把下面所有条目一起钉死），文案由**回推的返回值**决定，
     * 所以那句「还留着」不可能念在条目其实没回来的那一档上。
     *
     * 为什么不是"先 peek、落成才 pop"那一版：那样条目在这趟写库期间**还躺在栈顶**，
     * 双点那颗「撤销」会 peek 到同一枚、把 `applyUndo` 跑两遍 —— Delete 的撤销是 insert，
     * 于是同一门课插回库里两遍（数据坏，比顺序偏一格严重）；而且挂起之后才 pop 就得按身份摘那一条
     * （期间任何 push 都会把 `removeLastOrNull` 变成"吃掉一条没落过库的新条目"），那是 T123
     * 的账，本卡按红线不提前收。这一版只剩一条窄残余：回推期间若有别的 push，票据作废 ⇒
     * 那一档退回改前的行为（丢条目），不会更坏。
     *
     * 取消不在这支里：[suspendCatching] 把 `CancellationException` 原样抛出（P2-1），
     * VM 被清时整栈随进程语义作废，那时回推一条撤销没有意义。
     */
    fun undo() {
        val last = UndoManager.pop() ?: run {
            _importMessage.value = AppMessage("没有可撤销的操作")
            return
        }
        viewModelScope.launch {
            // 这一枚局部旗标只问一件事：applyUndo 那一步落成了没有。它闸住回推 ——
            // 库已经改成那一版之后（刷 UI / 文案那两拍）再抛，把条目放回栈顶就等于
            // 允许同一条撤销点两回，而撤销条目不幂等（Delete 的撤销是 insert）。
            var writtenToDb = false
            suspendCatching {
                repository.applyUndo(last.action)
                writtenToDb = true
                afterDataChangedInternal()
                _importMessage.value = AppMessage("已撤销：${last.label}")
            }.onFailure {
                val kind = undoFailureKindOf(exceptionClassNamesOf(it))
                val held = !writtenToDb && kind.restoreEntry && UndoManager.restoreTop(last)
                _importMessage.value = if (held) {
                    AppMessage(kind.copyForRestored(it.message), isError = true)
                } else {
                    // 两种"没回推"共用改前那一句（不宣称条目留着，所以不算谎）：
                    // 快照本身写不进去 / 票据作废的那一档，以及库已经改成那一版、
                    // 而刷 UI 或文案那一拍抛了 —— 后者那句「撤销失败」其实撤销已经落库，
                    // 那是改前就有的账，本卡按红线只把文案与前缀对齐、不改这一族的分档。
                    AppMessage("撤销失败：${it.message}", isError = true)
                }
            }
        }
    }

    /** 兼容旧命名：仍用于课表管理页的“撤销删除”入口 */
    fun undoDeleteCourse() = undo()

    /**
     * 设置页「保存学期」。
     *
     * **T249/#461：这一跳只调仓储一次。** 改前这里是两枚独立事务
     * （`renameSemesterCourses` 一枚、`saveSemester` 另一枚），中间进程被杀 ⇒ 旧学期行已删、
     * 新学期行未插 ⇒ `semesters` 落回「没设学期」，开学日与总周数永久丢，而屏上一句话都没有。
     * 现在两半合进 `ScheduleRepository.saveSemesterWithRename` 的同一枚 `db.withTransaction`，
     * 「要不要改代码」也在那枚事务内判（库里那一枚行由仓储自己读，VM 不再隔着一枚事务猜它）。
     *
     * 拒绝档（新代码下已有别的课表）的文案与 `isError` 逐字未改，且仍然在**任何写库之前**返回：
     * 合并事务被拒时一个字节都没写，所以这里也不补任何收尾刷新。
     */
    fun saveSemester(semester: Semester) {
        viewModelScope.launch {
            if (!repository.saveSemesterWithRename(semester)) {
                _importMessage.value = AppMessage(
                    "学期代码 ${semester.termCode} 下已有另一份课表，请先切换学期再改代码",
                    isError = true,
                )
                return@launch
            }
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

        /**
         * 假期/调休标注这条链的取证 tag（T60）。四种停法 + 一趟成功都走它，
         * 于是 `logcat -s SpecialDays` 能单独回答"这一轮到底走到哪一档停了"。
         * 级别一律 Info：用户那台机器（HyperOS）把 logcat 砍到 Info 以下、
         * release 又剥 Verbose（口径同扫码页那族取证行，见 ScanSilentBranchGuardTest ⑦）。
         */
        private const val SPECIAL_DAY_LOG_TAG = "SpecialDays"

        /**
         * 备份/口令恢复那一路的取证 tag（T204）。级别 Info（口径同上面那一枚：
         * 用户那台机器把 logcat 砍到 Info 以下、release 剥 Verbose）。
         * 恢复成功的 toast 是会自己消失的，而「补建了几枚学期行、它们的开学日是推算的」
         * 这笔账要能在事后被读出来 —— 否则用户报"历史学期的日期不对"时没有任何取证面可查。
         */
        private const val RESTORE_LOG_TAG = "BackupRestore"

        /**
         * 条数判据那一族的取证 tag（T270③）。与上面那一枚分开：`BackupRestore` 那一族
         * 已经被 `BackupSemesterRestoreWiringGuardTest` 数死一枚（T204 的补建账），
         * 本卡这三处（被拒 / 待确认 / 事务内复核）是**另一判**的账，
         * `logcat -s RestoreVolume` 才单独答得出"哪一条口令被哪张表的上限拦住"。
         * 级别一律 **Info**，不用 Warn：同页的 `SpecialDayRefreshWiringGuardTest` 第 ③ 层
         * 把整枚 VM 文件里的 `Log.d` / `Log.v` / `Log.e` / `Log.w` 各数成 0 处
         * （"取证行级别漂过就红"是那条卡的成文口径）。被拒那一手在屏上有一句 toast、
         * 在 logcat 上有同一句 Info —— 读得到读不到从来不是 Warn 与 Info 的差别。
         */
        private const val RESTORE_VOLUME_LOG_TAG = "RestoreVolume"

        /**
         * 口令这一路**体积闸门 / 信封判据 / 被咽掉的解码异常**三判的取证 tag（T283①，#530 发现 4）。
         *
         * 与上面两枚分开记，理由与 `RESTORE_VOLUME_LOG_TAG` 自己的 KDoc 同一条：一枚 tag 答一判的账，
         * `logcat -s ShareCodeGuard` 才单独答得出"这条口令是被哪把尺、哪一站拦下的"。
         * ⚠️ 也只有新 tag 才留得下这几行：`RESTORE_VOLUME_LOG_TAG` 的引用枚数（VM 内恰好 5 处）与
         * Info 级枚数（恰好 4 处）被 `ShareRestoreVolumeGuardTest` 第 ⑤ 层逐枚钉着，
         * 往那一枚上多挂一行就是改别人的尺。
         * 级别一律 **Info**（同页口径：已知设备把 logcat 截在 Info 以下，`Log.d` 等于没写）。
         * 枚数在册：本文件 1 处声明 + 5 处 `Log.i` 引用，由 `ShareCodeEntryGateWiringGuardTest` 第 ④ 层数死。
         */
        private const val SHARE_CODE_LOG_TAG = "ShareCodeGuard"
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
                // 「课表已换、历史无记录」的错位。
                // 返回值是这本账（T181①）：normalize 会判死若干条，此前那些行只在事务里蒸发，
                // 而下面那句「导入完成：新增 N」念的是预览条数 ⇒ 屏幕上的数比课表里的多。
                val written = repository.replaceSemesterCoursesWithHistory(
                    semester = pending.semester,
                    importedCourses = selection.toWrite,
                    history = ImportHistory(
                        source = pending.source,
                        importedAt = System.currentTimeMillis(),
                        termCode = pending.semester.termCode,
                        // 这里的 toWrite.size 只是占位：仓储在同一趟事务里把它覆盖成
                        // **真进了库的条数**（导入记录页那一句"· N 条课程"念的就是它）
                        courseCount = selection.toWrite.size,
                        message = "导入成功",
                    ),
                )
                // 导入可能复用/生成新 id，从数据库重读后再重排提醒
                afterDataChangedInternal()
                _pendingImport.value = null
                _importMessage.value = AppMessage(
                    importCompletionMessage(
                        addedCount = selection.addedCount,
                        changedCount = selection.changedCount,
                        keptCount = selection.keptCount,
                        droppedCount = written.droppedCount,
                    ),
                    isSuccess = true,
                )
            }
        }
    }

    /**
     * 逐条预览里勾选/取消某门课。
     * 冲突与计数按"勾选后的子集"重算，保证卡片数字与实际落库一致。
     *
     * 「计数」含归并后的冲突**组**数：copy() 只递新 `conflicts`，那枚组数是它的派生属性
     * （见 [PendingImport.conflictGroupCount]），因此取消勾选一门撞车课时标题与明细一起变小。
     * T94 把组数当构造参数递，这两处 copy 没点它 ⇒ 明细变了标题不动，T95① 修的就是这笔。
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
                conflicts = ConflictDetector.findConflicts(selection.toWrite, pending.semester.startLocalDate, pending.semester.totalWeeks),
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
                conflicts = ConflictDetector.findConflicts(selection.toWrite, pending.semester.startLocalDate, pending.semester.totalWeeks),
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
     *
     * 「系统日历提醒」不再单开一条**只撤不排**的特判（ai/T15）：那条特判的实际后果是
     * 日历模式用户改一次课表就没有课堂铃、没有上课实况、没有自动勿扰，
     * 要等到下一次冷启动 / 开机 / 12 小时兜底 Worker 才回来 —— 与
     * [com.buaa.schedule.widget.BackgroundSync.rescheduleReminders] 的 KDoc 把
     * 「① 系统日历提醒模式」列成"本轮课堂铃没人排、要补排"的第一类人群正好相反。
     * 两种模式现在都走 [com.buaa.schedule.widget.BackgroundSync.rescheduleRemindersAndBells]：
     * 日历模式下 `rescheduleReminders` 那一半照样撤掉应用内闹钟（不注册 = 不会双重通知），
     * 返回 false 之后铃由续排链按它自己那份判据接手，"只撤前者会留下永不消失的常驻通知和
     * 永久勿扰"那件事本来就归 [com.buaa.schedule.reminder.ClassProgressScheduler.rescheduleWindows]
     * 的带判据清理管（同 ai/T11 / ai/T11b 的口径），不需要在这里抢先拆一遍。
     */
    private suspend fun afterDataChangedInternal() {
        val app = getApplication<Application>()
        // 一枚链只读一遍（#536 F2）：下面三步原先各自回库查同一批表（学期行四遍、节次表三遍、
        // 展示口径的课程 3+K 遍），而这三步之间没有任何写那几张表的动作 —— 上面那笔落库才是
        // 唯一一次写，它已经在这之前完成了，所以入口读到的一定是"改完之后"的那一份。
        val chainSnapshot = com.buaa.schedule.widget.readChainSnapshotOrNull(app)
        // 必须兑现 rescheduleReminders 的 Boolean 契约（false=课堂铃没人排、当场补排），
        // 此前这里丢弃返回值：改一节课后上/下课铃被 cancelAll 清掉且不再续排，
        // 实况岛最长 12 小时不出现（只有兜底 Worker 兑现了契约）
        com.buaa.schedule.widget.BackgroundSync.rescheduleRemindersAndBells(app, snapshot = chainSnapshot)
        com.buaa.schedule.widget.BackgroundSync.refreshWidgets(app, snapshot = chainSnapshot)
        // 明日预告的续排此前只挂在开机广播与设置开关上：假期结束回到有课周，
        // 预告链条不会自己回来。数据每次变化都重新判定一次（内部按开关决定排/撤）
        com.buaa.schedule.widget.BackgroundSync.scheduleTomorrowPreview(app, chainSnapshot)
    }

    /**
     * 设置页那枚「每天 22:00 推送明日课程概览」开关拨动之后重新对齐闹钟（#536 F3）。
     *
     * 接收者必须是这一枚 [viewModelScope]，**不许**回过去用设置页那枚 `rememberCoroutineScope()`：
     * `scheduleTomorrowPreview` 在排闹钟那一手之前要跨过查库（学期行 + 展示口径的课程），而页
     * scope 绑在一次组合上 —— 拨完开关立刻按返回键（本仓的顶层导航切 tab 是真的 dispose 目的地）
     * 就把这一整颗协程取消在查库里，prefs 那边却已经同步落盘了 `enabled = true`。结果是开关下次
     * 进来仍然亮着、22:00 那颗闹钟一颗也没排上，而副标题那句"明天没有课或假期中不会推送"恰好
     * 替这个静默 no-op 说了话。关掉那一档本来就安全（`scheduleTomorrowPreview` 在任何挂起点之前
     * 短路撤闹钟），这一手只把"开"那一支挪出组合寿命。
     *
     * 先例：T124a 那一族（`ui/home/HomeScreen.kt` / `ui/course/CourseManagementScreen.kt` 的
     * "接收者是 `viewModel.viewModelScope`，不是本页 `rememberCoroutineScope()` 的那枚 scope"），
     * 反向棘轮在 `ui/WriteChainReceiverWiringGuardTest` 档 ⑥（T297 把它的定义从"写库那一手"拓宽成
     * "写库或有外部副作用的那一手"，这一枚落点就是这么进来的）。
     */
    fun onTomorrowPreviewToggled() {
        viewModelScope.launch {
            com.buaa.schedule.widget.BackgroundSync.scheduleTomorrowPreview(getApplication())
        }
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
                importParseSummary(
                    prefix = "解析完成：",
                    addedCount = pending.addedCount,
                    changedCount = pending.changedCount,
                    groupCount = pending.conflictGroupCount,
                ),
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
        // 预览那一条链同样递学期（T222/#306）：这一格交出去的 conflicts 会喂给
        // 「存在 N 组时间冲突」那句与向导的「只改这些周」，假周次在**落库之前**就得裁掉
        val conflicts = ConflictDetector.findConflicts(courses, semester.startLocalDate, semester.totalWeeks)
        val pending = PendingImport(
            semester = semester,
            courses = courses,
            existingCount = existing.size,
            addedCount = addedCount,
            changedCount = changedCount,
            conflicts = conflicts,
            // 组数不在这里递：由 PendingImport 类体里那枚派生属性从 conflicts 归并出来，
            // 两处逐条勾选的 copy 因此跟着子集一起变（T95①）。措辞仍由 ImportConflictCopy 出
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
                    importParseSummary(
                        prefix = "ICS 解析完成：",
                        addedCount = pending.addedCount,
                        changedCount = pending.changedCount,
                        groupCount = pending.conflictGroupCount,
                    ),
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
                    importParseSummary(
                        prefix = "文本解析完成：",
                        addedCount = pending.addedCount,
                        changedCount = pending.changedCount,
                        groupCount = pending.conflictGroupCount,
                    ),
                )
            }
        }
    }

    /** 备份导出：整表序列化，放 Default 上跑，避免调用方用 Main 调度器时卡界面 */
    suspend fun exportBackup(): String = withContext(Dispatchers.Default) {
        val semester = repository.getCurrentSemester()
        // 全部学期行都要带上（T204）：课程列表是跨学期的，只写主学期那一枚，
        // 换机恢复时历史学期就无据可依（补成"恢复那天开学"）。
        val semesters = repository.getAllSemesters()
        val courses = repository.getAllCourses()
        val timeSlots = repository.getTimeSlots()
        val reminders = repository.getReminders()
        val courseById = courses.associateBy { it.id }
        val backup = BackupData(
            semester = semester?.toBackup(),
            semesters = semesters.map { it.toBackup() },
            timeSlots = timeSlots.map { it.toBackup() },
            courses = courses.map { it.toBackup() },
            reminders = reminders.mapNotNull { setting ->
                courseById[setting.courseId]?.let { course ->
                    BackupReminder(
                        courseKey = ImportPlanner.courseKey(course),
                        enabled = setting.enabled,
                        advanceMinutes = setting.advanceMinutes,
                        // 片段依据（T229 ②/#303）：courseKey 的七维里没有 weeks ⇒ 同一门课拆成的
                        // 多枚片段共用一枚键。不带这一枚，读侧无论怎么写都回答不了
                        // "这条提醒是给哪一行片段的"，只能后写吃前写。判据在 BackupReminderPairing。
                        fragmentWeeks = course.weeks,
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

    /**
     * @param previewConfirmed 这条恢复**是不是**从"用户已经看过条数并点了头"那一支来的
     *   （[restorePendingBackup] / [confirmEmptyRestore] 两枚调用点）。false 时条数判据会
     *   先把这一手拦成 [pendingRestore] 那一问（T270③ 乙档），而不是直接写库。
     */
    fun importBackup(
        json: String,
        allowEmptyReplacement: Boolean = false,
        previewConfirmed: Boolean = false,
    ) {
        viewModelScope.launch {
            withImportLock {
                runCatching {
                    lenientJson.decodeFromString<BackupData>(json)
                }.onSuccess { data ->
                    restoreFromData(
                        data,
                        "备份恢复",
                        json,
                        allowEmptyReplacement,
                        entry = RestoreEntry.BackupFile,
                        previewConfirmed = previewConfirmed,
                    )
                }.onFailure { e ->
                    _importMessage.value = AppMessage("备份恢复失败：${e.message}", isError = true)
                }
            }
        }
    }

    /**
     * 备份里一条课程都没有：继续会清空当前课表，必须由用户明确点头。
     * 口令那一路 T270③ 之后**有**确认弹窗了（条数那一问），但"空口令"仍然直接拒 ——
     * 理由不变：里面本来就没有恢复价值，问一句只是多一次点击。
     */
    data class PendingEmptyRestore(val raw: String)

    private val _pendingEmptyRestore = MutableStateFlow<PendingEmptyRestore?>(null)
    val pendingEmptyRestore: StateFlow<PendingEmptyRestore?> = _pendingEmptyRestore.asStateFlow()

    fun confirmEmptyRestore() {
        val pending = _pendingEmptyRestore.value ?: return
        _pendingEmptyRestore.value = null
        // previewConfirmed=true：这一支用户已经明确点过"仍然恢复"，条数判据不许再问第二遍
        importBackup(pending.raw, allowEmptyReplacement = true, previewConfirmed = true)
    }

    fun dismissEmptyRestore() {
        _pendingEmptyRestore.value = null
    }

    /**
     * 待用户点头的恢复（T270③ 乙档：把口令路拉到与文件路同级的"预览＋确认"）。
     *
     * 卡面点名的病灶之一是两条入口**保护等级不同**：文件恢复有预览弹窗（用户看得见
     * "带了几条节次"），口令导入零预览、粘上去就直接写库。这一枚把手就是补上的那一问 ——
     * 判据由 `domain/schedule/RestoreVolumeGate.kt` 交回 `ConfirmFirst` 档位，
     * 弹窗只念 [com.buaa.schedule.domain.schedule.restoreShareConfirmCopy]（文案同源）。
     *
     * 存的是**已解码的 BackupData** 而不是原文：原文那一路（口令 4MB 上限）留在内存里
     * 只是白白攥一份解压前的负载，而条数已经过闸（≤ 上限），重放一次 restoreFromData 就够。
     */
    data class PendingRestore(
        val data: BackupData,
        val sourceLabel: String,
        val raw: String?,
        val allowEmptyReplacement: Boolean,
        val entry: RestoreEntry,
        val courseCount: Int,
        val timeSlotCount: Int,
        /** 判据当场渲染好的那一句（弹窗**只念**它，不在 UI 里重拼一档 ConfirmFirst） */
        val confirmCopy: String,
    )

    private val _pendingRestore = MutableStateFlow<PendingRestore?>(null)
    val pendingRestore: StateFlow<PendingRestore?> = _pendingRestore.asStateFlow()

    fun dismissPendingRestore() {
        _pendingRestore.value = null
    }

    /** 用户点了"仍然导入"：同一份内容带着 previewConfirmed=true 重放一次（只问一次，不循环） */
    fun confirmPendingRestore() {
        val pending = _pendingRestore.value ?: return
        _pendingRestore.value = null
        viewModelScope.launch {
            withImportLock {
                restoreFromData(
                    pending.data,
                    pending.sourceLabel,
                    pending.raw,
                    pending.allowEmptyReplacement,
                    entry = pending.entry,
                    previewConfirmed = true,
                )
            }
        }
    }

    /** 备份恢复前的只读预览（不写库） */
    data class BackupPreview(
        val versionTooNew: Boolean,
        val semesterName: String?,
        val startDate: String?,
        val totalWeeks: Int?,
        /**
         * 备份里带了几枚学期行（T204）。0 = 老备份 / 老口令只有单字段那一枚 ⇒
         * 恢复时历史学期无据可依，只能走补建那一等（界面上先把这件事说清楚，
         * 而不是等用户发现日期不对）。
         */
        val semesterCount: Int,
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
                // 老备份没有 semesters 键 ⇒ 空列表，此处如实报 0（不是"1 枚都看不清"）
                semesterCount = data.semesters.size,
                courseCount = data.courses.size,
                manualCourseCount = data.courses.count { it.toDomain().isManualCourse },
                timeSlotCount = data.timeSlots.size,
                reminderCount = data.reminders.size,
            )
        }

    /**
     * 生成课表分享口令（当前学期 + 手动课程 + 节次时间）。
     * 返回 null 表示当前没有可分享的课表。
     *
     * 学期行按库里全部学期一起带（T204，与 `exportBackup` 同一口径）：
     * 口令走的就是同一个 `BackupData` JSON，只带当前学期那一枚时，
     * 落在其它学期上的手动课程在对方设备上会被补建成本卡要消灭的那一等
     * （开学日 = 对方导入口令那天）。多带几枚学期行的体积在 deflate 之后可忽略。
     */
    suspend fun buildShareCode(): String? {
        val semester = repository.getCurrentSemester() ?: return null
        val courses = repository.getDisplayCourses(semester)
        if (courses.isEmpty()) return null
        val backup = BackupData(
            semester = semester.toBackup(),
            semesters = repository.getAllSemesters().map { it.toBackup() },
            timeSlots = repository.getTimeSlots().map { it.toBackup() },
            courses = courses.map { it.toBackup() },
        )
        return ScheduleShareCodec.encode(backup)
    }

    /**
     * 通过分享口令导入课表。
     *
     * 每一档结论各说一句实话（T270③ 三档 + 台账 #530 补的三档）：解不出来 / 太大 /
     * 来自更新版本 / 有读不懂的表 / 什么表都没带 / 条数越界被拒 / 合法。
     * "合法"之后仍然不一定写库 —— 条数判据对**没预览过**的那一路先交回确认档，
     * 而它吃的条数是 codec 从**原始 JSON** 上数出来的那一枚（`outcome.counts`），
     * 不是模型的默认值（#530 发现 3：被清零的 0 会让确认弹窗一次都不过）。
     */
    fun importShareCode(code: String) {
        viewModelScope.launch {
            withImportLock {
                when (val outcome = ScheduleShareCodec.decodeOutcome(code)) {
                    is ShareCodeOutcome.Malformed -> {
                        // #530 发现 4：改前 codec 的 `runCatching{}.getOrNull()` 连 Throwable 一起吃掉且零留痕，
                        // 而解码那一站是这一族里唯一没留痕的档（解码器自己的 bug / OOM 都在这枚兜底里消失）。
                        // codec 自己不挂 Log（它整个文件跑在 JVM 单测里，android.util.Log 在那里是"not mocked"），
                        // 于是它把读数随结论交出来，留痕落在这一支 —— 仍旧排在写文案之前。
                        outcome.failure?.let {
                            Log.i(SHARE_CODE_LOG_TAG, "口令解码失败：${it.type}：${it.message}（字符数 ${code.length}）")
                        }
                        _importMessage.value = AppMessage("口令无法识别，请检查是否完整复制", isError = true)
                    }
                    // 以下四支都是"识别得出来、但不许写"的档：句子由 domain 那本册子渲染，ui 只念不拼；
                    // 留痕一律挂**新** tag（下面那枚），不许挂 RESTORE_VOLUME_LOG_TAG ——
                    // 后者的引用枚数（VM 内 5 处）与 Info 级枚数（4 处）被 ShareRestoreVolumeGuardTest
                    // 第 ⑤ 层逐枚钉着，往这里多挂一枚就是把那两把尺念歪。
                    is ShareCodeOutcome.OverSize -> {
                        val copy = when (outcome) {
                            is ShareCodeOutcome.OverSize.EntryTooLong -> shareCodeTooManyCharsCopy(outcome.decision)
                            is ShareCodeOutcome.OverSize.PayloadTooLarge -> shareCodeOverCapCopy(outcome.decision)
                        }
                        Log.i(SHARE_CODE_LOG_TAG, "被拦的口令（体积）：$copy（字符数 ${code.length}）")
                        _importMessage.value = AppMessage(copy, isError = true)
                    }
                    is ShareCodeOutcome.FromNewerApp -> {
                        val copy = shareCodeNewerFormatCopy(outcome.decision)
                        Log.i(SHARE_CODE_LOG_TAG, "被拒的口令（版本过高）：$copy")
                        _importMessage.value = AppMessage(copy, isError = true)
                    }
                    is ShareCodeOutcome.UnreadableTables -> {
                        val copy = shareCodeUnreadableTablesCopy(outcome.decision)
                        Log.i(SHARE_CODE_LOG_TAG, "被拒的口令（读不懂的表）：$copy")
                        _importMessage.value = AppMessage(copy, isError = true)
                    }
                    is ShareCodeOutcome.NothingRestorable -> {
                        val copy = shareCodeNothingNamedCopy(outcome.decision)
                        Log.i(SHARE_CODE_LOG_TAG, "被拒的口令（什么表都没带）：$copy")
                        _importMessage.value = AppMessage(copy, isError = true)
                    }
                    is ShareCodeOutcome.OverCapacity -> {
                        val copy = restoreVolumeRejectCopy(outcome.reject)
                        // 留痕排在写文案之前：toast 会自己消失，而"哪一条口令被哪张表的上限拦住"
                        // 是事后唯一能查的账（级别 Info/Warn，本仓已知设备把 logcat 砍在 Info 以下）
                        Log.i(RESTORE_VOLUME_LOG_TAG, "被拒的口令：$copy")
                        _importMessage.value = AppMessage(copy, isError = true)
                    }
                    is ShareCodeOutcome.Valid -> restoreFromData(
                        outcome.data,
                        "口令导入",
                        raw = null,
                        entry = RestoreEntry.ShareCode,
                        previewConfirmed = false,
                        counts = outcome.counts,
                    )
                }
            }
        }
    }

    /**
     * @param raw 原始备份内容；非 null 表示这是"导入备份文件"路径，
     *   空备份被拒时可以把它交给用户二次确认。
     * @param entry 哪一路进来的（口令 / 备份文件）—— 条数判据用它决定句子里说哪一路，
     *   两路的**保护等级相同**（T270③ 乙档：没预览过的那一律先问一句，不再一路有预览一路没有）。
     * @param previewConfirmed 用户是否已经看过条数并点过头。
     */
    private suspend fun restoreFromData(
        data: BackupData,
        sourceLabel: String,
        raw: String?,
        allowEmptyReplacement: Boolean = false,
        entry: RestoreEntry,
        previewConfirmed: Boolean,
        counts: RestoreCounts? = null,
    ) {
        if (data.version > BACKUP_FORMAT_VERSION) {
            _importMessage.value = AppMessage(
                "${sourceLabel}失败：备份来自更新版本的应用（v${data.version}），请先升级后再恢复",
                isError = true,
            )
            return
        }
        // 条数闸门：课程／节次／提醒**三张表各一枚上限**（T270① 甲档）。
        // 改前这里与 `ScheduleShareCodec` 那一处都只数 data.courses.size，而节次表零上限 ⇒
        // 一条带 3 万行节次的口令把整张作息表换掉（TimeSlotEntity 的主键是 autoGenerate 的 id，
        // 那些行一行都不撞键），而同趟事务末尾的 UndoManager.clear() 又拆掉了唯一的撤销把手。
        // ⚠️ #530 发现 3：条数**优先吃调用点递进来的那一枚**（口令路 = codec 从原始 JSON 上数好的三枚），
        // 只有没递的入口（备份文件那两路，以及确认弹窗回来那一手）才回落到模型字段。
        // 把回落当成唯一来路，就是让 `ignoreUnknownKeys` + 全字段默认值替闸门数数 ⇒
        // 被改名清空的表在闸门眼里是 0，"没预览过就先问一句"那一档跟着失效，一次弹窗都不过。
        val gateCounts = counts ?: RestoreCounts(
            courseCount = data.courses.size,
            timeSlotCount = data.timeSlots.size,
            reminderCount = data.reminders.size,
        )
        when (
            val volume = RestoreVolumeGate.decide(
                counts = gateCounts,
                limits = RestoreVolumeLimits.Default,
                entry = entry,
                previewed = previewConfirmed,
                insideRestoreTx = false,
            )
        ) {
            is RestoreVolumeDecision.Reject -> {
                val copy = restoreVolumeRejectCopy(volume)
                Log.i(RESTORE_VOLUME_LOG_TAG, "被拒的恢复：$copy")
                _importMessage.value = AppMessage(copy, isError = true)
                return
            }
            is RestoreVolumeDecision.ConfirmFirst -> {
                // 句子由判据渲染（档位与措辞同源），弹窗那一边只念不拼
                val confirmCopy = restoreShareConfirmCopy(volume)
                _pendingRestore.value = PendingRestore(
                    data = data,
                    sourceLabel = sourceLabel,
                    raw = raw,
                    allowEmptyReplacement = allowEmptyReplacement,
                    entry = volume.entry,
                    courseCount = volume.courseCount,
                    timeSlotCount = volume.timeSlotCount,
                    confirmCopy = confirmCopy,
                )
                Log.i(
                    RESTORE_VOLUME_LOG_TAG,
                    "待确认：${volume.entry.label}带 ${volume.timeSlotCount} 条节次（上限内），等用户点头",
                )
                _importMessage.value = AppMessage(
                    "${sourceLabel}还没开始：口令里带 ${volume.timeSlotCount} 条节次，" +
                        "会整张替换你的作息表，请在弹窗里确认",
                )
                return
            }
            is RestoreVolumeDecision.Write -> Unit
        }
        val result = repository.restoreBackupData(
            backupSemester = data.semester?.toDomain(),
            // 备份里带全的学期行（T204）：按 termCode 与课程分组配对，缺了才轮得到补建那一等。
            // 老备份 / 老口令没有这个键 ⇒ 空列表，读侧在仓储层回落到上面那一枚 semester。
            backupSemesterRows = data.semesters.map { it.toDomain() },
            courses = data.courses.map { it.toDomain() },
            timeSlots = data.timeSlots.map { it.toDomain() },
            // 提醒逐枚递给内核，**不在这里按 courseKey 归并**（T229 ②/#303）：
            // 改前这一手是 `data.reminders.associate { … }`，同门课多片段共用一枚键时后写吃前写，
            // 恢复后只有一枚片段领得到提醒（关掉的那半重新响、改过提前量的那半回默认）。
            // 配对判据与退档口径全在 `data/backup/BackupReminderPairing.kt`，册子由
            // `BackupReminderPairingGuardTest` 第 ② 层钉。
            backupReminders = data.reminders.map { BackupReminderPairing.settingOf(it) },
            fallbackStartDate = mostRecentMonday(),
            allowEmptyReplacement = allowEmptyReplacement,
            // 条数判据在事务里再复核一遍（同一枚内核、同一份上限），entry 递过去让它说得出是哪一路
            entry = entry,
        )
        when (result) {
            is RestoreResult.OverCapacity -> {
                // 事务内那一遍拦下来的：库里一个字节都没写（判据排在任何 delete/insert 之前）。
                // 走到这一等说明有人新开了第三枚调用点、绕过了门外那一遍 —— 日志要把这件事留在盘上。
                val copy = restoreVolumeRejectCopy(result.reject)
                Log.i(RESTORE_VOLUME_LOG_TAG, "事务内复核拒掉的恢复：$copy")
                _importMessage.value = AppMessage(copy, isError = true)
            }
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
                // 补建账必须念出来（T204）：这些学期行的开学日不是备份里的，是按恢复那天推算的。
                // 不念就是把跨学期恢复的日期偏移重新藏回静默里 —— 那正是本卡的病灶。
                // ⚠️ 次序判据（`BackupSemesterRestoreWiringGuardTest` 第 ⑥ 层钉）：**取账 → 留痕日志 → 写文案**。
                // 别把上面那两行挪进 buildString 里，也别把新加的 append 排到 rebuiltNote 之后
                // （补建那一句是"最后一句实话"，T204/T223 的文案册子按它收尾；T229 ② 那枚提醒账插在它前面）。
                val rebuiltNote = com.buaa.schedule.data.backup.rebuiltSemestersNote(result.rebuiltSemesters)
                if (rebuiltNote != null) Log.i(RESTORE_LOG_TAG, "$sourceLabel：$rebuiltNote")
                _importMessage.value = AppMessage(buildString {
                    append("${sourceLabel}成功：${result.restoredCourses} 条课程")
                    if (result.insertedManual > 0) append("（新增手动课程 ${result.insertedManual} 条）")
                    if (result.skippedInvalid > 0) append("，跳过无效数据 ${result.skippedInvalid} 条")
                    // 提醒对不回课程那一档也要念出来（T229 ②/#303）：不念的话"备份吃提醒"
                    // 仍然是静默的 —— 这一枚与上面那枚 skippedInvalid 同一条口径。
                    if (result.remindersUnplaced > 0) append("，提醒 ${result.remindersUnplaced} 条没能对回课程")
                    if (rebuiltNote != null) append("。$rebuiltNote")
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
            // T100①＋T106：起手清场成对清**两对** —— `diff`/`skippedOccurrences`（computeDiff 一次返回，
            // 非空分支成对写、dismissCalendarSyncDiff 成对清）与 `message`/permissionPermanentlyDenied
            // （onCalendarPermissionDenied 一次写两枚）：漏一半 = 同步完成旁还挂着「去系统设置」那颗按钮。
            _calendarSync.update {
                it.copy(syncing = true, message = null, permissionPermanentlyDenied = false, diff = null, skippedOccurrences = 0)
            }
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
        // 同上：这份 diff 就此作废，它的附属说明跟着一起清（应用结果的文案只报新增/更新/删除）
        _calendarSync.update {
            it.copy(diff = null, skippedOccurrences = 0, reminderMinutes = minutes.toString())
        }
        viewModelScope.launch {
            _calendarSync.update { it.copy(syncing = true) }
            val result = suspendCatching { calendarSyncManager.apply(calendarId, pending, minutes) }.getOrNull()
            // T265：失败那一堆数字里混着两种代价完全不同的事——「没写进去」与「写进去了但认不回来」。
            // 后者要单独念一句（并把「按所有权标记回收」这条路指给用户），否则用户再点一次同步
            // 只会往同一个日历上再插一批重复事件。分档住在 domain 内核，这里只做句子映射。
            val verdict = result?.let {
                CalendarReclaimPlanner.applyCopy(
                    failed = it.failed,
                    orphanSuspects = it.orphanSuspects,
                    rolledBack = it.rolledBack,
                )
            }
            _calendarSync.update {
                it.copy(
                    syncing = false,
                    message = when {
                        result == null -> AppMessage("同步失败：日历写入异常，请重试或检查日历权限", isError = true)
                        verdict == CalendarReclaimPlanner.ApplyCopy.OrphansRecognisable -> AppMessage(
                            "同步未完成：${result.orphanSuspects} 个日程已经进到你的日历里，本应用却认不出它们；" +
                                "再点一次同步会重复插入，可点「移除已同步的日程」按所有权标记回收",
                            isError = true,
                        )
                        verdict == CalendarReclaimPlanner.ApplyCopy.RolledBackOwnWrites -> AppMessage(
                            "同步未完成：${result.rolledBack} 个日程写入后映射落库失败，已被本应用当场撤回，请重试",
                            isError = true,
                        )
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
        _calendarSync.update { it.copy(showPicker = true, calendarsLoaded = false) } // T110：复位缓存旗标，列表不再"进程寿命"（唯一读点是 :1611 那次早返回）
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
            _calendarSync.update { it.copy(syncing = true, message = null, permissionPermanentlyDenied = false) } // T110：起手成对清，与 :1488 同一枚仪式（这条链不经那个入口）
            // T265：目标日历 id 只拿去**报数**（映射里落在别的日历上的那些枚数会被折进句子档），
            // 不参与也不允许参与删除范围的收窄 —— 收窄就会把旧目标日历上的那些留成孤儿。
            val targetCalendarId = _calendarSync.value.targetId
            val removed = suspendCatching { calendarSyncManager.removeAllSyncedEvents(targetCalendarId) }.getOrNull()
            _calendarSync.update {
                it.copy(
                    syncing = false,
                    message = removed?.let { outcome ->
                        val n = outcome.removed
                        when (outcome.copy) {
                            // 只有「按所有权枚回来的都删掉了」这一档才配念那句承诺的完成时
                            RemovalCopy.AllReclaimed -> AppMessage("已移除 $n 个日程")
                            RemovalCopy.NothingWasOurs -> AppMessage(
                                "没有找到本应用创建的日程，你在日历里自己的日程一枚都没被动过",
                                isSuccess = true,
                            )
                            RemovalCopy.ReclaimedButMarkerBlind -> AppMessage(
                                "已移除 $n 个日程，但这台机器的日历没让本应用认回自己创建的事件，" +
                                    "可能还有认不出的日程留在日历里（本应用不会去猜它们）",
                                isError = true,
                            )
                            RemovalCopy.BlindReclaimSpansOtherCalendars -> AppMessage(
                                "已移除 $n 个日程；本应用没能按所有权标记认回事件，而你换过目标日历，" +
                                    "旧日历上可能还留着认不出的日程",
                                isError = true,
                            )
                            RemovalCopy.PartiallyReclaimed -> AppMessage(
                                "已移除 $n 个日程，${outcome.failed} 个删除失败（映射已保留，可再点一次）",
                                isError = true,
                            )
                            RemovalCopy.AllDeletesFailed -> AppMessage(
                                "移除没成：${outcome.failed} 个日程一枚都没删掉，映射已保留，可再点一次",
                                isError = true,
                            )
                        }
                    } ?: AppMessage("移除失败：日历写入异常，请检查权限后重试", isError = true),
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
        // T110：死 id 只清内存不够 —— 上面那两枚初值是从偏好里读回来的（:1472-1473），下一次冷启动
        // 又把死 id 捞回来：界面继续念那个已经不存在的日历名，真去同步时 CALENDAR_ID 打进死 id、异常被
        // CalendarSyncManager 那颗 runCatching 吞掉，用户读到的就成了「同步失败：日历写入异常」。
        // 撤的时候两枚 key 也必须成对（它们与 targetId/targetName 是同一件事的两头，漏一枚等于换个方向
        // 念旧账）。空列表不动手：查询失败交回来的也是一份空列表（见 §6.4-A 那一格），那一刻分不清「日历被删了」
        // 与「provider 抖了一下」—— 宁可让偏好多留一次，也不要在一次抖动里抹掉用户选好的日历。
        if (targetGone && loaded.isNotEmpty()) settingsPrefs.edit {
            remove("calendar_sync_target_id")
            remove("calendar_sync_target_name")
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
        // 预览弹窗已经念过条数并拿到用户的点头 ⇒ 条数判据这一路直接放行（不再问第二遍）
        importBackup(pending.raw, previewConfirmed = true)
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

    /**
     * 导出 ICS 到用户选中的位置（T252，#309）。
     *
     * ## 改前的三行为什么必须拆
     *
     * 改前这一节是：算内容 → 开流写 → 报数，中间**没有一格问过"有没有东西可写"**：
     * `val written = withContext(Dispatchers.IO) { writeTextToUri(uriString, result.text) }`
     * 而 `writeTextToUri` 走的是 `ContentResolver.openOutputStream(uri)` 的默认模式（截断写），
     * 于是 `IcsExporter` 交回 `ExportResult(text = "", eventCount = 0, …)` 那一等 ——
     * 课程不为空、学期也设了、但每门课的周次全落在学期区间之外（T222 裁周次）或节次全不在作息表里 ——
     * **先把用户挑的那份文件截成 0 字节**，再念一句「已导出 0 个日程，可在系统日历中导入」。
     * 用户读到的是一次成功，盘上发生的是一次破坏。
     *
     * ## 现在这一手长在哪
     *
     * 判空**排在开流之前**：条数、跳过数、导出件文本、是否真选中了文件这四枚事实先交给
     * [IcsExportGate.decide]，再由它的档位决定要不要开流 ——
     * [IcsExportDecision.WriteTruncating] 才截断写，[IcsExportDecision.RefuseEmpty] 与
     * [IcsExportDecision.RefuseInconsistent] 一次都不开流（用户选中的文件保持原样），
     * [IcsExportDecision.NoFileSelected] 一句不说。句子在
     * [icsExportWrittenMessage] / [icsExportRefusedMessage] / [icsExportInconsistentMessage]，
     * 与本卡的裁定同尺：**零日程时不动用户选中的那个文件**。
     *
     * ⚠️ 「保持原样」钉的是我们这一手不动它；`CreateDocument` 那一步由系统选择器建文档，
     * 不在本应用手里（本卡零设备，没量过它建出来的那一刻是什么状态）。
     */
    fun exportIcsTo(uriString: String) {
        viewModelScope.launch {
            val result = exportIcs()
            if (result == null) {
                showMessage("暂无可导出的课表，请先导入课程并设置学期", isError = true)
                return@launch
            }
            when (val decision = IcsExportGate.decide(
                eventCount = result.eventCount,
                skippedOccurrences = result.skippedOccurrences,
                icsText = result.text,
                fileSelected = uriString.isNotBlank(),
            )) {
                // 取消不是失败：没落地文件，后面两判都无从谈起，也一句话不说
                IcsExportDecision.NoFileSelected -> Unit
                is IcsExportDecision.RefuseEmpty -> showMessage(icsExportRefusedMessage(decision), isError = true)
                is IcsExportDecision.RefuseInconsistent ->
                    showMessage(icsExportInconsistentMessage(decision), isError = true)
                is IcsExportDecision.WriteTruncating -> {
                    val written = withContext(Dispatchers.IO) { writeTextToUri(uriString, decision.text) }
                    showMessage(
                        if (written) icsExportWrittenMessage(decision) else "导出失败：无法写入所选位置",
                        isError = !written,
                    )
                }
            }
        }
    }

    /**
     * WakeUp 兼容 JSON 导出。
     *
     * 此前这段跑在设置页 ActivityResult 回调所在的**主线程**上，并且 `runCatching`
     * 把失败静默吞掉：用户选完保存位置就以为成功了，实际可能写出一个空文件。
     *
     * ## T277 审计 N4 → T279①：那把闸门补到这条链上（形状逐手照 [IcsExportGate]）
     *
     * 改前唯一的门是 `if (courses.isEmpty())`，判的是**入参**而不是"写出去几段"：
     * 两档 drop（没节次 = T27、没周次 = #237）把每一门都丢下时，`toWakeUpJson` 交回的是
     * `{"name":"…","courses":[]}`，而下一手 `writeTextToUri(uriString, json)` 是默认的**截断写** ——
     * 用户挑的那份 `.json` 当场被换成一张空课表，屏幕念的还是那句「WakeUp JSON 已导出（0 段排课…）」。
     * 现在四枚事实（写出去的段数、丢下的段数、导出件文本、是否真选中了文件）先交给
     * [WakeUpExportGate.decide]，再由档位决定要不要开流：[WakeUpExportDecision.WriteTruncating]
     * 才截断写，[WakeUpExportDecision.RefuseEmpty] 与 [WakeUpExportDecision.RefuseInconsistent]
     * 一次都不开流（用户选中的文件保持原样），[WakeUpExportDecision.NoFileSelected] 一句不说。
     * 句子在 [wakeUpExportMessage] / [wakeUpExportRefusedMessage] / [wakeUpExportInconsistentMessage]。
     *
     * ⚠️ 「保持原样」钉的是我们这一手不动它；`CreateDocument` 那一步由系统选择器建文档，
     * 不在本应用手里（本卡零设备，没量过它建出来的那一刻是什么状态）。
     * ⚠️ 入参那一枚 `courses.isEmpty()` 的门**保留**：它说的是"课表里一枚都没有"（用户能自己补），
     * 与"有课但一段都写不出去"（drops 全丢）是两格，句子也不同 ⇒ 并成一格就有一格没人念。
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
                // 「今天」出自 dayTicker 滴进 uiState 的那一枚值（不是这里再读一次时钟）：
                // 顶栏「第 N 周」与这份导出件的兜底起始日必须同源于一次读数，跨零点时才会说同一天。
                com.buaa.schedule.domain.schedule.ScheduleExporters.toWakeUpJson(
                    courses,
                    semester,
                    repository.getTimeSlots(),
                    uiState.value.today,
                )
            }
            // 不进导出件的课有**两档**（T27 没有节次 / T203 没有周次），两档都得报数，且一门只落一档：
            // 判据取 WakeUpDropTally 不在这里重拼一遍。报数只能是"写出去了几门"而不是 courses.size ——
            // 后者会在丢掉课时仍然承诺"N 门课"，而对方 App 里少了哪几门用户无从知道（这些课在网格上本来就画不出来）。
            val drops = com.buaa.schedule.domain.schedule.WakeUpDropTally.tally(courses)
            // 段数由上面那本账递进来（内核不读库、不数第二次）：写出去几段 = 总数减两档之和
            when (val decision = WakeUpExportGate.decide(
                writtenFragments = courses.size - drops.droppedCourses,
                droppedFragments = drops.droppedCourses,
                json = json,
                fileSelected = uriString.isNotBlank(),
            )) {
                // 取消不是失败：没落地文件，后面两判都无从谈起，也一句话不说
                WakeUpExportDecision.NoFileSelected -> Unit
                is WakeUpExportDecision.RefuseEmpty -> showMessage(wakeUpExportRefusedMessage(decision), isError = true)
                is WakeUpExportDecision.RefuseInconsistent ->
                    showMessage(wakeUpExportInconsistentMessage(decision), isError = true)
                is WakeUpExportDecision.WriteTruncating -> {
                    val written = withContext(Dispatchers.IO) { writeTextToUri(uriString, decision.json) }
                    showMessage(
                        if (written) wakeUpExportMessage(courses.size, drops.noPeriodCourses, drops.noWeekCourses) else "导出失败：无法写入所选位置",
                        isError = !written,
                    )
                }
            }
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

