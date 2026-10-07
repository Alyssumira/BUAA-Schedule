package com.buaa.schedule.domain.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T260②：`EditorSaveFinishing` 的表驱动逐格账（纯 JVM，无 Robolectric、无设备、不读钟）。
 *
 * 钉的是本卡病灶那一格与它的**两侧**：
 * - 朝宽扭一刀（把「落成 + 页面那一支还活着」那条短路摘掉 ⇒ 目的地层连活着的那一支也一起接管）⇒
 *   [EditorSaveFinish.PageTailOwnsThePop] 那一格当场变红，同时「连 pop 两格」那一族回到盘上；
 * - 朝窄扭一刀（把新建身份那一档的「退栈之前闸门不许放开」折成与编辑身份同档）⇒
 *   [EditorSaveFinish.DestinationPopsEditorHoldingGate] 那一格变红，而它正是
 *   「课已入库、页面退回添加课程、再点一次插第二门课」那一手唯一还按着闸门的那一格。
 *
 * 重点格：**写库成功 + 收尾所在的 scope 已被取消**（`Landed` + `tailScopeCancelled = true`）——
 * 本卡的题眼。它必须落到「由目的地层退栈」那一档，而不是继续等一枚永远不会跑的收尾。
 *
 * 另有全枚举一格不许漏与「七档都可达」（死档检查：本卡明令不许把某一档写成盘上到不了的那一等），
 * 以及 [EditorSaveFinishing.readout] 那张「VM 的旗 → 三枚已算好的值」的映射表 ——
 * 配对那一格（陈旧回执）单独钉：它松掉就是新打开的那页编辑器替**上一趟**写库退自己的栈。
 */
class EditorSaveFinishingTest {

    private data class Cell(
        val label: String,
        val saveInFlight: Boolean,
        val writeOutcome: EditorWriteOutcome,
        val tailScopeCancelled: Boolean,
        val identity: EditorDraftIdentity,
        val expectedTier: String,
    )

