package com.buaa.schedule.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `WeekViewLineBudget.kt` 的表驱动单测（T296②）—— 零 android：卡高、行高、视口实高、内边距、比例
 * 全是数字，密度与 fontScale 归 Composable 侧量好再递进来（同 [DayTimelineBlockLinesTest] 的口径）。
 *
 * 覆盖的四族格子：
 * 1. **两把尺 disagreement**：同一格容量分别按「排版表标称 × fontScale」（改前那把）与
 *    「实测行盒」（改后这把）走一遍，逐档给出 fontScale 1.0 / 1.15 / 1.3 / 2.0。
 * 2. **每一枚边界的两侧各一格**：tier 的 0/1/wanted、轴的 3/2/1/0 行、卡的副信息行与节次行取舍、
 *    行高的托底与首帧回退。
 * 3. **F2 的两行时间**：`"起\n止"` 必须和节次号一起进同一笔账，行高不够时**不画**而不是照画。
 * 4. **非真空格**：至少一格证明「旧算式承诺装得下、新算式说装不下」（在册 #537 F1/F2 的算术本体）。
 *
 * 实测那两枚锚点是本仓已记录的装机数（`DayTimelineBlockLinesTest:157-160`：labelMedium
 * 45px = 17.142857dp@1.0、91px = 34.666668dp@2.0，**不是** 1.0 那枚的整 2 倍）。
 * 1.15 / 1.3 两档的实测行盒按 1.0 那枚线性外推 —— 真实值（以及 MiSans 等厂商字体的垂直度量）是
 * **待装机读数**；外推只是本表的输入，内核不吃"线性"这个假设，而 16 × fontScale 恒比
 * 17.142857 × fontScale 小 7.1%，所以这格外推对旧算式是**宽松**的一侧，不是苛刻的一侧。
 */
class WeekViewLineBudgetTest {

    // ---- ① tier 的边界：每枚门槛两侧各一格 --------------------------------------

    @Test
    fun tierCountsWholeLinesAndNeverOverPromises() {
        val line = 17.0
        // 0 行：容量归零 / 为负 → 什么都不承诺
        assertNothingFits(weekLineTier(0.0, line, 3))
        assertNothingFits(weekLineTier(-4.0, line, 3))
        // 一行：边界下侧 NothingFits，边界上侧 Fit（wanted 就是一行）
        assertNothingFits(weekLineTier(line - EPS, line, 3))
        val one = weekLineTier(line, line, 1) as WeekLineTier.Fit
        assertEquals(1, one.lines)
        assertEquals(0.0, one.spareDp, EPS)
        // 两行：wanted=3 时下侧只承诺一行（ShortBy）；wanted=2 时刚好 Fit
        val justBelowTwo = weekLineTier(line * 2.0 - EPS, line, 3) as WeekLineTier.ShortBy
        assertEquals(1, justBelowTwo.lines)
        assertEquals(3, justBelowTwo.wantedLines)
        val exactlyTwo = weekLineTier(line * 2.0, line, 2) as WeekLineTier.Fit
        assertEquals(2, exactlyTwo.lines)
        assertEquals(0.0, exactlyTwo.spareDp, EPS)
        // 三行（== wanted）装得下：Fit，余量如实记下
        val three = weekLineTier(line * 3.0 + 1.0, line, 3) as WeekLineTier.Fit
        assertEquals(3, three.lines)
        assertEquals(1.0, three.spareDp, EPS)
        // 容量比 wanted 还多：只承诺 wanted 行（多出来的不是"再挤一行"的许可证）
        val generous = weekLineTier(line * 5.0, line, 3) as WeekLineTier.Fit
        assertEquals(3, generous.lines)
        assertEquals(line * 2.0, generous.spareDp, EPS)
    }

    @Test
    fun tierRefusesToPromiseWithoutARuler() {
        // 行高 ≤0 = 这一格压根没量到：不许退化成"随便承诺一行"
        assertNothingFits(weekLineTier(100.0, 0.0, 3))
        assertNothingFits(weekLineTier(100.0, -1.0, 3))
        // 没想画任何行 → 整段容量都是余量
        val none = weekLineTier(100.0, 17.0, 0) as WeekLineTier.Fit
        assertEquals(0, none.lines)
        assertEquals(100.0, none.spareDp, EPS)
    }

    // ---- ② 两把尺 disagreement：标称 × fontScale vs 实测行盒 ---------------------

