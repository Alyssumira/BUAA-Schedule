# 后台待机功耗审计（2026-09-18，T1 轮）

> 范围：`reminder/**`、`widget/**`、`data/local/**`（Room 与 token 读写频率）、以及全部持有
> AlarmManager / WorkManager / WakeLock 的路径。只读审计，**本轮未改任何业务代码**。
> 前两轮同类结论见 `docs/PERFORMANCE_BATTERY_AUDIT.md`（2026-09-14 第 1 轮、2026-09-17 第 2 轮），
> 本文只记**本轮新证据**，不重复已修项；已修且复核仍在位的地方写进 §3。
> 取证方式：全仓库静态读 + 调用链闭合。**凡需要设备才能定论的，逐条标注**
> 「需真机/需 AVD，由 orchestrator 验」，命令在 §4 给全。

---

## 0. 一句话结论

**调度拓扑本身是健康的，本轮没有 P0；问题全部集中在"每一次唤醒要付多少钱"，
而不是"一天醒多少次"。** 稳态可排队闹钟 ≤6 条、Manifest 无一条高频系统广播、
WakeLock 全部带超时且 `try/finally` 释放——这三条都是本轮 grep 逐条验证过的硬事实，
意味着"减少唤醒源"这类常规省电建议在项目里已经没有活可干。
真正在漏水的是：`BUAAApplication.onCreate` 那条**无条件重跑的后台重建链**，
因为每个闹钟都会拉起冷进程，于是"数据一个字没变的一次唤醒"要付两遍全量重排、
一遍全学期快照重写、以及最多 15 次跨 binder 问 Launcher。

### 0.1 稳态闹钟清单（审计基线，改动前后都该长成这样）

| # | 用途 | requestCode | 档位 | 位置 | 稳态是否常驻 |
| --- | --- | --- | --- | --- | --- |
| 1 | 课前提醒（下一条） | `0` | `setExactAndAllowWhileIdle` | `ReminderScheduler.kt:101` | 是 |
| 2 | 上课铃 | `30_260_031` | **`setAlarmClock`** | `ClassProgressScheduler.kt:349` | 是 |
| 3 | 下课铃 | `30_260_032` | `setExactAndAllowWhileIdle` | `ClassProgressScheduler.kt:383` | 是 |
| 4 | 勿扰看门狗 | `30_260_034` | `setAlarmClock` | `ClassProgressDnd.kt:159` | 仅课中（下课即 `cancelWatchdog`） |
| 5 | 组件零点刷新 | `10_001` | `setAndAllowWhileIdle`（**非精确**） | `BackgroundSync.kt:155` | 仅有组件实例时 |
| 6 | 明日预告 22:00 | `20_260_022` | `setExactAndAllowWhileIdle` | `TomorrowPreviewReceiver.kt:182` | 仅开开关且有内容可推时 |

WorkManager：全仓库唯一一条周期任务 `widget_fallback_refresh`，12h，`KEEP` 策略
（`WidgetFallbackWorker.kt:74-79`）。**JobScheduler 零使用**（`JobInfo`/`JobService` 全仓库 0 命中）。
Manifest 动态注册零（`registerReceiver` 全仓库 0 命中），`SCREEN_ON`/`SCREEN_OFF`/`TIME_TICK`/
`BATTERY_CHANGED`/`CONNECTIVITY_CHANGE` 一条都没订阅。这两项是本轮复核确认的**硬事实**，
下一轮不要把精力花在这里。

---

## 1. 问题表（按对待机功耗的实际影响排序）

