# BUAA-Schedule 性能与耗电专项审查报告

> 审查时间：2026-09-14（第 1 轮）、2026-09-17（第 2 轮，见 §4 起）  
> 审查对象：`app/src/main`（Compose / Room / AlarmManager / WorkManager / WebView）  
> 验证：`./gradlew --offline :app:compileDebugKotlin` 与 `./gradlew --offline testDebugUnitTest` 均通过

---

## 0. 结论

项目整体采用“事件驱动 + 低频后台”设计，后台唤醒面已经很小：
- 提醒：AlarmManager 只保留下一条；
- Widget：数据变化 + 每日零点，无固定轮询；
- 兜底刷新：WorkManager 12h，且无 Widget 时会自注销。
- 课程实况：**上课期间有循环**（进度条每前进一格重贴一次，一节课 ≤100 次），下课即停。

第四类是**上课期间的课程实况前台服务**：`CourseFluidService` 有一个 `Handler` 循环，
它按进度条的 100 格安排下一次唤醒（`nextProgressTickMs`），只在贴出来的内容真的会变时
`notify` 一次。改造前是固定 `UPDATE_INTERVAL_MS = 30_000L`，90 分钟一节课要重贴 180 次、
其中约 80 次内容完全相同（R5 §7 记录的就是这个与文档不符的结论）。
这个循环负责的是**进度条**——秒级跳动由系统渲染的
countdown chronometer 承担，不依赖这个循环；下课（或课程实况被手动关闭）即 `removeCallbacks` 并 `stopSelf`，
因此它只在真的在上课时耗电，课后不留任何轮询。

本次发现的耗电/性能问题主要集中在 **WebView 常驻、Compose 分钟级重组、重复联网抓取、后台重复装配** 四类。均已完成代码优化。

---

## 1. 已优化项

### 1.1 保留的教务 WebView 在后台暂停（耗电优化）

**问题**：登录成功后的 byxt WebView 为保持页面上下文会话而常驻隐藏宿主（1×1），
即使应用退到后台，WebView 的 JS 定时器、网络、渲染仍可能继续运行，造成无谓耗电。

**改动**：
- `data/import/BuaaWebSession.kt`：新增 `setAppForeground(fg)`；
  后台时对会话 WebView 调 `onPause() + pauseTimers()`，前台恢复 `onResume() + resumeTimers()`。
- `MainActivity.kt`：`onStop` / `onStart` 同步前台状态。
- `retain()` 时若应用已在后台，会立即暂停新保留的 WebView。

**收益**：退到后台后不再为隐藏登录页维持 JS/网络/渲染活动；回前台后会话仍可用。

### 1.2 首页学期列表与“学习日程”标注去重网络（耗电/流量优化）

**问题**：
- 每次回到首页 `HomeScreen` 都会调用 `refreshBuaaTerms()`，即使进程内已拉取过学期列表；
- `refreshSpecialDays()` 每次都请求本月+下月，即使本地已有缓存。

**改动**：
- `ScheduleViewModel.refreshBuaaTerms()`：进程内已有学期选项或正在抓取时直接返回；
  并新增 `buaaTermsFetching` 防止快速进出首页造成并发重复请求。
- `ScheduleViewModel.refreshSpecialDays()`：先查 `SpecialDayCache.cachedMonths()`，
  已缓存的本月/下月不再请求教务接口。

**收益**：主页常驻期间的重复进入不再反复驱动隐藏 WebView 执行页面内 fetch。

### 1.3 周视图/日视图分钟 tick 生命周期门控（性能/耗电优化）

**问题**：周视图每分钟更新一个 `LocalTime`，并把该值作为普通参数传给整棵 `WeekGrid`；
每分钟会导致周视图全部课程卡重组，且应用退到后台后协程仍继续跑。

**改动**：
- `WeekView.kt`：改为 `nowTickState = remember { mutableStateOf(...) }`，只把 `State<LocalTime>` 传给网格；
  只有“当前课高亮”与“当前时间线”读取该状态，普通课程格不再随分钟 tick 全量重组。
- `NowLine` / `CourseCell.isCurrentProvider` 直接读取 `State.value`：
  非当前周、非今天的课程不订阅分钟状态。
- `WeekView.kt` / `DayView.kt`：tick 循环放进 `lifecycle.repeatOnLifecycle(STARTED)`，
  应用退到后台自动停表，回前台自动重启。

