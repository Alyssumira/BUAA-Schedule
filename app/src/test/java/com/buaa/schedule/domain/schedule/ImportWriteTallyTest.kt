package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.ui.importing.importCompletionMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T181① 表驱动单测：导入落库前那一刀「哪几行会被判死、判死了几行」的账（纯 JVM，零 android、不装机）。
 *
 * 判据本体是 [ImportWriteTally.tally]，而它数的正是 [CourseConstraints.normalize] 的返回值：
 * 一条课程只要星期几不在 `1..7`、节次全在 `1..MAX_PERIOD` 之外、周次全在 `1..MAX_WEEK` 之外、
 * 名称空白，就被判死（返回 null）；**能救的**（周次/节次只裁掉越界的那几枚、名称首尾空白、
 * 负 colorIndex、脏学分）一律留下，不进 dropped 这本册子 —— 表里"救回"那一组钉的就是这条分界。
 *
 * ## 病与这一格的关系
 *
 * 此前这一刀写在 `ScheduleRepository.replaceSemesterCoursesWithHistory` 里、形状是
 * `importedCourses.mapNotNull(CourseConstraints::normalize)`：被判死的行当场蒸发，
 * 调用方拿不到任何账，而 `confirmPendingImport` 那句「导入完成：新增 N」念的是**预览选中的条数**。
 * 于是课表少一门、屏幕上那句还是多的，没有任何人念过少的那一门。
 * 本文件钉的是**判据本身**（哪几行进哪本册子），接线（VM 是否把 droppedCount 端给文案、
 * 仓储是否交回这本账）由 `ImportCompletionSkippedCountGuardTest` 源码核对钉。
 *
 * ⚠️ 复跑的是结论与算式，不是 Room、不是设备：**未装机**（真机上"导入一条星期 8 的课"
 * 需要教务侧或文本侧造出该行，本卡零 adb）。
 */
class ImportWriteTallyTest {

    private val term = "2026-2027-1"
    private val weeks = (1..16).toList()

    private fun course(
        name: String = "高等数学",
        dayOfWeek: Int = 1,
        periods: List<Int> = listOf(1, 2),
        weeks: List<Int> = this.weeks,
        id: Long = 0L,
    ) = Course(
        id = id,
        name = name,
        teacher = "张三",
        location = "J3-101",
        dayOfWeek = dayOfWeek,
        periods = periods,
        weeks = weeks,
        sourceGroupKey = "G$dayOfWeek-$name",
        semesterCode = term,
        credit = 3.0,
    )

    /** 一格 = 一条待写库的课程；[expectKept] 是"normalize 留不留它"的期望 */
    private class Cell(
        val label: String,
        val input: Course,
        val expectKept: Boolean,
    )

    private val cells = listOf(
        Cell("正常一条", course(), expectKept = true),
        Cell("星期八（越界，判死）", course(dayOfWeek = 8), expectKept = false),
        Cell("星期零（判死）", course(dayOfWeek = 0), expectKept = false),
        Cell("星期负（判死）", course(dayOfWeek = -3), expectKept = false),
        Cell("一条节次都没有（判死）", course(periods = emptyList()), expectKept = false),
        Cell("节次全在范围外（判死）", course(periods = listOf(31, 99)), expectKept = false),
        Cell("周次全空（判死）", course(weeks = emptyList()), expectKept = false),
        Cell("周次全在范围外（判死）", course(weeks = listOf(31, 40)), expectKept = false),
        Cell("名称只有空白（判死）", course(name = "   "), expectKept = false),
        Cell("周次只裁掉越界那几枚（救回）", course(weeks = listOf(1, 2, 33)), expectKept = true),
        Cell("节次只裁掉越界那一枚（救回）", course(periods = listOf(1, 99)), expectKept = true),
        Cell("名称首尾空白（救回并 trim）", course(name = "  线性代数  "), expectKept = true),
    )

    @Test
    fun `每一行进哪本册子逐格与 normalize 的结论同向`() {
        for (cell in cells) {
            val outcome = ImportWriteTally.tally(listOf(cell.input))
            val kept = outcome.writtenCount == 1
            assertEquals(
                "「${cell.label}」：normalize 的结论应与期望同向。" +
                    "实际 writtenCount=${outcome.writtenCount}、droppedCount=${outcome.droppedCount}、" +
                    "CourseConstraints.normalize 返回 ${CourseConstraints.normalize(cell.input) != null}",
                cell.expectKept,
                kept,
            )
            // 一本册子记不下的行不能同时进另一本：两本相加必须恰好是输入条数
            assertEquals(
                "「${cell.label}」：writtenCount + droppedCount 必须等于喂进去的条数（不许漏账）",
                1,
                outcome.writtenCount + outcome.droppedCount,
            )
        }
    }

