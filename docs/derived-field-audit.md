# 派生构造参数审计（T97 · 只报不动手）

分支 `ai/T97`，基点 `788acf5`。本文件是**唯一**被 T97 新增的文件，不改任何代码。
所有 `file:line` 都是本次 `Read`/`grep` 出来的原文（路径相对仓库根，行号按本工作树）。
**⚠️ 这一句只对 T97 当时成立**：本档行号现在按下面那节保鲜声明重核过。

## 锚点保鲜声明（T101 追加 · 本档头一节，先读这段再读行号）

**本档行号对应的 commit：`e47a18e`**（T101 逐条回读重核过；T97 初版按 `788acf5`）。
T97 之后主源码只动过一枚文件，而那枚文件正好是本档引用最密的一处（T97 正文对它写了 18 条显式锚点、
展开 45 个行号，另加 §2.2/§3.3 里那些不带文件名的裸 `:NNN`）：`2cf8405` 在
`ui/ScheduleViewModel.kt:101` 之前插进一段 KDoc ⇒ 该文件 101 行以后全体后移
（101–1377 段 **+7**、`1378` 那处展开成 6 行 ⇒ 其后 **+12**、`1409` 那处展开成 4 行 ⇒ 其后 **+15**：
起手 `_calendarSync.update` 1378→**1389**、成对写点 1394/1395→**1406/1407**、
`confirmCalendarSync` 1409→**1423**、`dismissCalendarSyncDiff` 1430→**1445**）。
代码侧一处不红（全仓守卫读的是 needle 文本不是行号），文档里的"按行号指路"却集体走偏 ——
**行号是易碎品**。以下四条是本仓写文档锚点的规矩，本档与 `docs/` 其余各档同守：

1. **行号只是辅助，不许单独承重**：每条引用必须同时给出**符号名**（`dismissCalendarSyncDiff`）
   或**原文片段**（`` `it.copy(diff = null, skippedOccurrences = 0)` ``）。行号漂了，读者还能凭后两样找回位置。
2. **文档头部标注锚点对应的 commit**（就是本节这一行）。改了被引文件、或 rebase 之后，顺手更新这一行的哈希。
3. **改主源码时若插入/删除了行**：顺手 `grep -rn "该文件名.kt" docs README.md` 把引用核一遍。
   分两档处置 —— **(A) 历史叙述**（"当时/改前/本卡把 X 改成 Y"，行号是当时的坐标）**保留原貌不订正**
   （本仓规矩，先例是 T96 对 `docs/STATUS.md:705`/`:728` 的处置）；**(B) 现状描述**（"现在长在 `:NNN`"）
   逐条回读，错了就改行号或改成符号名+原文片段。`docs/STATUS.md` 整本是逐卡台账 ⇒ 永远按 (A) 办。
4. **怎么复算**（别信本节，信命令）：
   `grep -rhoE '[A-Za-z0-9_]+\.kt:[0-9]+' docs/derived-field-audit.md | wc -l` 给本档的显式锚点条数
   （**现值 217** —— 注意这个数字本轮动过两次：`c55ddc5` 上是 218，本节底下删掉一枚举例锚点之后回到 217，
   而 T101 那一轮写的 217 只在 `f122152` 成立、`87c2225` 起其实是 218；T97 初版 215），逐条回读的思路是
   把 `X.kt:NNN` 拆成「文件 + 行号」→ `awk 'NR==NNN' app/src/main/java/com/buaa/schedule/<X>.kt` 读出那一行
   → 与同框的符号名/原文片段比对，不相符即改行号（**改行号，不改结论**）。
   ⚠️ **这一格被两把尺子数过，别再混**：上面那条吃 `grep -o`，一行里两枚锚点算**两条**（现值 217）；
   把 `-o` 漏掉写成 `grep -c` 数的是**命中的行数**，同一份文件给 109。编排者 09-26 在卡面上就把后者当
   前者引用过一次，所以这条命令要么整条照抄、要么别抄。
   裸 `:NNN` 的归属文件取同一句里最近的那枚显式文件名；`docs/` 里另一条普查命令是
   `grep -rhoE '[A-Za-z0-9_]+\.kt:[0-9]+' docs README.md | wc -l`（全仓普查现值 **526**，即本段落地之后；
   它是这样一路涨上来的：T101 订正后写的是 517，而 **517 在 T101 的三枚 commit 上都不复现**
   （`f122152` 516、`87c2225` 518、`2572c4f` 与基点 `c55ddc5` 都是 524），`8605707`（T102 把复算命令
   并进 `docs/TESTING.md`）527，本节底下删掉一枚举例锚点 ⇒ 526。T101 那句里的"订正前 514"指哪一档
   已经查不到，不再引用）。⇒ 这一族的现值**必须连着"在哪枚 commit 上量的"一起写**，否则下一个人数出
   不同的数，还以为是自己的尺子坏了 —— **改这一节自己就会挪动这一格的数**，本轮实测一次：删掉一句举例
   里 `ScheduleViewModel.kt` 那枚 `:101` 的行号，本档 218→217、全仓 527→526；而本段初稿把那个例子原样
   点名了一遍，计数当场弹回去 ⇒ 现在这句是**故意拆开的写法**，别有人"顺手"把它拼回 `X.kt:NNN` 的形状。
   **多写文件名正是规矩 ① 想要的结果**，
   涨的那几条不是漏网。

## 0. 口径、规模、判据怎么执行的

### 0.1 我自己数出来的规模

| 量 | 卡面给的 | 我实测的 | 说明 |
| --- | --- | --- | --- |
| `app/src/main/java` 下 `data class` 声明 | 85 枚**文件** | 85 枚文件、**177 行**声明 | 一行一枚，含 sealed 族里的 `data class Accepted(...) : Envelope` |
| 含 `.copy(` 的文件 | 34 | 34 | 一致 |
| `.copy(` 出现次数 | 126 | **140 次 / 139 行**（一行两处的那处是 `core/designsystem/ScheduleCharts.kt:1061`） | 卡面少 14 次；我用 `grep -o` |
| 另：无接收者的隐式 `copy(` | 未提 | **1 处**：`ui/home/WeekGridGeometry.kt:253` | 扩展函数 `CourseDragState.advancedBy` 里的 `return copy(...)`，**只 grep `.copy(` 会整条漏掉**，见 §0.3 |

141 处（140 + 1）里落在**本仓自己的 data class** 上的是 **71 处 / 18 枚类**；其余 70 处是 Compose 与平台类型（`Color.copy`、`Highlight.copy`、`TextStyle.copy`、`material3.ColorScheme.copy`、`Constraints.copy`），与本族无关。逐行分类见 §0.4 的落点表。

### 0.2 三档判据的实际执行方式

- **(a) 派生**：不只看定义处，而是**扫全部构造站点的实参**。参数默认值那一档全仓只有 27 枚字段带函数调用默认值，且全是 `emptyList()` / `LocalDate.now()`（`ui/ScheduleViewModel.kt:83`）——**没有一枚 `val x: Int = f(同类的另一枚参数)` 形状**。也就是说 T94 那种「派生表达式写在参数表默认值上」的形状在全仓当前是 0 枚；剩下能成立的是「调用点算好再递进去」，所以我把每个候选类的**每一处构造**都读了原文。
  同时排除掉一种**看着像 a 其实不是**的常见形状：多枚参数由同一个**外部对象/局部量**算出来（`ScanRejectInfo` 十枚字段全取 `shape.*`、`BackupPreview` 的 `courseCount`/`manualCourseCount` 全取 `data.courses`、`Meeting` 的 `location`/`teacher` 全取 `course.*`）。这叫**同源扇出**，任何一枚都不是"由同类另一枚构造参数算出来的"，`copy` 换掉其中一枚也不会让另一枚过期（它们各自独立地过期不了）。只有当参数值确实是**同表另一枚参数的函数**时才算 a，例如 `domain/schedule/CourseWeekSpans.kt:140` `unknownCount = rows.count { it.weeksUnknown }` 而 `rows = rows` 就是同表参数。
- **(b) copy 站点改源**：以 §0.4 的 71 处为准逐处读原文，判它改的是不是 (a) 那枚派生所依赖的源参数。
- **(c) 真读到旧值**：grep 字段名在 `app/src/main` 的读取点，并要求读取发生在 copy 之后的那枚对象上。

### 0.3 本卡筛法的一个已知盲点

按位置构造（`Coverage(key, course, course.displayName, …)`）我**没有**程序化解析——只读得出来「哪个实参落在第几个参数上」。风险被两件事压住了：本仓 data class 的构造站点几乎清一色用具名实参（我读过的构造站点里只有 `domain/schedule/WeekFreeGrid.kt:88`、`ui/signin/ScanDecodingAdmission.kt:115-118`、`domain/schedule/SemesterStats.kt:263-265` 这几处按位置或半按位置传，都逐条看过）；而**所有 71 处自有类的 copy 站点是逐处读原文定性的**，(b) 这一格没有盲点。另加 `data/calendar/CalendarSyncPlanner.kt:44`、`widget/CourseListWidgetService.kt:164` 两处整行按位置/半按位置的构造。

### 0.4 71 处自有类 copy 站点的落点（(b) 的完整宇宙）

| 类 | 处数 | 站点 |
| --- | --- | --- |
| `CalendarSyncUiState` | 22 | `ui/ScheduleViewModel.kt:1392,1398,1400,1404,1407,1426,1429,1432,1448,1452,1457,1465,1470,1474,1478,1482,1484,1487,1499,1511,1518,1528`（22 处全是 `_calendarSync.update { it.copy(…)` 的形状，分散在 `startCalendarSync` / `confirmCalendarSync` / `dismissCalendarSyncDiff` / `openCalendarPicker` / `dismissCalendarPicker` / `selectCalendarTarget` / `setCalendarReminderMinutes` / `requestRemoveSyncedEvents` / `dismissRemoveSyncedEvents` / `removeSyncedEvents` / `onCalendarPermissionDenied` / `onCalendarPermissionGranted` / `ensureCalendarsLoaded`） |
| `Course` | 13 | `data/import/BuaaScheduleParser.kt:136`、`data/repository/ScheduleRepository.kt:215,223,253`、`domain/schedule/CourseConstraints.kt:73`、`domain/schedule/ImportPlanner.kt:50,53`、`ui/course/CourseManagementScreen.kt:183`、`ui/home/ConflictWizardDialog.kt:81`、`ui/home/HomeScreen.kt:416,428,442`、`ui/ScheduleViewModel.kt:507`（`UndoManager.pushCreate(course.copy(id = savedId))`）**现状（T132 重钉）**：本行 copy 站点 那一格连写在向导文件尾上的 `:81` 是 T131 之前的读数；T131 在该文件 269→278、净插 9 行（窗口起点是 pre `:69` 那句 KDoc「写的是 [CourseSaveOptions.partialWeeks]：只改冲突的那几周…」），而且它把那一行**本身改写**成带 `weeks` 的一版 ⇒ 今天那枚 `Course` 的 copy 站点落在 `:89`，原文 `target.copy(periods = newPeriods, weeks = scopedWeeks)`（这一枚不是"同一行挪了位置"，是"同一站点的文本被 T131 换过"，读原文的人要按 `:89` 找）。复算：`git show 5f39a4d^:app/src/main/java/com/buaa/schedule/ui/home/ConflictWizardDialog.kt | awk 'NR==81'` 给旧的那一行，`git show HEAD:app/src/main/java/com/buaa/schedule/ui/home/ConflictWizardDialog.kt | awk 'NR==89'` 给今天这一行。同格那三枚落在首页文件上的 `:416`/`:428`/`:442` 在首页窗口（pre `:819`）之前 ⇒ 未漂。**现状（T135 重钉，基点 `d22a3f8`）**：上面那两句（向导那枚落 `:89`、首页那三枚"未漂"）同为 T132 在 `5f39a4d` 那棵树上的读数，本批 T133 `e41d95b` 与 T133b `a3700e9` 又往这两枚文件里插了行 ⇒ **四枚全漂，而且本卡要把一条假"现值"驳回**：① 向导那枚今天落在裸 `:104`，原文 `target.copy(periods = newPeriods, weeks = scopedWeeks, isManualOverride = true)` —— T133b 把 `isManualOverride` 折进同一枚 copy，所以那一行的**文本又被换过一版**，不是单纯挪位（同上一条复算形状：上一格末尾那两条带 `git show` 的复算把末尾的 89 换成 104 就给今天这一版，本机工作树上更直接的一条是 `awk 'NR==104' app/src/main/java/com/buaa/schedule/ui/home/ConflictWizardDialog.kt`）；② 首页那三枚今天落在裸 `:447` / `:460` / `:479`，逐枚 `awk` 读回的原文依次是「`val shifted = course.copy(`」「`shifted.copy(weeks = listOf(week)),`」「`viewModel.updateCourse(course.copy(periods = newPeriods.sorted(), isManualOverride = true))`」（复算 `grep -nE '\.copy\(' app/src/main/java/com/buaa/schedule/ui/home/HomeScreen.kt` ⇒ 五行，447 / 460 / 479 之外那两行是裸 `:538` 的 `titleMedium.copy` 与裸 `:943` 的 `colorScheme.copy`，都不在自有类宇宙里）。⇒ ⚠️ **卡面给的"现值应为 `:428` / `:441` / `:459`"按盘面驳回**：那三枚既不等于 `awk` 读出的原文所在行，也不是任何一扇插行窗口的产物（它长得像"旧行号各自减一"），本档按盘面写 447 / 460 / 479。两枚文件的净插与窗口都能复算：向导 278 → **293**（净插 **15**，唯一一扇窗口起在 pre `:72` 之后 ⇒ pre `:89` 那枚整行被推到 `:104`；跑 `git diff -U0 5f39a4d..HEAD -- app/src/main/java/com/buaa/schedule/ui/home/ConflictWizardDialog.kt` 读它那两枚 `@@` 头 ⇒ `@@ -72,0 +73,15 @@` 与 `@@ -89 +104 @@`）、首页 1368 → **1406**（净插 **38**，三扇窗口分别起在 pre `:415` 之后 +31、pre `:418` 之后 +1、pre `:439` 之后 +4，而 pre `:442` 那一行被**改写成三行**、copy 落在其中第二行 ⇒ 第三枚比前两枚各多漂 6 与 5 行，增量不是均匀的 +N，别拿"净插 38"去减；同一条 `git diff -U0` 换成首页那枚文件读 `@@` 头即可）。⇒ 上一版那句"首页窗口在 pre `:819`，所以这三枚未漂"从此是 `5f39a4d` 的历史叙述：**这一族的下一次复算要按 447 / 460 / 479 起读**。本行那 13 处的枚数仍是现值：其余八枚站点所在的那六枚文件在 `073d098..HEAD` 区间零改动（复算 `git diff --name-only 073d098..HEAD -- app/src/main` ⇒ 五枚：`ConflictWizardDialog`、`HomeScreen`、`StatsScreen` 三枚 `M` 加两枚新内核 `A`；两枚新内核里没有 `Course` 的 copy 站点，复算 `grep -nE '\.copy\(' app/src/main/java/com/buaa/schedule/ui/home/ManualTimeOverridePolicy.kt app/src/main/java/com/buaa/schedule/ui/home/ConflictShiftWeekScope.kt` ⇒ 仅冲突内核那枚 `:14` 一句 KDoc 里的举例，不是站点）⇒ 「13」与「无一改 `sourceGroupKey`」两句一字未动 |
| `WidgetAppearance` | 12 | `widget/WidgetConfigActivity.kt:346,380,404,417,427,441,449,477,498,515,532,580` |
| `ScanDecoderHealth` | 3 | `ui/signin/ScanRecoveryPolicy.kt:156,165,167` |
| `LiquidGlassMaterial` | 3 | `core/designsystem/DesignTokens.kt:282`、`core/designsystem/GlassSurface.kt:74`、`core/designsystem/GlassSegmentedControl.kt:77` |
| `WidgetBinding` | 3 | `widget/WidgetCommon.kt:893`、`widget/WidgetConfigActivity.kt:363,369` |
| `PendingImport` | 2 | `ui/ScheduleViewModel.kt:812,833`（两处 `_pendingImport.value = pending.copy(`） |
| `ReminderSetting` | 2 | `data/repository/ScheduleRepository.kt:225,395` |
| `TimeSlot` | 2 | `ui/settings/SettingsScreen.kt:654,668` |
| `SecondEngineLedger` | 2 | `ui/signin/ScanSecondEnginePolicy.kt:246,260` |
| `Semester` | 1 | `data/repository/ScheduleRepository.kt:627` |
| `SemesterEntity` | 1 | `data/repository/ScheduleRepository.kt:103` |
| `SemanticColors` | 1 | `core/designsystem/Theme.kt:167` |
| `CalendarSyncEntity` | 1 | `data/calendar/CalendarSyncManager.kt:173` |
| `ClassWindow` | 1 | `reminder/ReminderReceiver.kt:48` |
| `ScanAssistState` | 1 | `ui/signin/ScanCameraAidPolicy.kt:541` |
| `ResizeState` | 1 | `ui/home/WeekView.kt:1104` |
| `CourseDragState` | 1 | `ui/home/WeekGridGeometry.kt:253`（隐式接收者） |

**(b) 这一格是闭合的**：只有这 18 枚类有 copy 站点，其余 156 枚 data class 连一处 `copy` 都没有，档位最高只能到「无害」。

## 1. 主表

档位分布：**真回归 0 枚 · 潜在 1 枚 · 无害 24 枚**（另有 2 枚参照行：`PendingImport` 已修、`DecodingAdmission` 不算 a）。

| 类名 | 字段 | 档位 | 定义处 | copy 站点 | 读取点 | 一句话依据 |
| --- | --- | --- | --- | --- | --- | --- |
| `PendingImport` | `conflictGroupCount` | 参照（T94 真回归 → T95 已修） | `ui/ScheduleViewModel.kt:171`（**类体**属性 `val conflictGroupCount: Int = CourseConflictResolution.groupConflicts(conflicts).size`，不在参数表） | `ui/ScheduleViewModel.kt:812,833` 两处 `copy(conflicts = …)` | `ui/importing/ImportScreen.kt:249`、`ui/ScheduleViewModel.kt:1041,1105,1136`（三处 `groupCount = pending.conflictGroupCount`） | 组数 = `groupConflicts(conflicts).size`，挂在类体后任何 `copy` 都点不到它 ⇒ 不可能过期；本表其余各行的判据都从这一枚推出来 |
| `ScanDecoderHealth` | `giveUpReason` | **潜在** | `ui/signin/ScanRecoveryPolicy.kt:65`（参数），唯一赋值 `:160` = `"连续 $consecutive 帧解码失败、自动试回 $cycles 轮仍不成"` | `:165`、`:167` 改 `consecutiveFailures`/`suspensionCycles` 两枚源字段而**不重算** `giveUpReason` | `ui/signin/ScanRecoveryPolicy.kt:116,128,137`、`ui/signin/ScanFrameFlowPolicy.kt:142`、`ui/signin/SpocScanScreen.kt:1072,1223` | 三格字面全齐，不发火**只靠 `:150` 那行运行期早返回**（`if (health.giveUpReason != null) return health`），没有任何静态守卫钉住这个前提 ⇒ 详见 §2 |
| `Board` | `unknownCount`、`finishedCount` | 无害 | `domain/schedule/CourseWeekSpans.kt:73,74` | 无 | `ui/stats/StatsScreen.kt:1021,1023`、`ui/stats/StatsScreen.kt:267` | `:140` `unknownCount = rows.count { it.weeksUnknown }`、`:141` 同理，`rows` 就是同表参数；全仓无 `Board.copy`。**现状（T132 重钉）**：本行 读取点 那枚 `:1021,1023` 是 T131 之前的读数 —— T131 在 StatsScreen 那一枚文件 1100→1104、净插 4 行，起点是 pre `:298` 那行 `onApplyShift = { target, newPeriods -> …`，其后各落点整体 +4 ⇒ 今天 `:1025` 那一行是 `board.finishedCount.takeIf { it > 0 }`、`:1027` 那一行是 `board.unknownCount.takeIf { it > 0 }`；同格另那一枚 `:267` 在窗口之前 ⇒ 一字未漂。总账与复算命令见 STATUS 末尾 T132 那一节 |
| `Coverage` | `finished`（源＝同表 `lastWeek`）、`label`（源＝同表 `course`） | 无害 | `domain/schedule/CourseWeekSpans.kt:57,51` | 无 | `ui/stats/StatsScreen.kt:993,1002,1003`、`domain/schedule/CourseWeekSpans.kt:90,129` | `:126` `finished = currentWeek != null && lastWeek != null && lastWeek < currentWeek`，`lastWeek` 就是同表参数（`:123`）；`:120` `label = fragments.first().displayName` 与 `:119` `course = fragments.first()` 同值同源 ⇒ label = course.displayName。其余 `weeksUnknown`/`firstWeek`/`spans`/`fragmentCount` 全取函数内的局部 `inRangeWeeks`/`fragments`，**不是同表参数的函数**，故不计入 (a)。无 `Coverage.copy`。**现状（T132 重钉）**：本行 读取点 那枚 `:993,1002,1003` 是 T131 之前的读数（StatsScreen 1100→1104、净插 4 行，窗口起点 pre `:298` 的 `onApplyShift = { target, newPeriods -> …`）⇒ 今天 `:997` 那一行是 `label = coverage.label`、`:1006` 那一行是 `finished = coverage.finished`、`:1007` 那一行是 `endsAtWeek = coverage.lastWeek`；同行那两枚 `CourseWeekSpans` 的落点不在本三枚文件里 ⇒ 未漂 |
| `SemesterSummary` | `courseCount`、`totalCredits`、`creditsMissing`、`busiestDayOfWeek`、`quietestBusyDay`、`freeSlotCount` | 无害 | `domain/schedule/SemesterStats.kt:111,112,114,117,118,120` | 无 | `ui/stats/StatsScreen.kt:191,192,391,401,402,922` | `:268` `courseCount = credits.size` 而 `:272` `perCourse = credits`（同表）；`:271` `creditsMissing = credits.size - known` 同表两枚（`creditsKnown = known` 在 `:270`）；`:274,275,277` 全是 `loads` = `dayLoads` 参数的函数。**不计 a** 的两枚：`:267` `fragmentCount = courses.size` 与 `:278` `weekCount = weekAxisLength(courses, semester)` 取的是 `summarize` 的入参，不是本表参数。无 `SemesterSummary.copy`。**现状（T132 重钉）**：本行 读取点 那枚 `:191,192,391,401,402,922` 是 T131 之前的读数；StatsScreen 1100→1104 净插 4 行、窗口起点 pre `:298`（`onApplyShift = { target, newPeriods -> …`）⇒ `:191`/`:192` 两枚在窗口之前**未漂**，其余四枚今天依次是 `:395` `text = formatCreditTotal(summary.totalCredits)`、`:405` `if (summary.creditsMissing > 0)`、`:406` 那句「`${summary.creditsMissing} 门课没有学分数据，未计入`」、`:926` `text = "${summary.freeSlotCount}"` |
| `DayLoad` | `courseCount`、`freePeriodCount`、`averageMinutes`、`peakMinutes` | 无害 | `domain/schedule/SemesterStats.kt:82,83,80,81` | 无 | `ui/stats/StatsScreen.kt:190,421`、`domain/schedule/SemesterStats.kt:255` | `:220` `courseCount = groupsByDay[day].size` 中 `day` 即同表参数 `dayOfWeek`（`:217`）；`:218,219` 同理由 `perWeek = minutesByDayAndWeek[day]`（`:215`）。无 copy。**现状（T132 重钉）**：本行 读取点 那枚 `:190,421` 是 T131 之前的读数；StatsScreen 净插 4 行、窗口起点 pre `:298` ⇒ `:190` 未漂，`:421` 今天落在 `:425`，那一行原文是 `val busiest = summary.busiestDayOfWeek?.let { iso -> summary.dayLoads.getOrNull(iso - 1) }`；同行那枚 `SemesterStats` 的 `:255` 不在本三枚文件里 ⇒ 未漂 |
| `CourseCredit` | `course`、`credit` | 无害 | `domain/schedule/SemesterStats.kt:61,62` | 无 | `ui/stats/StatsScreen.kt:192,859,880` | `:159,160` 两枚都从同表参数 `fragments`（`:161`）算出。无 copy。**现状（T132 重钉）**：本行 读取点 那三枚是 T131 之前的读数；StatsScreen 净插 4 行、窗口起点 pre `:298` ⇒ `:192` 未漂，`:859` 今天落在 `:863`（那一行原文 `else (item.credit ?: 0.0).toFloat() / maxCredit.toFloat()`）、`:880` 今天落在 `:884`（那一行原文 `val missing = perCourse.count { it.credit == null }`） |
| `Meeting` | `label`、`groupKey`、`location`、`teacher`、`campus`、`periodCount`、`minutes`、`minutesKnown`、`periodsKnown` | 无害 | `domain/schedule/WeekDaySchedule.kt:67,68,74,75,76,70,71,72,73` | 无 | `ui/stats/StatsDrillCopy.kt:44,45,46,56,72`、`ui/stats/StatsScreen.kt:718` | `:181` `label = course.displayName`、`:182` `groupKey = …(course)`、`:184,185` 由 `distinctPeriods`（`:178` 取自 `course.periods`）⇒ 全是同表参数 `course`（`:180`）的函数。无 copy。**现状（T132 重钉）**：本行 读取点 那枚 StatsScreen 的 `:718` 是 T131 之前的读数（净插 4 行、窗口起点 pre `:298` 的 `onApplyShift = { target, newPeriods -> …`）⇒ 今天落在 `:722`，那一行原文是 `day.meetings.take(MAX_MEETINGS_IN_WEEK_VIEW)`；同行那四枚 `StatsDrillCopy` 的落点不在本三枚文件里 ⇒ 未漂 |
| `DaySchedule` | `meetings` | 无害 | `domain/schedule/WeekDaySchedule.kt:86` | 无 | `domain/schedule/WeekDaySchedule.kt:89,98,127,136`、`ui/stats/StatsScreen.kt:697,699` | `:200` `dayLists[day - 1]`，`day` 即同表参数 `dayOfWeek`（`:199`）。无 copy。**现状（T132 重钉）**：本行 读取点 那枚 StatsScreen 的 `:697,699` 是 T131 之前的读数（净插 4 行、窗口起点 pre `:298`）⇒ 今天 `:701` 那一行是 `?: week.days.filter { it.meetings.isNotEmpty() }`、`:703` 那一行是 `if (day.meetings.isEmpty())` |
| `Grid`（`WeekFreeGrid`） | `weekUnresolved`、`freeDayOfWeek`、`freePeriods`、`occupiedCellCount` | 无害 | `domain/schedule/WeekFreeGrid.kt:72,76,77,78` | 无 | `ui/stats/StatsScreen.kt:1044,1063,1064`、`domain/schedule/WeekFreeGrid.kt:82`（`freeCellCount get() = cellCount - occupiedCellCount`） | `:140` `weekUnresolved = resolvedWeek == null`（`week = resolvedWeek` 同表）、`:144,146` 取同表 `occupiedByDay`、`:145` 取同表 `rows`。无 copy。**现状（T132 重钉）**：本行 读取点 那枚 StatsScreen 的 `:1044,1063,1064` 是 T131 之前的读数（净插 4 行、窗口起点 pre `:298`）⇒ 今天依次是 `:1048` 那一行 `isEmptiest = grid.freeDayOfWeek == day.dayOfWeek`、`:1067` 那一行 `grid.freeDayOfWeek?.let { …最空的是周… }`、`:1068` 那一行 `grid.freePeriods.takeIf { it.isNotEmpty() }`；同格那枚 `WeekFreeGrid` 的 `:82` 不在本三枚文件里 ⇒ 未漂 |
| `Row`（`WeekFreeGrid`） | `occupiedDays` | 无害 | `domain/schedule/WeekFreeGrid.kt:38` | 无 | `domain/schedule/WeekFreeGrid.kt:40,41,88` | `:133` 整个列表按同表参数 `period`（`:132`）逐天判出。无 copy |
| `DayRow`（`WeekFreeGrid`） | `occupiedPeriods` | 无害 | `domain/schedule/WeekFreeGrid.kt:48` | 无 | `domain/schedule/WeekFreeGrid.kt:49,50,51` | `:88` 由同表参数 `dayOfWeek` 取列；且这一处本身就长在 `Grid.dayRows` 这个**类体 `get()`** 里（`:86-89`），是安全形状 |
| `Trend` | `peakWeek`、`currentWeek` | 无害 | `domain/schedule/WeeklyLoadTrend.kt:53,52` | 无 | `core/designsystem/ScheduleCharts.kt:1073,1106`、`ui/stats/StatsScreen.kt:970` | `:124` `peakWeek = if (peakMinutes > 0L) peak else null` 直接以同表参数 `peakMinutes`（`:125`）当开关；`:123` `currentWeek = currentWeek?.takeIf { it in 1..totalWeeks }` 用同表参数 `totalWeeks`（`:121`）夹。无 copy。**现状（T132 重钉）**：本行 读取点 那枚 StatsScreen 的 `:970` 是 T131 之前的读数（净插 4 行、窗口起点 pre `:298`）⇒ 今天落在 `:974`，那一行原文是 `trend.peakWeek?.let { "最忙的是第 $it 周，约 ${humanMinutes(trend.peakMinutes)}" }`；同格那两枚 `ScheduleCharts` 的落点不在本三枚文件里 ⇒ 未漂 |
| `TodayPlan` | `minutesToNext`、`minutesRemaining` | 无害 | `domain/schedule/TodayPlanner.kt:31,33` | 无 | `ui/home/DayView.kt:1196,1225` | `:85` `minutesToNext = next?.let { … }`、`:86` `minutesRemaining = ongoing?.let { … }`，`next`/`ongoing` 都是同表参数（`:81,82`）。无 copy |
| `Occurrence` | `stableId`、`contentHash` | 无害 | `domain/schedule/ScheduleOccurrences.kt:24,26` | 无（`Occurrence` 本身） | `data/calendar/CalendarSyncPlanner.kt:26,29,31,33,42`、`data/calendar/CalendarSyncManager.kt:369,377` | `:96,97` 两枚都是 `(course, week, segment, date…)` 的函数，这些全是同表参数（`:91,95,92`）。注意 `data/calendar/CalendarSyncManager.kt:173` 那处 copy 属于 `CalendarSyncEntity` 且**显式带了新的 `contentHash`** |
| `TintPlate` | `secondaryForeground` | 无害 | `core/designsystem/Color.kt:192` | 无 | `ui/home/DayView.kt:1113` | `:244` `secondaryForeground = dimForeground(foreground, luma)`，`foreground` 即同表参数（`:243`）—— :186 那句注释「三个字段要一起用，拆开就没有意义」正是这一族该有的警告，但它没有 copy 站点可拆 |
| `CourseGroup` | `displayName` | 无害 | `ui/course/CourseManagementScreen.kt:444` | 无 | `ui/course/CourseManagementScreen.kt:428`（排序键）、列表行标题 | `:421` `displayName = groupDisplayNameOf(fragments, primary)`，`fragments` 就是同表参数（`:423`）。无 copy ⇒ 不会「改了片段却不改门面名」 |
| `ParseOutcome` | `fallbackWeekCourses`、`unknownTeacherCourses` | 无害 | `data/import/BuaaScheduleParser.kt:22,24` | 无 | `ui/importing/BuaaLoginScreen.kt:192,193,195,196` | `:118,119` 两枚都是 `merged.count { … }` 而 `:117` `courses = merged` 是同表参数。无 copy |
| `ReminderPlan` | `triggerAtMillis` | 无害 | `reminder/ReminderScheduler.kt:52` | 无 | `reminder/ReminderScheduler.kt:158,160,358`（排闹钟与取最近一条） | `:346` `triggerAt = occurrence.classStart… - advanceMinutes * 60_000L`，而 `:352` `classStart = occurrence.classStart`、`:353` `advanceMinutes = advanceMinutes` 都是同表参数 ⇒ 它是两枚同表参数的函数。无 `ReminderPlan.copy`（全仓该类只构造一次） |
| `DayCourseRow` | `startTime`、`endTime` | 无害 | `ui/home/DayView.kt:703,704` | 无 | `ui/home/DayView.kt:678,679` | `:728,729` 取同表参数 `segment`（`:726`）的首末节去查节次表。`private` 类、只在 `:724` 构造一次 |
| `CourseMenuRequest` | `anchorX` | 无害 | `ui/home/WeekView.kt:1623` | 无 | `ui/home/WeekView.kt:1650`（菜单弹层定位） | `:942,1081` `anchorX = index * dayWidthPx + …`，`index` 即同表参数 `dayIndex`。无 copy |
| `CourseDragState` | `originStartPeriod`、`targetStartPeriod`、`originTopPx` | 无害 | `ui/home/WeekGridGeometry.kt:219,222,224` | 有 1 处：`ui/home/WeekGridGeometry.kt:253`（隐式 `copy`） | `ui/home/WeekView.kt:901,907,1032` | `:881,1006` `originStartPeriod = segment.first`（同表参数），但 `:253` 那处 copy 改的是 `totalOffset`/`targetDayIndex`/`targetStartPeriod`，**没碰 `segment`/`originTopPx`** ⇒ 改源不成立 |
| `BlankDecodingTrace` | `occurrence` | 无害 | `ui/signin/ScanDecodingAdmission.kt:162` | 无 | `ui/signin/SpocScanScreen.kt:1387,1389` | `:176` `occurrence = count`，而同一行的 `ledger = BlankDecodingLedger(count, …)` ⇒ `occurrence == ledger.count` 是两枚参数间的真不变式。无 copy |
| `DecodingAdmission` | `payloadLength`（与 `blankness` 的口径约束） | 参照：**严格不算 a**，见 §3.6 | `ui/signin/ScanDecodingAdmission.kt:92` | 无 | `ui/signin/ScanDecodingAdmission.kt:197`（取证行「长度=」） | `:115-118` 两枚各自独立地由那颗 `payload` 算出，`payloadLength` 不是 `blankness` 的函数，只有值域约束（注释 `:91`「NoText 与 EmptyText 都是 0」）。`admitted` 长在类体 `get()`（`:95-96`）= 安全形状 |
| `WidgetData` | `courses` | 无害 | `widget/WidgetDataCache.kt:22` | 无 | `widget/WidgetCommon.kt:288,446,656`、`widget/WidgetDataSynchronizer.kt:273` | `:84` `courses = repository.getDisplayCourses(semester)`，`semester` 就是同表参数（`:83`）。无 copy，且整枚换引用（`widget/WidgetDataCache.kt:88`） |
| `Semester` | `termName` | 无害 | `domain/model/Semester.kt:8` | 有 1 处：`data/repository/ScheduleRepository.kt:627` | `data/export/IcsExporter.kt:61`、`ui/ScheduleViewModel.kt:1244`（`semesterName = data.semester?.termName`） | `ui/ScheduleViewModel.kt:235`（`termName = termCode`）、`data/repository/ScheduleRepository.kt:564`、`data/import/BuaaScheduleParser.kt:225` 三处同表回退；`:627` 那处 copy 只改 `startDate`/`totalWeeks`，**不碰 `termCode`** ⇒ 改源不成立 |
| `Course` | `isManualOverride` | 无害 | `domain/model/Course.kt:38` | 13 处（§0.4），**无一改 `sourceGroupKey`** | `domain/schedule/ImportPlanner.kt:39,61` | `ui/editor/CourseEditorScreen.kt:227` `isManualOverride = … || initialCourse?.sourceGroupKey != null` 与同表参数 `sourceGroupKey`（`:225`）构成「有来源键 ⇒ 必标手动」的不变式；13 处 copy 没有一处写 `sourceGroupKey` ⇒ 改源不成立 |

## 2. 逐枚详解

### 2.0 真回归 0 枚

按三档判据逐格要求（a 派生 + b 改源的 copy 站点 + c copy 之后有人读），**全仓没有一枚字段现在就在念旧值**。这不是"筛得松"，而是被 §0.4 那个闭合事实顶住的：能进 (b) 的类只有 18 枚，其中 12 枚（`SemesterEntity`、`TimeSlot`、`WidgetAppearance`、`WidgetBinding`、`SemanticColors`、`LiquidGlassMaterial`、`CalendarSyncEntity`、`ReminderSetting`、`ScanAssistState`、`SecondEngineLedger`、`ResizeState`、`ClassWindow`）的参数表里**找不出一枚是另一枚的函数**，剩下 6 枚逐条给了：`PendingImport`（已修）、`Course`（两枚候选都是假阳性，见 §3.1/§3.2）、`Semester`/`CourseDragState`/`CalendarSyncUiState`（copy 没改源）、`ScanDecoderHealth`（下一节）。

### 2.1 `ScanDecoderHealth.giveUpReason`（潜在，全仓唯一一枚 a+b+c 字面齐全的）

三格的证据：

- **(a) 定义处**：`ui/signin/ScanRecoveryPolicy.kt:65`
  `val giveUpReason: String? = null` —— 它**在参数表上**，所以 `copy()` 会把没点名的它原样带走。
  **赋值处（生产代码全仓只有一处）**：`ui/signin/ScanRecoveryPolicy.kt:160`
  `giveUpReason = "连续 $consecutive 帧解码失败、自动试回 $cycles 轮仍不成",`
  `consecutive` 在 `:152` 算出并立刻以 `consecutiveFailures = consecutive`（`:157`）交回同表参数，`cycles` 在 `:154` 算出并以 `suspensionCycles = cycles`（`:159`）交回 ⇒ 这枚字符串就是那两枚构造参数的函数（判据里点名的"字符串拼接"形状）。
- **(b) 改源不重算的 copy 站点两处**：
  `ui/signin/ScanRecoveryPolicy.kt:165` `return health.copy(consecutiveFailures = consecutive, framesAtLastFailure = frameSerial)`
  `ui/signin/ScanRecoveryPolicy.kt:167-172` `return health.copy(consecutiveFailures = consecutive, …, suspensionCycles = cycles, suspendUntilFrame = …)`
  两枚都改了 (a) 的源，都没点 `giveUpReason`。
- **(c) copy 之后读这枚字段的点**：`ui/signin/ScanRecoveryPolicy.kt:116`（`scannerWorkingOf`）、`:128`（`scannerGiveUp`）、`:137`（`decoderFrameAction` 的 Skip 档）、`ui/signin/ScanFrameFlowPolicy.kt:142`（取证行「原因 …」）、`ui/signin/SpocScanScreen.kt:1072`（界面那一档的措辞）、`:1223`（留痕）。

