package com.buaa.schedule.domain.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T271②：`OnboardingConsentGate` 的表驱动逐格账（纯 JVM，无 Robolectric、无设备、不读钟）。
 *
 * T280② 添了第三手两枚判据（`逐格 residentDialogCopy 的八格账（payload 决定话）` 与
 * `residentDialogCopyTiersNeverPointOutsideTheDialog`）：钉的是**弹窗正文那几句「当前」由
 * payload 交回的值决定、由不得那枚活字段**，两枚边界（stamp 0|1、本屏那一手 false|true）
 * 各占两侧，合计八格 + 三格两侧对照 + 五档话的「不指路」全表扫。
 *
 * 钉的是台账 #410 那一族的两侧格子：
 *
 * - **朝宽**（跳过那一手也把同意位置真）：题眼格
 *   「[OnboardingGesture.HeaderSkip] + 没勾 + 盘上没同意」必须拿到 `writesConsent = false`，
 *   而 [OnboardingGesture.ImportLanding] 那一族即使调用点把 `privacyBoxTick` 递成真也不许落盘
 *   —— 那三枚屏上根本没有「我已阅读并同意」那一格，把它当真就是**替用户同意**。
 *   把内核里的 `writesConsent` 写成常量真，当场红两枚格加一整列不变量。
 * - **朝窄**（把已同意的用户拖回提示）：`privacyAccepted = true` 的两枚格必须拿到
 *   `ConsentNoticeSilent` / `NothingToSay`，红点与那一句都不许出现。
 *   把 `screenNotice` 里「已同意」那一支摘掉就红这一格。
 *
 * 另钉三枚跨档位不变量，它们合起来就是本卡的题眼：
 * 1. 死路档只在「常驻页没有同意那一手」时才可能出现（[PathSevered] ⟹ 入口旗为假）；
 * 2. 指向首启引导的那一档，只在引导那棵子树还组合得上时才允许出现 —— 改前那句
 *    「同意那一格在首启引导页」押的就是这条；
 * 3. 每一档交回的话必须与它交回的路**同一段文字**（有话指某处就得写清那一屏那一手，
 *    没路可指就不许出现任何一屏的名字 —— 不许承诺一枚盘面上没有的按钮）。
 */
class OnboardingConsentGateTest {

    private data class GestureCell(
        val label: String,
        val gesture: OnboardingGesture,
        val tick: Boolean,
        val accepted: Boolean,
        val onboardingCompleted: Boolean,
        val permanentEntry: Boolean,
        val expectedTier: String,
        val expectedWritesConsent: Boolean,
        val expectedWritesOnboardingDone: Boolean,
        val expectedReachable: Boolean,
        val expectedPath: ConsentPath?,
    )

    private data class NoticeCell(
        val label: String,
        val needsConsent: Boolean,
        val accepted: Boolean,
        val onboardingCompleted: Boolean,
        val permanentEntry: Boolean,
        val expectedTier: String,
        val expectedMark: Boolean,
        val expectedPath: ConsentPath?,
    )

    // ─────────────── 第一手：这一手落不落盘、出去之后指哪儿 ───────────────

    @Test
    fun `逐格 gestureDisposition 的十四格账`() {
        for ((index, cell) in gestureCells().withIndex()) {
            val mark = "#${index + 1} ${cell.label}"
            val verdict = OnboardingConsentGate.gestureDisposition(
                gesture = cell.gesture,
                privacyBoxTick = cell.tick,
                privacyAccepted = cell.accepted,
                onboardingCompleted = cell.onboardingCompleted,
                permanentEntryAvailable = cell.permanentEntry,
            )
            assertEquals(
                "$mark\n  输入：${cell.gesture} tick=${cell.tick} accepted=${cell.accepted} " +
                    "onboardingCompleted=${cell.onboardingCompleted} entry=${cell.permanentEntry}" +
                    "\n  实到档位：" + verdict::class.simpleName,
                cell.expectedTier,
                verdict::class.simpleName,
            )
            assertEquals(
                "$mark 的「要不要落同意位」——只有用户当面勾了才落，常量真就是替他同意",
                cell.expectedWritesConsent,
                verdict.writesConsent,
            )
            assertEquals(
                "$mark 的「要不要落引导已完成」",
                cell.expectedWritesOnboardingDone,
                verdict.writesOnboardingDone,
            )
            assertEquals(
                "$mark 之后同意这件事还回不回得来（改前那一形就是 false 而屏上还写着去引导页同意）",
                cell.expectedReachable,
                verdict.consentStillReachable,
            )
            assertEquals(
                "$mark 交回的那条同意路：${cell.expectedPath?.screen ?: "无"}",
                cell.expectedPath,
                verdict.path,
            )
            assertCopyMatchesPath(mark, verdict.path, verdict.spoken, consentHandled = cell.expectedWritesConsent)
        }
    }

