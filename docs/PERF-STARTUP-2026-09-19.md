# 冷启动链瘦身（T18）—— WorkManager 按需初始化 / 起手 ContentProvider / 扫码链预热

日期：2026-09-19。分支 `ai/T18`，基线 `fc8c986`。
本页只记这三件事的**改法、线程账、清单对照与待量的数**；
门禁计数见 §5。设备上还没有一个数字是本卡量的（不许碰设备），
§6 是编排者必须量的三个数与命令形态。

## 0. 一句话

起手链上原本有一笔**每一次冷进程都付、且付在主线程上**的 WorkManager 初始化
（`androidx.startup.InitializationProvider` → `WorkManagerInitializer` →
Room 建库 + 连 JobScheduler）。本卡把它摘成按需，并逐个证明应用内每一个
`WorkManager.getInstance` 调用点都只在后台线程上被调到；顺带把六家组件
Provider 里逐字重复的 `onEnabled`/`onDisabled` 收进基类并挪出广播主线程；
最后在首帧之后预热一次扫码链，把 `libbarhopper_v3.so` 的 `dlopen`
从"用户点开扫码页的那一瞬间"搬到没人等的后台线程上。

## 1. WorkManager 改按需初始化

两半分处两个文件、少一半都不红，所以两边都有守卫测试钉着（§4）。

### 1.1 清单侧（`app/src/main/AndroidManifest.xml:172-180`）

```xml
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup"
    android:exported="false"
    tools:node="merge">
    <meta-data
        android:name="androidx.work.WorkManagerInitializer"
        tools:node="remove" />
</provider>
```

- `tools:node="merge"` + 只给那**一条** meta-data 挂 `tools:node="remove"`。
- ⚠️ **不许把整个 provider 摘掉**：同一个 provider 下还挂着
  `ProfileInstallerInitializer`，而本应用 Gitee 自分发、拿不到 Play 的云端 ART
  profile，Baseline Profile（T16 接的线）只能靠它落盘 —— 删 provider 等于删 profile。
  另外两条（emoji2 / ProcessLifecycle）同理不归这张卡动。
- 副作用面：provider 本身仍然会 install（androidx.startup 的 `InitializationProvider`
  还在），只是它少跑一个 Initializer。**别拿"provider 还在"当作"没摘干净"**。

### 1.2 应用侧（`BUAAApplication.kt:24,50`）

```kotlin
class BUAAApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}
```

这一半不是"锦上添花"，是**功能正确性的前提**。反编译 `work-runtime-2.9.1`
（`javap -c androidx.work.impl.WorkManagerImpl`）确认 `getInstance(Context)` 的形状：

```
0: getstatic sLock / 5: monitorenter            ← 整个函数在静态 sLock 里面
  ...
20: instanceof androidx/work/Configuration$Provider
23: ifeq 47
31: invokeinterface getWorkManagerConfiguration  ← 我们的 getter 在 sLock 里被调
47: new IllegalStateException
51: ldc "WorkManager is not initialized properly.  You have explicitly disabled
       WorkManagerInitializer in your manifest, have not manually called
       WorkManager#initialize at this point, and your Application does not
       implement Configuration.Provider."
```

也就是：**只摘 initializer 而不实现 `Configuration.Provider`，`getInstance` 不自我
初始化，而是抛 `IllegalStateException`**。而应用内两处调用点外面都套着
`runCatching`（`WidgetFallbackWorker.ensure`、`BackgroundSync.cancelLegacyPeriodicWork`），
于是症状不是崩溃，而是「12 小时兜底任务永远注册不上」——
HyperOS 那批吞闹钟的机型上组件与提醒跟着一起哑，日志里只有一行 WARN。
这是这一族最难发现的失败形状，所以守卫测试把两半边一起钉。

配置的**等价性**也是核过的：`androidx.work.WorkManagerInitializer.create()` 的字节码就是
`Logger.debug("Initializing WorkManager with default configuration.")` +
`WorkManager.initialize(context, new Configuration.Builder().build())` +
`WorkManager.getInstance(context)`。我们给的是同一句 `Configuration.Builder().build()`：
默认日志档、默认后台 executor、默认反射 WorkerFactory。
`WidgetFallbackWorker` 用 `(Context, WorkerParameters)` 标准构造，反射工厂建得起来，
所以不需要自定义 WorkerFactory。**这张卡改的只有"什么时候初始化"，不是"用什么初始化"。**

