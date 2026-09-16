# 厂商/ROM 适配笔记（证据分级）

> 定位：Sleepy `docs/widget-vendor-specs/`（64 篇）的低成本单文件版。
> 所有厂商差异**沉淀为文档，而不是 if-else**——代码只做能力探针与降级链。
> 每条结论标注证据等级：**A** = 官方文档 / 本机实测复现；**B** = 可信二手
> （dontkillmyapp、厂商开发者平台、其它 App 的实测记录）；**C** = 传闻，待验证。

## 通用（跨厂商）

- **杀后台严重度**（dontkillmyapp 口径，B）：MIUI/EMUI/ColorOS 属第一梯队激进；
  三大变量 = ①熄屏后的休眠桶 ②「自启动」权限默认关 ③电池优化白名单未加。
  应对：前台可见时刷新最可靠；后台依赖闹钟 + 开机自愈 + 状态驱动兜底三件套。
- **厂商无版本化承诺**（A，Sleepy `xiaomi/gaps.md`）：没有任何 WorkManager /
  AlarmManager 的行为保证跨 ROM 版本稳定，所有调度必须自带降级链。
- **渠道重要性创建即固化**（A，本机实测）：改 importance 必须换渠道 id 并删旧渠道。
- **静默 + 低优先级组合最容易被实况/焦点提升忽略**（B，SleepDown 真机证据；
  Sleepy 反例用 setSilent+promoted 并用，存在争议）：本项目的「课程进行中」
  渠道为 DEFAULT 且不 setSilent。
- **实况通知不用系统 chronometer**（B，依据是 SleepDown `LiveUpdateForegroundService.kt:95`
  原文 + 我们带 chronometer 时用户看不到岛；**没有**单独只改这一条复现过因果）：
  `setWhen()` + `setUsesChronometer(true)` 会让系统自绘的时钟**顶掉岛上那一格 promoted chip**。
  本项目倒计时一律 `setShowWhen(false)` + 应用侧分钟对齐重发。
- **岛上那一格小字走真正的 setter，extras 只作兜底**（A，本机真机 2026-09-16 已验证：
  setter 这一版岛上看得到我们写的短句）：`ReminderNotifications.applyShortCriticalText` ——
  androidx 有公开 `setShortCriticalText(String)`，框架 builder 按 SleepDown 的做法
  CharSequence→String 双签名反射（本机探针给出「框架小字接口=String」）。
  只写 extras 那一版是否也能上岛，**未测**，别写成结论。
- **判断"上没上岛"只认独有文案，不认图标**（A，本轮踩坑）：这台机器上
  `com.qoder.mobile.cn` 常驻同一个岛、文案正是「进行中」，我曾三次把它当成我们的。
  自检文案因此必须是只有我们会写的词（「自检中」）。
- **实况通知在 Android 17 起需要 `POST_PROMOTED_NOTIFICATIONS`**（A，`javap` 对比
  android-36 / android-37 的 `android.jar` + 官方《创建实时更新通知》）：
  `android.Manifest.permission.POST_PROMOTED_NOTIFICATIONS` **只存在于 API 37**
  （36 没有该常量，但两版都有 `canPostPromotedNotifications()`），官方文档明确要求
  **在清单里声明**；同时 API 37 的框架 `Notification.Builder` 才补上
  `setRequestPromotedOngoing(boolean)`，并给出 `EXTRA_REQUEST_PROMOTED_ONGOING =
  "android.requestPromotedOngoing"` —— 与本项目直接写 extras 的键名一致（已核实等价）。
  本应用 compileSdk 36，因此清单写权限字面量、代码走 extras，两者都不依赖新 SDK。
- **支持「实时活动」的系统口径**（B，用户 2026-09-16 给出 + 各家公开资料）：原生
  Android 16+、Xiaomi HyperOS 3.0.300 以上、ColorOS 16 会把符合规范的实况通知渲染成
  岛/胶囊；判定仍以本机 `canPostPromotedNotifications()` 三态 +
  「设置 → 提醒可靠性 → 实况通道自检」为准，不要按 ROM 名字猜。

## 小米 / 澎湃 HyperOS