    private fun gestureCells(): List<GestureCell> = listOf(
        // ── 题眼：跳过 + 没勾 + 盘上没同意 ⇒ 同意位不动，但路必须还在（乙档）──
        GestureCell(
            "跳过 + 没勾 + 常驻页有同意那一格", OnboardingGesture.HeaderSkip,
            tick = false, accepted = false, onboardingCompleted = false, permanentEntry = true,
            expectedTier = "PathToPermanentEntry",
            expectedWritesConsent = false, expectedWritesOnboardingDone = true,
            expectedReachable = true, expectedPath = ConsentPath.settingsEntry,
        ),
        // ── 屏盘对齐：勾了再跳过，落的就是用户这一格的手（不是替他置真）──
        GestureCell(
            "跳过 + 勾了 + 盘上还没有", OnboardingGesture.HeaderSkip,
            tick = true, accepted = false, onboardingCompleted = false, permanentEntry = true,
            expectedTier = "ConsentFromTick",
            expectedWritesConsent = true, expectedWritesOnboardingDone = true,
            expectedReachable = true, expectedPath = null,
        ),
        // ── 改前的形状：跳过而不带同意、常驻页又没有那一格 ⇒ 门焊死，只能说破 ──
        GestureCell(
            "跳过 + 没勾 + 常驻页也没有同意那一手（改前一形）", OnboardingGesture.HeaderSkip,
            tick = false, accepted = false, onboardingCompleted = false, permanentEntry = false,
            expectedTier = "PathSevered",
            expectedWritesConsent = false, expectedWritesOnboardingDone = true,
            expectedReachable = false, expectedPath = null,
        ),
        GestureCell(
            "跳过 + 勾了 + 常驻页没有那一格 用户的手照落", OnboardingGesture.HeaderSkip,
            tick = true, accepted = false, onboardingCompleted = false, permanentEntry = false,
            expectedTier = "ConsentFromTick",
            expectedWritesConsent = true, expectedWritesOnboardingDone = true,
            expectedReachable = true, expectedPath = null,
        ),
        // ── 隐私步的「下一步」：带同意，但不落「引导已完成」（两扇不同的门）──
        GestureCell(
            "隐私步下一步 + 勾了", OnboardingGesture.PrivacyStepNext,
            tick = true, accepted = false, onboardingCompleted = false, permanentEntry = true,
            expectedTier = "ConsentFromTick",
            expectedWritesConsent = true, expectedWritesOnboardingDone = false,
            expectedReachable = true, expectedPath = null,
        ),
        GestureCell(
            "隐私步下一步 + 没勾 什么都不落，也不许把引导判成完成", OnboardingGesture.PrivacyStepNext,
            tick = false, accepted = false, onboardingCompleted = false, permanentEntry = true,
            expectedTier = "ConsentNotGivenOnPrivacyStep",
            expectedWritesConsent = false, expectedWritesOnboardingDone = false,
            expectedReachable = true, expectedPath = null,
        ),
        // ── 正常流程：过了隐私步的人盘上早有同意 ──
        GestureCell(
            "导入落点 + 盘上已同意", OnboardingGesture.ImportLanding,
            tick = false, accepted = true, onboardingCompleted = false, permanentEntry = true,
            expectedTier = "NothingToSay",
            expectedWritesConsent = false, expectedWritesOnboardingDone = true,
            expectedReachable = true, expectedPath = null,
        ),
        GestureCell(
            "手动添加落点 + 盘上已同意", OnboardingGesture.ManualAddLanding,
            tick = false, accepted = true, onboardingCompleted = false, permanentEntry = true,
            expectedTier = "NothingToSay",
            expectedWritesConsent = false, expectedWritesOnboardingDone = true,
            expectedReachable = true, expectedPath = null,
        ),
        GestureCell(
            "末步落首页 + 盘上已同意 + 常驻页没有那一格也无妨", OnboardingGesture.LastStepDone,
            tick = false, accepted = true, onboardingCompleted = true, permanentEntry = false,
            expectedTier = "NothingToSay",
            expectedWritesConsent = false, expectedWritesOnboardingDone = true,
            expectedReachable = true, expectedPath = null,
        ),
        // ── 三枚「只可能出现在隐私步之后」的出口却在盘上没同意：矛盾档，不许静默 ──
        GestureCell(
            "导入落点 + 盘上没同意 流程顺序被改坏", OnboardingGesture.ImportLanding,
            tick = false, accepted = false, onboardingCompleted = false, permanentEntry = true,
            expectedTier = "FlowOrderContradiction",
            expectedWritesConsent = false, expectedWritesOnboardingDone = true,
            expectedReachable = true, expectedPath = ConsentPath.settingsEntry,
        ),
        GestureCell(
            "手动添加落点 + 盘上没同意", OnboardingGesture.ManualAddLanding,
            tick = false, accepted = false, onboardingCompleted = false, permanentEntry = true,
            expectedTier = "FlowOrderContradiction",
            expectedWritesConsent = false, expectedWritesOnboardingDone = true,
            expectedReachable = true, expectedPath = ConsentPath.settingsEntry,
        ),
        GestureCell(
            "末步落首页 + 盘上没同意 + 入口也丢了", OnboardingGesture.LastStepDone,
            tick = false, accepted = false, onboardingCompleted = true, permanentEntry = false,
            expectedTier = "FlowOrderContradiction",
            expectedWritesConsent = false, expectedWritesOnboardingDone = true,
            expectedReachable = false, expectedPath = null,
        ),
        // ── 朝宽那一侧第二格：导入步的屏上没有勾选框，调用点递来真也不许落 ──
        GestureCell(
            "导入落点 + 递来一枚屏上根本没有的勾 不落同意", OnboardingGesture.ImportLanding,
            tick = true, accepted = false, onboardingCompleted = false, permanentEntry = true,
            expectedTier = "FlowOrderContradiction",
            expectedWritesConsent = false, expectedWritesOnboardingDone = true,
            expectedReachable = true, expectedPath = ConsentPath.settingsEntry,
        ),
        GestureCell(
            "跳过 + 勾了 + 盘上早有了 不再多写一遍", OnboardingGesture.HeaderSkip,
            tick = true, accepted = true, onboardingCompleted = true, permanentEntry = true,
            expectedTier = "NothingToSay",
            expectedWritesConsent = false, expectedWritesOnboardingDone = true,
            expectedReachable = true, expectedPath = null,
        ),
    )

