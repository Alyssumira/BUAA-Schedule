package com.buaa.schedule.ui.signin

import java.io.File
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫码链预热的形状守卫（T18）。
 *
 * 本模块单测没有 Robolectric：`Choreographer.getInstance()` 一调就是 android.jar 里那个抛
 * "not mocked" 的桩，所以"跑在首帧之后"这件事 JVM 里跑不出来，只能按源码形状核对
 * （手法与 `ColdStartRebuildWiringTest` 一致：读 .kt、配平取函数体、**找不到锚点就抛** ——
 * `assumeTrue` 式的跳过等于没有守卫）。
 *
 * 真正能在 JVM 里跑出来的是"进程内只付一次"：[ScanChainWarmUp.claim] 背后就是一颗
 * `AtomicBoolean`，八线程抢一次门闩、只许一个人赢。这条比任何形状核对都硬，因为它测的
 * 就是那段代码（T24 之后 ⑨⑩ 也走同一条路：真调解码那一档，靠注入的探针决定闸门）。
 *
 * 守卫要挡住的坏形状，都不红：
 * ① 把预热挪回 `onCreate` 里同步做 —— 那不是"首帧之后"，那是把成本搬到**更早**的主线程；
 * ② 只 `BarcodeScanning.getClient()` 就以为预热到了 —— `libbarhopper_v3.so` 的
 *    `System.loadLibrary` 写在 `BarhopperV3` 的**实例构造函数**里，那个实例第一次真解码
 *    才 new，光建客户端碰不到它（这是反编译确认过的，也是这张卡最容易白做的地方）；
 * ③ 顺手申请相机权限 / 开相机 / `bindToLifecycle` —— 用户没点扫码页就该什么都不弹；
 * ④ 异常逃出 [ScanChainWarmUp.warmUp] —— 它跑在 `BUAAApplication.applicationScope` 上，
 *    那个作用域是 `SupervisorJob + Dispatchers.IO` 且**没有** `CoroutineExceptionHandler`，
 *    逃出去就是顺着线程默认处理器把整个进程打死；
 * ⑤ 把两档换回"相机那一档先跑" —— 两档之间确实没有数据依赖，看着像谁先谁后都行，可它们共用
 *    同一段"用户还没点进扫码页"的窗口，而相机档在相机枚举慢的设备上会把 5 秒上限**整额吃掉**
 *    （buaa36 实测：`TimeoutException ... ProcessCameraProvider-initializeCameraX`，
 *    见 docs/PERF-STARTUP-2026-09-19.md §8 ③），把只值约 340 ms 的那一下顶到它后面就等于没跑。
 *    这条是 T18b 量完设备之后补的。
 *
 * T24 之后这里还多两条形影不离的守卫（⑨⑩）：**探针判定「这份包里没有
 * libbarhopper_v3.so」时，解码那一档一次 ML Kit 调用都不发**。这条只能真跑一遍才量得到 ——
 * 源码形状核对只量得到「闸门写在调用之前」，量不到「零次」。跑法是注入探针的加载动作、
 * 直接调 `warmUpBarcodeDecoder()`（因此它是 `internal`），然后看它有没有安静地返回：
 * 在 JVM 里越过闸门必然抛（android.jar 是桩，`createBitmap` 与 catch 里那句 `Log.d`
 * 都是 "not mocked"），所以「没抛」就是「没走到 ML Kit」的证据。观测通道本身由 ⑩ 校准：
 * 闸门放开时若不再抛，⑩ 先红，提醒这条通道断了（例如哪天有人开了
 * `isReturnDefaultValues`，或把 catch 里的日志换掉）。
 */
class ScanChainWarmUpTest {

    /** ① 进程内只付一次：八线程抢门闩，恰好一个人赢（这条测的是真代码，不是形状） */
    @Test
    fun warmUpSlotIsGrantedExactlyOncePerProcess() {
        val threads = 8
        val barrier = CyclicBarrier(threads)
        val winners = AtomicInteger(0)
        val workers = (1..threads).map {
            Thread {
                barrier.await()
                if (ScanChainWarmUp.claim()) winners.incrementAndGet()
            }.also { it.start() }
        }
        workers.forEach { it.join() }
        assertEquals(
            "预热门闩被 " + winners.get() + " 个线程抢到，只能 1 个：" +
                "转屏会再调一次 scheduleAfterFirstFrame，多付一次就等于把这笔账又搬回用户眼前",
            1,
            winners.get(),
        )
        // 门闩已经翻过去：后面任何一次 claim 都必须为 false
        assertTrue("门闩在第一次之后还能被翻开，预热就会在一个进程里付多遍", !ScanChainWarmUp.claim())
    }

