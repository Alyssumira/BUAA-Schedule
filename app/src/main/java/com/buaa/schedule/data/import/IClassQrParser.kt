package com.buaa.schedule.data.import

/**
 * 认得北航 iClass（竞业达「轻新课堂」）那张课堂签到码，并**原样保留**它。
 *
 * 真码逐字原文（zxing-cpp 从一张投影照片解出来）：
 * ```
 * http://iclass.buaa.edu.cn:8081/app/course/stu_scan_sign.action?courseSchedId=2488752&timestamp=1790247391994
 * ```
 * `timestamp` 换算过去就是拍照那一刻 ⇒ 这是**滚动码**：扫到必须立刻提交，旧截图无效。
 * 这一族因此没有"先查详情再提交"那一步，解析器也不为它编造任何可延迟的语义。
 *
 * 三个门槛（host 精确 + 路由 + 带 `courseSchedId`）不是防御性偏执，而是"这张码归谁"的
 * 判据：iClass 与智学北航同在 `*.buaa.edu.cn` 下，任何一道松掉都会把别家的码打到这里、
 * 或把这里的码打给别家。判定与 [IClassSignUrl] 一样是纯 JVM、零 android import、零时钟读取
 * （`timestamp` 只当参数名看，从不参与判断、也不与本地时间比较 —— 码过没过期由服务端判）。
 */
object IClassQrParser {

    /**
     * 学生**扫码**签到那条路由。
     *
     * 同族还有一条 `app/course/stu_auto_sign.action`（POST，带经纬度与 `machineInfo`，
     * 人不在课堂也能签）—— 那不是"扫老师投的码"，本仓刻意不接，也不许有人顺手把它
     * 从这里放过来：路由门槛就是为它设的。
     *
     * T83 起 `internal`：[ScanRejectClassifier] 要说"host 对但路径不是签到入口"那一档，
     * 而它必须与这道门槛**同一个字面量**，两处分身就是两本账（改这里它跟着改）。
     */
    internal const val SCAN_SIGN_ROUTE = "/app/course/stu_scan_sign.action"

    /**
     * 这门课这一学期的排课 ID：这条链上唯一的必填参数，实测值形如 `2488752`。
     * 界面要念的是驼峰原样，所以那份写人在 [ScanRejectClassifier]（只有显示用的差别）。
     */
    internal const val COURSE_SCHED_ID = "courseschedid"

    /** 取值形态照实测：纯数字。放宽到 `[0-9A-Za-z_-]` 是因为服务端别的 ID 也用过带横线的形状 */
    internal val SCHED_ID_PATTERN = Regex("^[0-9A-Za-z_-]{1,64}$")

