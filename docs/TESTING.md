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
| 单元测试 | 1799 | 209 | 按 `app/src/test/java/com/buaa/schedule/` 的七个包报方向：`domain`（周次与教学周、冲突检测与归并、导入规划、节次窗口与连堂、课次投影与逐周文本导出、今日与逐日排程、学期统计与负载趋势、课程元信息格式）/ `data`（教务抓取与真实返回、ICS 与文本解析与往返、签到码解析与拒绝分档、iClass 接口与提交 URL、备份 schema 与凭证排除名单、本地迁移链、分享编解码、撤销、日历查询与同步计划、导出）/ `reminder`（提醒排程与明日预告、课堂铃与续排、实况岛文案与倒计时、唤醒锁取证、前台服务降级）/ `ui`（首页几何与顶栏与页头条、日时间轴与滑动切日、导入冲突文案与逐条勾选、统计页接线、签到页帧流与兜底引擎与相机 3A、编辑器与空节次、节假日标注、环境自检）/ `widget`（外观与短名与显示字段与翻周、冷启动重建、快照脏 key 与刷新、圆角与玻璃图源与背景烘焙）/ `core`（设计系统与玻璃档位、底栏解墨、课程色板与主题槽位、图表、启动请求）/ `update`（Gitee 发布解析与安装包完整性）。跨包还有一族**接线守卫**：文件名带 `Guard` 的 45 枚全部吃"读 main 源码数出现次数"那把尺子（整个 test 树里这样读源码的文件是 84 枚）—— 上表这四个数与这一格里的两枚，复算命令全在下一节，逐条可直接粘贴（表格单元格里放不下带管道的命令：这一行只有 5 枚列分隔符，多一枚就断列）；逐条判据认类名，本页不抄清单。**现状（T129 量于 `073d098`）：本行那两枚数 1,725 / 198 与这一格里的两枚 42 / 76 都是盘上现量**，上一档 1,698 / 194 / 39 / 73 量于 `f5192fb`、按本页规矩留着不抹；增量是 T121 / T122 / T127 三张卡添的四枚文件共 27 枚 `@Test`，逐格账在下面「用例数」与「Guard 那一族」两格里（仪器测试那一行的 66 / 14 本轮同两条尺复算 ⇒ 一字未动，三张卡零枚 androidTest 改动）；**现状（T135 量于 `d22a3f8`）：本行那两枚数长成 1,770 / 205，这一格里的两枚长成 45 / 81** —— 上一档 1,725 / 198 / 42 / 76 量于 `073d098`、按本页规矩留着不抹；增量是 T131 / T133 / T133b / T134 这一批添的 **7 枚新文件共 45 枚 `@Test`**（复算 `git diff --name-only 073d098..HEAD -- app/src/test` ⇒ 恰好 7 行 `A`、零行 `M`；逐枚同一条只读文件的尺给 6 / 7 / 3 / 6 / 5 / 6 / 12，和 45），涨的 3 枚 Guard 按文件名序是 `CourseManagementImportMessageWiringGuardTest`、`ConflictShiftWeekScopeWiringGuardTest`、`ManualTimeOverrideWiringGuardTest`，另 4 枚是表驱动与判据单测（`ImportPlannerManualTimeTest`、`ConflictShiftWeekScopeTest`、`ConflictWizardWeekScopeReachabilityTest`、`ManualTimeOverridePolicyTest`）；42 ⇒ 45 与 76 ⇒ 81 之间那两枚"点名的文件里出现 45 枚、出现 81 枚"的差额账、以及 81 里**第一次出现的 2 枚例外**（提到 main 路径却没开文件），都写在下面「Guard 那一族与"读源码"那一族」那一格，复算命令仍是下一节那四条；本页这一格不增行（表格单元格里放不下带管道的命令，也放不进第二枚列分隔符）；**现状（T136 量于 `1b0fefb`）：本行那两枚数长成 1,783 / 207，这一格里的两枚长成 45 / 83** —— 上一档 1,770 / 205 / 45 / 81 量于 `d22a3f8`、按本页规矩留着不抹；增量是 T128 这一批（`34a265a` + `5eadb78`）添的 **两枚新文件共 12 枚 `@Test`、加一枚既有守卫里添的 1 枚**（复算 `git diff --name-status d22a3f8..HEAD -- app/src/test` ⇒ **恰好 2 行 `A` + 2 行 `M`**；逐枚同一条只读文件的尺给 `CourseGroupAppearancePolicyTest` 6、`UndoUpdateAdmissionTest` 6、`UndoUpdateEntryGuardTest` 6 ⇒ 7、`ManualTimeOverrideWiringGuardTest` 12 ⇒ 12 ⇒ 两枚 `M` 里只有一枚添了用例 ⇒ 1,770 + 6 + 6 + 1 + 0 = **1,783**），两枚新文件的名字都不带 `Guard` ⇒ 45 那一族本轮一字未动、而那两枚 `M` 本来就在 45 里；45 ⇒ 45 与 81 ⇒ 83 之间的差额账、以及 83 里那一族**"提到 main 路径却没开文件"从 2 枚长成 3 枚**，都写在下面同一格；仪器测试那一行的 66 / 14 本轮同两条尺复算 ⇒ 仍一字未动（复算 `git diff --name-status d22a3f8..HEAD -- app/src/androidTest` ⇒ 零行，T128 两张 commit 零枚 androidTest 改动）；；**现状（T138 量于 `d7ce427`）：本行那两枚数长成 1,792 / 208，这一格里的两枚长成 45 / 84** —— 上一档 1,783 / 207 / 45 / 83 量于 `1b0fefb`、按本页规矩留着不抹；增量是 T137 那三枚 commit（`9ed876d` + `7e71aa3` + `714447b`）添的**一枚新文件 4 枚 + 两枚既有守卫里添的 3 与 2 枚**（复算 `git diff --name-status 1b0fefb..HEAD -- app/src/test` ⇒ 恰好一行 `A` + 两行 `M`；三条只读文件的尺各给 `GroupRowUndoPolicyTest` 4、`UndoUpdateEntryGuardTest` 7 ⇒ 10、`UndoManagerTest` 2 ⇒ 4 ⇒ 1,783 + 4 + 3 + 2 = **1,792**），那枚新文件的名字不带 `Guard` ⇒ 45 那一族本轮一字未动、而 84 里它自己出了一枚；45 ⇒ 45 与 83 ⇒ 84 之间的构成账（84 = 45 枚 Guard + 39 枚别的族）与那一族**"提到 main 路径却没开文件"从 3 枚长成 4 枚**，都写在下面同一格；仪器测试那一行的 66 / 14 本轮同两条尺复算 ⇒ 仍一字未动（复算 `git diff --name-only 1b0fefb..HEAD -- app/src/androidTest` ⇒ 零行，T137 三枚 commit 零枚 androidTest 改动）；本页这一格照旧不增行；**现状（T140 量于 `b76cba9`）：本行那两枚数长成 1,799 / 209，这一格里的两枚长成 45 / 85** —— 上一档 1,792 / 208 / 45 / 84 量于 `d7ce427`、按本页规矩留着不抹；增量是 T139 那两枚 commit（`a287b9f` 动 main、`b76cba9` 加档）添的**一枚新文件 5 枚 + 一枚既有守卫里添的 2 枚**（复算 `git diff --name-status 412acb8..b76cba9 -- app/src/test` ⇒ 恰好一行 `A` + 一行 `M`；两条只读文件的尺各给 `MainRowUndoPolicyTest` 5、`UndoUpdateEntryGuardTest` 10 ⇒ 12 ⇒ 1,792 + 5 + 2 = **1,799**，同一枚守卫文件 1,260 ⇒ 1,511 行），那枚新文件的名字不带 `Guard` ⇒ 45 那一族本轮零枚新守卫、一字未动，而 85 里它自己出一枚；仪器测试那一行的 66 / 14 本轮同两条尺复算 ⇒ 仍一字未动（复算 `git diff --name-only 412acb8..b76cba9 -- app/src/androidTest` ⇒ 零行，T139 两枚 commit 零枚 androidTest 改动）；本页这一格照旧不增行 |
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
当前单测这一族是 194 枚文件跑出 200 枚 testsuite：194 + 6 = 200。**现状（T129 量于 `073d098`）：198 枚文件跑出 204 枚 testsuite：198 + 6 = 204** —— 上一句那组 194 / 200 是 `f5192fb`（T117 那一档）的读数、按本页规矩留着不抹；两枚各自复算 `find app/src/test -name '*.kt' | wc -l` ⇒ **198**、`grep -rh "^class " app/src/test --include=*.kt | wc -l` ⇒ **204**。**现状（T135 量于 `d22a3f8`）：这一族长成 205 枚文件跑出 211 枚 testsuite：205 + 6 = 211**，上一句那组 198 / 204 同为 `073d098` 的读数、留着不抹；两枚同一把尺各自复算 ⇒ `find app/src/test -name '*.kt' | wc -l` 给 **205**、`grep -rh "^class " app/src/test --include=*.kt | wc -l` 给 **211**，多出来的 7 枚文件各出一枚 suite、一枚都没进差额。**现状（T136 量于 `1b0fefb`）：这一族长成 207 枚文件跑出 213 枚 testsuite：207 + 6 = 213**，上一句那组 205 / 211 同为 `d22a3f8` 的读数、留着不抹；两枚同一把尺各自复算给 **207** 与 **213**，本轮多出来的 2 枚文件仍是一枚文件装一枚顶层类、各出一枚 suite ⇒ 一枚没进差额，差额那 6 枚的出资人还是下面逐枚点名那六枚。**现状（T138 量于 `d7ce427`）：这一族长成 208 枚文件跑出 214 枚 testsuite：208 + 6 = 214**，上一句那组 207 / 213 同为 `1b0fefb` 的读数、留着不抹；两枚同一把尺各自复算给 **208** 与 **214**，本轮多出来的那一枚文件（T137 那枚新守卫）又是一枚文件装一枚顶层类 ⇒ 一枚没进差额，差额那 6 枚的出资人还是下面逐枚点名那六枚。**现状（T140 量于 `b76cba9`）：这一族长成 209 枚文件跑出 215 枚 testsuite：209 + 6 = 215**，上一句那组 208 / 214 同为 `d7ce427` 的读数、留着不抹；两枚同一把尺各自复算给 **209** 与 **215**，本轮多出来的那一枚文件（T139 那枚表驱动单测）又是一枚文件装一枚顶层类 ⇒ 一枚没进差额，差额那 6 枚的出资人还是下面逐枚点名那六枚（复算上面那两条 `for f in $(find …)` ⇒ 仍是 6 枚多类文件、名单一字未动）。**差额枚数没变、出资人也没换** ——
但这句每一轮都是**重量过**而不是接着抄的，因为 T104 / T105 / T106 / T110 四张卡各自动过这一族，而它们
贡献的形状各不相同：T104 与 T105 各添一枚守卫文件（`CourseEditorSaveErrorClearPairingGuardTest` 名下 2 枚
`@Test`、`CalendarSyncTargetPairingGuardTest` 名下 6 枚），T106 净添 **0 枚文件** —— 它先在 `76b75fc`
立了一枚 `CalendarSyncPermissionFlagClearGuardTest`，又在 `db235e4` 按红线把它删掉、将可达路径枚举
**折回 T105 那枚文件**（于是那枚文件 6 枚 → 7 枚）。**T110 是第四张动这一族的卡，贡献形状与 T106 同一款、
与 T104/T105 不同款**：它没有新立文件，而是往同一枚 `CalendarSyncTargetPairingGuardTest.kt` 里添 3 枚
`@Test`（① 那一族添偏好与缓存两半、③ 那一族添移除链那一档）⇒ 那枚文件 **7 枚 → 10 枚**
（复算 `grep -cE '^[[:space:]]*@Test' app/src/test/java/com/buaa/schedule/ui/CalendarSyncTargetPairingGuardTest.kt`
⇒ 10（**这是 `6d6d121` 那一档的读数**，本轮 T117 同一把尺已给 11，见下一段），上一版是 7 —— 这一枚数过去只能靠点名，今天它自己有了一条只读文件的复算命令）；文件列 **+0**。
**现状（T129 量于 `073d098`）：同一把只读文件的尺 ⇒ 仍 11、文件列仍 +0**（那枚文件自 T117 之后只被 T118 改过体内报错行号与一格恒真判据，零枚 `@Test` 增删；复算 `grep -cE '^[[:space:]]*@Test' app/src/test/java/com/buaa/schedule/ui/CalendarSyncTargetPairingGuardTest.kt` ⇒ 11）。
**第五、第六张是 T115 与 T116，贡献形状与 T110 同一款、与 T104/T105 不同款**（本句 T117 记，基点
`f5192fb`）：零枚新文件，往既有文件里添 `@Test` —— T115② 给 `ui/signin/IClassSignInWiringGuardTest.kt`
补第 ⑦ 枚（复算 `grep -cE '^[[:space:]]*@Test' app/src/test/java/com/buaa/schedule/ui/signin/IClassSignInWiringGuardTest.kt`
⇒ 7，基点 `6d6d121` 上是 6；紧跟其后的 T115②′ 只往那枚用例里补"四档逐字"判据、**没添新 `@Test`**，
所以它停在 7 而不是 8），T116① 给 `ui/CalendarSyncDiffClearPairingGuardTest.kt` 与
`ui/CalendarSyncTargetPairingGuardTest.kt` **各**添 1 枚（同一条只读文件的尺，把末尾文件名换成它自己
⇒ 前者 3、`6d6d121` 上是 2；后者 **11**、`6d6d121` 上是 10 —— 也就是上面那格「7 枚 → 10 枚」的后一档）。
⇒ 三枚逐文件的尺各 +1、合计 +3，suite 与文件列各 +0（复算哪几枚文件动过：
`git diff --name-status 6d6d121..f5192fb -- app/src/test` ⇒ 三行 `M`、零行 `A`）。
**第七、第八、第九张是 T121、T122、T127（T129 记，基点 `073d098`），贡献形状与 T104/T105 同款（一枚文件装一枚顶层类）、
与 T110/T115/T116 不同款（那三张零新文件）**：T121③ `d4c6c51` 新立两枚 —— `CourseDeletionPolicyTest`（表驱动判据，名下 **9** 枚）
与 `CourseDeletionWiringGuardTest`（**6** 枚）；T122① `7965270` 新立 `UndoUpdateEntryGuardTest`（**6** 枚，判 deferred、main 零改动）；
T127② `44bdc58` 新立 `CourseGroupDeletionWiringGuardTest`（**6** 枚）。逐枚尺同一把（`grep -cE '^[[:space:]]*@Test' <那枚文件>`，
四条各量的读数 **9 / 6 / 6 / 6** 都是本轮在 `073d098` 上量的）。⇒ **文件 +4（194 ⇒ 198）、suite +4（200 ⇒ 204）、
用例 +27（1,698 ⇒ 1,725 = 9 + 6 + 6 + 6）、差额仍 6**：四枚新文件各出一枚 suite、一枚都没进差额。
⚠️ 还有一格形状要记：T127① `970282a` 改判了 `UndoUpdateEntryGuardTest` 的第 ⑤ 枚（极性从"钉病"翻成"钉药"，
那枚文件里 `UNGATED_LABEL` 相关的尺从「恰好一处」改成「必须 0 处」，同一格另钉上一枚「`takeIf` 那一闸恰好一处、
且闸的是文案那一句读的同一枚变量」），**翻极性没添新 `@Test`** ⇒ 那枚文件在 `7965270` 与
`970282a` 上同一条尺都给 **6**（复算 `git show 7965270:app/src/test/java/com/buaa/schedule/ui/UndoUpdateEntryGuardTest.kt | grep -cE '^[[:space:]]*@Test'`
⇒ 6、同一条换成 `970282a` ⇒ 6）—— 与本页 T115②′ 那格"补判据不添用例所以停在 7 不是 8"是同一款账。
`CalendarSyncTargetPairingGuardTest` 与 `CalendarSyncDiffClearPairingGuardTest` 这两枚在本轮区间里被 **T118** 动过
（体内报错行号 + 删一格恒真判据），**枚数 11 / 3 一字未动** ⇒ 复算 `git diff --name-status f5192fb..073d098 -- app/src/test`
⇒ **四行 `A` + 两行 `M`**，`M` 那两行贡献 0 枚用例。
**第十张是 T128（T136 记，基点 `1b0fefb`，两枚 commit `34a265a` + `5eadb78`），把两款形状叠在同一枚卡上**：两枚新文件（T104/T105/T121/T122/T127 那一款）+ 往两枚既有守卫里添用例（T110/T115/T116 那一款）。新立的两枚各装一枚顶层类 —— `data/repository/CourseGroupAppearancePolicyTest`（组写那一支的逐行判据，名下 **6** 枚）与 `ui/UndoUpdateAdmissionTest`（四把维度的压栈判据内核，名下 **6** 枚）；动了既有守卫的两枚里只有 `ui/UndoUpdateEntryGuardTest` 长了用例：**6 ⇒ 7**（`5eadb78` 把 T122 埋的那枚扳机层从"钉前置未落地"翻成正向判据，**一格没删**），而 `ui/home/ManualTimeOverrideWiringGuardTest` 是 **12 ⇒ 12** ⚠️ —— 它在同一区间被 `5eadb78` 动过（701 ⇒ 726 行）却**一枚 `@Test` 没添**。⇒ 四条只读文件的尺各给 **6 / 6 / 7 / 12**（同一把 `grep -cE '^[[:space:]]*@Test' <那枚文件>`，两枚 `M` 的 pre 读数用 `git show d22a3f8:` 前缀同一条尺复算 ⇒ 6 与 12）⇒ **文件 +2（205 ⇒ 207）、suite +2（211 ⇒ 213）、用例 +13（1,770 ⇒ 1,783 = 6 + 6 + 1 + 0）、差额仍 6**（两枚新文件各出一枚 suite、一枚没进差额）。⚠️ 本段要驳一笔**卡面自己的账**：卡面把这一批写成"2 行 `A` + 3 行 `M`"且给那枚 `ManualTimeOverrideWiringGuardTest` 记了 12 ⇒ 13，而盘面上 `git diff --name-status d22a3f8..HEAD -- app/src/test` 只有**四行**（两 `A` 两 `M`）、同一把尺在两棵 commit 上都给 **12** ⇒ 按卡面那笔加总得到 1,784、盘上是 **1,783**；本段按盘面写，差额账 consequently 也不动（`grep -rl "src/main/java"` 那把的 81 ⇒ 83 同理只由那两枚新文件出资，见下面同一格）。
**上一轮**三张卡添的都是"一枚文件装一枚顶层类"的形状 ⇒ 两列各 +2、用例 +9（2 + 6 + 1），**一枚都没进差额**；
T110 添的是"一枚既有文件里多几枚 `@Test`"的形状 ⇒ suite +0、用例再 +3 ⇒ **四张卡累计：两列各 +2、
用例 +12（2 + 6 + 1 + 3），仍是一枚都没进差额**，那 6 枚还是下面那五枚文件出的（第六枚点名的是
减数）**；T115 + T116 之后同一句续成六张卡累计：两列各 +2、用例 +15（2 + 6 + 1 + 3 + 1 + 2），差额仍
+0、那 6 枚的出资人仍没换**（这两枚"仍"本轮都是量出来的，不是推出来的：下面两条循环尺的读数一字未动）。判据是一条命令，零命中就等于"没有多类文件"：

