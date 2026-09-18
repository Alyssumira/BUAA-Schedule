# 智学北航（SPOC）扫码签到接入方案

日期：2026-09-18　状态：**已落地**（§9 的 1–4、6–7 步完成；第 5 步真机联调待做）。
落地与原方案的出入记在 §11，不要按 §5/§6 的正文读实现细节。

## 0. 取证结论（线上真实源码，非推断）

解析产物留在 `D:\schedule\.tmp\`：`spoc_index.js`（主包）、`spoc_signIn.js`、
`spoc_signDetail.js`、`spoc_pages-table-startCourse.js`。

| 事实 | 证据位置 |
| --- | --- |
| 签到页路由 `/pages/table/signIn`，`onLoad` 读 `zjdm/czid/step/qdid` | `spoc_signIn.js` 模块 `44e0` `onLoad` |
| 有 `qdid` 时调 `queryQdxxByQdid` 判断**是否已签**，已签直接渲染 step=2 | 同上 |
| 提交签到 `POST spocxssk/saveXsqd`，body `{zjdm, czid, xh}` | 模块 `edf7` `apiStuSign` |
| `qdid` 就是签到活动的 `CJID`（不是独立 ID） | `startCourse` chunk `lookStuActivity`：`signIn?qdid=` + `t.CJID` |
| 学生从活动列表进签到**不依赖扫码**：`queryQdhdByHdid{hdid} → {ZJDM,CZID}` | `startCourse` chunk `stuGoSign` |
| 签到成功后 WebSocket 推 `hdjtyw:"xsqdcg"` 给老师端 | `spoc_signIn.js` `sendSuccessMsg` |
| **鉴权是请求头 `token: "Inco-"+JWT` + `rolecode`，不是 Cookie** | `spoc_index.js` httpRequest 拦截器（偏移 153272） |
| token 失效走 `sys/refreshToken{refreshToken}` 续期，失败跳 `casmobile` | 同上，偏移 154158 / 155482 |
| Base：`https://spoc.buaa.edu.cn/spocnewht/` | `spoc_index.js` `baseUrl` 常量 |
| H5 前端无 `uni.scanCode`，扫码能力在原生壳 | 主包 grep `scanCode` = 0 命中 |

**对既有报告的两处修正**：`qdid == CJID`（原报告当成独立 ID）；原生 HTTP 可用（原报告按
byxt/gsmis 的 401 外推，SPOC 走 token 头，不存在该问题）。

**唯一未取证项**：老师端二维码的字面内容（H5 里没有生成逻辑，在 APP 原生侧）。
→ 用 `SpocQrParser` 三形态兼容 + `queryQdxxByQdid` 一次调用自证，真机首联即锁定。

## 1. 包体代价（已实测，非估算）

`com.google.mlkit:barcode-scanning:17.3.0` AAR 9,898,786 字节，内含单一
`libbarhopper_v3.so`，四 ABI：

| ABI | 原始 |
| --- | --- |
| x86 | 6,122,368 |
| x86_64 | 5,909,280 |
| arm64-v8a | **4,946,720** |
| armeabi-v7a | 3,244,440 |

本工程未设 `abiFilters`，且 `useLegacyPackaging` 未开（AGP 默认 .so 不压缩入包）
→ 直接加依赖 = 通用 APK **+20.2MB**。

### 1.1 现状：APK 里已经有一个 native 库

`dist/buaa-schedule-0.1.1.apk` 的 `lib/` 下只有一个成员，来自
`androidx.graphics:graphics-path:1.0.1`（`androidx.compose.ui:ui-graphics` 的传递依赖，
全仓无任何 `androidx.graphics` 直接引用）：

| ABI | `libandroidx.graphics.path.so` |
| --- | --- |
| arm64-v8a | 10,096 |
| armeabi-v7a | 7,252 |
| x86 | 9,284 |
| x86_64 | 10,760 |

合计 37,392 字节。**结论：项目自身对 ABI 没有任何要求**，arm64 剪枝不影响自己的代码。

### 1.2 因此剪枝必须「按文件」，绝不能「按 ABI」

```kotlin
packaging {
    jniLibs {
        // 只删 MLKit 自己那一个库的非 arm64 版本。
        excludes += setOf(
            "lib/armeabi-v7a/libbarhopper_v3.so",
            "lib/x86/libbarhopper_v3.so",
            "lib/x86_64/libbarhopper_v3.so",
        )
        useLegacyPackaging = true   // .so 压缩入包
    }
}
```

两条红线，写死在这里以免日后改错：

