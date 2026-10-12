package com.buaa.schedule.domain.url

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T302②：[eduDocumentVerdict] 与 [sessionDomainVerdict] 的表驱动单测（在册 #515）。
 *
 * 纯 JVM：不碰 WebView、不碰 Robolectric、不读钟、不读时区、不起协程 —— 内核本体零 import，
 * 这一格才测得动（台账里「返回布尔后调用点各自再判一次 SDK_INT」那一形同样在这里被档住：
 * 表里没有一枚档位需要问设备）。
 *
 * ## 表的来历
 *
 * 每格都来自 T302⓪ 那趟纯 JVM scratch 的实测（JDK 21，四枚现判据 `:354`/`:605`/`:120`/`:511` 的逐格返回值
 * 记在那枚提交里），不是照着内核的实现"顺推"出来的。⚠️ 期望值全部是手写的档位名，
 * 没有一枚来自被验对象的运行时输出（本仓反套套逻辑那条硬规矩）。
 *
 * 两处与 `java.net.URI` 直接对表的格子（userinfo 那格的 host、裸空格那格的抛）把 URI 自己的答案
 * 也钉进断言 —— 钉的是「两把刀在同一格上给出不同答案」这件事，而不是「内核跟着 URI 的答案走」。
 */
class EduWebOriginTest {

    /** 每一格同时过两枚问题：教务 origin（就绪）与会话域（含 SSO 子域）—— 两问两档，不许一枚 Boolean */
    private data class Cell(
        val url: String?,
        val edu: EduDocument,
        val session: SessionDomain,
        val why: String,
    )

    @Test
    fun `两枚判据的表驱动 二十格逐格对表`() {
        for (cell in CELLS) {
            val actualEdu = eduDocumentVerdict(cell.url)
            assertSame(
                "教务 origin 那一问：「${cell.url}」—— ${cell.why}",
                cell.edu,
                actualEdu,
            )
            val actualSession = sessionDomainVerdict(cell.url)
            assertSame(
                "会话域那一问：「${cell.url}」—— ${cell.why}",
                cell.session,
                actualSession,
            )
        }
    }

    /**
     * 两问**必须**给出不同的档：`sso.buaa.edu.cn` 是「允许留在会话里」却「不是教务 origin」。
     *
     * 这一格是"两枚函数不许折成一枚"的全部理由：折成一枚 Boolean 之后，停在 SSO 登录页的会话
     * 要么被当成外站拆掉（用户白登一回），要么 SSO 被当成教务就绪（页面内 fetch 打到错的服务器上）。
     */
    @Test
    fun `sso 那一格证明两问是两个答案`() {
        val sso = "https://sso.buaa.edu.cn/login?service=https%3A%2F%2Fbyxt.buaa.edu.cn%2Fjwapp%2Findex.do"
        assertSame(
            "停在 SSO 登录页：会话 WebView 必须**留在**北航域里（拆掉它，刚换到的会话 Cookie 就白留了）",
            SessionDomain.AllowedBuaaDomain,
            sessionDomainVerdict(sso),
        )
        assertSame(
            "但同一枚 URL **不是**教务 origin（就绪判据认了它，页面内 fetch 就发到 sso 那台去了）",
            EduDocument.NotEduHost,
            eduDocumentVerdict(sso),
        )
        // 反向那一半：教务 origin 一定同时是北航域（两枚档位的包含关系，写死在一格上）
        val edu = "https://byxt.buaa.edu.cn/jwapp/sys/homeapp/index.do"
        assertSame(EduDocument.OnEduOrigin, eduDocumentVerdict(edu))
        assertSame(SessionDomain.AllowedBuaaDomain, sessionDomainVerdict(edu))
    }

    /**
     * 后缀伪装那四格：`startsWith` 版（盘面上的 `:120`）全部命中，内核全部不认。
     *
     * 这是 #515 的病体本身，所以每一格单独钉一遍，并把「裸前缀会说谎」当成**前提**钉进断言
     * （前提不成立 = 换靶了，那一格先红）—— 摘掉内核、换回 `startsWith(BYXT_ORIGIN)` 的那一刻，
     * 这一格与第一格同时红，这就是 W 臂要的证据。
     */
    @Test
    fun `后缀伪装四格 裸前缀全部命中而内核全部不认`() {
        val origin = EduWebOrigin.ORIGIN
        for (spoof in SUFFIX_SPOOFS) {
            assertTrue(
                "前提：盘面那枚裸前缀判据确实放过它（不放就说明本格的靶换了）：$spoof",
                spoof.startsWith(origin),
            )
            assertSame(
                "后缀/userinfo 伪装必须落在「不是教务」那一侧：$spoof",
                EduDocument.NotEduHost,
                eduDocumentVerdict(spoof),
            )
            assertSame(
                "而且它连「北航域」都不许算（会话 WebView 留在外站上等于把教务 Cookie 带出国门）：$spoof",
                SessionDomain.ForeignDomain,
                sessionDomainVerdict(spoof),
            )
        }
    }

