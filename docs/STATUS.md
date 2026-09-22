# 功能状态清单

> 这一页是从 `README.md` 挪出来的历史清单。README 面向装 App 的人，这里面向想知道
> "哪些已经落地、哪些还没做、哪些明确不做"的贡献者，所以条目按开发顺序而不是使用顺序排。
> 带 ⚠️ 的条目有平台限制或使用前提。
>
> 最后整理：2026-09-23 凌晨（**T68** 收用户那句「为什么凌晨还是显示前一天的课表」。真账不在
> 时钟上：「翻到哪一天」存在 `rememberSaveable` 的槽位里（它本来就是要比进程活得长），而决定
> body 那一天的只有 `browseDate ?: today` 一行 —— **没有任何东西在 today 前进时把它作废**，
> 于是昨天翻过一天（或点过桌面组件某一格）之后，今早日视图仍停在那一天。修法是一笔浏览只在
> **它被按下的那一天之内**有效（锚定日与浏览日成对落盘，判据取锚定日而非"上次见过的今天"，
> 所以进程死活/恢复次序/滴答迟到都不影响结论），顺带把"顶栏写着今天、body 画着昨天"这一族
> （T56 修了周、日没修）收进唯一一份 `dayViewDate`。
> ⚠️ 装机复核抓到**本卡自己带来的一笔新账**：切到「周课表」页签后顶栏第二行仍跟着日视图的
> 浏览日走（截图里网格高亮的是周二 9/22，第二行写「9月24日 星期四」），而那一页日视图根本不在屏上
> ⇒ 另立 **T68b** 收，不悄悄咽下去。
> 在跑：**T70**（今日课表左右滑动卡顿：手写 `detectHorizontalDragGestures` + 每事件 `launch{snapTo}`
> + `AnimatedContent` 整页双份组合；对照组是不卡的 `WeekView` 那枚 `HorizontalPager`）。
> 排在它后面的是 T68b 与 **T69**（学期统计入口太深）—— 三条都要碰 `HomeScreen.kt`/`DayView.kt`，串行。
> 再往前是扫码这一族连收四卡：**T64** 的真账是 `ImageAnalysis` 从没声明分辨率、
> 按 CameraX 文档落在 640×480，装机实测改后交付 **1280×960**；**T65** 收 T64 点名的三条欠账 ——
> potential 框分档 + 检测驱动的四档变焦阶梯（含"抬了没用就回滚且本轮不再试"）替掉"每次绑定固定抬 1.5×"
> 那个错形态、点按对焦**成功档**补上留痕、并按用户决定「不要闪光灯，我们的场景不用」把手电整条撤走；
> **T66** 把用户点头"加"的那一半落地：zxing-cpp 以**兜底第二引擎**进包（主力不换、不逐帧双解、
> 四道上界节流、两引擎共用一道提交闸门），代价实测 **+741,224 B**；
> **T67** 收 T59 有意没收回的那笔电账（= 台账 #99）：**解码器判死之后相机还绑着、分析器一帧一帧
> `close()` 却一枚也不再解** ⇒ 新增一颗以 `scannerGiveUp` 为键的 effect 把帧流停下来，
> 停用窗口那条自动活路一个字没动；顺手收掉一条 Kotlin 优先级造成的取证行谎话
> （`"…" + giveUpReason ?: "停用窗口内"` 里 Elvis 从不生效 ⇒ 那一档读出来是"…：null"）。
> ⚠️ 判死那一档在模拟器上**触发不了**（三种强制手段全试过）⇒ 停帧这个新行为目前只有 JVM 证据。
> 装机此前第一次拿到**正解码**（虚拟场景棋盘格被 ML Kit 误检成一枚有原文的码 → 本地判否、零请求），
> 于是"帧→解码→原文→提交闸门→界面"整条端到端第一次有证据，同时也记下 ML Kit 的误检这笔新账。
> 再往前是「节假日没有标注出来」+「时间轴模式显示的文字内容有点少」两条 ——
> 前者分两层都收了：数据侧 T60（三个触发点 + 跨月 + 每一档停法留一行取证）、渲染侧 T62
> （今日页页头先量后摆、全称走读屏语义；真账是数据就算到位了也只有一格单字在画它）；
> 后者 T61/T61b，真账是 45 分钟块的行高预算永远过不去那道 58dp 门，
> 修完又打回一次，因为预算吃的是 `TextStyle.lineHeight` 那个**名义值**而不是排版真正吐出来的行盒。
> 同日往前是扫码「没反应」第二轮 T59/T59b（查到底是**装机版本停在 T44 之前**、
> 而更新链按 versionName 比所以永远不会提示；仓库侧另收两条"只关不开"的开关与三条裸静默支路）、
> 学分显示补全 + 体育课显示体育项目 T58、学期统计页
> 三张增密图 T51/T54、课次卡片进场动画 T52、掉帧自动降档死链修好 T53；
> 上一轮整理是 2026-09-21 的「去掉手输签到码」入口，T45；
> v0.1.0 之前的整理见对应条目）。

## 已落地

- [x] Android 工程骨架（Compose + Material 3 + Room + KSP）
- [x] CI：GitHub Actions 编译 + 单元测试
- [x] 核心数据模型：Course / Semester / TimeSlot / ImportHistory / ReminderSetting / CalendarSync / WidgetSnapshot / SpecialDay
- [x] Room 数据层与 Repository（按学期覆盖导入，保留手动课程）
- [x] 课表领域逻辑：周次解析、教学周计算、冲突检测
- [x] 周视图 / 日视图（24h 时间轴 + 节次行双模式、拖拽改时间、宽屏双栏并排）
- [x] 课程编辑器（手动增删改查）
- [x] 设置页（学期配置）
- [x] 导入页占位 + 示例数据
- [x] 北航接口 DTO / 解析器 / API 客户端
- [x] `getMyScheduleDetail` 真实 JSON 解析验证
- [x] `getTermWeeks` 真实 JSON 解析验证
- [x] 按周循环导入 + 跨周合并
- [x] M4b 临时入口：粘贴 Cookie 调北航接口导入（**仅 debug 构建**，release 不显示）
- [x] M4 登录：WebView 统一身份认证登录，并在 byxt 页面上下文里逐周抓取课表（原生网络栈复刻不出凭证，必 401）
- [x] 导入预览确认（新增/更新/冲突）——**登录链路与 Cookie/ICS/文本/口令链路共用同一预览**，确认前不落库
- [x] ICS 文件导入
- [x] 文本导入
- [x] 备份/恢复（`BackupData` JSON；导出与导入同一套 schema，见 `docs/BACKUP_FORMAT.md`）
- [x] 课程提醒（AlarmManager 只保留下一条闹钟，含上课倒计时、时区/时间变化重排）
- [x] 五种桌面组件（今日 / 明日 / 本周课表 / 本周网格 4×2 / 下一节课）
- [x] Widget 事件驱动刷新（数据变化 + 每日零点，无固定轮询）
      首个/最后一个实例的注册与注销（`onEnabled` / `onDisabled`）自 T18 起收在
      `ScheduleAppWidgetProvider` 基类一份实现里（此前六家各写一份、内容逐字相同），
      并且整条链跑在 `goAsync()` 续命的 `Dispatchers.IO` 协程上而不是广播主线程上 ——
      WorkManager 已改按需初始化，"进程里第一个调 `getInstance` 的线程"就是付建库钱的人
- [x] 桌面快照只写脏 key（T43）：`WidgetDataSynchronizer.sync()` 每轮仍给每个 key 排一行
      （K = 学期数 + 1，"一 key 一行"这条不变式没动），但**只把内容真的变了的行 upsert** 进
      `widget_snapshots`。判据三条（任一成立才写）：没有凭据 / 载荷 SHA-256 不同 /
      表里那一行的时刻已经不被读侧认账（与 `WidgetDataCache` 那条 15 分钟 + 同一天同口径 ——
      少了第三条就是把"一轮省 K 次 upsert"换成"每次组件刷新多付 3 趟主库查询 + 1 次补写"，净亏）。
      凭据是本进程内存里那张 `key → (载荷哈希, 写入时刻)`，**不去 SELECT 表里那行回来比**：
      读回 K 行就把省下的那次写换成了 K 趟查询，一分不买。脏判定与哈希对比是纯 JVM 函数
      （`planSnapshotWrites` / `payloadHashOf` / `snapshotStampStillFresh`，时钟与"是否同一天"
      都由调用点当参数传），钉在 `WidgetSnapshotDirtyTest`（13 条）。
      收益实数（K = 5）：数据未变、同进程再来一轮 ⇒ 改前 1+5 查 5 写、改后 1+5 查 **0 写**；
      改一门课 ⇒ 5 写 → 1～2 写；冷进程第一轮 / 超 15 分钟（开机、零点、12 小时兜底）⇒
      仍是 5 写，一行都没少、查询也没多付。
      **最高判据是"一个 key 都不误删"**：`deleteKeysNotIn` 拿的名单仍是 `keys`
      （全部排产 key），不是写出去的那几行 —— 名单跟着写集合走的话，"本轮一行都没写"
      就等价于"把整表判空"，那条 `DELETE ... NOT IN ()` 是 prepare 即语法失败、整批事务回滚。
      这一条既有纯函数级断言（跳满 K 行时凭据键集仍是 K 个 key），也有一条按源码核对
      "传的是 `keys.toList()`"的守卫（JVM 跑不到真库，办法抄 `ColdStartRebuildWiringTest`）。
      同时评估过两条收窄，**都判为不做**，理由记在 `BackgroundSync.syncAndRedrawAllWidgets`
      的注释里：① "数据没变跳过快照重写"这一半已由上面收掉，整步不调 `sync` 反而不安全
      （`refreshWidgets` 开头无条件把进程内缓存 invalidate 掉了）；② "按受影响 provider 收窄重绘"
      拿不到"受影响"这个结论（六家共读同一份 `WidgetData`、`WidgetBindingStore` 没有枚举接口），
      而各家 `updateAll` 第一件事就是 `getAppWidgetIds`、没实例的家就地返回，本来就没的量可省。
      ⚠️ `WidgetCommon.requestLiveRefresh`（上下课铃驱动的轻重绘）不参与任何这类收窄 ——
      那一刻课表一个字没改、只是时间翻面，今日列表的「进行中」高亮靠的就是这一枪
- [x] 备份恢复预览（版本校验 + 内容摘要确认）
- [x] 课表分享口令（压缩编码，聊天工具可直接粘贴互导）
      ⚠️ 口令是**明文编码**（无加密、无口令保护），拿到即可完整还原课表（课程/教师/教室）；
      只应发给明确的接收人，公开场合建议发截图。详见 `docs/PRIVACY.md`
- [x] ICS 日历导出（每次上课一个日程，可直接导入系统日历）
- [x] 数据导出：WakeUp 兼容 JSON（`ScheduleExporters.toWakeUpJson`，给 WakeUp 课程表 App 用）
      ⚠️ WakeUp 格式**不是本 App 的备份格式**，导出的文件无法通过「备份恢复」导入；
      要备份/迁移请用下面的 `BackupData` JSON 或课表分享口令
- [x] 数据导出：复制本周课表纯文本（可粘贴到聊天工具）
- [x] 系统日历增量同步（Calendar Provider：目标日历选择、差异确认、内容变化增量更新、一键移除）
- [x] 提醒方式选择（应用内提醒 / 系统日历提醒，避免双重通知）
- [x] 上课铃 / 下课铃（课程进行中常驻通知；上课自动勿扰，下课后恢复上课前状态。
      勿扰与常驻通知是两个独立开关，只开勿扰也能生效；授权状态在设置页随回到前台即时刷新）
- [x] Room schema 版本化 + 迁移测试：JVM 侧 `MigrationChainTest`（版本链连续、注册顺序、`ADD COLUMN`
      与导出的 schema JSON 列集合对得上）+ 仪器侧 `MigrationTest`（**真正跑一遍升级**，需连机 `connectedDebugAndroidTest`）
- [x] 横滑翻周（HorizontalPager，与顶部翻周按钮/跳周双向同步）
- [x] 触觉反馈（翻周、切换视图、校区/学期选择）
- [x] 页面转场动效 + 系统「移除动画」适配
- [x] 排版刻度补全（10 级，修复课程卡文字静默回落到 M3 默认 11sp）
- [x] 动效令牌（duration / easing 集中定义）
- [x] 校区筛选（此前按钮只改组件内状态，选了不生效）
- [x] 玻璃 effect 缓存（effectKey 化，避免每分钟 tick 重算 blur/lens）
- [x] 桌面组件外观配置（**按实例**设置配色来源 / 10 套一键样式预设 / 背景色 / 不透明度 / 圆角 / 文字颜色，实时预览所见即所得；Launcher 支持 `requestPinAppWidget` 时可一键添加到桌面，不支持时 Toast 提示改手动长按添加）
      玻璃感壁纸背景那张位图**同参数只烤一遍**（`WidgetBackgroundRenderer` 里按"能让像素变的全部输入"
      建键的有界 LRU，容量 2）：此前每个实例每次刷新都新造一张 480x320 ARGB_8888 = 614,400 B
      （与真机 `dumpsys appwidget` 报的 `views_bitmap_memory` 逐字吻合），N 个组件把同一张壁纸糊 N 遍。
      出图尺寸也不再一律 480x320：按该实例 options 报的每一轴出图、上限仍是那两枚常量
      （2×1「下一节课」那一档 614,400 B → 120,960 B）。屏幕上看到的圆角与画布尺寸无关
      （`bake` 与 Launcher 的拉伸互为逆运算，钉在 `WidgetBackgroundRendererCacheTest`）
- [x] 节次时间可编辑（HH:mm 格式 + 结束晚于开始校验，保存前不写库）
- [x] 导入抓取可取消（登录与刷新两条链路均支持；取消不落库，登录会话保留）
- [x] 课表背景默认提取系统桌面壁纸（可关闭回退渐变；拾取的图片优先级更高；支持模糊/亮度/取景缩放）
      ⚠️ Android 14（API 34）起系统禁止普通应用读取壁纸（`getDrawable` 需 `MANAGE_EXTERNAL_STORAGE`），
      该版本上会自动回退渐变或用户自选图片；设置页会在该场景下显式提示并引导改用手动选图
- [x] 设置页 / 导入页的大卡片为**透明磨砂**面板（不做折射），模糊半径可在「设置 → 外观 → 面板模糊」调节
- [x] 课表管理总览页（按课程归并全部片段：搜索、整门课改色、整门课删除、进入编辑器）
- [x] 冲突处理向导（按组给出同天最近空位建议，一键只改冲突周）
- [x] 多课表学期切换（设置页在已导入学期间切换"当前学期"，无需重建数据）
- [x] 导入预览逐条勾选（取消勾选 = 原样保留已存在课程，不会误删）+ 导入历史独立页
- [x] 发布签名配置（`local.properties` 或 `BUAA_KEYSTORE_*` 环境变量；缺省保持 unsigned）
- [x] CI 跑模拟器仪器测试（`reactivecircus/android-emulator-runner` + `connectedDebugAndroidTest`，
      API 29 + API 34 矩阵，fail-fast 关闭、报告分档上传）
- [x] 文档体系（`docs/`：架构总览 / 备份与口令格式 / 设计系统约束 / 隐私与数据安全 / 已知问题 / 厂商 ROM 适配笔记 / 发版与自更新）
- [x] 宽屏（≥600dp）周视图 + 日视图双栏并排（用 `Row` 权重实现，未引入 material3-adaptive）
- [x] 首启引导：隐私说明 + 5 步 Pager（环境自检与「厂商后台放行」合进步骤「提醒可靠性」：
      通知 / 精确闹钟 / 电池优化豁免 / 实况提升权限 / 澎湃焦点通知协议，
      任一项未通过都直接给跳转入口）
