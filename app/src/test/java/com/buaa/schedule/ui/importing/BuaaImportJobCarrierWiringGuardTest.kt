package com.buaa.schedule.ui.importing

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T336④ 接线守卫：钉「抓取课的六枚在飞事实住在哪、作业跑在哪枚作用域上」（在册 #412）。
 *
 * 判据本体（谁该活过组合、重建后出哪一手）在 `domain/importing/FetchJobCarrierPolicy`，
 * 档位表由 `FetchJobCarrierPolicyTest`（T336③）逐格钉。这一枚管**盘上形状**：那一族判据一旦
 * 接错线就一句都兑现不了，而接错线的方式恰是Compose 上最难看见的两形 —— 状态留在组合里、
 * 扳机放进 `LaunchedEffect` 的键表。
 *
 * ## 病（为什么形状也要钉）
 * 教务登录页按下「抓取课表」后逐周请求要跑几十秒（量尺 = 同段注释自己陈的那句 M5），而改前那六枚
 * 在飞状态全长在组合上（`by remember { mutable… }`）⇒ 转屏（`app/src/main/AndroidManifest.xml` 零枚
 * `configChanges` ⇒ 必重建 Activity）⇒ 六枚归零 ⇒ 那一格 `LaunchedEffect(webViewRef, fetchStarted)` 的键
 * 被清零 ⇒ **抓取作业被取消**：进度退回 0/N、结论句消失，用户读作「点了没反应 / 刚才白等了」。
 *
 * 两个退化方向：
 * 1. **朝宽**：哪天有人把六枚里的任何一枚改回 `by remember`（看着只是"顺手局部化"），或把
 *    `fetchStarted` 塞回某枚 `LaunchedEffect` 的键表（看着只是"换枚键"），病灶原样回潮，
 *    而行为面在单测里红不到 —— 单测钉的是判据，不钉接线。
 * 2. **朝窄**：哪天有人把 `webViewRef` 也塞进 `rememberSaveable`（看着像"并尺"），交回的是
 *    一具随组合销毁的 WebView 的引用 —— 卡面陈的证伪档①。
 *
 * ## 十层判据（每层都点名红了去改哪一条链）
 * ① [inFlightFactsAreNoLongerHeldByComposition] —— 六枚名字各判一次「`NAME by remember` 在屏幕里 0 处」，
 *    外加**名单闭合**：屏幕里所有 `by remember` 声明的名字逐枚等于在册那 7 枚（平台 1 + 加载读数 6）。
 *    新增一枚该随作业存的状态却写在组合上 ⇒ 这一格先红，不许静默漏过。
 * ② [theSixNameRosterHasExactlyOneSource] —— 内核名单块里引号包住的六枚名字逐枚在册（写死清单比对，
 *    不靠内核自己供）；holder 的 `Progress` 字段名册 == 那六枚 + `interruptedByRebuild`（诚实旗标）。
 *    两头同源：读数改名而判据不改 ⇒ 红；判据加名而读数不加 ⇒ 红。
 * ③ [screenAsksTheHoldersFlowNotItsOwnMemory] —— 屏幕取数那一形逐字节钉：`BuaaImportJob.progress
 *    .collectAsState().value` 恰 1 处、六枚 `val NAME = importProgress.NAME` 各恰 1 处；
 *    holder 写数那一头六枚 `it.copy(NAME = value)` 各恰 1 处 ⇒ 读写两头都只认同一枚 StateFlow。
 * ④ [fetchRunsOnTheProcessScopeNotTheCompositionScope] —— 抓取作用域：屏幕里 `rememberCoroutineScope`
 *    连 import 一起 0 枚；holder 里 `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)` 恰 1 枚、
 *    `job = jobScope.launch {` 恰 1 枚、作业体经代理跑的逐字节 1 枚。
 * ⑤ [webViewRefStaysOutOfSavedInstanceState] —— 平台那一枚：合法形（`by remember { mutableStateOf<WebView?>(null) }`）
 *    恰 1 处，`webViewRef … by rememberSaveable` 0 处，`rememberSaveable` 在屏幕代码里 0 枚（只剩注释里那句禁令在册，
 *    裸原文栏 1 枚）。
 * ⑥ [theTriggerNeverSitsInALaunchedEffectKeyTable] —— 键表册子：屏幕抹注释后所有 `LaunchedEffect(…)` 的键表
 *    逐枚等于在册那一本（只有 `webViewRef`）；六枚在飞名字在任何键表里 0 枚；改前那一形
 *    `LaunchedEffect(webViewRef, fetchStarted)` 裸原文 2 枚（两处散文在册）／抹注释 0 枚。
 * ⑦ [ignitionIsAskedInExactlyOneGate] —— 点火闸门全仓唯一：`FetchJobCarrierPolicy.ignition(` 落点册子逐枚点名
 *    （恰 holder 那一枚）、`Ignition.Idle, Ignition.AttachOnly -> return` 逐字节 1 处（**已在飞 + 同一枚平台
 *    绝不二次点火**那一手）、`progressResetsOnIgnition(` 恰 1 处、屏幕侧 0 处。
 * ⑧ [theRebuildSentenceTellsTheTruth] —— 屏上那句实话：诚实旗标进条件式逐字节 1 处、
 *    「从头再取」裸原文 2 枚（注释 + 用户文案）／抹注释 1 枚（**屏上真念到的那一枚**），
 *    而「续上了」两栏都 0 枚 —— 丙档没买到跨销毁续跑，措辞就不许冒充买到了。
 * ⑨ [thePolicyKernelStaysPureJvm] —— 内核那一侧：文件长在 `domain/` 下，抹注释后 `android` / `androidx` /
 *    `WebView` / `Context` 各 0 枚（裸原文栏读得到 KDoc 里的那几句转述 ⇒ 这一格的 0 不是抹出来的）。
 * ⑩ [theDetectorRecognisesTheShapesItClaimsToCatch] —— 反套套逻辑：把改前那六形、证伪档①②那一形各喂进
 *    同一把尺，数到该数的值；合法形喂进去数 0。上面所有「0 处／恰 N 枚」才有对照物。
 *
 * ## 复算这几把尺（命令里写成方括号类是故意的：一个字都不匹配本文件）
 * - 屏幕里 `by remember` 声明：`grep -nE '(var|val) [A-Za-z][A-Za-z0-9_]* by remember' app/src/main/java/com/buaa/schedule/ui/importing/BuaaLoginScreen.kt` → 7（webViewRef + 六枚加载读数）
 * - 点火闸门落点：`grep -rn 'FetchJobCarrierPolicy[.]ignition[(]' app/src/main` → 1
 * - 抓取作用域：`grep -rn 'CoroutineScope(SupervisorJob() + Dispatchers[.]Main[.]immediate)' app/src/main` → 2
 *   （本 holder 一枚 + `update/UpdateCheck.kt` 那枚在册先例；屏幕侧 `rememberCoroutineScope` 0 枚由本守卫第 ④ 层点名）
 * - 改前那一形：`grep -n 'LaunchedEffect(webViewRef, fetchStarted)' app/src/main/java/com/buaa/schedule/ui/importing/BuaaLoginScreen.kt` → 2（两处散文在册，代码 0 处）
 *
 * ## 覆盖面边界（如实写）
 * - 只扫 `app/src/main/java` 下的 `.kt`，读的是**工作树**（已在 `app/build.gradle.kts` 的守卫输入面里，T92①）；
 *   零 android import、不起 Robolectric、零时钟、零设备。
 * - 名单闭合那把尺认的是「声明形」`var|val NAME by remember`。把六枚中的某一枚改写成
 *   `remember { mutableStateOf(...) }` 再接 `by` 之外的取值方式，本尺切不到 —— 那时第 ③ 层的
 *   「六枚 `val NAME = importProgress.NAME` 各 1 处」会先红，因为屏上再没有那六枚读数。
 * - 第 ⑤ 层只判 `webViewRef` 这一枚不许进快照（卡面点名的证伪档）；其余状态将来要不要快照不在本卡射程。
 * - 本守卫自带一枚抹注释助手，写法逐字抄同目录 `SemesterGateOrderingGuardTest` 里那一族
 *   （本仓每一枚守卫自带一份、都是 private，没有可跨文件调用的那一枚）。新增一枚合法助手的代价
 *   由 `CommentStrippingHelperShapeGuardTest` 当场念出：那四枚写死值各 +1（本卡 ④ 档同一批重钉，
 *   改前 134／134／135／135 → 改后 135／135／136／136）。
 */
