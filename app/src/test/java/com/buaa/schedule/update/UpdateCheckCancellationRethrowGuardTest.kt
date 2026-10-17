package com.buaa.schedule.update

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T365③：把「检查更新出网那一手不许把协程取消吞成失败」钉成源码形状守卫（在册 #536 后半／T363 交回的 C-1）。
 *
 * ## 钉法点名（卡面四种里用了哪几种）
 *
 * - **抹注释后数出现次数 ＋ 窗口归属**：本卡的病灶不是某一行写错，而是**归属与先后**——
 *   复投那一手排在 `present` 之后就是「先替取消编了一句失败话、再考虑要不要复投」；
 *   `store.edit` 那一段没被内核那一格闸住，就是「取消照样落盘」。所以逐字节比一行不够，
 *   必须先把包住 `fetchReleases()` 的那一手到 `present` 之间切成一枚具名窗口，
 *   再在窗口里数复投、数收回现场、数落盘锚点，同时拿窗口索引钉先后。
 * - **反套套逻辑那一对（0 与 1 同时算）**：改前那一手 `failureReason = outcome.exceptionOrNull()...`
 *   在**代码里 0 枚**、在**含注释的原文里恰好 1 枚**（KDoc 逐字留着做取证，同 T261 那一族）。
 *   只查 0 那一半就成了「把取证删干净也算绿」，两半必须同批复算。
 * - **名册逐枚点名（覆盖面尺）**：main 里「取消那一族的落点」按三枚针数出的文件与枚数逐枚点名，
 *   总数与文件数都钉成写死值。这一格拦的是"册子与盘面各说各话"——新写一枚复投或分类不进来、
 *   或把在册某一枚悄悄摘掉，都在这里红，且消息念实到枚数与实到文件数。
 *   ⚠️ 这把尺钉的**不是**「只准有复投」：只分类不复投的落点从 T365 起就合法在册
 *   （`UpdateFetchSettlement.fateOf` 那一格就是），所以名册里每一枚都点名写清它靠哪一行、
 *   那一行是分类还是复投 —— 光把两个写死值改大是不算登记完的（T397② 就是这么补注释的）。
 *
 * ## T397② 重钉这一族（追加，不改上面任何一格判据）
 *
 * 卡 T392 把 iClass 登录失败折成九档中文之后，`domain/signin/LoginFailure.kt` 的九档梯子那一格
 * （`cause is CancellationException -> LoginFailureRung.WithdrawnLocally`）被这三枚针数到，
 * 盘面于是 10 枚文件／11 枚手而名册 9／10 ⇒ 本守卫的 ③ 红了一格、gate/37 不合并。
 * 那枚登记是**正当**的（分类行本来就上册），而 T397① 又给 `data/import/IClassApi.kt` 的 `login()`
 * 补了真正的复投那一手（把裹在外面的 `runCatching` 换成 try／catch 取消那一型复投／catch 其余才折档），
 * 于是名册同批重钉成 **11 枚文件／12 枚手**。上面 ① ② ④ 三格与三枚针一字未动。
 *
 * ## T400② 重钉这一族（追加，三枚针照旧一字未动）
 *
 * 卡 T400 收 `widget/BackgroundSync.kt` 那条后台链（在册 #677）：把「块内带挂起点」那六枚
 * `runCatching { … }` **整个换掉**成 try／catch 取消复投／catch 其余，另把 `readChainSnapshotOrNull`
 * 的 FQ 写法收敛成短形（同文件已 import，为了让全文件同形、数得出来）。于是那一枚文件盘面从 1 枚手变成 **8 枚手**，
 * 名册同批重钉成 **11 枚文件／19 枚手** —— 文件数一格不动（没有新增宿主），只动枚数与那一行的来路注释。
 * 那四枚**没有**被换的 runCatching（都在非挂起 `fun` 里、块内零挂起点）不进这一族，也不该进：
 * 它们的来路写在 ROSTER 那一行的注释里，别把"册子没写"读成"盘上没有"。
 * ## T404① 重钉这一族（追加，不改上面任何一格判据与那三枚针）
 *
 * 卡面 #696 的第二枚把 iClass **递交**那一手也换成了同一形状：`data/import/IClassApi.kt` 的
 * `signIn()` 从前整段裹在一层裸 `runCatching`（吃 `Throwable`）里，取消走到调用点时已经被折成一次
 * 失败**返回** —— 那一手正是 T397① 在 `login()` 上裁过的同一档，而当时 `IClassApi.kt` 的 KDoc
 * 把「[signIn] 那一路」与 `Error` 那一族**同列**为已知缺口留在盘上。本卡收掉的是取消那半条：
 * `signIn()` 现在也有 `catch (e: CancellationException) { throw e }`，排在 `catch (cause: Throwable)`
 * 之前；**折档仍不在这里**（交回的仍是原文，`IClassApiTest` 那枚在册格钉的就是这一条）。
 * 于是名册同批重钉成 **11 枚文件／13 枚手**（`IClassApi.kt` 那一行 1 → 2，文件数不动 ——
 * 同一枚文件里多了一手，正是"文件数与枚数互为两侧"那格要说出来的形状）。
 * ⚠️ 本卡只改这一枚守卫里的两枚写死值与那一行注释；名册里另外点名的 `widget/**` 两枚与
 * `ui/ScheduleViewModel.kt` 一字未动（它们分属在飞的 T400／T403）。若那两面在合并态也增删了
 * 取消那一族的落点，这两枚写死值要由编排者按**合并对象**重钉，别拿任何单支读数当合并态。
 *
 * ## 为什么四格都不许改成 >=
 *
 * 三格是等式（落盘锚点各恰 1 枚），因为「多一枚」与「少一枚」在这里都是病：
 * 少一枚 = 那一手没了（病灶复发），多一枚 = 同一枚键又多了第二个写点（T261 第 2 处静默的形状）。
 * 名册那一格也是等式，理由与本仓在册那把跨树绝对尺同源：只判下限的话扫描器读空了照样绿。
 *
 * ## 输入面与代价（如实写）
 *
 * 本守卫只读 `src/main/java` 的工作树文本（JVM，无 Robolectric、无设备、不读钟），
 * 那棵树已由 `app/build.gradle.kts` 里 `guardReadWorkingTreeFiles` 整体声明成 `testDebugUnitTest` 的输入。
 * ⚠️ 未装机：取消那一手的屏上读数（不再弹「检查更新失败」）只由判据单测与本守卫的锚点核对背书，零 adb 读数。
 * 代价：谁在 main 里新增或摘掉一枚取消那一族的落点（复投**或**分类），都得回来把
 * `EXPECTED_RETHROW_HANDS`／`EXPECTED_RETHROW_FILES` 与名册同批重钉，并把那一枚的来路
 * （哪一行、分类还是复投）写进 `ROSTER` 的注释里（报错消息会念「实到 N 枚 expected:<M>」）；
 * 在册那把助手形状尺（`CommentStrippingHelperShapeGuardTest` 的四枚写死值）本文件一个字都不碰——
 * 本卡的抹注释助手走 `buildString` 逐段拼，刻意不写成「铺成字符数组、收尾再把数组包成字符串」那一族形状。
 */
