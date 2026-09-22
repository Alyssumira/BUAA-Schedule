package com.buaa.schedule.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T69 第 0 步的**实测账**：顶栏「学期统计」入口的宽度预算，全部来自 emulator-5554 的像素，
 * 不是按"字数 × 字号"估出来的数。本文件自包含（不引用任何生产代码），它钉的是
 * "落点为什么选第一行、为什么第二档不可用"这笔账的来路。
 *
 * ── 量具与口径 ─────────────────────────────────────────────────────────────
 * 设备：`emulator-5554`（Pixel 镜像），`wm density` = **420** ⇒ density = 420/160 = **2.625**；
 * `settings get system font_scale` = **1.0**；`nproc` = **6**（≥5，不会被
 * `GlassJankDecision.kt:89` 并进 reduce-motion）。屏幕 **1080×2400**，
 * 1080/2.625 = 411.43 ⇒ `LocalConfiguration.screenWidthDp` 报 **411**。
 *
 * 两把尺各自独立，互相核对：
 * 1. **uiautomator dump**（`/sdcard/t69g.xml` → `D:/schedule/.tmp/T69b-pre-hierarchy.xml`）
 *    量到的是 Compose 语义节点的整数像素矩形；
 * 2. **像素扫描**（`D:/schedule/.tmp/T69b-11-pre-week.png`，PIL 逐点扫 surfaceVariant
 *    色块 (225,226,236) 与墨色 (<120) 的连续段），量到的是画出来的东西到底占了几格。
 *
 * ⚠️ 本仓 `:app` 的 JVM 单测既没有 Robolectric 也没有 `ui-test`（`testImplementation` 只有
 * junit），跑不起 `TextMeasurer` —— 所以宽度只能像 T61b 那样**下设备量**，
 * 再把量到的整数像素当常量传进判据（下面每个常量都注了它的出处）。
 * 口径同 T61b：**向上取整到整 px**，标称值只是下限。
 *
 * ── 实测数（px / dp）───────────────────────────────────────────────────────
 * | 量到的东西 | px | dp |
 * |---|---|---|
 * | 「课次」胶囊内宽 = labelMedium 两枚 CJK（周课表页签第二行） | 62 | 23.619 |
 * | 「周课表」labelLarge 三枚 CJK | 108 | 41.143 |
 * | 「今日」labelLarge 两枚 CJK | 72 | 27.429 |
 * | 「校区切换」labelLarge 四枚 CJK | 144 | 54.857 |
 * | 左列实占（周课表页签，宽的那一档：「9月22日 星期二」labelMedium） | 216 | 82.286 |
 * | 左列实占（今日页签：「今日课表」titleMedium） | 168 | 64.000 |
 * | `GlassSegmentedControl(listOf("周课表","今日"))` 实占 | 372 | 141.714 |
 * | 第一行内容宽（1080 − spaceL 42 − spaceS 21） | 1017 | 387.429 |
 * | 第二行内容宽（1080 − spaceS ×2） | 1038 | 395.429 |
 * | 校区按钮实占（今日页签 x21..254） | 233 | 88.762 |
 *
 * 由 62px 这一枚锚点按**字数线性**外推（线性在 labelLarge 上用 2/3/4 枚三档独立样本验过：
 * 72 / 108 / 144 恰成 36.0px 的等差，见 [cjkAdvanceIsLinearInCharacterCountOnDevice]）：
 * - 「学期统计」labelMedium 四枚 = **124px = 47.238dp**
 * - 「统计」  labelMedium 两枚 = **62px = 23.619dp**（这一档就是锚点本身）
 * 照「课次」那颗胶囊的配方（外观层 `padding(horizontal = spaceS)`）加两侧内衬 2×21px：
 * - 长档胶囊实占 = 124 + 42 = **166px = 63.238dp**
 * - 短档胶囊实占 = 62 + 42 = **104px = 39.619dp**
 *
 * ── 为什么落在第一行（首选档成立）────────────────────────────────────────────
 * 第一行留给入口的预算 = 1017 − 372（分段控件）− 216（左列宽的那一档）= **429px = 163.429dp**
 * ≥ 166px，余 **263px = 100.190dp**；今日页签那一档余 477px = 181.714dp。**两个页签都放得下**，
 * 且入口排在 `GlassSegmentedControl` **之前**：分段控件是这一行的最后一个子节点、
 * 右缘由行尾 padding 钉死（实测 x=687..1059），前面插不进它的位，所以**它一格都不跳**。
 * 左列带 `weight(1f)` + `maxLines=1` + `Ellipsis`，胶囊要的那一截从它的份里扣，
 * 扣不走（216 → 479 份里只用 166）。
 *
 * ── 为什么第二档（第二行尾部）不可用 ─────────────────────────────────────────
 * 第二行 `ScheduleToolbarRow` 的周次簇里，`weight(1f)` 写在 `AnimatedVisibility` **内部**
 * 那枚居中盒子上，而 `AnimatedVisibility` 自己在外面那一行里**没有** weight ——
 * 于是簇按父级给的整份宽度（1038px）铺开，排在它后面的 `campusSlot` 拿到 maxWidth = 0，
 * **整块被挤到屏外**：像素证据是周课表页签第二行 x=860..1079 全段只有 ‹ › 那颗箭头的
 * 墨色（x=995..1003），校区按钮那块 (225,226,236) 底板一个像素都没出现，
 * 而同一枚按钮在今日页签实测占 233px（x21..254）。
 * ⇒ 第二行的剩余预算是 **0dp**，往它尾部再加一颗胶囊只会把已在场的那一块推得更远。
 * **这条溢出是本卡之外发现的既有缺陷，已单列进报告残账，本卡不改。**
 */
