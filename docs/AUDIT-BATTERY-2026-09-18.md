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
| **P1** | `BUAAApplication.kt:36`、`:58-74`；放大点 `BackgroundSync.kt:112-126`、`:139-161`、`WidgetFallbackWorker.kt:71-73` | 每次冷启动进程无条件重跑整套后台链；而**每一个闹钟都把进程冷启动**（6 条闹钟 × 每日多次），于是"数据没变的唤醒"付两遍全量重排 + 全学期快照重写 + ≤18 次 Launcher binder 往返 | 单次冷唤醒的后台工作量：重复的那一半（约 7+2N 次查询、N 份快照写盘、≤12 趟 Launcher binder）**是可证明的净多余**；折算成功耗降幅 **推断，未取证**（需 §4.3/§4.4 前后对测）。待机总唤醒次数不变，降的是每次唤醒的 CPU 占空比与闪存活动。**2026-09-19 进度**：组件探测那一份已由 ai/T10 收成一次探测（≤12 趟已省掉）；"整套重排重跑"已由 ai/T13 收成三把钥匙闸门、ai/T14 再把闸门看不见的课堂铃兜底续排失败接进 `failures` 清单（见 §2.1 两段"已落地"）；**§4.4 已测**（见 §4.4-b：10 次冷启动里整链重建 10→1、单次 CPU 2551→2133 ms，−16.4%，口径 = 应用内提醒 + 组件 0 绑定）；剩下的账是 §4.3 的唤醒次数与真机乘数 | 中：幂等补注册（ROM 吞广播的自愈路径）与"升级后重建"依赖这条链，收窄触发条件必须保留事件式兜底，不能简单删 |
| **P1** | `ClassProgressScheduler.kt:274-285`（破坏性分支）＋ `ReminderNotifications.kt:181-198`（判据是进程内 `@Volatile`）＋ `BUAAApplication.kt:23`（`Dispatchers.IO`，真并行） | onCreate 那条链与广播自己那条链并发跑同一次 `rescheduleWindows`；前者的"这是下课铃被吞的遗留"判据读的是进程内状态，**可能在课前倒计时已下发之后**才跑到，于是把刚上岛的倒计时停掉 | 直接收益小（省一次通知重下），**但这是 2026-09-17 事故的同类残留路径**，修掉 P1-①（重复链）后本条随之消失 | 高：动的是曾经拆掉过课前倒计时的同一段代码；建议**只通过消除重复链来间接修**，不要给 `rescheduleWindows` 加新分支。**2026-09-19 补**：另有一条**不依赖并发**的确定性同型抖动（同一处 `cancelAll` 被更早的一步抢先调用），见下一行与 §2.9 |
| **P1** | `ReminderScheduler.kt:83-89`（`plan == null` 分支无条件 `ClassProgressScheduler.cancelAll`）＋ `ClassProgressScheduler.kt:321-325`（`setAlarmClock` 排已过时刻的上课铃 → 立刻投递） | 课前提醒全关的用户，只要此刻正在上课，**每一次重排**（下课铃续排 / 冷启动 / 开机 / 改时间）都会：勿扰被恢复 → 记录与看门狗被抹 → 5 秒后被一次"多出来的上课铃"重新 `enter()`。模拟器实测三次同型（§2.9 日志） | 每轮多一整趟上课铃副作用链（notify + startForegroundService + setInterruptionFilter + prefs 落盘）；折算电流未取证。**真实危害不是功耗**：那次过期闹钟被 ROM 吞掉时，这一节课的勿扰永久进不去且无自愈入口（记录已清，`selfCheck` 不进门） | 低-中：只删"抢先的那一份"清理，判据仍归 `rescheduleWindows:280-291` 唯一实现；两个课堂开关都关时必须保留 `cancelAll`（那条路 `rescheduleNextWindow:308` 会早退，没人接手） |
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