    @Test
    fun `逐格 四枚事实到七档的表驱动账`() {
        val cells = listOf(
            // ── 题眼：写库成功 + 收尾所在的 scope 已被取消 ──
            Cell(
                "落成 + 收尾那枚 scope 已被取消 + 新建 ⇒ 目的地层退栈且闸门继续按住",
                saveInFlight = false, writeOutcome = EditorWriteOutcome.Landed,
                tailScopeCancelled = true, identity = EditorDraftIdentity.CreatingNewCourse,
                expectedTier = "DestinationPopsEditorHoldingGate",
            ),
            Cell(
                "落成 + 收尾那枚 scope 已被取消 + 编辑 ⇒ 目的地层退栈（同一行再写不坏数据）",
                saveInFlight = false, writeOutcome = EditorWriteOutcome.Landed,
                tailScopeCancelled = true, identity = EditorDraftIdentity.EditingExistingCourse,
                expectedTier = "DestinationPopsEditor",
            ),
            // ── 两侧的另一侧：scope 还活着 ⇒ 页面自己退，目的地不许跟着弹 ──
            Cell(
                "落成 + 页面那一支报到了 + 新建 ⇒ 归页面退栈 目的地不动（连 pop 两格那一族）",
                saveInFlight = false, writeOutcome = EditorWriteOutcome.Landed,
                tailScopeCancelled = false, identity = EditorDraftIdentity.CreatingNewCourse,
                expectedTier = "PageTailOwnsThePop",
            ),
            Cell(
                "落成 + 页面那一支报到了 + 编辑 ⇒ 同上",
                saveInFlight = false, writeOutcome = EditorWriteOutcome.Landed,
                tailScopeCancelled = false, identity = EditorDraftIdentity.EditingExistingCourse,
                expectedTier = "PageTailOwnsThePop",
            ),
            // ── 在途：写库还没交回结论，谁都不许退栈，闸门必须按住 ──
            Cell(
                "在途 + 没结论 + 新建 ⇒ 闸门按住等结论（转屏后的新 composition 不许起第二趟）",
                saveInFlight = true, writeOutcome = EditorWriteOutcome.NoConclusion,
                tailScopeCancelled = false, identity = EditorDraftIdentity.CreatingNewCourse,
                expectedTier = "HoldGateAwaitingConclusion",
            ),
            Cell(
                "在途 + 没结论 + 编辑 ⇒ 同一档（身份在途这一档不参与）",
                saveInFlight = true, writeOutcome = EditorWriteOutcome.NoConclusion,
                tailScopeCancelled = false, identity = EditorDraftIdentity.EditingExistingCourse,
                expectedTier = "HoldGateAwaitingConclusion",
            ),
            Cell(
                "在途 + 还没结论而收尾已没了 ⇒ 还是等结论 不许弹（库里此刻还没有那一行）",
                saveInFlight = true, writeOutcome = EditorWriteOutcome.NoConclusion,
                tailScopeCancelled = true, identity = EditorDraftIdentity.CreatingNewCourse,
                expectedTier = "HoldGateAwaitingConclusion",
            ),
            // ── 失败：库里没有那一行 ⇒ 任何身份、任何 scope 命运都不退栈 ──
            Cell(
                "没落成 + 页面活着 ⇒ 不退栈（红条由页面那一支念）",
                saveInFlight = false, writeOutcome = EditorWriteOutcome.Failed,
                tailScopeCancelled = false, identity = EditorDraftIdentity.CreatingNewCourse,
                expectedTier = "StayWithoutPop",
            ),
            Cell(
                "没落成 + 收尾被取消 + 新建 ⇒ 还是不退栈（退了就是把草稿咽掉）",
                saveInFlight = false, writeOutcome = EditorWriteOutcome.Failed,
                tailScopeCancelled = true, identity = EditorDraftIdentity.CreatingNewCourse,
                expectedTier = "StayWithoutPop",
            ),
            Cell(
                "没落成 + 收尾被取消 + 编辑 ⇒ 不退栈",
                saveInFlight = false, writeOutcome = EditorWriteOutcome.Failed,
                tailScopeCancelled = true, identity = EditorDraftIdentity.EditingExistingCourse,
                expectedTier = "StayWithoutPop",
            ),
            // ── 陈旧回执排第一：别处发起的结论，一枚字都不许动（也不许替它按住闸门）──
            Cell(
                "结论属于别处那一趟 + 新建 ⇒ 什么都不做（新页不许替上一趟退栈）",
                saveInFlight = false, writeOutcome = EditorWriteOutcome.ForeignConclusion,
                tailScopeCancelled = true, identity = EditorDraftIdentity.CreatingNewCourse,
                expectedTier = "IgnoreForeignConclusion",
            ),
            Cell(
                "结论属于别处那一趟 + 在途旗标也举着 ⇒ 陈旧那一档仍排第一（顺序即语义）",
                saveInFlight = true, writeOutcome = EditorWriteOutcome.ForeignConclusion,
                tailScopeCancelled = true, identity = EditorDraftIdentity.EditingExistingCourse,
                expectedTier = "IgnoreForeignConclusion",
            ),
            Cell(
                "结论属于别处那一趟 + 页面正自己写库 ⇒ 同上 不许把在途那一档抢过去",
                saveInFlight = true, writeOutcome = EditorWriteOutcome.ForeignConclusion,
                tailScopeCancelled = false, identity = EditorDraftIdentity.CreatingNewCourse,
                expectedTier = "IgnoreForeignConclusion",
            ),
            // ── 没发起过保存：一切照常 ──
            Cell(
                "没在写库也没结论 + 新建 ⇒ 闸门放开、不退栈",
                saveInFlight = false, writeOutcome = EditorWriteOutcome.NoConclusion,
                tailScopeCancelled = false, identity = EditorDraftIdentity.CreatingNewCourse,
                expectedTier = "NothingPending",
            ),
            Cell(
                "没在写库也没结论 + 编辑 ⇒ 同一档",
                saveInFlight = false, writeOutcome = EditorWriteOutcome.NoConclusion,
                tailScopeCancelled = false, identity = EditorDraftIdentity.EditingExistingCourse,
                expectedTier = "NothingPending",
            ),
            Cell(
                "没结论而收尾旗说没了 ⇒ 没有结论就没有退栈的依据 照旧 NothingPending",
                saveInFlight = false, writeOutcome = EditorWriteOutcome.NoConclusion,
                tailScopeCancelled = true, identity = EditorDraftIdentity.CreatingNewCourse,
                expectedTier = "NothingPending",
            ),
        )
        for (cell in cells) {
            val finish = EditorSaveFinishing.decide(
                saveInFlight = cell.saveInFlight,
                writeOutcome = cell.writeOutcome,
                tailScopeCancelled = cell.tailScopeCancelled,
                identity = cell.identity,
            )
            assertEquals(
                "格子「${cell.label}」判成了另一档（实到 ${tierName(finish)}）：" +
                    "inFlight=${cell.saveInFlight} outcome=${cell.writeOutcome} " +
                    "cancelled=${cell.tailScopeCancelled} identity=${cell.identity}",
                cell.expectedTier,
                tierName(finish),
            )
        }
    }

