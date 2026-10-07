package com.buaa.schedule.domain.calendar

/**
 * 「这枚系统日历事件是不是本应用建的、能不能把它收回来」的**唯一判据内核**（T265，台账 #372 / #507）。
 *
 * ## 病在哪（改前盘面，逐格原文留在回执里）
 *
 * 写侧把所有权标记 `Events.CUSTOM_APP_PACKAGE` 打进每一枚我们创建的事件（`data/calendar/`
 * `CalendarSyncManager.kt` 裸 `:307`），而全仓**没有任何一处按 selection 把这枚列查回来**
 * （复算：`CUSTOM_APP_PACKAGE` 在 `app/src/main/java` 出现 1 次、在 `app/src/test` 出现 0 次，
 * 那一枚就是写）。于是"删除本应用创建的全部日历事件"那一手（同文件裸 `:266`）的删除范围
 * **完全取自我们自己的映射表**（Room 里的 `calendar_sync`）—— 标记只写不读，屏上那句承诺
 * （`ui/settings/SettingsScreen.kt` 裸 `:2005`「将删除本应用创建的全部日历事件」）就兑不了：
 *
 * 1. 批写成功但返回 URI 解析不出 eventId（裸 `:206` → `:211`）⇒ `failed++`，事件已经在日历上了、
 *    映射表里没有它；
 * 2. `flushMappings` 重试两轮仍返回 false（裸 `:220`）⇒ 事件已进日历、映射没落盘；
 * 3. 同一形状还有一个不改代码也会碰到的入口：批写与落库之间进程被杀（同文件那段
 *    「必须逐批立刻写映射」的注释自己承认了这个窗口，它只是把窗口缩到一批，没有关死）。
 *
 * 两档都能真造出「有事件、无映射」的状态（判据与失败路径见本卡回执）：`flushMappings` 的
 * false 是 `runCatching { repository.applyCalendarSyncMappingChanges(...) }.isSuccess` 两轮都假，
 * 而 `applyCalendarSyncMappingChanges` 走的是 `db.withTransaction { ... }` —— 磁盘满、事务冲突、
 * 以及**协程被取消**都会让它交回异常（本文件不判这一格，判它要读 Room 与协程语义，
 * 归调用点；这里只把「调用点交回来的档位」当输入）。
 *
 * 后果是双份的：孤儿永久留在用户日历上（下次点"移除"也碰不到它），而下一次同步因为查不到映射，
 * 把同一批课次**再插一遍**。
 *
 * ## 第 ⓪ 问裁的是丙档（甲＋乙同时做）
 *
 * - **甲**＝把所有权标记变成**读得回来的判据**：回收那一手先按标记枚 provider，映射表退成
 *   加速缓存与第二来源，不再当唯一真相（[planReclaim]）。
 * - **乙**＝映射落盘失败 ⇒ **当场删掉自己刚写的那几枚**，并把失败档在屏上说实话
 *   （[aftermathOfInsertBatch] + [applyCopy]）。
 *
 * 为什么两半都要：**乙 拿不到甲的把手**。eventId 解析不出那一档（病灶 1）根本没有 id 可删 ——
 * 「删自己刚写的」需要先点得出名，而点得出名的只有映射落成了的那些；剩下的孤儿只能由「按标记枚一遍」
 * 回收。反过来 **甲 盖不住乙 的那一格**：一次同步里映射落库失败、而用户再没点过"移除"，
 * 孤儿就在用户日历上住下了，中间每一次同步都多插一批重复。单做甲 = 将错就错押在用户手动回收上；
 * 单做乙 = 病灶 1 与"改前那几批已经躺在日历上的存量孤儿"（老版本用户）永远收不回。
 *
 * ## 硬门槛（本卡的判据方向，不是可选项）
 *
 * **「用户从没同步过、直接点移除」这一档一枚都不许动**（[ReclaimPlan.NothingOurs]）。
 * 所有权判据误宽会删掉用户自己在日历里建的日程 —— 那比留一枚孤儿贵得多。所以三档里
 * 只有 `Answered` 且**逐枚按标记命中**的那些才允许扩大范围；provider 拒答、压根没问、
 * 或"答了一枚都没有而我们的映射却记着 N 枚"（这台机器的 provider 在说谎）三档，
 * 一律**退回只删映射点得出的名**，并在屏上单独说一句（[RemovalCopy.ReclaimedButMarkerBlind] 与
 * [RemovalCopy.BlindReclaimSpansOtherCalendars]）。
 *
 * ## 判据顺序（顺序即语义）
 *
 * [planReclaim]：
 * 1. `Answered` 且标记枚回来非空 ⇒ [ReclaimPlan.ByOwnershipMarker]（并集：标记命中的 ∪ 映射记着的；
 *    `orphansBeyondMappings` 就是"标记有、映射没有"那些，正是本卡要捞回来的孤儿）。
 * 2. `Answered` 而标记枚回空、映射却非空 ⇒ [ReclaimPlan.RecordedOnly]，理由是
 *    [MarkerBlindReason.AnsweredEmptyAgainstMappings] —— 两本账对不上，不扩大范围、但要念出来。
 * 3. `Refused` / `NotAttempted` ⇒ [ReclaimPlan.RecordedOnly]（映射非空时）。
 * 4. 两本账都空 ⇒ [ReclaimPlan.NothingOurs]，**排在所有删除动作之前**（硬门槛那一格）。
 *
 * [removalCopy]：先按"删成了多少 / 失败了多少"分三档（部分失败与全失败都要念，
 * 因为映射按既有口径保留、可以再点一次），剩下的成功档再按**计划那一型**与
 * 「映射里有没有落在别的日历上的那些」分档 —— 后者念的那句要额外警告"本应用可能还在
 * 你已经换掉的旧目标日历上留有日程"。
 *
 * [aftermathOfInsertBatch]：批次本身被拒（一枚都没落地）排在最前，因为它既不是孤儿也不是撤回；
 * 然后是映射没落盘那一支（能点得出名的 ⇒ 当场撤回；点不出名的 ⇒ 只能留痕交给甲），
 * 最后才是"映射落了、但有几枚 eventId 认不回来"。
 *
 * ## 为什么不折成一枚 Boolean
 *
 * 调用点要做四件**不同**的事：删哪些（集合不同）、映射清哪些、留不留痕、屏上念哪一句。
 * "全部回收干净"与"回收了但 provider 说谎"折成同一枚 true，就是把本卡要消灭的那句
 * 静默谎话（「将删除本应用创建的全部日历事件」）保留下来。
 *
 * ## 纯度
 *
 * 零 android / androidx import、不读时钟、不读时区、不读权限、不读 `Build.VERSION`、
 * 不碰 ContentResolver —— 六枚事实（标记那一列问回来的是什么档、按标记命中的 id 集合、
 * 映射记着的 id 集合、批写落档、映射落库档、目标日历是否换过）全部由调用点
 * （`data/calendar/CalendarSyncManager.kt`）当参数递进来。这一条由
 * `domain/calendar/CalendarReclaimSingleSourceGuardTest` 第 ① 层按源码文本钉着。
 */