    /** fontScale 1.0：旧尺说三行装得下（余 3dp），实测行盒说只装得下两行（差 0.43dp ≈ 装机那 1px）。 */
    @Test
    @Suppress("MagicNumber")
    fun oldRulerPromisedFitWhereMeasuredBoxSaysOverflow() {
        val nominalAt1x = nominalLineHeightDp(1.0)
        val measuredAt1x = measuredLineHeightDp(1.0)
        val available = 51.0

        val old = weekLineTier(available, nominalAt1x, 3)
        val now = weekLineTier(available, measuredAt1x, 3)
        assertTrue("旧尺在这一格该说装得下（这正是被 T61b① 判死的过度承诺）", old is WeekLineTier.Fit)
        assertEquals(3, (old as WeekLineTier.Fit).lines)
        assertTrue("实测行盒在同一格该说装不下", now is WeekLineTier.ShortBy)
        val short = now as WeekLineTier.ShortBy
        assertEquals(2, short.lines)
        assertEquals(3, short.wantedLines)
        // 3 行实测行盒 − 容量 = 0.428571dp ≈ 装机那 1px（45px×3=135px 对 51dp≈133.875px）
        assertEquals(0.428571, short.deficitDp, 1e-5)
    }

    /**
     * 逐档扫两把尺的差：1.0 用实测锚点、2.0 用**非线性**那枚装机实测（91px，不是 45px 的整 2 倍），
     * 1.15 / 1.3 用对旧尺宽松的外推 —— 四档里旧尺都至少比实测多承诺一行。
     */
    @Test
    fun measuredRulerNeverPromisesMoreThanNominal() {
        for (fontScale in listOf(1.0, 1.15, 1.3, 2.0)) {
            val nominal = nominalLineHeightDp(fontScale)
            val measured = measuredLineHeightDp(fontScale)
            assertTrue(
                "fontScale $fontScale：实测行盒该 ≥ 标称（标称只是下限）",
                measured >= nominal - EPS,
            )
            // 同一格容量，旧尺承诺的行数从不比新尺少 —— 差额就是被过度承诺的那一截
            for (height in listOf(26.3, 34.0, 51.0, 55.5, 63.0, 100.0, 128.0)) {
                val oldLines = tierLines(weekLineTier(height, nominal, 3))
                val nowLines = tierLines(weekLineTier(height, measured, 3))
                assertTrue(
                    "fontScale $fontScale @ $height dp：新尺反而比旧尺多承诺（$nowLines > $oldLines）——不可能，" +
                        "实测行盒不小于标称",
                    nowLines <= oldLines,
                )
            }
        }
        // 2.0 那枚实测不是 1.0 的整 2 倍：字体度量本来就不线性，这条是本表不用标称的理由
        assertTrue(
            "91px 折出来的 2.0 档不该等于 1.0 档的整 2 倍",
            kotlin.math.abs(measuredLineHeightDp(2.0) - measuredLineHeightDp(1.0) * 2.0) > 0.1,
        )
    }

    // ---- ③ F2：节次轴的三行一起算，两行时间不再无条件在场 ------------------------

    @Test
    fun timeAxisDrawsAllThreeLinesOnlyWhenAllThreeFit() {
        val line = 17.0
        // 三行下侧：节次号让位，但两行时间照画（两行还装得下）
        val two = planWeekTimeAxis(heightDp = line * 3.0 - EPS, lineHeightDp = line)
        assertFalse(two.showPeriodNumber)
        assertTrue(two.showTwoLineTime)
        assertFalse(two.showStartOnly)
        // 三行上侧：整列三行
        val three = planWeekTimeAxis(heightDp = line * 3.0, lineHeightDp = line)
        assertTrue(three.showPeriodNumber)
        assertTrue(three.showTwoLineTime)
        assertFalse(three.showStartOnly)
        // 两行下侧：并成一行都做不到（48dp 定宽列横向放不下「08:00–08:45」），只画起时
        val one = planWeekTimeAxis(heightDp = line * 2.0 - EPS, lineHeightDp = line)
        assertFalse(one.showPeriodNumber)
        assertFalse(one.showTwoLineTime)
        assertTrue(one.showStartOnly)
        // 一行下侧：整档不画 —— 改前这一格两行照画、上下都出界
        assertAxisEmpty(planWeekTimeAxis(heightDp = line - EPS, lineHeightDp = line))
        assertAxisEmpty(planWeekTimeAxis(heightDp = 0.0, lineHeightDp = line))
        assertAxisEmpty(planWeekTimeAxis(heightDp = 120.0, lineHeightDp = 0.0))
    }

