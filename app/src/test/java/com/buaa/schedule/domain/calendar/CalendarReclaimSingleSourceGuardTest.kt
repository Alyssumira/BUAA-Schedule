package com.buaa.schedule.domain.calendar

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T265④：把「所有权标记必须又写又读」与「回收范围不许只认映射表」钉成源码形状守卫。
 *
 * **本卡用的钉法点名（本仓四种）**：
 * - 第 ① 层 = **数出现次数**（禁物枚数全部钉成 0）+ **逐字节**（四枚判据的返回类型原文）；
 * - 第 ② 层 = **数出现次数**（全 main 树扫）+ **逐字节**（写侧与读侧各一枚原文）——
 *   **这一层就是拦住「所有权标记又变成只写不读」的那枚钉子**；
 * - 第 ③ 层 = **抹注释找锚点**（`removeAllSyncedEvents` 那颗函数体按花括号配平切块）+ **读形状**
 *   （三档 `when` 分支原文都在块内，`NothingOurs -> emptyList()` 少一枚就红）；
 * - 第 ④ 层 = **逐字节**（回滚那一手的四枚原文）+ **数出现次数**（`deleteOwnedEvents` 的调用点册子）；
 * - 第 ⑤ 层 = **读形状**（六档句子在 VM 里各有且只有一张嘴）+ **逐字节**（屏上那句承诺与新增的认领句）；
 * - 第 ⑥ 层 = **数出现次数**（尺子自己的承重锚点下限，反套套逻辑：读空文件一律红）。
 *
 * **不许写死行号**：本文件一条行号都不钉，全部按符号原文 + 花括号块 + 枚数取。
 * 每条判据的报错消息都点名**文件**与**判据**，并写清「朝宽/朝窄各是谁」。
 *
 * ## 这枚守卫拦的是哪五步退路
 *
 * 1. 把标记那一列的读点删掉（或把 selection 改成别的列）⇒ 屏上那句「将删除本应用创建的全部日历事件」
 *    又回到兑不了的状态，孤儿继续留着而同步继续重复插（本卡病灶复发）；
 * 2. 把读点**放宽**（selection 里加 `OR` / 第二枚列 / 按日历收窄后反过来当兜底）⇒ 删到用户自己建的日程；
 * 3. 把内核的判据改成读钟 / 读时区 / 读权限 / 读 `Build.VERSION`，或把分档折成 Boolean ⇒ 对撞本仓成文禁令
 *    （这些事实只能在调用点当参数传入），并让「念哪句」失去可测的分档；
 * 4. 把 `flushMappings` 失败那一档的**当场撤回**摘掉 ⇒ 回到"事件写了、映射没落、无人再管"；
 * 5. 把失败档的句子又折回一句泛泛的「N 个日程未写入」⇒ 用户按那句去再点一次同步，
 *    而那一手正好是往同一个日历上再插一批重复。
 */
class CalendarReclaimSingleSourceGuardTest {

    // ---------------- ① 内核纯度与分档 ----------------

