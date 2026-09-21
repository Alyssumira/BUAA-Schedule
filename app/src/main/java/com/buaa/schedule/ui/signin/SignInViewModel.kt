package com.buaa.schedule.ui.signin

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.buaa.schedule.data.import.SpocApi
import com.buaa.schedule.data.import.SpocCredential
import com.buaa.schedule.data.import.SpocQrParser
import com.buaa.schedule.data.import.SpocSession
import com.buaa.schedule.data.import.SpocSessionExpiredException
import com.buaa.schedule.data.import.SpocSignTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * 一次扫码签到的进程状态。
 *
 * 没有单独画 `Parsing`：[SpocQrParser.parse] 是同步纯函数，纳秒级完成，
 * 给它一个状态只会让界面多闪一帧看不见的中间态。
 */
sealed interface SignInState {
    data object Idle : SignInState

    /** 二维码里只有 `qdid`，正在查签到详情换 `zjdm/czid` */
    data object Resolving : SignInState

    data object Submitting : SignInState

    /**
     * @param timeText 服务端回传的签到时间原文（`2026-09-18 10:20:00`），拿不到时是本地时间
     * @param alreadySigned  true 表示这张码之前就签过，本次没有再提交
     */
    data class Signed(val timeText: String, val alreadySigned: Boolean) : SignInState

    /** @param relogin true 表示服务端已经认不得这次的凭证，界面该给「去登录」出口 */
    data class Failed(val reason: String, val relogin: Boolean) : SignInState
}

/**
 * 扫码签到状态机（扫到即提交，没有二次确认页 —— 这是已定决策）。
 *
 * 他班的码本地不拦：能不能签由服务端判定，界面只如实回显它给的原因。这样做的代价是
 * 「签错班」这件事永远不可能由本地预防，好处是二维码格式一变（换个字段名、多一层壳）
 * 不会让本该成功的签到被本地判断挡掉。
 */
class SignInViewModel(application: Application) : AndroidViewModel(application) {

    private val api = SpocApi()

    private val _state = MutableStateFlow<SignInState>(SignInState.Idle)
    val state: StateFlow<SignInState> = _state.asStateFlow()

    /**
     * 相机分析流是按帧回调的，同一张二维码在预览里能被连解几十次。
     * 用界面状态挡不够（`Resolving → Failed` 之间会漏），所以单独放一个提交闸门。
     */
    private var inFlight = false

    /** @param raw 扫码得到的原文 —— 相机实时解码与相册识图两条路共用这一个入口（手输入口已删除） */
    fun signIn(raw: String) {
        if (inFlight) return
        val target = SpocQrParser.parse(raw)
        if (target == null) {
            _state.value = SignInState.Failed("这不是一张智学北航的签到码", relogin = false)
            return
        }
        inFlight = true
        viewModelScope.launch {
            try {
                val credential = SpocSession.authorized()
                if (credential == null) {
                    _state.value = SignInState.Failed(
                        if (SpocSession.hasSession()) "登录已失效，请重新登录智学北航"
                        else "还没有登录智学北航",
                        relogin = true,
                    )
                    return@launch
                }
                when (target) {
                    is SpocSignTarget.ByCourse -> submit(target.zjdm, target.czid, credential)
                    is SpocSignTarget.ByQdid -> resolve(target.qdid, credential)
                }
            } finally {
                inFlight = false
            }
        }
    }

    /** 失败/成功后回到待扫描状态，并重新放行下一次提交 */
    fun reset() {
        if (inFlight) return
        _state.value = SignInState.Idle
    }

    /**
     * 相册那张图里没解出二维码 —— 走同一张失败卡。
     *
     * 这个入口存在的理由：相册是这台设备上唯一还能用的扫码路径（MLKit 的 so 只打进
     * arm64，见本页 KDoc），它静默失败时用户没有任何信号，只能反复选同一张图。
     */
    fun reportNoQrCode() {
        if (inFlight) return
        _state.value = SignInState.Failed(
            "那张图里没认出二维码。请换一张更清晰的图，或用相机重新对准二维码再扫。",
            relogin = false,
        )
    }

    /**
     * `qdid` → 详情 → `zjdm/czid`。
     *
     * ⚠️ 老师端二维码的字面内容没有取到证（生成逻辑在 APP 原生侧，H5 里找不到），
     * 所以这条分支的响应字段是照 H5 自己的取值口径推的。真机首联时如果落进
     * 「查不到班级参数」那条失败，说明详情接口并不回传 zjdm/czid，要改的是这里。
     */
    private suspend fun resolve(qdid: String, credential: SpocCredential) {
        _state.value = SignInState.Resolving
        val detail = api.querySignByQdid(qdid, credential.token, credential.rolecode)
        val failure = detail.exceptionOrNull()
        if (failure != null) {
            fail(failure)
            return
        }
        val content = detail.getOrNull() ?: JsonObject(emptyMap())
        val nested = SpocApi.pick(content, "qdxxMap") as? JsonObject
        val zjdm = SpocApi.string(content, "zjdm") ?: SpocApi.string(nested ?: content, "zjdm")
        val czid = SpocApi.string(content, "czid") ?: SpocApi.string(nested ?: content, "czid")
        if (zjdm.isNullOrBlank() || czid.isNullOrBlank()) {
            // H5 在这一步的语义就是「已经签过了，把签到时间显示出来」，两种形态都按它处理
            val signedAt = SpocApi.string(content, "qdsj") ?: SpocApi.string(nested ?: content, "qdsj")
            if (!signedAt.isNullOrBlank()) {
                _state.value = SignInState.Signed(signedAt, alreadySigned = true)
            } else {
                _state.value = SignInState.Failed(
                    "签到详情里没有回传班级参数，无法自动提交",
                    relogin = false,
                )
            }
            return
        }
        submit(zjdm, czid, credential)
    }

    private suspend fun submit(zjdm: String, czid: String, credential: SpocCredential) {
        // xh 是提交接口的必填项，页面从 storage 读 xh，我们登录后一起收割了过来
        if (credential.xh.isBlank()) {
            _state.value = SignInState.Failed("登录信息里没有学号，请重新登录智学北航", relogin = true)
            return
        }
        _state.value = SignInState.Submitting
        val result = api.submitSign(zjdm, czid, credential.xh, credential.token, credential.rolecode)
        result.onSuccess { content ->
            val qdxx = SpocApi.pick(content, "qdxxMap") as? JsonObject ?: content
            val timeText = SpocApi.string(qdxx, "QDSJ")?.takeIf { it.isNotBlank() }
                ?: java.time.LocalDateTime.now().format(TIME_FORMAT)
            _state.value = SignInState.Signed(timeText, alreadySigned = false)
        }.onFailure { fail(it) }
    }

    private fun fail(error: Throwable) {
        _state.value = if (error is SpocSessionExpiredException) {
            SignInState.Failed(error.message ?: "登录已失效", relogin = true)
        } else {
            // 服务端拒绝他班的码时给的是中文原因，直接透传，不要包一层"签到失败"把信息吃掉
            SignInState.Failed(error.message ?: "网络异常，请稍后重试", relogin = false)
        }
    }

    companion object {
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
