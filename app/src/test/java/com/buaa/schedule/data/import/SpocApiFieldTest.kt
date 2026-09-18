package com.buaa.schedule.data.import

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 服务端字段名大小写混用（`ZJDM/CZID/QDSJ` 全大写、`qdxxMap/content` 驼峰），
 * 接口层因此不建 @Serializable DTO，全部经 `pick` / `string` 取值。
 *
 * 这两个函数是签到解析的地基：取错一个键就是「签了但界面说没签」，
 * 所以把口径钉在这里，而不是等真机联调时靠现象反推。
 */
class SpocApiFieldTest {

    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `服务端只给大写形态时按小写也取得到`() {
        // 真实响应里是 ZJDM/CZID 全大写，页面代码却按小写传参 —— 接口层两头都要能接上，
        // 否则签到详情解出来永远是空，表现为「扫了码但什么也没查到」
        val o = obj("""{"ZJDM":"1AA9","CZID":"7F03"}""")
        assertEquals("1AA9", SpocApi.string(o, "zjdm"))
        assertEquals("7F03", SpocApi.string(o, "czid"))
    }

    @Test
    fun `签到详情套在 qdxxMap 里`() {
        // 提交成功的响应把有效载荷塞在 qdxxMap 下一层，直接在外层找 CZID 找不到
        val o = obj("""{"QDSJ":"2026-09-18 10:20:00","qdxxMap":{"CZID":"7F03","ID":"88"}}""")
        assertEquals("2026-09-18 10:20:00", SpocApi.string(o, "qdsj"))
        val detail = SpocApi.pick(o, "qdxxmap") as? JsonObject ?: error("没解出 qdxxMap")
        assertEquals("7F03", SpocApi.string(detail, "CZID"))
        assertEquals("88", SpocApi.string(detail, "id"))
    }

    @Test
    fun `缺失与 JSON null 都算没取到`() {
        val o = obj("""{"rolecode":null,"xh":""}""")
        assertNull(SpocApi.string(o, "rolecode"))
        assertNull(SpocApi.string(o, "yhdm"))
        // 空串是服务端真给了空值，与「没有这个键」区分开：签到提交前要靠它判断是否补登
        assertEquals("", SpocApi.string(o, "xh"))
    }

    @Test
    fun `数字字段按原文取出不挑类型`() {
        val o = obj("""{"CZID":123,"QDCS":"3"}""")
        assertEquals("123", SpocApi.string(o, "czid"))
        assertEquals("3", SpocApi.string(o, "qdcs"))
    }
}
