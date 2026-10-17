package com.buaa.schedule.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 时间轴小时刻度文案的判据表（T420 内核）。
 *
 * 表驱动：每一行就是一个整点/文案对，边界成对钉 ——
 * `0` 与 `9` 是补零那一档（不补零就露成 "0:00"/"9:00"，刻度列的位数都不齐），
 * `10` 与 `23` 是不该被补过头的两档（`padStart(3)` 会画成 "010:00"）。
 *
 * 另有一枚 locale 档：设备把 FORMAT locale 换成阿拉伯数字系统时，这一列**必须**还是 ASCII，
 * 否则同屏就是两套数字字形（`reminder/ReminderNotifications.kt` 的 clockOf 与
 * `widget/TwoDayWidgetProvider.kt` 的 TWO_DAY_CLOCK 都是钉死 locale 的）。
 */
class TimelineTickFormatTest {

    @Test
    fun hourTickLabelTable() {
        val cases: List<Pair<Int, String>> = listOf(
            0 to "00:00",
            9 to "09:00",
            10 to "10:00",
            23 to "23:00",
        )
        for ((input, expected) in cases) {
            assertEquals("hourTickLabel($input)", expected, hourTickLabel(input))
        }
    }

    @Test
    fun hourTickLabelNeverLeavesAsciiDigits() {
        // 整列刻度都是十进制 ASCII：非 ASCII 数字（阿拉伯-印度 U+0660 档、波斯 U+06F0 档）
        // 一出现就是那套 locale 敏感的格式化器回来了
        for (hour in 0..23) {
            val label = hourTickLabel(hour)
            assertTrue(
                "$label 里有非 ASCII 字符：小时刻度必须钉在 U+0030-U+0039 与冒号上",
                label.all { it.code in 0x30..0x39 || it == ':' },
            )
        }
    }

    @Test
    fun hourTickLabelIgnoresDefaultFormatLocale() {
        val previous = Locale.getDefault(Locale.Category.FORMAT)
        try {
            for (tag in listOf("ar-EG", "fa-IR")) {
                Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag(tag))
                assertEquals("$tag 下 8 点仍是 ASCII 08:00", "08:00", hourTickLabel(8))
                assertEquals("$tag 下 0 点仍是 ASCII 00:00", "00:00", hourTickLabel(0))
            }
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, previous)
        }
    }
}
