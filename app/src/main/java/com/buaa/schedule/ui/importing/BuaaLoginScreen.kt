package com.buaa.schedule.ui.importing

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebChromeClient
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import com.buaa.schedule.BuildConfig
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.core.designsystem.GlassSurface
import com.buaa.schedule.core.designsystem.GlassTopBar
import com.buaa.schedule.core.designsystem.LocalSemanticColors
import com.buaa.schedule.core.designsystem.LocalSemanticPlate
import com.buaa.schedule.core.designsystem.GlassVariant
import com.buaa.schedule.data.import.BuaaInPageFetcher
import com.buaa.schedule.data.import.BuaaImportJob
import com.buaa.schedule.data.import.BuaaSemesterStart
import com.buaa.schedule.data.import.BuaaWebSession
import com.buaa.schedule.data.import.redactUrl
import com.buaa.schedule.data.import.resolveBuaaSemesterStart
import com.buaa.schedule.domain.session.RetainSource
import com.buaa.schedule.domain.url.EduDocument
import com.buaa.schedule.domain.url.eduDocumentVerdict
import com.buaa.schedule.ui.ScheduleViewModel
import java.net.URLEncoder

/**
 * 北航统一身份认证 WebView 登录页。
 *
 * 登录成功后 WebView 会跳转到 byxt.buaa.edu.cn/jwapp 首页，**在页面上下文里**
 * 逐周 fetch 教务接口拿到课表 JSON（byxt 的鉴权凭证无法外带到原生网络栈，
 * 见 [BuaaInPageFetcher] 的说明），解析后进入导入预览，用户确认后才落库。
 * 登录会话本身由 `BuaaWebSession` 保留（WebView 转挂隐藏宿主 + CookieManager 持久化）。
 *
 * 加载健壮性：
 * - WebView 创建/加载/销毁由 AndroidView factory/onRelease 驱动；
 * - **WebView 必须放在一层普通 FrameLayout 里**，不能直接交给 AndroidView，
 *   原因见 [createSsoWebView] 的注释（否则必然白屏）；
 * - loadUrl 在 attach 后执行（attach 前调用会被 WebView 静默丢弃→无网络活动）；
 * - 加载启动超时：15 秒内无 onPageStarted 显示超时提示；
 * - 主文档网络错误、HTTP 错误、SSL 错误、渲染进程崩溃均有可见提示和重试；
 * - onRenderProcessGone 触发重建 WebView；
 * - onPageCommitVisible 才认为“页面真正可见”。
 */