    /**
     * `https://byxt.buaa.edu.cn@evil.com/` 里 `URI.host` 到底等于什么 —— ⓪ 实测＝`evil.com`
     * （userinfo 是 `byxt.buaa.edu.cn`），不是直觉里的「读不出」也不是「byxt.buaa.edu.cn」。
     *
     * 两把刀在这一格都判"不是教务"，但**理由不同**：URI 说「host 是 evil.com」，
     * 手写刀说「整段 authority 不等于教务那一台」。把它记成断言，是为了让"换回 URI"那一步
     * 也必须在测试眼前承认自己看到了什么。
     */
    @Test
    fun `userinfo 伪装 URI 与手写刀各看清了什么`() {
        val spoof = "https://byxt.buaa.edu.cn@evil.com/"
        assertEquals("URI 实测剥掉 userinfo 之后交的 host：", "evil.com", URI(spoof).host)
        assertEquals("URI 实测读出的 userInfo：", "byxt.buaa.edu.cn", URI(spoof).userInfo)
        assertSame(EduDocument.NotEduHost, eduDocumentVerdict(spoof))
        assertSame(SessionDomain.ForeignDomain, sessionDomainVerdict(spoof))
        // 手写刀看得更粗：authority 整段留着 `@`，于是 host 整段不等于教务 ⇒ 同一档，且它不会因为畸形而抛
        assertEquals("byxt.buaa.edu.cn@evil.com", urlAuthority(spoof))
        assertEquals("byxt.buaa.edu.cn@evil.com" to "", splitAuthority(urlAuthority(spoof)!!))
    }

    /**
     * ⓪ 真值表里 `:511` 说谎那一格：query 里一枚裸 ASCII 空格让 `java.net.URI` **抛**，
     * 于是「真教务文档」被 URI 版判成"读不出 host"（盘面上的句子就是「重定向到非北航域」，当场拆会话）。
     *
     * 内核走手写刀 ⇒ 它不吃 query 段，照常认教务 origin。这一格钉的是「不许为了省事换回 URI」。
     */
    @Test
    fun `裸空格 query URI 抛而内核照常认`() {
        val spaced = "https://byxt.buaa.edu.cn/jwapp/sys/xnfd.do?a= b"
        val threw = runCatching { URI(spaced).host }.exceptionOrNull()
        assertNotNull("前提：这枚串必须真的让 java.net.URI 抛（不抛就换靶了）", threw)
        assertTrue(
            "抛的是 URISyntaxException（⓪ 实测 JDK 21），实到 ${threw?.javaClass?.simpleName}",
            threw is java.net.URISyntaxException,
        )
        assertSame(EduDocument.OnEduOrigin, eduDocumentVerdict(spaced))
        assertSame(SessionDomain.AllowedBuaaDomain, sessionDomainVerdict(spaced))
        // 裸非 ASCII（教务 URL 的真实形状：?tabName=我的课表）URI 收、手写刀也收，两把同档
        val unicode = "https://byxt.buaa.edu.cn/jwapp/sys/homeapp/index.do?tabName=我的课表"
        assertSame(EduDocument.OnEduOrigin, eduDocumentVerdict(unicode))
        assertEquals("byxt.buaa.edu.cn", URI(unicode).host)
    }

    /**
     * 被盘面那枚「带尾斜杠」判据误伤的三格合法 URL（`:443`／大小写混排／无尾斜杠）：内核必须认。
     *
     * 旧判据在这一组上的返回值同时钉成 `[false, false, false]` —— 钉的是**事实**（改前盘面就是这三枚
     * 合法格说不就绪），不是愿望。把内核改成"端口也认不得/大小写也认不得"的那一步（N 臂）红在这一格。
     */
    @Test
    fun `合法格三枚 带尾斜杠的旧判据会误伤而内核必须认`() {
        for (legit in LEGIT_CELLS) {
            assertSame(
                "这一格必须是「就是教务 origin」（旧那枚带尾斜杠的 startsWith 判它不就绪）：${legit.url}",
                EduDocument.OnEduOrigin,
                eduDocumentVerdict(legit.url),
            )
            assertSame(
                "而会话域当然允许留在北航域里：${legit.url}",
                SessionDomain.AllowedBuaaDomain,
                sessionDomainVerdict(legit.url),
            )
        }
        assertEquals(
            "对照：盘面上那枚带尾斜杠的前缀判据对这组合法格逐枚返回 false（实到 " +
                LEGIT_CELLS.map { it.url!!.startsWith("$BYXT_ORIGIN_LEGACY/") } + "）",
            listOf(false, false, false),
            LEGIT_CELLS.map { it.url!!.startsWith("$BYXT_ORIGIN_LEGACY/") },
        )
    }

