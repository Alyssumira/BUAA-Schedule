package com.buaa.schedule.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T261②：`UpdateCheckGate` 的表驱动逐格账（纯 JVM，无 Robolectric、无设备、不读钟）。
 *
 * 钉的是本卡三处静默各自的那一格，以及它们**两侧**的格子：
 *
 * - **未同意隐私 + 手动点**（题眼）：朝「出声」这一侧是甲档裁定 —— 这一手必须拿到
 *   [UpdateCheckVerdict.BlockedByPrivacy] 且 `announces` 为真；朝「安静」那一侧
 *   （自动那一手同样未同意隐私）必须 `announces` 为假，否则没同意过隐私的用户
 *   每次冷启动都要被一个弹窗打断。两格互为两侧：把 `announces` 写成常量 `true`
 *   或常量 `false` 各红一枚，而改前那一形（整段返回、什么档都不给）两枚都红。
 * - **已忽略 + 又来了更新号**：忽略只许压住**那一个**版本 ⇒ 查到 v0.3.0、忽略的是 v0.2.0
 *   时结论仍是 [UpdateCheckVerdict.NewVersionFound]（朝窄侧：把忽略写成"凡有新的都不许念"
 *   就红这一格）；而查到的正是被忽略那一版时不许再弹一遍 ⇒
 *   [UpdateCheckVerdict.SuppressedByIgnore]（朝宽侧：把忽略判据摘掉就红那一格，
 *   那正是"忽略了还每天 nag"的复发方向），手动那一手在这一档**必须开口说出撤销落点**。
 * - **上一次结果档**：[UpdateCheckGate.restingLine] 三档各说各的话。改前设置页只有一枚
 *   时间戳，成功与失败同形 —— 这里把「失败那句里必须出现『失败』、成功那句里必须出现
 *   『成功』」逐格钉住，两格互相 red 的写法（把 Failed 那支压成 Never 那句）当场红。
 *
 * 另加一枚跨档位不变量：**手动那一手要么在转圈、要么在说话**，
 * 不许出现"按了按钮而屏上什么都没换"那一格（本卡第 1 处病灶的形状）。
 * 反过来自动那一手必须**存在**静默档位，否则就是把所有自动检查都改成弹窗（另一侧的红）。
 */
class UpdateCheckGateTest {

    private data class AdmitCell(
        val label: String,
        val privacyAccepted: Boolean,
        val trigger: UpdateCheckTrigger,
        val throttleAllowsCheck: Boolean,
        val lastOutcome: UpdateLastOutcome,
        val expectedTier: String,
        val expectedAnnounces: Boolean,
        val expectedOutcome: UpdateLastOutcome? = null,
    )

    private data class PresentCell(
        val label: String,
        val trigger: UpdateCheckTrigger,
        val ignoredVersion: String?,
        val newestAboveInstalled: String?,
        val failureReason: String?,
        val expectedTier: String,
        val expectedAnnounces: Boolean,
        val expectedVersion: String? = null,
    )

    private companion object {
        const val IGNORED = "0.2.0"
        const val NEWER = "0.3.0"
        const val AUTO_LABEL = "10月8日 09:12"

        // 偏好里没有时间戳时，设置页的格式器给的就是这一句（epoch 0 ⇒「从未进行」）。
        const val NEVER_LABEL = "从未进行"
    }

    // ─────────────── 第一手：出不出网、该不该出声 ───────────────