    /** ② 排期：主线程侧只有两次帧回调，活由第二次派发到后台作用域 */
    @Test
    fun scheduleDefersPastTheFirstFrameAndHandsTheWorkToTheCallerScope() {
        val code = normalize(withoutComments(readSource(WARM_UP_FILE)))
        val body = balancedBlock(code, "fun scheduleAfterFirstFrame(scope: CoroutineScope, context: Context)")

        assertEquals(
            "帧回调注册了 ${count(body, "postFrameCallback")} 次，必须是 2 次：一次跑在首帧那一趟 " +
                "doFrame 的 animation 阶段（那时首帧还没画），在它里面再排一次才落到下一帧：\n$body",
            2,
            count(body, "postFrameCallback"),
        )
        assertTrue("不挂 Choreographer 就没有可判据的「首帧之后」：\n$body", body.contains("Choreographer.getInstance()"))
        // 门闩必须在最前面：过了闩才排回调，排两遍回调就是付两遍
        assertTrue("门闩没排在最前面：\n$body", body.indexOf("claim()") in 0 until body.indexOf("postFrameCallback"))
        assertTrue("活没交给调用方传进来的作用域：\n$body", body.contains("scope.launch"))
        assertTrue(
            "launch 不在第二个帧回调里面（那等于在 onCreate 这一趟就把活派出去）：\n$body",
            body.indexOf("scope.launch") > body.indexOf("postFrameCallback", body.indexOf("postFrameCallback") + 1),
        )
        // 主线程侧不许有阻塞调用
        assertTrue(
            "主线程侧出现了等待/阻塞（预热的等待只许发生在 Dispatchers.IO 上）：\n$body",
            !body.contains("get(") && !body.contains("Tasks.await") && !body.contains("Thread.sleep"),
        )
    }

    /** ③ 本体：两步都在 IO 上，各自可单独删掉关掉一档；异常一律就地吞 */
    @Test
    fun warmUpRunsOnIoAndSwallowsEverything() {
        val code = normalize(withoutComments(readSource(WARM_UP_FILE)))
        val body = balancedBlock(code, "internal suspend fun warmUp(appContext: Context)")
        assertTrue("预热本体不在 Dispatchers.IO 上：\n$body", body.contains("Dispatchers.IO"))
        assertTrue("相机那一档不见了：\n$body", body.contains("warmUpCameraProvider("))
        assertTrue("解码器那一档不见了：\n$body", body.contains("warmUpBarcodeDecoder("))
        assertEquals(
            "两步之外还塞了别的东西 —— 「一键关掉」靠的就是这两行各删一行，不新增配置开关：\n$body",
            2,
            count(body, "warmUp") - count(body, "suspend fun warmUp"),
        )
        assertTrue(
            "整个 warmUp 里没有 catch (Throwable)：UnsatisfiedLinkError 是 Error，" +
                "非 arm64 设备上抛的就是它，而它一旦逃出 warmUp 就会顺着没有 handler 的 " +
                "applicationScope 把进程打死",
            code.contains("catch (t: Throwable)"),
        )
        // 两步各自带超时上限，最坏只是多占一个 IO 线程
        assertTrue("相机侧没有超时：\n$body", code.contains("CAMERA_TIMEOUT_SECONDS"))
        assertTrue("解码侧没有超时：\n$body", code.contains("DECODE_TIMEOUT_SECONDS"))
    }

