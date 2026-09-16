package com.buaa.schedule.data.backup

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlotProfile
import com.buaa.schedule.domain.schedule.ScheduleExporters
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份格式契约。
 *
 * 存在的理由：此前 `SettingsScreen` 里「导出备份」按钮因为同名变量遮蔽，
 * 实际调用的是 WakeUp 导出器，产出的文件无法被「导入备份」恢复，
 * 而这条链路上一个测试都没有。这里把"备份必须是 BackupData、且与 WakeUp 格式互不兼容"
 * 固定下来，避免同类事故再发生。
 */
class BackupExportContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val semester = Semester(
        termCode = "2026-2027-1",
        termName = "2026-2027-1",
        startDate = "2026-09-07",
        totalWeeks = 19,
    )

    /** id 不在备份里，往返后以 0 表示"入库时由数据库分配" */
    private val course = Course(
        id = 0L,
        name = "高等数学",
        teacher = "张三",
        location = "J3-101",
        campus = "学院路",
        dayOfWeek = 1,
        periods = listOf(1, 2),
        weeks = listOf(1, 3, 5),
        colorIndex = 2,
        sourceGroupKey = "2026-2027-1|MATH|01",
        semesterCode = "2026-2027-1",
    )

    @Test
    fun backupRoundTripsThroughJson() {
        val backup = BackupData(
            semester = semester.toBackup(),
            timeSlots = TimeSlotProfile.DEFAULT.map { it.toBackup() },
            courses = listOf(course.toBackup()),
            reminders = listOf(BackupReminder(courseKey = "key-1", enabled = true, advanceMinutes = 15)),
        )

        val decoded = json.decodeFromString<BackupData>(json.encodeToString(backup))

        assertEquals(backup, decoded)
        // 领域往返：恢复侧拿到的是等价的 Semester / Course
        assertEquals(semester, decoded.semester?.toDomain())
        assertEquals(listOf(course), decoded.courses.map { it.toDomain() })
        assertEquals(TimeSlotProfile.DEFAULT.size, decoded.timeSlots.size)
    }

    @Test
    fun wakeUpExportIsNotAValidBackup() {
        val wakeUp = ScheduleExporters.toWakeUpJson(
            listOf(course),
            semester,
            TimeSlotProfile.DEFAULT,
        )

        // WakeUp 是另一套 schema（courses[].day/startNode/step/startWeek…），
        // 缺 BackupCourse 的必填字段 dayOfWeek / weeks → 不能当备份用
        val attempt = runCatching { json.decodeFromString<BackupData>(wakeUp) }
        assertTrue("WakeUp JSON 不应能被解析成 BackupData", attempt.isFailure)
    }

    @Test
    fun wakeUpExportStillProducesItsOwnFormat() {
        val wakeUp = ScheduleExporters.toWakeUpJson(
            listOf(course),
            semester,
            TimeSlotProfile.DEFAULT,
        )
        // 反向确认导出器本身没坏（两个功能应各自可用）
        assertTrue(wakeUp.contains("\"tableInfo\""))
        assertTrue(wakeUp.contains("\"courses\""))
        assertTrue(wakeUp.contains("\"startNode\":1"))
    }

    @Test
    fun explicitVersionSurvivesRoundTrip() {
        // 恢复侧靠 version 判断"备份来自更新版本的应用"（高于当前版本时拒绝恢复）。
        // 当前版本号等于默认值，序列化时会被省略，因此这里用 encodeDefaults 显式写出。
        val explicit = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val encoded = explicit.encodeToString(BackupData(version = 3))
        assertEquals(3, json.decodeFromString<BackupData>(encoded).version)
        // 缺省时回落为当前版本
        assertEquals(2, json.decodeFromString<BackupData>("{}").version)
    }
}
