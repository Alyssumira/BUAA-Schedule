package com.buaa.schedule.data.import

/**
 * 一次扫码要提交给**哪一个平台**。
 *
 * 为什么需要这一枚共用类型：本仓原先整条签到链只接了智学北航（SPOC），而学生课上真扫
 * 的那张码来自**另一个平台**（北航 iClass，竞业达「轻新课堂」）。两族码的提交形状根本
 * 不同 —— SPOC 是「取字段 → POST JSON → `token` 请求头」，iClass 是「扫码原文逐字保留
 * → GET 拼一个 `&id=`」。把两族捏进同一个「解析出几个字段」的目标里，后者一定会被
 * 前者的形状扭曲（实测：拿提取出来的字段重组 URL 打到 iClass，服务端回 `参数错误!`）。
 * 所以 [IClass] 这一支**只装原文、不装字段**。
 */
sealed interface ScanTarget {

    /** 智学北航（SPOC）：字段化目标，交给 [SpocApi] 那一条链 */
    data class Spoc(val target: SpocSignTarget) : ScanTarget

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
 * 两族签到码的统一入口：先按 iClass 认（host 精确匹配，抢不走 SPOC 的码），
 * 落空再退回既有的 [SpocQrParser]。
 *
 * 次序是有意的：[SpocQrParser] 的域名门槛是 `*.buaa.edu.cn`，iClass 那张码
 * **过得了域名、死在参数名**（它只认 `zjdm/czid/qdid`）。今天先跑哪一族都是 null，
 * 但哪天 iClass 的码里多出一个叫 `qdid` 的参数，就会被 SPOC 那一族抢走、
 * 打去智学北航 —— 那是「签错平台」，比解析失败难查得多。
 *
 * 与两族各自的本体一样：纯 JVM，不碰 Android API、不读时钟
 * （仓库口径，纯度由 `ScanTargetParserTest` 的守卫钉住）。
 */
object ScanTargetParser {

    /**
     * @return null 表示这**两族**都不是（别的 App 的二维码、一串无关文本等）。
     *
     * ⚠️ null 只说"不收"，不说**为什么**不收 —— 那一句由 [ScanRejectClassifier.classify] 答
     * （T83：界面按档说话，取证按形状留痕）。两者对"收不收"用的是同一道门槛：
     * 判据第一问就是问这一颗。
     */
    fun parse(raw: String?): ScanTarget? =
        IClassQrParser.parse(raw) ?: SpocQrParser.parse(raw)?.let { ScanTarget.Spoc(it) }
}
