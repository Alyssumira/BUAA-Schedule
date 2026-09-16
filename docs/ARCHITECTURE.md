# 架构总览

> 面向新接手的开发者：读完应能定位"某功能改哪里"。
> 约定：本文描述的是源码事实，README 只写能力清单。

## 1. 分层

```
ui/（Compose 界面 + 各自的 state）
 └─ ScheduleViewModel（唯一 ViewModel，所有界面共享）
     └─ data/repository/ScheduleRepository（唯一数据门面）
         ├─ data/local/*Dao + *Entity（Room，schema 版本化在 app/schemas/）
         ├─ data/import/*（北航教务抓取与解析）
         ├─ data/backup/*（备份/恢复）
         └─ domain/（纯 Kotlin：模型、周次计算、冲突检测、导入计划）
```

- `domain/` 不依赖 Android 类，全部逻辑都应该是可 JVM 单测的纯函数。
- `data/local/` 之外**不允许**直接碰 Room；写库一律走 Repository。
- ViewModel 用 `withImportLock` 把所有导入入口互斥，避免并发抓取/写入。

## 2. 数据与"当前学期"

- `courses` 表：一行 = 一个排课片段（同一天非连续节次就是一个 `periods: List<Int>`）。
- `courses.sourceGroupKey` 把同一门课的多个片段串起来；`isManualOverride` 保护手动修改不被再次导入覆盖。
- **"当前学期" = `semesters` 表中 id 最大的一行**（`ORDER BY id DESC LIMIT 1`）。
  因此"多课表切换"（`Repository.switchSemester`）就是把目标学期行删除后按新 id 重插；
  两张表之间没有外键级联，切换不会丢课。
- 覆盖导入（`replaceSemesterCourses`）会**清空该学期的课程再写入**，
  所以导入逐条预览里"取消勾选"必须把已存在的课程原样塞回去（见 `resolveImportSelection`）。

## 3. 北航导入链路（最特殊的一块）

```
BuaaLoginScreen（WebView 统一身份认证）
  → 登录成功跳到 byxt 首页 → BuaaInPageFetcher 在【页面上下文】里 fetch 接口
  → BuaaScheduleParser.parseArrangedList → showPendingImport → 用户确认 → 入库
```

三条硬约束（都踩过坑）：

1. `evaluateJavascript` **不会 await Promise**，页面内 `fetch` 的结果必须
   写进 `window.__buaaFetch[id]` 全局槽位、由原生侧轮询读回（见 `WebViewEvaluateJavascriptContractTest`）。
2. 保留的登录 WebView **必须留在窗口内**（`BuaaWebSession` 的 1×1 隐藏宿主），
   脱离窗口后页面内异步 JS 不会执行，刷新课表会恒定超时。
3. 教务接口鉴权认页面上下文，原生 OkHttp 复刻必 401 —— 不要试图"优化"成原生请求。

失败路径**不要清登录会话**：`CookieManager.removeAllCookies` 会把 SSO TGT 一起删掉，
用户被迫重新登录。

## 4. 液态玻璃管线

```
MainActivity → SceneBackground（绘制壁纸/渐变，并录制进 LayerBackdrop）
            → CompositionLocalProvider(LocalSceneBackdrop)
                 └─ 各页面 GlassSurface / liquid 组件 → 采样 backdrop 做折射
```

- 录制层里**只有壁纸/渐变**，明度遮罩（scrim）画在录制层之外，
  否则降亮度会连带压暗所有玻璃表面。
- 壁纸模糊加在 `SceneBackground` 的外层 `Modifier.blur`，同样不进录制层。
- `GlassSurface` 在无 backdrop / API<31 时自动降级为 tint + 描边（见 `LiquidGlass.kt`）。
- `Personalization` 只用 SharedPreferences，**新增个性化字段不需要 Room 迁移**。

## 5. 桌面组件

- `widget/WidgetCommon` 是 5 个 Provider（今日 / 明日 / 下一节 / 本周列表 / 本周网格）的共享渲染器。
- 外观按 `appWidgetId` 存在 `WidgetAppearanceStore`，由 `WidgetConfigActivity` 配置。
- RemoteViews 用到的能力：`setTextViewText` / `setTextColor` / `setViewVisibility` /
  `setInt`（背景图 `setColorFilter`）/ `setFloat`（`setAlpha`）/ `setImageViewBitmap` /
  `setRemoteAdapter` + `setEmptyView`（可滚动的课程列表与网格）/
  `setOnClickPendingIntent` + `setPendingIntentTemplate` + `setOnClickFillInIntent`（整卡与单行点击）。
- 刷新是事件驱动（数据变化 + 每日零点），`updatePeriodMillis=0`，没有轮询。

## 6. 提醒

- `ReminderEntity(courseId, enabled, advanceMinutes)` 逐课程配置。
- 同一时刻只保留**一条** AlarmManager 闹钟（下一节课），数据变化后由
  `afterDataChangedInternal()` 重排；模式为"系统日历"时不注册应用内闹钟。
