package com.buaa.schedule.ui.signin

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.core.designsystem.GlassSurface
import com.buaa.schedule.core.designsystem.GlassTopBar
import com.buaa.schedule.core.designsystem.GlassVariant
import com.buaa.schedule.core.designsystem.ModalTransition
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * 智学北航扫码签到页。
 *
 * 三条入口指向同一个状态机（[SignInViewModel]）：相机实时解码、相册识图、手输签到码。
 * 相机是主路径，后两条不是装饰 —— MLKit 的 native 库只打进 arm64（见
 * docs/BUAA_SPOC_SIGNIN_PLAN.md §1.2），其余 ABI 上扫码器一加载就会抛
 * [UnsatisfiedLinkError]；另外教室投影反光、摄像头脏了的时候，相册识别是唯一还能用的路子。
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

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    // MLKit 的 so 只有 arm64：别的架构上这一页要能退化成相册 + 手输，而不是崩
    var scannerWorking by remember { mutableStateOf(true) }
    var showManualInput by remember { mutableStateOf(false) }
    // 输入态提到外层并 rememberSaveable：弹窗曾把 code 记在 if 分支里，
    // 转一次屏输入框就清空（分支内的 remember 随子树一起没了）
    var manualCode by rememberSaveable { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }
    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val scanner = remember {
        runCatching {
            BarcodeScanning.getClient(
                // 只解 QR：多解一种格式会给每一帧多加一次解码开销
                BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
            )
        }.getOrNull()
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

    LaunchedEffect(Unit) {
        provider = cameraProviderOrNull(context)
    }

    // CompositionLocal 只能在组合期读，绑定发生在协程里，所以先把旋转值取出来
    val view = LocalView.current
    val targetRotation = remember(view) { view.display?.rotation ?: Surface.ROTATION_0 }
    val busy = state is SignInState.Resolving || state is SignInState.Submitting

    LaunchedEffect(granted, provider, scannerWorking, analyzer) {
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
            // 没有后置摄像头（平板/模拟器）：这一页只剩相册与手输两条路，不算错误
            cameraError = it.message
        }
    }

    // 一帧二维码能被连续解几十次，analyzer 里用 consumed 锁死；回到待扫状态时要手动放行
    LaunchedEffect(state) {
        if (state is SignInState.Idle) analyzer?.resume()
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null && scanner != null) {
            runCatching { InputImage.fromFilePath(context, uri) }
                .onSuccess { image ->
                    scanner.process(image).addOnSuccessListener { codes ->
                        codes.firstOrNull()?.rawValue?.let { viewModel.signIn(it) }
                    }
                }
                // 相册里那张图太大 / 读不出来时，别让整个页面跟着倒
                .onFailure { cameraError = "读不出那张图：${it.message}" }
        }
    }

    BackHandler(enabled = busy, onBack = { })

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )

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
            Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceS)) {
                val hintText = when {
                    scanner == null || !scannerWorking -> "这台设备用不了相机扫码，请从相册选那张二维码，或直接输入签到码。"
                    !granted -> "没有相机权限，无法扫码。请在系统设置里放行，或改用下面两个入口。"
                    cameraError != null -> "相机不可用（$cameraError），请改用下面两个入口。"
                    else -> null
                }
                if (hintText != null) {
                    Text(
                        text = hintText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.spaceS)) {
                    Button(
                        onClick = {
                            galleryLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        enabled = scanner != null && !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("相册识别") }
                    TextButton(
                        onClick = { showManualInput = true },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("手输签到码") }
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
            // 失败结果卡升 ALERT + error 语义色（与登录页状态卡、冲突横幅同一档），
            // 进行中/成功仍是中性 PANEL
            GlassSurface(
                variant = if (failed) GlassVariant.ALERT else GlassVariant.PANEL,
                semanticTint = if (failed) MaterialTheme.colorScheme.error else null,
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
                        color = when (state) {
                            is SignInState.Signed -> MaterialTheme.colorScheme.primary
                            is SignInState.Failed -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    when (val s = state) {
                        is SignInState.Failed -> Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.spaceS)) {
                            if (s.relogin) {
                                Button(onClick = onNeedLogin, modifier = Modifier.weight(1f)) { Text("去登录") }
                            }
                            TextButton(
                                onClick = { viewModel.reset() },
                                modifier = Modifier.weight(1f),
                            ) { Text("重新扫码") }
                        }
                        is SignInState.Signed -> Button(
                            onClick = { viewModel.reset() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("继续扫码") }
                        else -> Unit
                    }
                }
            }
        }
    }

    ModalTransition(open = showManualInput) { modal ->
        AlertDialog(
            modifier = modal,
            onDismissRequest = { showManualInput = false },
            title = { Text("手输签到码") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceS)) {
                    OutlinedTextField(
                        value = manualCode,
                        onValueChange = { manualCode = it },
                        label = { Text("签到码 / 二维码里的链接") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Text(
                        text = "投影看不清时才用得上：签到码是二维码正下方那串字母数字。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showManualInput = false
                        viewModel.signIn(manualCode)
                        manualCode = ""
                    },
                ) { Text("签到") }
            },
            dismissButton = {
                TextButton(onClick = { showManualInput = false }) { Text("取消") }
            },
        )
    }
}

/** 分析流固定用单线程：MLKit 自己会排队，多线程只会让 close 时序更难保证 */
private val analysisExecutor = Executors.newSingleThreadExecutor()

/**
 * 相机帧 → QR 原文。
 *
 * [consumed] 是这一页唯一的「一次只签一个」闸门：CameraX 按帧回调，同一张二维码
 * 在预览里能被解出几十次，不锁住就会连着发几十次提交请求。
 */
private class QrCodeAnalyzer(
    private val scanner: BarcodeScanner,
    private val onCode: (String) -> Unit,
    private val onFailure: () -> Unit,
) : ImageAnalysis.Analyzer {

    @Volatile private var consumed = false

    fun resume() {
        consumed = false
    }

    // 只在本函数内消化这个 opt-in：标 @ExperimentalGetImage 会把它传染给调用方，
    // 而调用方是框架经 ImageAnalysis.Analyzer 接口回调的，无处可标。
    // 必须是 androidx 那个 @OptIn —— lint 的 UnsafeOptInUsageError 只认它，kotlin.OptIn 压不住
    @androidx.annotation.OptIn(markerClass = [androidx.camera.core.ExperimentalGetImage::class])
    override fun analyze(image: ImageProxy) {
        if (consumed) {
            image.close()
            return
        }
        val mediaImage = image.image
        if (mediaImage == null) {
            image.close()
            return
        }
        try {
            scanner.process(InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees))
                .addOnSuccessListener { codes ->
                    codes.firstOrNull()?.rawValue?.let {
                        // 先置位再回调：回调里就会开始发请求，这期间新帧可能已经进来了
                        consumed = true
                        onCode(it)
                    }
                }
                .addOnFailureListener {
                    // 解不出来是常态（画面糊、没对准），只有 native 缺失这种才值得降级；
                    // 但连 MLKit 都报错时继续按帧重试只是白耗电，交给界面提示换入口
                    consumed = true
                    onFailure()
                }
        } catch (e: Throwable) {
            // UnsatisfiedLinkError 会从 process() 里抛出来：这台设备的 ABI 没带扫码库
            consumed = true
            onFailure()
        } finally {
            image.close()
        }
    }
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