    /**
     * 钉法 = **数出现次数**（禁物 0 枚）+ **逐字节**（返回类型原文）。
     *
     * 四枚判据的返回类型必须还是那四个分档名，而不是 `Boolean` / `Int`；
     * 内核本体必须零 android、零时钟、零时区、零权限、零 `Build.VERSION`、零 ContentResolver
     * —— 这些事实只能在调用点当参数递进来（本仓明文禁令，台账 #265 那一族的成因）。
     * 两头都钉：把 `planReclaim` 改成返回 Boolean ⇒ 逐字节那格红 + 另一格"返回值分档名册子"红；
     * 在内核里补一句 `System.currentTimeMillis()` 想"顺手算 syncedAt" ⇒ 禁物那格 0→1 红。
     */
    @Test
    fun theKernelStaysPureJvmAndKeepsItsFourTieredVerdicts() {
        val code = blankCommentsKeepingLiterals(readMainSource(KERNEL_FILE))
        for (forbidden in FORBIDDEN_IN_KERNEL) {
            assertEquals(
                "内核 $KERNEL_FILE 里出现了禁物「$forbidden」（本仓禁令：判据本体不许读时钟/时区/权限/" +
                    "`Build.VERSION`/ContentProvider，这些只能由调用点当参数递进来）。" +
                    "改这一格之前先回看内核 KDoc「纯度」那一段：" + lineHints(code, indexOfAll(code, forbidden)),
                0,
                occurrences(code, forbidden),
            )
        }
        assertEquals(
            "内核里 android/androidx 的 import 必须是 0 枚（它是 domain 层的纯 JVM 判据）：",
            0,
            occurrences(code, ANDROID_IMPORT),
        )
        for (verdict in VERDICT_RETURN_TYPES) {
            assertEquals(
                "判据返回类型原文「$verdict」在内核里应当恰好 1 处（四枚判据一人一枚档）。" +
                    "少一枚 = 那一枚判据被折成了 Boolean/Int（本卡明令不许），多一枚 = 长出了第二把尺：" +
                    lineHints(code, indexOfAll(code, verdict)),
                1,
                occurrences(code, verdict),
            )
        }
        assertEquals(
            "内核里**没有**任何一枚判据以 Boolean 收尾（`): Boolean` 必须是 0 处）。" +
                "「删/不删/按标记查/回滚自己刚写的/念哪句」这五件事折成一枚 Boolean，" +
                "就是把「去重成功」与「判据说不出话」念成同一句话：",
            0,
            occurrences(code, BOOLEAN_RETURN),
        )
        assertEquals(
            "四枚判据的函数头原文各恰 1 处（改名/搬走 = 本守卫与调用点要一起重钉，而 domain/calendar 这一族" +
                "全仓只许这一枚内核）：",
            listOf(1, 1, 1, 1),
            KERNEL_ENTRIES.map { occurrences(code, it) },
        )
    }

    /**
     * 钉法 = **数出现次数**（全 main 树）：domain/calendar 这一族在 main 里只许有一枚内核文件。
     *
     * 拦的是"第二把尺"那一步退路（同族先例：`TermWeekGate` 搬家的理由）。谁在 data/ui 层再写一枚
     * "按标记判断归属"的小判据，这一格先红 —— 因为两把尺各自错方向是这一族病灶的常见收场。
     */
    @Test
    fun thereIsExactlyOneOwnershipKernelInMainAndItLivesInDomain() {
        val files = mainFilesMentioning(KERNEL_SYMBOL)
        assertEquals(
            "全 main 里引用 `$KERNEL_SYMBOL` 的文件不恰好是【内核本体 + 契约 + 两处调用点】那四枚 —— " +
                "多一枚 = 又有一条链自己算删除范围或自己念句子（第二把尺）；" +
                "少一枚 = 有调用点不问它了（本卡修的那件事在退）：",
            listOf(DOMAIN_KERNEL, DATA_OWNERSHIP_QUERY, CALENDAR_SYNC_MANAGER, SCHEDULE_VIEW_MODEL).sorted(),
            files,
        )
    }

    // ---------------- ② 所有权标记：必须又写又读 ----------------

