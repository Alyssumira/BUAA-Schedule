package com.buaa.schedule.data.import

/**
 * 一张**没被两族收下**的码，到底死在哪一档（T83）。
 *
 * 为什么非分档不可：[ScanTargetParser.parse] 只回 null，于是失败卡上只有一句话可说，
 * 而那一句在 T84 之后**已经是错的**（"这不是一张智学北航的签到码"—— 现在认两族了）。
 * 更要紧的是它把四件完全不同的事糊成一句：
 *
 * 1. 根本不是一张码（一串文本、一张名片、空）；
 * 2. 是一张码，可它归别家（host 不在 iClass / `*.buaa.edu.cn` 这两道门槛上）；
 * 3. host 对，但这一族要的入口没对上 —— 路径不是学生扫码那条路由（[ScanRejectRung.WrongRoute]），
 *    或必填参数不齐（[ScanRejectRung.MissingParams]）；
 * 4. 参数名齐了，值却用不了 —— 形状不符（[ScanRejectRung.BadParamValue]），
 *    或那张"码"其实是一条已经发出去的请求（[ScanRejectRung.RequestUrlNotCode]）。
 *
 * 这四件事在界面上是四种话，在取证上是四种下一步：2 与 3 说明平台的码换了形状（要改的是判据），
 * 1 说明第二站（解码器）交出来的压根不是链接（要改的是取景与解码那一段）。
 * 糊成一句，两边都在瞎判 —— 分档就是为了每一档各自说话。
 *
 * 纯度（仓库口径，与 [ScanTargetParser] 同一档，由 `ScanTargetParserTest` 的守卫钉住）：
 * 零 import、零 android、零时钟与设备读取。判"这一族收不收"用的是两族解析器自己那套门槛与常数
 * （[IClassQrParser] / [SpocQrParser] 的 internal 成员），并且**先问 [ScanTargetParser] 本人**，
 * 所以"收不收"与"为什么没收"长不成两本账 —— 全矩阵不变量在 `ScanRejectClassifierTest` 里钉着：
 * `classify(x).rung == Recognized ⟺ ScanTargetParser.parse(x) != null`。
 *
 * ⚠️ 值不外流：判形状当然要取值看（[IClassQrParser.queryValue] / [SpocQrParser.queryParams]），
 * 但 [ScanRejectInfo] 里只有名字、长度与 host，三处措辞也都由这些字段拼出来。
 * 这一点不靠自觉：那张表里每一条输入的真实参数值，都不许出现在任何一条输出里（行为级断言）。
 */
enum class ScanRejectRung {

    /**
     * 两族的门槛其实都过了。
     *
     * 界面拿不到这一档（判据只在 `parse` 返回 null 之后才被问，见 [ScanTargetParser]），
     * 它是判据自身的**全函数**出口：直接拿一张真码问判据，答案必须是这一档，
     * 否则"收不收"与"为什么没收"就是两把尺子。
     */
    Recognized,

    /** 不是一张码：既没有 http(s) 链接形状，也不带智学北航的签到路由片段 */
    NotACode,

    /** 有链接形状，但 host 不在两族的门槛上（含 authority 读不出来的畸形链接） */
    ForeignHost,

    /** host 是 iClass 的，路径却不落在学生扫码签到那条路由上（`stu_auto_sign.action` 那一族就落到这里） */
    WrongRoute,

    /** host 与路由都对，这一族要的必填参数没齐（缺名字，或值是空的） */
    MissingParams,

    /** 参数名齐了，值却不合这一族实测过的形状 */
    BadParamValue,

    /** 形状全对，但原文里已经带着提交要拼的 `id` —— 那是一条发过的请求，不是课堂投影那张码 */
    RequestUrlNotCode,
}

/** 这张码**看起来**归哪一族（按 host 与路由判，与两族解析器的门槛同一口径） */
enum class ScanCodeFamily { IClass, Spoc, Foreign, None }

/**
 * 一次拒绝的**形状**（没有一个字段装得下原文，也没有一个字段装得下参数值）。
 *
 * [scheme] 只在链接形状下非空（`http` / `https`）—— 它同时是"是不是链接"的判据，
 * 所以这里不再另存一颗布尔：两份真相早晚会漂。
 */
data class ScanRejectInfo(
    val rung: ScanRejectRung,
    val family: ScanCodeFamily,
    val textLength: Int,
    val scheme: String?,
    val host: String?,
    val port: String?,
    val path: String?,
    /** 扫到的东西里看得见的参数名（小写、按出现次序、值为空的不算看见）；**只有名字** */
    val paramNames: List<String>,
    /** 这一族还缺哪几项（[ScanRejectRung.MissingParams] 那一档念给用户看） */
    val wantedParams: List<String>,
    /** 哪几项名字对得上却用不了（[ScanRejectRung.BadParamValue] 与 [ScanRejectRung.RequestUrlNotCode] 念给用户看） */
    val flaggedParams: List<String>,
)

