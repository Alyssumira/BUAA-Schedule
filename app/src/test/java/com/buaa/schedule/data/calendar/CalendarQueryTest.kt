package com.buaa.schedule.data.calendar

import android.provider.CalendarContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日历列表查询的选择/投影契约（R5 §8-5）。
 *
 * KNOWN_ISSUES §2c 记过一次"日历列表检索不到"：投影只带了 ACCOUNT_NAME，
 * CalendarProvider 要求它与 ACCOUNT_TYPE 成对出现，抛出的
 * IllegalArgumentException 又被查询外层的 runCatching 吞掉。
 * 当时是修好了，但没有任何测试守着 —— 这几条就是那把锁。
 */
class CalendarQueryTest {

    private fun calendar(id: Long, accessLevel: Int) = CalendarSyncManager.CalendarInfo(
        id = id,
        displayName = "日历 $id",
        accountName = "user@example.com",
        accessLevel = accessLevel,
    )

    @Test
    fun accountColumnsComeAsAPair() {
        val columns = CalendarQuery.projection.toList()
        val hasName = columns.contains(CalendarContract.Calendars.ACCOUNT_NAME)
        val hasType = columns.contains(CalendarContract.Calendars.ACCOUNT_TYPE)
        // 只留一个 = provider 直接抛异常，且异常会被 runCatching 吞成"没有日历"
        assertEquals("ACCOUNT_NAME 与 ACCOUNT_TYPE 必须同时出现", hasName, hasType)
        assertTrue("日历选择器需要账户列", hasName && hasType)
    }

    @Test
    fun projectionCarriesEveryColumnTheCursorReads() {
        val columns = CalendarQuery.projection.toList()
        assertTrue(columns.contains(CalendarContract.Calendars._ID))
        assertTrue(columns.contains(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME))
        assertTrue(columns.contains(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL))
        // queryCalendarsOnce 按下标取列，重复的列会让下标与语义错位
        assertEquals(columns.size, columns.distinct().size)
        assertTrue("不允许出现空列名", columns.none { it.isBlank() })
    }

    @Test
    fun strictSelectionExcludesHiddenAndReadOnlyCalendars() {
        val selection = CalendarQuery.writableSelection
        assertTrue(
            "selection 必须排除隐藏日历",
            selection.contains(CalendarContract.Calendars.VISIBLE),
        )
        assertTrue(selection.contains("1"))
        assertTrue(
            "selection 必须要求可写权限",
            selection.contains(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
        )
        assertTrue(
            "阈值应为 CONTRIBUTOR 及以上",
            selection.contains(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
        )
    }

    @Test
    fun fallbackKeepsWritableAccessLevelsOnly() {
        val writable = listOf(
            CalendarContract.Calendars.CAL_ACCESS_ROOT,
            CalendarContract.Calendars.CAL_ACCESS_OWNER,
            CalendarContract.Calendars.CAL_ACCESS_EDITOR,
            CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
        )
        val readOnly = listOf(
            CalendarContract.Calendars.CAL_ACCESS_READ,
            CalendarContract.Calendars.CAL_ACCESS_FREEBUSY,
            CalendarContract.Calendars.CAL_ACCESS_NONE,
        )
        val all = (writable + readOnly).mapIndexed { index, level -> calendar(index + 1L, level) }

        assertEquals(
            "只保留 CONTRIBUTOR 及以上的日历",
            writable.indices.map { it + 1L }.toSet(),
            CalendarQuery.fallbackWritable(all).map { it.id }.toSet(),
        )
    }

    @Test
    fun strictResultWinsAndFallbackQueryIsSkipped() {
        val strict = listOf(calendar(1L, CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR))
        var fallbackRuns = 0
        val resolved = CalendarQuery.resolveWritable(strict) {
            fallbackRuns++
            listOf(calendar(2L, CalendarContract.Calendars.CAL_ACCESS_ROOT))
        }

        assertSame("严格 selection 已命中，不该再发第二次查询", strict, resolved)
        assertEquals(0, fallbackRuns)
    }

    @Test
    fun emptyStrictResultFallsBackToCodeSideFiltering() {
        // ROM 对带 AND 的 selection 返回空集：退化路径必须自己把只读日历挡掉
        val resolved = CalendarQuery.resolveWritable(emptyList()) {
            listOf(
                calendar(1L, CalendarContract.Calendars.CAL_ACCESS_OWNER),
                calendar(2L, CalendarContract.Calendars.CAL_ACCESS_READ),
            )
        }

        assertEquals(listOf(1L), resolved.map { it.id })
        assertFalse(
            "只读日历不能被选作同步目标",
            resolved.any {
                it.accessLevel < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
            },
        )
    }
}
