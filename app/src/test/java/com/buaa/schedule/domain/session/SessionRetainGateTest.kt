package com.buaa.schedule.domain.session

import com.buaa.schedule.domain.url.EduDocument
import com.buaa.schedule.domain.url.eduDocumentVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T378③：`domain/session/SessionRetainGate` 的表驱动单测（纯 JVM，零 Robolectric、零设备、零时钟）。
 *
 * 钉的是**判据本身**：五枚教务档逐枚给结论、旧实例那一枚怎么处置、单飞旗与超时闸归谁收。
 * 「生产接线在不在那里」不在本格 —— 那一半由 `BuaaWebSessionLifecycleGuardTest` 读源码形状钉。
 *
 * 期望值全部**手写**：一枚都不从 `SessionRetainGate` 的输出求。不变式那两格（表里 Accepted 的 URL
 * 逐枚都要被 `domain/url` 判成 OnEduOrigin；Refused 逐枚都要报出它是五档里的哪一档）吃的是
 * **另一枚独立内核**的输出 —— 内核空转（把所有输入都折成一档）时，题眼格与这两格都会当场分家。
 *
 * 装机欠账（本卡零 adb，这些没量到，不许当成已验）：
 * 「渲染进程掉死那一次究竟会不会走到上缴」「被顶掉的会话实际丢多久」「拒收之后用户要不要重走一遍 SSO」
 * —— 本文件钉的是给定这五枚事实时判据说什么，不是那三件事真不真发生。
 */
class SessionRetainGateTest {

    /** 一行的全部输入与手写的期望结论 */
    private data class Row(
        val why: String,
        val candidateUrl: String?,
        val source: RetainSource,
        val hasPrevious: Boolean,
        val previousIsCandidate: Boolean,
        val restoreInFlight: Boolean,
        val admission: String,
        val previousWebView: String,
        val singleFlight: String,
        val refusedTier: EduDocument? = null,
    )

    private fun row(
        table: List<Row>,
        index: Int,
    ): SessionRetainVerdict = with(table[index]) {
        SessionRetainGate.decide(
            candidateUrl = candidateUrl,
            source = source,
            hasPreviousInstance = hasPrevious,
            previousIsCandidate = previousIsCandidate,
            restoreInFlight = restoreInFlight,
        )
    }

    private fun assertRow(
        table: List<Row>,
        index: Int,
    ) {
        val expect = table[index]
        val actual = row(table, index)
        val message = "${expect.why}（候选=${expect.candidateUrl} 来路=${expect.source} " +
            "有旧=${expect.hasPrevious} 旧即候选=${expect.previousIsCandidate} 旗在飞=${expect.restoreInFlight}）"
        assertEquals("$message ⇒ 收下/拒收那一档：", expect.admission, actual.javaClass.simpleName)
        assertEquals("$message ⇒ 旧实例那一档：", expect.previousWebView, actual.previousWebView.name)
        assertEquals("$message ⇒ 旗与超时闸那一档：", expect.singleFlight, actual.singleFlight.name)
        if (actual is SessionRetainVerdict.Refused) {
            assertEquals(
                "${expect.why} ⇒ 拒收档必须报得出它是教务五档里的哪一档（否则日志又只剩一句「没收下」）：",
                expect.refusedTier?.javaClass?.simpleName,
                actual.candidateVerdict.javaClass.simpleName,
            )
        }
    }

    // ─────────────── ① 题眼表：出身逐枚定档 ───────────────