/**
 * 把 `parse` 的那一句 null 拆成档。
 *
 * 只在解析失败那一支被问，问一次算一次（同步、纯函数、纳秒级），所以这里没有任何缓存与计数。
 */
object ScanRejectClassifier {

    /** 界面念的是服务端的驼峰原样；判据吃的键名是小写（[IClassQrParser.COURSE_SCHED_ID]）—— 只有显示用的差别，判据同一个 */
    internal const val SCHED_ID_SHOWN = "courseSchedId"

    /**
     * @return 这一条拒绝的档。**永不返回 null**：说不出档位就等于回到那句糊成一句的假话。
     */
    fun classify(raw: String?): ScanRejectInfo {
        val shape = Shape(raw?.trim() ?: "")
        // 第一问先问解析器本人：收下了就没什么可解释的（这一句是全矩阵不变量的那一半）
        when (ScanTargetParser.parse(shape.source)) {
            is ScanTarget.IClass -> return infoOf(shape, ScanRejectRung.Recognized, ScanCodeFamily.IClass)
            is ScanTarget.Spoc -> return infoOf(shape, ScanRejectRung.Recognized, ScanCodeFamily.Spoc)
            null -> Unit
        }
        if (!shape.urlShaped) {
            // 不是链接的两种处境：智学北航的路由片段（形态二，参数照判）与"就是一段文本"
            if (shape.source.contains(SpocQrParser.SIGN_IN_ROUTE, ignoreCase = true)) return spocVerdict(shape)
            return infoOf(shape, ScanRejectRung.NotACode, ScanCodeFamily.None)
        }
        val host = shape.host
        // authority 读不出来（`user:pass@host`、端口位置放着非数字那一类）：连"哪台的码"都说不出
        if (host == null) return infoOf(shape, ScanRejectRung.ForeignHost, ScanCodeFamily.Foreign)
        if (host.equals(IClassSignUrl.HOST, ignoreCase = true)) return iclassVerdict(shape)
        if (SpocQrParser.isSpocDomain(host)) return spocVerdict(shape)
        return infoOf(shape, ScanRejectRung.ForeignHost, ScanCodeFamily.Foreign)
    }

    /** 一枚取值的全部形状件（私有类：原文只在这里过一遍，[ScanRejectInfo] 里没有它的位置） */
    private class Shape(text: String) {
        val source: String = text
        val length: Int = text.length
        val urlShaped: Boolean =
            text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)
        val scheme: String? = if (urlShaped) urlSchemeOf(text) else null
        val path: String? = if (urlShaped) urlPathOf(text) else null
        val names: List<String> = if (text.isEmpty()) emptyList() else SpocQrParser.queryParamNames(text)
        val host: String?
        val port: String?

