package com.buaa.schedule

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.reminder.ClassProgressScheduler
import com.buaa.schedule.reminder.ReminderScheduler
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 上/下课铃的生命周期（R5 F-11）。
 *
 * 课前提醒与课堂铃是两套 PendingIntent：`rescheduleAll` 里只撤前者，
 * "上课中途清空课表"就会留下一条永不消失的常驻通知 + 永久勿扰。
 * 这里用真实的 AlarmManager / PendingIntent 登记表跑一遍：
 * 铃要确实挂得上（否则断言是空的），撤完必须查不到。
 *
 * 运行：./gradlew :app:connectedDebugAndroidTest（需要设备或模拟器）
 */
@RunWith(AndroidJUnit4::class)
class ClassBellLifecycleTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** 明天（第 2 教学周）的某一节还没上的课：保证一定排得出铃声 */
    private fun futureCourse() = Course(
        id = 1L,
        name = "铃声回归测试课",
        location = "J3-101",
        dayOfWeek = tomorrow().dayOfWeek.value,
        periods = listOf(1, 2),
        weeks = listOf(2),
    )

    private fun tomorrow(): LocalDate = LocalDate.now().plusDays(1)

    private fun mondayOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** 让 `tomorrow` 落在第 2 教学周 */
    private fun semesterStartForTomorrow() = mondayOf(tomorrow()).minusWeeks(1)

    private fun semester(start: LocalDate) = Semester(
        termCode = "TEST-BELL",
        termName = "TEST-BELL",
        startDate = start.toString(),
        totalWeeks = 19,
    )

    @Before
    fun startWithNoBells() {
        ClassProgressScheduler.cancelAll(context)
    }

    @Test
    fun clearingTheScheduleTakesDownClassBells() {
        val start = semesterStartForTomorrow()
        ClassProgressScheduler.rescheduleWindows(context, listOf(futureCourse()), start, emptyList())
        assertTrue("测试前提：课堂铃必须已经排上", ClassProgressScheduler.hasPendingClassBells(context))

        // 用户在上课中途把课表清空：走的是课前提醒的统一重排入口
        ReminderScheduler.rescheduleAll(context, emptyList(), semester(start), emptyList())

        assertFalse("课表清空后不能再挂着上/下课铃", ClassProgressScheduler.hasPendingClassBells(context))
    }

    @Test
    fun scheduleWithNothingLeftToRunTakesDownClassBells() {
        val start = semesterStartForTomorrow()
        ClassProgressScheduler.rescheduleWindows(context, listOf(futureCourse()), start, emptyList())
        assertTrue("测试前提：课堂铃必须已经排上", ClassProgressScheduler.hasPendingClassBells(context))

        // 课还在列表里，但学期已经结束 → 挑不出下一条提醒，同样必须收铃
        val stale = semester(mondayOf(LocalDate.now()).minusWeeks(30))
        ReminderScheduler.rescheduleAll(context, listOf(futureCourse()), stale, emptyList())

        assertFalse(
            "没有任何即将到来的提醒时，课堂铃也必须一起收掉",
            ClassProgressScheduler.hasPendingClassBells(context),
        )
    }

    @Test
    fun reschedulingArmsBothBellsForTheUpcomingClass() {
        ClassProgressScheduler.rescheduleWindows(
            context = context,
            courses = listOf(futureCourse()),
            semesterStart = semesterStartForTomorrow(),
            timeSlots = emptyList(),
        )

        assertTrue(
            "明天的课应同时排上上课铃与下课铃",
            ClassProgressScheduler.hasPendingClassBells(context),
        )
        ClassProgressScheduler.cancelAll(context)
        assertFalse("cancelAll 之后不该再留有铃声登记", ClassProgressScheduler.hasPendingClassBells(context))
    }
}