    /**
     * F2 的非真空格（1.15×，审计档记的那一格）：行高 55.5dp 时旧门槛
     * `height >= 16 × 1.15 × 3` 成立 ⇒ 三行全画，而实测行盒要 3 × 19.714 = 59.14dp ⇒ 溢出 3.64dp。
     */
    @Test
    @Suppress("MagicNumber")
    fun axisOldGatePaintedThreeLinesThatDoNotFit() {
        val fontScale = 1.15
        val nominal = nominalLineHeightDp(fontScale)
        val measured = measuredLineHeightDp(fontScale)
        val height = 55.5
        assertTrue("旧门槛在这一格是放行的（三行全画）", height >= nominal * 3.0)
        val plan = planWeekTimeAxis(heightDp = height, lineHeightDp = measured)
        assertFalse("实测口径下节次号必须让位", plan.showPeriodNumber)
        assertTrue(plan.showTwoLineTime)
        val short = plan.tier as WeekLineTier.ShortBy
        assertEquals(2, short.lines)
        // 旧算式画出来的三行按实测行盒要占多少：59.142857 > 55.5 ⇒ 溢出 3.642857dp
        assertEquals(59.142857, measured * 3.0, 1e-5)
        assertEquals(3.642857, short.deficitDp, 1e-5)
    }

    /**
     * F2 的横屏档（视口均分 + 横屏，审计算式 26.3dp）：旧形状两行照画，
     * 实测行盒 @1.0 只要 34.2857dp 就已超 26.3 ⇒ 溢出 7.98dp（上下各甩一半）。
     */
    @Test
    fun landscapeFittedRowCannotHoldTwoAxisLines() {
        val height = 26.3
        val measured = measuredLineHeightDp(1.0)
        val plan = planWeekTimeAxis(heightDp = height, lineHeightDp = measured)
        assertFalse(plan.showTwoLineTime)
        assertTrue(plan.showStartOnly)
        val short = plan.tier as WeekLineTier.ShortBy
        assertEquals(1, short.lines)
        // tier 的 deficit 是对"三行都想画"记的账
        assertEquals(3 * measured - height, short.deficitDp, 1e-6)
        // 改前无条件画的那两行，在这一格按实测行盒要 34.285714dp > 26.3dp ⇒ 溢出 7.985714dp，
        // contentAlignment = Center 把它对称甩到上下各半 —— 这就是整条节次轴互相压字的量
        val twoLineOverflow = 2 * measured - height
        assertEquals(7.985714, twoLineOverflow, 1e-5)
        assertTrue("这一档旧形状确实超出一行以上", twoLineOverflow > 7.0)
    }

    // ---- ④ F1：课程卡的取舍 ------------------------------------------------------

    @Test
    fun courseCardKeepsMetaLineOnlyWhenATitleLineIsStillPaid() {
        val line = 17.0
        val padding = 5.0
        // available = 44 − 10 = 34 = 2 行：副信息 + 一行课名，正好
        val two = planWeekCourseCard(44.0, padding, line, line, hasMetaLine = true, hasPeriodLine = false)
        assertTrue(two.showMetaLine)
        assertEquals(1, two.titleLines)
        // 差一点点（available 33.9）：副信息让位，课名仍然画（pinned）
        val tight = planWeekCourseCard(43.9, padding, line, line, hasMetaLine = true, hasPeriodLine = false)
        assertFalse("留不出整一行课名时副信息必须让位", tight.showMetaLine)
        assertEquals(1, tight.titleLines)
        // 没有副信息内容的卡不占容量：那 34dp 全给课名（两行）
        val noMeta = planWeekCourseCard(44.0, padding, line, line, hasMetaLine = false, hasPeriodLine = false)
        assertFalse(noMeta.showMetaLine)
        assertEquals(2, noMeta.titleLines)
    }

