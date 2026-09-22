package com.buaa.schedule.ui.signin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import zxingcpp.BarcodeReader

/**
 * T66 第二引擎（zxing-cpp 兜底）判据内核的表驱动单测。
 *
 * 判据本体在 [ScanSecondEnginePolicy] 里零 android import，这里也就零 android 依赖 ——
 * 这正是把它抽出来的全部理由：连击门槛、帧间隔、每轮额度、三次判死这四道节流全是
 * **帧数与计数**，必须在 JVM 里逐支打表，而不是只能在真机上举着手机看。
 *
 * 三条红线各有正面靶子：
 * - [FrameCodeRung.NothingDetected] **永不**触发（⑦：400 帧零发火，画面里没码时双解只是烧电）；
 * - 触发只认 [retryableRung] 两档，而且那本账与缩放账**不共用计数器**（⑧′）；
 * - 每一种停法都说得出话（⑤），措辞唯一来源是 [secondEngineStopText]。
 *
 * ⑪ 是全文件唯一碰真库的一条：宿主 JVM 里没有 `libzxingcpp_android.so`，
 * 于是"构造真解码器"必然抛 ⇒ 探针必须落到 Unusable。它盖住的正是本卡最贵的失效形状：
 * 缺库的设备上兜底不是"解不出"，而是"根本没上线"，而这一下必须由我们自己的 try 接住。
 */
class ScanSecondEnginePolicyTest {

    // ---- ① 触发档位：四档全表 ----

    @Test
    fun retryableRungTable() {
        val rows = listOf(
            // rung → 值不值得补一解
            FrameCodeRung.CodeReadable to false,      // 主力赢了，不补
            FrameCodeRung.NothingDetected to false,   // 红线：瞄不准不是解不开
            FrameCodeRung.CodeTooSmall to true,       // tryHarder + tryDownscale 的活
            FrameCodeRung.CodeUndecodable to true,    // tryInvert 的活
        )
        for ((rung, expected) in rows) {
            assertEquals("$rung：", expected, retryableRung(rung))
        }
        // 靶子：四档都在。数目变了就是这张表没跟着改，而"全表"两个字就不成立了
        assertEquals("FrameCodeRung 的档位数目变了：", 4, FrameCodeRung.entries.size)
    }

    // ---- ② 节流常数：数值与彼此的关系都不许漂 ----

    @Test
    fun throttleConstantsAreTheAdvertisedNumbers() {
        assertEquals("连击门槛漂了（≈200–300ms 的那笔账）：", 6L, SecondEngineStreakFrames)
        assertEquals("帧间隔漂了（单发最坏耗时的四倍余量）：", 15L, SecondEngineFrameGap)
        assertEquals("每轮额度漂了（8×15=120 帧的持续输出）：", 8, SecondEngineMaxFiresPerBind)
        assertEquals("判死的连击漂了（≈1.5–2 秒）：", 3, SecondEngineGiveUpAfterMisses)
        // 关系一：门槛小于间隔 —— 否则"刚攒够连击"必然撞上"间隔还没到"，第一发的时机没人说得清
        assertTrue("连击门槛不许大于等于帧间隔：", SecondEngineStreakFrames < SecondEngineFrameGap)
        // 关系二：判死先于额度 —— 否则「兜底在这台上根本没在起作用」这一档永远轮不到说话
        assertTrue("三次判死不许高于封顶：", SecondEngineGiveUpAfterMisses < SecondEngineMaxFiresPerBind)
        // 关系三：补解必须比降级文案先动（屏上档位的滞后窗是 RungSettleFrames 帧）
        assertTrue("兜底不许比降级文案还晚出声：", SecondEngineStreakFrames < RungSettleFrames)
    }

    // ---- ③ 发火判据：全表 ----

    private data class Row(
        val label: String,
        val fires: Int = 0,
        val misses: Int = 0,
        val lastFireFrame: Long = -1L,
        val unusableSeen: Boolean = false,
        val streak: Long = 0L,
        val frame: Long = 100L,
        val codeInHand: Boolean = false,
        val engineUsable: Boolean = true,
        val expected: SecondEngineDecision,
    )

