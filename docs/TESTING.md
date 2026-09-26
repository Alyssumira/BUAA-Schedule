# 测试与 CI

> 从 `README.md` 挪出来的一节：README 面向装 App 的人，这张「测试覆盖」表是**逐卡会变的账**
> （每一张加测试的卡都得回来改它），所以它该和门禁读数住在 `docs/` 里，而不是住在首页。
> 挪之前它在 README 的「给想改代码的人 → 测试」一节，已经错过两次（仪器测试那格 `65`、
> 单元测试那格 `1585 | 186`，两处都由实测订正）。
>
> **这一页与 [`STATUS.md`](STATUS.md) 各管一头，别再让两头都自称权威**（旧版那句"数与门禁实跑的读数
> 以 STATUS 里各卡记的那一组为准"判作废，理由有三条，最后一条是本轮撞上的）：
> ① STATUS 是**逐卡台账**，本仓规矩 (A) 类永不订正 ⇒ 它记的是"那张卡收单那一刻的读数"，天生是历史；
> ② 现值必须**连着复算命令**一起给，而 STATUS 里没有命令，只有读数 ⇒ 拿它当现值用等于让人相信一个
> 没法自查的数；③ STATUS **会整段缺席**：`bb7924a` 上 STATUS 最后一节是「清盘与 `ai/T93` 预置」，
> T103…T106 四张卡一条门禁读数都没记进去（`wc -l docs/STATUS.md` ⇒ 2904），此时"以 STATUS 为准"
> 指向的是一个根本没有这一格的页 ⇒ 那句只能作废，不是改措辞。
> 分工钉死：**本页管现值 + 每格的复算命令**（数旁边就是命令，数不对就自己重算）；**STATUS 管"某张卡
> 当时跑出门禁什么读数"**，按 (A) 类原貌保留。两页各有对的时候，**现值认本页的复算命令，不认任何一页
> 里的静态数字** —— 包括本页上表那六格。

## 怎么跑

```bash
./gradlew testDebugUnitTest          # 单元测试（JVM，不需要设备）
./gradlew connectedDebugAndroidTest  # 仪器测试（模拟器或真机）
```

CI 里一律写 `:app:` 前缀（裸任务名会被 Gradle 匹配到所有子工程，把 `:benchmark` 一起拉进来）。

## 覆盖表

| 类型 | 用例数 | 文件数 | 覆盖范围 |
| --- | --- | --- | --- |
| 单元测试 | 1695 | 194 | 按 `app/src/test/java/com/buaa/schedule/` 的七个包报方向：`domain`（周次与教学周、冲突检测与归并、导入规划、节次窗口与连堂、课次投影与逐周文本导出、今日与逐日排程、学期统计与负载趋势、课程元信息格式）/ `data`（教务抓取与真实返回、ICS 与文本解析与往返、签到码解析与拒绝分档、iClass 接口与提交 URL、备份 schema 与凭证排除名单、本地迁移链、分享编解码、撤销、日历查询与同步计划、导出）/ `reminder`（提醒排程与明日预告、课堂铃与续排、实况岛文案与倒计时、唤醒锁取证、前台服务降级）/ `ui`（首页几何与顶栏与页头条、日时间轴与滑动切日、导入冲突文案与逐条勾选、统计页接线、签到页帧流与兜底引擎与相机 3A、编辑器与空节次、节假日标注、环境自检）/ `widget`（外观与短名与显示字段与翻周、冷启动重建、快照脏 key 与刷新、圆角与玻璃图源与背景烘焙）/ `core`（设计系统与玻璃档位、底栏解墨、课程色板与主题槽位、图表、启动请求）/ `update`（Gitee 发布解析与安装包完整性）。跨包还有一族**接线守卫**：文件名带 `Guard` 的 39 枚全部吃"读 main 源码数出现次数"那把尺子（整个 test 树里这样读源码的文件是 73 枚）—— 上表这四个数与这一格里的两枚，复算命令全在下一节，逐条可直接粘贴（表格单元格里放不下带管道的命令：这一行只有 5 枚列分隔符，多一枚就断列）；逐条判据认类名，本页不抄清单 |
| 仪器测试 | 66 | 14 | Room 迁移 / Repository 提醒写入与事务 / Widget 刷新新鲜度与渲染契约与外观存档与数据缓存 / WebView 会话保留与 evaluateJavascript 契约 / 教务 Cookie 与 iClass 签到 id 两份加密存储的落盘与两边隔离 / 课堂铃生命周期 / 壁纸解码 / 日历同步部分失败 |

仪器测试跑在 API 29 + API 34 模拟器上（CI 同配置）：Room 迁移与 WebView 相关用例需要真实
Framework 环境，API 34 一档覆盖的是 Android 14 那批行为收紧里我们自己写得动断言的那些
（精确闹钟默认拒绝等）。上表"壁纸解码"那一格钉的是**自选图片**的解码路径，不是系统桌面壁纸
的读取 —— 后者在仪器测试里没有断言。而"读不到桌面壁纸"这件事的真因也在仪器测试之外查清的：
不是"Android 14 起平台禁了"，是本应用从未声明 `READ_EXTERNAL_STORAGE`（其后还有一道 app-op），
零权限只读得到壁纸的**颜色**（`getWallpaperColors`）—— 口径与取证过程见
[`docs/KNOWN_ISSUES.md`](KNOWN_ISSUES.md) §1（那一节的标题是「组件读不到系统桌面壁纸：真因是
「权限 + app-op」两道闸，不是 API 档次」；`§N` 是**序号**、比标题脆，本轮起照规矩 ① 与标题同框）。

