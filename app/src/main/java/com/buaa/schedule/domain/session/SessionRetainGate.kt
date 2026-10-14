package com.buaa.schedule.domain.session

import com.buaa.schedule.domain.url.EduDocument
import com.buaa.schedule.domain.url.eduDocumentVerdict

/**
 * 「这枚上缴的 WebView 该不该占住教务会话槽」的**唯一一份**判据（T378①，台账在册五条里的
 * ①先换引用并无条件写 lastUrl、②唯一一枚不摘树就 destroy 的出口、③拆了超时闸却不收单飞旗、
 * ⑤崩溃重建那一路拿空白文档顶掉活会话 —— 四条都落在这一格）。
 *
 * ## 纯度（硬边界）
 *
 * 零 android / androidx import：**不读 `WebView`、不读 `Build.VERSION`、不读权限、不读时钟、不查库**。
 * 五枚事实（候选文档的 URL、这一趟上缴的来路、会话槽里有没有实例、那枚是不是就是候选本身、
 * 单飞旗在不在飞）全部由调用点（`data/import/BuaaWebSession` 的 `retain`）当参数递进来，
 * 本文件一枚都不许自己去取。「是不是教务出身」问的是 `domain/url` 那一枚唯一真源
 * （[eduDocumentVerdict]），本卡**没有搬动它、也没有第二把尺**：全仓每一枚问这句话的地方都只问它。
 * 「返回布尔后调用点各自再判一次设备档次」这一形同样不许 —— 分档就是为了让调用点无档可补。
 *
 * ## 病在哪（基点 `f928dea` 的盘面，四手都在同一枚函数体里）
 *
 * 改前 `retain` 的次序是：`val old = sessionWebView` → `sessionWebView = webView`（**先换引用**）→
 * `lastUrl = webView.url`（**无条件**：一枚 `about:blank` 的新实例照样写进会话真相）→
 * `moveToHiddenHost(webView, activity)`（把宿主**连着旧实例一起**迁进当前窗口）→
 * 只有 `old !== webView` 时 `runCatching { old.destroy() }`（**全文唯一一枚不先从视图树摘下来就
 * destroy 的出口**，另外三枚 `clear`／`releaseForMemory`／`abandonRestore` 都是先 `removeView`）→
 * `cancelRestoreGuard()`（拆掉那道 30 秒超时闸 —— 而那枚闸是全仓**唯一**还能把 `restoreInFlight`
 * 收回 false 的东西，函数体内 `restoreInFlight` 零处）。
 *
 * 于是三个"拆不掉／收不回／焊死"的结构性后果（用户可见代价的分档要装机才能定，本卡不冒充量到）：
 *
 * 1. **非教务出身的实例能顶掉活会话**：`hasSession()` 读的就是那枚 `lastUrl`，而全仓只有
 *    `clear`／`releaseForMemory`／`abandonRestore` 三处会把它清成 null —— 崩溃重建那一路一枚都够不到。
 *    渲染进程掉死之后 `retryToken++` 造出来的那枚新实例（URL 还是 `about:blank` 或 SSO 页）只要走到
 *    任意一枚**没有 origin 判据**的上缴调用点，`lastUrl` 就被写脏、`hasSession()` 转 false、
 *    而手里那枚**真的登录着**的旧实例被 `destroy` 掉。
 * 2. **重建被焊死**：`restore()` 起手第一问是 `if (hasSession() || sessionWebView != null) return`。
 *    脏 `lastUrl` 只关掉前一半，被顶掉的 `sessionWebView != null` 关掉后一半 —— 本进程再也不会尝试恢复。
 * 3. **单飞旗收不回来**：`cancelRestoreGuard()` 拆闸，而 `restoreInFlight` 仍留着 true；此后
 *    `restore()` 在 `if (restoreInFlight) return` 那一档恒早退，且再没有任何东西能把它清零。
 *
 * ## 修法（第 ⓪ 问裁的是甲档：三问一次答完，调用点只分派）
 *
 * 本内核把 `retain` 要做的三件事拆成**具名结论**，调用点按档执行，不再自己判出身：
 *
 * - [PreviousWebView]：手里那一枚旧 WebView 怎么办 —— 包括「本档根本不收候选，所以一枚都不许拆」与
 *   「上缴的就是会话槽里那一枚，拆它就是拆会话自己」这两枚改前没有名字、因而被顺手 `destroy` 掉的档。
 * - [RestoreSingleFlight]：`restoreInFlight` 与它那道超时闸怎么办 —— 四档，**改前后只有「谁收旗」这一件事**，
 *   但改前把闸拆了却不收旗，等于两样都丢了。
 * - 收下／拒收本身：只有 [SessionRetainVerdict.Accepted] 这一档才允许碰 `sessionWebView`、`lastUrl`、
 *   隐藏宿主、门铃信号；[SessionRetainVerdict.Refused] 那一档把这些一手全挡在外面。
 *
 * ## 判据顺序（顺序即语义）
 *
 * 1. 先问出身：`eduDocumentVerdict(候选 URL)` 不是 `OnEduOrigin` ⇒ [SessionRetainVerdict.Refused]。
 *    **这一问排在本函数一切结论之前** —— 改前的病恰恰是"先动状态、再（其实不）问出身"。
 * 2. 收下档里再问手里那枚：没有 ⇒ [PreviousWebView.NoPreviousInstance]；就是候选本身 ⇒
 *    [PreviousWebView.CandidateIsThePrevious]（不许自己拆自己）；另有其枚 ⇒
 *    [PreviousWebView.RemoveFromTreeThenDestroy]（次序与另外三枚出口对齐：先摘树、再 `stopLoading`、再 `destroy`）。
 * 3. 最后问旗：本来不在飞 ⇒ [RestoreSingleFlight.NothingInFlight]（两手都是空转，一手都不许做）；
 *    在飞且这一趟就是恢复链自己 ⇒ [RestoreSingleFlight.CollectFlagAndCancelGuard]（旗与闸一起收，
 *    恢复链不必再自己收一遍 —— 改前那一手 `restoreInFlight = false` 就长在上缴调用点上，本卡把它收进档里）；
 *    在飞而来路是登录页 ⇒ [RestoreSingleFlight.CollectFlagKeepGuardArmed]（会话槽已经被别人占了，
 *    那一趟恢复**还挂着一枚没人认领的 WebView**：旗要收，闸得留着替它收尾 —— 超时闸的判据本批同时
 *    从「旗在不在飞」换成「会话槽里是不是它自己」，否则闸被旗一清零就白举着）。
 * 4. 拒收档：旗在飞 ⇒ [RestoreSingleFlight.LeaveToRestoreChain]（retain 没收下任何东西，无权替恢复链
 *    拆它的闸、收它的旗）；不在飞 ⇒ [RestoreSingleFlight.NothingInFlight]。
 *
 * ## 为什么不折成一枚 Boolean
 *
 * 调用点要做的是**四件不同的事**（换不换引用、写不写 lastUrl、拆不拆旧、旗归谁收），
 * 折成 Boolean 就等于把「这一枚实例不能当会话」与「那一枚旧实例还得留着」念成同一句话 ——
 * 而这两句在崩溃那一路上一真一假，正是本卡要拆开的东西。
 *
 * ## 可达性口径（别把后果说过头）
 *
 * 五枚无判据的上缴调用点在盘上是**结构可达**的（登录页 `:212`／`:370`／`:449`／`:492`／`:518`，
 * 本卡的行号是基点 `f928dea` 的现值，只写文件名与裸行号，坐标归编排者的锚点账）。
 * 但「渲染进程掉死那一次真的会走到上缴吗」「被顶掉的会话丢的是十分钟还是一整天」「拒收之后用户要不要
 * 重走一遍 SSO」全部**没有 main 侧证据**：本卡零 adb，运行期分档归装机，不在这里主张。
 */