**为什么现在念不出错**：`healthAfterDecodeFailure` 的入口 `ui/signin/ScanRecoveryPolicy.kt:150` 是
`if (health.giveUpReason != null) return health`
—— 判死之后这个函数对**任何**输入都原样返回同一枚对象（`ui/signin/SpocScanScreen.kt:1214` 还靠 `if (next === health) return` 的引用相等提前退出），所以 `:165`/`:167` 这两条不重算的路径只在 `giveUpReason == null` 时走得到，走过去之后它仍然是 `null`，而 `null` 恰好是对的（还没判死）。复位那两档（`:183`、`:195`）用的是**整枚新构造** `ScanDecoderHealth(pageVisibleRecoveries = …)`，不是 copy ⇒ 不会带走旧字符串。

**所以它是"潜在"而不是"真回归"的准确说法**：正确性挂在一行运行期早返回上，而这一行**没有任何静态守卫钉着**。将来谁在别处加一处 `health.copy(consecutiveFailures = …)`（例如"复探失败要再记一笔"这类改动，正是 `ui/signin/SpocScanScreen.kt:1213` 现在唯一调 `healthAfterDecodeFailure` 的那条链被拉长）、或者把 `:150` 那行改成"判死后还允许再数几帧"，界面上立刻出现的错是：

- 界面/文案侧：`ui/signin/SpocScanScreen.kt:1072` 那行 `val why = health.giveUpReason ?: "停用窗口内"` 会念出**上一轮的**"连续 N 帧解码失败、自动试回 M 轮仍不成"，而 N/M 与当时账上的 `consecutiveFailures`/`suspensionCycles` 已经不是同一把尺子；`ui/signin/ScanFrameFlowPolicy.kt:142` 那行 Warn 取证同样带着旧数（这一页修过三轮"静默 no-op"，读数不一致正是它自己的靶子）。
- 判据侧：`scannerGiveUp`（`:128`）只判 `!= null`，字符串内容过期不影响分档；受影响的只有**说给用户/日志看的那句话里的两个数**。

**两种最小修法**：

① 类体派生属性（T95 那一刀）：把 `:65` 从参数表挪进类体，写成
`val giveUpReason: String? = if (suspensionCycles >= MaxDecodeSuspensionCycles) "连续 $consecutiveFailures 帧解码失败、自动试回 $suspensionCycles 轮仍不成" else null`
之后 `:156-161` 那个分支就不必再点它（早返回 `:150` 也可以改成 `if (scannerGiveUp(health)) return health`，形状更干净）。
代价与风险：**这一枚不是 `PendingImport.conflictGroupCount` 那种"纯函数"**。`groupConflicts(conflicts)` 只吃 `conflicts` 一枚，而这里要把"判死这件事"（`cycles >= MaxDecodeSuspensionCycles`）一起搬进类体，等于把判据从 `:155` 那行**复制**到类体里 —— 判据就此变成两处（`ui/signin/ScanRecoveryPolicy.kt:155` 的分支条件 + 类体的三元），而这一页的既有纪律恰恰是"判据只此一处"（`ui/signin/ScanRecoveryPolicy.kt:125` 那句「⚠️ UI 侧不许绕过这里直接读 giveUpReason 拼分支」同一条理由）。另外测试侧有 **7 处**手搓 `giveUpReason` 字符串（口径：按 `giveUpReason\s*=(?!=)` 数**赋值点** —— 只 grep `giveUpReason =` 会把 5 处 `==` 比较一起数进来，那把尺子给 12；「4 处」是初版的小账，`[T100③ 订正]`）：`app/src/test/java/com/buaa/schedule/ui/signin/ScanFrameFlowGuardTest.kt:93`、`app/src/test/java/com/buaa/schedule/ui/signin/ScanRecoveryPolicyTest.kt:154,158,176,178,210,212`，其中 `:210` 递的是"回到前台额度用完"这一档 —— 生产代码目前**从不**产生这个值，改成类体属性后这一档连表达方式都没了（它不是 `(consecutiveFailures, suspensionCycles)` 的函数）。这一枚恰好说明 ① 不是万能刀。

② 形状守卫（本仓已有四种钉法）：不换形状，而是把"不发火"的前提钉死。推荐三条一起钉，成本比 ① 低、且不引入第二处判据：
- **逐字节**：钉 `ui/signin/ScanRecoveryPolicy.kt:150` 那一整行原文 `if (health.giveUpReason != null) return health` —— 它是这枚字段唯一的保护，删掉它当场红。
- **数出现次数**：`assertEquals(1, occurrences(f, "giveUpReason ="))` —— 生产文件里赋值点只许有一处（现在正好 1 处，在 `:160`）。第二处赋值 = 第二把尺子。
- **读形状**：`assertFalse(f.contains(Regex("""copy\(\s*consecutiveFailures\s*=[^)]*\)""")))` 的反面做法太脆，改成数出现次数更稳：`assertEquals(2, occurrences(f, "consecutiveFailures = consecutive"))` 并把 `:165`/`:167` 两处的行号写进断言消息，改站点必须同步改守卫。
- （可选）**抹注释找锚点**：`ui/signin/ScanRecoveryPolicy.kt` 里给"判死只有一处赋值"留一行锚点注释（例如 `// giveUpReason 的赋值全仓只此一处`），守卫先抹掉 `//` 与 `/* */` 再数这句锚点是否还在，防止有人靠改注释绕过。

**我倾向 ②**。理由就一句：这一枚派生值**不是同表参数的纯函数**（它还要"判到哪一档"这个上下文），把它做成类体属性要么复制判据、要么丢档位；而它的不发火前提是一行显式代码，一行显式代码是可以被守卫钉住的，`copy()` 的语义盲区才是钉不住的。对照 ①/② 的分工：**能写成 `f(同表参数)` 的走 ①（`PendingImport` 那枚就是），写不出的走 ② 把运行期前提钉成静态前提。**

### 2.2 顺带量到的一枚口径问题（不算本族，写进回执 · **这笔账已由 T100 收掉**）

**改前的形状**（T97 当时读到、也是本节原标题"顺带量到的一枚口径问题"说的那件事）：
`ui/ScheduleViewModel.kt:1389` 起手那档当时写在 `:1378`，原文
`_calendarSync.update { it.copy(syncing = true, message = null, diff = null) }` —— 把 `diff` 清空却没清
`skippedOccurrences`，而两者是 `CalendarSyncManager.computeDiff` 一次返回的同一对（成对写点现在长在
`:1406` `diff = computed.first` + `:1407` `skippedOccurrences = computed.second`；`dismissCalendarSyncDiff`
当时在 `:1430`、现在在 `:1445`，那一档从一开始就是成对清的）。
**现状（T100 收的）**：`2cf8405` 给起手与 `confirmCalendarSync` 各补了一枚 `skippedOccurrences = 0`，
今天三处清空 `:1389` / `:1423` / `:1445` 全部成对（复算：`grep -rn "diff = null" app/src/main/java --include=*.kt`
恰好这三条，且同一枚 `copy(…)` 实参表里都带 `skippedOccurrences = 0`），规矩钉在**写侧**的
`app/src/test/java/com/buaa/schedule/ui/CalendarSyncDiffClearPairingGuardTest.kt`：`:45`
`everyDiffClearInViewModelAlsoClearsSkippedOccurrences`（数 3 处、逐处要求成对）+ `:77`
`thePairedProducerIsTheOnlyWriterAndTheOnlyReaderSitsInsideTheDiffModal`（钉生产者唯一）。
它**不满足 (a)**（`skippedOccurrences` 不是 `diff` 的函数，两枚都来自外部那趟计算），所以不列进主表；
而且改前也读不出旧值：唯一渲染点在 `ui/settings/SettingsScreen.kt:1914`，整块长在
`ModalTransition(payload = calendarSync.diff)`（`:1903`）里，`diff == null` 时不组合 —— 漏清的那一半只是
"上一份的附属说明活到下一份之前"，不念错数。

**【T100⓪ 判定：两处漏清都是缺陷，收法＝三处清空成对】** 上面"不算本族"成立（它确实不是 `f(diff)`），但"该不该一起清"问的是**寿命归谁**，三条读数都朝"这一份 diff 的附属说明"（下面这段是 T100⓪ 的判定原文，行号已由 T101 按 `e47a18e` 重核；判定当时看到的是改前形状）：

- **① 生产者只有一枚，且成对**：`data/calendar/CalendarSyncManager.kt:98`
  `suspend fun computeDiff(calendarId: Long): Pair<CalendarSyncPlanner.Diff, Int>?`，那枚 `Int` 就是同一趟
  `ScheduleOccurrences.build(semester, courses, timeSlots)`（`:108`）的 `build.skipped`，`:111` 与 `Diff` 装进同一个 `Pair` 返回。
  `ui/ScheduleViewModel.kt:1409` + `:1410` 是**全仓唯一**的写点；`computed == null` 那一档（`:1404-1407`）两枚一起不写 ⇒
  这枚数从来没有独立于 diff 的产生路径。
- **② `confirmCalendarSync` 起手那档（`:1423`）之后那条链不再产它、也不再读它**：它的应用段（`:1426-1440`，`it.copy(` 起在 `:1429`）那次
  `copy` 只带 `syncing` 与 `message`，而 `ApplyResult`（`data/calendar/CalendarSyncManager.kt:40-47`）只有
  `inserted/updated/deleted/failed`、**没有** skipped 字段 ⇒ "同步完成后仍想知道刚才跳过几节"这件事在代码里没有承载体
  （成功文案 `:1434` 念的也只有新增/更新/删除三个数）。
- **③ 唯一读点锁在 diff 的挂载闸门里**：`ui/settings/SettingsScreen.kt:1914` + `:1916` 是全仓唯一消费点（另一枚同名的是
  `data/export/IcsExporter.kt:26`，走 `ui/ScheduleViewModel.kt:1601`/`:1602` 那两行 `result.skippedOccurrences`，与本卡无关），它整块长在
  `ModalTransition(payload = calendarSync.diff)`（`:1903`）内；那一层的 payload 版（`core/designsystem/ModalTransition.kt:84-100`）
  外壳开合只看 `payload != null`（`:95`），收场期间画锚住的**上一次** payload（`:91`/`:93`/`:100`）⇒ 块的可见窗口由那份 diff 关掉。

⇒ 采"附属说明"这一读法：`diff` 清空而它留着 = 一对里漏一半，`grep -rn "diff = null" app/src/main/java --include=*.kt` 恰好 3 条
（判定当时的三条坐标是 `ui/ScheduleViewModel.kt:1378`/`:1409`/`:1430` —— 那是 `788acf5` 的行号，别再照着读，
现在同一批站点写在 `:1389`/`:1423`/`:1445`），当时只有 `dismissCalendarSyncDiff`（现 `:1445`）成对
⇒ 起手与 `confirmCalendarSync` 各补一枚 `skippedOccurrences = 0`。
另一种读法（"独立事实"⇒ 改渲染口径把它挪出 `ModalTransition(payload = diff)`）**弃**：②已证它没有"留着以后还要用"的消费方，
把它挪出弹窗等于凭空给界面添一行常驻文案。
读法 A 的代价如实记一条：清 0 之后，收场那几帧里锚住的旧 `diff` 三个数还在淡出、这一行当场消失 —— 这正是 `:1445`
（`dismissCalendarSyncDiff`）在 T100 **之前就已经有**的行为，T100 只是把它对齐到另外两处。改完 `startCalendarSync` 整条链
（含 `computed == null` 那一档 `:1401-1404`）不存在"diff 为空而 `skippedOccurrences` 非零"的可读窗口，
因为起手 `:1389` 已经清过。

## 3. 我查过但排除的

程序化两遍（第一遍：实参表达式里**字面出现同表另一枚参数名**，命中 19 处；第二遍：多枚实参**共享同一个取值根**，命中 48 处）之后逐条人工判。落进 a 的是 **25 枚类**（主表：1 潜在 + 24 无害）。剩下的按下面六类排除，每类给原文。

### 3.1 `Course.colorIndex` —— 最有代表性的假阳性（"看着最像 a，其实不是"）

命中理由：`data/import/IcsParser.kt:141-144`
```
141:                             dayOfWeek = dow.value,
142:                             periods = periods,
143:                             weeks = weeks.distinct().sorted(),
144:                             colorIndex = (startSection + dow.value) % 8,
```
`:144` 的实参确实把 `:141` 那枚同表参数（`dow.value`）当输入，而 `Course` 有 13 处 copy、其中 `ui/home/HomeScreen.kt:416-419` 恰恰改的就是 `dayOfWeek`（**现状（T135 重钉，基点 `d22a3f8`）**：本行这枚连写的 `:416-419` 与下面那块引文都是 **T133 之前**的盘面 —— 首页那枚文件先被 T133 在 pre 裸 `:415` 之后插 31 行、再被 T133b 在 pre 裸 `:418` 之后插 1 行，同时把 `dayOfWeek = newDayIndex + 1` 改写成 `dayOfWeek = newDayOfWeek`，于是那块四行今天落在裸 `:447-451`、并且长成**五行**（多出来那一行是 `isManualOverride = manualTimeOverride,`，所以这个区间的**行数也漂了**，别按四行去找）⇒ 复算 `awk 'NR>=447 && NR<=451' app/src/main/java/com/buaa/schedule/ui/home/HomeScreen.kt` 逐行读回那五行；下面那块引文本卡一字不改，它是当时的读数、按 (A) 类规矩留着）：
```
416:             val shifted = course.copy(
417:                 dayOfWeek = newDayIndex + 1,
418:                 periods = shiftedPeriods,
419:             )
```
按字面走，这就是 a+b+c（卡片颜色由周/日视图按 `colorIndex` 取调色板）。**它不是 a**，理由是这条关系根本不是不变式，只是导入时的一次性播种：
- 它是用户可以另起一手的独立量：`ui/editor/CourseEditorScreen.kt:500-508` 那排色板的 `onClick = { colorIndex = index; customColor = null }` 就是"自己挑一支"，`:121` 的初值 `initialCourse?.colorIndex ?: 0` 只把它当草稿起点、不是当约束；
- 写侧也按"两枚无关"处理：`data/repository/ScheduleRepository.kt:253-259` 的 `updateCourseGroupAppearance` 在同组片段之间同步的正是 `colorIndex = course.colorIndex.coerceAtLeast(0)`，而各片段的 `dayOfWeek` **刻意不同步**（KDoc `:233` 「不含时间、教师、周次——同组片段本就可能有不同的时间安排」）；
- 同一格的两枚值若真该相等，`:144` 就不会写成 `% 8` 这种"散列取色"而不是"换算"的形状。

⇒ 把课拖到别的星期之后卡片颜色不变，是**用户挑过的颜色该留着**，不是旧值。这一枚如果按 a 收，就是给一条本来正确的行为开一张改错方向的卡。

### 3.2 `Course` 的其余候选：派生全在类体，所以 13 处 copy 一处都不构成 b

`domain/model/Course.kt` 把每一枚派生值都挂在类体上，这是"不可能过期"的那一半形状：
```
67:     val startPeriod: Int get() = periods.minOrNull() ?: 1
69:     val endPeriod: Int get() = periods.maxOrNull() ?: 1
78:     val firstPeriodOrNull: Int? get() = periods.minOrNull()
81:     val lastPeriodOrNull: Int? get() = periods.maxOrNull()
84:     val displayName: String
85:         get() = alias?.trim()?.takeIf { it.isNotEmpty() } ?: name
```
其余 copy 站点逐处读过原文，每处改的都是源字段本身：`ui/course/CourseManagementScreen.kt:183`（`primary.copy(colorIndex = index, customColorArgb = null)`）、`domain/schedule/CourseConstraints.kt:73-80`（归一化，`:79` 连 `credit` 都自己算）、`domain/schedule/ImportPlanner.kt:50`（`course.copy(id = old.id, credit = course.credit ?: old.credit)`）、`:53-57`（并周次 + 学分取已知值）、`data/import/BuaaScheduleParser.kt:136-143`（同格并片段）、`data/repository/ScheduleRepository.kt:215,223`、`ui/home/HomeScreen.kt:428,442`、`ui/home/ConflictWizardDialog.kt:81`、`ui/ScheduleViewModel.kt:507`（`UndoManager.pushCreate(course.copy(id = savedId))`）。**现状（T132 重钉）**：本句里连写在向导文件尾上那枚 `:81` 与本档 §0.4 表 `Course` 那一格是同一枚站点，同为 T131 之前的读数 ⇒ 今天那枚 copy 落在 `:89`，原文 `target.copy(periods = newPeriods, weeks = scopedWeeks)`（T131 把 `weeks` 折进了同一枚 copy，所以那一行的文本也被换过，不是单纯挪位；复算同 §0.4 那一格末尾给的两条 `git show … | awk 'NR==N'`）。同句那两枚落在首页文件上的 `:428`/`:442` 在首页窗口（pre `:819`）之前 ⇒ 未漂；其余各枚（`CourseManagementScreen` / `CourseConstraints` / `ImportPlanner` / `BuaaScheduleParser` / `ScheduleRepository` / `ScheduleViewModel`）不在本三枚文件里 ⇒ 一字未动。**现状（T135 重钉，基点 `d22a3f8`）**：上面那两句"未漂 / 一字未动"里，漂的那一半本轮兑现了 —— T133 `e41d95b` 与 T133b `a3700e9` 的第一扇插行窗口起在首页 pre 裸 `:415` 之后，就落在本句这两枚站点**之前** ⇒ 它们今天分别落在裸 `:460` 与裸 `:479`（逐行读回：`awk 'NR==460' app/src/main/java/com/buaa/schedule/ui/home/HomeScreen.kt` 给「`shifted.copy(weeks = listOf(week)),`」、`awk 'NR==479'` 给「`viewModel.updateCourse(course.copy(periods = newPeriods.sorted(), isManualOverride = true))`」—— 后一枚的文本又被 T133 追加过 `isManualOverride`，与本格那枚向导站点同款，是"文本换了一版"不是单纯挪位）；本句末尾那枚连写在向导文件上的站点（原写 `:81`、T132 重钉成 `:89`）经 T133 落裸 `:104`，窗口与净插都写在 §0.4 表 `Course` 那一格末尾，本卡不在这里重抄第二遍。⚠️ 卡面给的那组"现值应为 `:428` / `:441` / `:459`"按盘面驳回（三枚都对不上 `awk` 读出的原文，也凑不出任何一扇窗口的净插数）。**没漂的那一半仍成立**：上面点名的六枚文件本卡逐条 `git diff --name-only 073d098..HEAD -- app/src/main` 复算 ⇒ 只有首页、向导、统计页三枚 `M` 加两枚新内核 `A`，六枚都不在里面，而两枚新内核里一枚 `Course` 的 copy 站点都没有 ⇒ "13 处"这枚数与"其余各枚一字未动"那半句今天都是量出来的现值。

### 3.3 `ScheduleUiState.conflicts` —— a 成立、b 不存在，而且结构上永远不可能存在

`ui/ScheduleViewModel.kt:478-483`（`val uiState = combine(` 起在 `:463`）
```
478:         ScheduleUiState(
479:             courses = visibleCourses,
480:             semester = semester,
481:             timeSlots = timeSlots,
482:             currentWeek = currentWeek,
483:             conflicts = ConflictDetector.findConflicts(visibleCourses),
```
`:483` 的 `conflicts = ConflictDetector.findConflicts(visibleCourses)` 就是 `:479` 那枚 `courses = visibleCourses` 的函数（与 T94 同一把尺子的形状）。但全仓 **0 处 `ScheduleUiState.copy`**，而它唯一的生产者就是这个 `combine` 块（`:463-487`）——四个源里任何一个一动就整枚重建，`courses` 与 `conflicts` 因此在类型层面不可能各说各话。这一枚最像"下一个 T94"，值得记一句：**将来谁给它加 copy 站点（比如想只改 `currentWeek` 而不重算冲突），必须先回来读这一行。**

### 3.4 `ClassWindow` —— 同源扇出 + 一处**有意**只覆盖一枚

`reminder/ClassProgressScheduler.kt:217-229`（构造）：
```
224:             startMillis = window.begin.atZone(zone).toInstant().toEpochMilli(),
225:             endMillis = window.end.atZone(zone).toInstant().toEpochMilli(),
227:             week = window.week,
228:             dayOfWeek = window.begin.dayOfWeek.value,
```
`:224` 与 `:228` 同取 `window.begin`；`reminder/ReminderScheduler.kt:399-400` 那处构造甚至故意把两枚捏成同一个值（注释在 `:392-393`：「所以 start/end 先都填上课时间，真正开跑时由接收器把 start 改成"此刻"」）。全仓唯一一处 `ClassWindow.copy` 就是执行那句注释：`reminder/ReminderReceiver.kt:48` `window = scheduled.copy(startMillis = now),`，它上面 `:47` 的注释写着「课前这一段进度条量的是"这段等待"，所以起点是此刻而不是上课时间」。
排除理由：`dayOfWeek`/`week` 不是 `startMillis` 这枚**构造参数**的函数（三者各自取函数入参 `window`），且这次覆盖是设计意图。真要按 a 收，就得把 `dayOfWeek` 改成 `Instant.ofEpochMilli(startMillis)` 的函数——那恰好会把这条链改错（"这是哪一天哪一周"必须来自课次，不能来自被改过的进度条起点）。留一句风险：这一枚的口径只靠 `reminder/ReminderReceiver.kt:47` 那行注释维持、无守卫；三枚混排进同一个 Bundle（`reminder/ClassProgressScheduler.kt:88-98`）之后各消费方读哪一枚，是装机才看得出的账（见 §4）。

### 3.5 copy 站点自己带了新值 / 计数器成组但互相独立（其余有 copy 的类）

- `data/calendar/CalendarSyncManager.kt:172-174`（`if (applyBatch(ops)) {` 在 `:171`）：
  ```
  172:                 val upserts = chunk.map { (mapping, occurrence) ->
  173:                     mapping.copy(contentHash = occurrence.contentHash, syncedAt = now)
  ```
  `contentHash` 是 `CalendarSyncEntity`（`data/local/CalendarSyncEntity.kt:22`）的参数，但它的值来自**另一枚类** `Occurrence.contentHash`，不是同表参数；而这处 copy 点的正是它本身 ⇒ 判据里明写的"copy 同时传了新值"，不算 b。同一枚字段在 `data/calendar/CalendarSyncPlanner.kt:33,42` 被读来做"要不要重写事件"的判据，读的就是这份新值。
- `ui/signin/ScanSecondEnginePolicy.kt:197-202` `SecondEngineLedger(fires, misses, lastFireFrame, unusableSeen)`：两处 copy（`:246` `fires = ledger.fires + 1, lastFireFrame = frame`；`:260-265` `misses = …` + `unusableSeen = ledger.unusableSeen || !usable`）改的是四枚互相独立的计数器。它的 KDoc `:186-187` 恰好把这点写成了纪律：「整枚换引用、字段全不可变…必须整枚换 —— 半改的账本会让「第几次发火」和「连续第几次失手」各说各话」。
- `ui/signin/ScanCameraAidPolicy.kt:460-468` `ScanAssistState` 七枚计数器 + `:541-549` 那处 copy **七枚全点齐** ⇒ 无 b。`candidateFrames` 与 `candidateRung` 的关系（同档才 +1、换档归 1）在 `:508-510` 的判据局部量里算，不是从参数表里读别人。
- `widget/WidgetAppearance.kt:30-59` 九枚 + 12 处单字段 copy：派生量全在类体（`:63-64 gridRowTextSizeSp`、`:66-67 cornerDrawableRes`、`:69-70 alphaFraction`）⇒ 与 `Course` 同一半安全形状。`widget/WidgetConfigActivity.kt:346` 那处 `preset.appearance.copy(rowFields = appearance.rowFields)` 是"换预设但保留用户勾的字段"，也是显式点名。
- `widget/WidgetBindingStore.kt:16-26` `WidgetBinding`（`:19-25` 的 `weekOffsetBase` 与 `weekOffset` 是"偏移 + 偏移基准"两枚独立事实，`widget/WidgetCommon.kt:893` 那处 copy 两枚一起点）、`ui/home/WeekGridGeometry.kt:262-266` `ResizeState`（`ui/home/WeekView.kt:1104` 只点 `deltaY`，而"新结束节"是 `:270-274` 的函数 `newEnd(metric)` 不是字段）、`ui/settings/SettingsScreen.kt:654,668` 的 `TimeSlot`、`data/repository/ScheduleRepository.kt:225,395` 的 `ReminderSetting`（换 `courseId` 是拆周次后把提醒搬去新行）、`data/repository/ScheduleRepository.kt:103` 的 `SemesterEntity`（`target.copy(id = 0L)` 让它落成新行）：参数表里都不存在"另一枚参数的函数"。
- `core/designsystem/Theme.kt:167-176` `SemanticColors.copy` 把五个槽位逐一点齐；`warning` 与 `onWarning` 是"成对解出来的配色"而不是彼此的函数（KDoc `core/designsystem/GlassSurface.kt:255-258`「两个字段必须成对用」）。`LiquidGlassMaterial` 的三处 copy（`core/designsystem/DesignTokens.kt:282-296`、`core/designsystem/GlassSurface.kt:74`、`core/designsystem/GlassSegmentedControl.kt:77`）里 `blur/lensHeight/lensAmount` 都由工厂入参 `intensity` 算出（`core/designsystem/LiquidGlass.kt` 的 `pill`/`dialog` 工厂），`copy(useVibrancy = false)` 更是单旗标。

### 3.6 「同源扇出」一族：看着像 a，其实没有一枚参数是另一枚的函数

这十三枚是第二遍扫描里最大的噪声源，也是**编排者复核我有没有筛错时最该看的一节**。共同形状：`X(a = obj.p, b = obj.q, c = f(obj.p))` —— 各枚实参共享一个**外部对象/函数入参**，而那枚外部对象不在参数表上。

| 类 | 定义处 | 共享源 | 原文（赋值处） |
| --- | --- | --- | --- |
| `ScanRejectInfo` | `data/import/ScanReject.kt:80` | 函数入参 `shape` | `:187-197` `textLength = shape.length`、`scheme = shape.scheme`、`paramNames = shape.names`（十枚全取 `shape`） |
| `BackupPreview` | `ui/ScheduleViewModel.kt:1221`（`data class BackupPreview(`） | 局部 `data: BackupData` | `:1247` `courseCount = data.courses.size`、`:1248` `manualCourseCount = data.courses.count { it.isManualOverride }` |
| `ImportHistory` | `domain/model/ImportHistory.kt:8` | 局部 `selection` | `ui/ScheduleViewModel.kt:777` `courseCount = selection.toWrite.size`（`toWrite` 不在 `ImportHistory` 参数表上） |
| `GanttRow` | `core/designsystem/ScheduleCharts.kt:598` | 局部 `coverage` | `ui/stats/StatsScreen.kt:993,1000,1001,1002,1003` 五枚全取 `coverage.*`。**现状（T132 重钉）**：那五枚编号是 T131 之前的读数（StatsScreen 净插 4 行、窗口起点 pre `:298`）⇒ 今天依次是 `:997` `label = coverage.label`、`:1004` `spans = coverage.spans`、`:1005` `weeksUnknown = coverage.weeksUnknown`、`:1006` `finished = coverage.finished`、`:1007` `endsAtWeek = coverage.lastWeek`，五枚仍全取 `coverage.*` ⇒ 本行判档不变 |
| `HeatGridDay` | `core/designsystem/ScheduleCharts.kt:608` | 局部 `day`/`grid` | `ui/stats/StatsScreen.kt:1041,1044` `label = "周${weekdayChar(day.dayOfWeek)}"`、`isEmptiest = grid.freeDayOfWeek == day.dayOfWeek`。**现状（T132 重钉）**：那两枚编号是 T131 之前的读数（StatsScreen 净插 4 行、窗口起点 pre `:298`）⇒ 今天 `:1045` 那一行是 `HeatGridDay(`、`:1048` 那一行是 `isEmptiest = grid.freeDayOfWeek == day.dayOfWeek`；同格那两枚 `ScheduleCharts` 的落点不在本三枚文件里 ⇒ 未漂 |
| `Row`（组件） | `widget/CourseListWidgetService.kt:73` | 局部 `course` | `:164` 起 `teacher = course.teacher`、`periodsText = widgetPeriodsText(course.periods, …)`、`color = courseColor(course).toArgb()`（参数表里只有 `courseId`，没有 `course`） |
| `WidgetRowFields` | `widget/CourseListWidgetService.kt:320` | 局部 `row` | `:218` 起 `dayTag = row.dayTag`… 全取 `row.*` |
| `GlassBakeKey` | `widget/WidgetBackgroundRenderer.kt:539` | 局部 `size`/`radii` | `:571` 起 `bakeWidthPx = size.widthPx`、`radiusX = radii.radiusX` |
| `GlassSourceIdentity` | `widget/WidgetBackgroundRenderer.kt:514` | 局部 `bitmap` | `widget/WidgetWallpaperProbe.kt:295` `widthPx = bitmap.width`、`heightPx = bitmap.height` |
| `ExportResult` | `data/export/IcsExporter.kt:22` | 构建过程中的计数器 | `:47` `ExportResult("", 0, build.skipped)`、`:76` 同形状 |
| `Diff` | `data/calendar/CalendarSyncPlanner.kt:12` | `compute` 的两枚入参 | `:44` `Diff(toInsert, toUpdate, toDelete, unchanged)`，`unchanged` 在 `:40-43` 数出来。**对照：`:18,19` 把真正的兄弟函数放进了类体**——`totalChanged get() = toInsert.size + toUpdate.size + toDelete.size` |
| `DefaultSlot` | `data/import/IcsParser.kt:29` | 局部 `slot: TimeSlot` | `:46` 起 `start = LocalTime.parse(slot.startTime)`、`number = slot.number` |
| `TodayCourseSlot` | `domain/schedule/TodayPlanner.kt:17` | 局部 `window` | `:66-72` `start/end/segment/status` 四枚全取 `window.*`（`course` 是参数，但那四枚都不是它的函数） |

`DecodingAdmission`（主表列作参照行）也在这一类：`blankness` 与 `payloadLength` 都由那颗 `payload` 算出（`ui/signin/ScanDecodingAdmission.kt:115-118`），彼此只有**值域约束**（`:91` 注释），不是函数关系。`LaunchRequests`/`ReminderSetting`/`BackupReminder` 那几处命中是纯噪声：实参是 `null`、`setting.first`、`setting.second` 这类成组值，参数名只是恰好撞名。

### 3.7 一条从这张表里读出来的口径（供编排者定规矩用）

本仓**已经**在按"兄弟参数的函数放类体、要多算一趟的才放参数表"这条线写：`domain/schedule/CourseWeekSpans.kt:77,80`（`isEmpty`/`knownCount`）、`domain/schedule/SemesterStats.kt:65,89,125`、`domain/schedule/WeekFreeGrid.kt:40,41,49,50,51,81,82,86-89`、`domain/schedule/WeekDaySchedule.kt:89,98,127,136`、`data/calendar/CalendarSyncPlanner.kt:18,19`、`domain/schedule/WeeklyLoadTrend.kt:61`、`domain/model/Course.kt:67-85`、`widget/WidgetAppearance.kt:63-70`、`ui/signin/ScanDecodingAdmission.kt:95-96` 全是类体 `get()`；参数表里留下的 `unknownCount`/`freeSlotCount`/`occupiedCellCount` 这类，是因为**要多算一趟**（`rows.count {}`、`loads.sumOf {}`）才不回类体。T94 犯的错正是把一枚"要多算一趟的派生值"放进参数表、却没给它的两个 copy 站点配套重算 ⇒ 参数表上每多一枚这种字段，就是在要求"每个 copy 站点都别忘了算它"。全仓当前这种字段共 **25 枚类**（主表），其中只有 **1 枚**配了 copy 站点。


## 4. 未验到（这台环境验不到的，一律不写成实测）

**环境事实**：没有可用设备。模拟器不在，`adb devices` 只剩用户真机，用户明确说先不要动手机。本卡全程**没有跑 adb、没有跑任何 gradle 任务**（隔壁 T96 在跑全量门禁），所有结论都只来自读源码。

### 4.1 需要装机才能确认的"用户看得出来的错"

1. **§2.1 那枚 `giveUpReason`**：若将来 `ui/signin/ScanRecoveryPolicy.kt:150` 那行早返回被改动，界面上会念错的是哪一句、念错多久被下一帧盖掉 —— 要装机看扫码页那一档文案的位置才读得出来。本卡只证明了"现在念不出错"与"没有静态守卫"。
2. **§3.4 的 `ClassWindow`**：`reminder/ReminderReceiver.kt:48` 把 `startMillis` 覆盖成"此刻"之后，实况卡/超级岛与「下一节课」组件同屏读 `week`/`dayOfWeek`（它们来自课次）与进度条（来自被改过的 `startMillis`）。跨零点、跨教学周边界那一档会不会念出"第 N 周 周一 08:00 起 · 已过 90%"这种自相矛盾的话，未验；`reminder/ClassProgressScheduler.kt:88-98` 那枚 Bundle 把两者并排发给四个进程边界，任一条链自己重新推导日期都可能与另一条对不上。
3. **主表 24 枚"无害"的显示面本身对不对**：本卡判的是"不存在改源的 copy 站点"，不是"这些数字现在显示得对"。统计页/组件上那些 `courseCount`/`freeSlotCount`/`unknownCount` 的实际读数没有装机核对过。
4. **T95 那次修复的装机兑现**：不属于本卡范围，但要点明 —— 本卡对 `PendingImport` 的"已修"判定同样只是读码（`ui/ScheduleViewModel.kt:171` + 形状守卫 `app/src/test/java/com/buaa/schedule/ui/PendingImportConflictGroupTest.kt:185-211`），没有重装重测。

### 4.2 扫描范围边界（没验到的代码，不假装全仓扫完）

- 只扫了 `app/src/main/java`（177 行 `data class`、141 处 copy）。**没扫**：`app/src/debug/java`、`app/src/androidTest/java`、`app/src/test/java`（测试里的 copy 只在 §2.1 当证据看了 4 处，没有全量审）、`benchmark/` 模块、以及构建期生成的代码（Room DAO/`Mappers` 生成物）。
- **按位置构造的实参↔参数对应关系没有程序化解析**（§0.3）。风险主要落在"无害"那一档的计数上：若某处按位置构造藏着一枚 a，它会漏进 §3 而不是主表，**不会**让真回归的 0 变成 1（真回归必须先有 b，而 b 那 71 处是逐行读原文定性的）。
- `docs/*.md` 里若另有对本族的说法，本卡没有交叉核对（按要求不去读非构建输入文档）。

### 4.3 与卡面数字的差异（只记账，不改卡面的判据）

| 项 | 卡面 | 实测 |
| --- | --- | --- |
| 含 `data class` 的文件 | 85 | 85（一致）；声明本身 177 行、去重后 174 个类名 |
| `.copy(` 出现次数 | 126 | **140**（139 行，`ui/home/WeekView.kt:2132` 那类一行两处），另有 1 处隐式 `copy(` 在 `ui/home/WeekGridGeometry.kt:253` |
| T94 引入 `conflictGroupCount` 的哈希 | `189fd0a` | `git log -S conflictGroupCount -- app/src/main/java` 只指到 **`030f8d8`**（"T94①: 导入那四处「N 组」改吃归并后的组数"）。`189fd0a` 是"T94②′"，diff 只碰 `ui/signin/ScanFrameFlowPolicy.kt` 4 行、不含本族字段 |
| T95 修复哈希 | `45e7fc1` | 一致（`45e7fc1 T95①②`），参数表里那枚 `val conflictGroupCount: Int,` 已不在，改为 `ui/ScheduleViewModel.kt:171` 的类体属性 |

## 5. 收单时可用的三条规矩（本卡不动手，只把形状摆出来）

1. **一句话判据**：新加一枚 data class 构造参数时，若它的值 = f(同表另一枚参数)，只有两条正当落点 —— ① 类体 `val x = f(…)`（永远不过期，代价是不进 `equals`/`componentN`），② 留在参数表但给它**每一个** copy 站点配一条"数出现次数"守卫。**没有第三条**：留在参数表而不点名，就是 T94。
2. **① 与 ② 的分工线**（本卡新量到的）：只有当派生值是**同表参数的纯函数**时 ① 才成立。`ui/signin/ScanRecoveryPolicy.kt:160` 那枚是"判到哪一档"这件事的格式化产物，判据本体（`:155` 的 `cycles >= MaxDecodeSuspensionCycles`）搬进类体会造成第二处判据，所以那一枚只能走 ②。
3. **守卫钉得住已知、钉不住未知**：形状守卫能钉"`conflictGroupCount` 不再出现在参数表上"（`app/src/test/java/com/buaa/schedule/ui/PendingImportConflictGroupTest.kt:196` 读形状 + `:200` 逐字节 + `app/src/test/java/com/buaa/schedule/ui/importing/ImportConflictCopyTest.kt:301` 数出现次数 = 3 处读点（`3, occurrences(viewModel, "groupCount = pending.conflictGroupCount")`）），但**下一枚新加的派生字段它一个字都不会说**。所以真正该配的还是"改源之后立刻读派生"那条表驱动用例（`app/src/test/java/com/buaa/schedule/ui/PendingImportConflictGroupTest.kt:175` 的 2 → 1 → 0 三档读数 + `:210` 那处"两处 copy 站点还在"的计数）。给本族的收单问题保持两条：这枚字段的每个拷贝站点被钉了吗？有没有一枚用例真的"改了源再去读它"？


## 6. T103 第二遍账：成对字段的「清点对岸」（评估卡 · 只量不修）

分支 `ai/T103`，基点 `1c6b7bd`。本节是 T103 新增，**没有改动上面任何一节的结论与行号**。
本节全部读数来自 `grep` / 一段一次性的只读 Python 筛选脚本（跑在 `D:/tmp/`，没落进仓库），
**没有跑过任何 gradle 命令、没有碰过任何设备、没有读过 `local.properties`**。

### 6.0 这一遍问的问题与 §0–§5 不是同一枚

§1 的主表判据 (a) 问的是「这枚参数是不是同表另一枚参数的**函数**」。T100 收掉的那对**不满足 (a)**
（`skippedOccurrences` 不是 `f(diff)`，两枚各有各的源，见 §2.2 那句「它不满足 (a)」），
却照样是缺陷。所以第二遍换了问题：**这两枚字段是不是由同一次生产一起写出来的？清点它们的是不是同一处？**
判据形状照 `app/src/test/java/com/buaa/schedule/ui/CalendarSyncDiffClearPairingGuardTest.kt`
的两枚 `@Test`（`:45` `everyDiffClearInViewModelAlsoClearsSkippedOccurrences` 数清点点、
`:77` `thePairedProducerIsTheOnlyWriterAndTheOnlyReaderSitsInsideTheDiffModal` 钉「唯一读者在闸门里」）。

由此本节的「候选」定义：**存在一处写点同时写 A 与 B（同一次生产），又存在一处写/清点只写其中一枚**。
两枚都不满足的成对字段（全仓 4 枚，见 §6.6）不进候选账。

### 6.1 尺子与宇宙（每一格都标用的是哪把尺）

