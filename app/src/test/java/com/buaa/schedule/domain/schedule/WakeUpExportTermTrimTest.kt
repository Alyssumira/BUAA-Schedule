package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T222 病灶 3（#307）**WakeUp 那条导出链**的表驱动格子。
 *
 * 病在哪（卡面原文的口径）：`toWakeUpJson` 里 `startWeek`/`endWeek` 吃的是**整条** `course.weeks`，
 * 而同一份 JSON 下面那行写的是 `"maxWeek":$totalWeeks` —— 自己声明这学期 19 周，
 * 却给对方 App 递一条 `endWeek:21`：WakeUp 按 `startDate` + `startWeek..endWeek` + `type`
 * 推日期，学期结束之后那几天在**对方**表里照样排课，那是离开本应用的数据、我们无从收回。
 *
 * 现在周次先过 [TermWeekGate.weeksInsideTerm]（与排铃两条链、冲突判定、ICS/系统日历同一枚符号），
 * 递进去的上界**就是这份导出件自己写出去的那一枚 `maxWeek`** ⇒ 两行不可能再互相打脸。
 * 没有学期行那一档也按它判：那一档判不出的是"这学期到底几周"，不是"这一周超出我自己写的范围"。
 *
 * ⚠️ [cells] 里那几档 `expected = null` 的格子钉的是**今天还留着的那一格**：
 * 整门课都在学期外时它照旧出场。把它判成"没进导出件"要多出一档 drop，
 * 而那要连着改 `WakeUpDropTally`（档位册子）与 `wakeUpExportMessage`（那句"门 / 段"措辞是 #308 的地盘），
 * 两枚文件都不在本卡白名单里 ⇒ 逐字登记在回执「需要跨卡配合」。这一格是**明写的现状**，不是漏网。
 */
class WakeUpExportTermTrimTest {

    private val semesterStart = "2026-09-07"
    private val termWeeks = 19

    private fun semester(weeks: Int = termWeeks) = Semester(
        termCode = "T",
        termName = "本学期",
        startDate = semesterStart,
        totalWeeks = weeks,
    )

    private fun course(weeks: List<Int>, periods: List<Int> = listOf(1, 2), day: Int = 1) = Course(
        id = 1L,
        name = "高数",
        teacher = "张三",
        location = "J3-101",
        dayOfWeek = day,
        periods = periods,
        weeks = weeks,
    )

    private class Exported(val maxWeek: Int, val entries: List<Entry>) {
        data class Entry(val startWeek: Int, val endWeek: Int, val type: Int)
    }

    /** 只把三枚周次字段捞出来：本卡动的是它们，别的（节次、颜色、day）一格都不该变。 */
    private fun parse(json: String): Exported {
        val maxWeek = Regex("\"maxWeek\":(\\d+)").find(json)?.groupValues?.get(1)?.toInt()
            ?: error("导出件里没有 maxWeek 那一行：$json")
        val entries = Regex("\"startWeek\":(-?\\d+),\"endWeek\":(-?\\d+),\"type\":(-?\\d+)").findAll(json).map {
            Exported.Entry(
                it.groupValues[1].toInt(),
                it.groupValues[2].toInt(),
                it.groupValues[3].toInt(),
            )
        }.toList()
        return Exported(maxWeek, entries)
    }

    private fun export(courses: List<Course>, row: Semester?): Exported =
        parse(ScheduleExporters.toWakeUpJson(courses, row, emptyList()))

    private data class Cell(
        val name: String,
        val weeks: List<Int>,
        val expected: Exported.Entry?,
    )

