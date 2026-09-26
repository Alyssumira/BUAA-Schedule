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
| `CalendarSyncUiState` | 22 | `ui/ScheduleViewModel.kt:1389,1395,1397,1401,1404,1423,1426,1429,1445,1449,1454,1462,1467,1471,1475,1479,1481,1484,1496,1508,1515,1525`（22 处全是 `_calendarSync.update { it.copy(…)` 的形状，分散在 `startCalendarSync` / `confirmCalendarSync` / `dismissCalendarSyncDiff` / `openCalendarPicker` / `dismissCalendarPicker` / `selectCalendarTarget` / `setCalendarReminderMinutes` / `requestRemoveSyncedEvents` / `dismissRemoveSyncedEvents` / `removeSyncedEvents` / `onCalendarPermissionDenied` / `onCalendarPermissionGranted` / `ensureCalendarsLoaded`） |
| `Course` | 13 | `data/import/BuaaScheduleParser.kt:136`、`data/repository/ScheduleRepository.kt:215,223,253`、`domain/schedule/CourseConstraints.kt:73`、`domain/schedule/ImportPlanner.kt:50,53`、`ui/course/CourseManagementScreen.kt:183`、`ui/home/ConflictWizardDialog.kt:81`、`ui/home/HomeScreen.kt:416,428,442`、`ui/ScheduleViewModel.kt:507`（`UndoManager.pushCreate(course.copy(id = savedId))`） |
| `WidgetAppearance` | 12 | `widget/WidgetConfigActivity.kt:346,380,404,417,427,441,449,477,498,515,532,580` |
| `ScanDecoderHealth` | 3 | `ui/signin/ScanRecoveryPolicy.kt:156,165,167` |
| `LiquidGlassMaterial` | 3 | `core/designsystem/DesignTokens.kt:282`、`core/designsystem/GlassSurface.kt:74`、`core/designsystem/GlassSegmentedControl.kt:77` |
| `WidgetBinding` | 3 | `widget/WidgetCommon.kt:893`、`widget/WidgetConfigActivity.kt:363,369` |
| `PendingImport` | 2 | `ui/ScheduleViewModel.kt:809,830`（两处 `_pendingImport.value = pending.copy(`） |
| `ReminderSetting` | 2 | `data/repository/ScheduleRepository.kt:225,365` |
| `TimeSlot` | 2 | `ui/settings/SettingsScreen.kt:654,668` |
| `SecondEngineLedger` | 2 | `ui/signin/ScanSecondEnginePolicy.kt:246,260` |
| `Semester` | 1 | `data/repository/ScheduleRepository.kt:597` |
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
| `PendingImport` | `conflictGroupCount` | 参照（T94 真回归 → T95 已修） | `ui/ScheduleViewModel.kt:171`（**类体**属性 `val conflictGroupCount: Int = CourseConflictResolution.groupConflicts(conflicts).size`，不在参数表） | `ui/ScheduleViewModel.kt:809,830` 两处 `copy(conflicts = …)` | `ui/importing/ImportScreen.kt:249`、`ui/ScheduleViewModel.kt:1038,1102,1133`（三处 `groupCount = pending.conflictGroupCount`） | 组数 = `groupConflicts(conflicts).size`，挂在类体后任何 `copy` 都点不到它 ⇒ 不可能过期；本表其余各行的判据都从这一枚推出来 |
| `ScanDecoderHealth` | `giveUpReason` | **潜在** | `ui/signin/ScanRecoveryPolicy.kt:65`（参数），唯一赋值 `:160` = `"连续 $consecutive 帧解码失败、自动试回 $cycles 轮仍不成"` | `:165`、`:167` 改 `consecutiveFailures`/`suspensionCycles` 两枚源字段而**不重算** `giveUpReason` | `ui/signin/ScanRecoveryPolicy.kt:116,128,137`、`ui/signin/ScanFrameFlowPolicy.kt:142`、`ui/signin/SpocScanScreen.kt:1072,1223` | 三格字面全齐，不发火**只靠 `:150` 那行运行期早返回**（`if (health.giveUpReason != null) return health`），没有任何静态守卫钉住这个前提 ⇒ 详见 §2 |
| `Board` | `unknownCount`、`finishedCount` | 无害 | `domain/schedule/CourseWeekSpans.kt:73,74` | 无 | `ui/stats/StatsScreen.kt:1021,1023`、`ui/stats/StatsScreen.kt:267` | `:140` `unknownCount = rows.count { it.weeksUnknown }`、`:141` 同理，`rows` 就是同表参数；全仓无 `Board.copy` |
| `Coverage` | `finished`（源＝同表 `lastWeek`）、`label`（源＝同表 `course`） | 无害 | `domain/schedule/CourseWeekSpans.kt:57,51` | 无 | `ui/stats/StatsScreen.kt:993,1002,1003`、`domain/schedule/CourseWeekSpans.kt:90,129` | `:126` `finished = currentWeek != null && lastWeek != null && lastWeek < currentWeek`，`lastWeek` 就是同表参数（`:123`）；`:120` `label = fragments.first().displayName` 与 `:119` `course = fragments.first()` 同值同源 ⇒ label = course.displayName。其余 `weeksUnknown`/`firstWeek`/`spans`/`fragmentCount` 全取函数内的局部 `inRangeWeeks`/`fragments`，**不是同表参数的函数**，故不计入 (a)。无 `Coverage.copy` |
| `SemesterSummary` | `courseCount`、`totalCredits`、`creditsMissing`、`busiestDayOfWeek`、`quietestBusyDay`、`freeSlotCount` | 无害 | `domain/schedule/SemesterStats.kt:111,112,114,117,118,120` | 无 | `ui/stats/StatsScreen.kt:191,192,391,401,402,922` | `:268` `courseCount = credits.size` 而 `:272` `perCourse = credits`（同表）；`:271` `creditsMissing = credits.size - known` 同表两枚（`creditsKnown = known` 在 `:270`）；`:274,275,277` 全是 `loads` = `dayLoads` 参数的函数。**不计 a** 的两枚：`:267` `fragmentCount = courses.size` 与 `:278` `weekCount = weekAxisLength(courses, semester)` 取的是 `summarize` 的入参，不是本表参数。无 `SemesterSummary.copy` |
| `DayLoad` | `courseCount`、`freePeriodCount`、`averageMinutes`、`peakMinutes` | 无害 | `domain/schedule/SemesterStats.kt:82,83,80,81` | 无 | `ui/stats/StatsScreen.kt:190,421`、`domain/schedule/SemesterStats.kt:255` | `:220` `courseCount = groupsByDay[day].size` 中 `day` 即同表参数 `dayOfWeek`（`:217`）；`:218,219` 同理由 `perWeek = minutesByDayAndWeek[day]`（`:215`）。无 copy |
| `CourseCredit` | `course`、`credit` | 无害 | `domain/schedule/SemesterStats.kt:61,62` | 无 | `ui/stats/StatsScreen.kt:192,859,880` | `:159,160` 两枚都从同表参数 `fragments`（`:161`）算出。无 copy |
| `Meeting` | `label`、`groupKey`、`location`、`teacher`、`campus`、`periodCount`、`minutes`、`minutesKnown`、`periodsKnown` | 无害 | `domain/schedule/WeekDaySchedule.kt:67,68,74,75,76,70,71,72,73` | 无 | `ui/stats/StatsDrillCopy.kt:44,45,46,56,72`、`ui/stats/StatsScreen.kt:718` | `:181` `label = course.displayName`、`:182` `groupKey = …(course)`、`:184,185` 由 `distinctPeriods`（`:178` 取自 `course.periods`）⇒ 全是同表参数 `course`（`:180`）的函数。无 copy |
| `DaySchedule` | `meetings` | 无害 | `domain/schedule/WeekDaySchedule.kt:86` | 无 | `domain/schedule/WeekDaySchedule.kt:89,98,127,136`、`ui/stats/StatsScreen.kt:697,699` | `:200` `dayLists[day - 1]`，`day` 即同表参数 `dayOfWeek`（`:199`）。无 copy |
| `Grid`（`WeekFreeGrid`） | `weekUnresolved`、`freeDayOfWeek`、`freePeriods`、`occupiedCellCount` | 无害 | `domain/schedule/WeekFreeGrid.kt:72,76,77,78` | 无 | `ui/stats/StatsScreen.kt:1044,1063,1064`、`domain/schedule/WeekFreeGrid.kt:82`（`freeCellCount get() = cellCount - occupiedCellCount`） | `:140` `weekUnresolved = resolvedWeek == null`（`week = resolvedWeek` 同表）、`:144,146` 取同表 `occupiedByDay`、`:145` 取同表 `rows`。无 copy |
| `Row`（`WeekFreeGrid`） | `occupiedDays` | 无害 | `domain/schedule/WeekFreeGrid.kt:38` | 无 | `domain/schedule/WeekFreeGrid.kt:40,41,88` | `:133` 整个列表按同表参数 `period`（`:132`）逐天判出。无 copy |
| `DayRow`（`WeekFreeGrid`） | `occupiedPeriods` | 无害 | `domain/schedule/WeekFreeGrid.kt:48` | 无 | `domain/schedule/WeekFreeGrid.kt:49,50,51` | `:88` 由同表参数 `dayOfWeek` 取列；且这一处本身就长在 `Grid.dayRows` 这个**类体 `get()`** 里（`:86-89`），是安全形状 |
| `Trend` | `peakWeek`、`currentWeek` | 无害 | `domain/schedule/WeeklyLoadTrend.kt:53,52` | 无 | `core/designsystem/ScheduleCharts.kt:1073,1106`、`ui/stats/StatsScreen.kt:970` | `:124` `peakWeek = if (peakMinutes > 0L) peak else null` 直接以同表参数 `peakMinutes`（`:125`）当开关；`:123` `currentWeek = currentWeek?.takeIf { it in 1..totalWeeks }` 用同表参数 `totalWeeks`（`:121`）夹。无 copy |
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
| `Semester` | `termName` | 无害 | `domain/model/Semester.kt:8` | 有 1 处：`data/repository/ScheduleRepository.kt:597` | `data/export/IcsExporter.kt:61`、`ui/ScheduleViewModel.kt:1241`（`semesterName = data.semester?.termName`） | `ui/ScheduleViewModel.kt:235`（`termName = termCode`）、`data/repository/ScheduleRepository.kt:534`、`data/import/BuaaScheduleParser.kt:225` 三处同表回退；`:597` 那处 copy 只改 `startDate`/`totalWeeks`，**不碰 `termCode`** ⇒ 改源不成立 |
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
  `ui/ScheduleViewModel.kt:1406` + `:1407` 是**全仓唯一**的写点；`computed == null` 那一档（`:1401-1404`）两枚一起不写 ⇒
  这枚数从来没有独立于 diff 的产生路径。
