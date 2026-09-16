package com.buaa.schedule.data.import

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.IOException

class BuaaSessionExpiredException(message: String) : IOException(message)

/**
 * 北航教务接口客户端（HttpURLConnection 实现）。
 *
 * 经真机实测（shouldInterceptRequest 内用 HttpURLConnection 直连），教务接口
 * 认证 = **byxt 域 Cookie + 标准 application/x-www-form-urlencoded POST + 
 * X-Requested-With: XMLHttpRequest 头**，与 OkHttp 相比唯一差异是底层栈与
 * User-Agent。此处统一用 HttpURLConnection + 浏览器 UA，确保与已验证路径一致。
 */
class BuaaApi(
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    private val scheduleUrl = "https://byxt.buaa.edu.cn/jwapp/sys/homeapp/api/home/student/getMyScheduleDetail.do"
    private val termWeeksUrl = "https://byxt.buaa.edu.cn/jwapp/sys/homeapp/api/home/getTermWeeks.do"
    private val gsmisScheduleUrl = "https://gsmis.buaa.edu.cn/gsapp/sys/wdkbapp/bykb/loadXskbData.do"

    private val browserUserAgent =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"

    /** 执行 POST form 请求并返回原始响应文本 */
    private fun postFormJava(url: String, cookie: String, params: List<Pair<String, String>>): String {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("X-Requested-With", "XMLHttpRequest")
                setRequestProperty("Cookie", cookie)
                setRequestProperty("User-Agent", browserUserAgent)
                setRequestProperty("Referer", "https://byxt.buaa.edu.cn/jwapp/sys/homeapp/home/index.html")
            }
            val body = params.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code == 401 || code == 403) {
                throw BuaaSessionExpiredException("教务系统拒绝请求（HTTP $code），登录可能失效")
            }
            if (code !in 200..299) {
                throw IOException("HTTP $code: ${connection.responseMessage}")
            }
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun fetchSchedule(
        termCode: String,
        cookie: String,
        campusCode: String = "",
        type: String = "term",
        week: Int? = null,
    ): Result<List<BuaaCourseDto>> = withContext(Dispatchers.IO) {
        runCatching {
            val params = mutableListOf("termCode" to termCode, "campusCode" to campusCode, "type" to type)
            if (type == "week" && week != null) params.add("week" to week.toString())
            val text = postFormJava(scheduleUrl, cookie, params)
            ensureNotLoginPage(text)
            val decoded = json.decodeFromString<BuaaScheduleResponse>(text)
            if (decoded.datas == null && decoded.code != null && decoded.code != "0") {
                throw BuaaSessionExpiredException(
                    "登录已失效或接口返回异常：code=${decoded.code}, msg=${decoded.msg}"
                )
            }
            decoded.datas?.arrangedList.orEmpty()
        }
    }

    suspend fun fetchTermWeeks(
        termCode: String,
        cookie: String,
    ): Result<List<BuaaTermWeekDto>> = withContext(Dispatchers.IO) {
        runCatching {
            val text = postFormJava(termWeeksUrl, cookie, listOf("termCode" to termCode))
            ensureNotLoginPage(text)
            val decoded = json.decodeFromString<BuaaTermWeeksResponse>(text)
            if (decoded.datas == null && decoded.code != null && decoded.code != "0") {
                throw BuaaSessionExpiredException(
                    "登录已失效或接口返回异常：code=${decoded.code}, msg=${decoded.msg}"
                )
            }
            decoded.datas.orEmpty()
        }
    }

    suspend fun fetchGsmisSchedule(
        termCode: String,
        cookie: String,
    ): Result<List<GsmisCourseDto>> = withContext(Dispatchers.IO) {
        runCatching {
            val params = listOf("ZC" to "", "XNXQDM" to termCode, "XH" to "", "XQDM" to "")
            val text = postFormJava(gsmisScheduleUrl, cookie, params)
            ensureNotLoginPage(text)
            json.decodeFromString<GsmisScheduleResponse>(text).jgList.orEmpty()
        }
    }

    private fun ensureNotLoginPage(text: String) {
        if (text.trimStart().startsWith("<")) {
            throw BuaaSessionExpiredException("登录已失效（接口返回了登录页面）")
        }
    }
}
