package com.buaa.schedule.widget

import com.buaa.schedule.widget.ColdStartRebuild.AlarmHead
import com.buaa.schedule.widget.ColdStartRebuild.Decision
import com.buaa.schedule.widget.ColdStartRebuild.Fingerprint
import com.buaa.schedule.widget.ColdStartRebuild.Outcome
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 冷启动那条后台重建链的"三把钥匙"（审计 §2.1 P1-①剩余 + §2.2 P1-②）。
 *
 * 改的是**触发条件**：原先每一次冷进程启动都无条件跑整套重建（约 `7+2N` 次 Room 查询、
 * 多次跨 binder 探测、N 份全量课程 JSON 编码、两条闹钟重排），而绝大多数冷启动是
 * "某条闹钟把进程从零拉起来投递广播"，那一刻课表一个字都没变。
 * 现在三把钥匙都说"无事可做"才跳过，任何一把不确定就照旧全跑。
 *
 * 为什么测 [ColdStartRebuild.run] 这个不接 Context 的编排入口：本模块单测没有 Robolectric
 * （android.jar 里全是抛 "not mocked" 的桩），真跑一次这条链要设备，而这张卡的风险面
 * 恰好只有三件可以数出来的事 —— **整链被跑了几遍**、**指纹在什么条件下才被写**、
 * **钥匙 2 按人群探的是哪一头**。形状抄 `ColdStartWidgetStepsTest` 与
 * `ClassProgressCleanupDecisionTest`（同一手法，同一理由）。
 *
 * 生产接线（`BUAAApplication` 真的把整链交给了这道闸门）JVM 跑不到，
 * 由 `ColdStartRebuildWiringTest` 按源码形状核对。
 */
class ColdStartRebuildTest {

    private val hour = 60L * 60L * 1000L
    private val versionCode = 42L
    private val now = 1_760_000_000_000L // 任何固定时刻都行：判据不许自己读钟

    // ---- 执行记录：整链跑了几遍、探针被问了什么、指纹写了什么 ----------------

    private class Trace {
        val ran = mutableListOf<String>()
        val written = mutableListOf<Fingerprint>()
        val askedHeads = mutableListOf<AlarmHead?>()
        val reportedFailures = mutableListOf<String>()
        val decisions = mutableListOf<Decision>()
        var chainRuns = 0
        var probeRuns = 0
    }

    /**
     * 跑一次闸门。
     *
     * @param failing 让哪一处抛（`chain` = 重建整链、`widgets` = 组件四步、
     *   `probe` = 钥匙 2 的探测、`read` = 读指纹、`write` = 写指纹）
     * @param reportsFailure 重建整链**不抛**、只通过留痕口报上来的一步失败
     *   —— 那是各步自己 `runCatching` 吞掉异常的那些路径
     */
    private fun runGate(
        trace: Trace,
        stored: Fingerprint?,
        armed: Boolean = true,
        head: AlarmHead? = AlarmHead.PreClassReminder,
        chainResult: AlarmHead? = AlarmHead.PreClassReminder,
        failing: String? = null,
        reportsFailure: String? = null,
        currentVersionCode: Long = versionCode,
        nowMillis: Long = now,
    ): Outcome = runBlocking {
        ColdStartRebuild.run(
            currentVersionCode = currentVersionCode,
            nowMillis = nowMillis,
            readFingerprint = {
                trace.ran += "read"
                if (failing == "read") throw IllegalStateException("prefs 读不动")
                stored
            },
            writeFingerprint = {
                trace.ran += "write"
                if (failing == "write") throw IllegalStateException("prefs 写不动")
                trace.written += it
            },
            alarmsStillArmed = { asked ->
                trace.ran += "probe"
                trace.probeRuns += 1
                trace.askedHeads += asked
                if (failing == "probe") throw IllegalStateException("getBroadcast 抛了")
                armed
            },
            rebuildRemindersAndBells = { report ->
                trace.ran += "chain"
                trace.chainRuns += 1
                if (failing == "chain") throw IllegalStateException("整链炸了")
                reportsFailure?.let { report(it, IllegalStateException("$it 这一步抛了")) }
                if (failing == "head") null else chainResult
            },
            runWidgetSteps = {
                trace.ran += "widgets"
                if (failing == "widgets") throw IllegalStateException("组件四步炸了")
            },
            reportStepFailure = { label, _ -> trace.reportedFailures += label },
            logDecision = { trace.decisions += it },
        )
    }