| 级别 | 位置（文件:行号） | 症状 | 省电收益估计 | 改动风险 |
| --- | --- | --- | --- | --- |
| **P1** | `BUAAApplication.kt:36`、`:58-74`；放大点 `BackgroundSync.kt:112-126`、`:139-161`、`WidgetFallbackWorker.kt:71-73` | 每次冷启动进程无条件重跑整套后台链；而**每一个闹钟都把进程冷启动**（6 条闹钟 × 每日多次），于是"数据没变的唤醒"付两遍全量重排 + 全学期快照重写 + ≤18 次 Launcher binder 往返 | 单次冷唤醒的后台工作量：重复的那一半（约 7+2N 次查询、N 份快照写盘、≤12 趟 Launcher binder）**是可证明的净多余**；折算成功耗降幅 **推断，未取证**（需 §4.3/§4.4 前后对测）。待机总唤醒次数不变，降的是每次唤醒的 CPU 占空比与闪存活动。**2026-09-19 进度**：组件探测那一份已由 ai/T10 收成一次探测（≤12 趟已省掉）；"整套重排重跑"仍待办 | 中：幂等补注册（ROM 吞广播的自愈路径）与"升级后重建"依赖这条链，收窄触发条件必须保留事件式兜底，不能简单删 |
| **P1** | `ClassProgressScheduler.kt:274-285`（破坏性分支）＋ `ReminderNotifications.kt:181-198`（判据是进程内 `@Volatile`）＋ `BUAAApplication.kt:23`（`Dispatchers.IO`，真并行） | onCreate 那条链与广播自己那条链并发跑同一次 `rescheduleWindows`；前者的"这是下课铃被吞的遗留"判据读的是进程内状态，**可能在课前倒计时已下发之后**才跑到，于是把刚上岛的倒计时停掉 | 直接收益小（省一次通知重下），**但这是 2026-09-17 事故的同类残留路径**，修掉 P1-①（重复链）后本条随之消失 | 高：动的是曾经拆掉过课前倒计时的同一段代码；建议**只通过消除重复链来间接修**，不要给 `rescheduleWindows` 加新分支 |
| **P2** | `BackgroundSync.kt:67` 与 `:70-78` | 同一轮重排里 `planNextReminder` 算了**两遍**：`rescheduleAll` 内部已算（`ReminderScheduler.kt:64`），外层为了拿 `willRemind` 这个 Boolean 又把 O(课程数×剩余周次×节次段) 的全量搜索重跑一次 | 每次唤醒省一次全量窗口搜索（学期中段约上千次窗口构造，见 §2.3 的量级推导） | 低：让 `rescheduleAll` 复用已算出的 plan 即可，公开签名可保持 |
| **P2** | `WidgetDataSynchronizer.kt:36-63`（尤其 `:50`） | 快照 sync 是 N+1：`getTimeSlots()` 是循环不变量却写在 `keys.forEach` 里（每 key 查一次）；且重写**全部学期**的快照（`"current"` + 每个 termCode），而每个组件只读自己那一个 key | N = 学期数+1 → 一轮 sync 从 `3+2N` 次查询降到 `4+N`（把循环不变量提出去）；若进一步只写被组件引用的 key，JSON 编码与 upsert 从 N 份降到 1–2 份。**待机时被 §0-P1 那条链每次都触发** | 低-中：查询提出循环是纯搬家；只写被引用的 key 要先确认 `WidgetBindingStore` 里没有"组件引用了但本轮没写"的 key |
| **P2** | `WakeLocks.kt:22`（默认 5s）× `ReminderReceiver.kt:41`、`TomorrowPreviewReceiver.kt:41`、`ClassProgressReceiver.kt:88` | 三处把"重活"包在**默认 5 秒**锁里，而 `BootReceiver.kt:36-41` 与 `WidgetRefreshReceiver.kt:39-45` 明确注释"默认 5 秒会被提前收回"并传 10s。超时后系统静默收回锁 → 剩下的 DB 查询/重排跑在随时睡回去的 CPU 上，**等于回到加锁前那个故障**，且不留任何日志 | 表面是收益（少持锁），实为**故障面**：Doze 深睡下锁被收回会重演"这一节课没有提醒"。真实待机电流影响很小 | 低：只调超时参数；但要连带回答"跑不完时半套闹钟"的语义（见 §2.5） |
| **P2** | `ClassProgressReceiver.kt:26`（同步块）＋ `:42-57` | ACTION_START/ACTION_END 的主体跑在**广播主线程**：`startLiveWindow`（ensureChannels + notify + startForegroundService）、`ClassProgressDnd.enter`（prefs 写 + `setInterruptionFilter`）、`scheduleEnd`，5+ 次 binder 串在 `onReceive` 里。同项目 `ReminderReceiver.kt:34-37` 的注释已经写下正确结论："这笔 binder + 解密全落在广播主线程上就是在吃 10 秒配额" | 中等：主线程配额紧张时整条广播被掐 → 表现为提醒丢失；掐掉后重排没跑完，又会拉起下一次冷启动重跑（与 P1-① 互相放大） | 低：挪进 `goAsync()` 协程即可，但 `scheduleEnd` 必须先于任何可抛步骤（R5 F-31 的顺序约束不能破） |
| **P2** | `TomorrowPreviewReceiver.kt:196`（`schedule(context)` 固定"明天"）＋ `BackgroundSync.kt:166-175`（只判开关）＋ `ScheduleViewModel.kt:731`（每次数据变化都调） | 事件入口不看课表就排 22:00：寒暑假/周末里，**每次改课表、每次开机、每次改时间**都会重新武装一次必然空转的 22:00 精确闹钟（醒来查 4 张表 + 跑一遍 121 天×全课程搜索，然后链条停下）。广播里那条 `nextPreviewDay` 搜索（`:84`）只在"真正读过课表"的路径上生效，事件入口绕过它 | 假期里每次数据变更后省 1 次精确唤醒（含 4 次查库 + 一次 121×N 搜索）。学期内为 0 | 低：事件入口复用 `nextPreviewDay`/`hasPreviewContentOn`；代价是事件入口要多读一次课表（本来就在同一协程里） |
| **P3** | `TomorrowPreviewReceiver.kt:199`+`:231`、`BackgroundSync.kt:177-180`+`:240` | 取消路径仍是"用 `FLAG_UPDATE_CURRENT` **造**一个 PendingIntent 再去 cancel"——同一反模式第 1 轮已在 `ReminderScheduler.kt:170-178` 与 `ClassProgressScheduler.kt:403-409` 修掉并各留了一段注释说明为什么 | 可忽略（一次 binder + 一条 PI 记录）；**但它是"两处同名不同实现"那种事故的近亲**，收掉口径一致性 | 极低：改成 `FLAG_NO_CREATE` + 判 null 的私有 helper，与已修的两处对齐 |

**没有 P0。** 特别复核过 2026-09-17 那条 action 常量事故的残留：
`ClassProgressScheduler` 现在只引用 `ClassProgressReceiver.ACTION_START / ACTION_END`
（`:398`、`:408-409`、`:439`、`:446`），本文件内已无自设 action 常量，且 `:132-134` 把
"不要再写回私有副本"钉成了注释。发端与收端比对的是同一个编译期常量，链路是活的。

---

## 2. 逐条取证

### 2.1 P1-① 冷启动后台链：每一次闹钟唤醒都在重跑全套

**调用链（静态闭合，无需设备）**

`Application.onCreate` 里这一段**没有任何触发条件判断**：

```kotlin
// BUAAApplication.kt:23
val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

// BUAAApplication.kt:36, 56-74（节选）
applicationScope.launch {
    step("cancelLegacyPeriodicWork") { ... }
    step("dndSelfCheck")            { ... }
    step("rescheduleReminders")     { BackgroundSync.rescheduleRemindersAndBells(this@BUAAApplication) }
    step("refreshWidgets")          { BackgroundSync.refreshWidgets(this@BUAAApplication) }
    step("scheduleWidgetMidnight")  { BackgroundSync.scheduleWidgetMidnight(this@BUAAApplication) }
    step("scheduleTomorrowPreview") { BackgroundSync.scheduleTomorrowPreview(this@BUAAApplication) }
    step("widgetFallbackWorker")    { WidgetFallbackWorker.ensure(this@BUAAApplication) }
}
```

紧邻的注释写的是设计意图（`BUAAApplication.kt:33`）：

> `// 应用启动/升级（升级会清掉已注册闹钟）：重排提醒并调度零点刷新。`

意图是"启动/升级"，实现是"每次进程创建"。**Android 上广播投递会先实例化 Application 再投递广播**，
所以 §0.1 那 6 条闹钟里的每一条，只要投递时进程不在，就顺带跑一遍上面整块。这不是"可能"，
是 `Application.onCreate` 的语义本身。

**这一次冷唤醒到底付多少**（`refreshWidgets` → `WidgetDataSynchronizer.sync` 的查询数按学期数 N 计）：

