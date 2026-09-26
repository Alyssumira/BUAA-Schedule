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
| 单元测试 | 1683 | 192 | 按 `app/src/test/java/com/buaa/schedule/` 的七个包报方向：`domain`（周次与教学周、冲突检测与归并、导入规划、节次窗口与连堂、课次投影与逐周文本导出、今日与逐日排程、学期统计与负载趋势、课程元信息格式）/ `data`（教务抓取与真实返回、ICS 与文本解析与往返、签到码解析与拒绝分档、iClass 接口与提交 URL、备份 schema 与凭证排除名单、本地迁移链、分享编解码、撤销、日历查询与同步计划、导出）/ `reminder`（提醒排程与明日预告、课堂铃与续排、实况岛文案与倒计时、唤醒锁取证、前台服务降级）/ `ui`（首页几何与顶栏与页头条、日时间轴与滑动切日、导入冲突文案与逐条勾选、统计页接线、签到页帧流与兜底引擎与相机 3A、编辑器与空节次、节假日标注、环境自检）/ `widget`（外观与短名与显示字段与翻周、冷启动重建、快照脏 key 与刷新、圆角与玻璃图源与背景烘焙）/ `core`（设计系统与玻璃档位、底栏解墨、课程色板与主题槽位、图表、启动请求）/ `update`（Gitee 发布解析与安装包完整性）。跨包还有一族**接线守卫**：文件名带 `Guard` 的 37 枚全部吃"读 main 源码数出现次数"那把尺子（整个 test 树里这样读源码的文件是 71 枚）—— 上表这四个数与这一格里的两枚，复算命令全在下一节，逐条可直接粘贴（表格单元格里放不下带管道的命令：这一行只有 5 枚列分隔符，多一枚就断列）；逐条判据认类名，本页不抄清单 |
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
当前单测这一族是 192 枚文件跑出 198 枚 testsuite：192 + 6 = 198。差额**枚数没变、出资人也没换** ——
这句本轮重新量过而不是接着抄：T99 与 T100 各添一枚守卫文件，`ScanGiveUpReasonDerivationGuardTest`
名下 6 枚 `@Test`、`CalendarSyncDiffClearPairingGuardTest` 名下 2 枚，两枚**各自只有一枚顶层类**，
所以这一轮加的是「2 枚文件 / 2 枚 suite / 8 枚用例」，那 6 枚差额还是下面这五枚文件出的。逐枚点名
（括号里是该类名下的 `@Test` 枚数）：

- `ScheduleChartsT51Test.kt` 装着 `ChartGeometryTest`（13）+ `ChartDescriptionTest`（9）—— 两枚类名
  与文件名都不相同，所以这枚文件**不给**出一枚叫 `ScheduleChartsT51Test` 的 suite；
- `WeekCourseCountsTest.kt`（6）多一枚 `DayTimelineSegmentsTest`（8）；
- `ImportPlannerTest.kt`（11）多一枚 `CourseFilterTest`（2）；
- `WeekGridSummaryTest.kt` 装着三枚：`WeekGridSummaryTest`（5）+ `WeekGridDensityTest`（3）+
  `WidgetItemKeyTest`（3）—— 只有它一枚出 2 枚差额；
- `WidgetAppearanceTest.kt`（11）多一枚 `WidgetTodayHighlightTest`（4）。

1 + 1 + 1 + 2 + 1 = 6。两列的口径必须同一把尺子，所以仪器测试那一行同样是**文件数**（14）。

**差额只可能来自"一枚文件里多枚顶层类"，别的原因在本仓都不成立**：JVM 单测这一族没有一枚用
`@Nested`、`@ParameterizedTest` 或 `@RunWith`（下面「testsuite 数」那条给命令），所以"内部类各自成 suite""参数化
拆成多枚"这两条常见来路在这里枚数为 0，suite 与测试类一一对应。而"测试类"的判据还要再窄一格：
**一枚顶层 `class` 声明，且自己名下挂着 ≥1 枚 `@Test`**。全仓顶层 `class` 声明共 199 枚，比 198 多的
那一枚是 `core/designsystem/GlassJankDecisionTest.kt:186` 的 `private data class Quad`（表驱动用的
四元组容器，名下一枚 `@Test` 都没有）⇒ 它不成 suite。这条边界值得写死：数「顶层类」与数「测试类」
在这棵树差 1，抄错的人分不出自己抄的是哪一个。

