package com.buaa.schedule.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T100②：`CalendarSyncUiState.skippedOccurrences` 与 `diff` 是 `CalendarSyncManager.computeDiff`
 * 一次返回的**同一对**（`Pair<CalendarSyncPlanner.Diff, Int>`），清空 `diff` 的站点必须把它一起清 0。
 *
 * 病（T97 审计 §2.2 记下的那一笔相邻形状）：成对写只有一处、成对清也只有一处 ——
 * `startCalendarSync` 起手那档 `it.copy(syncing = true, message = null, diff = null)` 与
 * `confirmCalendarSync` 那档 `it.copy(diff = null, reminderMinutes = …)` 都只清了一半，
 * 于是"上一份 diff 的附属说明"会活在下一份 diff 之前。今天念不出错，只是因为唯一渲染点
 * 整块锁在 `ModalTransition(payload = calendarSync.diff)` 里 —— 那是一句**运行期巧合**，
 * 没有静态网钉着，跟 T94 那族"挂在别处的隐式前提"同一种形状。
 *
 * 为什么不收成 T95 那一刀（类体派生属性）：`skippedOccurrences` 不是 `f(diff)`（`Diff` 里没有
 * 这个数，两枚各有各的源：`CalendarSyncPlanner.compute(...)` 与 `ScheduleOccurrences.build(...).skipped`），
 * 所以它正当的落点只剩"每次成对写 / 每次成对清"这一条 —— 由本文件钉住。
 *
 * 三层：
 * 1. [everyDiffClearInViewModelAlsoClearsSkippedOccurrences] —— 写侧：**数出现次数 + 读形状**。
 *    先数 `ScheduleViewModel.kt`（抹注释后）里 `diff = null` 恰好 3 处，再逐处取出**包围它的那次
 *    `copy(…)` 实参表**（括号配平，不是"同一行"，换行写法也算），要求每一处都同时写 `skippedOccurrences = 0`。
 * 2. [thePairedProducerIsTheOnlyWriterAndTheOnlyReaderSitsInsideTheDiffModal] —— 读侧前提：
 *    生产者只有一处（`diff = computed.first` 与 `skippedOccurrences = computed.second` 各一枚），
 *    而界面唯一读点落在 `ModalTransition(payload = calendarSync.diff)` 那个窗口之内 ——
 *    有人把渲染点挪出弹窗、或再添第二处写点，本守卫当场红，逼着重新判一次"该不该成对"。
 * 3. [theRemoveChainClearsNeitherDiffNorSkippedBecauseTheTwoModalsHoldEachOtherOff] —— **T116 补的第三层**：
 *    `docs/derived-field-audit.md` §8.4-B 末那句「现有那枚 diff 守卫数的是 `diff = null` 清点点 ⇒
 *    `:1481` 这一格零守卫」就是这一层。`removeSyncedEvents` 起手收两枚（旗标 + 句子）、**不收这一对**，
 *    今天不红的凭据是"两扇弹窗彼此挡住对方的入口"这一道**外来的闸** —— 本层钉那道闸的位置，
 *    并记下"补收"那一支为什么驳回（拿「移除」去作废一份用户还没确认的 diff，见那枚判据的 KDoc 第三段）。
 *
 * 三枚都只做**源码核对**（JVM，无 Robolectric、无设备）。
 */
class CalendarSyncDiffClearPairingGuardTest {

