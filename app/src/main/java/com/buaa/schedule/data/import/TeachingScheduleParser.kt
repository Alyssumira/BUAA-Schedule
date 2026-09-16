package com.buaa.schedule.data.import

import com.buaa.schedule.domain.model.SpecialDay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 教务「学习日程」响应解析：提取 上学日（班）/ 节假日（休）标注。
 *
 * 该接口（`api/home/teachingSchedule/list.do?rq=日期&lxdm=student`）返回结构
 * **没有公开文档**（抓包获得，含 JJR=节假日、SKKC=本月有课日）。为避免被
 * 未知/变化的字段形态卡死，这里做**防御式解析**：
 *
 * 1. 递归扫描整棵 JSON 树；
 * 2. 命中名为 JJR（或 holiday/节假日）的子树时，把其中的对象视为候选标注；
 * 3. 候选对象必须带可识别的日期字段（rq/date/day）；
 * 4. 类型/名称字段里含「调休/上班/补班」判为 班，其余判为 休；
 * 5. 同一日期只保留一条（后写覆盖）。
 *
 * 解析失败的日期直接丢弃，不影响其余条目——标注是锦上添花，不能因为
 * 数据奇怪而崩溃或影响课表主流程。
 */
object TeachingScheduleParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val DATE_KEY = Regex("^(rq|date|day|riqi)$", RegexOption.IGNORE_CASE)
    private val TYPE_KEY = Regex("^(lx|type|lxmc|status|kind|leixing)$", RegexOption.IGNORE_CASE)
    private val NOTE_KEY = Regex("^(mc|name|note|title|remark|beizhu|mingcheng|memo)$", RegexOption.IGNORE_CASE)

    /** 日期值前缀：yyyy-MM-dd 或 yyyy/M/d（容忍后续跟时间部分） */
    private val DATE_VALUE = Regex("^\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}")

    private val WORKDAY_WORDS = listOf("调休", "上班", "补班", "work")
    private val HOLIDAY_CONTEXT_KEYS = listOf("jjr", "holiday", "节假日", "vacation")

    fun parse(raw: String): List<SpecialDay> {
        if (raw.isBlank()) return emptyList()
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return emptyList()
        val out = LinkedHashMap<LocalDate, SpecialDay>()
        walk(null, root, out)
        return out.values.sortedBy { it.date }
    }

    private fun walk(key: String?, element: JsonElement, out: MutableMap<LocalDate, SpecialDay>) {
        when (element) {
            is JsonObject -> {
                extractDay(key, element)?.let { day -> out[day.date] = day }
                element.forEach { (k, v) -> walk(k, v, out) }
            }
            is JsonArray -> element.forEach { walk(key, it, out) }
            is JsonPrimitive -> Unit
        }
    }

    private fun extractDay(parentKey: String?, obj: JsonObject): SpecialDay? {
        val inHolidayContext = parentKey != null &&
            HOLIDAY_CONTEXT_KEYS.any { parentKey.contains(it, ignoreCase = true) }

        val dateEntry = obj.entries.firstOrNull { DATE_KEY.matches(it.key) } ?: return null
        val dateText = dateEntry.value.jsonPrimitive.contentOrNull ?: return null
        if (!DATE_VALUE.containsMatchIn(dateText.trim())) return null
        // 教务返回的日期固定是 ASCII 数字，钉死 Locale.US 保证解析一致
        val isoFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd", java.util.Locale.US)
        val slashFmt = DateTimeFormatter.ofPattern("yyyy/M/d", java.util.Locale.US)
        val date = runCatching {
            LocalDate.parse(dateText.trim().take(10), isoFmt)
        }.recoverCatching {
            LocalDate.parse(dateText.trim().take(10), slashFmt)
        }.getOrNull() ?: return null

        val type = obj.entries.firstOrNull { TYPE_KEY.matches(it.key) }?.value?.jsonPrimitive?.contentOrNull
        val note = obj.entries.firstOrNull { NOTE_KEY.matches(it.key) }?.value?.jsonPrimitive?.contentOrNull

        val haystack = listOfNotNull(note, type, if (inHolidayContext) "节假日" else null)
            .joinToString("|")
        val mentionsWork = WORKDAY_WORDS.any { haystack.contains(it, ignoreCase = true) }
        val mentionsHoliday = inHolidayContext ||
            HOLIDAY_WORDS.any { haystack.contains(it, ignoreCase = true) }

        // 既不像节假日也不像调休的条目（比如普通日程）不产出标注
        if (!mentionsWork && !mentionsHoliday) return null

        return SpecialDay(
            date = date,
            isHoliday = !mentionsWork,
            note = note ?: type,
        )
    }

    private val HOLIDAY_WORDS = listOf("假", "休", "节", "holiday", "rest")
}