| 步骤 | Room 查询 | 跨 binder 调用 | 备注 |
| --- | --- | --- | --- |
| `rescheduleReminders` | 4（`BackgroundSync.kt:62-66`） | 2 条闹钟（cancel+set）+ 1 次 prefs | `getDisplayCourses` 已走 SQL 过滤（第 1 轮 §1.4），不是全表 |
| ↳ `planNextReminder` ×2 | 0 | 0 | **同一份入参算两遍**，见 §2.3 |
| `refreshWidgets` | `3 + 2N`（`WidgetDataSynchronizer.kt:33-62`：`getAllSemesters` + `getCurrentSemester` + 每 key 两查 + `deleteKeysNotIn`） | ≤6（`hasAnyWidgetSafely`，`BackgroundSync.kt:117`） | N = 学期数+1；每 key 一次 JSON encode + upsert |
| ↳ 6 个 Provider `updateAll` | 命中 `WidgetDataCache` 5s TTL（`WidgetDataCache.kt:28`）→ 0 | 6 次 `getAppWidgetIds` + 每实例重绘 | |
| `scheduleWidgetMidnight` | 0 | ≤6（又一次 `hasAnyWidget`，`BackgroundSync.kt:142`）+ 1 条闹钟 | |
| `scheduleTomorrowPreview` | 0 | 1 条闹钟 + 1 次 prefs | |
| `WidgetFallbackWorker.ensure` | WorkManager 自有库（首次 `getInstance` 建库） | ≤6（**第三次** `hasAnyWidget`，`WidgetFallbackWorker.kt:73`） | |

合计一次冷唤醒：**约 `7 + 2N` 次 Room 查询、3 次互不共享结果的 `hasAnyWidget`（每次最多 6 趟
`getAppWidgetIds`，共 ≤18 趟）、`N` 份全量课程列表 JSON 编码，外加广播自己那条链的同一套。**
3 个学期导入的用户 N=4 → 约 15 次查询 + ≤18 趟 binder，而这**一次唤醒里课表一个字都没变**。
（趟数订正于 2026-09-19：`hasAnyWidget` 逐个问的是**六**个 Provider——Today / Tomorrow / Week /
WeekGrid / NextClass / TwoDay，`any { }` 短路时最少 1 趟、最坏 6 趟；本段初稿按 5 个算。）

> **已落地（ai/T10，`cc2b72d` + `a541a31`）**：冷启动这条链现在开头探测**一次**，结论按参数传给
> `refreshWidgets` / `scheduleWidgetMidnight` / `WidgetFallbackWorker.ensure` 三个下游（不是缓存，
> 所以组件真被增删的 `onEnabled` / `onDisabled` 两条路仍各自当场探测）。同一笔账里漏算的一处：
> WorkManager 拉起 `doWork` 的那次唤醒原本最多探测 **5** 次（`doWork` 开头 1 次 + 它调的
> `onDataChanged → refreshWidgets` 1 次 + …），本轮一并收到 1 次。
> **未收的两处**（各 2 次，不在本轮边界内）：`BootReceiver.kt:55-56`、`WidgetRefreshReceiver`
> 的零点/改时间路径；另 `cancelWidgetMidnightIfNoWidgets` 那一次广播里 `hasAnyWidgetSafely`
> 仍被问 2 遍（自己一遍 + `ensure` 默认参数一遍，改前也是 2 遍，不是本轮引入）。

`hasAnyWidget` 这三次重复是可以直接对读证实的：`BackgroundSync.kt:117`（`refreshWidgets` 内）、
`BackgroundSync.kt:142`（`scheduleWidgetMidnight` 内）、`WidgetFallbackWorker.kt:73`（`ensure` 内）。
三处都在同一协程同一时刻附近，中间没有任何数据写入，结果不可能不同。

**与广播链的重叠**：`ReminderReceiver.kt:56-63` 自己就在做 `rescheduleAll`；
`BootReceiver.kt:54-57` 做的是同一套 5 步；`WidgetRefreshReceiver.kt:46-66` 同。
也就是说**开机、改时间、每条闹钟**这三类事件都会出现"onCreate 版 + 广播版"并发跑同一套重建。

> **需 AVD 验（收益量级）**：静态只能证明"工作做了两遍"，无法证明省多少电。
> 用 §4.3 的 `wakelock` 归因（持锁时长）+ §4.4 的本包冷进程启动次数与 `procstats` CPU 累计，
> 对改动前后各测同一待机窗口即可定量。

### 2.2 P1-② 并发重复重排 vs 进程内倒计时归属（事故残留面）

`rescheduleWindows` 里这段是唯一会**主动拆实况**的分支：

```kotlin
// ClassProgressScheduler.kt:274-285
if (window.startMillis > System.currentTimeMillis() &&
    !ReminderNotifications.isCountingDownTo(window.courseId, window.startMillis)
) {
    CourseFluidService.stop(context)
    ReminderNotifications.cancelClassOngoing(context)
    ClassProgressDnd.restore(context)
}
```

判据 `isCountingDownTo` 读的是**进程内两个 `@Volatile`**（`ReminderNotifications.kt:181-198`），
只有 `startLiveWindow(BEFORE_CLASS)` 会写它（`:236-238`）。`ReminderReceiver` 自己的顺序是对的，
注释也写明了为什么（`:234-235` "先记归属再下发"）：先 `startLiveWindow`（`ReminderReceiver.kt:45-51`）
再 `rescheduleAll`（`:56-63`），同一协程内串行，所以它自己的重排看得见归属。

问题在第二条链：onCreate 的 `rescheduleRemindersAndBells` 跑在 `Dispatchers.IO`
（`BUAAApplication.kt:23`）——IO 是**多线程池，真并行**，不是 `Main.immediate` 那种串行队列。
两条链之间没有任何互斥（`ScheduleRepository` 的 `writeMutex` 只护写库，`ScheduleRepository.kt:78`，
闹钟调度侧零锁）。因此存在两个方向的坏交错：

1. onCreate 版**先**跑到 → `isCountingDownTo` 为假 → 走清理分支 → 但此时倒计时还没下发，无事发生（**常见**）；
2. onCreate 版**后**跑到（它前面排着 WorkManager 建库、Room 冷打开、`getAllSemesters`…
   在冷进程+Doze 下完全可能比广播慢）→ 把 `ReminderReceiver` 刚 `startLiveWindow` 贴上去的
   课前倒计时**整条撤掉**，且随后自己 `schedule()` 只排上/下课铃，课前那段实况这一节课不会回来。

交错 2 的用户可见现象，与 2026-09-17 修掉的那次"课前倒计时被自己的重排拆掉"是**同一句现象描述**
（`ClassProgressScheduler.kt:276-278` 的原话："ReminderReceiver 自己触发的重排会在倒计时下发后一秒内
把它拆掉"）。那次修的是**同协程内的顺序**，没修**跨协程的重复执行**。

