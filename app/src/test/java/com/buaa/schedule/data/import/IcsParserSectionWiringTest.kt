package com.buaa.schedule.data.import

import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.TimeSlotProfile
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T192②：ICS 导入的节次判据**接线行为**（纯 JVM，跑真实的 `IcsParser.parse`）。
 *
 * [IcsSectionPolicyTest] 钉的是内核那一判本身，本文件钉的是调用点有没有真的按它走：
 * 落不进节次表的那条事件必须**整条不入库**（旧形状在这里被安成第 1 节，于是早自习真的写库、
 * 真的上课表），而留痕必须真的被念出来（`onSkippedEvent`）。
 *
 * ## 为什么留痕是参数而不是体内一句 `Log.w`
 *
 * `:app` 的 JVM 单测面只有 `junit`：没有 Robolectric，也没开 `returnDefaultValues`，
 * `android.util.Log` 在那里是一枚会抛 `Method w in android.util.Log not mocked` 的桩。
 * 留痕如果直接写死成 `Log.w`，「跳过那一等到底有没有留痕」这一档在 JVM 上就**一行也测不到**
 * （测它就把整条 parse 炸掉），只能退回读源码文本。做成带默认值的参数后：
 * 设备侧默认仍是 `Log.w(TAG, …)`（`ui/ScheduleViewModel.importIcs` 的实参表一个字没动，
 * 本卡不许碰那份文件），JVM 侧本文件注入一个 recorder 逐条断言。
 *
 * ## 两侧的红臂
 *
 * - 朝**宽**（把 null 闸换成 `?.number ?: 1`，旧形状）⇒ 「早自习不再被安成第 1 节」那格红
 *   （课程数从 1 变 2），「整表时间非法」那格红，留痕两格跟着红；
 * - 朝**窄**（把「晚于最后一节折进最后一节」也一并判成落不进表）⇒
 *   「22:30 的延自习仍在第 14 节」那格红 —— 事实 3 那一等是旧注释授权过的，不许顺手改。
 *
 * 档位如实写明：本文件证明的是**解析产出的课程形状 + 留痕被念出**；
 * 「用户在屏幕上看不见这几条被跳过」那一档**没做**（要动 `ScheduleViewModel`，本卡红线），
 * 真机导入一份含早自习的 .ics 归编排者装机。
 */
class IcsParserSectionWiringTest {

    private companion object {
        val SEMESTER_START: LocalDate = LocalDate.of(2026, 9, 7) // 周一

        /** 一节早自习（07:00–07:40）+ 一门正课（08:00–08:45）：只有后者落得进节次表 */
        val MORNING_STUDY_AND_NORMAL_CLASS = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            SUMMARY:早自习
            DTSTART:20260907T070000
            DTEND:20260907T074000
            RRULE:FREQ=WEEKLY;COUNT=16
            END:VEVENT
            BEGIN:VEVENT
            SUMMARY:高等数学
            DTSTART:20260907T080000
            DTEND:20260907T084500
            RRULE:FREQ=WEEKLY;COUNT=16
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        /** 晚于最后一节（22:30–23:00）：旧注释授权「用最近的前一个节次」，本卡不改这一等 */
        val LATE_EVENT = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            SUMMARY:延自习
            DTSTART:20260907T223000
            DTEND:20260907T230000
            RRULE:FREQ=WEEKLY;COUNT=4
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        /** DTEND 早于 DTSTART 的畸形事件：诚实格（endSection < startSection）那一档 */
        val REVERSED_TIME_EVENT = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            SUMMARY:时间倒填的坏事件
            DTSTART:20260907T100000
            DTEND:20260907T090000
            RRULE:FREQ=WEEKLY;COUNT=4
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        /**
         * 「节次号升序但 start 不升序」的作息表：`TimeSlotDao.getAll()` 的
         * `ORDER BY number` 交回的就是这种顺序（`SettingsScreen` 逐格自由填 `HH:mm`，
         * 校验只有 `isValidTimeSlot`，没有任何跨行单调性检查）。
         */
        val NON_MONOTONIC_SLOTS = listOf(
            TimeSlot(number = 1, startTime = "09:00", endTime = "09:45"),
            TimeSlot(number = 2, startTime = "08:00", endTime = "08:45"),
            TimeSlot(number = 3, startTime = "13:00", endTime = "13:45"),
        )

        /** 12:30–12:45 不在任何节次里：按时间序的前一节是第 1 节（09:00 开始），按列表序是第 2 节 */
        val BETWEEN_SLOTS_EVENT = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            SUMMARY:午后讲座
            DTSTART:20260907T123000
            DTEND:20260907T124500
            RRULE:FREQ=WEEKLY;COUNT=4
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
    }

