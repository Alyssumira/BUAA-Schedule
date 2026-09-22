package com.buaa.schedule.ui.home

import com.buaa.schedule.ui.home.DayTimelineBlockLine.CourseName
import com.buaa.schedule.ui.home.DayTimelineBlockLine.Remark
import com.buaa.schedule.ui.home.DayTimelineBlockLine.Room
import com.buaa.schedule.ui.home.DayTimelineBlockLine.TimeAndTeacher
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 日视图时间轴行预算内核（DayTimelineBlockLines.kt）的表驱动单测。
 *
 * 数字全部来自装机口径（buaa36，1080×2400 / 420dpi / fontScale 1.0）：
 * 排版表 labelLarge 行高 20sp、labelMedium 16sp（Type.kt），
 * 块高 = 分钟数 × 1.35dp（DesignTokens.dayHeightPerMinute，T61 从 1.05 抬上来的那一档），
 * 文字内边距上下各 3dp。1sp = fontScale dp，所以 sp 直接当 dp 用即可。
 *
 * 这里没有 android：块高、行高、内边距、可用宽度都是数字，密度与 fontScale 由调用点算好递进来。
 */
class DayTimelineBlockLinesTest {

    private class Case(
        val name: String,
        val minutes: Int,
        val fontScale: Float,
        val hasRemark: Boolean,
        val expected: List<DayTimelineBlockLine>,
        val blockHeightOverride: Double? = null,
    )

    /**
     * 真机场景表。前四行的 blockHeight 走 `分钟 × 1.35 + 触控下限 48dp` 这条与调用点同式的算式，
     * 所以这张表钉的不只是判据，也钉住了「1.35 这一档确实抬到足以装下三行」这件事本身。
     */
    @Test
    fun plannedLinesFollowMeasuredBudgetOnRealScenarios() {
        val cases = listOf(
            // 正常 45 分钟单节课，正常字号：60.75dp − 6dp = 54.75dp 容量，
            // 20 + 16 + 16 = 52 ≤ 54.75 → 课名/时间·教师/教室三行都在（改前这一档只有两行，
            // 因为 47.25dp 够不到旧的 58dp 门槛），备注 68dp > 54.75 仍不出场
            Case("45min@1.35 常规字号 → 三行", 45, 1.0f, true, listOf(CourseName, TimeAndTeacher, Room)),
            // 系统字号调到 2.0：行高按 fontScale 长（40/32），dp 门槛与内边距不长，
            // 40 + 32 = 72 > 54.75 → 第二行就不画了，只剩课名。旧口径在这里反过来过度承诺：
            // 38dp 那档判定与 40dp 的课名行高打架，正是 T54「第三行把课名顶出去」的成因
            Case("45min@1.35 fontScale 2.0 → 只剩课名", 45, 2.0f, true, listOf(CourseName)),
            // 90 分钟连排：121.5 − 6 = 115.5dp 容量，四行 68dp 装得下 → 备注白送
            Case("90min 连排 → 四行", 90, 1.0f, true, listOf(CourseName, TimeAndTeacher, Room, Remark)),
            // 20 分钟的短块被 minTouchTarget 补到 48dp：容量 42dp，
            // 20 + 16 = 36 装得下、再加 16 = 52 装不下 → 两行（补齐的高度用掉了一行，正是它该干的活）
            Case("20min 触下限补到 48dp → 两行", 20, 1.0f, true, listOf(CourseName, TimeAndTeacher)),
            // 同一块没有备注：候选都不递，行数与上一行一致（这条钉的是"没有备注 ≠ 多出一行别的东西"）
            Case("90min 连排但无备注 → 三行", 90, 1.0f, false, listOf(CourseName, TimeAndTeacher, Room)),
            // 极端：块高比三行还矮（1.0f 字号、10 分钟且不补，容量只有 4dp）
            // → 课名仍在场，被块的裁切吃掉一角也不把它摘掉（pinned 的语义）
            Case("矮到容量归零 → 只有课名", 10, 1.0f, true, listOf(CourseName), blockHeightOverride = 10.0),
        )
        for (case in cases) {
            val plan = planDayTimelineBlockLines(
                blockHeightDp = case.blockHeightDp(),
                contentVerticalPaddingDp = VerticalPadding,
                availableWidthDp = RealBlockWidthDp,
                specs = realSpecs(case.fontScale, case.hasRemark),
            )
            assertEquals("${case.name}（块高 ${case.blockHeightDp()}dp）", case.expected, plan.lines)
        }
    }

    /** 每一行按顺序能装就装：容量刚好卡在边界上时算"装得下"（58dp 整 = 三行 + 3dp×2） */
    @Test
    fun budgetIsInclusiveAtTheBoundary() {
        val plan = planDayTimelineBlockLines(
            blockHeightDp = 58.0,
            contentVerticalPaddingDp = 3.0,
            availableWidthDp = 300.0,
            specs = listOf(
                DayTimelineLineSpec(CourseName, 20.0),
                DayTimelineLineSpec(TimeAndTeacher, 16.0),
                DayTimelineLineSpec(Room, 16.0),
                DayTimelineLineSpec(Remark, 16.0),
            ),
        )
        assertEquals(listOf(CourseName, TimeAndTeacher, Room), plan.lines)
    }