**收益**：前台的分钟级重组范围从“整周网格”收窄到“今日当前周相关单元格 + 时间线”；
后台不再空转每秒/每分钟协程。

### 1.4 Widget/提醒后台读取改为 SQL 过滤（性能优化）

**问题**：`ScheduleRepository.getDisplayCourses()` 每次都 `getAll()` 读全表，
再在 Kotlin 内存里过滤“手动课程 + 当前学期”；存在多学期历史数据时浪费内存和 CPU。

**改动**：
- `CourseDao.kt`：新增 `getDisplay(semesterCode)`，SQL 直接过滤
  `semesterCode IS NULL OR semesterCode = :semesterCode`。
- `ScheduleRepository.kt`：`getDisplayCourses()` 在有当前学期时走该查询；
  无学期时仍读全表（语义不变）。

**收益**：提醒重排、Widget 更新、明日预告、日历同步等后台路径不再把全部历史学期课程读进内存。

### 1.5 后台任务复用 Application 级 Repository（装配开销优化）

**问题**：`BackgroundSync.rescheduleReminders()` 每次新建 `ScheduleRepository`，
在开机、升级、时间变化、Widget 兜底等路径重复装配。

**改动**：`BackgroundSync.kt` 增加 `repositoryOf(context)`，
优先复用 `BUAAApplication.repository`，与 WidgetCommon 的口径一致。

**收益**：后台事件少一次对象装配和数据库单例查找路径。

### 1.6 上/下课铃取消不再创建无意义 PendingIntent（性能优化）

**问题**：`ClassProgressScheduler.cancel()` 每次用 `FLAG_UPDATE_CURRENT` 创建两个
“空窗口” PendingIntent 再立刻 cancel；即使从未排过铃也会创建。

**改动**：`ClassProgressScheduler.kt` 的 `cancel()` 改用 `FLAG_NO_CREATE`
只取消已存在的 PendingIntent，并新增 `existingPendingIntent()` 辅助方法。

**收益**：每次重排提醒 / 取消提醒时减少 PendingIntent 对象创建与跨进程调用。

---

## 2. 维持现状的取舍（不建议轻易改动）

| 项 | 原因 |
| --- | --- |
| WorkManager 12h Widget 兜底 | 对抗部分国产 ROM 清理精确闹钟；Worker 内已自检有无 Widget，无组件时自注销 |
| 每日 22:00 明日预告 | 用户功能；精确权限缺失时已降级为非精确闹钟 |
| 隐藏 WebView 前台保持活跃 | byxt 页面上下文是刷新课表的唯一凭证来源，前台暂停会导致刷新不可用；后台暂停已覆盖主要耗电场景 |
| 北航按周串行抓取 | 接口/服务端未验证并发安全；并发可能增加限流风险和瞬时网络功耗 |

---

## 3. 验证结果

```text
./gradlew --offline :app:compileDebugKotlin   → BUILD SUCCESSFUL
./gradlew --offline testDebugUnitTest          → BUILD SUCCESSFUL
```

改动未改变任何数据模型、Room schema、提醒/Widget 对外契约；纯性能/耗电优化。
---

# 4. 第 2 轮（2026-09-17）：省电 / 稳定性 / 内存 / 包体

> 同一批代码的四轴全量复查。下面只记**本轮改动**，第 1 轮的结论不重复。
> 每条都带"为什么原来是错的"，因为这几处都不是显然会踩的坑。

## 4.1 省电

| 改动 | 原来错在哪 |
| --- | --- |
| `TomorrowPreviewReceiver`：续排移进已经读出课表的那条路，按 `nextPreviewDay`（上限 120 天）排到「往后第一个明天有课」的前一天 22:00，找不到就不排 | `onReceive` 无条件续排下一晚 → 周末、假期、学期结束后每晚一次精确闹钟唤醒 + 三次查表，纯白醒 |
| `BackgroundSync.rescheduleReminders` 按 `ReminderMode` 门控，日历模式下只清理不重排 | 「系统日历提醒」用户明明一个应用内闹钟都不该有，却在每次开机 / 改时间 / 12h 兜底时被重新装上整天的闹钟 |
| 同函数返回 Boolean（提醒链是否已把上下课铃一起接手），`WidgetFallbackWorker` 据此决定是否补排 | 兜底 Worker 原先无条件再调一次 `rescheduleNextWindow`：多三次查库，还把刚排好的铃 cancel 掉重排一遍 |
| `refreshWidgets` 在无组件实例时早退 | 全学期快照 sync（逐学期查库 + JSON + upsert）与 5 次 `getAppWidgetIds` 没有读者 |
| `WidgetRefreshReceiver`：系统 `DATE_CHANGED` 与自己排的 `ACTION_MIDNIGHT_REFRESH` 在 120s 内只放行一次全量刷新（判据用 `elapsedRealtime`，改表也绕不过），续排照常每次做 | 零点两条广播同刻到达 → 同一份刷新跑两遍 |
| 5 个 provider 的 `onEnabled` 统一走 `WidgetCommon.bootstrapBackgroundSync`（分步兜异常 + 60s 去重） | 各 provider 自己排一份零点闹钟与兜底任务，Launcher 一次 `onEnabled` 风暴就是多份 |

