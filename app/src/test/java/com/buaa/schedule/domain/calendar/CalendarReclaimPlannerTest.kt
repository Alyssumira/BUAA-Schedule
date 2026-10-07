package com.buaa.schedule.domain.calendar

import com.buaa.schedule.domain.calendar.CalendarReclaimPlanner.InsertAftermath
import com.buaa.schedule.domain.calendar.CalendarReclaimPlanner.InsertBatchLanding
import com.buaa.schedule.domain.calendar.CalendarReclaimPlanner.MarkerBlindReason
import com.buaa.schedule.domain.calendar.CalendarReclaimPlanner.MarkerRead
import com.buaa.schedule.domain.calendar.CalendarReclaimPlanner.MappingFlush
import com.buaa.schedule.domain.calendar.CalendarReclaimPlanner.ReclaimPlan
import com.buaa.schedule.domain.calendar.CalendarReclaimPlanner.RemovalCopy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T265②：`CalendarReclaimPlanner` 的表驱动逐格账（纯 JVM，无 Robolectric、无设备、零 android import）。
 *
 * 四组判据各自一张表，每一格都点名"这一格在用户日历上做了什么"：
 *
 * **朝宽那一侧（把所有权判据放宽会删到谁的）**——本卡的代价上限是「删掉用户自己在日历里建的日程」，
 * 所以有几格专门钉"扩大范围的唯一把手是标记那一列"：
 * - `Refused` 而调用点仍递进来一份非空的 markerEventIds（provider 已经拒答，那份集合就是脏的）
 *   ⇒ 计划必须是 `RecordedOnly`、eventIds 必须**恰等于**映射集合。谁把"非 Answered 也读 markerEventIds"
 *   写进去，这一格当场红，而它红的是"用户自己的日程被删"那一档。
 * - `Answered` 而 markerEventIds 空、映射非空 ⇒ 不许升级成 `ByOwnershipMarker`（空集不是"整个日历都是我们的"）。
 * - `NothingOurs` 那一族在三枚 marker 档 × 两本账都空的组合下必须**全部**成立（从没同步过的用户点删除，
 *   任何一档都不许发一次 provider 删除）。
 *
 * **朝窄那一侧（把范围收窄会留下谁的）**——收窄到只认映射，就是本卡的病灶本身：
 * - `Answered` × marker={1,2} × mapping={1} ⇒ eventIds 必须是 {1,2} 而不是 {1}，
 *   `orphansBeyondMappings` 必须是 1（那 1 枚就是改前永久留在用户日历上的孤儿）。
 * - 反过来 mapping={5,6,7} × marker={5,6} ⇒ 并集必须保住 7（不回退到改前的覆盖面）。
 * - `RemovalCopy` 那一族：`RecordedOnly` 的成功**不许**念成 `AllReclaimed`（那等于对用户宣布
 *   「本应用创建的全部日历事件都删干净了」而实际上还有读不回来的）；`NothingOurs` 那一档即使
 *   调用点误报了 deletedCount>0 也只许念"没有"（这一格钉的是判据顺序，改前它是 `deletedCount == 0 &&` 的形状）。
 *
 * 写侧那张表钉的是乙那半：**映射落库失败 ⇒ 当场删自己刚写的**，而"自己刚写的"只包括点得出名的那些；
 * eventId 解析不出的那一档没有把手，只能留痕交给甲。`tallyOf` 里 `rollbackSucceeded = false` 那一格与
 * 「同一档两臂」那两格钉的是"撤回失败的撤回不许念成撤回成功"——那一格 rolledBack 必须归零、
 * orphanSuspects 必须把撤不掉的那些一起算进去，而 failed 两臂同数。
 *
 * 期望档位统一存成类名（`::class.simpleName`），这样一张表能同时钉住"走到哪一档"和"那一档带着什么数"，
 * 而不会把两件事折成一句 `assertTrue(x is Y)`。
 */
class CalendarReclaimPlannerTest {

    // ---------------- ① planReclaim：回收范围 ----------------

