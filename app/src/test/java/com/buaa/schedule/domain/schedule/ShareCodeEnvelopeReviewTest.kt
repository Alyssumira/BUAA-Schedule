package com.buaa.schedule.domain.schedule

import com.buaa.schedule.data.backup.BACKUP_COURSES_KEY
import com.buaa.schedule.data.backup.BACKUP_ENVELOPE_KEYS
import com.buaa.schedule.data.backup.BACKUP_REMINDERS_KEY
import com.buaa.schedule.data.backup.BACKUP_SEMESTERS_KEY
import com.buaa.schedule.data.backup.BACKUP_TIME_SLOTS_KEY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T283②：`ShareCodeEnvelopeReview` 的表驱动单测（台账 #530 发现 3）。
 *
 * 钉的是「闸门吃的那三枚条数来自**原始 JSON**，不来自模型的默认值」这一判的**判据侧**；
 * 接线侧（codec 真的把原始读数递进来、VM 真的吃 `Valid.counts`）归
 * `ShareCodeEntryGateWiringGuardTest`，端到端那一等归 `ScheduleShareCodecTierTest`。
 *
 * 键名册子内嵌一份**抄件**（`REGISTER`），逐字节钉的就是盘上 `BACKUP_ENVELOPE_KEYS` 那枚抄件对不对得上；
 * 表里的 `wireKey` 一律取 `BACKUP_*_KEY` 那几枚常量，不在测试里再写一遍字符串。
 */
class ShareCodeEnvelopeReviewTest {

    private companion object {
        /** 顶层键册子的期望值（顺序 = `BackupData` 属性声明顺序） */
        val REGISTER = listOf("version", "semester", "semesters", "timeSlots", "courses", "reminders")

        const val CURRENT = 2
    }

    private fun gated(
        courses: Int? = null,
        timeSlots: Int? = null,
        reminders: Int? = null,
        coursesShapeOk: Boolean = true,
    ) = listOf(
        ShareCodeGatedTableRow(
            slot = ShareCodeGatedTable.Courses,
            wireKey = BACKUP_COURSES_KEY,
            present = courses != null,
            rows = if (courses != null && coursesShapeOk) courses else if (courses != null) null else courses,
        ),
        ShareCodeGatedTableRow(
            slot = ShareCodeGatedTable.TimeSlots,
            wireKey = BACKUP_TIME_SLOTS_KEY,
            present = timeSlots != null,
            rows = timeSlots,
        ),
        ShareCodeGatedTableRow(
            slot = ShareCodeGatedTable.Reminders,
            wireKey = BACKUP_REMINDERS_KEY,
            present = reminders != null,
            rows = reminders,
        ),
    )

    private fun facts(
        gatedRows: List<ShareCodeGatedTableRow> = gated(),
        knownOther: List<Pair<String, Int>> = emptyList(),
        unreadableArrays: List<Pair<String, Int>> = emptyList(),
        unreadableOther: List<String> = emptyList(),
        versionPresent: Boolean = false,
        versionCode: Int? = null,
    ) = ShareCodeEnvelopeFacts(
        gatedRows = gatedRows,
        knownOtherArrayRows = knownOther,
        unreadableArrayRows = unreadableArrays,
        unreadableOtherKeys = unreadableOther,
        versionKeyPresent = versionPresent,
        versionCode = versionCode,
        currentFormatVersion = CURRENT,
    )

    @Test
    fun 册子的真源是序列化描述符() {
        // 逐字节：domain 里不许有第二份键名清单，判据吃的这份必须由 BackupData 自己给出
        assertEquals(
            "BACKUP_ENVELOPE_KEYS 与 BackupData 的属性清单不一致（改了字段名就是改了格式）",
            REGISTER,
            BACKUP_ENVELOPE_KEYS,
        )
        assertEquals(
            "三枚闸门表的键名册子必须各一处定义（第二处就是第二把尺）",
            listOf(BACKUP_COURSES_KEY, BACKUP_TIME_SLOTS_KEY, BACKUP_REMINDERS_KEY),
            listOf("courses", "timeSlots", "reminders"),
        )
    }

