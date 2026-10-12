package com.buaa.schedule.ui.editor

import com.buaa.schedule.domain.model.ReminderSetting
import com.buaa.schedule.domain.schedule.CourseConstraints
import com.buaa.schedule.domain.schedule.WeekParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T321⓪：改前行为探针（台账 #499 = #379 升档）。
 *
 * 这一枚文件**只登记基点的真实行为**，不登记期望：三格判红（额外节次 / 学分 / 提前分钟）
 * 今天压根进不了 `editorCanSave`，于是"框子红了、按钮照样按得下去、落库的是归一化之后的
 * 另一个值"。每一格都同时念两句：
 * 1. 那一格按 `CourseEditorScreen` 现有的判红口径确实判红；
 * 2. 真调生产闸门 `editorCanSave(weeks, periods, saving)` —— 它照样返回 true。
 *
 * 三格的红**都不是**"这格没填"（三条判据都带 `isNotBlank()` 前件），所以把它们塞进闸门
 * 不会造出"按不下去却没人说为什么"的新静默；这是第⓪问裁甲档的盘面依据。
 *
 * ① 步落地（闸门吃进判红名单）之后这三格必然翻红，本探针当场作废并**随实现一并删掉**，
 * 期望值改由 `CourseEditorRedFieldGateTest` 手写的表驱动格子钉。
 */
class CourseEditorRedFieldSaveBypassProbeTest {

    /** 闸门那三格判据在正常档位都齐（周次解析得出、节次解析得出、没在写库）——只留判红字段缺席。 */
    private val weeks = listOf(1, 2, 3)
    private val periods = listOf(1, 2)

    @Test
    fun `改前探针 学分判红时闸门照样放行而存进去的是null`() {
        // 屏上写的是 "3,5"（逗号是北航手输学分最常见的错法，:218 的注释逐字点着它）
        val creditText = "3,5"
        val creditNumber = creditText.trim().toDoubleOrNull()
        assertTrue("学分格判红（CourseEditorScreen 的 creditInvalid 口径）", creditText.isNotBlank())
        assertNull(
            "填了坏值 ⇒ normalizeCredit 交回 null，即库里存的是「没有学分数据」，" +
                "而框子里写着「3,5」——改前这一格压根不在闸门里",
            CourseConstraints.normalizeCredit(creditNumber),
        )
        assertTrue(
            "改前读数：红着照样按得下去（editorCanSave 的形参里没有一枚是判红字段）",
            editorCanSave(weeks = weeks, periods = periods, saving = false),
        )
    }

    @Test
    fun `改前探针 提前分钟判红时闸门照样放行而存进去的是夹取后的另一个值`() {
        val advanceMinutes = "999"
        val parsed = advanceMinutes.trim().toIntOrNull()
        assertTrue(
            "提前分钟格判红（越界，CourseEditorScreen 的 advanceInvalid 口径）",
            advanceMinutes.isNotBlank() && parsed != null && parsed !in 0..CourseConstraints.MAX_ADVANCE_MINUTES,
        )
        val stored = CourseConstraints.normalizeAdvanceMinutes(parsed ?: ReminderSetting.DEFAULT_ADVANCE_MINUTES)
        assertEquals(
            "屏上 999 ⇒ 落库 " + CourseConstraints.MAX_ADVANCE_MINUTES + "（coerceIn 的另一个值）",
            CourseConstraints.MAX_ADVANCE_MINUTES,
            stored,
        )
        assertTrue(
            "改前读数：这一格的红同样进不了闸门",
            editorCanSave(weeks = weeks, periods = periods, saving = false),
        )
    }

    @Test
    fun `改前探针 额外节次判红时节次照样非空而那段被整段丢掉`() {
        val extraPeriods = "abc"
        val parsedExtra = WeekParser.parse(extraPeriods)
        assertTrue(
            "额外节次格判红：非空白且解析得出空表（CourseEditorScreen 的 extraPeriodsInvalid 口径）",
            extraPeriods.isNotBlank() && parsedExtra.isEmpty(),
        )
        // parsePeriods 只在「解析得出值」时才判越界：空表那一路 extra.any{} 恒 false ⇒ 只交回首段
        val periodsWithDroppedExtra = parsePeriods("1", "2", extraPeriods)
        assertEquals(
            "用户看到的额外节次在 parsePeriods 那一层被静默丢掉，交回的只剩 1-2",
            listOf(1, 2),
            periodsWithDroppedExtra,
        )
        assertTrue("解析结果非空 ⇒ 改前闸门按 periods.isNotEmpty() 放行", periodsWithDroppedExtra.isNotEmpty())
        assertTrue(
            "改前读数：这一格的红同样进不了闸门",
            editorCanSave(weeks = weeks, periods = periodsWithDroppedExtra, saving = false),
        )
    }
}
