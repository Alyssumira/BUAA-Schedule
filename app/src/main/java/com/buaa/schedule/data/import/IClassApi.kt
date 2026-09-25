package com.buaa.schedule.data.import

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset

/**
 * 服务端业务性拒绝（外壳 `STATUS=1`）。
 *
 * [message] 是 ERRMSG 的**逐字原文**：这一族的 ERRMSG 全是厂商自己的中文文案，
 * 我们一条都没有（也没有取证到"已签过""码过期"这样的分类），所以整条链上
 * 一个字都不许加工 —— 包一层"签到失败"就把唯一那条真信息吃掉了。
 */
class IClassRejectedException(message: String) : IOException(message)

/** 响应读不出结论：空体、非 JSON、没有 STATUS、HTTP 非 2xx —— 一律按失败处理 */
class IClassUnexpectedResponseException(message: String) : IOException(message)

/** 登录被拒（口令错、账号不存在、验证码网关不放行……服务端给什么就说什么） */
class IClassLoginFailedException(message: String) : IOException(message)

/**
 * 一次 HTTP 往返的**原始**结果：状态码 + `Content-Type` 头 + 响应体字节。
 *
 * 刻意不带任何解析痕迹：外壳判定要能在拿到字节之前就被测到（假响应表），
 * 而 charset 这件事的真相只在响应头里（见 [IClassApi.charsetFor]）。
 */
class IClassRawResponse(
    val statusCode: Int,
    val contentType: String?,
    val body: ByteArray,
)

/**
 * 可注入的传输层 —— 这一族唯一碰网络的地方。
 *
 * 存在的理由：签到要在没有界面的进程里发请求（课前提醒那颗按钮点进来也是），所以必须是纯 HTTP；
 * 但与"外壳判定"隔开后，判定这一层变成了**表驱动可测的纯函数**（假响应对着
 * [IClassApi.classifySign] 喂，一次真请求都不发）。真机上这条链的失败形状
 * 我们只实测过 `参数错误!` 与空体两种，其余都得靠假响应把判据钉住。
 */
fun interface IClassTransport {

    /**
     * @param method `GET`（签到：一次拼接出来的 URL，没有请求体）或 `POST`（登录：form-urlencoded）
     * @param formBody 只在 POST 时有值，已按 UTF-8 编码好
     * @return 原始响应；HTTP 层失败（连不上、非 2xx 的取不到体）时抛 IOException
     */
    fun execute(url: String, method: String, formBody: String?): IClassRawResponse
}

/**
 * 真实传输：HttpURLConnection。
 *
 * 三条"没有"是契约的一部分，这里逐字守着：**不发** Cookie、**不发**鉴权头、**不发**请求体
 * （签到那一次 GET 的全部信息就在那条 URL 上）。UA 不属于鉴权材料，照厂商客户端发。
 */
object HttpURLConnectionIClassTransport : IClassTransport {

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"

