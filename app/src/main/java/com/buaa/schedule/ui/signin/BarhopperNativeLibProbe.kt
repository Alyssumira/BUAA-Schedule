package com.buaa.schedule.ui.signin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 扫码解码那颗原生库的名字，实际文件是 `lib/<abi>/lib<这里>.so`。
 *
 * 与 `app/build.gradle.kts` 末尾 `androidComponents` 块排除掉的三条 jniLibs **同源**：
 * 那边按文件名删掉 armeabi-v7a / x86 / x86_64 三档、只留 arm64-v8a。改一边不改另一边
 * （名字一漂）探针就变成一个永远回答"可用"的空壳，非 arm64 的 release 包会立刻回到
 * 启动即崩 —— 所以这条漂移由 BarhopperNativeLibProbeTest 里那条"名字同源"守卫钉着，
 * 它是真去读 build.gradle.kts 的文本比对，不是注释提醒。
 */
internal const val BARHOPPER_NATIVE_LIBRARY: String = "barhopper_v3"

/**
 * 探到的结论。
 *
 * **「还没探」不在这个枚举里** —— 它是 [BarhopperNativeLibProbe.verdict] 的那颗 null。
 * 未判定与已判定不可用必须分得开：把未判定当成不可用，arm64 用户会在预热还没跑到
 * 解码那一档时白看一帧降级页（这一页的行为在 arm64 上要与改动前逐帧一致）。
 */
internal enum class NativeLibVerdict { Available, Missing }

/**
 * 「这台设备的这份包里到底有没有 `libbarhopper_v3.so`」的前置探针（T24）。
 *
 * ## 为什么判据只能是真去 loadLibrary
 *
 * 三个看着都行、其实都不行的办法：
 *
 * - `Build.SUPPORTED_ABIS`：答的是"这台设备支持哪些 ABI"，不是"这份包里带了哪一档"。
 *   x86_64 模拟器支持 x86_64，而 release 包里恰恰没有那一档的库 —— 猜出来的结论正好反。
 * - 翻 APK / `nativeLibraryDir` 下有没有那个文件：`useLegacyPackaging` 决定它是装在包里
 *   安装时解压还是原样 mmap，目录布局还牵扯 split APK 与系统版本，判出来的"没有"不可信。
 * - 等 ML Kit 自己抛：抛的位置不在我们这边（见下一节），等于不判。
 *
 * 真 load 一次是唯一能拿到"这个进程能不能 dlopen 到它"的办法。附带一条白拿的好处：
 * `System.loadLibrary` 对同一个类加载器重复加载同一个库是 no-op，所以我们这次 load 成之后，
 * ML Kit 后面那次 `loadLibrary` 直接命中现成的映射，一分钱不花。
 *
 * ## 为什么必须提前判
 *
 * `loadLibrary` 写在 `BarhopperV3` 的实例构造函数里，那个实例第一次真正解码才被 new，
 * 而 new 它的那一下跑在 **ML Kit 自己的工作线程**上。release x86_64 模拟器实测
 * （冷启动约 1.5 s 后）：
 *
 * ```
 * FATAL EXCEPTION: pool-6-thread-2  java.lang.UnsatisfiedLinkError:
 *     dlopen failed: library "libbarhopper_v3.so" not found
 *     at java.lang.System.loadLibrary → pa.a.d → mlkit_vision_barcode_bundled.t.onTransact → …
 * ```
 *
 * 异常落在那个线程上：我们调用侧的 `catch (Throwable)` 接不到（`Tasks.await` 在另一个
 * 线程等一个永远不会完成的 task），它顺着那个线程的默认处理器把整个进程打死。
 * 所以这里只做一件事：**把 dlopen 挪到我们选定的线程上、用我们自己写下的 try 接住**，
 * 再把结论递给调用方，让那条链在判定为 Missing 时**根本不发起 ML Kit 调用**。
 * 这不是"抛得更早然后接住"——判定为不可用的进程里，一次 `getClient`／`process`／
 * `Tasks.await` 都不会发生（有守卫测试钉住零调用）。
 *
 * ## 三态怎么摆
 *
 * UI 只在 [NativeLibVerdict.Missing] 时才收掉解码入口；未判定不算不可用。
 * 代价是一条纪律：**要用解码器之前必须先把判定做完**。扫码页把这条纪律落在
 * `bindToLifecycle`（相机第一帧解码的唯一入口）之前 `awaitDecided()`，
 * 相册那条路则只放行"已判定可用"，所以第一帧解码永远发生在一个已判定可用的 scanner 上。
 *
 * ## 线程
 *
 * dlopen 一次是几毫秒到几十毫秒的量级，**绝不在主线程做**：[decideNow] 只给后台线程调
 * （预热那一档本来就跑在 `Dispatchers.IO` 上），主线程只许廉价读 [verdict]，
 * 或者在 [awaitDecided] 上挂着等 —— 后者会把加载那一下派到 IO。
 */
internal class BarhopperNativeLibProbe(private val loader: (String) -> Unit) {

    @Volatile
    private var decided: NativeLibVerdict? = null

    private val decidingLock = Any()

    /** 一次 volatile 读：不加载、不阻塞、不换线程，所以组合期也能读 */
    val verdict: NativeLibVerdict?
        get() = decided

    /** 只有"已判定可用"才 true（解码动作的放行判据：未判定与不可用都放行不了） */
    fun isAvailable(): Boolean = decided == NativeLibVerdict.Available

    /**
     * 就地判定，幂等：整个进程里加载动作只发生一次。
     *
     * 双检 + `synchronized`：并发进来只有一个线程真去做 dlopen，其余的在里面等同一份结论。
     * 等自己人比让每个入口各自 load 一遍便宜，也比"各自读到未判定"更符合调用方要的答案。
     */
    fun decideNow(): NativeLibVerdict {
        decided?.let { return it }
        synchronized(decidingLock) {
            decided?.let { return it }
            val outcome = try {
                loader(BARHOPPER_NATIVE_LIBRARY)
                NativeLibVerdict.Available
            } catch (failure: Throwable) {
                // 缺库的正规形状是 UnsatisfiedLinkError；别的 Throwable 说明加载动作在
                // 这台设备上就是走不通（ROM 抽风、命名空间异常…），结论一样：解码不可用。
                // 这里不重抛：逃出去只会落在 applicationScope 那条没有 handler 的链上，
                // 而这一颗探针的存在意义就是"这次失败不许把进程带走"。
                NativeLibVerdict.Missing
            }
            decided = outcome
            return outcome
        }
    }

    /**
     * 拿到判定为止。**已有结论时直接返回，既不换线程也不挂起** —— 扫码页那两处（进页面时、
     * 绑定分析流之前）都走这条路，arm64 上预热早已把结论算好，这两句相对改动前不多花任何一帧。
     */
    suspend fun awaitDecided(): NativeLibVerdict =
        decided ?: withContext(Dispatchers.IO) { decideNow() }
}

/**
 * 进程内那一份探针。
 *
 * 写成 `var` 是有意的 seam：宿主 JVM 里 `System.loadLibrary` 的行为不可用（没有那颗 `.so`，
 * android.jar 又只是桩），present / missing 两条只能靠注入模拟。换掉整颗探针（连带它自己的
 * 那份缓存）比给它开后门"清空结论"干净：测试各自 new 一颗、跑完把这一颗换回去就行。
 * 生产侧没有任何一处会重新赋值它。
 */
internal var barhopperNativeLib: BarhopperNativeLibProbe =
    BarhopperNativeLibProbe { name -> System.loadLibrary(name) }