> **已落地（ai/T13，`177ac08` + 本枚 fix(app)）**：上面那句"**没有任何触发条件判断**"现在有了判据。
> `BUAAApplication` 那条链里的 `rescheduleReminders` 与组件四步两步合成一步
> `step("coldStartRebuild")`，整条交给 `ColdStartRebuild.run(context)`（`BUAAApplication.kt:65-67`）。
> **三把钥匙是与关系**，任何一把不确定就照旧全跑（判据本体 `ColdStartRebuild.kt:263-282`）：
> ① `versionCode` 与上次"整链成功"记下的那个不同 ⇒ 重跑 —— 这才是 `BUAAApplication.kt:33`
> 那句注释（"升级会清掉已注册闹钟"）的原始意图，实现从"每次进程创建"被拉回"启动/升级"；
> ② 我们自己的闹钟确实还挂着 —— `PendingIntent.FLAG_NO_CREATE` **只查不造**，探到"不在"⇒ 重跑；
> ③ 距上次成功 ≥ 24 小时 ⇒ 无条件重跑，前两条都不再评估（`RERUN_AFTER_MILLIS`，
> `ColdStartRebuild.kt:157`），这条同时给"跳过"一个硬上限，兜住"整链被掐死、不再自我传播"。
>
> 钥匙 2 的探测对象**按人群分别选**（`ColdStartRebuild.kt:301-313`），人群不查库，
> 用的是上一次成功重排留下的结论 —— `rescheduleReminders` 那个早已存在的布尔恒等于
> "本轮排上了一条课前提醒闹钟"（`BackgroundSync.kt:84-118`，`rescheduleRemindersAndBells`
> `:137-148` 把它透出来）。三类人逐个回读过自愈链：开着课前提醒的人探课前提醒那头
> （`ReminderScheduler.hasPendingReminder` `ReminderScheduler.kt:315-324`），因为
> `ReminderReceiver.kt:56-63` 每次投递都重跑 `rescheduleAll` ⇒ 链自我传播，自愈窗口 = 下一条提醒；
> 课前提醒全关的人**必须**探到课堂铃在排（`ClassProgressScheduler.hasPendingClassBells`
> `ClassProgressScheduler.kt:399-405`）—— 这类人下课铃 `ACTION_END` 必定续排下一个窗口
> （`ClassProgressReceiver.kt:71-78` → `:98-110`），那条也断了就只剩 `widget_fallback_refresh`，
> 周期**实测 12 小时**（`WidgetFallbackWorker.kt:85`，登记条件"有组件 **或** 提醒走应用内闹钟"
> 在 `:81`，所以这类人确实在册），窗口长到这个量级，因此不接受"另一头还挂着"当替代；
> 切到「系统日历提醒」的人应用内一个闹钟都不排（`BackgroundSync.rescheduleReminders` 的清理分支），
> 钥匙 2 恒判"不在" ⇒ 每一次冷启动照旧全跑，**与改动前逐字一致，没有为这类用户开例外**。
>
> 钥匙 2 的口径缺口写进 `ColdStartRebuild` 的类注释（`ColdStartRebuild.kt:50-76`）：
> `ClassProgressScheduler.kt:409-413` 记着一条真机实测的反向事实 —— 只 `alarmManager.cancel()`
> 之后那条 PI 记录**仍可被 `FLAG_NO_CREATE` 查得到**（PI 被应用侧引用钉住），所以"探得到 PI"
> 逻辑上不等于"闹钟还在排"。在冷启动这个时点可以接受：判据跑在 `Application.onCreate`，
> 那个方法只在**进程创建**时运行一次，钉住记录的那次 `getBroadcast` 发生在已死的上一个进程里。
> 顺带订正本仓库自己的一处形状：`ReminderScheduler.cancelAll`（`ReminderScheduler.kt:307-313`）
> 就是"只 `alarmManager.cancel()`"那一形，课堂铃那条才补了 `PendingIntent.cancel()`
> （`:414-421`）—— 本卡未越界去改前者。真误判了付的是"白跳过一轮"，24 小时这条上限必然到期，
> 比它更早到的是那条 12 小时兜底 Worker。**残余（只能真机证伪）**：通知栏里仍挂着的课前倒计时
> 通知同样持有那条 PI 记录，是"探得到而闹钟不在排"唯一看得见的具体形态。
>
> 指纹（versionCode + 上次成功时刻 + 那轮排上的闹钟头）落 `schedule_settings`，
> **不开 Room 表、不加迁移**（这条链本来就在读那个文件，零次额外磁盘打开）；
> 只有整链干净跑完才写，任一步抛了不写，**跳过的那一轮同样不写**（否则 24 小时上限永不到期，
> `ColdStartRebuild.kt:245-250`）。留在闸门**外面**照旧每次跑：`cancelLegacyPeriodicWork`
> （它一被跳过，被退役的 legacy 轮询下一次唤醒仍会被判成"无事可做"，是个自我闭合的死循环）、
> `dndSelfCheck`（勿扰的唯一自愈入口，ai/T12 刚把开机那条改走它），
> 以及本来就在 `launch` 之前的 `Personalization.load` / `ensureChannels`。
>
> §2.2 那半按本审计自己的话办：**没有给 `rescheduleWindows` 的清理分支加一个字、加一条判据**，
> 而是把"onCreate 版 + 广播版"重复执行里属于冷启动的这一份在多数唤醒里直接省掉；
> 形状由 `ColdStartRebuildWiringTest.coldStartChainHasExactlyOneDriverRepoWide` 钉住
> （全仓库 `ColdStartRebuild.run(` 恰一处，重建整链的调用点集合仍是广播侧那三处 + 闸门一处）。
> 交错 2 的概率仍**未取证** —— 跳过只是让它不再常见，不是让它不可能。
> 门禁：新增 23 条判据单测（三把钥匙各自正反例、24h 边界、任一步抛 ⇒ 不写时间戳、跳过不续期、
> 四类人群口径、2×2×2 真值表）+ 6 条生产接线形状守卫。
> **§4.4 那笔账已还（2026-09-19，见 §4.4-b）**：这里说的"同一待机窗口前后各测一次"事后证明**量不到**
> —— 闸门只在进程创建那一瞬判定，被动窗口没有分辨度，实测换成脚本化冷启动各 10 次。
> 结论：10 次冷启动里整链重建 **10 → 1**，单次 CPU **2551 → 2133 ms（−16.4%）**；
> 人群口径是"应用内提醒 + 桌面组件 0 个绑定"，挂着组件的用户差值更大（同一节里那条边界）。

> **已落地（ai/T14，`a9ec26a`）**：ai/T13 自己在两处注释里登记的那条残余收掉了 ——
> 课堂铃兜底续排（`ClassProgressScheduler.rescheduleNextWindow`）过去整体裹在 `runCatching` 里，
> 闸门看不见它的失败，那一轮仍被记成"成功"并写下指纹。
>
> **为什么旧的"方向安全"论证不够**（登记处的原话是：闹钟没排上就是排不上，
> 下一次冷启动钥匙 2 当场探"不在"→ 整链重跑）：它只在**铃确实被撤了却没排上**这半边成立。
> 而 `rescheduleWindows` 的第一句就是 `cancel`（撤上/下课铃），续排那一步真正会抛的点
> **全在 `cancel` 之前** —— 读 prefs、`scheduleRepository()`、`getCurrentSemester()`、
> `getDisplayCourses` / `getTimeSlots` 任意一处抛（Room 的 `SQLiteFullException`、
> cursor window 都是现实存在的失败），此时**上一轮那对课堂铃还挂在那里**，
> 本轮的清理与重排一个字都没执行。于是钥匙 2 探到"闹钟在"、钥匙 1 与 3 也放行
> ⇒ 这一整轮被跳过，**最长 24 小时**；而被跳过的这 24 小时里用户用的是**上一轮的窗口** ——
> 课被删了还在响、改过时间了还按旧的时刻响。只有把这道失败记进闸门的 `failures` 清单
> （⇒ 不写指纹 ⇒ 下一次冷启动无条件重跑）才补得上这个洞。
>
> **改法（窄）**：`rescheduleNextWindow(context, onStepFailed = NO_RESCHEDULE_STEP_FAILURE)`，
> 那句 `Log.w`（"下课后续排课堂窗口失败"）tag 与文案一字未改，只是挪成文件顶层的
> `LOG_RESCHEDULE_WINDOW_FAILURE` 作编排 seam 的日志钩子默认值 —— `android.util.Log` 在本模块
> JVM 单测里是抛 "not mocked" 的桩，硬留在 `onFailure` 里第一句就把报告口挤掉了。
> 无操作默认值在 `reminder/` 侧自己声明，不复用 `widget` 那份（方向是 widget → reminder）。
> 生产接线只改 `BackgroundSync` 那一处（复用 `rescheduleRemindersAndBells` 已有的 `onStepFailed`）；
> 另外四处（`WidgetFallbackWorker` 1、`CourseFluidService` 2、`ClassProgressReceiver` 1）吃默认值 ——
> 它们没有 `failures` 清单可落。`runCatching` 仍然整体吞异常（**不许改成向外抛**：
> 那四处里坐着下课铃广播与前台服务链路）。三条早退（两个课堂开关都关 / 学期为空 /
> 学期没有起始日期）**不算失败** —— 那是"没有可排的窗口"，记成失败等于让闸门每一轮都无条件重跑，
> §2.1 这半张卡的省电量级会被整个抹掉。
>
> **测了几遍**：新增 14 条 = 9 条编排 seam 行为（三个失败点各报**一次**、标签是
> `rescheduleNextWindow`、日志先于报告、异常不外逃、干净跑完 0 次、三条早退 0 次、
> 只开一个开关时仍往下走）+ 5 条源码形状（`rescheduleRemindersAndBells` 传的是它自己那个报告口，
> 而非常量、也不是漏传；全仓库 `ClassProgressScheduler.rescheduleNextWindow(` 的调用点集合 =
> 已知 5 处、其中只 1 处带报告口；那四处实参仍是 `context` / `applicationContext`；
> 日志文案未改且只一份；早退走 null 而不是走报告口）。两处按精确签名串切的既有守卫**只改锚点**
> （`ClassProgressReceiverMainThreadTest` 改成只到左括号、钉的仍是可见性；
> `ClassProgressCleanupDecisionTest` 里 BackgroundSync 那处改成匹配新接线）。
> 两处变异实测确认守卫是活的：抹掉 `BackgroundSync` 那个报告口 ⇒ 形状守卫两条红；
> 抹掉编排本体的日志钩子 ⇒ 顺序断言与形状守卫各一条红（共 4 条）。
>
> **门禁**（worktree `T14` @ `a9ec26a`，`--offline --rerun`）：`:app:testDebugUnitTest`
> **748 tests / 0 failures**（master 地板 734，只涨不跌）；`:app:lintDebug`
> **0 error / 14 warning**，其中 `.kt` 9 条与基线同口径（`BuaaWebSession` / `SettingsScreen` /
> `ReminderGuidance` / `HomeScreen` / `OnboardingScreen` / `WeekView` ×3 / `SceneBackground`）。
>
> ⚠️ 顺带登记本轮造成的**行号漂移**（本轮不动，留给下一次统一订正，同上一轮那条订正任务）：
> `ClassProgressScheduler.kt` 顶部多了两个顶层属性、`rescheduleNextWindow` 拆成"接线 + seam"两层，
> 该文件里 `rescheduleWindows` 及之前 **+24**、`schedule` / `cancel` / `cancelAll` /
> `hasPendingClassBells` 及之后 **+89**。本文件与 `ColdStartRebuild` 类注释里那些绝对行号
> （`:349`、`:383`、`:399-405`、`:409-413`、`:414-421`、`:430-440`、`:274-285`、`:280-291`、
> `rescheduleNextWindow:308`）要按这两个偏移换算；本轮只保证**新写**的引用是当前行号。

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

