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
| `Course` | `isManualOverride` | 无害 | `domain/model/Course.kt:38` | 13 处（§0.4），**无一改 `sourceGroupKey`** | `domain/schedule/ImportPlanner.kt:40`、`data/repository/ScheduleRepository.kt:200` 一带 | `ui/editor/CourseEditorScreen.kt:227` `isManualOverride = … || initialCourse?.sourceGroupKey != null` 与同表参数 `sourceGroupKey`（`:225`）构成「有来源键 ⇒ 必标手动」的不变式；13 处 copy 没有一处写 `sourceGroupKey` ⇒ 改源不成立 |
