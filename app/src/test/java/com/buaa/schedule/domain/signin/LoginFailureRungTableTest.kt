package com.buaa.schedule.domain.signin

import com.buaa.schedule.data.import.IClassApi
import com.buaa.schedule.data.import.IClassLoginFailedException
import com.buaa.schedule.data.import.IClassRawResponse
import com.buaa.schedule.data.import.IClassTransport
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
 * T392②：表驱动打表 `domain/signin/LoginFailure`（第⓪问裁的甲档新内核），外加登录那一路的接线缝。
 *
 * ## 这张表问的是什么
 *
 * 「登录这一趟在本机断成了什么形状，屏上那一句该念谁的话」。九档里只有一档（SpokenVerbatim）念的是
 * **别人已经写好的那句**（厂商 ERRMSG 逐字、本仓按外壳档位写好的六句、空凭据那一拒），其余八档各念
 * 本机这一趟看得见的事。题眼是那一枚契约（LocallySpokenFailure）而不是消息文本的形状：
 * `IClassApi` 的 `login()` 里那支 `runCatching` 同时装着两种抛法 —— 「手机号与口令都要填」是我们自己
 * 抛的中文，`Read timed out` 是传输层抛的英文，改前它们从同一句 `error.message` 走到用户眼前；
 * 改后两枚必须落进**不同的档**：前者逐字不动，后者换成中文档且一个英文字节都不许跟过去。
 *
 * ## 输入面
 *
 * 纯 JVM：判据只吃 `java.lang.Throwable` 与它的继承形状，表里那些异常全在本进程里手工造出来
 * （零 Robolectric、零设备、零时钟、零网络、零 adb，不发一次真请求、不猜口令、不生成 timestamp）。
 * 接线缝用假 transport（`IClassTransport { _, _, _ -> throw … }`），一次 `execute` 数一枚。
 *
 * ## 钉不住的行为（明说，不许用「覆盖了」这个词）
 *
 * 本文件钉的是**档位表 + 折档那一手 + 复投那一手**（最后一枚是 T397② 追加的接线守卫格）。
 * 它不证明真机上究竟抛出过哪几档（表里那些 JVM 类名的出现频率
 * 一条都没取证到）、不证明 `HttpURLConnectionIClassTransport` 在哪一档会抛哪一枚、不证明屏上那一句
 * 被画出来之后的样子（那是组合期的事，本模块没有 compose 测试规则），也不证明签到那一路（`signIn`）
 * 的话 —— 那一路本卡一字未动，同族缺口登记给编排者与另一枚只读审计卡。
 * ⚠️ T397② 那一格额外不证明的一件事也得写在这里：**取消到底能不能进到登录那一手**（零装机、
 * 在册样本 0 枚，`loginAttempt` 又是非挂起函数）—— 那一格钉的是"假如进到了就必须原样复投"这一形状，
 * 不是"这条路真走过"。
 */
class LoginFailureRungTableTest {

    /** 一格三样东西：场景名、手工造出来的那一枚 Throwable、它必须落进的那一档 */
    private data class Row(
        val label: String,
        val cause: Throwable,
        val expected: LoginFailureRung,
    )