- **超级岛有两条上岛路径**（A，dev.mi.com + SleepDown 真机）：
  ① Android 16/17 标准 promoted ongoing 通知自动渲染成岛样式，**无需提报、
  无需上架**；② 定制岛效果才走《模板库》提报（注册开发者→上架→预审→联调→灰度）。
  本项目走路径 ①，实现清单见 `docs/KNOWN_ISSUES.md` 2a。
- **路径 ② 对普通第三方应用是关的**（B，HyperIsland 源码可核对：类名/方法名/开关都写死在
  其 Xposed hook 里）：自定义岛内容（`miui.focus.param`）被 SystemUI **进程内**的三重白名单
  卡住 —— `miui.systemui.notification.NotificationSettingsManager.canShowFocus` /
  `canCustomFocus` 管"这个 business 能不能显示"，
  `miui.systemui.notification.focus.SignatureChecker.checkSignatures` 管"发通知的签名登记过没有"。
  HyperIsland 的"能上岛"来自两件事：以模块身份**在 SystemUI 进程里** `notify()`（签名检查看到的
  调用方就是 SystemUI 自己），外加自己的 `pref_unlock_all_focus` / `pref_unlock_focus_auth`
  两个开关把上述方法 hook 成恒 true，其引导页第 3 步指向 HyperCeiler 的同名功能。
  **应对**：本项目仍发标准实况；岛模板做成小米专属、默认关闭的实验项，不可见时归因于白名单。
- **小米官方口径印证上述结论**（A，dev.mi.com 澎湃OS 开发者平台《常见Q&A》2026-09-16 抓取）：
  焦点通知「OS1 需要单独适配……当前阶段不建议接入了」、OS2 起支持，且
  **「开发者发起申请邮件至 mipush-permission@xiaomi.com」** —— 即自定义岛内容要走人工提报，
  没有可自助开启的旁路。标准 promoted ongoing 不受此限（HyperOS 3.0.300+ 会渲染成岛/胶囊）。
- **焦点通知载荷语义**（B，同上，`ScreenRecorderHook.buildFocusBundle`）：
  `timerInfo` 是**计时器**不是倒计时 —— `timerWhen` = 起始绝对时刻（样例算作 `now - duration`），
  `timerType` 1 = 走秒、2 = 停住（只在录屏暂停时给 2，**没有倒计时取值**）；
  `sameWidthDigitInfo` 只有 `timerInfo` 一个字段，文字归通知本身与 `android.shortCriticalText`；
  `highlightColor` 是 `#RRGGBB` **字符串**；动作必须摊平成顶层 `miui.focus.action_1 / _2`。
- **版本对应**（A）：HyperOS 2 = Android 15、3 = Android 16、**4 = Android 17**。
- **实况三态**：API 36 `canPostPromotedNotifications()` 区分「允许/用户关闭/
  系统不支持」，已接入设置页「提醒可靠性 → 实况通知」。
- **自启动管理页**（B，组件名随版本漂移，逐个尝试失败即兜底应用详情）：
  `com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity`。
- **杀后台**（B）：省电策略可吞 AlarmManager 闹钟 → 上课铃丢失。已对冲：
  上课铃用 `setAlarmClock`（用户闹钟档，豁免最彻底）+ 进前台状态驱动补起实况
  （`LiveClassResyncer`）+ 短唤醒锁包重排（`WakeLocks`）。

## OPPO / ColorOS、华为 / EMUI

- **自启动/启动管理页**（B）：ColorOS
  `com.coloros.safecenter/...permission.startup.StartupAppListActivity`、
  华为 `com.huawei.systemmanager/...startupmgr.ui.StartupNormalAppListActivity`，
  均已在 `ReminderGuidance.openAutoStartSettings` 里逐个尝试。
- **ColorOS 15 及以前：流体云是独立系统服务**（B，Sleepy `oppo-coloros-fluid-cloud.md`）：
  `NotificationCompat + IMPORTANCE_HIGH` 不等于流体云；接 SeedlingSDK 需厂商审核。
  本项目不做厂商 SDK，标准 promoted ongoing 在其上以普通实况形式呈现即可。
- **ColorOS 16 已改为开放生态**（B，2025-10 起多家报道）：流体云直接兼容谷歌
  Android 16「实时活动」API 规范，遵循该规范的第三方应用**不必接 SeedlingSDK、不必提报**
  即可上岛/上胶囊。所以 ColorOS 16 与原生 A16/A17 走同一条路：
  `ProgressStyle` + ongoing + 非静默渠道 + `android.requestPromotedOngoing`，
  以及 Android 17 起的 `POST_PROMOTED_NOTIFICATIONS`（见「通用」一节）。
  SeedlingSDK 只在需要**定制**流体云样式时才谈得上。
