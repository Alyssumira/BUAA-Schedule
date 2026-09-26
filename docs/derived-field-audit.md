# 派生构造参数审计（T97 · 只报不动手）

分支 `ai/T97`，基点 `788acf5`。本文件是**唯一**被本卡新增的文件，不改任何代码。
所有 `file:line` 都是本次 `Read`/`grep` 出来的原文（路径相对仓库根，行号按本工作树）。

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
| `CalendarSyncUiState` | 22 | `ui/ScheduleViewModel.kt:1378,1383,1385,1389,1392,1409,1411,1414,1430,1434,1439,1447,1452,1456,1460,1464,1466,1469,1481,1493,1500,1510` |
| `Course` | 13 | `data/import/BuaaScheduleParser.kt:136`、`data/repository/ScheduleRepository.kt:215,223,253`、`domain/schedule/CourseConstraints.kt:73`、`domain/schedule/ImportPlanner.kt:50,53`、`ui/course/CourseManagementScreen.kt:183`、`ui/home/ConflictWizardDialog.kt:81`、`ui/home/HomeScreen.kt:416,428,442`、`ui/ScheduleViewModel.kt:500` |
| `WidgetAppearance` | 12 | `widget/WidgetConfigActivity.kt:346,380,404,417,427,441,449,477,498,515,532,580` |
| `ScanDecoderHealth` | 3 | `ui/signin/ScanRecoveryPolicy.kt:156,165,167` |
| `LiquidGlassMaterial` | 3 | `core/designsystem/DesignTokens.kt:282`、`core/designsystem/GlassSurface.kt:74`、`core/designsystem/GlassSegmentedControl.kt:77` |
| `WidgetBinding` | 3 | `widget/WidgetCommon.kt:893`、`widget/WidgetConfigActivity.kt:363,369` |
| `PendingImport` | 2 | `ui/ScheduleViewModel.kt:802,823` |
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
| `PendingImport` | `conflictGroupCount` | 参照（T94 真回归 → T95 已修） | `ui/ScheduleViewModel.kt:164`（**类体**属性，不在参数表） | `ui/ScheduleViewModel.kt:802,823` 两处 `copy(conflicts = …)` | `ui/importing/ImportScreen.kt:249`、`ui/ScheduleViewModel.kt:1031,1095,1126` | 组数 = `groupConflicts(conflicts).size`，挂在类体后任何 `copy` 都点不到它 ⇒ 不可能过期；本表其余各行的判据都从这一枚推出来 |
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
| `Semester` | `termName` | 无害 | `domain/model/Semester.kt:8` | 有 1 处：`data/repository/ScheduleRepository.kt:597` | `data/export/IcsExporter.kt:61`、`ui/ScheduleViewModel.kt:1234` | `ui/ScheduleViewModel.kt:228`（`termName = termCode`）、`data/repository/ScheduleRepository.kt:534`、`data/import/BuaaScheduleParser.kt:225` 三处同表回退；`:597` 那处 copy 只改 `startDate`/`totalWeeks`，**不碰 `termCode`** ⇒ 改源不成立 |
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
代价与风险：**这一枚不是 `PendingImport.conflictGroupCount` 那种"纯函数"**。`groupConflicts(conflicts)` 只吃 `conflicts` 一枚，而这里要把"判死这件事"（`cycles >= MaxDecodeSuspensionCycles`）一起搬进类体，等于把判据从 `:155` 那行**复制**到类体里 —— 判据就此变成两处（`ui/signin/ScanRecoveryPolicy.kt:155` 的分支条件 + 类体的三元），而这一页的既有纪律恰恰是"判据只此一处"（`ui/signin/ScanRecoveryPolicy.kt:125` 那句「⚠️ UI 侧不许绕过这里直接读 giveUpReason 拼分支」同一条理由）。另外测试侧有 4 处手搓 `giveUpReason` 字符串（`app/src/test/java/com/buaa/schedule/ui/signin/ScanFrameFlowGuardTest.kt:93`、`app/src/test/java/com/buaa/schedule/ui/signin/ScanRecoveryPolicyTest.kt:154,158,176,210`），其中 `:176`/`:210` 递的是"回到前台额度用完"这一档 —— 生产代码目前**从不**产生这个值，改成类体属性后这一档连表达方式都没了（它不是 `(consecutiveFailures, suspensionCycles)` 的函数）。这一枚恰好说明 ① 不是万能刀。

