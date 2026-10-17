package com.buaa.schedule.domain.signin

import com.buaa.schedule.data.import.IClassApi
import com.buaa.schedule.data.import.IClassLoginFailedException
import com.buaa.schedule.data.import.IClassRawResponse
import com.buaa.schedule.data.import.IClassRejectedException
import com.buaa.schedule.data.import.IClassTransport
import com.buaa.schedule.data.import.IClassUnexpectedResponseException
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T404②：表驱动打表 `domain/signin/SubmitFailure`（#696 第⓪问裁的甲档：递交这一侧走同一把尺）。
 *
 * ## 这张表问的是什么
 *
 * 「这一发签到在本机断成了什么形状，屏上那一句该念谁的话」。九档里只有一档（SpokenVerbatim）念的是
 * **别人已经写好的那句**（厂商 ERRMSG 逐字 ＋ `classifySign` 那七档本仓自己写好的句子），其余八档各念
 * 本机这一趟看得见的事。题眼是那一枚契约（[LocallySpokenFailure]）而不是消息文本的形状：
 * `SocketTimeoutException("Read timed out")` 与 `IClassRejectedException("参数错误!")` 改前走的是
 * 同一句 `error.message`，改后必须落进**不同的档**：后者逐字不动，前者换成中文档且一个英文字节都不许跟过去。
 *
 * ## 为什么梯子不新立一枚（本卡的第⓪问裁定）
 *
 * [SubmitFailureClassifier.rungOf] 整颗转发给 [LoginFailureClassifier.rungOf]，档位是同一枚
 * [LoginFailureRung]。本文件 ④ 那格守的就是这件事的另一头：**句子表是两颗**，
 * 登录那八句每一句尾巴都写「没有拿到 id」，签到这一趟的本地后果不是那件事 ——
 * 把登录的句子搬到签到卡上就是新的一页假话，而再刻一把继承判据梯就是全仓第二把尺。
 * 于是每一格都两枚参照物都取：同一枚输入既问递交这一侧、也问登录那一侧，两头各钉各的话。
 *
 * ## 输入面
 *
 * 纯 JVM：判据只吃 `java.lang.Throwable` 与它的继承形状，表里那些异常全在本进程里手工造出来
 * （零 Robolectric、零设备、零时钟、零网络、零 adb，不发一次真请求、不猜口令、不生成 timestamp）。
 * 接线缝用假 transport（`IClassTransport { _, _, _ -> throw … }`），一次 `execute` 数一枚。
 *
 * ## 钉不住的行为（明说，不许用「覆盖了」这个词）
 *
 * 本文件钉的是**档位表＋两张句子表的分家＋递交那一手的复投**。它不证明真机上究竟抛出过哪几档
 *（表里那些 JVM 类名的出现频率一条都没取证到）、不证明 `HttpURLConnectionIClassTransport` 在哪一档
 * 会抛哪一枚、不证明那张失败卡被画出来之后的样子（那是组合期的事，本模块没有 compose 测试规则），
 * 也不证明取消到底能不能进到递交那一手（零装机、在册样本 0 枚）—— ③ 那一格钉的是
 * "假如进到了就必须原样复投"这一形状，不是"这条路真走过"。
 */
class SubmitFailureRungTableTest {

    /** 一格三样东西：场景名、手工造出来的那一枚 Throwable、它必须落进的那一档 */
    private data class Row(
        val label: String,
        val cause: Throwable,
        val expected: LoginFailureRung,
    )