class BuaaImportJobCarrierWiringGuardTest {

    // ─────────────── ① 六枚在飞事实不许再长在组合上 ───────────────

    @Test
    fun inFlightFactsAreNoLongerHeldByComposition() {
        val screen = withoutComments(readMainSource(FILE_SCREEN))
        for (name in IN_FLIGHT) {
            assertEquals(
                "**钉法 = 数出现次数（抹注释后逐枚点名）**：$FILE_SCREEN 里「$name by remember」必须 0 处 —— " +
                    "这一族是**在飞事实**（抓取作业跑着才有意义），载体寿命必须 ≥ 作业寿命；写在组合上就是" +
                    "改前那一形：转屏清零读数 ⇒ 键被清零 ⇒ 作业被取消。实到 " +
                    regexCount(screen, inComposition(name)) + " 处。红了就是把这一枚搬回了页面里",
                0,
                regexCount(screen, inComposition(name)),
            )
            assertEquals(
                "**钉法 = 数出现次数**：$FILE_SCREEN 里「val $name = importProgress.$name」必须恰 1 处 —— " +
                    "读数只许从 holder 那一枚 StateFlow 取。实到 " +
                    occurrences(screen, "val $name = importProgress.$name") + " 处",
                1,
                occurrences(screen, "val $name = importProgress.$name"),
            )
        }
        assertEquals(
            "**钉法 = 名单闭合（读形状，等式）**：$FILE_SCREEN 里所有「var|val NAME by remember」的名字逐枚等于" +
                "这本册子 —— 平台一枚（webViewRef，由 AndroidView 的 factory 每轮重新供）+ 页面自己的六枚加载读数。" +
                "多出一枚 = 有人把新状态写回组合（判据那边名单里若有它，第②层会同时红）；少一枚 = 在册某枚换了形，" +
                "上面那些「0 处」就要重判。实到：\n" + byRememberRegister(screen),
            listOf(
                "  loadError: 1 处",
                "  loadProgress: 1 处",
                "  loadStarted: 1 处",
                "  pageVisible: 1 处",
                "  retryToken: 1 处",
                "  timedOut: 1 处",
                "  webViewRef: 1 处",
            ).joinToString(separator = "\n") + "\n",
            byRememberRegister(screen),
        )
    }

