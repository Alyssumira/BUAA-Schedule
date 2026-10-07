package com.buaa.schedule.ui.home

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.schedule.CourseConstraints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T197 的表驱动：没设学期时首页那一排周次控件的四枚判据内核（[shouldOfferWeekNavigation] /
 * [weekChoicesFor] / [shouldDrawSemesterProgress] / [weekCountsOf] + [notEveryWeekFlag]）。
 *
 * 病灶是**两枚硬写的 20**：`totalWeeks = (state.semester?.totalWeeks ?: 20)`（首页，递进工具条后
 * 同时喂跳周清单与学期进度线）与 `val totalWeeks = (semester?.totalWeeks ?: 20)`（周视图，下游是
 * 卡角那枚「不是每周都有」的旗与横滑 Pager）。没学期的那一格界面并不空转，而是**按一枚不存在的
 * 20 周学期运转**：清单真列 20 行、真能点出 browseWeek；进度线真画一条 96dp 的学期轨道；
 * 读屏文案那半句「，不是每周都有」拿假 20 当分母判。
 *
 * 表打的档位（卡面口径：有学期/无学期 × 周次信息有/无 × 页签命中/不命中），无学期那一档**钉两条结论**：
 * ① 清单为空（②进度线不画）与③旗不亮（分母取不到时读屏不许念那半句）。
 * 另三遍不冗余的尺：④有学期那一档与改前**逐格同值**（含夹取下界 1、上界 [CourseConstraints.MAX_TOTAL_WEEKS]，
 * 本卡不许动正常档）；⑤内核从不造出入参之外的周数（这是"零硬写 20"的实数版，比"grep 没匹配到"强）；
 * ⑥两枚结论同源（Pager 页数与分母出自同一枚 [weekCountsOf]，同一屏上"这学期多少周"只许一个答案）。
 *
 * 纯 JVM：页签序号、有没有学期、学期总周数、这批课的周次信息四样事实全由参数注入 ——
 * 没有时钟、没有 Robolectric、没有 android import（本仓收单硬口径）。
 */
class WeekNavigationAvailabilityTest {

    // ─────────────── ① 导航该不该给：有学期/无学期 × 页签命中/不命中 ───────────────

    private data class NavRow(
        val scene: String,
        val selectedTab: Int,
        val hasSemester: Boolean,
        val weekTab: Int,
        val expected: Boolean,
    )

    @Test
    fun `周次导航只在有学期且落在周课表页签时给`() {
        val table = listOf(
            NavRow("周课表页签 + 有学期 ⇒ 照给（改前与改后同值，正常档不许动）", 0, true, 0, true),
            NavRow(
                "周课表页签 + 没学期 ⇒ **不给**。本卡病灶：改前这一格只看 selectedTab == 0，" +
                    "于是假 20 周的清单真能点、进度线真能画",
                0, false, 0, false,
            ),
            NavRow("今日页签 + 有学期 ⇒ 不给（日视图按日期翻页，这是 T52/#113 那一族的既有口径）", 1, true, 0, false),
            NavRow("今日页签 + 没学期 ⇒ 不给", 1, false, 0, false),
            NavRow("序号是调用点的事实：换一枚 weekTab=2 也照命中（内核里一枚都不写死）", 2, true, 2, true),
            NavRow("换一枚 weekTab=2 而选中 0 ⇒ 不命中（「0 就是周课表」那种暗号不许留在内核里）", 0, true, 2, false),
        )
        for (row in table) {
            val actual = shouldOfferWeekNavigation(row.selectedTab, row.hasSemester, row.weekTab)
            assertEquals("${row.scene} —— 实到 $actual", row.expected, actual)
        }
    }

    @Test
    fun `没学期那一格不许放宽到任何页签`() {
        for (selected in listOf(0, 1, 2, 7)) {
            assertFalse(
                "selectedTab=$selected、没学期仍给周次导航：那 20 周是造出来的，" +
                    "清单/进度线/读屏旗都会跟着当真",
                shouldOfferWeekNavigation(selected, hasSemester = false, weekTab = selected),
            )
        }
    }

    // ─────────────── ② 跳周清单：无学期 ⇒ 空清单 ───────────────

    private data class ChoiceRow(
        val scene: String,
        val hasSemester: Boolean,
        val totalWeeks: Int?,
        val expected: List<Int>,
    )