    private data class TierCell(
        val name: String,
        val facts: ShareCodeEnvelopeFacts,
        val expectTier: String,
        val expectCounts: RestoreCounts?,
        val note: String,
    )

    @Test
    fun 信封档位每一格() {
        val cells = listOf(
            TierCell(
                name = "三张表都在且带行 ⇒ 读全了，条数就是原始行数",
                facts = facts(gated(courses = 20, timeSlots = 3, reminders = 1), versionPresent = true, versionCode = CURRENT),
                expectTier = "Understood",
                expectCounts = RestoreCounts(20, 3, 1),
                note = "这是正常口令那一等：counts 与模型行数应当一致（键名都对得上时两把尺同值）",
            ),
            TierCell(
                name = "键名不认识 + 认得的表恰好没带 ⇒ 读不懂的表（改前这一等是 Valid + 被清零的 0）",
                facts = facts(
                    gated(courses = 20),
                    unreadableArrays = listOf("slotTimes" to 600),
                    versionPresent = true,
                    versionCode = CURRENT,
                ),
                expectTier = "UnreadableTables",
                expectCounts = RestoreCounts(20, 0, 0),
                note = "#530 发现 3 的本体：600 行节次在改前的闸门那里读成 0，于是确认弹窗一次都不过",
            ),
            TierCell(
                name = "什么表都没带（三枚键都在、全是空列表）⇒ NothingNamed",
                facts = facts(gated(courses = 0, timeSlots = 0, reminders = 0), versionPresent = true, versionCode = CURRENT),
                expectTier = "NothingNamed",
                expectCounts = null,
                note = "写侧明说这张表是空的（三枚键都出现了）⇒ 没有可恢复的内容，句子里不许点名读不懂的键；" +
                    "这一档交回的是『什么都没带』这个结论本身，不带着数（带着数就是让调用点以为有东西可写）",
            ),
            TierCell(
                name = "version 键缺席 ⇒ 不判成新版（本仓写侧本来就不带这枚键）",
                facts = facts(gated(courses = 2, timeSlots = 1), versionPresent = false, versionCode = null),
                expectTier = "Understood",
                expectCounts = RestoreCounts(2, 1, 0),
                note = "丙档（要求显式版本标记）今天会当场打死自家口令：encode 用 encodeDefaults=false ⇒ version 被省略",
            ),
            TierCell(
                name = "version 键在、值高于当前 ⇒ NewerFormat",
                facts = facts(gated(courses = 2), versionPresent = true, versionCode = CURRENT + 1),
                expectTier = "NewerFormat",
                expectCounts = null,
                note = "改前这一问吃的是模型默认值：键被改名（schema）就永远不响",
            ),
            TierCell(
                name = "version 键在、值等于当前 ⇒ 放行",
                facts = facts(gated(courses = 2), versionPresent = true, versionCode = CURRENT),
                expectTier = "Understood",
                expectCounts = RestoreCounts(2, 0, 0),
                note = "两侧各一格：+1 才拦，等于不拦",
            ),
            TierCell(
                name = "认得的键在、但值不是列表 ⇒ UnreadableTables（换了形状）",
                facts = facts(
                    gatedRows = gated(courses = 0, timeSlots = 0, reminders = 0, coursesShapeOk = false),
                    versionPresent = true,
                    versionCode = CURRENT,
                ),
                expectTier = "UnreadableTables",
                expectCounts = RestoreCounts(0, 0, 0),
                note = "present 而 rows=null 是「形状不是那一套」，与「没带这枚键」不是一回事；" +
                    "数不出行数 ⇒ 交回的条数只能是 0，而这正是本卡要在这一档拦下去的理由（闸门拿不到真值）",
            ),
            TierCell(
                name = "多一枚不认得的标量键 + 表都在 ⇒ 照老契约放过（记下来）",
                facts = facts(
                    gated(courses = 5, timeSlots = 2, reminders = 1),
                    knownOther = listOf(BACKUP_SEMESTERS_KEY to 2),
                    unreadableOther = listOf("futureField"),
                    versionPresent = true,
                    versionCode = CURRENT,
                ),
                expectTier = "Understood",
                expectCounts = RestoreCounts(5, 2, 1),
                note = "兼容性判据（BackupCreditCompatTest 那一族）钉的就是这一等：新增可选键不许打死老口令",
            ),
            TierCell(
                name = "只有 semester 单字段、其它表全缺席（老应用编出来的那一版）⇒ 放行且条数 0",
                facts = facts(gated(), versionPresent = true, versionCode = CURRENT),
                expectTier = "Understood",
                expectCounts = RestoreCounts(0, 0, 0),
                note = "键「缺席」不等于键「被改名」：老口令没有 semesters/timeSlots/reminders 三枚键，照旧读得回（BackupSemestersCompatTest 钉着）",
            ),
            TierCell(
                name = "读不懂的数组键 + 什么都没带 ⇒ 仍判读不懂（不是什么都没带）",
                facts = facts(gated(courses = 0, timeSlots = 0, reminders = 0), unreadableArrays = listOf("rows" to 9)),
                expectTier = "UnreadableTables",
                expectCounts = RestoreCounts(0, 0, 0),
                note = "顺序判据：先问读没读全，再问带没带东西 —— 病因是表名，不是空",
            ),
        )
        for (cell in cells) {
            val decision = ShareCodeEnvelopeReview.review(cell.facts)
            val tier = decision::class.simpleName!!
            assertEquals("${cell.name}｜${cell.note}｜实到 $tier", cell.expectTier, tier)
            // 条数读数在**这两档**都得带着：Understood 那一枚要递给条数闸门，
            // UnreadableTables 那一枚要说得出"本应用看见了几张、没看见哪几张"（改前交回的是被清零的 0）。
            // NewerFormat 根本不数，NothingNamed 数出来必是 0 —— 那两档交回 null 而不是假装带数。
            val readCounts = when (decision) {
                is ShareCodeEnvelopeDecision.Understood -> decision.counts
                is ShareCodeEnvelopeDecision.UnreadableTables -> decision.counts
                is ShareCodeEnvelopeDecision.NewerFormat,
                is ShareCodeEnvelopeDecision.NothingNamed,
                -> null
            }
            if (cell.expectCounts != null) {
                assertEquals("${cell.name}：条数必须来自原始读数", cell.expectCounts, readCounts)
            }
            if (decision is ShareCodeEnvelopeDecision.UnreadableTables && decision.unreadableArrayKeys.isNotEmpty()) {
                assertTrue(
                    "${cell.name}：读不懂那一等必须把行数带出来（不许只剩一句「无法识别」）",
                    decision.unreadableArrayRows > 0 || decision.reshapedKnownKeys.isNotEmpty(),
                )
            }
        }
    }

