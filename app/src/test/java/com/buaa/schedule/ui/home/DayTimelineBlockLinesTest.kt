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
 * 数字全部来自装机口径（buaa36，1080×2400 / 420dpi / 密度 2.625）：
 * 两档行高是**调用点实测**的那两枚（labelLarge 52px、labelMedium 45px 折成 dp），
 * 不是排版表里 20sp/16sp 那两个标称值 —— T61b① 改的就是这一手，标称会少算 2.1dp，
 * 按标称挑的门槛只剩 1px 真余量。
 * 块高 = 分钟数 × 1.40dp（DesignTokens.dayHeightPerMinute，就是按下面这组实测数挑的那一档），
 * 文字内边距上下各 3dp。
 *
 * 这里没有 android：块高、行高、内边距都是数字，密度与 fontScale 由调用点量好递进来。
 * T61b③ 删掉了"可用宽度"那一档参数（宽度从来不是限制项，量它反倒多一条 lint 警告）。
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
     * 真机场景表。前四行的 blockHeight 走 `分钟 × 1.40 + 触控下限 48dp` 这条与调用点同式的算式，
     * 所以这张表钉的不只是判据，也钉住了「1.40 这一档确实抬到足以装下实测三行＋2dp 余量」这件事本身。
     */
    @Test
    fun plannedLinesFollowMeasuredBudgetOnRealScenarios() {
        val cases = listOf(
            // 正常 45 分钟单节课，正常字号：63 − 6 = 57dp 容量，
            // 实测三行 19.81 + 17.14 + 17.14 = 54.10 ≤ 57 → 课名/时间·教师/教室三行都在，
            // 真余量 2.90dp（装机复量到 7.1px）；备注再要 17.14 → 71.24 > 57，仍不出场
            Case("45min@1.40 常规字号 → 三行", 45, 1.0f, true, listOf(CourseName, TimeAndTeacher, Room)),
            // 系统字号调到 2.0：行高按字体度量长（99px / 91px，并不是 1.0 那两枚的整 2 倍），
            // dp 门槛与内边距不长 → 37.71 + 34.67 = 72.38 > 57，第二行就不画了，只剩课名。
            // 旧口径（标称 38dp 那档判定）在这里反过来过度承诺：40dp 的课名行高与 38dp 门槛打架，
            // 正是 T54「第三行把课名顶出去」的成因
            Case("45min@1.40 fontScale 2.0 → 只剩课名", 45, 2.0f, true, listOf(CourseName)),
            // 90 分钟连排：126 − 6 = 120dp 容量，四行 71.24dp 装得下 → 备注白送
            Case("90min 连排 → 四行", 90, 1.0f, true, listOf(CourseName, TimeAndTeacher, Room, Remark)),
            // 20 分钟的短块被 minTouchTarget 补到 48dp：容量 42dp，
            // 19.81 + 17.14 = 36.95 装得下、再加 17.14 = 54.10 装不下 → 两行
            // （补齐的高度用掉了一行，正是它该干的活）
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
                specs = realSpecs(case.fontScale, case.hasRemark),
            )
            assertEquals("${case.name}（块高 ${case.blockHeightDp()}dp）", case.expected, plan.lines)
        }
    }

    /** 每一行按顺序能装就装：容量刚好卡在边界上时算"装得下"（54.1dp 整 = 实测三行） */
    @Test
    fun budgetIsInclusiveAtTheBoundary() {
        val plan = planDayTimelineBlockLines(
            blockHeightDp = 60.1, // 54.1 + 上下各 3dp
            contentVerticalPaddingDp = 3.0,
            specs = realSpecs(fontScale = 1.0f, hasRemark = true),
        )
        assertEquals(listOf(CourseName, TimeAndTeacher, Room), plan.lines)
    }

    /** 后面的行装不下时不许"换个小的塞进来"：行序就是信息优先级，缺席之后不许插队 */
    @Test
    fun aDroppedLineDoesNotLetALaterOneJumpTheQueue() {
        val plan = planDayTimelineBlockLines(
            blockHeightDp = 63.0, // 45 分钟 × 1.40
            contentVerticalPaddingDp = 3.0,
            specs = listOf(
                DayTimelineLineSpec(CourseName, 20.0),
                DayTimelineLineSpec(TimeAndTeacher, 20.0),
                DayTimelineLineSpec(Room, 20.0),   // 20+20+20 = 60 > 57 → 断在这里
                DayTimelineLineSpec(Remark, 10.0), // 单看装得下，也不许补位
            ),
        )
        assertEquals(listOf(CourseName, TimeAndTeacher), plan.lines)
    }

    /** 行高 ≤0 = 这一行没有内容可摆（调用点据此不递；递了也不占容量、也不画） */
    @Test
    fun zeroHeightLineIsNotADrawnLine() {
        val plan = planDayTimelineBlockLines(
            blockHeightDp = 63.0,
            contentVerticalPaddingDp = 3.0,
            specs = listOf(
                DayTimelineLineSpec(CourseName, NameDpAt1x),
                DayTimelineLineSpec(TimeAndTeacher, 0.0),
                DayTimelineLineSpec(Room, MetaDpAt1x),
            ),
        )
        assertEquals(listOf(CourseName, Room), plan.lines)
    }

    /** 空候选集与"全是 pinned"都不炸；pinned 行始终在场 */
    @Test
    fun emptySpecsYieldEmptyPlanAndPinnedLineSurvivesAnywhereInTheList() {
        assertEquals(
            emptyList<DayTimelineBlockLine>(),
            planDayTimelineBlockLines(63.0, 3.0, emptyList()).lines,
        )
        val roomFirst = planDayTimelineBlockLines(
            blockHeightDp = 4.0,
            contentVerticalPaddingDp = 3.0,
            specs = listOf(DayTimelineLineSpec(CourseName, NameDpAt1x), DayTimelineLineSpec(Room, MetaDpAt1x)),
        )
        assertEquals(listOf<DayTimelineBlockLine>(CourseName), roomFirst.lines)
    }

    // ---- 场景数字：与调用点同一把算式 ----------------------------------------

    private fun Case.blockHeightDp(): Double = blockHeightOverride
        ?: (minutes * DpPerMinute).coerceAtLeast(MinTouchTargetDp)

    private fun realSpecs(fontScale: Float, hasRemark: Boolean) = listOfNotNull(
        DayTimelineLineSpec(CourseName, nameDp(fontScale)),
        DayTimelineLineSpec(TimeAndTeacher, metaDp(fontScale)),
        DayTimelineLineSpec(Room, metaDp(fontScale)),
        if (hasRemark) DayTimelineLineSpec(Remark, metaDp(fontScale)) else null,
    )

    private fun nameDp(fontScale: Float) = if (fontScale > 1.5f) NameDpAt2x else NameDpAt1x

    private fun metaDp(fontScale: Float) = if (fontScale > 1.5f) MetaDpAt2x else MetaDpAt1x

    private companion object {
        /** DesignTokens.dayHeightPerMinute（T61b① 从 1.35 抬到 1.40 的那一档） */
        const val DpPerMinute = 1.40

        /** DesignTokens.minTouchTarget：短块补到这里为止 */
        const val MinTouchTargetDp = 48.0

        /**
         * 调用点实测的两档行高（dp）：装机 buaa36 镜像、密度 2.625、色块 Text 节点的实排 bounds。
         * fontScale 2.0 那两枚不是 1.0 的整 2 倍（99px≠104px、91px≈90px×1.01）——
         * 字体度量本来就不线性，这也是"标称 × fontScale"那套算式靠不住的另一个证据。
         */
        const val NameDpAt1x = 19.809524 // 52px
        const val MetaDpAt1x = 17.142857 // 45px
        const val NameDpAt2x = 37.714286 // 99px
        const val MetaDpAt2x = 34.666668 // 91px

        /** 块内文字的单侧上下内边距（DayView 的 BlockTextVerticalPadding） */
        const val VerticalPadding = 3.0
    }
}
