package com.buaa.schedule.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T105：给三对「今天成对、零守卫」的成对字段补静态守卫 —— 只做源码核对，不改任何 main 源码。
 *
 * 三对来自 `docs/derived-field-audit.md` §6.8 的 ①③⑥（T103 那一遍评估账的明留三格）：
 *
 * **① `CalendarSyncUiState.targetId` / `.targetName`**（§6.8①、§6.2 表 #10）
 * 三处写点今天全成对：`ui/ScheduleViewModel.kt:1373-1374`（起手从偏好里构造整枚 state）、
 * `:1462` `it.copy(targetId = calendarId, targetName = displayName, showPicker = false)`（选完日历）、
 * `:1528-1529` 那个 `if (targetGone)` 双写（目标日历已被用户删掉）。
 * 这一对**没有分头清点**（§6.9 驳回② 因此把它判成「根本没进候选」），要钉的是「别长出第二把尺子」。
 * 它比 §6.4 那几枚「结构不可能」更值得钉，是因为唯一读者是**裸读**：
 * `ui/settings/SettingsScreen.kt:1623` `summary = calendarSync.targetName ?: "未选择",`
 * —— 它不在任何由 `targetId` 驱动的块里（全仓 `calendarSync.targetId` 出现 0 次）。
 * 于是「只把 `targetId` 打回 -1L」的那一处新站点，会当场把「未选择」念成上一任日历的名字。
 *
 * **T110 给 ① 添的第二半（偏好那一侧）**：`:1523` 那次检测今天除了把内存里两枚打回 `-1L` / `null`，
 * 还必须在 `:1538` 那一档把偏好里那两枚 key（`calendar_sync_target_id` / `calendar_sync_target_name`）
 * **一起撤掉** —— 只清内存的话，下一次 VM 重建起手 `:1373-1374` 又把死 id 读回来，界面继续念那个已经不
 * 存在的日历名，真去同步时 `CALENDAR_ID` 打进死 id、异常被 `CalendarSyncManager` 那颗 `runCatching` 吞掉，
 * 用户读到的就成了 `:1432` 那句「同步失败：日历写入异常」—— 真因（那个日历没了）被洗成"写入异常"。
 * 两枚 key **同样必须成对**（漏一枚 = 换个方向念旧账：留着名字就把"未选择"念成上任的名字，留着 id 就
 * 还能往死 id 里写）。另外撤的那一档带 `loaded.isNotEmpty()`：查询失败交回来的也是空列表（§6.4-A），
 * 那一刻分不清「日历被删了」与「provider 抖了一下」，宁可让偏好多留一次，也不要在抖动里抹掉用户选好的日历。
 * 归 `theDeadTargetIsDroppedFromPreferencesAsAPairOfKeysAndNotOnlyFromMemory` 那枚判据钉。
 *
 * **T110 给 ① 添的第三半（那枚缓存旗标）**：`calendarsLoaded` 此前在全仓**一处复位都没有**
 * （`grep -rn "calendarsLoaded = false" app/src/main/java` ⇒ 0 行），`:1512` 那次早返回因此把 `calendars`
 * 变成"进程寿命"的缓存 —— 选择器列不出用户刚删/刚建的日历，`targetGone` 那次检测一个进程里也只跑头一趟，
 * 而 `:1516` 那一档还把 `calendarsLoaded = true` 与一句失败文案一起落，查询失败也永久锁死。
 * 现在有了唯一一枚复位站点：`:1449` 开窗那一档连带写 `calendarsLoaded = false`
 * （归 `theCalendarListCacheHasExactlyOneResetSiteAndItIsThePickerOpening` 钉）。
 *
 * **③ `CalendarSyncUiState.message` / `.permissionPermanentlyDenied`**（§6.8③、§6.2 表 #7、§6.4-B）
 * 成对写一处：`ui/ScheduleViewModel.kt:1496-1503`（`permissionPermanentlyDenied = !canAskAgain,` 与
 * `message = AppMessage(... "日历权限已被永久拒绝，请到系统设置手动开启" ...)` 出自同一次 copy）。
 * **T105 当时记的是「分头清两处」，T106 已把其中一处改成成对清**（那是一枚真漏清，不是结构性无害）：
 * `:1389` 起手那档现在 `it.copy(syncing = true, message = null, permissionPermanentlyDenied = false,
 * diff = null, skippedOccurrences = 0)` —— 撤句子顺手撤旗标；剩下 `:1508` 仍是单清（只清旗标不撤句子，
 * 它长在 launcher「全部授予」那一档里，那条档上句子本来就该留）。
 * **T110 又添一枚成对清：`:1481` `removeSyncedEvents()` 起手**（「移除已同步的日程」那条链有自己的入口，
 * 不经 `startCalendarSync`，所以 T106 那一刀盖不住它）—— 现在写
 * `it.copy(syncing = true, message = null, permissionPermanentlyDenied = false)`。它落下的句子
 * `:1486` 那句「已移除 N 个日程」恰好把 `:1664` 那格重新点亮，旧旗标因此在完成句旁边挂出那颗按钮。
 * 这一枚的**前提**（清旗标不是"声称已授权"）由 `everyRouteIntoTheRemoveChainStandsInsideTheSameGateAndClearsTheFlagPaired` 钉：
 * 它唯一的界面触发点长在 `showRemoveConfirm` 驱动的那层弹窗里，而开窗只有 `requestRemoveSyncedEvents()`
 * 一枚写点、其唯一入口 `SettingsScreen.kt:1654` 已经在闸里。
 * 病为什么当时会被误判成无害：§6.4-B 把 `:331` 那句 `if (viewModel.hasCalendarPermission())` 短路当成
 * 「到不了 :317 所以旗标不会被读到」的闸，方向搞反了 —— 正是那道短路让**已授权的用户根本不启动 launcher**，
 * 于是 `onCalendarPermissionGranted()`（清旗标唯一入口）永不被调，而 `:1664`
 * `visible = calendarSync.message != null` 那格会被下一句「同步完成…」重新点亮 ⇒
 * 「同步完成」旁边常驻一颗「去系统设置开启日历权限」。修法与可达路径枚举见
 * 修法与可达路径枚举见本文件第三枚 `@Test`
 * `everyRouteIntoTheSyncEntryStandsInsideAPermissionGate`（T106 新增：把「进到 `startCalendarSync()` 时
 * 权限必然已到手」这条前提逐入口钉死）；T110 之后本文件这一族钉「**成对写 1 处 + 成对清 2 处 + 单清 1 处**」
 * 这三枚数，以及闸门本身（闸 B 如今只是第二层保险，不再是唯一的挡头）。
 *
 * **⑥ `SettingsScreen.showPrivacyDialog` / `privacyConsentAt`**（§6.8⑥、§6.2 表 #14）
 * 连号一起写：`ui/settings/SettingsScreen.kt:1848-1849`（`privacyConsentAt = 0L` + `showPrivacyDialog = false`）；
 * 分头清两处：`:1817` `onDismissRequest = { showPrivacyDialog = false },`、
 * `:1853` `TextButton(onClick = { showPrivacyDialog = false })` —— 都只关窗、不归零同意时间。
 * 不漏**完全靠一行写法**维持：`:1814`
 * `ModalTransition(payload = if (showPrivacyDialog) privacyConsentAt else null) { consentAt, modal ->`
 * （弹窗正文读的是 payload 交回来的 `consentAt`，不是已经翻面的那枚活字段）。
 * 谁把它改回同文件另一种常用写法 `ModalTransition(open = showPrivacyDialog)`（见 `:1859` 那一层），
 * 或给 `privacyConsentAt` 添第二枚读者，收场那几帧就当场念「未同意」—— 本文件钉的就是这一行的形状
 * 与两枚字段各自的出现次数。
 *
 * 判据形状、报错话术、读源码的刀法都照两枚先例：
 * `app/src/test/java/com/buaa/schedule/ui/CalendarSyncDiffClearPairingGuardTest.kt`（同族第一回，
 * `diff` / `skippedOccurrences`）与
 * `app/src/test/java/com/buaa/schedule/ui/editor/CourseEditorSaveErrorClearPairingGuardTest.kt`
 * （同族第二回，`saving` / `saveError`，T104 刚写完）。那两枚各自钉死的字段与本文件**不重叠**：
 * `diff = null` / `skippedOccurrences = 0` 的三处成对账归前者，`saveError` 一族归后者。
 *
 * 十一枚 `@Test` 全是**纯 JVM 源码核对**：只 import `java.io.File` 与 JUnit，零 android import、
 * 零时钟读取（不碰 `System.currentTimeMillis()` / `LocalDate.now()` 之类）。
 * 行号按 `4b1c4a4` 盘面复算；T106 只把 `ScheduleViewModel.kt:1389` 那一枚 copy **同行改写**（没有增删行）
 * ⇒ 本文件点名的行号在 `688b191`＋T106 之上仍然成立，只有 ③ 那一族的**枚数**按新盘面重钉
 * （旗标赋值 2→3、成对写 1 / 成对清 1 / 单清 1）；按 T101 的规矩，每条判据都跟**符号名 + 原文片段**同框，
 * 行号只是辅助。
 * **T110 之后本文件的行号账**：`ScheduleViewModel.kt:1449`（开窗复位旗标）与 `:1481`（移除链起手成对清）
 * 两枚都是**同行改写**、零漂移，唯一的插行是 `ensureCalendarsLoaded` 末尾那档撤偏好（`:1532-1541`，净 +10）
 * ⇒ 本文件点名的 `:1531` 之前的行号一处不漂，那颗文件从 1,683 行变 1,693 行；③ 那一族的枚数按新盘面
 * 再重钉一次（旗标赋值 3→4、成对清 1→2、句子清点 1→2），① 添偏好那一半与缓存旗标那一半。
 *
 * **T116 给 ③ 添的第三条链（§8 表 #1）**：`confirmCalendarSync` 起手那一枚 `it.copy(syncing = true)`
 * 一枚对岸都不收 —— 本文件不修它（判"不动 main"的理由与代价写在 `theConfirmChainClearsNoPairItselfAndThreeOtherLinesHoldItShut`
 * 那枚判据的 KDoc 里），钉的是"它今天不红究竟靠哪三枚别处的行"：那三枚各占一格，**哪一格被改掉都先红在这里**。
 * 同一枚 data class 的第四条链（`removeSyncedEvents` 起手不收 `diff`/`skippedOccurrences`，§8 表 #2）
 * 归 `CalendarSyncDiffClearPairingGuardTest` 的第三枚判据 —— 那一格判据的是那一对的清点宇宙，不归本文件。
 */
class CalendarSyncTargetPairingGuardTest {

    // ---------------- ① targetId / targetName ----------------

    /**
     * 写侧：`targetId` 与 `targetName` 的每次重建都必须落在**同一批**「整枚重建」（`copy(...)` 或
     * 起手那处 `CalendarSyncUiState(...)`）里，且两枚各自恰好被赋值 3 次。
     *
     * 两头都钉：`targetId = ` 从 3 变 4 = 多了一把尺子（新站点的起手必须同样成对，这一族已经数第二回了）；
     * 变 2 = 有人换了写法（`copy(targetId to ...)` / 抽成函数 / 改名），字面判据跟不上了要重判。
     * `targetName = ` 同为一枚独立的 3 —— 两枚数**相等**是「一一对应」，**各自写死**是「谁掉队都红」。
     */
    @Test
    fun targetIdAndTargetNameAreAssignedInExactlyTheSameThreeRebuilds() {
        val code = blankCommentsKeepingLiterals(readMainSource(SCHEDULE_VIEW_MODEL))
        val idWrites = indexOfAll(code, TARGET_ID_ASSIGNED)
        assertEquals(
            "给 `targetId` 赋值的站点从 3 处变了（现在只有起手 :1373 从偏好构造、:1462 选完日历、" +
                ":1528 那个 if (targetGone) 三处）。**多一处** = 又添了一把只动 id 的尺子 —— " +
                "界面唯一读者读的是 name（SettingsScreen.kt:1623 那行裸的 summary），" +
                "id 单独打回 -1L 而 name 留着，就会把「未选择」念成上一任日历的名字；" +
                "**少一处** = 有人换了写法或删了那条链，本守卫的字面判据跟不上了，得回来重判：" +
                lineHints(code, idWrites),
            TARGET_WRITE_SITES,
            idWrites.size,
        )
        val nameWrites = indexOfAll(code, TARGET_NAME_ASSIGNED)
        assertEquals(
            "给 `targetName` 赋值的站点从 3 处变了，而它必须与上面那枚 id 同数（成对写、不许有一枚字段" +
                "被单独搬动）：多出来的一次清点说明有人开始只清名字（同一枚病的另一头），少一处说明" +
                "id 那一侧在单飞：" + lineHints(code, nameWrites),
            TARGET_WRITE_SITES,
            nameWrites.size,
        )
        val idRebuilds = idWrites.map { rebuildSite(code, it) }
        val nameRebuilds = nameWrites.map { rebuildSite(code, it) }
        assertEquals(
            "targetId 与 targetName 不再出自同一批整枚重建（左边按 id 切出的重建位置 " +
                "$idRebuilds、右边按 name 切出的 $nameRebuilds）—— 两枚一旦分家，成对写就只剩巧合",
            idRebuilds.toSet(),
            nameRebuilds.toSet(),
        )
        assertEquals(
            "成对重建的**批数**不是 3（上面两条各自数到 3、这一条数「几批」，防的是两处挤进同一批而第三批单飞）",
            TARGET_WRITE_SITES,
            idRebuilds.toSet().size,
        )
        val unpaired = idWrites.filter { !rebuildArguments(code, it).contains(TARGET_NAME_ASSIGNED) }
        assertTrue(
            "这些重建只写了 targetId、没写 targetName —— 两枚是「选中的目标日历」这一件事的 id 与名字，" +
                "漏一半就是让前任日历的名字活在已经重置的选择上：" + lineHints(code, unpaired),
            unpaired.isEmpty(),
        )
        // 靶子：被数的这几行原文都要在（读错文件、整份读空都能让上面五条一次全绿）
        assertEquals(
            "靶子：:1462 那次成对写的原文还在（selectCalendarTarget 的落点）：",
            1,
            occurrences(code, "it.copy(targetId = calendarId, targetName = displayName, showPicker = false)"),
        )
        assertEquals(
            "靶子：:1528 那行 `if (targetGone) -1L` 的原文还在：",
            1,
            occurrences(code, "targetId = if (targetGone) -1L else it.targetId,"),
        )
        assertEquals(
            "靶子：:1529 那行 `if (targetGone) null` 的原文还在：",
            1,
            occurrences(code, "targetName = if (targetGone) null else it.targetName,"),
        )
        assertEquals(
            "靶子：参数表上 targetId 的哨兵值仍是 -1L（它才是「未选择」的写法，见 :97）：",
            1,
            occurrences(code, "val targetId: Long = -1L,"),
        )
        assertEquals(
            "靶子：参数表上 targetName 的哨兵值仍是 null（与 -1L 配对，见 :98）：",
            1,
            occurrences(code, "val targetName: String? = null,"),
        )
        assertEquals(
            "靶子：CalendarSyncUiState( 在整颗文件里只出现 2 次（:93 的声明 + :1372 的起手构造）—— " +
                "多一次就是有人另起了一处整枚构造，那处也得成对",
            2,
            occurrences(code, REBUILD_CTOR),
        )
        assertEquals(
            "`targetId` 在这颗文件里的生命点不再是 10 处（1 声明 + 3 赋值 + 6 读取）。" +
                "**多一处** = 添了读者，「漏清会不会被念出来」要按新读者重算；**少一处** = 有半条链换了载体",
            TARGET_ID_LIFE_POINTS,
            occurrences(code, "targetId"),
        )
        assertEquals(
            "`targetName` 的生命点不再是 5 处（1 声明 + 3 赋值 + 1 读取，其中 :1529 那一行同时是赋值与读取）",
            TARGET_NAME_LIFE_POINTS,
            occurrences(code, "targetName"),
        )
    }

