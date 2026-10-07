package com.buaa.schedule.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T181② 表驱动单测：WakeUp JSON 导出成功那一句的括号与条数（纯 JVM，零 android、不装机）。
 *
 * 措辞本体在 [wakeUpExportMessage]。病是 `else` 那一支**开了「（」没关「）」** ——
 * 那一句原本是两段字面量拼起来的，前一段开括号、后一段既没关也没人补，
 * 而课表里只要有一门**没有节次**的课，这一支就是可达的（可达性由
 * [com.buaa.schedule.ui.EmptyPeriodPlacementTest] 证，本卡零 adb 不再复现一遍）。
 * 同一函数的另一支（一支没有没有节次的课）是闭合的 ⇒ 这不是口径之争，是漏了一个字。
 *
 * **T203 补了第二档**：导出件丢课其实有两档（没有节次 = T27、没有周次 = #237），而这一句只念前
 * 那一档 ⇒ 空周次的课整条不进导出件之后，"已导出 N 门课"仍是多的。现在四支：
 * 无缺失 / 只缺节次 / 只缺周次 / 两档都有，每一支的数字只跟着自己那一档出场。
 *
 * 四支都逐字钉，两侧各一格：
 * - 朝宽（把右括号摘掉）⇒ [每一支都闭合] 与逐字那两格同时红；
 * - 朝严（把"另有 N 门没有节次"那一支无条件端出来）⇒ 零节次缺失那一格的逐字断言红。
 */
class WakeUpExportCopyTest {

    private val open = '（'
    private val close = '）'

    private class Cell(
        val label: String,
        val total: Int,
        val noPeriod: Int,
        val noWeek: Int,
        val expected: String,
        val expectMentionsNoPeriod: Boolean,
        val expectMentionsNoWeek: Boolean,
    )

    private val cells = listOf(
        Cell(
            label = "一门都没有缺",
            total = 18,
            noPeriod = 0,
            noWeek = 0,
            expected = "WakeUp JSON 已导出（18 门课）",
            expectMentionsNoPeriod = false,
            expectMentionsNoWeek = false,
        ),
        Cell(
            label = "有一门没有节次",
            total = 5,
            noPeriod = 1,
            noWeek = 0,
            expected = "WakeUp JSON 已导出（4 门课；另有 1 门没有节次、无法定位，未写入）",
            expectMentionsNoPeriod = true,
            expectMentionsNoWeek = false,
        ),
        Cell(
            label = "有三门没有节次",
            total = 9,
            noPeriod = 3,
            noWeek = 0,
            expected = "WakeUp JSON 已导出（6 门课；另有 3 门没有节次、无法定位，未写入）",
            expectMentionsNoPeriod = true,
            expectMentionsNoWeek = false,
        ),
        Cell(
            label = "全部都没有节次",
            total = 2,
            noPeriod = 2,
            noWeek = 0,
            expected = "WakeUp JSON 已导出（0 门课；另有 2 门没有节次、无法定位，未写入）",
            expectMentionsNoPeriod = true,
            expectMentionsNoWeek = false,
        ),
        // —— T203 补的第二档：没有周次那几门（此前它们被导出器造出"第 1-1 周"，如今整条不进导出件）——
        Cell(
            label = "有一门没有周次",
            total = 5,
            noPeriod = 0,
            noWeek = 1,
            expected = "WakeUp JSON 已导出（4 门课；另有 1 门没有周次、无法定位，未写入）",
            expectMentionsNoPeriod = false,
            expectMentionsNoWeek = true,
        ),
        Cell(
            label = "有两门没有周次",
            total = 7,
            noPeriod = 0,
            noWeek = 2,
            expected = "WakeUp JSON 已导出（5 门课；另有 2 门没有周次、无法定位，未写入）",
            expectMentionsNoPeriod = false,
            expectMentionsNoWeek = true,
        ),
        Cell(
            label = "两档各缺一门（一门只算一次，两句各念各的数）",
            total = 6,
            noPeriod = 1,
            noWeek = 2,
            expected = "WakeUp JSON 已导出（3 门课；另有 1 门没有节次、2 门没有周次，都无法定位，未写入）",
            expectMentionsNoPeriod = true,
            expectMentionsNoWeek = true,
        ),
        Cell(
            label = "两档全缺（写出去 0 门）",
            total = 3,
            noPeriod = 1,
            noWeek = 2,
            expected = "WakeUp JSON 已导出（0 门课；另有 1 门没有节次、2 门没有周次，都无法定位，未写入）",
            expectMentionsNoPeriod = true,
            expectMentionsNoWeek = true,
        ),
    )

