package com.buaa.schedule.ui.signin

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.core.designsystem.GlassSurface
import com.buaa.schedule.core.designsystem.GlassTopBar
import com.buaa.schedule.core.designsystem.GlassVariant
import com.buaa.schedule.core.designsystem.LocalSemanticColors
import com.buaa.schedule.core.designsystem.LocalSemanticPlate
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * 智学北航扫码签到页。
 *
 * 两条入口指向同一个状态机（[SignInViewModel]）：相机实时解码、相册识图。相机是主路径。
 * 第三条入口「手输签到码」（底部按钮 + 输入弹窗）已于 2026-09-21 整条删除：
 * 现实里老师端只有那张二维码，不存在一个可以抄下来的短码，那条入口是按假想需求做的。
 *
 * 删掉它之后盖不住一个新事实：这两条入口**不是**在任何设备上都还在 —— MLKit 的解码库
 * 在 release 包里只带 arm64 一档（见 docs/BUAA_SPOC_SIGNIN_PLAN.md §1.2 与 app/build.gradle.kts 末尾的
 * `androidComponents` 块），而相册识别送进的是**同一个** `scanner.process(...)` ——
 * scanner 建不出来（缺解码库）时相机与相册**一起没**，这一页在这种设备上一条路都没有。
 * 以前这个事实被手输那颗按钮盖着，现在只能靠降级文案说实话（见 [scanUiStatus]）。
 *
 * 这件事由 [BarhopperNativeLibProbe] 在任何一次解码调用之前判掉（T24）：不能等 ML Kit
 * 自己抛，它是在自己的工作线程上 `System.loadLibrary` 的，那个 `UnsatisfiedLinkError`
 * 这一页任何一处 catch 都接不住，只会顺着线程默认处理器把进程打死。
 * 判定为不可用时这里的效果是 `scanner` 直接为 null：相机分析器不建、相册按钮的 enabled
 * 一起灭掉，文案不再指向任何一条出路。arm64 上探针只会回答"可用"，
 * 这一页的组合与绑定次序和改动前一致。
 *
 * 扫到即提交，没有确认页（已定决策）：他班的码由服务端判定拒绝，界面只回显原因。
 */
