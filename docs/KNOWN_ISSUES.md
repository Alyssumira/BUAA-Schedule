# 已知问题与平台限制

记录**当前已知、尚未修复或有平台约束**的问题。修掉的问题会从这里移除并进 README 的
已完成清单；新问题先记到这里，而不是只写在聊天记录里。

## 平台限制（App 无法绕过，已做引导或回退）

### 1. Android 14+ 读不到系统壁纸

- **现象**：Android 14（API 34）起，`WallpaperManager.getDrawable()` 需要
  `MANAGE_EXTERNAL_STORAGE` 或签名级 `READ_WALLPAPER_INTERNAL`，普通应用一律返回 null。
  **澎湃 OS（HyperOS）基于 Android 14/15，全部在此范围内** ——
  「使用桌面壁纸」开关在该系统上必然无效，这是平台硬限制，不是 bug。
- **现状**：`SceneBackground.decodeSystemWallpaper()` 在 API 34+ **显式提前返回 null**，
  背景回退北航蓝渐变或用户自选图片；桌面组件壁纸模糊同理回退纯色圆角底。
- **引导**：设置页在「Android 14+ 且开启『使用桌面壁纸』且未选自定义图片」时会显示
  明确提示，建议改用「选择壁纸图片」手动指定。
- **替代方案**：手动选图（「选择壁纸图片」）不受该限制，选一次持久生效。
  若选图后背景仍不变，排查方向：① 部分选择器（"最近"列表）返回的 URI 不支持
  持久授权，重启后失效（logcat 过滤 `SettingsScreen` 可见告警）；
  ② 解码失败（图片损坏/URI 失效）会记 `SceneBackground` 告警日志。
- **结论**：不是 bug，是平台收紧；不要尝试申请 `MANAGE_EXTERNAL_STORAGE` 来恢复该功能。

### 2. 精确闹钟可能被收回（Android 12+）

- `SCHEDULE_EXACT_ALARM` 在 API 31-32 默认授予，**Android 14（API 34）起默认拒绝**；
  未授权时提醒退化为非精确路径（可能迟到几分钟），桌面组件的零点跨天刷新同样退化。
- 现状：闹钟调度失败时退化为非精确路径并记录 warning（`WidgetRefreshReceiver` 对
  `SecurityException` 有独立分支）；「设置 → 提醒可靠性」与组件配置页都提供
  权限状态诊断和一键跳转 `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` 授权页。

### 2a. 通知不上澎湃「超级岛」（HyperIsland）

- **版本对应**：HyperOS 2 = Android 15、HyperOS 3 = Android 16、
  **HyperOS 4 = Android 17**（2026-08 起推送）。
- **两条上岛路径，此前只说对了一条**：小米官方的《超级岛模板库》提报接入
  （注册开发者→上架→签名指纹→场景预审→联调白名单→灰度 7~15 天）只覆盖
  **定制岛效果**；而 **Android 16/17 标准的 promoted ongoing 通知，HyperOS 会
  自动渲染成超级岛/焦点样式，不需要提报、不需要上架小米商店**。
- **竞品取证（2026-09-15，SleepDown 源码）**：SleepDown 未上架小米商店、未接
  任何小米 SDK / MiPush，compileSdk 37，其「课程实况」通知全部走标准 AOSP 路径，
  关键写法有五点，缺一项都可能不被提升：
  ① 渠道 IMPORTANCE_DEFAULT，**通知从不 `setSilent`**（防重复响声只靠
  `setOnlyAlertOnce`）——静默通知会被实况提升逻辑忽略；
  ② `CATEGORY_PROGRESS` + ongoing；
  ③ `ProgressStyle` 必须带 **`setProgressTrackerIcon`（小圆点）+ `Segment(100)`**，
  否则岛上的进度「头」没有东西可渲染；
  ④ `setShortCriticalText`（岛/胶囊紧凑文案）签名跨版本不稳（CharSequence / String 两版都有 ROM 在改），
  SleepDown 的写法是**反射调真正的 setter（CharSequence 优先、String 兜底）+ 再双写一份 extras**；
  ⑤ extras `android.requestPromotedOngoing=true` 双保险。
