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
 * 失败原因逐字来自服务端 ERRMSG：这一族的中文文案我们一条都没有取证到，
 * 加工一个字（"账号或密码错误"这种听上去很合理的猜测）就是编话。
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
                    Log.w(TAG, "iClass 登录失败：${error.message ?: error.javaClass.simpleName}")
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
        // 失败原因逐字来自服务端 ERRMSG，一个字都不加工，所以这句话在拼接处才碰 serverMessage
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
            Text(
                text = "登录一次即可：之后签到只用到服务端回传的那枚 id，" +
                    "口令不写进任何存储，也不参与之后的请求。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("手机号 / 账号") },
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
                label = { Text("口令") },
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
 * 状态句不放进这里，因为失败那一档要说服务器给的原文（逐字来自 ERRMSG，得在拼接处才成形）。
 */
private enum class LoginPhase(val buttonLabel: String) {
    /** 什么都没发生：表单可填、按钮可点 */
    Idle("登录"),

    /** 登录请求在飞 */
    Submitting("正在登录…"),

    /** 服务器给了原因：这句话的正文由调用点拼，按钮回到「登录」让用户改口令再来一趟 */
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