enum class RetainSource {

    /** 登录页上缴：用户在页面上走完了 SSO，页面把那枚 WebView 递过来（含崩溃重建后的新实例） */
    LoginScreen,

    /** 冷启动 Cookie 恢复链上缴：`restore()` 自己那一趟加载成了教务文档 */
    CookieRestore,
}

/** 会话槽里那一枚**旧** WebView 的处置：四档四句话，不许并档 */
enum class PreviousWebView {

    /** 会话槽本来是空的：没有东西要拆 */
    NoPreviousInstance,

    /** 上缴的就是会话槽里那一枚本身：拆它就是拆掉刚收下的会话，一手都不许动 */
    CandidateIsThePrevious,

    /** 本档收下候选，而手里另有其枚：**先从视图树摘下来**，再 `stopLoading` ＋ `destroy` */
    RemoveFromTreeThenDestroy,

    /** 本档拒收候选（它不是教务出身），而手里那枚还在当会话用：它比候选更有资格留着，一枚都不许拆 */
    KeepPreviousAsSession,
}

/** `restoreInFlight` 单飞旗与它那道超时闸的处置：四档，每档一句实话 */
enum class RestoreSingleFlight {

    /** 这一趟就是恢复链本身跑成了：旗收掉，闸也一并拆掉（两样都是它自己的东西） */
    CollectFlagAndCancelGuard,

