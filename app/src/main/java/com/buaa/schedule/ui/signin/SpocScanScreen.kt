package com.buaa.schedule.ui.signin

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    // scanner 还在，所以相册识别仍然承诺得起。
    // ⚠️ 这一颗以前只关不开（T59①）：两条失败出口都只写 false、没有任何一处写回 true，
    // 于是一帧瞬时失败就把相机扫码判死到整页结束。现在它的取值由 [ScanRecoveryPolicy]
    // 的内核决定（连错几帧才停用、停用几轮才算没救、回到前台给不给再试），
    // 写在下面 [QrCodeAnalyzer] 推进来的回调里 —— 停用期间故意不 unbind：帧必须继续到达，
    // 内核才有"窗口过完"这个观测量，也才分得清"解码器在坏"和"相机根本没送帧"。
    var scannerWorking by remember { mutableStateOf(true) }
    // 重试绑定这一档的令牌（T59②）。它不进 LaunchedEffect 的键表 —— 那颗 effect 的键串
    // 被 ScanUiStatusTest ⑥ 按字面钉着（"LaunchedEffect(granted, provider, scannerWorking, analyzer)"），
    // 所以令牌改藏在 analyzer 的**同一性**里：+1 就换一颗 analyzer，那颗 effect 照旧重跑。
    var bindGeneration by remember { mutableIntStateOf(0) }
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

    val analyzer = remember(scanner, bindGeneration) {
        scanner?.let {
            QrCodeAnalyzer(
                it,
                onCode = { text -> viewModel.signIn(text) },
                // ① 双向：内核说停就停、说活就活。旧写法这颗回调只写 false，
                // 一次抖动就把相机扫码判死到整页结束（用户报的「扫码没反应」第二条）
                onWorkingChanged = { working -> scannerWorking = working },
            )
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
    // ③ 有一次签到真的在飞：结果卡上那两颗按钮与相册那颗一起按灭。
    // 状态机自己也有 `if (inFlight) return` 的守卫（两处口径同一个来源），
    // 但按灭按钮才是修「按了没反应」的那一半 —— 吞掉动作是 ViewModel 的事，
    // 让用户看见"现在点不动"是界面的事。
    val inFlight by viewModel.inFlight.collectAsState()
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
        // 停用中故意**不解绑**（也不绑）：帧还得继续到达，内核才有"窗口过完"这个观测量。
        // 这一句排在 unbindAll 之前不是随手写的，排到后面去就把 T59① 唯一的自动活路掐了。
        if (!granted || !scannerWorking) return@LaunchedEffect
        // unbindAll 必须先于 bind：重复绑定同一个 Preview 会抛 IllegalArgumentException
        cameraProvider.unbindAll()
        // ② 绑定失败过去是"这一页到此为止"：`cameraError` 不是这颗 effect 的键，抛一次之后
        // 没有任何东西会再跑一次绑定，用户只能退出重进。现在就地重试，次数（3）、退避
        // （400ms/800ms）、哪一类失败才配重试都由 [ScanRecoveryPolicy] 判 —— 有界，
        // 而"这台设备根本没有后置摄像头"那一档一次都不许多试（重试治不好它）。
        var attempt = 1
        while (true) {
            val bound = runCatching {
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
            }
            if (bound.isSuccess) {
                activeAnalyzer.markBindStarted(SystemClock.elapsedRealtime())
                // 绑上了就要把上一轮的旧错收回去：cameraError 不清的话文案永远停在
                // "相机不可用"，而它已经不成立 —— 重试机制也就白做了
                if (cameraError != null) cameraError = null
                break
            }
            val error = bound.exceptionOrNull()
            // runCatching 会把协程取消也一并接住：那一档必须原样抛出去，不然这颗 effect
            // 被键表换页掐掉之后还会接着写 cameraError（仓库口径见 lint 的 SwiftCancellationException）
            if (error is CancellationException) throw error
            val reason = error?.message?.takeIf { it.isNotBlank() } ?: error?.javaClass?.simpleName ?: "未知失败"
            val kind = classifyCameraBindFailure(reason)
            if (!cameraBindRetryAllowed(attempt, kind)) {
                cameraError = reason
                Log.w(
                    TAG,
                    "相机绑定失败，第 $attempt 次之后不再重试（判类 $kind，上限 $MaxCameraBindAttempts）：$reason" +
                        " —— 这一页只剩相册一条路",
                )
                break
            }
            Log.w(TAG, "相机绑定失败第 $attempt 次（判类 $kind），${cameraBindBackoffMillis(attempt)}ms 后重试：$reason")
            delay(cameraBindBackoffMillis(attempt))
            attempt++
        }
    }

    // ①② 回到前台 = 有界地再给一次机会（额度在 [healthAfterPageVisible]，2 次）。
    // 这一档盖的是"帧一帧都不到"的那种坏：按帧的判据在这种情况下永远推不动自己。
    // 权限弹框回来也走这里（它本来就是一次 ON_RESUME），所以放行之后一定会有人再试一次绑定。
    LaunchedEffect(permissionResumeTick) {
        if (permissionResumeTick == 0) return@LaunchedEffect
        val working = analyzer?.recoverOnPageVisible() ?: true
        scannerWorking = working
        // 绑定那一档的旧错也一起给一次重试：键表被形状守卫钉着（见 bindGeneration 的注释），
        // 所以换 analyzer 来驱动重绑，而不是往键表里塞新东西
        val bindErrorToRetry = cameraError?.startsWith(GalleryUnreadablePrefix) == false
        if (bindErrorToRetry) {
            Log.i(TAG, "回到前台：重试相机绑定（上一次的失败原因是「$cameraError」）")
            cameraError = null
            bindGeneration++
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
        if (uri == null) {
            // ③ 用户在那张选图框里按了返回：这不是故障，但"这一趟没选图"与"选了图却没反应"
            // 在读证据时必须分得开 —— 旧写法这里连一行都没有
            Log.i(TAG, "相册选图被取消，这一次什么都没做")
            return@rememberLauncherForActivityResult
        }
        // isAvailable() 是一次 volatile 读（不 dlopen、不挂起）：相册这条走的是同一个
        // scanner.process()，所以"已判定可用"才放行 —— 未判定也挡在外面，宁可这次选择不发生，
        // 也不把一帧递给一个还没验过库的解码器（起手那颗 LaunchedEffect 早就把判定做完了，
        // 用户从选图回到这里之间不可能还没判完，这道判断只是把纪律写成代码）。
        //
        // ⚠️ 以前这三个条件是捏在一起的 `if (a && b && c)`，落空就是纯静默：按钮的 enabled
        // 只看 scanner，所以"判定没到手 / 已判定不可用"那一档**点得动、点下去什么都没有**（③ 的第二支）。
        // 现在把它拆成一条明说的支路：状态机落到失败卡（说清是没开始，不是没解出来），
        // 顺带把相机那边的帧数带上，好让"相机有没有在送帧"这一件事在这里也能对上账。
        if (scanner == null || !barhopperNativeLib.isAvailable()) {
            val why = if (scanner == null) "解码器没建出来" else "解码库的判定还没到手，或已判定为不可用"
            Log.w(TAG, "相册识别没有真正开始：$why（相机侧已收 ${analyzer?.framesArrived() ?: -1L} 帧）")
            viewModel.reportGalleryBlocked()
            return@rememberLauncherForActivityResult
        }
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
                    galleryUsable = scanner != null,
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
                        enabled = scanner != null && !inFlight,
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
                                // ③ 这颗按钮以前点得动、会被 `if (inFlight) return` 吞掉
                                enabled = !inFlight,
                                modifier = cardAction,
                            ) { Text("重新扫码") }
                        }
                        is SignInState.Signed -> Button(
                            onClick = { viewModel.reset() },
                            enabled = !inFlight,
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
 *
 * 上面那句"在解码之后才拦"只说提交闸门。解码器自己坏了那一档（[health]）走的是**入口拦**：
 * 停用窗口里的帧直接 close、一次也不喂给解码器 —— 但分析流**故意不 unbind**。
 * 这是 T59① 的一条要紧取舍：unbindAll 会掐断帧流，而"窗口过完没有"这件事的唯一观测量
 * 就是到达的帧数；掐了帧流就等于把这一档唯一的自动活路也掐掉，剩下只有退出重进那一条
 * （旧写法犯的正是这个错：它把"停用"说成永久，却什么证据都不留）。
 */
private class QrCodeAnalyzer(
    private val scanner: BarcodeScanner,
    private val onCode: (String) -> Unit,
    /**
     * 解码这一档"还活着吗"推进来（T59①）。旧写法这颗回调只写 false，所以一帧抖动就把
     * 相机扫码判死到整页结束；现在取值由 [ScanRecoveryPolicy] 的内核判，两边都会写。
     */
    private val onWorkingChanged: (Boolean) -> Unit,
) : ImageAnalysis.Analyzer {

    /**
     * 上一次放行。@Volatile 是必须的：写它的是 ML Kit 的回调线程，读它的是 analysisExecutor，
     * 清它的是主线程 —— 三处不同线程，而且**整枚换引用**（[ScanHandled] 不可变），
     * 所以任何一次读到的都是配对完整的「原文 + 时刻」。
     */
    @Volatile private var handled: ScanHandled? = null

    /** 屏幕上是否挂着等用户按的结果卡（`Failed` / `Signed`），由组合侧推进来，见 [markAwaitingUserAction] */
    @Volatile private var awaitingUserAction = false

    /**
     * 解码器的健康度（T59①）：写它的是分析流/ML Kit 那几颗线程，读它的是主线程
     * （回到前台那一颗 effect 问它还能不能给机会）。整枚换引用，理由同 [handled]。
     */
    @Volatile private var health = ScanDecoderHealth()

    /**
     * 到达过分析器的帧数：既是内核算停用窗口的时间轴，也是⑤「帧确实到过」的那份证据本体。
     * Long 自增 + 只在跨越阈值时说话 ⇒ 按帧零分配。
     */
    @Volatile private var frameCount = 0L

    /** 绑定完成的时刻（`elapsedRealtime`），0 = 还没绑上。主线程写、分析线程读 */
    @Volatile private var bindElapsedMillis = 0L

    /**
     * 到这一步为止有多少帧"解出了条码却读不出原文"（③ 的第一支）。
     * 只数不弹：按帧的东西做成 UI 就是每秒十几条 toast。
     */
    @Volatile private var valuelessCodes = 0L

    /**
     * 本轮绑定是否已留过首帧痕（⑤）。只在本线程读写 ⇒ 不需要 volatile，也不给按帧添分配。
     *
     * T59b① 的订正：这枚标志以前"跟着 analyzer 活、从不复位"是假的 —— analyzer 实例的存活期
     * 比一次绑定长得多（`remember(scanner, bindGeneration)` 只在 scanner 或 bindGeneration 变时
     * 才换新实例），而 `scannerWorking` 从 false 翻回 true 那条自动恢复路径会让绑定 effect 重跑
     * 却不换实例，于是重绑之后再没有首帧行，读证据的人分不清「这次绑定没换来帧」和
     * 「换来帧了但这轮的标记早就烧掉了」。
     * 复位点是 [markBindStarted]（绑定成功后被调一次，天然的每绑定钩子）⇒ 语义是**每次绑定一行**。
     * ⚠️ 复位**不放进 analyzer 构造**：换实例与换绑定不等价，构造时置位盖不住上面那条重绑路径。
     * [frameCount] 也不跟着复位：它是 [ScanRecoveryPolicy] 内核算停用窗口的时间轴，
     * 复位就把窗口判据掐了；日志里的「第 N 帧」继续按全局帧号报。
     */
    private var firstFrameLogged = false

    /**
     * 绑定成功后调一次：健康度那几行取证要报"这是绑定后第多少毫秒发生的事"。
     * 顺带把首帧标志复位（T59b①）—— 每一轮绑定都该重新报一次"首帧已到达"。
     */
    fun markBindStarted(elapsedRealtimeMillis: Long) {
        bindElapsedMillis = elapsedRealtimeMillis
        firstFrameLogged = false
    }

    /**
     * 回到前台（ON_RESUME）：还配不给一次新的机会，额度在 [healthAfterPageVisible] 里判。
     *
     * 这一档盖的是"帧一帧都不到"的那种坏（切后台再回来、相机被别家占过）——
     * 那种时候任何按帧的判据都推不动自己，没有这条路就只剩退出重进。
     *
     * @return 相机扫码这一档现在是否可用（调用点照它写 `scannerWorking`）
     */
    fun recoverOnPageVisible(): Boolean {
        val next = healthAfterPageVisible(health)
        if (next === health) {
            // 额度用完了也留一行：否则"回来过"和"没回来过"在读证据时一模一样
            if (!scannerWorkingOf(health)) {
                Log.w(
                    TAG,
                    "回到前台但不再给解码器机会（额度 " +
                        "${health.pageVisibleRecoveries}/$MaxPageVisibleRecoveries 已用完，已收 $frameCount 帧）：" +
                        health.giveUpReason ?: "停用窗口内",
                )
            }
            return scannerWorkingOf(health)
        }
        health = next
        val working = scannerWorkingOf(next)
        Log.i(TAG, "回到前台：给解码器第 ${next.pageVisibleRecoveries}/$MaxPageVisibleRecoveries 次机会（已收 $frameCount 帧）")
        onWorkingChanged(working)
        return working
    }

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
        // ⑤① 同一个计数器干两件事：给内核当时间轴，兼当"帧到过"的证据。
        // 停用窗口里帧**照旧到达**（分析流不 unbind，只是不再喂解码器）—— 这正是
        // [decoderFrameAction] 能算出"窗口过完了"的前提。
        val frame = ++frameCount
        if (!firstFrameLogged) {
            // ⑤ 首帧标记：这颗标志每次绑定置一次（复位点在 [markBindStarted]，T59b①），
            // 所以这一行是"每次绑定一行"，不是"每帧一行"。
            // 它值钱的的地方在于把「相机没送帧」和「送帧了但解不出/被判停用」分开 ——
            // 没有这一行，这两种处境在读证据时长得一模一样。
            firstFrameLogged = true
            logFirstFrameArrived(frame)
        }
        if (decoderFrameAction(health, frame) == DecoderFrameAction.Skip) {
            image.close()
            return
        }
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
                    // 任务正常返回本身就是"这颗解码器还能用"的证据：先记健康度的账，
                    // 再管这一帧解出了什么（解不出东西是常态，不该改判据）
                    noteDecodeSucceeded()
                    val first = codes.firstOrNull()
                    val raw = first?.rawValue
                    if (raw == null) {
                        // ③ 以前这里是 `?.let {}` 一句：两种"没有原文"（一枚条码没解出文字 /
                        // 干脆没有条码）与"这一帧真的什么都没看见"在证据上完全同形，
                        // 于是用户报的「对准了没反应」连区分都区分不出来。
                        // 判据（要不要说话、多久说一次）在 [shouldLogValuelessBarcode]。
                        noteNoReadableValue(sawBarcodeWithoutText = first != null, frame = frame)
                    } else {
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
                    // ⚠️ 这个词的零命中由形状守卫盯着（ScanSubmissionGateTest ⑨）：
                    // 布尔死锁不许从任何一头复活，所以这里只记账、不再写那颗布尔。
                    handled = ScanHandled(DecodeFailurePayload, System.currentTimeMillis())
                    awaitingUserAction = true
                    noteDecodeFailed(frame)
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
            noteDecodeFailed(frame)
        }
    }

    /**
     * 一次解码失败的记账（①）。判据全在 [healthAfterDecodeFailure]，这里只做两件事：
     * 换引用、以及在档位真的翻面时留一行说得出原因的日志。
     *
     * 取时一律用 `elapsedRealtime`：分析器里读墙钟的口子被钉死成三处
     * （[ScanSubmissionGateTest] ⑨），那三处要的是提交闸门的账，不是这一档的。
     */
    private fun noteDecodeFailed(frame: Long) {
        val wasWorking = scannerWorkingOf(health)
        val next = healthAfterDecodeFailure(health, frame)
        if (next === health) return
        health = next
        val working = scannerWorkingOf(next)
        if (working != wasWorking || next.giveUpReason != null) {
            Log.w(
                TAG,
                "相机扫码这一档" + (if (working) "恢复" else "停用") + "：连错 ${next.consecutiveFailures} 帧" +
                    "（阈值 $ConsecutiveDecodeFailureLimit）/ 自动试回 ${next.suspensionCycles}" +
                    "/$MaxDecodeSuspensionCycles 轮 / 已收 $frame 帧 / 绑定后 ${sinceBindMillis()}ms" +
                    " / 原因 ${next.giveUpReason ?: "停用窗口 ${suspendWindowFrames(next.suspensionCycles)} 帧后放一帧去探"}",
            )
        }
        onWorkingChanged(working)
    }

    /** 一次正常返回：解码器自证还能用，停用与轮数一起清零（要不要换引用也是内核判） */
    private fun noteDecodeSucceeded() {
        val wasWorking = scannerWorkingOf(health)
        val next = healthAfterDecodeSuccess(health)
        if (next === health) return
        health = next
        val working = scannerWorkingOf(next)
        if (!wasWorking && working) {
            // 这一行就是 T59① 的正面证据：以前这一档是从回不去的
            Log.i(TAG, "相机扫码从停用里自己回来了（已收 $frameCount 帧，绑定后 ${sinceBindMillis()}ms）")
        }
        onWorkingChanged(working)
    }

    private fun sinceBindMillis(): Long {
        val at = bindElapsedMillis
        return if (at > 0L) SystemClock.elapsedRealtime() - at else -1L
    }

    /** 这一页到底收到过多少帧（⑤ 的证据本体；调用点只在留痕时读一次，不在按帧路径上） */
    fun framesArrived(): Long = frameCount

    /**
     * 首帧到达那一行（⑤）。级别必须是 Info 以上：用户那台机器（HyperOS）把 logcat 砍到
     * Info、release 又剥 Verbose，写成 `Log.d` 就等于没写（本页既有取证行同此口径，
     * 见 [ScanUiStatus] 的 KDoc）。
     *
     * 不进 release 取证登记名单（`ReleaseForensicLogSurvivalTest.SITES`）：那本名单是省电审计
     * §4.3/§4.4 的过滤条件清单，没有一行按扫码页的 tag 过滤。这一行的存活靠的是"仓库里
     * 没有任何删 `android.util.Log` 的 assume 规则"这条全局守卫（同一份名单的层 1 钉着），
     * 不需要把自己塞进别人的账本里。
     */
    private fun logFirstFrameArrived(frame: Long) {
        Log.i(TAG, "本轮绑定的首帧已到达分析器：第 $frame 帧（绑定后 ${sinceBindMillis()}ms）—— 相机在送帧")
    }

    /**
     * 「解出了条码，但那枚条码没有可读原文」（③ 的第一支）。
     *
     * 只在**真的看到一枚条码**时计数：空结果（画面里没码）是按帧的常态，一帧一行会把
     * logcat 冲干净，那种帧本来也不欠任何解释。要不要说话由 [shouldLogValuelessBarcode]
     * 判（第一次必说、之后每 [$ValuelessBarcodeLogStride] 次一次），这里只管数。
     *
     * 不升成 UI：这是按帧路径，做成状态就是每秒十几次重写组合。
     */
    private fun noteNoReadableValue(sawBarcodeWithoutText: Boolean, frame: Long) {
        if (!sawBarcodeWithoutText) return
        val total = ++valuelessCodes
        if (!shouldLogValuelessBarcode(total)) return
        Log.w(
            TAG,
            "解出条码却读不出原文：累计第 $total 次（第 $frame 帧）—— 这种帧投不进签到，" +
                "用户看到的就是「对准了但没反应」",
        )
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
