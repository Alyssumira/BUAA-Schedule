package com.buaa.schedule.data.import

import com.buaa.schedule.domain.model.Semester
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T216①：`BuaaScheduleParser.parseTermWeeks` 换成「调真路内核」之后的**表驱动 JVM 单测**。
 *
 * 卡面 §2 病灶 A 的裁定是解 ②：这层薄壳不再自己判「哪一行算第 1 周」，而是把 DTO 映射成
 * [BuaaWeekRow] 交给 [resolveBuaaFirstWeek]，再拿结论组 [Semester]。所以这批格子按
 * `BuaaFirstWeekPolicyTest` 那批真路口径对齐摆 —— 同一批行喂给内核与喂给这层壳，结论必须同一枚。
 *
 * 与改前的**三档行为差别**是本文件的主要证据（改前这里全仓第二枚 `minByOrNull
 * { it.serialNumber ?: Int.MAX_VALUE }`，T193 判死的那一形）：
 * 1. 序号全缺、首行不是最早那一格 —— 改前把**列表首行**当第 1 周（哪怕它其实是第 8 周），
 *    改后按 `startDate` 串序取最早；
 * 2. 最早日期被两枚不同串并列那一格 —— 改前静默取首行，改后 `return null`；
 * 3. 日期串带前后空白那一格 —— 改前 `substringBefore(" ")` 会把前导空格留在产物里
 *    （`"  2026-09-07 08:00:00"` → `"  2026-09-07"`，`isNotBlank()` 照样通过），改后内核先 `trim()`。
 *
 * 只做 JVM 判据核对：零 Robolectric、零设备、不读时钟、不碰网络。
 * 「教务页面真回一批没号的行时用户看到什么」归装机（本卡零 adb，见回执的未验到清单）。
 */
class BuaaTermWeeksToSemesterTest {

    private companion object {
        const val TERM = "2026-2027-1"

        /** 真路实测过的开学周：2026-09-07 是周一 */
        const val WEEK1 = "2026-09-07 00:00:00"
        const val WEEK2 = "2026-09-14 00:00:00"
        const val WEEK8 = "2026-09-28 00:00:00"
    }

    /** 一格子 = 一批 `getTermWeeks` 行 + 期望交出去的 Semester（null = 挑不出，必须诚实回 null） */
    private data class Case(
        val name: String,
        val rows: List<BuaaTermWeekDto>,
        val expected: Semester?,
    )

    private fun week(serial: Int? = null, start: String? = null, term: String? = TERM) =
        BuaaTermWeekDto(term = term, startDate = start, serialNumber = serial, name = serial?.let { "第${it}周" })

    private fun semester(startDate: String, totalWeeks: Int, term: String = TERM) = Semester(
        termCode = term,
        termName = term,
        startDate = startDate,
        totalWeeks = totalWeeks,
    )