@Composable
fun SpocScanScreen(
    onBack: () -> Unit,
    /** 凭证失效时把用户送去登录页 */
    onNeedLogin: () -> Unit,
    viewModel: SignInViewModel = viewModel(
        factory = SignInViewModel.Factory(
            LocalContext.current.applicationContext as android.app.Application,
        ),
    ),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.state.collectAsState()

    // 权限的真相在系统那边，不在进入页面那一刻的快照里（D3）：
    // 「Don't allow」→ 去系统设置里放行 → 回来，这一页必须跟着翻面。
    // 旧写法是一颗无 key 的 remember{}，只在组合时读一次，请求回调之外再没人重读它 ——
    // 用户从设置回来照样写着「没有相机权限」，而下面那颗以 granted 为键的绑定 effect
    // 也永远不会再跑第二遍。口径抄设置页那一处（permissionResumeTick）：
    // ON_RESUME 撞一次就把 tick 加一，remember 连带重建、当场重读真实权限。
    // ⚠️ 读权限这件事留在调用点（一次 checkSelfPermission 的易失读），判据不在这里。
    var permissionResumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionResumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var granted by remember(permissionResumeTick) {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    // 取 provider 这一步本身失败过（抛错 / 超时 / future 永不完成），而且重试也拿不到（D2）：
    // 它和 cameraError 是两件事 —— cameraError 只有拿到 provider 之后、绑定阶段才写得进去，
    // 所以 provider 拿不到时那个字段是空的，旧版整页就这样一声不吭。
    var cameraProviderMissing by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    // 解码器"跑起来之后"坏了（analyzer 收到 ML Kit 的失败回调）：相机这条先停用，
    // scanner 还在，所以相册识别仍然承诺得起
    var scannerWorking by remember { mutableStateOf(true) }
    // 解码器"根本不在包里"（T24）：探针**已判定**不可用才 true —— 未判定不是不可用，
    // 那样会把 arm64 上预热还没跑到那一档的窗口变成一帧降级页。
    // 这一档比 scannerWorking 更彻底：相册识别用的是同一个 scanner，所以它一起没。
    var decoderMissing by remember { mutableStateOf(barhopperNativeLib.verdict == NativeLibVerdict.Missing) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }
    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // 起手就把判定等到手（判定那一下的 dlopen 在 IO 上，主线程只是挂起等结论）。
    // 常态下这里一次都不等：预热在首帧之后就把结论算好了（ScanChainWarmUp 的闸门顺路做的），
    // awaitDecided() 直接返回现成的 verdict。
    LaunchedEffect(Unit) {
        if (barhopperNativeLib.awaitDecided() == NativeLibVerdict.Missing) decoderMissing = true
    }

    val scanner = remember(decoderMissing) {
        // 判定为不可用时不建 scanner：下面的 analyzer 建不出来（相机那一档不绑），
        // 相册那颗按钮的 enabled = scanner != null 也一起灭掉 —— 它送进的是同一个 process()，
        // 缺库时同样解不出东西，留着只会把用户指向一条死路。
        // arm64 上 decoderMissing 恒为 false（探针只会回答可用），这一句等价于原来的 remember {}。
        if (decoderMissing) {
            null
        } else {
            runCatching {
                BarcodeScanning.getClient(
                    // 只解 QR：多解一种格式会给每一帧多加一次解码开销
                    BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
                )
            }.getOrNull()
        }
    }
    DisposableEffect(scanner) {
        onDispose { scanner?.close() }
    }

    val analyzer = remember(scanner) {
        scanner?.let {
            QrCodeAnalyzer(it, onCode = { text -> viewModel.signIn(text) }, onFailure = { scannerWorking = false })
        }
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // COMPATIBLE 模式在 Surface 重建（切后台再回来）时不会黑屏
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    LaunchedEffect(granted) {
        // 键从 Unit 换成 granted（D3 的第二半）：有些设备上相机服务在没放行之前根本不给
        // provider，那一档下第一次取值是白取的 —— 放行回来要能再拿一次。
        // 已经拿到手就不重复取（ProcessCameraProvider 是进程单例，取值本身几十毫秒级）。
        if (provider != null) return@LaunchedEffect
        cameraProviderMissing = false
        val acquired = cameraProviderWithRetry(context)
        provider = acquired
        if (acquired == null) {
            cameraProviderMissing = true
            // 这一条只在日志里存在是不够的：用户那台机器（HyperOS）logcat 砍到 Info 级、
            // release 又剥 Verbose，读证据的是下面 scanUiStatus 那一档文案
            Log.w(TAG, "相机服务未交出 ProcessCameraProvider（首试 + 重试各 ${ProviderTimeoutMillis}ms 上限）")
        }
    }

    // CompositionLocal 只能在组合期读，绑定发生在协程里，所以先把旋转值取出来
    val view = LocalView.current
    val targetRotation = remember(view) { view.display?.rotation ?: Surface.ROTATION_0 }
    val busy = state is SignInState.Resolving || state is SignInState.Submitting
    // 相机这条路径是否真的在跑：取景框只在它有效时出现，
    // 退化到相册时再压一层暗区就只是噪音。
    // 判据抽成纯函数 scanCameraLive（D2）：旧写法漏了 provider 与 analyzer 两项，
    // provider 拿不到时它照样返回 true，于是取景框画在一块黑 PreviewView 上，
    // 整页谎报"一切正常"。
    val cameraLive = scanCameraLive(
        scannerAvailable = scanner != null,
        analyzerReady = analyzer != null,
        cameraProviderReady = provider != null,
        granted = granted,
        scannerWorking = scannerWorking,
        cameraError = cameraError,
    )

    LaunchedEffect(granted, provider, scannerWorking, analyzer) {
        // 纪律（T24）：真正要用解码器之前先把判定做完 —— 绑上分析流就是第一帧解码的
        // 唯一入口，所以它必须排在判定之后，未判定的 scanner 一次也不许开帧。
        // 已判定可用时 awaitDecided() 不换线程、不挂起，这一次调用相对改动前不多出任何一帧。
        if (barhopperNativeLib.awaitDecided() != NativeLibVerdict.Available) return@LaunchedEffect
        val cameraProvider = provider ?: return@LaunchedEffect
        val activeAnalyzer = analyzer ?: return@LaunchedEffect
        if (!granted || !scannerWorking) return@LaunchedEffect
        // unbindAll 必须先于 bind：重复绑定同一个 Preview 会抛 IllegalArgumentException
        cameraProvider.unbindAll()
        runCatching {
            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(targetRotation)
                .build()
                .apply { setAnalyzer(analysisExecutor, activeAnalyzer) }
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build(),
                preview,
                analysis,
            )
        }.onFailure {
            // 没有后置摄像头（平板/模拟器）：这一页只剩相册一条路，不算错误
            cameraError = it.message
        }
    }

    // 闸门（ScanSubmissionGate）由两件事驱动：
    // ① 回到 Idle（用户按了「重新扫码 / 继续扫码」）⇒ 整枚清掉，同一张码也允许再来一次；
    // ② 结果卡还挂在屏幕上 ⇒ 告诉闸门"用户在点按钮了"，这一档同一份原文不再重投。
    // 第 ② 条不是多余的保险：同一张解不开的码重投出来的还是**相等**的 Failed 值，
    // MutableStateFlow 对相等的值不重发，所以 ① 那一支永远不会被它触发；
    // 只靠冷却期的话就是每 1500ms 一次真提交，一直刷到用户离开（旧闸门的 consumed
    // 之所以是颗死锁，就是为了压住这个 —— 现在压住它的是这一条，而不是"以后全不投了"）。
    // 换一张码（payload 变了）不看这一条，照旧立刻放行。
    LaunchedEffect(state) {
        analyzer?.markAwaitingUserAction(state is SignInState.Failed || state is SignInState.Signed)
        if (state is SignInState.Idle) analyzer?.resume()
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        // isAvailable() 是一次 volatile 读（不 dlopen、不挂起）：相册这条走的是同一个
        // scanner.process()，所以"已判定可用"才放行 —— 未判定也挡在外面，宁可这次选择不发生，
        // 也不把一帧递给一个还没验过库的解码器（起手那颗 LaunchedEffect 早就把判定做完了，
        // 用户从选图回到这里之间不可能还没判完，这道判断只是把纪律写成代码）。
        if (uri != null && scanner != null && barhopperNativeLib.isAvailable()) {
            runCatching { InputImage.fromFilePath(context, uri) }
                .onSuccess { image ->
                    // 解不出来必须说话：以前空结果什么都不发生，用户只会以为"按了没反应"，
                    // 于是反复挑同一张图（H3）
                    scanner.process(image)
                        .addOnSuccessListener { codes ->
                            val raw = codes.firstOrNull()?.rawValue
                            if (raw == null) viewModel.reportNoQrCode() else viewModel.signIn(raw)
                        }
                        .addOnFailureListener { viewModel.reportNoQrCode() }
                }
                // 相册里那张图太大 / 读不出来时，别让整个页面跟着倒。
                // 前缀必须用 GalleryUnreadablePrefix：scanUiStatus 靠它把这一支和绑定失败分开
                // （相册刚失败时再让用户"改用相册"就是绕圈，见那里的注释）
                .onFailure { cameraError = "$GalleryUnreadablePrefix：${it.message}" }
        }
    }

    BackHandler(enabled = busy, onBack = { })

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )

        // 相机画面不参与 SceneBackground 的对比度兜底：它自己就是全页最亮的一层，
        // 白墙/窗户一进画面，居中的结果卡就糊在亮底上。框外压一层与全站同浓度的
        // 遮罩救对比度，框内留透明——顺带回答了"二维码对准哪儿"（H4）。
        // 结果卡出现时也要留着：那正是最需要对比度的一刻，卡片本身就落在框内。
        if (cameraLive) {
            ScanViewfinder(modifier = Modifier.fillMaxSize())
        }

        GlassTopBar(
            title = "扫码签到",
            statusBarInset = true,
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // 贴底动作条与顶栏同属「栏」：走 CHROME 档（折射最强的 pill 材质），
        // 而不是页身那种 PANEL —— 两条栏材质一致，中间才是内容层
        GlassSurface(
            variant = GlassVariant.CHROME,
            contentPadding = DesignTokens.spaceL,
            shape = RoundedCornerShape(topStart = DesignTokens.cornerPanel, topEnd = DesignTokens.cornerPanel),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            // 这是全屏页：栏体贴到屏幕最下沿，内容得自己让出系统导航栏，
            // 否则三键导航机上那颗按钮被导航键压住（M6）
            Column(
                modifier = Modifier.navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceS),
            ) {
                // 文案档位抽成纯函数 scanUiStatus（ScanUiStatus.kt），每一档都能在 JVM 单测里
                // 逐支跑一遍：这一页的提示条是唯一可靠的取证面（用户那台机器读不到 Log.d）。
                // 新增的是 cameraProviderMissing 那一档（D2）—— 旧版里 provider 取不到时
                // 五支全落空，提示条一个字都不出，而 cameraLive 还谎报正常。
                val hintText = scanUiStatus(
                    decoderMissing = decoderMissing,
                    scannerUsable = scanner != null && scannerWorking,
                    granted = granted,
                    cameraError = cameraError,
                    cameraProviderMissing = cameraProviderMissing,
                )
                if (hintText != null) {
                    Text(
                        text = hintText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.spaceS)) {
                    // 栏内动作一律 48dp 触控下限（M4）。「手输签到码」那颗按钮及其弹窗
                    // 已整条删除（2026-09-21，现实里不存在可抄的签到码），这一栏只剩相册一颗
                    val barAction = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = DesignTokens.minTouchTarget)
                    Button(
                        onClick = {
                            galleryLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        enabled = scanner != null && !busy,
                        modifier = barAction,
                    ) { Text("相册识别") }
                }
            }
        }

        val resultText = when (val s = state) {
            is SignInState.Resolving -> "正在读取签到信息…"
            is SignInState.Submitting -> "正在提交签到…"
            is SignInState.Signed ->
                if (s.alreadySigned) "这节课你已经签过了（${s.timeText}）" else "签到完成　${s.timeText}"
            is SignInState.Failed -> s.reason
            else -> null
        }
        if (resultText != null) {
            val failed = state is SignInState.Failed
            val signed = state is SignInState.Signed
            // 失败卡升 ALERT + error 语义色（与登录页状态卡、冲突横幅同一档）；
            // 「签到完成」是这一页唯一做完的回执，染 success；
            // 读取/提交进行中仍是无染 PANEL——恒染色等于把正常流程一直点红点绿
            GlassSurface(
                variant = if (failed) GlassVariant.ALERT else GlassVariant.PANEL,
                semanticTint = when {
                    failed -> MaterialTheme.colorScheme.error
                    signed -> LocalSemanticColors.current.success
                    else -> null
                },
                contentPadding = DesignTokens.spaceL,
                shape = RoundedCornerShape(DesignTokens.cornerPanel),
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = DesignTokens.spaceL),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceS)) {
                    Text(
                        text = resultText,
                        style = MaterialTheme.typography.bodyMedium,
                        // 成对取墨：绿卡配绿字（1.00:1）就是这张卡改前的读数；
                        // 现在底板与文字由同一处解出，没染色的进行中仍走 onSurfaceVariant
                        color = LocalSemanticPlate.current?.foreground
                            ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when (val s = state) {
                        is SignInState.Failed -> Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.spaceS)) {
                            val cardAction = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = DesignTokens.minTouchTarget)
                            if (s.relogin) {
                                Button(onClick = onNeedLogin, modifier = cardAction) { Text("去登录") }
                            }
                            TextButton(
                                onClick = { viewModel.reset() },
                                modifier = cardAction,
                            ) { Text("重新扫码") }
                        }
                        is SignInState.Signed -> Button(
                            onClick = { viewModel.reset() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = DesignTokens.minTouchTarget),
                        ) { Text("继续扫码") }
                        else -> Unit
                    }
                }
            }
        }
    }
}

