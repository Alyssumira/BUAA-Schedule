package com.buaa.schedule.ui.signin.iclass

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.core.designsystem.GlassSurface
import com.buaa.schedule.core.designsystem.GlassTopBar
import com.buaa.schedule.core.designsystem.GlassVariant
import com.buaa.schedule.core.designsystem.LocalSemanticPlate
import com.buaa.schedule.data.import.IClassSession
import com.buaa.schedule.domain.signin.LoginFailureClassifier
import kotlinx.coroutines.launch

/**
 * 北航 iClass（竞业达「轻新课堂」）登录页 —— T85 之后它是全仓**唯一**一条签到登录链。
 *
 * 一次 `POST app/user/login.action`，表单五件套（phone / password / verificationType /
 * verificationUrl / userLevel），口令校验由服务端委托给学校的 `ve` 网关（常量与取证见
 * [com.buaa.schedule.data.import.IClassApi]）。这一页因此**没有 WebView** ——
 * 从前同一目录里另有一页（智学北航那边）走的正是 WebView + CAS 跳转收割 localStorage 的路子，
 * 两条路形状不同、要填的字段也不同，所以 T84 刻意没有把它们并成一页的两副面孔；
 * T85 那条被整页拆掉了，"不许送错登录页"这条纪律现在由"根本没有第二页可送"来保证。
 *
 * 服务端给的原因逐字来自 ERRMSG（外壳 `STATUS=1` 那一档）：这一族的中文文案我们一条都没有取证到，
 * 加工一个字（"账号或密码错误"这种听上去很合理的猜测）就是编话。
 * 而**判不出外壳**的那几档 —— 空体、被转走、响应体没读回来、非 JSON、没有 STATUS、HTTP 非 2xx ——
 * 念的是 [com.buaa.schedule.data.import.IClassApi] 按档位各写好的那一句，每一句只说我们看得见的事：
 * 被转走那一档不猜转去哪（我们根本不读 `Location`），没读回来那一档连「服务端」三个字都不出现
 * （那一趟往返断在我们这侧，服务端那侧答了什么，盘上一个证据都没有）。
 *
 * ⚠️ T392① 补上从前缺的那半张表：**连外壳都没有**的那几档（传输层直接抛出来的超时、连不上、
 * 加密通道没建起来、地址没认出来、往返半路断了）在盘上从前是**裸着**走到下面那一行的 ——
 * `runCatching` 把 JVM 异常本身交回来，而这一页念的是 `error.message`，于是用户看到过英文的
 * `timeout`，同一句「每一句都只念我们看得见的事」在那条支路上是假话。现在折档住在
 * [com.buaa.schedule.domain.signin.LoginFailureClassifier]（纯 JVM 表，每档一格打在
 * `LoginFailureRungTableTest`）：本仓与服务端已经写好那句话的那几枚（含"手机号与口令都要填"这一拒）
 * 仍然**逐字念**，只有抛出来的那一族被换成按档写好的中文。日志那一行走
 * [com.buaa.schedule.domain.signin.LoginFailureClassifier.forensicsLine]：只交回档位与异常类名，
 * 不交回异常原文 —— 传输层那几枚的 message 里就带着服务器地址与端口。
 *
 * ⚠️ 落盘只有一枚 `result.id`，**口令一个字都不存**：老客户端把 phone + password 明文
 * 写进 SharedPreferences、每次进页面重登一遍 —— 那对口令能过学校统一身份认证，
 * 明文躺在可读写目录里等于把账号交给任何拿到这台设备的人。这里刻意不照抄，
 * 所以下面也不做"记住口令/自动填充"那一类开关。
 *
 * 为什么这一页住在 `ui/signin` 的**子包**里：`SignInEntryWiringGuardTest` ⑥ 禁的是
 * 扫码目录里出现可敲字符的输入控件（那条按假想需求做的「手输签到码」入口不许回来），
 * 这一页收的是账号与口令 —— 它不是签到码输入口，而是一条登录表单。
 * 真要把这类表单页并回 `ui/signin`，得先把那条守卫的扫描范围一起改掉，别绕过去。
 */
