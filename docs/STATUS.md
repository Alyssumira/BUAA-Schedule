# 功能状态清单

> 这一页是从 `README.md` 挪出来的历史清单。README 面向装 App 的人，这里面向想知道
> "哪些已经落地、哪些还没做、哪些明确不做"的贡献者，所以条目按开发顺序而不是使用顺序排。
> 带 ⚠️ 的条目有平台限制或使用前提。
>
> 最后整理：2026-09-18（学期统计页 / 学分字段；此前的整体整理停在 v0.1.0 发布时，即 2026-09-16）。

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
- [x] 课程学分字段（Room **v8→v9**，`ALTER TABLE courses ADD COLUMN credit REAL`，可空、无默认）。
      口径：**留空 = 不知道**，**0 = 教务明说这门课不计学分**，两者在统计页是两个说法；
      旧的备份 / 分享口令缺这个键照常恢复
- [x] 智学北航（SPOC）扫码签到：首页加号菜单进，先登录（WebView 走统一身份认证，凭证
      KeyStore 加密落盘）再扫码；**扫到即自动提交**，无确认页，他班的码交服务端判定；
      相册选图与手输签到码两条兜底；课前提醒可带「扫码签到」按钮（设置 → 通知与提醒）
      ⚠️ 解码库只打进 **arm64-v8a**（换取 +3.70MB 而非 +20.2MB），其余 ABI 与 x86_64
      模拟器上进这一页会自动降级为相册 + 手输，没有实时取景
      ⚠️ **真机联调未跑过**：老师端二维码的字面内容是唯一没取到证的环节，解析器按三形态
      兼容。偏差与待答问题见 `docs/BUAA_SPOC_SIGNIN_PLAN.md` §11、`docs/KNOWN_ISSUES.md` §11

## 不做的内容

本项目明确不包含 AI 助手 / AI 导入 / 智能推荐等 AI 功能。

暂不考虑做国际化，目前文案均为中文硬编码。

## 待实现

- [ ] 多套节次方案（单套时间编辑 + **按首节时间/每节时长/课间自动推算整表**已完成，见 `SmartPeriods`）
- [ ] 桌面组件背景的真实高斯模糊（当前做法：采样壁纸 → 缩小再放大得到廉价模糊 + 叠加用户背景色，
      `WidgetBackgroundRenderer`；受同样的 Android 14 壁纸限制，该版本上回退纯色圆角底）
- [ ] 图片 / PDF 导入
- [ ] 完整个性化外观（壁纸取景 / 横竖屏独立配置；模糊/亮度/缩放/面板磨砂半径已完成）
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
      （周表滚动+翻周 / 课次行↔24 小时时间轴来回切 / 今日视图 / 设置页滚动）。
      **扫码页与 WebView 导入页刻意不进 profile**：前者会让每次冷启动为走不到的 CameraX+MLKit
      帧回调付编译成本，后者是系统组件在编译、ART 管不着。
      ⚠️ 这六个场景**没有连过设备**，锚点全部取自源码（本工程无 `testTagsAsResourceId`，
      只有 `By.desc` / `By.text` 可用；核不到的分组标题退化成坐标级 swipe），
      在已播种库上的实际可跑性靠下面这一次生成来验。

      **生成与验收步骤（要连设备，由编排者按序执行）**：
      1. **先播种**，条件是：库里有一个学期 + 多门课，且 `currentWeek` 落在学期**中间**
         （第 1 周和最后一周都不行：「下一周」/「上一周」有一侧是禁用态，翻周那步会空跑）；
         `schedule_settings` 里 `privacy_consent_at` 与 `onboarding_completed=true` 两个门都要过，
         否则冷启动落在引导页、采到的是引导页的方法；时间轴模式保持默认（课次行），
         切换场景要从默认态起步。**生成前不要 `pm clear`**，那会把播种一起清掉。
      2. `./gradlew :app:generateBaselineProfile`（可加 `--stacktrace`）。它驱动
         `:benchmark:connectedNonMinifiedReleaseAndroidTest` → `:collectNonMinifiedReleaseBaselineProfile`
         → `:app:mergeReleaseBaselineProfile` → `:app:copyReleaseBaselineProfileIntoSrc`。
      3. 产物落在 **`app/src/main/baselineProfiles/`**（当前只有占位 `.gitkeep`）：
         常规 profile 与 startup profile 是两份并列的文件，都要 commit 入库，不要加进 .gitignore。
      4. `./gradlew :app:assembleRelease` 重装到机上，然后
         `adb shell dumpsys package com.buaa.schedule | grep -i profile`：
         预期看到 `primaryProfile=` / `secondaryProfile=` 指向 `/data/misc/profiles/cur/<uid>/com.buaa.schedule/primary.prof`
         一类的路径，且 `profileVersion=` 不再是空；安装后状态里 `Verify` 之后会走一次
         `speed-profile`。API 26–30 上没有 `ProfileInstallerInitializer` 就没有这一行，
         这也是为什么那枚依赖要显式钉住。
      5. **验收对照**（这步才是收益本身，用 `HomeStartupBenchmark` 现成的 None vs Partial）：
         `./gradlew :benchmark:connectedNonMinifiedReleaseAndroidTest`
         跑 `HomeStartupBenchmark#startupWithoutCompilation` 与 `#startupWithBaselineProfile`，
         比 `TotalTime` 的分布。注意：插件侧我没找到任何按类过滤的机制，
         所以 `generateBaselineProfile` 很可能把 `HomeStartupBenchmark`（5 轮 × 2 组）一起跑掉，
         多花十几分钟不算错；只想跑采集就补
         `-Pandroid.testInstrumentationRunnerArguments.class=com.buaa.schedule.benchmark.InteractionBaselineProfileGenerator`。