    /**
     * 读侧前提：`targetName` 在界面上只有一张嘴，而且那张嘴**不在任何 `targetId` 驱动的闸门里**。
     *
     * 这一格是 ① 比 §6.4 那几枚「结构不可能」更值得钉的全部理由：T100 那两枚守卫的第二层判的是
     * 「读点在闸门里 ⇒ 漏清看不见」，这里判的是反过来的事实 —— 漏清**当场念给用户**。
     * 两头都钉：给这族状态添第二枚 `calendarSync.target*` 读者 → 1 变 2 红；
     * 把这一行挪进 `if (calendarSync.targetId != -1L)` 里 → 「无闸门」那条断言红（这一族的档位要跟着改）。
     */
    @Test
    fun theTargetNameReaderIsAloneAndSitsOutsideEveryTargetIdGate() {
        val screen = blankCommentsKeepingLiterals(readMainSource(SETTINGS_SCREEN))
        val reads = indexOfAll(screen, "calendarSync.target")
        assertEquals(
            "界面上 `calendarSync.target*` 的读点不再是恰好 1 处（现在只有目标日历那一行的 summary）。" +
                "**多一处** = 给这一对添了第二张嘴：只要 targetId 与 targetName 哪天分头清，" +
                "新增的那处读者会跟着念假话，①的写侧判据要按新读者重算；" +
                "**少一处** = 目标日历那一行整块被换掉了（载体变了），写侧那 3 处成对判据得跟着重钉：" +
                lineHints(screen, reads),
            TARGET_UI_READS,
            reads.size,
        )
        assertEquals(
            "靶子：SettingsScreen 里 `calendarSync.` 的读者总数变了（现测 16 枚，含 :297 的 collectAsState 一行）" +
                "—— 这一条是上一条 0 值判据的凭据：读空文件也会给出 0，读对文件才会给出 16",
            CALENDAR_SYNC_READS,
            occurrences(screen, "calendarSync."),
        )
        assertEquals(
            "`calendarSync.targetId` 在界面上应当是 0 处 —— 今天没有任何闸门用它兜住 name。" +
                "若哪天有人给它补一道 `if (calendarSync.targetId != -1L)`，这一格会先红，" +
                "那是好事：①的漏清从「念给用户」降级成「看不见」，档位要搬到 §6.4 那一档重判",
            0,
            occurrences(screen, "calendarSync.targetId"),
        )
        assertEquals(
            "靶子：那一行 summary 的写法还在（裸读 + 回退，没有兜路的判断）：",
            1,
            occurrences(screen, TARGET_SUMMARY_SHAPE),
        )
        assertEquals(
            "靶子：回退文案「未选择」还在，且全文件只有这一处 —— 它就是漏清会被念出来的那五个字",
            1,
            occurrences(screen, TARGET_FALLBACK_TEXT),
        )
        val at = screen.indexOf(TARGET_SUMMARY_SHAPE)
        check(at >= 0) { "找不到 $TARGET_SUMMARY_SHAPE：目标日历那一行换写法了，本守卫要跟着改" }
        val heads = enclosingBlockHeads(screen, at)
        val gating = heads.firstOrNull { it.contains("targetId") } ?: "无"
        assertTrue(
            "目标日历那行 summary 外面套上了由 `targetId` 驱动的块（块头 $gating）—— " +
                "见上面那条 0 值判据的话：这一族的档位要重判，name 单独残留在界面上不再念得出来",
            heads.none { it.contains("targetId") },
        )
        assertTrue(
            "靶子：读者仍长在 `item(key = \"targetCalendar\") {` 那一格里（LazyColumn 的第 1619 行）；" +
                "它不在了说明扫的不是那一页，或那一格整块搬了家",
            heads.any { it.contains(TARGET_ITEM_HEAD) },
        )
        assertEquals(
            "靶子：`item(key = \"targetCalendar\")` 在整颗文件里仍然只有一处：",
            1,
            occurrences(screen, TARGET_ITEM_HEAD),
        )
    }

    /**
     * ① 的第二半（**T110 新增**）：目标日历被用户删掉那一档，偏好里那两枚 key 也必须**成对**撤。
     *
     * 病当时的形状：`:1523` 的 `targetGone` 只把内存里两枚打回 `-1L` / `null`，偏好一行不动 ⇒
     * 下一次 VM 重建（冷启动）起手 `:1373-1374` 又把死 id 与死名字读回来，`:1623` 那行 summary 继续念
     * 一个已经不存在的日历名；真去同步时 `CalendarSyncManager` 把 `CALENDAR_ID` 打进死 id，异常又被它
     * 自己的 `runCatching` 吞掉 ⇒ 用户读到的是 `:1432`「同步失败：日历写入异常」，真因被洗成"写入异常"。
     *
     * 判据四枚，各是这条链上的一环：
     * - 两枚 key **各自恰好被 remove 一次**（漏一枚 = 换个方向念旧账：只留名字就把"未选择"念成上任的名字，
     *   只留 id 就还能往死 id 里写）；
     * - 两次 remove 的**块头链完全相同**，而且**两者之间不再出现第二枚 `settingsPrefs.edit`**
     *   （同一次落地只能按位置证：两条一模一样的 `if (…) settingsPrefs.edit { … }` 并排放，
     *   切出来的块头文本是相等的）—— 分头清（拆成两次 edit）在这条链上就是 T99/T100 那一族
     *   「三处清空只有一处成对」的重演，本条先红；
     * - 那次 edit 的块头必须同时含 `targetGone`（只在真检测出死目标时动手）与 `loaded.isNotEmpty()`
     *   （空列表不动手：查询失败交回来的也是空列表，那一刻分不清"日历没了"与"provider 抖了一下"）；
     * - 撤的那一档必须长在 `ensureCalendarsLoaded()` 体内（与 `targetGone` 同一颗函数，中间隔一颗别的函数
     *   就等于换个时机动手）。
     *
     * 两头都钉：**朝宽**——把 `remove("calendar_sync_target_name")` 删掉（只撤 id）⇒ 该 key 的 remove 计数
     * 1→0、两枚 key 的出现次数从 3/3 变 3/2、块头链那条断言也跟着红；**朝紧**——把两枚 remove 拆进两次
     * `edit { }`、或给同一颗 key 补第二次 remove、或把 `loaded.isNotEmpty()` 从闸门里抹掉 ⇒ 三条里相应那条红。
     */
    @Test
    fun theDeadTargetIsDroppedFromPreferencesAsAPairOfKeysAndNotOnlyFromMemory() {
        val code = blankCommentsKeepingLiterals(readMainSource(SCHEDULE_VIEW_MODEL))
        val idDrops = indexOfAll(code, TARGET_ID_DROPPED)
        assertEquals(
            "偏好里 `calendar_sync_target_id` 被 remove 的站点不是恰好 1 处（:1539 那次成对撤）。" +
                "**0 处** = 死 id 又只被内存清掉，冷启动起手 :1373 把它捞回来，本卡那枚病原样复发；" +
                "**2 处** = 多出一条别的路在撤它（撤的时机与前提都得重判）：" + lineHints(code, idDrops),
            TARGET_PREF_DROP_SITES,
            idDrops.size,
        )
        val nameDrops = indexOfAll(code, TARGET_NAME_DROPPED)
        assertEquals(
            "偏好里 `calendar_sync_target_name` 被 remove 的站点不是恰好 1 处 —— 它必须与上面那枚 id 同数：" +
                "两枚是「选中的目标日历」这一件事的 id 与名字，只撤名字就把死 id 留下（还能往里写），" +
                "只撤 id 就把死名字留下（:1623 那行裸读当场念给用户）：" + lineHints(code, nameDrops),
            TARGET_PREF_DROP_SITES,
            nameDrops.size,
        )
        val idHeads = enclosingBlockHeads(code, idDrops.first())
        val nameHeads = enclosingBlockHeads(code, nameDrops.first())
        assertEquals(
            "两枚 key 的remove 落在**不同形状的块**里（左边按 id 切出的块头链 $idHeads、" +
                "右边按 name 切出的 $nameHeads）—— 一枚在闸门里、另一枚在闸门外，就是分头清的另一种写法",
            idHeads,
            nameHeads,
        )
        // 同一块要按**位置**判，不能只比块头字符串：两枚一模一样的 `if (...) settingsPrefs.edit { }`
        // 拆成两条并排，切出来的块头文本是相等的，只有"两者之间没有第二次 edit"才证得了同一次落地
        val earlier = minOf(idDrops.first(), nameDrops.first())
        val later = maxOf(idDrops.first(), nameDrops.first())
        val nextEdit = code.indexOf(PREFS_EDIT_HEAD, earlier + 1)
        assertTrue(
            "两枚偏好 key 不再出自**同一次** `settingsPrefs.edit { }`（前一处之后又出现了 L" +
                (if (nextEdit < 0) 0 else code.substring(0, nextEdit).count { it == '\n' } + 1) +
                " 那一枚 edit，而后一处在 L" + (code.substring(0, later).count { it == '\n' } + 1) +
                "）—— 拆成两次 edit 就是分头清，中间那次失败或提前返回就会留下半对：" +
                lineHints(code, idDrops),
            nextEdit < 0 || nextEdit > later,
        )
        val dropBlock = idHeads.last()
        assertTrue(
            "那次成对撤的落点不再是偏好编辑（最内层块头 `$dropBlock` 里没有 settingsPrefs.edit）—— " +
                "换了载体（Editor 直接 apply？换 SharedPreferences 名？）就得回来重钉这一族：",
            dropBlock.contains(PREFS_EDIT_HEAD),
        )
        assertTrue(
            "撤偏好那一档不再由 `targetGone` 驱动（块头 `$dropBlock`）—— 那等于每次查询都撤一次目标，" +
                "用户什么都没干也会丢掉了选好的日历：",
            dropBlock.contains("targetGone"),
        )
        assertTrue(
            "撤偏好那一档不再拒空列表（块头 `$dropBlock` 不含 loaded.isNotEmpty()）—— 查询失败交回来的" +
                "也是一份空列表（见 docs/derived-field-audit.md §6.4-A 那一格：被调方把异常吞成空集），" +
                "那一刻分不清「日历被删了」与「provider 抖了一下」，动一次手就把用户选好的日历永久抹掉：" +
                "\n" + lineAt(code, idDrops.first()),
            dropBlock.contains("loaded.isNotEmpty()"),
        )
        val fnHead = code.indexOf(ENSURE_LOADED_HEAD)
        check(fnHead >= 0) { "靶子：找不到 `$ENSURE_LOADED_HEAD` —— 那次检测换了宿主，本守卫要跟着改" }
        val fnEnd = code.indexOf(ENSURE_LOADED_TAIL, fnHead)
        check(fnEnd > fnHead) { "靶子：ensureCalendarsLoaded 之后找不到下一处成员边界，窗口切不出来" }
        assertTrue(
            "撤偏好那一档跑出了 ensureCalendarsLoaded() 的体内（窗口 " +
                "${code.substring(fnHead, fnEnd).lines().size} 行）—— 换时机就得重判「什么时候才敢说那个日历真没了」",
            idDrops.first() in fnHead until fnEnd,
        )
        assertEquals(
            "`calendar_sync_target_id` 在 VM 里的出现次数不再是 3（起手读 1 + 选完写 1 + 撤 1）。" +
                "**多一处** = 又添一枚读者或第二处撤点，本判据的「撤的时机」要重算；**少一处** = 有半条链" +
                "换了 key 或换了载体，字面判据跟不上了：",
            TARGET_ID_PREF_LIFE_POINTS,
            occurrences(code, TARGET_ID_PREF_KEY),
        )
        assertEquals(
            "`calendar_sync_target_name` 的出现次数不再是 3（同上），而且它必须与上面那枚**同数** —— " +
                "两枚的读/写/撤站点数一旦不等，就说明某一头少了那一半：" +
                lineHints(code, indexOfAll(code, TARGET_NAME_PREF_KEY)),
            TARGET_NAME_PREF_LIFE_POINTS,
            occurrences(code, TARGET_NAME_PREF_KEY),
        )
        // 靶子：起手那两枚读点（死 id 就是从这儿被捞回来的）与撤的那三行原文
        assertEquals("靶子：起手读 targetId 那行原文还在（:1373）：", 1, occurrences(code, TARGET_ID_READ_SHAPE))
        assertEquals("靶子：起手读 targetName 那行原文还在（:1374）：", 1, occurrences(code, TARGET_NAME_READ_SHAPE))
        assertEquals("靶子：成对撤那一档的闸门原文还在（:1538，两枚 key 由它驱动）：",
            1, occurrences(code, TARGET_PREF_DROP_GATE))
        assertEquals("靶子：撤 id 那一行原文还在（:1539）：", 1, occurrences(code, TARGET_ID_DROPPED))
        assertEquals("靶子：撤 name 那一行原文还在（:1540）：", 1, occurrences(code, TARGET_NAME_DROPPED))
    }