    /** 三把钥匙里"时间"与"版本"两格都绿时的那份指纹 */
    private fun fresh(head: AlarmHead? = AlarmHead.PreClassReminder, ageMillis: Long = 5 * hour) =
        Fingerprint(versionCode = versionCode, succeededAt = now - ageMillis, head = head)

    // ---- 钥匙 1：versionCode -------------------------------------------------

    /**
     * 正例：升级会清掉已注册闹钟 —— versionCode 变了就必须重跑。
     * 这才是 `BUAAApplication.kt:33` 那句注释（"应用启动/升级"）原本的意图。
     */
    @Test
    fun `versionCode 变了就整链重跑`() {
        val trace = Trace()

        val outcome = runGate(trace, stored = fresh(), armed = true, currentVersionCode = versionCode + 1)

        assertEquals(1, trace.chainRuns)
        assertEquals(Outcome.Rebuilt, outcome)
        assertTrue("结论里没交代是 versionCode 触发的：${trace.decisions}",
            trace.decisions.single().reason.contains("versionCode"))
        // 重跑之后新指纹记的是**当前** versionCode，下一次冷启动就不会再报升级
        assertEquals(versionCode + 1, trace.written.single().versionCode)
    }

    /** 负例：versionCode 没变、另两把钥匙也放行 —— 这时才允许跳过 */
    @Test
    fun `versionCode 没变且另两把钥匙放行时跳过`() {
        val trace = Trace()

        val outcome = runGate(trace, stored = fresh(), armed = true)

        assertEquals("三把钥匙都说无事可做，整链却被跑了一遍", 0, trace.chainRuns)
        assertEquals(Outcome.Skipped, outcome)
        assertFalse(trace.decisions.single().rebuild)
    }

    // ---- 钥匙 2：我们自己的闹钟确实还挂着 ------------------------------------

    /** 正例：闹钟不在了（被 ROM 吞了 / 从没排上）就必须重跑，且探的是上一次记下的那一头 */
    @Test
    fun `闹钟不在就整链重跑`() {
        val trace = Trace()

        val outcome = runGate(trace, stored = fresh(head = AlarmHead.ClassBells), armed = false)

        assertEquals(1, trace.chainRuns)
        assertEquals("钥匙 2 探的对象不是上次排上的那一头", listOf(AlarmHead.ClassBells), trace.askedHeads)
        assertTrue(trace.decisions.single().reason.contains("闹钟"))
        assertEquals(Outcome.Rebuilt, outcome)
    }

    /** 负例：闹钟还挂着 ⇒ 这一头放行；时间格也放行 ⇒ 只有 `head` 这一格决定成败 */
    @Test
    fun `闹钟还挂着时这一头放行`() {
        val trace = Trace()

        runGate(trace, stored = fresh(), armed = true)

        assertEquals(0, trace.chainRuns)
    }

    /**
     * 只有"闹钟还在不在排"这一把钥匙说不行时也照样重跑：
     * 版本没变、时间刚过一小时、闹钟不在 ⇒ 仍须整链重跑（防的是把 AND 写成 OR）。
     */
    @Test
    fun `只有闹钟这一把钥匙说不行时也照样重跑`() {
        val trace = Trace()

        val outcome = runGate(trace, stored = fresh(ageMillis = 1 * hour), armed = false)

        assertEquals(1, trace.chainRuns)
        assertEquals(Outcome.Rebuilt, outcome)
        assertEquals("干净跑完就该写指纹，否则下一次还要白跑", 1, trace.written.size)
        assertTrue(trace.reportedFailures.isEmpty())
    }

