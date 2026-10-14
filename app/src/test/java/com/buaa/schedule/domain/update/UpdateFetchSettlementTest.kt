package com.buaa.schedule.domain.update

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T365②：`UpdateFetchSettlement` 的表驱动逐格账（纯 JVM，无 Robolectric、无设备）。
 *
 * ## 表的形状
 *
 * 卡面要的那张表是「异常种类 × 是否落盘 × UI 档位」。这里逐枚格子交回的是五列：
 * 归档（[UpdateFetchFate]）、有没有失败原因（`reason`，它就是 `present` 那把尺上唯一认得出
 * 失败的一格）、落不落盘（`persistsRun`）、内存摆哪一档（[UpdateRestingTier]）、
 * 取消要不要原样交回上层（`rethrowsCancellation`）。后三列是同一次收场的三件事，
 * 原盘面上被一枚「有没有异常」压成了一件，于是取消那一手同时做错三件。
 *
 * ## 钉的是哪几格（每格点名它拦的是哪一形复发）
 *
 * - **取消那一行三格全反**（本卡题眼）：`persistsRun` 必须假、`restingTier` 必须是 RestInIdle、
 *   `rethrowsCancellation` 必须真。把取消折进失败那一档 = 三列同时红（那就是改前的形状）；
 *   只把 `persistsRun` 写朝宽（N 臂走的就是这一格）= 一次取消把上一次真检查的时刻顶掉、
 *   还在盘上落一枚从没发生过的失败档；不收回 Idle = 「正在检查」永挂，之后每一次检查
 *   都在调用点那道同状态闸门上原样返回 ⇒ 设置页那颗按钮静默 no-op。
 * - **取消那一行不许有 reason**：reason 非 null 就等于喂给 `present` 一枚 CheckFailed，
 *   也就是把「用户走了」念成「检查更新失败」。这一格与上一格互为两侧：
 *   有人为了"让取消也有反馈"给取消编一句措辞，本格当场红。
 * - **真失败那一行不许被改窄成"不落盘"**：失败要落 `KEY_FAILURE_AT` 与失败档，这是 T261 第 2 处
 *   静默的修法（进程死后设置页那一行还要能念出"那一次成没成"），把 `persistsRun` 顺手写成假
 *   就把那一处静默放回来了 —— 所以这一列在两档上都得钉着，不是只钉取消那一档。
 * - **`is` 判据的连带与排除**：`CancellationException` 的**子类**（盘面上就是 JobCancellationException
 *   那一族）必须一起算取消；它的**父类** IllegalStateException 必须算失败。两枚格子互为两侧 ——
 *   把判据写成 `error::class == CancellationException::class` 红子类那格，写成
 *   `is IllegalStateException` 红父类那格。
 * - **措辞函数只在真失败那一档被调用**：取消那一手不许调它（否则等于替一次没发生的失败编话），
 *   拿到答复那一手也不许调。计数格钉的是"调用枚数 == 1"，不是"调用过"。
 *
 * ## 最后一枚是前提取证，不是判据
 *
 * `真实的协程取消确实落进 runCatching 的失败档` 那一格量的是本卡病灶的**成因**：把 `withContext`
 * 里的挂起体取消掉，标准库 runCatching 兜住它，**兜住之后同步代码照样跑完**（原盘面上落盘那一段
 * 就在同步段里，一次都不会跳过去）。这一格不判内核，它判的是"内核拿得到一枚取消"这件事在 JVM 上
 * 真的成立 —— 拿不到的话本卡整枚靶子就是空转，所以它得一直红着有人才能放心改。
 *
 * ## 纯度
 *
 * 本文件只喂内核异常种类与一枚措辞 lambda；内核体内不读钟、不读区、不读权限、不读
 * `Build.VERSION`、不读偏好（`UpdateCheckSilentBranchGuardTest` 对判据文件钉的就是这一条口径，
 * 本卡的判据文件是同目录另一枚，由 ③ 那枚守卫按同一个口径数一遍）。
 */
class UpdateFetchSettlementTest {