    /**
     * ①-b（**T110 新增**）：`calendarsLoaded` 这枚缓存旗标今天有了**唯一一枚复位站点**，就是开窗那一档。
     *
     * 为什么这一族归在本文件：`calendars` 与 `calendarsLoaded` 是 §6.2 表 #11 那一对，而 `calendars`
     * 的读者就是选择器那张列表（`SettingsScreen.kt:1870` 的 isEmpty 判断 + 下面的逐行 clickable）。
     * 此前 `calendarsLoaded = false` 在全仓**一处都没有** ⇒ `:1512` 那次早返回让列表变成"进程寿命"的缓存：
     * 用户在系统日历里刚删/刚建的日历看不见，连带 `targetGone` 那次检测一个进程里只跑头一趟。
     * 本卡取的修法是把复位**钉在开窗那一档**（不是拆掉早返回、也不是给同步入口加复位）—— 于是"每次开窗
     * 强制刷新"这件事现在完全靠 `:1449` 那一行维持，本判据钉的就是这一行的形状、枚数与位置。
     *
     * 两头都钉：**朝宽**——把 `calendarsLoaded = false` 从那行删掉（复位没了 ⇒ 缓存回到进程寿命）⇒
     * 复位站点 1→0 红、生命点 5→4 红、`openCalendarPicker` 的实参表不含 showPicker 那条一并红；
     * **朝紧**——再给别处补一枚复位（例如顺手写进 `:1389` 同步起手，那是本卡量过代价之后**有意不加**的一档，
     * 加了就得重判 provider 趟数）⇒ 复位站点 1→2 红、生命点 5→6 红、"同步入口那档不带它"那条红。
     */
    @Test
    fun theCalendarListCacheHasExactlyOneResetSiteAndItIsThePickerOpening() {
        val code = blankCommentsKeepingLiterals(readMainSource(SCHEDULE_VIEW_MODEL))
        val resets = indexOfAll(code, CALENDARS_LOADED_RESET)
        assertEquals(
            "`calendarsLoaded = false` 的复位站点不再是 1 处（现在只有 :1449 openCalendarPicker 那一档）。" +
                "**0 处** = 缓存回到「进程寿命」，选择器列的是上一次查询的结果、targetGone 一个进程只检测一次；" +
                "**2 处以上** = 又有一条链在作废这份缓存，先问它是不是也给同步入口加了一次 ContentProvider 查询" +
                "（本卡量过、有意没加）：" + lineHints(code, resets),
            CALENDARS_LOADED_RESET_SITES,
            resets.size,
        )
        assertEquals(
            "唯一的复位站点不再长在 `fun openCalendarPicker()` 体内（现宿主：" +
                precedingFunHead(code, resets.first()) + "）—— 宿主一换，「每次开窗都重查」这句话就得按新宿主重推",
            OPEN_PICKER_HEAD,
            precedingFunHead(code, resets.first()),
        )
        val launch = code.indexOf(PICKER_QUERY_LAUNCH)
        check(launch > 0) { "靶子：找不到 `viewModelScope.launch { ensureCalendarsLoaded() }`（:1450）—— 开窗那次查询换了写法" }
        assertTrue(
            "复位跑到了那次查询**之后** —— 这一次开窗仍然撞上 :1512 的早返回，列表照旧是陈的：" +
                lineAt(code, resets.first()),
            resets.first() < launch,
        )
        val args = rebuildArguments(code, resets.first())
        assertTrue(
            "开窗与复位不再是同一次重建落地的两枚（那次 copy 的实参表：" + args.trim() + "）—— " +
                "分成两次 update 就有一帧是「窗口开着、缓存旗标还没翻」，那一帧查询照样早返回",
            args.contains(PICKER_OPENED),
        )
        assertEquals(
            "`calendarsLoaded` 在 VM 里的生命点不再是 5（1 枚参数表声明 + 1 枚读点 :1512 + 3 枚写点 " +
                "1516/1527 的 true 与 1449 的 false）。**多一处** = 添了读者或又一枚复位站点，" +
                "「这份缓存什么时候算旧」要重判；**少一处** = 早返回那一句被拆了（那是另一套修法，" +
                "本判据与 :1516/:1527 那两处写点都得跟着重钉）",
            CALENDARS_LOADED_LIFE_POINTS,
            occurrences(code, "calendarsLoaded"),
        )
        assertEquals(
            "`calendarsLoaded = true` 的写点不再是 2 处（:1516 查询失败那一档 + :1527 成功那一档）—— " +
                "少一处就是有人把某一档的落地顺序改了，本判据那枚 5 的生命点要跟着重算",
            CALENDARS_LOADED_TRUE_SITES,
            occurrences(code, "calendarsLoaded = true"),
        )
        assertEquals(
            "靶子：:1512 那枚早返回还在 —— 本卡的修法是把**复位入口**钉在开窗，不是把闸门拆掉" +
                "（拆闸门 = 每次调用都查，连同步入口也加一趟）：",
            1,
            occurrences(code, CALENDARS_LOADED_EARLY_RETURN),
        )
        assertEquals("靶子：参数表上的初值仍是 false（:96，第一次开窗必查的前提）：",
            1, occurrences(code, CALENDARS_LOADED_DECL))
        assertEquals("靶子：开窗那一档的整行原文还在（:1449）：", 1, occurrences(code, PICKER_RESET_SHAPE))
        // 取舍的另一头：同步入口那档今天**不带**复位（给了它就得给 startCalendarSync 加一次 provider 查询）
        val syncFlagWrite = code.indexOf(FLAG_ASSIGNED, code.indexOf(SYNC_ENTRY_PAIRED_COPY))
        check(syncFlagWrite > 0) { "靶子：找不到 :1389 那次成对清 —— 同步起手的写法换过了，这一条要重钉" }
        assertFalse(
            "同步入口 :1389 那次重建开始带 `calendarsLoaded` 了 —— 这是本卡量过代价之后**有意没做**的那一支" +
                "（每次点同步多一趟 ContentProvider 查询，而同步链自己会跑 computeDiff：Room 读 + 逐课次摘要）；" +
                "要做这一支，先按 T110 回执那笔账重判 provider 趟数，再把本判据的 1 处复位改成 2 处：" +
                lineAt(code, syncFlagWrite),
            rebuildArguments(code, syncFlagWrite).contains("calendarsLoaded"),
        )
    }

    // ---------------- ③ message / permissionPermanentlyDenied ----------------

    /**
     * 写/清侧（**T106 之后重钉、T110 再重钉**）：**成对写 1 处 + 成对清 2 处 + 旗标单清 1 处**。
     *
     * `permissionPermanentlyDenied = ` 出现 4 次（:1497 的成对生产 + :1389 同步起手那次**成对**清 +
     * :1481 移除链起手那次**成对**清 + :1508 的单清）、`message = null` 出现 2 次（:1389 与 :1481，
     * 两枚都必须连旗标一起清 —— 本判据是**逐处**取的，不是只取第一处）。
     * 两头都钉：
     * - **少一处旗标赋值**（= 有人把 :1389 或 :1481 的成对清又拆回「只撤句子」）→ 本卡那枚真漏清复发：
     *   在系统设置里手动授予权限之后点同步，`withCalendarPermission` 的短路让 launcher 根本不启动、
     *   `onCalendarPermissionGranted()` 永不被调，旗标常驻到下一句提示把 :1664 那格重新点亮为止；
     *   :1481 少一处就是 T109 那枚（「已移除 N 个日程」旁边挂着那颗按钮）复发；
     * - **多一处旗标赋值 / 多一处 `message = null`** → 冒出了第三条只动一枚的通道，
     *   「撤句子必顺手撤旗标」这条反命题的覆盖面要按新站点重数一遍（新站点也得逐处带旗标，
     *   且得先证它只在已授权时可达，见 `everyRouteIntoTheSyncEntryStandsInsideAPermissionGate` 与
     *   `everyRouteIntoTheRemoveChainStandsInsideTheSameGateAndClearsTheFlagPaired`）；
     * - **:1508 那处开始连句子一起清** → 句子清点从 2 变 3，`:1664` 那格 visible 的账要重判（不是病，
     *   但这一族「谁在撤句子」的清单变了，§6.2 #7 那一格要跟着改）。
     */
    @Test
    fun thePermissionFlagAndTheMessageAreWrittenTogetherOnceAndClearedPairedAtTheSyncEntry() {
        val code = blankCommentsKeepingLiterals(readMainSource(SCHEDULE_VIEW_MODEL))
        val flagWrites = indexOfAll(code, FLAG_ASSIGNED)
        assertEquals(
            "给 `permissionPermanentlyDenied` 赋值的站点从 4 处变了（:1497 成对生产 + :1389 同步起手成对清 + " +
                ":1481 移除链起手成对清 + :1508 单清）。**少一处** = 两枚成对清里被拆回去一枚，" +
                "T106（「同步完成」旁）或 T109（「已移除 N 个日程」旁）那枚真漏清当场复发，" +
                "本文件第三枚 `@Test`（可达路径枚举）与移除链那枚也会跟着红；" +
                "**多一处** = 又添一条只动旗标不动句子的路径，那道「句子还在才念得出按钮」的闸就多一个绕开" +
                "它的入口，得先证新站点只在已授权时可达：" + lineHints(code, flagWrites),
            FLAG_WRITE_SITES,
            flagWrites.size,
        )
        val messageClears = indexOfAll(code, MESSAGE_CLEARED)
        assertEquals(
            "`message = null` 的清空站点不再是 2 处（:1389 startCalendarSync 起手 + :1481 removeSyncedEvents " +
                "起手，两枚都必须连旗标一起清）。**多一处** = 又添一条撤句子的站点，它同样得把旗标带上，" +
                "否则那条站点就是 T106/T109 那枚病的新载体；**少一处** = 起手不再撤句子，" +
                "那 :1664 那枚 `visible = calendarSync.message != null` 的挡法就换了位置：" +
                lineHints(code, messageClears),
            MESSAGE_CLEAR_SITES,
            messageClears.size,
        )
        val raisedAt = code.indexOf(FLAG_RAISED)
        check(raisedAt >= 0) {
            "靶子：找不到 `permissionPermanentlyDenied = !canAskAgain,`（:1497 那次成对生产）—— " +
                "旗标的生产写法换过了，本守卫整批要跟着重判"
        }
        assertTrue(
            "那一次写旗标的重建里没有 `message = ` —— 永久拒绝的旗标与那句" +
                "「日历权限已被永久拒绝，请到系统设置手动开启」出自同一次生产（:1496-1503），" +
                "分开写就会有一枚先落地：\n" + lineAt(code, raisedAt),
            rebuildArguments(code, raisedAt).contains(MESSAGE_ASSIGNED),
        )
        val grantedShapeAt = code.indexOf(GRANTED_CLEAR_SHAPE)
        check(grantedShapeAt >= 0) {
            "靶子：找不到 `_calendarSync.update { it.copy(permissionPermanentlyDenied = false) }`" +
                "（:1508 那次单清；:1389 起手那处现在是五枚实参的成对清，别拿它当这一条的靶子）"
        }
        val clearedAt = code.indexOf(FLAG_CLEARED, grantedShapeAt)
        assertTrue(
            ":1508 onCalendarPermissionGranted 那一处开始连 `message` 一起清了 —— 那是把「撤句子」做成两枚" +
                "站点（:1389 起手 + 授权回调），上面 `MESSAGE_CLEAR_SITES` 那格会先红：届时 §6.2 #7 那一格的" +
                "「成对写 1 / 成对清 1 / 单清 1」三枚数与本文件的判据都要按两枚句子清点重钉：\n" +
                lineAt(code, clearedAt),
            !rebuildArguments(code, clearedAt).contains(MESSAGE_ASSIGNED),
        )
        val clearsMissingFlag = messageClears.filter { !rebuildArguments(code, it).contains(FLAG_ASSIGNED) }
        assertTrue(
            "这些撤句子（`message = null`）的站点没有把旗标一起清 —— **这就是 T106 / T109 修掉的那两枚真漏清" +
                "在复发**：在系统设置里手动授予权限后点同步（:1389）或点「移除已同步的日程」（:1481），" +
                "withCalendarPermission 的短路（SettingsScreen.kt:331）让 launcher 根本不启动，于是 :1508 " +
                "那次单清永远走不到，旗标常驻；等 :1429-1439 那句「同步完成…」或 :1486 那句「已移除 N 个日程」" +
                "把 :1664 那格点亮，:1677 那颗「去系统设置开启日历权限」就挂在完成文案旁边。" +
                "改回去之前先看下面两枚可达路径判据 `everyRouteIntoTheSyncEntryStandsInsideAPermissionGate` " +
                "与 `everyRouteIntoTheRemoveChainStandsInsideTheSameGateAndClearsTheFlagPaired`：\n" +
                lineHints(code, clearsMissingFlag),
            clearsMissingFlag.isEmpty(),
        )
        // 靶子：被这四条断言点名的四行原文
        assertEquals(
            "靶子：:1389 那一行的原文还在（syncing + message + 旗标 + diff + skippedOccurrences 五枚一起落地）：",
            1,
            occurrences(code, SYNC_ENTRY_PAIRED_COPY),
        )
        assertEquals(
            "靶子：:1481 那一行的原文还在（T110 给移除链起手补的那档成对清，三枚一起落地）—— " +
                "它不在就是 T109 那枚漏清复发（「已移除 N 个日程」旁边挂着那颗按钮）：",
            1,
            occurrences(code, REMOVE_ENTRY_CLEAR_SHAPE),
        )
        assertEquals(
            "靶子：:1481 那次成对清之后落下的句子原文还在（它就是重新点亮 :1664 那格的那一句）：",
            1,
            occurrences(code, REMOVE_DONE_MESSAGE_SHAPE),
        )
        assertEquals(
            "靶子：:1508 那一行的原文还在（整颗 onCalendarPermissionGranted 就这一句 copy）：",
            1,
            occurrences(code, GRANTED_CLEAR_SHAPE),
        )
        assertEquals(
            "靶子：与旗标同一次生产的那句永久拒绝文案还在（它不在才说明这两枚已经不成对了）：",
            1,
            occurrences(code, DENIED_MESSAGE_TEXT),
        )
        assertEquals("靶子：参数表上 message 的初值仍是 null（:109）：", 1, occurrences(code, MESSAGE_DECL))
        assertEquals("靶子：参数表上旗标的初值仍是 false（:112）：", 1, occurrences(code, FLAG_DECL))
    }

    /**
     * 读侧前提（**T106 之后改口径**）：这两枚字段今天不被念出假话，**第一层是 :1389 那次成对清**，
     * 两道闸退成第二层保险 —— 但它们仍然值得钉，因为「机制可被别处一行改动挪走」这句 T103 的警告
     * 对成对清同样成立（谁把 :1389 的旗标删回去，闸 B 一个人扛不住，见上面那枚 `@Test`）。
     *
     * 闸 A（`:331` 的 `if (viewModel.hasCalendarPermission())` 短路）决定的是**走不走 launcher**：
     * 有权限就直接 `action()`，所以 :317 那次 `onCalendarPermissionGranted()` 到不了 ——
     * T105 当时把它当成"挡住漏清的闸"，方向反了：它恰恰是那条漏清路径的**成因**。
     * 闸 B（`:1664` 的 `visible = calendarSync.message != null` + `:1665` 的 `message?.let`）挡的是
     * 「句子为空时按钮整块不组合」，现在它只是配着成对清的第二层。
     *
     * 两头都钉：闸少一处（0 命中）红、闸多一处（2 命中）红；读者跑出窗口红、窗口不再像那块也红。
     */
    @Test
    fun thoseTwoGatesAreNowTheSecondLayerBehindThePairedClearAtTheSyncEntry() {
        val screen = blankCommentsKeepingLiterals(readMainSource(SETTINGS_SCREEN))
        // ---- 闸 A ----
        assertEquals(
            "`viewModel.hasCalendarPermission()` 在界面上的调用点不再是 1 处（现在只有 :331 withCalendarPermission " +
                "的第一句）。**多一处** = 又添了一道能绕过 launcher 的入口，每一道都得问" +
                "「它进去的那条链有没有把旧旗标清掉」（T106 的清点是 :1389 起手那枚成对清，覆盖 startCalendarSync）；" +
                "**少一处** = 短路没了 —— 每次点同步都会先弹申请框，:317 那次单清重新变成可达，" +
                "「靠成对清兜住」这件事的账要重判：",
            PERMISSION_GATE_SITES,
            occurrences(screen, PERMISSION_GATE),
        )
        assertEquals(
            "靶子：withCalendarPermission 的签名原文还在（闸 A 的宿主函数）：",
            1,
            occurrences(screen, WITH_PERMISSION_HEAD),
        )
        val head = screen.indexOf(WITH_PERMISSION_HEAD)
        val gate = screen.indexOf(PERMISSION_GATE)
        check(head >= 0 && gate >= 0) { "靶子：闸 A 的两行原文至少要都在，否则本守卫扫的不是这一页" }
        assertTrue(
            "闸 A 不再是 withCalendarPermission 的第一句（中间隔了 " +
                "${screen.substring(head + WITH_PERMISSION_HEAD.length, gate).trim()}）—— " +
                "「有权限就不启动 launcher」这一句前提被削弱，③靠它挡 :1508 的账要重判",
            screen.substring(head + WITH_PERMISSION_HEAD.length, gate).all { it.isWhitespace() },
        )
        assertEquals(
            "`calendarPermissionLauncher.launch(` 应当恰好 1 处（:335，在闸 A 的 else 分支里）—— " +
                "这一枚是「有权限时根本不启动申请框 ⇒ 到不了 :317」的凭据；多一处就有人绕过闸直接申请",
            1,
            occurrences(screen, LAUNCH_CALL),
        )
        assertTrue(
            "launcher.launch 跑到了闸 A 之前 —— 那等于每次点同步都会先弹系统申请框，" +
                "§6.4-B 那条「永久拒绝后再点也不会走到 onCalendarPermissionGranted」的推理作废",
            gate < screen.indexOf(LAUNCH_CALL),
        )
        assertEquals(
            "靶子：`fun hasCalendarPermission(): Boolean = calendarSyncManager.hasPermission()` 还在 " +
                "（ScheduleViewModel.kt:1380，闸 A 问的就是它）：",
            1,
            occurrences(blankCommentsKeepingLiterals(readMainSource(SCHEDULE_VIEW_MODEL)), HAS_PERMISSION_DECL),
        )
        // ---- 闸 A 之内：唯一那次清旗标的调用点 ----
        assertEquals(
            "`viewModel.onCalendarPermissionGranted()` 的调用点不再是 1 处（:317）。多一处 = 有人绕过授权回调" +
                "去清旗标，那 :1508 那次单清就不再「走不到」",
            1,
            occurrences(screen, GRANTED_CALL),
        )
        assertEquals(
            "靶子：全授予那一档的判断原文还在（:316，它是 :317 唯一的入口）：",
            1,
            occurrences(screen, GRANTED_BRANCH_HEAD),
        )
        val branch = screen.indexOf(GRANTED_BRANCH_HEAD)
        val elseAt = screen.indexOf(ELSE_BRANCH_HEAD, branch + GRANTED_BRANCH_HEAD.length)
        check(elseAt > branch) { "靶子：:316 之后找不到那档 if/else 的 else 边界，窗口切不出来" }
        val call = screen.indexOf(GRANTED_CALL)
        assertTrue(
            "清旗标那次调用（onCalendarPermissionGranted）跑出了「全部授予」那一档 —— " +
                ":1508 的单清从此可以在拒绝路径上发生，旗标与句子谁先落地就又要判一次",
            call in branch until elseAt,
        )
        // ---- 闸 B ----
        assertEquals(
            "旗标 `calendarSync.permissionPermanentlyDenied` 在界面上的读者不再是 1 处（只有 :1677 那颗按钮）。" +
                "**多一处** = 添了第二张嘴，而它多半不在 message 驱动的窗口里 ⇒ 任何「旗标是旧的」残态" +
                "（例如 :1389 之外那条不撤旗标的链留下的）当场被念出来；" +
                "**少一处** = 那颗按钮不在了，③这一对已经没有读者，本守卫与 §6.8③ 那格一起收掉",
            1,
            occurrences(screen, FLAG_READER),
        )
        assertEquals(
            "靶子：:1664 那枚 `visible = calendarSync.message != null` 的 item 原文还在（闸 B 的第一层）：",
            1,
            occurrences(screen, STATUS_ITEM_HEAD),
        )
        assertEquals(
            "靶子：`calendarSync.message` 的读者仍是 3 处（:1664 判据 / :1665 取值 / :1874 选择器空列表的兜底文案）—— " +
                "这一枚是上面那条 1 值判据的凭据，也是「句子有几张嘴」的清单",
            MESSAGE_UI_READS,
            occurrences(screen, "calendarSync.message"),
        )
        val windowStart = screen.indexOf(STATUS_ITEM_HEAD)
        val windowEnd = screen.indexOf("SettingsGroup(", windowStart + STATUS_ITEM_HEAD.length)
        check(windowEnd > windowStart) { "靶子：那一格之后找不到下一个 SettingsGroup 边界，窗口切不出来" }
        val windowLength = windowEnd - windowStart
        check(windowLength in STATUS_WINDOW_MIN..STATUS_WINDOW_MAX) {
            "状态那一格的窗口长度越界（$windowLength 不在 $STATUS_WINDOW_MIN..$STATUS_WINDOW_MAX，现测 960）：" +
                "它不再是原来那块了，「句子一撤按钮就没了」这句话得重判"
        }
        val flagReader = screen.indexOf(FLAG_READER)
        assertTrue(
            "那颗「去系统设置开启日历权限」的按钮跑出了 message 驱动的那一格 —— T106（:1389）与 T110（:1481）" +
                "之后两枚起手都是成对清（撤句子顺手撤旗标），这层闸只是第二层保险；但它一挪走，任何「旗标旧、" +
                "句子新」的残态都会常驻，届时③要从「已修 + 双层」重判成「真漏清」",
            flagReader in windowStart until windowEnd,
        )
        assertEquals(
            "靶子：那颗按钮的文案「去系统设置开启日历权限」只有一处 —— 它是这一对漏清唯一会被念出来的地方",
            1,
            occurrences(screen, SETTINGS_BUTTON_TEXT),
        )
    }