    // ─────────────── ① 早于第一节课：整条不入库（本卡的承重） ───────────────

    @Test
    fun `早自习不再被安成第 1 节 整条不入库`() {
        val traces = mutableListOf<String>()
        val courses = IcsParser.parse(
            content = MORNING_STUDY_AND_NORMAL_CLASS,
            semesterStart = SEMESTER_START,
            termCode = "2026-2027-1",
            onSkippedEvent = { traces += it },
        )

        assertEquals(
            "解析产出的课程必须只有落得进节次表的那一门（实到 ${courses.map { it.name }}）。" +
                "多出来的一门「早自习」= 判据又凭空造出一节：旧形状 `previous?.number ?: 1` 在 " +
                "07:00 < 第一节 08:00 那一支交第 1 节，于是它进 courses、被 confirmPendingImport 写库、" +
                "出现在课表周一第一节的位置上，连 colorIndex 都按那枚假节次号算",
            listOf("高等数学"),
            courses.map { it.name },
        )
        assertEquals(
            "落得进节次表的那一门仍是第 1 节（08:00 上课、08:45 下课）",
            listOf(1),
            courses.single().periods,
        )
    }

    @Test
    fun `跳过的那一条留了痕 而且留痕说得出为什么`() {
        val traces = mutableListOf<String>()
        IcsParser.parse(
            content = MORNING_STUDY_AND_NORMAL_CLASS,
            semesterStart = SEMESTER_START,
            termCode = "T",
            onSkippedEvent = { traces += it },
        )

        assertEquals(
            "整条跳过的事件数与留痕数必须一对一（落不进节次表的那条一条不许哑）：实到 " + traces.size +
                " 条留痕。0 条 = 跳过没人知道（本卡明令要留痕）；2 条 = 把成课的那条也念成跳过",
            1,
            traces.size,
        )
        val trace = traces.single()
        assertTrue("留痕里必须点名是哪条事件（用户/排查者按 SUMMARY 找原文）：实到「$trace」", trace.contains("早自习"))
        assertTrue("留痕必须交代内核结论 BeforeFirstSlot（早于第一节课）：实到「$trace」", trace.contains("BeforeFirstSlot"))
        assertTrue("留痕必须带上那枚钟点，否则 Logcat 里无从复核：实到「$trace」", trace.contains("07:00"))
    }

    // ─────────────── ② 节次表为空：一条也不许成课 ───────────────

    /**
     * `IcsParser.slotTable` 用 `mapNotNull` 过 `LocalTime.parse`：整表时间都写坏时交回空表，
     * 旧形状在这里给**每一条**事件都安上第 1 节（用户看到的是一课表挤在同一节里）。
     */
    @Test
    fun `节次表全被 parse 掉时给 NoSlots 整条跳过而不是人人安成第 1 节`() {
        val traces = mutableListOf<String>()
        val courses = IcsParser.parse(
            content = MORNING_STUDY_AND_NORMAL_CLASS,
            semesterStart = SEMESTER_START,
            termCode = "T",
            timeSlots = listOf(
                TimeSlot(number = 1, startTime = "99:99", endTime = "99:99"),
                TimeSlot(number = 2, startTime = "not-a-time", endTime = "also-broken"),
            ),
            onSkippedEvent = { traces += it },
        )

        assertEquals(
            "表里一枚节次都读不出来时不产任何课程（实到 ${courses.map { it.name }}）：" +
                "空表下任何节次号都是编的，旧形状正是这里给每条事件安第 1 节",
            emptyList<String>(),
            courses.map { it.name },
        )
        assertEquals("两条事件都被跳过 ⇒ 两条留痕（实到 ${traces.size} 条）", 2, traces.size)
        assertTrue(
            "留痕必须交代是 NoSlots 这一等，不是早于第一节：\n  " + traces.joinToString("\n  "),
            traces.all { it.contains("NoSlots") },
        )
    }

    // ─────────────── ③ 事实 3：晚于最后一节仍折进最后一节（有意的） ───────────────

    @Test
    fun `晚于最后一节的活动仍折进第 14 节 这一等是注释授权过的`() {
        val traces = mutableListOf<String>()
        val courses = IcsParser.parse(
            content = LATE_EVENT,
            semesterStart = SEMESTER_START,
            termCode = "T",
            onSkippedEvent = { traces += it },
        )

        assertEquals(
            "22:30–23:00 在最后一节（21:30–22:15）之后：按旧注释「找不到则用最近的前一个节次」" +
                "折进第 14 节，成课（实到 ${courses.map { it.name to it.periods }}）。" +
                "把它一并判成跳过 = 延自习、晚间活动整条静默消失，那是把有意的一等当 bug 改",
            listOf("延自习" to listOf(14)),
            courses.map { it.name to it.periods },
        )
        assertEquals("这一等不是跳过，一条留痕都不许有（实到 ${traces.size} 条）", 0, traces.size)
    }