    private val cells = listOf(
        // ① 界内
        Cell("界内整学期：1..19 ⇒ startWeek 1 / endWeek 19 一字未动", (1..19).toList(), Exported.Entry(1, 19, 0)),
        Cell("界内那一周的边界：只有第 19 周 ⇒ 19-19（上界闭区间，与展示侧同一档）", listOf(19), Exported.Entry(19, 19, 1)),
        Cell("界内单周：1、3、5 ⇒ 仍是单周（type 1）、endWeek 5", listOf(1, 3, 5), Exported.Entry(1, 5, 1)),
        Cell("界内双周：2、4 ⇒ 仍是双周（type 2）、endWeek 4", listOf(2, 4), Exported.Entry(2, 4, 2)),
        // ② 越上界（病灶那一格）
        Cell(
            "越上界混着界内：20/21 被裁 ⇒ 对方 App 再也排不到学期结束之后那几周",
            listOf(1, 2, 20, 21), Exported.Entry(1, 2, 0),
        ),
        Cell("只在学期外那一周（第 20 周）⇒ 界内不剩，走「照旧原样出场」那一档", listOf(20), null),
        Cell("极端越界（备份里用户可控的第 400 周）⇒ 界内没有一周留下", listOf(400), null),
        // ③ 越下界
        Cell("第 0 周 ⇒ 界内不认这一周（屏上没有第 0 周）", listOf(0), null),
        Cell("负周次（教务脏数据）⇒ 界内不认", listOf(-3), null),
        Cell("越下界混着界内：只留第 3 周", listOf(-1, 0, 3), Exported.Entry(3, 3, 1)),
        // ④ 空周次（#237 那一档，形状必须不变）
        Cell("空周次 ⇒ 整条不进导出件（两档 drop 里的周次那一档）", emptyList(), null),
    )

    @Test
    fun `六档格子逐条对账`() {
        val row = semester()
        for (cell in cells) {
            val exported = export(listOf(course(cell.weeks)), row)
            if (cell.expected == null) {
                if (cell.weeks.isEmpty()) {
                    assertTrue(
                        "${cell.name}：空周次那一档（#237）整条不进导出件 —— 它要是又出场了，" +
                            "就是**导出侧把「裁周次」写成了「造周次」**",
                        exported.entries.isEmpty(),
                    )
                } else {
                    val single = exported.entries.singleOrNull()
                    assertEquals(
                        "${cell.name}（weeks=${cell.weeks}）：界内一周不剩 ⇒ 今天**照旧原样出场**，" +
                            "这一格是明写的现状（把它判成第三档 drop 要连着改报数那两枚文件 ⇒ 回执跨卡那一格）。" +
                            "它要是变了形状，说明有人在这一档上动了刀却没改报数",
                        Exported.Entry(cell.weeks.minOrNull()!!, cell.weeks.maxOrNull()!!, single?.type ?: -1),
                        Exported.Entry(single?.startWeek ?: -1, single?.endWeek ?: -1, single?.type ?: -1),
                    )
                }
                continue
            }
            assertEquals(
                "${cell.name}（weeks=${cell.weeks}）—— 交回的 startWeek/endWeek/type 不对，" +
                    "就是**导出侧没并尺**（或裁过头把界内那几周也裁了）",
                listOf(cell.expected),
                exported.entries,
            )
        }
    }

    /** 写进 `maxWeek` 的那一枚数与裁周次用的那一枚必须是同一个出处（学期行 / 没有学期行时的常数上限）。 */
    @Test
    fun declaredMaxWeekIsTheBoundTheWeeksWereClippedAgainst() {
        val row = semester(weeks = 12)
        val exported = export(listOf(course((1..20).toList())), row)
        assertEquals("maxWeek 仍是学期行那 12 周（本卡没动它）", 12, exported.maxWeek)
        assertEquals(
            "endWeek 越过自己声明的 maxWeek = 那份导出件自相矛盾，对方 App 就在学期结束后还排课：" + exported.entries,
            listOf(Exported.Entry(1, 12, 0)),
            exported.entries,
        )
    }