    private val rows = listOf(
        Row("厂商 ERRMSG 的逐字原文（这一族唯一的真信息）", IClassRejectedException("参数错误!"), LoginFailureRung.SpokenVerbatim),
        Row("classifySign 按外壳档位写好的那七句之一", IClassUnexpectedResponseException("iClass 回了空响应（没有任何受理的证据）"), LoginFailureRung.SpokenVerbatim),
        Row("stuSignStatus 没给 1 那一档", IClassUnexpectedResponseException("iClass 未确认签到完成（stuSignStatus=缺失）"), LoginFailureRung.SpokenVerbatim),
        Row("这一发在本机被收回（那一页离场，或这一次请求被取消）", CancellationException("Job was cancelled"), LoginFailureRung.WithdrawnLocally),
        Row("本机硬故障（不是签到本身的事）", StackOverflowError("StackOverflowError message"), LoginFailureRung.LocalHardFault),
        Row("等回话等到超时", SocketTimeoutException("Read timed out"), LoginFailureRung.RoundTripTimedOut),
        Row("握手没过（SSLException 的子类）", SSLHandshakeException("handshake failed"), LoginFailureRung.SecureChannelNotSettled),
        Row("证书没认下", CertificateException("no trusted certificate found"), LoginFailureRung.SecureChannelNotSettled),
        Row("地址没被认出来（message 里就带着 host）", UnknownHostException("iclass.buaa.edu.cn"), LoginFailureRung.AddressNotResolved),
        Row("端口没接上（message 里带着 host、IP 与端口）", ConnectException("Failed to connect to iclass.buaa.edu.cn/198.51.100.7:8181"), LoginFailureRung.ConnectionNotBuilt),
        Row("路上不通", NoRouteToHostException("Host is unreachable"), LoginFailureRung.ConnectionNotBuilt),
        Row("端口整片不通", PortUnreachableException("Port unreachable"), LoginFailureRung.ConnectionNotBuilt),
        Row("往返半路断了", SocketException("Connection reset"), LoginFailureRung.RoundTripDroppedMidway),
        Row("读到一半流就没了", EOFException("unexpected end of stream"), LoginFailureRung.RoundTripDroppedMidway),
        Row("读被中断（超时的超类，必须排在超时之后）", InterruptedIOException("thread interrupted"), LoginFailureRung.RoundTripDroppedMidway),
        Row("我们没登记过的形状（状态不对）", IllegalStateException("already connected"), LoginFailureRung.FailureShapeUnrecognized),
        Row("一枚赤的 IOException（`IClassApiTest` 在册那格喂的就是它）", IOException("连不上"), LoginFailureRung.FailureShapeUnrecognized),
        Row("URL 本身不成形（扫码原文千奇百怪的那一档）", java.net.MalformedURLException("unknown protocol: ip"), LoginFailureRung.FailureShapeUnrecognized),
    )

    /** 八档本机句子里一律不许出场的英文 token（异常原文的形状，不是档位句的形状） */
    private val englishLeakNeedles = listOf(
        "Read timed",
        "timeout",
        "Timeout",
        "Connection",
        "connect",
        "Socket",
        "SSL",
        "Handshake",
        "host",
        "Stream",
        "interrupted",
        "Exception",
        "Error",
        "protocol",
        "URL",
    )

    /** 任何一句屏上的话与取证行都不许带场的凭证 */
    private val credentialNeedles = listOf(
        "http",
        "://",
        "8181",
        "8081",
        "buaa",
        ".cn",
        "iclass",
        "id=",
        "2488752",
        "198.51.100.7",
        "stu_scan_sign",
        "timestamp",
    )

    /** 这一族屏上不许出现的"替服务端答"那两句（口径同 IClassApi 的 EmptyBody 那一段） */
    private val forbiddenClaims = listOf("服务端没有受理", "没有签上", "签到没有成功", "肯定没签成")

    // ---------------- ① 每一档至少一格，且名册闭合 ----------------