/** 取景框边长占短边的比例：再大就把结果卡顶出画面，再小对不准教室投影上的远距离二维码 */
private const val ViewfinderSideRatio = 0.62f

/** 取景框中心的纵向位置：略高于正中，给贴底动作条让出地方 */
private const val ViewfinderCenterYFraction = 0.42f

/** 框线宽度：比发丝粗、比卡片描边细，压在实拍画面上下不显脏（这一处的几何值，无对应令牌） */
private val ViewfinderStrokeWidth = 2.dp

/**
 * 扫码取景框：框外压暗 [DesignTokens.SCRIM_ALPHA]（与引导页遮罩同浓度），框内透明，描一圈主色框线。
 *
 * "挖洞"用一条 EvenOdd 路径一次画成，而不是暗层上再叠一块透明矩形：
 * 两层要在圆角边缘像素级对齐，差一点就在洞边漏出一圈亮边。
 */
@Composable
private fun ScanViewfinder(modifier: Modifier = Modifier) {
    val frameColor = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val side = minOf(size.width, size.height) * ViewfinderSideRatio
        val hole = Rect(
            offset = Offset(
                x = (size.width - side) / 2f,
                y = size.height * ViewfinderCenterYFraction - side / 2f,
            ),
            size = Size(side, side),
        )
        val corners = CornerRadius(DesignTokens.cornerPanel.toPx())
        val fullCanvas = Rect(offset = Offset.Zero, size = size)
        val mask = Path().apply {
            addRect(fullCanvas)
            addRoundRect(RoundRect(hole, corners))
            fillType = PathFillType.EvenOdd
        }
        drawPath(mask, Color.Black.copy(alpha = DesignTokens.SCRIM_ALPHA))
        drawRoundRect(
            color = frameColor.copy(alpha = 0.9f),
            topLeft = hole.topLeft,
            size = hole.size,
            cornerRadius = corners,
            style = Stroke(width = ViewfinderStrokeWidth.toPx()),
        )
    }
}