- [x] 应用内检查更新 → 下载 → 安装（对端是本仓库 Gitee 的 Releases，见 `docs/RELEASE.md`）
- [x] 澎湃超级岛 / ColorOS 流体云课堂实况（课前倒计时 + 课中进度）
- [x] 学期统计页（设置 → 课表 → 学期统计：总学分、每周负载柱状、逐课学分对比、空档统计）。
      数据只吃 `SemesterStats.summarize()` 一个入口，按课程组归并——一门课拆成三段只算一遍学分
      ⚠️ 入口本身从 `4b3f10e` 那次起就没接上（`onOpenStats` 只传给了画不出这一行的根设置页，
      `settings/{section}` 子页走的是 `= {}` 默认值，点下去静默无响应），本轮补接线并把设置页
      的导航回调收在 `AppNavHost` 一处构造 —— 两个调用点各写一份是这类漏过的结构性成因
- [x] 课程学分字段（Room **v8→v9**，`ALTER TABLE courses ADD COLUMN credit REAL`，可空、无默认）。
      口径：**留空 = 不知道**，**0 = 教务明说这门课不计学分**，两者在统计页是两个说法；
      旧的备份 / 分享口令缺这个键照常恢复
- [x] 智学北航（SPOC）扫码签到：首页加号菜单进，先登录（WebView 走统一身份认证，凭证
      KeyStore 加密落盘）再扫码；**扫到即自动提交**，无确认页，他班的码交服务端判定；
      扫码自动提交，无确认页，他班的码交服务端判定；兜底只剩相册选图一条（手输签到码整条入口
      已由 T45 拆除）；课前提醒可带「扫码签到」按钮（设置 → 通知与提醒）
      ⚠️ 解码库只打进 **arm64-v8a**（换取 +3.70MB 而非 +20.2MB），其余 ABI 与 x86_64
      模拟器上进这一页两条解码路一起没有实时取景，只剩相册识别
      ⚠️ **真机联调未跑过**：老师端二维码的字面内容是唯一没取到证的环节，解析器按三形态
      兼容。偏差与待答问题见 `docs/BUAA_SPOC_SIGNIN_PLAN.md` §11、`docs/KNOWN_ISSUES.md` §11
- [x] 扫码页「扫码没反应」三条（T44）：三条都是"界面看着活着、链路其实已经停了"。
      ① **提交闸门从布尔死锁换成「原文 + 时刻」**（根因）。旧写法 `QrCodeAnalyzer.consumed`
      在任何一次放行后置 true，而清它只有 `SignInState.Idle` 那一档、Idle 又只有按结果卡上的
      「重新扫码 / 继续扫码」才回得来 —— 同一张码重投出来的 `Failed` 与上一个值**相等**，
      `MutableStateFlow` 对相等的值不重发，那颗 `LaunchedEffect(state)` 就再也不触发，
      于是之后每一帧在 `analyze` 入口被 `image.close()` 丢掉。现在是 `ScanHandled(payload, atMillis)`
      + 纯函数 `shouldSubmitScan`：**换一张码立刻放行**（旧锁唯一走不通的就是这一条），
      同一张码要过 `RescanCooldownMillis = 1500ms` 冷却、**并且**屏幕上没有等用户按的结果卡
      （少了后一半就是每 1500ms 一次真提交，一直刷到用户离开 —— 同一颗闸门历来偏保守的方向）。
      代价写在 KDoc 里：闸门挪到解码之后才拦，已放行过的那些帧照旧各解一次，
      按帧节奏仍由 `STRATEGY_KEEP_ONLY_LATEST` + 单线程 executor 压着（同一时刻最多一帧在 ML Kit 手里）。
      ② `cameraLive` 旧式 `scanner != null && scannerWorking && granted && cameraError == null`
      **不看 provider**，而 `ProcessCameraProvider` 取不到（`future.get()` 抛、或永不完成）时
      `cameraError` 也留空 —— 提示条五档全落空、取景框画在一块黑 `PreviewView` 上、
      手输签到码被压成最弱一档。补法：`provider != null && analyzer != null` 进 `cameraLive`，
      提示档位新增 provider 专属一档，取值改成 `withTimeoutOrNull(4_000ms)` + 一次重试
      （重试只治瞬时失败：`getInstance` 是进程单例，那颗 future 真卡住时第二次等的还是它，
      这种设备最后由文案说话）。③ 权限的 `granted` 是颗无 key `remember{}`，
      「Don't allow」→ 去系统设置放行 → 回来，这一页永远停在「没有相机权限」且绑定不再重跑；
      现在 ON_RESUME 撞一次 tick 就重读真权限（口径抄设置页），取 provider 那颗 effect 的键
      也从 `Unit` 换成 `granted`。
      两条判据（`ScanSubmissionGate.kt` / `ScanUiStatus.kt`）零 `android` import、时钟与权限
      都在调用点读，`ScanSubmissionGateTest`（11 条）+ `ScanUiStatusTest`（当时 8 条，T59b② 起 10 条）逐支跑；
      「置位先于回调」「resume 还连着那颗按钮」「页面没有另算一份口径」这几条 JVM 跑不到的
      按源码形状钉住。门禁：**979 单测 / 125 套件 / 2 跳过 / 0 失败**，lint **0 error / 14 warning**。
      ⚠️ 三条都**没上设备验过**（本卡只有 JVM 单测 + lint），待复验的三件观测：
      ① 扫一张无效码→按「重新扫码」→再扫另一张码要有反应（改前是此后再也不反应）；
      ② `adb shell pm revoke` 掉相机权限后进页面、再去系统设置里放行，返回时提示条要翻面；
      ③ 造一个 provider 拿不到的场景（把相机服务打掉）后提示条要出现"CameraX 起不来"那一句
- [x] 去掉「手输签到码」整条入口（T45，2026-09-21）：产品拍板 —— 现实里老师端只有那张
      二维码，**不存在一个可以抄下来的签到码短码**，扫码页那条入口（底部按钮 + 输入弹窗 +
      `showManualInput`/`manualCode` 两枚 state）是按假想需求做的，整条拆除。
      拆的不只是 UI：`scanUiStatus` 降级文案里有三档的出口是手输，逐条改口径（当时是**七档**，
      不是这条早先写的"六档"—— 少算的是 T44 新加的 provider 那一档）——
      scanner 不可用 / provider 缺失两档只指相册，无权限一档留"放行 + 相册"（相册不需要
      相机权限，那条出口仍然成立）；**缺库那一档从此不许指向任何出路** —— 相机与相册共用
      同一颗 scanner，两条一起没，这是以前被手输盖住、现在盖不住的新事实（`BarhopperNativeLibProbeTest`
      ⑦ 的守卫随之改写：缺库文案里"相册/手输/输入"三个子串一个都不许出现）。
      `cameraError` 加了一处窄分支：它有两个写点（绑定失败 / 相册"读不出那张图"），后者恰恰
      是相册刚失败，再"改用相册"就是绕圈 —— 按 `GalleryUnreadablePrefix` 前缀分两支，
      判据仍是零 android import 的纯 JVM 函数。`reportNoQrCode` 的失败文案同批改口径。
      状态机 / 闸门 / `reset()` 未动；`SpocQrParser` 三形态未动（那说的是二维码**里**的内容）。
      门禁：**980 单测 / 125 套件 / 2 跳过 / 0 失败**（+1：`cameraError` 两分支的新判据；
      手输 UI 本来就没有自己的单测，被改的是钉旧文案的那几条断言），lint **0 error / 14 warning**
      （与基线同一组，删掉的五枚 import 没留下 UnusedImport）
- [x] 组件玻璃背景的图源从 API 档次一刀切换成运行时实测（T46，2026-09-21）：
      「Android 14 起第三方应用读不到桌面壁纸」这句写在 `KNOWN_ISSUES.md` 多年的断言
      被装机实测证伪（API 36 上 `getDrawable()` 仍返回真实壁纸）；闸门白关一半设备，
      被关的那一半退到"兜底"档把 App 内自选那张糊成不透明底 —— 一块与桌面无关的
      (69,77,97) 恒值死板，这就是「修复小部件背景，现在的样子太别扭了」。
      现在 `WidgetWallpaperProbe` 每轮刷新实测一次（零 android import 的判据收三格
      拒绝：拿不到位图 / 尺寸退化 / 采样全同纯色占位），组件侧图源只认实测到的系统
      桌面壁纸（`PickedImage` 在组件侧作废，无源走纯色半透明那条"桌面透得过来"的
      好看形），配置页与渲染侧共用 memo 这一个答案、四格说明按实测口径重写、那颗
      只在无源时出现的挑图按钮删除。⚠️ **App 内课表背景那条链（`SceneBackground
      .decodeSystemWallpaper` + `SettingsScreen` 壁纸提示）本卡刻意未动**，还留着
      同一道 34 闸门 —— 待修口径见 `docs/KNOWN_ISSUES.md` §1。
      上方「课表背景默认提取系统桌面壁纸」与待办「桌面组件背景的真实高斯模糊」两格里
      "Android 14 起系统禁止普通应用读取壁纸"的说法，对本条之后的**组件**这条链不再
      成立（按只追加规矩不改写原文），实况以 §1 与本条为准。
      门禁：**983 单测 / 125 套件 / 2 跳过 / 0 失败**（+2：wiring 新增"整包扫闸门
      回潮"与"探针唯一实测点"两条守卫），lint **0 error / 14 warning**（与基线同一组）
- [x] 组件玻璃背景加**主色档** + 订正上一条那句"实测证伪"（T47，2026-09-21）：
      ⚠️ **上一条里「装机实测把『Android 14 起第三方读不到桌面壁纸』证伪了：API 36 上
      `getDrawable()` 仍返回真实桌面壁纸，关掉闸门后 tile 立刻跟着桌面的亮暗两区走
      （亮区 (43,57,88)、暗区 (21,29,51)）」这句是错的**（按只追加规矩不改写原文，以本条为准）。
      那两组数量的是**无源时那块半透明板透出来的桌面像素**（板本身半透明、RemoteViews 宿主
      窗口透明），不是"壁纸位图进过组件"的证据 —— 同一类取样陷阱第二次踩（第一次见上一条
      的 (69,77,97) 恒值板）。同一台设备（buaa36 / API 36 / 1080×2400）插桩复测的真因是
      「运行时权限 + app-op」两道闸：四个读图入口（`getDrawable()` / `peekDrawable()` /
      `getBitmap()` / `peekDrawable(displayId)`）一律抛 `SecurityException: Permission
      android.permission.READ_EXTERNAL_STORAGE denied` —— 这枚权限本应用**从未声明**
      （`git log -S READ_EXTERNAL_STORAGE -- app/src/main/AndroidManifest.xml` 零条提交），
      ⇒ 那条位图路在 T46 之前和之中都不通；只声明+授予它改抛 `Op READ_MEDIA_IMAGES ignore`，
      两枚相册权限都给齐才真的收到 `BitmapDrawable` **922×1024 ARGB_8888**（9 枚采样 8 种不同）
      ⇒ 平台在 API 36 仍然把壁纸发给三方应用，挡路的正是我们自己不要的那两枚权限。
      零权限那条通道活着：`getWallpaperColors(FLAG_SYSTEM)` 给 primary sRGB(0.204, 0.243, 0.396)
      = **(52, 62, 101)**、secondary **(16, 15, 25)**、colorHints = 6；`FLAG_SYSTEM or FLAG_LOCK`
      抛 `IllegalArgumentException: Must specify exactly one kind of wallpaper to read`。
      逐条读数与取舍在 `docs/KNOWN_ISSUES.md` §1（那一节 T46 写的版本本卡已重写，不再留旧说法）。
      **本卡做法**：**不新增任何权限**（为一块半透明底板去要"你的全部照片"，代价大于收益，
      理由写进 `WidgetWallpaperProbe` 的 KDoc，并由 wiring ⑫ 扫全主源集 + 清单钉住那两个名字
      一次都不许出现）；位图那条入口一字不动地留作第一档，无源时新增**第二档 = 桌面主色**：
      探针 `paletteOf` 在 IO 线程问一次 `getWallpaperColors(FLAG_SYSTEM)`（只在位图档撞空后才问），
      memo 加第三格 `@Volatile memoPalette`，写序红线不变（两格结论先写、时间戳最后写，
      读侧先看时间戳）。判据 `WidgetGlassSource.palettePlateArgb` 零 android import（wiring ⑬
      钉该文件 `import` 行为 0、四格设备事实全靠参数交进来）：primary 定色相、两色里较暗那格
      定明度、只有两色分不出明暗（亮度差 <0.02，含副色缺席）时才让 colorHints 投票、
      不许跨过用户那块板的墨侧（跨侧整格退回预设，那格行为与改前逐字一致）、同侧之内再夹进
      WCAG AA 亮度带。`GlassSource` 加第五格 `SystemWallpaperPaletteOnly`（配置页那句因此
      **不许**再说"糊的是壁纸"，五格 `when` 不带 else，wiring ⑯ 逐格钉"该说什么"与"不许说什么"）；
      渲染侧出口换成三格 `GlassRender`（位图 / 板色 / 纯色），`blurBackground = false` 时
      一律走用户配色、连那次 binder 都不付。圆角烘焙（T30/T38/T39）、alpha 复合口径（T37）、
      按轴出图与 `GlassBakeCache`（T42）未动 ⇒ **有位图源时像素与改前逐格一致**。
      可读性改前/改后（同一条算式复算，白字压在本机那张推导板 (34,39,63)、亮度 0.0215 上）：
      **16.13:1 → 14.69:1**，两头都远在 AA 4.5 之上；带子本身把暗侧顶在亮度 0.183（=4.51:1）、
      亮侧地板 0.212（=4.51:1），跨侧那一步先拦，所以不存在"改了底色反而把字洗掉"。
      过程中被 lint 抓出来、而不是靠猜的两条方法可用性：`getWallpaperColors` 是 **API 27** 才有
      （minSdk 26 那一档交 null 走第三档）、`WallpaperColors.getColorHints()` 是 **API 31** 才有
      （27~30 交 hints=0，而 0 在判据里的语义正是"平台没话说"）。这两道版本号挡的是"这枚方法
      存不存在"，与 T46 拆掉的那道"拿 API 档次代答设备事实"的闸门不是一类东西，`KNOWN_ISSUES.md`
      §1 里把这句差别写死了。
      ⚠️ **本卡未上设备复量**（设备由用户占用，只有 JVM 单测 + lint）：装机要看的两件 ——
      ① 拨「玻璃感壁纸背景」时那块板的底色应随桌面换壁纸而变（改前的观测是 `w8.blur`
      true/false 来回拨 tile 像素差 **0 / 530100** 的静默 no-op）；② 换一张接近纯白的亮壁纸时
      板色应当**退回**用户自己选的那块（跨侧被拦下），而不是把字洗掉。取样记得先扫 tile 包围盒。
      门禁：**999 单测 / 125 套件 / 2 跳过 / 0 失败**（+16：主色档那张纯 JVM 表 9 条 +
      接线钉 7 条 —— 探针必读 colors 且不读 LOCK、memo 写序、全主源集零相册权限、判据零 import、
      渲染侧无位图才吃板色且开关关掉时不许吃、`GlassRender` 三格不带 else、配置页五格不带 else
      与逐格文案分叉），lint **0 error / 14 warning**（与基线同一组）
