package com.buaa.schedule.ui.course

import com.buaa.schedule.core.designsystem.weekCourseCounts
import com.buaa.schedule.core.designsystem.weekDensityBarDescription
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.TimeSlotProfile
import com.buaa.schedule.domain.schedule.SemesterStats
import com.buaa.schedule.ui.settings.courseManagementRowSummary
import com.buaa.schedule.ui.settings.wakeUpExportMessage
import com.buaa.schedule.ui.settings.wakeUpExportRowSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * T224：「门」与「段」两把尺**各摆格子**（表驱动，纯 JVM，零 android 运行面、零装机）。
 *
 * ## 为什么要一张表而不是一句断言
 *
 * 这一族栽过四次（#179 / T94 / T184 / 本批 #308），每一次都不是"数字算错"，而是
 * **同一屏上两句数出自两把尺**：不崩、不报错，只会被读出来。单独钉一句文案永远只能钉住那一句 ——
 * 所以这里把同一批课程数据同时端给四枚读数面（内核那本账、课表管理页那一张卡、设置页那两行、
 * 周次密度条的读屏），一格一行，**门与段两列都要有对照**：
 *
 * | 数据 | 门（courseGroupKey） | 段（排课片段行） | 管理页卡片数 | 设置页那行念 | 密度条读屏念 |
 *
 * 改前的读数（种子数据）：设置页念 22、管理页页头念 18、统计页念「18 门课 · 22 段排课」，
 * WakeUp 那一格先说 22 门课、点完 toast 说 20 门课。今天四格只有两种单位词，
 * 且每种单位词只吃一枚具名读数。
 *
 * ⚠️ 「两句是否真的同屏」要装机才判得实（本卡零 adb）——本表钉的是**取数与措辞同源**这件事，
 * 屏幕上的读数归编排者。
 */
class CourseUnitTwoRulersTableTest {

    private fun course(
        id: Long,
        name: String,
        day: Int,
        periods: List<Int>,
        weeks: List<Int> = (1..8).toList(),
        groupKey: String? = null,
        semesterCode: String? = "2025-2026-2",
    ) = Course(
        id = id,
        name = name,
        dayOfWeek = day,
        periods = periods,
        weeks = weeks,
        sourceGroupKey = groupKey,
        semesterCode = semesterCode,
    )

    private class Row(
        val label: String,
        val courses: List<Course>,
        /** 内核那一把尺：按 [SemesterStats.courseGroupKey] 归并出来的门数 */
        val expectCourses: Int,
        /** 另一把尺：排课片段行数（= 一条 Course 一行） */
        val expectFragments: Int,
    )

    private val rows = listOf(
        Row(
            label = "两门导入课，其中一门拆成理论 + 实验两段",
            courses = listOf(
                course(1, "高等数学", day = 1, periods = listOf(1, 2), groupKey = "G1"),
                course(2, "高等数学", day = 5, periods = listOf(5, 6), weeks = (9..16).toList(), groupKey = "G1"),
                course(3, "大学英语", day = 2, periods = listOf(3, 4), groupKey = "G2"),
            ),
            expectCourses = 2,
            expectFragments = 3,
        ),
        Row(
            label = "同名手动课排在不同天（改前管理页念 2 门、统计页念 1 门的那一格）",
            courses = listOf(
                course(4, "自习", day = 1, periods = listOf(1, 2), semesterCode = null),
                course(5, "自习", day = 3, periods = listOf(7, 8), semesterCode = null),
            ),
            expectCourses = 1,
            expectFragments = 2,
        ),
        Row(
            label = "同名手动课同一时间、分两个周次段各录一次",
            courses = listOf(
                course(6, "自习", day = 1, periods = listOf(1, 2), weeks = (1..8).toList(), semesterCode = null),
                course(7, "自习", day = 1, periods = listOf(1, 2), weeks = (9..16).toList(), semesterCode = null),
            ),
            expectCourses = 1,
            expectFragments = 2,
        ),
        Row(
            label = "同名课分在两个学期（内核带学期前缀 ⇒ 两门；改前那一族键会并成一枚）",
            courses = listOf(
                course(8, "重修英语", day = 1, periods = listOf(1, 2), semesterCode = "2024-2025-1"),
                course(9, "重修英语", day = 1, periods = listOf(1, 2), semesterCode = "2025-2026-1"),
            ),
            expectCourses = 2,
            expectFragments = 2,
        ),
        Row(
            label = "一门课三个片段排在同三天",
            courses = listOf(
                course(10, "体育课", day = 2, periods = listOf(1, 2), groupKey = "G3"),
                course(11, "体育课", day = 4, periods = listOf(1, 2), groupKey = "G3"),
                course(12, "体育课", day = 6, periods = listOf(1, 2), weeks = (1..16).toList(), groupKey = "G3"),
            ),
            expectCourses = 1,
            expectFragments = 3,
        ),
        Row(label = "空课表", courses = emptyList(), expectCourses = 0, expectFragments = 0),
    )

