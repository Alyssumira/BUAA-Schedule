package com.buaa.schedule.data.local

import android.content.Context
import com.buaa.schedule.data.import.TeachingScheduleParser
import com.buaa.schedule.domain.model.SpecialDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/**
 * 「学习日程」标注的本地缓存：**按月缓存接口原始响应**，读取时再解析。
 *
 * 缓存原始响应而不是解析结果，好处是解析器后续修正时旧缓存能直接受益，
 * 不需要做缓存版本迁移。文件不进 Room，因此**不涉及 schema 迁移**。
 */
object SpecialDayCache {

    /**
     * 月龄换算基数。**"多大算该重抓"不在这里判** —— 那条判据（连同 7 天这个口径）
     * 收在纯 JVM 的 `SpecialDayRefreshPolicy.SpecialDayCacheStaleAfterDays` 里，
     * 本文件只报「哪个月有文件、它有多少天大」这一份设备侧事实（T60）。
     */
    private const val MILLIS_PER_DAY = 24 * 60 * 60_000L

    /** 缓存文件名：`yyyy-MM.json`。用 padStart 而不是 String.format("%02d")，
     *  后者受默认 Locale 影响（阿拉伯-埃及等 Locale 会写出非 ASCII 数字，
     *  于是这个月永远命中不到自己写的缓存文件，每次都重新联网）。 */
    private fun fileNameOf(month: YearMonth): String =
        "${month.year}-${month.monthValue.toString().padStart(2, '0')}.json"

    private fun dir(context: Context): File =
        File(context.filesDir, "special_days").apply { mkdirs() }

    private fun file(context: Context, month: YearMonth): File =
        File(dir(context), fileNameOf(month))

    /** 覆盖写入某个月的原始响应 */
    suspend fun put(context: Context, month: YearMonth, rawResponse: String) =
        withContext(Dispatchers.IO) {
            runCatching {
                file(context, month).writeText(rawResponse, Charsets.UTF_8)
            }
            Unit
        }

    /** 读取全部已缓存月份的标注（按日期去重、排序） */
    suspend fun loadAll(context: Context): List<SpecialDay> = withContext(Dispatchers.IO) {
        val files = dir(context).listFiles { f -> f.name.endsWith(".json") } ?: return@withContext emptyList()
        val byDate = LinkedHashMap<LocalDate, SpecialDay>()
        files.sortedBy { it.name }.forEach { f ->
            runCatching { f.readText(Charsets.UTF_8) }.getOrNull()?.let { raw ->
                TeachingScheduleParser.parse(raw).forEach { day -> byDate[day.date] = day }
            }
        }
        byDate.values.sortedBy { it.date }
    }

    /**
     * 已缓存月份 → **月龄**（天，向上取整：不足一天算一天）。没列出的月份就是没有缓存文件。
     *
     * T60 之前这里直接判「哪些月份该重抓」（`staleMonths`），于是 7 天那条判据也落在这份
     * 读文件系统的代码里，JVM 单测完全碰不到它 —— "缓存新鲜 / 过期 / 半新半旧"这几档
     * 从来没有被打过表。现在这里只报事实，判过期在 `SpecialDayRefreshPolicy`。
     * 口径与旧写法等价：满 7 天向上取整成 7，判据那一头 `<= 7` 仍算新鲜，第 8 天才过期。
     *
     * ⚠️ 月龄下限夹 0：mtime 落在未来（设备时钟回摆过、或手工调过时间）时，
     * 负数在判据那一头与"刚抓的"同形，会让这一整月永远不再补抓。
     */
    suspend fun cachedMonthAges(context: Context): Map<YearMonth, Long> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val files = dir(context).listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?: return@withContext emptyMap()
        files.mapNotNull { f ->
            // 文件名本身就是月份（`2026-10.json`）。解析不出来的（半截写入、外部塞进来的东西）
            // 跳过，让那个月按"没有缓存"处理 —— 方向与 loadAll 读到坏文件时一致：补抓一次就好。
            val month = runCatching { YearMonth.parse(f.name.removeSuffix(".json")) }.getOrNull()
                ?: return@mapNotNull null
            val ageMillis = (now - f.lastModified()).coerceAtLeast(0L)
            month to (ageMillis + MILLIS_PER_DAY - 1) / MILLIS_PER_DAY
        }.toMap()
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        dir(context).listFiles()?.forEach { it.delete() }
        Unit
    }
}