### 2.9 P1（2026-09-19 模拟器补录）课前提醒全关的用户，每重排一次就被静音抖动一次

这条不在第 1 轮的静态扫描视野里：它是 §2.5 那张"每次持锁留一行 log"的取证表跑出来的
—— 没有 T8b 那行 `elapsed/held`，三次多余的 `class_progress` 混在正常铃声里看不出来。
行号按 `ed70a1d`。

**实测**（emulator-5554，`dnd_during_class=true`，此刻真有一节在上的课，下课铃 20:35:00）：

```
20:06:05.514  tag=class_reschedule  elapsed=70ms   held=true   ← 一次下课铃广播在续排
20:06:05.517  tag=class_progress    elapsed=104ms  held=true
20:06:10.637  tag=class_progress    elapsed=109ms  held=true   ← +5.1s：一节已经在上的课又响了一次"上课铃"
              ZenModeController:  20:06:05.450 →0 ， 20:06:10.610 →2
20:06:29.376  tag=boot_rebuild      elapsed=477ms  held=true   ← 冷启动链
20:06:34.349  tag=class_progress    elapsed=53ms   held=true   ← 又是 +5.0s，同一件事
              ZenModeController:  20:06:28.942 →0
```

`shared_prefs` 里 `dnd_restore_deadline=1789765500000`（=20:35 下课 +30min 宽限）与
`dnd_saved_interruption_filter=1` 由 `enter()` 写下，随后被 `cancelAll→restore` 抹掉。

**链路**：这类用户的课前提醒一条都没有，于是 `ReminderScheduler.rescheduleAll`
走 `plan == null` 分支（`ReminderScheduler.kt:83-89`），它**无条件**补一句
`ClassProgressScheduler.cancelAll(context)` 再 `return null`；`cancelAll`
（`ClassProgressScheduler.kt:430-440`）做四件破坏性动作——撤双铃、停实况前台服务、
撤常驻通知、`ClassProgressDnd.restore()` + `cancelWatchdog()`。
`return null` 又触发 `BackgroundSync.rescheduleRemindersAndBells`（`BackgroundSync.kt:83-89`）
的兜底续排 → `rescheduleWindows` → `schedule()`（`:321-325`）用 `setAlarmClock` 排**上课铃**，
而 `planNextClassWindow` 的口径明写"**包含正在上课的那一次**"（`:171-173`），
`startMillis` 已在过去 → AlarmManager 立刻投递 → 那一发"多出来的上课铃"再跑整套
`enter()` + `startLiveWindow` + `scheduleEnd` + `requestLiveRefresh` + `cancelCourseReminder`。

**所以省电之外更要紧的是**：中间那 5 秒里勿扰是**开着记录已被抹掉**的状态。第 4 步把
`dnd_saved_interruption_filter` 和看门狗一起清了，`selfCheck` 的进门判据恰好是
"有残留记录"（`ClassProgressDnd.kt:128`）——**一旦第 5 步那次过期闹钟被 ROM 吞掉
（这正是澎湃/MIUI 的看家本领），这一节课的自动勿扰就永久进不去，且没有任何自愈入口**。
真机上这一条的期望表现是"上课了没静音"，不是"卡死静音"，所以用户很难报上来。

**修法方向**（本轮由 ai/T11 落，见 §1 表末行）：第 4 步那次 `cancelAll` 是**第二份**清理实现，
而且比唯一那份更早、更没判据——`rescheduleWindows` 自己在 `:280-291` 已经有一条带判据的
同类清理（"课还没开始**且**不在数这节课的课前倒计时"才收）。因此只删多余的那一半：
`plan == null` 分支里只要「课程进行中 / 上课自动勿扰」任一开关还开着就不要抢先 `cancelAll`，
交给紧随其后的续排链接手。两个开关都关时必须照旧 `cancelAll`——
那条路上 `rescheduleNextWindow` 会在 `:308` 早退，没人接手。
`semesterStart == null || courses.isEmpty()` 分支（`:70-76`）**不动**，那是真的没课表，
"上课中途清空课表留下永久勿扰"的 R5 F-11 语义靠它。