    /**
     * 前提侧（**T106 新增**，修法 (a) 的承重墙）：**走到 `startCalendarSync()` 的每一条路都站在权限闸里面**。
     *
     * 为什么这一族判据归在本文件而不是别处：(a) 的全部理由就是那句「进到起手时权限必然已经到手」，
     * 这句话一倒，`:1389` 那次成对清就从「顺手宣布永久拒绝不成立」降级成「在无权限时撒谎」。
     * 上面两枚 ③ 判据钉的是**写侧的枚数**，本枚钉的是**谁能进到那个写侧** —— 两头合起来才是完整的一条账。
     *
     * 全仓 `grep -rn "startCalendarSync" app/src/main/java` 复算出的入口逐条（行号按 `688b191`＋T106
     * 那次同行改写，两颗文件都没增删行 ⇒ 与 `688b191` 一致）：
     * - **入口 1** `ui/settings/SettingsScreen.kt:1641` `onClick = { startCalendarSync() }`（那颗按钮）
     *   → `:341` `fun startCalendarSync() = withCalendarPermission { viewModel.startCalendarSync() }`
     *   → `:331` 的闸：已授权 ⇒ `:332` `action()` 直接执行；未授权 ⇒ `:335` launcher → `:316` 全授予
     *   那一档 → `:319`。**两支都已持有权限**。
     * - **入口 2** `:319` `(action ?: viewModel::startCalendarSync).invoke()` —— 长在
     *   `:316` `if (grants.isNotEmpty() && grants.values.all { it }) {` 里面，权限是系统刚交回的；
     *   且 `:317` 刚调过 `onCalendarPermissionGranted()`（`:1508` 那次单清）。
     * - **入口 3** `ui/ScheduleViewModel.kt:1463` `selectCalendarTarget`（宿主 `:1457`）选完日历内部那次
     *   `startCalendarSync()` —— **唯一一枚不过 withCalendarPermission 的调用点**，所以为它单独钉两道：
     *   ① 它唯一的界面触发点 `SettingsScreen.kt:1888` 长在
     *   `ModalTransition(open = calendarSync.showPicker)`（`:1859`）那一层窗口之内，而
     *   `showPicker = true` 全仓只有两枚写点（VM `:1397` 长在 startCalendarSync 体内、
     *   `:1449` 长在 openCalendarPicker 体内），后者的唯一界面入口是 `:1625`
     *   `onClick = { withCalendarPermission { viewModel.openCalendarPicker() } }` ⇒ **开窗这件事本身在闸里**；
     *   ② 那一行 clickable 只在 `:1870` `if (calendarSync.calendars.isEmpty()) {` 的 **else** 侧组合，
     *   而 `calendars` 只由 `ensureCalendarsLoaded()` 的成功分支填过 ⇒ 「有可点的行」= 上一次
     *   `queryCalendars` 成功 = 那一刻权限是真的。
     *
     * ⚠️ 静态证不了的那半如实记（报告里归进「只能等真机」）：T110 之后 `calendarsLoaded`（VM `:1512` 那枚早返回
     * 读的就是它）有了**一枚复位站点** —— `:1449` 开窗那一档连带写 `calendarsLoaded = false`
     * （`grep -rn "calendarsLoaded = false" app/src/main/java` ⇒ 1 行，此前是 0 行）⇒ 选择器列的不再是
     * "进程寿命"的缓存。但**同步入口那一档仍然吃缓存**（本卡的取舍：不给 `startCalendarSync` 加 provider 趟数），
     * 于是同一进程里第二次点「同步」用的是上一次开窗/第一次同步查回的那份列表。
     * 另一件事今天仍只能等真机：用户在弹窗开着的时候去系统设置把权限**关掉**再回来点一行，起手那次清旗标就是
     * 在无权限时清。但旗标只活在内存里（参数表 `:112` 初值 false，全仓无偏好持久化），且下一次点「同步到系统日历」会走闸 → launcher →
     * `onCalendarPermissionDenied` 重新立旗标 ⇒ 最坏是那一帧少一颗按钮，不是假话常驻。
     *
     * 两头都钉：闸被拆/被挪位 → `:331`、`:332`、`:335` 三条红；新增一枚绕过闸的同步入口
     * （例如直接 `onClick = { viewModel.startCalendarSync() }`）→ `BARE_SYNC_CALL_SITES` 1→2 红；
     * 内部调用换宿主 → `precedingFunHead` 那条红；新增一枚开窗点 → `PICKER_OPEN_SITES` 2→3 红。
     */
    @Test
    fun everyRouteIntoTheSyncEntryStandsInsideAPermissionGate() {
        val screen = blankCommentsKeepingLiterals(readMainSource(SETTINGS_SCREEN))
        val code = blankCommentsKeepingLiterals(readMainSource(SCHEDULE_VIEW_MODEL))
        val manager = blankCommentsKeepingLiterals(readMainSource(CALENDAR_SYNC_MANAGER))
        // ---- 闸本体（入口 1 的第一道）----
        assertEquals(
            "靶子：闸的宿主函数定义原文还在（`SettingsScreen.kt:330` " +
                "`fun withCalendarPermission(action: () -> Unit) {`）：",
            1,
            occurrences(screen, WITH_PERMISSION_HEAD),
        )
        assertEquals(
            "`if (viewModel.hasCalendarPermission()) {`（:331）不再是恰好 1 处。**多一处** = 又添一道能绕过 " +
                "launcher 的短路，每加一枚都得问「它进的那条链有没有把旧旗标清掉」；**少一处** = 短路没了，" +
                "`:1508` 那次单清重新变成唯一通道，本卡那枚病就换个方向复发：" +
                lineHints(screen, indexOfAll(screen, PERMISSION_GATE)),
            PERMISSION_GATE_SITES,
            occurrences(screen, PERMISSION_GATE),
        )
        val gateHead = screen.indexOf(WITH_PERMISSION_HEAD)
        val gate = screen.indexOf(PERMISSION_GATE)
        check(gateHead >= 0 && gate >= 0) { "靶子：闸 A 的两行原文至少要都在，否则本守卫扫的不是这一页" }
        assertTrue(
            "闸不再是 withCalendarPermission 的第一句（中间隔着「" +
                screen.substring(gateHead + WITH_PERMISSION_HEAD.length, gate).trim() + "」）—— " +
                "「点同步之前先问一句真实权限」这条前提被削弱，起手那次清旗标就可能是假话",
            screen.substring(gateHead + WITH_PERMISSION_HEAD.length, gate).all { it.isWhitespace() },
        )
        val launch = indexOfAll(screen, LAUNCH_CALL)
        assertEquals(
            "`calendarPermissionLauncher.launch(` 不再是恰好 1 处（:335，闸的 else 分支里）。这一枚是" +
                "「已授权时根本不启动申请框 ⇒ 到不了 :317 那次单清」的凭据，也是本卡病根的成因；" +
                "**多一处** = 有人绕过闸直接申请，起手清旗标的覆盖面要重算：" + lineHints(screen, launch),
            1,
            launch.size,
        )
        assertTrue(
            "launcher.launch 跑到了闸之前 —— 那等于每次点同步都先弹申请框，本卡第 3 步那条" +
                "「有权限就不启动 launcher ⇒ 到不了 :317」的推理作废，:1389 那次成对清也就从" +
                "「唯一覆盖正常路径的清点」降级成多余的一刀",
            gate < launch.first(),
        )
        val actionCalls = indexOfAll(screen, ACTION_CALL)
        assertEquals(
            "闸 true 分支里那句 `action()`（:332）不再是恰好 1 处 —— 它就是「已授权 ⇒ 直接执行、不启动 " +
                "launcher」这一支的载体，本卡那条正常路径走的正是它：" + lineHints(screen, actionCalls),
            1,
            actionCalls.size,
        )
        assertTrue(
            "`action()` 不在 `if (viewModel.hasCalendarPermission())` 之后 —— 「有权限就直接执行 action」" +
                "这句话不再是闸的语义，入口 1 的权限状态要按新写法重推",
            actionCalls.first() > gate,
        )
        // ---- 被闸包住的三块，与那颗按钮 ----
        assertEquals(
            "`withCalendarPermission { … }` 的调用块不再是 3 处（:341 同步 / :1625 选目标日历 / " +
                ":1654 移除已同步日程）。**多一处** = 又添一条要走闸的通道；**少一处** = 有入口不再走闸，" +
                "起手那次清旗标的覆盖面要重判",
            GATED_CALL_SITES,
            occurrences(screen, GATED_CALL_BLOCK),
        )
        assertEquals("靶子：入口 1 那条链的原文还在（:341，整句都在闸里）：", 1, occurrences(screen, GATED_SYNC))
        assertEquals("靶子：选目标日历那行的原文还在（入口 3 的开窗侧）：", 1, occurrences(screen, GATED_PICKER))
        assertEquals("靶子：移除已同步日程那行的原文还在（它也在闸里，但不走到 startCalendarSync）：",
            1, occurrences(screen, GATED_REMOVE))
        assertEquals("靶子：那颗「同步到系统日历」按钮的 onClick 原文还在（:1641，入口 1 的起点）：",
            1, occurrences(screen, SYNC_BUTTON_SHAPE))
        assertEquals(
            "`viewModel.startCalendarSync()` 在界面上的调用点不再是 1 处（只有 :341 闸里那一枚）。" +
                "**多一处** = 有人新增一枚不过闸的同步入口 ⇒ 起手那次成对清就不再必然发生，" +
                "本卡那枚「旗标永不清」会换个载体回来：" +
                lineHints(screen, indexOfAll(screen, BARE_SYNC_CALL)),
            BARE_SYNC_CALL_SITES,
            occurrences(screen, BARE_SYNC_CALL),
        )
        // ---- 入口 2：launcher 全授予那一档 ----
        assertEquals("靶子：全授予那一档的判断原文还在（:316，:317 那次单清唯一的入口）：",
            1, occurrences(screen, GRANTED_BRANCH_HEAD))
        assertEquals(
            "`viewModel::startCalendarSync` 那枚方法引用不再是 1 处（:319 的默认支）—— 它是入口 2 的载体：" +
                lineHints(screen, indexOfAll(screen, SYNC_METHOD_REF)),
            1,
            occurrences(screen, SYNC_METHOD_REF),
        )
        val grantedBranch = screen.indexOf(GRANTED_BRANCH_HEAD)
        val elseAt = screen.indexOf(ELSE_BRANCH_HEAD, grantedBranch + GRANTED_BRANCH_HEAD.length)
        check(elseAt > grantedBranch) { "靶子：:316 之后找不到那档 if/else 的 else 边界，窗口切不出来" }
        assertTrue(
            "默认同步那次调用跑出了「全部授予」那一档 —— 它从此可以在拒绝路径上被 invoke，" +
                "「进到 startCalendarSync 时权限必已到手」这条前提要重判",
            screen.indexOf(SYNC_METHOD_REF) in grantedBranch until elseAt,
        )
        // ---- 入口 3：唯一一枚不过闸的内部调用（宿主必须点名）----
        val entry = code.indexOf(SYNC_ENTRY_HEAD)
        check(entry >= 0) {
            "靶子：找不到 `fun startCalendarSync() {`（:1383）—— 起手的宿主换了，本守卫要跟着改"
        }
        val selfRef = entry until (entry + SYNC_ENTRY_HEAD.length)
        val internalCalls = indexOfAll(code, SYNC_CALL_TEXT).filter { it !in selfRef }
        assertEquals(
            "ScheduleViewModel 内部对 `startCalendarSync()` 的调用不再是 1 处（现在只有 :1463 " +
                "selectCalendarTarget 选完日历那一次）。**多一处** = 又添一枚不经闸的内部入口，" +
                "必须先证它同样只在已授权时可达，否则起手那次清旗标就是假话：" + lineHints(code, internalCalls),
            1,
            internalCalls.size,
        )
        assertEquals(
            "内部那次调用的宿主不再是 `fun selectCalendarTarget(calendarId: Long, displayName: String) {`" +
                "（:1457）—— 现在它是「开窗在闸里 + 列表非空要有权限」这两道前提的载体，宿主一换就得按新宿主" +
                "重做一遍（现宿主：" + precedingFunHead(code, internalCalls.first()).trim() + "）",
            SELECT_TARGET_HEAD,
            precedingFunHead(code, internalCalls.first()),
        )
        assertEquals("靶子：`fun selectCalendarTarget(…) {` 仍是唯一一枚定义（:1457）：",
            1, occurrences(code, SELECT_TARGET_HEAD))
        assertEquals(
            "界面上 `viewModel.selectCalendarTarget(` 的调用点不再是 1 处（:1888 那一行日历名）—— 它经一次" +
                "点击直达 VM 内部那次 startCalendarSync()，多一枚就是多一条不经 withCalendarPermission 的入口",
            1,
            occurrences(screen, SELECT_TARGET_CALL),
        )
        val modalAt = screen.indexOf(PICKER_MODAL_HEAD)
        check(modalAt >= 0) {
            "靶子：找不到 `ModalTransition(open = calendarSync.showPicker)`（:1859）—— " +
                "选择器那层的驱动方式换过了，「开窗在闸里」要按新写法重推"
        }
        val nextModal = screen.indexOf("ModalTransition(", modalAt + PICKER_MODAL_HEAD.length)
        check(nextModal > modalAt) { "靶子：选择器弹窗之后找不到下一层 ModalTransition 边界，窗口切不出来" }
        val click = screen.indexOf(SELECT_TARGET_CALL)
        assertTrue(
            "选日历那一行的 clickable 跑出了 showPicker 驱动的那一层 —— 入口 3 从此可能在闸外被触发",
            click in modalAt until nextModal,
        )
        val emptyGate = screen.indexOf(EMPTY_LIST_GATE)
        assertTrue(
            "「列表为空就整块换成一句提示」那道判断（:1870）不在那一行之前 —— 那就不能再把「有可点的行」" +
                "当成「上一次 queryCalendars 成功＝那一刻真有权限」的证据",
            emptyGate in modalAt until click,
        )
        val pickerOpens = indexOfAll(code, PICKER_OPENED)
        assertEquals(
            "`showPicker = true` 的写点不再是 2 处（VM :1397「没有目标日历」那一档 + :1449 openCalendarPicker）。" +
                "**多一处** = 冒出一条不经任何闸就能打开选择器的路，入口 3 的「开窗在闸里」就此失证：" +
                lineHints(code, pickerOpens),
            PICKER_OPEN_SITES,
            pickerOpens.size,
        )
        val pickerHosts = pickerOpens.map { precedingFunHead(code, it) }.sorted()
        assertEquals(
            "两枚开窗站点不再分别长在 `fun openCalendarPicker()`（界面唯一入口 :1625 在闸里）与 " +
                "`fun startCalendarSync()`（入口 1/2/3 全在闸里）体内 —— 宿主一换就得重数一次它的界面入口" +
                "过不过闸：\n  " + pickerHosts.joinToString("\n  "),
            listOf(OPEN_PICKER_HEAD, SYNC_ENTRY_HEAD).sorted(),
            pickerHosts,
        )
        assertEquals("靶子：界面上 openCalendarPicker 的调用点仍是闸里那一枚（:1625）：",
            1, occurrences(screen, BARE_PICKER_CALL))
        // ---- 尺子侧：闸读的是活的系统权限，不是那枚旗标 ----
        assertEquals(
            "靶子：`fun hasCalendarPermission(): Boolean = calendarSyncManager.hasPermission()` 还在 " +
                "（VM :1380，闸问的就是它）：",
            1,
            occurrences(code, HAS_PERMISSION_DECL),
        )
        assertEquals(
            "`ContextCompat.checkSelfPermission` 在 CalendarSyncManager 里不再是 2 处（:50 的 WRITE 与 :52 的 " +
                "READ，两枚都 granted 才算过）—— 少一枚就是「闸放行 ≠ 能写日历」，起手清旗标这句话的强度要改口径",
            PERMISSION_CHECKS,
            occurrences(manager, PERMISSION_CHECK),
        )
        assertEquals("靶子：`fun hasPermission(): Boolean =` 仍是唯一一枚定义（data/calendar/" +
            "CalendarSyncManager.kt:49）：", 1, occurrences(manager, MANAGER_HEAD))
        assertEquals(
            "VM 里对旗标的**读取**不再是 0 处（生命点 4 = 1 枚参数表声明 + 3 枚赋值，一枚读者都没有）。" +
                "**正数** = 旗标开始当第二把尺子用：要么 `hasCalendarPermission()` 的短路被搬进了 VM" +
                "（闸与旗标互兜，本卡的修法失去凭据），要么有分支开始按旧旗标决定行为。" +
                "**负数**只可能来自「赋值字面比 `permissionPermanentlyDenied` 这个符号名还多」—— 即参数表 " +
                "`:112` 那一枚声明的写法变了（改名 / 换 `val x: Boolean = false` 之外的形状），本条算式里那个 " +
                "`- 1` 要跟着重钉。⚠️ 别把这条当 T106 复发的探测器：把 `:1389` 的旗标删回去时符号名与赋值" +
                "各 -1、结果仍是 0（变异 M1 实测红了的是上面那枚 `FLAG_WRITE_SITES` 判据，不是这条）：" +
                occurrences(code, FLAG_NAME) + " - 1 - " + occurrences(code, FLAG_ASSIGNED) + " = ",
            VM_FLAG_READERS,
            occurrences(code, FLAG_NAME) - 1 - occurrences(code, FLAG_ASSIGNED),
        )
    }

