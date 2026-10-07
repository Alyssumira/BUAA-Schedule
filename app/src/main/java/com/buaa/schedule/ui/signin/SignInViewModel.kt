package com.buaa.schedule.ui.signin

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.buaa.schedule.data.import.IClassApi
import com.buaa.schedule.data.import.IClassSession
import com.buaa.schedule.data.import.ScanRejectClassifier
import com.buaa.schedule.data.import.ScanTarget
import com.buaa.schedule.data.import.ScanTargetParser
import com.buaa.schedule.data.import.scanRejectCardText
import com.buaa.schedule.data.import.scanRejectEvidenceText
import com.buaa.schedule.data.import.scanRejectForensicLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 一次扫码签到的进程状态。
 *
 * 没有单独画 `Parsing`：[ScanTargetParser.parse] 是同步纯函数，纳秒级完成，
 * 给它一个状态只会让界面多闪一帧看不见的中间态。
 *
 * T85 拆掉了 `Resolving` 那一档：它说的是"二维码里只有 `qdid`，要先查签到详情换 `zjdm/czid`"，
 * 而**只有智学北航那一族有这一步**。iClass 那张码是滚动码，提交的就是它自己（见 [IClassApi]），
 * 没有可"解析"的第二趟 —— 留着一档永不进入的状态，界面里就永远有一条读不到的分支。
 */
sealed interface SignInState {
    data object Idle : SignInState

    data object Submitting : SignInState

    /**
     * @param timeText 服务端回传的签到时间 —— iClass 的成功回执里没有一个可取证的时间字段，
     *   所以这一族它**恒为本地时间**（口径见 [submitToIClass]）。
     * ⚠️ 从前这里还带着 `alreadySigned`（"这张码之前就签过"）：那是智学北航 `queryQdxxByQdid`
     * 回 `QDSJ` 才有的语义，iClass 这一族连"已签过"这个分类都没取证到（见 [IClassApi] 的类注释），
     * 唯一的产出口只会写 `false` ⇒ 界面上那句"你已经签过了"是一条永不成立的分支，T85 一并拆掉。
     */
    data class Signed(val timeText: String) : SignInState

    /**
     * @param relogin true 表示这一族压根没有可用的凭证，界面该给「去登录」出口
     *   （iClass 只有这一个出口：没有可静默续期的东西，服务端拒绝不算"要重登"，见 [fail]）
     * @param evidence 失败卡第二行：**扫到的东西长什么样**（host / 路径 / 参数名 / 字符数，
     *   由 [ScanRejectClassifier] 的形状件拼出来，一个参数值都不在里面）。
     *   只有解析失败那一档带得出形状，服务端拒绝那几档没有形状可说 ⇒ null（不许编一份）。
     */
    data class Failed(
        val reason: String,
        val relogin: Boolean,
        val evidence: String? = null,
    ) : SignInState
}

/**
 * 扫码签到状态机（扫到即提交，没有二次确认页 —— 这是已定决策）。
 *
 * T84 之前这一颗还要在两族之间分岔，T85 按用户决定「只留 iClass，把 SPOC 那条拆掉」之后
 * 只剩一条支路：[ScanTargetParser] 认出 iClass 的码 → [IClassSession] 取那枚 `id` →
 * [IClassApi.signIn] 一次 GET。为什么不在解析层"抽出字段再重组"——
 * iClass 那张码是滚动码，提交的**全部信息**就是那条 URL 本身（外加 `&id=`），
 * 拆开来重组就成另一条请求了，见 [com.buaa.schedule.data.import.IClassSignUrl]。
 *
 * 他班的码本地不拦：能不能签由服务端判定，界面只如实回显它给的原因。这样做的代价是
 * 「签错班」这件事永远不可能由本地预防，好处是二维码格式一变（换个字段名、多一层壳）
 * 不会让本该成功的签到被本地判断挡掉。
 */
class SignInViewModel(application: Application) : AndroidViewModel(application) {

    private val iClassApi = IClassApi()

    private val _state = MutableStateFlow<SignInState>(SignInState.Idle)
    val state: StateFlow<SignInState> = _state.asStateFlow()