## 4.2 稳定性

- **三族通知的启动 PendingIntent 互相覆盖**（`ReminderNotifications`）：课前提醒 / 明日预告 / 课堂实况
  都用 `requestCode=0` 指向 `MainActivity`，而 PendingIntent 判等只看 `(requestCode, Intent.filterEquals)`、
  `filterEquals` **不含 extras**。于是一节课的通知会把另一节课的深链 extra 改掉，取消时也一起被误取消。
  现在按码段分家（300k / 310k / 320k）并各带 `buaa://launch/<码段>` 判别（`MainActivity` 从不读 `intent.data`，
  判别串不改变行为，只防"以后谁改了重建逻辑又撞回去"）。
- **`BUAAApplication.onCreate` 后台链整块兜异常**：那个作用域是 `SupervisorJob` 且没有
  `CoroutineExceptionHandler`，块内任何未捕获异常都落到线程默认处理器 → 直接杀进程，
  表现为"一打开就闪退"，而自建更新通道对这类失败无效。WorkManager 首次 `getInstance` 要建自己的库、
  `hasAnyWidget` 要跨 binder 问 Launcher（MIUI 上实测会抛 `DeadObjectException`），两条都不是"不可能发生"。
- `WidgetFallbackWorker.ensure/doWork`、`ReminderReceiver` 中 `goAsync()` **之前**那段（贴实况 + `notify`，
  跑在主线程）就地兜异常留痕 —— 同样的位置，逃出就是崩在广播里。
- `CourseListWidgetService`：`DAY_NAMES[idx]` 改 `getOrNull`。备份 / 口令恢复出一个越界的 `dayOfWeek`
  原先会让组件永久停在灰色"崩溃"块上，且没有任何提示。
- `CourseFluidService` 不再自建常驻作用域，改挂 `BUAAApplication.applicationScope`：
  原来的 `ioScope` 从不取消（泄漏一个作用域），而在 `onDestroy` 里取消又是错的 ——
  `finishLiveAndReschedule()` 之后紧跟着 `stopSelf()`，onDestroy 会在续排协程还没被调度起来时到达，
  等于把"下一节课的窗口"一起取消，只剩 12 小时兜底。
- 复核（未改）：5 个 receiver 全部已 `goAsync()` + `CancellationException` 续传 + `finally finish()`。

## 4.3 内存

- **`BUAAApplication.onTrimMemory`：全项目此前零 trim 回调**，LMK 只能整程回收。现在在
  `BACKGROUND / MODERATE / COMPLETE` 三档释放隐藏会话 WebView（单个最大常驻块，几十 MB 量级的 Chromium 堆；
  Cookie 已落盘，回前台按 Cookie 重建）并清 `WidgetDataCache` 的进程内快照。
  刻意**不**处理 `RUNNING_CRITICAL` 与 `UI_HIDDEN`：前者发给的是前台进程，而前台正是用户可能正在抓课表的时候
  （取数就走这个 WebView）；后者意味着"切出去又马上回来"，重跑一遍 SSO 比省下的内存更贵。
- `WidgetDataCache.peek()` 认 5s TTL：组件进程可能整程不触发 `invalidate()`（用户从不在应用内改课表），
  不设时限就等于每绑过一个学期留一份全量课程列表到进程结束。过期返回 null，已有的异步补数据路径接管。
- `BackdropGraphicsLayerPool.release()` 加面积闸（>1.3M 像素的层直接还给 `GraphicsContext`）：
  池只按"个数 16"收层，而释放后的层按最后使用的尺寸保留后备纹理 —— 整屏背景层与几十像素的卡片层在旧口径下等价，
  16 个大层就是上百 MB 常驻显存。
