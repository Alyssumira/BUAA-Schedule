package com.buaa.schedule.data.share

import com.buaa.schedule.data.backup.BACKUP_COURSES_KEY
import com.buaa.schedule.data.backup.BACKUP_ENVELOPE_KEYS
import com.buaa.schedule.data.backup.BACKUP_FORMAT_VERSION
import com.buaa.schedule.data.backup.BACKUP_REMINDERS_KEY
import com.buaa.schedule.data.backup.BACKUP_SEMESTERS_KEY
import com.buaa.schedule.data.backup.BACKUP_TIME_SLOTS_KEY
import com.buaa.schedule.data.backup.BACKUP_VERSION_KEY
import com.buaa.schedule.data.backup.BackupData
import com.buaa.schedule.domain.schedule.RestoreCounts
import com.buaa.schedule.domain.schedule.RestoreEntry
import com.buaa.schedule.domain.schedule.RestoreVolumeDecision
import com.buaa.schedule.domain.schedule.RestoreVolumeGate
import com.buaa.schedule.domain.schedule.RestoreVolumeLimits
import com.buaa.schedule.domain.schedule.ShareCodeEnvelopeDecision
import com.buaa.schedule.domain.schedule.ShareCodeEnvelopeFacts
import com.buaa.schedule.domain.schedule.ShareCodeEnvelopeReview
import com.buaa.schedule.domain.schedule.ShareCodeEntryGate
import com.buaa.schedule.domain.schedule.ShareCodeGatedTable
import com.buaa.schedule.domain.schedule.ShareCodeGatedTableRow
import com.buaa.schedule.domain.schedule.ShareCodePayloadGate
import com.buaa.schedule.domain.schedule.ShareCodeSizeGate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * 解码那一站的结论（T270③ 三档 + 台账 #530 补的三档）。
 *
 * 改前这里只有一档 `BackupData?`：形状不对与被条数闸门拒掉折成同一个 null，
 * 于是调用点只能说一句「口令无法识别，请检查是否完整复制」—— 对一条被**判死**的口令，
 * 那句话是假话（它识别得出来，只是不许写）。分成三档之后每一档各有一句实话。
 *
 * #530 又补了三档，因为剩下的三句实话仍然说不出口：
 * - [OverSize]：太大，且两枚尺各有其档 —— 贴进来的字符数超上限（[ShareCodeOutcome.OverSize.EntryTooLong]）
 *   与解压后字节数超上限（[ShareCodeOutcome.OverSize.PayloadTooLarge]）要劝的是两种下一步；
 *   后者**不许**劝人重新复制（那条口令往往是完整的，只是内容超上限 —— 改前正是这一等被折进
 *   [Malformed]，于是用户被劝去重抄一条本来就完整的码）。
 * - [FromNewerApp]：口令自己报了更高的格式版本（改前这一问只在键存在时响，缺键就被默认值填成"当前版本"）。
 * - [UnreadableTables] / [NothingRestorable]：口令里有本应用读不懂的表，或明说自己一张表都没带
 *   （改前 `ignoreUnknownKeys` + 全字段默认值先把三张表清成 0，条数闸门与"要不要先问一句"吃的都是这枚 0）。
 *
 * ⚠️ 档位与句子同源：每一档在 domain 里都有自己那句实话（`ShareCodeSizeGate.kt` /
 * `ShareCodeEnvelopeReview.kt` 末尾那几枚函数），ui 只念不拼。
 */
sealed interface ShareCodeOutcome {

    /**
     * 形状/压缩/Base64/JSON 任一环节解不出来（或根本不是口令）。
     *
     * ⚠️ 这一档**只**管"解不出来"。太大 / 来自更新版本 / 读不懂的表都不许折进这里（#530 发现 2）。
     * [failure] 是解码那一站咽下去的那枚 Throwable 的读数（#530 发现 4：改前
     * `runCatching{}.getOrNull()` 连类型都不留、零留痕，解码器自己的 bug 与 OOM 都在这枚兜底里消失）；
     * 前缀都不对那一等是 null —— 那种输入压根不是口令，没有异常可记。
     */
    data class Malformed(val failure: ShareCodeFailure? = null) : ShareCodeOutcome

    /** 太大；两档都保证一条都没落库，[EntryTooLong] 那一档连解压缓冲都没分配 */
    sealed interface OverSize : ShareCodeOutcome {

        /** 贴进来的文本本身就不可能是条正常口令 ⇒ 闸门在任何 Base64 分配**之前**就拦下 */
        data class EntryTooLong(val decision: ShareCodeEntryGate.TooManyChars) : OverSize

        /** 口令未必被剪贴板截断，是它解压后的内容超上限 ⇒ 句子不许劝人"重新复制" */
        data class PayloadTooLarge(val decision: ShareCodePayloadGate.OverCap) : OverSize
    }