    @Test
    fun `全枚举 32 格都有归属且七档全都到得了`() {
        val reached = mutableSetOf<String>()
        var cells = 0
        for (inFlight in listOf(false, true)) {
            for (outcome in EditorWriteOutcome.entries) {
                for (cancelled in listOf(false, true)) {
                    for (identity in EditorDraftIdentity.entries) {
                        cells++
                        reached += tierName(
                            EditorSaveFinishing.decide(inFlight, outcome, cancelled, identity)
                        )
                    }
                }
            }
        }
        assertEquals("四枚入参的组合枚数（2 × 4 × 2 × 2）：", 32, cells)
        assertEquals(
            "七档里有到不了的死档，或多出了不在册的第八档（本卡交付的是七档，改档数要回来重判）：" +
                reached.toSortedSet(),
            setOf(
                "NothingPending",
                "HoldGateAwaitingConclusion",
                "PageTailOwnsThePop",
                "DestinationPopsEditor",
                "DestinationPopsEditorHoldingGate",
                "StayWithoutPop",
                "IgnoreForeignConclusion",
            ).toSortedSet(),
            reached.toSortedSet(),
        )
    }

    @Test
    fun `闸门与退栈两枚投影逐档点名`() {
        val tiers = listOf(
            // 档位 → 该不该按住闸门 → 归不归目的地层退栈
            EditorSaveFinish.NothingPending to (false to false),
            EditorSaveFinish.HoldGateAwaitingConclusion to (true to false),
            EditorSaveFinish.PageTailOwnsThePop to (false to false),
            EditorSaveFinish.DestinationPopsEditor to (false to true),
            EditorSaveFinish.DestinationPopsEditorHoldingGate to (true to true),
            EditorSaveFinish.StayWithoutPop to (false to false),
            EditorSaveFinish.IgnoreForeignConclusion to (false to false),
        )
        assertEquals("投影表覆盖的档数应当是七档：", 7, tiers.size)
        for ((finish, projection) in tiers) {
            assertEquals(
                "「${tierName(finish)}」这一档按不住闸门这件事变了（实到 " +
                    "${projection.first}，盘上判到 ${EditorSaveFinishing.holdsSaveGate(finish)}）：" +
                    "在途那一档与新建落成那一档放开的后果就是第二趟 `id = 0L` 起手",
                projection.first,
                EditorSaveFinishing.holdsSaveGate(finish),
            )
            assertEquals(
                "「${tierName(finish)}」这一档归不归目的地层退栈变了（表里 ${projection.second}，" +
                    "盘上 ${EditorSaveFinishing.claimedByDestination(finish)}）：页面那一支活着的时候" +
                    "目的地弹 = 对着 navController 多 pop 一格",
                projection.second,
                EditorSaveFinishing.claimedByDestination(finish),
            )
        }
        // 两枚投影不许把同一档既说成「不动」又说成「要弹」
        assertTrue(
            "落成 + 收尾被取消那一档必须同时是「目的地认领」的档：" +
                EditorSaveFinishing.claimedByDestination(EditorSaveFinish.DestinationPopsEditorHoldingGate)
                    .toString(),
            EditorSaveFinishing.claimedByDestination(EditorSaveFinish.DestinationPopsEditorHoldingGate) &&
                EditorSaveFinishing.holdsSaveGate(EditorSaveFinish.DestinationPopsEditorHoldingGate),
        )
        assertNotEquals(
            "页面活着那一档与目的地认领那一档不许混成同一档（混了就是连 pop 两格）",
            tierName(EditorSaveFinish.PageTailOwnsThePop),
            tierName(EditorSaveFinish.DestinationPopsEditor),
        )
    }

    private data class ReadoutCell(
        val label: String,
        val stage: EditorSaveStage?,
        val myRequestId: Long,
        val expectedOutcome: EditorWriteOutcome,
        val expectedInFlight: Boolean,
        val expectedCancelled: Boolean,
    )