**为什么不给 `schedule()` 加"时刻已过就不排"的守卫**：那条过期投递恰好是
`CourseFluidService.recoverMissedClassStart`（`CourseFluidService.kt:95-115`）故意依赖的机制——
KDoc 原文"重排后到期的上课铃会立刻触发，把课中实况、勿扰进入与下课铃整套补上"。
在排程端掐掉它等于把**中途重启后的勿扰自愈**一起掐了。要修的是"先无条件拆、再靠过期闹钟补回来"
这个来回，不是补回来的那一步。

**2026-09-19 补：开机重建那一步是同一族里的第二处**（由 ai/T12 落）。上一行的修法实测生效
（`rescheduleAll` 之后不再抖，log 里那句「正处在课堂窗口内：本轮不撤销」按预期出现），
但同一次验证里冷启动链还是抖了一下，而且发生在更早的位置（emulator-5554，2026-09-18，
一节 20:40–21:25 的课在上、`dnd_during_class=true`、课前提醒全关）：

```
20:50:47.071  WakeLocks 进入 tag=boot_rebuild
20:50:47.085  ZenModeController: Zen mode setting changed to 0   ← 唤醒锁后 14ms，rescheduleReminders 之前
20:50:47.133  ReminderScheduler: 正处在课堂窗口内：本轮不撤销课堂铃与勿扰，交给续排链
20:50:47.266  WakeLocks 跑完 tag=boot_rebuild elapsed=195ms held=true
20:50:52.308  ZenModeController: Zen mode setting changed to 2   ← 5.2s 后续排链排出已过期的上课铃，重新 enter
```

`→0` 唯一对得上的语句是 `BootReceiver.kt:51`（`b5dc353` 时点）：开机重建的第一步**无条件**
`ClassProgressDnd.restore(context)`。它的前提"重启把闹钟全清了、遗留记录再没有下课铃来恢复"
只对**真正遗留**的记录（期限已过 / 旧版本缺期限）成立；正在上课时那条记录连同看门狗是当下
正要用的自愈凭据。覆盖安装（`MY_PACKAGE_REPLACED`）走同一接收器，抖动一样。

**修法**：那一步换成 `selfCheck` 的期限判据（有记录**且**期限已过才动手；无记录照旧 no-op，
绝不动用户自己的勿扰设置；旧记录缺期限照旧当场自愈）。判据只此一份，收在
`ClassProgressDnd.restoreIfStale`（不接 Context、动作注入 lambda，形状同
`ReminderScheduler.takeDownClassProgressIfNeeded`），开机侧不长第二份比较式。
「有记录但期限未到」交给紧随其后的重建链收口——没有课在进行时它必然走到带判据的清理
并把 `restore` 调下去：日历模式 `BackgroundSync.kt:51-57` → `ClassProgressScheduler.kt:434`；
无学期 / 空课表 `ReminderScheduler.kt:73-79` → 同上；课前提醒全关（`plan == null`）
`ReminderScheduler.kt:103-111` → 同上；有下一条提醒 `ClassProgressScheduler.kt:276-291`
（挑不出窗口 :277 收；课没开始 :290 恢复——开机新进程里不存在课前倒计时归属，
`ReminderNotifications.kt:198` 那对变量是进程内的）。真在上课则续排链重排出已过期的
上课铃、立刻投递重新 `enter`，勿扰一秒都不掉。
**已知残余**：开机重建查库抛异常时（`rescheduleReminders` 兜住并按 `true` 返回），续排链
这一轮没接手，那条期限未到的记录要等下一次冷启动 `selfCheck` 才收 —— 改前那种场景是被
开机无条件 restore"顺带"覆盖的，代价恰恰就是上面这 5 秒抖动。

**2026-09-19 补：同族的第三处由 ai/T11b 落（`1170d86`）**。上一枚（ai/T11，`b5dc353`）给
`shouldTakeDownClassProgress` 补的那道判据只看了 `ongoingAt`，于是"**窗口还没开始**"
仍被当成该收 —— 而课前提醒刚触发、倒计时刚上岛的那一刻 `plan` 同样是 null（唯一那条
课前提醒已经用掉了）。那一刻任何一次重排（改设置 / 改课表 / 关掉提醒）都会：

- 岛上那条课前倒计时当场消失（`cancelAll:433` 撤的 `cancelClassOngoing` 就是同一条通知 id）；
- 更要紧的是 `cancelAll` 第一步撤掉的双铃里包含**几秒后正要响的那发上课铃**，而这条分支
  本来就没有下一条提醒，被撤掉的铃再没有任何人续排（`rescheduleNextWindow` 只在铃响时跑）
  —— 那一节课的课堂实况与自动勿扰**永久缺席**。与"正在上课"那处同族，差的只是窗口状态。

**判据**：`shouldTakeDownClassProgress`（现 `ReminderScheduler.kt:273-291`）多收一个注入的
`(courseId, classStartMillis) -> Boolean`，`takeDownClassProgressIfNeeded`（`:198-220`）透传，
生产接线在 `:129` 传 `ReminderNotifications::ownsCountdownTo` —— 读的就是
`ClassProgressScheduler.rescheduleWindows:280-291` 那个分支用的同一对进程内状态，
**没有长出第二份"在不在数倒计时"的实现**，`isCountingDownTo` / `ownsCountdownTo` 本身
一个字未改。三判次序保持（两开关都关 → 挑不出窗口 → 正在上课 → 倒计时），第一判必须在前：
两条课堂开关都关时 `rescheduleNextWindow:308` 直接早退、`rescheduleAll:169-175` 走的也是
`cancelAll`，没有"下一环再判一次"可等，收了才是终态。为什么传 `ownsCountdownTo` 而不是
`isCountingDownTo`：后者内部自己读 `System.currentTimeMillis()`，而"课还没开始"这一判用的
已经是注入的那只 `now` —— 两只钟会在跨秒那一瞬给出相反的答案，守卫恰好在最该生效的
"这一节正要开始、勿扰正要生效"的那一秒失效；`ownsCountdownTo` 的归属是清得掉的
（ACTION_START 落地即以 IN_CLASS 重发实况、顺带归零），"归属还在 + 注入的 `now` 说没开始"
合起来就是 `isCountingDownTo` 想表达的内容，而时间只由 `now` 读一次。
为什么放过不会变成"永远收不掉"：那一发上课铃正是同一次 `ReminderReceiver` 广播
（`:44-62`，下发倒计时的同一段里就调 `rescheduleAll`）经 `rescheduleWindows` 排下的，
"不收"保住的正是它；铃一响 ACTION_START 重发实况、归零倒计时、排好下课铃，
续排链因此在下一环重新判一次 —— 与 `rescheduleWindows:281-285` 那笔取舍是同一笔账。
归属对不上（课被删 / 时间被改 / 数的是别的课）时照常 `cancelAll`，R5 F-11 的清理语义不变。

