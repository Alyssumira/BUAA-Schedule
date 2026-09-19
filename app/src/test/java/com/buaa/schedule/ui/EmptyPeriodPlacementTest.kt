package com.buaa.schedule.ui

import com.buaa.schedule.data.backup.BackupCourse
import com.buaa.schedule.data.backup.toDomain
import com.buaa.schedule.data.local.Converters
import com.buaa.schedule.data.local.CourseEntity
import com.buaa.schedule.data.local.toDomain as entityToDomain
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.TimeSlotProfile
import com.buaa.schedule.domain.schedule.ScheduleExporters
import com.buaa.schedule.ui.home.TimeSlotIndex
import com.buaa.schedule.ui.home.timeWindowOf
import com.buaa.schedule.widget.weekGridDaySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这门课没有节次」时，**定位/导出**那几条链上的落点。
 *
 * T26 收掉的是文案链（少一句「第1-2节」而已），这一份收的是更要紧的一类：
 * `Course.startPeriod` 在空表时兜底成 1，于是消费方把这个 1 当成"确实有一节在第 1 节"
 * 来用 —— 导出的 JSON 里多出一条 `startNode:1,step:0` 的假课、桌面组件印出「1高等数学」、
 * 24 小时时间轴被一门画不出来的课撑开。这些都会离开本应用或占住一个不存在的位置。
 *
 * 口径与 T26 一致：**这一项不出场**，而不是换个猜测值顶上。每条断言都成对写：
 * 先钉住节次齐全时逐字/逐分钟不变，再钉住没有节次时它整个消失。
 * 全部真调生产函数（导出器、组件摘要、抽出来的 `timeWindowOf`），不在测试里重拼一遍。
 */
class EmptyPeriodPlacementTest {

    private fun course(
        name: String = "高等数学 A",
        day: Int = 1,
        periods: List<Int> = listOf(1, 2),
        weeks: List<Int> = listOf(1, 2, 3),
        teacher: String? = "张三",
        location: String? = "主楼A-101",
        colorIndex: Int = 0,
    ) = Course(
        id = name.hashCode().toLong(),
        name = name,
        teacher = teacher,
        location = location,
        dayOfWeek = day,
        periods = periods,
        weeks = weeks,
        colorIndex = colorIndex,
    )

    private val blank = course(name = "没有节次的课", periods = emptyList())

    private fun wakeUp(courses: List<Course>) = ScheduleExporters.toWakeUpJson(
        courses = courses,
        semester = null,
        timeSlots = emptyList(),
    )

    // —— 可达性：空节次的 Course 不是虚构的输入 ——

    /**
     * 备份文件 / 分享口令都是用户可控输入，而 [BackupCourse.toDomain] 是这条链上
     * 唯一直接产出 `Course` 的出口：`periods` 给了空数组、又没有 v1 的 `startSection`
     * 可回退时，它今天就会原样造出一门没有节次的课（仓库层的 normalize 只在**写库前**
     * 拦一道，这个纯函数出口没人守）。
     */
    @Test
    fun `备份 JSON 里空 periods 会产出一门没有节次的课`() {
        val restored = BackupCourse(
            name = "没有节次的课",
            dayOfWeek = 1,
            periods = emptyList(),
            weeks = listOf(1, 2, 3),
        ).toDomain()
        assertTrue("备份恢复链上确实拿得到空节次课", restored.periods.isEmpty())
        // 兜底值正是它被当成"第 1 节有课"的原因
        assertEquals(1, restored.startPeriod)
    }

    /**
     * 库的**读路径不复校**：`periods` 列是 `TEXT NOT NULL`、没有 CHECK 约束，
     * `Converters.toIntList` 对空白/非数字的值一律返回空表。所以任何一行外部写入
     * 或被改坏的 .db（adb 播种、旧包留下的行）都会以空节次课的身份进入
     * `getDisplayCourses`，直接喂给下面这三条链。
     */
    @Test
    fun `库的 periods 列读回空表时不做任何复核`() {
        val converters = Converters()
        assertEquals(emptyList<Int>(), converters.toIntList(""))
        assertEquals(emptyList<Int>(), converters.toIntList("   "))
        assertEquals(emptyList<Int>(), converters.toIntList("null"))

        val entity = CourseEntity(
            id = 7L,
            name = "没有节次的课",
            teacher = null,
            location = null,
            campus = null,
            dayOfWeek = 1,
            periods = emptyList(),
            weeks = listOf(1),
            colorIndex = 0,
            remark = null,
            sourceGroupKey = null,
            semesterCode = null,
        )
        val row = entity.entityToDomain()
        assertTrue(row.periods.isEmpty())
        assertEquals(1, row.startPeriod)
        // 而且这行原样写得回库：`fromIntList(emptyList())` 就是空白串
        assertEquals("", converters.fromIntList(row.periods))
    }

    // —— B-1 WakeUp JSON：整条不进导出件 ——