| 量 | 复算命令（原样可粘贴） | 读数 | 用的哪把尺 |
| --- | --- | --- | --- |
| `app/src/main/java` 里 `data class` 声明 | `grep -rnE "data class " app/src/main/java --include='*.kt' \| wc -l` | **177**（与卡面一致；去重后 174 个类名，同 §4.3） | 命中**行**数；一行一枚，故与出现次数同值 |
| `.copy(` 出现**次数** | `grep -rhoE "\.copy\(" app/src/main/java --include='*.kt' \| wc -l` | **140**（与卡面一致） | `grep -o` |
| `.copy(` 命中**行数** | `grep -rnE "\.copy\(" app/src/main/java --include='*.kt' \| wc -l` | **139** | `grep -n`，即 `-c` 那一把；与上一行差的那 1 是 `core/designsystem/ScheduleCharts.kt:1061`（`scheme.primary.copy(alpha = 0.45f), scheme.primary.copy(alpha = 0.06f)` 一行两处） |
| **隐式 `copy(` 盲区（卡面要求自证）** | `grep -rnE "(^\|[^.[:alnum:]_])copy\(" app/src/main/java --include='*.kt'` | **6 行命中**，其中**代码只有 1 处**：`ui/home/WeekGridGeometry.kt:253` 的 `return copy(`（`CourseDragState.advancedBy` 的隐式接收者）；其余 5 行全在注释/KDoc 里（`ui/home/HomeScreen.kt:1338`、`ui/ScheduleViewModel.kt:160,162,165,797`） | 命中行数 ⇒ **盲区不是零，但只有 1 处，且与 T97 §0.1 同一枚**（这一族自 09-26 至今没长新的）。**现状（T132 重钉）**：本格连写在首页文件尾上那枚 `:1338` 是 T131 之前的读数；T131 在 HomeScreen 那一枚文件 1364→1368、净插 4 行，窗口起点是 pre `:819`–`:820` 那两行（「`// viewModelScope + join + partialWeeks 三条账都在那一处，这里不再抄一遍`」与「`onApplyShift = { target, newPeriods -> …`」）⇒ 今天那行注释落在 `:1342`，原文仍以 `copy(fontWeight = ...)` 起头（复算 `git show 5f39a4d^:app/src/main/java/com/buaa/schedule/ui/home/HomeScreen.kt | awk 'NR==1338'` 与 `git show HEAD:app/src/main/java/com/buaa/schedule/ui/home/HomeScreen.kt | awk 'NR==1342'` 两句**同文**）；同格那四枚落在 VM 文件上的 `:160`/`:162`/`:165`/`:797` 与那枚落在 `WeekGridGeometry` 文件上的 `:253` 不在本三枚文件里 ⇒ 一字未动，「只有 1 处」这一判不变 |
| copy 密度 top（按**次数**那把尺） | `grep -rcE "\.copy\(" app/src/main/java --include='*.kt' \| grep -v ':0$' \| sort -t: -k2 -rn \| head -8` | `ui/ScheduleViewModel.kt` 25、`widget/WidgetConfigActivity.kt` 16、`core/designsystem/ScheduleCharts.kt` 13、`ui/home/WeekView.kt` 10、`data/repository/ScheduleRepository.kt` 7、`core/designsystem/liquid/LiquidBottomTabs.kt` 7、`core/designsystem/LiquidGlass.kt` 6、`ui/home/HomeScreen.kt` 5 | **卡面这八格逐格复现**（`grep -c` 与 `grep -o` 在这八个文件上同值：没有一个文件把两处 copy 写在同一行） |
| 把 `.copy(` 与「置空」并起来的站点 | `grep -rnE "\.copy\(" app/src/main/java --include='*.kt' \| grep -cE "= *(null\|0\b\|false\|\"\")"` | **40 行** —— ⚠️ 这一把**噪声占大头**：40 行里 `Color.copy(alpha = 0.xx)` 一档就占 27 行（`ui/home/WeekView.kt` 5、`core/designsystem/ScheduleCharts.kt` 9、`core/designsystem/liquid/*` 6、`core/designsystem/SettingsStack.kt` 3、`widget/WidgetConfigActivity.kt:648`、`ui/home/DayView.kt` 2、`ui/importing/ImportScreen.kt` 2）。同一条筛法换 `grep -o \| wc -l` 也给 **40**（这次两把尺重合，因为命中行里没有一行两处） | 先按行、再按次数 |
| **自有 data class 的 copy 站点宇宙** | 一次性脚本：解析 `data class` 参数表 → 取每处 `copy(` 的**括号配平实参表**（不是同一行）→ 留下实参名命中参数表的站点 → 按手写类型归属表分类（归属表逐枚读原文定，脚本只负责切实参表） | **18 枚类 / 72 处站点**（= T97 §0.4 那张表，逐格核对**没有变化**：T100 只往既有站点里加了实参，没添新站点） | 站点数按 `(文件, 行)` 去重 |
| remembered var（Compose 局部状态槽） | `grep -rcE "\bvar [A-Za-z_][A-Za-z0-9_]* by (remember\|rememberSaveable)" app/src/main/java --include='*.kt' \| grep -v ':0$' \| awk -F: '{s+=\$2} END {print s" 行 / "NR" 文件"}'` | **169 枚 / 28 枚文件** | 命中行数 |
| 其中**至少有一枚被单独置空过**的文件 | 同上一段脚本 + 「按文件列 `NAME = null/0/false/""` 赋值点」那一遍 | **14 枚文件**（`MainActivity.kt`、`ui/editor/CourseEditorScreen.kt` 25 枚、`ui/home/WeekView.kt` 17 枚、`ui/importing/BuaaLoginScreen.kt` 13 枚、`ui/signin/SpocScanScreen.kt` 13 枚、`ui/settings/SettingsScreen.kt` 26 枚、其余 8 枚见 §6.7，**14 枚本遍已全判**） | 文件数 |

**这两遍筛法各漏了什么（不许读者替我补）**：

- 「自有类 18 枚」那一格吃的是**类型归属表**，而归属表是手写的 ⇒ 如果谁新加了一枚自有 data class 并给它开了
  `copy` 站点，而我没把它列进归属表，这一遍就漏。压住这件事的是**站点总数**：脚本按「实参名命中某枚自有
  `data class` 参数表」数出来 130 处，其中 58 处落在 `alpha` / `fontWeight` / `copyOn…` 这类**平台类型也有的同名槽位**
  （表里那一枚 `TintPlate` 被算到 57 处就是这个原因，`Color.copy(alpha=)` 与 `TintPlate.alpha` 撞名），
  扣掉之后落进 18 枚自有类的是 72 处 —— **这个差是分类出来的，不是程序化证明出来的**。
- 「同一枚字段在别处与另一枚一起被写」这一格吃的是 `copy(` 的实参表，**不吃整枚重建**
  （`_importMessage.value = AppMessage(...)` 那 30 处是重建，两枚字段必然一起写，本来就不构成漏清）。
- remembered var 那一遍**没有**做「同一处块里一起写、别处只清一枚」的程序化配对（那是 data class 那一遍做的事），
  只列了每枚 var 的写点与清点，配对是逐枚读出来的 ⇒ 剩下 8 枚文件里可能还藏着同族，见 §6.7。
- **按位置构造的实参↔参数对应关系仍未程序化解析**（§0.3 那条限制对本节同样成立）。

### 6.2 候选账（本遍判了 14 枚）

档位分布（**T103 当时判的**）：**真漏清 1 枚 · 已被钉住 3 枚 · 结构不可能 9 枚 · 越界形状 1 枚**（共 14 枚进表）。
**T106 之后的账**：#7 `(message, permissionPermanentlyDenied)` 从"结构不可能"搬到**真漏清（已修）** ⇒ 本表按现状读是
**真漏清 2 枚（#1 与 #7，后者已由 T106 收掉）· 已被钉住 3 枚 · 结构不可能 8 枚 · 越界形状 1 枚**；
#1 `saving`/`saveError` 那枚仍挂在 §6.8⑤ 等排卡。旧分布不抹，因为它就是 T103 那一遍的读数。
⚠️ 那 9 枚"结构不可能"里有 2 枚（#10 #12）**按 §6.0 的定义根本不该进候选账** —— 它们成对写、
却一处"只清一半"都没有；我把它们留在表上是因为卡面的起手式第二条直接要求读每一枚 data class 的
成对字段表，但**档位那一格对它们是硬套的**，口径问题见 §6.9 驳回②。

| # | 字段对（A，B） | 状态类 | 生产者（把它们绑在一起的证据） | 写点/清点数 | 判定 | 证据锚点 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `saving` ， `saveError` | `ui/editor/CourseEditorScreen.kt` 的两枚 remembered var（不是 data class 字段，见 §6.3） | `performSave()` 那一句 `saving = true` + 下一句 `saveError = null` 是同一次「开始一件会写库的操作」 | 写点 7 处 / 清点 3 处 | **真漏清** | `CourseEditorScreen.kt:231-232`、`:245`、`:247`、`:614`、`:618`、`:620`、读点 `:282`+`:295` |
| 2 | `diff` ， `skippedOccurrences` | `CalendarSyncUiState`（`ui/ScheduleViewModel.kt:93`） | `data/calendar/CalendarSyncManager.kt:98` `suspend fun computeDiff(calendarId: Long): Pair<CalendarSyncPlanner.Diff, Int>?` 一次返回同一对 | 写点 4 处 / 清点 3 处，**三处全成对** | **已被钉住** | `app/src/test/java/com/buaa/schedule/ui/CalendarSyncDiffClearPairingGuardTest.kt:45`+`:77`（细节见 §2.2，本节不重开） |
| 3 | `consecutiveFailures`+`suspensionCycles` ， `giveUpReason` | `ScanDecoderHealth`（`ui/signin/ScanRecoveryPolicy.kt`） | 同一枚字符串由那两枚计数器拼出来（§2.1 的 (a)） | 3 处 `health.copy(`，其中 2 处不重算 | **已被钉住** | `app/src/test/java/com/buaa/schedule/ui/signin/ScanGiveUpReasonDerivationGuardTest.kt` 五枚 `@Test`：`:61` `theOnlyEarlyReturnGuardIsStillVerbatimAndStillFirstStatement`、`:105` `productionAssignsTheDerivedFieldAtExactlyOneSite`、`:140` `theCopySitesThatChangeTheSourcesWithoutRecomputingAreStillExactlyTwo`、`:182` `theResetPathsStillBuildAFreshInstanceInsteadOfCopying`、`:204` `theCountersScanOnlyTheMainKernelFile`（§2.1 当年说「没有任何静态守卫钉着」，T98 之后这句已经过期，本节按现状改判） |
| 4 | `conflicts` ， `excludedKeys`/`addedCount`/`changedCount`/`keptCount` | `PendingImport`（`ui/ScheduleViewModel.kt:136`） | 五枚全取 `resolveImportSelection(...)` 交回的同一枚 `ImportSelection` + 同一次 `findConflicts` | 2 处 copy（`:812`、`:833`），两处**五枚全点齐** | **已被钉住** | `app/src/test/java/com/buaa/schedule/ui/PendingImportConflictGroupTest.kt:207` 那句断言的消息就是「两处逐条勾选的 copy 站点都还在按子集重算 conflicts」 |
| 5 | `fetchState` ， `fetchWeek`/`fetchTotal` | `ui/importing/BuaaLoginScreen.kt` 三枚 remembered var | `onProgress = { week, total -> fetchWeek = week; fetchTotal = total; fetchState = "正在获取课表：第 $week/$total 周..." }` 一处写三枚 | 写点 3 组 / 清点 6 处（`fetchState = null` 就有 4 处，`fetchWeek = 0`/`fetchTotal = 0` 各 1 处） | **结构不可能** | 读点 `BuaaLoginScreen.kt:344` `val fetchFraction = if (fetchTotal > 0 && fetchStateText != null) {` ⇒ 两枚计数器唯一的读者恒在 `fetchState != null` 驱动的括号里 |
| 6 | `colorMode` ， `backgroundColor` | `WidgetAppearance`（`widget/WidgetAppearance.kt:30`） | 换预设那一档整枚搬过来：`widget/WidgetConfigActivity.kt:346` `preset.appearance.copy(rowFields = appearance.rowFields)` ⇒ 配色来源与那支自定义色出自同一枚预设、一起落 | 12 处 `appearance.copy(`，其中 `:380` `appearance.copy(colorMode = it)` 与 `:404`/`:417` `appearance.copy(backgroundColor = argb)` **各改一枚** | **结构不可能** | 两枚读点都自带闸门：`widget/WidgetConfigActivity.kt:393` `if (appearance.colorMode == WidgetAppearance.COLOR_MODE_CUSTOM) {`（自定义色那一行只在这个分支里组合）与 `:624` `val baseColor = if (appearance.colorMode == WidgetAppearance.COLOR_MODE_SYSTEM) {`（SYSTEM 那一支也只把 `backgroundColor` 当 `:626` `?: appearance.backgroundColor` 的**回退**读，自定义色那一行整块不在 else 之外组合） |
| 7 | `message` ， `permissionPermanentlyDenied` | `CalendarSyncUiState` | `ui/ScheduleViewModel.kt:1496` 那次 copy 同时写 `permissionPermanentlyDenied = !canAskAgain` 与 `message = AppMessage(... "日历权限已被永久拒绝，请到系统设置手动开启")` | **改前**：成对写 1 处 + **两处分头清**（`:1389` 只清 `message`、`:1508` `it.copy(permissionPermanentlyDenied = false)` 只清旗标）。**现状（T106）**：`:1389` 已连旗标一起撤（`it.copy(syncing = true, message = null, permissionPermanentlyDenied = false, diff = null, skippedOccurrences = 0)`），清点仍 2 处，`:1508` 那处保持只清旗标（它长在 launcher「全部授予」那一档里，那条档上句子本来就该留）。⚠️ 那两句「清点仍 2 处 / `:1508` 只清旗标」是 **T106 那一遍的读数**，按本档规矩留着（§6.8③ 与 §6.4-B 用的就是这种"旧读数 + 现状"两段并陈的形状）。**现状（T110，本轮 T111 复算）**：清点 **3 处** = **成对清 2 + 单清 1** —— `:1389` 同步起手、`:1481` 移除链起手（T110 第二条链，`_calendarSync.update { it.copy(syncing = true, message = null, permissionPermanentlyDenied = false) }`，与 `:1389` 同一枚仪式、且不经那个入口）、`:1508` 那处**仍**只清旗标。复算两把各数一枚字段：`grep -n "message = null" app/src/main/java/com/buaa/schedule/ui/ScheduleViewModel.kt` ⇒ **2 行**（`:1389`、`:1481`）；`grep -n "permissionPermanentlyDenied = false" app/src/main/java/com/buaa/schedule/ui/ScheduleViewModel.kt` ⇒ **3 行**（再加 `:1508`）⇒ 2 + 1 = **3 处清点**，其中成对清 2 处。 | **真漏清 —— T106 已修**（T103 当时判的"结构不可能"有半边是错的，两个方向的账见 §6.4-B） | 读点 `ui/settings/SettingsScreen.kt:1677` `if (calendarSync.permissionPermanentlyDenied) {`，它整块长在 `:1664` `item(key = "status", visible = calendarSync.message != null) {` + `:1665` `calendarSync.message?.let {` 里面 ⇒ ⚠️ 这道闸**只在 `message == null` 那一段挡得住**，而 `:1429-1439` 那句「同步完成：新增 …」恰好又把它填非空。守卫：`app/src/test/java/com/buaa/schedule/ui/CalendarSyncTargetPairingGuardTest.kt` 的 ③ 那一族三枚 `@Test`（写侧枚数 / 两道闸作第二层 / 全部入口都在权限闸里）。⚠️ 那句「三枚」是 **T106 那一遍的账**，留着；**现状（T110 之后，本轮 T111 复算）：③ 那一族四枚** —— 原三枚 + 第四枚 `everyRouteIntoTheRemoveChainStandsInsideTheSameGateAndClearsTheFlagPaired`（把上面那句"成对清 2 处"里的第二条链的写侧枚数与唯一可达路径一起钉）。整枚文件的复算命令：`grep -cE '^[[:space:]]*@Test' app/src/test/java/com/buaa/schedule/ui/CalendarSyncTargetPairingGuardTest.kt` ⇒ **10**（T105 立时 6、T106 折回 7、T110 添到 10）；③ 那一族按**方法名**点名而不按行号 —— 行号会跟着 KDoc 添行漂，方法名不会。 |
| 8 | `menuFor` ， `lastMenu` | `ui/home/WeekView.kt` 两枚 remembered var | `:558` `if (menuFor != null) lastMenu = menuFor` —— 一次写两枚（**故意**留一份给退场动画） | 成对写点 1 处；`menuFor = null` **4 处**（`:876`、`:1001`、`:1175`、`:1218`）一处都不跟着清 `lastMenu` | **结构不可能** | `lastMenu` 全仓唯一读者 `WeekView.kt:1173` `lastMenu?.let { menu ->`，它挂的浮层 `visible` 由 `menuFor` 关掉：`:1217` `visible = menuFor != null,` ⇒ 清一半正是设计意图（收场期间画锚住的那一份），不是残值 |
| 9 | `text` ， `isError`/`isSuccess` | `AppMessage`（`ui/ScheduleViewModel.kt:130`） | 每一句提示的「文案」与「染色」出自同一个构造 | 全仓 `AppMessage(` **45 处**构造、`.copy(` **0 处** | **结构不可能** | 复算：`grep -rn "AppMessage(" app/src/main/java --include='*.kt' \| wc -l` ⇒ 45；`grep -rnE "AppMessage\([^)]*\)\.copy\(\|message\.copy\(" app/src/main/java --include='*.kt' \| wc -l` ⇒ **0**。三枚字段被同一枚对象包着 ⇒ `copy` 站点根本不存在，一半都漏不掉 |
| 10 | `targetId` ， `targetName` | `CalendarSyncUiState` | `:1462` `it.copy(targetId = calendarId, targetName = displayName, showPicker = false)` 与 `:1528-1529` 那个 `if (targetGone)` 双写 | 成对写点 3 处（含初值 `:1373-1374`）/ **分头清点 0 处**；复算 `grep -n "targetId = \|targetName = " app/src/main/java/com/buaa/schedule/ui/ScheduleViewModel.kt` ⇒ **6 行 = 3 组**（`:1373`+`:1374` 初值、`:1462`、`:1528`+`:1529`），本轮（T111）重数一字未动 | **结构不可能**（本遍判据下**根本没进候选**：见 §6.9 的档位口径驳回） | 唯一读者 `ui/settings/SettingsScreen.kt:1623` `summary = calendarSync.targetName ?: "未选择",` —— 它**不在**任何 `targetId` 驱动的块里 ⇒ 今天不漏，将来加一处「只把 `targetId` 打回 -1L」的站点就会漏，且**无守卫**（登记进 §6.8①）。⚠️ 最后那两句「无守卫 / 登记进 §6.8①」是 **T103 那一遍的账**，留着；**「无守卫」自 T105 起就已过期，本档按当时卡面授权范围没改，本轮订正**：守卫就长在 `app/src/test/java/com/buaa/schedule/ui/CalendarSyncTargetPairingGuardTest.kt` 的 **① 那一族**（T105 立，钉"两枚各赋值 3 处、逐处成对、界面读者 1 枚且不在 `targetId` 驱动的块里"），**T110 又给这一族加了偏好那一头** —— ① 的 `theDeadTargetIsDroppedFromPreferencesAsAPairOfKeysAndNotOnlyFromMemory`（`:1538` 那一档两枚 key **成对** `remove`、且 `loaded.isNotEmpty()` 才动手）与 ①-b 的 `theCalendarListCacheHasExactlyOneResetSiteAndItIsThePickerOpening`。偏好那头的复算：`grep -n "calendar_sync_target_id\|calendar_sync_target_name" app/src/main/java/com/buaa/schedule/ui/ScheduleViewModel.kt` ⇒ **6 行**，每枚 key 各 3 次（读 `:1373`/`:1374`、写 `:1459`/`:1460`、撤 `:1539`/`:1540`）⇒ **撤也是成对撤**。完整账在 §6.8① 那句「现状」，本节不重开 |
| 11 | `calendars` ， `calendarsLoaded` | `CalendarSyncUiState` | 同一次 `ensureCalendarsLoaded()`：成功那一档 `:1526-1527` `calendars = loaded,` + `calendarsLoaded = true,` 成对写 | 成对写点 1 处；**分头写点 1 处** `ui/ScheduleViewModel.kt:1516` 那一档只落 `calendarsLoaded = true,`（两处行号本轮 T111 逐枚 `awk 'NR==…'` 回读，在 `6d6d121` 上仍一字未动） | **结构不可能**（但那道闸**不在本族的位置**，见 §6.4-A）；⚠️ 判档不变，**理由本轮补一句 —— 见最后一格末「现状」那半段** | 那一档长在 `:1513-1515` `val loaded = suspendCatching { calendarSyncManager.queryCalendars() }.getOrElse { _calendarSync.update { it.copy(` 里；`queryCalendars()` 自己把异常吞成空列表（`data/calendar/CalendarSyncManager.kt:71` `runCatching {` + `:90` `.onFailure { Log.w(TAG, "读取日历列表失败", it) }` + `:91` `return result`）⇒ `getOrElse` 走不到。**现状（T110 之后，本轮 T111 复算）**：那三行**一字未动**（复算 `sed -n '71p;90p;91p' app/src/main/java/com/buaa/schedule/data/calendar/CalendarSyncManager.kt` 三行原样），⇒ 这道**外来**闸门仍在原地、本格判档照旧。要补的那句理由是：**上面靠"`:1512` 再也不同步重试"撑着的那半条后果，开窗那一路 T110 之后已经自愈**（`calendarsLoaded` 现在有 **1 枚**复位站点 `:1449` 开窗那一档连带写；复算 `grep -rn "calendarsLoaded = false" app/src/main/java` ⇒ 1 行，改前那遍是 0 行），**同步那一路仍吃缓存**（`:1391` 那一档今天不带复位，是有意取舍）—— 账与代价已经写在 §6.8② 那句「现状」与 §6.4-A 的「A 的现状」段里，本格只对齐、不重复大段 |
| 12 | `boundCamera` ， `analysisUseCase` | `ui/signin/SpocScanScreen.kt` 两枚 remembered var | 绑定成功那档 `:381-382` `boundCamera = camera` + `analysisUseCase = analysis` 成对写 | 写点 2 组 / 清点 3 组，**三组全是连号两行**（`:330-331`、`:409-410`、`:448-449`） | **结构不可能**（同 10：无分头站点，不进候选） | 复算 `grep -n "boundCamera = null\|analysisUseCase = null" app/src/main/java/com/buaa/schedule/ui/signin/SpocScanScreen.kt` ⇒ 3 行 + 3 行，行号相邻 |
| 13 | `cameraError` ， `cameraProviderMissing` | `ui/signin/SpocScanScreen.kt` 两枚 remembered var | **不是同一次生产** —— `:155` 的注释就写着「它和 cameraError 是两件事」，两枚各有独立生产者（provider 效果 vs 绑定/相册失败） | 各自 2/6 处写点，**互不点名** | **越界形状**：本卡三档给不了它（见 §6.6） | 读侧是一条**优先级梯**而不是闸门：`ui/signin/ScanUiStatus.kt:87` `cameraError != null ->` 排在 `:93` `cameraProviderMissing ->` 之前，而 `:81-84` 那段注释正是拿「两枚会不会同时成立」在解释这个排序 |
| 14 | `showPrivacyDialog` ， `privacyConsentAt` | `ui/settings/SettingsScreen.kt` 两枚 remembered var（声明 `:209`/`:210`） | 撤回同意那一档两枚**连号一起写**：`:1848 privacyConsentAt = 0L` + `:1849 showPrivacyDialog = false` | 成对写点 1 处；**分头清点 2 处** `:1817` `onDismissRequest = { showPrivacyDialog = false }` 与 `:1853` `TextButton(onClick = { showPrivacyDialog = false })` 都只关窗、不归零同意时间 | **结构不可能** | `privacyConsentAt` 在界面侧**只有一个读者**，而那个读者本身就是闸门：`:1814` `ModalTransition(payload = if (showPrivacyDialog) privacyConsentAt else null) { consentAt, modal ->`。复算唯一读者：`grep -rn "privacyConsentAt" app/src/main/java --include='*.kt' \| grep -v SettingsScreen` ⇒ 只有 `core/FirstRun.kt:29` `fun privacyConsentAt(context: Context): Long =`（读偏好，不是读这枚 var） |

**#14 值得单记一句**：它是本仓**已经知道自己有这个病**的一枚 —— `:1812-1813` 那两行注释写的就是
「payload 用同意时间而不是布尔：点『撤回同意』在关窗的同一刻把 privacyConsentAt 归零，
光靠 open = showPrivacyDialog 会让正在淡出的正文当场翻成『未同意』」。它选 `payload =` 版而不是
`open =` 版，正是为了不让收场那几帧读到**已经翻面**的那一枚。本节按 T100 那枚守卫的形状数一遍：
这一对**没有**守卫文件（`grep -rl "成对" app/src/test --include='*.kt'` 那 14 枚里没有它），
钉住它的只有那段注释 + `:1814` 那一行的写法。→ 登记 §6.8⑥。

### 6.3 真漏清那一档（本遍 1 枚，展开）

**#1 `CourseEditorScreen` 的 `saving` / `saveError`**

- **这两枚为什么是一对**：底栏那一格的「在忙」与「上一趟为什么没成」是同一枚操作状态的两半。
  生产者的证据是 `performSave()` 里那两行**连号**：
  ```
  231:            saving = true
  232:            saveError = null
  ```
  收尾那两行也连号（`245: saveError = "保存失败，请重试；草稿已保留"` / `247: saving = false`）。
  也就是说：这条链自己承认「开始一项写库操作 = 立旗标 + 收回上一句错」，两枚同生同灭。
- **分头的那一处**：删除这条链**立了旗标却没收回句子**：
  ```
  613:                        scope.launch {
  614:                            saving = true
  615:                            if (onDelete(target)) {
  616:                                onBack()
  617:                            } else {
  618:                                saveError = "删除失败，请重试"
  619:                            }
  620:                            saving = false
  ```
  `:614` 与 `:231` 是同一件事（开始一项写库操作），却少了 `:232` 那一行。**这就是 T100 的形状换了载体**：
  不是 `copy()` 少点一枚实参，而是同一段仪式在两处操作里被复制成两半。
- **读点没有闸门**（这是它区别于 §6.4 那些「结构不可能」的地方）：
  ```
  282:                    if (saveError != null) {
  …
  295:                                text = saveError ?: "",
  ```
  这一段长在底栏的 `Column` 里，**不**套在任何 `if (saving)` / `ModalTransition(payload = …)` 之内；
  `saving` 只影响同一列下面那颗按钮的字（`:310` `Text(if (saving) "保存中..." else "保存")`）。
  ⇒ 「清了 A，B 仍被读到」在结构上是开的。
- **用户怎么走到那一步**（全程可点，无需任何异常时序）：
  1. 首页长按/点击一门课 → 进编辑器（`ui/home/HomeScreen.kt` 的 `onCourseClick` → 编辑器路由）；
  2. 改一下周次或时间，点底栏「保存」→ `performSave()` 里 `viewModel.saveCourse(...)` 走失败支
     （`ui/ScheduleViewModel.kt:548` `_importMessage.value = AppMessage("更新课程失败：${e.message}", isError = true)`
     那一族，编辑器这条是 `:245`）→ 底栏出现红条**「保存失败，请重试；草稿已保留」**；
  3. 用户不重试保存，改点同一屏那颗「删除课程」（`:568` `onClick = { showDeleteDialog = true }`，
     `:570` `enabled = !saving` —— 此刻 `saving` 已在 `:247` 落回 false，所以这颗按钮**可点**）→ 确认；
  4. `:614 saving = true` **不清 `saveError`** → `onDelete(target)` 在跑的那一段里，红条仍写着
     「保存失败，请重试；**草稿已保留**」；
  5. 删除成功 → `:616 onBack()` → 退场动画期间这一帧仍在组合，那句「草稿已保留」已经是**假话**
     （课连同排课与课前提醒都被 `:605` 那句确认文案点名删掉了）。
- **错的样子**：编辑器底栏一句话同时说两件互相矛盾的事 —— 门面上的按钮说「保存中…」（`:310` 读的是被
  删除链借用的 `saving`），红条说「保存失败，请重试；草稿已保留」（上一次保存的残值），而用户刚刚做的
  是**删除**、且已经成功了。两半都念错，且没有一枚测试走过这条路（`app/src/test/` 里 `saveError` 只出现在
  这一枚文件的读点与写点，复算：`grep -rln "saveError" app/src/test --include='*.kt'` ⇒ **0 行**）。
- **这个数怎么数出来的**：`grep -n "saveError" app/src/main/java/com/buaa/schedule/ui/editor/CourseEditorScreen.kt`
  ⇒ 6 行（`:130` 声明、`:232` 清、`:245` 写、`:282` 判、`:295` 读、`:618` 写）；
  `grep -n "saving = " app/src/main/java/com/buaa/schedule/ui/editor/CourseEditorScreen.kt` ⇒ 4 行
  （`:231`、`:247`、`:614`、`:620`），其中**只有** `:231` 旁边跟着那句 `saveError = null`。

**判零依据（为什么全仓只有这一枚落进这一档）**：⚠️ 这句"**只有**一枚"**已被 T106 证否** —— #7 也是真漏清，
本节当时判它零枚靠的是把 `:331` 那道短路读成护栏（账见 §6.4-B「这一带的两处错」）。下面这段按原文留着，
读的时候要把它当 **T103 当时的账**，不是现状。
另外 6 枚「成对写 + 分头清」的候选，其**读侧全部落在闸门里**
（#5 #6 #7 #8 #11 #14 六枚：#5 #6 #8 #11 #14 的闸门原文点在 §6.2 各行最后一格，#7 两向各一道、在 §6.4-B 展开
—— #7 那两道**其中一道当场被证不成立**，其余五枚本节复核过仍在）；
再加 #9 那一枚属**载体级别**的免疫（`AppMessage` 全仓 45 处构造、0 处 `copy`，一半都漏不掉），
#10 #12 两枚压根没有分头清点的站点；而本仓真正**没有闸门**的读点只有两类载体 —— 编辑器/登录页这类「底栏常驻一行」，
其中只有编辑器这一枚同时满足「两枚字段由同一仪式成对写」与「清点仪式被复制成两半」。
`ui/importing/BuaaLoginScreen.kt` 那一族最接近（`:119`/`:131`/`:153` 三处只清 `fetchState`），
但它那一行进度条的读点被 `:344` 的 `&& fetchStateText != null` 挡住了 —— 这正是本节要的差别，
所以它判结构不可能而不是真漏清，不是"没找到"。

### 6.4 「结构不可能」那几枚的机制（卡面要求：不许用"应该没事"）

**A. #11 `(calendars, calendarsLoaded)`：闸门不在字段对上，而在被调方里。**
`ui/ScheduleViewModel.kt:1519` 那一档确实写了 `calendarsLoaded = true,` 而没有 `calendars = …`，
读点也确实看得见：`:1394` `current.calendars.isEmpty() -> _calendarSync.update {` 会据此落一句
`NO_WRITABLE_CALENDAR_MESSAGE`（`:116` 定义，内容是「没有检索到可写的日历…」），而那一档的真相是「查询失败」。
但那条链的第一环走不到：`:1513` `val loaded = suspendCatching { calendarSyncManager.queryCalendars() }`
的被调方 `data/calendar/CalendarSyncManager.kt:69-91` 把整段 contentResolver 查询包进
`runCatching { … }`，`:90` `.onFailure { Log.w(TAG, "读取日历列表失败", it) }` 只留日志，
`:91` `return result` 交回的仍然是列表 ⇒ `queryCalendars()` 正常返回时**永不抛**，`getOrElse` 那一档在
今天不产生任何用户可读的状态。**所以它判「结构不可能」靠的是别人的 `runCatching`，不是字段对自身的性质**——
这句话必须记着：谁把 `CalendarSyncManager.kt:71` 那个 `runCatching` 拆掉（或换成 `Result` 往外抛），
#11 立刻从「结构不可能」搬到「真漏清」，且错的是那句"没有检索到可写的日历"（它会把权限没给说成设备没日历，
并且 `:1512` `if (_calendarSync.value.calendarsLoaded) return` 会让这一页**再也不同步重试**）。
登记进 §6.8②。

**A 的现状（T110 收了缓存那一半，吞异常那一半没动）**：`calendarsLoaded` 现在有一枚复位站点 ——
`:1449` 开窗那一档连带写 `calendarsLoaded = false`（改前那格记的"0 行复位 ⇒ 进程寿命缓存"留着，
它是那一遍的读数），于是上面那句"`:1512` 会让这一页**再也不同步重试**"在**开窗那一路**已不复成立：
每次开窗都重查一遍，选择器不再列陈名单，`:1523` 的 `targetGone` 也随之每次跑一趟，并配上了撤偏好那档
（`:1538`，两枚 key 成对撤 + `loaded.isNotEmpty()` 才动手）。而 `:71` / `:90` / `:91` 那颗
`runCatching` 的本体一字未动 ⇒ `getOrElse` 那一档今天仍然几乎走不到、"查询失败"仍然被洗成
"这台设备没有日历"，**#11 判「结构不可能」用的那道外来闸门仍在原地**，本节上面那句"这句话必须记着"
继续有效。同步入口 `:1391` 也仍然吃缓存（取舍与代价见 §6.8② 那一格「现状」）。

**B. #7 `(message, permissionPermanentlyDenied)`：改前判"两个方向各有一道闸"—— 那半边是错的，T106 已修。**

**改前的形状（T103 那一遍的原文，行号按 `4b1c4a4`，照抄不抹）**：
- 清 `message` 而留旗标（`ui/ScheduleViewModel.kt:1389` 那一档当时写 `it.copy(syncing = true, message = null, diff = null, skippedOccurrences = 0)`
  确实没点 `permissionPermanentlyDenied`）⇒ 本节当时写「**看不见**：那枚旗标全仓只有一个读者
  `ui/settings/SettingsScreen.kt:1677` `if (calendarSync.permissionPermanentlyDenied) {`，
  而它整块长在 `:1664` `item(key = "status", visible = calendarSync.message != null)` 与
  `:1665` `calendarSync.message?.let {` 两层之内 —— 句子一撤，那颗「去系统设置开启日历权限」的按钮跟着没了。
  这与 §2.2 里 `ModalTransition(payload = calendarSync.diff)` 那道闸同一形状，只是驱动它的是 `message`」。
- 清旗标而留句子（`:1508` `onCalendarPermissionGranted` 整颗函数就是 `it.copy(permissionPermanentlyDenied = false)`）⇒
  「**走不到**：`onCalendarPermissionGranted()` 全仓唯一调用点是 `ui/settings/SettingsScreen.kt:317`，
  它在 `:316` `if (grants.isNotEmpty() && grants.values.all { it })` 里；…… `:331`
  `if (viewModel.hasCalendarPermission())` 要么直接放行（**根本不启动 launcher，也就到不了 `:317`**），
  要么 launcher 直接回全 false 再走 `:325`」。

**这一带的两处错，逐条对上读数**：
1. **第一档「看不见」把闸的有效期当成了永久**。闸 B 的判据是 `message != null`，它只买"`message == null` 那一段"。
   而 `:1389` 撤完句子之后，同一条 `startCalendarSync` 链上有**三枚**站点会把 `message` 重新写非空：
   `:1394-1395`（`NO_WRITABLE_CALENDAR_MESSAGE`）、`:1401-1403`（「暂无可同步的课表，请先导入课程并设置学期」）、
   以及最要命的 `:1429-1439`（`confirmCalendarSync` 落「同步完成：新增 …」/ 两句失败文案）。
   ⇒ 句子一非空，`:1664` 那格重新可见，`:1677` 读的就是那枚**旧**旗标。它与 §2.2 的 `diff` 闸**不同一形状**：
   `ModalTransition(payload = calendarSync.diff)` 的 payload 与它驱动的块出自同一次重建，而 `message` 与旗标不是。
2. **第二档「走不到」把拦路虎当成了护栏**。`:331` 那道 `hasCalendarPermission()` 短路正是让
   `onCalendarPermissionGranted()`（当时旗标**唯一**的复位入口）**永不被调**的那件事：
   用户按"永久拒绝"提示去系统设置里手动开好日历权限 → 回来点「同步到系统日历」（`SettingsScreen.kt:1641`
   `onClick = { startCalendarSync() }` → `:341` `fun startCalendarSync() = withCalendarPermission { … }`）→
   `:331` 判"已有权限" ⇒ 直接 `action()`，`:335` 那枚 `calendarPermissionLauncher.launch(` 根本不启动 →
   `:319` `(action ?: viewModel::startCalendarSync).invoke()` 那条分支走不到 ⇒ 旗标常驻。
   ⇒ 全程不需要任何异常时序，**每次都成立**。

**现状（T106 收的）**：`ui/ScheduleViewModel.kt:1392` 起手那次 copy 改成
`it.copy(syncing = true, message = null, permissionPermanentlyDenied = false, diff = null, skippedOccurrences = 0)`
—— 与它本来就成对清的 `diff`/`skippedOccurrences`（T100①）并成**一次清两对**，形状同 T100（`diff`/`skipped`）与
T104（`saving`/`saveError`）。这是**同行改写**：`ScheduleViewModel.kt` 与 `SettingsScreen.kt` 都没增删行
（1683 / 2154 行不变）⇒ 本档那批 `X.kt:NNN` 锚点一处不漂。
选它的前提是「**进到 `startCalendarSync()` 时权限必然已到手**」，这条对**每一个入口**都成立（`grep -rn "startCalendarSync" app/src/main/java`
⇒ 5 处提及，逐条：`:1383` 定义、`SettingsScreen.kt:319` 在"全部授予"那一档内、`:341` 在闸的 `action` 里、
`:1641` 调的是 `:341` 那枚本地包装、`ScheduleViewModel.kt:1466` 在 `selectCalendarTarget`（`:1460`）体内 ——
它是唯一一枚不过 `withCalendarPermission` 的调用点，但它的宿主只能被 `:1625`
`onClick = { withCalendarPermission { viewModel.openCalendarPicker() } }` 或 `:1397`（长在 `startCalendarSync` 体内）
打开的选择器那一层触发，而 `showPicker = true` 全仓就这两枚写点）⇒ 旗标被清的那一刻权限是真的，
这句话不是假话。弃另一条（把 `:1677` 的读侧改成「旗标为真**且**当前确实没权限」）的理由：它让那颗按钮的可见性
从此**每次重组都查一次实时权限**，且界面同时信两把尺子（旗标仍是脏的，只是不念）——
本仓对派生字段的规矩是"值 = f(同表参数) 才许做成派生"（§6.0），这枚旗标的值是"系统权限 + 上一次申请结果"的函数，
不符合；一枚起手成对清覆盖 `startCalendarSync` 全部下游落点，比在读侧补判据更省。
钉住它的守卫：`app/src/test/java/com/buaa/schedule/ui/CalendarSyncTargetPairingGuardTest.kt` ③ 那一族三枚 `@Test`
—— `thePermissionFlagAndTheMessageAreWrittenTogetherOnceAndClearedPairedAtTheSyncEntry`（旗标赋值 3 处 /
`message = null` 1 处且必须带旗标）、`thoseTwoGatesAreNowTheSecondLayerBehindThePairedClearAtTheSyncEntry`
（两道闸降级为第二层保险，仍两头钉）、`everyRouteIntoTheSyncEntryStandsInsideAPermissionGate`
（上面那 5 枚入口逐条钉，含 `precedingFunHead` 认宿主）。