    @Test
    fun decisionTable() {
        val rows = listOf(
            // 连击没到线：一帧都不补（这是绝大多数帧的出口）
            Row("连击 0", streak = 0, expected = SecondEngineDecision.Waiting),
            Row("连击差一帧", streak = SecondEngineStreakFrames - 1, expected = SecondEngineDecision.Waiting),
            Row("连击到线且本轮首发", streak = SecondEngineStreakFrames, expected = SecondEngineDecision.Fire),
            // 间隔：差一帧就还不发，恰好到线就发
            Row(
                "离上一发 14 帧", streak = 40, lastFireFrame = 86L, frame = 100L,
                expected = SecondEngineDecision.Waiting,
            ),
            Row(
                "离上一发恰好 15 帧", streak = 40, lastFireFrame = 85L, frame = 100L,
                expected = SecondEngineDecision.Fire,
            ),
            // 闸门里已有原文 / 结果卡正等用户按：补了也投不出去
            Row("闸门里有原文", streak = 20, codeInHand = true, expected = SecondEngineDecision.CodeInHand),
            // 额度与判死各差一发
            Row(
                "额度用完", fires = SecondEngineMaxFiresPerBind, streak = 40,
                expected = SecondEngineDecision.OutOfBudget,
            ),
            Row(
                "差一发额度", fires = SecondEngineMaxFiresPerBind - 1, streak = 40,
                expected = SecondEngineDecision.Fire,
            ),
            Row(
                "连续三次没解出", misses = SecondEngineGiveUpAfterMisses, streak = 99,
                expected = SecondEngineDecision.GaveUp,
            ),
            Row(
                "两次失手仍可再试", misses = SecondEngineGiveUpAfterMisses - 1, streak = 40,
                expected = SecondEngineDecision.Fire,
            ),
            // 引擎自身不可用的两个入口：账本里记着 / 探针当下就不可用
            Row("账本记着不可用", streak = 40, unusableSeen = true, expected = SecondEngineDecision.EngineUnusable),
            Row("探针当下不可用", streak = 40, engineUsable = false, expected = SecondEngineDecision.EngineUnusable),
            Row("没连击也没引擎", streak = 0, engineUsable = false, expected = SecondEngineDecision.EngineUnusable),
            // 帧号哨兵值与帧号倒退：都不许变成连发
            Row("哨兵值+连击不足", streak = 3, lastFireFrame = -1L, expected = SecondEngineDecision.Waiting),
            Row("帧号倒退", streak = 40, lastFireFrame = 200L, frame = 100L, expected = SecondEngineDecision.Waiting),
        )
        for (row in rows) {
            val ledger = SecondEngineLedger(
                fires = row.fires,
                misses = row.misses,
                lastFireFrame = row.lastFireFrame,
                unusableSeen = row.unusableSeen,
            )
            assertSame(
                "${row.label}（$ledger，streak=${row.streak} frame=${row.frame}）：",
                row.expected,
                secondEngineDecision(ledger, row.streak, row.frame, row.codeInHand, row.engineUsable),
            )
        }
        // 靶子：六档处境在这张表里都真的出现过（少一档=表在空转，改判据时没人提醒）
        assertEquals(
            "decisionTable 覆盖的处境档数变了（Waiting/Fire/CodeInHand/OutOfBudget/GaveUp/EngineUnusable）：",
            6,
            rows.map { it.expected }.distinct().size,
        )
    }

