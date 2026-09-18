package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.TimeSlotProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleOccurrencesTest {

    private val semester = Semester(
        termCode = "T",
        termName = "T",
        startDate = "2026-09-07",
        totalWeeks = 19,
    )

    private fun course(
        location: String? = "J3-101",
        periods: List<Int> = listOf(1, 2),
        weeks: List<Int> = listOf(1, 3),
    ) = Course(
        name = "高数",
        teacher = "张三",
        location = location,
        dayOfWeek = 1,
        periods = periods,
        weeks = weeks,
    )

    @Test
    fun stableIdAndHashAreDeterministic() {
        val first = ScheduleOccurrences.build(semester, listOf(course()), emptyList())
        val second = ScheduleOccurrences.build(semester, listOf(course()), emptyList())

        assertEquals(first.occurrences.map { it.stableId }, second.occurrences.map { it.stableId })
        assertEquals(first.occurrences.map { it.contentHash }, second.occurrences.map { it.contentHash })
        assertEquals(2, first.occurrences.size)
        assertEquals(0, first.skipped)
    }

    @Test
    fun contentHashChangesWhenCourseContentChanges() {
        val original = ScheduleOccurrences.build(semester, listOf(course()), emptyList())
        val moved = ScheduleOccurrences.build(semester, listOf(course(location = "J5-202")), emptyList())

        assertEquals(original.occurrences.map { it.stableId }, moved.occurrences.map { it.stableId })
        assertNotEquals(original.occurrences.map { it.contentHash }, moved.occurrences.map { it.contentHash })
    }

    @Test
    fun occurrenceDatesFollowWeekAndDay() {
        // 第 1 周周一、第 3 周周一
        val build = ScheduleOccurrences.build(semester, listOf(course()), emptyList())
        val dates = build.occurrences.map { it.date.toString() }

        assertEquals(listOf("2026-09-07", "2026-09-21"), dates)
    }

    @Test
    fun missingSlotTimeIsSkippedAndCounted() {
        val slots = listOf(TimeSlot(number = 1, startTime = "08:00", endTime = "08:45"))
        val build = ScheduleOccurrences.build(
            semester,
            listOf(course(periods = listOf(1, 9), weeks = listOf(1))),
            slots,
        )

        // 第 1 节可用、第 9 节缺时间 → 1 个课次 + 1 个跳过
        assertEquals(1, build.occurrences.size)
        assertEquals(1, build.skipped)
    }

    @Test
    fun invalidSemesterStartYieldsNothing() {
        val build = ScheduleOccurrences.build(
            semester.copy(startDate = "bad"),
            listOf(course()),
            emptyList(),
        )
        assertTrue(build.occurrences.isEmpty())
    }

    @Test
    fun stableIdDoesNotCollideWhenIdentityDiffersOnlyAfterTruncation() {
        // 两门课的身份键前缀完全相同、差异落在被截断的部分之后。
        // 此前实现直接 take(80)，两者会得到同一个 UID（日历映射互相覆盖）；
        // 现在拼了内容摘要，必须能区分开。
        val longName = "超长课程名称".repeat(12)
        val first = course(weeks = listOf(1)).copy(name = longName + "甲")
        val second = course(weeks = listOf(1)).copy(name = longName + "乙")

        val firstId = ScheduleOccurrences.stableIdFor(first, week = 1, segment = 1..2)
        val secondId = ScheduleOccurrences.stableIdFor(second, week = 1, segment = 1..2)

        assertNotEquals(firstId, secondId)
        // 同一门课重复生成必须稳定
        assertEquals(secondId, ScheduleOccurrences.stableIdFor(second, week = 1, segment = 1..2))
    }

    // —— P2-5：别名要真的上得了日历 ————————————————————————————————

    @Test
    fun titlePrefersAlias() {
        // course() 默认跨两周 → 两个课次，所以这里比"去重后的标题"而不是个数
        val withAlias = ScheduleOccurrences.build(
            semester,
            listOf(course().copy(alias = "数学分析")),
            emptyList(),
        )
        assertEquals(listOf("数学分析"), withAlias.occurrences.map { it.title }.distinct())

        val blankAlias = ScheduleOccurrences.build(
            semester,
            listOf(course().copy(alias = "   ")),
            emptyList(),
        )
        // 空白别名回退教务原名，不是回退成空字符串
        assertEquals(listOf("高数"), blankAlias.occurrences.map { it.title }.distinct())
    }

    @Test
    fun aliasChangeUpdatesContentHashButNotIdentity() {
        // 摘要不含别名 → CalendarSyncPlanner 判定 unchanged → 日历上的旧标题永远刷不掉
        val before = ScheduleOccurrences.build(semester, listOf(course()), emptyList())
        val after = ScheduleOccurrences.build(
            semester,
            listOf(course().copy(alias = "数学分析")),
            emptyList(),
        )
        assertNotEquals(
            before.occurrences.map { it.contentHash },
            after.occurrences.map { it.contentHash },
        )
        // 但身份键刻意不含名称：改别名走 update，不能删旧建新
        // （删旧建新会连带丢掉用户在日历 App 里给事件加的备注）
        assertEquals(
            before.occurrences.map { it.stableId },
            after.occurrences.map { it.stableId },
        )
    }

    @Test
    fun descriptionNamesOnlyThisSegment() {
        // [5,6] 隔着午休会展开成两个课次，各自的描述只能写自己那一段
        val build = ScheduleOccurrences.build(
            semester,
            listOf(course(periods = listOf(5, 6), weeks = listOf(1))),
            TimeSlotProfile.DEFAULT,
        )
        assertEquals(2, build.occurrences.size)
        assertEquals(
            listOf("第5节", "第6节"),
            build.occurrences.map { it.description.substringAfter("教师: 张三 · ").substringBefore(" · ") },
        )
    }
}