    // ---- 钥匙 3：24 小时硬上限 -----------------------------------------------

    /** 正例：距上次成功超过 24h 就无条件重跑，连前两条都不判（探针一次都不该被问） */
    @Test
    fun `超过 24 小时无条件重跑而且不再花 binder 问闹钟`() {
        val trace = Trace()

        runGate(trace, stored = fresh(ageMillis = 25 * hour), armed = false)

        assertEquals(1, trace.chainRuns)
        assertEquals("钥匙 3 已经开口了还去探测闹钟，白付 binder", 0, trace.probeRuns)
        assertTrue(trace.decisions.single().reason.contains("24"))
    }

    /** 边界：正好 24h 触发、差 1ms 不触发 —— 跳过的硬上限是个可以数的数，不是"感觉久了" */
    @Test
    fun `24 小时这条边界按毫秒判定`() {
        assertEquals(24L * 60L * 60L * 1000L, ColdStartRebuild.RERUN_AFTER_MILLIS)
        assertEquals(24.hours.inWholeMilliseconds, ColdStartRebuild.RERUN_AFTER_MILLIS)

        val at = Trace()
        runGate(at, stored = fresh(head = AlarmHead.ClassBells, ageMillis = ColdStartRebuild.RERUN_AFTER_MILLIS), armed = true)
        assertEquals("到点必须重跑", 1, at.chainRuns)

        val justBefore = Trace()
        runGate(
            justBefore,
            stored = fresh(head = AlarmHead.ClassBells, ageMillis = ColdStartRebuild.RERUN_AFTER_MILLIS - 1),
            armed = true,
        )
        assertEquals("还没到点，另两把钥匙也放行 ⇒ 跳过", 0, justBefore.chainRuns)
    }

    /** 从没成功跑完过（首次安装 / 数据被清 / 上一轮抛了）：走钥匙 3 那一格，同样不花探测的钱 */
    @Test
    fun `没有成功记录时直接重跑`() {
        val trace = Trace()

        runGate(trace, stored = null)

        assertEquals(1, trace.chainRuns)
        assertEquals(0, trace.probeRuns)
        assertTrue(trace.decisions.single().reason.contains("无成功记录"))
    }

    /** 时钟回摆（用户改系统时间、时区变化）：时间戳已经不可信，按"该跑"处理 */
    @Test
    fun `时钟回摆时按该跑处理`() {
        val trace = Trace()

        runGate(trace, stored = Fingerprint(versionCode, now + 10 * hour, AlarmHead.PreClassReminder), armed = true, nowMillis = now)

        assertEquals(1, trace.chainRuns)
        assertTrue(trace.decisions.single().reason.contains("回摆"))
    }

    // ---- 指纹：只有整链干净跑完才写 -------------------------------------------

    /** 跳过的那一轮**不许**刷新时间戳 —— 否则 24h 这个硬上限永远到不了 */
    @Test
    fun `跳过时不写指纹`() {
        val trace = Trace()

        runGate(trace, stored = fresh(), armed = true)

        assertTrue("跳过的一轮把时间戳续上了，24h 上限就成了摆设：${trace.written}", trace.written.isEmpty())
    }

    /** 整链干净跑完才写：versionCode、这一轮的时间、这一轮排上的那当头闹钟 */
    @Test
    fun `整链干净跑完时写下指纹`() {
        val trace = Trace()

        val outcome = runGate(trace, stored = null, chainResult = AlarmHead.ClassBells)

        assertEquals(Outcome.Rebuilt, outcome)
        assertEquals(listOf(Fingerprint(versionCode, now, AlarmHead.ClassBells)), trace.written)
    }