    // ─────────────── ② 六枚名字的名单只有一本账 ───────────────

    @Test
    fun theSixNameRosterHasExactlyOneSource() {
        val kernel = withoutComments(readMainSource(FILE_KERNEL))
        val roster = quotedNames(parenBlock(kernel, "val IN_FLIGHT_FACTS: Set<String> = setOf")).sorted()
        assertEquals(
            "**钉法 = 读形状（内核名单块逐枚点名）**：$FILE_KERNEL 的 IN_FLIGHT_FACTS 块里的名字必须逐枚等于" +
                "卡面点名的那六枚。实到 " + roster.joinToString(" ／ ") + " —— 内核改名单而 holder 的读数、" +
                "屏幕的取数不跟着改，就是把「载体寿命 ≥ 作业寿命」这条判据用在了一枚不存在的事实上",
            IN_FLIGHT,
            roster,
        )
        val holder = withoutComments(readMainSource(FILE_HOLDER))
        val fields = parenBlock(holder, "data class Progress(").let { block ->
            FIELD_NAME.findAll(block).map { it.groupValues[1] }.toList().sorted()
        }
        assertEquals(
            "**钉法 = 读形状（两头同源）**：$FILE_HOLDER 的 Progress 字段名册必须 = 内核那六枚 + 诚实旗标 " +
                "interruptedByRebuild（它不进在飞名单：它说的正是「上一轮被打断过」，本轮读数之外它无作业可随）。" +
                "实到 " + fields.joinToString(" ／ "),
            (IN_FLIGHT + "interruptedByRebuild").sorted(),
            fields,
        )
        val platform = quotedNames(parenBlock(kernel, "val PLATFORM_FACTS: Set<String> = setOf"))
        assertEquals(
            "**钉法 = 读形状**：平台那一族只许 webViewRef 一枚（它的跨销毁合法载体是进程内 holders，" +
                "不是快照 —— 卡面证伪档①）。实到 " + platform.joinToString(" ／ "),
            listOf("webViewRef"),
            platform,
        )
    }

    // ─────────────── ③ 读写两头同源 ───────────────

    @Test
    fun screenAsksTheHoldersFlowNotItsOwnMemory() {
        val screen = withoutComments(readMainSource(FILE_SCREEN))
        assertEquals(
            "**钉法 = 逐字节**：屏幕只经那一枚 StateFlow 取数（「$FLOW_READ」恰 1 处）。实到 " +
                occurrences(screen, FLOW_READ) + " 处 —— 0 处就是屏幕又不问 holder 了",
            1,
            occurrences(screen, FLOW_READ),
        )
        val holder = withoutComments(readMainSource(FILE_HOLDER))
        for (name in IN_FLIGHT) {
            assertEquals(
                "**钉法 = 逐字节**：$FILE_HOLDER 里「it.copy($name = value)」恰 1 处 —— ProgressWriter 那一枚可写视图" +
                    "把写入立刻发成新版读数，读的那头拿的就是同一份。实到 " +
                    occurrences(holder, "it.copy($name = value)") + " 处（作业体内直接改这枚名字的地方越多，" +
                    "屏上的分数与实际进度越可能对不上账）",
                1,
                occurrences(holder, "it.copy($name = value)"),
            )
        }
        assertEquals(
            "**钉法 = 数出现次数**：$FILE_HOLDER 的 `it.copy(` 落点恰 $EXPECTED_PROGRESS_UPDATES 处 —— 六枚 setter（" +
                "ProgressWriter 那六枚 var 各一枚）+ 五处整批落读数：点火归零、平台销毁清场、askToFetch、cancel、" +
                "askAgain。实到 " + occurrences(holder, "it.copy(") + " 处。" +
                "多一处就要回来点名它落在哪条链上，别改成 >=",
            EXPECTED_PROGRESS_UPDATES,
            occurrences(holder, "it.copy("),
        )
    }

    // ─────────────── ④ 抓取作用域：进程级，不是组合级 ───────────────