    @Test
    fun `逐格 admit 的九格账`() {
        val cells = listOf(
            // ── 题眼：隐私没同意而用户按了按钮 ⇒ 档要给、话要说（改前是整段静默）──
            AdmitCell(
                "未同意隐私 + 手动点 + 节流本来放行", false, UpdateCheckTrigger.Manual, true,
                UpdateLastOutcome.Never, "BlockedByPrivacy", true,
            ),
            AdmitCell(
                "未同意隐私 + 手动点 + 节流也不放行 隐私那一档排在前", false,
                UpdateCheckTrigger.Manual, false,
                UpdateLastOutcome.Succeeded, "BlockedByPrivacy", true,
            ),
            // ── 朝窄那一侧：同一档但自动那一手不许弹窗打断 ──
            AdmitCell(
                "未同意隐私 + 冷启动自动检查 静默但仍有档", false, UpdateCheckTrigger.Automatic, true,
                UpdateLastOutcome.Never, "BlockedByPrivacy", false,
            ),
            AdmitCell(
                "未同意隐私 + 自动 + 今天已经查过 还是先答隐私那一档", false,
                UpdateCheckTrigger.Automatic, false,
                UpdateLastOutcome.Failed, "BlockedByPrivacy", false,
            ),
            // ── 不变量：手动那一手绝不静默（今天的调用点上走不到，走到就是调用点递错了尺）──
            AdmitCell(
                "已同意 + 手动 + 被每日节流压掉 仍然要出声不许 no-op", true,
                UpdateCheckTrigger.Manual, false,
                UpdateLastOutcome.Succeeded, "HeldByThrottle", true, UpdateLastOutcome.Succeeded,
            ),
            // ── 结果档要能顺着档位流到那一行：失败与成功不许同形 ──
            AdmitCell(
                "已同意 + 自动 + 上次是失败 带出来的还是失败档", true,
                UpdateCheckTrigger.Automatic, false,
                UpdateLastOutcome.Failed, "HeldByThrottle", false, UpdateLastOutcome.Failed,
            ),
            AdmitCell(
                "已同意 + 自动 + 这台机器从没查过", true,
                UpdateCheckTrigger.Automatic, false,
                UpdateLastOutcome.Never, "HeldByThrottle", false, UpdateLastOutcome.Never,
            ),
            // ── 放行那一手：状态由调用点摆成「正在检查」，本档不该弹窗 ──
            AdmitCell(
                "已同意 + 自动 + 节流放行 出网", true,
                UpdateCheckTrigger.Automatic, true,
                UpdateLastOutcome.Succeeded, "RunCheck", false,
            ),
            AdmitCell(
                "已同意 + 手动 + 节流放行 出网", true,
                UpdateCheckTrigger.Manual, true,
                UpdateLastOutcome.Failed, "RunCheck", false,
            ),
        )

        for ((index, cell) in cells.withIndex()) {
            val verdict = UpdateCheckGate.admit(
                privacyAccepted = cell.privacyAccepted,
                trigger = cell.trigger,
                throttleAllowsCheck = cell.throttleAllowsCheck,
                lastOutcome = cell.lastOutcome,
            )
            val mark = "#${index + 1} ${cell.label}"
            assertEquals(
                "$mark\n  输入：privacyAccepted=${cell.privacyAccepted} trigger=${cell.trigger} " +
                    "throttleAllowsCheck=${cell.throttleAllowsCheck} lastOutcome=${cell.lastOutcome}" +
                    "\n  实到档位：" + verdict::class.simpleName,
                cell.expectedTier,
                verdict::class.simpleName,
            )
            assertEquals(
                "$mark 的「该不该出声」——弹窗闸门在 announces 上，改前一档都不给所以零反馈",
                cell.expectedAnnounces,
                verdict.announces,
            )
            cell.expectedOutcome?.let { expected ->
                val held = verdict as UpdateCheckVerdict.HeldByThrottle
                assertEquals(
                    "$mark 得把**上一手的结果档**带出来，否则设置页那一行没东西可读（台账 #368）",
                    expected,
                    held.lastOutcome,
                )
            }
            if (verdict is UpdateCheckVerdict.BlockedByPrivacy) {
                assertEquals(
                    "$mark 档位自己得记住是自动还是手动（否则「该不该出声」就只能在调用点猜）",
                    cell.trigger,
                    verdict.trigger,
                )
            }
        }
    }

    // ─────────────── 第二手：查完之后怎么呈现 ───────────────