@Composable
fun IClassLoginScreen(
    onBack: () -> Unit,
    /** 登录成功并已把 id 落盘后回调；调用方决定接下来去哪（扫码页 / 返回） */
    onLoggedIn: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var serverMessage by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }

    fun submit() {
        if (submitting) return
        submitting = true
        serverMessage = null
        scope.launch {
            IClassSession.login(context, phone, password)
                .onSuccess {
                    saved = true
                    // 口令只在这次请求的内存里存在过：成功之后连引用都不留
                    password = ""
                    // ⚠️ 这一档**故意不落 `submitting`** —— 与失败档那句 `submitting = false`
                    // 不对称是刻意的，别"补齐对称"：这枚旗是这一页唯一的重入闸（`submit()`
                    // 开头那句短路）、三处 `enabled` 与键盘 Done 那记短路共同的来源。
                    // 成功之后到这一页真正离场之间还有一段窗口（`MainActivity` 给这一页提供了
                    // `LocalAnimatedVisibilityScope` ⇒ 旧页在退场动画期间仍在组合），
                    // 在这里落旗等于在那段窗口里把表单放开、并允许第二趟登录 POST ——
                    // 而第二趟一旦失败就会写 `serverMessage`，把「已登录」那句话染成失败卡。
                    // 这一档界面要说的话由下面的阶段梯子读 `saved` 来说，不靠这里落旗。
                    onLoggedIn()
                }
                .onFailure { error ->
                    // 日志那一行只交回档位与异常类名（异常原文可能带服务器地址与端口，那一栏明写"未记录"）；
                    // 屏上那句仍然逐字念 error.message —— 折档发生在 IClassApi.login() 里，到这里它
                    // 要么是服务端/本仓的原话，要么是本机那一档写好的中文，两样都不是 JVM 的英文
                    Log.w(TAG, LoginFailureClassifier.forensicsLine(error))
                    serverMessage = error.message ?: "iClass 没有给出原因"
                    submitting = false
                }
        }
    }

    // 这一页的阶段 —— 三枚裸旗**唯一**被读成"界面上怎么说"的地方，谁的出口最近谁在前
    // （与扫码页 `scanUiStatus` 同一口径：档位判一次，措辞按档位取）。
    //
    // 状态句、那颗按钮的字、那张卡的语义色三处**都从这一枚阶段值投影**，谁都不许再自己判一次。
    // 从前按钮写着 `Text(if (submitting) "正在登录…" else "登录")`，是躲在状态句梯子旁边的
    // 第二个读者，而它不看 `saved` —— 于是登录成功之后、这一页交出去之前，同一列两行话互相打架：
    // 上面那支梯子里 `saved` 赢下第一档、念「已登录北航 iClass，正在进入扫码页…」，
    // 下面那颗按钮照旧念「正在登录…」。这与 #114 那枚"顶栏与 body 两个日期源"是同一族病。
    //
    // 单一真源在这里是**有机制**的、不是"两处看起来一致"：投影侧一律 `when (phase)` / 比阶段值，
    // 将来加一档而忘了改投影，`when (phase)` 不穷尽就编译不过。
    val phase = when {
        saved -> LoginPhase.Saved
        submitting -> LoginPhase.Submitting
        serverMessage != null -> LoginPhase.Failed
        else -> LoginPhase.Idle
    }

    val statusText = when (phase) {
        LoginPhase.Saved -> "已登录北航 iClass，正在进入扫码页…"
        LoginPhase.Submitting -> "正在登录…"
        // 原因那一栏一个字都不加工：服务端给的便是 ERRMSG 原文；判不出外壳时是 IClassApi 按档位写好的那句；
        // 连外壳都没有的那几档（传输层抛出来的）是 domain/signin/LoginFailure 按档写好的那句。
        // 三样都在 login() 那一侧就定好了档，所以这一句仍然只在拼接处才碰 serverMessage
        LoginPhase.Failed -> "登录失败：$serverMessage"
        LoginPhase.Idle -> null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = DesignTokens.spaceL),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceM),
        ) {
            GlassTopBar(
                title = "北航 iClass 登录",
                subtitle = "课堂签到（竞业达「轻新课堂」）",
                statusBarInset = true,
                onBack = onBack,
            )
            // #689：这一页从前只说口令的去向，没有一句说这对账号口令是**谁的**——
            // 于是拿着"我平时签到都用统一认证"的用户以为本应用要求他另注册一个账号。
            // 纪律：只点名这是 iClass 自己的凭据 + 本应用既不注册也不保管；
            // 我们从未取证到"统一认证那套口令登得进 iClass"（零 adb、一次成功登录都没见过），
            // 所以这里既不写"这就是你的统一认证账号"，也不写"那不是"。
            Text(
                text = "这一对账号口令是北航 iClass（课堂平台）自己的登录凭据：本应用不替你注册它，也不替你保管它。" +
                    "它归谁开、能不能拿别处的凭据登进来，我们查不到，也不替你猜 —— 以 iClass 那边给你的那一套为准。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "口令只在提交那一趟里发出去一次：之后签到只用到服务端回传的那枚 id，" +
                    "口令不写进任何存储，也不参与之后的请求。" +
                    "登不进去时下面那一行念的是 iClass 回的原话，或是我们确实看得见的那半件事" +
                    "（没连上、等超时、加密通道没建起来、响应没读回来）—— 我们从不替 iClass 编原因。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("iClass 的手机号 / 账号") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !submitting,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Next,
                ),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("iClass 的口令") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !submitting,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (!submitting) submit() }),
            )
            Button(
                onClick = ::submit,
                enabled = !submitting && phone.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = DesignTokens.minTouchTarget),
            ) { Text(phase.buttonLabel) }
            TextButton(
                onClick = onBack,
                modifier = Modifier.defaultMinSize(minHeight = DesignTokens.minTouchTarget),
            ) { Text("先不登录，返回") }
        }

        if (statusText != null) {
            // 语义色也按阶段取，不许再自己读一遍 serverMessage（读法与上面那支梯不一致就是第二枚读者）
            val isError = phase == LoginPhase.Failed
            GlassSurface(
                variant = if (isError) GlassVariant.ALERT else GlassVariant.PANEL,
                semanticTint = if (isError) MaterialTheme.colorScheme.error else null,
                contentPadding = DesignTokens.spaceL,
                shape = RoundedCornerShape(DesignTokens.cornerPanel),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // 全屏页：先让出系统导航栏再叠页面内缩（与扫码页同口径）
                    .navigationBarsPadding()
                    .padding(
                        start = DesignTokens.spaceL,
                        end = DesignTokens.spaceL,
                        bottom = DesignTokens.spaceL,
                    ),
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    // 成对取墨：染了 error 语义色时底板与文字一次解出（与扫码页那张失败卡同口径）
                    color = LocalSemanticPlate.current?.foreground
                        ?: MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * [IClassLoginScreen] 的阶段（判据在函数体里那一支 `phase` 梯子，这里只负责"这一档按钮上怎么说"）。
 *
 * 分工与扫码页 `ScanUiStatus.kt` 同口径：档位判一次、措辞按档位取，页面不扣住任何一份字面量。
 * 状态句不放进这里，因为失败那一档要说**别人给的那句话**——服务端的 ERRMSG 原文（逐字）、
 * 本仓按外壳档位写好的那六句，或本机这一趟的失败档（`domain/signin/LoginFailure` 那张表），
 * 三样都得在拼接处才成形。
 */
private enum class LoginPhase(val buttonLabel: String) {
    /** 什么都没发生：表单可填、按钮可点 */
    Idle("登录"),

    /** 登录请求在飞 */
    Submitting("正在登录…"),

    /**
     * 这一趟没拿到 id：那句话的正文由调用点拼，按钮回到「登录」让用户改口令再来一趟。
     *
     * ⚠️ 从前这一档的注释写的是"服务器给了原因"——那半句在盘上一直不成立：传输层抛出来的那一族
     * 服务器一个字都没给（T392① 把它折成 `domain/signin/LoginFailure` 的档位句）。
     * 现在这一档收三路话：服务端逐字原话、本仓按外壳档位写好的那句、本机这一趟的失败档。
     */
    Failed("登录"),

    /**
     * 已经登进去、这一页正在交出去（退场动画走完之前它仍在组合）。
     *
     * 按钮念「已登录」：不是「正在登录…」—— 那一句话上面那行状态句已经在说了，
     * 两行互斥就是从前这枚读者自己判 `submitting` 判出来的；也不是「登录」——
     * 那会让这一页看着像"闲下来了、可以再来一趟"，而闸门从头到尾没松过。
     */
    Saved("已登录"),
}

private const val TAG = "IClassLoginScreen"