    private data class PlanCell(
        val label: String,
        val marker: MarkerRead,
        val markerEventIds: Set<Long>,
        val mappingEventIds: Set<Long>,
        val expectedTier: String,
        val expectedEventIds: Set<Long>,
        val expectedOrphans: Int? = null,
        val expectedBlindReason: MarkerBlindReason? = null,
    )

    @Test
    fun `逐格 回收范围的表驱动账（朝宽与朝窄两侧都在表里）`() {
        val cells = listOf(
            // ── 题眼：按标记捞回"有事件、无映射"的那些 ──
            PlanCell(
                "标记枚回两枚而映射只记着一枚 ⇒ 并集回收，孤儿计数 1",
                MarkerRead.Answered, setOf(1L, 2L), setOf(1L),
                "ByOwnershipMarker", setOf(1L, 2L), expectedOrphans = 1,
            ),
            PlanCell(
                "标记枚回三枚而映射一枚都没有（改前那批永久孤儿）⇒ 全部回收，孤儿 3",
                MarkerRead.Answered, setOf(7L, 8L, 9L), emptySet(),
                "ByOwnershipMarker", setOf(7L, 8L, 9L), expectedOrphans = 3,
            ),
            PlanCell(
                "两本账完全对上 ⇒ 并集不变、孤儿 0（这是同步一路走来的正常形状）",
                MarkerRead.Answered, setOf(1L, 2L), setOf(1L, 2L),
                "ByOwnershipMarker", setOf(1L, 2L), expectedOrphans = 0,
            ),
            // ── 朝窄那一侧的对应格：映射比标记多（旧版本没打标记的事件）也照删，不许回退覆盖面 ──
            PlanCell(
                "映射比标记多一枚 ⇒ 并集仍保住那枚 7",
                MarkerRead.Answered, setOf(5L, 6L), setOf(5L, 6L, 7L),
                "ByOwnershipMarker", setOf(5L, 6L, 7L), expectedOrphans = 0,
            ),
            // ── 硬门槛：从没同步过的用户点「移除」，三档 marker 都不许动一枚 ──
            PlanCell(
                "标记答『一枚都没有』而映射也空 ⇒ 一枚都不许动",
                MarkerRead.Answered, emptySet(), emptySet(),
                "NothingOurs", emptySet(),
            ),
            PlanCell(
                "provider 拒答且映射空 ⇒ 仍然一枚都不许动（不许把『读不到』当成『整个日历都是我们的』）",
                MarkerRead.Refused, emptySet(), emptySet(),
                "NothingOurs", emptySet(),
            ),
            PlanCell(
                "压根没问且映射空 ⇒ 一枚都不许动",
                MarkerRead.NotAttempted, emptySet(), emptySet(),
                "NothingOurs", emptySet(),
            ),
            PlanCell(
                "压根没问而调用点塞了一份 markerEventIds ⇒ 那一枚集合一个字节都不许读（朝宽那一刀）",
                MarkerRead.NotAttempted, setOf(11L, 12L), emptySet(),
                "NothingOurs", emptySet(),
            ),
            // ── 标记读不回来 ⇒ 退回只删映射点得出的名 ──
            PlanCell(
                "标记答空而映射记着两枚（两本账对不上）⇒ 只删那两枚，理由单列一档",
                MarkerRead.Answered, emptySet(), setOf(3L, 4L),
                "RecordedOnly", setOf(3L, 4L), expectedBlindReason = MarkerBlindReason.AnsweredEmptyAgainstMappings,
            ),
            PlanCell(
                "provider 拒答而映射记着两枚 ⇒ 只删那两枚",
                MarkerRead.Refused, emptySet(), setOf(3L, 4L),
                "RecordedOnly", setOf(3L, 4L), expectedBlindReason = MarkerBlindReason.ProviderRefused,
            ),
            PlanCell(
                "**朝宽那一刀**：provider 已经拒答，调用点却递来一份非空的 markerEventIds ⇒" +
                    " 那一枚集合必须被无视，范围仍恰等于映射集合",
                MarkerRead.Refused, setOf(101L, 102L, 103L), setOf(3L),
                "RecordedOnly", setOf(3L), expectedBlindReason = MarkerBlindReason.ProviderRefused,
            ),
            PlanCell(
                "没问而映射记着三枚 ⇒ 只删那三枚（用户自己的日程一枚不在里面）",
                MarkerRead.NotAttempted, setOf(9L), setOf(1L, 2L, 3L),
                "RecordedOnly", setOf(1L, 2L, 3L), expectedBlindReason = MarkerBlindReason.NotAttempted,
            ),
        )

        for (cell in cells) {
            val plan = CalendarReclaimPlanner.planReclaim(cell.marker, cell.markerEventIds, cell.mappingEventIds)
            val tier = plan::class.simpleName
            assertEquals("${cell.label} —— 走到的档位不对：", cell.expectedTier, tier)
            when (plan) {
                is ReclaimPlan.ByOwnershipMarker -> {
                    assertEquals("${cell.label} —— 回收集合不是『标记 ∪ 映射』：", cell.expectedEventIds, plan.eventIds)
                    assertEquals(
                        "${cell.label} —— 孤儿（标记有、映射无）枚数不对：",
                        cell.expectedOrphans,
                        plan.orphansBeyondMappings,
                    )
                }

                is ReclaimPlan.RecordedOnly -> {
                    assertEquals(
                        "${cell.label} —— 盲收那一档的范围必须恰等于映射集合：",
                        cell.expectedEventIds,
                        plan.eventIds,
                    )
                    assertEquals("${cell.label} —— 读不回来的原因不对：", cell.expectedBlindReason, plan.reason)
                }

                is ReclaimPlan.NothingOurs -> assertEquals(
                    "NothingOurs 那一档必须是**不带任何 id** 的那一枚（provider 上一次删除都不许发）：" +
                        cell.label,
                    "NothingOurs",
                    tier,
                )
            }
        }
        assertEquals("回收范围那一张表的格数（改动判据要连这张表一起重钉）：", 12, cells.size)
        assertEquals(
            "表里 NothingOurs 那一族必须占 4 格（三档 marker × 两本账都空，再加『没问却塞了 id』那一刀）—— " +
                "这一族是本卡的硬门槛，格子数掉了就说明有人把门槛那一档的覆盖拆薄了：",
            4,
            cells.count { it.expectedTier == "NothingOurs" },
        )
        assertEquals(
            "ByOwnershipMarker 那一族必须占 4 格（其中两格是题眼：孤儿被捞回来；两格是朝窄的对应格：覆盖面不回退）：",
            4,
            cells.count { it.expectedTier == "ByOwnershipMarker" },
        )
    }

