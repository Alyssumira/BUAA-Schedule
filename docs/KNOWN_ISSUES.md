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
  ④ `setShortCriticalText`（岛/胶囊紧凑文案）无跨版本稳定公开签名，
  直接写 extras `android.shortCriticalText`；
  ⑤ extras `android.requestPromotedOngoing=true` 双保险。
- **本项目已对齐（2026-09-15）**：`CourseFluidService` / `ReminderNotifications`
  移除 `setSilent`（课程进行中首帧会响一声，属预期）、ProgressStyle 补
  tracker 图标（`ic_progress_dot`）与 Segment、新增
  `applyPromotedOngoingExtras`（requestPromotedOngoing + shortCriticalText
  「还有 N 分钟下课」extras）。倒计时 chronometer 保留（SleepDown 用文字刷新，
  chronometer 是更强的实况信号，两者不冲突）。
- **仍需真机验证**：以上对齐是把「与实测能上岛的应用的差距」清零；
  HyperOS 侧是否还有隐式门槛（如通知权限子开关「常驻提醒」、
  通知设置里的「提升式通知」用户开关 `canPostPromotedNotifications`），
  需要在澎湃 OS 4 真机上观察 logcat 与岛表现后确认。
- **定制岛效果（自定义动效/模板）**：如需进一步定制才走小米开发者平台提报，
  本项目暂不做，标准样式已覆盖课程进度场景。

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
