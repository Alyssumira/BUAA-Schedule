package com.buaa.schedule.data.calendar

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.buaa.schedule.data.local.CalendarSyncEntity
import com.buaa.schedule.data.repository.ScheduleRepository
import com.buaa.schedule.domain.calendar.CalendarReclaimPlanner
import com.buaa.schedule.domain.calendar.CalendarReclaimPlanner.InsertAftermath
import com.buaa.schedule.domain.calendar.CalendarReclaimPlanner.InsertBatchLanding
import com.buaa.schedule.domain.calendar.CalendarReclaimPlanner.MappingFlush
import com.buaa.schedule.domain.calendar.CalendarReclaimPlanner.ReclaimPlan
import com.buaa.schedule.domain.model.startLocalDate
import com.buaa.schedule.domain.schedule.ScheduleOccurrences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.TimeZone

/**
 * 系统日历同步（Calendar Provider）。
 *
 * 定位是“用户手动触发的增量同步”，不做后台定时任务：
 * 课程/学期/节次变化后由用户在设置页发起，先展示差异再批量提交。
 * 每个实际上课对应一个日历事件，事件身份与 ICS 导出共用
 * [ScheduleOccurrences] 的稳定 ID。
 */
class CalendarSyncManager(
    private val context: Context,
    private val repository: ScheduleRepository,
) {

    data class CalendarInfo(
        val id: Long,
        val displayName: String,
        val accountName: String,
        /** CAL_ACCESS_* 常量，供代码侧兜底过滤用 */
        val accessLevel: Int = CalendarContract.Calendars.CAL_ACCESS_NONE,
    )

    /**
     * 一次批量提交的结果。
     *
     * [orphanSuspects] 与 [rolledBack] 是 T265 新加的两本账，它们**不与** inserted/failed 互斥：
     * 同一批课次可以既进 [failed] 又进 [orphanSuspects]（事件已经在用户日历上、我们却没有映射），
     * 也可以既进 [failed] 又进 [rolledBack]（当场把自己刚写的收回来了，所以它没留在日历上）。
     * 少了这两枚，屏上就只能念「N 个日程未写入」—— 而"N 个未写入"里有两种代价完全不同的事。
     */
    data class ApplyResult(
        val inserted: Int,
        val updated: Int,
        val deleted: Int,
        val failed: Int,
        /** 已经进到用户日历、却没有映射可定位的事件枚数（下次回收按所有权标记捞回来） */
        val orphanSuspects: Int = 0,
        /** 映射落库失败那批被当场撤回的枚数 */
        val rolledBack: Int = 0,
    ) {
        val succeeded: Boolean get() = failed == 0
    }

    /**
     * 「移除已同步的日程」那一手的结果。
     *
     * @param orphansReclaimed 其中「映射表里没有、靠所有权标记才捞回来」的枚数（本卡题眼那批）
     * @param copy 屏上该念哪一句（档由 domain 内核定，句子本身住在调用点）
     */
    data class RemoveOutcome(
        val removed: Int,
        val failed: Int,
        val orphansReclaimed: Int,
        val copy: CalendarReclaimPlanner.RemovalCopy,
    )

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /** 可写日历列表（过滤只读与隐藏日历），调用前需已授权 */
    suspend fun queryCalendars(): List<CalendarInfo> = withContext(Dispatchers.IO) {
        CalendarQuery.resolveWritable(
            strict = queryCalendarsOnce(CalendarQuery.writableSelection),
            // 兜底：部分 ROM 的 CalendarProvider 对带 AND 条件的查询返回空集，
            // 或本地账户的可见位没被正确维护。退化为无条件查询、权限等级在代码侧再筛一遍。
            everything = { queryCalendarsOnce(null) },
        )
    }

    /**
     * 单次日历查询。投影与 selection 的契约见 [CalendarQuery]；
     * 下面的列下标按 [CalendarQuery.projection] 的顺序取，改投影顺序要一起改。
     */
    private fun queryCalendarsOnce(selection: String?): List<CalendarInfo> {
        val result = mutableListOf<CalendarInfo>()
        runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                CalendarQuery.projection,
                selection,
                null,
                CalendarQuery.sortOrder,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(
                        CalendarInfo(
                            id = cursor.getLong(0),
                            displayName = cursor.getString(1) ?: "?",
                            accountName = cursor.getString(2) ?: "",
                            accessLevel = cursor.getInt(4),
                        )
                    )
                }
            }
        }.onFailure { Log.w(TAG, "读取日历列表失败", it) }
        return result
    }

    /**
     * 计算当前课表与已同步事件之间的差异。
     * 返回 null 表示当前没有学期/课程可同步。
     */
    suspend fun computeDiff(calendarId: Long): Pair<CalendarSyncPlanner.Diff, Int>? =
        // IO 而不是 Default：这里主体是 Room 读 + 后续的内容摘要计算，
        // Default 的线程数等于 CPU 核数，一旦某个 DAO 调用退化成阻塞读，
        // 会连带拖住进程里所有 CPU 型协程。IO 池按需扩容，对这类混合负载更安全。
        withContext(Dispatchers.IO) {
            val semester = repository.getCurrentSemester() ?: return@withContext null
            if (semester.startLocalDate == null) return@withContext null
            val courses = repository.getDisplayCourses(semester)
            if (courses.isEmpty()) return@withContext null
            // 课次展开会对上千个课次逐个求内容摘要，属于 CPU 工作，不能留在 Main 上
            val build = ScheduleOccurrences.build(semester, courses, repository.getTimeSlots())
            if (build.occurrences.isEmpty()) return@withContext null
            val existing = repository.getCalendarSyncEntriesFor(calendarId)
            CalendarSyncPlanner.compute(build.occurrences, existing) to build.skipped
        }

    /**
     * 批量提交差异（新增/更新/删除），并回写映射表。
     * 部分失败时跳过失败批次继续，结果中如实计数。
     */
    suspend fun apply(
        calendarId: Long,
        diff: CalendarSyncPlanner.Diff,
        reminderMinutes: Int,
    ): ApplyResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        var inserted = 0
        var updated = 0
        var deleted = 0
        var failed = 0
        var orphanSuspects = 0
        var rolledBack = 0

        // 1) 删除（事件删除会级联删除其提醒）
        diff.toDelete.chunked(DELETE_BATCH).forEach { chunk ->
            val ops = ArrayList<ContentProviderOperation>(chunk.size)
            chunk.forEach { mapping ->
                ops.add(
                    ContentProviderOperation
                        .newDelete(eventUri(mapping.calendarEventId))
                        .build()
                )
            }
            if (applyBatch(ops)) {
                deleted += chunk.size
                // 立即落库：事件已经从系统日历删掉了，映射必须同步消失，
                // 否则进程被杀后这条残留映射会让下次同步拿到一个已被删除的 eventId
                flushMappings(emptyList(), chunk.map { it.occurrenceId })
            } else {
                failed += chunk.size
            }
        }

        // 2) 更新（事件字段 + 重写提醒）：按批提交。
        // 提醒 id 用一次 `IN (...)` 查询取回整批，而不是每个事件各查一次 ——
        // 内容变更动辄数百课次，逐事件跨进程 query 会让"批量提交"名不副实。
        diff.toUpdate.chunked(UPDATE_BATCH).forEach { chunk ->
            val reminderIdsByEvent = queryReminderIdsByEvent(chunk.map { it.first.calendarEventId })
            val ops = ArrayList<ContentProviderOperation>(chunk.size * 3)
            chunk.forEach { (mapping, occurrence) ->
                ops.add(
                    ContentProviderOperation
                        .newUpdate(eventUri(mapping.calendarEventId))
                        .withValues(eventValues(calendarId, occurrence))
                        .build()
                )
                reminderIdsByEvent[mapping.calendarEventId].orEmpty().forEach { reminderId ->
                    ops.add(
                        ContentProviderOperation
                            .newDelete(reminderUri(reminderId))
                            .build()
                    )
                }
                ops.add(newReminderInsertOp(knownEventId = mapping.calendarEventId, minutes = reminderMinutes))
            }
            if (applyBatch(ops)) {
                val upserts = chunk.map { (mapping, occurrence) ->
                    mapping.copy(contentHash = occurrence.contentHash, syncedAt = now)
                }
                // 立即落库，理由同删除批次：contentHash 不更新的话，
                // 下次同步会认为这批课次"内容变了"而反复重写事件
                if (flushMappings(upserts, emptyList())) updated += upserts.size
                else failed += chunk.size
            } else {
                failed += chunk.size
            }
        }

        // 3) 新增（事件 + 提醒一起批量提交，事件 ID 用回引关联）
        diff.toInsert.chunked(INSERT_BATCH).forEach { chunk ->
            val ops = ArrayList<ContentProviderOperation>(chunk.size * 2)
            val eventOpIndexes = mutableListOf<Int>()
            chunk.forEach { occurrence ->
                eventOpIndexes += ops.size
                ops.add(
                    ContentProviderOperation
                        .newInsert(CalendarContract.Events.CONTENT_URI)
                        .withValues(eventValues(calendarId, occurrence))
                        .build()
                )
                ops.add(newReminderInsertOp(backReferenceIndex = ops.size - 1, minutes = reminderMinutes))
            }
            val results = runCatching {
                context.contentResolver.applyBatch(CalendarContract.AUTHORITY, ops)
            }
            results.fold(
                onSuccess = { applied ->
                    val named = mutableSetOf<Long>()
                    val upserts = mutableListOf<CalendarSyncEntity>()
                    var unnamed = 0
                    eventOpIndexes.forEachIndexed { i, opIndex ->
                        val eventUriString = applied.getOrNull(opIndex)?.uri?.toString()
                        val eventId = eventUriString?.substringAfterLast('/')?.toLongOrNull()
                        if (eventId != null) {
                            named += eventId
                            upserts += chunk[i].toMapping(calendarId, eventId, now)
                        } else {
                            // 事件已经在日历上了，而我们连它的 id 都点不出来 —— 这一档不猜（猜就是
                            // 拿用户日历上的未知事件当写入目标），改由回收那一手按所有权标记捞回来。
                            unnamed++
                        }
                    }
                    // ⚠️ 新增批次**必须**逐批立刻写映射，这是整个同步最关键的一点：
                    // 事件此刻已经真的进到系统日历里了。若攒到最后统一写，
                    // 中途进程被杀（同步几百课次要十几秒，很容易被系统回收）
                    // 就会留下"日历里有事件、映射表为空"的状态，
                    // 下次同步因为查不到映射而把同样的课次再插一遍 —— 日历里全是重复事件。
                    // T265 把那一窗口剩下的两档收口：落库真失败 ⇒ 当场撤回自己刚写的（乙），
                    // 点不出名的那些 ⇒ 留痕交给按标记回收（甲）。
                    val flush = when {
                        upserts.isEmpty() -> MappingFlush.NotNeeded
                        flushMappings(upserts, emptyList()) -> MappingFlush.Committed
                        else -> MappingFlush.Failed
                    }
                    val aftermath = CalendarReclaimPlanner.aftermathOfInsertBatch(
                        landing = InsertBatchLanding.Landed,
                        attempted = chunk.size,
                        namedEventIds = named,
                        unnamedCount = unnamed,
                        flush = flush,
                    )
                    val rollbackOk = aftermath is InsertAftermath.RollbackOwnWrites &&
                        deleteOwnedEvents(aftermath.eventIds, "映射落库失败后的当场撤回")
                    val tally = CalendarReclaimPlanner.tallyOf(aftermath, rollbackSucceeded = rollbackOk)
                    inserted += tally.inserted
                    failed += tally.failed
                    orphanSuspects += tally.orphanSuspects
                    rolledBack += tally.rolledBack
                    if (tally.orphanSuspects > 0) {
                        Log.w(
                            TAG,
                            "日历新增批次里 ${tally.orphanSuspects} 枚事件留在系统日历而本应用无法定位（" +
                                "点不出 eventId 或撤回失败），下次「移除已同步的日程」会按所有权标记回收",
                        )
                    }
                    if (tally.rolledBack > 0) {
                        Log.i(TAG, "日历新增批次里 ${tally.rolledBack} 枚因映射落库失败被当场撤回，未留在用户日历上")
                    }
                },
                onFailure = {
                    Log.w(TAG, "日历事件新增批次失败（${chunk.size} 条）", it)
                    failed += CalendarReclaimPlanner.tallyOf(
                        CalendarReclaimPlanner.aftermathOfInsertBatch(
                            landing = InsertBatchLanding.Refused,
                            attempted = chunk.size,
                            namedEventIds = emptySet(),
                            unnamedCount = 0,
                            flush = MappingFlush.NotNeeded,
                        ),
                    ).failed
                },
            )
        }

        ApplyResult(inserted, updated, deleted, failed, orphanSuspects, rolledBack)
    }

    /**
     * 把一个批次产生的映射变更立即写库，失败重试一次。
     *
     * @return 是否确实落库。false 时调用方要把这批计入 failed：事件已经在日历里、
     * 映射却没有，下次同步查不到映射就会把同一批课次再插一遍（R5 F-43）。
     * T265 给这一档接了后续动作：新增批次交回 false 时调用方**当场撤回自己刚写的那些**
     * （[deleteOwnedEvents]），撤不动或点不出名的那些升格为 `orphanSuspects`，
     * 由 [removeAllSyncedEvents] 按所有权标记回收。
     */
    private suspend fun flushMappings(
        upserts: List<CalendarSyncEntity>,
        deleteIds: List<String>,
    ): Boolean {
        if (upserts.isEmpty() && deleteIds.isEmpty()) return true
        repeat(2) { attempt ->
            val ok = runCatching {
                repository.applyCalendarSyncMappingChanges(upserts, deleteIds)
            }.isSuccess
            if (ok) return true
            Log.w(
                TAG,
                "同步映射落库失败（第 ${attempt + 1} 次，${upserts.size} 增 / ${deleteIds.size} 删）",
            )
        }
        return false
    }

    /**
     * 移除本应用创建的**全部**日历事件，并且**只清理确认删除成功**的映射。
     *
     * T265 改的是"范围从哪来"：改前那一版只认内存里的映射表（`repository.getCalendarSyncEntries()`），
     * 于是"有事件、无映射"的那两批（eventId 解析不出 / 映射落库失败 / 批写与落库之间进程被杀）
     * 永久留在用户日历上，而下一次同步因为查不到映射会把同批课次再插一遍。
     * 现在先按所有权标记枚 provider（[CalendarOwnershipQuery]），映射表退成**第二来源与加速缓存**：
     * - 标记问得回来 ⇒ 删「标记命中的 ∪ 映射记着的」，孤儿（标记有、映射无）一并捞回；
     * - 标记问不回来（provider 拒 / 没权限没问 / 答了空集而映射却有货）⇒ **只删映射点得出名的那些**，
     *   绝不退成"这个日历里的事件都归我删"，并把这一档交回屏上（[RemoveOutcome.copy]）；
     * - 两本账都空 ⇒ 一枚都不碰（从没同步过的用户点"移除"，他自己的日程不许被动）。
     *
     * 失败的批次必须保留映射：一旦无条件清空，遗留的日历事件在映射丢失后
     * 再也定位不到、无法清理，且下次同步会因为“映射缺失”把同一课次重复插入。
     *
     * @param currentTargetCalendarId 界面上选中的目标日历（-1L = 没选）。只用于**报数**：
     *   映射里落在别的日历上的那些枚数会被折进句子档，不参与、也不允许参与删除范围的收窄。
     * @return 实际删除成功的事件数 + 孤儿账 + 该念哪一句
     */
    suspend fun removeAllSyncedEvents(currentTargetCalendarId: Long): RemoveOutcome = withContext(Dispatchers.IO) {
        val entries = repository.getCalendarSyncEntries()
        val mappingEventIds = entries.mapTo(HashSet()) { it.calendarEventId }
        val attempted = hasPermission()
        val (marker, markerEventIds) = CalendarOwnershipQuery.fold(
            rows = if (attempted) queryOwnedEventIdsOnce() else null,
            attempted = attempted,
        )
        val plan = CalendarReclaimPlanner.planReclaim(marker, markerEventIds, mappingEventIds)
        val eventIds: List<Long> = when (plan) {
            is ReclaimPlan.ByOwnershipMarker -> plan.eventIds.toList()
            is ReclaimPlan.RecordedOnly -> plan.eventIds.toList()
            is ReclaimPlan.NothingOurs -> emptyList()
        }.sorted()
        Log.i(
            TAG,
            "日历回收起手：标记枚举=$marker（命中 ${markerEventIds.size} 枚）、映射记着 ${mappingEventIds.size} 枚 " +
                "⇒ 本次要删 ${eventIds.size} 枚（其中 " +
                "${if (plan is ReclaimPlan.ByOwnershipMarker) plan.orphansBeyondMappings else 0} 枚是映射外的孤儿）",
        )
        var removed = 0
        var failed = 0
        val deletedEventIds = HashSet<Long>()
        eventIds.chunked(DELETE_BATCH).forEach { chunk ->
            val ops = ArrayList<ContentProviderOperation>(chunk.size)
            chunk.forEach { eventId -> ops.add(ContentProviderOperation.newDelete(eventUri(eventId)).build()) }
            if (applyBatch(ops)) {
                removed += chunk.size
                deletedEventIds += chunk
            } else {
                failed += chunk.size
                Log.w(TAG, "日历事件删除批次失败（${chunk.size} 条），保留其映射以便下次重试")
            }
        }
        // 只清真删掉的那些映射（既有口径：失败批次保留映射以便下次重试）
        val prunedOccurrenceIds = entries.filter { it.calendarEventId in deletedEventIds }.map { it.occurrenceId }
        repository.removeCalendarSyncEntries(prunedOccurrenceIds)
        val orphansReclaimed = deletedEventIds.count { it !in mappingEventIds }
        if (plan is ReclaimPlan.RecordedOnly) {
            Log.i(TAG, "日历回收按标记读不回来（${plan.reason}）：本次只删了映射记着的 $removed 枚，可能有孤儿留在用户日历上")
        }
        RemoveOutcome(
            removed = removed,
            failed = failed,
            orphansReclaimed = orphansReclaimed,
            copy = CalendarReclaimPlanner.removalCopy(
                plan = plan,
                deletedCount = removed,
                failedCount = failed,
                mappingsOutsideCurrentTarget = entries.count { it.calendarId != currentTargetCalendarId },
            ),
        )
    }

    /**
     * 单次按所有权标记枚事件（契约与"刻意没有兜底第二遍"那三条见 [CalendarOwnershipQuery]）。
     *
     * 交回 null 表示"这一次没问到答案"（异常，或 cursor 都没给），**不是**"答了零枚"——
     * 两者在 [CalendarOwnershipQuery.fold] 里是两档，而两档都不许扩大删除范围。
     */
    private fun queryOwnedEventIdsOnce(): List<Long>? = runCatching {
        val ids = mutableListOf<Long>()
        val cursor = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            CalendarOwnershipQuery.projection,
            CalendarOwnershipQuery.selection,
            CalendarOwnershipQuery.selectionArgs(context.packageName),
            null,
        ) ?: error("Events 按所有权标记查询交回 null cursor")
        cursor.use { while (it.moveToNext()) ids += it.getLong(0) }
        ids
    }.onFailure { Log.w(TAG, "按所有权标记枚举本应用创建的日历事件失败（本次只按映射表回收）", it) }.getOrNull()

    /**
     * 删掉点得出名的若干枚事件（回收那一手与"映射落库失败后当场撤回自己刚写的"共用这一把刀）。
     *
     * @return 是否**每一批**都删成了。false 时调用方要把这批升格成孤儿嫌疑 ——
     *   撤回失败的撤回不许念成撤回成功（分档见 domain 内核 `CalendarReclaimPlanner.tallyOf`）。
     */
    private fun deleteOwnedEvents(eventIds: Set<Long>, reason: String): Boolean {
        if (eventIds.isEmpty()) return true
        var allSucceeded = true
        eventIds.chunked(DELETE_BATCH).forEach { chunk ->
            val ops = ArrayList<ContentProviderOperation>(chunk.size)
            chunk.forEach { eventId -> ops.add(ContentProviderOperation.newDelete(eventUri(eventId)).build()) }
            if (!applyBatch(ops)) allSucceeded = false
        }
        if (!allSucceeded) {
            Log.w(TAG, "$reason：${eventIds.size} 枚里有一批删除失败，这些事件留在用户日历上等待按标记回收")
        }
        return allSucceeded
    }

    private fun eventValues(calendarId: Long, occurrence: com.buaa.schedule.domain.schedule.Occurrence): ContentValues =
        ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, occurrence.title)
            put(CalendarContract.Events.DESCRIPTION, occurrence.description)
            occurrence.course.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            put(
                CalendarContract.Events.DTSTART,
                occurrence.date.atTime(occurrence.start)
                    .atZone(TimeZone.getDefault().toZoneId()).toInstant().toEpochMilli()
            )
            put(
                CalendarContract.Events.DTEND,
                occurrence.date.atTime(occurrence.end)
                    .atZone(TimeZone.getDefault().toZoneId()).toInstant().toEpochMilli()
            )
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_BUSY)
            // 所有权标记：这一枚列**不是装饰**。回收那一手（removeAllSyncedEvents →
            // CalendarOwnershipQuery）按它等值枚回"本应用创建的那些"，所以写它一处、读它一处，
            // 缺一处屏上那句「将删除本应用创建的全部日历事件」就兑不了（T265，台账 #372）。
            // 只写不读 = 本卡病灶；只读不写 = 一台机器上再没有新事件带标记，回收从此永远枚不到东西。
            put(CalendarContract.Events.CUSTOM_APP_PACKAGE, context.packageName)
        }

    /** 提醒插入操作：knownEventId 直接引用已有事件，或用 backReferenceIndex 回引同批次事件插入结果 */
    private fun newReminderInsertOp(knownEventId: Long? = null, backReferenceIndex: Int? = null, minutes: Int): ContentProviderOperation {
        val builder = ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
            .withValue(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            .withValue(CalendarContract.Reminders.MINUTES, minutes)
        return if (knownEventId != null) {
            builder.withValue(CalendarContract.Reminders.EVENT_ID, knownEventId).build()
        } else {
            builder.withValueBackReference(CalendarContract.Reminders.EVENT_ID, backReferenceIndex!!).build()
        }
    }

    /**
     * 批量取回一批事件的提醒 id（`event_id IN (?, ?, …)`）。
     * 一次查询覆盖整批，避免"更新数百课次 → 数百次跨进程 query"。
     */
    private fun queryReminderIdsByEvent(eventIds: List<Long>): Map<Long, List<Long>> {
        if (eventIds.isEmpty()) return emptyMap()
        val result = mutableMapOf<Long, MutableList<Long>>()
        runCatching {
            val selection = "${CalendarContract.Reminders.EVENT_ID} IN (" +
                eventIds.joinToString(",") { "?" } + ")"
            context.contentResolver.query(
                CalendarContract.Reminders.CONTENT_URI,
                arrayOf(CalendarContract.Reminders._ID, CalendarContract.Reminders.EVENT_ID),
                selection,
                eventIds.map { it.toString() }.toTypedArray(),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val reminderId = cursor.getLong(0)
                    val eventId = cursor.getLong(1)
                    result.getOrPut(eventId) { mutableListOf() } += reminderId
                }
            }
        }.onFailure { Log.w(TAG, "批量读取事件提醒失败（${eventIds.size} 个事件）", it) }
        return result
    }

    private fun applyBatch(ops: ArrayList<ContentProviderOperation>): Boolean =
        runCatching {
            context.contentResolver.applyBatch(CalendarContract.AUTHORITY, ops)
            true
        }.fold({ true }, { e ->
            Log.w(TAG, "日历批次操作失败（${ops.size} 个操作）", e)
            false
        })

    private fun eventUri(eventId: Long) =
        CalendarContract.Events.CONTENT_URI.buildUpon().appendPath(eventId.toString()).build()

    private fun reminderUri(reminderId: Long) =
        CalendarContract.Reminders.CONTENT_URI.buildUpon().appendPath(reminderId.toString()).build()

    private fun com.buaa.schedule.domain.schedule.Occurrence.toMapping(
        calendarId: Long,
        eventId: Long,
        now: Long,
    ) = CalendarSyncEntity(
        occurrenceId = stableId,
        // 共用 ScheduleOccurrences.courseStableId（可读前缀 + 8 位摘要）：
        // 这里此前自己 `take(80)` 截断，没有摘要后缀——前缀相同、差异落在
        // 截断点之后的两门课会撞成同一个 ID（stableIdFor 加摘要正是防这个）。
        courseStableId = com.buaa.schedule.domain.schedule.ScheduleOccurrences.courseStableId(course),
        occurrenceDate = date.toString(),
        calendarId = calendarId,
        calendarEventId = eventId,
        contentHash = contentHash,
        syncedAt = now,
    )

    private companion object {
        private const val TAG = "CalendarSyncManager"
        private const val INSERT_BATCH = 50
        private const val DELETE_BATCH = 50
        private const val UPDATE_BATCH = 50
    }
}
