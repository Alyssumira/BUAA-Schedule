package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.ui.settings.wakeUpExportInconsistentMessage
import com.buaa.schedule.ui.settings.wakeUpExportRefusedMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * T279②：把「WakeUp 导出到底该不该碰用户选中的那个文件」那一判钉成表驱动单测（纯 JVM、零时钟注入面）。
 *
 * 判据本体在 `domain/schedule/WakeUpExportGate.kt` 的 [WakeUpExportGate.decide]，句子在
 * `ui/settings/WakeUpExportCopy.kt`（末尾那几格连句子一起钉，句子在表里是**逐字写死的原文**，
 * 不是拿文案函数自己算一遍再跟自己对 —— 那种断言恒真）。
 *
 * ## 病（用户看得见的那一串：自己选的文件被换成空课表，屏幕还报喜）
 *
 * `exportWakeUpTo` 改前唯一的门判的是**入参**（`courses.isEmpty()`）。两档 drop（没节次 = T27、
 * 没周次 = #237）把每一门都丢下时，`toWakeUpJson` 交回的是 `{"name":"…","courses":[]}`，而调用点
 * 下一手是默认的截断写 ⇒ 用户挑的那份 `.json` 当场被换成一张空课表，屏幕上念的仍是
 * 「WakeUp JSON 已导出（0 段排课；另有 N 段…未写入）」。**0 段这一格可达**：表 ③ 吃的就是
 * `WakeUpExportDropQuadrantTest` 已证的输入形状（库的读路径与备份恢复链都交得出「缺节次 / 缺周次」的课），
 * 而 ③ 那组格子之外另有一格（⑤）拿**生产导出器 + 生产判据**真算一遍两枚数，不拿表里假设的数字自证。
 *
 * ## 两侧都要有格子
 *
 * - **朝宽**（判据只看「文本非空白」就开流 —— 那正是改前的形状，改前压根没有判据）⇒
 *   表 ③（0 段但有合法空骨架）与表 ⑨（段数被上游算成负数）会被截断：
 *   [朝宽那把错尺 只看文本就会把用户文件截成空课表] 把这两格的差异当场量出来；
 * - **朝窄**（「只要有段被丢掉就不写」那种看着更稳的保守尺）⇒ 表 ② 那种「15 段真写出去 + 3 段被丢」
 *   的正常导出被整条拒掉，用户明明有课却读到「没有可导出的内容」：
 *   [朝窄那把错尺 有丢段就不写会把正常导出整条拒掉] 量的是这一侧。
 * 两把错尺都在测试体内**并排复算**：格子先断言错尺自己真的咬得住（反证自证），再断言本内核给出相反、
 * 正确的那一侧 —— 红名会说清是哪一边跑掉了。
 *
 * ## 档位如实写明
 *
 * `:app` 的 JVM 测试面只有 junit（无 Robolectric、未开 `returnDefaultValues`）⇒ 本文件钉的是**判据与句子**；
 * 「调用点真按判据走、开流那一手只长在 WriteTruncating 支里」由 `WakeUpExportGateWiringGuardTest` 钉源码形状。
 * ⚠️ 未装机：系统选择器建文档那一刻文件是什么状态、`openOutputStream` 的实际截断行为，本卡零设备没有读数。
 */
class WakeUpExportGateTest {

    private companion object {
        const val VALID_JSON = """{"name":"本学期","courses":[{"name":"高数","day":1}]}"""
        const val EMPTY_COURSES_JSON = """{"name":"本学期","startDate":"2026-09-07","courses":[]}"""
        const val BLANK_TEXT = "   \n "

        fun course(name: String, periods: List<Int>, weeks: List<Int>) = Course(
            id = name.hashCode().toLong(),
            name = name,
            teacher = "张三",
            location = "J3-101",
            dayOfWeek = 1,
            periods = periods,
            weeks = weeks,
        )
    }

    private class Cell(
        val name: String,
        val written: Int,
        val dropped: Int,
        val json: String,
        val fileSelected: Boolean,
        /** 期望档位用名字说（四档各一名），红的时候读得出是哪一档跑掉了 */
        val expected: String,
        val why: String,
    )