    private val rows = listOf(
        Row(
            "空凭据那一拒（本仓自己抛的中文）",
            IClassLoginFailedException("手机号与口令都要填"),
            LoginFailureRung.SpokenVerbatim,
        ),
        Row(
            "服务端给的 ERRMSG 原文（逐字包进异常里）",
            IClassLoginFailedException("用户名或密码错误"),
            LoginFailureRung.SpokenVerbatim,
        ),
        Row(
            "classifyLogin 按外壳档位写好的那六句之一",
            IClassLoginFailedException("iClass 回了空响应（没有拿到 id）"),
            LoginFailureRung.SpokenVerbatim,
        ),
        Row("这一趟在本机被收回（那一页离场）", CancellationException("Job was cancelled"), LoginFailureRung.WithdrawnLocally),
        Row("本机硬故障（不是登录本身的事）", StackOverflowError("StackOverflowError message"), LoginFailureRung.LocalHardFault),
        Row("等回话等到超时", SocketTimeoutException("Read timed out"), LoginFailureRung.RoundTripTimedOut),
        Row("握手没过（SSLException 的子类）", SSLHandshakeException("handshake failed"), LoginFailureRung.SecureChannelNotSettled),
        Row("证书没认下", CertificateException("no trusted certificate found"), LoginFailureRung.SecureChannelNotSettled),
        Row("地址没被认出来（message 里就带着 host）", UnknownHostException("iclass.buaa.edu.cn"), LoginFailureRung.AddressNotResolved),
        Row(
            "端口没接上（message 里带着 host、IP 与端口）",
            ConnectException("Failed to connect to iclass.buaa.edu.cn/198.51.100.7:8181"),
            LoginFailureRung.ConnectionNotBuilt,
        ),
        Row("路上不通", NoRouteToHostException("Host is unreachable"), LoginFailureRung.ConnectionNotBuilt),
        Row("端口整片不通", PortUnreachableException("Port unreachable"), LoginFailureRung.ConnectionNotBuilt),
        Row("往返半路断了", SocketException("Connection reset"), LoginFailureRung.RoundTripDroppedMidway),
        Row("读到一半流就没了", EOFException("unexpected end of stream"), LoginFailureRung.RoundTripDroppedMidway),
        Row("读被中断（超时的超类，必须排在超时之后）", InterruptedIOException("thread interrupted"), LoginFailureRung.RoundTripDroppedMidway),
        Row("我们没登记过的形状（状态不对）", IllegalStateException("already connected"), LoginFailureRung.FailureShapeUnrecognized),
        Row("一枚赤的 IOException（签到那一路逐字念它，这一路折它）", IOException("连不上"), LoginFailureRung.FailureShapeUnrecognized),
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
    )

    /** 任何一句屏上的话与取证行都不许带场的凭证 */
    private val credentialNeedles = listOf("http", "://", "8181", "8081", "buaa", ".cn", "iclass", "id=", "2488752", "198.51.100.7")

    // ---------------- ① 每一档至少一格，且名册闭合 ----------------

    /** 全表逐格问一遍：每一格必须落在自己那一档，九档一枚不许缺席 */
    @Test
    fun `每一档至少一格 九档全被命中过`() {
        for (row in rows) {
            assertEquals(describe(row), row.expected, LoginFailureClassifier.rungOf(row.cause))
        }
        val hit = rows.map { it.expected.name }.distinct().sorted()
        val declared = LoginFailureRung.values().map { it.name }.sorted()
        assertEquals(
            buildString {
                append("这张表要把九档都命中过（实到 ")
                append(hit.size)
                append(" 档，册子：")
                append(hit.joinToString())
                append("）：少一档就是有一档只活在 KDoc 里，屏上永远不会念到它")
            },
            declared,
            hit,
        )
        assertEquals(
            buildString {
                append("档位枚数（实到 ")
                append(LoginFailureRung.values().size)
                append(" 枚）：本卡一档不加一档不并 —— 并档就是把两种现场念成同一句话")
            },
            9,
            LoginFailureRung.values().size,
        )
    }

    // ---------------- ② 题眼：我们自己抛的中文 与 传输层抛的英文 各落一档 ----------------

    /**
     * 题眼第一格。两枚都从 `login()` 那同一支 `catch` 里出来：一枚是我们自己写的中文（loginAttempt
     * 那一拒），一枚是 JVM 抛的英文 —— 改前它们走的是同一句 `error.message`，中文那档被英文那档拖着
     * 一起糊。这一格钉的就是**不许再糊**：不同的档、不同的句子，而且我们那一句改后仍然逐字在场。
     */
    @Test
    fun `题眼格 我们自己的中文与传输层的英文各落一档`() {
        val ours = IClassLoginFailedException("手机号与口令都要填")
        val transport = SocketTimeoutException("Read timed out")
        assertNotEquals(
            buildString {
                append("两枚落进了同一档（实到 ")
                append(LoginFailureClassifier.rungOf(ours))
                append("）：这一枚分岔就是本卡的题眼")
            },
            LoginFailureClassifier.rungOf(ours),
            LoginFailureClassifier.rungOf(transport),
        )
        assertEquals(LoginFailureRung.SpokenVerbatim, LoginFailureClassifier.rungOf(ours))
        assertEquals(LoginFailureRung.RoundTripTimedOut, LoginFailureClassifier.rungOf(transport))
        assertEquals(
            "我们自己抛的那一句必须逐字在场（不许被折成通用句）：",
            "手机号与口令都要填",
            LoginFailureClassifier.screenLine(ours),
        )
        val folded = LoginFailureClassifier.screenLine(transport)
        assertNotEquals(
            buildString {
                append("传输层那枚的原文没被换掉（实到 ")
                append(folded)
                append("）")
            },
            "Read timed out",
            folded,
        )
        assertTrue(
            buildString {
                append("折出来的那句必须是超时这一档自己的中文：")
                append(folded)
            },
            folded.contains("超时"),
        )
        for (needle in englishLeakNeedles + credentialNeedles) {
            assertFalse(
                buildString {
                    append("屏上那句带了「")
                    append(needle)
                    append("」：")
                    append(folded)
                },
                folded.contains(needle),
            )
        }
    }