    // ─────────────── 第二手：这一屏要不要持续念、指哪儿 ───────────────

    @Test
    fun `逐格 screenNotice 的八格账`() {
        val cells = listOf(
            // ── 朝宽那一侧：不需要同意的屏（本机零出网）不许挂红点、不许念那一句 ──
            NoticeCell(
                "不需要同意 + 没同意", needsConsent = false, accepted = false,
                onboardingCompleted = true, permanentEntry = true,
                expectedTier = "ConsentNoticeSilent", expectedMark = false, expectedPath = null,
            ),
            NoticeCell(
                "不需要同意 + 已同意", needsConsent = false, accepted = true,
                onboardingCompleted = false, permanentEntry = false,
                expectedTier = "ConsentNoticeSilent", expectedMark = false, expectedPath = null,
            ),
            // ── 朝窄那一侧：处理过的用户不能被拖回提示里 ──
            NoticeCell(
                "要同意 + 已同意 + 引导已完成 静默", needsConsent = true, accepted = true,
                onboardingCompleted = true, permanentEntry = true,
                expectedTier = "ConsentNoticeSilent", expectedMark = false, expectedPath = null,
            ),
            NoticeCell(
                "要同意 + 已同意 + 引导还没结束 也静默", needsConsent = true, accepted = true,
                onboardingCompleted = false, permanentEntry = false,
                expectedTier = "ConsentNoticeSilent", expectedMark = false, expectedPath = null,
            ),
            // ── 引导那棵子树还进得去：指的就是眼前那一屏 ──
            NoticeCell(
                "要同意 + 没同意 + 引导还在", needsConsent = true, accepted = false,
                onboardingCompleted = false, permanentEntry = true,
                expectedTier = "ConsentNoticeToHandle", expectedMark = true,
                expectedPath = ConsentPath.onboardingEntry,
            ),
            NoticeCell(
                "要同意 + 没同意 + 引导还在 + 常驻页没那一格", needsConsent = true, accepted = false,
                onboardingCompleted = false, permanentEntry = false,
                expectedTier = "ConsentNoticeToHandle", expectedMark = true,
                expectedPath = ConsentPath.onboardingEntry,
            ),
            // ── 乙档落地后：引导关门，指常驻页 ──
            NoticeCell(
                "要同意 + 没同意 + 引导已关门 + 常驻页有那一格", needsConsent = true, accepted = false,
                onboardingCompleted = true, permanentEntry = true,
                expectedTier = "ConsentNoticeToHandle", expectedMark = true,
                expectedPath = ConsentPath.settingsEntry,
            ),
            // ── 改前的形状：门全关，念一句实话而不是一枚不存在的按钮 ──
            NoticeCell(
                "要同意 + 没同意 + 引导已关门 + 哪儿都没有", needsConsent = true, accepted = false,
                onboardingCompleted = true, permanentEntry = false,
                expectedTier = "ConsentNoticeToHandle", expectedMark = true, expectedPath = null,
            ),
        )

        for ((index, cell) in cells.withIndex()) {
            val mark = "#${index + 1} ${cell.label}"
            val notice = OnboardingConsentGate.screenNotice(
                screenNeedsConsent = cell.needsConsent,
                privacyAccepted = cell.accepted,
                onboardingCompleted = cell.onboardingCompleted,
                permanentEntryAvailable = cell.permanentEntry,
            )
            assertEquals(
                "$mark\n  输入：needsConsent=${cell.needsConsent} accepted=${cell.accepted} " +
                    "onboardingCompleted=${cell.onboardingCompleted} entry=${cell.permanentEntry}" +
                    "\n  实到档位：" + notice::class.simpleName,
                cell.expectedTier,
                notice::class.simpleName,
            )
            assertEquals(
                "$mark 的持续提示标记（红点）——改前那一形没人念这件事，更新检查就永久静默",
                cell.expectedMark,
                notice.showsMark,
            )
            val handled = notice as? ConsentScreenNotice.ConsentNoticeToHandle
            if (cell.expectedTier == "ConsentNoticeToHandle") {
                assertEquals("$mark 交回的那条路", cell.expectedPath, handled?.path)
            } else {
                assertNull("$mark 静默档不该有话要说", notice.line)
            }
            assertCopyMatchesPath(
                mark,
                (notice as? ConsentScreenNotice.ConsentNoticeToHandle)?.path,
                notice.line,
                consentHandled = false,
            )
        }
    }