    private val table = listOf(
        Cell(
            name = "① 正常导出：18 段都写出去、一段没丢、已选中文件",
            written = 18, dropped = 0, json = VALID_JSON, fileSelected = true,
            expected = "WriteTruncating",
            why = "有课可写就照旧截断写，本卡不动这一等",
        ),
        Cell(
            name = "② 15 段写出去 + 3 段被丢（两档 drop 的正常混合法）",
            written = 15, dropped = 3, json = VALID_JSON, fileSelected = true,
            expected = "WriteTruncating",
            why = "丢了几段不等于一段都没了：这一等必须照写（朝窄那把错尺就死在这一格）",
        ),
        Cell(
            name = "③ 全部片段被丢：0 段写出去 / 3 段被丢 / 文本是合法的空 courses 骨架",
            written = 0, dropped = 3, json = EMPTY_COURSES_JSON, fileSelected = true,
            expected = "RefuseEmpty",
            why = "本卡的病灶那一格：交回的文本非空白（顶层 name 与 tableInfo 都在），" +
                "按文本判就又开始截用户的文件；按段数判才钉得住甲档（零段完全不碰那个文件）",
        ),
        Cell(
            name = "④ 0 段而丢段数为 0（段数是零这一侧的边界，丢没丢只改句子不改档）",
            written = 0, dropped = 0, json = EMPTY_COURSES_JSON, fileSelected = true,
            expected = "RefuseEmpty",
            why = "droppedFragments 只决定句子里念不念那枚数，不决定判据",
        ),
        Cell(
            name = "⑤ 段数 1 而导出件是空白串",
            written = 1, dropped = 0, json = BLANK_TEXT, fileSelected = true,
            expected = "RefuseInconsistent",
            why = "报得出段数却没有正文 = 导出器内部两格打架，同样不许开流",
        ),
        Cell(
            name = "⑥ 段数 1 而导出件是空串",
            written = 1, dropped = 0, json = "", fileSelected = true,
            expected = "RefuseInconsistent",
            why = "⑤ 的下侧边界：空串与空白串都算空白文本",
        ),
        Cell(
            name = "⑦ 用户没选中文件（选择器取消）：别的事实全正常",
            written = 18, dropped = 0, json = VALID_JSON, fileSelected = false,
            expected = "NoFileSelected",
            why = "取消不是失败：一句话不说，也不许拿别的档位的句子凑一句",
        ),
        Cell(
            name = "⑧ 未选中文件 + 0 段（判据顺序：这一等先于段数判）",
            written = 0, dropped = 3, json = EMPTY_COURSES_JSON, fileSelected = false,
            expected = "NoFileSelected",
            why = "顺序即语义：连「要不要写」都还没问出来，就先别谈写了什么",
        ),
        Cell(
            name = "⑨ 段数被上游算成负数（0 的那一侧）",
            written = -1, dropped = 3, json = VALID_JSON, fileSelected = true,
            expected = "RefuseEmpty",
            why = "负数与 0 同判（`<= 0`）：报不出正数就没有可写的内容，截断写照旧要挡",
        ),
        Cell(
            name = "⑩ 段数 1 而文本只有一枚空格",
            written = 1, dropped = 0, json = " ", fileSelected = true,
            expected = "RefuseInconsistent",
            why = "⑤ 的另一侧边界：一枚空格也算空白",
        ),
        Cell(
            name = "⑪ 段数 1 而文本是带换行的非空白骨架",
            written = 1, dropped = 0, json = "{\n \"courses\": [] \n}", fileSelected = true,
            expected = "WriteTruncating",
            why = "⑩ 的另一侧：带换行的文本**不**是空白 ⇒ 有正文可写（那枚换行的合法性归 N2 的转义层管）",
        ),
        Cell(
            name = "⑫ 未选中文件 + 空白文本（两支都看着像要拒）",
            written = 0, dropped = 0, json = "", fileSelected = false,
            expected = "NoFileSelected",
            why = "取消那一等排在判据链最前面，后面两判都无从谈起",
        ),
    )

    private fun tier(decision: WakeUpExportDecision): String = decision.javaClass.simpleName

    // ─────────────── ① 判据表：一格一笔 ───────────────