那句"CI 同配置"的对应处在本仓 `.github/workflows/android.yml`：`instrumented` job 的矩阵写的是
`api-level: [ 29, 34 ]`，跑的是 `script: ./gradlew :app:connectedDebugAndroidTest --stacktrace`；
`build` job 那一步跑 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`。
（T98 在 `4b6376d` 上逐条回读过这三行原文；本页刻意不写行号 —— 行号会漂，原文串不会。**本轮在同一棵
`bb7924a` 上再逐条回读一遍，三行原文一字未动 ⇒ 仍成立**；本轮只给出门禁那一步的行号一处：
`.github/workflows/android.yml:38` 就是上面那串四个任务的命令行，下面「为什么不钉成守卫」那一节要引它。）

## 这几个数怎么复算

**列名先说清**：第二列「用例数」是 `@Test` 方法的枚数，钉死的写法是**行首（含缩进）第一个 token 就是
`@Test`** 那把尺（不是"这串字符出现几次"，理由见下一节那条换尺记录），第三列「文件数」是 `.kt`
**源文件**的枚数 —— 它**不等于** JUnit 报告里的 testsuite 数（一个文件里可以有不止一枚测试类）。
当前单测这一族是 194 枚文件跑出 200 枚 testsuite：194 + 6 = 200。**差额枚数没变、出资人也没换** ——
但这句每一轮都是**重量过**而不是接着抄的，因为 T104 / T105 / T106 / T110 四张卡各自动过这一族，而它们
贡献的形状各不相同：T104 与 T105 各添一枚守卫文件（`CourseEditorSaveErrorClearPairingGuardTest` 名下 2 枚
`@Test`、`CalendarSyncTargetPairingGuardTest` 名下 6 枚），T106 净添 **0 枚文件** —— 它先在 `76b75fc`
立了一枚 `CalendarSyncPermissionFlagClearGuardTest`，又在 `db235e4` 按红线把它删掉、将可达路径枚举
**折回 T105 那枚文件**（于是那枚文件 6 枚 → 7 枚）。**T110 是第四张动这一族的卡，贡献形状与 T106 同一款、
与 T104/T105 不同款**：它没有新立文件，而是往同一枚 `CalendarSyncTargetPairingGuardTest.kt` 里添 3 枚
`@Test`（① 那一族添偏好与缓存两半、③ 那一族添移除链那一档）⇒ 那枚文件 **7 枚 → 10 枚**
（复算 `grep -cE '^[[:space:]]*@Test' app/src/test/java/com/buaa/schedule/ui/CalendarSyncTargetPairingGuardTest.kt`
⇒ 10，上一版是 7 —— 这一枚数过去只能靠点名，今天它自己有了一条只读文件的复算命令）；文件列 **+0**。
**上一轮**三张卡添的都是"一枚文件装一枚顶层类"的形状 ⇒ 两列各 +2、用例 +9（2 + 6 + 1），**一枚都没进差额**；
T110 添的是"一枚既有文件里多几枚 `@Test`"的形状 ⇒ suite +0、用例再 +3 ⇒ **四张卡累计：两列各 +2、
用例 +12（2 + 6 + 1 + 3），仍是一枚都没进差额**，那 6 枚还是下面那五枚文件出的（第六枚点名的是
减数）。判据是一条命令，零命中就等于"没有多类文件"：

```bash
for f in $(find app/src/test -name "*.kt"); do
  n=$(grep -cE '^(public |internal |private |abstract |open |sealed |data |value )*class ' "$f")
  [ "$n" -gt 1 ] && echo "$n $f"
done                                                # ⇒ 6 枚文件：3 + 2 + 2 + 2 + 2 + 2 = 13 枚顶层类
```

13 枚类塞在 6 枚文件里 ⇒ 多出来 13 − 6 = **7** 枚；这 7 枚里有一枚名下零 `@Test`（下面那枚 `Quad`），
扣掉它才是 **6**。逐枚点名（括号里是该类名下的 `@Test` 枚数，**T108 那一遍**逐枚与当时那批门禁 XML 的
`tests="…"` 对过；本轮 T111 换一条**不依赖 XML** 的静态命令把它逐枚重量了一遍 —— 判据是"一枚类声明到
下一枚类声明之间"的那些行首 `@Test`，六枚文件 `6d6d121` 上的读数与下面六条一字未动）：

```bash
for f in $(find app/src/test -name "*.kt"); do
  [ "$(grep -cE '^(public |internal |private |abstract |open |sealed |data |value )*class ' "$f")" -gt 1 ] || continue
  echo "== $f"
  awk '/^(public |internal |private |abstract |open |sealed |data |value )*class /{ln=NR; sub(/^.*class /,""); split($0,a,/[^A-Za-z0-9_]/); c=a[1]; t[c]=0; L[c]=ln}
       /^[[:space:]]*@Test/{t[c]++}
       END{for(k in t) printf "   %d  %s  @Test=%d\n", L[k], k, t[k]}' "$f" | sort -n