    @Test
    fun `逐格 present 的十格账`() {
        val cells = listOf(
            // ── 题眼（朝窄那一侧的红点）：忽略只管那一个号，新来的号照旧提示 ──
            PresentCell(
                "忽略 v0.2.0 而查到的是 v0.3.0 自动检查照样提示", UpdateCheckTrigger.Automatic,
                IGNORED, NEWER, null, "NewVersionFound", true, NEWER,
            ),
            PresentCell(
                "忽略 v0.2.0 而查到的是 v0.3.0 手动检查同样提示", UpdateCheckTrigger.Manual,
                IGNORED, NEWER, null, "NewVersionFound", true, NEWER,
            ),
            // ── 题眼（朝宽那一侧的红点）：查到的正是被忽略那一版 ⇒ 有自己的那一档 ──
            PresentCell(
                "忽略的就是 v0.3.0 自动检查不许再弹一遍", UpdateCheckTrigger.Automatic,
                NEWER, NEWER, null, "SuppressedByIgnore", false, NEWER,
            ),
            PresentCell(
                "忽略的就是 v0.3.0 手动那一手必须说出撤销落点", UpdateCheckTrigger.Manual,
                NEWER, NEWER, null, "SuppressedByIgnore", true, NEWER,
            ),
            // ── 没有忽略记录时同一格不许凭空念"已被忽略" ──
            PresentCell(
                "从没忽略过而查到 v0.3.0", UpdateCheckTrigger.Automatic,
                null, NEWER, null, "NewVersionFound", true, NEWER,
            ),
            PresentCell(
                "从没忽略过也没有新版本 手动那一下要念一句没更新", UpdateCheckTrigger.Manual,
                null, null, null, "NothingNewer", true,
            ),
            PresentCell(
                "从没忽略过也没有新版本 自动那一下不许打断", UpdateCheckTrigger.Automatic,
                null, null, null, "NothingNewer", false,
            ),
            PresentCell(
                "被忽略的那一版已经装上了（查不到更新的）不许念成「已被忽略」", UpdateCheckTrigger.Manual,
                NEWER, null, null, "NothingNewer", true,
            ),
            // ── 失败排在忽略与新版本之前：网络挂了不许念成用户自己的决定 ──
            PresentCell(
                "查失败 + 有忽略记录 走的仍是失败档", UpdateCheckTrigger.Manual,
                NEWER, NEWER, "连接超时", "CheckFailed", true,
            ),
            PresentCell(
                "查失败 + 自动 静默但结果档由调用点落盘", UpdateCheckTrigger.Automatic,
                null, null, "HTTP 500", "CheckFailed", false,
            ),
        )

        for ((index, cell) in cells.withIndex()) {
            val verdict = UpdateCheckGate.present(
                trigger = cell.trigger,
                ignoredVersion = cell.ignoredVersion,
                newestAboveInstalled = cell.newestAboveInstalled,
                failureReason = cell.failureReason,
            )
            val mark = "#${index + 1} ${cell.label}"
            assertEquals(
                "$mark\n  输入：trigger=${cell.trigger} ignoredVersion=${cell.ignoredVersion} " +
                    "newestAboveInstalled=${cell.newestAboveInstalled} failureReason=${cell.failureReason}" +
                    "\n  实到档位：" + verdict::class.simpleName,
                cell.expectedTier,
                verdict::class.simpleName,
            )
            assertEquals("$mark 的「该不该出声」", cell.expectedAnnounces, verdict.announces)
            cell.expectedVersion?.let { expected ->
                val version = when (verdict) {
                    is UpdateCheckVerdict.NewVersionFound -> verdict.version
                    is UpdateCheckVerdict.SuppressedByIgnore -> verdict.version
                    else -> "<档位里没有版本号>"
                }
                assertEquals("$mark 版本号要交回调用点（弹窗那一侧还得拿它取 UpdateInfo）", expected, version)
            }
        }
    }