object CalendarReclaimPlanner {

    /** 所有权标记那一列（`Events.CUSTOM_APP_PACKAGE`）在调用点问出来的是哪一档。 */
    enum class MarkerRead {
        /** 查询成功，交回的 id 集合可信 —— **空集也是可信的一次回答**（它说的是"没有带我们标记的事件"）。 */
        Answered,

        /** provider 抛异常 / 那一枚列或 selection 不被支持 ⇒ 一枚都读不回来。 */
        Refused,

        /** 压根没问（没有读权限、或调用方主动跳过这一手）。与 [Refused] 分档：屏上念的话不同 ——
         *  前者是"这台机器的日历不肯答"，后者是"我们还没去问"。 */
        NotAttempted,
    }

    /** 标记那一手读不回来的原因（只服务于「念哪句」，不参与删除范围 —— 范围一律退回映射）。 */
    enum class MarkerBlindReason {
        ProviderRefused,
        NotAttempted,

        /** provider 答"一枚都没有"，可我们自己的映射表却记着 N 枚 —— 两本账对不上，
         *  这台机器的 provider 在这枚列上没给可信答案（本卡唯一只能装机验的那一档的形状就落在这里）。 */
        AnsweredEmptyAgainstMappings,
    }

    /**
     * 回收（"移除已同步的日程"）那一手的**范围**。
     *
     * 三档，删除范围从宽到窄排列，但**最宽那一档也只到"带我们标记的 ∪ 我们自己记着的"**：
     * 没有任何一档能表达"这个日历里的事件都归我删"。
     */
    sealed class ReclaimPlan {
        /**
         * 按所有权标记枚回来的那些，加上映射表记着的那些（并集）。
         *
         * @param eventIds 要删的事件 id（provider 侧的 `_ID`）
         * @param orphansBeyondMappings 「标记命中、映射却没有」的枚数 —— 就是本卡题眼那批孤儿，
         *   改前这一枚恒为 0 且无人去问
         */
        data class ByOwnershipMarker(val eventIds: Set<Long>, val orphansBeyondMappings: Int) : ReclaimPlan()