    @Test
    fun 闸门吃到的节次条数不再是那枚被清零的0() {
        // 这就是卡面要求的「改前 0 / 改后非 0」那一格，两头都量：
        val payloadSlotRows = 600
        val renamed = facts(
            gated(courses = 20),
            unreadableArrays = listOf("slotTimes" to payloadSlotRows),
            versionPresent = true,
            versionCode = CURRENT,
        )
        val decision = ShareCodeEnvelopeReview.review(renamed)
        assertTrue("改了名的表必须判成读不懂：实到 ${decision::class.simpleName}", decision is ShareCodeEnvelopeDecision.UnreadableTables)
        val unreadable = decision as ShareCodeEnvelopeDecision.UnreadableTables
        assertEquals(
            "改前递给 capacityViolation 的 timeSlotCount 是 0（模型替它兜的底），" +
                "而现在这一等交回来的读数是口令里真的带着的 600 行",
            payloadSlotRows,
            unreadable.unreadableArrayRows,
        )
        assertEquals(
            "同时要说得出本应用没看到哪张表（节次/提醒两枚键缺席）",
            listOf(BACKUP_TIME_SLOTS_KEY, BACKUP_REMINDERS_KEY),
            unreadable.missingGatedTableKeys,
        )
        // 反面对照：同一份 600 行如果是**认得的**表名带进来的，闸门按 600 数、按 500 拒
        val honest = facts(gated(courses = 20, timeSlots = payloadSlotRows, reminders = 0))
        val understood = ShareCodeEnvelopeReview.review(honest) as ShareCodeEnvelopeDecision.Understood
        assertEquals(600, understood.counts.timeSlotCount)
        val reject = RestoreVolumeGate.capacityViolation(
            counts = understood.counts,
            limits = RestoreVolumeLimits.Default,
            entry = RestoreEntry.ShareCode,
        )
        assertEquals(
            "同一条 600 行节次，键名认得时被条数闸门点名拒掉（改前那一等本来就对）",
            OverCapacityTable.TimeSlots,
            reject?.table,
        )
        assertEquals(payloadSlotRows, reject?.count)
    }

