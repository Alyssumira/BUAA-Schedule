package com.buaa.schedule.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CoursePeriodsTest {

    @Test
    fun segmentsOfContiguousPeriods() {
        assertEquals(listOf(1..3), listOf(1, 2, 3).toPeriodSegments(NO_PERIOD_GAP))
    }

    @Test
    fun segmentsOfNonContiguousPeriods() {
        assertEquals(listOf(1..2, 9..10), listOf(1, 2, 9, 10).toPeriodSegments(NO_PERIOD_GAP))
    }

    @Test
    fun segmentsOfSinglePeriod() {
        assertEquals(listOf(5..5), listOf(5).toPeriodSegments(NO_PERIOD_GAP))
    }

    @Test
    fun segmentsIgnoreDuplicatesAndOrder() {
        assertEquals(listOf(1..2, 4..5), listOf(5, 2, 1, 4, 2).toPeriodSegments(NO_PERIOD_GAP))
    }

    @Test
    fun segmentsOfEmptyList() {
        assertEquals(emptyList<IntRange>(), emptyList<Int>().toPeriodSegments(NO_PERIOD_GAP))
    }

    @Test
    fun labelOfContiguousPeriods() {
        assertEquals("第1-2节", periodLabel(listOf(1, 2), NO_PERIOD_GAP))
    }

    @Test
    fun labelOfNonContiguousPeriods() {
        assertEquals("第1-2,9-10节", periodLabel(listOf(1, 2, 9, 10), NO_PERIOD_GAP))
    }

    @Test
    fun labelOfSinglePeriod() {
        assertEquals("第3节", periodLabel(listOf(3), NO_PERIOD_GAP))
    }

    @Test
    fun coursePeriodBounds() {
        val course = Course(
            name = "高数",
            dayOfWeek = 1,
            periods = listOf(9, 2, 1, 10),
            weeks = listOf(1),
        )
        assertEquals(1, course.startPeriod)
        assertEquals(10, course.endPeriod)
    }

    // —— R5 F-30：节次号相邻 ≠ 连堂 ——————————————————————————————

    private val defaultGap = periodGapMinutesOf(TimeSlotProfile.DEFAULT.toStartEndTimes())

    @Test
    fun lunchBreakSplitsAdjacentPeriods() {
        // 第 5 节 12:15 下课，第 6 节 14:00 才上课：中间隔着 105 分钟午饭
        assertEquals(listOf(5..5, 6..6), listOf(5, 6).toPeriodSegments(defaultGap))
    }

    @Test
    fun shortBreakStillCountsAsLinked() {
        // 课间只有 5-15 分钟，1-2、6-7 这类真连堂不能被切开
        assertEquals(listOf(1..2), listOf(1, 2).toPeriodSegments(defaultGap))
        assertEquals(listOf(6..7), listOf(6, 7).toPeriodSegments(defaultGap))
        assertEquals(listOf(9..10), listOf(9, 10).toPeriodSegments(defaultGap))
    }

    @Test
    fun dinnerBreakSplitsTenFromEleven() {
        assertEquals(listOf(10..10, 11..11), listOf(10, 11).toPeriodSegments(defaultGap))
    }

    @Test
    fun customTimetableWithLongRecessSplitsEveryPair() {
        // 用户自定义作息：每节之间空 45 分钟 —— 那就不算连堂
        val slots = listOf(
            TimeSlot(number = 1, startTime = "08:00", endTime = "08:45"),
            TimeSlot(number = 2, startTime = "09:30", endTime = "10:15"),
            TimeSlot(number = 3, startTime = "10:20", endTime = "11:05"),
        ).toStartEndTimes()
        val gap = periodGapMinutesOf(slots)
        assertEquals(listOf(1..1, 2..3), listOf(1, 2, 3).toPeriodSegments(gap))
    }

    @Test
    fun missingTimetableFallsBackToPeriodAdjacency() {
        // 节次表残缺时退回旧行为：只按节次号相邻切段
        val gap = periodGapMinutesOf(emptyMap())
        assertEquals(listOf(5..6), listOf(5, 6).toPeriodSegments(gap))
    }

    // —— P1-2 回归：文案口径必须与切段口径同源 ——————————————————————

    @Test
    fun labelSplitsAcrossLunchBreak() {
        // 网格把 5、6 节画成两张卡，文案就不能再写「第5-6节」。
        // 多段的拼法是"数字段用逗号连、前后缀只包一次"：第5,6节（同 第1-2,9-10节）
        assertEquals("第5,6节", periodLabel(listOf(5, 6), defaultGap))
        assertEquals("第5,6节", periodLabelOf(listOf(5, 6), TimeSlotProfile.DEFAULT))
    }

    @Test
    fun labelKeepsLinkedPairsInOneSpan() {
        assertEquals("第1-2节", periodLabel(listOf(1, 2), defaultGap))
        assertEquals("第1-2节", periodLabelOf(listOf(1, 2), TimeSlotProfile.DEFAULT))
    }

    @Test
    fun labelSegmentCountMatchesSplitSegmentCount() {
        // 不变量：文案里的段数 == 切段结果的段数。两边读的是同一个 gapMinutes，
        // 任何一侧偷偷换口径（P1-2 就是这么藏了两轮的）都会被这条抓住。
        for (periods in listOf(
            listOf(5, 6),
            listOf(1, 2, 9, 10),
            listOf(10, 11),
            listOf(1, 2, 3, 7, 8),
            listOf(3),
        )) {
            val segments = periods.toPeriodSegments(defaultGap)
            val printed = periodLabel(periods, defaultGap)
                .removePrefix("第")
                .removeSuffix("节")
                .split(",")
            assertEquals(
                "periods=$periods 的文案「$printed」与切段 $segments 段数不一致",
                segments.size,
                printed.size,
            )
        }
    }

    // —— T26：没有节次就是"这一项不出场"，不是「第节」 ——————————————

    @Test
    fun emptyPeriodsProduceNoLabelAtAll() {
        // 「第节」是包装留着、内容没了：joinToString 给出 ""，再被套上「第…节」
        assertEquals("", periodLabel(emptyList(), NO_PERIOD_GAP))
        // 节次表残缺的那一支同样不能造出内容（gap 恒为 null 时 [5,6] 会并成一段，空表还是空表）
        assertEquals("", periodLabel(emptyList(), periodGapMinutesOf(emptyMap())))
        // 节次表兜底 ≠ 节次兜底：DEFAULT 表补的是 slots，periods 为空仍然什么都没有
        assertEquals("", periodLabelOf(emptyList(), TimeSlotProfile.DEFAULT))
        assertEquals("", periodLabelOf(emptyList(), emptyList()))
    }

    @Test
    fun nonEmptyLabelsKeepTheirExactSpelling() {
        // 回归守卫：下面这些串是用户看惯的既有文案，这一轮只改「空节次」那一支。
        // 逐字写死，不用任何生产函数反推 —— 否则改错口径时这条会跟着一起绿。
        assertEquals("第1-2节", periodLabel(listOf(1, 2), NO_PERIOD_GAP))
        assertEquals("第1-2,9-10节", periodLabel(listOf(1, 2, 9, 10), NO_PERIOD_GAP))
        assertEquals("第3节", periodLabel(listOf(3), NO_PERIOD_GAP))
        assertEquals("第5,6节", periodLabel(listOf(5, 6), defaultGap))
        assertEquals("第1-2节", periodLabel(listOf(1, 2), defaultGap))
        assertEquals("第1-2,9-10节", periodLabel(listOf(1, 2, 9, 10), defaultGap))
        assertEquals("第5,6节", periodLabelOf(listOf(5, 6), TimeSlotProfile.DEFAULT))
        assertEquals("第1-2节", periodLabelOf(listOf(1, 2), TimeSlotProfile.DEFAULT))
        // 单段版：课堂实况、课前提醒与日视图色块读的都是它，段一定来自切段结果，恒非空
        assertEquals("第1节", periodLabel(1..1))
        assertEquals("第1-2节", periodLabel(1..2))
    }

    @Test
    fun bareLabelStripsTheWrapperAndKeepsEmptyEmpty() {
        assertEquals("1-2", unwrappedPeriodLabel(periodLabel(listOf(1, 2), NO_PERIOD_GAP)))
        assertEquals("1-2,9-10", unwrappedPeriodLabel(periodLabel(listOf(1, 2, 9, 10), NO_PERIOD_GAP)))
        assertEquals("3", unwrappedPeriodLabel(periodLabel(listOf(3), NO_PERIOD_GAP)))
        // 空进空出：组件那一头是"有内容才补「节」字"，靠的就是这一条
        assertEquals("", unwrappedPeriodLabel(periodLabel(emptyList(), NO_PERIOD_GAP)))
    }

    @Test
    fun joinMetaDropsAbsentPartsAndTheirSeparators() {
        assertEquals("A · B", joinMeta("A", null, "", "   ", "B"))
        assertEquals("", joinMeta("", null, "  "))
        assertEquals("A，B", joinMeta(listOf("A", "", "B"), separator = "，"))
        assertEquals("A B", joinMeta("A", "B", separator = " "))
        // 节次缺席的那一整段：不留尾部分隔符，也不留「第节」
        assertEquals(
            "高等数学 · J3-101",
            joinMeta("高等数学", "J3-101", periodLabel(emptyList(), NO_PERIOD_GAP)),
        )
    }
}
