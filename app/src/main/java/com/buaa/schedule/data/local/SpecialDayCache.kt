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

    /** 缓存超过这么多天就重新抓：教务的调休安排常在月中/次月才公布或改动 */
    private const val STALE_AFTER_DAYS = 7L
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
     * 需要补抓的月份：没有缓存文件，**或**文件已超过 [STALE_AFTER_DAYS] 天。
     *
     * 此前只按文件名判定「这个月有没有缓存」，于是本月第一次抓取成功后就永久跳过 ——
     * 而教务的调休安排往往临近才公布或修改，迟发的标注再也进不来（R5 F-44）。
     */
    suspend fun staleMonths(context: Context, wanted: Collection<YearMonth>): Set<YearMonth> =
        withContext(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - STALE_AFTER_DAYS * MILLIS_PER_DAY
            wanted.filter { month ->
                val file = file(context, month)
                !file.exists() || file.lastModified() < cutoff
            }.toSet()
        }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        dir(context).listFiles()?.forEach { it.delete() }
        Unit
    }
}