    /** 全表逐格问两遍：每一格必须落在自己那一档，九档一枚不许缺席；每一格屏上都得说得出人话 */
    @Test
    fun `每一档至少一格 九档全被命中过`() {
        for (row in rows) {
            assertEquals(describe(row), row.expected, SubmitFailureClassifier.rungOf(row.cause))
            val line = SubmitFailureClassifier.screenLine(row.cause)
            assertTrue("${describe(row)} —— 屏上那一句是空的", line.isNotBlank())
            for (needle in credentialNeedles) {
                assertFalse("${describe(row)} —— 屏上那一句带了凭证「$needle」：$line", line.contains(needle))
            }
            if (row.expected == LoginFailureRung.SpokenVerbatim) {
                val spoken = (row.cause as LocallySpokenFailure).spokenLine
                if (spoken.isNotBlank()) {
                    assertEquals("${describe(row)} —— 逐字那一档不许改写字面量", spoken, line)
                }
            } else {
                for (needle in englishLeakNeedles) {
                    assertFalse("${describe(row)} —— 本机档位句带了 JVM 原文「$needle」：$line", line.contains(needle))
                }
                assertTrue("${describe(row)} —— 那一档没交代本地这一侧的证据状况：$line", line.contains("没有任何受理的证据"))
            }
        }
        val hit = rows.map { it.expected.name }.distinct().sorted()
        val declared = LoginFailureRung.values().map { it.name }.sorted()
        assertEquals(
            buildString {
                append("这张表要把九档都命中过（实到 ")
                append(hit.size)
                append(" 档，册子：")
                append(hit.joinToString())
                append("）：少一档就是有一档只活在 KDoc 里，卡上永远不会念到它")
            },
            declared,
            hit,
        )
        assertEquals(
            buildString {
                append("档位枚数（实到 ")
                append(LoginFailureRung.values().size)
                append(" 枚）：本卡一枚不加、一枚不并 —— 并档就是把两种现场念成同一句话")
            },
            9,
            LoginFailureRung.values().size,
        )
    }

    // ---------------- ② 题眼：本仓与服务端写好的句子 与 传输层的英文 各落一档 ----------------

    /**
     * 题眼第一格。两枚都从 `signIn` 那一路交回界面：一枚自带中文（厂商 ERRMSG 逐字），
     * 一枚是 JVM 抛的英文 —— 改前它们走的是同一句 `error.message`。
     */
    @Test
    fun `题眼格 服务端原话与传输层英文各落一档`() {
        val server = IClassRejectedException("参数错误!")
        val transport = SocketTimeoutException("Read timed out")
        assertNotEquals(
            buildString {
                append("两枚落进了同一档（实到 ")
                append(SubmitFailureClassifier.rungOf(server))
                append("）：这一枚分岔就是本卡的题眼")
            },
            SubmitFailureClassifier.rungOf(server),
            SubmitFailureClassifier.rungOf(transport),
        )
        assertEquals(LoginFailureRung.SpokenVerbatim, SubmitFailureClassifier.rungOf(server))
        assertEquals(LoginFailureRung.RoundTripTimedOut, SubmitFailureClassifier.rungOf(transport))
        assertEquals(
            "服务端那一句必须逐字在场（不许被折成通用句）：",
            "参数错误!",
            SubmitFailureClassifier.screenLine(server),
        )
        val folded = SubmitFailureClassifier.screenLine(transport)
        assertNotEquals(
            buildString {
                append("传输层那枚的原文没被换掉（实到 ")
                append(folded)
                append("）")
            },
            "Read timed out",
            folded,
        )
        assertTrue("折出来的那句必须是超时这一档自己的中文：$folded", folded.contains("超时"))
        for (needle in englishLeakNeedles + credentialNeedles) {
            assertFalse("折出来的那句带了「$needle」：$folded", folded.contains(needle))
        }
    }