```bash
for f in $(find app/src/test -name "*.kt"); do
  n=$(grep -cE '^(public |internal |private |abstract |open |sealed |data |value )*class ' "$f")
  [ "$n" -gt 1 ] && echo "$n $f"
done                                                # ⇒ 6 枚文件：3 + 2 + 2 + 2 + 2 + 2 = 13 枚顶层类
```

13 枚类塞在 6 枚文件里 ⇒ 多出来 13 − 6 = **7** 枚；这 7 枚里有一枚名下零 `@Test`（下面那枚 `Quad`），
扣掉它才是 **6**。逐枚点名（括号里是该类名下的 `@Test` 枚数，**T108 那一遍**逐枚与当时那批门禁 XML 的
`tests="…"` 对过；本轮 T111 换一条**不依赖 XML** 的静态命令把它逐枚重量了一遍 —— 判据是"一枚类声明到
下一枚类声明之间"的那些行首 `@Test`，六枚文件 `6d6d121` 上的读数与下面六条一字未动）；**本轮 T117 在
`f5192fb` 上同一条命令再重量 ⇒ 六条仍一字未动**（T115/T116 动的那三枚守卫都不在这六枚多类文件里）：

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
`find app/src/test -name "*.kt" | wc -l` ⇒ **194**）。**现状（T129 量于 `073d098`）：同一组两条尺 ⇒ 198 / 204，
与 198 + 6 = 204 对得上；那六枚多类文件与它们名下的 13/9 · 6/8 · 11/2 · 5/3/3 · 11/4 · 7/0 逐枚复算一字未动**
（多类文件那把循环尺仍给 **6 枚文件 / 13 枚顶层类**，`Quad` 名下仍 0 枚 ⇒ 减数还是它）。**现状（T135 量于 `d22a3f8`）：同一组两条尺 ⇒ 205 / 211，与 205 + 6 = 211 对得上；那六枚多类文件与它们名下的 13/9 · 6/8 · 11/2 · 5/3/3 · 11/4 · 7/0 逐枚复算仍一字未动**（本轮新添的 7 枚文件是一枚文件装一枚顶层类的形状 ⇒ 多类文件那把循环尺仍给 **6 枚文件 / 13 枚顶层类**、`Quad` 名下仍 0 枚、差额仍是 6，七枚新文件一枚都没进差额）。**现状（T136 量于 `1b0fefb`）：同一组两条尺 ⇒ 207 / 213，与 207 + 6 = 213 对得上；那六枚多类文件与它们名下的 13/9 · 6/8 · 11/2 · 5/3/3 · 11/4 · 7/0 逐枚复算仍一字未动**（本轮新添的 2 枚文件又是一枚文件装一枚顶层类的形状 ⇒ 多类文件那把循环尺仍给 **6 枚文件 / 13 枚顶层类**、`Quad` 名下仍 0 枚、差额仍是 6，两枚新文件一枚都没进差额）。**现状（T138 量于 `d7ce427`）：同一组两条尺 ⇒ 208 / 214，与 208 + 6 = 214 对得上；那六枚多类文件与它们名下的 13/9 · 6/8 · 11/2 · 5/3/3 · 11/4 · 7/0 逐枚复算仍一字未动**（本轮新添的那一枚文件是一枚文件装一枚顶层类的形状 ⇒ 多类文件那把循环尺仍给 **6 枚文件 / 13 枚顶层类**、`Quad` 名下仍 0 枚、差额仍是 6，那一枚新文件没进差额；复算同上面那两条 `for` 循环尺）。两列的口径必须同一把尺子，
所以仪器测试那一行同样是**文件数**（14，**T129 复算仍 14 枚文件 / 66 枚静态用例，一字未动**；**T135 同两条尺复算 ⇒ 仍 14 枚文件 / 66 枚静态用例，一字未动** —— 复算 `git diff --name-only 073d098..HEAD -- app/src/androidTest` 给**零行**，T131 / T133 / T133b / T134 四张卡一枚 androidTest 改动都没有 ⇒ 这一行本轮又是"没人碰出来的对"）。

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
在这棵树差 1（201 对 200），抄错的人分不出自己抄的是哪一个。**现状（T129 量于 `073d098`）：这一族长成 205 对 204，差仍是 1**（上面 testsuite 那一格那两条 `class` 尺各复算一次 ⇒ 吃顶格裸 `class` 的那把给 **204**、带 modifier 那把给 **205**；多出来的那一枚还是 `Quad`，本轮逐枚尺读到它名下仍 0 枚 `@Test`）。**现状（T135 量于 `d22a3f8`）：这一族长成 212 对 211，差仍是 1**（同一组两条尺 ⇒ 吃顶格裸 `class` 的那把给 **211**、带 modifier 那把给 **212**；多出来的那一枚还是 `Quad`，同一条只读文件的尺读到它名下仍 0 枚 `@Test`，它所在那一行也还在本档点名它的那枚 `:186` 上 —— 这枚文件在 `073d098..HEAD` 区间零改动，复算 `git diff --name-only 073d098..HEAD -- app/src/test/java/com/buaa/schedule/core/designsystem/GlassJankDecisionTest.kt` ⇒ 空）。**现状（T136 量于 `1b0fefb`）：这一族长成 214 对 213，差仍是 1**（同一组两条尺 ⇒ 吃顶格裸 `class` 的那把给 **213**、带 modifier 那把给 **214**；多出来的那一枚还是 `Quad`，同一条只读文件的尺读到它名下仍 0 枚 `@Test`，它所在那一行也还在本档点名它的那枚 `:186` 上 —— 这枚文件在 `d22a3f8..HEAD` 区间同样零改动，复算 `git diff --name-only d22a3f8..HEAD -- app/src/test/java/com/buaa/schedule/core/designsystem/GlassJankDecisionTest.kt` ⇒ 空；本轮多出来的两枚顶层类就是 T128 那两枚新文件各一枚）。**现状（T138 量于 `d7ce427`）：这一族长成 215 对 214，差仍是 1**（同一组两条尺 ⇒ 吃顶格裸 `class` 的那把给 **214**、带 modifier 那把给 **215**；多出来的那一枚还是 `Quad`，同一条只读文件的尺读到它名下仍 0 枚 `@Test`，它所在那一行也还在本档点名它的那枚裸 `:186` 上 —— 复算 `grep -n 'private data class Quad' app/src/test/java/com/buaa/schedule/core/designsystem/GlassJankDecisionTest.kt` ⇒ 186；那枚文件在 `1b0fefb..HEAD` 区间同样零改动，复算 `git diff --name-only 1b0fefb..HEAD -- app/src/test/java/com/buaa/schedule/core/designsystem/GlassJankDecisionTest.kt` ⇒ 空；本轮多出来的那一枚顶层类就是 T137 那枚新守卫文件）。

- 文件数（本机可用，秒级）与"每枚文件都有用例"这条前提：

  ```bash
  find app/src/test -name "*.kt" | wc -l                # 单测「文件数」⇒ 208（量于 d7ce427；上一档 207 量于 1b0fefb、再上一档 205 量于 d22a3f8、再上 198 量于 073d098）
  find app/src/androidTest -name "*.kt" | wc -l         # 仪器测试「文件数」⇒ 14（T129 / T135 / T136 / T138 四次复算一字未动）
  for f in $(find app/src/test -name "*.kt"); do [ "$(grep -cE '^[[:space:]]*@Test' "$f")" = "0" ] && echo "$f"; done
  ```

  第三条按"@Test 为空即列出"数：零命中 ⇒ 194 枚文件**每一枚**都至少含一枚 `@Test`，所以"文件数"与
  "测试类所在文件数"在这一族是同一个数（仪器测试那 14 枚同一条判据，也是零命中）。**现状（T129 量于
  `073d098`）：同一条判据在 198 枚文件上仍零命中、那 14 枚上也仍零命中 ⇒ "文件数"与"测试类所在文件数"
  今天还是同一个数**；**现状（T135 量于 `d22a3f8`）：同一条判据在 205 枚文件上仍零命中、那 14 枚上也仍零命中 ⇒ 这一族"文件数 == 测试类所在文件数"本轮又一次是量出来的、不是抄来的**；**现状（T136 量于 `1b0fefb`）：同一条判据在 207 枚文件上仍零命中**（本轮那两枚新文件名下各挂 6 枚，没有一枚空文件 ⇒ "文件数 == 测试类所在文件数"这一族今天还是量出来的）；**现状（T138 量于 `d7ce427`）：同一条判据在 208 枚文件上仍零命中**（本轮那一枚新文件名下挂 4 枚，没有一枚空文件 ⇒ "文件数 == 测试类所在文件数"这一族今天还是量出来的）。这一条本轮从
  `grep -c '@Test'` 换成带行首锚的那把，只为与下面「用例数」那一格同一把尺：旧写法会把 KDoc 里当词写的
  `@Test` 当成"这个文件有用例"，于是**一枚全靠注释提到 `@Test` 的空文件能骗过它**。⚠️ 单位上这里
  不用换：`-c` 数命中行、`-o | wc -l` 数出现次数，两把只在**要报枚数**时不可混（下面那一格要报 1,770，
  所以它吃 `-o`）；这一条只判"是不是 0"，命中行数为 0 与出现次数为 0 是同一件事，换锚就够了。