    /** 题眼第二格：服务端原话与本机那一档也是两档（原话不许被折，档位句也不许冒充原话） */
    @Test
    fun `题眼格 服务端原话与本机档位句各一档`() {
        val serverSaid = IClassLoginFailedException("用户名或密码错误")
        val localSaid = UnknownHostException("iclass.buaa.edu.cn")
        assertEquals(LoginFailureRung.SpokenVerbatim, LoginFailureClassifier.rungOf(serverSaid))
        assertEquals(LoginFailureRung.AddressNotResolved, LoginFailureClassifier.rungOf(localSaid))
        assertNotEquals(LoginFailureRung.SpokenVerbatim, LoginFailureClassifier.rungOf(localSaid))
        assertEquals("用户名或密码错误", LoginFailureClassifier.screenLine(serverSaid))
        val line = LoginFailureClassifier.screenLine(localSaid)
        assertNotEquals("本机那一档不许把服务端那句话借去说：", LoginFailureClassifier.screenLine(serverSaid), line)
        assertTrue(
            buildString {
                append("本机那一档必须站在本机这一侧说话：")
                append(line)
            },
            line.contains("这台设备"),
        )
    }

    // ---------------- ③ 接线缝：折档发生在 login() 里，逐字那一路一字不动 ----------------

    /** 登录那一趟传输层抛出来时：屏上念中文档、落成 IClassLoginFailedException、本机那一枚挂在 cause 上 */
    @Test
    fun `登录那一路抛出来的失败落中文档而不是一行英文`() {
        var calls = 0
        val api = IClassApi(
            IClassTransport { _, _, _ ->
                calls++
                throw ConnectException("Failed to connect to iclass.buaa.edu.cn/198.51.100.7:8181")
            },
        )
        val outcome = runBlocking { api.login("13800000000", "se cret") }
        assertEquals(buildString { append("登录发了 ").append(calls).append(" 趟（本该恰好一趟）") }, 1, calls)
        val error = requireNotNull(outcome.exceptionOrNull()) { "这一档本该失败，却把 id 交出去了：$outcome" }
        val line = requireNotNull(error.message) { "折出来的那一句没有 message：$error" }
        assertTrue(
            buildString {
                append("登录那一路的失败要落成 IClassLoginFailedException（登录页据它决定给不给重登表单）：")
                append(error.javaClass.simpleName)
            },
            error is IClassLoginFailedException,
        )
        assertEquals(
            buildString {
                append("屏上那句不是「端口没接上」那一档的句子：")
                append(line)
            },
            LoginFailureClassifier.screenLine(ConnectException("refused")),
            line,
        )
        val raw = requireNotNull(error.cause) { "本机那一枚没挂在 cause 上，取证行就念不出形状：$error" }
        assertEquals(LoginFailureRung.ConnectionNotBuilt, LoginFailureClassifier.rungOf(raw))
        for (needle in credentialNeedles + listOf("Failed", "connect")) {
            assertFalse(
                buildString {
                    append("屏上那句带了「")
                    append(needle)
                    append("」（地址、端口或异常原文外流）：")
                    append(line)
                },
                line.contains(needle),
            )
        }
    }

    /** 空凭据那一拒：不发请求，也不被折 —— 那句中文必须逐字走到调用点 */
    @Test
    fun `空凭据那一拒逐字念而不折成本机档位句`() {
        var calls = 0
        val api = IClassApi(
            IClassTransport { _, _, _ ->
                calls++
                throw IOException("这一趟根本不该发出去")
            },
        )
        for (pair in listOf("" to "口令", "   " to "口令", "13800000000" to "")) {
            val outcome = runBlocking { api.login(pair.first, pair.second) }
            val line = requireNotNull(outcome.exceptionOrNull()?.message) { "空凭据本该失败：$pair" }
            assertEquals("空凭据那句话必须逐字在场（$pair）：", "手机号与口令都要填", line)
        }
        assertEquals("空凭据居然发了请求：", 0, calls)
    }