    @Test
    fun `导出件里这门课整条缺席且非空课程逐字段不变`() {
        val real = listOf(
            course(),
            course(name = "大学英语听说", day = 3, periods = listOf(9, 10), colorIndex = 5),
        )
        // 逐分钟/逐字节：掺进空节次课之后的整串 JSON，与只有真课的整串完全相同
        assertEquals(wakeUp(real), wakeUp(real + blank))
        assertEquals(wakeUp(real), wakeUp(listOf(blank) + real))
        // 一条假课都没有：不该再出现"从第 1 节开始、长度 0 节"的形状
        val mixed = wakeUp(real + blank)
        assertFalse(mixed, mixed.contains("\"step\":0"))
        // 只剩空节次课时导出件的 courses 数组是空的，而不是里面躺着一门假课
        val onlyBlank = wakeUp(listOf(blank))
        assertTrue(onlyBlank, onlyBlank.endsWith("\"courses\":[]}"))
    }

    /** 节次齐全时的 JSON 形状一并钉住：改了兜底口径不该顺手改到正常课的字段。 */
    @Test
    fun `节次齐全的课程导出的那一行逐字不变`() {
        assertTrue(
            wakeUp(listOf(course())).contains(
                """{"name":"高等数学 A","teacher":"张三","position":"主楼A-101","day":1,""" +
                    """"startNode":1,"step":2,"startWeek":1,"endWeek":3,"type":0,"color":0}""",
            ),
        )
        // 非连续节次仍按起始段截断（既有语义，与本次无关，一起钉住别被改坏）
        assertTrue(
            wakeUp(listOf(course(periods = listOf(1, 2, 9, 10)))).contains("\"startNode\":1,\"step\":2"),
        )
        assertTrue(
            wakeUp(listOf(course(periods = listOf(3, 4)))).contains("\"startNode\":3,\"step\":2"),
        )
    }

    // —— B-2 周网格组件摘要：不再冒出假节次号 ——

    @Test
    fun `周网格摘要不再印出假节次号`() {
        // 旧写法在这里印的是 "1没有节X"
        assertEquals("", weekGridDaySummary(listOf(blank)))
        val real = listOf(
            course("高等数学 A", periods = listOf(1, 2)),
            course("线性代数", periods = listOf(3)),
        )
        assertEquals("1高等A\n3线性代", weekGridDaySummary(real))
        assertEquals(weekGridDaySummary(real), weekGridDaySummary(real + blank))
        // 「＋N」只数真上过场的课：4 门真课压进 3 行是「＋2」，
        // 掺进那门空节次的课若还占一行就会虚报成「＋3」
        val four = real + listOf(
            course("大学英语听说", periods = listOf(5, 6)),
            course("体育", periods = listOf(7)),
        )
        val folded = weekGridDaySummary(four, maxLines = 3)
        assertEquals("1高等A\n3线性代\n＋2", folded)
        assertEquals(folded, weekGridDaySummary(four + blank, maxLines = 3))
    }

    // —— B-3 24 小时时间轴窗口：不被空节次课撑开 ——

    private val slots: List<TimeSlot> = TimeSlotProfile.DEFAULT

    @Test
    fun `时间轴窗口不被空节次课撑开`() {
        val index = TimeSlotIndex(slots)
        // 只有这门画不出卡片的课时，落到"没有可排的课"那句默认窗口 08:00–22:00；
        // 旧写法用 startPeriod/endPeriod 的 1 兜底，硬撑成第 1 节的 08:00–08:45（取整 08:00–09:00）
        assertEquals(8 * 60 to 22 * 60, timeWindowOf(listOf(blank), index))
        // 有真课在场时，多掺一门空节次的课不改变窗口一分钟
        val afternoon = listOf(course(name = "体育课", periods = listOf(9, 10)))
        val baseline = timeWindowOf(afternoon, index)
        // 16:40 上课、18:15 下课 → 下界取整到 16:00、上界取整到 19:00
        assertEquals(16 * 60 to 19 * 60, baseline)
        assertEquals(baseline, timeWindowOf(afternoon + blank, index))
        assertEquals(baseline, timeWindowOf(listOf(blank) + afternoon, index))
        // 节次表里查不到的节次仍走 TimeSlotIndex 那条 08:00–22:15 兜底，本次改动不碰它
        assertEquals(
            8 * 60 to 23 * 60,
            timeWindowOf(listOf(course(periods = listOf(99))), TimeSlotIndex(emptyList())),
        )
    }

    // —— 守卫：`?: 1` 没有被删 ——

    /**
     * `startPeriod` / `endPeriod` 的空表兜底**故意保留**：十来处 `sortedBy { it.startPeriod }`
     * 靠的是"没有节次就排最前"这个次序语义，改成可空会把 `?:` 摊到那串调用点上去。
     * 需要区分"第 1 节"与"根本没有节次"的地方改走 [Course.firstPeriodOrNull]。
     * 这条测试是给下一个人的：别以为兜底被删了，也别把它当位置用。
     */
    @Test
    fun `startPeriod 的 1 兜底仍在，可空出口另算`() {
        val noPeriods = course(periods = emptyList())
        assertEquals(1, noPeriods.startPeriod)
        assertEquals(1, noPeriods.endPeriod)
        assertNull(noPeriods.firstPeriodOrNull)
        assertNull(noPeriods.lastPeriodOrNull)
        // 非空时两条出口给出同一个数，所以换出口不会挪动任何一格
        val real = course(periods = listOf(9, 10, 12))
        assertEquals(real.startPeriod, real.firstPeriodOrNull)
        assertEquals(real.endPeriod, real.lastPeriodOrNull)
    }
}
