package com.buaa.schedule.data.import

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面内抓取脚本的协议契约（R5 §8-4）。
 *
 * `WebViewEvaluateJavascriptContractTest` 只证明「evaluateJavascript 能同步取回字符串」，
 * 完全不引用生产脚本 —— 协议退化（相对地址、槽位名、错误哨兵）它不会红。
 * 真跑 [BuaaInPageFetcher.fetch] 需要已登录的 byxt 会话，CI 造不出来，
 * 因此这里至少把脚本本身钉住。
 */
class BuaaInPageFetchScriptTest {

    private fun script(method: String, path: String, body: String = ""): String =
        BuaaInPageFetcher.startScript(method, path, body, "req-1")

    @Test
    fun requestUrlIsPinnedToTheByxtOrigin() {
        val text = script("GET", "/jwapp/sys/homeapp/api/home/kb/xnxq.do")
        assertTrue(
            "注入脚本不得依赖当前文档 origin（R5 F-39）",
            text.contains("fetch('https://byxt.buaa.edu.cn/jwapp/sys/homeapp/api/home/kb/xnxq.do'"),
        )
        assertFalse("不允许出现相对地址的 fetch", text.contains("fetch('/"))
    }

    @Test
    fun wrongDocumentFailsFastInsteadOfHittingAnotherHost() {
        val text = script("GET", "/x.do")
        assertTrue(text.contains("location.origin !== 'https://byxt.buaa.edu.cn'"))
        // 停在登录页时必须把错误哨兵写进槽位，原生侧才会按「这次抓取失败」处理
        assertTrue(text.contains("BUA_FETCH_ERROR"))
    }

    @Test
    fun resultStoreAndMethodFollowTheNativePollingProtocol() {
        val get = script("GET", "/x.do")
        assertTrue(get.contains("window.__buaaFetch"))
        assertTrue(get.contains("var id = 'req-1'"))
        assertTrue(get.contains("method: 'GET'"))
        assertFalse("GET 不该带 body 声明", get.contains("init.body"))
        assertTrue(script("POST", "/x.do", "week=3").contains("method: 'POST'"))
        assertTrue(script("POST", "/x.do", "week=3").contains("init.body = 'week=3';"))
    }

    @Test
    fun interpolatedValuesCannotEscapeTheStringLiteral() {
        // 查询串来自服务端返回的 termCode / 用户可改的日期，畸形取值不能改写脚本语义
        val text = script("GET", "/x.do?termCode=a';alert(1);//")
        assertTrue("进入脚本字面量的引号必须被转义", text.contains("termCode=a\\'"))
        assertFalse(text.contains("termCode=a'"))
    }
}