**T110 又收了第二条链（同形状、不同入口）**：「移除已同步的日程」那条链自己不进 `startCalendarSync`
—— VM `:1478` `removeSyncedEvents()` 的起手（`:1481`）此前只立 `syncing = true`、收尾只写 `message`，
上一次永久拒绝留下的旗标清不掉，而 `:1486` 那句「已移除 N 个日程」恰好把 `:1664` 那格重新点亮 ⇒
完成句旁边继续挂着 `:1677` 那颗按钮。它**不经**上面那一刀盖住的入口，所以 T106 的覆盖面到不了它。
改法取的仍是"起手成对清"（`:1481` 现在写 `it.copy(syncing = true, message = null, permissionPermanentlyDenied = false)`，
同行改写、零行号漂移），**弃"再套一道权限闸"那一支**：这条链今天就已经在唯一那道闸里（那颗行
`onClick` 就在 `withCalendarPermission { … }` 内，复算 `grep -rn "withCalendarPermission {" app/src/main/java` ⇒ 3 处），
而 `:331` 那句短路的 true 分支直接 `action()`、根本不启动 launcher ⇒ "再套一道闸"既不撤旗标，
又把**造成这枚病的**那件短路再犯一遍。清旗标在语义上等于"宣布此刻已授权"，这句由**入口在闸里**兜住
（真·永久拒绝时那条路根本不落 `showRemoveConfirm = true`，进不到那次 copy）；剩下的残态只有
"确认框开着的那几秒里回系统设置把权限关掉再点移除"那一格，方向安全：落的是 `:1487` 那句
「移除失败：日历写入异常，请检查权限后重试」，文案自己就写着要检查权限，而下一次点同步会走闸重新立旗标。
③ 那一族因此按新盘面重钉（旗标赋值 3→4、`message = null` 1→2，且"必须带旗标"改成**逐处**取），
并添第四枚判据 `everyRouteIntoTheRemoveChainStandsInsideTheSameGateAndClearsTheFlagPaired`
（起手形状 + 那条链的唯一入口枚举：`showRemoveConfirm = true` 写点 1 枚、界面调用点 1 枚且长在
`:1941` 那一层窗口里、闸本体与 launcher 各 1 枚）。本文件 ③ 那一族因此是三枚 → 四枚。
⚠️ 仍**没有装机证据**：这颗按钮在真机上不再挂出来（两条链都算），本节只有代码级推断（见 §4.1、§6.7 那一格）。

### 6.5 已被钉住那三枚的点名单

| 候选 | 钉它的文件 | 钉它的 `@Test` |
| --- | --- | --- |
| #2 `diff` / `skippedOccurrences` | `app/src/test/java/com/buaa/schedule/ui/CalendarSyncDiffClearPairingGuardTest.kt` | `:45` `everyDiffClearInViewModelAlsoClearsSkippedOccurrences`、`:77` `thePairedProducerIsTheOnlyWriterAndTheOnlyReaderSitsInsideTheDiffModal` |
| #3 `consecutiveFailures`+`suspensionCycles` / `giveUpReason` | `app/src/test/java/com/buaa/schedule/ui/signin/ScanGiveUpReasonDerivationGuardTest.kt` | `:61` / `:105` / `:140` / `:182` / `:204` 五枚全在管它 |
| #4 `PendingImport` 那五枚 | `app/src/test/java/com/buaa/schedule/ui/PendingImportConflictGroupTest.kt`（组数）+ `app/src/test/java/com/buaa/schedule/ui/importing/ImportConflictCopyTest.kt`（三处读点计数） | 前者 `:207` 那条断言直接把「两处 copy 站点还在」数死 |

复算这三枚测试文件存在且带"成对"字样：`grep -rl "成对" app/src/test --include='*.kt' | wc -l` ⇒ **14**
（与卡面那句「本仓已有 14 枚测试文件的判据里带"成对"字样」一致；本节读的是其中与成对字段直接相关的
`CalendarSyncDiffClearPairingGuardTest.kt` 一枚，其余 13 枚是配色/墨色/撤销那几族，没有可挪用的判据）。

### 6.6 越界形状（本卡三档给不了它，如实挂在这里）

**#13 `(cameraError, cameraProviderMissing)`**（`ui/signin/SpocScanScreen.kt:157-158`）。
两枚字段互相约束（读侧是一条优先级梯，`ui/signin/ScanUiStatus.kt:87` 那支排在 `:93` 之前），
但**生产者不是同一次**：`cameraProviderMissing` 由 `SpocScanScreen.kt:274-288` 那颗
`LaunchedEffect(granted)` 写（`:279` 复位、`:283` 置真，两处都不点 `cameraError`），
`cameraError` 由绑定失败（`:411`）、相册解码失败（`:534` `.onFailure { cameraError = "$GalleryUnreadablePrefix：${it.message}" }`）、
绑定成功（`:398` `if (cameraError != null) cameraError = null`）三处写。
于是有一条**跨生产者**的残值：相册先失败过一次（句子进 `cameraError`）→ 之后 provider 拿不到
（`:283` 只置 `cameraProviderMissing = true`）→ 梯子上 `:87` 那支赢，界面永远说
「这张图读不出来，换一张图，或重新对准二维码再扫。」而真原因是那句 `:93` 的
「相机服务没把摄像头交给这一页」。**它不进 §6.2 的主账**，因为卡面的候选定义是"同一次生产 + 分头清点"，
而这两枚自 09-26 起就被 `SpocScanScreen.kt:155` 那行注释按"两件事"处理；把它塞进三档里的任何一档都要替
本卡扩一条判据。→ 按"一枚一卡"的规矩登记给编排者：见 §6.8④。

### 6.7 上限声明（本节最重要的一格）

**扫到了什么程度**：
- **闭合一档**：自有 data class 的 copy 站点宇宙（18 枚类 / 72 处站点）—— 本节**逐枚**过了一遍
  「参数表里有没有两枚字段出自同一次生产」与「有没有一处站点只写其中一枚」。
  这一档进表的 8 枚（#2 #3 #4 #6 #7 #9 #10 #11）全部判档完毕，**这一档没有剩**；
  另外 6 枚（#1 #5 #8 #12 #13 #14）来自 remembered var 那一档，见下一条。
  ⚠️ 但这一档的「过了一遍」是**按类**过的，不是按 177 枚 data class 过的：其余 **159 枚自有 data class
  连一处 `copy` 都没有**，本节按 §0.4 那条既有事实（"档位最高只能到无害"）**直接引用了 T97 的账，
  没有重新一枚枚读参数表**。如果那 159 枚里有一枚将来长出 copy 站点，本节不给它兜底。
- **第二档（本遍判完，但判据比上一档软）**：remembered var 这一族。用「同一文件里 ≥2 枚 remembered var
  且至少一枚被单独置空过」筛出 **14 枚文件**，本节把这 14 枚**逐枚读原文判完了**：
  `ui/editor/CourseEditorScreen.kt`（25 枚 var → #1 真漏清）、
  `ui/importing/BuaaLoginScreen.kt`（13 枚 → #5 + `(fetchState, fetchCancelled)` 一枚**无分头站点**，`:407-408`
  两枚一起写、`:429` 关的那枚此刻另一枚必为 null）、
  `ui/home/WeekView.kt`（17 枚 → #8 `menuFor`/`lastMenu`；其余 8 枚被清点的都是**单枚对象槽**
  `drag`、`pendingMove`、`movePickerFor`、`resizeFor`、`detailFor`、`pendingDelete`、`pendingSyncTarget`、`pressed`，
  唯一的跨槽配对是 `movePickerFor`→`pendingMove` 那一趟交棒：`:1245` `movePickerFor = null` 与
  `:1248` `pendingMove = CourseMoveRequest(` 长在同一个 `onConfirm = { dayIndex, startPeriod ->` 里 ⇒ 0 处分头清）、
  `ui/signin/SpocScanScreen.kt`（13 枚 → #13 越界；`boundCamera`/`analysisUseCase` → #12）、
  `ui/settings/SettingsScreen.kt`（26 枚 → #14；另 3 枚 `pendingCalendarAction` `:315`、`iclassSignedIn` `:1339`
  是单枚旗标，无配对对象）、
  `MainActivity.kt`（6 枚 → `(pendingPulseCourseId, pulseCourseId)` 判**不成立**：`:756` `pulseCourseId = pendingPulseCourseId`
  是一次性交棒，`:758` 归零的是**队列**、留给 `:843` 那份是**已交出去的一枚**，其消费点是 `:844`
  `onHighlightConsumed = { pulseCourseId = -1L }`，形状与 §6.6 那枚"跨生产者梯"不同、与漏清也不同）；
  以及 `ui/course/CourseManagementScreen.kt`、`ui/home/DayView.kt`、`ui/home/HomeScreen.kt`、
  `ui/importing/ImportScreen.kt`、`ui/onboarding/OnboardingScreen.kt`、`ui/settings/WidgetPinRow.kt`、
  `ui/stats/StatsScreen.kt`、`ui/signin/iclass/IClassLoginScreen.kt` 八枚文件 —— 这八枚里被单独置空的全是
  **单枚对话框/忙旗标**（`pendingDelete = null` `:213`、`dragActive = false` `:253`/`:271`、
  `pulseCourseId = -1L` `:208`、`showJumpDialog = false` 三处、`hadPendingImport = false` `:144`、
  `checking = false` `:151`、`showEnvironmentDialog = false` 两处、`showGuidance = false` `:123`、
  `expanded = false` `:611`、`submitting`/`serverMessage`/`password` 三枚），**本节没有在任何一枚上面找到
  "同一仪式成对写"的第二枚**，所以它们不进表（"没找到配对对象"与"配对了但没守卫"是两件事，后者才记账）。**现状（T132 重钉）**：上面那一段里落在本三枚文件上的只有两枚 —— `expanded = false` 那枚 `:611` 在统计页窗口（pre `:298` 那句 `onApplyShift = { target, newPeriods -> …`）之后 ⇒ 今天在同一枚文件上的 `:615`（复算 `awk 'NR==611' <<<"$(git show 5f39a4d^:app/src/main/java/com/buaa/schedule/ui/stats/StatsScreen.kt)"` 与 `awk 'NR==615' <<<"$(git show HEAD:app/src/main/java/com/buaa/schedule/ui/stats/StatsScreen.kt)"` 两句**同文**，都是 `expanded = false`）；`pulseCourseId = -1L` 那枚 `:208` 在首页文件上、落在首页窗口（pre `:819`）之前 ⇒ 未漂。同段其余各枚（`:213`/`:253`/`:271`/`:144`/`:151`/`:123`）逐枚拿本行原文去 `git show HEAD:<候选文件> | awk 'NR==N'` 比过，**没有一枚落在这三枚文件里** ⇒ 不动。「任何一枚上面没找到第二枚」这一判与档位都不变。
  ⚠️ **这一档的软处要如实写**：判"单枚旗标 ⇒ 无配对"用的是**读原文时没看见第二枚**，不是程序化证明。
  `ui/signin/SpocScanScreen.kt` 那 13 枚里本节只配对了 3 对，其余没配对的 10 枚是按"读者只有同一颗 effect"
  放过的，本节不给它们逐个点名 ⇒ 这一档**允许有漏**。
- **本档剩下的（数得出来，且按定义进不了候选）**：全仓 `grep -rcE "\bvar [A-Za-z_][A-Za-z0-9_]* by (remember|rememberSaveable)" app/src/main/java --include='*.kt' | grep -v ':0$' | awk -F: '$2>=2 {n++; s+=$2} END {print n" 枚文件 / "s" 枚 var 行"}'`
  ⇒ **20 枚文件 / 161 枚 var 行**；扣掉上面判完的 14 枚文件 ⇒ 残 **6 枚文件 / 19 枚 var 行**，逐枚是
  `core/designsystem/ScheduleCharts.kt`(6)、`widget/WidgetConfigActivity.kt`(4)、
  `core/designsystem/GlassSegmentedControl.kt`(3)、`core/designsystem/SceneBackground.kt`(2)、
  `ui/home/ConflictWizardDialog.kt`(2)、`core/designsystem/liquid/TermAndCampusBar.kt`(2)。
  这 6 枚**一枚都没有被置空过**（脚本 CLR 那一遍给 0 行 ⇒ 它们压根没进 14 枚那张表），
  按 §6.0 的候选定义"存在一处只写其中一枚"它们不可能成为候选 —— 除非把"重新赋一个非空新值"也算清点，
  那是另一件事，本节没做。⚠️ 169/161 两枚数**不是同一把尺**：前者是 `grep -c`  summed 的 var 行、
  后者只累加"文件里 ≥2 枚"的那些行；本节的 14 枚文件那张表吃的是脚本按 var **名**去重的数（142 枚），
  三把尺各说各的，别互相减。
- **一档完全没扫**：`app/src/debug/java`、`app/src/androidTest/java`、`app/src/test/java`、`benchmark/` 模块、
  Room/`Mappers` 生成物 —— 同 §4.2，本节没有扩大范围。
- **没跑门禁**：按卡面红线，本节**零 gradle、零 adb、零设备**，所有结论只来自读源码。
  ⇒ 「#1 是真漏清」这一句的证据是**代码可达性**，不是装机截图；它在设备上具体会念多久（退场动画几帧还是整屏停留）
  本节验不到，与 §4.1 同一档。

### 6.8 明留（本节认为该改、按红线一枚没动）

| 编号 | 位置 | 该改什么（不写方案细节，等排卡） |
| --- | --- | --- |
| ① | `ui/ScheduleViewModel.kt:1462` `it.copy(targetId = calendarId, targetName = displayName, showPicker = false)` 与 `:1528-1529` 那两行 `if (targetGone) -1L` / `if (targetGone) null` | `targetId`/`targetName` 三处写点今天全成对，但**没有任何守卫**钉住"成对"。唯一读者 `ui/settings/SettingsScreen.kt:1623` 不在闸门里 ⇒ 与 T100 改前的 `diff`/`skippedOccurrences` 只差一枚守卫。该补的是 `CalendarSyncDiffClearPairingGuardTest` 那一形状的第二份实例。**现状（守卫已由 T105 立、偏好那一半由 T110 收）**：守卫落在 `app/src/test/java/com/buaa/schedule/ui/CalendarSyncTargetPairingGuardTest.kt` ① 那一族（两枚各赋值 3 处、逐处成对、界面读者 1 枚且不在 `targetId` 驱动的块里）。T110 收的是**同一对字段在偏好里的那一头** —— 改前的形状是 `:1523` 那次 `targetGone` 检测只把内存里两枚打回 `-1L` / `null`、偏好一行不动 ⇒ 起手 `:1373-1374` 在下一次冷启动又把死 id 与死名字捞回来：`:1623` 那行裸读继续念一个已经不存在的日历名，真去同步时 `CALENDAR_ID` 打进死 id、异常被 `runCatching` 吞掉，用户读到的是 `:1432`「同步失败：日历写入异常」（真因被洗成"写入异常"）。现在 `:1538` 那一档把两枚 key **一起** `remove`（与内存那两枚同样成对），且 `loaded.isNotEmpty()` 才动手（查询失败交回来的也是空列表，那一刻分不清「日历被删了」与「provider 抖了一下」）。新判据 `theDeadTargetIsDroppedFromPreferencesAsAPairOfKeysAndNotOnlyFromMemory`：两枚各撤一次、同一次 `edit` **按位置**判（两条一模一样的 `edit { }` 并排放，块头文本相等而分头清是真病）、闸门同时含 `targetGone` 与 `loaded.isNotEmpty()`、落点在那颗函数体内、两枚 key 各自出现 3 次（读 1 + 写 1 + 撤 1） |
| ② | `data/calendar/CalendarSyncManager.kt:71` `runCatching {` + `:90` `.onFailure { Log.w(TAG, "读取日历列表失败", it) }` + `:91` `return result` | 它把"查询失败"洗成"这台设备没有日历"，是 §6.4-A 那道**外来**闸门的来源；同时它使 `ui/ScheduleViewModel.kt:1517` 那句「读取日历列表失败，请检查日历权限」几乎永不显示。另附同族一笔：`calendarsLoaded` 全仓**没有任何**复位站点（`grep -rn "calendarsLoaded = false" app/src/main/java --include='*.kt'` ⇒ 0 行），于是 `calendars` 是进程寿命的缓存，用户在系统日历里删掉一个日历后选择器会一直列着那个死 id。**现状（T110 只收了后半笔）**：那颗 `runCatching` 吞异常的本体**一字未动**（本卡的范围是 VM 那一段区，:71 / :90 / :91 这三行仍挂在上面那一格里，等下一张卡）。后半笔收了 —— 上面那句「0 行复位」是**改前读数**，留着；T110 之后 `calendarsLoaded = false` 有 **1 枚复位站点**（`:1449` 开窗那一档连带写；复算 `grep -rn "calendarsLoaded = false" app/src/main/java` ⇒ 1 行），`calendars` 因此不再是"进程寿命"的缓存、`targetGone` 那次检测每次开窗都跑一趟。取的修法是"每次开窗强制刷新"而不是拆掉 `:1512` 那次早返回：**同步入口那一档今天不带复位**（有意 —— 每点一次同步多 1–2 趟 provider 查询，而同步链自己已经要跑 Room 读 + 逐课次内容摘要 + Events 查询），于是"同一进程里第二次点同步用的是上一份列表"这一格残态如实留着，它不影响死 id 被撤（每进程头一次开窗或头一次同步都必查）。守卫 `theCalendarListCacheHasExactlyOneResetSiteAndItIsThePickerOpening`：复位站点 1 处、宿主 `openCalendarPicker`、位置在那次查询之前、生命点 5、`:1512` 早返回仍在、`:1389` 那档**不带**它（正解若要改这一支，本判据故意先红） |
| ③ | **已由 T106 收掉**：`ui/ScheduleViewModel.kt:1389` 起手那次 copy（改后原文 `it.copy(syncing = true, message = null, permissionPermanentlyDenied = false, diff = null, skippedOccurrences = 0)`）；`ui/ScheduleViewModel.kt:1508` `_calendarSync.update { it.copy(permissionPermanentlyDenied = false) }` 保持只清旗标 | **改前登记的原话**：「清旗标不清句子；今天被 `ui/settings/SettingsScreen.kt:331` 那道 `hasCalendarPermission()` 短路挡着（§6.4-B）。这句注释该留在两处之一：要么把它做成成对清，要么把『靠哪道闸不念旧账』写进 KDoc」。**现状**：那一格判错了 —— `:331` 不是挡住漏清的闸，而是**造成**漏清的那件事（已授权时它让 launcher 根本不启动 ⇒ `:1508` 的 `onCalendarPermissionGranted()` 永不被调），而 `:1664` 那格会被 `:1429-1439`「同步完成…」重新点亮 ⇒ 真漏清。修法取了第一个选项：**把起手做成成对清**，并把可达路径枚举钉成守卫（§6.4-B「现状」那一段有取舍与为什么不选改读侧）|
| ④ | `ui/signin/SpocScanScreen.kt:283` `cameraProviderMissing = true` 与 `ui/signin/ScanUiStatus.kt:87`/`:93` 那两支 | 跨生产者残值（§6.6）：`cameraError` 以「读不出那张图」开头时，梯子在说相册、而真病因是 provider。判据该按**来源**分支，不是按**写入先后**赌。**现状（T114① 在基点 `ee68e23` 上复算，上面那句原文一字未抹）**：本格"按来源分支"这一刀**在 §6.8④ 写下之前就已经长在盘上**——分叉那一支由 `1eac187`（T45「拆掉手输签到码整条入口」，2026-09-21）落的，而 `1eac187` 是本节基点 `1c6b7bd` 的祖先（⇒ T103 当时就看得见它，§6.6 引的那句「这张图读不出来，换一张图，或重新对准二维码再扫。」正是分叉**之后**的产物）。⚠️ 卡面 09-27 猜的 T90 `abfd38c` **驳回**：那枚 commit 只动两枚测试文件、main 侧 0 文件。所以这一格**不能整格判作废**：剩下的那一小截是"**哪一支赢**仍然按静态先后赌"——`:88` 那一支从头到尾没读 `cameraProviderMissing`，provider 档 `:93` 排在它后面就永远轮不到；五步可达时序、守卫空档（相册前缀 × provider 缺失零覆盖）、改它要一起重钉的两枚守卫与代价全在 §7.3，复算命令在 §7.4。本格的"该改什么"由此从"补分叉"改成"**把 provider 那一枚读进 `:88` 那一支**"，等排卡 |
| ⑤ | `ui/editor/CourseEditorScreen.kt:614` `saving = true` | 本遍唯一的真漏清。修法与红线冲突（不许动 `app/src/main/**`），留一卡：删除这条链要不要复用 `saving` 这枚旗标本身也值得重判 —— 复用它是 `:310` 那句「保存中…」在删除时说假话的原因 |
| ⑥ | `ui/settings/SettingsScreen.kt:1814` `ModalTransition(payload = if (showPrivacyDialog) privacyConsentAt else null) { consentAt, modal ->` 与它上面 `:1812-1813` 那两行注释 | 这一对的"不漏"完全靠**那一行的写法** + 一段注释维持：`:1817`/`:1853` 两处 `showPrivacyDialog = false` 都不归零 `privacyConsentAt`，谁把它改回 `ModalTransition(open = showPrivacyDialog)`（本仓另一种常用写法，见同文件 `:1859` 那一层）或把 `privacyConsentAt` 添第二个读者，收场那几帧就当场翻成「未同意」。**这正是 T100 那枚守卫该钉的第二份实例**，形状一模一样、只欠写它 —— 本卡不许新增/修改测试，故只登记 |

### 6.9 卡面对账：三句复现、两句要驳

**复现（带命令）**：177 / 140 / copy 密度前八 / 「14 枚测试文件带"成对"字样」，逐格同上表，全部对得上。

**驳回 ①（卡面「`grep ".copy("` 的盲区本仓是不是空的」）**：盲区**不空**。
命令：`grep -rnE "(^\|[^.[:alnum:]_])copy\(" app/src/main/java --include='*.kt'` ⇒ 6 行命中，
去掉 5 行注释/KDoc，**代码里 1 处**：`ui/home/WeekGridGeometry.kt:253` `return copy(`
（`CourseDragState.advancedBy` 里的隐式接收者，与 T97 §0.1 记的是同一枚，自 09-26 没长新的）。
⇒ 本节的 72 处自有类站点**把这 1 处算在内**；只按 `.copy(` 数会把它漏成 71 处。

**驳回 ②（三档判据不完备）**：本遍有 2 枚成对字段落在三档之外，硬套会写假话：
- #10 `targetId`/`targetName`、#12 `boundCamera`/`analysisUseCase`：**成对写点存在、分头清点站点为 0**。
  它们既不是"真漏清"（没有那条路径）、也不是"结构不可能"（读者不在闸门里，见 §6.2 两行的锚点）、
  也不是"已被钉住"（没有测试）。它们是本节 §6.0 定义的"候选"**之外**的东西，却被卡面的
  「建议起手式」第二条（"看每一枚 data class 的字段表：哪些是成对语义"）直接点名要找。
  ⇒ 建议给下一遍补第四档："成对但暂无分头站点（无守卫观察项）"。本节把这两枚登记在 §6.8①/§6.2 里，
  档位那一格写的是"结构不可能（本遍判据下根本没进候选）"，那不是卡面第三档的原意，读者按 §6.8① 那条读。
- #11 `calendars`/`calendarsLoaded` 与 #7 的第二个方向：判"结构不可能"用的闸**不在字段对上**
  （一枚靠被调方的 `runCatching`、一枚靠 launcher 的短路），本节为它们各写了一句「这句话必须记着」，
  见 §6.4-A/B。卡面那句「判这档要给机制」满足了，但**机制可被别处一行改动挪走**，
  这与 §2.1 当年批评"正确性挂在一行运行期早返回上"是同一件事 ⇒ 这两枚实际强度低于 #5 #6 #8 #14。
  ⚠️ **T106 应了这句话，而且比本节预计的更糟**：#7 不是"被别处一行改动挪走"，而是那道闸**当天就不成立**
  （`:331` 的 `hasCalendarPermission()` 短路不是护栏、是漏清的成因，§6.4-B）。#11 那枚靠别人 `runCatching`
  的闸本节复核仍在（`grep -rn "calendarsLoaded = false" app/src/main/java --include='*.kt'` ⇒ 0 行，
  它仍是 §6.8② 那格），但"这一档的机制可被别处一行挪走"这条规矩对**下一遍**只强不弱。

### 6.10 本节的锚点普查（在 §6 最后一次编辑之后量的，按 §0.4 第 4 条那一格的口径）

⚠️ 本节往文档里点了名 ⇒ 它自己挪动了 §0.4 那两格普查数。**下面这几格是在 §6 定稿之后重量的**，
不是在 `1c6b7bd` 上量的（那两格在 `1c6b7bd` 上的现值 217 / 526 已被本节改成下面这两个数）：

- 本档显式锚点条数：`grep -rhoE '[A-Za-z0-9_]+\.kt:[0-9]+' docs/derived-field-audit.md | wc -l` ⇒ **268**
  （`1c6b7bd` 上是 217，T103 那一遍 +46 到 263，T106 再 +5。**T111 这一趟（改 §6.2 表 #7/#10/#11 三格）
  之后重跑同一条命令 ⇒ 仍 268，一字未动** —— 那三格新写的行号全部用裸 `:NNN` + 符号名/原文片段同框，
  没往本档补过一枚连写；复算时点：`6d6d121`（T110 已合进 master）之后、本卡最后一次编辑之后）
- 全仓普查：`grep -rhoE '[A-Za-z0-9_]+\.kt:[0-9]+' docs README.md | wc -l` ⇒ **578**（`1c6b7bd` 上是 526；
  T103 +47 到 573，T106 再 +5。⚠️ 全仓这一格与上一格的差是 310，不是别的 —— 两把尺都只吃本档之外的文档。
  **现状（T111 在 `6d6d121` 之后重跑）⇒ 589**，与本档那一格 268 的差跟着变成 **321**；578 ⇒ 585 ⇒ 589
  这两趟都是编排侧给 `docs/STATUS.md` 补账顶的，见下面那条「补记」，本卡的纯文档改动**没再顶高它**）
- ⚠️ **两把尺子在本节同样不许混**：上面两条吃 `grep -o`（一行两枚锚点算**两条**）。
  同一份文件换那一把数**命中行**的：`grep -cE '[A-Za-z0-9_]+\.kt:[0-9]+' docs/derived-field-audit.md` ⇒ **146**。
  268 与 146 的差就是"一行里点两枚锚点"多出来的那部分 —— §6.2 那张表一格里塞两枚是常态，所以本档这一格
  两把尺的差距（268 vs 146）比 §0.4 当年那组（217 vs 109）还要再拉开一点，抄错就等于报错数。
  **这一格 T111 重跑同一条命令 ⇒ 仍 146**（上面那三格从此对应的是 `6d6d121` 之后、本卡最后一次编辑之后的
  盘面：**268 / 589 / 146**）。
- ⚠️ **T106 那一遍给本档净添 5 枚锚点**（都在 §6.2 #7 那一格、§6.4-B 的「改前的形状 / 现状」两段与
  §6.8③ 那一格：`startCalendarSync` 起手那次 copy、`confirmCalendarSync` 落「同步完成…」那次 copy、
  `openCalendarPicker` 开窗那一档、`SettingsScreen` 里那颗按钮的 onClick 与那枚本地包装）。
  main 侧那枚修复是**同行改写**、`ScheduleViewModel.kt` 与 `SettingsScreen.kt` 行数一列不变
  （1683 / 2154）⇒ **本档指向这两颗文件的既有锚点一处都不漂**，只多不少。
  ⚠️ 本格故意不写成 `文件.kt:行号` 那种连写：它是本节的计数器，写了就会把上面三个数再顶高 2，
  变成"数错了还自称数过"。行号在 §6.2 / §6.4-B / §6.8③ 三处正文里与符号名、原文片段同框。
- ⚠️ **补记（09-26 深夜，编排侧给 `docs/STATUS.md` 补 T103–T108 五节，随后又补 T110 收单账）**：那两趟纯文档改动
  **只动全仓那一格** —— 578 ⇒ 585（补五节，净 +7 枚）⇒ **589**（补 T110 那节，再 +4 枚）；
  `docs/STATUS.md` 自身现 **122** 枚。本档那一格 268 与 `-c` 那把 146 **一字未动**
  （两把尺都只吃本档之外的文档，这正是上一格那句"差是 310"的意思）。
  ⇒ 这条给"现值必须连着在哪枚 commit 上量"又添一个实例：**任何人往 docs 里补一段带行号的账，
  全仓那一格当场过期**，而本档那两格毫无反应 —— 读的人若不知道自己看的是哪一把尺，会以为只有全仓那格在撒谎。
  ⚠️ **T111 这一趟（纯文档卡，收 §6.2 表 #7/#10/#11 三格 + TESTING.md 的门禁那几格）之后三把尺重跑：
  268 / 589 / 146 一字未动** —— 本卡往这两页写的行号一律是裸 `:NNN` + 符号名/原文片段同框（`:1481`、
  `:1449`、`:1538`、`:1391`、守卫那 10 枚 `@Test` 的枚数），**没补过一枚连写**，这是上面那条规矩第一次
  被下一轮真正执行到；顶高的只有 `X.md:NNN` 那一族（18 ⇒ 24，本页 8 ⇒ 13 + STATUS 9 ⇒ 10），
  而那三把尺都不吃它 —— 复算与逐枚回读都记在 TESTING.md「行号锚点犯的是同一族错」那一节。
  本卡另外把**本档 268 枚锚点全量回读**了一遍（脚本按「文件名 + 行号 → 读那一行 → 判非空且文件存在」：
  **268/268 落到非空行、0 枚越界、0 枚缺失文件**；本节 §6 那一段仍是 **51 枚 / 16 颗文件**，
  与上一格那句一字不差 —— 本卡动过的三格都在 §6 里，它们没添连写，所以这一枚数本该不动，量出来确实没动）。
- ⚠️ **本档这一格被本节自己改过两遍，这是实况不是事故**：`b15d34e`（§6.1–§6.9 初稿落档）之后量到的是
  261 / 526→571 / 140；本节随后补了 #14 那一行与 §6.7 的收口改写，两格各自 +2（+2 枚锚点来自 #14 那一格，
  其中 SettingsScreen 一枚、FirstRun 一枚），成了现在的 263 / 573 / 142。
  §0.4 第 4 条那句"这一族的现值必须连着在哪枚 commit 上量的一起写"在本节同样成立 ——
  **上面三个数对应的是本节最后一枚 commit，不是 `b15d34e`**。
- 本节 `文件.kt:行号` 形式的锚点已**逐枚回读**：T103 那一遍是 39 枚（一次性脚本：把锚点拆成「文件 + 行号」→
  读那一行 → 与同框的符号名/原文片段比对，39/39 命中、无越界、无缺失文件）；**T106 之后本节这一格是 51 枚
  （`-o` 那把尺，16 颗文件）**，同一把脚本重跑 ⇒ 51/51 落到非空行、无越界，其中 2 枚指向
  `app/src/test/**` 的守卫文件（`CalendarSyncDiffClearPairingGuardTest` 与 `PendingImportConflictGroupTest`），
  只搜 `app/src/main/java` 会假报"缺失文件"，把这 2 枚算进去才对得上 51。
  要点名一件事：
  `docs/` 的锚点保鲜声明那一节说的是**本档 §1–§5** 的行号对应 `e47a18e`；
  本节 §6 的行号对应 **`1c6b7bd`**，下一轮若动了被引文件，两批行号要**分开**重核。
  ⚠️ §7 与 §8 的行号对应 **`ee68e23`**（T114 的基点，`6d6d121` 之后的纯文档盘面）—— 本节 §6 那批
  与它们**不共版**：`ScheduleViewModel` 与 `SettingsScreen` 这两枚被引最密的文件在 `1c6b7bd`→`ee68e23`
  之间被 T104/T106/T110 动过，重核时分三批（§1–§5 / §6 / §7–§8）逐批对哈希，别一把梭。

## 7. T114 第三遍·§7：复核 §6.8④ 那一格（评估卡 · 只量不修）

分支 `ai/T114`，基点 `ee68e23`。本节与 §8 同出一枚卡、分两枚 commit（本节是第①枚，`T114①`）。
**零 main 改动、零测试改动、零 gradle（连 `--stop` 都没碰）、零 adb、零设备、零 `local.properties`**；
本节全部读数来自 `git log -S` / `git show` / `git merge-base` / `grep -n` / `awk 'NR==N'`。
本节**没有改动 §0–§6 任何一格的结论**，只在 §6.8④ 那一格末尾追加了一段「现状」（旧登记原文一字未抹，按本档规矩）。
⚠️ 本节新增文本**一枚 `文件.kt:行号` 连写都没有**（全部写成裸 `:NNN` + 同框符号名/原文片段，理由见 §6.10 末与 §7.6 那一格）。

### 7.0 这一节问的问题与卡面给的那句读数

卡面（T114）把 §6.8④ 登记的修法读成「**早就落地了**」，并猜落地者是 T90 `abfd38c`，要本节复算、允许驳回。
三档结论都合法，本节读盘面的结果是**第 2 档：还剩一小截真的** —— 但剩下的是哪一小截，与 §6.8④
当年写的"该改什么"**不是同一刀**。逐条：

- **"按来源分支"确实已经存在** ⇒ 卡面这句对（复算见 §7.2）。
- **落地者不是 T90 `abfd38c`，而是 T45 `1eac187`（2026-09-21）** ⇒ 卡面那句猜错了，而且错得有关系：
  `1eac187` 是 **§6 自己的基点 `1c6b7bd` 的祖先**，也就是说 §6.8④ 写下的那一刻分叉**已经在盘上**，
  而 §6.6 引用的正是分叉**之后**的那句 `:89` 字面量（`1c6b7bd` 版第 88 行就是那枚 `startsWith`，复算命令在 §7.2 第 5 条）。
  ⇒ "判已实现、本格作废"这一档**只对半格**：作废掉的应当是"补分叉"这一读法，不是这一整格。
- **剩下的一小截**：分叉只分**措辞**，不分**哪一支赢** —— `:88` 那一支从头到尾没有读 `cameraProviderMissing`，
  provider 那一档 `:93` 排在它后面就永远轮不到。于是 §6.8④ 那个"赌"字仍然成立，只是赌注从"两枚字段谁先写"
  缩到了"梯子第 4 档赢的时候，第 5 档的真病因有没有被说出口"。具体形状、可达时序、守卫空档在 §7.3。

### 7.1 卡面那八处行号逐枚复算（当场 `awk 'NR==N'`，与同框符号名对得上才写）

`ScanUiStatus` 那枚文件 182 行、`SpocScanScreen` 那枚 1626 行（`wc -l`，本节复算用）。

| 卡面写的指针 | 盘面读到的原文（截断到可核对的那半句） | 判定 |
| --- | --- | --- |
| ScanUiStatus 的 `:85-86` | 两行注释，起句「⚠️ 必须按来源分两支：cameraError 以「读不出那张图」开头 ⇒ 相册刚刚才失败，」 | ✅ 一字不差 |
| 的 `:87` | `cameraError != null ->` | ✅ |
| 的 `:88` | `if (cameraError.startsWith(GalleryUnreadablePrefix)) {` | ✅ |
| 的 `:89` | `"这张图读不出来，换一张图，或重新对准二维码再扫。"` | ✅ |
| 的 `:91` | `"相机不可用（$cameraError），请改用相册识别。"` | ✅ |
| 的 `:93` | `cameraProviderMissing -> "相机服务没把摄像头交给这一页（CameraX 起不来）…"` | ✅ |
| SpocScanScreen 的 `:157` | `var cameraProviderMissing by remember { mutableStateOf(false) }`（`:158` 是 `var cameraError`，`:155` 是那句「它和 cameraError 是两件事」） | ✅ |
| 的 `:279` / `:283` | `cameraProviderMissing = false` / `cameraProviderMissing = true`（同一颗 `LaunchedEffect(granted)`，键在 `:274`，早返回在 `:278`） | ✅ 两枚 |
| 的 `:398` | `if (cameraError != null) cameraError = null` | ✅ |
| 的 `:411` | `cameraError = reason` | ✅ |
| 的 `:467` | `cameraError = null`（判据在 `:464` `val bindErrorToRetry = cameraError?.startsWith(GalleryUnreadablePrefix) == false`） | ✅ |
| 的 `:534` | `.onFailure { cameraError = "$GalleryUnreadablePrefix：${it.message}" }` | ✅ |
| 的 `:310` 与 `:672-673` | 读侧传参：`:310` 长在 `scanCameraLive(` 那枚实参表里（`cameraError = cameraError,`），`:672-673` 是 `scanUiStatus(` 的两枚实参（`cameraError =` / `cameraProviderMissing =`） | ✅ 三处 |

⇒ **卡面这八处（拆开来是十三枚指针）逐枚复现，无一处漂移**。本节另外补两枚卡面没点名的读者锚点：
`scanUiStatus` 的返回值只被 `:675` `if (hintText != null) {` 那格画出来，而**同一列**下面那颗 frameAid
在 `:687` 写着 `if (cameraLive && hintText == null)` —— 本仓在这里**会**写闸，`:89` 那一支没写。

### 7.2 「修法已落地」的对账：落地了，但落地时间早于 §6.8④ 本身（五条命令）

