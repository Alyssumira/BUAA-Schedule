# 派生构造参数审计（T97 · 只报不动手）

分支 `ai/T97`，基点 `788acf5`。本文件是**唯一**被本卡新增的文件，不改任何代码。
所有 `file:line` 都是本次 `Read`/`grep` 出来的原文（路径相对仓库根，行号按本工作树）。

## 0. 口径、规模、判据怎么执行的

### 0.1 我自己数出来的规模

| 量 | 卡面给的 | 我实测的 | 说明 |
| --- | --- | --- | --- |
| `app/src/main/java` 下 `data class` 声明 | 85 枚**文件** | 85 枚文件、**177 行**声明 | 一行一枚，含 sealed 族里的 `data class Accepted(...) : Envelope` |
| 含 `.copy(` 的文件 | 34 | 34 | 一致 |
| `.copy(` 出现次数 | 126 | **140 次 / 139 行**（`WeekView.kt:2132` 那类一行两处） | 卡面少 14 次；我用 `grep -o` |
| 另：无接收者的隐式 `copy(` | 未提 | **1 处**：`ui/home/WeekGridGeometry.kt:253` | 扩展函数 `CourseDragState.advancedBy` 里的 `return copy(...)`，**只 grep `.copy(` 会整条漏掉**，见 §0.3 |

141 处（140 + 1）里落在**本仓自己的 data class** 上的是 **71 处 / 18 枚类**；其余 70 处是 Compose 与平台类型（`Color.copy`、`Highlight.copy`、`TextStyle.copy`、`material3.ColorScheme.copy`、`Constraints.copy`），与本族无关。逐行分类见 §0.4 的落点表。

### 0.2 三档判据的实际执行方式

- **(a) 派生**：不只看定义处，而是**扫全部构造站点的实参**。参数默认值那一档全仓只有 27 枚字段带函数调用默认值，且全是 `emptyList()` / `LocalDate.now()`（`ui/ScheduleViewModel.kt:83`）——**没有一枚 `val x: Int = f(同类的另一枚参数)` 形状**。也就是说 T94 那种「派生表达式写在参数表默认值上」的形状在全仓当前是 0 枚；剩下能成立的是「调用点算好再递进去」，所以我把每个候选类的**每一处构造**都读了原文。
  同时排除掉一种**看着像 a 其实不是**的常见形状：多枚参数由同一个**外部对象/局部量**算出来（`ScanRejectInfo` 十枚字段全取 `shape.*`、`BackupPreview` 的 `courseCount`/`manualCourseCount` 全取 `data.courses`、`Meeting` 的 `location`/`teacher` 全取 `course.*`）。这叫**同源扇出**，任何一枚都不是"由同类另一枚构造参数算出来的"，`copy` 换掉其中一枚也不会让另一枚过期（它们各自独立地过期不了）。只有当参数值确实是**同表另一枚参数的函数**时才算 a，例如 `CourseWeekSpans.kt:140` `unknownCount = rows.count { it.weeksUnknown }` 而 `rows = rows` 就是同表参数。
- **(b) copy 站点改源**：以 §0.4 的 71 处为准逐处读原文，判它改的是不是 (a) 那枚派生所依赖的源参数。
- **(c) 真读到旧值**：grep 字段名在 `app/src/main` 的读取点，并要求读取发生在 copy 之后的那枚对象上。

### 0.3 本卡筛法的一个已知盲点

