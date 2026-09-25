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
 * 北航 iClass（竞业达「轻新课堂」）登录页。
 *
 * 与 [com.buaa.schedule.ui.signin.SpocLoginScreen] 是**两条不同的路**，不是同一页的两副面孔：
 * SPOC 的鉴权材料是一枚要由 CAS 跳转换来的 JWT，所以那边必须挂一个 WebView 走完跳转再收割
 * localStorage；iClass 这一族的登录接口就是一次 `POST app/user/login.action`，
 * 表单五件套（phone / password / verificationType / verificationUrl / userLevel），
 * 口令校验由服务端委托给学校的 `ve` 网关（常量与取证见
 * [com.buaa.schedule.data.import.IClassApi]）。这一页因此**没有 WebView**。
 *
 * 失败原因逐字来自服务端 ERRMSG：这一族的中文文案我们一条都没有取证到，
 * 加工一个字（"账号或密码错误"这种听上去很合理的猜测）就是编话。
 *
 * ⚠️ 落盘只有一枚 `result.id`，**口令一个字都不存**：老客户端把 phone + password 明文
 * 写进 SharedPreferences、每次进页面重登一遍 —— 那对口令能过学校统一身份认证，
 * 明文躺在可读写目录里等于把账号交给任何拿到这台设备的人。这里刻意不照抄，
 * 所以下面也不做"记住口令/自动填充"那一类开关。
 *
 * 为什么这一页住在 `ui/signin` 的**子包**里：`SpocSignInEntryWiringGuardTest` ⑥ 禁的是
 * 扫码目录里出现可敲字符的输入控件（那条按假想需求做的「手输签到码」入口不许回来），
 * 这一页收的是账号与口令 —— 它不是签到码输入口，而是 SPOC 那边 WebView 登录的对应物。
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
                    onLoggedIn()
                }
                .onFailure { error ->
                    Log.w(TAG, "iClass 登录失败：${error.message ?: error.javaClass.simpleName}")
                    serverMessage = error.message ?: "iClass 没有给出原因"
                    submitting = false
                }
        }
    }

    val statusText = when {
        saved -> "已登录北航 iClass，正在进入扫码页…"
        submitting -> "正在登录…"
        serverMessage != null -> "登录失败：$serverMessage"
        else -> null
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
            ) { Text(if (submitting) "正在登录…" else "登录") }
            TextButton(
                onClick = onBack,
                modifier = Modifier.defaultMinSize(minHeight = DesignTokens.minTouchTarget),
            ) { Text("先不登录，返回") }
        }

        if (statusText != null) {
            val isError = serverMessage != null
            GlassSurface(
                variant = if (isError) GlassVariant.ALERT else GlassVariant.PANEL,
                semanticTint = if (isError) MaterialTheme.colorScheme.error else null,
                contentPadding = DesignTokens.spaceL,
                shape = RoundedCornerShape(DesignTokens.cornerPanel),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // 全屏页：先让出系统导航栏再叠页面内缩（与扫码页、SPOC 登录页同口径）
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
                    // 成对取墨：染了 error 语义色时底板与文字一次解出（同 SPOC 登录页那处）
                    color = LocalSemanticPlate.current?.foreground
                        ?: MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val TAG = "IClassLoginScreen"