    // ─────────────── 不变量 ───────────────

    /**
     * 不变量一：**没勾就没有同意**。全表扫 `privacyBoxTick = false`，以及扫「勾了但这一屏
     * 根本没有勾选框」的那三枚出口 —— 两族都不许落同意位。这条就是「不许替用户置真」的尺。
     */
    @Test
    fun nothingEverWritesConsentWithoutTheUsersOwnTick() {
        for (gesture in OnboardingGesture.entries) {
            for (accepted in listOf(false, true)) {
                for (done in listOf(false, true)) {
                    for (entry in listOf(false, true)) {
                        val unticked = OnboardingConsentGate.gestureDisposition(
                            gesture = gesture,
                            privacyBoxTick = false,
                            privacyAccepted = accepted,
                            onboardingCompleted = done,
                            permanentEntryAvailable = entry,
                        )
                        assertFalse(
                            "$gesture 在用户没勾那一格时（accepted=$accepted done=$done entry=$entry）" +
                                "落盘了同意位 —— 这就是替用户同意：档位 ${unticked::class.simpleName}",
                            unticked.writesConsent,
                        )
                    }
                }
            }
            if (gesture != OnboardingGesture.PrivacyStepNext && gesture != OnboardingGesture.HeaderSkip) {
                val strayTick = OnboardingConsentGate.gestureDisposition(
                    gesture = gesture,
                    privacyBoxTick = true,
                    privacyAccepted = false,
                    onboardingCompleted = true,
                    permanentEntryAvailable = true,
                )
                assertFalse(
                    "$gesture 那一屏上没有「我已阅读并同意」那枚勾选框，" +
                        "调用点递来的勾不许当用户的手：档位 ${strayTick::class.simpleName}",
                    strayTick.writesConsent,
                )
            }
        }
    }

