# 发布与自更新

应用没有上架任何商店，用户拿到新版本、拿到 bug 修复的**唯一**通道就是应用内的
「检查更新 → 下载 → 安装」，对端是这个仓库在 Gitee 上的 Releases。
所以发版流程里的每一步都以「能不能被用户真的装上」为验收标准，而不是「构建绿了」。

## 一条命令

```powershell
powershell -ExecutionPolicy Bypass -File release.ps1 0.2.0   # 改号 + 打包
powershell -ExecutionPolicy Bypass -File release.ps1         # 首发：按 gradle.properties 里已有的号原样打包
```

传号时第一步会拒绝"同号或更小的号"（`stampVersion`），所以首次发布**不要**传号 ——
`VERSION_NAME` 已经写着 `0.1.0`，没有上一个版本可比。

它按顺序做完就停：

1. `:app:stampVersion` —— 把 `0.2.0` 写进 `gradle.properties` 的 `VERSION_NAME`，并把
   `VERSION_CODE` 加一；
2. `:app:testDebugUnitTest :app:lintDebug` —— 门禁，红了就不往下走；
3. `:app:releasePackage`（依赖 `assembleRelease`）—— R8 打包，收进
   `dist/buaa-schedule-<版本>.apk`，打印证书指纹和 Gitee 发布步骤。

`releasePackage` 自己有三道闸，因为"文件名看起来对"不构成证据：

- 收产物前先删掉 `dist/` 里其它版本的 `buaa-schedule-*.apk`（同时躺着两个版本时，上传
  附件拿错文件几乎是必然，而且错了也不会红）；
- 复制完用 `aapt2 dump badging` **读回包内**的 `versionName` / `versionCode`，与本轮
  `gradle.properties` 要求的值逐字核对，不一致就直接失败；
- `-DebugSign` 的中间产物写在 `build/release-work/`，不写 `build/outputs/apk/release/`
  —— 后者归 AGP 独占，它对里面文件的处理不由我们决定。

签名命令走 `ProcessBuilder` 并检查退出码。这里踩过一次坑：`providers.exec {}` 返回的是
**惰性** ValueSource，不去取结果就根本不执行，于是 apksigner 从未跑过、目标文件不存在，
构建在 `copyTo` 处抛 `NoSuchFileException` 才暴露（`project.exec` 同样不能用：8.11 起弃用，
而弃用提示压不掉）。

`release.ps1` 在最后还有一道**意图核对**：回读 `gradle.properties`，确认它等于这次要发的
号、并且 `dist/buaa-schedule-<号>.apk` 确实在，否则红着退出并提醒"别去打 tag"。
`releasePackage` 只能保证"包内版本 == gradle.properties"，它不知道你想要哪个号 ——
改号那步静默失效时（本项目踩过：分支条件写成 `if ($Bump)`，而脚本里没有这个参数），
两头都是绿的，只有这一道核对能把"发出去的号其实没改"拦下来。

只想在真机上验一遍 R8 产物（没有发布密钥时）加 `-DebugSign`，它用本机调试密钥自签。
**这种包不得发布**：换回正式密钥时用户必须先卸载，而卸载会把课表数据库一起带走。

## 版本号只有一个来源

`gradle.properties` 的 `VERSION_NAME` / `VERSION_CODE` 是唯一来源，
`app/build.gradle.kts` 直接读它，读不到或格式不对就在**配置期**失败。

以前是 `-PversionName` 传进来、缺省退回 `"0.1.0"`。少传一次参数的后果不是报错，而是
发出一个自称 `0.1.0` 的包：Gitee 的 tag 已经指着 `v0.2.0`，于是所有已装用户每次冷启动
都会被提示「有更新」，装完版本号纹丝不动，下次再弹一遍 —— 更新通道从此只会制造噪音。
这类失败没有任何日志能看出异常，所以把它改成了"不可能忘"而不是"记得别忘"。

同理，`GITHUB_RUN_NUMBER` 也不再参与 `versionCode`：CI 跑号会一路涨到比正式发布的包还大，
CI 产物一旦外流，之后所有正常版本的包都会因"降级"被拒装。确实需要一个自增号快照包时显式传
`-PVERSION_CODE=12`（属性名是大写的 `VERSION_CODE`，`-PversionCode` 现在没有任何代码读取，
传了只会静默无效），并且只在这种包不会被装进日常设备时使用。

