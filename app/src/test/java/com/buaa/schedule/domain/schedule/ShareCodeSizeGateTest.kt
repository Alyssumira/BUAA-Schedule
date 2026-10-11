package com.buaa.schedule.domain.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T283②：`ShareCodeSizeGate` 的表驱动单测（台账 #530 发现 1 + 发现 2）。
 *
 * 每枚边界都取**两侧各一格**（上限本身 / 上限 +1 / 上限 −1），因为这一族最容易漂的就是
 * `>` 与 `>=` 那一枚方向：字符数那把尺「等于上限」要放行（写侧自己贴边算合法），
 * 解压那把尺「等于上限」要拦（循环就是在这一格停的，且「占满上限」不是「在上限内」）。
 * 纯 JVM：不碰设备、不读时钟、不起协程。
 */
class ShareCodeSizeGateTest {

    private data class EntryCell(
        val name: String,
        val codeChars: Int,
        val maxCodeChars: Int,
        val expectOver: Boolean,
    )

    @Test
    fun 入口字符数每一格() {
        val max = ShareCodeSizeGate.MAX_SHARE_CODE_CHARS
        val cells = listOf(
            EntryCell("空串（0 字符）放行", 0, max, expectOver = false),
            EntryCell("上限减一 放行", max - 1, max, expectOver = false),
            EntryCell("恰好等于上限 放行（判据是 > 不是 >=）", max, max, expectOver = false),
            EntryCell("上限加一 拦下", max + 1, max, expectOver = true),
            EntryCell("远超上限（40MB 文本那一等）拦下", 40_000_000, max, expectOver = true),
            EntryCell("小上限边界：等于 放行", 100, 100, expectOver = false),
            EntryCell("小上限边界：加一 拦下", 101, 100, expectOver = true),
        )
        for (cell in cells) {
            val decision = ShareCodeSizeGate.reviewEntry(cell.codeChars, cell.maxCodeChars)
            assertEquals(
                "${cell.name}：实到 ${decision::class.simpleName}",
                cell.expectOver,
                decision is ShareCodeEntryGate.TooManyChars,
            )
            if (cell.expectOver) {
                val over = decision as ShareCodeEntryGate.TooManyChars
                assertEquals("${cell.name}：字符数读数要跟着结论走", cell.codeChars, over.codeChars)
                assertEquals("${cell.name}：上限读数要跟着结论走", cell.maxCodeChars, over.maxCodeChars)
            }
        }
    }

    @Test
    fun 默认上限就是册子里那一枚() {
        val over = ShareCodeSizeGate.reviewEntry(ShareCodeSizeGate.MAX_SHARE_CODE_CHARS + 1)
        assertTrue("默认参数没吃到册子里的那枚上限：实到 $over", over is ShareCodeEntryGate.TooManyChars)
        assertEquals(
            ShareCodeSizeGate.MAX_SHARE_CODE_CHARS,
            (over as ShareCodeEntryGate.TooManyChars).maxCodeChars,
        )
    }

    private data class PayloadCell(
        val name: String,
        val decompressedBytes: Int,
        val capBytes: Int,
        val streamFinished: Boolean,
        val expect: String,
    )

    @Test
    fun 解压体积每一格() {
        val cap = 100
        val cells = listOf(
            PayloadCell("流结束 + 上限减一 放行", 99, cap, true, "Proceed"),
            PayloadCell("流结束 + 恰好占满上限 太大（占满不等于在上限内）", cap, cap, true, "OverCap"),
            PayloadCell("流结束 + 超上限（不该发生，仍旧按太大说）", 101, cap, true, "OverCap"),
            PayloadCell("流没结束 + 撞上限 太大（口令完整、内容超上限）", cap, cap, false, "OverCap"),
            PayloadCell("流没结束 + 已越上限 太大", 500, cap, false, "OverCap"),
            PayloadCell("流没结束 + 上限减一 流自己断了（这才叫没复制完整）", 99, cap, false, "Stopped"),
            PayloadCell("零字节 + 流结束 放行（空表交给信封那一问）", 0, cap, true, "Proceed"),
            PayloadCell("零字节 + 流没结束 流自己断了", 0, cap, false, "Stopped"),
        )
        for (cell in cells) {
            val decision = ShareCodeSizeGate.reviewPayload(cell.decompressedBytes, cell.capBytes, cell.streamFinished)
            val actual = when (decision) {
                is ShareCodePayloadGate.Proceed -> "Proceed"
                is ShareCodePayloadGate.OverCap -> "OverCap"
                is ShareCodePayloadGate.StreamStoppedEarly -> "Stopped"
            }
            assertEquals("${cell.name}：实到 $actual", cell.expect, actual)
            if (cell.expect == "OverCap") {
                val over = decision as ShareCodePayloadGate.OverCap
                assertEquals("${cell.name}：字节数读数", cell.decompressedBytes, over.decompressedBytes)
                assertEquals("${cell.name}：上限读数", cell.capBytes, over.capBytes)
                assertEquals(
                    "${cell.name}：流是否结束要原样递出来（句子要说的是完整还是截断）",
                    cell.streamFinished,
                    over.streamFinished,
                )
            }
        }
    }