        /**
         * 只删映射表点得出名的那些（= 改前的全部覆盖面，一格都不少），孤儿留在盘上，
         * 但**必须**在屏上说出"读不回来"这件事（见 [removalCopy]）。
         */
        data class RecordedOnly(val eventIds: Set<Long>, val reason: MarkerBlindReason) : ReclaimPlan()

        /** 两本账都说没有 ⇒ 一枚都不许动，provider 上一次删除操作都不许发（硬门槛那一格）。 */
        data object NothingOurs : ReclaimPlan()
    }

    /**
     * 纯 JVM 判据：这一次回收该删哪些、按哪一路删。
     *
     * @param marker 调用点把 provider 的结果折成的档（[MarkerRead]）
     * @param markerEventIds 只有 [MarkerRead.Answered] 时才参与；其余档下本函数一个字节都不读它
     * @param mappingEventIds 映射表里记着的 event id 集合（Room 读出来的，已折算）
     */
    fun planReclaim(
        marker: MarkerRead,
        markerEventIds: Set<Long>,
        mappingEventIds: Set<Long>,
    ): ReclaimPlan {
        if (marker == MarkerRead.Answered && markerEventIds.isNotEmpty()) {
            return ReclaimPlan.ByOwnershipMarker(
                eventIds = markerEventIds + mappingEventIds,
                orphansBeyondMappings = (markerEventIds - mappingEventIds).size,
            )
        }
        if (mappingEventIds.isEmpty()) return ReclaimPlan.NothingOurs
        return ReclaimPlan.RecordedOnly(
            eventIds = mappingEventIds,
            reason = when (marker) {
                MarkerRead.Refused -> MarkerBlindReason.ProviderRefused
                MarkerRead.NotAttempted -> MarkerBlindReason.NotAttempted
                // 走到这里 marker 必是 Answered 而它一枚都没答出来，映射表却有货
                MarkerRead.Answered -> MarkerBlindReason.AnsweredEmptyAgainstMappings
            },
        )
    }

    /** 「移除已同步的日程」完成后屏上该念哪一句（句子本身由调用点持有，本内核只出档）。 */
    enum class RemovalCopy {
        /** 全删成了，而且没有读不回来的账 —— 这一档才配念「将删除本应用创建的全部日历事件」的完成时。 */
        AllReclaimed,

        /** 两本账都说没有 ⇒ 念"没有本应用创建的日程"，**不许**念「已移除 0 个日程」。 */
        NothingWasOurs,

        /** 按映射删成了，但标记那一手读不回来 ⇒ 可能有孤儿留着，下次同步仍可能重复插。 */
        ReclaimedButMarkerBlind,

        /** 同上一档，而且映射里还有落在**你已经换掉的旧目标日历**上的那些 ⇒ 额外警告范围可能不止这一个日历。 */
        BlindReclaimSpansOtherCalendars,

        /** 删成了若干、另若干批次失败 ⇒ 失败那些的映射按既有口径保留，可以再点一次。 */
        PartiallyReclaimed,

        /** 一枚都没删掉（全是失败批次）⇒ 念失败句，不许念成功句。 */
        AllDeletesFailed,
    }