class UpdateCheckCancellationRethrowGuardTest {

    private companion object {
        const val CHECK = "com/buaa/schedule/update/UpdateCheck.kt"
        const val KERNEL = "com/buaa/schedule/domain/update/UpdateFetchSettlement.kt"

        // ── ① 包住 fetchReleases() 的那一手 ──
        const val FETCH_HAND = "val outcome = runCatching { fetchReleases() }"
        const val ERROR_READ = "val error = outcome.exceptionOrNull()"
        const val SETTLE_CALL = "val settlement = UpdateFetchSettlement.of(error) { readable(it) }"
        const val REST_BRANCH = "if (settlement.restingTier == UpdateRestingTier.RestInIdle) {"
        const val IDLE_RESTORE = "_state.value = UpdateUiState.Idle"
        const val RETHROW_HAND = "if (settlement.rethrowsCancellation && error is CancellationException) throw error"
        const val PRESENT_CALL = "val verdict = UpdateCheckGate.present("
        const val REASON_FROM_KERNEL = "failureReason = settlement.reason,"
        const val CHECKING_STATE = "_state.value = UpdateUiState.Checking"

        /** 改前那一手：把 runCatching 的异常直接喂给 present（取消与真失败在这一格里同形） */
        const val OLD_FEED_TO_PRESENT = "failureReason = outcome.exceptionOrNull()?.let { readable(it) },"

        // ── ② 取消分支零落盘 ＋ 写侧那道闸 ──
        const val PERSIST_GATE = "if (settlement.persistsRun) store.edit {"
        const val LAST_AT_WRITE = "putLong(KEY_LAST_AT, now)"
        const val FAILURE_AT_WRITE = "putLong(KEY_FAILURE_AT, now)"
        const val OUTCOME_FAIL_WRITE = "putString(KEY_LAST_OUTCOME, OUTCOME_FAILED)"
        const val EDIT_OPEN = "store.edit {"

        // ── ③ 名册：main 里「取消那一族的落点」的三枚针（互相不是子串，可分别数）──
        // ⚠️ 三枚针本身一字未动（改针就是改尺，本卡的题眼恰是"尺不许被顺手朝上重钉"）。
        val RETHROW_NEEDLES = listOf(
            "is CancellationException",
            "catch (e: CancellationException)",
            "catch (e: kotlinx.coroutines.CancellationException)",
        )
        /**
         * 名册：每枚点名**来路**（靠哪一行、那一行是分类还是复投），逐枚如下 ——
         * T397② 把这把尺的口径写明白：它数的是「main 里碰过取消那一族的落点」，
         * 不是「只准有复投」。只分类不复投的落点本来就合法在册（`UpdateFetchSettlement` 从 T365 起就是），
         * 所以"册子涨枚数"与"某一格复投被摘掉"在这把尺上是两件事，都得靠来路那一栏分辨。
         */
        val ROSTER = listOf(
            // 复投 ×2：`if (settlement.rethrowsCancellation && error is CancellationException) throw error`
            //        ＋ 撤销忽略那一手里同型的 `if (error is CancellationException) throw error`
            "com/buaa/schedule/update/UpdateCheck.kt" to 2,
            // 分类：`error is CancellationException -> UpdateFetchFate.Cancelled`（fateOf 那一格，内核半）
            "com/buaa/schedule/domain/update/UpdateFetchSettlement.kt" to 1,
            // 分类（T392① 落盘、T397② 上册）：`cause is CancellationException -> LoginFailureRung.WithdrawnLocally`
            //  —— 这是九档梯子那一格的**判档**，句子与档都未动；复投住在调用点，不在这里
            "com/buaa/schedule/domain/signin/LoginFailure.kt" to 1,
            // 复投（T397① 落第一手、T404① 落第二手）：`login()` 与 `signIn()` 各一枚
            // `catch (e: CancellationException) { throw e }`，两枚都排在
            // `catch (cause: Throwable)` 之前 —— 签到那一手收下的是 iClass 那一路"取消被折成一次失败返回"
            // 那半条在册缺口（`IClassApi` 的 signIn KDoc 现在自己写着这一条），折档仍不住在数据层
            "com/buaa/schedule/data/import/IClassApi.kt" to 2,
            // 复投：`catch (e: kotlinx.coroutines.CancellationException)`（BootReceiver 自己那句口径）
            "com/buaa/schedule/reminder/BootReceiver.kt" to 1,
            // 复投：同一型的 catch（明日预告那一支广播）
            "com/buaa/schedule/reminder/TomorrowPreviewReceiver.kt" to 1,
            // 复投：`suspendCatching` 里那一手（KDoc「取消是控制流，不是错误」的落点）
            "com/buaa/schedule/ui/ScheduleViewModel.kt" to 1,
            // 复投：`if (error is CancellationException) throw error`（扫码页递交那一手）
            "com/buaa/schedule/ui/signin/SpocScanScreen.kt" to 1,
            // 复投 ×8（T400② 把这条后台链上「块内带挂起点」的六枚外面那一层整个换掉，
            //  并把 `readChainSnapshotOrNull` 那枚 FQ 写法收敛成短形 ⇒ 全文件同形八枚，逐枚点名）：
            //  ① `readChainSnapshotOrNull`（改前就在册的那一枚）
            //  ② `rescheduleReminders`（原来那层 `runCatching { … }.getOrDefault(true)` 把取消与"读库失败"压成同一个 true）
            //  ③ `refreshWidgetsAsync` ④ `refreshWidgets` ⑤ `scheduleTomorrowPreview`
            //  ⑥ `runColdStartWidgetSteps` 的 `probeHasWidgets` ⑦ `runChainStep`（冷启动四步的公共闸门）
            //  ⑧ `runIsolatedRedrawLayers`（T400② 新增的分层执行体：各层试完，取消除外）
            //  ⚠️ 同文件 :49 的散文里还有第二形，被 withoutComments 抹掉 ⇒ 不计
            //  ⚠️ 同文件另有四枚 runCatching **不在这一族**（cleanUpInCalendarMode / scheduleWidgetMidnight /
            //  cancelLegacyPeriodicWork / hasAnyWidgetSafely）：都不在非挂起 `fun` 里、块内零挂起点，
            //  取消结构上不可能发生 ⇒ 本卡裁定不换（针数不到它们，也不需要数）
            "com/buaa/schedule/widget/BackgroundSync.kt" to 8,
            // 复投：`catch (e: CancellationException)`
            "com/buaa/schedule/widget/WidgetCommon.kt" to 1,
            // 复投：`catch (e: kotlinx.coroutines.CancellationException)`
            "com/buaa/schedule/widget/WidgetRefreshReceiver.kt" to 1,
        )
        const val EXPECTED_RETHROW_HANDS = 19
        const val EXPECTED_RETHROW_FILES = 11

        // ── ④ 判据文件那三格反着的形状 ＋ 纯度口径 ──
        const val KERNEL_NO_PERSIST = "persistsRun = false,"
        const val KERNEL_REST_IDLE = "restingTier = UpdateRestingTier.RestInIdle,"
        const val KERNEL_RETHROW = "rethrowsCancellation = true,"
        const val PURITY_PROMISE = "不读时钟、不读时区、不读权限、不读 `Build.VERSION`"
        val FORBIDDEN_READS = listOf(
            "System.currentTimeMillis",
            "currentTimeMillis",
            "LocalDate.now",
            "Instant.now",
            "ZoneId",
            "TimeZone",
            "Build.VERSION",
            "SharedPreferences",
            "getSharedPreferences",
            "FirstRun",
            "Context",
            "java.net",
            "Log.",
            "Dispatchers",
            "runCatching",
        )
    }