    /**
     * ④ 解码那一档必须真的解一帧，否则 `.so` 根本没被 dlopen，这张卡就是白做的。
     *
     * 锚点里的 `private` → `internal` 是跟着 T24 走的：⑨ 要在 JVM 里真调这一档。
     * 改的只有这一个修饰符，断言一条没动。
     */
    @Test
    fun decoderStepPerformsARealDecode() {
        val code = withoutComments(readSource(WARM_UP_FILE))
        val body = normalize(balancedBlock(code, "internal suspend fun warmUpBarcodeDecoder()"))
        assertTrue("没建 scanner：\n$body", body.contains("BarcodeScanning.getClient("))
        assertTrue(
            "没有真正 process 一帧：getClient() 碰不到 BarhopperV3 的构造函数，" +
                "libbarhopper_v3.so 的 loadLibrary 就还留在那次 dlopen 上：\n$body",
            body.contains(".process("),
        )
        assertTrue("解码没等完成就返回（异步 fire-and-forget 等于不等 dlopen）：\n$body", body.contains("Tasks.await("))
        assertTrue("送的图不是位图：\n$body", body.contains("InputImage.fromBitmap("))
        assertTrue(
            "close() 没放在 finally 里：预热失败时那颗 scanner 会漏在进程里，" +
                "常驻的不该只有 `.so` 的映射：\n$body",
            body.contains("finally") && body.contains("close()"),
        )
        // 与扫码页同一档格式：预热的是同一条代码路径
        assertTrue("格式档与扫码页不一致：\n$body", body.contains("Barcode.FORMAT_QR_CODE"))
    }

    /** ⑤ 相机那一档只等 provider，绝不开相机 */
    @Test
    fun cameraStepOnlyInitializesTheProvider() {
        val code = withoutComments(readSource(WARM_UP_FILE))
        val body = normalize(balancedBlock(code, "private suspend fun warmUpCameraProvider(appContext: Context)"))
        assertTrue("没等 ProcessCameraProvider：\n$body", body.contains("ProcessCameraProvider.getInstance(appContext)"))
        assertTrue("在自己的地方等了它的 future（不许把 future 丢给别人）：\n$body", body.contains(".get("))
        assertTrue(
            "provider 的等待没有超时上限（CameraX 在某些设备上会一直不 ready）：\n$body",
            body.contains("CAMERA_TIMEOUT_SECONDS"),
        )
    }

    /** ⑥ 红线：不弹权限、不开相机、不预览、不新增配置开关、不阻塞主线程 */
    @Test
    fun warmUpStaysInsideItsRedLines() {
        val code = blankCommentsAndLiterals(readSource(WARM_UP_FILE))
        val forbidden = listOf(
            "checkSelfPermission" to "弹权限是扫码页的事，预热没资格替用户决定",
            "requestPermission" to "同上",
            "ActivityResultContracts" to "预热不许挂 launcher",
            "registerForActivityResult" to "同上",
            "bindToLifecycle" to "绑生命周期就是开相机",
            "PreviewView" to "预热不许出画面",
            "ImageAnalysis" to "预热不许挂分析流",
            "Dispatchers.Main" to "预热的活一律在 IO 上",
            "runBlocking" to "协程阻塞会连着 applicationScope 一起抖",
            "BuildConfig" to "关掉某一档 = 删掉 warmUp 里那一行，不新增编译期开关",
            "getSharedPreferences" to "同上，不新增运行时开关",
            "lifecycleOwner" to "预热不挂生命周期：转屏掐死一次半途的预热就永远补不回来",
        )
        for ((token, why) in forbidden) {
            assertTrue("预热里出现了 $token —— $why", !code.contains(token))
        }
    }

    /** ⑦ 接线：全仓库只有一个驱动点，排在 setContent 之后，作用域是进程级那一个 */
    @Test
    fun warmUpIsWiredOnceAfterSetContentOnTheProcessScope() {
        val callSites = mutableListOf<String>()
        for (source in mainSources()) {
            val code = blankCommentsAndLiterals(source.text)
            var from = 0
            while (true) {
                val at = code.indexOf("scheduleAfterFirstFrame(", from)
                if (at < 0) break
                val lineStart = code.lastIndexOf('\n', at) + 1
                if (!code.substring(lineStart, at).contains("fun ")) callSites += source.relative
                from = at + 1
            }
        }
        assertEquals(
            "预热调用点集合变了：$callSites。多一处就等于多一个转屏/多入口去抢那颗门闩的地方" +
                "（抢不到，但读代码的人会以为预热要做两遍）",
            listOf("$MAIN_PREFIX/com/buaa/schedule/MainActivity.kt"),
            callSites.distinct().sorted(),
        )

        val onCreate = balancedBlock(withoutComments(readSource(MAIN_ACTIVITY_FILE)), "override fun onCreate(")
        val code = normalize(onCreate)
        val setContent = code.indexOf("setContent {")
        val schedule = code.indexOf("ScanChainWarmUp.scheduleAfterFirstFrame(")
        assertTrue("onCreate 里没排预热：\n${code.take(300)}", schedule >= 0)
        assertTrue(
            "预热排在了 setContent 之前 —— 首帧的判据是靠 Choreographer 排的，排在 setContent 之前" +
                "只会让人以为这里在同步做预热；正确的位置是那次组合声明之后",
            schedule > setContent && setContent >= 0,
        )
        assertTrue(
            "作用域不是进程级那个 applicationScope：用 lifecycleScope 的话转屏会把跑到一半的预热" +
                "掐死，而门闩已经翻过去，这次预热就永远不会补做",
            code.contains("scope = (application as BUAAApplication).applicationScope"),
        )
    }