    // ---------------- ② removalCopy：念哪句 ----------------

    private data class CopyCell(
        val label: String,
        val plan: ReclaimPlan,
        val deleted: Int,
        val failed: Int,
        val mappingsOutsideCurrentTarget: Int,
        val expected: RemovalCopy,
    )

    @Test
    fun `逐格 移除完成后念哪句的表驱动账`() {
        val byMarker = ReclaimPlan.ByOwnershipMarker(setOf(1L, 2L, 3L), orphansBeyondMappings = 1)
        val blind = ReclaimPlan.RecordedOnly(setOf(4L, 5L), MarkerBlindReason.ProviderRefused)
        val cells = listOf(
            CopyCell(
                "按标记回收、全删成 ⇒ 才配念那句承诺的完成时",
                byMarker, deleted = 3, failed = 0, mappingsOutsideCurrentTarget = 0,
                expected = RemovalCopy.AllReclaimed,
            ),
            CopyCell(
                "按标记回收但有两批失败 ⇒ 部分回收（失败那批的映射留着，可以再点一次）",
                byMarker, deleted = 2, failed = 1, mappingsOutsideCurrentTarget = 0,
                expected = RemovalCopy.PartiallyReclaimed,
            ),
            CopyCell(
                "**朝窄那一刀**：只按映射回收成功 ⇒ 不许念成 AllReclaimed（那是在宣布『都删干净了』）",
                blind, deleted = 2, failed = 0, mappingsOutsideCurrentTarget = 0,
                expected = RemovalCopy.ReclaimedButMarkerBlind,
            ),
            CopyCell(
                "只按映射回收成功，而映射里还有落在旧目标日历上的行 ⇒ 额外一句范围警告",
                blind, deleted = 2, failed = 0, mappingsOutsideCurrentTarget = 4,
                expected = RemovalCopy.BlindReclaimSpansOtherCalendars,
            ),
            CopyCell(
                "按标记回收**且**映射里有旧目标日历的行 ⇒ 不加那句警告（标记那一路本来就不按日历收窄）",
                byMarker, deleted = 3, failed = 0, mappingsOutsideCurrentTarget = 4,
                expected = RemovalCopy.AllReclaimed,
            ),
            CopyCell(
                "两本账都说没有 ⇒ 念『没有本应用创建的日程』，不许念『已移除 0 个日程』",
                ReclaimPlan.NothingOurs, deleted = 0, failed = 0, mappingsOutsideCurrentTarget = 0,
                expected = RemovalCopy.NothingWasOurs,
            ),
            CopyCell(
                "**判据顺序那一格**：NothingOurs 而调用点误报 deleted>0 ⇒ 仍只许念『没有』" +
                    "（改前这一格是 `deletedCount == 0 &&` 的形状，把删除动作摆在档位之前）",
                ReclaimPlan.NothingOurs, deleted = 3, failed = 0, mappingsOutsideCurrentTarget = 0,
                expected = RemovalCopy.NothingWasOurs,
            ),
            CopyCell(
                "一枚都没删掉（全是失败批次）⇒ 念失败句",
                blind, deleted = 0, failed = 2, mappingsOutsideCurrentTarget = 0,
                expected = RemovalCopy.AllDeletesFailed,
            ),
            CopyCell(
                "全失败那一格配着旧目标日历的行也仍是失败句（失败那一族排在范围警告之前）",
                blind, deleted = 0, failed = 2, mappingsOutsideCurrentTarget = 5,
                expected = RemovalCopy.AllDeletesFailed,
            ),
        )
        for (cell in cells) {
            assertEquals(
                "${cell.label} —— 句子档不对：",
                cell.expected,
                CalendarReclaimPlanner.removalCopy(cell.plan, cell.deleted, cell.failed, cell.mappingsOutsideCurrentTarget),
            )
        }
        assertEquals("念哪句那一张表的格数：", 9, cells.size)
        assertEquals(
            "六档 RemovalCopy 必须被表**全部**覆盖（有一档没有格子吃 = 那一档是死档或表被拆薄了）：",
            RemovalCopy.entries.map { it.name }.sorted(),
            cells.map { it.expected.name }.distinct().sorted(),
        )
    }