    /**
     * 纯 JVM 判据：按「删成多少 / 失败多少 / 计划是哪一型 / 映射里有没有别的日历的行」出句子档。
     *
     * 判据顺序：失败那一族先分（部分失败、全失败），因为它们的映射还留着、话要说"可再点一次"；
     * 成功那一族再按**计划那一型**分（按标记回收＝真把本应用建的都收了；只按映射回收＝还有读不回来的），
     * 最后一格才看「目标日历是否换过」—— 它只在**盲收**那一族里加一句范围警告，
     * 不改变删除范围（范围由 [planReclaim] 定死，这里只管说实话）。
     *
     * @param mappingsOutsideCurrentTarget 调用点数出来的：映射行里 `calendarId` 与当前选中的目标日历不同的那些枚数
     */
    fun removalCopy(
        plan: ReclaimPlan,
        deletedCount: Int,
        failedCount: Int,
        mappingsOutsideCurrentTarget: Int,
    ): RemovalCopy = when {
        failedCount > 0 && deletedCount > 0 -> RemovalCopy.PartiallyReclaimed
        failedCount > 0 -> RemovalCopy.AllDeletesFailed
        // 「两本账都说没有」排在成功/失败计数之前：这一档与计数无关，
        // 谁把 provider 删除动作塞在它前面（deletedCount 变成正数）它也照样只许念"没有"——
        // 想念成「已移除 N 个」就得先改这一档，改这一档就得先过本卡那道硬门槛。
        plan is ReclaimPlan.NothingOurs -> RemovalCopy.NothingWasOurs
        plan is ReclaimPlan.RecordedOnly && mappingsOutsideCurrentTarget > 0 ->
            RemovalCopy.BlindReclaimSpansOtherCalendars

        plan is ReclaimPlan.RecordedOnly -> RemovalCopy.ReclaimedButMarkerBlind
        else -> RemovalCopy.AllReclaimed
    }

    /** 一批"新增"提交回写的落档（调用点从 `applyBatch` 的结果折算，不含任何 provider 语义）。 */
    enum class InsertBatchLanding {
        /** 整批被 provider 拒了：一枚都没进日历 ⇒ 既不是孤儿也不必撤回。 */
        Refused,

        /** 批次成功：这一批**已经躺在用户日历上**了，后面两档都是围绕它做账。 */
        Landed,
    }

    /** 映射落库那一手的落档（调用点从 `flushMappings` 的返回值折算）。 */
    enum class MappingFlush {
        /** 这一批没产生要落盘的映射行（例如 eventId 一枚都没解析出来），Room 压根没被叫。 */
        NotNeeded,
        Committed,
        Failed,
    }

    /** 一批新增之后该做什么（回收动作 + 报数的依据）。 */
    sealed class InsertAftermath {
        /** 批次被拒 ⇒ 只报 failed，不动映射、不撤回。 */
        data class BatchRefused(val attempted: Int) : InsertAftermath()

        /** 全解析得出名、映射也落了 ⇒ 正常入账。 */
        data class Recorded(val named: Int) : InsertAftermath()

        /**
         * 映射落了，但这一批里有 [unnamed] 枚的 eventId **认不回来**（返回 URI 解析不出）：
         * 它们已经在日历上、映射里没有 ⇒ 孤儿嫌疑。本卡不猜 id（猜就是拿用户日程当事故），
         * 而是把它们交给甲那一路（下次回收按标记枚回来）。
         */
        data class OrphansUnnamed(val named: Int, val unnamed: Int) : InsertAftermath()

        /**
         * 映射没落盘，而这一批点得出名 [eventIds] ⇒ **当场删掉自己刚写的**（乙那半）。
         * 只删点得出名的那些；[unnamed] 枚点不出名的仍然留给甲。
         */
        data class RollbackOwnWrites(val eventIds: Set<Long>, val unnamed: Int) : InsertAftermath()

        /** 映射没落盘而这一批**一枚都点不出名** ⇒ 撤不动（没有把手），只能留痕并交给甲。 */
        data class NothingNamedToRollBack(val unnamed: Int) : InsertAftermath()
    }

    /**
     * 纯 JVM 判据：一批新增的落档 × 映射落库的落档 ⇒ 下一步动作。
     *
     * @param landing 批次本身成没成
     * @param attempted 这一批想插多少枚（批次被拒时按它报失败数）
     * @param namedEventIds 从批次结果的 URI 里解析得出的 id（解析不出的那些**不在这里**）
     * @param unnamedCount 批次成功却解析不出 id 的枚数
     * @param flush 映射落库那一手的档位
     */
    fun aftermathOfInsertBatch(
        landing: InsertBatchLanding,
        attempted: Int,
        namedEventIds: Set<Long>,
        unnamedCount: Int,
        flush: MappingFlush,
    ): InsertAftermath = when {
        landing == InsertBatchLanding.Refused -> InsertAftermath.BatchRefused(attempted)
        flush == MappingFlush.Failed && namedEventIds.isNotEmpty() ->
            InsertAftermath.RollbackOwnWrites(namedEventIds, unnamedCount)
        flush == MappingFlush.Failed -> InsertAftermath.NothingNamedToRollBack(unnamedCount)
        unnamedCount > 0 -> InsertAftermath.OrphansUnnamed(namedEventIds.size, unnamedCount)
        else -> InsertAftermath.Recorded(namedEventIds.size)
    }