- 文件数（本机可用，秒级）与"每枚文件都有用例"这条前提：

  ```bash
  find app/src/test -name "*.kt" | wc -l                # 单测「文件数」⇒ 192
  find app/src/androidTest -name "*.kt" | wc -l         # 仪器测试「文件数」⇒ 14
  for f in $(find app/src/test -name "*.kt"); do [ "$(grep -c '@Test' "$f")" = "0" ] && echo "$f"; done
  ```

  第三条按"@Test 为空即列出"数：零命中 ⇒ 192 枚文件**每一枚**都至少含一枚 `@Test`，所以"文件数"与
  "测试类所在文件数"在这一族是同一个数（仪器测试那 14 枚同一条判据，也是零命中）。

- 用例数（离线可读）：

  ```bash
  grep -rho "@Test" app/src/test --include=*.kt | wc -l          # 单测用例数 ⇒ 1683
  grep -rho "@Test" app/src/androidTest --include=*.kt | wc -l   # 仪器测试用例数 ⇒ 66
  ```

  1683 与下面 XML 的 `<testcase>` 合计同值（同一台机器上对过，见下一条末）。

- testsuite 数与"为什么是 6 枚差额"（离线可数，本机两条路都给）：

  ```bash
  grep -rh "^class " app/src/test --include=*.kt | wc -l                          # 静态 ⇒ 198
  grep -rhE "^(public |internal |private |abstract |open |sealed |data |value )*class " \
       app/src/test --include=*.kt | wc -l                                        # 顶层 class 声明总数 ⇒ 199
  grep -rho "@Nested\|@ParameterizedTest\|@RunWith" app/src/test --include=*.kt | wc -l   # ⇒ 0
  ```

  第一条吃的是本仓写法：测试类一律写成顶格的裸 `class X {`，而全仓唯一一枚带 modifier 的顶层类就是那枚
  `private data class Quad`，于是 199 − 198 = 1 正好等于"名下零枚 `@Test` 的顶层类"。**写法一变这条就骗人**：
  有人给测试类加 modifier ⇒ 少报；有人拿裸 `class` 声明一枚不含 `@Test` 的顶层辅助类 ⇒ 多报。所以它是
  秒级自查，不是权威。第三条是那两条"常见来路"为零的证据。

  权威仍是门禁的 XML：跑完 `:app:testDebugUnitTest --rerun-tasks` 之后数
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

  上面那条静态尺子与这副 XML 在本机**对过一枚不差**：拿 `634c6d6`（09-25 那趟冷门禁的树，
  `app/build/test-results/testDebugUnitTest/` 里那 194 份 XML 就是它留下的）作对照 —— 静态数出 194，
  XML 也是 194，`<testsuite name>` 的集合逐枚相同，每枚 suite 名下的 `<testcase>` 数一处不差，合计 1658
  等于该点 `@Test` 的 grep 值。对法：`git archive 634c6d6 app/src/test | tar -x -C /tmp/x`，再对
  `/tmp/x/app/src/test` 跑上面第一条，与 XML 的 name 集合比 ⇒ 静态那条可以放心用，理由是**这次比过**，
  不是"看起来该相等"。（⚠️ 这半条对照只在 `app/build/test-results` 还留着那趟读数的时候可复现 ——
  下一趟冷门禁会把整个目录换掉。静态与实测的等价性属于"当时比过"的证据，不是常青命令。）

  T98 那一档在 `ai/T98`（基点 `4b6376d`，那张卡只改注释与文档）上实跑两次
  `:app:testDebugUnitTest --rerun-tasks`，两回读数逐格相同：**1675 tests / 196 suites / 0 failures /
  0 errors / 0 skipped**，与编排者在 `d334917` 上取的那组地板读数也逐格相同 ⇒ 那说的是 **T98 那一轮
  这一族没有增删**，不是本页上表的现值。现值 1683 / 198 的门禁证据在 `docs/STATUS.md:2811`（那段起头
  「收单证据（我自己跑的，不是引它的日志）」，集成态 `378dedd` 冷全量）与 `docs/STATUS.md:2830`（起头
  「收单证据（我自己在 `87c2225` 上跑的）」）两处，各写着 **1,683 tests / 198 suites / 0 失败 / 0 errors /
  0 skipped** ⇒ 表与门禁没有分叉，分叉的只是这一页旧版的那四个数。