    override fun execute(url: String, method: String, formBody: String?): IClassRawResponse {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                // 厂商客户端不设 Accept-Encoding：这里也不设，留着让服务端自己决定
                if (formBody != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
            }
            if (formBody != null) {
                connection.outputStream.use { it.write(formBody.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            // 4xx/5xx 也要把体读回来：这一族的失败信息就在 ERRMSG 里，丢了就只剩"HTTP 500"
            val stream = if (code >= HttpURLConnection.HTTP_BAD_REQUEST) {
                connection.errorStream ?: connection.inputStream
            } else {
                connection.inputStream
            }
            val body = runCatching { stream?.use { it.readBytes() } }.getOrNull() ?: ByteArray(0)
            return IClassRawResponse(code, connection.contentType, body)
        } finally {
            connection?.disconnect()
        }
    }

    private const val CONNECT_TIMEOUT_MILLIS = 15_000
    private const val READ_TIMEOUT_MILLIS = 30_000
}

/**
 * 北航 iClass（竞业达「轻新课堂」）接口客户端。
 *
 * 三条本族自己的契约，逐条都是实测/取证结论，别外推：
 * 1. **鉴权材料只有一个 `id`**（登录响应里的 `result.id`），它以查询参数的形式进签到 URL；
 *    没有 token 头、没有 Cookie、没有 refreshToken（所以本类没有续期那一步）；
 * 2. **签到是拼接出来的 GET**，不是"字段进 DTO 出"：所以 [signIn] 收的是扫码原文，
 *    中间任何一次"解析再重组"都会把它变成另一条请求（见 [IClassSignUrl]）；
 * 3. **外壳与门户页那一套不是一回事**：`/app` 打头的那一族回 `{STATUS, ERRCODE, ERRMSG}`，
 *    门户页面（`/front` 打头）回 `{code, msg}`。本类只走 `/app` 那一族，判定见 [classifyEnvelope]。
 *
 * 这三条 T84 落地时是拿智学北航（SPOC）那一族**对照**着写的（token 头 / POST JSON / `{code,msg}` 外壳
 * 是它那边的形状）。T85 把那条链整条拆掉之后，对照物在本仓里已经不存在了，
 * 但三条一个字都没改 —— 它们钉的是本类自己怎么写，不是"跟谁不一样"。
 *
 * ⚠️ `ERRCODE` **恒为 `"100"`**，全类没有任何一处读它（拿它分支就是拿常量分支）。
 */
class IClassApi(
    private val transport: IClassTransport = HttpURLConnectionIClassTransport,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    /**
     * 提交一次课堂签到。
     *
     * @param rawUrl 扫码原文（[ScanTarget.IClass.rawUrl]），中间只过 [IClassSignUrl.forSubmission]
     * @return 成功只带一个 `Unit`：这一族的成功回执里没有任何我们读得懂的附加信息
     *         （`stuSignStatus` 之外没有取到证的字段，不猜、也不显示）
     */
    suspend fun signIn(rawUrl: String, userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            classifySign(transport.execute(IClassSignUrl.forSubmission(rawUrl, userId), "GET", null)).getOrThrow()
        }
    }

    /**
     * 账号口令登录，取回签到要用的 `id`。
     *
     * 口令只在**这一次请求**里存在：成功之后本类与 [IClassSession] 都只留 `result.id`，
     * 绝不落盘（老客户端把 phone+password 明文写进 SharedPreferences 再重登，那是不该照抄的做法）。
     */
    suspend fun login(phone: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = phone.trim()
            if (trimmed.isEmpty() || password.isEmpty()) {
                throw IClassLoginFailedException("手机号与口令都要填")
            }
            val form = listOf(
                "phone" to trimmed,
                "password" to password,
                "verificationType" to VERIFICATION_TYPE,
                "verificationUrl" to VERIFICATION_URL,
                "userLevel" to USER_LEVEL_STUDENT,
            ).joinToString("&") { (key, value) ->
                URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
            }
            classifyLogin(transport.execute(LOGIN_URL, "POST", form)).getOrThrow()
        }
    }

    /**
     * 外壳判定的第一层：`STATUS` 走**整数 switch**，成功只表示"框架层面受理了"。
     *
     * - `1` → [Envelope.Rejected]：业务错误，ERRMSG 是给人看的那一句
     * - `2` → [Envelope.Framework]：框架码 100005（实践里是网关/会话那一层的话，不是业务回执）
     * - 其余（实测就是 `0`）→ [Envelope.Accepted]
     * - 空体（0 字节或只剩 `\r\n`）→ [Envelope.EmptyBody]，**判失败**
     *
     * 空体那一档不是洁癖：实测给任一数字型属性喂非数字值，服务端就回 2 字节 `\r\n` ——
     * 那是"处理到一半死了"的形状，当成功就是把失败签成"签到完成"。
     * 没有 STATUS / 不是 JSON / 不是对象同样落 [Envelope.Unrecognized]，也判失败。
     */
    sealed interface Envelope {
        data class Accepted(val root: JsonObject, val result: JsonObject?) : Envelope
        data class Rejected(val serverMessage: String?) : Envelope
        data class Framework(val serverMessage: String?) : Envelope
        data object EmptyBody : Envelope
        data class Unrecognized(val detail: String) : Envelope
    }

