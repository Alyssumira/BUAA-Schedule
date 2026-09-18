package com.buaa.schedule.data.import

import android.content.Context
import android.util.Log
import android.webkit.WebView
import com.buaa.schedule.data.local.SpocTokenStore
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.resume

/** 智学北航的站点根 */
const val SPOC_ORIGIN = "https://spoc.buaa.edu.cn"

/** 接口基址：页面里 `config.baseUrl` 就是这个值（取证见 docs/BUAA_SPOC_SIGNIN_PLAN.md §0） */
const val SPOC_API_BASE = "$SPOC_ORIGIN/spocnewht/"

/** H5 前端首页，登录回跳与 Referer 都用它 */
const val SPOC_H5_ENTRY = "$SPOC_ORIGIN/bhspoc/"

/** CAS 单点登录入口：访问它会带票据回落到 H5 */
const val SPOC_CAS_ENTRY = "${SPOC_API_BASE}casmobile"

/** H5 签到页路由前缀；扫到的码若是完整 URL，多半以它开头 */
const val SPOC_SIGNIN_ROUTE = "$SPOC_H5_ENTRY#/pages/table/signIn"

/**
 * 一次 SPOC 登录拿到的全部鉴权材料。
 *
 * [token] 是**已带 `Inco-` 前缀**的完整值，可直接进请求头；
 * [xh] 是学号，提交签到时是必填 body 字段（页面里读 `uni.getStorageSync("xh")`）。
 */
data class SpocCredential(
    val token: String,
    val refreshToken: String,
    val xh: String,
    val rolecode: String?,
)

/**
 * 智学北航（SPOC）登录会话（应用级单例）。
 *
 * 与 [BuaaWebSession] **并列、不复用**：那边是 byxt 域的 Cookie 会话，必须把 WebView
 * 转挂在隐藏宿主里续命（`evaluateJavascript` 只在 attach 到窗口时才回调）；这边的
 * 鉴权材料是一枚 JWT，从 H5 的 localStorage 收割出来之后 WebView 就可以扔掉，
 * 之后所有请求都是纯 HTTP —— 因此本类没有宿主、没有定时器闸门、没有内存压力回收那一套。
 *
 * 收割完只留 [SpocTokenStore] 里的一份密文：课前签到提醒要在广播进程里发请求，
 * 那时界面上一个 WebView 都没有。
 */
object SpocSession {

    private const val TAG = "SpocSession"

    /**
     * 提前续期窗口：token 剩余寿命不足 10 分钟就先换新的。
     *
     * 签到是「老师开码之后两三分钟内必须签上」的场景，拿着一个下一秒就过期的 token
     * 去发请求，代价是用户当众重登。
     */
    private const val RENEW_AHEAD_MS = 10 * 60_000L

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 续期单飞：并发签到/探测时只让一个协程去打 refreshToken */
    private val refreshMutex = Mutex()

    @Volatile private var appContext: Context? = null

    @Volatile private var cached: SpocCredential? = null

    /** 区分「盘上确实没有」与「还没读盘」，避免每次取值都解一次密文 */
    @Volatile private var loadedFromDisk = false

