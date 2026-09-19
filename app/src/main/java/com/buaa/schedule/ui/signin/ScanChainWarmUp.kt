package com.buaa.schedule.ui.signin

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.Choreographer
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.graphics.createBitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 首帧之后把扫码链预热一次（T18）。
 *
 * ## 要藏掉的是哪一下
 *
 * [SpocScanScreen] 第一次解帧时才发生的事有三件，全都不在"应用启动"这笔账上，
 * 而是砸在用户点开扫码页的那一瞬间：
 *
 * 1. **解码器 `.so` 的 `dlopen`**。反编译 `barcode-scanning:17.3.0` 确认：
 *    `com.google.android.libraries.barhopper.BarhopperV3` 的
 *    `System.loadLibrary("barhopper_v3")` 写在**实例构造函数的第一句**里
 *    （`<clinit>` 里什么都没有），而构造它的是 bundled 那条流水线
 *    （`com.google.mlkit.vision.barcode.bundled.internal.zza`）。
 *    所以 `MlKitInitProvider` 在起手把 ML Kit 的 **Java 侧**拉起来之后，
 *    这颗 `.so` 依然要等**第一次真正解码**才映射进来 ——
 *    起手那颗 provider 一点也帮不上这一处。
 * 2. `ProcessCameraProvider.getInstance()`：CameraX 的 provider 初始化 + 相机枚举
 *    （扫码页里那个 `cameraProviderOrNull` 就在等它）。
 * 3. CameraX 与 ML Kit 那批类的首次加载与 verifier 工作。
 *
 * ## 首帧的判据
 *
 * `Choreographer` 的一次 `doFrame` 按 input → **animation** → insets → **traversal**
 * 的顺序跑，Compose 的组合/测量排在 animation 阶段、真正的首帧绘制排在同一帧的
 * traversal 阶段。所以：
 *
 * - 在 `onCreate` 里排下的第一个帧回调，跑在**首帧那一趟 doFrame 的 animation 阶段** ——
 *   那时首帧还没画，这里动手就等于把成本搬回更早的主线程（明确不要的做法）；
 * - 在那个回调里再排一次，第二次回调就跑在**下一帧**的 animation 阶段 ——
 *   上一帧（含首帧的 traversal/draw）已经整趟走完。
 *
 * 这就是"首帧之后"的可判据版本：两次 `postFrameCallback`，不是 `onCreate`，
 * 也不是靠 `Thread.sleep` 猜一个时间。
 *
 * ## 代价与开关
 *
 * - 主线程只多两次帧回调（各一次注册 + 一次 `launch` 派发），微秒量级。
 * - 后台侧：一次 64×64 的空白位图 + 一次注定解不出东西的解码 + 一次 provider 初始化，
 *   毫秒量级；两步各自带超时上限，最坏多占一个 IO 线程
 *   [CAMERA_TIMEOUT_SECONDS] + [DECODE_TIMEOUT_SECONDS] 秒，绝不占主线程。
 * - 常驻内存：`libbarhopper_v3.so` 的映射会留在进程里（`System.loadLibrary` 之后
 *   没有卸载的 API），ML Kit 与 CameraX 的类同理。**解码器实例本身不常驻** ——
 *   `close()` 照旧调用，留下的只有已经映射进来的那些。
 *   用户不打开扫码页时，这笔内存原本是零；这是本项唯一的净增代价。
 * - **一键关掉**：[warmUp] 里那两行调用各自删掉一行就关一档
 *   （相机一档、解码器一档），不需要新增任何配置项。取舍与量法见
 *   docs/PERF-STARTUP-2026-09-19.md §3。
 *
 * ## 红线（有守卫测试钉着）
 *
 * 只做类加载 / `dlopen` / `ProcessCameraProvider.getInstance()` 这类**不弹框**的准备：
 * 绝不申请 `CAMERA` 权限（授权归 [SpocScanScreen] 自己现取现用）、绝不打开相机、
 * 绝不做 `bindToLifecycle`。预热失败一律静默：这台设备用不了相机扫码时的降级路径
 * （相册识别 / 手输签到码）由那一页自己负责，这里没有资格替它决定。
 */
internal object ScanChainWarmUp {

    private const val TAG = "ScanChainWarmUp"

    /** 相机 provider 初始化的上限：超时就当没预热成，不追着用户跑 */
    internal const val CAMERA_TIMEOUT_SECONDS = 5L

    /** 一次空图解码的上限，同上 */
    internal const val DECODE_TIMEOUT_SECONDS = 5L

    /** 空白预热图的边长：够 ML Kit 走完"建识别器 → 解一帧"的全部前置，小到可以忽略 */
    private const val WARM_UP_IMAGE_SIDE = 64

    /**
     * 进程内只付一次的凭据。
     *
     * 转屏会重建 MainActivity，`scheduleAfterFirstFrame` 也就被调第二次 ——
     * 这里夹住。`compareAndSet` 本身就是一次内存序，不需要额外加锁。
     */
    private val claimed = AtomicBoolean(false)