    @Test
    fun `表驱动逐格 挑得出来就交原样 挑不出就 null`() {
        val cases = listOf(
            // ── 一、真路口径：与 BuaaTermWeeksParserTest 那 19 行同值 ──
            Case(
                "19 行全带序号（真数据形状）⇒ 第 1 周 = 序号最小那行",
                (1..19).map { week(serial = it, start = if (it == 1) WEEK1 else WEEK2) },
                semester("2026-09-07", 19),
            ),
            // ── 二、病灶那一族：改前会拿列表首行当第 1 周 ──
            Case(
                "序号全缺 + 首行其实是第 8 周 ⇒ 取最早日期那行（改前回首行 = 2026-09-28）",
                listOf(week(start = WEEK8), week(start = WEEK1), week(start = WEEK2)),
                semester("2026-09-07", 3),
            ),
            Case(
                "序号全缺 + 最早日期两枚不同串并列 ⇒ null（改前静默取首行）",
                listOf(
                    week(start = "2026-09-07 08:00:00"),
                    week(start = "2026-09-07 00:00:00"),
                    week(start = WEEK2),
                ),
                null,
            ),
            Case(
                "序号全缺 + 最早日期两行逐字同串（一枚重复行）⇒ 收，周数照旧按行数算",
                listOf(week(start = WEEK1), week(start = WEEK1), week(start = WEEK2)),
                semester("2026-09-07", 3),
            ),
            // ── 三、两条来源分家 / 序号并列打架 ──
            Case(
                "序号那条与日期那条分家 ⇒ null（不许只信一条）",
                listOf(week(serial = 1, start = WEEK2), week(serial = 2, start = WEEK1)),
                null,
            ),
            Case(
                "同一枚最小序号被两行不同串并列占着 ⇒ null",
                listOf(
                    week(serial = 1, start = "2026-09-07 08:00:00"),
                    week(serial = 1, start = WEEK1),
                    week(serial = 2, start = WEEK2),
                ),
                null,
            ),
            Case(
                "同一枚最小序号被两行同串占着（重复行）⇒ 收",
                listOf(week(serial = 1, start = WEEK1), week(serial = 1, start = WEEK1)),
                semester("2026-09-07", 2),
            ),
            // ── 四、候选为空：一行可用日期都没有 ──
            Case("空列表 ⇒ null", emptyList(), null),
            Case(
                "没有一行带 startDate ⇒ null",
                listOf(week(serial = 1), week(serial = 2)),
                null,
            ),
            Case(
                "日期全是纯空白串 ⇒ 算没给，null（空串不是「一枚能用的日期」）",
                listOf(week(serial = 1, start = "   "), week(serial = 2, start = "")),
                null,
            ),
            Case(
                "只有带号的行里有日期，没号的行日期缺省 ⇒ 按那条挑（号不是必要条件）",
                listOf(week(serial = null, start = null), week(serial = 7, start = WEEK1)),
                semester("2026-09-07", 2),
            ),
            // ── 五、产物形状：裁时间尾巴 + trim + 不 parse、不归一成周一 ──
            Case(
                "带时间尾巴 ⇒ 只交日期段",
                listOf(week(serial = 1, start = "2026-09-07 13:24:35")),
                semester("2026-09-07", 1),
            ),
            Case(
                "前后带空白的日期串 ⇒ 裁完是干净的 2026-09-07（改前会留下前导空格）",
                listOf(week(serial = 1, start = "  2026-09-07 08:00:00  ")),
                semester("2026-09-07", 1),
            ),
            // ── 六、学期号不猜 ──
            Case(
                "两行给了两枚不同的 term ⇒ 数据自己分家，null（取哪一枚都是猜）",
                listOf(
                    week(serial = 1, start = WEEK1, term = TERM),
                    week(serial = 2, start = WEEK2, term = "2026-2027-2"),
                ),
                null,
            ),
            Case(
                "所有行的 term 都缺省 ⇒ null（没有学期号的 Semester 组不出来）",
                listOf(week(serial = 1, start = WEEK1, term = null)),
                null,
            ),
            Case(
                "被挑中那一行没写 term、另一行写了 ⇒ 认那枚唯一值（改前在这一格回 null）",
                listOf(
                    week(serial = 1, start = WEEK1, term = null),
                    week(serial = 2, start = WEEK2, term = TERM),
                ),
                semester("2026-09-07", 2),
            ),
        )

        for (case in cases) {
            assertEquals("格子「${case.name}」", case.expected, BuaaScheduleParser.parseTermWeeks(case.rows))
        }
    }

    /**
     * 同源尺：同一批行分别喂内核与喂这层薄壳，日期必须**逐字同一枚**。
     *
     * 这一格拦的是「壳里偷偷再判一遍」—— 只要壳用自己的挑法（改前那一形、或任何改名版），
     * 在两枚不同串并列那一档就会给出一个日期而内核给的是 Undetermined，两把尺当场分家。
     */
    @Test
    fun `交出去的日期与真路内核同一枚`() {
        val batches = listOf(
            (1..19).map { week(serial = it, start = if (it == 1) WEEK1 else WEEK2) },
            listOf(week(start = WEEK8), week(start = WEEK1), week(start = WEEK2)),
            listOf(week(serial = 1, start = "2026-09-07 08:00:00"), week(serial = 1, start = WEEK1)),
            listOf(week(serial = 1, start = "  2026-09-07 08:00:00  ")),
        )
        for ((index, rows) in batches.withIndex()) {
            val kernel = resolveBuaaFirstWeek(rows.map { BuaaWeekRow(it.serialNumber, it.startDate) })
            val semester = BuaaScheduleParser.parseTermWeeks(rows)
            when (kernel) {
                is BuaaFirstWeek.Resolved -> assertEquals(
                    "第 ${index + 1} 批：内核挑出的那一枚必须就是壳交出去的那一枚",
                    kernel.startDate,
                    semester?.startDate,
                )
                is BuaaFirstWeek.Undetermined -> assertEquals(
                    "第 ${index + 1} 批：内核判不出（${kernel.reason}）时壳必须回 null，不许自己补一枚日期",
                    null,
                    semester,
                )
            }
        }
    }

    /**
     * 学期号与周数这两格**不在本卡的判据里**，所以单独钉住它们别被连坐改掉：
     * `totalWeeks` 仍是行数（与真路 `weeks.size` 同口径，重复行也占一格 —— 那笔账在台账 #236），
     * `termName` 与 `termCode` 仍是同一枚串（这路本来就没人给它起人话名字）。
     */
    @Test
    fun `周数仍按行数 学期名仍与学期码同一枚`() {
        val rows = listOf(
            week(serial = 1, start = WEEK1),
            week(serial = 1, start = WEEK1),
            week(serial = 2, start = WEEK2),
        )

        val semester = BuaaScheduleParser.parseTermWeeks(rows)!!

        assertEquals(3, semester.totalWeeks)
        assertEquals(TERM, semester.termCode)
        assertEquals(TERM, semester.termName)
        assertEquals(0L, semester.id)
    }
}
