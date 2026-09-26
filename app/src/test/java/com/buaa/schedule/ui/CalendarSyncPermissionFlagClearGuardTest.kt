package com.buaa.schedule.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T106：`CalendarSyncUiState.permissionPermanentlyDenied` 的**清点站点**守卫 —— 只做源码核对。
 *
 * 病（本卡修掉的那一枚，全程可点、无需任何异常时序）：
 * 1. 用户申请日历权限时勾了「不再询问」⇒ `ui/ScheduleViewModel.kt:1494` `onCalendarPermissionDenied`
 *    那一次 copy 同时立旗标（`:1497` `permissionPermanentlyDenied = !canAskAgain,`）与句子
 *    （`:1498-1502`「日历权限已被永久拒绝，请到系统设置手动开启」）；界面上那颗
 *    「去系统设置开启日历权限」按钮（`ui/settings/SettingsScreen.kt:1677` 判据 + `:1678-1680`）出现。
 * 2. 用户真的去系统设置里把日历权限**手动开好** ⇒ 回到页面点「同步到系统日历」
 *    （`ui/settings/SettingsScreen.kt:1641` `onClick = { startCalendarSync() }` → `:341`
 *    `fun startCalendarSync() = withCalendarPermission { viewModel.startCalendarSync() }`）。
 * 3. `:330-339` 的 `withCalendarPermission` 第一句就是 `:331`
 *    `if (viewModel.hasCalendarPermission()) {` —— **有权限就直接 `action()`、根本不启动 launcher**
 *    （`:335` 那一枚 `calendarPermissionLauncher.launch(` 只长在 `:333` 的 else 里）。
 * 4. 而改前 `permissionPermanentlyDenied = false` **只有 `:1507` `onCalendarPermissionGranted()` 一枚入口**，
 *    它唯一调用点是 `ui/settings/SettingsScreen.kt:317`，在 `:316`「全部授予」那一档里 ——
 *    第 3 步既然不启动 launcher，这一步就永远走不到 ⇒ **旗标永不清**。
 * 5. 同步照常成功：`:1389` 起手撤句子、`:1429-1439` 落「同步完成：新增 N，更新 M，删除 K」。
 *    于是 `ui/settings/SettingsScreen.kt:1664` `item(key = "status", visible = calendarSync.message != null)`
 *    这格被新句子点亮，`:1677` 那句 `if (calendarSync.permissionPermanentlyDenied) {` 仍然为真 ⇒
 *    **「同步完成」旁边挂着「去系统设置开启日历权限」**，点它 `ReminderGuidance.openAppDetails(context)`
 *    跳去应用详情页，而权限其实早就给了。
 *
 * 修法（本卡选 (a)：把起手那次清场做成成对清）：`ui/ScheduleViewModel.kt:1389` 的 copy 里加
 * `permissionPermanentlyDenied = false`。它成立的前提是**任何能走到 `startCalendarSync()` 的路径
 * 都已经持有权限** —— 该前提就是下面第二枚 `@Test` 逐条枚举钉住的东西，前提一旦被改动（新增一枚
 * 不经过 `withCalendarPermission` 的入口），那枚判据当场红。
 *
 * 为什么不选 (b)（在 `:1429-1439` 那次结果 copy 里按 `calendarSyncManager.hasPermission()` 决定旗标）：
 * 它只补一枚落点。`startCalendarSync` 那条链上还有一枚 `message` 落点（`:1394-1395`
 * `NO_WRITABLE_CALENDAR_MESSAGE`、`:1401-1403`「暂无可同步的课表」）在结果之前就会把
 * `visible = message != null` 那格点亮，旗标照旧是旧的 ⇒ 同一条用户路径上按 (b) 仍要多点两枚站点，
 * 而且 `MutableStateFlow.update {}` 的实参表可能被**重跑**，把平台读（`hasPermission()`）折进 lambda
 * 里等于给重试路径添一次副作用读取。(a) 一枚站点覆盖 `startCalendarSync` 的全部下游落点。
 * 为什么不选 (c)（读侧改成同时判 `!hasCalendarPermission()`，或把旗标做成派生属性）：本仓对派生字段
 * 的硬规矩是「值 = f(同表参数) 才许做成类体派生属性」，而这枚旗标的值是**系统权限状态 + 上一次申请
 * 结果**的函数、不是同表参数的函数（见 `docs/derived-field-audit.md` §6.0 与 §6.2 #7），改读侧等于
 * 让界面同时信两把尺子，旗标本身还是脏的。
 *
 * 三枚 `@Test` 全是**纯 JVM 源码核对**：只 import `java.io.File` 与 JUnit，零 android import、
 * 零时钟读取；判据不吃 `Build.VERSION`、不吃 Context。行号按 `688b191`＋本卡那枚 main 改动盘面
 * （该改动是**同行改写**，`ScheduleViewModel.kt` 与 `SettingsScreen.kt` 都没长行 ⇒ 行号仍与 `688b191` 一致），
 * 按 T101 的规矩每条判据都跟**符号名 + 原文片段**同框，行号只是辅助。
 */
