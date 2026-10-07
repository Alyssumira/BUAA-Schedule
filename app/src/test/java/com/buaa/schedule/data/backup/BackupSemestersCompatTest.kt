package com.buaa.schedule.data.backup

import com.buaa.schedule.data.share.ScheduleShareCodec
import com.buaa.schedule.domain.model.Semester
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T204②：`BackupData.semesters` 这一枚可选字段在**序列化兼容面**上的自证。
 *
 * 关键不是"新字段能不能存进去"，而是那两条升级路都走得通：
 * - 用户手机上的备份文件是升级前导出的（只有 `semester` 那一枚）⇒ 解析必须安静地给出
 *   **空列表**并按单字段回落，而不是报"备份无效"（同 `BackupCreditCompatTest` 的口径）；
 * - 新备份被**老应用**读时，`semesters` 是它不认得的键 ⇒ `ignoreUnknownKeys` 忽略它，
 *   老应用照旧按 `semester` 恢复主学期（旧行为原样，不会变成"打不开"）。
 *   这一条靠把老那一版的 `BackupData` 形状**在这里复刻一枚只读类型**来证 ——
 *   它只用于测试，不参与任何生产代码，也不会被写进备份。
 *
 * `version` 因此**不 +1**（红线）：涨到 3 只换来一条更窄的升级路
 * （`data.version > BACKUP_FORMAT_VERSION` 那一支会让老应用直接拒绝恢复），
 * 而字段本身两边都认得。口径同 [BackupCourse.credit] 与 [BACKUP_FORMAT_VERSION] 的注释。
 *
 * 分享口令那条链走的是同一个 JSON（`data/share/ScheduleShareCodec`），所以一并钉：
 * 口令里新字段跟着到、老口令照旧回落。
 */
class BackupSemestersCompatTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** 升级前的 `BackupData` 形状（逐字复刻：没有 semesters 这一枚），只用于证明老应用读得回新备份 */
    @Serializable
    private data class LegacyBackupData(
        val version: Int = 2,
        val semester: BackupSemester? = null,
        val timeSlots: List<BackupTimeSlot> = emptyList(),
        val courses: List<BackupCourse> = emptyList(),
        val reminders: List<BackupReminder> = emptyList(),
    )

    private val legacyJson = """
        {
          "version": 2,
          "semester": {"termCode": "2026-2027-1", "termName": "2026-2027学年第一学期",
                       "startDate": "2026-09-07", "totalWeeks": 19},
          "courses": [
            {"name": "高等数学", "dayOfWeek": 1, "periods": [1, 2], "weeks": [1, 2, 3],
             "semesterCode": "2026-2027-1"},
            {"name": "中国近代史", "dayOfWeek": 3, "periods": [5, 6], "weeks": [1, 3, 5, 7],
             "semesterCode": "2025-2026-2"}
          ]
        }
    """.trimIndent()

    // ─────────────── ① 老备份读得回 ───────────────

    @Test
    fun legacyBackupWithoutSemestersKeyStillDecodes() {
        val data = json.decodeFromString<BackupData>(legacyJson)

        assertEquals(2, data.version)
        assertEquals("2026-2027-1", data.semester?.termCode)
        assertEquals(
            "老备份没有 semesters 键 ⇒ 默认值空列表（不是 null、更不是解析失败）",
            emptyList<BackupSemester>(),
            data.semesters,
        )
        assertEquals("课程仍是两枚，跨两学期", 2, data.courses.size)
    }

    @Test
    fun legacyBackupPairsBackIntoSingleRowOnly() {
        val data = json.decodeFromString<BackupData>(legacyJson)
        val rows = data.semesters.map { it.toDomain() }
        val index = indexBackupSemesterRows(rows, data.semester?.toDomain())

        assertEquals(
            "老备份的索引里只有主学期那一枚（回落）；历史学期查不到 ⇒ 恢复时走「只能补建」那一等",
            listOf("2026-2027-1"),
            index.keys.toList(),
        )
        assertNull(index["2025-2026-2"])
    }

    // ─────────────── ② 新备份被老应用读时怎样 ───────────────

    @Test
    fun oldAppStillReadsNewBackup() {
        val new = BackupData(
            semester = MAIN.toBackup(),
            semesters = listOf(HISTORY.toBackup(), MAIN.toBackup()),
            courses = listOf(BackupCourse(name = "高等数学", dayOfWeek = 1, periods = listOf(1), weeks = listOf(1))),
        )
        val wire = json.encodeToString(BackupData.serializer(), new)
        assertTrue("新备份里必须真的有 semesters 这个键", wire.contains("\"semesters\""))

        val asOldAppSeesIt = json.decodeFromString<LegacyBackupData>(wire)

        assertEquals(
            "老应用读新备份：version 没涨 ⇒ 不走「版本过高」那一支",
            2,
            asOldAppSeesIt.version,
        )
        assertEquals("老应用照旧拿得到主学期", MAIN.toBackup(), asOldAppSeesIt.semester)
        assertEquals("课程照旧读得回", 1, asOldAppSeesIt.courses.size)
        assertEquals("节次/提醒这些字段一个都没丢", emptyList<BackupTimeSlot>(), asOldAppSeesIt.timeSlots)
    }

    // ─────────────── ③ 新备份自己往返 + 口令那条链 ───────────────

    @Test
    fun newBackupRoundTripsEverySemesterRow() {
        val new = BackupData(
            semester = MAIN.toBackup(),
            semesters = listOf(HISTORY.toBackup(), MAIN.toBackup()),
        )

        val decoded = json.decodeFromString<BackupData>(json.encodeToString(BackupData.serializer(), new))

        assertEquals(new, decoded)
        assertEquals(
            "两枚学期行都要原样回来（顺序也是写侧的顺序：getAllSemesters 的 startDate 倒序）",
            listOf("2025-2026-2", "2026-2027-1"),
            decoded.semesters.map { it.termCode },
        )
        assertEquals("2026-02-23", decoded.semesters.first().startDate)
    }

    @Test
    fun shareCodeCarriesAllSemesterRows() {
        val new = BackupData(
            semester = MAIN.toBackup(),
            semesters = listOf(HISTORY.toBackup(), MAIN.toBackup()),
            courses = listOf(BackupCourse(name = "高等数学", dayOfWeek = 1, periods = listOf(1), weeks = listOf(1))),
        )

        val decoded = ScheduleShareCodec.decode(ScheduleShareCodec.encode(new))

        val shared = requireNotNull(decoded)
        assertEquals(new, shared)
        assertEquals(
            "口令走的是同一个 BackupData JSON ⇒ 多带的那几枚学期行自动跟着到，" +
                "ScheduleShareCodec 一个字都不用改",
            listOf("2025-2026-2", "2026-2027-1"),
            shared.semesters.map { it.termCode },
        )
    }

    @Test
    fun oldShareCodeStillDecodesWithEmptySemesters() {
        // 升级前的口令：deflate 之前的 JSON 里根本没有 semesters 这个键（老应用编出来的那一版）
        val legacyWire = """
            {
              "version": 2,
              "semester": {"termCode": "2026-2027-1", "termName": "2026-2027学年第一学期",
                           "startDate": "2026-09-07", "totalWeeks": 19},
              "courses": [{"name": "高等数学", "dayOfWeek": 1, "periods": [1], "weeks": [1]}]
            }
        """.trimIndent()

        val decoded = requireNotNull(ScheduleShareCodec.decode(deflateToCode(legacyWire)))

        assertEquals("老口令解出来 semesters 是空列表（默认值那条路）", emptyList<BackupSemester>(), decoded.semesters)
        assertEquals("主学期照旧读得回", MAIN.toBackup(), decoded.semester)
        assertEquals("课程数不受影响", 1, decoded.courses.size)
        assertEquals(
            "空列表 + 单字段 ⇒ 索引里就只有那一枚（配对回落，见 indexBackupSemesterRows）",
            listOf("2026-2027-1"),
            indexBackupSemesterRows(decoded.semesters.map { it.toDomain() }, decoded.semester?.toDomain()).keys.toList(),
        )
    }

    /** 复刻老应用的编码那一手（JSON → deflate → URL-safe Base64），只为造出「没有那个键」的输入 */
    private fun deflateToCode(text: String): String {
        val raw = text.toByteArray(Charsets.UTF_8)
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(raw)
        deflater.finish()
        val out = ByteArrayOutputStream(raw.size / 4)
        val chunk = ByteArray(4096)
        while (!deflater.finished()) {
            out.write(chunk, 0, deflater.deflate(chunk))
        }
        deflater.end()
        return "BUAASCH1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    private companion object {
        val MAIN = Semester(
            id = 0L,
            termCode = "2026-2027-1",
            termName = "2026-2027学年第一学期",
            startDate = "2026-09-07",
            totalWeeks = 19,
        )
        val HISTORY = Semester(
            id = 0L,
            termCode = "2025-2026-2",
            termName = "2025-2026学年第二学期",
            startDate = "2026-02-23",
            totalWeeks = 18,
        )
    }
}