```
# 1 谁写进那枚常量的（main 侧全量）
git log -S "GalleryUnreadablePrefix" --oneline -- app/src/main/java            ⇒ 70975fe 1eac187（两支，都是 signin 侧）
# 2 谁写出那枚分叉的（精确到表达式 + 文件）
git log -S "startsWith(GalleryUnreadablePrefix)" --oneline -- …/ui/signin/ScanUiStatus.kt   ⇒ 只有 1eac187
# 3 卡面猜的那枚 commit 改了什么
git show --stat --format= abfd38c                                              ⇒ 两枚 app/src/test 文件、+334 行、main 侧 0 文件
# 4 那枚分叉是否早于 §6 的基点
git merge-base --is-ancestor 1eac187 1c6b7bd && echo YES                       ⇒ YES
# 5 §6 自己看见过它（决定性的一条：T103 基点上第 88 行就是那枚 startsWith）
git show 1c6b7bd:…/ui/signin/ScanUiStatus.kt | grep -n "startsWith(GalleryUnreadablePrefix)"  ⇒ 88: 命中
git log --format="%h %ad %s" --date=short -1 1eac187                           ⇒ 1eac187 2026-09-21 refactor(signin): 拆掉「手输签到码」整条入口，降级文案六档逐条改口径
```

⇒ 结论：**"已实现"这一档驳回，"还剩一小截真的"这一档成立**。`abfd38c`（T90-B）连 main 都没碰，
不可能是它；分叉是 T45 那一遍"降级文案逐条改口径"里落的，而 §6.6/§6.8④ 是在看得见它的前提下写的。
另一枚相关 commit `70975fe`（T59「解码棘轮可恢复 + 绑定失败可就地重试」）只往 `cameraError` 那三枚
写点里添了 `:398` 与 `:464`/`:467` 那一族（回前台重试），**没有动梯子的次序**，也没动 `:88` 那支的判据。

### 7.3 剩下那一小截的具体形状（照 §6.3 的证据形状写）

- **字段对 / 宿主**：`(cameraError, cameraProviderMissing)`，宿主是 SpocScanScreen 那枚文件里的
  两枚 remembered var（声明 `:157`、`:158`）。§6.2 #13 已把这对判成**越界形状**（生产者不是同一次），
  本节**不推翻那一档**：本节量的不是"漏清"，是**读侧那一格阶梯**少读了一枚字段。
- **哪一枚能被留在非空**：`cameraError` 能以相册前缀非空，而同一时刻 `cameraProviderMissing == true`。
  写侧没有任何一处会把它俩的关系说清楚：`cameraError` 的四处写点 `:398`/`:411`/`:467`/`:534` 没有一处读
  `cameraProviderMissing`，`:279`/`:283` 两处也没一处读 `cameraError`（复算见 §7.4 第 2、3 条 ⇒ 互不点名 = 0 处）。
- **全序（不需要任何异常时序，全程可点）**：
  1. 冷启动进这一页，`granted` 已经是 true ⇒ `:274` 那颗 `LaunchedEffect(granted)` 头一趟就跑，
     `:278` 的早返回放行（此刻 `provider` 仍是 null），`cameraProviderWithRetry` 首试 + 重试都拿不到
     ⇒ `:283 cameraProviderMissing = true`；
  2. 相册那颗按钮**照常可点**：`:707` `enabled = scanner != null && !inFlight` —— 两枚条件都不含 provider；
  3. 用户挑了一张读不出来的图 ⇒ `:534` 写进 `cameraError = "$GalleryUnreadablePrefix：…"`；
  4. 梯子 `:87` 那支赢 ⇒ 屏幕上说的是 `:89`「这张图读不出来，换一张图，或**重新对准二维码再扫**。」
     而 provider 为 null 时绑定那颗 effect 在 `:318` `val cameraProvider = provider ?: return@LaunchedEffect`
     就断了，一帧都不会到 —— **"重新对准再扫"在这一格是死路**，而 `:93` 那句真病因（相机服务没把摄像头交给这一页）
     永远不出口。
  5. 这句残值**不会**被相机那一侧洗掉：`:398` 要绑定成功才清，`:467` 那一支被 `:464` 的
     `bindErrorToRetry` 明确把相册前缀排除在外 ⇒ 撤掉它的唯一途径是"换一张真能读出来的图"（或退出重进这一页）。
- **`:81-84` 那段注释自己承认了它管不到这一格**：`:81` 那句「绑定失败与 provider 缺失可以同时成立吗？不能」
  只对**绑定**那个写点成立，`:82-84` 立刻补了"但相册那条也借这个字段…所以这一支排在前面"。
  于是这一支的排序理由就是**写入先后**（"更具体的原因先说"），§6.8④ 那个"赌"字在此仍然生效，
  只是范围从整条梯缩到了这一支。
- **守卫账（这一格是测试矩阵的空档，不是测试允许的行为）**：`ScanUiStatusTest` 里
  `cameraErrorBranchesByItsWriter`（第 167 行那枚 `@Test`）两支都在 `cameraProviderMissing = false`
  的 healthy 基线上打（基线那张表在 `:41`）；`ladderPriorityFollowsTheNearestWayOut`（`:91`）那枚
  "两枚同时成立"的格子只试了**绑定原文**那一支（`:105` 传的是 `"cameraError" to "绑定失败：x"`）。
  ⇒ **相册前缀 × provider 缺失**这一格零覆盖。整枚文件的复算命令在 §7.4 第 4 条（8 行命中，逐行点名）。
- **代价（改它要付什么）**：措辞那一刀的代价很小（`scanUiStatus` 多读一枚参数、`:88` 那一支再分两档），
  但它**当场红**两处守卫：`ScanCameraAidWiringGuardTest` 的"放弃阶梯逐字比对"（`:248`/`:253` 那两行把
  `internal fun scanUiStatus(` 起到 `GalleryUnreadablePrefix` 声明之前整段钉成区域，右界就写死在那枚常量上）
  与 `ScanUiStatusTest` 的 `missingCameraProviderSpeaks`（`:65` 那枚 `@Test` 断言"七档文案彼此都不能重复"，
  新添一档就要跟着改那枚 7）。⇒ 排卡时这两处要**一起重钉**，别指望只改一句文案。
- **值不值（本节只给账，不替编排者判）**：不改变任何状态、不影响任何一条链的走向，覆盖面只在
  "provider 拿不到 + 相册又恰好读不出"这一格双故障；用户在这一格里已经看见的是"一块不会动的黑预览 +
  一句让他去重新对准的话"。与本仓 #117 那枚 deferred 同一形状（真的、但排不上号）。

### 7.4 本节四条复算命令（表格里放不下的那几格都指到这里）

```
# 1 阶梯两支的"互不点名"——cameraError 侧读不读 provider（⇒ 0 行）
grep -n "cameraError" app/src/main/java/com/buaa/schedule/ui/signin/SpocScanScreen.kt | grep -c "cameraProviderMissing"
# 2 反向同样为 0：provider 那两枚写点点没点名 cameraError
grep -n "cameraProviderMissing" app/src/main/java/com/buaa/schedule/ui/signin/SpocScanScreen.kt | grep -c "cameraError"
# 3 这对字段在 main 侧的全部落点（19 行，逐行都在 §7.1 那张表里）
grep -n "cameraError\|cameraProviderMissing" app/src/main/java/com/buaa/schedule/ui/signin/SpocScanScreen.kt
# 4 守卫矩阵：那枚测试文件里 provider 出现的 8 行，与 cameraError 同框的只有 :105 那一枚（非相册前缀）
grep -n "cameraProviderMissing" app/src/test/java/com/buaa/schedule/ui/signin/ScanUiStatusTest.kt
```
读数（在 `ee68e23` 上）：第 1 条 **0**、第 2 条 **0**、第 3 条 **19**、第 4 条 **8**（`:41`、`:53`、`:66`、
`:94`、`:102`、`:105`、`:263`、`:301`）。第 3 条那 19 行 = 声明 2（`:157`、`:158`）+ 注释 6（`:155`、`:333`、
`:396`、`:403`、`:664`、`:1536`）+ 写点 6（`:279`、`:283`、`:398`、`:411`、`:467`、`:534`）
+ 传参 3（`:310`、`:672`、`:673`）+ 判据 1（`:464`）+ 日志 1（`:466` 那句「上一次的失败原因是…」）。

### 7.5 本节没验到的（不写成"没有"）

- ⚠️ **零装机证据**：上面那条五步时序是**代码可达性推断**，"provider 首试 + 重试都拿不到"这一格在
  这台环境上从没被复现过（`cameraProviderWithRetry` 要真机/模拟器才验得到，而本卡红线禁设备）。
  与 §4.1、§6.7 末格同一档：那句「屏幕上是一句让他重新对准的话」本节**证不了它念了多久**，只证了它能被念到。
- **没扫 UI 侧那半屏**：`:707` 那颗按钮的 `enabled` 只查了 `scanner` 与 `inFlight` 这一句是本节读到的，
  本节没有把这一页所有以 provider 为条件的可见性列成表（`cameraLive` 那枚纯函数除外，它在 `:304`）。
- **没重开 §6.2 #13 那档**：越界形状那一判（"生产者不是同一次，不进 §6.2 主账"）本节照抄不动。
  §7 量的这一小截是**读侧阶梯**，不是新的字段对，于是它落在 §6.8④ 那一格里、不另立新格。

### 7.6 本节改动了文档哪两处（防"静默删除雷"的自证）

1. §6.8 表格 ④ 那一格的第三列**末尾追加**一段「现状（T114①）」，上面那句"判据该按**来源**分支，
   不是按**写入先后**赌"**原样留着**；这一格改完回读过列首的「④」与下一行列首的「⑤」都在。
2. §6.10 末尾**追加**一行「§7 与 §8 的行号对应 `ee68e23`」，上一行「本节 §6 的行号对应 **`1c6b7bd`**…」
   原样保留。
3. 三把普查尺在本节**两次**编辑之后重跑（命令在 §0.4 第 4 条与 §6.10 那三格）：本档 `-o` 仍 **268**、
   全仓 `-o` 仍 **589**、本档 `-c` 仍 **146** —— §7 全文没添一枚连写，所以 §6.10 那三格**不需要重钉**。
   ⚠️ 这一句写在 §7 里，量在 §7 落盘之后（读数写进回执）。

## 8. T114 第三遍·§8：起手块这一遍（评估卡 · 只量不修）

分支 `ai/T114`，基点 `ee68e23`（§7 之后，第②枚 commit `T114②`）。
**零 main 改动、零测试改动、零 gradle（连 `--stop` 都没碰）、零 adb、零设备**；全部读数来自 `grep` / `awk` /
逐枚读原文。与 §7 一样，本节新增文本**一枚 `文件.kt:行号` 连写都没有**（§8.6 第 5 条把这句话自己也数了一遍）。

### 8.0 这一遍问的问题（与 §0–§5、§6 都不是同一枚）

- §0–§5 问的是**字段表**：这枚参数是不是同表另一枚的函数。
- §6 问的是**成对字段的清点对岸**：有没有一处写点同时写 A 与 B、又有没有一处只清其中一枚。
- **本节问的是起手那几行**：每一条 `...Scope.launch { … }`（以及它体外两行以内的那次"开始一件事"）
  把头几行该立的旗立起来时，**有没有把上一次的产物一起收回去**。判据形状照 §6.0，只把"写点"换成"起手块"：
  1. 这枚旗的**对岸**是谁 —— 同一次操作产生的句子 / 错误 / 上一轮结果，**包括持久化偏好那一头**（T110 就栽在这上头）；
  2. 起手块清没清对岸？**每一个入口都问**，不只一条链（T110 的病是"起手清了、第二条链没清"）；
  3. 它的读者在不在闸门里 —— 不在闸里的裸读者就是"今天不漏、明天漏"的位置。
档位沿用 §6 那四档（真漏清 / 已被钉住 / 结构不可能 / 越界形状），**并首次启用 §6.9 驳回② 提议的第四档
「成对但暂无分头站点（无守卫观察项）」** —— 走这一档的是 **#9 一枚**（`buaaTermsFetching` × `_buaaTermOptions`），
本节没有第二枚够格，这一句本身要如实写出来。

### 8.1 尺子与宇宙（卡面那四把尺逐把重量）

| 量 | 复算命令 | 卡面给的 | 本节复算 | 用的哪把尺 / 漏了什么 |
| --- | --- | --- | --- | --- |
| `viewModelScope.launch` | 命令见 §8.5 第 1 条 | 35 | **35** ✅ | 命中行数；一行一处，与出现次数同值 |
| `scope\.launch` 与 `coroutineScope\.launch` | 命令见 §8.5 第 1 条 | 14 | **14** ✅ | ⚠️ **这把尺是大小写敏感的**：它吃全小写接收者（`scope.launch`），**不吃驼峰接收者**（`settingsScope.launch` 那串里是小写 s 后面接大写 S，模式对不上）⇒ 见下一行 |
| **卡面那两把尺漏掉的第三类接收者** | 命令见 §8.5 第 2 条 | 未提 | **27** | `applicationScope` / `animationScope`（12 处，设计系统那几个动效文件）/ `lifecycleScope` / `ioScope` / `rowScope` / `settingsScope` / `downloadScope` / `refetchScope` / `dragScope` —— **本节把这 27 处一起扫了**（其中真的立旗的只有 `downloadScope.launch` 那两处，见 #10） |
| 起手宇宙**全集** | 命令见 §8.5 第 3 条 | — | **76** | = 35 + 14 + 27，三把尺**两两不交**（本节实测：并起来正好 76，无一行被数两次）⇒ 卡面的 35+14=49 **只盖住全集的 64%** |
| `_[a-zA-Z]+\.update \{` | 命令见 §8.5 第 4 条 | 21 | **21** ✅ | ⚠️ 这把尺量到的 21 行**全部**是同一枚接收者（`_calendarSync`）、全部长在 ScheduleViewModel 那一枚文件里 ⇒ 它**不是**"整仓 StateFlow 写面"，只是那一枚状态机的写面。同文件里 `_importMessage` / `_pendingImport` / `_pendingBackup` / `_specialDays` 那几枚走的是 `.value =` 那一族，这把尺一行都不吃 |
| **`.value =` 那一族（补尺）** | 命令见 §8.5 第 5 条 | 未提 | **84 处**（ScheduleViewModel 一枚占 59） | 上一行那句盲区的定量账：本遍真正判的 #3 / #4 / #7 / #9 三枚旗**全在**这把尺上，卡面那把尺看不见它们 |
| `MutableStateFlow(` 声明 | 命令见 §8.5 第 6 条 | 5 | **5**（照抄卡面那把尺） | ⚠️ **这把尺给的数不可用**：它不吃带泛型实参的声明（`MutableStateFlow<AppMessage?>(null)` 那一族一行都不命中）。换 §8.5 第 7 条那把 ⇒ **14**。漏掉的 9 枚恰恰是这一族最该被数的几枚（`_importMessage`、`_pendingImport`、`_pendingBackup`、`_pendingEmptyRestore`、`_specialDays`、`_state` ×2、`_buaaTermOptions`、`specialDayVisibleMonths`） |
| 起手块会立旗 / 领闸 / 交柄的站点 | 见 §8.5 第 8 条（池子命令）+ 逐枚读原文 | 未给 | **13 处 / 宿主 6 枚文件** | 池子命令给 **28 行**，扣掉"收尾写 false"、"对话框开关"、`CourseSaveOptions(… = true)` 这类实参噪声后是 13 处。**⚠️ 本节自己踩到一次**：池子那把尺吃的是 `launch` 之后三行，而 startCalendarSync 起手那次成对清写在体外第 5 行（KDoc 注释把它推下去了），单跑池子命令会把它漏成一枚不立旗的链 ⇒ 13 处里含它，靠的是逐枚读原文，**不靠那把尺**。**现状（T129 按本行自己给的三条判据「立旗 / 领闸 / 交柄」复核，量于 `073d098`；上面那两格读数与"本节自己踩到一次"那段机制账一字未抹）**：那对 **13 / 63** 是**那把池子尺的产物**、不是旗标的真实分布 —— `ConflictWizardDialog` 那一处按这三条**占了「立旗」与「交柄」两条**（旗 = 那枚 `Set<String>` 的增删：起点 `onShiftStart` 裸 `:139`、终点 `onShiftEnd` 裸 `:141`、失败归还裸 `:142-143`；柄 = 裸 `:79` 的 `val job = viewModel.viewModelScope.launch` 再 `job.join()`），却被"体外两行以内会立旗"那把尺漏掉（它命中的是裸 `:78` 的 `var saved = false` 与裸 `:82` 的 `CourseSaveOptions(partialWeeks = true)`，正是本行点名要扣掉的两类噪声）⇒ **真实分布应是 14 处 / 62 处**。⚠️ 这一对数不是本卡新算的结论，是 **T126 先写在 §8.6 第二条那条现状句末尾**的（"按 §8.1 末行自己给的三条复算，这一枚旗与柄两条都占 ⇒ 真实分布应是 14 / 62"），T129 只是把同一笔账补进本行、让两处同值。**宿主那一半本行不重述**：「6 枚文件」是 T114 逐枚读原文给出的集合，本卡没有在盘上重建那六枚的身份 ⇒ 不判它跟着不动（本行只把"处数"那一半按三条判据改口，别把两半当一个数抄）**现状（T132 重钉）**：本行那些落在向导文件上的裸锚点是 T131 之前的读数 —— T131 在该文件 269→278、净插 9 行，窗口起点是 pre `:69` 那句 KDoc「写的是 [CourseSaveOptions.partialWeeks]：只改冲突的那几周，其余周保持原排课」⇒ 今天依次是：起点 `onShiftStart` 裸 `:148`、终点 `onShiftEnd` 裸 `:150`、失败归还那两行裸 `:151-152`、柄 `val job = viewModel.viewModelScope.launch` 裸 `:87`（后面那句 `job.join()` 今天裸 `:93`）、被那把尺误吃的两枚噪声 `var saved = false` 裸 `:84` 与 `CourseSaveOptions(partialWeeks = true)` 裸 `:90`。逐枚复算＝ `git show 5f39a4d^:app/src/main/java/com/buaa/schedule/ui/home/ConflictWizardDialog.kt | awk 'NR==旧'` 与 `git show HEAD:app/src/main/java/com/buaa/schedule/ui/home/ConflictWizardDialog.kt | awk 'NR==新'` 两句同文 ⇒ 本行判据不变。**本行那两对数（13 / 63 与 14 / 62）数的是站点枚数、不是行号** ⇒ 这一族挪位不动它们 |

**这四把尺合起来仍不是"起手块的全宇宙"**：只走 `LaunchedEffect { … }`、只走 `scope.launch` 之外的
`launch { }`（裸 `CoroutineScope.launch` 的隐式接收者）、以及 `viewModel.scope` 那类别名本节没数，见 §8.6。

### 8.2 候选账（本遍判 10 枚）

档位分布：**真漏清 3 枚（#3 #5 #6）· 已被钉住 1 枚（#8）· 结构不可能 5 枚（#1 #2 #4 #7 #10）·
第四档「成对但暂无分头站点（无守卫观察项）」1 枚（#9）· 越界形状 0 枚**。
⚠️ 那 5 枚"结构不可能"里**只有 #7 属于"结构上挡死"**（载体级免疫，§6.2 #9 那一型），
其余 4 枚（#1 #2 #4 #10）用的都是**别处的一道闸**，全部落在 §6.9 驳回② 那句"可被别处一行改动挪走"里 ——
机制逐枚写在 §8.4，本节一次都没有用"应该没事"。

| # | 旗标 × 对岸 | 起手块（宿主） | 生产者／把两枚绑在一起的证据 | 分头站点 | 读者在不在闸门里 | 判定 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `syncing` × （`message`，`permissionPermanentlyDenied`） | VM 的 `confirmCalendarSync`：体外 `:1425` 起协程、体内 `:1426` 只写 `it.copy(syncing = true)` | `:1416` 那颗函数与 `:1383` 那颗同属"一次会写日历的操作"；`:1428-1439` 收尾把 `message` 写非空 | **起手清对岸 0 枚**（同族另两处 `:1389` 清 4 枚、`:1481` 清 2 枚） | `message` 的读者 `:1664` `item(key = "status", visible = calendarSync.message != null)` **不含 `syncing`**；旗标的读者 `:1643` `enabled = !calendarSync.syncing` 只管那两颗按钮 | **结构不可能**（机制见 §8.4-A，可挪走那一类）。**现状（T116 已把这一格钉进守卫、零 main 改动；上面那五格读数与这一句档位原文都留着，它们量的是 `22d1a55` 的改前盘面）**：守卫＝`CalendarSyncTargetPairingGuardTest` 第 ⑪ 枚（该类枚数 10 ⇒ **11**），判"不动 main"，三枚承重行各占一格（成对清与 `diff = computed.first` 的**同宿主 + 先后**、那颗「同步」在 diff 弹窗窗口之内、全仓 main 树扫 `confirmCalendarSync` 只有那两枚文件），两侧七臂里 `C`/`C2` 两臂**只红这一枚判据**（枚数全对、落点错），机制与"补收那一支为什么驳回"见 §8.4-A 末 |
| 2 | `syncing` × （`diff`，`skippedOccurrences`） | VM 的 `removeSyncedEvents`：`:1480` 起协程、`:1481` 起手 | 同一枚 `computeDiff` 的产物（§6.2 #2）＋ `:1486`「已移除 N 个日程」把 `:1664` 那格重新点亮 | 起手清了 `message` 与旗标（T110），**没清 `diff`/`skipped`** | `skippedOccurrences` 的唯一读者长在 `ModalTransition(payload = calendarSync.diff)` 那块里（SettingsScreen `:1903` 起） | **结构不可能**（机制见 §8.4-B，可挪走那一类）；⚠️ 现有那枚 diff 守卫**管不到它**，理由见 §8.4-B 末。**现状（T116 已把这一格钉进守卫、零 main 改动；上面那五格读数与这一句档位原文都留着）**：守卫＝`CalendarSyncDiffClearPairingGuardTest` 第 ③ 枚（该类枚数 2 ⇒ **3**，归它不归第三枚文件：那一枚判据的宇宙本来就是这一对），判"不动 main"，形状＝三枚写点逐处负判据（一枚都不许碰这一对）＋ `:1481` 实参表逐字 ＋ 那枚入口调用点落在**两扇窗之外**（两格：该测试第 ③ 枚 `theRemoveChainClearsNeitherDiffNorSkippedBecauseTheTwoModalsHoldEachOtherOff` 体内的 `:265` `assertFalse`（吃 `at in diffAt until diffEnd`）与 `:273` 那枚（吃 `at in removeAt until removeEnd`））＋ `dismissCalendarSyncDiff()` 两枚收场路落在 diff 窗**之内**（一格：同一枚判据体内 `:288` 的 `assertTrue`）；⚠️ 上面这条「形状＝」串在 T116 那一版里**曾写着**「＋ 两扇弹窗窗口互不重叠 ＋」，**该格 T118 已判成恒真删掉**（旧句子按本仓规矩留着不抹，恒真的机制账复述在 §8.4-B、原件在该判据 KDoc 第 2 条）—— 别跟运行期那道闸混了，「两扇窗彼此挡住对方的入口」这一句仍然成立；"照 `:1389` 的仪式补收"那一支**驳回**（那是拿「移除」去作废一份用户还没确认的 diff，不是清场），机制与代价见 §8.4-B 末 |
| 3 | `_buaaRefreshing` × `_importMessage` | VM 的 `refreshFromBuaa`：`:927` 立旗、`:928` 起协程 | `:941` 那句进度（「正在刷新课表：第 x/y 周…」）与 `:988` 那次落旗出自同一趟刷新 | **三枚入口只有一枚补了句子**：ImportScreen `:378` 调完立刻 `:379` `showMessage("正在刷新课表...")`；HomeScreen `:643`（`onTermSelected`）与 `:853`（菜单「刷新课表」）**都不写** | 首页读者 `:468-471` 是**读完即清**（`showSnackbar` 紧跟 `clearImportMessage()`）；ImportScreen `:392` 与 SettingsScreen `:705` 两枚读者是常驻横幅，**不自清** | **真漏清**（形状齐全，当下无可达残值 ⇒ 全靠首页那一行 `clearImportMessage()`）**现状（T132 重钉）**：本行落在首页文件上那枚 `:853`（菜单「刷新课表」）是 T131 之前的读数 —— T131 在首页 1364→1368、净插 4 行，窗口起点是 pre `:819` 与 `:820` 那两行（`// viewModelScope + join + partialWeeks 三条账都在那一处，这里不再抄一遍` 与 `onApplyShift = { target, newPeriods -> …`）⇒ 今天那句 `if (!viewModel.refreshFromBuaa()) onImportBuaa()` 落在首页裸 `:857`（复算 `git show HEAD:app/src/main/java/com/buaa/schedule/ui/home/HomeScreen.kt | awk 'NR==857'`）；本行另外两枚首页锚点 —— `:643` 的 `onTermSelected`、`:468-471` 那枚「读完即清」—— 都在窗口之前 ⇒ 未漂；VM 与 ImportScreen / SettingsScreen 那几枚不在本三枚文件里 ⇒ 一字未动。本行档位「真漏清」不变 |
| 4 | `_buaaRefreshing` × `buaaRefreshJob` | 同一枚链的**收尾**：`:988` 在 `finally` 里落旗 | `:928` 把新 Job 交给那枚可变量、`:1002` 在取消时把它交回 null ⇒ 旗标与句柄是"同一件事在忙"的两半 | 收尾 `:988` 只落旗、**不清句柄**（改后仍留着一枚已完成的 Job） | 唯一读者 `:1003` `if (job.isActive)` —— 已完成的 Job 永远不 active ⇒ 读不出假话 | **结构不可能**（机制见 §8.4-C，可挪走那一类：两道闸都在别人身上） |
| 5 | `submitting` × （`saved`，`serverMessage`） | IClassLoginScreen 的 `submit()`：`:82` 立旗、`:83` 收句子、`:84` 起协程 | 三枚是同一次登录的三种产物：在忙 / 成功了 / 服务器给了原因 | 起手**成对**（`:82`+`:83`，与 §6.3 改前的编辑器同一形状）；**成功那一档分头**：`:87` `saved = true` 与 `:89` `password = ""` 连写，`:90` 走人，**没有 `submitting = false`**（只有失败那一档的 `:95` 落旗） | `statusText` 那一支 `when`（`:101-103`）按 `saved` → `submitting` → `serverMessage` 排；但**同一列**那颗按钮的字 `:160` `Text(if (submitting) "正在登录…" else "登录")` **不吃那条梯** | **真漏清（本遍唯一一枚当下就发作的）**，展开见 §8.3-B。**现状（T115①/②/②′ 在基点 `34fadd8` 之后已修；上面那四格读数与这一句档位原文都留着，它量的是改前盘面）**：修的不是"成功档少落一枚旗"，而是**两枚读者各判一次**这件事本身 —— 三枚裸旗从此只在一枚 `val phase = when { saved → submitting → serverMessage → else }` 里被读成界面话，状态句、那颗按钮的字（新起 `private enum class LoginPhase(val buttonLabel: String)`，Saved 那一档说「已登录」）、失败卡那层语义色三处一律投影自阶段值。**没有**采纳"成功档补 `submitting = false` 与失败档对称"：那枚旗同时是本页唯一的重入闸 `if (submitting) return` 与三处 `enabled = !submitting`、键盘 Done 短路的共同来源，落旗等于在这一页交出去之前（存在这段窗口 —— 路由那一侧给这一页提供了 `LocalAnimatedVisibilityScope`；⚠️ 它有多长今天禁设备、没量过）把表单放开并允许第二趟登录 POST，而第二趟一旦失败就写 `serverMessage`、把「已登录」那句染成失败卡。**成功档故意不落旗、失败档照旧落**这一对不对称本身钉进守卫（`IClassSignInWiringGuardTest` 第 ⑦ 枚，枚数 6 ⇒ **7**） |
| 6 | `checking` × `checks` | OnboardingScreen 的 `runChecks()`：`:143` 短路、`:144` 立旗、`:145` 起协程 | `:150` 一次写 `checks`、`:151` 同趟落 `checking` | 起手只立旗，**不收上一轮那份清单** | 部分在闸里：`:476` `if (checking && checks == null)` 只在头一趟画 spinner、`:482` 与 `:486` 那两处 `enabled = !checking`、`:251` `nextEnabled = !checking && …` | **真漏清 → 判 deferred**（先例 #117）：重跑期间旧结论文字仍读得出来，但同一屏那颗按钮自己写着「正在检测…」（`:489`），且每一张卡的 Fix 入口都被 `enabled` 按住 ⇒ 代价=措辞，本遍认为不值 |
| 7 | `flight` × `_state` | SignInViewModel 的 `signIn`：`:104` 领闸、`:105` 起协程、`:113` 在 `finally` 归还 | `_state` 是 sealed 那族（`Idle` / `Submitting` / `Signed` / `Failed`），一次成功写 `:159` | 起手不收上一张结果卡 | 上一张卡**不可能被单独读到**：那条链每一档都是整枚重建（`:135`、`:153`、`:159`、`:164`、`:172`、`:183`、`:199`、`:218`） | **结构不可能**（**结构上挡死**那一类：`.copy(` 0 处，一半都漏不掉；§6.2 #9 同一型） |
| 8 | `specialDayFetchInFlight` × 本轮落盘与日志 | VM 的 `refreshSpecialDays`：`:375` 在协程**外** `compareAndSet` 领闸 | `:383` 的归还在同一颗协程的 `finally` 里，且带 `if (claimed)` | 领闸者必归还；未领到的人**故意不归还**（`:383` 那行） | 闸门的读者是它自己（`:375` 那一行 + `runSpecialDayFetch(…, fetchInFlight = !claimed)`），`_specialDays` 的读者不读这枚旗 ⇒ 它压根没有"上一轮残值"这一说 | **已被钉住**：`SpecialDayRefreshWiringGuardTest` 里点名这枚闸门（main 侧 3 行、测试侧 4 行，命令见 §8.5 第 9 条） |
| 9 | `buaaTermsFetching` × `_buaaTermOptions` | VM 的 `refreshBuaaTerms`：`:708` 双条短路、`:709` 立旗、`:710` 起协程 | `:714` 那一档把列表交给 cache、`:718` 同趟落旗 | **成对写点 0 处、分头清点 0 处**：cache 全仓**没有任何**复位站点（写点只有 `:714` 那一处，唯一初值长在声明处 `:697`）⇒ 按 §6.0"存在一处只写其中一枚"根本进不了候选 | `:708` 读 cache 判非空，HomeScreen 那侧 `:637` `if (termOptions.isNotEmpty())` 决定那颗学期按钮**画不画** ⇒ 空 cache 的读者是"整块不组合" | **第四档：成对但暂无分头站点（无守卫观察项）** ← **本遍唯一一枚走新档的**。⚠️ 另记一句：这枚旗是**裸 `var`、不是 StateFlow**（`:701`）⇒ 它压根没有界面读者，"在忙"这件事用户永远看不见，而这正是 `:373` 那段注释拿它当反例点名过的原因 |
| 10 | `downloadJob` × （`_state`，`pendingInstall`） | UpdateCheck 的 `startDownload` `:184` 与 `retryInstall` `:194`：把新 Job **交给句柄**当起手 | 句柄=「在下载/在装」，`_state = Downloading(…)`（`:219`）与 `pendingInstall = info to file`（`:345`、`:350`）是同一件事的三半 | `install()` 里**三条失败支只写 `_state`、不清 `pendingInstall`**：`:340`、`:357`、`:366` 各一处；对照成对那两处：`:345`+`:346`、`:350`+`:351` 是**连号两行**，`:173`+`:174` 与 `:369`+`:370` 也是 | 唯一读者 `retryInstall`（`:189` 拆那枚 Pair）只从 UpdateDialog 的 NeedsInstallPermission 那一档进（`:67` 传参、`:318` 那颗「重试安装」）；Failed 那一档走 `:83` `SimpleDialog`，**没有**那颗按钮 | **结构不可能**（机制见 §8.4-D，可挪走那一类） |

### 8.3 两枚值得展开的

**A. #3 `_buaaRefreshing` / `_importMessage` —— T110 那一型换了载体**

- **对岸是谁**：`_importMessage` 里**上一趟刷新留下的那一句**。它与旗标同生：`:941` 每次进度都写它，
  `:988` 落旗的那趟同一条链上一句也刚写过（`:966`「刷新完成，但教务系统没有返回课程」/
  `:983`「刷新失败：…，可稍后重试」）。它**不是**持久化偏好，但 T110 那一课在这里同样成立 —— 要问的是**每一个入口**。
- **每个入口都问的结果**：`grep -rn "refreshFromBuaa" app/src/main/java` ⇒ **5 行**（§8.5 第 10 条），逐枚：
  VM `:923` 定义、VM 内部无第二处调用、ImportScreen `:378`（**后面紧跟 `:379` 补句**）、
  HomeScreen `:643` `onTermSelected = { code -> viewModel.refreshFromBuaa(code) }`（**不补**）、
  HomeScreen `:853` `if (!viewModel.refreshFromBuaa()) onImportBuaa()`（**不补**）⇒ **三枚入口、一枚补、两枚不补**。（T132 重钉：本行这枚 `:853` 同为 T131 之前的读数，首页净插 4 行、窗口起点 pre `:819` ⇒ 今天那句 `if (!viewModel.refreshFromBuaa()) onImportBuaa()` 在首页裸 `:857`；复算 `git show HEAD:app/src/main/java/com/buaa/schedule/ui/home/HomeScreen.kt | awk 'NR==857'` 与 `git show 5f39a4d^:app/src/main/java/com/buaa/schedule/ui/home/HomeScreen.kt | awk 'NR==853'` 两句同文）
- **当下为什么不发作**（这句必须写出来，否则下一轮会把它当"真 bug"排掉）：那两枚不补的入口都长在首页，
  而首页读 `_importMessage` 的方式是**吃掉再清**：`:468` `LaunchedEffect(importMessage)` → `:470` `showSnackbar(message.text)`
  → `:471` `viewModel.clearImportMessage()`。⇒ 走到 `:853` 那一刻，句子早已被首页自己清成 null。（T132 重钉：本行这两枚都落在首页文件上 —— `:471` 那枚在首页窗口（pre `:819`）之前 ⇒ 未漂，`:853` 那枚在窗口之后 ⇒ 今天裸 `:857`，与上面两行同族同判）
- **为什么本节仍判"真漏清"而不是"结构不可能"**：挡住它的不是字段对自身的性质，而是**另一枚文件里的一行
  `clearImportMessage()`**。§6.9 驳回② 已经把这条判据的强度问题写清楚了（#7 就是这么翻车的），
  本节不重复犯错。谁把首页改成"横幅常驻不自动清"（ImportScreen 与 SettingsScreen **今天就是这样**：
  `:392` 与 `:705` 两枚读者都不自清），#3 当场从"不发作"变成"删除链的兄弟"。
- **代价与改法方向（本节不给方案）**：与 T106/T110 同一刀 —— 起手成对清（在 `:927` 立旗那一行旁边补一句
  `_importMessage.value = null`，或把"正在刷新"这句由 VM 自己写而不是让调用方补），
  好处是三枚入口一次盖住；代价是 `refreshFromBuaa` 在"已在忙"那一档（`:924` 短路 return true）也会清句子，
  而 ImportScreen `:379` 今天正是在这一档**故意**再写一遍"正在刷新" —— 两处的口径得先对齐再动。
- **守卫账**：`grep -rn "buaaRefreshing" app/src/test --include='*.kt' | wc -l` ⇒ **0 行**（§8.5 第 11 条）⇒ 零覆盖。

**B. #5 IClassLoginScreen 的 `submitting` —— 成功那一档把旗忘在原地**

- **生产者的证据**：`submit()` 体外的连号两行 `:82 submitting = true` / `:83 serverMessage = null`
  —— 这一半**已经做对了**（就是 §6.3 那枚编辑器改前缺的仪式，T104 补的那一刀在这里是原样存在的）。
  病在**收尾**：失败那一档 `:94`+`:95` 连号（写句子、落旗），成功那一档 `:87` `saved = true` → `:89` `password = ""`
  → `:90` `onLoggedIn()` —— **三行里没有 `submitting = false`**。
- **谁能读到那枚残旗**：`statusText` 那支 `when`（`:101` → `:102` → `:103`）里 `saved` 排在 `submitting` 前面，
  所以提示行说的是「已登录北航 iClass，正在进入扫码页…」；而**同一列**下面那颗按钮的字 `:160`
  `Text(if (submitting) "正在登录…" else "登录")` **不吃那条梯** ⇒ 登录成功后这一屏同时写着
  「已登录…正在进入扫码页…」与「正在登录…」。四枚 `enabled = !submitting`（`:134`、`:146`、`:156` 与
  `:152` 那处短路）方向安全（点不动是对的），错的是**那句话**。
- **窗口有多长**：`:90` 的 `onLoggedIn()` 是一次导航跳转，旧页在退场动画期间仍在组合 ⇒ 与 §6.3
  那枚编辑器"退场动画那几帧"同一档。⚠️ 本节**没有装机证据**它念了多久（§8.6 第 3 条）。
- **为什么它比 #3 更该排卡**：#3 的残值当下不可达（要靠改别人），#5 的残值**每次登录成功都走到**。
  守卫账：`grep -rn "submitting\|serverMessage" app/src/test --include='*.kt' | wc -l` ⇒ **0 行**（§8.5 第 12 条）。
  ⚠️ 这一句是**改前读数**，留着：T115② 之后重跑同一条命令 ⇒ **20 行**，20 行全在 `IClassSignInWiringGuardTest`
  那一枚文件里（第 ⑦ 枚判据），别处仍然 0 —— 也就是说这一族的守卫只有这一处、且是静态源码核对，
  不是运行期测试（这一页是 `@Composable`，本仓没有 Compose 运行时单测的架子）。
  **现状（T115 已修）**：那一格展开的四步里，改的是最后一步 —— 残旗照旧留在 `submitting` 上（故意的，
  理由与代价见 §8.2 #5 那一格「现状」），但界面上那三处读者不再各判一次，而是全部投影自同一枚阶段值。
  ⚠️ 本节当时写的"窗口有多长"那一格（`:90` 的导航跳转 + 退场动画期间旧页仍在组合）**今天仍然没量过**：
  T115 这一趟照样禁设备，所以"存在这段窗口、这段窗口里两行不许互斥"钉成了判据，"这段窗口占几帧/几毫秒"
  一个字都没写。

### 8.4 那五枚"结构不可能"的机制（卡面硬要求：闸在哪、凭什么挡、属于哪一类）

**A. #1 `confirmCalendarSync` 起手。** 闸＝**那扇模态窗**。`:1417` `val pending = _calendarSync.value.diff ?: return`
⇒ 进不到这条链除非 `diff` 非空；而 `diff` 唯一的读者块是 SettingsScreen 的
`ModalTransition(payload = calendarSync.diff)`（`:1903` 起），里面那颗「同步」的 onClick 就是 `:1932`，
**全仓唯一一枚**（`grep -rn "confirmCalendarSync" app/src/main/java` ⇒ 2 行：定义 + 那一处，见 §8.5 第 13 条）。
⇒ 想在这一刻带着一句旧 `message` 走进来，必须有另一条链在弹窗开着的时候写 `message =`；
候选只有 `onCalendarPermissionDenied`（`:1494-1505`，一次写两枚）与 `removeSyncedEvents`（`:1486-1487`），
而它们各自的入口都在弹窗**底下那层**的设置列表里，`AlertDialog` 把列表整页挡住 ⇒ 写不进来。
**凭什么挡得住**：靠 Compose 对话框的输入独占，不靠这枚 data class 的任何性质。
**属于哪一类**：**可被别处一行挪走** —— 谁把 `diff` 从弹窗改成常驻卡（本仓另一种常见写法），
或者给那扇窗添一颗能触发权限申请的第二按钮，#1 立刻搬到"真漏清"，且错的方向与 §6.4-B 那枚一模一样
（「同步完成」旁边挂着「去系统设置开启日历权限」）。⇒ 登记 §8.7②。