class CalendarSyncPermissionFlagClearGuardTest {

    /**
     * 写/清侧：**旗标共有三枚赋值站点（成对立 1 + 成对清 1 + 单清 1），而清点必须同时覆盖
     * `startCalendarSync` 起手与 `onCalendarPermissionGranted`**；并且「撤句子」这一动作只有
     * `:1389` 一枚站点、它必须顺手把旗标也撤掉（这条就是本卡那枚病的反命题）。
     *
     * 两头都钉：
     * - **朝紧**（把本卡的修复拆掉 = 起手不再点旗标）→ 第一条 3→2 红、第四条「起手体内一枚清点都没有」红、
     *   第六条「撤句子那次没带旗标」红；
     * - **朝宽**（别处再添一处只动旗标的 copy，例如顺手在 `dismissCalendarSyncDiff` 里清一下）→
     *   第一条 3→4 红、第二条 2→3 红、第三条「清点长在这两枚宿主函数之外」红。
     */
    @Test
    fun theStalePermanentDenialFlagIsClearedAtTheSyncEntryAndNowhereElse() {
        val code = blankCommentsKeepingLiterals(readMainSource(SCHEDULE_VIEW_MODEL))
        // 凭据：先确认读的是对的文件（读空/读错文件下面每一条都会"意外地"给 0 或 1）
        assertEquals(
            "靶子：`permissionPermanentlyDenied` 在 ScheduleViewModel 里的生命点不是 4 枚（1 枚参数表声明 + " +
                "3 枚赋值：:1389 起手成对清、:1497 成对立、:1508 单清）—— 数不对就是读错了文件或多读了" +
                "一处注释外的字面",
            FLAG_LIFE_POINTS,
            occurrences(code, FLAG_NAME),
        )
        assertEquals(
            "靶子：`message = ` 在这颗文件里仍是 9 处落点（含 :1389 的那枚 `message = null`）—— 它是上面那条" +
                "生命点判据的凭据，也是「这一族句子有几张嘴」的清单",
            MESSAGE_WRITE_POINTS,
            occurrences(code, MESSAGE_ASSIGNED),
        )
        val flagWrites = indexOfAll(code, FLAG_ASSIGNED)
        assertEquals(
            "给 `permissionPermanentlyDenied` 赋值的站点不是 3 处了（:1389 起手成对清 / :1497 成对立 / " +
                ":1508 单清）。**少一处** = 起手那次成对清被拆回「只撤句子」（就是本卡 T106 修掉的那枚真漏清：" +
                "手动授权后点同步，旗标永不清，「同步完成」旁边还挂着「去系统设置开启日历权限」）；" +
                "**多一处** = 又添了一条只动旗标的路径，它绕过这两枚宿主函数，得回来重判「谁能宣布权限不再是" +
                "永久拒绝」并把新增落点补进下面的宿主判据：" + lineHints(code, flagWrites),
            FLAG_WRITE_SITES,
            flagWrites.size,
        )
        val flagClears = indexOfAll(code, FLAG_CLEARED)
        assertEquals(
            "`permissionPermanentlyDenied = false`（清点）不是 2 处了。现在两处：:1389（startCalendarSync 起手）" +
                "与 :1508（onCalendarPermissionGranted，launcher 全授予那一档）。**少一处** = 有人删掉了起手" +
                "那次成对清（本卡的病回来了）；**多一处** = 第三条清旗标的通道出现，必须先证它同样只在" +
                "「此刻真的持有权限」时走得到：" + lineHints(code, flagClears),
            FLAG_CLEAR_SITES,
            flagClears.size,
        )
        val syncBody = functionBody(code, SYNC_ENTRY_HEAD, "fun startCalendarSync()")
        val grantedBody = functionBody(code, GRANTED_HEAD, "fun onCalendarPermissionGranted()")
        assertEquals(
            "靶子：`fun onCalendarPermissionDenied(canAskAgain: Boolean) {` 应当恰好一枚定义（旗标唯一的**生产**者）：",
            1,
            occurrences(code, DENIED_HEAD),
        )
        val deniedBody = functionBody(code, DENIED_HEAD, "fun onCalendarPermissionDenied")
        val inSync = flagClears.count { it in syncBody }
        val inGranted = flagClears.count { it in grantedBody }
        val outside = flagClears.filter { it !in syncBody && it !in grantedBody }
        assertEquals(
            "startCalendarSync 起手体内没有旗标清点了（现在 1 枚，长在 :1389 那次成对清里）。" +
                "这一枚是**本卡修的病根**：清旗标原先只有 onCalendarPermissionGranted() 一个入口，" +
                "而 withCalendarPermission 在已授权时根本不启动 launcher，那条路径就永远到不了那个入口。" +
                "把它删掉 = 让「永久拒绝」旗标在系统设置里授予之后常驻：",
            1,
            inSync,
        )
        assertEquals(
            "onCalendarPermissionGranted 体内不再有旗标清点（现在 1 枚，:1508）—— launcher 全授予那一档" +
                "是另一条通道，两条通道都在才算把「权限已经到手」这件事念全：",
            1,
            inGranted,
        )
        assertTrue(
            "冒出一枚既不在 startCalendarSync 也不在 onCalendarPermissionGranted 体内的旗标清点 —— " +
                "第三条通道（可能是「读一眼系统权限就顺手清」之类），得先证它只在真有权限时可达，" +
                "再把它的宿主函数补进上面两条判据：" + lineHints(code, outside),
            outside.isEmpty(),
        )
        assertTrue(
            "旗标的生产者 onCalendarPermissionDenied 体内居然也在清旗标 —— 立与清同源就必须重判「谁最后落地」",
            flagClears.none { it in deniedBody },
        )
        val messageClears = indexOfAll(code, MESSAGE_CLEARED)
        assertEquals(
            "`message = null` 的清点不是 1 处了（现在只有 :1389 起手那档）。这一条配着下面那条「它必须带旗标」" +
                "就是本卡的反命题：**界面上那颗按钮只在句子非空时组合**（SettingsScreen.kt:1664），" +
                "所以只要每次撤句子都顺手撤旗标，就不可能再出现「有句子、旗标是旧的」。" +
                "**多一处** = 又添一条只撤句子的站点（漏清那一族的正解形状被破坏）；" +
                "**少一处** = 起手不再撤句子，:1664 那格 visible 的挡法换了位置：" + lineHints(code, messageClears),
            MESSAGE_CLEAR_SITES,
            messageClears.size,
        )
        val entryClear = flagClears.first { it in syncBody }
        val entryArgs = rebuildArguments(code, entryClear)
        assertTrue(
            "起手那次清旗标不再与 `message = null` 出自同一次 copy —— 两半又分头落地，" +
                "「句子还在、旗标是旧的」那扇门重新开了一条缝：\n" + lineAt(code, entryClear),
            entryArgs.contains(MESSAGE_CLEARED),
        )
        assertTrue(
            "起手那次 copy 不再成对清 `diff`/`skippedOccurrences`（T100① 的账被这行的改写带走了）：\n" + lineAt(code, entryClear),
            entryArgs.contains(DIFF_CLEARED) && entryArgs.contains(SKIPPED_CLEARED),
        )
        val clearedSentenceSites = messageClears.filter { !rebuildArguments(code, it).contains(FLAG_CLEARED) }
        assertTrue(
            "这些撤句子的站点没有顺手撤旗标 —— 正是 T106 那枚缺陷的形状（撤完句子后 :1664 那格会被" +
                "下一句提示重新点亮，而 :1677 读的还是旧的旗标）：" + lineHints(code, clearedSentenceSites),
            clearedSentenceSites.isEmpty(),
        )
        val raisedAt = code.indexOf(FLAG_RAISED)
        check(raisedAt >= 0) {
            "靶子：找不到 `permissionPermanentlyDenied = !canAskAgain,`（:1497 那次成对生产）—— " +
                "旗标的生产写法换过了，本守卫整批要跟着重判"
        }
        assertTrue(
            "立旗标那一次 copy 不再同时写 `message = ` —— 「旗标与句子同一次生产」是本卡判据的前提，" +
                "它一断，「撤句子就撤旗标」这条成对规矩就没了靶子：\n" + lineAt(code, raisedAt),
            rebuildArguments(code, raisedAt).contains(MESSAGE_ASSIGNED),
        )
        // 靶子：被上面点名的三行原文（读错文件、整份读空都能让那些数一次全绿）
        assertEquals(
            "靶子：:1389 起手那行的原文还在（syncing + message + 旗标 + diff + skippedOccurrences 五枚一起落地）：",
            1,
            occurrences(code, ENTRY_CLEAR_SHAPE),
        )
        assertEquals(
            "靶子：:1508 那一行还在（整颗 onCalendarPermissionGranted 就这一句 copy）：",
            1,
            occurrences(code, GRANTED_CLEAR_SHAPE),
        )
        assertEquals(
            "靶子：与旗标同一次生产的那句永久拒绝文案还在（它不在就说明这两枚已经不成对了）：",
            1,
            occurrences(code, DENIED_MESSAGE_TEXT),
        )
        assertEquals("靶子：参数表上旗标的初值仍是 false（:112）：", 1, occurrences(code, FLAG_DECL))
        assertEquals("靶子：`fun startCalendarSync() {` 仍是唯一一枚定义（:1383）：", 1, occurrences(code, SYNC_ENTRY_HEAD))
        assertEquals(
            "靶子：`fun onCalendarPermissionGranted() {` 仍是唯一一枚定义（:1507）：",
            1,
            occurrences(code, GRANTED_HEAD),
        )
    }