    @Test
    fun `教务五档逐枚给结论 非教务出身一枚都不许收下`() {
        val table = listOf(
            Row(
                why = "正常上缴：byxt 首页，手里没有旧的，旗也不在飞 —— 收下，无旧可拆，旗没人欠",
                candidateUrl = ON_EDU,
                source = RetainSource.LoginScreen,
                hasPrevious = false,
                previousIsCandidate = false,
                restoreInFlight = false,
                admission = "Accepted",
                previousWebView = "NoPreviousInstance",
                singleFlight = "NothingInFlight",
            ),
            Row(
                why = "本卡题眼：崩溃重建后那枚新实例（URL 还是 about:blank）上缴，而手里正有一枚" +
                    "登录着的活会话 —— 改前它换引用、写脏 lastUrl（hasSession 转 false）、还把活的那枚拆掉；" +
                    "今天必须整档拒收，旧的留着、旗与闸都不动",
                candidateUrl = "about:blank",
                source = RetainSource.LoginScreen,
                hasPrevious = true,
                previousIsCandidate = false,
                restoreInFlight = false,
                admission = "Refused",
                previousWebView = "KeepPreviousAsSession",
                singleFlight = "NothingInFlight",
                refusedTier = EduDocument.Unparseable,
            ),
            Row(
                why = "SSO 回跳途中（文档还在 sso 域）上缴，而恢复链那一趟正在飞：拒收，" +
                    "旗与闸都留给恢复链（retain 没收下任何东西，无权替它拆闸）",
                candidateUrl = "https://sso.buaa.edu.cn/login?service=x",
                source = RetainSource.LoginScreen,
                hasPrevious = true,
                previousIsCandidate = false,
                restoreInFlight = true,
                admission = "Refused",
                previousWebView = "KeepPreviousAsSession",
                singleFlight = "LeaveToRestoreChain",
                refusedTier = EduDocument.NotEduHost,
            ),
            Row(
                why = "后缀伪装（byxt.buaa.edu.cn.evil.com）：内核逐段比 host，整段不等 ⇒ 拒收。" +
                    "这一档在册 #515 已钉过「裸前缀那形会放行它」",
                candidateUrl = "https://byxt.buaa.edu.cn.evil.com/jwapp/sys/homeapp/index.do",
                source = RetainSource.LoginScreen,
                hasPrevious = true,
                previousIsCandidate = false,
                restoreInFlight = true,
                admission = "Refused",
                previousWebView = "KeepPreviousAsSession",
                singleFlight = "LeaveToRestoreChain",
                refusedTier = EduDocument.NotEduHost,
            ),
            Row(
                why = "userinfo 伪装（byxt.buaa.edu.cn@evil.com）：同一档，拒收",
                candidateUrl = "https://byxt.buaa.edu.cn@evil.com/jwapp/sys/homeapp/",
                source = RetainSource.LoginScreen,
                hasPrevious = false,
                previousIsCandidate = false,
                restoreInFlight = false,
                admission = "Refused",
                previousWebView = "NoPreviousInstance",
                singleFlight = "NothingInFlight",
                refusedTier = EduDocument.NotEduHost,
            ),
            Row(
                why = "明文降级（http 同 host）：与教务那台不是同一 origin、也不是同一份 Cookie ⇒ 拒收，" +
                    "但日志要说得出是「降级」而不是「外人」",
                candidateUrl = "http://byxt.buaa.edu.cn/jwapp/sys/homeapp/index.do",
                source = RetainSource.LoginScreen,
                hasPrevious = true,
                previousIsCandidate = false,
                restoreInFlight = false,
                admission = "Refused",
                previousWebView = "KeepPreviousAsSession",
                singleFlight = "NothingInFlight",
                refusedTier = EduDocument.SchemeDowngraded,
            ),
            Row(
                why = "同 host 而端口是别的数字（:8443）：同源策略下那是另一台服务器 ⇒ 拒收，单独一档",
                candidateUrl = "https://byxt.buaa.edu.cn:8443/jwapp/sys/homeapp/index.do",
                source = RetainSource.LoginScreen,
                hasPrevious = false,
                previousIsCandidate = false,
                restoreInFlight = false,
                admission = "Refused",
                previousWebView = "NoPreviousInstance",
                singleFlight = "NothingInFlight",
                refusedTier = EduDocument.PortMismatch,
            ),
            Row(
                why = "URL 读不出来（WebView.getUrl() 在起手与销毁之间会给 null）：与 about:blank 同命，" +
                    "拒收 —— 这一枚改前会把 lastUrl 写成 null，等价于自己把会话清了",
                candidateUrl = null,
                source = RetainSource.LoginScreen,
                hasPrevious = true,
                previousIsCandidate = false,
                restoreInFlight = true,
                admission = "Refused",
                previousWebView = "KeepPreviousAsSession",
                singleFlight = "LeaveToRestoreChain",
                refusedTier = EduDocument.Unparseable,
            ),
        )
        for (index in table.indices) assertRow(table, index)
    }

    // ─────────────── ② 合法格不许误伤（内核比的是整段，不是前缀） ───────────────