done   # ⇒ 13/9 · 6/8 · 11/2 · 5/3/3 · 11/4 · 7/0（最后一枚是 Quad，名下 0 枚 —— 它就是那枚减数）
```

- `ScheduleChartsT51Test.kt` 装着 `ChartGeometryTest`（13）+ `ChartDescriptionTest`（9）—— 两枚类名
  与文件名都不相同，所以这枚文件**不给**出一枚叫 `ScheduleChartsT51Test` 的 suite；
- `WeekCourseCountsTest.kt`（6）多一枚 `DayTimelineSegmentsTest`（8）；
- `ImportPlannerTest.kt`（11）多一枚 `CourseFilterTest`（2）；
- `WeekGridSummaryTest.kt` 装着三枚：`WeekGridSummaryTest`（5）+ `WeekGridDensityTest`（3）+
  `WidgetItemKeyTest`（3）—— 只有它一枚出 2 枚差额；
- `WidgetAppearanceTest.kt`（11）多一枚 `WidgetTodayHighlightTest`（4）；
- `GlassJankDecisionTest.kt`（7）多一枚 `private data class Quad`（**0**）—— 它**不出**差额，
  它是那 7 减到 6 的减数。

1 + 1 + 1 + 2 + 1 + 0 = 6。**这一串本轮（T111，基点 `6d6d121`）又自己走了一遍，没有照抄上一轮的加法**：
每枚文件的差额 = 它名下"≥1 枚 `@Test` 的顶层类"数 − 1 ⇒ `ScheduleChartsT51Test.kt` 2 − 1 = **1**、
`WeekCourseCountsTest.kt` 2 − 1 = **1**、`ImportPlannerTest.kt` 2 − 1 = **1**、`WeekGridSummaryTest.kt`
3 − 1 = **2**、`WidgetAppearanceTest.kt` 2 − 1 = **1**、`GlassJankDecisionTest.kt` 名下只剩 1 枚挂得住
`@Test` 的类（`Quad` 零枚、不成 suite）1 − 1 = **0** ⇒ 1 + 1 + 1 + 2 + 1 + 0 = **6**，与
194 + 6 = 200 对得上（两枚各自复算：`grep -rh "^class " app/src/test --include=*.kt | wc -l` ⇒ **200**、
`find app/src/test -name "*.kt" | wc -l` ⇒ **194**）。两列的口径必须同一把尺子，所以仪器测试那一行同样是**文件数**（14）。

这 6 枚还有一条与静态尺**完全无关**的对法，本轮在那批门禁 XML 上跑过：把 200 枚 `<testsuite>` 的类名
简名与 194 枚 `.kt` 的文件名 basename 做两次 `comm` ⇒ "有 suite、无同名文件"恰好 **7** 枚
（`ChartGeometryTest` / `ChartDescriptionTest` / `DayTimelineSegmentsTest` / `CourseFilterTest` /
`WeekGridDensityTest` / `WidgetItemKeyTest` / `WidgetTodayHighlightTest`），"有文件、无同名 suite"
恰好 **1** 枚（`ScheduleChartsT51Test.kt`，就是上面说的那枚两类型文件）⇒ 193 + 7 = 200。静态那条
"7 枚多出来的类再减掉 `Quad`"与这条 XML 名集差，两把尺各走各的路，落在同一个 6 上 —— 这比"两个数
看着一样"值钱，因为它排除了"多出来的类名恰好都跟某个文件名重名"这种巧合。⚠️ 这半条同样只在**那批
XML 还在**的那棵 worktree 里可复算，出处见上面「现值 1,692 / 200」那一段。

**差额只可能来自"一枚文件里多枚顶层类"，别的原因在本仓都不成立**：JVM 单测这一族没有一枚用
`@Nested`、`@ParameterizedTest` 或 `@RunWith`（下面「testsuite 数」那条给命令），所以"内部类各自成 suite""参数化
拆成多枚"这两条常见来路在这里枚数为 0，suite 与测试类一一对应。而"测试类"的判据还要再窄一格：
**一枚顶层 `class` 声明，且自己名下挂着 ≥1 枚 `@Test`**。全仓顶层 `class` 声明共 201 枚，比 200 多的
那一枚是 `core/designsystem/GlassJankDecisionTest.kt:186` 的 `private data class Quad`（表驱动用的
四元组容器，名下一枚 `@Test` 都没有）⇒ 它不成 suite。这条边界值得写死：数「顶层类」与数「测试类」
在这棵树差 1（201 对 200），抄错的人分不出自己抄的是哪一个。

- 文件数（本机可用，秒级）与"每枚文件都有用例"这条前提：

  ```bash
  find app/src/test -name "*.kt" | wc -l                # 单测「文件数」⇒ 194
  find app/src/androidTest -name "*.kt" | wc -l         # 仪器测试「文件数」⇒ 14
  for f in $(find app/src/test -name "*.kt"); do [ "$(grep -cE '^[[:space:]]*@Test' "$f")" = "0" ] && echo "$f"; done
  ```

  第三条按"@Test 为空即列出"数：零命中 ⇒ 194 枚文件**每一枚**都至少含一枚 `@Test`，所以"文件数"与
  "测试类所在文件数"在这一族是同一个数（仪器测试那 14 枚同一条判据，也是零命中）。这一条本轮从
  `grep -c '@Test'` 换成带行首锚的那把，只为与下面「用例数」那一格同一把尺：旧写法会把 KDoc 里当词写的
  `@Test` 当成"这个文件有用例"，于是**一枚全靠注释提到 `@Test` 的空文件能骗过它**。⚠️ 单位上这里
  不用换：`-c` 数命中行、`-o | wc -l` 数出现次数，两把只在**要报枚数**时不可混（下面那一格要报 1695，
  所以它吃 `-o`）；这一条只判"是不是 0"，命中行数为 0 与出现次数为 0 是同一件事，换锚就够了。

- 用例数（离线可读；**这一格的尺子本轮换过，理由写在下面**）：

  ```bash
  grep -rhoE '^[[:space:]]*@Test' app/src/test --include=*.kt | wc -l         # 单测用例数 ⇒ 1695
  grep -rhoE '^[[:space:]]*@Test' app/src/androidTest --include=*.kt | wc -l  # 仪器测试用例数 ⇒ 66
  ```

  **这一格本轮跟着 T110 走过一次**：上一版写 1692，那是 `bb7924a`（T106 收单树）上的读数，按这一族的
  规矩**留着不抹**（它就是那一遍的读数，而且下面「现值 1,692 / 200」那一段还指着它）；本页基点
  `6d6d121`（T110 已合进 master）之上，上面那条命令给 **1695**。增量不是新文件：T110 在同一枚
  `ui/CalendarSyncTargetPairingGuardTest.kt` 里净添 3 枚 `@Test`、**零枚文件**（复算
  `grep -cE '^[[:space:]]*@Test' app/src/test/java/com/buaa/schedule/ui/CalendarSyncTargetPairingGuardTest.kt`
  ⇒ 10，上一版是 7）⇒ 「文件数」那一列与 194↔200 那枚差额都不动，只有用例 +3。
  ⚠️ 这一格同时第一次拿到**增量层面的双尺交叉验证**：T110 的收单账记着同一棵树上门禁 XML 也是
  **1,695 tests / 200 suites**（`docs/STATUS.md:3076`，同节 `docs/STATUS.md:3093` 那句"行首锚 `@Test`
  全仓 1,695 == 门禁 XML 1,695"）⇒ 新尺与 XML 第二次在**同一棵 commit** 上同值（第一次是 T108 那趟的
  1692 / 200）；本卡按红线没跑 gradle，那组读数是编排者记的，出处已点名。

  **为什么换尺**：旧版那两条是 `grep -rho "@Test" …`，它数的是**这串字符在 test 树里出现的次数**，
  不是用例数。在 `8605707`（T102 写这页时那棵树）上两把尺同值、都与该点门禁 XML 的 `<testcase>`
  合计 1683 相等 ⇒ 那时它是把好尺；**今天这棵 `bb7924a` 上同一把旧尺给 1696，而门禁是 1692** ⇒
  **旧尺多算 4 枚**。多出来的不是丢信用例，是**把 `@Test` 当词写的句子**：

  ```bash
  grep -rho '@Test' app/src/test --include=*.kt | wc -l                        # 旧尺 ⇒ 1696（多算 4）
  grep -rn '@Test' app/src/test --include=*.kt | grep -vE ':[0-9]+:[[:space:]]*@Test'
  ```

  第二条直接点名那 4 处（旧尺对新尺的差集），全部落在 `ui/CalendarSyncTargetPairingGuardTest.kt`
  **一枚**文件里，行号 `:35` / `:58` / `:251` / `:324`。三处是 KDoc（`:58` 那句就是「七枚 ``@Test``
  全是纯 JVM 源码核对」），**`:251` 那一处不是注释** —— 它长在 `assertEquals` 的报错字符串里
  （「…本文件第三枚 ``@Test``（可达路径枚举）也会跟着红」）。卡面把四处都说成"注释/KDoc"，这一处
  要驳回：字符串字面量同样被旧尺数成一枚用例，而它恰恰是**守卫最天然会写出的那种句子**。⇒ 这一族
  污染不会自己消失，只要还有人在报错消息里指认别的用例，旧尺就会继续虚报，而虚报的枚数正好
  骗过"看起来像用例数"的那种眼球。

  ⚠️ **新尺也不常青，它只是把污染面从"行内任意位置"收窄到"行首"**。它自己的判据（今天零命中）：

  ```bash
  grep -rnE '^[[:space:]]*@Test' app/src/test --include=*.kt \
    | grep -vE ':[0-9]+:[[:space:]]*@Test[[:space:]]*$'                        # ⇒ 0
  ```

  这条列的是"被新尺数到、但行首锚之后还挂着别的东西"的行 ⇒ 0 命中意味着今天那 1695 枚**枚枚都是
  光秃秃的注解行**（本仓写法是四空格缩进的裸 `@Test`，全树没有一行写成 `@Test fun foo()`）。
  残余风险就一种形状：**某行的第一个非空 token 是 `@Test` 而它不是注解** —— 把 KDoc 的 ` * ` 沟槽
  丢了直接写 `@Test` 起头、或在一对三引号里嵌一段测试代码样本，都会中招。那种行新尺照数，且**页面
  上这三条命令没有一条能发现它**（它们与它是同一把尺）⇒ 这一格的最终仲裁仍是下面那副门禁 XML；
  新尺只是今天与它同值，不是永远与它同值。

- testsuite 数与"为什么是 6 枚差额"（离线可数，本机两条路都给）：

  ```bash
  grep -rh "^class " app/src/test --include=*.kt | wc -l                          # 静态 ⇒ 200
  grep -rhE "^(public |internal |private |abstract |open |sealed |data |value )*class " \
       app/src/test --include=*.kt | wc -l                                        # 顶层 class 声明总数 ⇒ 201
  grep -rho "@Nested\|@ParameterizedTest\|@RunWith" app/src/test --include=*.kt | wc -l   # ⇒ 0
  ```

  第一条吃的是本仓写法：测试类一律写成顶格的裸 `class X {`，而全仓唯一一枚带 modifier 的顶层类就是那枚
  `private data class Quad`（两把尺的差集实测就是它那一行，一条命令：
  `diff <(grep -rhnE '^(public |internal |private |abstract |open |sealed |data |value )*class ' \
  app/src/test --include=*.kt | cut -d: -f2- | sort) <(grep -rh '^class ' app/src/test --include=*.kt | sort)`），
  于是 201 − 200 = 1 正好等于"名下零枚 `@Test` 的顶层类"。**写法一变这条就骗人**：
  有人给测试类加 modifier ⇒ 少报；有人拿裸 `class` 声明一枚不含 `@Test` 的顶层辅助类 ⇒ 多报。所以它是
  秒级自查，不是权威。第三条是那两条"常见来路"为零的证据。⚠️ 单位上这里**不用**在两条 `class` 尺前加
  `-o`：锚在行首 ⇒ 一行最多命中一次，"命中行数"与"出现次数"必然相等（这与上面「用例数」那格不同，
  那里的 `@Test` 不设锚时一行可以出现两回）。

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
  不是"看起来该相等"。（⚠️ 这半条对照**只在 `app/build/test-results` 还留着那趟读数的时候可复现**，
  而它现在已经在**本卡这棵 worktree 里没了**：`ls app/build/` 直接 `No such file or directory` ——
  worktree 是新建的，那 194 份 XML 从没跟过来。⇒ 静态与实测的等价性是"当时比过"的证据，不是常青命令，
  也不是常青**产物**。）本轮能在这棵树上复算的只剩它**不依赖 XML 的那半边**：那句话说"该点静态数出
  194、`@Test` 的 grep 值 1658"，这两枚拿 `git grep` 在 `634c6d6` 上按**同一棵 commit** 复算 ⇒
  `git grep -h -I -E "^class " 634c6d6 -- 'app/src/test' | wc -l` 给 **194**、
  `git grep -o -I -E "@Test" 634c6d6 -- 'app/src/test' | wc -l` 给 **1658**，两处都与那句历史叙述
  逐字相同 ⇒ 它没漂（它本来就钉在 commit 上，钉得住是应该的；漂的是"现在那批 XML 还在不在"）。

  T98 那一档在 `ai/T98`（基点 `4b6376d`，那张卡只改注释与文档）上实跑两次
  `:app:testDebugUnitTest --rerun-tasks`，两回读数逐格相同：**1675 tests / 196 suites / 0 failures /
  0 errors / 0 skipped**，与编排者在 `d334917` 上取的那组地板读数也逐格相同 ⇒ 那说的是 **T98 那一轮
  这一族没有增删**，不是本页上表的现值。下面这几组同理，全是**历史收单证据**、按 (A) 类保留原貌不订正：
  `docs/STATUS.md:2811`（起头「收单证据（我自己跑的，不是引它的日志）」，集成态 `378dedd` 冷全量）与
  `docs/STATUS.md:2830`（起头「收单证据（我自己在 `87c2225` 上跑的）」）**各写 1,683 tests / 198 suites
  / 0 失败 / 0 errors / 0 skipped**，`docs/STATUS.md:2793`（T99 自己在集成对象 `cdfd9ab` 上跑的冷全量）
  **1,681 tests / 197 suites**，它自己写着"对 1,675/196 恰是 +6 tests / +1 suite，全出自 T99 那枚新
  守卫"⇒ 1,681→1,683 那两档"每枚新守卫文件各加 1 枚 suite、差额出资人没换"有门禁 XML 层的证据，不只靠
  本页那条静态尺子。⚠️ 这三枚 `STATUS.md:NNNN` 指针本轮逐条回读过，在 `bb7924a` 上仍落在原来那段的起头
  上 —— STATUS 是**往后追加**的台账，前面的行不漂。

  **现值 1,692 / 200 这一组在 STATUS 里还没有在册的那一步**：它是编排者 09-26 在 `ai/T106`（收单树
  `bb7924a`）上跑的门禁，T103…T106 四张卡都还没写进 STATUS（`bb7924a` 上 `wc -l docs/STATUS.md` 给
  2904，最后一节是「清盘与 `ai/T93` 预置」，最新一枚在册的卡是 T102）⇒ 这一格只能按**带出处的读数**落地，
  不许写成常青事实。本轮在那趟门禁留在 `ai/T106` 那棵 worktree 的 XML 上**只读**复核过一遍（没跑 gradle）：
  `TEST-*.xml` 200 份、`<testcase>` 合计 **1692**、`<testsuite>` 元素 **200**、failures / errors /
  skipped 三格全 **0** ⇒ 与上面那条新尺逐格相同。⚠️ 这句复验自己也过期得很快：那批 XML 会被下一趟
  `--rerun-tasks` 整个换掉，且**只在那棵 worktree 里存在** —— 本卡这棵连 `app/build/` 都没有，在这里跑
  上面那条 python 只会读到空目录，别把"读不到"读成"门禁没跑"。

- Guard 那一族与"读源码"那一族（上表最后一格里那两个数）：

  ```bash
  find app/src/test -name "*Guard*.kt" | wc -l                   # ⇒ 39
  grep -rl "src/main/java" app/src/test --include=*.kt | wc -l   # ⇒ 73
  comm -23 <(find app/src/test -name "*Guard*.kt" | sort) \
           <(grep -rl "src/main/java" app/src/test --include=*.kt | sort) | wc -l   # ⇒ 0
  comm -23 <(grep -rl "src/main/java" app/src/test --include=*.kt | sort) \
           <(grep -rl "File(" app/src/test --include=*.kt | sort) | wc -l           # ⇒ 0
  ```

  第三条撑着"39 枚 Guard **全部**吃读源码那把尺子"那句（零命中 = 没有一枚 Guard 落在这 73 之外）；
  第四条撑着"这 73 枚每一枚都真的在开文件"那句 ⇒ 今天它是真读数。73 的构成是 39 枚 Guard + 34 枚
  别的族 —— ⚠️ **这一格里只有 Guard 那一路在动**：T104 与 T105 各添一枚 Guard，73 相对上一版只涨 2，
  "非 Guard 的那 34 枚"在两版盘面上是同一批。口径两处要挑明：前两条形如 `grep -rl`，数的是**文件枚数**
  （`-l` 一个文件最多给一条），所以"某文件里提了三回 `src/main/java`"不会被数成三枚；而第二条的判据
  只是**那串字面量在文件里出现过**，哪天有人在注释里提一句 `src/main/java` 而没开文件，第二条就开始
  虚报、第四条当场红 ⇒ 那时改判据而不是改数。上面「用例数」那一格就是这句话的现成先例。

- 仪器测试的用例数**只能静态数**（上面那条 grep ⇒ 66），因为它在本机从来没有留下一份 `:app:` 的
  connected 结果。⚠️ 旧版给的理由是"这台机器没有可用的模拟器"，T102 已经把它换掉过一次；本轮再看
  现状，那句理由给得比当时更差：**它举的两处证据都是"某个 worktree 的 `build/` 目录当下长什么样"**
  （"旧版说的 `app/build/outputs/` 里没有 `androidTest-results`"、"全仓唯一的 connected 产物长在
  `benchmark/build/outputs/androidTest-results/` 底下"）。这类"没有"是**逐 worktree 的临时状态**，
  不是事实：本卡这棵 `ai/T108` 上 `app/build/` 与 `benchmark/build/` **两个都不存在**，`ls` 给的是
  `No such file or directory` 而不是"目录里没有那一项" —— 于是那句话在这里连复算都复算不出个意思，
  而在隔壁那棵跑过 benchmark 的 worktree 上它又恰好成立。⇒ 换成一条不依赖构建产物的理由：
  **`docs/STATUS.md` 整本里 `connectedDebugAndroidTest` 只出现 3 次**
  （`grep -c connectedDebugAndroidTest docs/STATUS.md` ⇒ 3），三处都在讲 CI 与 `MigrationTest` 的
  设计，**没有一处是一次本地 connected 跑完的读数**；而 CI 确实跑它（上面 `android.yml` 那两行），
  产物却从不落回仓库。⇒ 66 今天仍是静态计数，理由是"本机没有 :app: 的 connected 读数在册"，
  不是"机器上没模拟器"（`ls ~/.android/avd/` 给 `buaa36.avd` 与 `buaa36.ini`，那台 AVD 现在也在），
  也不是"某个 build 目录里少一个子目录"。这一格同时是本页最该警惕的一种句子：**"某文件不存在"
  看着像证据，其实只是当时的盘面**。

## 为什么这张表容易说谎

这一页挂着两类数。一类是**会变大的现值**：上表的 1695 / 194 / 66 / 14，复算一节的 200 / 201 / 39 / 73，
锚点一节命令旁边那几枚 —— 它们枚枚都会随"加一次测试"或"写一次文档"而动，而加测试的卡通常只记得改
`docs/STATUS.md`。另一类是**要求读数为零（或为定值）的自证判据**：每一枚测试文件都含 `@Test`、
`@Nested`/`@ParameterizedTest`/`@RunWith` 三件套为 0、每枚 Guard 都落在"读源码"那一族里、仪器测试那
14 枚也每枚含 `@Test`、读源码那 73 枚每一枚都带 `File(`。前一类的病是过期，后一类的病是**悄悄变成
非零** —— 而后一类恰恰是前一类可信的理由，所以它过期了更没人回头看。

本页**第四次**失手就是证据，而且又是整页：T98 把这张表从 README 搬过来的时候订正过两处（仪器测试那格
`65`、单元测试那格 `1585 | 186`），T99 与 T100 各加一枚守卫文件之后没人回头改这一页，六格里五格同时
过期、只有仪器测试那一格（66 | 14）因为那两张卡没碰 androidTest 而侥幸还对着 —— 那一版由 T102 收掉。
本轮是**同一族病复发第二次**：T104（改 main + 新守卫 2 枚）、T105（新守卫 6 枚）、T106（重钉守卫 +
新增 1 枚）连着三张卡各加了测试，整页现值又一次全部过期，而且这次连"数法"本身都坏了（上面那格
`@Test` 的尺子）。⚠️ 本轮订正之后**六格里只剩仪器测试那一格没动过**（66 | 14），因为这三张卡没碰
androidTest —— 与 T102 那次是同一个"侥幸"形状：它不是维护出来的，是没人碰出来的。⇒ 光靠"改得更勤"
治不好这件事，所以本页每个数旁边都放了一条命令：**下一个人数得出来，就不必相信本页**。

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

- **那句历史叙述仍成立**，按规矩 (A) 保留原貌，本轮再回读一遍：今天这枚文件的 101–107 行正是那段
  KDoc —— 101 是 `/**`、107 是 `*/`、108 是 `val skippedOccurrences: Int = 0`（`awk 'NR>=101 && NR<=108'`
  读 `app/src/main/java/com/buaa/schedule/ui/ScheduleViewModel.kt`；T106 动过这枚文件，但那次是
  **同行改写**，所以这一段没被推着走）。
- **本页旧版那条指针漂了 3 行**：旧版把 #151 那笔新账记在 `docs/STATUS.md:2811`，而新账实际起在 2814
  （别与上面那条搞混 —— 引用「收单证据」那段的 2811 至今是对的，漂的是"新账"这一条）。
  制造这次漂移的恰好是本页基点那枚
  commit：`c55ddc5` 在 `docs/STATUS.md:2243` 处 +4 / −1 ⇒ 其后整体后移 3 行
  （`git show --stat c55ddc5 -- docs/STATUS.md`）。它漂得无声无息，是因为下面第一条普查命令的尺子
  **只认 `\.kt:[0-9]+`** —— 而 `X.md:NNN` 这一族全仓有 18 枚（本页 8 枚，其余 10 枚长在
  `docs/STATUS.md` 的 9 枚与 `docs/derived-field-audit.md` 的 1 枚里），普查命令一条都量不到；本轮之前
  本页那唯一一枚就是它。⚠️ 上一句那三个数是**本轮（T108）在 `bb7924a` 上重量的现值**，别与下一句
  "本轮开始时它是 9…现在 16"搞混 —— 那句是 **T102 那一轮的实验账**（(A) 类，保留原貌，它当时量的
  就是 16）。这一格本身就是"每格带命令"的活样本：它一轮之内被两批订正各添过指针 ——
  数得出来才不会把它抄旧。这正好解释了规矩 ① 为什么要求"行号与符号名/原文片段同框"：行号错了，还能凭那句起头找回去。

规矩全文写在 [`docs/derived-field-audit.md`](derived-field-audit.md) 头部那节「锚点保鲜声明」
（行号必须与符号名/原文片段同框、文档头部标注锚点对应的 commit、改主源码插删行顺手核引用、
**(A) 历史叙述保留原貌 / (B) 现状描述逐条回读**）。复算命令与测试计数同一副尺子，但**要跑四条**，
一把尺子盖不住两族指针：

```bash
grep -rhoE '[A-Za-z0-9_]+\.kt:[0-9]+' docs README.md | wc -l       # 全仓 .kt 锚点枚数 ⇒ 578
grep -rhoE '[A-Za-z0-9_.-]+\.md:[0-9]+' docs README.md | wc -l     # 全仓 .md:NNN 指针枚数 ⇒ 18（上一条看不见这一族）
grep -rhoE '[A-Za-z0-9_]+\.kt:[0-9]+' docs/derived-field-audit.md | wc -l   # 单档 ⇒ 268
grep -coE '[A-Za-z0-9_]+\.kt:[0-9]+' docs/TESTING.md               # 本页贡献几枚 ⇒ 4
```

**这四枚数是本轮在最后一次编辑之后重量的**，与审计档 §6.10 那三格（本档 268 / 全仓 578 / 换成命中行
那把 146）**各自独立量到同一个数** ⇒ 两页的尺子今天对得上；⚠️ 但这两页**互相**是对方最大的一处污染源：
往任一页里多点名一处代码，另一页的全仓那一格就跟着动。

四件事不写清，下一个人还会抄错：

1. **旧版那句"T101 之后 517"复现不出来。** 同一把尺子在 T101 的三枚 commit 上分别给 516
   （`f122152`）、518（`87c2225`）、524（`2572c4f`，本页基点 `c55ddc5` 同值）—— 517 不是其中任何一个，
   它多半是某次未提交工作树上的读数。⇒ 现值按本轮量出的写。
2. **单位别混，两把尺今天各自给数。** 上面用的是 `grep -o`：**一行里两枚锚点算两条**（⇒ 578）。换成
   `git grep -n -I -E "[A-Za-z0-9_]+\.kt:[0-9]+" -- '*.md' | wc -l` 数的是**命中的行数**，本轮在
   `bb7924a` 上量到 **394**；而 T102 那一轮同一把尺在它自己的树上给 357（本轮用
   `git grep -n -I -E "[A-Za-z0-9_]+\.kt:[0-9]+" 8605707 -- '*.md' | wc -l` 复算 ⇒ **357**，一字未漂）。
   那 37 枚的差本轮逐档对过账：`git grep -c` 两版逐档相减 ⇒ **审计档 +36、STATUS +1、本页 +0**，
   全部落在 T103 与 T106 往 §6 里点的那些名上 —— 别把这种差当成尺子坏了，它是**文档自己写多了**。
   同一份审计档两把尺的差更悬殊：268 对 146。两个读数都对，只是两把尺子；把它们当同一个数的人会
   凭空"丢掉"一两百枚锚点。
3. **"本页贡献几枚"这一格是本轮自己动过的地方，要说清谁在贡献。** 本页现在 4 枚（命中行数与出现次数
   今天相等：4 行、每行一枚），而贡献者**不是**上面这几行命令：命令里的正则字面量匹配不上自己
   （`.kt` 前面挨着的是 `+`，不是单词字符）—— 本轮往页里粘进一批新命令（用例数那三条、多类文件那枚
   for 循环、`File(` 那条判据、两把 `class` 尺的差集、两把锚点尺的复算），**这一格一枚没动**，4 → 4，
   这是逐条量过的而不是推的。真正在贡献的是正文里点名代码的 `X.kt:NNN`：旧版只有
   `ui/ScheduleViewModel.kt:101` 一枚（它在这页出现两次，算两枚），T102 为说清"199 枚顶层类 / 198 枚
   suite"（那两个数是**当时的**现值，本轮已是 201 / 200）又点了 `GlassJankDecisionTest.kt:186` 一枚，
   同样出现两次。⇒ 结论留着有用而且本轮**又验了一遍**：**往本页粘命令不需要替它加"自己算一枚"的
   余量，但点名一处代码就得打算重数这一格。**

## 为什么不把这页的数钉成一枚守卫

直觉方案是加一枚 `@Test`，断言"本页写的数 == 盘上数出来的数"。本轮判：**不做**。先说清它在哪些时刻
会响，免得判得含糊 —— 它读的是 `docs/`，而 `docs/` 不在测试任务的输入面里（`app/build.gradle.kts` 里
`guardReadWorkingTreeFiles` 逐项点名：`src/main/java`、`src/main/res`、清单、proguard、构建脚本、版本
目录、`schemas`，没有 `docs/` 与 `README.md`；**本轮回读过那份清单 —— 上一轮（T108）那句"七项一字未增未减"
是数错了，不是清单长了**：它自己那串点名就漏了 `../kyant-backdrop/consumer-rules.pro`（⑦ 那一档，
`ReleaseForensicLogSurvivalTest` 扫兄弟模块的规则文件），而这一项自 T92① `1a7b99f` 起就在清单里，
`bb7924a` 上也是八项 ⇒ 现状是 **八项一字未增未减**，"没有 `docs/` 与 `README.md`"这半句仍成立。复算：
`awk '/^val guardReadWorkingTreeFiles = files\(/,/^\)/' app/build.gradle.kts | grep -cE '^[[:space:]]+"'`
⇒ 8，八项逐条是 `src/main/java`、`src/main/res`、`src/main/AndroidManifest.xml`、`proguard-rules.pro`、
`build.gradle.kts`、`../gradle/libs.versions.toml`、`../kyant-backdrop/consumer-rules.pro`、`schemas`
—— 这一格是**回读出来的**而不是接着抄的，因为它就是本节论证的地基，不能让它在没人看的时候悄悄变），于是：

- **A 侧（加测试、忘了改本页）它会红。** 加了测试类就是加了 class 字节，`testDebugUnitTest` 本来就要
  重跑，守卫照常评估。这一侧它是可靠的。
- **B 侧（只改本页那个数）它不重跑。** 有人把 1695 手滑成 1663，磁盘状态当场是红的，而 Gradle 判
  UP-TO-DATE、端一次缓存绿灯 —— 这就是 #86 / #92① 记过的同一个形状（那节注释里的实验 B 量过等价
  案例：改一行注释、零字节进字节码 ⇒ 编译重跑而测试判 UP-TO-DATE）。`docs/` 留在输入面之外还是
  当时的**决定**，不是漏：同一节写着"为什么不铺到整个仓库：那等于『改 README 也重跑 1,660 枚测试』，
  是拿一种错换另一种"。⚠️ 那句 `1,660` 是**逐字引构建脚本注释**（`app/build.gradle.kts` 第 424 行那句），
  它自己已经落后于现值 1695 —— 引它是因为要引的是"当时的决定"，不是现值；那行注释在红线上，本卡不动，
  记进明留。

B 侧那道假绿要补上，只有把 `../docs/TESTING.md` 塞进 `guardReadWorkingTreeFiles` —— 那是构建脚本改动，
本卡在红线之外；而且改完就把 T92① 关掉的账重新付一次（文档改一个标点 ⇒ 重跑全量 1695 枚）。
另有一处本机证明本轮给不出：卡面要求"先证明它在这台机器上真的会重跑"，而本机门禁那一步跑的是
`testDebugUnitTest --rerun-tasks`（`docs/STATUS.md:2811`、`:2830` 两节都记着 executed 数），
`--rerun-tasks` 恰好**把 B 侧的假绿遮掉**、让守卫看起来永远可靠；CI 的 build job 跑的却是不带
`--rerun-tasks` 的那条（`.github/workflows/android.yml:38`）。⇒ 同一枚守卫在本地"每次必跑"、在 CI
"看缓存"，红绿不一致会被读成"改文档 CI 就红"，而真相是"取决于上一次谁跑了什么"。这一半我跑不了
gradle，证不出来；要证，请编排者收单时补一发：把本页某格的数改错 → 不带 `--rerun-tasks` 单跑那枚
守卫 → 看它是 UP-TO-DATE 还是 FAILED。

即使证明它确实会重跑，仍然判不做，理由有两条比缓存更根本：

1. **它的判据与本页的命令是同一只手写的规则，不提供独立信息。** 本轮这副尺子写了三版才敢用：第一版的
   注释剥离器把 `/**` 数成两层嵌套、吃掉大半文件，只认出 20 枚类；第二版改用类栈，数出 206 枚（把
   `private data class Quad` 这类顶层辅助类与函数内的假嵌套都算成 suite），`@Test` 只归上 1673 枚，
   比 grep 少 10；到第三版钉死"顶格 `class` 声明 **且** 名下挂着 `@Test`"才与真 XML 逐枚对上
   （194 = 194，见上面那条对照）。⚠️ 这一整段是 **T102 那一轮在自己的盘面上做的实验账**（20 / 206 /
   1673 / 194 四枚数都属 (A) 类，本轮按规矩不动它们；本轮只把"194 = 194"那半句的可复算性标出来 ——
   它依赖的就是上面那段已经消失的 XML 产物，今天在这棵树上复算不出来）。把这副规则誊进 Kotlin 当断言，
   失败方向要么烦人（有人加一枚不含
   `@Test` 的顶层辅助类，suite 数变了、文档没错 ⇒ 红），要么更糟（正则写松一点，它数出 201 而文档写
   200，它"agree"了，读者却以为这格被钉住了）。**守卫的价值全在它的尺子比被钉的那份东西可信**，这里不成立。
2. **它红的时候，最省事的动作正是这一页的病。** 断言"文档 == 盘上"变红，最短路径是把文档那个数改成
   盘上的数 —— 数字誊写。而本页旧版整页五格过期，没有一格是**算错**的，全是**没人回头算**；治疗是
   把尺子放到数旁边（本轮已经一格一条地放了），让下一个人自己数得出，而不是把同一个数再抄一份到
   测试代码里、多出一处会过期的副本。真要钉，值得钉的是形状而不是数值：**"每个现值旁边必须挂着一条
   `# … ⇒ 读数` 形式的命令"** 这一条数值断言表达不了，形状断言又必须读 `docs/` ⇒ 回到 B 侧那个假绿。

顺带一条已经量过、不是推理的事实，说明"文档对构建是惰性的"这句在本仓有产物层证据：`docs/STATUS.md:2830`
记着在 `87c2225` 上冷重建，`assembleRelease` 出的包 **7,247,127 B 与地板逐字节相同** ⇒ 改文档一个字节
不进 dex；同理不进测试任务的输入指纹。如果哪天仍然要机器兜这一页，便宜的形状是**别走测试任务**：
在 CI 的 build step 之后直接跑本页那几条命令、把读数与文档 diff 掉 —— 没有输入面，就没有输入面这一族病。