    /** 与 [BuaaWebSession.init] 同口径：任意线程调一次，只寄存 Context */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
    }

    /** 是否已经有登录材料（不代表 token 没过期，过期会走 [authorized] 续期） */
    fun hasSession(): Boolean = credential() != null

    /** 冷启动后从盘上读一次；不碰 WebView，可在任意线程调用 */
    fun credential(): SpocCredential? {
        if (loadedFromDisk) return cached
        val context = appContext ?: return null
        // ⚠️ 顺序不能反：两个 @Volatile 之间没有互斥，若先发布 loadedFromDisk
        // 再算 cached，并发第二个调用（提醒广播的 hasSession 与签到界面就会撞上）
        // 会在上面那行看到"已加载"、却拿到还是 null 的 cached —— 随机"未登录"。
        val loaded = SpocTokenStore.load(context)?.let { decode(it) }
        cached = loaded
        loadedFromDisk = true
        return loaded
    }

    /** 登录页收割到材料后调用：内存与磁盘同时更新 */
    fun save(context: Context, credential: SpocCredential) {
        appContext = context.applicationContext
        cached = credential
        loadedFromDisk = true
        SpocTokenStore.save(context, encode(credential))
        Log.i(TAG, "SPOC 会话已保存，xh=${if (credential.xh.isBlank()) "(空)" else "已填"}")
    }

    /** 退出 SPOC 登录：只清 SPOC 的材料，教务那边的 Cookie 与 WebView 一概不动 */
    fun clear() {
        cached = null
        loadedFromDisk = true
        appContext?.let { SpocTokenStore.clear(it) }
        Log.i(TAG, "SPOC 会话已清除")
    }

    /**
     * 取一个可以直接发请求的会话，必要时先静默续期。
     *
     * @return null 表示需要用户重新登录：没有材料、没有 refreshToken，或续期被服务端拒了
     */
    suspend fun authorized(): SpocCredential? {
        val current = credential() ?: return null
        if (!needsRenewal(current)) return current
        return refreshMutex.withLock {
            // 双检：等锁期间可能已经有别的调用把 token 换好了
            val now = credential() ?: return@withLock null
            if (!needsRenewal(now)) return@withLock now
            if (now.refreshToken.isBlank()) {
                clear()
                return@withLock null
            }
            val renewed = SpocApi(json).refresh(now.refreshToken).getOrNull()
            if (renewed == null) {
                // 服务端已经认得这张 refreshToken 无效：留着只会每次都白试一遍
                clear()
                null
            } else {
                val next = now.copy(token = renewed.token, refreshToken = renewed.refreshToken)
                appContext?.let { save(it, next) }
                Log.i(TAG, "SPOC token 已续期")
                next
            }
        }
    }

    /** token 剩余寿命是否进入 [RENEW_AHEAD_MS]；解析不出 exp 时按"不必提前续"处理，交给服务端判定 */
    private fun needsRenewal(credential: SpocCredential): Boolean {
        val expMillis = jwtExpiresAtMillis(credential.token) ?: return false
        return expMillis - System.currentTimeMillis() < RENEW_AHEAD_MS
    }

    /**
     * 登录页收割会话：从 H5 的 localStorage 里取四个键。
     *
     * ⚠️ 不能直接 `localStorage.getItem("token")`：uni-app H5 的 `setStorageSync`
     * 会把值包成 `{"type":"string","data":"..."}` 再写进去（chunk-vendors 里那段
     * `o(t)` 解壳逻辑）。所以优先调页面自己的 `uni.getStorageSync`，让它按自己的格式
     * 解出来；页面里没有 `uni` 时才退回裸读 + 手工解壳。
     *
     * `evaluateJavascript` 要求主线程，且 WebView 必须仍 attach 在窗口上。
     */
    suspend fun harvest(webView: WebView): SpocCredential? {
        val script = """
            (function(){
              function raw(k){ try { return localStorage.getItem(k); } catch (e) { return null; } }
              function get(k){
                try {
                  if (typeof uni !== 'undefined' && uni.getStorageSync) {
                    var v = uni.getStorageSync(k);
                    if (v) return v;
                  }
                } catch (e) {}
                var s = raw(k);
                if (!s) return '';
                try { var p = JSON.parse(s); if (p && ('data' in p)) return p.data; } catch (e) {}
                return s;
              }
              try {
                return JSON.stringify({token: get('token'), refreshToken: get('refreshToken'),
                                       xh: get('xh'), rolecode: get('rolecode')});
              } catch (e) { return ''; }
            })()
        """.trimIndent()
        val callbackValue = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<String?> { cont ->
                webView.evaluateJavascript(script) { value -> if (cont.isActive) cont.resume(value) }
            }
        }
        val credential = parseHarvest(callbackValue)
        if (credential == null) Log.w(TAG, "收割失败：页面里没读到 token")
        return credential
    }

    /**
     * 解析 [harvest] 的回执。
     *
     * `evaluateJavascript` 回调给的是**一个 JSON 字符串字面量**（外层带引号、内层转义），
     * 所以要先按 JSON 解出那个字符串，再解一次内层对象。
     */
    private fun parseHarvest(callbackValue: String?): SpocCredential? {
        val outer = runCatching { json.parseToJsonElement(callbackValue ?: "") }.getOrNull() as? JsonPrimitive
        if (outer == null || outer is JsonNull || !outer.isString || outer.content.isBlank()) return null
        val obj = runCatching { json.parseToJsonElement(outer.content).jsonObject }.getOrNull() ?: return null
        fun field(name: String): String = text(obj, name).orEmpty()
        val token = field("token")
        val refreshToken = field("refreshToken")
        // token 与 refreshToken 是硬门槛：后者没了就意味着过期后只能重登，不如现在就知道。
        // xh 空着也先收下，提交签到前再由界面提示补登。
        if (token.isBlank() || refreshToken.isBlank()) return null
        return SpocCredential(
            token = if (token.startsWith("Inco-")) token else "Inco-$token",
            refreshToken = refreshToken,
            xh = field("xh"),
            rolecode = field("rolecode").takeIf { it.isNotBlank() },
        )
    }

    private fun text(obj: JsonObject, name: String): String? = SpocApi.string(obj, name)

    /**
     * 用 [buildJsonObject] 而不是手拼：字段全部来自 WebView 收割的 localStorage，
     * 内容由服务端定。手拼的 quote() 只转义 `\\` 与 `"`，一个换行就会写出解析不了的
     * 密文——下次冷启动 decode 静默返回 null，表现为"每天都要重登"，
     * 而坏值已经覆盖了原来的好值，无从自愈。
     */
    private fun encode(credential: SpocCredential): String = buildJsonObject {
        put("token", credential.token)
        put("refreshToken", credential.refreshToken)
        put("xh", credential.xh)
        credential.rolecode?.let { put("rolecode", it) }
    }.toString()

    private fun decode(persisted: String): SpocCredential? = runCatching {
        val obj = json.parseToJsonElement(persisted).jsonObject
        val token = text(obj, "token")?.takeIf { it.isNotBlank() } ?: return null
        SpocCredential(
            token = token,
            refreshToken = text(obj, "refreshToken").orEmpty(),
            xh = text(obj, "xh").orEmpty(),
            rolecode = text(obj, "rolecode"),
        )
    }.getOrNull()

    /**
     * 从 JWT 里读 `exp`（秒）换算成毫秒时间戳。
     *
     * 只解 Base64URL 的 payload 段，**不验签**：这里只想知道还剩多久寿命，令牌真伪由
     * 服务端判定，本地验签没有正当用途（也不持有服务端公钥）。
     * 结构不符（不是三段、payload 非 JSON、没有 exp）一律返回 null。
     */
    fun jwtExpiresAtMillis(token: String): Long? = runCatching {
        val raw = token.removePrefix("Inco-").trim()
        val payload = raw.split('.').getOrNull(1) ?: return null
        // JWT 的 base64url 段不带填充，Java 解码器要求补齐到 4 的倍数
        val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
        val decoded = String(java.util.Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
        val exp = (json.parseToJsonElement(decoded).jsonObject["exp"] as? JsonPrimitive)?.content?.toLongOrNull()
            ?: return null
        exp * 1000L
    }.getOrNull()
}