    @Test
    fun `被忽略那一档的话点名了撤销落点`() {
        val verdict = UpdateCheckGate.present(
            trigger = UpdateCheckTrigger.Manual,
            ignoredVersion = NEWER,
            newestAboveInstalled = NEWER,
            failureReason = null,
        ) as UpdateCheckVerdict.SuppressedByIgnore

        assertTrue(
            "这一档必须自带那句话（改前它连档都没有）：实到 " + verdict.spoken,
            verdict.spoken.title.isNotBlank() && verdict.spoken.message.isNotBlank(),
        )
        assertTrue(
            "句子里要点名被忽略的是哪一版（实到：" + verdict.spoken.message + "）",
            verdict.spoken.message.contains(NEWER),
        )
        assertTrue(
            "句子里要指出撤销落在哪一格，否则这扇门还是单向的（实到：" + verdict.spoken.message + "）",
            verdict.spoken.message.contains("恢复更新提醒"),
        )
        val auto = UpdateCheckGate.present(
            trigger = UpdateCheckTrigger.Automatic,
            ignoredVersion = NEWER,
            newestAboveInstalled = NEWER,
            failureReason = null,
        ) as UpdateCheckVerdict.SuppressedByIgnore
        assertNotEquals(
            "自动与手动走到同一档、同一句话，但「该不该弹窗」必须分档（两侧都封死才不静默）",
            auto.announces,
            verdict.announces,
        )
    }

    // ─────────────── 上一次结果档：那一行的三句读数 ───────────────

    @Test
    fun `restingLine 三档各说各的话 失败与成功不许同形`() {
        // 每一档只喂「它在生产路径上真拿得到的那枚读数」：从没查过时偏好里没有时间戳，
        // 设置页的格式器给的就是 NEVER_LABEL；只有查过的两档才带时间。
        // 拿 10月8日 09:12 去喂 Never 档是本格子改前的错——那一手在生产上出不了这个输入。
        val never = UpdateCheckGate.restingLine(UpdateLastOutcome.Never, NEVER_LABEL)
        val ok = UpdateCheckGate.restingLine(UpdateLastOutcome.Succeeded, AUTO_LABEL)
        val failed = UpdateCheckGate.restingLine(UpdateLastOutcome.Failed, AUTO_LABEL)

        assertTrue(
            "从没查过那一档要把「从未进行」原样念出来，不许吞成空串：实到 $never",
            never.contains(NEVER_LABEL),
        )
        assertTrue("成功那一档必须念出「成功」（改前只有时间戳，失败也念这句）：实到 $ok", ok.contains("成功"))
        assertTrue("失败那一档必须念出「失败」：实到 $failed", failed.contains("失败"))
        assertTrue(
            "失败那一档还要给出路（手动检查不受每日节流限制）：实到 $failed",
            failed.contains("重试"),
        )
        assertNotEquals("成功与失败两档不许折成同一句话", ok, failed)
        assertNotEquals("「从没查过」与「查成功了」也不许同形", never, ok)
        assertNotEquals("「从没查过」与「查失败了」同样不许同形", never, failed)
        assertTrue(
            "从没查过那一档不许凭空造出一枚时间读数：实到 $never",
            !never.contains(AUTO_LABEL),
        )
        for (line in listOf(ok, failed)) {
            assertTrue("查过的两档都得带上时间读数，不许把 $AUTO_LABEL 丢掉：实到 $line", line.contains(AUTO_LABEL))
        }
        for (line in listOf(never, ok, failed)) {
            assertTrue("任何一档都不许把 epoch 0 原样念成 1970：实到 $line", !line.contains("1970"))
        }
    }

    // ─────────────── 跨档位不变量与档位枚数 ───────────────