    /**
     * @return [ScanTarget.IClass]，装着**原文**（只去过首尾空白）；null 表示这不是 iClass 的签到码。
     *
     * 门槛四道，缺一律不认：
     * 1. 绝对 http(s) URL；
     * 2. host 逐字等于 [IClassSignUrl.HOST]（拿 authority 切出来的 host 比对，不是 `contains`：
     *    `iclass.buaa.edu.cn:8081.evil.com` 那种后缀伪装在 [splitAuthority] 那一刀就掉了）；
     * 3. 路径落在 [SCAN_SIGN_ROUTE] 上（见那里写的理由）；
     * 4. query 里有非空 `courseSchedId`。
     *
     * 再加一道**排斥**条件：原文里已经带 `id=` 参数的不认。提交是
     * `原文 + "&id=" + User.id`（见 [IClassSignUrl.forSubmission]），再拼一个就成了同名双参数 ——
     * 那是服务端 `参数错误!` 的另一条来路；而课堂投影那张码里没有这一项，
     * 带它的只能是"某次请求的 URL"而不是码。
     */
    fun parse(raw: String?): ScanTarget.IClass? {
        val text = raw?.trim() ?: return null
        if (!text.startsWith("http://", ignoreCase = true) &&
            !text.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        val authority = urlAuthority(text) ?: return null
        // 端口不参与"是不是这张码"的判定（没有 :8081 的形态也认），但决定提交前要不要升级，
        // 那一步在 IClassSignUrl.upgradeToTls —— 它才对 host+端口这一**对**较真。
        val host = splitAuthority(authority)?.first ?: return null
        if (!host.equals(IClassSignUrl.HOST, ignoreCase = true)) return null
        if (!isScanSignRoute(text)) return null
        val schedId = queryValue(text, COURSE_SCHED_ID) ?: return null
        if (!SCHED_ID_PATTERN.matches(schedId)) return null
        if (queryValue(text, IClassSignUrl.USER_ID_PARAM) != null) return null
        return ScanTarget.IClass(rawUrl = text)
    }

    /**
     * 路由判定只吃 path 那一段（authority 之后、`?` 与 `#` 之前）。
     *
     * 为什么不整串 `contains`：参数值里也可能出现这个字符串（`?back=…stu_scan_sign.action`），
     * 那时候这张码根本不是签到入口，而是别的东西包了一层。
     *
     * T83：切 path 那一步搬到 [urlPathOf]（同一刀，[ScanRejectClassifier] 说 WrongRoute 那一档
     * 时要念它），本函数的判据一个字没动 —— 否则"收不收"与"为什么没收"就长成了两把尺子。
     */
    private fun isScanSignRoute(url: String): Boolean =
        urlPathOf(url)?.endsWith(SCAN_SIGN_ROUTE) == true

    /**
     * query 取值（键名大小写不敏感）：只看 `#` 之前那一截 —— 这张码没有 hash 路由。
     *
     * T83 起 `internal`：[ScanRejectClassifier] 判"参数齐不齐 / 值合不合形状"用的就是这两道
     * 门槛本身，不再抄一份取值逻辑。⚠️ 取到的值只许用来**判形状**，绝不许进日志或界面。
     */
    internal fun queryValue(url: String, key: String): String? {
        val query = url.substringBefore('#').substringAfter('?', "")
        if (query.isEmpty()) return null
        for (pair in query.split('&')) {
            if (!pair.contains('=')) continue
            if (pair.substringBefore('=').trim().lowercase() != key) continue
            val value = pair.substringAfter('=').trim()
            if (value.isNotEmpty()) return value
        }
        return null
    }
}

/**
 * 取一条绝对 URL 的 authority（`://` 之后、第一个 `/ ? #` 之前那段），取不到返回 null。
 *
 * 为什么不用 `java.net.URI`：这一族的码是从**投影照片**上解出来的，实测那串的常见形状里
 * 会有 URI 拒收的字符（未编码的空格、`{}`、`|`），而"URI 解不开"不等于"不是这张码"。
 * 手写切一段反而逐字可控：host 与端口都留在返回值里，谁要用谁自己按最后一个 `:` 分。
 */
internal fun urlAuthority(url: String): String? {
    val separator = url.indexOf("://")
    if (separator <= 0) return null
    val start = separator + 3
    if (start >= url.length) return null
    var end = url.length
    for (index in start until url.length) {
        val c = url[index]
        if (c == '/' || c == '?' || c == '#') {
            end = index
            break
        }
    }
    return if (end > start) url.substring(start, end) else null
}

/**
 * 一条绝对 URL 的 scheme（`://` 之前那段，逐字取、小写归一）；不是这个形状时返回 null。
 *
 * 判据不吃它（"是不是这张码"与 http/https 无关，见 [IClassQrParser] 的门槛四），
 * 它只服务 T83 那一行取证：`http:` 与 `https:` 是两种现场（前者才需要 [IClassSignUrl.upgradeToTls]）。
 */
internal fun urlSchemeOf(url: String): String? {
    val separator = url.indexOf("://")
    if (separator <= 0) return null
    return url.substring(0, separator).lowercase()
}

/**
 * 一条绝对 URL 的 path（authority 之后、第一个 `?` 之前，且只看到 `#` 为止）；没有 path 段时 null。
 *
 * 刀法与 [urlAuthority] 同一份：先 `substringBefore('#')` 再找 `?`，所以
 * `http://h/p?x=1#/y?q=2` 的 path 是 `/p`、`http://h/p#/y?q=2` 的也是 `/p`。
 * 这一刀原来是 [IClassQrParser.isScanSignRoute] 内联写的，T83 搬出来共用 ——
 * 判档的那一把尺子与收码的那把必须是同一把，两把就会有一把说谎。
 */
internal fun urlPathOf(url: String): String? {
    val head = url.substringBefore('#')
    val separator = head.indexOf("://")
    if (separator <= 0) return null
    val pathStart = head.indexOf('/', separator + 3)
    if (pathStart < 0) return null
    val queryStart = head.indexOf('?', pathStart).let { if (it < 0) head.length else it }
    return head.substring(pathStart, queryStart)
}

/**
 * authority 拆成 (host, port)：无端口时 port 是空串。
 *
 * 切的是**最后一个**冒号（host 里出现冒号只可能是端口分隔符），并且要求端口是纯数字，
 * 否则整段判为畸形返回 null。两条都是为后缀伪装准备的：
 * `iclass.buaa.edu.cn:8081.evil.com` 按 `startsWith` 会过关，按这一刀就落到
 * port=`8081.evil.com`（非数字）⇒ null ⇒ 不是这一族的码。
 * `user:pass@host` 同理（port 段带着 `@`，非数字）。
 */
internal fun splitAuthority(authority: String): Pair<String, String>? {
    val at = authority.lastIndexOf(':')
    if (at < 0) return authority to ""
    val port = authority.substring(at + 1)
    if (port.isEmpty() || !port.all { it.isDigit() }) return null
    return authority.substring(0, at) to port
}