**现状（T116 收 §8.7② 的 #1 那一半：判"不动 main"，把上面这三行闸钉成守卫）**。
零 main 改动、零测试文件新增，落点＝`CalendarSyncTargetPairingGuardTest` 第 ⑪ 枚
`theConfirmChainClearsNoPairItselfAndThreeOtherLinesHoldItShut`（该类枚数 10 ⇒ **11**；上面那六格改前读数
一字未抹）。判"不动"的机制与后果（这一格是本节 §8.0 第 3 问的正身：**裸读者**今天不在，但挡着它的是别人）：

- **现在不红靠谁挡着**（三行，逐枚钉成判据）：① `diff` 全仓唯一生产者 `diff = computed.first` 长在
  `fun startCalendarSync()` 体内、且**在同一枚函数体内排在 `:1389` 那次五枚成对清之后** ⇒ "手里有一份 diff"
  今天蕴含"起手刚把句子与旗标收过一遍"；② 界面唯一出口 `onClick = { viewModel.confirmCalendarSync() }`
  落在 `ModalTransition(payload = calendarSync.diff)` 那一段之内（输入独占）；③ 全仓 `app/src/main/java`
  树扫 `confirmCalendarSync` 只有那两枚文件（定义 + 那一句 onClick）⇒ 没有第二条路。
  把旗标立成 true 的那一枚站点（`:1497` `permissionPermanentlyDenied = !canAskAgain,`）也不在 confirm 体内。
- **哪一行被谁改掉就会红**：把 ① 那次成对清抽进 helper、或搬到 `computeDiff` 之后（**枚数一处不变**，
  只有落点错 ⇒ 这两臂实测各自只红第 ⑪ 枚那一枚判据，正是 T115②′ 那一课的靶子）；把 ② 那颗按钮搬出弹窗
  改成常驻卡（本节上面警告的那一支）⇒ 窗口包含判据红；在第三枚文件里调它 ⇒ 树扫判据红。
- **为什么"补收"那一支不采纳**（不是一句"没必要"）：起手清旗标在语义上等于**宣布此刻已授权**（T110 那笔账），
  `:1389` 与 `:1481` 都拿"入口在 `:331` 那道闸里"当凭据；confirm 的凭据只是**传递性**的（那扇窗由
  startCalendarSync 开），中间还隔着一次 `calendarSyncManager.apply()` —— 权限在弹窗开着的那几秒里被
  系统收回时，收尾落的正是 `:1432`/`:1437` 那句「…请重试或检查日历权限」，**那一刻把旗标清掉就等于在失败
  文案旁边撤掉唯一那颗出路按钮**（`:1677`）。另一枚字段 `message` 更不用收：收尾 `:1428-1440` 是整枚重建、
  每一档都无条件写新句子（这一条也钉进了判据）。⇒ 这一族正确的落点是"生产 diff 之前"那一枚，不是"应用 diff 之前"。
- ⚠️ 本节 §8.6 第 4 条那句"没有跑过任何一条真机点击序列"在这一格同样成立：守卫是静态源码核对，
  "弹窗独占输入"这件事今天仍然只有代码分支级证据，没有装机级证据。

**B. #2 `removeSyncedEvents` 起手不清 `diff`/`skipped`。** 闸＝**两扇弹窗互斥**。移除那条链的入口
只有 `:1941` 那扇 `ModalTransition(open = calendarSync.showRemoveConfirm)` 里的「移除」那颗，
而它要开着就得先点列表里那一行（`showRemoveConfirm = true` 全仓唯一写点，见 §8.5 第 14 条）；
`diff` 非空同样要开一扇模态窗（A 那一格）。两扇窗彼此挡住对方的入口 ⇒ 起手那一刻 `diff` 必为 null。
**⚠️ 现有守卫管不到这一格**，这一点要说死：`CalendarSyncDiffClearPairingGuardTest` 第一枚判据数的是
**`diff = null` 那类清点点**（钉"3 处、每处都带 `skippedOccurrences = 0`"），而 `:1481` 根本不写 `diff` ⇒
它不在这枚判据的宇宙里；`:1426` 同理。⇒ **这一格零守卫**，登记 §8.7②。
**属于哪一类**：可被别处一行挪走（同 A，且挪走的是同一道闸）。

**现状（T116 收 §8.7② 的 #2 那一半：上面那句"这一格零守卫"已经不成立，main 照旧一字未动）**。
落点＝`CalendarSyncDiffClearPairingGuardTest` 第 ③ 枚
`theRemoveChainClearsNeitherDiffNorSkippedBecauseTheTwoModalsHoldEachOtherOff`（该类枚数 2 ⇒ **3**）。
归它不归 `CalendarSyncTargetPairingGuardTest`，理由要说清：那一枚文件的宇宙本来就是"这一对的清点点"，
而 #2 缺的正是一格"这条链压根不在这对宇宙里、凭什么算它没漏"的账 —— 上面那句"第一枚判据数的是
`diff = null` 那类站点、`:1481` 根本不写 `diff` ⇒ 不在宇宙里"如今换成了**按位置**的判据（下面这几处
裸 `:NNN` 一律指该测试第 ③ 枚 `theRemoveChainClearsNeitherDiffNorSkippedBecauseTheTwoModalsHoldEachOtherOff`
的体内行号）：① `removeSyncedEvents` 体内三枚写点**逐处**负判据（一枚都不许写 `diff` 也不许写
`skippedOccurrences`，`:1481` 的实参表还钉成逐字）；**位置那一头今天实际钉着三格** ——
② 那唯一一枚入口 `onClick = { withCalendarPermission { viewModel.requestRemoveSyncedEvents() } }`
落在**两扇窗之外**（两格：`:265` 那枚 `assertFalse` 吃 `at in diffAt until diffEnd`、`:273` 那枚吃
`at in removeAt until removeEnd`，由 `:258` 那格 `opens.size` 恰好 1 托着），③ `dismissCalendarSyncDiff()`
的两枚收场路落在 diff 窗**之内**（一格：`:288` 那枚 `assertTrue` 吃 `at in diffAt until diffEnd`，
由 `:280` 那格 `dismisses.size` 恰好 2 托着）。
⚠️ **T116 那一版在 ②③ 之间还挂着第四格，本档按本仓"改前读数一字不抹"的规矩把旧句子留在原处、只标它的下场**：
它**曾写着**「diff 弹窗那一段与移除确认框那一段**互不重叠**（两层各自到下一枚 `ModalTransition(` 为界）」，
**该格 T118 已判成恒真删掉**（改的都是本测试，main 一字未动）—— 它写的 `diffEnd <= removeAt || removeEnd <= diffAt`
里，`diffEnd` / `removeEnd` 取的是各自头之后**最近**的一枚 `ModalTransition(`（体内 `:251`、`:253` 那两枚
`indexOf(MODAL_SEP, …)`），而 `ModalTransition(payload = calendarSync.diff)` 与
`ModalTransition(open = calendarSync.showRemoveConfirm)` 两枚头本身又以同一串开头 ⇒ 在两枚头的计数各恰好为 1
（`:236` 那格 `diffModals.size`、`:244` 那格 `removeModals.size` 的 `assertEquals` 钉着）的前提下，
"另一扇窗若真落进这一扇之内，它正好就是这一扇的右边界"，两条不等式至少一条取等 ⇒ 恒绿、结构上不可能红。
机制账与六种摆法的复算写在上面那枚判据的 KDoc 第 2 条（`:133` 起那一段）。
⚠️ 别把这枚被删的**静态**判据跟本节上面那句「两扇窗彼此挡住对方的入口」混为一谈：那一句讲的是**运行期**
那道闸（Compose 对话框独占输入、弹窗开着时另一扇的入口点不到），它**仍然成立**，也正是这一格
「结构不可能」的凭据本身；删格删掉的只是"拿两枚头的相邻位置去证明两扇窗分得开"那把套套逻辑的尺。
两侧读数：`B`（朝紧——照 `:1389` 的仪式给 `:1481` 补收这一对）红在逐字那一格、同时把本节 §8.5 之外
那枚"3 处成对清"的清点判据顶成 4；`G`（朝宽——把 `:1481` 拆回只立旗）红在逐字那一格、同时红在
`CalendarSyncTargetPairingGuardTest` ③ 那一族；`E`（朝宽·落点——枚数仍是 1，但把移除入口与 diff 弹窗里
那颗「取消」整枚对调）红在位置那一格。
**"补收"那一支为什么驳回**（这一格与 #1 不同，驳回理由不是"没有症状"而是"补了会更糟"）：这条链既不生产也
不读那一对，今天补上是纯 no-op；而一旦上面 ②③ 那两道位置闸被人改掉（给确认卡顺手补一颗「先移除再同步」，
本仓另一种常见写法），补收就从"无害"变成**点一颗写着移除的按钮，把用户正要确认的那份差异吞掉**——
比它假装修的残值更糟。真正该问的是"移除完成之后那份 diff 要不要作废"，那是产品判断、不是这一族的清场仪式。
⚠️ 另一笔成本：往 `:1481` 补 `diff = null` 会把本文件第一枚判据的 3 处清点顶成 4 处，而那个"3"是按 §6.3
那笔改前账钉的 ⇒ 两处都得重钉。**这一格与 A 同样没有装机证据**（§8.6 第 4 条），守卫是静态源码核对。

**C. #4 刷新链收尾。** 两道闸，**都不是这枚字段对的性质**：
① `:1003` `if (job.isActive)` —— 已完成的 Job 永远不 active，所以"旗已落、句柄还留着"读不出假话；
② `withImportLock`（`:664-668`，拿不到锁就写「已有导入正在进行，请稍候」并返回 null）——
它是本节真正担心的那一格（取消后旧协程的 `finally` 迟到落旗 ⇒ `:924` 那记重入闸短暂失效 ⇒ 允许第二趟刷新）
的兜底：两趟真并发也会被这把锁挡回来。
**凭什么挡得住**：`isActive` 是 Job 自己的状态机；`importMutex` 是三条写库链共用的互斥量。
**属于哪一类**：**可被别处一行挪走**（①把 `if (job.isActive)` 去掉就漏；②谁给 `refreshFromBuaa` 换一把
自己的锁、绕开 `withImportLock`，#4 立刻变成"取消能踢开正在跑的导入"）。⇒ 登记 §8.7③。

**D. #10 UpdateCheck。** 闸＝**弹窗按 sealed 分支渲染**。`pendingInstall` 的唯一读者是 `retryInstall`
（`:189` 拆那枚 Pair），而调它的只有 `UpdateDialog` 里 NeedsInstallPermission 那一档传进去的回调
（`:67` 传参、`:318` 那颗「重试安装」）；`Failed` 那一档走的是 `:83` `SimpleDialog`，**没有那颗按钮**
⇒ `:340` / `:357` / `:366` 三条"只写 `_state` 不清 `pendingInstall`"的支路今天都读不出来。
**凭什么挡得住**：靠 `when (shown)` 那几支的分支覆盖，不靠字段对。
**属于哪一类**：**可被别处一行挪走** —— 谁在 `Failed` 那张卡上顺手补一颗「重试安装」（这张卡今天已经有
「打开发布页」一颗按钮，加一颗是一行的事），点下去就会拿一份陈旧的 `(info, file)` 去装一个可能已经不存在的包，
而屏幕上说的是另一回事。⇒ 登记 §8.7③。

**E. #7 `flight` / `_state`（唯一一枚"结构上挡得住"的）。** 闸不在别人身上，在**载体本身**：
`SignInState` 那族每一档都是整枚重建（`:135`、`:153`、`:159`、`:164`、`:172`、`:183`、`:199`、`:218`
八处 `_state.value = SignInState.X(…)`），全仓 `SignInState` 一处 `.copy(` 都没有
（复算见 §8.5 第 15 条 ⇒ **0**）⇒ 想"只写一半"在这枚载体上**没有落点**。这就是 §6.2 #9 那枚 `AppMessage`
的免疫，判"结构不可能"不欠一道外来的闸。**唯一前提**：下一个人别给 `SignInState` 加可 copy 的字段表
（sealed 的 `data class` 分支一旦长出 `.copy(`，这枚就降级成"可被一行挪走"）—— 这句话归 §8.6 的上限，不归明留。

### 8.5 本遍十五条复算命令

```
# 1  卡面那两把尺（逐字照抄，读数 35 / 14）
grep -rn "viewModelScope.launch" app/src/main/java --include=*.kt | wc -l
grep -rn "scope\.launch\|coroutineScope\.launch" app/src/main/java --include=*.kt | wc -l
# 2  卡面漏掉的第三类接收者（⇒ 27）
grep -rnE "[A-Za-z]Scope\.launch" app/src/main/java --include=*.kt | grep -v viewModelScope | wc -l
# 3  起手宇宙全集（⇒ 76 = 35 + 14 + 27，两两不交）
grep -rnE "[A-Za-z]*[sS]cope\.launch" app/src/main/java --include=*.kt | wc -l
# 4  第三把尺（⇒ 21，且 21 行全是同一枚接收者）
grep -rnE "_[a-zA-Z]+\.update \{" app/src/main/java --include=*.kt | wc -l
grep -rhoE "_[a-zA-Z]+\.update \{" app/src/main/java --include=*.kt | sort | uniq -c
# 5  补尺：.value = 那一族（⇒ 84；单看 ScheduleViewModel 是 59）
grep -rnE "_[a-zA-Z]+\.value = " app/src/main/java --include=*.kt | wc -l
# 6  卡面第四把尺（⇒ 5，本节判它"数错了"）
grep -rn "MutableStateFlow(" app/src/main/java --include=*.kt | wc -l
# 7  换掉之后真正数到声明的尺（⇒ 14）
grep -rnE "val [A-Za-z_]+ = *(kotlinx\.coroutines\.flow\.)?MutableStateFlow" app/src/main/java --include=*.kt | wc -l
# 8  起手立旗的候选池（⇒ 28 行，逐枚读原文缩到 13 处）
grep -rn -B2 -A3 "[sS]cope\.launch" app/src/main/java --include=*.kt | grep -E '=[[:space:]]*(true|false)|compareAndSet\(' | sort -u | wc -l
# 9  一枚旗标的两侧覆盖（#8 已被钉住 / #3、#5 零覆盖）
grep -rn "specialDayFetchInFlight" app/src/main/java --include=*.kt | wc -l    # ⇒ 3
grep -rn "specialDayFetchInFlight" app/src/test --include=*.kt | wc -l          # ⇒ 4
# 10 refreshFromBuaa 的入口枚举（#3 那句"三枚入口一枚补"）
grep -rn "refreshFromBuaa" app/src/main/java --include=*.kt                     # ⇒ 5 行
# 11 那枚刷新旗的守卫账
grep -rn "buaaRefreshing" app/src/test --include=*.kt | wc -l                   # ⇒ 0
# 12 #5 那三枚字段的守卫账
grep -rn "submitting\|serverMessage" app/src/test --include=*.kt | wc -l        # ⇒ 0（改前读数）；T115② 之后重跑 ⇒ 20，20 行全在 IClassSignInWiringGuardTest 那一枚文件的第 ⑦ 枚判据里
# 13 confirmCalendarSync 的界面入口枚数（#1 那道闸的"唯一"）
grep -rn "confirmCalendarSync" app/src/main/java --include=*.kt                 # ⇒ 2 行
# 14 showRemoveConfirm 的写点枚数（#2 那道闸的"唯一"）
grep -rn "showRemoveConfirm = true" app/src/main/java --include=*.kt | wc -l    # ⇒ 1
# 15 #7 的载体级免疫：整枚重建 vs copy
grep -rnE "_state\.value = SignInState" app/src/main/java/com/buaa/schedule/ui/signin/SignInViewModel.kt | wc -l   # ⇒ 8
grep -rnE "state\.value\.copy\(|SignInState\.[A-Za-z]+\([^)]*\)\.copy\(" app/src/main/java --include=*.kt | wc -l  # ⇒ 0
```

⚠️ **补记（T129 在 `073d098` 上把上面十五条逐条原样重跑了一遍 —— 这一格欠的就是这条复算）**：
**每一条读数一字未动** —— 第 1 条 **35 / 14**、第 2 条 **27**、第 3 条 **76**、第 4 条 **21**、第 5 条 **84**
（单看 VM 那枚文件 **59**）、第 6 条 **5**、第 7 条 **14**、第 8 条 **28**、第 9 条 **3 / 4**、第 10 条 **5 行**、
第 11 条 **0**、第 12 条 **20**（上面那句"改前 0、T115② 之后 20"里，20 是今天的现值、0 只在 `34fadd8`
之前的盘面上才复现得到 —— 这一句本卡不重述为实测，它是那一档的历史读数）、第 13 条 **2 行**、
第 14 条 **1**、第 15 条 **8 / 0**。⇒ 这一族尺子自 `ee68e23`（§8 那一遍的基点）之后被 **六张动过 `app/` 的卡**碰过
（复算 `git log --oneline ee68e23..073d098 -- app/` 逐条归卡 ⇒ T115①②②′、T116①、T118①a①b②、
T121①②③③补、T122①、T127①②③；T119 / T120 / T126 那三张是纯文档卡、不在这一族里），
**没有一趟挪动这些尺**；本条只记"尺子没坏"，**不改变 §8.2
任何一格的判档**（本轮真正改口的现值只有 §8.1 末行与 §8.6 那两处的 13 / 63 ⇒ 14 / 62，机制账不在这些尺上）。
⚠️ 顺手把 §7.4 那四条与 §9.1 那五条也各重跑一次 ⇒ §7.4 = **0 / 0 / 19 / 8**（那两枚被点名的文件自
`ee68e23` 起零改动，复算 `git diff --name-only ee68e23..073d098 -- app/src/main/java/com/buaa/schedule/ui/signin/SpocScanScreen.kt app/src/test/java/com/buaa/schedule/ui/signin/ScanUiStatusTest.kt` ⇒ 零行）；
§9.1 那六条的复算写在 §9.1 末尾的补记里。

### 8.6 上限声明（这一遍扫的是什么、没扫到什么）

- **扫的到底是什么**：`[sS]cope\.launch` 全集 76 处（§8.5 第 3 条）里，**体外两行以内会立旗、领闸、
  或把 Job 句柄交出去**的那 13 处（宿主 6 枚文件），逐枚读完原文判档。这一档本节**判完了，没有剩**。
  **现状（T129，量于 `073d098`；上面这一整句一字未抹，它量的是 `ee68e23` 的盘面）**：按本条自己认的那三条
  （立旗 / 领闸 / 交柄）复算，这一档是 **14 处 / 62 处**，不是 13 / 63 —— 差的就是 §8.6 第二条那条「现状」
  点名摘出去的 `ConflictWizardDialog` 那一处（它占「立旗」与「交柄」两条，复算与逐枚落点写在 §8.1 末行那条
  「现状」里），"判完了没有剩"这一句**不变**，只是"判完的那一档"的边界从 13 挪到 14 ⇒ 两条数今天同值，
  而下面第二条那枚 **63** 也同步读成 62（这一对改口的账 T126 已先记在它自己那条「现状」末尾与本节回执）。
- **没扫到的一族（数得出来，不是"没找到"）**：76 处里剩下的 **63 处起手块本节一枚都没有立成候选**，
  因为它们**不立旗** —— 典型的三类：① UI 侧"点了就走"（HomeScreen 的拖课/改节次/删除三处、
  CourseManagementScreen 的换色与删组两处、ConflictWizardDialog 那一处、ImportScreen 那两处读文件）；
  ② 设计系统那 12 处 `animationScope.launch`（`animateTo` 的驱动协程）；③ 服务/组件那几处
  `ioScope` / `refetchScope` / `applicationScope`。⚠️ **"不立旗"本身是一条别的账**：这些站点在操作飞着的时候
  按钮仍可点、可重复触发（例如 HomeScreen 删除与课程管理删组那一族），那是**缺旗标**，
  与本节判的"起手漏清对岸"**不是同一族病**，本节不给它们判任何一档 —— 要排卡得先立判据（转 §8.7④）。
  **现状（T126 按 §9.2 / §9.4 的实测收窄；上面那 ①②③ 三类与「按钮仍可点、可重复触发」那一句一字未抹，
  它们量的是 `ee68e23` 的盘面）**：本句对 ① 里点名的 `ConflictWizardDialog` 那一处**断言不实**。那里今天立着一枚
  在飞旗：声明 `var pendingCourses by remember { mutableStateOf(setOf<String>()) }` 在 `:119`（弹窗层，注释 `:117-118`
  明写记在行内会被行重建复位），起点 `onShiftStart` 在 `:139`、终点 `onShiftEnd` 在 `:141`（`:142-143` 失败把按钮还给
  用户重试），读者两处同源 —— `enabled = !pending` 在 `:213`、文案 `Text(if (pending) "写入中…" else "只改这些周")`
  在 `:220` ⇒ 那颗「只改这些周」在写入期间**点不动**，「这些站点……按钮仍可点」对它就是错的（§9.4 驳回②）。（T132 重钉：本段那些落在向导文件上的裸锚点同为 T131 之前的读数 —— T131 在向导 269→278、净插 9 行，窗口起点是 pre `:69` 那句 KDoc「写的是 [CourseSaveOptions.partialWeeks]：只改冲突的那几周」⇒ 今天依次是 声明 `var pendingCourses by remember { mutableStateOf(setOf<String>()) }` 裸 `:128`、它上面那两行注释裸 `:126-127`、起点 `onShiftStart` 裸 `:148`、终点 `onShiftEnd` 裸 `:150`、失败归还那两行裸 `:151-152`、`enabled = !pending` 裸 `:222`、文案「写入中…／只改这些周」那一行裸 `:229`；逐枚复算＝ `git show 5f39a4d^:app/src/main/java/com/buaa/schedule/ui/home/ConflictWizardDialog.kt | awk 'NR==旧'` 对 `git show HEAD:app/src/main/java/com/buaa/schedule/ui/home/ConflictWizardDialog.kt | awk 'NR==新'`，两句同文。**「第三枚范本」「写入期间点不动」这两判都不变** —— 挪的是行号、不是形状）
  **收窄后的枚数：九枚里不立 UI 旗的是 8 枚，不是 9 枚** —— §9.2 那一遍是逐枚读原文判的，T126 另跑一把尺做旁证：
  这九处的控件宿主文件里 `enabled` 的落点只有 HomeScreen 2 行（`:1034` / `:1076`，两枚周翻页箭头）、WeekView 1 行
  （`:1175`，那是一枚 `BackHandler` 而不是按钮闸）、CourseManagementScreen **0 行**、ImportScreen 3 行
  （`:354` / `:481` / `:662`），**没有一行**落在这九处点名的那颗控件上。（T132 重钉：本段那两枚落在首页文件上的 `:1034`/`:1076` 同为 T131 之前的读数 —— T131 在首页 1364→1368、净插 4 行，窗口起点 pre `:819` ⇒ 今天那两枚周翻页箭头的 `enabled = (displayWeek ?: 1) > 1` 与 `enabled = (displayWeek ?: totalWeeks) < totalWeeks` 依次落在首页裸 `:1038` 与裸 `:1080`；复算 `grep -n 'enabled' app/src/main/java/com/buaa/schedule/ui/home/HomeScreen.kt` ⇒ **仍 2 行**、正是这两枚 ⇒ 「首页只有 2 行 `enabled`、都不在这九处那颗控件上」这一判不变。同段那枚 WeekView 的 `:1175`、ImportScreen 那三枚 `:354`/`:481`/`:662` 与「管理页 **0 行**」都不在本三枚文件里 ⇒ 未漂）
  ⚠️ **顺手把「这一格为什么会漏」的机制钉死，否则下一遍同样漏**：那一枚 `scope.launch` 其实进了 §8.5 第 8 条那把
  池子尺，可它命中的是 `:78` 那行 `var saved = false` 与 `:82` 那记 `CourseSaveOptions(partialWeeks = true)`（T132 重钉：这两枚编号也是 T131 之前的读数，向导净插 9 行、窗口起点 pre `:69` ⇒ 今天 `var saved = false` 在向导裸 `:84`、`CourseSaveOptions(partialWeeks = true)` 在向导裸 `:90`，两行文本一字未改、只是往后挪；复算同本条上面那对 `git show … | awk 'NR==N'`）——
  正是 §8.1 末行点名要扣掉的两类噪声；它真正的旗是 `Set<String>` 的**增删**而不是 `= true` / `= false`，且长在另一枚
  函数里 ⇒ 「体外两行以内会立旗」这句判据与那把尺**天生吃不到它**。⇒ 本条开头那对 **13 处 / 63 处**是**那把尺的产物，
  不是旗标的真实分布**（按 §8.1 末行自己给的三条「立旗 / 领闸 / 交柄」复算，这一枚旗与柄两条都占 ⇒ 真实分布应是
  14 / 62）；§8.1 那张表末行那一格不在本卡授权的两格内，T126 没动它，账记在 T126 回执。
  ⚠️ **这条订正不改变本句对别的站点的陈述强度**：本句的原始主语是「76 处里剩下的 **63 处**」，不是 §8.7④ 那 9 处，
  而 §9 只判了那 9 枚加卡面另点名的一枚（§9.6 第一条明写「没有再扫其余不立旗的 63 处起手块」）⇒ 把
  `ConflictWizardDialog` 摘出去之后，其余各处「仍可点、可重复触发」今天**仍未被逐枚复核**：本条不把它们升格成实测，
  也不降格。已经被 §9.2 逐枚判过的只有那九枚，其中「真能重复触发」覆盖 **5 处**、完全无挡只剩 **2 处**（§9.2 首句）。
  本条举的那两枚例子（HomeScreen 删除、课程管理删组那一族）**不在订正范围内**：它们的形状在 §9.2 #3 / #5 逐枚复算过，
  仍然立着（#3 的修法后来由 T121 收掉，那是 §9.3 的账，不是这句例子的账）。
- **一档完全没扫**：纯 `LaunchedEffect { … }` 里的 UI 侧旗标（本仓另一种起手，量级不小：
  仅 SpocScanScreen 那一枚文件本节就点过 10 颗 `LaunchedEffect`），`remember` 之外、
  不走 StateFlow 的裸 `var`（唯一撞见的一枚是 #9 的 `buaaTermsFetching`，本节顺手记进明留），
  `withContext(Dispatchers.X) { … }` 里嵌的写点，以及**隐式接收者的 `launch { }`**（本节没有为它写尺子，
  没数过它到底是 0 处还是几处 ⇒ 这句是"没数"，不是"没有"）。
- **仪器测试覆盖不到的路径**：#3 与 #5 的"当下是否真被用户看见"取决于**退场动画与导航跳转各占几帧**，
  这只有装机能定档（与 §4.1、§6.7 末格同一档）；#1 / #2 / #10 的"模态窗挡住入口"本节是从代码分支读出来的，
  **没有跑过任何一条真机点击序列**。本卡红线禁设备 ⇒ 这几句只到代码可达性为止。
- **普查尺自证（本节自己的连写普查）**：§7 与 §8 全文写完之后重跑 §0.4 第 4 条与 §6.10 那三格
  ⇒ 本档 `-o` **268**、全仓 `-o` **589**、本档 `-c` **146**，三格一字未动（命令见 §8.5 之外的那三条，
  已写在 §6.10 里）。本节把行号一律写成"第 N 行"或裸 `:N` + 同框符号名/原文片段，
  **没有往本档补过一枚连写** —— §6.10 那三格因此不需要重钉，这与 T111 那一趟是同一件事。
  ⚠️ **补记（T115 在这一节动了三格：§8.2 表 #5 的现状、§8.3-B 的守卫账、§8.7 明留① 划掉，外加 §8.5 第 12 条
  那条命令的现状读数）**：改完最后一次编辑之后重跑同三条命令 ⇒ **268 / 589 / 146 一字未动**
  —— 那一趟新写的行号同样全是裸 `:N` + 符号名（文件名一律不与其行号连写），本档这格没添连写。
  ⚠️ **补记（T129 在这一节动了三格：§8.1 末行那条「现状」、本条上面第一条那句「现状」、本格这条补记；
  连同 §8.7④ 那一格与 §9 那一批现状句）**：改完最后一次编辑之后重跑 §0.4 第 4 条与 §6.10 那三格，再把 §9.6
  那第四把尺一起跑 ⇒ **本档 `-o` 268 / 全仓 `-o` 589 / 本档 `-c` 146 / docs 里 `.md:行号` 24，四枚一字未动**
  （开工前后各量一遍，改前读数在 `073d098` 上同样是这四枚）。本卡新写的行号**全是裸 `:NNN` + 同框符号名或
  原文片段**（两枚删除入口那几处、`ConflictWizardDialog` 那枚旗的起讫、两枚 `SnackbarDuration.Long` 那扇窗），
  文件名一律不与其行号连写 ⇒ 两把 `.kt` 尺与两把 `.md` 尺都没理由挪。**这一条补的就是 §9.6 / §8.5 那两格
  早就写成惯例的"一对四尺读数"—— T126 那一趟把它量到了、落在自己的回执里却没往本格补，本卡补齐这一笔**
  （复算四条命令原样：`grep -rhoE '[A-Za-z0-9_]+\.kt:[0-9]+' docs/derived-field-audit.md | wc -l`、
  同一条把路径换成 `docs README.md`、`grep -cE '[A-Za-z0-9_]+\.kt:[0-9]+' docs/derived-field-audit.md`、
  `grep -rhoE '[A-Za-z0-9_]+\.md:[0-9]+' docs README.md | wc -l`）。
- **没跑门禁**：零 gradle、零 adb、零设备、零 `local.properties`，所有结论只来自 `grep` / `awk` / `git log`。

### 8.7 明留（按红线一枚没动，逐条带锚点）