- 用例数（离线可读；**这一格的尺子本轮换过，理由写在下面**）：

  ```bash
  grep -rhoE '^[[:space:]]*@Test' app/src/test --include=*.kt | wc -l         # 单测用例数 ⇒ 1799（量于 b76cba9；上一档 1792 量于 d7ce427、再上一档 1783 量于 1b0fefb、再上 1770 量于 d22a3f8、更早 1725 量于 073d098）
  grep -rhoE '^[[:space:]]*@Test' app/src/androidTest --include=*.kt | wc -l  # 仪器测试用例数 ⇒ 66（T129 / T135 / T136 / T138 / T140 五次复算一字未动）
  ```

  **这一格本轮跟着 T110 走过一次**：上一版写 1692，那是 `bb7924a`（T106 收单树）上的读数，按这一族的
  规矩**留着不抹**（它就是那一遍的读数，而且下面「现值 1,692 / 200」那一段还指着它）；本页基点
  `6d6d121`（T110 已合进 master）之上，上面那条命令给 **1695**。增量不是新文件：T110 在同一枚
  `ui/CalendarSyncTargetPairingGuardTest.kt` 里净添 3 枚 `@Test`、**零枚文件**（复算
  `grep -cE '^[[:space:]]*@Test' app/src/test/java/com/buaa/schedule/ui/CalendarSyncTargetPairingGuardTest.kt`
  ⇒ 10（`6d6d121` 那一档，本轮 T117 已到 11），上一版是 7）⇒ 「文件数」那一列与 194↔200 那枚差额都不动，只有用例 +3。
  ⚠️ 这一格同时第一次拿到**增量层面的双尺交叉验证**：T110 的收单账记着同一棵树上门禁 XML 也是
  **1,695 tests / 200 suites**（`docs/STATUS.md:3076`，同节 `docs/STATUS.md:3093` 那句"行首锚 `@Test`
  全仓 1,695 == 门禁 XML 1,695"）⇒ 新尺与 XML 第二次在**同一棵 commit** 上同值（第一次是 T108 那趟的
  1692 / 200）；本卡按红线没跑 gradle，那组读数是编排者记的，出处已点名。

  **本轮（T117）这一格跟着 T115 + T116 又走一格**：上面两枚都是**带基点的历史档位**（`bb7924a` ⇒ 1692、
  `6d6d121` ⇒ 1695，按这一族的规矩一字不抹），本页**新基点 `f5192fb`**（T116 已合进 master）之上，同一条
  带行首锚的命令给 **1698** ⇒ 增量 **+3 = T115 的 1 + T116 的 2**。形状与 T110 同一款、与 T104/T105 不同款：
  **三枚都是既有文件里添 `@Test`，零枚新文件**（复算 `git diff --name-status 6d6d121..f5192fb -- app/src/test`
  ⇒ 恰好三行 `M`、零行 `A`）⇒ 「文件数」那一列（194）与 194↔200 那枚差额都不动，只有用例数动。添在哪三枚
  文件里、各自几枚，是三条只读文件的尺（判据同一把：`grep -cE '^[[:space:]]*@Test' <那枚文件>`）：
  T115② 往 `ui/signin/IClassSignInWiringGuardTest.kt` 添 1 枚（⇒ 7，基点 `6d6d121` 上是 6；它后面那枚
  T115②′ 只往同一枚用例里补逐字判据、**没添新 `@Test`**，所以这一枚数是 7 不是 8）；T116① 往
  `ui/CalendarSyncDiffClearPairingGuardTest.kt` 与 `ui/CalendarSyncTargetPairingGuardTest.kt` **各**添 1 枚
  （⇒ 3、`6d6d121` 上是 2；⇒ 11、`6d6d121` 上是 10 —— 上一格那枚 10 就是本页旧版点名的那枚）。三枚逐文件
  的增量 1 + 1 + 1 = 3，与全仓那把尺的 +3 对得上，而 T115 那一档与 T116 那一档之间有中间读数可查（见下段）。
  ⚠️ 双尺交叉这一半本轮也补齐了，而且连成一条三档：**1695 ⇒ 1696 ⇒ 1698**——T115 的收单账把地板写成
  「**新地板 = 1,696 tests · 200 suites · 0 失败 · 0 skipped**」（STATUS 的 T115 节，裸 `:3238` 那一行起头），
  T116 的收单账记着冷门禁在 `7f3bf72` 上两跑 **1,698 / 200 / 0 失败 / 0 错误 / 0 skipped**（裸 `:3296`，
  那一行起头是「`:app:testDebugUnitTest --rerun-tasks` 两跑」），同节裸 `:3300` 记着"行首锚 `@Test` 全仓复算
  1,698 == 门禁 XML"⇒ 新尺与 XML **第三次**在同一棵 commit 上同值（第一次 T108 的 1692 / 200、第二次 T110
  的 1695 / 200）。这两枚指针按本页的锚点纪律写成**裸 `:NNN` + 那一行起头原文**，不写成 `STATUS.md:NNN`
  那种连写（那会把下面「锚点」那一节的 `.md:NNN` 普查格顶旧一格，而本卡不动那一格）。本卡按红线没跑
  gradle，那三组门禁读数是编排者记的，出处已点名；本页只有静态尺是本轮自己量的。

  **本轮（T129）这一格跟着 T121 + T122 + T127 又走一格**：上面那些都是**带基点的历史档位**（`bb7924a` ⇒ 1692、
  `6d6d121` ⇒ 1695、`f5192fb` ⇒ 1698，按这一族的规矩一字不抹），本页**新基点 `073d098`**（T127③ 已合进 master）之上，
  同一条带行首锚的命令给 **1,725** ⇒ 增量 **+27 = T121 的 15（9 + 6）+ T122 的 6 + T127 的 6**。⚠️ **口径要写死**：
  这一枚 1,725 吃的是 `app/src/test`；把口径换成 `app/src`（含 androidTest 那 66 枚）是**另一枚数**、与本行不同尺，
  别拿它跟门禁 XML 那格比大小。同一把尺的两形写法在本仓等价（`grep -rhoE '^[[:space:]]*@Test'` ⇒ 1,725、
  卡面给的 `grep -rh '^    @Test'` ⇒ 1,725 —— 本仓 `@Test` 一律四空格缩进，见上面「新尺也不常青」那一格）。
  形状与 T104/T105 同款、与 T110/T115/T116 不同款：**四枚全是新文件、一枚文件装一枚顶层类**（复算
  `git diff --name-status f5192fb..073d098 -- app/src/test` ⇒ 四行 `A` + 两行 `M`，`M` 那两行是 T118 改体内报错行号、
  零枚 `@Test` 增删）⇒ 「文件数」那一列 194 ⇒ **198**、suite 那一格 200 ⇒ **204**、差额仍 **6**。
  ⚠️ 双尺交叉这一半本轮也补齐：编排侧在 `073d098` 上跑完冷门禁，**门禁态读数是 1,725 tests · 204 suites ·
  0 失败 · 0 错误 · 0 skipped**，lint **0 error / 14 warning**（九档 per-id：BatteryLife 1、ConfigurationScreenWidthHeight 3、
  FrequentlyChangingValue 2、GradleDependency 3、InlinedApi 1、ObsoleteSdkInt 1、OldTargetApi 1、UseKtx 1、
  WebViewApiAvailability 1），干净全量签名包 **7,249,962 B** ⇒ 行首锚那把静态尺 **1,725 == 门禁 XML 1,725**，
  这是**第四次**在同一棵 commit 上同值（前三次 T108 的 1692 / 200、T110 的 1695 / 200、T116 的 1,698 / 200）。
  ⚠️ **这三组门禁读数（含 lint 九档与包体字节数）都是编排者本轮给的读数、不是本卡量的**：本卡按红线零 gradle、
  零 adb、零产物，`app/build/` 在这棵 worktree 里根本不存在（复算 `ls app/build/` ⇒ No such file or directory），
  所以门禁那几枚**没有本页的复算命令可给**，只有出处；本页能自证的只有上面那几条静态尺。

  **本轮（T135）这一格跟着 T131 / T133 / T133b / T134 又走一格**：上面那些都是**带基点的历史档位**（`bb7924a` ⇒ 1692、
  `6d6d121` ⇒ 1695、`f5192fb` ⇒ 1698、`073d098` ⇒ 1,725，按这一族的规矩一字不抹），本页**新基点 `d22a3f8`** 之上，同一条
  带行首锚的命令给 **1,770** ⇒ 增量 **+45**。形状与 T104 / T105 / T121 / T122 / T127 同款、与 T110 / T115 / T116 不同款：
  **七枚全是新文件、一枚文件装一枚顶层类**（复算 `git diff --name-status 073d098..HEAD -- app/src/test` ⇒ 七行 `A` + 零行 `M`）
  ⇒「文件数」那一列 198 ⇒ **205**、suite 那一格 204 ⇒ **211**、差额仍 **6**（七枚新文件各出一枚 suite、一枚都没进差额）。
  逐枚尺同一把（`grep -cE '^[[:space:]]*@Test' <那枚文件>`），七枚读数 **6 / 7 / 3 / 6 / 5 / 6 / 12**（和 45）都是本轮在
  `d22a3f8` 上量的，顺序按上面那条 `git diff --name-only` 的七行给：`ImportPlannerManualTimeTest`、
  `CourseManagementImportMessageWiringGuardTest`、`ConflictShiftWeekScopeTest`、`ConflictShiftWeekScopeWiringGuardTest`、
  `ConflictWizardWeekScopeReachabilityTest`、`ManualTimeOverridePolicyTest`、`ManualTimeOverrideWiringGuardTest`。
  ⚠️ **口径要写死**：这一枚 1,770 吃的是 `app/src/test`；把口径换成 `app/src`（含 androidTest 那 66 枚）是**另一枚数**、
  与本行不同尺，别拿它跟门禁 XML 那格比大小。同一把尺的两形写法在本仓仍等价（`grep -rhoE '^[[:space:]]*@Test'` ⇒ 1,770、
  卡面给的 `grep -rh '^    @Test'` ⇒ 1,770 —— 本仓 `@Test` 一律四空格缩进这条前提本轮由下面「新尺也不常青」那一格的
  0 命中再验一次）。
  ⚠️ **门禁那一格本卡不许跑、也就不订正**：在册的最新一枚门禁读数仍是 **1,725 tests · 204 suites · 0 失败 · 0 错误 ·
  0 skipped**，量于 `073d098`、由编排侧记（出处就是上面那一段），本页**没有**更新的门禁读数可写 ⇒ 于是静态尺今天给
  1,770、门禁在册值落后**一整批**（T131 / T133 / T133b / T134 那 45 枚用例从没进过任何一趟门禁）。这一格只有下一趟
  门禁才订正得了：本卡零 gradle、零产物，`ls app/build/` 在这棵 worktree 上仍给 `No such file or directory`
  （复算同上面那一段）⇒ 这里只登记差额与出处，不编读数。

  **本轮（T136）这一格跟着 T128 又走一格**：上面那些都是**带基点的历史档位**（`bb7924a` ⇒ 1692、`6d6d121` ⇒ 1695、
  `f5192fb` ⇒ 1698、`073d098` ⇒ 1,725、`d22a3f8` ⇒ 1,770，按这一族的规矩一字不抹），本页**新基点 `1b0fefb`**（T128 那两枚
  `34a265a` + `5eadb78` 已合进 master）之上，同一条带行首锚的命令给 **1,783** ⇒ 增量 **+13**。形状是"上两款项叠在一枚卡上"：
  **两枚新文件共 12 枚 + 一枚既有守卫里添 1 枚**（复算 `git diff --name-status d22a3f8..HEAD -- app/src/test` ⇒ **两行 `A` + 两行 `M`**；
  四条只读文件的尺各给 6 / 6 / 7 / 12 ⇒ 那两枚 `M` 里只有 `UndoUpdateEntryGuardTest` 6 ⇒ 7 出了用例，`ManualTimeOverrideWiringGuardTest`
  12 ⇒ 12 贡献 **0** 枚，逐枚账与这笔驳回同在上面"哪几张卡动过这一族"那一格）⇒「文件数」那一列 205 ⇒ **207**、suite 那一格 211 ⇒ **213**、
  差额仍 **6**（两枚新文件各出一枚 suite、一枚没进差额）。⚠️ **口径要写死**：这一枚 1,783 吃的是 `app/src/test`；把口径换成 `app/src`
  （含 androidTest 那 66 枚）是**另一枚数**、与本行不同尺。同一把尺的两形写法在本仓仍等价（`grep -rhoE '^[[:space:]]*@Test'` ⇒ 1,783、
  卡面给的 `grep -rh '^    @Test'` ⇒ 1,783 —— 本仓 `@Test` 一律四空格缩进这条前提本轮由下面「新尺也不常青」那一格的 0 命中再验一次）。
  ⚠️ **门禁那一格本轮第一次不是"落后"而是"在册"**：编排侧在合并对象 `5eadb78` 上重跑六步冷门禁，**`:app:testDebugUnitTest --rerun-tasks`
  两跑都是 1,783 tests · 213 suites · 0 失败 · 0 错误 · 0 skipped**，lint **0 error / 14 warning**（九枚 id 逐档同：BatteryLife 1、
  ConfigurationScreenWidthHeight 3、FrequentlyChangingValue 2、GradleDependency 3、InlinedApi 1、ObsoleteSdkInt 1、OldTargetApi 1、
  UseKtx 1、WebViewApiAvailability 1），`:benchmark:compileNonMinifiedReleaseKotlin` 10 枚任务 10 executed，干净全量签名包
  **7,251,984 B** ⇒ 行首锚那把静态尺 **1,783 == 门禁 XML 1,783**，**这是同一棵 commit 上同值第五次成立**（前四次 T108 的 1692 / 200、
  T110 的 1695 / 200、T116 的 1,698 / 200、T129 的 1,725 / 204）⇒ 上面那格"静态尺今天给 1,770、门禁在册值落后一整批"从此是
  `d22a3f8` 那一档的**改前状态**、按本页规矩留着不抹。出处：`docs/STATUS.md` 末尾那一节，裸 `:3790` 起头是「## 09-27 深夜续：T128 收单
  （组外观那一支交出它自己的结论 = T122 埋的那枚扳机）」，那一节自己写着"读数是我这轮量的，不是抄回执"。⚠️ **这四组门禁读数都是
  编排者本轮给的、不是本卡量的**：本卡按红线零 gradle、零 adb、零产物，`app/build/` 在这棵 worktree 里根本不存在
  （复算 `ls app/build/` ⇒ No such file or directory），所以门禁那几枚**没有本页的复算命令可给**，只有出处；本页能自证的仍只有静态尺。

  **现状（T138 量于 `d7ce427`）这一格跟着 T137 又走一格**：上面那些都是**带基点的历史档位**（`bb7924a` ⇒ 1692、`6d6d121` ⇒ 1695、`f5192fb` ⇒ 1698、`073d098` ⇒ 1,725、`d22a3f8` ⇒ 1,770、`1b0fefb` ⇒ 1,783，按这一族的规矩一字不抹），本页**新基点 `d7ce427`**（T137 那三枚 `9ed876d` + `7e71aa3` + `714447b` 已合进 master）之上，同一条带行首锚的命令给 **1,792** ⇒ 增量 **+9**。形状是"一枚新文件 + 两枚既有守卫添用例"那两款叠在一枚卡上：**一枚新文件装 4 枚（`data/repository/GroupRowUndoPolicyTest`，判据表驱动）+ 两枚既有守卫里添 3 与 2 枚**（`UndoUpdateEntryGuardTest` 7 ⇒ 10 = ⑦ 容量档 + ⑧ 按 id 复原与次序档 + 判据落点与纯度档；`UndoManagerTest` 2 ⇒ 4 = 条目往返逐位对账两枚；复算 `git diff --name-status 1b0fefb..HEAD -- app/src/test` ⇒ **恰好一行 `A` + 两行 `M`**）⇒「文件数」那一列 207 ⇒ **208**、suite 那一格 213 ⇒ **214**、差额仍 **6**（那一枚新文件出一枚 suite、没进差额）。⚠️ **口径要写死**：这一枚 1,792 吃的是 `app/src/test`；把口径换成 `app/src`（含 androidTest 那 66 枚）是**另一枚数**、与本行不同尺。同一把尺的两形写法在本仓仍等价（`grep -rhoE '^[[:space:]]*@Test'` ⇒ 1,792、`grep -rh '^    @Test'` ⇒ 1,792 —— 两形各量一次 ⇒ 1,792 / 1,792），而"四空格缩进 / 行首之后不挂东西"这条前提本轮由下面「新尺也不常青」那一格的 0 命中再验一次。
  ⚠️ **门禁那一格本轮在册**：编排侧在合并对象 `714447b` 上重跑六步冷门禁（前一趟 `clean` 与 `assembleRelease` 各被那枚 Windows 文件锁咬死一次、是**环境死不是红**，补跑才拿到产物层读数），**`:app:testDebugUnitTest --rerun-tasks` 给 1,792 tests · 214 suites · 0 失败 · 0 错误 · 0 skipped**，lint **0 error / 14 warning**，`:benchmark` 10/10 executed，干净全量签名包 **7,252,469 B**（相对上一档 `5eadb78` 那棵的 7,251,984 ⇒ **+485 B**，代理侧与编排侧两侧同数 ⇒ 按那一族的口径**记新地板**）⇒ 行首锚那把静态尺 **1,792 == 门禁 XML 1,792**，**这是同一棵 commit 上同值第六次成立**（前五次 T108 的 1692 / 200、T110 的 1695 / 200、T116 的 1,698 / 200、T129 的 1,725 / 204、T136 的 1,783 / 213）。出处：台账 `docs/STATUS.md` 末尾那一节「09-28 凌晨续二：T137 收单」—— 裸 `:3882` 那一行起头是「`:app:testDebugUnitTest --rerun-tasks` **1,792 tests / 214 suites / 0 失败 / 0 错误 / 0 skipped**（XML 本轮新时间戳 01:40:06）→」、裸 `:3883` 起头是「lint **0 error / 14 warning** → `:benchmark` 上一趟已 10/10 executed。**签名包 7,252,469 B。**」（两枚本轮 `awk 'NR==N'` 逐枚回读，起头一字未动；台账是 (A) 类、本卡一字未改）。⚠️ **这几组门禁读数与那枚字节数都不是本卡量的**：本卡按红线零 gradle、零 adb、零产物，`app/build/` 在这棵 worktree 里根本不存在（复算 `ls app/build/` ⇒ `No such file or directory`，本卡原样跑过），所以门禁那几枚**没有本页的复算命令可给**，只有出处；而"docs-only 卡不必跑 gradle"这条口径本轮同样有盘上凭据（复算 `grep -rn 'docs[/\\]' app/src/test --include=*.kt | grep -E 'Paths\.get|File\(|"docs/'` ⇒ **0 命中**，本卡原样跑过 ⇒ 全仓没有任何测试读 `docs/` 下的文件，写了"跑了门禁"就是假实测）。

  **现状（T140 量于 `b76cba9`）这一格跟着 T139 又走一格**：上面那些都是**带基点的历史档位**（`bb7924a` ⇒ 1692、`6d6d121` ⇒ 1695、`f5192fb` ⇒ 1698、`073d098` ⇒ 1,725、`d22a3f8` ⇒ 1,770、`1b0fefb` ⇒ 1,783、`d7ce427` ⇒ 1,792，按这一族的规矩一字不抹），本页**新基点 `b76cba9`**（T139 那两枚 `a287b9f` + `b76cba9` 已 ff-only 合进 master；顶端 `186afa0` 只动台账、零枚测试改动）之上，同一条带行首锚的命令给 **1,799** ⇒ 增量 **+7**。形状是"一枚新文件 + 一枚既有守卫添用例"那两款叠在同一枚卡上：**一枚新文件装 5 枚（`data/repository/MainRowUndoPolicyTest`，判据表驱动）+ 一枚既有守卫里添 2 枚**（`UndoUpdateEntryGuardTest` 名下 10 ⇒ **12** = 第 ⑨ 层那两枚，一枚钉主行两档判据、一枚钉调用点那两枚参照物；该文件 1,260 ⇒ **1,511** 行；复算 `git diff --name-status 412acb8..b76cba9 -- app/src/test` ⇒ **恰好一行 `A` + 一行 `M`**、逐枚 `grep -cE '^[[:space:]]*@Test' <那枚文件>` 各给 5 与 12 ⇒ 1,792 + 5 + 2 = **1,799**）⇒「文件数」那一列 208 ⇒ **209**、suite 那一格 214 ⇒ **215**、差额仍 **6**（那一枚新文件出一枚 suite、没进差额）。⚠️ **口径要写死**：这一枚 1,799 吃的是 `app/src/test`；把口径换成 `app/src`（含 androidTest 那 66 枚）是**另一枚数**、与本行不同尺。同一把尺的两形写法在本仓仍等价（两形各量一次 ⇒ 1,799 / 1,799），而"四空格缩进 / 行首之后不挂东西"这条前提本轮由下面「新尺也不常青」那一格的 0 命中再验一次。
  ⚠️ **门禁那一格本轮在册**：编排侧在合并对象 `b76cba9` 上重跑六步冷门禁（第 0 步自证那一趟给的是树 vs HEAD 为 0、静态行首锚尺 **1,799**、守卫名下 **12**、`ScheduleRepository` **727** 行 ⇒ 验的就是这棵），**`:app:testDebugUnitTest --rerun-tasks` 给 1,799 tests · 215 suites · 0 失败 · 0 错误 · 0 skipped**（XML 215 枚，两趟新时间戳 04:24:44 与 04:28:43）、lint **0 error / 14 warning**、`:benchmark` 编译 rc=0、干净全量签名包 **7,252,625 B**（两次独立全量同数）⇒ 行首锚那把静态尺 **1,799 == 门禁 XML 1,799**，**这是同一棵 commit 上同值第七次成立**（前六次 T108 的 1692 / 200、T110 的 1695 / 200、T116 的 1,698 / 200、T129 的 1,725 / 204、T136 的 1,783 / 213、T138 的 1,792 / 214）。出处：台账 `docs/STATUS.md` 末尾那一节「09-28 凌晨续四：T139 收单」—— 裸 `:3941` 那一行起头是「## 09-28 凌晨续四：T139 收单（撤销主行那一趟补上占用校验；朝窄的正解是摘掉析取的其中一枚参照物）」、裸 `:3957` 起头是「`testDebugUnitTest --rerun-tasks` 第一趟 **XML 215 / tests=1799 / red=0 / skipped=0**（新时间戳 04:24:44）→ lint **0 error / 14 warning** →」、裸 `:3959` 起头是「⇒ **包体按"两次独立全量一致"记新地板**：上一档 7,252,469 ⇒ **+156 B**」（三枚本轮 `awk 'NR==N'` 逐枚回读，起头一字未动；台账是 (A) 类、本卡一字未改）。⚠️ **这几组门禁读数与那枚字节数都不是本卡量的**：本卡按红线零 gradle、零 adb、零产物，`app/build/` 在这棵 worktree 里根本不存在（复算 `ls app/build/` ⇒ `No such file or directory`，本卡原样跑过），所以门禁那几枚**没有本页的复算命令可给**，只有出处；而"docs-only 卡不必跑 gradle"这条口径本轮同样有盘上凭据（复算卡面那三条命令串起来的那把尺 —— 在 test 树里搜读 `docs/` 路径的语句 ⇒ **0 命中**，本卡原样跑过 ⇒ 全仓没有任何测试读 `docs/` 下的文件，写了"跑了门禁"就是假实测）。

  **为什么换尺**：旧版那两条是 `grep -rho "@Test" …`，它数的是**这串字符在 test 树里出现的次数**，
  不是用例数。在 `8605707`（T102 写这页时那棵树）上两把尺同值、都与该点门禁 XML 的 `<testcase>`
  合计 1683 相等 ⇒ 那时它是把好尺；**T108 那轮在 `bb7924a` 上同一把旧尺给 1696，而门禁是 1692** ⇒
  **旧尺多算 4 枚**（那一对读数按规矩留着，它是那一遍的读数）。多出来的不是丢信用例，是**把 `@Test`
  当词写的句子**：

  ```bash
  grep -rho '@Test' app/src/test --include=*.kt | wc -l                        # 旧尺 ⇒ 1804（比新尺多 5；上一档 1797 量于 d7ce427 多 5、再上一档 1788 量于 1b0fefb 多 5、再上 1775 量于 d22a3f8 多 5、再上 1730 多 5、更早 1702 多 4）
  grep -rn '@Test' app/src/test --include=*.kt | grep -vE ':[0-9]+:[[:space:]]*@Test'
  # 同一把差集尺读旧盘面：单枚文件走 grep -n 时行号前面没有"路径:"，排除式得改成行首锚
  git show bb7924a:app/src/test/java/com/buaa/schedule/ui/CalendarSyncTargetPairingGuardTest.kt \
    | grep -nE '@Test' | grep -vE '^[0-9]+:[[:space:]]*@Test'   # ⇒ 4 行：35 / 58 / 251 / 324（旧账那四枚）
  ```

  **现状（本轮 T111 在 `6d6d121` 上重量）**：旧尺 **1699**、新尺 **1695** ⇒ 虚报的枚数仍是 **4**，
  两把尺的差没变，变的是四处落点的位置。**现状（本轮 T117 在 `f5192fb` 上重量）**：旧尺 **1702**、
  新尺 **1698** ⇒ 差**还是 4**，四枚落点**还是同一枚文件**，变的又只有位置。**现状（T129 在 `073d098` 上重量）**：
  旧尺 **1,730**、新尺 **1,725** ⇒ 这一族的差**第一次不再是 4 而是 5**、落点**第一次不再是同一枚文件**（逐枚账接在下面那段末尾）。**现状（T135 在 `d22a3f8` 上重量）**：旧尺 **1,775**、新尺 **1,770** ⇒ 差**仍是 5**、落点**仍是 5 枚 / 两枚文件**，五枚的行号（裸 `:60` / `:83` / `:486` / `:572` / 那枚新文件里的裸 `:50`）本轮逐枚 `awk 'NR==N'` 回读**一字未动** —— T131 / T133 / T133b / T134 这四张卡往 test 树里只添新文件、这两枚既有文件一行都没动（复算 `git diff --name-status 073d098..HEAD -- app/src/test` ⇒ 七行 `A` + 零行 `M`）⇒ 于是这一族的差停在 5、没跟着 45 枚新用例一起长；它下一次长，是有人往新守卫的 KDoc 或报错消息里再写一句"第 N 枚 `@Test`"的时候。**现状（T136 在 `1b0fefb` 上重量）**：旧尺 **1,788**、新尺 **1,783** ⇒ 差**仍是 5**、落点**仍是 5 枚 / 两枚文件**，可**第五枚的行号漂了**：上面几版记的那枚裸 `:50`（`UndoUpdateEntryGuardTest` 类头 KDoc「本文件落到 master 时，第五枚 ``@Test`` 的方法名是」）今天在同一条差集尺的输出里落在裸 **`:71`** —— 那枚文件被 `5eadb78` 从 738 行推到 **955** 行（复算：`git show d22a3f8:` 前缀同一条 `wc -l` 给 738、本机给 955），而 `CalendarSyncTargetPairingGuardTest` 那四枚（裸 `:60` / `:83` / `:486` / `:572`）本轮逐枚 `awk 'NR==N'` 回读**一字未动**，那枚文件也确实在这段区间零改动（复算 `git diff --name-only d22a3f8..HEAD -- app/src/test/java/com/buaa/schedule/ui/CalendarSyncTargetPairingGuardTest.kt` ⇒ 空）⇒ 上面那几组行号按本页规矩留着不抹，它们各自钉的是当时那棵 commit。⚠️ 上面那句"它下一次**长**，是有人往 KDoc 里再写一句"第 N 枚"的时候"本轮**没有兑现**（差仍是 5、一枚没长），兑现的是另一条：既有那句 KDoc 自己数得对，可它**住的行号**会被同一枚卡的插行推着走 ⇒ 这一族的账从此有两本，一本数枚数、一本盯行号。
  **现状（T138 在 `d7ce427` 上重量）**：旧尺 **1,797**、新尺 **1,792** ⇒ 差**仍是 5**、落点**仍是 5 枚 / 两枚文件**，而且**那五枚的行号本轮一字未动**：`CalendarSyncTargetPairingGuardTest` 的裸 `:60` / `:83` / `:486` / `:572` 与 `UndoUpdateEntryGuardTest` 的裸 `:71` 逐枚 `awk 'NR==N'` 回读，起头原文与上面那几格点名的句子逐字相同（复算 `grep -rn '@Test' app/src/test --include=*.kt | grep -vE ':[0-9]+:[[:space:]]*@Test'` ⇒ **5 行 / 两枚文件**，本轮原样跑）。⚠️ 这一枚 `:71` 值得点名：那枚文件本轮被 T137 从 **955 行推到 1,260 行**（复算 `wc -l app/src/test/java/com/buaa/schedule/ui/UndoUpdateEntryGuardTest.kt` ⇒ 1,260、`git show 1b0fefb:` 前缀同一条尺 ⇒ 955），三枚新用例全插在它**之下** ⇒ 那句自数用例枚数的 KDoc 这次没被推着走；可它数的"第五枚"今天已不是本文件的第五枚（名下 7 ⇒ **10**，复算 `grep -cE '^[[:space:]]*@Test' app/src/test/java/com/buaa/schedule/ui/UndoUpdateEntryGuardTest.kt` ⇒ 10）⇒ 上面那句"它下一次长，是有人往 KDoc 里再写一句'第 N 枚 `@Test`'的时候"这一轮兑现的是**第三种形状：枚数变了、句子没跟着变**（盯行号那本账本轮没漂，数枚数那本里**自己数自己的那句**今天谎着）。
  **现状（T140 在 `b76cba9` 上重量）**：旧尺 **1,804**、新尺 **1,799** ⇒ 差**仍是 5**、落点**仍是 5 枚 / 两枚文件**，而且**那五枚的行号本轮一字未动**：`CalendarSyncTargetPairingGuardTest` 的裸 `:60` / `:83` / `:486` / `:572` 与 `UndoUpdateEntryGuardTest` 的裸 `:71` 逐枚 `awk 'NR==N'` 回读 ⇒ 五句原文与上一档一字相同。原因也量得出：T139 往那枚守卫添的是第 ⑨ 层两枚用例，复算 `git diff -U0 412acb8..b76cba9 -- app/src/test/java/com/buaa/schedule/ui/UndoUpdateEntryGuardTest.kt` 那三扇 hunk 最早起在旧 `:98` 之后 ⇒ `:98` 以上一行没漂，`:71` 那句 KDoc 自然也没漂（本轮新添的 1,799 里没有任何一枚落在这副差集里 ⇒ 两把尺的差仍是 5 而不是 6 或 7）。
  第二条直接点名那 4 处（旧尺对新尺的差集），全部落在 `ui/CalendarSyncTargetPairingGuardTest.kt`
  **一枚**文件里 —— **行号已经整体漂了**：T108 那一版记的是 `:35` / `:58` / `:251` / `:324`（`bb7924a`
  盘面，本轮用上面第三条原样复现 ⇒ 那句当时是对的），T111 那一版是 `:60` / `:83` / `:480` / `:566`，
  **本轮（T117，`f5192fb`）复算 ⇒ `:60` / `:83` / `:486` / `:572`**（第二条换成读工作树 ⇒ 仍是 **4 枚**、
  仍是同一枚文件；**前两枚一字未动、后两枚各 +6** ⇒ 漂的原因是 T106 与 T110 往这枚文件的头部与中段添过
  行，本轮这一步则是 T116① 只往 `:83` 之后、`:480` 之前那一段添了行 —— 头部两枚因此纹丝不动）。
  **现状（T129，`073d098`）⇒ 差集变成 5 枚 / 两枚文件**：上面那四枚的**行号一字未动**（裸 `:60` / `:83` / `:486` /
  `:572` —— T118 只改那枚文件体内报错消息的行号算术、没往头部或中段添行），第五枚是**新文件**
  `UndoUpdateEntryGuardTest` 里裸 `:50` 那句 KDoc（原文「本文件落到 master 时，第五枚 ``@Test`` 的方法名是」），
  形状与上面 `:83` 那一枚同款 —— **一句 KDoc 在自己数自己文件的用例枚数**。⇒ 上面那句"全部落在……**一枚**文件里"
  从此是 `f5192fb` 的历史读数、按本页规矩留着不抹；复算就是第二条原样跑（`grep -rn '@Test' app/src/test --include=*.kt | grep -vE ':[0-9]+:[[:space:]]*@Test'` ⇒ **5 行 / 两枚文件**）。⚠️ 这一格因此要给本页那条结论
  补一句：**"旧尺虚报的枚数恰好骗过眼球"这件事不再只在同一枚守卫文件里发生** —— 只要还有人往新守卫的 KDoc 里
  写"第 N 枚 `@Test`"，这把旧尺就会继续长差集，而且**每添一枚守卫文件就可能添一处**。
  逐枚 `awk 'NR==行号'` 回读过形状，三版的分工一模一样：**三处 KDoc + 一处字符串字面量**：
  `:60`「修法与可达路径枚举见本文件第三枚 ``@Test``」、`:83`「十一枚 ``@Test`` 全是**纯 JVM 源码核对**…」、
  `:572`「…对成对清同样成立（…见上面那枚 ``@Test``）」三处是 KDoc（**`:83` 那句 T111 那一版引的是
  「十枚」，本轮同一条 awk 读出「十一枚」** —— 它在本文件里的行号没漂，漂的是它数的那个枚数）；
  **`:486` 那一处不是注释** ——
  它长在 `assertEquals`（起在 `:482`，T111 那一版是 `:476`）的报错字符串里（「…本文件第三枚 ``@Test``（可达路径枚举）与移除链
  那枚也会跟着红」；`bb7924a` 上同一处那句还没有「与移除链那枚」这五个字，旧句子留着对旧行号 `:251`）。
  **上一版卡面**把四处都说成"注释/KDoc"，那一处当时要驳回、现在仍要驳回：
  字符串字面量同样被旧尺数成一枚用例，而它恰恰是**守卫最天然会写出的那种句子**。⇒ 这一族
  污染不会自己消失，只要还有人在报错消息里指认别的用例，旧尺就会继续虚报，而虚报的枚数正好
  骗过"看起来像用例数"的那种眼球。
  ⚠️ 本轮还多撞出一条同类账：`bb7924a` 上那句 KDoc 写的是「**七枚** `@Test`」，T111 那一版同一位置（`:83`）
  写的是「**十枚**」—— 那行句子自己在数本文件的用例枚数，T106/T110 加了 3 枚就把它推着走了一遍。
  **本轮（T117）同一条 awk 再读 `:83` ⇒ 它已写成「十一枚」**（T116① 又往这枚文件添了 1 枚）⇒ 这条账追到
  第三代：那一行句子跟着数、本页那把尺跟着量，两处都得动 —— 而它不在本页任何一条复算命令的输出里，
  只有点名它的人才看得见。
  ⇒ **被旧尺数成用例的那些句子内部还挂着别的会变的数**，一处污染两代账；这也是本页为什么坚持"数旁边
  必须放命令"而不是"数旁边放一句解释"。

  ⚠️ **新尺也不常青，它只是把污染面从"行内任意位置"收窄到"行首"**。它自己的判据（今天零命中）：

  ```bash
  grep -rnE '^[[:space:]]*@Test' app/src/test --include=*.kt \
    | grep -vE ':[0-9]+:[[:space:]]*@Test[[:space:]]*$'                        # ⇒ 0
  ```

  这条列的是"被新尺数到、但行首锚之后还挂着别的东西"的行 ⇒ 0 命中意味着今天那 1,725 枚**枚枚都是
  光秃秃的注解行**（本仓写法是四空格缩进的裸 `@Test`，全树没有一行写成 `@Test fun foo()`）。
  **现状（T129 在 `073d098` 上重跑这一把）⇒ 判据仍 0 命中**，也就是说那 1,725 枚（含本轮新添的四枚文件里那 27 枚）
  枚枚还是光秃秃的注解行；本页上一版这里写的是 1698，那是 `f5192fb` 的现值、按规矩留着不抹。
  **现状（T135 在 `d22a3f8` 上重跑这一把）⇒ 判据仍 0 命中**，也就是说那 1,770 枚（含本轮新添的七枚文件里那 45 枚）
  枚枚还是光秃秃的注解行：四空格缩进的裸 `@Test` 这一写法连着两批没被破，所以卡面那把 `grep -rh '^    @Test'` 与本页
  那把 `grep -rhoE '^[[:space:]]*@Test'` 今天还给同一个 1,770 —— 而这两把**都**依赖"缩进恒为四空格 / 行首之后不挂东西"
  这一前提，前提一破两把一起骗人（残余风险仍旧写在下一段）。
  **现状（T136 在 `1b0fefb` 上重跑这一把）⇒ 判据仍 0 命中**，也就是说那 1,783 枚（含本轮新添的两枚文件里那 12 枚与那枚既有守卫里添的
  1 枚）枚枚还是光秃秃的注解行：四空格缩进的裸 `@Test` 这一写法连着**三批**没被破，卡面那把 `grep -rh '^    @Test'` 与本页那把
  `grep -rhoE '^[[:space:]]*@Test'` 今天还给同一个 **1,783**（两形各量一次 ⇒ 1,783 / 1,783；T138 同两条尺 ⇒ 1,792 / 1,792）；上面那组 1,770 是 `d22a3f8` 的读数、
  按本页规矩留着不抹。
  **现状（T138 量于 `d7ce427`）：同一条尺重跑这一把）⇒ 判据仍 0 命中**，也就是说那 1,792 枚（含本轮那一枚新文件里的 4 枚与两枚既有守卫里添的 5 枚）枚枚还是光秃秃的注解行：四空格缩进的裸 `@Test` 这一写法连着**四批**没被破，上面那两形写法今天还给同一个 **1,792**；上面那组 1,783 是 `1b0fefb` 的读数、按本页规矩留着不抹。
  **现状（T140 量于 `b76cba9`）：同一条尺重跑这一把 ⇒ 判据仍 0 命中**，也就是说那 1,799 枚（含本轮那一枚新文件里的 5 枚与那枚既有守卫里添的 2 枚）枚枚还是光秃秃的注解行：四空格缩进的裸 `@Test` 这一写法连着**五批**没被破，卡面那把 `grep -rh '^    @Test'` 与本页那把 `grep -rhoE '^[[:space:]]*@Test'` 今天还给同一个 **1,799**（两形各量一次 ⇒ 1,799 / 1,799）；上面那组 1,792 是 `d7ce427` 的读数、按本页规矩留着不抹。
  残余风险就一种形状：**某行的第一个非空 token 是 `@Test` 而它不是注解** —— 把 KDoc 的 ` * ` 沟槽
  丢了直接写 `@Test` 起头、或在一对三引号里嵌一段测试代码样本，都会中招。那种行新尺照数，且**页面
  上这三条命令没有一条能发现它**（它们与它是同一把尺）⇒ 这一格的最终仲裁仍是下面那副门禁 XML；
  新尺只是今天与它同值，不是永远与它同值。