    /** ③′ 终局档的优先次序：三句成句的实话不许被连击/额度盖住 */
    @Test
    fun terminalModesOutrankStreakAndBudget() {
        val all = SecondEngineLedger(
            fires = SecondEngineMaxFiresPerBind,
            misses = SecondEngineGiveUpAfterMisses,
            lastFireFrame = 0L,
            unusableSeen = true,
        )
        // 三样同时成立时报最"终"的那一句：判死 > 引擎不在这台设备上 > 额度用完
        assertSame(
            SecondEngineDecision.GaveUp,
            secondEngineDecision(all, 999, 999, codeInHand = true, engineUsable = true),
        )
        assertSame(
            SecondEngineDecision.EngineUnusable,
            secondEngineDecision(all.copy(misses = 0), 999, 999, codeInHand = true, engineUsable = true),
        )
        assertSame(
            SecondEngineDecision.OutOfBudget,
            secondEngineDecision(all.copy(misses = 0, unusableSeen = false), 999, 999, codeInHand = true, engineUsable = true),
        )
        // 引擎不可用连"连击不足"都不等：那一档是本轮的终局，越早说越好（换挡留痕负责去重）
        assertSame(
            SecondEngineDecision.EngineUnusable,
            secondEngineDecision(SecondEngineLedger(unusableSeen = true), 0, 1L, codeInHand = false, engineUsable = true),
        )
    }

    // ---- ④ 账本转移 ----

    @Test
    fun ledgerTransitions() {
        val start = SecondEngineLedger()
        assertEquals("新账本一开始一发都没发过：", 0, start.fires)
        assertEquals("哨兵值：本轮还没发过火", -1L, start.lastFireFrame)
        assertFalse(start.unusableSeen)

        val fired = secondEngineAfterFire(start, 42L)
        assertEquals("发火没记次数：", 1, fired.fires)
        assertEquals("发火没记下帧号（间隔判据要读它）：", 42L, fired.lastFireFrame)
        assertEquals("发火不许动失手计数：", 0, fired.misses)

        val miss = secondEngineAfterResult(fired, decoded = false, usable = true)
        assertEquals("没解出来要记失手：", 1, miss.misses)
        assertEquals("次数不许回退：", 1, miss.fires)
        assertFalse("只是没解出来，不等于它不在这台设备上：", miss.unusableSeen)

        val hit = secondEngineAfterResult(miss, decoded = true, usable = true)
        assertEquals("一发命中就把失手清零（别拿旧账判新一轮的场景）：", 0, hit.misses)
        assertEquals(1, hit.fires)

        val broken = secondEngineAfterResult(miss, decoded = false, usable = false)
        assertTrue("抛过东西就是「它不在这台设备上」：", broken.unusableSeen)
        assertEquals("抛的那一发两档都要记（不可用 + 失手），判死那一档才有账可对：", 2, broken.misses)

        // 已经判死的账本不涨失手：那个数再涨也只是让取证行说"连续第 4 次"，
        // 而判据在 GaveUp 那一档就已经闭嘴了 —— 让数字停在门槛上才是说实话
        val dead = SecondEngineLedger(fires = 5, misses = SecondEngineGiveUpAfterMisses)
        assertEquals(
            "判死后失手计数不许继续累加：",
            SecondEngineGiveUpAfterMisses,
            secondEngineAfterResult(dead, decoded = false, usable = true).misses,
        )
        // 账本不可变：每一次转移都给新引用，传进去那一枚一个字都不许变
        assertEquals("afterFire 改动了传进去的那一枚：", 0, start.fires)
        assertEquals("afterResult 改动了传进去的那一枚：", 0, fired.misses)
    }

    // ---- ⑤ 停法的措辞：四档有话、两档沉默 ----

    @Test
    fun stopTextTable() {
        for (decision in listOf(SecondEngineDecision.Fire, SecondEngineDecision.Waiting)) {
            assertNull("$decision 不许有停法文案：", secondEngineStopText(decision))
        }
        val speaking = listOf(
            SecondEngineDecision.CodeInHand,
            SecondEngineDecision.OutOfBudget,
            SecondEngineDecision.GaveUp,
            SecondEngineDecision.EngineUnusable,
        )
        for (decision in speaking) {
            val line = secondEngineStopText(decision)
            assertNotNull("$decision 说不出自己为什么停：", line)
            assertTrue("$decision 的停法太短，读不出后果：$line", requireNotNull(line).length >= 12)
        }
        // 额度与判死两档必须把**数字**说出来：读日志的人要能核"到底发过几发"
        assertTrue(
            "额度那档没报封顶数字：",
            requireNotNull(secondEngineStopText(SecondEngineDecision.OutOfBudget)).contains(SecondEngineMaxFiresPerBind.toString()),
        )
        assertTrue(
            "判死那档没报连击数字：",
            requireNotNull(secondEngineStopText(SecondEngineDecision.GaveUp)).contains(SecondEngineGiveUpAfterMisses.toString()),
        )
        // 四档都不许把用户指向已经不存在的入口（手输签到码 2026-09-21 删、手电/补光 2026-09-22 撤）
        for (decision in speaking) {
            val line = requireNotNull(secondEngineStopText(decision))
            for (banned in listOf("手输", "输入签到码", "手电", "补光", "闪光")) {
                assertFalse("$decision 的停法指向了死入口「$banned」：$line", line.contains(banned))
            }
        }
    }