    /**
     * ③ 的第二条链（**T110 新增**）：「移除已同步的日程」这条链自己不进 `startCalendarSync`，
     * 所以 T106 那一刀盖不住它 —— 它现在有自己的成对清（VM `:1481`），本判据钉这一枚的**形状**与**前提**。
     *
     * 病与 T106 同族：`:1486` 那句「已移除 N 个日程」把 `SettingsScreen.kt:1664`
     * `item(key = "status", visible = calendarSync.message != null)` 那格重新点亮，而上一次永久拒绝留下的
     * 旗标没人撤 ⇒ 完成句旁边继续挂着 `:1677` 那颗「去系统设置开启日历权限」。
     *
     * **取舍**（为什么是"起手成对清"而不是"再套一道权限闸"）：这条链**今天就已经在闸里** ——
     * 它唯一的界面触发点是 `:1654` `withCalendarPermission { viewModel.requestRemoveSyncedEvents() }`，
     * 而 `:331` 那句 `if (viewModel.hasCalendarPermission()) {` 的 true 分支直接 `action()`、**根本不启动
     * launcher**，所以 `:317` 那次 `onCalendarPermissionGranted()`（清旗标的现成入口）在已授权时永远走不到。
     * ⇒ "再套一道闸"这个方向在这枚病上是个空操作：它既不撤旗标，又把 :331 的短路（就是造成漏清的那件事）
     * 再犯一遍。清旗标因此只能落在链自己身上，落在 `removeSyncedEvents()` 起手。
     * 代价与诚实性：这条链自己不调权限申请 ⇒ 清旗标在语义上是"宣布此刻已授权"，这句由**入口在闸里**兜住
     * （开窗只有一枚写点 `requestRemoveSyncedEvents`，其唯一触发点过闸；已授权 ⇒ 系统实测的
     * `hasPermission()`（两枚 checkSelfPermission）为真，刚授权 ⇒ launcher 交回全授予）。
     * 剩下的那一格残态只能等真机、且方向是安全的：在确认框开着的那几秒里回系统设置把权限**关掉**再点「移除」，
     * 那一档旗标被清、句子落的是 `:1487` 那句「移除失败：日历写入异常，请检查权限后重试」——
     * 按钮少一颗，但话本身没念假（失败文案里就写着要检查权限），而下一次点同步会走闸重新立旗标。
     * 真·永久拒绝时这条链根本进不到那次 copy（`:1654` 那一档在拒绝时不落 `showRemoveConfirm = true`），
     * 所以"撤了不该撤的旗标"在可达路径上不成立。
     *
     * 两头都钉：**朝宽**——把 `:1481` 拆回 `it.copy(syncing = true)` ⇒ 本判据的原文靶子 1→0 红，
     * 同时 ③ 那两枚枚数判据（旗标赋值 4→3、句子清点 2→1）一起红；**朝紧**——给这颗按钮之外再添一枚
     * `viewModel.removeSyncedEvents()` 调用点（尤其是不在确认框里、不过 `:1654` 那道闸的一枚）⇒
     * 调用点 1→2 红、"落在 showRemoveConfirm 那层窗口里"那条红，届时要按新入口重推一遍权限前提。
     */
    @Test
    fun everyRouteIntoTheRemoveChainStandsInsideTheSameGateAndClearsTheFlagPaired() {
        val code = blankCommentsKeepingLiterals(readMainSource(SCHEDULE_VIEW_MODEL))
        val screen = blankCommentsKeepingLiterals(readMainSource(SETTINGS_SCREEN))
        // ---- 写侧：那条链起手那档必须成对清两枚 ----
        val entryShapes = indexOfAll(code, REMOVE_ENTRY_CLEAR_SHAPE)
        assertEquals(
            "`removeSyncedEvents` 起手那档的成对清原文（:1481）不再是恰好 1 处 —— **0 处**就是 T109 那枚" +
                "漏清在复发（「已移除 N 个日程」旁边挂着那颗按钮：句子由 :1486 重新点亮、旗标没人撤）；" +
                "**2 处**就是又有一条链用了同一枚形状，它的入口过不过闸得另外证：" + lineHints(code, entryShapes),
            1,
            entryShapes.size,
        )
        assertEquals(
            "那次成对清的宿主不再是 `fun removeSyncedEvents()`（现宿主：" +
                precedingFunHead(code, entryShapes.first()) + "）—— 抽进 helper 或搬去别的函数，" +
                "「这条链只在已授权时可达」这句话就要按新宿主重推",
            REMOVE_HEAD,
            precedingFunHead(code, entryShapes.first()),
        )
        val flagInEntry = code.indexOf(FLAG_ASSIGNED, entryShapes.first())
        val entryArgs = rebuildArguments(code, flagInEntry)
        assertTrue(
            ":1481 那次重建不再撤句子（实参表：" + entryArgs.trim() + "）—— 撤旗标不撤句子是 :1508 那一档的" +
                "形状（长在 launcher 全部授予那一档里，那条档上句子本来就该留），这条链落的是完成句，" +
                "两枚必须一起落地：",
            entryArgs.contains(MESSAGE_CLEARED),
        )
        assertTrue(
            ":1481 那次重建不再撤旗标（实参表：" + entryArgs.trim() + "）—— 本卡那枚病的正身：" +
                "上一次永久拒绝留下的旗标清不掉，等 :1486 那句完成句把 :1664 那格点亮，" +
                ":1677 那颗「去系统设置开启日历权限」就挂在完成文案旁边：",
            entryArgs.contains(FLAG_ASSIGNED),
        )
        assertEquals("靶子：那次成对清之后落下的完成句原文还在（:1486，它就是重新点亮那格的那一句）：",
            1, occurrences(code, REMOVE_DONE_MESSAGE_SHAPE))
        assertEquals(
            "这条链的失败文案原文不再是一枚 —— 「移除失败：日历写入异常，请检查权限后重试」（:1487）是" +
                "真·没权限时用户唯一看得见的提示（那颗按钮今天在这条链上本来就不该出现）：",
            1,
            occurrences(code, REMOVE_FAILED_MESSAGE_SHAPE),
        )
        // ---- 前提侧：进到那次 copy 的唯一一条路（开窗 1 枚写点 + 界面 1 枚调用点 + 那枚调用点在弹窗里）----
        val opens = indexOfAll(code, REMOVE_CONFIRM_OPENED)
        assertEquals(
            "`showRemoveConfirm = true` 的写点不再是 1 处（只有 :1471 requestRemoveSyncedEvents）—— " +
                "**多一处** = 又有一条路能打开那颗确认框，必须先证它同样过 :1654 那道闸：" + lineHints(code, opens),
            1,
            opens.size,
        )
        assertEquals(
            "那唯一一枚开窗站点的宿主不再是 `fun requestRemoveSyncedEvents()`（现宿主：" +
                precedingFunHead(code, opens.first()) + "）",
            REMOVE_REQUEST_HEAD,
            precedingFunHead(code, opens.first()),
        )
        val calls = indexOfAll(screen, REMOVE_CALL)
        assertEquals(
            "界面上 `viewModel.removeSyncedEvents()` 的调用点不再是 1 处（:1481 那次成对清唯一的进入路径）—— " +
                "**多一处** = 多一条不经确认框、因而也不经 :1654 那道闸的入口，清旗标那句话就此变成无凭据的" +
                "宣布：" + lineHints(screen, calls),
            1,
            calls.size,
        )
        val modal = screen.indexOf(REMOVE_MODAL_HEAD)
        check(modal >= 0) { "靶子：找不到 `$REMOVE_MODAL_HEAD`（:1941）—— 确认框那层的驱动方式换过了" }
        val nextModal = screen.indexOf("ModalTransition(", modal + REMOVE_MODAL_HEAD.length)
        check(nextModal > modal) { "靶子：移除确认框之后找不到下一层 ModalTransition 边界，窗口切不出来" }
        assertTrue(
            "那颗「移除」按钮跑出了 showRemoveConfirm 驱动的那一层 —— 于是它能在这条链的闸之外被点到，" +
                "起手那次清旗标就不再必然发生在已授权的那一刻",
            calls.first() in modal until nextModal,
        )
        assertEquals("靶子：确认框那一层的原文还在（它由 VM :1471 那枚唯一的写点驱动）：",
            1, occurrences(screen, REMOVE_MODAL_HEAD))
        assertEquals(
            "触发开窗那枚调用的界面入口不再是 1 处（:1654）—— 它就是这条链的权限前提：" +
                lineHints(screen, indexOfAll(screen, REMOVE_REQUEST_CALL)),
            1,
            occurrences(screen, REMOVE_REQUEST_CALL),
        )
        // ---- 那道闸本身：它已在 ④ 里被逐条钉过，这里只复述两枚承重事实 ----
        assertEquals("靶子：:1654 那一整句（withCalendarPermission 包着 requestRemoveSyncedEvents）还在：",
            1, occurrences(screen, GATED_REMOVE))
        assertEquals(
            "`if (viewModel.hasCalendarPermission()) {`（:331）不再是 1 处 —— 本判据那句「再套一道闸是空操作」" +
                "吃的就是「这条链已经在唯一那道闸里、而闸的 true 分支直接 action() 不启动 launcher」：",
            PERMISSION_GATE_SITES,
            occurrences(screen, PERMISSION_GATE),
        )
        assertEquals(
            "`calendarPermissionLauncher.launch(` 不再是 1 处（:335，闸的 else 分支）—— 多一处就有人绕过闸" +
                "申请权限，那 :317 那次单清重新变成可达，起手成对清的覆盖面要重算",
            1,
            occurrences(screen, LAUNCH_CALL),
        )
        assertTrue(
            "移除那一行跑到了闸定义之前 —— 「进到这条链时权限必然已到手」这句前提的载体换了位置，" +
                "起手那次清旗标就可能是假话",
            screen.indexOf(GATED_REMOVE) > screen.indexOf(WITH_PERMISSION_HEAD),
        )
    }