getter 的两条约束（已写进注释）：它在 `sLock` 里面被第一个调到 `getInstance` 的线程执行，
所以它不许碰 WorkManager 自己（自锁风险）、不许读盘、不许等任何人。

### 1.3 主线程上到底被摘掉了什么（静态账，毫秒数待 §6① 量）

`androidx.startup` 的 provider 跑在 `Application.attachBaseContext` 之后、
`onCreate` 之前，全程主线程。被摘掉的那一条原本在主线程上做（`WorkManagerImplExtKt.createWorkManager`
与 `WorkManagerImpl.<init>` 的字节码逐条对过）：

| 动作 | 形状 | 是否真的读盘 |
| --- | --- | --- |
| `WorkDatabase.create(...)` | Room 生成的 `WorkDatabase_Impl` 类加载 + `DatabaseConfiguration` 组装（含一次 `Resources.getBoolean`） | **不等第一次 DAO 访问就不会 open** SQLite |
| `Schedulers.createBestAvailableBackgroundScheduler` | `SystemJobScheduler` 构造 → `context.getSystemService(JobScheduler)` | 跨 binder 到 system_server |
| `Trackers` / `Processor` / `GreedyScheduler` / `WorkManagerTaskExecutor` / `WorkLauncherImpl` | 全套对象构造 | 构造本身不读盘 |
| `Schedulers.registerRescheduling` / `ForceStopRunnable` | **`executeOnTaskThread` 派出去** | 真正的 pruneWork / 清理过期 work 在 WorkManager 自己的 task 线程上 |
| `PreferenceUtils`（`WorkManagerImpl.<init>` 内 new） | SharedPreferences 句柄 | 首次读值是 lazy |

⚠️ `ForceStopRunnable` 那条不要记错：它在按需路径与自动路径上都**不占主线程**，
本卡没有把它"挪到后台"，它本来就在后台。这张卡挪出来的只有上面前三行那类
构造 + 一次 binder 往返，以及 provider 少跑一个 Initializer 的调度开销。

## 2. WorkManager 调用点的线程账

全仓库真调 `WorkManager.` 的主源码文件只有两个（`WorkManagerOnDemandInitTest` ③ 把
这个集合钉成断言，多一处就等于多一个要审的线程）。可达路径全部列在这里。

### 2.1 应用侧

| # | 调用点（文件:行） | 上游入口 | 执行线程 | 备注 |
| --- | --- | --- | --- | --- |
| A | `widget/BackgroundSync.kt:536`（`cancelLegacyPeriodicWork`） | `BUAAApplication.kt:87` 的 `step("cancelLegacyPeriodicWork")` | **Dispatchers.IO**（`applicationScope`，`BUAAApplication.kt:33`） | 绝大多数冷进程里第一个付钱的调用点；排在 `Application.onCreate` 里，早于任何广播/服务回调 |
| B | 同上（`cancelLegacyPeriodicWork`） | 六家 Provider 的 `onDisabled` → `WidgetCommon.cancelMidnightIfNoWidgetsFromReceiver` | **Dispatchers.IO**（`launchRefresh`，`goAsync()` 续命） | 本卡挪出来的：原先 `onDisabled` 在广播主线程上直连这里 |
| C | `widget/BackgroundSync.kt:521`（同一条链里的 `WidgetFallbackWorker.ensure`） | 同 B | **Dispatchers.IO** | 一次 `onDisabled` 连带两处 `getInstance` + 一次跨 binder 探测 |
| D | `widget/WidgetFallbackWorker.kt:92`（`ensure`） | `WidgetCommon.bootstrapBackgroundSync`（`WidgetCommon.kt:118`） ← ①`onEnabled`→`bootstrapFromReceiver` ②`goAsyncUpdate/Next/TwoDay/WeekGrid` ③`saveConfigAndRefresh` | **Dispatchers.IO** | ①是本卡挪出来的；②③改动前就在协程里 |
| E | 同 D | `BackgroundSync.runColdStartWidgetSteps`（`BackgroundSync.kt:440`）← `ColdStartRebuild.run` ← A 那条 IO 链 | **Dispatchers.IO** | 冷启动四步共用一次组件探测（T10 的账） |

