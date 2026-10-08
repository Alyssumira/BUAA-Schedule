package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.TimeSlotProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T270②：`RestoreVolumeGate` 的表驱动单测 —— 逐格 + **两侧**的格子。
 *
 * 卡的形状（三档结论、五枚输入）与卡面 ⓪ 问的裁定同源：
 * - 朝宽那一头（改前的病）：闸门只管课程 ⇒ 一条只带 1 门课程、3 万行节次的口令一路绿灯，
 *   [三万行节次的口令被拒在节次那一枚上限] 与 [提醒表也有闸] 两格会红；
 * - 朝窄那一头（修法过头的病）：把合法规模误杀 ⇒ [合法的 200 行节次表不许被误杀] 与
 *   [三枚上限各自留着的余量] 两格会红。
 *
 * 纯 JVM：不碰 Room、不读时钟、不读设备；输入全是已经数好的条数。
 */
class RestoreVolumeGateTest {

    // ─────────────────────── 主表：逐格 ───────────────────────

    private data class Row(
        val name: String,
        val counts: RestoreCounts,
        val limits: RestoreVolumeLimits = RestoreVolumeLimits.Default,
        val entry: RestoreEntry = RestoreEntry.ShareCode,
        val previewed: Boolean = false,
        val insideRestoreTx: Boolean = false,
        val expect: RestoreVolumeDecision,
    )