    @Test
    fun `判据表 每一格都按预期给出结论`() {
        for (cell in table) {
            val actual = WakeUpExportGate.decide(cell.written, cell.dropped, cell.json, cell.fileSelected)
            val side = if (cell.expected == "WriteTruncating") {
                "窄（该写的没写）"
            } else {
                "宽（该不动盘的动了盘）"
            }
            assertEquals(
                "格子「${cell.name}」：写出去=${cell.written}、丢下=${cell.dropped}、" +
                    "文本=「${cell.json.take(24)}」、已选中文件=${cell.fileSelected} ⇒ " +
                    "期望 ${cell.expected}，实到 ${tier(actual)} —— ${cell.why}\n" +
                    "这一格红了，说明判据朝${side}的方向跑掉了",
                cell.expected,
                tier(actual),
            )
        }
    }

    /** 卡面点名的承重格：0 段而用户已经选中文件 ⇒ **不落盘**，一个字节都不许动 */
    @Test
    fun `零段且已选中文件 一律不落盘`() {
        val cells = table.filter { it.written <= 0 && it.fileSelected }
        assertTrue(
            "表里没有「0 段（或负数）+ 已选中」的格子了：本卡的主案发现场被从表里删走，这一格就成了空转",
            cells.isNotEmpty(),
        )
        for (cell in cells) {
            val actual = WakeUpExportGate.decide(cell.written, cell.dropped, cell.json, cell.fileSelected)
            assertFalse(
                "「${cell.name}」（写出去 ${cell.written} 段、文本「${cell.json.take(24)}」）判成了 ${tier(actual)} —— " +
                    "WriteTruncating 一出场就是拿默认模式开流，用户选中的那份 .json 当场被截成一张空课表。" +
                    "这一等必须落进 Refuse*（本卡的病灶本身，审计 N4）",
                actual is WakeUpExportDecision.WriteTruncating,
            )
            assertEquals(
                "「${cell.name}」对用户文件的处置必须是 LEAVE_UNTOUCHED，实到 ${actual.mutation}",
                WakeUpTargetFileMutation.LEAVE_UNTOUCHED,
                actual.mutation,
            )
        }
    }

    /** 四档各出一枚真落点：判据今天确实交得出四枚不同结论（不是四档并成三档） */
    @Test
    fun `四档各出一枚 且只有写出那一档交截断写`() {
        val decisions = listOf(
            WakeUpExportGate.decide(18, 0, VALID_JSON, true),
            WakeUpExportGate.decide(0, 3, EMPTY_COURSES_JSON, true),
            WakeUpExportGate.decide(1, 0, BLANK_TEXT, true),
            WakeUpExportGate.decide(18, 0, VALID_JSON, false),
        )
        assertEquals(
            "四枚输入必须交出四枚不同档位（实到 ${decisions.map { tier(it) }}）",
            4,
            decisions.map { tier(it) }.distinct().size,
        )
        assertEquals(
            "不落盘的三档各一枚 LEAVE_UNTOUCHED",
            listOf(
                WakeUpTargetFileMutation.LEAVE_UNTOUCHED,
                WakeUpTargetFileMutation.LEAVE_UNTOUCHED,
                WakeUpTargetFileMutation.LEAVE_UNTOUCHED,
            ),
            listOf(decisions[1].mutation, decisions[2].mutation, decisions[3].mutation),
        )
        assertEquals(
            "写出那一档是唯一交截断写的一档",
            WakeUpTargetFileMutation.TRUNCATE_AND_WRITE,
            decisions[0].mutation,
        )
        assertEquals("落盘模式只有两档（不许折成一枚 Boolean）", 2, WakeUpTargetFileMutation.entries.size)
        val write = decisions[0] as WakeUpExportDecision.WriteTruncating
        // 正文只由 WriteTruncating 交回：另三档在类型上就没有 json 可递
        assertEquals(VALID_JSON, write.json)
        assertEquals(18, write.writtenFragments)
        assertEquals(0, write.droppedFragments)
        assertEquals(3, (decisions[1] as WakeUpExportDecision.RefuseEmpty).droppedFragments)
        assertEquals(1, (decisions[2] as WakeUpExportDecision.RefuseInconsistent).writtenFragments)
        assertEquals(WakeUpExportDecision.NoFileSelected, decisions[3])
    }

    // ─────────────── ② 两把错尺并排复算 ───────────────