    // ─────────── ① 包住 fetchReleases() 的那一手必须带 CancellationException 复投 ───────────

    @Test
    fun `出网那一手带取消复投 且复投排在问判据之前`() {
        val raw = source(CHECK)
        val code = withoutComments(raw)

        for ((label, anchor) in listOf(
            "出网那一手本身" to FETCH_HAND,
            "异常本体只取一次" to ERROR_READ,
            "归档与定档那一手" to SETTLE_CALL,
            "收回现场那一档" to REST_BRANCH,
            "复投那一手" to RETHROW_HAND,
            "失败原因换成内核那一格" to REASON_FROM_KERNEL,
        )) {
            assertEquals(
                "$CHECK 里 $label「$anchor」必须恰好一枚（实到 " + occurrences(code, anchor) + " 枚）。" +
                    "0 枚 = 那一手整个没了（本卡病灶复发）；2 枚 = 同一次收场被分在两处判（两把尺）",
                1,
                occurrences(code, anchor),
            )
        }

        val fetchAt = code.indexOf(FETCH_HAND)
        val errorAt = code.indexOf(ERROR_READ)
        val settleAt = code.indexOf(SETTLE_CALL)
        val restAt = code.indexOf(REST_BRANCH)
        val rethrowAt = code.indexOf(RETHROW_HAND)
        val presentAt = code.indexOf(PRESENT_CALL)
        val checkingAt = code.indexOf(CHECKING_STATE)
        assertTrue(
            "顺序钉（本卡题眼）：转圈（$checkingAt）→ 出网（$fetchAt）→ 取异常（$errorAt）→ 归档定档（$settleAt）" +
                "→ 收回现场（$restAt）→ **复投（$rethrowAt）**，全都必须排在问 present（$presentAt）之前。" +
                "复投排到 present 之后，就是先拿取消喂了一枚 CheckFailed、再想起来它是取消——弹窗那一下已经定了",
            checkingAt in 0 until fetchAt && fetchAt < errorAt && errorAt < settleAt &&
                settleAt < restAt && restAt < rethrowAt && rethrowAt < presentAt,
        )

        // 窗口：包住 fetchReleases() 的那一手 → present 之前，就是"取消分档"那一整段
        val window = code.substring(restAt, presentAt)
        assertEquals(
            "取消那一分支里复投那一手必须恰 1 枚（窗口切自 " + CHECK + " 的收回现场那一段，实到 " +
                occurrences(window, RETHROW_HAND) + " 枚）。0 枚就是 W 臂那一形：只归档不复投，" +
                "调用方的协程以「正常跑完」收场，屏上还挂着转圈",
            1,
            occurrences(window, RETHROW_HAND),
        )
        assertEquals(
            "取消那一分支里必须真的把转圈收回 Idle（窗口内「$IDLE_RESTORE」恰 1 枚，实到 " +
                occurrences(window, IDLE_RESTORE) + " 枚）。0 枚就是甲按字面落地的那一形：Checking 永挂，" +
                "之后每一次检查都在同状态闸门上原样返回 ⇒ 设置页那颗按钮静默 no-op",
            1,
            occurrences(window, IDLE_RESTORE),
        )
        assertEquals(
            "窗口里必须出现 CancellationException 那一手（实到 " + occurrences(window, "CancellationException") +
                " 枚）——本守卫钉的是**形状**，不是" +
                "「内核里有一句就完事」：调用点这一手拿不回取消本体，结构化并发就断在这里",
            1,
            occurrences(window, "CancellationException"),
        )

        // 反套套逻辑那一对：改前那一手在代码里 0 枚、在含注释的原文里恰 1 枚（取证 KDoc 逐字留着）
        assertEquals(
            "改前那一手「$OLD_FEED_TO_PRESENT」在 " + CHECK + " 的**代码**里必须 **0** 枚（实到 " +
                occurrences(code, OLD_FEED_TO_PRESENT) + "）。它把 runCatching 的异常直接喂给 present，" +
                "取消与真失败在这一格里同形——那正是本卡的弹窗与失败档两个后果的共同源头",
            0,
            occurrences(code, OLD_FEED_TO_PRESENT),
        )
        assertEquals(
            "同一串在**含注释的原文**里必须恰好 1 枚（取证那一行逐字留着）。实到 " +
                occurrences(raw, OLD_FEED_TO_PRESENT) + " 枚：读到 0 = 取证被搬走，上面那个 0 就成了" +
                "「注释删干净也算绿」的假绿；读到 2 = 那一手又有人抄回代码里",
            1,
            occurrences(raw, OLD_FEED_TO_PRESENT),
        )
    }

