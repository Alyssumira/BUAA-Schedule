package com.buaa.schedule.ui.home

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T250 判据内核：冲突向导那颗「只改这些周」按下去以后，**「写完之后的收尾」该由哪一枚协程接收者跑**
 * 这一判（纯 JVM，零 android import、零体内读钟）。
 *
 * 卡面第 0 问的定性就在这一族形状里（生产接线本身由
 * [ConflictWizardSettlementReceiverWiringGuardTest] 按形状钉住，本内核只钉「谁跑收尾」这一判）：
 * - 写库那一手**从来不在**行组合 scope 里 —— [applyConflictShift] 把它放在
 *   `viewModel.viewModelScope` 的那枚 job 上，行的 dispose 顶不掉它 ⇒ 库里照样写成功；
 * - 长在行组合 scope 上的是**等那枚 job 的 `join()` + 记 `onShiftEnd` 的账** ⇒ 行一滚出 LazyColumn
 *   视口就被 dispose，这一手连同那个等待点一起取消，`onShiftEnd` 一行都不执行：
 *   `pendingCourses` 永不复位（按钮永久「写入中…」）、`shiftedCourses` 永不记账
 *   （于是「重复位移保护」形同虚设，连点把同一门课越挪越远）。
 *
 * 本内核把两枚接收者各复刻一遍，走同一档事件顺序（点火 → 写入挂起 → 行 dispose → 写入完成），
 * 量出两种症状的分界。**不证明** SQLite 上真写一行（零设备红线，那一档归编排者）。
 */
class ConflictWizardSettlementReceiverPolicyTest {

    /** 写库那一手的替身：`updateCourse` 挂在一道闸上，测试自己决定它什么时候跑完。 */
    private class FakeDb(private val gate: CompletableDeferred<Unit>) {
        /** 已经点火（进了协程体、还没写完）的笔数 */
        var started = 0
            private set

        /** 真落库的笔数（两笔 = 同一门课被挪走两次 = 越挪越远） */
        val writes = mutableListOf<Int>()

        suspend fun updateCourse(): Boolean {
            started++
            gate.await()
            writes += writes.size + 1
            return true
        }
    }

    /** 弹窗层那两枚账的替身：`pendingCourses` = 正在写哪几门、`shiftedCourses` = 已应用过位移。 */
    private class Ledger {
        var pending: Set<String> = emptySet()
            private set
        var shifted: Set<String> = emptySet()
            private set

        fun start(id: String) {
            pending = pending + id
        }

        fun end(id: String, saved: Boolean) {
            pending = pending - id
            if (saved) shifted = shifted + id
        }
    }

    /** ③ 的两档：只换收尾那枚接收者，其余事件顺序一模一样。 */
    private class Case(val label: String, val diseased: Boolean, val wantWrites: Int)

    // ---- ① 定性：写库不在行组合 scope 上，dispose 顶不掉它；被吞掉的只有收尾 ----

    @Test
    fun 行组合scope被dispose时写库照样落库而收尾一行都不执行() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val db = FakeDb(gate)
        val vmScope = newScope()
        val rowScope = newScope()
        val ledger = Ledger()

        click(settlementReceiver = rowScope, vmScope = vmScope, db = db, ledger = ledger)

        assertEquals("点火那一刻 pending 就要立起（按钮立刻变「写入中…」）", setOf(COURSE_KEY), ledger.pending)
        assertEquals("写库那一手此刻已经长在 vmScope 的那枚 job 里", 1, db.started)

        rowScope.cancel()
        gate.complete(Unit)

        assertEquals(
            "写库那一手不随行的 dispose 取消 ⇒ 库里确实落了这一笔（第 0 问：那一手里跑的不是写库）",
            listOf(1),
            db.writes,
        )
        assertEquals(
            "被吞掉的只有收尾 ⇒ pending 永不复位，按钮永久「写入中…」；这与「库里已经写了」是同一件事的两面",
            setOf(COURSE_KEY),
            ledger.pending,
        )
        assertEquals("shifted 也没记上账 ⇒ 那句「重复位移保护」形同虚设", emptySet<String>(), ledger.shifted)