- **本项目已对齐（2026-09-15）**：`CourseFluidService` / `ReminderNotifications`
  移除 `setSilent`（课程进行中首帧会响一声，属预期）、ProgressStyle 补
  tracker 图标（`ic_progress_dot`）与 Segment、新增
  `applyPromotedOngoingExtras`（requestPromotedOngoing + shortCriticalText
  「还有 N 分钟下课」extras）。
- **当时对齐后仍没上岛，本项目改了两处（2026-09-16）**：
  ① 上一版此处写的是「chronometer 是更强的实况信号，两者不冲突」—— **错**，SleepDown
  `LiveUpdateForegroundService.kt:95` 原文是
  *"SystemUI's chronometer is intentionally not used because it replaces the promoted chip"*
  （系统自绘时钟会占掉岛上那一格 promoted chip），故三条实况路径统一改 `setShowWhen(false)`，
  倒计时改由应用侧分钟对齐重发驱动（`nextCourseFluidTickMs`），chip 文案改成纯文本短句
  （`chipCountdownLabel` → 「N分钟」）；
  ② 小字补上**真正的 setter**（见下条）。
  **归因边界**：这两处之间没有单独只改①就复看过（那次看到的胶囊其实是别的 App 的，见下条），
  所以①的因果强度是 SleepDown 源码 + 我们带 chronometer 时用户看不到岛，属**对齐**而非**已证**。
- **小字这一格：extras 里那一份不算数，要调 setter（2026-09-16 真机已验证）**。此前我们只往
  `notification.extras` 写 `android.shortCriticalText`，从没调用过 `setShortCriticalText`；
  补上 `applyShortCriticalText`（androidx 走公开 setter，框架 builder 按 SleepDown 做
  `CharSequence`→`String` 双签名反射，本机探针读到「框架小字接口=String」）之后，
  用户肉眼确认岛上那格就是我们写的「自检中」。**只写 extras 那一版到底上没上岛，仍未测**。
- **实况形状与 SleepDown 已逐项对齐（2026-09-16）**：补上最后一项漏项
  `setColor(Notification.COLOR_DEFAULT)`（三条路径：`postClassOngoing`、
  `CourseFluidService` 框架与兼容分支、`postSelfTest`；`javap` 核实 android-36/37 都有该常量）。
  属**对齐**，取色差异没做单变量复验，不要写成"缺它就不上岛"。
  **刻意没抄** SleepDown 的 `BigTextStyle` 基础样式：它在 `NotificationScheduler.kt:719` 先设
  BigText、再在 `status.progressPercent != null` 时 `setStyle(ProgressStyle)` **覆盖**掉，
  而我们三条实况路径永远带进度，抄过来是一行死代码。
- **ColorOS 流体云（2026-09-16）**：不需要为它改一行下发形状 —— 按公开资料 ColorOS 16 起
  直接接安卓实时活动 API，与我们为澎湃修出来的形状同一条路。因此这次只改**说辞与入口**：
  `IslandDiagnostics.liveIslandSurface()` 决定那一行叫超级岛还是流体云，
  `ReminderGuidance.openPromotedNotificationSettings()` 跳 AOSP 提升设置页
  （`resolveActivity` 兜底）。**无 ColorOS 真机，这一侧全部标记为未实测**，
  细节与证据边界见 `VENDOR_NOTES.md`「OPPO / ColorOS」一节。
- **判据教训：岛上是别的 App 的胶囊，别把它当我们的**（2026-09-16 踩坑）。这台机器上
  `com.qoder.mobile.cn` 常驻同一个岛，文案正是「进行中」、图标是圆角叶子状；我三次把它读成
  "我们的岛"，还据此写了两条错误结论（"修完 chronometer 就肉眼上岛"、"岛上那格是 ROM 通用文案"）。
  **口径**：自检文案必须是只有我们才会写的词（「自检中」），判读只认这句话，不认图标。
