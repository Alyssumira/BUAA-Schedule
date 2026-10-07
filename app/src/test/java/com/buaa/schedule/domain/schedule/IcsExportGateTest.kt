package com.buaa.schedule.domain.schedule

import com.buaa.schedule.ui.settings.icsExportInconsistentMessage
import com.buaa.schedule.ui.settings.icsExportRefusedMessage
import com.buaa.schedule.ui.settings.icsExportWrittenMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T252②：把「ICS 导出到底该不该碰用户选中的那个文件」那一判钉成表驱动单测（纯 JVM、零时钟注入面）。
 *
 * 判据本体在 `domain/schedule/IcsExportGate.kt` 的 [IcsExportGate.decide]，句子在
 * `ui/settings/IcsExportCopy.kt`（本文件末尾那几格连句子一起钉，句子在表里是**逐字写死的原文**，
 * 不是拿文案函数自己算一遍再跟自己对——那种断言恒真）。
 *
 * ## 病（用户看得见的那一串：自己选的文件被清空，屏幕还报喜）
 *
 * 日程条数为 0 时改前三行连着走：`exportIcs()` 交回 `ExportResult("", 0, …)` ⇒
 * 下一行 `writeTextToUri(uriString, result.text)` 用默认模式开流（截断）⇒
 * 最后一句把 `eventCount` 原样插进「已导出 N 个日程，可在系统日历中导入」。
 * 于是"导出没内容"被折成"把用户原有的文件清空"，而那句话在用户听来是成功的。
 *
 * ## 两侧都要有格子（卡面 §3 点名这一格）
 *
 * - **朝宽**（判据只看"文本非空白"就开流 —— 那正是改前的形状，改前压根没有判据）⇒
 *   表 ⑤（0 条 + 一枚合法空 `BEGIN/END VCALENDAR` 骨架）与表 ⑥（条数被上游算成负数）会被截断：
 *   [朝宽那把错尺 只看文本就会把用户文件截成零字节] 把这两格的差异当场量出来；
 * - **朝窄**（"只要有课次被跳过就不写"那种看着更稳的保守尺）⇒ 表 ① 那种"12 条真日程 + 3 条被跳过"
 *   的正常导出被整条拒掉，用户明明有课却读到"没有可导出的日程"：
 *   [朝窄那把错尺 有跳过就不写会把正常导出整条拒掉] 量的是这一侧。
 * 两把错尺都在测试体内**并排复算**：格子先断言错尺自己真的咬得住（反证自证），再断言本内核给出相反、
 * 正确的那一侧 —— 红名会说清是哪一边跑掉了。
 *
 * ## 档位如实写明
 *
 * `:app` 的 JVM 测试面只有 junit（无 Robolectric、未开 `returnDefaultValues`）⇒ 本文件钉的是**判据与句子**；
 * "调用点真按判据走、开流那一手只长在 WriteTruncating 支里"由 [IcsExportGateWiringGuardTest] 钉源码形状。
 * ⚠️ 未装机：系统选择器建文档那一刻文件是什么状态、`openOutputStream` 的实际截断行为，本卡零设备没有读数。
 */
class IcsExportGateTest {

    // ─────────────── ① 判据表：一格一笔，每格写清「为什么」 ───────────────

    @Test
    fun `判据表 每一格都按预期给出结论`() {
        for (cell in table) {
            val actual = IcsExportGate.decide(cell.eventCount, cell.skipped, cell.text, cell.fileSelected)
            assertEquals(
                "格子「${cell.name}」：条数=${cell.eventCount}、跳过=${cell.skipped}、" +
                    "文本=${describeText(cell.text)}、已选中文件=${cell.fileSelected} ⇒ " +
                    "期望 ${cell.expectedLabel}，实到 $actual —— ${cell.why}\n" +
                    "这一格红了，说明判据朝${cell.mutantSide}的方向跑掉了",
                cell.expected.toString(),
                actual.toString(),
            )
        }
    }