    @Test
    fun `清单档位逐格对号 夹取上下界与改前逐字同`() {
        val table = listOf(
            ChoiceRow("有学期 16 周 ⇒ 1..16（与改前逐格同）", true, 16, (1..16).toList()),
            ChoiceRow(
                "**没学期 ⇒ 空清单**。改前这一格是 (1..20).toList()：清单真列 20 行、点下去 " +
                    "onBrowseWeekChange(week) 真把一个凭空造出的周号写进 browseWeek",
                false, 16, emptyList(),
            ),
            ChoiceRow("没学期且没有周数 ⇒ 空清单（两枚入参一起说实话）", false, null, emptyList()),
            ChoiceRow("hasSemester 为真而周数取不到 ⇒ 空清单：宁可少给一排，也不凭空排一排", true, null, emptyList()),
            ChoiceRow("脏数据 0 周 ⇒ 夹到下界 1（改前 coerceIn(1, MAX) 同值，下界不许漂）", true, 0, listOf(1)),
            ChoiceRow("脏数据负数 ⇒ 同样夹到下界 1", true, -5, listOf(1)),
            ChoiceRow(
                "脏数据 999 ⇒ 夹到上界 ${CourseConstraints.MAX_TOTAL_WEEKS}（" +
                    "「避免跳周列表物化超大列表」那笔账照旧）",
                true, 999, (1..CourseConstraints.MAX_TOTAL_WEEKS).toList(),
            ),
            ChoiceRow(
                "上界那一格边界：恰好 MAX_TOTAL_WEEKS ⇒ 全长清单（夹取是闭区间，别多削一格）",
                true, CourseConstraints.MAX_TOTAL_WEEKS, (1..CourseConstraints.MAX_TOTAL_WEEKS).toList(),
            ),
        )
        for (row in table) {
            val actual = weekChoicesFor(row.hasSemester, row.totalWeeks)
            assertEquals("${row.scene} —— 实到 $actual", row.expected, actual)
        }
    }

    @Test
    fun `没学期那一格清单必须为空而不是二十行`() {
        // 卡面点名要钉的一条结论，单独再钉一遍：无学期时清单枚数恰好 0
        assertEquals(
            "没学期时清单枚数不是 0 —— 那些周号是硬写的 20 变出来的，屏幕上一个都不该有",
            0,
            weekChoicesFor(hasSemester = false, totalWeeks = 20).size,
        )
        for (weeks in listOf<Int?>(1, 8, 16, 20, 30, 999, 0, null)) {
            assertEquals(
                "没学期、totalWeeks=$weeks 这一格清单不为空",
                emptyList<Int>(),
                weekChoicesFor(hasSemester = false, totalWeeks = weeks),
            )
        }
    }

    // ─────────────── ③ 学期进度线：没学期就不画那条轨道 ───────────────

    @Test
    fun `进度线只跟有学期`() {
        assertTrue(shouldDrawSemesterProgress(hasSemester = true))
        assertFalse(
            "没学期还画学期进度线：SemesterProgressLine 在 currentWeek=null 时填充是 0f，" +
                "但那条 96dp 的素色轨道照画 —— 它说的「这学期多少周」根本不存在",
            shouldDrawSemesterProgress(hasSemester = false),
        )
    }

    // ─────────────── ④ 分母：不是每周都有的旗吃什么数 ───────────────

    private data class CountsRow(
        val scene: String,
        val hasSemester: Boolean,
        val semesterTotalWeeks: Int?,
        val courseWeeks: List<List<Int>>,
        val expectedPagerPages: Int,
        val expectedBase: Int?,
    )

    private data class FlagRow(
        val scene: String,
        val weeksSize: Int,
        val base: Int?,
        val expected: Boolean,
    )

    @Test
    fun `两枚结论逐格对号 有学期与改前同值 无学期改吃真实最大周号`() {
        val table = listOf(
            CountsRow(
                "有学期 16 周、课只上到第 8 周 ⇒ 两枚都是 16（分母吃学期总周数，与改前逐字同）",
                true, 16, listOf((1..8).toList()), 16, 16,
            ),
            CountsRow(
                "有学期而脏数据 0 周 ⇒ 夹到下界 1（改前 coerceIn(1, MAX) 同值）",
                true, 0, listOf(listOf(1, 2)), 1, 1,
            ),
            CountsRow(
                "有学期而脏数据 999 周 ⇒ 夹到上界 $MAX_WEEKS_TEXT",
                true, 999, listOf((1..40).toList()), MAX_WEEKS, MAX_WEEKS,
            ),
            CountsRow(
                "**没学期 + 课上有周次** ⇒ 分母 = 这批课真实出现过的最大周号（16），Pager 也走同一枚数。" +
                    "改前这一格是硬写的 20：横滑能翻到第 17~20 页根本没有的周",
                false, null, listOf((1..8).toList(), (9..16).toList()), 16, 16,
            ),
            CountsRow(
                "没学期 + 周次信息越界（45 周）⇒ 照样夹到上界",
                false, null, listOf(listOf(45)), MAX_WEEKS, MAX_WEEKS,
            ),
            CountsRow(
                "**没学期 + 一枚周号都没有**（没有课程，或每门课 weeks 都空）⇒ 分母 null ⇒ 旗不亮；" +
                    "Pager 那一枚给 1（pageCount=0 会让调用点的 coerceIn(0, -1) 直接抛）",
                false, null, emptyList(), 1, null,
            ),
            CountsRow(
                "没学期 + 课程全带空周次 ⇒ 同一格：分母 null",
                false, null, listOf(emptyList(), emptyList()), 1, null,
            ),
            CountsRow(
                "两枚入参矛盾（有学期而周数取不到）⇒ 按「取不到」处理：旗不亮、单页 —— 宁可少说",
                true, null, listOf((1..8).toList()), 1, null,
            ),
        )
        for (row in table) {
            val courses = row.courseWeeks.mapIndexed { index, weeks -> course(weeks = weeks, id = index.toLong()) }
            val (pagerPages, base) = weekCountsOf(row.hasSemester, row.semesterTotalWeeks, courses)
            assertEquals("${row.scene} —— Pager 页数实到 $pagerPages", row.expectedPagerPages, pagerPages)
            if (row.expectedBase == null) {
                assertNull("${row.scene} —— 分母实到 $base（期望 null ⇒ 旗不亮）", base)
            } else {
                assertEquals("${row.scene} —— 分母实到 $base", row.expectedBase, base)
            }
        }
    }