- **根因已定位（2026-09-16）：清单缺 `POST_PROMOTED_NOTIFICATIONS`**。R5 F-C10 曾以
  "`canPostPromotedNotifications()` 是只读查询、不需要权限"为由把这条声明删掉，
  商店审核是当时的动机。前半句没错、结论错：**查询**不需要权限，**发**需要 ——
  `javap` 对比本机 `android-36` / `android-37` 的 `android.jar`，
  `android.Manifest.permission.POST_PROMOTED_NOTIFICATIONS` 只在 API 37 存在
  （`canPostPromotedNotifications()` 两版都有），官方《创建实时更新通知》亦明确要求清单声明；
  同版还补上了框架 `Notification.Builder.setRequestPromotedOngoing()` 与
  `EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"`（与本项目自写 extras 同键）。
  这正好解释 2026-09-15 那台 **HyperOS 4 = Android 17** 的现象：通知正常显示、一个也不上岛、
  系统不给任何回调。**已修**：清单恢复声明（compileSdk 仍为 36，故写权限字面量），
  `ReminderGuidance.promotedOngoingState` 把授权态折进三态，缺权限时不再报"正常"。
  **权限恢复只是必要条件，不是充分条件（2026-09-16 复测修正）**：重装声明该权限的版本后
  `flags=ONGOING_EVENT|ONLY_ALERT_ONCE|PROMOTED_ONGOING` 确实被框架打上，SystemUI 也有
  `FocusPlugin: onAuthSuccess` → `IslandTemplateFactory: RealBigIsland CREATE NEW path`，
  但用户复看**实况只出现在下拉通知栏、岛上仍然没有**。**误读点**：
  `RealBigIsland CREATE NEW path` 只证明 SystemUI **inflate/测量**了岛的视图，
  不等于它被渲染到屏幕上，不能当作"已上岛"的判据。真正的判据是屏幕上有胶囊 +
  `make the transition to ElementT` 一类的上岛动画日志。
  补上上面两处修复之后，才第一次**以独有文案「自检中」为判据**确认我们的胶囊上了岛
  （中间那次"看到胶囊"的判读其实是 `com.qoder.mobile.cn` 的，见前面的踩坑条目）。
- **由复测得到的关键区分**：**标准实况（promoted ongoing）通道不需要小米白名单**——
  全程无 `canShowFocus` / `canCustomFocus` / `checkSignatures` 拒绝日志。
  白名单只挡住下面那条**自定义岛内容**（`miui.focus.param`）的路。
  不要把两件事混成"我们上不了岛"。
- **可观测化（2026-09-16）**：实况最大的问题不是"没写对"，而是**写完无从知道系统采纳没有**。
  新增 `IslandDiagnostics`：① `promotedOngoingState` 三态（允许/用户关闭/系统不支持）；
  ② `focusProtocolVersion` 读 `Settings.System` 的 `notification_focus_protocol`
  （普通应用可读，HyperIsland 的引导页也读它）；③ `postSelfTest` 发一条 12 秒后自动消失的
  样例实况（与实发通知同特征），入口在「设置 → 提醒可靠性 → 实况通道自检」。
  进程启动时 `BUAAApplication.onCreate` 无条件 `cancelSelfTest`，避免这 12 秒内被杀留下常驻通知。
  **为什么必须上屏**：这台 HyperOS 会整条吞掉第三方应用的 logcat（`logcat --pid=<我们进程>`
  读到 0 行，包 flags 明确带 DEBUGGABLE，logd 无 blacklist），日志在这台机器上**不是可用判据**；
  自检因此把 `hasPromotableCharacteristics()` 的结论直接显示在设置页
  （`IslandDiagnostics.lastSelfTestVerdict`）。
  **v0.1.0 现状**：③ 与「实况通道自检」入口、`lastSelfTestVerdict`、启动时的 `cancelSelfTest`
  一并作为调试内容删除；①② 两项探针保留，继续驱动引导页与设置页那一行的文案与跳转，
  `logPromotionShape()` 仍在 `postClassOngoing` 与 `CourseFluidService` 两个分支上打日志。
  上面那几条真机判据（「自检中」文案、吞 logcat、只能读屏幕）是**当时得出结论的过程**，
  仍然有效；删掉的只是复现它的那颗按钮。