**测了几遍**：`ClassProgressCleanupDecisionTest` 由 14 行扩到 **24 行**（+10）。新增 8 行
行为断言（归属对得上⇒不收、只开勿扰时同样不收、课被删⇒照收、时间被改⇒照收、
数的是别的课⇒照收、两开关都关⇒照收**且倒计时判连问都不问**、正在上课⇒不问、
挑不出窗口⇒不问），期望值全部手写、不从实现反推；另 2 行接线守卫
（`productionWiresTheRealCountdownOwnerNotAConstant` 钉住生产传的确是 `ReminderNotifications`
那一份而不是恒 true / 恒 false 的常量，
`criterionOrderIsPassThroughThenSwitchesThenWindowThenCountdown` 钉住透传与三判次序）。
这两条不是冗余：上面那 8 行全由**注入的假判据**驱动，接线漏了它们一条都不会红，
而 `shouldTakeDownClassProgress(` 那个形状锚点只匹配签名前缀、加参数不会翻面。
顺带把原有那条"判据里不许出现 `startMillis`"的守卫改成"只许出现一次、且必须是
`countingDownTo` 的实参"——它禁的是本地重写窗口比较式，不是禁把窗口身份交给别人判。

**门禁**（worktree `T11b` @ `1170d86`，`--offline --rerun`）：`:app:testDebugUnitTest`
**734 tests / 0 failures**（master 地板 724）；`:app:lintDebug` **0 error / 14 warning**，
其中 `.kt` 9 条与基线同口径。**只能真机证伪的部分**：`plan == null` + 倒计时在数的现场
（课前那十分钟里进设置页随便改一项、或把「课程进行中」之外的提醒全关掉），改前表现为
倒计时一秒内消失且那一节课再无铃，改后应看到 `ReminderScheduler: 正处在课堂窗口内
（正在上课 / 正数着这节的课前倒计时）：本轮不撤销课堂铃与勿扰，交给续排链`
一行，且上课铃照响、勿扰照进。

> **第四处同族（只登记，本轮不动代码）**：日历模式那条路径也是同一族 ——
> `BackgroundSync.rescheduleReminders` 的 `if (!usesInAppReminders(context))` 分支
> （`BackgroundSync.kt:88-99`，`ClassProgressScheduler.cancelAll` 在 `:94`）
> → `cancelAll`（`ClassProgressScheduler.kt:430-440`）→ **`:434 ClassProgressDnd.restore`
> 无条件恢复勿扰**：正上着课时切换提醒模式 / 每次冷唤醒走日历模式那条清理，都会重演
> §2.9 上面那 5 秒抖动，顺带把勿扰记录与看门狗一起抹掉。
> **本轮不动它**：那一句是无条件 restore 里唯一还有人在依赖的自愈腿 ——
> ai/T12 把开机那条改走 `restoreIfStale` 之后，日历模式下"没有课在进行"的遗留
> 就是靠这句收的（`rescheduleReminders` 返回 `false` ⇒ `rescheduleRemindersAndBells:142-146`
> 只补排课堂铃，不再回头清理）。要改得先解决"自愈"与"跳过"互斥：给这一句加判据的同时，
> 得先有一条能区分"真遗留"与"课上正用到一半"的状态来源，那是另一张卡的量级。

> **已落地（ai/T15，`14e9a79` + `5cd96eb` + `5862f7e`，seam 在 `0c21553`）**：上面那句"要先有一条状态来源"
> 其实仓库里早就有了 —— 就是续排链自己那份 `rescheduleWindows` 判据。所以这一枚没有新造状态来源，
> 只把日历模式那一步的闸门换成**两枚课堂开关的窄判据**（`shouldCancelAllInCalendarMode`，
> `BackgroundSync.kt:169-170`：两枚都关才当场 `cancelAll`；只要有一枚开着就整块跳过，清理交给
> 那个 `return false` 兑现的续排链）。编排本体抽成了 `cleanUpInCalendarMode`（`:208-225`，守卫 `:218`），
> 分支在 `:107-131` —— `BootReceiver.kt:61-63` 那份收口清单的指路跟着改。
> **本卡开头那条前提被推翻了**：「系统日历提醒」管的是**课前提醒从哪条通道下发**，
> 课堂链（上课实况 + 自动勿扰）没有日历等价物，它在日历模式下本来就该继续跑，
> 闸门只有那两枚课堂开关。同族另外两处一起收：UI 路 `ScheduleViewModel.afterDataChangedInternal`
> 不再给日历模式单开一条**只撤不排**的分支；设置页两枚开关不再按提醒模式置灰
> （那句「「系统日历提醒」模式下不生效」是假话 —— 同一时刻勿扰正在生效）。
>
> **改前/改后实测**（emulator-5554，2026-09-19；现场 = `reminder_mode=calendar` + 两枚课堂开关开 +
> 一节 04:20–05:10 正在上的课；触发都是"非开机重排"那条广播，不含开机那一步）：
>
> - **后台路**：改前一次重排把 `mInterruptionFilter` 从 3 打到 **1 持续 4.99 s**（0.15 s 采样
>   100 个点里 15 个在 1；系统日志 `04:34:01.734 ZenModeController →0` / `04:34:06.862 →2`
>   = **5.13 s**，与本节上面那条开机 5.2 s 同量级），常驻通知同期 8 → 6 → 10。
>   改后同一动作：**100/100 个采样全程 3**，`ZenModeController` 一行变更都没有，
>   3 条闹钟与 `dnd_restore_deadline` / `dnd_saved_interruption_filter` 逐字不变。
> - **UI 路**（用户真的会做的那个动作：课表 → 点这门课 → 编辑页 →「保存」）：改前把排队闹钟
>   从 3 打到 **1**（只剩 22:00 明日预告）、两条勿扰记录删除、`mInterruptionFilter` 落到 1，
>   **20 秒后仍是 1** —— 这一节课的实况、下课铃、自动勿扰一起没了，直到下一次冷启动重建。
>   改后同一动作排队数 3→3（中途瞬时 4，是重排先装后撤）、勿扰全程不掉、记录原样。
> - **窄判据没被放宽**：两枚开关都关掉时铃照撤、勿扰照恢复（排队 4→2、`mInterruptionFilter` →1、
>   记录清掉）；只关一枚时铃留着 —— 这正是"用户自己关了本功能才当场收"的口径。
>
> **仍然只能真机证的**：MIUI/澎湃那些 ROM 在课堂中途吞掉闹钟后，这条链能不能自己回来（§2.9 全程没碰）。

---

## 3. 看起来该改、但本轮判断**不该改**的项

