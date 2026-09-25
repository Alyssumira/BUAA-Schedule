package com.buaa.schedule.data.import

/**
 * 把扫码原文变成**能发出去的提交地址**。
 *
 * 两条实测事实叠在这一个文件上，少一条都签不成：
 *
 * 1. **提交 = 一次字符串拼接**：`GET <扫码原文> + "&id=" + <User.id>`。
 *    没有请求体、没有第四个参数、没有签名、没有 Cookie、没有鉴权头。
 *    ⚠️ 「解析出字段再重组 URL」就是错的（服务端回 `参数错误!`），所以这里一行都不重排：
 *    参数顺序、参数名、大小写、有没有编码，全部原样带过 [upgradeToTls] 与 [forSubmission]。
 * 2. **但 scheme 与端口要先升级**：`http://iclass.buaa.edu.cn:8081/…` →
 *    `https://iclass.buaa.edu.cn:8181/…`。实测 https 那一侧是同一个 webapp、同一份 JSON，
 *    证书是 GlobalSign 的 `*.buaa.edu.cn` DV。升级只为了一件事：**不必**为签到在清单里
 *    开 `usesCleartextTraffic` 例外（那是把整个应用的明文流量都放开）。
 *    升级严格限定在 `iclass.buaa.edu.cn` + `8081` 这一**对**上：别的 host、别的端口、
 *    已经是 https 的，一律原样返回。
 *
 * 两个函数都是纯函数（不碰网络、不碰 Android API、不读时钟），
 * 所以"不许动别家"这一档能在 JVM 里表驱动钉死，见 `IClassSignUrlTest`。
 */
object IClassSignUrl {

    /** 平台 host（唯一允许做端口升级的那一个） */
    const val HOST = "iclass.buaa.edu.cn"

    /** 扫码原文里带的明文端口 */
    const val CLEARTEXT_PORT = "8081"

    /** 同一个 webapp 的 TLS 端口（实测同机同应用，证书有效） */
    const val TLS_PORT = "8181"

    /** 拼接用的参数名：`id` 是 User.id（学号是另一条字段，签到不取它） */
    const val USER_ID_PARAM = "id"

    /** 拼接用的完整前缀，含前置 `&`（原文里必然已有 query，见 [forSubmission] 的说明） */
    private const val USER_ID_PREFIX = "&id="

    /**
     * http:8081 → https:8181，其余逐字保留。
     *
     * 判定按 authority 整段比 `host:port`，而不是 `startsWith("http://iclass.buaa.edu.cn:8081")`：
     * 后者会让 `http://iclass.buaa.edu.cn:8081.evil.com/` 这种后缀伪装也被"升级"成
     * 一条指向别处的 https 请求（那是把明文退化成加密的**外发**，更糟）。
     * 端口按"authority 最后一个冒号之后"切，所以 `:18081`、`:80` 都不命中。
     */
    fun upgradeToTls(raw: String): String {
        val separator = raw.indexOf("://")
        if (separator <= 0) return raw
        if (!raw.substring(0, separator).equals("http", ignoreCase = true)) return raw
        val authority = urlAuthority(raw) ?: return raw
        val parts = splitAuthority(authority) ?: return raw
        if (!parts.first.equals(HOST, ignoreCase = true) || parts.second != CLEARTEXT_PORT) return raw
        val rest = raw.substring(raw.indexOf(authority, separator + 3) + authority.length)
        return "https://$HOST:$TLS_PORT$rest"
    }

    /**
     * 一次提交真正打出去的那条地址：先 [upgradeToTls]，再在末尾拼 `&id=<User.id>`。
     *
     * `&` 而不是 `?`：能走到这一格的原文一定带 query（`courseSchedId` 是解析门槛之一，
     * 见 [IClassQrParser]），拼 `?` 会把原有一个 `?` 变成两个、把 `courseSchedId` 挤成空值。
     * 这里也**不**对 [userId] 做 URL 编码：它是登录响应里 `result.id` 的逐字值，
     * 编码一次就等于改写了服务端给我们的东西。
     */
    fun forSubmission(raw: String, userId: String): String {
        require(userId.isNotBlank()) { "iClass 提交没有 id 可拼：会话里就不该出现空白 id（调用方须先判空）" }
        return upgradeToTls(raw) + USER_ID_PREFIX + userId.trim()
    }
}