    /**
     * ③ 的第三条链（**T116 收 §8 表 #1**）：`confirmCalendarSync` 起手那一枚
     * `_calendarSync.update { it.copy(syncing = true) }` —— **一枚对岸都不收**。
     *
     * 本判据钉的是「全靠别人那一行」这件事本身，不是一枚当下的症状（这一格今天念不出错，
     * §8.4-A 因此把它判成「结构不可能·可挪走」）。现在不红的凭据是**三枚别处的行**，
     * 每条各被本判据的一格钉住，被改掉都会先红在这里、而不是先在用户屏幕上红：
     * 1. `diff` 全仓只有一枚生产者（`diff = computed.first`），它长在 `startCalendarSync` 体内、
     *    且**在同一函数体内位于 `:1389` 那次五枚一起落地的成对清之后** ⇒ 「手里有一份 diff」今天
     *    蕴含「起手刚把句子与旗标收过一遍」。谁把那次成对清挪到 `computeDiff` 之后、或挪出那枚函数体，
     *    枚数不变而这一格先红（T115②′ 记下的那个洞就在这儿）。
     * 2. 那颗「同步」的 onClick 长在 `ModalTransition(payload = calendarSync.diff)` 那一层之内，
     *    弹窗独占输入 ⇒ 没人能在 diff 还挂着时把 `message`/旗标写进来（候选只有
     *    `onCalendarPermissionDenied` 那一枚立旗站点，本判据顺手钉它**不在** confirm 体内）。
     * 3. 全仓 `app/src/main/java` 里提到 `confirmCalendarSync` 的文件恰好是那两枚（定义 + 那一句 onClick）
     *    ⇒ 没有第二条入口。这一格按**树扫**取，上面两枚都抓不到「第三枚文件里冒出一枚调用点」的形状。
     *
     * **为什么"照 `:1389` 那枚仪式补收"这一支本卡不走**（这才是它值得钉的原因，不是随手写的 1==1）：
     * 起手清旗标在语义上等于**宣布此刻已授权**（T110 那笔账），`:1389` 与 `:1481` 都拿「入口在 `:331`
     * 那道闸里」当凭据；confirm 的凭据只是**传递性**的（它那扇窗由 startCalendarSync 开），中间还隔着
     * 一次 `calendarSyncManager.apply()` —— 权限在弹窗开着的那几秒里被系统收回时，收尾落的正是
     * `:1432`/`:1437` 那句「…请重试或检查日历权限」，那一刻把旗标清掉就等于**在失败文案旁边撤掉唯一那颗出路按钮**。
     * 另一枚字段 `message` 压根不需要收：收尾 `:1428-1440` 是整枚重建、每一档都无条件写新句子。
     * ⇒ 这一族正确的落点是「生产 diff 之前」那一枚（已经收了），不是「应用 diff 之前」。
     *
     * 两头都钉：
     * - **朝宽**（把责任推给别人）：成对清挪到生产之后 / 挪出函数体 → 位置判据红；onClick 搬出弹窗
     *   （改常驻卡，本仓另一种常见写法）→ 窗口包含判据红；第三枚文件里添调用点 → 树扫红；
     * - **朝紧**（把它改成收）：`:1426` 的实参表不再是**逐字** `syncing = true` → 本判据红。那一支不是
     *   禁止，是**收费**：补收之前得先回答上面那句「清旗标凭什么算已授权」，并把 §8.4-A 那一格一起重钉。
     * ⚠️ 与 T115②′ 同一课：本判据**逐处 + 按位置 + 逐字**取，一枚都不靠"数出现次数"——
     *   「枚数对但落点错」正是这一族最容易蒙过去的形状。
     */
    @Test
    fun theConfirmChainClearsNoPairItselfAndThreeOtherLinesHoldItShut() {
        val code = blankCommentsKeepingLiterals(readMainSource(SCHEDULE_VIEW_MODEL))
        val screen = blankCommentsKeepingLiterals(readMainSource(SETTINGS_SCREEN))
        // ---- 这条链的骨架：函数头 1 处、体内三枚写点、那道早返回在最前 ----
        val heads = indexOfAll(code, CONFIRM_HEAD)
        assertEquals("靶子：`$CONFIRM_HEAD` 不再是恰好 1 处：" + lineHints(code, heads), 1, heads.size)
        val head = heads.first()
        val bodyEnd = code.indexOf(MEMBER_FUN_SEP, head + CONFIRM_HEAD.length)
        check(bodyEnd > head) {
            "靶子：`confirmCalendarSync` 之后找不到下一枚成员函数头 —— 这颗函数体切不出来，宿主判据要重写法"
        }
        val updates = indexOfAll(code, UPDATE_SITE).filter { it in head until bodyEnd }
        assertEquals(
            "`confirmCalendarSync` 体内的 `_calendarSync.update {` 写点不再是 3 处（体外作废 diff 一枚 / " +
                "起手立旗一枚 / 收尾落旗一枚）。这一格是**按宿主切块**取的：「枚数还是 3、但其中一枚被搬进 " +
                "helper 或搬去别的函数」同样落不进这个区间，也就同样红：" + lineHints(code, updates),
            3,
            updates.size,
        )
        for ((index, at) in updates.withIndex()) {
            assertEquals(
                "这条链第 ${index + 1} 枚写点的宿主不再是 `fun confirmCalendarSync() {`（现宿主：" +
                    precedingFunHead(code, at) + "）—— 抽 helper 或搬家，「它什么时候真的会跑」都要重推",
                CONFIRM_HEAD,
                precedingFunHead(code, at),
            )
        }
        val guard = code.indexOf(CONFIRM_EARLY_RETURN)
        val launchAt = code.indexOf(SCOPE_LAUNCH, head)
        check(guard in head until bodyEnd && launchAt in head until bodyEnd) {
            "靶子：那道 `?: return` 早返回或那一次 `viewModelScope.launch {` 已经不在 confirmCalendarSync 体内 " +
                "—— 这条链的骨架换了，本判据的宇宙要跟着挪"
        }
        assertEquals(
            "靶子：`val pending = _calendarSync.value.diff ?: return` 全仓不再恰好 1 枚 —— 它就是 §8.4-A " +
                "那句「进不到这条链除非 diff 非空」的载体：",
            1,
            occurrences(code, CONFIRM_EARLY_RETURN),
        )
        assertTrue(
            "那道早返回不再是这条链的第一件事（" + lineAt(code, guard) + " 已经不在第一枚写点之前）—— " +
                "「进到这条链时 diff 必然非空」这句前提换了位置，残值判据要从头重推",
            guard < updates.first(),
        )
        assertTrue(
            "起手立旗不再是「体外那次 diff 作废之后、协程之内」：体外" + lineAt(code, updates.first()) +
                " 与 launch" + lineAt(code, launchAt) + " 与起手" + lineAt(code, updates[1]) +
                " 的次序变了 —— 这个次序就是 §8.2 表 #1 那一格读的形状（体内第一行才立旗），" +
                "换了就要重判「残值窗口有多长」那一格",
            updates[0] < launchAt && launchAt < updates[1],
        )
        // ---- 三枚写点的实参表：逐处取、逐字比 ----
        val args = updates.mapIndexed { index, at ->
            updateArguments(code, at, if (index + 1 < updates.size) updates[index + 1] else bodyEnd)
        }
        assertTrue(
            "体外那一枚写点不再成对作废 diff（实参表：" + args[0].trim() + "）—— 成对这一半归 " +
                "`CalendarSyncDiffClearPairingGuardTest` 第一枚判据管，本判据只钉它**长在协程之外**：" +
                "diff 是在立旗之前就被作废的，不是在飞行途中：",
            args[0].contains(DIFF_CLEARED) && args[0].contains(SKIPPED_CLEARED),
        )
        assertFalse(
            "体外那一枚写点开始立旗（实参表：" + args[0].trim() + "）—— 立旗与协程边界脱钩，" +
                "上面那条「体外一枚、体内两枚」的位置判据与 §8.2 表 #1 那一格都要重钉：",
            args[0].contains("syncing ="),
        )
        assertEquals(
            "`:1426` 起手那枚 copy 的实参表不再是**逐字** `$CONFIRM_ENTRY_ARGS`（现在是：" + args[1].trim() +
                "）—— 这一格钉的就是本卡 #1 的判词「起手一枚对岸都不收」。**少一枚**（连 `syncing` 都不立了）" +
                "= 那条链在飞的时候 `:1643` 那颗 `enabled = !calendarSync.syncing` 没人管；**多一枚**" +
                "（补收 `message`/旗标，或连 `diff`/`skippedOccurrences` 一起收）= 本卡判「不动 main」那一支被反着做，" +
                "先回答 KDoc 第二段那句「清旗标凭什么算已授权」，再把 §8.4-A 那一格与 :1389/:1481 两枚仪式一起重钉" +
                "（补收 diff 那一头还会把本文件第一枚判据的 3 处清点顶成 4 处）：",
            CONFIRM_ENTRY_ARGS,
            args[1].trim(),
        )
        assertTrue(
            "收尾那枚不再落旗（实参表：" + args[2].trim() + "）—— 这条链自己就是 `syncing` 的读者 " +
                "(`SettingsScreen.kt:1643` `enabled = !calendarSync.syncing`) 的载体，旗落不下来那两颗按钮就永久按不动：",
            args[2].contains("syncing = false"),
        )
        assertTrue(
            "收尾那枚不再**无条件**写句子（`message = when {` 不见了，实参表：" + args[2].trim() +
                "）—— 这就是 confirm 起手不必收 `message` 的全部理由：每一档都落一句新话，旧句子被覆盖。" +
                "它一旦改成有条件写（比如成功那档不写句子），旧句子就能活过这条链，本判据的方向要整个反过来：",
            args[2].contains(CONFIRM_TAIL_MESSAGE_SHAPE),
        )
        assertFalse(
            "收尾那枚开始撤旗标（实参表：" + args[2].trim() + "）—— 那是把责任从「生产 diff 之前」搬来" +
                "「应用 diff 之后」，形状换了：`:1389` 那次成对清就从承重件降级成多余的一刀，" +
                "③那一族的「成对写 1 / 成对清 2 / 单清 1」三枚数与 §8.4-A 都要按两枚落点重钉：",
            args[2].contains(FLAG_ASSIGNED),
        )
        // ---- 承重前提 1：凡有 diff，必先过 :1389 那次成对清（位置判据，不是枚数判据）----
        val produced = indexOfAll(code, DIFF_PRODUCED)
        assertEquals(
            "`diff` 的生产者（`diff = computed.first`）不再是全仓唯一一枚（§8.5 第 13 条那笔账的静态版）—— " +
                "**多一枚** = 多一条不经过 :1389 就能拿到 diff 的路，本卡 #1 当场从「结构不可能」搬进「真漏清」：" +
                lineHints(code, produced),
            1,
            produced.size,
        )
        val syncEntry = code.indexOf(SYNC_ENTRY_HEAD)
        check(syncEntry >= 0) { "靶子：找不到 `$SYNC_ENTRY_HEAD` —— 同步入口改过名，③那一族与本判据都要重钉" }
        val syncEnd = code.indexOf(MEMBER_FUN_SEP, syncEntry + SYNC_ENTRY_HEAD.length)
        check(syncEnd > syncEntry) { "靶子：startCalendarSync 之后找不到下一枚成员函数头，那枚函数体切不出来" }
        assertTrue(
            "diff 的生产者跑出了 `startCalendarSync` 体内（现宿主：" + precedingFunHead(code, produced.first()) +
                "）—— 「唯一生产者被那枚成对清罩着」这句话没了载体，本判据第一格要按新宿主重写：" +
                lineAt(code, produced.first()),
            produced.first() in syncEntry until syncEnd,
        )
        val pairedClears = indexOfAll(code, SYNC_ENTRY_PAIRED_COPY)
        assertEquals(
            "靶子：`:1389` 那次「syncing + message + 旗标 + diff + skippedOccurrences 五枚一起落地」的成对清原文 " +
                "不再是恰好 1 处：" + lineHints(code, pairedClears),
            1,
            pairedClears.size,
        )
        assertTrue(
            "那次成对清也跑出了 `startCalendarSync` 体内（现宿主：" +
                precedingFunHead(code, pairedClears.first()) + "）：" + lineAt(code, pairedClears.first()),
            pairedClears.first() in syncEntry until syncEnd,
        )
        assertTrue(
            "**朝宽那一格**：成对清还在、枚数也对，却落到了 diff 生产**之后**（" +
                lineAt(code, pairedClears.first()) + " vs " + lineAt(code, produced.first()) +
                "）—— 「手里有一份 diff」就此不再蕴含「起手刚收过句子与旗标」，而 confirm 起手不收" +
                "全部凭据就是这一条。这正是 T115②′ 记下的「枚数对但落点错」那一型：",
            pairedClears.first() < produced.first(),
        )
        // ---- 承重前提 2：那扇弹窗（界面上唯一一条进这条链的路在窗口之内）----
        val diffModal = indexOfAll(screen, DIFF_MODAL_HEAD)
        assertEquals(
            "靶子：`$DIFF_MODAL_HEAD` 不再是恰好 1 处 —— diff 的渲染方式换了载体（改成常驻卡就是 §8.4-A 说的" +
                "「别处一行」，那一格本判据要反过来重判）：" + lineHints(screen, diffModal),
            1,
            diffModal.size,
        )
        val modalAt = diffModal.first()
        val nextModal = screen.indexOf(MODAL_SEP, modalAt + DIFF_MODAL_HEAD.length)
        check(nextModal > modalAt) { "靶子：diff 弹窗之后找不到下一层 ModalTransition 边界，窗口切不出来" }
        val calls = indexOfAll(screen, CONFIRM_CALL)
        assertEquals(
            "界面上 `viewModel.confirmCalendarSync()` 的调用点不再是 1 处（现在只有弹窗里那颗「同步」）：" +
                lineHints(screen, calls),
            1,
            calls.size,
        )
        assertTrue(
            "那颗「同步」跑出了 diff 弹窗那一层（onClick" + lineAt(screen, calls.first()) + " 不在 " +
                "L${screen.substring(0, modalAt).count { it == '\n' } + 1} 到 " +
                "L${screen.substring(0, nextModal).count { it == '\n' } + 1} 之间）—— 弹窗不再独占输入，" +
                "于是能有一句旧 `message` 或一枚旧旗标在 diff 挂着时被写进来、而 confirm 起手不收：" +
                "§8.4-A 那句「靠 Compose 对话框的输入独占」当场作废，这一格搬进「真漏清」：",
            calls.first() in modalAt until nextModal,
        )
        // ---- 承重前提 3：没有第二条路（全仓 main 树扫，不止本文件点名的那两枚文件）----
        assertEquals(
            "全仓 `app/src/main/java` 里提到 `confirmCalendarSync` 的文件不再恰好是那两枚 —— " +
                "第三枚文件里冒出来的调用点是上面两格**都抓不到**的形状（它既不在弹窗里、也不经过 :331 那道闸）：" +
                "「进到这条链时 diff 必然非空、且起手刚成对清过」这句前提要按新入口重推",
            listOf(SCHEDULE_VIEW_MODEL, SETTINGS_SCREEN),
            mainFilesMentioning(CONFIRM_REF),
        )
        assertEquals("靶子：VM 里 `confirmCalendarSync` 这个符号不再恰好 1 枚（定义那一行）：", 1, occurrences(code, CONFIRM_REF))
        assertEquals("靶子：SettingsScreen 里它不再恰好 1 枚（那一句 onClick）：", 1, occurrences(screen, CONFIRM_REF))
        // ---- 残值的唯一生产者：它不许搬进 confirm 体内 ----
        val raised = indexOfAll(code, FLAG_RAISED)
        assertEquals(
            "靶子：把 `permissionPermanentlyDenied` 立成 true 的那枚站点（:1497 " +
                "`permissionPermanentlyDenied = !canAskAgain,`）不再恰好 1 处：" + lineHints(code, raised),
            1,
            raised.size,
        )
        assertFalse(
            "那枚立旗站点跑进了 `confirmCalendarSync` 体内（" + lineAt(code, raised.first()) + "）—— " +
                "那么「起手不收旗标」立刻从「全靠别人挡着」变成「自己把自己写脏」，本判据的方向要整个反过来重钉：" +
                "这一格不是禁止，但它一改，§8.4-A 那一格读的就不是同一枚病了：",
            raised.first() in head until bodyEnd,
        )
    }