    @Test
    fun fetchRunsOnTheProcessScopeNotTheCompositionScope() {
        for (column in listOf("抹注释" to withoutComments(readMainSource(FILE_SCREEN)), "原文" to readMainSource(FILE_SCREEN))) {
            for (needle in listOf("rememberCoroutineScope", "scope.launch")) {
                assertEquals(
                    "**钉法 = 数出现次数（" + column.first + " 栏）**：$FILE_SCREEN 里「$needle」必须 0 处 —— " +
                        "改前那里躺着一枚死声明 `val scope = rememberCoroutineScope()`（台账记过它零调用方），" +
                        "而作业真正的接收者现在是 holder 的进程级作用域。栏内实到 " +
                        occurrences(column.second, needle) + " 处",
                    0,
                    occurrences(column.second, needle),
                )
            }
        }
        val holder = withoutComments(readMainSource(FILE_HOLDER))
        for (shape in listOf(JOB_SCOPE_DECL, JOB_LAUNCH, BODY_THROUGH_PROXY)) {
            assertEquals(
                "**钉法 = 逐字节**：$FILE_HOLDER 里那一形「$shape」恰 1 处 —— 三形合起来就是「作业跑在与组合无关的" +
                    "作用域上，且只经代理出口说话」。实到 " + occurrences(holder, shape) + " 处。把作用域换成 " +
                    "viewModelScope 是本卡第⓪问否掉的乙档（它牵动 ScheduleViewModel 的在册文档锚点），" +
                    "换成组合作用域就是病灶回潮",
                1,
                occurrences(holder, shape),
            )
        }
        assertEquals(
            "**钉法 = 数出现次数**：$FILE_HOLDER 抹注释后「jobScope」恰 3 处 = 声明（1）+ 点火那一处 " +
                "`job = jobScope.launch {`（1）+ deliverPending 那一处（1）。实到 " + occurrences(holder, "jobScope") +
                " 处 —— 多一处就要回来点名那条链挂在哪枚作用域上（KDoc 里那句转述不进账，它被抹注释挡着）",
            3,
            occurrences(holder, "jobScope"),
        )
    }

    // ─────────────── ⑤ 平台那一枚不许进快照 ───────────────

    @Test
    fun webViewRefStaysOutOfSavedInstanceState() {
        val raw = readMainSource(FILE_SCREEN)
        val screen = withoutComments(raw)
        assertEquals(
            "**钉法 = 逐字节（合法形恰一枚）**：$FILE_SCREEN 里「$WEBVIEW_LEGAL」恰 1 处 —— 页面脚下那一枚由 " +
                "AndroidView 的 factory 每轮重新供，留在组合上是对的（判据：PLATFORM_FACTS × COMPOSITION = Supported）。" +
                "实到 " + occurrences(screen, WEBVIEW_LEGAL) + " 处",
            1,
            occurrences(screen, WEBVIEW_LEGAL),
        )
        assertEquals(
            "**钉法 = 读形状**：「webViewRef … by rememberSaveable」必须 0 处（卡面证伪档①：WebView 不可快照，" +
                "塞进 savedInstanceState 交回的是随旧组合销毁的那一具）。实到 " +
                regexCount(screen, SAVEABLE_WEBVIEW) + " 处",
            0,
            regexCount(screen, SAVEABLE_WEBVIEW),
        )
        // 两栏都读：屏幕代码里一枚 rememberSaveable 都没有，注释里那句禁令要留在盘上（它挡着下一次"顺手并尺"）
        assertEquals(
            "**钉法 = 口径尺（两栏各钉绝对值）**：$FILE_SCREEN 里「rememberSaveable」裸原文恰 $EXPECTED_SAVEABLE_RAW 枚" +
                "（就是第 102 行那句「不许改 rememberSaveable」的在册禁令）、抹注释后恰 $EXPECTED_SAVEABLE_STRIPPED 枚。" +
            "实到 裸 " + occurrences(raw, "rememberSaveable") + " ／ 抹 " + occurrences(screen, "rememberSaveable") +
                "。两栏合流 = 那句禁令被删了（删证据不是修尺）；抹注释栏变 1 = 真有人把它接进了快照",
            Pair(EXPECTED_SAVEABLE_RAW, EXPECTED_SAVEABLE_STRIPPED),
            Pair(occurrences(raw, "rememberSaveable"), occurrences(screen, "rememberSaveable")),
        )
        assertEquals(
            "**钉法 = 数出现次数**：$FILE_SCREEN 不许 import 那一枚（`import androidx.compose.runtime.rememberSaveable` " +
                "0 处，实到 " + occurrences(raw, "import androidx.compose.runtime.rememberSaveable") + " 处）",
            0,
            occurrences(raw, "import androidx.compose.runtime.rememberSaveable"),
        )
    }

    // ─────────────── ⑥ 扳机不许进 LaunchedEffect 的键表 ───────────────