按位置构造（`Coverage(key, course, course.displayName, …)`）我**没有**程序化解析——只读得出来「哪个实参落在第几个参数上」。风险被两件事压住了：本仓 data class 的构造站点几乎清一色用具名实参（我读过的 51 处构造里只有 `WeekFreeGrid.kt:88`、`ScanDecodingAdmission.kt:115-118`、`SemesterStats.kt:263-265` 这几处按位置或半按位置传，都逐条看过）；而**所有 71 处自有类的 copy 站点是逐处读原文定性的**，(b) 这一格没有盲点。

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
| `Occurrence` | `stableId`、`contentHash` | 无害 | `domain/schedule/ScheduleOccurrences.kt:24,26` | 无（`Occurrence` 本身） | `data/calendar/CalendarSyncPlanner.kt:26,29,31,33,42`、`data/calendar/CalendarSyncManager.kt:369,377` | `:96,97` 两枚都是 `(course, week, segment, date…)` 的函数，这些全是同表参数（`:91,95,92`）。注意 `CalendarSyncManager.kt:173` 那处 copy 属于 `CalendarSyncEntity` 且**显式带了新的 `contentHash`** |
| `TintPlate` | `secondaryForeground` | 无害 | `core/designsystem/Color.kt:192` | 无 | `ui/home/DayView.kt:1113` | `:244` `secondaryForeground = dimForeground(foreground, luma)`，`foreground` 即同表参数（`:243`）—— :186 那句注释「三个字段要一起用，拆开就没有意义」正是这一族该有的警告，但它没有 copy 站点可拆 |
| `CourseGroup` | `displayName` | 无害 | `ui/course/CourseManagementScreen.kt:444` | 无 | `ui/course/CourseManagementScreen.kt:428`（排序键）、列表行标题 | `:421` `displayName = groupDisplayNameOf(fragments, primary)`，`fragments` 就是同表参数（`:423`）。无 copy ⇒ 不会「改了片段却不改门面名」 |
| `ParseOutcome` | `fallbackWeekCourses`、`unknownTeacherCourses` | 无害 | `data/import/BuaaScheduleParser.kt:22,24` | 无 | `ui/importing/BuaaLoginScreen.kt:192,193,195,196` | `:118,119` 两枚都是 `merged.count { … }` 而 `:117` `courses = merged` 是同表参数。无 copy |
| `ReminderPlan` | `triggerAtMillis` | 无害 | `reminder/ReminderScheduler.kt:52` | 无 | `reminder/ReminderScheduler.kt:158,160,358`（排闹钟与取最近一条） | `:346` `triggerAt = occurrence.classStart… - advanceMinutes * 60_000L`，而 `:352` `classStart = occurrence.classStart`、`:353` `advanceMinutes = advanceMinutes` 都是同表参数 ⇒ 它是两枚同表参数的函数。无 `ReminderPlan.copy`（全仓该类只构造一次） |
| `DayCourseRow` | `startTime`、`endTime` | 无害 | `ui/home/DayView.kt:703,704` | 无 | `ui/home/DayView.kt:678,679` | `:728,729` 取同表参数 `segment`（`:726`）的首末节去查节次表。`private` 类、只在 `:724` 构造一次 |
| `CourseMenuRequest` | `anchorX` | 无害 | `ui/home/WeekView.kt:1623` | 无 | `ui/home/WeekView.kt:1650`（菜单弹层定位） | `:942,1081` `anchorX = index * dayWidthPx + …`，`index` 即同表参数 `dayIndex`。无 copy |
| `CourseDragState` | `originStartPeriod`、`targetStartPeriod`、`originTopPx` | 无害 | `ui/home/WeekGridGeometry.kt:219,222,224` | 有 1 处：`ui/home/WeekGridGeometry.kt:253`（隐式 `copy`） | `ui/home/WeekView.kt:901,907,1032` | `:881,1006` `originStartPeriod = segment.first`（同表参数），但 `:253` 那处 copy 改的是 `totalOffset`/`targetDayIndex`/`targetStartPeriod`，**没碰 `segment`/`originTopPx`** ⇒ 改源不成立 |
| `BlankDecodingTrace` | `occurrence` | 无害 | `ui/signin/ScanDecodingAdmission.kt:162` | 无 | `ui/signin/SpocScanScreen.kt:1387,1389` | `:176` `occurrence = count`，而同一行的 `ledger = BlankDecodingLedger(count, …)` ⇒ `occurrence == ledger.count` 是两枚参数间的真不变式。无 copy |
| `DecodingAdmission` | `payloadLength`（与 `blankness` 的口径约束） | 参照：**严格不算 a**，见 §3.6 | `ui/signin/ScanDecodingAdmission.kt:92` | 无 | `ui/signin/ScanDecodingAdmission.kt:197`（取证行「长度=」） | `:115-118` 两枚各自独立地由那颗 `payload` 算出，`payloadLength` 不是 `blankness` 的函数，只有值域约束（注释 `:91`「NoText 与 EmptyText 都是 0」）。`admitted` 长在类体 `get()`（`:95-96`）= 安全形状 |
| `WidgetData` | `courses` | 无害 | `widget/WidgetDataCache.kt:22` | 无 | `widget/WidgetCommon.kt:288,446,656`、`widget/WidgetDataSynchronizer.kt:273` | `:84` `courses = repository.getDisplayCourses(semester)`，`semester` 就是同表参数（`:83`）。无 copy，且整枚换引用（`WidgetDataCache.kt:88`） |
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
—— 判死之后这个函数对**任何**输入都原样返回同一枚对象（`SpocScanScreen.kt:1214` 还靠 `if (next === health) return` 的引用相等提前退出），所以 `:165`/`:167` 这两条不重算的路径只在 `giveUpReason == null` 时走得到，走过去之后它仍然是 `null`，而 `null` 恰好是对的（还没判死）。复位那两档（`:183`、`:195`）用的是**整枚新构造** `ScanDecoderHealth(pageVisibleRecoveries = …)`，不是 copy ⇒ 不会带走旧字符串。

**所以它是"潜在"而不是"真回归"的准确说法**：正确性挂在一行运行期早返回上，而这一行**没有任何静态守卫钉着**。将来谁在别处加一处 `health.copy(consecutiveFailures = …)`（例如"复探失败要再记一笔"这类改动，正是 `SpocScanScreen.kt:1213` 现在唯一调 `healthAfterDecodeFailure` 的那条链被拉长）、或者把 `:150` 那行改成"判死后还允许再数几帧"，界面上立刻出现的错是：