    /** 服务端给了外壳与 ERRMSG：那一枚走逐字档，一个字节都不许多（登录页那句「登录失败：…」的前半段） */
    @Test
    fun `服务端给的原文经登录接线缝仍逐字`() {
        val api = IClassApi(
            IClassTransport { _, _, _ ->
                jsonBody("""{"STATUS":1,"ERRCODE":"100","ERRMSG":"账号或口令不对，请核对 iClass 那边给的那一对"}""")
            },
        )
        val outcome = runBlocking { api.login("13800000000", "se cret") }
        val error = requireNotNull(outcome.exceptionOrNull()) { "这一档本该失败：$outcome" }
        assertEquals(
            "服务端那句话被加工了：",
            "账号或口令不对，请核对 iClass 那边给的那一对",
            error.message,
        )
        assertEquals(LoginFailureRung.SpokenVerbatim, LoginFailureClassifier.rungOf(error))
        assertNullCause(error)
    }

    /** 成功那一趟不进取值链：拿到 id 就是拿到 id，档位表只在失败那一支说话 */
    @Test
    fun `成功那一趟不进取值链`() {
        val api = IClassApi(
            IClassTransport { _, _, _ -> jsonBody("""{"STATUS":0,"ERRCODE":"100","result":{"id":"2488752"}}""") },
        )
        val outcome = runBlocking { api.login("13800000000", "se cret") }
        assertEquals("2488752", outcome.getOrThrow())
        assertTrue("成功那一趟本该没有失败：${outcome.exceptionOrNull()}", outcome.isSuccess)
    }

    // ---------------- ④ 纯度：判据本体零 android（两把尺一起走） ----------------

    /**
     * 内核纯度 + 依赖方向。钉法 = **读形状**（逐行扫 import 行）+ **数出现次数**（"android" 那六个字母
     * 在抹注释后的代码里 0 枚 —— 抓的是不写 import 的全限定写法那一形，只数 import 行的那把尺看不见它）。
     */
    @Test
    fun `内核零 android 而判据只住在 domain`() {
        val kernel = codeOnly(sourceFile(KERNEL))
        val importLines = kernel.lines().filter { it.trimStart().startsWith("import") }
        val androidImports = importLines.count { it.contains("android") }
        assertEquals(
            buildString {
                append("import 行里带 android 的枚数（实到 ")
                append(androidImports)
                append(" 枚，全数：")
                append(importLines.joinToString(" | ") { it.trim() })
                append("）：纯判据不许 import android.* / androidx.*")
            },
            0,
            androidImports,
        )
        val androidLetters = occurrences(kernel, "android")
        assertEquals(
            buildString {
                append("整颗内核（抹注释后）里 android 那六个字母出现的枚数（实到 ")
                append(androidLetters)
                append(" 枚）：这一枚抓全限定写法；散文里那句纯度承诺由 codeOnly 抹掉，不算命中")
            },
            0,
            androidLetters,
        )
        for (banned in listOf("Build.VERSION", "Context", "Log.", "System.currentTimeMillis", "Date(", "SharedPreferences")) {
            assertEquals(
                buildString {
                    append("内核里「")
                    append(banned)
                    append("」的枚数（设备面／时钟／日志的本能去处，一律不许自己去读）：")
                },
                0,
                occurrences(kernel, banned),
            )
        }
        val upwardImports = importLines.count { it.contains("com.buaa.schedule.data") || it.contains("com.buaa.schedule.ui") }
        assertEquals(
            buildString {
                append("内核往上 import data / ui 的行数（实到 ")
                append(upwardImports)
                append(" 枚）：方向只许 data → domain，否则这张表就长回了数据层")
            },
            0,
            upwardImports,
        )
        assertEquals(
            buildString {
                append("内核里枚数尺那族助手（字符数组抹注释）的落点枚数 —— 本卡换的是逐行丢那一形：")
            },
            0,
            occurrences(kernel, "toCharArray"),
        )
    }

