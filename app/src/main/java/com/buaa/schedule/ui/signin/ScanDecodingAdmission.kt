package com.buaa.schedule.ui.signin

/**
 * 一次解码递出来的那份东西，**算不算一次扫码结果**（T87 / 台账 #107：ML Kit 误检的收口）。
 *
 * 这一档以前不存在：解码器递回来任何东西都一律算"一次扫码结果"，于是
 * 模拟器虚拟场景那面棋盘格被 ML Kit 解成一枚**空原文**（`rawValue == ""`，不是 null）之后，
 * 它一路穿过提交闸门、递进状态机，最后由 [com.buaa.schedule.data.import.ScanRejectClassifier]
 * 判成 `NotACode`，界面上对人说「扫到的不是签到码：这一族只认北航 iClass 的签到链接，
 * 而它连链接都不是」。2026-09-25 装机实测这一发：**795 帧里 794 帧都递回空原文**
 * （`logcat -d -s ScanSignInParse` 那一行 `档=NotACode 长度=0`），而用户什么都没扫。
 *
 * 那句话比改前的「这不是一张智学北航的签到码」诚实，可它仍然在**指责用户扫错了东西**。
 * 而这颗判据说的是另一件事：**空白原文不是"一次签到码解析失败"，是"这次没认出来"** ——
 * 没认出来就不该进状态机、不该弹失败卡，但必须留一行痕（这一年在系统性消灭的是"静默死路"，
 * 把它改成静默丢弃等于开倒车）。
 *
 * 纯度（仓库口径，与 [ScanSubmissionGate] / [ScanRecoveryPolicy] / [ScanFrameFlowPolicy] 同一档）：
 * 零 import、零 android、零时钟与设备读取。设备侧事实（**这是相机帧还是相册图**）由调用点
 * 当参数传进来 —— 那两件事的界面后果不同（相机那条不收就只是继续扫，相册那条不收要说
 * "那张图里没认出二维码"），而对"收不收"的判断本身完全相同，所以来源只进措辞不进判据。
 *
 * ⚠️ 这一颗**只管收与不收，不管是什么码**：
 * - 七档拒绝判据（[com.buaa.schedule.data.import.ScanRejectRung]）一个字都没动，也不新增一档；
 *   收下来的原文照旧由 `ScanTargetParser.parse` 判，判否才落到 `rejectScan` 那一句人话上。
 * - 判死棘轮也不经过这里：空白原文**从来没有**算进"试过一次失败"（那一本账只由
 *   `noteDecodeFailed` 写，两个写点是 ML Kit 的失败回调与 `process()` 同步抛），
 *   本卡既没接进去、也不许接进去 —— 对着空白墙面把扫码页判死是比弹一张假卡更坏的失效。
 */

/**
 * 这一次解码发生在哪条路上 —— 设备侧事实，由调用点读出来当参数传进来。
 *
 * 只有两枚，因为本页只剩两条解码路（「手输签到码」2026-09-21 整条删除，不存在第三种来源）。
 */
internal enum class DecodingSource {

    /**
     * 相机帧：ML Kit 分析流与 zxing-cpp 兜底**共用**这一档（两条引擎递回来的都是字符串，
     * 在"这份投不投得出去"这件事上没有任何区别，见 [ScanSubmissionGate] 那颗闸门）。
     */
    CameraFrame,

    /** 相册里挑的那张图：一次用户动作，没有按帧重投，界面另有「图里没认出码」那一句 */
    GalleryImage,
}

/** 递过来的解码结果是什么形状 —— 「为什么不收」的那一半答案（也是取证行的主语） */
internal enum class DecodingBlankness {

    /** 有可读文本：**收**（收不收之后由 `ScanTargetParser.parse` 判档，不归这里管） */
    None,

    /**
     * 解码器连原文都没给（ML Kit 的 `rawValue == null`，通常是一枚只带框的候选码）。
     *
     * 相机那条路上这一档另有其人：`noteNoReadableValue` 那本账（按帧、带节流）在它自己的
     * 支路上就已经拦下了，到不了本判据；相册那条路上它是主力档（用户挑了一张没有码的图）。
     * 判据把 null 也判成"不收"是为了让函数**全**（`String?` 进得来），而不是为了抢那一行日志。
     */
    NoText,