    @Test
    fun `句子册子就是那六档（改动方向要先过朝宽那一侧的账）`() {
        assertEquals(
            "RemovalCopy 不再是那六枚：",
            listOf(
                "AllReclaimed",
                "NothingWasOurs",
                "ReclaimedButMarkerBlind",
                "BlindReclaimSpansOtherCalendars",
                "PartiallyReclaimed",
                "AllDeletesFailed",
            ).sorted(),
            RemovalCopy.entries.map { it.name }.sorted(),
        )
    }

    // ---------------- ③ aftermathOfInsertBatch：写侧那一手 ----------------

    private data class AftermathCell(
        val label: String,
        val landing: InsertBatchLanding,
        val attempted: Int,
        val named: Set<Long>,
        val unnamed: Int,
        val flush: MappingFlush,
        val expectedTier: String,
        val expectedEventIds: Set<Long>? = null,
        val expectedUnnamed: Int? = null,
        val expectedNamed: Int? = null,
        val expectedAttempted: Int? = null,
    )

    @Test
    fun `逐格 一批新增之后的动作表（乙那半：失败即删自己刚写的）`() {
        val cells = listOf(
            AftermathCell(
                "整批被 provider 拒 ⇒ 一枚都没进日历，既不是孤儿也不必撤回",
                InsertBatchLanding.Refused, attempted = 50, named = emptySet(), unnamed = 0,
                flush = MappingFlush.NotNeeded, expectedTier = "BatchRefused", expectedAttempted = 50,
            ),
            AftermathCell(
                "批写成功、50 枚都点得出名、映射落库成功 ⇒ 正常入账",
                InsertBatchLanding.Landed, attempted = 50, named = (1L..50L).toSet(), unnamed = 0,
                flush = MappingFlush.Committed, expectedTier = "Recorded", expectedNamed = 50,
            ),
            AftermathCell(
                "**题眼档 1**：批写成功而其中 3 枚的 URI 解析不出 eventId（映射仍落成了）⇒" +
                    " 那 3 枚升格为孤儿嫌疑，交给按标记回收",
                InsertBatchLanding.Landed, attempted = 50, named = (1L..47L).toSet(), unnamed = 3,
                flush = MappingFlush.Committed, expectedTier = "OrphansUnnamed",
                expectedNamed = 47, expectedUnnamed = 3,
            ),
            AftermathCell(
                "**题眼档 2**：批写成功、映射落库失败而 50 枚都点得出名 ⇒ 当场撤回自己刚写的那 50 枚",
                InsertBatchLanding.Landed, attempted = 50, named = (1L..50L).toSet(), unnamed = 0,
                flush = MappingFlush.Failed, expectedTier = "RollbackOwnWrites", expectedEventIds = (1L..50L).toSet(),
            ),
            AftermathCell(
                "两档叠在一起：40 枚点名、10 枚认不回来、映射又没落库 ⇒ 只撤点得出名的 40 枚，" +
                    "剩下 10 枚留给按标记回收",
                InsertBatchLanding.Landed, attempted = 50, named = (1L..40L).toSet(), unnamed = 10,
                flush = MappingFlush.Failed, expectedTier = "RollbackOwnWrites",
                expectedEventIds = (1L..40L).toSet(), expectedUnnamed = 10,
            ),
            AftermathCell(
                "映射没落库而这一批一枚都点不出名 ⇒ 撤不动（没有把手），只能留痕",
                InsertBatchLanding.Landed, attempted = 50, named = emptySet(), unnamed = 50,
                flush = MappingFlush.Failed, expectedTier = "NothingNamedToRollBack", expectedUnnamed = 50,
            ),
            AftermathCell(
                "题眼档 1 的真实形状：全批都解析不出 ⇒ upserts 是空表、Room 压根没被叫（flush 是 NotNeeded）",
                InsertBatchLanding.Landed, attempted = 50, named = emptySet(), unnamed = 50,
                flush = MappingFlush.NotNeeded, expectedTier = "OrphansUnnamed",
                expectedNamed = 0, expectedUnnamed = 50,
            ),
            AftermathCell(
                "空批（既没点名也没未点名）⇒ Recorded(0)，不许被折成失败也不许被折成撤回",
                InsertBatchLanding.Landed, attempted = 0, named = emptySet(), unnamed = 0,
                flush = MappingFlush.NotNeeded, expectedTier = "Recorded", expectedNamed = 0,
            ),
        )
        for (cell in cells) {
            val aftermath = CalendarReclaimPlanner.aftermathOfInsertBatch(
                cell.landing, cell.attempted, cell.named, cell.unnamed, cell.flush,
            )
            assertEquals("${cell.label} —— 走到的动作档不对：", cell.expectedTier, aftermath::class.simpleName)
            when (aftermath) {
                is InsertAftermath.BatchRefused -> assertEquals(
                    "${cell.label} —— 报失败用的枚数不对：", cell.expectedAttempted, aftermath.attempted,
                )

                is InsertAftermath.Recorded -> assertEquals(
                    "${cell.label} —— 入账枚数不对：", cell.expectedNamed, aftermath.named,
                )

                is InsertAftermath.OrphansUnnamed -> {
                    assertEquals("${cell.label} —— 落账枚数不对：", cell.expectedNamed, aftermath.named)
                    assertEquals("${cell.label} —— 孤儿嫌疑枚数不对：", cell.expectedUnnamed, aftermath.unnamed)
                }

                is InsertAftermath.RollbackOwnWrites -> {
                    assertEquals(
                        "${cell.label} —— 撤回集合必须恰等于点得出名的那些（一枚都不许多、一枚都不许少）：",
                        cell.expectedEventIds,
                        aftermath.eventIds,
                    )
                    assertEquals(
                        "${cell.label} —— 撤不动而留给甲的那些枚数不对：",
                        cell.expectedUnnamed,
                        aftermath.unnamed,
                    )
                }

                is InsertAftermath.NothingNamedToRollBack -> assertEquals(
                    "${cell.label} —— 只能留痕的枚数不对：", cell.expectedUnnamed, aftermath.unnamed,
                )
            }
        }
        assertEquals("写侧那一张表的格数：", 8, cells.size)
        assertEquals(
            "**朝宽那一格**：五档动作里没有任何一档交回『点不出名却照样去删』的形状 —— " +
                "NothingNamedToRollBack 必须恰占 1 格（它就是『没有把手』那一档，谁把它折进撤回档，" +
                "谁就在拿用户日历上的未知事件当撤回目标）：",
            1,
            cells.count { it.expectedTier == "NothingNamedToRollBack" },
        )
    }