    /**
     * 相机分析流是按帧回调的，同一张二维码在预览里能被连解几十次。
     * 用界面状态挡不够（`Submitting → Failed` 之间会漏），所以单独放一个提交闸门。
     *
     * ③ 这一枚以前是颗裸 `Boolean`，于是三个 `if (inFlight) return` 把用户按下去的
     * 「重新扫码 / 继续扫码 / 相册识别」吃掉而界面毫无动静 —— 那是**按次**的静默支路，
     * 修法是让动作本身反映实情（按钮在忙的时候点不动），不是排队：签到这件事重复提交
     * 比"这次没吃进去"更糟，而排队只会把同一张码再投一次。
     * 现在它是单一来源的一颗 [StateFlow]，界面 collect 它来决定 `enabled`，
     * 这三个守卫读的还是同一个值。
     *
     * ③′（T257）这一枚之上又长了一档**不按按钮**的：相机按帧递进来的滚动码。按钮在忙的时候
     * 点不动，可它管不到"投影上那枚码自己换了脸"这一手 —— 闸门看见 payload 变了就放行并记账，
     * 这一颗再把它丢掉，用户扫到了、系统记下了、屏幕上一个字都没有。按用户决定，丙档：
     * **丢仍然丢**（不发第二发，也绝不自动补投），但这一手必须有名字 —— 见 [signIn] 走的
     * [scanFlightAdmission]。排队那一条被 T59③ 钉死过（重复提交比这次没吃进去更糟），这里不翻案。
     */
    private val flight = MutableStateFlow(false)

    /** 有一次签到请求正在飞（界面用它把那颗会丢动作的按钮按灭） */
    val inFlight: StateFlow<Boolean> = flight.asStateFlow()

    /**
     * 这一次在飞之间，已经念过一句"这一手没投出去"了没有。
     *
     * 它只管按帧那一路的**重复念**：同一枚码在冷却账上每 1500ms 会再被放行一次，
     * 少了这一位，一次慢请求就把同一句话按 1.5 秒一遍地写进 logcat。
     * ⚠️ 与 [flight] 同生同灭 —— 置位在发那一发之前，清场在那颗 `finally` 里，
     * 两边都要动，漏一边的话"上一次在飞念过"会永久按住下一次在飞的这句话。
     */
    private var dropNoticed = false

    /**
     * 正在飞的那一发的原文，**只**用来回答"这一手是不是同一枚码"。
     *
     * 一个字都不许进日志、进卡面、进 [SignInState]：iClass 那张码带时间戳，是滚动码，
     * 把完整原文留在任何读得到的地方等于把"还能再签一次"的凭证交出去
     *（口径同 `ScanRejectClassifier` 那一条"只有形状，没有原文"）。
     * 与 [flight] 同生同灭，写它的两条入口（相机回调 / 相册回调）都在这颗 VM 的主线程上，
     * `@Volatile` 只是把"分析线程哪天也来读一次"这一档的账先付掉。
     */
    @Volatile private var dispatchingPayload: String? = null

    /**
     * 扫码得到的原文递进状态机 —— 相机实时解码与相册识图两条路共用这一个入口（手输入口已删除）。
     *
     * 这一手投不投、投不成时念哪一句，全在 [scanFlightAdmission] 那一颗纯判据里（表驱动单测
     * `ScanFlightAdmissionTest` 逐格钉着），这里一个字都不解释、也不拼第二份真相。
     *
     * @param raw 扫码得到的原文
     * @param cooldownRemainingMillis 这一枚在提交闸门冷却账上的剩余毫秒。取数是闸门那侧的事：
     *   相机那条刚把这一枚记进账，剩余就是整个 [RescanCooldownMillis]；相册那条根本没有账，
     *   传 [NoLedgerCooldownRemainingMillis]。状态机自己不读墙钟、也不自己算冷却。
     * @param isRetryHand 这一手是不是用户主动递进来的再一次动作（相册那张图 = true；相机按帧 = false）
     */
    fun signIn(raw: String, cooldownRemainingMillis: Long, isRetryHand: Boolean) {
        when (val admission = scanFlightAdmission(
            dispatching = flight.value,
            isRetryHand = isRetryHand,
            samePayloadAsDispatching = raw == dispatchingPayload,
            alreadyNoticedForThisDispatch = dropNoticed,
            cooldownRemainingMillis = cooldownRemainingMillis,
        )) {
            ScanFlightAdmission.Dispatch -> dispatchNow(raw)
            is ScanFlightAdmission.Dropped -> tellDroppedArrival(admission)
            // 不念也不写日志：屏幕上那一句说的就是这件事（口径同 noteReadableCodeSeenOnce ——
            // 这一档既没失败也没新东西，而它唯一的听众是界面）。它不是静默支路：
            // 走到这里的前提正是"那一句还挂着"，由 [dropNoticed] 与 [flight] 同生同灭保证。
            ScanFlightAdmission.DropAlreadyNoticed -> Unit
        }
    }

