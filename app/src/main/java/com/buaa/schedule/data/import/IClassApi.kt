package com.buaa.schedule.data.import

import com.buaa.schedule.domain.signin.LocallySpokenFailure
import com.buaa.schedule.domain.signin.LoginFailureClassifier
import com.buaa.schedule.domain.signin.LoginFailureRung
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
import java.util.concurrent.CancellationException

/**
 * 服务端业务性拒绝（外壳 `STATUS=1`）。
 *
 * [message] 是 ERRMSG 的**逐字原文**：这一族的 ERRMSG 全是厂商自己的中文文案，
 * 我们一条都没有（也没有取证到"已签过""码过期"这样的分类），所以整条链上
 * 一个字都不许加工 —— 包一层"签到失败"就把唯一那条真信息吃掉了。
 *
 * T392① 起它同时实现 [LocallySpokenFailure]：这一枚自带的句子（厂商原文）在任何一次折档里
 * 都只许被逐字念出来，不许被通用句覆盖。
 */
class IClassRejectedException(message: String) : IOException(message), LocallySpokenFailure {

    override val spokenLine: String get() = message.orEmpty()
}

/**
 * 响应读不出结论：空体、非 JSON、没有 STATUS、HTTP 非 2xx、被服务端转走、响应体没读回来 —— 一律按失败处理。
 *
 * 「按失败处理」说的是**我们这侧怎么处理**，不是服务端答了什么：这一族只有落到
 * [Envelope.Rejected] 那一档时那句才是厂商的原文（ERRMSG 逐字），其余各档只许念我们看得见的那半件事。
 */
class IClassUnexpectedResponseException(message: String) : IOException(message), LocallySpokenFailure {

    override val spokenLine: String get() = message.orEmpty()
}

/**
 * 登录被拒（口令错、账号不存在、验证码网关不放行……服务端给什么就说什么）。
 *
 * T392① 之后这一枚还装**本机那一趟的失败档**：传输层抛出来的东西在 `login()` 里被折成
 * [com.buaa.schedule.domain.signin.LoginFailureRung] 的中文句再交回，屏上因此不会念 JVM 的英文。
 * 「服务端给什么就说什么」那一半没变 —— 逐字念的还是 [LocallySpokenFailure.spokenLine]。
 */
class IClassLoginFailedException(message: String) : IOException(message), LocallySpokenFailure {

    override val spokenLine: String get() = message.orEmpty()
}

/**
 * 一次 HTTP 往返的**原始**结果：状态码 + `Content-Type` 头 + 响应体（三态，见 [body]）。
 *
 * 刻意不带任何解析痕迹：外壳判定要能在拿到字节之前就被测到（假响应表），
 * 而 charset 这件事的真相只在响应头里（见 [IClassApi.charsetFor]）。
 * 也刻意**不带 `Location`**：这一族不跟重定向，把转跳地址留在结构里就等于给它一条通往界面与
 * logcat 的路，而那条地址是服务端给的、可能带着我们发出去的那枚 `id`。
 */
class IClassRawResponse(
    val statusCode: Int,
    val contentType: String?,
    /**
     * 响应体的三态，不许折成两态：
     * 非空数组 = 读回来的字节；空数组 = 服务端确实什么都没回；
     * **null = 我们没把体读回来**（读超时、连接中途断、体损坏）—— 那一档对服务端答了什么一无所知。
     */
    val body: ByteArray?,
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
     * @return 原始响应。三档事实各交回各的，谁也不折成谁：3xx 交回状态码本身（**不跟过去**，也不读 `Location`），
     *         响应体读不回来交回 [IClassRawResponse.body] = null，服务端什么都没回才交回空数组。
     *         只有连不上、响应行与头都读不到时才抛 IOException。
     */
    fun execute(url: String, method: String, formBody: String?): IClassRawResponse
}

