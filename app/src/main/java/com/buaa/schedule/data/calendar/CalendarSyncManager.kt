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

    data class ApplyResult(
        val inserted: Int,
        val updated: Int,
        val deleted: Int,
        val failed: Int,
    ) {
        val succeeded: Boolean get() = failed == 0
    }

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
                    val upserts = buildList {
                        eventOpIndexes.forEachIndexed { i, opIndex ->
                            val eventUriString = applied.getOrNull(opIndex)?.uri?.toString()
                            val eventId = eventUriString?.substringAfterLast('/')?.toLongOrNull()
                            val occurrence = chunk[i]
                            if (eventId != null) {
                                add(occurrence.toMapping(calendarId, eventId, now))
                            } else {
                                failed++
                            }
                        }
                    }
                    // ⚠️ 新增批次**必须**逐批立刻写映射，这是整个同步最关键的一点：
                    // 事件此刻已经真的进到系统日历里了。若攒到最后统一写，
                    // 中途进程被杀（同步几百课次要十几秒，很容易被系统回收）
                    // 就会留下"日历里有事件、映射表为空"的状态，
                    // 下次同步因为查不到映射而把同样的课次再插一遍 —— 日历里全是重复事件。
                    if (flushMappings(upserts, emptyList())) inserted += upserts.size
                    else failed += upserts.size
                },
                onFailure = {
                    Log.w(TAG, "日历事件新增批次失败（${chunk.size} 条）", it)
                    failed += chunk.size
                },
            )
        }

        ApplyResult(inserted, updated, deleted, failed)
    }

    /**
     * 把一个批次产生的映射变更立即写库，失败重试一次。
     *
     * @return 是否确实落库。false 时调用方要把这批计入 failed：事件已经在日历里、
     * 映射却没有，下次同步查不到映射就会把同一批课次再插一遍（R5 F-43）。
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
     * 移除本应用创建的全部日历事件，并且**只清理确认删除成功**的映射。
     *
     * 失败的批次必须保留映射：一旦无条件清空，遗留的日历事件在映射丢失后
     * 再也定位不到、无法清理，且下次同步会因为“映射缺失”把同一课次重复插入。
     *
     * @return 实际删除成功的事件数
     */
    suspend fun removeAllSyncedEvents(): Int = withContext(Dispatchers.IO) {
        val entries = repository.getCalendarSyncEntries()
        var removed = 0
        val removedOccurrenceIds = mutableListOf<String>()
        entries.chunked(DELETE_BATCH).forEach { chunk ->
            val ops = ArrayList<ContentProviderOperation>(chunk.size)
            chunk.forEach { mapping ->
                ops.add(
                    ContentProviderOperation
                        .newDelete(eventUri(mapping.calendarEventId))
                        .build()
                )
            }
            if (applyBatch(ops)) {
                removed += chunk.size
                removedOccurrenceIds += chunk.map { it.occurrenceId }
            } else {
                Log.w(TAG, "日历事件删除批次失败（${chunk.size} 条），保留其映射以便下次重试")
            }
        }
        repository.removeCalendarSyncEntries(removedOccurrenceIds)
        removed
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
        courseStableId = com.buaa.schedule.domain.schedule.ScheduleOccurrences.courseIdentityKey(course)
            .replace(Regex("[^\\w\\u4e00-\\u9fa5-]"), "-")
            .take(80),
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
