# 测试与 CI

> 从 `README.md` 挪出来的一节：README 面向装 App 的人，这张「测试覆盖」表是**逐卡会变的账**
> （每一张加测试的卡都得回来改它），所以它该和门禁读数住在 `docs/` 里，而不是住在首页。
> 挪之前它在 README 的「给想改代码的人 → 测试」一节，已经错过两次（仪器测试那格 `65`、
> 单元测试那格 `1585 | 186`，两处都由实测订正）。
>
> 数与门禁实跑的读数以 [`STATUS.md`](STATUS.md) 里各卡记的那一组为准；本页只讲
> "有哪几族、怎么跑、怎么数"，不复制门禁结论。

## 怎么跑

```bash
./gradlew testDebugUnitTest          # 单元测试（JVM，不需要设备）
./gradlew connectedDebugAndroidTest  # 仪器测试（模拟器或真机）
```

CI 里一律写 `:app:` 前缀（裸任务名会被 Gradle 匹配到所有子工程，把 `:benchmark` 一起拉进来）。

## 覆盖表

| 类型 | 用例数 | 文件数 | 覆盖范围 |
| --- | --- | --- | --- |
| 单元测试 | 1675 | 190 | 按 `app/src/test/java/com/buaa/schedule/` 的七个包报方向：`domain`（周次与教学周、冲突检测与归并、导入规划、节次窗口与连堂、课次投影与逐周文本导出、今日与逐日排程、学期统计与负载趋势、课程元信息格式）/ `data`（教务抓取与真实返回、ICS 与文本解析与往返、签到码解析与拒绝分档、iClass 接口与提交 URL、备份 schema 与凭证排除名单、本地迁移链、分享编解码、撤销、日历查询与同步计划、导出）/ `reminder`（提醒排程与明日预告、课堂铃与续排、实况岛文案与倒计时、唤醒锁取证、前台服务降级）/ `ui`（首页几何与顶栏与页头条、日时间轴与滑动切日、导入冲突文案与逐条勾选、统计页接线、签到页帧流与兜底引擎与相机 3A、编辑器与空节次、节假日标注、环境自检）/ `widget`（外观与短名与显示字段与翻周、冷启动重建、快照脏 key 与刷新、圆角与玻璃图源与背景烘焙）/ `core`（设计系统与玻璃档位、底栏解墨、课程色板与主题槽位、图表、启动请求）/ `update`（Gitee 发布解析与安装包完整性）。跨包还有一族**接线守卫**：文件名带 `Guard` 的 35 枚全部吃"读 main 源码数出现次数"那把尺子（整个 test 树里这样读源码的文件是 70 枚）—— 逐条判据认类名，本页不抄清单 |
| 仪器测试 | 66 | 14 | Room 迁移 / Repository 提醒写入与事务 / Widget 刷新新鲜度与渲染契约与外观存档与数据缓存 / WebView 会话保留与 evaluateJavascript 契约 / 教务 Cookie 与 iClass 签到 id 两份加密存储的落盘与两边隔离 / 课堂铃生命周期 / 壁纸解码 / 日历同步部分失败 |

仪器测试跑在 API 29 + API 34 模拟器上（CI 同配置）：Room 迁移与 WebView 相关用例需要真实
Framework 环境，API 34 一档覆盖的是 Android 14 那批行为收紧里我们自己写得动断言的那些
（精确闹钟默认拒绝等）。上表"壁纸解码"那一格钉的是**自选图片**的解码路径，不是系统桌面壁纸
的读取 —— 后者在仪器测试里没有断言。而"读不到桌面壁纸"这件事的真因也在仪器测试之外查清的：
不是"Android 14 起平台禁了"，是本应用从未声明 `READ_EXTERNAL_STORAGE`（其后还有一道 app-op），
零权限只读得到壁纸的**颜色**（`getWallpaperColors`）—— 口径与取证过程见
[`docs/KNOWN_ISSUES.md`](KNOWN_ISSUES.md) §1。