结论：**主线程侧没有任何一条调到 `WorkManager.getInstance` 的应用代码路径**，
因此不需要额外的"预热门闩"。守卫测试 ④ 钉的就是这件事的形状：
六家 Provider 里 `override fun onEnabled(` / `override fun onDisabled(` 的条目数必须为 0，
基类那两份必须走 `*FromReceiver`，而 `*FromReceiver` 里必须出现
`receiver.goAsync()` + `launchRefresh(` 且不得直接出现 `WorkManager.`。

### 2.2 库内部（应用改不动，残余风险）

`work-runtime-2.9.1` 自己也在主线程上调 `getInstance`。逐条查过：

| 库内调用点 | 入口线程 | 我们设备上的可达性 | 证据 |
| --- | --- | --- | --- |
| `impl.background.systemjob.SystemJobService.onCreate()` → `WorkManagerImpl.getInstance` | **Service 主线程** | **可达**：JobScheduler 把 12 小时兜底任务投给一个刚被拉起的冷进程时，建库的钱付在 Service 的主线程上 | `javap -c` 该类，唯一一处 `invokestatic getInstance` 在 `onCreate` |
| `impl.background.systemalarm.RescheduleReceiver.onReceive` → `getInstance` | 广播主线程 | **不可达**：清单里 `android:enabled="false"`，且 `@bool/enable_system_alarm_service_default` 在 `values-v23` 里是 `false`（minSdk 26 全部命中） | AAR 清单 + `aar/res/values-v23/values-v23.xml` |
| `impl.diagnostics.DiagnosticsReceiver.onReceive` → `WorkManager.getInstance` | 广播主线程 | 只有 `adb` 侧带 `DUMP` 权限手动发 `androidx.work.diagnostics.REQUEST_DIAGNOSTICS` 才会走到 | 合并清单里该 receiver 的存在与 `android:permission` |
| `impl.utils.ForceStopRunnable$BroadcastReceiver.onReceive` | 广播主线程 | 不调 `getInstance`（只做 `setAlarm`），无关 | `javap -c` |

改前这些路径由 provider 先付，改后由它们自己付。**这是一笔"从起手挪到最坏现场"的
反向搬家**，量级与 §6② 同源，必须量；不接受"反正很少发生"。缓解事实（不是修复）：
`SystemJobService.onCreate` 不在 ActivityThread 的启动关键路径上、
它后面紧跟着的就是同一个进程里 worker 的执行，改前 provider 也在这同一次进程启动里付过同样的钱。

### 2.3 幂等与门闩在哪

- **`getInstance` 本身幂等**：`sLock` + `sInstance == null` 判据在库里，两个 IO 线程
  并发首次调用 → 一个付钱、另一个在 monitor 上等；主线程不参与，所以不加门闩。
  ⚠️ 副作用：如果将来有人把某条路径改回主线程，它可能**被后台那条正在初始化的线程堵住**，
  最坏等一整次建库。这也是 §2.1 那张表必须逐条守的原因。
- **重复注册的幂等**沿用既有那一份：`WidgetCommon.lastBootstrapAt` 的 60 秒时间闸门
  （`BOOTSTRAP_INTERVAL_MS`），一次广播里六家 Provider 各进来一次也只真跑一遍。
- **`onEnabled`/`onDisabled` 收进基类**只是搬家不是新增职责：改动前六家各写一份、
  内容逐字相同（这正是"新增一种组件就漏注册"的形状），现在基类一份。
  `goAsync()` 的票只有一份：`ScheduleAppWidgetProvider` 已有
  `takeRebindPendingResult()` 那一份口径，`BroadcastReceiver.goAsync()` 在票被取走时
  **返回 null 而不抛**，`launchRefresh` 的 `pendingResult?.finish()` 天然兜得住 ——
  最坏结果是这一次补注册不再替进程续命，活照样跑完。

