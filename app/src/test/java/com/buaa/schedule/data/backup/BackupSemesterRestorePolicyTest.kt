package com.buaa.schedule.data.backup

import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.mondayOfWeekAnchor
import com.buaa.schedule.domain.model.startLocalDate
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T204① + T223：把「恢复时这一枚学期行到底该怎么办」那一判钉成表驱动单测（纯 JVM、零时钟）。
 *
 * 判据本体在 `data/backup/BackupSemesterRestorePolicy.kt` 的 `decideSemesterRowForRestore`
 * 与 `indexBackupSemesterRows`。病灶有两处（逐字原文见内核段首与本回执 §2）：
 * - `BackupData` 只带**一枚**学期行，而 `BackupCourse` 每门课都带 `semesterCode` ⇒ 课程跨学期、学期不跨；
 * - 读侧对库里没有的学期直接 `startDate = fallbackStartDate.toString()` + `totalWeeks = 20`，
 *   而 `fallbackStartDate` 的实参是恢复那天所在的周一 ⇒ 换机恢复后每一枚历史学期的日期与周次一起错位。
 *
 * **T223 加的是第三处**：主学期那一路当时根本没走这一判 —— `restoreBackupDataInTx` 里的 `safeSemester`
 * 自己 parse，认不出来就 `?: fallbackStartDate` 就地换成"恢复那天所在的周一"，随后无条件 delete+insert
 * 落库，而**补建账里没有它** ⇒ 恢复文案在这一档念"没有任何学期行是补建出来的"，是谎话。
 * 所以本文件的表从"一列格子"扩成**两侧各一列**：同一枚场景按 `mainSemester = false / true` 各跑一遍，
 * 每格除结论外还明写「落库的那一行是什么 / 补建账里有没有它 / 文案念什么」三问（卡面 §4 点名的五格
 * 在两侧一枚不缺）。两问的裁定（第二问两侧同答、第一问只许分一处）由
 * [两问的结论 第二问两侧同答 第一问只许分那一处] 与它旁边那两格钉住。
 *
 * 档位（如实写明）：`:app` 的 JVM 测试面只有 `junit`，**没有 Robolectric、没有能在 JVM 上跑的 Room 缝**，
 * 所以本文件钉的是**判据**与**配对**；"调用点有没有真的按这一判走、主学期那一路有没有再自己 parse、
 * 补建的主学期有没有真的进账"由源码形状守卫 `BackupSemesterRestoreWiringGuardTest` 钉；
 * "恢复一份坏日期的备份后库里那一行到底长什么样"那一档**没做**（红线零设备），归编排者装机。
 *
 * 两侧都有格子（卡面 §3.4 要求的五格全在，一枚不落）：
 * - 朝**宽**扭一次（内核恒回 `InsertFromBackup`，库里已有行也去写、没日期也硬写）⇒
 *   表里「库里已有 + 不写库」那一族、`只能补建` 那几格、以及**主学期坏日期那一格**红；
 * - 朝**窄**扭一次（内核恒回 `Rebuild`，备份带来的日期不用、兜底日期照旧交出去）⇒
 *   「备份带了该学期行」那一族、`老备份回落` 那几格与「日期有出处时不进补建账」整片红；
 * - 朝**只管历史学期**扭一次（`mainSemester` 恒按 false 用，也就是 T204 落地后的盘面）⇒
 *   第一问那两格（主学期顶掉库里那一行）与主学期五格里 `S①主 / S⓪主` 那几格红、
 *   两问测试里"分岔处数 = 1"那条也红。
 */
class BackupSemesterRestorePolicyTest {

    // ─────────────── ① 判据表：一等一笔，卡面要求的五格在两侧各一枚 ───────────────

    @Test
    fun `判据表 每一格都按预期给出结论`() {
        for (cell in table) {
            val actual = decide(cell)
            assertEquals(
                "格子「${cell.name}」（场景 ${cell.scenario}、`mainSemester=${cell.mainSemester}`）：" +
                    "termCode=${cell.termCode}、备份行=${describe(cell.backupRow)}、" +
                    "库里的行=${describe(cell.existingRow)}、兜底日期=${cell.fallback}、周次证据=${cell.observedMaxWeek}" +
                    " ⇒ 期望 ${describe(cell.expected)}。实际 ${describe(actual)}。${cell.why}\n" +
                    "这一格红了说明判据朝${cell.mutantDirection}的方向跑掉了",
                cell.expected,
                actual,
            )
        }
    }

    /** 卡面 §3.4 要求的"每格给三问"：落库的那一行 + 补建账里有没有它 + 文案念什么 */
    @Test
    fun `表驱动 每格另给落库的那一行与补建账与文案三问`() {
        for (cell in table) {
            val actual = decide(cell)
            val answer = answerOf(actual)
            assertEquals(
                "「${cell.name}」**落库的那一行**必须是 ${describe(cell.writtenRow)}（实到 " +
                    "${describe(answer.writtenRow)}）：KeepExistingInDb 一格都不写、" +
                    "InsertFromBackup 写备份那一枚（归一 + 限幅后）、Rebuild 写补建那一枚。" +
                    "这一格与判据表读的是两枚字段（writtenRow vs expected），对不上就是有人在结论之外改了落库",
                cell.writtenRow,
                answer.writtenRow,
            )
            assertEquals(
                "「${cell.name}」**补建账里**${if (cell.inTally) "必须有它" else "不许有它"}（实到 " +
                    "${answer.inTally}）：只有开学日没出处的那一等进账 —— 账本是恢复文案唯一的出处，" +
                    "多记 = 把有出处的日期说成猜的，少记 = 把猜的日期藏进静默（T223 主学期那一档就是少记）",
                cell.inTally,
                answer.inTally,
            )
            val note = rebuiltSemestersNote(
                if (answer.inTally) listOf(actual as SemesterRowDecision.Rebuild) else emptyList()
            )
            if (cell.copyMustContain.isEmpty()) {
                assertNull(
                    "「${cell.name}」的文案那一整段**不许出现**（实到「$note」）：这一格的开学日有出处" +
                        "或压根没人被顶掉，此时念「另有 N 枚学期行是补建出来的」就是假话",
                    note,
                )
            } else {
                assertNotNull("「${cell.name}」的文案必须念出来（实到 null）", note)
                for (needle in cell.copyMustContain) {
                    assertTrue(
                        "「${cell.name}」的文案必须念出「$needle」（实到「$note」）：" +
                            "枚数、哪一学期、「开学日不在备份里」、「按恢复那天推算」四样少一样，" +
                            "这笔账就少一半 —— 措辞唯一来源在内核 `rebuiltSemestersNote`，UI 不许现拼",
                        note!!.contains(needle),
                    )
                }
            }
        }
    }

    /**
     * 卡面点名的第 4 格那一族：**只能补建**那一等必须"说实话"。
     *
     * 两处不许退回去：① 结论里必须带着开学日的来历（[RebuiltStartOrigin]）与周数的来历
     * （[RebuiltWeeksBasis]），恢复结果与文案才念得出"这一枚是猜的";② 周数**不许是静默的 20** ——
     * 有周次证据就用证据（课程上到第 16 周 ⇒ 至少 16 周），没证据就给最小值并标 NO_BASIS。
     */
    @Test
    fun `补建那一等一定带着来历 且周数不是静默的二十`() {
        val rebuilds = table.filter { it.expected is SemesterRowDecision.Rebuild }
        assertTrue("表里必须至少有一格「只能补建」（卡面第 4 格），实到 ${rebuilds.size} 格", rebuilds.isNotEmpty())
        assertTrue(
            "「只能补建」那一等**两侧都要有格子**（实到 " + rebuilds.count { !it.mainSemester } + " 格历史学期、" +
                rebuilds.count { it.mainSemester } + " 格主学期）：只钉历史学期那一侧，" +
                "就是 T204 落地后主学期仍在裸写兜底日期的盘面",
            rebuilds.any { it.mainSemester } && rebuilds.any { !it.mainSemester },
        )
        for (cell in rebuilds) {
            val actual = decide(cell) as SemesterRowDecision.Rebuild

            val expectedRebuild = cell.expected as SemesterRowDecision.Rebuild
            assertEquals(
                "「${cell.name}」的开学日来历必须是 ${expectedRebuild.startOrigin}（实到 ${actual.startOrigin}）：" +
                    "库里没有、备份也没带 / 带了但日期认不出来，这两档在用户那边是完全不同的两句话，" +
                    "合成一枚「反正都是猜」就没人能分清是老备份还是坏备份",
                expectedRebuild.startOrigin,
                actual.startOrigin,
            )
            assertEquals(
                "「${cell.name}」的周数来历必须是 ${expectedRebuild.weeksBasis}（实到 ${actual.weeksBasis} / " +
                    "${actual.totalWeeks} 周）：周数从哪儿来是要报得出的 —— 有周次证据就是 COURSE_WEEKS，" +
                    "没有就是 NO_BASIS，任何一等都不许是「顺手一枚 20」",
                expectedRebuild.weeksBasis,
                actual.weeksBasis,
            )
            assertEquals(
                "「${cell.name}」写进库的 totalWeeks 必须是 ${expectedRebuild.totalWeeks}" +
                    "（实到 ${actual.totalWeeks}），整枚结论 ${describe(actual)} 要与 ${describe(expectedRebuild)} 等价",
                expectedRebuild,
                actual,
            )
            assertFalse(
                "「${cell.name}」不许把 totalWeeks 造回 20 —— 旧代码那枚静默的 20 是 #236 那一族" +
                    "（没依据就报「这学期 20 周」）在本仓的落点。实到 ${actual.totalWeeks} 周，" +
                    "周次证据 = ${cell.observedMaxWeek}",
                actual.totalWeeks == 20 && cell.observedMaxWeek != 20,
            )
        }
    }

