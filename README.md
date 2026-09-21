# BUAA Schedule（北航课程表）

<p align="center">
  <img src="BUAA-Schedule.png" width="320" alt="BUAA Schedule" />
</p>

> 给北航写的课程表 App：登录教务抓课表、课前提醒、上课铃、桌面小组件，
> 在支持的机型上还会把课堂进度送到状态栏的实况里。课表数据只存在你自己的手机上。

[![下载 v0.1.1](https://img.shields.io/badge/下载-v0.1.1-2f6fed)](https://gitee.com/alyssumira/buaa-schedule/releases)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3ddc84)
![MIT](https://img.shields.io/badge/License-MIT-lightgrey)

声明：本项目全部代码均由 AI 生成。

---

## 安装

1. 到 [Releases 页面](https://gitee.com/alyssumira/buaa-schedule/releases) 下载列表里最新的
   `buaa-schedule-<版本号>.apk`（写这段时是 `buaa-schedule-0.1.1.apk`）。**只下这一个**：同一页还有一个
   `-debug.apk`，那是作者装机自测用的调试签名包，和正式包签名不同，装了它之后正式包就无法直接覆盖安装。
2. 安装并打开。以后不用再来这个页面 —— 设置里的「检查更新」会自己发现新版本。
3. 第一次打开会走一段引导。**建议照着点完**，尤其是「提醒可靠性」那一页里的
   「自启动与后台管理」「省电策略：无限制」「后台弹出界面」三项，
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
- 课程可以起**别名**：编辑器里「课程别名（可选，仅改显示）」填一个短名，课表卡片、桌面组件、
  通知与实况都改显示它；教务原名仍在数据里，「课表管理」里会写一句「原名 X」，两个名字都搜得到这门课
- 按校区筛选；「课表管理」按课程归并，可以整门课改色或删除
- 卡片副信息那行显示教师还是教室，由你定（设置 → 外观 → 课程卡副信息）；哪一侧没登记，自动显示另一侧
- 本周的课和上周不一样时，顶部会有一条提示；不是每周都有的课，卡片右上角带一个小三角
- 编辑器里可以填**学分**（不清楚就留空），然后在「设置 → 课表 → 学期统计」看到总学分、
  一周里哪天最忙、每门课占多少，以及哪几天是整天空的
- 时间冲突有处理向导：按组给出同一天最近的空位建议，一键只改冲突的那几周
- 开学日期、总周数、每节课的时间都能自己配；换学期在设置页切换即可，不用重建数据
- 平板或折叠屏展开（≥600dp）时，周视图和日视图会自动变成双栏并排

### 提醒与实况

- 课前提醒、上课铃、下课铃；上课时自动进入勿扰，下课恢复你原来的状态
- 提醒可以选「应用内通知」或「系统日历提醒」，只走一条，避免同一节课响两次
- 在支持实况的系统上（澎湃 OS 的超级岛、ColorOS 的流体云已实测），课前倒计时和
  课中进度会显示在状态栏；其它系统退化成一条常驻通知，功能不减但存在感低很多

### 扫码签到（智学北航）

- 入口在**首页右下角加号 → 扫码签到**。第一次进去会先到登录页（智学北航的统一身份认证），
  登录成功直接落到扫码页，不用再回首页点一次
- 对着课堂上的二维码扫，**识别到就自动提交**，没有确认页。不是你们班的码服务端会拒绝，
  界面把服务端给的原因原样显示出来
- 教室投影反光、摄像头脏了扫不出来时：改用**相册选图**（把二维码截屏发给自己再识别），
  或重新对准二维码再扫
- 想让课前那条提醒的通知上带一个「扫码签到」按钮：设置 → 通知与提醒 → 智学北航签到。
  同一组里能看到登录状态，也可以在这里退出登录
- 扫码签到（相机实时识别与相册识别）只支持 arm64 机型（这是把安装包压小换来的）；
  老机型和模拟器上没有对应的解码库，这一页用不了扫码签到

### 桌面小组件

五种：今日课程、明日课程、本周课表、本周网格 4×2、下一节课。
每个组件**单独**配色（跟课表配色或自定义）、背景、不透明度、圆角、文字颜色，另有 10 套一键样式；
调整时预览实时跟着变。课表一改、以及每天零点，组件都会自己刷新，不占后台轮询。
列表型三种和「下一节课」还能选**每行显示什么**（教师 / 教室 / 节次等，勾选顺序就是显示顺序）；
4×2 网格的表头可以翻上一周 / 下一周，点任意一天会直接进到 App 里那一天的日视图。

### 外观

液态玻璃默认**开启**（设置里只有「开启 / 关闭」两项）：关掉之后顶栏、底栏、页签切换这些小面积
玻璃仍然保留，只有整屏的大块面板退化成实心卡片，更省电也更清晰。深浅色跟随系统。课表背景默认取系统桌面壁纸
（App 内那一层在 Android 14 及以上仍按"系统不让读"提前放弃，这时会自动回退成渐变，或者你在设置里自选一张图；
桌面组件那一层不是这样 —— 它每轮刷新实测一次。实测读到壁纸位图就糊位图；读不到（普通应用读不到位图是常态，
真因见 [`docs/KNOWN_ISSUES.md`](docs/KNOWN_ISSUES.md) §1：挡路的是我们自己不要的那两枚相册权限，不是 API 档次）
就退成一层纯色半透明底，而那一层的底色取自桌面**主色**（`getWallpaperColors`，零权限），不是你预设的那个色。
你自己在设置里选的那张图从来不喂组件）。
设置页与导入页的那些大卡片是**透明磨砂**：底下透什么就是什么，糊到什么程度由设置里的「面板模糊」
滑杆决定（拖到 0 只剩一层薄色），旁边还有一根「卡片透明度」滑杆管所有玻璃表面的浓淡。
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

九成是国产 ROM 的后台清理，不是 App 坏了。引导第三步「提醒可靠性」会逐项自检，任一项没过都会直接给出
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
- 对学校的网络请求只发生在你主动操作的时候：点「导入 / 刷新」抓课表、点「签到」提交这一次
  扫码。签到只发送该次签到要用的班级标识与你的学号，相机画面在本机解码，不上传也不落盘；
  检查更新只访问 Gitee 的公开接口。
- 卸载应用会带走本地数据库。重要课表请保留备份文件或分享口令。
- 每一项权限用来干什么，写在 [`docs/PRIVACY.md`](docs/PRIVACY.md)。

## 已知限制

- 不做 AI 导入、AI 助手、智能推荐；也没有国际化，界面文案是中文。
- 图片和 PDF 导入还没做。
- 桌面小组件的背景是"缩放得到的廉价模糊"，不是真正的高斯模糊。
- 冷启动的 Baseline Profile 优化要在打包前连设备生成一次；没生成过的那版包里没有这项优化。
- 实况的具体样式由各厂商系统决定，同一份通知在澎湃 / ColorOS / 原生上的呈现可能不同。

## 反馈

Issues：<https://gitee.com/alyssumira/buaa-schedule/issues>

报问题前请先翻一眼 [`docs/KNOWN_ISSUES.md`](docs/KNOWN_ISSUES.md)。如果方便，带上机型与系统版本、
引导「提醒可靠性」那一步的自检截图，以及是"没响"还是"响了但没上岛"——这三样基本能一次定位。

---

## 给想改代码的人

工具链：JDK 17 或 21（AGP 8.13 要求 ≥17；Gradle 8.14.3 **不支持 Java 25**，若本机装了新版
Android Studio，需显式指定 JDK）。

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew assembleDebug
```

CI 里的任务一律写成 `:app:` 前缀 —— 裸任务名会被 Gradle 匹配到所有子工程，把 `:benchmark` 一起拉进来。

子模块：`:app`（应用）、`:kyant-backdrop`（vendored 液态玻璃库）、`:benchmark`（宏基准 /
Baseline Profile 生成，已接入 `:app`；采集要连一台 API 28+ 的设备，所以仍不在任何 CI
或本地常规任务里跑，生成命令与步骤见 [`docs/STATUS.md`](docs/STATUS.md)）。

### 测试

```bash
./gradlew testDebugUnitTest          # 单元测试（JVM，不需要设备）
./gradlew connectedDebugAndroidTest  # 仪器测试（模拟器或真机）
```

| 类型 | 用例数 | 文件数 | 覆盖范围 |
| --- | --- | --- | --- |
| 单元测试 | 548 | 75 | 周次解析 / 教学周计算 / 冲突检测 / 导入规划 / 备份 schema / ICS 与文本解析与往返 / 节次分段与连堂判定 / 教务抓取脚本契约 / 日历投影选择 / 提醒排程与明日预告推送集合 / 分享编解码 / Widget 外观与短名与显示字段与翻周 / 课程管理页归并与别名口径 / 课程色板色差与主题槽位全覆盖 / Gitee 发布解析与安装包完整性与附件选择 / 实况卡片文案与倒计时口径 / 学期学分与逐周课负载统计 / 迁移链与导出 schema 对齐 / 逐周密度与日时间轴分段口径 / 学分在教务解析、备份与分享口令三条链路上的往返 / 签到二维码三形态解析 / JWT 过期判定 / 签到响应的字段大小写与解壳形态 |
| 仪器测试 | 65 | 14 | Room 迁移 / Repository 提醒写入与事务 / Widget 刷新新鲜度与渲染契约与外观存档与数据缓存 / WebView 会话保留与 evaluateJavascript 契约 / 教务 cookie 与签到 token 落盘 / 课堂铃生命周期 / 壁纸解码 / 日历同步部分失败 |

仪器测试跑在 API 29 + API 34 模拟器上（CI 同配置）：Room 迁移与 WebView 相关用例需要真实
Framework 环境，API 34 一档覆盖的是 Android 14 那批行为收紧里我们自己写得动断言的那些
（精确闹钟默认拒绝等）。上表"壁纸解码"那一格钉的是**自选图片**的解码路径，不是系统桌面壁纸
的读取 —— 后者在仪器测试里没有断言。而"读不到桌面壁纸"这件事的真因也在仪器测试之外查清的：
不是"Android 14 起平台禁了"，是本应用从未声明 `READ_EXTERNAL_STORAGE`（其后还有一道 app-op），
零权限只读得到壁纸的**颜色**（`getWallpaperColors`）—— 口径与取证过程见
[`docs/KNOWN_ISSUES.md`](docs/KNOWN_ISSUES.md) §1。

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
| [`docs/BUAA_API.md`](docs/BUAA_API.md) | 北航教务与智学北航接口参考（改导入 / 签到链路时看） |
| [`docs/BUAA_SPOC_SIGNIN_PLAN.md`](docs/BUAA_SPOC_SIGNIN_PLAN.md) | 扫码签到的取证、方案与**落地偏差**（§11，改这块先看它） |
| [`docs/THIRD_PARTY_NOTICES.md`](docs/THIRD_PARTY_NOTICES.md) | 第三方代码逐条许可证、取用位置与署名全文 |

## 第三方与致谢

本项目不是从零堆出来的，液态玻璃那一层尤其站在别人的成果上。这里只列名单 ——
用到哪里、按什么许可证、以及那些文件头的完整解释，都写在
[`docs/THIRD_PARTY_NOTICES.md`](docs/THIRD_PARTY_NOTICES.md) 里。

**随安装包分发**

- [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（tag 2.0.0）· Apache-2.0 —— 液态玻璃的全部底子
- [Kyant0/Shapes](https://github.com/Kyant0/Shapes) · Apache-2.0 —— G2 连续曲率圆角，以源码内嵌
- [SleepDown课程表](https://github.com/xiaomanjun233/SleepDown-Schedule) —— 上述玻璃库副本的取用位置
- [Google ML Kit barcode-scanning](https://developers.google.com/ml-kit/vision/barcode-scanning) · Apache-2.0 —— 二维码解码，模型打进安装包、不依赖 GMS
- AndroidX（Compose / Room / CameraX / WorkManager 等）、Kotlin 生态、JUnit 与测试族 —— 常规运行与测试库

**参考过、没有取代码**

- [拾光 shiguang_warehouse](https://github.com/xingheyuzhuan/shiguang_warehouse)（MIT）—— 24 小时时间轴与组件"共用水源"的目标形态
- [lingion/sleepy](https://github.com/lingion/sleepy)（GPL-3.0）—— 只借了「一键外观预设」的思路，无重合代码
- [1812z/HyperIsland](https://github.com/1812z/HyperIsland)（MIT）—— 澎湃超级岛载荷键名与白名单的取证来源
- WakeUp 课程表 —— 只提供导出格式兼容

玻璃文件里那批 `Modified for SleepDown` 头只说明**从哪儿取的**：库本身属于
Kyant0/AndroidLiquidGlass（Apache-2.0），SleepDown 和我们一样只是它的使用者，本应用没有一行
代码来自它的自研业务。谢谢 Kyant0 写出这套渲染，也谢谢 SleepDown 作者 —— 超级岛"为什么要
这样排布"的答案，大半是从其真机行为里抠出来的（证据在 `docs/VENDOR_NOTES.md`）。
本应用与 SleepDown 无关，不由其作者维护，也不代表其官方版本。

## 许可证

本项目源码以 MIT 许可发布，见 [LICENSE](LICENSE)。第三方代码与资源遵循各自许可证 ——
具体谁贡献了什么、按哪份许可，见「第三方与致谢」及其指向的
[`docs/THIRD_PARTY_NOTICES.md`](docs/THIRD_PARTY_NOTICES.md)。
