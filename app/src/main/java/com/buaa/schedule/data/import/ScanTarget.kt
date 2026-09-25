package com.buaa.schedule.data.import

/**
 * 一次扫码要提交给**哪一个平台**。
 *
 * 为什么还需要这一枚类型（T85 之后的实话）：本仓原先整条签到链只接了智学北航（SPOC），
 * 而学生课上真扫的那张码来自**另一个平台**（北航 iClass，竞业达「轻新课堂」）。两族码的提交形状
 * 根本不同 —— SPOC 是「取字段 → POST JSON → `token` 请求头」，iClass 是「扫码原文逐字保留
 * → GET 拼一个 `&id=`」。把两族捏进同一个「解析出几个字段」的目标里，后者一定会被
 * 前者的形状扭曲（实测：拿提取出来的字段重组 URL 打到 iClass，服务端回 `参数错误!`）。
 * 所以 [IClass] 这一支**只装原文、不装字段**。
 *
 * T85 按用户决定「只留 iClass，把 SPOC 那条拆掉」之后，这里只剩一支。类型本身留着，
 * 不是为了给下一族预留位置（那是没人要的抽象），而是因为它把"提交形状"写进了类型：
 * 谁要在解析层顺手抽几个字段出来，就得先改掉这颗只装原文的壳 —— 那正是上面那条实测教训
 * 唯一还能被机器看见的地方。
 */
sealed interface ScanTarget {

    /**
     * 北航 iClass（竞业达轻新课堂）。
     *
     * [rawUrl] 是**扫码得到的原文**：只剥掉首尾空白，其余一字不动 ——
     * scheme、`:8081` 那枚端口、参数顺序与参数名都是契约的一部分
     * （提交前的端口升级见 [IClassSignUrl.upgradeToTls]，它同样不重排任何东西）。
     */
    data class IClass(val rawUrl: String) : ScanTarget
}

/**
 * 扫码签到唯一的收码口：一张码 → 要么是一条 [ScanTarget.IClass]，要么是 null。
 *
 * T85 之前这里是两族轮着试（`IClassQrParser.parse(raw) ?: SpocQrParser.parse(raw)`），
 * 而那条次序带着一段理由：SPOC 的域名门槛是 `*.buaa.edu.cn`，iClass 那张码过得了域名、
 * 只死在参数名上，所以先跑 iClass 才不会被"哪天码里多出一个叫 `qdid` 的参数"抢走。
 * ⚠️ **那段理由现在已经不成立了** —— 抢码的那一族已经被拆掉，这里只剩一次调用，
 * 没有次序可言。留着它就是在描述一场不存在的竞争（注释里最坏的一种谎）。
 *
 * 与解析器本体一样：纯 JVM，不碰 Android API、不读时钟
 * （仓库口径，纯度由 `ScanTargetParserTest` 的守卫钉住）。
 */
object ScanTargetParser {

    /**
     * @return null 表示这不是一张北航 iClass 的课堂签到码（别家 App 的二维码、
     * 一串无关文本、智学北航那族的链接都算在内 —— T85 之后 `*.buaa.edu.cn` 里
     * 只有 `iclass.buaa.edu.cn` 这一台被认）。
     *
     * ⚠️ null 只说"不收"，不说**为什么**不收 —— 那一句由 [ScanRejectClassifier.classify] 答
     * （T83：界面按档说话，取证按形状留痕）。两者对"收不收"用的是同一道门槛：
     * 判据第一问就是问这一颗。
     */
    fun parse(raw: String?): ScanTarget? = IClassQrParser.parse(raw)
}