    private val rows = listOf(
        // ① 本卡的靶子：一条只带 1 门课程、却带 3 万行节次的口令。
        //    改前两道闸门（codec 与 VM）都只数课程 ⇒ 它一路通到库里；今天它必须死在节次那一枚上限。
        Row(
            name = "3 万行节次 + 1 门课（口令、未预览）",
            counts = RestoreCounts(courseCount = 1, timeSlotCount = 30_000, reminderCount = 0),
            expect = RestoreVolumeDecision.Reject(RestoreEntry.ShareCode, OverCapacityTable.TimeSlots, 30_000, 500),
        ),
        Row(
            name = "3 万行节次 + 1 门课（备份文件）⇒ 拒排在入口之前",
            counts = RestoreCounts(1, 30_000, 0),
            entry = RestoreEntry.BackupFile,
            previewed = true,
            expect = RestoreVolumeDecision.Reject(RestoreEntry.BackupFile, OverCapacityTable.TimeSlots, 30_000, 500),
        ),
        Row(
            name = "3 万行节次在事务里复核 ⇒ 仍然拒，不是「门外问过了就写」",
            counts = RestoreCounts(1, 30_000, 0),
            entry = RestoreEntry.BackupFile,
            previewed = true,
            insideRestoreTx = true,
            expect = RestoreVolumeDecision.Reject(RestoreEntry.BackupFile, OverCapacityTable.TimeSlots, 30_000, 500),
        ),
        // ② 课程那一枚上限不许被摘（改前唯一存在的那一把尺）
        Row(
            name = "2001 门课程（口令）",
            counts = RestoreCounts(2_001, 14, 0),
            expect = RestoreVolumeDecision.Reject(RestoreEntry.ShareCode, OverCapacityTable.Courses, 2_001, 2_000),
        ),
        // ③ 提醒那一枚上限也必须存在（朝宽自检：闸门又只数课程的话这一格退回 ConfirmFirst）
        Row(
            name = "2001 条提醒（口令）",
            counts = RestoreCounts(10, 14, 2_001),
            expect = RestoreVolumeDecision.Reject(RestoreEntry.ShareCode, OverCapacityTable.Reminders, 2_001, 2_000),
        ),
        // ④ 三张表同时越界 ⇒ 报**第一个**（课程 → 节次 → 提醒），保证同一份输入只有一句结论
        Row(
            name = "三张表同时越界 ⇒ 报课程那一张",
            counts = RestoreCounts(9_999, 9_999, 9_999),
            expect = RestoreVolumeDecision.Reject(RestoreEntry.ShareCode, OverCapacityTable.Courses, 9_999, 2_000),
        ),
        // ⑤ 乙档：口令未预览而这一趟要动节次表 ⇒ 先问一句，不许直接写
        Row(
            name = "合法 200 行节次 + 30 门课（口令、未预览）⇒ 先确认",
            counts = RestoreCounts(30, 200, 0),
            expect = RestoreVolumeDecision.ConfirmFirst(RestoreEntry.ShareCode, 30, 200, UndoSnapshotNeed.Necessary),
        ),
        Row(
            name = "同一份口令（用户已经点过确认）⇒ 落库",
            counts = RestoreCounts(30, 200, 0),
            previewed = true,
            expect = RestoreVolumeDecision.Write(UndoSnapshotNeed.Necessary),
        ),
        Row(
            name = "备份文件（预览弹窗已经念过条数）⇒ 落库，不追加第二问",
            counts = RestoreCounts(30, 200, 0),
            entry = RestoreEntry.BackupFile,
            previewed = true,
            expect = RestoreVolumeDecision.Write(UndoSnapshotNeed.Necessary),
        ),
        // ⑥ 并尺那一格：文件路哪天少了预览（第三枚调用点），也走同一问 —— 两路同级
        Row(
            name = "备份文件但没预览过 ⇒ 同样先确认（两路同一规则）",
            counts = RestoreCounts(30, 200, 0),
            entry = RestoreEntry.BackupFile,
            previewed = false,
            expect = RestoreVolumeDecision.ConfirmFirst(RestoreEntry.BackupFile, 30, 200, UndoSnapshotNeed.Necessary),
        ),
        // ⑦ 事务里绝不问第二遍（否则就是"写库前自己弹自己"）
        Row(
            name = "事务内复核 + 未预览 ⇒ 直接写，不再问",
            counts = RestoreCounts(30, 200, 0),
            entry = RestoreEntry.BackupFile,
            previewed = false,
            insideRestoreTx = true,
            expect = RestoreVolumeDecision.Write(UndoSnapshotNeed.Necessary),
        ),
        // ⑧ 口令没带节次 ⇒ 仓储那句 isNotEmpty 那一手不执行 ⇒ 无破坏可确认
        Row(
            name = "口令只带课程、不带节次 ⇒ 直接写，快照也不需要",
            counts = RestoreCounts(30, 0, 0),
            expect = RestoreVolumeDecision.Write(UndoSnapshotNeed.None),
        ),
        Row(
            name = "空表（0 条课程 0 条节次）⇒ 本判不问（空课程由 EmptyBackup 那一等管）",
            counts = RestoreCounts(0, 0, 0),
            expect = RestoreVolumeDecision.Write(UndoSnapshotNeed.None),
        ),
        // ⑨ 边界：等于上限放行，上限 +1 拒绝（朝宽方向的 off-by-one 自检）
        Row(
            name = "恰好 500 行节次 ⇒ 不越界，走确认",
            counts = RestoreCounts(1, 500, 0),
            expect = RestoreVolumeDecision.ConfirmFirst(RestoreEntry.ShareCode, 1, 500, UndoSnapshotNeed.Necessary),
        ),
        Row(
            name = "501 行节次 ⇒ 越界",
            counts = RestoreCounts(1, 501, 0),
            previewed = true,
            expect = RestoreVolumeDecision.Reject(RestoreEntry.ShareCode, OverCapacityTable.TimeSlots, 501, 500),
        ),
        Row(
            name = "恰好 2000 门课程 ⇒ 不越界",
            counts = RestoreCounts(2_000, 0, 0),
            previewed = true,
            expect = RestoreVolumeDecision.Write(UndoSnapshotNeed.None),
        ),
        Row(
            name = "恰好 2000 条提醒 ⇒ 不越界",
            counts = RestoreCounts(10, 0, 2_000),
            previewed = true,
            expect = RestoreVolumeDecision.Write(UndoSnapshotNeed.None),
        ),
        // ⑩ 只带 1 行节次也要问一句：这一手是整表替换，不是追加
        Row(
            name = "1 行节次（口令、未预览）⇒ 仍是整表替换，先确认",
            counts = RestoreCounts(5, 1, 0),
            expect = RestoreVolumeDecision.ConfirmFirst(RestoreEntry.ShareCode, 5, 1, UndoSnapshotNeed.Necessary),
        ),
    )

    @Test
    fun `判据表逐格`() {
        for (row in rows) {
            val actual = RestoreVolumeGate.decide(
                counts = row.counts,
                limits = row.limits,
                entry = row.entry,
                previewed = row.previewed,
                insideRestoreTx = row.insideRestoreTx,
            )
            assertEquals(
                "格子「${row.name}」：输入 ${row.counts} / ${row.entry} / previewed=${row.previewed} / " +
                    "insideRestoreTx=${row.insideRestoreTx}",
                row.expect,
                actual,
            )
        }
    }

