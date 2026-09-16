package com.buaa.schedule.domain.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 智慧节次推算测试。
 *
 * 推算结果直接变成节次时间表（影响当前课高亮/提醒/组件），边界与午休规则不能错。
 */
class SmartPeriodsTest {

    @Test
    fun derivesStandardMorning() {
        val result = SmartPeriods.derive(
            SmartPeriods.Params(
                firstStart = "08:00", periodMinutes = 45, breakMinutes = 10,
                lunchAfterPeriod = 0, lunchMinutes = 0, count = 4,
            ),
        )
        val slots = result.getOrThrow()
        assertEquals(4, slots.size)
        assertEquals("08:00", slots[0].startTime)
        assertEquals("08:45", slots[0].endTime)
        assertEquals("08:55", slots[1].startTime)   // +10 分钟休息
        assertEquals("09:40", slots[1].endTime)
        assertEquals("10:45", slots[3].startTime)
        assertEquals("11:30", slots[3].endTime)
    }

    @Test
    fun lunchBreakInsertsAfterConfiguredPeriod() {
        val result = SmartPeriods.derive(
            SmartPeriods.Params(
                firstStart = "08:00", periodMinutes = 45, breakMinutes = 10,
                lunchAfterPeriod = 4, lunchMinutes = 90, count = 6,
            ),
        )
        val slots = result.getOrThrow()
        // 第 4 节 10:45-11:30，其后插入 90 分钟午休 → 第 5 节 13:00 开始
        assertEquals("11:30", slots[3].endTime)
        assertEquals("13:00", slots[4].startTime)
        assertEquals(6, slots.size)
    }

    @Test
    fun rejectsBadInput() {
        assertTrue(SmartPeriods.derive(
            SmartPeriods.Params("8:00", 45, 10, 0, 0, 4),
        ).isFailure) // 缺前导零
        assertTrue(SmartPeriods.derive(
            SmartPeriods.Params("08:00", 0, 10, 0, 0, 4),
        ).isFailure) // 时长为 0
        assertTrue(SmartPeriods.derive(
            SmartPeriods.Params("08:00", 45, 10, 99, 0, 4),
        ).isFailure) // 午休位置越界
    }

    @Test
    fun periodCountRespectsMax() {
        val tooMany = SmartPeriods.derive(
            SmartPeriods.Params("08:00", 45, 10, 0, 0, CourseConstraints.MAX_PERIOD + 1),
        )
        assertTrue(tooMany.isFailure)
    }
}