    /** 一批新增最终进 `ApplyResult` 的账（[rolledBack] 是"当场撤回了"的枚数，只有乙那一档才有）。 */
    data class InsertTally(
        val inserted: Int,
        val failed: Int,
        val orphanSuspects: Int,
        val rolledBack: Int,
    )

    /**
     * 把动作那一档折成报数。
     *
     * @param rollbackSucceeded 调用点真的去删了 [InsertAftermath.RollbackOwnWrites] 那批、
     *   provider 有没有答应。**false 时那批也升格成孤儿嫌疑**（事件还在日历上、映射又没有），
     *   而 rolledBack 归零 —— 这一格不许把失败的撤回念成撤回成功。
     */
    fun tallyOf(aftermath: InsertAftermath, rollbackSucceeded: Boolean = true): InsertTally = when (aftermath) {
        is InsertAftermath.BatchRefused ->
            InsertTally(inserted = 0, failed = aftermath.attempted, orphanSuspects = 0, rolledBack = 0)

        is InsertAftermath.Recorded ->
            InsertTally(inserted = aftermath.named, failed = 0, orphanSuspects = 0, rolledBack = 0)

        is InsertAftermath.OrphansUnnamed -> InsertTally(
            inserted = aftermath.named,
            failed = aftermath.unnamed,
            orphanSuspects = aftermath.unnamed,
            rolledBack = 0,
        )

        is InsertAftermath.RollbackOwnWrites -> if (rollbackSucceeded) {
            InsertTally(
                inserted = 0,
                failed = aftermath.eventIds.size + aftermath.unnamed,
                orphanSuspects = aftermath.unnamed,
                rolledBack = aftermath.eventIds.size,
            )
        } else {
            InsertTally(
                inserted = 0,
                failed = aftermath.eventIds.size + aftermath.unnamed,
                orphanSuspects = aftermath.eventIds.size + aftermath.unnamed,
                rolledBack = 0,
            )
        }

        is InsertAftermath.NothingNamedToRollBack -> InsertTally(
            inserted = 0,
            failed = aftermath.unnamed,
            orphanSuspects = aftermath.unnamed,
            rolledBack = 0,
        )
    }

    /** 「同步」那条链完成后屏上该念哪一句。 */
    enum class ApplyCopy {
        /** 一枚没失败、也没有孤儿 ⇒ 念「同步完成：新增 / 更新 / 删除」。 */
        Clean,

        /** 有日程已经进了用户日历却认不回来（孤儿嫌疑）⇒ 必须念这一档，
         *  否则用户以为"再点一次同步会把它补上"，实际是**再插一批重复**。 */
        OrphansRecognisable,

        /** 映射落库失败那批被当场撤回 ⇒ 念"写了又收回，请重试"，不许念成"从未写过"。 */
        RolledBackOwnWrites,

        /** 其余失败（批次被拒那一族）⇒ 维持既有的「N 个日程未写入」。 */
        PlainFailure,
    }

    /**
     * 纯 JVM 判据：同步结果的报数 ⇒ 句子档。
     *
     * 孤儿那一档**排在**撤回与泛失败之前：它要念的话比"N 个未写入"更具体，
     * 而且指的是同一批数字里更贵的那一半（用户日历上多了收不回来的东西）。
     */
    fun applyCopy(
        failed: Int,
        orphanSuspects: Int,
        rolledBack: Int,
    ): ApplyCopy = when {
        failed == 0 && orphanSuspects == 0 -> ApplyCopy.Clean
        orphanSuspects > 0 -> ApplyCopy.OrphansRecognisable
        rolledBack > 0 -> ApplyCopy.RolledBackOwnWrites
        else -> ApplyCopy.PlainFailure
    }
}