    private data class Cell(
        val label: String,
        val error: Throwable?,
        val expectedFate: UpdateFetchFate,
        val persistsRun: Boolean,
        val restingTier: UpdateRestingTier,
        val rethrows: Boolean,
        /** null = 这一档不许有原因（reason 必须是 null）；非 null = 必须是措辞函数交回的那一句 */
        val expectedReason: String?,
    )

    private class FakeJobCancellation(message: String) : CancellationException(message)

    private companion object {
        const val READABLE = "连接超时"
        const val RAW = "HTTP 500"
    }

    @Test
    fun `异常种类乘是否落盘乘UI档位 的逐格账`() {
        val cells = listOf(
            // ── 拿到答复：落盘、档位交给 present、不复投、不许有原因 ──
            Cell(
                "没异常（查到了，哪怕列表是空的）", null,
                UpdateFetchFate.Answered, persistsRun = true,
                restingTier = UpdateRestingTier.LeaveToPresentation, rethrows = false,
                expectedReason = null,
            ),
            // ── 取消：三格全反，且不许有失败原因 ──
            Cell(
                "裸 CancellationException（无 message）", CancellationException(),
                UpdateFetchFate.Cancelled, persistsRun = false,
                restingTier = UpdateRestingTier.RestInIdle, rethrows = true,
                expectedReason = null,
            ),
            Cell(
                "带 message 的取消（kotlinx 交回的那种）", CancellationException("Job was cancelled normally"),
                UpdateFetchFate.Cancelled, persistsRun = false,
                restingTier = UpdateRestingTier.RestInIdle, rethrows = true,
                expectedReason = null,
            ),
            Cell(
                "取消的子类 JobCancellationException 那一族", FakeJobCancellation("This job has not completed yet"),
                UpdateFetchFate.Cancelled, persistsRun = false,
                restingTier = UpdateRestingTier.RestInIdle, rethrows = true,
                expectedReason = null,
            ),
            // ── 真失败：落盘、档位交给 present（由它交回 CheckFailed）、不复投、必须有原因 ──
            Cell(
                "IOException 一句原文", IOException(RAW),
                UpdateFetchFate.Failed, persistsRun = true,
                restingTier = UpdateRestingTier.LeaveToPresentation, rethrows = false,
                expectedReason = RAW,
            ),
            Cell(
                "SocketTimeoutException（readable 有专门措辞的那一档）", SocketTimeoutException(RAW),
                UpdateFetchFate.Failed, persistsRun = true,
                restingTier = UpdateRestingTier.LeaveToPresentation, rethrows = false,
                expectedReason = READABLE,
            ),
            // ── CancellationException 的父类：不是取消，必须算失败（这一格与子类那格互为两侧）──
            Cell(
                "IllegalStateException（取消的父类，不许顺手算成取消）", IllegalStateException("boom"),
                UpdateFetchFate.Failed, persistsRun = true,
                restingTier = UpdateRestingTier.LeaveToPresentation, rethrows = false,
                expectedReason = "boom",
            ),
        )

        for ((index, cell) in cells.withIndex()) {
            val mark = "#${index + 1} ${cell.label}"
            val readableCalls = intArrayOf(0)
            val settlement = UpdateFetchSettlement.of(cell.error) { throwable ->
                readableCalls[0]++
                if (throwable is SocketTimeoutException) READABLE else throwable.message ?: "网络异常"
            }

            assertEquals(
                "$mark\n  输入异常种类：" + cell.error?.let { it::class.simpleName } +
                    "\n  实到归档：" + settlement.fate,
                cell.expectedFate,
                settlement.fate,
            )
            assertEquals(
                "$mark 的「落不落盘」——这一格朝宽就是取消把上一次的检查时刻与结果档顶掉（台账 #536 后半）",
                cell.persistsRun,
                settlement.persistsRun,
            )
            assertEquals(
                "$mark 的「UI 档位」——RestInIdle 之外都不许收回现场；收回现场这一格朝窄就是" +
                    "「正在检查」永挂、之后每一次检查都被同状态闸门折成 no-op",
                cell.restingTier,
                settlement.restingTier,
            )
            assertEquals(
                "$mark 的「要不要复投」——假了就等于把取消当正常返回，调用方的协程以「跑完了」收场",
                cell.rethrows,
                settlement.rethrowsCancellation,
            )
            assertEquals(
                "$mark 的失败原因——present 只认这一格；取消那一行读到非 null 就等于弹「检查更新失败」",
                cell.expectedReason,
                settlement.reason,
            )
            assertEquals(
                "$mark 的措辞函数调用枚数（只有真失败那一档才许替它编话）——实到 " + readableCalls[0] + " 次",
                if (cell.expectedFate == UpdateFetchFate.Failed) 1 else 0,
                readableCalls[0],
            )
        }
    }

