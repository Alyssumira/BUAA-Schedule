package com.buaa.schedule.ui.settings

import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.TimeSlotProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 节次时间校验测试。
 *
 * 节次时间写坏会让整个课表时间轴错位（当前课高亮、提醒闹钟、ICS 导出全跟着错），
 * 所以格式与先后顺序都必须卡住。
 */
class TimeSlotValidationTest {

    private fun slot(start: String, end: String) = TimeSlot(number = 1, startTime = start, endTime = end)

    @Test
    fun acceptsWellFormedRange() {
        assertTrue(isValidTimeSlot(slot("08:00", "08:45")))
        assertTrue(isValidTimeSlot(slot("00:00", "23:59")))
    }

    @Test
    fun rejectsMissingLeadingZero() {
        // "8:00" 字符串比较会大于 "10:00"，必须在格式层拦掉
        assertFalse(isValidTimeSlot(slot("8:00", "08:45")))
        assertFalse(isValidTimeSlot(slot("08:00", "8:45")))
    }

    @Test
    fun rejectsNonTimeText() {
        assertFalse(isValidTimeSlot(slot("", "")))
        assertFalse(isValidTimeSlot(slot("08:00", "abc")))
        assertFalse(isValidTimeSlot(slot("8点", "9点")))
    }

    @Test
    fun rejectsStartNotBeforeEnd() {
        assertFalse("开始等于结束应视为非法", isValidTimeSlot(slot("08:00", "08:00")))
        assertFalse("结束早于开始应视为非法", isValidTimeSlot(slot("10:00", "09:00")))
    }

    @Test
    fun rejectsRangeCrossingMidnight() {
        // 跨零点在课表语义下无意义（同一天内的时间轴），明确拒绝
        assertFalse(isValidTimeSlot(slot("23:00", "00:30")))
    }

    @Test
    fun defaultProfileIsFullyValid() {
        // 内置默认节次时间表必须全部合法，否则首次启动就会出现"非法节次"提示
        assertTrue(TimeSlotProfile.DEFAULT.isNotEmpty())
        TimeSlotProfile.DEFAULT.forEach { slot ->
            assertTrue("第 ${slot.number} 节 ${slot.startTime}-${slot.endTime} 应为合法", isValidTimeSlot(slot))
        }
    }
}