    /**
     * 原文是空串（长度 0）。
     *
     * ⚠️ 这一档与 [WhitespaceOnly] 的分别是本卡存在的核心理由：**空串不可能是用户扫到的内容**
     * （一张装着零字节的 QR 在版本 1 的最小尺寸下都放不下），它是解码器交出来的空结果 ——
     * 装机实测的棋盘格误检就是这一档。措辞必须说"解码器给了个空东西"，不许说成用户扫错了。
     */
    EmptyText,

    /**
     * 原文非空，却一个可见字符都没有（空格 / 换行 / 制表 / 全角空格都算）。
     *
     * 这一档**可能真是**一条装着空白的文本码（谁都能生成一张内容为三个空格的 QR），
     * 所以取证行不能与空串混成同一句话：那种码收下来也签不了，但它值得按"扫到了东西"记；
     * 空串那种连"扫到了东西"都不成立。
     */
    WhitespaceOnly,
}

/**
 * 一次递交的准入结论。
 *
 * ⚠️ 装不下原文：只有长度与档级。空白原文本身没什么可泄的，但这一颗与七档那条
 * "值不外流"的纪律同源（见 `ScanRejectInfo`），照抄一份比另立一份便宜。
 */
internal data class DecodingAdmission(
    val blankness: DecodingBlankness,
    val source: DecodingSource,
    /** 原文的字符数（[DecodingBlankness.NoText] 与 [DecodingBlankness.EmptyText] 都是 0） */
    val payloadLength: Int,
) {
    /** 收 = 递给提交闸门与状态机；不收 = 留痕之后就地咽下（相机那条）或走"图里没认出码"（相册那条） */
    val admitted: Boolean
        get() = blankness == DecodingBlankness.None
}

/**
 * 唯一的准入判据。
 *
 * 空白口径**就是** Kotlin 的 `String.isBlank()`，也就是 `Char.isWhitespace()` 那一把尺子 ——
 * 按 Unicode 的 White_Space 属性判：半角/全角空格（U+3000）、不换行空格（U+00A0）、
 * 制表、换行、回车都算空白；`\u200B` 零宽空格与 `\u0000` 这类控制符**不算**。
 * （注意它比 `java.lang.Character.isWhitespace` 宽一档：那颗把 U+00A0 排除在外，Kotlin 不排。）
 * 这里刻意不自造第三种空白定义：[com.buaa.schedule.data.import.ScanRejectClassifier.classify]
 * 吃的是 `trim()` 后的原文，而 Kotlin 的 `trim()` 用的正是同一个 `isWhitespace` ——
 * 两边同一把尺子，"收不收"与"为什么没收"才长不成两本账。
 *
 * 判据只看那颗字符串：`rawBytes` 有没有、字节数多少、是哪个解码器解的，一律不参与判断 ——
 * 空字节数组配一枚非空白的 `displayValue` 是**真码**（例如内容为单个字符的 QR），
 * 拿字节当判据就会把它一起杀掉。
 */
internal fun decodingAdmission(payload: String?, source: DecodingSource): DecodingAdmission = when {
    payload == null -> DecodingAdmission(DecodingBlankness.NoText, source, payloadLength = 0)
    payload.isEmpty() -> DecodingAdmission(DecodingBlankness.EmptyText, source, payloadLength = 0)
    payload.isBlank() -> DecodingAdmission(DecodingBlankness.WhitespaceOnly, source, payloadLength = payload.length)
    else -> DecodingAdmission(DecodingBlankness.None, source, payloadLength = payload.length)
}

/** 相册是一次动作，没有"第几帧"可报（[decodingAdmissionTraceText] 的 frame 传这一枚 = 未记录） */
internal const val NoDecodingFrame = -1L