- [x] 冷启动链瘦身（T18）：三件事。① **WorkManager 改按需初始化** —— 清单里给
      `androidx.startup.InitializationProvider` 挂 `tools:node="merge"`、只对它下面
      `androidx.work.WorkManagerInitializer` 那**一条** meta-data 挂 `tools:node="remove"`
      （provider 本身必须留着：`ProfileInstallerInitializer` 挂在它身上，是本应用自分发拿不到
      云端 ART profile 时 Baseline Profile 唯一的落盘路径），配套 `BUAAApplication`
      实现 `androidx.work.Configuration.Provider`（少这一半 `getInstance` 会抛
      `IllegalStateException`，而调用点外面的 `runCatching` 会把它压成一行 WARN =
      兜底任务从此注册不上）。② 六家组件 Provider 逐字重复的 `onEnabled` / `onDisabled`
      收进 `ScheduleAppWidgetProvider` 基类一份，并经 `WidgetCommon` 的两个
      `*FromReceiver` 入口把整条链挪到 `goAsync()` 续命的 `Dispatchers.IO` 协程上。
      ③ **首帧之后预热一次扫码链**（`ui/signin/ScanChainWarmUp`：两次 `postFrameCallback`
      落到下一帧，两档各 ≤5 秒上限、进程内 `AtomicBoolean` 只付一次、失败静默、
      不申请权限不开相机），为的是把 `libbarhopper_v3.so` 的 `dlopen` 从扫码页首帧挪走。
      改前/改后的 provider 逐项对照、调用点线程表与静态账见
      **`docs/PERF-STARTUP-2026-09-19.md`**；门禁：780 单测 0 失败、lint 0 error / 14 warning
      （T18b 换序之后复跑 **781** 条，仍是 0 失败 / 0 error / 14 warning、`.kt` 告警集 IDENTICAL）。
      ✅ **设备上那三个数已量完**（2026-09-19，buaa36 / API 36 / debug 双版本对照，
      逐条读数与口径都在 **`docs/PERF-STARTUP-2026-09-19.md` §8**）：① 冷启动 `TotalTime`
      中位 **−593 ms（−11.4%）**（两版同为 debuggable ⇒ 不含 T16 那笔 profile 收益）、
      起手那条 `WM-WrkMgrInitializer` 在 `tid == pid` 的主线程上**已消失**，且装到机上那份
      APK 的 merged manifest 里 `ProfileInstallerInitializer` 仍在（只摘了 WorkManager 那一条）、
      `dumpsys jobscheduler` 里兜底 job 仍在 ⇒ 按需初始化没把功能弄坏；
      ② 组件广播那条路**只量到同向的一半**（`dispatch` −315 ms；`finish` 里含 `goAsync()`
      的异步段、不能读成"主线程变贵"，而"第一次 `getInstance` 落在哪条线程"要插桩才看得见，
      没跑）；③ 扫码预热**有效但当时顺序排反了** —— 解码档那约 340 ms 确实成功落地，
      却被前面相机档整额耗光的 5 秒顶到后面，已由 **T18b 换序**（`warmUp` 里解码档先、
      相机档后，顺序由 `ScanChainWarmUpTest` ⑧ 钉住），§6 ③ 那两条被证伪的量法一并订正。
      **换序后复量过**：解码器就绪落在首帧上屏后 **1.15 秒**（`.so` 映射 +0.43 秒），
      相机档那 5 秒超时退到它后面；停在首页时 `dumpsys media.camera` 计数为 0，没开相机

## 不做的内容

本项目明确不包含 AI 助手 / AI 导入 / 智能推荐等 AI 功能。

暂不考虑做国际化，目前文案均为中文硬编码。

## 待实现

- [ ] 多套节次方案（单套时间编辑 + **按首节时间/每节时长/课间自动推算整表**已完成，见 `SmartPeriods`）
- [ ] 桌面组件背景的真实高斯模糊（当前做法：采样壁纸 → 缩小再放大得到廉价模糊 + 叠加用户背景色，
      `WidgetBackgroundRenderer`；受同样的 Android 14 壁纸限制，该版本上回退纯色圆角底）
- [ ] 图片 / PDF 导入
- [ ] 完整个性化外观（壁纸取景 / 横竖屏独立配置；模糊/亮度/缩放/面板磨砂半径已完成）
- [x] 扫码第二引擎 zxing-cpp（T66，已落地，见上面那条条目）：**只当兜底**，在 ML Kit 连续只回 potential
      / 解不出原文时再解一次（`tryHarder` / `tryInvert` / `tryDownscale` 那类免费重试是它唯一强过主力的地方）；
      ⚠️ 不许换主力（468 帧私有基准：它反光 79.5% / 糊码 64.1%，差于 ML Kit 的 96.2% / 74.4%）。
      实测代价 **+741,224 B**（包 6,494,971 → 7,236,195）。
- [ ] ML Kit 误检的收口（T65⑤ 装机新发现 + T66 新添的一笔）：虚拟场景那面**高对比棋盘格**被解成一枚有原文的码并自动提交，
      靠 `SpocQrParser` 本地判否才没打成请求。真教室里没有棋盘，但黑板花纹 / 表格线 / 投影摩尔纹同类；
      T66 之后又多一条：**兜底命中时投出去的是兜底的原文**，而提交闸门的冷却只按"同一份原文"算 ⇒
      兜底误检 + 主力随后解出**不同**原文时会放行第二份。要不要在提交前加一道本地闸门
      （框稳不稳 / 原文像不像 URL / 同一枚码连续 N 帧复现才提交），未定 = #107。
- [x] Baseline Profile 接线（T16）：`:app` 与 `:benchmark` 各应用 `androidx.baselineprofile` 1.4.1，
      `:app` 侧补 `baselineProfile(project(":benchmark"))` 与显式 `implementation(libs.androidx.profileinstaller)`。
      这枚插件不是独立产品线，它就是 androidx.benchmark 那次发布里的
      benchmark-baseline-profile-gradle-plugin，所以版本号必须与 `benchmarkMacro` 同代际一起动。
      为什么值得接：本应用 Gitee 自分发 + 应用内自更新，每次自更新后系统的安装过滤会退回 verify，
      而"云端 ART profile"是 Google Play 专属通道、我们自己发的包结构上拿不到，profile 是唯一补偿。
      原先列的 5 步：①②③ 已做完。④「配 non-debuggable target variant」**不用手写** ——
      这个版本的插件已是 "wrapper + producer / consumer / apptarget" 四件套，wrapper 按模块应用的
      Android 插件类型分发：`:app`（com.android.application）拿 apptarget+consumer，
      `:benchmark`（com.android.test）拿 producer，producer 自己造出 `nonMinifiedRelease` /
      `benchmarkRelease` 两个 build type，采集走 nonMinifiedRelease（不混淆、可装机）。
      由此 `useConnectedDevices = true` 只能写在 **:benchmark** 的 producer 扩展里，
      写进 `:app` 是 unresolved reference（实测）。⑤「加一条 CI job 生成」**不做** ——
      生成要连一台 API 28+ 的设备，`android.yml` 里没有机子；产物入库靠人工。
      `automaticGenerationDuringBuild` 保持默认 false：打开后每次 assembleRelease 都会去抢设备。
      采集场景 = `BaselineProfileGenerator`（冷启动 → 另出一份 startup profile，全工程唯一带
      `includeInStartupProfile` 的）+ `InteractionBaselineProfileGenerator` 四条日常交互
      （周表滚动+翻周 / 课次行↔24 小时时间轴来回切 / 今日视图 / 设置页），四个 CUJ 落成
      **五个采集方法**：「切时间轴」在真机上是两件不同的控件（周课表侧那颗胶囊 +
      今日页的「列表/时间轴」分段），锚点不通用，合在一个方法里必有一边空跑。
      **扫码页与 WebView 导入页刻意不进 profile**：前者会让每次冷启动为走不到的 CameraX+MLKit
      帧回调付编译成本，后者是系统组件在编译、ART 管不着。
      ✅ 这五条场景的锚点已在 emulator-5554（API 36 / release nonMinified / 已播种）上按
      `uiautomator dump` 订正过一轮（T16b）。订正掉的三件事：①本工程无 `testTagsAsResourceId`，
      只有 `By.desc` / `By.text` 可用，而**带文案的节点全部 `clickable=false`**（可点容器的
      bounds 是另外的无名节点）→ 点击一律改成拿节点 `visibleBounds` 的中心做 `UiDevice.click`；
      ②场景不靠 app 的默认落位，各自点分段切到起点页并用 dump 里的证据确认，确认不了**抛异常**
         （静默空跑产出的是一份"看着成功其实没覆盖交互"的 profile，比红一条测试危险）；
         周课表侧的场景会**先刻意切一次今日页、再切回来**，为的是吃掉 `HomeScreen.kt:178-183`
         那个只在首次数据到位后触发一次、且只会把页签改成今日的 `LaunchedEffect` ——
         屏幕上页签连跳两下是预期，不是抖动，别在人工盯屏时把它当成 bug；
      ③唯一的残余未验项是今日页「哪一格被选中」的判据（`BySelector.selected(true)` 按 bounds
      罩住文字中心来认，见 `segmentSelected` 的 KDoc）—— 它红了就是 Compose 没把选中态映射进
      accessibility 树，复核动作是 dump 后 grep `selected="true"`，不是改判据写法。

      **生成与验收步骤（要连设备，由编排者按序执行）**：
      2026-09-19 已在 buaa36（API 36 / Google APIs 镜像）上按这五步跑通并入库，下面三处读数
      就是那一轮的；这一节里被实测证伪的三条说法已就地订正。
      1. **先播种**，条件是：库里有一个学期 + 多门课，且 `currentWeek` 落在学期**中间**
         （第 1 周和最后一周都不行：「下一周」/「上一周」有一侧是禁用态，翻周那步会空跑）；
         **今天这一天要有课**：`HomeScreen.kt:180` 的
         `selectedTab = if (hasTodayCourses) 1 else 0` 使冷启动**直接落在「今日」页**
         （五条场景现在都自己点分段声明起点页、不再依赖这个落位，但验收 dump 时要知道
         默认那一帧在哪页）；而今天有课时日视图内容区才有课可换 —— 空的那一天
         「列表/时间轴」两格照样渲染（`DayView.kt:247` 那个分段控件没有数量门），
         一切却只是换掉一块 `EmptyState`，这条 CUJ 就没什么价值了；
         拦冷启动的是 `schedule_settings` 里的 **`onboarding_completed=true`** 这一扇门
         （`MainActivity.kt:158` 只读它），**不是** `privacy_consent_at` —— 订正：那个键
         （`FirstRun.kt:21`）只管出网闸门（`UpdateCheck.kt:126`），没它也能进主页，
         只是更新检查会静默不发；时间轴模式保持默认（课次行），切换场景要从默认态起步。
         **生成前不要 `pm clear`**，那会把播种一起清掉。
      2. `./gradlew :app:generateBaselineProfile`（可加 `--stacktrace`）。它驱动
         `:benchmark:connectedNonMinifiedReleaseAndroidTest` → `:collectNonMinifiedReleaseBaselineProfile`
         → `:app:mergeReleaseBaselineProfile` → `:app:copyReleaseBaselineProfileIntoSrc`。
      3. 产物落在 **`app/src/release/generated/baselineProfiles/`**（生成结束时插件自己打印的就是
         这个路径，不是 `src/main`）：
         ```
         A baseline profile was generated for the variant `release`:
         file:///D:/schedule/BUAA-Schedule/app/src/release/generated/baselineProfiles/baseline-prof.txt
         A startup profile was generated for the variant `release`:
         file:///D:/schedule/BUAA-Schedule/app/src/release/generated/baselineProfiles/startup-prof.txt
         ```
         为什么会以为在 `src/main`：那一步的 task 叫 `copyReleaseBaselineProfileIntoSrc`，"IntoSrc"
         说的是**写回源集**，而它写的是 **release 源集**下的 `src/release/generated/` —— 后续构建正是从
         `src/release/generated/baselineProfiles/` 把这两份读走的，跟 `src/main` 无关。接线时按字面
         理解建的 `app/src/main/baselineProfiles/` 里只有一个占位 `.gitkeep`，插件从来没往那儿写过
         东西，本卡已把它删掉（真要手写规则时目录随时建得回来，git 只是不跟踪空目录）。
         常规 profile 与 startup profile 是两份并列的文件，都要 commit 入库，不要加进 .gitignore。
         本仓库当前这两份的量级：`baseline-prof.txt` **2,868,063 B / 26,601 行**（其中
         `com/buaa/schedule` 自己的条目 **3,901** 条），`startup-prof.txt` **2,272,111 B / 21,790 行**。
         行数是全部依赖库 profile 拼接后的总数 —— 与下面那条"报错行号在拼接之后才数出来"同源。
         这条链路已实测通：手写一行合法方法规则进 profile 目录，`assembleRelease` 后包内
         `assets/dexopt/baseline.prof` 从 6,500 变 6,508 字节 —— 也就是文件放进目录就会进包。
         那次实验（`1c64c1e`）写的是 `app/src/main/baselineProfiles/`，AGP 那侧确实也读它；
         但**生成产物**只落 `src/release/generated/`，两个目录不是一回事。
         ⚠️ 但格式很硬：`.txt` 里一条方法规则行**少了 H/S/P 任一标志位**（比如只写
         `Lcom/...;->foo()V`），`:app:expandReleaseArtProfileWildcards` 会直接让整条
         assembleRelease 失败，且报的行号是在全部依赖库 profile 拼接**之后**才数出来的
         （实测报 `baseline-prof.txt:3949:1`，而那个文件只有 1 行）—— 看着像不存在的行，
         别被误导去找依赖库。
         ⚠️ `WidgetCornerRadii` 的两条规则（`axisPx`、`resolveSizePx`）在 T38/T39 两轮改签名后
         两份文件里共 4 行失配（ART 对这种规则是静默丢弃），已**手工同步**到当前描述符 ——
         不是重新生成的，下次连设备重生成会自然覆盖；手工同步的边界是只改描述符、**不新增**
         规则（T39 新加的 `displaySizeDp`/`capToDisplay` 没有规则是正常状态，产物只反映被 trace
         到的路径）。判据来自 `javap -p -s` 打在 `app/build/tmp/kotlin-classes/debug/` 下那一份
         class（⚠️ release 目录那份可能滞后于源码，是旧编译产物，别拿它当依据）。
      4. `./gradlew :app:assembleRelease` 重装到机上，验收看**这两处**（订正：先前写的
         `dumpsys package com.buaa.schedule | grep -i profile` 在这台设备上**不成立** —— 那条 grep
         打不出 `primaryProfile=`，而 `cur/0/com.buaa.schedule/` 在只有静态 profile、还没做过运行期
         采样时**就是空的**）：
         - `/data/misc/profiles/ref/com.buaa.schedule/primary.prof` 存在，大小 **8,104 B**
           （包内 `assets/dexopt/baseline.prof` 是 8,133 B；落到 `ref/` 时差几十字节是正常的
           头/编码差异，别写成相等。⚠️ 这两个数都是**那一轮那枚包**的读数，构建输入一变
           通配符展开出的方法集合就变（T16c 门禁那侧 unsigned 构建量到 10,554 B），要重量）。
         - `dumpsys package dexopt` 里这个包那一行显示 `status=speed-profile`、`reason=bg-dexopt`。
           `bg-dexopt-job` 有它自己的排期，`adb root` 后用 `cmd jobscheduler run -f com.buaa.schedule 0`
           一类手段可以主动催，不必等它自己排上。
         为什么是 `ref/` 而不是 `cur/`：包内那份文本 profile 经 `mergeReleaseBaselineProfile` →
         `expandReleaseArtProfileWildcards` 编成二进制 `baseline.prof` 打进 APK，`ProfileInstaller`
         装包后把它落到平台的 `ref/` 目录，随后 `bg-dexopt-job` 按 `speed-profile` 编译一次；
         `cur/` 是 ART 运行期采样写的那一份，跟 Baseline Profile 不是一回事。
         API 26–30 上没有 `ProfileInstallerInitializer` 就没有落盘这一步，
         这也是为什么那枚依赖要显式钉住。
      5. **验收对照**（这步才是收益本身，用 `HomeStartupBenchmark` 现成的 None vs Partial）：
         `./gradlew :benchmark:connectedNonMinifiedReleaseAndroidTest`
         跑 `HomeStartupBenchmark#startupWithoutCompilation` 与 `#startupWithBaselineProfile`，
         比 `TotalTime` 的分布。**这组对照要单独跑**：插件没有对外暴露按类过滤的开关，
         但 producer 那侧自己就把不带 `includeInStartupProfile` 的启动基准跳掉了 ——
         生成日志（`:benchmark:connectedNonMinifiedReleaseAndroidTest` 那一段）打的是
         ```
         com.buaa.schedule.benchmark.HomeStartupBenchmark > startupWithBaselineProfile[buaa36(AVD) - 16] SKIPPED
         com.buaa.schedule.benchmark.HomeStartupBenchmark > startupWithoutCompilation[buaa36(AVD) - 16] SKIPPED
         ```
         一行没跑，所以先前那句"很可能把 `HomeStartupBenchmark`（5 轮 × 2 组）一起跑掉、多花十几分钟"
         不成立，也就不需要拿 `-Pandroid.testInstrumentationRunnerArguments.class=...` 去挡它。
         那条参数**仍然有用**，只是用途换成收窄：只想重跑某一个交互 CUJ、不想等整轮的时候用它
         （这一轮全量是 `BUILD SUCCESSFUL in 17m 29s`），例如
         `-Pandroid.testInstrumentationRunnerArguments.class=com.buaa.schedule.benchmark.InteractionBaselineProfileGenerator`。

      **这一轮的收益与代价（2026-09-19，buaa36 实测）**：接入 Baseline Profile 的**包体代价
      +2,270 B**（`classes.dex` 那一侧反而 −70 B；这个数是在**同一份构建输入**上 clean A/B 出来的，
      跨构建直接比 APK 总字节不可信）。**冷启动收益**在 release 包上量到 `am start -W` 中位
      **1,075 ms → 882 ms（约 −18%）**（改前 `verify` / 改后 `speed-profile`，各 15 轮）。
      ⚠️ 口径：这 −18% 是 **release + profile** 那一档的账，T18 的 −11.4%
      （`docs/PERF-STARTUP-2026-09-19.md` §8 ①）是 **debug 双版本**的账 —— ART 不对 debuggable 包做
      `speed-profile`，profile 那一笔进不了它的差值。两档各算各的，**不可相加**。