        init {
            val parts = if (urlShaped) urlAuthority(text)?.let { splitAuthority(it) } else null
            // 空 host（`http://:8081/x` 那一类畸形写法）连同它后面那枚端口一起按"读不出服务器"处理：
            // 措辞那一头是拿 host==null 说"认不出是哪台的码"的，留一枚孤零零的端口只会拼出半句话
            val readableHost = parts?.first?.lowercase()?.takeIf { it.isNotEmpty() }
            host = readableHost
            port = if (readableHost == null) null else parts?.second?.takeIf { it.isNotEmpty() }
        }
    }

    /** iClass 那一族的门槛，按 [IClassQrParser.parse] 的原次序一道道问过去 */
    private fun iclassVerdict(shape: Shape): ScanRejectInfo {
        if (shape.path?.endsWith(IClassQrParser.SCAN_SIGN_ROUTE) != true) {
            return infoOf(shape, ScanRejectRung.WrongRoute, ScanCodeFamily.IClass)
        }
        val schedId = IClassQrParser.queryValue(shape.source, IClassQrParser.COURSE_SCHED_ID)
        if (schedId == null) {
            return infoOf(shape, ScanRejectRung.MissingParams, ScanCodeFamily.IClass, wanted = listOf(SCHED_ID_SHOWN))
        }
        if (!IClassQrParser.SCHED_ID_PATTERN.matches(schedId)) {
            return infoOf(shape, ScanRejectRung.BadParamValue, ScanCodeFamily.IClass, flagged = listOf(SCHED_ID_SHOWN))
        }
        if (IClassQrParser.queryValue(shape.source, IClassSignUrl.USER_ID_PARAM) != null) {
            return infoOf(
                shape,
                ScanRejectRung.RequestUrlNotCode,
                ScanCodeFamily.IClass,
                flagged = listOf(IClassSignUrl.USER_ID_PARAM),
            )
        }
        // 门槛全过 ⇒ 上面 ScanTargetParser.parse 已经收下，这一格在真链路上到不了。
        // 留着它是为了让函数全，而它说的仍是实话：判据认为这张码收得下（表驱动单测直接问判据时就是这一档）。
        return infoOf(shape, ScanRejectRung.Recognized, ScanCodeFamily.IClass)
    }

    /**
     * 智学北航那一族的门槛：要的是 `zjdm`+`czid` 这一对，或一个形状对的 `qdid`
     * （[SpocQrParser] 的 `fromParams` 那两道，这里只把它们翻译成"缺哪一项 / 哪一项用不了"）。
     */
    private fun spocVerdict(shape: Shape): ScanRejectInfo {
        val params = SpocQrParser.queryParams(shape.source)
        val zjdm = params["zjdm"]
        val czid = params["czid"]
        if (params["qdid"] != null) {
            // 名字在、值非空，却还走到这一格 ⇒ 只可能是形状不符（值本身不往任何一头送）
            return infoOf(shape, ScanRejectRung.BadParamValue, ScanCodeFamily.Spoc, flagged = listOf("qdid"))
        }
        val pair = when {
            zjdm.isNullOrBlank() && czid.isNullOrBlank() -> "zjdm+czid"
            zjdm.isNullOrBlank() -> "zjdm"
            czid.isNullOrBlank() -> "czid"
            else -> "zjdm+czid"
        }
        return infoOf(shape, ScanRejectRung.MissingParams, ScanCodeFamily.Spoc, wanted = listOf(pair, "qdid"))
    }

    private fun infoOf(
        shape: Shape,
        rung: ScanRejectRung,
        family: ScanCodeFamily,
        wanted: List<String> = emptyList(),
        flagged: List<String> = emptyList(),
    ): ScanRejectInfo = ScanRejectInfo(
        rung = rung,
        family = family,
        textLength = shape.length,
        scheme = shape.scheme,
        host = shape.host,
        port = shape.port,
        path = shape.path,
        paramNames = shape.names,
        wantedParams = wanted,
        flaggedParams = flagged,
    )
}

/** 这两族的码才把路径念出来；别家的路径既不上界面也不进日志（那一头归属都判不出，路径上有什么没人核过） */
private val ScanRejectInfo.familyIsOurs: Boolean
    get() = family == ScanCodeFamily.IClass || family == ScanCodeFamily.Spoc

/** host[:port] 的展示形态；authority 读不出来时 null（措辞那一头按 null 说"认不出服务器"） */
private fun rejectHostLabel(info: ScanRejectInfo): String? = info.host?.let { h ->
    clipped(if (info.port == null) h else "$h:${info.port}", MaxHostLabelChars)
}

/**
 * 失败卡上那一句（T83③）：**按档说话**。
 *
 * 三条措辞纪律（都钉在 `ScanRejectClassifierTest` 的表里）：
 * ① 短 —— 一句话说完这一档，卡片不许被顶出画面；
 * ② 不许出现"请把手机对准…"那类没有信息量的指令句 —— 这一档说的是"我们判出它不是签到码"，
 *    叫用户再对准一次既回答不了"为什么不行"，又把一个判据问题演成操作问题；
 * ③ 每一档都要报出**扫到的东西**（host / 路径 / 参数名），因为"这张码归谁"只有用户看得见：
 *    光"解析失败"四个字，我们连第二站解出的是什么都不知道（这张卡之前吃过一次这样的亏）。
 */
fun scanRejectCardText(info: ScanRejectInfo): String {
    val host = rejectHostLabel(info)
    val whose = when {
        host != null -> "这是 $host 的码"
        info.scheme != null -> "这是一条认不出服务器的链接"
        else -> "扫到的这段东西"
    }
    return when (info.rung) {
        ScanRejectRung.Recognized ->
            "两族判据都说这张码收得下，签到却没开始 —— 它的形状已记进取证日志。"
        ScanRejectRung.NotACode ->
            "扫到的不是签到码：既不是链接，也不像 32 位的签到 ID。"
        ScanRejectRung.ForeignHost ->
            if (host == null) "$whose，也说不出它是北航哪一族的码。" else "$whose，但不是北航 iClass 或智学北航的码。"
        ScanRejectRung.WrongRoute -> {
            val path = rejectPathLabel(info)
            "$whose，可${if (path == null) "那个页面" else " $path "}不是学生扫码签到的入口。"
        }
        ScanRejectRung.MissingParams ->
            "$whose，还缺签到要用的参数：${rejectNameList(info.wantedParams, AlternativeSeparator)}。"
        ScanRejectRung.BadParamValue ->
            "$whose，参数名对得上，值却用不了：${rejectNameList(info.flaggedParams, NameSeparator)}。"
        ScanRejectRung.RequestUrlNotCode ->
            "$whose，可它已经带着提交才拼的 ${rejectNameList(info.flaggedParams, NameSeparator)} —— " +
                "是一条发过的请求，不是投影那张码。"
    }
}

