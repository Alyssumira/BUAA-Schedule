package com.buaa.schedule.ui

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.TimeSlotProfile
import com.buaa.schedule.domain.model.periodLabelOf
import com.buaa.schedule.domain.schedule.CourseConflictResolution
import com.buaa.schedule.ui.course.deleteGroupConfirmText
import com.buaa.schedule.ui.course.fragmentSummary
import com.buaa.schedule.ui.home.conflictCourseLine
import com.buaa.schedule.ui.home.conflictSuggestionLine
import com.buaa.schedule.ui.home.courseDetailSubtitle
import com.buaa.schedule.ui.home.dayCourseMetaLine
import com.buaa.schedule.ui.importing.importCourseRowText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 界面上那些「课程信息 + 节次」的一行文案，在**这门课没有节次**时该长什么样。
 *
 * 判据只有一条：没有节次就是"这一段不出场"，而不是把包装留在原处 ——
 * 旧写法在这里会给出「第节」「 · 张三」「周3  高数」「（第节，J3-101）」这类残骸。
 * 每一对断言都成对写：先钉住有节次时**逐字不变**，再钉住没节次时整段消失。
 *
 * 走的是各界面文件里抽出来的纯函数（`internal`），不在测试里重拼一遍文案。
 */
class EmptyPeriodCopyTest {

    private val slots: List<TimeSlot> = TimeSlotProfile.DEFAULT

    private fun course(
        name: String = "高等数学",
        day: Int = 1,
        periods: List<Int> = listOf(1, 2),
        weeks: List<Int> = listOf(1),
        location: String? = "J3-101",
        teacher: String? = "张三",
    ) = Course(
        name = name,
        teacher = teacher,
        location = location,
        dayOfWeek = day,
        periods = periods,
        weeks = weeks,
    )

    // —— 日视图卡片那行：节次 · 教师 ——

    @Test
    fun dayCardMetaLineKeepsTeacherWhenPeriodsMissing() {
        assertEquals("第1-2节 · 张三", dayCourseMetaLine(course(), slots))
        assertEquals("张三", dayCourseMetaLine(course(periods = emptyList()), slots))
        assertEquals("", dayCourseMetaLine(course(periods = emptyList(), teacher = null), slots))
    }

    // —— 课程详情 Sheet：节次 · 周次 ——

    @Test
    fun detailSubtitleNeverStartsWithADanglingSeparator() {
        assertEquals("第1-2节 · 1周", courseDetailSubtitle(course(), slots))
        assertEquals("1周", courseDetailSubtitle(course(periods = emptyList()), slots))
    }

    // —— 导入清单：周N 节次 课程名 教室 ——

    @Test
    fun importRowCollapsesTheGapLeftByAMissingPeriod() {
        assertEquals("周3 第1-2节 高等数学 J3-101", importCourseRowText(course(day = 3), slots))
        val blank = importCourseRowText(course(day = 3, periods = emptyList()), slots)
        assertEquals("周3 高等数学 J3-101", blank)
        assertFalse(blank, blank.contains("第节"))
        assertFalse(blank, blank.contains("  "))
    }

    // —— 冲突处理向导 ——

    @Test
    fun conflictRowDropsTheParenthesesWhenNothingIsLeft() {
        assertEquals("• 高等数学（第1-2节，J3-101）", conflictCourseLine(course(), slots))
        assertEquals("• 高等数学（J3-101）", conflictCourseLine(course(periods = emptyList()), slots))
        assertEquals(
            "• 高等数学",
            conflictCourseLine(course(periods = emptyList(), location = null), slots),
        )
    }

    @Test
    fun suggestionLineKeepsItsExactWording() {
        assertEquals(
            "建议：高等数学 移到 第3-4节（后挪 2 节）",
            conflictSuggestionLine(course(), CourseConflictResolution.ShiftSuggestion(listOf(3, 4), 2), slots),
        )
        assertEquals(
            "建议：高等数学 移到 第3-4节（前挪 1 节）",
            conflictSuggestionLine(course(), CourseConflictResolution.ShiftSuggestion(listOf(3, 4), -1), slots),
        )
        assertEquals(
            "建议：高等数学 移到 第3-4节",
            conflictSuggestionLine(course(), CourseConflictResolution.ShiftSuggestion(listOf(3, 4), 0), slots),
        )
        // shiftedBy 为 0 时那句「移到 」后面必须有东西 —— 界面那一支靠 periods 非空才渲染，
        // 空节次的建议根本不可执行（点下去等于把节次清空），见 ConflictWizardDialog 的分支条件
        assertEquals("", periodLabelOf(emptyList(), slots))
    }

    // —— 课程管理页 ——

    @Test
    fun fragmentSummaryLeavesTheWeekdayAloneWhenAPeriodListIsEmpty() {
        assertEquals(
            "周一 1-2 周三 3-4",
            fragmentSummary(listOf(course(day = 1, periods = listOf(1, 2)), course(day = 3, periods = listOf(3, 4))), slots),
        )
        // 旧写法在这里会多一个尾巴空格（"周一 "），摘要就变成断在半截上
        assertEquals("周一", fragmentSummary(listOf(course(day = 1, periods = emptyList())), slots))
        assertEquals("周一 周三 3-4", fragmentSummary(listOf(course(day = 1, periods = emptyList()), course(day = 3, periods = listOf(3, 4))), slots))
    }

    @Test
    fun deleteWarningKeepsItsSentenceWhenNoFragmentHasPeriods() {
        val two = listOf(course(day = 1, periods = listOf(1, 2)), course(day = 3, periods = listOf(3, 4)))
        assertEquals(
            "将删除这门课的全部 2 个片段（第1-2节、第3-4节）。删除后可在提示条里撤销。",
            deleteGroupConfirmText(two, slots),
        )
        assertEquals(
            "将删除这门课的全部 2 个片段。删除后可在提示条里撤销。",
            deleteGroupConfirmText(listOf(course(periods = emptyList()), course(day = 3, periods = emptyList())), slots),
        )
        // 只有一段缺节次时，括号里剩下的那段前面不能有悬空的顿号
        assertEquals(
            "将删除这门课的全部 2 个片段（第3-4节）。删除后可在提示条里撤销。",
            deleteGroupConfirmText(listOf(course(periods = emptyList()), course(day = 3, periods = listOf(3, 4))), slots),
        )
    }
}