    /**
     * 表驱动可测的外壳判定：字节 → 文本（按 charset 规则）→ JSON → [Envelope]。
     *
     * 先判空体再解 JSON，顺序反了会把空体报成"不是 JSON"，
     * 用户读到的是我们造的那句，而不是"服务端什么都没回"。
     */
    fun classifyEnvelope(response: IClassRawResponse): Envelope {
        if (response.statusCode !in 200..299) {
            // 状态码先说话，但如果体里真有外壳，下面一层还能把 ERRMSG 捞出来，所以不在此早退
            val parsed = parseEnvelope(response)
            if (parsed is Envelope.Rejected || parsed is Envelope.Framework) return parsed
            return Envelope.Unrecognized("HTTP ${response.statusCode}（iClass 没有给出业务原因）")
        }
        return parseEnvelope(response)
    }

    private fun parseEnvelope(response: IClassRawResponse): Envelope {
        val text = String(response.body, charsetFor(response.contentType))
        if (text.isBlank()) return Envelope.EmptyBody
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return Envelope.Unrecognized("响应不是外壳形状（解不出 JSON 对象）")
        val status = string(root, "STATUS")?.trim()?.toIntOrNull()
        if (status == null) return Envelope.Unrecognized("响应里没有可判定的 STATUS")
        val message = string(root, "ERRMSG")?.trim()?.takeIf { it.isNotEmpty() }
        return when (status) {
            STATUS_BUSINESS_ERROR -> Envelope.Rejected(message)
            STATUS_FRAMEWORK -> Envelope.Framework(message)
            else -> Envelope.Accepted(root, pick(root, RESULT_KEY) as? JsonObject)
        }
    }

    /**
     * 签到判定：外壳成功**还要** `result.stuSignStatus == "1"` 才算真签成。
     *
     * 少了后半句就是"服务端没收下这次签到、界面却报签到完成" —— 那是这一族最坏的假成功，
     * 因为用户不会再去核对，而课堂上的出勤记录真的没写上。
     */
    fun classifySign(response: IClassRawResponse): Result<Unit> = when (val envelope = classifyEnvelope(response)) {
        is Envelope.Accepted -> {
            val status = string(envelope.result ?: JsonObject(emptyMap()), STU_SIGN_STATUS_KEY)
            if (status?.trim() == STU_SIGN_STATUS_SIGNED) {
                Result.success(Unit)
            } else {
                Result.failure(
                    IClassUnexpectedResponseException("iClass 未确认签到完成（stuSignStatus=${status ?: "缺失"}）"),
                )
            }
        }

        is Envelope.Rejected -> Result.failure(IClassRejectedException(envelope.serverMessage ?: "iClass 拒绝了这次签到（服务端未给出原因）"))
        is Envelope.Framework -> Result.failure(IClassRejectedException(envelope.serverMessage ?: "iClass 框架错误（STATUS=2）"))
        Envelope.EmptyBody -> Result.failure(IClassUnexpectedResponseException("iClass 回了空响应（服务端没有受理这次签到）"))
        is Envelope.Unrecognized -> Result.failure(IClassUnexpectedResponseException(envelope.detail))
    }

    /** 登录判定：成功要拿到非空白的 `result.id`（它**不是**学号，见 [IClassSession]） */
    fun classifyLogin(response: IClassRawResponse): Result<String> = when (val envelope = classifyEnvelope(response)) {
        is Envelope.Accepted -> {
            val id = string(envelope.result ?: JsonObject(emptyMap()), USER_ID_KEY)?.trim()
            if (id.isNullOrEmpty()) {
                Result.failure(IClassLoginFailedException("iClass 登录响应里没有 id（无法签到）"))
            } else {
                Result.success(id)
            }
        }

        is Envelope.Rejected -> Result.failure(IClassLoginFailedException(envelope.serverMessage ?: "登录被拒（服务端未给出原因）"))
        is Envelope.Framework -> Result.failure(IClassLoginFailedException(envelope.serverMessage ?: "iClass 框架错误（STATUS=2）"))
        Envelope.EmptyBody -> Result.failure(IClassLoginFailedException("iClass 回了空响应（登录没有发生）"))
        is Envelope.Unrecognized -> Result.failure(IClassLoginFailedException(envelope.detail))
    }