- **本项目已落到的 ColorOS 适配（2026-09-16，B/C）**：结论是**没有一行下发形状需要为它改变**
  —— 上面那条路与我们为澎湃修出来的形状是同一套，所以这次只做三件事：
  1. `IslandDiagnostics.liveIslandSurface()`（纯函数 `liveIslandSurfaceOf` 可单测，
     `LiveIslandSurfaceTest` 4 例）按品牌 + `ro.build.version.opporom` 把机型归到
     超级岛 / 流体云 / 原生实况，设置页与引导页那一行的标题与文案随之改口
     （`promotedRowTitle()`）。**在 ColorOS 上不再拿"岛"这个词许诺澎湃的行为。**
  2. `ReminderGuidance.openPromotedNotificationSettings()`：跳 AOSP 的
     `Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS`（`javap` 核实 android-36 有该常量、
     SleepDown 同款），**先 `resolveActivity` 再跳、解不开退回普通通知设置页** ——
     厂商不保证实现这一页（ColorOS 的「流体云」开关另有其页）。
  3. ColorOS 分支的文案里写死"未实测"：引导页第 6 步与「实况通道自检」都明确
     本仓库没有 ColorOS 真机、那条自检在流体云上就是唯一判据。版本串 `ro.build.version.opporom`
     只反射读一次（`//noinspection PrivateApi`，失败退 null），**只用于把版本号显示给用户，
     不参与任何行为判断** —— 我们不想用一个读不到的属性去决定要不要发实况。
  **证据边界（C）**：以上没有一条在 ColorOS 真机上跑过；能给的只有"形状与已上岛的
  澎湃通知完全一致 + 自检通道"。有 OPPO/一加/realme 机器时按「只认独有文案『自检中』」判读。

## 本项目适配设施清单（代码锚点）

| 设施 | 位置 |
| --- | --- |
| 实况通知（promoted ongoing + extras 双写 + tracker/Segment） | **唯一生产者** `ReminderNotifications.startLiveWindow(phase)`（课前 `ReminderReceiver` / 课中 `ClassProgressReceiver` / 兜底 `LiveClassResyncer` 三条路径都走它）→ 同 id 上由 `CourseFluidService` 接管 |
| 实况正文两行文案 + 倒计时口径 | `ReminderNotifications.liveBody` / `minutesLeft`（与岛上小字 `chipCountdownLabel` 同源） |
| 课前/课中两段的收尾分工（只有课中收尾才恢复勿扰 + 续排下一节；课前交给上课铃广播，避免把刚排的下课铃 cancel 掉重排） | `CourseFluidService.finishLiveAndReschedule` |
| 实况三态探针 + 诊断行 | `ReminderGuidance.promotedOngoingState` / SettingsScreen「实况通知」 |
| 实况能力探针（三态 + 焦点协议档位） | `IslandDiagnostics` / `EnvironmentCheck`（v0.1.0 起不再有自检实况） |
| 机型 → 实况产品名（超级岛 / 流体云 / 实况通知），只影响文案 | `IslandDiagnostics.liveIslandSurface` / `promotedRowTitle` |
| 系统「通知提升」页直达（解不开退普通通知设置） | `ReminderGuidance.openPromotedNotificationSettings` |
| 自定义岛内容模板（小米专属、默认关闭、本版本不暴露开关） | `IslandFocusTemplate.attach` ← `CourseFluidService` / `postClassOngoing` |
| 实况/岛点按落到本节课（唯一 extra 键源） | `ReminderNotifications.courseLaunchPendingIntent` ← `MainActivity.EXTRA_COURSE_ID` |
| 状态驱动兜底（课堂窗口内补起实况） | `LiveClassResyncer` ← MainActivity.onStart |
| 上课铃 setAlarmClock + 三级降级 | `ClassProgressScheduler.scheduleClassStartBell` |
| 短唤醒锁包闹钟重排 | `WakeLocks` ← ReminderReceiver |
| 开机/升级/权限变化/时间变化自愈 | `BootReceiver` / `WidgetRefreshReceiver` |
| 厂商自启动直达页 + 电池白名单 | `ReminderGuidance.openAutoStartSettings` / `requestIgnoreBatteryOptimizations` |
| 引导页「厂商后台放行」三行分别跳转（自启动 / 电池 / 应用权限页） | `OnboardingScreen.VendorStep` ← `openVendorPermissionPage` |
| 上课自动勿扰（含下课恢复） | `ClassProgressDnd` |