    /**
     * 三处清空 `diff` 的站点（`startCalendarSync` 起手 / `confirmCalendarSync` 应用确认 /
     * `dismissCalendarSyncDiff`）必须**逐处**同时把 `skippedOccurrences` 清 0。
     *
     * 判据两头都钉：3 是"清点过、不许长出第四处漏网的清空站点"（朝松），
     * `skippedOccurrences = 0` 也是 3 是"三处一处都不许掉队"（朝紧的反面 —— 多一处单独清 0
     * 也算改了口径，要重新判一次）。
     */
    @Test
    fun everyDiffClearInViewModelAlsoClearsSkippedOccurrences() {
        val code = blankCommentsKeepingLiterals(readMainSource(SCHEDULE_VIEW_MODEL))
        val hits = indexOfAll(code, DIFF_CLEARED)
        assertEquals(
            "清空 diff 的站点从 3 处变了（现在是 startCalendarSync / confirmCalendarSync / " +
                "dismissCalendarSyncDiff 各一处）。多一处 = 多一次成对清理要写，少一处 = 有人换了写法：" +
                lineHints(code, hits),
            3,
            hits.size,
        )
        val unpaired = hits.filter { !copyArgumentList(code, it).contains(SKIPPED_CLEARED) }
        assertTrue(
            "这些 `diff = null` 站点没把配套的 `skippedOccurrences = 0` 一起写 —— " +
                "两枚是 computeDiff 一次返回的同一对，漏一半就是让上一份 diff 的附属说明活到下一份之前" +
                "（本卡 T100① 刚修掉的就是 startCalendarSync 与 confirmCalendarSync 这两处）：" +
                lineHints(code, unpaired),
            unpaired.isEmpty(),
        )
        assertEquals(
            "`skippedOccurrences = 0` 的处数不等于 diff 清空站点的处数：成对清被打乱了",
            hits.size, occurrences(code, SKIPPED_CLEARED),
        )
    }

    /**
     * 钉这条链的两端：写侧只有一对、读侧只在弹窗里。
     *
     * 第二枚断言是"今天看不出错"的那句解释本身 —— 唯一读点长在 payload 驱动的弹窗块里，
     * `diff == null` 时整块不组合。把它钉住不是为了永久豁免，而是**挪动它就必须重新判一次**：
     * 渲染口径一改（比如把"跳过了 N 个课次"变成常驻状态行），漏清就从看不见的账变成念给用户的旧数。
     */
    @Test
    fun thePairedProducerIsTheOnlyWriterAndTheOnlyReaderSitsInsideTheDiffModal() {
        val code = blankCommentsKeepingLiterals(readMainSource(SCHEDULE_VIEW_MODEL))
        assertEquals(
            "diff 的写点应当恰好一处、且与 skippedOccurrences 同处（`computed.first` / `computed.second` " +
                "出自同一次 computeDiff）：写点变两处就是两把尺子",
            1, occurrences(code, "diff = computed.first"),
        )
        assertEquals(
            "skippedOccurrences 的生产者应当恰好一处，且与上面那枚 diff 写点成对",
            1, occurrences(code, "skippedOccurrences = computed.second"),
        )
        assertEquals(
            "computeDiff 的返回形状变了（Pair<Diff, Int> 是这两枚成对的唯一凭据）：$SHAPE_ANCHOR",
            1, occurrences(blankCommentsKeepingLiterals(readMainSource(CALENDAR_SYNC_MANAGER)), SHAPE_ANCHOR),
        )

        val screen = blankCommentsKeepingLiterals(readMainSource(SETTINGS_SCREEN))
        val reads = indexOfAll(screen, "calendarSync.skippedOccurrences")
        assertEquals("界面读 skippedOccurrences 的点数变了（现在只有弹窗里那一行的判据与文案两处）：", 2, reads.size)
        val modalAt = screen.indexOf(MODAL_ANCHOR)
        check(modalAt >= 0) { "找不到 $MODAL_ANCHOR：弹窗那层的驱动方式换过了，本守卫要跟着改" }
        val nextModal = screen.indexOf("ModalTransition(", modalAt + MODAL_ANCHOR.length)
        check(nextModal > modalAt) { "diff 弹窗之后找不到下一个 ModalTransition 边界，窗口切不出来" }
        val windowLength = nextModal - modalAt
        check(windowLength in WINDOW_MIN..WINDOW_MAX) {
            "diff 弹窗那一段的长度越界（$windowLength 不在 $WINDOW_MIN..$WINDOW_MAX）：窗口不是原来那块了，得重判"
        }
        for (at in reads) {
            assertTrue(
                "界面读 skippedOccurrences 的点跑到了 diff 弹窗之外 —— 那等于给一枚只在 diff 期间有意义的" +
                    "字段加了常驻读者，漏清的旧值就会真的念给用户听",
                at in modalAt until nextModal,
            )
        }
    }