> **推断，未取证**：交错 2 的实际发生概率我没有设备可测。它需要 onCreate 链恰好晚于广播链的
> `startLiveWindow`。冷进程 + WorkManager 首次初始化 + Room WAL 恢复同时落在 onCreate 链上时最可能。
> 取证办法写进 §4.4：同一次唤醒里 `BUAAApplication` 的 step 日志与 `BUAA-LiveUpdate` 日志
> 若出现"倒计时下发 → 紧跟一次 cancel"的时序，即为实证。
> **修法是消除重复（P1-①），不是给这个分支再加判据**——加判据正是历史上出事的那个方向。

### 2.3 P2 `planNextReminder` 一轮两遍

```kotlin
// BackgroundSync.kt:67-78（节选）
ReminderScheduler.rescheduleAll(context, courses, semester, timeSlots, reminders)   // ① 内部已算 plan
val semesterStart = semester?.startLocalDate
val willRemind = semesterStart != null && courses.isNotEmpty() &&
    ReminderScheduler.planNextReminder(                                            // ② 同一批入参再算一遍
        courses = courses, semesterStart = semesterStart, timeSlots = timeSlots,
        reminders = reminders, now = LocalDateTime.now(), nowMillis = System.currentTimeMillis(),
    ) != null
```

①的内部（`ReminderScheduler.kt:64-71`）算的就是同一个 `planNextReminder`，且 `plan == null`
时 ① 已经走了 `ClassProgressScheduler.cancelAll` 分支。搜索成本见
`ReminderScheduler.kt:231-239`：`for (week in course.weeks.sorted())` × `for (segment in segments)`
× 全部课程，`.minByOrNull` 收口——**每个候选都要展开节次段**（`toPeriodSegments`）。
`MainActivity.kt:238-239` 的注释给了这门课的量级："课程上限 2000"。
搜索成本不是"课数"而是"**(当前周次−1) × 节次段数 × 课程数**"——`nextOccurrence`
与 `nextOrCurrentWindow` 都是周次升序遍历、只在第一个"尚未结束"的窗口处 return
（`ReminderScheduler.kt:231-238`、`ClassProgressScheduler.kt:200-213`），
所以**每个已经过去的教学周都要被重走一遍**。学期中段（第 10 周、40 门课、每课 2–3 段）
≈ 9×3×40 = 上千次窗口构造，两遍就是两千次上下；学期末翻倍。
**而这发生在每一次冷唤醒上**。这是本轮最便宜的一刀。

### 2.4 P2 快照 sync 的 N+1 与全学期重写

```kotlin
// WidgetDataSynchronizer.kt:36-51（节选）
val keys = LinkedHashSet<String>().apply {
    add("current")
    semesters.forEach { add(it.termCode) }          // ← N = 学期数+1
}
db.withTransaction {
    keys.forEach { key ->
        val data = WidgetData(
            semester = semester,
            courses = repository.getDisplayCourses(semester),
            timeSlots = repository.getTimeSlots(),   // ← 循环不变量，每 key 查一次
        )
        dao.upsert(WidgetSnapshotEntity(key = key, dataJson = json.encodeToString(...), ...))
    }
```

`timeSlots` 与 `key` 无关（表就一张节次表，`AppDatabase.kt:28`），却被查了 N 次。
另一半问题不是"查得多"而是"写得多"：一次 `refreshWidgets` 会把**每一个历史学期**的完整课程列表
重新 JSON 化并 upsert，而消费方 `WidgetDataCache.get(context, binding.semesterCode)`
（`WidgetDataCache.kt:65-88`）每次只读一个 key。多学期用户（导入过 3 个学期）在这里
是 4 倍于必要的写盘量。写盘 + JSON 编码正是待机时最不该做的事（闪存与 CPU 双唤醒）。

只写"被组件实际引用的 key"是更好的形状，但要先确认 `WidgetBindingStore` 里所有已绑定 id
的 semesterCode 集合都能覆盖到（含 `null` → `"current"` 的映射）；
只把 `getTimeSlots()` 提出循环是**零语义变化**的第一步。

### 2.5 P2 WakeLock 超时与实际工作量不匹配（漏的是"半套闹钟"，不是"没 release"）

**先给结论：全项目没有一处 acquire 后漏 release 的路径。** 唯一的持锁实现是一份
`inline` + `try/finally`，且带超时自动回收：

```kotlin
// WakeLocks.kt:22, 28-35
timeoutMs: Long = 5_000L,
...
runCatching { lock.acquire(timeoutMs) }
return try { block() } finally {
    runCatching { if (lock.isHeld) lock.release() }   // 超时被系统收回后不再 release
}
```

6 个调用点全部经由它，没有裸 `acquire`，没有 `FULL_WAKE_LOCK`/`SCREEN_BRIGHT_WAKE_LOCK`，
tag 统一 `buaa:schedule:<tag>`（`WakeLocks.kt:26`）。`setReferenceCounted(false)` +
每次新建锁对象 → 嵌套调用（`ClassProgressReceiver.kt:26` 外层 + `:88` 内层）不会互相扣计数。
**这块本轮挑不出问题，写得比多数开源应用好。**

剩下的问题是超时取值：

| 调用点 | 超时 | 包住的活 |
| --- | --- | --- |
| `BootReceiver.kt:36-41` | **显式 10s** | 重排 + 全量刷组件 + 两个闹钟 + 建渠道 |
| `WidgetRefreshReceiver.kt:39-45` | **显式 10s** | 同上量级，注释直说"默认 5 秒不够，会被系统提前收回" |
| `ReminderReceiver.kt:41` | **默认 5s** | `startLiveWindow`（notify+FGS binder）+ `notifyCourse`（可能一次 KeyStore 解密）+ 4 次查库 + 全量重排 |
| `TomorrowPreviewReceiver.kt:41` | **默认 5s** | 4 次查库 + 一次 121 天 × 全课程搜索（`:84` + `:139-142`）+ 续排 |
| `ClassProgressReceiver.kt:26` | **默认 5s** | 见 §2.6（主线程那串 binder） |
| `ClassProgressReceiver.kt:88` | **默认 5s** | 3 次查库 + `planNextClassWindow` 全量搜索 + cancel/schedule |

同一份代码里"5 秒不够"这句判断已经被写下两次（`BootReceiver`、`WidgetRefreshReceiver`），
但最重的两处计算（`TomorrowPreview` 的 121 天搜索、`class_reschedule` 的全量窗口搜索）
反而用默认值。**后果不是耗电，是静默降级**：超时后系统收回锁，剩下的重排跑在随时睡回去的
CPU 上——正是 `WakeLocks.kt:9-12` 写这块要防的那件事，而且没有日志。