    @Test
    fun courseCardPutsPeriodLineAfterMetaLineAndNeverDropsTitle() {
        val line = 17.0
        val padding = 5.0
        // available = 62 − 10 = 52：副信息 + 节次行 + 一行课名（默认档 62dp 卡的真实形状）
        val full = planWeekCourseCard(62.0, padding, line, line, hasMetaLine = true, hasPeriodLine = true)
        assertTrue(full.showMetaLine)
        assertTrue(full.showPeriodLine)
        assertEquals(1, full.titleLines)
        // 容量只够两行时，排在后面的节次行让位（行序 = 信息优先级，不插队）
        val oneRowShort = planWeekCourseCard(44.0, padding, line, line, hasMetaLine = true, hasPeriodLine = true)
        assertTrue(oneRowShort.showMetaLine)
        assertFalse("副信息已经吃掉第二行的名额，节次行不许反过来把课名挤成零行", oneRowShort.showPeriodLine)
        assertEquals(1, oneRowShort.titleLines)
        // 容量连一行课名都不够：课名是 pinned 行，照画，但溢出由 tier 留痕
        val starved = planWeekCourseCard(14.0, padding, line, line, hasMetaLine = true, hasPeriodLine = true)
        assertEquals(1, starved.titleLines)
        assertFalse(starved.showMetaLine)
        assertNothingFits(starved.titleTier)
    }

    @Test
    fun courseTitleLineCapIsThreeAndScalesWithRuler() {
        val padding = 5.0
        // available = 120 − 10 = 110：实测行盒 → 3 行封顶（不是 6 行）
        val capped = planWeekCourseCard(120.0, padding, 17.0, 17.0, hasMetaLine = false, hasPeriodLine = false)
        assertEquals(3, capped.titleLines)
        assertEquals(3, (capped.titleTier as WeekLineTier.Fit).lines)
        // 同一格用标称尺：也是 3 行 —— 两把尺在这一格同意（本卡不是"一律更严"，是"换对尺"）
        val nominalCapped = planWeekCourseCard(120.0, padding, 16.0, 16.0, hasMetaLine = false, hasPeriodLine = false)
        assertEquals(3, nominalCapped.titleLines)
        // maxTitleLines 枚数可递（调用点用默认 3，这里钉的是"上限不是写死在算式里"）
        val twoMax = planWeekCourseCard(120.0, padding, 17.0, 17.0, false, false, maxTitleLines = 2)
        assertEquals(2, twoMax.titleLines)
    }

    /**
     * F1 在**计划层**的非真空格（1.0×，available 34dp）：旧尺按 `16dp` 记账 ⇒ 副信息行 + 课名行
     * 一共 32dp ≤ 34dp，"装得下"；同一格按实测行盒 17.142857dp ⇒ 两行要 34.285714dp > 34dp，
     * 于是新算式把副信息砍掉。旧尺那句"装得下"是假账。
     */
    @Test
    fun oldCardPlanPromisedAMetaLineTheMeasuredBoxCannotFit() {
        val cardHeight = 44.0
        val padding = 5.0
        val available = cardHeight - padding * 2.0
        val old = planWeekCourseCard(
            cardHeight, padding, nominalLineHeightDp(1.0), nominalLineHeightDp(1.0),
            hasMetaLine = true, hasPeriodLine = false,
        )
        val now = planWeekCourseCard(
            cardHeight, padding, measuredLineHeightDp(1.0), measuredLineHeightDp(1.0),
            hasMetaLine = true, hasPeriodLine = false,
        )
        assertTrue("旧尺在该格承诺了副信息行", old.showMetaLine)
        assertFalse("实测行盒在同一格必须收回这句承诺", now.showMetaLine)
        val promisedMeasuredHeight = (old.titleLines + (if (old.showMetaLine) 1 else 0)) * measuredLineHeightDp(1.0)
        assertTrue(
            "旧尺承诺的内容按实测行盒算只有 ${promisedMeasuredHeight}dp，容量 ${available}dp —— 那它就该是装得下的",
            promisedMeasuredHeight > available,
        )
        assertEquals(0.285714, promisedMeasuredHeight - available, 1e-5)
    }

    // ---- ⑤ F3：行高 —— 旋钮照吃、实测一行托底、首帧说实话 ------------------------

    @Test
    fun fittedRowConsumesTheRowScaleKnob() {
        val floor = weekRowHeightFloorDp(17.142857, 5.0)
        val viewport = 800.0
        val small = weekFitViewportRowHeightDp(viewport, 8, 0.75, floor, 48.0)
        val big = weekFitViewportRowHeightDp(viewport, 8, 1.5, floor, 48.0)
        // 改前这一档两枚算出来是同一个数（weekRowScale 压根不被消费）
        assertTrue("滑杆在视口均分档必须真的改行数出来的高度", big > small + EPS)
        assertEquals((viewport / 8) * 0.75, small, EPS)
        assertEquals((viewport / 8) * 1.5, big, EPS)
    }