    @Test
    fun `四支的措辞逐字钉住`() {
        for (cell in cells) {
            assertEquals(
                "「${cell.label}」：total=${cell.total}、noPeriod=${cell.noPeriod}、noWeek=${cell.noWeek}",
                cell.expected,
                wakeUpExportMessage(cell.total, cell.noPeriod, cell.noWeek),
            )
        }
    }

    @Test
    fun `四支的括号都必须闭合`() {
        for (cell in cells) {
            val text = wakeUpExportMessage(cell.total, cell.noPeriod, cell.noWeek)
            val opens = text.count { it == open }
            val closes = text.count { it == close }
            assertEquals(
                "「${cell.label}」那句的「（」「）」枚数必须相等（一句Snackbar 不许念出括号不闭合的破句）。" +
                    "期望 1 与 1，实际 $opens 与 $closes，原文：$text",
                1 to 1,
                opens to closes,
            )
        }
    }

    @Test
    fun `没有节次那一档不许端出未写入那半句`() {
        for (cell in cells) {
            val text = wakeUpExportMessage(cell.total, cell.noPeriod, cell.noWeek)
            assertEquals(
                "「${cell.label}」：『没有节次』那半句出场与否必须跟着 noPeriod 走",
                cell.expectMentionsNoPeriod,
                text.contains("没有节次"),
            )
            assertEquals(
                "「${cell.label}」：『没有周次』那半句出场与否必须跟着 noWeek 走（T203 那半场）",
                cell.expectMentionsNoWeek,
                text.contains("没有周次"),
            )
            if (cell.noPeriod == 0 && cell.noWeek == 0) {
                assertFalse("零缺失那句不许出现『另有』：$text", text.contains("另有"))
            } else {
                if (cell.noPeriod > 0) {
                    assertTrue("没节次那档要把门数说死：$text", text.contains("另有 ${cell.noPeriod} 门没有节次"))
                }
                if (cell.noWeek > 0) {
                    assertTrue("没周次那档要把门数说死：$text", text.contains("${cell.noWeek} 门没有周次"))
                }
            }
            // 一句 Snackbar 至多一个「另有」：两档都有缺失时也是同一句里并列，不是第二本册子
            val expectedLingYou = if (cell.noPeriod == 0 && cell.noWeek == 0) 0 else 1
            assertEquals(
                "「${cell.label}」那句里『另』的枚数必须与缺失档数同框（至多一枚『另有』）。实到那句：$text",
                expectedLingYou,
                text.count { it == '另' },
            )
        }
    }

    @Test
    fun `报的门数是写出去的那几门不是课程总数`() {
        // T27：没有节次的课整条不进导出件 ⇒ 括号里那个数只能是"写出去了几门"
        val text = wakeUpExportMessage(totalCourses = 7, noPeriodCourses = 2, noWeekCourses = 0)
        assertTrue("应报 5 门（实际那句：$text）", text.startsWith("WakeUp JSON 已导出（5 门课"))
        assertFalse("不许把 7 门报成写出去了 7 门：$text", text.contains("（7 门课"))
        // T203：两档各减一次（两档互斥，所以减两次不会把同一门减两遍）
        val both = wakeUpExportMessage(totalCourses = 7, noPeriodCourses = 2, noWeekCourses = 3)
        assertTrue("两档都缺时要报 2 门（实际那句：$both）", both.startsWith("WakeUp JSON 已导出（2 门课"))
    }
}
