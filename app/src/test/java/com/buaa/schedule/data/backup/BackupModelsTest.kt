package com.buaa.schedule.data.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupModelsTest {

    @Test
    fun v1BackupWithLegacySectionsDerivesPeriods() {
        // v1 备份只有 startSection/endSection，没有 periods 字段
        val v1Course = BackupCourse(
            name = "高等数学",
            teacher = "张三",
            location = "J3-101",
            dayOfWeek = 1,
            weeks = (1..16).toList(),
            startSection = 2,
            endSection = 4,
        )

        val domain = v1Course.toDomain()

        assertEquals(listOf(2, 3, 4), domain.periods)
        assertEquals(2, domain.startPeriod)
        assertEquals(4, domain.endPeriod)
    }

    @Test
    fun v2BackupPeriodsTakePrecedence() {
        val v2Course = BackupCourse(
            name = "算法",
            dayOfWeek = 3,
            periods = listOf(1, 2, 9, 10),
            weeks = listOf(1),
            startSection = 7, // 旧字段存在也不应影响
            endSection = 8,
        )

        val domain = v2Course.toDomain()

        assertEquals(listOf(1, 2, 9, 10), domain.periods)
    }

    @Test
    fun legacySingleSectionDerivesSinglePeriod() {
        val legacy = BackupCourse(
            name = "讲座",
            dayOfWeek = 5,
            weeks = listOf(1),
            startSection = 6,
            endSection = null,
        )

        assertEquals(listOf(6), legacy.toDomain().periods)
    }
}