    /** 重建链里任一步抛了：后面的步骤照跑（不半途而废），但不写时间戳 */
    @Test
    fun `整链里任一步抛了就不写时间戳`() {
        for (failing in listOf("chain", "widgets")) {
            val trace = Trace()

            val outcome = runGate(trace, stored = null, failing = failing)

            assertEquals("$failing 抛了却没记下来", Outcome.RebuiltWithFailures, outcome)
            assertTrue("$failing 抛了却写了时间戳：下一次冷启动会被误判成无事可做", trace.written.isEmpty())
            assertEquals("重建整链仍然被跑了一遍", 1, trace.chainRuns)
        }
    }

    /** 各步自己吞异常的那些路径：通过留痕口报上来同样算"这轮没成功" */
    @Test
    fun `步骤通过留痕口报失败时不写时间戳`() {
        val trace = Trace()

        val outcome = runGate(trace, stored = null, reportsFailure = "rescheduleReminders")

        assertEquals(Outcome.RebuiltWithFailures, outcome)
        assertTrue(trace.written.isEmpty())
        assertEquals(listOf("rescheduleReminders"), trace.reportedFailures)
    }

    /** 重建链报不出这一轮排上了哪当头（返回 null）：不能凭猜测写指纹 */
    @Test
    fun `拿不到本轮排上的那当头时不写指纹`() {
        val trace = Trace()

        val outcome = runGate(trace, stored = null, failing = "head")

        assertEquals(Outcome.RebuiltWithFailures, outcome)
        assertTrue(trace.written.isEmpty())
        assertEquals(1, trace.chainRuns)
    }

    /** 写指纹自己抛了不许把整条链炸掉（这条链跑在没有 CoroutineExceptionHandler 的 SupervisorJob 里） */
    @Test
    fun `写指纹抛异常不炸链路`() {
        val trace = Trace()

        val outcome = runGate(trace, stored = null, failing = "write")

        assertEquals(Outcome.Rebuilt, outcome)
        assertEquals(listOf("writeRebuildFingerprint"), trace.reportedFailures)
        assertNull("指纹没写下去", trace.written.firstOrNull())
    }

    /** 读指纹抛了按"没有成功记录"处理 = 重跑，而不是跳过 */
    @Test
    fun `读指纹抛异常时按重跑处理`() {
        val trace = Trace()

        runGate(trace, stored = fresh(), failing = "read")

        assertEquals(1, trace.chainRuns)
        assertEquals(0, trace.probeRuns)
    }

    /** 钥匙 2 的探测抛了按"不在"处理：方向只能是多跑一次，绝不许反过来当成"还在排" */
    @Test
    fun `探测抛异常时按闹钟不在处理`() {
        val trace = Trace()

        val outcome = runGate(trace, stored = fresh(), failing = "probe")

        assertEquals(1, trace.chainRuns)
        assertEquals(Outcome.RebuiltWithFailures, outcome)
        assertTrue("探测失败却没留痕，下次复盘看不到为什么跳不过去",
            trace.reportedFailures.contains("probeRebuildAlarms"))
    }

    // ---- 步骤与顺序 ----------------------------------------------------------

    /** 顺序与改动前逐条一致：读指纹 → 闹钟探测 → 提醒链 → 组件四步 → 写指纹 */
    @Test
    fun `整链的步骤顺序一条都没挪`() {
        val trace = Trace()

        runGate(trace, stored = fresh(head = AlarmHead.ClassBells), armed = false)

        assertEquals("read,probe,chain,widgets,write", trace.ran.joinToString(","))
    }

    // ---- 钥匙 2 的人群口径 ---------------------------------------------------

    /** 日历模式：应用内一个闹钟都不排 ⇒ 永远判"不在" ⇒ 永远重跑，与改动前逐字一致（不许为这类用户开例外） */
    @Test
    fun `日历模式永远判不在`() {
        var reminderProbes = 0
        var bellProbes = 0

        val armed = ColdStartRebuild.alarmsStillArmed(
            usesInAppReminders = false,
            head = AlarmHead.PreClassReminder,
            reminderAlarmArmed = { reminderProbes++; true },
            classBellsArmed = { bellProbes++; true },
        )

        assertFalse(armed)
        assertEquals(0, reminderProbes)
        assertEquals(0, bellProbes)
    }