    // ─────────────────────── 两侧格子 ───────────────────────

    /**
     * 朝宽（改前那一病）：闸门只管课程 ⇒ 3 万行节次的口令在库里落地。
     * 这一格是"摘掉节次那一枚上限"的探针：摘掉之后 decide 交回的是 ConfirmFirst/Write，
     * 红名就指着节次那一枚闸。
     */
    @Test
    fun `三万行节次的口令被拒在节次那一枚上限`() {
        val decision = RestoreVolumeGate.decide(
            counts = RestoreCounts(courseCount = 1, timeSlotCount = 30_000, reminderCount = 1),
            limits = RestoreVolumeLimits.Default,
            entry = RestoreEntry.ShareCode,
            previewed = false,
            insideRestoreTx = false,
        )
        // 用穷举 when 取"到底是哪一档"，而不是先 as? Reject 再补两句 is Write / is ConfirmFirst ——
        // 后者会被上面那枚 as? 的流分析 smart cast 成"永远 false"的套套逻辑（编译期就报 warning，
        // 而套套逻辑的格子改坏了也不红，正是本仓 T118/T121 那一族要防的形状）。
        val tier: Any = when (decision) {
            is RestoreVolumeDecision.Reject -> "${decision.table}/${decision.count}/${decision.limit}"
            is RestoreVolumeDecision.Write -> "Write 档（=${decision.undoSnapshot}）：库里要写行"
            is RestoreVolumeDecision.ConfirmFirst -> "ConfirmFirst 档：问一句就写"
        }
        assertEquals(
            "3 万行节次必须死在**节次**那一枚上限（改前两道闸门都只数课程 ⇒ 它一路通到库里）。" +
                "实到档位：$tier",
            "TimeSlots/30000/500",
            tier,
        )
    }

    /** 朝宽自检之二：提醒表也得有自己的闸（改前两路只数课程）。 */
    @Test
    fun `提醒表也有自己的闸 不许跟着课程那一枚蒙过去`() {
        val decision = RestoreVolumeGate.decide(
            counts = RestoreCounts(courseCount = 5, timeSlotCount = 0, reminderCount = 30_000),
            limits = RestoreVolumeLimits.Default,
            entry = RestoreEntry.BackupFile,
            previewed = true,
            insideRestoreTx = false,
        )
        val reject = decision as? RestoreVolumeDecision.Reject ?: error("档位应当是 Reject，实到 $decision")
        assertEquals(OverCapacityTable.Reminders, reject.table)
    }

    /**
     * 朝窄（修法过头那一病）：卡面点名的**合法 200 行节次表**这一档不许被误杀。
     * 误杀的形状是"任何规模都拒"或"上限被砍到 200 以下"；两档各钉一格。
     */
    @Test
    fun `合法的 200 行节次表不许被误杀`() {
        // (说法, 入口, previewed, insideRestoreTx) 四格：同一份合法规模在每一路上都得活着
        val legitTiers = listOf(
            Triple("口令未预览", RestoreEntry.ShareCode, false) to false,
            Triple("口令已确认", RestoreEntry.ShareCode, true) to false,
            Triple("文件已预览", RestoreEntry.BackupFile, true) to false,
            Triple("事务内复核", RestoreEntry.BackupFile, false) to true,
        )
        for ((tier, insideTx) in legitTiers) {
            val (label, entry, previewed) = tier
            val decision = RestoreVolumeGate.decide(
                counts = RestoreCounts(30, 200, 30),
                limits = RestoreVolumeLimits.Default,
                entry = entry,
                previewed = previewed,
                insideRestoreTx = insideTx,
            )
            assertFalse(
                "合法规模「$label」那一档被误杀成拒了：$decision —— 200 行节次是卡面点名的活路，" +
                    "上限要砍就朝 3 万那一头砍，不许砍到它下面",
                decision is RestoreVolumeDecision.Reject,
            )
        }
        // 应用内那一手根本加不出行（节次区只改时间、不改数量），默认表 14 行更不许被误杀
        val tiny = RestoreVolumeGate.decide(
            RestoreCounts(18, TimeSlotProfile.DEFAULT.size, 18),
            RestoreVolumeLimits.Default,
            RestoreEntry.ShareCode,
            previewed = true,
            insideRestoreTx = false,
        )
        assertEquals(RestoreVolumeDecision.Write(UndoSnapshotNeed.Necessary), tiny)
    }