`versionCode` 必须逐次递增：同 `versionCode` 的覆盖安装不算升级，HyperOS 的启动器
（`com.miui.home`）按 `package + versionCode` 缓存应用图标，换图标也不刷新。

## 签名：一把密钥用到底

`local.properties`（不入库）里四要素齐全才会启用发布签名，否则 release 产物是 unsigned：

```properties
buaa.keystore.path=D\:/keystore/buaa-schedule.jks
buaa.keystore.password=…
buaa.keystore.alias=…
buaa.keystore.keyPassword=…
```

键名要逐字对上（是 `buaa.keystore.keyPassword`，不是 `buaa.keyPassword`）：四要素缺一就
静默按 unsigned 处理，构建照样绿，只有装机时才发现。也可以不落地文件，直接给
`buaa.keystore.base64`。

CI 用 `BUAA_KEYSTORE_BASE64` 等环境变量传同一组值（secrets 存不了二进制，构建时解到
`build/keystore/` 下再签）。

**这把密钥一旦公开分发就不可更换。** 换密钥 = 老用户必须先卸载再装 = 课表数据全清。
所以：

- keystore 及其口令要有异地备份，仓库里没有第二份；
- 应用侧在安装前会做一次签名预检（`UpdateCheck.signatureMismatch`）：新包与本机已装包
  的签名证书不一致时，直接把"要先卸载、课表会没、请先导出备份"讲清楚，而不是让系统
  安装器丢一句"未安装"了事。预检拿不到任何一侧证书时**放行**，它只负责"把能预见的
  失败讲明白"，不该因为自己解析不出来拦住一次正常更新。

### 本项目的正式证书（基线，2026-09-16 生成）

- 证书 SHA-256：`FD954BFB88D6C8CDDDDB77E21D564E7263EA8C79F0DCF1639F1278C901A87462`
- 证书 SHA-1：`4247e5a17e98fbf1d9f2f180861b4b0ab5ff1c54`
- DN `CN=Alyssumira, OU=BUAA-Schedule, O=Alyssumira, L=Beijing, ST=Beijing, C=CN`，
  alias `alyssumira`，PKCS12，RSA 2048，有效期 10950 天。
- 指纹是**公开信息**（每个发出去的包里都带着，`apksigner --print-certs` 谁都能读），
  所以写在这里没有泄密问题；它的用途是"一眼认出这个包不是我签的"。
- 文件与口令都在仓库外：keystore 在 `D:/keystore/buaa-schedule.jks`，口令在
  `local.properties`（`.gitignore` 第 4 行，不入库），另有独立备份
  `~/Documents/buaa-schedule-release-credentials.txt` —— **两份不要放在同一处备份**。
- 装过**调试自签**包的设备（本项目验证阶段就是这么装的），第一次装正式签名的包会直接
  "未安装成功"，因为签名不同 —— 必须先卸载，而卸载会把课表数据库一起带走，
  所以先走一次 App 内的导出备份 / 分享口令。

## Gitee 那一侧的要求

- tag 写成 `v<VERSION_NAME>`（去掉 `v` 之后必须与包内版本号逐段相等，比较在
  `UpdateInfo.compareVersions` 里按数值分段做）。
- **tag 要用轻量 tag**：`git tag v0.1.0 && git push origin v0.1.0`。加 `-a` 的附注 tag
  会新建一个 tag 对象，服务端于是把这条历史整个翻一遍 —— 本仓库首个 commit（Gitee 网页
  建的 `Initial commit`）committer 是 `noreply@gitee.com`，与 author 不等，钩子当场
  `hook declined`（2026-09-16 实测）。轻量 tag 指着已经推上去的 commit，没有新对象，能过。