    /** 卡面点名的承重格：0 条 + 用户已经选中文件 ⇒ **不落盘**，一个字节都不许动 */
    @Test
    fun `零日程且已选中文件 一律不落盘`() {
        val cells = table.filter { it.eventCount <= 0 && it.fileSelected }
        assertTrue(
            "表里没有「0 条 + 已选中」的格子了：本卡的主案发现场被从表里删走，这一格就成了空转",
            cells.isNotEmpty(),
        )
        for (cell in cells) {
            val actual = IcsExportGate.decide(cell.eventCount, cell.skipped, cell.text, cell.fileSelected)
            assertFalse(
                "「${cell.name}」（条数 ${cell.eventCount}、文本 ${describeText(cell.text)}）判成了 $actual —— " +
                    "WriteTruncating 一出场就是拿默认模式开流，用户选中的那份文件当场被截成 0 字节。" +
                    "这一等必须落进 Refuse*（本卡的病灶本身，#309）",
                actual is IcsExportDecision.WriteTruncating,
            )
            assertEquals(
                "「${cell.name}」对用户文件的处置必须是 LEAVE_UNTOUCHED，实到 ${actual.mutation}",
                IcsTargetFileMutation.LEAVE_UNTOUCHED,
                actual.mutation,
            )
        }
    }

    /** 反方向承重格：条数 > 0 且导出件非空白 ⇒ **照旧落盘**，不许被本卡的护栏顺手停掉 */
    @Test
    fun `有日程且导出件非空白 照常截断写出`() {
        val cells = table.filter { it.eventCount > 0 && it.text.isNotBlank() && it.fileSelected }
        assertTrue(
            "表里没有「条数>0 + 文本非空白 + 已选中」的格子了：正常导出这条路没人钉，" +
                "本卡的护栏是不是把这条路一起堵死就无从知道",
            cells.isNotEmpty(),
        )
        for (cell in cells) {
            val actual = IcsExportGate.decide(cell.eventCount, cell.skipped, cell.text, cell.fileSelected)
            assertTrue(
                "「${cell.name}」必须判成 WriteTruncating（实到 $actual）：这一等不落盘就是**朝窄跑掉** —— " +
                    "课表里真有 ${cell.eventCount} 条日程却念一句「没有可导出的日程」，用户手里的日历文件永远刷不老",
                actual is IcsExportDecision.WriteTruncating,
            )
            val written = actual as IcsExportDecision.WriteTruncating
            assertEquals(
                "「${cell.name}」对用户文件的处置必须是 TRUNCATE_AND_WRITE，实到 ${written.mutation}",
                IcsTargetFileMutation.TRUNCATE_AND_WRITE,
                written.mutation,
            )
            assertEquals(
                "「${cell.name}」交回调用点去写的正文必须是喂进来的那一枚、一枚字节都不许改" +
                    "（进去 ${cell.text.length} 枚字符，出来 ${written.text.length} 枚）：" +
                    "判据顺手裁文本 = 屏幕上报的数与写进文件的内容不再是同一份东西",
                cell.text,
                written.text,
            )
            assertEquals("「${cell.name}」条数原样交回", cell.eventCount, written.eventCount)
            assertEquals("「${cell.name}」跳过数原样交回", cell.skipped, written.skippedOccurrences)
        }
    }

    /** 导出器两格打架那一等：条数报得出、正文却是空的 ⇒ 单独一档，既不写也不许并进「排不出课」 */
    @Test
    fun `条数为正而导出件空白 判成打架那一档`() {
        val cells = table.filter { it.eventCount > 0 && it.text.isBlank() && it.fileSelected }
        assertTrue("表里没有「条数>0 + 正文空白」的格子：打架那一档没格子吃", cells.isNotEmpty())
        for (cell in cells) {
            val actual = IcsExportGate.decide(cell.eventCount, cell.skipped, cell.text, cell.fileSelected)
            assertEquals(
                "「${cell.name}」（条数 ${cell.eventCount}、文本 ${describeText(cell.text)}）必须判成 " +
                    "RefuseInconsistent(${cell.eventCount})，实到 $actual。" +
                    "并进 RefuseEmpty = 屏幕叫用户去补节次/周次，可那两格根本没坏；" +
                    "照常开流 = 又一次把用户文件截成 0 字节（本卡病灶换个方向复发）",
                IcsExportDecision.RefuseInconsistent(cell.eventCount).toString(),
                actual.toString(),
            )
        }
    }