这一节与 §1 同等重要。以下每一项都有"表面理由"，逐条给出否决依据。

| 项 | 表面理由 | 为什么不改 |
| --- | --- | --- |
| `setAlarmClock` 用于上课铃（`ClassProgressScheduler.kt:349`）与勿扰看门狗（`ClassProgressDnd.kt:159`） | `setAlarmClock` 是 Doze 完全豁免档，状态栏还常驻闹钟图标，看着就像最耗电的那一档 | 这两条的**全部意义就是对抗省电策略**：前者是"用户亲自设定的闹钟"语义，后者一被吞就是"整机静音一整天"。第 2 轮 §4.5 已就同一问题给过结论（"不以省电为名削弱可靠性"），本轮复核认同。而且看门狗只在 `enter()` 真的静音成功时才排（`ClassProgressDnd.kt:82`），下课 `restore()` 成功后立刻 `cancelWatchdog`（`:113`）——**正常一节课不会留下额外唤醒**，这个设计是自洽的 |
| 每门课一个闹钟？ | 典型课表 30 门课，直觉上应该有一堆闹钟 | 实测**不存在**：`ReminderScheduler` 只排 `minByOrNull{triggerAtMillis}` 一条（`:165`、`:101`），课堂铃同理固定两个 requestCode 复用（`:136-137`）。稳态 ≤6 条（§0.1）。这里已经是最优形状，**不要为了"批处理"去合并**——AOSP 对第三方 `setExact*` 本来就不做对齐合并，合并反而会破坏"提前 10 分钟"的语义 |
| 无 `isDeviceIdleMode` / Doze 自适应判断（全仓库 0 命中，本轮已 grep 确认） | 常规省电清单会要求"进 Doze 时跳过非关键刷新" | 本项目的后台任务**全部是闹钟驱动**，不是轮询；闹钟能投递到就已经穿过了 Doze 闸门（`*AndAllowWhileIdle` 档位）。在此之上再加 `isDeviceIdleMode` 判断，只会得到"Doze 里不重排 → 提醒链断"，与 P1-① 相反的失败方向。**唯一适合做这种判断的是零点组件刷新**，而它已经是非精确档（`BackgroundSync.kt:155`），系统本来就会自己合并延后 |
| WorkManager 12h 兜底（`WidgetFallbackWorker.kt:77`）+ `KEEP` 策略 | 待机清单上"每 12 小时醒一次查一堆表"很像浪费；有人会顺手改成 `UPDATE` | 两条都不该动。(a) 它是 HyperOS 类 ROM 清掉第三方精确闹钟后**唯一的恢复路径**（`WidgetFallbackWorker.kt:16-27` 记着原委）；(b) `KEEP` 正是防止"每次 `enqueue` 重置计时"的正确选择——本项目 `bootstrapBackgroundSync` 每次组件广播都会调 `ensure()`（调用点见 `WidgetCommon.kt` 里 `bootstrapBackgroundSync` 的全部调用处，本卡不押行号），若换成 `UPDATE`，60 秒闸门挡不住"用户一直用桌面"的情形，周期任务会被反复重置成**永不触发**。这条是本轮专门复核过的**好消息** |
| `SpocSession.hasSession()` 落在闹钟路径上（`ReminderReceiver.kt:132`） | AndroidKeyStore 每次 `load()` 都要 `KeyStore.getInstance+load(null)+getEntry`（`KeystoreBlobStore.kt:65-67`，2 趟 keystore daemon binder），待机清单上属于"闹钟里别碰密钥库" | 两个短路已经把它挡住了：(a) `spocSignHintEnabled(context) && hasSession()` 的 && 顺序（`ReminderReceiver.kt:132`）——该开关默认 `false`（同文件 `:79`），关着时**根本不解密**；(b) `SpocSession` 有 `loadedFromDisk` 进程内缓存（`SpocSession.kt:82`、`:95`），冷进程内至多解密一次。要再优化就是加磁盘明文缓存，属于拿安全性换一次 binder，**不值** |
| 每次唤醒重排闹钟时 `cancel()` 再 `schedule()`（`ClassProgressScheduler.kt:262`、`286`） | 表面看就是"取消旧 job 再排新 job"的反面教材，想加"窗口没变就跳过" | 跳过判据要求把"当前已排的窗口"存进可比较状态，而这块刚因为**两份同值不同名的状态**出过一次事故；并且 `PendingIntent.cancel()` 后再 `getBroadcast` 是系统推荐做法，单条 alarm 的 set 成本约一次 binder，**远小于**它上面那两条重复链（P1-①）。先把重复链消掉，再回来评估这条要不要加判据 |
| 组件 `updatePeriodMillis`（6 份 `res/xml/*_widget_info.xml` 全是 `0`） | 组件不自己轮询，那要不要给"下一节课"组件加个 30 分钟周期刷新？ | 不加。翻面点已经由上课铃/下课铃的 `requestLiveRefresh` 精确驱动（调用点在 `ClassProgressReceiver` 的 `ACTION_START`/`ACTION_END` 两支 → 定义在 `WidgetCommon` 的 `requestLiveRefresh`，且刻意只重绘 3 个与"此刻"有关的组件、绕开 `refreshWidgets`，注释见 `requestLiveRefresh` 上方那段 KDoc）。加周期刷新等于把 6 条闹钟之外的第 7 条唤醒源装回来，而它解决的还是已经被解决的问题 |
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

`dumpsys alarm` 分两段：排队中的闹钟 + 按 `u0aNNN:` 分段的投递/唤醒历史。
P1 改动只应影响前者的条数与后者的增速。

⚠️ **2026-09-19 在 API 36 模拟器上实测：本节原先的 A/B/C/D 四条全都读不出正确数**。
根因是这段文档写的时候按旧版 `dumpsys` 的段名锚定，而 API 36 的 `dumpsys alarm`
**顶层只有一个 `Current Alarm Manager state:`**，`Current alarm queue:` 与 `Total num`
两个标记串都已不存在 → `awk '/Current alarm queue:/,/Total num/'` 的区间**一头都不匹配**，
A 与 B 恒定为 0 / 空。**恒定为 0 比报错更坏**：改后拿它做"没改坏"守卫会无条件通过。
D 那种不带区间的 `grep -c "ClassProgressReceiver"` 则是另一个方向的错——统计段里
`u0a216:com.buaa.schedule … 1 wakeups:` 下面还有一批含同名类名的历史行，实测
**3 条真实排队被数成 13 条**。下面这四条是逐条在 emulator-5554（API 36）上跑通过的口径。