    /**
     * ⑧ 两档的**先后**：解码那一档必须排在相机那一档之前（换回去就红）。
     *
     * 为什么这条只能按形状核对，而不是"把两档抽成可注入的参数、传两个记账 lambda 进去跑一遍"：
     * - 跑 `warmUp` 需要一个真的 `android.content.Context`。本模块没有 Robolectric、没有 Mockito，
     *   `testOptions.unitTests.isReturnDefaultValues` 也没开 —— 连 `ContextWrapper()` 这个构造函数
     *   都是一句 "Stub!"，那个 Context 在 JVM 里造不出来（同本文件开头那句"没有 Robolectric"）；
     * - 就算把两档抽成带默认实参的函数引用参数，注入之后测试量到的也只是**自己传进去的那两个假
     *   lambda** 的先后，真接线（谁站在第一个位子上）还得回头按源码核对。一条断言拆成两半、
     *   internal 面还多两个符号，不划算。
     * 所以沿用本文件既有的手法（`ClassProgressRescheduleWiringTest` ④ 钉"取证日志排在报告口之前"
     * 用的是同一个形状）：锚点找不到就抛，找得到就比两个调用在 `warmUp` 函数体里的位置。
     */
    @Test
    fun decoderStepIsScheduledBeforeTheCameraStep() {
        val code = normalize(withoutComments(readSource(WARM_UP_FILE)))
        val body = balancedBlock(code, "internal suspend fun warmUp(appContext: Context)")
        val decoder = body.indexOf("warmUpBarcodeDecoder()")
        val camera = body.indexOf("warmUpCameraProvider(appContext)")
        check(decoder >= 0 && camera >= 0) { "warmUp 里那两行调用换过形状了：\n$body" }
        // 各只许调用一次：多出来的那一遍会先占住窗口，下面的顺序断言就成了摆设
        assertEquals("解码那一档在 warmUp 里被调用不止一次：\n$body", 1, count(body, "warmUpBarcodeDecoder"))
        assertEquals("相机那一档在 warmUp 里被调用不止一次：\n$body", 1, count(body, "warmUpCameraProvider"))
        // 顺序只在"两档都跑在 IO 那一段里"时才有意义：排到 withContext 外面就是排回主线程
        val io = body.indexOf("Dispatchers.IO")
        assertTrue("两档没落在 withContext(Dispatchers.IO) 里面：\n$body", io in 0 until decoder && io in 0 until camera)
        assertTrue(
            "两档的顺序被换回「相机那一档先跑」了（解码档在 $decoder、相机档在 $camera）。" +
                "两档之间确实没有数据依赖，但它们共用同一段「用户还没点进扫码页」的窗口：" +
                "相机档在相机枚举慢的设备上会把 CAMERA_TIMEOUT_SECONDS 那 5 秒整额耗光 —— " +
                "buaa36 实测 TimeoutException: Waited 5000000000 nanoseconds " +
                "[tag=[ProcessCameraProvider-initializeCameraX]] status=PENDING，" +
                "而解码档（`.so` 的 dlopen + 解码器构造）只值约 340 ms 且收益已被实测证明。" +
                "换回去的代价不是慢 5 秒，是这次预热对「打开应用就为了签到」的用户等于没跑" +
                "（docs/PERF-STARTUP-2026-09-19.md §8 ③ / T18b）：\n$body",
            decoder < camera,
        )
    }

