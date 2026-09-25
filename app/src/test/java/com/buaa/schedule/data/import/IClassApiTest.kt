package com.buaa.schedule.data.import

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.charset.Charset

/**
 * `/app` 那一族两层外壳的判定，全用假响应表驱动（一次真请求都不发）。
 *
 * 这一层的判据每一条都有代价，写在这里是为了让"改判据"这件事必须付钱：
 * - **`STATUS` 是整数 switch**，`ERRCODE` 恒为 `"100"` ⇒ 拿 ERRCODE 分支就是拿常量分支，
 *   所以本文件专门有一档"把 ERRCODE 改成任何别的值，判定不许变"；
 * - **成功还要 `result.stuSignStatus == "1"`** ⇒ 少了这半句就是"服务端没收下、界面报签成"，
 *   那一档是这条链上最坏的假成功（用户不会去核对，而课堂出勤记录真的没写上）；
 * - **空体判失败** ⇒ 实测喂给数字型属性一个非数字值，服务端回 2 字节 `\r\n`，
 *   那是"处理到一半死了"，不是"签成了"；
 * - **ERRMSG 逐字透传** ⇒ 厂商的中文文案我们一条都没有，也没有分类，
 *   包一层"签到失败"就把唯一那条真信息吃掉了；
 * - **charset 先看头、没有就 GBK** ⇒ 厂商客户端就是这么初始化 charset 的，
 *   按 UTF-8 硬解会把 `参数错误!` 糊成乱码，而那句乱码是用户唯一的线索。
 */
class IClassApiTest {

    private val api = IClassApi()
    private val gbk: Charset = IClassApi.FALLBACK_CHARSET

    /** 真码原文（提交 URL 的输入，见 [urlOfRecordedRequestGoesToTheConcatenatedAddress]） */
    private val realCode =
        "http://iclass.buaa.edu.cn:8081/app/course/stu_scan_sign.action" +
            "?courseSchedId=2488752&timestamp=1790247391994"

    private fun body(text: String, contentType: String? = null, status: Int = 200) =
        IClassRawResponse(status, contentType, text.toByteArray(Charsets.UTF_8))

    private fun gbkBody(text: String, contentType: String? = null, status: Int = 200) =
        IClassRawResponse(status, contentType, text.toByteArray(gbk))

    private fun failedMessage(result: Result<*>): String =
        (requireNotNull(result.exceptionOrNull()) { "这一档本该失败，却成功了：$result" }).message.orEmpty()

    // ---- 签到：成功那一档与"没签成"那一档必须分得开 ----

    @Test
    fun `STATUS 受理且 stuSignStatus 为1才算签成`() {
        for (raw in listOf(
            """{"STATUS":0,"ERRCODE":"100","ERRMSG":"","result":{"stuSignStatus":"1"}}""",
            """{"STATUS":"0","ERRCODE":"100","ERRMSG":"","result":{"stuSignStatus":"1"}}""",
            """{"STATUS":0,"ERRCODE":"100","ERRMSG":"","result":{"STUSIGNSTATUS":"1"}}""",
            """{"STATUS":0,"ERRCODE":"100","ERRMSG":"","result":{"stuSignStatus":"1","id":88}}""",
        )) {
            assertTrue("该判签成：$raw", api.classifySign(body(raw)).isSuccess)
        }
    }

    @Test
    fun `STATUS 受理但 stuSignStatus 不是1不许报成功`() {
        for (raw in listOf(
            """{"STATUS":0,"ERRCODE":"100","ERRMSG":"","result":{"stuSignStatus":"0"}}""",
            """{"STATUS":0,"ERRCODE":"100","ERRMSG":"","result":{"stuSignStatus":"2"}}""",
            """{"STATUS":0,"ERRCODE":"100","ERRMSG":"","result":{}}""",
            """{"STATUS":0,"ERRCODE":"100","ERRMSG":""}""",
            """{"STATUS":0,"ERRCODE":"100","ERRMSG":"","result":null}""",
        )) {
            val message = failedMessage(api.classifySign(body(raw)))
            assertTrue("这一档说的话里没有 stuSignStatus，读起来像成功：$message", message.contains("stuSignStatus"))
        }
    }