那句"CI 同配置"的对应处在本仓 `.github/workflows/android.yml`：`instrumented` job 的矩阵写的是
`api-level: [ 29, 34 ]`，跑的是 `script: ./gradlew :app:connectedDebugAndroidTest --stacktrace`；
`build` job 那一步跑 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`。
（T98 在 `4b6376d` 上逐条回读过这三行原文；本页刻意不写行号 —— 行号会漂，原文串不会。）

## 这几个数怎么复算

**列名先说清**：第二列「用例数」是 `@Test` 方法的枚数，第三列「文件数」是 `.kt` **源文件**的
枚数 —— 它**不等于** JUnit 报告里的 testsuite 数（一个文件里可以有不止一枚测试类）。
当前单测这一族是 190 个文件跑出 196 枚 testsuite：190 + 6 = 196，那 6 枚差额逐枚对过 ——
`ScheduleChartsT51Test.kt` 装着 `ChartGeometryTest` + `ChartDescriptionTest`（两枚，类名与文件名
都不同）、`WeekCourseCountsTest.kt` 多一枚 `DayTimelineSegmentsTest`、`ImportPlannerTest.kt`
多一枚 `CourseFilterTest`、`WeekGridSummaryTest.kt` 装着三枚（外加 `WeekGridDensityTest` 与
`WidgetItemKeyTest`）、`WidgetAppearanceTest.kt` 多一枚 `WidgetTodayHighlightTest`。
两列的口径必须同一把尺子，所以仪器测试那一行同样是**文件数**（14）。

- 文件数（本机可用，秒级）：
  `find app/src/test -name "*.kt" | wc -l` ⇒ **190**，
  `find app/src/androidTest -name "*.kt" | wc -l` ⇒ **14**。
  这 190 个文件**每一个**都至少含一枚 `@Test`（按"@Test 为空即列出"的办法数过，零命中），
  所以"文件数"与"测试类所在文件数"在这一族是同一个数。
- 用例数（离线可读，作为交叉核对）：`grep -rho "@Test" app/src/test --include=*.kt | wc -l`
  ⇒ **1675**，与下面 XML 实测同值。
- 用例数与 testsuite 数（以门禁为准）：跑完 `:app:testDebugUnitTest --rerun-tasks` 之后数
  `app/build/test-results/testDebugUnitTest/*.xml` 的 `<testcase>` / `<testsuite>` 节点，
  **不要从控制台摘要抄**：

  ```bash
  python -c "import glob, xml.etree.ElementTree as ET; \
  t=[ET.parse(f).getroot() for f in glob.glob('app/build/test-results/testDebugUnitTest/*.xml')]; \
  print('tests', sum(len(r.findall('.//testcase')) for r in t), \
        'suites', len(t), \
        'failures', sum(int(r.get('failures',0)) for r in t), \
        'errors', sum(int(r.get('errors',0)) for r in t), \
        'skipped', sum(int(r.get('skipped',0)) for r in t))"
  ```

  T98 这一档在 `ai/T98`（基点 `4b6376d`，本卡只改注释与文档）上实跑两次
  `:app:testDebugUnitTest --rerun-tasks`，两回读数逐格相同：
  **1675 tests / 196 suites / 0 failures / 0 errors / 0 skipped**，与编排者在 `d334917`
  上取的那组地板读数也逐格相同 ⇒ 这一族本轮没有增删。
- 仪器测试的用例数**只能静态数**（`grep -rho "@Test" app/src/androidTest --include=*.kt | wc -l`
  ⇒ 66）：这台机器没有可用的模拟器，66 是静态计数而不是"跑过"。

## 为什么这张表容易说谎

上表的四个数里有三个（1675 / 190 / 66）会随任何一次加测试而变，而加测试的卡通常只记得改
`docs/STATUS.md`。所以：改数的纪律是**先跑再改**，改哪一格就在回执里给出那一格的复算命令；
不许拿旧值顺手 `+N`。「覆盖范围」那一列以前是一份逐族清单，抄不全就等于说谎，现在只按包报
方向、细节认 `app/src/test` 的类名。
