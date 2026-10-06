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
 * 两支都逐字钉，两侧各一格：
 * - 朝宽（把右括号摘掉）⇒ [每一支都闭合] 与逐字那两格同时红；
 * - 朝严（把"另有 N 门没有节次"那一支无条件端出来）⇒ 零节次缺失那一格的逐字断言红。
 */
class WakeUpExportCopyTest {

    private val open = '（'
    private val close = '）'

    private class Cell(
        val label: String,
        val total: Int,
        val unplaceable: Int,
        val expected: String,
        val expectMentionsUnplaceable: Boolean,
    )

    private val cells = listOf(
        Cell(
            label = "一门都没有缺节次",
            total = 18,
            unplaceable = 0,
            expected = "WakeUp JSON 已导出（18 门课）",
            expectMentionsUnplaceable = false,
        ),
        Cell(
            label = "有一门没有节次",
            total = 5,
            unplaceable = 1,
            expected = "WakeUp JSON 已导出（4 门课；另有 1 门没有节次、无法定位，未写入）",
            expectMentionsUnplaceable = true,
        ),
        Cell(
            label = "有三门没有节次",
            total = 9,
            unplaceable = 3,
            expected = "WakeUp JSON 已导出（6 门课；另有 3 门没有节次、无法定位，未写入）",
            expectMentionsUnplaceable = true,
        ),
        Cell(
            label = "全部都没有节次",
            total = 2,
            unplaceable = 2,
            expected = "WakeUp JSON 已导出（0 门课；另有 2 门没有节次、无法定位，未写入）",
            expectMentionsUnplaceable = true,
        ),
    )

    @Test
    fun `两支的措辞逐字钉住`() {
        for (cell in cells) {
            assertEquals("「${cell.label}」：total=${cell.total}、unplaceable=${cell.unplaceable}", cell.expected, wakeUpExportMessage(cell.total, cell.unplaceable))
        }
    }

    @Test
    fun `两支的括号都必须闭合`() {
        for (cell in cells) {
            val text = wakeUpExportMessage(cell.total, cell.unplaceable)
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
            val text = wakeUpExportMessage(cell.total, cell.unplaceable)
            assertEquals(
                "「${cell.label}」：那一支的「没有节次」出场与否必须跟着 unplaceable 走",
                cell.expectMentionsUnplaceable,
                text.contains("没有节次"),
            )
            if (cell.unplaceable == 0) {
                assertFalse("零缺失那句不许出现『另有』：$text", text.contains("另有"))
            } else {
                assertTrue("有缺失那句要把门数说死：$text", text.contains("另有 ${cell.unplaceable} 门"))
            }
        }
    }

    @Test
    fun `报的门数是写出去的那几门不是课程总数`() {
        // T27：没有节次的课整条不进导出件 ⇒ 括号里那个数只能是"写出去了几门"
        val text = wakeUpExportMessage(totalCourses = 7, unplaceableCourses = 2)
        assertTrue("应报 5 门（实际那句：$text）", text.startsWith("WakeUp JSON 已导出（5 门课"))
        assertFalse("不许把 7 门报成写出去了 7 门：$text", text.contains("（7 门课"))
    }
}
