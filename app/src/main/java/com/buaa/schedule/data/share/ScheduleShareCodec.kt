package com.buaa.schedule.data.share

import com.buaa.schedule.data.backup.BackupData
import com.buaa.schedule.domain.schedule.CourseConstraints
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * 课表分享口令：BackupData JSON → deflate 压缩 → URL-safe Base64。
 *
 * 生成形如 `BUAASCH1:eyJ...` 的短文本，方便在聊天工具中直接粘贴传输。
 * 解码端容忍空白与换行，校验失败返回 null 而不是抛异常。
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

    fun decode(code: String): BackupData? {
        val trimmed = code.trim()
        if (!trimmed.startsWith(PREFIX)) return null
        return runCatching {
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
        }.getOrNull()?.takeIf { it.courses.size <= CourseConstraints.MAX_COURSE_COUNT }
    }
}