/** 相册那一次的序号恒为 1（没有风暴要数） */
internal const val SingleActionOrdinal = 1L

// ---- 留痕的节流：按帧的东西不做去重就是把 logcat 冲干净，口径与 shouldLogValuelessBarcode 同族 ----

/**
 * 同一档空白原文说几次话。
 *
 * 50 的账（**实测装机**，2026-09-25 这台 AVD 的虚拟场景）：相机送帧 **8.10 帧/秒**
 * （795 帧 / 98.0 s，临时探针量得，探针未入库），而那种画面里**794/795 帧都递回空原文** ——
 * 也就是"每一帧都空白"是常态而不是意外。取 50 ⇒ 约每 6 秒一行；按 T67 那轮量到的
 * 2.80 帧/秒算则约每 18 秒一行。两个极端都读得到，也不会盖过别的取证行。
 * 换档（[DecodingBlankness] 变了）**必说**：空串与全空白是两种病因，从一种跳到另一种
 * 是画面换了东西的信号，不该被自己的节流咽下去。
 */
internal const val BlankDecodingLogStride = 50L

/**
 * 本轮绑定为止"空白原文"的留痕账本。整枚换引用、字段全不可变 ——
 * 写它的是 ML Kit 的回调线程（主力那一支）与分析线程（兜底那一支），两颗线程共用同一道闸门。
 *
 * @param count 本轮已经遇到几次空白原文（说不说话的都数）
 * @param lastBlankness 上一次是哪一档，null = 本轮还没遇到过（第一次必说）
 */
internal data class BlankDecodingLedger(
    val count: Long = 0L,
    val lastBlankness: DecodingBlankness? = null,
)

/**
 * 一次空白原文的留痕结论：账本已经翻好，说不说话由 [speak] 给。
 *
 * 把"记账"与"要不要说话"捏在同一个返回值里，是为了让调用点没法只挑一半 ——
 * 只说话不记账就是每帧一行，只记账不说话就是本卡要避免的那种静默丢弃。
 */
internal data class BlankDecodingTrace(
    val ledger: BlankDecodingLedger,
    /** 这是本轮第几次空白原文（带进取证行，读日志的人不必自己数） */
    val occurrence: Long,
    /** 这一次说不说话 */
    val speak: Boolean,
)

/**
 * 记一笔空白原文的账，并给出这一次说不说话。
 *
 * 纯函数：吃旧账本、吐新账本，不碰时钟、不碰设备。计数与 [speak] 的判据同一次算出来，
 * 所以"第 50 次"那一行里报的数就是它自己那一次的数。
 */
internal fun traceBlankDecoding(ledger: BlankDecodingLedger, blankness: DecodingBlankness): BlankDecodingTrace {
    val count = ledger.count + 1L
    val speak = blankness != ledger.lastBlankness || count == 1L || count % BlankDecodingLogStride == 0L
    return BlankDecodingTrace(BlankDecodingLedger(count, blankness), occurrence = count, speak = speak)
}

/**
 * 「这次没认出来」那一行取证的**全文**（措辞唯一来源；调用点只许 `Log.i(TAG, 本函数)`）。
 *
 * 级别是 Info 而不是 Warn：这一档**没有失败**，失败卡也没有弹 —— 拿 Warn 说一件没失败的事，
 * 读日志的人会以为链路上出了故障（而用户那台机器把 logcat 砍在 Info，写 `Log.d` 又等于没写）。
 *
 * 四件事一枚不少：
 * ① 哪条路（源=相机帧 / 相册图）+ 第几帧 + 本轮第几次 —— 对得上分析器那本帧号账；
 * ② 长度与形状 —— **「用户真扫了一条空白文本」与「解码器给了个空东西」分得开**，
 *    这两件事对排查的含义不同：前者说明码的内容真是空白，后者说明第二站在误检；
 * ③ 后果：没进状态机、没弹失败卡（相机那条）/ 界面走"图里没认出码"那一句（相册那条）；
 * ④ 一句禁令式的自证：`参数值=未记录` 与七档同一口径，读日志的人不必猜这里是不是漏了。
 *
 * ⚠️ 原文本身**一个字都不进这一行**，只报长度：全空白那档要是把原文拼出去，
 * 换行与制表就会把一条取证记录切成好几行（日志被原文切开 = 可以伪造行）。
 */
