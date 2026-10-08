package com.buaa.schedule.ui

import com.buaa.schedule.data.backup.BackupCourse
import com.buaa.schedule.data.backup.toDomain
import com.buaa.schedule.data.local.Converters
import com.buaa.schedule.data.local.CourseEntity
import com.buaa.schedule.data.local.toDomain as entityToDomain
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.schedule.CourseConstraints
import com.buaa.schedule.domain.schedule.ScheduleExporters
import com.buaa.schedule.domain.schedule.WakeUpDrop
import com.buaa.schedule.domain.schedule.WakeUpDropTally
import com.buaa.schedule.ui.settings.wakeUpExportMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T203：WakeUp 导出件把课丢下的**两档**（没有节次 = T27、没有周次 = #237）与调用方那句报数。
 *
 * ## 病在哪
 *
 * `course.weeks` 为空时导出器今天写的是 `startWeek:1, endWeek:1, type:0` —— 那枚 `?: 1` 是凭空造值，
 * 而这条数据**离开本应用**：WakeUp 照单收下，对方 App 里就多出一门"第 1-1 周"的课，我们无从收回。
 * 同文件 `:48-55` 早就把同一形状判死过一遍（`startNode:1,step:0`，T27），口径是**这一项不出场**。
 * 本卡把空周次那一档折进同一条口径，并把调用方的报数从"只数没节次那一档"改成两档各数一次
 * （一门课既没节次又没周次只落一档，所以不会念成两门）。
 *
 * ## 四象限各摆一格
 *
 * `(periods 空?, weeks 空?)` 的四种组合同时打三条链：导出件里那一门**在不在**、
 * [WakeUpDropTally] 把它记进哪一档、[wakeUpExportMessage] 端出来的是哪一句。
 * 三条链读的是同一本账 ⇒ "导出件少了一门而屏幕上仍承诺 N 门"那种错配被逐格钉死。
 * 全部真调生产函数（导出器、判据、文案），不在测试里重拼一遍。
 *
 * ⚠️ 零 adb：两档输入的可达性由下面的可达性格证（库的读路径 + 备份出口），装机那一档本卡不复现。
 */
class WakeUpExportDropQuadrantTest {

    private fun course(
        name: String = "高等数学 A",
        day: Int = 1,
        periods: List<Int> = listOf(1, 2),
        weeks: List<Int> = listOf(1, 2, 3),
    ) = Course(
        id = name.hashCode().toLong(),
        name = name,
        teacher = "张三",
        location = "主楼A-101",
        dayOfWeek = day,
        periods = periods,
        weeks = weeks,
        colorIndex = 0,
    )

    private fun wakeUp(courses: List<Course>): String =
        ScheduleExporters.toWakeUpJson(courses = courses, semester = null, timeSlots = emptyList())

    /** 导出件里课程数组的条数：`"day":` 只在课程对象里出现（顶层与 tableInfo 都没有这一列） */
    private fun exportedEntries(json: String): Int {
        val coursesArray = json.substringAfter("\"courses\":[")
        return coursesArray.split("\"day\":").size - 1
    }

    private class Cell(
        val label: String,
        val periods: List<Int>,
        val weeks: List<Int>,
        val expectExported: Boolean,
        val expectDrop: WakeUpDrop?,
        val expectClause: String,
    )

    private val quadrants = listOf(
        Cell(
            label = "都有（正常课，一格都不该动）",
            periods = listOf(1, 2),
            weeks = listOf(1, 2, 3),
            expectExported = true,
            expectDrop = null,
            expectClause = "WakeUp JSON 已导出（1 段排课）",
        ),
        Cell(
            label = "只缺节次（T27 那一档）",
            periods = emptyList(),
            weeks = listOf(1, 2, 3),
            expectExported = false,
            expectDrop = WakeUpDrop.NO_PERIOD,
            expectClause = "WakeUp JSON 已导出（0 段排课；另有 1 段没有节次、无法定位，未写入）",
        ),
        Cell(
            label = "只缺周次（#237 本卡那一档）",
            periods = listOf(1, 2),
            weeks = emptyList(),
            expectExported = false,
            expectDrop = WakeUpDrop.NO_WEEK,
            expectClause = "WakeUp JSON 已导出（0 段排课；另有 1 段没有周次、无法定位，未写入）",
        ),
        Cell(
            label = "都缺（一门只算一次）",
            periods = emptyList(),
            weeks = emptyList(),
            expectExported = false,
            expectDrop = WakeUpDrop.NO_PERIOD,
            expectClause = "WakeUp JSON 已导出（0 段排课；另有 1 段没有节次、无法定位，未写入）",
        ),
    )