- **② `confirmCalendarSync` 起手那档（`:1423`）之后那条链不再产它、也不再读它**：它的应用段（`:1426-1440`，`it.copy(` 起在 `:1429`）那次
  `copy` 只带 `syncing` 与 `message`，而 `ApplyResult`（`data/calendar/CalendarSyncManager.kt:40-47`）只有
  `inserted/updated/deleted/failed`、**没有** skipped 字段 ⇒ "同步完成后仍想知道刚才跳过几节"这件事在代码里没有承载体
  （成功文案 `:1434` 念的也只有新增/更新/删除三个数）。
- **③ 唯一读点锁在 diff 的挂载闸门里**：`ui/settings/SettingsScreen.kt:1914` + `:1916` 是全仓唯一消费点（另一枚同名的是
  `data/export/IcsExporter.kt:26`，走 `ui/ScheduleViewModel.kt:1588`/`:1589` 那两行 `result.skippedOccurrences`，与本卡无关），它整块长在
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
`:144` 的实参确实把 `:141` 那枚同表参数（`dow.value`）当输入，而 `Course` 有 13 处 copy、其中 `ui/home/HomeScreen.kt:416-419` 恰恰改的就是 `dayOfWeek`：
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
其余 copy 站点逐处读过原文，每处改的都是源字段本身：`ui/course/CourseManagementScreen.kt:183`（`primary.copy(colorIndex = index, customColorArgb = null)`）、`domain/schedule/CourseConstraints.kt:73-80`（归一化，`:79` 连 `credit` 都自己算）、`domain/schedule/ImportPlanner.kt:50`（`course.copy(id = old.id, credit = course.credit ?: old.credit)`）、`:53-57`（并周次 + 学分取已知值）、`data/import/BuaaScheduleParser.kt:136-143`（同格并片段）、`data/repository/ScheduleRepository.kt:215,223`、`ui/home/HomeScreen.kt:428,442`、`ui/home/ConflictWizardDialog.kt:81`、`ui/ScheduleViewModel.kt:507`（`UndoManager.pushCreate(course.copy(id = savedId))`）。

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
- `widget/WidgetBindingStore.kt:16-26` `WidgetBinding`（`:19-25` 的 `weekOffsetBase` 与 `weekOffset` 是"偏移 + 偏移基准"两枚独立事实，`widget/WidgetCommon.kt:893` 那处 copy 两枚一起点）、`ui/home/WeekGridGeometry.kt:262-266` `ResizeState`（`ui/home/WeekView.kt:1104` 只点 `deltaY`，而"新结束节"是 `:270-274` 的函数 `newEnd(metric)` 不是字段）、`ui/settings/SettingsScreen.kt:654,668` 的 `TimeSlot`、`data/repository/ScheduleRepository.kt:225,365` 的 `ReminderSetting`（换 `courseId` 是拆周次后把提醒搬去新行）、`data/repository/ScheduleRepository.kt:103` 的 `SemesterEntity`（`target.copy(id = 0L)` 让它落成新行）：参数表里都不存在"另一枚参数的函数"。
- `core/designsystem/Theme.kt:167-176` `SemanticColors.copy` 把五个槽位逐一点齐；`warning` 与 `onWarning` 是"成对解出来的配色"而不是彼此的函数（KDoc `core/designsystem/GlassSurface.kt:255-258`「两个字段必须成对用」）。`LiquidGlassMaterial` 的三处 copy（`core/designsystem/DesignTokens.kt:282-296`、`core/designsystem/GlassSurface.kt:74`、`core/designsystem/GlassSegmentedControl.kt:77`）里 `blur/lensHeight/lensAmount` 都由工厂入参 `intensity` 算出（`core/designsystem/LiquidGlass.kt` 的 `pill`/`dialog` 工厂），`copy(useVibrancy = false)` 更是单旗标。