class StatsEntryTopBarBudgetMeasuredBaselineTest {

    /**
     * CJK 进宽对字数**线性**（同一样式下）：labelLarge 两枚 72 / 三枚 108 / 四枚 144，
     * 每枚都是 36.0px。这条成立，才允许拿「课次」那一枚 2 字锚点外推 4 字的「学期统计」。
     */
    @Test
    fun cjkAdvanceIsLinearInCharacterCountOnDevice() {
        assertEquals(36.0, TodayLabelPx.toDouble() / 2, 0.001)
        assertEquals(36.0, WeekLabelPx.toDouble() / 3, 0.001)
        assertEquals(36.0, CampusLabelPx.toDouble() / 4, 0.001)
    }

    /**
     * 标称是**下限**这条在宽度上同样成立：labelMedium 标称 12sp = 31.5px/枚，
     * 装机实量 31.0px/枚（差 1.6%）。按"字数 × 字号"估「学期统计」会得 126px，
     * 实测是 124px —— 差 2px 就是这笔账要下设备量的理由（口径同 T61b 的行高）。
     */
    @Test
    fun measuredCjkAdvanceDeviatesFromNominalFontSize() {
        val nominalAdvance = 12.0 * Density // 31.5px
        val measuredAdvance = CourseModeTextPx / 2.0 // 31.0px
        assertEquals(31.0, measuredAdvance, 0.001)
        assertTrue(
            "标称与实测应当确有差值（这一档一旦相等，说明锚点被改成了估出来的数）：$measuredAdvance vs $nominalAdvance",
            measuredAdvance < nominalAdvance,
        )
        assertEquals(1.6, (nominalAdvance - measuredAdvance) / nominalAdvance * 100, 0.05)
    }

    /** 两档文案的实宽与胶囊实占（长档 = 短档 × 2 + 内衬，逐格对上 KDoc 那张表）。 */
    @Test
    fun labelAndPillWidthsFollowTheMeasuredAnchor() {
        assertEquals(124.0, longLabelWidthPx, 0.001)
        assertEquals(62.0, shortLabelWidthPx, 0.001)
        assertEquals(166.0, longPillWidthPx, 0.001)
        assertEquals(104.0, shortPillWidthPx, 0.001)
        assertEquals(47.238, longLabelWidthPx / Density, 0.001)
        assertEquals(23.619, shortLabelWidthPx / Density, 0.001)
        assertEquals(63.238, longPillWidthPx / Density, 0.001)
    }

    /**
     * 第一行放得下长档，**两个页签都放得下**（产品判据：不许逼用户先切到周课表才有入口）。
     * 取宽的那一档（周课表页签，左列实占 216px）算预算，余量还够一截 spaceL。
     */
    @Test
    fun firstTopBarRowFitsTheLongLabelOnBothTabs() {
        assertEquals(429.0, weekTabRowOneBudgetPx, 0.001)
        assertEquals(477.0, dayTabRowOneBudgetPx, 0.001)
        assertTrue("周课表页签放不下长档：$weekTabRowOneBudgetPx < $longPillWidthPx", weekTabRowOneBudgetPx >= longPillWidthPx)
        assertTrue("今日页签放不下长档：$dayTabRowOneBudgetPx < $longPillWidthPx", dayTabRowOneBudgetPx >= longPillWidthPx)
        // 余量（px 与 dp 各钉一次）：263px = 100.190dp，够 spaceL×6 的呼吸
        assertEquals(263.0, weekTabRowOneBudgetPx - longPillWidthPx, 0.001)
        assertEquals(100.190, (weekTabRowOneBudgetPx - longPillWidthPx) / Density, 0.001)
        assertEquals(163.429, weekTabRowOneBudgetPx / Density, 0.001)
    }

