package com.buaa.schedule.ui

import java.io.File
import org.junit.Assert.assertEquals
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
 * **③ `CalendarSyncUiState.message` / `.permissionPermanentlyDenied`**（§6.8③、§6.2 表 #7、§6.4-B）
 * 成对写一处：`ui/ScheduleViewModel.kt:1496-1503`（`permissionPermanentlyDenied = !canAskAgain,` 与
 * `message = AppMessage(... "日历权限已被永久拒绝，请到系统设置手动开启" ...)` 出自同一次 copy）。
 * 分头清两处：`:1389` `it.copy(syncing = true, message = null, diff = null, skippedOccurrences = 0)`
 * 只撤句子不清旗标；`:1508` `_calendarSync.update { it.copy(permissionPermanentlyDenied = false) }`
 * 只清旗标不撤句子。今天不发作靠的是**别处**的两道闸（`ui/settings/SettingsScreen.kt:1664`
 * 那句 `visible = calendarSync.message != null`、与 `:331` 的 `if (viewModel.hasCalendarPermission())`
 * 短路），**不是**字段对自身的性质 —— §6.4-B 明写「这句话必须记着」。所以本文件把两半**一起**钉：
 * 闸门还在 + 分头清的站点还是这几处。挪走任何一半，本守卫当场红，逼着重新判一次档位。
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
 * 六枚 `@Test` 全是**纯 JVM 源码核对**：只 import `java.io.File` 与 JUnit，零 android import、
 * 零时钟读取（不碰 `System.currentTimeMillis()` / `LocalDate.now()` 之类）。
 * 行号按 `4b1c4a4` 盘面复算；按 T101 立的规矩，每条判据都跟**符号名 + 原文片段**同框，行号只是辅助。
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

    // ---------------- ③ message / permissionPermanentlyDenied ----------------

    /**
     * 写/清侧：**成对写 1 处、分头清 2 处**。
     *
     * `permissionPermanentlyDenied = ` 出现 2 次（:1497 的成对写 + :1508 的单清）、
     * `message = null` 出现 1 次（:1389 的单清）。两头都钉：
     * **多一处** = 添了新的单清点或新的旗标写法，「漏清会不会被念出来」要按新站点重算；
     * **少一处** = 有人把某一处改成了成对清 —— 那是这一族的正解，但本文件的判据与 §6.4-B 的账
     * 都要跟着改（谁改谁回来把这条断言与那节一起收掉，别让下一遍以为闸门还在）。
     */
    @Test
    fun thePermissionFlagAndTheMessageAreWrittenTogetherOnceButClearedApartTwice() {
        val code = blankCommentsKeepingLiterals(readMainSource(SCHEDULE_VIEW_MODEL))
        val flagWrites = indexOfAll(code, FLAG_ASSIGNED)
        assertEquals(
            "给 `permissionPermanentlyDenied` 赋值的站点从 2 处变了（:1497 成对写 + :1508 单清）。" +
                "**多一处** = 又添一处只动旗标不动句子的路径，那道「句子还在才念得出按钮」的闸就多一个" +
                "绕开它的入口；**少一处** = 旗标换了载体或不再复位，本守卫的字面判据跟不上了：",
            FLAG_WRITE_SITES,
            flagWrites.size,
        )
        val messageClears = indexOfAll(code, MESSAGE_CLEARED)
        assertEquals(
            "`message = null` 的清空站点不再是 1 处（现在只有 :1389 startCalendarSync 起手那一档）。" +
                "**多一处** = 多一条只撤句子、留着旗标的路径；**少一处** = 起手不再撤句子，" +
                "那 :1664 那枚 `visible = calendarSync.message != null` 的挡法就换了位置：" +
                lineHints(code, messageClears),
            MESSAGE_CLEAR_SITES,
            messageClears.size,
        )
        val raisedAt = code.indexOf(FLAG_RAISED)
        check(raisedAt >= 0) {
            "靶子：找不到 `permissionPermanentlyDenied = !canAskAgain,`（:1497 那次成对写）—— " +
                "旗标的生产写法换过了，本守卫整批要跟着重判"
        }
        assertTrue(
            "那一次写旗标的重建里没有 `message = ` —— 永久拒绝的旗标与那句" +
                "「日历权限已被永久拒绝，请到系统设置手动开启」出自同一次生产（:1496-1503），" +
                "分开写就会有一枚先落地：\n" + lineAt(code, raisedAt),
            rebuildArguments(code, raisedAt).contains(MESSAGE_ASSIGNED),
        )
        val clearedAt = code.indexOf(FLAG_CLEARED)
        check(clearedAt >= 0) { "靶子：找不到 `permissionPermanentlyDenied = false`（:1508 那次单清）" }
        assertTrue(
            ":1508 onCalendarPermissionGranted 那一处开始连 `message` 一起清了 —— 这是这一族的正解之一，" +
                "但 §6.4-B 那句「这一方向是被 hasCalendarPermission() 那道闸挡住的」要跟着改口径，" +
                "本守卫与 :331 那道闸的成对判据都得重钉（改完请连 docs/derived-field-audit.md §6.8③ 一起收账）：\n" +
                lineAt(code, clearedAt),
            !rebuildArguments(code, clearedAt).contains(MESSAGE_ASSIGNED),
        )
        assertTrue(
            ":1389 startCalendarSync 起手那一处开始连旗标一起清了 —— 同上，那是正解之一，" +
                "但「撤句子就顺手把按钮关掉」这道巧合就此消失，本守卫要重钉：\n" + lineAt(code, messageClears.first()),
            !rebuildArguments(code, messageClears.first()).contains(FLAG_ASSIGNED),
        )
        // 靶子：被这三条断言点名的三行原文
        assertEquals(
            "靶子：:1389 那一行的原文还在（syncing + message + diff + skippedOccurrences 四枚一起落地）：",
            1,
            occurrences(code, "it.copy(syncing = true, message = null, diff = null, skippedOccurrences = 0)"),
        )
        assertEquals(
            "靶子：:1508 那一行的原文还在（整颗 onCalendarPermissionGranted 就这一句 copy）：",
            1,
            occurrences(code, "_calendarSync.update { it.copy(permissionPermanentlyDenied = false) }"),
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
     * 读侧前提：那两处「分头清」今天不发作，靠的是**两道别人的闸**（§6.4-B 明写「机制可被别处一行改动挪走」）。
     * 本守卫因此把闸钉成判据 —— 闸门一旦被挪走，漏清立刻从看不见变成看得见，这一格必须红。
     *
     * 闸 A（`:331` 的 `if (viewModel.hasCalendarPermission())` 短路）挡的是 :1508 那次单清：
     * 有权限就直接执行 `action()`、**根本不启动 launcher**，所以「旗标为 true 却还没被 :1508 清掉」这一段进不去。
     * 闸 B（`:1664` 的 `visible = calendarSync.message != null` + `:1665` 的 `message?.let`）挡的是 :1389
     * 那次单清留下的旗标：句子一撤，那颗「去系统设置开启日历权限」的按钮整块不组合。
     *
     * 两头都钉：闸少一处（0 命中）红、闸多一处（2 命中）红；读者跑出窗口红、窗口不再像那块也红。
     */
    @Test
    fun thePermissionGateAndTheMessageGateStillInertizeThoseTwoSplitClears() {
        val screen = blankCommentsKeepingLiterals(readMainSource(SETTINGS_SCREEN))
        // ---- 闸 A ----
        assertEquals(
            "`viewModel.hasCalendarPermission()` 在界面上的调用点不再是 1 处（现在只有 :331 withCalendarPermission " +
                "的第一句）。**多一处** = 又添了一道能绕过 launcher 的入口，:1508 那次单清的挡法要重算；" +
                "**少一处** = 短路没了 —— 那条 §6.4-B 的机制被搬走，漏清从看不见变成看得见，" +
                "③的档位要从「结构不可能」搬到「真漏清」，同时把 :1389/:1508 改成成对清：",
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
                "**多一处** = 添了第二张嘴，而它多半不在 message 驱动的窗口里 ⇒ :1389 留下的旧旗标当场念出来；" +
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
            "那颗「去系统设置开启日历权限」的按钮跑出了 message 驱动的那一格 —— 这正是 §6.4-B 记下的" +
                "那句「这一句必须记着」预言的事：闸 B 挪走之后，:1389 清了句子而留着的旗标会变成常驻读者，" +
                "③从「结构不可能」搬到「真漏清」。要么现在把 :1389/:1508 改成成对清，要么回来重判这一档",
            flagReader in windowStart until windowEnd,
        )
        assertEquals(
            "靶子：那颗按钮的文案「去系统设置开启日历权限」只有一处 —— 它是这一对漏清唯一会被念出来的地方",
            1,
            occurrences(screen, SETTINGS_BUTTON_TEXT),
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

        // ---- ③ message / permissionPermanentlyDenied（§6.8③、§6.4-B）----
        const val FLAG_ASSIGNED = "permissionPermanentlyDenied = "
        const val FLAG_RAISED = "permissionPermanentlyDenied = !canAskAgain,"
        const val FLAG_CLEARED = "permissionPermanentlyDenied = false"
        const val FLAG_DECL = "val permissionPermanentlyDenied: Boolean = false,"
        const val MESSAGE_ASSIGNED = "message = "
        const val MESSAGE_CLEARED = "message = null"
        const val MESSAGE_DECL = "val message: AppMessage? = null,"
        const val DENIED_MESSAGE_TEXT = "日历权限已被永久拒绝，请到系统设置手动开启"
        const val SETTINGS_BUTTON_TEXT = "去系统设置开启日历权限"
        const val FLAG_WRITE_SITES = 2
        const val MESSAGE_CLEAR_SITES = 1
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