    /**
     * 卡面第 2 格与第 3 格的分界：库里**已有**那一行、而备份的日期**不**能顶掉它时，一个字节都不许动。
     *
     * 选格的口径是"这一格自己声明了不写库"（`writtenRow == null`）—— 于是历史学期那一侧恒进得来，
     * 主学期只有「备份的日期认不出来」那几格进得来（`writtenRow != null` 那几格归
     * [两问的结论 第二问两侧同答 第一问只许分那一处] 管）。断言读的是格子里独立写下的第三问，
     * 判据朝备份倾斜时两处会互相对不上。
     *
     * 这一格钉的是"同机恢复无损"那一条，也是旧 KDoc 自己写的理由（不改写学期行，免得把历史学期
     * 顶成当前学期）。朝宽那一扭（备份优先）会先在这里红。
     */
    @Test
    fun `库里已有那一行且备份顶不动时一个字节都不动`() {
        val cells = table.filter { it.existingRow != null && it.writtenRow == null }
        assertTrue(
            "表里必须至少有一格「库里已有 + 不写库」，且**两侧都要有**（实到 " +
                cells.count { !it.mainSemester } + " 格历史学期、" + cells.count { it.mainSemester } +
                " 格主学期）：主学期那一侧一格都没有，说明 T223 的病（坏日期顶掉当前学期的好日期）" +
                "在本表里根本没有格子",
            cells.isNotEmpty() && cells.any { it.mainSemester } && cells.any { !it.mainSemester },
        )
        for (cell in cells) {
            val actual = decide(cell)
            assertEquals(
                "「${cell.name}」库里既然有 ${cell.termCode} 这一行、备份那一枚又没资格顶它" +
                    "（`mainSemester=${cell.mainSemester}`、备份的日期" +
                    (if (cell.backupRow?.startLocalDate == null) "认不出来" else "认得出来") +
                    "），结论必须是 KeepExistingInDb 且带的就是库里那一枚（实到 ${describe(actual)}）：" +
                    "拿备份去盖 = 把用户在设置页订正过的开学日与周数冲掉；" +
                    "拿兜底日期去盖 = 把这台设备当前学期的好日期顶成恢复那天 —— 同机恢复就不再无损",
                SemesterRowDecision.KeepExistingInDb(requireNotNull(cell.existingRow)),
                actual,
            )
        }
    }

    /**
     * 两问的裁定（卡面 §1 点名的那一判）：**第二问两侧同答，第一问只许在「库里已有 + 备份的日期
     * 认得出来」那一族分岔**。
     *
     * 比的是**答复形状**（哪一等结论 + 写不写库 + 进不进补建建账 + 补建时两样来历），
     * 不是逐字的行值 —— 两侧格子的 termCode / 行内容本来就不同（一枚主学期、一枚历史学期），
     * 逐字等只能在"同一枚结论"那一格里比（那里已经钉过）。
     */
    @Test
    fun `两问的结论 第二问两侧同答 第一问只许分那两格`() {
        val paired = table.groupBy { it.scenario }.filter { (_, cells) ->
            cells.size == 2 && cells.first().question != SUPPLEMENT
        }
        val singleSided = table
            .filter { it.question != SUPPLEMENT }
            .groupBy { it.scenario }
            .filter { (_, cells) -> cells.size != 2 }
            .keys
        assertEquals(
            "第一问与第二问的每一枚场景都必须有 `mainSemester = false / true` **各一格**（卡面 §4 要求" +
                "表驱动两侧都有格子）。落单的场景：" + singleSided.joinToString() +
                "（实到 ${paired.size} 组配对 / ${singleSided.size} 枚落单）",
            emptySet<String>(),
            singleSided.toSet(),
        )
        val diverged = mutableListOf<String>()
        for ((scenario, cells) in paired) {
            val history = cells.first { !it.mainSemester }
            val main = cells.first { it.mainSemester }
            val historyShape = shapeOf(decide(history))
            val mainShape = shapeOf(decide(main))
            if (historyShape == mainShape) {
                assertTrue(
                    "「$scenario」两侧同答（形状 ${describe(historyShape)}）—— 这一组要么登记成「第二问」" +
                        "（备份的日期认不出来 / 压根没带，主学期在这里没有任何特权），要么库里那一枚根本没有行" +
                        "（没有竞争，两侧当然同答）。登记成第一问、库里又有行、备份的日期还认得出来 —— " +
                        "却两侧同答 = `mainSemester` 成了死参（恢复不再恢复主学期配置，" +
                        "或者历史学期被允许拿备份盖库里那一行）",
                    history.question == SECOND_QUESTION || history.existingRow == null,
                )
            } else {
                diverged += scenario
                assertEquals(
                    "两侧答复不一致只许发生在**第一问**（库里那一行 vs 备份那一枚，且备份的日期认得出来）：" +
                        "「$scenario」被登记成「${history.question}」却在分岔。历史学期=${describe(historyShape)}、" +
                        "主学期=${describe(mainShape)}",
                    FIRST_QUESTION,
                    history.question,
                )
                // 分岔的**形状**也钉死：历史学期不写、主学期写备份那一枚，两侧都不进补建账
                assertEquals(
                    "「$scenario」分岔时历史学期那一侧必须仍然是「一个字都不写」那一等（实到 " +
                        describe(historyShape) + "）：库里那一行是这台设备的事实",
                    "KeepExistingInDb",
                    historyShape.kind,
                )
                assertEquals(
                    "「$scenario」分岔时主学期那一侧必须是 InsertFromBackup（实到 ${describe(mainShape)}）：" +
                        "顶掉库里那一行的资格只来自备份的日期有出处，不来自「这是当前学期」那句空话",
                    "InsertFromBackup",
                    mainShape.kind,
                )
                assertEquals(
                    "「$scenario」主学期写进库的必须是**备份带来的那一枚**（实到 " +
                        "${describe(shapeOf(decide(main)))}）",
                    main.backupRow,
                    answerOf(decide(main)).writtenRow,
                )
                assertFalse(
                    "「$scenario」两侧都不许进补建账（日期有出处 / 谁也没被顶掉，猜的日期一枚都没写）：" +
                        "实到 历史学期=${historyShape.inTally}、主学期=${mainShape.inTally}",
                    historyShape.inTally || mainShape.inTally,
                )
            }
        }
        assertEquals(
            "两问的分岔**只许发生在「库里已有 + 备份的日期认得出来」那两格**（库里坏值那一档与两边都" +
                "认得出来那一档各一枚，实到 ${diverged.size} 处：" + diverged.joinToString() +
                "）。多一处 = 主学期在第二问上也有了特权，坏日期就又有了「按恢复那天写而不留痕」的入口" +
                "（改之前那一形正是它）；一处都没有 = `mainSemester` 是死参，恢复不再恢复学期配置、" +
                "当前学期那枚指针也落不下来",
            setOf("S①库里坏+备份认得出来", "S⑥两边都认得出来"),
            diverged.toSet(),
        )
    }

    /**
     * 第二问那一族**逐格**同答（含"库里那一行本身就是坏值"那一等）：两侧结论必须逐字等价。
     *
     * 卡面要求两问给同一个答案 —— 这一格是它的直接证据，`mainSemester` 被谁偷偷改成恒 true / 恒 false
     * 都会在这里红（而不是只在上一格里以"指纹相等"的形式被顺带扫过）。
     */
    @Test
    fun `第二问的每一格 主学期与历史学期给出同一形状的结论`() {
        val cells = table.filter { it.question == SECOND_QUESTION }
        assertEquals(
            "第二问的格子必须是「四格 × 两侧」= 8 格（实到 ${cells.size} 格）：" +
                "库里认得出来+备份坏 / 两边都坏 / 库里没有+备份坏 / 备份压根没带这一 termCode —— " +
                "卡面 §4 点名的五格里，除「库里坏值 + 备份认得出来」那一格（归第一问）之外全在第二问。" +
                "少一枚就是某一档没钉",
            8,
            cells.size,
        )
        for ((scenario, pair) in cells.groupBy { it.scenario }) {
            val history = pair.first { !it.mainSemester }
            val main = pair.first { it.mainSemester }
            val historyShape = shapeOf(decide(history))
            val mainShape = shapeOf(decide(main))
            assertEquals(
                "第二问「$scenario」两侧必须给**同一个答案**：历史学期=${describe(historyShape)} vs " +
                    "主学期=${describe(mainShape)}。这一族的裁定是「认不出来就不动 / 补建并留痕」，" +
                    "主学期在这一问上没有任何特权 —— 旧代码的「就地兜底一个日历值」不是「主学期该有不同答复」，" +
                    "它只是没走这一判",
                historyShape,
                mainShape,
            )
            assertEquals(
                "第二问「$scenario」两侧**进不进补建账**也要一致（历史学期=${history.inTally}、" +
                    "主学期=${main.inTally}）：不一致 = 恢复文案会在其中一侧说谎" +
                    "（主学期那一档正是改之前说谎的那一格）",
                history.inTally,
                main.inTally,
            )
            assertEquals(
                "第二问「$scenario」两侧**文案念几项**也要一致（历史学期 ${history.copyMustContain.size} 项、" +
                    "主学期 ${main.copyMustContain.size} 项）：账本来源两侧同口径，念法才同口径",
                history.copyMustContain.isEmpty(),
                main.copyMustContain.isEmpty(),
            )
        }
    }