- **不要用 `defaultConfig.ndk.abiFilters`**。它是全局开关，会把
  `libandroidx.graphics.path.so` 的 v7a/x86/x86_64 一起裁掉；一个 ABI 目录全空之后，
  该档设备装包时命中 `INSTALL_FAILED_NO_MATCHING_ABIS`，**整个 App 装不上**，
  而不只是扫码功能不可用。x86_64 模拟器同样中招。
- **不要用 `lib/x86/**` 这类目录通配**（本文早先版本这么写过，是错的）。目录级 exclude
  与上一条同害，会连带删掉 graphics-path。按 `.so` 文件名精确排除才只伤 MLKit。

### 1.3 实测代价（2026-09-18 首次落地构建）

`./build.cmd :app:assembleRelease` → **6,066,854 字节**（地板 2,453,873 → +3,612,981，
即 **+3.44MB**）。按 APK 内实际占字节拆解：

| 项 | 入包字节 | 说明 |
| --- | --- | --- |
| `lib/arm64-v8a/libbarhopper_v3.so` | 2,105,673 | 原始 4,946,720，`Defl:N` 压缩已生效，与预估 2,103,961 吻合 |
| `assets/mlkit_barcode_models/*.tflite` | 880,888 | **预估漏项**，且是 `Stored` 不压缩（`useLegacyPackaging` 只管 .so） |
| `classes.dex` 增量 | 349,322 | CameraX + MLKit 的 Java/Kotlin |
| `res/` + `resources.arsc` | 198,304 | MLKit/CameraX 自带资源与字符串 |
| `lib/*/libimage_processing_util_jni.so` 四档 | 68,155 | CameraX 的；非 arm64 那三份留着给模拟器 |
| `lib/*/libsurface_util_jni.so` 四档 | 6,778 | 同上 |
| `lib/*/libandroidx.graphics.path.so` 四档 | −19,546 | graphics-path 跟着一起被压缩，唯一负项 |
| 其余（META-INF / baseline / other） | 24,864 | |

那 880,888 字节的模型是三个文件：

- `barcode_ssd_mobilenet_v1_dmp25_quant.tflite` 390,456 —— 2D 检测器，**QR 必需**
- `oned_feature_extractor_mobile.tflite` 276,552 —— 一维码（EAN/UPC）
- `oned_auto_regressor_mobile.tflite` 213,880 —— 一维码

本功能只解 `FORMAT_QR_CODE`，两个 `oned_*` 理论上可用
`packaging { assets { excludes += "mlkit_barcode_models/oned_*.tflite" } }` 省掉
**490,432 字节**。没写进第 1 步：assets 布局属 MLKit 内部实现、升级即变，
且属于"省不到要害就得真机确认扫二维码不碰一维码模型"的那类改动。留作体积攻坚项。

备选（若日后反悔）：ZXing core 607,650 字节纯 Java，R8 后约 +250~400KB，无 GMS 依赖，
但暗光/斜拍识别率明显弱于 MLKit。

**arm64-only 的后果要认**：armeabi-v7a 老机（Android 8~9 低端）装上后一进扫码页
`UnsatisfiedLinkError` 直接崩。所以扫码入口必须带 native 可用性探测，不可用时
降级为「相册选图识别」+「手输签到码」，且**不能**让 MLKit 的类出现在启动路径上
（放 `remember` 惰性构造，避免全局 `dlopen`）。x86_64 模拟器同理走降级。

## 2. 依赖改动

`gradle/libs.versions.toml` 新增：

```toml
camerax = "1.4.1"
mlkitBarcode = "17.3.0"

androidx-camera-core = { group = "androidx.camera", name = "camera-core", version.ref = "camerax" }
androidx-camera-camera2 = { group = "androidx.camera", name = "camera-camera2", version.ref = "camerax" }
androidx-camera-lifecycle = { group = "androidx.camera", name = "camera-lifecycle", version.ref = "camerax" }
androidx-camera-view = { group = "androidx.camera", name = "camera-view", version.ref = "camerax" }
mlkit-barcode-scanning = { group = "com.google.mlkit", name = "barcode-scanning", version.ref = "mlkitBarcode" }
```

`app/build.gradle.kts`：CameraX 四件 + MLKit 一件 `implementation`。
MLKit bundled 自带模型，**不需要** GMS，符合华为/澎湃机型现状。
`camera-view` 只为 `PreviewView`；若嫌重可用 `SurfaceProvider` + `Preview` 的
Compose 封装替代（先按标准做法，体积数据出来后再决定砍）。

