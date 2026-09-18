package com.buaa.schedule.data.import

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class SpocSessionExpiredException(message: String) : IOException(message)

/** [sys/refreshToken] 返回的一对令牌；null 表示续期失败，需要重新登录 */
data class SpocTokens(val token: String, val refreshToken: String)

/**
 * 智学北航（SPOC）接口客户端。
 *
 * **与教务链路的关键差异**：教务那边的鉴权是 byxt 域 Cookie，原生栈直连会被 401
 * 挡回来（见 docs/BUAA_API.md「两条链路」）；SPOC 这边鉴权在**请求头**上
 * （`token: "Inco-"+JWT` + `rolecode`），不依赖 Cookie，因此 HttpURLConnection
 * 直连是成立的 —— 这也正是签到能在无界面的广播进程里发起的前提。
 * 两条链路的结论不能互相外推，取证出处见 docs/BUAA_SPOC_SIGNIN_PLAN.md §0。
 *
 * 响应统一是 `{code:String, msg, content}` 外壳，取值口径抄页面里的
 * `e(t.data.content || t.data)`：`content` 存在就用它，否则用整个响应体。
 * 服务端字段名大小写混用（`ZJDM/CZID/QDSJ` 全大写、`qdxxMap/content` 驼峰），
 * 所以一律经 [pick] 做大小写不敏感取值，不建 @Serializable DTO。
 */
