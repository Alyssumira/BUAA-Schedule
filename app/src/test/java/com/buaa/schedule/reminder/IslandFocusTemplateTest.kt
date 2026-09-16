package com.buaa.schedule.reminder

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 焦点通知载荷的**结构**回归（R6 超级岛）。
 *
 * 岛的内容改不了不奇怪，改错了才奇怪：字段名是小米定的，拼错一个字母就是整条 param
 * 被 SystemUI 静默丢掉，而且丢了没有任何回调。所以这里把三件最容易回退的事钉住：
 * 1. 外面必须套 `param_v2` 信封，`business`/`scene` 不能被改名；
 * 2. `timerInfo` 走的是「起始绝对时刻 + 一直走秒」，把剩余毫秒填进去看似合理、实际会在
 *    下发那一刻被冻结（HyperIsland 只在录屏暂停时用另一种取值）；
 * 3. `miui.focus.pic_ticker` 这类图片键要与 pics Bundle 的键一致（Bundle 本身需要
 *    Android 运行时，只能在真机上核对，这里保证 param 里写的就是那个键名）。
 */
class IslandFocusTemplateTest {

    private val json = Json { prettyPrint = false }

    private fun param(
        startMillis: Long,
        sectionText: String = "第 1-2 节",
        nowMillis: Long = NOW,
    ): JsonObject {
        val raw = IslandFocusTemplate.paramJson(
            packageName = "com.buaa.schedule",
            notificationId = 1234,
            courseName = "高等数学",
            sectionText = sectionText,
            startMillis = startMillis,
            nowMillis = nowMillis,
        )
        val envelope = json.parseToJsonElement(raw).jsonObject
        val outer = envelope["param_v2"]
        checkNotNull(outer) { "缺少 param_v2 信封：$raw" }
        return outer.jsonObject
    }

    @Test
    fun wrapsPayloadInParamV2EnvelopeWithRegisteredBusiness() {
        val p = param(startMillis = START)
        assertEquals("schedule", p.getValue("business").jsonPrimitive.content)
        assertEquals("class_progress", p.getValue("scene").jsonPrimitive.content)
        // notifyId 是 SystemUI 用来配对通知的键：包名 + 通知 id，与 notify() 用的 id 必须同源
        assertEquals("com.buaa.schedule1234", p.getValue("notifyId").jsonPrimitive.content)
        assertEquals("高等数学", p.getValue("content").jsonPrimitive.content)
        assertEquals("第 1-2 节", p.getValue("ticker").jsonPrimitive.content)
    }

    @Test
    fun tickerFallsBackToCourseNameWhenSectionIsBlank() {
        assertEquals(
            "高等数学",
            param(startMillis = START, sectionText = "   ").getValue("ticker").jsonPrimitive.content,
        )
    }

    @Test
    fun timerCountsUpFromCourseStart() {
        val island = param(startMillis = START)
            .getValue("param_island").jsonObject
        val digits = island.getValue("bigIslandArea").jsonObject
            .getValue("sameWidthDigitInfo").jsonObject
        val timer = digits.getValue("timerInfo").jsonObject

        // timerWhen 是**起始**时刻，不是"距下课还剩多久"：后者会让岛上的秒针停在下发那一瞬
        assertEquals(START, timer.getValue("timerWhen").jsonPrimitive.long)
        assertEquals(NOW, timer.getValue("timerSystemCurrent").jsonPrimitive.long)
        assertEquals(1, timer.getValue("timerType").jsonPrimitive.int)
    }

    @Test
    fun missingCourseStartStillShowsACountingTimer() {
        // 广播丢了 start 时宁可从当下开始走，也不要 0（1970 年）把岛撑成一个荒谬的天数
        val timer = param(startMillis = 0L, nowMillis = NOW)
            .getValue("param_island").jsonObject
            .getValue("bigIslandArea").jsonObject
            .getValue("sameWidthDigitInfo").jsonObject
            .getValue("timerInfo").jsonObject
        assertEquals(NOW, timer.getValue("timerWhen").jsonPrimitive.long)
    }

    @Test
    fun pictureKeysMatchTheBundleSystemUiLooksUp() {
        val p = param(startMillis = START)
        // param 里只写键名，真正的 Icon 在 miui.focus.pics Bundle 里按同名键取
        assertEquals("miui.focus.pic_ticker", p.getValue("tickerPic").jsonPrimitive.content)
        assertEquals(
            "miui.focus.pic_ticker",
            p.getValue("tickerPicDark").jsonPrimitive.content,
        )
        val left = p.getValue("param_island").jsonObject
            .getValue("bigIslandArea").jsonObject
            .getValue("imageTextInfoLeft").jsonObject
        assertEquals(
            "miui.focus.pic_ticker",
            left.getValue("picInfo").jsonObject.getValue("pic").jsonPrimitive.content,
        )
        assertTrue(
            "smallIslandArea 必须也带 picInfo，否则小岛无内容可渲染",
            p.getValue("param_island").jsonObject.containsKey("smallIslandArea"),
        )
    }

    @Test
    fun fixedProtocolFieldsAreNotDropped() {
        // encodeDefaults=false 会正好吃掉这几个默认值字段 —— 样例里它们每次都写
        val p = param(startMillis = START)
        assertEquals(1, p.getValue("protocol").jsonPrimitive.int)
        assertTrue(p.getValue("updatable").jsonPrimitive.content.toBoolean())
        assertEquals(1, p.getValue("param_island").jsonObject.getValue("islandPriority").jsonPrimitive.int)
    }

    private companion object {
        const val START = 1_760_000_000_000L
        const val NOW = 1_760_003_600_000L
    }
}