    // —— ① 四象限：导出件里那一门在不在 ——

    @Test
    fun `四象限的出场与否`() {
        for (cell in quadrants) {
            val row = course(name = cell.label, periods = cell.periods, weeks = cell.weeks)
            val json = wakeUp(listOf(row))
            assertEquals(
                "「${cell.label}」：periods=${cell.periods}、weeks=${cell.weeks} ⇒ 导出件里该有几段排课。" +
                    "\n实到整串：$json",
                if (cell.expectExported) 1 else 0,
                exportedEntries(json),
            )
            // T279①（N6）重钉：这一格此前按 `course.name` 写死，而导出件的 `name` 现在读
            // **displayName**（别名优先，无别名时逐字回退到 name ⇒ 本文件的夹具没有别名，
            // 两个值今天仍相等，但尺子指的是"上屏那一枚名字"，不再是教务原名）。
            // 带别名那一档的正格在 `WakeUpJsonTextTest`（别名进导出件 + 控制字符不出场一起打）。
            assertEquals(
                "「${cell.label}」：那一门的名字在导出件里出场与否必须与上面的条数一致（出场的是 displayName）",
                cell.expectExported,
                json.contains("\"name\":\"${row.displayName}\""),
            )
        }
    }

    @Test
    fun `空周次那档不许留下任何造出来的周次`() {
        val json = wakeUp(listOf(course(name = "没有周次的课", weeks = emptyList())))
        assertTrue("导出件里该是空数组，实到：$json", json.endsWith("\"courses\":[]}"))
        // 病灶那三枚值一枚都不许出现（改前这一条里躺着 startWeek:1,endWeek:1,type:0）
        assertFalse(json.contains("\"startWeek\":1"))
        assertFalse(json.contains("\"endWeek\":1"))
        assertFalse(json.contains("\"startNode\""))
    }

    @Test
    fun `掺进被丢下的课不该改动真课那几门一个字节`() {
        val real = listOf(
            course(name = "高等数学 A", periods = listOf(1, 2), weeks = listOf(1, 2, 3)),
            course(name = "大学英语听说", day = 3, periods = listOf(9, 10), weeks = listOf(2, 4, 6)),
        )
        val baseline = wakeUp(real)
        val dropped = listOf(
            course(name = "没有节次", periods = emptyList()),
            course(name = "没有周次", weeks = emptyList()),
            course(name = "两档都缺", periods = emptyList(), weeks = emptyList()),
        )
        assertEquals(baseline, wakeUp(real + dropped))
        assertEquals(baseline, wakeUp(dropped + real))
        assertEquals(baseline, wakeUp(listOf(dropped[0], real[0], dropped[1], real[1], dropped[2])))
        assertEquals(2, exportedEntries(wakeUp(real + dropped)))
    }

    // —— ② 四象限：判据落在哪一档（一门只落一档） ——

    @Test
    fun `四象限的档位归属`() {
        for (cell in quadrants) {
            val c = course(name = cell.label, periods = cell.periods, weeks = cell.weeks)
            assertEquals("「${cell.label}」应记进哪一档", cell.expectDrop, WakeUpDropTally.wakeUpDrop(c))
            assertEquals(
                "「${cell.label}」：判据与导出器的出场与否必须同判（不同判 = 报数与写出来的段数错配）",
                cell.expectExported,
                WakeUpDropTally.wakeUpDrop(c) == null,
            )
        }
    }