    /** 判据顺序即语义：没选中文件那一等**先于**条数与文本两判 */
    @Test
    fun `没选中文件先于一切判据`() {
        val cells = table.filter { !it.fileSelected }
        assertTrue("表里没有「未选中文件」的格子：判据顺序这一判没格子吃", cells.isNotEmpty())
        for (cell in cells) {
            val actual = IcsExportGate.decide(cell.eventCount, cell.skipped, cell.text, cell.fileSelected)
            assertEquals(
                "「${cell.name}」（条数 ${cell.eventCount}、文本 ${describeText(cell.text)}）必须给 NoFileSelected，" +
                    "实到 $actual —— 反过来先判条数，就等于替用户断言了「有文件可选」",
                IcsExportDecision.NoFileSelected.toString(),
                actual.toString(),
            )
        }
    }

    // ─────────────── ② 落盘模式：四档里只有一档能动那个文件 ───────────────

    @Test
    fun `落盘模式只有写出那一档是截断`() {
        val decisions = table.map {
            IcsExportGate.decide(it.eventCount, it.skipped, it.text, it.fileSelected)
        }
        val truncating = decisions.filter { it.mutation == IcsTargetFileMutation.TRUNCATE_AND_WRITE }
        val writeTiers = decisions.filterIsInstance<IcsExportDecision.WriteTruncating>()
        assertEquals(
            "交 TRUNCATE_AND_WRITE 的档数必须与 WriteTruncating 的档数逐枚相等（实到 " +
                "${truncating.size} 对 ${writeTiers.size}）：多一枚 = 有一档不动盘却自称要截断，" +
                "少一枚 = 有一次截断没有档位认领 —— 两形都是把落盘模式与档位分了家",
            writeTiers.size,
            truncating.size,
        )
        assertEquals(
            "本表判成截断写的格子必须恰好 $WRITE_CELLS 枚（实到 ${truncating.size}）：" +
                "枚数涨了就是又有格子被请去动用户的文件",
            WRITE_CELLS,
            truncating.size,
        )
    }

    // ─────────────── ③ 两侧的错误判据：并排复算，不许只讲道理 ───────────────

    /**
     * 朝宽那一扭：判据只看「文本非空白」就开流 —— 把条数那一判摘掉，正是改前的形状（改前没有判据，
     * `result.text` 是什么就往外写什么）。
     */
    @Test
    fun `朝宽那把错尺 只看文本就会把用户文件截成零字节`() {
        val wideRuler: (Int, String, Boolean) -> IcsExportDecision = { eventCount, text, fileSelected ->
            when {
                !fileSelected -> IcsExportDecision.NoFileSelected
                text.isNotBlank() -> IcsExportDecision.WriteTruncating(text, eventCount, 0)
                else -> IcsExportDecision.RefuseInconsistent(eventCount)
            }
        }
        // ⚠️ 这把尺是**靶子**不是判据：它把「0 条 + 空骨架」那一等判成开流，正是 #309 的病灶
        val victims = table.filter { it.eventCount <= 0 && it.text.isNotBlank() && it.fileSelected }
        assertTrue(
            "表里必须留着「0 条 + 文本非空白 + 已选中」的格子（实到 ${victims.size} 枚）：" +
                "摘掉条数那一判的尺今天靠谁抓不住，得有一枚靶子在场",
            victims.isNotEmpty(),
        )
        for (cell in victims) {
            val wrong = wideRuler(cell.eventCount, cell.text, cell.fileSelected)
            val right = IcsExportGate.decide(cell.eventCount, cell.skipped, cell.text, cell.fileSelected)
            assertTrue(
                "反证自证：朝宽那把错尺在「${cell.name}」上必须**真的**给出 WriteTruncating（实到 $wrong）" +
                    " —— 它给不出就说明这把靶尺自己拼错了，本格只是在演自己",
                wrong is IcsExportDecision.WriteTruncating,
            )
            assertEquals(
                "「${cell.name}」：错尺要开流（截断用户文件），本内核必须判 ${right.javaClass.simpleName} 不动盘",
                IcsTargetFileMutation.LEAVE_UNTOUCHED,
                right.mutation,
            )
        }
    }

