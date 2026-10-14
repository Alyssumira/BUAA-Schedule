package com.buaa.schedule.data.import

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.edit
import com.buaa.schedule.data.local.BuaaCookieStore
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.session.PreviousWebView
import com.buaa.schedule.domain.session.RetainSource
import com.buaa.schedule.domain.session.RestoreBackoffSentinel
import com.buaa.schedule.domain.session.RestoreBackoffWindow
import com.buaa.schedule.domain.session.RestoreBackoffWindowGate
import com.buaa.schedule.domain.session.RestoreSingleFlight
import com.buaa.schedule.domain.session.SessionRetainGate
import com.buaa.schedule.domain.session.SessionRetainVerdict
import com.buaa.schedule.domain.url.EduDocument
import com.buaa.schedule.domain.url.SessionDomain
import com.buaa.schedule.domain.url.eduDocumentVerdict
import com.buaa.schedule.domain.url.sessionDomainVerdict
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.lang.ref.WeakReference

/**
 * 北航教务登录会话（应用级单例）。
 *
 * 登录成功后 WebView **不销毁**：转挂到一个仍在窗口内的 1×1 隐藏宿主，
 * byxt 的 storage 凭证（API 鉴权实际依赖）随 WebView 存活，后续刷新课表
 * 复用同一实例即可静默重新拉取，无需再次登录。
 *
 * ⚠️ 不能只是把 WebView 从视图树 detach：脱离窗口的 WebView 其 JS 不再被调度，
 * `evaluateJavascript` 永不回调 —— 实测表现为复用会话刷新课表一路等到页面内 fetch 的超时上限
 * （现值：`data/import/BuaaInPageFetcher` 裸 `:55` 的 `TIMEOUT_MS = 12_000L`，十二秒一枚；台账里那句
 * 「恒定 25s 超时」是 25s 那一档**旧真机读数**，T378② 按盘面改成现值，逐周轮询下的总等待另算），
 * 且失败路径会误清掉仍然有效的登录会话。
 *
 * Cookie 由 CookieManager 持久化（含 SSO 全局会话 TGT），冷启动后首次进入
 * 导入页时 SSO 自动跳过登录表单直达 byxt。
 */
object BuaaWebSession {

    private const val TAG = "BuaaWebSession"
    private const val PREFS_NAME = "buaa_session"
    private const val PREF_RETAINED = "retained"

    /** 会话恢复尝试的最小间隔：失败后 60s 内不再重试，避免 onStart 抖动把 WebView 堆爆 */
    private const val RESTORE_RETRY_INTERVAL_MS = 60_000L

    /**
     * 单调钟刻度上「本进程从没恢复失败过」的哨兵 —— 引用内核那一枚，**不在这里另写一个负数**
     * （两枚调用点各自的 `-1L` 就是两套「从没失败过」，台账 #496 钉的正是这一形）。
     */
    private const val NEVER_FAILED_MILLIS = RestoreBackoffSentinel.NEVER_FAILED_MILLIS

    /** 需要持久化的鉴权域：byxt 自身 + 统一身份认证的 SSO 全局会话 */
    private val COOKIE_HOSTS = listOf("byxt.buaa.edu.cn", "sso.buaa.edu.cn")

    /**
     * 教务站点 origin：会话判定、页面内接口（[BuaaInPageFetcher]）、登录回跳地址
     * 共用这一份，避免各处字面量漂移（R5 F-39）。
     *
     * T302①：字面量本体搬到了 `domain/url/EduWebOrigin.kt`（唯一真源），这里只留一枚别名 ——
     * 「是不是教务 origin」那句话由 [com.buaa.schedule.domain.url.eduDocumentVerdict] 说，
     * 本文件不再拿它做前缀比较（在册 #515：`:354`/`:605` 带斜杠、`:120` 不带斜杠，两把尺各自说谎）。
     */
    const val BYXT_ORIGIN = com.buaa.schedule.domain.url.EduWebOrigin.ORIGIN

    private const val SESSION_ENTRY_URL = "$BYXT_ORIGIN/jwapp/sys/homeapp/index.do"

    @Volatile private var appContext: Context? = null

    /** setAcceptCookie 只需配一次；由 [cookieManager] 维护 */
    @Volatile private var cookiesConfigured = false

    /**
     * 会话 WebView / 隐藏宿主的实际存储。
     *
     * 必须是**弱**引用：`View.mParent` 会从宿主一路指到承载它的 Activity 窗口，
     * 单例强持有等于把一扇已经销毁的窗口（含 DecorView 与 Activity Context）
     * 挂到进程结束 —— lint 的三条 `StaticFieldLeak` 报的就是这条链。
     * 窗口还活着时视图树自己就强持有这两个对象，弱引用不会让会话无缘无故丢掉；
     * 真的走到内存回收，说明已经没有活着的窗口在跑 JS，此时 Cookie 也已落盘
     * （[BuaaCookieStore]），下次 [restore] 会重新装配。
     */
    @Volatile private var sessionWebViewRef: WeakReference<WebView>? = null
    @Volatile private var hiddenHostRef: WeakReference<ViewGroup>? = null

    /** 当前/最近一次已知的主文档 URL；让 [hasSession] 不必跨线程问 WebView */
    @Volatile private var lastUrl: String? = null

    private var sessionWebView: WebView?
        get() = sessionWebViewRef?.get()
        set(value) {
            sessionWebViewRef = value?.let { WeakReference(it) }
        }