- testsuite 数与"为什么是 6 枚差额"（离线可数，本机两条路都给）：

  ```bash
  grep -rh "^class " app/src/test --include=*.kt | wc -l                          # 静态 ⇒ 215（量于 b76cba9；上一档 214 量于 d7ce427、再上一档 213 量于 1b0fefb、再上一档 211 量于 d22a3f8、再上 204 量于 073d098）
  grep -rhE "^(public |internal |private |abstract |open |sealed |data |value )*class " \
       app/src/test --include=*.kt | wc -l                                        # 顶层 class 声明总数 ⇒ 216（量于 b76cba9；上一档 215 量于 d7ce427、再上一档 214 量于 1b0fefb、再上一档 212 量于 d22a3f8、再上 205 量于 073d098）
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
  上 —— STATUS 是**往后追加**的台账，前面的行不漂。**本轮（T111）在 `6d6d121` 上又逐条 awk 回读一遍，
  三枚仍落在原来那三段的起头上**（`docs/STATUS.md:2793` 起头「地板抬到 1,681 / 197 …」、`:2811` 与
  `:2830` 各起头「收单证据（…）」），其间 STATUS 从 2904 行长到 **3100** 行（`wc -l docs/STATUS.md`）
  ⇒ 那句"前面的行不漂"这一轮也被验了一遍。

  **现值 1,692 / 200 这一组在 STATUS 里还没有在册的那一步**：它是编排者 09-26 在 `ai/T106`（收单树
  `bb7924a`）上跑的门禁，T103…T106 四张卡都还没写进 STATUS（`bb7924a` 上 `wc -l docs/STATUS.md` 给
  2904，最后一节是「清盘与 `ai/T93` 预置」，最新一枚在册的卡是 T102）⇒ 这一格只能按**带出处的读数**落地，
  不许写成常青事实。本轮在那趟门禁留在 `ai/T106` 那棵 worktree 的 XML 上**只读**复核过一遍（没跑 gradle）：
  `TEST-*.xml` 200 份、`<testcase>` 合计 **1692**、`<testsuite>` 元素 **200**、failures / errors /
  skipped 三格全 **0** ⇒ 与上面那条新尺逐格相同。⚠️ 这句复验自己也过期得很快：那批 XML 会被下一趟
  `--rerun-tasks` 整个换掉，且**只在那棵 worktree 里存在** —— 本卡这棵连 `app/build/` 都没有，在这里跑
  上面那条 python 只会读到空目录，别把"读不到"读成"门禁没跑"。
  ⚠️ **而这一格的第一句已经翻案**（现状，`6d6d121`）：编排者 09-26 深夜先给 STATUS 补了 T103/T104/T105/
  T106/T108 五节（`742b3d4`）、又补了 T110 的收单账（`6d6d121`）⇒ "还没在册的那一步"不成立了，
  T110 那节白纸黑字记着在 `890306f` 上两跑都是 **1,695 tests / 200 suites / 0 failures / 0 errors /
  0 skipped**（`docs/STATUS.md:3076`，那一行起头是「`:app:testDebugUnitTest --rerun-tasks` 两跑都是」），
  同节 `docs/STATUS.md:3093` 记着"行首锚 `@Test` 全仓 1,695 == 门禁 XML 1,695"。⇒ 本页上表那格从此
  **有在册的门禁读数可对**，但**它仍然不是常青事实**：这两枚指针指的是 T110 那一趟，下一张加测试的卡
  一落地它们又落后一格 —— 所以数旁边那条 grep 才是本体，STATUS 的指针只是"这一格当时被谁证过"。
  ⚠️ **那句预言本轮（T117）就兑现了**：T115 与 T116 一落地，上面那两枚 1,695 指针当场落后两格
  （1,696 与 1,698，两档都在册，见「用例数」那一格的末段）⇒ 本页现值走的是复算命令，不是这两枚指针。

- Guard 那一族与"读源码"那一族（上表最后一格里那两个数）：

  ```bash
  find app/src/test -name "*Guard*.kt" | wc -l                   # ⇒ 45（量于 b76cba9；这一把连着四档一字未动，上一档量于 d7ce427、再上一档量于 1b0fefb、再上 42 量于 073d098、再上 39）
  grep -rl "src/main/java" app/src/test --include=*.kt | wc -l   # ⇒ 85（量于 b76cba9；上一档 84 量于 d7ce427、再上一档 83 量于 1b0fefb、再上一档 81 量于 d22a3f8、再上 76 量于 073d098、再上 73）
  comm -23 <(find app/src/test -name "*Guard*.kt" | sort) \
           <(grep -rl "src/main/java" app/src/test --include=*.kt | sort) | wc -l   # ⇒ 0（连着七档零命中，最新一档量于 b76cba9）
  comm -23 <(grep -rl "src/main/java" app/src/test --include=*.kt | sort) \
           <(grep -rl "File(" app/src/test --include=*.kt | sort) | wc -l           # ⇒ 5（量于 b76cba9；上一档 4 量于 d7ce427、再上一档 3 量于 1b0fefb、再上一档 2 量于 d22a3f8 是那一族第一次翻非零；更早三档都是 0）
  ```

  第三条撑着"39 枚 Guard **全部**吃读源码那把尺子"那句（零命中 = 没有一枚 Guard 落在这 73 之外）；
  第四条撑着"这 73 枚每一枚都真的在开文件"那句 ⇒ 今天它是真读数。**本轮（T111，`6d6d121`）四条重跑，
  读数 39 / 73 / 0 / 0 一字未动** —— T110 添的是既有文件里的 3 枚 `@Test`，既没添 Guard 文件也没添
  "读源码"文件，所以这一族本来就不该动；它没动是**量出来的**，不是推出来的。**本轮（T117，`f5192fb`）
  四条再重跑 ⇒ 39 / 73 / 0 / 0 仍一字未动** —— T115/T116 动的那三枚文件名字枚枚带 `Guard`（都在 39 里、
  也都在 73 里），添的仍是既有文件里的 `@Test` ⇒ 这一族同样一枚不该动，它没动也是量出来的。
  73 的构成是 39 枚 Guard + 34 枚
  别的族 —— ⚠️ **这一格里只有 Guard 那一路在动**：T104 与 T105 各添一枚 Guard，73 相对上一版只涨 2，
  "非 Guard 的那 34 枚"在两版盘面上是同一批。口径两处要挑明：前两条形如 `grep -rl`，数的是**文件枚数**
  （`-l` 一个文件最多给一条），所以"某文件里提了三回 `src/main/java`"不会被数成三枚；而第二条的判据
  只是**那串字面量在文件里出现过**，哪天有人在注释里提一句 `src/main/java` 而没开文件，第二条就开始
  虚报、第四条当场红 ⇒ 那时改判据而不是改数。上面「用例数」那一格就是这句话的现成先例。
  **现状（T129 量于 `073d098`）：四条重跑 ⇒ 42 / 76 / 0 / 0** —— 上面那两组 39 / 73 分别量于 `6d6d121` 与 `f5192fb`、
  按本页规矩留着不抹。涨的 3 枚 Guard 是 T121③ `d4c6c51` 的 `CourseDeletionWiringGuardTest`、T122① `7965270` 的
  `UndoUpdateEntryGuardTest`、T127② `44bdc58` 的 `CourseGroupDeletionWiringGuardTest`；第四枚新文件
  `CourseDeletionPolicyTest` 是**表驱动判据单测、不吃读源码那把尺**（复算
  `grep -c 'src/main/java' app/src/test/java/com/buaa/schedule/data/repository/CourseDeletionPolicyTest.kt` ⇒ **0**）
  ⇒ 构成从此是 **42 枚 Guard + 34 枚别的族 = 76**，"非 Guard 的那 34 枚"在两版盘面上仍是同一批（复算
  `comm -13 <(find app/src/test -name '*Guard*.kt' | sort) <(grep -rl 'src/main/java' app/src/test --include=*.kt | sort) | wc -l` ⇒ **34**）。
  ⚠️ 口径要挑明（这一族在本仓吃过两次数法不一致的账）：卡面那把宽尺 `grep -rl 'src/main/java\|readMainSource\|findMainJavaDir'`
  与本页这条 `grep -rl "src/main/java"` 今天给的是**同一个 76**（两把各量一次 ⇒ 76 / 76），所以下一个人换尺不必重数；
  第三、第四条那两条形如 `comm` 的判据仍**零命中** ⇒ "42 枚 Guard 全部在 76 里"、"76 枚每一枚都真的带 `File(`"
  两句今天都是真读数。
  **现状（T135 量于 `d22a3f8`）：四条重跑 ⇒ 45 / 81 / 0 / 2** —— 上面那三组（39 / 73、42 / 76）都按本页规矩留着不抹。
  涨的 3 枚 Guard 按文件名序是 `CourseManagementImportMessageWiringGuardTest`、`ConflictShiftWeekScopeWiringGuardTest`、
  `ManualTimeOverrideWiringGuardTest`（复算 `comm -13 <(git diff --name-only 073d098..HEAD -- app/src/test | sort) \
  <(find app/src/test -name '*Guard*.kt' | sort) | wc -l` ⇒ 42，加上这三枚 ⇒ 45）；另 4 枚新文件是表驱动与判据单测，
  其中 `ConflictShiftWeekScopeTest` 与 `ConflictWizardWeekScopeReachabilityTest` **不吃读源码那把尺**（复算
  `grep -c 'src/main/java' app/src/test/java/com/buaa/schedule/ui/home/ConflictShiftWeekScopeTest.kt` ⇒ **0**、
  同一条换成 `ConflictWizardWeekScopeReachabilityTest` ⇒ **0**），另两枚吃 ⇒ 构成从此是
  **45 枚 Guard + 36 枚别的族 = 81**（复算 `comm -13 <(find app/src/test -name '*Guard*.kt' | sort) <(grep -rl 'src/main/java' app/src/test --include=*.kt | sort) | wc -l` ⇒ **36**；"非 Guard 的那一批"自 T129 的 34 起第一次长人，涨的两枚就是刚点名那两枚）。
  ⚠️ **第三条仍 0、第四条本轮第一次翻成 2** —— 这正是本页上面那格预告过的那件事："哪天有人在注释里提一句
  `src/main/java` 而没开文件，第二条就开始虚报、第四条当场红 ⇒ 那时改判据而不是改数"。红的是**第四条**，两枚出资人
  是 `ui/home/ManualTimeOverridePolicyTest.kt`（4 处命中全在 `assertEquals` 的报错字符串里，形如「`\n复算：sed -n '61,69p' …`」，
  它自己零次开文件）与 `domain/schedule/ImportPlannerManualTimeTest.kt`（3 处命中同款，两处 KDoc + 一处报错字符串）：
  两枚都是**表驱动判据单测**，把"人去复算 main 源码"的那条命令写进报错消息，却从不自己读源码 ⇒ 于是本页第二条
  （判据只是"那串字面量在文件里出现过"）**虚报 2 枚**，"这 81 枚每一枚都真的带 `File(`" 那句从此是 `073d098` 的历史读数、
  今天不成立（复算 `comm -23 <(grep -rl "src/main/java" app/src/test --include=*.kt | sort) <(grep -rl "File(" app/src/test --include=*.kt | sort)` ⇒ 逐行点名那两枚）。
  第三条不受影响 ⇒ "45 枚 Guard 全部在 81 里"仍成立（同一条 `comm` 零命中，逐行读回是空的）。
  ⚠️ **本卡不动判据**：按本页自己那条"改判据而不是改数"，第四条要么换成"只认 `File(` 且真读源码的那把"、要么把第二条
  收窄成"读过文件的那批"，那是**口径决策**、会改这一族现值的含义，本卡红线只收"数与行号"⇒ 判据留给下一张动这一族的卡，
  本页这格今天照实记 81 / 0 / 2 并把两枚出资人点名在这儿。
  ⚠️ 口径要挑明（这一族的"尺子不常青"本轮撞上新的一例）：**卡面给的那把宽尺 `grep -rl 'readMainSource\|readAllMainSources\|File('`
  与本页这条 `grep -rl "src/main/java"` 今天不再给同一个数** —— 前者 80、后者 81，上一档两把还给 76 / 76。差集是双向的、
  两把都各有对方没有的名（逐行复算两条 `comm -23` ⇒ 本页比卡面多 `ui/home/ManualTimeOverridePolicyTest.kt` 与
  `domain/schedule/ImportPlannerManualTimeTest.kt` 那两枚（提到 main 路径却不 `File(`、也不 `readMainSource`），
  卡面比本页多 `data/local/MigrationChainTest.kt` 一枚（它用 `File(schemaDir, …)` 开 `schemas` 目录、整枚文件却没写过
  `src/main/java` 那串字面量）⇒ 81 − 2 + 1 = 80，两把各自闭合）。⇒ 上面那句"所以下一个人换尺不必重数"从今天起作废：
  **这一族的现值必须连着"哪把尺"一起写**，单写一枚 81 或 80 都会被下一个人当成同一件事。
  **现状（T136 量于 `1b0fefb`）：四条重跑 ⇒ 45 / 83 / 0 / 3** —— 上面那三组（39 / 73、42 / 76、45 / 81）都按本页规矩留着不抹。
  本轮**只有"读源码"那把在长、Guard 那把一字没动**：`find app/src/test -name "*Guard*.kt" | wc -l` 在这棵树上仍给 **45**（T128 那两枚新文件的
  名字都不带 `Guard` —— 复算 `git diff --name-status d22a3f8..HEAD -- app/src/test` ⇒ 两行 `A` 里零行带 `Guard`，而那两行 `M` 点名的两枚本来
  就在 45 里）⇒ 81 ⇒ 83 这两枚**全部由那两枚新文件出资**（两条只读文件的尺：`grep -c 'src/main/java' <那枚文件>` 各给 **6** 与 **1** 处命中）。
  构成从此是 **45 枚 Guard + 38 枚别的族 = 83**（复算 `comm -13 <(find app/src/test -name '*Guard*.kt' | sort) <(grep -rl 'src/main/java' app/src/test --include=*.kt | sort) | wc -l` ⇒ **38**；
  "非 Guard 的那一批"自 T129 的 34、T135 的 36 起今天长到 38，涨的两枚就是刚点名那两枚）。⚠️ **第三条仍 0、第四条从 2 长成 3** —— 第三枚出资人是
  `ui/UndoUpdateAdmissionTest.kt`：它**只在类头 KDoc 里点了内核那串路径**、整枚文件一次都没开（`grep -c 'File(' <那枚文件>` ⇒ **0**，卡面那把
  `grep -lE 'Paths\.get|readText|bufferedReader'` 把它与同批另一枚并排跑 ⇒ 只回另一枚），而同批新立的 `data/repository/CourseGroupAppearancePolicyTest.kt`
  是**真开文件**的那一枚（同一条尺 ⇒ `File(` **3** 处）⇒ 上面 T135 那一格点名的"提到 main 路径却没开文件"那一族从 2 枚长成 **3 枚**，
  第三条 `comm` 逐行读回依次是 `domain/schedule/ImportPlannerManualTimeTest.kt`、`ui/UndoUpdateAdmissionTest.kt`、`ui/home/ManualTimeOverridePolicyTest.kt`。
  ⚠️ 本页那句"哪天有人在注释里提一句 `src/main/java` 而没开文件，第二条就开始虚报、第四条当场红"今天**第四次兑现**，形状与预告一字不差。
  **本卡照旧不动判据**（那是口径决策，本卡红线只收"数与行号"）⇒ 这格今天照实记 83 / 0 / 3 并把三枚出资人点名在这儿。
  ⚠️ 口径要挑明（两把尺这一轮还给两个数，但**上一档那对数已经翻篇**）：卡面那把宽尺 `grep -rlE 'readMainSource|readAllMainSources|File\('`
  与本页这条 `grep -rl "src/main/java"` 今天给 **81 对 83**（上一档是 80 对 81）；差集仍**双向** —— 本页比卡面多刚点名那三枚（提到却不 `File(`、
  也不 `readMainSource`），卡面比本页多 `data/local/MigrationChainTest.kt` 一枚（它用 `File(schemaDir, …)` 开 `schemas` 目录、整枚文件没写过那串
  字面量）⇒ **83 − 3 + 1 = 81**，两把各自闭合 ⇒ 上面那句"现值必须连着哪把尺一起写"本轮又验一次。
  **现状（T138 量于 `d7ce427`）：四条重跑 ⇒ 45 / 84 / 0 / 4** —— 上面那四组（39 / 73、42 / 76、45 / 81、45 / 83）都按本页规矩留着不抹。本轮**两把里只有"读源码"那把在长、Guard 那把照旧一字没动**：`find app/src/test -name "*Guard*.kt" | wc -l` 在这棵树上仍给 **45**（T137 那枚新文件的名字不带 `Guard` —— 复算 `git diff --name-only 1b0fefb..HEAD -- app/src/test` ⇒ 那一行 `A` 零行带 `Guard`，而那两行 `M` 点名的两枚本来就在 45 里）⇒ 83 ⇒ 84 这一枚**全部由那一枚新文件出资**（两条只读文件的尺：`grep -c 'src/main/java' app/src/test/java/com/buaa/schedule/data/repository/GroupRowUndoPolicyTest.kt` ⇒ **1** 处命中、同一条换成 `grep -c "File(" <那枚文件>` ⇒ **0**）。构成从此是 **45 枚 Guard + 39 枚别的族 = 84**（复算 `comm -13 <(find app/src/test -name '*Guard*.kt' | sort) <(grep -rl 'src/main/java' app/src/test --include=*.kt | sort) | wc -l` ⇒ **39**；"非 Guard 的那一批"自 T129 的 34、T135 的 36、T136 的 38 起今天长到 39，涨的那一枚就是刚点名那枚）。⚠️ **第三条仍 0、第四条从 3 长成 4** —— 第四枚出资人就是本轮新立的 `data/repository/GroupRowUndoPolicyTest`：它**只在类头 KDoc 里点了内核那串路径**、整枚文件一次都没开（`grep -c "File(" <那枚文件>` ⇒ **0**），形状与上一档点名的 `ui/UndoUpdateAdmissionTest` 一模一样 ⇒ "提到 main 路径却没开文件"那一族从 3 枚长成 **4 枚**，第四条 `comm` 逐行读回依次是 `domain/schedule/ImportPlannerManualTimeTest.kt`、`data/repository/GroupRowUndoPolicyTest.kt`、`ui/UndoUpdateAdmissionTest.kt`、`ui/home/ManualTimeOverridePolicyTest.kt`（复算就是上面第四条那条 `comm`，本轮原样跑 ⇒ **4 行**）。⚠️ 本页那句"哪天有人在注释里提一句 `src/main/java` 而没开文件，第二条就开始虚报、第四条当场红"今天**第五次兑现**，形状与预告一字不差。**本卡照旧不动判据**（那是口径决策，本卡红线只收"数与行号"）⇒ 这格今天照实记 84 / 0 / 4 并把四枚出资人点名在这儿。口径要挑明（两把尺这一轮还给两个数、上一档那对数又翻篇）：卡面那把宽尺 `grep -rlE 'readMainSource|readAllMainSources|File\('` 与本页这条 `grep -rl "src/main/java"` 今天给 **81 对 84**（上一档 81 对 83）；差集仍**双向** —— 本页比卡面多刚点名那四枚（提到却不 `File(`、也不 `readMainSource`），卡面比本页多 `data/local/MigrationChainTest.kt` 一枚（它用 `File(schemaDir, …)` 开 `schemas` 目录、整枚文件没写过那串字面量 —— 复算 `grep -c 'src/main/java' app/src/test/java/com/buaa/schedule/data/local/MigrationChainTest.kt` ⇒ **0**）⇒ **84 − 4 + 1 = 81**，两把各自闭合 ⇒ "现值必须连着哪把尺一起写"这条本轮又验第四次。
  **现状（T140 量于 `b76cba9`）：四条重跑 ⇒ 45 / 85 / 0 / 5** —— 上面那五组（39 / 73、42 / 76、45 / 81、45 / 83、45 / 84）都按本页规矩留着不抹。本轮**两把里还是只有"读源码"那把在长、Guard 那把照旧一字没动**：`find app/src/test -name "*Guard*.kt" | wc -l` 在这棵树上仍给 **45**（T139 本轮**零枚新守卫** —— 那枚新文件的名字不带 `Guard`，复算 `git diff --name-only 412acb8..b76cba9 -- app/src/test` ⇒ 那一行 `A` 零行带 `Guard`，而那唯一一行 `M` 点名的那枚本来就在 45 里）⇒ 84 ⇒ 85 这一枚**全部由那一枚新文件出资**（两条只读文件的尺：`grep -c 'src/main/java' app/src/test/java/com/buaa/schedule/data/repository/MainRowUndoPolicyTest.kt` ⇒ **1** 处命中、同一条换成 `grep -c "File(" <那枚文件>` ⇒ **0**）。构成从此是 **45 枚 Guard + 40 枚别的族 = 85**（复算 `comm -13 <(find app/src/test -name '*Guard*.kt' | sort) <(grep -rl 'src/main/java' app/src/test --include=*.kt | sort) | wc -l` ⇒ **40**；"非 Guard 的那一批"自 T129 的 34、T135 的 36、T136 的 38、T138 的 39 起今天长到 40，涨的那一枚就是刚点名那枚）。⚠️ **第三条仍 0、第四条从 4 长成 5** —— 第五枚出资人就是本轮新立的 `data/repository/MainRowUndoPolicyTest`：它**只在类头 KDoc 里点了内核那串路径**、整枚文件一次都没开（`grep -c "File(" <那枚文件>` ⇒ **0**），形状与上一档点名的 `data/repository/GroupRowUndoPolicyTest` 一字不差 ⇒ "提到 main 路径却没开文件"那一族从 4 枚长成 **5 枚**，第四条 `comm` 逐行读回依次是 `domain/schedule/ImportPlannerManualTimeTest.kt`、`data/repository/GroupRowUndoPolicyTest.kt`、`data/repository/MainRowUndoPolicyTest.kt`、`ui/UndoUpdateAdmissionTest.kt`、`ui/home/ManualTimeOverridePolicyTest.kt`（复算就是上面第四条那条 `comm`，本轮原样跑 ⇒ **5 行**）。⚠️ 本页那句"哪天有人在注释里提一句 `src/main/java` 而没开文件，第二条就开始虚报、第四条当场红"今天**第六次兑现**，形状与预告一字不差。**本卡照旧不动判据**（那是口径决策，本卡红线只收"数与行号"）⇒ 这格今天照实记 85 / 0 / 5 并把五枚出资人点名在这儿。口径要挑明（两把尺这一轮还给两个数、而**卡面那把宽尺本轮一字没动**）：卡面那把宽尺 `grep -rlE 'readMainSource|readAllMainSources|File\('` 与本页这条 `grep -rl "src/main/java"` 今天给 **81 对 85**（上一档 81 对 84）；差集仍**双向** —— 本页比卡面多刚点名那五枚（提到却不 `File(`、也不 `readMainSource`），卡面比本页多 `data/local/MigrationChainTest.kt` 一枚（它用 `File(schemaDir, …)` 开 `schemas` 目录、整枚文件没写过那串字面量 —— 复算 `grep -c 'src/main/java' app/src/test/java/com/buaa/schedule/data/local/MigrationChainTest.kt` ⇒ **0**）⇒ **85 − 5 + 1 = 81**，两把各自闭合 ⇒ "现值必须连着哪把尺一起写"这条本轮又验第五次。

- 仪器测试的用例数**只能静态数**（上面那条 grep ⇒ 66），因为它在本机从来没有留下一份 `:app:` 的
  connected 结果。⚠️ 旧版给的理由是"这台机器没有可用的模拟器"，T102 已经把它换掉过一次；本轮再看
  现状，那句理由给得比当时更差：**它举的两处证据都是"某个 worktree 的 `build/` 目录当下长什么样"**
  （"旧版说的 `app/build/outputs/` 里没有 `androidTest-results`"、"全仓唯一的 connected 产物长在
  `benchmark/build/outputs/androidTest-results/` 底下"）。这类"没有"是**逐 worktree 的临时状态**，
  不是事实：本卡这棵 `ai/T108` 上 `app/build/` 与 `benchmark/build/` **两个都不存在**，`ls` 给的是
  `No such file or directory` 而不是"目录里没有那一项" —— 于是那句话在这里连复算都复算不出个意思，
  而在隔壁那棵跑过 benchmark 的 worktree 上它又恰好成立。⇒ 换成一条不依赖构建产物的理由：
  **`docs/STATUS.md` 整本里 `connectedDebugAndroidTest` 只出现 3 次**
  （`grep -c connectedDebugAndroidTest docs/STATUS.md` ⇒ 3；T111 在 `6d6d121` 上复算 ⇒ **仍 3**，
  STATUS 那两趟补账没往里添新读数），三处都在讲 CI 与 `MigrationTest` 的
  设计，**没有一处是一次本地 connected 跑完的读数**；而 CI 确实跑它（上面 `android.yml` 那两行），
  产物却从不落回仓库。⇒ 66 今天仍是静态计数，理由是"本机没有 :app: 的 connected 读数在册"，
  不是"机器上没模拟器"（`ls ~/.android/avd/` 给 `buaa36.avd` 与 `buaa36.ini`，那台 AVD 现在也在），
  也不是"某个 build 目录里少一个子目录"。这一格同时是本页最该警惕的一种句子：**"某文件不存在"
  看着像证据，其实只是当时的盘面**。
  **现状（T129 量于 `073d098`）：这一格三条都还成立，两枚数一字未动** —— ① 静态尺 `grep -rhoE '^[[:space:]]*@Test'
  app/src/androidTest --include=*.kt | wc -l` ⇒ **66**、`find app/src/androidTest -name '*.kt' | wc -l` ⇒ **14**；
  ② 三张新卡零枚 androidTest 改动（复算 `git diff --name-status f5192fb..073d098 -- app/src/androidTest` ⇒ **零行**）；
  ③ `grep -c connectedDebugAndroidTest docs/STATUS.md` ⇒ **仍 3**（STATUS 从 3,100 行长到 **3,602** 行、
  `wc -l docs/STATUS.md` 量于 `073d098`，其间补了 T117–T127 六节却没添一枚本地 connected 读数）；
  ④ 本卡这棵 worktree 里 `ls app/build/` 依旧给 `No such file or directory` ⇒ 66 今天**仍然只能静态数**。
  **现状（T135 量于 `d22a3f8`）：这一格四条都还成立，两枚数仍一字未动** —— ① 同一把尺 `grep -rhoE '^[[:space:]]*@Test'
  app/src/androidTest --include=*.kt | wc -l` ⇒ **66**、`find app/src/androidTest -name '*.kt' | wc -l` ⇒ **14**；
  ② 本批四张卡零枚 androidTest 改动（复算 `git diff --name-status 073d098..HEAD -- app/src/androidTest` ⇒ **零行**）；
  ③ `grep -c connectedDebugAndroidTest docs/STATUS.md` ⇒ **仍 3**（STATUS 从 3,602 行长到 **3,772** 行、
  `wc -l docs/STATUS.md` 量于 `d22a3f8`，其间补了 T131–T134 那几节，一条本地 connected 读数都没添）；
  ④ `ls app/build/` 在这棵 worktree 上仍给 `No such file or directory` ⇒ 66 今天**仍然只能静态数**，
  而本页那些静态尺已经走到 1,770 / 205 —— 仪器测试这两格又一次是"没人碰出来的对"，不是维护出来的。
  **现状（T138 量于 `d7ce427`）：这一格五条都还成立，两枚数仍一字未动** —— ① 同一把尺 `grep -rhoE '^[[:space:]]*@Test' app/src/androidTest --include=*.kt | wc -l` ⇒ **66**、`find app/src/androidTest -name '*.kt' | wc -l` ⇒ **14**，另有"每枚都含 `@Test`"那条判据在 14 枚上仍**零命中**；② T137 那三枚 commit 零枚 androidTest 改动（复算 `git diff --name-only 1b0fefb..HEAD -- app/src/androidTest` ⇒ **零行**）；③ `grep -c connectedDebugAndroidTest docs/STATUS.md` ⇒ **仍 3**（STATUS 从 3,840 行长到 **3,907** 行、`wc -l docs/STATUS.md` 量于 `d7ce427`，其间补了 T136 / T137 那两节收单账，一条本地 connected 读数都没添）；④ `ls app/build/` 在这棵 worktree 上仍给 `No such file or directory` ⇒ 66 今天**仍然只能静态数**；⑤ 本页那些静态尺今天走到 **1,792 / 208** —— 仪器测试这两格连着**第四批**是"没人碰出来的对"，不是维护出来的。
  **现状（T140 量于 `b76cba9`）：这一格五条都还成立，两枚数仍一字未动** —— ① 同一把尺读 androidTest 那棵树 ⇒ `@Test` 行首锚 **66**、`find app/src/androidTest -name '*.kt'` **14**，另有"每枚都含 `@Test`"那条判据在 14 枚上仍**零命中**（本卡逐枚 `grep -cE` 跑过一遍 ⇒ 没有一枚回 0）；② T139 那两枚 commit 零枚 androidTest 改动（复算 `git diff --name-only 412acb8..b76cba9 -- app/src/androidTest` ⇒ **零行**，本卡原样跑过）；③ `grep -c connectedDebugAndroidTest docs/STATUS.md` ⇒ **仍 3**（STATUS 从 3,907 行长到 **3,981** 行、`wc -l docs/STATUS.md` 量于 `b76cba9` 之后那棵 `186afa0`，其间补了 T138b / T139 两节收单账，一条本地 connected 读数都没添）；④ `ls app/build/` 在这棵 worktree 上仍给 `No such file or directory` ⇒ 66 今天**仍然只能静态数**；⑤ 本页那些静态尺今天走到 **1,799 / 209** —— 仪器测试这两格连着**第五批**是"没人碰出来的对"，不是维护出来的。

## 为什么这张表容易说谎

这一页挂着两类数。一类是**会变大的现值**：上表的 1698 / 194 / 66 / 14，复算一节的 200 / 201 / 39 / 73，
锚点一节命令旁边那几枚 —— **现状（T129 量于 `073d098`）：这一族今天长成上表 1,725 / 198 / 66 / 14、复算一节
204 / 205 / 42 / 76**，上面那两组旧数按本页规矩留着不抹；**现状（T135 量于 `d22a3f8`）：这一族今天长成上表
1,770 / 205 / 66 / 14、复算一节 211 / 212 / 45 / 81**，上面那三组旧数（1698 / 194、1725 / 198、204 / 205 / 42 / 76）
同样留着不抹；**现状（T136 量于 `1b0fefb`）：这一族今天长成上表 1,783 / 207 / 66 / 14、复算一节 213 / 214 / 45 / 83**；**现状（T138 量于 `d7ce427`）：这一族今天长成上表 1,792 / 208 / 66 / 14、复算一节 214 / 215 / 45 / 84**（仪器测试那两枚 66 / 14 本轮同两条尺复算 ⇒ 仍一字未动）；**现状（T140 量于 `b76cba9`）：这一族今天长成上表 1,799 / 209 / 66 / 14、复算一节 215 / 216 / 45 / 85**（仪器测试那两枚 66 / 14 本轮同两条尺复算 ⇒ 仍一字未动）（仪器测试那两枚
66 / 14 本轮同两条尺复算 ⇒ 一字未动），上面那几组旧数按本页规矩全部留着；锚点那一节那四枚（589 / 24 / 268 / 4）本轮同样一字未动，
理由与复算写在「行号锚点犯的是同一族错」那一节末格 —— 它们枚枚都会随"加一次测试"或"写一次文档"而动，而加测试的卡通常只记得改
`docs/STATUS.md`。另一类是**要求读数为零（或为定值）的自证判据**：每一枚测试文件都含 `@Test`、
`@Nested`/`@ParameterizedTest`/`@RunWith` 三件套为 0、每枚 Guard 都落在"读源码"那一族里、仪器测试那
14 枚也每枚含 `@Test`、读源码那 73 枚每一枚都带 `File(`。前一类的病是过期，后一类的病是**悄悄变成
非零** —— 而后一类恰恰是前一类可信的理由，所以它过期了更没人回头看。
⚠️ **后面那一类本轮当场兑现了一条**：上面那句"读源码那批每一枚都带 `File(`"（原判据读数 0）在 `d22a3f8` 上给的是
**2** ⇒ 这一族第一次悄悄变成非零，而且红在**本表现值刚被顶新一格**的那同一批卡上（逐枚出资人与形状写在
「Guard 那一族与"读源码"那一族」那一格）。其余四条自证判据本轮复算仍全为零（每枚测试文件都含 `@Test` ⇒ 零命中、
三件套 ⇒ 0、每枚 Guard 都在"读源码"那一族里 ⇒ 0、仪器测试那 14 枚也每枚含 `@Test` ⇒ 零命中、
行首锚之后不挂东西 ⇒ 0）⇒ 本页那条"前一类的可信理由在后一类"的因果链今天两头同时动，正是这一节想让人看见的形状。
  ⚠️ **同一族本轮第二次兑现（T138 量于 `d7ce427`）**：上一条点名的"读源码那批每一枚都带 `File(`"那枚自证判据（原判据读数 0）今天给的是 **4**（上一档 3 量于 `1b0fefb`、再上一档 2 量于 `d22a3f8`，两档都留着不抹），而出资的那枚新文件 `data/repository/GroupRowUndoPolicyTest` 正是 T137 那一枚表驱动判据单测 —— 与前一段同一批卡同时动。**其余四条自证判据本轮复算仍全为零**（每枚测试文件都含 `@Test` ⇒ 在 208 枚上零命中、三件套 ⇒ 0、每枚 Guard 都在"读源码"那一族里 ⇒ 0、仪器测试那 14 枚也每枚含 `@Test` ⇒ 零命中、行首锚之后不挂东西 ⇒ 0）⇒ 前一类（现值）本轮**五把尺全部挪过**（1,792 / 208 / 214 / 215 / 45 / 84），后一类（判据）只挪了第四条那一枚 ⇒ 这一节那条因果链今天仍是两头同时动。
  ⚠️ **同一族本轮第三次兑现（T140 量于 `b76cba9`）**：上一条点名的"读源码那批每一枚都带 `File(`"那枚自证判据（原判据读数 0）今天给的是 **5**（上一档 4 量于 `d7ce427`、再上一档 3 量于 `1b0fefb`、再上一档 2 量于 `d22a3f8`，三档都留着不抹），而出资的那枚新文件 `data/repository/MainRowUndoPolicyTest` 正是 T139 那一枚表驱动判据单测 —— 还是"把人去复算 main 源码的那条命令写进报错消息、自己一次没开文件"那一款，形状与 T138 点名的 `GroupRowUndoPolicyTest` 一字不差。**其余四条自证判据本轮复算仍全为零**（每枚测试文件都含 `@Test` ⇒ 在 209 枚上零命中、三件套 ⇒ 0、每枚 Guard 都在"读源码"那一族里 ⇒ 0、仪器测试那 14 枚也每枚含 `@Test` ⇒ 零命中、行首锚之后不挂东西 ⇒ 0，这五把本卡各原样跑过一遍）⇒ 前一类（现值）本轮**五把尺又全部挪过**（1,799 / 209 / 215 / 216 / 45 / 85），后一类（判据）照旧只挪第四条那一枚 ⇒ 这一节那条因果链今天还是两头同时动。

本页**第四次**失手就是证据，而且又是整页：T98 把这张表从 README 搬过来的时候订正过两处（仪器测试那格
`65`、单元测试那格 `1585 | 186`），T99 与 T100 各加一枚守卫文件之后没人回头改这一页，六格里五格同时
过期、只有仪器测试那一格（66 | 14）因为那两张卡没碰 androidTest 而侥幸还对着 —— 那一版由 T102 收掉。
本轮是**同一族病复发第二次**：T104（改 main + 新守卫 2 枚）、T105（新守卫 6 枚）、T106（重钉守卫 +
新增 1 枚）连着三张卡各加了测试，整页现值又一次全部过期，而且这次连"数法"本身都坏了（上面那格
`@Test` 的尺子）。⚠️ 本轮订正之后**六格里只剩仪器测试那一格没动过**（66 | 14），因为这三张卡没碰
androidTest —— 与 T102 那次是同一个"侥幸"形状：它不是维护出来的，是没人碰出来的。⇒ 光靠"改得更勤"
治不好这件事，所以本页每个数旁边都放了一条命令：**下一个人数得出来，就不必相信本页**。
⚠️ **同一族病已经复发第三次了**（本轮 T111 收，起点是 T110）：那枚卡只改了 main 的一段区与**同一族**
的守卫，就把本页顶过期三处 —— 上表那格与「用例数」那格（1692 ⇒ 1695）、"三张卡贡献形状"那两段论证
（要续成四张）、差集那四枚**行号**（`:35/:58/:251/:324` ⇒ `:60/:83/:480/:566`，枚数没变、位置全漂）。
形状与上两段说的是同一件事：**加测试的卡不会想到回来改这页，改注释式的句子也会推着这页的行号走**。
⇒ 本页累计**第五次失手**（README 时代两格 + T99/T100 那一次整页 + T104–T106 那一次整页 + T110 这一次
局部），复发计数走到**第三次**。这次侥幸对着的是「文件数」与仪器测试那两格（T110 没添文件、没碰
androidTest）—— 与前两次同一个"没人碰出来的对"。
⚠️ **同一族病复发第四次**（本轮 T117 收，起点是 T115 + T116）：这两张卡一共只往**三枚既有守卫文件**里添
3 枚 `@Test`（零枚新文件；`app/src/main` 那侧只动了登录页一枚），就把本页顶过期**四处** ——
「用例数」那一格与上表那格（1695 ⇒ 1698，中间还夹一档 1696）、逐枚守卫那格（那枚文件 10 ⇒ 11；另两枚
6 ⇒ 7 与 2 ⇒ 3 此前根本没在册，本轮给它们各补了一条只读文件的尺）、"哪几张卡动过这一族"那两段论证
（四张 ⇒ 六张、+12 ⇒ +15）、差集那四枚**行号与那句 KDoc 自己数的枚数**（`:480`/`:566` ⇒ `:486`/`:572`，
`:83` 那句从「十枚」长成「十一枚」，枚数仍是 4）。⇒ 本页累计**第六次失手**、复发计数走到**第四次**；
这次侥幸对着的还是「文件数」与仪器测试那两格（194 / 66 / 14 一枚没动，两张卡零新文件、没碰 androidTest）
—— 与前三次同一个"没人碰出来的对"。
⚠️ **同一族病复发第五次**（本轮 T129 收，起点是 T121 + T122 + T127）：这三张卡往 test 树里添了**四枚新文件、
27 枚 `@Test`**（复算 `git diff --name-status f5192fb..073d098 -- app/src/test` ⇒ 四行 `A` + 两行 `M`），
把本页顶过期**六处** —— 上表那格（1,698 / 194 ⇒ 1,725 / 198）、「用例数」那一格（+27）、「Guard 那一族」那格
（39 ⇒ 42、73 ⇒ 76）、差额与 testsuite 那两格（200 ⇒ 204、201 ⇒ 205）、"哪几张卡动过这一族"那两段论证
（六张 ⇒ 九张、+15 ⇒ +42 枚累计用例）、旧尺差集那一族（**第一次从 4 枚长成 5 枚、第一次从一枚文件长成两枚文件**：
新那枚是 `UndoUpdateEntryGuardTest` 的裸 `:50`，句子里又挂着一枚会数的数 ⇒ 「一处污染两代账」有了第二份实例）。
⇒ 本页累计**第七次失手**、复发计数走到**第五次**。这次侥幸对着的只有仪器测试那两格（66 / 14 —— 三张卡零枚
androidTest 改动）与锚点普查那四格（589 / 24 / 268 / 4 —— 本卡新写的行号一律裸 `:NNN` + 原文片段同框），
"没人碰出来的对"这一族形状与前四次一模一样 ⇒ **第五次仍然验证了本页那句结论：光靠改得更勤治不好，只有把尺子
放到数旁边才治得住**（本轮每一格都照这一条各跑了一次，读数与出处逐格写在数旁边）。

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
  **同行改写**，所以这一段没被推着走）。**T110 也动过它，本轮（T111）同一条 awk 再读一遍：101/107/108
  三枚一字未动** —— T110 的插行全落在 `:1531` 之后（那枚文件从 1,683 行变 **1,693** 行，
  `wc -l app/src/main/java/com/buaa/schedule/ui/ScheduleViewModel.kt`），页头这一段跟着一起不动。
  同一条规矩也核过本页那三枚 STATUS 指针：`:2793` / `:2811` / `:2830` 与它引的"新账起在 2814"
  本轮逐条 awk 回读，四枚全落在原来的起头上。
- **本页旧版那条指针漂了 3 行**：旧版把 #151 那笔新账记在 `docs/STATUS.md:2811`，而新账实际起在 2814
  （别与上面那条搞混 —— 引用「收单证据」那段的 2811 至今是对的，漂的是"新账"这一条）。
  制造这次漂移的恰好是本页基点那枚
  commit：`c55ddc5` 在 `docs/STATUS.md:2243` 处 +4 / −1 ⇒ 其后整体后移 3 行
  （`git show --stat c55ddc5 -- docs/STATUS.md`）。它漂得无声无息，是因为下面第一条普查命令的尺子
  **只认 `\.kt:[0-9]+`** —— 而 `X.md:NNN` 这一族全仓有 18 枚（本页 8 枚，其余 10 枚长在
  `docs/STATUS.md` 的 9 枚与 `docs/derived-field-audit.md` 的 1 枚里），普查命令一条都量不到；本轮之前
  本页那唯一一枚就是它。⚠️ 上一句那三个数是**本轮（T108）在 `bb7924a` 上重量的现值**，别与下一句
  "本轮开始时它是 9…现在 16"搞混 —— 那句是 **T102 那一轮的实验账**（(A) 类，保留原貌，它当时量的
  就是 16）。**现状（本轮 T111 在 `6d6d121` 之后重量）：这一族全仓 24 枚 = 本页 13 + STATUS 10 + 审计档 1**
  —— 本页从 8 涨到 13 全是本卡引的 STATUS 收单账指针，见下面那四条命令的第二条与它末段那句反面实例；
  STATUS 从 9 涨到 10 是编排者补 T110 那节写的。这一格本身就是"每格带命令"的活样本：它一轮之内被两批订正各添过指针 ——
  数得出来才不会把它抄旧。这正好解释了规矩 ① 为什么要求"行号与符号名/原文片段同框"：行号错了，还能凭那句起头找回去。

规矩全文写在 [`docs/derived-field-audit.md`](derived-field-audit.md) 头部那节「锚点保鲜声明」
（行号必须与符号名/原文片段同框、文档头部标注锚点对应的 commit、改主源码插删行顺手核引用、
**(A) 历史叙述保留原貌 / (B) 现状描述逐条回读**）。复算命令与测试计数同一副尺子，但**要跑四条**，
一把尺子盖不住两族指针：

```bash
grep -rhoE '[A-Za-z0-9_]+\.kt:[0-9]+' docs README.md | wc -l       # 全仓 .kt 锚点枚数 ⇒ 589
grep -rhoE '[A-Za-z0-9_.-]+\.md:[0-9]+' docs README.md | wc -l     # 全仓 .md:NNN 指针枚数 ⇒ 24（上一条看不见这一族）
grep -rhoE '[A-Za-z0-9_]+\.kt:[0-9]+' docs/derived-field-audit.md | wc -l   # 单档 ⇒ 268
grep -coE '[A-Za-z0-9_]+\.kt:[0-9]+' docs/TESTING.md               # 本页贡献几枚 ⇒ 4
```

**这四枚数是本轮（T111）在最后一次编辑之后重量的**（基点 `6d6d121` = T110 已合进 master），与审计档
§6.10 那三格（本档 268 / 全仓 589 / 换成命中行那把 146）**各自独立量到同一个数** ⇒ 两页的尺子今天对得上；
⚠️ 但这两页**互相**是对方最大的一处污染源：往任一页里多点名一处代码，另一页的全仓那一格就跟着动。
**本轮的账就长这样**：`.kt` 那三格（589 / 268 / 146）本卡**一枚没动** —— 因为本卡新写的行号一律用裸
`:NNN` + 符号名/原文片段同框（差集那四枚、守卫枚数、`:1481`/`:1449`/`:1538` 这些站点），没往任一页补
`文件.kt:行号` 那种连写；**动的是上面第二条**：`X.md:NNN` 那一族从 18 涨到 **24**，全部 6 枚是本卡添的
（本页 8 ⇒ **13**：新引 `docs/STATUS` 的 T110 收单账指针 5 枚 + 上一版本页就有的 8 枚；STATUS 9 ⇒ **10**，
是编排者补 T110 那节时添的；审计档仍 **1**）。⇒ 这一格再次说明：两族指针得**各数一遍**，
本页那一族用同一条 `-o` 尺复算：`grep -ohE '[A-Za-z0-9_.-]+\.md:[0-9]+' docs/TESTING.md | wc -l` ⇒ **13**。
**本轮（T117）在那四条命令原样重跑，是在最后一次编辑之后量的 ⇒ 589 / 24 / 268 / 4 四枚一字未动**，
本页那一族再复算也仍是 **13 枚 / 12 行**：本卡新写的行号一律**裸 `:NNN` + 那一行起头原文同框**（三枚新引
STATUS 收单账的指针写成裸 `:3238` / `:3296` / `:3300`，差集那四枚写成裸 `:60` / `:83` / `:486` / `:572`，
`assertEquals` 那一枚写成裸 `:482`），没往任一页补 `文件.kt:行号` 或 `文件.md:行号` 那种连写 ⇒ 两族普查尺
本来就不该动，它没动同样是**量出来的**（这一格是本页自己那条规矩 ① 的现成用例：**守规矩的改动不动尺子**，
不需要为它改数）。
**本轮（T129）在那四条命令原样重跑，同样是在最后一次编辑之后量的 ⇒ 589 / 24 / 268 / 4 四枚一字未动**，
本页那一族也仍是 **13 枚 / 12 行**（复算 `grep -ohE '[A-Za-z0-9_.-]+\.md:[0-9]+' docs/TESTING.md | wc -l` ⇒ 13、
`grep -cE '[A-Za-z0-9_.-]+\.md:[0-9]+' docs/TESTING.md` ⇒ 12）：本卡新写的行号一律**裸 `:NNN` + 同框原文片段**
（旧尺差集那五枚写成裸 `:60` / `:83` / `:486` / `:572` / `:50`，守卫体内那几处只给符号名与逐字句子），
没往任一页补 `文件.kt:行号` 或 `文件.md:行号` 那种连写 ⇒ 两族普查尺没理由挪，它没动同样是**量出来的**。
⚠️ 顺手自证一句 STATUS 那两枚指针：本卡红线不许动 `docs/STATUS.md`，而本页钉着的那两枚裸 `:3076` / `:3093`
本轮逐条 `awk 'NR==N'` 回读，**两句起头一字未动**（`:3076` 起头「`:app:testDebugUnitTest --rerun-tasks` 两跑都是」、
`:3093` 起头「这正是 §6.10 那格想要的形状」）⇒ STATUS 从 3,100 行长到 3,602 行靠的是**往后追加**，前面的行不漂，
这一句是那一格结论的第三份实例。
**本轮（T135）在那四条命令原样重跑，同样是在最后一次编辑之后量的 ⇒ 589 / 24 / 268 / 4 四枚一字未动**，
本页那一族也仍是 **13 枚 / 12 行**（复算 `grep -ohE '[A-Za-z0-9_.-]+\.md:[0-9]+' docs/TESTING.md | wc -l` ⇒ 13、
`grep -cE '[A-Za-z0-9_.-]+\.md:[0-9]+' docs/TESTING.md` ⇒ 12），本页 `.kt` 那一格仍是 **4 行**（`grep -coE '[A-Za-z0-9_]+\.kt:[0-9]+' docs/TESTING.md` ⇒ 4）：
本卡新写的行号一律**裸 `:NNN` + 同框符号名/原文片段**（新写的那些全长成裸 `:60` / `:83` / `:486` / `:572` / `:50` /
`:186` / `:3076` / `:3093` / `:206` 这种形状，文件名与行号之间不留连写），没往任一页补 `文件.kt:行号` 或 `文件.md:行号`
那种连写 ⇒ 两族普查尺没理由挪，它没动同样是**量出来的**（审计档 §6.10 那三格本卡也只同格改字、没增行，两页那三枚数
仍各自独立量到同一个值）。
**本轮（T136）在那四条命令原样重跑，同样是在最后一次编辑之后量的 ⇒ 589 / 24 / 268 / 4 四枚一字未动**，本页那一族也仍是
**13 枚 / 12 行**（两条尺同上面那格各重跑一次，单位别混这条规矩本轮照守），本页 `.kt` 那一格仍是 **4 行**：本卡这一轮是把五把尺的现值
**逐格重述**，一格新行号都没往页里写 —— 新落的那些全长成裸 `:3790` 这种形状、与那一行的节名或原文片段同框，文件名一律不与其行号连写
（新引的那一枚指的是台账末尾「09-27 深夜续：T128 收单」那一节，写的是节名加裸行号，没写成 `STATUS.md:数字` 那种连写）。
⚠️ 三枚跨页指针本卡一条没动、也只回读不重钉：本页钉着的那两处与台账里那句"指向这颗文件的 56 枚文档锚点"，本轮逐条 `awk 'NR==N'`
回读 ⇒ 三句起头一字未动（台账从 3,772 行长到 **3,840** 行、`wc -l` 本轮量的，靠的还是**往后追加**，前面的行不漂）⇒ 两族普查尺没理由挪，
它没动同样是**量出来的**。⚠️ 本卡给本页新加的行**全部落在第 30 行之后**（复算 `git diff -U0 -- docs/TESTING.md | grep -oE '^@@ -[0-9]+' | sed 's/^@@ -//' | sort -n | head -1`
⇒ 最早那枚 hunk 起在 **32**）⇒ 上面那一格钉着本页头部的两枚历史指针不因本卡再漂一格。
⚠️ **本轮顺手 grep 出一条以前没人点名的反向钉**：本页自己**也被钉着行号** —— `grep -rnoE 'TESTING\.md:[0-9]+' docs/ README.md`
⇒ 两枚，都在台账里：STATUS 的 T98 一节（裸 `:2780` 那行）写着"改后 `docs/TESTING.md`:27-30 与改前 README 那四行完全相同"、
T99 一节（裸 `:2865` 那行）写着"`docs/TESTING.md`:24 的「覆盖……」"。两句都是 (A) 类历史叙述、本卡红线不许回改，
而它们指的正是本页**头部那 30 行以内**的位置 ⇒ 所以本卡给自己加了一条新纪律并照着执行：**往本页加行只加在第 30 行之后**，
第 1 到 30 行一个字节没动（复算 `git diff -U0 -- docs/TESTING.md | grep -oE '^@@ -[0-9]+' | sed 's/^@@ -//' | sort -n | head -1`
⇒ 最早那枚 hunk 起在 **32**、也就是覆盖表那一行，头部 30 行不在任何 hunk 里 ⇒ 那两枚历史指针不因本卡再漂一格；
注意别拿 `diff <(git show …:docs/TESTING.md | head -30) <(head -30 docs/TESTING.md)` 去验，本仓工作树是 CRLF、
`git show` 给 LF，那条命令在 Windows 上恒给"30 行全不同"的假红）。⇒ 这一格是给下一个人的提醒：
**本页不是只有它钉别人，它也被两枚历史指针钉着**，改本页头部之前先跑上面那条 grep。
**本轮（T140）在那四条命令原样重跑，同样是在最后一次编辑之后量的 ⇒ 589 / 24 / 268 / 4 四枚一字未动**，本页那一族也仍是
**13 枚 / 12 行**（两条尺同上面那格各重跑一次，单位别混这条规矩本轮照守），本页 `.kt` 那一格仍是 **4 行**（同上面那格那条 `-c` 尺原样跑 ⇒ 4）：本卡这一轮**动锚点动了五枚**，全在审计档那三格里，而每一枚都是**同格改字、只换那三个数字**（`:395`⇒`:412` 两枚、`:564`⇒`:581` 一枚、`:627`⇒`:644` 两枚；连写形状一枚没拆，也没往任何一页新写"文件名与其行号连写"那种形状），新写的行号一律裸 `:NNN` + 同框符号名或逐字原文 ⇒ 两族普查尺没理由挪，它没动同样是**量出来的**（审计档 §9.6 那一格本轮补了一句自证，同四把尺在那儿各对一次数，另加 `wc -l` 仍 1,513 ⇒ 零增删行）。⚠️ 本卡照守 T136 那条"往本页加行只加在第 30 行之后"：复算本轮那一串 hunk 头取最早一枚 ⇒ 起在 **32**，也就是覆盖表那一行（同格改字、没加行），头部 30 行不在任何 hunk 里 ⇒ 台账钉着的那两枚历史指针不因本卡再漂一格。

四件事不写清，下一个人还会抄错：

1. **旧版那句"T101 之后 517"复现不出来。** 同一把尺子在 T101 的三枚 commit 上分别给 516
   （`f122152`）、518（`87c2225`）、524（`2572c4f`，本页基点 `c55ddc5` 同值）—— 517 不是其中任何一个，
   它多半是某次未提交工作树上的读数。⇒ 现值按本轮量出的写。
2. **单位别混，两把尺今天各自给数。** 上面用的是 `grep -o`：**一行里两枚锚点算两条**（⇒ 589）。换成
   `git grep -n -I -E "[A-Za-z0-9_]+\.kt:[0-9]+" -- '*.md' | wc -l` 数的是**命中的行数**，T108 那一轮在
   `bb7924a` 上量到 **394**；而 T102 那一轮同一把尺在它自己的树上给 357（本轮用
   `git grep -n -I -E "[A-Za-z0-9_]+\.kt:[0-9]+" 8605707 -- '*.md' | wc -l` 复算 ⇒ **357**，一字未漂）。
   那 37 枚的差本轮逐档对过账：`git grep -c` 两版逐档相减 ⇒ **审计档 +36、STATUS +1、本页 +0**，
   全部落在 T103 与 T106 往 §6 里点的那些名上 —— 别把这种差当成尺子坏了，它是**文档自己写多了**。
   **现状（本轮 T111 在 `6d6d121` 之后重跑这一把）⇒ 405**（394 ⇒ 405 是 +11，全是编排者补 T103–T108
   五节与 T110 收单账那两趟纯文档改动写进 `docs/STATUS.md` 的点名；本卡两页**一枚 `.kt` 锚点没添**，
   所以上面那格 589 与审计档那两格都没动 —— 这一枚 405 只当"这一把尺今天多少"，别拿它跟 589 比大小）。
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
   ⚠️ **本轮给这条结论添了个反面实例**：4 → 4 说的是 `.kt` 那一族；同一趟本卡往本页写了 5 枚
   `docs/STATUS.md` 的收单账指针，于是 `.md:NNN` 那一族当场 8 ⇒ 13、全仓那一格 18 ⇒ 24（上面第二条）。
   ⇒ 规矩要说全：**粘命令不动任何一格，点名代码动 `.kt` 那一格，点名另一枚文档的收单账动 `.md` 那一格**
   —— 后两种都算"点名一处东西"，只是被点名的东西住在哪一档，尺子就量哪一档。
   本页那一族这次还撞出**两把尺不等**：`grep -ohE '[A-Za-z0-9_.-]+\.md:[0-9]+' docs/TESTING.md | wc -l`
   ⇒ **13 枚**，换成 `grep -c` 那把 ⇒ **12 行**（有一行点了两枚）—— 正是上面第 2 条那句"不许混"的现场。

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
—— 这一格是**回读出来的**而不是接着抄的，因为它就是本节论证的地基，不能让它在没人看的时候悄悄变；
**现状（T129 在 `073d098` 上重跑同一条 awk ⇒ 仍 8、八项一字未增未减**，`docs/` 与 `README.md` 也仍然不在里面），
**现状（T135 在 `d22a3f8` 上重跑同一条 awk ⇒ 仍 8、八项一字未增未减**，`docs/` 与 `README.md` 也仍然不在里面 ——
本批四张卡零枚构建脚本改动，复算 `git diff --name-only 073d098..HEAD -- app/build.gradle.kts` ⇒ 空），
**现状（T136 在 `1b0fefb` 上重跑同一条 awk ⇒ 仍 8、八项一字未增未减**，`docs/` 与 `README.md` 也仍然不在里面 —— T128 那两枚 commit 同样
零枚构建脚本改动，复算 `git diff --name-only d22a3f8..HEAD -- app/build.gradle.kts` ⇒ 空、`wc -l app/build.gradle.kts` 仍 770），**现状（T138 量于 `d7ce427`）：同一条 awk ⇒ 仍 8、八项一字未增未减，`wc -l app/build.gradle.kts` 仍 770（T137 那三枚 commit 零枚构建脚本改动，复算 `git diff --name-only 1b0fefb..HEAD -- app/build.gradle.kts` ⇒ 空），`docs/` 与 `README.md` 也仍然不在里面**，于是：

- **A 侧（加测试、忘了改本页）它会红。** 加了测试类就是加了 class 字节，`testDebugUnitTest` 本来就要
  重跑，守卫照常评估。这一侧它是可靠的。
- **B 侧（只改本页那个数）它不重跑。** 有人把 1698 手滑成 1663（上一版这一格写的是 1695 ⇒ 那句手滑对
  两个档位同样成立；**现状（T129）：这一格今天写的是 1,725 ⇒ 那句手滑对三个档位同样成立**；**现状（T135）：这一格今天写的是 1,770 ⇒ 那句手滑对四个档位同样成立**；**现状（T136）：这一格今天写的是 1,783 ⇒ 那句手滑对五个档位同样成立**；**现状（T138 量于 `d7ce427`）：这一格今天写的是 1,792 ⇒ 那句手滑对六个档位同样成立**；**现状（T140 量于 `b76cba9`）：这一格今天写的是 1,799 ⇒ 那句手滑对七个档位同样成立**），磁盘状态当场是红的，而 Gradle 判
  UP-TO-DATE、端一次缓存绿灯 —— 这就是 #86 / #92① 记过的同一个形状（那节注释里的实验 B 量过等价
  案例：改一行注释、零字节进字节码 ⇒ 编译重跑而测试判 UP-TO-DATE）。`docs/` 留在输入面之外还是
  当时的**决定**，不是漏：同一节写着"为什么不铺到整个仓库：那等于『改 README 也重跑 1,660 枚测试』，
  是拿一种错换另一种"。⚠️ 那句 `1,660` 在本卡落笔时是**逐字引构建脚本注释**（`app/build.gradle.kts` 第 424 行那句），
  而 T112（`65f77e5`，本卡合入之后）已把那三行注释跟到现值 ⇒ **从今天起它是历史引文、不再逐字成立**
  （复算 `awk 'NR==424' app/build.gradle.kts` 给的是 1,695）。引它是因为要引的是"当时的决定"，不是现值；
  ⚠️ **现状（T129 在 `073d098` 上重跑同一条 awk ⇒ 仍给 1,695）** —— 也就是说那三行注释自 T112 之后没再跟过现值，
  而本页今天静态尺已经是 **1,725** ⇒ 构建脚本里那句"重跑 1,695 枚测试"落后一格。**本卡红线不许碰 `app/`，
  所以这一格只如实记账、不动它**（T112 那一趟的先例是"改到那一枚文件时顺手把注释跟到现值"，
  本卡没有任何改 `app/` 的授权 ⇒ 这笔账留给下一张真动那枚文件的卡收）。
  本卡当年把这两行注释记进明留、判"在红线上不动"，那一格由 T112 收了。
  ⚠️ **现状（T135 在 `d22a3f8` 上重跑那两条 awk ⇒ 仍给 1,695，而且那两句还钉在同一对行号上）**：
  `awk 'NR==424' app/build.gradle.kts` 读出「// 为什么不铺到整个仓库：那等于"改 README 也重跑 1,695 枚测试"，是拿一种错换另一种。」、
  `awk 'NR==428' app/build.gradle.kts` 读出「// 指纹 ~3.6 MB、测试段本身 1,695 枚的墙钟不变）。代码类改动本来就会重跑（class 字节变了），」
  ⇒ 那两枚行号**没漂**（构建脚本自 T112 之后零改动：复算 `git diff --name-only 073d098..HEAD -- app/build.gradle.kts` ⇒ 空、
  `wc -l app/build.gradle.kts` ⇒ 770），而本页静态尺已经走到 **1,770** ⇒ 那句"重跑 1,695 枚测试"从今天起落后**两批**
  （1,695 ⇒ 1,725 ⇒ 1,770，差 75 枚）。**本卡照旧一个字不动它**：卡面明写这一格与"下次真改构建脚本的卡"并走，
  单独改它没有可复算的收益、还会给构建脚本造一次无意义 diff ⇒ 这笔账仍然留给下一张真动那枚文件的卡收。
  ⚠️ **现状（T136 在 `1b0fefb` 上重跑那两条 awk ⇒ 仍给 1,695，那两句仍在同一对行号 `:424` 与 `:428` 上，`wc -l` 仍 770）**：
  本页静态尺今天走到 **1,783** ⇒ 那句"重跑 1,695 枚测试"落后**三批**（1,695 ⇒ 1,725 ⇒ 1,770 ⇒ 1,783，差 **88** 枚）；上面那几档
  "落后两批 / 差 75 枚"按本页规矩留着不抹，它们量的是当时那棵 commit。**本卡仍一个字不动它**（红线不许碰 `app/`，账同上一段）。
  ⚠️ **现状（T138 在 `d7ce427` 上重跑那两条 awk ⇒ 仍给 1,695，那两句仍在同一对行号 `:424` 与 `:428` 上，`wc -l app/build.gradle.kts` 仍 770）**：本页静态尺今天走到 **1,792** ⇒ 那句"重跑 1,695 枚测试"落后**四批**（1,695 ⇒ 1,725 ⇒ 1,770 ⇒ 1,783 ⇒ 1,792，差 **97** 枚）；上面那几档"落后三批 / 差 88 枚"按本页规矩留着不抹，它们量的是当时那棵 commit。复算同上面那两条 `awk 'NR==424'` / `awk 'NR==428'`，本轮各跑一次 ⇒ 两句原文一字未动；**本卡仍一个字不动它**（红线不许碰 `app/`，这笔账仍然留给下一张真动那枚文件的卡收）。
  ⚠️ **现状（T140 在 `b76cba9` 上重跑那两条 awk ⇒ 仍给 1,695，那两句仍在同一对行号 `:424` 与 `:428` 上，`wc -l app/build.gradle.kts` 仍 770）**：本页静态尺今天走到 **1,799** ⇒ 那句"重跑 1,695 枚测试"落后**五批**（1,695 ⇒ 1,725 ⇒ 1,770 ⇒ 1,783 ⇒ 1,792 ⇒ 1,799，差 **104** 枚）；上面那几档"落后四批 / 差 97 枚"按本页规矩留着不抹，它们量的是当时那棵 commit。复算同上面那两条 `awk 'NR==424'` / `awk 'NR==428'`，本轮各跑一次 ⇒ 两句原文一字未动，而 T139 那两枚 commit 零枚构建脚本改动（复算 `git diff --name-only 412acb8..b76cba9 -- app/build.gradle.kts` ⇒ 零行，本卡原样跑过）；**本卡照旧一个字不动它**（红线不许碰 `app/`，账同上一段）。

B 侧那道假绿要补上，只有把 `../docs/TESTING.md` 塞进 `guardReadWorkingTreeFiles` —— 那是构建脚本改动，
本卡在红线之外；而且改完就把 T92① 关掉的账重新付一次（文档改一个标点 ⇒ 重跑全量 1698 枚，上一版是 1695；
**现状（T129）：全量 1,725 枚、上一版是 1,698**；**现状（T135）：全量 1,770 枚、上一版是 1,725**；**现状（T136）：全量 1,783 枚、上一版是 1,770**；**现状（T138 量于 `d7ce427`）：全量 1,792 枚、上一版是 1,783**；**现状（T140 量于 `b76cba9`）：全量 1,799 枚、上一版是 1,792**）。
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
不进 dex；同理不进测试任务的输入指纹。⚠️ 那枚字节数是 `87c2225` 那一档的地板、按本页规矩留着不抹；
**现状（T129）：编排侧在 `073d098` 上跑干净全量，签名包地板是 7,249,962 B**（这一枚也是编排者的读数、
本卡零 gradle 零产物，复算只能等下一趟门禁；本页能自证的仍是上面那些静态尺）。**现状（T135）：本卡又是零 gradle、零 adb、零产物，
`app/build/` 在这棵 worktree 上仍不存在（复算 `ls app/build/` ⇒ No such file or directory）⇒ 地板那枚数本卡既不订正也不新量，
7,249,962 B 仍是 `073d098` 那一档的在册读数**；**现状（T136）：地板这一格本轮第一次有更新的在册读数可写** —— 编排侧在合并对象
`5eadb78` 上跑干净全量，签名包 **7,251,984 B** ⇒ 新地板（起点是上一档 `d22a3f8` 那批的 7,250,597 ⇒ **+1,387 B**），同一棵树第二次全量
再读逐字节同数，且扫 apk dex 得 `GroupAppearanceEdit` 命中 **1** ⇒ 真往 dex 里加了类，按那一族的口径**记新地板**、"±几百字节的带"
这一档**不适用**；出处同上面那节（`docs/STATUS.md` 末尾裸 `:3790` 起头那一节）。地板这一格本轮又有更新的在册读数可写** —— 编排侧在合并对象 `714447b` 上跑干净全量，签名包 **7,252,469 B** ⇒ 新地板（起点是上一档 `5eadb78` 那棵的 7,251,984 ⇒ **+485 B**，代理侧与编排侧两侧同数 ⇒ 按那一族的口径**记新地板**，"±几百字节的带"这一档同样**不适用**）；出处就是台账末尾那一节「09-28 凌晨续二：T137 收单」—— 裸 `:3883` 那一行起头是「lint **0 error / 14 warning** → `:benchmark` 上一趟已 10/10 executed。**签名包 7,252,469 B。**」（本轮 `awk 'NR==3883' docs/STATUS.md` 回读起头一字未动；台账是 (A) 类、本卡一字未改）；上面那组 7,251,984 B 是 `5eadb78` 那一档的在册地板、按本页规矩留着不抹。**现状（T140 量于 `b76cba9`）：地板这一格本轮又有更新的在册读数可写** —— 编排侧在合并对象 `b76cba9` 上跑干净全量（`assembleRelease` 先行），签名包 **7,252,625 B** ⇒ 新地板（起点是上一档 `714447b` 那棵的 7,252,469 ⇒ **+156 B**，同一棵第二次全量逐字节同数 ⇒ 按那一族的口径**记新地板**；⚠️ 本轮台账给的判据是"真往 dex 里加了类"，所以**"±几百字节的带"这一档不适用**，本卡没有产物层证据、也不替它复算那一判）；出处就是台账末尾那一节「09-28 凌晨续四：T139 收单」—— 裸 `:3959` 那一行起头是「⇒ **包体按"两次独立全量一致"记新地板**：上一档 7,252,469 ⇒ **+156 B**」（本轮 `awk 'NR==3959' docs/STATUS.md` 回读起头一字未动；台账是 (A) 类、本卡一字未改）；上面那组 7,252,469 B 是 `714447b` 那一档的在册地板、按本页规矩留着不抹。
零产物，`ls app/build/` 在这棵 worktree 上仍给 `No such file or directory`（复算同上一段）⇒ 本页能自证的仍只有静态尺，而"文档对构建惰性"这半句本轮仍然成立 ——
它现在多了一份不靠产物的证据：T131…T134 那批往 test 树里添了 45 枚用例、往 `docs/` 里添了几十行账，
而 `app/build.gradle.kts` 连一次 diff 都没产生（复算 `git diff --name-only 073d098..HEAD -- app/build.gradle.kts` ⇒ 空，
同一条换成 `-- docs/` ⇒ 三行：本页与审计档、台账，全都不是构建输入）。如果哪天仍然要机器兜这一页，便宜的形状是**别走测试任务**：
在 CI 的 build step 之后直接跑本页那几条命令、把读数与文档 diff 掉 —— 没有输入面，就没有输入面这一族病。