    /** 密度条那一枚入参形状：调用点递给 `weekCourseCounts` 的就是这个（逐片段的周次集合） */
    private fun weekSets(courses: List<Course>): List<Set<Int>> = courses.map { it.weeks.toSet() }

    @Test
    fun `同一批数据 门与段各是多少 两侧都有对照`() {
        for (row in rows) {
            val summary = SemesterStats.summarize(row.courses, null, TimeSlotProfile.DEFAULT)
            assertEquals(
                "「${row.label}」：内核那把尺的门数（`SemesterStats.courseCount` = 按 courseGroupKey 归并）",
                row.expectCourses,
                summary.courseCount,
            )
            assertEquals(
                "「${row.label}」：另一把尺的段数（`SemesterStats.fragmentCount` = 排课片段行数）",
                row.expectFragments,
                summary.fragmentCount,
            )
            // 两把尺在同一条数据上必须真的是两枚数（门 == 段 的那几格是巧合，不许拿它当恒等式）
            assertEquals(
                "「${row.label}」：门数不许大于段数（归并只会把片段并少，不会凭空多出门）",
                true,
                summary.courseCount <= summary.fragmentCount,
            )
        }
    }

    @Test
    fun `管理页那张卡的组数与内核的门数同尺 卡片段数之和等于总段数`() {
        for (row in rows) {
            val groups = groupCourses(row.courses, query = "")
            assertEquals(
                "「${row.label}」：课表管理页页头那句「N 门课」吃的是 groups.size —— 它必须逐枚等于内核的 " +
                    "courseCount（改前这里是第二把尺：同名不同时的手动课在管理页算 2 门、在统计页算 1 门）。" +
                    "实到管理页 ${groups.size} 门 vs 内核 ${SemesterStats.summarize(row.courses, null, TimeSlotProfile.DEFAULT).courseCount} 门",
                SemesterStats.summarize(row.courses, null, TimeSlotProfile.DEFAULT).courseCount,
                groups.size,
            )
            assertEquals(
                "「${row.label}」：把每张卡底下的段数加起来必须是总段数（一卡片不吞片段、也不重复挂片段）",
                row.expectFragments,
                groups.sumOf { it.fragments.size },
            )
            for (group in groups) {
                val keyCount = group.fragments.map { SemesterStats.courseGroupKey(it) }.distinct().size
                assertEquals(
                    "「${row.label}」/「${group.displayName}」：一张卡只许装着同一枚组键的片段，键形 = 内核那一把尺",
                    1,
                    keyCount,
                )
                assertEquals("「${group.displayName}」：卡上的组键必须逐字来自内核", group.key, SemesterStats.courseGroupKey(group.fragments.first()))
            }
        }
    }

    @Test
    fun `设置页两行念的两个数就是统计页那两格具名读数`() {
        for (row in rows) {
            val summary = SemesterStats.summarize(row.courses, null, TimeSlotProfile.DEFAULT)
            assertEquals(
                "「${row.label}」：课表管理那一行必须把门与段**两枚都念出来**（只念一枚就是让读者自己去猜哪把尺）",
                "查看、编辑、删除全部课程（当前 ${summary.courseCount} 门课 · ${summary.fragmentCount} 段排课）",
                courseManagementRowSummary(
                    courseCount = summary.courseCount,
                    fragmentCount = summary.fragmentCount,
                ),
            )
            assertEquals(
                "「${row.label}」：WakeUp 导出那一行是预览，念的只能是段（导出件一条片段写一行，门在那儿表达不出来）",
                "把当前课表导出为 WakeUp 课程表可导入的文件（${summary.fragmentCount} 段排课）",
                wakeUpExportRowSummary(summary.fragmentCount),
            )
        }
    }

    @Test
    fun `单位词不许串门 门那一枚只许跟着门数`() {
        for (row in rows) {
            val summary = SemesterStats.summarize(row.courses, null, TimeSlotProfile.DEFAULT)
            val managementLine = courseManagementRowSummary(summary.courseCount, summary.fragmentCount)
            assertEquals("「${row.label}」：设置页那一行的「门」恰一枚", 1, managementLine.count { it == '门' })
            assertEquals("「${row.label}」：设置页那一行的「段」恰一枚", 1, managementLine.count { it == '段' })
            val exportLine = wakeUpExportRowSummary(summary.fragmentCount)
            assertEquals("「${row.label}」：WakeUp 那一行一枚门都不许有（它数是片段）", 0, exportLine.count { it == '门' })
            assertEquals("「${row.label}」：WakeUp 那一行的「段」恰一枚", 1, exportLine.count { it == '段' })
            assertFalse(
                "「${row.label}」：段数那一句不许把 fragmentCount 写成门（病灶原文形状：「（${summary.fragmentCount} 门课）」）",
                exportLine.contains("${summary.fragmentCount} 门"),
            )
        }
    }