- 没有浏览器会话时全套都能走接口（2026-09-16 首发即这么发的）：
  `POST https://gitee.com/api/v5/repos/alyssumira/buaa-schedule/releases` 创建发布，
  参数 `tag_name` / `name` / `body` 之外**还必须传 `target_commitish`**（填 tag 所指 commit
  的 sha；少传直接 400 `target_commitish is missing`，哪怕 tag 早就推上去了）。
  再 `POST .../releases/{release_id}/attach_files`（multipart 字段名 `file`，成功回 201）上传附件。
  两步都要 `access_token`，即 Gitee 私人令牌里勾了 `projects` 域的那种；
  本项目的令牌放在 `local.properties` 的 `buaa.gitee.token`（同 keystore 口令，不入库）。
- 上传响应里有 `id` / `size` / `label` 等字段，但 **releases 列表接口回给客户端的附件对象
  只有 `name` 与 `browser_download_url`**（2026-09-16 首发后逐字段核对过），
  所以弹窗里的体积只能来自下载响应的 `Content-Length`，这一点没变。
- 标题写版本号，正文即更新说明（弹窗按 markdown 逐行渲染，`#`/`- ` 认）。
- 附件名用 `buaa-schedule-<版本>.apk`（下载目录里按这个名字保留，"重试安装"不必重下）。
- **可以再挂一个调试包**（真机自测要用），但文件名里必须含 `debug`，例如
  `buaa-schedule-<版本>-debug.apk`。客户端选附件的顺序是：精确匹配上面的规范名 →
  退而选第一个名字里不含 `debug` 的 APK → 只剩调试包时**不给下载链接**（弹窗改跳发布页）。
  这么定的原因不是洁癖：调试签名覆盖不了正式签名，把调试包发给装了正式包的用户，
  结果要么是"未安装成功"，要么是把用户逼去卸载重装，而卸载会连带清掉课表数据库。
- **附件必须公开可下载**。仓库私有或附件需要登录时，接口回来的是 200 + 一段 HTML 登录页，
  应用会把它拦在安装器之外（见下面的完整性校验），用户看到的是"下载到的是一份网页"。

## 对端实测行为（2026-09-16，公开仓库、无 token）

代码里的判断都建立在这几条实测之上，接口哪天变了先复核这里：

- release 对象只有 `tag_name / name / body / prerelease / created_at / author /
  target_commitish / assets`。**没有** `html_url`、**没有** `published_at`、**没有** `draft`
  （GitHub 那套全不兼容）。所以发布页直链是应用按 tag 自己拼的，发布时间取 `created_at`。
- 附件对象只有 `{"name", "browser_download_url"}`。**没有** `size`、**没有** `content_type`
  → 包体积无法预知，弹窗里的 MB 只能来自下载响应的 `Content-Length`。
- 直链要经**两跳 302**：`releases/download/<tag>/<file>` → `attach_files/<id>/download/<file>`
  → `foruda.gitee.com/attach_file/…?token=…`，最终 `Content-Type` 是 `application/zip`，
  文件体以 `PK\x03\x04` 开头。CDN 域名拼不出可信白名单，所以应用自己逐跳跟随，
  每跳只允许 https。
- `Range: bytes=0-3` 被忽略 → **不支持断点续传**，中断只能整包重来。
- 附件不存在时是 `404` + JSON 错误体。

因此应用侧的"这是不是一个能装的包"落成四道闸：`Content-Type` 不是 html/json、
写到 `Content-Length` 声明的字节数、文件头是 zip 本地文件头 `50 4B 03 04`、体积不小于
64 KB（当前 release 包的体积见下面「包体与 ABI」那一节）。接口没有校验和可用，只能验到这一层，剩下的交给系统安装器。

## 包体与 ABI（2026-09-18）

- 当前 release 包：**6,386,623 字节**。上一轮（学分列 + 学期统计页）的基线是 2,508,653，
  所以**扫码签到这一轮涨 3,877,970 字节（+3.70MB）**，几乎全是
  `com.google.mlkit:barcode-scanning`；更早记录的 2,453,873 地板见
  `docs/PERFORMANCE_BATTERY_AUDIT.md`，逐项拆解见 `docs/BUAA_SPOC_SIGNIN_PLAN.md` §1.3。
