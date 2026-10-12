package com.buaa.schedule.domain.url

/**
 * 绝对 URL 的三刀：scheme / authority / path，外加把 authority 拆成 (host, port)。
 *
 * T302① 把这一族从 `data/import/IClassQrParser.kt` 尾部整枚搬进来（本体逐字未改，只搬家），
 * 因为全仓有**两个**现场要用同一把刀，而"第二把尺"是台账 #455 那一族的成因：
 *
 * - 签到码那一路：`data/import/IClassQrParser.kt`（host 精确比 `iclass.buaa.edu.cn`）、
 *   `data/import/IClassSignUrl.kt`（比 `host`+`port` 这一**对**才升级 TLS）、
 *   `data/import/ScanReject.kt`（说"为什么不收这张码"要念同一刀的形状件）；
 * - 教务会话那一路：`EduWebOrigin.kt` 里的 `eduDocumentVerdict` 与 `sessionDomainVerdict`
 *   （比 `byxt.buaa.edu.cn` 与 `*.buaa.edu.cn`）。
 *
 * ## 为什么是手写刀而不是 `java.net.URI`
 *
 * 不是口味问题，是实测出来的：T302⓪ 把 22 格 URL 同时喂进 URI 与这一族（纯 JVM scratch，JDK 21），
 * 两枚说谎的方向不一样 ——
 *
 * 1. `…/jwapp/x?a= b`（query 里一枚裸 ASCII 空格）：`java.net.URI` **抛** `URISyntaxException`，
 *    于是"URI 解不开"被念成"不是这个域"。那一格是真教务文档，URI 版会把会话判死。
 * 2. `https://byxt.buaa.edu.cn:443.evil.com/`：`URI.getHost()` 交回 **null**（端口位不是数字 ⇒ 整个 host
 *    读不出来），报错的信息量是零；这一族交回"端口非纯数字 ⇒ 畸形"，档位说得出为什么。
 * 3. `HTTPS://BYXT.BUAa.EDU.CN/`：`URI` **不折大小写**（scheme 给 `HTTPS`、host 原样大写），
 *    拿它比对就必须自己再 lowercase 一次 —— 那还是这一族的写法。
 *
 * 手写切一段反而逐字可控：host 与端口都留在返回值里，谁要用谁自己按最后一个 `:` 分。
 * 而"URI 解不开"从来不等于"不是这个域"，也不等于"不是这张码"。
 *
 * 纯度：本文件零 import（`java.net.URI` 都不必请，见上）、不读钟、不读时区、不读权限、
 * 不碰 `Build.VERSION`、不查库 —— 四刀都是 `String? -> String?/Pair?` 的纯函数。
 */

/**
 * 取一条绝对 URL 的 authority（`://` 之后、第一个 `/ ? #` 之前那段），取不到返回 null。
 *
 * 空 authority（`https:///x`）也算取不到：`end > start` 那一格就是为它写的。
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
 * 判据不吃它（"是不是这张码"与 http/https 无关，见 IClassQrParser 的门槛四），它服务两件事：
 * T83 那一行取证（`http:` 与 `https:` 是两种现场，前者才需要 IClassSignUrl.upgradeToTls），
 * 以及 T302 的 [eduDocumentVerdict] —— 那一头**必须**先 gate scheme，否则
 * `{bad|brace}://byxt.buaa.edu.cn/` 这种畸形串会被手写刀切成一枚"看着像教务"的 authority。
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
 * 这一刀原来是 IClassQrParser.isScanSignRoute 内联写的，T83 搬出来共用 ——
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
 *
 * ⚠️ 这一刀**不**替 `@`（userinfo）动刀：authority 的合法形状是 `[userinfo@]host[:port]`，
 * 谁要判"服务器到底是哪台"得自己决定剥不剥 userinfo —— `EduWebOrigin.kt` 那一枚不剥
 * （它比的是整段 host，带 `@` 的串自然落在"不是教务那一台"那一档），签到那一路同理。
 */
internal fun splitAuthority(authority: String): Pair<String, String>? {
    val at = authority.lastIndexOf(':')
    if (at < 0) return authority to ""
    val port = authority.substring(at + 1)
    if (port.isEmpty() || !port.all { it.isDigit() }) return null
    return authority.substring(0, at) to port
}