    /** 真的发那一发：解析 → 置在飞 → 一次 GET。解析落空那一档走 [rejectScan]，一句人话加一行取证。 */
    private fun dispatchNow(raw: String) {
        val target = ScanTargetParser.parse(raw)
        if (target == null) {
            rejectScan(raw)
            return
        }
        flight.value = true
        dispatchingPayload = raw
        dropNoticed = false
        viewModelScope.launch {
            try {
                // 只留 iClass 一族之后这里没有"分岔"可分了，但 `when` 留着：
                // 它让"再加一族就必须在这里改一笔"由编译器说出口，而不是靠读到这一行的那个人记得。
                when (target) {
                    is ScanTarget.IClass -> submitToIClass(target)
                }
            } finally {
                // 三样一起清：在飞旗、原文、"念过没有"。漏任何一样都是从这一头复活旧病 ——
                // 漏旗标就是永久 in-flight（按钮再也不亮），漏原文就是把上一发的 payload
                // 当成这一发的比较基准，漏旗标位就是把下一次在飞的那一句话永久按住不念。
                flight.value = false
                dispatchingPayload = null
                dropNoticed = false
            }
        }
    }

    /**
     * 丢掉这一手，并且说实话：一句进结果卡，一行进 logcat（`Log.w`，HyperOS 把 logcat 截在
     * Info 级以下，`Log.d` 等于没写），两句话同档同源，都由 [ScanFlightAdmission] 给。
     *
     * 落的是 [SignInState.Failed] 那张卡：它是这一页唯一"回答我刚点的那一下去哪了"的出口
     *（口径同 [reportGalleryBlocked]），界面一个字都不必改 —— 呈现层不是本卡的战场。
     * ⚠️ 已知残账（交回编排者，属 #291 的第二半）：卡片会被在飞那一发的最终结果覆盖，
     * 而"签到完成"那句说的是**上一枚**码；把它拆成两句要动 `SpocScanScreen` 的呈现层，
     * 本卡不许动，所以这里只把它写在这一句上。
     */
    private fun tellDroppedArrival(dropped: ScanFlightAdmission.Dropped) {
        dropNoticed = true
        Log.w(TAG, droppedArrivalTraceText(dropped))
        _state.value = SignInState.Failed(
            reason = droppedArrivalCardText(dropped),
            relogin = false,
        )
    }

    /**
     * 解析失败：按档说一句人话，再留一行**扫到了什么**的取证（T83①②③）。
     *
     * 三件事必须同时做，少一件就是第四条静默死路的形状：
     * - 界面按档说话（[scanRejectCardText]）—— 一句"这不是一张签到码"把"根本不是码"与
     *   "是码但不是这一族的"糊在一起，用户与编排者都归不了因（T83 立这一族档的原始理由）；
     * - 取证行记下第二站交出来的**形状**（[scanRejectForensicLine]）—— 这一站以前一行日志都没有，
     *   于是"屏幕上写了什么"只能靠用户描述；这次定到根因靠的是一张投影照片 + zxing-cpp 手解；
     * - ⚠️ 只有形状，没有原文也没有参数值：iClass 那张码带 `timestamp`，是**滚动码**，
     *   完整原文进 logcat 等于把"还能再签一次"的凭证写给任何拿到这台设备的人（口径见那颗文件）。
     *
     * 一行、按次不按帧：这条路排在提交闸门之后（同一份原文压着不再投，见 [shouldSubmitScan]），
     * 所以这一发不是按帧刷屏的那一类，用 `Log.w` 明说这是失败。
     */
    private fun rejectScan(raw: String) {
        val info = ScanRejectClassifier.classify(raw)
        Log.w(TAG, scanRejectForensicLine(info))
        _state.value = SignInState.Failed(
            reason = scanRejectCardText(info),
            relogin = false,
            evidence = scanRejectEvidenceText(info),
        )
    }