/** 分析流固定用单线程：MLKit 自己会排队，多线程只会让 close 时序更难保证 */
private val analysisExecutor = Executors.newSingleThreadExecutor()

/**
 * 相机帧 → QR 原文。
 *
 * 闸门是 [handled] 那一枚「原文 + 时刻」，判据在 [shouldSubmitScan]（纯 JVM，单测钉着）。
 * 它换掉了原先那颗 [consumed] 式的布尔死锁：那颗锁只在 `SignInState.Idle` 才清，而 Idle 只有
 * 用户按结果卡上的按钮才回得来 —— 于是**放行过一次之后**每一帧都被静默丢掉（预览照旧活着，
 * 再对准一张码也不会有任何反应），这就是用户报的「扫码没反应」。
 *
 * ⚠️ 代价要说清：闸门现在在解码**之后**才拦，不再在 analyze 入口把帧直接 close 掉。
 * 这个区别只影响"已经放行过一次之后"那一段：那一档以前每帧零开销、现在每帧照旧解一次。
 * 而正常取景时（还没扫到码）本来就在按帧解码，两者是同一份账 —— 何况旧写法省下的那份开销
 * 换来的是整页失效。按帧的节奏仍由 `STRATEGY_KEEP_ONLY_LATEST` + 单线程 executor 压着，
 * 同一时刻最多一帧在 ML Kit 手里。
 */