class SpocApi(
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    private val browserUserAgent =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"

    /**
     * 用 refreshToken 续期。请求本身不带任何鉴权头。
     *
     * 失败以 [SpocSessionExpiredException] 抛出，调用方据此决定是弹登录还是提示重试。
     */
    suspend fun refresh(refreshToken: String): Result<SpocTokens> =
        call("sys/refreshToken", buildJsonObject { put("refreshToken", refreshToken) }, null, null)
            .mapCatching { content ->
                val raw = pick(content, "token")?.jsonPrimitive?.contentOrNullSafe()
                    ?: throw SpocSessionExpiredException("续期响应里没有 token")
                val refresh = pick(content, "refreshToken")?.jsonPrimitive?.contentOrNullSafe()
                    ?: throw SpocSessionExpiredException("续期响应里没有 refreshToken")
                // 页面里存的是一律带 "Inco-" 前缀的完整值（setStorageSync("token","Inco-"+n)），
                // 这里保持同样口径，调用方拿到的 token 直接进请求头，不再拼前缀。
                SpocTokens(token = if (raw.startsWith("Inco-")) raw else "Inco-$raw", refreshToken = refresh)
            }

    /** 按二维码里的签到 ID 查签到详情；已签到时响应里带 `QDSJ` */
    suspend fun querySignByQdid(qdid: String, token: String, rolecode: String?): Result<JsonObject> =
        call("spocxssk/queryQdxxByQdid", buildJsonObject { put("qdid", qdid) }, token, rolecode)

    /**
     * 按教师班 ID 查该班的签到活动列表。
     *
     * 暂无调用方：原设想课前提醒拿它静默探测「这节课有没有进行中活动」，但四条签到接口
     * 都以 zjdm / qdid / hdid 为键，而本地课表与 SPOC 课程之间没有映射，探测无从取键。
     * 作废的是键的来源，不是接口本身 —— 日后若建立课表与 SPOC 课程的映射，它是唯一
     * 能列出一堂课全部签到活动的入口，**别当死代码删**（同 [querySignByHdid]）。
     */
    suspend fun querySignListByZjdm(zjdm: String, token: String, rolecode: String?): Result<List<JsonObject>> =
        callList("spocxssk/queryQdhdListByZjdm", buildJsonObject { put("zjdm", zjdm) }, token, rolecode)

    /**
     * 按课堂活动 ID 查签到信息，拿 `ZJDM/CZID`。
     *
     * 暂无调用方，但留着：老师端二维码的字面内容是唯一没取证到的环节，真机若扫出的是
     * `hdid` 而不是 `qdid`，这一步就是解析器第三条分支要接的接口。
     */
    suspend fun querySignByHdid(hdid: String, token: String, rolecode: String?): Result<JsonObject> =
        call("spocxssk/queryQdhdByHdid", buildJsonObject { put("hdid", hdid) }, token, rolecode)

    /** 提交学生签到。成功响应里带 `qdxxMap.ID / qdxxMap.CZID / qdxxMap.QDSJ` */
    suspend fun submitSign(zjdm: String, czid: String, xh: String, token: String, rolecode: String?): Result<JsonObject> =
        call("spocxssk/saveXsqd", buildJsonObject {
            put("zjdm", zjdm)
            put("czid", czid)
            put("xh", xh)
        }, token, rolecode)

    private suspend fun call(
        path: String,
        body: JsonObject,
        token: String?,
        rolecode: String?,
    ): Result<JsonObject> = withContext(Dispatchers.IO) {
        runCatching { execute(path, body, token, rolecode).expectObject() }
    }

    private suspend fun callList(
        path: String,
        body: JsonObject,
        token: String?,
        rolecode: String?,
    ): Result<List<JsonObject>> = withContext(Dispatchers.IO) {
        runCatching { execute(path, body, token, rolecode).expectList() }
    }

    private fun JsonElement.expectObject(): JsonObject = this as? JsonObject
        ?: throw IOException("智学北航响应形态与预期不符：期望对象，实际 ${this::class.simpleName}")

    /**
     * 列表接口的 `content` **直接就是数组** —— 页面里拿到响应后做的是
     * `listData.concat(i)`，没有再取任何键。形态不对就明说，别静默当成空列表：
     * 那会把「接口改版」伪装成「这节课没有签到活动」。
     */
    private fun JsonElement.expectList(): List<JsonObject> {
        val array = this as? JsonArray
            ?: throw IOException("智学北航响应形态与预期不符：期望数组，实际 ${this::class.simpleName}")
        return array.map {
            it as? JsonObject ?: throw IOException("签到活动列表里混进了非对象条目")
        }
    }

    private fun execute(
        path: String,
        body: JsonObject,
        token: String?,
        rolecode: String?,
    ): JsonElement {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(SPOC_API_BASE + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 30_000
                // 页面里的 httpRequest 对每个接口都显式声明 application/json，
                // 服务端也按 JSON 解析，这里不留 form 分支。
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", browserUserAgent)
                setRequestProperty("Referer", SPOC_H5_ENTRY)
                if (token != null) setRequestProperty("token", token)
                if (!rolecode.isNullOrBlank()) setRequestProperty("rolecode", rolecode)
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code == 401 || code == 403) {
                throw SpocSessionExpiredException("智学北航拒绝请求（HTTP $code），登录可能失效")
            }
            if (code !in 200..299) throw IOException("HTTP $code: ${connection.responseMessage}")
            val text = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            // 会话失效时服务端不再返回 JSON，而是把统一身份认证页整页吐回来；
            // 页面那边靠字符串里含「统一身份认证」判定，这里两个特征都认。
            val trimmed = text.trimStart()
            if (trimmed.startsWith("<")) {
                throw SpocSessionExpiredException(
                    if (text.contains("统一身份认证")) "登录已失效（接口返回了统一身份认证页）"
                    else "登录已失效（接口返回了 HTML 页面）",
                )
            }
            val root = json.parseToJsonElement(text).jsonObject
            val bizCode = pick(root, "code")?.jsonPrimitive?.contentOrNullSafe()
            if (bizCode == null) {
                // 普通 IO 失败而不是会话失效：合法 JSON 却没有外壳 code 的多半是网关/
                // 代理插进来的响应体或服务端改版，会话本身可能完好。抛
                // SpocSessionExpiredException 会让上层把 token 整包清掉，
                // 用户就在签到窗口内被迫重登一遍 WebView——那是最不该发生的降级。
                throw IOException(
                    "接口响应里没有 code（不是标准外壳，可能是网关/异常页），msg=${pick(root, "msg")?.jsonPrimitive?.contentOrNullSafe()}",
                )
            }
            if (bizCode == "403") throw SpocSessionExpiredException("token 已过期（code=403）")
            if (bizCode != "200") {
                throw IOException("智学北航返回失败：code=$bizCode, msg=${pick(root, "msg")?.jsonPrimitive?.contentOrNullSafe()}")
            }
            // 与页面同口径：`e(t.data.content || t.data)` —— content 缺失或为 null 时回落到
            // 整个响应体；存在时按原形态返回（对象或数组都可能），由调用方各自的
            // expectObject / expectList 判定。
            val content = pick(root, "content")
            return if (content == null || content is JsonNull) root else content
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        /** 大小写不敏感取字段：服务端字段名混用两种风格 */
        fun pick(obj: JsonObject, name: String): JsonElement? {
            obj[name]?.let { return it }
            val lower = name.lowercase()
            return obj.entries.firstOrNull { it.key.lowercase() == lower }?.value
        }

        /** 取一个字符串字段；缺失或 JSON null 都返回 null */
        fun string(obj: JsonObject, name: String): String? =
            (pick(obj, name) as? JsonPrimitive)?.let { if (it is JsonNull) null else it.content }
    }
}

private fun JsonPrimitive.contentOrNullSafe(): String? = if (this is JsonNull) null else content