### 3.6 「同源扇出」一族：看着像 a，其实没有一枚参数是另一枚的函数

这十三枚是第二遍扫描里最大的噪声源，也是**编排者复核我有没有筛错时最该看的一节**。共同形状：`X(a = obj.p, b = obj.q, c = f(obj.p))` —— 各枚实参共享一个**外部对象/函数入参**，而那枚外部对象不在参数表上。

| 类 | 定义处 | 共享源 | 原文（赋值处） |
| --- | --- | --- | --- |
| `ScanRejectInfo` | `data/import/ScanReject.kt:80` | 函数入参 `shape` | `:187-197` `textLength = shape.length`、`scheme = shape.scheme`、`paramNames = shape.names`（十枚全取 `shape`） |
| `BackupPreview` | `ui/ScheduleViewModel.kt:1218`（`data class BackupPreview(`） | 局部 `data: BackupData` | `:1244` `courseCount = data.courses.size`、`:1245` `manualCourseCount = data.courses.count { it.isManualOverride }` |
| `ImportHistory` | `domain/model/ImportHistory.kt:8` | 局部 `selection` | `ui/ScheduleViewModel.kt:774` `courseCount = selection.toWrite.size`（`toWrite` 不在 `ImportHistory` 参数表上） |
| `GanttRow` | `core/designsystem/ScheduleCharts.kt:598` | 局部 `coverage` | `ui/stats/StatsScreen.kt:993,1000,1001,1002,1003` 五枚全取 `coverage.*` |
| `HeatGridDay` | `core/designsystem/ScheduleCharts.kt:608` | 局部 `day`/`grid` | `ui/stats/StatsScreen.kt:1041,1044` `label = "周${weekdayChar(day.dayOfWeek)}"`、`isEmptiest = grid.freeDayOfWeek == day.dayOfWeek` |
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
| **隐式 `copy(` 盲区（卡面要求自证）** | `grep -rnE "(^\|[^.[:alnum:]_])copy\(" app/src/main/java --include='*.kt'` | **6 行命中**，其中**代码只有 1 处**：`ui/home/WeekGridGeometry.kt:253` 的 `return copy(`（`CourseDragState.advancedBy` 的隐式接收者）；其余 5 行全在注释/KDoc 里（`ui/home/HomeScreen.kt:1338`、`ui/ScheduleViewModel.kt:160,162,165,794`） | 命中行数 ⇒ **盲区不是零，但只有 1 处，且与 T97 §0.1 同一枚**（这一族自 09-26 至今没长新的） |
| copy 密度 top（按**次数**那把尺） | `grep -rcE "\.copy\(" app/src/main/java --include='*.kt' \| grep -v ':0$' \| sort -t: -k2 -rn \| head -8` | `ui/ScheduleViewModel.kt` 25、`widget/WidgetConfigActivity.kt` 16、`core/designsystem/ScheduleCharts.kt` 13、`ui/home/WeekView.kt` 10、`data/repository/ScheduleRepository.kt` 7、`core/designsystem/liquid/LiquidBottomTabs.kt` 7、`core/designsystem/LiquidGlass.kt` 6、`ui/home/HomeScreen.kt` 5 | **卡面这八格逐格复现**（`grep -c` 与 `grep -o` 在这八个文件上同值：没有一个文件把两处 copy 写在同一行） |
| 把 `.copy(` 与「置空」并起来的站点 | `grep -rnE "\.copy\(" app/src/main/java --include='*.kt' \| grep -cE "= *(null\|0\b\|false\|\"\")"` | **40 行** —— ⚠️ 这一把**噪声占大头**：40 行里 `Color.copy(alpha = 0.xx)` 一档就占 27 行（`ui/home/WeekView.kt` 5、`core/designsystem/ScheduleCharts.kt` 9、`core/designsystem/liquid/*` 6、`core/designsystem/SettingsStack.kt` 3、`widget/WidgetConfigActivity.kt:648`、`ui/home/DayView.kt` 2、`ui/importing/ImportScreen.kt` 2）。同一条筛法换 `grep -o \| wc -l` 也给 **40**（这次两把尺重合，因为命中行里没有一行两处） | 先按行、再按次数 |
| **自有 data class 的 copy 站点宇宙** | 一次性脚本：解析 `data class` 参数表 → 取每处 `copy(` 的**括号配平实参表**（不是同一行）→ 留下实参名命中参数表的站点 → 按手写类型归属表分类（归属表逐枚读原文定，脚本只负责切实参表） | **18 枚类 / 72 处站点**（= T97 §0.4 那张表，逐格核对**没有变化**：T100 只往既有站点里加了实参，没添新站点） | 站点数按 `(文件, 行)` 去重 |
| remembered var（Compose 局部状态槽） | `grep -rcE "\bvar [A-Za-z_][A-Za-z0-9_]* by (remember\|rememberSaveable)" app/src/main/java --include='*.kt' \| grep -v ':0$' \| awk -F: '{s+=\$2} END {print s" 行 / "NR" 文件"}'` | **169 枚 / 28 枚文件** | 命中行数 |
| 其中**至少有一枚被单独置空过**的文件 | 同上一段脚本 + 「按文件列 `NAME = null/0/false/""` 赋值点」那一遍 | **14 枚文件**（`MainActivity.kt`、`ui/editor/CourseEditorScreen.kt` 25 枚、`ui/home/WeekView.kt` 17 枚、`ui/importing/BuaaLoginScreen.kt` 13 枚、`ui/signin/SpocScanScreen.kt` 13 枚、`ui/settings/SettingsScreen.kt` 26 枚、其余 9 枚见 §6.7） | 文件数 |

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