    /**
     * ⑨ 探针判定「这份包里没有 libbarhopper_v3.so」时，解码那一档**零 ML Kit 调用**。
     *
     * 这是 T24 的根因守卫：release 在非 arm64 设备上启动约 1.5 s 后 FATAL，
     * 抛点是 ML Kit 自己 worker 线程上的 `System.loadLibrary`
     * （`FATAL EXCEPTION: pool-6-thread-2 java.lang.UnsatisfiedLinkError:
     * dlopen failed: library "libbarhopper_v3.so" not found`），我们这一侧的
     * `catch (Throwable)` 接不到 —— 所以唯一的解是**根本不发这次调用**。
     *
     * 三条断言各挡一种坏形状：
     * - 「安静返回」：越过闸门在 JVM 里必然抛（⑩ 校准的就是这条），所以不抛 == 没走到 ML Kit；
     * - 探针只被加载一次：闸门真的读的是探针的结论，而不是别处再猜一遍；
     * - 闸门写在 `BarcodeScanning.getClient(` 之前（形状核对）：位置漂到 try 里面，
     *   前两条就都白量了。
     */
    @Test
    fun decoderStepIssuesNoMlKitCallWhenProbeSaysMissing() {
        val loads = AtomicInteger(0)
        withProbe(
            BarhopperNativeLibProbe {
                loads.incrementAndGet()
                throw UnsatisfiedLinkError("dlopen failed: library \"libbarhopper_v3.so\" not found")
            },
        ) {
            val outcome = runCatching { runBlocking { ScanChainWarmUp.warmUpBarcodeDecoder() } }
            assertTrue(
                "探针判定不可用时这一档还是走到了 ML Kit：${outcome.exceptionOrNull()} —— " +
                    "缺库的设备上那一下会抛在 ML Kit 自己的线程上，本函数的 catch 接不住，进程当场没",
                outcome.isSuccess,
            )
            assertEquals("闸门没读探针，或者读了不止一次（探针的结论是进程内缓存的）：", 1, loads.get())
        }

        val body = normalize(balancedBlock(withoutComments(readSource(WARM_UP_FILE)), "internal suspend fun warmUpBarcodeDecoder()"))
        val gate = body.indexOf("barhopperNativeLib.decideNow()")
        val firstMlKitCall = body.indexOf("BarcodeScanning.getClient(")
        check(gate >= 0 && firstMlKitCall >= 0) { "解码档的闸门或第一次 getClient 换过形状了：\n$body" }
        assertTrue(
            "闸门挪到第一次 ML Kit 调用之后了（闸门在 $gate、getClient 在 $firstMlKitCall）：" +
                "这等于把守卫退化成「抛得更早然后接住」，而缺库那一下根本不在我们的线程上",
            gate < firstMlKitCall && body.indexOf("return", gate) in 0 until firstMlKitCall,
        )
    }

    /**
     * ⑩ ⑨ 那条观测通道的校准：闸门放开（探针说可用）时，同一句调用在 JVM 里**必须抛**。
     *
     * 它不是多余的。⑨ 量的是「没抛 == 没走到 ML Kit」，这个等价式成立只因为 android.jar
     * 是桩：越过闸门后 `createBitmap` 或者就地抛、或者被自己的 catch 接住后由 `Log.d`
     * 抛出去。哪天有人开了 `testOptions.unitTests.isReturnDefaultValues`、或者把 catch 里
     * 那句日志换掉，⑨ 就会变成一个永远绿的假守卫 —— 那条通道断了这里先红。
     */
    @Test
    fun mlKitCallIsAudiblyBrokenOnJvmSoTheQuietReturnIsRealEvidence() {
        withProbe(BarhopperNativeLibProbe { }) {
            val outcome = runCatching { runBlocking { ScanChainWarmUp.warmUpBarcodeDecoder() } }
            assertTrue(
                "越过闸门之后这一档在 JVM 里居然安安静静跑完了 —— ⑨ 的「安静返回」不再是" +
                    "「零 ML Kit 调用」的证据，去看是不是开了 returnDefaultValues 或换了日志写法",
                outcome.isFailure,
            )
        }
    }