    @Test
    fun `五格一批里那条星期八只进判死那本册子`() {
        val batch = listOf(
            course(name = "高等数学", dayOfWeek = 1, id = 1L),
            course(name = "线性代数", dayOfWeek = 2, id = 2L),
            course(name = "大学物理", dayOfWeek = 3, id = 3L),
            course(name = "复变函数", dayOfWeek = 4, id = 4L),
            course(name = "星期八的课", dayOfWeek = 8, id = 5L),
        )
        val outcome = ImportWriteTally.tally(batch)
        assertEquals("喂 5 条、只有最后一条星期越界 ⇒ 写下 4 条", 4, outcome.writtenCount)
        assertEquals("判死那本册子里必须恰好记着 1 条", 1, outcome.droppedCount)
        assertEquals(
            "留下的行是星期 1..4 那四门（顺序与 normalize 后的值都在这一格里）",
            listOf("高等数学", "线性代数", "大学物理", "复变函数"),
            outcome.writtenCourses.map { it.name },
        )
        // 文案那一格：判死了就得念出来
        val message = importCompletionMessage(
            addedCount = 5,
            changedCount = 0,
            keptCount = 0,
            droppedCount = outcome.droppedCount,
        )
        assertTrue("dropped=1 时那句必须含「跳过」，实际：$message", message.contains("跳过"))
        assertTrue(
            "dropped=1 时那句得把条数说死（实际：$message）",
            message.contains("跳过 1 条无效"),
        )
    }

    @Test
    fun `一条没判死时那句里不许出现跳过`() {
        val outcome = ImportWriteTally.tally(listOf(course(name = "A", dayOfWeek = 1), course(name = "B", dayOfWeek = 2)))
        assertEquals(0, outcome.droppedCount)
        val message = importCompletionMessage(
            addedCount = outcome.writtenCount,
            changedCount = 0,
            keptCount = 0,
            droppedCount = outcome.droppedCount,
        )
        assertFalse("dropped=0 时无效行不存在，那句『跳过』不许出场：$message", message.contains("跳过"))
        assertEquals(
            "导入完成：新增 3、更新 1（含拆行片段）",
            importCompletionMessage(addedCount = 3, changedCount = 1, keptCount = 0, droppedCount = 0),
        )
        assertEquals(
            "导入完成：新增 3、更新 1、保留 2（含拆行片段）（跳过 1 条无效）",
            importCompletionMessage(addedCount = 3, changedCount = 1, keptCount = 2, droppedCount = 1),
        )
    }

    @Test
    fun `空批次与满盘判死两端的账`() {
        val nothing = ImportWriteTally.tally(emptyList())
        assertEquals("空批次两头都是零", ImportWriteOutcome(writtenCourses = emptyList(), droppedCount = 0), nothing)
        val allDead = listOf(
            course(dayOfWeek = 8),
            course(periods = emptyList()),
            course(name = " "),
        )
        val outcome = ImportWriteTally.tally(allDead)
        assertEquals(0, outcome.writtenCount)
        assertEquals(3, outcome.droppedCount)
        assertTrue(
            "满盘判死也要念那一句（实际：${importCompletionMessage(3, 0, 0, outcome.droppedCount)}）",
            importCompletionMessage(3, 0, 0, outcome.droppedCount).contains("跳过 3 条无效"),
        )
    }

    @Test
    fun `写库条数是同表派生值不可能与留下的行打架`() {
        val outcome = ImportWriteTally.tally(cells.map { it.input })
        assertEquals(
            "writtenCount 恒等于 writtenCourses.size（类体属性，不是第二个构造参数）",
            outcome.writtenCourses.size,
            outcome.writtenCount,
        )
        assertEquals("整表 12 格里判死 8 格", 8, outcome.droppedCount)
        assertEquals("整表 12 格里救回 4 格", 4, outcome.writtenCount)
        assertEquals(
            "救回来的那一行是 normalize 之后的值，不是原样",
            "线性代数",
            outcome.writtenCourses.first { it.name.isNotEmpty() && it.name.startsWith("线性代数") }.name,
        )
    }
}