    /**
     * 不变量二：**死路只可能来自「常驻页没有同意那一手」**。
     * 入口在册而判据还说没有路，就是判据在说谎（改前正是这一形反着骗：无路却念「去引导页」）。
     */
    @Test
    fun severedOnlyWhenNoPermanentEntryIsLeft() {
        for (gesture in OnboardingGesture.entries) {
            for (tick in listOf(false, true)) {
                for (done in listOf(false, true)) {
                    val verdict = OnboardingConsentGate.gestureDisposition(
                        gesture = gesture,
                        privacyBoxTick = tick,
                        privacyAccepted = false,
                        onboardingCompleted = done,
                        permanentEntryAvailable = true,
                    )
                    assertFalse(
                        "常驻页还留着同意那一手，$gesture（tick=$tick done=$done）却被判成死路：" +
                            verdict::class.simpleName,
                        verdict is OnboardingExitDisposition.PathSevered,
                    )
                    if (!verdict.writesConsent) {
                        assertTrue(
                            "$gesture（tick=$tick done=$done）在入口在册时走不到可回头的路：" +
                                verdict::class.simpleName,
                            verdict.consentStillReachable,
                        )
                    }
                    val severedArm = OnboardingConsentGate.gestureDisposition(
                        gesture = gesture,
                        privacyBoxTick = tick,
                        privacyAccepted = false,
                        onboardingCompleted = true,
                        permanentEntryAvailable = false,
                    )
                    if (!severedArm.writesConsent && gesture.writesOnboardingDone &&
                        severedArm !is OnboardingExitDisposition.ConsentNotGivenOnPrivacyStep
                    ) {
                        assertFalse(
                            "$gesture（tick=$tick）把引导那扇门关上了，常驻页又没有同意那一手，" +
                                "判据却说还回得来：" + severedArm::class.simpleName,
                            severedArm.consentStillReachable,
                        )
                    }
                }
            }
        }
    }

    /**
     * 不变量三：**指引导页的那一档，引导必须还进得去**。
     * 台账 #410 的第二半就是这一条 ——「同意那一格在首启引导页」押死在了一页进不去的屏上。
     */
    @Test
    fun onboardingPointerOnlyWhileOnboardingIsOpen() {
        for (needsConsent in listOf(true, false)) {
            for (accepted in listOf(true, false)) {
                for (entry in listOf(true, false)) {
                    val closed = OnboardingConsentGate.screenNotice(
                        screenNeedsConsent = needsConsent,
                        privacyAccepted = accepted,
                        onboardingCompleted = true,
                        permanentEntryAvailable = entry,
                    )
                    assertNotTheOnboardingPage("引导已关门时的提示（needs=$needsConsent accepted=$accepted）", closed)
                }
            }
        }
    }

