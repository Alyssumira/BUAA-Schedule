package com.buaa.schedule.data.import

import android.content.Context
import android.util.Log
import android.webkit.WebView
import com.buaa.schedule.domain.importing.FetchJobCarrierPolicy
import com.buaa.schedule.domain.importing.Ignition
import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 登录页那一轮逐周抓取的**进程内 holder**（T336②，在册 #412）。
 *
 * ## 为什么是 holder 而不是组合
 * 病：逐周请求要跑几十秒（量尺 = `BuaaLoginScreen` 同段注释自己陈的那句），而那六枚在飞事实
 * 全长在组合上（`by remember { mutable… }`）⇒ 转屏（复算尺 `grep -c configChanges app/src/main/AndroidManifest.xml` → 0，
 * 必重建 Activity）⇒ 六枚归零 ⇒ `LaunchedEffect` 的键被清零 ⇒ **作业被取消**，进度条退回 0/N、结论句消失。
 * 判据在 `domain/importing/FetchJobCarrierPolicy`：在飞事实的**载体寿命必须 ≥ 作业寿命**，于是只有进程内
 * holder 放行 —— 快照那一档（甲档单独用）只保住数字、救不了作业，还会造出「屏上念着第 7/19 周、
 * 脚下从第 1 周重跑」那一档谎（`CarrierVerdict.DigitsLie`）。
 *
 * ## 本仓的现成机制（不是这一枚卡新造的）
 * - 作用域：`update/UpdateCheck.kt` 的 `downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`
 *   早就为同一枚病写过那句「Activity 重建（旋转、后台被回收）会取消 composition scope 的协程」；
 * - 读数与平台：`BuaaWebSession` 就是这一族 holders 的先例（会话 WebView 的合法载体是它，不是快照）。
 *
 * ## 买到什么、买不到什么（如实写）
 * 买到：作业不再被「组合销毁」这枚静默机制顺手杀掉；读数与作业同源（进度条与结论句不退回 0/N、不消失）；
 * 同一枚平台还在飞时**绝不二次点火**（`Ignition.AttachOnly`），点火与否由具名闸门裁定并可测可守。
 * **买不到**：抓取发在 WebView 的页面上下文里（byxt 凭证外带不出，见 `BuaaInPageFetcher`），而重建时旧组合的
 * `AndroidView.onRelease` 会把作业脚下那一枚 WebView `stopLoading + destroy`。这一枚 holder 于是在平台换枚后
 * 出 [Ignition.StartOnReplacedPlatform] 那一手：**在新平台上再点一次，进度数字显式归零**（不拿旧数字念新作业）。
 * 要让那一轮请求从第 N+1 周续跑，需要给 `BuaaInPageFetcher.fetchSemesterCourses` 一枚 `startWeek`／平台供给器 ——
 * 在本卡文件白名单外，登记成残账。反过来把 WebView 在 teardown 里 `retain` 进隐藏宿主是**陷阱**：
 * `moveToHiddenHost` 只认「当时那枚 liveActivity」的 `android.R.id.content`，而转屏路径上旧 Activity 正在
 * onDestroy、新 Activity 还没 onCreate ⇒ 挂进一具正要销毁的窗口，JS 冻结，每一枚请求只能各自等到超时，
 * 结局比换平台重跑更坏。
 */
object BuaaImportJob {

    /**
     * 在飞事实的读数本体：六枚名字与 [FetchJobCarrierPolicy.IN_FLIGHT_FACTS] 逐枚同源。
     *
     * @param fetchState 正在念的那一句进度文案；null = 这一屏没有要念的话
     * @param fetchWeek 逐周抓取的分子
     * @param fetchTotal 分母；> 0 才画得出确定性进度条
     * @param fetchStarted 有人要求抓过课表 —— 改前它是 `LaunchedEffect` 的键之一，那一形就是把作业交给组合清零
     * @param fetchCancelled 用户按过「取消导入」
     * @param importPrepared 结果已交给导入预览、正等页面跳转
     * @param interruptedByRebuild 这一屏上念的轮次**曾被组合销毁打断过**：屏上那句进度话要说实话
     *   （平台随旧组合一起 destroy 了，这一轮是在新平台上从头再取的），不许拿它冒充"续上了"
     */
    data class Progress(
        val fetchState: String? = null,
        val fetchWeek: Int = 0,
        val fetchTotal: Int = 0,
        val fetchStarted: Boolean = false,
        val fetchCancelled: Boolean = false,
        val importPrepared: Boolean = false,
        val interruptedByRebuild: Boolean = false,
    )

    private val _progress = MutableStateFlow(Progress())

