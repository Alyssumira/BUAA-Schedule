# BUAA Schedule（北航课程表）

<p align="center">
  <img src="BUAA-Schedule.png" width="320" alt="BUAA Schedule" />
</p>

> 给北航写的课程表 App：登录教务抓课表、课前提醒、上课铃、桌面小组件，
> 在支持的机型上还会把课堂进度送到状态栏的实况里。课表数据只存在你自己的手机上。

[![下载 v0.1.0](https://img.shields.io/badge/下载-v0.1.0-2f6fed)](https://gitee.com/alyssumira/buaa-schedule/releases)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3ddc84)
![MIT](https://img.shields.io/badge/License-MIT-lightgrey)

声明：本项目全部代码均由 AI 生成。

---

## 安装

1. 到 [Releases 页面](https://gitee.com/alyssumira/buaa-schedule/releases) 下载
   `buaa-schedule-0.1.0.apk`。**只下这一个**：同一页还有一个 `-debug.apk`，那是作者装机自测用的
   调试签名包，和正式包签名不同，装了它之后正式包就无法直接覆盖安装。
2. 安装并打开。以后不用再来这个页面 —— 设置里的「检查更新」会自己发现新版本。
3. 第一次打开会走一段引导。**建议照着点完**，尤其是「厂商后台放行」那一步，
   否则在小米/澎湃、OPPO、vivo 这类系统上，上课铃可能一整个学期都不响（原因见下文）。

要求 Android 8.0 及以上。应用没有上架任何商店，源码与安装包都在这个仓库里。

## 日常怎么用

### 导入课表

在「导入」里登录学校统一身份认证，账号密码只交给学校的登录页面，App 只是把那个页面显示给你。
抓回来的课表先进**预览**：新增 / 更新 / 冲突逐条列出，你可以取消勾选任何一条，确认之后才写进本机；
抓取过程随时可以取消，登录会话还在，不用重新输密码。

不想登录也有别的入口：粘贴纯文本、选 ICS 文件、或者输入同学发给你的**分享口令**。
手动加的课程（比如自己蹭的课）在按学期覆盖导入时会被保留。

### 看课表

- 周视图有两种模式：按节次排的行（默认），以及 24 小时时间轴；顶栏写着「课次 / 时间」的按钮切换，
  左右横滑翻周，也可以直接跳周
- 今日视图列出当天全部课程与教室，时间轴模式带上下课时间
- 按校区筛选；「课表管理」按课程归并，可以整门课改色或删除
- 时间冲突有处理向导：按组给出同一天最近的空位建议，一键只改冲突的那几周
- 开学日期、总周数、每节课的时间都能自己配；换学期在设置页切换即可，不用重建数据
- 平板或折叠屏展开（≥600dp）时，周视图和日视图会自动变成双栏并排

### 提醒与实况

- 课前提醒、上课铃、下课铃；上课时自动进入勿扰，下课恢复你原来的状态
- 提醒可以选「应用内通知」或「系统日历提醒」，只走一条，避免同一节课响两次
- 在支持实况的系统上（澎湃 OS 的超级岛、ColorOS 的流体云已实测），课前倒计时和
  课中进度会显示在状态栏；其它系统退化成一条常驻通知，功能不减但存在感低很多

### 桌面小组件

五种：今日课程、明日课程、本周课表、本周网格 4×2、下一节课。
每个组件**单独**配色（跟课表配色或自定义）、背景、不透明度、圆角、文字颜色，另有 10 套一键样式；
调整时预览实时跟着变。课表一改、以及每天零点，组件都会自己刷新，不占后台轮询。

### 外观

液态玻璃默认**关闭**（设置里只有「开启 / 关闭」两项）：整屏的大块玻璃面板开销最高、观感却不如小面积
玻璃，所以默认只在小面积保留，想要全开在设置里勾上即可。深浅色跟随系统。课表背景默认取系统桌面壁纸
（Android 14 起系统不再让普通应用读壁纸，这时会自动回退成渐变，或者你在设置里自选一张图）。
排版有 10 级刻度可调，翻周、切视图、选校区都有触觉反馈。

### 把数据带走（或传给别人）

| 想做的事 | 用哪个 | 注意 |
| --- | --- | --- |
| 换机 / 怕丢 | 备份与恢复（JSON） | 卸载会清掉本机数据库，先导出 |
| 发给同学 | 分享口令 | 口令是**明文编码**，拿到就能还原完整课表，别发到公开场合 |
| 进系统日历 | 日历增量同步 / 导出 ICS | 内容变了只改差异，可一键移除 |
| 给 WakeUp 课程表用 | 导出 WakeUp 兼容 JSON | 这个格式**不能**再导回本应用 |
| 贴到聊天里 | 复制本周课表纯文本 | — |

## 提醒不响、实况不出现怎么办

九成是国产 ROM 的后台清理，不是 App 坏了。引导最后一页有「环境自检」，任一项没过都会直接给出
对应的系统页面：

| 检查项 | 没通过的后果 | 在哪里开 |
| --- | --- | --- |
| 通知权限 | 提醒不响，实况也不出现 | 系统设置 → 通知 |
| 精确闹钟 | 上课铃被推迟到下一次批量唤醒 | 系统设置 → 闹钟与提醒 |
| 电池优化豁免 | 熄屏后后台闹钟被清掉 | 引导里的「省电策略：无限制」 |
| 自启动 / 后台管理 | 重启手机后提醒失效 | 安全中心 / i管家 → 应用管理 → BUAA 课表 |
| 后台弹出界面（小米 / 澎湃） | 实况上不了岛 | 设置 → 应用 → BUAA 课表 → 权限 → 其他权限 |
| 实况提升权限 | 只影响新系统的岛，老系统无此项 | 装上声明了该权限的版本即可 |

更细的现象对照和厂商差异记录在 [`docs/KNOWN_ISSUES.md`](docs/KNOWN_ISSUES.md) 与
[`docs/VENDOR_NOTES.md`](docs/VENDOR_NOTES.md)。

## 数据与隐私

- 课表、设置、壁纸取景、提醒配置全部存在本机应用私有目录。没有账号系统，没有云同步，
  也没有任何统计埋点或广告 SDK。
- 对学校的网络请求只发生在你主动点「导入 / 刷新」的时候；检查更新只访问 Gitee 的公开接口。
- 卸载应用会带走本地数据库。重要课表请保留备份文件或分享口令。
- 每一项权限用来干什么，写在 [`docs/PRIVACY.md`](docs/PRIVACY.md)。

## 已知限制

- 不做 AI 导入、AI 助手、智能推荐；也没有国际化，界面文案是中文。
- 图片和 PDF 导入还没做。
- 桌面小组件的背景是"缩放得到的廉价模糊"，不是真正的高斯模糊。
- 冷启动还没做 Baseline Profile 优化。
- 实况的具体样式由各厂商系统决定，同一份通知在澎湃 / ColorOS / 原生上的呈现可能不同。

## 反馈

Issues：<https://gitee.com/alyssumira/buaa-schedule/issues>

报问题前请先翻一眼 [`docs/KNOWN_ISSUES.md`](docs/KNOWN_ISSUES.md)。如果方便，带上机型与系统版本、
「环境自检」页的截图，以及是"没响"还是"响了但没上岛"——这三样基本能一次定位。

---

## 给想改代码的人

工具链：JDK 17 或 21（AGP 8.13 要求 ≥17；Gradle 8.14.3 **不支持 Java 25**，若本机装了新版
Android Studio，需显式指定 JDK）。

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew assembleDebug
```

CI 里的任务一律写成 `:app:` 前缀 —— 裸任务名会被 Gradle 匹配到所有子工程，把 `:benchmark` 一起拉进来。

子模块：`:app`（应用）、`:kyant-backdrop`（vendored 液态玻璃库）、`:benchmark`（宏基准 /
Baseline Profile 生成，**尚未接入 `:app`**，其用例目前不在任何 CI 或本地任务里执行）。

### 测试

```bash
./gradlew testDebugUnitTest          # 单元测试（JVM，不需要设备）
./gradlew connectedDebugAndroidTest  # 仪器测试（模拟器或真机）
```

| 类型 | 用例数 | 文件数 | 覆盖范围 |
| --- | --- | --- | --- |
| 单元测试 | 345 | 56 | 周次解析 / 教学周计算 / 冲突检测 / 导入规划 / 备份 schema / ICS 与文本解析与往返 / 节次分段与连堂判定 / 教务抓取脚本契约 / 日历投影选择 / 提醒排程与明日预告推送集合 / 分享编解码 / Widget 外观 / Gitee 发布解析与安装包完整性与附件选择 / 实况卡片文案与倒计时口径 |
| 仪器测试 | 55 | 12 | Room 迁移 / Repository 提醒写入与事务 / Widget 刷新新鲜度与渲染契约 / WebView 会话保留与隐藏宿主 / 课堂铃生命周期 / 日历同步部分失败 |

仪器测试跑在 API 29 + API 34 模拟器上（CI 同配置）：Room 迁移与 WebView 相关用例需要真实
Framework 环境，API 34 一档用于覆盖 Android 14 的行为收紧（壁纸读取、精确闹钟等）。

### 发布

版本号只有 `gradle.properties` 里的 `VERSION_NAME` / `VERSION_CODE` 一个来源，缺它或格式不对会在
配置期直接失败。发版走一条命令：

```powershell
powershell -ExecutionPolicy Bypass -File release.ps1 0.2.0
```

它按「改号 → 门禁 → R8 打包 → 收产物」的顺序执行，红了就停。签名密钥不入库，
在 `local.properties` 配 `buaa.keystore.path/.password/.alias/.keyPassword`
（或 `BUAA_KEYSTORE_*` 环境变量），四要素齐全才产出正式签名的 release，否则是 unsigned。
完整流程、Gitee 那侧的要求、以及自更新链路的实测行为见 [`docs/RELEASE.md`](docs/RELEASE.md)。

### 文档

| 文件 | 讲什么 |
| --- | --- |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | 架构总览与包边界 |
| [`docs/DESIGN_SYSTEM.md`](docs/DESIGN_SYSTEM.md) | 设计系统约束（颜色 / 排版 / 动效令牌） |
| [`docs/BACKUP_FORMAT.md`](docs/BACKUP_FORMAT.md) | 备份文件与分享口令的格式 |
| [`docs/PRIVACY.md`](docs/PRIVACY.md) | 数据清单、备份规则、权限逐项说明 |
| [`docs/KNOWN_ISSUES.md`](docs/KNOWN_ISSUES.md) | 已知问题与平台限制（报 bug 前先看） |
| [`docs/VENDOR_NOTES.md`](docs/VENDOR_NOTES.md) | 厂商 / ROM 适配笔记（按证据分级） |
| [`docs/RELEASE.md`](docs/RELEASE.md) | 发版与自更新 |
| [`docs/STATUS.md`](docs/STATUS.md) | 功能状态清单：已落地 / 不做 / 待实现 |
| [`docs/BUAA_API.md`](docs/BUAA_API.md) | 北航教务接口参考（改导入链路时看） |

## 第三方与参考项目

本项目不是从零堆出来的：液态玻璃那一层渲染、以及几个交互控件的手感，直接用到了别人的成果。
下面逐条写清**用了谁的什么、按什么许可证用**。

### 随安装包分发的代码

| 项目 | 许可证 | 用到哪里 |
| --- | --- | --- |
| [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（tag 2.0.0） | Apache-2.0 | 液态玻璃渲染内核，以 `:kyant-backdrop` 目录 vendored。本地改动：把上游的 KMP 结构（commonMain / androidMain）拍平成纯 Android 库，并把 Compose 1.11 / Kotlin 2.3 的 API 适配到本项目的组合 |
| [Kyant0/Shapes](https://github.com/Kyant0/Shapes) | Apache-2.0 | G2 连续曲率圆角形状，内嵌在 `kyant-backdrop/…/com/kyant/shapes/`。Maven 上各版本都以 Kotlin 2.3 编译、与本项目 Kotlin 2.0 不兼容，故只能内嵌源码 |
| [SleepDown课程表](https://github.com/xiaomanjun233/SleepDown-Schedule) | 署名-非商业、源码可见 1.1（非 OSI 开源许可） | `:kyant-backdrop` 里 14 个带 `Modified for SleepDown` 头的文件（共享模糊 `SharedBlurBackdrop`、取景录制缓存等），以及 `core/designsystem/liquid/` 的 `DampedDragAnimation`、`DragGestureInspector`、`InteractiveHighlight`、`LiquidBottomTab` |
| AndroidX / Jetpack：Compose BOM 2024.12.01（ui / foundation 1.11.3、material3 1.3.1）、Room 2.8.3、Navigation 2.8.5、WorkManager 2.9.1、Lifecycle 2.8.7、Core KTX 1.18.0 | Apache-2.0 | 常规运行库 |
| Kotlin 运行时与 kotlinx-coroutines 1.9.0、kotlinx-serialization-json 1.8.1、OkHttp 4.12.0 | Apache-2.0 | 常规运行库 |
| JUnit 4.13.2、androidx.test / Espresso / UiAutomator / benchmark | EPL-1.0 / Apache-2.0 | 只在测试里，不进 APK |

### 只参考过、没有取代码的项目

- [xingheyuzhuan/shiguang_warehouse](https://github.com/xingheyuzhuan/shiguang_warehouse)（拾光，MIT）：
  24 小时连续时间轴与"多个组件共用水源"的目标形态。
- [lingion/sleepy](https://github.com/lingion/sleepy)（GPL-3.0）：只借了「一键外观预设」的思路。
  已按文件名 + 相似度逐一比对过，**没有任何重合代码**；特此写明以免误会。
- [1812z/HyperIsland](https://github.com/1812z/HyperIsland)（MIT）：只读源码取证澎湃超级岛的
  载荷键名与白名单（结论与证据记在 `docs/VENDOR_NOTES.md`），未取代码。
- **WakeUp 课程表**：只提供「导出 WakeUp 兼容 JSON」这一条格式兼容，无代码依赖。

### 对 SleepDown课程表 那份许可的遵守

它不是 OSI 开源许可：允许个人非商业使用，但**对外提供修改版必须源码可见 + 显著署名**。
本仓库满足前两条 —— 完整源码公开、免费且无广告无付费；署名按该许可第 2 条原文：

> 本项目基于 SleepDown课程表 修改；原作者：xiaomanjun233；原项目：
> <https://github.com/xiaomanjun233/SleepDown-Schedule>

主要修改内容（相对 SleepDown 的那部分代码）：适配到本项目的 Compose / Kotlin 版本、给玻璃档位加
「关闭 / 开启」两档收敛（默认关闭大面积面板），并在此之上重写成本应用自己的设计系统。
**本应用与 SleepDown 无关，不由其作者维护，也不代表其官方版本。**

## 许可证

本项目源码以 MIT 许可发布，见 [LICENSE](LICENSE)。第三方代码与资源遵循各自许可证 ——
具体谁贡献了什么、按哪份许可，见上一节「第三方与参考项目」。