    /**
     * ②b 双侧格（同一枚输入在两页各有一格）：这一格是本卡"两枚参照物都取"的那一对。
     *
     * 同一枚 `SocketTimeoutException` 递给登录那一侧与递交这一侧：档**必须相同**（一把判据梯），
     * 屏上那一句**必须不同**（两页的本地后果不是一件事 —— 登录断了的后果是"没有拿到 id"，
     * 递交断了的后果是"没有任何受理的证据"）。两头各钉一次，任何一头被合成另一头都当场红。
     */
    @Test
    fun `双侧格 同一枚超时在两页各说各的后果`() {
        val transport = SocketTimeoutException("Read timed out")
        assertEquals(
            "档位必须同源（判据梯只许一把）：",
            LoginFailureClassifier.rungOf(transport),
            SubmitFailureClassifier.rungOf(transport),
        )
        val loginLine = LoginFailureClassifier.screenLine(transport)
        val submitLine = SubmitFailureClassifier.screenLine(transport)
        assertNotEquals("两页念成了同一句话（句子表被合成一颗 = 其中一页在说另一页的事）：", loginLine, submitLine)
        assertTrue("登录那一句的后果半句丢了：$loginLine", loginLine.contains("没有拿到 id"))
        assertTrue("递交那一句的后果半句丢了：$submitLine", submitLine.contains("没有任何受理的证据"))
        assertFalse("递交那一句里混进了登录的后果：$submitLine", submitLine.contains("没有拿到 id"))
        assertFalse("递交那一句里出现了「登录」这件事：$submitLine", submitLine.contains("登录"))
        // 反向那一头：登录页也不许被搬到递交侧的句子上（第二枚参照物）
        val rejected = IClassRejectedException("签到已结束")
        assertEquals("签到这一侧逐字：", "签到已结束", SubmitFailureClassifier.screenLine(rejected))
        val loginSameRung = IClassLoginFailedException("用户名或密码错误")
        assertEquals("登录这一侧逐字：", "用户名或密码错误", LoginFailureClassifier.screenLine(loginSameRung))
    }

    /** ②c 名册里每一档的句子都必须**互不相同**（并句就是把两种现场念成一句话） */
    @Test
    fun `九档句子逐档互不相同`() {
        val lines = LoginFailureRung.values().map { rung ->
            SubmitFailureClassifier.screenLine(sampleOf(rung))
        }
        assertEquals("九档句子实到 ${lines.distinct().size} 枚不同，写死 9 枚：", 9, lines.distinct().size)
    }

    // ---------------- ③ 接线缝：递交那一手交回原文，而取消原样复投 ----------------

    /**
     * 在册那枚格子（`IClassApiTest.传输层抛出来的是失败而不是异常`）钉的是"签到那一路交回原文"。
     * 本卡把折档搬到界面那一头，所以**这一格必须照旧绿**：API 那一层一个字的加工都不许做。
     */
    @Test
    fun `签到那一趟交回的是原文而不是折过的句子`() {
        val fake = IClassTransport { _, _, _ -> throw IOException("连不上") }
        val outcome = runBlocking { IClassApi(fake).signIn(realCode, "88") }
        val error = requireNotNull(outcome.exceptionOrNull()) { "签到那一路没把传输层抛出来的一枚交回调用点" }
        assertEquals("交回的必须是那一枚本身（不许换成一句中文）：", "连不上", error.message)
        assertTrue("不许在数据层就把它包成 LocallySpokenFailure：", error !is LocallySpokenFailure)
        assertEquals(
            "而界面那一头吃的就是这一枚原文，落进档位表：",
            LoginFailureRung.FailureShapeUnrecognized,
            SubmitFailureClassifier.rungOf(error),
        )
    }

