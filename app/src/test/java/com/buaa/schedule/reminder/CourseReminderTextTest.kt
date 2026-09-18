package com.buaa.schedule.reminder

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.schedule.toEpochMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 通知面的"快照文案"判据：**相对时间只允许出现在本来就有人重发的载体上**。
 *
 * 三条通知链的唤醒源各不相同，所以同一件事在三处写法不一样：
 * - 课前提醒横幅（tag `course_<id>` / [ReminderReceiver.NOTIFY_ID_COURSE]）：下发之后
 *   再没有任何东西碰它 → 只许写绝对时刻；
 * - 上课铃那条 promoted 兜底常驻（[ReminderNotifications.postClassOngoing]）：
 *   前台服务一起来就用同一 id 覆盖，服务被挡下时它是唯一能看到的一条，
 *   而且再没有第二次下发 → 同样只许写绝对终点；
 * - [CourseFluidService] 自己那条：每分钟按 `snapshotRedeadlineMillis` 重发一次 →
 *   才配写"还有 N 分钟"。
 *
 * 这里断言的都是纯函数：不接受 now（或 now 只喂给会走的那一支），
 * 所以"过不过期"能直接由文案看出来，不依赖真实时钟。
 */
class CourseReminderTextTest {

    private val classStart = LocalDateTime.of(2026, 9, 7, 8, 0)
    private val classEnd = LocalDateTime.of(2026, 9, 7, 9, 35)
    private val startMillis = classStart.toEpochMillis()
    private val endMillis = classEnd.toEpochMillis()

    /**
     * 课前提醒闹钟里的那份窗口：`startMillis` 与 `endMillis` **都是上课时刻**。
     *
     * 这是 [ReminderScheduler.createPendingIntent] 烘进 extras 的形状（课前那段实况量的
     * 是"这段等待过去了多少"，收铃时刻就是上课时刻，真正开跑时才由接收器把 start 改成此刻）。
     * 把它当成"start=上课、end=下课"来写测试，会得到一句"09:35 上课"。
     */
    private fun reminderWindow(
        at: LocalDateTime = classStart,
        location: String? = "J3-101",
        teacher: String? = "张三",
        section: String = "第1-2节",
    ) = ClassProgressScheduler.ClassWindow(
        courseId = 7L,
        courseName = "高等数学",
        location = location,
        sectionText = section,
        startMillis = at.toEpochMillis(),
        endMillis = at.toEpochMillis(),
        teacher = teacher,
        week = 3,
        dayOfWeek = 1,
        colorArgb = null,
    )

    // ---- 课前提醒横幅：永不过期的折叠行 ----

    @Test
    fun reminderHeadlineCarriesOnlyAbsoluteInfo() {
        assertEquals("08:00 上课 · J3-101", courseReminderHeadline(reminderWindow()))
    }

    @Test
    fun reminderHeadlineHasNoCountdownToGoStale() {
        val headline = courseReminderHeadline(reminderWindow())
        assertFalse(headline, headline.contains("还有"))
        assertFalse(headline, headline.contains("分钟"))
    }

    /**
     * "永不过期"的可证伪写法：一次性快照的输出与**读表的那一刻**无关。
     * 把 nowMillis 换成课前 10 分钟、迟到 3 分钟、下课之后，三行文案必须逐字相同。
     */
    @Test
    fun snapshotTextDoesNotDependOnTheReadingMoment() {
        val snapshots = listOf(
            startMillis - 10 * 60_000L,
            startMillis + 3 * 60_000L,
            endMillis + 40 * 60_000L,
        ).map { now ->
            liveBody("第1-2节", startMillis, endMillis, "J3-101", LivePhase.IN_CLASS, now, ticking = false)
        }
        assertEquals(1, snapshots.distinct().size)
        assertEquals("08:00 上课 · J3-101", courseReminderHeadline(reminderWindow()))
    }

    @Test
    fun reminderDetailListsSectionTimeAndRoom() {
        assertEquals(
            "第1-2节\n08:00 上课\nJ3-101 · 张三",
            courseReminderDetail(reminderWindow()),
        )
    }

    @Test
    fun reminderDetailSkipsMissingItemsEntirely() {
        val detail = courseReminderDetail(reminderWindow(location = null, teacher = null, section = ""))
        assertEquals("08:00 上课", detail)
        assertFalse(detail, detail.contains(" · "))
    }

    @Test
    fun reminderHeadlineFallsBackWhenTheTimeIsUnknowable() {
        // 旧闹钟 extras 缺终点（读出来是 0）：宁可退回节次，
        // 也不能凭空写一个 08:00 —— clockOf 那侧已有 <=0 哨兵
        val broken = reminderWindow().copy(endMillis = 0L, location = null, teacher = null)
        assertEquals("第1-2节", courseReminderHeadline(broken))
        assertEquals("第1-2节", courseReminderDetail(broken))
    }