## 真机观察记录（追加区，按日期倒序）

- 2026-09-16（17:08 起，Redmi 22127RK46C / HyperOS 4 / Android 17，**修正下一条**）：
  - **下一条的"已上岛"结论当时不成立**（A）：用户复看反馈「只在下拉通知界面出现了，没上岛」。
    形状上只剩一处与 SleepDown 不同：我们发的是 `setWhen(endMillis) + setUsesChronometer(true) +
    setChronometerCountDown(true)`，而 SleepDown `LiveUpdateForegroundService.kt:95` 原文
    *"SystemUI's chronometer is intentionally not used because it replaces the promoted chip"*。
    **已修**：`postClassOngoing`、`CourseFluidService` 两个分支、`postSelfTest` 统一改为
    `setShowWhen(false)`，倒计时改由应用侧分钟对齐重发驱动。
  - **上岛判据（唯一站得住的一条）：岛上出现只有我们才会写的文案**（A，17:39，用户肉眼确认
    「自检中」）。同窗口两条硬旁证：
    1. `dumpsys notification --noredact` 里我们那条 extras 全对：
       `showWhen=false`、`showChronometer=false`、`requestPromotedOngoing=true`、
       `shortCriticalText=自检中`、`template=ProgressStyle`；
    2. SystemUI `DynamicIslandService: make the transition to ElementT, for com.buaa.schedule`
       → 12 秒后 `FocusPlugin: onNotificationRemoved … removeIslandDataByKey` 正常自撤。
  - **踩坑：辨认胶囊归属只能靠独有文案，不能靠图标**（A）。这台机器上 `com.qoder.mobile.cn`
    常驻同一个岛，它的胶囊文案正是「进行中」、图标是圆角叶子状，很容易当成我们的。
    17:08 / 17:20 / 17:57 三张截屏我都把它读成了"我们的岛"，据此写下的两条结论**已作废**：
    ① "chronometer 修完就肉眼上岛"（那张是 Qoder 的）；
    ② "岛上那格是 ROM 通用文案「进行中」，所以小字只认 setter"（那本来就不是我们的胶囊，
    而"去掉自检标题里的「进行中」字样后岛上的字没变"这个单变量实验读的也是 Qoder 的字）。
    **口径**：自检文案要用世界上只有我们才会写的词（「自检中」合格，「进行中」不合格），
    判读时只认这句话；同一时刻可能有别的 App 在岛上。
  - **归因不能说过头**（A）：从"用户看不到岛"到"用户看到我们的岛"之间改了**两件事**
    （去 chronometer、补 `setShortCriticalText`），中间那次单独只改第一件的复看被 Qoder 的
    胶囊污染了。所以：两处都对齐了 SleepDown、终态真机确认；但"只缺 chronometer 那一条时
    到底上不上岛"我们**没有**独立证据，别在文档或提交信息里写成已证。
- **`promotable` 由屏幕而非日志读出**（A）：这台 ROM **整条吞掉第三方应用的 logcat**
  （`logcat --pid=<我们进程>` 0 行；包 flags 带 DEBUGGABLE；logd 无 blacklist），
  所以 `ReminderNotifications.logPromotionShape()` 的结论同时写进
  `IslandDiagnostics.lastSelfTestVerdict`，在「实况通道自检」行直接显示
  「平台判定：这条通知有资格被提升为实况」。
  **以后本机实况相关问题不要再去找日志开关，直接读屏幕。**
- **重发节拍取「进度步」与「chip 分钟翻转」的较早者**（A）：`nextCourseFluidTickMs` =
  min(进度 +1% 时刻, chip 分钟翻转时刻 +150ms)，下限 1s（`CourseFluidTickTest` 5 例）。
  刻意**不**照抄 SleepDown 的墙钟整分钟对齐：那相对 `endMillis` 最多错位一分钟，
  会把"还剩 1 分钟"晚显示整整一分钟。