    // ---- ⑥ 探针档位 ----

    @Test
    fun probeVerdictComesFromTheConstructionItself() {
        assertSame("构造成功就是可用：", SecondEngineProbe.Usable, secondEngineProbeOnConstruct(true))
        assertSame("构造失败就是不可用：", SecondEngineProbe.Unusable, secondEngineProbeOnConstruct(false))
        // 三态各自成立：Pending 折叠成任何一头都是一类失效（把它当不可用 ⇒ 兜底永远醒不来）
        assertEquals("探针档位数变了：", 3, SecondEngineProbe.entries.size)
        assertSame("枚举里必须留着未判定那一档：", SecondEngineProbe.Pending, SecondEngineProbe.valueOf("Pending"))
    }

    // ---- ⑦ 红线：没码的帧永不触发 ----

    @Test
    fun nothingDetectedNeverFiresAcrossHundredsOfFrames() {
        for (rung in listOf(FrameCodeRung.NothingDetected, FrameCodeRung.CodeReadable)) {
            val loop = FrameLoop()
            repeat(400) { loop.step(rung) }
            assertTrue("$rung 那一档补了 ${loop.fired.size} 次，只许 0 次：", loop.fired.isEmpty())
            assertEquals("$rung 之后可重试连击不归零了：", 0L, loop.assist.retryableStreak)
            assertEquals("账本白记了：", 0, loop.ledger.fires)
        }
    }

    /** ⑦′ 单帧噪声不算连击：中间夹一帧好的就从头重数 */
    @Test
    fun aSingleReadableFrameClearsTheStreak() {
        val loop = FrameLoop()
        repeat((SecondEngineStreakFrames - 1).toInt()) { loop.step(FrameCodeRung.CodeTooSmall) }
        assertTrue("差一帧就已经发过火：", loop.fired.isEmpty())
        loop.step(FrameCodeRung.CodeReadable)
        repeat(SecondEngineStreakFrames.toInt()) { loop.step(FrameCodeRung.CodeTooSmall) }
        assertTrue("被好帧打断后重数满一窗之前不许发火（决策看的是上一帧为止）：", loop.fired.isEmpty())
        loop.step(FrameCodeRung.CodeTooSmall)
        assertEquals("重数满一窗之后就该发第一发：", listOf(loop.frame), loop.fired)
    }

    // ---- ⑧ 端到端：坏帧段的发火节奏 ----

    /** 糊码段一直坏：三次失手 ⇒ 判死，发火帧号恰好是「门槛+1」起步、每隔 [SecondEngineFrameGap] 一发 */
    @Test
    fun sustainedBadFramesFireOnTheAnnouncedCadenceThenGiveUp() {
        val loop = FrameLoop(decodedText = null)
        repeat(120) { loop.step(FrameCodeRung.CodeUndecodable) }
        val first = SecondEngineStreakFrames + 1
        assertEquals(
            "发火帧号不对（第 1 发在连击门槛之后一帧，之后每隔 $SecondEngineFrameGap 帧一发）：",
            listOf(first, first + SecondEngineFrameGap, first + SecondEngineFrameGap * 2),
            loop.fired,
        )
        assertEquals("三次失手就该判死，之后 83 帧一帧都不许多补：", SecondEngineGiveUpAfterMisses, loop.ledger.misses)
        assertEquals("发火次数就该等于判死门槛：", SecondEngineGiveUpAfterMisses, loop.ledger.fires)
        assertSame(
            "第 120 帧仍在判死之外：",
            SecondEngineDecision.GaveUp,
            secondEngineDecision(loop.ledger, loop.assist.retryableStreak, loop.frame, false, true),
        )
    }