    // ---------------- ⑤ 折档那一手的形状：只在登录那一路 ----------------

    /**
     * 钉法 = **读形状**（花括号配平切出 signIn / login 两段）+ **数出现次数**。
     * 甲档的边界本身就是要钉的东西：签到那一路的落点住在另一枚只读审计卡的地盘。
     */
    @Test
    fun `折档只住在登录那一路而签到那一路一枚都不许`() {
        val code = codeOnly(sourceFile(API))
        val loginBlock = balancedBlock(code, "suspend fun login(")
        val signInBlock = balancedBlock(code, "suspend fun signIn(")
        assertEquals("login 段里那一手分派的枚数：", 1, occurrences(loginBlock, "spokenLoginFailureOrSelf(cause)"))
        assertEquals("login 段里 catch 的枚数（本机抛出来的只在这里被接住）：", 1, occurrences(loginBlock, "catch (cause: Throwable)"))
        assertEquals("折档函数声明 + 调用点共两枚：", 2, occurrences(code, "spokenLoginFailureOrSelf"))
        assertEquals("rungOf 在数据层的调用点只许一枚：", 1, occurrences(code, "rungOf(raw)"))
        assertEquals("screenLine 在数据层的调用点只许一枚：", 1, occurrences(code, "screenLine(raw)"))
        assertEquals("signIn 段里折档的枚数：", 0, occurrences(signInBlock, "spokenLoginFailureOrSelf"))
        assertEquals("signIn 段里内核的枚数：", 0, occurrences(signInBlock, "LoginFailureClassifier"))
        assertEquals("逐字那一档的判据必须是契约而不是文本形状：", 1, occurrences(code, "== LoginFailureRung.SpokenVerbatim"))
        assertEquals("本机那一枚只许挂一次 cause：", 1, occurrences(code, "initCause(raw)"))
    }

    /**
     * T397②：登录这一手的**复投**接线守卫（第⓪问裁的丙档形状 = 在册卡 T365 那一族两半里的调用点那一半）。
     *
     * 这一格问的是「取消在这一手到底被折成一次登录结果，还是原样往上传」。改前它是被折的，而折它的
     * 不是 `spokenLoginFailureOrSelf` 那枚助手、是**裹在它外面那一层** `runCatching`（它吃 `Throwable`）：
     * 取消走到调用点时已经换成一枚 [IClassLoginFailedException]，屏上念的是
     * 「这一趟登录在本机被收回了…也没有拿到 id」——
     * 那句话本身就把"取消"念成了一次登录结果，而这正是本卡要拆掉的。
     *
     * ⚠️ 钉法里最要紧的一枚是 `runCatching` 那 **0**：只在原来那一支 catch 里补一句复投是一枚 no-op
     * （复投发生在它的块之内，同一层 runCatching 会把它再吃一遍），第⓪问的裁定整个落在这枚数字上。
     * 两半也不许合成一半：判档仍住在 domain 那张表（第三枚断言量的就是这件事），复投只住在这一个调用点。
     *
     * 朝宽判红（W 臂，摘掉复投那一手）：行为那一格先红（`assertThrows` 拿不到取消，login() 交回的
     * 是一枚失败的 Result），随后形状那五枚（1／1／0／1／1）与顺序那一枚、在册名册那一格一起红。
     * 朝窄判红（N 臂，摘掉 rungOf 里取消那一格）：这一格第三枚断言红，而
     * ① 那格（九档缺一档）与 ⑥ 那格一起红。
     *
     * 覆盖面（诚实写明，别当它没有）：这一格只钉登录那一路。`signIn` 那一路仍整段裹在 runCatching 里、
     * 一枚复投都没有，而 `Error` 那一族也仍被折成一次失败返回 —— 两样都是卡面划下的**已知缺口**
     * （改前既有），不是这一格放过去的绿灯。取消到底能不能进到这只手，本卡零装机、没有证到，见提交。
     */
    @Test
    fun `取消在登录这一手原样复投而不折成一次登录结果`() {
        val cancellation = CancellationException("Job was cancelled")
        var calls = 0
        val api = IClassApi(
            IClassTransport { _, _, _ ->
                calls++
                throw cancellation
            },
        )
        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking { api.login("13800000000", "se cret") }
        }
        assertEquals(
            buildString {
                append("取消那一手发了 ")
                append(calls)
                append(" 趟（本该恰好一趟，且不许在发出去之前就被折成一句登录结果）")
            },
            1,
            calls,
        )
        // ⚠️ 写死之前先在同一枚对象上现量（本仓口径），而这一格的现量纠正了我原本的打算：
        // 交回调用点的类型确实是取消那一型（class = java.util.concurrent.CancellationException、
        // message 逐字还是 "Job was cancelled"），但**对象身份不保证是原来那一枚** ——
        // 协程 builder 对一枚不是 JobCancellationException 的普通 CancellationException 会造一枚
        // 同 message 的新 CE，把原来那一枚归到 cause 或 suppressed 上。所以这里钉的是
        // 「类型仍是取消那一型 ＋ 原来那一枚还在场」，不钉「同一枚对象」（后者是过严的假针）。
        val carried = thrown === cancellation ||
            thrown.cause === cancellation ||
            thrown.suppressed.any { it === cancellation }
        assertTrue(
            buildString {
                append("交回调用点的这一枚里找不到原来那一枚取消（实到类型 ")
                append(thrown.javaClass.name)
                append("、message ")
                append(thrown.message)
                append("、cause ")
                append(thrown.cause?.javaClass?.name ?: "(空)")
                append("、suppressed ")
                append(thrown.suppressed.map { it.javaClass.name }.joinToString().ifEmpty { "(空)" })
                append("）：换了本体又丢了原来那一枚，就是又折了一层 —— ")
                append("调用点既拿不到「这一手是被谁收回的」，取证行也念不出形状")
            },
            carried,
        )
        assertEquals(
            buildString {
                append("两半不许合成一半：判档仍住在内核（实到 ")
                append(LoginFailureClassifier.rungOf(cancellation))
                append("）—— 调用点复投的是取消本体，「这一手是取消」这件事仍由那张表回答")
            },
            LoginFailureRung.WithdrawnLocally,
            LoginFailureClassifier.rungOf(cancellation),
        )