| 编号 | 位置（锚点） | 该改什么（不写方案细节，等排卡） |
| --- | --- | --- |
| ① ~~本遍唯一一枚当下发作的~~ ⇒ **已收（T115① 修 main、T115②/②′ 立守卫；文档这一格只划掉、不删字）** | IClassLoginScreen 那枚文件：`:82` 立旗、`:87` 与 `:89` 与 `:90`（成功档那三行）、`:160` 那颗按钮的字、`:101-103` 那支 `when` | ~~**本遍唯一一枚当下发作的**：登录成功那一档不落 `submitting` ⇒ 同一列两行话互相打架（「已登录…正在进入扫码页…」 vs 「正在登录…」）。形状与 §6.3 那枚编辑器改前**同一族**（一次生产的两半被写到两处、其中一处漏了），差别只在窗口是退场那几帧。零守卫（§8.5 第 12 条 ⇒ 0 行）~~ **现状（在基点 `34fadd8` 之后，改前那三格读数与档位原文全留着）**：收的是**判定点**而不是那枚旗 —— 三枚裸旗从此只在一枚 `val phase = when { … }` 阶段梯里被读成界面话，状态句、那颗按钮的字、失败卡的语义色三处都投影自它（新起一枚 `private enum class LoginPhase`，成功那一档按钮说「已登录」）。"成功档补一句 `submitting = false`"那一支**驳回**：`submitting` 同时是本页唯一的重入闸与三处 `enabled` 加键盘短路的共同来源，落旗等于在这一页交出去之前（`LocalAnimatedVisibilityScope` ⇒ 存在这段窗口；⚠️ 有多长今天禁设备、没量过）松开表单、允许第二趟登录 POST，而它一旦失败就写 `serverMessage`、把「已登录」那句染成失败卡。守卫：`IClassSignInWiringGuardTest` 第 ⑦ 枚（该类枚数 6 ⇒ **7**；四组格子 —— 阶段梯的序与四档逐字、三处投影都不许再读裸旗、Saved 那一档的措辞两边都不许撞、那对不对称的闸门），六臂变异全红（朝宽三臂 W1 把 `Text(if (submitting) …)` 放回来 / W2 换梯序 / W3 成功档落旗，朝紧三臂 T1 失败档不落旗 / T2 Saved 措辞撞成在飞那句 / T3 兜底那一档落错阶段值）。**这一臂 T3 逼出一格新账**：`occurrences(梯子里的阶段值) == 4` 那把尺只数枚数、不数落点，四档改成逐字钉才抓得到（本节 §8.5 那几把"数枚数"的尺在下一遍都要按这个教训重看一遍） |
| ② ~~这一格今天一行守卫都没有~~ ⇒ **已收（T116 两枚判据；main 一字未动、零新建文件，文档这一格只划掉、不删字）** | VM 的 `confirmCalendarSync`（`:1425` 起协程、`:1426` 只写 `syncing = true`）与 `removeSyncedEvents`（`:1481` 起手清了两枚、没清 `diff`/`skipped`） | ~~**同一枚 data class 的第三、四条链的起手形状**。`:1389` 与 `:1481` 已经证明"起手成对清"这刀在这两枚文件里做得出来；剩下的问题不是"今天漏不漏"（都不漏，机制见 §8.4-A/B 那道模态窗），而是**这两格今天一行守卫都没有**：`CalendarSyncDiffClearPairingGuardTest` 那枚判据数的是 `diff = null` 清点点、`CalendarSyncTargetPairingGuardTest` 那族数的是 `message = null` 与旗标 —— `:1426` 一枚都不在它们宇宙里（§8.5 第 13、14 条给出入口唯一性）。建议按 §6.8①/⑥ 那两格的形状补第三份守卫实例~~ **现状（T116，在基点 `22d1a55` 上量；上面那段建议与四格读数全留着）**：两枚判据**分头落进既有那两枚文件**，没有新建第三份守卫实例（"补第三份文件"那一支按卡面红线驳回）——#1 归 `CalendarSyncTargetPairingGuardTest` 第 ⑪ 枚（枚数 10 ⇒ **11**，③那一族的第三条链起手形状），#2 归 `CalendarSyncDiffClearPairingGuardTest` 第 ③ 枚（枚数 2 ⇒ **3**，那一对的清点宇宙本来就是它的）。判据形状一律**逐处 / 按位置 / 逐字**，不吃 count：三枚写点按宿主函数体切块取实参表、`:1426` 与 `:1481` 各钉一枚逐字实参表、成对清与 `diff = computed.first` 钉**同宿主＋先后**、#2 那枚文件把移除链的入口钉**在两扇窗之外**（两格：`theRemoveChainClearsNeitherDiffNorSkippedBecauseTheTwoModalsHoldEachOtherOff` 体内 `:265` 那枚 `assertFalse` 吃 `at in diffAt until diffEnd`、`:273` 那枚吃 `at in removeAt until removeEnd`）＋ `dismissCalendarSyncDiff()` 的两枚收场路钉**在 diff 窗之内**（一格：同一枚判据体内 `:288` 的 `assertTrue`）〔⚠️ 这一头 T116 那一版**曾写着**「两扇弹窗窗口钉**互不重叠**」，**那一格 T118 已判成恒真删掉** —— 旧句子按本仓规矩留着不抹，恒真的机制账写在该判据 KDoc 第 2 条、复述见 §8.4-B；它跟运行期那句「两扇窗彼此挡住对方的入口」不是一回事，后者仍然成立〕、界面入口钉**在窗口之内／之外**、`confirmCalendarSync` 的引用面钉**全仓 main 树扫**。两侧七臂实测（`A`/`B`/`C`/`C2`/`D`/`E`/`G`）全部红，其中 `C`（成对清抽进 helper）与 `C2`（搬到 `computeDiff` 之后）**只红第 ⑪ 枚那一枚判据**——那正是 T115②′ 那一格警告的"枚数对、落点错"形状，也是本节 §8.5 那批"数枚数"的尺子在下一遍要照这个教训重看的东西。**main 侧两格都判"不动"**：理由、代价与"哪一行被谁改掉就会红"写在 §8.4-A/B 那两段「现状」里（一句摘要：清旗标=宣布已授权，confirm 的凭据只是传递性的，而 `:1481` 补收那一对是拿「移除」去作废一份没确认的 diff）。本节 §8.6 第 4 条那句"没有装机证据"对这一格同样不变 |
| ③ | UpdateCheck 的 `install()` 三条失败支（`:340`、`:357`、`:366`）与 `pendingInstall` 的唯一读者 `retryInstall`（`:189`）、UpdateDialog 的 `:83` 那一档 | `pendingInstall` 与 `_state` 的成对关系今天由**弹窗分支**兜着（§8.4-D）。谁往 `Failed` 那张卡上补一颗「重试安装」，这一格当场变真漏清（拿陈旧 `(info, file)` 去装一个可能已经不存在的包）。要么起手清，要么把"靠哪一档不念旧账"写进 KDoc（这正是 §6.8③ 当年那句原话的第二份实例） |
| ④ | HomeScreen 的 `:420` / `:442` / `:452`、CourseManagementScreen 的 `:181` / `:214`、ConflictWizardDialog 的 `:79`、ImportScreen 的 `:151` / `:172` / `:485` | 这 9 处起手块**一枚旗标都不立** ⇒ 操作飞着的时候那颗按钮仍可点、可重复触发（与 §8.6 第二条合起来读）。**本节没给它们判档**，因为本节的判据是"对岸清没清"，不是"该不该有旗"。要收这一族得先立判据（哪些操作值得按灭、哪些按"重复提交比吃掉更糟"的口径走 SignInViewModel 那套闸门） —— 这是**下一轮排卡的唯一来源里最大的一块**，本节按"一枚没动"如实挂着。**现状（T120 已判档，见 §9；上面那整段断言与那九枚行号原样留着，它们量的是 `ee68e23` 的盘面，§9.1 末格在 `b84248f` 上逐枚复算 ⇒ 九枚全部未漂）**：判据已在 §9.0 立起来（抄的是 `SignInViewModel` 那颗 `flight` 闸门 KDoc 的既有口径"按灭 > 排队"，摊成三问：① 有没有别人替它守着 ② 第二枚落下来用户当场看见什么 ③ **按灭挡不挡得住** —— 跨对象 LIFO、scope 被取消、退场那 300 ms 三族它挡不住）。逐处判档在 §9.2：**收 1（#3 长按菜单删除那族）· 不收 7 · 待真机 1（#9 那枚分享面板）**，§9.3 给了 T121–T126 六枚后续卡。**上面那两格断言按实测要各收窄一处**：(a) 九枚里 `ConflictWizardDialog` 那枚**今天立着全仓最完整的一族旗**（弹窗层 `remember` + `enabled = !pending` + 「写入中…」文案 + 起讫配对四处读者同源），"一枚旗标都不立"对它是**不实**，它反过来是本节给的第三枚范本；(b) 本节点名的"口令导入 `OutlinedButton`"**名实不符** —— 锚点 `:485` 落在「分享本课表（口令）」那颗，「口令导入」是另一枚 `Button`、UI 侧不起协程且过 `withImportLock`，严格说不算起手块。**"最大的一块"这一句自 T120 起不再成立**：九处收成一枚半，另有三族的病（撤销无身份 / 一次换色两次取锁 / 六处协程挂 composition scope）**按灭挡不住**，比补旗更值钱 —— 旧句子留着不抹，账在 §9.4 那三条驳回与 §9.5 那五条明留。**另复核一条：上面那句"走 SignInViewModel 那套闸门"的指针实测为真**，闸门今天就在 ViewModel 里（`flight` / `inFlight` + `signIn` 首行早退 + `finally` 归还），T85 拆掉的是 `alreadySigned` 那一档，不是它 ⇒ 本档这一格不欠订正。**现状（T126 收 §9.5④ 登记给本卡的那三件事：上面 T120 那段现状句一字未抹；那三件事 T120 说全了一件、缺两件，本卡补齐缺的那两件、并把一格比较级压低）**：（一）**驳回② 那一件 T120 只判了「对它是实」、没写收窄后的枚数** ⇒ 补：**九枚里不立 UI 旗的是 8 枚，不是 9 枚**（旁证尺在 §8.6 第二条那条「现状」里，T126 在基点 `efd6fb7` 上重跑过：这九处的控件宿主文件里 `enabled` 一共只有 HomeScreen 2 行 `:1034`/`:1076`、WeekView 1 行 `:1175`（那是 `BackHandler`、不是按钮闸）、CourseManagementScreen **0 行**、ImportScreen 3 行 `:354`/`:481`/`:662`，没有一行落在这九处点名的那颗控件上；立旗的只有本行 #6 那一枚）。（二）**上面那句「全仓最完整的一族旗」T126 复核判为比较级过头**：按读者枚数排第一的不是本枚 —— 同一把尺（数旗名在宿主文件里的命中行）`grep -nE '\bsaving\b'` 在 CourseEditorScreen 那枚文件 ⇒ **11 行**（声明 `:129`、起讫**两对** `:231`/`:247` 与 `:614`/`:624`、`enabled = !saving` `:570`、文案 `Text(if (saving) "保存中..." else "保存")` `:310`、折进纯判据实参表 `:172` 与 `editorCanSave` 本体 `:780-781`、外加 `BackHandler(enabled = isDraftDirty && !saving)` `:256`），而 `grep -nE 'pendin'` 在本枚那枚文件 ⇒ **8 行**、扣掉两枚形参传递（`:138` 往下传、`:163` 往里收）是 **6 处读者**。⇒ 这一格真正独一份的是**另一维**：全仓唯一一枚**按课程身份 keyed** 的在飞旗（`setOf<String>` 按 `wizardKey()` 索引、记在弹窗层 `:119`，注释 `:117-118` 明写记在行内会被行重建复位；撑这一句的尺：`grep -rnE 'by remember \{ mutableStateOf\(setOf' app/src/main/java --include='*.kt'` ⇒ **2 行**、全在同一枚文件里，另一枚 `:116` 是「已应用」标记而不是在飞旗）＋ 终点带失败归还（`:142-143`）＋ 写库落在 `viewModelScope` 再 `job.join()`（`:79` 与 `:85`，KDoc `:63-66` 把理由写成 P1）⇒「第三枚范本」这一判**不变**，「最完整」那一判按本条收窄（§9.3 T121 那一行照抄的是旧比较级，那一格本卡没动，账记在 T126 回执）。⚠️ 顺带一格机制账：本枚之所以在 §8 那一遍被判进「不立旗」那一边，是因为那把池子尺只吃 `= true`/`= false`/`compareAndSet(`，而它命中的两行 `:78` `var saved = false` 与 `:82` `CourseSaveOptions(partialWeeks = true)` 全是噪声，真正的旗是 `Set<String>` 的增删且长在另一枚函数里 —— 完整账写在 §8.6 第二条的「现状」。（三）**驳回③ 那一件 T120 只给了名、没给锚点** ⇒ 补：`:485` 那枚 `scope.launch` 长在 `:483` 起的 `OutlinedButton` 里，那颗按钮的文案在 `:505`「分享本课表（口令）」；「口令导入」是 `:473` 那枚 **`Button`**（`onClick` 体 `:474-477` 里只有 `viewModel.clearImportMessage()` 与 `viewModel.importShareCode(shareCode)`，UI 侧不起协程 —— launch 长在 VM `:1271`、`:1272` 才进 `withImportLock`），`enabled = shareCode.isNotBlank()` 那枚内容闸在 `:481` ⇒ 名与实到这里对上；上面那句「口令导入 `OutlinedButton`」是**控件类型与所指的颗两处**都对不上。（四）**第三件事（「最大的一块」自 T120 起不再成立、九处收成一枚半）T120 那段现状句已经说全**：收 1 · 不收 7 · 待真机 1 与「一枚半」都在那一格里，T126 不重复写。（五）**本行那九枚锚点在基点 `efd6fb7` 上逐枚 `awk NR==N` 复算 ⇒ 全部未漂**：HomeScreen `:420`/`:442`/`:452`、CourseManagementScreen `:181`/`:214`、ImportScreen `:151`/`:172`/`:485` 逐枚读到的原文都以 `scope.launch {` 开头（HomeScreen `:442` 那一枚是把 body 写在同一行），ConflictWizardDialog `:79` 是 `val job = viewModel.viewModelScope.launch {`；`git diff --name-status b84248f..efd6fb7` ⇒ main 侧只有 T121 动过 `ScheduleRepository` 与 `ScheduleViewModel`（另新建三枚文件），这五枚 UI 文件与 WeekView 一字未动 ⇒ 本行不重钉，四把尺也没理由挪。**现状（T129 补，量于 `073d098`；上面 T120 与 T126 两段现状句一字未抹）**：上面那句「**收 1（#3 长按菜单删除那族）**」里的那一枚**已经落地** —— T121① `4dfd7d3` 在仓储层给 `deleteCourse` 补上"到底删没删到"（事务内先按 id 把行读回来，判据原文 `if (deletion.removedAnything) deleteCourseRow(rows.first())`）、T121② `cb9f599` 让 VM 删不到时报 `false` 且不压撤销栈（那行注释原文「删不到 ⇒ 不报成功、不压栈」）、T121③ `d4c6c51` 钉住 9 枚表驱动 + 6 枚接线 ⇒ §9.2 #3 那格"phantom 落点"那半句从今天起是**改前读数**（完整现状句写在那一格末尾与 §9.3 T121 那一行）；同一族的第二枚（管理页删整组那颗被丢掉的 Boolean）由 T127① `970282a` 收（见 §9.2 #5 与 §9.5③ 两处现状句）。⇒ **"收 1 · 不收 7 · 待真机 1"作为 T120 的判档读数原样保留**，只是"收"这一档今天已有 main 侧落点、不再是一张空卡；⚠️ 本行(九枚锚点)与 §8.6 那九处的"不立 UI 旗"那一格**照旧成立**：T121 与 T127 都没给那颗删除补 UI 旗（复算 `grep -n 'enabled' app/src/main/java/com/buaa/schedule/ui/home/HomeScreen.kt` ⇒ 仍 2 行、两枚都是周翻页箭头；`CourseManagementScreen.kt` ⇒ 仍 **0 行**），本行那九枚落点本轮逐枚 `awk 'NR==N'` 再读 ⇒ 全部未漂。**现状（T132 重钉，量于 `bd3245e`；上面 T120 / T126 / T129 三段现状句一字未抹，那句「九枚全部未漂」在它自己的基点上仍是真的）**：T131 `5f39a4d` 往本行点名的向导文件净插 9 行（269→278，窗口起点 pre `:69` 那句 KDoc「写的是 [CourseSaveOptions.partialWeeks]：只改冲突的那几周」）、往首页净插 4 行（1364→1368，窗口起点 pre `:819`）⇒ **本行那九枚里有两枚不再成立**：① 九枚里向导那一枚 `:79`（`val job = viewModel.viewModelScope.launch`）今天落在裸 `:87` ⇒ 上面「ConflictWizardDialog `:79` 是 `val job = viewModel.viewModelScope.launch {`」这句按 `:79` 找回去会撞空（今天 `:79` 那行是 `viewModel: ScheduleViewModel,`，一枚形参）；② 本行 T126 现状（一）里那两枚首页 `:1034`/`:1076` 今天落在裸 `:1038`/`:1080`（旁证尺就一条：`grep -n 'enabled' app/src/main/java/com/buaa/schedule/ui/home/HomeScreen.kt` ⇒ **仍 2 行**，行号是 1038 与 1080）。**本行其余各枚按同一把窗口尺逐枚判**：九枚里首页那三枚 `:420`/`:442`/`:452` 全在首页窗口之前 ⇒ 未漂（本行 T126 现状（二）另点名的 `:116` 是「已应用」标记那一行、`:138` 与 `:163` 是两枚形参传递，三枚都在窗口之后 ⇒ 今天依次裸 `:125`、`:147`、`:172`）；向导其余各枚今天依次是 `job.join()` 裸 `:93`、`var saved = false` 裸 `:84`、`CourseSaveOptions(partialWeeks = true)` 裸 `:90`、「已应用」标记 `var shiftedCourses` 裸 `:125`、注释那两行裸 `:126-127`、在飞旗声明裸 `:128`、往下传形参裸 `:147`、起点 `onShiftStart` 裸 `:148`、终点 `onShiftEnd` 裸 `:150`、失败归还裸 `:151-152`、往里收形参裸 `:172`、`val pending = courseKey in pendingCourses` 裸 `:182`、`enabled = !pending` 裸 `:222`、文案那一行裸 `:229`；KDoc 那三行裸 `:63-66` 在窗口之前 ⇒ 未漂；CourseManagementScreen / ImportScreen / CourseEditorScreen / WeekView 那几枚不在本三枚文件里 ⇒ 一字未动。**「8 枚不立 UI 旗」「第三枚范本」「名与实对调」这三判都不变**，变的全是行号 |
| ⑤ | OnboardingScreen 的 `:144`（起手只立 `checking`）与 `:476` / `:482` / `:489` 三处读者 | 判 **deferred**（先例 #117）：旧结论文字在重跑期间仍然读得出来，但同一屏那颗按钮自己写着「正在检测…」、每张卡的 Fix 入口被 `enabled` 按住 ⇒ 代价=措辞，本遍不值。谁哪天把 `enabled = !checking` 那一族去掉，这一格自动升级 |
| ⑥ | VM 的 `refreshBuaaTerms`（`:701` 声明、`:709` 立旗、`:718` 归还） | 第四档那一枚。要留意的不是漏清，是**这枚旗是裸 `var`、没有界面读者**："正在拉学期列表"这件事用户永远看不见，而 `:373` 那段注释已经把它当反面教材点名过一次（"旧写法的 buaaTermsFetching 就是这个形状"）⇒ 下一遍若要给它换 StateFlow，记得同批看 `:708` 那记双条件短路要不要跟着改 |

### 8.8 卡面对账：四把尺复现、一句要驳、两处本节自己踩到

**复现（带命令与读数）**：`viewModelScope.launch` **35** ✅、`scope\.launch|coroutineScope\.launch` **14** ✅、
`_[a-zA-Z]+\.update \{` **21** ✅、`MutableStateFlow(` **5** ✅ —— 四条**照抄卡面命令**都能一字不差复现。
§7 那八处行号（拆开十三枚指针）也逐枚复现，账在 §7.1。

**驳回 ①（"第四把尺给的数不可用"）**：卡面用 `MutableStateFlow(` 数"有几枚状态"，读到 5，本节**照抄也对上 5**，
但这一格本节必须驳：**这把尺漏掉了全部带泛型实参的声明**（`MutableStateFlow<AppMessage?>(null)` 那种写法一行都不命中），
真正数声明的尺（§8.5 第 7 条）给 **14** ⇒ **漏 9 枚**，而漏掉的正是这一族最该被数的
`_importMessage` / `_pendingImport` / `_pendingBackup` / `_pendingEmptyRestore` / `_specialDays` /
`_state` ×2 / `_buaaTermOptions` / `specialDayVisibleMonths`。**这一条对本节的直接影响**：
本节判的 #3、#7、#10 三枚旗标里，只有 `_buaaRefreshing` 与 `flight` 长在卡面那 5 枚里，
其余全在那 9 枚里 —— 拿卡面那把尺起手，这三枚候选根本不会出现。
**下一遍若要复用这四把尺，请把第 4 条换成第 7 条。**

**驳回 ②（宇宙只盖住 64%）**：卡面那两把 `launch` 尺是**大小写敏感**的，`[A-Za-z]Scope\.launch` 去掉
`viewModelScope` 之后还有 **27** 处（§8.5 第 2 条），全集是 **76**（第 3 条）。这 27 处里本节真读到立旗的
只有 `downloadScope.launch` 那两处（候选 #10）—— 也就是说**换一把尺就多一枚候选**，
"卡面那 49 处扫完了"与"起手宇宙扫完了"是两件事。⚠️ 本节**没有**声称这 27 处判完：
`animationScope` 那 12 处是动效驱动协程，本节只确认它们不立业务旗标（读原文，未程序化配对）。

**本节自己踩到的两处（如实写，别让它变成下一轮的"读数不一致"）**：
1. §8.5 第 8 条那把"候选池"尺吃的是 `launch` 之后**三行**，而 `startCalendarSync` 起手那次成对清被 KDoc
   推到体外第 5 行 ⇒ 单跑池子命令会把全仓最标准的那一枚起手漏掉。13 处里含它，靠的是逐枚读原文。
   ⇒ 下一遍别拿池子命令的 28 当答案，那只是**噪声占多数的候选面**。
2. 本节一度把 `cameraError` 那两枚字段在 main 侧的落点数成 16，重跑 §7.4 第 3 条得 **19** 才改过来 ——
   写进 §7.4 的是复算之后的读数（分解表同格）。**这就是 §0.4 第 4 条那句"数字旁边必须带命令"的用途。**

## 9. T120 第四遍·§9：起手块**有没有闸**这一遍（评估卡 · 只判档不改动）

分支 `ai/T120`，基点 `b84248f`（§8 那一族之后主源码未动）。**零 main 改动、零测试改动、零 gradle、零 adb、零设备**；
全部读数来自 `grep` / 逐枚读原文。**本节新增文本一枚 `文件.kt:行号` 连写都没有**（自证见 §9.6 末格），
§8.7④ 那九处的行号在本节基点上逐枚复算**一字未漂**（复算账在 §9.1 第 6 条），因此本节既不钉新锚点、也不需要重钉旧锚点。

### 9.0 这一遍问的问题，与"立判据"那句话（§8.7④ 欠的债）

- §8 问的是**起手块清没清对岸**（同一次操作的上一个产物有没有被收回去）。
- **本节问的是同一批站点的另一枚问题：这一次操作在飞的时候，那颗控件还点不点得动。** §8.6 第二条已经把话说到门口就
  停住了 ——「那是**缺旗标**，与本节判的'起手漏清对岸'不是同一族病，本节不给它们判任何一档 —— 要排卡得先立判据」，
  §8.7④ 把这句话接成"下一轮排卡的唯一来源里最大的一块"。**本节就是去还这一格：先立判据，再逐处判档。**

**判据不是本节发明的。** 全仓今天只有**一处**把它写成了原文，在 `SignInViewModel` 那颗 `flight` 闸门的 KDoc 里
（它讲的是扫码签到，但口径是通用的）：「签到这件事重复提交比"这次没吃进去"更糟，而**排队只会把同一张码再投一次**」，
下一句是「修法是**让动作本身反映实情（按钮在忙的时候点不动）**，不是排队」。⇒ 本仓的既定口径是
**"按灭 > 排队"，且按灭的理由是"别把用户这次点击吃掉得无声无息"**。本节把这一句摊成三问，按顺序问：

1. **这道闸今天是不是已经有别人替它守着了？**（四种间接闸都算：`enabled =` 条件、点一下就自关的载体
   （确认框/浮层/把手在 invoke 之前自己置 `null`）、系统选择器一次只回一枚 uri、**仓库层的互斥锁**）
   ⇒ 有，且第二问答"否" ⇒ **不收**。
2. **第二枚真的落下来，用户当场看见的谎是不是就生效了？** 判据是"指得到落点的代码"，不是"感觉会乱"：
   写库返回值被丢掉、snackbar 那句措辞、撤销捞错对象、面板多出一枚。指不到 ⇒ 老实写"本遍没找到落点"。
3. **按灭挡不挡得住它？**（这一问是本判据的核心分辨，也是本节唯一新增的东西）——**「按灭」只挡同一枚控件的重复触发**，
   它挡不住这三族：**(a) 跨对象**（两条链各写一门课，后果在 LIFO 撤销栈上交错，按钮各按各的灭）；
   **(b) scope 被取消**（`rememberCoroutineScope()` 绑 composition，页面一划走协程就死在两次取锁中间）；
   **(c) 退场动画那 300 ms**（`ModalTransition` 的挂载闸门语义决定收场期间内容**仍然挂着、仍然吃点击**，
   置 `null` 只是不再进场）。凡后果落在 (a)(b)(c) 的 ⇒ 判**不收**，理由必须写成"按灭无效，修法在别处"，并登记到 §9.5。

三档定名（与 §6/§8 的四档**不通用**，本节这一族只有三档够用）：
**收** = 三问全走到底（今天无闸 · 后果当场可见 · 只有按灭挡得住）；
**不收** = 第 1 问或第 2 问答"否"，或第 3 问判"按灭挡不住"（**这是完全合法的收法**，先例 #117"真的但不值钱"、
#114"测试台产物、零代码改动"）；
**待真机** = 第 2 问的答案取决于运行期行为（Activity 任务栈、退场动画占几帧），本卡红线禁设备 ⇒ 只能挂这一档。

⚠️ **本节顺手把 §8.7④ 那句指针复核掉了，它是对的**：卡面怀疑"那套闸门其实在 `IClassLoginScreen` 而不在 ViewModel"，
实测**在** ViewModel —— `SignInViewModel` 里今天**确实还立着一枚再入闸**：`private val flight = MutableStateFlow(false)`
对外暴露成 `val inFlight`，`fun signIn(raw: String)` 第一行就是 `if (flight.value) return`，`finally` 里归还，
而界面侧 `enabled = !inFlight` 读的是同一枚值（复算见 §9.1 第 4 条）。它被 T85 拆掉的是**另一族**
（`alreadySigned` 那一档，注释原文在 sealed 族里，本节读过、不动它）。⇒ 文档指针不欠订正。

### 9.1 复算命令与读数（卡面三条照抄、本节三条补尺）

