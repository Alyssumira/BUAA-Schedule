package com.buaa.schedule.domain.signin

/**
 * 「这一发签到在本机断成了什么形状」——**Throwable → 第几档中文**那张表的递交侧（#696，第⓪问裁的甲档）。
 *
 * ## 病在哪
 *
 * `ui/signin/SignInViewModel.kt` 的 `fail()` 从前是 `_state.value = SignInState.Failed(error.message ?: …)`，
 * 也就是把**任意异常原文**当签到卡的文案往上屏递。同一条链在登录那一侧已经由
 * [LoginFailureClassifier] 按档折成中文（T392①），递交这一侧没走过那把尺 ——
 * 于是 `Read timed out`、`Failed to connect to iclass…/198.51.100.7:8181` 这类 JVM 的英文
 * （里面就带着主机名与端口）直接进 `SignInState.Failed.reason`，再由 `SpocScanScreen` 那张
 * ALERT 卡逐字画给用户。`IClassSession.kt` 文件头那句「每一句都只念我们看得见的事」在抛出来的
 * 那一支上从来没成立过。
 *
 * ## 为什么梯子不新立一枚（这一条是本文件的题眼）
 *
 * 「这一枚 Throwable 断成了什么形状」这件事全仓只许有一把尺：档位判据**转发**给
 * [LoginFailureClassifier.rungOf]，吃的是同一枚 [LoginFailureRung]。这里新写的只有**话怎么说**那张表。
 * 理由是两页的后果不是一件事：登录那一趟断了的本地后果是「没有拿到 id」，递交这一趟断了的本地后果
 * 是「没有任何受理的证据」—— 把登录的句子搬到签到卡上就是另一句假话（卡面上写着"登录"、屏上是签到），
 * 而再刻一把继承判据梯就是 #455 那一族「唯一真源旁边又各写一遍」。两枚句子表、一把判据梯。
 *
 * ## 「没有任何受理的证据」这一句为什么是这一族的正确说法
 *
 * 它念的是本仓已经在册的那半件事：`IClassApi` 的 `EmptyBody` 那一档写明了空体「只支持"没有任何受理的
 * 证据"这半句，不支持"服务端没有受理"那半句」（见那颗文件的 `Envelope.EmptyBody` 注释与 `classifySign`
 * 里那三句尾注）。传输层抛出来的各档同样不知道服务端收没收到这一发，所以每一档的尾巴都只许落到
 * 「没有任何受理的证据」，**不许**落到「没签上」「签到失败了」那种关于服务端的断言。
 *
 * ## 逐字那一档为什么在递交这一侧更要紧
 *
 * `classifySign` 那七档的句子（[com.buaa.schedule.data.import.IClassRejectedException] 与
 * [com.buaa.schedule.data.import.IClassUnexpectedResponseException]）本来就是这条链上唯一的真信息：
 * 前者装的是厂商 ERRMSG 的逐字原文，后者是本仓按外壳档位各写好的那一句。两枚都实现
 * [LocallySpokenFailure]，所以它们在 [LoginFailureRung.SpokenVerbatim] 那一档**逐字过界**，
 * 本卡一个字都不加工 —— 把它们折成通用句就是把「参数错误!」念成「这一趟没走成」。
 *
 * ## 纯度（仓库口径，同 [LoginFailureClassifier]）
 *
 * 零 `android.*` / `androidx.*` import，也零不写 import 的全限定 android 写法；不读时钟、不读权限、
 * 不读 `Build.VERSION`、不读设备度量、不碰网络、不碰存储。判据只吃一枚 `Throwable`（`java.lang` 的东西）
 * 与它的继承形状，所以每一档都能在 JVM 单测里逐格跑一遍（表驱动那张表在
 * `SubmitFailureRungTableTest`，档位枚数与名册闭合都由它钉）。
 *
 * ## 值不外流
 *
 * [screenLine] 与 [forensicsLine] 都不读 `Throwable.message`（除 [LoginFailureRung.SpokenVerbatim]
 * 那一档读的是本仓/服务端已经写好句子的那一枚自带的 `spokenLine`），也不读任何 URL、host、端口、
 * 参数名与那枚 `id`。这一条不靠自觉：那张表里每一档的输出都由表驱动单测逐格核过。
 */
object SubmitFailureClassifier {

    /**
     * 这一发落在第几档。**判据住在 [LoginFailureClassifier]，这里一次都不重新判**（见类注释的题眼）。
     */
    fun rungOf(cause: Throwable): LoginFailureRung = LoginFailureClassifier.rungOf(cause)

