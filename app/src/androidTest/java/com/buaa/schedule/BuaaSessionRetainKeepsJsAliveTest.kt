package com.buaa.schedule

import android.app.Instrumentation
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buaa.schedule.data.import.BuaaWebSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 会话保留的回归测试。
 *
 * 背景（真机实测）：`BuaaWebSession.retain` 旧实现只是把 WebView 从视图树
 * `removeView` 掉，结果"刷新课表（复用登录会话）"恒定 25s 超时
 * （"刷新失败：无法获取学期列表"）——脱离窗口的 WebView，页面内的异步 JS
 * 不再可靠执行，`evaluateJavascript` 轮询永远读不到结果。
 *
 * 本测试钉住修复后的三条契约：
 * 1. retain 之后 WebView **仍在窗口内**，页面里的异步任务（setTimeout → 写全局探针，
 *    等价于 fetch 完成后写结果槽位）仍会执行；
 * 2. 隐藏宿主用 applicationContext 创建，单例不会握着一个已 finish 的 Activity；
 * 3. Activity 重建后再 retain，宿主会搬到新窗口而不是留在已销毁的视图树里。
 */
@RunWith(AndroidJUnit4::class)
class BuaaSessionRetainKeepsJsAliveTest {

    @Test
    fun retainedSessionWebViewStaysAttachedAndRunsAsyncJs() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var web: WebView? = null

        // 用 debug-only 的空宿主 Activity：MainActivity 有持续 Compose 动画，
        // ActivityScenario 等 idle 会让单条测试跑十几分钟
        ActivityScenario.launch(DebugHostActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                val view = WebView(activity)
                web = view
                view.settings.javaScriptEnabled = true
                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView, url: String?) {
                        // 模拟页面内异步任务：1.2s 后写探针
                        v.evaluateJavascript(
                            "setTimeout(function(){ window.__probe = 'done'; }, 1200);",
                            null,
                        )
                    }
                }
                content.addView(view, FrameLayout.LayoutParams(1, 1))
                view.loadDataWithBaseURL(null, "<html><body>probe</body></html>", "text/html", "utf-8", null)

                // 上缴会话：旧实现会 removeView 使 WebView 脱离窗口
                BuaaWebSession.retain(view, activity)

                assertTrue("retain 之后 WebView 必须仍 attach 在窗口上", view.isAttachedToWindow)
            }

            val probe = awaitProbe(instrumentation, web!!)
            assertEquals("retain 之后页面内异步 JS 仍须执行", "done", probe)
        }

        // 清理单例，避免影响其它测试
        instrumentation.runOnMainSync { BuaaWebSession.clear() }
    }

    /**
     * 隐藏宿主必须建在 applicationContext 上。
     *
     * `BuaaWebSession` 是进程级单例：宿主用 Activity 建的话，退出登录后单例里
     * 还会握着一个已经 finish 的 Activity，整个窗口（含 DecorView）释放不掉。
     */
    @Test
    fun hiddenHostIsBuiltOnApplicationContext() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(DebugHostActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                val view = WebView(activity)
                content.addView(view, FrameLayout.LayoutParams(1, 1))
                BuaaWebSession.retain(view, activity)

                val host = view.parent as View
                assertFalse(
                    "单例持有的隐藏宿主不能握有 Activity",
                    host.context is android.app.Activity,
                )
            }
        }
        instrumentation.runOnMainSync { BuaaWebSession.clear() }
    }

    /** Activity 重建（转屏 / 从最近任务返回）后再上缴会话：宿主得搬到新窗口，不能留在旧窗口里。 */
    @Test
    fun retainAfterActivityRebuildReattachesToTheNewWindow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var web: WebView? = null

        ActivityScenario.launch(DebugHostActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                val view = WebView(activity)
                web = view
                content.addView(view, FrameLayout.LayoutParams(1, 1))
                BuaaWebSession.retain(view, activity)
            }
        }

        // 第二个宿主 Activity = 重建后的新窗口；旧窗口此时已经销毁
        ActivityScenario.launch(DebugHostActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                BuaaWebSession.retain(web!!, activity)

                assertTrue("重建后 WebView 必须重新 attach 到窗口", web!!.isAttachedToWindow)
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                assertSame(
                    "隐藏宿主必须搬到新窗口的 content，不能留在旧视图树里",
                    content,
                    (web!!.parent as View).parent,
                )
            }
        }

        instrumentation.runOnMainSync { BuaaWebSession.clear() }
    }

    /** 轮询全局探针，最多等 8s */
    private fun awaitProbe(instrumentation: Instrumentation, web: WebView): String? {
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            val value = evalBlocking(instrumentation, web, "window.__probe || 'pending'")
            if (value != null && value.contains("done")) return "done"
            Thread.sleep(300)
        }
        return evalBlocking(instrumentation, web, "window.__probe || 'pending'")
    }

    private fun evalBlocking(
        instrumentation: Instrumentation,
        web: WebView,
        script: String,
    ): String? {
        val latch = CountDownLatch(1)
        var result: String? = null
        instrumentation.runOnMainSync {
            web.evaluateJavascript(script) { value ->
                result = value
                latch.countDown()
            }
        }
        latch.await(3, TimeUnit.SECONDS)
        return result
    }
}
