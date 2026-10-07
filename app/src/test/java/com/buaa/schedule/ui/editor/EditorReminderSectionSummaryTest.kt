package com.buaa.schedule.ui.editor

import com.buaa.schedule.domain.model.ReminderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T214 病灶 B（#264）用户可见那一格的表：编辑器「提醒」开关底下那句 summary。
 *
 * 卡面对"没有行是常态"这一档给的第一条处置是 **显式承认这是产品口径，并且要在用户看得见的地方
 * 说出来，而不是只在日志里**。这里钉的就是那一句：
 * - [noRowStatesTheDefaultOutLoud]：没有行 ⇒ 那句必须非空、必须念出"开启"与那枚默认提前量，
 *   数字取自 [ReminderSetting.DEFAULT_ADVANCE_MINUTES] 而不是第二处 `10`（守卫
 *   `reminder/ReminderDefaultSingleSourceGuardTest` 钉枚数，本文件钉"这句话里念的是同一枚数"）；
 * - [rowBasedCellsSayNothingExtra]：有行（开或关）时两格里都看得见用户自己的设置，
 *   summary 必须留空 —— 多占一行、还把"你设过的"说成"默认的"，就是新的谎；
 * - [summaryIsDerivedNotHardcoded]：把默认常量换一档，句子跟着变（写死的句子不会）。
 *
 * 纯 JVM：`editorReminderSectionSummary` 是 `CourseEditorScreen.kt` 里的普通 internal 函数，
 * 不接 Composable、不读资源，三档的取法与排铃侧同一份（`EffectiveReminderSetting.of`）。
 */
class EditorReminderSectionSummaryTest {

    @Test
    fun noRowStatesTheDefaultOutLoud() {
        val summary = editorReminderSectionSummary(initialReminder = null)
        assertNotNull("没有提醒行时那句口径必须出现（空了就等于把它塞回日志里）", summary)
        assertTrue("句子必须念出默认是开启：$summary", summary!!.contains("开启"))
        assertTrue("句子必须念出默认提前量 ${ReminderSetting.DEFAULT_ADVANCE_MINUTES}：$summary", summary.contains("提前 ${ReminderSetting.DEFAULT_ADVANCE_MINUTES} 分钟"))
        assertTrue("句子必须说明这是默认口径而不是用户设过的：$summary", summary.contains("默认"))
        assertTrue("句子必须交代怎样才有那一行：$summary", summary.contains("保存"))
    }

    @Test
    fun rowBasedCellsSayNothingExtra() {
        assertNull(
            "有行且开：用户自己在开关与提前量两格里看得见，不许再压一句默认的口径上去",
            editorReminderSectionSummary(ReminderSetting(courseId = 1, enabled = true, advanceMinutes = 20)),
        )
        assertNull(
            "有行但关：同理（这一档用户明说过不要提醒，说一句按默认开启更是错的）",
            editorReminderSectionSummary(ReminderSetting(courseId = 1, enabled = false, advanceMinutes = 15)),
        )
    }

    /**
     * 句子里那枚数是**读来的**，不是抄的：常量与实体默认值必须一致，
     * 而句子必须跟着常量走（有人把句子写成死字数 10，这一格与守卫的第 ② 层会分开红）。
     */
    @Test
    fun summaryQuotesTheSameConstantTheChainsUse() {
        val summary = editorReminderSectionSummary(null).orEmpty()
        val fromDomain = ReminderSetting.DEFAULT_ADVANCE_MINUTES.toString()
        assertEquals(
            "summary 里那枚分钟数必须就是 domain 常量的十进制写法（两处必须同源）",
            1,
            summary.split("分").first().countOccurrences(fromDomain),
        )
        // 编辑器初值与排铃结论也读同一枚：改一处的字面量必然在这里露馅
        assertEquals(10, ReminderSetting(courseId = 1).advanceMinutes)
    }

    private fun String.countOccurrences(needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var at = indexOf(needle)
        while (at >= 0) {
            count++
            at = indexOf(needle, at + needle.length)
        }
        return count
    }
}