## 3. 起手 ContentProvider 盘点（逐项对照）

证据来源：
- 改后 = 本 worktree 本次构建产物 `app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml`
  与 `app/build/outputs/logs/manifest-merger-debug-report.txt`。
- 改前 = 基线那份合并清单（`D:/schedule/BUAA-Schedule` 的 release 合并产物；
  `git diff fc8c986..master -- app/src/main/AndroidManifest.xml` 为空，
  即应用侧清单在 fc8c986 与当前 master 顶端逐字相同）。

| provider | authorities | meta-data（Initializer） | 改前 | 改后 |
| --- | --- | --- | --- | --- |
| `androidx.core.content.FileProvider` | `com.buaa.schedule.fileprovider` | `android.support.FILE_PROVIDER_PATHS` | 有（应用声明） | 有（未动） |
| `androidx.startup.InitializationProvider` | `com.buaa.schedule.androidx-startup` | `androidx.work.WorkManagerInitializer` | 有（work-runtime 2.9.1） | **无**（merger 报告：`REJECTED from [androidx.work:work-runtime:2.9.1]`） |
|  |  | `androidx.emoji2.text.EmojiCompatInitializer` | 有（emoji2 1.4.0） | 有（未动） |
|  |  | `androidx.lifecycle.ProcessLifecycleInitializer` | 有（lifecycle-process 2.9.4） | 有（未动） |
|  |  | `androidx.profileinstaller.ProfileInstallerInitializer` | 有（profileinstaller 1.4.1） | 有（未动，T16 的生效路径） |
| `com.google.mlkit.common.internal.MlKitInitProvider` | `com.buaa.schedule.mlkitinitprovider` | —（不是 startup provider，mlkit common 18.11.0 自带） | 有 | 有（未动，见 §4.3 为什么它帮不上 `.so`） |

净结果：**provider 数量 3 → 3，Initializer 条数 4 → 3**。

> ⚠️ 与交接说明的一处订正：任务里给的"改前实测 = InitializationProvider 下两条
> meta-data（WorkManager 与 ProfileInstaller）"与证据不符。改前那份合并清单里是
> **四条**（还有 emoji2 与 ProcessLifecycle）。摘掉的仍然只有 WorkManager 那一条，
> 结论不变，但账目以合并清单为准。
>
> 这张卡的"起手 provider 清理"就清到这里为止：剩下三条（emoji2 / ProcessLifecycle /
> ProfileInstaller）分别管 emoji 字体、进程级生命周期与 Baseline Profile 落盘，
> 每条都有别的东西在依赖，摘掉的收益要在设备上另立一本账才能算，不在本卡范围内。
> `MlKitInitProvider` 是 ML Kit 自己的 provider（不在 startup 的管辖下），也留着 ——
> §4 的预热恰恰需要它先把 ML Kit 的 Java 侧拉起来。

## 4. 扫码链预热（首帧之后、进程内一次、可取消、静默）

### 4.1 接线（`MainActivity.kt:210-213`）

```kotlin
ScanChainWarmUp.scheduleAfterFirstFrame(
    scope = (application as BUAAApplication).applicationScope,
    context = applicationContext,
)
```

排在 `setContent { … }` **之后**；作用域取进程级 `applicationScope` 而不是 `lifecycleScope`；
不按 `onboardingCompleted` 分叉。三条理由都是同一句话：门闩是进程内一次性的，
Activity 重建/引导子树换掉都不会再给它第二次机会。

### 4.2 「首帧之后」的可判据版本（`ui/signin/ScanChainWarmUp.kt`）

`Choreographer` 的一趟 `doFrame` 按 input → **animation** → insets → **traversal** 排，
Compose 的组合/测量在 animation 阶段、真正的首帧绘制在同一帧的 traversal 阶段。
所以：`onCreate` 里排下的第一次 `postFrameCallback` 跑在**首帧那一趟的 animation 阶段**，
那时首帧还没画；在它里面再排一次，第二次回调才落在下一帧的 animation 阶段
—— 上一帧（含首帧 traversal/draw）已经整趟走完。**两次 `postFrameCallback`，
不是 `onCreate`、不是 `Thread.sleep` 猜时间**（守卫测试数这两次注册）。
主线程侧总共只多两次帧回调注册 + 一次 `scope.launch` 派发，微秒量级；
测试还反向钉了主线程侧不得出现 `get(` / `Tasks.await` / `Thread.sleep`。