- Guard 那一族与"读源码"那一族（上表最后一格里那两个数）：

  ```bash
  find app/src/test -name "*Guard*.kt" | wc -l                   # ⇒ 37
  grep -rl "src/main/java" app/src/test --include=*.kt | wc -l   # ⇒ 71
  comm -23 <(find app/src/test -name "*Guard*.kt" | sort) \
           <(grep -rl "src/main/java" app/src/test --include=*.kt | sort) | wc -l   # ⇒ 0
  ```

  第三条撑着"37 枚 Guard **全部**吃读源码那把尺子"那句（零命中 = 没有一枚 Guard 落在这 71 之外）；
  71 的构成是 37 枚 Guard + 34 枚别的族。口径要挑明：第二条数的是**那串字面量在文件里出现过**，
  本机另核过这 71 枚每一枚都带 `File(` ⇒ 今天它就是真读数；哪天有人在注释里提一句 `src/main/java`，
  这条就开始虚报，那时改判据而不是改数。

- 仪器测试的用例数**只能静态数**（上面那条 grep ⇒ 66），因为它在本机从未跑过：
  `ls app/build/outputs/` 里没有 `androidTest-results` 这一目录，全仓唯一的 connected 产物长在
  `benchmark/build/outputs/androidTest-results/` 底下。⚠️ 这一句旧版写的理由是"这台机器没有可用的
  模拟器"，那句**已经不成立** —— `~/.android/avd/` 下确有 `buaa36.ini` 与 `buaa36.avd`，benchmark 那批
  结果的目录名就叫 `buaa36(AVD) - 16`。结论不动（66 仍是静态计数而不是"跑过"），换掉的只是理由，
  换成一条能复算的理由。

## 为什么这张表容易说谎

这一页挂着两类数。一类是**会变大的现值**：上表的 1683 / 192 / 66 / 14，复算一节的 198 / 199 / 37 / 71，
锚点一节命令旁边那几枚 —— 它们枚枚都会随"加一次测试"或"写一次文档"而动，而加测试的卡通常只记得改
`docs/STATUS.md`。另一类是**要求读数为零（或为定值）的自证判据**：每一枚测试文件都含 `@Test`、
`@Nested`/`@ParameterizedTest`/`@RunWith` 三件套为 0、每枚 Guard 都落在"读源码"那一族里、仪器测试那
14 枚也每枚含 `@Test`、读源码那 71 枚每一枚都带 `File(`。前一类的病是过期，后一类的病是**悄悄变成
非零** —— 而后一类恰恰是前一类可信的理由，所以它过期了更没人回头看。

本页第三次失手就是证据，而且这次是整页：T98 把这张表从 README 搬过来的时候订正过两处（仪器测试那格
`65`、单元测试那格 `1585 | 186`），T99 与 T100 各加一枚守卫文件之后没人回头改这一页，六格里五格同时
过期，只有仪器测试那一格（66 | 14）因为那两张卡没碰 androidTest 而侥幸还对着。⇒ 光靠"改得更勤"治不
好这件事，所以本页现在每个数旁边都放了一条命令：**下一个人数得出来，就不必相信本页**。

改数的纪律还是那条：**先跑再改**，改哪一格就在回执里给出那一格的复算命令，不许拿旧值顺手 `+N`。
本轮再钉一条同等级的：**改数不许顺手改定义** —— "文件数"这一列是 `.kt` 源文件枚数，与仪器测试那行的
14 同一把尺，换成"测试类枚数"或"suite 枚数"都会让这两行不再可比。「覆盖范围」那一列以前是一份逐族
清单，抄不全就等于说谎，现在只按包报方向、细节认 `app/src/test` 的类名。

### 行号锚点犯的是同一族错（T101）

文档里的 `文件.kt:NNN` 与上面那些数一样是**会过期的现值**：`2cf8405` 往
`ui/ScheduleViewModel.kt:101` 之前插了 7 行 KDoc，代码侧一处不红（守卫读的是 needle 文本不是行号），
`docs/` 里指路的行号却集体走偏 —— 同一族第三次踩：前两次是 #33（扫哈希锚点的死链）与 #35（实测并订正
行号锚点，两枚编号来自编排者的卡面），这一次是 #151 = T100 记在 `docs/STATUS.md:2814` 的那笔新账
（那段起头是「本卡留下一笔新账（记 #151）」）。

本轮把这两处都回读了一遍，一处仍成立、一处是本页自己欠的：

- **那句历史叙述仍成立**，按规矩 (A) 保留原貌：今天这枚文件的 101–107 行正是那段 KDoc —— 101 是
  `/**`、107 是 `*/`、108 是 `val skippedOccurrences: Int = 0`（`awk 'NR>=101 && NR<=108'` 读
  `app/src/main/java/com/buaa/schedule/ui/ScheduleViewModel.kt`）。