internal fun decodingAdmissionTraceText(admission: DecodingAdmission, occurrence: Long, frame: Long): String =
    "解码原文不收 源=${sourceLabel(admission.source)} ${frameLabel(frame)} 本轮第 $occurrence 次 " +
        "长度=${admission.payloadLength} 形状=${blanknessLabel(admission)} 参数值=未记录 —— ${consequenceLabel(admission.source)}"

private fun sourceLabel(source: DecodingSource): String = when (source) {
    DecodingSource.CameraFrame -> "相机帧"
    DecodingSource.GalleryImage -> "相册图"
}

/** 帧号未记录（相册那条没有帧）那一档说实话，不许编一个 0 出来 —— 与 frameFlowStopLogText 同一口径 */
private fun frameLabel(frame: Long): String =
    if (frame >= 0L) "第 $frame 帧" else "帧号未记录（相册那条没有帧）"

private fun blanknessLabel(admission: DecodingAdmission): String = when (admission.blankness) {
    DecodingBlankness.None -> "有可读原文（这一档不该走到留痕，说明调用点漏了准入判断）"
    DecodingBlankness.NoText -> "解码器没给原文，只回了一枚条码（rawValue 为空）"
    DecodingBlankness.EmptyText -> "解码器交出一枚空原文（长度 0）—— 最像误检，不是用户扫错了东西"
    DecodingBlankness.WhitespaceOnly -> "原文 ${admission.payloadLength} 个字符全是空白 —— 可能真扫到一条只含空白的文本"
}

private fun consequenceLabel(source: DecodingSource): String = when (source) {
    DecodingSource.CameraFrame -> "没递进签到状态机、没弹失败卡，取景与解码照旧"
    DecodingSource.GalleryImage -> "没递进签到状态机，界面说的是「那张图里没认出二维码」那一句"
}

/**
 * 共用那道闸门（`QrCodeAnalyzer.submitDecodedText`）对一份原文的三种出路。
 *
 * 为什么不是布尔：改前那句 `Boolean` 只分得开"投了/没投"，而 T87 之后"没投"有两种原因，
 * 后果与下一步完全不同（闸门压住 = 正常流程；空白原文 = 第二站在误检）。兜底那一行留痕
 * 原来写的是 `if (submitted) …否则说"被闸门压住"` —— 让它照旧说这一句，就等于在
 * "其实一个字都没递交"的时候把责任推给闸门。三档一张表，措辞仍只有一份。
 */
internal enum class DecodingSubmission {

    /** 收了，闸门也放了：原文已经递给状态机（[com.buaa.schedule.ui.signin.SignInViewModel.signIn]） */
    Submitted,

    /** 收了，但同一份原文刚投过或结果卡正等用户按 —— 闸门本来的活儿 */
    HeldByGate,

    /** 没收：准入判据就地咽下，留痕在「解码原文不收」那一行，不在提交闸门那一本账里 */
    BlankRejected,
}

/**
 * 「第二引擎补解命中」那一行的收尾半句（措辞唯一来源，调用点只许 [decodedTextSubmissionClause]）。
 *
 * 前两档的字符串与改前**逐字相同**（本卡只多开一档，不重写别人那句话 ——
 * `ScanSecondEngineWiringGuardTest` 那几档钉的就是这一页说话的形状）。
 */
internal fun decodedTextSubmissionClause(submission: DecodingSubmission): String = when (submission) {
    DecodingSubmission.Submitted -> "这一份按同一道闸门递交签到"
    DecodingSubmission.HeldByGate -> "这一份被提交闸门压住（同一份原文刚投过，或结果卡正等用户按）"
    DecodingSubmission.BlankRejected -> "这一份是空白原文，被准入判据收在闸门之前（留痕见「解码原文不收」那一行）"
}