- [x] 今日课表长课名不再把状态胶囊挤出卡外（T48，2026-09-21，用户反馈「课程标题太长时排版出 bug」）：
      `DayView.CourseTimelineCard` 课名/胶囊同排互抢——`Row` 按顺序把剩余全宽先递给非加权的课名 Text，
      长名吃满整行后胶囊 maxWidth 归零、被摆到行宽之外，PANEL 底板的 clip 把它整枚裁没。
      课名收进 `weight(1f, fill = false)`（fill=false 保证短标题行逐像素不动），胶囊文字补锁
      `maxLines = 1` —— 长标题卡多出的那截竖向空隙真因就是没锁行数的胶囊在归零宽度下逐字竖排撑高。
      同族收口：管理页 `CourseGroupCard` 课名补 `maxLines = 1` + 省略号（列本有 weight(1f)）。
      守卫：`CourseTitleRowBudgetGuardTest` 纯 JVM 扫主源码钉死「同排课名 Text 必须带 weight/maxLines」
      （负向验证过，摘掉 weight 即红）。时间轴模式（`DayTimelineCourseList`）不在本卡范围。

- [x] 今日课表「时间轴」模式重做（T49，2026-09-21，用户反馈「时间轴显示模式简陋还丑」）：
      真机镜像实测（buaa36，周一 10:44、当天 5 节课）六条表现逐条对位——
      ① 左小时刻度列＋整点网格线（复用周视图 `HourLabels`/`NowLine`，提取进共享件
      `ui/home/TimelineAxis.kt`，窗口整点吸附是刻度对齐的前提）；
      ② 「现在」线接上 `TIMELINE_TICK_MS` 15 秒链（State 只喂线，块不跟着重组），
      正在上的块描边 1dp→2dp 换 error 色，几何当当前态第二通道；
      ③ 进模式首帧即滚到「现在」（effect 先于首绘不闪；全 past 落最后一节课头、
      全 future/空表落窗口顶，判据在 `dayTimelineAnchorMinute`）；
      ④ 课间 ≥20 分钟画虚线、≥30 分钟追加「课间 N 分钟」；
      ⑤ 色块收口只做课程色描边＋1dp 投影，不套 GlassSurface（小玻璃好看、大玻璃板丑）；
      ⑥ 模式选择落盘 `Personalization.day_timeline_mode`（全新键，不照抄
      week_grid_mode_declared 的一次性收敛——入口从第一天起就是带标签的分段控件）。
      判据内核全部进 `ui/home/DayTimelineAxis.kt`（零 android import，结构守卫钉着）；
      tint plate 对比度推导链原样保留（守卫同钉）。真机观感待编排者复核。
      守卫：`DayTimelineAxisTest` 16 条真单测（课间嵌套负例是负向验证逼出来的——
      第一版测试数据摘掉并块仍绿，换成内层块包住才算住机制）＋
      `DayTimelineStructureGuardTest` 5 条结构钉子（负向验证：注释掉 NowLine 接线即红）。
      门禁：**1007 单测 / 128 套件 / 2 跳过 / 0 失败**（+21/+2），
      lint **0 error / 14 warning**（与基线同一组）。

- [x] 日视图时间轴装机复核后的四处残余收口（T49b，2026-09-21，buaa36 复核暴露）：
      ① 刻度文字与整点线错开半小时——`HourLabels` 每格居中的约定在画了整点线的
      日视图露馅（实测周二：09:00 线 y=830、"09:00" 文字中心 y=903，逐枚落两线正中），
      周视图因不画整点线不受害。收口给共享件加 `HourLabelAnchor` 锚点档：
      默认 `SlotCenter`（周视图调用点不传参，那一屏逐像素不变，守卫单处调用
      +不带锚点双钉），日视图传 `LineTop`（文字顶边贴自己那枚整点线）。
      ② `dayTimelineAnchorMinute` 的「全 past」旧口径 `maxByOrNull{first}` 在并行课
      嵌套下把内层小块当最后一节课（A=[600,800]∋B=[610,620] 时 now=700 误判全 past、
      锚点跳 610）——并块抽成 `mergeDayTimelineBlocks` 与 `dayTimelineGaps` 共用一份口径，
      锚点取合并末段起点/终点。负向验证：两条嵌套单测在旧实现下实测红于 610≠700、610≠600。
      ③ 「现在」线 15 秒链此前无条件起链：翻到别的日子 NowLine 直接 return、块高亮走
      分钟级 now，屏幕每 15 秒白醒一次写没人读的 State。收进纯判据
      `nowLineNeedsLiveTick`（isToday 且 now 在窗口开区间内，与 NowLine 的
      fraction>0&&<1 同口径），effect 键带上开关、回到今天第一拍先发布不停旧红线；
      周视图链不动。④ 当前块 error 描边与现在线同色贴太近（实测周一 11:18：
      描边下沿 y=1673 vs 线 y=1660，差 13px、同 (186,26,26)，读成一条发虚加粗边）——
      描边退回课程色满浓度、几何 1→2dp 通道保留，红色收归现在线独享；
      不加线端圆点/时刻标签，那要改共享 NowLine、动的就是已验收的周视图。
      守卫：`DayTimelineAxisTest` +4 真单测（16→20），`DayTimelineStructureGuardTest`
      +3 结构钉子（5→8，负向验证：临时摘掉 LineTop 传参/链开关/描边换色三处机制实测全红）。
      门禁：**1030 单测 / 128 套件 / 2 跳过 / 0 失败**（+7，T49 后地板 1023 只涨不跌），
      lint **0 error / 14 warning**（与基线同一组，无新增）。

- [x] 学期统计页三张增密图：周次覆盖 Gantt / 负载趋势 / 空档分布（T51，2026-09-22，
      `983faf4`…`5cf17e5` 七枚）：判据全部落在三个纯 JVM 内核里——
      `CourseWeekSpans`（一门课实际在上的周次区间，连续周并段、断周不并）、
      `WeekFreeGrid`（某一周的「周几 × 节次」占用格，带 `dayRows` 转置视图与
      查不到节次时长的诚实计数）、`WeeklyLoadTrend`（逐周分钟数、峰值周、结课周标记）。
      界面侧 `ScheduleCharts.kt` 新增 `CourseWeekGantt` / `WeekFreeHeatGrid` /
      `WeeklyLoadTrendChart` 三张组件，`StatsScreen.kt` 按「趋势紧跟每周负载、覆盖紧跟
      课程学分、分布紧跟空档」的顺序各起一张同族 `GlassSurface(PANEL)` 卡，
      旧三张卡一字未动；三内核结果与 `SemesterSummary` 同一口径 `remember` memoize，
      全学期重算只在 courses/semester/timeSlots 真变时发生一次。
      取色走 `courseColor → legibleTintPlate → coursePlateSceneLuma` 那条 T23/T25b/T29
      真机校准过的链，不裸铺在玻璃板上；Gantt 逐行、热力格逐日挂 `contentDescription`
      （`ganttRowDescription` / `heatGridDayDescription` 就是为读屏写的）。
      每张图只有一个整块 `reveal` 缩放（与 `WeekDensityStrip` 同口径：不逐行挂动画），
      且读在 draw 阶段。门禁：**1100 单测 / 134 套件 / 0 失败**（+70/+6）。

- [x] 课次卡片进场动画 + 三处可打断收口（T52，2026-09-22，`d7eb4a1`…`65e5414`）：
      今日页列表 / 今日页时间轴 / 周课表网格三处的课程卡，第一次进入这一屏时
      淡入 + 8dp 上浮（`EntrancePlaybook` 进程内一把键只播一遍，三把常量键
      `day-list` / `day-timeline` / `week-grid`；键掺进日期或课程数就会「改一次数据
      重播一遍」，所以一律常量）。**一屏只有一条 `animateFloatAsState`**（380ms，
      只从 `MotionTokens.DURATION_LONG` 取），每张卡从这条驱动里切自己那一段窗口
      （`entranceSlotProgress`，窗口 0.45）——末格恰在驱动收尾时落定，
      「总时长封顶」是构造性质而不是算出来的，条目再多只压步长。
      周网格的名次不是渲染循环下标（列内课程是 DAO 顺序不是节次顺序），
      改为对既有聚合表一次 `remember` 建「天→节次」名次表、逐卡一次哈希查找。
      进场变换在 CourseCell 里**并进既有那张按压/脉冲缩放层**（两张图层各持一份 alpha
      就没法合账），今日页两处自持一层挂在 `animateItem` 之前；落定后恒为
      alpha=1 / translationY=0，静止像素与改前逐位一致（装机复核过）。
      同批收口：列表↔时间轴从 `Crossfade` 换成带 1/20 屏纵向位移的 `AnimatedContent`；
      「现在」线的一分钟一跳补成一段滑行；`pendingSyncTarget` 让「外部改周」被惯性
      滑动打断时不再整个丢掉（此前顶栏写第 20 周、屏幕停在第 12 周）；
      脉冲撤销从 `snapTo(0)` 改成从当前值接着淡出。
      守卫：`EntrancePlaybookTest` 11 条真单测（含「总长封顶」那条可执行定义）＋
      `CourseEntranceWiringGuardTest` 8 条结构钉子（三处接线与常量键）。
      ⚠️ 记账一条方法学：**buaa36 的录帧包络量不出 260ms 级动画**——它平均每
      250~400ms 才出一帧，一段横向滑行与一次硬切都只落一根尖峰。所以「切日 / 底栏切页
      是硬切」这个旧结论已作废（代码里 `dayAxisTransition`、NavHost 的
      enter/exit/popEnter 都在），进场动画能测出来只因为它是 380ms 且发生在冷启动
      最慢的那一段。帧级验收要么走真机，要么走代码 + 结构守卫。

- [x] 掉帧自动降档整条死链修好（T53，2026-09-22，`6d7dbf3`…`af2e7e0`）：
      装机实测 `GlassJankMonitor` 从未收到过一次帧回调（同一进程 `GlassDiag` 1197 行、
      `GlassJank` 0 行、`ps -T` 里没有 FrameMetrics 线程），于是 release 包里
      「持续掉帧→自动降玻璃档」这道运行时自保护从未跑起来过。根因两处：
      `attach()` 落在 `MainActivity.onCreate` 开头（`setContent` 之前），
      而 `addOnFrameMetricsAvailableListener` 传 null Handler 在现在的平台链上
      （`View.addFrameMetricsListener → FrameMetricsObserver → HardwareRendererObserver`）
      直接抛 NPE——老的「null 就自起 FrameMetrics HandlerThread」兜底已被删；
      外面那层 `runCatching` 把异常吞得看不见。修法：注册延到 `decorView.post`
      （那一刻 `mAttachInfo` 就位、ThreadedRenderer 已建，两条死路都不再可走）、
      进程内懒建一个 `HandlerThread("FrameMetrics")` 复用、失败打 `Log.e` 留痕
      （release 也要看得见），`registered` 标志让 detach 只对真注册过的监听发 remove。
      降档语义一字未动（连续两个坏窗口、10 分钟冷却、只降不升、压到 OFF 才停采样），
      判据本体剥进 `GlassJankDecision.kt`（零 android import，帧数/坏帧数/时间戳当参数）。
      **装机复验通过**：`logcat -s GlassJank` 出「FrameMetrics 监听已注册」+
      `frames=19 jank=18 jankRate=94.7`，`ps -T` 出现 FrameMetrics 线程。
      守卫：`GlassJankDecisionTest` 表驱动 7 条 + `GlassJankMonitorWiringGuardTest` 3 条。
      ⚠️ 顺带量到一条：模拟器 10 秒窗口只出 19 帧、其中 18 帧超 32ms——
      `RELEASE_MIN_FRAMES = 100` 那道"帧数太少不判"的闸正好挡住了误降档，
      这条保护在模拟器上被真实触发过一次。

- [x] 周次覆盖 Gantt 八行压成一行（T54，2026-09-22，`5b49614`，装机复核暴露）：
      `CourseWeekGantt` 里背景格线那个 `Row` 与 `visibleRows.forEach { Row(...) }`
      是同一个 `Box` 的两个子项，Box 默认把所有子项叠在 top-start，于是八行课程名
      互相压字、八条轨道叠成一行，而格线高度按八行算——卡片下方一大片空白。
      行层收进 `Column`（两列权重、`GanttRowPitch`、semantics、reveal 一字未动）。
      顺手给「空档分布」表头补了轴标注「列 = 节次（数字是第几节）· 行 = 周几」——
      原来 `第 4周 1 2 3 … 14` 读起来像 1..14 是周次。另两张图逐行核过，
      同族形状（Box 里裸 `forEach`）只有这一处，守卫泛扫三张图钉住这一类。
      守卫：`StatsChartsStructureGuardTest` +4 条（行层必须在 Column 且与格线同 Box、
      三层权重各 3 处 + 格线高=pitch×行数、三图泛扫、轴标注零命中即失败）。
      门禁：**1144 单测 / 140 套件 / 0 失败**（当时那 2 枚 skipped 被记成
      「没有 release APK 的工作区里的既有产物层跳过」——**这个口径下一节被证伪**，见 T57），
      lint **0 error / 14 warning**（与基线同一组，无新增）。