    /**
     * 屏上那一句。[LoginFailureRung.SpokenVerbatim] 那一档交回的是那一枚异常**自带**的话，
     * 其余八档交回的是本文件按档写好的那句 —— 两族话都不含 URL、host、端口、参数值与那枚 `id`。
     */
    fun screenLine(cause: Throwable): String = sentenceOf(rungOf(cause), cause)

    /**
     * 日志那一行：这一发断在哪一档 ＋ 屏上现在念的是不是本仓/服务端的原话 ＋ 异常类名。
     *
     * 与 [LoginFailureClassifier.forensicsLine] 的**一处**实质差别：那一颗沿 `cause` 走到最深那一枚
     * 再报档，因为登录那一侧折过档之后顶层已经被换成新造的那一枚、"断在哪一档"只活在 cause 链上。
     * 递交这一侧不折异常本体（`IClassApi.signIn` 交回的是原文，折的只有屏上那句话），
     * 所以顶层那一枚**就是**本机断掉的那一枚，档位与类名都读顶层。
     * ⚠️ 哪天递交这一侧也开始包一层新异常，这一行就得跟着学那一颗分两层报，否则报出来的档是包装层的。
     *
     * `异常原文=未记录` 是**明写的规则**而不是疏漏：传输层那几枚的 message 里带着服务器地址、端口
     * 与解析出来的 IP，而这一行日志在用户的设备上（release 也剥不掉 Warn 级）。类名不是凭证，留它。
     */
    fun forensicsLine(cause: Throwable): String = buildString {
        val spoken = cause is LocallySpokenFailure
        append("签到失败分档 档=").append(rungOf(cause))
        append(" 逐字=").append(if (spoken) "是" else "否")
        append(" 屏上=").append(if (spoken) "本仓或服务端的那一句" else "本机档位句")
        append(" JVM=").append(cause.javaClass.simpleName)
        append(" 异常原文=未记录（那一栏可能带着服务器地址与端口）")
    }

    /** 一档一句人话：只有 SpokenVerbatim 那一档不在这里说话，它的话由那一枚异常自己带着 */
    private fun sentenceOf(rung: LoginFailureRung, cause: Throwable): String = when (rung) {
        LoginFailureRung.SpokenVerbatim -> verbatimLine(cause)
        LoginFailureRung.WithdrawnLocally ->
            "这一发签到在本机被收回了（那一页离场，或这一次请求被取消），没有任何结论，也没有任何受理的证据"
        LoginFailureRung.LocalHardFault ->
            "这一发签到断在本机的一个硬故障上（那种故障与 iClass 答了什么无关，也不是它给的话），没有任何受理的证据"
        LoginFailureRung.RoundTripTimedOut ->
            "等 iClass 那一趟往返等超时了（连接没建成，或建好之后没等到回话），这一发没有结论，也没有任何受理的证据"
        LoginFailureRung.SecureChannelNotSettled ->
            "和 iClass 那台服务器的加密通道没建起来（握手没成，或证书没认下），这一发没走成，没有任何受理的证据"
        LoginFailureRung.AddressNotResolved ->
            "这台设备没把 iClass 那台服务器的地址认出来（网络没通，或域名没解析出来），这一发没走成，没有任何受理的证据"
        LoginFailureRung.ConnectionNotBuilt ->
            "这台设备没能和 iClass 那台服务器的端口接上（连接被拒，或中间路上不通），这一发没走成，没有任何受理的证据"
        LoginFailureRung.RoundTripDroppedMidway ->
            "和 iClass 那一趟往返在半路断了（连接被中断，或响应读到一半就没了），这一发没有结论，也没有任何受理的证据"
        LoginFailureRung.FailureShapeUnrecognized ->
            "这一发签到断在本机一个我们没登记过原因的失败形状上（类名只进取证行），" +
            "iClass 那侧收没收到这一发我们说不了，没有任何受理的证据"
    }

    /** 逐字念那一档自带的话；那一枚异常没留下句子就说这句实话，不许借通用档的句子兜过去 */
    private fun verbatimLine(cause: Throwable): String = when (cause) {
        is LocallySpokenFailure -> cause.spokenLine.ifBlank { ownSentenceMissingLine }
        else -> ownSentenceMissingLine
    }

    private const val ownSentenceMissingLine =
        "这一发签到失败的那一档在本仓没有留下句子（是我们自己漏写的，不是 iClass 的话），没有任何受理的证据"
}