    /** 开着课前提醒的人：探课前提醒那一头，课堂铃那一头一次都不问 */
    @Test
    fun `课前提醒人群探的是课前提醒闹钟`() {
        var reminderProbes = 0
        var bellProbes = 0

        assertTrue(
            ColdStartRebuild.alarmsStillArmed(
                usesInAppReminders = true,
                head = AlarmHead.PreClassReminder,
                reminderAlarmArmed = { reminderProbes++; true },
                classBellsArmed = { bellProbes++; false },
            ),
        )
        assertEquals(1, reminderProbes)
        assertEquals("两头都问就是白付一次 binder", 0, bellProbes)
        assertFalse(
            "课前提醒人群：那一头不在就是不在，不许拿课堂铃顶数",
            ColdStartRebuild.alarmsStillArmed(
                usesInAppReminders = true,
                head = AlarmHead.PreClassReminder,
                reminderAlarmArmed = { false },
                classBellsArmed = { true },
            ),
        )
    }

    /**
     * 课前提醒全关的人：只剩上/下课铃那条链，所以**必须**探到课堂铃在排才算数 ——
     * 这类人没有更短的自愈入口（兜底 Worker 是 12 小时），不接受"另一头还挂着"当替代。
     */
    @Test
    fun `提醒全关人群必须探到课堂铃`() {
        var reminderProbes = 0

        assertTrue(
            ColdStartRebuild.alarmsStillArmed(
                usesInAppReminders = true,
                head = AlarmHead.ClassBells,
                reminderAlarmArmed = { reminderProbes++; true },
                classBellsArmed = { true },
            ),
        )
        assertEquals(0, reminderProbes)
        assertFalse(
            "课堂铃不在排还放行",
            ColdStartRebuild.alarmsStillArmed(
                usesInAppReminders = true,
                head = AlarmHead.ClassBells,
                reminderAlarmArmed = { true },
                classBellsArmed = { false },
            ),
        )
    }

    /** 上一次什么都没排上（提醒全关 + 两个课堂开关都关）：没有正向证据就不许跳过 */
    @Test
    fun `没有闹钟头可探时永远判不在`() {
        var probes = 0

        assertFalse(
            ColdStartRebuild.alarmsStillArmed(
                usesInAppReminders = true,
                head = null,
                reminderAlarmArmed = { probes++; true },
                classBellsArmed = { probes++; true },
            ),
        )
        assertEquals(0, probes)
    }

    // ---- 三把钥匙的与关系 -----------------------------------------------------

    /**
     * 2×2×2 全组合的期望值表（每一格的期望都是手写的，不是从判据里推出来的）：
     * 只要有一把钥匙说"该跑"就该跑，全部放行才可跳过。
     */
    @Test
    fun `三把钥匙是与关系不是或关系`() {
        val freshStamp = fresh()
        val staleStamp = fresh(ageMillis = 25 * hour)
        val otherVersion = Fingerprint(versionCode - 1, now - 1 * hour, AlarmHead.PreClassReminder)

        val expectations = listOf(
            Triple(null, true, true),
            Triple(null, false, true),
            Triple(freshStamp, true, false),
            Triple(freshStamp, false, true),
            Triple(staleStamp, true, true),
            Triple(staleStamp, false, true),
            Triple(otherVersion, true, true),
            Triple(otherVersion, false, true),
        )
        for ((stamp, armed, expectRebuild) in expectations) {
            val trace = Trace()

            val outcome = runGate(trace, stored = stamp, armed = armed)

            assertEquals(
                "指纹=${stamp?.succeededAt} version=${stamp?.versionCode} 闹钟在排=$armed",
                expectRebuild,
                outcome != Outcome.Skipped,
            )
            assertEquals(if (expectRebuild) 1 else 0, trace.chainRuns)
        }
    }
}