        vmScope.cancel()
    }

    // ---- ② 修好的那一手：同一档 dispose，收尾照样落地 ----

    @Test
    fun 收尾搬到viewModelScope之后同一档dispose不再吞掉记账() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val db = FakeDb(gate)
        val vmScope = newScope()
        val rowScope = newScope()
        val ledger = Ledger()

        click(settlementReceiver = vmScope, vmScope = vmScope, db = db, ledger = ledger)
        rowScope.cancel()
        gate.complete(Unit)

        assertEquals("写库一笔", listOf(1), db.writes)
        assertEquals(
            "收尾跑到了 ⇒「正在写哪几门」复位，按钮从「写入中…」交回用户（或标成「已应用」）",
            emptySet<String>(),
            ledger.pending,
        )
        assertEquals("「已应用过位移」记上了账", setOf(COURSE_KEY), ledger.shifted)

        vmScope.cancel()
    }

    // ---- ③ 两种症状的分界落在「连点第二下还有没有建议」上 ----

    /**
     * 同一档事件（点火 → 行滚出视口 → 写入完成 → 行回到视口再点一下），只换收尾那枚接收者：
     * 病形状第二下照样给出建议、照样写第二笔（越挪越远）；修好的形状第二次根本没有建议（只落一笔）。
     *
     * 建议侧那一判逐字复刻生产：`if (applied) null else 建议`，applied = 这门课在不在
     * `shiftedCourses` 里 ⇒ 「账记没记上」与「有没有第二次位移」被并成一判。
     */
    @Test
    fun 重复位移保护只在收尾记账成立那一档挡得住第二次点击() = runBlocking {
        val cases = listOf(
            Case("收尾挂在行组合 scope（T250 之前的病形状）", diseased = true, wantWrites = 2),
            Case("收尾挂在 viewModelScope（T250① 的修法）", diseased = false, wantWrites = 1),
        )
        for (case in cases) {
            val gate = CompletableDeferred<Unit>()
            val db = FakeDb(gate)
            val vmScope = newScope()
            val ledger = Ledger()

            check(suggestionOf(ledger) != null) { case.label + "：第一次本该有建议" }
            val firstRowScope = newScope()
            click(
                settlementReceiver = if (case.diseased) firstRowScope else vmScope,
                vmScope = vmScope,
                db = db,
                ledger = ledger,
            )
            firstRowScope.cancel()
            gate.complete(Unit)

            // 行滚回视口 = 一次全新的组合、一枚全新的行组合 scope；弹窗层那两枚账跟着读回来
            val secondRowScope = newScope()
            if (suggestionOf(ledger) != null) {
                click(
                    settlementReceiver = if (case.diseased) secondRowScope else vmScope,
                    vmScope = vmScope,
                    db = db,
                    ledger = ledger,
                )
            }
            assertEquals(
                case.label + "：收尾的账记不上时，「已应用过位移」永远为假 ⇒ 第二次点击照旧有建议、" +
                    "照旧再挪一次（连点把同一门课越挪越远）；收尾记账成立那一档第二次根本没有建议",
                case.wantWrites,
                db.writes.size,
            )

            vmScope.cancel()
            firstRowScope.cancel()
            secondRowScope.cancel()
        }
    }

    // ---------------- 复刻生产形状的那几手 ----------------

    /**
     * 复刻 [applyConflictShift]：写库那一手在 `viewModel.viewModelScope` 的 job 里，
     * `job.join()` 与结论都排在**调用者**那一手里（join 只等完成、不传播取消）。
     */
    private suspend fun replicaApplyShift(vmScope: CoroutineScope, db: FakeDb): Boolean {
        var saved = false
        val job = vmScope.launch { saved = db.updateCourse() }
        job.join()
        return saved
    }

    /**
     * 复刻那一手点击：先同步把 pending 立起（按钮当场变灰），再把「等结论 + 记 `onShiftEnd` 的账」
     * 挂在哪一枚接收者上由参数决定 —— 本卡的病与修法都落在这一枚参数上。
     */
    private fun click(
        settlementReceiver: CoroutineScope,
        vmScope: CoroutineScope,
        db: FakeDb,
        ledger: Ledger,
    ) {
        ledger.start(COURSE_KEY)
        settlementReceiver.launch {
            ledger.end(COURSE_KEY, replicaApplyShift(vmScope, db))
        }
    }

    /** 复刻建议侧那一判：已应用过位移 = 没有建议（这就是「重复位移保护」）。 */
    private fun suggestionOf(ledger: Ledger): List<Int>? =
        if (COURSE_KEY in ledger.shifted) null else SUGGESTED_PERIODS

    /** Unconfined + SupervisorJob：事件顺序完全由测试递闸的那一手决定，体内不读钟。 */
    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private companion object {
        const val COURSE_KEY = "42"
        val SUGGESTED_PERIODS = listOf(3, 4)
    }
}