    /** 兜底每次都命中：一路发到每轮封顶为止，一发不多 */
    @Test
    fun alwaysHittingFallbackKeepsFiringUpToThePerBindCap() {
        val loop = FrameLoop(decodedText = "https://spoc.buaa.edu.cn/t/checkin?qdid=1")
        repeat(300) { loop.step(FrameCodeRung.CodeTooSmall) }
        assertEquals("封顶就是 $SecondEngineMaxFiresPerBind 发：", SecondEngineMaxFiresPerBind, loop.fired.size)
        assertEquals("每发之间恰好隔 $SecondEngineFrameGap 帧：", SecondEngineFrameGap, loop.fired[1] - loop.fired[0])
        assertEquals(
            "最后一发的帧号：",
            SecondEngineStreakFrames + 1 + SecondEngineFrameGap * (SecondEngineMaxFiresPerBind - 1),
            loop.fired.last(),
        )
        assertEquals("一路命中的话失手计数一直是 0：", 0, loop.ledger.misses)
        assertSame(
            "额度用完之后每一帧都该报 OutOfBudget：",
            SecondEngineDecision.OutOfBudget,
            secondEngineDecision(loop.ledger, loop.assist.retryableStreak, loop.frame, false, true),
        )
    }

    /** 闸门挂着结果卡那一段：连击到线也不补（补了投不出去），卡一放下照旧补 */
    @Test
    fun codeInHandSuppressesWithoutSpendingBudget() {
        val loop = FrameLoop(decodedText = null)
        repeat(40) { loop.step(FrameCodeRung.CodeTooSmall, codeInHand = true) }
        assertTrue("结果卡挂着的时候补了 ${loop.fired.size} 发，只许 0 发：", loop.fired.isEmpty())
        assertEquals("被压住的这些帧不许花额度：", 0, loop.ledger.fires)
        assertEquals("连击照旧在攒（闸门与观测是两本账）：", 40L, loop.assist.retryableStreak)
        // 卡放下（用户按了「重新扫码」或换了张码）：连击还在，下一帧就能补
        loop.step(FrameCodeRung.CodeTooSmall, codeInHand = false)
        assertEquals("闸门放下后第一帧就该补：", listOf(41L), loop.fired)
    }

    /** ⑧′ 与缩放那本账的分工：没有缩放控制的设备上兜底照旧攒连击（T66 刻意与 tooSmallStreak 分家） */
    @Test
    fun retryableStreakIgnoresZoomCapabilityUnlikeTooSmallStreak() {
        val loop = FrameLoop(zoomAvailable = false, decodedText = null)
        repeat(10) { loop.step(FrameCodeRung.CodeTooSmall) }
        assertEquals("缩放那本账在无缩放设备上不清零的话就是攒空炮：", 0L, loop.assist.tooSmallStreak)
        assertTrue("兜底那本账不许跟着缩放能力走：", loop.assist.retryableStreak >= SecondEngineStreakFrames)
        assertEquals("没有缩放控制的设备上兜底也该发火（它是唯一还能出声的手段）：", listOf(7L), loop.fired)
    }

    /** 引擎判死是**闩锁**：一旦报过不可用，本轮再多的坏帧也不许叫它第二次 */
    @Test
    fun engineUnusableLatchIsForeverWithinThisLedger() {
        val loop = FrameLoop(decodedText = null, usableAfterFirstFire = false)
        repeat(60) { loop.step(FrameCodeRung.CodeTooSmall) }
        assertEquals("不可用之后不许再补第二发：", 1, loop.fired.size)
        assertTrue("账本要记下它不可用：", loop.ledger.unusableSeen)
        assertSame(
            "第 60 帧的结论：",
            SecondEngineDecision.EngineUnusable,
            secondEngineDecision(loop.ledger, loop.assist.retryableStreak, loop.frame, false, engineUsable = false),
        )
    }