    @Test
    fun `归档那一手与整表结论同源 同一枚异常两次问给出同一格`() {
        for (error in listOf(
            null,
            CancellationException(),
            FakeJobCancellation("This job has not completed yet"),
            IOException(RAW),
            IllegalStateException("boom"),
        )) {
            val mark = "输入异常种类：" + (error?.let { it::class.qualifiedName } ?: "无异常")
            val byFate = UpdateFetchSettlement.fateOf(error)
            val bySettlement = UpdateFetchSettlement.of(error) { it.message ?: "网络异常" }
            assertEquals(
                "$mark —— 归档那一手与整表那一手分着答话，就是本卡病灶的形状（两把尺各判一次）。" +
                    "实到：" + byFate + " ／ " + bySettlement.fate,
                byFate,
                bySettlement.fate,
            )
            assertEquals(
                "$mark —— 「落不落盘」只能由归档定：取消不落、其余都落（实到 " +
                    bySettlement.persistsRun + "，归档 " + byFate + "）",
                byFate != UpdateFetchFate.Cancelled,
                bySettlement.persistsRun,
            )
            assertEquals(
                "$mark —— 取消以外的一档都不许交回上层（实到 " + bySettlement.rethrowsCancellation + "）",
                byFate == UpdateFetchFate.Cancelled,
                bySettlement.rethrowsCancellation,
            )
        }
    }

    /**
     * 前提取证（不判内核，判的是病灶在 JVM 上真能复现）：
     * 挂起体被取消时 runCatching 兜得住，**兜住之后同步段照常跑完** —— 原盘面上
     * 落盘那一段（KEY_LAST_AT / KEY_FAILURE_AT / 失败档）全在同步段里，一次都不跳。
     */
    @Test
    fun `真实的协程取消确实落进 runCatching 的失败档`() {
        var swallowed: Throwable? = null
        var reachedThePersistSite = false
        runBlocking {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val running = scope.launch {
                val outcome = runCatching { withContext(Dispatchers.IO) { delay(30_000L) } }
                swallowed = outcome.exceptionOrNull()
                reachedThePersistSite = true
            }
            delay(60L)
            scope.cancel()
            running.join()
        }
        assertTrue(
            "取消被吞下来之后执行继续走进落盘那一段（这就是 :251 那一手在取消路径上到得到的取证）。" +
                "实到 reachedThePersistSite=" + reachedThePersistSite,
            reachedThePersistSite,
        )
        assertTrue(
            "吞下来的那枚必须是 CancellationException，内核与复投那一手才接得住它。实到：" +
                swallowed?.let { it::class.qualifiedName },
            swallowed is CancellationException,
        )
        assertEquals(
            "内核必须把这一次真取消判成 Cancelled 而不是 Failed。实到：" +
                UpdateFetchSettlement.fateOf(swallowed),
            UpdateFetchFate.Cancelled,
            UpdateFetchSettlement.fateOf(swallowed),
        )
        assertEquals(
            "这一次取消一个字都不许落盘（三格结论里的落盘那一格，读的是上面那枚真取消）。实到：" +
                UpdateFetchSettlement.of(swallowed) { it.message ?: "网络异常" }.persistsRun,
            false,
            UpdateFetchSettlement.of(swallowed) { it.message ?: "网络异常" }.persistsRun,
        )
    }
}