- [x] 「现在」线收进课程卡之下（T55，2026-09-22，`a98613b`+`40fdf90`，装机复核暴露）：
      时间轴与周课表两处的 `NowLine` 都是那个 `Box` 的**最后一个子项**，排在课程块/七天列之后，
      于是 2dp 红线从卡名中间横过去——装机实测（buaa36，周一 17:41）时间轴把「思想政治」
      四个字划了一道，周课表第 10 节三张卡各被划一道。Box 子项按声明顺序叠放，
      所以修法就是把线的声明挪到块/列之前（`DayView.kt:769`、`WeekView.kt:744`）：
      卡片盖住线、文字一处不碰，而课间与空列照旧把线整段露出来，「现在」的信号留在空档处。
      没做区间镂空——玻璃卡画的是 `LocalSharedCourseBackdrop` 烘焙的整屏前缀，线在卡下不透出来，
      降级档板 alpha=0.92 也只透 8%，镂空是为一个看不到的东西加一层账。取色链与动画规格不动。
      守卫：`NowLineUnderCardsGuardTest` 4 条（两视图各钉「线在课程块之前、且在整点线之后」、
      调用点各恰一处、旧注释「线在最后画」不许再出现）。本模块没有 Robolectric，
      只能钉声明顺序这一层，真正的像素结论交装机。
      ⚠️ 留两笔同类残账：`ScheduleCharts.kt:357` 学期统计的今日时段带也是「线最后画」，
      但那里压的是 8dp 色条、没有文字，观感影响小；今日页时间轴的**课间文字标签**
      （「课间 115 分钟」）仍可能被线横穿（线在课间层之上）——收它要牺牲空档处的可见性，是设计口径不是 bug。

- [x] 浏览周次时顶栏第二行的日期跟着那一周（T56，2026-09-22，`c788b78`+`1e8f04e`）：
      第一行 `weekHeadline` 跟着 `browseWeek` 走，第二行 `todayLabel` 却永远写今天，
      装机实测「第3周（浏览）」下面挂着「9月21日 星期一」，而 9/21 正是第 4 周的周一
      （网格表头自己就写着 9/14–9/20）——两行自相矛盾，用户读成 app 算错了周。
      周→日期这条算式在仓里原本各写一份（周课表表头内联、桌面组件 `weekRange`、组件跳周链），
      收成一份 `domain/schedule/SemesterWeekDates.kt`（锚点归一仍只走 `WeekCalculator.mondayOf`，
      不新增第二套锚点算法），`WidgetCommon.weekRange` 改为一行委托，于是顶栏与组件副标题
      报的必定是同一段日期；`week < 1` 从「凭空算出开学前的日期」变成返回 null 走调用方兜底。
      派生逻辑剥进零 android import 的 `ui/home/TopBarDateLabel.kt`：跟随模式（`browseWeek` 为 null
      或翻回当前周）仍报今天、格式与 locale 逐字符不变，学期读不到时也退回今天不猜。
      `remember` 的键列全四个入参——漏 `browseWeek` 就是翻周日期停在今天（本卡修的正是它），
      漏学期/今天就是换学期、跨午夜仍显示旧日期（T41/T43 同类坑）。
      ⚠️ `WeekView.kt:237` 表头那份内联算式**尚未收口**（本卡文件边界所限），
      「全仓一份」目前是两处已收、一处未收。
      守卫：`SemesterWeekDatesTest` 5 + `TopBarDateLabelTest` 5（跨年学期、开学日在周中、
      `browseWeek` 为 null/0/越上界、当前周恰为第 1 周）+ `TopBarDateWiringGuardTest` 4。

- [x] `jankRate=` 泄漏进 release dex，产物层反向对照复活（T57，2026-09-22，`b568e37`）：
      T55/T56 合进 master 后整树门禁 **1162 条里真红 1 条**：
      `ReleaseForensicLogSurvivalTest.minifiedDexStillCarriesEveryForensicLine`。
      该文件的 `DEBUG_ONLY_FRAGMENTS = ["GlassDiag", "jankRate="]` 是**反向对照**——
      这两串在 release 产物里必须查无此文，它们「不在」才证明读到的是真被 R8 折叠过
      `BuildConfig.DEBUG` 分支的 minified 产物，同一文件里那些「取证行还活着」的断言才有证明力。
      根因是 T53 的副作用：那条日志原先长在 `if (BuildConfig.DEBUG)` 里，连分支带字符串一起被删；
      判据剥成纯函数后分支取决于**运行时枚举值**，R8 折叠不动，字符串就留在常量池里。
      运行时行为本来就是对的（内核只在 `debug == true` 时返回 `DebugReport`，release 永远走不到），
      纯粹是产物里多带一具走不到的身体。修法：调用点用**字面量** `BuildConfig.DEBUG` 再挡一道
      （局部 `val debug` 折叠不掉，注释里点明），判据内核一个字不动，
      **不许**靠放宽 `DEBUG_ONLY_FRAGMENTS` 变绿。
      直接证据（对 `classes*.dex` 按字节搜）：`jankRate=` 有→无、`GlassDiag` 两边均无、
      同支的 `frames=` 与 ` jank=` 一并折掉。
      ⚠️ **方法学订正（本卡真正的产出）**：这条红之所以每一轮都没报，是因为第 3 层断言
      「没有 release 产物就 `assumeTrue` 跳过」，而 worktree 里从来没有产物——
      之前把「2 skipped」记成"环境性、不是代码问题"是**错的**，那两枚 skip 正好盖住了它。
      门禁顺序从此钉死：`:app:assembleRelease` **必须排在** `:app:testDebugUnitTest` 前面，
      收单时看 skipped 计数而不是只看 failures。worktree 没有签名口令时产出的是
      `app-release-unsigned.apk`，测试按 `*.apk` 通配取第一个，照样吃得到，别以为构建坏了。
      门禁（顺序合规）：**1162 单测 / 144 套件 / 0 失败 / 0 skipped**，lint **0 error / 14 warning**。

- [x] 学分显示补全 + 体育课显示体育项目（T58，2026-09-22，`e454f08`…`747d1ad`）：
      按 `docs/BUAA_API.md` 的要求做的，但**根因不在接口**：学分从第一批解析起就一路进库、
      进备份、进编辑器输入框，缺的只是**没有任何一处界面把它画出来**——所以这一卡是显示缺口，
      数据侧一行未动。体育项目则相反：教务 `getMyScheduleDetail` **没有这个字段**，
      项目名只长在课程名里（`体育(田径)`），只能从名字剥。两件事共用同一批显示文件，
      按「同文件必同卡」合成一张。
      判据剥进零 android import 的 `domain/model/CourseMetaFormat.kt`：
      `formatCredit` 走 `CourseConstraints.normalizeCredit`（越界/NaN/Infinity 返回 null，**不夹取**），
      小数按 ×100 取整手拼，**不许**用 `DecimalFormat`/`NumberFormat`——那两个跟 locale 走，
      某些区域会把 `2.5` 打成 `2,5`；`creditLabel` 只在有值时给出 `X学分`。
      `peProjectOf` 认「前缀是体育 + 首个括号内有 ≤12 字且含字母数字」，全角半角括号与空格先归一。
      接线三处：详情 Sheet 加「学分」「体育项目」两行（行序 教师→地点→校区→学分→体育项目→备注）、
      管理页课程组摘要、日视图列表卡片 `dayCourseMetaLine`（节次·教师·学分）。
      两条容易写错的口径钉死：学分 `null` 时**整行缺席**、不许落进 `DetailRow` 的「未设置」占位
      （那是给教师/地点准备的，`0.0` 学分仍要老实画「0学分」）；体育项目一律读 `course.name`
      而非 `displayName`，别名一盖项目就丢。
      **时间轴色块不加第四行**：38/58dp 两档是按 1.05dp/分钟标定的既有口径，58 档三行
      （标题/时间/教室）没有可证的余量，本模块没有能量文本布局的测试——加行就是在重演
      T54「挤出去的第三行把课程名顶没」。
      守卫：`CourseMetaFormatTest` 5 条表驱动 + `CourseMetaWiringGuardTest` 4 条
      （扫源码文本钉住三处接线真的存在、Sheet 行序、`DetailRow("学分"` 只许一次、
      `peProjectOf` 实参只许 `course.name`、全仓禁现 `DecimalFormat`/`NumberFormat`）。
      ⚠️ 第一版守卫红在自己人手上：禁现扫描把 `CourseMetaFormat` KDoc 里那句
      「不许用 DecimalFormat」本身当成了命中，`747d1ad` 改成先 `blankComments` 再匹配。
      装机验收（buaa36，22 门种子课）：详情 Sheet 两态（`体育(田径)`→「1学分」+「田径」；
      `体育(体能测试)`→学分行整行缺席）、日视图列表卡「第1-2节 · 李娜 · 2学分」、
      管理页「张强 · 周五 5 · 1 段」缺学分时无悬挂分隔符；临时把大学物理改成 2.5 学分复测
      小数渲染（「2.5学分」）后**已改回 4**。时间轴模式复核仍是三行。
      门禁（顺序合规）：**1171 单测 / 146 套件 / 0 失败 / 0 skipped**，lint **0 error / 14 warning**。
- [x] 扫码「没反应」第二轮：两条只关不开的开关 + 三条裸静默支路（T59 + T59b，2026-09-22，
      `52acd9c`…`6094708`）：**用户第二次报同一条 bug，这一次查到底层不是这一页的代码**。
      **先说真账**（dex 字符串取证，2026-09-22 01:53 从那台 Redmi 上 `adb pull` 下来的
      `base.apk`（6,436,048 B，sha256 `ccd0cd31…`）vs 同日 08:42 从干净 master 产的
      `app-release.apk`）：
      | 标记串 | 手机上那份 | 最新那份 |
      |---|---|---|
      | `手输` | **3 处** | 0 |
      | `签到码` | 7 | 1 |
      | `相机服务没把摄像头交给这一页`（T44 那一档） | **0** | 1 |
      | `相册识别` | 1 | 7 |
      ⇒ 手机上跑的是 **T44（`e29bd87`，09-21 11:18）之前**的构建，那颗 `consumed` 布尔死闸
      还在（扫过一次之后每一帧在 `analyze` 入口被丢掉、预览照旧活着 = 字面意义的"没反应"）。
      **第二层**才是机制：`gradle.properties` 的 `VERSION_NAME` 自 09-16 起一直是 `0.1.1`、
      `VERSION_CODE=3`，而 `UpdateCheck` 按 `compareVersions(versionName)` 比 —— 那 35 枚提交
      没进过任何发布渠道，**装机那份永远收不到"有更新"**。所以"还是没反应"不是回归，是分发断了。
      **本轮仓库侧收口**（判据全在新增的零 import 内核 `ui/signin/ScanRecoveryPolicy.kt`）：
      ① `scannerWorking` 与 T44 拆掉的 `consumed` 同型：ML Kit 的 `onFailure` 与 `process()`
      同步抛两条出口都只写 `false`，**全仓没有任何一处写回 `true`** ⇒ 一帧瞬时失败就把相机
      判死到整页结束，还对用户说"这台设备用不了相机扫码"。现在是按帧推进的健康度
      （`ScanDecoderHealth`，整枚换引用），三道界都在内核里：连错 `ConsecutiveDecodeFailureLimit=3`
      帧才停用、停用窗口 `20/40/60` 帧随轮数线性变长、自动试回满 `MaxDecodeSuspensionCycles=3`
      轮才判死，外加"回到前台再给一次机会"额度 `MaxPageVisibleRecoveries=2`。
      **用完就是用完**，那一档界面继续显示既有降级文案、不假装还有救。
      ⚠️ 一条要紧取舍写死在注释里：停用期间**故意不 `unbindAll`** —— 帧必须继续到达，
      "窗口过完没有"这件事的唯一观测量就是到达的帧数；掐了帧流就等于把这一档唯一的自动活路
      也掐掉。因此 `analyze()` 里 `val frame = ++frameCount` 必须排在停用判断**之前**（守卫钉着）。
      ② 绑定失败过去是"这一页到此为止"：`cameraError` 不是那颗 effect 的键，抛一次之后再没
      有任何东西会重跑绑定。现在有界重试（`MaxCameraBindAttempts=3`、退避 `400ms/800ms`），
      且 `classifyCameraBindFailure` 按失败原因**原文**分两支 —— "这台设备没有后置摄像头"
      那一档 `NoBackCamera` **一次都不许多试**（重试治不好它）；`runCatching` 吞掉的
      `CancellationException` 原样抛出（不然换页之后还会写 `cameraError`）。绑成功要把
      `cameraError` 收回 null，否则文案永远停在已经不成立的那一句。
      ③ 三条裸静默支路留痕：相册选图被取消（`uri == null`，以前连一行都没有）、
      相册识别被解码器状态挡住（以前 `if (a && b && c)` 捏在一起，按钮 `enabled` 只看
      `scanner`，所以"判定没到手"那一档**点得动、点下去什么都没有** ⇒ 拆出来走
      `reportGalleryBlocked()`，话与"那张图里没认出二维码"分开）、解出条码却读不出原文
      （以前 `?.let {}` 吞掉，与"这一帧什么都没看见"在证据上完全同形 ⇒ 只数不弹，
      `shouldLogValuelessBarcode` 第一次必说、之后每 50 次一次）。
      另外把三颗会吞动作的按钮（相册识别 / 重新扫码 / 继续扫码）从 `!busy` 换成
      `enabled = !inFlight`，`inFlight` 从裸 `Boolean` 换成 ViewModel 的单一 `StateFlow`：
      吞动作是状态机的事，**让用户看见"现在点不动"**才是修"按了没反应"的那一半。
      ④ 入口接线：`SettingsScreen.onOpenSpocSignIn` 的 `= {}` **默认值摘掉**（T41 那条
      「学期统计」静默 no-op 就是这么漏出来的），新增 `SpocSignInEntryWiringGuardTest` 6 条
      钉住路由注册、两处调用点都真传回调、参数不许再有静默默认值，以及「手输 / 输入签到码 /
      手动输入」连同 `TextField(`/`OutlinedTextField(`/`BasicTextField(`/`TextFieldValue`
      不许再回到这一页（先 `blankComments` 再匹配 —— T58 红在自己 KDoc 上的教训直接复用）。
      ⑤ 取证钩子：每次绑定一行「本轮绑定的首帧已到达分析器：第 N 帧」，把「相机没送帧」
      与「送帧了但解不出/被判停用」分开（没有这一行这两种处境读证据时长得一模一样）。
      **T59b 是主线程复核抓出来的两处"说得出、做不到"**：
      ① `firstFrameLogged` 从不复位，而 analyzer 实例的存活期比一次绑定长得多
      （`remember(scanner, bindGeneration)`；`scannerWorking` 翻回 true 那条自动恢复路径会
      重跑绑定却不换实例）⇒ 第二次绑定起再也没有首帧行，⑤ 想买的那份区分恰恰买不到。
      复位点收在 `markBindStarted`（绑定成功必调一次 = 天然的每绑定钩子），
      `frameCount` **不跟着清**（那是内核算窗口的时间轴）。
      ② `scannerWorking == false` 有两种病因却共用一句「这台设备用不了相机扫码」——
      停用窗口（最多 20/40/60 帧、正在自己试回来）被说成设备事实，正是本卡要拆的那类假话的
      最后一处。内核加 `scannerGiveUp(health)`，`scanUiStatus` 加同名参数把那一档分成
      建不出来 / 判死 / 暂时三档（暂时那句只说"正在自动重试"，**不承诺时间、不指使去设置**；
      判死那档字面量一字不动，两处守卫按它扫），`scanCameraLive` 的"活不活"那一乘项从
      `scannerWorking` 换成 `!scannerGiveUp` ⇒ 停用窗口里取景框不再陪闪 1~3 秒。
      判据单独用 `onGiveUpChanged` push 进组合，不能"组合期读 health 快照"：判死发生时
      `scannerWorking` 早已停在 false 不再翻面，不 push 就没人知道换挡了。提示档位 7 → **8 档**。
      守卫：`ScanRecoveryPolicyTest` 14 条（三档界限 + 同帧二次报错不重复计 +
      暂时/判死对照表）、`ScanSilentBranchGuardTest` 9 条（按源码形状钉：置位次序、
      复位点、三处支路、`inFlight` 单一来源、内核文件 `import` 行为 0）、
      `SpocSignInEntryWiringGuardTest` 6 条。
      门禁（顺序合规：`assembleRelease` 先行并真产出 `app-release-unsigned.apk`，
      产物层那三行没跳过）：**1200 单测 / 149 套件 / 0 失败 / 0 skipped**，lint **0 error / 14 warning**
      （基线 T58 是 1171/146）。
      ⚠️ **仍未装机验证，且这一页在现有两台设备上开不出来**（别把"合了"读成"验了"）：
      门只有一道 —— 首页「扫码签到」走 `SpocSession.hasSession()`，AVD 上 SPOC 会话是空的
      ⇒ 那一下被送进 `spoc_login` 而到不了扫码页；token 由 `KeystoreBlobStore` 封存，
      **外部播种不进去**，也没有 scheme 深链可绕（intent-filter 从未开）。
      （本卡一度把这归结为"这台镜像没有后置摄像头"，**量错了改回来**：`dumpsys media.camera`
      数到 `Number of camera devices: 1` / `Device 10 … Facing: Back`，起手那三行
      `CameraValidator$CameraIdListIncorrectException: Expected camera missing from device`
      是 CameraX 在模拟器上的已知 quirk，它自己 `Retry init`；这一页 09-18 起就是在这台 AVD 上
      跑通过 80 秒实时取景的。⇒ 摄像头这条**不是**阻塞项，别再去修它。）
      二维码喂不进虚拟场景那条既有结论不变（见 [[buaa-emulator-testing]]）。
      所以 T44/T45/T59 三代扫码修复**至今没有一次真机回归**，欠的观测仍然是那三件
      （无效码→重新扫码→换一张要有反应 / `pm revoke` 后放行要翻面 / 造一次 provider 拿不到）。
      ⚠️ 记一笔新发现的**残余债务**（不属本卡范围，下轮单独拍）：判死之后相机**仍然绑着**、
      帧照收照丢，这一页剩下的时间里 sensor 一直在转 —— 想省电就得 `unbindAll`，但预览会
      整个黑掉，那是 UX 决策不是机制决策，别顺手带进别的卡。
      顺带订正本页三处过期口径：`[x] 智学北航（SPOC）扫码签到` 那两条还在教"相册 + 手输两条兜底"
      （T45 已整条删掉手输）、T45 那条的"六档降级文案"实为**七档**（少算了 T44 新加的
      provider 那一档）、T44 那条的 `ScanUiStatusTest`（8 条）现已 10 条。
      `docs/BUAA_SPOC_SIGNIN_PLAN.md` §计划 里另有三条与代码不符（intent-filter、
      "扫码成功即 close 分析流"、手输兜底），已在同批改注。