    // ---- ⑨ 真库在宿主 JVM 上必须判成不可用（不许假装能用） ----

    /**
     * 造一枚**真**解码器（[BarcodeReader]）交给壳：宿主 JVM 的 `java.library.path` 里
     * 没有 `libzxingcpp_android.so`，`BarcodeReader` 的 `init` 块必然抛 ⇒
     * 探针必须落到 [SecondEngineProbe.Unusable]，此后 [ZxingCppFallbackDecoder.mayTry] 永不再放行。
     *
     * 这一条是真代码（不注入、不 mock）。它同时是"探针=构造那一下"这条设计的地基：
     * 全仓第二处 `System.loadLibrary` 由 `BarhopperNativeLibProbeTest` ⑥ 钉着不许有，
     * 所以这里只能靠构造来判 —— 判不动就得说实话。
     */
    @Test
    fun realReaderConstructionOnHostJvmDecidesUnusable() {
        val decoder = ZxingCppFallbackDecoder { BarcodeReader() }
        assertEquals("新壳的起点必须是未判定：", SecondEngineProbe.Pending, decoder.verdict())
        assertNull("还没试过就没有失败原因：", decoder.lastFailure)
        assertTrue("未判定不许被当成不可用（那样兜底永远不会被叫醒）：", decoder.mayTry())
        assertSame(
            "宿主 JVM 里没有那颗 .so，构造就该判死：",
            SecondEngineProbe.Unusable,
            decoder.ensureReady(),
        )
        assertFalse("判过不可用还放行：", decoder.mayTry())
        assertNotNull("失败原因没留下来（取证行就说不出为什么）：", decoder.lastFailure)
        assertSame("结论不许改判（幂等：至多撞一次锁、构造一次）：", SecondEngineProbe.Unusable, decoder.ensureReady())
        assertEquals("没解过帧就没有 zxing-cpp 计时：", -1, decoder.lastReadMillis())
    }

    // ---- 帧循环替身：把 analyze 里那一段顺序在 JVM 里照抄一遍 ----

    /**
     * 生产顺序的照抄：**先**用截至上一帧为止的账做决定，**后**把这一帧的档位喂进帧观测内核。
     *
     * 为什么是这个顺序：[trySecondEngine] 排在 `scanner.process()` 之前，而 ML Kit 的档位
     * 要到它的回调里才知道 —— 一帧的结论永远只属于下一帧。这一句就是"发火最晚晚一帧"
     * 那条取舍的可执行版本（代价与理由写在 [ScanSecondEnginePolicy] 的文件注释里）。
     */
    private class FrameLoop(
        val zoomAvailable: Boolean = true,
        val decodedText: String? = null,
        /** false = 第一发之后引擎就报"我不在这台设备上"（构造抛过 / 解帧抛过那一档） */
        val usableAfterFirstFire: Boolean = true,
    ) {
        var assist = ScanAssistState()
        var ledger = SecondEngineLedger()
        var frame = 0L
        val fired = mutableListOf<Long>()
        private var engineUsable = true

        fun step(rung: FrameCodeRung, codeInHand: Boolean = false): SecondEngineDecision {
            frame++
            val decision = secondEngineDecision(ledger, assist.retryableStreak, frame, codeInHand, engineUsable)
            if (decision is SecondEngineDecision.Fire) {
                ledger = secondEngineAfterFire(ledger, frame)
                fired += frame
                // 生产里 [ZxingCppFallbackDecoder.decode] 自己就把"它抛了"记进探针档位，
                // 而调用点是在 decode **之后**才读档位 —— 所以档位翻面必须先于记账。
                if (!usableAfterFirstFire) engineUsable = false
                ledger = secondEngineAfterResult(ledger, decoded = decodedText != null, usable = engineUsable)
            }
            assist = advanceScanAssist(assist, rung, zoomAvailable).state
            return decision
        }
    }
}
