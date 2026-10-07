package com.buaa.schedule.reminder

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.ReminderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 病灶 A 的判据表：课前提醒「并列那一等」必须整体交出来。
 *
 * 钉的是 [ReminderScheduler.planTiedReminders] 与 [ReminderScheduler.allocateReminderSlots]
 * 两枚纯函数 —— **零 android、零时钟**（`now` / `nowMillis` / `zone` 全是参数）：
 * 排铃那一段要 Context，本模块单测没有 Robolectric，所以钉得住的只有判据本身。
 *
 * 两个方向各有一侧顶着：
 * - 判据过宽（把全部候选都当成"并列"一起排）＝ 一次唤醒排出一学期的闹钟，
 *   由「不并列的必须留在表外」与「同刻开课但提前量不同不算并列」那两格顶住；
 * - 判据过严（把还没到触发时刻的也提前吃掉）＝ 由「近触发还剩两分钟必须留着」那格顶住。
 */
class ReminderTieGroupTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    /** 学期第 1 周周一 = 2026-09-07 ⇒ 周三 = 09-09；默认节次表第 3 节 09:50 上课 */
    private val semesterStart = LocalDate.of(2026, 9, 7)

    private val earlyNow = LocalDateTime.of(2026, 9, 7, 8, 0)

    /** 周三第 3 节的触发时刻：09:50 上课、默认提前 10 分钟 ⇒ 09:40 */
    private val wednesdayThirdTrigger = mills(LocalDateTime.of(2026, 9, 9, 9, 40))

    private fun mills(dateTime: LocalDateTime): Long = dateTime.atZone(zone).toInstant().toEpochMilli()

    private fun wednesdayCourse(id: Long, period: Int, weeks: List<Int> = (1..16).toList()) = Course(
        id = id,
        name = "课程$id",
        dayOfWeek = 3,
        periods = listOf(period),
        weeks = weeks,
    )

    private data class Cell(
        val name: String,
        val courses: List<Course>,
        val reminders: Map<Long, ReminderSetting>,
        val now: LocalDateTime,
        /** 期望的课程 id 序（等首在表头）；空表 = 本轮没有可排的提醒 */
        val expectedIds: List<Long>,
    )

    private val cells = listOf(
        Cell(
            name = "两门同触发时刻（这台机器上的离散数学 + 概率论那一档）⇒ 两门都要出来，等首是 id 小的那门",
            courses = listOf(wednesdayCourse(9, 3), wednesdayCourse(2, 3)),
            reminders = emptyMap(),
            now = earlyNow,
            expectedIds = listOf(2, 9),
        ),
        Cell(
            name = "三门同触发时刻 ⇒ 三门都要出来",
            courses = listOf(wednesdayCourse(11, 3), wednesdayCourse(3, 3), wednesdayCourse(7, 3)),
            reminders = emptyMap(),
            now = earlyNow,
            expectedIds = listOf(3, 7, 11),
        ),
        Cell(
            name = "并列但其中一门提醒关掉 ⇒ 只留开着的那门（关掉的不是被吃掉，是不该排）",
            courses = listOf(wednesdayCourse(2, 3), wednesdayCourse(9, 3)),
            reminders = mapOf(9L to ReminderSetting(courseId = 9, enabled = false, advanceMinutes = 10)),
            now = earlyNow,
            expectedIds = listOf(2),
        ),
        Cell(
            name = "两门并列刚响过（此刻 09:41，触发时刻 09:40）⇒ 整等一起出局：本轮再没有下一条（两门都已在 09:40 被排上并响过）",
            courses = listOf(wednesdayCourse(2, 3), wednesdayCourse(9, 3)),
            reminders = emptyMap(),
            now = LocalDateTime.of(2026, 9, 9, 9, 41),
            expectedIds = emptyList(),
        ),
        Cell(
            name = "已过触发时刻的那门出局、还没到的那门留下（『不补发陈旧提醒』的既有口径，此时只剩一门是正解）",
            courses = listOf(wednesdayCourse(9, 3), wednesdayCourse(2, 4)),
            reminders = emptyMap(),
            // 09:45：第 3 节那门的触发时刻（09:40）已过；第 4 节那门 10:30 才触发
            now = LocalDateTime.of(2026, 9, 9, 9, 45),
            expectedIds = listOf(2),
        ),
        Cell(
            name = "不并列（第 3 节与第 4 节差 60 分钟）⇒ 只取最早那一枚，第二门留在表外",
            courses = listOf(wednesdayCourse(2, 3), wednesdayCourse(5, 4)),
            reminders = emptyMap(),
            now = earlyNow,
            expectedIds = listOf(2),
        ),
        Cell(
            name = "同一时刻开课但提前量不同 ⇒ 触发时刻不同就不算并列（分组键是 triggerAt，不是 classStart）",
            courses = listOf(wednesdayCourse(2, 3), wednesdayCourse(9, 3)),
            reminders = mapOf(
                2L to ReminderSetting(courseId = 2, enabled = true, advanceMinutes = 20),
                9L to ReminderSetting(courseId = 9, enabled = true, advanceMinutes = 10),
            ),
            now = earlyNow,
            expectedIds = listOf(2),
        ),
        Cell(
            name = "近触发（还有两分钟才到触发时刻）⇒ 必须留着，判据不许提前把它吃掉",
            courses = listOf(wednesdayCourse(2, 3), wednesdayCourse(9, 3)),
            reminders = emptyMap(),
            now = LocalDateTime.of(2026, 9, 9, 9, 38),
            expectedIds = listOf(2, 9),
        ),
        Cell(
            name = "触发时刻恰好就是此刻 ⇒ 两门一起丢掉（不补发陈旧提醒，边界取 <=）",
            courses = listOf(
                wednesdayCourse(2, 3, weeks = listOf(1)),
                wednesdayCourse(9, 3, weeks = listOf(1)),
            ),
            reminders = emptyMap(),
            now = LocalDateTime.of(2026, 9, 9, 9, 40),
            expectedIds = emptyList(),
        ),
        Cell(
            name = "两门都只在第 1 周且都已过触发 ⇒ 空表",
            courses = listOf(
                wednesdayCourse(2, 3, weeks = listOf(1)),
                wednesdayCourse(9, 3, weeks = listOf(1)),
            ),
            reminders = emptyMap(),
            now = LocalDateTime.of(2026, 9, 9, 12, 0),
            expectedIds = emptyList(),
        ),
        Cell(
            name = "课表为空 ⇒ 空表（不是 null，也不许凭空造一门）",
            courses = emptyList(),
            reminders = emptyMap(),
            now = earlyNow,
            expectedIds = emptyList(),
        ),
    )

    @Test
    fun earliestTieGroupIsHandedOverWhole() {
        for (cell in cells) {
            val group = ReminderScheduler.planTiedReminders(
                courses = cell.courses,
                semesterStart = semesterStart,
                timeSlots = emptyList(),
                reminders = cell.reminders,
                now = cell.now,
                nowMillis = mills(cell.now),
                zone = zone,
            )
            assertEquals(
                "${cell.name}\n实际=${group.map { it.course.id }} 期望=${cell.expectedIds}",
                cell.expectedIds,
                group.map { it.course.id },
            )
        }
    }

    /** 行序不许决定生死：同一批课换两种数据库返回顺序，交出来的整等必须一样 */
    @Test
    fun databaseRowOrderNoLongerDecidesWhoGetsReminded() {
        val a = wednesdayCourse(9, 3)
        val b = wednesdayCourse(2, 3)
        val forward = ReminderScheduler.planTiedReminders(
            listOf(a, b), semesterStart, emptyList(), emptyMap(), earlyNow, mills(earlyNow), zone,
        )
        val reversed = ReminderScheduler.planTiedReminders(
            listOf(b, a), semesterStart, emptyList(), emptyMap(), earlyNow, mills(earlyNow), zone,
        )

        assertEquals(2, forward.size)
        assertEquals(
            "换一种行序就少排一门课：forward=${forward.map { it.course.id }} reversed=${reversed.map { it.course.id }}",
            forward.map { it.course.id },
            reversed.map { it.course.id },
        )
    }

    /**
     * 伙伴那一格的形状：只有等首带伙伴（其余各门自己那格是空表，否则 toString/equals 互指
     * 绕无穷圈）、伙伴里不含等首自己（否则同一门课占两枚槽位、通知发两遍）、
     * 整等里触发时刻全相等（"一等"的定义就在这格）。
     */
    @Test
    fun partnersRideOnlyOnTheHeadAndNeverPointBack() {
        val group = ReminderScheduler.planTiedReminders(
            courses = listOf(wednesdayCourse(9, 3), wednesdayCourse(2, 3), wednesdayCourse(7, 3)),
            semesterStart = semesterStart,
            timeSlots = emptyList(),
            reminders = emptyMap(),
            now = earlyNow,
            nowMillis = mills(earlyNow),
            zone = zone,
        )

        assertEquals(3, group.size)
        val head = group.first()
        assertEquals(2, head.sameInstantPartners.size)
        assertTrue("伙伴里不许含等首自己", head.sameInstantPartners.none { it.course.id == head.course.id })
        assertTrue(
            "伙伴自己不许再带伙伴：toString 与 equals 会互指绕无穷圈",
            head.sameInstantPartners.none { it.sameInstantPartners.isNotEmpty() },
        )
        assertTrue(
            "整等里除等首外的每一枚，交出来时伙伴那格必须是空表",
            group.drop(1).none { it.sameInstantPartners.isNotEmpty() },
        )
        assertEquals(1, group.map { it.triggerAtMillis }.distinct().size)
        assertEquals(wednesdayThirdTrigger, head.triggerAtMillis)
        // 打印等首不该把栈撑爆（伙伴互指时这里就是 StackOverflowError）
        assertTrue(head.toString().contains("课程2"))
    }

    /** [ReminderPlan.armableTogether] 是"本轮一起排出去"的唯一读法：等首 + 伙伴，不多不少 */
    @Test
    fun headCarriesTheWholeGroupForArming() {
        val head = ReminderScheduler.planTiedReminders(
            courses = listOf(wednesdayCourse(9, 3), wednesdayCourse(2, 3)),
            semesterStart = semesterStart,
            timeSlots = emptyList(),
            reminders = emptyMap(),
            now = earlyNow,
            nowMillis = mills(earlyNow),
            zone = zone,
        ).first()

        assertEquals(listOf(2L, 9L), head.armableTogether().map { it.course.id })
    }

    /** [ReminderScheduler.planNextReminder] 仍是"头名"形状：拿到的就是等首，伙伴带着 */
    @Test
    fun singleHeadShapeStillPointsAtTheHead() {
        val head = ReminderScheduler.planNextReminder(
            courses = listOf(wednesdayCourse(9, 3), wednesdayCourse(2, 3)),
            semesterStart = semesterStart,
            timeSlots = emptyList(),
            reminders = emptyMap(),
            now = earlyNow,
            nowMillis = mills(earlyNow),
            zone = zone,
        )

        assertEquals(2L, head?.course?.id)
        assertEquals(
            "头名那枚必须带着同刻伙伴，否则排程又只剩一门",
            listOf(9L),
            head?.sameInstantPartners?.map { it.course.id },
        )

        val late = LocalDateTime.of(2026, 9, 9, 12, 0)
        assertNull(
            "没有可排候选时头名形状仍是 null（调用方按『本轮没有提醒』判断）",
            ReminderScheduler.planNextReminder(
                courses = listOf(wednesdayCourse(2, 3, weeks = listOf(1))),
                semesterStart = semesterStart,
                timeSlots = emptyList(),
                reminders = emptyMap(),
                now = late,
                nowMillis = mills(late),
                zone = zone,
            ),
        )
    }

    private data class SlotCell(
        val name: String,
        val tiedSize: Int,
        val maxSlots: Int,
        val expectedArmed: Int,
        val expectedDropped: Int,
    )

    private val slotCells = listOf(
        SlotCell("一门课、四枚槽位 ⇒ 全排上", 1, 4, 1, 0),
        SlotCell("两门并列、四枚槽位 ⇒ 全排上（这台机器上的常态）", 2, 4, 2, 0),
        SlotCell("四门并列、四枚槽位 ⇒ 刚好排满", 4, 4, 4, 0),
        SlotCell("六门并列、四枚槽位 ⇒ 挤掉两门，而且掉几门要数得出来", 6, 4, 4, 2),
        SlotCell("零枚槽位（参数越界那一档）⇒ 一门都排不上，也不许崩", 3, 0, 0, 3),
        SlotCell("空等 ⇒ 空表、零掉落", 0, 4, 0, 0),
    )

    /**
     * 槽位上限那笔账单独一格：超限时排了几门、掉了几门必须数得出来 ——
     * 那句 `Log.w` 读的就是 [ReminderScheduler.ReminderSlotting.droppedCount]，
     * 数错了就是"并列吃课"换了个更隐蔽的形状重演。
     */
    @Test
    fun slotCapAccountsForEveryCourseItCannotArm() {
        for (cell in slotCells) {
            val plans = List(cell.tiedSize) { index ->
                ReminderScheduler.ReminderPlan(
                    course = wednesdayCourse(index + 1L, 3),
                    segment = 3..3,
                    classStart = LocalDateTime.of(2026, 9, 9, 9, 50),
                    advanceMinutes = 10,
                    triggerAtMillis = wednesdayThirdTrigger,
                    week = 1,
                )
            }
            val tied = if (plans.isEmpty()) {
                emptyList()
            } else {
                listOf(plans.first().copy(sameInstantPartners = plans.drop(1))) + plans.drop(1)
            }

            val slotting = ReminderScheduler.allocateReminderSlots(tied, cell.maxSlots)

            assertEquals("${cell.name}：排上的枚数不对", cell.expectedArmed, slotting.armed.size)
            assertEquals("${cell.name}：挤出去的门数不对", cell.expectedDropped, slotting.droppedCount)
            if (cell.expectedArmed > 0) {
                assertEquals(
                    "${cell.name}：槽位 0 必须是等首（被挤掉的只可能是表尾）",
                    tied.first().course.id,
                    slotting.armed.first().course.id,
                )
            }
        }
    }
}
