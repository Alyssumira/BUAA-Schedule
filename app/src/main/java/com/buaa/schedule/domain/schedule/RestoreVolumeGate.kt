package com.buaa.schedule.domain.schedule

/**
 * 恢复/导入这一手的入口是哪一路。两路的**保护等级**过去不一样（T270 的病灶之一）：
 * 备份文件走「读文件 → [预览] → 用户点恢复」，口令走「粘贴 → 立刻写库」，零预览零确认。
 * 判据把这一枚当参数收进来，于是"口令这一路要不要先把话说清楚再动手"是**册子里的一格**，
 * 不是散在两个调用点里的两句话。
 *
 * 文件路那一手预览指的是 `ui/ScheduleViewModel.parseBackupPreview`（口令路改前没有对应物）。
 */
enum class RestoreEntry {
    ShareCode,
    BackupFile,

    ;

    /** 用户句子里那一路的名字（拒绝/确认文案都要说得出是哪一路动的手） */
    val label: String
        get() = when (this) {
            ShareCode -> "口令导入"
            BackupFile -> "备份恢复"
        }
}

/**
 * 哪一枚上限被越过。三张表**各自一枚闸**（T270 ⓪ 甲档的裁定）：
 * 改前全仓只有 [CourseConstraints.MAX_COURSE_COUNT] 一把条数尺，节次表与提醒表零上限。
 * 并成一枚"总条数"闸门不算数：一条灌 3 万行节次而课程只带 1 条的口令，
 * 总条数照样过得了闸门，而它毁掉的正是节次表那一头。
 */
enum class OverCapacityTable(val label: String) {
    Courses("课程"),
    TimeSlots("节次"),
    Reminders("提醒"),
}

/**
 * 这一趟写库会不会把节次表**整张换掉**，换掉之后手上还剩不剩回头的把手。
 *
 * [Necessary] = 该留一份撤销快照而没有得留：`data/local/TimeSlotDao.kt` 一共四手
 * （`observeAll` / `getAll` / `upsertAll` / `deleteAll`），零"回滚上一版"那一路；
 * 而 `data/repository/ScheduleRepository.kt` 的 `restoreBackupDataInTx` 在同一趟事务里
 * 既 `deleteAll()`+`upsertAll()` 换表、又 `UndoManager.clear()` 拆掉唯一那枚撤销把手。
 *
 * 本卡**没有**给它配快照机制（那要动 `UndoManager` 的条目形状 + `applyUndo` 分支 +
 * 导入页的撤销入口，超出本卡面；已在回执里点名交回编排者）。它的两个真实消费点：
 * 1. 确认弹窗那一句「这一步撤销不了」（[restoreShareConfirmCopy]）——把不可撤销念到明处；
 * 2. 写库前后的留痕日志（`restoreSlotReplacementLogCopy`，Info 级）。
 * 也就是 ⓪ 问里丙档**能今天落地的那一半**（说实话），不含快照本体。
 */
enum class UndoSnapshotNeed {
    Necessary,
    None,
}

/**
 * 判据的输入：**只有已经数好的条数**。本文件不许自己去 `List.size`、不碰 Room、不读设备。
 */
data class RestoreCounts(
    val courseCount: Int,
    val timeSlotCount: Int,
    val reminderCount: Int,
)

/**
 * 三张表的上限（甲档：各表分别一枚，谁越界点谁的名）。
 *
 * 三枚数各自的依据（朝窄方向不许误杀合法的规模）：
 * - 课程沿用 [CourseConstraints.MAX_COURSE_COUNT]（2000）—— 全仓唯一那把条数尺，
 *   本卡不另起第二枚数字，否则"哪个上限说了才算"又变成两把尺；
 * - 节次 500：应用内那一手**根本加不出行**（`ui/settings/SettingsScreen.kt` 的节次区
 *   只改时间、不改节次数量，`domain/model/TimeSlotProfile.kt` 是 14 行），
 *   课程侧的节次上界是 [CourseConstraints.MAX_PERIOD]=30；留 500 是给
 *   「别的学校整表搬来的作息表 / 用户手改的大备份」那一档活路 ——
 *   卡面点名的**合法 200 行节次表**在里面（2.5 倍余量），
 *   而口令能塞进 4MB 解压上限的那一档（约 8 万行、实测形状最小的 JSON 行 51 字节）
 *   在它 60 倍以上，一枚都进不来；
 * - 提醒 2000：提醒是逐枚挂在课程行上的（`BackupReminder.courseKey`），
 *   合法备份里"提醒行数 > 课程行数"这件事本身就不成立，所以跟着课程那一枚上限走。
 */
data class RestoreVolumeLimits(
    val maxCourses: Int,
    val maxTimeSlots: Int,
    val maxReminders: Int,
) {
    companion object {
        /** 节次表的条数上限（依据见类注释） */
        const val MAX_TIME_SLOT_COUNT = 500

        val Default = RestoreVolumeLimits(
            maxCourses = CourseConstraints.MAX_COURSE_COUNT,
            maxTimeSlots = MAX_TIME_SLOT_COUNT,
            maxReminders = CourseConstraints.MAX_COURSE_COUNT,
        )
    }
}

