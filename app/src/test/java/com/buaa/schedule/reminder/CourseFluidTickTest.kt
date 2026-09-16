package com.buaa.schedule.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 实况重发节奏（R6 超级岛，去掉 chronometer 之后）。
 *
 * 岛上的倒计时小字是**静态文本**：以前秒级由系统 chronometer 渲染、应用只管进度格，
 * 现在两件事都落到"重发通知"上，所以醒得太晚小字就停住、醒得太勤就是白耗电。
 * 这两侧都得分开钉住。
 */
class CourseFluidTickTest {

    private val minute = 60_000L

    @Test
    fun normalLengthClassIsDrivenByProgressSteps() {
        // 90 分钟课：进度一格 = 54s，比一分钟的文案翻转更勤，所以按进度醒
        val start = 0L
        val end = 90 * minute
        assertEquals(54_000L, nextCourseFluidTickMs(start, end, 45 * minute))
    }

    @Test
    fun singlePeriodClassWakesEvenMoreOftenThanOnceAMinute() {
        // 45 分钟一节课：进度一格 = 27s
        assertEquals(27_000L, nextCourseFluidTickMs(0L, 45 * minute, 22 * minute + 30_000L))
    }

    @Test
    fun veryLongWindowFallsBackToTheChipFlip() {
        // 200 分钟窗口：进度一格 = 120s，比一分钟稀，此时必须改由小字翻转驱动，
        // 否则岛上的数字会整整一分钟不动。
        val tick = nextCourseFluidTickMs(0L, 200 * minute, 100 * minute)
        assertEquals(minute + 150L, tick)
    }

    @Test
    fun wakingUpLandsAfterTheChipTextActuallyFlips() {
        // 还剩 60_001ms 时小字仍是「2分钟」，下一次翻到「1分钟」在 now+1ms；
        // 醒过来必须已经越过那个边界，否则重发的是同样内容、白闪一次岛。
        val now = 540_000L - 1L
        val flipAt = 540_000L
        val tick = nextCourseFluidTickMs(0L, 600_000L, now)
        assertTrue("tick=$tick 应让 now+tick 越过翻转时刻", now + tick >= flipAt)
    }

    @Test
    fun degenerateWindowNeverBusyLoops() {
        // start==end 时 total 被兜成 1ms，不加下限这里会变成每毫秒重发一次
        assertEquals(1_000L, nextCourseFluidTickMs(1_000L, 1_000L, 1_000L))
        assertEquals(1_000L, nextCourseFluidTickMs(0L, minute, 2 * minute))
    }
}