    // ─────────── ② OUTCOME_FAILED 不许出现在取消分支 ───────────

    @Test
    fun `取消那一分支一次都不落盘 而落盘那一段整个由内核闸着`() {
        val code = withoutComments(source(CHECK))
        val restAt = code.indexOf(REST_BRANCH)
        val presentAt = code.indexOf(PRESENT_CALL)
        assertTrue(
            "两枚锚点都得在场才切得出取消那一分支（REST_BRANCH 实到索引 $restAt、PRESENT_CALL 实到索引 $presentAt）",
            restAt >= 0 && presentAt > restAt,
        )
        val window = code.substring(restAt, presentAt)

        for (anchor in listOf(
            OUTCOME_FAIL_WRITE,
            FAILURE_AT_WRITE,
            LAST_AT_WRITE,
            EDIT_OPEN,
            "KEY_LAST_OUTCOME",
            "KEY_FAILURE_AT",
            "putString(KEY_LAST_DAY, today)",
        )) {
            assertEquals(
                "取消那一分支里「$anchor」必须 **0** 枚（实到 " + occurrences(window, anchor) + " 枚）。" +
                    "落下去的每一枚都是假话：KEY_LAST_AT 顶掉上一手真实的检查时刻（杀进程也回不来）、" +
                    "KEY_FAILURE_AT 把 FAILURE_RETRY_MILLIS 那枚重试闸门拨到一次没发生过的取消上、" +
                    "失败档让下一次冷启动念「上次检查失败」",
                0,
                occurrences(window, anchor),
            )
        }

        // 反"搬走算绿"：上面读到 0 的同时，那三枚写点在全文件仍各恰 1 枚，且整段被内核那一格闸着
        for (anchor in listOf(LAST_AT_WRITE, FAILURE_AT_WRITE, OUTCOME_FAIL_WRITE, PERSIST_GATE)) {
            assertEquals(
                CHECK + " 里「$anchor」必须恰好 1 枚（实到 " + occurrences(code, anchor) + " 枚）。" +
                    "0 枚就是上面那些 0 的来源不是" +
                    "「取消不落盘」而是「整个落盘被搬走了」——真失败那一档也就没人落结果档了（T261 第 2 处静默）",
                1,
                occurrences(code, anchor),
            )
        }
        val gateAt = code.indexOf(PERSIST_GATE)
        assertTrue(
            "那道闸必须排在三枚写点之前（$PERSIST_GATE 实到索引 $gateAt，" +
                "$LAST_AT_WRITE 实到索引 " + code.indexOf(LAST_AT_WRITE) + "）",
            gateAt >= 0 && gateAt < code.indexOf(LAST_AT_WRITE),
        )
        assertEquals(
            CHECK + " 里「$EDIT_OPEN」总共恰 2 枚（落盘那一段 ＋ restoreIgnored 那一手撤销忽略，实到 " +
                occurrences(code, EDIT_OPEN) + " 枚）：枚数尺——多一枚就是又起了一处没被内核闸着的写点",
            2,
            occurrences(code, EDIT_OPEN),
        )
    }