    // ---------------- ④ tallyOf + applyCopy：账与句子 ----------------

    private data class TallyCell(
        val label: String,
        val aftermath: InsertAftermath,
        val rollbackSucceeded: Boolean,
        val expected: CalendarReclaimPlanner.InsertTally,
    )

    @Test
    fun `逐格 动作档折成报数（撤回失败不许念成撤回成功）`() {
        val cells = listOf(
            TallyCell(
                "批次被拒：failed 记整批，孤儿 0、撤回 0",
                InsertAftermath.BatchRefused(50), true,
                CalendarReclaimPlanner.InsertTally(inserted = 0, failed = 50, orphanSuspects = 0, rolledBack = 0),
            ),
            TallyCell(
                "正常入账：inserted 记点得出名的那些",
                InsertAftermath.Recorded(47), true,
                CalendarReclaimPlanner.InsertTally(inserted = 47, failed = 0, orphanSuspects = 0, rolledBack = 0),
            ),
            TallyCell(
                "解析不出 eventId 那批：既进 failed 又进 orphanSuspects（同一批数字的两本账）",
                InsertAftermath.OrphansUnnamed(named = 47, unnamed = 3), true,
                CalendarReclaimPlanner.InsertTally(inserted = 47, failed = 3, orphanSuspects = 3, rolledBack = 0),
            ),
            TallyCell(
                "撤回成功：rolledBack 记撤掉的枚数，撤掉的那些不再是孤儿",
                InsertAftermath.RollbackOwnWrites(setOf(1L, 2L, 3L), unnamed = 0), true,
                CalendarReclaimPlanner.InsertTally(inserted = 0, failed = 3, orphanSuspects = 0, rolledBack = 3),
            ),
            TallyCell(
                "**撤回失败那一格**：rolledBack 必须归零、撤不掉的那批升格成孤儿嫌疑",
                InsertAftermath.RollbackOwnWrites(setOf(1L, 2L, 3L), unnamed = 2), false,
                CalendarReclaimPlanner.InsertTally(inserted = 0, failed = 5, orphanSuspects = 5, rolledBack = 0),
            ),
            TallyCell(
                "点不出名的那批留痕：failed 与 orphanSuspects 同数、撤回 0",
                InsertAftermath.NothingNamedToRollBack(unnamed = 50), true,
                CalendarReclaimPlanner.InsertTally(inserted = 0, failed = 50, orphanSuspects = 50, rolledBack = 0),
            ),
        )
        for (cell in cells) {
            assertEquals(
                "${cell.label} —— 报数不对：",
                cell.expected,
                CalendarReclaimPlanner.tallyOf(cell.aftermath, rollbackSucceeded = cell.rollbackSucceeded),
            )
        }
        assertEquals("报数那一张表的格数：", 6, cells.size)
        // 两侧的同一档：撤回成不成都得把 failed 记满（不许把失败折成"没发生过"）
        val both = listOf(true, false).map {
            CalendarReclaimPlanner.tallyOf(InsertAftermath.RollbackOwnWrites(setOf(1L, 2L), unnamed = 0), it)
        }
        assertEquals(
            "撤回成/败两臂的 failed 必须同为 2（撤没撤回来这批都没落到映射上）：",
            listOf(2, 2),
            both.map { it.failed },
        )
        assertEquals(
            "而 rolledBack 只许在成功那一臂为正（把失败的撤回念成撤回成功就是第二句谎话）：",
            listOf(2, 0),
            both.map { it.rolledBack },
        )
        assertEquals(
            "撤不回的那批必须同时被记成孤儿嫌疑（它们此刻仍在用户日历上、而映射里没有）：",
            listOf(0, 2),
            both.map { it.orphanSuspects },
        )
    }

