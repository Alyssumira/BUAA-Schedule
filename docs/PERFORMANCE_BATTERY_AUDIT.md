# BUAA-Schedule 性能与耗电专项审查报告

> 审查时间：2026-09-14  
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