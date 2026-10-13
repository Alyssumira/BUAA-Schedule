package com.buaa.schedule.data.import

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * - **T349：转走／读不回来／真空体是三档，不许折成一档** ⇒ 屏上那句逐字走到失败卡第一行
 *   （`SignInViewModel.fail()`）与登录页那句「登录失败：…」（`IClassLoginScreen.serverMessage`），
 *   所以每一档各自规定"必须念什么、绝对不许念什么"：读不回来那一档对服务端的答复一无所知，
 *   连「服务端」三个字都不许上屏；3xx 那一档不许从那张转跳页的体里捞出成功；
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

    /**
     * 真空体（服务端什么都没回）那一档。
     *
     * 措辞从前是「服务端没有受理这次签到」—— 那是关于服务端的一句断言，而一枚空体只支持
     * "没有任何受理的证据"这半句（实测的形状是"处理到一半死了"，请求服务端很可能已经收了）。
     * T349 按卡面 ④ 订正成后者：判失败这件事不变，变的是不替服务端答。
     */
    @Test
    fun `空响应体一律判失败`() {
        for (raw in listOf("", "\r\n", "\n", "   ")) {
            val result = api.classifySign(IClassRawResponse(200, null, raw.toByteArray(gbk)))
            assertTrue("空体（${raw.toByteArray().size} 字节）被判成了成功", result.isFailure)
            assertEquals("iClass 回了空响应（没有任何受理的证据）", failedMessage(result))
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

    // ==== T349：三档 × 两条路（假响应表驱动，逐格点名"屏上那句到底断言了谁"）====

    /**
     * 一格三样东西：档位名、递进 `classify*` 的假响应、以及**屏上那一句**的归属判据。
     *
     * "屏上那句"不是内部状态，是逐字走到用户眼前的那两行：
     * - 签到：`classifySign(...)` 的异常 message → `SignInViewModel.fail()`（:298-302）→
     *   `SignInState.Failed.reason` → 那张失败卡的第一行；
     * - 登录：`classifyLogin(...)` 的异常 message → `IClassLoginScreen` 的 `serverMessage` →
     *   「登录失败：$serverMessage」那一句。
     * 所以 say / notSay 数的是**那句话在替谁说话**：
     * - `EmptyBody`：零字节本身是响应的一部分，所以有资格说「iClass 回了空响应」；
     *   但说不了「服务端没有受理」/「登录没有发生」—— 一枚空体里读不出受理没受理；
     * - `BodyUnreadable`：盘上关于服务端的答复**一个事实都没有**（我们连体都没拿回来），
     *   所以它的句子连「服务端」三个字都不许出现，只许念我们这侧断了；
     * - `Redirected`：3xx 状态码是我们真读到的，所以能说「把这次请求转走了」；
     *   但不许说转去哪（`Location` 我们根本不读），更不许从那张转跳页的体里捞出成功。
     */
    private data class UnreadableRung(
        val name: String,
        /** 与 `IClassApi.Envelope` 里那一档逐字同名的枚号（名册闭合由 ③ 那枚守卫吃它） */
        val envelope: String,
        val response: IClassRawResponse,
        val signSay: List<String>,
        val signNotSay: List<String>,
        val loginSay: List<String>,
        val loginNotSay: List<String>,
    )

    /** 那一枚挂在查询串上的 id：假响应的体里故意把它写进了转跳地址，为的是抓住"凭证上屏" */
    private val credential = "2488752"

    private val unreadableRungs = listOf(
        UnreadableRung(
            name = "服务端把请求转走了（3xx）",
            envelope = "Redirected",
            response = IClassRawResponse(
                302,
                "text/html",
                """<a href="https://evil.example.com/p?from=8181&id=$credential">moved</a>""".toByteArray(Charsets.UTF_8),
            ),
            signSay = listOf("转走", "没跟过去"),
            signNotSay = listOf("成功", "服务端没有受理", "读回来", "evil.example.com", "https", "http", "id=", credential),
            loginSay = listOf("转走", "没有拿到 id"),
            loginNotSay = listOf("成功", "登录没有发生", "读回来", "evil.example.com", "https", "http", "id=", credential),
        ),
        UnreadableRung(
            name = "响应体没读回来（超时／中途断）",
            envelope = "BodyUnreadable",
            response = IClassRawResponse(200, null, null),
            signSay = listOf("没把", "读回来"),
            // 这一档最要紧的一格：它对服务端的答复一无所知，所以一个字都不许替服务端说
            signNotSay = listOf("服务端", "回了空", "空响应", "成功", "转走", "没有受理"),
            loginSay = listOf("没把", "读回来", "没有拿到 id"),
            loginNotSay = listOf("服务端", "回了空", "空响应", "成功", "转走", "登录没有发生"),
        ),
        UnreadableRung(
            name = "真空体（服务端回了零字节）",
            envelope = "EmptyBody",
            response = IClassRawResponse(200, null, ByteArray(0)),
            signSay = listOf("回了空响应", "没有任何受理的证据"),
            signNotSay = listOf("没有受理", "读回来", "转走", "成功"),
            loginSay = listOf("回了空响应", "没有拿到 id"),
            loginNotSay = listOf("登录没有发生", "读回来", "转走", "成功"),
        ),
    )

    /** 三档在签到那一路：逐格点名屏上第一行念的是谁 */
    @Test
    fun `三档假响应在签到那一路各念各的`() {
        for (rung in unreadableRungs) {
            val result = api.classifySign(rung.response)
            assertTrue("「${rung.name}」那一档本该失败，却判成了签成", result.isFailure)
            val message = failedMessage(result)
            for (needle in rung.signSay) {
                assertTrue("「${rung.name}」那句里没有「$needle」，读起来不像这一档：$message", message.contains(needle))
            }
            for (banned in rung.signNotSay) {
                assertFalse("「${rung.name}」那句替不该说话的一方说了话（含「$banned」）：$message", message.contains(banned))
            }
            assertTrue(
                "读不出结论的三档要落成 IClassUnexpectedResponseException（界面据此不给「去登录」出口）：${rung.name}",
                result.exceptionOrNull() is IClassUnexpectedResponseException,
            )
        }
    }

    /** 三档在登录那一路：同一张表的另外两格，落点是 IClassLoginScreen 那句「登录失败：…」 */
    @Test
    fun `三档假响应在登录那一路各念各的`() {
        for (rung in unreadableRungs) {
            val result = api.classifyLogin(rung.response)
            assertTrue("「${rung.name}」那一档本该失败，却把 id 交出去了", result.isFailure)
            assertNull("「${rung.name}」那一档交回了 id（等于拿一条读不懂的响应去建会话）", result.getOrNull())
            val message = failedMessage(result)
            for (needle in rung.loginSay) {
                assertTrue("「${rung.name}」那句里没有「$needle」，读起来不像这一档：$message", message.contains(needle))
            }
            for (banned in rung.loginNotSay) {
                assertFalse("「${rung.name}」那句替不该说话的一方说了话（含「$banned」）：$message", message.contains(banned))
            }
            assertTrue(
                "登录那一路三档都要落成 IClassLoginFailedException（登录页据它决定给不给重登表单）：${rung.name}",
                result.exceptionOrNull() is IClassLoginFailedException,
            )
        }
    }

    /**
     * 三档两两不折成同一句 —— 这一格钉的就是卡面乙那一笔本身。
     *
     * 从前 :97 那一折让「读不回来」与「真空体」交回同一个 `ByteArray(0)`，于是两档在屏上是同一句话；
     * 把那一折扭回来（变异臂 W）时，上面两枚逐格判据仍然全绿（它们各自喂的假响应没变），
     * **只有这一格会红**：它不认档位名，只认"两句话是不是同一句"。
     */
    @Test
    fun `读不回来与真空体与转走是三条不同的句子`() {
        val sign = unreadableRungs.map { failedMessage(api.classifySign(it.response)) }
        val login = unreadableRungs.map { failedMessage(api.classifyLogin(it.response)) }
        assertEquals("签到那三档折成了同一句（读失败又被当成空体）：", 3, sign.toSet().size)
        assertEquals("登录那三档折成了同一句：", 3, login.toSet().size)
        // 而"我们这侧断了"那一档与"服务端回了零字节"那一档必须能靠主语分开
        val unreadable = sign[1]
        val empty = sign[2]
        assertTrue("读不回来那句没有站在我们这侧：$unreadable", unreadable.contains("读回来") && !unreadable.contains("回了"))
        assertTrue("真空体那句没有站在服务端那一侧：$empty", empty.contains("回了空响应"))
    }

    /**
     * `classifyEnvelope` 交回的必须是那一档本身，不是"事后由措辞反推的一档"。
     *
     * 卡面 ⑤ 的朝窄臂（在册册子里摘掉一枚档位）红在这一格与 ③ 的名册闭合那一格上：
     * 档位被并掉时 message 可能还是那句人话，而档位名先没。
     */
    @Test
    fun `三档假响应各落回自己那一档`() {
        for (rung in unreadableRungs) {
            assertEquals("「${rung.name}」落错了档：", rung.envelope, api.classifyEnvelope(rung.response).javaClass.simpleName)
        }
    }

    /**
     * 3xx 的体里就算躺着一枚完整的"签成了"外壳，也不许捞出成功 —— 硬约束 2 的行为面。
     *
     * 这一格是"顺序即语义"的唯一实测面：把 3xx 那一判挪到解体之后，
     * 这一格当场从失败翻成成功（而它翻过去就是这条链上最贵的那枚假成功：用户不再核对，
     * 而课堂出勤记录其实没写上 —— 见 classifySign 的 KDoc）。
     */
    @Test
    fun `转走那一档不许从体里捞出成功`() {
        val signed = IClassRawResponse(
            302,
            null,
            """{"STATUS":0,"ERRCODE":"100","result":{"stuSignStatus":"1","id":"$credential"}}""".toByteArray(Charsets.UTF_8),
        )
        val sign = api.classifySign(signed)
        assertTrue("302 附带的外壳被当成签成了", sign.isFailure)
        assertTrue("那句没念「转走」这件事：${failedMessage(sign)}", failedMessage(sign).contains("转走"))
        val login = api.classifyLogin(signed)
        assertTrue("302 附带的外壳被当成拿到了 id", login.isFailure)
        assertNull("登录那一路从转跳响应里交回了 id（等于拿一条没跟过去的响应建会话）", login.getOrNull())
    }

    /** 读不回来排在状态码判档之前：一枚 500 而体没读回来时，说的是"没读回来"而不是"服务端没给原因" */
    @Test
    fun `读不回来那一档排在状态码之前`() {
        val unreadable = api.classifySign(IClassRawResponse(500, null, null))
        assertTrue("500 + 没读回来居然判成成功", unreadable.isFailure)
        assertEquals(
            "这一格该落 BodyUnreadable，而不是替服务端答「没给出业务原因」：",
            "没把 iClass 的响应体读回来（超时或连接中途断），没有任何受理的证据",
            failedMessage(unreadable),
        )
        // 反过来：500 且体读回来了，那一档仍然走状态码那一支（在册判据不许被新档顺手吃掉）
        val withBody = api.classifySign(body("""{"STATUS":0}""", status = 500))
        assertTrue("500 + 有体应当落 Unrecognized：${failedMessage(withBody)}", failedMessage(withBody).contains("500"))
    }

    /**
     * 三档经接线缝（`signIn` / `login` 各一枚假 transport）也各落各的，并且**只发一趟**。
     *
     * `assertEquals(1, seen.size)` 这一格管的是 API 那一侧：一次 `signIn` 只叫一次 `execute`。
     * transport 里面那一侧（平台会不会自己去跟那一跳）管不到，也不该由单测假装管到了 ——
     * 它由 ③ 那枚源码形状守卫钉 + 列入未验到（真机那一次 3xx 到底由谁发的）。
     */
    @Test
    fun `三档经签到与登录的接线缝各发且只发一趟`() {
        for (rung in unreadableRungs) {
            var calls = 0
            val fake = IClassTransport { _, _, _ -> calls++; rung.response }
            val signOutcome = runBlocking { IClassApi(fake).signIn(realCode, credential) }
            assertEquals("「${rung.name}」那一路签到发了 ${calls} 趟（本该恰好一趟）", 1, calls)
            assertTrue("「${rung.name}」那一路签到居然成功了", signOutcome.isFailure)
            assertEquals(
                "签到接线缝上的那句与档位表不是同一句：",
                failedMessage(api.classifySign(rung.response)),
                failedMessage(signOutcome),
            )

            var loginCalls = 0
            val loginFake = IClassTransport { _, _, _ -> loginCalls++; rung.response }
            val loginOutcome = runBlocking { IClassApi(loginFake).login("13800000000", "se cret") }
            assertEquals("「${rung.name}」那一路登录发了 ${loginCalls} 趟（本该恰好一趟）", 1, loginCalls)
            assertTrue("「${rung.name}」那一路登录居然拿到了 id", loginOutcome.isFailure)
            assertEquals(
                "登录接线缝上的那句与档位表不是同一句：",
                failedMessage(api.classifyLogin(rung.response)),
                failedMessage(loginOutcome),
            )
        }
    }

    /**
     * 凭证不上一条能被人读到的路：三档在两路上交回的消息里都不许出现 URL、host、端口、参数名与那枚 id。
     *
     * 3xx 那一格的体里故意写了 `https://evil.example.com/p?from=8181&id=…`：
     * 服务端给的转跳地址只要有一条被带进消息，它就顺着失败卡上屏、顺着 `Log.w` 进 logcat
     * （口径同 `ScanReject` 那一族「只有形状，没有原文也没有参数值」—— 那张码是滚动码，
     *  完整原文给出去等于把"还能再签一次"交给任何拿到这台设备的人）。
     */
    @Test
    fun `三档那六句话里一个字的凭证都没有`() {
        for (rung in unreadableRungs) {
            for (message in listOf(failedMessage(api.classifySign(rung.response)), failedMessage(api.classifyLogin(rung.response)))) {
                for (banned in listOf("iclass", "buaa", "8181", "8081", "evil", "http", "://", "courseSchedId", "timestamp", "id=", credential)) {
                    assertFalse("屏上那句话里带了「$banned」（凭证或地址外流）：$message", message.contains(banned))
                }
            }
        }
    }

    // ==== T349③：源码形状守卫（四枚钉法逐枚点名；锚点读不到就抛 —— 静默跳过的守卫比没有守卫更糟）====

    /** 本文件是全仓唯一一枚读 `IClassApi.kt` 的测试（现量 `grep -rl 'IClassApi.kt' app/src/test` ⇒ 1）⇒ 守卫并进这里，零新建文件 */
    private fun iClassApiSource(): String {
        val file = File(findMainJavaDir(), "com/buaa/schedule/data/import/IClassApi.kt")
        assertTrue("找不到 ${file.path}：文件挪过家的话本卡这几枚守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /**
     * 抹掉整行注释（保行号），只给"锚点是代码形状"的那几枚判据用。
     *
     * 为什么不照抄本仓那一族 `blankComments`：`CommentStrippingHelperShapeGuardTest` 那四枚写死值
     * 数的是「字符数组声明 ＋ 把那枚数组包回字符串的物化写法」那一枚**形状**（两枚针本文件一律不逐字引用，
     * 引用了就会把这枚文件推进该守卫 ④ 的"两栏读数不等"名册，替编排者动那把尺），
     * 照抄就等于替编排者把那四枚各 +1
     * （卡面明令不许朝上重钉）。这一枚换的是**另一种机制**：逐行丢弃 trim 之后以双斜杠、以星号、
     * 或以块注释起始那两字符开头的行 —— 不在那把尺的宇宙里。
     *
     * 覆盖面（诚实写在报错消息之外，别当它没有）：看不见**行尾**注释里的字。`IClassApi.kt` 盘上
     * 所有注释都是整行的，所以本卡那几枚锚全数抹得到；而本卡禁止的那几枚锚（`?: ByteArray(0)`、
     * `instanceFollowRedirects` 打回 true、读 `Location`）若哪天只出现在行尾注释里，这一枚抓不到 ——
     * 那一栏由下面第 ④ 枚（读**原文**的散文↔实现同栏守卫）兜住，它专门读散文。
     */
    private fun codeOnly(src: String): String = src.lines().joinToString("\n") { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) "" else line
    }

    /** 从锚点往后做到花括号配平，交回那一段源码（同 `IClassSignInWiringGuardTest` 那一族的刀法） */
    private fun blockAfter(source: String, anchor: String): String {
        val at = source.indexOf(anchor)
        check(at >= 0) { "找不到锚点「$anchor」：写法换过了，这一枚守卫要跟着改" }
        val open = source.indexOf('{', at)
        check(open >= at) { "「$anchor」之后找不到左括号" }
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(at, index + 1)
                }
            }
        }
        throw IllegalStateException("「$anchor」的花括号没配平")
    }

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    /** 取锚点所在那一整行；锚点读不到、或者在盘上不止一处，都直接抛（一枚逐字节判据不许同时比两支） */
    private fun lineWith(source: String, anchor: String): String {
        val hits = source.lines().filter { it.contains(anchor) }
        check(hits.isNotEmpty()) { "找不到锚点「$anchor」：那一支分支换写法了，本守卫要跟着改" }
        check(hits.size == 1) { "锚点「$anchor」在盘上有 ${hits.size} 处，逐字节判据不知道该比哪一支：\n${hits.joinToString("\n")}" }
        return hits[0]
    }

    /**
     * ① 钉法 = **数出现次数**（正向一枚、反向零枚）＋ **读形状**（transport 那一段里没有第二条出站的路）。
     *
     * 卡面甲那一笔的源码面：签到那一次 GET 的全部鉴权材料挂在查询串上，所以"不发第二趟"这件事
     * 只能由源码形状钉 —— 单测里那次 `assertEquals(1, calls)` 数的是 API 到 transport 那一跳，
     * transport **里面**平台自己去跟那一跳它看不见（列进未验到）。
     *
     * 朝宽判红：`instanceFollowRedirects` 扭回 `true` ⇒ 第二枚断言（0 枚）当场不成立；
     * 有人照 `update/UpdateCheck.kt` 那族自己逐跳 ⇒ `repeat(`/`while`/`getHeaderField(` 从 0 变正 ⇒ 红；
     * 有人把转跳地址读进结构 ⇒ `Location` 在代码里出现 ⇒ 红。
     * 朝窄判红：把那一行整枚删掉 ⇒ 正向锚从 1 变 0 ⇒ 红（`blockAfter` 读不到锚点直接抛，不会静默绿）。
     */
    @Test
    fun `签到那一路不发第二趟请求`() {
        val code = codeOnly(iClassApiSource())
        assertEquals("instanceFollowRedirects = false 在盘上的落点枚数：", 1, occurrences(code, "instanceFollowRedirects = false"))
        assertEquals("自动跟随不许以 true 的形式长回来：", 0, occurrences(code, "instanceFollowRedirects = true"))

        val transport = blockAfter(code, "override fun execute(url: String, method: String, formBody: String?): IClassRawResponse {")
        assertEquals("transport 里构造出站地址只许一次：", 1, occurrences(transport, "URL("))
        assertEquals("transport 里 openConnection 只许一次（一次 execute = 一趟请求）：", 1, occurrences(transport, "openConnection("))
        assertEquals("逐跳跟随的那类循环不许出现在 transport 里：", 0, occurrences(transport, "repeat(") + occurrences(transport, "while"))
        assertEquals("服务端给的转跳头一次都不读（读了就有了通往界面与 logcat 的路）：", 0, occurrences(transport, "getHeaderField("))
        assertEquals("Location 那三个字不许出现在代码里（只许出现在解释为什么不读它的散文里）：", 0, occurrences(transport, "Location"))
        // 语义归上层：transport 只交回原始事实。3xx 那一判若在 transport 里，就是同一张梯子写两遍（台账 #455 那一族）
        assertEquals("3xx 的判档住在 classifyEnvelope，不许在 transport 里各写一遍：", 0, occurrences(transport, "300..399"))
    }

    /**
     * ② 钉法 = **数出现次数**（被禁写法 0 枚）＋ **读形状**（三态各一枚、顺序逐枚比下标）。
     *
     * 卡面乙那一笔的源码面：`:97` 那一折把"读不回来"变成"零字节"，于是顺着空体判据落成一句关于
     * 服务端的断言。行为面扭不回来（② 那批格子喂的是手工假响应，不经过 transport），
     * 所以这一枚是**唯一**能听见那一折被扭回来的格子 —— 变异臂 W 就红在这里。
     *
     * 朝宽判红：把读失败重新折回 `?: ByteArray(0)` ⇒ 第一枚断言从 0 变正 ⇒ 红；
     * 把 `body` 收回非空 `ByteArray` ⇒ 第三枚断言红。
     * 朝窄判红：把 `parseEnvelope` 里那一枚空性再守删掉 ⇒ 第四枚断言红；
     * 把 3xx 那一判挪到解体之后 ⇒ 顺序那三枚下标断言红（顺序即语义）。
     */
    @Test
    fun `读体失败不许再折成空体`() {
        val code = codeOnly(iClassApiSource())
        assertEquals("被禁的折档写法（读失败 → 零字节）在盘上的枚数：", 0, occurrences(code, "?: ByteArray(0)"))
        assertEquals("ByteArray(0) 只许留在「连流都没给」那一档：", 1, occurrences(code, "ByteArray(0)"))
        assertEquals("体的三态必须由类型表达（非空 ByteArray 就折不回 null）：", 1, occurrences(code, "val body: ByteArray?"))
        assertEquals("classifyEnvelope 那一枚读不回来判档：", 1, occurrences(code, "if (response.body == null) return Envelope.BodyUnreadable"))
        assertEquals("parseEnvelope 自己也得守一次空性（不许留一条绕过判档就解体的路）：", 1, occurrences(code, "val bytes = response.body ?: return Envelope.BodyUnreadable"))

        val redirectedAt = code.indexOf("response.statusCode in 300..399")
        val unreadableAt = code.indexOf("response.body == null")
        val statusAt = code.indexOf("response.statusCode !in 200..299")
        val emptyAt = code.indexOf("text.isBlank()")
        check(redirectedAt >= 0 && unreadableAt >= 0 && statusAt >= 0 && emptyAt >= 0) { "四枚判据锚点读不全：梯子形状换过了，本守卫要跟着改" }
        assertTrue("顺序即语义：3xx 必须排在读体之前（$redirectedAt < $unreadableAt）", redirectedAt < unreadableAt)
        assertTrue("顺序即语义：读不回来必须排在状态码判档之前（$unreadableAt < $statusAt）", unreadableAt < statusAt)
        assertTrue("顺序即语义：状态码必须排在解体的空体判据之前（$statusAt < $emptyAt）", statusAt < emptyAt)
    }

    /**
     * ③ 钉法 = **读形状**（花括号配平切出 Envelope 那一段）＋ **数出现次数**（名册与分支逐枚对账）。
     *
     * 梯子的闭合是双向的：档位枚数、每一档在两枚穷尽 `when` 里各一支、册子（② 那张表）里的名字
     * 与盘上逐一同名、以及 Envelope 的 KDoc 里每一档都有自己那条 bullet —— 少任何一头都是"各说各话"。
     *
     * 朝窄判红：从 `Envelope` 摘掉一枚档位 ⇒ 3+4 那两枚枚数断言与名册断言同时红；
     * 从 ② 那张表里摘掉一行 ⇒ 册子那枚断言红（`assertEquals` 逐字比名字）；
     * 新加一档不补 KDoc ⇒ bullet 那一段红。
     * 朝宽判红：把某一档并到 EmptyBody 那一支（`when` 里少一支）⇒ 该档的分支枚数从 2 变 1；
     * 档位名换字面量而 KDoc 不动 ⇒ bullet 那一枚红。
     */
    @Test
    fun `梯子的档位与两路分支与册子与 KDoc 四方同名`() {
        val raw = iClassApiSource()
        val code = codeOnly(raw)
        val ladder = blockAfter(code, "sealed interface Envelope {")
        assertEquals("Envelope 里 data object 的枚数（EmptyBody / Redirected / BodyUnreadable）：", 3, occurrences(ladder, "data object "))
        assertEquals("Envelope 里 data class 的枚数（Accepted / Rejected / Framework / Unrecognized）：", 4, occurrences(ladder, "data class "))
        val rungs = Regex("""data (?:object|class) (\w+)""").findAll(ladder).map { it.groupValues[1] }.toList().sorted()
        assertEquals(
            "梯子七档逐字点名（少一档或多一档都算删判据）：",
            listOf("Accepted", "BodyUnreadable", "EmptyBody", "Framework", "Redirected", "Rejected", "Unrecognized"),
            rungs,
        )
        for (rung in rungs) {
            assertEquals(
                "档位「$rung」必须在签到与登录两枚穷尽 when 里各有一支（并支就是折档）：",
                2, occurrences(code, "Envelope.$rung ->"),
            )
        }
        // 册子（② 那张表）与盘上的档位名双向闭合：表里只登记本卡那三档，而这三档必须逐一同名
        assertEquals(
            "② 那张册子登记的档位名（顺序即表序）：",
            listOf("Redirected", "BodyUnreadable", "EmptyBody"),
            unreadableRungs.map { it.envelope },
        )
        assertTrue(
            "册子里的档位名必须在盘上梯子里读得到（改名不许静默换表）：",
            unreadableRungs.all { rung -> rung.envelope in rungs },
        )
        // KDoc 那一头：Envelope 的判据表里每一档都得有自己的那条 bullet（散文与判据不许各说各话）
        val docAt = raw.lastIndexOf("/**", raw.indexOf("sealed interface Envelope {"))
        check(docAt >= 0) { "读不到 Envelope 的 KDoc：判据表挪窝了，本守卫要跟着改" }
        val doc = raw.substring(docAt, raw.indexOf("sealed interface Envelope {"))
        for (rung in rungs) {
            assertTrue("Envelope 的 KDoc 里没有「$rung」那一档的那条 bullet（梯子长了档、判据表没跟上）：\n$doc", doc.contains("[Envelope.$rung]"))
        }
    }

    /**
     * ④ 钉法 = **读原文找锚点**（散文那一栏必须读得到，这一枚**不抹注释**）＋ **数出现次数**。
     *
     * 卡面点名的那句「`:56` 那句 KDoc 与实现不许再各说各话」落在这里：
     * `@return` 那一栏从前承诺"非 2xx 的取不到体时抛 IOException"，而实现（:97）既不抛也不分档 ——
     * 一句关于行为的散文站在一个不干那件事的代码旁边。这一枚同时读散文与代码，两边各钉一头。
     *
     * 朝宽判红：把 `instanceFollowRedirects` 扭回 `true` 而散文不改 ⇒ 代码那一头红；
     * 把 `@return` 那句改回旧承诺 ⇒ 散文那一头两枚断言红（旧句出现 + 新锚消失）；
     * 把"三条'没有'"写回来 ⇒ 最后一枚红（第四条不跟重定向是**现在时**的契约，散文退回就是假句）。
     */
    @Test
    fun `execute 的那句 KDoc 与实现同一栏`() {
        val raw = iClassApiSource()
        val code = codeOnly(raw)
        val contractAt = raw.indexOf("@return 原始响应")
        check(contractAt >= 0) { "读不到 execute 的 @return 那句：契约换了载体，本守卫要跟着改" }
        val contract = raw.substring(contractAt, raw.indexOf("fun execute(url: String", contractAt))
        assertTrue("@return 那一栏没写出「响应体读不回来交回 null」那一档：$contract", contract.contains("null"))
        assertTrue("@return 那一栏没写出 3xx 不跟过去：$contract", contract.contains("3xx") && contract.contains("不跟过去"))
        assertEquals(
            "旧的假承诺（非 2xx 取不到体时抛 IOException）不许回到散文里 —— 实现现在交回的是那一档本身：",
            0, occurrences(raw, "非 2xx 的取不到体"),
        )
        // 散文与代码各钉一头：谁单独被扭都不算绿
        assertEquals("代码那一头必须真的不跟：", 1, occurrences(code, "instanceFollowRedirects = false"))
        assertTrue("契约那一栏必须把第四条写出来（三条'没有'已经是假句）：", raw.contains("四条\"没有\""))
        assertEquals("三条'没有'那句现在时的契约不许长回来：", 0, occurrences(raw, "三条\"没有\""))
    }

    /**
     * ⑤ 钉法 = **逐字节**（六支分支的句子逐枚比）＋ **数出现次数**（两句关于服务端的旧断言在代码里 0 枚）。
     *
     * 卡面 ④ 那一档的源码面：`服务端没有受理这次签到` 与 `登录没有发生` 两句是关于服务端的断言，
     * 而它们各自的证据（一枚空体 / 一次没读回来的往返）都不支持那半句。
     * 盘上的散文里**留着**那两句（各自记着"从前写的是什么"）—— 那是过去时记录，按本仓规矩不抹；
     * 所以这一枚数的是**抹注释之后**的那一栏，比的是六支分支里逐字的句子。
     *
     * 朝宽判红：把任一档的句子扭回旧那句 ⇒ 逐字节比对方与 0 枚那两枚同时红；
     * 朝窄判红：把三档并成一句 ⇒ 逐字节那三组互斥红（与 ② 那枚"三条不同的句子"行为面同命）。
     */
    @Test
    fun `六支分支的句子各自逐字钉住而两句旧断言在代码里归零`() {
        val code = codeOnly(iClassApiSource())
        assertEquals("「服务端没有受理这次签到」在代码里的枚数（只许活在过去时的散文里）：", 0, occurrences(code, "服务端没有受理这次签到"))
        assertEquals("「登录没有发生」在代码里的枚数（同上）：", 0, occurrences(code, "登录没有发生"))
        val expected = mapOf(
            "Envelope.Redirected -> Result.failure(IClassUnexpectedResponseException(" to
                "iClass 把这次请求转走了（没跟过去，也不知道转去哪），没有任何受理的证据",
            "Envelope.BodyUnreadable -> Result.failure(IClassUnexpectedResponseException(" to
                "没把 iClass 的响应体读回来（超时或连接中途断），没有任何受理的证据",
            "Envelope.EmptyBody -> Result.failure(IClassUnexpectedResponseException(" to
                "iClass 回了空响应（没有任何受理的证据）",
            "Envelope.Redirected -> Result.failure(IClassLoginFailedException(" to
                "iClass 把这次登录请求转走了（没跟过去，也不知道转去哪），没有拿到 id",
            "Envelope.BodyUnreadable -> Result.failure(IClassLoginFailedException(" to
                "没把 iClass 的登录响应体读回来（超时或连接中途断），没有拿到 id",
            "Envelope.EmptyBody -> Result.failure(IClassLoginFailedException(" to
                "iClass 回了空响应（没有拿到 id）",
        )
        for ((branch, sentence) in expected) {
            val line = lineWith(code, branch)
            assertTrue("分支「$branch」那一支不是逐字的「$sentence」：$line", line.contains("\"$sentence\""))
        }
        assertEquals("六支分支枚数（三档 × 两条路）：", 6, occurrences(code, "Envelope.Redirected -> Result.failure(") + occurrences(code, "Envelope.BodyUnreadable -> Result.failure(") + occurrences(code, "Envelope.EmptyBody -> Result.failure("))
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