        val code = codeOnly(sourceFile(API))
        val loginBlock = balancedBlock(code, "suspend fun login(")
        for ((label, anchor, expected) in listOf(
            Triple("取消那一型的 catch", "catch (e: CancellationException)", 1),
            Triple("复投那一手", "throw e", 1),
            Triple("吃掉取消的那一层（它留着上面那一手就在块之内）", "runCatching", 0),
            Triple("折档那一手（复投不许把折档一起摘掉）", "spokenLoginFailureOrSelf(cause)", 1),
            Triple("宽 catch（其余各档仍在这里折）", "catch (cause: Throwable)", 1),
        )) {
            assertEquals(
                buildString {
                    append(API)
                    append(" 的 login 段里")
                    append(label)
                    append("「")
                    append(anchor)
                    append("」实到 ")
                    append(occurrences(loginBlock, anchor))
                    append(" 枚，写死 ")
                    append(expected)
                    append(" 枚")
                },
                expected,
                occurrences(loginBlock, anchor),
            )
        }
        val rethrowAt = loginBlock.indexOf("catch (e: CancellationException)")
        val foldAt = loginBlock.indexOf("spokenLoginFailureOrSelf(cause)")
        assertTrue(
            buildString {
                append("顺序钉（本卡题眼）：复投那一型（实到索引 ")
                append(rethrowAt)
                append("）必须排在折档那一手（实到索引 ")
                append(foldAt)
                append("）之前。排到之后就是先折了一句登录结果、再想起来它是取消 —— ")
                append("那句话已经写进 Result 了，协程框架什么也没收到")
            },
            rethrowAt in 0 until foldAt,
        )
    }

    // ---------------- ⑥ 措辞纪律：每一档自己说话，一个字的凭证都不外流 ----------------

    /** 八档本机句子逐档核：都交代"没有拿到 id"、都不带凭证、八句互不相同 */
    @Test
    fun `八档句子各说各的而不带一个字的凭证`() {
        val sentences = rows.filter { it.expected != LoginFailureRung.SpokenVerbatim }
            .map { it.expected to LoginFailureClassifier.screenLine(it.cause) }
            .distinctBy { it.first }
        assertEquals(
            buildString {
                append("八档本机句子都得到场（实到 ")
                append(sentences.size)
                append(" 档）：")
            },
            8,
            sentences.size,
        )
        for ((rung, line) in sentences) {
            assertTrue(
                buildString {
                    append("档「")
                    append(rung)
                    append("」那句没交代本地后果（没有拿到 id）：")
                    append(line)
                },
                line.contains("没有拿到 id"),
            )
            for (needle in englishLeakNeedles + credentialNeedles) {
                assertFalse(
                    buildString {
                        append("档「")
                        append(rung)
                        append("」那句带了「")
                        append(needle)
                        append("」：")
                        append(line)
                    },
                    line.contains(needle),
                )
            }
        }
        val distinct = sentences.map { it.second }.distinct()
        assertEquals(
            buildString {
                append("八档不许有两档同一句（实到 ")
                append(distinct.size)
                append(" 句）：折成同一句就是本卡要修的那枚糊")
            },
            8,
            distinct.size,
        )
    }

    /**
     * 逐字那一档没留下句子时说那句实话（不许借八档里任何一句兜过去），而取证行只交回形状。
     * 取证行还钉住折档之后的那两层：档念最深那一枚，逐字念顶层那一枚 —— 少任何一层都是把刚折出来的档位名再丢一次。
     */
    @Test
    fun `逐字档没留下句子时说实话而取证行只交回形状`() {
        val blank = IClassLoginFailedException("")
        assertEquals(LoginFailureRung.SpokenVerbatim, LoginFailureClassifier.rungOf(blank))
        val line = LoginFailureClassifier.screenLine(blank)
        assertTrue(buildString { append("句子空缺时必须说实话：").append(line) }, line.contains("没有留下句子"))
        val borrowed = rows.filter { it.expected != LoginFailureRung.SpokenVerbatim }
            .map { LoginFailureClassifier.screenLine(it.cause) }
            .distinct()
        assertFalse(
            buildString {
                append("空缺那一格不许借八档里任何一句（实到 ").append(line).append("）：借一句就是替那一档编话")
            },
            borrowed.contains(line),
        )
        val raw = SocketTimeoutException("Read timed out after 30000 ms")
        val folded = IClassLoginFailedException(LoginFailureClassifier.screenLine(raw)).apply { initCause(raw) }
        val forensics = LoginFailureClassifier.forensicsLine(folded)
        assertTrue(buildString { append("取证行必须念出折出来的那一档：").append(forensics) }, forensics.contains("RoundTripTimedOut"))
        assertTrue(buildString { append("取证行必须念出本机那一枚的类名：").append(forensics) }, forensics.contains("SocketTimeoutException"))
        assertTrue(buildString { append("取证行必须明写原文没记：").append(forensics) }, forensics.contains("未记录"))
        for (needle in listOf("Read timed", "30000", "timeout")) {
            assertFalse(
                buildString {
                    append("取证行带了异常原文「").append(needle).append("」：").append(forensics)
                },
                forensics.contains(needle),
            )
        }
        val verbatimForensics = LoginFailureClassifier.forensicsLine(IClassLoginFailedException("手机号与口令都要填"))
        assertTrue(
            buildString {
                append("逐字那一档的取证行要说清是原话（实到 ").append(verbatimForensics).append("）：")
            },
            verbatimForensics.contains("档=SpokenVerbatim"),
        )
    }

    // ---------------- ⑦ 顺序格：子类排在自己的超类前面 ----------------

    /**
     * 子类三行逐枚点名，外加一枚手写**反序探针**（把「半路断了」那一问挪到最前）。
     *
     * 探针只在**两行**上改口：`ConnectException` 是 `SocketException` 的子类、
     * `SocketTimeoutException` 是 `InterruptedIOException` 的子类 —— 把超类那一问挪到前面，
     * 这两行就会被超类接走，两种现场折成同一句话。第三行（`SSLHandshakeException`）的超类
     * 就是判据自己那一问（`is SSLException`），挪序改不了它的口，所以那一行钉的是
     * **不落兜底档**而不是"探针与内核分岔" —— 把三行都说成会改口就是这张表自己编话。
     */
    @Test
    fun `子类那一问排到超类之后会在两行上改口`() {
        val probeRows = listOf(
            Row("ConnectException 是 SocketException 的子类", ConnectException("refused"), LoginFailureRung.ConnectionNotBuilt),
            Row("SocketTimeoutException 是 InterruptedIOException 的子类", SocketTimeoutException("Read timed out"), LoginFailureRung.RoundTripTimedOut),
        )
        for (row in probeRows) {
            assertEquals(describe(row), row.expected, LoginFailureClassifier.rungOf(row.cause))
            assertNotEquals(
                buildString {
                    append(row.label)
                    append(" 不许被折进「半路断了」那一档（实到 ")
                    append(LoginFailureClassifier.rungOf(row.cause))
                    append("）：那是它的超类，排错了就是把两种现场念成同一句话")
                },
                LoginFailureRung.RoundTripDroppedMidway,
                LoginFailureClassifier.rungOf(row.cause),
            )
            assertNotEquals(
                buildString {
                    append("反序探针必须在「")
                    append(row.label)
                    append("」这一行给出不同的档（实到 ")
                    append(reversedLadderProbe(row.cause))
                    append("）：同答就是这一格什么都没钉住")
                },
                LoginFailureClassifier.rungOf(row.cause),
                reversedLadderProbe(row.cause),
            )
        }
        val handshake = SSLHandshakeException("handshake failed")
        assertEquals(
            buildString {
                append("握手那一枚必须由 SSLException 那一问接住（实到 ")
                append(LoginFailureClassifier.rungOf(handshake))
                append("）：落到兜底档就是把已登记的形状说成认不出")
            },
            LoginFailureRung.SecureChannelNotSettled,
            LoginFailureClassifier.rungOf(handshake),
        )
        assertNotEquals(LoginFailureRung.FailureShapeUnrecognized, LoginFailureClassifier.rungOf(handshake))
        assertNotEquals(LoginFailureRung.RoundTripDroppedMidway, LoginFailureClassifier.rungOf(handshake))
        assertEquals(
            "一枚赤的 SocketException 仍然归「半路断了」（探针与内核在非子类行同答）：",
            LoginFailureClassifier.rungOf(SocketException("reset")),
            reversedLadderProbe(SocketException("reset")),
        )
    }

    /** 一枚手写反序梯子：把「往返半路断了」那一问挪到子类三问之前，专门暴露"顺序即语义"那一维 */
    private fun reversedLadderProbe(cause: Throwable): LoginFailureRung = when {
        cause is LocallySpokenFailure -> LoginFailureRung.SpokenVerbatim
        cause is SocketException || cause is InterruptedIOException || cause is EOFException ->
            LoginFailureRung.RoundTripDroppedMidway
        else -> LoginFailureClassifier.rungOf(cause)
    }

    // ---------------- 小工具（读形状 + 数出现次数；本文件不引入字符数组助手的那枚形状）----------------

    private fun jsonBody(text: String): IClassRawResponse =
        IClassRawResponse(200, "application/json;charset=UTF-8", text.toByteArray(Charsets.UTF_8))

    private fun assertNullCause(error: Throwable) {
        assertEquals(
            buildString {
                append("逐字那一档不该被换上 cause（换上了就是把服务端那句话降成本机档位句）：实到 ")
                append(error.cause?.javaClass?.simpleName ?: "(空)")
            },
            null,
            error.cause,
        )
    }

    private fun describe(row: Row): String = buildString {
        append(row.label)
        append(" —— 抛的是 ")
        append(row.cause.javaClass.simpleName)
        append("，期望档 ")
        append(row.expected)
        append("，实到档 ")
        append(LoginFailureClassifier.rungOf(row.cause))
    }

    private fun sourceFile(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue(
            buildString {
                append("找不到 ")
                append(file.path)
                append("：文件挪过家的话本卡那几枚形状格要跟着改路径")
            },
            file.isFile,
        )
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

    private fun balancedBlock(source: String, anchor: String): String {
        val at = source.indexOf(anchor)
        check(at >= 0) { buildMessage("找不到锚点", anchor) }
        val open = source.indexOf('{', at)
        check(open >= at) { buildMessage("锚点之后找不到左括号", anchor) }
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
        throw IllegalStateException(buildMessage("花括号没配平", anchor))
    }

    private fun buildMessage(head: String, anchor: String): String = buildString {
        append(head)
        append("「")
        append(anchor)
        append("」：写法换过了，本卡这几枚形状格要跟着改")
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
        const val KERNEL = "com/buaa/schedule/domain/signin/LoginFailure.kt"
        const val API = "com/buaa/schedule/data/import/IClassApi.kt"
    }
}
