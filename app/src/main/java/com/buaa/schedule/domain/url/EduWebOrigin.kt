package com.buaa.schedule.domain.url

/**
 * 「当前这枚文档 URL 是不是教务 origin」与「这枚 URL 允不允许留在会话 WebView 里」的**唯一判据内核**（T302，在册 #515）。
 *
 * ## 病在哪（改前盘面，行号是那棵树现算的，以字面量为准）
 *
 * 会话的唯一真相是「当前 URL 还在不在教务 origin 上」，而这句话在 `31ae541` 的盘面上被写了**四遍**，
 * 四遍的宽窄各不相同（每格实测值见 T302⓪ 那枚提交）：
 *
 * - `data/import/BuaaWebSession.kt` 的 `:354` 与 `:605`：`startsWith("$BYXT_ORIGIN/")` —— 带尾斜杠。
 *   它挡住了 `https://byxt.buaa.edu.cn.evil.com/` 与 `https://byxt.buaa.edu.cn@evil.com/`
 *   （第 24 枚字符实测是 `.` 与 `@`），**但也挡住了三枚合法格**：`https://byxt.buaa.edu.cn`（无尾斜杠）、
 *   `https://byxt.buaa.edu.cn:443/`、`HTTPS://BYXT.BUAa.EDU.CN/`。审计把最重那句压在这一枚上，是错的。
 * - `ui/importing/BuaaLoginScreen.kt` 的 `:120`：`startsWith(BYXT_ORIGIN)` —— **少一枚尾斜杠**。
 *   它对上面那两枚后缀伪装**全部命中**，于是「教务系统页面已就绪」这一句在外人文档上说了谎。
 *   后果被两层挡着（注入脚本自带 `location.origin` 哨兵、会话复用那一头还有带斜杠的 `:354`），
 *   所以本卡定档＝**判据说谎**，不是「外人的返回被喂进导入链」。定档不往上夸大，也不往下抹。
 * - `data/import/BuaaWebSession.kt` 的 `:509-511`：`java.net.URI(it).host` + `endsWith(".buaa.edu.cn")`。
 *   这一枚问的是**另一个问题**（允许留在会话里的北航域，含 SSO 子域），却带着 URI 那把刀的旧伤：
 *   query 里一枚裸 ASCII 空格就能让它抛 ⇒ 真教务文档被判成「非北航域」当场拆会话；大小写也不折。
 * - `ui/importing/BuaaLoginScreen.kt` 的 `:580`：硬编码 `…/jwapp/sys/homeapp/` 字面量。它问的是第三件事
 *   （是不是教务**首页那一条路由**），本内核不替它判，故这一枚不动（登记在册）。
 *
 * ## 为什么是手写 authority 刀而不是 `java.net.URI`
 *
 * 丙档（换成 URI 逐段比 authority+port）被实测否掉：`…/jwapp/x?a= b` 那一格里 URI 抛，判据就把一枚
 * 真教务文档念成「不是这个域」—— 比带斜杠的 :354 还窄。URI 也不折大小写，修不了 `HTTPS://BYXT…` 那一格。
 * 于是本内核复用 [urlSchemeOf] / [urlAuthority] / [splitAuthority] 这三刀（`domain/url/UrlShape.kt`，
 * 与签到码那一路**同一把**尺子，台账 #455 那一族的病灶就是「唯一真源旁边又各写一遍」），
 * 比较纪律则照丙档收：逐段比 scheme / host / port，绝不对整串做前缀比较。
 *
 * ## 两个问题两枚函数（不许折成一枚 Boolean）
 *
 * [eduDocumentVerdict] 答「是不是教务 origin」（就绪判据、会话真相）；
 * [sessionDomainVerdict] 答「允不允许留在会话 WebView 里」（北航域，含 `sso.buaa.edu.cn` 那一族）。
 * 两枚返回值都是 sealed 分档：调用点要念的句子不同（「重定向到非北航域」／「会话已失效，需要重新登录」），
 * 折成一枚 Boolean 就是把「会话没了」与「页面还没就绪」念成同一句话（本仓那条老禁令）。
 *
 * ## 判据顺序（顺序即语义）
 *
 * [eduDocumentVerdict]：读不出 authority / 端口位非纯数字 ⇒ [EduDocument.Unparseable] →
 * host 逐段不等于教务 host ⇒ [EduDocument.NotEduHost]（后缀伪装、userinfo 伪装、`sso`、`buaa.edu.cn` 本尊
 * 全落在这一档）→ scheme `https` 且端口缺省或 `443` ⇒ [EduDocument.OnEduOrigin] →
 * 同 host 而端口是别的数字 ⇒ [EduDocument.PortMismatch] → 同 host 而 scheme 是 `http` ⇒
 * [EduDocument.SchemeDowngraded] → scheme 既不是 https 也不是 http ⇒ [EduDocument.Unparseable]。
 * host 比的是**整段**（`equals(ignoreCase = true)`）而不是 `endsWith`：教务只有那一台，
 * `byxt.buaa.edu.cn.evil.com`、`byxt.buaa.edu.cnevil.com`、`evil.com@byxt.buaa.edu.cn` 都不许命中。
 *
 * [sessionDomainVerdict]：同样先过三刀 → scheme 必须是 http(s) → host 等于 `buaa.edu.cn` 或以
 * `.buaa.edu.cn` 结尾（大小写归一后逐段比）才算 [SessionDomain.AllowedBuaaDomain]。
 * 这一枚**不看端口**（会话 WebView 留在哪个北航端口上都还是北航的页面，拆掉它才是代价）。
 *
 * ## 纯度（硬边界）
 *
 * 本文件零 android import、不读钟、不读时区、不读权限、不碰 `Build.VERSION`、不查库；
 * 连 `java.net.URI` 都不必请（见上）。两枚函数都是 `String? -> 分档`，WebView 是调用点的事。
 * 「返回布尔后调用点各自再判一次 SDK_INT」这一形同样不许：分档就是为了让调用点无档可补。
 */
