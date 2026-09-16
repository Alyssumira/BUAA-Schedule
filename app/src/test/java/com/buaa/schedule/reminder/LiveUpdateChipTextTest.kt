package com.buaa.schedule.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 岛上/胶囊那一格的小字（`shortCriticalText`）。
 *
 * 这一格很窄、且 HyperOS 只肯渲染纯文本：R5 之前我们把正文句式「还有 N 分钟下课」直接塞进去，
 * 真机上岛上看不到东西；SleepDown 的取证同样写明带 span 的文案会让倒计时渲染失败。
 * 句式因此是**契约**，不是排版偏好，得钉住。
 */
class LiveUpdateChipTextTest {

    private val minute = 60_000L

    @Test
    fun chipTextIsPlainMinutesWithoutSentenceWords() {
        val chip = ReminderNotifications.chipCountdownLabel(System.currentTimeMillis() + 38 * minute)
        assertEquals("38分钟", chip)
        assertFalse(chip.contains("还有"))
        assertFalse(chip.contains(" "))
    }

    @Test
    fun chipTextRoundsUpSoItNeverClaimsZeroTooEarly() {
        // 还剩 90s：说「2分钟」而不是「1分钟」，与正文的向上取整口径一致
        assertEquals("2分钟", ReminderNotifications.chipCountdownLabel(System.currentTimeMillis() + 90_000L))
        assertEquals("1分钟", ReminderNotifications.chipCountdownLabel(System.currentTimeMillis() + 30_000L))
    }

    @Test
    fun chipTextClampsToZeroAfterClassEnds() {
        // 过点之后不能出现负数分钟——岛上写「-3分钟」比不写更糟
        assertEquals("0分钟", ReminderNotifications.chipCountdownLabel(System.currentTimeMillis() - 5 * minute))
    }
}