    /**
     * 朝窄那一扭：「只要有课次被跳过就不写」那种看着更保守的尺 —— 它把真日程一起挡在门外，
     * 于是用户明明有课却读到「没有可导出的日程」，谎话只是换了个方向。
     */
    @Test
    fun `朝窄那把错尺 有跳过就不写会把正常导出整条拒掉`() {
        val narrowRuler: (Int, Int, String, Boolean) -> IcsExportDecision = { eventCount, skipped, text, fileSelected ->
            when {
                !fileSelected -> IcsExportDecision.NoFileSelected
                skipped > 0 -> IcsExportDecision.RefuseEmpty(skipped)
                else -> IcsExportGate.decide(eventCount, skipped, text, fileSelected)
            }
        }
        // ⚠️ 同样是靶子：跳过数不是"能不能写"的判据，只是"写出去几条之外还差几条"的那本账
        val victims = table.filter {
            it.eventCount > 0 && it.skipped > 0 && it.text.isNotBlank() && it.fileSelected
        }
        assertTrue(
            "表里必须留着「条数>0 且有课次被跳过」的格子（实到 ${victims.size} 枚）：" +
                "正常导出被保守尺挡掉这一案没靶子",
            victims.isNotEmpty(),
        )
        for (cell in victims) {
            val wrong = narrowRuler(cell.eventCount, cell.skipped, cell.text, cell.fileSelected)
            val right = IcsExportGate.decide(cell.eventCount, cell.skipped, cell.text, cell.fileSelected)
            assertTrue(
                "反证自证：朝窄那把错尺在「${cell.name}」上必须**真的**给出 RefuseEmpty（实到 $wrong）",
                wrong is IcsExportDecision.RefuseEmpty,
            )
            assertTrue(
                "「${cell.name}」：本内核必须照常写出 ${cell.eventCount} 条（实到 $right）—— " +
                    "被跳过的那 ${cell.skipped} 条自有句子报数，不该反过来没收整份导出件",
                right is IcsExportDecision.WriteTruncating,
            )
            assertEquals(
                "「${cell.name}」被跳过的条数必须原样跟着写出那一档，句子才报得出这一账",
                cell.skipped,
                (right as IcsExportDecision.WriteTruncating).skippedOccurrences,
            )
        }
    }

    // ─────────────── ④ 档位枚数与不折叠 ───────────────

    @Test
    fun `四档各自独立 不许折成一枚 Boolean`() {
        val decisions = table.map {
            IcsExportGate.decide(it.eventCount, it.skipped, it.text, it.fileSelected)
        }
        val seen = decisions.map { it.javaClass.simpleName }.toSet()
        assertEquals(
            "本表的格子必须把四档**各跑出一枚**（实到 $seen）。只跑出三枚 = 有两档被折成一档 " +
                "（那就是屏幕上少了一句实话）；跑出四枚以上 = 判据里长出了本表不认识的分支。" +
                "「分支恰好四枚」那一格的静态尺在 IcsExportGateWiringGuardTest（JVM 侧读不到 sealed 的分支表：" +
                "本工程没有 kotlin-reflect，`KClass.sealedSubclasses` 直接抛 KotlinReflectionNotSupportedError，" +
                "实测过、记在这儿免得下一个人再撞）",
            setOf("NoFileSelected", "RefuseEmpty", "RefuseInconsistent", "WriteTruncating"),
            seen,
        )
        assertEquals(
            "四档必须各出一枚、一枚都不重复（跑出的档数 ${seen.size} 与格子数 ${decisions.size} 之间" +
                "只差在重复上；实到 ${seen.size} 档）",
            4,
            seen.size,
        )
    }