    // ---------------- ⑥ showPrivacyDialog / privacyConsentAt ----------------

    /**
     * 写/清侧：三处关窗里**恰好一处**连号归零同意时间（:1848-1849），另外两处只关窗
     * （:1817 onDismissRequest、:1853「知道了」）。
     *
     * 两头都钉：`showPrivacyDialog = false` 从 3 变 4 = 又添一处只关窗、留下旧同意时间的入口，
     * 那几帧收场动画要不要翻面得按新读者重算；变 2 = 有人合并了两处按钮，本守卫的窗口边界跟着挪。
     * `privacyConsentAt = 0L` 从 1 变多 = 「撤回同意」不止一处，成对写的那一批要重数。
     */
    @Test
    fun thePrivacyDialogClosesThreeTimesButZeroesTheConsentTimestampOnce() {
        val screen = blankCommentsKeepingLiterals(readMainSource(SETTINGS_SCREEN))
        assertEquals(
            "`showPrivacyDialog` 在这颗文件里的生命点不再是 6 处（1 声明 + 1 开 + 3 关 + 1 读者）。" +
                "**多一处读者** = 给这枚旗标添了第二张嘴，「关窗没归零同意时间」会不会被念出来要重算；" +
                "**少一处** = 有半条链换了载体（改成 state class 或抽成函数），下面的枚数判据得重钉",
            DIALOG_LIFE_POINTS,
            occurrences(screen, "showPrivacyDialog"),
        )
        assertEquals(
            "`privacyConsentAt` 的生命点不再是 4 处（声明那一行本身占 2 枚：`var privacyConsentAt` 与 " +
                "`FirstRun.privacyConsentAt(context)` 那枚同名函数 + :1814 的读者 + :1848 的归零）。" +
                "别把这一枚数当成「三处」—— 声明行里那第二枚是 core/FirstRun.kt:29 的函数，不是这枚 var",
            CONSENT_LIFE_POINTS,
            occurrences(screen, "privacyConsentAt"),
        )
        val closes = indexOfAll(screen, DIALOG_CLOSED)
        assertEquals(
            "关窗（`showPrivacyDialog = false`）的站点不再是 3 处：:1817 onDismissRequest、:1849 撤回同意、" +
                ":1853 知道了。**多一处** = 又添一条只关窗不清场的路径，收场那几帧读到的就是上一次翻面的字段；" +
                "**少一处** = 两处并成了一处，那本守卫的分头清账要重数：" + lineHints(screen, closes),
            DIALOG_CLOSE_SITES,
            closes.size,
        )
        assertEquals(
            "开框（`showPrivacyDialog = true`）不再是 1 处（:1790 那一格按钮）—— 它决定 payload 的" +
                "「开」这一侧从哪来，多一处就是多一条能带着旧同意时间进弹窗的路",
            1,
            occurrences(screen, DIALOG_OPENED),
        )
        assertEquals(
            "`privacyConsentAt = 0L`（归零同意时间）不再是 1 处（:1848）",
            CONSENT_ZERO_SITES,
            occurrences(screen, CONSENT_ZEROED),
        )
        val paired = closes.filter { previousCodeLine(screen, it) == CONSENT_ZEROED }
        assertEquals(
            "「连号一起写」的关窗站点不再是 1 处 —— 现在只有 :1848/:1849 那两行把归零同意时间与关窗排在一起。" +
                "**多一处** = 有人把分头清改成了成对清（这一族的正解之一，但 §6.8⑥ 那格与本守卫都要跟着重钉）；" +
                "**少一处** = 撤回同意那一档不再归零，「已同意」会一直留在偏好里：" + lineHints(screen, paired),
            PAIRED_PRIVACY_SITES,
            paired.size,
        )
        val split = closes.filter { previousCodeLine(screen, it) != CONSENT_ZEROED }
        assertEquals(
            "只关窗、不归零同意时间的站点不再是 2 处（:1817、:1853）。这两处就是 §6.2 #14 那格说的" +
                "「三组全是连号两行」的反面：**多一处** 意味着新增的关闭路径也得被 :1814 那行 payload 罩住；" +
                "**少一处** 意味着有人已经把它们改成成对清了（改完请连 §6.8⑥ 一起收账）：" + lineHints(screen, split),
            SPLIT_PRIVACY_SITES,
            split.size,
        )
        // 靶子：三行原文 + 两行声明
        assertEquals("靶子：:1817 那行 onDismissRequest 的原文还在：", 1, occurrences(screen, DISMISS_SHAPE))
        assertEquals("靶子：:1853 那颗「知道了」按钮的原文还在：", 1, occurrences(screen, KNOWN_BUTTON_SHAPE))
        assertEquals("靶子：:1847 撤回偏好那次调用还在（它上面两行就是连号那两行）：", 1, occurrences(screen, REVOKE_CALL))
        assertEquals("靶子：两枚 var 的声明原文都还在（:209 / :210）：", 1, occurrences(screen, CONSENT_DECL))
        assertEquals("靶子：showPrivacyDialog 的声明原文还在（mutableStateOf(false)）：", 1, occurrences(screen, DIALOG_DECL))
    }

    /**
     * 形状侧：把 :1814 那一行钉成判据 —— 这一对「不漏」**完全**靠这一行的写法维持。
     *
     * `ModalTransition(payload = if (showPrivacyDialog) privacyConsentAt else null) { consentAt, modal ->`
     * 原文一字不改要还在（改一个字符都红），而且弹窗正文里的两枚判据必须读 payload 交回来的 `consentAt`、
     * 不读那枚活字段 —— 这样「分头清」留下的旧同意时间在收场那几帧里才念不出来。
     *
     * 两头都钉：改回 `ModalTransition(open = showPrivacyDialog)` 那一格常用写法 ⇒ payload 那行 1→0 红、
     * `ModalTransition(open = showPrivacyDialog` 0→1 红、`ModalTransition(open = ` 3→4 红；
     * 反过来谁删掉这层弹窗或把 `consentAt > 0L` 那两格判据合并 ⇒ 2→1 红。
     */
    @Test
    fun theConsentTimestampHasExactlyOneReaderAndItIsThePayloadGateItself() {
        val screen = blankCommentsKeepingLiterals(readMainSource(SETTINGS_SCREEN))
        assertEquals(
            "靶子：:1814 那一行 payload 的原文不在了或改过写法了 —— 这一对不漏全靠它，" +
                "换写法之前先回来看 §6.2 #14 与 §6.8⑥ 那两格（:1812-1813 的注释就是这件事的说明书）：",
            1,
            occurrences(screen, PAYLOAD_GATE),
        )
        assertEquals(
            "`ModalTransition(` 在这颗文件里不再是 6 层 —— 这一枚是「弹窗总数」的清单，" +
                "上面那条 payload=1 只有配着它才不是一句空话（读空文件两边都给 0）",
            MODAL_LAYERS,
            occurrences(screen, "ModalTransition("),
        )
        assertEquals(
            "`ModalTransition(open = ` 的那一层都不许是 privacy 那一层（现测 3 层用 open 驱动：:1859 选日历、" +
                ":1941 移除确认、:2009 空课表恢复）。**多一处** = privacy 那一层被换成了 open 写法 —— " +
                "收场那几帧当场翻成「未同意」，这正是 :1812-1813 两行注释警告的那件事",
            OPEN_LAYERS,
            occurrences(screen, "ModalTransition(open = "),
        )
        assertEquals(
            "本卡点名的那枚改法今天不存在：`ModalTransition(open = showPrivacyDialog` 应当是 0 处 —— " +
                "它与上面那条 3 是一枚判据的两头（换成 open 写法时两枚一起红，谁也别想只撞上一枚）",
            0,
            occurrences(screen, OPEN_PRIVACY_SHAPE),
        )
        val gate = screen.indexOf(PAYLOAD_GATE)
        check(gate >= 0) { "找不到 $PAYLOAD_GATE：privacy 弹窗的驱动方式换过了，本守卫要跟着改" }
        val next = screen.indexOf("ModalTransition(", gate + PAYLOAD_GATE.length)
        check(next > gate) { "privacy 弹窗之后找不到下一层 ModalTransition 边界，窗口切不出来" }
        val windowLength = next - gate
        check(windowLength in PRIVACY_WINDOW_MIN..PRIVACY_WINDOW_MAX) {
            "privacy 弹窗那一段的长度越界（$windowLength 不在 $PRIVACY_WINDOW_MIN..$PRIVACY_WINDOW_MAX，现测 2008）：" +
                "窗口不是原来那块了，得重判"
        }
        val body = screen.substring(screen.indexOf('\n', gate) + 1, next)
        assertEquals(
            "`privacyConsentAt` 在弹窗体内出现的次数不是 1 —— 那唯一一处必须是 :1848 的**写**" +
                "（下面那格会把它钉住）。若有人给正文添了第二枚读者（例如常驻一行「上次同意时间」），" +
                "分头清留下的旧值就不再被 payload 挡住，:1817/:1853 两处单清当场念假话",
            1,
            occurrences(body, "privacyConsentAt"),
        )
        assertEquals(
            "弹窗体内那一处 privacyConsentAt 不再是 `privacyConsentAt = 0L`（那次写）—— " +
                "它变成读取的这一刻起，这一对的守卫要按「读者在不在闸门里」重判",
            1,
            occurrences(body, CONSENT_ZEROED),
        )
        assertEquals(
            "正文里读 payload 交回的那枚 `consentAt > 0L` 判据不再是 2 处（:1830 那行文案、:1844 那颗" +
                "「撤回同意」按钮的可见性）。**多一处** = 又有文案要靠 payload 兜住收场帧；" +
                "**少一处** = 有人把两格判据合并或改成读活字段，那 payload 这层写法就白搭了",
            CONSENT_PARAM_READS,
            occurrences(screen, "consentAt > 0L"),
        )
    }

    // ---- 源码核对小工具（抄 CalendarSyncDiffClearPairingGuardTest / CourseEditorSaveErrorClearPairingGuardTest）----

    /** 每次命中所在行的行号与原文：失败消息要能把人带到那一处，只报个数等于没有守卫 */
    private fun lineHints(source: String, positions: List<Int>): String {
        if (positions.isEmpty()) return ""
        return "\n" + positions.joinToString("") { at ->
            "  L${source.substring(0, at).count { it == '\n' } + 1}: " + lineText(source, at) + "\n"
        }
    }

    /** 单行版：断言只点名一处站点时用，报错里直接给「L 几 + 原文」 */
    private fun lineAt(source: String, hit: Int): String =
        "  L${source.substring(0, hit).count { it == '\n' } + 1}: " + lineText(source, hit)

    private fun lineText(source: String, hit: Int): String {
        val start = source.lastIndexOf('\n', hit).let { if (it < 0) 0 else it + 1 }
        val end = source.indexOf('\n', hit).let { if (it < 0) source.length else it }
        return source.substring(start, end).trim()
    }

    /** [hit] 之前**最近的非空一行**（去空白）：用来判「连号一起写」那两行是不是真的连着 */
    private fun previousCodeLine(source: String, hit: Int): String {
        val lineStart = source.lastIndexOf('\n', hit).let { if (it < 0) 0 else it + 1 }
        var from = lineStart
        while (from > 1) {
            val previousEnd = from - 1
            val previousStart = source.lastIndexOf('\n', previousEnd - 1).let { if (it < 0) 0 else it + 1 }
            val text = source.substring(previousStart, previousEnd).trim()
            if (text.isNotEmpty()) return text
            from = previousStart
        }
        return ""
    }

    /** 包围 [hit] 的那次「整枚重建」的位置：nearest of `copy(` / `CalendarSyncUiState(`（往前找，取更近的） */
    private fun rebuildSite(source: String, hit: Int): Int {
        val copy = source.lastIndexOf("copy(", hit)
        val ctor = source.lastIndexOf(REBUILD_CTOR, hit)
        val open = maxOf(copy, ctor)
        check(open >= 0) {
            "那一处往前找不到包围它的 copy( / CalendarSyncUiState(：赋值换了载体（抽成函数？直接赋值？），" +
                "本守卫要跟着改"
        }
        return open
    }