    /**
     * 钉法 = **数出现次数** + **逐字节**。**这一层是本卡的题眼**：
     * `CUSTOM_APP_PACKAGE` 在改前是全仓唯一一枚所有权标记，而它**只写不读**
     * （main 出现 1 次、test 出现 0 次、按 selection 查回的读点 0 处）⇒「删除本应用创建的全部日历事件」
     * 兑不了，孤儿永久留在用户日历上。
     *
     * 三枚判据互为备份：
     * - 全 main 树**总数**恰 2（写 1 + 读 1）：只写不读 ⇒ 2→1 红；只读不写 ⇒ 也 2→1 红（那更坏，
     *   新写入的事件从此不带标记，按标记枚回来永远是空集）；
     * - 写侧那一句原文恰 1、读侧那一句原文恰 1：**换掉其中任何一句的写法**（改成 `SYNC_DATA1`、
     *   改成拼包名进串、改成不带 `?`）⇒ 相应那格红；
     * - 点名两枚文件各持一枚（写侧在 `CALENDAR_SYNC_MANAGER`、读侧在 `DATA_OWNERSHIP_QUERY`）：
     *   把读侧搬进别的载体 ⇒ 逐文件那两格红。
     */
    @Test
    fun theOwnershipMarkerIsWrittenOnceAndReadOnceAcrossMain() {
        assertEquals(
            "全 main 里 `$MARKER_COLUMN` 的出现次数不再是 2（写侧一枚 + 读侧一枚）。" +
                "**1 = 标记又变成只写不读**（本卡病灶原地复发，屏上那句承诺又兑不了）；" +
                "**3 以上 = 多了一把尺在按标记判归属**（每加一枚都要先问它会不会把范围放宽到用户自己的日程）。" +
                "实到读数：\n" + markerRegister(),
            MARKER_SITES,
            mainOccurrences(MARKER_COLUMN),
        )
        val writer = blankCommentsKeepingLiterals(readMainSource(CALENDAR_SYNC_MANAGER))
        assertEquals(
            "写侧原文「$MARKER_WRITE」在 $CALENDAR_SYNC_MANAGER 里不再恰好 1 处 —— 每一枚我们创建的事件" +
                "都必须带标记，少一处（例如只对某一批事件写）就是让回收那一手枚不到它：" +
                lineHints(writer, indexOfAll(writer, MARKER_WRITE)),
            1,
            occurrences(writer, MARKER_WRITE),
        )
        val reader = blankCommentsKeepingLiterals(readMainSource(DATA_OWNERSHIP_QUERY))
        assertEquals(
            "读侧原文「$MARKER_READ」在 $DATA_OWNERSHIP_QUERY 里不再恰好 1 处 —— 这一枚就是「标记读得回来」" +
                "这件事的把手；它没了，removeAllSyncedEvents 就又退回「删除范围只认自己的映射表」：" +
                lineHints(reader, indexOfAll(reader, MARKER_READ)),
            1,
            occurrences(reader, MARKER_READ),
        )
        // 朝宽那一侧的三格：selection 只许等值匹配本包名
        assertEquals(
            "selection 的实参表只许构造一枚参数（多一枚就是在放宽或收窄归属判据）：",
            1,
            occurrences(reader, SELECTION_ARG),
        )
        assertEquals(
            "投影里只许有 `_ID` 一枚列（出现第二枚列 = 有人在拿「看着像我们建的」当判据，" +
                "那种模糊判据删到的是用户自己建的日程）：",
            1,
            occurrences(reader, PROJECTION_ID),
        )
        for (widening in WIDENING_NEEDLES) {
            assertEquals(
                "读侧契约里不许出现「$widening」（朝宽那一刀：兜底第二遍 / OR / 模糊匹配 / 按日历收窄，" +
                    "四把刀任何一把落进 selection 或投影，删错人的日程就从" +
                    "「理论可能」变成「今天这条路真的会走到」）：" +
                    lineHints(reader, indexOfAll(reader, widening)),
                0,
                occurrences(reader, widening),
            )
        }
    }

    /** 报错里交回实到读数：哪枚文件、哪一行、原文是什么。 */
    private fun markerRegister(): String = mainFilesWith(MARKER_COLUMN).joinToString(separator = "\n") {
        "  ${it.first}: ${it.second} 处"
    }

    // ---------------- ③ 回收范围：不再只认映射表 ----------------