    @Test
    fun `STATUS 是一就是业务错误且 ERRMSG 逐字上屏`() {
        // 没有 charset 头 ⇒ 按 GBK 解（厂商客户端的初始化口径），所以这里也按 GBK 编进去
        val response = gbkBody("""{"STATUS":1,"ERRCODE":"100","ERRMSG":"参数错误!","result":null}""")
        val result = api.classifySign(response)
        assertEquals("参数错误!", failedMessage(result))
        assertTrue(
            "业务拒绝要落成 IClassRejectedException（界面据此决定给不给「去登录」出口）",
            result.exceptionOrNull() is IClassRejectedException,
        )
        // 同一句话按 UTF-8 送来时必须跟着头走，否则这一档就成了"永远只认 GBK"
        val utf8 = body("""{"STATUS":1,"ERRCODE":"100","ERRMSG":"参数错误!"}""", contentType = "text/json;charset=UTF-8")
        assertEquals("参数错误!", failedMessage(api.classifySign(utf8)))
    }

    @Test
    fun `STATUS 是一而没有 ERRMSG 时说明服务端没给原因`() {
        assertEquals(
            "iClass 拒绝了这次签到（服务端未给出原因）",
            failedMessage(api.classifySign(body("""{"STATUS":1,"ERRCODE":"100","ERRMSG":""}"""))),
        )
    }

    @Test
    fun `STATUS 是二走框架码那一档`() {
        val result = api.classifySign(body("""{"STATUS":2,"ERRCODE":"100","ERRMSG":"100005"}"""))
        assertEquals("100005", failedMessage(result))
        assertTrue(result.exceptionOrNull() is IClassRejectedException)
    }

    // ---- 空体：判失败，不许当成功 ----

    @Test
    fun `空响应体一律判失败`() {
        for (raw in listOf("", "\r\n", "\n", "   ")) {
            val result = api.classifySign(IClassRawResponse(200, null, raw.toByteArray(gbk)))
            assertTrue("空体（${raw.toByteArray().size} 字节）被判成了成功", result.isFailure)
            assertEquals("iClass 回了空响应（服务端没有受理这次签到）", failedMessage(result))
        }
    }

    @Test
    fun `外壳读不出来时说的是形状而不是编造的原因`() {
        for (raw in listOf("<html>500</html>", "not json", "[]", """{"ERRCODE":"100"}""", "{}")) {
            val result = api.classifySign(body(raw))
            assertTrue("这一档本该失败：$raw", result.isFailure)
            assertTrue(
                "把形状问题说成了业务原因：${failedMessage(result)}",
                failedMessage(result).let { it.contains("STATUS") || it.contains("JSON") },
            )
        }
    }

    @Test
    fun `HTTP 非2xx 也是失败且带上状态码`() {
        val result = api.classifySign(body("""{"STATUS":0,"result":{"stuSignStatus":"1"}}""", status = 500))
        assertTrue("500 里躺着的『受理』不许算签成", result.isFailure)
        assertTrue(failedMessage(result).contains("500"))
    }

    // ---- ERRCODE 恒为 100：不参与判定 ----

    @Test
    fun `ERRCODE 改成别的值判定不许变`() {
        val withHundred = """{"STATUS":1,"ERRCODE":"100","ERRMSG":"参数错误!"}"""
        val withOther = """{"STATUS":1,"ERRCODE":"999","ERRMSG":"参数错误!"}"""
        val without = """{"STATUS":1,"ERRMSG":"参数错误!"}"""
        assertEquals(failedMessage(api.classifySign(body(withHundred))), failedMessage(api.classifySign(body(withOther))))
        assertEquals(failedMessage(api.classifySign(body(withHundred))), failedMessage(api.classifySign(body(without))))
        val success = """{"STATUS":0,"ERRCODE":"100","result":{"stuSignStatus":"1"}}"""
        val successOtherCode = """{"STATUS":0,"ERRCODE":"404","result":{"stuSignStatus":"1"}}"""
        assertEquals(api.classifySign(body(success)).isSuccess, api.classifySign(body(successOtherCode)).isSuccess)
    }