    @Test
    fun `三枚合法教务写法照旧收下 一枚都不许误伤`() {
        val table = listOf(
            Row(
                why = "无尾斜杠的教务根：改前带斜杠那把尺误伤它（在册 #515 三枚合法格之一），内核认它",
                candidateUrl = "https://byxt.buaa.edu.cn",
                source = RetainSource.LoginScreen,
                hasPrevious = false,
                previousIsCandidate = false,
                restoreInFlight = false,
                admission = "Accepted",
                previousWebView = "NoPreviousInstance",
                singleFlight = "NothingInFlight",
            ),
            Row(
                why = "合法首页带显式缺省端口 :443：同一枚 origin，认它",
                candidateUrl = "https://byxt.buaa.edu.cn:443/jwapp/sys/homeapp/index.do",
                source = RetainSource.LoginScreen,
                hasPrevious = false,
                previousIsCandidate = false,
                restoreInFlight = false,
                admission = "Accepted",
                previousWebView = "NoPreviousInstance",
                singleFlight = "NothingInFlight",
            ),
            Row(
                why = "大小写混排：内核折一次大小写才认（字面量逐字比那一形不认，那是第三问的触发器）",
                candidateUrl = "HTTPS://BYXT.BUAa.EDU.CN/jwapp/sys/homeapp/index.do",
                source = RetainSource.LoginScreen,
                hasPrevious = false,
                previousIsCandidate = false,
                restoreInFlight = false,
                admission = "Accepted",
                previousWebView = "NoPreviousInstance",
                singleFlight = "NothingInFlight",
            ),
            Row(
                why = "query 里一枚裸 ASCII 空格：改前 URI 那把刀在这一枚上抛 ⇒ 真教务文档被念成外人。" +
                    "内核不请 URI，这一格是它凭什么存在的证据之一",
                candidateUrl = "https://byxt.buaa.edu.cn/jwapp/sys/homeapp/index.do?rd=a b",
                source = RetainSource.LoginScreen,
                hasPrevious = false,
                previousIsCandidate = false,
                restoreInFlight = false,
                admission = "Accepted",
                previousWebView = "NoPreviousInstance",
                singleFlight = "NothingInFlight",
            ),
        )
        for (index in table.indices) assertRow(table, index)
    }

    // ─────────────── ③ 旧实例那一枚：四档逐枚各有自己的处置 ───────────────

    @Test
    fun `旧实例四档 拆旧只许先摘树再拆 而自拆与会拆活会话那两档必须挡掉`() {
        val table = listOf(
            Row(
                why = "收下候选、手里另有一枚（崩溃重建／二次登录都要走到这里）：这是全仓唯一一枚" +
                    "改前「挂在树上直接 destroy」的出口，今天必须走 RemoveFromTreeThenDestroy 那一档",
                candidateUrl = ON_EDU,
                source = RetainSource.LoginScreen,
                hasPrevious = true,
                previousIsCandidate = false,
                restoreInFlight = true,
                admission = "Accepted",
                previousWebView = "RemoveFromTreeThenDestroy",
                singleFlight = "CollectFlagKeepGuardArmed",
            ),
            Row(
                why = "上缴的就是会话槽里那一枚本身（同一枚 WebView 被上缴两次，safeBack 与抓取链就会各来一次）：" +
                    "这一档改前靠 `old !== webView` 那枚裸判断挡着，没有名字因而被念成「替换旧会话」；" +
                    "今天它是具名档，调用点一手都不许动",
                candidateUrl = ON_EDU,
                source = RetainSource.LoginScreen,
                hasPrevious = true,
                previousIsCandidate = true,
                restoreInFlight = true,
                admission = "Accepted",
                previousWebView = "CandidateIsThePrevious",
                singleFlight = "CollectFlagKeepGuardArmed",
            ),
            Row(
                why = "手里没有旧的（冷启动第一次上缴）：无东西可拆",
                candidateUrl = ON_EDU,
                source = RetainSource.CookieRestore,
                hasPrevious = false,
                previousIsCandidate = false,
                restoreInFlight = true,
                admission = "Accepted",
                previousWebView = "NoPreviousInstance",
                singleFlight = "CollectFlagAndCancelGuard",
            ),
            Row(
                why = "拒收档里即使手里有旧的也不许拆：那一枚才是仍然登录着的会话（四档的第四枚）",
                candidateUrl = "https://sso.buaa.edu.cn/login",
                source = RetainSource.CookieRestore,
                hasPrevious = true,
                previousIsCandidate = false,
                restoreInFlight = true,
                admission = "Refused",
                previousWebView = "KeepPreviousAsSession",
                singleFlight = "LeaveToRestoreChain",
                refusedTier = EduDocument.NotEduHost,
            ),
        )
        for (index in table.indices) assertRow(table, index)
        val reached = table.indices.map { row(table, it).previousWebView }
        assertEquals(
            "四档旧实例结论逐枚都要有至少一行打到（实到 ${reached.distinct().size} 枚：" +
                "${reached.distinct()}）—— 少一枚就有那一档只能靠删它来保持绿色",
            4,
            reached.distinct().size,
        )
    }