    /** 第 ⓪ 问要的判据形状：**从跳过进来、从未同意的用户**要走的那几步，逐格念得出。 */
    @Test
    fun consentPathIsCountableFromTheFrontPage() {
        assertEquals(3, ConsentPath.settingsEntry.tapsFromFrontPage)
        assertEquals(2, ConsentPath.onboardingEntry.tapsFromFrontPage)
        assertEquals(2, ConsentPath.settingsEntryFromThisScreen.tapsFromFrontPage)

        val afterSkip = OnboardingConsentGate.screenNotice(
            screenNeedsConsent = true,
            privacyAccepted = false,
            onboardingCompleted = true,
            permanentEntryAvailable = true,
        ) as ConsentScreenNotice.ConsentNoticeToHandle
        assertEquals(ConsentPath.settingsEntry, afterSkip.path)
        assertTrue(
            "出引导之后的那句话必须点名常驻页，否则用户还是找不到：${afterSkip.copy.message}",
            afterSkip.copy.message.contains(ConsentPath.settingsEntry.screen) &&
                afterSkip.copy.message.contains(ConsentPath.settingsEntry.hand),
        )
    }

    /**
     * 出口册子：**四枚**出口落「引导已完成」，**两枚**手能把用户当面勾的那一格落盘。
     * 基点上是 4:1（跳过那一枚把比例钉死成单向门），本卡把它掰成 4:2 ——
     * 枚数差本身不是缺陷，「四枚里一枚都不带同意、又不留路」才是。
     */
    @Test
    fun exitLedgerCountsFourDoneWritesAndTwoConsentHands() {
        val doneWriters = OnboardingGesture.entries.filter { it.writesOnboardingDone }
        assertEquals(
            "落「引导已完成」的出口册子（多一枚少一枚都要改判据）：" +
                doneWriters.joinToString { it.name },
            listOf("HeaderSkip", "ImportLanding", "ManualAddLanding", "LastStepDone"),
            doneWriters.map { it.name },
        )
        val consentHands = OnboardingGesture.entries.filter {
            OnboardingConsentGate.gestureDisposition(
                gesture = it,
                privacyBoxTick = true,
                privacyAccepted = false,
                onboardingCompleted = false,
                permanentEntryAvailable = true,
            ).writesConsent
        }
        assertEquals(
            "能把「用户当面勾了」落盘的出口册子（基点上只有 PrivacyStepNext 一枚，跳过那一枚不带）：" +
                consentHands.joinToString { it.name },
            listOf("HeaderSkip", "PrivacyStepNext"),
            consentHands.map { it.name }.sorted(),
        )
    }

    // ─────────────── 第三手（T280② 新增）：弹窗正文那几句「当前」由 payload 决定 ───────────────

    private data class DialogCell(
        val label: String,
        val consentStamp: Long,
        val handLanded: Boolean,
        /** 期望落在哪一档：Agreed = payload 说已同意 / Handed = 本屏那一手 / NotYet = 还没同意 */
        val expectedTier: String,
        /** 那一格日期串该不该出现（只有 payload 说已同意那一侧才许念它） */
        val expectLabelSpoken: Boolean,
    )