    @Test
    fun `两档各计一次且合起来正好是丢下的门数`() {
        val courses = listOf(
            course(name = "真课一", periods = listOf(1, 2), weeks = listOf(1, 2)),
            course(name = "真课二", periods = listOf(3), weeks = listOf(3, 4, 5)),
            course(name = "缺节次一", periods = emptyList(), weeks = listOf(1)),
            course(name = "缺节次二", periods = emptyList(), weeks = listOf(2, 4)),
            course(name = "缺周次", periods = listOf(5, 6), weeks = emptyList()),
            course(name = "两档都缺", periods = emptyList(), weeks = emptyList()),
        )
        val counts = WakeUpDropTally.tally(courses)
        // "两档都缺"那一门只落 NO_PERIOD：2 门缺节次 + 1 门都缺 = 3，不是 4
        assertEquals("没有节次那一档（含两档都缺的那门，一门只落一档）", 3, counts.noPeriodCourses)
        assertEquals("没有周次那一档（不含两档都缺的那门）", 1, counts.noWeekCourses)
        assertEquals("总丢下 4 段（绝不重复计数）", 4, counts.droppedCourses)
        assertEquals("导出件真写出 2 段", 2, exportedEntries(wakeUp(courses)))
        assertEquals(
            "报数的账必须与导出件对得上：courses.size − 丢下的段数 = 写出去的段数",
            courses.size - counts.droppedCourses,
            exportedEntries(wakeUp(courses)),
        )
    }

    // —— ③ 四象限：那句报数念的是什么 ——

    @Test
    fun `四象限端出的那句`() {
        for (cell in quadrants) {
            val courses = listOf(course(name = cell.label, periods = cell.periods, weeks = cell.weeks))
            val counts = WakeUpDropTally.tally(courses)
            assertEquals(
                "「${cell.label}」那句（total=${courses.size}、noPeriod=${counts.noPeriodCourses}、noWeek=${counts.noWeekCourses}）",
                cell.expectClause,
                wakeUpExportMessage(courses.size, counts.noPeriodCourses, counts.noWeekCourses),
            )
        }
    }

    @Test
    fun `两档都有缺失时同一句里各念一次`() {
        val courses = listOf(
            course(name = "真课一", periods = listOf(1, 2), weeks = listOf(1, 2)),
            course(name = "真课二", periods = listOf(3), weeks = listOf(3, 4)),
            course(name = "真课三", periods = listOf(5), weeks = listOf(5)),
            course(name = "缺节次", periods = emptyList(), weeks = listOf(1)),
            course(name = "两档都缺", periods = emptyList(), weeks = emptyList()),
            course(name = "缺周次", periods = listOf(7), weeks = emptyList()),
        )
        val counts = WakeUpDropTally.tally(courses)
        assertEquals(
            "3 段真课出场、2 段因节次被丢、1 段因周次被丢（那句话整体端出来）",
            "WakeUp JSON 已导出（3 段排课；另有 2 段没有节次、1 段没有周次，都无法定位，未写入）",
            wakeUpExportMessage(courses.size, counts.noPeriodCourses, counts.noWeekCourses),
        )
        assertEquals(3, exportedEntries(wakeUp(courses)))
    }

    @Test
    fun `没有缺失的那一句一个字节都没动过`() {
        // T181② 钉过的两支之一：本卡补第二档不许把它顺手改掉
        assertEquals(
            "WakeUp JSON 已导出（18 段排课）",
            wakeUpExportMessage(totalFragments = 18, noPeriodFragments = 0, noWeekFragments = 0),
        )
        assertEquals(
            "WakeUp JSON 已导出（4 段排课；另有 1 段没有节次、无法定位，未写入）",
            wakeUpExportMessage(totalFragments = 5, noPeriodFragments = 1, noWeekFragments = 0),
        )
    }

    // —— ④ 字段形状：正常课的 week 三档判据不被本卡碰坏 ——

    @Test
    fun `非空周次的三种 type 逐字不变`() {
        val odd = wakeUp(listOf(course(weeks = listOf(1, 3, 5))))
        assertTrue("全奇数周次仍是 type:1，实到：$odd", odd.contains("""startNode":1,"step":2,"startWeek":1,"endWeek":5,"type":1,"color":0}"""))
        val even = wakeUp(listOf(course(weeks = listOf(2, 4, 6))))
        assertTrue("全偶数周次仍是 type:2，实到：$even", even.contains("""startNode":1,"step":2,"startWeek":2,"endWeek":6,"type":2,"color":0}"""))
        val mixed = wakeUp(listOf(course(weeks = listOf(1, 2, 3))))
        assertTrue("奇偶混着仍是 type:0，实到：$mixed", mixed.contains("""startNode":1,"step":2,"startWeek":1,"endWeek":3,"type":0,"color":0}"""))
        // 乱序周次照旧排序后取首末（本卡只把空表那一档改成不出场）
        val shuffled = wakeUp(listOf(course(weeks = listOf(9, 1, 6))))
        assertTrue("乱序 [9,1,6] 排序后取 1..9、奇偶混着 ⇒ type:0，实到：$shuffled", shuffled.contains(""""startWeek":1,"endWeek":9,"type":0"""))
        val single = wakeUp(listOf(course(weeks = listOf(7))))
        assertTrue("单枚周次 7 ⇒ startWeek=endWeek=7、type:1（这才叫真有其事，不是空表造出来的 1-1 周），实到：$single", single.contains(""""startWeek":7,"endWeek":7,"type":1"""))
    }