    /**
     * **没有学期行**那一档：`totalWeeks` 取不到 ⇒ 用 `CourseConstraints.MAX_TOTAL_WEEKS`，
     * 而那一枚正是同时写进 `maxWeek` 的数 ⇒ 判的还是"这份导出件自己声明的范围"，
     * 不是"随便猜一个学期"（台账 #236 那族 `?: 20` 造出「这学期 20 周」的账就断在这里）。
     */
    @Test
    fun withoutSemesterRowTheDeclaredCeilingIsStillHonoured() {
        val ceiling = CourseConstraints.MAX_TOTAL_WEEKS
        val exported = export(listOf(course(listOf(1, 2, ceiling + 5))), null)
        assertEquals("没有学期行时 maxWeek 写的是那枚上限常数", ceiling, exported.maxWeek)
        assertEquals(
            "超出自己声明的 maxWeek 的那一周照样得裁掉（没有学期行 ≠ 什么都不判）",
            listOf(Exported.Entry(1, 2, 0)),
            exported.entries,
        )
        // ⚠️ 夹具必须是**奇偶混合**的三周（崩卡那枚代理写的是 `listOf(1, 25)`，两枚都是奇数 ⇒
        // `type` 走的是"单周"那一支 = 1，而它把期望写成 0 ⇒ 这一格当场红，与裁周次这一维无关）。
        // 期望那三格（startWeek 1 / endWeek 25 / type 0）照崩卡原文不动，改的是夹具：
        // 这一格判的是"1..maxWeek 之内的周次一律留下"，奇偶混合才不越界到 type 那一档去。
        val insideCeiling = export(listOf(course(listOf(1, 24, 25))), null)
        assertEquals(
            "而 1..maxWeek 之内的周次一律留下：判不出「这学期几周」时不裁界内",
            listOf(Exported.Entry(1, 25, 0)),
            insideCeiling.entries,
        )
    }

    /**
     * 卡面点名的"更重的一格"之外，还要一条**不连坐**的证据：
     * 同批课程里别的课、以及同一门课的 day/startNode/step/color 都不能因为裁周次而变。
     * 判据：把一条越界课与一条界内课放在一起 ⇒ 界内那条的 JSON 片段逐字节不变。
     */
    @Test
    fun clippingWeeksChangesNothingElseInTheExportedCourse() {
        val inTerm = course(listOf(1, 2))
        val withPhantom = course(listOf(1, 2, 20, 21))
        val jsonInTerm = ScheduleExporters.toWakeUpJson(listOf(inTerm), semester(), emptyList())
        val jsonPhantom = ScheduleExporters.toWakeUpJson(listOf(withPhantom), semester(), emptyList())

        val bodyInTerm = jsonInTerm.substringAfter("\"courses\":[")
        val bodyPhantom = jsonPhantom.substringAfter("\"courses\":[")
        assertEquals(
            "除 endWeek 之外整条 JSON 逐字节相同 ⇒ 裁周次没有顺手改节次 / 星期 / 颜色 / 门数",
            bodyInTerm.replace("\"endWeek\":2", "«end»"),
            bodyPhantom.replace("\"endWeek\":2", "«end»"),
        )
        assertTrue("界内那条的 endWeek 仍是 2（没被裁过头）", bodyInTerm.contains("\"endWeek\":2"))
        assertTrue("越界那条的 endWeek 被收在 2 ⇒ 不再写 21", !bodyPhantom.contains("\"endWeek\":21"))
    }

    /** 两档 drop 的形状不许被本卡改成三档（`WakeUpExportDropShapeGuardTest` 按源码数，这枚按输出数）。 */
    @Test
    fun dropCountStaysTwoCategories() {
        val noPeriod = course(emptyList(), periods = emptyList())
        val noWeek = course(emptyList())
        val outOfTerm = course(listOf(20, 21))
        val entries = export(listOf(noPeriod, noWeek, outOfTerm), semester()).entries
        assertEquals(
            "没节次、没周次那两档照旧整条不写（导出的门数可以少于入参，调用方按档报数）；" +
                "整门课在学期外那一档今天**照旧出场** ⇒ 本卡没把 drop 从两档改成三档，" +
                "报数那本册子（WakeUpDropTally）因此一字不用动",
            1,
            entries.size,
        )
        assertEquals(listOf(Exported.Entry(20, 21, 0)), entries)
    }
}