`AndroidManifest.xml`：`<uses-permission android:name="android.permission.CAMERA" />` +
`<uses-feature android:name="android.hardware.camera" android:required="false" />`
（required=false，无相机设备不应被商店过滤掉，功能走降级）。

`proguard-rules.pro`：MLKit/CameraX 自带 consumer rules，预期不需要补；若 release
出现 `BarcodeScanner` 反射丢失再补 `-keep`，不要提前加。

## 3. 会话层：`data/import/SpocSession.kt`（新）

与 `BuaaWebSession` **并列、不复用**——域不同（`spoc.buaa.edu.cn` vs `byxt/gsmis`）、
鉴权机制不同（token 头 vs Cookie），强行合并只会让两边都长出 if。

复用现成手法：
- 登录 WebView：抄 `BuaaWebSession.createSessionWebView:432` / `BuaaLoginScreen.kt:84` 的
  SSO WebView 结构，起始 URL `https://spoc.buaa.edu.cn/spocnewht/casmobile`；
  导航监听到 URL 回到 `spoc.buaa.edu.cn/bhspoc/` 即视为登录完成。
- 取 token：uni-app H5 的 `uni.setStorageSync` 落在 **localStorage**，用
  `BuaaInPageFetcher` 同款 `evaluateJavascript` 读
  `token / refreshToken / xh / rolecode / yhdm / photo` 六键。
- 落盘：`BuaaCookieStore` 的 KeyStore AES-GCM 方案照搬（新 prefs 名
  `spoc_token_store`）。
- 续期：`POST sys/refreshToken {refreshToken}` → `Inco-`+新 token；失败清会话并弹登录。
  token 临期判断走 JWT `exp`（Base64 解 payload，不引库）。
- `backup_rules.xml` / `backup_rules_legacy.xml`：**必须**把 `spoc_token_store` 加进排除，
  与现有会话材料的处理口径一致。

## 4. 接口层：`data/import/SpocApi.kt`（新）

`HttpURLConnection`（**不复活 OkHttp**，与 `BuaaApi.kt` 同构：浏览器 UA + JSON body +
`token`/`rolecode` 头）。四个方法够用：

| 方法 | 路径 | body |
| --- | --- | --- |
| `querySignByQdid` | `spocxssk/queryQdxxByQdid` | `{qdid}` |
| `querySignList` | `spocxssk/queryQdhdListByZjdm` | `{zjdm}` |
| `querySignByHdid` | `spocxssk/queryQdhdByHdid` | `{hdid}` |
| `submitSign` | `spocxssk/saveXsqd` | `{zjdm, czid, xh}` |

响应统一 `kotlinx.serialization`，容忍大小写字段（服务端返回 `ZJDM/CZID/QDSJ/ID` 全大写）。
网络失败/会话过期各自抛类型化异常，沿用 `BuaaSessionExpiredException` 的写法新建
`SpocSessionExpiredException`。

## 5. UI：加号菜单入口 + 扫码页

**入口**：`ui/home/HomeScreen.kt:641` 的 `LiquidMenu` 追加
`LiquidMenuItem(Icons.Default.QrCode2, "扫码签到") { onSpocSignIn() }`。
菜单从 4 项变 5 项，`LiquidMenu` 是右下锚点生长，需回看展开高度是否吃掉 FAB 上方
撤销条区域（`SnackbarHost` 同一个抬升口径）；顺手更新 FAB 的 `contentDescription`
文案，播报要跟真实行为一致。

**路由**：`MainActivity.kt`（现有 `buaa_login` 等路由旁）注册 `spoc_scan`，
另注册 `signin` scheme 的 intent-filter，让课前提醒的 action 能深链直达。

**扫码页** `ui/signin/SpocScanScreen.kt`（新）：
1. 未授予 CAMERA → 系统授权弹窗（`rememberLauncherForActivityResult`，
   口径同 `MainActivity.kt:111`）；拒绝后留在页面给「相册识别」出口。
2. 已授予且 native 可用 → `Preview` + `ImageAnalysis`（`STRATEGY_KEEP_ONLY_LATEST`）
   → MLKit `BarcodeScanning.getDefaultScanner()`，约束：仅 `FORMAT_QR_CODE`、
   `inputImageRotationDegree` 跟随传感器方向、扫码成功即 `close` 分析流（防重复提交）。
3. 兜底：相册选图 → `InputImage.fromFilePath`；手输签到码输入框。
4. 无相机/ABI 不支持时整页只显示后两项。

**状态机** `ui/signin/SignInViewModel.kt`（新，`AndroidViewModel` + `StateFlow`，
与 `ScheduleViewModel` 一致）：
`Idle → Parsing → Resolving → Submitting → SignedAt(time) | Failed(reason)`。