    /** 口令报的格式版本高于本应用读得懂的那一枚 */
    data class FromNewerApp(val decision: ShareCodeEnvelopeDecision.NewerFormat) : ShareCodeOutcome

    /** 口令里有本应用读不懂的表（改名/换了形状）⇒ 条数闸门拿不到真值，一次都不许落库 */
    data class UnreadableTables(val decision: ShareCodeEnvelopeDecision.UnreadableTables) : ShareCodeOutcome

    /** 口令明说自己一张表都没带（空课表，或所有表都是写出来的空列表） */
    data class NothingRestorable(val decision: ShareCodeEnvelopeDecision.NothingNamed) : ShareCodeOutcome

    /** 解得出来，但某张表的条数越界 ⇒ 一次都不许落库（判据在 RestoreVolumeGate） */
    data class OverCapacity(val reject: RestoreVolumeDecision.Reject) : ShareCodeOutcome

    /**
     * 解得出来且条数全在上限内。⚠️ 这只说明"可以往下走"，不代表可以直接写库 ——
     * 写不写由 `RestoreVolumeGate.decide` 的 ConfirmFirst / Write 档位决定（口令路要先确认）。
     *
     * [counts] 是**原始 JSON** 上数出来的三张表条数（#530 发现 3：调用点不许再从模型的默认值数一遍，
     * 否则"没预览过就先问一句"那一档又会吃到被清零的 0）。
     */
    data class Valid(val data: BackupData, val counts: RestoreCounts) : ShareCodeOutcome
}

/**
 * 解码那一站咽下去的那枚 Throwable 的**读数**（#530 发现 4）。
 *
 * 只带类型名与 message 两枚文本：codec 不许把异常对象本身递到 ui（那等于把写库的手术刀递给文案层），
 * 也不许在 ui 的句子里念异常原文（同页在册的口径：解析器的英文原文不是用户文案，见台账 #369 一族）。
 * 读数只干一件事 —— 让调用点能在写文案**之前**把它留痕进 logcat。
 *
 * `type` 那一枚在绝大多数情况下就是 Throwable 的类名（`java.util.zip.DataFormatException` 那种），
 * 另有两格**没有异常对象可记**、由本文件自己命名（`decodeOutcome` 里逐处点着名）：
 * `incomplete-deflate-stream` = 压缩流在体积上限之前就自己停了（多半没复制完整），
 * `envelope-not-an-object` = 顶层解出来不是对象（压根不是那一份信封）。
 * 这两格占的是「无法识别」那句话里**唯一真实**的那一半，与"太大""读不懂"那两档互不代替。
 */
data class ShareCodeFailure(val type: String, val message: String?)

/**
 * 课表分享口令：BackupData JSON → deflate 压缩 → URL-safe Base64。
 *
 * 生成形如 `BUAASCH1:eyJ...` 的短文本，方便在聊天工具中直接粘贴传输。
 * 解码端容忍空白与换行，校验失败交回**档位**而不是抛异常。
 *
 * ## 本文件只管"体积 + 形状 + 条数闸门"，零格式改动（T270 的红线，#530 也没动它）
 *
 * 前缀 `BUAASCH1:`、deflate、URL-safe Base64、`BackupData` 的字段集合**一字未动**：
 * 改编码格式 = 已经发出去的旧码全部失效，那一条要先论证迁移才许碰。
 * 条数闸门从"只数 courses"换成 `RestoreVolumeGate.capacityViolation`（课程/节次/提醒三张表
 * 各自一枚上限），因为改前那一枚正是本卡的病灶：一条只带 1 门课程、却带 3 万行节次的口令
 * 在它眼里完全合法。
 *
 * ## #530 加的三道判据，以及它们的**次序**（次序即语义，一格都不许换）
 *
 * 1. [ShareCodeSizeGate.reviewEntry] —— **字符数闸门排在 `trim()` 与 `Base64.getUrlDecoder()` 之前**。
 *    改前这里是全仓第四枚用户可控入口，而 8MB 那把尺（`ui/ImportLimits.kt` 的 `MAX_IMPORT_BYTES`）
 *    只吃三路**文件**入口（它的载体是 `InputStream.readTextLimited`，口令这一路根本没有流），
 *    于是那条路在任何长度判据之前就把整串解成了 3/4 长度的 ByteArray。
 * 2. [ShareCodeSizeGate.reviewPayload] —— 循环退出后**先问"流结束了没有"**，再决定要不要把文本交给
 *    JSON 解析器：改前是直接把 4MB 处的截断前缀喂下去，失败之后折进「请检查是否完整复制」那句假话。
 * 3. [ShareCodeEnvelopeReview] —— 条数从**原始 JSON** 上数（键名清单 + 各数组行数都由这一枚文件算好递进
 *    纯判据），再交给 `capacityViolation`；模型的默认值只用来拿业务字段，不再用来数条数。
 *    ⚠️ 判据范围只看**顶层**键名：`BackupCourse.credit` / `BackupReminder.fragmentWeeks` 那种嵌套的
 *    新增可选字段仍按 `ignoreUnknownKeys` 的老契约放过 —— 那是 `BackupCreditCompatTest` /
 *    `BackupReminderFragmentKeyCompatTest` 钉着的承诺，不在本卡授权范围内。
 *
 * ## 为什么本文件一行 `Log` 都没有（改后仍旧没有）
 *
 * `app/build.gradle.kts` 没开 `unitTests.returnDefaultValues`，而这一族的行为测试**全在 JVM 上跑**
 * （`ScheduleShareCodecTest` 与三枚 backup 兼容测试直接调 `decode`）⇒ 在这里挂 `android.util.Log`
 * 会让那些测试当场红（"Method i in android.util.Log not mocked"）。留痕因此落在**调用点**
 * （`ui/ScheduleViewModel.importShareCode`，新 tag「ShareCodeGuard」，Info 级，排在写文案之前），
 * codec 负责把咽下去的 Throwable 变成**能递出去的读数**（[ShareCodeFailure]）——
 * 两件事合起来才是同页那句"留痕是事后唯一能查的账"。
 */