- **点实况没反应已修**：`postClassOngoing` 一直收到 `courseId` 却从不使用，服务侧 contentIntent
  也不带课程 id。现在两条路径共用 `ReminderNotifications.courseLaunchPendingIntent`，
  键源唯一为 `MainActivity.EXTRA_COURSE_ID`。
- **定制岛内容（`miui.focus.param`）不是我们能单方面做到的**：HyperIsland 之所以能常驻上岛并
  自绘内容，是因为它以 Xposed 模块身份**在 SystemUI 进程里**调 `nm.notify()`，
  并且额外提供两个 hook 把小米的白名单强制改写成 true：
  `miui.systemui.notification.NotificationSettingsManager.canShowFocus` / `canCustomFocus`
  （该 business 能不能显示）与 `miui.systemui.notification.focus.SignatureChecker.checkSignatures`
  （发通知方的签名有没有登记）。其引导页第 3 步就是让用户去 HyperCeiler 打开
  「移除焦点通知白名单」+「解锁焦点通知白名单验证」。普通安装的第三方应用没有等价物。
- **因此本项目把岛模板做成实验项**：`IslandFocusTemplate` 只在小米/Redmi 出现、
  默认关闭（「设置 → 提醒可靠性 → 自定义超级岛内容（实验）」）。
  打开后岛没变化的**唯一正确解读**是"该 business 未提报通过 / 机器未解锁白名单"，
  而不是载荷写错了 —— 先用上面的自检确认标准实况通道本身通不通。
- **载荷取证（2026-09-16，HyperIsland `ScreenRecorderHook.buildFocusBundle`）**：
  ① `miui.focus.param` 是**字符串** extra，外面必须套 `{"param_v2": {...}}`；
  ② 动作要摊平成顶层 `miui.focus.action_1 / _2`（`miui.focus.actions` 嵌套 Bundle 那份不被读取）；
  ③ `sameWidthDigitInfo` 里**只有 `timerInfo`**，没有 title/content —— 课程文字归
  通知 title/text 与 `android.shortCriticalText`；
  ④ `timerInfo` 是**计时器**：`timerWhen` = 起始绝对时刻、`timerType` 1 = 走秒 / 2 = 停住
  （样例只在录屏暂停时给 2，没有"倒计时"取值）。填剩余毫秒会在下发那一刻被冻结；
  ⑤ `highlightColor` 是 `#RRGGBB` 字符串。以上均由 `IslandFocusTemplateTest` 钉住字段名。
- **框架 `Notification.Builder` 在 API 36 没有 `setRequestPromotedOngoing`**（A，本机编译核实）：
  只有 androidx 兼容层有该 setter，所以走框架 builder 的 Android 16 分支只能靠
  `extras["android.requestPromotedOngoing"]` 这一条路。
