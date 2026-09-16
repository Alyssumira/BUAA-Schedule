package com.buaa.schedule.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * 明日预告 22:00 调度时刻的纯函数测试。
 *
 * 预告晚几分钟可接受（非精确闹钟降级路径），但「错过当天 22:00 必须排到明天」
 * 与「22:00 前必须落在今天」这两个边界不能错。
 */
class TomorrowPreviewScheduleTimeTest {

    @Test
    fun beforeTenPmSchedulesToday() {
        val now = LocalDateTime.of(2026, 9, 13, 21, 30)
        val millis = TomorrowPreviewScheduler.nextFireTime(now)
        val fired = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
        assertEquals(LocalDateTime.of(2026, 9, 13, 22, 0), fired)
    }

    @Test
    fun afterTenPmSchedulesTomorrow() {
        val now = LocalDateTime.of(2026, 9, 13, 22, 30)
        val millis = TomorrowPreviewScheduler.nextFireTime(now)
        val fired = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
        assertEquals(LocalDateTime.of(2026, 9, 14, 22, 0), fired)
    }

    @Test
    fun exactlyAtTenPmSchedulesTomorrow() {
        // 恰好 22:00 触发时已算错过（!isAfter 为假），应排到明天
        val now = LocalDateTime.of(2026, 9, 13, 22, 0)
        val millis = TomorrowPreviewScheduler.nextFireTime(now)
        val fired = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
        assertEquals(LocalDateTime.of(2026, 9, 14, 22, 0), fired)
    }

    @Test
    fun scheduledTimeIsAlwaysInFuture() {
        val now = LocalDateTime.of(2026, 9, 13, 10, 0)
        val millis = TomorrowPreviewScheduler.nextFireTime(now)
        assertTrue(millis > now.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
    }
}