    /**
     * **#227 的现状登记**（本卡只评估不实现，这一格钉的是"今天实际导出成什么"，不是裁定）：
     * 有洞的周次 `[2,4,6,9,11]` 既不全奇也不全偶 ⇒ 压平成 `startWeek:2..endWeek:11, type:0`
     * ⇒ 对方 App 在第 7、8 周也排这门课。全仓其它钉用的都是**连续**周次夹具，从没打过这一档。
     */
    @Test
    fun `不连续周次今天被压平成一个区间_现状登记`() {
        val json = wakeUp(listOf(course(name = "洞课", weeks = listOf(2, 4, 6, 9, 11))))
        assertTrue(
            "改法另立卡后这一格要跟着改（现在钉的是 #227 未修的现状）：$json",
            json.contains(""""startWeek":2,"endWeek":11,"type":0"""),
        )
        assertEquals("今天它仍然出场（压平不是丢下）", 1, exportedEntries(json))
    }

    // —— ⑤ 可达性：空 weeks 不是虚构的输入 ——

    /**
     * 写路径确实拦这道（[CourseConstraints.normalize] 对空周次返回 null），但**读路径不复校**：
     * `weeks` 列是 `TEXT NOT NULL`、没有 CHECK 约束，`Converters.toIntList` 对空白/非数字一律回空表
     * ⇒ 旧版本留下的行、被改坏的 .db、adb 播种都会以"没有周次的课"进入 `getDisplayCourses`，
     * 直接喂给导出器。与 `EmptyPeriodPlacementTest` 给空节次证的是同一条链。
     */
    @Test
    fun `库的 weeks 列读回空表时不做任何复核`() {
        val converters = Converters()
        assertEquals(emptyList<Int>(), converters.toIntList(""))
        assertEquals(emptyList<Int>(), converters.toIntList("   "))
        assertEquals(emptyList<Int>(), converters.toIntList("null"))

        val row = CourseEntity(
            id = 7L,
            name = "没有周次的课",
            teacher = null,
            location = null,
            campus = null,
            dayOfWeek = 1,
            periods = listOf(1, 2),
            weeks = emptyList(),
            colorIndex = 0,
            remark = null,
            sourceGroupKey = null,
            semesterCode = null,
        ).entityToDomain()
        assertTrue("库的读路径原样交回空周次的课", row.weeks.isEmpty())
        assertEquals(WakeUpDrop.NO_WEEK, WakeUpDropTally.wakeUpDrop(row))
        // 而写路径拦得住：同一条过 normalize 直接判死（所以本卡改的是**已经写进库**那一档）
        assertNull(CourseConstraints.normalize(row))
        // 而且这行原样写得回库：`fromIntList(emptyList())` 就是空白串
        assertEquals("", converters.fromIntList(row.weeks))
    }

    /** 备份 JSON 是用户可控输入，而 `BackupCourse.toDomain` 是这条链上唯一直接产出 Course 的出口。 */
    @Test
    fun `备份里空 weeks 会产出一门没有周次的课`() {
        val restored = BackupCourse(
            name = "没有周次的课",
            dayOfWeek = 1,
            periods = listOf(1, 2),
            weeks = emptyList(),
        ).toDomain()
        assertTrue("备份恢复链上确实拿得到空周次课", restored.weeks.isEmpty())
        val counts = WakeUpDropTally.tally(listOf(restored))
        assertEquals("这门课只落周次那一档，不落节次那一档", 0, counts.noPeriodCourses)
        assertEquals(1, counts.noWeekCourses)
        assertEquals(
            "WakeUp JSON 已导出（0 段排课；另有 1 段没有周次、无法定位，未写入）",
            wakeUpExportMessage(1, counts.noPeriodCourses, counts.noWeekCourses),
        )
        assertEquals(0, exportedEntries(wakeUp(listOf(restored))))
    }
}