private class QrCodeAnalyzer(
    private val scanner: BarcodeScanner,
    private val onCode: (String) -> Unit,
    private val onFailure: () -> Unit,
) : ImageAnalysis.Analyzer {

    /**
     * 上一次放行。@Volatile 是必须的：写它的是 ML Kit 的回调线程，读它的是 analysisExecutor，
     * 清它的是主线程 —— 三处不同线程，而且**整枚换引用**（[ScanHandled] 不可变），
     * 所以任何一次读到的都是配对完整的「原文 + 时刻」。
     */
    @Volatile private var handled: ScanHandled? = null

    /** 屏幕上是否挂着等用户按的结果卡（`Failed` / `Signed`），由组合侧推进来，见 [markAwaitingUserAction] */
    @Volatile private var awaitingUserAction = false

    /** 回到待扫状态：整枚闸门清掉，连同一张码也重新允许（「重新扫码 / 继续扫码」那一颗按钮） */
    fun resume() {
        handled = null
        awaitingUserAction = false
    }

    /**
     * 结果卡挂着的时候把闸门按在"只认新码"这一档：同一份原文不再重投。
     * 少了这一半，冷却期一到就会每 [RescanCooldownMillis] ms 把同一张码真提交一次
     * （相等的 `Failed` 值不重发 → Idle 那一支永远等不到），论证见 [shouldSubmitScan]。
     */
    fun markAwaitingUserAction(awaiting: Boolean) {
        awaitingUserAction = awaiting
    }

    // 只在本函数内消化这个 opt-in：标 @ExperimentalGetImage 会把它传染给调用方，
    // 而调用方是框架经 ImageAnalysis.Analyzer 接口回调的，无处可标。
    // 必须是 androidx 那个 @OptIn —— lint 的 UnsafeOptInUsageError 只认它，kotlin.OptIn 压不住
    @androidx.annotation.OptIn(markerClass = [androidx.camera.core.ExperimentalGetImage::class])
    override fun analyze(image: ImageProxy) {
        val mediaImage = image.image
        if (mediaImage == null) {
            image.close()
            return
        }
        try {
            // close 只能挂到任务结束之后：InputImage 只持有 mediaImage 的引用，MLKit 是在
            // 自己的工作线程上才去读 getPlanes() 的。放在 finally 里立即 close，那一帧就报
            // IllegalStateException: Image is already closed，被下面的失败分支当成
            // 「这台设备用不了相机扫码」而永久关掉整页的扫码能力。
            scanner.process(InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees))
                .addOnSuccessListener { codes ->
                    codes.firstOrNull()?.rawValue?.let { raw ->
                        // 墙钟在调用点读、判据是纯函数（仓库口径）
                        val now = System.currentTimeMillis()
                        if (shouldSubmitScan(handled, raw, now, awaitingUserAction)) {
                            // 先置位再回调：回调里就会开始发请求，这期间新帧可能已经进来了
                            // （这一句次序钉在 ScanSubmissionGateTest 的形状守卫里）
                            handled = ScanHandled(raw, now)
                            onCode(raw)
                        }
                    }
                }
                .addOnFailureListener {
                    // 解不出来是常态（画面糊、没对准），只有 native 缺失这种才值得降级；
                    // 但连 MLKit 都报错时继续按帧重试只是白耗电，交给界面提示换入口。
                    // 留痕用 [DecodeFailurePayload] 占位（这次解码没有原文可记），并把闸门按在
                    // "只认新码"这一档：与旧 consumed 的口径一致，只是不再排斥以后真解出来的码。
                    handled = ScanHandled(DecodeFailurePayload, System.currentTimeMillis())
                    awaitingUserAction = true
                    onFailure()
                }
                .addOnCompleteListener { image.close() }
        } catch (e: Throwable) {
            // 只盖得住 process() 的**同步**部分：烂 InputImage、Image 已 close 这类。
            // ⚠️ 缺库那种 UnsatisfiedLinkError 这里盖不住（T24 订正）—— 它是 ML Kit 在
            // 自己的 worker 上调 System.loadLibrary 时抛的，本方法早就返回了。
            // 缺库不走到这里：BarhopperNativeLibProbe 判定不可用时 scanner 为 null、
            // 分析流根本不绑，而绑定之前又必须先 awaitDecided()（见 SpocScanScreen）。
            // 所以能拿到回调的 scanner 一定是"已判定可用"的那一颗，这里只剩运行期故障。
            image.close()
            handled = ScanHandled(DecodeFailurePayload, System.currentTimeMillis())
            awaitingUserAction = true
            onFailure()
        }
    }
}