    // ─────────────── ④ 单飞旗与超时闸：四档逐枚 ───────────────

    @Test
    fun `旗与闸的四档 拆闸那一手只许与收旗同档`() {
        val table = listOf(
            Row(
                why = "恢复链自己跑成了（retain 由 restore 的 onPageFinished 调）：旗与闸都是它自己的东西，" +
                    "两样一起收 —— 改前这一档由调用点自己先收旗，本卡把那一手收进内核给的档位里",
                candidateUrl = ON_EDU,
                source = RetainSource.CookieRestore,
                hasPrevious = false,
                previousIsCandidate = false,
                restoreInFlight = true,
                admission = "Accepted",
                previousWebView = "NoPreviousInstance",
                singleFlight = "CollectFlagAndCancelGuard",
            ),
            Row(
                why = "登录页占下了会话槽，而恢复链那一趟还挂着：旗**要**收（否则 restore 恒早退、" +
                    "本进程再也不尝试恢复），闸**不许**拆（那一枚没人认领的 WebView 只剩这道闸会替它拆）",
                candidateUrl = ON_EDU,
                source = RetainSource.LoginScreen,
                hasPrevious = false,
                previousIsCandidate = false,
                restoreInFlight = true,
                admission = "Accepted",
                previousWebView = "NoPreviousInstance",
                singleFlight = "CollectFlagKeepGuardArmed",
            ),
            Row(
                why = "旗本来就不在飞：收旗与拆闸都是空转，一手都不许做（改前 retain 无条件 " +
                    "cancelRestoreGuard() 就是拿空转当routine）",
                candidateUrl = ON_EDU,
                source = RetainSource.LoginScreen,
                hasPrevious = false,
                previousIsCandidate = false,
                restoreInFlight = false,
                admission = "Accepted",
                previousWebView = "NoPreviousInstance",
                singleFlight = "NothingInFlight",
            ),
            Row(
                why = "本卡修的第三条病：改前 retain 收下任何一枚实例都无条件拆闸，而函数体内 " +
                    "restoreInFlight 零处 —— 闸没了、旗还留着 true，此后 restore() 恒在 " +
                    "if (restoreInFlight) return 那一档静默早退。拒收档今天两手都不碰",
                candidateUrl = "about:blank",
                source = RetainSource.LoginScreen,
                hasPrevious = true,
                previousIsCandidate = false,
                restoreInFlight = true,
                admission = "Refused",
                previousWebView = "KeepPreviousAsSession",
                singleFlight = "LeaveToRestoreChain",
                refusedTier = EduDocument.Unparseable,
            ),
        )
        for (index in table.indices) assertRow(table, index)
        val flags = table.indices.map { row(table, it).singleFlight.name }
        assertEquals("四档旗与闸逐枚都要有至少一行打到（实到 ${flags.distinct().size} 枚）：", 4, flags.distinct().size)
    }

    // ─────────────── ⑤ 反套套逻辑：内核不许把两件事折成一档 ───────────────

