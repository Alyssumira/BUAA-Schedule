package com.buaa.schedule.data.import

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 扫码结果的归一化。
 *
 * 钉住三件事：
 * 1. **hash 路由的入参在 `#` 之后** —— `/bhspoc/#/pages/table/signIn?qdid=..`，
 *    只看 URI 的 query 会一个参数都取不到（`java.net.URI.getQuery()` 对 fragment
 *    之前的 `?` 才会给值），这是本解析器不复用标准库解析、自己切两截的原因；
 * 2. `zjdm`+`czid` 与 `qdid` 同时出现时走前者 —— H5 的 step=1 形态能少一次详情查询；
 * 3. **不是北航域的外链一律不认** —— 否则任何带 `zjdm` 参数的第三方链接都会被
 *    当成签到码提交出去。
 */
class SpocQrParserTest {

    @Test
    fun `hash 路由里的 qdid 能取到`() {
        assertEquals(
            SpocSignTarget.ByQdid("1AA9A5D7F4295A0DE0630211FE0AB83E"),
            SpocQrParser.parse(
                "https://spoc.buaa.edu.cn/bhspoc/#/pages/table/signIn?qdid=1AA9A5D7F4295A0DE0630211FE0AB83E",
            ),
        )
    }

    @Test
    fun `活动列表形态给出 zjdm 与 czid`() {
        assertEquals(
            SpocSignTarget.ByCourse("ZJ001", "CZ002"),
            SpocQrParser.parse("https://spoc.buaa.edu.cn/bhspoc/#/pages/table/signIn?zjdm=ZJ001&czid=CZ002&step=1"),
        )
    }

    @Test
    fun `两种形态同时存在时优先 zjdm 加 czid`() {
        assertEquals(
            SpocSignTarget.ByCourse("ZJ001", "CZ002"),
            SpocQrParser.parse(
                "https://spoc.buaa.edu.cn/bhspoc/#/pages/table/signIn?zjdm=ZJ001&czid=CZ002&qdid=1AA9A5D7F4295A0DE0630211FE0AB83E",
            ),
        )
    }

    @Test
    fun `参数落在主 query 上也认`() {
        assertEquals(
            SpocSignTarget.ByQdid("AB12CD34"),
            SpocQrParser.parse("https://spoc.buaa.edu.cn/bhspoc/?qdid=AB12CD34#/pages/table/signIn"),
        )
    }

    @Test
    fun `转义过的参数值会还原`() {
        assertEquals(
            SpocSignTarget.ByCourse("a b", "c&d"),
            SpocQrParser.parse("https://spoc.buaa.edu.cn/bhspoc/#/pages/table/signIn?zjdm=a%20b&czid=c%26d"),
        )
    }

    @Test
    fun `裸 ID 当作 qdid`() {
        assertEquals(
            SpocSignTarget.ByQdid("1AA9A5D7F4295A0DE0630211FE0AB83E"),
            SpocQrParser.parse("1AA9A5D7F4295A0DE0630211FE0AB83E"),
        )
    }

    @Test
    fun `只有路由片段也认`() {
        assertEquals(
            SpocSignTarget.ByQdid("XYZ12345"),
            SpocQrParser.parse("/pages/table/signIn?qdid=XYZ12345"),
        )
    }

    @Test
    fun `北航域但非签到活动返回空`() {
        assertNull(
            SpocQrParser.parse("https://spoc.buaa.edu.cn/bhspoc/#/pages/table/wenda_s?hdid=ABCDEF12"),
        )
    }

    @Test
    fun `第三方链接带同名参数也不认`() {
        assertNull(SpocQrParser.parse("https://example.com/signIn?qdid=ABCDEF12"))
        assertNull(SpocQrParser.parse("https://example.com/?zjdm=A&czid=B"))
    }

    @Test
    fun `无关二维码与短串返回空`() {
        assertNull(SpocQrParser.parse("https://weixin.qq.com/r/abc"))
        assertNull(SpocQrParser.parse("智慧教室 3 号楼"))
        assertNull(SpocQrParser.parse("abc"))
        assertNull(SpocQrParser.parse(""))
        assertNull(SpocQrParser.parse(null))
    }

    @Test
    fun `首尾空白不影响判定`() {
        assertEquals(
            SpocSignTarget.ByQdid("1AA9A5D7F4295A0DE0630211FE0AB83E"),
            SpocQrParser.parse("  1AA9A5D7F4295A0DE0630211FE0AB83E\n "),
        )
    }
}