- **本页旧版那条指针漂了 3 行**：旧版把 #151 那笔新账记在 `docs/STATUS.md:2811`，而新账实际起在 2814
  （别与上面那条搞混 —— 引用「收单证据」那段的 2811 至今是对的，漂的是"新账"这一条）。
  制造这次漂移的恰好是本页基点那枚
  commit：`c55ddc5` 在 `docs/STATUS.md:2243` 处 +4 / −1 ⇒ 其后整体后移 3 行
  （`git show --stat c55ddc5 -- docs/STATUS.md`）。它漂得无声无息，是因为下面第一条普查命令的尺子
  **只认 `\.kt:[0-9]+`** —— 而 `X.md:NNN` 这一族全仓有 15 枚（本页 7 枚，其余 8 枚长在
  `docs/STATUS.md` 与 `docs/derived-field-audit.md` 里），普查命令一条都量不到；本轮之前本页那唯一一枚
  就是它。这一格本身就是"每格带命令"的活样本：本轮开始时它是 9，本页三处订正各添了指针之后现在 15 ——
  数得出来才不会把它抄旧。这正好解释了规矩 ① 为什么要求"行号与符号名/原文片段同框"：行号错了，还能凭那句起头找回去。

规矩全文写在 [`docs/derived-field-audit.md`](derived-field-audit.md) 头部那节「锚点保鲜声明」
（行号必须与符号名/原文片段同框、文档头部标注锚点对应的 commit、改主源码插删行顺手核引用、
**(A) 历史叙述保留原貌 / (B) 现状描述逐条回读**）。复算命令与测试计数同一副尺子，但**要跑四条**，
一把尺子盖不住两族指针：

```bash
grep -rhoE '[A-Za-z0-9_]+\.kt:[0-9]+' docs README.md | wc -l       # 全仓 .kt 锚点枚数 ⇒ 527
grep -rhoE '[A-Za-z0-9_.-]+\.md:[0-9]+' docs README.md | wc -l     # 全仓 .md:NNN 指针枚数 ⇒ 15（上一条看不见这一族）
grep -rhoE '[A-Za-z0-9_]+\.kt:[0-9]+' docs/derived-field-audit.md | wc -l   # 单档 ⇒ 218
grep -coE '[A-Za-z0-9_]+\.kt:[0-9]+' docs/TESTING.md               # 本页贡献几枚 ⇒ 4
```

三件事不写清，下一个人还会抄错：

1. **旧版那句"T101 之后 517"复现不出来。** 同一把尺子在 T101 的三枚 commit 上分别给 516
   （`f122152`）、518（`87c2225`）、524（`2572c4f`，本页基点 `c55ddc5` 同值）—— 517 不是其中任何一个，
   它多半是某次未提交工作树上的读数。⇒ 现值按本轮量出的写。
2. **单位别混。** 上面用的是 `grep -o`：**一行里两枚锚点算两条**。换成
   `git grep -n -I -E "[A-Za-z0-9_]+\.kt:[0-9]+" -- '*.md' | wc -l` 数的是**命中的行数**，同一棵树给
   357。两个读数都对，只是两把尺子；把它们当同一个数的人会凭空"丢掉"170 枚锚点。
3. **旧版那句"本行自己就贡献 1"是错的，虽然当时总数碰巧对得上。** 本页现在的贡献是 4 枚，
   而贡献者**不是**这几行命令：命令里的正则字面量匹配不上自己（`.kt` 前面挨着的是 `+`，不是单词字符）。
   真正在贡献的是正文里点名代码的那些 `X.kt:NNN` —— 旧版只有 `ui/ScheduleViewModel.kt:101` 一枚，
   本轮为说清"199 枚顶层类 / 198 枚 suite"又点了 `GlassJankDecisionTest.kt:186` 一枚。结论留着有用：
   **往本页粘命令不需要替它加"自己算一枚"的余量，但点名一处代码就得打算重数这一格。**

## 为什么不把这页的数钉成一枚守卫

直觉方案是加一枚 `@Test`，断言"本页写的数 == 盘上数出来的数"。本轮判：**不做**。先说清它在哪些时刻
会响，免得判得含糊 —— 它读的是 `docs/`，而 `docs/` 不在测试任务的输入面里（`app/build.gradle.kts` 里
`guardReadWorkingTreeFiles` 逐项点名：`src/main/java`、`src/main/res`、清单、proguard、构建脚本、版本
目录、`schemas`，没有 `docs/` 与 `README.md`），于是：

