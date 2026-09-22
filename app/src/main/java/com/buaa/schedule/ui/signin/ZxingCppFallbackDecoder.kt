package com.buaa.schedule.ui.signin

import androidx.camera.core.ImageProxy
import zxingcpp.BarcodeReader

/**
 * 第二引擎（zxing-cpp）在本仓的那一枚薄壳（T66）。
 *
 * ## 它只干三件事，而且一件都不判
 *
 * ① 把「这颗解码器在这台设备上到底起不起来」判成 [SecondEngineProbe] 的三档并缓存；
 * ② 起得来就把帧递给它、拿回原文；
 * ③ 把里面抛出的一切接住，翻成档位/失败原因，绝不让它逃出调用线程。
 *
 * **什么时候该叫它、叫几次、什么时候不再叫 —— 全在 [ScanSecondEnginePolicy]**，这里一个判断
 * 都没有（仓库口径：判据停在纯内核那一侧，这一颗只是设备与库的适配器）。
 *
 * ## 为什么探针就是「构造那一下」
 *
 * zxing-cpp 的 Android wrapper（v3.1.1 源码逐行核过）把
 * `System.loadLibrary("zxingcpp_android")` 写在 `BarcodeReader` 的 **`init` 块**里，
 * 也就是**构造函数**里。于是"构造成功"与"这台设备上这份包里有那颗 `.so`"是同一件事
 * —— 不需要第二处去 `loadLibrary`（那一处的存在理由由 `BarhopperNativeLibProbeTest` ⑥ 钉着，
 * 全仓只许那一颗真去 load），也不需要猜 `Build.SUPPORTED_ABIS`（[BarhopperNativeLibProbe]
 * 的类注释已经写清为什么猜 ABI 一定是错的：它答的是设备支持什么，不是这份包里带了什么）。
 *
 * 与 barhopper 那一颗的**唯一**区别，也是这一颗可以不做前置探针的理由：barhopper 的 load
 * 发生在 **ML Kit 自己的 worker 线程**上，我们调用侧的 catch 接不到（实测 release x86_64
 * 模拟器 FATAL），所以必须提前判；而这里的构造发生在**我们自己的分析线程、我们自己的 try 里**，
 * 接得住。于是这一颗的纪律是：**未判定就当可用**（[SecondEngineProbe.Pending] 放行），
 * 第一次真要用的那一下现场把结论做出来 —— 用户不为兜底多等一步，兜底没被叫醒时也不占
 * 常驻内存（那颗 `.so` 一旦 dlopen 就留在进程里，见 [ScanChainWarmUp] 的"代价"那一节；
 * 正因如此这一颗**故意不进预热**，取舍写在收工报告里）。
 *
 * ## 线程纪律（写死在这里，不靠调用点自觉）
 *
 * [ensureReady] 与 [decode] 只许跑在**分析流那一颗单线程 executor** 上：
 * 唯一的写者就是那一个线程，所以两处状态用 `@Volatile` 整枚换引用已经够了（同
 * [QrCodeAnalyzer] 的 [ScanAssistState] 口径）。**不许**在绑定路径、主线程、或 ML Kit 的
 * 回调线程上调它们 —— 那会变成两个写者，「第几次补解」和「连续第几次失手」就会各说各话。
 */
internal class ZxingCppFallbackDecoder(private val open: () -> BarcodeReader) {

    @Volatile
    private var decided: SecondEngineProbe = SecondEngineProbe.Pending

    /** 构造出来的解码器本体。null = 还没构造或构造失败（结论以 [decided] 为准） */
    @Volatile
    private var reader: BarcodeReader? = null

    /** 最近一次失败的原文（只给取证行用，不参与任何判断） */
    @Volatile
    var lastFailure: String? = null
        private set

    /** 一次 volatile 读：不构造、不阻塞，所以绑定路径也能廉价看一眼 */
    fun verdict(): SecondEngineProbe = decided

    /** 非 Unusable = 允许试着用一次（Pending 放行：第一次真用的那一下就是判定本身） */
    fun mayTry(): Boolean = decided != SecondEngineProbe.Unusable

    /**
     * 把结论做到手。幂等：整个进程里构造动作至多成功一次、失败也只撞一次锁。
     *
     * 双检 + `synchronized` 的写法和 [BarhopperNativeLibProbe.decideNow] 一致 —— 虽然按当前的
     * 线程纪律这里只有一个写者，但"只有一个写者"是调用点的性质不是这一颗的性质，
     * 万一哪天第二引擎挪线程，这把锁就是那档的兜底，代价是一把从未争抢的锁。
     */
    fun ensureReady(): SecondEngineProbe {
        decided.takeIf { it != SecondEngineProbe.Pending }?.let { return it }
        synchronized(this) {
            decided.takeIf { it != SecondEngineProbe.Pending }?.let { return it }
            val built = runCatching { open() }
            // 判据收进内核（[secondEngineProbeOnConstruct]）：这里只递"构造成没成"这个事实
            decided = secondEngineProbeOnConstruct(built.isSuccess)
            reader = built.getOrNull()
            if (built.isFailure) {
                lastFailure = built.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
                    ?: "未知失败"
            }
            return decided
        }
    }