    @Test
    fun `分母从不造出输入里没有的周数`() {
        // 「零硬写 20」的实数版：没学期时结果的每一格都必须来自这批课自己
        val courses = listOf(course(weeks = (1..6).toList(), id = 1L), course(weeks = listOf(3), id = 2L))
        val (pagerPages, base) = weekCountsOf(hasSemester = false, semesterTotalWeeks = null, courses = courses)
        assertEquals(6, pagerPages)
        assertEquals(6, base)
        assertFalse(
            "结果里冒出 20 说明还有兜底：这批课的周次信息只到第 6 周",
            listOf(pagerPages, base).contains(20),
        )
    }

    @Test
    fun `旗子只在与分母可比时才亮`() {
        val table = listOf(
            FlagRow("有学期 16 周、这门课只上 8 周 ⇒ 亮（与改前同式）", 8, 16, true),
            FlagRow("有学期 16 周、门门都上 ⇒ 不亮", 16, 16, false),
            FlagRow("周次超出学期（脏数据）⇒ 不亮，不改口说「不是每周都有」", 20, 16, false),
            FlagRow(
                "**分母取不到 ⇒ 一律不亮**：改前这半句念的是「课时数 < 假 20」，" +
                    "读屏替我们念出一句没有依据的话（本卡病灶 B）",
                3, null, false,
            ),
            FlagRow("分母取不到 + 零周次 ⇒ 也不亮（同一格换一门课）", 0, null, false),
            FlagRow("一学期的学期（1 周）+ 没周次的课 ⇒ 亮：这一格分母是真的", 0, 1, true),
        )
        for (row in table) {
            assertEquals("${row.scene} —— 实到 ${notEveryWeekFlag(row.weeksSize, row.base)}", row.expected, notEveryWeekFlag(row.weeksSize, row.base))
        }
    }

    @Test
    fun `没学期那一格两条结论一起钉`() {
        // 卡面点名的"清单为空"与"旗不亮"，在同一格里同时兑现
        val courses = listOf(course(weeks = emptyList(), id = 1L))
        val (_, base) = weekCountsOf(hasSemester = false, semesterTotalWeeks = null, courses = courses)
        assertEquals(0, weekChoicesFor(hasSemester = false, totalWeeks = base).size)
        assertFalse(notEveryWeekFlag(weeksSize = 0, notEveryWeekBase = base))
        assertFalse(shouldDrawSemesterProgress(hasSemester = false))
        assertFalse(shouldOfferWeekNavigation(selectedTab = 0, hasSemester = false, weekTab = 0))
        // 而"这批课里真有周次"那一格：旗照亮，但分母是课表自己的最大周号，不是 20
        val withWeeks = listOf(course(weeks = (1..12).toList(), id = 1L), course(weeks = listOf(1, 2), id = 2L))
        val real = weekCountsOf(hasSemester = false, semesterTotalWeeks = null, courses = withWeeks).second
        assertEquals(12, real)
        assertTrue(notEveryWeekFlag(weeksSize = 2, notEveryWeekBase = real))
        assertFalse(notEveryWeekFlag(weeksSize = 12, notEveryWeekBase = real))
    }

    // ---------------- helpers ----------------

    private val MAX_WEEKS = CourseConstraints.MAX_TOTAL_WEEKS
    private val MAX_WEEKS_TEXT = CourseConstraints.MAX_TOTAL_WEEKS.toString()

    private fun course(weeks: List<Int>, id: Long) = Course(
        id = id,
        name = "测试课 $id",
        dayOfWeek = 1,
        periods = listOf(1, 2),
        weeks = weeks,
    )
}