- [x] 节假日标注·数据链路（T60，2026-09-22，`c510214`…`addf3a2`）：用户「节假日没有标注出来」
      有**两层**，这一卡只管第一层。真账（读代码读出来的，不是猜的）：
      ① 全应用只有**一个**触发点 —— `HomeScreen` 那个 `LaunchedEffect(Unit)`，而它跑在
      Cookie 恢复**之前**，于是第一次冷启动必然抓不到；② `BuaaWebSession.fetchTeachingSchedule`
      在同进程没有教务 WebView 时直接 `return null`，**一条日志都不留** —— 用户那边是"从没标注"，
      我们这边是"什么都没说过"；③ 该不该重抓的判据埋在 `SpecialDayCache.staleMonths`（读文件系统那一层），
      JVM 单测完全碰不到；④ 待抓月份写死"本月 + 下月"，**翻到 10 月永远不会去抓 10 月**。
      收法沿用本仓那条收单硬判据：新增零 import 内核 `ui/SpecialDayRefreshPolicy.kt`
      （`decideSpecialDayFetch` 答"抓不抓 / 抓哪几个月 / 停在哪一档"，四档 skip 与三档 trigger
      都在这里，连日志文案 `specialDaySkipLog` 也是纯函数），调用点只递事实
      （月龄、屏幕上看得见的月份、有没有会话、有没有人在飞）。
      四个动作：`staleMonths` 删掉换成 `cachedMonthAges`（只报"哪个月有文件、它多少天大"，
      mtime 在未来夹 0 —— 负数在判据那头与"刚抓的"同形，会让那一整月永远不再补抓）；
      `BuaaWebSession` 加**单边沿**的 `sessionRetained`（`tryEmit` 收在 `retain()` 最后一行，
      刻意不做电平：`hasSession()` 仍是唯一真相）⇒ 登录成功后自动补一趟 `SessionReady`；
      `HomeScreen` 把屏幕上那几个月（浏览周的周一~周日 + 正在看的这一天）喂给
      `onSpecialDayVisibleMonths` ⇒ `BrowseMonth` 那一档；单飞闸门 `compareAndSet` 在起协程**之前**
      领，`finally` 只在 `claimed` 时放 ⇒ 一趟没完不再叠第二趟，也不会把别人还在跑的标志误清。
      每一档停法一行 `Log.i(SpecialDays, …)`（Info 级：HyperOS 把 logcat 截在 Info、
      release 剥掉 verbose，这条约定见 `buaa-device-and-publish`）。
      顺手订正一处我自己在卡面上写错的前提：`LaunchedEffect(Unit)` **不是**每进程一次 ——
      NavHost 每次导航回首页都会重组它，所以它现在是 `PageResume` 那一档，不是"起手一次"。
      门禁（`assembleRelease` 先行）：**1218 单测 / 151 套件 / 0 失败 / 0 skipped**，
      lint 0 error / 14 warning（基线 T59b 是 1200/149；新增 12 条表驱动 + 6 条接线守卫）。
      装机取证（buaa36，`logcat -s SpecialDays`）两档真话都到位：
      「上一次补抓还在进行中 触发=浏览到新月份」/「无可用教务会话 触发=回首页 待补月份=2026-09/2026-10」
      —— 后者正是这台 AVD 的处境（没登录教务，抓取这一档在模拟器上永远走不到，
      跨月与新鲜判据只有单测撑腰）。⚠️ **数据到位之后能不能看见，不属本卡**：全应用只有
      `WeekView` 日头那一格在消费 `specialDays`，今日页/顶栏/组件一律不认它 —— 那是 T62。

- [x] 时间轴文字密度（T61 + T61b，2026-09-22，`0ab08be`…`d5ed279`）：「时间轴模式显示的文字内容
      有点少」**不是观感问题，是算式问题**：`DesignTokens.dayHeightPerMinute` 是 1.05dp，
      一节 45 分钟的课只有 47.25dp，而"教室内那一行"门口诀写着 58dp ⇒ **单节课永远没有教室行**，
      教师、备注同理。原来那三道 `if (blockHeightDp > 38/58…)` 是散在 `DayView` 里的魔法数，
      每道的判据还各不相同。
      收法：新增零 import 内核 `ui/home/DayTimelineBlockLines.kt` —— 一张"这个块装得下哪几行"的
      表（`DayTimelineBlockLine` 带 `pinned` 位：课名必留，其余按序扣容量；**第一次扣不动就置
      `starved`，后面更短的行不许插队**，否则四行的出现顺序会随节次长度乱跳）；`DayView` 补上
      第 2 行的教师（`08:00–09:35 · 第1-2节 · 李娜`）、第 3 行的教室（空则老实写「教室未定」，
      不静默删行）、第 4 行的备注；`dayHeightPerMinute` 1.05 → **1.40**。
      **T61 我收下之后又打回自己一次**（这一笔值得留着）：① 预算吃的是 `TextStyle.lineHeight`
      的名义值，而排版真正吐出来的行盒更高 —— 实测 `labelLarge` 19.81dp（名义 20）、
      `labelMedium` 17.14dp（名义 16）；`lineHeight` 是**下限，不是行盒**。结果 45 分钟块的
      真余量只剩 0.38dp，而没有任何东西裁它 ⇒ 换 MiSans 那多出来的一行会直接画到板外面。
      ② 没有第二道裁切。③ 卡面上我给的 `availableWidthDp` 是个没人量的猜测入参，还换来第 15 条
      lint warning（`ConfigurationScreenWidthHeight`）。
      T61b 三件一起收：行高改由 `TextMeasurer` **实测**（`getLineBottom(0) - getLineTop(0)` 向上
      取整再转 dp，采样字符必须是 CJK ——「课」，拉丁字母量不出中文字体的行盒；记档 key 要带上
      `fontScale` 与 measurer），`.clipToBounds()` 收在竖直 padding **之后**（早于 padding 等于没裁），
      宽度参数整个删掉。⚠️ 本项目的 ui-text 那份**没有** `rememberTextMeasurer` 可用，
      得自己 `TextMeasurer(LocalFontFamilyResolver, LocalDensity, LocalLayoutDirection)`。
      装机（buaa36，9/22 那一块）：三行齐 ——「大学英语读写译(1)」「08:00–09:35 · 第1-2节 · 李娜」
      「外天楼305」；把系统字号拉到 2.0 复测，行是**往下掉**而不是往外溢（末行底 2017px < 块底 2077px），
      复量完已把 `font_scale` 放回 1.0。⚠️ 录帧包络量不出 260ms 级动画那条既有结论不变。
      门禁（顺序合规）：**1233 单测 / 153 套件 / 0 失败 / 0 skipped**，lint 0 error / 14 warning
      （新增 5 条内核表驱动 + 9 条源码形状守卫；守卫钉住"行集必须走内核、38/58 那两枚魔法数不许回来"、
      `1.35.dp` 那笔中间账不许被滚回来、内核里不许出现宽度入参）。
      有意未做：「现在」线那一格的时间胶囊还归共享的 `NowLine` / `NowGlideLine` 管，
      两处守卫钉着它们，不为这一卡去动。
- [x] 节假日标注·渲染面（T62，2026-09-22，`9a927e1`…`078f1aa`）：同一句投诉的第二层。
      真账不是"没接上"而是"只接上了一处、且那一处只有一个字"：`specialDays` 全应用只有
      `WeekView` 日头那一格在消费（窄屏一格 ~43dp，画的是「休」/「班」一枚字），
      **`SpecialDay.note`（节假日到底叫什么）零处显示** —— 抓完数据用户也只是多看见一个单字。
      今日页（用户每天看的那一页）完全不认这份数据。
      三档收法，两份判据都是零 import：
      ① `ui/SpecialDayBadgePolicy.kt` 答"这一天挂不挂、挂哪一枚"—— 周末**不**因为"是周末"自动得
      「休」（七格常年挂两枚只是噪音，同组件侧"周末不标无课"一条口径）；同日期重复取第一条带字的
      note；既休又班时看那天本来是不是周末 —— **周末判「班」、工作日判「休」**，因为挂错两边的后果
      不对称：把真要上课的周六标成「休」会让人缺席一次真实存在的课，反过来只是白欢喜。
      ② `ui/home/SpecialDayBadge.kt` 是「休/班」的**唯一渲染口**（字、色 error/primary、字距 2dp
      收在一份里，此前散在表头那 16 行），`SpecialDay.badgeOf` 那第四份答案删掉。
      ③ `ui/home/SpecialDaySurface.kt` 答"页头这一行摆哪一档"（整句 / 只留本体 / 一个字节都不许多），
      宽度全部是调用点 `TextMeasurer` 实测进来的 Double（T61b 同一手法），摆法上徽标拿自然宽、
      前导周次文字 `weight(1f, fill = false)` 吃剩下的并 ellipsis —— **被省的永远是说明不是本体**。
      被版面藏起来的全称走 `semantics.contentDescription`（「国庆节（节假日）」，休/班两种说法分得开）：
      那一格摆不下七个字，但读屏用户不该永远不知道那个「休」是谁。
      装机实测（buaa36，`adb install -r` 后注入 `files/special_days/2026-09.json` 三份标注，
      **验完已删干净**）：9/25 页头「第 4 周 休· 中秋节」（红），整对文字 419–661 在两个箭头之间
      **居中**（中心 540 = 第一行日期的中心）；把说明换成 24 字长句后掉到只留本体那一档，
      `uiautomator dump` 里那一格的 `content-desc` 实测拿到整句 ⇒ 全称确实只是没画出来、没丢；
      9/22（无标注那天）副行仍是**单个** Text 节点「今天 · 第 4 周」，与改前逐字节同形、没有占位；
      周表头 9/25 休 / 9/26 休 / 9/27 班（周日那枚「班」正是"周末判班"那一档的真数据）。
      顺带第一次在真机上观测到 T60 的 CacheFresh 档：注入新鲜缓存后 `logcat -s SpecialDays` 的
      待补月份从 `2026-09/2026-10` 缩成 `2026-10`。
      门禁（`assembleRelease` 先行、真产出 6,447,295 B 的 unsigned 包）：
      **1258 单测 / 156 套件 / 0 失败 / 0 skipped**，lint 0 error / 14 warning（地板 1233/153）。
      ⚠️ 已知残账，**不是本卡引入**：`TeachingScheduleParser` 按"文案里含 调休/上班/补班"判上班日，
      所以一条名字里带「调休」二字的**放假**条目会被判成「班」（我这轮拿长句试标注时就撞了一次）。
      真数据里没这种名字，但这条判据是文案驱动的，别把它当日期事实。
      有意未做：**桌面组件**（周网格按星期几摆、一格根本没有日期，要标注得先给组件侧引入
      "当前浏览周每一天"的口径 + 快照新键 + RemoteViews 布局 —— 另立一卡，守卫里钉了一条
      `widgetSideIsDeliberatelyNotWiredYet` 防"半接"）；**顶栏那两行**（与日头重复，
      并钉了反向断言不许偷偷接回）。
      ⚠️ 调度事故也记一笔：上一支 T62 子代理撞 150 轮上限**之后仍然活着**，把已废弃的顶栏版本
      又写回 worktree 两轮（15:03 与 15:11–15:14，六个文件、未提交），我第一遍门禁因此被污染、
      作废重跑。处置：`git checkout -- .` 复原到 HEAD，那份写回留在 `.tmp/T62/ghost-final.patch`
      （不入库）。**派下一支卡之前先确认上一支真的退了。**