    private var hiddenHost: ViewGroup?
        get() = hiddenHostRef?.get()
        set(value) {
            hiddenHostRef = value?.let { WeakReference(it) }
        }

    /** 应用是否处于前台；false 时保留会话但暂停 WebView 渲染与 JS 定时器以省电 */
    @Volatile private var appForeground: Boolean = true

    /**
     * 「刚刚有人上缴了一个可用会话」的**边沿**信号（T60）。
     *
     * 为什么需要它：`fetchTeachingSchedule` / `refreshSchedule` 都要求同一个进程里活着的
     * 教务 WebView（[sessionWebView] + [hasSession] 两道都过），而这两样只在 [retain] 里被赋值。
     * 首页那条 `LaunchedEffect(Unit)` 的补抓恰恰跑在这之前 —— 冷启动时它跑完，会话才被
     * 恢复出来（或直接根本没有会话），于是那一趟唯一的机会空耗，此后没有任何东西再敲一次门。
     * 本信号就是那第二声敲门：**会话刚可能出现的那一刻**。
     *
     * 只发边沿、不发状态：`hasSession()` 仍然是唯一真相，这里不复刻第二份判据
     * （一份状态若与它不同步，就会有人照着错的那半写代码）。谁收到自己去问 `hasSession()`。
     *
     * 发射点只有 [retain] 一处（且只在它**收下**实例那一档发）：登录页六处上缴调用点与冷启动 Cookie
     * 恢复成功后的上缴都汇到那里，所以不需要在每个入口各补一行 —— 枚数是 T378② 在基点 `f928dea`
     * 上重数的（`grep -c "BuaaWebSession.retain(" ` 现值 6；这句原本写「五处」，对不上盘面）。
     * 上缴**不等于收下**：非教务出身的候选由 `domain/session/SessionRetainGate` 判成拒收档，
     * 拒收档一声门都不敲（否则订阅者回头问 `hasSession()` 拿到 false，这一轮白跑）。
     */
    private val _sessionRetained = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 见 [_sessionRetained]。订阅方（ScheduleViewModel）在 viewModelScope 里收，随 ViewModel 一起走。 */
    val sessionRetained: SharedFlow<Unit> = _sessionRetained.asSharedFlow()

    /**
     * 恢复流程的单飞标志。`onStart` 每次回到前台都会调 [restore]，
     * 而 `sessionWebView` 只在 `onPageFinished` 里才被赋值 ——
     * 加载慢或页面被重定向到 SSO 时，多次 `onStart` 会各建一个 WebView，
     * 每个都挂着 SSR 页面，最终 OOM。
     *
     * T378②：**这枚旗的处置全仓只有三个收口，而且每一处都同时处置它那道超时闸** ——
     * ① [restore] 里那一枚 30 秒超时闸走到 [abandonRestore]（收旗 ＋ 拆闸 ＋ 记下退避读数）；
     * ② [clear]（退出登录，收旗 ＋ 拆闸）；
     * ③ [retain] 收下实例那两档（T378② 之前这里是零处 —— 它只 `cancelRestoreGuard()` 拆闸，
     *   于是闸没了、旗还留着 true，[restore] 此后恒在 `if (restoreInFlight) return` 那一档早退）。
     * 其余出口（[releaseForMemory]）**不许**碰它：它在 `sessionWebView == null` 时就整段早退，
     * 而那正是恢复在飞时的状态 —— 它插不进这一族，硬加一手只会与超时闸抢同一枚旗。
     */
    @Volatile private var restoreInFlight: Boolean = false

    /**
     * 上次恢复失败时的**单调钟**读数（`SystemClock.elapsedRealtime` 口径），用于 [restore] 的退避。
     *
     * T378②换尺（台账 #495／#496 那一族的第四枚落点）。改前那两行逐字留在这里做取证
     * （源码形状守卫按「代码里 0 处、含注释恰好 1 处」双向复算）：
     *
     * ```
     * @Volatile private var lastRestoreFailureAt: Long = 0L
     * if (now - lastRestoreFailureAt < RESTORE_RETRY_INTERVAL_MS) return
     * ```
     *
     * 两头各一枚病：拿的是墙上钟（用户改表／时区校正往回拨 ⇒ 差值是**负数** ⇒ 恒早退，
     * 而这一档全函数里唯一一句话不说），且初始值与时间戳共用同一格（换成单调钟之后照旧拿 `0L`
     * 就撞上 `elapsedRealtime()` 开机那刻从 0 起走：开机后一分钟内的第一次恢复被念成「刚刚失败过」）。
     * 所以「从没失败过」单立具名哨兵 [NEVER_FAILED_MILLIS]（`-1L`，单调钟取不出负数），
     * 窗口判据与它**不共用刻度**；倒退与出窗各是一档，本体在 `domain/session/RestoreBackoffWindowGate`。
     */
    @Volatile private var lastRestoreFailureAtElapsedRealtime: Long = NEVER_FAILED_MILLIS

    /**
     * 恢复流程的加载超时：页面既不 finish 也不报错（连接挂起、SSO 静默重定向）时，
     * 单飞标志不能永久停在 true —— 那样本进程再也不会尝试恢复会话。
     */
    private const val RESTORE_TIMEOUT_MS = 30_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var restoreGuard: Runnable? = null