    /**
     * 从 [hit] 往前找包围它的那次重建，再按括号配平（跳过字符串字面量）取出实参表。
     *
     * 不用"同一行"当判据：本仓这种写法换行很常见（:1496-1503 那次成对写就折了 8 行）。
     * 配平跑偏就抛 —— 静默跳过等于没有守卫。
     */
    private fun rebuildArguments(source: String, hit: Int): String {
        val open = rebuildSite(source, hit)
        val tagLength = if (source.startsWith(REBUILD_CTOR, open)) REBUILD_CTOR.length else "copy(".length
        var depth = 0
        var index = open + tagLength - 1
        var inString = false
        var escaped = false
        while (index < source.length) {
            val c = source[index]
            when {
                inString -> when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }

                c == '"' -> inString = true
                c == '(' -> depth++
                c == ')' -> {
                    depth--
                    if (depth == 0) {
                        val args = source.substring(open + tagLength, index)
                        check(hit in (open + tagLength)..index) { "取出的实参表不含那一处赋值：配平跑偏了" }
                        return args
                    }
                }
            }
            index++
        }
        throw IllegalStateException("重建的括号没配平：那一处赋值切不出实参表")
    }

    /**
     * 那一处 `_calendarSync.update {` **之内**那次 `copy(` 的实参表。
     *
     * [limit] 是下一枚写点（或函数体末尾）：`copy(` 必须落在 `[updateAt, limit)` 之内才认。
     * 这一条是"逐处"两字的落实 —— 拿"同一行"或"全局搜一遍"当判据都会把隔壁那枚写点的实参表
     * 误交到手上来（T116 收 §8 表 #1 时撞到的形状：三枚写点里两枚的实参表都含 `syncing =`）。
     */
    private fun updateArguments(source: String, updateAt: Int, limit: Int): String {
        val open = source.indexOf("copy(", updateAt)
        check(open in updateAt until limit) {
            "那一处 " + lineAt(source, updateAt) + " 到下一枚写点之间没找到 copy(（命中的 copy 在 " +
                (if (open < 0) "文件末尾之外" else "L${source.substring(0, open).count { it == '\n' } + 1}") +
                "）—— 写法换过了（直接赋值？抽成函数？中间塞了第二枚 copy？），本守卫要跟着改"
        }
        return rebuildArguments(source, open + "copy(".length)
    }

    /**
     * [hit] 处**由外到内**每一层花括号块的"头"：那层 `{` 前面、到上一个换行 / `;` / 花括号为止的文本。
     *
     * 用来判"那行 summary 有没有被 `targetId` 套住"（①的读侧）。与
     * CourseEditorSaveErrorClearPairingGuardTest 同名工具同一把刀：只认块头、不认整段，
     * 是为了不把同一层里别的语句误读成闸门。
     */
    private fun enclosingBlockHeads(source: String, hit: Int): List<String> {
        check(hit in source.indices) { "命中位置越界：$hit / ${source.length}" }
        check(source[hit] != '"') { "那一处落在字符串字面量里，切不出块头" }
        val opens = mutableListOf<Int>()
        var index = 0
        var inString = false
        var escaped = false
        while (index < hit) {
            val c = source[index]
            when {
                inString -> when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }

                c == '"' -> inString = true
                c == '{' -> opens += index
                c == '}' -> if (opens.isNotEmpty()) opens.removeAt(opens.size - 1)
            }
            index++
        }
        return opens.map { headBefore(source, it) }
    }

    /** `{` 前那一段块头：从最近的换行 / `;` / `}` / `{` 之后起算，压掉空白 */
    private fun headBefore(source: String, brace: Int): String {
        var from = brace
        while (from > 0) {
            val c = source[from - 1]
            if (c == '\n' || c == ';' || c == '{' || c == '}') break
            from--
        }
        return source.substring(from, brace).replace(whitespace, " ").trim()
    }

    private val whitespace = Regex("\\s+")

    /**
     * [hit] 之前**最近的那一枚成员函数头**整行（去掉首尾空白）。
     *
     * 用来判「这一处调用/赋值长在谁体内」—— 本仓的类体成员一律缩进 4 空格，所以
     * `\n    fun ` 是一个足够硬的分隔符。与 [enclosingBlockHeads] 那把刀互补：前者认块头、
     * 这一把认宿主。宿主一换（把调用抽进 helper、或搬去别的函数）它就当场给出**实际宿主**当证据。
     */
    private fun precedingFunHead(source: String, hit: Int): String {
        check(hit in source.indices) { "命中位置越界：$hit / ${source.length}" }
        val cut = source.lastIndexOf("\n    fun ", hit)
        check(cut >= 0) { "那一处之前找不到 `\n    fun ` —— 它已经不在任何成员函数体内了，宿主判据要重写法" }
        val from = cut + 1
        val to = source.indexOf('\n', from).let { if (it < 0) source.length else it }
        return source.substring(from, to).trim()
    }

    private fun indexOfAll(haystack: String, needle: String): List<Int> {
        val hits = mutableListOf<Int>()
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return hits
            hits += at
            from = at + needle.length
        }
    }

    private fun occurrences(haystack: String, needle: String): Int = indexOfAll(haystack, needle).size

    private fun readMainSource(relativeFromMainJava: String): String {
        val file = File(findMainJavaDir(), relativeFromMainJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /**
     * 全仓 `app/src/main/java` 里（抹注释后）**仍然提到** [needle] 的文件，相对路径、正斜杠、已排序。
     *
     * 只在被点名的那两枚文件里数调用点，漏的是"第三枚文件里新长出来的一枚"—— 那种形状既不在
     * 现有弹窗窗口里、也不在 `withCalendarPermission` 那道闸里，而 §8.4-A/B 那两格读的都是
     * 「界面上唯一那一条路」。与 `ClassProgressRescheduleWiringTest` / `SemanticGlassPlateTest`
     * 那几枚树扫判据同一把刀（`.kt` 全集 + 逐文件读文本；本仓 202 枚，成本可忽略）。
     */
    private fun mainFilesMentioning(needle: String): List<String> {
        val root = findMainJavaDir()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${root.path} 下一个 .kt 都没有，扫描路径不对", files.isNotEmpty())
        return files.filter { needle in blankCommentsKeepingLiterals(it.readText()) }
            .map { it.relativeTo(root).path.replace('\\', '/') }
            .sorted()
    }

    private fun findMainJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { File(dir, it) }
                .firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }

    /**
     * **只抹注释、保留字符串字面量内容**，长度与换行位置不变（行号因此仍然可信）。
     *
     * 整段抄两枚先例的同名工具，理由在本卡这三对上一枚都不能少：
     * - 不认字面量的行扫描会被 `SettingsScreen.kt:1167` 那种**写在字符串字面量里**的块注释开头两个字符
     *   吞掉后面的整段代码（T100 那枚守卫的注释里已经把这件事记过一回，本文件照抄它的走法）；
     * - 连字面量内容一起抹那把刀会把 `summary = calendarSync.targetName ?: "未选择"` 与
     *   「去系统设置开启日历权限」那两颗靶子一起抹掉，①③的读侧就数成 0；
     * - 不抹注释的话，⑥ 那两枚字段各自多算一处 —— `SettingsScreen.kt:1812-1813` 那两行注释里
     *   **同时写着** `privacyConsentAt` 与 `open = showPrivacyDialog`（它警告的正是本守卫钉的那枚改法），
     *   6/4 两枚生命点数会各自弹成 7/5，且 `ModalTransition(open = showPrivacyDialog` 会从 0 变 1。
     */
    private fun blankCommentsKeepingLiterals(source: String): String {
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

    private companion object {
        const val SCHEDULE_VIEW_MODEL = "com/buaa/schedule/ui/ScheduleViewModel.kt"
        const val SETTINGS_SCREEN = "com/buaa/schedule/ui/settings/SettingsScreen.kt"

        // ---- ① targetId / targetName（判据常数全部来自 4b1c4a4 盘面的一次性 python 复算，见 §6.8①）----
        const val REBUILD_CTOR = "CalendarSyncUiState("
        const val TARGET_ID_ASSIGNED = "targetId = "
        const val TARGET_NAME_ASSIGNED = "targetName = "
        const val TARGET_WRITE_SITES = 3
        const val TARGET_ID_LIFE_POINTS = 10
        const val TARGET_NAME_LIFE_POINTS = 5
        const val TARGET_UI_READS = 1
        const val CALENDAR_SYNC_READS = 16
        const val TARGET_SUMMARY_SHAPE = "summary = calendarSync.targetName ?: "
        const val TARGET_FALLBACK_TEXT = "未选择"
        const val TARGET_ITEM_HEAD = "item(key = \"targetCalendar\")"

        // ---- ① 的第二半：偏好里那两枚 key（T110 新增）----
        const val TARGET_ID_PREF_KEY = "\"calendar_sync_target_id\""
        const val TARGET_NAME_PREF_KEY = "\"calendar_sync_target_name\""
        const val TARGET_ID_DROPPED = "remove(\"calendar_sync_target_id\")"
        const val TARGET_NAME_DROPPED = "remove(\"calendar_sync_target_name\")"
        const val TARGET_PREF_DROP_SITES = 1
        const val TARGET_ID_PREF_LIFE_POINTS = 3
        const val TARGET_NAME_PREF_LIFE_POINTS = 3
        const val TARGET_PREF_DROP_GATE = "if (targetGone && loaded.isNotEmpty()) settingsPrefs.edit {"
        const val PREFS_EDIT_HEAD = "settingsPrefs.edit"
        const val ENSURE_LOADED_HEAD = "private suspend fun ensureCalendarsLoaded() {"
        const val ENSURE_LOADED_TAIL = "data class PendingBackup"
        const val TARGET_ID_READ_SHAPE = "targetId = settingsPrefs.getLong(\"calendar_sync_target_id\", -1L),"
        const val TARGET_NAME_READ_SHAPE = "targetName = settingsPrefs.getString(\"calendar_sync_target_name\", null),"

        // ---- ①-b `calendars` / `calendarsLoaded` 那枚缓存旗标（T110 新增）----
        const val CALENDARS_LOADED_RESET = "calendarsLoaded = false"
        const val CALENDARS_LOADED_RESET_SITES = 1
        const val CALENDARS_LOADED_TRUE_SITES = 2
        const val CALENDARS_LOADED_LIFE_POINTS = 5
        const val CALENDARS_LOADED_EARLY_RETURN = "if (_calendarSync.value.calendarsLoaded) return"
        const val CALENDARS_LOADED_DECL = "val calendarsLoaded: Boolean = false,"
        const val PICKER_RESET_SHAPE = "_calendarSync.update { it.copy(showPicker = true, calendarsLoaded = false) }"
        const val PICKER_QUERY_LAUNCH = "viewModelScope.launch { ensureCalendarsLoaded() }"
        const val SYNC_ENTRY_PAIRED_COPY =
            "it.copy(syncing = true, message = null, permissionPermanentlyDenied = false, diff = null, skippedOccurrences = 0)"

        // ---- ③ message / permissionPermanentlyDenied（§6.8③、§6.4-B）----
        const val FLAG_ASSIGNED = "permissionPermanentlyDenied = "
        const val FLAG_RAISED = "permissionPermanentlyDenied = !canAskAgain,"
        const val FLAG_CLEARED = "permissionPermanentlyDenied = false"
        const val GRANTED_CLEAR_SHAPE = "_calendarSync.update { it.copy(permissionPermanentlyDenied = false) }"
        const val FLAG_DECL = "val permissionPermanentlyDenied: Boolean = false,"
        const val MESSAGE_ASSIGNED = "message = "
        const val MESSAGE_CLEARED = "message = null"
        const val MESSAGE_DECL = "val message: AppMessage? = null,"
        const val DENIED_MESSAGE_TEXT = "日历权限已被永久拒绝，请到系统设置手动开启"
        const val SETTINGS_BUTTON_TEXT = "去系统设置开启日历权限"
        const val FLAG_WRITE_SITES = 4
        const val MESSAGE_CLEAR_SITES = 2
        const val PERMISSION_GATE = "if (viewModel.hasCalendarPermission()) {"
        const val WITH_PERMISSION_HEAD = "fun withCalendarPermission(action: () -> Unit) {"
        const val LAUNCH_CALL = "calendarPermissionLauncher.launch("
        const val HAS_PERMISSION_DECL = "fun hasCalendarPermission(): Boolean = calendarSyncManager.hasPermission()"
        const val GRANTED_CALL = "viewModel.onCalendarPermissionGranted()"
        const val GRANTED_BRANCH_HEAD = "if (grants.isNotEmpty() && grants.values.all { it }) {"
        const val ELSE_BRANCH_HEAD = "} else {"
        const val STATUS_ITEM_HEAD = "item(key = \"status\", visible = calendarSync.message != null) {"
        const val FLAG_READER = "calendarSync.permissionPermanentlyDenied"
        const val PERMISSION_GATE_SITES = 1
        const val MESSAGE_UI_READS = 3

        // ---- ③ 的第二条链：「移除已同步的日程」（T110 新增）----
        const val REMOVE_ENTRY_CLEAR_SHAPE =
            "it.copy(syncing = true, message = null, permissionPermanentlyDenied = false) }"
        const val REMOVE_DONE_MESSAGE_SHAPE = "AppMessage(\"已移除 \$n 个日程\")"
        const val REMOVE_FAILED_MESSAGE_SHAPE = "AppMessage(\"移除失败：日历写入异常，请检查权限后重试\", isError = true)"
        const val REMOVE_HEAD = "fun removeSyncedEvents() {"
        const val REMOVE_CALL = "viewModel.removeSyncedEvents()"
        const val REMOVE_MODAL_HEAD = "ModalTransition(open = calendarSync.showRemoveConfirm)"
        const val REMOVE_CONFIRM_OPENED = "showRemoveConfirm = true"
        const val REMOVE_REQUEST_HEAD = "fun requestRemoveSyncedEvents() {"
        const val REMOVE_REQUEST_CALL = "viewModel.requestRemoveSyncedEvents()"

        // ---- ③ 的第三条链：`confirmCalendarSync` 起手（T116 收 §8 表 #1）----
        const val CONFIRM_HEAD = "fun confirmCalendarSync() {"
        const val CONFIRM_EARLY_RETURN = "val pending = _calendarSync.value.diff ?: return"
        const val CONFIRM_ENTRY_ARGS = "syncing = true"
        const val CONFIRM_TAIL_MESSAGE_SHAPE = "message = when {"
        const val CONFIRM_CALL = "viewModel.confirmCalendarSync()"
        const val CONFIRM_REF = "confirmCalendarSync"
        const val UPDATE_SITE = "_calendarSync.update {"
        const val MEMBER_FUN_SEP = "\n    fun "
        const val SCOPE_LAUNCH = "viewModelScope.launch {"
        const val DIFF_PRODUCED = "diff = computed.first"
        const val DIFF_MODAL_HEAD = "ModalTransition(payload = calendarSync.diff)"
        const val MODAL_SEP = "ModalTransition("
        const val DIFF_CLEARED = "diff = null"
        const val SKIPPED_CLEARED = "skippedOccurrences = 0"

        /** 入口 3 是唯一一枚不过闸的调用点：宿主 `fun selectCalendarTarget(…)`（VM :1457），
         *  唯一界面触发点 `viewModel.selectCalendarTarget(`（SS :1888）的 click 长在 `showPicker`
         *  驱动的那层窗口里（SS :1859），而 `showPicker = true` 全仓只 2 枚写点（VM :1397/:1449）
         *  —— 开窗本身在闸里。 */
        const val SELECT_TARGET_HEAD =
            "fun selectCalendarTarget(calendarId: Long, displayName: String) {"
        const val SYNC_ENTRY_HEAD = "fun startCalendarSync() {"
        const val SYNC_CALL_TEXT = "startCalendarSync()"
        const val OPEN_PICKER_HEAD = "fun openCalendarPicker() {"
        const val SELECT_TARGET_CALL = "viewModel.selectCalendarTarget("
        const val PICKER_MODAL_HEAD = "ModalTransition(open = calendarSync.showPicker)"
        const val EMPTY_LIST_GATE = "if (calendarSync.calendars.isEmpty()) {"
        const val PICKER_OPENED = "showPicker = true"
        const val PICKER_OPEN_SITES = 2
        const val ACTION_CALL = "action()"
        const val GATED_CALL_BLOCK = "withCalendarPermission {"
        const val GATED_CALL_SITES = 3
        const val GATED_SYNC = "withCalendarPermission { viewModel.startCalendarSync() }"
        const val GATED_PICKER = "withCalendarPermission { viewModel.openCalendarPicker() }"
        const val GATED_REMOVE =
            "withCalendarPermission { viewModel.requestRemoveSyncedEvents() }"
        const val BARE_SYNC_CALL = "viewModel.startCalendarSync()"
        const val BARE_SYNC_CALL_SITES = 1
        const val BARE_PICKER_CALL = "viewModel.openCalendarPicker()"
        const val SYNC_BUTTON_SHAPE = "onClick = { startCalendarSync() }"
        const val SYNC_METHOD_REF = "viewModel::startCalendarSync"
        const val FLAG_NAME = "permissionPermanentlyDenied"
        const val VM_FLAG_READERS = 0
        const val CALENDAR_SYNC_MANAGER =
            "com/buaa/schedule/data/calendar/CalendarSyncManager.kt"
        const val MANAGER_HEAD = "fun hasPermission(): Boolean ="
        const val PERMISSION_CHECK = "checkSelfPermission"
        const val PERMISSION_CHECKS = 2

        /** 状态那一格现在实测 960 字符（:1664 到下一个 SettingsGroup）；带子留 3 倍余量，出带即重判 */
        const val STATUS_WINDOW_MIN = 300
        const val STATUS_WINDOW_MAX = 4_000

        // ---- ⑥ showPrivacyDialog / privacyConsentAt（§6.8⑥、§6.2 #14）----
        const val DIALOG_CLOSED = "showPrivacyDialog = false"
        const val DIALOG_OPENED = "showPrivacyDialog = true"
        const val DIALOG_DECL = "var showPrivacyDialog by remember { mutableStateOf(false) }"
        const val CONSENT_ZEROED = "privacyConsentAt = 0L"
        const val CONSENT_DECL = "var privacyConsentAt by remember { mutableLongStateOf(FirstRun.privacyConsentAt(context)) }"
        const val REVOKE_CALL = "FirstRun.revokePrivacy(context)"
        const val DISMISS_SHAPE = "onDismissRequest = { showPrivacyDialog = false },"
        const val KNOWN_BUTTON_SHAPE = "TextButton(onClick = { showPrivacyDialog = false })"
        const val PAYLOAD_GATE = "ModalTransition(payload = if (showPrivacyDialog) privacyConsentAt else null) { consentAt, modal ->"
        const val OPEN_PRIVACY_SHAPE = "ModalTransition(open = showPrivacyDialog"
        const val DIALOG_LIFE_POINTS = 6
        const val CONSENT_LIFE_POINTS = 4
        const val DIALOG_CLOSE_SITES = 3
        const val CONSENT_ZERO_SITES = 1
        const val PAIRED_PRIVACY_SITES = 1
        const val SPLIT_PRIVACY_SITES = 2
        const val MODAL_LAYERS = 6
        const val OPEN_LAYERS = 3
        const val CONSENT_PARAM_READS = 2

        /** privacy 弹窗那一段现在实测 2008 字符（:1814 到下一层 ModalTransition）*/
        const val PRIVACY_WINDOW_MIN = 600
        const val PRIVACY_WINDOW_MAX = 6_000
    }
}
