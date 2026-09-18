package com.buaa.schedule.widget

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 一轮快照 sync 的读次数（审计 §2.4：`getTimeSlots()` 是循环不变量，原先却写在
 * `keys.forEach` 里，N = 学期数 + 1 时一轮多查 N-1 遍同一张节次表）。
 *
 * 为什么测 [WidgetDataSynchronizer.planSnapshotRows] 而不是真的跑
 * [WidgetDataSynchronizer.sync]：sync 要 Context + Room + 注入的 repository，而
 * `:app` 的 JVM 测试里既没有 Robolectric 也没有 Room（`testImplementation` 只有 junit），
 * 库起不来。这条改动的风险面恰好只有"同一张表查了几遍、每 key 的课程查了几遍"，
 * 遍数就是那两个注入读参数被调用的次数，所以钉住次数等于钉住口径。
 */
class WidgetSnapshotPlanTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val timeSlots = listOf(
        TimeSlot(number = 1, startTime = "08:00", endTime = "08:45"),
        TimeSlot(number = 2, startTime = "08:55", endTime = "09:40"),
    )

    private fun semester(code: String) = Semester(
        termCode = code,
        termName = code,
        startDate = "2026-09-07",
        totalWeeks = 16,
    )

    private fun course(name: String) = Course(
        name = name,
        dayOfWeek = 1,
        periods = listOf(1),
        weeks = listOf(1),
    )

    /** 两个读的调用记录：节次表记次数，课程记每次拿到的是哪个学期 */
    private class Reads {
        var timeSlotsCalls = 0
        val coursesQueries = mutableListOf<String?>()
    }

    private fun keysOf(semesters: List<Semester>): Set<String> =
        LinkedHashSet<String>().apply {
            add("current")
            semesters.forEach { add(it.termCode) }
        }

    @Test
    fun `节次表按整轮查一次，与 key 个数无关`() = runBlocking {
        for (termCount in 1..6) {
            val semesters = (1..termCount).map { semester("2026-2027-$it") }
            val keys = keysOf(semesters)
            val reads = Reads()

            val rows = WidgetDataSynchronizer.planSnapshotRows(
                keys = keys,
                semesters = semesters,
                current = semesters.first(),
                getTimeSlots = {
                    reads.timeSlotsCalls += 1
                    timeSlots
                },
                getDisplayCourses = { semester ->
                    reads.coursesQueries += semester?.termCode
                    emptyList()
                },
            )

            assertEquals("keys=${keys.size} 时节次表被查了几遍", 1, reads.timeSlotsCalls)
            assertEquals("每 key 仍各查一次课程", keys.size, reads.coursesQueries.size)
            assertEquals("一行 key 一行快照", keys.size, rows.size)
        }
    }

    @Test
    fun `current 跟当前学期，其余 key 跟自己的学期`() = runBlocking {
        val current = semester("2026-2027-1")
        val past = semester("2025-2026-2")
        val reads = Reads()

        val rows = WidgetDataSynchronizer.planSnapshotRows(
            keys = keysOf(listOf(current, past)),
            semesters = listOf(current, past),
            current = current,
            getTimeSlots = { reads.timeSlotsCalls += 1; timeSlots },
            getDisplayCourses = { semester ->
                reads.coursesQueries += semester?.termCode
                listOf(course(semester?.termCode ?: "全部"))
            },
        )

        assertEquals(1, reads.timeSlotsCalls)
        // 顺序跟着 keys 走：current 先落到当前学期，再各查自己的学期
        assertEquals(listOf("2026-2027-1", "2026-2027-1", "2025-2026-2"), reads.coursesQueries)
        val snapshotKeys = rows.map { it.key }
        assertEquals(listOf("current", "2026-2027-1", "2025-2026-2"), snapshotKeys)

        rows.forEach { row ->
            val snapshot = json.decodeFromString<WidgetSnapshotDto>(row.dataJson).toWidgetData()
            // "current" 这一行写的是当前学期，而不是 semester == null 的那一行
            val expectedTerm = if (row.key == "current") current.termCode else row.key
            assertEquals(expectedTerm, snapshot.semester?.termCode)
            assertEquals(listOf(expectedTerm), snapshot.courses.map { it.name })
            // 一次读取的节次表要原样进到每一行，提出循环不能改变行内容
            assertEquals(timeSlots, snapshot.timeSlots)
        }
    }

    @Test
    fun `给了几个 key 就出几行，semesters 认不出来的那行学期为空`() = runBlocking {
        // sync 的 keys 恒由 semesters 推出来，所以"keys 里有、semesters 里没有"今天走不到。
        // 钉住的是行集不变式 rows.keys == keys：deleteKeysNotIn(keys) 拿同一个集合当保留名单，
        // 排产少出一行，那一行就同时不被写、又被删，读侧只能回退主库。
        val known = semester("2026-2027-1")
        val reads = Reads()

        val rows = WidgetDataSynchronizer.planSnapshotRows(
            keys = linkedSetOf("current", known.termCode, "2024-2025-1"),
            semesters = listOf(known),
            current = known,
            getTimeSlots = { reads.timeSlotsCalls += 1; timeSlots },
            getDisplayCourses = { semester ->
                reads.coursesQueries += semester?.termCode
                emptyList()
            },
        )

        assertEquals(1, reads.timeSlotsCalls)
        assertEquals(listOf("2026-2027-1", "2026-2027-1", null), reads.coursesQueries)
        assertEquals(
            "2024-2025-1 不在 semesters 里，也必须有一行快照",
            listOf("current", "2026-2027-1", "2024-2025-1"),
            rows.map { it.key },
        )
        assertNull(
            json.decodeFromString<WidgetSnapshotDto>(rows.last().dataJson).toWidgetData().semester
        )
    }
}