    /**
     * 当前前台 Activity 的**弱引用**，由 `MainActivity.onStart` 刷新。
     *
     * [restore] 的 WebViewClient 捕获的是"发起恢复的那个 Activity"，页面加载慢或被
     * SSO 重定向时 `onPageFinished` 往往落在转屏之后。已销毁 Activity 的
     * `android.R.id.content` 仍然非 null，只判空会把宿主挂回旧窗口：
     * WebView 名义上 attached，JS 却不再被调度（静默刷新恒定超时），
     * 而新 Activity 的 reattachTo 早就跑完了，救不回来。
     * 弱引用：只用来找活着的窗口，不因此持有 Activity。
     */
    @Volatile private var currentActivity: WeakReference<android.app.Activity>? = null

    /** 由 Activity.onStart 调用：刷新隐藏宿主的候选窗口 */
    fun setCurrentActivity(activity: android.app.Activity) {
        currentActivity = WeakReference(activity)
    }

    /** 首个尚未 finishing/destroyed 的候选窗口；全部已死则返回 null */
    private fun liveActivity(preferred: android.app.Activity?): android.app.Activity? {
        val candidates = listOfNotNull(preferred, currentActivity?.get())
        return candidates.firstOrNull { !it.isFinishing && !it.isDestroyed }
    }

    /**
     * 随 Activity 前后台切换调用：后台暂停隐藏会话 WebView（onPause + pauseTimers），
     * 前台恢复。被暂停后 `evaluateJavascript` 不再调度，因此任何需要页面上下文
     * 的抓取都依赖前台恢复；应用后台时不会有页面内 fetch 发生。
     */
    fun setAppForeground(fg: Boolean) {
        if (appForeground == fg) return
        appForeground = fg
        Handler(Looper.getMainLooper()).post { applyTimerGate(sessionWebView) }
    }

    /**
     * 前后台定时器闸门：后台挂起、前台恢复。
     *
     * ⚠️ `pauseTimers()` / `resumeTimers()` 挂起的是**整个进程**里所有 WebView 的
     * 定时器（官方口径），但在 SDK 36 的桩里它们是实例方法 —— 只能借某一个
     * WebView 实例调得到。于是"手里有实例才调得到"这条约束本身成了坑：
     * 退到后台（挂起生效）→ 内存压力销毁唯一的会话 WebView → 回前台时
     * [sessionWebView] 已是 null，早退处再没人能 `resumeTimers()` ——
     * 进程的定时器就永久停在挂起态，之后新建的登录页连 `onPageFinished`
     * 都不回调，页面内 fetch 只能一路静默超时。用户看到的是"身份认证登录后
     * 卡在打开导入预览界面"，而且没有任何报错。
     *
     * 所以不变式做在两个时机：状态翻转时用手上的实例施加；实例**生**（见
     * [createSessionWebView] 与登录页的 WebView）和**灭**（见 [releaseForMemory]、
     * [abandonRestore]）之前各调一次 [alignTimersWithForeground] 兜住没有实例的空窗。
     * 必须在主线程调用。
     */
    private fun applyTimerGate(web: WebView?) {
        val fg = appForeground
        if (web == null) return
        runCatching { if (fg) web.resumeTimers() else web.pauseTimers() }
            .onFailure { Log.w(TAG, "切换 WebView 定时器失败 fg=$fg", it) }
        runCatching { if (fg) web.onResume() else web.onPause() }
    }

    /**
     * 借一个具体实例把进程定时器对齐到"当前在前台"。
     *
     * 新建的 WebView 会**继承**进程现有的挂起态 —— 一出生 JS 就不跑，
     * 所以每个创建点都要在配置完 settings 之后调一次；销毁实例之前同样调一次，
     * 别把挂起态留给下一个实例。后台时不动手：那时挂起本来就是对的。
     */
    fun alignTimersWithForeground(web: WebView) {
        if (!appForeground) return
        runCatching { web.resumeTimers() }
            .onFailure { Log.w(TAG, "对齐 WebView 定时器失败", it) }
    }