/**
 * 真实传输：HttpURLConnection。
 *
 * 四条"没有"是契约的一部分，这里逐字守着：**不发** Cookie、**不发**鉴权头、**不发**请求体
 * （签到那一次 GET 的全部信息就在那条 URL 上）、**不跟**重定向。
 * 最后那一条不是风格问题：挂在 URL 上的那枚 `id` 就是这一族全部的鉴权材料，
 * 跟着服务端给的 `Location` 再发一次，等于把那枚凭证交给契约外的一台主机，
 * 还把一次签到变成两趟真的把学生标记成已签到的写。转走这件事由上层单独判一档，不由这里决定语义。
 * UA 不属于鉴权材料，照厂商客户端发。
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
                // 不跟重定向（本文件那四条"没有"里的最后一条）。这一族的全部鉴权材料就挂在那条 URL 的
                // 查询串上，跟过去就是把 `id` 发给 Location 指去的任意主机，还把一次签到变成两趟写。
                // 3xx 原样交回，判据在 classifyEnvelope 那一头，这里不替它决定语义。
                instanceFollowRedirects = false
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
            // 两档不许折成一档：连流都没给 = 服务端确实什么都没回（空数组）；有流却没把字节读回来 = null，
            // 那是**我们这侧**的失败（读超时、连接中途断、体损坏），不是服务端的一个答复。
            // 折成空数组就会顺着 parseEnvelope 的空体判据落成"服务端回了空响应"那句 —— 替服务端说话。
            val body = if (stream == null) ByteArray(0) else runCatching { stream.use { it.readBytes() } }.getOrNull()
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
 *    正因为全部材料就挂在那条 URL 上，[HttpURLConnectionIClassTransport] **不跟**重定向 ——
 *    跟一次 3xx 就是把这枚 `id` 交给契约外的一台主机，这条契约的"只有一个"当场就不成立了；
 * 2. **签到是拼接出来的 GET**，不是"字段进 DTO 出"：所以 [signIn] 收的是扫码原文，
 *    中间任何一次"解析再重组"都会把它变成另一条请求（见 [IClassSignUrl]）；
 * 3. **外壳与门户页那一套不是一回事**：`/app` 打头的那一族回 `{STATUS, ERRCODE, ERRMSG}`，
 *    门户页面（`/front` 打头）回 `{code, msg}`。本类只走 `/app` 那一族，判定见 [classifyEnvelope]。
 *
 * 这三条 T84 落地时是拿智学北航（SPOC）那一族**对照**着写的（token 头 / POST JSON / `{code,msg}` 外壳
 * 是它那边的形状）。T85 把那条链整条拆掉之后，对照物在本仓里已经不存在了，
 * 但三条的**语义**一个字都没改 —— 它们钉的是本类自己怎么写，不是"跟谁不一样"。
 * T349 只在第 1 条后面补了一句它本来就蕴含的前提（不跟重定向），并把外壳的档位从 5 长成 7；
 * 三条本身一条没并、没删、没改口。
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
     *
     * **失败按档说话**（T392①，第⓪问裁的甲档）：
     * - 本仓自己写好那句话的（空凭据那一拒、[classifyLogin] 那六档、裹着厂商 ERRMSG 原文的那一枚）
     *   带着 [LocallySpokenFailure] 走人 —— 折档认的就是这一枚契约，**逐字不动**；
     * - 传输层（[IClassTransport.execute]）抛出来的那一族读不到外壳，从前会连着 JVM 的英文
     *   （用户当场看到过 `timeout`）一路走到登录页那句「登录失败：…」；现在它们在**这里**就被折成
     *   [com.buaa.schedule.domain.signin.LoginFailureRung]，每一档只念本机这一趟看得见的事。
     * 折档只发生在这一路：[signIn] 那一路一个字没动（在册判据见 IClassApiTest 那枚
     * 「传输层抛出来的是失败而不是异常」，它钉的正是签到那一路交回原文）。
     *
     * **取消不在这里折档**（T397①，第⓪问裁的丙档形状）：这一手从前整个裹在一层 `runCatching` 里，
     * 而那一层吃的是 `Throwable` —— 协程取消走到调用点时已经被换成一句普通的失败**返回**，
     * 屏上念的就是 [com.buaa.schedule.domain.signin.LoginFailureRung.WithdrawnLocally] 那句话，
     * 而"这一趟没了"这件事本身协程框架与调用点都收不到。所以换成下面那一形：
     * 取消那一型**原样复投**，其余各档才折档。分工仍是 T365 那一族的两半 ——
     * **判档住在 [com.buaa.schedule.domain.signin.LoginFailureClassifier]（本卡一字未动），
     * 复投只住在这一个调用点**；口径见 `ui/ScheduleViewModel.kt` 里那句
     * 「取消是控制流，不是错误」，形状抄本仓已有的两枚（`widget/BackgroundSync.kt` 的
     * `readChainSnapshotOrNull`、`ui/ScheduleViewModel.kt` 的 `suspendCatching`：
     * 取消那一 catch 排在宽 catch **之前**）。
     * ⚠️ 只在原来那一支 catch 里补一句复投是不够的：那一手在 `runCatching` 的块**之内**，
     * 复投出去的取消会被同一层再吃一遍 —— 换掉那一层才是修法，这一条是第⓪问的裁定本身。
     * ⚠️ `Error` 那一族**仍**被折成一次失败返回（档 `LocalHardFault`）：从前那层 `runCatching`
     * 本来就吃 `Throwable`，那是改前既有的口径、不是这一手引入的；本卡单独裁过「不跟着复投」，
     * 与 [signIn] 那一路同列为已知缺口（见该函数那段口径）。
     */
    suspend fun login(phone: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            Result.success(loginAttempt(phone, password))
        } catch (e: CancellationException) {
            throw e
        } catch (cause: Throwable) {
            Result.failure(spokenLoginFailureOrSelf(cause))
        }
    }

    /** 原本那一趟：先拦空凭据（那句是我们自己写的中文），再发那五件套 form，判定整个交给 [classifyLogin] */
    private fun loginAttempt(phone: String, password: String): String {
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
        return classifyLogin(transport.execute(LOGIN_URL, "POST", form)).getOrThrow()
    }

    /**
     * 该换的才换：档是 [LoginFailureRung.SpokenVerbatim] 就原样交回那一枚，其余各档换成一句中文的
     * [IClassLoginFailedException]（登录页据它决定给不给重登表单，落点与 [classifyLogin] 那六档同一个）。
     *
     * 换掉的那一枚挂在 `cause` 上：类名由 [LoginFailureClassifier.forensicsLine] 交进取证行，
     * 而它的 message（可能带着服务器地址与端口）既不上屏也不进日志。
     * ⚠️ 形参叫 `raw` 而不是 `cause`：`apply` 那一段里 `cause` 会先解到 Throwable 自己的 `cause` 属性
     * （新造的那一枚是 null），initCause(null) 就等于把本机那一枚丢掉。
     */
    private fun spokenLoginFailureOrSelf(raw: Throwable): Throwable {
        if (LoginFailureClassifier.rungOf(raw) == LoginFailureRung.SpokenVerbatim) return raw
        return IClassLoginFailedException(LoginFailureClassifier.screenLine(raw)).apply { initCause(raw) }
    }

    /**
     * 外壳判定的第一层：`STATUS` 走**整数 switch**，成功只表示"框架层面受理了"。
     *
     * - `1` → [Envelope.Rejected]：业务错误，ERRMSG 是给人看的那一句
     * - `2` → [Envelope.Framework]：框架码 100005（实践里是网关/会话那一层的话，不是业务回执）
     * - 其余（实测就是 `0`）→ [Envelope.Accepted]
     * - 3xx → [Envelope.Redirected]：服务端把这次请求转走了。**不看它的体**，也不跟过去
     * - 响应体没读回来 → [Envelope.BodyUnreadable]：超时／中途断，那是我们这侧的事，与空体不是一档
     * - 空体（0 字节或只剩 `\r\n`）→ [Envelope.EmptyBody]，**判失败**
     *
     * 空体那一档不是洁癖：实测给任一数字型属性喂非数字值，服务端就回 2 字节 `\r\n` ——
     * 那是"处理到一半死了"的形状，当成功就是把失败签成"签到完成"。但它只支持"没有任何受理的证据"
     * 这半句，不支持"服务端没有受理"那半句：一枚空体里读不出受理没受理，而那一发请求服务端很可能已经收了。
     * 没有 STATUS / 不是 JSON / 不是对象同样落 [Envelope.Unrecognized]，也判失败。
     *
     * **判据顺序就是语义**（[classifyEnvelope]）：3xx 排在读体之前、读体排在状态码判档之前。
     * 反过来的话，一枚 302 附带的那张转跳页会被解成"外壳"，而读不到体那次会被念成"服务端回了空响应" ——
     * 前者是给 3xx 编造语义，后者是拿我们这侧的失败去断言服务端。两档都另有去处，不许借道空体与 Unrecognized。
     */
    sealed interface Envelope {
        data class Accepted(val root: JsonObject, val result: JsonObject?) : Envelope
        data class Rejected(val serverMessage: String?) : Envelope
        data class Framework(val serverMessage: String?) : Envelope
        data object EmptyBody : Envelope

        /** 服务端回 3xx 把请求转走：我们没跟过去，也不知道转去了哪，这一族的语义里**没有**"签到成功" */
        data object Redirected : Envelope

        /** 响应体没读回来（读超时、连接中途断、体损坏）：对服务端答了什么一无所知，只许说我们这侧的事 */
        data object BodyUnreadable : Envelope

        data class Unrecognized(val detail: String) : Envelope
    }

    /**
     * 表驱动可测的外壳判定：状态码 → 体的三态 → 文本（按 charset 规则）→ JSON → [Envelope]。
     *
     * 先判转走、再判读没读回来、再判空体、最后才解 JSON，顺序反了就把三档折成一档：
     * 空体那一格若被解到前面，读不到体会被报成"不是 JSON"；3xx 那一格若被解到后面，
     * 转跳页会被当成业务外壳。用户读到的是我们造的那句，而不是盘上那一个事实。
     */
    fun classifyEnvelope(response: IClassRawResponse): Envelope {
        // 3xx 一律单独一档，并且**不看体**：认不下的一律不许从体里捞出成功与否（那是编造语义）。
        if (response.statusCode in 300..399) return Envelope.Redirected
        // 没读回来与什么都没回是两件事：前者是这一趟往返在我们这侧断了。
        if (response.body == null) return Envelope.BodyUnreadable
        if (response.statusCode !in 200..299) {
            // 状态码先说话，但如果体里真有外壳，下面一层还能把 ERRMSG 捞出来，所以不在此早退
            val parsed = parseEnvelope(response)
            if (parsed is Envelope.Rejected || parsed is Envelope.Framework) return parsed
            return Envelope.Unrecognized("HTTP ${response.statusCode}（iClass 没有给出业务原因）")
        }
        return parseEnvelope(response)
    }

    private fun parseEnvelope(response: IClassRawResponse): Envelope {
        // 空性在这一枚函数里再守一次：它是唯一把字节变成文本的地方，绕过 classifyEnvelope 直接喂它的
        // 测试与将来第三个调用点都不该拿到"折成零字节的读失败"（本卡钉的就是那一折）。
        val bytes = response.body ?: return Envelope.BodyUnreadable
        val text = String(bytes, charsetFor(response.contentType))
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
        Envelope.EmptyBody -> Result.failure(IClassUnexpectedResponseException("iClass 回了空响应（没有任何受理的证据）"))
        // 这两档的头念的是**我们看得见的那件事**（被转走 / 没读回来），尾巴那句念的是本地结论。
        // 一句都不替服务端答：那一发 GET 在服务端很可能已经受理，而我们没有任何证据说它没受理。
        Envelope.Redirected -> Result.failure(IClassUnexpectedResponseException("iClass 把这次请求转走了（没跟过去，也不知道转去哪），没有任何受理的证据"))
        Envelope.BodyUnreadable -> Result.failure(IClassUnexpectedResponseException("没把 iClass 的响应体读回来（超时或连接中途断），没有任何受理的证据"))
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
        // 空体的那一档从前写"登录没有发生"：那是关于服务端的断言，而空体只支持"没有拿到 id"这半句
        // （这半句还是本地事实 —— 没有 id 就没有会话，[IClassSession] 只在成功时落盘）。
        Envelope.EmptyBody -> Result.failure(IClassLoginFailedException("iClass 回了空响应（没有拿到 id）"))
        Envelope.Redirected -> Result.failure(IClassLoginFailedException("iClass 把这次登录请求转走了（没跟过去，也不知道转去哪），没有拿到 id"))
        Envelope.BodyUnreadable -> Result.failure(IClassLoginFailedException("没把 iClass 的登录响应体读回来（超时或连接中途断），没有拿到 id"))
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
