package com.buaa.schedule.data.share

import com.buaa.schedule.data.backup.BackupData
import com.buaa.schedule.domain.schedule.RestoreCounts
import com.buaa.schedule.domain.schedule.RestoreEntry
import com.buaa.schedule.domain.schedule.RestoreVolumeDecision
import com.buaa.schedule.domain.schedule.RestoreVolumeGate
import com.buaa.schedule.domain.schedule.RestoreVolumeLimits
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * 解码那一站的**三档**结论（T270③）。
 *
 * 改前这里只有一档 `BackupData?`：形状不对与被条数闸门拒掉折成同一个 null，
 * 于是调用点只能说一句「口令无法识别，请检查是否完整复制」—— 对一条被**判死**的口令，
 * 那句话是假话（它识别得出来，只是不许写）。分成三档之后每一档各有一句实话。
 */
sealed interface ShareCodeOutcome {
    /** 形状/压缩/Base64/JSON 任一环节解不出来（或根本不是口令） */
    data object Malformed : ShareCodeOutcome

    /** 解得出来，但某张表的条数越界 ⇒ 一次都不许落库（判据在 RestoreVolumeGate） */
    data class OverCapacity(val reject: RestoreVolumeDecision.Reject) : ShareCodeOutcome

    /** 解得出来且条数全在上限内。⚠️ 这只说明"可以往下走"，不代表可以直接写库 ——
     * 写不写由 `RestoreVolumeGate.decide` 的 ConfirmFirst / Write 档位决定（口令路要先确认） */
    data class Valid(val data: BackupData) : ShareCodeOutcome
}

/**
 * 课表分享口令：BackupData JSON → deflate 压缩 → URL-safe Base64。
 *
 * 生成形如 `BUAASCH1:eyJ...` 的短文本，方便在聊天工具中直接粘贴传输。
 * 解码端容忍空白与换行，校验失败返回 null 而不是抛异常。
 *
 * ## 本文件只管"形状 + 条数闸门"，零格式改动（T270 的红线）
 *
 * 前缀 `BUAASCH1:`、deflate、URL-safe Base64、`BackupData` 的字段集合**一字未动**：
 * 改编码格式 = 已经发出去的旧码全部失效，那一条要先论证迁移才许碰。
 * 条数闸门从"只数 courses"换成 `RestoreVolumeGate.capacityViolation`（课程/节次/提醒三张表
 * 各自一枚上限），因为改前那一枚正是本卡的病灶：一条只带 1 门课程、却带 3 万行节次的口令
 * 在它眼里完全合法。
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
     * 解码 + 条数闸门，交回**哪一档**（[ShareCodeOutcome]）。
     *
     * 闸门吃的是 `RestoreVolumeGate.capacityViolation` —— 与 VM、与仓储层事务内那一遍
     * 同一枚内核、同一份上限，所以"这一条口令在三处算不算越界"不可能给出三个答案。
     */
    fun decodeOutcome(code: String): ShareCodeOutcome {
        val trimmed = code.trim()
        if (!trimmed.startsWith(PREFIX)) return ShareCodeOutcome.Malformed
        val data = runCatching {
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
            } finally {
                inflater.end()
            }
            json.decodeFromString<BackupData>(out.toByteArray().toString(Charsets.UTF_8))
        }.getOrNull() ?: return ShareCodeOutcome.Malformed
        // ⚠️ 三张表各自数一遍（改前这一枚只数 courses ⇒ 节次表零上限，本卡病灶）
        val violation = RestoreVolumeGate.capacityViolation(
            counts = RestoreCounts(
                courseCount = data.courses.size,
                timeSlotCount = data.timeSlots.size,
                reminderCount = data.reminders.size,
            ),
            limits = RestoreVolumeLimits.Default,
            entry = RestoreEntry.ShareCode,
        )
        return violation?.let { ShareCodeOutcome.OverCapacity(it) } ?: ShareCodeOutcome.Valid(data)
    }

    /**
     * 兼容入口：只要"能不能解出一枚合法的 BackupData"。
     *
     * 越界那一档仍旧交回 null（与改前逐字同形，老调用点与老测试的契约不变），
     * 但**用户看得见的那一句**要用 [decodeOutcome] 才说得出是哪张表越了界。
     */
    fun decode(code: String): BackupData? =
        (decodeOutcome(code) as? ShareCodeOutcome.Valid)?.data
}