    /** 换掉进程内那枚探针，跑完换回去（探针把自己的结论一起换掉了，不需要额外"清空"） */
    private fun withProbe(probe: BarhopperNativeLibProbe, block: () -> Unit) {
        val saved = barhopperNativeLib
        barhopperNativeLib = probe
        try {
            block()
        } finally {
            barhopperNativeLib = saved
        }
    }

    // ---- 源码核对工具（抄 ColdStartRebuildWiringTest）----

    private class Source(val relative: String, val text: String)

    private fun readSource(relativeFromJava: String): String {
        val file = File(findMainJavaDir(), relativeFromJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    private fun mainSources(): List<Source> {
        val javaDir = findMainJavaDir()
        val files = javaDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${javaDir.path} 下一个 .kt 都没有，路径不对", files.isNotEmpty())
        return files.map { Source("$MAIN_PREFIX/${it.relativeTo(javaDir).path.replace('\\', '/')}", it.readText()) }
    }

    /** 从 [signature] 之后第一个 `{` 起配平到对应右括号（含）；找不到锚点就抛 */
    private fun balancedBlock(source: String, signature: String): String {
        val at = source.indexOf(signature)
        check(at >= 0) { "找不到 $signature：签名改过家（含换行/参数换行），这条守卫要跟着改" }
        val open = source.indexOf('{', at)
        check(open >= at) { "$signature 之后找不到左括号" }
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(at, index + 1)
                }
            }
        }
        throw IllegalStateException("$signature 的花括号没配平")
    }

    private fun withoutComments(source: String): String {
        val out = StringBuilder(source)
        var block = out.indexOf("/*")
        while (block >= 0) {
            val end = out.indexOf("*/", block + 2)
            if (end < 0) break
            out.replace(block, end + 2, " ")
            block = out.indexOf("/*")
        }
        val text = out.toString()
        return text.lines().joinToString("\n") { line ->
            val slash = line.indexOf("//")
            if (slash >= 0) line.substring(0, slash) else line
        }
    }

    /** 注释与字符串字面量都抹成空白，长度与换行位置不变：整份文件的红线扫描用这份 */
    private fun blankCommentsAndLiterals(src: String): String {
        val out = src.toCharArray()
        var i = 0
        while (i < out.size) {
            when {
                src.startsWith("//", i) -> {
                    val nl = src.indexOf('\n', i).let { if (it < 0) out.size else it }
                    for (k in i until nl) out[k] = ' '
                    i = nl
                }

                src.startsWith("/*", i) -> {
                    var depth = 1
                    var j = i + 2
                    while (j < out.size && depth > 0) {
                        when {
                            src.startsWith("/*", j) -> { depth++; j += 2 }
                            src.startsWith("*/", j) -> { depth--; j += 2 }
                            else -> j++
                        }
                    }
                    for (k in i until j.coerceAtMost(out.size)) if (out[k] != '\n') out[k] = ' '
                    i = j
                }

                src.startsWith("\"\"\"", i) -> {
                    val end = src.indexOf("\"\"\"", i + 3).let { if (it < 0) out.size else it + 3 }
                    for (k in (i + 3) until (end - 3).coerceAtLeast(i + 3)) if (out[k] != '\n') out[k] = ' '
                    i = end
                }

                out[i] == '"' || out[i] == '\'' -> {
                    val quote = out[i]
                    var j = i + 1
                    while (j < out.size) {
                        when {
                            src[j] == '\\' -> j += 2
                            src[j] == quote -> { j++; break }
                            src[j] == '\n' -> break
                            else -> j++
                        }
                    }
                    for (k in (i + 1) until (j - 1).coerceAtLeast(i + 1)) out[k] = ' '
                    i = j
                }

                else -> i++
            }
        }
        return String(out)
    }

    private fun count(haystack: String, needle: String): Int {
        var n = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return n
            n++
            from = at + needle.length
        }
    }

    private fun normalize(code: String): String = code.replace(Regex("\\s+"), " ").trim()

    /** 工作目录是模块目录还是仓库根不由这里决定：两种布局都试，全落空就抛 */
    private fun findMainJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { File(dir, it) }
                .firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }

    private companion object {
        const val MAIN_PREFIX = "app/src/main/java"
        const val WARM_UP_FILE = "com/buaa/schedule/ui/signin/ScanChainWarmUp.kt"
        const val MAIN_ACTIVITY_FILE = "com/buaa/schedule/MainActivity.kt"
    }
}