    // ─────────────── ⑤ 纯函数：不读时钟、无状态、同输入两次同结论 ───────────────

    @Test
    fun `同输入连判两次必须同结论`() {
        for (cell in table) {
            assertEquals(
                "格子「${cell.name}」两次判定不一致 = 判据里有状态（时钟、缓存或别的格子留下的账）：" +
                    "同一次导出里「写 / 不写」翻面，用户看到的那句与文件实际被动的次数就对不上",
                IcsExportGate.decide(cell.eventCount, cell.skipped, cell.text, cell.fileSelected).toString(),
                IcsExportGate.decide(cell.eventCount, cell.skipped, cell.text, cell.fileSelected).toString(),
            )
        }
    }

    // ─────────────── ⑥ 句子：档位与文案同源（句子在本表里逐字写死） ───────────────

    @Test
    fun `每一档念自己那一句`() {
        for (cell in table) {
            val decision = IcsExportGate.decide(cell.eventCount, cell.skipped, cell.text, cell.fileSelected)
            val message = when (decision) {
                is IcsExportDecision.WriteTruncating -> icsExportWrittenMessage(decision)
                is IcsExportDecision.RefuseEmpty -> icsExportRefusedMessage(decision)
                is IcsExportDecision.RefuseInconsistent -> icsExportInconsistentMessage(decision)
                IcsExportDecision.NoFileSelected -> null
            }
            assertEquals(
                "格子「${cell.name}」那一句：期望 ${cell.literalMessage ?: "（一句不说）"}，实到 $message",
                cell.literalMessage,
                message,
            )
            if (decision.mutation == IcsTargetFileMutation.LEAVE_UNTOUCHED) {
                if (decision is IcsExportDecision.NoFileSelected) {
                    assertNull("取消那一档一句都不许念（实到「$message」）", message)
                    continue
                }
                assertTrue(
                    "不落盘的两档句子必须自己说清「未写出文件」（格子「${cell.name}」：$message）—— " +
                        "不说这一手，用户会以为屏幕后头那份文件已经换了内容",
                    message != null && message.contains("未写出文件"),
                )
                assertFalse(
                    "不落盘的档位不许出现「已导出」三个字（格子「${cell.name}」：$message）—— " +
                        "本卡病灶就是把「没内容」念成成功回执（#309 的后半句）",
                    message != null && message.contains("已导出"),
                )
            } else {
                assertTrue("写出那一档必须报数（格子「${cell.name}」：$message）", message!!.startsWith("已导出 "))
            }
        }
    }

    /** 全角括号逐枚自配平：与 `UserVisibleParenBalanceGuardTest` 同一口径，这里只管本卡那三句 */
    @Test
    fun `句子各自闭合 不许把括号押到下一段`() {
        val sentences = listOf(
            icsExportWrittenMessage(IcsExportDecision.WriteTruncating(FULL_ICS, 12, 0)),
            icsExportWrittenMessage(IcsExportDecision.WriteTruncating(FULL_ICS, 12, 3)),
            icsExportRefusedMessage(IcsExportDecision.RefuseEmpty(0)),
            icsExportRefusedMessage(IcsExportDecision.RefuseEmpty(7)),
            icsExportInconsistentMessage(IcsExportDecision.RefuseInconsistent(5)),
        )
        for (sentence in sentences) {
            assertEquals(
                "这句里「（」与「）」枚数不等 ⇒ 括号押在下一段了（整句：$sentence）",
                sentence.count { it == '（' },
                sentence.count { it == '）' },
            )
        }
        val refusalSentences = table
            .filter { it.eventCount <= 0 && it.fileSelected }
            .map { IcsExportGate.decide(it.eventCount, it.skipped, it.text, it.fileSelected) }
            .filterIsInstance<IcsExportDecision.RefuseEmpty>()
            .map { icsExportRefusedMessage(it) }
        assertTrue("0 条那一档没有句子可验：本格空转", refusalSentences.isNotEmpty())
        for (sentence in refusalSentences) {
            assertFalse(
                "0 条那一档被拼出成功回执（实到「$sentence」）：改前那句「已导出 0 个日程」就是这一形",
                sentence.contains("已导出 0 个日程"),
            )
        }
    }

