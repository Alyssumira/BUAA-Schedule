package com.buaa.schedule.data.share

import com.buaa.schedule.data.backup.BACKUP_COURSES_KEY
import com.buaa.schedule.data.backup.BACKUP_REMINDERS_KEY
import com.buaa.schedule.data.backup.BACKUP_TIME_SLOTS_KEY
import com.buaa.schedule.data.backup.BackupCourse
import com.buaa.schedule.data.backup.BackupData
import com.buaa.schedule.data.backup.BackupSemester
import com.buaa.schedule.data.backup.BackupTimeSlot
import com.buaa.schedule.domain.schedule.RestoreCounts
import com.buaa.schedule.domain.schedule.ShareCodeEntryGate
import com.buaa.schedule.domain.schedule.ShareCodePayloadGate
import com.buaa.schedule.domain.schedule.ShareCodeSizeGate
import com.buaa.schedule.domain.schedule.shareCodeOverCapCopy
import com.buaa.schedule.domain.schedule.shareCodeUnreadableTablesCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater

/**
 * T283②：口令解码那一站的**端到端档位**表驱动单测（台账 #530 发现 1/2/3/4）。
 *
 * 分工：`ShareCodeSizeGateTest` / `ShareCodeEnvelopeReviewTest` 钉判据本体，这一枚钉
 * 「codec 真的按 ①字符数 → ②解压体积 → ③信封 → ④条数 走了一遍，并把每档的读数原样交出来」。
 * 载荷一律用 `deflateToCode` 手工编（复刻别的 App 编出来的那一版），不走 `encode()` 自己产的
 * 正形口令 —— 否则就是编解码闭环自证，"读不全"那一档永远零测试（改前正是这样，审计档逐字点过）。
 *
 * 纯 JVM：不碰设备、不读时钟、不起协程（codec 里一行 `Log` 都没有，留痕在调用点）。
 */
class ScheduleShareCodecTierTest {