    /**
     * 解一帧，返回拿到的第一份非空原文；null = 这一帧它也没解出来（或它已经不在台上）。
     *
     * 三处要紧的写法，都不是随手抄的：
     * - **同步、就地读完**：`BarcodeReader.read(ImageProxy)` 内部是一次
     *   `readYBuffer(image.planes[0].buffer, rowStride, cropRect…, imageInfo.rotationDegrees, options)`
     *   的 **external 同步调用**（v3.1.1 源码核过：`read(image: ImageProxy)` 里除了这一句没有别的
     *   出路，它不把 proxy 或 buffer 存进任何字段）—— 也就是说像素在这句返回前就已经被 native
     *   读完了。这是本卡敢走"兜底在 close 之前就地拿到它需要的字节"这条路的依据：
     *   不用为它复制一份 1.2 MB 的亮度面，也不动 `image.close()` 的时机。旋转 native 自己转，
     *   与 ML Kit 那侧 `InputImage.fromMediaImage(media, rotationDegrees)` 是同一个口径。
     * - **只喂 QR 档**（见 [zxingCppFallbackOptions]）：多解一种格式就是给每一次补解加开销，
     *   而这一页只有二维码这一种码。
     * - **一切异常都接住**：缺库、`check(image.format in supportedYUVFormats)` 那一句抛
     *   IllegalStateException（有的设备/有的时刻给的是 RGBA 档）、native 里出来的任何东西 ——
     *   逃出 [QrCodeAnalyzer.analyze] 就等于把整页扫码带走，而这颗引擎的全部身份是"兜底"。
     *   抛出来的东西顺手记进 [lastFailure]，好让取证行说得出**为什么**没有第二次。
     *
     * [formatOnly] 是抛错的性质：`IllegalStateException`/`IllegalArgumentException` 在这一点上
     * 说的是"这条流它接不了"（帧格式），那是每绑定都不会变的设备事实 ⇒ 判整颗引擎不可用；
     * 其余（OOM、native 内部）只算这一发失手，让连击判死去管 —— 拿一次抽风判死一整轮，
     * 是这一页修过三轮的那类"把暂时说成永久"。
     */
    fun decode(image: ImageProxy): String? {
        val active = reader ?: return null
        // 先把上一次的失败原文清掉再干活：[lastFailure] 只在抛的那一下写，
        // 不清就会在"这一发只是没解出来"的那一行里带上上一发的异常 —— 取证行带上不属于
        // 本帧的原因是谎话，而这一页修过三轮的病就叫两本账各说各话。
        lastFailure = null
        val results = runCatching { active.read(image) }
        val failure = results.exceptionOrNull()
        if (failure != null) {
            val formatRejected = failure is IllegalStateException || failure is IllegalArgumentException
            if (formatRejected) {
                decided = SecondEngineProbe.Unusable
                reader = null
            }
            lastFailure = "${failure.javaClass.simpleName}: ${failure.message}"
            return null
        }
        // 只要**有原文**的那一枚：zxing-cpp 的 Result 在解不出时会带 error 而不是抛，
        // 空文本/纯空白一律不算命中（那是"看见了像码的东西"，不是解出来了）
        val text = results.getOrNull()?.firstOrNull { !it.text.isNullOrBlank() }?.text
        return text
    }

    /** 最近一次补解在 zxing-cpp 内部计的运行时间（ms）。-1 = 还没解过或拿不到。 */
    fun lastReadMillis(): Int = reader?.lastReadTime ?: -1
}

/**
 * 兜底那一次的档位：把「免费的重试」全部打开，格式收到 QR。
 *
 * 逐项理由（对应 468 帧基准里 zxing-cpp 唯一值回包体代价的那一半能力）：
 * - `tryHarder`：更密的扫描线/更多的定位尝试 —— 糊码档（64.1% 那一档）主要靠它；
 * - `tryInvert`：**反色**图（投影仪白底黑码的补片、屏幕反光后的暗码）只有这条路；
 * - `tryDownscale` + 默认 `downscaleFactor=3`：大码先缩小再找定位图形；
 * - `tryRotate`：旋转不变性交给库自己做，而不是我们自己转像素；
 * - `tryDenoise` **不开**：去噪是给极脏的静态图准备的，代价是按帧的，兜底付不起；
 * - `maxNumberOfSymbols = 1`：这一页一次只要一枚码（ML Kit 那侧也只取 firstOrNull）。
 *
 * `formats` 只留 QR_CODE：签到码只有二维码一种，多开一档等于给每一次补解加一份通用开销。
 */
private fun zxingCppFallbackOptions(): BarcodeReader.Options = BarcodeReader.Options(
    formats = setOf(BarcodeReader.Format.QR_CODE),
    tryHarder = true,
    tryRotate = true,
    tryInvert = true,
    tryDownscale = true,
    maxNumberOfSymbols = 1,
)

/**
 * 进程内那一份第二引擎。
 *
 * 与 [barhopperNativeLib] 同一个 seam 口径：写成 `var` 是给宿主 JVM 留的口子（那里没有
 * 那颗 `.so`，`open` 必然抛 ⇒ 正好用来跑"构造失败 ⇒ 三档判定"那一条真代码），
 * 生产侧没有任何一处会重新赋值它。
 */
internal var zxingCppFallback: ZxingCppFallbackDecoder = ZxingCppFallbackDecoder {
    BarcodeReader(zxingCppFallbackOptions())
}