    @Test
    fun `手动那一手要么在转圈要么在说话 自动那一侧仍存在静默档`() {
        val manualVerdicts = listOf(
            UpdateCheckGate.admit(false, UpdateCheckTrigger.Manual, true, UpdateLastOutcome.Never),
            UpdateCheckGate.admit(true, UpdateCheckTrigger.Manual, false, UpdateLastOutcome.Succeeded),
            UpdateCheckGate.admit(true, UpdateCheckTrigger.Manual, true, UpdateLastOutcome.Never),
            UpdateCheckGate.present(UpdateCheckTrigger.Manual, NEWER, NEWER, null),
            UpdateCheckGate.present(UpdateCheckTrigger.Manual, null, NEWER, null),
            UpdateCheckGate.present(UpdateCheckTrigger.Manual, null, null, null),
            UpdateCheckGate.present(UpdateCheckTrigger.Manual, null, null, "连接超时"),
        )
        for (verdict in manualVerdicts) {
            val speaksOrSpins = verdict is UpdateCheckVerdict.RunCheck || verdict.announces
            assertTrue(
                "用户按下按钮的那一手不许「屏上什么都没换」（本卡第 1 处病灶的形状）：" +
                    verdict::class.simpleName,
                speaksOrSpins,
            )
        }

        val autoVerdicts = listOf(
            UpdateCheckGate.admit(false, UpdateCheckTrigger.Automatic, true, UpdateLastOutcome.Never),
            UpdateCheckGate.admit(true, UpdateCheckTrigger.Automatic, false, UpdateLastOutcome.Failed),
            UpdateCheckGate.present(UpdateCheckTrigger.Automatic, NEWER, NEWER, null),
            UpdateCheckGate.present(UpdateCheckTrigger.Automatic, null, null, null),
            UpdateCheckGate.present(UpdateCheckTrigger.Automatic, null, null, "连接超时"),
            UpdateCheckGate.present(UpdateCheckTrigger.Automatic, null, NEWER, null),
        )
        val silent = autoVerdicts.filter { !it.announces }
        assertEquals(
            "自动那一侧六枚结论里必须**恰好五枚**静默（唯一出声的是「发现新版本」）——" +
                "多一枚就是每天第一次打开都要被网络错误打断，少一枚就是新版本被咽掉。" +
                "实到静默枚数：" + silent.size,
            5,
            silent.size,
        )

        val tiers = (manualVerdicts + autoVerdicts +
            UpdateCheckGate.admit(true, UpdateCheckTrigger.Manual, true, UpdateLastOutcome.Failed)
            ).mapNotNull { it::class.simpleName }.distinct().sorted()
        assertEquals(
            "内核交出的档位必须恰好七档（折成一枚 Boolean 是 1 档，把两档并一是 6 档）：实到 $tiers",
            listOf(
                "BlockedByPrivacy", "CheckFailed", "HeldByThrottle", "NewVersionFound",
                "NothingNewer", "RunCheck", "SuppressedByIgnore",
            ),
            tiers,
        )
    }

    @Test
    fun `三档要念给用户听的话两两不同形`() {
        val privacy = UpdateCheckGate.privacyBlockedCopy()
        val ignored = UpdateCheckGate.ignoreSuppressedCopy(NEWER)
        val heldOk = UpdateCheckGate.restingLine(UpdateLastOutcome.Succeeded, AUTO_LABEL)
        val heldFailed = UpdateCheckGate.restingLine(UpdateLastOutcome.Failed, AUTO_LABEL)

        assertTrue("隐私那一档两截都得有话：实到 $privacy", privacy.title.isNotBlank() && privacy.message.isNotBlank())
        assertTrue("隐私那句要说什么没查（实到：" + privacy.message + "）", privacy.message.contains("隐私"))
        assertTrue(
            "隐私那句不许承诺一个盘面上没有的按钮，得指到说明那一格（实到：" + privacy.message + "）",
            privacy.message.contains("数据与隐私"),
        )
        assertNotEquals("「没查」与「查了但被忽略」不许共用一句", privacy.message, ignored.message)
        assertNotEquals("被挡那一句与设置页那一句也不许同形", privacy.message, heldOk)
        assertNotEquals("节流那两句内部同样不许同形", heldOk, heldFailed)
    }
}
