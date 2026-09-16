# BUAA Schedule（北航课程表）

声明：本项目全部代码均由AI生成
从零实现的北航专用课程表 Android App

## 当前进度

> 下面是已落地功能的清单（不是初始骨架的路线图）。带 ⚠️ 的条目有平台限制或使用前提，点开前先看一眼。

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
- [x] Room schema 版本化 + 迁移测试（`connectedDebugAndroidTest`）
- [x] 横滑翻周（HorizontalPager，与顶部翻周按钮/跳周双向同步）
- [x] 触觉反馈（翻周、切换视图、校区/学期选择）
- [x] 页面转场动效 + 系统「移除动画」适配
- [x] 排版刻度补全（10 级，修复课程卡文字静默回落到 M3 默认 11sp）
- [x] 动效令牌（duration / easing 集中定义）
- [x] 校区筛选（此前按钮只改组件内状态，选了不生效）
- [x] 玻璃 effect 缓存（effectKey 化，避免每分钟 tick 重算 blur/lens）
- [x] 桌面组件外观配置（**按实例**设置配色来源 / 10 套一键样式预设 / 背景色 / 不透明度 / 圆角 / 文字颜色，实时预览所见即所得；Launcher 支持 `requestPinAppWidget` 时可一键添加到桌面，不支持时 Toast 提示改手动长按添加）
- [x] 节次时间可编辑（HH:mm 格式 + 结束晚于开始校验，保存前不写库）
- [x] 导入抓取可取消（登录与刷新两条链路均支持；取消不落库，登录会话保留）
- [x] 课表背景默认提取系统桌面壁纸（可关闭回退渐变；拾取的图片优先级更高；支持模糊/亮度/取景缩放）
      ⚠️ Android 14（API 34）起系统禁止普通应用读取壁纸（`getDrawable` 需 `MANAGE_EXTERNAL_STORAGE`），
      该版本上会自动回退渐变或用户自选图片；设置页会在该场景下显式提示并引导改用手动选图
- [x] 课表管理总览页（按课程归并全部片段：搜索、整门课改色、整门课删除、进入编辑器）
- [x] 冲突处理向导（按组给出同天最近空位建议，一键只改冲突周）
- [x] 多课表学期切换（设置页在已导入学期间切换"当前学期"，无需重建数据）
- [x] 导入预览逐条勾选（取消勾选 = 原样保留已存在课程，不会误删）+ 导入历史独立页
- [x] 发布签名配置（`local.properties` 或 `BUAA_KEYSTORE_*` 环境变量；缺省保持 unsigned）
- [x] CI 跑模拟器仪器测试（`reactivecircus/android-emulator-runner` + `connectedDebugAndroidTest`，
      API 29 + API 34 矩阵，fail-fast 关闭、报告分档上传）
- [x] 文档体系（`docs/`：架构总览 / 备份与口令格式 / 设计系统约束 / 隐私与数据安全 / 已知问题 / 厂商 ROM 适配笔记）
- [x] 宽屏（≥600dp）周视图 + 日视图双栏并排（用 `Row` 权重实现，未引入 material3-adaptive）

## 不做的内容

本项目明确不包含 AI 助手 / AI 导入 / 智能推荐等 AI 功能
暂不考虑做国际化，目前文案均为中文硬编码

## 待实现

- [ ] 多套节次方案（单套时间编辑 + **按首节时间/每节时长/课间自动推算整表**已完成，见 `SmartPeriods`）
- [ ] 桌面组件背景的真实高斯模糊（当前做法：采样壁纸 → 缩小再放大得到廉价模糊 + 叠加用户背景色，
      `WidgetBackgroundRenderer`；受同样的 Android 14 壁纸限制，该版本上回退纯色圆角底）
- [ ] 图片 / PDF 导入
- [ ] 完整个性化外观（壁纸取景 / 横竖屏独立配置；模糊/亮度/缩放已完成）
- [ ] Baseline Profile：**目前完全没接入，`:benchmark` 里的三个类都是跑不起来的死代码**。
      release 产物只含 **AGP 自动合并的依赖库 profile**（`app/build/outputs/apk/release/baselineProfiles/`），
      `app` 自身的热点方法一条都没采集。补齐需要 5 步，缺一不可：
      ① `:app` 应用 `androidx.baselineprofile` 插件；② `:app` 加 `baselineProfile(project(":benchmark"))`；
      ③ `:app` 加 `androidx.profileinstaller` 运行时依赖（否则生成的 profile 装了也不会生效）；
      ④ `:benchmark` 配 non-debuggable target variant（宏基准与 profile 采集不接受 debuggable 目标）；
      ⑤ 加一条 CI job 生成并把 `baseline-prof.txt` 提交到 `app/src/main/baselineProfiles/`。
      **在此之前不要引用任何冷启动数字**：debuggable 目标上量出来的启动时间不能代表 release。

## 构建

```bash
./gradlew assembleDebug
```