    /** 内核的 origin 常量本身：字面量只许这一处，别的文件都从它派生（③ 的形状守卫引这一格） */
    @Test
    fun `origin 常量的形状`() {
        assertEquals("https://byxt.buaa.edu.cn", EduWebOrigin.ORIGIN)
        assertEquals("byxt.buaa.edu.cn", EduWebOrigin.HOST)
        assertEquals("https", EduWebOrigin.SCHEME)
        assertEquals("443", EduWebOrigin.TLS_PORT)
        assertEquals("buaa.edu.cn", EduWebOrigin.BUAA_DOMAIN)
        assertSame(EduDocument.OnEduOrigin, eduDocumentVerdict(EduWebOrigin.ORIGIN + "/"))
        assertSame(EduDocument.OnEduOrigin, eduDocumentVerdict(EduWebOrigin.ORIGIN))
    }

    // ---- 表 ----

    private companion object {

        /** 盘面上 `:354`/`:605` 那枚带尾斜杠的前缀（本卡只当**事实**用：证明旧判据误伤了哪些合法格） */
        const val BYXT_ORIGIN_LEGACY = "https://byxt.buaa.edu.cn"

        val CELLS = listOf(
            Cell(
                "https://byxt.buaa.edu.cn/",
                EduDocument.OnEduOrigin,
                SessionDomain.AllowedBuaaDomain,
                "教务首页根路径：就绪判据的正身（盘面四枚全认它）",
            ),
            Cell(
                "https://byxt.buaa.edu.cn/jwapp/sys/homeapp/index.do?ticket=ST-1234",
                EduDocument.OnEduOrigin,
                SessionDomain.AllowedBuaaDomain,
                "SSO 回跳后的教务首页（带查询串那枚合法格，N 臂的靶之一）",
            ),
            Cell(
                "http://byxt.buaa.edu.cn/",
                EduDocument.SchemeDowngraded,
                SessionDomain.AllowedBuaaDomain,
                "scheme 降级：host 对而明文 —— 浏览器眼里那是另一枚 origin、另一份 Cookie；" +
                    "会话域那一问照旧允许（两问分档的形状）",
            ),
            Cell(
                "https://byxt.buaa.edu.cn.evil.com/",
                EduDocument.NotEduHost,
                SessionDomain.ForeignDomain,
                "后缀伪装（第 24 枚字符实测是 `.`）：带斜杠的旧判据不命中，裸前缀的 :120 命中",
            ),
            Cell(
                "https://byxt.buaa.edu.cn@evil.com/",
                EduDocument.NotEduHost,
                SessionDomain.ForeignDomain,
                "userinfo 伪装（第 24 枚字符实测是 `@`，URI.host 实测 = evil.com）：同上",
            ),
            Cell(
                "https://byxt.buaa.edu.cnevil.com/",
                EduDocument.NotEduHost,
                SessionDomain.ForeignDomain,
                "粘连后缀：没有点分隔符也要被整段比判掉（endsWith 与 startsWith 在这一格都还认得出来）",
            ),
            Cell(
                "https://sso.buaa.edu.cn/login?service=x",
                EduDocument.NotEduHost,
                SessionDomain.AllowedBuaaDomain,
                "统一身份认证：会话可以留在里面，页面内抓取不能认它就绪（两问两档的题眼）",
            ),
            Cell(
                "https://evilbuaa.edu.cn/",
                EduDocument.NotEduHost,
                SessionDomain.ForeignDomain,
                "`buaa.edu.cn` 贴在别的词后面：`evilbuaa.edu.cn` 里那枚 `buaa` 前面是 `l` 而不是点 ⇒ " +
                    "整段比与「点＋注册域」都比不掉它，只能靠 `.buaa.edu.cn` 那枚点挡住",
            ),
            Cell(
                "HTTPS://BYXT.BUAa.EDU.CN/",
                EduDocument.OnEduOrigin,
                SessionDomain.AllowedBuaaDomain,
                "大小写混排：scheme 与 host 各折一次（URI 不折 ⇒ :511 今天在这一格说谎；" +
                    "带斜杠的 :354/:605 也判不就绪）",
            ),
            Cell(
                "https://byxt.buaa.edu.cn:443/",
                EduDocument.OnEduOrigin,
                SessionDomain.AllowedBuaaDomain,
                "显式写出缺省端口 = 同一枚 origin：带尾斜杠的 :354/:605 今天判它不就绪（N 臂的靶之一）",
            ),
            Cell(
                "about:blank",
                EduDocument.Unparseable,
                SessionDomain.Unparseable,
                "WebView 起手那一枚：没有 `://` ⇒ 读不出 authority（:120 今天正好不认它，方向对）",
            ),
            Cell(
                null,
                EduDocument.Unparseable,
                SessionDomain.Unparseable,
                "getUrl() 交回 null：判据不许抛，也不许把 null 念成「教务没了」那一档",
            ),
            Cell(
                "https://byxt.buaa.edu.cn/jwapp/sys/xnfd.do?a= b",
                EduDocument.OnEduOrigin,
                SessionDomain.AllowedBuaaDomain,
                "query 里一枚裸空格 ⇒ URI 抛，:511 判「非北航域」当场拆会话；手写刀不吃 query 段",
            ),
            Cell(
                "{bad|brace}://byxt.buaa.edu.cn/",
                EduDocument.Unparseable,
                SessionDomain.Unparseable,
                "URI 抛的垃圾串，而手写刀能把 authority 切成教务 host ⇒ 必须先 gate scheme 才不漏",
            ),
            Cell(
                "https://byxt.buaa.edu.cn:8443/",
                EduDocument.PortMismatch,
                SessionDomain.AllowedBuaaDomain,
                "host 对、端口是别的数字：另一台服务器，页面内 fetch 打不到教务接口；会话域那一问照旧允许",
            ),
            Cell(
                "https://byxt.buaa.edu.cn:443.evil.com/",
                EduDocument.Unparseable,
                SessionDomain.Unparseable,
                "端口位挂着 `.evil.com`（URI.getHost() 实测交回 null，手写刀交回「端口非纯数字」）⇒ 都 fail-closed",
            ),
            Cell(
                "https://buaa.edu.cn/",
                EduDocument.NotEduHost,
                SessionDomain.AllowedBuaaDomain,
                "注册域本尊：不是教务那一台，但留在会话里没有出国门",
            ),
            Cell(
                "https:///byxt.buaa.edu.cn/",
                EduDocument.Unparseable,
                SessionDomain.Unparseable,
                "空 authority（`https:///`）：读不出服务器 ⇒ 两问都判不可用",
            ),
            Cell(
                "https://byxt.buaa.edu.cn",
                EduDocument.OnEduOrigin,
                SessionDomain.AllowedBuaaDomain,
                "无尾斜杠的裸 origin：盘面 :354/:605 判不就绪（第三枚被误伤的合法格）",
            ),
            Cell(
                "https://byxt.buaa.edu.cn.evil.com/jwapp/sys/homeapp/index.do",
                EduDocument.NotEduHost,
                SessionDomain.ForeignDomain,
                "后缀伪装 ＋ 真教务路径：拿路径当防线的那一枚（`maybeCaptureSession`）今天恰好挡住，" +
                    "但那是运气形状，本卡两问都不给它机会",
            ),
        )

        /** 盘面 `:120` 会说谎的格子（裸前缀命中而真身不是教务） */
        val SUFFIX_SPOOFS = listOf(
            "https://byxt.buaa.edu.cn.evil.com/",
            "https://byxt.buaa.edu.cn@evil.com/",
            "https://byxt.buaa.edu.cnevil.com/",
            "https://byxt.buaa.edu.cn.evil.com/jwapp/sys/homeapp/index.do",
        )

        /** 被「带尾斜杠」误伤的三枚合法格 */
        val LEGIT_CELLS = listOf(
            Cell(
                "https://byxt.buaa.edu.cn:443/jwapp/sys/homeapp/index.do",
                EduDocument.OnEduOrigin,
                SessionDomain.AllowedBuaaDomain,
                "端口 ＋ 路由",
            ),
            Cell(
                "HTTPS://BYXT.BUAa.EDU.CN/jwapp/sys/homeapp/index.do",
                EduDocument.OnEduOrigin,
                SessionDomain.AllowedBuaaDomain,
                "大小写 ＋ 路由",
            ),
            Cell(
                "https://byxt.buaa.edu.cn",
                EduDocument.OnEduOrigin,
                SessionDomain.AllowedBuaaDomain,
                "裸 origin，没有尾斜杠",
            ),
        )
    }
}