    @Test
    fun 档位没有一档被折成Boolean() {
        // 本工程没有 kotlin-reflect ⇒ `KClass.sealedSubclasses` 直接抛 KotlinReflectionNotSupportedError
        //（在册教训见 IcsExportGateTest 第 ④ 层），所以这一格吃的是内核**跑出来的四枚档**而不是分支表；
        //「声明恰好四枚」那把静态尺在 ShareCodeEntryGateWiringGuardTest（读的是源码形状）。
        val seen = listOf(
            ShareCodeEnvelopeReview.review(facts(gated(courses = 1))),
            ShareCodeEnvelopeReview.review(facts(versionPresent = true, versionCode = CURRENT + 1)),
            ShareCodeEnvelopeReview.review(facts(gated(), unreadableArrays = listOf("slotTimes" to 600))),
            ShareCodeEnvelopeReview.review(facts(gated(courses = 0, timeSlots = 0, reminders = 0))),
        ).map { it.javaClass.simpleName }.sorted()
        assertEquals(
            "四档各跑出过一枚（读全了 / 版本太高 / 表名读不懂 / 什么都没带）；折成 Boolean 调用点只能猜：实到 $seen",
            listOf("NewerFormat", "NothingNamed", "Understood", "UnreadableTables"),
            seen,
        )
    }

    @Test
    fun 三句被拒的话都说得出下一步该干什么() {
        val newer = shareCodeNewerFormatCopy(ShareCodeEnvelopeDecision.NewerFormat(3, CURRENT))
        assertTrue("新版那一等要报得出两枚版本号：$newer", newer.contains("v3") && newer.contains("v$CURRENT"))
        assertTrue("并且要落一句下一步：$newer", newer.contains("升级"))
        assertFalse("被拒那一等不许含糊成「无法识别」：$newer", newer.contains("无法识别"))
        val unreadable = shareCodeUnreadableTablesCopy(
            ShareCodeEnvelopeDecision.UnreadableTables(
                unreadableArrayKeys = listOf("slotTimes"),
                unreadableArrayRows = 600,
                missingGatedTableKeys = listOf(BACKUP_TIME_SLOTS_KEY),
                reshapedKnownKeys = emptyList(),
                counts = RestoreCounts(20, 0, 0),
            ),
        )
        assertTrue("读不懂那一等要点名键名与行数：$unreadable", unreadable.contains("slotTimes") && unreadable.contains("600"))
        assertTrue("还要说得出没看到哪张表：$unreadable", unreadable.contains(BACKUP_TIME_SLOTS_KEY))
        assertTrue("一条都没写这件事也得念出来：$unreadable", unreadable.contains("一条都没有写入"))
        val nothing = shareCodeNothingNamedCopy(ShareCodeEnvelopeDecision.NothingNamed(emptyList(), true))
        assertTrue("什么都没带那一等要给两种解释里的至少一种：$nothing", nothing.contains("空"))
        assertFalse("这一等不是「口令坏了」：$nothing", nothing.contains("无法识别"))
    }

    @Test
    fun 表名点名的那本册子与条数闸门同源() {
        // 三枚受闸门管的表，在信封判据这边与在 RestoreVolumeGate 那边必须是同一批名字（不许第二套量词）
        assertEquals(
            listOf(OverCapacityTable.Courses, OverCapacityTable.TimeSlots, OverCapacityTable.Reminders),
            ShareCodeGatedTable.entries.map { it.capacityTable },
        )
        assertEquals(
            listOf("课程", "节次", "提醒"),
            ShareCodeGatedTable.entries.map { it.capacityTable.label },
        )
    }
}