    /** 只有把门闩翻过来的那一次返回 true（给守卫测试直接断言幂等用） */
    internal fun claim(): Boolean = claimed.compareAndSet(false, true)

    /**
     * 排一次"首帧之后"的预热。主线程侧只有两次帧回调注册，其余全在后台。
     *
     * @param scope 进程级作用域（`BUAAApplication.applicationScope`）：转屏销毁 Activity
     *   不该把一次跑到一半的预热带走 —— 门闩已经翻过去，被掐断就等于再也不会补做。
     */
    fun scheduleAfterFirstFrame(scope: CoroutineScope, context: Context) {
        if (!claim()) return
        val appContext = context.applicationContext
        Choreographer.getInstance().postFrameCallback {
            // 这一帧的 animation 阶段：首帧还没画完，不许在这里动手
            Choreographer.getInstance().postFrameCallback {
                scope.launch { warmUp(appContext) }
            }
        }
    }

    /**
     * 本体。**任何异常都不许逃出这个函数**：[scheduleAfterFirstFrame] 用的是
     * `SupervisorJob` 且没有 `CoroutineExceptionHandler` 的进程级作用域，
     * 逃出这里等于顺着线程的默认处理器把整个进程打死（同 `BUAAApplication.onCreate` 那条链的口径）。
     * 用 `catch (Throwable)` 而不是 `runCatching`：`UnsatisfiedLinkError` 是 Error，
     * 非 arm64 设备上就是它（[SpocScanScreen] 里那条降级走的也是同一个 catch）。
     */
    internal suspend fun warmUp(appContext: Context) {
        withContext(Dispatchers.IO) {
            // 两档各一次，谁先谁后无所谓：它们之间没有任何依赖。
            // 关掉某一档 = 删掉对应那一行。
            warmUpCameraProvider(appContext)
            warmUpBarcodeDecoder()
        }
    }

    /**
     * 相机侧：把 `ProcessCameraProvider` 建起来。
     *
     * 只做 `getInstance()` + 在自己的协程里等它的 future（阻塞的是 IO 线程，
     * 主线程一步不等）。这一步不打开相机、不碰权限：CameraX 到这里只做了
     * 服务绑定与相机枚举，`openCamera` 要等到扫码页 `bindToLifecycle` 之后。
     * 之后扫码页那次 `cameraProviderOrNull` 拿到的是同一个单例、已经完成的 future。
     */
    private suspend fun warmUpCameraProvider(appContext: Context) {
        try {
            val provider = ProcessCameraProvider.getInstance(appContext)
                .get(CAMERA_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            Log.d(TAG, "相机 provider 预热完成：${provider != null}")
        } catch (t: Throwable) {
            // 没有相机 / CameraX 在这台设备上不可用 / 超时：扫码页自己会给出降级，这里闭嘴
            Log.d(TAG, "相机 provider 预热未成功（忽略）", t)
        }
    }

    /**
     * 解码器侧：真的解一帧空白图，把 `BarhopperV3` 的构造走到，从而逼出
     * `System.loadLibrary("barhopper_v3")` 那一次 `dlopen`。
     *
     * 为什么不是只 `BarcodeScanning.getClient()` 就完事：`loadLibrary` 在
     * `BarhopperV3` 的**实例构造函数**里（见类注释的字节码结论），
     * 而那个实例是第一次解码时才 new 的 —— 光建客户端碰不到它。
     *
     * `close()` 照旧调用：这张卡要留在进程里的只有 `.so` 的映射与类，
     * 不是一颗常驻的检测器。
     */
    private suspend fun warmUpBarcodeDecoder() {
        var scanner: BarcodeScanner? = null
        try {
            scanner = BarcodeScanning.getClient(
                // 与扫码页同一档格式：预热出来的是同一条代码路径，
                // 解多种格式只会给这一帧多加开销
                BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
            )
            // 全零的空白图：任何一帧都解不出东西，但"建识别器 → 送进 native → 拿回空结果"
            // 这条路会完整走一遍。ARGB_8888 是 BarhopperV3.recognize 的原生档，
            // 省掉它内部那次格式转换（转换会把这帧的量再乘一遍系数）。
            // createBitmap 走 core-ktx 那一份（与 SceneBackground / WidgetBackgroundRenderer 同写法）
            val bitmap = createBitmap(WARM_UP_IMAGE_SIDE, WARM_UP_IMAGE_SIDE, Bitmap.Config.ARGB_8888)
            val task = scanner.process(InputImage.fromBitmap(bitmap, 0))
            // 阻塞等：这一行跑在 Dispatchers.IO 上，主线程不参与
            Tasks.await(task, DECODE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            Log.d(TAG, "解码器预热完成")
        } catch (t: Throwable) {
            Log.d(TAG, "解码器预热未成功（忽略）", t)
        } finally {
            runCatching { scanner?.close() }
        }
    }
}