    /** 后面的行装不下时不许"换个小的塞进来"：行序就是信息优先级，缺席之后不许插队 */
    @Test
    fun aDroppedLineDoesNotLetALaterOneJumpTheQueue() {
        val plan = planDayTimelineBlockLines(
            blockHeightDp = 60.75,
            contentVerticalPaddingDp = 3.0,
            availableWidthDp = 300.0,
            specs = listOf(
                DayTimelineLineSpec(CourseName, 20.0),
                DayTimelineLineSpec(TimeAndTeacher, 20.0),
                DayTimelineLineSpec(Room, 20.0),   // 20+20+20 = 60 > 54.75 → 断在这里
                DayTimelineLineSpec(Remark, 10.0), // 单看装得下，也不许补位
            ),
        )
        assertEquals(listOf(CourseName, TimeAndTeacher), plan.lines)
    }

    /** 宽度量不到（≤0）时附加行一概不承诺——省略号救不了零宽，只留 pinned 的课名 */
    @Test
    fun unknownWidthCommitsToNothingButThePinnedLine() {
        val specs = listOf(
            DayTimelineLineSpec(CourseName, 20.0),
            DayTimelineLineSpec(TimeAndTeacher, 16.0),
            DayTimelineLineSpec(Room, 16.0),
        )
        for (width in listOf(0.0, -1.0)) {
            assertEquals(
                "可用宽度 $width 时不该承诺附加行",
                listOf<DayTimelineBlockLine>(CourseName),
                planDayTimelineBlockLines(200.0, 3.0, width, specs).lines,
            )
        }
        // 宽度是限制项的只有"量不到"这一档：同样的 specs 给到正宽度就是三行全在
        assertEquals(
            listOf(CourseName, TimeAndTeacher, Room),
            planDayTimelineBlockLines(200.0, 3.0, 1.0, specs).lines,
        )
    }

    /** 行高 ≤0 = 这一行没有内容可摆（调用点据此不递；递了也不占容量、也不画） */
    @Test
    fun zeroHeightLineIsNotADrawnLine() {
        val plan = planDayTimelineBlockLines(
            blockHeightDp = 60.75,
            contentVerticalPaddingDp = 3.0,
            availableWidthDp = 300.0,
            specs = listOf(
                DayTimelineLineSpec(CourseName, 20.0),
                DayTimelineLineSpec(TimeAndTeacher, 0.0),
                DayTimelineLineSpec(Room, 16.0),
            ),
        )
        assertEquals(listOf(CourseName, Room), plan.lines)
    }

    /** 空候选集与"全是 pinned"都不炸；pinned 行始终在场 */
    @Test
    fun emptySpecsYieldEmptyPlanAndPinnedLineSurvivesAnywhereInTheList() {
        assertEquals(
            emptyList<DayTimelineBlockLine>(),
            planDayTimelineBlockLines(60.75, 3.0, 300.0, emptyList()).lines,
        )
        val roomFirst = planDayTimelineBlockLines(
            blockHeightDp = 4.0,
            contentVerticalPaddingDp = 3.0,
            availableWidthDp = 300.0,
            specs = listOf(DayTimelineLineSpec(CourseName, 20.0), DayTimelineLineSpec(Room, 16.0)),
        )
        assertEquals(listOf<DayTimelineBlockLine>(CourseName), roomFirst.lines)
    }

    // ---- 场景数字：与调用点同一把算式 ----------------------------------------

    private fun Case.blockHeightDp(): Double = blockHeightOverride
        ?: (minutes * DpPerMinute).coerceAtLeast(MinTouchTargetDp)

    private fun realSpecs(fontScale: Float, hasRemark: Boolean) = listOfNotNull(
        DayTimelineLineSpec(CourseName, NameLineSp * fontScale),
        DayTimelineLineSpec(TimeAndTeacher, MetaLineSp * fontScale),
        DayTimelineLineSpec(Room, MetaLineSp * fontScale),
        if (hasRemark) DayTimelineLineSpec(Remark, MetaLineSp * fontScale) else null,
    )

    private companion object {
        /** DesignTokens.dayHeightPerMinute（T61 抬到 1.35 的那一档） */
        const val DpPerMinute = 1.35

        /** DesignTokens.minTouchTarget：短块补到这里为止 */
        const val MinTouchTargetDp = 48.0

        /** Type.kt 的两档行高（sp；1sp = fontScale dp） */
        const val NameLineSp = 20.0
        const val MetaLineSp = 16.0

        /** 块内文字的单侧上下内边距 */
        const val VerticalPadding = 3.0

        /** buaa36 上一块可用的文字宽度（页边距与刻度列都扣掉之后） */
        const val RealBlockWidthDp = 300.0
    }
}