    /**
     * 前提侧（本卡修法 (a) 的承重墙）：**走到 `startCalendarSync()` 的每一条路都站在权限闸里面**。
     * 枚举全仓可达入口（改前逐条 `grep -rn "startCalendarSync"` 复算过，UI 侧 2 枚 + VM 内部 1 枚）：
     *
     * - **入口 1**：`ui/settings/SettingsScreen.kt:1641` `onClick = { startCalendarSync() }`（那颗按钮）
     *   → `:341` `fun startCalendarSync() = withCalendarPermission { viewModel.startCalendarSync() }`
     *   → `:331` 的闸：已授权 ⇒ 直接 `action()`；未授权 ⇒ launcher → `:316` 全授予那一档 → `:319`。
     *   **两支都已持有权限**（第二支是系统刚交回的 grants）。
     * - **入口 2**：`:319` `(action ?: viewModel::startCalendarSync).invoke()` —— `:317` 刚调过
     *   `onCalendarPermissionGranted()`，且这一支只在 `grants.values.all { it }` 里走得到。
     *   （顺带钉一笔：`pendingCalendarAction` 只在 `:334` 被赋成**非空** lambda，所以 `?:` 的那一半
     *   今天是死支路；死不死都无所谓，权限是刚授予的。）
     * - **入口 3**：`ui/ScheduleViewModel.kt:1463` `selectCalendarTarget(...)` 内部那次
     *   `startCalendarSync()`（选完目标日历自动同步）—— 它是**唯一一枚不经 withCalendarPermission**
     *   的调用点，所以本条为它单独钉两道：① 那个 click 长在
     *   `ModalTransition(open = calendarSync.showPicker)`（`SettingsScreen.kt:1859`）窗口之内，而
     *   `showPicker = true` 全仓只有两枚写点（`:1397` 长在 startCalendarSync 体内、`:1449` 长在
     *   openCalendarPicker 体内），后者在界面上的唯一调用点是 `:1625`
     *   `onClick = { withCalendarPermission { viewModel.openCalendarPicker() } }` ⇒ 开窗这件事本身在闸里；
     *   ② 那一行 clickable 只在 `:1870` `if (calendarSync.calendars.isEmpty()) {` 的 **else** 侧组合，
     *   而 `calendars` 只由 `ensureCalendarsLoaded()` 的成功分支（`:1526` `calendars = loaded,`）填过，
     *   即「列表非空」= 上一次查询日历成功 = 那一刻权限是真的。
     *   ⚠️ 静态证不了的那半如实记：`calendarsLoaded`（`:1512`）是进程寿命的缓存，若用户在弹窗开着的时候
     *   去系统设置把权限**关掉**再回来点一行，起手那次清旗标就是在无权限时清 —— 本节验不到设备行为，
     *   但旗标只活在内存里（参数表 `:112` 初值 false，全仓没有偏好持久化），且下一次点同步会走
     *   launcher → `onCalendarPermissionDenied` 重新立旗标 ⇒ 最坏是那一帧少一颗按钮，不是假话常驻。
     *
     * 两头都钉：闸被拆/被改写 → `:331`、`:341`、三枚 `withCalendarPermission { … }` 原文靶子红；
     * 新增一枚绕过闸的入口（例如直接 `onClick = { viewModel.startCalendarSync() }`）→
     * `GATED_CALL_SITES` 3→3 不变，但 `viewModel.startCalendarSync()` 1→2 红、VM 内部调用点 1→2 也红。
     */
    @Test
    fun everyRouteIntoTheSyncEntryStandsInsideAPermissionGate() {
        val screen = blankCommentsKeepingLiterals(readMainSource(SETTINGS_SCREEN))
        val code = blankCommentsKeepingLiterals(readMainSource(SCHEDULE_VIEW_MODEL))
        // ---- 闸本体 ----
        assertEquals(
            "靶子：闸的宿主函数定义原文还在（:330 `fun withCalendarPermission(action: () -> Unit) {`）：",
            1,
            occurrences(screen, GATE_HEAD),
        )
        assertEquals(
            "`if (viewModel.hasCalendarPermission()) {` 在界面上不再是恰好 1 处（:331）。**多一处** = 又添一道" +
                "能绕过 launcher 的短路，每加一枚都得问「它进的那条路要不要也清旗标」；**少一处** = 短路没了，" +
                "那么 :1508 那次单清重新变成唯一通道，本卡那枚病就换个方向复发：" +
                lineHints(screen, indexOfAll(screen, PERMISSION_GATE)),
            PERMISSION_GATE_SITES,
            occurrences(screen, PERMISSION_GATE),
        )
        assertEquals(
            "靶子：那颗「同步到系统日历」按钮的 onClick 原文还在（:1641，入口 1 的起点）：",
            1,
            occurrences(screen, SYNC_BUTTON_SHAPE),
        )
        val gate = screen.indexOf(PERMISSION_GATE)
        val head = screen.indexOf(GATE_HEAD)
        check(head >= 0 && gate >= 0) { "靶子：闸 A 的两行原文至少要都在，否则本守卫扫的不是这一页" }
        val between = screen.substring(head + GATE_HEAD.length, gate)
        assertTrue(
            "闸不再是 withCalendarPermission 的第一句（中间隔着「" + between.trim() + "」）—— " +
                "「点同步之前先问一句真实权限」这条前提被削弱，起手那次清旗标就可能是假话",
            between.all { it.isWhitespace() },
        )
        val launch = indexOfAll(screen, LAUNCH_CALL)
        assertEquals(
            "`calendarPermissionLauncher.launch(` 不再是恰好 1 处（:335，闸的 else 分支里）。这一枚是" +
                "「已授权时根本不启动申请框 ⇒ 到不了 :317 那次清旗标」的凭据；**多一处** = 有人绕过闸直接申请，" +
                "起手清旗标的账要重算：" + lineHints(screen, launch),
            1,
            launch.size,
        )
        val trueBranch = functionBody(screen, PERMISSION_GATE, "闸的 if 体")
        assertTrue(
            "launcher.launch 跑到了闸之前（或跑进了闸的 true 分支里）—— 那等于每次点同步都先弹申请框，" +
                "本卡第 3 步那条「有权限就不启动 launcher ⇒ 到不了 :317 那次单清」的推理作废，" +
                "而 :1389 那次成对清也就从「唯一覆盖那条正常路径的清点」降级成多余的一刀",
            gate < launch.first() && launch.first() !in trueBranch,
        )
        val actionCall = indexOfAll(screen, ACTION_CALL)
        assertEquals(
            "闸 true 分支里那句 `action()` 不再是恰好 1 处（:332）—— 它就是「已授权 ⇒ 直接执行、" +
                "不启动 launcher」这一支的载体，本卡那条正常路径走的正是它：",
            1,
            actionCall.size,
        )
        assertTrue(
            "`action()` 不在闸的 true 分支里了 —— 「有权限就直接执行 action」这句话不再是闸的语义，" +
                "起手那次清旗标的覆盖面（入口 1）要按新写法重推",
            actionCall.first() in trueBranch,
        )
        // ---- 三枚被闸包住的入口 ----
        assertEquals(
            "`withCalendarPermission { … }` 的调用块不再是 3 处（:341 同步 / :1625 选目标日历 / " +
                ":1654 移除已同步日程）。**多一处** = 又添一条需要同步前清旗标的通道（要重数上面那枚短路）；" +
                "**少一处** = 有入口不再走闸，起手那次清旗标的覆盖面要重判",
            GATED_CALL_SITES,
            occurrences(screen, GATED_CALL_BLOCK),
        )
        assertEquals(
            "靶子：同步那颗按钮那条链的原文还在（入口 1，整条都在闸里）：",
            1,
            occurrences(screen, GATED_SYNC),
        )
        assertEquals(
            "靶子：选目标日历那条行的原文还在（入口 3 的开窗侧，整条都在闸里）：",
            1,
            occurrences(screen, GATED_PICKER),
        )
        assertEquals(
            "靶子：移除已同步日程那条行的原文还在（它也在闸里，但它不走到 startCalendarSync）：",
            1,
            occurrences(screen, GATED_REMOVE),
        )
        assertEquals(
            "`viewModel.startCalendarSync()` 在界面上的调用点不再是 1 处（只有 :341 闸里那一枚）。" +
                "**多一处** = 有人新增一枚不过闸的同步入口 ⇒ 起手那次成对清就不再必然发生，" +
                "本卡那条「旗标永不清」的路径会换个载体回来：" +
                lineHints(screen, indexOfAll(screen, BARE_SYNC_CALL)),
            1,
            occurrences(screen, BARE_SYNC_CALL),
        )
        // ---- launcher 全授予那一档（入口 2）----
        assertEquals(
            "靶子：全授予那一档的判断原文还在（:316，:317 那次单清唯一的入口）：",
            1,
            occurrences(screen, GRANTED_BRANCH_HEAD),
        )
        assertEquals(
            "`viewModel.onCalendarPermissionGranted()` 的调用点不再是 1 处（:317）。**多一处** = 第三条清旗标" +
                "的通道（要么把它也钉进第一枚 `@Test` 的宿主判据，要么说明它能绕过「全部授予」那一档）；" +
                "**少一处** = launcher 授权回来不再清旗标，本卡的病从另一头复发：" +
                lineHints(screen, indexOfAll(screen, GRANTED_CALL)),
            1,
            occurrences(screen, GRANTED_CALL),
        )
        assertEquals(
            "`viewModel::startCalendarSync` 那枚方法引用不再是 1 处（:319 的默认支）—— 它是入口 2 的载体：" +
                lineHints(screen, indexOfAll(screen, SYNC_METHOD_REF)),
            1,
            occurrences(screen, SYNC_METHOD_REF),
        )
        val branch = screen.indexOf(GRANTED_BRANCH_HEAD)
        val elseAt = screen.indexOf(ELSE_BRANCH_HEAD, branch + GRANTED_BRANCH_HEAD.length)
        check(elseAt > branch) { "靶子：:316 之后找不到那档 if/else 的 else 边界，窗口切不出来" }
        val ref = screen.indexOf(SYNC_METHOD_REF)
        assertTrue(
            "默认同步那次调用跑出了「全部授予」那一档 —— 它从此可以在拒绝路径上被 invoke，" +
                "「进到 startCalendarSync 时权限必已到手」这条前提要重判",
            ref in branch until elseAt,
        )
        // ---- 入口 3：选完目标日历自动同步（唯一一枚不过 withCalendarPermission 的调用点）----
        val internalCalls = indexOfAll(code, SYNC_CALL_TEXT).filter {
            val entry = code.indexOf(SYNC_ENTRY_HEAD)
            it !in entry until (entry + SYNC_ENTRY_HEAD.length)
        }
        assertEquals(
            "ScheduleViewModel 内部对 `startCalendarSync()` 的调用不再是 1 处（现在只有 :1463 " +
                "selectCalendarTarget 选完日历那一次）。**多一处** = 又添一枚不经闸的内部入口，" +
                "必须先证它同样只在已授权时可达，否则起手那次清旗标就是假话：" + lineHints(code, internalCalls),
            1,
            internalCalls.size,
        )
        val selectBody = functionBody(code, SELECT_TARGET_HEAD, "fun selectCalendarTarget")
        assertTrue(
            "内部那次 startCalendarSync() 调用不在 selectCalendarTarget 体内（现在它是唯一一枚）—— " +
                "宿主换了就得按新宿主重做「开窗在闸里 + 列表非空要有权限」这两道前提",
            internalCalls.first() in selectBody,
        )
        assertEquals(
            "靶子：`fun selectCalendarTarget(calendarId: Long, displayName: String) {` 仍是唯一一枚定义（:1457）：",
            1,
            occurrences(code, SELECT_TARGET_HEAD),
        )
        assertEquals(
            "界面上 `viewModel.selectCalendarTarget(` 的调用点不再是 1 处（:1888 那一行日历名）—— " +
                "它经一次点击直达 VM 内部那次 startCalendarSync()，多一枚就是多一条不经 withCalendarPermission 的入口",
            1,
            occurrences(screen, SELECT_TARGET_CALL),
        )
        val modalAt = screen.indexOf(PICKER_MODAL_HEAD)
        check(modalAt >= 0) { "靶子：找不到 `ModalTransition(open = calendarSync.showPicker)` —— 选择器那层的驱动方式换过了" }
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
            "`showPicker = true` 的写点不再是 2 处（:1397 startCalendarSync 的「没有目标日历」那一档 + " +
                ":1449 openCalendarPicker）。**多一处** = 冒出一条不经任何闸就能打开选择器的路，" +
                "入口 3 的「开窗在闸里」那道前提就此失证：" + lineHints(code, pickerOpens),
            PICKER_OPEN_SITES,
            pickerOpens.size,
        )
        val openBody = functionBody(code, OPEN_PICKER_HEAD, "fun openCalendarPicker")
        val syncBody = functionBody(code, SYNC_ENTRY_HEAD, "fun startCalendarSync")
        assertTrue(
            "两枚开窗站点不再分别长在 openCalendarPicker / startCalendarSync 体内（这两枚宿主各自的界面入口 " +
                ":1625 与 :1641 都站在 withCalendarPermission 里）—— 宿主换了就得重新数一次",
            pickerOpens.count { it in openBody } == 1 && pickerOpens.count { it in syncBody } == 1,
        )
        assertEquals(
            "靶子：界面上 openCalendarPicker 的调用点仍是闸里那一枚（:1625）：",
            1,
            occurrences(screen, BARE_PICKER_CALL),
        )
    }

    /**
     * 尺子侧：**闸读的是当下真实权限，不是那枚旗标**；旗标在 VM 内没有读者、在界面上只有那颗按钮一个读者。
     *
     * 为什么这条值得单独钉：修法 (a) 说「进到起手就顺手宣布永久拒绝不成立」，这句话的真假**完全**押在
     * `hasCalendarPermission()` 读的是 `ContextCompat.checkSelfPermission`（WRITE 与 READ 两枚都得 granted）
     * 上。谁把它改成读缓存旗标（`return !_calendarSync.value.permissionPermanentlyDenied` 之类），
     * 闸与旗标就互相兜底成一枚循环，上面那两条判据全绿而行为回到「按钮常驻」。
     *
     * 两头都钉：闸换成读旗标 → 第一/第二条红；把 manager 那两枚 `checkSelfPermission` 减成一枚
     * （只查 WRITE）→ 第三条红（那时「闸放行」不等于「能写日历」，起手清旗标仍然算真话，但
     * 同步会失败在另一句提示上，§6.4-B 那格机制账要重判）。
     */
    @Test
    fun theGateStillReadsLiveSystemPermissionAndTheFlagHasNoReaderInsideTheViewModel() {
        val code = blankCommentsKeepingLiterals(readMainSource(SCHEDULE_VIEW_MODEL))
        val manager = blankCommentsKeepingLiterals(readMainSource(CALENDAR_SYNC_MANAGER))
        val screen = blankCommentsKeepingLiterals(readMainSource(SETTINGS_SCREEN))
        assertEquals(
            "靶子：`fun hasCalendarPermission(): Boolean = calendarSyncManager.hasPermission()` 还在 :1380 —— " +
                "它是「闸问的是真实权限」这句话本身的原文：",
            1,
            occurrences(code, HAS_PERMISSION_DECL),
        )
        val flagReadsInViewModel = occurrences(code, FLAG_NAME) - 1 - FLAG_WRITE_SITES
        assertEquals(
            "VM 里对旗标的**读取**不再是 0 处（生命点 4 = 1 枚参数表声明 + 3 枚赋值，一枚读者都没有）。" +
                "多一处读取 = 旗标开始当第二把尺子用：要么上面那条 `hasCalendarPermission()` 的短路被搬进了 " +
                "VM（闸与旗标互兜、本卡的修法就此失去凭据），要么有分支开始按旧旗标决定行为 —— " +
                "两种都要回来重判这一档",
            0,
            flagReadsInViewModel,
        )
        assertEquals(
            "`ContextCompat.checkSelfPermission` 在 CalendarSyncManager 里不再是 2 处（:50 的 WRITE 与 :52 的 " +
                "READ，两枚都 granted 才算过）—— 少一枚就是「闸放行 ≠ 能写日历」，起手清旗标这句话的强度要改口径",
            PERMISSION_CHECKS,
            occurrences(manager, PERMISSION_CHECK),
        )
        assertEquals(
            "靶子：`fun hasPermission(): Boolean =` 仍是唯一一枚定义（data/calendar/CalendarSyncManager.kt:49）：",
            1,
            occurrences(manager, MANAGER_HEAD),
        )
        assertEquals(
            "旗标在界面上的读者不再是 1 处（只有 :1677 那颗「去系统设置开启日历权限」的按钮判据）。" +
                "**多一处** = 第二张嘴会念旧旗标，起手那次成对清的覆盖面就得重算；**少一处** = 那颗按钮没了，" +
                "本卡这枚病没有可读的后果，本守卫与 §6.2 #7 那格一起收掉",
            1,
            occurrences(screen, FLAG_READER),
        )
        assertEquals(
            "靶子：那颗按钮的文案还在且只有一处（:1679）—— 它就是这枚漏清唯一会被念出来的地方：",
            1,
            occurrences(screen, SETTINGS_BUTTON_TEXT),
        )
    }

    // ---- 源码核对小工具（抄 CalendarSyncTargetPairingGuardTest / CalendarSyncDiffClearPairingGuardTest）----

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

    /**
     * 从 [hit] 往前找包围它的那次重建（`copy(` 或 `CalendarSyncUiState(`），按括号配平
     * （跳过字符串字面量）取出实参表。不用「同一行」当判据：本仓 :1496-1503 那种成对写就折了 8 行。
     */
    private fun rebuildArguments(source: String, hit: Int): String {
        val copy = source.lastIndexOf("copy(", hit)
        val ctor = source.lastIndexOf(REBUILD_CTOR, hit)
        val open = maxOf(copy, ctor)
        check(open >= 0) {
            "那一处往前找不到包围它的 copy( / CalendarSyncUiState(：赋值换了载体（抽成函数？直接赋值？），" +
                "本守卫要跟着改"
        }
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
     * [needle]（必须是**以 `{` 结尾**的一行头，如 `fun startCalendarSync() {`）那枚函数体的位置区间。
     *
     * 用来判「清点/开窗的站点长在谁体内」。括号配平跳过字符串与字符字面量；注释已由
     * [blankCommentsKeepingLiterals] 抹成空白，所以块注释里的花括号不会骗过它。找不到配不平整就抛。
     */
    private fun functionBody(source: String, headNeedle: String, label: String): IntRange {
        val head = source.indexOf(headNeedle)
        check(head >= 0) { "靶子：找不到 $label 的定义头（原文 `$headNeedle`）—— 那一层换写法或换名字了，本守卫要跟着改" }
        var index = head + headNeedle.length - 1
        check(source[index] == '{') { "$label 的定义头不是以 { 收尾的，切不出体：" + lineText(source, head) }
        var depth = 0
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
                c == '\'' -> {
                    var j = index + 1
                    while (j < source.length) {
                        if (source[j] == '\\') { j += 2; continue }
                        if (source[j] == '\'') break
                        if (source[j] == '\n') break
                        j++
                    }
                    index = j
                }

                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) {
                        val body = (head + headNeedle.length) until index
                        val bodyLength = index - (head + headNeedle.length)
                        check(bodyLength in BODY_MIN..BODY_MAX) {
                            "$label 的体长越界（$bodyLength 不在 $BODY_MIN..$BODY_MAX）—— 切出的窗口" +
                                "不是那一层，站点归属的判据要重判"
                        }
                        return body
                    }
                }
            }
            index++
        }
        throw IllegalStateException("$label 的花括号没配平")
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
     * 整段抄三枚先例的同名工具；本卡的靶子里有中文文案（「日历权限已被永久拒绝…」）与
     * `SettingsScreen.kt` 里那种**写在字符串字面量里**的块注释开头两个字符，两种刀法都不能少。
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
        const val CALENDAR_SYNC_MANAGER = "com/buaa/schedule/data/calendar/CalendarSyncManager.kt"

        // ---- 写/清侧（ScheduleViewModel.kt，行号按 688b191＋本卡同行改写）----
        const val FLAG_NAME = "permissionPermanentlyDenied"
        const val FLAG_ASSIGNED = "permissionPermanentlyDenied = "
        const val FLAG_CLEARED = "permissionPermanentlyDenied = false"
        const val FLAG_RAISED = "permissionPermanentlyDenied = !canAskAgain,"
        const val FLAG_DECL = "val permissionPermanentlyDenied: Boolean = false,"
        const val MESSAGE_ASSIGNED = "message = "
        const val MESSAGE_CLEARED = "message = null"
        const val DIFF_CLEARED = "diff = null"
        const val SKIPPED_CLEARED = "skippedOccurrences = 0"
        const val DENIED_MESSAGE_TEXT = "日历权限已被永久拒绝，请到系统设置手动开启"
        const val SETTINGS_BUTTON_TEXT = "去系统设置开启日历权限"
        const val FLAG_LIFE_POINTS = 4
        const val FLAG_WRITE_SITES = 3
        const val FLAG_CLEAR_SITES = 2
        const val MESSAGE_CLEAR_SITES = 1
        const val MESSAGE_WRITE_POINTS = 9

        const val SYNC_ENTRY_HEAD = "fun startCalendarSync() {"
        const val SYNC_CALL_TEXT = "startCalendarSync()"
        const val GRANTED_HEAD = "fun onCalendarPermissionGranted() {"
        const val DENIED_HEAD = "fun onCalendarPermissionDenied(canAskAgain: Boolean) {"
        const val OPEN_PICKER_HEAD = "fun openCalendarPicker() {"
        const val SELECT_TARGET_HEAD = "fun selectCalendarTarget(calendarId: Long, displayName: String) {"
        const val PICKER_OPENED = "showPicker = true"
        const val HAS_PERMISSION_DECL = "fun hasCalendarPermission(): Boolean = calendarSyncManager.hasPermission()"
        const val REBUILD_CTOR = "CalendarSyncUiState("

        /** 起手那次成对清的原文（本卡修法那一行）与 :1508 那次单清的原文 */
        const val ENTRY_CLEAR_SHAPE =
            "it.copy(syncing = true, message = null, permissionPermanentlyDenied = false, diff = null, skippedOccurrences = 0)"
        const val GRANTED_CLEAR_SHAPE = "_calendarSync.update { it.copy(permissionPermanentlyDenied = false) }"

        // ---- 前提侧（SettingsScreen.kt）----
        const val GATE_HEAD = "fun withCalendarPermission(action: () -> Unit) {"
        const val PERMISSION_GATE = "if (viewModel.hasCalendarPermission()) {"
        const val ACTION_CALL = "action()"
        const val LAUNCH_CALL = "calendarPermissionLauncher.launch("
        const val GATED_CALL_BLOCK = "withCalendarPermission {"
        const val GATED_SYNC = "withCalendarPermission { viewModel.startCalendarSync() }"
        const val GATED_PICKER = "withCalendarPermission { viewModel.openCalendarPicker() }"
        const val GATED_REMOVE = "withCalendarPermission { viewModel.requestRemoveSyncedEvents() }"
        const val BARE_SYNC_CALL = "viewModel.startCalendarSync()"
        const val BARE_PICKER_CALL = "viewModel.openCalendarPicker()"
        const val GRANTED_CALL = "viewModel.onCalendarPermissionGranted()"
        const val GRANTED_BRANCH_HEAD = "if (grants.isNotEmpty() && grants.values.all { it }) {"
        const val SYNC_METHOD_REF = "viewModel::startCalendarSync"
        const val ELSE_BRANCH_HEAD = "} else {"
        const val SYNC_BUTTON_SHAPE = "onClick = { startCalendarSync() }"
        const val SELECT_TARGET_CALL = "viewModel.selectCalendarTarget("
        const val PICKER_MODAL_HEAD = "ModalTransition(open = calendarSync.showPicker)"
        const val EMPTY_LIST_GATE = "if (calendarSync.calendars.isEmpty()) {"
        const val FLAG_READER = "calendarSync.permissionPermanentlyDenied"
        const val PERMISSION_GATE_SITES = 1
        const val GATED_CALL_SITES = 3
        const val PICKER_OPEN_SITES = 2

        // ---- 尺子侧（CalendarSyncManager.kt）----
        const val MANAGER_HEAD = "fun hasPermission(): Boolean ="
        const val PERMISSION_CHECK = "checkSelfPermission"
        const val PERMISSION_CHECKS = 2

        /** 函数体/块体的合理长度带：闸的 true 分支现在实测 25，startCalendarSync 的体 800+ */
        const val BODY_MIN = 15
        const val BODY_MAX = 12_000
    }
}