```bash
UID_TAG=u0a216   # §0.1 取的 UID；换设备/重装后要重取

# A. 排队条数 —— 期望值 = §0.1 那张表的 ≤6。
#    框架自己把答案写在一行里，不用解析闹钟块（实测本机 = 3，与手写块数一致）
adb shell dumpsys alarm | grep -oE "$UID_TAG:[0-9]+" | tail -1     # → "u0a216:3"

# A'. 第二条独立口径（用来交叉验证 A，两者必须相等）：
#     排队块里的 tag 行是"缩进 + tag=…开头"，历史块里的同名行前面还挂着 "type=…"，
#     所以行首锚定就够了（实测同为 3；朴素 grep -c 会得 13，见上）
adb shell dumpsys alarm | grep -cE "^ +tag=\*walarm\*:com\.buaa\.schedule"

# B. 逐条看档位与下次触发时刻（RTC_WAKEUP / setAlarmClock 会显式标注；
#    重点核对有没有 setInexactRepeating 或多条同 receiver 的排队条目）。
#    注意 tag 行在 `RTC_WAKEUP #N: Alarm{…}` 的**下一行**，所以 -A1 挂在类名上
adb shell dumpsys alarm | grep -E "^ +tag=\*walarm\*:com\.buaa\.schedule" -B1 -A1

# C. 本包被唤醒了几次：先按 `u0aNNN:com.buaa.schedule` 锚定本包那一块，再往下读 3 行。
#    首行的 `N wakeups` 是本包总唤醒数，桶行的 `K wakes L alarms` 是"这段时间投递了几次"。
#    两次采样做差 = 这段时间内系统真正为本包唤醒了几次。
#    ⚠️ 不要写成全局 `grep "wakes .*alarms"`：那一行每个包都有，实测会把无关包读进来。
#    ⚠️ 原口径 `grep -A12 "u0a<UID>:" | grep -E "total=|delivered="` 在 API 36 上
#    抓到的是紧接着的 `Alarm manager stats:` 里的 APPOPS 行
#    （HAS_SCHEDULE_EXACT_ALARM: count=…, total=…），与本包投递数毫无关系。
#    ⚠️ 这块是不是"自启动以来累计"未取证（§4.3 的口径才是累计），所以差值只在
#    同一个待机窗口内可比；跨 `dumpsys batterystats --reset` 不要拿它当基线。
adb shell dumpsys alarm | grep -A3 -E "^ +$UID_TAG:com\.buaa\.schedule"

# D. 看门狗闹钟有没有如约被取消（上课→下课后应回到 §0.1 的稳态条数）。
#    直接用 A 的条数差，不要按类名 grep：上/下课铃与看门狗共用同一个 receiver 类名，
#    历史段里也全是它，条数才是答案（看门狗的时刻 = 下课 + 30min，见 ClassProgressDnd）
adb shell dumpsys alarm | grep -oE "$UID_TAG:[0-9]+" | tail -1
```

**改前/改后各跑一遍 A**：条数应该都不变（P1-① 不减唤醒次数）。若改后 A 变多，说明新的
触发条件把某类闹钟漏排了，直接回退。**A 是这条链路的"没改坏"守卫，不是收益指标。**

本机这个种子前态（课前提醒全关）只有 3 条，比 §0.1 的 ≤6 低一半——**≤6 那道守卫要在
"开着课前提醒"的前态上测**，否则关掉提醒这一半链路根本没被走过。
另外 §2.9 那条抖动的直接读数也在这里：课中每重排一次，A 会先掉到 1（`cancelAll` 撤双铃）
再回到 3（过期上课铃补排），**掉下去的那一眼就是勿扰记录被抹掉的时刻**。

### 4.2 JobScheduler / WorkManager：本项目只有 1 条周期任务

```bash
# 本包名下的 job 条目（WorkManager 的 system job scheduler 后端）。
# 数的是"出现本包名的 job 描述行"，稳态期望 1 条 pending（widget_fallback_refresh）
adb shell dumpsys jobscheduler | grep -i -B2 -A8 "com.buaa.schedule" | head -80

# 只数条目。⚠️ 不要用 `grep -c "com.buaa.schedule"`：一条 job 的块里有十几行都带包名，
# 实测本包只有 1 条 job 时会读出 18。job 条目行是固定形状的 `  JOB #u0aNNN/K: …`，锚它
adb shell dumpsys jobscheduler | grep -cE "^ +JOB #$UID_TAG"        # → 1

# 确认周期任务的 next-fire 没有被反复重置（KEEP 策略的直接证据）：
# 连测两次间隔 >5 分钟，剩余延迟应单调变小而不是回跳成满值。
# ⚠️ API 36 的 job 块里没有 `interval=` / `deadline=` 这两个字段（原口径 grep 不到东西），
# 实际读得到的是 `Minimum latency: +5h18m…` 与 `Unsatisfied constraints: TIMING_DELAY`
adb shell dumpsys jobscheduler | grep -A14 -E "^ +JOB #$UID_TAG" | grep -iE "Minimum latency|constraints"
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
# tag 直接对应到代码位置：
#   reminder_show_and_reschedule -> ReminderReceiver.kt:41
#   class_progress / class_reschedule -> ClassProgressReceiver.kt:36 / :106
#   tomorrow_preview -> TomorrowPreviewReceiver.kt:41
#   widget_refresh -> WidgetRefreshReceiver.kt:39
#   boot_rebuild -> BootReceiver.kt:36

# A) 持锁**次数**：数 T8b 那行取证 log（每次 withPartialWakeLock 跑完恰好一行）。
#    这才是"这段重活被跑了几遍"的数，也是 P1-① 与 §2.5 的验收数。
adb shell logcat -d -s WakeLocks | grep -oE "tag=[a-z_]+" | sort | uniq -c