    @Test
    fun theTriggerNeverSitsInALaunchedEffectKeyTable() {
        val raw = readMainSource(FILE_SCREEN)
        val screen = withoutComments(raw)
        val tables = keyTables(screen)
        assertEquals(
            "**钉法 = 读形状（键表册子，等式）**：$FILE_SCREEN 里每一枚 LaunchedEffect 的键表逐枚等于这本册子" +
                "（只有平台那一枚）。实到：\n" + tables.joinToString(separator = "") { "  [$it]\n" } +
                "—— 键表里出现任何一枚在飞事实，就是把「清零」当成了「重跑的信号」",
            listOf("webViewRef"),
            tables,
        )
        for (name in IN_FLIGHT) {
            val hit = tables.firstOrNull { table -> table.contains(name) }
            assertNull(
                "**钉法 = 键表逐枚点名**：改前那一形是 `LaunchedEffect(webViewRef, fetchStarted)`（卡面证伪档②）。" +
                    "任何一枚键表里读得到「$name」（实到命中：$hit）⇒ 重建把这一枚清零时 effect 重跑，" +
                    "几十秒作废后从头再抓，而 holder 的闸门再也拦不住它",
                hit,
            )
        }
        assertEquals(
            "**钉法 = 逐字节**：「$ATTACH_PLATFORM」恰 1 处 —— 平台到位只报给 holder 的闸门，页面自己不点。实到 " +
                occurrences(screen, ATTACH_PLATFORM) + " 处",
            1,
            occurrences(screen, ATTACH_PLATFORM),
        )
        assertEquals(
            "**钉法 = 口径尺（两栏）**：改前那一形「LaunchedEffect(webViewRef, fetchStarted)」裸原文恰 " +
                "$EXPECTED_LEGACY_KEY_TABLE_RAW 枚（第 160 行那格键表注释 + 作业本体 KDoc 各把病灶在册一遍）、" +
                "抹注释后 0 枚。实到 裸 " + occurrences(raw, LEGACY_KEY_TABLE) + " ／ 抹 " +
                occurrences(screen, LEGACY_KEY_TABLE) + " —— 这一格同时是" +
                "「抹注释这一步真的在挡散文假阳性」的反证",
            Pair(EXPECTED_LEGACY_KEY_TABLE_RAW, 0),
            Pair(occurrences(raw, LEGACY_KEY_TABLE), occurrences(screen, LEGACY_KEY_TABLE)),
        )
    }

    // ─────────────── ⑦ 点火闸门全仓唯一 ───────────────

    @Test
    fun ignitionIsAskedInExactlyOneGate() {
        val register = needleRegister(IGNITION_CALL)
        assertEquals(
            "**钉法 = 数出现次数（全 main 落点册子，抹注释后）**：「$IGNITION_CALL」今天只剩一枚落点 = holder 的" +
                "闸门。册子多出一枚 = 全仓长出第二把点火尺（两处各判一次「要不要再抓一轮」，答案可以相反）；" +
                "少一枚 = 闸门不问了，屏上那一族读数与作业脱钩。实到：\n$register",
            "  $FILE_HOLDER: 1 处\n",
            register,
        )
        val holder = withoutComments(readMainSource(FILE_HOLDER))
        for (shape in listOf(ATTACH_ONLY_RETURN, RESET_RULERS_CALL, GATE_SIGNATURE)) {
            assertEquals(
                "**钉法 = 逐字节**：$FILE_HOLDER 里那一形「$shape」恰 1 处 —— 「已在飞 + 同一枚平台」那一手必须是" +
                    "**只挂读数**，重新点火两档必须显式归零。实到 " + occurrences(holder, shape) + " 处",
                1,
                occurrences(holder, shape),
            )
        }
        val screen = withoutComments(readMainSource(FILE_SCREEN))
        assertEquals(
            "**钉法 = 数出现次数**：$FILE_SCREEN 里「$IGNITION_CALL」必须 0 处 —— 页面不自己判断「该不该再抓一轮」，" +
                "它只把平台报上来（attachPlatform）与要求抓取（askToFetch）。实到 " +
                occurrences(screen, IGNITION_CALL) + " 处",
            0,
            occurrences(screen, IGNITION_CALL),
        )
        assertFalse(
            "**钉法 = 读形状**：$FILE_HOLDER 的闸门里不许把 AttachOnly 并进点火那一支" +
                "（`Ignition.Start, Ignition.AttachOnly ->` 0 处；实到那一段：" +
                holder.lines().filter { it.contains("Ignition.") }.joinToString(" ／ ") + "）",
            holder.contains("Ignition.Start, Ignition.AttachOnly"),
        )
    }

    // ─────────────── ⑧ 屏上那句实话 ───────────────