### 4.3 两档本体（都在 `Dispatchers.IO` 上，各自带 5 秒上限）

| 档 | 做什么 | 为什么必须是这个样子 |
| --- | --- | --- |
| 相机 | `ProcessCameraProvider.getInstance(appContext).get(5, SECONDS)` | CameraX 的 provider 初始化 + 相机枚举。**不开相机、不碰权限、不 `bindToLifecycle`**；扫码页那次的 `cameraProviderOrNull` 拿到的是同一个单例、已完成的 future |
| 解码器 | `BarcodeScanning.getClient(FORMAT_QR_CODE)` + 解一张 64×64 全零 `ARGB_8888` 图 + `Tasks.await(5s)` + `finally { close() }` | **只 `getClient()` 是白做**：`javap -c com.google.android.libraries.barhopper.BarhopperV3` 确认 `System.loadLibrary("barhopper_v3")` 写在**实例构造函数**里（`<clinit>` 只有一句 `TAG = getSimpleName()`），而那个实例由 bundled 流水线 `com.google.mlkit.vision.barcode.bundled.internal.zza`（全 jar 里唯一引用 `BarhopperV3` 的类）在第一次解码时 new。起手那颗 `MlKitInitProvider` 只拉起 ML Kit 的 Java 侧，碰不到这颗 `.so` |

- 格式档与扫码页一致（`FORMAT_QR_CODE`），预热的是同一条代码路径。
- 幂等：`private val claimed = AtomicBoolean(false)` + `claim()`；转屏再调一次
  `scheduleAfterFirstFrame` 直接 return（守卫测试用 8 线程抢一次、只许一个人赢）。
- 可取消/可关：`warmUp()` 里就这两行调用，**各删一行关一档**，不新增编译期或运行时开关
  （红线扫描钉 `BuildConfig` 与 `getSharedPreferences` 不得出现在这个文件里）。
- 失败静默：两步各自 `catch (t: Throwable)` 只留一行 `Log.d`。用 `Throwable` 而不是
  `runCatching`：非 arm64 设备上是 `UnsatisfiedLinkError`（Error），而
  `SpocScanScreen` 的降级路径按的就是同一个 catch。
- ⚠️ 为什么必须吞干净：它跑在 `applicationScope`（`SupervisorJob` 且**没有**
  `CoroutineExceptionHandler`）上，逃出这里就是顺着线程默认处理器把整个进程打死。

### 4.4 净增代价（唯一一笔）

`libbarhopper_v3.so` 的映射与 CameraX/ML Kit 的那批类会常驻进程
（`System.loadLibrary` 之后没有卸载 API）。**解码器实例本身不常驻**（`close()` 照调用）。
用户从不打开扫码页时这笔原本是零。是否值得，取决于 §6③ 量到的那一下有多大。

## 5. 门禁计数（本卡实跑，`--offline`，`--rerun`，JDK 21 / Gradle 8.14.3）

命令与日志：`/d/schedule/.tmp/t18-gate.log`

| 项 | 结果 | 地板 |
| --- | --- | --- |
| `:app:testDebugUnitTest` | **780 tests / 0 failures / 0 errors / 0 skipped** | 零失败（原地板 769 + 本卡新增 11 条守卫用例） |
| `:app:lintDebug` | **0 errors / 14 warnings** | 0 error / 14 warning |
| `.kt` 口径 lint 条目集合 | `IDENTICAL`（9 条，对 `t11-lint-kt-baseline.txt`） | 不得变多 |
| `:app:assembleDebug` | 通过，`app/build/outputs/apk/debug/app-debug.apk` = **45,051,219 字节**（debug 包含全 ABI 的 `.so`，不是 release 账） | 能出包 |