工具链：JDK 17 或 21（AGP 8.13 要求 ≥17）。注意 Gradle 8.14.3 **不支持 Java 25**，
若本机装了较新的 Android Studio（自带 JBR 为 25），需要显式指定 JDK：

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew assembleDebug
```

子模块说明：`:app`（应用）、`:kyant-backdrop`（vendored 液态玻璃库）、`:benchmark`（宏基准 /
Baseline Profile 生成，依赖真机，**尚未接入 `:app`，其用例目前不在任何 CI 或本地任务里执行**，
见上面「待实现」的 Baseline Profile 条目）。CI 中的任务都写成 `:app:` 前缀，
裸任务名会被 Gradle 匹配到所有子工程从而把 `:benchmark` 一起拉进来。

发布签名（可选，不配置则 release 为 unsigned）：

```properties
# local.properties
buaa.keystore.path=/abs/path/release.jks
buaa.keystore.password=****
buaa.keystore.alias=****
buaa.keystore.keyPassword=****
```

CI 上等价的环境变量：`BUAA_KEYSTORE_PATH` / `BUAA_KEYSTORE_PASSWORD` / `BUAA_KEYSTORE_ALIAS` / `BUAA_KEYSTORE_KEY_PASSWORD`。
版本号可覆盖：`-PversionCode=12 -PversionName=1.2.0`。

## 测试

```bash
./gradlew testDebugUnitTest          # 单元测试（JVM）
./gradlew connectedDebugAndroidTest  # 模拟器/真机仪器测试（需已连接设备）
```

概况（随代码变动，此处为最近一次统计）：

| 类型 | 用例数 | 文件数 | 覆盖范围 |
| --- | --- | --- | --- |
| 单元测试 | 282 | 45 | 周次解析 / 教学周计算 / 冲突检测 / 导入规划 / 备份 schema / ICS 与文本解析与往返 / 节次分段与连堂判定 / 教务抓取脚本契约 / 日历投影选择 / 提醒排程与明日预告推送集合 / 分享编解码 / Widget 外观 |
| 仪器测试 | 55 | 12 | Room 迁移（`MigrationTest`）/ Repository 提醒写入与事务 / Widget 刷新新鲜度与渲染契约（预览可读性、列表工厂）/ WebView 会话保留与隐藏宿主 / 课堂铃生命周期 / 日历同步部分失败 |

最近一次真机实测（Redmi K60 Pro / Android 17）：**55 例全通过、0 失败**。其中
`BuaaSessionRetainKeepsJsAliveTest` 3 例要求给本应用放行「后台弹出界面」——HyperOS 会拒绝
instrumentation 拉起 Activity，而 `ActivityScenario.launch` 没有超时；该类已加 30 秒/例的
JUnit `Timeout` 规则，在不放行的 ROM 上报超时而不是把整轮 `connectedDebugAndroidTest` 挂死。

单测不需要设备；仪器测试跑在 **API 29 + API 34 模拟器**上（CI 同配置），Room 迁移与
WebView 相关用例必须有真实 Framework 环境，API 34 一档用于覆盖 Android 14 行为收紧
（壁纸读取、精确闹钟等，见 `docs/KNOWN_ISSUES.md`）。

## 文档

- `docs/ARCHITECTURE.md` — 架构总览
- `docs/DESIGN_SYSTEM.md` — 设计系统约束
- `docs/BACKUP_FORMAT.md` — 备份与分享口令格式
- `docs/PRIVACY.md` — 隐私与数据安全说明（数据清单、备份规则、权限、日志脱敏）
- `docs/KNOWN_ISSUES.md` — 已知问题与平台限制（先查这里再报 bug）
- `docs/VENDOR_NOTES.md` — 厂商/ROM 适配笔记（证据分级 + 适配设施清单 + 真机观察记录）

## 北航接口参考

已确认接口：

- 登录：`https://sso.buaa.edu.cn/login`
- 学期周次：`/jwapp/sys/homeapp/api/home/getTermWeeks.do`
  - 参数：`termCode=2026-2027-1`
  - 返回：`datas[]`，含 `startDate`、`serialNumber`、`curWeek`
- 课表：`/jwapp/sys/homeapp/api/home/student/getMyScheduleDetail.do`
  - 参数：`termCode`、`campusCode`、`type=week`、`week=1`（也可 `type=term` 拉整学期）
- 已用真实返回 JSON 验证解析器：`arrangedList` / `cellDetail` / `titleDetail` / `weeksAndTeachers` / `getTermWeeks`

两条链路的调用方式不同，改代码时别混用：

| 链路 | 方法 | 位置 | 说明 |
| --- | --- | --- | --- |
| 页面内抓取（**生产在用**） | `GET` + query string | `BuaaInPageFetcher.kt` | 走 byxt WebView 页面内 `fetch`，凭证随页面 Cookie 自动带上 |
| 原生网络栈（仅 debug 的 Cookie 粘贴入口） | `POST` + form | `BuaaApi.kt` | 复刻不出页面上下文凭证，正式登录流程不用它 |

`type=term`（整学期一次拉取）已由 `BuaaScheduleParserTest` / `SemesterCoursesCompletenessTest`
覆盖验证可用；生产仍按周（`type=week`）循环抓取，以兼容部分课程周次不均匀的排课。