    /**
     * 第 ⓪ 问那一半的表：**正文的话由 payload 交回的值决定，而不是由那枚活字段决定**。
     *
     * 两枚边界各占两侧：`consentStamp` 在 0 与 1 之间（payload 说「没有」与「有过」）、
     * `handLanded` 在 false 与 true 之间（这一屏刚按过同意与否）。
     * 朝宽那一侧（把活字段当输入的形状）在本表里表现为：payload<=0 时却念出已同意、
     * 或把 payload<=0 那格的日期串漏进正文 —— 两格都当场红。
     * 朝窄那一侧：payload>0 还念「还没同意」，或本屏那一手按过之后仍把用户留在「不指路」那一句里。
     */
    @Test
    fun `逐格 residentDialogCopy 的八格账（payload 决定话）`() {
        for ((index, cell) in dialogCells().withIndex()) {
            val mark = "#${index + 1} ${cell.label}"
            val label = "假日期串[$cell.consentStamp]"
            val copy = OnboardingConsentGate.residentDialogCopy(
                consentStamp = cell.consentStamp,
                consentAtLabel = label,
                handLandedOnThisScreen = cell.handLanded,
            )
            val tier = when {
                copy == OnboardingConsentGate.entryInsideDialogCopy() -> "NotYet"
                copy.message.contains(LANDED_ON_THIS_SCREEN_MARK) -> "Handed"
                copy.message.contains("已同意（") -> "Agreed"
                else -> "认不出来：" + copy.message
            }
            assertEquals("$mark\n  输入：stamp=${cell.consentStamp} handLanded=${cell.handLanded}", cell.expectedTier, tier)
            assertTrue("$mark 标题与正文都得有话：实到 $copy", copy.title.isNotBlank() && copy.message.isNotBlank())
            assertEquals(
                "$mark 那一格日期串只许在 payload 说已同意那一侧出场（漏进别的档 = 把没读到的值念成读到了）",
                cell.expectLabelSpoken,
                copy.message.contains(label),
            )
            assertDialogCopyStaysInsideTheDialog(mark, copy)
        }
        // 两侧对照：同一枚 payload<=0 换一手就得换档，而 payload>0 那一侧一手不换档
        assertEquals(
            "payload=0 且本屏没按过 = 就是 entryInsideDialogCopy() 那一档（不指路的那一句）",
            OnboardingConsentGate.entryInsideDialogCopy(),
            OnboardingConsentGate.residentDialogCopy(0L, "任意", handLandedOnThisScreen = false),
        )
        assertEquals(
            "payload>0 时本屏那一手不许把档换掉（正文只跟 payload 走，活字段与手都不许翻它）",
            OnboardingConsentGate.residentDialogCopy(1L, "同一串", handLandedOnThisScreen = true).message,
            OnboardingConsentGate.residentDialogCopy(1L, "同一串", handLandedOnThisScreen = false).message,
        )
    }

    private fun dialogCells(): List<DialogCell> = listOf(
        // ── payload 边界：0 与 -1 都算「盘上没有」那一侧，两侧各两格 ──
        DialogCell("payload = 0 · 本屏没按过", 0L, false, expectedTier = "NotYet", expectLabelSpoken = false),
        DialogCell("payload = 0 · 本屏刚按下同意", 0L, true, expectedTier = "Handed", expectLabelSpoken = false),
        DialogCell("payload = -1（越界值也归「没有」那一侧）", -1L, false, "NotYet", expectLabelSpoken = false),
        DialogCell("payload = -1 · 本屏刚按下同意", -1L, true, "Handed", expectLabelSpoken = false),
        // ── payload = 1 与 Long.MAX_VALUE：「有过同意」那一侧的两端 ──
        DialogCell("payload = 1（边界另一侧的最小值）", 1L, false, "Agreed", expectLabelSpoken = true),
        DialogCell("payload = 1 · 本屏也按过（payload 说了算，那一手不许翻它）", 1L, true, "Agreed", expectLabelSpoken = true),
        DialogCell("payload = Long.MAX_VALUE", Long.MAX_VALUE, false, "Agreed", expectLabelSpoken = true),
        DialogCell("payload = Long.MAX_VALUE · 本屏按过", Long.MAX_VALUE, true, "Agreed", expectLabelSpoken = true),
    )

    /**
     * 不变量：**弹窗正文那三档话都不指路**。
     *
     * 入口就在这颗弹窗里、那颗按钮就在下面，所以这三档都不许提任何别的屏（也不许出现某条
     * [ConsentPath] 的那一屏那一手）—— 而它们当然也不许读那枚活字段：读一次就少一次 payload 的担保，
     * 收场动画那几帧里当场翻面。这一枚把「指路」与「回读」两件事一起钉在**全表**上，
     * 与上面那八格互补：八格管档位落点对不对，这一枚管话里没有别处。
     */
    @Test
    fun residentDialogCopyTiersNeverPointOutsideTheDialog() {
        val tiers = listOf(
            OnboardingConsentGate.residentDialogCopy(0L, "串", handLandedOnThisScreen = false),
            OnboardingConsentGate.residentDialogCopy(0L, "串", handLandedOnThisScreen = true),
            OnboardingConsentGate.residentDialogCopy(7L, "串", handLandedOnThisScreen = false),
            OnboardingConsentGate.residentDialogCopy(7L, "串", handLandedOnThisScreen = true),
            OnboardingConsentGate.entryInsideDialogCopy(),
        )
        tiers.forEach { copy -> assertDialogCopyStaysInsideTheDialog(copy.message, copy) }
        val inside = OnboardingConsentGate.entryInsideDialogCopy()
        assertTrue(
            "payload 为 0 那一档必须把「就在下面那一格按」说出来，否则用户还是得去别处找：" + inside.message,
            inside.message.contains(CONSENT_BUTTON_TEXT),
        )
        assertNotEquals(
            "本屏那一手落盘之后不许还念「还没按下」那一句（那是把用户的手吞掉）：",
            OnboardingConsentGate.entryInsideDialogCopy().message,
            OnboardingConsentGate.residentDialogCopy(0L, "串", handLandedOnThisScreen = true).message,
        )
    }

