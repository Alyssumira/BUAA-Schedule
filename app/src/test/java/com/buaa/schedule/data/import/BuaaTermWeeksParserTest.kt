package com.buaa.schedule.data.import

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BuaaTermWeeksParserTest {

    @Test
    fun parseRealTermWeeks() {
        val weeks = (1..19).map { week ->
            BuaaTermWeekDto(
                term = "2026-2027-1",
                currentWeek = week == 1,
                startDate = if (week == 1) "2026-09-07 00:00:00" else "2026-09-14 00:00:00",
                endDate = if (week == 1) "2026-09-13 00:00:00" else "2026-09-20 00:00:00",
                serialNumber = week,
                name = "第${week}周",
            )
        }

        val semester = BuaaScheduleParser.parseTermWeeks(weeks)

        assertNotNull(semester)
        assertEquals("2026-2027-1", semester!!.termCode)
        assertEquals("2026-09-07", semester.startDate)
        assertEquals(19, semester.totalWeeks)
    }
}