    @Test
    fun `密度条读屏那句念段 而它吃的就是 weekCourseCounts 的那一项`() {
        // 这一格的形状与调用点一致：`courseWeeks = visibleCourses.map { it.weeks.toSet() }` →
        // `weekCourseCounts(courseWeeks, totalWeeks)` → `WeekDensityStrip(counts = …)` 的一根柱子
        val courses = rows[0].courses
        val counts = weekCourseCounts(weekSets(courses), totalWeeks = 19)
        assertEquals("周数轴长 19 根柱子", 19, counts.size)
        for (index in counts.indices) {
            val week = index + 1
            val text = weekDensityBarDescription(week = week, count = counts[index], isCurrent = false)
            assertEquals(
                "第 $week 周那根柱子：念的数必须就是 counts[$index]（这一格排掉「读屏报的数与画出来的高度不同源」）",
                "第 $week 周，${counts[index]} 段排课",
                text,
            )
            assertEquals("第 $week 周那句读屏不许出现「门」：$text", 0, text.count { it == '门' })
        }
        assertEquals(
            "第 1 周那根柱子当前周那一档：后缀逐字",
            "第 1 周，${counts[0]} 段排课，当前教学周",
            weekDensityBarDescription(week = 1, count = counts[0], isCurrent = true),
        )
        // 对照：这一格是"三枚片段都排在第 1 周"⇒ 柱子念 3 段，而门只有 2 —— 念门就与统计页打脸
        val allInOne = weekCourseCounts(listOf(setOf(1), setOf(1), setOf(1)), totalWeeks = 2)
        assertEquals(
            "三段排课都在第 1 周 ⇒ 读屏念 3 段（不是 2 门、也不是 3 门）",
            "第 1 周，3 段排课",
            weekDensityBarDescription(1, allInOne[0], false),
        )
    }

    @Test
    fun `颜色那一句按写库那一手的真实覆盖面说实话`() {
        // 教务组键在 ⇒ VM 那判（applyToGroup && sourceGroupKey != null）真的铺整组
        assertEquals(
            "有组键 + 多段：数要念出来",
            "颜色会应用到这门课的全部 3 段排课",
            groupColorScopeNote(fragmentCount = 3, hasSourceGroupKey = true),
        )
        assertEquals(
            "有组键 + 唯一一段",
            "颜色会应用到这门课那唯一的一段排课",
            groupColorScopeNote(fragmentCount = 1, hasSourceGroupKey = true),
        )
        // 手动课程没有组键 ⇒ 写库只落在门面那枚片段，这一档不许再承诺"全部片段"
        assertEquals(
            "没有组键 + 多段：必须说实话（改前这一档念的是「全部片段」，而那是不可能的覆盖面）",
            "手动课程没有教务组键：颜色只会改当前那一段，另外 2 段要逐段改",
            groupColorScopeNote(fragmentCount = 3, hasSourceGroupKey = false),
        )
        assertEquals(
            "没有组键 + 唯一一段",
            "颜色会改的是当前那一段排课",
            groupColorScopeNote(fragmentCount = 1, hasSourceGroupKey = false),
        )
    }

    @Test
    fun `删除确认那句念的是这门课的全部片段`() {
        // 两枚片段排在同一天不现实（同组可以跨天），这里要的是"这门课 = 这两段"那一格：
        // 换源后 group.fragments 就是内核交回的组内全部片段，句里的 2 与卡片上的段数同一枚
        val merged = listOf(
            course(21, "线性代数", day = 1, periods = listOf(1, 2), groupKey = "G9"),
            course(22, "线性代数", day = 3, periods = listOf(3, 4), groupKey = "G9"),
        )
        assertEquals(
            "改前那句「将删除这门课的全部 1 个片段」在第二把尺下是谎（删完统计页那门课照旧在列）；" +
                "换源之后「这门课」与「全部片段」同尺 ⇒ 逐字钉住这句",
            "将删除这门课的全部 2 个片段（第1-2节、第3-4节）。删除后可在提示条里撤销。",
            deleteGroupConfirmText(merged, TimeSlotProfile.DEFAULT),
        )
        // 另一档：5→6 之间是午休，节次串按作息表断开（"第5,6节"）—— 那一格钉的是"段数"与"节次串"
        // 是两把尺、各吃各的读数：段数仍是 2，不因断开而多数一枚，也不因合并而少数一枚
        val lunch = listOf(
            course(23, "复变函数", day = 5, periods = listOf(5), groupKey = "G10"),
            course(24, "复变函数", day = 5, periods = listOf(6), groupKey = "G10"),
        )
        assertEquals(
            "段数念 2（片段行数），节次串念「第5节、第6节」——同一句里两把尺各自如实",
            "将删除这门课的全部 2 个片段（第5节、第6节）。删除后可在提示条里撤销。",
            deleteGroupConfirmText(lunch, TimeSlotProfile.DEFAULT),
        )
    }
}