- 界面/文案侧：`ui/signin/SpocScanScreen.kt:1072` 那行 `val why = health.giveUpReason ?: "停用窗口内"` 会念出**上一轮的**"连续 N 帧解码失败、自动试回 M 轮仍不成"，而 N/M 与当时账上的 `consecutiveFailures`/`suspensionCycles` 已经不是同一把尺子；`ui/signin/ScanFrameFlowPolicy.kt:142` 那行 Warn 取证同样带着旧数（这一页修过三轮"静默 no-op"，读数不一致正是它自己的靶子）。
- 判据侧：`scannerGiveUp`（`:128`）只判 `!= null`，字符串内容过期不影响分档；受影响的只有**说给用户/日志看的那句话里的两个数**。

**两种最小修法**：

① 类体派生属性（T95 那一刀）：把 `:65` 从参数表挪进类体，写成
`val giveUpReason: String? = if (suspensionCycles >= MaxDecodeSuspensionCycles) "连续 $consecutiveFailures 帧解码失败、自动试回 $suspensionCycles 轮仍不成" else null`
之后 `:156-161` 那个分支就不必再点它（早返回 `:150` 也可以改成 `if (scannerGiveUp(health)) return health`，形状更干净）。
代价与风险：**这一枚不是 `PendingImport.conflictGroupCount` 那种"纯函数"**。`groupConflicts(conflicts)` 只吃 `conflicts` 一枚，而这里要把"判死这件事"（`cycles >= MaxDecodeSuspensionCycles`）一起搬进类体，等于把判据从 `:155` 那行**复制**到类体里 —— 判据就此变成两处（`ui/signin/ScanRecoveryPolicy.kt:155` 的分支条件 + 类体的三元），而这一页的既有纪律恰恰是"判据只此一处"（`ui/signin/ScanRecoveryPolicy.kt:125` 那句「⚠️ UI 侧不许绕过这里直接读 giveUpReason 拼分支」同一条理由）。另外测试侧有 4 处手搓 `giveUpReason` 字符串（`app/src/test/.../ScanFrameFlowGuardTest.kt:93`、`app/src/test/.../ScanRecoveryPolicyTest.kt:154,158,176,210`），其中 `:176`/`:210` 递的是"回到前台额度用完"这一档 —— 生产代码目前**从不**产生这个值，改成类体属性后这一档连表达方式都没了（它不是 `(consecutiveFailures, suspensionCycles)` 的函数）。这一枚恰好说明 ① 不是万能刀。

② 形状守卫（本仓已有四种钉法）：不换形状，而是把"不发火"的前提钉死。推荐三条一起钉，成本比 ① 低、且不引入第二处判据：
- **逐字节**：钉 `ui/signin/ScanRecoveryPolicy.kt:150` 那一整行原文 `if (health.giveUpReason != null) return health` —— 它是这枚字段唯一的保护，删掉它当场红。
- **数出现次数**：`assertEquals(1, occurrences(f, "giveUpReason ="))` —— 生产文件里赋值点只许有一处（现在正好 1 处，在 `:160`）。第二处赋值 = 第二把尺子。
- **读形状**：`assertFalse(f.contains(Regex("""copy\(\s*consecutiveFailures\s*=[^)]*\)""")))` 的反面做法太脆，改成数出现次数更稳：`assertEquals(2, occurrences(f, "consecutiveFailures = consecutive"))` 并把 `:165`/`:167` 两处的行号写进断言消息，改站点必须同步改守卫。
- （可选）**抹注释找锚点**：`ui/signin/ScanRecoveryPolicy.kt` 里给"判死只有一处赋值"留一行锚点注释（例如 `// giveUpReason 的赋值全仓只此一处`），守卫先抹掉 `//` 与 `/* */` 再数这句锚点是否还在，防止有人靠改注释绕过。

**我倾向 ②**。理由就一句：这一枚派生值**不是同表参数的纯函数**（它还要"判到哪一档"这个上下文），把它做成类体属性要么复制判据、要么丢档位；而它的不发火前提是一行显式代码，一行显式代码是可以被守卫钉住的，`copy()` 的语义盲区才是钉不住的。对照 ①/② 的分工：**能写成 `f(同表参数)` 的走 ①（`PendingImport` 那枚就是），写不出的走 ② 把运行期前提钉成静态前提。**

### 2.2 顺带量到的一枚口径问题（不算本族，写进回执）

`ui/ScheduleViewModel.kt:1378` `_calendarSync.update { it.copy(syncing = true, message = null, diff = null) }` 把 `diff` 清空却没清 `skippedOccurrences`，而两者是 `CalendarSyncManager.computeDiff` 一次返回的同一对（`:1389-1396` 就是成对写的，`dismissCalendarSyncDiff` 在 `:1430` 也成对清）。它**不满足 (a)**（`skippedOccurrences` 不是 `diff` 的函数，两枚都来自外部那趟计算），且现在读不到旧值：唯一渲染点在 `ui/settings/SettingsScreen.kt:1914`，而它整块长在 `ModalTransition(payload = calendarSync.diff)`（`:1903`）里，`diff == null` 时不组合。所以不列进主表，只在此留一行。