- **2026-09-16 真机反馈：「课前倒计点上不了岛，课中上了但简陋」的根因**（A，代码层面已定位并改）：
  ① 实况有三个各自为政的生产者（课前 `ReminderReceiver`、课中 `ClassProgressReceiver`、
  漏铃兜底 `LiveClassResyncer`），只有课中那个先发 promoted 兜底通知再起服务；
  ② 课前那条走的是 `setUsesChronometer` + `setChronometerCountDown` 的普通提醒，
  **chronometer 会顶掉 promoted 小字**（SleepDown 的 `LiveUpdateForegroundService.kt:95`
  注释同样写明这点），因此它永远提不上岛；③ 课中卡片正文只有一行节次，信息量等同于没有。
  **修法**：三条路径统一走 `ReminderNotifications.startLiveWindow(..., phase)` —— 先在同一条
  通知 id 上发 promoted 兜底，再起 `CourseFluidService`（服务成功则覆盖同 id），
  `LivePhase.BEFORE_CLASS / IN_CLASS` 只差文案只差倒计时目标；课前那条不再使用 chronometer，
  改为把「还有 N 分钟上课」直接写进正文；正文统一两行（节次 · 时间区间 · 地点 + 倒计时），
  与 `android.shortCriticalText` 的纯文本「N分钟」小字同口径取数（`minutesLeft` 单一来源）。
  **上课铃那一刻的交接竞态已按机制改掉**：课前那段的收尾时刻正是 `ClassProgressReceiver`
  处理 ACTION_START 的时刻，而服务自己收尾也会走 `finishLiveAndReschedule`（恢复勿扰 +
  `rescheduleNextWindow`）—— `rescheduleWindows` 的第一件事是 `cancel()` 现有闹钟再重排，
  于是它把刚排好的下课铃撤掉、并把"开始时间已在过去"的上课铃重新点一次，表现就是
  课刚上岛就掉一下又重弹；勿扰恢复还会抵消随后 ACTION_START 的 `enter()`。
  现在 `finishLiveAndReschedule` 按 `LivePhase` 分流，**只有课中收尾**才恢复勿扰 + 续排，
  课前的交接整个交给 ACTION_START；真把上课铃吞掉的场景仍有兜底
  （课前提醒本身会跑 `ReminderScheduler.rescheduleAll`，切回前台跑 `LiveClassResyncer`）。
  **仍需真机复核**：课前这一段是否第一次上岛、以及铃前后的通知在同一 id 上交接是否顺滑。
  判据仍只能用屏幕上的独有文案，不能看 logcat（这台机器吞第三方日志）。

### 2b. 运行时权限的申请与出路

- 日历（同步/移除）、通知（API 33+）均为**用到时弹系统申请框**；
  用户勾选「不再询问」后系统不再弹窗，App 会检测这种情况并给出
  「去系统设置」/「去通知设置」跳转，避免「点了没反应」的死角。
- 「设置 → 提醒可靠性」的权限状态行会随 ON_RESUME 刷新：在系统设置里
  开完权限回到 App 即显示最新状态（勿扰访问、通知、精确闹钟、实况通知三态均挂了同一 tick）。

### 2c. CalendarProvider 查询投影的 ACCOUNT_NAME/ACCOUNT_TYPE 硬规则

- **规则**：API 14+ 查询 `Calendars` 表时，投影若包含 `ACCOUNT_NAME` 与
  `ACCOUNT_TYPE` 二者之一，就**必须同时包含另一个**，否则 provider 直接抛
  `IllegalArgumentException`。该异常一旦被 `runCatching` 吞掉，对外表现就是
  「日历选择检索不到任何日历」（2026-09-15 已修复：`CalendarSyncManager.queryCalendars`
  投影补齐 `ACCOUNT_TYPE`，并加 `VISIBLE=1` 过滤 + 空结果二次兜底查询）。
- **教训**：对 ContentProvider 的查询失败不要静默吞掉后返回空集合——
  「空结果」与「查询失败」是两种完全不同的故障，日志里必须留痕（`queryCalendarsOnce`）。

### 3. 国产 ROM 后台查杀影响提醒

- HyperOS / ColorOS 等激进省电策略可能杀死进程导致提醒不响，App 无法完全规避。
- 已提供「提醒可靠性」引导（跳转电池优化白名单 + 自启动说明），但最终取决于用户设置。

### 4. 桌面组件「一键添加」依赖 Launcher

- `requestPinAppWidget` 仅部分 Launcher 支持；不支持时 Toast 提示改为手动长按添加。
- 各家 Launcher 对组件刷新频率的限制不同，属平台差异。

### 4a. 集合组件的行视图由**宿主**缓存，键是 `getItemId`

- 真机反馈「改完课表组件不刷新，甚至手动刷新后一点击又变回原样」的根因不在数据层：
  `RemoteViewsAdapter` 在 `hasStableIds() = true` 时按 `getItemId` 缓存行视图，
  id 不变就直接把上一次那份 RemoteViews 贴回来 —— 工厂刚算好的新内容根本没机会上场。
  旧 id 只含 `courseId + 节次`（网格更甚，只有 `position + 外观`），
  而用户改的恰恰是**课名与教室**，两项都不进 id。