    private data class ApplyCell(
        val label: String,
        val failed: Int,
        val orphans: Int,
        val rolledBack: Int,
        val expected: CalendarReclaimPlanner.ApplyCopy,
    )

    @Test
    fun `逐格 同步结果念哪句（孤儿那一档排在撤回与泛失败之前）`() {
        val table = listOf(
            ApplyCell(
                "一枚没失败也没孤儿 ⇒ 念「同步完成」",
                failed = 0, orphans = 0, rolledBack = 0,
                expected = CalendarReclaimPlanner.ApplyCopy.Clean,
            ),
            ApplyCell(
                "**题眼档**：有日程已经进了用户日历却认不回来 ⇒ 必须单独念一句" +
                    "「N 枚已在日历上但认不回，下次同步会重复插，可点移除按标记回收」",
                failed = 3, orphans = 3, rolledBack = 0,
                expected = CalendarReclaimPlanner.ApplyCopy.OrphansRecognisable,
            ),
            ApplyCell(
                "映射落库失败那批被当场撤回 ⇒ 念「写了又收回，请重试」，不许念成「从未写过」",
                failed = 50, orphans = 0, rolledBack = 50,
                expected = CalendarReclaimPlanner.ApplyCopy.RolledBackOwnWrites,
            ),
            ApplyCell(
                "批次被拒那一族 ⇒ 维持既有的「N 个日程未写入」",
                failed = 50, orphans = 0, rolledBack = 0,
                expected = CalendarReclaimPlanner.ApplyCopy.PlainFailure,
            ),
            ApplyCell(
                "顺序那一格：同一批里既有撤回成功又有认不回来的 ⇒ 孤儿那句优先（它是更贵的那一半）",
                failed = 10, orphans = 2, rolledBack = 8,
                expected = CalendarReclaimPlanner.ApplyCopy.OrphansRecognisable,
            ),
            ApplyCell(
                "『failed 为 0 却有孤儿』这一形（调用点漏记账）也必须被念出来，否则孤儿就静默了 —— " +
                    "这一格钉的是 `orphanSuspects > 0` 那条判断不看 failed",
                failed = 0, orphans = 1, rolledBack = 0,
                expected = CalendarReclaimPlanner.ApplyCopy.OrphansRecognisable,
            ),
        )
        for (cell in table) {
            assertEquals(
                "${cell.label} —— 句子档不对：",
                cell.expected,
                CalendarReclaimPlanner.applyCopy(cell.failed, cell.orphans, cell.rolledBack),
            )
        }
        assertEquals("同步结果那一张表的格数：", 6, table.size)
        assertEquals(
            "四档 ApplyCopy 必须全部有格子吃：",
            CalendarReclaimPlanner.ApplyCopy.entries.map { it.name }.sorted(),
            table.map { it.expected.name }.distinct().sorted(),
        )
        assertEquals(
            "Clean 那一档在整个宇宙里只许占 1 格（failed=0 且 orphan=0 是唯一形状）—— " +
                "多一格就说明有人在把『有孤儿』往成功那句里折：",
            1,
            table.count { it.expected == CalendarReclaimPlanner.ApplyCopy.Clean },
        )
    }
}