    /**
     * T116（§8 表 #2）：「移除已同步的日程」那条链（`removeSyncedEvents`）起手**不收** `diff` /
     * `skippedOccurrences` 这一对 —— 而它今天不收是对的，凭的是**两扇弹窗彼此挡住对方的入口**（§8.4-B）。
     *
     * §8.4-B 末那句「现有那枚 diff 守卫**管不到**这一格」是这一卡的账本：第一枚判据数的是
     * `diff = null` 那类**清点点**（钉"3 处、每处都带 `skippedOccurrences = 0`"），而 `:1481` 根本不写
     * `diff` ⇒ 它不在那枚判据的宇宙里。本判据就是补那一格，判"不动 main"（理由见下面第三段）：
     *
     * 1. 这条链既不生产也不读那一对：`diff` 的唯一生产者是 `diff = computed.first`（上一枚判据钉着），
     *    `skippedOccurrences` 的唯一读者在 diff 弹窗之内（同一枚判据钉着）—— 移除链跟它们没有一次交集。
     * 2. 两扇窗彼此挡住：移除链的入口 `viewModel.requestRemoveSyncedEvents()` 与 diff 弹窗
     *    `ModalTransition(payload = calendarSync.diff)` 谁开着另一扇的入口都点不到 ⇒ 起手那一刻
     *    `diff` 必为 null。本判据按**落点**钉这一句：那枚入口在两扇窗**之外**（两格）、
     *    `dismissCalendarSyncDiff()` 的两枚收场路在 diff 窗**之内**（一格）。
     *    ⚠️ **T116 那一版里这里还有第三格「diff 那一段与移除框那一段互不重叠」，T118 判成恒真后删掉**
     *    （机制账写全，别让下一个人再把它当承重件）：它写的是
     *    `diffEnd <= removeAt || removeEnd <= diffAt`，而 `diffEnd` / `removeEnd` 取的是各自头之后
     *    **最近**的一枚 `ModalTransition(`，`DIFF_MODAL_HEAD` 与 `REMOVE_MODAL_HEAD` 本身又都以
     *    `ModalTransition(` 开头 ⇒ 在两枚头的计数各恰好为 1（就是上面那两格 `assertEquals` 钉的）的前提下，
     *    "另一扇窗若真落进这一扇之内，它就正好是这一扇的右边界"，两条不等式至少一条取等 ⇒ **恒真**。
     *    读数（改的都是本测试，main 一字未动）：① 未改动的盘面上这一格就是**取等**绿的 ——
     *    `diffEnd` 与 `removeAt` 同落 `SettingsScreen.kt:1941`（diff 窗的右边界正好就是移除框那一行），
     *    所以它今天绿得不讲道理，而不是"两扇窗真的分得开"；② 把 `diffAt` 推到 0（"diff 窗把两扇窗之外
     *    的输入也吞了"那一形状）⇒ 只有「入口落点」那一格红、这一格仍绿。
     *    「把移除入口真搬进 diff 窗内」那一臂红的是「入口落点」、也不是这一格 —— 那是编排者改 main 量的，
     *    本卡按红线没复跑。⇒ 它涨格子数、不涨覆盖面。
     *    要判"真嵌套"得改成按花括号配平取两扇窗各自的**块范围**再比包含关系，而"真嵌套"那一臂
     *    **只有改 `SettingsScreen.kt` 才造得出来** —— 本卡红线是 main 一字不许动 ⇒ 按卡面判档走 (B)：
     *    删格子、把账写在这里，那一格的位置由「入口落点 ×2 + 收场路落点」三格接住。
     * 3. **"照 `:1389` 的仪式把这一对一起收"那一支不采纳**：那不是清场，那是拿「移除」去**作废一份用户
     *    还没确认的 diff**。今天两扇窗互斥 ⇒ 那种 diff 不存在，补收是纯 no-op；而一旦上面第 2 条那两道
     *    位置判据被人改掉（给确认卡顺手补一颗「先移除再同步」，这是本仓另一种常见写法），补收就从
     *    "无害"变成"点一颗写着移除的按钮把用户正要确认的那份差异吞掉" —— 比它假装修的残值更糟。
     *    正确的落点是"移除完成之后要不要让那份 diff 作废"，那是产品判断，不是这一族的清场仪式。
     *    ⚠️ 另一枚成本：往 `:1481` 补 `diff = null` 会把本文件第一枚判据的宇宙从 3 处顶成 4 处，
     *    那一格的"3"是按 §6.3 那笔改前账钉的，两处都得重钉。
     *
     * 两头都钉：
     * - **朝宽**（把闸挪走）：那枚入口被搬进任一扇窗之内 → 两格落点判据红；`dismissCalendarSyncDiff()`
     *   的两枚收场路长出 diff 窗（或界面长出第三处）→ 最后一格红；
     *   `:1481` 被拆回 `it.copy(syncing = true)`（连 T110 那两枚都不收了）→ 逐字判据红；
     * - **朝紧**（把它改成收）：这条链三枚写点里任何一枚开始动 `diff` 或 `skippedOccurrences` →
     *   逐处负判据红，且起手那枚的实参表不再是逐字原文 → 同一枚判据再红一次。
     * ⚠️ 与 T115②′ 同一课：**逐处 + 按位置 + 逐字**取，不数出现次数了事 —— 这一族的假格子正是"枚数对、
     *   落点错"（收尾那枚改了起手那枚没改，count 层面完全看不出来）。
     */
    @Test
    fun theRemoveChainClearsNeitherDiffNorSkippedBecauseTheTwoModalsHoldEachOtherOff() {
        val code = blankCommentsKeepingLiterals(readMainSource(SCHEDULE_VIEW_MODEL))
        val screen = blankCommentsKeepingLiterals(readMainSource(SETTINGS_SCREEN))
        // ---- 这条链的骨架：体内三枚写点，逐处取实参表 ----
        val heads = indexOfAll(code, REMOVE_HEAD)
        assertEquals("靶子：`$REMOVE_HEAD` 不再是恰好 1 处：" + lineHints(code, heads), 1, heads.size)
        val head = heads.first()
        val bodyEnd = code.indexOf(MEMBER_FUN_SEP, head + REMOVE_HEAD.length)
        check(bodyEnd > head) {
            "靶子：`removeSyncedEvents` 之后找不到下一枚成员函数头 —— 这颗函数体切不出来，宿主判据要重写法"
        }
        val updates = indexOfAll(code, UPDATE_SITE).filter { it in head until bodyEnd }
        assertEquals(
            "`removeSyncedEvents` 体内的 `_calendarSync.update {` 写点不再是 3 处（关窗一枚 / 起手立旗一枚 / " +
                "收尾落旗一枚）。这一格是**按宿主切块**取的：「枚数还是 3、但其中一枚被搬进 helper 或搬去" +
                "别的函数」同样落不进这个区间，也就同样红：" + lineHints(code, updates),
            3,
            updates.size,
        )
        for ((index, at) in updates.withIndex()) {
            assertEquals(
                "这条链第 ${index + 1} 枚写点不再长在 `fun removeSyncedEvents()` 体内（现宿主：" +
                    precedingFunHead(code, at) + "）—— 抽 helper 或搬家，「它什么时候真的会跑」都要重推",
                REMOVE_HEAD,
                precedingFunHead(code, at),
            )
        }
        val args = updates.mapIndexed { index, at ->
            updateSiteArguments(code, at, if (index + 1 < updates.size) updates[index + 1] else bodyEnd)
        }
        assertEquals(
            "`:1481` 起手那枚 copy 的实参表不再是**逐字** `$REMOVE_ENTRY_ARGS`（现在是：" + args[1].trim() +
                "）—— 这一格钉的是 T110 那刀的**边界**：这条链起手收两枚（旗标 + 句子，归 " +
                "`CalendarSyncTargetPairingGuardTest` ③ 那一族管），**不收本文件这一对**。" +
                "**少一枚**（连 `syncing` 都不立了）= 移除在飞的时候那两颗按钮没人按得住；" +
                "**多收这一对** = KDoc 第三段那支「拿移除去作废一份没确认的 diff」，本卡驳回：" +
                "要改先回答第 2 条那两道位置判据为什么不红，再把上面第一枚判据的 3 处清点一起重钉",
            REMOVE_ENTRY_ARGS,
            args[1].trim(),
        )
        assertTrue("起手那枚不再撤句子（`message = null` 不在实参表里）—— T109 那枚漏清复发，" +
            "③那一族的「成对清 2 处」也会先红：" + args[1].trim(), args[1].contains(MESSAGE_CLEARED))
        for ((index, text) in args.withIndex()) {
            assertFalse(
                "这条链的第 ${index + 1} 枚写点开始动 `diff`（实参表：" + text.trim() + "）—— 它既不生产也不读" +
                    "那一枚（生产者是 `diff = computed.first`、读者在 diff 弹窗里），动它就是拿「移除」去作废一份" +
                    "用户还没确认的差异：今天不可达、改法却是产品判断不是清场仪式（KDoc 第三段）：" +
                    "上面那枚「`diff = null` 恰好 3 处」的清点判据同时红，两处都要重钉",
                text.contains(DIFF_CLEARED) || text.contains(DIFF_PRODUCED),
            )
            assertFalse(
                "这条链的第 ${index + 1} 枚写点开始动 `skippedOccurrences`（实参表：" + text.trim() +
                    "）—— 它与 diff 同一次生产，只动一枚正是本文件第一枚判据拦的形状；两枚一起动则是上面" +
                    "那格没拦住的那一步，本卡判的是「这条链压根不该碰这一对」",
                text.contains(SKIPPED_CLEARED) || text.contains("skippedOccurrences = computed"),
            )
        }
        assertTrue(
            "收尾那枚不再落旗（实参表：" + args[2].trim() + "）—— 这条链自己就是那两颗按钮的" +
                "`enabled = !calendarSync.syncing` 的来源，旗落不下来它们就永久按不动：",
            args[2].contains("syncing = false"),
        )
        assertTrue(
            "收尾那枚不再落「已移除 N 个日程 / 移除失败…」那支句子（`message = removed?.let` 不见了，实参表：" +
                args[2].trim() + "）—— 它就是重新点亮 `SettingsScreen.kt:1664` 那格 `visible` 的那一句，" +
                "§8.2 表 #2 那句「起手不收对岸、可完成句会把读者重新点亮」吃的正是它：",
            args[2].contains(REMOVE_TAIL_MESSAGE_SHAPE),
        )
        // ---- 前提：移除链的入口在两扇窗之外（「两扇窗互不重叠」那一格 T118 已删，理由见本判据 KDoc 第 2 条）----
        val diffModals = indexOfAll(screen, DIFF_MODAL_HEAD)
        assertEquals(
            "靶子：`$DIFF_MODAL_HEAD` 不再是恰好 1 处 —— diff 的渲染方式换了载体（改成常驻卡就是 §8.4-B 说的" +
                "「别处一行」，那一格本判据要反过来重判，上面第一枚判据的窗口那格也一起红）：" +
                lineHints(screen, diffModals),
            1,
            diffModals.size,
        )
        val removeModals = indexOfAll(screen, REMOVE_MODAL_HEAD)
        assertEquals(
            "靶子：`$REMOVE_MODAL_HEAD` 不再是恰好 1 处 —— 移除确认框换了载体，" +
                "「两扇窗彼此挡住对方的入口」这句话就没有静态凭据了：" + lineHints(screen, removeModals),
            1,
            removeModals.size,
        )
        val diffAt = diffModals.first()
        val diffEnd = screen.indexOf(MODAL_SEP, diffAt + DIFF_MODAL_HEAD.length)
        val removeAt = removeModals.first()
        val removeEnd = screen.indexOf(MODAL_SEP, removeAt + REMOVE_MODAL_HEAD.length)
        check(diffEnd > diffAt && removeEnd > removeAt) { "靶子：两扇弹窗的窗口边界切不出来（少一层 ModalTransition？）" }
        // 这两枚窗口只拿来判下面那三格的**落点**，不拿来判"两扇窗彼此重不重叠"：那一格在 T116 那一版里
        // 存在过、T118 判成恒真删掉了（机制账写在本判据 KDoc 第 2 条，别照着旧样子再造一枚）。
        val opens = indexOfAll(screen, REMOVE_REQUEST_CALL)
        assertEquals(
            "界面上 `$REMOVE_REQUEST_CALL` 的调用点不再是 1 处（现在只有 :1654 那颗「移除」行，而它在 " +
                "`withCalendarPermission` 那道闸里）：" + lineHints(screen, opens),
            1,
            opens.size,
        )
        for (at in opens) {
            assertFalse(
                "**朝宽那一格**：移除那一行的入口跑进了 diff 弹窗**之内**（" + lineAt(screen, at) +
                    " 落在 L${screen.substring(0, diffAt).count { it == '\n' } + 1} 到 " +
                    "L${screen.substring(0, diffEnd).count { it == '\n' } + 1} 之间）—— 那就是「确认卡上顺手补" +
                    "一颗先移除再同步」的形状：两扇窗不再互斥、起手不收这一对当场从「结构不可能」搬进「真漏清」，" +
                    "而那时要重判的不是本判据、是 KDoc 第三段那句「这一对到底该谁收」",
                at in diffAt until diffEnd,
            )
            assertFalse(
                "移除那一行的入口跑进了移除确认框之内 —— 开窗点自己长在窗里，:1471 那枚唯一写点的账要重推",
                at in removeAt until removeEnd,
            )
        }
        // ---- diff 这一对的收场路全在弹窗之内（本文件宇宙的门）----
        val dismisses = indexOfAll(screen, DISMISS_DIFF_CALL)
        assertEquals(
            "界面上 `viewModel.dismissCalendarSyncDiff()` 的调用点不再是 2 处（:1906 那扇窗的 onDismissRequest、" +
                ":1935 那颗「取消」）。**多一处** = 又添一条能把 diff 收掉的通道，它同样得成对清" +
                "（上面第一枚判据按 VM 侧数，数不到界面这一头）：" + lineHints(screen, dismisses),
            2,
            dismisses.size,
        )
        for (at in dismisses) {
            assertTrue(
                "那一处 `dismissCalendarSyncDiff()` 跑出了 diff 弹窗 —— diff 的收场路不再只在窗内，" +
                    "「移除链与 diff 彼此看不见」这句前提要按新落点重推：" + lineAt(screen, at),
                at in diffAt until diffEnd,
            )
        }
    }