改动时同时要回答语义：超时到点后 block 不会中断（`withPartialWakeLock` 不取消协程），
所以存在"闹钟只排了一半"的中间态；本轮不建议动这条，只建议**对齐超时取值 + 加一条
"跑完用时"日志**，为下一轮的判断提供数据。

### 2.6 P2 上课/下课铃广播主体仍在主线程

```kotlin
// ClassProgressReceiver.kt:26, 29-57
WakeLocks.withPartialWakeLock(context, "class_progress") {   // ← onReceive 同步块内
    runCatching {
        when (intent.getStringExtra(EXTRA_ACTION)) {
            ACTION_START -> {
                ClassProgressScheduler.scheduleEnd(context, end)          // binder: AlarmManager
                if (classProgress) ReminderNotifications.startLiveWindow(...) // binder: NotificationManager + startForegroundService
                ClassProgressDnd.enter(context, end)                      // binder: 读权限 + setInterruptionFilter + prefs 写
                WidgetCommon.requestLiveRefresh(context)                  // 内部起协程，这步不阻塞
            }
```

只有 `ACTION_END` 的 DB 部分另外起了 `goAsync()` 协程（`:82-99`）。
同一个仓库里 `ReminderReceiver.kt:34-37` 已经把这条判断写得很清楚，并据此把自己的
`notify` + `SpocSession.hasSession()` 挪进了 `goAsync()` 之后：

> `// 闹钟唤醒常是冷进程，这笔 binder + 解密全落在广播主线程上就是在吃 10 秒配额，`
> `// 超配额系统直接掐广播 —— 用户看到的"这一节课没有提醒"就是这么来的。`

上/下课铃那条链是同一类工作（甚至更多：FGS 启动 + 勿扰 binder），却留在了主线程同步块里。
**顺序约束**：`scheduleEnd` 必须是该分支第一个（`ClassProgressReceiver.kt:39-41`，R5 F-31 的理由），
搬迁时不能改变这一点。

### 2.7 P2 事件入口的明日预告不看课表

```kotlin
// TomorrowPreviewReceiver.kt:192-196
/**
 * 事件入口用的默认对齐：按"明天 22:00"排。
 * 只有真正读过课表的那条路（[TomorrowPreviewReceiver]）才知道下一个值得醒的日子。
 */
fun schedule(context: Context) = schedule(context, LocalDate.now().plusDays(1))
```

调用点 `BackgroundSync.kt:166-175` 只判 `PREF_ENABLED`。触发方：`BootReceiver.kt:57`、
`WidgetRefreshReceiver.kt:64`、`ScheduleViewModel.kt:733`（**每次数据变化**）。
KDoc 自己承认了这条链的判据在广播侧（`TomorrowPreviewReceiver.kt:79-96`：`nextPreviewDay`
→ `fireDay` → `hasPreviewContentOn` 复核）。于是寒暑假里每改一次课表就重新武装一次
22:00 精确闹钟：响 → `postTomorrowPreviewIfAny` 查 4 张表 → 无可推内容 →
`nextPreviewDay` 在 121 天里找不到 → 不续排 → 链停。**一次白醒 + 一次全量搜索，每次数据变更攒一发。**

注意这是"必要的重排"与"空转重排"的分界样本：广播内部那条续排已经做了正确的内容判定
（第 2 轮 §4.1 的成果），漏的是事件入口这一半。修法：入口也走 `nextPreviewDay`，
代价是多一次已经在那条协程里付得起的查库。

### 2.8 P3 取消路径的"先造再取消"

第 1 轮在两个地方修掉过同一反模式，并各留注释（`ReminderScheduler.kt:170-173`、
`ClassProgressScheduler.kt:403-407`）。还剩两处没跟上：

```kotlin
// TomorrowPreviewReceiver.kt:198-200 + :223-233（pendingIntent 用 FLAG_UPDATE_CURRENT）
fun cancel(context: Context) {
    context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
}
// BackgroundSync.kt:177-180 + :232-241
fun cancelWidgetMidnight(context: Context) {
    context.getSystemService(AlarmManager::class.java)?.cancel(midnightPendingIntent(context))
}
```

`cancelWidgetMidnight` 的调用点是 `cancelWidgetMidnightIfNoWidgets`（`:183-193`），
跑在 6 个 Provider 的 `onDisabled` 上——**"拖掉最后一个桌面组件"这个动作**每次都会造一个
永不使用的 PendingIntent 记录。收益可忽略，价值全在口径一致性：留着两处反例，
下一轮就有人会照着它再写第三处。

---

## 3. 看起来该改、但本轮判断**不该改**的项

这一节与 §1 同等重要。以下每一项都有"表面理由"，逐条给出否决依据。