- **现状**：两个工厂统一走 `WidgetCommon.itemKey(position, contentStamp)`，
  高位放位置、低位放内容指纹。位置必须在高位：`hasStableIds` 下同一列内 id 重复会抛异常。
  数据侧 `BackgroundSync.refreshWidgets()` 已经先 `invalidate()` + 重写快照，
  这一条补上的是"通知到了、行却没换"的后半程。
- **4×2 紧凑网格「一天只显示一节课」**：列宽算术决定的，不是数据缺失 ——
  `numColumns=7` 在 4 格宽的组件里每列只有约 30dp，旧写法把「教室 + 全名」拼成一行，
  11sp 下折三四屏宽，`maxLines` 一截就只剩第一节课。
  现在每节课一行、短名截 3 字（`WEEK_GRID_NAME_CHARS`）、9sp、最多 5 行，
  第 5 行留给「＋N」溢出提示（`WEEK_GRID_MAX_LINES`，与布局的 `maxLines` 同值）。

### 4b. 组件被宿主重绑后，第一帧画的是 `initialLayout`

- 真机反馈「小组件编辑保存后会刷新，但是长按后又会变为原样」不是配置被写回，
  而是**重绑**：长按进编辑、拖拽改尺寸、恢复桌面这些动作会让 Launcher 重新绑定这个实例，
  系统立刻按 `initialLayout` 画一帧。本项目的 `initialLayout` 指的是静态示例预览
  （R5 F-27：线上布局是纯白底 + 运行时着色，静态渲染出来是白底白字），
  于是那一帧看着就是"配置被改回去了"。外观存在 `WidgetAppearanceStore` 里，一个字节都没动。
- **修法**：`ScheduleAppWidgetProvider` 这个共同底座在两个重绑通知点上补一次重绘 ——
  `onRestored`（**必须在 manifest 的 intent-filter 里声明 `APPWIDGET_RESTORED`**，
  否则系统根本不会把这条广播发给 Provider）与 `onAppWidgetOptionsChanged`
  （宿主把改尺寸发成"带 `EXTRA_OPTIONS` 的 UPDATE"时走这里）。
  按实例夹 1.5s 时间闸：一次拖拽会连发好几轮 optionsChanged。
- **一次广播只有一份 `PendingResult`**：AOSP 的 `onReceive` 会在同一条 UPDATE 里先派
  optionsChanged 再派 `onUpdate`，两边都要续命票。底座只把票给先到的那个，
  后到的拿到 `null` 照样重绘 —— `goAsync()` 第二次调用返回 null 而不是抛异常。
  **别写"拿到票就 finish"**：finish 一份已失效的票据会让当前广播的原始结果永久挂起。

### 4c. `WebView.pauseTimers()` 挂起的是**整个进程**的定时器

- 真机反馈「身份认证登录后会卡在打开导入预览界面」的机制在这里：会话 WebView 退后台时
  `BuaaWebSession` 会 `pauseTimers()` 省电，而它是**进程级**开关 —— 之后一旦内存压力
  销毁了那份会话 WebView（`releaseForMemory`），回前台时就再没有任何实例去 `resumeTimers()`，
  整个进程的 WebView 定时器永久冻住。新建的登录 WebView 一出生 JS 就是停的，
  `onPageFinished` 永不回调，界面停在"正在打开导入预览"。
- **注意桩与文档不一致**：android-36/37 的 `android.jar` 里 `pauseTimers/resumeTimers`
  是**实例方法**（`javap -v` 只有 `ACC_PUBLIC`，没有 `ACC_STATIC`），照文档当静态方法调会编译失败。
  效果却确实是进程级的，所以"对哪个实例调"只决定能不能编译，不决定影响范围。