    /** 屏幕只问这一枚（`collectAsState()`），不再自己 `remember` 那六件事 */
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /**
     * 页面出口：转屏后旧那一枚被摘掉、新那一枚挂上来 ⇒ 作业只朝**当前活着**的那一枚说话。
     *
     * 方法名逐字沿用页面原来直接调的那几枚（`showMessage` / `previewBuaaCourses` / `onBack` /
     * `onImportPrepared`）：作业体的文本因此一字不改地搬过来，四枚在册守卫（钉「闸门次序」「retain→清状态→
     * 报错→返回」「文案落点」）的针仍落在同一枚文件里。
     */
    interface Host {
        /** 页面所在窗口的 Context：`BuaaWebSession.retain` 要经它找 Activity 拿 content view */
        val pageContext: Context

        fun showMessage(message: String, isError: Boolean)

        suspend fun previewBuaaCourses(semester: Semester, courses: List<Course>, warnings: List<String>)

        fun onBack()

        fun onImportPrepared()
    }

    /**
     * 作业本体的类型见文件末尾的 `BuaaImportTask`（typealias 只能长在顶层）：
     * 由页面层交进来（`BuaaLoginScreen` 里那一枚 private 顶层函数），
     * 但**跑在本 holder 的 [jobScope] 上** —— 这一句就是本卡的全部机制。
     */

    /** 在飞事实的可写视图：setter 每写一格就发一版新读数 ⇒ 读写两头同源 */
    class ProgressWriter internal constructor(private val flow: MutableStateFlow<Progress>) {
        var fetchState: String?
            get() = flow.value.fetchState
            set(value) {
                flow.update { it.copy(fetchState = value) }
            }
        var fetchWeek: Int
            get() = flow.value.fetchWeek
            set(value) {
                flow.update { it.copy(fetchWeek = value) }
            }
        var fetchTotal: Int
            get() = flow.value.fetchTotal
            set(value) {
                flow.update { it.copy(fetchTotal = value) }
            }
        var fetchStarted: Boolean
            get() = flow.value.fetchStarted
            set(value) {
                flow.update { it.copy(fetchStarted = value) }
            }
        var fetchCancelled: Boolean
            get() = flow.value.fetchCancelled
            set(value) {
                flow.update { it.copy(fetchCancelled = value) }
            }
        var importPrepared: Boolean
            get() = flow.value.importPrepared
            set(value) {
                flow.update { it.copy(importPrepared = value) }
            }
    }

    /** 抓取作用域：进程级，与组合寿命无关（`*WiringGuardTest` 把这一行逐字节钉住）*/
    private val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val writer = ProgressWriter(_progress)

    /**
     * 交进作业本体的那一枚**代理**出口：每一手都在调用那一刻去找当前活着的页面，
     * 页面已经不在时把结论攒进 [pendingPreview]，而不是握着一枚已经随组合作废的回调不放。
     */
    private val proxy: Host = object : Host {
        override val pageContext: Context
            get() = host?.pageContext ?: fallbackContext
                ?: throw IllegalStateException("BuaaImportJob 还没有页面 Context：点火之前必须先 attach()")

        override fun showMessage(message: String, isError: Boolean) {
            val live = host
            if (live == null) {
                Log.i(TAG, "页面已离开，这句没人念：$message")
                return
            }
            live.showMessage(message, isError)
        }

        override suspend fun previewBuaaCourses(semester: Semester, courses: List<Course>, warnings: List<String>) {
            val live = host
            if (live == null) {
                pendingPreview = Triple(semester, courses, warnings)
                Log.i(TAG, "抓取跑完时页面已离开：结果攒进 pendingPreview，等下一次 attach 交回")
                return
            }
            live.previewBuaaCourses(semester, courses, warnings)
        }

        override fun onBack() {
            val live = host
            if (live == null) Log.i(TAG, "页面已离开，返回这一手无人可交") else live.onBack()
        }

        override fun onImportPrepared() {
            val live = host
            if (live == null) Log.i(TAG, "页面已离开，导入预览的跳转等用户回来点那一颗按钮") else live.onImportPrepared()
        }
    }

    private var job: Job? = null
    private var task: BuaaImportTask? = null

    /** 作业脚下那一枚 WebView：**只在作业期间**握着，平台销毁/作业结束即清 ⇒ 不当第二枚会话持有者 */
    private var platform: WebView? = null

    /** 当前活着的页面出口（null = 页面不在）*/
    private var host: Host? = null

    /** 摘掉页面时留下的那一枚 Context 的 application 形态：只给 `retain` 兜底用，不握 Activity */
    private var fallbackContext: Context? = null

    /** 结果落在没有宿主的窗口里时攒着，等下一次 [attach] 交回去 */
    private var pendingPreview: Triple<Semester, List<Course>, List<String>>? = null

    /** 有没有作业在飞（点火闸门的输入之一）*/
    val isRunning: Boolean get() = job?.isActive == true

    /**
     * 页面每次组合挂上来一次：交回出口 + 作业本体，顺带试一次点火。
     *
     * 转屏路径上这一手排在 [attachPlatform] 之前（effect 在组合之后才跑，而新平台是 factory 在组合里造的）
     * ⇒ 这里通常点不着火（手上还没有平台），由 [attachPlatform] 那一手点火。
     */
    fun attach(nextHost: Host, nextTask: BuaaImportTask) {
        task = nextTask
        host = nextHost
        fallbackContext = runCatching { nextHost.pageContext.applicationContext }.getOrNull()
        deliverPending()
        platform?.let { tryIgnite(it) }
    }