- **A 侧（加测试、忘了改本页）它会红。** 加了测试类就是加了 class 字节，`testDebugUnitTest` 本来就要
  重跑，守卫照常评估。这一侧它是可靠的。
- **B 侧（只改本页那个数）它不重跑。** 有人把 1683 手滑成 1663，磁盘状态当场是红的，而 Gradle 判
  UP-TO-DATE、端一次缓存绿灯 —— 这就是 #86 / #92① 记过的同一个形状（那节注释里的实验 B 量过等价
  案例：改一行注释、零字节进字节码 ⇒ 编译重跑而测试判 UP-TO-DATE）。`docs/` 留在输入面之外还是
  当时的**决定**，不是漏：同一节写着"为什么不铺到整个仓库：那等于『改 README 也重跑 1,660 枚测试』，
  是拿一种错换另一种"。

B 侧那道假绿要补上，只有把 `../docs/TESTING.md` 塞进 `guardReadWorkingTreeFiles` —— 那是构建脚本改动，
本卡在红线之外；而且改完就把 T92① 关掉的账重新付一次（文档改一个标点 ⇒ 重跑全量 1683 枚）。
另有一处本机证明本轮给不出：卡面要求"先证明它在这台机器上真的会重跑"，而本机门禁那一步跑的是
`testDebugUnitTest --rerun-tasks`（`docs/STATUS.md:2811`、`:2830` 两节都记着 executed 数），
`--rerun-tasks` 恰好**把 B 侧的假绿遮掉**、让守卫看起来永远可靠；CI 的 build job 跑的却是不带
`--rerun-tasks` 的那条（`.github/workflows/android.yml:38`）。⇒ 同一枚守卫在本地"每次必跑"、在 CI
"看缓存"，红绿不一致会被读成"改文档 CI 就红"，而真相是"取决于上一次谁跑了什么"。这一半我跑不了
gradle，证不出来；要证，请编排者收单时补一发：把本页某格的数改错 → 不带 `--rerun-tasks` 单跑那枚
守卫 → 看它是 UP-TO-DATE 还是 FAILED。

即使证明它确实会重跑，仍然判不做，理由有两条比缓存更根本：

1. **它的判据与本页的命令是同一只手写的规则，不提供独立信息。** 本轮数 suite 就翻车过两次：第一版
   按"顶层类"数出 206（被 `private data class Quad` 之类的辅助类与注释里的假象带偏），第二版按大括号
   深度归 `@Test` 少了 10 枚；直到改成"顶格 `class` 声明 **且** 名下挂着 `@Test`"才与真 XML 逐枚对上
   （194 = 194，见上面那条对照）。把这副规则誊进 Kotlin 当断言，失败方向要么烦人（有人加一枚不含
   `@Test` 的顶层辅助类，suite 数变了、文档没错 ⇒ 红），要么更糟（正则写松一点，它数出 199 而文档写
   198，它"agree"了，读者却以为这格被钉住了）。**守卫的价值全在它的尺子比被钉的那份东西可信**，这里不成立。
2. **它红的时候，最省事的动作正是这一页的病。** 断言"文档 == 盘上"变红，最短路径是把文档那个数改成
   盘上的数 —— 数字誊写。而本页旧版整页五格过期，没有一格是**算错**的，全是**没人回头算**；治疗是
   把尺子放到数旁边（本轮已经一格一条地放了），让下一个人自己数得出，而不是把同一个数再抄一份到
   测试代码里、多出一处会过期的副本。真要钉，值得钉的是形状而不是数值：**"每个现值旁边必须挂着一条
   `# … ⇒ 读数` 形式的命令"** 这一条数值断言表达不了，形状断言又必须读 `docs/` ⇒ 回到 B 侧那个假绿。

顺带一条已经量过、不是推理的事实，说明"文档对构建是惰性的"这句在本仓有产物层证据：`docs/STATUS.md:2830`
记着在 `87c2225` 上冷重建，`assembleRelease` 出的包 **7,247,127 B 与地板逐字节相同** ⇒ 改文档一个字节
不进 dex；同理不进测试任务的输入指纹。如果哪天仍然要机器兜这一页，便宜的形状是**别走测试任务**：
在 CI 的 build step 之后直接跑本页那几条命令、把读数与文档 diff 掉 —— 没有输入面，就没有输入面这一族病。