/**
 * provider 单次取值的等待上限。
 *
 * 4 秒的账：CameraX 绑相机服务 + 枚举摄像头正常是几十毫秒（`ScanChainWarmUp` 就是提前
 * 把这一档跑掉的，见它的 `warmUpCameraProvider`），而它自己那一份等待的上限也是秒级。
 * 再短会把"系统进程冷启动"那一档误判成没有相机，再长就是用户站在那儿盯黑屏。
 */
private const val ProviderTimeoutMillis = 4_000L

/** 取值次数：首试 + 一次重试。一次就够 —— 重试只对"瞬时的服务抖动/绑定失败"有用 */
private const val ProviderAttempts = 2

/**
 * 取 CameraProvider，带上限与一次重试（D2）。
 *
 * 旧写法是 `provider = cameraProviderOrNull(context)` 一句、包在只跑一次的
 * `LaunchedEffect(Unit)` 里，而 `cameraProviderOrNull` 内部那颗 `future.get()` 没有上限、
 * 没有日志、没有重试：future 永不完成时这一页就永远停在 provider == null，
 * 而既不进 cameraError、也不进任何文案 —— 第二结构性「没反应」。
 *
 * ⚠️ 重试只对"这一次取值本身失败了"有用：`ProcessCameraProvider.getInstance` 给的是
 * 进程单例，那颗 future 一旦永不完成，第二次 `get()` 等的还是同一颗 —— 这种设备最后还是
 * 靠 [scanUiStatus] 的 provider 那一档说话。不假装重试能治好一切。
 */