    @Test
    fun theRebuildSentenceTellsTheTruth() {
        val raw = readMainSource(FILE_SCREEN)
        val screen = withoutComments(raw)
        assertEquals(
            "**钉法 = 逐字节**：诚实旗标进条件式那一形「$TRUTH_CONDITION」恰 1 处（$FILE_SCREEN）。实到 " +
                occurrences(screen, TRUTH_CONDITION) + " 处 —— 0 处就是屏上又开始念一具尸体的现在时",
            1,
            occurrences(screen, TRUTH_CONDITION),
        )
        assertEquals(
            "**钉法 = 口径尺（两栏）**：「从头再取」裸原文 $EXPECTED_RESTART_COPY_RAW 枚（在册注释一遍 + 用户文案一遍）、" +
                "抹注释后 $EXPECTED_RESTART_COPY_STRIPPED 枚 —— 后者就是**用户真念到的那一枚**。实到 裸 " +
                occurrences(raw, RESTART_COPY) + " ／ 抹 " + occurrences(screen, RESTART_COPY) + " 处",
            Pair(EXPECTED_RESTART_COPY_RAW, EXPECTED_RESTART_COPY_STRIPPED),
            Pair(occurrences(raw, RESTART_COPY), occurrences(screen, RESTART_COPY)),
        )
        for (column in listOf("裸原文" to raw, "抹注释" to screen)) {
            assertEquals(
                "**钉法 = 数出现次数（" + column.first + " 栏）**：$FILE_SCREEN 里「续上了」必须 0 枚 —— 丙档买到的是" +
                    "「读数与作业不再随组合清零」，**没**买到「那一轮请求从第 N+1 周续跑」（要 startWeek，在册残账）。" +
                    "措辞不许冒充买到了。栏内实到 " + occurrences(column.second, "续上了") + " 枚",
                0,
                occurrences(column.second, "续上了"),
            )
        }
        assertEquals(
            "**钉法 = 逐字节**：holder 侧那一枚平台销毁清场「$DETACH_PLATFORM_SHAPE」恰 1 处（$FILE_SCREEN 里 " +
                "AndroidView 的 onRelease 路径）—— 不留痕的话闸门会以为作业还有脚，每一枚请求只各自等到超时。实到 " +
                occurrences(screen, DETACH_PLATFORM_SHAPE) + " 处",
            1,
            occurrences(screen, DETACH_PLATFORM_SHAPE),
        )
    }

    // ─────────────── ⑨ 内核保持纯 JVM ───────────────

    @Test
    fun thePolicyKernelStaysPureJvm() {
        val raw = readMainSource(FILE_KERNEL)
        val kernel = withoutComments(raw)
        assertTrue(
            "$FILE_KERNEL 必须长在 domain/ 下（判据本体不许住在 ui/ 或 data/）：实到路径 " + FILE_KERNEL,
            FILE_KERNEL.startsWith("com/buaa/schedule/domain/"),
        )
        for (needle in listOf("android", "androidx", "WebView", "Context", "composable")) {
            assertEquals(
                "**钉法 = 口径尺（两栏各钉）**：内核里「$needle」抹注释后必须 0 枚（它只收调用点递进来的布尔，" +
                    "WebView／Activity 那一族事实不进判据）；裸原文栏实到 " + occurrences(raw, needle) +
                " 枚，只许是 KDoc 的转述。抹注释栏实到 " + occurrences(kernel, needle) + " 枚",
                0,
                occurrences(kernel, needle),
            )
        }
        assertEquals(
            "**钉法 = 读形状**：内核不许出现任何 import 行以外的事实来源 —— 「import」裸原文与抹注释后同数" +
                "（本仓的纯 JVM 判据文件一枚 import 都没有）。实到 裸 " + occurrences(raw, "import ") +
                " ／ 抹 " + occurrences(kernel, "import ") + " 枚",
            0,
            occurrences(kernel, "import "),
        )
        assertTrue(
            "内核 KDoc 那句纯 JVM 承诺必须在册（$KERNEL_ANCHOR）：判据哪天开始自己读时钟／设备，先抹掉的就是这句",
            raw.contains(KERNEL_ANCHOR),
        )
    }

    // ─────────────── ⑩ 反套套逻辑：尺子认得它宣称能抓的那些形 ───────────────

    @Test
    fun theDetectorRecognisesTheShapesItClaimsToCatch() {
        val caught = listOf(
            Triple("改前那枚在飞读数（组合 + mutableIntStateOf）", "var fetchWeek by remember { mutableIntStateOf(0) }", 1),
            Triple("改前那枚旗标（组合 + mutableStateOf）", "var fetchStarted by remember { mutableStateOf(false) }", 1),
            Triple("改前那句结论读数", "var fetchState by remember { mutableStateOf<String?>(null) }", 1),
            Triple("证伪档①：把 WebView 塞进快照", "var webViewRef by rememberSaveable { mutableStateOf<WebView?>(null) }", 1),
            Triple("证伪档②：扳机进键表", "LaunchedEffect(webViewRef, fetchStarted) {", 1),
            Triple("换一枚键也不许放过", "LaunchedEffect(fetchTotal, webViewRef) {", 1),
        )
        for (row in caught) {
            val hits = probe(row.second)
            assertEquals(
                "**探测器自检（" + row.first + "）**：这一形必须被本守卫那几把尺数到 " + row.third + " 枚，实到 " +
                    "$hits 枚 —— 数不到的话上面所有「0 处／恰 1 处」都是空锚点（本仓口径：锚点拼错的假绿比没有守卫更糟）",
                row.third,
                hits,
            )
        }
        val legal = listOf(
            "val fetchWeek = importProgress.fetchWeek",
            "val fetchStarted = importProgress.fetchStarted",
            "LaunchedEffect(webViewRef) {",
            "var webViewRef by remember { mutableStateOf<WebView?>(null) }",
            "BuaaImportJob.askToFetch()",
        )
        for (shape in legal) {
            assertEquals(
                "**探测器自检（不误伤）**：这一形是合法写法（$shape），必须 0 枚，实到 " + probe(shape) +
                    " 枚 —— 把合法写法一起扫红的尺会逼下一张卡把守卫改松",
                0,
                probe(shape),
            )
        }
        // 键表那把尺自己：把在飞名字从键表里抹掉后必须读空
        assertEquals(
            "**探测器自检（键表解析切得到内容）**：LaunchedEffect(webViewRef, fetchStarted) { 解析出的键表必须是" +
                "「webViewRef, fetchStarted」（不是空串，否则第⑥层那一串「0 枚」全是空的）：",
            listOf("webViewRef, fetchStarted"),
            keyTables("LaunchedEffect(webViewRef, fetchStarted) { }"),
        )
        assertTrue(
            "app/src/main 一个 .kt 都没扫到（实到 " + sources().size + " 枚）：本守卫所有读数都是空的",
            sources().isNotEmpty(),
        )
        for (relative in listOf(FILE_SCREEN, FILE_HOLDER, FILE_KERNEL)) {
            assertTrue(
                "$relative 读空了（长度 " + readMainSource(relative).length + "）：路径挪过家，本守卫的针全在空串上比",
                readMainSource(relative).isNotEmpty(),
            )
        }
    }