### 6.2 候选账（本遍判了 13 枚）

档位分布：**真漏清 1 枚 · 已被钉住 3 枚 · 结构不可能 8 枚 · 越界形状 1 枚**（共 13 枚进表）。
⚠️ 那 8 枚"结构不可能"里有 2 枚（#10 #12）**按 §6.0 的定义根本不该进候选账** —— 它们成对写、
却一处"只清一半"都没有；我把它们留在表上是因为卡面的起手式第二条直接要求读每一枚 data class 的
成对字段表，但**档位那一格对它们是硬套的**，口径问题见 §6.9 驳回②。

| # | 字段对（A，B） | 状态类 | 生产者（把它们绑在一起的证据） | 写点/清点数 | 判定 | 证据锚点 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `saving` ， `saveError` | `ui/editor/CourseEditorScreen.kt` 的两枚 remembered var（不是 data class 字段，见 §6.3） | `performSave()` 那一句 `saving = true` + 下一句 `saveError = null` 是同一次「开始一件会写库的操作」 | 写点 7 处 / 清点 3 处 | **真漏清** | `CourseEditorScreen.kt:231-232`、`:245`、`:247`、`:614`、`:618`、`:620`、读点 `:282`+`:295` |
| 2 | `diff` ， `skippedOccurrences` | `CalendarSyncUiState`（`ui/ScheduleViewModel.kt:93`） | `data/calendar/CalendarSyncManager.kt:98` `suspend fun computeDiff(calendarId: Long): Pair<CalendarSyncPlanner.Diff, Int>?` 一次返回同一对 | 写点 4 处 / 清点 3 处，**三处全成对** | **已被钉住** | `app/src/test/java/com/buaa/schedule/ui/CalendarSyncDiffClearPairingGuardTest.kt:45`+`:77`（细节见 §2.2，本节不重开） |
| 3 | `consecutiveFailures`+`suspensionCycles` ， `giveUpReason` | `ScanDecoderHealth`（`ui/signin/ScanRecoveryPolicy.kt`） | 同一枚字符串由那两枚计数器拼出来（§2.1 的 (a)） | 3 处 `health.copy(`，其中 2 处不重算 | **已被钉住** | `app/src/test/java/com/buaa/schedule/ui/signin/ScanGiveUpReasonDerivationGuardTest.kt` 五枚 `@Test`：`:61` `theOnlyEarlyReturnGuardIsStillVerbatimAndStillFirstStatement`、`:105` `productionAssignsTheDerivedFieldAtExactlyOneSite`、`:140` `theCopySitesThatChangeTheSourcesWithoutRecomputingAreStillExactlyTwo`、`:182` `theResetPathsStillBuildAFreshInstanceInsteadOfCopying`、`:204` `theCountersScanOnlyTheMainKernelFile`（§2.1 当年说「没有任何静态守卫钉着」，T98 之后这句已经过期，本节按现状改判） |
| 4 | `conflicts` ， `excludedKeys`/`addedCount`/`changedCount`/`keptCount` | `PendingImport`（`ui/ScheduleViewModel.kt:136`） | 五枚全取 `resolveImportSelection(...)` 交回的同一枚 `ImportSelection` + 同一次 `findConflicts` | 2 处 copy（`:809`、`:830`），两处**五枚全点齐** | **已被钉住** | `app/src/test/java/com/buaa/schedule/ui/PendingImportConflictGroupTest.kt:207` 那句断言的消息就是「两处逐条勾选的 copy 站点都还在按子集重算 conflicts」 |
| 5 | `fetchState` ， `fetchWeek`/`fetchTotal` | `ui/importing/BuaaLoginScreen.kt` 三枚 remembered var | `onProgress = { week, total -> fetchWeek = week; fetchTotal = total; fetchState = "正在获取课表：第 $week/$total 周..." }` 一处写三枚 | 写点 3 组 / 清点 6 处（`fetchState = null` 就有 4 处，`fetchWeek = 0`/`fetchTotal = 0` 各 1 处） | **结构不可能** | 读点 `BuaaLoginScreen.kt:344` `val fetchFraction = if (fetchTotal > 0 && fetchStateText != null) {` ⇒ 两枚计数器唯一的读者恒在 `fetchState != null` 驱动的括号里 |
| 6 | `colorMode` ， `backgroundColor` | `WidgetAppearance`（`widget/WidgetAppearance.kt:30`） | 换预设那一档整枚搬过来：`widget/WidgetConfigActivity.kt:346` `preset.appearance.copy(rowFields = appearance.rowFields)` ⇒ 配色来源与那支自定义色出自同一枚预设、一起落 | 12 处 `appearance.copy(`，其中 `:380` `appearance.copy(colorMode = it)` 与 `:404`/`:417` `appearance.copy(backgroundColor = argb)` **各改一枚** | **结构不可能** | 两枚读点都自带闸门：`widget/WidgetConfigActivity.kt:393` `if (appearance.colorMode == WidgetAppearance.COLOR_MODE_CUSTOM) {`（自定义色那一行只在这个分支里组合）与 `:624` `val baseColor = if (appearance.colorMode == WidgetAppearance.COLOR_MODE_SYSTEM) {`（SYSTEM 那一支也只把 `backgroundColor` 当 `:626` `?: appearance.backgroundColor` 的**回退**读，自定义色那一行整块不在 else 之外组合） |
| 7 | `message` ， `permissionPermanentlyDenied` | `CalendarSyncUiState` | `ui/ScheduleViewModel.kt:1496` 那次 copy 同时写 `permissionPermanentlyDenied = !canAskAgain` 与 `message = AppMessage(... "日历权限已被永久拒绝，请到系统设置手动开启")` | 成对写点 1 处；**两处分头清**：`:1389` 只清 `message`、`:1508` `it.copy(permissionPermanentlyDenied = false)` 只清旗标 | **结构不可能**（两个方向各有一道闸，机制见 §6.4-B） | 读点 `ui/settings/SettingsScreen.kt:1677` `if (calendarSync.permissionPermanentlyDenied) {`，它整块长在 `:1664` `item(key = "status", visible = calendarSync.message != null) {` + `:1665` `calendarSync.message?.let {` 里面 |
| 8 | `menuFor` ， `lastMenu` | `ui/home/WeekView.kt` 两枚 remembered var | `:558` `if (menuFor != null) lastMenu = menuFor` —— 一次写两枚（**故意**留一份给退场动画） | 成对写点 1 处；`menuFor = null` 3 处（`:876`、`:1001`、`:1175`+`:1218`）不跟着清 `lastMenu` | **结构不可能** | `lastMenu` 全仓唯一读者 `WeekView.kt:1173` `lastMenu?.let { menu ->`，它挂的浮层 `visible` 由 `menuFor` 关掉：`:1217` `visible = menuFor != null,` ⇒ 清一半正是设计意图（收场期间画锚住的那一份），不是残值 |
| 9 | `text` ， `isError`/`isSuccess` | `AppMessage`（`ui/ScheduleViewModel.kt:130`） | 每一句提示的「文案」与「染色」出自同一个构造 | 全仓 `AppMessage(` **45 处**构造、`.copy(` **0 处** | **结构不可能** | 复算：`grep -rn "AppMessage(" app/src/main/java --include='*.kt' \| wc -l` ⇒ 45；`grep -rnE "AppMessage\([^)]*\)\.copy\(\|message\.copy\(" app/src/main/java --include='*.kt' \| wc -l` ⇒ **0**。三枚字段被同一枚对象包着 ⇒ `copy` 站点根本不存在，一半都漏不掉 |
| 10 | `targetId` ， `targetName` | `CalendarSyncUiState` | `:1462` `it.copy(targetId = calendarId, targetName = displayName, showPicker = false)` 与 `:1528-1529` 那个 `if (targetGone)` 双写 | 成对写点 3 处（含初值 `:1373-1374`）/ **分头清点 0 处** | **结构不可能**（本遍判据下**根本没进候选**：见 §6.9 的档位口径驳回） | 唯一读者 `ui/settings/SettingsScreen.kt:1623` `summary = calendarSync.targetName ?: "未选择",` —— 它**不在**任何 `targetId` 驱动的块里 ⇒ 今天不漏，将来加一处「只把 `targetId` 打回 -1L」的站点就会漏，且**无守卫**（登记进 §6.8①） |
| 11 | `calendars` ， `calendarsLoaded` | `CalendarSyncUiState` | 同一次 `ensureCalendarsLoaded()`：成功那一档 `:1526-1527` `calendars = loaded,` + `calendarsLoaded = true,` 成对写 | 成对写点 1 处；**分头写点 1 处** `ui/ScheduleViewModel.kt:1516` 那一档只落 `calendarsLoaded = true,` | **结构不可能**（但那道闸**不在本族的位置**，见 §6.4-A） | 那一档长在 `:1513-1515` `val loaded = suspendCatching { calendarSyncManager.queryCalendars() }.getOrElse { _calendarSync.update { it.copy(` 里；`queryCalendars()` 自己把异常吞成空列表（`data/calendar/CalendarSyncManager.kt:71` `runCatching {` + `:90` `.onFailure { Log.w(TAG, "读取日历列表失败", it) }` + `:91` `return result`）⇒ `getOrElse` 走不到 |
| 12 | `boundCamera` ， `analysisUseCase` | `ui/signin/SpocScanScreen.kt` 两枚 remembered var | 绑定成功那档 `:381-382` `boundCamera = camera` + `analysisUseCase = analysis` 成对写 | 写点 2 组 / 清点 3 组，**三组全是连号两行**（`:330-331`、`:409-410`、`:448-449`） | **结构不可能**（同 10：无分头站点，不进候选） | 复算 `grep -n "boundCamera = null\|analysisUseCase = null" app/src/main/java/com/buaa/schedule/ui/signin/SpocScanScreen.kt` ⇒ 3 行 + 3 行，行号相邻 |
| 13 | `cameraError` ， `cameraProviderMissing` | `ui/signin/SpocScanScreen.kt` 两枚 remembered var | **不是同一次生产** —— `:155` 的注释就写着「它和 cameraError 是两件事」，两枚各有独立生产者（provider 效果 vs 绑定/相册失败） | 各自 2/6 处写点，**互不点名** | **越界形状**：本卡三档给不了它（见 §6.6） | 读侧是一条**优先级梯**而不是闸门：`ui/signin/ScanUiStatus.kt:87` `cameraError != null ->` 排在 `:93` `cameraProviderMissing ->` 之前，而 `:81-84` 那段注释正是拿「两枚会不会同时成立」在解释这个排序 |

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
     （`ui/ScheduleViewModel.kt:545` `_importMessage.value = AppMessage("更新课程失败：${e.message}", isError = true)`
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