    // ---- 源码核对小工具（抄 SpecialDayRefreshWiringGuardTest / PendingImportConflictGroupTest）----
    /** 每次命中所在行的行号与原文：失败消息要能把人带到那一处，只报个数等于没有守卫 */
    private fun lineHints(source: String, positions: List<Int>): String {
        if (positions.isEmpty()) return ""
        return "\n" + positions.joinToString("") { at ->
            val start = source.lastIndexOf('\n', at).let { if (it < 0) 0 else it + 1 }
            val end = source.indexOf('\n', at).let { if (it < 0) source.length else it }
            "  L${source.substring(0, at).count { it == '\n' } + 1}: " +
                source.substring(start, end).trim() + "\n"
        }
    }

    /**
     * 从 [hit] 往前找包围它的那次 `copy(`，再按括号配平（跳过字符串字面量）取出实参表。
     *
     * 不用"同一行"当判据：本仓这种写法换行很常见（T100① 那两处就是为了不让单行过宽而折的行）。
     * 找不到 `copy(` 就抛 —— 静默跳过等于没有守卫。
     */
    private fun copyArgumentList(source: String, hit: Int): String {
        val open = source.lastIndexOf("copy(", hit)
        check(open >= 0) { "`diff = null` 这一处往前找不到包围它的 copy(：写法换过了，本守卫要跟着改" }
        var depth = 0
        var index = open + "copy".length
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
                        val args = source.substring(open + "copy(".length, index)
                        check(hit in (open + 5)..index) { "取出的 copy 实参表不含那一处 diff = null：配平跑偏了" }
                        return args
                    }
                }
            }
            index++
        }
        throw IllegalStateException("copy( 的括号没配平：$DIFF_CLEARED 那一处切不出实参表")
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

    /**
     * [hit] 之前**最近的那一枚成员函数头**整行（去首尾空白）—— 本仓类体成员一律缩进 4 空格，
     * 所以 `\n    fun ` 是足够硬的分隔符。与 `CalendarSyncTargetPairingGuardTest` 同名工具同一把刀：
     * 宿主一换（抽进 helper、搬去别的函数）它就直接给出**实际宿主**当证据，而不只是红。
     */
    private fun precedingFunHead(source: String, hit: Int): String {
        check(hit in source.indices) { "命中位置越界：$hit / ${source.length}" }
        val cut = source.lastIndexOf(MEMBER_FUN_SEP, hit)
        check(cut >= 0) { "那一处之前找不到成员函数头 —— 它已经不在任何 fun 体内了，宿主判据要重写法" }
        val from = cut + 1
        val to = source.indexOf('\n', from).let { if (it < 0) source.length else it }
        return source.substring(from, to).trim()
    }

    /** 单行版：断言只点名一处站点时用，报错里直接给「L 几 + 原文」 */
    private fun lineAt(source: String, hit: Int): String =
        "  L${source.substring(0, hit).count { it == '\n' } + 1}: " +
            source.substring(
                source.lastIndexOf('\n', hit).let { if (it < 0) 0 else it + 1 },
                source.indexOf('\n', hit).let { if (it < 0) source.length else it },
            ).trim()

    /**
     * 那一处 `_calendarSync.update {` **之内**那次 `copy(` 的实参表；[limit] 是下一枚写点（或函数体末尾）。
     *
     * `copy(` 必须落在 `[updateAt, limit)` 之内才认 —— 这一条是"逐处"两字的落实：拿"同一行"或
     * "全局搜一遍"当判据都会把隔壁那枚写点的实参表误交到手上来（这条链三枚写点里两枚都含 `syncing =`）。
     */
    private fun updateSiteArguments(source: String, updateAt: Int, limit: Int): String {
        val open = source.indexOf("copy(", updateAt)
        check(open in updateAt until limit) {
            "那一处 " + lineAt(source, updateAt) + " 到下一枚写点之间没找到 copy( —— 写法换过了" +
                "（直接赋值？抽成函数？中间塞了第二枚 copy？），本守卫要跟着改"
        }
        return copyArgumentList(source, open + "copy(".length)
    }

    private fun readMainSource(relativeFromJava: String): String {
        val file = File(findMainJavaDir(), relativeFromJava)
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
     * 两种现成的刀法在这张卡上都不可用，所以自己走一遍：
     * - `SpecialDayRefreshWiringGuardTest.blankComments`（块注释整段换成一个空格）会挪动行号，
     *   失败消息里的行号就成了假的；
     * - `CalendarModeClassBellCleanupTest.blankCommentsAndLiterals`（连字面量内容一起抹）会把
     *   `SettingsScreen.kt:1916` 那句 `"有 ${calendarSync.skippedOccurrences} 个课次…"` 一起抹掉，
     *   界面唯一读点的两处就数出一处。
     * 反过来，`SettingsScreen.kt:1167` 那个**写在字符串字面量里**的块注释开头两个字符，若按
     * "不认字面量的行扫描"处理会把后面的代码整段吞掉（同族那条警告说的就是这件事），所以这里
     * 照 `blankCommentsAndLiterals` 的走法认字面量，只是**不抹它的内容**。
     *
     * 留下的口子如实记：若有人把 `diff = null` 写进字符串字面量，本守卫会把它数成一处清空站点。
     * 数不对时失败消息会连行号与原文一起打出来，一眼能看出数是哪儿来的。
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
        const val DIFF_CLEARED = "diff = null"
        const val SKIPPED_CLEARED = "skippedOccurrences = 0"
        const val MODAL_ANCHOR = "ModalTransition(payload = calendarSync.diff)"
        const val SHAPE_ANCHOR = "fun computeDiff(calendarId: Long): Pair<CalendarSyncPlanner.Diff, Int>?"

        // ---- T116（§8 表 #2）：`removeSyncedEvents` 这一格零守卫那一条 ----
        const val REMOVE_HEAD = "fun removeSyncedEvents() {"
        const val REMOVE_ENTRY_ARGS = "syncing = true, message = null, permissionPermanentlyDenied = false"
        const val REMOVE_TAIL_MESSAGE_SHAPE = "message = removed?.let"
        const val REMOVE_REQUEST_CALL = "viewModel.requestRemoveSyncedEvents()"
        const val DISMISS_DIFF_CALL = "viewModel.dismissCalendarSyncDiff()"
        const val UPDATE_SITE = "_calendarSync.update {"
        const val MEMBER_FUN_SEP = "\n    fun "
        const val DIFF_PRODUCED = "diff = computed.first"
        const val DIFF_MODAL_HEAD = "ModalTransition(payload = calendarSync.diff)"
        const val REMOVE_MODAL_HEAD = "ModalTransition(open = calendarSync.showRemoveConfirm)"
        const val MODAL_SEP = "ModalTransition("
        const val MESSAGE_CLEARED = "message = null"

        /** diff 弹窗那一段的合理长度带：现在实测 1,740 字符（含标题到下一个 ModalTransition） */
        const val WINDOW_MIN = 200
        const val WINDOW_MAX = 6_000
    }
}