    /** 页面离开组合：只摘**自己这一枚**出口（新页面可能已经先挂上来了，认身份再摘）*/
    fun detach(leaving: Host) {
        if (host === leaving) host = null
    }

    /** WebView 平台到位（factory 每造一枚报一枚）：按闸门决定「什么都不做 / 点火 / 换新平台再点一次」*/
    fun attachPlatform(web: WebView) {
        tryIgnite(web)
    }

    /**
     * 平台随组合销毁（`AndroidView.onRelease` 那一手，早于 destroy）：留下痕迹，
     * 别让闸门以为作业还有脚 —— 作业在飞而平台没了，那一轮的每一枚请求只会等到超时。
     */
    fun detachPlatform(web: WebView) {
        if (platform !== web) return
        platform = null
        if (!isRunning) return
        Log.i(TAG, "抓取平台已随组合销毁 ⇒ 旧那一轮作废，等新平台到位按闸门再点一次（进度显式归零）")
        job?.cancel()
        job = null
        _progress.update { it.copy(fetchWeek = 0, fetchTotal = 0, interruptedByRebuild = true) }
    }

    /** 登录成功（页面内抓取的平台就绪）：要求抓取 —— 就是今天那句 `if (!fetchStarted) fetchStarted = true` */
    fun askToFetch() {
        if (_progress.value.fetchStarted) return
        _progress.update { it.copy(fetchStarted = true, fetchCancelled = false, interruptedByRebuild = false) }
        platform?.let { tryIgnite(it) }
    }

    /** 「取消导入」：取消作业 + 落读数（今天那一手是三枚赋值，语义逐枚保持：旗标落、文案清、进度句不再念）*/
    fun cancel() {
        job?.cancel()
        job = null
        platform = null
        _progress.update {
            it.copy(fetchStarted = false, fetchCancelled = true, fetchState = null, interruptedByRebuild = false)
        }
    }

    /** 「重新获取」：取消档收回、重新要求抓 —— 要不要点火仍由闸门定 */
    fun askAgain() {
        _progress.update { it.copy(fetchCancelled = false, fetchStarted = true, interruptedByRebuild = false) }
        platform?.let { tryIgnite(it) }
    }

    /**
     * 点火裁定：全仓唯一一处调 [FetchJobCarrierPolicy.ignition]。
     *
     * [Ignition.AttachOnly] 那一手必须什么都不做 —— 几十秒已经在飞，重跑一遍就是把已抓到的周次作废
     * （卡面陈的「把键改成别的东西也要防的那一形」）。
     */
    private fun tryIgnite(web: WebView) {
        val action = FetchJobCarrierPolicy.ignition(
            askedToFetch = _progress.value.fetchStarted,
            jobRunning = isRunning,
            platformIsJobPlatform = platform === web,
        )
        when (action) {
            Ignition.Idle, Ignition.AttachOnly -> return
            Ignition.Start -> ignite(web, action)
            Ignition.StartOnReplacedPlatform -> {
                job?.cancel()
                job = null
                ignite(web, action)
            }
        }
    }

    private fun ignite(web: WebView, action: Ignition) {
        val body = task ?: return
        platform = web
        // 重新点火两档必须显式归零：屏上念的分数只能属于脚下这一轮（判据 progressResetsOnIgnition）
        if (FetchJobCarrierPolicy.progressResetsOnIgnition(action)) {
            _progress.update { it.copy(fetchWeek = 0, fetchTotal = 0, importPrepared = false) }
        }
        Log.i(TAG, "抓取点火：$action")
        job = jobScope.launch {
            try {
                proxy.body(web, writer)
            } finally {
                if (platform === web) platform = null
                job = null
            }
        }
    }

    /** 攒着的结果交回当前页面；交不回就留在原地等下一次 attach */
    private fun deliverPending() {
        val pending = pendingPreview ?: return
        val live = host ?: return
        pendingPreview = null
        jobScope.launch { live.previewBuaaCourses(pending.first, pending.second, pending.third) }
    }

    private const val TAG = "BuaaImportJob"
}

/**
 * 作业本体的类型（Kotlin 的 typealias 只许长在顶层，故写在 object 之外）。
 *
 * 接收者是 [BuaaImportJob.Host]（作业体内 `showMessage(` / `previewBuaaCourses(` / `onBack(` /
 * `onImportPrepared(` 那几手都经它中转）、参数是脚下那枚 [android.webkit.WebView] 平台与
 * [BuaaImportJob.ProgressWriter]（六枚在飞事实的可写视图）。
 */
internal typealias BuaaImportTask = suspend BuaaImportJob.Host.(
    WebView,
    BuaaImportJob.ProgressWriter,
) -> Unit