    // ---------------- 读盘与小工具（与本仓守卫同一套写法）----------------

    private class MainFile(val relative: String, val text: String)

    /** 一趟测试里读一遍树就够（同一 JVM 实例内工作树不变；变异自证是靠重新起 gradle 生效的） */
    private val files: List<MainFile> by lazy {
        val root = findMainJavaDir()
        val found = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(
            "${root.path} 下一个 .kt 都没有（实到 ${found.size} 枚），路径不对 ⇒ 本守卫所有「0 处」都是空的",
            found.isNotEmpty(),
        )
        found.map {
            MainFile(
                root.toPath().relativize(it.toPath()).toString().replace(File.separatorChar, '/'),
                it.readText().replace("\r\n", "\n"),
            )
        }.sortedBy { file -> file.relative }
    }

    private fun sources(): List<MainFile> = files

    /** 那一形本体：`var|val NAME by remember …`（名字逐枚点名，第①层与第⑩层共用同一把尺） */
    private fun inComposition(name: String): Regex =
        Regex("(?:var|val)\\s+" + Regex.escape(name) + "\\s+by\\s+remember\\b")

    private val BY_REMEMBER_DECL: Regex = Regex("(?:var|val)\\s+([A-Za-z][A-Za-z0-9_]*)\\s+by\\s+remember\\b")
    private val SAVEABLE_WEBVIEW: Regex = Regex("(?:var|val)\\s+webViewRef\\s+by\\s+rememberSaveable\\b")
    private val KEY_TABLE: Regex = Regex("LaunchedEffect\\(([^)]*)\\)")
    private val FIELD_NAME: Regex = Regex("val\\s+([A-Za-z][A-Za-z0-9_]*)\\s*:")
    private val QUOTED_NAME: Regex = Regex("[\"]([A-Za-z][A-Za-z0-9_]*)[\"]")

    /**
     * 探测器自检那把尺：一形喂进来，三族针（在飞名字的组合声明 / 快照里的平台 / 键表里的在飞名字）
     * 各数一次，交回命中的总枚数。第①⑤⑥层用的就是这三族，所以自检与判据同一条路。
     */
    private fun probe(shape: String): Int {
        val inComposition = BY_REMEMBER_DECL.findAll(shape)
            .count { match -> IN_FLIGHT.contains(match.groupValues[1]) }
        val saveablePlatform = regexCount(shape, SAVEABLE_WEBVIEW)
        val triggerInKeyTable = keyTables(shape).count { table -> IN_FLIGHT.any { name -> table.contains(name) } }
        return inComposition + saveablePlatform + triggerInKeyTable
    }

    private fun byRememberRegister(code: String): String =
        BY_REMEMBER_DECL.findAll(code).map { it.groupValues[1] }.toList().sorted()
            .joinToString(separator = "") { "  $it: 1 处\n" }

    private fun keyTables(code: String): List<String> =
        KEY_TABLE.findAll(code).map { it.groupValues[1].trim() }.toList()

    /** 某串的落点册子（一律抹注释后），只列有命中的文件 */
    private fun needleRegister(needle: String): String = sources()
        .map { it.relative to occurrences(withoutComments(it.text), needle) }
        .filter { it.second > 0 }
        .joinToString(separator = "") { "  ${it.first}: ${it.second} 处\n" }

    private fun readMainSource(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 $relative：挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    private fun regexCount(haystack: String, pattern: Regex): Int = pattern.findAll(haystack).toList().size

    private fun occurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var at = haystack.indexOf(needle)
        while (at >= 0) {
            count++
            at = haystack.indexOf(needle, at + needle.length)
        }
        return count
    }

    private fun quotedNames(block: String): List<String> =
        QUOTED_NAME.findAll(block).map { it.groupValues[1] }.toList()