- `MainActivity` 只在玻璃档位 ≥ STANDARD 时创建 `SharedBlurBackdrop`（整屏 0.48x 降采样 + blur 的共享前缀层）。
  档位 OFF 恰好是低端机与被系统降级机的档位，而消费方本来就已对 null 降级。

## 4.4 包体（实测）

- 删 `okhttp` 依赖：R8 早已把它的类全剥掉，包里真正留下的只有它 41KB 的
  `okhttp3/internal/publicsuffix/publicsuffixes.gz`（全部网络请求走 `HttpURLConnection` 与页面内 `fetch`）。
- `packaging.resources.excludes`：`kotlin/**` builtins、`META-INF/*.kotlin_module`、`META-INF/*.version`、
  `DebugProbesKt.bin` —— 均无运行时读取方（`kotlin-reflect` 不是依赖）。META-INF 下各依赖的 `LICENSE.txt`
  一条不动（液态玻璃的 Apache-2.0 归因靠它），改动前后都是 6 份。
- `androidResources.localeFilters += ['zh','en']`：本应用自己没有 `values-*`，多语言资源全部来自 AndroidX/Compose，
  其他语言走默认（英文）回退，不会因为过滤器而找不到资源。

```text
app-release.apk      2,673,802 → 2,453,873 字节   (-219,929，-8.2%)
APK 条目                   181 → 94
resources.arsc          183,240 → 28,336
classes.dex（压缩）   1,861,112 → 1,861,676   （本轮代码的净增，说明没把任何功能编进去）
```

## 4.5 评估后不做

| 项 | 不做的理由 |
| --- | --- |
| `enableV1Signing = false` | 只省 ~14KB（`CERT.SF` + `MANIFEST.MF`），代价是 `app/build.gradle.kts` 里 `reportSigners()` 读 PKCS#7 打印证书指纹那行失效 —— 那是"发出去的包与本机已装是不是同一把密钥"的唯一自查手段（换签名事故的产物）。0.5% 换一条门禁不值 |
| 排除 `lib/*/libandroidx.graphics.path.so`（4 个 ABI 共 37KB） | `PathIteratorPreApi34Impl` 在 API 26–33 上是真可达路径（它躲过了 R8），不是死代码 |
| `repository.courses` 仍观察全表 | 分学期观察要动 `CourseFilter.visibleIn` 的"手动课 + 当前学期"口径，换来几十 KB 常驻，风险却压在数据展示上 |
| 12h 兜底 Worker、`setAlarmClock` 上课铃 | 与第 1 轮同结论：抗 ROM 的载荷，不以省电为名削弱可靠性 |
| 5 张 `ic_launcher_foreground.webp`（134,756 / 85,972 / 44,994 / 27,974 等）与 `onboarding_hero.webp`（77,990） | 这是 res/ 里唯一还能明显再砍的一刀（约占 res 六成），但素材重压属于视觉改动：要先出本地预览与约束计算、原件保留，等确认再动 |

## 4.6 遗留（需要单独决定，别顺手塞进下一轮）

- **全站零崩溃上报**：澎湃 HyperOS 会吞第三方 logcat，用户"闪退"之后既没有本地留痕也没有回传通道。
  任何形式的上报都是新子系统 + 新隐私面（写盘 / 回传堆栈，要同步进 `docs/PRIVACY.md`），得单独设计。
- `WidgetConfigActivity.kt:219-220` 用 `remember` 存草稿：配置桌面组件时转一次屏，已勾的外观与学期绑定
  全部回退到落盘值。修法是 `rememberSaveable` + 手写 `Saver`，但那会多出第二份字段清单（与
  `WidgetAppearanceStore` 并列），以后加字段容易只改一处 —— 留给单独一轮。
- `MIGRATION_7_8` 重建 `courses` 表，值得补一条 schemaJson 对比测试（androidTest 侧）。本轮没跑 connected
  测试：它会清空真机上的课表库。

## 4.7 验证（第 2 轮）

```text
gradle --offline :app:testDebugUnitTest  → 403 tests, 0 failures（第 1 轮基线 345）
gradle --offline :app:lintDebug          → 0 error / 19 warning
gradle --offline :app:assembleRelease    → BUILD SUCCESSFUL，2,453,873 字节（CN=Alyssumira 正式密钥）
```

真机回归未做：本轮只到编译与单测；改到的都是闹钟/通知/组件链路，装机后的表现要自己看。