- **小字这一格：补上 setter 之后才第一次确认我们的胶囊上岛**（A，17:39）：
  `ReminderNotifications.applyShortCriticalText` —— androidx 走公开 setter，框架 builder
  按 SleepDown 的做法做 `CharSequence`→`String` 双签名反射，extras 仍双写作兜底。
  本机屏幕上读到的探针结论：**「框架小字接口=String」**，即真课表那条反射分支会落到
  `String` 签名上（`CharSequence` 那档这台 ROM 没有）。
  **注意**：只写 extras 的那版从没被确认上没上岛（见上面的踩坑），"extras 不够"属于**未证**；
  已证的是"setter 这一版上岛、且小字就是我们写的那句"。
- **真课表那条分支在真机上起不动**（A）：`CourseFluidService` 是 `exported="false"` 的
  前台服务，`adb shell am start-foreground-service` 直接被挡
  （`Error: Requires permission not exported from uid 10128`），而 instrument 路径会清用户的课表库，
  不能拿来验证。因此改为在自检行里附带一个**类级签名探针**
  （`frameworkChipSignature()`，只 `getMethod` 不建实例），屏幕上的
  「框架小字接口=CharSequence|String|无」即证明那条反射调用落得到方法。

- 2026-09-16（Redmi 22127RK46C / HyperOS 4 / Android 17，`adb` 只读核对）：
  - **实况确实上了澎湃超级岛，权限恢复后三方印证闭环**（**此条结论已被上方条目推翻**——
    当时只上了下拉通知栏；`A`，设置页「发一条」自检 3 次）：
    1. SystemUI(pid 26910) `FocusPlugin: onAuthSuccess 0|com.buaa.schedule|20260009|null|10128`
       → `onInflateSuccess`，**全程没有出现** `canShowFocus` / `canCustomFocus` /
       `checkSignatures` 拒绝日志——说明标准实况通道不需要白名单；
    2. `IslandTemplateFactory: RealBigIsland CREATE NEW path for key=0|com.buaa.schedule|20260009`
       + `IslandModuleViewHolderAdapter: createModuleViewHolder moduleImageText_1 / moduleImageText_2 /
       modulePicSmallIsland` + `BaseIslandModuleViewHolder: setTitleHighlightColor`
       —— 大岛与小岛的视图组件都被真实创建，`TimerTextEffectView#island_title` 拿到了 348px 宽度；
       **判据边界（后补）**：这组日志只证明 SystemUI **inflate/测量**了岛的视图，
       **不能**据此判定"用户看得见岛"——当时正是把这条当成了上岛证据。
    3. `DynamicIslandService( 3050): Receive dynamic island info add:DynamicIslandData{mPackageName='com.buaa.schedule'}`
       → 系统级（非 SystemUI 内部）也登记了这条岛数据；**同样只是"登记"，不是"渲染"**；
    4. ~~截屏可见状态栏中央黑色胶囊岛（应用图标 + 文案）~~
       **误读（后补）**：当时画面里是**下拉通知栏**内的实况样式，状态栏中央没有胶囊。
  - **岛上的小字来自 `android.shortCriticalText`**（A）：`dumpsys notification --noredact`
    里自检那条的 extras 是 `android.shortCriticalText=还有 1 分钟下课`，而它只有 12 秒寿命——
    `countdownLabel()` 按分钟向上取整，12 秒被写成「还有 1 分钟」。**已修**：自检改用字面量，
    不再复用课程口径的 `countdownLabel`；真课表仍是 40–90 分钟，取整无误。
    **本轮再修（17:08）**：chip 必须保持**纯文本、无 span、尽量短**（SleepDown 的取证是
    长文案会让倒计时在岛上渲染失败），故统一为 `chipCountdownLabel()` 的「N分钟」，
    自检固定「自检中」。
  - **岛上出现约 1–2 秒一次的 remove→add 抖动**（O，非缺陷）：同窗口内同期在岛的
    `com.qoder.mobile.cn` 的 key 也有 133 次同类事件，而 `postSelfTest` 全仓只有一个
    点击入口（`SettingsScreen.kt:1297`）。判定为 HyperOS 自己按秒刷 `TimerTextEffectView`
    时重建视图，不是我们重复 `notify()`；若将来发现真课表也跟着闪，再回头核这条。
  - **Android 17 实况权限门槛在真机复现**（A）：`POST_PROMOTED_NOTIFICATIONS` 只在
    **重新声明并 `adb install -r` 之后**才出现在授权列表里
    （`dumpsys package com.buaa.schedule | grep -i promoted` → `granted=true`）。
    即**安装期自动授予**（normal 级），不需要、也不能用运行时申请框去拿；
    而 R5 F-C10 删掉声明的那版构建里这条权限**根本不存在于包内**，
    这就是同一台机器"通知正常显示、一个也不上岛"的直接原因。
    商店审核顾虑不成立：官方文档要求声明，未声明才是功能失效。
  - **系统版本读法**（A）：本机 `ro.build.version.sdk = 37`、`ro.build.version.release = 17`、
    `ro.build.version.incremental = OS4.0.0.24.XMKCNXM`、`ro.mi.os.version.name = OS4.0`、
    `ro.mi.os.version.code = 4`；`Settings.System` 的
    `notification_focus_protocol = 3`（普通应用可读，本项目探针已用）。
  - **`Build.DISPLAY` 不含 "HyperOS" / "MIUI" 字样**（A，本机 `CP2A.260605.016`）：
    `IslandDiagnostics.isXiaomiRom()` 的 DISPLAY 兜底分支在这代 ROM 上是**空转**的，
    判定必须继续依赖 `MANUFACTURER = Xiaomi` / `BRAND = Redmi`；
    若将来要按主版本号门控，可靠来源是 `ro.mi.os.version.name`（形如 `OS4.0`，
    HyperIsland 的 `HyperOsVersionUtil` 正则 `os\s*[-_]?\s*([34])` 亦如此）。

