package com.buaa.schedule.data.import

/**
 * 一次扫码解析出来的签到目标。
 *
 * 老师端二维码的**字面内容尚未取证**（H5 里没有生成逻辑，在 APP 原生侧），
 * 所以解析器按页面自己认识的两种入参形态给结果，真机首联后再收敛：
 *
 * - [ByCourse]：`?zjdm=..&czid=..`，H5 的 step=1 形态。拿到就能直接提交，不必先查详情。
 * - [ByQdid]：`?qdid=..`，H5 的扫码形态。`qdid` 就是签到活动的 `CJID`，
 *   先 `queryQdxxByQdid` 查出 `ZJDM/CZID`（同时也就知道是不是已经签过了）。
 */
sealed interface SpocSignTarget {
    data class ByCourse(val zjdm: String, val czid: String) : SpocSignTarget
    data class ByQdid(val qdid: String) : SpocSignTarget
}

/**
 * 把扫到的任意字符串归一化成 [SpocSignTarget]。
 *
 * 纯函数，不碰网络也不碰 Android API —— 二维码格式将来怎么变，改这里并补单测即可。
 */
object SpocQrParser {

    /** 签到 ID 允许字符集：服务端 ID 形如 `1AA9A5D7F4295A0DE0630211FE0AB83E`（32 位十六进制大写） */
    private val ID_PATTERN = Regex("^[0-9A-Za-z_-]{4,64}$")

    /**
     * 裸 ID 的收严版：形态三没有任何上下文（不是链接、不是路由），
     * `^[0-9A-Za-z_-]{4,64}$` 会命中一切身分/密钥/短链 ID —— 扫到别家 App 的码
     * 会直接拿本校账号去打智学北航接口。这里只认服务端实测过的形状：
     * 32 位十六进制（大小写都收，个别壳层会转小写）。
     */
    private val BARE_QDID_PATTERN = Regex("^[0-9A-Fa-f]{32}$")

    /** @return null 表示这根本不是智学北航的签到码（别的 App 的二维码、一串无关文本等） */
    fun parse(raw: String?): SpocSignTarget? {
        val text = raw?.trim() ?: return null
        if (text.isEmpty()) return null

        // 形态一：完整链接。参数可能落在主 query 上，也可能落在 hash 路由的 fragment query 上
        // （`/bhspoc/#/pages/table/signIn?qdid=..` 是后者），两处都要看。
        if (text.startsWith("http://", true) || text.startsWith("https://", true)) {
            val params = queryParams(text)
            // 域名门槛：不认 host 的话，随便一张带 zjdm 参数的外链都会被当成签到码。
            if (!isSpocUrl(text)) return null
            return fromParams(params)
        }

        // 形态二：只有路由片段（`/pages/table/signIn?qdid=..`），例如壳层自己拼过前缀。
        // 门槛是完整路由而不是 "signIn" 子串——后者会命中任何带登录字样的外链文本。
        if (text.contains(SIGN_IN_ROUTE, true)) {
            return fromParams(queryParams(text))
        }

        // 形态三：裸 ID。只认实测形状（32 位十六进制）；带空格、换行或长度不对的
        // 一律不当 ID 处理
        if (BARE_QDID_PATTERN.matches(text)) return SpocSignTarget.ByQdid(text)
        return null
    }

    private const val SIGN_IN_ROUTE = "/pages/table/signIn"

    private fun isSpocUrl(url: String): Boolean {
        val host = runCatching { java.net.URI(url.substringBefore('#').ifEmpty { url }).host }.getOrNull()
        if (host != null) return host == "spoc.buaa.edu.cn" || host.endsWith(".buaa.edu.cn")
        // URI 解析失败（原生壳偶尔会带奇怪的前缀）时退化到子串判定
        return url.contains("spoc.buaa.edu.cn") || url.contains(SIGN_IN_ROUTE, true)
    }

    /** 从 `zjdm`+`czid` 或 `qdid` 组装目标；两种都有时优先前者（少一次查询） */
    private fun fromParams(params: Map<String, String>): SpocSignTarget? {
        val zjdm = params["zjdm"]
        val czid = params["czid"]
        if (!zjdm.isNullOrBlank() && !czid.isNullOrBlank()) return SpocSignTarget.ByCourse(zjdm.trim(), czid.trim())
        val qdid = params["qdid"]?.trim()
        return qdid?.takeIf { ID_PATTERN.matches(it) }?.let { SpocSignTarget.ByQdid(it) }
    }

    /**
     * 收集 URL 里两截 query 的参数：`?...` 与 hash 路由里的 `#/?...`。
     *
     * 同名时以**后出现**的 fragment 参数为准 —— hash 路由是 H5 真正的入参来源，
     * 主 query 往往是分享链接包装层留下的。
     */
    private fun queryParams(url: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        // 两段各自只剥一次 '?'：'#' 之前是主 query，之后是 hash 路由自带的 query。
        // 没有 '#' 时第一段就是整条 URL，第二段为空串直接跳过。
        for (segment in listOf(url.substringBefore('#', url), url.substringAfter('#', ""))) {
            val query = segment.substringAfter('?', "")
            if (query.isBlank()) continue
            for (pair in query.split('&')) {
                if (!pair.contains('=')) continue
                val key = pair.substringBefore('=').trim().lowercase()
                val value = pair.substringAfter('=').trim()
                if (key.isNotEmpty() && value.isNotEmpty()) result[key] = urlDecode(value)
            }
        }
        return result
    }

    private fun urlDecode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
