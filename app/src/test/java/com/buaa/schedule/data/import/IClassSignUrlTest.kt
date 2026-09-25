package com.buaa.schedule.data.import

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 「扫码原文 → 提交地址」那一步的两个纯函数，表驱动。
 *
 * 这一档值得单独钉，是因为它的两条规则**互相拉扯**：
 * - 提交必须是拼接（原文逐字 + `&id=`），提取字段再重组就是服务端那句 `参数错误!`；
 * - 但 scheme/端口要升级成 https:8181，而且**只**升级 `iclass.buaa.edu.cn` + `8081` 这一对。
 * 升级写宽一点就会碰到别的 host（那是把明文请求改成加密的**外发**，比明文更糟），
 * 写窄一点就会把真码留在明文端口上、然后被平台层「默认禁明文」掐掉 ——
 * 而清单里**没有**、也不许有 `usesCleartextTraffic` 例外。两个方向都由下面的表钉住。
 */
class IClassSignUrlTest {

    private val realCode =
        "http://iclass.buaa.edu.cn:8081/app/course/stu_scan_sign.action" +
            "?courseSchedId=2488752&timestamp=1790247391994"

    /** 升级：命中项（输入 → 期望输出） */
    @Test
    fun upgradeHitsTheExactHostPortPair() {
        val expected = realCode
            .replace("http://iclass.buaa.edu.cn:8081", "https://iclass.buaa.edu.cn:8181")
        val cases = listOf(
            // 真码逐字
            realCode to expected,
            // 只有 authority、没有 path
            "http://iclass.buaa.edu.cn:8081" to "https://iclass.buaa.edu.cn:8181",
            // 无 query（解析器不会给这种进来，纯函数不替它设门槛）
            "http://iclass.buaa.edu.cn:8081/app/course/stu_scan_sign.action" to
                "https://iclass.buaa.edu.cn:8181/app/course/stu_scan_sign.action",
            // scheme 与 host 的大小写都收，输出走规范形状
            "HTTP://ICLASS.BUAA.EDU.CN:8081/app/x?action=stu_scan_sign.action?a=1" to
                "https://iclass.buaa.edu.cn:8181/app/x?action=stu_scan_sign.action?a=1",
            // 端口位上的后缀伪装：不命中（期望原样返回）
            "http://iclass.buaa.edu.cn:8081.evil.com/app/x?courseSchedId=1" to
                "http://iclass.buaa.edu.cn:8081.evil.com/app/x?courseSchedId=1",
            // 已经是 https：一个字都不动（升级是 http:8081 → https:8181 这一次性的事）
            "https://iclass.buaa.edu.cn:8181/app/x?courseSchedId=1" to
                "https://iclass.buaa.edu.cn:8181/app/x?courseSchedId=1",
            // 同 host 别的端口、同端口别的 host、没端口：全都不动
            "http://iclass.buaa.edu.cn:8082/app/x?a=1" to "http://iclass.buaa.edu.cn:8082/app/x?a=1",
            "http://iclass.buaa.edu.cn:18081/app/x?a=1" to "http://iclass.buaa.edu.cn:18081/app/x?a=1",
            "http://iclass.buaa.edu.cn:80/app/x?a=1" to "http://iclass.buaa.edu.cn:80/app/x?a=1",
            "http://iclass.buaa.edu.cn/app/x?a=1" to "http://iclass.buaa.edu.cn/app/x?a=1",
            "http://spoc.buaa.edu.cn:8081/spocnewht/x?a=1" to "http://spoc.buaa.edu.cn:8081/spocnewht/x?a=1",
            "http://evil-iclass.buaa.edu.cn:8081/app/x?a=1" to "http://evil-iclass.buaa.edu.cn:8081/app/x?a=1",
            // 根本不是 URL / 空串：原样返回，不抛
            "" to "",
            "  " to "  ",
            "/app/course/stu_scan_sign.action?courseSchedId=1" to
                "/app/course/stu_scan_sign.action?courseSchedId=1",
            "ftp://iclass.buaa.edu.cn:8081/app/x?a=1" to "ftp://iclass.buaa.edu.cn:8081/app/x?a=1",
        )
        cases.forEach { (input, output) ->
            assertEquals("升级判据对这条动了不该动的手：$input", output, IClassSignUrl.upgradeToTls(input))
        }
    }

    /** 幂等：升级过一次再喂回来，一个字都不再变（滚动码可能被连解几帧，路径要稳定） */
    @Test
    fun upgradeIsIdempotent() {
        val once = IClassSignUrl.upgradeToTls(realCode)
        assertEquals(once, IClassSignUrl.upgradeToTls(once))
    }

    /** 升级之后 query 一个字符都不许变：参数名、顺序、值、有没有编码都算契约 */
    @Test
    fun upgradeLeavesPathAndQueryByteForByte() {
        val upgraded = IClassSignUrl.upgradeToTls(realCode)
        val tail = "/app/course/stu_scan_sign.action?courseSchedId=2488752&timestamp=1790247391994"
        assertEquals("尾段被改写过：$upgraded", tail, upgraded.removePrefix("https://iclass.buaa.edu.cn:8181"))
        assertEquals("参数顺序与个数都原样", 2, upgraded.substringAfter('?').split('&').size)
        assertEquals("timestamp 还是那一个", "1790247391994", upgraded.substringAfter("timestamp=").substringBefore('&'))
        assertEquals("courseSchedId 还是那一个", "2488752", upgraded.substringAfter("courseSchedId=").substringBefore('&'))
    }

    /** 提交地址 = 升级后的原文 + `&id=` + User.id：一次拼接，第四个参数都不许有 */
    @Test
    fun submissionIsPlainConcatenation() {
        assertEquals(
            "https://iclass.buaa.edu.cn:8181/app/course/stu_scan_sign.action" +
                "?courseSchedId=2488752&timestamp=1790247391994&id=998877",
            IClassSignUrl.forSubmission(realCode, "998877"),
        )
    }

    /** id 只 trim 不编码：它是登录响应里 `result.id` 的逐字值 */
    @Test
    fun submissionTrimsButNeverEncodesTheId() {
        val expected = IClassSignUrl.forSubmission(realCode, "998877")
        assertEquals(expected, IClassSignUrl.forSubmission(realCode, "  998877\n"))
        assertEquals(
            "带空格的 id 原样拼（编码一次就是改写服务端给我们的东西）",
            expected + "x y",
            IClassSignUrl.forSubmission(realCode, "998877x y"),
        )
    }

    /** 空白 id 不许变成一条"看着合法、服务端必拒"的请求：调用方（会话层）得先把住 */
    @Test
    fun submissionRefusesBlankId() {
        for (blank in listOf("", "   ", "\n")) {
            assertThrows(IllegalArgumentException::class.java) { IClassSignUrl.forSubmission(realCode, blank) }
        }
    }

    /** 端口常量本身也是契约的一部分（写在两处会各漂各的），这里钉一次形状 */
    @Test
    fun portConstantsAreTheDocumentedPair() {
        assertEquals("8081", IClassSignUrl.CLEARTEXT_PORT)
        assertEquals("8181", IClassSignUrl.TLS_PORT)
        assertEquals("iclass.buaa.edu.cn", IClassSignUrl.HOST)
        assertEquals("id", IClassSignUrl.USER_ID_PARAM)
    }
}