- **修法**：闸门改成 `applyTimerGate(web)`（随前后台状态切当前实例），并在**每一个**
  WebView 诞生点与销毁点上调 `alignTimersWithForeground(web)`：
  `createSessionWebView()`、`BuaaLoginScreen.createSsoWebView`、`releaseForMemory`、`abandonRestore`。
  销毁点必须在 `destroy()` **之前**调 —— 销毁之后再 `resumeTimers()` 是空操作。
  新增任何 WebView 都要照这一条补，否则又是"某一页偶尔整页冻结"。
- 同一反馈的第二半是纯布局问题：待确认导入那张卡在滚动内容末尾，登录跳转后只看到 Hero
  与"解析完成…"，确认按钮要往下翻 —— 现在移到导入页第一屏。

## 开发环境坑（构建 / CI）

### 5. Gradle 8.14.3 不支持 Java 25

- 较新的 Android Studio 自带 JBR 是 Java 25，直接用它跑 Gradle 只报一行 `25.0.2` 就失败。
- **现状**：`app/build.gradle.kts` 已用 `kotlin { jvmToolchain(21) }` 固定编译工具链，
  本地与 CI 统一 JDK 21。daemon 运行 JVM 仍需显式指定：
  `JAVA_HOME=/path/to/jdk-21 ./gradlew ...`（见 README「构建」）。

### 6. 构建缓存目录不可写会让任务硬失败

- 受限环境下 `build-cache-1/*.part` 拒绝访问会让所有任务失败，
  因此 `gradle.properties` **刻意不开启** `org.gradle.caching=true`，开启前先确认环境可写。

## 功能边界（现状与取舍）

### 7. 逐周抓取在极端弱网下仍可能失败

- 课表按 `type=week` 逐周请求，单周失败会**先静默重试一次**再记入失败列表；
  但连续失败仍会让本次导入标记「不完整」，失败周以 warning 呈现，可整轮重试。
- 单请求超时 12s：为控制整学期最坏耗时（19 周），宁可单周失败也不无限等待。

### 8. 分享口令是明文编码

- `BUAASCH1:` 口令 = 备份 JSON 压缩 + Base64，**无加密无口令**，拿到即还原。
- 取舍理由：口令保护（加密 / 有效期）是独立特性，涉及密钥交换 UI，暂不做；
  风险已在 README 与 `docs/PRIVACY.md` 显式披露。

### 9. 国际化未做（有意为之）

- 无 `values-*` 目录，UI 中文硬编码在 Composable 里。当前不做多语言，见 README「待实现」。

### 10. 自更新链路的边界

- **没有校验和可用**：Gitee 的 assets 只给 `name` 与 `browser_download_url`，接口不提供
  文件摘要。因此"能不能装"只能落在文件自身四道闸上：`Content-Type` 不是 html/json、
  写到 `Content-Length` 声明的字节数、文件头是 zip 本地头 `50 4B 03 04`、体积 ≥ 64 KB。
  再往上的完整性只能靠 HTTPS 链路本身。
- **不支持断点续传**：`Range` 头被服务端忽略（实测整包重传），中断只能整个重来。
  所以下载走 `.part` 临时名再改名，半截文件不会以下一次"已下好"的身份被复用。
- **被登录墙挡下时是 200 + 一段 HTML**：应用会把它拦在安装器之前并报出具体原因，
  而不是让系统丢一句"解析软件包时出现问题"。
- **装不上有四条退路**：未授"安装未知应用"→ 停在 `NeedsInstallPermission`，包留在盘上，
  开好权限点「重试安装」不重下；权限页三档 intent 依次回退（厂商 ROM 会挪走第一档）；
  签名与本机不兼容 → `InstallBlocked` 先讲清"要卸载、课表会没、先导出备份"；
  安装器本身拉不起来 → 转浏览器打开发布页。这期间设置页红点**不会**被提前清掉。
- **没配发布密钥时 release 产物是 unsigned**，装不上也发不出去；`-PdebugSign` 用本机调试密钥
  自签只为真机验一遍 R8 产物，这种包**不得发布**（换正式密钥时用户须先卸载，数据全清）。
  发版步骤与对端实测细节统一记在 `docs/RELEASE.md`，这里只留边界，避免两处漂移。
