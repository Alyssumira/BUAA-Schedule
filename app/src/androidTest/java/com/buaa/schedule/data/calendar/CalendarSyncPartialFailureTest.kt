package com.buaa.schedule.data.calendar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buaa.schedule.data.local.AppDatabase
import com.buaa.schedule.data.local.CalendarSyncEntity
import com.buaa.schedule.data.repository.ScheduleRepository
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.schedule.Occurrence
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalTime

/**
 * 日历同步的**部分失败不变量**（需设备）：
 *
 * 1. **映射数量必须与真正落到系统日历里的事件一致** —— 事件已经写进日历却没记映射，
 *    下次同步会因为查不到映射把同一课次再插一遍，日历里全是重复事件；
 *    反过来记了映射但事件没写进去，事件就永远删不掉了。
 * 2. **批次失败时既有映射必须保留** —— 无条件清空映射会让遗留事件再也定位不到。
 */
@RunWith(AndroidJUnit4::class)
class CalendarSyncPartialFailureTest {

    /** 不存在的日历 ID：写入必然被 Calendar Provider 拒绝，用来稳定地造出"失败批次" */
    private companion object {
        const val BOGUS_CALENDAR_ID = -1L
    }

    private lateinit var db: AppDatabase
    private lateinit var repository: ScheduleRepository
    private lateinit var manager: CalendarSyncManager

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = AppDatabase.buildForTest(context)
        repository = ScheduleRepository(db)
        manager = CalendarSyncManager(context, repository)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun occurrence(stableId: String) = Occurrence(
        course = Course(
            name = "高等数学",
            teacher = "张三",
            location = "J3-101",
            dayOfWeek = 1,
            periods = listOf(1, 2),
            weeks = listOf(1),
        ),
        date = LocalDate.of(2026, 9, 7),
        start = LocalTime.of(8, 0),
        end = LocalTime.of(9, 35),
        segment = 1..2,
        stableId = stableId,
        contentHash = "hash-$stableId",
    )

    private fun mapping(occurrenceId: String) = CalendarSyncEntity(
        occurrenceId = occurrenceId,
        courseStableId = "course-$occurrenceId",
        occurrenceDate = "2026-09-07",
        calendarId = BOGUS_CALENDAR_ID,
        calendarEventId = 42L,
        contentHash = "hash-$occurrenceId",
        syncedAt = 1L,
    )

    @Test
    fun mappingCountStaysConsistentWithInsertedCount() = runBlocking {
        val occurrences = listOf(occurrence("occ-1"), occurrence("occ-2"))
        val diff = CalendarSyncPlanner.Diff(
            toInsert = occurrences,
            toUpdate = emptyList(),
            toDelete = emptyList(),
            unchangedCount = 0,
        )

        val result = manager.apply(BOGUS_CALENDAR_ID, diff, 10)
        val mappings = repository.getCalendarSyncEntriesFor(BOGUS_CALENDAR_ID)

        assertEquals(
            "映射条数必须等于真正插入成功的事件数，" +
                "否则下次同步会重复插入或留下无法清理的孤儿事件",
            result.inserted,
            mappings.size,
        )
        assertTrue("失败与成功之和应覆盖全部待插入课次", result.inserted + result.failed == 2)
    }

    @Test
    fun failedSyncKeepsExistingMappings() = runBlocking {
        // 模拟上一轮同步已经成功写入的事件映射
        repository.applyCalendarSyncMappingChanges(
            upserts = listOf(mapping("occ-1"), mapping("occ-2")),
            deleteIds = emptyList(),
        )

        // 本轮同步里这两条课次内容没变（unchanged），但同一批次的删除全部失败
        val diff = CalendarSyncPlanner.Diff(
            toInsert = emptyList(),
            toUpdate = emptyList(),
            toDelete = listOf(mapping("occ-3")),
            unchangedCount = 2,
        )
        manager.apply(BOGUS_CALENDAR_ID, diff, 10)

        val remaining = repository.getCalendarSyncEntriesFor(BOGUS_CALENDAR_ID)
        assertEquals(
            "批次失败时既有映射必须保留，否则遗留事件再也定位不到",
            setOf("occ-1", "occ-2"),
            remaining.map { it.occurrenceId }.toSet(),
        )
    }

    @Test
    fun updatedBatchFailureDoesNotAdvanceContentHash() = runBlocking {
        repository.applyCalendarSyncMappingChanges(
            upserts = listOf(mapping("occ-1").copy(contentHash = "stale")),
            deleteIds = emptyList(),
        )

        val diff = CalendarSyncPlanner.Diff(
            toInsert = emptyList(),
            toUpdate = listOf(mapping("occ-1").copy(contentHash = "stale") to occurrence("occ-1")),
            toDelete = emptyList(),
            unchangedCount = 0,
        )
        manager.apply(BOGUS_CALENDAR_ID, diff, 10)

        val stored = repository.getCalendarSyncEntriesFor(BOGUS_CALENDAR_ID).single { it.occurrenceId == "occ-1" }
        assertEquals(
            "更新失败时不能把 contentHash 推进，否则下次同步会认为内容已同步而跳过",
            "stale",
            stored.contentHash,
        )
    }

    @Test
    fun computeDiffIsNullWhenThereIsNothingToSync() = runBlocking {
        // 内存库里没有任何课程 → 没有课次可展开
        assert(manager.computeDiff(BOGUS_CALENDAR_ID) == null)
    }
}