② 形状守卫（本仓已有四种钉法）：不换形状，而是把"不发火"的前提钉死。推荐三条一起钉，成本比 ① 低、且不引入第二处判据：
- **逐字节**：钉 `ui/signin/ScanRecoveryPolicy.kt:150` 那一整行原文 `if (health.giveUpReason != null) return health` —— 它是这枚字段唯一的保护，删掉它当场红。
- **数出现次数**：`assertEquals(1, occurrences(f, "giveUpReason ="))` —— 生产文件里赋值点只许有一处（现在正好 1 处，在 `:160`）。第二处赋值 = 第二把尺子。
- **读形状**：`assertFalse(f.contains(Regex("""copy\(\s*consecutiveFailures\s*=[^)]*\)""")))` 的反面做法太脆，改成数出现次数更稳：`assertEquals(2, occurrences(f, "consecutiveFailures = consecutive"))` 并把 `:165`/`:167` 两处的行号写进断言消息，改站点必须同步改守卫。
- （可选）**抹注释找锚点**：`ui/signin/ScanRecoveryPolicy.kt` 里给"判死只有一处赋值"留一行锚点注释（例如 `// giveUpReason 的赋值全仓只此一处`），守卫先抹掉 `//` 与 `/* */` 再数这句锚点是否还在，防止有人靠改注释绕过。

**我倾向 ②**。理由就一句：这一枚派生值**不是同表参数的纯函数**（它还要"判到哪一档"这个上下文），把它做成类体属性要么复制判据、要么丢档位；而它的不发火前提是一行显式代码，一行显式代码是可以被守卫钉住的，`copy()` 的语义盲区才是钉不住的。对照 ①/② 的分工：**能写成 `f(同表参数)` 的走 ①（`PendingImport` 那枚就是），写不出的走 ② 把运行期前提钉成静态前提。**

### 2.2 顺带量到的一枚口径问题（不算本族，写进回执）

`ui/ScheduleViewModel.kt:1378` `_calendarSync.update { it.copy(syncing = true, message = null, diff = null) }` 把 `diff` 清空却没清 `skippedOccurrences`，而两者是 `CalendarSyncManager.computeDiff` 一次返回的同一对（`ui/ScheduleViewModel.kt:1394,1395` 成对写、`dismissCalendarSyncDiff` 在 `:1430` 成对清）。它**不满足 (a)**（`skippedOccurrences` 不是 `diff` 的函数，两枚都来自外部那趟计算），且现在读不到旧值：唯一渲染点在 `ui/settings/SettingsScreen.kt:1914`，而它整块长在 `ModalTransition(payload = calendarSync.diff)`（`:1903`）里，`diff == null` 时不组合。所以不列进主表，只在此留一行。

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
其余 copy 站点逐处读过原文，每处改的都是源字段本身：`ui/course/CourseManagementScreen.kt:183`（`primary.copy(colorIndex = index, customColorArgb = null)`）、`domain/schedule/CourseConstraints.kt:73-80`（归一化，`:79` 连 `credit` 都自己算）、`domain/schedule/ImportPlanner.kt:50`（`course.copy(id = old.id, credit = course.credit ?: old.credit)`）、`:53-57`（并周次 + 学分取已知值）、`data/import/BuaaScheduleParser.kt:136-143`（同格并片段）、`data/repository/ScheduleRepository.kt:215,223`、`ui/home/HomeScreen.kt:428,442`、`ui/home/ConflictWizardDialog.kt:81`、`ui/ScheduleViewModel.kt:500`。