/**
 * 失败卡上第二行（小字）：**这一档凭什么这么说**。
 *
 * 只有形状（host、路径〔我们两族才给〕、参数名、字符数），一个参数值都不许出现 ——
 * 与 [scanRejectForensicLine] 同一份口径：屏幕上念得出的人与 logcat 念得出的人看的是同一份事实。
 */
fun scanRejectEvidenceText(info: ScanRejectInfo): String {
    val parts = ArrayList<String>()
    parts += if (info.scheme == null) "不是链接" else "host=${rejectHostLabel(info) ?: "(读不出服务器)"}"
    if (info.familyIsOurs) rejectPathLabel(info)?.let { parts += "路径=$it" }
    parts += "参数=${if (info.paramNames.isEmpty()) "无" else rejectNameList(info.paramNames, NameSeparator)}"
    parts += "${info.textLength} 字符"
    return parts.joinToString(" · ")
}

/**
 * 解析失败那一支的**取证行**（T83②：第二站解出来的东西从来没人记过日志，
 * 于是"屏幕上写了什么"只能靠用户描述 —— 这次是靠一张投影照片 + zxing-cpp 才定到根因的）。
 *
 * 级别与 tag 归调用点（`SignInViewModel` 用 `Log.w`，见 [com.buaa.schedule.ui.signin] 那一页的
 * Info/Warn 纪律）；这一颗只管话怎么说，而"不许把完整原文写进日志"就落在 `形状=` 这一段：
 * 它是 scheme + host[:port] + path（与 [redactUrl] 同一口径 —— **查询串整体裁掉**），
 * 参数只念名字，末尾明写 `参数值=未记录`，读日志的人不必猜这是漏了还是规则。
 * 够定位的形状全留着：长度、哪一族、host 与端口、路径、参数名清单，
 * 而滚动码的 `timestamp` 那一个字节都不出去。
 */
fun scanRejectForensicLine(info: ScanRejectInfo): String {
    // 认得出却走到失败支 = 两本账漂移，这一行的前缀必须说实话，不许沿用"解析失败"
    val head = if (info.rung == ScanRejectRung.Recognized) "扫码解析判档与收码打脸" else "扫码解析失败"
    val builder = StringBuilder("$head 档=${info.rung} 族=${info.family} 长度=${info.textLength}")
    builder.append(" 形状=").append(rejectSketch(info))
    builder.append(" 参数名=").append(
        if (info.paramNames.isEmpty()) "无" else rejectNameList(info.paramNames, LogSeparator),
    )
    builder.append(" 参数值=未记录")
    if (info.wantedParams.isNotEmpty()) {
        builder.append(" 缺=").append(rejectNameList(info.wantedParams, LogSeparator))
    }
    if (info.flaggedParams.isNotEmpty()) {
        builder.append(" 不合用=").append(rejectNameList(info.flaggedParams, LogSeparator))
    }
    return builder.toString()
}

// ---- 措辞共用的取值小工具：封顶值与标签拼法只有一份，界面与日志才不会长成两套话 ----

/** host / 路径 / 参数名的封顶长度：失败卡只有一行到两行的地方，超长一律截断补省略号 */
private const val MaxHostLabelChars = 32
private const val MaxPathLabelChars = 40
private const val MaxNameChars = 24
private const val MaxNamesShown = 6

/** 屏上是中文排版（顿号 / "或"），日志是机器读的（半角逗号）—— 同一串名字，两种排法，判据只有一份 */
private const val NameSeparator = "、"
private const val AlternativeSeparator = " 或 "
private const val LogSeparator = ","

private fun clipped(value: String, cap: Int): String =
    if (value.length <= cap) value else value.take(cap) + "…"

private fun rejectPathLabel(info: ScanRejectInfo): String? =
    if (info.familyIsOurs) info.path?.let { clipped(it, MaxPathLabelChars) } else null

private fun rejectNameList(names: List<String>, separator: String): String =
    names.take(MaxNamesShown).joinToString(separator) { clipped(it, MaxNameChars) } +
        if (names.size > MaxNamesShown) "$separator…" else ""

/** `形状=` 那一段：scheme://host[:port] + （我们两族才给的）路径；不是链接就明写不是链接 */
private fun rejectSketch(info: ScanRejectInfo): String {
    if (info.scheme == null) return "不是链接"
    val head = "${info.scheme}://${rejectHostLabel(info) ?: "(读不出服务器)"}"
    return when {
        !info.familyIsOurs -> "$head 路径=不记录"
        info.path == null -> "$head 路径=无"
        else -> "$head${rejectPathLabel(info)}"
    }
}
