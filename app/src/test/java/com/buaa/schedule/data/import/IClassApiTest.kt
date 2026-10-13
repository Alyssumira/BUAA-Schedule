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