新增守卫用例：`WorkManagerOnDemandInitTest` 4 条 + `ScanChainWarmUpTest` 7 条。
手法沿用 `ColdStartRebuildWiringTest`：本模块没有 Robolectric，
`Choreographer.getInstance()` 是抛 "not mocked" 的桩，所以线程/时序只能按源码形状核对，
找不到锚点就抛（不用 `assumeTrue` 式的静默跳过）。唯一能在 JVM 里真跑的是那颗 `AtomicBoolean` 门闩。

接手时修的四处（都在未提交的遗留改动里，第一/第二枚提交前跑不过门禁）：

1. `WorkManagerOnDemandInitTest.widgetProviderFiles()` 把抽象基类
   `ScheduleAppWidgetProvider.kt` 一起数进来（7 家），与"六家"断言以及
   "每家 `onEnabled` 条目数为 0"直接冲突 —— 基类恰恰是唯一该有的那一份。已排掉基类。
2. 同一函数返回 `Sequence<String>` 却声明 `List<String>`（`walkTopDown().filter{}.map{}.sorted()`
   一路都在 Sequence 上），kotlinc 直接不编译。补 `.toList()`。
3. `findModuleDir()` 少爬一层（`<module>/src/main/AndroidManifest.xml` 往上应数
   manifest→main→src→module 三层），导致读成 `<module>/src/src/...`，四条用例全红。
4. `ScanChainWarmUp` 里 `Bitmap.createBitmap` 触发一条新的 `UseKtx` lint warning
   （14 → 15 且 `.kt` 条目集合变多）。改用仓库既有写法 `androidx.core.graphics.createBitmap`
   （`SceneBackground` / `WidgetBackgroundRenderer` 同一份）。

另有一处**注释与事实的核对**：遗留改动里那句"provider 下挂着四条 Initializer、
`getInstance` 未实现 Provider 会抛 `IllegalStateException`、`loadLibrary` 在
`BarhopperV3` 构造函数里、`ForceStopRunnable` 本来就不在主线程"—— 四条全部经
`javap -c` 复核为真（见 §1.2 / §1.3 / §4.3 的引用），保留原文。
唯一订正的是 §3 那条"改前 2 条 meta-data"的交接说法（实测 4 条）。

## 6. 必须在模拟器上量的三个数（本卡一条 adb 都没跑）

前置：`adb root`；一台 API 28+ 的 AVD（`buaa36`）；改前包从基线 `fc8c986` 另建一份
`:app:assembleDebug`。**两版都先把 Baseline Profile 的状态对齐**（装机后各跑 3 次冷启动
让 profile 生效再开始计数），否则量到的是 profile 的账不是这张卡的。
播种口径沿用 `docs/STATUS.md` T16 那一节：一个学期 + 多门课、`currentWeek` 落在学期中间、
`onboarding_completed=true` 与 `privacy_consent_at` 两道门都过。

### ① 冷启动 `TotalTime` 改前 / 改后各 ≥10 次取中位数

```
adb install -r -g app-debug.apk
adb shell am force-stop com.buaa.schedule
for i in $(seq 1 10); do
  adb shell am start -W -n com.buaa.schedule/.MainActivity \
      | grep -E 'TotalTime|WaitTime|ThisTime'
done
```

辅助判据（顺带证明 initializer 真的从起手摘掉了）：

```
adb shell setprop log.tag.WM-WrkMgrInitializer DEBUG
adb logcat -c; adb shell am force-stop com.buaa.schedule; adb shell am start -W -n com.buaa.schedule/.MainActivity
adb logcat -d -v threadtime -s WM-WrkMgrInitializer
```

`work-runtime` 那句 "Initializing WorkManager with default configuration." 的 tag 是
`androidx.work.WorkManagerInitializer` 的 `TAG = "WM-" + "WrkMgrInitializer"`。
改前：这一行出现，且 `logcat -v threadtime` 的**线程列 == 进程 pid**（主线程）。
改后：**这一行不该再出现**（按需路径不写它），首帧钱改由 IO 线程付。
预期方向：`TotalTime` 下降或持平；若上升，先查 §2.2 的 `SystemJobService` 那一行是不是恰好撞在同一次启动上。

### ② 「冷进程 + 组件广播」那条路上第一次 `getInstance` 落在哪条线程、多少毫秒