```
# 1  卡面尺①：Job 句柄那一族（⇒ 2 行，与卡面给的"两枚站点"一字不差）
grep -rn '?\.isActive == true' app/src/main/java --include='*.kt'
#    UpdateCheck 的 startDownload 与 retryInstall 各一枚（裸 `if (downloadJob?.isActive == true) return`）
# 1b 换一把宽尺才看见全貌（⇒ 9 行 / 5 枚文件）：这把尺把第二枚 Job 闸漏了
grep -rn 'isActive' app/src/main/java --include='*.kt' | wc -l
#    其中"再入闸"形状共 **3 枚站点**：UpdateCheck 那两枚 + ScheduleViewModel 的
#    `if (job.isActive) {`（buaaRefreshJob 那一族，§8.2 #4 判过它的读者）。它不带 `?.` 也不带 `== true`
#    ⇒ **卡面那把尺天生吃不到它**。剩下 6 行是 `continuation.isActive`（两处）/ `DayView` 的
#    while 自旋 / `SettingsScreen` 一枚同名局部 val / `import kotlinx.coroutines.isActive` 那行 import
#    ⚠️ 现状（T129 在 `073d098` 上把上面那条与它配套的枚文件尺各重跑一次 ⇒
#       `grep -rn 'isActive' app/src/main/java --include='*.kt' | wc -l` = 9、
#       同一条去掉行号后 `sort -u` = **6**）：**9 行一字未动、枚文件是 6 不是 5**（T126 报的，本卡复核成立）。
#       漏的那一枚长在 BuaaInPageFetcher 那颗 `cont.isActive` 上（原文
#       `if (cont.isActive) cont.resume(decodeJsString(value))`，裸 `:281`）—— 上面那句分解其实
#       把"continuation.isActive（两处）"的**两行都数进去了**、却没把第二处那颗文件数进"5 枚文件"；
#       同一句里 `SettingsScreen` 只点了"一枚同名局部 val"、实际吃两行（裸 `:488` 的 `val isActive =`
#       与裸 `:504` 的 `if (isActive) {`）⇒ 9 = 再入闸 3（UpdateCheck 两枚 + VM 一枚）+ cont.isActive 2
#       + while 自旋 1 + SettingsScreen 2 + import 1。**"3 枚再入闸站点"这一判不变**、下面 §9.2 那九处
#       也不引用这一枚文件 ⇒ 本条只是把"5 枚文件"这一格改口，四把普查尺与本节其余读数没理由挪。
#    ⚠️ 顺手把同一段第 4 条那把尺也复算一次：`grep -rn 'enabled = !' app/src/main/java --include='*.kt'`
#       ⇒ **13 行 / 8 枚文件**，`073d098` 上一字未动（T115/T116/T121/T122/T127 五趟都没碰过这把尺的面）。
# 2  卡面尺②：UI 本地旗那一族（⇒ 12 行 / **1 枚文件**，全在 IClassLoginScreen，含 3 行注释）
grep -rn 'submitting' app/src/main/java --include='*.kt'
grep -rln 'submitting' app/src/main/java --include='*.kt' | wc -l   # ⇒ 1
# 3  仓库层那把锁（⇒ **8 枚调用点**，定义不算、`return@withImportLock` 早退不算）
grep -n 'withImportLock {' app/src/main/java/com/buaa/schedule/ui/ScheduleViewModel.kt
# 4  卡面没给的补尺：全仓"操作在飞就按灭"的界面读者（⇒ 13 行 / 8 枚文件）
grep -rn 'enabled = !' app/src/main/java --include='*.kt'
# 5  退场窗口的长度（⇒ 300，判据第 3 问 (c) 那族的量）
grep -rn 'DURATION_DIALOG_EXIT *=' app/src/main/java --include='*.kt'
```

**第 3 条那 8 枚调用点包住的链（逐枚点名，这是"重复触发到底会不会并发写"的唯一凭据）**：
`importCourses`、`confirmPendingImport`、`refreshFromBuaa`、`previewBuaaCourses`、`importIcs`、`importText`、
`importBackup`、`importShareCode`。**九处里最终经过它的只有两处** —— `importIcs`、`importText`
（那两枚 SAF 回调的落库口）。**`updateCourse` / `deleteCourse` / `deleteCourseGroup` / `undo` 一条都不过它**，
它们只过 `ScheduleRepository` 那把 `writeMutex`。

⚠️ **这把锁的形状决定了它挡什么、不挡什么**（原文：`if (!importMutex.tryLock()) { _importMessage.value =
AppMessage("已有导入正在进行，请稍候"); return null }`）：它是 `tryLock` **不是 `withLock`** ⇒
第二枚触发**不排队、直接吃一句人话回绝**。而数据层那把 `writeMutex` 用的是 `withLock` ⇒ **排队**、
两笔都落、后写的赢。这两把锁一把拒一份排，正好是 §9.0 那句"按灭 > 排队"在仓库层的两份不同实现。

**第 4 条那 13 行的分档**（本节把"是不是在飞旗"逐枚读了原文）：真正"操作在飞 ⇒ 按灭"的是 **9 族**，
卡面说的"只有两枚"**不成立**（账在 §9.4 驳回①）——
`CourseEditorScreen` 的 `saving`（起讫两枚写点 + `enabled = !saving` + 那句 `if (saving) "保存中..."`，
**还额外折进了 `editorCanSave` 的实参表**）、`ConflictWizardDialog` 的 `pendingCourses`、
`SpocScanScreen` 的 `inFlight`（读的是第 5 条那枚 StateFlow）、`TermAndCampusBar` 的 `refreshing`
（**这一枚顺手补了 §8.2 #4 的空格**：`_buaaRefreshing` + `buaaRefreshJob` 那两枚字段的界面读者原来没点过名，
它就在闸里面）、`SettingsScreen` 的 `enabled = !calendarSync.syncing`（§8.2 #1 那道闸）、
`OnboardingScreen` 的 `checking`（§8.7⑤ 已判 deferred）、`IClassLoginScreen` 的 `submitting`（卡面范本②）、
`SignInViewModel` 的 `flight`。
**⚠️ 这一把尺一行都不吃卡面范本①**：`grep -rn 'enabled' app/src/main/java/com/buaa/schedule/update/ | wc -l`
⇒ **0** —— `UpdateCheck` 那一族**根本没有按灭**，它的第二层是 `when (shown)` 的**分支覆盖**
（`Downloading` 那一档换 `DownloadingDialog`，`confirmButton = {}`，那颗「立即下载」整枚不存在）。
⇒ 卡面把它叫"按灭范本"**名实不符**，账在 §9.4 驳回①。
**不是闸门的是两枚内容闸**：`WidgetConfigActivity` 的 `enabled = !isDefault`、`SettingsScreen` 那枚
`enabled = !preview.versionTooNew` —— 与卡面点名的 `enabled = shareCode.isNotBlank()` 同类（那是**内容闸**，
它管"输入够不够"，不管"上一次飞没飞"），本节按卡面那句原样承认这一条判据。

**第 6 件必记的事：九处的行号在本节基点上逐枚复算，全部未漂。** 拿 `awk 'NR==N'` 逐枚读原文与 §8.7④ 同框的
符号名对账 ⇒ `HomeScreen` 三枚（拖拽 / 缩放 / 删除那三条链的 `scope.launch`）、`CourseManagementScreen` 两枚、
`ConflictWizardDialog` 一枚、`ImportScreen` 三枚，**九枚全部一字不差**（T115 之后这五枚文件没动过 main）。
⇒ 本节沿用 §8.7④ 的行号不写"按 T120 盘面复算的新值"，四把尺因此没有挪动的理由。**现状（T132 重钉）**：上面那句「T115 之后这五枚文件没动过 main」与「九枚全部一字不差」自 `5f39a4d`（T131）起**不再成立** —— T131 往这五枚里的三枚 main 净插 17 行（向导 269→278 窗口 pre `:69`、首页 1364→1368 窗口 pre `:819`、统计页 1100→1104 窗口 pre `:298`），九枚里向导那一枚（`val job = viewModel.viewModelScope.launch`）今天落在裸 `:87`，长注写在 §8.7④ 那一格与本节末格那一行内；九枚里首页那三枚与其余五枚仍逐枚复算未漂（三枚首页锚点都在窗口之前）。**本档那四把尺仍然一字未动** —— 这一族重钉全部写成裸 `:NNN` + 同框原文，一枚连写都没添（复算 `grep -rhoE '[A-Za-z0-9_]+\.kt:[0-9]+' docs/derived-field-audit.md | wc -l` 与 `grep -rhoE '[A-Za-z0-9_]+\.kt:[0-9]+' docs README.md | wc -l` 与 `grep -cE '[A-Za-z0-9_]+\.kt:[0-9]+' docs/derived-field-audit.md` 与 `grep -rhoE '[A-Za-z0-9_]+\.md:[0-9]+' docs README.md | wc -l` ⇒ 268 / 589 / 146 / 24）。

⚠️ **补记（T129，量于 `073d098`）：上面那六条尺原样重跑，除 1b 那格的"枚文件"之外全部一字未动** ——
第 1 条 **2 行**、第 1b 条 **9 行**（枚文件 **6**，订正见上面那条现状注）、第 2 条 **12 行 / 1 枚文件**、
第 3 条 **8 枚调用点**、第 4 条 **13 行 / 8 枚文件**、第 5 条 **300**。第 6 件那九处行号本轮也逐枚
`awk 'NR==N'` 再读一遍 ⇒ **九枚全部未漂**（HomeScreen `:420`/`:442`/`:452`、CourseManagementScreen
`:181`/`:214`、ConflictWizardDialog `:79`（T132 重钉：T131 在向导 269→278 净插 9 行、窗口起点 pre `:69` ⇒ 今天 `val job = viewModel.viewModelScope.launch {` 那行落在向导裸 `:87`，按 `:79` 找回去会撞空 —— 今天 `:79` 那行是 `viewModel: ScheduleViewModel,`，一枚形参；复算 `git show HEAD:app/src/main/java/com/buaa/schedule/ui/home/ConflictWizardDialog.kt | awk 'NR==87'`）、ImportScreen `:151`/`:172`/`:485` 读到的原文仍以
`scope.launch {` 或 `val job = viewModel.viewModelScope.launch {` 开头）。理由要说清：T121 与 T127 只动过
三枚 main 文件（仓储层那枚、VM 那枚、管理页那枚），**后两枚都是同行改写** —— VM 仍 **1,693** 行
（`wc -l app/src/main/java/com/buaa/schedule/ui/ScheduleViewModel.kt`）、管理页仍 **451** 行
（同一条换成那枚文件）⇒ 指向这两枚文件的裸 `:NNN` 一列不漂；本轮新点名的那些落点（两枚删除入口、
两枚 `SnackbarDuration.Long`、那枚旗的起讫）一律写成**裸 `:NNN` + 原文片段同框**，四把普查尺也没理由挪。

### 9.2 九处逐处（Q1 有没有闸 · Q2 后果落点 · Q4 档位）

先给一句本节最重要的读数：**「这 9 处一枚旗标都不立 ⇒ 那颗按钮仍可点、可重复触发」这一句在 9 处里只覆盖 5 处**，
其中**完全无挡的只剩 2 处**。剩下 3 处各剩一枚 300 ms 退场窗，另外 4 处（含被点名的那枚向导）**今天结构上就点不动**。

| # | 处（符号 + 起手块锚点） | Q1 今天有没有闸（点名哪一种） | Q2 第二枚真落下来，用户看见什么（指得到才写） | Q4 档位 |
| --- | --- | --- | --- | --- |
| 1 | `handleCourseMove` 拖拽改日 —— `scope.launch` 起在 HomeScreen `:420`（`:405` 声明） | **无 UI 旗**；**两道间接闸**：① **自关载体**——拖完不直接落库，先收进 `pendingMove` 确认弹窗（WeekView `:903` 写、`:1260` 挂载），两颗按钮都在 invoke **之前**置 `null`（`:1274` 所有周 / `:1291` 仅本周 → `:1276` / `:1292` invoke），"移动到其他时间"那枚步进框同一条出口（`:1248`）；② 体外 `:413-414` 那记越界 `return@move` 静默取消；**不过仓库锁** | **指得到**：两枚触发是同值重复写 ⇒ 幂等，但 `updateCourse` 里 `if (original != null)` 那支**无条件** pushUpdate（VM `:528-537`，不看 before/after 是否相等）⇒ 栈顶压一枚 **no-op 撤销条目** | **不收**（第 1 问：载体自关；同值重写正是"用户改主意"想要的语义。那一枚 no-op 条目的修法是"同值不压栈"，不是按灭 ⇒ 转 §9.5②） |
| 2 | `handleCourseResize` 松手改节次段 —— `scope.launch` 起在 HomeScreen `:442`（`:440` 声明） | **无 UI 旗**；**两道间接闸，且是全仓唯一一枚起手块体外自己就带两道**：① `onResizeEnd` 第一句就 `resizeFor = null`（WeekView `:1109`）→ 才 `:1114` invoke，改节次那枚把手**当场不再渲染**（`resizeHandleVisible` 的判据就是它，`:1101-1102`）；② `if (merged != r.course.periods)` 同值早退（`:1113`）；**不过仓库锁** | **没找到后果的落点**：第二次触发要求**整枚新手势**（按住把手→拖→松），不是"再点一下"；且同值直接被 `:1113` 吃掉，连 #1 那枚 no-op 条目都造不出来 | **不收**（三问全"否"：有闸 + 无落点 + 按灭多余） |
| 3 | `handleCourseDelete` 长按菜单删除 —— `scope.launch` 起在 HomeScreen `:452`（`:450` 声明） | **无 UI 旗、无锁**（`deleteCourse` 一枚 `withImportLock` 都不过，见 §9.1 第 3 条）；只有**两层连着的自关载体**：详情浮层 `onDelete` 先 `detailFor = null` 再开确认框（WeekView `:1345-1347`）、确认框 `pendingDelete = null` 再 invoke（`:1317` → `:1318`） | **指得到，而且是九处里最硬的一条**：`repository.deleteCourse` 对**已经不存在的行**照删照返回（`:281-290` → `deleteCourseRow` `:371-374` 不看受影响行数）⇒ VM `.fold({ true }, …)` 把"这次什么都没删"报成 `true`（`:549-558`）⇒ `UndoManager.pushDelete` 压进一条 **phantom Delete**、snackbar 照样念「已删除「X」」+「撤销」。而那颗「撤销」调的是**无参全局** `undo()`（`:459`）→ `UndoManager.pop()` 捞**栈顶**（VM `:589-598`），回给用户的「已撤销：删除课程」念的是**栈顶那条的 label**，与这条 snackbar 是不是同一次操作**毫无关系** | **收**（唯一一枚三问走到底的：今天无旗无锁、后果当场生效、**phantom 那一半只有按灭挡得住**）。范本：`ConflictWizardDialog` 的 `pendingCourses` 那一族（`enabled` + 文案 + 起讫配对三处读者同源），不是卡面给的两枚。**现状（T121 已落地；上面那四格读数与这一句档位原文一字未抹，它们量的是 `b84248f` 的改前盘面，T129 在 `073d098` 上复核）**：`repository.deleteCourse` 今天**在事务内先按 id 把行读回来**（原文 `val rows = listOfNotNull(courseDao.getById(course.id)?.toDomain())`，落点仍是那一格钉的 `:281-290` 那几行，判据原文 `if (deletion.removedAnything) deleteCourseRow(rows.first())`）⇒ 删不到一行就交回 `removedAnything = false`，`:371-374` 那枚 `deleteCourseRow` 从此只在读回非空时才被叫到；VM 那一支跟着换成 `val removed = deletion.removedCourse ?: return@suspendCatching false`（那行注释原文「删不到 ⇒ 不报成功、不压栈」，落点仍在 `:549-558`）⇒ **phantom Delete 那一半没了**，提示条也分两支（`message = if (deleted) "已删除「…」" else "删除失败：「…」 还在课表里"`、`actionLabel = "撤销".takeIf { deleted }`，HomeScreen 那两枚读者在裸 `:455` 与裸 `:456`）。⇒ 上面那句"后果当场生效"从此是**改前读数**；**"收"这一判仍成立，但收法不是按灭**：T121 走的正是 §9.3 那一格末尾预言的那一支（"根因在仓储层缺 `deleteCourseGroup` 那道空快照早退，补它是动 main、比按灭更收口"）；**Q1 那格"无 UI 旗、无锁"一字未变**（复算 `grep -n 'enabled' app/src/main/java/com/buaa/schedule/ui/home/HomeScreen.kt` ⇒ 仍 2 行、两枚都是周翻页箭头，没有一行落在那颗删除上）。守卫账（本轮逐枚量）：`CourseDeletionPolicyTest` 名下 **9** 枚表驱动、`CourseDeletionWiringGuardTest` 名下 **6** 枚接线，复算 `grep -cE '^[[:space:]]*@Test' app/src/test/java/com/buaa/schedule/data/repository/CourseDeletionPolicyTest.kt` ⇒ 9、同一条换成 `app/src/test/java/com/buaa/schedule/ui/CourseDeletionWiringGuardTest.kt` ⇒ 6；⚠️ 该卡回执自陈**零装机级证据**，本格不替它升格 |
| 4 | `onPickColor` 给整组挑颜色 —— `scope.launch` 起在 CourseManagementScreen `:181`（`:179` 声明），`CourseSaveOptions(applyToGroup = true)` 在 `:184` | **真·无闸**（九处里两处之一）：色板选完**不收**——`onPickColor` 不写 `colorTargetKey = null`（对照 `onToggleColor` `:177` 会收），那层 `if (colorExpanded)` 一直开着（`:332`），枚枚色板 `onClick = { onPickColor(index) }`（`:348`）**没有 `enabled`**；**不过仓库锁** | **"同组碎片各走各的颜色 / 混色"这条断言本节判为不成立，指不到落点**：`updateCourseGroupAppearance` 是**一枚事务 + 一把 writeMutex** 里逐行盖**同一个色**（`:245-266`），两趟交错下来谁后跑完整谁赢 ⇒ **最后写赢、且整组一致**。真正能混色的是**另一件事**：VM 把一次"整组换色"拆成 `repository.updateCourse`（`:522`）**与** `updateCourseGroupAppearance`（`:540`）**两次独立取锁**、跨两个事务，中间被取消（这两处协程都长在 `rememberCoroutineScope()` 上，CourseManagementScreen `:100`）⇒ 只有主行变色、兄弟片段留旧色 | **不收**（第 3 问：混色的因是 (b) scope 被取消，**按灭挡不住**）。这一行另立明留 §9.5① |
| 5 | 确认框里删整组 `deleteCourseGroup` —— `scope.launch` 起在 CourseManagementScreen `:214` | **无 UI 旗**；**两道间接闸**：① 自关载体，`pendingDelete = null`（`:213`）写在 launch **之前**；② **仓储层空快照早退**——`courses.mapNotNull { getById }` 空了就 `return@withTransaction` 交回空快照（`:299-312`），VM 再 `if (snapshot.courses.isEmpty()) return@suspendCatching false`（`:571`）⇒ 第二枚**不压撤销、不谎报**；**不过仓库锁** | 后果只剩一枚：**UI 把返回的 Boolean 丢了**（`:215` 那行没有接收者、不看返回值）⇒ 第二枚仍然无条件念「已删除「X」」（`:216-220`）。但课表当场已经没有这门课了，用户无从分辨真假 ⇒ **本遍判"真的但不值钱"**（先例 #117） | **不收**（第 2 问过不了。对照 #3：**同一个仓里两枚删除，`deleteCourseGroup` 有空快照守卫、`deleteCourse` 没有**——这一族不对称才是 #3 的根因）。**现状（T121 + T127 之后，T129 在 `073d098` 上复核；上面那四格读数与"不收"这一判原文一字未抹，它量的是 `b84248f` 的盘面）**：① **那枚不对称已经收掉** —— `repository.deleteCourse` 现在同一枚事务内先按 id 读回行、读不到就 `removedAnything = false`（判据原文 `if (deletion.removedAnything) deleteCourseRow(rows.first())`），两枚删除入口从此各有自己的"删没删到"结论；② **上面那格"UI 把返回的 Boolean 丢了"由 T127① `970282a` 收掉**：管理页那一支现在是 `val deleted = viewModel.deleteCourseGroup(target.fragments)`，文案两支分叉（`if (deleted) "已删除「…」" else "删除失败：${target.displayName} 还在课表里"`）、那颗「撤销」换成 `actionLabel = "撤销".takeIf { deleted }`，两处同读一枚 `deleted`（落点仍是那一格钉的 `:215` 与 `:216-220`，T127① 是**同行改写**：`git show 970282a -- app/src/main/java/com/buaa/schedule/ui/course/CourseManagementScreen.kt` 的 hunk 头 `@@ -212,10 +212,10 @@` ⇒ 该文件 451 行一字未增删、文件内其后的裸 `:NNN` 一列不漂）；③ 守卫账（本轮逐枚量）：`CourseGroupDeletionWiringGuardTest` 名下 **6** 枚接线，复算 `grep -cE '^[[:space:]]*@Test' app/src/test/java/com/buaa/schedule/ui/CourseGroupDeletionWiringGuardTest.kt` ⇒ 6，第 ① 枚就是"两枚删除入口三拍对照表"（读结论 → 分文案 → 闸那颗按钮，逐枚同序、闸的变量名从原文里抓）⇒ 这一格从今天起**不是"零守卫"**；④ 但本遍那句判档的**理由仍然成立、只是换了落点**：删不到时屏幕上念的是"删除失败…还在课表里"而课表确实还留着那一组 ⇒ 谎话那一档没了，"值不值"这一问本遍没重判 ⚠️ 且 T127 回执自陈**零装机级证据** |
| 6 | 向导里落库 `applyConflictShift` —— `viewModel.viewModelScope.launch` 起在 ConflictWizardDialog `:79` | ⚠️ **本节判 §8.7④ 这一格不实**：这里今天立着**全仓最完整的一枚旗**——`var pendingCourses by remember { mutableStateOf(setOf()) }` 记在**弹窗那一层**、按 `wizardKey` 索引（`:119`，注释 `:117-118` 明写为什么不能记在行内），起点 `onShiftStart`（`:139`）、终点 `onShiftEnd`（`:140-144`，失败把按钮还给用户重试），读者三处同源：`enabled = !pending`（`:213`）、文案 `if (pending) "写入中…" else "只改这些周"`（`:220`）、`val pending = courseKey in pendingCourses`（`:173`）。载体还是 `viewModelScope` + `job.join()`（`:79`/`:85`），KDoc `:63-66` 点名"不能用 `rememberCoroutineScope()`，否则界面说已应用、库里其实没写（P1）" | **点不动**（`enabled` 已按灭）。退场窗口内能做的只有"划走弹窗"，而这一族**恰好是九处里唯一一枚划走也不丢写**的：作用域绑 VM、`join` 不传播取消 | **不收 ⇒ 且断言判为不实**：它不是"缺旗"，它是**第三枚范本**，本卡两枚范本的清单因为它而漏了一枚（§9.4 驳回②）。建议连它一起照抄。**现状（T126 已把那枚比较级压低，T129 把同一句订正补到本格；上面那四格读数一字未抹）**："**全仓最完整的一枚旗**"按**读者枚数**那一维**不成立** —— 同一把尺本轮在 `073d098` 上复算：`grep -cE '\bsaving\b' app/src/main/java/com/buaa/schedule/ui/editor/CourseEditorScreen.kt` ⇒ **11 行**，而 `grep -cE 'pendin' app/src/main/java/com/buaa/schedule/ui/home/ConflictWizardDialog.kt` ⇒ **8 行**（扣掉两枚形参传递是 6 处读者）⇒ 排第一的是编辑器那枚 `saving`、不是本枚。本枚真正独一份的是**另一维**：全仓唯一一枚**按课程身份 keyed** 的在飞旗（复算 `grep -rnE 'by remember \{ mutableStateOf\(setOf' app/src/main/java --include='*.kt'` ⇒ **2 行**、两枚都在本文件里，另一枚是「已应用」标记 `shiftedCourses` 而不是在飞旗）＋ 终点带失败归还 ＋ 写库落在 `viewModelScope` 再 `job.join()`。⇒ **「第三枚范本」这一判不变（本轮逐枚 `awk 'NR==N'` 复算那几个落点一字未漂：声明 `:119`、起点 `:139`、终点 `:141`、`enabled = !pending` `:213`、文案 `:220`），「最完整」那一判作废**；完整机制账与"为什么会漏"写在 §8.7④ 那条 T126 现状句的第（二）件里。**现状（T132 重钉）**：本格那些落在向导文件上的裸锚点同为 T131 之前的读数（269→278、净插 9 行，窗口起点 pre `:69`）⇒ 今天依次是 `val job = viewModel.viewModelScope.launch` 与 `job.join()` 裸 `:87`/`:93`、在飞旗声明 `var pendingCourses by remember { mutableStateOf(setOf<String>()) }` 裸 `:128`、它上面那两行注释裸 `:126-127`、起点 `onShiftStart` 裸 `:148`、终点 `onShiftEnd` 那一族裸 `:149-153`、读者 `val pending = courseKey in pendingCourses` 裸 `:182`、`enabled = !pending` 裸 `:222`、文案「写入中…／只改这些周」那一行裸 `:229`；KDoc 那四行裸 `:63-66` 在窗口之前 ⇒ 未漂。逐枚复算＝ `git show 5f39a4d^:app/src/main/java/com/buaa/schedule/ui/home/ConflictWizardDialog.kt | awk 'NR==旧'` 对 `git show HEAD:app/src/main/java/com/buaa/schedule/ui/home/ConflictWizardDialog.kt | awk 'NR==新'` 两句同文 ⇒ 上面那句「本轮逐枚 `awk 'NR==N'` 复算那几个落点一字未漂」在它自己的基点 `073d098` 上仍然成立，只是**今天**要按上面这组新号读；「第三枚范本」这一判不变 |
| 7 | `icsLauncher` 回调（SAF `.ics`）—— `scope.launch` 起在 ImportScreen `:151`（`:147` 声明） | **无 UI 旗**；**三道间接闸**：① **SAF 外部节流**——契约是 `ActivityResultContracts.OpenDocument()`（`:148`），一次会话只回**一枚** `uri`（`:149` 的 `uri ->` + `if (uri != null)`），选择器是独立窗口、期间本页那颗按不到；② **仓库层 `withImportLock`**，`importIcs` 整条链包在里面（VM `:1079`）；③ 这把锁是 `tryLock` ⇒ 第二枚**不排队、当场回绝**并念一句「已有导入正在进行，请稍候」（`:665-667`） | **没有"并发写"这一说**：第二枚在 `tryLock` 就死了，用户看见的是那句提示，不是第二份课表。重复导入同一份文件也过不去同一把锁 ⇒ **本遍没找到比这句提示更糟的落点** | **不收**（第 1 问三重闸；第 2 问有回声、不静默） |
| 8 | `textLauncher` 回调（SAF 文本）—— `scope.launch` 起在 ImportScreen `:172`（`:168` 声明） | 同 #7（同契约 `:169`、同 `uri ->` `:170`、落库口 `importText` 在 VM `:1114` 同一把锁里）；两颗入口按钮（`:429` / `:447` 的 `launcher.launch(…)`）**都没有 `enabled`** | 同 #7 | **不收**（同 #7） |
| 9 | §8.7④ 锚点 ImportScreen `:485` 那枚 `scope.launch` | **真·无闸**（九处里两处之一）：那颗按钮既没有 `enabled` 也没有旗，`buildShareCode` 是**只读**、**不过 `withImportLock`**（VM `:1255-1265`） | 落点指得到调用点：`context.startActivity(Intent.createChooser(sendIntent, "分享课表口令"))` 被调两次（`:496-498`）⇒ **两份分享面板**。**指不到的那一半本节说清楚**：面板是"叠两层"、"后一次顶掉前一次"还是"被系统去重"属 Activity 任务栈行为 ⇒ 禁设备、本遍没量 | **待真机**（九处里唯一一枚够这一档）。值不高：口令是只读快照现拼的，两枚面板里是同一份内容 |

**卡面单独点名的那颗「口令导入」不在这九处里**（它写着 `enabled = shareCode.isNotBlank()`，`:481`，属**内容闸**）：
它是 `Button` 不是 `OutlinedButton`（`:473-482`），onClick 里 `clearImportMessage()` + `importShareCode(shareCode)`（`:475-476`）
**UI 侧压根不起协程**（launch 长在 VM `:1271`），并且**过 `withImportLock`** ⇒ 第二枚同样被 `tryLock` 回绝。
**本节判它"不收"，并判 §8.7④ 那一格的名与实不符**：`④` 的锚点是 `:485`（导出/分享那颗），卡面把它读成了"口令导入"。

### 9.3 档位分布与后续卡建议表

**档位分布：收 1 枚（#3）· 不收 7 枚（#1 #2 #4 #5 #6 #7 #8）· 待真机 1 枚（#9）。**
"不收"占七枚不是本节和稀泥，是三问的算法决定的：**九处里有 4 处今天结构上就点不动**（#2 #6 #7 #8），
**2 处的后果本节指不到落点**（#4 的"混色"被事务边界否掉、#5 的空删除被仓储层守卫否掉），
**1 处的后果不值**（#5 那句谎话用户无从分辨，先例 #117）。
⇒ **§8.7④ 那句"这是下一轮排卡的唯一来源里最大的一块"要按实测收窄：九处收成一枚半**，
"最大的一块"这一格在 T120 之后**不再成立**（旧句子留着不抹，订正句加在 §9.5④ 那一格）。

| 建议卡 | 收哪几处 | 照哪个范本 | 要不要守卫 | 要不要装机 | 一句话理由 |
| --- | --- | --- | --- | --- | --- |
| **T121**（建议先开） | #3 的 phantom 那一半 | `ConflictWizardDialog` 的 `pendingCourses` 那一族（页面层 `remember` + `enabled` + 文案 + 起讫配对，**四处读者同源**），**不是**卡面给的两枚 | **要**：钉"invoke 之前必须已立旗"、钉那颗「撤销」的 `enabled` 吃同一枚旗 | 不要（判据全是源码可达性） | 九处里唯一一枚"今天无旗无锁 + 后果当场生效 + 只有按灭挡得住"的。⚠️ 同一批要顺手量另一笔：`repository.deleteCourse` 缺 `deleteCourseGroup` 那道空快照早退，**根因在那儿**，补它是动 main、比按灭更收口。**现状（T121 已落地 `4dfd7d3` + `cb9f599` + `d4c6c51`，T129 在 `073d098` 上复核；上面那一整格原文一字未抹）**：这一枚卡**收了、但没走"补旗"那半** —— 它走的正是本格末尾预言的那一支（仓储层补"到底删没删到"，形状照 `deleteCourseGroup` 那道空快照早退）⇒ §9.2 #3 那格里"后果当场生效"半句成了**改前读数**，而"今天无旗无锁"半句**今天仍成立**（复算 `grep -n 'enabled' app/src/main/java/com/buaa/schedule/ui/home/HomeScreen.kt` ⇒ 仍 2 行、两枚都是周翻页箭头那颗，没有一行落在删除上）。⇒ **"九处里唯一一枚三问走到底"这一判在 `b84248f` 的盘面上成立，在 `073d098` 上只剩两问还开着**（无旗无锁 + phantom 只有按灭挡得住），第三问"后果当场生效"已被 main 侧收掉；守卫账本轮逐枚量：`CourseDeletionWiringGuardTest` **6** 枚接线 + `CourseDeletionPolicyTest` **9** 枚表驱动（各复算 `grep -cE '^[[:space:]]*@Test' app/src/test/java/com/buaa/schedule/ui/CourseDeletionWiringGuardTest.kt` ⇒ 6、换成 `data/repository/CourseDeletionPolicyTest.kt` ⇒ 9），⚠️ 该卡回执自陈**零装机级证据** |
| **T122** | #1 与 #4 共用的那一枚 no-op 撤销条目 | WeekView `:1113` 那记 `if (merged != r.course.periods)`——同仓既有的"同值不写"形状，搬到 `updateCourse` 的 pushUpdate 之前 | **要**：同值早退是纯判据，表驱动单测钉得住 | 不要 | 这一枚**不需要任何旗**就能把 #1 #4 的可见后果收掉，比给色板加 `enabled` 便宜，且不误伤"用户真的想再点一次"。**现状（T122 落 `7965270`、判 deferred、main 一字未动；上面那句"不需要任何旗就能把 #1 #4 的可见后果收掉，比给色板加 `enabled` 便宜"是 §9 当时的建议原话，一字不抹）**：这一句被实测**驳回**，理由不是"值钱不值"而是"**这一判今天在压栈点算不出来**"，四条机制逐枚复核过：① 全仓唯一一枚**保证**同值的写库路径（管理页反复点**当前已选中**那块色板）走的正是 `options.applyToGroup` 那一支，而那一支写的是**兄弟行**、并且**无条件**把整组的 `isManualOverride` 翻成 `true`；② 而那枚组写**返回 `Unit`**、不回报它写了什么（复算 `grep -n 'suspend fun updateCourseGroupAppearance' app/src/main/java/com/buaa/schedule/data/repository/ScheduleRepository.kt` ⇒ 落在裸 `:245`，签名里没有返回类型）⇒ 调用点只剩两种都不接受的读法（"组那一支跑了就算动过"= 那枚同值条目照旧留在栈里、改了等于没改；"主行同值就算没动"= 把一次真改了兄弟行的编辑的撤销记录**静默丢掉**，比现状更贵）；③ 压栈点 `UndoManager.pushUpdate` 排在组写**之前**（两枚落点就是上面那格引的 `:522` → `:540` 那对，本轮 `awk 'NR==531'` 读到 `UndoManager.pushUpdate(`、`awk 'NR==540'` 读到 `repository.updateCourseGroupAppearance(course)`）⇒ 在压栈那一刻看不见组那一支会不会改东西；④ VM 手里那份课程表**是过滤过的**、不能当组视图用（守卫第 ④ 层原文："`uiState.courses` 必须仍是过滤出来的 `visibleCourses`（不是全表）：拿它当「整组兄弟行」来预判会漏掉被过滤掉的行 ⇒ 误判成「什么都没动」"）。⇒ **前置卡已排**：要收这一判得先让**组写交出它自己的结论**（T121 那一族形状），那是仓储层的一张卡；T122 交回的是 `UndoUpdateEntryGuardTest` 名下 **6** 枚"钉现状形状 + 钉前置条件未落地"的守卫（其中第 ⑤ 枚在 T127① 从"钉病"翻成"钉药"，枚数没动），且该守卫里那句签名断言就是**扳机** —— 组写一旦开始返回结论，它当场红 |
| **T123** | #3 跨对象那一半 + #5 被丢掉的那个 Boolean | **本节给不出范本**——这一族全仓零先例；正因如此**不许**把它塞进 T121"顺手一起做" | 要（`undo()` 的签名一改，两侧守卫都要重钉） | **要**：两次删除的两条 snackbar 各自可不可达，取决于 `SnackbarHostState` 的队列语义，禁设备量不出来 | `undo()` 是无参全局 pop、回话念的是栈顶 label ⇒ 这是**撤销的身份问题**，按灭挡不住（判据第 3 问 (a)） |
| **T124**（建议排 T121 之后） | #1 #2 #3 #4 #5 #9 六处共用的**作用域**这一族 | `applyConflictShift` 那一枚（`viewModel.viewModelScope.launch` + `job.join()`），它的 KDoc 已经把理由写成 P1 | 要（接线守卫，钉"这条链的协程接收者不许是 composition scope"） | 半：可达性不装机；"划走那一瞬到底写没写进去"要装机 | 六处挂在 `rememberCoroutineScope()`（HomeScreen `:177`、CourseManagementScreen `:100`、ImportScreen `:133`），而库里已经有明文说这么用会造出"界面说已应用、库里其实没写" |
| **T125**（低优先，可并档） | #9 那枚分享面板 | 卡面范本②那一族（UI 本地旗 + `enabled`）就够 | 要 | **要**（先装机判它到底叠不叠层，再决定值不值得开） | 只读链、两枚面板里是同一份口令，本节判它"后果未定档"而不是"后果轻" |
| **T126**（纯文档） | §8.7④ ④ 格 + §8.6 第二条 | —— | 不要 | 不要 | 那两格现在都把 #6 算进"一枚旗标都不立 / 按钮仍可点"，实测它立着全仓最完整的一枚；订正时旧句子留着、只加现状句。**现状（T126 已落地 `58a04c4` + `51139bd`，T129 把本格那枚旧比较级一并压低；上面这一整格原文一字未抹）**：两格都收了，但本格末尾那句"实测它立着**全仓最完整的一枚**"是 §9 当时的原话、T126 已判它**过头**并在 §8.7④ 补了机制账 —— 同一把尺本轮在 `073d098` 上复算：`grep -cE '\bsaving\b' app/src/main/java/com/buaa/schedule/ui/editor/CourseEditorScreen.kt` ⇒ **11 行** > `grep -cE 'pendin' app/src/main/java/com/buaa/schedule/ui/home/ConflictWizardDialog.kt` ⇒ **8 行** ⇒ 按读者枚数排第一的是编辑器那枚 `saving`；本枚独有的是**按课程身份 keyed** 那一维（"第三枚范本"这一判不变）。⇒ 这一行留在这里当**排卡记录**读，别当现状句读 |

### 9.4 卡面对账：三条尺复现、三句要驳、一句"卡面怀疑但实测成立"

**复现（照抄卡面命令，读数一字不差）**：`?\.`.isActive == true` ⇒ **2 行**（UpdateCheck 的 `startDownload` 与
`retryInstall` 各一枚）✅、`submitting` ⇒ **12 行 / 1 枚文件** ✅、`withImportLock` ⇒ **8 枚调用点** ✅。
§8.7④ 那九枚行号也逐枚复算**全部未漂**（账在 §9.1 末格）。（T132 重钉：这一判在它的基点上是真的；T131 `5f39a4d` 之后九枚里向导那一枚不再未漂，今天落在裸 `:87`，其余八枚仍逐枚复算未漂 —— 长注见 §8.7④ 那一格与 §9.1 末格）

**驳回 ①（"全仓今天只有两枚既有的按灭范本"）**：卡面 X ⇒ 实测 Y。换一把宽尺
`grep -rn 'enabled = !' app/src/main/java --include='*.kt'` ⇒ **13 行 / 8 枚文件**，逐枚读原文后
"操作在飞就按灭"的族是 **9 枚**（清单在 §9.1 第 4 条）。⚠️ 更要紧的是**卡面范本①名实不符**：
`grep -rn 'enabled' app/src/main/java/com/buaa/schedule/update/ | wc -l` ⇒ **0** —— `UpdateCheck` 那一族**没有按灭**，
它是 `if (downloadJob?.isActive == true) return` **吞掉这次点击** + `when (shown)` 的分支覆盖把整颗「立即下载」换掉
（`Downloading` 那一档是 `DownloadingDialog`，`confirmButton = {}`）。⇒ 这恰好就是 §9.0 引的那句 KDoc
警告过的形状（「以前是颗裸 `Boolean`，于是三个 `if (inFlight) return` 把用户按下去的…吃掉而界面毫无动静」）。
**排卡时的直接影响**：T121 **不许**照范本①抄，照抄会正好抄成本仓已经修过一次的哑闸。

**驳回 ②（"这 9 处起手块一枚旗标都不立"）**：卡面 X ⇒ 实测 Y。九枚里 **8 枚**不立 UI 旗，
#6 那枚 `ConflictWizardDialog` 立着**全仓最完整的一族**（弹窗层 `remember` + `enabled` + 文案 + 起讫配对，
§9.2 那一行给了全部锚点）。§8.6 第二条那句"这些站点在操作飞着的时候按钮仍可点、可重复触发"对它是**断言不实**。
**现状（T126 已压低那枚比较级、T129 在 `073d098` 上复核；上面这一段原文一字未抹）**：这一句里"**全仓最完整的
一族**"按**读者枚数**那一维**不成立** —— 同一把尺 `grep -cE '\bsaving\b' app/src/main/java/com/buaa/schedule/ui/editor/CourseEditorScreen.kt`
⇒ **11 行**，而 `grep -cE 'pendin' app/src/main/java/com/buaa/schedule/ui/home/ConflictWizardDialog.kt` ⇒ **8 行**
⇒ 排第一的是编辑器那枚 `saving`，本枚独一份的是**按课程身份 keyed** 那一维
（复算命令与逐枚落点写在 §9.2 #6 那一行的现状句里，机制账在 §8.7④ 那条 T126 现状句第（二）件）。⇒ **驳回本身
照旧成立**（"九枚里 8 枚不立 UI 旗"、"§8.6 那句对它是断言不实"两句本轮各复算一次：九处宿主文件里 `enabled`
今天仍是 HomeScreen 2 行 + WeekView 1 行 + CourseManagementScreen 0 行 + ImportScreen 3 行，没有一行落在这九处
点名的那颗控件上），只是**别再把它当"最完整"读**。

**驳回 ③（九处里第 9 处的名）**：卡面 X（"那颗「口令导入」`OutlinedButton`"）⇒ 实测 Y：§8.7④ 的锚点 `:485`
落在**「分享本课表（口令）」**那颗 `OutlinedButton`（`:483` 起）；「口令导入」是 `:473` 那枚 **`Button`**，
它 UI 侧不起协程（launch 长在 VM `:1271`）、`enabled = shareCode.isNotBlank()` 是**内容闸**、且过 `withImportLock`
⇒ **严格说它压根不是"起手块"**。本节按锚点（不按名）把 #9 判成导出的那枚，两处都给了档（都判"不收"）。

**卡面怀疑、本节实测不成立的那一句要单独记**：卡面要本节复核"§8.7④ 写'走 SignInViewModel 那套闸门'是不是指针写错了
文件"，并说"如果闸门其实在 IClassLoginScreen 而不在 ViewModel，这本身就是本卡的一条产出"。
⇒ **指针没写错**：`SignInViewModel` 里今天立着 `private val flight = MutableStateFlow(false)` +
对外 `val inFlight` + `fun signIn(raw: String)` 首行 `if (flight.value) return` + `finally` 归还，
被 T85 拆掉的是**另一族**（`alreadySigned` 那一档）。⇒ **文档欠一笔订正：不欠。**

**本节自己踩到的两处（照 §8.8 的写法如实登记）**：
1. 本节初稿把范本①写成"`enabled` 在 `UpdateDialog` 那一侧"，重跑上面那条 `wc -l` 得 **0** 才改过来。
   ⇒ **这是 §8.8 第 2 条那枚教训（"数字旁边必须带命令"）的第二份实例**，本节差点把一句没验的话写进判据档。
2. 复算九处行号时本节走的是**先按符号 `grep` 定位、再拿行号回对 §8.7④**，与 §7.1 那"当场 `awk 'NR==N'` 逐枚读"
   的顺序相反。结论同为"未漂"，但流程上是**先有了结论再补的验证** ⇒ 下一遍沿用 §7.1 那个顺序，别学本节这一条。

### 9.5 明留（按红线一枚没动，逐条带锚点；四条转 §9.3 那三枚卡）

| 编号 | 位置（锚点） | 该改什么 |
| --- | --- | --- |
| ① | VM 的 `updateCourse`：`repository.updateCourse` 与 `updateCourseGroupAppearance` 是**两次独立取锁、跨两个事务**（`:522` → `:540`）；起协程的 scope 三枚 `rememberCoroutineScope()` 在 HomeScreen `:177`、CourseManagementScreen `:100`、ImportScreen `:133` | 一次"整组换色"被取消在两次取锁**中间** ⇒ 主行新色、同组兄弟片段旧色 = **真混色**，而它的因是 scope 被 dispose，不是重复触发 ⇒ **按灭挡不住**（判据第 3 问 (b)）。要收就得换作用域或 `join`，照 `applyConflictShift` 那一枚（`:79` + `:85`，KDoc `:63-66` 已把理由写成 P1）（T132 重钉：这两枚向导锚点是 T131 之前的读数，269→278 净插 9 行、窗口起点 pre `:69` ⇒ 今天 `val job = viewModel.viewModelScope.launch` 裸 `:87`、`job.join()` 裸 `:93`；KDoc 那四行 `:63-66` 在窗口之前 ⇒ 未漂；复算 `git show HEAD:app/src/main/java/com/buaa/schedule/ui/home/ConflictWizardDialog.kt | awk 'NR==87||NR==93'`）。⇒ 转 **T124** |
| ② | VM `:528-537` 那支 `if (original != null) UndoManager.pushUpdate(…)` **不比较 before/after** | 同值重写压一枚 no-op 撤销条目 ⇒ 用户点「撤销」、屏幕念「已撤销：编辑课程」而课表纹丝不动（#1 #4 共用）。修法是一句同值早退，形状仓里已有：WeekView `:1113` 的 `if (merged != r.course.periods)`。⇒ 转 **T122**。**现状（T122 落 `7965270`、T127① `970282a` 改判第 ⑤ 枚；上面那句"用户点「撤销」、屏幕念「已撤销：编辑课程」而课表纹丝不动"一字未抹，它是 §9 当时的按字面读数，T129 在 `073d098` 上复核）**：那句**按字面不可达** —— 全仓今天只有**两枚**「撤销」入口（复算 `grep -rn 'actionLabel = "撤销"' app/src/main/java --include='*.kt'` ⇒ **2 行**：HomeScreen 裸 `:456`、CourseManagementScreen 裸 `:218`，两枚都写成 `"撤销".takeIf { deleted }`），而两枚都挂在**删除之后**的那条提示条上 ⇒ 删除永远把 Delete 压在栈顶，**「编辑」那一档没有属于自己的 pop 时机**（同一条链的另一把尺 `grep -rn 'ActionPerformed' app/src/main/java --include='*.kt'` ⇒ **2 行**，就是那两枚读者）。唯一能把序压错的窄窗是 `SnackbarDuration.Long` 那扇（两枚提示条各写着 `duration = SnackbarDuration.Long`，落点 HomeScreen 裸 `:457`、CMS 裸 `:219`；"约 2.75 秒"那枚数是 `UndoUpdateEntryGuardTest` 类头 KDoc 给的标称口径，**本卡禁设备、没在盘上量过窗长**），而那条链的病灶是 **pop 捞栈顶**（T123 的靶子）、**不是"多压了一枚同值条目"**。⇒ T122 因此判 **deferred、main 一字未动**，真实理由是"**这一判今天在压栈点算不出来**"（账在 §9.3 T122 那一行的现状句），钉住现状形状的守卫 `UndoUpdateEntryGuardTest` 名下 **7** 枚（复算 `grep -cE '^[[:space:]]*@Test' app/src/test/java/com/buaa/schedule/ui/UndoUpdateEntryGuardTest.kt` ⇒ 7；上一档 **6** 枚量于 `073d098` 与 `d22a3f8`、按本档规矩留着不抹，长的就是刚落地的那道扳机那一枚）⇒ **上面那枚"转 T122 / 前置卡已排"已兑现，本格从此不再指路 T122**：**T128** 两枚 commit 落进 master —— `34a265a` 把前置做完（组写那一支交出它自己的结论 `GroupAppearanceEdit(beforeRows)`，四把维度的判据另折进纯 JVM 内核 `ui/UndoUpdateAdmission.kt`，裸 `:44` 起头是「internal fun updateUndoWorthRecording(」，VM 的调用点裸 `:531` 起头是「if (original != null && updateUndoWorthRecording(original, course, edit.savedId, edit.removed.size, group) {」），`5eadb78` 当场把守卫里那层"钉前置未落地"翻成正向判据、**一格没删** ⇒ 那枚守卫 6 ⇒ 7。⚠️ **本格过期的是指路那半句、不是判据那半句**：留着"前置卡已排"会把下一个读者送去排一张**已经存在**的卡。**残余的那一半（本档登记在这儿，形状照那枚内核文件头 KDoc 裸 `:36` 那一节「## 残余的那一半（本卡没修，如实登记）」逐字抄）**：四把维度里**第 4 维命中而第 3 维不命中**时（管理页给整组换色、主行本来就是这个色），条目**照压**（不许丢），但 `UndoAction.Update` 今天只带得回主行（构造器在 `data/undo/UndoManager.kt` 裸 `:39`，起头是「data class Update(」）⇒ 撤销回去的是"主行原样"、兄弟片段留着新色 = **用户看得见的一次改色撤不干净**。那是撤销条目的**容量**问题，与本格那一判（要不要有条目）是两件事 ⇒ 登记为**下一枚代码卡 T137**：给 `UndoAction.Update` 加 `groupBefore: List<Course>`（走**默认参数** —— 那一族第 ⑤ 层那把构造器尺只数主构造器）+ `undoUpdate` 按 id 复原（落点在 `data/repository/ScheduleRepository.kt` 裸 `:338`，起头是「private suspend fun undoUpdate(action: UndoManager.UndoAction.Update) {」）。⚠️ 复算今天这一半**没做**：`grep -rn "groupBefore" app/src/main --include=*.kt` ⇒ **0 命中**；而"整组换色撤不干净"落在真机点序列上、本档禁设备 ⇒ 这一格只到代码可达性为止，装机级证据一枚都没有 |
| ③ | VM `fun undo()`（`:589-598`）无参、`UndoManager.pop()` 捞栈顶、回话念的是**栈顶那条的 label**；两处读者 HomeScreen `:459`、CourseManagementScreen `:222`；外加 CMS `:215` 把 `deleteCourseGroup` 返回的 Boolean 丢了 | 这是**撤销的身份问题不是按钮的闸门问题**：删 A 再删 B、然后点 A 那条 snackbar 的「撤销」⇒ 回来的是 B，而两句提示在 label 上分辨不出（都叫「删除课程」）。按灭挡不住（第 3 问 (a)）。⚠️ 两条 snackbar 各自可不可达属 `SnackbarHostState` 队列语义 ⇒ **禁设备、本遍没量**。**现状（T127 已落地，T129 在 `073d098` 上逐枚复核；上面那一整段原文一字未抹）**：① **那颗「撤销」不再无条件** —— T127① `970282a` 把管理页那一支改成读结论（原文 `val deleted = viewModel.deleteCourseGroup(target.fragments)`）、文案两支分叉、那颗按钮换成 `actionLabel = "撤销".takeIf { deleted }`，两处同读一枚 `deleted` ⇒ 上面那句"CMS 裸 `:215` 把 `deleteCourseGroup` 返回的 Boolean 丢了"与"那颗「撤销」无条件"两句从此都是**改前读数**（T127① 是**同行改写**：该文件 451 行一字未增删 ⇒ CMS 内部其后那些裸 `:NNN` 一列不漂）；② **上面"两处读者"里 CMS 那一枚的原文要订正**（T126 报的，本卡复核成立）：裸 `:222` 那行的原文是 `viewModel.undoDeleteCourse()` 而**不是** `viewModel.undo()` —— VM 里有一枚同义转发 `fun undoDeleteCourse() = undo()`（裸 `:604`），HomeScreen 裸 `:459` 那枚才直接写 `viewModel.undo()` ⇒ 两枚读者今天仍各有其人、只是名字不同，指路凭原文找回去的人会撞空；③ **这一族的病本体没被 T127 收**：撤销条目仍然没有身份、`pop()` 仍然捞栈顶 ⇒ 仍归 **T123**（该卡的判据落在守卫里：`CourseGroupDeletionWiringGuardTest` 名下 **6** 枚，复算 `grep -cE '^[[:space:]]*@Test' app/src/test/java/com/buaa/schedule/ui/CourseGroupDeletionWiringGuardTest.kt` ⇒ 6；其第 ④ 枚是"调用点册子"，钉 `deleteCourseGroup` 全仓被谁调、返回值被谁读，T127③ `073d098` 又给它补了一枚"次数"格 —— 回执自陈"臂 B4 第一次跑没红，洞在只钉落点不钉枚数"）；④ 上面那句"两条 snackbar 各自可不可达属 `SnackbarHostState` 队列语义 ⇒ 禁设备、本遍没量"**照旧成立**，本轮没有新增任何装机级证据 | 
| ④ | §8.7④ ④ 那一格 + §8.6 第二条末段 | 两格都需按 §9.2 收窄：#6 立旗（驳回②）、九处收成"一枚半"、第 9 处的名与实对调（驳回③）。**本节只按卡面授权在 §8.7④ 追加了现状句，§8.6 那一格一字未动** ⇒ 转 **T126** |
| ⑤ | 卡面 Q1 最后一条问的"`Dispatchers.Main` 单线程 + 纯 suspend 顺序写 ⇒ 第二次触发是不是只是排队" | 本节答复：**九处全部在 Main.immediate 上**（`rememberCoroutineScope()` 与 `viewModelScope` 都是），所以第二次触发天生**不是并行、是在挂起点交错**；交错咬不咬得到数据只取决于事务边界 —— 据此本节在 #4 给了"最后写赢且整组一致"的算术。**但本节没有穷举这 9 处的交错序**，那一格只到"逐处指得到/指不到"为止 |

### 9.6 上限声明与锚点自证

- **扫的范围**：只有 §8.7④ 点名的这 9 处 + 卡面另点名的「口令导入」那一枚，逐枚读原文。**没有**再扫
  "其余不立旗的 63 处起手块"（§8.6 第二条那笔账）—— 本节判据需要"有没有第二枚触发的落点"这一问，
  63 处里若有这种落点，本节**看不见**，那一格仍是 §8.6 的账。
- **一档完全没扫**：`LaunchedEffect` 里的 UI 侧旗（§8.6 第三条同一档）、`SnackbarHostState` 的队列语义、
  Activity 任务栈的去重行为（#9 待真机那一格）。⇒ 本节所有"点不动"都是从 `enabled`/分支覆盖**读**出来的，
  **没有跑过任何一条真机点击序列**；红线禁设备 ⇒ 这几句只到代码可达性为止。
- **锚点自证**：本节全文写完之后重跑 §0.4 第 4 条那三格与 §6.10 那把 md 尺 ⇒ 本档 `-o` **268**、全仓 `-o` **589**、
  本档 `-c` **146**、docs 里 `.md:行号` **24**，**四把尺一字未动**（改前读数同样在 `b84248f` 上是这四枚，
  开工时先量过一遍）。本节写的所有新行号**全是裸 `:NNN` + 同框符号名/原文片段**，文件名一律不与其行号连写，
  与 T116/T117/T118/T119 那几趟走的是同一条路。⇒ **不存在隐式漂移**，也不需要重钉 §6.10 那三格。**现状（T135 重钉三格之后，同四把尺在最后一次编辑之后重量）**：本档 `-o` 仍 **268**、全仓 `-o` 仍 **589**、本档 `-c` 仍 **146**、docs 里 `.md:NNN` 那一族仍 **24** ⇒ **四把尺一字未动**；本卡改的是三格 —— §0.4 表 `Course` 那一行、§3.1 的裸 `:234` 那一行、§3.2 的裸 `:259` 那一行，新写的行号（首页那三枚与那一枚区间、向导那一枚、两枚文件的净插数）**全部是裸 `:NNN` + 同框符号名或逐字原文**，文件名一律不与其行号连写。⚠️ 这条规矩本卡真撞过一次：在 §3.2 那一行写出过"文件名与其行号连写"的形状，四把尺当场从 268 / 613 跳到 **269 / 614**，随即改回裸写法并复算归位 ⇒ 这一对越界读数留在本节，是"尺子真的在管着笔"的实例而不是事后追认。⇒ 三格都是**同格改字、零增删行**（连同本节这一格自证在内，复算 `git diff --numstat -- docs/derived-field-audit.md` ⇒ **4 / 4**、四枚 hunk 分别起在 76 / 234 / 259 / 1510，增删行数相等 ⇒ 行号面没有任何一处被推着走）：本档仍 **1,513** 行、`docs/STATUS.md` 仍 **3,772** 行且零改动 ⇒ 被钉的指针所在行都不漂 —— `awk 'NR==206'` 在本档读回的仍是 `IcsExporter` 与 `skippedOccurrences` 那一格，STATUS 裸 `:3076` 起头仍是「`:app:testDebugUnitTest --rerun-tasks` 两跑都是」、裸 `:3093` 起头仍是「这正是 §6.10 那格想要的形状」、裸 `:3090` 仍写着"只有本档 `:206` 那一格要 +10"⇒ **不需要重钉 §6.10 那三格**，这一句本轮同样是量出来的。⚠️ **本卡没改的那一批登记在这儿（残账，不是结论）**：卡面只点名上面那三格，而首页与向导这**两枚文件**被推走的行远不止那三格 —— 本卡按"别顺手改无关的句子"没动它们，但把**实测映射**留在这儿，下一张不必重新推：首页那枚文件的映射是 pre 裸 `:415` 及以下不动、pre `:416`–`:418` **+31**、pre `:419`–`:439` **+32**、pre `:440`–`:441` **+36**、pre `:442` 被改写成三行（copy 落在那三行的第二行）、pre `:443` 及以上 **+38**；向导那枚是 pre 裸 `:72` 及以下不动、pre `:73` 及以上 **+15**。逐对读回原文验过的落点（旧 → 新，两侧原文**逐字相同**，复算同上面那条 `git show 5f39a4d:` 与本机 `awk 'NR==N'` 成对跑）：420→452、452→490、459→497、643→681、853→891、1034→1072、1076→1114、1342→1380（就是 §6.2 那枚"隐式 `copy(` 盲区"格钉的注释行，T132 刚从 1338 重钉到 1342 的那一枚）、向导 79→94 与 89→104。⇒ 本档**现在仍然漂着**的格子包括：那枚"隐式 `copy(` 盲区"、§7/§8 里钉首页 643 与 853 的两行、§8.7④ 那句"九枚全部未漂"（**这句现在不成立** —— 落在首页与向导的那几枚都漂了）、§9.2 表 #1/#2/#3 那三行的 420 / 442 / 452、§9.3 那两枚 `enabled` 落点 1034 / 1076、§9.5 那两枚 459 与它的配对行。⇒ 下一张动这些格时**照上面那两条分段映射写**，别拿"净插 38 / 净插 15"整片去减：首页是三扇窗口叠在一次改写之上，增量分段（442 那一枚比 428 那一枚多漂 5 行、比 416 那一枚多漂 6 行）；`StatsScreen` 本批零改动（T131 那 4 行是它的唯一一次，复算 `git diff -U0 5f39a4d..HEAD -- app/src/main/java/com/buaa/schedule/ui/stats/StatsScreen.kt` ⇒ 无 hunk），所以那几格不在残账里。
- **没跑门禁**：零 gradle、零 adb、零设备、零 `local.properties`；`app/` 下一字未动（main 与 test 都是），
  `docs/STATUS.md` 与 `docs/TESTING.md` 也一字未动。所有结论只来自 `grep` / `wc` / 逐枚读原文。