**判零依据（为什么全仓只有这一枚落进这一档）**：另外 6 枚「成对写 + 分头清」的候选，其**读侧全部落在闸门里**
（§6.4 逐枚点了是哪一道闸）；而本仓真正**没有闸门**的读点只有两类载体 —— 编辑器/登录页这类「底栏常驻一行」，
其中只有编辑器这一枚同时满足「两枚字段由同一仪式成对写」与「清点仪式被复制成两半」。
`ui/importing/BuaaLoginScreen.kt` 那一族最接近（`:119`/`:131`/`:153` 三处只清 `fetchState`），
但它那一行进度条的读点被 `:344` 的 `&& fetchStateText != null` 挡住了 —— 这正是本节要的差别，
所以它判结构不可能而不是真漏清，不是"没找到"。

### 6.4 「结构不可能」那几枚的机制（卡面要求：不许用"应该没事"）

**A. #11 `(calendars, calendarsLoaded)`：闸门不在字段对上，而在被调方里。**
`ui/ScheduleViewModel.kt:1516` 那一档确实写了 `calendarsLoaded = true,` 而没有 `calendars = …`，
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

**B. #7 `(message, permissionPermanentlyDenied)`：两个方向各有一道闸。**
- 清 `message` 而留旗标（`ui/ScheduleViewModel.kt:1389` 那一档 `it.copy(syncing = true, message = null, diff = null, skippedOccurrences = 0)`
  确实没点 `permissionPermanentlyDenied`）⇒ **看不见**：那枚旗标全仓只有一个读者
  `ui/settings/SettingsScreen.kt:1677` `if (calendarSync.permissionPermanentlyDenied) {`，
  而它整块长在 `:1664` `item(key = "status", visible = calendarSync.message != null)` 与
  `:1665` `calendarSync.message?.let {` 两层之内 —— 句子一撤，那颗「去系统设置开启日历权限」的按钮跟着没了。
  这与 §2.2 里 `ModalTransition(payload = calendarSync.diff)` 那道闸同一形状，只是驱动它的是 `message`。