    /**
     * 钉法 = **抹注释找锚点**（按花括号配平切 `removeAllSyncedEvents` 那颗函数体）+ **读形状**。
     *
     * 块内必须同时有：映射表那一读（还在，但它已不是唯一真相）、按标记那一读、内核那一次裁范围、
     * 以及三档 `when` 分支的原文。两头都钉：
     * - **朝窄**（把范围收回映射表）：删掉 `CalendarOwnershipQuery.fold(` ⇒ 锚点 1→0 红；
     *   把 `getCalendarSyncEntriesFor(`（按目标日历收窄）请进这颗块 ⇒ 它 0→1 红，
     *   那是"换过目标日历的用户永远删不掉旧日历上那些"的另一种写法；
     * - **朝宽**（把 `NothingOurs` 那一档折掉）：三档原文少一枚就红 —— 硬门槛
     *   「用户从没同步过直接点移除，一枚都不许动」靠的就是 `NothingOurs -> emptyList()` 那一行。
     */
    @Test
    fun theReclaimScopeComesFromTheMarkerPlusMappingsAndNeverFromACalendarNarrowing() {
        val manager = blankCommentsKeepingLiterals(readMainSource(CALENDAR_SYNC_MANAGER))
        val body = balancedBlock(manager, RECLAIM_HEAD)
        assertEquals(
            "回收那一手的映射表读取点不再是恰 1 处（它仍是**第二来源与加速缓存**，但不再是唯一真相）：" +
                lineHints(body, indexOfAll(body, MAPPING_READ)),
            1,
            occurrences(body, MAPPING_READ),
        )
        assertEquals(
            "回收那一手不再按所有权标记枚 provider（`$MARKER_FOLD` 实到 " +
                occurrences(body, MARKER_FOLD) + " 处）—— 这正是本卡的病灶：「有事件、无映射」那批" +
                "永久留在用户日历上，下次同步把同批课次再插一遍：" + lineHints(body, indexOfAll(body, MARKER_FOLD)),
            1,
            occurrences(body, MARKER_FOLD),
        )
        assertEquals(
            "回收范围不再由 domain 内核裁（`$PLAN_CALL` 实到 " + occurrences(body, PLAN_CALL) +
                " 处）—— 在这一颗函数体里自己算集合就是第二把尺：" +
                lineHints(body, indexOfAll(body, PLAN_CALL)),
            1,
            occurrences(body, PLAN_CALL),
        )
        for (branch in PLAN_BRANCHES) {
            assertEquals(
                "回收那一手的三档分支原文「$branch」不在块里恰 1 处 —— 三档是" +
                    "「按标记回收 / 只按映射回收 / 一枚都不动」，缺一枚就是把某一档折进另一档。" +
                    "**少了 NothingOurs 那一档 = 「用户从没同步过、直接点删除」开始删到用户自己的日程**：" +
                    lineHints(body, indexOfAll(body, branch)),
                1,
                occurrences(body, branch),
            )
        }
        assertEquals(
            "朝窄那一刀：回收那一手里不许出现按目标日历取映射的 `$NARROWING_READ`" +
                "（范围必须跨日历；目标日历 id 在这一手只许用来**报数**，不许用来收窄）：" +
                lineHints(body, indexOfAll(body, NARROWING_READ)),
            0,
            occurrences(body, NARROWING_READ),
        )
        assertEquals(
            "目标日历那一枚实参必须还被传进内核的报数判据（`$OUTSIDE_TARGET_ARG` 恰 1 处）—— " +
                "它不参与裁范围，但它是「你换过目标日历、旧日历上可能有认不回的日程」那一句的凭据；" +
                "谁把它删了，屏上那句范围警告就成了空话：",
            1,
            occurrences(body, OUTSIDE_TARGET_ARG),
        )
        assertEquals(
            "回收那一手起手必须留两行 Log.i（本仓已知 HyperOS 把 logcat 截在 Info 级 ⇒ Log.d 等于没写）：" +
                "一枚报「按标记枚回几枚 / 映射记着几枚 / 本次删几枚」，一枚报盲收那一档 —— " +
                "装机上判据走错方向只能从这两行读：" + lineHints(body, indexOfAll(body, LOG_I)),
            2,
            occurrences(body, LOG_I),
        )
        assertEquals(
            "起手那行 Log.i 的原文（「$RECLAIM_LOG」）必须还在，它是上面那格 2 枚的凭据之一：" +
                lineHints(body, indexOfAll(body, RECLAIM_LOG)),
            1,
            occurrences(body, RECLAIM_LOG),
        )
    }

    // ---------------- ④ 写侧：落库失败当场撤回自己刚写的 ----------------