    @Test
    fun `朝宽那把错尺 只看文本就会把用户文件截成空课表`() {
        val diverging = table.mapNotNull { cell ->
            val wrongRulerWrites = cell.fileSelected && cell.json.isNotBlank()
            val actual = WakeUpExportGate.decide(cell.written, cell.dropped, cell.json, cell.fileSelected)
            if (wrongRulerWrites && actual !is WakeUpExportDecision.WriteTruncating) cell else null
        }
        assertTrue(
            "两把尺至少要在「③ 0 段却有合法骨架」「⑨ 段数为负」这两格分家（实到 ${diverging.size} 格：" +
                diverging.joinToString { it.name } + "）。读到 0 格 = 本内核与错尺在同一张表上判得一模一样，" +
                "那这格就是空转，「按文本判」那条路没人挡",
            diverging.size >= 2,
        )
        for (cell in diverging) {
            val actual = WakeUpExportGate.decide(cell.written, cell.dropped, cell.json, cell.fileSelected)
            assertFalse(
                "「${cell.name}」：错尺（只看文本非空白）会开流而本内核判成了 ${tier(actual)} —— " +
                    "两把尺必须在这一格分家，否则「按文本判」那一条路又回来了" +
                    "（改前正是这么把用户选中的 .json 换成一张空课表的）",
                actual is WakeUpExportDecision.WriteTruncating,
            )
            assertEquals(WakeUpTargetFileMutation.LEAVE_UNTOUCHED, actual.mutation)
        }
    }

    @Test
    fun `朝窄那把错尺 有丢段就不写会把正常导出整条拒掉`() {
        val diverging = table.mapNotNull { cell ->
            val wrongRulerRefuses = cell.fileSelected && cell.dropped > 0
            val actual = WakeUpExportGate.decide(cell.written, cell.dropped, cell.json, cell.fileSelected)
            if (wrongRulerRefuses && actual is WakeUpExportDecision.WriteTruncating) cell else null
        }
        assertTrue(
            "两把尺至少要在「② 15 段写出去 + 3 段被丢」这一格分家（实到 ${diverging.size} 格：" +
                diverging.joinToString { it.name } + "）。读到 0 格 = 保守尺与真判据在这张表上无从分辨",
            diverging.isNotEmpty(),
        )
        for (cell in diverging) {
            val actual = WakeUpExportGate.decide(cell.written, cell.dropped, cell.json, cell.fileSelected)
            assertTrue(
                "「${cell.name}」：写出去 ${cell.written} 段就必须照写，实到 ${tier(actual)} —— " +
                    "把「有丢段」折成「什么都不写」，用户明明有课却读到「没有可导出的内容」",
                actual is WakeUpExportDecision.WriteTruncating,
            )
            assertEquals(WakeUpTargetFileMutation.TRUNCATE_AND_WRITE, actual.mutation)
        }
    }

    // ─────────────── ③ 段数与生产导出器对得上（不拿表里假设的数字自证） ───────────────

    /**
     * 「全部片段被丢」那一格的输入不是虚构的：这里用**生产导出器 + 生产判据**真算一遍两枚数，
     * 再递给 [WakeUpExportGate.decide]，断言它判成不落盘；最后一格反证"把入参枚数当段数"就是病灶原形。
     */
    @Test
    fun `全被丢的那三门课真算一遍 段数是零 判据就不落盘`() {
        val courses = listOf(
            course(name = "没有节次的课", periods = emptyList(), weeks = listOf(1, 2, 3)),
            course(name = "没有周次的课", periods = listOf(1, 2), weeks = emptyList()),
            course(name = "两档都缺的课", periods = emptyList(), weeks = emptyList()),
        )
        val json = ScheduleExporters.toWakeUpJson(courses, null, emptyList(), LocalDate.of(2026, 9, 15))
        val drops = WakeUpDropTally.tally(courses)
        val written = courses.size - drops.droppedCourses
        assertEquals("三门课都落进两档 drop 之一（一门只落一档）", 3, drops.droppedCourses)
        assertEquals("写出去的段数就是 0（本卡主案发现场的那枚数）", 0, written)
        assertTrue("导出件今天仍交回一张空课表：$json", json.endsWith("\"courses\":[]}"))
        val decision = WakeUpExportGate.decide(
            writtenFragments = written,
            droppedFragments = drops.droppedCourses,
            json = json,
            fileSelected = true,
        )
        assertEquals(
            "段数 0 ⇒ 判成 RefuseEmpty 并把丢下的枚数带进句子（改前这一手是直接开流截断的）",
            WakeUpExportDecision.RefuseEmpty(3).toString(),
            decision.toString(),
        )
        assertEquals(WakeUpTargetFileMutation.LEAVE_UNTOUCHED, decision.mutation)
        val withInputCount = WakeUpExportGate.decide(
            writtenFragments = courses.size,
            droppedFragments = drops.droppedCourses,
            json = json,
            fileSelected = true,
        )
        assertTrue(
            "反证：拿 courses.size（入参枚数）顶段数 = 本卡的病灶原形（三门全被丢却仍判开流）。" +
                "实到 ${tier(withInputCount)}",
            withInputCount is WakeUpExportDecision.WriteTruncating,
        )
    }