- 2026-09-16（澎湃 OS 4 / Android 17，用户真机，`connectedDebugAndroidTest` 55/55 通过）：
  - **`alarmManager.cancel()` 不等于 PendingIntent 消失**（A）：随后用
    `PendingIntent.getBroadcast(FLAG_NO_CREATE)` 仍取得到非空，于是"上/下课铃到底还挂不挂着"
    在真机上查不出来（`ClassBellLifecycleTest` 三例全红暴露）。`ClassProgressScheduler.cancel`
    现在补 `PendingIntent.cancel()` 把记录本身摘掉，重排时再 `getBroadcast` 造新的。
  - **HyperOS 拦下 instrumentation 的 Activity 拉起**（A）：`ActivityScenario.launch` 没有超时，
    Activity 不被放行时它一直等 RESUMED，把整轮仪器测试挂死（实测 34 分钟 0 例完成，
    且 `am instrument -w` 也不会自行退出）。**放行开关 = 应用权限「后台弹出界面」**
    （应用管理 → BUAA Schedule → 权限 → 其他权限）；开发者选项「USB调试（安全设置）」
    单独开**不够**（实测仍拦）。授权后 `BuaaSessionRetainKeepsJsAliveTest` 3 例通过，
    真机 55/55 全绿。
  - **AGP 8.13 没有 connected 测试的超时 DSL**（A，扫过 `com.android.tools.build` 全部 jar，
    `ExecutionConfig` 零命中）：防挂死只能落在测试类里 —— `BuaaSessionRetainKeepsJsAliveTest`
    加 JUnit `Timeout` 规则（30s/例 + `withLookingForStuckThread`），在不放行拉起 Activity 的
    ROM 上报超时并打印卡住的线程栈，而不是让整轮 `connectedDebugAndroidTest` 无限等待。
  - **真机与模拟器全新安装的差异：库里有用户真实数据**（A）：`CourseListFactory` 按
    `appWidgetId` 的绑定取快照 key，测试传 `INVALID_APPWIDGET_ID` 会落到 `"current"` 槽位，
    读到的是用户自己的课表而非 seed 内容。组件类仪器测试必须自配 widget id +
    `WidgetBindingStore` 绑到测试学期，否则同一条用例在开发机和真机上结论不同。

- 2026-09-15（澎湃 OS 4 / Android 17，用户真机）：第三方标准 promoted ongoing
  通知未上岛 → 逐项对齐 SleepDown（去 setSilent、补 tracker/Segment/shortCriticalText
  extras）后待复测；`canPostPromotedNotifications` 状态待在设置页确认。