    /**
     * 钉法 = **逐字节**（四枚原文）+ **数出现次数**（调用点册子）。
     *
     * 拦的是"乙"那半被摘掉：`flushMappings` 交回 false 之后什么都不做（改前的形状就是
     * `else failed += upserts.size`），事件已经写进用户日历而映射没落，从此无人认领。
     * 现在那一条路必须经过内核的分档 + 当场撤回，且撤回失败的那批要升格成孤儿嫌疑
     * （`tallyOf(..., rollbackSucceeded = rollbackOk)` 里那个 rollbackOk —— 把失败的撤回念成
     * 撤回成功就是第二句谎话）。
     */
    @Test
    fun aFailedMappingFlushRollsBackTheEventsWeJustWrote() {
        val manager = blankCommentsKeepingLiterals(readMainSource(CALENDAR_SYNC_MANAGER))
        for (anchor in WRITE_SIDE_ANCHORS) {
            assertEquals(
                "写侧原文「$anchor」在 $CALENDAR_SYNC_MANAGER 里不再恰好 1 处 —— 这一族四枚原文是一条链：" +
                    "解析不出 id 的那批留痕（unnamed++）→ 落库档位（MappingFlush）→ 内核裁档 → " +
                    "点得出名的当场撤回（rollbackOk）。少一枚就是有一档被折掉了：" +
                    lineHints(manager, indexOfAll(manager, anchor)),
                1,
                occurrences(manager, anchor),
            )
        }
        assertEquals(
            "`$TALLY_CALL` 的调用点不再是 2 处（新增成功那一路 + 批次被拒那一路）—— " +
                "报数只能从内核折，不许在管理器里手加：" +
                lineHints(manager, indexOfAll(manager, TALLY_CALL)),
            2,
            occurrences(manager, TALLY_CALL),
        )
        assertEquals(
            "`deleteOwnedEvents(` 在 main 里不再恰好 2 处（1 枚定义 + 1 枚调用）—— " +
                "多一枚 = 第二条删用户日历事件的路（每条都得先证它删的范围来自内核）；" +
                "少一枚 = 当场撤回那一手没了（病灶复发）或定义被搬走：",
            2,
            mainOccurrences(DELETE_HELPER),
        )
        assertEquals(
            "两本新账落地那一句原文变了（`$APPLY_RESULT_TAIL`）—— 孤儿账与撤回账必须一起进 ApplyResult，" +
                "少一本屏上那句就念不出来：" + lineHints(manager, indexOfAll(manager, APPLY_RESULT_TAIL)),
            1,
            occurrences(manager, APPLY_RESULT_TAIL),
        )
        assertEquals(
            "孤儿账的累加原文变了（`$ORPHAN_ACCUMULATE`）—— 它换成手加数字就是第二把尺：" +
                lineHints(manager, indexOfAll(manager, ORPHAN_ACCUMULATE)),
            1,
            occurrences(manager, ORPHAN_ACCUMULATE),
        )
    }

    // ---------------- ⑤ 屏上：失败档要说实话 ----------------

    /**
     * 钉法 = **读形状**（六档句子各一张嘴）+ **逐字节**（屏上那句承诺与新增的认领句）。
     *
     * 「念哪句」的分档住在内核，而**句子本身住在调用点**（domain 层不产文案）。所以这一层钉的是：
     * 六档 RemovalCopy 在 VM 里各出现 1 次（缺一档 = 有档没人念 = 那条残态静默），
     * 两档新增的同步失败句子原文各 1 次，以及既有三枚在册守卫的靶子原文（复述一遍，
     * 让"改句子"这件事在两枚守卫里同时看得见）。
     */
    @Test
    fun theFailureTiersAreEachSpokenByExactlyOneSentence() {
        val code = blankCommentsKeepingLiterals(readMainSource(SCHEDULE_VIEW_MODEL))
        val screen = blankCommentsKeepingLiterals(readMainSource(SETTINGS_SCREEN))
        for (tier in REMOVAL_COPY_BRANCHES) {
            assertEquals(
                "句子档「$tier」在 $SCHEDULE_VIEW_MODEL 里不再恰好 1 处 —— 六档各有且只有一张嘴。" +
                    "**少一档** = 那一档的残态在屏上静默（用户以为都删干净了）；**多一档** = " +
                    "同一档有两处措辞，两处会各自漂：",
                1,
                occurrences(code, tier),
            )
        }
        for (anchor in SYNC_FAILURE_SENTENCES) {
            assertEquals(
                "同步失败档的原文「$anchor」不再恰好 1 处 —— 这一族是「让失败档在屏上说实话」那一半：" +
                    "孤儿那一档必须单独念（否则用户再点一次同步 = 往同一个日历上再插一批重复），" +
                    "撤回那一档不许念成「从未写过」：" + lineHints(code, indexOfAll(code, anchor)),
                1,
                occurrences(code, anchor),
            )
        }
        assertEquals(
            "回收那一手的调用点原文不再是 1 处（它现在要吃目标日历 id 去**报数**；" +
                "谁把它换回无参写法，「你换过目标日历」那一句警告就没了来源）：" +
                lineHints(code, indexOfAll(code, RECLAIM_CALL)),
            1,
            occurrences(code, RECLAIM_CALL),
        )
        // 既有三枚在册守卫（CalendarSyncTargetPairingGuardTest / CalendarSyncDiffClearPairingGuardTest）的靶子
        assertEquals(
            "靶子复述：完成句「$REMOVE_DONE」恰 1 处 —— 它是 T109/T110 两枚在册守卫点名的原文，" +
                "本卡只许 AllReclaimed 那一档用它：" + lineHints(code, indexOfAll(code, REMOVE_DONE)),
            1,
            occurrences(code, REMOVE_DONE),
        )
        assertEquals(
            "靶子复述：异常句「$REMOVE_THREW」恰 1 处（suspendCatching 交不回结果那一档）：" +
                lineHints(code, indexOfAll(code, REMOVE_THREW)),
            1,
            occurrences(code, REMOVE_THREW),
        )
        assertEquals(
            "屏上那句承诺「$REMOVE_PROMISE」恰 1 处 —— 本卡让它从兑不了变成兑得了（范围按标记枚回），" +
                "所以它必须还在；如果哪天回收又退回只认映射表，该改的是这一句的措辞，" +
                "而不是让第 ② 层那枚钉子沉默：" + lineHints(screen, indexOfAll(screen, REMOVE_PROMISE)),
            1,
            occurrences(screen, REMOVE_PROMISE),
        )
        assertEquals(
            "认领句「$CLAIM_SENTENCE」恰 1 处 —— 它把「本应用创建的」按什么认领念给用户，" +
                "是朝宽那一侧唯一一句写在屏上的排除声明（不看你自己在日历里建的日程）：" +
                lineHints(screen, indexOfAll(screen, CLAIM_SENTENCE)),
            1,
            occurrences(screen, CLAIM_SENTENCE),
        )
        assertEquals(
            "确认框的载体 `$REMOVE_MODAL` 必须还是恰 1 处（换载体 = 上面两格与 T110/T116 那两枚在册守卫" +
                "的窗口判据一起要重钉）：",
            1,
            occurrences(screen, REMOVE_MODAL),
        )
    }

