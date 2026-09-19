# T17 release 收缩审计（2026-09-19）

基线 `7f02183`（master）。全部数字来自本机同一棵 `app/build` 的反复 `:app:assembleRelease`，
产物是 unsigned APK（本 worktree 没有 `local.properties`，构建会打印
"⚠️ 未配置发布签名…"，与体积无关）。中间产物（usage.txt / aapt2 dump / size 表）放在
`D:\schedule\.tmp\t17\`，**没有入库**。

标准命令（后续复现都要带这三个环境变量，否则本 worktree 报 `SDK location not found`）：

```
cd /d/schedule/BUAA-Schedule/.worktrees/T17 && \
JAVA_HOME="D:\\AndroidSDK\\jdk-21" ANDROID_HOME="D:\\AndroidSDK" \
PATH="/d/AndroidSDK/jdk-21/bin:$PATH" \
/d/AndroidSDK/gradle-8.14.3/bin/gradle.bat --offline :app:assembleRelease -q
```

## 0. 实测总账

| 状态 | APK | classes.dex（压缩后） | classes.dex（原始） | resources.arsc | res/\* 合计（压缩后） |
|---|---|---|---|---|---|
| 基线 `7f02183` | 6,357,067 | 2,546,506 | 5,347,196 | 100,484 | 555,323 |
| Q1 收窄实体 keep（已落地） | 6,353,263 | 2,542,706 | 5,334,820 | 100,484 | 555,323 |
| Q1 变体 A：只 keep 类名、不钉成员 | 6,352,439 | 2,541,867 | 5,332,356 | 100,484 | 555,323 |
| Q1 变体 B：整行删掉（类名也不 keep） | 6,351,827 | 2,541,270 | 5,331,752 | 100,484 | 555,323 |
| Q2 实验：删第一条 `*** Companion;`（叠在 Q1 上，**未落地**） | 6,352,207 | 2,541,640 | 5,330,224 | 100,484 | 555,323 |
| **最终 = Q1 + Q3** | **6,199,761** | **2,473,302** | 5,194,140 | **67,712** | **513,562** |

一句话总账：**这一轮实裁 -157,306 字节（-2.47%）**，其中 153,502 来自 Q3 那一行
`android.r8.optimizedResourceShrinking`，3,800 来自 Q1。dex 占比从 40.1%（2,546,506/6,357,067）
降到 39.9%（2,473,302/6,199,761）—— **dex 仍然是第一大块，这一轮没能动它多少**。

大小测量脚本（zipfile，逐 entry 的 compress_size）：

```
python /d/schedule/.tmp/t17/sizes.py baseline /d/schedule/.tmp/t17/base.apk final <apk>
```

---

## 1. `-keep @androidx.room.Entity class * { *; }` 里的 `{ *; }` 是否必要

**结论：不必要，已收窄为只钉构造函数。** 但假设里的**机制**是错的，而且错得值得记下来。

### 证据

`usage.txt` 差集：基线（`{ *; }`）vs **落地形态**（只钉 `<init>(...)`）=
`166` 个成员从"被 keep"翻成"可移除"，落在 **14 个 @Entity 类**上，**其中构造函数 0 个**
（正是钉住的效果）。作为对照，变体 A（只把 `{ *; }` 去掉、成员一条都不钉）是
`180` 个成员 / `16` 个类。两数之差是 14，但 q1c→q1 的实测差集是 **15 个成员 / 9 个类**
（说明有 1 个成员只在落地形态那一侧被判可移除，两个方向不对称，我没有深挖），组成是：
5 个合成的 `<init>(...,DefaultConstructorMarker)`（Course/ImportHistory/Reminder/Semester/TimeSlot
五个实体各 1）、`WorkProgress` 的真 `<init>` 加 2 个字段、`WorkSpec.copy$default`、
`WorkTypeConverters.backoffPolicyToInt` / `outOfQuotaPolicyToInt`、
`WorkInfo$State$EnumUnboxingLocalUtility` 的 4 项。
—— 这批里含构造函数，所以"钉住 `<init>(...)`"这一步不是白花的。

自有 7 个实体（106 个成员）：`CalendarSyncEntity` +16、`CourseEntity` +35、
`ImportHistoryEntity` +15、`ReminderEntity` +8、`SemesterEntity` +12、`TimeSlotEntity` +11、
`WidgetSnapshotEntity` +9。另外 **7 个 WorkManager 自己的实体被这条规则顺带多钉了 60 个成员**
（`androidx.work.impl.model.WorkSpec` +40、`Preference` +6、`SystemIdInfo` +6、
`Dependency` / `WorkName` / `WorkProgress` / `WorkTag` 各 +2）。

内容全是一眼可判死代码：`getId()` / `getName()` 这类属性 getter、`copy()`、`copy$default()`、
`component1()..component16()`、`$stable`、`<clinit>()`。

生成的访问方式（`app/build/generated/ksp/release/.../CourseDao_Impl.kt`）：

```kotlin
statement.bindLong(1, entity.id)                 // 属性访问 → 编译成 getId()
_item = CourseEntity(_tmpId, _tmpName, ...)      // 直接 new，不是反射
```

收窄后 `mapping.txt` 里实体字段仍在（`long id -> a` … 共 16 个）、主构造函数签名原样保留，
`getId()` 系列**整条消失** —— 即 R8 把 getter 调用改写成直接字段读写，两端同步改，不依赖名字。
所以原注释那句"实体构造函数由生成的 Dao_Impl / AppDatabase_Impl 反射调用"两件事都不成立：
实体既没有被反射构造、也没有被反射读字段，**全部静态可达**。真正需要反射 keep 的只有
`RoomDatabase` 子类（`RoomDatabase.Builder` 走 `Class.forName("<名>_Impl")`），
而那一行第 27 行本来就留着 —— 并且 `room-runtime` 的 consumer rule 里已经有同一条
（`configuration.txt:1075`），第 27 行其实是双保险。

### 为什么这条可以动（风险都排掉了）

- **和 kotlinx.serialization 的交集为空**：全仓 29 个 `@Serializable` 全在
  `data/backup/BackupModels.kt`、`data/import/BuaaDtos.kt`、`data/import/BuaaInPageFetcher.kt`、
  `widget/WidgetSnapshotModels.kt`、`update/UpdateInfo.kt`、`reminder/IslandFocusTemplate.kt`
  这些 DTO 上，**没有一个 `@Entity` 类是 `@Serializable`**。备份/分享口令走
  `BackupData`/`BackupCourse`，其 `$$serializer` 由第 11-17 行保护，这一轮一个字没改。
  唯一沾到实体的序列化点是 `WidgetDataSynchronizer.kt:140`
  `json.decodeFromString<WidgetSnapshotDto>(entity.dataJson)` —— 类型参数是 DTO，
  实体只是被静态读一个 `String` 字段。
- **按名字反射为 0**：
  `grep -rn "getIdentifier\|getDeclaredField\|getField\|kotlin.reflect\|memberProperties" app/src/main --include=*.kt`
  → **0 命中**（退出码 1）。另一条
  `grep -rn "Class.forName" app/src/main --include=*.kt` → **1 命中**：
  `IslandDiagnostics.kt:69` 的 `Class.forName("android.os.SystemProperties")`，读的是框架类，
  与实体成员无关。
- **收益**：classes.dex 2,546,506 → 2,542,706（**-3,800**），APK -3,804。
  变体 B（连类名都不 keep）只再多省 597 字节，不值得那点"实体类名在崩溃栈里消失"的代价，
  所以落地形态保留了类名。

提交：`98962a1`。

### 运行期待验证项（Q1）

minify 只在 release 生效，单测证不了。装机后按这个顺序点：

1. **课表同步 / 首屏渲染**：登录后拉一次课表 → 走 `CourseDao_Impl.insert`/`getAll`，
   是实体写入+读取的全链路（最能打穿"getter 被改写"这条）。
2. **备份导出 → 清数据 → 恢复**：导出后经 `BackupData`（DTO，不是实体）回来，
   再确认课表/学期/节次/提醒都还在。
3. **分享口令生成 + 另一台设备导入**：同一条序列化链路，另一侧验证。
4. **小组件刷新**：`WidgetSnapshotEntity.dataJson` 读写 + `WidgetSnapshotDto` 反序列化。
5. **日历同步**：`CalendarSyncEntity` 是唯一带 16 个新可移除成员的实体，
   开一次日历提醒模式 → 看 `calendar_sync` 表是否照常写入、重复同步是否幂等。
6. **WorkManager 侧**（这轮顺带放开的 7 个内部实体）：课前提醒排程 + 开机重建后任务表还在、
   提醒照常触发。

---

## 2. 第 12-17 行两条 kotlinx.serialization 规则：第一条是否冗余

**结论：就它的写作目的而言确实冗余，但不是零成本冗余，所以不动。已进"不动"清单。**

### 证据

把第 12-14 行（`-keepclassmembers class com.buaa.schedule.** { *** Companion; }`）注释掉，
其余保持不变，重跑：

- 第 15-17 行那条**足够**保护序列化：`mapping.txt` 里
  `com.buaa.schedule.data.backup.BackupData$Companion -> ...` 之下
  `1:3:kotlinx.serialization.KSerializer serializer():17:17 -> serializer` 原样存活；
  `BackupData` / `BackupCourse` / `BackupReminder` / `WidgetSnapshotDto` 全部在。
- `usage.txt` 差集：`61` 个成员 / `37` 个类翻成可移除，**其中 `@Serializable` 类 0 个** ——
  翻转的全是与序列化无关的 `Companion` 静态字段：`MainActivity`、各 `*Dao_Impl`、
  `ScheduleViewModel`、6 个 `*WidgetProvider`、`BootReceiver` / `ReminderReceiver` /
  `ClassProgressReceiver` / `CourseFluidService` / `WidgetFallbackWorker` 等。
- **但影响面越出了规则本来的用途**：`androidx.activity.ComponentActivity` 与
  `androidx.room.RoomDatabase` 的 `Companion` 静态字段连 `<clinit>()` 也一起翻成可移除 ——
  也就是说这条规则今天还在替两个 AndroidX 库类兜着，而这不是它注释里写的目的。
  我没法解释这条链路（`com.buaa.schedule.**` 模式根本不匹配那两个类），说明 R8 在这上面
  有我们没读明白的交互，属于卡里说的"拿不准"。
- 收益：classes.dex 2,542,706 → 2,541,640（**-1,066**，占最终整包 0.017%）。
- 顺手做的库侧核对：扫 gradle transforms 缓存里 1,294 个 jar，同时含
  `getDeclaredField` 与字符串 `Companion` 的类只有 2 个
  （`androidx/activity/ImmLeaksCleaner`、`kotlinx/coroutines/internal/ExceptionsConstructorKt`），
  都不是"按名字取 Companion 字段"；**kotlinx.serialization 的 jar 一个都没命中**，
  说明序列化侧的 Companion 查找在编译期就被插件静态化、运行期不反射。
  脚本：`python /d/schedule/.tmp/t17/scan_companion.py`。

### 为什么不做

1,066 字节换一次"会动到两个 AndroidX 库类的存活集"的行为改变，收益和风险不对称；
而且删掉它也不会让任何人更读得懂这份规则（今天它的作用已经不在注释说的那件事上了）。
**建议的后续处理**（不在本卡范围）：真要清，先把模式收到 `@Serializable` 类上，
例如 `-keepclassmembers @kotlinx.serialization.Serializable class * { *** Companion; }`，
再比对 usage.txt —— 本轮没有实测过这个写法，所以不当结论用。

---

## 3. `android.r8.optimizedResourceShrinking=true` 单开一行

**结论：实测 -153,502 字节（整包 -2.42%），`assembleRelease` 照常成功，没有发现被误删的资源。已落地。**

### 实测影响

| | 关（Q1 后） | 开 | 差 |
|---|---|---|---|
| APK | 6,353,263 | 6,199,761 | **-153,502** |
| resources.arsc | 100,484 | 67,712 | **-32,772**（-32.6%） |
| res/\* 合计（压缩后） | 555,323 | 513,562 | -41,761 |
| res/ 文件数（含 assets/arsc 的 entry 数） | 337 | 246 | -91 |
| arsc 表条目 | 1,162 | 703 | **-459** |
| classes.dex（压缩后） | 2,542,706 | 2,473,302 | **-69,404** |

dex 那 69,404 是连带效应：没人读的 `R$styleable` / `R$animator` / `R$dimen` 内部类和
`int[]` 常量本身就在 dex 里，资源一少它们就整批消失（`androidx.appcompat.R$styleable`
从"存活"变成只剩 1,124 字节，`androidx.appcompat.R$drawable`、`androidx.fragment.R$animator`
等直接不出现在 mapping.txt 里）。

被删的 459 条按类型：`attr` 169、`style` 117、`color` 51、`dimen` 40、`drawable` 36、
`string` 20、`layout` 17、`animator` 6、`integer` 2、`anim` 1。名字前缀分布（`cut -d/ -f2` 后
按首段统计）：`notification_*` 35、`common_*`（Google Api）31、`abc_*`（appcompat）27、
`tooltip_*` 14、`material_*` 12、`primary_*` 8、`fragment_*` 7、`compat_*` 5、`hint_*` 4、
`background_*` 4、`androidx_*`（compose）3、`vector_*` 2、`status_*` 2 ——
即**全部是合并进 app 包的库资源**（本工程没有 values-\* 里叫这些名字的条目，第 1 条核对覆盖），
没有一个来自本应用自己。

### "有没有明显不该被删的被删了"——三条独立核对，都是 0

1. **本应用自己的资源一个没少。** 从 `app/src/main/res` 枚举出 85 个自有条目
   （`string` 42 / `layout` 16 / `drawable` 14 / `xml` 9 / `mipmap` 2 / `style` 1 / `color` 1；
   枚举脚本对 `values/` 里的 `<item name=..>` 误抓出 4 条 manifest 属性噪声，已剔除），
   改后 arsc 里逐条比对：**MISSING = 0**。`xml/` 下 9 个（`backup_rules`、
   `backup_rules_legacy`、`file_paths`、6 个 `*_widget_info`）全在 —— 这是小组件与
   备份恢复唯一的入口，最该盯的一类。
2. **`raw/` 无从谈起**：本工程（含 `kyant-backdrop`）没有任何 `res/raw` 目录，
   改前改后的 arsc 里都没有 `raw` 类型条目（`uniq -c` 的类型分布里两边都无 raw）。
3. **引用图闭合。** `aapt2 dump resources` 取最终 id 表，再二进制扫
   `resources.arsc` + 全部 `res/*.xml` + `AndroidManifest.xml` 里的 `0x7f` 引用取差集：
   脚本已存成 `D:\schedule\.tmp\t17\refscan.py`，两边原样输出：

   ```
   python refscan.py q3.apk  dump-q3.txt   # apk=q3.apk  table-ids=703  files-with-dangling-refs=0
   python refscan.py q1c.apk dump-q1c.txt  # apk=q1c.apk table-ids=1162 files-with-dangling-refs=0
   ```

   即改前改后都是 **0 处悬空**；arsc 内部引用从 144 种降到 32 种，全部命中最终表。

### 报告里那 16 条看着矛盾的行（下轮复查别被吓到）

`app/build/outputs/mapping/release/resources.txt` 从 11,487 行变成 577 行，格式也换了
（新格式是 `<type>:<name>:<id> reachable from <宿主>`）。里面有 16 条写着"可达"、
但对应资源其实已被删，例如：

```
animator:fragment_open_enter:2130837508 reachable from Field int androidx.fragment.R$animator.fragment_open_enter
string:androidx_compose_ui_autofill:2131558428 reachable from Field int androidx.compose.ui.R$string.androidx_compose_ui_autofill
dimen:tooltip_vertical_padding:2131099765 reachable from res/layout/abc_tooltip.xml
```

逐条查过 16 条的宿主，分两类，都能解释：

- 宿主是 **R$ 内部类**的 11 条（`androidx.fragment.R$animator` 6 条 +
  `androidx.appcompat.R$dimen` 4 条 + `androidx.compose.ui.R$string` 1 条）：
  这四个 R$ 类在最终 `mapping.txt` 里
  **整类不存在**（`grep -cE "^androidx\.fragment\.R\$animator -> " mapping.txt` = 0），
  即没有任何存活代码会去读这些 id。
- 宿主是 **库 XML** 的 5 条（`res/layout/abc_tooltip.xml` 3 条 +
  `res/anim/abc_tooltip_enter.xml` 1 条 + `res/animator/fragment_open_enter.xml` 1 条）：
  `abc_tooltip` / `abc_tooltip_enter` /
  `abc_tooltip_exit` 在**改前改后**的 arsc 表里都查无条目（`grep -c abc_tooltip dump-q{1c,3}.txt`
  都是 0），说明这些文件早被非优化收缩器拿掉了，报告描述的是**合并后、收缩前**的资源集；
  `animator/fragment_open_enter` 则是改前在表里、改后被删（`dump-q1c.txt:1 / dump-q3.txt:0`），
  它的宿主正是上面那条已消失的 `androidx.fragment.R$animator` 字段。

结论：这 16 条是 R8 迭代收敛前的中间报告，不是漏删；加上第 3 条核对的 0 悬空，
两种解释路径都闭合了。

### 为什么"能开"这个前提在本工程成立

`isShrinkResources=true` 已经开着，非优化版收缩器早就在删资源；这一行只是把收缩器换成
R8 驱动的（能看静态字段引用，而不是只看 XML）。风险只有一条：**按名字取资源**。
全仓 `getIdentifier` 为 0（见第 1 节的 grep），资源都经 `R.*` 或 XML 引用。
`app/build.gradle.kts` 第 150-152 行那段注释已经写了这个判断，这一轮把它量化了。

提交：`83993d0`。

### 运行期待验证项（Q3）—— 这一条是本轮风险最大的改动

被删的 459 条里 117 条是 `style`、169 条是 `attr`，这两类是"编译期看不出、
运行时 inflate 才炸"的经典位置。装机后必点：

1. **三个小组件全形态**：今日 / 明日 / 两天 / 周视图 / 下周网格，逐个添加并刷新；
   另外走一次 **配置页**（`WidgetConfigActivity`，圆角背景 `widget_bg_r*` 与
   `*_widget_info.xml` 都在删除名单边上）。RemoteViews 是按 layout id 在**进程外**
   由 system_server inflate 的，是本轮最该盯的路径。
2. **备份 / 恢复**：`xml/backup_rules*`、`xml/file_paths` 决定 FileProvider 与
   自动备份，走一次"导出→清数据→恢复"和一次系统级"备份数据"开关。
3. **分享口令**：粘贴板 + `FileProvider`（`file_paths.xml`）分享一条口令到外部应用。
4. **课表同步**：夜间模式切一次（`values-night`、`drawable-night` 各 1 个），
   以及冷启动首屏（`window_background`、主题 `Theme.BUAASchedule` 的 attr 链）。
5. **扫码签到页**：CameraX `PreviewView` + ML Kit 那一路是 appcompat 资源最后的
   间接使用者（`abc_*` 被删了 17 个 layout），确认预览、反光场景识别、相册识别都正常。
6. **通知与实况/岛**：`ic_notification`、`ic_progress_dot` 两个 drawable，
   课前提醒与下课铃各触发一次；开机重建后再看一遍。
7. **系统级外观**：桌面图标（`mipmap/ic_launcher`，HyperOS 按 versionCode 缓存那条）
   与 `Settings` 页的图标网格。

---

## 4. `appcompat:1.6.1` 在 minified dex 里还剩多少

**背景（编排者已实测，直接引用 `D:\schedule\.tmp\t19-deps.txt:920`）**：
`androidx.camera:camera-view:1.4.2` → `androidx.appcompat:appcompat:1.1.0 -> 1.6.1`，
经 `appcompat-resources:1.6.1` 再带出 `vectordrawable` / `vectordrawable-animated`。

### 实测（apkanalyzer 按包归因，`--proguard-folder` 喂 mapping，size 列是**未压缩** dex 字节）

```
D:\AndroidSDK\cmdline-tools\latest\bin\apkanalyzer.bat dex packages \
  --proguard-folder app/build/outputs/mapping/release <apk>
```

| 包 | 基线（items / dex 字节） | 最终（Q1+Q3 后） |
|---|---|---|
| `androidx.appcompat` 合计 | 1,036 / **124,823**（161 个类） | 218 / **36,974**（53 个类） |
| `androidx.appcompat.widget` | 758 / 94,779 | 217 / 35,850 |
| `androidx.appcompat.view` | 276 / 27,910 | **0（整包消失）** |
| `androidx.appcompat.app` | 1 / 110 | **0** |
| `androidx.appcompat.widget.Toolbar` | **12,653（在）** | **0（已删）** |
| `androidx.appcompat.widget.SearchView` | 6,436（在） | 6,650（仍在） |
| `androidx.appcompat.app.AppCompatDialog` | 不在（早已被删） | 不在 |
| `androidx.appcompat.app.AppCompatActivity` | 不在 | 不在 |

基线里 `androidx` 总盘 2,601,895 字节、dex 可归因总量 4,403,550；最终分别
2,419,081 与 4,275,737。appcompat 从占可归因 dex 的 2.83% 降到 0.86%。

换算成压缩后：最终 dex 压缩比 2,473,302/5,194,140 = 0.4762，
**36,974 × 0.4762 ≈ 17,600 字节压缩后，占整包 0.28%**（这个换算是估的，
dex 内不同区段压缩率不同，所以标注为估算而不是实测）。

### 回答"Toolbar / SearchView / AppCompatDialog 有没有真被 R8 删掉"

- `AppCompatDialog`、`AppCompatActivity`、`appcompat.view`（menu 那一族）在**基线**就已基本被删；
- `Toolbar`（12,653）在**这一轮的 Q3 之后**才被删掉；
- `SearchView`（6,650）**删不掉**，因为它由 AGP 自带的默认规则硬 keep：
  `configuration.txt` 里能读到出处 ——
  `# aapt is not able to read app::actionViewClass ... until b/109831488 is resolved`
  `-keep class androidx.appcompat.widget.SearchView { <init>(...); }`
  来自 `app/build/intermediates/default_proguard_files/global/proguard-android-optimize.txt-8.13.0`。
  R8 没有"解 keep"的语法，所以这条在 `proguard-rules.pro` 里无论怎么写都动不了。

顺带一条对以后有用的因果：Q1 落地前那一次构建的合并配置里，能 grep 到成批的
`-keep class androidx.appcompat.widget.X { <init>(android.content.Context, android.util.AttributeSet); }`
—— `configuration.txt` 第 44-78 行区间，输出被我的 `head -20` 截断在 18 条，
**具体条数没数完**，但名单里含 `Toolbar`、`ActionBarContainer`、`ActionBarOverlayLayout`、
`ActionMenuView`、`SearchView$SearchAutoComplete`、`ViewStubCompat` 等。
开 optimizedResourceShrinking 之后，最终 `configuration.txt` 里
`(android.content.Context, android.util.AttributeSet)` 形态的 keep 实测是 **0 条**
（`grep -cE "android\.content\.Context, android\.util\.AttributeSet" configuration.txt` = 0）、
`-keep class androidx.appcompat` 只剩上面那 1 条 SearchView。
这些规则的出处是 AGP 从**当时存活的 layout XML** 生成的
`app/build/intermediates/aapt_proguard_file/release/processReleaseResources/aapt_rules.txt`
（最终 80 行，内容只剩 Manifest 里的组件与 provider，没有一条 view keep）。
也就是说 **Q3 顺手把 appcompat 那 88KB 砍了**，而 appcompat 剩下的部分是被
"AGP 默认规则 + 还有 layout 引用它"钉住的。

### 结论：这条杠杆不值得再做

- 剩 ~17.6KB（估）压缩后，占整包 0.28%；
- 唯一还能砍动它的路径是**去掉 `camera-view` 对 appcompat 的传递依赖**
  （`app/build.gradle.kts` / `libs.versions.toml` 里 exclude，或把 `PreviewView` 换成
  自绘 `SurfaceView`）—— 两个文件都是本卡明令禁改，且 `SpocScanScreen` 的扫码页
  现在就是靠 `PreviewView`；
- `proguard-rules.pro` 这条路是死的（R8 不能反解 keep）。
**照实说：已经花掉了，别再提。**

---

## 5. `material-icons-extended` 在 release dex 里还剩多少

**结论：0 字节。无需动。**（只报告，本来也没有可改的引用点。）

### 证据

| 检查 | 结果 |
|---|---|
| apkanalyzer 包归因 `androidx.compose.material.icons` / `.filled` | `0`（改前改后都是 0，不是本轮造成的） |
| `usage.txt` 里该包被判定移除的类 | **11,403 行**（`grep -cE "^androidx\.compose\.material\.icons" usage.txt`） |
| `mapping.txt` 里以 `androidx.compose.material.icons` **开头**（即整个类被保留） | **0 条** |
| `seeds.txt` 里该包 | 0 条（没有任何规则 keep 它） |
| classes.dex 里字符串 `material_icons`（扩展图标的 getter 名带 `$material_icons_extended` 后缀） | 0 次 |

它不是"整个包被删所以图标丢了"：真正用到的那 **38 个**图标 builder 被 R8 **方法内联**进了
调用方自己的类。在 `mapping.txt` 里能直接看到证据（左值仍是原方法名，右值已属于宿主类）：

```
17:21:androidx.compose.ui.graphics.vector.ImageVector
  androidx.compose.material.icons.filled.FileDownloadKt.getFileDownload(Icons$Filled):26:26 -> <clinit>
```

按 mapping.txt 统计，内联进最终 dex 的 builder 恰好 38 个，和源码里 `Icons.` 的用量一致：
`Add ArrowDropDown CalendarMonth Check Close CloudDownload Code DateRange Delete
DeleteSweep Description Edit EditCalendar EventAvailable EventBusy Face FileDownload
Gavel History Info Insights KeyboardArrowDown Link Lock Notifications Palette Person
QrCode2 Refresh Restore Schedule School Search Settings Share Warning Widgets`
（脚本：`python /d/schedule/.tmp/t17/` 下对 mapping.txt 的正向扫描）。

所以这个"有名的大户"在本工程里的实际成本是**按用量计费**的 38 个 ImageVector，
不是工件里那两千多个 —— 换 `material-icons-core` + 手抄 path data 能省下的字节接近 0，
而要改十几个文件的引用点。**别开这张卡。**

---

## 6. 门禁与本卡验收

```
cd /d/schedule/BUAA-Schedule/.worktrees/T17 && JAVA_HOME="D:\\AndroidSDK\\jdk-21" \
ANDROID_HOME="D:\\AndroidSDK" PATH="/d/AndroidSDK/jdk-21/bin:$PATH" \
/d/AndroidSDK/gradle-8.14.3/bin/gradle.bat --offline \
:app:testDebugUnitTest :app:lintDebug --rerun -q   # 退出码 0，日志 /d/schedule/.tmp/t17-gate.log
```

判定按报告文件读，不看 `echo $?`：

- `app/build/test-results/testDebugUnitTest/*.xml`：101 个 XML，
  **tests=769 failures=0 errors=0 skipped=0**
- `app/build/reports/lint-results-debug.txt`：末行 **`0 errors, 14 warnings`**

`:app:assembleRelease --offline -q` 在最终提交态重跑：**退出码 0**，
产物 `app/build/outputs/apk/release/app-release-unsigned.apk` = 6,199,761 字节
（构建按预期打印"⚠️ 未配置发布签名…"）。

## 7. 不动清单（本轮判定为"别碰"）

| 项 | 判定 | 一句话理由 |
|---|---|---|
| 第 12-14 行 `*** Companion;` | 不动 | 目的上冗余（第 15-17 行足够），但删除会连带放开 `androidx.activity.ComponentActivity` / `androidx.room.RoomDatabase` 的 Companion，只换 1,066 字节 |
| 第 11 / 15-17 / 18-23 行序列化规则 | 不动 | 备份与分享口令的唯一屏障，实测本轮所有改动都不触碰它们 |
| 第 27 行 `RoomDatabase { <init>(); }` | 不动 | 与 room-runtime consumer rule 重复，但它是**真反射**（`Class.forName("<名>_Impl")`），删了没有任何收益 |
| 第 28 行实体**类名** keep | 保留 | 完全不 keep 只再多省 597 字节，代价是实体类名从崩溃栈里消失 |
| appcompat / camera-view 依赖 | 不动 | 见第 4 节，R8 侧已无路，剩 0.28%，且改动落在禁改文件里 |
| material-icons-extended | 不动 | 见第 5 节，包侧 0 字节 |
| `.so` 打包方式、zxing-cpp、ML Kit 模型 | 未触碰 | 卡里已判定为花掉的杠杆 |

## 8. 本轮没做到的部分（如实）

1. **Q1/Q3 的运行时结论全部靠静态证据**（usage.txt 差集、mapping.txt、arsc 引用图），
   没有任何真机/模拟器证据 —— minify 只在 release 生效，769 个单测对这条路径完全无感。
   第 1、3 节各写了"运行期待验证项"，Q3 那节优先级更高（RemoteViews 跨进程 inflate）。
2. **Q4 的"压缩后 ≈17.6KB"是按整包 dex 压缩比折算的估算**，不是实测：apkanalyzer 的
   `dex packages` 只给未压缩字节，逐类的压缩后归属要 dexlib 级别的重打包，本轮没做。
   未压缩的 36,974 / 124,823 是实测。
3. **Q3 的 -69,404 dex 字节只归因到"哪些 R$ 内部类整批消失"这一层**
   （`androidx.fragment.R$animator` / `androidx.appcompat.R$dimen` /
   `androidx.compose.ui.R$string` / `androidx.appcompat.R$drawable` 从 mapping.txt 里整类不见，
   `androidx.appcompat` 从 124,823 降到 36,974 未压缩字节），
   **没有逐项拆到每个类贡献多少**。第 4 节那张表能覆盖 appcompat 一部分，其余是黑箱。
   （原先我担心 `resources.txt` 里 16 条矛盾报告有漏删，后来逐条查清：11 条的宿主 R$ 类
   在最终 mapping.txt 里整类不存在，5 条的宿主库 XML 在改前改后的 arsc 表里都查无条目，
   不再是未决项。）
4. **Q2 里 `com.buaa.schedule.**` 模式为什么会放开 `androidx.activity.ComponentActivity` /
   `androidx.room.RoomDatabase` 的 Companion 字段**，我没查出机制
   （怀疑与 R8 的静态字段下沉 / 类合并有关，未验证）。这正是我不动它的直接原因。
5. **没有跑 `lintVitalRelease`**：卡里只要求 `lintDebug`。Q3 删了 117 条 style、
   169 条 attr，而 lint 的资源检查是**按源文件**看的，看不见 arsc 收缩的后果，
   所以就算跑也不构成额外证据 —— 但如果以后要加一道自动闸门，
   真正该做的是第 3 节那套 aapt2+引用差集脚本化。
