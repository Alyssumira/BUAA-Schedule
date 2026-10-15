package com.buaa.schedule.domain.signin

import java.io.EOFException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

/**
 * 「这一趟登录在本机断成了什么形状」——**Throwable → 第几档中文**那张表的全部判据（T392①，甲档）。
 *
 * ## 病在哪
 *
 * `data/import/IClassApi.kt` 的 `login()` 是一支 `runCatching`，而六档中文全产自 `classifyLogin`，
 * `classifyLogin` 只吃 `IClassRawResponse`：传输层（`transport.execute`）一抛就读不到响应外壳，
 * `runCatching` 把 **JVM 异常本身**交回调用点，登录页那句「登录失败：…」念的就是那枚异常的 `message`。
 * 于是同一条链上并着两种话：服务端给的（厂商中文 ERRMSG 逐字）、本仓按档写好的（中文）、
 * 以及 JVM 的英文（用户当场看到过 `timeout`）。`data/import/IClassSession.kt` 文件头那句
 * 「每一句都只念我们看得见的事，不替服务端答」在抛出来的那一支上从来没有成立过。
 *
 * ## 为什么表里必须有「逐字念」那一档
 *
 * `login()` 那支 `runCatching` 里抛出来的东西**不止**传输层那一族：本仓自己按档写好的那几枚
 * （空凭据那一拒、`classifyLogin` 的六档，以及裹着厂商 ERRMSG 原文的那一枚）也走同一支 `catch`。
 * 把它们一起折成通用句，等于把「手机号与口令都要填」念成「这一趟没走成」——那是**我们自己的假话**，
 * 与原先那句英文的错不一样但一样贵。所以 [LoginFailureRung.SpokenVerbatim] 是这张表的第一问，
 * 而它的判据是 [LocallySpokenFailure] 这一枚契约（由 `data/import` 那三枚异常实现），不是消息文本的形状。
 *
 * ## 纯度（仓库口径，由 `LoginFailureRungTableTest` 第④格钉住）
 *
 * 零 `android.*` / `androidx.*` import，也零不写 import 的全限定 android 写法；不读时钟、不读权限、
 * 不读 `Build.VERSION`、不读设备度量、不碰网络、不碰存储。判据只吃一枚 `Throwable`（`java.lang` 的东西）
 * 与它的继承形状，所以每一档都能在 JVM 单测里逐格跑一遍。
 * ⚠️ 表里那些 JVM 类名（`SocketTimeoutException`、`SSLException`、`UnknownHostException` …）是
 * **类与类之间的继承判据**，不是"真机上取证到的失败样本"：本卡零 adb，那几档在真机上各出现过没有、
 * 各出现过几次，一条都没有取证到 —— 那一栏交给装机，不在这里主张。
 *
 * ## 顺序即语义
 *
 * [rungOf] 那支梯子按「子类排在自己的超类前面」写，反了就折档：
 * `ConnectException` 是 `SocketException` 的子类 ⇒ 排在它前面，否则「端口没接」会被念成「半路断了」；
 * `SocketTimeoutException` 是 `InterruptedIOException` 的子类 ⇒ 同理；
 * `SSLHandshakeException` 是 `SSLException` 的子类，而 `SSLException` 与 `CertificateException` 都还在
 * `IOException` 那一族里 ⇒ 加密通道那一问排在往返断掉之前；
 * `CancellationException` 是 `RuntimeException`、`Error` 又不是 `Exception` —— 两问都排在解体那几问之前，
 * 因为它们说的都是**本机**的事，而 iClass 那一侧收没收到这一趟仍然说不了。
 * [LoginFailureRung.FailureShapeUnrecognized] 是唯一的兜底档：**没有**一档靠"读消息文本"命中。
 *
 * ## 为什么不折成一枚 Boolean
 *
 * 调用点要分三件事：逐字念那一枚自带的那句、给一句**站在本机这一侧**的人话、以及在日志里留下
 * 「这一档凭什么这么说」的形状（[forensicsLine]）。折成布尔就只剩「是不是自家的异常」，
 * 而超时、连不上、TLS 没过、地址没认出来这四种现场在屏上会变成同一句话——
 * 那正是本卡要拆掉的东西，也只是下一枚"替服务端答"的句子的宿主。
 *
 * ## 值不外流（口径同 `data/import/ScanReject.kt`）
 *
 * [screenLine] 与 [forensicsLine] 都**不读** `Throwable.message`（除 [LoginFailureRung.SpokenVerbatim]
 * 那一档读的是本仓自己写好的那句），也不读任何 URL、host、端口、参数名或那枚 `id`：
 * 传输层那几枚异常的 message 里就带着 `iclass…:8181` 与解析出来的 IP，一句上屏就顺着
 * 失败卡与 logcat 两条路出去。这一条不靠自觉：表驱动那张表里每一档的输出都由 [②] 那些格子逐字核过。
 */