- [x] 扫码取帧密度与 3A（T64，2026-09-22，`16eb262`…`d2f479a`）：「码小不会自动放大」的第一层
      不在解码器，在**送进解码器的那幅画有多大**。此前 `ImageAnalysis` 一颗 `ResolutionSelector`
      都没声明 ⇒ 按 CameraX 文档落在 640×480（0.31MP），而 ML Kit 的门槛是"最小可辨识单元 ≥2px"、
      同行实测要到 **≥3px/模块** 才爬到 0.9 识别率 —— 模块数在降采样那一刻就没了，
      后面换任何引擎、加任何重试都救不回来。这一半根因从 T44 起一直没人动过。
      收了五件：① 分析流请求 1280×720 + `FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER`；
      ② **每绑定一条交付尺寸取证行**（"请求了 720p"与"拿到了 720p"必须能在 logcat 里分开，
      措辞与"够不够"的判定全出自内核 `analysisFrameLogText`，调用点不拼字符串数学），
      且它排在停用判断**之前** —— 停用窗口里的帧也是帧，不能变成盲区；
      ③ 点按对焦：`SurfaceOrientedMeteringPointFactory` 显式用**分析流**构造（两参构造默认按活跃
      Preview 的画幅换算，两者画幅可以不同），FILL_CENTER 那次"放大 + 居中裁切"的逆变换收在
      `analysisMeteringPointForTap`，落在裁切带外的点击**拒映射**而不是夹到边缘代答，
      `FLAG_AF` + `setAutoCancelDuration(3s)`，`isFocusMeteringSupported` 排在 `startFocusAndMetering`
      之前；④ 手电一档（**用户随后拍板「不要闪光灯，我们的场景不用」⇒ T65 整条拆**）；
      ⑤ 缩放缝：`clampedZoomRatio` 判"这台有没有缩放控制"，null 档一句话不动，非 null 档过钳制。
      判据全在新增的 `ScanCameraAidPolicy.kt`（**零 import**，帧够不够 / 点击映射 / 缩放钳制三块），
      11 条表驱动单测 + `ScanCameraAidWiringGuardTest` 8 条源码形状守卫 —— 其中两枚反向钉值得记：
      一枚钉 `setZoomSuggestionOptions` 不许被接进来，一枚钉 `ScanUiStatus.kt` 与基线**逐字节同**
      （它跑过、0 skipped，不是被假设过去的）。
      门禁（`assembleRelease` 先行）：**1277 单测 / 158 套件 / 0 失败 / 0 skipped**，
      lint 0 error / 14 warning（地板 1258/156，差值恰为 +19/+2）。
      **装机取证：这一页的链路第一次在设备上跑出来了**，而且门是被推翻的 —— `MainActivity` 的
      `ROUTABLE_FROM_INTENT` 里有 `spoc_scan`，而 `routeFrom` **只做集合成员判断、不查 `hasSession()`**
      ⇒ `adb shell am start -n com.buaa.schedule/.MainActivity --es com.buaa.schedule.EXTRA_ROUTE spoc_scan`
      不带教务会话就能开页（此前记忆写着"这是唯一那道门"，错了）。⚠️ 必须装 **debug** 包：
      release 裁了三个非 arm64 ABI，x86_64 模拟器上探针判"解码库不可用"、两条入口一起不 bind。
      实测四行：`相机 provider 第 1 次取值未成功` → `第 2 次取值成功`（T59 的重试第一次可见）；
      `本轮绑定的首帧已到达分析器：第 1 帧（绑定后 922ms）`；
      **`本轮绑定交付的分析帧：1280×960 / 旋转 90° —— 请求 1280×720、实际 1280×960`**
      （CameraX 落在 4:3 的 1.23MP 档 = 旧默认的 **4 倍像素**）；
      `这台设备没有可用的缩放控制（ZoomState 没报出 min/max 或区间固定），视场保持原样`
      —— 模拟器无变焦，钳制的 null 档不是假想分支；截图里底栏**只有「相册识别」没有手电按钮**
      （模拟器无闪光灯 ⇒ `hasFlashUnit` 那道显示闸生效）。
      复核抓到一处**本卡自己的静默档**：点按对焦把"被忽略 / 拒映射 / 不支持 / 抛出 / 启动失败"
      四档都留了行，**唯独成功那一档不打日志** ⇒ 我按了两次中心点 logcat 全静默，
      "手势没接住"与"点对成功"在设备上读不出区别。这一族修到第三轮了，转 T65③。
      同一轮的研究订正（两条都影响下一卡）：`ZoomSuggestionOptions` / `setZoomSuggestionOptions`
      **只存在于** `play-services-mlkit-barcode-scanning:18.3.1`（bundled 的 pom 以 compile 传进来，
      所以编得过），而 bundled 实现那 362 个类里 `zoom` 大小写**零命中**、驱动 zoom 建议的类在
      play-services 自己的内部包 ⇒ 在我们这条离线路线上它是**静默 no-op**，不许接；
      反过来 `enableAllPotentialBarcodes()` 在 bundled 字节码里有引用 ⇒ **真能在设备上生效**，
      它就是 T65 用来把"框里没码"与"码太小"分开的量具。
      有意未做 / 残账：**解码成功率仍验不到**（二维码喂不进虚拟场景，见 #98 真机回归）；
      `ProjectorZoomRatio = 1.5f` 每次绑定固定抬 1.5× 这个**形态不对**（可查证的做法是
      按检测框尺寸估码、驱动变焦、持续无效就回滚基准 —— 微信那套"放大"是超分 + 多尺度重试，
      代价 9.2MB + 757ms/图，我们出局；支付宝那篇一手资料全文没有变焦策略，"支付宝会智能放大"是讹传），
      T65② 换成检测框驱动的阶梯；zxing-cpp 当**第二引擎**（用户点头"加"，arm64 0.73MB deflated，
      只在 ML Kit 连续失败或只回 potential 时用 `tryHarder/tryInvert` 再解一次，
      ⚠️ 不许换主力：我们 468 帧私有基准里它反光 79.5% / 糊码 64.1%，差于 ML Kit 的 96.2% / 74.4%）
      另立 T66。
- [x] 扫码帧观测分档 + 检测驱动变焦阶梯 + 拆手电（T65，2026-09-22，`32bdb14`…`003bbdc`）：
      T64 复核点名的三条欠账一次结清（阶梯形态、对焦**成功档**无日志，以及按用户决定
      「加，然后不要闪光灯，我们的场景不用」把手电整条撤走）。
      **内核**（`ScanCameraAidPolicy.kt`，仍零 import）：`frameCodeRung(读到原文的码数, 候选数,
      最大候选框短边px)` → 四档 {`CodeReadable` / `NothingDetected` / `CodeTooSmall` / `CodeUndecodable`}，
      阈值 `MinUsefulCandidateBoxPx = UsefulModulePx(3) × QrModuleSideBudget(97) = `**`291 px`**
      （吃的是同一枚 v20 模块预算，不另起炉灶；3 px/模块取同行实测的识别率口径，不是 ML Kit 文档
      那个 2 px 的**存在性**下限）；退化框（缺失/非正）落 Undecodable 不落 TooSmall ——
      "太小"是一句要驱动抬视场的断言，拿不可信的测量抬视场，错的就是画面。
      **阶梯**：`ZoomLadderRatios = [1.25, 1.5, 1.75, 2.0]` + 基线 1f；`advanceScanAssist(状态, 档位,
      有没有缩放控制)` 每帧推一次 —— TooSmall 连续 30 帧升一档、到顶档再连续 60 帧仍太小 ⇒ 退回基线并
      **本轮绑定不再试**；屏上措辞另走 12 帧滞后窗（提示条不许被单帧噪声打得直闪）。
      `zoomControlAvailable == false` 时**连连击都不计**（计了也只是攒一发注定落空的命令），但
      "太小、走近一点"那句话照说 —— 在没有缩放控制的设备上它同样是实话。
      **接线**：scanner 加 `enableAllPotentialBarcodes()`（bundled 实现真兑现这颗开关，它是把
      "有码解不开"与"没码"分开测量的唯一仪器）；`noteFrameRung` 排在提交分支**之前**（闸门吞掉的帧也要记账）；
      缩放命令经回调出分析器、执行侧仍过 `clampedZoomRatio`、全页 `setZoomRatio(` 一处；
      绑定成功处探一次设备能力，账本随 `markBindStarted` 复位（重绑定 = 视场回基线，旧 `stepIndex`
      说"已经在 1.75×"而画面其实是 1×，那就是这一页修过三轮的"说了没做"）。
      **措辞唯一来源** `scanFrameAidText`：「看见二维码了，但它小到解不出来：请走近一点，或把码对准取景框正中。」/
      「看见二维码了，但一时解不开：请拿稳对准它，或换一张更清晰的码。」两档都不许提灯、提设置、承诺秒数。
      **反向钉两条**：`ZoomSuggestionOptions` / `setZoomSuggestionOptions` / `minAspectRatioToEnlarge`
      在页面源码零命中（bundled 路径上它是静默 no-op，谁接回来谁红）；`torch/Torch/手电/enableTorch/hasFlashUnit`
      在页面与内核零命中（撤走的东西不许留壳）。守卫 ③ 由"整文件逐字节未动"改为**区域比较**
      （`internal fun scanUiStatus(` 起、到 `GalleryUnreadablePrefix` 声明前止，比较基线仍 `03d6546`），
      因为本卡合法地给 `ScanUiStatus.kt` 添了 `scanFrameAidText`。
      门禁：`assembleRelease` 先行 → **1285 tests / 158 suites / 0 失败 / 0 skipped**（地板 1277/158，+8 全在本族）
      / lint **0 error / 14 warning**（地板不变）；release 包 **6,494,827 B**（改前 6,484,525，**+10,302**，
      拆手电省下的代码抵掉了阶梯与 potential 开关的大半）。
      **装机取证（buaa36 / debug 包 / 沿用 `EXTRA_ROUTE spoc_scan` 配方）**：
      ① 动作条只剩「相册识别」一颗，手电按钮从界面上消失（截图 `.tmp/T65v/scan_after.png`）；
      ② 两次点击各出一行 `点按对焦已受理：视口 (540.0, 764.0) → 测光点 (0.5, 0.33745584)`，
      且**没有**「被忽略 / 拒映射 / 不支持 / 抛出 / 启动失败」任一行 ⇒ T64 复核那条"成功档不打日志，
      所以'手势没接住'与'点对成功'读不出区别"的欠账结清，手势确实到达了 `CameraControl`；
      ③ `这台设备没有可用的缩放控制（ZoomState 没报出 min/max 或区间固定），视场保持原样` 绑定处一行，
      本轮**零条**升档行 ⇒ "没有缩放控制就闭嘴"在设备上兑现；
      ④ 交付帧仍是 `1280×960 / 旋转 90°`（T64 那本账没漂）；
      ⑤ **第一次拿到设备侧的正解码**：虚拟场景里那面棋盘格被 ML Kit 解成一枚**有原文**的码 → 自动提交 →
      `SpocQrParser` 本地判否（界面弹出「这不是一张智学北航的签到码」）。这条是双面的：
      好的一面是链路端到端活（帧→解码→原文→提交闸门→界面，此前只到过"帧到了"那一层）；
      坏的一面是 **ML Kit 在高对比棋盘上会误检**。⚠️ 已核 `SignInViewModel.kt:77-81` —— parse 返回 null
      时直接 return，**没有向服务端发出任何请求**，误检不会变成误签；真实教室里没有棋盘，
      但黑板花纹 / 表格线 / 投影摩尔纹属同一类风险，未估（转待实现）。
      有意未做 / 残账：potential 框自身的可靠性与每帧开销未测（这台没有可对照的真码）；
      291 px 阈值没对过一张真实签到码；阶梯在**有**缩放控制的设备上怎么走完全未测（模拟器没有）；
      帧数→秒数按 30fps 估（30/60/12 帧 ≈ 1s/2s/0.5s），实际送帧率未量；
      开了 potential 之后解码率是否变化未测。
- [x] 第二解码引擎 zxing-cpp 进包（T66，2026-09-22，`6de9944`…`917f774`）：用户那句「加，然后不要闪光灯」里
      "加"的那一半。**定位是红线不是口味**：ML Kit 仍是主力，zxing-cpp 只在它**连续**报「看见候选码却解不出原文」
      的帧上补一刀 —— 468 帧私有基准里它反光 79.5% / 糊码 64.1%，两项都低于 ML Kit 的 96.2% / 74.4%，
      当主力等于拿包体去买更低的识别率；它值回票价的是 `tryHarder` / `tryInvert` / `tryDownscale`
      这一类 ML Kit 不给我们而它内置免费的重试（`tryDenoise` 故意不开，那是按帧的代价）。
      **判据内核** `ScanSecondEnginePolicy.kt`（零 import，仓库口径）：`retryableRung` 只认
      `CodeTooSmall` / `CodeUndecodable` 两档（`NothingDetected` 是瞄不准，双解只是烧电）；
      四道上界全在一颗 `secondEngineDecision` 里 —— 连击 `SecondEngineStreakFrames=6` 帧才许第一次发火、
      两次之间隔 `SecondEngineFrameGap=15` 帧、每轮绑定封顶 `SecondEngineMaxFiresPerBind=8` 发、
      连续 `SecondEngineGiveUpAfterMisses=3` 次空手就本轮不再请它；终局三档排在"有没有必要"之前，
      否则永远听不到"这一轮为什么不再试了"。`secondEngineStopText` 是四句停法的唯一措辞来源，
      调用点只在**换挡**时留一行（按帧重复同一句等于把 logcat 冲干净，那种留痕和没有是一回事）。
      T66 还在 `ScanAssistState` 上另开了一本账 `retryableStreak`：它与 `tooSmallStreak` **只看一处不同** ——
      不看这台有没有缩放控制（缩放抬不动是相机的事，兜底解不解得开是解码器的事，两本账不许共用一个计数器）。
      **薄壳** `ZxingCppFallbackDecoder.kt`：探针就是"构造那一下"（`System.loadLibrary` 写在 wrapper 的
      `init` 块里 ⇒ 构造成功 ≡ 这颗 `.so` 在这台设备上加载得起来），未判定当可用放行、第一次真用现场把
      结论做出来；`IllegalStateException` / `IllegalArgumentException` 判整颗引擎不可用（帧格式是每绑定
      都不变的设备事实），其余异常只算这一发失手 —— 拿一次抽风判死一整轮是这一页修过三轮的"把暂时说成永久"。
      **接线**：补解排在 `scanner.process()` **之前**、跑在分析线程上，吃**截至上一帧为止**的连击
      （拿不到未来的结论是既定形状不是疏忽：代价最晚晚一帧，换来零拷贝、`image.close()` 时机一个字不动、
      零跨线程账本）；两条引擎**共用同一道提交闸门**（`submitDecodedText` 从 ML Kit 成功分支里抽出来，
      一人一份闸门就是两份真相）；兜底坏了**一行都不写进 ML Kit 那本健康度账**（那会把主力判死，
      是 T59① 刚修完的那类失效从另一头复活）；兜底**故意不进预热**（`.so` 一旦 dlopen 就常驻，不给罕见路径付这个）。
      **依赖与包体**：`io.github.zxing-cpp:android:3.1.1`（POM 原文 `<name>The Apache License, Version 2.0</name>`，
      **Apache-2.0 不是 MPL**）。它的 POM 带 camera-core 1.5.2 与 kotlin-stdlib 2.3.20，两条都**必须 exclude**：
      只把 core 抬上去就是让 1.5.2 的 API 面去接 1.4.2 的 lifecycle 实现体，编译期不红、只在绑定相机时抛。
      AAR 里四档 .so 合计 6,643,356 B 未压缩 ⇒ 点名裁掉非 arm64 那三档（省 4,896,508 B），
      清单从"三颗文件名"变成"两颗库 × 三档 = 六颗"，同源由 `BarhopperNativeLibProbeTest` ⑤ 读脚本文本比对钉住。
      实测 release 包 **7,236,195 B**（改前 6,494,971 ⇒ **+741,224 B**，其中兜底那颗 .so deflated 726,435、
      余下约 14.8 KB 是 dex），逐条目核过：`lib/arm64-v8a/libzxingcpp_android.so` 全包只此一份、DEFLATED、
      非 arm64 三档零命中、barhopper 仍只 arm64（4,946,720→2,105,673）、dex 里 `zxingcpp/BarcodeReader`
      17 命中（AAR 自带 `-keep` 生效，R8 没把兜底静默掉）。
      **我从字节码独立复核的三条地基**（不采信代理自述）：`BarcodeReader` 整类里 `ImageProxy.close()`
      **零调用** ⇒ 兜底不碰帧生命周期；`read(ImageProxy)` 的出路只有 `planes[0].buffer` + `rowStride` +
      `cropRect` + `imageInfo.rotationDegrees` → `readYBuffer(...)` 一条 native 同步调用 ⇒ 零拷贝就地读完
      成立，且它用的那四个 API 在 camera-core 1.4.2 里都在（exclude 的依据）；`loadLibrary` 在 `<init>` 偏移 72
      ⇒ "构造即探针"成立。
      门禁：`assembleRelease` 先行 → **1314 tests / 160 suites / 0 失败 / 0 skipped**（地板 1285/158，
      +29/+2 = 表驱动 15 条 + 接线守卫 14 条）/ lint **0 error / 14 warning**（家族与地板同一张票）。
      **装机（合并后的 master、debug 包）**：第二引擎全程**沉默**是这一轮的正观测 —— 5 行取证
      （provider 重试两行 / 无缩放控制一行 / 首帧 309ms / 交付 1280×960）之外零发火行、零停法行、
      零按帧刷屏，45 秒后进程健在；虚拟场景给不出"有候选码却解不开"的帧，所以 `retryableStreak` 攒不到 6。
      有意未做 / 残账：发火节奏、命中/失手/判死/额度四行留痕的实文、`EngineUnusable` 那一档、
      真码解出、真机 arm64 行为**整档未验**；⚠️ 一条新账 —— 兜底命中时投出去的是**兜底的原文**，
      而 `shouldSubmitScan` 的冷却只按"同一份原文"算 ⇒ 真机上若兜底误检、ML Kit 随后解出**不同**原文，
      这道闸门会放行第二份，目前只靠下游 `SpocQrParser` 判否兜底（与 #107 同族）。
      ⚠️ 这一卡的来历要记一句：第一支代理跑到 150 轮上限被强杀、**一枚提交没落**，全部工作停在未提交盘面里；
      接手判读后确认**接线其实接完了**，真正欠的是"完整门禁从没跑过 + 产物层从没逐条目核过"
      （外加一行掐断残留的重复注释、一处把"三颗文件名"写错的构建脚本注释）。