    /**
     * 别的来路（登录页）占下了会话槽，而恢复链那一趟还挂着：旗**要**收
     * （否则 `restore()` 恒早退、本进程再也不尝试恢复），闸**不许**拆
     * （那一枚挂着的 WebView 只剩这道闸会替它拆）。
     */
    CollectFlagKeepGuardArmed,

    /** 本档拒收了候选，什么都没动：旗与闸都原样留给恢复链自己收尾 */
    LeaveToRestoreChain,

    /** 旗本来就不在飞（闸也就不在举着）：两手都是空转，一手都不许做 */
    NothingInFlight,
}

/** [SessionRetainGate.decide] 的结论：两档，调用点按档分派，不许折叠 */
sealed interface SessionRetainVerdict {

    /** 这枚候选的 URL 原样交回，日志只负责念它（脱敏由调用点做） */
    val candidateUrl: String?

    /** 手里那枚旧实例怎么办 */
    val previousWebView: PreviousWebView

    /** 单飞旗与超时闸怎么办 */
    val singleFlight: RestoreSingleFlight

    /** 收下：候选就是教务出身的文档，会话槽、`lastUrl`、隐藏宿主与门铃信号都交给它 */
    data class Accepted(
        override val candidateUrl: String?,
        override val previousWebView: PreviousWebView,
        override val singleFlight: RestoreSingleFlight,
        val source: RetainSource,
    ) : SessionRetainVerdict

    /**
     * 拒收：候选不是教务出身（五档逐枚留在 [candidateVerdict] 里，日志要说得出是哪一种）。
     * 会话槽、`lastUrl`、隐藏宿主、门铃信号**一概不动**；旧实例一枚也不许拆；旗与闸留给恢复链。
     */
    data class Refused(
        override val candidateUrl: String?,
        override val previousWebView: PreviousWebView,
        override val singleFlight: RestoreSingleFlight,
        val candidateVerdict: EduDocument,
    ) : SessionRetainVerdict
}

/**
 * 把五枚已经算好的事实折成一档结论。纯函数：同样的输入永远给同样的档位，不读任何设备面。
 */
object SessionRetainGate {

    fun decide(
        candidateUrl: String?,
        source: RetainSource,
        hasPreviousInstance: Boolean,
        previousIsCandidate: Boolean,
        restoreInFlight: Boolean,
    ): SessionRetainVerdict {
        val verdict = eduDocumentVerdict(candidateUrl)
        return when (verdict) {
            EduDocument.OnEduOrigin -> SessionRetainVerdict.Accepted(
                candidateUrl = candidateUrl,
                previousWebView = when {
                    !hasPreviousInstance -> PreviousWebView.NoPreviousInstance
                    previousIsCandidate -> PreviousWebView.CandidateIsThePrevious
                    else -> PreviousWebView.RemoveFromTreeThenDestroy
                },
                singleFlight = when {
                    !restoreInFlight -> RestoreSingleFlight.NothingInFlight
                    source == RetainSource.CookieRestore -> RestoreSingleFlight.CollectFlagAndCancelGuard
                    else -> RestoreSingleFlight.CollectFlagKeepGuardArmed
                },
                source = source,
            )

            else -> SessionRetainVerdict.Refused(
                candidateUrl = candidateUrl,
                candidateVerdict = verdict,
                previousWebView = if (hasPreviousInstance) {
                    PreviousWebView.KeepPreviousAsSession
                } else {
                    PreviousWebView.NoPreviousInstance
                },
                singleFlight = if (restoreInFlight) {
                    RestoreSingleFlight.LeaveToRestoreChain
                } else {
                    RestoreSingleFlight.NothingInFlight
                },
            )
        }
    }
}