interface LocallySpokenFailure {

    /**
     * 那一档**自带**的那句话，屏上逐字念它。
     *
     * 两族话都从这一枚出口走：本仓按档写好的（"手机号与口令都要填"、`classifyLogin` 那六句），
     * 以及厂商 ERRMSG 的逐字原文（`Envelope.Rejected` 那一档把它包进了异常里）。
     * 空白交回的是"这一档没留下句子"那句实话（见 [LoginFailureClassifier.screenLine]），不许兜底成通用句。
     */
    val spokenLine: String
}

/**
 * 一次登录失败落在哪一档。
 *
 * 九档里只有一档（[SpokenVerbatim]）念的是别人的话，其余八档每一档都只念**本机这一趟**看得见的事，
 * 并且每一档都自己说明「没有拿到 id」——那是这一族唯一的本地后果（`IClassSession` 只在成功时落盘）。
 */
enum class LoginFailureRung {

    /** 本仓按档写好的那一句（含厂商 ERRMSG 的逐字原文）：**逐字念，不许再包一层** */
    SpokenVerbatim,

    /** 这一趟在本机被收回（那一页离场、或这次请求被取消）：根本没有结论，也与 iClass 答了什么无关 */
    WithdrawnLocally,

    /** 撞在本机一个硬故障上（`Error` 那一族）：它不是登录本身的事，也不该被说成 iClass 的话 */
    LocalHardFault,

    /** 等回话等到超时：连接没建成，或建好之后没等到 —— 两种都只说明这一趟没有结论 */
    RoundTripTimedOut,

    /** 加密通道那一段没过去（握手没成、证书没认下）：这一趟没走成，与账号口令无关 */
    SecureChannelNotSettled,

    /** 这台设备没把服务器的地址认出来（网络没通，或域名没解析出来） */
    AddressNotResolved,

    /** 没能和那台服务器的端口接上（连接被拒、或路上不通） */
    ConnectionNotBuilt,

    /** 往返在半路断了（连接被中断、响应读到一半就没了）：这一趟没有结论 */
    RoundTripDroppedMidway,

    /** 本机交回一个我们没登记过原因的失败形状：只能说"认不出这个形状"，不许猜它是超时还是拒绝 */
    FailureShapeUnrecognized,
}

/**
 * 一张表、一处判据：把 `login()` 那支 `catch` 交回来的 `Throwable` 折成档，再按档取话。
 *
 * 三个出口都是纯函数：[rungOf] 给档，[screenLine] 给屏上那一句，[forensicsLine] 给日志那一行
 * （级别与 tag 归调用点，本内核不碰 `Log`）。
 */
object LoginFailureClassifier {

    /** 沿 `cause` 往下最多走几跳：只为把"本机真正断掉的那一枚"的类名念出来，不做递归判定 */
    private const val MaxCauseHops = 4

    /** 这一趟的档。总函数：任何 `Throwable` 都有一档，说不出形状就落到最后一档 */
    fun rungOf(cause: Throwable): LoginFailureRung = when {
        cause is LocallySpokenFailure -> LoginFailureRung.SpokenVerbatim
        cause is CancellationException -> LoginFailureRung.WithdrawnLocally
        cause is Error -> LoginFailureRung.LocalHardFault
        // 超时排在"半路断了"之前：SocketTimeoutException 是 InterruptedIOException 的子类
        cause is SocketTimeoutException -> LoginFailureRung.RoundTripTimedOut
        // 加密通道那一问排在往返断掉之前：SSLException（含它的握手子类）与证书那一族都在 IOException 里
        cause is SSLException || cause is CertificateException -> LoginFailureRung.SecureChannelNotSettled
        cause is UnknownHostException -> LoginFailureRung.AddressNotResolved
        // 端口/路由那一问排在 SocketException 之前：ConnectException 就是它的子类
        cause is ConnectException || cause is NoRouteToHostException || cause is PortUnreachableException ->
            LoginFailureRung.ConnectionNotBuilt
        cause is SocketException || cause is InterruptedIOException || cause is EOFException ->
            LoginFailureRung.RoundTripDroppedMidway
        else -> LoginFailureRung.FailureShapeUnrecognized
    }