    @Test
    fun `readout 把 VM 那一枚旗读成三枚事实 配对与报到两格各有格子`() {
        val cells = listOf(
            ReadoutCell(
                "VM 里压根没有旗 ⇒ 没结论、没在途、收尾也没被取消",
                stage = null, myRequestId = 7L,
                expectedOutcome = EditorWriteOutcome.NoConclusion,
                expectedInFlight = false, expectedCancelled = false,
            ),
            ReadoutCell(
                "这一页还没发起过（NO_REQUEST）而旗属于别处 ⇒ 陈旧回执那一档",
                stage = EditorSaveStage(9L, EditorSavePhase.Landed, tailReported = true),
                myRequestId = EditorSaveSentinel.NO_REQUEST,
                expectedOutcome = EditorWriteOutcome.ForeignConclusion,
                expectedInFlight = false, expectedCancelled = false,
            ),
            ReadoutCell(
                "旗是上一 entry 的（号不对）⇒ 陈旧回执 且不许替它举在途旗",
                stage = EditorSaveStage(9L, EditorSavePhase.Writing, tailReported = false),
                myRequestId = 7L,
                expectedOutcome = EditorWriteOutcome.ForeignConclusion,
                expectedInFlight = false, expectedCancelled = false,
            ),
            ReadoutCell(
                "本 entry 的号 + 还在写 ⇒ 在途为真、没结论、收尾还活着",
                stage = EditorSaveStage(7L, EditorSavePhase.Writing, tailReported = false),
                myRequestId = 7L,
                expectedOutcome = EditorWriteOutcome.NoConclusion,
                expectedInFlight = true, expectedCancelled = false,
            ),
            ReadoutCell(
                "题眼：本 entry 落成而页面那一支没报到 ⇒ 收尾已被取消",
                stage = EditorSaveStage(7L, EditorSavePhase.Landed, tailReported = false),
                myRequestId = 7L,
                expectedOutcome = EditorWriteOutcome.Landed,
                expectedInFlight = false, expectedCancelled = true,
            ),
            ReadoutCell(
                "本 entry 落成且页面那一支报到了 ⇒ 收尾还在 归页面退栈",
                stage = EditorSaveStage(7L, EditorSavePhase.Landed, tailReported = true),
                myRequestId = 7L,
                expectedOutcome = EditorWriteOutcome.Landed,
                expectedInFlight = false, expectedCancelled = false,
            ),
            ReadoutCell(
                "本 entry 没落成而页面那一支没报到 ⇒ 失败 + 收尾没了（仍不许弹）",
                stage = EditorSaveStage(7L, EditorSavePhase.Failed, tailReported = false),
                myRequestId = 7L,
                expectedOutcome = EditorWriteOutcome.Failed,
                expectedInFlight = false, expectedCancelled = true,
            ),
            ReadoutCell(
                "本 entry 没落成且报到了 ⇒ 失败 + 收尾还在",
                stage = EditorSaveStage(7L, EditorSavePhase.Failed, tailReported = true),
                myRequestId = 7L,
                expectedOutcome = EditorWriteOutcome.Failed,
                expectedInFlight = false, expectedCancelled = false,
            ),
            ReadoutCell(
                "在途那一档就算 tailReported 被误写成 true 也不许冒充结论已交回",
                stage = EditorSaveStage(7L, EditorSavePhase.Writing, tailReported = true),
                myRequestId = 7L,
                expectedOutcome = EditorWriteOutcome.NoConclusion,
                expectedInFlight = true, expectedCancelled = false,
            ),
        )
        for (cell in cells) {
            val readout = EditorSaveFinishing.readout(cell.stage, cell.myRequestId)
            assertEquals("格子「${cell.label}」的写库结果读错了：", cell.expectedOutcome, readout.writeOutcome)
            assertEquals("格子「${cell.label}」的在途旗读错了：", cell.expectedInFlight, readout.saveInFlight)
            assertEquals("格子「${cell.label}」的收尾 scope 命运读错了：", cell.expectedCancelled, readout.tailScopeCancelled)
        }
    }

    @Test
    fun `readout 交回的三枚事实直接喂 decide 时题眼那一格落到目的地退栈`() {
        val rotatedCreate = EditorSaveFinishing.readout(
            stage = EditorSaveStage(requestId = 7L, phase = EditorSavePhase.Landed, tailReported = false),
            myRequestId = 7L,
        ).finish(EditorDraftIdentity.CreatingNewCourse)
        assertEquals(
            "「点保存到落库之间转一次屏」那一格的整条链（readout → finish）判到的不是目的地退栈：",
            "DestinationPopsEditorHoldingGate",
            tierName(rotatedCreate),
        )
        val aliveTail = EditorSaveFinishing.readout(
            stage = EditorSaveStage(requestId = 7L, phase = EditorSavePhase.Landed, tailReported = true),
            myRequestId = 7L,
        ).finish(EditorDraftIdentity.CreatingNewCourse)
        assertEquals(
            "页面那一支报到过的那一格判成了目的地退栈（同一枚栈被弹两回）：",
            "PageTailOwnsThePop",
            tierName(aliveTail),
        )
    }

    private fun tierName(finish: EditorSaveFinish): String = finish::class.simpleName
        ?: throw IllegalStateException("档位拿不到类名：$finish")
}