    /** 从签名的左圆括号起配平，交回括号内那一段；锚点找不到就抛，静默跳过等于没有守卫 */
    private fun parenBlock(source: String, signature: String): String {
        val at = source.indexOf(signature)
        check(at >= 0) { "找不到 $signature：写法换过了，这条守卫要跟着改" }
        val open = source.indexOf('(', at)
        check(open >= at) { "$signature 之后找不到左括号" }
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, index)
                }
            }
        }
        throw IllegalStateException("$signature 的圆括号没配平")
    }

    /**
     * 只抹注释、保留字符串字面量，长度与换行位置不变（报错消息里的行号因此可信）。
     * 逐字抄同目录 `SemesterGateOrderingGuardTest` 里的同族助手；末尾那一句是本仓钉着的
     * 物化写法 —— 换成对字符数组调那枚被禁的交回方式，这一枚守卫会编译绿、门禁绿、
     * 每次判据都绿，而从没读过一句它宣称在读的文件（T315 留下、T317 改回那一族病）。
     */
    private fun withoutComments(source: String): String {
        val out = source.toCharArray()
        var i = 0
        while (i < out.size) {
            when {
                source.startsWith("//", i) -> {
                    val nl = source.indexOf('\n', i).let { if (it < 0) out.size else it }
                    for (k in i until nl) out[k] = ' '
                    i = nl
                }

                source.startsWith("/*", i) -> {
                    var depth = 1
                    var j = i + 2
                    while (j < out.size && depth > 0) {
                        when {
                            source.startsWith("/*", j) -> { depth++; j += 2 }
                            source.startsWith("*/", j) -> { depth--; j += 2 }
                            else -> j++
                        }
                    }
                    for (k in i until j.coerceAtMost(out.size)) if (out[k] != '\n') out[k] = ' '
                    i = j
                }

                source.startsWith("\"\"\"", i) -> {
                    i = source.indexOf("\"\"\"", i + 3).let { if (it < 0) out.size else it + 3 }
                }

                out[i] == '"' || out[i] == '\'' -> {
                    val quote = out[i]
                    var j = i + 1
                    while (j < out.size) {
                        when {
                            source[j] == '\\' -> j += 2
                            source[j] == quote -> { j++; break }
                            source[j] == '\n' -> break
                            else -> j++
                        }
                    }
                    i = j
                }

                else -> i++
            }
        }
        return String(out)
    }

    private fun findMainJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(6) {
            val hit = listOf("src/main/java", "app/src/main/java").map { File(dir, it) }.firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }

    private companion object {
        const val FILE_SCREEN = "com/buaa/schedule/ui/importing/BuaaLoginScreen.kt"
        const val FILE_HOLDER = "com/buaa/schedule/data/import/BuaaImportJob.kt"
        const val FILE_KERNEL = "com/buaa/schedule/domain/importing/FetchJobCarrierPolicy.kt"

        /** 卡面点名的那六枚在飞事实（写死清单，② 层拿它与内核名单对账） */
        val IN_FLIGHT = listOf("fetchCancelled", "fetchStarted", "fetchState", "fetchTotal", "fetchWeek", "importPrepared")

        const val FLOW_READ = "BuaaImportJob.progress.collectAsState().value"
        const val WEBVIEW_LEGAL = "var webViewRef by remember { mutableStateOf<WebView?>(null) }"
        const val ATTACH_PLATFORM = "webViewRef?.let { BuaaImportJob.attachPlatform(it) }"
        const val JOB_SCOPE_DECL = "private val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)"
        const val JOB_LAUNCH = "job = jobScope.launch {"
        const val BODY_THROUGH_PROXY = "proxy.body(web, writer)"
        const val IGNITION_CALL = "FetchJobCarrierPolicy.ignition("
        const val ATTACH_ONLY_RETURN = "Ignition.Idle, Ignition.AttachOnly -> return"
        const val RESET_RULERS_CALL = "FetchJobCarrierPolicy.progressResetsOnIgnition(action)"
        const val GATE_SIGNATURE = "private fun tryIgnite(web: WebView) {"
        const val TRUTH_CONDITION = "if (importProgress.interruptedByRebuild && fetchState != null)"
        const val RESTART_COPY = "从头再取"
        const val LEGACY_KEY_TABLE = "LaunchedEffect(webViewRef, fetchStarted)"
        const val DETACH_PLATFORM_SHAPE = "com.buaa.schedule.data.import.BuaaImportJob.detachPlatform(web)"
        const val KERNEL_ANCHOR = "零 android import"

        /** ⓪ 步现量写死的期望（口径 = 本守卫内部抹注释后的文本，不是裸 grep） */
        const val EXPECTED_PROGRESS_UPDATES = 11
        const val EXPECTED_SAVEABLE_RAW = 1
        const val EXPECTED_SAVEABLE_STRIPPED = 0
        const val EXPECTED_LEGACY_KEY_TABLE_RAW = 2
        const val EXPECTED_RESTART_COPY_RAW = 2
        const val EXPECTED_RESTART_COPY_STRIPPED = 1
    }
}
