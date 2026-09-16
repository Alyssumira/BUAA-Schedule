package com.buaa.schedule.data.share

import com.buaa.schedule.data.backup.BackupCourse
import com.buaa.schedule.data.backup.BackupData
import com.buaa.schedule.data.backup.BackupSemester
import com.buaa.schedule.data.backup.BackupTimeSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleShareCodecTest {

    private fun sampleBackup() = BackupData(
        semester = BackupSemester(
            termCode = "2026-2027-1",
            termName = "2026-2027-1",
            startDate = "2026-09-07",
            totalWeeks = 19,
        ),
        timeSlots = listOf(BackupTimeSlot(1, "08:00", "08:45")),
        courses = listOf(
            BackupCourse(
                name = "高等数学",
                teacher = "张三",
                location = "J3-101",
                dayOfWeek = 1,
                periods = listOf(1, 2),
                weeks = (1..16).toList(),
                customColorArgb = 0xFF5B8DEF,
            ),
            BackupCourse(
                name = "算法",
                dayOfWeek = 3,
                periods = listOf(1, 2, 9, 10),
                weeks = listOf(1, 3, 5),
            ),
        ),
    )

    @Test
    fun roundTrip() {
        val code = ScheduleShareCodec.encode(sampleBackup())
        assertTrue(code.startsWith("BUAASCH1:"))

        val decoded = ScheduleShareCodec.decode(code)
        assertNotNull(decoded)
        assertEquals("2026-2027-1", decoded!!.semester?.termCode)
        assertEquals(2, decoded.courses.size)
        assertEquals(listOf(1, 2, 9, 10), decoded.courses[1].periods)
        assertEquals(0xFF5B8DEF, decoded.courses[0].customColorArgb)
        assertEquals(1, decoded.timeSlots.size)
    }

    @Test
    fun decodeIsWhitespaceTolerant() {
        val code = ScheduleShareCodec.encode(sampleBackup())
        assertNotNull(ScheduleShareCodec.decode("\n  $code  \n"))
    }

    @Test
    fun decodeRejectsNonTokenInput() {
        assertNull(ScheduleShareCodec.decode("高等数学,张三,J3-101,1,1-2,1-16"))
        assertNull(ScheduleShareCodec.decode(""))
    }

    @Test
    fun decodeRejectsCorruptPayload() {
        assertNull(ScheduleShareCodec.decode("BUAASCH1:not-base64!!!"))
        assertNull(ScheduleShareCodec.decode("BUAASCH1:AAAA"))
    }

    @Test
    fun decodeRejectsOversizePayload() {
        val oversize = sampleBackup().let { base ->
            base.copy(
                courses = (1..(com.buaa.schedule.domain.schedule.CourseConstraints.MAX_COURSE_COUNT + 1))
                    .map { i ->
                        BackupCourse(
                            name = "课程$i",
                            dayOfWeek = 1,
                            periods = listOf(1),
                            weeks = listOf(1),
                        )
                    }
            )
        }
        assertNull(ScheduleShareCodec.decode(ScheduleShareCodec.encode(oversize)))
    }

    @Test
    fun encodedIsCompact() {
        // 19 周完整课表压进口令后不应过长（聊天工具可传输）
        val many = sampleBackup().let { base ->
            base.copy(
                courses = (1..20).map { i ->
                    BackupCourse(
                        name = "课程$i",
                        teacher = "老师$i",
                        location = "J3-10$i",
                        dayOfWeek = i % 7 + 1,
                        periods = listOf(i % 12 + 1, i % 12 + 2),
                        weeks = (1..19).toList(),
                    )
                }
            )
        }
        val code = ScheduleShareCodec.encode(many)
        assertTrue("口令过长: ${code.length}", code.length < 2000)
    }
}