    /**
     * 第二档（第二行尾部）实测**没有预算**：周次簇按父级整份宽度铺开，
     * `campusSlot` 拿到的就是 0，校区按钮整块 233px 在屏外（像素已核）。
     * 这一格钉的是"退档不可用"这个事实，不是要修它。
     */
    @Test
    fun secondTopBarRowHasNoRoomLeftForAFifthElement() {
        assertEquals(1038.0, RowTwoContentPx.toDouble(), 0.001)
        // 簇吃掉整份 ⇒ 行尾实占 = 行宽，剩余 0
        assertEquals(0.0, RowTwoContentPx - WeekNavClusterPx.toDouble(), 0.001)
        assertTrue(
            "第二行剩余 0dp，连最窄的短档（104px）也放不下：这就是不退档的理由",
            RowTwoContentPx - WeekNavClusterPx < shortPillWidthPx,
        )
        assertEquals(88.762, CampusChipPx.toDouble() / Density, 0.001)
    }

    /** 分段控件实占的分解核对：两段 + 段间 2dp 内衬 + GlassSurface contentPadding 4dp。 */
    @Test
    fun segmentedControlWidthDecomposesIntoItsParts() {
        val weekSegment = WeekLabelPx + 2 * (2f + 12f) * Density // 内衬 2dp + 12dp，取到 64dp 下限之上
        val todaySegment = 64f * Density // 「今日」28+24dp 不到 64dp 下限 ⇒ 被 defaultMinSize 托住
        val container = 2 * 4f * Density // GlassSurface(contentPadding = 4.dp)
        val total = weekSegment + todaySegment + container
        assertEquals(370.5, total, 0.6)
        // 与实测 372px 差 1.5px：那 1.5px 是每枚语义节点各自 ceil 到整数像素吃掉的
        // （「周课表」段语义实测 172 而算出来 171），不是漏了某一块内衬
        assertEquals(372.0, total, 2.0)
    }

    // ---- 装机实测常量（出处见类 KDoc 那张表；全部 px，density 2.625）----------------
    private companion object {
        const val Density = 2.625

        /** 「课次」胶囊内宽：labelMedium 两枚 CJK（周课表页签第二行，像素扫描 x=43..102 墨色 + 两侧衬底） */
        const val CourseModeTextPx = 62

        /** 「今日」labelLarge 两枚 CJK（uiautomator 语义节点 w=72） */
        const val TodayLabelPx = 72

        /** 「周课表」labelLarge 三枚 CJK（uiautomator w=108） */
        const val WeekLabelPx = 108

        /** 「校区切换」labelLarge 四枚 CJK（uiautomator w=144） */
        const val CampusLabelPx = 144

        /** 第一行左列实占，宽的那一档：「9月22日 星期二」labelMedium（像素扫描 x=43..257 → 216） */
        const val LeadingColumnWeekTabPx = 216

        /** 第一行左列实占，窄的那一档：「今日课表」titleMedium（uiautomator w=168） */
        const val LeadingColumnDayTabPx = 168

        /** GlassSegmentedControl(listOf("周课表","今日")) 实占（左列右缘 x=687 → 行内容右缘 x=1059） */
        const val SegmentedControlPx = 372

        /** 第一行内容宽：1080 − spaceL(16dp=42px) − spaceS(8dp=21px) */
        const val RowOneContentPx = 1017

        /** 第二行内容宽：1080 − spaceS ×2 */
        const val RowTwoContentPx = 1038

        /** 第二行周次簇实测铺满整行（AnimatedVisibility 里那枚 weight(1f) 吃满 ⇒ 行尾剩 0） */
        const val WeekNavClusterPx = 1038

        /** 校区按钮实占（今日页签 x=21..254） */
        const val CampusChipPx = 233

        /** 胶囊左右内衬合计：DesignTokens.spaceS × 2 = 8dp × 2 = 42px */
        const val PillPaddingPx = 42

        val longLabelWidthPx get() = CourseModeTextPx * 2.0 // 4 枚 = 2 枚 × 2
        val shortLabelWidthPx get() = CourseModeTextPx * 1.0
        val longPillWidthPx get() = longLabelWidthPx + PillPaddingPx
        val shortPillWidthPx get() = shortLabelWidthPx + PillPaddingPx
        val weekTabRowOneBudgetPx get() = RowOneContentPx - SegmentedControlPx - LeadingColumnWeekTabPx.toDouble()
        val dayTabRowOneBudgetPx get() = RowOneContentPx - SegmentedControlPx - LeadingColumnDayTabPx.toDouble()
    }
}