    @Test
    fun `同一批输入里四档结论互不相混 题眼那两枚与改前的形状分家`() {
        val neverFailedButInFlight = SessionRetainGate.decide(
            candidateUrl = "about:blank",
            source = RetainSource.LoginScreen,
            hasPreviousInstance = true,
            previousIsCandidate = false,
            restoreInFlight = false,
        )
        val failedAndInFlight = SessionRetainGate.decide(
            candidateUrl = "about:blank",
            source = RetainSource.LoginScreen,
            hasPreviousInstance = true,
            previousIsCandidate = false,
            restoreInFlight = true,
        )
        assertNotEquals(
            "「旗不在飞」与「旗在飞」在两枚都拒收的输入上必须给出**不同**的旗档（" +
                "${neverFailedButInFlight.singleFlight} vs ${failedAndInFlight.singleFlight}）：" +
                "并成一档就等于把「无从收」与「不许收」念成同一句话 —— 后者正是 retain 越权拆闸那一格",
            neverFailedButInFlight.singleFlight,
            failedAndInFlight.singleFlight,
        )
        val adoptedFresh = SessionRetainGate.decide(
            candidateUrl = ON_EDU,
            source = RetainSource.LoginScreen,
            hasPreviousInstance = false,
            previousIsCandidate = false,
            restoreInFlight = false,
        )
        val adoptedReplacing = SessionRetainGate.decide(
            candidateUrl = ON_EDU,
            source = RetainSource.LoginScreen,
            hasPreviousInstance = true,
            previousIsCandidate = false,
            restoreInFlight = false,
        )
        assertNotEquals(
            "「无旧可拆」与「有一枚要摘树再拆」必须分档（$adoptedFresh vs $adoptedReplacing）：" +
                "并档就是改前那种「无论手里有没有别的实例都照拆」的写法",
            adoptedFresh.previousWebView,
            adoptedReplacing.previousWebView,
        )
        val selfAdopted = SessionRetainGate.decide(
            candidateUrl = ON_EDU,
            source = RetainSource.LoginScreen,
            hasPreviousInstance = true,
            previousIsCandidate = true,
            restoreInFlight = false,
        )
        assertNotEquals(
            "「上缴的就是手里那一枚」不许与「另有一枚要拆」同档（$selfAdopted vs $adoptedReplacing）：" +
                "同档就是让调用点自己拆掉刚收下的会话",
            selfAdopted.previousWebView,
            adoptedReplacing.previousWebView,
        )
        // 三枚拒收原因逐枚不同档：合并成一枚「不是教务」就是把五种病念成一句话
        val refusals = listOf(
            "about:blank",
            "http://byxt.buaa.edu.cn/",
            "https://byxt.buaa.edu.cn:8443/",
            "https://sso.buaa.edu.cn/login",
        ).map {
            (
                SessionRetainGate.decide(
                    candidateUrl = it,
                    source = RetainSource.LoginScreen,
                    hasPreviousInstance = false,
                    previousIsCandidate = false,
                    restoreInFlight = false,
                ) as SessionRetainVerdict.Refused
                ).candidateVerdict
        }
        assertEquals("四枚非教务候选必须报出四种不同的档（实到 ${refusals.distinct().size}）：", 4, refusals.distinct().size)
    }

    // ─────────────── ⑥ 不变式：结论与出身判据同源（吃另一枚内核） ───────────────

    @Test
    fun `不变式 收下档逐枚都是教务出身 拒收档逐枚都不是`() {
        val urls = listOf(
            ON_EDU,
            "https://byxt.buaa.edu.cn",
            "https://byxt.buaa.edu.cn:443/jwapp/sys/homeapp/index.do",
            "HTTPS://BYXT.BUAa.EDU.CN/jwapp/sys/homeapp/index.do",
            "about:blank",
            null,
            "https://sso.buaa.edu.cn/login",
            "https://byxt.buaa.edu.cn.evil.com/jwapp/sys/homeapp/index.do",
            "https://byxt.buaa.edu.cn@evil.com/jwapp/sys/homeapp/",
            "http://byxt.buaa.edu.cn/jwapp/sys/homeapp/index.do",
            "https://byxt.buaa.edu.cn:8443/jwapp/sys/homeapp/index.do",
            "not a url at all",
        )
        val accepted = mutableListOf<String>()
        val refused = mutableListOf<String>()
        for (url in urls) {
            when (
                val verdict = SessionRetainGate.decide(
                    candidateUrl = url,
                    source = RetainSource.LoginScreen,
                    hasPreviousInstance = false,
                    previousIsCandidate = false,
                    restoreInFlight = false,
                )
            ) {
                is SessionRetainVerdict.Accepted -> accepted += url ?: "<null>"
                is SessionRetainVerdict.Refused -> {
                    refused += url ?: "<null>"
                    // 每一枚拒收都要说得出它是五档里的哪一档，且那一档必须与内核现量一致
                    assertEquals(
                        "「$url」被拒收，但档位要与 domain/url 那枚唯一真源现量对上：",
                        eduDocumentVerdict(url).javaClass.simpleName,
                        verdict.candidateVerdict.javaClass.simpleName,
                    )
                }
            }
        }
        assertEquals(
            "这批 URL 里被收下的必须恰好 4 枚（教务那台合法写法：带路由、无尾斜杠、显式 :443、大小写混排），" +
                "实到 ${accepted.size} 枚：$accepted",
            4,
            accepted.size,
        )
        assertEquals(
            "被拒收的必须恰好 8 枚（含 about:blank、null、外人伪装、明文、错端口、读不出），" +
                "实到 ${refused.size} 枚：$refused",
            8,
            refused.size,
        )
        assertTrue("表里必须同时有两半的靶子（全绿或全拒都是空转）：", accepted.isNotEmpty() && refused.isNotEmpty())
    }

    private companion object {
        /** 教务首页（SSO 回跳后的真身）：内核给它 OnEduOrigin */
        const val ON_EDU = "https://byxt.buaa.edu.cn/jwapp/sys/homeapp/index.do?ticket=ST-1234"
    }
}
