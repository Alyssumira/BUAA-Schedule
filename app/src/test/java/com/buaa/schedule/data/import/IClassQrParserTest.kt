package com.buaa.schedule.data.import

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * iClass 那张码的识别，以及「原文有没有被偷偷改写」。
 *
 * 真码是一张投影照片的实测解码结果，本文件把它当**字面常量**钉住：这一族的提交是
 * 字符串拼接而不是字段重组（见 [IClassSignUrl] 的取证段），所以"解析器有没有动过原文"
 * 是这条链上最贵的一档 —— 改写一个字，服务端就回 `参数错误!`，而用户看到的只是一次签到失败。
 *
 * 另一半判据是**排斥**：host 后缀伪装、别家 host、路由不对（尤其 `stu_auto_sign.action`
 * 那条自动定位签到）、缺 `courseSchedId`、原文里已经带 `id=` —— 五档都要落到 null。
 * 落到 null 才有「这不是签到码」那句实话；落进 [ScanTarget.IClass] 就是拿本校凭据
 * 往一个错的地方发请求。
 */
class IClassQrParserTest {

    /** 真码逐字（zxing-cpp 解投影照片的结果）；`timestamp` 换算过去就是拍照那一刻 */
    private val realCode =
        "http://iclass.buaa.edu.cn:8081/app/course/stu_scan_sign.action" +
            "?courseSchedId=2488752&timestamp=1790247391994"

    @Test
    fun `真码被认成 iClass 目标且原文逐字`() {
        assertEquals(ScanTarget.IClass(realCode), IClassQrParser.parse(realCode))
    }

    @Test
    fun `首尾空白剥掉其余一字不动`() {
        assertEquals(ScanTarget.IClass(realCode), IClassQrParser.parse("\n  $realCode  \t"))
    }

    @Test
    fun `已经在 https 那一侧的原文也认`() {
        val https = realCode.replace("http://", "https://").replace(":8081", ":8181")
        assertEquals(ScanTarget.IClass(https), IClassQrParser.parse(https))
    }

    /** 端口不参与「是不是这张码」的判定；要不要升级是 [IClassSignUrl] 的事 */
    @Test
    fun `不带端口的形态照样认`() {
        val noPort = realCode.replace(":8081", "")
        assertEquals(ScanTarget.IClass(noPort), IClassQrParser.parse(noPort))
    }

    @Test
    fun `host 大小写不敏感`() {
        val mixed = realCode.replace("iclass.buaa.edu.cn", "IClass.BUAA.edu.cn")
        assertEquals(ScanTarget.IClass(mixed), IClassQrParser.parse(mixed))
    }

    /** 路由判定是 endsWith：前面多一段 context path 仍然命中 */
    @Test
    fun `多一段 context path 仍然命中`() {
        val prefixed = realCode.replace("/app/course", "/iclass/app/course")
        assertEquals(ScanTarget.IClass(prefixed), IClassQrParser.parse(prefixed))
    }

    @Test
    fun `host 后缀与前缀伪装都不认`() {
        for (raw in listOf(
            // 端口位上挂着 evil.com：splitAuthority 判它畸形（端口非纯数字）
            realCode.replace("iclass.buaa.edu.cn:8081", "iclass.buaa.edu.cn:8081.evil.com"),
            realCode.replace("iclass.buaa.edu.cn", "evil-iclass.buaa.edu.cn"),
            realCode.replace("iclass.buaa.edu.cn", "iclass.buaa.edu.cnx"),
            realCode.replace("iclass.buaa.edu.cn", "user:pass@iclass.buaa.edu.cn"),
        )) {
            assertNull("不该认：$raw", IClassQrParser.parse(raw))
        }
    }

    @Test
    fun `别的 host 带同名路由也不认`() {
        assertNull(
            IClassQrParser.parse("http://spoc.buaa.edu.cn:8081/app/course/stu_scan_sign.action?courseSchedId=1"),
        )
        assertNull(
            IClassQrParser.parse("http://iclass.example.com/app/course/stu_scan_sign.action?courseSchedId=1"),
        )
    }

    /** 自动定位签到那一条（人不在课堂也能签）不许从这一族放过去：它不是"扫老师投的码" */
    @Test
    fun `自动签到那条路由不认`() {
        assertNull(
            IClassQrParser.parse("http://iclass.buaa.edu.cn:8081/app/course/stu_auto_sign.action?courseSchedId=2488752"),
        )
    }

    @Test
    fun `路由只在参数值里出现不算命中`() {
        assertNull(
            IClassQrParser.parse(
                "http://iclass.buaa.edu.cn:8081/app/course/redirect?to=/app/course/stu_scan_sign.action&courseSchedId=1",
            ),
        )
    }

    @Test
    fun `缺 courseSchedId 或值不成形都不认`() {
        for (raw in listOf(
            "http://iclass.buaa.edu.cn:8081/app/course/stu_scan_sign.action?timestamp=1",
            "http://iclass.buaa.edu.cn:8081/app/course/stu_scan_sign.action?courseSchedId=",
            "http://iclass.buaa.edu.cn:8081/app/course/stu_scan_sign.action?courseSchedId=a%20b",
        )) {
            assertNull("不该认：$raw", IClassQrParser.parse(raw))
        }
    }

    /** 键名大小写不敏感：取一次 lowercase 的代价是零，收益是服务端哪天改了驼峰位置不至于签不成 */
    @Test
    fun `参数名大小写不敏感`() {
        val renamed = realCode.replace("courseSchedId", "COURSESCHEDid")
        assertEquals(ScanTarget.IClass(renamed), IClassQrParser.parse(renamed))
    }

    /** 提交是 `原文 + "&id="`：原文里已有 `id=` 就成了同名双参数，那是另一种 `参数错误!` */
    @Test
    fun `原文已经带 id 的不认`() {
        assertNull(IClassQrParser.parse("$realCode&id=998877"))
        assertNull(
            IClassQrParser.parse(
                "http://iclass.buaa.edu.cn:8081/app/course/stu_scan_sign.action?id=7&courseSchedId=1",
            ),
        )
    }

    /** 两族同在 `*.buaa.edu.cn` 下：智学北航那张码不许被这一族抢走 */
    @Test
    fun `智学北航的码不认成本族`() {
        assertNull(
            IClassQrParser.parse(
                "https://spoc.buaa.edu.cn/bhspoc/#/pages/table/signIn?qdid=1AA9A5D7F4295A0DE0630211FE0AB83E",
            ),
        )
    }

    @Test
    fun `非链接与空输入一律不认`() {
        for (raw in listOf(null, "", "   ", "1AA9A5D7F4295A0DE0630211FE0AB83E", "ftp://iclass.buaa.edu.cn/x")) {
            assertNull("不该认：$raw", IClassQrParser.parse(raw))
        }
    }

    /** 畸形 URL 一律返回 null：这一族解的是投影照片，越界抛异常会顺着相机回调崩掉整页 */
    @Test
    fun `畸形 URL 返回空而不抛`() {
        for (raw in listOf(
            "http://",
            "http:///app/course/stu_scan_sign.action?courseSchedId=1",
            "http://iclass.buaa.edu.cn",
            "http://iclass.buaa.edu.cn:",
            "://x",
        )) {
            assertNull("不该认：$raw", IClassQrParser.parse(raw))
        }
    }
}
