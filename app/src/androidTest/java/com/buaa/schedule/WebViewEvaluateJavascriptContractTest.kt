package com.buaa.schedule

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 钉住 `WebView.evaluateJavascript` 的返回值契约。
 *
 * 为什么需要这个测试：`BuaaInPageFetcher` 依赖“evaluateJavascript 能拿到 fetch 的
 * 结果文本”这一假设。但 evaluateJavascript 等价于 DevTools 的 `Runtime.evaluate`，
 * **不带 awaitPromise**：脚本若返回 Promise，回调拿到的是 Promise 对象的 JSON 序列化
 * 结果（`{}`），而不是 resolve 后的值。
 *
 * 这个测试把该契约固定下来，避免有人再写出“Promise 当同步值用”的抓取代码。
 */
@RunWith(AndroidJUnit4::class)
class WebViewEvaluateJavascriptContractTest {

    @Test
    fun promiseResultsAreNotAwaited() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val done = CountDownLatch(1)
        val results = arrayOfNulls<String>(3)
        var webView: WebView? = null

        instrumentation.runOnMainSync {
            val web = WebView(context)
            webView = web
            web.settings.javaScriptEnabled = true
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    // 1) 同步返回值：正常拿到
                    view.evaluateJavascript("(function(){ return 42; })()") { sync ->
                        results[0] = sync
                        // 2) 裸 Promise：不会 await
                        view.evaluateJavascript("Promise.resolve(42)") { promise ->
                            results[1] = promise
                            // 3) async IIFE（BuaaInPageFetcher 当前写法）：同样不会 await
                            view.evaluateJavascript("(async function(){ return 42; })()") { async ->
                                results[2] = async
                                done.countDown()
                            }
                        }
                    }
                }
            }
            web.loadDataWithBaseURL(null, "<html><body>ok</body></html>", "text/html", "utf-8", null)
        }

        assertTrue("WebView 回调超时", done.await(30, TimeUnit.SECONDS))

        assertEquals("同步表达式应正常返回 42", "42", results[0])
        assertEquals("裸 Promise 不会被 await，只能拿到 {} ", "{}", results[1])
        assertEquals("async IIFE 不会被 await，只能拿到 {} ", "{}", results[2])

        instrumentation.runOnMainSync {
            webView?.stopLoading()
            webView?.destroy()
        }
    }
}