- 清旗标而留句子（`:1508` `onCalendarPermissionGranted` 整颗函数就是 `it.copy(permissionPermanentlyDenied = false)`）⇒
  **走不到**：`onCalendarPermissionGranted()` 全仓唯一调用点是 `ui/settings/SettingsScreen.kt:317`，
  它在 `:316` `if (grants.isNotEmpty() && grants.values.all { it })` 里；要拿到"旗标为 true 时句子还没被清"，
  需要先有一次 `onCalendarPermissionDenied(canAskAgain = false)`（`:325`），而勾了「不再询问」之后
  `:330-338` 那个 `withCalendarPermission` 的入口 `:331` `if (viewModel.hasCalendarPermission())` 要么直接放行
  （**根本不启动 launcher，也就到不了 `:317`**），要么 launcher 直接回全 false 再走 `:325`。
  ⇒ 这一方向是被 `hasCalendarPermission()` 这道闸挡住的，同样**不是**字段对自身的性质；登记进 §6.8③。

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
  另外 5 枚（#1 #5 #8 #12 #13）来自 remembered var 那一档，见下一条。
  ⚠️ 但这一档的「过了一遍」是**按类**过的，不是按 177 枚 data class 过的：其余 **159 枚自有 data class
  连一处 `copy` 都没有**，本节按 §0.4 那条既有事实（"档位最高只能到无害"）**直接引用了 T97 的账，
  没有重新一枚枚读参数表**。如果那 159 枚里有一枚将来长出 copy 站点，本节不给它兜底。
