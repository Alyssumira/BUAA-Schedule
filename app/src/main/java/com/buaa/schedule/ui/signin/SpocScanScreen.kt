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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.core.designsystem.GlassSurface
import com.buaa.schedule.core.designsystem.GlassTopBar
import com.buaa.schedule.core.designsystem.GlassVariant
import com.buaa.schedule.core.designsystem.LocalSemanticColors
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
 * 相机是主路径。后两条**不是**在任何设备上都还在：MLKit 的解码库在 release 包里只带
 * arm64 一档（见 docs/BUAA_SPOC_SIGNIN_PLAN.md §1.2 与 app/build.gradle.kts 末尾的
 * `androidComponents` 块），而相册识别送进的是**同一个** `scanner.process(...)` ——
 * 缺库的设备上相册也解不出任何东西，这一页真正剩下的只有手输签到码。
 *
 * 这件事由 [BarhopperNativeLibProbe] 在任何一次解码调用之前判掉（T24）：不能等 ML Kit
 * 自己抛，它是在自己的工作线程上 `System.loadLibrary` 的，那个 `UnsatisfiedLinkError`
 * 这一页任何一处 catch 都接不住，只会顺着线程默认处理器把进程打死。
 * 判定为不可用时这里的效果是 `scanner` 直接为 null：相机分析器不建、相册入口不再承诺能用、
 * 文案指向手输。arm64 上探针只会回答"可用"，这一页的组合与绑定次序和改动前一致。
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
    // 解码器"跑起来之后"坏了（analyzer 收到 ML Kit 的失败回调）：相机这条先停用，
    // scanner 还在，所以相册识别仍然承诺得起
    var scannerWorking by remember { mutableStateOf(true) }
    // 解码器"根本不在包里"（T24）：探针**已判定**不可用才 true —— 未判定不是不可用，
    // 那样会把 arm64 上预热还没跑到那一档的窗口变成一帧降级页。
    // 这一档比 scannerWorking 更彻底：相册识别用的是同一个 scanner，所以它一起没。
    var decoderMissing by remember { mutableStateOf(barhopperNativeLib.verdict == NativeLibVerdict.Missing) }
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

    LaunchedEffect(Unit) {
        provider = cameraProviderOrNull(context)
    }

    // CompositionLocal 只能在组合期读，绑定发生在协程里，所以先把旋转值取出来
    val view = LocalView.current
    val targetRotation = remember(view) { view.display?.rotation ?: Surface.ROTATION_0 }
    val busy = state is SignInState.Resolving || state is SignInState.Submitting
    // 相机这条路径是否真的在跑：取景框只在它有效时出现，
    // 退化到相册/手输时再压一层暗区就只是噪音
    val cameraLive = scanner != null && scannerWorking && granted && cameraError == null

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
            // 否则三键导航机上两颗按钮被导航键压住（M6）
            Column(
                modifier = Modifier.navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(DesignTokens.spaceS),
            ) {
                val hintText = when {
                    // 解码器整条链都不在（T24）：相机与相册用的是同一个 scanner，
                    // 再提"从相册选那张二维码"就是把用户往死路上引 —— 只剩手输
                    decoderMissing -> "这份安装包没带这台设备那一档的扫码解码库，相机和相册都解不出二维码，只能手输签到码。"
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
                    // 栏内两颗动作一律 48dp 触控下限（M4）
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
                    // 相机能用时手输是最后的兜底，压在最弱一档（TextButton）正合适；
                    // 相机不可用时它和相册就是仅有的两条路，最弱档等于把它藏起来
                    if (cameraLive) {
                        TextButton(
                            onClick = { showManualInput = true },
                            enabled = !busy,
                            modifier = barAction,
                        ) { Text("手输签到码") }
                    } else {
                        OutlinedButton(
                            onClick = { showManualInput = true },
                            enabled = !busy,
                            modifier = barAction,
                        ) { Text("手输签到码") }
                    }
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
                        color = when (state) {
                            // 与卡片 tint 同源：绿卡配蓝字会读成两件事
                            is SignInState.Signed -> LocalSemanticColors.current.success
                            is SignInState.Failed -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
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
                    // 空串提交等于白跑一次状态机，再被失败卡告知"这不是签到码"
                    enabled = manualCode.isNotBlank(),
                ) { Text("签到") }
            },
            dismissButton = {
                TextButton(onClick = { showManualInput = false }) { Text("取消") }
            },
        )
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
            // close 只能挂到任务结束之后：InputImage 只持有 mediaImage 的引用，MLKit 是在
            // 自己的工作线程上才去读 getPlanes() 的。放在 finally 里立即 close，那一帧就报
            // IllegalStateException: Image is already closed，被下面的失败分支当成
            // 「这台设备用不了相机扫码」而永久关掉整页的扫码能力。
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
                .addOnCompleteListener { image.close() }
        } catch (e: Throwable) {
            // 只盖得住 process() 的**同步**部分：烂 InputImage、Image 已 close 这类。
            // ⚠️ 缺库那种 UnsatisfiedLinkError 这里盖不住（T24 订正）—— 它是 ML Kit 在
            // 自己的 worker 上调 System.loadLibrary 时抛的，本方法早就返回了。
            // 缺库不走到这里：BarhopperNativeLibProbe 判定不可用时 scanner 为 null、
            // 分析流根本不绑，而绑定之前又必须先 awaitDecided()（见 SpocScanScreen）。
            // 所以能拿到回调的 scanner 一定是"已判定可用"的那一颗，这里只剩运行期故障。
            image.close()
            consumed = true
            onFailure()
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