    companion object {

        /**
         * 厂商客户端把 charset 初始化成 **GBK**，只有响应头带 `charset=` 才覆盖。
         *
         * 这条对中文 ERRMSG 是决定性的：`参数错误!` 之类是 GBK 字节，按 UTF-8 解就是乱码，
         * 而那句乱码是用户唯一能看到的原因。所以解 JSON 之前先看头，头里没有就按 GBK。
         */
        val FALLBACK_CHARSET: Charset = Charset.forName("GBK")

        /** 响应头里的 `charset=`；没有、不认识、被引号包着都处理掉，兜底 [FALLBACK_CHARSET] */
        fun charsetFor(contentType: String?): Charset {
            val value = contentType?.substringAfter("charset=", "")?.trim()?.trim('"', ';')
            if (value.isNullOrEmpty()) return FALLBACK_CHARSET
            return runCatching { Charset.forName(value) }.getOrDefault(FALLBACK_CHARSET)
        }

        /** 大小写不敏感取字段：这一族的键名没有契约保证，多一次 lowercase 的代价是零 */
        fun pick(obj: JsonObject, name: String): JsonElement? {
            obj[name]?.let { return it }
            val lower = name.lowercase()
            return obj.entries.firstOrNull { it.key.lowercase() == lower }?.value
        }

        /** 取一个标量字段的原文（数字也当字符串拿：`id` 就是数字形状）；缺失或 JSON null → null */
        fun string(obj: JsonObject, name: String): String? =
            (pick(obj, name) as? JsonPrimitive)?.let { if (it is JsonNull) null else it.content }

        /** 外壳的框架码：`1` = 业务错误（带给人看的 ERRMSG），`2` = 框架码 100005 */
        const val STATUS_BUSINESS_ERROR = 1
        const val STATUS_FRAMEWORK = 2

        private const val RESULT_KEY = "result"
        private const val USER_ID_KEY = "id"
        private const val STU_SIGN_STATUS_KEY = "stuSignStatus"

        /** `stuSignStatus` 只有实测到这一个值算真签成 */
        const val STU_SIGN_STATUS_SIGNED = "1"

        /**
         * 登录入口。与扫码原文同一对端口形状：明文侧是 `:8081`，
         * 我们只走 TLS 侧的 `:8181`（实测是同一个 webapp），因此清单里
         * **不需要** `usesCleartextTraffic` 例外。
         */
        const val LOGIN_URL =
            "https://" + IClassSignUrl.HOST + ":" + IClassSignUrl.TLS_PORT + "/app/user/login.action"

        /**
         * 口令校验委托给学校 `ve` 网关：`${0}`/`${1}`/`${2}` 由服务端自己填，不是我们的占位符。
         *
         * ⚠️ 这一条是**发给 iClass 的表单参数**，本应用从不向它发请求（清单里没有、
         * 代码里也没有任何一处访问 :88）—— 那句话由 iClass 的登录实现去打。
         */
        const val VERIFICATION_TYPE = "1"
        const val VERIFICATION_URL =
            "http://iclass.buaa.edu.cn:88/ve/webservices/mobileCheck.shtml" +
            "?method=mobileLogin&username=\${0}&password=\${1}&lx=\${2}"

        /**
         * 学生档位。
         *
         * ⚠️ 这是本文件里唯一一枚**没取证到取值**的常量（契约只给了字段名）。
         * 真机首联若被拒，先看这一枚与 `verificationType`：两处都是老客户端表单里的常量位。
         */
        const val USER_LEVEL_STUDENT = "1"
    }
}