    /**
     * `mainSemester` 只买通「备份的日期认得出来」那一格 —— 翻转它的效果必须在两侧都看得见。
     *
     * 这一格是**反套套逻辑**的那一手：它不读表里的 expected，而是拿同一格输入翻转 role 再比结论。
     * - 备份认不出来 / 压根没带 ⇒ 翻转必须**一个字都不改**（第二问两侧同答，主学期没有特权）；
     *   这一手红了说明有人把「认不出来」那一档又交回给 `mainSemester` 兜底 —— 本卡的病灶。
     * - 备份认得出来而库里已有 ⇒ 翻转**必须改判**（Keep ↔ Insert），且改判只许发生在这一族。
     */
    @Test
    fun `翻转 mainSemester 只在备份的日期认得出来那一格改判`() {
        val unreadable = table.filter { it.backupRow?.startLocalDate == null }
        assertTrue(
            "表里必须有「备份的日期认不出来 / 压根没带」那一族（实到 ${unreadable.size} 格）",
            unreadable.isNotEmpty(),
        )
        for (cell in unreadable) {
            val asIs = decide(cell)
            val flipped = decide(cell.copy(mainSemester = !cell.mainSemester))
            assertEquals(
                "「${cell.name}」备份那一枚的日期" +
                    (if (cell.backupRow == null) "压根没带" else "认不出来") +
                    "，翻转 `mainSemester=${cell.mainSemester}→${!cell.mainSemester}` 必须一个字都不改结论：" +
                    "改前 ${describe(asIs)} / 改后 ${describe(flipped)}。红了就是有人让「这是主学期」这句话" +
                    "重新买通了兜底日期 —— 旧代码 `?: fallbackStartDate` 那一形正是这一档，" +
                    "它把整学期的日期与周次顶成恢复那天，而补建账里干干净净",
                asIs,
                flipped,
            )
        }
        val bothReadable = table.filter { it.backupRow?.startLocalDate != null && it.existingRow != null }
        assertTrue(
            "表里必须有「库里已有 + 备份的日期认得出来」那一族（第一问的靶，实到 ${bothReadable.size} 格）",
            bothReadable.isNotEmpty(),
        )
        for (cell in bothReadable) {
            val asIs = shapeOf(decide(cell))
            val flipped = shapeOf(decide(cell.copy(mainSemester = !cell.mainSemester)))
            assertTrue(
                "「${cell.name}」库里与备份都读得出日期，翻转 `mainSemester` **必须改判**：" +
                    "两侧现在都是 ${describe(asIs)}。没改判 = `mainSemester` 是死参 —— " +
                    "要么恢复不再恢复主学期配置（当前学期那枚指针也落不下来），" +
                    "要么历史学期被允许拿备份盖库里那一行（同机恢复不再无损）",
                asIs != flipped,
            )
            assertEquals(
                "「${cell.name}」翻转后落到主学期那一侧必须是 InsertFromBackup、" +
                    "落到历史学期那一侧必须是 KeepExistingInDb（实到 ${describe(flipped)}）：" +
                    "两问里第一问的答复就是这两句，别的形状都是又开了一等",
                if (cell.mainSemester) "KeepExistingInDb" else "InsertFromBackup",
                flipped.kind,
            )
        }
    }

    // ─────────────── ② 日期归一：写进库的每一枚 startDate 都是周一 ───────────────

    /**
     * 三个等里两等会写库（InsertFromBackup / Rebuild），它们交出的 `startDate` 必须是周一。
     *
     * 全仓课次日期都是 `startDate.plusWeeks(w-1).plusDays(dow-1)`，开学日不是周一就整表偏移
     * 且周次编号错位（R7 P2-12 那一族：同一节课最多错开 6 天）。归一公式只许引
     * `domain/model` 那唯一一份 [mondayOfWeekAnchor]。
     */
    @Test
    fun `写进库的结论一律落在周一`() {
        for (cell in table) {
            val written = answerOf(decide(cell)).writtenRow ?: continue
            val parsed = LocalDate.parse(written.startDate)
            assertEquals(
                "「${cell.name}」写进库的 startDate=${written.startDate} 必须是周一（ISO dayOfWeek=1）。" +
                    "红了说明归一被拆掉：备份里那枚串或兜底日期不是周一时，整学期每一节课的日期与周次一起偏",
                1,
                parsed.dayOfWeek.value,
            )
            assertEquals(
                "「${cell.name}」的 startDate 必须等于 mondayOfWeekAnchor(它自己)（幂等，别再二次偏移）",
                written.startDate,
                mondayOfWeekAnchor(parsed).toString(),
            )
        }
    }

    /** 兜底日期本身不是周一时也要归一：调用点写法变了（直接给 LocalDate.now()）不静默收下 */
    @Test
    fun `兜底日期不是周一时补建行仍落在周一`() {
        val notMonday = LocalDate.parse("2026-10-07") // 周三
        val decision = decide(
            Cell(
                name = "兜底日期是周三",
                scenario = "S补",
                question = SUPPLEMENT,
                termCode = "2024-2025-1",
                backupRow = null,
                existingRow = null,
                fallback = notMonday,
                observedMaxWeek = 18,
                mainSemester = false,
                expected = SemesterRowDecision.Rebuild(
                    termCode = "2024-2025-1",
                    termName = "2024-2025-1",
                    startDate = "2026-10-05",
                    totalWeeks = 18,
                    startOrigin = RebuiltStartOrigin.NOT_IN_BACKUP,
                    weeksBasis = RebuiltWeeksBasis.COURSE_WEEKS,
                ),
                writtenRow = null,
                inTally = true,
                copyMustContain = emptyList(),
                why = "",
                mutantDirection = "",
            )
        ) as SemesterRowDecision.Rebuild
        assertEquals(
            "周三 2026-10-07 递进来时，补建行的开学日必须是该自然周的周一 2026-10-05（实到 " +
                "${decision.startDate}）：旧代码是 `fallbackStartDate.toString()` 裸着写，" +
                "调用点哪天不先算周一了就把周三当开学日写进库",
            "2026-10-05",
            decision.startDate,
        )
    }

    // ─────────────── ③ 配对表：老备份回落、重复 termCode、空白 termCode ───────────────

    @Test
    fun `配对表 老备份回落与重复行都按预期`() {
        for (cell in indexTable) {
            val actual = indexBackupSemesterRows(cell.rows, cell.legacy)
            assertEquals(
                "格子「${cell.name}」：列表=${cell.rows.map { it.termCode }}、单字段=" +
                    cell.legacy?.termCode + " ⇒ 期望索引 ${cell.expectedKeys}。实际 ${actual.keys}",
                cell.expectedKeys,
                actual.keys.toList(),
            )
            for ((key, expectedRow) in cell.expectedRows) {
                assertEquals(
                    "格子「${cell.name}」里 termCode=$key 应当配到「${expectedRow.termName} / " +
                        "${expectedRow.startDate}」，实到 ${describe(actual[key])}：配错一枚 = 恢复时" +
                        "拿另一学期的开学日去建这一学期，比不配还坏",
                    expectedRow,
                    actual[key],
                )
            }
        }
    }

    /** 卡面第 5 格单独再钉一遍：主学期在 `semesters` 里重复出现时**首枚为准**，不许后枚覆盖 */
    @Test
    fun `同一 termCode 重复出现时首枚为准`() {
        val first = semester("2026-2027-1", "2026-2027学年第一学期", "2026-09-07", 19)
        val second = semester("2026-2027-1", "被改过的那一枚", "2026-02-23", 20)
        val index = indexBackupSemesterRows(listOf(first, second), legacy = second)
        assertEquals(
            "重复 termCode 必须首枚为准（实到 ${describe(index["2026-2027-1"])}）：备份文件是用户能" +
                "手改的，「后一枚覆盖前一枚」会让列表顺序悄悄决定恢复结果，而顺序没有任何语义" +
                "（写侧取的是 `getAllSemesters()` 的 startDate 倒序）",
            first,
            index["2026-2027-1"],
        )
        assertEquals(
            "索引只许一枚 2026-2027-1（实到 ${index.size} 枚）：两枚都留在索引里 = 同一学期两个开学日",
            1,
            index.size,
        )
    }

    /** 老备份（没有 semesters 键）必须等价于「只有单字段那一枚」，这是不涨版本号的兼容面 */
    @Test
    fun `老备份只有单字段时回落得到那一枚`() {
        val legacy = semester("2026-2027-1", "2026-2027学年第一学期", "2026-09-07", 19)
        val index = indexBackupSemesterRows(emptyList(), legacy)
        assertEquals(listOf("2026-2027-1"), index.keys.toList())
        assertEquals(legacy, index["2026-2027-1"])
        assertNull(
            "历史学期 2025-2026-2 在老备份里就是没有出处（实到 ${describe(index["2025-2026-2"])}）：" +
                "索引里查不到它，判据才走「只能补建」那一等 —— 这正是本卡要说实话的那一格",
            index["2025-2026-2"],
        )
    }