    @Test
    fun fittedRowFloorsAtOneMeasuredLineAndTellsTruthOnFirstFrame() {
        val line = 17.142857
        val floor = weekRowHeightFloorDp(line, 5.0)
        assertEquals(line + 10.0, floor, EPS)
        // 托底生效：14 节 × 视口 300dp ⇒ 均分 21.4dp < 一行 + 内边距
        val floored = weekFitViewportRowHeightDp(300.0, 14, 1.0, floor, 64.0)
        assertEquals(floor, floored, EPS)
        // 托底不生效：同一组参数换成 6 节
        val roomy = weekFitViewportRowHeightDp(300.0, 6, 1.0, floor, 64.0)
        assertEquals(50.0, roomy, EPS)
        // 首帧：视口还没量到 → 回退值（且仍不低于地板），不是 0、也不是凭空造一枚
        assertEquals(64.0, weekFitViewportRowHeightDp(0.0, 12, 1.0, floor, 64.0), EPS)
        assertEquals(floor, weekFitViewportRowHeightDp(-1.0, 12, 1.0, floor, 20.0), EPS)
        // 视口矮于下限按下限均分（改前 `coerceAtLeast(200)` 的语义不变）；边界两侧各一格
        assertEquals(200.0 / 10, weekFitViewportRowHeightDp(150.0, 10, 1.0, 0.0, 64.0), EPS)
        assertEquals(250.0 / 10, weekFitViewportRowHeightDp(250.0, 10, 1.0, 0.0, 64.0), EPS)
        // 节次数为 0（作息表空表）不许除零
        assertEquals(200.0, weekFitViewportRowHeightDp(200.0, 0, 1.0, 0.0, 64.0), EPS)
        // 比例坏值（NaN / 0 / 负）折回 1.0：这一档屏上念的是比例，不是高度
        assertEquals(100.0, weekFitViewportRowHeightDp(800.0, 8, Double.NaN, 0.0, 64.0), EPS)
        assertEquals(100.0, weekFitViewportRowHeightDp(800.0, 8, 0.0, 0.0, 64.0), EPS)
        assertEquals(100.0, weekFitViewportRowHeightDp(800.0, 8, -2.0, 0.0, 64.0), EPS)
    }

    @Test
    fun rowFloorIsNotInventedWhenNothingWasMeasured() {
        // 没量到行高时地板是 0（不设地板），内核不自己造一枚数 —— 调用点那把尺量不到时走的是偏高退让支路
        assertEquals(0.0, weekRowHeightFloorDp(0.0, 5.0), EPS)
        assertEquals(0.0, weekRowHeightFloorDp(-3.0, 5.0), EPS)
        assertEquals(27.0, weekRowHeightFloorDp(17.0, 5.0), EPS)
    }

    // ---- 小工具 ------------------------------------------------------------------

    private fun assertNothingFits(tier: WeekLineTier) {
        assertTrue("该档是 NothingFits，实际 $tier", tier is WeekLineTier.NothingFits)
    }

    private fun assertAxisEmpty(plan: WeekTimeAxisPlan) {
        assertFalse(plan.showPeriodNumber)
        assertFalse(plan.showTwoLineTime)
        assertFalse(plan.showStartOnly)
        assertNothingFits(plan.tier)
    }

    private fun tierLines(tier: WeekLineTier): Int = when (tier) {
        is WeekLineTier.Fit -> tier.lines
        is WeekLineTier.ShortBy -> tier.lines
        WeekLineTier.NothingFits -> 0
    }

    /** 改前那把尺：`TextStyle.lineHeight.toDp()` 对 sp 走的就是 `sp × fontScale`（labelMedium = 16sp） */
    private fun nominalLineHeightDp(fontScale: Double): Double = 16.0 * fontScale

    /**
     * 改后这把尺：本仓已记录的装机实测（labelMedium 45px=17.142857dp@1.0、91px=34.666668dp@2.0），
     * 1.15 / 1.3 两档线性外推（真实值＝待装机读数；外推对旧尺是宽松的一侧）。
     */
    private fun measuredLineHeightDp(fontScale: Double): Double = when (fontScale) {
        1.0 -> MetaDpAt1x
        2.0 -> MetaDpAt2x
        else -> MetaDpAt1x * fontScale
    }

    private companion object {
        const val EPS = 1e-9

        /** 装机实测：45px / 密度 2.625（`DayTimelineBlockLinesTest` 同一枚数） */
        const val MetaDpAt1x = 17.142857

        /** 装机实测：91px —— 不是 45px 的整 2 倍（字体度量本来就不线性） */
        const val MetaDpAt2x = 34.666668
    }
}