- **剪枝只按 `.so` 文件名做**：`packaging.jniLibs.excludes` 里逐条列出
  `lib/<非arm64>/libbarhopper_v3.so`。**不要**改用 `ndk.abiFilters`，也**不要**写
  `lib/x86/**` 这类目录通配 —— 两者都会连带裁掉 `libandroidx.graphics.path.so`，
  那个 ABI 目录一旦全空，该档设备装包时命中 `INSTALL_FAILED_NO_MATCHING_ABIS`，
  **整个 App 装不上**，而不只是扫码功能不可用。
- **MLKit 的解码库只有 arm64-v8a**，这是换取体积的代价。缺库时抛的
  `UnsatisfiedLinkError` 在 ML Kit **自己的 worker 线程**上（`System.loadLibrary` 写在
  `BarhopperV3` 的实例构造函数里），应用侧的 catch 接不到 —— T24 之前它就是非 arm64
  release 包的"启动约 1.5 秒后 FATAL"。现在的机制是 `BarhopperNativeLibProbe`：在任何一次
  ML Kit 解码调用之前，先在 IO 线程上真去 `loadLibrary("barhopper_v3")` 判一次；判定不可用
  时预热那一档一次 ML Kit 调用都不发，扫码页把 `scanner` 置 null，于是相机分析器不建、
  **相册识别也不再承诺**（它送进的是同一个 `process()`）。这一页**没有任何剩下的出路**：
  「手输签到码」入口已于 2026-09-21 整条删除（现实里不存在可抄的签到码，见 `docs/STATUS.md`
  当日条目），缺库设备上提示文案只说实话 —— 这一页用不了扫码签到。
  因此**模拟器只能验降级路径**，扫码本身必须在 arm64 真机上验，装机自测时别把模拟器绿了当成验过。
- 剩余可攻项（已量过、未实施）：包内 `assets/mlkit_barcode_models/` 的两个
  `oned_*.tflite` 合计 **490,432 字节**服务于一维码，本功能只解 QR，理论上可用
  assets exclude 省掉；未做是因为 assets 布局属 MLKit 内部实现、升级即变。

## 发完之后怎么验

不需要装机，命令行就能确认链路通：

```bash
curl -sIL "https://gitee.com/alyssumira/buaa-schedule/releases/download/v0.2.0/buaa-schedule-0.2.0.apk" | grep -i -E "^(HTTP|location|content-type)"
curl -sL   "https://gitee.com/alyssumira/buaa-schedule/releases/download/v0.2.0/buaa-schedule-0.2.0.apk" -o t.apk && head -c 4 t.apk | od -An -tx1
```

最后一跳应是 `200`，文件头应是 `50 4b 03 04`。

但这两条都不能证明用户装得上。**在旧版本上真点一次「检查更新 → 下载 → 安装」**，
这是唯一能证明整条链路通的手段；这一步没做，就不要宣布发版完成。

## 故障对照

| 现象 | 先看这里 |
| --- | --- |
| "复制到了一个不属于本轮构建的文件" | 包内 `versionName` 与 `gradle.properties` 不符：清空 `build/release-work` 与 `dist` 再重跑，别手上传附件 |
| "apksigner 自签 失败（退出码 …）" | 异常里带 stdout/stderr；常见原因是本机没有 `~/.android/debug.keystore`（跑过一次任意 debug 包就会生成） |
| 装了新版本还是弹"有更新" | 包内 `VERSION_NAME` 是否真等于 tag：`release.ps1` 之外手搓构建最容易漏 |
| 提示"下载到的是一份网页" | 附件/仓库不是公开可读，或 Gitee 登录墙；用上面 `curl -iL` 看首跳 |
| 提示"不是 APK 格式" | 附件传错文件（源码包、改名后的 zip） |
| 每次下载都从头开始 | 正常，Gitee 忽略 `Range` |
| 红点一直不清 | 设计如此：只有装上（或用户点"忽略此版本"）才消，见 `UpdateCheck.pendingUpdateVersion` |
| 点"去开启"没反应 | 厂商 ROM 挪走了 `ACTION_MANAGE_UNKNOWN_APP_SOURCES`，应用已按三档回退，最后一档是应用信息页 |
| "这个版本没有可直接下载的可信链接" | 附件域名不在 `gitee.com` 白名单里（例如有人把附件换成了第三方直链） |