object EduWebOrigin {

    /** 教务站点 scheme（实测只走 TLS；明文那一档不是同一个 origin，见 [EduDocument.SchemeDowngraded]） */
    const val SCHEME = "https"

    /** 教务站点 host（唯一真源；`data/import/` 那枚 `BYXT_ORIGIN` 由它派生，字面量在 app/src/main 里只许出现一次） */
    const val HOST = "byxt.buaa.edu.cn"

    /** `https` 的缺省端口：URL 里写不写它都是同一枚 origin，所以它必须与「不带端口」同档 */
    const val TLS_PORT = "443"

    /** 会话 WebView 允许留在其中的注册域（它的任意子域都算，SSO 就住在这一族里） */
    const val BUAA_DOMAIN = "buaa.edu.cn"

    /** 教务站点 origin：登录回跳、页面内接口的绝对前缀、会话判定共用这一枚（R5 F-39 ＋ T302） */
    const val ORIGIN = "https://$HOST"
}

/** [eduDocumentVerdict] 的五档；每档都是一句不同的话，所以不许并成 Boolean */
sealed interface EduDocument {

    /** 就是教务 origin：https ＋ host 逐段相等 ＋ 端口缺省或 443 ⇒ 页面就绪，可以在它里面发教务请求 */
    data object OnEduOrigin : EduDocument

    /** host 对而 scheme 是 `http`：明文那一档与教务那台不是同一个 origin（也不是同一份 Cookie） */
    data object SchemeDowngraded : EduDocument

    /** host 对而端口是别的数字（`:8443`、`:8081`）：同源策略下那是另一台服务器，页面内 fetch 打不到教务接口 */
    data object PortMismatch : EduDocument

    /** host 逐段不相等：后缀伪装（`.evil.com`、`cnevil.com`）、userinfo 伪装、`sso`、`buaa.edu.cn` 本尊全在这一档 */
    data object NotEduHost : EduDocument

    /** 读不出 authority（没有 `://`、`about:blank`、空 authority）、端口位不是纯数字、scheme 不是 http(s)、URL 为 null */
    data object Unparseable : EduDocument
}

/** [sessionDomainVerdict] 的三档 */
sealed interface SessionDomain {

    /** 北航域（`buaa.edu.cn` 及其任意子域）：会话 WebView 可以留在里面，通常意味着还停在 SSO 那一头 */
    data object AllowedBuaaDomain : SessionDomain

    /** 不是北航的域：一律 stopLoading + 拆掉（会话 Cookie 不该跟着走出国门） */
    data object ForeignDomain : SessionDomain

    /** 连 host 都读不出来（畸形串、null、`about:blank`）：与「外人」同命，但句子得说得出为什么 */
    data object Unparseable : SessionDomain
}

/**
 * 这枚文档 URL 是不是教务 origin（就绪判据）。`null` 与垃圾串都收敛到 [EduDocument.Unparseable]，
 * 判据本体不抛异常 —— 调用点不需要 `runCatching`，也不需要在它后面补一档 `Build.VERSION`。
 */
fun eduDocumentVerdict(url: String?): EduDocument {
    if (url == null) return EduDocument.Unparseable
    val authority = urlAuthority(url) ?: return EduDocument.Unparseable
    val parts = splitAuthority(authority) ?: return EduDocument.Unparseable
    if (!parts.first.equals(EduWebOrigin.HOST, ignoreCase = true)) return EduDocument.NotEduHost
    val scheme = urlSchemeOf(url) ?: return EduDocument.Unparseable
    return when {
        scheme == EduWebOrigin.SCHEME && (parts.second.isEmpty() || parts.second == EduWebOrigin.TLS_PORT) ->
            EduDocument.OnEduOrigin

        scheme == EduWebOrigin.SCHEME -> EduDocument.PortMismatch
        scheme == "http" -> EduDocument.SchemeDowngraded
        else -> EduDocument.Unparseable
    }
}

/**
 * 这枚 URL 允不允许留在会话 WebView 里（北航域，含 SSO 子域）。
 *
 * 与 [eduDocumentVerdict] 是两个问题：教务 origin 只有一台，会话域含一整族子域；
 * 拿 [eduDocumentVerdict] 顶它会把停在 SSO 登录页的会话当成外站拆掉（那正是 [SessionDomain.AllowedBuaaDomain]
 * 存在的理由），拿 `endsWith` 顶它则会把后缀伪装当成北航域。
 */
fun sessionDomainVerdict(url: String?): SessionDomain {
    if (url == null) return SessionDomain.Unparseable
    val authority = urlAuthority(url) ?: return SessionDomain.Unparseable
    val parts = splitAuthority(authority) ?: return SessionDomain.Unparseable
    val scheme = urlSchemeOf(url) ?: return SessionDomain.Unparseable
    if (scheme != EduWebOrigin.SCHEME && scheme != "http") return SessionDomain.Unparseable
    val host = parts.first.lowercase()
    val apex = EduWebOrigin.BUAA_DOMAIN
    return if (host == apex || host.endsWith(".$apex")) SessionDomain.AllowedBuaaDomain else SessionDomain.ForeignDomain
}