private suspend fun cameraProviderWithRetry(context: android.content.Context): ProcessCameraProvider? {
    repeat(ProviderAttempts) { attempt ->
        // withTimeoutOrNull 的两种结局都是 null（超时 → null；超时被 cameraProviderOrNull 里的
        // runCatching 吞掉、块自己返回 null → 也是 null），所以"这次等待有上限"这件事
        // 不依赖谁去接那个取消异常 —— 这里只关心一件事：到底拿没拿到 provider。
        val hit = withTimeoutOrNull(ProviderTimeoutMillis) { cameraProviderOrNull(context) }
        if (hit != null) {
            if (attempt > 0) Log.i(TAG, "相机 provider 第 ${attempt + 1} 次取值成功")
            return hit
        }
        Log.w(TAG, "相机 provider 第 ${attempt + 1} 次取值未成功（${ProviderTimeoutMillis}ms 上限，或取值本身抛了）")
    }
    return null
}

/**
 * 取 CameraProvider。
 *
 * 不引 `concurrent-futures-ktx` 的 `await()`：CameraX 1.4 的 `awaitInstance` 又要绑
 * 别的扩展包，这里一个 `suspendCancellableCoroutine` 就够，失败只意味着没有相机。
 */
private suspend fun cameraProviderOrNull(context: android.content.Context): ProcessCameraProvider? =
    withContext(Dispatchers.Main) {
        runCatching {
            suspendCancellableCoroutine { cont ->
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener(
                    {
                        if (cont.isActive) {
                            cont.resume(runCatching { future.get() }.getOrNull())
                        }
                    },
                    ContextCompat.getMainExecutor(context),
                )
            }
        }.getOrNull()
    }

/** 与本页另一条设备链路（SpocLoginScreen）同一个 TAG 口径：只留证据，不代替文案 */
private const val TAG = "SpocScanScreen"