/**
 * 「这一次恢复/导入：落不落库、先问不问一句、拒的话拒在哪张表」三件事的**同一本册子**（T270）。
 *
 * ## 病在哪（三行连起来才成立，逐行都"看着没问题"）
 *
 * 1. `data/share/ScheduleShareCodec.kt` 的闸门（裸 :58）只数 `it.courses.size`；
 *    `ui/ScheduleViewModel.kt` 同族第二处（裸 :1589）也只数课程。
 *    ⇒ 一条只带 1 门课程、却带 3 万行节次的口令，两道闸门都当它合法。
 * 2. 节次侧的校验只有 `domain/schedule/TimeSlotValidation.kt` 的 `isValidTimeSlot`
 *    （裸 :21），它判的是"时刻真不真、结束晚不晚于开始"，**不钳 `number`、更不数行数**；
 *    而 `data/local/TimeSlotEntity.kt`（裸 :8）主键是 autoGenerate 的 `id`、`number` 上零索引
 *    ⇒ 3 万行**一行都不撞键**，整批进库。
 * 3. `data/repository/ScheduleRepository.kt` 的 `restoreBackupDataInTx`（裸 :907 换表、裸 :942 清栈）
 *    在同一趟事务里先 `deleteAll()` 掉用户整张作息表、再 `UndoManager.clear()`
 *    —— 唯一的撤销把手被这一手自己拆了，而 `TimeSlotDao` 零回滚路径。
 *
 * ## 判据顺序（顺序即语义，一格都不许换序）
 *
 * 1. **先问上限**（[capacityViolation]）：三张表逐张数，越界那一枚点的名出 [Reject]。
 *    它排在所有分支最前，含 `insideRestoreTx` 那一等 —— 超限的东西**一次都不许落库**，
 *    不许被"用户点了确认"降级成"那就写吧"（确认不是越权手）。
 * 2. `insideRestoreTx == true` ⇒ [Write]，永不 [ConfirmFirst]：
 *    这一等是事务里的最后一道复核，门外已经问过一遍；在这里再要一次预览就是
 *    「写库前自己弹自己」的死循环，也是把只读判据变成有副作用的那一手。
 * 3. 口令路且没预览过、而这一趟**真的要动节次表**（`timeSlotCount > 0`）⇒ [ConfirmFirst]。
 *    条件是"要动节次表"而不是"只要带东西来"：口令没带节次时 `deleteAll()` 那一手根本不会执行
 *    （`restoreBackupDataInTx` 里那句 `if (safeSlots.isNotEmpty())`），无破坏可确认。
 * 4. 其余 ⇒ [Write]。
 *
 * ## 为什么不折成一枚 Boolean
 *
 * 三档各自要说不同的话：[Reject] 要报"哪张表、多少条、上限多少"；[ConfirmFirst] 要报
 * "将替换你的 N 条节次 + 这一步撤销不了"；[Write] 一句话不说直接落库。
 * 折成 Boolean 之后"拒"与"先问"在同一枚 false 里合体，调用点只能猜，
 * 而本卡的两条修法（甲＝拒、乙＝先问）恰好分在那两侧。
 *
 * ## 证据档位
 *
 * 表驱动 JVM 单测 `RestoreVolumeGateTest`（逐格 + 两侧格子：朝宽＝闸门又只数课程；
 * 朝窄＝把合法 200 行节次表误杀）与源码形状守卫 `ShareRestoreVolumeGuardTest`
 * （三枚调用点真的吃这一判、口令路的确认弹窗真的长在那一句上、被拒那一手真的留了日志）。
 */
sealed interface RestoreVolumeDecision {

    /** 直接落库：这一趟该不该留撤销快照由 [undoSnapshot] 说（本卡只念不建，见 [UndoSnapshotNeed]） */
    data class Write(val undoSnapshot: UndoSnapshotNeed) : RestoreVolumeDecision

    /**
     * 先把话说清楚再落库（乙档：把口令路拉到与文件路同级的预览＋确认）。
     * 条数从这里交回调用点，弹窗不许自己去摸 `BackupData` —— 摸得到列表就等于摸得到写库。
     */
    data class ConfirmFirst(
        val entry: RestoreEntry,
        val courseCount: Int,
        val timeSlotCount: Int,
        val undoSnapshot: UndoSnapshotNeed,
    ) : RestoreVolumeDecision

    /** 一条都不落库，并把越界的那张表点出名（甲档） */
    data class Reject(
        val entry: RestoreEntry,
        val table: OverCapacityTable,
        val count: Int,
        val limit: Int,
    ) : RestoreVolumeDecision
}

object RestoreVolumeGate {

