package com.buaa.schedule.ui.signin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.buaa.schedule.BuildConfig
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.core.designsystem.GlassSurface
import com.buaa.schedule.core.designsystem.GlassTopBar
import com.buaa.schedule.core.designsystem.GlassVariant
import com.buaa.schedule.core.designsystem.LocalSemanticColors
import com.buaa.schedule.data.import.SpocSession
import com.buaa.schedule.data.import.SPOC_CAS_ENTRY
import com.buaa.schedule.data.import.redactUrl
import kotlinx.coroutines.delay

/**
 * 智学北航（SPOC）登录页。
 *
 * 与 [com.buaa.schedule.ui.importing.BuaaLoginScreen] 的结构一致，但只做一件事：
 * 走完 CAS 跳转让 H5 把 JWT 写进自己的 localStorage，然后收割 + 加密落盘，页面就可以
 * 销毁了 —— 之后所有签到请求都是纯 HTTP（`token` 头），不再需要这个 WebView。
 * 这也是它不必像教务那边 `retain()` 保住会话的原因。
 *
 * 起始地址是 `spocnewht/casmobile`：已登录统一身份认证时会直接回落到 H5，未登录才停
 * 在认证页。判断"登录完成"靠 URL 落回 `/bhspoc/`，之后轮询收割（H5 是 SPA，落 token
 * 发生在 `onPageFinished` 之后的一次接口回调里，只试一次会扑空）。
 */
