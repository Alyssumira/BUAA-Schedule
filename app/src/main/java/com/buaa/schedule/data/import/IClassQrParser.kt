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
 * 判据：`*.buaa.edu.cn` 下面住着的不止这一台（教务、智学北航、以及一批叫不出名字的系统），
 * host 一松就会把别家的码打到这里、或把这里的码打给别家。T85 把智学北航那一族整条拆掉之后，
 * 这道门槛从"两族之间的分界"变成了"本仓唯一一条链的入口"，判据一个字没改。
 * 判定与 [IClassSignUrl] 一样是纯 JVM、零 android import、零时钟读取
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
     *    `iclass.buaa.edu.cn:8081.evil.com` 那种后缀伪装在
     *    [com.buaa.schedule.domain.url.splitAuthority] 那一刀就掉了）；
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
        val authority = com.buaa.schedule.domain.url.urlAuthority(text) ?: return null
        // 端口不参与"是不是这张码"的判定（没有 :8081 的形态也认），但决定提交前要不要升级，
        // 那一步在 IClassSignUrl.upgradeToTls —— 它才对 host+端口这一**对**较真。
        // T302①：这一刀搬到了 domain/url/UrlShape.kt（全仓一枚），跨层调用写全限定名 ——
        // 本文件的零 import 行是 ScanTargetParserTest 那枚纯度守卫钉着的，不许为了少打几个字请回 import。
        val host = com.buaa.schedule.domain.url.splitAuthority(authority)?.first ?: return null
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
     * T83：切 path 那一步搬到 [com.buaa.schedule.domain.url.urlPathOf]（同一刀，
     * [ScanRejectClassifier] 说 WrongRoute 那一档
     * 时要念它），本函数的判据一个字没动 —— 否则"收不收"与"为什么没收"就长成了两把尺子。
     * T302① 那一刀与上面两枚一起住进 `domain/url/UrlShape.kt`：全仓只许一枚 authority/path 刀。
     */
    private fun isScanSignRoute(url: String): Boolean =
        com.buaa.schedule.domain.url.urlPathOf(url)?.endsWith(SCAN_SIGN_ROUTE) == true

    /**
     * query 取值（键名大小写不敏感）：只看 `#` 之前那一截 —— 这张码没有 hash 路由。
     *
     * T83 起 `internal`：[ScanRejectClassifier] 判"参数齐不齐 / 值合不合形状"用的就是这两道
     * 门槛本身，不再抄一份取值逻辑。⚠️ 取到的值只许用来**判形状**，绝不许进日志或界面。
     * T85 起切 query 那一刀收进 [queryPairs]：取值与念名字必须走同一把尺子。
     */
    internal fun queryValue(url: String, key: String): String? {
        for ((name, value) in queryPairs(url)) {
            if (name == key) return value
        }
        return null
    }

    /**
     * 这条链接上**看得见的参数名**（小写、按出现次序、值为空的不算看见、同名只念一次）。
     *
     * 取证行与失败卡都念这一串。单独一颗而不用 [queryValue]：⚠️ "念名字"与"看见值"之间那道
     * 界限一旦要靠调用方自觉，就早晚会有人把整个 query 拼进日志。
     * 这一颗原先长在 `SpocQrParser` 里（判档那一边只有它用），T85 拆掉 SPOC 时跟着判据搬回来。
     */
    internal fun queryParamNames(url: String): List<String> = queryPairs(url).map { it.first }.distinct()

    /** query 段里看得见的 `(名字, 值)` 对；值为空的直接不算看见（[queryValue] 与 [queryParamNames] 同源） */
    private fun queryPairs(url: String): List<Pair<String, String>> {
        val query = url.substringBefore('#').substringAfter('?', "")
        if (query.isEmpty()) return emptyList()
        val pairs = ArrayList<Pair<String, String>>()
        for (pair in query.split('&')) {
            if (!pair.contains('=')) continue
            val name = pair.substringBefore('=').trim().lowercase()
            val value = pair.substringAfter('=').trim()
            if (name.isNotEmpty() && value.isNotEmpty()) pairs += name to value
        }
        return pairs
    }
}