    /**
     * iClass（竞业达轻新课堂）：一次 GET，参数就是扫码原文加一枚 `id`。
     *
     * 没有会话时**必须**走到登录页：这一族的登录页是账号口令表单（T85 之后它也是全仓唯一的
     * 签到登录页），静默失败就是把用户留在一张"签不上"的码前面反复扫。
     * 这里也不写"有会话但读不出来"那一档 —— iClass 的 `authorized()` 就是读盘那一句，
     * 两种说法在事实上同形，写两条就是给界面留一条永不成立的分支。
     */
    private suspend fun submitToIClass(target: ScanTarget.IClass) {
        val userId = IClassSession.authorized()
        if (userId == null) {
            _state.value = SignInState.Failed(
                "还没有登录北航 iClass（轻新课堂）",
                relogin = true,
            )
            return
        }
        _state.value = SignInState.Submitting
        // 成功回执里没有一个我们取到过证的时间字段 ⇒ 时间按本地给（见 SignInState.Signed 的 KDoc），
        // 而"签成了"这一句由 stuSignStatus 担保（见 IClassApi 的两层外壳判定）
        iClassApi.signIn(target.rawUrl, userId)
            .onSuccess {
                _state.value = SignInState.Signed(java.time.LocalDateTime.now().format(TIME_FORMAT))
            }
            .onFailure { fail(it) }
    }

    /** 失败/成功后回到待扫描状态，并重新放行下一次提交 */
    fun reset() {
        if (flight.value) return
        _state.value = SignInState.Idle
    }

    /**
     * 相册那张图里没解出二维码 —— 走同一张失败卡。
     *
     * 这个入口存在的理由：相册是这台设备上唯一还能用的扫码路径（MLKit 的 so 只打进
     * arm64，见本页 KDoc），它静默失败时用户没有任何信号，只能反复选同一张图。
     */
    fun reportNoQrCode() {
        if (flight.value) return
        _state.value = SignInState.Failed(
            "那张图里没认出二维码。请换一张更清晰的图，或用相机重新对准二维码再扫。",
            relogin = false,
        )
    }

    /**
     * 相册这一次**根本没开始解码**（解码器没建出来，或原生库判定没到手）—— 与
     * [reportNoQrCode] 是两件事：那条是"递进去了、没认出码"，这一条是"没递进去"。
     *
     * 为什么不能复用那条的话：说"换一张更清晰的图"会把用户支使去反复挑同一张好图，
     * 而这条路上根本没有图的事。底部提示条那一档（[scanUiStatus]）说的才是这台设备的实情，
     * 这张卡只负责回答"我刚点的那一下去哪了"。
     */
    fun reportGalleryBlocked() {
        if (flight.value) return
        _state.value = SignInState.Failed(
            "相册识别这一次没有真正开始：这台设备上的扫码解码器现在用不了。" +
                "请以底部提示条那一句为准。",
            relogin = false,
        )
    }

    /**
     * 服务端与网络那一头的失败：一句话，外加"要不要给「去登录」出口"。
     *
     * T85 之前这里还有一支 `error is SpocSessionExpiredException → relogin = true`：那是 JWT
     * 带 `exp` 才会有的分类。iClass 没有可静默续期的东西、也没有取证到"凭证过期"这一说
     * （[IClassApi] 那三枚异常没有一枚表示"要重登"），所以那一整支跟着 SPOC 一起拆掉，
     * `relogin` 现在只有一个写点（[submitToIClass] 里"盘上没有 id"那一下）。
     *
     * 留下来的这一支仍然一个字都不加工：服务端给的 ERRMSG 就是中文原因，
     * 包一层"签到失败"把信息吃掉是这一族最早栽过的亏。
     */
    private fun fail(error: Throwable) {
        _state.value = SignInState.Failed(
            error.message ?: "网络异常，请稍后重试",
            relogin = false,
        )
    }

    companion object {
        /**
         * 扫码链第三站（判格式）的 tag：`logcat -d -s ScanSignInParse` 一行一档，
         * 与相机侧那几行（`SpocScanScreen`）分得开 —— 那几行说"帧到没到、解没解出原文"，
         * 这一行说"解出来的那段文字被判成什么档"。级别口径与那一页一致（只有 Info/Warn，
         * 用户的机器把 logcat 砍到 Info、release 又剥 Verbose，`Log.d` 等于没写）。
         */
        private const val TAG = "ScanSignInParse"

        // Locale 必须钉死：这是回给用户看的"签到时间"凭证文本。跟随默认 locale 时，
        // th-TH 系统按佛历输出（年份 +543）、ar/fa 输出阿拉伯-印度数字（项目同族
        // formatter 如 TeachingScheduleParser 都钉了 Locale.US）。
        private val TIME_FORMAT = java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withLocale(java.util.Locale.US)
    }

    class Factory(
        private val application: Application,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(SignInViewModel::class.java)) {
                return SignInViewModel(application) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