    /** 数字型 ERRCODE 也不该被谁偷偷用起来：判定层一次都不取值 */
    @Test
    fun `判定层从不读 ERRCODE`() {
        val code = File(findMainJavaDir(), "com/buaa/schedule/data/import/IClassApi.kt").readText()
        assertTrue(
            "IClassApi 里出现了按 ERRCODE 取值的代码（它恒为 100，拿常量分支等于不分支）：\n" +
                Regex("""(string|pick)\([^)]*ERRCODE""").findAll(code).joinToString(),
            Regex("""(string|pick)\([^)]*ERRCODE""").findAll(code).none(),
        )
    }

    // ---- charset：先看响应头，没有就 GBK ----

    @Test
    fun `没有 charset 头时按 GBK 解中文 ERRMSG`() {
        val text = """{"STATUS":1,"ERRCODE":"100","ERRMSG":"签到时间已过，请重新扫码"}"""
        val result = api.classifySign(gbkBody(text))
        assertEquals("签到时间已过，请重新扫码", failedMessage(result))
        // 同一份字节按 UTF-8 硬解必然不是那句话——这条断言钉的是"GBK 兜底不是可有可无"
        val asUtf8 = String(text.toByteArray(gbk), Charsets.UTF_8)
        assertTrue("UTF-8 硬解居然也得到原句，那这一档就没有钉住任何东西", asUtf8 != "签到时间已过，请重新扫码")
    }