    /**
     * 三张表逐张数上限，越界的那一枚交回 [RestoreVolumeDecision.Reject]；都在 limit 内交回 null。
     *
     * 检查顺序固定为 课程 → 节次 → 提醒：多个越界同时出现时报**第一个**，
     * 这样同一份输入永远只有一句结论（表驱动单测才钉得住"念哪句"）。
     */
    fun capacityViolation(
        counts: RestoreCounts,
        limits: RestoreVolumeLimits,
        entry: RestoreEntry,
    ): RestoreVolumeDecision.Reject? {
        if (counts.courseCount > limits.maxCourses) {
            return RestoreVolumeDecision.Reject(entry, OverCapacityTable.Courses, counts.courseCount, limits.maxCourses)
        }
        if (counts.timeSlotCount > limits.maxTimeSlots) {
            return RestoreVolumeDecision.Reject(entry, OverCapacityTable.TimeSlots, counts.timeSlotCount, limits.maxTimeSlots)
        }
        if (counts.reminderCount > limits.maxReminders) {
            return RestoreVolumeDecision.Reject(entry, OverCapacityTable.Reminders, counts.reminderCount, limits.maxReminders)
        }
        return null
    }

    /**
     * 这一趟会不会把节次表整张换掉。判据与仓储里那句 `if (safeSlots.isNotEmpty())` 同尺：
     * 带 0 条节次时 `deleteAll()` 那一手压根不执行，无破坏也就无需快照。
     */
    fun undoSnapshotNeed(counts: RestoreCounts): UndoSnapshotNeed =
        if (counts.timeSlotCount > 0) UndoSnapshotNeed.Necessary else UndoSnapshotNeed.None

    fun decide(
        counts: RestoreCounts,
        limits: RestoreVolumeLimits,
        entry: RestoreEntry,
        previewed: Boolean,
        insideRestoreTx: Boolean,
    ): RestoreVolumeDecision {
        capacityViolation(counts, limits, entry)?.let { return it }
        val undoSnapshot = undoSnapshotNeed(counts)
        if (insideRestoreTx) return RestoreVolumeDecision.Write(undoSnapshot)
        if (entry == RestoreEntry.ShareCode && !previewed && counts.timeSlotCount > 0) {
            return RestoreVolumeDecision.ConfirmFirst(
                entry = entry,
                courseCount = counts.courseCount,
                timeSlotCount = counts.timeSlotCount,
                undoSnapshot = undoSnapshot,
            )
        }
        return RestoreVolumeDecision.Write(undoSnapshot)
    }
}

// ─────────────────── 文案（同在本文件：档位与句子必须同源，抄到 ui 里就是第二把尺）───────────────────

/** [RestoreVolumeDecision.Reject] 那一句：说得出哪张表、多少条、上限多少，并保证一次都没写。 */
fun restoreVolumeRejectCopy(decision: RestoreVolumeDecision.Reject): String =
    "${decision.entry.label}已拒绝：${decision.table.label}有 ${decision.count} 条，" +
        "超过上限 ${decision.limit} 条。一条都没有写入，你的课程与节次表还是原样。" +
        "请确认这份口令/备份是否被改坏"

/** 口令路确认弹窗的标题。 */
fun restoreShareConfirmTitle(): String = "这条口令要替换你的节次表"

/**
 * 口令路确认弹窗的正文：把"将替换你的 N 条节次"与"这一步撤销不了"两件事都念出来。
 *
 * 后那一句是 ⓪ 问里丙档今天能落的那一半 —— 快照本体没有（见 [UndoSnapshotNeed] 的 KDoc），
 * 所以至少在用户点头之前，这一手不再是**静默**不可撤销。
 */
fun restoreShareConfirmCopy(decision: RestoreVolumeDecision.ConfirmFirst): String = buildString {
    append("口令里带 ${decision.timeSlotCount} 条节次时间，导入后会整张替换你现在的作息表")
    append("（${decision.courseCount} 条课程按口令内容合并）。")
    if (decision.undoSnapshot == UndoSnapshotNeed.Necessary) {
        append("这一步撤销不了：节次表没有回滚路径，导入记录也只能告诉你改过什么，改不回原样。")
    }
    append("想保留现在的作息表就点取消。")
}

/**
 * 换表那一手的留痕文案（Info 级；本仓已知 HyperOS 把 logcat 截在 Info，`Log.d` 等于没写）。
 *
 * 恢复成功的 toast 会自己消失，而"哪一趟把用户的作息表换掉了"是事后唯一能查的东西。
 */
fun restoreSlotReplacementLogCopy(sourceLabel: String, timeSlotCount: Int, undoSnapshot: UndoSnapshotNeed): String =
    "$sourceLabel：节次表整张替换为 $timeSlotCount 条，撤销快照 ${if (undoSnapshot == UndoSnapshotNeed.Necessary) "必要但没有" else "不需要"}" +
        "（TimeSlotDao 零回滚路径，UndoManager 在同一趟事务里 clear）"