    // ─────────────── ④ 句子：各归各档、逐字钉住 ───────────────

    @Test
    fun `不落盘那两档的句子逐字钉住`() {
        assertEquals(
            "有丢段那一支：要说得出「几段为什么没进去」",
            "这 3 段都缺少节次或周次、无法定位，没有可导出的 WakeUp 内容，未写出文件，你选中的文件没有被改动",
            wakeUpExportRefusedMessage(WakeUpExportDecision.RefuseEmpty(3)),
        )
        assertEquals(
            "零丢段那一支：没有「被丢」可报时就只说排不出课",
            "这个学期排不出任何一段可导出的内容，未写出文件，你选中的文件没有被改动",
            wakeUpExportRefusedMessage(WakeUpExportDecision.RefuseEmpty(0)),
        )
        assertEquals(
            "打架那一档的句子必须与「课表排不出课」分开（不叫用户去修一个没坏的地方）",
            "导出失败：算出 2 段可写内容却发现导出件是空的，未写出文件，你选中的文件没有被改动",
            wakeUpExportInconsistentMessage(WakeUpExportDecision.RefuseInconsistent(2)),
        )
        for (sentence in listOf(
            wakeUpExportRefusedMessage(WakeUpExportDecision.RefuseEmpty(3)),
            wakeUpExportRefusedMessage(WakeUpExportDecision.RefuseEmpty(0)),
            wakeUpExportInconsistentMessage(WakeUpExportDecision.RefuseInconsistent(1)),
        )) {
            assertFalse(
                "不落盘的三句里一句都不许出现「已导出」（改后要把「报喜」摘掉的那只手）：$sentence",
                sentence.contains("已导出"),
            )
            assertTrue("每一句都要说清「没写出文件」：$sentence", sentence.contains("未写出文件"))
        }
    }

    /** 单位词那一格（#308 那族）：这两句数的也是排课片段，不许漂回「门」 */
    @Test
    fun `拒绝那两句的单位词只许段`() {
        for (sentence in listOf(
            wakeUpExportRefusedMessage(WakeUpExportDecision.RefuseEmpty(3)),
            wakeUpExportRefusedMessage(WakeUpExportDecision.RefuseEmpty(0)),
            wakeUpExportInconsistentMessage(WakeUpExportDecision.RefuseInconsistent(4)),
        )) {
            assertEquals(
                "句子里的「门」必须 0 枚（那一把尺数是课程行，而这两句吃的是片段枚数）：$sentence",
                0,
                sentence.count { it == '门' },
            )
            assertTrue("单位词得是「段」这一族：$sentence", sentence.contains("段"))
        }
    }

    // ─────────────── ⑤ 内核纯度：判据只吃参数 ───────────────

    /** 同一枚输入两次调用必须给出同一档位（不是「两次点击两种结论」那一族） */
    @Test
    fun `同一枚输入两次判定结果相同`() {
        val first = WakeUpExportGate.decide(0, 2, EMPTY_COURSES_JSON, true)
        val second = WakeUpExportGate.decide(0, 2, EMPTY_COURSES_JSON, true)
        assertEquals(
            "判据不读时钟 / 时区 / 权限 / 设备度量：同表两次调用必须同档（源码枚数由守卫钉）",
            first.toString(),
            second.toString(),
        )
        assertEquals(first.mutation, second.mutation)
    }
}