    /**
     * 屏上那一句。[LoginFailureRung.SpokenVerbatim] 那一档交回的是那一枚异常**自带**的话，
     * 其余八档交回的是本内核按档写好的那句 —— 两族话都不含 URL、host、端口、参数值与那枚 `id`。
     */
    fun screenLine(cause: Throwable): String = sentenceOf(rungOf(cause), cause)

    /**
     * 日志那一行：档位 + 这一档念的是不是原话 + 本机那一枚异常的形状。
     *
     * `异常原文=未记录` 是**明写的规则**而不是疏漏：传输层那几枚的 message 里带着服务器地址、端口
     * 与解析出来的 IP，而那一行日志在用户的设备上（release 也剥不掉 Warn 级）。类名不是凭证，留它。
     */
    fun forensicsLine(cause: Throwable): String = buildString {
        val rung = rungOf(cause)
        append("登录失败分档 档=").append(rung)
        append(" 逐字念=").append(if (rung == LoginFailureRung.SpokenVerbatim) "是" else "否")
        append(" JVM=").append(deepestClassName(cause))
        append(" 异常原文=未记录（那一栏可能带着服务器地址与端口）")
    }

    /** 一档一句人话：只有 SpokenVerbatim 那一档不在这里说话，它的话由那一枚异常自己带着 */
    private fun sentenceOf(rung: LoginFailureRung, cause: Throwable): String = when (rung) {
        LoginFailureRung.SpokenVerbatim -> verbatimLine(cause)
        LoginFailureRung.WithdrawnLocally ->
            "这一趟登录在本机被收回了（那一页离场，或这一次请求被取消），没有任何结论，也没有拿到 id"
        LoginFailureRung.LocalHardFault ->
            "这一趟登录断在本机的一个硬故障上（那种故障与 iClass 答了什么无关，也不是它给的话），没有拿到 id"
        LoginFailureRung.RoundTripTimedOut ->
            "等 iClass 那一趟往返等超时了（连接没建成，或建好之后没等到回话），这一趟没有结论，没有拿到 id"
        LoginFailureRung.SecureChannelNotSettled ->
            "和 iClass 那台服务器的加密通道没建起来（握手没成，或证书没认下），这一趟没走成，没有拿到 id"
        LoginFailureRung.AddressNotResolved ->
            "这台设备没把 iClass 那台服务器的地址认出来（网络没通，或域名没解析出来），这一趟没走成，没有拿到 id"
        LoginFailureRung.ConnectionNotBuilt ->
            "这台设备没能和 iClass 那台服务器的端口接上（连接被拒，或中间路上不通），这一趟没走成，没有拿到 id"
        LoginFailureRung.RoundTripDroppedMidway ->
            "和 iClass 那一趟往返在半路断了（连接被中断，或响应读到一半就没了），这一趟没有结论，没有拿到 id"
        LoginFailureRung.FailureShapeUnrecognized ->
            "这一趟登录断在本机一个我们没登记过原因的失败形状上（类名只进取证行），" +
            "iClass 那侧收没收到这一趟我们说不了，没有拿到 id"
    }

    /** 逐字念那一档自带的话；那一枚异常没留下句子就说这句实话，不许借通用档的句子兜过去 */
    private fun verbatimLine(cause: Throwable): String = when (cause) {
        is LocallySpokenFailure -> cause.spokenLine.ifBlank { ownSentenceMissingLine }
        else -> ownSentenceMissingLine
    }

    /** 沿 cause 走到最深的那一枚（有界、认得出自引用），只交回它的类名：形状可以上日志，原文不行 */
    private fun deepestClassName(cause: Throwable): String {
        var node: Throwable = cause
        var hops = 0
        while (hops < MaxCauseHops) {
            val next = node.cause ?: break
            if (next === node) break
            node = next
            hops++
        }
        return node.javaClass.simpleName
    }

    private const val ownSentenceMissingLine =
        "这一趟登录失败的那一档在本仓没有留下句子（是我们自己漏写的，不是 iClass 的话），没有拿到 id"
}