| 项 | 表面理由 | 为什么不改 |
| --- | --- | --- |
| `setAlarmClock` 用于上课铃（`ClassProgressScheduler.kt:349`）与勿扰看门狗（`ClassProgressDnd.kt:159`） | `setAlarmClock` 是 Doze 完全豁免档，状态栏还常驻闹钟图标，看着就像最耗电的那一档 | 这两条的**全部意义就是对抗省电策略**：前者是"用户亲自设定的闹钟"语义，后者一被吞就是"整机静音一整天"。第 2 轮 §4.5 已就同一问题给过结论（"不以省电为名削弱可靠性"），本轮复核认同。而且看门狗只在 `enter()` 真的静音成功时才排（`ClassProgressDnd.kt:82`），下课 `restore()` 成功后立刻 `cancelWatchdog`（`:113`）——**正常一节课不会留下额外唤醒**，这个设计是自洽的 |
| 每门课一个闹钟？ | 典型课表 30 门课，直觉上应该有一堆闹钟 | 实测**不存在**：`ReminderScheduler` 只排 `minByOrNull{triggerAtMillis}` 一条（`:165`、`:101`），课堂铃同理固定两个 requestCode 复用（`:136-137`）。稳态 ≤6 条（§0.1）。这里已经是最优形状，**不要为了"批处理"去合并**——AOSP 对第三方 `setExact*` 本来就不做对齐合并，合并反而会破坏"提前 10 分钟"的语义 |
| 无 `isDeviceIdleMode` / Doze 自适应判断（全仓库 0 命中，本轮已 grep 确认） | 常规省电清单会要求"进 Doze 时跳过非关键刷新" | 本项目的后台任务**全部是闹钟驱动**，不是轮询；闹钟能投递到就已经穿过了 Doze 闸门（`*AndAllowWhileIdle` 档位）。在此之上再加 `isDeviceIdleMode` 判断，只会得到"Doze 里不重排 → 提醒链断"，与 P1-① 相反的失败方向。**唯一适合做这种判断的是零点组件刷新**，而它已经是非精确档（`BackgroundSync.kt:155`），系统本来就会自己合并延后 |
| WorkManager 12h 兜底（`WidgetFallbackWorker.kt:77`）+ `KEEP` 策略 | 待机清单上"每 12 小时醒一次查一堆表"很像浪费；有人会顺手改成 `UPDATE` | 两条都不该动。(a) 它是 HyperOS 类 ROM 清掉第三方精确闹钟后**唯一的恢复路径**（`WidgetFallbackWorker.kt:16-27` 记着原委）；(b) `KEEP` 正是防止"每次 `enqueue` 重置计时"的正确选择——本项目 `bootstrapBackgroundSync` 每次组件广播都会调 `ensure()`（6 个调用点：`WidgetCommon.kt:133/356/519/633/875` + 5 个 `onEnabled`），若换成 `UPDATE`，60 秒闸门挡不住"用户一直用桌面"的情形，周期任务会被反复重置成**永不触发**。这条是本轮专门复核过的**好消息** |
| `SpocSession.hasSession()` 落在闹钟路径上（`ReminderReceiver.kt:132`） | AndroidKeyStore 每次 `load()` 都要 `KeyStore.getInstance+load(null)+getEntry`（`KeystoreBlobStore.kt:65-67`，2 趟 keystore daemon binder），待机清单上属于"闹钟里别碰密钥库" | 两个短路已经把它挡住了：(a) `spocSignHintEnabled(context) && hasSession()` 的 && 顺序（`ReminderReceiver.kt:132`）——该开关默认 `false`（同文件 `:79`），关着时**根本不解密**；(b) `SpocSession` 有 `loadedFromDisk` 进程内缓存（`SpocSession.kt:82`、`:95`），冷进程内至多解密一次。要再优化就是加磁盘明文缓存，属于拿安全性换一次 binder，**不值** |
| 每次唤醒重排闹钟时 `cancel()` 再 `schedule()`（`ClassProgressScheduler.kt:262`、`286`） | 表面看就是"取消旧 job 再排新 job"的反面教材，想加"窗口没变就跳过" | 跳过判据要求把"当前已排的窗口"存进可比较状态，而这块刚因为**两份同值不同名的状态**出过一次事故；并且 `PendingIntent.cancel()` 后再 `getBroadcast` 是系统推荐做法，单条 alarm 的 set 成本约一次 binder，**远小于**它上面那两条重复链（P1-①）。先把重复链消掉，再回来评估这条要不要加判据 |
| 组件 `updatePeriodMillis`（6 份 `res/xml/*_widget_info.xml` 全是 `0`） | 组件不自己轮询，那要不要给"下一节课"组件加个 30 分钟周期刷新？ | 不加。翻面点已经由上课铃/下课铃的 `requestLiveRefresh` 精确驱动（`ClassProgressReceiver.kt:57/65` → `WidgetCommon.kt:502-507`，且刻意只重绘 3 个与"此刻"有关的组件、绕开 `refreshWidgets`，注释在 `:491-501`）。加周期刷新等于把 6 条闹钟之外的第 7 条唤醒源装回来，而它解决的还是已经被解决的问题 |
| `planNextClassWindow` 每周次×每段全展开（`ClassProgressScheduler.kt:200-216`） | 想改成"从今天所在周开始二分"之类，砍掉搜索 | 纯函数、有单测钉住（`ClassProgressWindowTest`）、且语义要求"含正在上的这一节 + 跨节次段"。单次成本是亚毫秒级（§2.3 推的量级：上千次迭代 + 同数量级的 `LocalDateTime` 分配），**真正的问题是被调用的次数**——一次冷唤醒里 `planNextReminder` 两遍 + `planNextClassWindow` 一遍，两条重复链再各乘一遍。优化单次成本会把刚钉过的窗口语义推回风险区，应该先消次数 |

---

## 4. 度量方案（无 root、adb-only，模拟器可执行）

**目标**：给 §1 的每条改动一对可复现的前后数字。所有命令在**宿主机 Git Bash** 里执行，
不需要 root，不需要 `adb shell su`。`<PKG>` = `com.buaa.schedule`，`<UID>` 用第 0 步取。

### 4.0 准备：取 UID、模拟"真待机"的电源与屏幕状态

```bash
# 0.1 取应用 UID（后面所有 --checkin 行的第 2 列就是它）
adb shell dumpsys package com.buaa.schedule | grep -m1 userId=

# 0.2 关键：把模拟器从"恒定充电"改成"放电"，否则 Doze 永远进不去、
#     JobScheduler/WorkManager 的充电约束被立刻满足 —— 测出来的曲线和真机无关
adb shell dumpsys battery unplug
adb shell dumpsys battery set level 60
adb shell dumpsys battery set ac 0
adb shell dumpsys battery set usb 0

# 0.3 关屏幕并确认真的熄了（后面任何一步前都该复核 mWakefulness=Asleep）
adb shell input keyevent 26
adb shell dumpsys power | grep -E "mWakefulness=|Display Power"

# 0.4 清表，开始计量（reset 必须在熄屏之后，否则把熄屏本身的开销记进来）
adb shell dumpsys batterystats --reset
```

测完复原（**必做**，否则模拟器永远显示"未充电且电量 60%"）：

```bash
adb shell dumpsys battery reset
```

### 4.1 闹钟面盘点：数的是"排队中的闹钟条数"，不是累计

`dumpsys alarm` 分两段：上半 `Current alarm queue:` 是**此刻还挂着**的闹钟，
下半按 `u0aNNN:` 分段是**投递历史**。P1 改动只应影响上半的条数与下半的增速。

```bash
# A. 排队条数 —— 期望值 = §0.1 那张表的 ≤6。数的是"含本包类名的行"的条数
adb shell dumpsys alarm | awk '/Current alarm queue:/,/Total num/' \
  | grep -c "com.buaa.schedule"

# B. 逐条看档位与下次触发时刻（RTC_WAKEUP / setAlarmClock 会显式标注；
#    重点核对有没有 setInexactRepeating 或多条同 receiver 的排队条目）
adb shell dumpsys alarm | awk '/Current alarm queue:/,/Total num/' \
  | grep -B1 -A6 "com.buaa.schedule"

# C. 历史投递数：两次采样做差 = 这段时间内系统真正为本包唤醒了几次
#    （这是 §4.3 之外的第二个独立计数，两者应当同量级，差得远说明有唤醒没走到投递）
adb shell dumpsys alarm | grep -A12 "u0a<UID>:" | grep -E "total=|delivered="

# D. 看门狗闹钟是否如约被取消（上课→下课后应重新回到 ≤6 条）
adb shell dumpsys alarm | grep -c "ClassProgressReceiver"
```