**自动提交（按你的决定）**：解析出 `qdid` → `querySignByQdid` 取 `zjdm/czid` →
直接 `saveXsqd`，不弹确认页。他班的码由服务端判定拒绝，UI 只如实回显失败原因，
本地不做「是不是我的课」的判断。`querySignByQdid` 返回已签状态时直接渲染 step=2 语义的
「签到完成 + QDSJ」，不重复提交。WebSocket 的 `xsqdcg` 推送**不复刻**：那是给老师端
界面加速的广播，服务端落库不依赖它（若真机发现老师端必须等它才刷新，再补一个
OkHttp-WebSocket 单连接，发完即关）。

## 6. 课前签到提醒

生效范围取**全局开关、所有课**（本地课表与 SPOC 课程无映射，不做按课标记）。

**不做第二个调度器**：课前 alarm 已经存在，加标志位复用，避免两套链条互相重排
（上一轮「自重排拆掉课前倒计时」就是这个坑）。

- `reminder/ClassProgressScheduler.kt:42` `ClassWindow` 增 `spocSignHint: Boolean`，
  `toExtras():74` / `from():112` 同步读写。
- `reminder/ReminderScheduler.kt:133` `planNextReminder`：读设置开关，把标志带进 plan。
- `reminder/ReminderReceiver.kt:81` 发横幅处：开关开启时给通知加
  `addAction("扫码签到", deepLinkIntent)`；同时把 `BackgroundSync` 触发的一次
  静默探测（`querySignList`）结果写进文案，探到进行中活动就显示活动名，探不到就
  保持原样。**探测失败绝不能挡住本地提醒**，整段 `runCatching` 包住。
- 通道沿用 `ReminderNotifications.kt:31` 现有 `CHANNEL_SPECS`，不新增通道
  （新通道要用户重新放行，收益为零）。

**开关**：`ui/settings/SettingsScreen.kt` 的「通知与提醒」组（`SettingsSection.NOTIFICATION`），
用 `SettingsSwitchRow`（`core/designsystem/SettingsStack.kt:359`）加一行，
模板照「明日课程预告」`:1309-1332`：prefs 存 `schedule_settings`，变更即
`BackgroundSync.rescheduleReminders`，并顺带引导 SPOC 登录（未登录时行尾显示「未绑定」）。

## 7. 文档

- `docs/BUAA_API.md`：新增「SPOC 链路」一节，写清 token 头鉴权与四条接口，
  并显式记录它与 byxt/gsmis 的 401 问题是**两回事**。
- `docs/PRIVACY.md`：新增 CAMERA 权限、扫码数据流向（图片不出本机；签到请求发往
  `spoc.buaa.edu.cn`）、token 本地加密存储与备份排除。
- `docs/RELEASE.md`：体积表更新 + 「MLKit 仅 arm64、x86 模拟器走降级」的说明。
- `docs/KNOWN_ISSUES.md`：二维码字面内容未取证这条，联调后闭环或转正式记录。

## 8. 测试

- 纯函数单测（JVM，进现有 435 条里）：
  - `SpocQrParserTest`：完整 H5 URL / `?zjdm=&czid=` / 裸 CJID / 无关二维码 四类；
  - JWT `exp` 解析与续期判定；
  - 响应大小写字段兼容反序列化；
  - `ClassWindow` 标志位 extras 往返。
- **禁用 `connectedDebugAndroidTest`**（会清空课表库，见既有记录）；真机验证一律
  `./gradlew :app:assembleRelease` + `adb install -r`，之后装机由你自己操作。
- 真机首联必答的三个问题：① 二维码到底是什么；② 原生 `saveXsqd` 是否真能不带 Cookie
  只靠 token 头通过；③ 老师端是否依赖 `xsqdcg` 广播才刷新签到列表。

## 9. 实施顺序（每步可独立验证）

1. 依赖 + `packaging` ABI 裁剪 → 立刻出体积数，确认是否接受新地板。
2. `SpocSession` + SPOC 登录页 → 能拿到并持久化 token，杀掉进程重启仍在。
3. `SpocApi` + `SpocQrParser`（含单测）。
4. 扫码页 + 状态机 + 加号菜单入口 + 路由/深链。
5. 真机联调，锁定二维码格式，必要时收敛解析器。
6. 课前提醒标志位 + 设置开关。
7. 文档四处 + 全量 `./build.cmd`（lint 0e 与 435+ 测试不退）。

## 10. 已定决策留档