    /**
     * 主学期这一路的**标的行**在配对表落空时退回单字段那一枚（T223 调用点的那一枚 `?:` 的口径）。
     *
     * 场景：备份的单字段 `semester.termCode` 是空白串（`BackupSemester.termCode` 是必填 String，
     * 手改的备份给得出 `""`）。配对表按自己的规矩把空白 termCode 的行丢在索引外，于是
     * `backupRows[mainCode]` 配不到 —— 但这一枚**确实是备份带来的主学期行**，日期也可能认得出来。
     * 若此时把 `backupRow = null` 递给判据，结论就退成 `NOT_IN_BACKUP`（"这一学期不在备份里"），
     * 而备份里明明写着它的开学日 —— 那是**反方向**的谎。所以调用点先 `?: backupSemester`，
     * 判据收得到日期就走 InsertFromBackup（有出处 ⇒ 不进账）。守卫钉那一枚 `?:` 在不在。
     */
    @Test
    fun `主学期标的行配不到索引时仍用单字段那一枚 不退成 不在备份里`() {
        val blankTerm = semester("", "手改过的主学期行", "2026-09-07", 19)
        val index = indexBackupSemesterRows(listOf(blankTerm), legacy = blankTerm)
        assertNull(
            "空白 termCode 不进索引（配对表自己的规矩，实到 ${describe(index[""])}）：留着只会造出一枚" +
                "空 termCode 的学期行",
            index[""],
        )
        val viaIndexOnly = decideSemesterRowForRestore(
            termCode = "",
            backupRow = index[""],
            existingRow = null,
            fallbackStartDate = RESTORE_MONDAY,
            observedMaxWeek = 19,
            mainSemester = true,
        )
        val withLegacy = decideSemesterRowForRestore(
            termCode = "",
            backupRow = index[""] ?: blankTerm,
            existingRow = null,
            fallbackStartDate = RESTORE_MONDAY,
            observedMaxWeek = 19,
            mainSemester = true,
        )
        assertEquals(
            "只按索引递行时，主学期会被判成「备份压根没带」（实到 ${describe(viaIndexOnly)}）—— " +
                "这正是把有出处的日期说成猜的那一句假话",
            RebuiltStartOrigin.NOT_IN_BACKUP,
            (viaIndexOnly as SemesterRowDecision.Rebuild).startOrigin,
        )
        assertEquals(
            "递上标的行（`backupRows[mainCode] ?: 单字段那一枚`）后必须是 InsertFromBackup 2026-09-07" +
                "（实到 ${describe(withLegacy)}）：日期认得出来就是有出处，不进补建账、文案整段不出现",
            SemesterRowDecision.InsertFromBackup(blankTerm),
            withLegacy,
        )
    }

    // ─────────────── ④ 内核不读时钟：同一批输入连判两次同结论 ───────────────

    @Test
    fun `判据不读时钟 同一输入连判两次同结论`() {
        for (cell in table) {
            val first = decide(cell)
            val second = decide(cell)
            assertEquals(
                "「${cell.name}」两次判定不一致 = 判据里有状态（时钟或随机）：同一次恢复里" +
                    "同一枚学期行会先「用备份的日期」再「补建」，落库结果取决于第几次调用",
                first,
                second,
            )
        }
    }

    // ─────────────── ⑤ 补建账那一句：为空时不给文案，非空时必须念出来历 ───────────────

    @Test
    fun `补建账文案 空账不给句子 有账就念出开学日不是备份里的`() {
        assertNull(
            "没有任何补建时必须返回 null（实到 ${rebuiltSemestersNote(emptyList())}）：" +
                "恢复成功后面挂一句「有学期行是猜的」是假话",
            rebuiltSemestersNote(emptyList()),
        )
        val one = rebuild("2025-2026-2", "2026-10-05", 16, RebuiltStartOrigin.NOT_IN_BACKUP)
        val note = rebuiltSemestersNote(listOf(one))!!
        assertTrue("必须念出补建枚数（实到「$note」）", note.contains("1 枚"))
        assertTrue("必须念出是哪一学期（实到「$note」）", note.contains("2025-2026-2"))
        assertTrue(
            "必须明说开学日不是备份里的、而是按恢复那天推算（实到「$note」）：" +
                "这句话是本卡「不许静默」那一面的唯一出口",
            note.contains("不在备份里") && note.contains("恢复那天"),
        )
        val many = rebuiltSemestersNote(
            listOf(one) + (1..5).map {
                one.copy(termCode = "TERM-$it", startOrigin = RebuiltStartOrigin.BACKUP_DATE_UNREADABLE)
            },
        )!!
        assertTrue("超过点名列举上限时要折算成「等 N 枚」，且枚数是全量（实到「$many」）", many.contains("等 6 枚"))
    }

    /**
     * T223：文案那一格必须**真把主学期念出来**。
     *
     * 改前的原文（本仓 `ui/ScheduleViewModel` 恢复那一段，账本为空时走的那一支）：
     * `rebuiltSemestersNote(result.rebuiltSemesters)` 收到空列表 ⇒ 返回 null ⇒
     * `if (rebuiltNote != null) append("。${'$'}rebuiltNote")` 整段不出现 ⇒ 屏幕上只有
     * 「恢复成功：N 条课程」，而库里主学期那一行的开学日是按恢复那天猜的 ——
     * 「没有任何学期行是补建出来的」在这一档是谎话。
     * 改后：主学期那一枚 `Rebuild` 必须跟着落进同一本账，于是这一句被 append 出来、termCode 是主学期的。
     */
    @Test
    fun `补建账含主学期那一枚时文案把它念出来 账本里没有它时那一整段不出现`() {
        val mainRebuild = rebuild(
            termCode = MAIN_BACKUP.termCode,
            startDate = "2026-10-05",
            totalWeeks = 19,
            startOrigin = RebuiltStartOrigin.BACKUP_DATE_UNREADABLE,
        )
        val note = rebuiltSemestersNote(listOf(mainRebuild))
        assertNotNull(
            "主学期（当前学期）那一枚补建必须让文案出现（实到 null）：旧形状在这一格账本是空的，" +
                "于是恢复文案念「没有任何学期行是补建出来的」而库里那一行的日期是猜的 —— 谎话",
            note,
        )
        assertTrue("文案必须念出主学期的 termCode=${MAIN_BACKUP.termCode}（实到「$note」）", note!!.contains(MAIN_BACKUP.termCode))
        assertTrue("必须念出「1 枚」（实到「$note」）", note.contains("1 枚"))
        assertTrue(
            "必须明说这一枚的开学日不在备份里、按恢复那天推算（实到「$note」）",
            note.contains("不在备份里") && note.contains("恢复那天"),
        )
        assertFalse(
            "措辞里不许出现「周数也是猜的」之外的第二枚数字（周数另有来历档 NO_BASIS / COURSE_WEEKS，" +
                "这句只交代开学日）：实到「$note」",
            note.contains("20 周"),
        )
        // 账本里没有主学期（日期有出处那一档）时，那一整段必须不出现
        assertNull(
            "主学期走 InsertFromBackup 时不许进账 ⇒ 文案整段不出现（实到 " +
                "${rebuiltSemestersNote(emptyList())}）：多念就是假话",
            rebuiltSemestersNote(emptyList()),
        )
    }

    // ─────────────── 表 ───────────────

    private fun semester(code: String, name: String, start: String, weeks: Int) =
        Semester(termCode = code, termName = name, startDate = start, totalWeeks = weeks)

    private fun decide(cell: Cell): SemesterRowDecision = decideSemesterRowForRestore(
        termCode = cell.termCode,
        backupRow = cell.backupRow,
        existingRow = cell.existingRow,
        fallbackStartDate = cell.fallback,
        observedMaxWeek = cell.observedMaxWeek,
        mainSemester = cell.mainSemester,
    )

    /** 结论 → 这一格真正的答复：落库的那一行（null = 不写）+ 进不进补建账（文案由这两样决定） */
    private fun answerOf(decision: SemesterRowDecision): Answer = when (decision) {
        is SemesterRowDecision.KeepExistingInDb -> Answer(null, false)
        is SemesterRowDecision.InsertFromBackup -> Answer(decision.semester, false)
        is SemesterRowDecision.Rebuild -> Answer(decision.toSemester(), true)
    }

    /**
     * 结论 → **答复形状**：哪一等 + 写不写库 + 进不进账 + 补建时那两样来历。
     *
     * 两侧比同答时不能逐字比行值（一枚格子的 termCode 是 2026-2027-1、另一枚是 2025-2026-2，
     * 本来就不同），比的是"这一档怎么处置"。行值的对账归表驱动那一格（每格独立写着 expected 与三问）。
     */
    private fun shapeOf(decision: SemesterRowDecision): Shape = when (decision) {
        is SemesterRowDecision.KeepExistingInDb -> Shape("KeepExistingInDb", false, false, null)
        is SemesterRowDecision.InsertFromBackup -> Shape("InsertFromBackup", true, false, null)
        is SemesterRowDecision.Rebuild -> Shape(
            kind = "Rebuild",
            writes = true,
            inTally = true,
            origins = "${decision.startOrigin}/${decision.weeksBasis}",
        )
    }

    private data class Shape(val kind: String, val writes: Boolean, val inTally: Boolean, val origins: String?)

    private data class Answer(val writtenRow: Semester?, val inTally: Boolean)