    @Test
    fun `响应头带 charset 时以头为准`() {
        val text = """{"STATUS":1,"ERRCODE":"100","ERRMSG":"参数错误!"}"""
        val utf8Header = body(text, contentType = "application/json;charset=UTF-8")
        val gbkHeader = gbkBody(text, contentType = "application/json; charset=GBK")
        val quotedHeader = body(text, contentType = """application/json; charset="utf-8"""")
        assertEquals("参数错误!", failedMessage(api.classifySign(utf8Header)))
        assertEquals("参数错误!", failedMessage(api.classifySign(gbkHeader)))
        assertEquals("参数错误!", failedMessage(api.classifySign(quotedHeader)))
    }

    @Test
    fun `charsetFor 认头不认猜不认识就回 GBK`() {
        assertEquals(Charsets.UTF_8, IClassApi.charsetFor("application/json;charset=UTF-8"))
        assertEquals(Charsets.UTF_16LE, IClassApi.charsetFor("text/plain; charset=UTF-16LE"))
        assertEquals(gbk, IClassApi.charsetFor("application/json;charset=NOT-A-CHARSET"))
        assertEquals(gbk, IClassApi.charsetFor("application/json"))
        assertEquals(gbk, IClassApi.charsetFor(null))
        assertEquals(gbk, IClassApi.charsetFor(""))
        // 只有 charset= 后面为空也要落兜底，而不是拿 "" 去 Charset.forName
        assertEquals(gbk, IClassApi.charsetFor("application/json;charset="))
    }

    // ---- 登录：只取 result.id ----

    @Test
    fun `登录成功取到 resultid 且数字与字符串两种形状都收`() {
        for (raw in listOf(
            """{"STATUS":0,"ERRCODE":"100","ERRMSG":"","result":{"id":2488752,"studentNo":"17371234"}}""",
            """{"STATUS":0,"ERRCODE":"100","ERRMSG":"","result":{"id":"2488752"}}""",
            """{"STATUS":0,"ERRCODE":"100","ERRMSG":"","result":{"ID":"2488752"}}""",
        )) {
            assertEquals("2488752", api.classifyLogin(body(raw)).getOrNull())
        }
    }

    @Test
    fun `id 不是学号这一档由字段来源保证`() {
        // result.id 与 result.studentNo 是两条独立字段：签到只取前者，后者连看都不看
        val raw = """{"STATUS":0,"ERRCODE":"100","result":{"id":"88","studentNo":"17371234"}}"""
        assertEquals("88", api.classifyLogin(body(raw)).getOrThrow())
    }

    @Test
    fun `登录没有 id 一律判失败`() {
        for (raw in listOf(
            """{"STATUS":0,"ERRCODE":"100","result":{}}""",
            """{"STATUS":0,"ERRCODE":"100","result":{"id":""}}""",
            """{"STATUS":0,"ERRCODE":"100","result":{"id":null}}""",
            """{"STATUS":0,"ERRCODE":"100"}""",
            "",
            "\r\n",
        )) {
            assertTrue("这一档本该失败：$raw", api.classifyLogin(body(raw)).isFailure)
        }
    }

    @Test
    fun `登录被拒时 ERRMSG 逐字`() {
        val result = api.classifyLogin(gbkBody("""{"STATUS":1,"ERRCODE":"100","ERRMSG":"用户名或密码错误"}"""))
        assertEquals("用户名或密码错误", failedMessage(result))
        assertTrue(result.exceptionOrNull() is IClassLoginFailedException)
    }

    // ---- 传输缝：签到那次请求长什么样 ----

    @Test
    fun urlOfRecordedRequestGoesToTheConcatenatedAddress() {
        val seen = ArrayList<Triple<String, String, String?>>()
        val fake = IClassTransport { url, method, form ->
            seen += Triple(url, method, form)
            body("""{"STATUS":0,"ERRCODE":"100","result":{"stuSignStatus":"1"}}""")
        }
        val outcome = runBlocking { IClassApi(fake).signIn(realCode, "2488752") }
        if (outcome.isFailure) fail("签到被判失败：${failedMessage(outcome)}")
        assertEquals(1, seen.size)
        val (url, method, form) = seen[0]
        assertEquals("GET", method)
        assertNull("签到不许带请求体", form)
        assertEquals(
            "提交地址不是『原文 + &id=』那个形状",
            "https://iclass.buaa.edu.cn:8181/app/course/stu_scan_sign.action" +
                "?courseSchedId=2488752&timestamp=1790247391994&id=2488752",
            url,
        )
    }

    @Test
    fun `登录请求是 form 且五个字段一个不多一个不少`() {
        val seen = ArrayList<Triple<String, String, String?>>()
        val fake = IClassTransport { url, method, form ->
            seen += Triple(url, method, form)
            body("""{"STATUS":0,"ERRCODE":"100","result":{"id":"998877"}}""")
        }
        val id = runBlocking { IClassApi(fake).login("13800000000", "se cret").getOrThrow() }
        assertEquals("998877", id)
        val (url, method, form) = seen[0]
        assertEquals("POST", method)
        assertEquals("https://iclass.buaa.edu.cn:8181/app/user/login.action", url)
        val pairs = requireNotNull(form).split("&").associate {
            it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
        assertEquals(
            setOf("phone", "password", "verificationType", "verificationUrl", "userLevel"),
            pairs.keys,
        )
        assertEquals("13800000000", pairs["phone"])
        assertEquals("se cret", pairs["password"])
        assertEquals("1", pairs["verificationType"])
        assertEquals(
            "http://iclass.buaa.edu.cn:88/ve/webservices/mobileCheck.shtml" +
                "?method=mobileLogin&username=\${0}&password=\${1}&lx=\${2}",
            pairs["verificationUrl"],
        )
        assertEquals("1", pairs["userLevel"])
    }

    @Test
    fun `传输层抛出来的是失败而不是异常`() {
        val fake = IClassTransport { _, _, _ -> throw java.io.IOException("连不上") }
        val outcome = runBlocking { IClassApi(fake).signIn(realCode, "88") }
        assertEquals("连不上", failedMessage(outcome))
    }

    /** 空口令与空手机号不该变成一次真请求：先把住，再谈服务端怎么说 */
    @Test
    fun `空凭据不发请求`() {
        var calls = 0
        val fake = IClassTransport { _, _, _ ->
            calls++
            body("""{"STATUS":0,"result":{"id":"1"}}""")
        }
        val api2 = IClassApi(fake)
        for (pair in listOf("" to "x", "   " to "x", "13800000000" to "")) {
            assertTrue("空凭据本该失败：$pair", runBlocking { api2.login(pair.first, pair.second) }.isFailure)
        }
        assertEquals("空凭据居然还发了请求", 0, calls)
    }

    private fun findMainJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { File(dir, it) }
                .firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }
}