    // ─────────────── 素材 ───────────────

    private fun describeText(text: String): String = when {
        text.isEmpty() -> "空串"
        text.isBlank() -> "全空白「${text.replace("\r", "\\r").replace("\n", "\\n")}」"
        text == EMPTY_SKELETON -> "合法空骨架"
        else -> "完整导出件"
    }

    private data class Cell(
        val name: String,
        val eventCount: Int,
        val skipped: Int,
        val text: String,
        val fileSelected: Boolean,
        val expected: IcsExportDecision,
        /** 这一档屏幕上那句**逐字写死的原文**（取消那一档给 null）；拿文案函数算出来再跟自己对是恒真 */
        val literalMessage: String?,
        val why: String,
        val mutantSide: String,
    ) {
        val expectedLabel: String get() = expected.toString()
    }

    private companion object {

        /** 判成 WriteTruncating（唯一能动用户文件的那一档）的格子枚数 */
        const val WRITE_CELLS = 2

        /** 导出器交回的真导出件（条数 > 0 那一档的正文） */
        val FULL_ICS = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"

        /**
         * 乙档那个形状：合法的零日程日历骨架。今天 `IcsExporter.export` 交回的是空串，
         * 但表里必须留着这一等 —— 它钉的是「导出器哪天改成写骨架，判据也不许因此又开始动用户的文件」。
         */
        val EMPTY_SKELETON = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n"

        val table: List<Cell> = listOf(
            Cell(
                name = "① 正常导出且有几条被跳过",
                eventCount = 12,
                skipped = 3,
                text = FULL_ICS,
                fileSelected = true,
                expected = IcsExportDecision.WriteTruncating(FULL_ICS, 12, 3),
                literalMessage = "已导出 12 个日程，可在系统日历中导入（3 个课次因节次时间缺失被跳过）",
                why = "12 条真日程 + 3 条因节次缺失没排上：照写，句子把两笔一起报（跳过不是没收整份导出件的理由）",
                mutantSide = "更保守的方向跑掉了（朝窄）",
            ),
            Cell(
                name = "② 正常导出没有跳过",
                eventCount = 48,
                skipped = 0,
                text = FULL_ICS,
                fileSelected = true,
                expected = IcsExportDecision.WriteTruncating(FULL_ICS, 48, 0),
                literalMessage = "已导出 48 个日程，可在系统日历中导入",
                why = "最普通的一等：本卡的护栏不许碰它",
                mutantSide = "更保守的方向跑掉了（朝窄）",
            ),
            Cell(
                name = "③ 卡面那一格：0 条 + 空串 + 已选中",
                eventCount = 0,
                skipped = 0,
                text = "",
                fileSelected = true,
                expected = IcsExportDecision.RefuseEmpty(0),
                literalMessage = "这个学期排不出任何课次，没有可导出的日程，未写出文件，你选中的文件没有被改动",
                why = "课全落在学期外（T222 裁周次）时走的就是这一等：改前在这里开流截断，再念「已导出 0 个日程」",
                mutantSide = "更宽松的方向跑掉了（朝宽）",
            ),
            Cell(
                name = "④ 0 条因为节次全不在作息表里",
                eventCount = 0,
                skipped = 37,
                text = "",
                fileSelected = true,
                expected = IcsExportDecision.RefuseEmpty(37),
                literalMessage = "没有可导出的日程（37 个课次因节次时间缺失被跳过），未写出文件，你选中的文件没有被改动",
                why = "同一不落盘档位，但句子要说得出 37 条被跳过 —— 那是用户唯一能动手的地方",
                mutantSide = "更宽松的方向跑掉了（朝宽）",
            ),
            Cell(
                name = "⑤ 0 条而文本是合法空骨架",
                eventCount = 0,
                skipped = 0,
                text = EMPTY_SKELETON,
                fileSelected = true,
                expected = IcsExportDecision.RefuseEmpty(0),
                literalMessage = "这个学期排不出任何课次，没有可导出的日程，未写出文件，你选中的文件没有被改动",
                why = "按「文本非空白就开流」那把朝宽的尺，这里又开始动用户的文件：判据认的是条数，不是字节数",
                mutantSide = "更宽松的方向跑掉了（朝宽）",
            ),
            Cell(
                name = "⑥ 条数被上游算成负数",
                eventCount = -1,
                skipped = 2,
                text = EMPTY_SKELETON,
                fileSelected = true,
                expected = IcsExportDecision.RefuseEmpty(2),
                literalMessage = "没有可导出的日程（2 个课次因节次时间缺失被跳过），未写出文件，你选中的文件没有被改动",
                why = "负数不是「有几条可写」：`<= 0` 那一判把它收进不落盘，`== 0` 那种写法会放它去开流",
                mutantSide = "更宽松的方向跑掉了（朝宽）",
            ),
            Cell(
                name = "⑦ 条数为正而导出件空白",
                eventCount = 24,
                skipped = 0,
                text = "",
                fileSelected = true,
                expected = IcsExportDecision.RefuseInconsistent(24),
                literalMessage = "导出失败：算出 24 个日程却没有导出内容，未写出文件，你选中的文件没有被改动",
                why = "导出器两格打架（报得出数、交不回正文）：单独一档，既不截断也不叫用户去补课表",
                mutantSide = "更宽松的方向跑掉了（朝宽）",
            ),
            Cell(
                name = "⑧ 条数为正而导出件全空白",
                eventCount = 1,
                skipped = 4,
                text = "  \r\n ",
                fileSelected = true,
                expected = IcsExportDecision.RefuseInconsistent(1),
                literalMessage = "导出失败：算出 1 个日程却没有导出内容，未写出文件，你选中的文件没有被改动",
                why = "`isBlank()` 认全空白：一串空格写进 .ics 也照样是「把用户文件换成没用的东西」",
                mutantSide = "更宽松的方向跑掉了（朝宽）",
            ),
            Cell(
                name = "⑨ 没选中文件 × 有内容",
                eventCount = 30,
                skipped = 0,
                text = FULL_ICS,
                fileSelected = false,
                expected = IcsExportDecision.NoFileSelected,
                literalMessage = null,
                why = "取消就是取消：不动盘也一句不说",
                mutantSide = "更啰嗦的方向跑掉了（给取消编一句失败回执）",
            ),
            Cell(
                name = "⑩ 没选中文件 × 0 条",
                eventCount = 0,
                skipped = 5,
                text = "",
                fileSelected = false,
                expected = IcsExportDecision.NoFileSelected,
                literalMessage = null,
                why = "判据顺序：先问有没有文件。反过来判就替用户断言了「有文件可选」",
                mutantSide = "顺序颠倒（先判条数）",
            ),
            Cell(
                name = "⑪ 没选中文件 × 打架那一等",
                eventCount = 8,
                skipped = 0,
                text = "",
                fileSelected = false,
                expected = IcsExportDecision.NoFileSelected,
                literalMessage = null,
                why = "同上：没落地文件时后面两判都无从谈起",
                mutantSide = "顺序颠倒（先判文本）",
            ),
        )
    }
}