    /** 应用启动时调用一次（任意线程）；只做 Context 寄存，不碰 WebView provider */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
    }

    /**
     * CookieManager 的统一入口：第一次真正要用时才去拉 WebView provider。
     *
     * `CookieManager.getInstance()` 并不只是拿个对象——它要求系统装载并绑定
     * `android.webkit` provider，是冷启动主线程上排得上号的开销，而绝大多数启动
     * 从头到尾不会碰教务导入。所以 [init] 只寄存 Context，这条链留到
     * 登录页建 WebView / 落盘注回 Cookie 时才发动。
     */
    private fun cookieManager(): CookieManager {
        val cm = CookieManager.getInstance()
        if (!cookiesConfigured) {
            cookiesConfigured = true
            cm.setAcceptCookie(true)
        }
        return cm
    }

    /**
     * 登录成功的 WebView 上缴会话：转挂到隐藏宿主（不 destroy），
     * 之后由 [refreshSchedule] 复用。
     *
     * 必须在主线程调用（会变更视图树）。
     *
     * **三问不在这里判**（T378②）：收不收这一枚实例、会话槽里旧的那一枚拆不拆、`restoreInFlight`
     * 单飞旗与它那道超时闸归谁收 —— 判据本体在 `domain/session/SessionRetainGate`（纯 JVM），
     * 这里只按它给的档分派。为什么非得收口在一处：登录页六枚上缴调用点里只有一枚长在 origin
     * 判据分支里（其余五枚含崩溃重建后那枚 URL 还是 `about:blank` 的新实例），改前它们都能
     * 无条件换引用、无条件写 `lastUrl`、并把手里那枚**真的登录着**的实例拆掉。
     *
     * 改前那一手逐字留在这里做取证（源码形状守卫按「代码里 0 处、含注释恰好 1 处」双向复算）：
     * `if (old != null && old !== webView) {` ＋ `runCatching { old.destroy() }` —— 挂着树直接拆，
     * 而下面那一档 `cancelRestoreGuard()` 是全仓唯一还能收 `restoreInFlight` 的闸，拆了闸却不收旗。
     */
    fun retain(webView: WebView, source: RetainSource, activity: android.app.Activity? = null) {
        liveActivity(activity)?.let { currentActivity = WeakReference(it) }
        val previous = sessionWebView
        when (
            val verdict = SessionRetainGate.decide(
                candidateUrl = webView.url,
                source = source,
                hasPreviousInstance = previous != null,
                previousIsCandidate = previous === webView,
                restoreInFlight = restoreInFlight,
            )
        ) {
            is SessionRetainVerdict.Accepted -> {
                sessionWebView = webView
                lastUrl = webView.url
                val attached = moveToHiddenHost(webView, activity)
                if (!attached) {
                    Log.w(TAG, "未找到可挂载的窗口宿主，会话 JS 可能被冻结（复用刷新会超时）")
                }
                when (verdict.previousWebView) {
                    // 与另外三枚出口同一次序：先从视图树摘下来，再 stopLoading ＋ destroy。
                    // 改前这是全仓唯一一枚挂在树上就直接 destroy 的出口。
                    PreviousWebView.RemoveFromTreeThenDestroy -> previous?.let { old ->
                        Log.i(TAG, "替换旧会话 WebView（先摘树再拆）")
                        runCatching {
                            (old.parent as? ViewGroup)?.removeView(old)
                            old.stopLoading()
                            old.destroy()
                        }
                    }

                    // 手里没有旧的、或那枚就是候选本身（再往前一档是「本档拒收」，落不到这里）：
                    // 后者拆掉的就是刚收下的会话，一手都不许动
                    PreviousWebView.NoPreviousInstance, PreviousWebView.CandidateIsThePrevious -> Unit
                    PreviousWebView.KeepPreviousAsSession -> Unit
                }
                when (verdict.singleFlight) {
                    RestoreSingleFlight.CollectFlagAndCancelGuard -> {
                        restoreInFlight = false
                        cancelRestoreGuard()
                    }

                    RestoreSingleFlight.CollectFlagKeepGuardArmed -> {
                        // 会话槽被别的来路占下了：旗要收（否则 restore 恒早退），闸不许拆
                        // （还在飞的那一枚 WebView 只剩这道闸会替它拆）
                        restoreInFlight = false
                        Log.i(TAG, "单飞旗收掉，超时闸留着替还在飞的那一枚收尾")
                    }

                    RestoreSingleFlight.NothingInFlight, RestoreSingleFlight.LeaveToRestoreChain -> Unit
                }
                Log.i(TAG, "会话已保留（来源=${verdict.source} attached=$attached url=${redactUrl(webView.url)}）")
                // 若保留时应用已在后台（理论上不会从 UI 路径发生，但兜底）：闸门会同时
                // 处理进程级定时器与这个 WebView 的 onPause
                applyTimerGate(webView)
                // 会话到这里是真的可用了（sessionWebView 已赋值、lastUrl 在 byxt 域）：敲一声门。
                // 发在函数末尾是有意的 —— 早一步发，订阅者回头去问 hasSession() 可能还是 false，
                // 它这一轮就白跑了（为什么只发边沿不发状态见 [_sessionRetained]）。
                _sessionRetained.tryEmit(Unit)
            }

            is SessionRetainVerdict.Refused -> {
                // 三样一概不碰：会话槽、lastUrl（= hasSession 的唯一真相）、隐藏宿主；旧实例一枚也不拆。
                // 五档教务判据逐枚念出来，免得「没收下」又变成一句不说的静默支路。
                Log.w(
                    TAG,
                    "拒收上缴的 WebView：文档不是教务出身（档=${verdict.candidateVerdict} " +
                        "url=${redactUrl(verdict.candidateUrl)}）" +
                        "旧实例=${verdict.previousWebView} 旗与闸=${verdict.singleFlight}",
                )
            }
        }
        // 两档共同的两手 —— 讲的是**凭证**，不是「会话槽归谁」：CookieManager 是进程级的，
        // 与这一枚实例进不进会话槽是两件事。改前 retain 无论出身都做这两手，本卡不许把登录态
        // 落盘跟着「拒收实例」一起修没（那才是真正会让用户重走一遍 SSO 的那一刀）。
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit { putBoolean(PREF_RETAINED, true) }
        // 立刻把会话 Cookie 加密落盘：CookieManager 不会持久化"会话 Cookie"，
        // 不落盘的话用户划掉应用后登录态就没了。
        persistCookies()
    }

    /**
     * 把当前 WebView 的会话 Cookie 加密落盘 + flush。
     *
     * 只存鉴权相关的两个域。`CookieManager.flush()` 顺手也调用一次：
     * 带 Max-Age 的 Cookie 由系统自己持久化，flush 能确保它立刻写盘。
     */
    private fun persistCookies() {
        val context = appContext ?: return
        runCatching {
            val cm = cookieManager()
            val payload = COOKIE_HOSTS.mapNotNull { host ->
                cm.getCookie("https://$host/")?.takeIf { it.isNotBlank() }?.let { "$host\t$it" }
            }.joinToString("\n")
            if (payload.isNotBlank()) BuaaCookieStore.save(context, payload)
            cm.flush()
        }.onFailure { Log.w(TAG, "会话 Cookie 落盘失败", it) }
    }

    /**
     * 把上次落盘的 Cookie 注回 CookieManager，再做一次 flush。
     * 冷启动时若不做这一步，byxt 会被重定向到 SSO 登录页。
     */
    private fun injectPersistedCookies(context: Context) {
        val payload = BuaaCookieStore.load(context) ?: return
        runCatching {
            val cm = cookieManager()
            payload.lineSequence().forEach { line ->
                val host = line.substringBefore('\t').trim()
                val header = line.substringAfter('\t', "").trim()
                if (host.isNotBlank() && header.isNotBlank()) {
                    // getCookie 返回的是 "a=b; c=d"，要逐条 setCookie（一次只能设一条）
                    header.split(";").forEach { pair ->
                        val kv = pair.trim()
                        if (kv.contains("=")) {
                            cm.setCookie("https://$host/", "$kv; Path=/")
                        }
                    }
                }
            }
            cm.flush()
            Log.i(TAG, "已恢复持久化的会话 Cookie")
        }.onFailure { Log.w(TAG, "恢复会话 Cookie 失败", it) }
    }

    /**
     * 把 WebView 转挂到 Activity 内容视图里的 1×1 隐藏宿主。
     * @return WebView 是否已 attach 到窗口（false 表示 JS 可能不会执行）
     */
    private fun moveToHiddenHost(webView: WebView, activity: android.app.Activity?): Boolean {
        // 已 finish 的 Activity 依然能查到 android.R.id.content，必须显式拒绝，
        // 否则宿主挂回旧窗口：isAttachedToWindow 为 true，JS 却再也不被调度。
        val window = liveActivity(activity)
        val content = window?.findViewById<ViewGroup>(android.R.id.content)
        if (content == null) {
            // 兜底：至少保证不残留在即将销毁的旧视图树里
            (webView.parent as? ViewGroup)?.removeView(webView)
            return false
        }
        // 宿主必须用 applicationContext 创建：用 Activity 的话这个单例对象里
        // 会一直握着一个已经 finish 的 Activity，转屏/退出后它整个窗口都释放不掉。
        // FrameLayout 只是容器，用应用 Context 创建完全合法，且随后 addView 进
        // Activity 的 content 里依然能正常 attach 到窗口。
        val hostContext = appContext ?: window.applicationContext
        val host = hiddenHost ?: android.widget.FrameLayout(hostContext).apply {
            alpha = 0f
            isClickable = false
            isFocusable = false
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }.also { hiddenHost = it }

        // Activity 重建后 host 可能还挂在旧 content 上，需要迁到新 content
        if (host.parent !== content) {
            (host.parent as? ViewGroup)?.removeView(host)
            runCatching { content.addView(host, android.widget.FrameLayout.LayoutParams(1, 1)) }
        }
        (webView.parent as? ViewGroup)?.removeView(webView)
        if (webView.parent == null) {
            runCatching { host.addView(webView, android.widget.FrameLayout.LayoutParams(1, 1)) }
        }
        return webView.isAttachedToWindow
    }

    /**
     * 是否有可复用的活跃会话。
     *
     * 读的是主线程维护的 [lastUrl] 缓存，不碰 WebView：这个方法会在
     * `withImportLock`（Dispatchers.Default）里被调到，跨线程读 `getUrl()`
     * 属于未定义行为（R5 F-36）。
     *
     * T302③：判据本身在 `domain/url/EduWebOrigin.kt`（在册 #515）。改前这一行是
     * `lastUrl?.startsWith("$BYXT_ORIGIN/")` —— 那枚尾斜杠替它挡住了后缀伪装，却也把三枚合法格
     * （无尾斜杠、`:443`、大小写混排）判成"没有会话"。现在这两档都由内核逐段裁定，本行只问一次、
     * 不再自己拿 origin 比前缀（守卫第①格钉着那一形全树 0 处）。
     */
    fun hasSession(): Boolean = eduDocumentVerdict(lastUrl) == EduDocument.OnEduOrigin

    /** 拉取学年学期列表（复用保留的 byxt WebView 页面上下文） */
    suspend fun fetchTermList(): List<BuaaInPageFetcher.TermOption> {
        val web = sessionWebView ?: return emptyList()
        if (!hasSession()) return emptyList()
        return BuaaInPageFetcher.fetchTermList(web)
    }

    /**
     * 抓取「学习日程」月历（JJR 节假日 / SKKC 有课日），用于课表上的假期标注。
     *
     * @return month → 原始响应；无会话或全部失败时返回 null
     */
    suspend fun fetchTeachingSchedule(
        months: List<java.time.YearMonth>,
    ): Map<java.time.YearMonth, String>? {
        val web = sessionWebView ?: return null
        if (!hasSession()) return null
        val out = LinkedHashMap<java.time.YearMonth, String>()
        months.forEach { month ->
            BuaaInPageFetcher.fetchTeachingScheduleMonth(web, month.atDay(1))?.let { out[month] = it }
        }
        return out.ifEmpty { null }
    }

    /**
     * 静默刷新课表：复用保留的登录 WebView 页面上下文 fetch。
     * 无会话 / 会话失效返回 null（调用方引导重新登录）。
     *
     * @param existingStartDate 库里已有的开学日期。抓取未取得第一周周一时沿用它，
     *   两者都没有则判定本次刷新失败——写一个猜出来的日期会让整张课表的日期与
     *   周次编号整体偏移（课次日期 = startDate.plusWeeks(w-1).plusDays(dow-1)），
     *   而调用方紧接着就删旧插新，没有回滚路径。
     */
    suspend fun refreshSchedule(
        existingStartDate: java.time.LocalDate? = null,
        onProgress: (week: Int, total: Int) -> Unit = { _, _ -> },
    ): kotlin.Result<SemesterCourses>? {
        val web = sessionWebView ?: return null
        if (!hasSession()) return null
        val fetcher = BuaaInPageFetcher
        val terms = fetcher.fetchTermList(web)
        val selectedTerm = terms.firstOrNull { it.selected }?.code ?: terms.firstOrNull()?.code
        if (selectedTerm == null) return Result.failure(IllegalStateException("无法获取学期列表"))
        val info = fetcher.fetchSemesterInfo(web, selectedTerm)
        val startDate = info.firstWeekMonday
            ?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            ?.let(com.buaa.schedule.domain.schedule.WeekCalculator::mondayOf)
            ?: existingStartDate
            ?: return Result.failure(IllegalStateException("未取得学期第一周周一，未改动课表"))
        val outcome = fetcher.fetchSemesterCourses(
            webView = web, termCode = selectedTerm, totalWeeks = info.totalWeeks, onProgress = onProgress,
        )
        val semester = com.buaa.schedule.domain.model.Semester(
            termCode = selectedTerm,
            termName = terms.firstOrNull { it.code == selectedTerm }?.name ?: selectedTerm,
            startDate = startDate.toString(),
            totalWeeks = info.totalWeeks,
        )
        val domain = BuaaScheduleParser.parseArrangedList(outcome.courses, selectedTerm, info.totalWeeks)
        val warnings = buildList {
            if (outcome.failedWeeks.isNotEmpty()) {
                add(
                    "${outcome.failedWeeks.size} 个教学周抓取失败（" +
                        outcome.failedWeeks.joinToString("、") + "），本次结果不完整"
                )
            }
        }
        return Result.success(
            SemesterCourses(semester, domain, warnings, outcome.failedWeeks),
        )
    }

    /** 丢弃当前会话（会话失效 / 用户退出登录） */
    fun clear() {
        // 退出登录可能正好赶上一次恢复在跑：单飞标志不清零的话本进程再也不尝试恢复
        restoreInFlight = false
        cancelRestoreGuard()
        val web = sessionWebView
        sessionWebView = null
        lastUrl = null
        val host = hiddenHost
        hiddenHost = null
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (web != null) {
                (web.parent as? ViewGroup)?.removeView(web)
                runCatching {
                    web.stopLoading()
                    web.destroy()
                }
            }
            if (host != null) (host.parent as? ViewGroup)?.removeView(host)
        }
        runCatching {
            val cm = cookieManager()
            cm.removeAllCookies(null)
            cm.flush()
        }
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit { putBoolean(PREF_RETAINED, false) }
        // 必须同时清掉落盘的 Cookie，否则下次冷启动又会注回去，用户会觉得"退不掉"
        appContext?.let { BuaaCookieStore.clear(it) }
        Log.i(TAG, "会话已清除")
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun createSessionWebView(context: Context): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // 会话 WebView 只加载北航教务页面，不需要任何本地/内容提供器访问面
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        // 注意：不要再动 settings.databaseEnabled（API 33+ 已废弃、无效果）。
        // WebSQL 早已被系统移除，留着这行只会产生 deprecation 警告。
        // 安全浏览让 WebView 对已知恶意页做拦截（startSafeBrowsing 需 API 27+）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            runCatching { WebView.startSafeBrowsing(context, null) }
        }
        // 第三方 Cookie 一律不放行：SSO 跳转靠的是顶层导航 + 第一方 Cookie，
        // 放开只会让页面里任意第三方 iframe 带上会话 Cookie（行为是对的，
        // 之前的注释写反了 —— R5 F-48）
        cookieManager().setAcceptThirdPartyCookies(this, false)
        // 新建的 WebView 会继承进程遗留的定时器挂起态（上次后台 pause 之后没有
        // 实例能 resume）—— 不清掉的话这个页面一出生 JS 就是冻结的。
        alignTimersWithForeground(this)
        // 这个 WebView 的 Context 是 application：默认的 JS 对话框实现会拿它去建
        // AlertDialog，没有窗口令牌 → BadTokenException 直接崩进程。页面弹 alert
        // （教务系统偶尔这么干）时一律取消，让脚本继续跑。
        webChromeClient = object : android.webkit.WebChromeClient() {
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
        }
    }

    /** 撤掉尚未触发的恢复超时；恢复成功或失败都要调，免得超时任务回头去拆下一个实例 */
    private fun cancelRestoreGuard() {
        restoreGuard?.let { mainHandler.removeCallbacks(it) }
        restoreGuard = null
    }

    /**
     * 系统施加内存压力时释放那个只为「静默刷新」存在的隐藏会话 WebView。
     *
     * 值得放掉的理由：这个 WebView 挂着一个已加载的 byxt 页面，Chromium 侧的渲染堆
     * 是整个应用里最大的一块单体驻留内存，而它只在用户点「同步教务」时才真正干活。
     * Cookie 早已由 [BuaaCookieStore] 加密落盘，下次回前台 [restore] 会照原样重建，
     * 用户唯一能感知的是这期间发起的一次静默刷新要等页面重新装好 —— 而内存吃紧时
     * 本来就不该抢着做后台刷新。
     *
     * 线程口径同 [retain]：WebView 的 destroy 必须回到创建它的主线程。
     */
    fun releaseForMemory() {
        Handler(Looper.getMainLooper()).post {
            val web = sessionWebView ?: return@post
            Log.i(TAG, "内存压力：释放隐藏会话 WebView，下次前台按 Cookie 重建")
            // 先摘引用再 destroy：WeakReference 本身挡不住视图树那侧的强引用，
            // 真正让 WebView 可回收的是下面的 removeView + destroy。
            sessionWebView = null
            lastUrl = null
            // 拆之前先把进程定时器对齐到当前状态：这个实例很可能正是"当初被 pause
            // 的那一个"，destroy 之后就没有任何实例能替我们 resumeTimers() 了。
            alignTimersWithForeground(web)
            runCatching {
                (web.parent as? ViewGroup)?.removeView(web)
                web.stopLoading()
                web.destroy()
            }.onFailure { Log.w(TAG, "释放会话 WebView 失败", it) }
            hiddenHost = null
        }
    }

    /** 加载失败/被踢到站外：把这次恢复用的 WebView 彻底拆掉，避免半死实例继续吃内存 */
    private fun abandonRestore(web: WebView, reason: String) {
        Log.w(TAG, "放弃会话恢复：$reason")
        cancelRestoreGuard()
        restoreInFlight = false
        lastRestoreFailureAtElapsedRealtime = SystemClock.elapsedRealtime()
        // 同 releaseForMemory：destroy 之前用这个实例清掉进程挂起态
        alignTimersWithForeground(web)
        runCatching {
            (web.parent as? ViewGroup)?.removeView(web)
            web.stopLoading()
            web.destroy()
        }
        // 只清掉指向它的引用，不动已落盘的 Cookie（Cookie 还有用，下次仍可尝试）
        if (sessionWebView === web) {
            sessionWebView = null
            lastUrl = null
        }
    }

    /**
     * 尝试在进程重启后恢复 byxt 会话：用持久化 Cookie 重新加载 byxt 页面，
     * 等待页面就绪后重新上缴为可复用会话。
     *
     * 三个必须的守卫（此前都没有，导致回到前台时反复新建 WebView + 失败实例永不回收）：
     * 1. [restoreInFlight] 单飞 —— 一次只允许一个恢复流程在跑；
     * 2. 失败退避 —— 加载失败后 60s 内不再重试；判据本体在
     *    `domain/session/RestoreBackoffWindowGate`（纯 JVM、单调钟、四档），这里只取钟与档位（T378②）；
     * 3. `onReceivedError`/`onReceivedHttpError` 清理 —— 失败实例立刻 destroy。
     */
    fun restore(activity: android.app.Activity) {
        if (hasSession() || sessionWebView != null) return
        if (restoreInFlight) return
        // T378②：退避读的是单调钟（elapsedRealtime），墙上钟被往回拨那一档由内核单独成档、偏向放行。
        // 改前这里是 `val now = System.currentTimeMillis()` ＋ 一枚不夹下限的差值比较 ——
        // 回拨之后差为负、恒早退，而这一档连一句日志都没有。
        val window = RestoreBackoffWindowGate.decide(
            nowElapsedRealtime = SystemClock.elapsedRealtime(),
            lastFailureAt = lastRestoreFailureAtElapsedRealtime,
            windowMillis = RESTORE_RETRY_INTERVAL_MS,
        )
        when (window) {
            is RestoreBackoffWindow.SuppressedWithinWindow -> {
                Log.i(
                    TAG,
                    "恢复退避中：距上次失败 ${window.elapsedSinceFailureMillis}ms < ${window.windowMillis}ms",
                )
                return
            }

            is RestoreBackoffWindow.GrantedClockInverted -> Log.w(
                TAG,
                "恢复退避读到读数倒退（now=${window.nowElapsedRealtime} last=${window.lastFailureAt}）：" +
                    "递进来的不是单调钟，偏向放行并留痕",
            )

            is RestoreBackoffWindow.GrantedNeverFailed, is RestoreBackoffWindow.GrantedAfterWindow -> Unit
        }
        val appCtx = appContext ?: activity.applicationContext
        if (!appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(PREF_RETAINED, false)) return
        restoreInFlight = true
        setCurrentActivity(activity)
        // 先把上次加密落盘的会话 Cookie 注回去，否则加载 byxt 会被重定向到 SSO 登录页
        // （这就是"退出应用后登录状态没了"的直接原因）
        injectPersistedCookies(appCtx)
        // 用 applicationContext 创建：这个 WebView 会被单例长期持有，
        // 绑 Activity Context 就等于把整个 Activity 窗口泄漏到进程结束。
        val web = createSessionWebView(appCtx)
        // 页面既不 finish 也不报错（连接挂起）时，单飞标志会永久卡住：30s 后自行拆掉。
        // T378②把这一问从「旗还在不在飞」换成「会话槽里是不是它自己」—— 因为 [retain] 现在会在
        // 别的来路（登录页）收下会话时就把旗收掉：闸还举着正是为了拆掉这一枚没人认领的实例，
        // 判据若仍吃那枚旗，收旗就等于把闸举废（挂着的 WebView 反而再没人拆了）。
        // 改前那一问逐字留在这里做取证（形状守卫按「代码 0 处／含注释恰好 1 处」双向复算）：
        // if (restoreInFlight && sessionWebView == null) abandonRestore(web, "加载超时")
        val guard = Runnable {
            if (sessionWebView !== web) abandonRestore(web, "加载超时")
        }
        restoreGuard = guard
        mainHandler.postDelayed(guard, RESTORE_TIMEOUT_MS)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (view == null) return
                if (url != null) lastUrl = url
                // 两问两档（T302③，在册 #515）：先问「是不是教务 origin」，不认再问「是不是北航会话域」。
                // 改前这里是 `url?.startsWith("$BYXT_ORIGIN/")` ＋ `isAllowedSessionUrl(url)`（URI.host ＋
                // endsWith 后缀）两把尺子并存，前者误伤三枚合法格、后者在一枚裸空格 query 上把真教务文档
                // 判成外站。现在两问都只问 domain/url 那一枚内核。
                when (eduDocumentVerdict(url)) {
                    EduDocument.OnEduOrigin -> retain(view, RetainSource.CookieRestore, activity)

                    else -> when (sessionDomainVerdict(url)) {
                        SessionDomain.ForeignDomain, SessionDomain.Unparseable -> {
                            // 被重定向到站外（SSO 表单页以外的陌生域），或者连 host 都读不出来：视为会话不可用
                            abandonRestore(view, "重定向到非北航域: ${redactUrl(url)}")
                        }

                        SessionDomain.AllowedBuaaDomain -> {
                            // 仍在北航域、但停在 SSO 登录页：Cookie 已失效，需要用户重新登录。
                            // 此前这个分支两不靠 —— restoreInFlight 永久 true（本进程再也不尝试
                            // 恢复），这个隐藏的 WebView 也永不销毁。这个 WebView 用户碰不到
                            // （1×1、alpha=0、不收触摸），不可能在这里完成登录，直接拆掉退避。
                            abandonRestore(view, "会话已失效，需要重新登录: ${redactUrl(url)}")
                        }
                    }
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                // 只有主框架失败才判定恢复失败（子资源失败不影响会话建立）
                if (view != null && request?.isForMainFrame == true) {
                    abandonRestore(view, "加载失败: ${error?.errorCode}")
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?,
            ) {
                if (view != null && request?.isForMainFrame == true) {
                    abortIfSessionRestoreHttpError(view, errorResponse)
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: android.webkit.SslErrorHandler?,
                error: android.net.http.SslError?,
            ) {
                // 证书异常绝不放行（此前未覆写，走系统默认行为；显式拒绝更安全）
                handler?.cancel()
                if (view != null) abandonRestore(view, "SSL 校验失败")
            }

            private fun abortIfSessionRestoreHttpError(
                view: WebView,
                response: android.webkit.WebResourceResponse?,
            ) {
                val code = response?.statusCode ?: 0
                // 401/403 说明会话确实失效；5xx 是服务端问题，也按失败退避，
                // 免得每次回到前台都猛敲一遍教务。
                if (code == 401 || code == 403 || code >= 500) {
                    abandonRestore(view, "HTTP $code")
                }
            }
        }
        moveToHiddenHost(web, activity)
        web.loadUrl(SESSION_ENTRY_URL)
    }

    /**
     * Activity 重建（旋转 / 深色模式 / 分屏 / 字体变化）后调用：
     * 把已保留的会话 WebView 从旧 Activity 的 content 树迁到当前 Activity。
     *
     * `restore()` 在 `sessionWebView != null` 时会直接 return，因此不能靠恢复流程
     * 顺带迁移；若不显式重挂载，宿主会一直留在已经废弃的旧窗口上 ——
     * WebView 名义上 attached，实际不再被任何活动窗口调度
     * （evaluateJavascript 不回调，复用会话刷新课表会超时）。
     *
     * 必须在主线程调用（会变更视图树）。无会话时为 no-op。
     */
    fun reattachTo(activity: android.app.Activity) {
        setCurrentActivity(activity)
        val web = sessionWebView ?: return
        val attached = moveToHiddenHost(web, activity)
        lastUrl = web.url
        if (!attached) {
            Log.w(TAG, "会话 WebView 重挂载失败，可能仍不在窗口内")
        }
    }
}

/** 整学期抓取结果：学期 + 课程 + 供导入预览展示的警告 */
data class SemesterCourses(
    val semester: Semester,
    val courses: List<Course>,
    val warnings: List<String> = emptyList(),
    /**
     * 抓取失败的周次。非空表示结果**不完整**：此时绝不能走"先清空该学期再写入"
     * 的覆盖导入，否则没抓到的周次会被连带删掉（静默数据丢失）。
     */
    val failedWeeks: List<Int> = emptyList(),
) {
    /** 结果是否完整（可以安全覆盖既有课表） */
    val isComplete: Boolean get() = failedWeeks.isEmpty()
}