    /**
     * ③b 取消那一型**不许**被折成一次签到结果（#696 第二枚的另一半，形状抄 T397①）。
     *
     * 覆盖面（诚实写明）：这一格钉的是"假如取消进到递交这一手就必须原样复投"这一形状。
     * 取消究竟能不能进到手（零装机、在册样本 0 枚）不在这一格的射程里。
     */
    @Test
    fun `取消在递交那一手原样复投而不折成一次签到结果`() {
        val cancellation = CancellationException("Job was cancelled")
        var calls = 0
        val api = IClassApi(IClassTransport { _, _, _ -> calls++; throw cancellation })
        val thrown = assertThrows(CancellationException::class.java) { runBlocking { api.signIn(realCode, "88") } }
        assertEquals("递交那一手发了 $calls 趟（本该恰好一趟）：", 1, calls)
        val carried = thrown === cancellation ||
            thrown.cause === cancellation ||
            thrown.suppressed.any { it === cancellation }
        assertTrue(
            buildString {
                append("交回调用点的这一枚里找不到原来那一枚取消（实到类型 ")
                append(thrown.javaClass.name)
                append("、message ")
                append(thrown.message)
                append("）：换了本体又丢了原来那一枚，就是又折了一层")
            },
            carried,
        )
        assertEquals(
            "两半不许合成一半：判档仍住在内核（实到 ${SubmitFailureClassifier.rungOf(cancellation)}）",
            LoginFailureRung.WithdrawnLocally,
            SubmitFailureClassifier.rungOf(cancellation),
        )
    }

    /** ③c 外壳那七档的句子照旧逐字走到卡上（本卡不许吃掉那条真信息） */
    @Test
    fun `外壳七档的句子经递交接线缝仍逐字`() {
        val cases = listOf(
            """{"STATUS":1,"ERRCODE":"100","ERRMSG":"签到已结束"}""" to "签到已结束",
            """{"STATUS":0,"ERRCODE":"100","result":{"stuSignStatus":"0"}}""" to "iClass 未确认签到完成（stuSignStatus=0）",
            """{"ERRCODE":"100"}""" to "响应里没有可判定的 STATUS",
        )
        for ((json, expected) in cases) {
            val response = IClassRawResponse(200, "application/json;charset=UTF-8", json.toByteArray(Charsets.UTF_8))
            val error = requireNotNull(IClassApi().classifySign(response).exceptionOrNull())
            assertEquals("那一档的句子被改写了：", expected, error.message)
            assertEquals("屏上必须逐字念那一枚自带的那一句：", expected, SubmitFailureClassifier.screenLine(error))
        }
    }

    // ---------------- ④ 纯度：判据本体零 android，且梯子不新刻一把 ----------------

    /**
     * 钉法 = **数出现次数**：新内核里 `is SocketTimeoutException` 那类继承判据 **0 枚**，
     * 而它对 [LoginFailureClassifier.rungOf] 的转发恰好 1 枚。
     * 抓的是"顺手又刻一把梯"：那一手在屏上看不出来（两份判据今天同读数），
     * 而 drift 从这一格复活（同 `LoginFailureRungTableTest` ④ 那一族的理由）。
     */
    @Test
    fun `内核零android而判据梯全仓只有一把`() {
        val kernel = codeOnly(sourceFile(KERNEL_FILE))
        val imports = kernel.lines().map(String::trim).filter { it.startsWith("import ") }
        assertTrue("判据文件里出现了 import，这段判据就到不了 JVM：\n$imports", imports.isEmpty())
        for (banned in listOf("android.", "androidx.", "SystemClock", "System.currentTimeMillis", "Date(", "Instant", "LocalDateTime", "Build.VERSION")) {
            assertFalse("判据本体自己去碰了设备/时钟（$banned）", kernel.contains(banned))
        }
        assertTrue("内核文件是空的？", kernel.length > 1_000)
        for (needle in listOf(
            "is SocketTimeoutException",
            "is SSLException",
            "is CertificateException",
            "is UnknownHostException",
            "is ConnectException",
            "is SocketException",
            "is InterruptedIOException",
            "is EOFException",
            "is Error",
            "is CancellationException",
        )) {
            assertEquals("新内核里又刻了一枚继承判据「$needle」（全仓第二把尺）：", 0, occurrences(kernel, needle))
        }
        assertEquals("转发给在册那一把梯的落点只许一处：", 1, occurrences(kernel, "LoginFailureClassifier.rungOf"))
        assertEquals(
            "契约那一枚判据在本文件只许被读两下（折档那一问与取证行的「逐字=」各一次，同一枚契约不是第二把尺）：",
            2,
            occurrences(kernel, "is LocallySpokenFailure"),
        )
    }