    @Test
    fun reminderHeadlineWithNothingAtAllSaysTheCourseName() {
        val bare = reminderWindow().copy(endMillis = 0L, location = null, sectionText = "")
        assertEquals("高等数学 要上课了", courseReminderHeadline(bare))
    }

    /** 迟到投递（Doze 把闹钟压后几分钟）：文案一个字都不变，因为它不数时间 */
    @Test
    fun lateDeliveryReadsIdentical() {
        val onTime = courseReminderHeadline(reminderWindow())
        val threeMinutesLate = courseReminderHeadline(
            reminderWindow(at = classStart).copy(startMillis = startMillis + 180_000L),
        )
        assertEquals(onTime, threeMinutesLate)
    }

    // ---- 一次性快照（服务起不来时的兜底常驻）：绝对终点 ----

    @Test
    fun snapshotCarrierWritesTheAbsoluteDeadline() {
        val now = startMillis + 20 * 60_000L
        val ticking = liveBody("第1-2节", startMillis, endMillis, "J3-101", LivePhase.IN_CLASS, now)
        val snapshot = liveBody(
            "第1-2节", startMillis, endMillis, "J3-101", LivePhase.IN_CLASS, now, ticking = false,
        )
        assertEquals("第1-2节 · 08:00–09:35 · J3-101\n还有 75 分钟下课", ticking)
        assertEquals("第1-2节 · 08:00–09:35 · J3-101\n09:35 下课", snapshot)
    }

    @Test
    fun snapshotCarrierBeforeClassPointsAtTheBell() {
        assertEquals("08:00 上课", liveDeadlineLine(LivePhase.BEFORE_CLASS, startMillis))
        assertEquals("09:35 下课", liveDeadlineLine(LivePhase.IN_CLASS, endMillis))
    }

    @Test
    fun missingDeadlineDegradesToGenericWording() {
        assertEquals("马上上课", liveDeadlineLine(LivePhase.BEFORE_CLASS, 0L))
        assertEquals("即将下课", liveDeadlineLine(LivePhase.IN_CLASS, -1L))
        assertEquals("09:35", liveDeadlineChip(endMillis))
        assertEquals("进行中", liveDeadlineChip(0L))
    }

    @Test
    fun tickingCarrierKeepsTheWalkingNumber() {
        val now = endMillis - 8 * 60_000L - 1L
        assertEquals("还有 9 分钟下课", liveCountdownLine(LivePhase.IN_CLASS, endMillis, now))
        // 同一个终点交给快照版：写出来的是时刻，不是会走的数字
        assertEquals("09:35 下课", liveDeadlineLine(LivePhase.IN_CLASS, endMillis))
    }

    /** 一次性快照的任何形状里都不许残留相对数字 */
    @Test
    fun noRelativeWordingSurvivesInAnySnapshot() {
        val cases = listOf(
            reminderWindow(),
            reminderWindow(location = null),
            reminderWindow(teacher = null),
            reminderWindow(section = ""),
            reminderWindow(at = classStart.plusDays(1)),
            reminderWindow().copy(endMillis = 0L),
        )
        for (case in cases) {
            val text = courseReminderHeadline(case) + courseReminderDetail(case) +
                liveBody(
                    case.sectionText, case.startMillis, case.endMillis, case.location,
                    LivePhase.BEFORE_CLASS, 0L, ticking = false,
                ) +
                liveBody(
                    case.sectionText, case.startMillis, case.endMillis, case.location,
                    LivePhase.IN_CLASS, case.endMillis, ticking = false,
                ) + liveDeadlineChip(case.endMillis)
            assertFalse("快照文案里出现了相对数字：$text", text.contains("还有"))
        }
    }

    // ---- 明日预告：相对日期到零点就作废 ----

    @Test
    fun tomorrowPreviewTitleIsAnAbsoluteDate() {
        val title = ReminderNotifications.tomorrowPreviewTitle(LocalDate.of(2026, 9, 9))
        assertEquals("9月9日（周三） 的课程", title)
        assertFalse(title, title.contains("明天"))
    }

    @Test
    fun emptyTomorrowPreviewNamesTheDateItDescribes() {
        val text = ReminderNotifications.buildTomorrowPreviewText(
            courses = emptyList(),
            date = LocalDate.of(2026, 9, 12),
            timeSlots = emptyList(),
        )
        assertEquals("9月12日（周六） 没有课", text)
    }

    @Test
    fun tomorrowPreviewLinesAreAbsoluteClocks() {
        val courses = listOf(
            Course(
                name = "高等数学", location = "J3-101", dayOfWeek = 1,
                periods = listOf(1, 2), weeks = listOf(1),
            ),
        )
        val text = ReminderNotifications.buildTomorrowPreviewText(
            courses = courses, date = LocalDate.of(2026, 9, 7), timeSlots = emptyList(),
        )
        assertTrue(text, text.startsWith("08:00 高等数学 · J3-101 · 第1-2节"))
    }
}