### 3.3 `ScheduleUiState.conflicts` —— a 成立、b 不存在，而且结构上永远不可能存在

`ui/ScheduleViewModel.kt:467-472`
```
467:         ScheduleUiState(
468:             courses = visibleCourses,
469:             semester = semester,
470:             timeSlots = timeSlots,
471:             conflicts = ConflictDetector.findConflicts(visibleCourses),
```
`:471` 就是 `:468` 那枚同表参数的函数（与 T94 同一把尺子的形状）。但全仓 **0 处 `ScheduleUiState.copy`**，而它唯一的生产者就是这个 `combine` 块（`:456-477`）——四个源里任何一个一动就整枚重建，`courses` 与 `conflicts` 因此在类型层面不可能各说各话。这一枚最像"下一个 T94"，值得记一句：**将来谁给它加 copy 站点（比如想只改 `currentWeek` 而不重算冲突），必须先回来读这一行。**

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

- `data/calendar/CalendarSyncManager.kt:171-174`：
  ```
  171:                 val upserts = chunk.map { (mapping, occurrence) ->
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
| `BackupPreview` | `ui/ScheduleViewModel.kt:1211` | 局部 `data: BackupData` | `:1237` `courseCount = data.courses.size`、`:1238` `manualCourseCount = data.courses.count { it.isManualOverride }` |
| `ImportHistory` | `domain/model/ImportHistory.kt:8` | 局部 `selection` | `ui/ScheduleViewModel.kt:767` `courseCount = selection.toWrite.size`（`toWrite` 不在 `ImportHistory` 参数表上） |
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
4. **T95 那次修复的装机兑现**：不属于本卡范围，但要点明 —— 本卡对 `PendingImport` 的"已修"判定同样只是读码（`ui/ScheduleViewModel.kt:164` + 形状守卫 `app/src/test/java/com/buaa/schedule/ui/PendingImportConflictGroupTest.kt:185-211`），没有重装重测。

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
| T95 修复哈希 | `45e7fc1` | 一致（`45e7fc1 T95①②`），参数表里那枚 `val conflictGroupCount: Int,` 已不在，改为 `ui/ScheduleViewModel.kt:164` 的类体属性 |

## 5. 收单时可用的三条规矩（本卡不动手，只把形状摆出来）

1. **一句话判据**：新加一枚 data class 构造参数时，若它的值 = f(同表另一枚参数)，只有两条正当落点 —— ① 类体 `val x = f(…)`（永远不过期，代价是不进 `equals`/`componentN`），② 留在参数表但给它**每一个** copy 站点配一条"数出现次数"守卫。**没有第三条**：留在参数表而不点名，就是 T94。
2. **① 与 ② 的分工线**（本卡新量到的）：只有当派生值是**同表参数的纯函数**时 ① 才成立。`ui/signin/ScanRecoveryPolicy.kt:160` 那枚是"判到哪一档"这件事的格式化产物，判据本体（`:155` 的 `cycles >= MaxDecodeSuspensionCycles`）搬进类体会造成第二处判据，所以那一枚只能走 ②。
3. **守卫钉得住已知、钉不住未知**：形状守卫能钉"`conflictGroupCount` 不再出现在参数表上"（`app/src/test/java/com/buaa/schedule/ui/PendingImportConflictGroupTest.kt:196` 读形状 + `:200` 逐字节 + `app/src/test/java/com/buaa/schedule/ui/importing/ImportConflictCopyTest.kt:298` 数出现次数 = 3 处读点），但**下一枚新加的派生字段它一个字都不会说**。所以真正该配的还是"改源之后立刻读派生"那条表驱动用例（`app/src/test/java/com/buaa/schedule/ui/PendingImportConflictGroupTest.kt:175` 的 2 → 1 → 0 三档读数 + `:210` 那处"两处 copy 站点还在"的计数）。给本族的收单问题保持两条：这枚字段的每个拷贝站点被钉了吗？有没有一枚用例真的"改了源再去读它"？
