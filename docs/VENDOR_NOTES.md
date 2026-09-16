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

## 小米 / 澎湃 HyperOS

- **超级岛有两条上岛路径**（A，dev.mi.com + SleepDown 真机）：
  ① Android 16/17 标准 promoted ongoing 通知自动渲染成岛样式，**无需提报、
  无需上架**；② 定制岛效果才走《模板库》提报（注册开发者→上架→预审→联调→灰度）。
  本项目走路径 ①，实现清单见 `docs/KNOWN_ISSUES.md` 2a。
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
- **ColorOS 流体云是独立系统服务**（B，Sleepy `oppo-coloros-fluid-cloud.md`）：
  `NotificationCompat + IMPORTANCE_HIGH` 不等于流体云；接 SeedlingSDK 需厂商审核。
  本项目不做厂商 SDK，标准 promoted ongoing 在其上以普通实况形式呈现即可。

## 本项目适配设施清单（代码锚点）

| 设施 | 位置 |
| --- | --- |
| 实况通知（promoted ongoing + extras 双写 + tracker/Segment） | `CourseFluidService` / `ReminderNotifications` |
| 实况三态探针 + 诊断行 | `ReminderGuidance.promotedOngoingState` / SettingsScreen「实况通知」 |
| 状态驱动兜底（课堂窗口内补起实况） | `LiveClassResyncer` ← MainActivity.onStart |
| 上课铃 setAlarmClock + 三级降级 | `ClassProgressScheduler.scheduleClassStartBell` |
| 短唤醒锁包闹钟重排 | `WakeLocks` ← ReminderReceiver |
| 开机/升级/权限变化/时间变化自愈 | `BootReceiver` / `WidgetRefreshReceiver` |
| 厂商自启动直达页 + 电池白名单 | `ReminderGuidance.openAutoStartSettings` / `requestIgnoreBatteryOptimizations` |
| 上课自动勿扰（含下课恢复） | `ClassProgressDnd` |

## 真机观察记录（追加区，按日期倒序）

- 2026-09-16（澎湃 OS 4 / Android 17，用户真机，首轮 `connectedDebugAndroidTest` 52 例通过）：
  - **`alarmManager.cancel()` 不等于 PendingIntent 消失**（A）：随后用
    `PendingIntent.getBroadcast(FLAG_NO_CREATE)` 仍取得到非空，于是"上/下课铃到底还挂不挂着"
    在真机上查不出来（`ClassBellLifecycleTest` 三例全红暴露）。`ClassProgressScheduler.cancel`
    现在补 `PendingIntent.cancel()` 把记录本身摘掉，重排时再 `getBroadcast` 造新的。
  - **HyperOS 拦下 instrumentation 的 Activity 拉起**（A）：`ActivityScenario.launch` 没有超时，
    Activity 不被放行时它一直等 RESUMED，把整轮仪器测试挂死（实测 34 分钟 0 例完成，
    且 `am instrument -w` 也不会自行退出）。放行开关 = 开发者选项「USB调试（安全设置）」。
    本轮 `BuaaSessionRetainKeepsJsAliveTest` 三例因此未在真机执行（CI 模拟器无此限制）。
  - **真机与模拟器全新安装的差异：库里有用户真实数据**（A）：`CourseListFactory` 按
    `appWidgetId` 的绑定取快照 key，测试传 `INVALID_APPWIDGET_ID` 会落到 `"current"` 槽位，
    读到的是用户自己的课表而非 seed 内容。组件类仪器测试必须自配 widget id +
    `WidgetBindingStore` 绑到测试学期，否则同一条用例在开发机和真机上结论不同。

- 2026-09-15（澎湃 OS 4 / Android 17，用户真机）：第三方标准 promoted ongoing
  通知未上岛 → 逐项对齐 SleepDown（去 setSilent、补 tracker/Segment/shortCriticalText
  extras）后待复测；`canPostPromotedNotifications` 状态待在设置页确认。
