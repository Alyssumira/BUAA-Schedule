package com.buaa.schedule.reminder

import com.buaa.schedule.domain.schedule.toEpochMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * 「这个上课窗口相对此刻是什么状态」的唯一实现。
 *
 * 收敛前这句比较写了四遍：`WidgetCommon.nextWidgetInClass`、
 * `CourseFluidService.updater` 的自停判据、`LiveClassResyncer.decide`、
 * `ClassProgressScheduler.rescheduleWindows` 的遗留清理分支 ——
 * 四处分别用 `<=`/`<`/`>=`/`>` 的不同组合，边界那一秒谁对谁错全看抄的是哪一份。
 * 收敛后算法只此一处，四个消费方差的只是**谁在什么时候被叫醒**。
 *
 * 时间一律由参数注入：判据本身不读系统时钟。
 */
class ClassWindowStatusTest {

    private val begin = LocalDateTime.of(2026, 9, 7, 8, 0)
    private val end = LocalDateTime.of(2026, 9, 7, 9, 35)

    private fun window(startAt: LocalDateTime, endAt: LocalDateTime) = ClassProgressScheduler.ClassWindow(
        courseId = 1L,
        courseName = "高等数学",
        location = "J3-101",
        sectionText = "第1-2节",
        startMillis = startAt.toEpochMillis(),
        endMillis = endAt.toEpochMillis(),
    )

    private val classWindow = window(begin, end)

    private fun millisAt(moment: LocalDateTime) = moment.toEpochMillis()

    @Test
    fun becomesOngoingExactlyAtTheStartSecond() {
        assertTrue(classWindow.startsAfter(millisAt(begin.minusSeconds(1))))
        assertFalse(classWindow.startsAfter(millisAt(begin)))
        assertFalse(classWindow.ongoingAt(millisAt(begin.minusSeconds(1))))
        assertTrue(classWindow.ongoingAt(millisAt(begin)))
    }

    @Test
    fun stopsBeingOngoingExactlyAtTheEndSecond() {
        assertTrue(classWindow.ongoingAt(millisAt(end.minusSeconds(1))))
        assertFalse(classWindow.ongoingAt(millisAt(end)))
        assertFalse(classWindow.endedAt(millisAt(end.minusSeconds(1))))
        assertTrue(classWindow.endedAt(millisAt(end)))
    }

    /** 迟到唤醒：闹钟比预定晚投几分钟，醒来时必须按"已经在这节里"处理，不能改口成下一节 */
    @Test
    fun lateStartBellStillSeesTheClassAsOngoing() {
        val late = millisAt(begin.plusMinutes(3))
        assertTrue(classWindow.ongoingAt(late))
        assertFalse(classWindow.startsAfter(late))
        assertFalse(classWindow.endedAt(late))
    }

    /** 迟到唤醒的另一半：下课铃晚投时不能继续把课挂着，否则实况通知永不自停 */
    @Test
    fun lateEndBellSeesTheClassAsEnded() {
        val late = millisAt(end.plusMinutes(3))
        assertFalse(classWindow.ongoingAt(late))
        assertTrue(classWindow.endedAt(late))
        assertFalse(classWindow.startsAfter(late))
    }

    @Test
    fun threePredicatesCoverEveryInstantExactlyOnce() {
        val probes = mutableListOf(
            millisAt(begin) - 1L,
            millisAt(begin),
            millisAt(begin) + 1L,
            millisAt(end) - 1L,
            millisAt(end),
            millisAt(end) + 1L,
        )
        // 再加一串非整分钟的采样点：只踩边界的话，抄错一个符号也能过
        var moment = begin.minusHours(3)
        val stop = end.plusHours(3)
        while (!moment.isAfter(stop)) {
            probes += millisAt(moment)
            moment = moment.plusMinutes(7)
        }
        for (probe in probes) {
            val hits = listOf(
                classWindow.startsAfter(probe),
                classWindow.ongoingAt(probe),
                classWindow.endedAt(probe),
            )
            assertEquals(
                "每个时刻必须恰好命中「未开始/进行中/已结束」之一：$hits",
                1,
                hits.count { it },
            )
        }
    }

    /**
     * 下课时刻缺失（旧版闹钟 extras 里没写、读出来是 0）的窗口：
     * **绝不允许**判成"正在上课"，否则常驻通知与上课勿扰会一直挂着。
     */
    @Test
    fun windowWithoutAnEndTimeIsNeverOngoing() {
        val broken = classWindow.copy(endMillis = 0L)
        assertFalse(broken.ongoingAt(millisAt(begin.plusMinutes(10))))
        assertFalse(broken.ongoingAt(millisAt(begin)))
        assertTrue(broken.endedAt(millisAt(begin.plusMinutes(10))))
    }

    @Test
    fun emptyWindowIsNotOngoingEither() {
        val empty = classWindow.copy(startMillis = 0L, endMillis = 0L)
        assertFalse(empty.ongoingAt(millisAt(begin)))
        assertFalse(empty.startsAfter(millisAt(begin)))
        assertTrue(empty.endedAt(millisAt(begin)))
    }
}
