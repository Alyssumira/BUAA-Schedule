package com.buaa.schedule.data.backup

import com.buaa.schedule.domain.schedule.CourseConstraints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份恢复的**输入边界**。
 *
 * 备份文件是用户可控输入（可被改坏、可被构造），v1 的 `startSection..endSection`
 * 在 `toDomain()` 里会被展开成 `periods`。展开前若不限幅，
 * `"startSection": 1, "endSection": 2000000000` 会一次性分配 20 亿个 Int 直接 OOM。
 */
class BackupCourseBoundsTest {

    private fun v1Course(startSection: Int?, endSection: Int?) = BackupCourse(
        name = "高等数学",
        dayOfWeek = 1,
        weeks = listOf(1, 2, 3),
        startSection = startSection,
        endSection = endSection,
    )

    @Test
    fun hugeEndSectionIsClampedToMaxPeriod() {
        val course = v1Course(1, Int.MAX_VALUE).toDomain()
        assertEquals(CourseConstraints.MAX_PERIOD, course.periods.size)
        assertEquals(1, course.periods.first())
        assertEquals(CourseConstraints.MAX_PERIOD, course.periods.last())
    }

    @Test
    fun hugeStartAndEndCollapseToASinglePeriod() {
        // 两端都被夹到 MAX_PERIOD，得到单元素区间而不是空列表/崩溃
        val course = v1Course(2_000_000_000, 2_000_000_000).toDomain()
        assertEquals(listOf(CourseConstraints.MAX_PERIOD), course.periods)
    }

    @Test
    fun negativeStartIsClampedToPeriodOne() {
        val course = v1Course(-5, 4).toDomain()
        assertEquals(listOf(1, 2, 3, 4), course.periods)
    }

    @Test
    fun invertedV1SectionYieldsEmptyPeriods() {
        // start 夹到 1..MAX 后仍大于 end：结果是空区间，不抛异常
        val course = v1Course(20, 2).toDomain()
        assertTrue(course.periods.isEmpty())
    }

    @Test
    fun missingEndSectionFallsBackToStartSection() {
        val course = v1Course(3, null).toDomain()
        assertEquals(listOf(3), course.periods)
    }

    @Test
    fun v2PeriodsAreFilteredToLegalRange() {
        val course = BackupCourse(
            name = "大学物理",
            dayOfWeek = 2,
            weeks = listOf(1),
            periods = listOf(0, 1, 2, 99, -3, 2),
        ).toDomain()
        // 非法值剔除、重复值去重、结果有序
        assertEquals(listOf(1, 2), course.periods)
    }

    @Test
    fun v2PeriodsTakePrecedenceOverV1Sections() {
        val course = BackupCourse(
            name = "线性代数",
            dayOfWeek = 3,
            weeks = listOf(1),
            periods = listOf(5, 6),
            startSection = 1,
            endSection = Int.MAX_VALUE,
        ).toDomain()
        // periods 非空 → 走 v2 分支，v1 字段完全不参与，不受脏 endSection 影响
        assertEquals(listOf(5, 6), course.periods)
    }

    @Test
    fun noPeriodsAtAllYieldsEmptyRatherThanCrashing() {
        val course = BackupCourse(
            name = "空课",
            dayOfWeek = 1,
            weeks = listOf(1),
        ).toDomain()
        assertTrue(course.periods.isEmpty())
    }
}