object ScheduleShareCodec {

    private const val PREFIX = "BUAASCH1:"
    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_DECOMPRESSED_BYTES = 4 * 1024 * 1024

    fun encode(backup: BackupData): String {
        val raw = json.encodeToString(backup).toByteArray(Charsets.UTF_8)
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
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    /**
     * 解码 + 体积闸门 + 信封判据 + 条数闸门，交回**哪一档**（[ShareCodeOutcome]）。
     *
     * 条数闸门吃的是 `RestoreVolumeGate.capacityViolation` —— 与 VM、与仓储层事务内那一遍
     * 同一枚内核、同一份上限，所以"这一条口令在三处算不算越界"不可能给出三个答案。
     */
    fun decodeOutcome(code: String): ShareCodeOutcome {
        // ① 入口字符数闸门：必须在 trim()/Base64 之前（判据本体在 ShareCodeSizeGate，纯 JVM）
        when (val entry = ShareCodeSizeGate.reviewEntry(code.length, ShareCodeSizeGate.MAX_SHARE_CODE_CHARS)) {
            is ShareCodeEntryGate.Proceed -> Unit
            is ShareCodeEntryGate.TooManyChars -> return ShareCodeOutcome.OverSize.EntryTooLong(entry)
        }
        val trimmed = code.trim()
        if (!trimmed.startsWith(PREFIX)) return ShareCodeOutcome.Malformed(null)
        var decompressedBytes = 0
        var streamFinished = false
        val text = runCatching {
            val compressed = Base64.getUrlDecoder().decode(trimmed.removePrefix(PREFIX))
            val inflater = Inflater()
            inflater.setInput(compressed)
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            try {
                while (!inflater.finished() && out.size() < MAX_DECOMPRESSED_BYTES) {
                    val n = inflater.inflate(chunk)
                    if (n == 0) break
                    out.write(chunk, 0, n)
                }
                // ⚠️ finished() 必须在 end() **之前**读（改前这一问根本没问 ⇒ 分不清"被体积拦下"与"流自己断了"）
                streamFinished = inflater.finished()
            } finally {
                inflater.end()
            }
            decompressedBytes = out.size()
            out.toByteArray().toString(Charsets.UTF_8)
        }.fold(
            onSuccess = { it },
            onFailure = { error ->
                // #530 发现 4：这里仍旧不往下解（异常不许被当成合法输入），但 Throwable 的读数跟着结论
                // 一起交出去，由调用点留痕 ⇒ 解码器自己的 bug / OOM 从此查得到
                return ShareCodeOutcome.Malformed(ShareCodeFailure(error::class.java.name, error.message))
            },
        )
        // ② 解压体积闸门：**超限的截断前缀一次都不许**交给 JSON 解析器
        when (val payload = ShareCodeSizeGate.reviewPayload(decompressedBytes, MAX_DECOMPRESSED_BYTES, streamFinished)) {
            is ShareCodePayloadGate.Proceed -> Unit
            is ShareCodePayloadGate.OverCap -> return ShareCodeOutcome.OverSize.PayloadTooLarge(payload)
            // 流在上限之前就自己停了 —— 这一档才是"多半没复制完整"，交给 Malformed 那一句
            is ShareCodePayloadGate.StreamStoppedEarly -> return ShareCodeOutcome.Malformed(
                ShareCodeFailure(
                    "incomplete-deflate-stream",
                    "解压 ${payload.decompressedBytes} 字节处停止，上限 ${payload.capBytes} 字节",
                ),
            )
        }
        // ③ 信封判据：条数从原始 JSON 上数（模型的默认值不许再替闸门数数）
        val root = runCatching { json.parseToJsonElement(text) }.fold(
            onSuccess = { it },
            onFailure = { error ->
                return ShareCodeOutcome.Malformed(ShareCodeFailure(error::class.java.name, error.message))
            },
        )
        if (root !is JsonObject) {
            return ShareCodeOutcome.Malformed(
                ShareCodeFailure("envelope-not-an-object", "顶层是 ${root::class.java.simpleName}"),
            )
        }
        val review = ShareCodeEnvelopeReview.review(envelopeFactsOf(root))
        val counts = when (review) {
            is ShareCodeEnvelopeDecision.Understood -> review.counts
            is ShareCodeEnvelopeDecision.NewerFormat -> return ShareCodeOutcome.FromNewerApp(review)
            is ShareCodeEnvelopeDecision.UnreadableTables -> return ShareCodeOutcome.UnreadableTables(review)
            is ShareCodeEnvelopeDecision.NothingNamed -> return ShareCodeOutcome.NothingRestorable(review)
        }
        val data = runCatching {
            json.decodeFromString<BackupData>(text)
        }.fold(
            onSuccess = { it },
            onFailure = { error ->
                return ShareCodeOutcome.Malformed(ShareCodeFailure(error::class.java.name, error.message))
            },
        )
        // ⚠️ 三张表各自数一遍，且数的是**原始 JSON** 的行数（改前这一枚数的是模型的默认值）
        val violation = RestoreVolumeGate.capacityViolation(
            counts = counts,
            limits = RestoreVolumeLimits.Default,
            entry = RestoreEntry.ShareCode,
        )
        return violation?.let { ShareCodeOutcome.OverCapacity(it) } ?: ShareCodeOutcome.Valid(data, counts)
    }

    /**
     * 兼容入口：只要"能不能解出一枚合法的 BackupData"。
     *
     * 越界与上面那几档仍旧交回 null（与改前逐字同形，老调用点与老测试的契约不变），
     * 但**用户看得见的那一句**要用 [decodeOutcome] 才说得出是被哪把尺、哪张表拦住的。
     */
    fun decode(code: String): BackupData? =
        (decodeOutcome(code) as? ShareCodeOutcome.Valid)?.data

    /**
     * 把原始 JSON 的顶层读数换成 [ShareCodeEnvelopeFacts]：**只测不判**。
     *
     * 判断全在 `ShareCodeEnvelopeReview`（纯 JVM）；这一枚函数只负责数键名与数组行数，
     * 一条 `if` 都不带档位含义 —— 否则判据就分成两把尺。
     * 键名册子的真源是 [BACKUP_ENVELOPE_KEYS]（由 `BackupData` 的序列化描述符给出），这里不重写。
     */
    private fun envelopeFactsOf(root: JsonObject): ShareCodeEnvelopeFacts {
        val gated = listOf(
            ShareCodeGatedTable.Courses to BACKUP_COURSES_KEY,
            ShareCodeGatedTable.TimeSlots to BACKUP_TIME_SLOTS_KEY,
            ShareCodeGatedTable.Reminders to BACKUP_REMINDERS_KEY,
        ).map { (slot, key) ->
            ShareCodeGatedTableRow(
                slot = slot,
                wireKey = key,
                present = root.containsKey(key),
                rows = (root[key] as? JsonArray)?.size,
            )
        }
        val understood = BACKUP_ENVELOPE_KEYS.toSet()
        val unreadableArrays = mutableListOf<Pair<String, Int>>()
        val unreadableOther = mutableListOf<String>()
        root.forEach { (key, value) ->
            if (key !in understood) {
                val array = value as? JsonArray
                if (array != null) unreadableArrays += key to array.size else unreadableOther += key
            }
        }
        val versionElement = root[BACKUP_VERSION_KEY] as? JsonPrimitive
        return ShareCodeEnvelopeFacts(
            gatedRows = gated,
            knownOtherArrayRows = listOf(BACKUP_SEMESTERS_KEY).mapNotNull { key ->
                (root[key] as? JsonArray)?.let { key to it.size }
            },
            unreadableArrayRows = unreadableArrays,
            unreadableOtherKeys = unreadableOther,
            versionKeyPresent = root.containsKey(BACKUP_VERSION_KEY),
            versionCode = versionElement?.intOrNull,
            currentFormatVersion = BACKUP_FORMAT_VERSION,
        )
    }
}