    private data class Cell(
        val name: String,
        /** 同一枚场景在两侧的格子靠它配对（卡面 §3.4 点名的五格 = 五枚 scenario，另加两枚补格） */
        val scenario: String,
        /** 「第二问」两侧必须同答；「第一问」允许分岔；「补格」不受这两条约束 */
        val question: String,
        val termCode: String,
        val backupRow: Semester?,
        val existingRow: Semester?,
        val fallback: LocalDate,
        val observedMaxWeek: Int?,
        /** 两侧：true = 主学期那一路（这次恢复的标的），false = 历史学期那一路 */
        val mainSemester: Boolean,
        val expected: SemesterRowDecision,
        /** 三问之一：落库的那一行是什么（null = 库里那一行一个字节都不动） */
        val writtenRow: Semester?,
        /** 三问之二：补建账里有没有它 */
        val inTally: Boolean,
        /** 三问之三：文案念什么（空表 = 那一整段不出现） */
        val copyMustContain: List<String>,
        val why: String,
        val mutantDirection: String,
    )

    private data class IndexCell(
        val name: String,
        val rows: List<Semester>,
        val legacy: Semester?,
        val expectedKeys: List<String>,
        val expectedRows: Map<String, Semester>,
    )

    private fun rebuild(
        termCode: String,
        startDate: String,
        totalWeeks: Int,
        startOrigin: RebuiltStartOrigin,
        weeksBasis: RebuiltWeeksBasis = RebuiltWeeksBasis.COURSE_WEEKS,
    ) = SemesterRowDecision.Rebuild(
        termCode = termCode,
        termName = termCode,
        startDate = startDate,
        totalWeeks = totalWeeks,
        startOrigin = startOrigin,
        weeksBasis = weeksBasis,
    )

    private fun describe(value: Any?): String = when (value) {
        null -> "null"
        is Semester -> "Semester(${value.termCode}/${value.termName}/${value.startDate}/${value.totalWeeks}周)"
        is Answer -> "落库=${describe(value.writtenRow)}，补建账=${if (value.inTally) "有它" else "没它"}"
        is Shape -> value.kind + "（" + (if (value.writes) "写库" else "不写库") +
            "、补建账" + (if (value.inTally) "有它" else "没它") +
            (value.origins?.let { "、来历 $it" } ?: "") + "）"
        is SemesterRowDecision.KeepExistingInDb -> "KeepExistingInDb(${value.existing.termCode})"
        is SemesterRowDecision.InsertFromBackup ->
            "InsertFromBackup(${value.semester.termCode}/${value.semester.startDate}/${value.semester.totalWeeks}周)"
        is SemesterRowDecision.Rebuild ->
            "Rebuild(${value.termCode}/${value.startDate}/${value.totalWeeks}周," +
                "开学日来历=${value.startOrigin},周数来历=${value.weeksBasis})"
        else -> value.toString()
    }