# B) 每次持锁的**时长**：同一行里自带 elapsed 与超时上限，不用另一个工具
adb shell logcat -d -s WakeLocks | grep -oE "tag=[a-z_]+ elapsed=[0-9]+ms timeout=[0-9]+ms held=[a-z]+"
```

⚠️ **不要用 `dumpsys batterystats` 那一行来数次数**（本节原先就是这么写的）。它在 API 36
模拟器上实测的是"**被归因到这把锁的唤醒次数**"，跟"这把锁被 acquire 了几次"是两个量：
同一段待机里 `tag=class_progress` 的取证 log 有 **6 行**，batterystats 只记
`class_progress: 23ms (2 times)`；而 `boot_rebuild` 真跑了 **2 次**（其中一次 477ms），
batterystats 里它那一行连时间和次数都是空的（`Wake lock buaa:schedule:boot_rebuild realtime`）。
进程本来就醒着时拿的锁不会 blamed 到任何一次唤醒——**所以它当"没改坏"守卫会永远偏小，
改动前后的差值也就无从判断**。它唯一还有用的读数是"被 blamed 的那部分时长"，即真正耗电的量：

```bash
# C) 被归因到本包各把锁的唤醒时长（做**时长**对比用，别拿它的次数做判据）
adb shell dumpsys batterystats | grep -E "buaa:schedule:[a-z_]+: [0-9]+ms \([0-9]+ times\)"
```

**验收读法**：改后同一 tag 的 **A 次数**应持平或下降，**B 的 elapsed** 应下降
——持锁时长才是真正耗电的那个乘数。两者一起看：只降次数不降时长 = 少跑了一半但每次更重，
那是把活挪了地方不是省了。

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

⚠️ **口径订正（2026-09-19，ai/T13 落地后实测时改的）**：本节原来给的
"30 分钟被动待机 + `logcat -b events` 数 `am_proc_start`"这一套**量不到本指标**。
闸门（`ColdStartRebuild`）只在**进程创建**那一瞬间判定，而本仓所有闹钟都是"只排下一条"式的，
间隔以小时计 —— 模拟器静止 30 分钟内压根没有几次冷启动，被动窗口只会得到
"改前改后都是 0~2 次"这种没有分辨度的数。实测改用了下面 **4.4-b** 的脚本化冷启动。
下面那段 `am_proc_start` 的取法在**真机长时间待机**场景里仍然有效（真机的唤醒源多得多），
保留备查；`dumpsys procstats` 那条同理。

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

**4.4-b 实测：脚本化冷启动的改前/改后对照（2026-09-19，emulator-5554，API 36）**

做法：同一台模拟器、同一份种子库（18 门课、应用内提醒全开、桌面组件 0 个绑定），
两个包各跑 **10 次冷启动**；一次冷启动 = `input keyevent HOME` → `am kill` → 无头广播把它拉起 →
settle 14 秒后从 `/proc/<pid>/stat` 的 utime+stime 取 CPU（100 Hz → 每 tick 10 ms）。
**只用 `am kill` 不用 `am force-stop`**：后者会把本包的闹钟一起撤掉（见 §4.1），
那样两端的"冷"就不是同一种冷。

对照的两个提交只差 ai/T13 这一枚（这样差值才只归给它）：

| | 改前 | 改后 |
|---|---|---|
| 包 | `9e70ddd`（闸门不存在） | `437c106`（三把钥匙 + 接线） |
| 整链重建次数 / 10 次冷启动 | **10**<sup>†</sup> | **1**（第 1 次「无成功记录：整链重跑」） |
| 跳过次数 | 0 | **9**（「三把钥匙均放行：跳过重建整链」） |
| 单次 CPU 均值 | 2551 ms（第 2~10 次） | **2133 ms**（9 次跳过轮） |
| 单次 CPU 峰值 | 2770 ms | 2620 ms（=那一次重建） |
| 排队闹钟 | 每轮 4→4 | 每轮 4→4 |

<sup>†</sup> 改前列没有日志行可数（闸门不存在 ⇒ `ColdStartRebuild` 一行都不打），"10" 是按
"没有跳过判据 ⇒ 每次 `onCreate` 都跑完整重建"推定的；改后列的 1 / 9 是数出来的。

**两个指标必须分开说，否则会被读成"省了 84% 的电"**：

- 按**重建次数**这个直接指标：10 → 1，**−90%**。这正是 P1-① 要的那件事。
- 按**单次冷启动的 CPU**：2551 → 2133 ms，**−16.4%（省约 418 ms/次）**。
  差值远小于 90%，因为被跳过的只是 `BUAAApplication.onCreate` 里那一步重建，
  而**进程创建、Room/DI 初始化、其余 step 照付**。换句话说这条链本身只占一次冷启动开销的一小片，
  之前把它当成"一次唤醒的绝大部分"是估计，不是测量。
- 换算：每 10 次冷启动省约 3.9 秒 CPU。真机上一天多少次冷启动取决于 ROM 的杀进程策略，
  本窗口给不出那个乘数（见 §4.5）。

**顺手买到的两条独立证据**（都不用另跑窗口）：

1. **跳过的那一轮确实不刷新时间戳**：日志里的"距上次成功"在 9 次跳过轮里单调递增
   （27.7 s → 55.4 s → 83.1 s → 110.8 s → 138.5 s → 166.4 s → 194.1 s → 221.7 s → 249.4 s），
   一格不差地等于各次冷启动之间的间隔 —— 也就是那 9 步一步都没把"上次成功"往前推。
   这条可以自己核：把 `coldstart-after.json`（与取数脚本同目录，仓库外，见本节末）里
   10 行的 `launched_at` 相减，得到的序列与日志里那一串逐格吻合
   （间隔 30 s 减去每轮的 settle 与 kill 开销 ≈ 27.7 s）。
   这就是 ai/T13 那句"否则 24 小时这个硬上限永远到不了"的生产实证。
2. **`am kill` 不动闹钟队列**：10 轮里排队数恒为 4，且钥匙②能凭它判"闹钟在"。

**这个差值的适用边界（读之前先看这条）**：本窗口的人群是"应用内提醒 + 组件 0 个绑定"，
所以组件那四步（`runColdStartWidgetSteps`）在两端都只花一次 binder 探测就短路，
**没进差值**。真机上桌面挂着组件的用户，被跳过的是"全学期快照 sync + 六个组件重绘"那一整块，
差值会明显大于 16%。要拿这条结论去解释真机体感，得先在真机上按 §4.6 的清单重跑一遍。

**其余局限**：① 模拟器的 CPU 频率与 idle 状态被拍平（§4.5），绝对毫秒不可外推，
只有**同一台机器上的比值**可用；② 改后包的第 1 次冷启动前进程是我手动杀掉的，
改后窗口少了一步 `am kill`（只影响"要不要杀"，不影响被测的那个新进程的 CPU）；
③ 每端只跑了一轮 10 次，单次读数的离散度 2500~2770 ms（±5%），
差值 418 ms 远大于这个噪声，但重跑一次的把握仍然高于一次；
④ 真正的省电结论还要 §4.3 的唤醒次数与 §4.2 的周期任务一起看，本节只回答"一次冷启动付多少"。

**取数脚本**：放在仓库外 `D:\schedule\.tmp\coldstart_window.py`（一次性取证工具，不入库）。
关键参数 `--label before|after --starts 10 --spacing 30 --settle 14 --launch noop`。
`--launch noop` = 往 `ClassProgressReceiver` 投一个它不处理的 action，只为把进程从零拉起并
让 `onCreate` 那条链跑一遍；换成 `widget` 会额外触发一次全量重排（两端都会，但会把
被测量本身混进测量里），换成 `ui` 会把 Compose 首帧的 CPU 记进差值 —— 都不对。

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