    @Test
    fun 三档没有一档被折成Boolean() {
        // 本工程没有 kotlin-reflect ⇒ `KClass.sealedSubclasses` 直接抛 KotlinReflectionNotSupportedError
        //（在册教训见 IcsExportGateTest 第 ④ 层那句"实测过、记在这儿免得下一个人再撞"）。
        // 这一格因此吃的是**内核跑出来的档**而不是分支表；"声明恰好几枚"那把静态尺在
        // ShareCodeEntryGateWiringGuardTest 第 ⑤/⑥ 层（读的是源码里的声明枚数）。
        val entrySeen = listOf(
            ShareCodeSizeGate.reviewEntry(0, 10),
            ShareCodeSizeGate.reviewEntry(11, 10),
        ).map { it.javaClass.simpleName }.sorted()
        assertEquals(
            "入口判据的两档（放行 / 字符数越界）各跑出过一枚；折成 Boolean 就等于把太大与解不出来合体：实到 $entrySeen",
            listOf("Proceed", "TooManyChars"),
            entrySeen,
        )
        val payloadSeen = listOf(
            ShareCodeSizeGate.reviewPayload(99, 100, streamFinished = true),
            ShareCodeSizeGate.reviewPayload(100, 100, streamFinished = false),
            ShareCodeSizeGate.reviewPayload(99, 100, streamFinished = false),
        ).map { it.javaClass.simpleName }.sorted()
        assertEquals(
            "解压判据的三档（放行 / 超上限 / 流自己断了）各跑出过一枚：改前这里只有能不能解出 BackupData " +
                "一枚 Boolean，于是超上限与坏压缩在屏上是同一句假话：实到 $payloadSeen",
            listOf("OverCap", "Proceed", "StreamStoppedEarly"),
            payloadSeen,
        )
    }

    @Test
    fun 句子说得出数字且不许劝人重抄一条完整的码() {
        val overCap = ShareCodeSizeGate.reviewPayload(
            4 * 1024 * 1024,
            4 * 1024 * 1024,
            streamFinished = false,
        ) as ShareCodePayloadGate.OverCap
        val copy = shareCodeOverCapCopy(overCap)
        assertTrue("太大那一档必须报得出字节数：$copy", copy.contains("4194304"))
        assertTrue("太大那一档要说一条都没写：$copy", copy.contains("一条都没有写入"))
        assertFalse(
            "完整但太大的一等，句子不许劝人「检查是否完整复制」—— 那条口令往往就是完整的，" +
                "劝人重抄一条本来就完整的码是假话（#530 发现 2 改前正是这一档折进了 Malformed）：$copy",
            copy.contains("完整复制"),
        )
        val tooMany = ShareCodeEntryGate.TooManyChars(20_000_000, ShareCodeSizeGate.MAX_SHARE_CODE_CHARS)
        val entryCopy = shareCodeTooManyCharsCopy(tooMany)
        assertTrue("字符数那一档要报得出实到数：$entryCopy", entryCopy.contains("20000000"))
        assertTrue("字符数那一档也要报上限：$entryCopy", entryCopy.contains("1000000"))
        // 第三档（流自己停在中途）**故意没有第三句**：它要说的那句「请检查是否完整复制」在调用点的
        // Malformed 那支已有在册原文（同句两写是本仓在册的病，见 ShareCodeSizeGate.kt 末尾那段）。
        // 这一格因此钉的是"读数还在"——句子借别人的，数字必须自己带着走，否则 logcat 查不出是哪一站。
        val stalled = ShareCodePayloadGate.StreamStoppedEarly(1024, 4 * 1024 * 1024)
        assertEquals("断流那一档要报得出停在几字节", 1024, stalled.decompressedBytes)
        assertEquals("断流那一档也要报得出上限", 4 * 1024 * 1024, stalled.capBytes)
        val stalledAgain = ShareCodeSizeGate.reviewPayload(1024, 4 * 1024 * 1024, streamFinished = false)
        assertTrue("流没走完而没撞上限 ⇒ 只能是断流那一档，不许算成太大：$stalledAgain", stalledAgain is ShareCodePayloadGate.StreamStoppedEarly)
    }

    @Test
    fun 体积尺与条数尺是两把尺互不代替() {
        // 条数上限（节次 500）与体积上限（4MB / 1M 字符）各管一头：
        // 一条 600 行节次的口令是「条数越界」，一条解压后超 4MB 的口令是「体积越界」，两句话都得说得出。
        val slots = ShareCodeSizeGate.reviewPayload(1_000, 4 * 1024 * 1024, streamFinished = true)
        assertTrue("规模内的解压结果不该被体积尺拦住：$slots", slots is ShareCodePayloadGate.Proceed)
        val counts = RestoreCounts(courseCount = 1, timeSlotCount = 600, reminderCount = 0)
        val reject = RestoreVolumeGate.capacityViolation(counts, RestoreVolumeLimits.Default, RestoreEntry.ShareCode)
        assertEquals(
            "条数那一档仍旧由 RestoreVolumeGate 说（本卡没动它，也不许在体积尺这边另起一枚上限）",
            OverCapacityTable.TimeSlots,
            reject?.table,
        )
    }
}