**改前/改后各跑一遍 A**：条数应该都不变（P1-① 不减唤醒次数）。若改后 A 变多，说明新的
触发条件把某类闹钟漏排了，直接回退。**A 是这条链路的"没改坏"守卫，不是收益指标。**

### 4.2 JobScheduler / WorkManager：本项目只有 1 条周期任务

```bash
# 本包名下的 job 条目（WorkManager 的 system job scheduler 后端）。
# 数的是"出现本包名的 job 描述行"，稳态期望 1 条 pending（widget_fallback_refresh）
adb shell dumpsys jobscheduler | grep -i -B2 -A8 "com.buaa.schedule" | head -80

# 只数条目，用 WORK_NAME 的落盘形式（WorkManager 会把 unique name 前缀成 SystemJob#<id>，
# 所以按包名计数而不是按任务名）
adb shell dumpsys jobscheduler | grep -c "com.buaa.schedule"

# 确认周期任务的 next-fire 没有被反复重置（KEEP 策略的直接证据）：
# 连测两次间隔 >5 分钟，last-failed/next 时间戳应单调前移而不是回跳
adb shell dumpsys jobscheduler | grep -A14 "com.buaa.schedule" | grep -E "interval|deadline|latency"
```

**这一项是 P1-① 的验收点之一**：`ensure()` 每 60 秒被组件广播最多调一次（`WidgetCommon.kt:53`），
但 `ExistingPeriodicWorkPolicy.KEEP`（`WidgetFallbackWorker.kt:76`）意味着它不重置计时。
若改后 `dumpsys jobscheduler` 里本包 job 的下次触发时间反复回跳，就是有人把 `KEEP` 改成了 `UPDATE`。

### 4.3 强制进入待机并统计唤醒次数（本项目最好用的一组数）

```bash
# 1. 立刻进 Doze（不等系统自适应时间常数；模拟器上必须用 force，否则可能要等几十分钟）
adb shell dumpsys deviceidle force-idle
adb shell dumpsys deviceidle get deep          # 确认 mState=IDLE / 当前在 deep idle

# 2. 待机窗口：建议固定 30 分钟（模拟器 Doze 的 maintenance window 会在此期间开合若干次，
#    每个 maintenance window 都是一次真实唤醒，正是要数的东西）
sleep 1800

# 3. 取数后再解除，避免影响后续测试
adb shell dumpsys deviceidle unforce

# --- 唤醒归因：本项目所有 WakeLock 都带 "buaa:schedule:<tag>" 标签 ---
# 数的是"每种持锁名各醒了几次"。tag 直接对应到代码位置：
#   reminder_show_and_reschedule -> ReminderReceiver.kt:41
#   class_progress / class_reschedule -> ClassProgressReceiver.kt:26 / :88
#   tomorrow_preview -> TomorrowPreviewReceiver.kt:41
#   widget_refresh -> WidgetRefreshReceiver.kt:39
#   boot_rebuild -> BootReceiver.kt:36
# 这一条是 P1-① 与 P2(§2.5) 的主要验收数：改后同一 tag 的"次数"应持平，
# 而"每次持锁时长"下降 —— 持锁时长才是真正耗电的那个乘数。
adb shell dumpsys batterystats | grep -A6 "buaa:schedule"
```

**按字段名而不是列号取 checkin 列**（AOSP 会调整 `--checkin` 的列序，但会把字段名本身写进每行）：

```bash
# 唤醒原因直方图（wake_reason 列的后一列是原因名，如 alarm*:time / -1*:sync）
adb shell dumpsys batterystats --checkin \
  | awk -F, '{for(i=1;i<=NF;i++) if($i=="wake_reason") print $2" "$(i+1)}' \
  | sort | uniq -c | sort -rn | head -20

# 本包的 wakelock 行（第 2 列 = UID；先跑一次基线确认本机构的 wakelock section 号，
# 再固定它做前后差 —— 不要跨版本硬编码列号）
adb shell dumpsys batterystats --checkin > after.txt
adb shell dumpsys batterystats --checkin | grep "u0a<UID>" | grep -i wakelock

# 组件作业与广播投递计数（alarm → 广播是一一对应的，这里是第二个独立证据）
adb shell dumpsys batterystats --checkin | grep "u0a<UID>" | grep -iE "broadcast|alarm"
```

```bash
# 一次性导出全部人读报表，改前/改后各存一份再 diff（最省事的回归法）
adb shell dumpsys batterystats > standby.after.txt
diff standby.before.txt standby.after.txt | grep -E "^[<>].*(Wake|wakelock|alarm|Job)"
```

### 4.4 冷进程启动次数：P1-① 的直接指标（不需要改代码）

P1-① 不减少唤醒**次数**，它减少"每次唤醒被要求重跑全套重建"的**进程数**。
正确的可观测点是**进程冷启动次数**——因为 `BUAAApplication.onCreate` 那条链只在冷启动时跑，
一次冷启动 = 一整套重建。

⚠️ **不要用应用日志时长来量**：本轮复核确认这条链的**成功路径一条日志都不打**
（`BUAAApplication.kt:44-48` 的 `step()` 只在 `onFailure` 里 `Log.w`，六个 step 成功时全静默；
`BackgroundSync` / `ClassProgressScheduler` 的 `Log.w` 同样只在失败分支）。
想按日志跨度量单次唤醒，得先加一条 trace 级留痕——那是代码改动，本轮不做，列在 §4.7。

**用事件缓冲区，adb-only、无需 root：**

```bash
adb logcat -c
# am_proc_start 是 ActivityManager 写进 events 缓冲区的固定 tag。
# 最后一列 reason 是重点：for broadcast / for service / for content provider
# —— "for broadcast" 的条数就是 §2.1 说的那件事：某条闹钟把进程从零拉起了 N 次，
# 每一次都附带整套 onCreate 后台重建。
adb logcat -b events -v epoch > proc.log &
sleep 1800; kill %1

# 本包冷启动总次数（P1-① 的核心数：改前/改后同一待机窗口应当**下降**或持平，
# 若改后不变而 §4.3 的持锁时长也没降，说明收窄触发条件没生效）
grep "am_proc_start" proc.log | grep -c "com.buaa.schedule"

# 按拉起原因分桶：看有多少次冷启动是为了一个只做后台重排的广播
grep "am_proc_start" proc.log | grep "com.buaa.schedule" \
  | sed -E 's/.*reason=([^ ]*).*/\1/' | sort | uniq -c | sort -rn

# 人读对照（同一条信息的另一种取法，两者应一致）
grep -E "Start proc.*(com\.buaa\.schedule)" proc.log
```