@Composable
fun SpocLoginScreen(
    onBack: () -> Unit,
    /** 收割成功并已落盘后回调；调用方决定接下来去哪（扫码页 / 返回） */
    onLoggedIn: () -> Unit,
) {
    val context = LocalContext.current

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loadStarted by remember { mutableStateOf(false) }
    var pageVisible by remember { mutableStateOf(false) }
    var timedOut by remember { mutableStateOf(false) }
    var retryToken by remember { mutableIntStateOf(0) }
    // 每次 onPageFinished 自增：作为收割协程的 key，让「页面又动了一次」重新触发探测
    var probeTick by remember { mutableIntStateOf(0) }
    var harvesting by remember { mutableStateOf(false) }
    // 第几轮收割留成数值：只有文案在换的话，这 12 轮的窗口读起来就是"卡住了"（M5）
    var harvestAttempt by remember { mutableIntStateOf(0) }
    var harvestFailed by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(probeTick, retryToken) {
        val web = webViewRef ?: return@LaunchedEffect
        if (saved) return@LaunchedEffect
        // 只有落在 H5 上才值得收割；停在认证页时读到的必然是空的，
        // 那样会把「请输入账号密码」的阶段误报成「读取登录状态失败」。
        if (!web.url.orEmpty().contains(SPOC_H5_MARKER)) return@LaunchedEffect
        harvesting = true
        harvestFailed = false
        harvestAttempt = 0
        // SPA 拿到接口响应才写 storage，与 onPageFinished 之间没有事件可挂，只能给时间窗
        for (attempt in 1..HARVEST_ATTEMPTS) {
            harvestAttempt = attempt
            val credential = SpocSession.harvest(web)
            if (credential != null) {
                SpocSession.save(context, credential)
                harvesting = false
                saved = true
                onLoggedIn()
                return@LaunchedEffect
            }
            delay(HARVEST_INTERVAL_MILLIS)
        }
        harvesting = false
        harvestFailed = true
    }

    val statusText = when {
        saved -> "登录状态已保存，正在返回…"
        loadError != null -> "登录页加载失败：$loadError\n请检查网络（校园网/VPN）后重试。"
        timedOut -> "登录页加载超时（15 秒无响应）。\n请检查是否连接校园网或 VPN。"
        // 指引要对得上页面上真有的按钮：以前写的「重新读取」在这颗按钮上并不存在
        harvestFailed -> "已进入智学北航，但没读到登录状态。\n可能是页面还在跳转，点「原地再读一次」，或重新加载页面。"
        harvesting -> "正在读取登录状态（第 $harvestAttempt/$HARVEST_ATTEMPTS 次）…"
        !pageVisible -> "正在连接智学北航统一身份认证…"
        else -> null
    }

    fun retryLoad() {
        loadError = null
        timedOut = false
        loadStarted = false
        pageVisible = false
        loadProgress = 0
        harvestFailed = false
        retryToken++
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            GlassTopBar(
                title = "智学北航登录",
                progress = if (loadStarted && loadProgress in 1..99) loadProgress / 100f else null,
                statusBarInset = true,
                onBack = onBack,
            )
            key(retryToken) {
                val mainHandler = remember { Handler(Looper.getMainLooper()) }
                val timeoutRunnable = remember {
                    Runnable {
                        if (!loadStarted && loadError == null) {
                            timedOut = true
                            Log.w(TAG, "SPOC 登录页加载超时：15s 内未收到 onPageStarted")
                        }
                    }
                }
                DisposableEffect(retryToken) {
                    loadStarted = false
                    pageVisible = false
                    loadProgress = 0
                    loadError = null
                    timedOut = false
                    mainHandler.postDelayed(timeoutRunnable, LOAD_TIMEOUT_MILLIS)
                    onDispose { mainHandler.removeCallbacks(timeoutRunnable) }
                }
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { ctx ->
                        // WebView 必须垫一层 FrameLayout，不能直接交给 AndroidView，
                        // 否则 Chromium 量到的高度是 0 → 页面全白（同 BuaaLoginScreen）
                        FrameLayout(ctx).apply {
                            addView(
                                createSpocLoginWebView(
                                    context = ctx,
                                    onLoadStarted = {
                                        loadStarted = true
                                        timedOut = false
                                        loadError = null
                                    },
                                    onPageCommitVisible = { pageVisible = true },
                                    onProgress = { loadProgress = it },
                                    onMainFrameError = { loadError = it },
                                    onRenderGone = { retryToken++ },
                                    onPageSettled = { probeTick++ },
                                ).also { webViewRef = it },
                                FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                ),
                            )
                        }
                    },
                    onRelease = { holder -> holder.releaseChildren() },
                )
            }
        }

        // 出错时升为 ALERT + error 语义色（与教务登录页状态卡同档），中性进度仍留 PANEL
        if (statusText != null) {
            val isErrorStatus = loadError != null || timedOut || harvestFailed
            // 「登录状态已保存」是这一页唯一做完了的回执：success 进状态卡
            val isDoneStatus = saved
            val successInk = LocalSemanticColors.current.success
            GlassSurface(
                variant = if (isErrorStatus) GlassVariant.ALERT else GlassVariant.PANEL,
                semanticTint = when {
                    isErrorStatus -> MaterialTheme.colorScheme.error
                    isDoneStatus -> successInk
                    else -> null
                },
                contentPadding = DesignTokens.spaceL,
                shape = RoundedCornerShape(DesignTokens.cornerPanel),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // 全屏页：先让出系统导航栏再叠页面内缩（M6）
                    .navigationBarsPadding()
                    .padding(
                        start = DesignTokens.spaceL,
                        end = DesignTokens.spaceL,
                        bottom = DesignTokens.spaceL,
                    ),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceS)) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            isErrorStatus -> MaterialTheme.colorScheme.error
                            isDoneStatus -> successInk
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    // 收割轮次是确定的（HARVEST_ATTEMPTS），给出分母就画确定性进度（M5）
                    if (harvesting) {
                        LinearProgressIndicator(
                            progress = {
                                (harvestAttempt.toFloat() / HARVEST_ATTEMPTS).coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (isErrorStatus) {
                        Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.spaceS)) {
                            val cardAction = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = DesignTokens.minTouchTarget)
                            // 它做的是整页重刷，名字必须与旁边"只再探测一次"那颗区分开（M7）
                            Button(onClick = { retryLoad() }, modifier = cardAction) {
                                Text("重新加载页面")
                            }
                            // 重新读取只需要再探测一次，不必重刷页面：刷了反而可能掉登录态
                            if (harvestFailed && loadError == null && !timedOut) {
                                Button(
                                    onClick = {
                                        harvestFailed = false
                                        probeTick++
                                    },
                                    modifier = cardAction,
                                ) { Text("原地再读一次") }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 建登录 WebView。
 *
 * 与教务登录页的差异：这个实例**不被任何单例持有**，离开本页即 destroy，
 * 因此可以绑 Activity 上下文（默认的 JS 对话框实现需要窗口令牌，绑 application
 * 会让认证页的一次 `alert()` 崩掉进程）。
 */
private fun createSpocLoginWebView(
    context: Context,
    onLoadStarted: () -> Unit,
    onPageCommitVisible: () -> Unit,
    onProgress: (Int) -> Unit,
    onMainFrameError: (String) -> Unit,
    onRenderGone: () -> Unit,
    onPageSettled: () -> Unit,
): WebView = WebView(context).apply {
    @SuppressLint("SetJavaScriptEnabled")
    settings.javaScriptEnabled = true
    // 登录态就存在 localStorage 里，关掉等于收割必失败
    settings.domStorageEnabled = true
    settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.setSupportMultipleWindows(false)
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true

    // 新建实例会继承进程遗留的定时器挂起态（上次退后台 pause 后没有实例能 resume），
    // 不校正的话这一页一出生 JS 就是冻结的，登录页根本渲染不出来
    com.buaa.schedule.data.import.BuaaWebSession.alignTimersWithForeground(this)

    webViewClient = object : WebViewClient() {
        // CAS 跳转链必须由 WebView 自己走完，拦任何一跳都会打断 ticket 交换
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            Log.i(TAG, "onPageStarted: ${redactUrl(url)}")
            onLoadStarted()
        }

        override fun onPageCommitVisible(view: WebView?, url: String?) {
            Log.i(TAG, "onPageCommitVisible: ${redactUrl(url)}")
            onPageCommitVisible()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            Log.i(TAG, "onPageFinished: ${redactUrl(url)}")
            onPageSettled()
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            if (request?.isForMainFrame == true) {
                Log.w(TAG, "onReceivedError(main): ${error?.description}")
                onMainFrameError(error?.description?.toString() ?: "网络错误")
            }
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: WebResourceResponse?,
        ) {
            // 跳转链中间的 401/30x 很常见，交给下一跳；真正的网络失败由 onReceivedError 兜底
            Log.w(TAG, "onReceivedHttpError: ${redactUrl(request?.url?.toString())} -> ${errorResponse?.statusCode}")
        }

        override fun onReceivedSslError(
            view: WebView?,
            handler: android.webkit.SslErrorHandler?,
            error: android.net.http.SslError?,
        ) {
            Log.w(TAG, "onReceivedSslError: ${error?.primaryError}")
            handler?.cancel()
            onMainFrameError("SSL 证书错误（${error?.primaryError}）")
        }

        override fun onRenderProcessGone(
            view: WebView?,
            detail: android.webkit.RenderProcessGoneDetail?,
        ): Boolean {
            Log.w(TAG, "onRenderProcessGone: didCrash=${detail?.didCrash()}")
            onMainFrameError("WebView 渲染进程异常退出")
            onRenderGone()
            return true
        }
    }

    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            onProgress(newProgress)
        }

        override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
            // 只在 debug 回显：release 里页面会打印带 ticket 的跳转地址
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "console[${msg?.messageLevel()}]: ${msg?.message()} @${msg?.sourceId()}:${msg?.lineNumber()}")
            }
            return true
        }
    }

    // attach 之前 loadUrl 会被静默丢弃（无任何网络活动）
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            v.removeOnAttachStateChangeListener(this)
            v.post { loadUrl(SPOC_CAS_ENTRY) }
        }

        override fun onViewDetachedFromWindow(v: View) = Unit
    })
}

/** Destroy 容器里的 WebView，避免渲染进程泄漏 */
private fun FrameLayout.releaseChildren() {
    for (i in 0 until childCount) {
        (getChildAt(i) as? WebView)?.let { web ->
            web.stopLoading()
            web.destroy()
        }
    }
    removeAllViews()
}

/** H5 前端的路径标记；URL 里出现它才算「已经回到智学北航自己的页面」 */
private const val SPOC_H5_MARKER = "/bhspoc"

private const val TAG = "SpocLoginScreen"
private const val LOAD_TIMEOUT_MILLIS = 15_000L
private const val HARVEST_ATTEMPTS = 12
private const val HARVEST_INTERVAL_MILLIS = 800L