    private companion object {

        const val FIRST_QUESTION = "第一问"
        const val SECOND_QUESTION = "第二问"
        const val SUPPLEMENT = "补格"

        /** 恢复那天所在的周一（`ui/ScheduleViewModel` 递给旧代码的那一枚实参） */
        val RESTORE_MONDAY: LocalDate = LocalDate.parse("2026-10-05")

        /** 主学期（当前学期）在**备份**里那一枚：日期认得出来 */
        val MAIN_BACKUP: Semester = Semester(
            termCode = "2026-2027-1",
            termName = "2026-2027学年第一学期",
            startDate = "2026-09-07",
            totalWeeks = 19,
        )

        /** 主学期在备份里那一枚，开学日是坏串（`2026/9/7` 这种手改或旧版没校验的写法） */
        val MAIN_BACKUP_BAD: Semester = MAIN_BACKUP.copy(startDate = "2026/9/7")

        /** 主学期在**库里**那一枚：同机恢复，用户在设置页订正过开学日与周数（认得出来，且与备份不同值） */
        val MAIN_DB: Semester = MAIN_BACKUP.copy(
            termName = "2026-2027学年第一学期（我在设置页订正过）",
            startDate = "2026-09-14",
            totalWeeks = 16,
        )

        /** 主学期在库里那一枚，而库里那串本身就是旧版未校验的坏值（`Semester.startLocalDate` 的 KDoc 明写着会有） */
        val MAIN_DB_BAD: Semester = MAIN_BACKUP.copy(startDate = "2026 年 9 月 7 日")

        /** 历史学期在备份里的那一枚（真实开学日，正是 T204 要带过来的东西） */
        val HISTORY_BACKUP_ROW: Semester = Semester(
            termCode = "2025-2026-2",
            termName = "2025-2026学年第二学期",
            startDate = "2026-02-23",
            totalWeeks = 18,
        )

        /** 历史学期在备份里那一枚，日期是坏串 */
        val HISTORY_BACKUP_BAD: Semester = HISTORY_BACKUP_ROW.copy(startDate = "2026/2/23")

        /** 历史学期在库里那一枚（订正过，认得出来） */
        val HISTORY_DB_ROW: Semester = Semester(
            termCode = "2025-2026-2",
            termName = "2025-2026学年第二学期（我自己在设置页改过开学日）",
            startDate = "2026-03-02",
            totalWeeks = 17,
        )

        /** 历史学期在库里那一枚，库里那串本身是坏值 */
        val HISTORY_DB_BAD: Semester = HISTORY_BACKUP_ROW.copy(startDate = "2025/9/1")

        /** 文案里那一枚 termCode 之外的必念项，写全太啰嗦，这里合成一次各处补 termCode */
        /**
         * 三问里"有账"那一档必须念到的四样（枚数、开学日来历、去哪儿核对），termCode 由每一格自己补进去 ——
         * 独立写下的第三问，不从结论反推（否则三问测试就成了套套逻辑）。
         */
        fun copyNeedles(vararg extra: String): List<String> =
            listOf("另有 1 枚", "不在备份里", "恢复那天", "请到设置页核对") + extra.toList()

        val COPY_MAIN: List<String> = copyNeedles(MAIN_BACKUP.termCode)
        val COPY_HISTORY: List<String> = copyNeedles(HISTORY_BACKUP_ROW.termCode)

        /**
         * 卡面 §3.4 点名的五格 = S①～S⑤，每格**两侧各一枚**（`mainSemester=false/true`）；
         * S⓪/S⑥ 是第一问那一对（备份的日期认得出来时才有的竞争），其余是同一判据的补格
         * （归一、限幅、周次证据、老备份回落、空白 termCode）。
         *
         * 每一格除 `expected` 外还独立写着「落库的那一行 / 进不进补建账 / 文案念什么」三问，
         * 由 [表驱动 每格另给落库的那一行与补建账与文案三问] 与结论互相核对 —— 不是套套逻辑。
         */
        val table: List<Cell> = listOf(
            // ══ S⓪ 库里没有 + 备份认得出来（T204 的修复本体；两侧同答）══
            Cell(
                name = "S⓪史换机恢复：库里没有、备份带着历史学期 ⇒ 用备份的 2026-02-23",
                scenario = "S⓪库里没有+备份认得出来",
                question = FIRST_QUESTION,
                termCode = "2025-2026-2",
                backupRow = HISTORY_BACKUP_ROW,
                existingRow = null,
                fallback = RESTORE_MONDAY,
                observedMaxWeek = 16,
                mainSemester = false,
                expected = SemesterRowDecision.InsertFromBackup(HISTORY_BACKUP_ROW),
                writtenRow = HISTORY_BACKUP_ROW,
                inTally = false,
                copyMustContain = emptyList(),
                why = "日期有出处就用出处，不进补建账。旧代码在这一格写的是「2026-10-05 开学、20 周」",
                mutantDirection = "更窄的方向跑掉了（不用备份带来的日期，退回补建那一等）",
            ),
            Cell(
                name = "S⓪主换机恢复（干净的新机）：库里没有、备份带着主学期 ⇒ 用备份的 2026-09-07",
                scenario = "S⓪库里没有+备份认得出来",
                question = FIRST_QUESTION,
                termCode = "2026-2027-1",
                backupRow = MAIN_BACKUP,
                existingRow = null,
                fallback = RESTORE_MONDAY,
                observedMaxWeek = 19,
                mainSemester = true,
                expected = SemesterRowDecision.InsertFromBackup(MAIN_BACKUP),
                writtenRow = MAIN_BACKUP,
                inTally = false,
                copyMustContain = emptyList(),
                why = "这一格两侧同答（库里没东西可竞争）：主学期走 InsertFromBackup 后由仓储层 delete+insert，" +
                    "让它成为「当前学期」（id 最大的那一行）—— 这是它与历史学期唯一的**动作**差别，不是答复差别",
                mutantDirection = "更窄的方向跑掉了（不用备份带来的日期）",
            ),

            // ══ S① 卡面第 1 格：库里坏值 + 备份认得出来（第一问唯一的分岔处）══
            Cell(
                name = "S①史库里那枚是旧版未校验的坏串、备份的日期认得出来 ⇒ 仍然不动库里那一行",
                scenario = "S①库里坏+备份认得出来",
                question = FIRST_QUESTION,
                termCode = "2025-2026-2",
                backupRow = HISTORY_BACKUP_ROW,
                existingRow = HISTORY_DB_BAD,
                fallback = RESTORE_MONDAY,
                observedMaxWeek = 16,
                mainSemester = false,
                expected = SemesterRowDecision.KeepExistingInDb(HISTORY_DB_BAD),
                writtenRow = null,
                inTally = false,
                copyMustContain = emptyList(),
                why = "「库里已有 ⇒ 一个字节都不动」不吃值：库里那枚坏串是这台设备自己的状态，" +
                    "读侧按 startLocalDate 给 null 降级（「未设置学期」），不是这次恢复造成的损坏；" +
                    "动它 = 恢复顺手改用户的学期行",
                mutantDirection = "更宽的方向跑掉了（备份优先于库里既有行）",
            ),
            Cell(
                name = "S①主库里那枚是坏串、备份的日期认得出来 ⇒ 主学期让备份顶掉它（第一问的分岔）",
                scenario = "S①库里坏+备份认得出来",
                question = FIRST_QUESTION,
                termCode = "2026-2027-1",
                backupRow = MAIN_BACKUP,
                existingRow = MAIN_DB_BAD,
                fallback = RESTORE_MONDAY,
                observedMaxWeek = 19,
                mainSemester = true,
                expected = SemesterRowDecision.InsertFromBackup(MAIN_BACKUP),
                writtenRow = MAIN_BACKUP,
                inTally = false,
                copyMustContain = emptyList(),
                why = "这一格是两问里**唯一**允许两侧不同答的地方：主学期那一行是这次恢复的标的，" +
                    "而顶掉它的依据是备份里那枚认得出来的日期（有出处）。库里那枚坏串正好被有出处的一枚换掉",
                mutantDirection = "更窄的方向跑掉了（标的行不敢顶库里那一行 = 恢复不再恢复学期配置）",
            ),

            // ══ S② 卡面第 2 格：库里认得出来 + 备份坏（T223 修复本体，两侧同答）══
            Cell(
                name = "S②史库里订正过、备份那枚日期是坏串 ⇒ 不动库里那一行",
                scenario = "S②库里认得出来+备份坏",
                question = SECOND_QUESTION,
                termCode = "2025-2026-2",
                backupRow = HISTORY_BACKUP_BAD,
                existingRow = HISTORY_DB_ROW,
                fallback = RESTORE_MONDAY,
                observedMaxWeek = 16,
                mainSemester = false,
                expected = SemesterRowDecision.KeepExistingInDb(HISTORY_DB_ROW),
                writtenRow = null,
                inTally = false,
                copyMustContain = emptyList(),
                why = "有行不等于有日期：备份那枚 parse 不出来就是没出处，不许拿它去盖库里的订正值",
                mutantDirection = "更宽的方向跑掉了（拿坏串或兜底日期盖库里）",
            ),
            Cell(
                name = "S②主库里是当前学期、备份那枚日期是坏串 ⇒ 不写一个字（旧代码在这一格顶成恢复那天）",
                scenario = "S②库里认得出来+备份坏",
                question = SECOND_QUESTION,
                termCode = "2026-2027-1",
                backupRow = MAIN_BACKUP_BAD,
                existingRow = MAIN_DB,
                fallback = RESTORE_MONDAY,
                observedMaxWeek = 19,
                mainSemester = true,
                expected = SemesterRowDecision.KeepExistingInDb(MAIN_DB),
                writtenRow = null,
                inTally = false,
                copyMustContain = emptyList(),
                why = "**T223 的病灶本体**：改之前 `safeSemester` 在这一格把 MAIN_DB 的 2026-09-14 顶成" +
                    "「2026-10-05（恢复那天所在的周一）」并无条件 delete+insert，整学期日期与周次编号一起错位，" +
                    "而补建账里没有它 ⇒ 恢复文案念「没有任何学期行是补建出来的」，那一档是谎话。" +
                    "现在第二问两侧同答：不动库里那一行",
                mutantDirection = "更宽的方向跑掉了（认不出来就就地兜底一枚日历值 —— 就是改之前那一形）",
            ),

            // ══ S③ 卡面第 3 格：两边都坏（库里有一枚坏行 + 备份一枚坏日期）══
            Cell(
                name = "S③史两边都坏 ⇒ 库里那一枚坏行照旧不动",
                scenario = "S③两边都坏",
                question = SECOND_QUESTION,
                termCode = "2025-2026-2",
                backupRow = HISTORY_BACKUP_BAD,
                existingRow = HISTORY_DB_BAD,
                fallback = RESTORE_MONDAY,
                observedMaxWeek = 16,
                mainSemester = false,
                expected = SemesterRowDecision.KeepExistingInDb(HISTORY_DB_BAD),
                writtenRow = null,
                inTally = false,
                copyMustContain = emptyList(),
                why = "两侧都有行、两侧都读不出日期 ⇒ 「不动库里」优先于「补建」：补建要把库里已有的行删掉再" +
                    "造一枚猜的，比留着一枚坏串更凶（坏串读得出 null，按「未设置学期」降级是既有口径）",
                mutantDirection = "更宽的方向跑掉了（把库里已有的行删掉换成猜的）",
            ),
            Cell(
                name = "S③主两边都坏 ⇒ 当前学期那一行照旧不动（与历史学期逐字同答）",
                scenario = "S③两边都坏",
                question = SECOND_QUESTION,
                termCode = "2026-2027-1",
                backupRow = MAIN_BACKUP_BAD,
                existingRow = MAIN_DB_BAD,
                fallback = RESTORE_MONDAY,
                observedMaxWeek = 19,
                mainSemester = true,
                expected = SemesterRowDecision.KeepExistingInDb(MAIN_DB_BAD),
                writtenRow = null,
                inTally = false,
                copyMustContain = emptyList(),
                why = "这一格专门钉「主学期不许有特权」：库里那串是坏的也不能拿恢复那天去换 —— " +
                    "换了就是把损坏藏进一枚看起来合理的日期，而文案那头依然干净",
                mutantDirection = "更宽的方向跑掉了（坏值 + 坏值 ⇒ 造一枚好端端的假日期）",
            ),

            // ══ S④ 卡面第 4 格：两边都没有可用的日期（库里没有那一行 + 备份带的认不出来）══
            Cell(
                name = "S④史库里没有、备份带了行但日期是坏串 ⇒ 补建并标 BACKUP_DATE_UNREADABLE",
                scenario = "S④库里没有+备份坏",
                question = SECOND_QUESTION,
                termCode = "2025-2026-2",
                backupRow = HISTORY_BACKUP_BAD,
                existingRow = null,
                fallback = RESTORE_MONDAY,
                observedMaxWeek = 18,
                mainSemester = false,
                expected = SemesterRowDecision.Rebuild(
                    termCode = "2025-2026-2",
                    termName = "2025-2026学年第二学期",
                    startDate = "2026-10-05",
                    totalWeeks = 18,
                    startOrigin = RebuiltStartOrigin.BACKUP_DATE_UNREADABLE,
                    weeksBasis = RebuiltWeeksBasis.COURSE_WEEKS,
                ),
                writtenRow = Semester(
                    termCode = "2025-2026-2",
                    termName = "2025-2026学年第二学期",
                    startDate = "2026-10-05",
                    totalWeeks = 18,
                ),
                inTally = true,
                copyMustContain = COPY_HISTORY,
                why = "斜杠单位 parse 不出来就不许「抢救」成日期 —— 认不出来就是没出处（同 T180 口径）。" +
                    "termName 仍可以用备份那一枚（名字是有出处的，日期才是没有的）",
                mutantDirection = "更宽的方向跑掉了（把认不出来的串折算成任何一个日期）",
            ),
            Cell(
                name = "S④主库里没有、备份那枚日期是坏串 ⇒ 补建 + **必须进账**（文案从此念得出主学期）",
                scenario = "S④库里没有+备份坏",
                question = SECOND_QUESTION,
                termCode = "2026-2027-1",
                backupRow = MAIN_BACKUP_BAD,
                existingRow = null,
                fallback = RESTORE_MONDAY,
                observedMaxWeek = 19,
                mainSemester = true,
                expected = SemesterRowDecision.Rebuild(
                    termCode = "2026-2027-1",
                    termName = "2026-2027学年第一学期",
                    startDate = "2026-10-05",
                    totalWeeks = 19,
                    startOrigin = RebuiltStartOrigin.BACKUP_DATE_UNREADABLE,
                    weeksBasis = RebuiltWeeksBasis.COURSE_WEEKS,
                ),
                writtenRow = Semester(
                    termCode = "2026-2027-1",
                    termName = "2026-2027学年第一学期",
                    startDate = "2026-10-05",
                    totalWeeks = 19,
                ),
                inTally = true,
                copyMustContain = COPY_MAIN,
                why = "卸载重装 / 换机 + 一份坏日期的备份：这一格旧代码走的就是「就地兜底一个日历值 + 不进账」，" +
                    "整学期日期与周次编号一起错位而屏幕上什么都没有。现在它与历史学期那一格逐字同答，" +
                    "并且**主学期这一枚也在补建账里** ⇒ 文案念「另有 1 枚学期行是补建出来的（2026-2027-1）…」",
                mutantDirection = "更宽的方向跑掉了（不记账：本卡「文案说谎」那一档会重新长出来）",
            ),

            // ══ S⑤ 卡面第 5 格：备份压根没带这一 termCode（老备份 / 老口令）══
            Cell(
                name = "S⑤史老备份恢复历史学期：索引里没有该 termCode ⇒ 补建 + NOT_IN_BACKUP",
                scenario = "S⑤备份压根没带这一termCode",
                question = SECOND_QUESTION,
                termCode = "2025-2026-2",
                backupRow = null,
                existingRow = null,
                fallback = RESTORE_MONDAY,
                observedMaxWeek = 16,
                mainSemester = false,
                expected = SemesterRowDecision.Rebuild(
                    termCode = "2025-2026-2",
                    termName = "2025-2026-2",
                    startDate = "2026-10-05",
                    totalWeeks = 16,
                    startOrigin = RebuiltStartOrigin.NOT_IN_BACKUP,
                    weeksBasis = RebuiltWeeksBasis.COURSE_WEEKS,
                ),
                writtenRow = Semester(
                    termCode = "2025-2026-2",
                    termName = "2025-2026-2",
                    startDate = "2026-10-05",
                    totalWeeks = 16,
                ),
                inTally = true,
                copyMustContain = COPY_HISTORY,
                why = "老备份只有主学期那一枚（回落见配对表），历史学期确实没出处 ⇒ 只能补建，" +
                    "但开学日标 NOT_IN_BACKUP、周数标 COURSE_WEEKS（这批课上到第 16 周），恢复文案念得出来",
                mutantDirection = "更宽的方向跑掉了（把兜底日期说成有出处，或干脆不标来历）",
            ),
            Cell(
                name = "S⑤主备份压根没带主学期那一枚（口令里没有学期）⇒ 补建 + NOT_IN_BACKUP，两侧同答",
                scenario = "S⑤备份压根没带这一termCode",
                question = SECOND_QUESTION,
                termCode = "2026-2027-1",
                backupRow = null,
                existingRow = null,
                fallback = RESTORE_MONDAY,
                observedMaxWeek = 16,
                mainSemester = true,
                expected = SemesterRowDecision.Rebuild(
                    termCode = "2026-2027-1",
                    termName = "2026-2027-1",
                    startDate = "2026-10-05",
                    totalWeeks = 16,
                    startOrigin = RebuiltStartOrigin.NOT_IN_BACKUP,
                    weeksBasis = RebuiltWeeksBasis.COURSE_WEEKS,
                ),
                writtenRow = Semester(
                    termCode = "2026-2027-1",
                    termName = "2026-2027-1",
                    startDate = "2026-10-05",
                    totalWeeks = 16,
                ),
                inTally = true,
                copyMustContain = COPY_MAIN,
                why = "这一等两侧的答复逐字相同（同 S④）：termName 退成 termCode（没有出处就是没有，" +
                    "不许编一个好看的名字），周数按这批课的最大周次，整枚进账并被文案念出来",
                mutantDirection = "更宽的方向跑掉了（给 termName 编一枚出处、或干脆不记账）",
            ),

            // ══ S⑥ 第一问的另一头：库里与备份都认得出来、值不同（分岔的靶）══
            Cell(
                name = "S⑥史库里与备份都有能读出的日期 ⇒ 库里为准（同机恢复无损那一档）",
                scenario = "S⑥两边都认得出来",
                question = FIRST_QUESTION,
                termCode = "2025-2026-2",
                backupRow = HISTORY_BACKUP_ROW,
                existingRow = HISTORY_DB_ROW,
                fallback = RESTORE_MONDAY,
                observedMaxWeek = 16,
                mainSemester = false,
                expected = SemesterRowDecision.KeepExistingInDb(HISTORY_DB_ROW),
                writtenRow = null,
                inTally = false,
                copyMustContain = emptyList(),
                why = "库里那一行是这台设备此刻的事实（用户订正过就用订正后的）。拿备份去盖 = 同机恢复" +
                    "不再无损，而且 insert 会把这行的 id 顶到最大、历史学期变成「当前学期」",
                mutantDirection = "更宽的方向跑掉了（备份优先于库里既有行）",
            ),
            Cell(
                name = "S⑥主库里与备份都有能读出的日期 ⇒ 备份顶掉库里那一行（第一问的分岔本体）",
                scenario = "S⑥两边都认得出来",
                question = FIRST_QUESTION,
                termCode = "2026-2027-1",
                backupRow = MAIN_BACKUP,
                existingRow = MAIN_DB,
                fallback = RESTORE_MONDAY,
                observedMaxWeek = 19,
                mainSemester = true,
                expected = SemesterRowDecision.InsertFromBackup(MAIN_BACKUP),
                writtenRow = MAIN_BACKUP,
                inTally = false,
                copyMustContain = emptyList(),
                why = "主学期是这次恢复的标的：`BackupModels` 的 KDoc 自己写着单字段那一枚是「恢复时主学期配置" +
                    "（节次、周数）的出处」，而「当前学期」= `SemesterDao.getCurrent()` 那一句 " +
                    "`ORDER BY id DESC LIMIT 1` —— 它必须最后写才落得下来。两问的裁定在内核 KDoc " +
                    "「两问的结论」那一节，这里把它钉成格子",
                mutantDirection = "更窄的方向跑掉了（标的行让位库里 = 恢复不再恢复学期配置、" +
                    "首页停在别的学期上，看着像「没恢复」）",
            ),

            // ══ 补格：归一 / 限幅 / 周次证据 / 没有证据（两侧各一枚）══
            Cell(
                name = "补史备份带来的开学日不是周一 ⇒ 归一到该自然周的周一",
                scenario = "补史归一",
                question = SUPPLEMENT,
                termCode = "2025-2026-2",
                backupRow = HISTORY_BACKUP_ROW.copy(startDate = "2026-02-22"), // 周日（报到日）
                existingRow = null,
                fallback = RESTORE_MONDAY,
                observedMaxWeek = 16,
                mainSemester = false,
                expected = SemesterRowDecision.InsertFromBackup(
                    HISTORY_BACKUP_ROW.copy(startDate = "2026-02-16"),
                ),
                writtenRow = HISTORY_BACKUP_ROW.copy(startDate = "2026-02-16"),
                inTally = false,
                copyMustContain = emptyList(),
                why = "全仓课次日期都是 startDate.plusWeeks(w-1).plusDays(dow-1)，开学日不是周一" +
                    "整张表就按星期几错位（R7 P2-12 那一族：同一节课最多错开 6 天）",
                mutantDirection = "更宽的方向跑掉了（坏锚点原样入库）",
            ),
            Cell(
                name = "补主备份带来的开学日不是周一 ⇒ 同样归一到周一（主学期不是第二套锚点）",
                scenario = "补主归一",
                question = SUPPLEMENT,
                termCode = "2026-2027-1",
                backupRow = MAIN_BACKUP.copy(startDate = "2026-09-06"), // 周日（报到日）
                existingRow = null,
                fallback = RESTORE_MONDAY,
                observedMaxWeek = 19,
                mainSemester = true,
                expected = SemesterRowDecision.InsertFromBackup(
                    MAIN_BACKUP.copy(startDate = "2026-08-31"),
                ),
                writtenRow = MAIN_BACKUP.copy(startDate = "2026-08-31"),
                inTally = false,
                copyMustContain = emptyList(),
                why = "旧代码在主学期那一格用的是 `WeekCalculator.mondayOf`（同一公式的另一处引用点），" +
                    "现在归一到内核里的 [mondayOfWeekAnchor] —— 两侧同一把尺",
                mutantDirection = "更宽的方向跑掉了（非周一起点入库，整学期按星期几错位）",
            ),
            Cell(
                name = "补史备份带来的周数越界 ⇒ 限幅到 MAX_TOTAL_WEEKS",
                scenario = "补史限幅",
                question = SUPPLEMENT,
                termCode = "2025-2026-2",
                backupRow = HISTORY_BACKUP_ROW.copy(totalWeeks = 400),
                existingRow = null,
                fallback = RESTORE_MONDAY,
                observedMaxWeek = 16,
                mainSemester = false,
                expected = SemesterRowDecision.InsertFromBackup(
                    HISTORY_BACKUP_ROW.copy(totalWeeks = 30),
                ),
                writtenRow = HISTORY_BACKUP_ROW.copy(totalWeeks = 30),
                inTally = false,
                copyMustContain = emptyList(),
                why = "备份是用户可控输入：400 周会让周次选择器长到不可用、currentWeekOrNull 报越界周次",
                mutantDirection = "更宽的方向跑掉了（越界值直接入库）",
            ),
            Cell(
                name = "补主备份带来的周数越界 ⇒ 同一把尺限幅（与主学期旧口径同值）",
                scenario = "补主限幅",
                question = SUPPLEMENT,
                termCode = "2026-2027-1",
                backupRow = MAIN_BACKUP.copy(totalWeeks = 400),
                existingRow = null,
                fallback = RESTORE_MONDAY,
                observedMaxWeek = 19,
                mainSemester = true,
                expected = SemesterRowDecision.InsertFromBackup(
                    MAIN_BACKUP.copy(totalWeeks = 30),
                ),
                writtenRow = MAIN_BACKUP.copy(totalWeeks = 30),
                inTally = false,
                copyMustContain = emptyList(),
                why = "改之前主学期那一格自己调 `CourseConstraints.normalizeTotalWeeks`（同值）；" +
                    "现在由内核调，仓库不再自己限幅 —— 少一处第二把尺",
                mutantDirection = "更宽的方向跑掉了（越界周数入库）",
            ),
            Cell(
                name = "补史库里没有、备份也没带、且没有周次证据 ⇒ 补建 + NO_BASIS（不许静默 20）",
                scenario = "补史无证据",
                question = SUPPLEMENT,
                termCode = "2024-2025-1",
                backupRow = null,
                existingRow = null,
                fallback = RESTORE_MONDAY,
                observedMaxWeek = null,
                mainSemester = false,
                expected = SemesterRowDecision.Rebuild(
                    termCode = "2024-2025-1",
                    termName = "2024-2025-1",
                    startDate = "2026-10-05",
                    totalWeeks = 1,
                    startOrigin = RebuiltStartOrigin.NOT_IN_BACKUP,
                    weeksBasis = RebuiltWeeksBasis.NO_BASIS,
                ),
                writtenRow = Semester(
                    termCode = "2024-2025-1",
                    termName = "2024-2025-1",
                    startDate = "2026-10-05",
                    totalWeeks = 1,
                ),
                inTally = true,
                copyMustContain = copyNeedles("2024-2025-1"),
                why = "两样都没出处时，旧代码报的是「2026-10-05 开学、20 周」且一句提示都没有。" +
                    "这一格钉的是：周数没有依据时给最小值并把 NO_BASIS 交回文案，绝不造一枚看起来合理的 20",
                mutantDirection = "更宽的方向跑掉了（没依据也报一个像样的周数）",
            ),
            Cell(
                name = "补主主学期没有周次证据（那一组课是空的）⇒ 补建 + NO_BASIS，进账",
                scenario = "补主无证据",
                question = SUPPLEMENT,
                termCode = "2026-2027-1",
                backupRow = MAIN_BACKUP_BAD,
                existingRow = null,
                fallback = RESTORE_MONDAY,
                observedMaxWeek = null,
                mainSemester = true,
                expected = SemesterRowDecision.Rebuild(
                    termCode = "2026-2027-1",
                    termName = "2026-2027学年第一学期",
                    startDate = "2026-10-05",
                    totalWeeks = 1,
                    startOrigin = RebuiltStartOrigin.BACKUP_DATE_UNREADABLE,
                    weeksBasis = RebuiltWeeksBasis.NO_BASIS,
                ),
                writtenRow = Semester(
                    termCode = "2026-2027-1",
                    termName = "2026-2027学年第一学期",
                    startDate = "2026-10-05",
                    totalWeeks = 1,
                ),
                inTally = true,
                copyMustContain = COPY_MAIN,
                why = "备份里只有一枚学期行、课程全被判死或全属别的学期时也会进这一格：改之前它写的是" +
                    "「恢复那天 + 备份里那枚周数」且不进账；现在两侧同尺 —— 没证据就是 1 周 + NO_BASIS + 留痕",
                mutantDirection = "更宽的方向跑掉了（照抄备份里那枚没依据可循的周数）",
            ),
            Cell(
                name = "补史只能补建但有周次证据 ⇒ 周数取这批课的最大周次（有出处的下界）",
                scenario = "补史周次证据",
                question = SUPPLEMENT,
                termCode = "2024-2025-1",
                backupRow = null,
                existingRow = null,
                fallback = RESTORE_MONDAY,
                observedMaxWeek = 19,
                mainSemester = false,
                expected = SemesterRowDecision.Rebuild(
                    termCode = "2024-2025-1",
                    termName = "2024-2025-1",
                    startDate = "2026-10-05",
                    totalWeeks = 19,
                    startOrigin = RebuiltStartOrigin.NOT_IN_BACKUP,
                    weeksBasis = RebuiltWeeksBasis.COURSE_WEEKS,
                ),
                writtenRow = Semester(
                    termCode = "2024-2025-1",
                    termName = "2024-2025-1",
                    startDate = "2026-10-05",
                    totalWeeks = 19,
                ),
                inTally = true,
                copyMustContain = copyNeedles("2024-2025-1"),
                why = "课程上到第 19 周，这学期就至少 19 周：这是从备份内容里读出来的，不是造的。" +
                    "开学日仍然是猜的（startOrigin 明说），这一等整枚都在补建账里",
                mutantDirection = "更宽/更窄都会红（周数被换成常量就不再是「有出处」）",
            ),
            Cell(
                name = "补主周次证据越界 ⇒ 限幅后再记账（主学期不吃第二把尺）",
                scenario = "补主证据越界",
                question = SUPPLEMENT,
                termCode = "2026-2027-1",
                backupRow = MAIN_BACKUP_BAD,
                existingRow = null,
                fallback = RESTORE_MONDAY,
                observedMaxWeek = 999,
                mainSemester = true,
                expected = SemesterRowDecision.Rebuild(
                    termCode = "2026-2027-1",
                    termName = "2026-2027学年第一学期",
                    startDate = "2026-10-05",
                    totalWeeks = 30,
                    startOrigin = RebuiltStartOrigin.BACKUP_DATE_UNREADABLE,
                    weeksBasis = RebuiltWeeksBasis.COURSE_WEEKS,
                ),
                writtenRow = Semester(
                    termCode = "2026-2027-1",
                    termName = "2026-2027学年第一学期",
                    startDate = "2026-10-05",
                    totalWeeks = 30,
                ),
                inTally = true,
                copyMustContain = COPY_MAIN,
                why = "observedMaxWeek 来自用户可改的备份（weeks 列表已被 normalize 钳在 1..30，" +
                    "但判据自己不依赖那一层）：越界值照样限幅，别把 999 写进库",
                mutantDirection = "更宽的方向跑掉了（越界证据直接入库）",
            ),
            Cell(
                name = "补主学期在 semesters 里重复出现后配到的是首枚 ⇒ 判据拿到的日期是 2026-09-07",
                scenario = "补主首枚为准",
                question = SUPPLEMENT,
                termCode = "2026-2027-1",
                backupRow = MAIN_BACKUP,
                existingRow = null,
                fallback = RESTORE_MONDAY,
                observedMaxWeek = 19,
                mainSemester = true,
                expected = SemesterRowDecision.InsertFromBackup(MAIN_BACKUP),
                writtenRow = MAIN_BACKUP,
                inTally = false,
                copyMustContain = emptyList(),
                why = "配对表里同 termCode 出现两枚（另一枚 startDate 是 2026-02-23）时首枚为准，" +
                    "所以判据收到的备份行是 2026-09-07 那一枚。次序规则见 indexBackupSemesterRows 与那一格单测",
                mutantDirection = "更宽的方向跑掉了（后一枚覆盖前一枚，同一学期两个开学日）",
            ),
        )

        /**
         * 配对表：卡面第 1 格与第 5 格的**读侧**那一半在这里钉（判据表钉的是它们喂进
         * `decideSemesterRowForRestore` 之后长什么样）。
         */
        val indexTable: List<IndexCell> = listOf(
            IndexCell(
                name = "新备份：列表两枚 + 单字段主学期（主学期两枚同源，索引仍是两枚）",
                rows = listOf(HISTORY_BACKUP_ROW, MAIN_BACKUP),
                legacy = MAIN_BACKUP,
                expectedKeys = listOf("2025-2026-2", "2026-2027-1"),
                expectedRows = mapOf(
                    "2025-2026-2" to HISTORY_BACKUP_ROW,
                    "2026-2027-1" to MAIN_BACKUP,
                ),
            ),
            IndexCell(
                name = "老备份：列表为空 ⇒ 回落到单字段那一枚（卡面第 1 格）",
                rows = emptyList(),
                legacy = MAIN_BACKUP,
                expectedKeys = listOf("2026-2027-1"),
                expectedRows = mapOf("2026-2027-1" to MAIN_BACKUP),
            ),
            IndexCell(
                name = "主学期在列表里重复出现 ⇒ 首枚为准（卡面第 5 格），单字段那枚更靠后也不许顶掉它",
                rows = listOf(
                    MAIN_BACKUP,
                    MAIN_BACKUP.copy(termName = "手改过的第二枚", startDate = "2026-02-23", totalWeeks = 20),
                    HISTORY_BACKUP_ROW,
                ),
                legacy = MAIN_BACKUP.copy(startDate = "2020-01-01", totalWeeks = 8),
                expectedKeys = listOf("2026-2027-1", "2025-2026-2"),
                expectedRows = mapOf(
                    "2026-2027-1" to MAIN_BACKUP,
                    "2025-2026-2" to HISTORY_BACKUP_ROW,
                ),
            ),
            IndexCell(
                name = "老备份且单字段也没有 ⇒ 空索引（补建那一等唯一进得来的路）",
                rows = emptyList(),
                legacy = null,
                expectedKeys = emptyList(),
                expectedRows = emptyMap(),
            ),
            IndexCell(
                name = "列表里混进空白 termCode 的行 ⇒ 不进索引（主学期那一路自己补回标的行）",
                rows = listOf(
                    Semester(termCode = "   ", termName = "手改出来的空行", startDate = "2026-09-07", totalWeeks = 19),
                    MAIN_BACKUP,
                ),
                legacy = null,
                expectedKeys = listOf("2026-2027-1"),
                expectedRows = mapOf("2026-2027-1" to MAIN_BACKUP),
            ),
            IndexCell(
                name = "列表非空但主学期只在单字段里 ⇒ 单字段补进索引（列表在前，不冲突）",
                rows = listOf(HISTORY_BACKUP_ROW),
                legacy = MAIN_BACKUP,
                expectedKeys = listOf("2025-2026-2", "2026-2027-1"),
                expectedRows = mapOf(
                    "2025-2026-2" to HISTORY_BACKUP_ROW,
                    "2026-2027-1" to MAIN_BACKUP,
                ),
            ),
        )
    }
}
