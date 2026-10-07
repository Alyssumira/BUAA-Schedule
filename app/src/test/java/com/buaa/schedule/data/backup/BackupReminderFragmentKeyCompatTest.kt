package com.buaa.schedule.data.backup

import com.buaa.schedule.data.share.ScheduleShareCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T229 ②（#303）：备份键补「片段依据」这一手在**序列化兼容面**上的自证。
 *
 * 卡面要的那条判据写在这里：补一枚键，会不会让**老备份配不上新键**？答否，两侧各有各的格子 ——
 * 口径与 `BackupSemestersCompatTest`（`semesters`）、`BackupCreditCompatTest`（`credit`）同一条：
 *
 * - **老备份 → 新应用**：老文件里根本没有 `fragmentWeeks` 这个键 ⇒ 解成 `null`（不是解析失败、
 *   不是"这条提醒读不回来"）。读侧把 `null` 当成「这一枚没有片段依据」，退到**同键组内逐枚配对**，
 *   一枚都不许多丢 —— 退档只改"落到哪一行"，不改"落不落得到"。
 * - **新备份 → 老应用**：`fragmentWeeks` 是它不认得的键，两边解码用的都是
 *   `Json { ignoreUnknownKeys = true }` ⇒ 直接忽略，老应用照旧按 `courseKey` 恢复（旧行为原样）。
 *   这一条靠把升级前那一版 `BackupReminder` 的形状**在这里复刻一枚只读类型**来证
 *   （只用于测试，不参与生产代码，也不会被写进备份）。
 *
 * `BACKUP_FORMAT_VERSION` 因此**不涨**（红线）：涨到 3 只会让老应用在这一份文件上直接走
 * `data.version > BACKUP_FORMAT_VERSION` 那一支拒绝恢复，而字段本身两边都认得 ——
 * 换来的是一条**更窄**的升级路，不是更兼容。论证本体在 [BACKUP_FORMAT_VERSION] 与
 * [BackupReminder] 的段首，枚数由 [BackupReminderPairingGuardTest] 第 ⑤ 层钉。
 */
class BackupReminderFragmentKeyCompatTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** 升级前的 `BackupReminder` 形状（逐字复刻：没有 fragmentWeeks 这一枚） */
    @Serializable
    private data class LegacyBackupReminder(
        val courseKey: String,
        val enabled: Boolean = true,
        val advanceMinutes: Int = 10,
    )

    /** 升级前的整份备份形状（提醒那一行用的是上面那枚） */
    @Serializable
    private data class LegacyBackupData(
        val version: Int = 2,
        val semester: BackupSemester? = null,
        val semesters: List<BackupSemester> = emptyList(),
        val timeSlots: List<BackupTimeSlot> = emptyList(),
        val courses: List<BackupCourse> = emptyList(),
        val reminders: List<LegacyBackupReminder> = emptyList(),
    )

    /** 老应用导出过的那一份：一条键、两枚设置、没有任何片段依据 */
    private val legacyWire = """
        {
          "version": 2,
          "semester": {"termCode": "2026-2027-1", "termName": "2026-2027学年第一学期",
                       "startDate": "2026-09-07", "totalWeeks": 19},
          "courses": [
            {"name": "高等数学", "teacher": "张三", "location": "J3-101", "dayOfWeek": 1,
             "periods": [1, 2], "weeks": [1, 2, 3], "semesterCode": "2026-2027-1"},
            {"name": "高等数学", "teacher": "张三", "location": "J3-101", "dayOfWeek": 1,
             "periods": [1, 2], "weeks": [9, 10, 11], "semesterCode": "2026-2027-1"}
          ],
          "reminders": [
            {"courseKey": "K", "enabled": false, "advanceMinutes": 15},
            {"courseKey": "K", "enabled": true, "advanceMinutes": 5}
          ]
        }
    """.trimIndent()

    // ─────────────── ① 老备份读得回，且一枚都不许多丢 ───────────────

    @Test
    fun legacyReminderWithoutFragmentKeyDecodesToNull() {
        val data = json.decodeFromString<BackupData>(legacyWire)

        assertEquals(2, data.version)
        assertEquals("提醒仍是两枚（同键两枚也照收：归并是配对那一层的事，不是解码那一层）", 2, data.reminders.size)
        data.reminders.forEach { reminder ->
            assertNull(
                "老备份没有 fragmentWeeks 键 ⇒ 必须是 null（带默认值的可选字段）。" +
                    "解成空列表也算不上诚实：那是「片段依据 = 什么周次都没有」，" +
                    "与「这一枚备份压根没带依据」是两件事",
                reminder.fragmentWeeks,
            )
        }
        assertEquals(listOf(false to 15, true to 5), data.reminders.map { it.enabled to it.advanceMinutes })
    }

    @Test
    fun legacyReminderStillPairsEveryFragment() {
        val data = json.decodeFromString<BackupData>(legacyWire)
        val rows = data.courses.mapIndexed { i, course ->
            BackupReminderPairing.rowOf(course.toDomain().copy(id = 100L + i))
        }
        // 老备份里那枚键是手写的占位串 "K"，这里换成**真算出来的** courseKey：
        // #303 的成因就是"两枚片段算出同一枚键"，用假键就测不到那一层
        val sharedKey = rows.first().courseKey
        assertEquals("两枚片段算出来的键相同（这一格的前提）", sharedKey, rows[1].courseKey)
        val settings = data.reminders.map {
            BackupReminderPairing.settingOf(it.copy(courseKey = sharedKey))
        }

        val outcome = BackupReminderPairing.pair(settings, rows)

        assertEquals(
            "老备份的两枚设置都必须落到某一枚片段上：改前 `associate` 先吃掉一枚 ⇒ 这里只会有 1 枚",
            2,
            outcome.assignments.size,
        )
        assertEquals("零丢枚（老备份不该比新备份更差）", 0, outcome.unplaced.size)
        assertEquals(
            "两枚都记成「老备份无依据」那一档 —— 恢复文案与守卫读的就是这一档",
            listOf(BackupReminderPairing.Basis.LegacyNoSignature, BackupReminderPairing.Basis.LegacyNoSignature),
            outcome.assignments.map { it.basis },
        )
    }

    // ─────────────── ② 新备份被老应用读时怎样 ───────────────

    @Test
    fun oldAppStillReadsNewBackupWithFragmentKey() {
        val new = BackupData(
            version = BACKUP_FORMAT_VERSION,
            semester = BackupSemester("2026-2027-1", "2026-2027学年第一学期", "2026-09-07", 19),
            reminders = listOf(
                BackupReminder(courseKey = "K", enabled = false, advanceMinutes = 15, fragmentWeeks = listOf(1, 2, 3)),
                BackupReminder(courseKey = "K", enabled = true, advanceMinutes = 5, fragmentWeeks = listOf(9, 10, 11)),
            ),
        )
        val wire = json.encodeToString(BackupData.serializer(), new)
        assertTrue("新备份里必须真的有 fragmentWeeks 这个键", wire.contains("\"fragmentWeeks\""))

        val asOldAppSeesIt = json.decodeFromString<LegacyBackupData>(wire)

        assertEquals(
            "老应用读新备份：version 没涨 ⇒ 不走「版本过高」那一支（这条判据的读点在 " +
                "`ui/ScheduleViewModel.restoreFromData`，枚数钉在 BackupReminderPairingGuardTest 第 ⑤ 层）",
            2,
            asOldAppSeesIt.version,
        )
        assertEquals("老应用照旧拿得到两条提醒", 2, asOldAppSeesIt.reminders.size)
        assertEquals(
            "老应用看不见 fragmentWeeks（ignoreUnknownKeys 忽略），其余字段一个都不丢",
            listOf(LegacyBackupReminder("K", false, 15), LegacyBackupReminder("K", true, 5)),
            asOldAppSeesIt.reminders,
        )
        assertEquals(new.semester, asOldAppSeesIt.semester)
    }

    // ─────────────── ③ 新版本号：不涨，且两侧读的都是同一枚常量 ───────────────

    @Test
    fun backupFormatVersionStaysTwo() {
        assertEquals(
            "BACKUP_FORMAT_VERSION 必须还是 2：这一枚新增的是**带默认值的可选字段**，" +
                "与 BackupData.semesters（T204）、BackupCourse.credit 同口径。涨到 3 换来的只是" +
                "老应用对这份新文件直接拒绝恢复，而它本来读得回",
            2,
            BACKUP_FORMAT_VERSION,
        )
        assertEquals(
            "写侧默认值仍然引用同一枚常量（不是一处裸字面量）",
            BACKUP_FORMAT_VERSION,
            BackupData().version,
        )
    }

    @Test
    fun newReminderRoundTripsThroughJson() {
        val reminder = BackupReminder("K", false, 15, listOf(1, 2, 3))

        val encoded = json.encodeToString(BackupReminder.serializer(), reminder)
        val decoded = json.decodeFromString(BackupReminder.serializer(), encoded)

        assertEquals("片段依据必须原样往返（写进文件、读回来还是那一串周次）", reminder, decoded)
        assertEquals(
            "编码出来的键名逐字是这个（守卫第 ⑤ 层按字符串名钉 JSON 契约，改名就等于断老备份的键）",
            true,
            encoded.contains("\"fragmentWeeks\":[1,2,3]"),
        )

        val withoutKey = json.decodeFromString(
            BackupReminder.serializer(),
            """{"courseKey":"K","enabled":false,"advanceMinutes":15}""",
        )
        assertNull("缺键 ⇒ null，不是抛、不是空列表", withoutKey.fragmentWeeks)
        assertEquals("其余字段不受影响", false to 15, withoutKey.enabled to withoutKey.advanceMinutes)
    }

    // ─────────────── ④ 口令那条链：同一个 JSON，跟着到 ───────────────

    @Test
    fun shareCodeCarriesFragmentKey() {
        val payload = BackupData(
            courses = listOf(
                BackupCourse(name = "高等数学", teacher = "张三", location = "J3-101", dayOfWeek = 1,
                    periods = listOf(1, 2), weeks = listOf(1, 2, 3)),
            ),
            reminders = listOf(BackupReminder("K", true, 30, listOf(1, 2, 3))),
        )

        val decoded = requireNotNull(ScheduleShareCodec.decode(ScheduleShareCodec.encode(payload)))

        assertEquals(payload, decoded)
        assertEquals(
            "口令走的是同一个 BackupData JSON ⇒ 片段依据自动跟着到，" +
                "data/share/ScheduleShareCodec 一个字都不用改",
            listOf(1, 2, 3),
            decoded.reminders.single().fragmentWeeks,
        )
    }
}