先把进程摆回冷态，再用组件广播把它拉起来：

```
adb shell am force-stop com.buaa.schedule
adb shell am broadcast -a android.appwidget.action.APPWIDGET_ENABLED \
    -n com.buaa.schedule/.widget.TodayWidgetProvider --receiver-foreground
```

（`-n` 显式指名是因为这些 receiver `exported=false`；`--receiver-foreground` 是后台启动豁免。
这条在 Android 14+ 上如果被拦，改用真放一个组件后由桌面发出、或
`adb shell cmd appwidget` —— 别为了跑通命令去改 receiver 的 exported。）

读三样东西，按方便程度排序：

1. **最直接（回答"哪条线程 + 多少毫秒"）：临时在真调用点上计时。**
   插到 `BackgroundSync.cancelLegacyPeriodicWork`（应用里第一次 `getInstance` 的落点）：

   ```kotlin
   val t0 = android.os.SystemClock.elapsedRealtime()
   runCatching { WorkManager.getInstance(context).cancelUniqueWork(LEGACY_PERIODIC_WORK) }
   android.util.Log.i("T18WM", "first getInstance on ${Thread.currentThread().name} " +
       "+${android.os.SystemClock.elapsedRealtime() - t0}ms")
   ```

   然后 `adb logcat -d -v threadtime -s T18WM`。改后应当报
   `DefaultDispatcher-worker-N` + 一个非零毫秒数；改前那一行会接近 0ms
   （provider 已经付过），而那份钱藏在同一次进程启动的 `TotalTime` 里 —— 这正是 ① 的意义。
   ⚠️ 这段计时是**临时**的，跑完删掉：别把它留在树上（同 §2.3 的理由 —— 初始化路径上的
   任何多余动作都会被并发调用点放大）。
2. **主线程那一段有多少毫秒（ActivityManager 侧的权威读数）：**
   `adb shell dumpsys activity broadcasts | grep -B2 -A12 TodayWidgetProvider`
   比对改前/改后的 receiver dispatch 耗时（`broadcastTime` 一类字段）与
   `PendingResult.finish()` 的总耗时：改后应当是"主线程段几乎不涨、总耗时里才含建库"。
3. **不改代码的路线：perfetto（atrace 简写模式，产物直接拖进 ui.perfetto.dev）。**

   ```
   adb shell "perfetto -o /data/misc/perfetto-traces/t18.pftrace -t 8s sched am wm database binder_driver dalvik" &
   # 这 8 秒里把 force-stop + 那次广播跑掉
   adb pull /data/misc/perfetto-traces/t18.pftrace
   ```

   判据：`WORKDATABASE` / SQLite 与到 `system_server(jobscheduler)` 的 binder 那几段
   挂在**非** `com.buaa.schedule` 主线程（tid == pid）的线程线上；改前它们就在主线程线上。

同一组还要顺手验一次**功能没坏**：放一个组件、不动课表、等到（或 `date` 推进）
12 小时后组件仍刷新；以及 `adb shell dumpsys jobscheduler | grep -A5 com.buaa.schedule`
里 `WidgetFallbackWorker` 那条 unique work 存在 —— 这是 §1.2 那个"静默不注册"失败形状的唯一实物证据。

### ③ 预热是否真的把扫码页首帧的 `dlopen` 藏掉了

真实路径（首帧是首页、之后才进扫码页），不要用 `EXTRA_ROUTE` 直达 ——
那样首帧就是扫码页自己，预热来不及，量的不是这条账：

```
adb shell am force-stop com.buaa.schedule
adb shell am start -W -n com.buaa.schedule/.MainActivity
sleep 2                                   # 让"首帧之后"那次预热跑完（两档各 ≤5 秒上限）
PID=$(adb shell pidof com.buaa.schedule)
# (a) 此刻 `.so` 进来了没有 —— debuggable 包可以用 run-as 读自己进程的 maps
adb shell "run-as com.buaa.schedule cat /proc/$PID/maps" | grep -c barhopper
adb shell dumpsys gfxinfo com.buaa.schedule reset
adb shell input tap <首页加号菜单坐标>     # 坐标沿用 T16b 那套交互 CUJ
adb shell input tap <扫码入口坐标>
sleep 2; adb shell dumpsys gfxinfo com.buaa.schedule framestats | head -40
# (b) 载入落在哪条线程、哪一段时间（perfetto 的 atrace 简写模式，产物拖进 ui.perfetto.dev）
adb shell "perfetto -o /data/misc/perfetto-traces/scan.pftrace -t 12s sched am dalvik binder_driver" &
adb pull /data/misc/perfetto-traces/scan.pftrace
```