- **开档（本遍没判、规模数得出来）**：169 枚 remembered var / 28 枚文件。本节只做了「哪些 var 被单独
  置空过」这一遍列表（14 枚文件有），并逐枚读原文判了 **5 枚文件**：
  `ui/editor/CourseEditorScreen.kt`（判 1 枚候选 → #1）、`ui/importing/BuaaLoginScreen.kt`（判 2 枚 → #5 + §6.3 判零依据里那枚）、
  `ui/home/WeekView.kt`（判 1 枚 → #8；其余 8 枚被清点的 var 只列了账，见下）、
  `ui/signin/SpocScanScreen.kt`（判 1 枚 → #13 越界；`boundCamera`/`analysisUseCase` → #12）、
  `ui/settings/SettingsScreen.kt`（只列账，**未判**）。
  **还剩 9 枚文件没进去**，逐枚是：`MainActivity.kt`（2 枚被清点：`pendingEditorRoute = null` `:667`、
  `pendingPulseCourseId = -1L` `:758`/`:762`）、`ui/course/CourseManagementScreen.kt`（`pendingDelete = null` `:213`）、
  `ui/home/DayView.kt`（`dragActive = false` `:253`/`:271`）、`ui/home/HomeScreen.kt`
  （`pulseCourseId = -1L` `:208`、`showJumpDialog = false` `:1102`/`:1124`/`:1135`）、
  `ui/importing/ImportScreen.kt`（`hadPendingImport = false` `:144`）、`ui/onboarding/OnboardingScreen.kt`
  （`checking = false` `:151`、`showEnvironmentDialog = false` `:283`/`:287`）、
  `ui/settings/SettingsScreen.kt`（4 枚：`pendingCalendarAction = null` `:315`、`iclassSignedIn = false` `:1339`、
  `privacyConsentAt = 0L` `:1848`、`showPrivacyDialog = false` `:1849`）、`ui/settings/WidgetPinRow.kt`
  （`showGuidance = false` `:123`）、`ui/stats/StatsScreen.kt`（`expanded = false` `:611`），
  外加 `ui/home/WeekView.kt` 里那 8 枚我没逐枚配对的（`drag`、`pendingMove`、`movePickerFor`、`resizeFor`、
  `detailFor`、`pendingDelete`、`pendingSyncTarget`、`pressed`）。
  这一批的规模这样数：`grep -rcE "\bvar [A-Za-z_][A-Za-z0-9_]* by (remember|rememberSaveable)" app/src/main/java --include='*.kt' | grep -v ':0$' | awk -F: '{s+=$2} END {print s}'`
  给全仓 169，减去已进的 5 枚文件（25+13+17+13+26 = 94）⇒ **剩 75 枚 var 的账没判**。
  按本节实际判中的比例（94 枚里出 1 枚真漏清 + 1 枚越界 + 3 枚结构），**不能排除这 75 枚里还有 1–2 枚真漏清**。