    /** 复刻写侧那一手（JSON → deflate → URL-safe Base64），只为造出「键名不对 / 太大 / 版本更高」的输入 */
    private fun deflateToCode(text: String): String {
        val raw = text.toByteArray(Charsets.UTF_8)
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(raw)
        deflater.finish()
        val out = ByteArrayOutputStream(raw.size / 4)
        val chunk = ByteArray(4096)
        while (!deflater.finished()) {
            val n = deflater.deflate(chunk)
            out.write(chunk, 0, n)
        }
        deflater.end()
        return "BUAASCH1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    private fun coursesJson(count: Int, key: String = BACKUP_COURSES_KEY): String =
        "\"$key\":[" + (1..count).joinToString(",") {
            "{\"name\":\"课程$it\",\"dayOfWeek\":1,\"periods\":[1],\"weeks\":[1]}"
        } + "]"

    private fun slotRowsJson(key: String, count: Int): String =
        "\"$key\":[" + (1..count).joinToString(",") {
            """{"number":$it,"startTime":"08:00","endTime":"08:45"}"""
        } + "]"

    private data class TierCell(
        val name: String,
        val payload: String,
        val expectTier: String,
        val note: String,
    )

    @Test
    fun 信封形状每一格端到端() {
        val cells = listOf(
            TierCell(
                name = "改了名的节次表：认得的 courses 带 20 行，slotTimes 里 600 行",
                payload = "{\"version\":2,${coursesJson(20)},${slotRowsJson("slotTimes", 600)}}",
                expectTier = "UnreadableTables",
                note = "改前这一等交回 Valid(counts=20,0,0) ⇒ 600 行在闸门那里是 0，确认弹窗一次都不过",
            ),
            TierCell(
                name = "版本键高于本应用：version=3",
                payload = "{\"version\":3,${coursesJson(1)}}",
                expectTier = "FromNewerApp",
                note = "改前这一问靠模型的默认值响；键名一换（新版写成 schema）就永远不响",
            ),
            TierCell(
                name = "版本键缺席（本仓写侧自己的形状）⇒ 照旧放行",
                payload = "{\"semester\":null,${coursesJson(3)}}",
                expectTier = "Valid",
                note = "丙档（要求显式版本标记）今天会打死自家口令：encodeDefaults=false ⇒ version 被省略",
            ),
            TierCell(
                name = "三张表都在而全是空列表 ⇒ 什么表都没带",
                payload = "{\"version\":2,\"semester\":null,\"semesters\":[],\"timeSlots\":[],\"courses\":[],\"reminders\":[]}",
                expectTier = "NothingRestorable",
                note = "写侧明说这张表是空的 ⇒ 一句「没有可恢复的内容」，不许折成「无法识别」",
            ),
            TierCell(
                name = "老口令的形状：只有 semester 单字段，认得的表全缺席 ⇒ 放行且条数 0",
                payload = "{\"version\":2,\"semester\":{\"termCode\":\"2026-2027-1\",\"termName\":\"2026-2027-1\",\"startDate\":\"2026-09-07\",\"totalWeeks\":19}}",
                expectTier = "Valid",
                note = "键「缺席」不等于「被改名」：不许把 BackupSemestersCompatTest 钉着的旧形状判死",
            ),
            TierCell(
                name = "认得的键换了形状：courses 是对象不是列表",
                payload = "{\"version\":2,\"courses\":{\"a\":1}}",
                expectTier = "UnreadableTables",
                note = "present 而 rows=null ⇒ 形状不是那一套，句子要点名",
            ),
            TierCell(
                name = "新增的标量键 + 表都在 ⇒ 照 ignoreUnknownKeys 老契约放过",
                payload = "{\"version\":2,\"futureField\":\"x\",${coursesJson(2)}}",
                expectTier = "Valid",
                note = "兼容性判据那一族钉的就是这一等（只新增可选键 ⇒ 老应用照旧读得回）",
            ),
            TierCell(
                name = "顶层不是对象（一个数组）",
                payload = "[1,2,3]",
                expectTier = "Malformed",
                note = "这一等才是「根本不是口令的形状」",
            ),
        )
        for (cell in cells) {
            val outcome = ScheduleShareCodec.decodeOutcome(deflateToCode(cell.payload))
            assertEquals(
                "${cell.name}｜${cell.note}｜实到 ${tierName(outcome)}",
                cell.expectTier,
                tierName(outcome),
            )
        }
    }

    private fun tierName(outcome: ShareCodeOutcome): String = when (outcome) {
        is ShareCodeOutcome.OverSize.EntryTooLong -> "OverSize.EntryTooLong"
        is ShareCodeOutcome.OverSize.PayloadTooLarge -> "OverSize.PayloadTooLarge"
        is ShareCodeOutcome.Malformed -> "Malformed"
        is ShareCodeOutcome.FromNewerApp -> "FromNewerApp"
        is ShareCodeOutcome.UnreadableTables -> "UnreadableTables"
        is ShareCodeOutcome.NothingRestorable -> "NothingRestorable"
        is ShareCodeOutcome.OverCapacity -> "OverCapacity"
        is ShareCodeOutcome.Valid -> "Valid"
    }

    @Test
    fun 读不懂的那一等交回来的行数不是0() {
        val code = deflateToCode("{\"version\":2,${coursesJson(20)},${slotRowsJson("slotTimes", 600)}}")
        val outcome = ScheduleShareCodec.decodeOutcome(code)
        val unreadable = outcome as? ShareCodeOutcome.UnreadableTables
        assertNotNull("改了名的表必须判成读不懂那一等：实到 ${tierName(outcome)}", unreadable)
        assertEquals(
            "改前递给 capacityViolation 的是被默认值清零的那枚 0；现在结论里带着口令真配的 600 行",
            600,
            unreadable!!.decision.unreadableArrayRows,
        )
        assertEquals(
            "同时要说得出本应用没看到哪两张表（节次与提醒两枚键都缺席）",
            listOf(BACKUP_TIME_SLOTS_KEY, BACKUP_REMINDERS_KEY),
            unreadable.decision.missingGatedTableKeys,
        )
        val copy = shareCodeUnreadableTablesCopy(unreadable.decision)
        assertTrue("句子要点名读不懂的键：$copy", copy.contains("slotTimes"))
        assertTrue("句子要报得出行数：$copy", copy.contains("600"))
        assertNull("这一等一条都不许落库（兼容入口仍旧交回 null）", ScheduleShareCodec.decode(code))
    }

    @Test
    fun 合法口令的条数从原始JSON上来() {
        val backup = BackupData(
            semester = BackupSemester("2026-2027-1", "2026-2027-1", "2026-09-07", 19),
            timeSlots = listOf(BackupTimeSlot(1, "08:00", "08:45")),
            courses = listOf(
                BackupCourse(name = "高等数学", dayOfWeek = 1, periods = listOf(1, 2), weeks = (1..16).toList()),
                BackupCourse(name = "算法", dayOfWeek = 3, periods = listOf(9, 10), weeks = listOf(1, 3, 5)),
            ),
        )
        val outcome = ScheduleShareCodec.decodeOutcome(ScheduleShareCodec.encode(backup))
        val valid = outcome as? ShareCodeOutcome.Valid
        assertNotNull("正形口令仍旧放行：实到 ${tierName(outcome)}", valid)
        assertEquals(
            "Valid 必须自带条数（调用点拿它喂闸门，不再摸模型的默认值）",
            RestoreCounts(courseCount = 2, timeSlotCount = 1, reminderCount = 0),
            valid!!.counts,
        )
        assertEquals(
            "键名都对得上时，原始读数与模型读数同值（两把尺量同一件事不许给出两个答案）",
            backup.courses.size,
            valid.counts.courseCount,
        )
    }

    @Test
    fun 完整但太大那一档端到端() {
        // 一条**没有损坏**、只是内容超 4MB 的口令：改前把 4MB 处的截断前缀喂给 JSON 解析器，
        // 失败之后折进「口令无法识别，请检查是否完整复制」—— 劝人重抄一条本来就完整的码。
        val big = buildString {
            append("{\"version\":2,\"")
            append(BACKUP_COURSES_KEY)
            append("\":[")
            var i = 0
            while (length < 5_000_000) {
                append("{\"name\":\"课程")
                append(i++)
                append("\",\"dayOfWeek\":1,\"periods\":[1],\"weeks\":[1]},")
            }
            append("{\"name\":\"尾\",\"dayOfWeek\":1,\"periods\":[1],\"weeks\":[1]}]}")
        }
        val code = deflateToCode(big)
        assertTrue(
            "造出来的口令本身要在字符数上限内（这一档测的是解压尺）：${code.length}",
            code.length < ShareCodeSizeGate.MAX_SHARE_CODE_CHARS,
        )
        val outcome = ScheduleShareCodec.decodeOutcome(code)
        val over = outcome as? ShareCodeOutcome.OverSize.PayloadTooLarge
        assertNotNull("内容超上限那一等必须自成一档：实到 ${tierName(outcome)}", over)
        val decision = over!!.decision
        assertEquals("字节数读数要就是那枚上限（循环停在撞线处）", 4 * 1024 * 1024, decision.decompressedBytes)
        assertEquals("上限读数也要交得出来", 4 * 1024 * 1024, decision.capBytes)
        assertFalse("流没走完：内容比上限大，但不能谎称它是被截断的复制", decision.streamFinished)
        val copy = shareCodeOverCapCopy(decision)
        assertFalse("这一等的句子不许劝人重新复制（那条口令就是完整的）：$copy", copy.contains("完整复制"))
        assertTrue("这一等要说一条都没写：$copy", copy.contains("一条都没有写入"))
        assertNull(ScheduleShareCodec.decode(code))
    }

    @Test
    fun 字符数尺两侧各一格端到端() {
        val max = ShareCodeSizeGate.MAX_SHARE_CODE_CHARS
        val justOver = "BUAASCH1:" + "A".repeat(max)
        val entry = ScheduleShareCodec.decodeOutcome(justOver)
        val over = entry as? ShareCodeOutcome.OverSize.EntryTooLong
        assertNotNull("贴进来的字符数越上限要自成一档（改前这里零判据，先分配再说）：实到 ${tierName(entry)}", over)
        assertEquals("闸门报的字符数就是粘过来的那一串的长度", justOver.length, over!!.decision.codeChars)
        assertEquals("上限读数来自册子那枚", max, (over.decision as ShareCodeEntryGate.TooManyChars).maxCodeChars)
        // 反手一格：上限内的长串**不受这一档拦**（它因为不是合法压缩流而落进 Malformed，
        // 那才是「多半没复制完整」那句话该出现的地方）
        val inside = "BUAASCH1:" + "A".repeat(max - 20)
        assertEquals(
            "上限内的长串不该被体积尺拦住",
            "Malformed",
            tierName(ScheduleShareCodec.decodeOutcome(inside)),
        )
    }

    @Test
    fun 解码异常不再被咽掉() {
        val broken = ScheduleShareCodec.decodeOutcome("BUAASCH1:AAAAAAAA")
        val malformed = broken as? ShareCodeOutcome.Malformed
        assertNotNull("坏压缩流仍旧是「无法识别」那一等：实到 ${tierName(broken)}", malformed)
        assertNotNull(
            "#530 发现 4：改前 runCatching{}.getOrNull() 连 Throwable 一起吃掉且零留痕 —— " +
                "现在异常的类型与 message 跟着结论一起交出去，调用点才留得下痕",
            malformed!!.failure,
        )
        assertTrue("读数要有类型名（logcat 里查得出是哪一站）：${malformed.failure}", malformed.failure!!.type.isNotEmpty())
        // 前缀都不对那一等没有异常可记 ⇒ failure 为 null（不许编造一枚）
        assertNull((ScheduleShareCodec.decodeOutcome("高等数学,张三,J3-101") as ShareCodeOutcome.Malformed).failure)
        assertNull((ScheduleShareCodec.decodeOutcome("") as ShareCodeOutcome.Malformed).failure)
    }

    @Test
    fun 被剪贴板截短那一等说的才是没复制完整() {
        // 与上一格那条"内容太大"的口令对照：这一条**没坏也没太大**，只是没抄全 ⇒ 压缩流在上限之前就停了。
        // 改前它和"太大"共用一句「请检查是否完整复制」——那一句对这一等恰好是真的，
        // 对"太大"那一等是假话（于是 domain 把 OverCap 的句子钉成不许出现「完整复制」，见 ShareCodeSizeGateTest）。
        val full = deflateToCode("{\"version\":2,${coursesJson(60)}}")
        assertEquals("先确认整条口令解得开（这一格测的是截断，不是坏数据）", "Valid", tierName(ScheduleShareCodec.decodeOutcome(full)))
        val body = full.removePrefix("BUAASCH1:")
        val truncated = "BUAASCH1:" + body.substring(0, body.length / 2 / 4 * 4)
        val outcome = ScheduleShareCodec.decodeOutcome(truncated)
        val malformed = outcome as? ShareCodeOutcome.Malformed
        assertNotNull("被截短的口令仍旧是「无法识别」那一等（不许放它进条数闸门）：实到 ${tierName(outcome)}", malformed)
        val type = malformed!!.failure?.type
        assertTrue(
            "这一等的读数必须指着压缩流那一站（StreamStoppedEarly 的哨兵名，或 zip 自己那枚异常）：$type",
            type == "incomplete-deflate-stream" || type?.contains("DataFormat") == true,
        )
        assertNull("截断那一等一条都不许落库", ScheduleShareCodec.decode(truncated))
    }

    /** 造一条**解压后超 4MB** 的完整载荷（不是坏流），只为把 PayloadTooLarge 那一档真跑出来 */
    private fun oversizedSlotsJson(): String = buildString {
        append("{\"version\":2,\"")
        append(BACKUP_TIME_SLOTS_KEY)
        append("\":[")
        var i = 0
        while (length < 5_000_000) {
            append("""{"number":${i++ % 40 + 1},"startTime":"08:00","endTime":"08:45"},""")
        }
        append("""{"number":1,"startTime":"08:00","endTime":"08:45"}]}""")
    }

    @Test
    fun 档位册子还是七档不是Boolean() {
        // 本工程没有 kotlin-reflect ⇒ `KClass.sealedSubclasses` 直接抛 KotlinReflectionNotSupportedError
        //（在册教训逐字记在 IcsExportGateTest 第 ④ 层："实测过、记在这儿免得下一个人再撞"）。
        // 这一格因此吃的是**每档各造一枚真实输入、再看跑出来的那枚档名**——比读分支表更硬：
        // 分支表只说"类型在册"，这一格说的是"调用点真拿得到这一档"。
        // 「ShareCodeOutcome 的声明恰好九枚、一枚都不许少」那把静态尺在
        // ShareCodeEntryGateWiringGuardTest 第 ⑥ 层（源码里数声明枚数）。
        val max = ShareCodeSizeGate.MAX_SHARE_CODE_CHARS
        val entryTooLong = ScheduleShareCodec.decodeOutcome("BUAASCH1:" + "A".repeat(max + 1))
        val payloadTooLarge = ScheduleShareCodec.decodeOutcome(deflateToCode(oversizedSlotsJson()))
        val produced = listOf(
            ScheduleShareCodec.decodeOutcome(""),
            entryTooLong,
            payloadTooLarge,
            ScheduleShareCodec.decodeOutcome(deflateToCode("{\"version\":3,${coursesJson(1)}}")),
            ScheduleShareCodec.decodeOutcome(
                deflateToCode("{\"version\":2,${coursesJson(20)},${slotRowsJson("slotTimes", 600)}}"),
            ),
            ScheduleShareCodec.decodeOutcome(
                deflateToCode(
                    "{\"version\":2,\"semester\":null,\"semesters\":[],\"timeSlots\":[]," +
                        "\"courses\":[],\"reminders\":[]}",
                ),
            ),
            ScheduleShareCodec.decodeOutcome(
                deflateToCode("{\"version\":2,${slotRowsJson(BACKUP_TIME_SLOTS_KEY, 600)}}"),
            ),
            ScheduleShareCodec.decodeOutcome(
                deflateToCode(
                    "{\"version\":2,${coursesJson(1)},${slotRowsJson(BACKUP_TIME_SLOTS_KEY, 1)}}",
                ),
            ),
        ).map { it.javaClass.simpleName }.toSet()
        assertEquals(
            "八枚叶档各跑出过一枚 ⇒ 顶层七档俱在（OverSize 那一档的两枚叶子各算一枚）：实到 $produced。" +
                "少一枚就是有一档重新借别人的句子说话（折成 Boolean 就是本卡病灶回来）",
            setOf(
                "EntryTooLong",
                "FromNewerApp",
                "Malformed",
                "NothingRestorable",
                "OverCapacity",
                "PayloadTooLarge",
                "UnreadableTables",
                "Valid",
            ),
            produced,
        )
        assertEquals(
            "OverSize 下面两档（字符数 / 解压字节数）各一枚：两把尺两种下一步，不许合体（实到 " +
                listOf(entryTooLong, payloadTooLarge).map { it.javaClass.simpleName }.toSet() + "）",
            setOf("EntryTooLong", "PayloadTooLarge"),
            listOf(entryTooLong, payloadTooLarge).map { it.javaClass.simpleName }.toSet(),
        )
        val twoThousandOne = deflateToCode("{\"version\":2,${coursesJson(2001)}}")
        assertTrue(
            "体积尺与条数尺不许互相代替：一条 2001 门课的仍旧走 OverCapacity（体积够小）",
            ScheduleShareCodec.decodeOutcome(twoThousandOne) is ShareCodeOutcome.OverCapacity,
        )
        assertFalse(
            "同一等不许被体积那一档抢先说出（那是另一句实话）",
            ScheduleShareCodec.decodeOutcome(twoThousandOne) is ShareCodeOutcome.OverSize,
        )
    }

    @Test
    fun 空表与缺表两种形状各归其档() {
        val explicitEmpty = ScheduleShareCodec.decodeOutcome(
            deflateToCode("{\"version\":2,\"semester\":null,\"semesters\":[],\"timeSlots\":[],\"courses\":[],\"reminders\":[]}"),
        )
        assertTrue("写侧明说全是空表 ⇒ 什么都没带那一等", explicitEmpty is ShareCodeOutcome.NothingRestorable)
        val absent = ScheduleShareCodec.decodeOutcome(deflateToCode("{\"version\":2,\"semester\":null}"))
        val valid = absent as? ShareCodeOutcome.Valid
        assertNotNull("键全缺席（本仓写侧对空表就是这么编的）不许判成什么都没带：实到 ${tierName(absent)}", valid)
        assertEquals(0, valid!!.counts.courseCount)
    }

    @Test
    fun 解压尺排在信封尺之前() {
        // 一条「解压后超上限」的口令，内容里就算带着读不懂的表名，也**先**被体积尺拦下
        //（顺序即语义：不许越过上限去数一条根本没解完的口令）
        val big = buildString {
            append("{\"version\":2,\"slotTimes\":[")
            var i = 0
            while (length < 5_000_000) {
                append("""{"number":${i++ % 40 + 1},"startTime":"08:00","endTime":"08:45"},""")
            }
            append("""{"number":1,"startTime":"08:00","endTime":"08:45"}]}""")
        }
        val outcome = ScheduleShareCodec.decodeOutcome(deflateToCode(big))
        val over = outcome as? ShareCodeOutcome.OverSize.PayloadTooLarge
        assertNotNull("超上限那一等先于信封判据：实到 ${tierName(outcome)}", over)
        assertTrue("档位读数仍是解压尺的那一枚", over!!.decision is ShareCodePayloadGate.OverCap)
    }

    @Test
    fun 容忍空白那条承诺还在() {
        val code = deflateToCode("{\"version\":2,${coursesJson(2)}}")
        val padded = "\n   $code   \t"
        assertEquals(
            "首尾空白照旧放过（KDoc 那句「容忍空白与换行」不是空话）",
            "Valid",
            tierName(ScheduleShareCodec.decodeOutcome(padded)),
        )
        assertEquals(
            "口令中间夹一个空格仍旧整条判死（在册 #360 那一等本卡不收，句子里不许承诺「空格也行」）",
            "Malformed",
            tierName(ScheduleShareCodec.decodeOutcome(code.replaceRange(20, 20, " "))),
        )
    }
}