@Composable
fun BuaaLoginScreen(
    onBack: () -> Unit,
    /** 抓取完成并生成导入预览后回调：调用方负责把用户带到能看见预览卡片的页面 */
    onImportPrepared: () -> Unit = {},
    viewModel: ScheduleViewModel = viewModel(
        factory = ScheduleViewModel.Factory(LocalContext.current.applicationContext as android.app.Application),
    ),
) {
    val context = LocalContext.current
    val termCode by viewModel.buaaTermCode.collectAsState()
    // 登录完成后在 WebView 页面内抓取课表（byxt 凭证无法外带，见 BuaaInPageFetcher）
    // 平台那一枚的合法载体是进程内 holders（BuaaWebSession / 本卡的 BuaaImportJob），
    // **不许**改 rememberSaveable：WebView 不可快照，塞进 savedInstanceState 是错的（在册 #412 的证伪档①）。
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    // 六枚**在飞事实**（fetchState / fetchWeek / fetchTotal / fetchStarted / fetchCancelled / importPrepared）
    // 的载体是进程内 holder `data/import/BuaaImportJob`，不再是组合 —— 判据在
    // `domain/importing/FetchJobCarrierPolicy`：在飞事实的载体寿命必须 ≥ 作业寿命。
    // 抓取进度的分子分母单独留成数值：换文案只是眨眼，有分数才画得出确定性进度条。
    // 19 周逐周请求在网络差时要跑几十秒，用户需要一个"还在走"的证据（M5）
    // 改前这一族是七枚 `by remember { mutable… }`：转屏（Manifest 零枚 configChanges ⇒ 必重建 Activity）
    // 一次就把它们清零，连带把下面那枚 effect 的键清掉 ⇒ 作业被取消、进度退回 0/N、结论句消失。
    val importProgress = BuaaImportJob.progress.collectAsState().value
    val fetchState = importProgress.fetchState
    val fetchWeek = importProgress.fetchWeek
    val fetchTotal = importProgress.fetchTotal
    val fetchStarted = importProgress.fetchStarted
    val fetchCancelled = importProgress.fetchCancelled
    val importPrepared = importProgress.importPrepared
    val leavePage = onBack
    val openPreviewPage = onImportPrepared
    // 页面出口每次组合挂一枚上来：转屏后旧那一枚被摘掉、新那一枚接上，
    // 作业只朝**当前活着**的那一枚说话（否则那句结论会写进一具已经作废的组合里）。
    val host = remember(context, viewModel, leavePage, openPreviewPage) {
        object : BuaaImportJob.Host {
            override val pageContext: Context get() = context
            override fun showMessage(message: String, isError: Boolean) {
                viewModel.showMessage(message, isError = isError)
            }

            override suspend fun previewBuaaCourses(
                semester: com.buaa.schedule.domain.model.Semester,
                courses: List<com.buaa.schedule.domain.model.Course>,
                warnings: List<String>,
            ) {
                viewModel.previewBuaaCourses(semester, courses, warnings)
            }

            override fun onBack() {
                leavePage()
            }

            override fun onImportPrepared() {
                openPreviewPage()
            }
        }
    }
    LaunchedEffect(webViewRef) {
        // ⚠️ 键表里只有平台，没有 fetchStarted：把扳机放进键表 = 「键被清零 ⇒ effect 重跑 ⇒ 从头再抓」，
        //    那是把几十秒作废后重新开始，不叫恢复（在册 #412 的证伪档②，同 MainActivity 那句「不许按
        //    courseMissing 重启」一个道理）。点不点火由 holder 的闸门按 FetchJobCarrierPolicy 裁。
        webViewRef?.let { BuaaImportJob.attachPlatform(it) }
    }
    DisposableEffect(host) {
        // 作业本体的**文本**长在页面层这一枚文件里（闸门次序、两句失败措辞一字未改），
        // 作用域交给 holder —— 这一行就是「被组合销毁」与「不被组合销毁」的分界。
        BuaaImportJob.attach(host) { web, progress -> fetchBuaaScheduleInPage(web, progress) }
        onDispose { BuaaImportJob.detach(host) }
    }
    // 作业本体见文件末尾的 fetchBuaaScheduleInPage：文本留在这枚文件（四枚在册守卫的针因此一字不改），
    // 跑的作用域在 data/import/BuaaImportJob —— 改前它是组合上那一格
    // `LaunchedEffect(webViewRef, fetchStarted) { … }`，键被清零就是把整轮作废。
    var loadProgress by remember { mutableIntStateOf(0) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loadStarted by remember { mutableStateOf(false) }
    var pageVisible by remember { mutableStateOf(false) }
    var timedOut by remember { mutableStateOf(false) }
    var retryToken by remember { mutableIntStateOf(0) }

    val loginUrl = "https://sso.buaa.edu.cn/login?service=" +
        URLEncoder.encode("https://byxt.buaa.edu.cn/jwapp/sys/homeapp/index.do", "UTF-8")

    // Cookie 与登录 WebView 均保留（BuaaWebSession）：冷启动后 SSO 免登录直达，
    // 重复导入 / 刷新课表复用同一会话

    // 状态条文案：抓取进度 / 加载失败 / 已取消 —— 集中到底部浮动卡，不挤压 WebView。
    // （此前这些提示放在 WebView 上方，把网页压掉一小截，页面显得"尺寸不适配"）
    // 转屏打断过一轮时这句进度话必须说实话：这一轮是从头再取的，不是刚才那一轮的延续
    // —— 作业与读数同住 holder，可页面脚下那枚 WebView 随旧组合一起销毁了（续跑要 startWeek，在册残账）。
    val fetchStateText = if (importProgress.interruptedByRebuild && fetchState != null) {
        "$fetchState\n（刚才转屏打断了一轮，这一轮从头再取；登录会话仍在，不必重登）"
    } else {
        fetchState
    }
    val loadErrorText = loadError
    val statusText = when {
        importPrepared -> "课程已获取完成，正在打开导入预览…"
        loadErrorText != null -> "登录页加载失败：$loadErrorText\n请检查网络（校园网/VPN）后重试。"
        timedOut -> "登录页加载超时（15 秒无响应）。\n请检查是否连接校园网或 VPN。"
        fetchStateText != null -> fetchStateText
        fetchCancelled -> "已取消导入。登录会话仍在，可直接重新获取课表。"
        !pageVisible && !fetchStarted -> "正在连接北航统一身份认证..."
        else -> null
    }
    val showCancelAction = fetchStateText != null && fetchStarted && !fetchCancelled

    fun retryLoad() {
        loadError = null
        timedOut = false
        loadStarted = false
        pageVisible = false
        loadProgress = 0
        retryToken++
    }

    /**
     * 返回前先保住登录会话：抓取中直接返回会让 AndroidView.onRelease 销毁 WebView，
     * 用户已经登录的 byxt 会话随之丢失（R4 P2-3）。
     * 已取消 / 未开始抓取时 retain 是幂等 no-op，统一走这里最安全。
     *
     * T378②之后这一手还要多认一句：上缴**不等于收下** —— `retain` 现在自判出身
     * （判据本体在 `domain/session/SessionRetainGate`），页面此刻不在教务 origin 就是拒收档，
     * 手里的活会话与 `lastUrl` 都不动，只把 Cookie 落盘。改前五枚没有 origin 判据的上缴调用点
     * 各自都能把手里那枚真登录着的实例顶掉，这是那一格的病根。
     */
    fun safeBack() {
        if (fetchStarted && !fetchCancelled) {
            webViewRef?.let { web ->
                BuaaWebSession.retain(web, RetainSource.LoginScreen, context.findActivity())
            }
        }
        onBack()
    }

    // 系统返回键 / 侧滑手势同样要先保住会话再离开：retain 到抓取结束才发生，
    // 而抓取窗口有几十秒，绕过 safeBack 直接 pop 会让 onRelease 当场 destroy WebView。
    BackHandler(enabled = fetchStarted && !fetchCancelled) { safeBack() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            GlassTopBar(
                title = "统一身份认证登录",
                progress = if (loadStarted && loadProgress in 1..99) loadProgress / 100f else null,
                statusBarInset = true,
                onBack = { safeBack() },
                actions = {
                    Text(
                        text = termCode,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            // retryToken 变化时销毁重建 WebView，实现“重新加载”
            key(retryToken) {
                val mainHandler = remember { Handler(Looper.getMainLooper()) }
                val timeoutRunnable = remember {
                    Runnable {
                        if (!loadStarted && loadError == null) {
                            timedOut = true
                            Log.w(TAG, "SSO 加载超时：15s 内未收到 onPageStarted")
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
                    onDispose {
                        mainHandler.removeCallbacks(timeoutRunnable)
                    }
                }
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { ctx ->
                        FrameLayout(ctx).apply {
                            addView(
                                createSsoWebView(
                                    // 必须用 applicationContext：这个 WebView 会被 retain()
                                    // 交给 BuaaWebSession 单例长期持有，绑 Activity 就等于
                                    // 每转一次屏泄漏一个已 finish 的窗口。
                                    context = ctx.applicationContext,
                                    loginUrl = loginUrl,
                                    onLoadStarted = {
                                        loadStarted = true
                                        timedOut = false
                                        loadError = null
                                    },
                                    onPageCommitVisible = { pageVisible = true },
                                    onProgress = { loadProgress = it },
                                    onMainFrameError = { loadError = it },
                                    onRenderGone = { retryToken++ },
                                    onLoggedIn = {
                                        // 要求抓取 = 把 fetchStarted 落进 holder（点不点火由闸门裁：
                                        // 已经在飞的那一轮绝不重跑，平台换枚才再点一次）
                                        BuaaImportJob.askToFetch()
                                    },
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

        // 浮动状态卡：贴底、不遮登录表单，也不挤压 WebView 高度。
        // 出错时升为 ALERT + error 语义色（与首页冲突横幅同一档），中性进度仍留 PANEL
        if (statusText != null) {
            val isErrorStatus = loadError != null || timedOut
            // 「课程已获取完成」是这一页唯一做完了的回执：success 进状态卡
            val isDoneStatus = importPrepared
            // 只声明意图（这块卡想染成 success）；最终染哪支、上面写哪支由 GlassSurface 配对
            val successTint = LocalSemanticColors.current.success
            val fetchFraction = if (fetchTotal > 0 && fetchStateText != null) {
                (fetchWeek.toFloat() / fetchTotal).coerceIn(0f, 1f)
            } else {
                null
            }
            GlassSurface(
                variant = if (isErrorStatus) GlassVariant.ALERT else GlassVariant.PANEL,
                semanticTint = when {
                    isErrorStatus -> MaterialTheme.colorScheme.error
                    isDoneStatus -> successTint
                    else -> null
                },
                contentPadding = DesignTokens.spaceL,
                shape = RoundedCornerShape(DesignTokens.cornerPanel),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // 全屏页：先让出系统导航栏，再叠页面内缩，
                    // 否则三键导航机上整张卡压在导航键上（M6）
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
                        // 成对取墨：染了语义色（失败/完成）时底板与文字一次解出，
                        // 中性进度那张卡没染色的仍走 onSurfaceVariant
                        color = LocalSemanticPlate.current?.foreground
                            ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 19 周逐周请求在网络差时要跑几十秒。只有文案在换的话，
                    // 这段等待读起来就是"卡住了"；有分母就画确定性进度（M5）
                    if (fetchFraction != null) {
                        LinearProgressIndicator(
                            progress = { fetchFraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.spaceS)) {
                        // 这一行全是"救场"入口，实测 40dp 比正文行还矮；统一抬到 48dp（M4）
                        val cardAction = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = DesignTokens.minTouchTarget)
                        if (loadError != null) {
                            Button(onClick = { retryLoad() }, modifier = cardAction) {
                                Text("重新加载")
                            }
                        }
                        if (timedOut) {
                            Button(onClick = { retryLoad() }, modifier = cardAction) {
                                Text("重试")
                            }
                        }
                        if (showCancelAction) {
                            Button(
                                onClick = {
                                    // 取消的是 holder 上那一支作业（改前它靠把 fetchStarted 折成 false
                                    // 让组合里那枚 effect 的键变化来取消 —— 同一只手在转屏时也这么杀了作业）
                                    BuaaImportJob.cancel()
                                    webViewRef?.let { web ->
                                        BuaaWebSession.retain(
                                            web,
                                            RetainSource.LoginScreen,
                                            context.findActivity(),
                                        )
                                    }
                                    // 这句说的是**登录态**（Cookie 已落盘、手里那枚活会话没被顶掉），
                                    // 不是「这一枚 WebView 一定被收下」：页面还停在 SSO 时上缴会被
                                    // domain/session/SessionRetainGate 判成拒收档（T378②）。
                                    viewModel.showMessage("已取消导入，登录会话已保留")
                                },
                                modifier = cardAction,
                            ) { Text("取消导入") }
                        }
                        if (importPrepared) {
                            Button(
                                onClick = onImportPrepared,
                                modifier = cardAction,
                            ) { Text("查看导入预览") }
                        }
                        if (fetchCancelled) {
                            Button(
                                onClick = {
                                    // 取消档收回 + 重新要求抓：要不要真的点火仍由 holder 的闸门裁
                                    BuaaImportJob.askAgain()
                                },
                                modifier = cardAction,
                            ) { Text("重新获取") }
                            TextButton(onClick = { safeBack() }, modifier = cardAction) {
                                Text("返回")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 抓取作业本体（T336② 从组合里那一格 `LaunchedEffect(webViewRef, fetchStarted)` 原样搬出来）。
 *
 * 搬走的只有**长在哪枚作用域上**：文本留在这枚文件（闸门次序、retain 的位置、两句失败措辞、开学日那一档
 * 分档一字未改 ⇒ `SemesterGateOrderingGuardTest` / `BuaaSemesterStartWiringGuardTest` /
 * `SemesterInfoReasonGuardTest` / `EduWebOriginSingleRulerGuardTest` 四枚在册守卫的针仍落在同一枚文件里），
 * 而跑的作用是 [BuaaImportJob] 的进程级 scope —— 组合销毁不再等于作业销毁。
 *
 * 读数经 [BuaaImportJob.ProgressWriter]（那六枚在飞事实的载体就是它），出口经 [BuaaImportJob.Host]
 * （每次组合重新挂上来的那一枚 ⇒ 转屏后那句结论不会写进一具已经作废的组合）。
 * 方法名 `showMessage` / `previewBuaaCourses` / `onBack` / `onImportPrepared` 与页面原来直接调的那几枚逐字同名，
 * 所以这一段的正文只少了 `viewModel.` 那一层前缀。
 */
private suspend fun BuaaImportJob.Host.fetchBuaaScheduleInPage(
    web: WebView,
    progress: BuaaImportJob.ProgressWriter,
) {
    val context = pageContext
    with(progress) {
        importPrepared = false
        // 登录成功 → byxt 页面已就绪，页面上下文 fetch 拉课表（原生栈复刻不出凭证 → 401，
        // 唯一可靠路径是 WebView 页面内 fetch，见 BuaaInPageFetcher）
        val fetcher = com.buaa.schedule.data.import.BuaaInPageFetcher
        // R5 F-39：SSO 回跳途中文档可能还停在 sso 域，此时页面内请求打不到教务接口。
        // 注入脚本已钉死绝对 origin，这里只做提示，避免把「没登录」报成「网络差」。
        // T302③（在册 #515）：改前这一行是 `web.url.orEmpty().startsWith(BYXT_ORIGIN)` —— **少一枚尾斜杠**，
        // 于是 `https://byxt.buaa.edu.cn.evil.com/` 与 `https://byxt.buaa.edu.cn@evil.com/` 都能过这道闸门，
        // "教务系统页面尚未就绪"那句就在外人文档上说了谎（⓪ 实测：裸前缀对这两枚全部命中）。
        // 现在问 domain/url 那枚内核：它逐段比 scheme/host/port，整段不等就是 NotEduHost，没有前缀可蹭。
        if (eduDocumentVerdict(web.url) != EduDocument.OnEduOrigin) {
            fetchState = null
            showMessage("教务系统页面尚未就绪，请稍后重试", isError = true)
            // T378②：这一枚候选**注定是拒收档**（上面那一问就是它的出身），所以它不会再顶掉手里
            // 那枚真登录着的会话、也不会再把 `lastUrl` 写脏。留这一手的理由只剩「凭证」那一半：
            // retain 的拒收档照样把会话 Cookie 加密落盘并打上 PREF_RETAINED，否则用户刚刚拿到的
            // SSO 票据会随着这枚 WebView 一起被 onRelease 销毁 —— 那是改前顺带保住的东西，不许修没。
            BuaaWebSession.retain(web, RetainSource.LoginScreen, context.findActivity())
            onBack()
            return
        }
        fetchState = "正在获取学年学期列表..."
        // 新一轮从 0 开始：否则「重新获取」会先闪一下上一轮的 N/N 满格
        fetchWeek = 0
        fetchTotal = 0
        val terms = fetcher.fetchTermList(web)
        if (terms.isEmpty()) {
            fetchState = null
            showMessage("获取学期列表失败，请稍后重试", isError = true)
            BuaaWebSession.retain(web, RetainSource.LoginScreen, context.findActivity())
            onBack()
        } else {
            val selectedTerm = terms.firstOrNull { it.selected }?.code
                ?: terms.first().code
            fetchState = "正在获取学期信息（学期 $selectedTerm）..."
            val info = fetcher.fetchSemesterInfo(web, selectedTerm)
            val totalWeeks = info.totalWeeks
            // ── 闸门：开学日认不出来就当场返回，**一行课表都不抓**（T196②）──
            //
            // 改前的次序是「抓完二十周再判开学日」：`resolveBuaaSemesterStart` 长在
            // `fetchSemesterCourses` 之后的 `else` 支里，于是开学日取不到时用户已经等完整轮逐周抓取
            // （每周一到两次页面内 fetch），最后听到一句「已保留原课表未做改动，请稍后重试」——
            // 那一轮空转正好是 20 周，因为失败档的 `totalWeeks` 是硬写的 20。
            // 对照本仓另一路：静默刷新 `data/import/BuaaWebSession.kt` 的 `refreshSchedule` 次序本来就是
            // 对的（拿信息 → 立刻判 → 才逐周抓），首次导入这一路照它改。
            //
            // **两枚失败档的优先级在这里定死**（次序即成本次序，不是语义次序）：
            // 1. 先判开学日 `resolveBuaaSemesterStart` —— 便宜的一枚：它只吃已经拿到手的那枚串，
            //    零页面内 fetch，认不出来整轮逐周抓取根本不必开始；
            // 2. 再判 `outcome.failedWeeks` 与 `outcome.courses.isEmpty()` —— 贵的那两枚：
            //    它们要有逐周抓取的结果才谈得上，而那句「抓取未完成：第 … 周失败」的措辞一个字没改。
            //    判据本体不许在这里复刻，两枚都只读 `SemesterFetchOutcome` 的现成结论。
            //
            // 开学日只认 getTermWeeks 第 1 周的 startDate（过 mondayOf 归一成周一）。
            // **认不出来就不动学期**，口径对齐静默刷新那一路（BuaaWebSession.refreshSchedule
            // 的 `?: existingStartDate ?: return Result.failure(...)`；首次导入手里没有
            // existingStartDate，所以那一等落到"报错 + 返回"）。
            // 旧形状是 `runCatching { mondayOf(LocalDate.parse(rawStart)) }.getOrDefault(rawStart)`
            // —— 兜底值正是 parse 失败的那枚原串，坏日期因此原样进 Semester.startDate 并落库，
            // 此后 Semester.startLocalDate 恒 null（周课表空网格、提醒链 cancelAll、
            // 组件停在"假期中"、设置页念出并回写这串垃圾）。也不许换成"今天所在周的周一"：
            // 提前导入下一学期时那是一枚猜出来的日期，整表日期与周次编号一起偏。
            // 判据本体在 resolveBuaaSemesterStart（纯 JVM、零时钟），这里只取它的结论。
            when (val start = resolveBuaaSemesterStart(info.firstWeekMonday)) {
                is BuaaSemesterStart.Undetermined -> {
                    // 提前返回也要先把登录 WebView 保住再报错（T196② 复核过的那一行）：
                    // retain 原先只长在逐周抓取之后，闸门搬到抓取之前以后，这一支要是照旧不掉 retain，
                    // 用户返回时 AndroidView.onRelease 会当场 destroy 那枚 WebView —— 已经登录的 byxt
                    // 会话跟着丢，下一次导入得重新走 SSO。形状与这一趟另两枚提前返回（页面没就绪 /
                    // 学期列表为空）一致：retain → 清状态 → 报错 → onBack。
                    // **不**把 retain 提前到 fetchSemesterInfo 之前：那会在页面内请求还在跑的时候
                    // 把 WebView 转挂隐藏宿主，改的是抓取链的宿主而不是这一格的次序。
                    BuaaWebSession.retain(web, RetainSource.LoginScreen, context.findActivity())
                    fetchState = null
                    // 文案按两维分档（T196④）：**有没有拿到一枚串**（start.raw）与
                    // **页面到底答没答、答成什么样**（info.missingReason，五档早返回一一对应）。
                    // 映射本体是纯函数 semesterStartFailureMessage（同包、零 android、照
                    // ImportCompletionCopy 的先例），那七句由 SemesterStartFailureCopyTest 表驱动钉住；
                    // 这里只递两枚现成的事实，不在页面里再判一次日期、也不在这里长第二本原因表。
                    showMessage(
                        semesterStartFailureMessage(raw = start.raw, reason = info.missingReason),
                        isError = true,
                    )
                    onBack()
                }
                is BuaaSemesterStart.Resolved -> {
                    fetchState = "正在逐周获取课表（学期 $selectedTerm）..."
                    val outcome = fetcher.fetchSemesterCourses(
                        webView = web,
                        termCode = selectedTerm,
                        totalWeeks = totalWeeks,
                        onProgress = { week, total ->
                            fetchWeek = week
                            fetchTotal = total
                            fetchState = "正在获取课表：第 $week/$total 周..."
                        },
                    )
                    // 保留登录 WebView（byxt 页面上下文，供刷新课表复用）
                    BuaaWebSession.retain(web, RetainSource.LoginScreen, context.findActivity())
                    fetchState = null
                    when {
                        // 有周次没抓到：宁可让用户重试，也不能用不完整的结果覆盖既有课表
                        // （覆盖导入是"先清空该学期再写入"，没抓到的周次会被连带删掉）
                        outcome.failedWeeks.isNotEmpty() -> {
                            showMessage(
                                "抓取未完成：第 ${outcome.failedWeeks.joinToString("、")} 周失败，" +
                                    "已保留原课表未做改动，请稍后重试",
                                isError = true,
                            )
                            onBack()
                        }
                        outcome.courses.isEmpty() -> {
                            showMessage("教务系统未返回该学期课程（可能未选课）", isError = true)
                            onBack()
                        }
                        else -> {
                            val termName = terms.firstOrNull { it.code == selectedTerm }?.name ?: selectedTerm
                            val semester = com.buaa.schedule.domain.model.Semester(
                                termCode = selectedTerm,
                                termName = termName,
                                startDate = start.monday.toString(),
                                totalWeeks = totalWeeks,
                            )
                            // 进入导入预览（不直接落库），随后跳到导入页让用户确认。
                            // 用 Outcome 版解析：缺教师/按整学期兜底的条数要作为警告带进预览，
                            // 此前走 parseArrangedList 把警告整个丢掉了
                            val parsed = com.buaa.schedule.data.import.BuaaScheduleParser
                                .parseArrangedListOutcome(outcome.courses, selectedTerm, totalWeeks)
                            val parseWarnings = buildList {
                                if (parsed.fallbackWeekCourses > 0) {
                                    add("${parsed.fallbackWeekCourses} 条课程缺少教师/周次信息，已按整学期展示，请核对")
                                }
                                if (parsed.unknownTeacherCourses > 0 && parsed.fallbackWeekCourses == 0) {
                                    add("${parsed.unknownTeacherCourses} 条课程缺少教师信息")
                                }
                            }
                            previewBuaaCourses(semester, parsed.courses, parseWarnings)
                            importPrepared = true
                            onImportPrepared()
                        }
                    }
                }
            }
        }
    }
}

/**
 * 建 WebView 并挂好回调（此时还没有 loadUrl）。
 *
 * ⚠️ 调用方必须把它放进一层普通 [FrameLayout]，不能直接作为 `AndroidView` 的 factory 返回值。
 *
 * 原因：Compose 的 `AndroidView` 会把 factory 产出的 View 直接塞进它自己的
 * `ViewFactoryHolder`（`AndroidViewHolder`，一个 ViewGroup）里。WebView 直接以它为父容器时，
 * Chromium 拿到的高度是 0——页面里 `html, body { height: 100% }` 全部算成 `0px`，
 * 视觉视口又是正常值（`window.innerHeight` 正确），于是根元素高度为 0。
 *
 * 北航 SSO 首页刚好是「一个撑满视口的 `<iframe>` 承载真正的登录表单」的结构：
 * - 父页面 `html, body, iframe { height: 100%; overflow: hidden }`
 * - `<iframe src="/cas/login-mobile.html">` 里是 Vue 2 + Vant 的登录表单
 * - 表单其实渲染成功（Vue 已挂载、DOM 完整），但 iframe 高度为 0 被整体裁掉
 *
 * 结果就是：页面加载完成、无任何报错、`onPageCommitVisible` 正常回调，屏幕却是一片纯白。
 * 实测：直接交给 AndroidView 时 `getComputedStyle(html).height == "0px"`；
 * 中间垫一层 FrameLayout 后为 `895px`，页面正常显示。
 */
private fun createSsoWebView(
    context: Context,
    loginUrl: String,
    onLoadStarted: () -> Unit,
    onPageCommitVisible: () -> Unit,
    onProgress: (Int) -> Unit,
    onMainFrameError: (String) -> Unit,
    onRenderGone: () -> Unit,
    onLoggedIn: () -> Unit,
): WebView = WebView(context).apply {
    var pageFetchStarted = false
    @SuppressLint("SetJavaScriptEnabled")
    settings.javaScriptEnabled = true
    // 必须开启：byxt 的 SPA（umi）用 localStorage 存会话，禁用会导致
    // umi 抛 "Cannot read properties of null (reading 'getItem')" → 白屏、不发 API 请求
    settings.domStorageEnabled = true
    // 不要再设 settings.databaseEnabled：WebSQL 已被 WebView 移除，
    // 该开关在 API 33+ 已废弃且无效果，留着只有 deprecation 警告。
    settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
    // 登录页只需要访问网络：本地文件 / content:// / 多窗口都不需要，
    // 关掉可减少被恶意页面（若发生跳转劫持）利用的攻击面
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.setSupportMultipleWindows(false)
    // 视口适配：按网页声明的 viewport 渲染并整体缩放适配屏幕，
    // 否则部分页面会以桌面宽度排版、只能左右滚动才能看全（"网页尺寸不适配"的根源）
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true

    // 新建的 WebView 会继承进程遗留的定时器挂起态（上次退后台时 pause、之后手上
    // 再没有实例能 resume）—— 那样这一页一出生 JS 就是冻结的，onPageFinished
    // 永不回调，登录看着像卡死。见 BuaaWebSession.applyTimerGate。
    com.buaa.schedule.data.import.BuaaWebSession.alignTimersWithForeground(this)

    webViewClient = object : WebViewClient() {

        /**
         * 不接管 URL 加载：登录跳转链（SSO → byxt → homeapp）必须让 WebView 自己走完，
         * 任何拦截都会打断 ticket 换取流程。
         * 响应体采集改由 BuaaInPageFetcher 在页面内 fetch 完成。
         */
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?,
        ): Boolean = false

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            Log.i(TAG, "onPageStarted: ${redactUrl(url)}")
            onLoadStarted()
        }

        override fun onPageCommitVisible(view: WebView?, url: String?) {
            Log.i(TAG, "onPageCommitVisible: ${redactUrl(url)}")
            onPageCommitVisible()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            Log.i(TAG, "onPageFinished: ${redactUrl(url)} title=${view?.title?.take(30)}")
            maybeCaptureSession(url)
        }

        /**
         * 登录成功后的跳转链（SSO → byxt index.do?ticket=… → 302/JS → homeapp 首页）。
         *
         * byxt 的 API 鉴权不走可复制的 Cookie（实测 byxt 域只有 _zte_* 路由 cookie，
         * 会话凭证在页面 storage / 请求头里，外部 OkHttp 直接调 API 会 401）。
         * 因此登录完成后改为【页面内抓取】：在 WebView 里用 fetch 调教务接口，
         * 同源请求自动携带页面全部凭证，拿到 JSON 后回传原生侧解析。
         */
        private fun maybeCaptureSession(url: String?) {
            if (url?.startsWith("https://byxt.buaa.edu.cn/jwapp/sys/homeapp/") != true) return
            if (pageFetchStarted) return
            pageFetchStarted = true
            Log.i(TAG, "byxt 首页已就绪，开始在页面内抓取课表 JSON")
            onLoggedIn()
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            if (request?.isForMainFrame == true) {
                Log.w(TAG, "onReceivedError(main): ${error?.description}")
                onMainFrameError(error?.description?.toString() ?: "网络错误")
            } else {
                // 子资源失败不阻断登录，但要留下痕迹便于排查
                Log.w(TAG, "onReceivedError(sub): ${redactUrl(request?.url?.toString())} ${error?.description}")
            }
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: WebResourceResponse?,
        ) {
            Log.w(TAG, "onReceivedHttpError: ${redactUrl(request?.url?.toString())} -> ${errorResponse?.statusCode}")
            if (request?.isForMainFrame == true) {
                // 登录跳转链的中间 401/30x 常见：先不报错，等待下一个跳转；
                // 只有最终停留的错误页才中断（onReceivedError 会兜底真正的网络失败）
            }
        }

        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: android.net.http.SslError?,
        ) {
            // 失败关闭：不调用 handler.proceed()
            Log.w(TAG, "onReceivedSslError: ${error?.primaryError}")
            handler?.cancel()
            onMainFrameError("SSL 证书错误（${error?.primaryError}）")
        }

        override fun onRenderProcessGone(
            view: WebView?,
            detail: RenderProcessGoneDetail?,
        ): Boolean {
            Log.w(TAG, "onRenderProcessGone: didCrash=${detail?.didCrash()}")
            onMainFrameError("WebView 渲染进程异常退出")
            // 返回 true 表示已处理；重建由 retryToken 触发的 onRelease 负责
            onRenderGone()
            return true
        }
    }

    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            onProgress(newProgress)
        }

        override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
            // 只在 debug 构建里回显页面 console：release 里页面会打印大量业务数据
            // （含 SSO 跳转 URL 与接口返回片段），属信息泄漏面
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "console[${msg?.messageLevel()}]: ${msg?.message()} @${msg?.sourceId()}:${msg?.lineNumber()}")
            }
            return true
        }

        // WebView 用 applicationContext 创建（会话要跨 Activity 存活），而系统默认的
        // JS 对话框实现会拿这个 Context 去 inflate AlertDialog —— 没有窗口令牌，
        // 一个 alert() 就能崩掉进程。登录表单是前端校验，不依赖原生对话框，一律取消。
        override fun onJsAlert(
            view: WebView?,
            url: String?,
            message: String?,
            result: android.webkit.JsResult?,
        ): Boolean {
            result?.cancel()
            return true
        }

        override fun onJsConfirm(
            view: WebView?,
            url: String?,
            message: String?,
            result: android.webkit.JsResult?,
        ): Boolean {
            result?.cancel()
            return true
        }

        override fun onJsPrompt(
            view: WebView?,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: android.webkit.JsPromptResult?,
        ): Boolean {
            result?.cancel()
            return true
        }
    }

    // loadUrl 必须在 attach 到窗口后调用：attach 前调用会被 WebView 静默丢弃（无任何网络活动）
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            v.removeOnAttachStateChangeListener(this)
            Log.i(TAG, "WebView attached, loading: ${redactUrl(loginUrl)}")
            v.post { loadUrl(loginUrl) }
        }

        override fun onViewDetachedFromWindow(v: View) = Unit
    })
}

/** Destroy 容器里的 WebView，避免渲染进程泄漏 */
private fun FrameLayout.releaseChildren() {
    for (i in 0 until childCount) {
        (getChildAt(i) as? WebView)?.let { web ->
            // 作业脚下那一枚平台正随组合销毁：先给 holder 留痕（由它按 FetchJobCarrierPolicy 的闸门
            // 决定「换到新平台再点一次」），再走原来的 stopLoading + destroy —— 销毁次序一字未改。
            com.buaa.schedule.data.import.BuaaImportJob.detachPlatform(web)
            web.stopLoading()
            web.destroy()
        }
    }
    removeAllViews()
}

private const val TAG = "BuaaLoginScreen"
private const val LOAD_TIMEOUT_MILLIS = 15_000L

/**
 * 从 Context 链里找宿主 Activity。
 * 保留登录会话时需要它来拿到窗口内容视图 —— 脱离窗口的 WebView 不执行 JS。
 */
private fun Context.findActivity(): android.app.Activity? {
    var ctx: Context? = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
