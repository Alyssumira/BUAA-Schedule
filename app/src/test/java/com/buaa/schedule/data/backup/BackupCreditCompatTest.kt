package com.buaa.schedule.data.backup

import com.buaa.schedule.data.share.ScheduleShareCodec
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.schedule.CourseConstraints
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 学分在备份 / 分享口令这条链上的往返。
 *
 * 关键不是"新字段能不能存进去"，而是**老备份还能不能恢复**：
 * 用户手机上的备份文件是升级前导出的，里面没有 `credit` 键，
 * 解析必须安静地给出 null，而不是报"备份无效"——那是真实升级路径上最容易
 * 一脚踩空的地方（`allowBackup` 打开着，换机也会带旧库/旧备份过来）。
 */
class BackupCreditCompatTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun courseJson(credit: String) = """
        {
          "version": 2,
          "courses": [{
            "name": "高等数学", "dayOfWeek": 1,
            "periods": [1, 2], "weeks": [1, 2, 3],
            "semesterCode": "2026-2027-1", "sourceGroupKey": "G1",
            "credit": $credit
          }]
        }
    """.trimIndent()

    @Test
    fun creditRoundTripsThroughBackupJson() {
        val course = Course(
            name = "高等数学",
            dayOfWeek = 1,
            periods = listOf(1, 2),
            weeks = listOf(1, 2, 3),
            sourceGroupKey = "G1",
            semesterCode = "2026-2027-1",
            credit = 3.5,
        )
        val backup = BackupData(courses = listOf(course.toBackup()))

        val decoded = json.decodeFromString<BackupData>(json.encodeToString(backup))

        assertEquals(backup, decoded)
        assertEquals(3.5, decoded.courses.single().credit!!, 0.0)
        assertEquals(listOf(course), decoded.courses.map { it.toDomain() })
    }

    @Test
    fun backupWithoutCreditKeyStillRestores() {
        // 升级前导出的备份：完全没有 credit 这一行
        val legacy = """
            {
              "version": 2,
              "courses": [{
                "name": "高等数学", "dayOfWeek": 1,
                "periods": [1, 2], "weeks": [1, 2, 3],
                "semesterCode": "2026-2027-1"
              }]
            }
        """.trimIndent()

        val data = json.decodeFromString<BackupData>(legacy)

        assertEquals(1, data.courses.size)
        assertNull(data.courses.single().toDomain().credit)
        // 缺字段不改变格式版本：老应用也不该被这些文件挡住
        assertEquals(2, data.version)
    }

    @Test
    fun v1BackupWithLegacySectionsStillRestoresWithoutCredit() {
        // v1 连 periods 都没有，只有 startSection/endSection
        val v1 = """
            {
              "version": 1,
              "courses": [{
                "name": "高等数学", "teacher": "张三", "location": "J3-101",
                "dayOfWeek": 1, "startSection": 1, "endSection": 2,
                "weeks": [1, 2, 3]
              }]
            }
        """.trimIndent()

        val course = json.decodeFromString<BackupData>(v1).courses.single().toDomain()

        assertEquals(listOf(1, 2), course.periods)
        assertNull(course.credit)
    }

    @Test
    fun futureUnknownKeysAreIgnoredNotRejected() {
        // 新版本往备份里再加字段时，老应用必须还能读（ignoreUnknownKeys 是这条契约的载体）
        val fromFuture = courseJson("3.5").replace("\"version\": 2", "\"version\": 9, \"nextField\": 1")

        val data = json.decodeFromString<BackupData>(fromFuture)

        assertEquals(3.5, data.courses.single().credit!!, 0.0)
    }

    @Test
    fun outOfRangeCreditFromBackupIsDroppedBeforeWrite() {
        // 备份是用户可控输入：越界学分不能在统计页把总学分污染成天文数字
        val insane = json.decodeFromString<BackupData>(courseJson("1e308")).courses.single().toDomain()
        val negative = json.decodeFromString<BackupData>(courseJson("-3")).courses.single().toDomain()

        assertNull(CourseConstraints.normalize(insane)?.credit)
        assertNull(CourseConstraints.normalize(negative)?.credit)
        assertEquals(
            "合法值要原样穿过 normalize",
            3.5,
            requireNotNull(CourseConstraints.normalize(insane.copy(credit = 3.5))).credit!!,
            0.0,
        )
    }

    @Test
    fun shareCodeCarriesCreditBothWays() {
        val course = Course(
            name = "离散数学",
            dayOfWeek = 2,
            periods = listOf(3, 4),
            weeks = listOf(1, 3, 5),
            semesterCode = "2026-2027-1",
            credit = 4.0,
        )
        val backup = BackupData(courses = listOf(course.toBackup()))

        val decoded = ScheduleShareCodec.decode(ScheduleShareCodec.encode(backup))

        val shared = requireNotNull(decoded).courses.single().toDomain()
        // 整条课程都要等价：只核对 credit 会漏掉"口令丢字段"这类问题
        assertEquals(course, shared)
        assertEquals(4.0, shared.credit!!, 0.0)
    }
}
