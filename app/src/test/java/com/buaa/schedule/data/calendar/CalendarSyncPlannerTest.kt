package com.buaa.schedule.data.calendar

import com.buaa.schedule.data.local.CalendarSyncEntity
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.schedule.Occurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class CalendarSyncPlannerTest {

    private fun occurrence(stableId: String, contentHash: String = "hash") = Occurrence(
        course = Course(name = "高数", dayOfWeek = 1, periods = listOf(1, 2), weeks = listOf(1)),
        date = LocalDate.of(2026, 9, 7),
        start = LocalTime.of(8, 0),
        end = LocalTime.of(9, 35),
        segment = 1..2,
        stableId = stableId,
        contentHash = contentHash,
    )

    private fun mapping(occurrenceId: String, contentHash: String = "hash", eventId: Long = 1) =
        CalendarSyncEntity(
            occurrenceId = occurrenceId,
            courseStableId = "course",
            occurrenceDate = "2026-09-07",
            calendarId = 5,
            calendarEventId = eventId,
            contentHash = contentHash,
            syncedAt = 0,
        )

    @Test
    fun classifiesInsertUpdateDeleteAndUnchanged() {
        val desired = listOf(
            occurrence("a"),                        // 不变
            occurrence("b", contentHash = "new"),   // 内容变化 → 更新
            occurrence("c"),                        // 新课次 → 新增
        )
        val existing = listOf(
            mapping("a", "hash", eventId = 11),
            mapping("b", "old", eventId = 12),
            mapping("d", "hash", eventId = 13),     // 已不存在 → 删除
        )

        val diff = CalendarSyncPlanner.compute(desired, existing)

        assertEquals(listOf("c"), diff.toInsert.map { it.stableId })
        assertEquals(listOf("b" to 12L), diff.toUpdate.map { it.first.occurrenceId to it.first.calendarEventId })
        assertEquals(listOf("d"), diff.toDelete.map { it.occurrenceId })
        assertEquals(1, diff.unchangedCount)
        assertEquals(3, diff.totalChanged)
    }

    @Test
    fun emptyExistingMeansAllInserts() {
        val diff = CalendarSyncPlanner.compute(
            listOf(occurrence("a"), occurrence("b")),
            emptyList(),
        )
        assertEquals(2, diff.toInsert.size)
        assertTrue(diff.toUpdate.isEmpty())
        assertTrue(diff.toDelete.isEmpty())
    }

    @Test
    fun emptyDesiredMeansAllDeletes() {
        val diff = CalendarSyncPlanner.compute(
            emptyList(),
            listOf(mapping("a"), mapping("b")),
        )
        assertEquals(2, diff.toDelete.size)
        assertTrue(diff.toInsert.isEmpty())
    }

    @Test
    fun noChangesYieldsEmptyDiff() {
        val diff = CalendarSyncPlanner.compute(
            listOf(occurrence("a")),
            listOf(mapping("a", "hash")),
        )
        assertTrue(diff.isEmpty)
        assertEquals(0, diff.totalChanged)
    }
}