**每次冷启动付了多少 CPU**（`procstats` 按进程聚合，比 batterystats 的列序稳定）：

```bash
# 待机窗口结束后取；重点看 com.buaa.schedule 的 CPU 累计与"被启动次数"
adb shell dumpsys procstats --hours 3 | grep -A6 "com.buaa.schedule"
```

**唯一可直接用于 §2.2 取证的应用日志**：实况下发路径上有一条无条件 `Log.d`
（`ReminderNotifications.kt:465`，tag `BUAA-LiveUpdate`，由 `postClassOngoing` 的 `:306`
与 `CourseFluidService` 的 `:211`/`:243` 调用）。它够用，因为 §2.2 那个坏交错的表现
正好落在实况下发上：

```bash
adb logcat -v epoch -s BUAA-LiveUpdate:D > live.log &
sleep 1800; kill %1
# 交错 2 的实证形态：同一次唤醒内先出现 postClassOngoing / fluidService 的下发记录，
# 紧随其后又出现一次"同一节课"的下发或一段实况消失（配合 §4.1-D 的闹钟条数变化）。
# 出现一次即为实证；一晚没有出现，就把 §2.2 的判定从"推断"降级为"未见复现"。
grep -E "postClassOngoing|fluidService" live.log
```

### 4.5 为什么这些数在模拟器上只能做相对比较

1. **没有真实射频功耗模型**：待机耗电的真机大头是 modem/Wi-Fi 唤醒与信号搜索，
   模拟器恒"满格已连接"，`batterystats` 的 discharge 电流表是按真机硬件标定的，
   在这套虚拟传感器上算出的 mAh/百分比是**外推值**，不是测量值。
2. **CPU 频率与 idle 状态被拍平**：`/sys/devices/system/cpu/.../scaling_cur_freq` 在模拟器上
   不反映真机的 DVFS 与大小核迁移，而"一次唤醒多花 200ms"在真机上体现为进不了深度 idle，
   模拟器上没有这条曲线。所以 §4.4 的**时长差**可信，**电流读数**不可信。
3. **Doze 时间常数没有意义**：`force-idle` 直接跳过了真机上"不活动 → light → 深度"的几十分钟
   自适应，还改变了 maintenance window 的节奏；`dumpsys battery unplug` 只是伪造状态，
   不会真的按电量降频。**只有"同一台模拟器、同一份待机脚本、只换被测 APK"的差值有效。**
4. **WorkManager/JobScheduler 约束语义不同**：模拟器恒"插着 USB"除非手工 `unplug`；
   真机待机时 `requiresCharging`/`requiresDeviceIdle` 会显著推迟 job，模拟器上不会。
   这是 §4.2 必须配 `dumpsys battery unplug` 的原因。
5. **厂商省电策略是这套架构最大的变量，而模拟器上没有**：`WidgetFallbackWorker.kt:16-19`、
   `ClassProgressDnd.kt:22-27` 整条链设计来对抗澎湃/MIUI 清闹钟与杀后台。
   模拟器上这些**永远不会触发**，所以"砍掉兜底链是否省电"这个问题在模拟器上
   一定得到错误答案。§3 那几条否决全部依赖真机，不依赖这里的数字。

因此本轮建议的用法：模拟器只用于**回归"没有变差"**（§4.1 条数、§4.2 job 不重置、
§4.3 wakelock 次数不升）+ **相对比较工作量**（§4.3 持锁时长、§4.4 唤醒跨度）。
任何绝对续航结论要落到一台真机（同一台、同一账号课表、飞行模式、亮屏关闭 8 小时），
由 orchestrator 执行。

### 4.6 基线采样清单（交给你跑，一轮就够）

| 序号 | 命令 | 取的数 | 用在哪条改动的验收 |
| --- | --- | --- | --- |
| M1 | §4.1-A | 排队闹钟条数（期望 ≤6） | 全部改动的"没改坏"守卫 |
| M2 | §4.1-C 两次采样差 | 本包被投递次数 | P1-②、P2 §2.7 |
| M3 | §4.2 第 2 条 | 本包 job 数 + 下次触发时刻 | WorkManager `KEEP` 复核 |
| M4 | §4.3 wakelock 表 | 每 tag 次数 × 平均持锁时长 | **P1-① 主指标**、P2 §2.5 |
| M5 | §4.4 `am_proc_start` 计数 + `procstats` | 本包冷进程启动次数 / 每次的 CPU 累计 | **P1-① 直接指标** |
| M6 | §4.4 `BUAA-LiveUpdate` 日志 | 实况下发时序（拆掉倒计时的实证） | P1-② |
| M7 | §4.3 `wake_reason` 直方图 | 唤醒原因构成 | P2 §2.7 |

### 4.7 做不了、需要先改代码的一项（本轮明确不做）

唯一拿不到的数是"**同一次唤醒里 onCreate 链与广播链各跑了几遍**"——这是 P1-①/②
最直接的证据，但成功路径无留痕（§4.4 已说明）。要量它必须先在 `BackgroundSync`
与 `BUAAApplication.step` 的成功路径上各加一条 `Log.d`（含一个进程内递增的"本轮序号"），
这属于代码改动，与"只审计不动手"冲突，**留给实施那一轮随改动一起加**，
并作为该改动的回归探针（改后同一唤醒序号应从 2 降到 1）。

---

## 5. 本轮验证与边界

- **未改任何业务代码**：本轮唯一新增文件是本 `docs/` 文档，无 Kotlin 改动、无 Gradle 改动。
- 未跑 `:app:testDebugUnitTest` 与 `:app:lintDebug`：**没有新增单测，也没有可被 lint 影响的源文件**。
  §2.3 / §2.4 两条若要落代码，配套纯函数单测应在**实施那一轮**随改动一起提交（本文档不预置空测试）。
- 接口契约零变化（本轮无签名改动）。
- 禁改清单未触碰：`ui/**`、`core/designsystem/**`、`app/build.gradle.kts`、`benchmark/**`、
  `settings.gradle.kts`、`gradle/**`；未读取 `local.properties`；未触碰 `ai/T2` worktree。
- 设备侧一律未执行：本文所有 `adb` 命令均为**交给 orchestrator 的脚本**，本轮未运行任何 adb。