| 决策 | 结论 |
| --- | --- |
| 扫码器 | MLKit bundled，**只保留 arm64-v8a**，`.so` 压缩入包 |
| 提交时机 | 扫到**自动提交**，不做二次确认；他班码交服务端判定 |
| 签到通道 | 原生 HTTP 为主，in-page fetch 兜底 |
| 提醒范围 | 全局开关，作用于所有课；不建 SPOC 课程映射 |
| 无 GMS 依赖 | bundled 自带模型，不引 `play-services-code-scanner` |

## 11. 实际落地与原方案的偏差（2026-09-18）

正文（尤其 §5/§6）是按"准备开工"写的，下面这些是动手之后发现的、与正文不一致的地方。
读实现细节看这一节，别看正文。

| 正文的说法 | 实际落地 | 为什么 |
| --- | --- | --- |
| §1.3 地板 6,066,854 字节 | **6,386,623 字节**（较 §1.3 再 +319,769；较接入前的 2,508,653 是 +3,877,970） | 那个数是第 1 步（只加依赖）量的。当时没有任何代码引用 CameraX/MLKit，R8 把大部分类删了；扫码页与状态机接上之后才露出真实的 319,769 字节。以 §1.3 的口径报体积会长期偏小 |
| §6 用 `querySignList` 静默探测，把活动名写进提醒文案 | **作废，不做** | 四条签到接口都以 `zjdm / qdid / hdid` 为键，而本地课表与 SPOC 课程**没有映射**（§10 已记），课前那一次没有任何一键可取。探测连请求都发不出去，不是"探不到" |
| §6 `ClassWindow` 增 `spocSignHint` 标志位，`toExtras/from` 同步读写 | **不加标志位**，`ReminderReceiver` 在弹通知的那一刻现读 `schedule_settings` | 闹钟是几十分钟前排好的。烘进 extras 的话，用户上课前两分钟把这行关掉，这一节仍然带着按钮 —— 开关管不住它管得着的那一次 |
| §5 另注册 `signin` scheme 的 intent-filter 供深链 | **不加 intent-filter**：通知的 PendingIntent 显式指向 `MainActivity`，用 `EXTRA_ROUTE` 传路由名，且只认白名单 `spoc_scan / spoc_login` | `MainActivity` 是 launcher 导出页，任何外部应用都能塞同样的 extra 进来；白名单把这扇门收窄到"能跳去这两个页面"，比再开一个 scheme 少一个攻击面 |
| §5 状态机 `Idle → Parsing → Resolving → Submitting → …` | **没有 `Parsing` 态**：`Idle → Resolving → Submitting → Signed/Failed` | 解析二维码是一步同步调用，单独一个状态在界面上没有任何一帧显示得出来，只是多一个要处理的分支 |
| §5 `BarcodeScanning.getDefaultScanner()` | `getClient(FORMAT_QR_CODE)` | 只解 QR，多解一种格式是给每一帧多加一次解码开销；而且构造点就是 `remember` 里的探测点，`UnsatisfiedLinkError` 当场翻成降级 |
| §6 设置里"加一行开关" | 同一分组里三行：开关 + 账号行（登录态，点击进登录/扫码）+ 退出登录 | 只有开关的话，用户读完说明还是得回首页找加号；未登录时开关点开也只是当场失败 |
| §8 单测四项 | 实落 3 个文件、19 条（`SpocQrParserTest` 11 / `SpocJwtTest` 4 / `SpocApiFieldTest` 4）。`ClassWindow` 标志位的 extras 往返测试随该标志位一起取消 | 全量 `testDebugUnitTest` 527 条通过，`lintDebug` 0 error / 19 warning（与接入前同基线） |
| §9 第 5 步「真机联调，锁定二维码格式」 | **未做** | 需要真人在教室里对着课堂二维码扫一次；三个待答问题原样记在 §8 末尾，并已转成 `docs/KNOWN_ISSUES.md` §11 的正式记录 |

另外两条踩坑记录，正文里没法预判：

- `ImageProxy.image` 的 opt-in **必须**用 `@androidx.annotation.OptIn(markerClass = [...])`。
  写 `kotlin.OptIn` 编译器是过了，但 lint 的 `UnsafeOptInUsageError` 只认 androidx 那个，
  门禁当场红（本项目不引 lint baseline，error 必须真修）。
- 响应解壳不能只认对象：列表接口的 `content` **直接就是数组**（页面里做 `listData.concat(i)`）。
  只按 `JsonObject` 取会把「接口改版」伪装成「没有签到活动」，所以改成 `JsonElement` +
  `expectObject()` / `expectList()` 显式校验形态。