- [x] 判死之后停止帧流（T67 = 台账 #99，2026-09-23，`b2e1c41`…`6a796c2`）：T59 那轮**有意没收回**的耗电残账。
      **真账**：绑定那颗 effect 里 `if (!granted || !scannerWorking) return@LaunchedEffect`
      排在 `unbindAll()` **之前** —— 这个次序是 T59① 故意留的（停用窗口里"窗口过完没有"的唯一观测量
      就是帧还在不到达，掐了帧流就掐死那条自动活路）。但同一句把**判死**也盖住了：
      `scannerGiveUp` 成立后 `decoderFrameAction` 永远返回 `Skip`，而 `scannerWorking` 那时早已停在
      false 不再翻面 ⇒ 键表不动 ⇒ **从判死到用户退出这一页，相机按帧送、分析器一帧一帧 `close()`、
      一枚也不再解**。
      **形态**：新增 `LaunchedEffect(scannerGiveUp)`（在绑定那颗之后、回到前台那颗之前）→
      读 `analyzer.decoderHealthSnapshot()`（新加的只读函数）+ 两个句柄是否非空 →
      `frameFlowStop(health, analysisFlowBound)` → 只有 `Unbind` 才"句柄与解绑同批清零 + `unbindAll()` +
      一行 Warn"。判据开在**新文件** `ScanFrameFlowPolicy.kt`（零 import）—— 不是口味：
      `ScanSecondEngineWiringGuardTest` ④e 把 `ScanRecoveryPolicy.kt` **逐字节钉在 `003bbdc`**，
      往恢复内核加任何一挡都会让 T66 那条反向钉红。三件要紧的"不"：不往被 `ScanUiStatusTest` ⑥
      按字面钉着的键表里塞东西；**不在 ML Kit 回调里就地解绑**（同一枚 Task 后面还挂着
      `addOnCompleteListener { image.close() }`，就地 unbind 会把"这一帧关没关"变成回调次序的依赖 ——
      这一页踩过 finally 过早 close 的坑）；停用窗口（未判死）一律 `Keep`、一个字没改。
      用 `unbindAll()` 而不是 `unbind(analysis)`：留预览就是留相机，电账大头不收；
      代价是**判死档预览不再刷新**（那一档 `cameraLive` 本来就 false、取景框与帧观测提示早已收掉，
      画面继续动才是假话）—— 观感是否可接受待真机点头。
      **电账（全是上限口径，不是实测省电）**：临时探针包实测该页 **2.80 帧/秒**（180 帧 / 64.4 s，
      那一档还挂着 ML Kit 解码、是限流项，判死档不喂解码器只会更快；真机按 30 fps 送帧只是未量的上限）；
      扫码页绑着送帧时进程 **162.8–183.8 % of one core**（5 个 20–25 s 窗），同一页把相机路径拆掉
      （`stop cameraserver`，UI 确实还活着：两次点击都打出"点按对焦已受理"）只有 **0.3–0.4 %**
      ⇒ 差值 ≈**1.76 核**是"相机路径开着"的全部代价；折算**每帧 close 空转 ≈0.63 核·秒**、
      **一节 45 分钟的课挂机 ≈4 750 CPU-秒 ≈7 560 帧被 close 而一枚不解**。判死最早落在**第 123 帧**
      （我自己把 `healthAfterDecodeFailure` + `decoderFrameAction` 那台状态机逐帧复算过：第 1/2/3 帧连错
      → 压到 23，Probe 落在 23 → 压到 63，Probe 落在 63 → 压到 123，第 123 帧那一发把
      `suspensionCycles` 顶到 `MaxDecodeSuspensionCycles` ⇒ 判死；也就是 3 + 20 + 40 + 60 = 123）
      ⇒ 123 帧之后每一帧都是纯烧电，修完之后 123 帧就是终点。⚠️ 未做 mAh/续航换算；模拟器那 176 pp 里软件 GL 与
      emulation 的成分拆不开。
      **顺手收的一条既有谎话**：`recoverOnPageVisible` 里那句 `")：" + health.giveUpReason ?: "停用窗口内"`
      —— Kotlin 里 `+` 比 `?:` 绑得紧，Elvis **永远取左操作数**（编译器同一句警告 "always returns the
      left operand" 就在 `SpocScanScreen.kt:992`）⇒ 停用窗口里额度用完那一档取证行读出来是"…：**null**"。
      先把档位单独算成 `val why` 再进模板，守卫 ④c 加两枚钉（新形状必须在、`+ health.giveUpReason`
      这种死 Elvis 形状一律红）。
      门禁（我自己按序重跑）：`assembleRelease` → **1331 tests / 161 suites / 0 失败 / 0 skipped**
      （地板 1314/160，+17 全在 `ScanFrameFlowGuardTest`）/ lint **0 error / 14 warning**；
      release 包 **7,237,006 B**（T66 之后 7,236,195 ⇒ +811）。守卫里那 17 条是拿**真账**摆的：
      `driveToGiveUp` 从第 1 帧起逐帧喂失败、真经过 `decoderFrameAction` 的 Skip/Probe 一路走到
      `giveUpReason` 落上，不手搓一枚"看起来像判死"的 health（手搓会把"第几轮才判死"和
      "判死后 working 不再翻面"两件事糊掉）。
      **装机说实话**：判死这一档在 buaa36 上**触发不了** —— 三种强制手段都用过（`pm revoke` 直接把进程
      杀了、帧号从 1 重启；14 轮 HOME/前台循环；12 轮 `stop/start cameraserver` + 重进页面、期间 6 次
      成功重绑），全程**零条**"停用/恢复"、ML Kit 一次 `onFailure` 都没抛 ⇒ **判死⇒停帧这一整个新行为
      只有 JVM 证据**。我自己在合并后的 master 上复跑 30 秒取**负观测**：新增的停帧行**一条都不出现**、
      帧照常到达（首帧 667ms / 交付 1280×960）、点按对焦仍受理（句柄仍活的证据）、
      `dumpsys media.camera` 里客户端仍是我们的 pid。⚠️ 顺带推翻我卡里给的一条口径：**`frameCount`
      挂在 `QrCodeAnalyzer` 实例上**，`am start --es EXTRA_ROUTE spoc_scan` 每次新建组合 ⇒ 帧号从 1 重启
      ⇒ 想拿"两绑定之间的帧号差"算送帧率在这台机器上不成立。
      我复核后订正卡里四处判读：判死的推进点是**失败那一路**（`noteDecodeFailed` ← `onFailure` 与
      `process()` 同步抛），不是成功回调；组合**会**醒（`scannerGiveUp` false→true 是真翻面），
      病根是醒过来的那一次**没人看它**（绑定那颗的键里没有它）；恢复内核被逐字节钉着 ⇒ 判据只能开新文件；
      取证行算不出送帧率（见上一条）。另记一条**维护陷阱**：`ScanRecoveryPolicy.kt` 现在被**两枚**守卫
      按**两个基线**逐字节钉着（T66 ④e → `003bbdc`、T67 ⑥b → `83d18f5`），将来真要改它得同时重钉两处。
      我另收一处：内核注释原写"模拟器实测该页约 30 帧/秒在空转"—— 探针实测是 **2.80**，30 那个数是
      未量的上限口径，被写成"实测"了 ⇒ 已订正成三条能对账的数（假实测与谎话同罪）。
      有意未做 / 残账：停帧之后"回到前台重新绑回来"**无设备证据**（要先进判死；JVM 侧是 ①e + ④c，
      真机建议按"遮住镜头让解码连错"那条路走一次）；真机 arm64 的电账与判死档真实送帧率未量；
      解绑与"ML Kit 仍持有已交出去的帧"并发那几帧仍由原本那一发 close 收掉 —— 没有设备证据能证伪
      "解绑后出现 Image is already closed"，只有 close 时机未改的形状守卫。

- [x] 「凌晨还是显示前一天的课表」（T68，2026-09-23，`9f5e665`…`5099903`）：用户同批两条里的第二条。
      **先把不是账的那条路排掉**（这一步不做，下一步就会去改时钟）：`today` 本身不推进是同一个症状的
      另一条因果链，但 `uiState` 走的是 `SharingStarted.WhileSubscribed(5_000)`
      （`ScheduleViewModel.kt:463-467`）⇒ 退到后台五秒上游就取消，回前台重新订阅时 `dayTicker`
      那颗冷流第一件事就是 `emit(LocalDateTime.now().toLocalDate())`（`:418-431`）⇒
      **"回到前台"这一档的 today 永远是新鲜的**，Doze 冻结延时的影响被重新订阅抹平。
      于是剩下的唯一输入就是那笔**跨天存活的浏览**：`HomeScreen` 把"翻到哪一天"存在
      `rememberSaveable { mutableLongStateOf(-1L) }` 里（它本来就该比进程活得长），
      而决定 body 那一天只有 `date = browseDate ?: today` 一行算术 —— 没有任何东西在 today 前进时作废它。
      昨天翻过一天、或者点过桌面组件的某一格（那一路也写同一个槽位），今早冷启动日视图仍停在那一天；
      而顶栏与标题讲的是今天，**两行各说一天**，与 T56 那一族同一条因果链（那次修的是周，日没修）。
      **内核**（`ui/home/DayBrowsePolicy.kt`，零 android import、零时钟读取，`LocalDate` 当参数传）：
      判据取**锚定日**而不是"上一次见过的今天" —— 一笔浏览只在它被按下的那一天之内有效，
      与进程死活、恢复次序、滴答迟不迟到都无关；锚定日缺失或落在未来（改过系统时间、跨国往西）
      一律不复活旧浏览日，方向恒偏「跟随今天」。`dayViewDate` 由此成为全页唯一一份 body 真相，
      `dayTabHeadline` 沿用本仓「（浏览）」记号、不写具体日期（日视图页头紧挨着下面就写着那天，
      再摆一遍就是以前那张「三个第 N 周 + 两个日期」返工单要治的东西）。
      **接线**：槽位从一枚变两枚（`LocalDate` 直接进 Bundle 连编译都过不去），
      `setBrowseDate(date, on = today)` 一次写完整对，「跳到本周」那处清零一起清；
      全页 `setBrowseDate` 调用点恰好四处（日视图两处回调 / 组件跳格 / 跳到本周），
      四处消费方（两处 `date =`、顶栏第二行、今日页标题、屏上月份）从此读同一个 `browseDateOnScreen`。
      守卫 ③ 把"槽位只许一处直写"钉死 —— **绕过去单写浏览日，就会留下一笔永不过期的浏览**。
      ⚠️ 一条顺带的性质：老包存的只有浏览日、没有锚定日 ⇒ 新包读回哨兵 -1 ⇒
      **升级后的第一次打开会一次性把人弹回今天**。这不是副作用，这正是当前装机用户看到的那一屏。
      有意未做（守卫 ⑧ 把它钉成决定）：「浏览到哪一**周**」不让位 —— 那一维顶栏第一行明写着
      「（浏览）」、又有「跳到本周」这个显式入口，读到的是"我在看第 3 周"而不是"今天在第 3 周"，
      不构成谎话；半夜查下节课时被人顺手丢回本周才是。
      门禁（我自己按序重跑）：`assembleRelease` 先行 → **1352 tests / 163 suites / 0 失败 / 0 skipped**
      （地板 1331/161，+21 条 / +2 枚套件全在 `DayBrowsePolicyTest` 9 + `DayBrowseWiringGuardTest` 9
      + `TopBarDateLabelTest` 补的 3）/ lint **0 error / 14 warning**（地板不变）。
      release 签名包 **7,236,213 B**（基点 `cf228c7` 实测 7,237,096 ⇒ **-883 B**：
      两处重复的日期算术被收成一份真相，删掉的比新内核多）。
      复现测试**先红后绿**这条成立（第一枚 commit 就是把现状逐字转录进 JVM，红的三条分别是
      `inUseBrowseDate expected:<null> but was:<2026-09-22>`、`dayViewDate expected:<2026-09-23>
      but was:<2026-09-22>`、标题 `expected:<[课表（浏览）]> but was:<[今日课表]>`）。
      **装机取证（buaa36 / debug 包 / 设备钟 GMT，故"今天"= 9 月 22 日）**：
      ① 今日页签翻到 9月24日 ⇒ 标题从「今日课表」变**「课表（浏览）」**、页头副行从「今天 · 第 4 周」
      变「第 4 周」、`回到今天` 出现（截图 `.tmp/T68v/d_next.png`）；时间轴块仍按 T61 的密度画
      （编译原理 08:00–09:35 · 第1-2节 · 韩雪 · 主M401），本卡没碰排版；
      ② `KEYCODE_HOME` + `am kill` + 重新 `am start`（`pidof` 先空后新 pid ⇒ 真进程死亡）⇒
      回来仍是 9月24日 且顶栏第二行同步 ⇒ **同一天内的浏览跨进程死亡存活**这条在设备上兑现，
      没有修成"一动就跳回今天"（`.tmp/T68v/h_try.png`）；
      ③ ⚠️ 同一张图抓到**本卡带来的新账**：人在「周课表」页签时，顶栏第二行仍写「9月24日 星期四」，
      而网格里高亮的今天是 9/22 周二 —— 日视图根本不在屏上，`browseDateOnScreen` 这个名字在这里
      就是假的。修法很小（那一档只在日视图真的显示时才传 `dateOnScreen`），但**不能悄悄咽下去**
      ⇒ 台账 #111 / **T68b**，排在 T70 之后（同文件，禁并行）。
      残账：跨午夜那一档**没有设备证据** —— 这台 AVD 改钟的历史结论是 `toybox date -s` 会跑飞到
      2018 并同时打坏闹钟队列 / Room WAL / TLS（见 `project-buaa-avd-seed-and-root`），
      所以不为这一档赌设备状态，判据那 9 条 JVM 用例（含冷启动恢复、半恢复、锚定日在未来三档）
      就是目前的全部证据；真机上按"昨天翻一天、今早开"这条路走一次才算闭环。
      另一档已知不一致本卡有意留着：日视图翻到**别的那一周**里的某天时，顶栏第二行退回今天
      （`dayInsideDisplayedWeek` 只认"与第一行那一周不冲突"，越出去就又是 T56 那句自相矛盾），
      窄屏上这一档多半不出现，出现了也只是"第二行不讲 body 那一天"，不是讲错的一天。