    /** 朝窄自检之二：三枚上限各自留着的余量（不是 Int.MAX_VALUE，也不是砍到合法规模之下）。 */
    @Test
    fun `三枚上限各自留着的余量`() {
        val limits = RestoreVolumeLimits.Default
        assertTrue(
            "节次上限必须有界（当前 ${limits.maxTimeSlots}）：写成 Int.MAX_VALUE 等于没有闸，" +
                "3 万行那一档会重新活过来",
            limits.maxTimeSlots in 1..9_999,
        )
        assertTrue(
            "节次上限必须留得住合法的 200 行那一档（当前 ${limits.maxTimeSlots}）",
            limits.maxTimeSlots >= 200,
        )
        assertEquals(RestoreVolumeLimits.MAX_TIME_SLOT_COUNT, limits.maxTimeSlots)
        assertTrue("课程上限有界且沿用全仓那一把尺", limits.maxCourses in 1..9_999)
        assertTrue("提醒上限有界", limits.maxReminders in 1..9_999)
    }

    // ─────────────────────── 快照档与写库判据同源 ───────────────────────

    /**
     * 快照档与仓储里那句 `if (safeSlots.isNotEmpty())` 必须同尺：
     * 带 0 条节次时那一手不执行 ⇒ 说"需要快照"就是虚报；带 ≥1 条时整张替换 ⇒ 说"不需要"就是谎报。
     */
    @Test
    fun `撤销快照档与节次表换不换同尺`() {
        assertEquals(UndoSnapshotNeed.None, RestoreVolumeGate.undoSnapshotNeed(RestoreCounts(30, 0, 12)))
        assertEquals(UndoSnapshotNeed.Necessary, RestoreVolumeGate.undoSnapshotNeed(RestoreCounts(30, 1, 12)))
        assertEquals(UndoSnapshotNeed.Necessary, RestoreVolumeGate.undoSnapshotNeed(RestoreCounts(0, 200, 0)))
    }

    // ─────────────────────── 文案：档位与句子同源 ───────────────────────

    @Test
    fun `拒绝那一句说得出哪张表 多少条 上限多少 且一次都没写`() {
        val copy = restoreVolumeRejectCopy(
            RestoreVolumeDecision.Reject(RestoreEntry.ShareCode, OverCapacityTable.TimeSlots, 30_000, 500),
        )
        for (needle in listOf("口令导入", "节次", "30000", "500", "一条都没有写入")) {
            assertTrue("拒绝文案缺「$needle」：$copy", copy.contains(needle))
        }
        // 文件路那一句必须报的是另一路的名字（两路同级，但不许说错哪一路动的手）
        assertTrue(
            restoreVolumeRejectCopy(
                RestoreVolumeDecision.Reject(RestoreEntry.BackupFile, OverCapacityTable.Courses, 2_001, 2_000),
            ).contains("备份恢复")
        )
    }

    @Test
    fun `确认那一句把条数与不可撤销都念出来`() {
        val copy = restoreShareConfirmCopy(
            RestoreVolumeDecision.ConfirmFirst(RestoreEntry.ShareCode, 30, 200, UndoSnapshotNeed.Necessary),
        )
        for (needle in listOf("200 条节次", "整张替换", "30", "撤销不了")) {
            assertTrue("确认文案缺「$needle」：$copy", copy.contains(needle))
        }
        // 不动节次表那一等没有这一问（decide 不会交回 ConfirmFirst），
        // 但快照为 None 时"撤销不了"那一句必须跟着退掉 —— 不许念一句比判据更宽的承诺
        val noSnapshot = restoreShareConfirmCopy(
            RestoreVolumeDecision.ConfirmFirst(RestoreEntry.ShareCode, 30, 200, UndoSnapshotNeed.None),
        )
        assertFalse("快照不需要时不许谎称撤销不了：$noSnapshot", noSnapshot.contains("撤销不了"))
        assertTrue(restoreShareConfirmTitle().contains("节次表"))
    }

    @Test
    fun `换表那一手的留痕文案念得出条数与快照档`() {
        val copy = restoreSlotReplacementLogCopy("口令导入", 200, UndoSnapshotNeed.Necessary)
        for (needle in listOf("口令导入", "整张替换为 200 条", "必要但没有")) {
            assertTrue("留痕文案缺「$needle」：$copy", copy.contains(needle))
        }
        assertTrue(restoreSlotReplacementLogCopy("备份恢复", 0, UndoSnapshotNeed.None).contains("不需要"))
    }
}