    // ---------------- ⑥ 尺子自己要有格子（反套套逻辑） ----------------

    /**
     * 钉法 = **数出现次数**（下限）。
     *
     * 上面五层全靠"读对工作树里的那四份文件"。读错文件、整份读空、路径挪过家 —— 那些判据大多
     * 会一起变成"0 == 0"的绿。这一层把四枚文件各自的承重锚点枚数钉成**下限**，
     * 词法一退化就当场红（同族先例：UserVisibleParenBalanceGuardTest 档 ① 末尾那两格）。
     */
    @Test
    fun theRulerIsReallyReadingTheseFourFiles() {
        val kernel = blankCommentsKeepingLiterals(readMainSource(DOMAIN_KERNEL))
        val query = blankCommentsKeepingLiterals(readMainSource(DATA_OWNERSHIP_QUERY))
        val manager = blankCommentsKeepingLiterals(readMainSource(CALENDAR_SYNC_MANAGER))
        val code = blankCommentsKeepingLiterals(readMainSource(SCHEDULE_VIEW_MODEL))
        assertTrue(
            "$DOMAIN_KERNEL 至少要有 4 枚判据函数头（读到 0 = 扫的不是这颗文件）：实到 " +
                KERNEL_ENTRIES.count { balancedHead(kernel, it) },
            KERNEL_ENTRIES.count { balancedHead(kernel, it) } >= 4,
        )
        assertTrue(
            "$DATA_OWNERSHIP_QUERY 里 marker 三档的原文至少要各在场（读到 0 = 契约换了载体）：",
            MARKER_READ_TIERS.count { occurrences(query, it) >= 1 } == MARKER_READ_TIERS.size,
        )
        assertTrue(
            "$CALENDAR_SYNC_MANAGER 至少要读到 20 枚 `CalendarContract.`（本卡改的是它，读空文件会给 0）：" +
                " 实到 ${occurrences(manager, "CalendarContract.")}",
            occurrences(manager, "CalendarContract.") >= 20,
        )
        assertTrue(
            "$SCHEDULE_VIEW_MODEL 里 `AppMessage(` 的读者至少要 40 枚（本卡给它添了六档句子）：" +
                " 实到 ${occurrences(code, "AppMessage(")}",
            occurrences(code, "AppMessage(") >= 40,
        )
    }

    private fun balancedHead(source: String, needle: String): Boolean =
        occurrences(source, needle) == 1

    private companion object {
        const val DOMAIN_KERNEL = "com/buaa/schedule/domain/calendar/CalendarReclaimPlanner.kt"
        const val DATA_OWNERSHIP_QUERY = "com/buaa/schedule/data/calendar/CalendarOwnershipQuery.kt"
        const val CALENDAR_SYNC_MANAGER = "com/buaa/schedule/data/calendar/CalendarSyncManager.kt"
        const val SCHEDULE_VIEW_MODEL = "com/buaa/schedule/ui/ScheduleViewModel.kt"
        const val SETTINGS_SCREEN = "com/buaa/schedule/ui/settings/SettingsScreen.kt"
        const val KERNEL_FILE = DOMAIN_KERNEL
        const val KERNEL_SYMBOL = "CalendarReclaimPlanner"

        /** 第 ① 层的禁物：内核本体里一个都不许出现（抹注释后取，KDoc 里提到不算）。 */
        val FORBIDDEN_IN_KERNEL = listOf(
            "import android",
            "import androidx",
            "System.currentTimeMillis",
            "LocalDate.now",
            "ZoneId",
            "TimeZone",
            "Build.VERSION",
            "checkSelfPermission",
            "contentResolver",
            "applyBatch",
            "CalendarContract",
        )
        const val ANDROID_IMPORT = "import android"
        val VERDICT_RETURN_TYPES = listOf(
            "): ReclaimPlan {",
            "): RemovalCopy = when {",
            "): InsertAftermath = when {",
            "): ApplyCopy = when {",
        )
        const val BOOLEAN_RETURN = "): Boolean"
        val KERNEL_ENTRIES = listOf(
            "fun planReclaim(",
            "fun removalCopy(",
            "fun aftermathOfInsertBatch(",
            "fun applyCopy(",
        )

        // ---- ② 标记的写与读 ----
        const val MARKER_COLUMN = "CUSTOM_APP_PACKAGE"
        const val MARKER_SITES = 2
        const val MARKER_WRITE = "put(CalendarContract.Events.CUSTOM_APP_PACKAGE, context.packageName)"
        const val MARKER_READ = "\${CalendarContract.Events.CUSTOM_APP_PACKAGE} = ?"
        const val SELECTION_ARG = "arrayOf(packageName)"
        const val PROJECTION_ID = "arrayOf(CalendarContract.Events._ID)"
        val WIDENING_NEEDLES = listOf(" OR ", " || ", "LIKE", "resolveEmpty", "ifEmpty", "CALENDAR_ID")
        val MARKER_READ_TIERS = listOf("MarkerRead.Answered", "MarkerRead.Refused", "MarkerRead.NotAttempted")

        // ---- ③ 回收那一手 ----
        const val RECLAIM_HEAD = "suspend fun removeAllSyncedEvents("
        const val MAPPING_READ = "repository.getCalendarSyncEntries()"
        const val MARKER_FOLD = "CalendarOwnershipQuery.fold("
        const val PLAN_CALL = "CalendarReclaimPlanner.planReclaim("
        val PLAN_BRANCHES = listOf(
            "is ReclaimPlan.ByOwnershipMarker -> plan.eventIds.toList()",
            "is ReclaimPlan.RecordedOnly -> plan.eventIds.toList()",
            "is ReclaimPlan.NothingOurs -> emptyList()",
        )
        const val NARROWING_READ = "getCalendarSyncEntriesFor("
        const val OUTSIDE_TARGET_ARG = "mappingsOutsideCurrentTarget = entries.count"
        const val LOG_I = "Log.i("
        const val RECLAIM_LOG = "日历回收起手"

        // ---- ④ 写侧 ----
        val WRITE_SIDE_ANCHORS = listOf(
            "unnamed++",
            "else -> MappingFlush.Failed",
            "val rollbackOk = aftermath is InsertAftermath.RollbackOwnWrites &&",
            "rollbackSucceeded = rollbackOk",
        )
        const val TALLY_CALL = "CalendarReclaimPlanner.tallyOf("
        const val DELETE_HELPER = "deleteOwnedEvents("
        const val APPLY_RESULT_TAIL = "ApplyResult(inserted, updated, deleted, failed, orphanSuspects, rolledBack)"
        const val ORPHAN_ACCUMULATE = "orphanSuspects += tally.orphanSuspects"

        // ---- ⑤ 句子 ----
        val REMOVAL_COPY_BRANCHES = listOf(
            "RemovalCopy.AllReclaimed ->",
            "RemovalCopy.NothingWasOurs ->",
            "RemovalCopy.ReclaimedButMarkerBlind ->",
            "RemovalCopy.BlindReclaimSpansOtherCalendars ->",
            "RemovalCopy.PartiallyReclaimed ->",
            "RemovalCopy.AllDeletesFailed ->",
        )
        val SYNC_FAILURE_SENTENCES = listOf(
            "verdict == CalendarReclaimPlanner.ApplyCopy.OrphansRecognisable",
            "verdict == CalendarReclaimPlanner.ApplyCopy.RolledBackOwnWrites",
        )
        const val RECLAIM_CALL = "calendarSyncManager.removeAllSyncedEvents(targetCalendarId)"
        const val REMOVE_DONE = "AppMessage(\"已移除 \$n 个日程\")"
        const val REMOVE_THREW = "AppMessage(\"移除失败：日历写入异常，请检查权限后重试\", isError = true)"
        const val REMOVE_PROMISE = "将删除本应用创建的全部日历事件，不影响课表数据。确定继续？"
        const val CLAIM_SENTENCE = "按我们写进日历的所有权标记认领"
        const val REMOVE_MODAL = "ModalTransition(open = calendarSync.showRemoveConfirm)"
    }

    // ---- 源码核对小工具（抄 CalendarSyncTargetPairingGuardTest / TermWeekGateSingleRulerWiringGuardTest）----

    /** 命中所在行的行号与原文：失败消息要把人带到那一处，只报个数等于没有守卫。 */
    private fun lineHints(source: String, positions: List<Int>): String {
        if (positions.isEmpty()) return ""
        return "\n" + positions.joinToString("") { at ->
            "  L${source.substring(0, at).count { it == '\n' } + 1}: " + lineText(source, at) + "\n"
        }
    }

    private fun lineText(source: String, hit: Int): String {
        val start = source.lastIndexOf('\n', hit).let { if (it < 0) 0 else it + 1 }
        val end = source.indexOf('\n', hit).let { if (it < 0) source.length else it }
        return source.substring(start, end).trim()
    }

    /**
     * 从 [headNeedle] 之后第一枚 `{` 起按花括号配平切出整块（含字符串字面量护栏）。
     *
     * 不用"到下一枚 `private fun `"当边界：本仓这种 `suspend fun` / `private fun` / `data class`
     * 混排的成员，一种分隔符就会漏切或多切（切少了是假绿，切多了是把隔壁那位的行算进判据）。
     */
    private fun balancedBlock(source: String, headNeedle: String): String {
        val head = source.indexOf(headNeedle)
        check(head >= 0) { "找不到「$headNeedle」—— 那颗函数换了名字或载体，本守卫要跟着改" }
        val open = source.indexOf('{', head)
        check(open > head) { "「$headNeedle」之后找不到块起点，切不出函数体" }
        var depth = 0
        var index = open
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
                    // 字符字面量：'{' 与 '}' 都不参与配平
                    var j = index + 1
                    while (j < source.length && source[j] != '\'') {
                        if (source[j] == '\\') j++
                        j++
                    }
                    index = j
                }

                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
            index++
        }
        throw IllegalStateException("「$headNeedle」的块没配平：切不出函数体，本守卫的判据面不可信")
    }

    private fun mainOccurrences(needle: String): Int =
        mainFilesWith(needle).sumOf { it.second }

    /** 全 `app/src/main/java` 里（抹注释后）needle 的逐文件枚数：相对路径、正斜杠、已排序、含 0 的文件被略去。 */
    private fun mainFilesWith(needle: String): List<Pair<String, Int>> {
        val root = findMainJavaDir()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${root.path} 下一个 .kt 都没有，扫描路径不对", files.isNotEmpty())
        return files.map { file ->
            file.relativeTo(root).path.replace('\\', '/') to occurrences(blankCommentsKeepingLiterals(file.readText()), needle)
        }.filter { it.second > 0 }.sortedBy { it.first }
    }

    private fun mainFilesMentioning(needle: String): List<String> = mainFilesWith(needle).map { it.first }

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
        repeat(6) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { File(dir, it) }
                .firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }

    /**
     * **只抹注释、保留字符串字面量内容**，长度与换行位置不变。
     * 整段抄两枚先例的同名工具：不认字面量的行扫描会被写在字符串里的注释开头两个字符吞掉整段代码，
     * 而连字面量一起抹会把 `MARKER_READ`（一枚字符串模板）与屏上那两句中文靶子抹没。
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
}