    // ─────────── ③ 取消那一族的落点名册（覆盖面尺，逐枚点名：分类与复投两类都登记）───────────

    @Test
    fun `main 里取消复投那一族的名册与盘面对得上`() {
        val mainDir = findMainJavaDir()
        val counted = mutableMapOf<String, Int>()
        fun walk(dir: File) {
            dir.listFiles()?.forEach { entry ->
                if (entry.isDirectory) {
                    walk(entry)
                } else if (entry.name.endsWith(".kt")) {
                    val hits = RETHROW_NEEDLES.sumOf { needle ->
                        occurrences(withoutComments(entry.readText()), needle)
                    }
                    if (hits > 0) {
                        val relative = entry.absolutePath.substring(mainDir.absolutePath.length + 1)
                            .replace('\\', '/')
                        counted[relative] = hits
                    }
                }
            }
        }
        walk(mainDir)

        val total = counted.values.sum()
        val rosterKeys = ROSTER.map { it.first }.sorted()
        val diskKeys = counted.keys.sorted()
        assertEquals(
            "名册外的文件数出取消那一族的落点（分类或复投；或名册里的文件已经没这一手了）：\n  盘面 " +
                diskKeys.size + " 枚 ｜ 实到 " +
                diskKeys.joinToString(" ／ ") + "\n  名册 " + rosterKeys.size + " 枚 ｜ 在册 " +
                rosterKeys.joinToString(" ／ "),
            rosterKeys,
            diskKeys,
        )
        val mismatches = ROSTER
            .map { (relative, expected) ->
                val actual = counted[relative] ?: 0
                if (actual != expected) "$relative 实到 $actual 枚 expected:<$expected>" else null
            }
            .filterNotNull()
        assertTrue(
            "逐枚点名对不上（枚数写的是 T365③ 落盘那天抹注释后的现量；" +
                "T397② 同批重钉为 11 枚文件／12 枚手；T400② 只把 widget/BackgroundSync.kt 那一行从 1 枚手重钉成 8 枚手 ⇒ 11 枚文件／19 枚手，逐枚来路——哪一行、分类还是复投——写在 ROSTER 注释里）：" +
                mismatches.joinToString(" ／ "),
            mismatches.isEmpty(),
        )
        assertEquals(
            "main 里取消那一族的落点总数 = " + EXPECTED_RETHROW_HANDS + " 枚（实到 " + total + " 枚）。" +
                "少一枚 = 有一处吞了取消（本卡病灶在别处复发）；多一枚 = 新写了一处复投或分类却没进本名册，" +
                "而下一枚卡照本册子判" +
                "「这一族收干净了」就会判错",
            EXPECTED_RETHROW_HANDS,
            total,
        )
        assertEquals(
            "同一族分布 " + EXPECTED_RETHROW_FILES + " 枚文件（实到 " + counted.size + " 枚）——" +
                "与总数那格互为两侧：枚数对而文件数不对，说明某一枚文件里多写了一手",
            EXPECTED_RETHROW_FILES,
            counted.size,
        )
    }

