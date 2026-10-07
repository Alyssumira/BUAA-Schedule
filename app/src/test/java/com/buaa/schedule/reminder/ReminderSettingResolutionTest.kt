package com.buaa.schedule.reminder

import com.buaa.schedule.data.backup.BackupReminder
import com.buaa.schedule.data.local.ReminderEntity
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.EffectiveReminderSetting
import com.buaa.schedule.domain.model.ReminderSetting
import com.buaa.schedule.domain.schedule.CourseConstraints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * T214 病灶 B（#264）的判据表：提醒那三档「有行且开 / 有行但关 / 没有行」。
 *
 * 先回答卡面问的那一件：**"没有行"是常态**。三条写库链逐条读过（回执里有行号）：
 * - 教务导入 `data/repository/ScheduleRepository.kt` 的 `replaceSemesterCoursesInTx` /
 *   `replaceCoursesForExistingSemesterInTx` 对 reminders 表只有 `deleteRemindersOfDroppedCourses`
 *   一句（删被顶掉的行），全链零 `upsert`；
 * - 备份恢复 `restoreBackupDataInTx` 只对**备份里带了行**的那些课 `upsert`
 *   （`remindersByCourseKey.forEach`），而备份那本账来自导出时的 `getReminders()`（有才带）；
 * - 手动新建 `ui/editor/CourseEditorScreen.kt` 的 `saveCourseDraft` 把写提醒那句包在
 *   `if (isEditingExisting)` 里 ⇒ 新建那一路不写。
 *
 * 所以按卡面的第一档处置：**显式承认"默认开启 + 默认提前量"是产品口径**，
 * 并要求它 (1) 只有一枚来源（[ReminderSetting.DEFAULT_ADVANCE_MINUTES] /
 * [ReminderSetting.DEFAULT_ENABLED]），(2) 结论自带出处（[EffectiveReminderSetting.Basis]），
 * (3) 在用户看得见的地方说出来（`ui/editor/EditorReminderSectionSummaryTest`）。
 * 本文件同时钉排铃链上这三档的行为：**改前改后一个字节都没变**（口径本来就是默认放行），
 * 变的是"说不说得清自己是哪一档"。
 */
class ReminderSettingResolutionTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val semesterStart = LocalDate.of(2026, 9, 7)
    private val totalWeeks = 16
    private val now = LocalDateTime.of(2026, 9, 7, 7, 0)

    private val mondayCourse = Course(
        id = 1,
        name = "高等数学",
        dayOfWeek = 1,
        periods = listOf(1),
        weeks = (1..totalWeeks).toList(),
    )

    private data class Cell(
        val name: String,
        val setting: ReminderSetting?,
        val expectedEnabled: Boolean,
        val expectedAdvance: Int,
        val expectedBasis: EffectiveReminderSetting.Basis,
    )

    private val cells = listOf(
        Cell(
            name = "有行且开 ⇒ 照读那一行（advanceMinutes 是行里写的 20，不是默认）",
            setting = ReminderSetting(courseId = 1, enabled = true, advanceMinutes = 20),
            expectedEnabled = true,
            expectedAdvance = 20,
            expectedBasis = EffectiveReminderSetting.Basis.RowOn,
        ),
        Cell(
            name = "有行且开、提前 0 分钟（合法边界）⇒ 读来的是 0，不许被默认顶成 10",
            setting = ReminderSetting(courseId = 1, enabled = true, advanceMinutes = 0),
            expectedEnabled = true,
            expectedAdvance = 0,
            expectedBasis = EffectiveReminderSetting.Basis.RowOn,
        ),
        Cell(
            name = "有行但关 ⇒ enabled=false，且这一档必须说得出自己是 RowOff 而不是没设过",
            setting = ReminderSetting(courseId = 1, enabled = false, advanceMinutes = 15),
            expectedEnabled = false,
            expectedAdvance = 15,
            expectedBasis = EffectiveReminderSetting.Basis.RowOff,
        ),
        Cell(
            name = "没有行 ⇒ 默认开启 + 默认提前量，且出处是 NoRowDefaultOn（这一档就是 #264 说漏的那半句）",
            setting = null,
            expectedEnabled = ReminderSetting.DEFAULT_ENABLED,
            expectedAdvance = ReminderSetting.DEFAULT_ADVANCE_MINUTES,
            expectedBasis = EffectiveReminderSetting.Basis.NoRowDefaultOn,
        ),
        Cell(
            name = "没有行时读到的两枚值必须与 ReminderSetting 自己的构造默认**同一枚**（不是抄的第二个数）",
            setting = null,
            expectedEnabled = ReminderSetting(courseId = 1).enabled,
            expectedAdvance = ReminderSetting(courseId = 1).advanceMinutes,
            expectedBasis = EffectiveReminderSetting.Basis.NoRowDefaultOn,
        ),
    )

    @Test
    fun `三档逐格对账`() {
        for (cell in cells) {
            val effective = EffectiveReminderSetting.of(cell.setting)
            assertEquals("${cell.name}（enabled）", cell.expectedEnabled, effective.enabled)
            assertEquals("${cell.name}（advanceMinutes）", cell.expectedAdvance, effective.advanceMinutes)
            assertEquals("${cell.name}（basis）", cell.expectedBasis, effective.basis)
        }
    }

    /** 三档必须**互不重合**：两两不同的 basis，否则"说不清自己是哪一档"原地复发 */
    @Test
    fun threeBasesAreDistinctAndCoverEveryInput() {
        val bases = cells.map { EffectiveReminderSetting.of(it.setting).basis }.distinct()
        assertEquals("三档必须各是一档（合并成两档就说明有一档没被交代）", 3, bases.size)
        assertEquals(
            "枚举里每一档都被表覆盖到（少一档就是有一等输入没格子）",
            EffectiveReminderSetting.Basis.values().toSet(),
            bases.toSet(),
        )
    }

    /**
     * 默认值只有一枚来源：三处不同形状的构造默认都必须等于 domain 那两枚常量。
     *
     * 枚数册子（"全仓 `advanceMinutes` 的默认值只有一处字面量"）由
     * `reminder/ReminderDefaultSingleSourceGuardTest` 按源码文本钉；这里钉的是**取值相等**——
     * 两处各钉一半：改一处的字面量，本文件与守卫一起红。
     */
    @Test
    fun everyDefaultComesFromTheSameConstant() {
        val default = ReminderSetting.DEFAULT_ADVANCE_MINUTES
        assertEquals("ReminderSetting 的构造默认", default, ReminderSetting(courseId = 1).advanceMinutes)
        assertEquals("ReminderEntity 的构造默认（建行时的那一格）", default, ReminderEntity(courseId = 1).advanceMinutes)
        assertEquals("BackupReminder 的构造默认（备份缺键时的那一格）", default, BackupReminder(courseKey = "k").advanceMinutes)
        assertEquals("编辑器留空时的回退值（与排铃侧同一枚）", default, CourseConstraints.normalizeAdvanceMinutes(default))
        assertTrue(
            "enabled 的默认也同源",
            ReminderSetting(courseId = 1).enabled == ReminderEntity(courseId = 1).enabled &&
                ReminderSetting(courseId = 1).enabled == BackupReminder(courseKey = "k").enabled,
        )
    }

    /** 脏行里的提前量仍然过归一化（收默认值来源不等于放弃限幅：999 不能变成 999 分钟） */
    @Test
    fun dirtyStoredAdvanceIsStillClampedThroughTheOnePath() {
        assertEquals(
            "行里写着 999 ⇒ 排出去的是上限",
            CourseConstraints.MAX_ADVANCE_MINUTES,
            EffectiveReminderSetting.of(ReminderSetting(courseId = 1, advanceMinutes = 999)).advanceMinutes
                .let(CourseConstraints::normalizeAdvanceMinutes),
        )
        assertEquals(
            "行里写着 -5 ⇒ 归一到 0",
            0,
            CourseConstraints.normalizeAdvanceMinutes(
                EffectiveReminderSetting.of(ReminderSetting(courseId = 1, advanceMinutes = -5)).advanceMinutes,
            ),
        )
    }

    /**
     * 链上行为：三档在 [ReminderScheduler.planTiedReminders] 里的落点。
     *
     * ⚠️ 这一格钉的是**没变**：改前"没有行"也是放行 + 10 分钟（这正是病灶被当成正常的缘故），
     * 改后必须一个字节不差，只是结论带上出处。若有人把"没有行 = 不提醒"悄悄改上去，
     * 前三条断言里 [0] 与 [2] 当场红 —— 那种行为变更要走另一张卡与装机验收（卡面 B 的第二档处置）。
     */
    @Test
    fun chainKeepsTheSameBehaviourButNamesItsBasis() {
        val open = ReminderSetting(courseId = 1, enabled = true, advanceMinutes = 20)

        val fromRow = ReminderScheduler.planNextReminder(
            courses = listOf(mondayCourse),
            semesterStart = semesterStart,
            totalWeeks = totalWeeks,
            timeSlots = emptyList(),
            reminders = mapOf(1L to open),
            now = now,
            nowMillis = now.atZone(zone).toInstant().toEpochMilli(),
            zone = zone,
        )
        assertEquals(20, fromRow?.advanceMinutes)
        assertEquals(EffectiveReminderSetting.Basis.RowOn, fromRow?.settingBasis)

        val off = ReminderScheduler.planNextReminder(
            courses = listOf(mondayCourse),
            semesterStart = semesterStart,
            totalWeeks = totalWeeks,
            timeSlots = emptyList(),
            reminders = mapOf(1L to ReminderSetting(courseId = 1, enabled = false, advanceMinutes = 20)),
            now = now,
            nowMillis = now.atZone(zone).toInstant().toEpochMilli(),
            zone = zone,
        )
        assertNull("有行但关 ⇒ 这一门整个出局（改前后同解）", off)

        val noRow = ReminderScheduler.planNextReminder(
            courses = listOf(mondayCourse),
            semesterStart = semesterStart,
            totalWeeks = totalWeeks,
            timeSlots = emptyList(),
            reminders = emptyMap(),
            now = now,
            nowMillis = now.atZone(zone).toInstant().toEpochMilli(),
            zone = zone,
        )
        assertEquals("没有行 ⇒ 仍按默认提前量（口径未改）", ReminderSetting.DEFAULT_ADVANCE_MINUTES, noRow?.advanceMinutes)
        assertEquals(EffectiveReminderSetting.Basis.NoRowDefaultOn, noRow?.settingBasis)
        assertFalse(
            "等首与伙伴各带自己的出处（伙伴那格不许被等首的结论顶掉）",
            noRow?.sameInstantPartners?.isNotEmpty() == true,
        )
    }
}