判据（三件都要成立）：
1. **映射时机**：冷启动停在首页 2 秒后 `(a)` 的第一条 grep 计数就应当 > 0（预热已经把
   `libbarhopper_v3.so` 拉进来了）；关掉预热的那一份在同一时刻应当是 0，
   且要点进扫码页之后才变 > 0。这一条是本项唯一"直接指着那个 `dlopen`"的读数。
2. **帧**：进扫码页那一段的 `framestats` 长帧数不高于对照组；`trace` 里
   `System.loadLibrary` / dlopen 那截挂在后台线程线上、且在首帧之后。
3. **红线**：停在首页时 `adb shell dumpsys media.camera | grep -c com.buaa.schedule` 为 0
   （没开相机），且 `adb shell dumpsys package com.buaa.schedule | grep -A1 "android.permission.CAMERA"`
   的授权状态与预热前一致（没弹权限框）。

对照组怎么造：`ScanChainWarmUp.warmUp()` 里那两行调用各删一行就关一档（解码器一档 / 相机一档），
重新构建一份 APK。这比加开关便宜，也正是"不新增配置项"这条红线的用途。
如果 ③ 量出来收益接近零，**这张卡的第三件事就该整体删掉**（净代价只有 §4.4 那笔常驻内存），
删法就是 `MainActivity.kt:210` 那一次调用 + `ScanChainWarmUp.kt` + 它的 7 条守卫用例。

## 7. 残余风险（复核后认定）

| 风险 | 形状 | 现在的答案 |
| --- | --- | --- |
| 库内在 Service 主线程上 `getInstance` | `SystemJobService.onCreate()`（§2.2） | 应用改不动。是"起手主线程"换成"投递现场主线程"，净收益要 §6①② 量出来才算得清。若 ② 显示这一路上建库的钱落在 Service 主线程且不可接受，退路是给 `BUAAApplication.onCreate` 的 IO 链第一步补一次 `WorkManager.getInstance`（那时它已经在 IO 上），把这笔钱稳定抢在 job 投递之前 —— 本卡**没有**这么做，因为它会让每次冷进程都必付这笔（哪怕桌面上一个组件都没有） |
| 只改一半就静默失效 | 清单与 Application 之间没有编译期联系 | 由 `WorkManagerOnDemandInitTest` ①② 钉住；实物证据仍需 §6② 最后那条 jobscheduler 检查 |
| 组件的后台补注册不再续命 | `goAsync()` 的票被别处先取走 → 返回 null，协程照跑但进程可能先被回收 | 沿用 `takeRebindPendingResult` 既有口径：下一次事件或宿主重绑会补上。`onUpdate` 那条本来就自带票 |
| 预热的常驻内存 | `.so` 映射 + CameraX/ML Kit 类 | 用户不开扫码页时原本是零；§6③ 判定收益后决定留删 |
| 预热两档各 5 秒上限 | 最坏多占两个 IO 线程共 10 秒 | 不占主线程；`applicationScope` 是 `Dispatchers.IO`（线程预算 64），不会被它饿死 |
| `workManagerConfiguration` 在 `sLock` 内执行 | 将来有人往里塞读盘/回头调 WorkManager 会自锁或放大 | getter 只 `Configuration.Builder().build()`，测试逐字比对 + 反向钉住声明行不得出现 `WorkManager.` / `getInstance` |
| 守卫测试是"形状核对" | 线程/时序在 JVM 里跑不出来 | 与 `ColdStartRebuildWiringTest` 同一局限，明确记在这里：**形状对了不等于设备上对了**，§6 的三个数没量完之前本卡不算收工 |