    // ─────────── ④ 判据文件：零 android、不读钟，取消那一档三格都反着 ───────────

    @Test
    fun `判据文件零 android 也不读钟读区读偏好 而取消那一档三格全反`() {
        val raw = source(KERNEL)
        val offenders = raw.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("import ") }
            .filter { it.contains("android.") || it.contains("androidx.") }
            .toList()
        assertEquals("$KERNEL 必须零 android / androidx import。实到：" + offenders, emptyList<String>(), offenders)

        val code = withoutComments(raw)
        for (needle in FORBIDDEN_READS) {
            assertEquals(
                "判据文件里「$needle」必须 0 处：读了它，「这一手落不落盘、内存摆哪一档」就不再是纯函数——" +
                    "同一次冷启动的两次调用会给出不同结论（本仓口径：这些留在调用点算好再当参数递进来）。" +
                    "\n  复算：把 " + KERNEL + " 的注释抹成空格后数「$needle」\n  实到枚数：" +
                    occurrences(code, needle),
                0,
                occurrences(code, needle),
            )
        }
        // 反套套逻辑：那句纯度承诺必须留痕，否则上面那一串 0 没人负责
        assertEquals(
            "内核 KDoc 里那句纯度承诺必须恰好留痕一枚（含注释原文）。实到：" + occurrences(raw, PURITY_PROMISE) + " 枚",
            1,
            occurrences(raw, PURITY_PROMISE),
        )