    // ---------------- ⑤ 措辞纪律：每一档自己说话，一个字的凭证都不外流 ----------------

    /** 八档本机句子逐档核：都交代"没有任何受理的证据"、都不带凭证、都不替服务端答 */
    @Test
    fun `八档句子各说各的而不带一个字的凭证`() {
        val lines = LoginFailureRung.values()
            .filter { it != LoginFailureRung.SpokenVerbatim }
            .map { rung -> SubmitFailureClassifier.screenLine(sampleOf(rung)) }
        assertEquals("本机句子应当有 8 句：", 8, lines.size)
        for (line in lines) {
            for (needle in credentialNeedles + englishLeakNeedles) {
                assertFalse("屏上那一句带了「$needle」：$line", line.contains(needle))
            }
            for (banned in forbiddenClaims) {
                assertFalse("那一句替服务端答了「$banned」：$line", line.contains(banned))
            }
            assertTrue("那一句没交代本地这一侧的证据状况：$line", line.contains("没有任何受理的证据"))
        }
    }

    /** 逐字那一档没留下句子时说这句实话，不许借通用档的句子兜过去；取证行只交回形状 */
    @Test
    fun `逐字档没留下句子时说实话而取证行只交回形状`() {
        val blank = IClassRejectedException("   ")
        val line = SubmitFailureClassifier.screenLine(blank)
        assertTrue("没说出这是我们自己漏写的那一句：$line", line.contains("在本仓没有留下句子"))
        assertFalse("兜底到了通用档那一句：$line", line.contains("往返"))
        val folded = SubmitFailureClassifier.forensicsLine(SocketTimeoutException("Read timed out"))
        assertTrue("取证行没念档位：$folded", folded.contains("RoundTripTimedOut"))
        assertTrue("取证行没念类名：$folded", folded.contains("SocketTimeoutException"))
        assertTrue("取证行没写明不记录原文：$folded", folded.contains("异常原文=未记录"))
        for (needle in credentialNeedles) {
            assertFalse("取证行带了「$needle」：$folded", folded.contains(needle))
        }
        val verbatim = SubmitFailureClassifier.forensicsLine(IClassRejectedException("参数错误!"))
        assertTrue("逐字那一档的取证行没认出那是本仓/服务端的话：$verbatim", verbatim.contains("逐字=是"))
        assertFalse("取证行把服务端那句话抄进了日志：$verbatim", verbatim.contains("参数错误"))
    }

    // ---------------- 小工具（读形状 + 数出现次数；本文件不引入字符数组助手的那枚形状）----------------

    /** 每一档取一枚表里现成的样本（不许为格子造第二份判据） */
    private fun sampleOf(rung: LoginFailureRung): Throwable =
        requireNotNull(rows.firstOrNull { it.expected == rung }) { "名册里少了 $rung 那一档的样本格" }.cause

    private val realCode =
        "http://iclass.buaa.edu.cn:8081/app/course/stu_scan_sign.action?courseSchedId=2488752&timestamp=1790247391994"

    private fun describe(row: Row): String = buildString {
        append(row.label)
        append(" —— 抛的是 ")
        append(row.cause.javaClass.simpleName)
        append("，期望档 ")
        append(row.expected)
        append("，实到档 ")
        append(SubmitFailureClassifier.rungOf(row.cause))
    }

    private fun sourceFile(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 ${file.path}：文件挪过家的话本卡那几枚形状格要跟着改路径", file.isFile)
        return file.readText()
    }

    /** 逐行丢掉注释行（保行号）：与枚数尺那一族的字符数组助手是**另一种机制**，不在那把尺的宇宙里 */
    private fun codeOnly(src: String): String = src.lines().joinToString("\n") { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) "" else line
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

    private companion object {
        const val KERNEL_FILE = "com/buaa/schedule/domain/signin/SubmitFailure.kt"
    }
}