    // ─────────────── helpers ───────────────

    /** 有话指某处就得写清那一屏那一手；没路可指就不许出现任何一屏的名字 */
    private fun assertCopyMatchesPath(
        mark: String,
        path: ConsentPath?,
        copy: ConsentCopy?,
        consentHandled: Boolean,
    ) {
        if (consentHandled || copy == null) return
        if (path == null) {
            assertFalse(
                "$mark 没有可走的路，那句话却点了首启引导那一屏：${copy.message}",
                copy.message.contains(ConsentPath.onboardingEntry.screen),
            )
            assertFalse(
                "$mark 没有可走的路，那句话却承诺了常驻页那一格：${copy.message}",
                copy.message.contains(ConsentPath.settingsEntry.screen),
            )
        } else {
            assertTrue(
                "$mark 交回的路是「${path.screen}」，那句话却没写出这一屏：${copy.message}",
                copy.message.contains(path.screen),
            )
            assertTrue(
                "$mark 交回的路要用户点「${path.hand}」，那句话却没写出这一手：${copy.message}",
                copy.message.contains(path.hand),
            )
        }
    }

    private fun assertNotTheOnboardingPage(mark: String, notice: ConsentScreenNotice) {
        val path = (notice as? ConsentScreenNotice.ConsentNoticeToHandle)?.path
        assertFalse(
            "$mark 引导那棵子树已经不组合了，交回的路还指着首启引导那一屏：${path?.screen}",
            path == ConsentPath.onboardingEntry,
        )
        assertFalse(
            "$mark 那句话把用户指向了再也进不去的一屏：${notice.line?.message}",
            notice.line?.message?.contains(ConsentPath.onboardingEntry.screen) == true,
        )
    }

    /**
     * 弹窗正文那一档的话**不指路**：入口就在这颗弹窗里、那颗按钮就在下面，
     * 所以任何一条 [ConsentPath] 的那一屏都不许出现在话里（出现 = 把用户往别的屏赶，
     * 而那一句本来该由 payload 交回的值当场说完）。
     */
    private fun assertDialogCopyStaysInsideTheDialog(mark: String, copy: ConsentCopy) {
        listOf(ConsentPath.onboardingEntry, ConsentPath.settingsEntry).forEach { entry ->
            assertFalse(
                "$mark 弹窗正文指向了别的屏（话里不该出现「${entry.screen}」）：${copy.title} / ${copy.message}",
                copy.message.contains(entry.screen) || copy.title.contains(entry.screen),
            )
            assertFalse(
                "$mark 弹窗正文把用户赶去点「${entry.hand}」—— 那颗按钮就在这层弹窗里，" +
                    "由 payload 交回的值决定念哪一句：${copy.message}",
                copy.message.contains(entry.hand),
            )
        }
    }

    private companion object {
        /** 本屏那一手落盘之后那句话的记号（与内核 consentHandedInsideDialogCopy 同一串） */
        const val LANDED_ON_THIS_SCREEN_MARK = "已记下你在这一屏按下的那一手"

        /** 常驻页确认位那颗按钮的字面：payload 为 0 那一档必须把它说出来 */
        const val CONSENT_BUTTON_TEXT = "同意隐私说明"
    }
}