        for (anchor in listOf(KERNEL_NO_PERSIST, KERNEL_REST_IDLE, KERNEL_RETHROW)) {
            assertEquals(
                KERNEL + " 里取消那一档的「$anchor」必须恰好 1 枚（实到 " + occurrences(code, anchor) + " 枚）。" +
                    "这一格与 ① ② 互为两层：调用点形状对、内核那一档被改朝宽，表驱动单测红而本守卫的" +
                    " ① ② 两格仍绿——两处都得钉着才拦得住",
                1,
                occurrences(code, anchor),
            )
        }
        assertEquals(
            KERNEL + " 里「UpdateFetchFate.Cancelled ->」那一档恰好 1 枚（实到 " +
                occurrences(code, "UpdateFetchFate.Cancelled ->") + " 枚）：取消只许有一档结论",
            1,
            occurrences(code, "UpdateFetchFate.Cancelled ->"),
        )
    }

    // ---------------- helpers ----------------

    private fun source(relative: String): String = File(findMainJavaDir(), relative).readText()

    private fun occurrences(hay: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var at = hay.indexOf(needle)
        while (at >= 0) {
            count++
            at = hay.indexOf(needle, at + needle.length)
        }
        return count
    }

    /**
     * 把 `//` 与块注释抹成空格（字符串字面量原样留着，换行位置不保也够用）。
     *
     * ⚠️ 刻意不写成「先把源铺成字符数组、收尾再把那枚数组包成字符串返回」那一族形状：
     * 在册守卫 CommentStrippingHelperShapeGuardTest 把那一族在 app/src/test 的声明枚数与物化枚数
     * 钉成了合并态写死值（139／139／1／140），多写一枚就把四格一起顶红，而过滤门禁盖不住、只有全量才暴露
     *（同仓先例：WeekdayDateOffsetSingleRulerGuardTest 里那句裁定）。所以这里逐段 buildString。
     */
    private fun withoutComments(source: String): String = buildString(source.length) {
        val triple = "\"\"\""
        var i = 0
        while (i < source.length) {
            when {
                source.startsWith("//", i) -> {
                    val nl = source.indexOf('\n', i)
                    i = if (nl < 0) source.length else nl
                }

                source.startsWith("/*", i) -> i = blankBlock(source, i, this)

                source.startsWith(triple, i) -> {
                    append(triple)
                    val end = source.indexOf(triple, i + triple.length)
                    val stop = if (end < 0) source.length else end
                    append(source.substring(i + triple.length, stop))
                    if (end >= 0) {
                        append(triple)
                        i = stop + triple.length
                    } else {
                        i = stop
                    }
                }

                source[i] == '"' || source[i] == '\'' -> i = copyQuoted(source, i, this)

                else -> {
                    append(source[i])
                    i++
                }
            }
        }
    }

    /** 块注释（可嵌套）：非换行字符一律不抄，换行抄下来让报错还能数得出行 */
    private fun blankBlock(source: String, from: Int, out: StringBuilder): Int {
        var depth = 1
        var j = from + 2
        while (j < source.length && depth > 0) {
            when {
                source.startsWith("/*", j) -> {
                    depth++
                    j += 2
                }

                source.startsWith("*/", j) -> {
                    depth--
                    j += 2
                }

                else -> {
                    if (source[j] == '\n') out.append('\n')
                    j++
                }
            }
        }
        return j
    }

    /** 字符串／字符字面量：原样抄（字面量里的注释记号不算注释），跨到行尾没闭合就把这行抄完 */
    private fun copyQuoted(source: String, from: Int, out: StringBuilder): Int {
        val quote = source[from]
        out.append(quote)
        var j = from + 1
        while (j < source.length) {
            when (source[j]) {
                '\\' -> {
                    if (j + 1 < source.length) {
                        out.append(source[j])
                        out.append(source[j + 1])
                    }
                    j += 2
                }

                quote -> {
                    out.append(quote)
                    return j + 1
                }

                '\n' -> return j

                else -> {
                    out.append(source[j])
                    j++
                }
            }
        }
        return j
    }

    private fun findMainJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { candidate -> File(dir, candidate) }
                .firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }
}