    // ─────────────── ④ 事实 4：诚实格在可空内核之后仍然成立 ───────────────

    /**
     * DTEND 早于 DTSTART：两侧都落得进节次表（10:00 在第 3 节内、09:00 在第 2 节内），
     * 于是 `endSection < startSection` 那一格被走到 —— 它必须在 null 闸**之外**照旧生效，
     * 且不占用「跳过」那一档的留痕。
     */
    @Test
    fun `DTEND 早于 DTSTART 的畸形事件仍走诚实格 不混进跳过那一档`() {
        val traces = mutableListOf<String>()
        val courses = IcsParser.parse(
            content = REVERSED_TIME_EVENT,
            semesterStart = SEMESTER_START,
            termCode = "T",
            onSkippedEvent = { traces += it },
        )

        assertEquals(
            "倒序时间不产课程（实到 ${courses.map { it.name }}）：`if (endSection < startSection) emptyList()` " +
                "那一格 + 下面的 `periods.isNotEmpty()` 闸",
            emptyList<String>(),
            courses.map { it.name },
        )
        assertEquals(
            "这一等的两枚节次号都是非空的（都落得进表），**不属于**「落不进节次表」那一档：" +
                "留痕数必须 0（实到 ${traces.size} 条）。不为 0 说明 null 闸被搬到了诚实格之后、" +
                "或者有人把两档并成了一档",
            0,
            traces.size,
        )
    }

    // ─────────────── ⑤ 事实 2 端到端：同一张表两种列表序，产出必须逐字段相同 ───────────────

    @Test
    fun `节次表喂进去的顺序不参与产出 乱序与升序逐字段相同`() {
        val traces = mutableListOf<String>()
        val ascending = IcsParser.parse(
            content = BETWEEN_SLOTS_EVENT,
            semesterStart = SEMESTER_START,
            termCode = "T",
            timeSlots = NON_MONOTONIC_SLOTS,
            onSkippedEvent = { traces += it },
        )
        val scrambled = IcsParser.parse(
            content = BETWEEN_SLOTS_EVENT,
            semesterStart = SEMESTER_START,
            termCode = "T",
            timeSlots = NON_MONOTONIC_SLOTS.reversed(),
            onSkippedEvent = { traces += it },
        )

        assertEquals(
            "同一张作息表、两种列表顺序，产出的课程必须逐字段相同（Course 是 data class，" +
                "整枚对象直接比）：升序给 $ascending，乱序给 $scrambled。不同 = 判据吃了列表顺序",
            ascending,
            scrambled,
        )
        assertEquals(
            "12:30 不在任何节次里，候选是第 1 节（09:00 开始）与第 2 节（08:00 开始）：" +
                "「最近的前一节」按时间序应当是第 1 节（实到 ${
                    ascending.singleOrNull()?.periods
                }）。旧的 `lastOrNull{}` 按列表序取到的是列表里最后一个满足条件的第 2 节 —— " +
                "而 DAO 的 SQL 保证的正是 number 升序，不是 start 升序（事实 2 的账）",
            listOf(1),
            ascending.single().periods,
        )
        assertEquals("两遍都成课 ⇒ 零留痕（实到 ${traces.size} 条）", 0, traces.size)
    }

    @Test
    fun `默认作息表整表反转喂进去 产出与升序相同`() {
        val forward = IcsParser.parse(
            content = MORNING_STUDY_AND_NORMAL_CLASS,
            semesterStart = SEMESTER_START,
            termCode = "T",
            timeSlots = TimeSlotProfile.DEFAULT,
            onSkippedEvent = { },
        )
        val backward = IcsParser.parse(
            content = MORNING_STUDY_AND_NORMAL_CLASS,
            semesterStart = SEMESTER_START,
            termCode = "T",
            timeSlots = TimeSlotProfile.DEFAULT.reversed(),
            onSkippedEvent = { },
        )
        assertEquals(
            "默认 14 节表整体反转后，产出的每一门课必须一模一样（实到 $forward vs $backward）：" +
                "这一格是「新内核不许依赖列表顺序」在 parse 这一层的落地",
            forward,
            backward,
        )
        assertEquals(
            "正课仍落在第 1 节（实到 ${forward.map { it.name to it.periods }}；" +
                "早自习那一条本就该被整条跳过，不该在这里冒出来）",
            listOf("高等数学" to listOf(1)),
            forward.map { it.name to it.periods },
        )
    }
}