- **一档完全没扫**：`app/src/debug/java`、`app/src/androidTest/java`、`app/src/test/java`、`benchmark/` 模块、
  Room/`Mappers` 生成物 —— 同 §4.2，本节没有扩大范围。
- **没跑门禁**：按卡面红线，本节**零 gradle、零 adb、零设备**，所有结论只来自读源码。
  ⇒ 「#1 是真漏清」这一句的证据是**代码可达性**，不是装机截图；它在设备上具体会念多久（退场动画几帧还是整屏停留）
  本节验不到，与 §4.1 同一档。

### 6.8 明留（本节认为该改、按红线一枚没动）

| 编号 | 位置 | 该改什么（不写方案细节，等排卡） |
| --- | --- | --- |
| ① | `ui/ScheduleViewModel.kt:1462` `it.copy(targetId = calendarId, targetName = displayName, showPicker = false)` 与 `:1528-1529` 那两行 `if (targetGone) -1L` / `if (targetGone) null` | `targetId`/`targetName` 三处写点今天全成对，但**没有任何守卫**钉住"成对"。唯一读者 `ui/settings/SettingsScreen.kt:1623` 不在闸门里 ⇒ 与 T100 改前的 `diff`/`skippedOccurrences` 只差一枚守卫。该补的是 `CalendarSyncDiffClearPairingGuardTest` 那一形状的第二份实例 |
| ② | `data/calendar/CalendarSyncManager.kt:71` `runCatching {` + `:90` `.onFailure { Log.w(TAG, "读取日历列表失败", it) }` + `:91` `return result` | 它把"查询失败"洗成"这台设备没有日历"，是 §6.4-A 那道**外来**闸门的来源；同时它使 `ui/ScheduleViewModel.kt:1517` 那句「读取日历列表失败，请检查日历权限」几乎永不显示。另附同族一笔：`calendarsLoaded` 全仓**没有任何**复位站点（`grep -rn "calendarsLoaded = false" app/src/main/java --include='*.kt'` ⇒ 0 行），于是 `calendars` 是进程寿命的缓存，用户在系统日历里删掉一个日历后选择器会一直列着那个死 id |
| ③ | `ui/ScheduleViewModel.kt:1508` `_calendarSync.update { it.copy(permissionPermanentlyDenied = false) }` | 清旗标不清句子；今天被 `ui/settings/SettingsScreen.kt:331` 那道 `hasCalendarPermission()` 短路挡着（§6.4-B）。这句注释该留在两处之一：要么把它做成成对清，要么把「靠哪道闸不念旧账」写进 KDoc |
| ④ | `ui/signin/SpocScanScreen.kt:283` `cameraProviderMissing = true` 与 `ui/signin/ScanUiStatus.kt:87`/`:93` 那两支 | 跨生产者残值（§6.6）：`cameraError` 以「读不出那张图」开头时，梯子在说相册、而真病因是 provider。判据该按**来源**分支，不是按**写入先后**赌 |
| ⑤ | `ui/editor/CourseEditorScreen.kt:614` `saving = true` | 本遍唯一的真漏清。修法与红线冲突（不许动 `app/src/main/**`），留一卡：删除这条链要不要复用 `saving` 这枚旗标本身也值得重判 —— 复用它是 `:310` 那句「保存中…」在删除时说假话的原因 |

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
  这与 §2.1 当年批评"正确性挂在一行运行期早返回上"是同一件事 ⇒ 这两枚实际强度低于 #5 #6 #8 #9。
