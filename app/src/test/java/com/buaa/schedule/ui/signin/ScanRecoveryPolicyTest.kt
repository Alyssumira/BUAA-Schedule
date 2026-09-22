package com.buaa.schedule.ui.signin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫码解码器"停用 / 试回 / 放弃"判据的表驱动单测（T59①②）。
 *
 * 这一档以前在 JVM 里是**没法测**的：`scannerWorking` 是一颗写在 Composable 里的
 * `mutableStateOf(true)`，只关不开，而关它的两出口都在分析器里。判据抽到
 * [ScanRecoveryPolicy.kt] 之后，"几次失败算坏、坏了几轮算没救、回到前台还配不配再试"
 * 全都能在这里逐支钉住 —— 零 android import，帧号与原因原文都是参数。
 *
 * 本文件刻意不调用任何取钟口：停用与退避一律以**帧数**表达，所以这里不需要假时钟
 * （也让 [ScanSubmissionGateTest] ⑨ 那句"analyzer 里只有三处 System.currentTimeMillis()"
 * 继续保持可核）。
 */
class ScanRecoveryPolicyTest {

    /** 把一串"在第几帧失败"喂进判据，返回最后那份健康度：表驱动的主入口 */
    private fun failures(vararg frameSerials: Long, start: ScanDecoderHealth = ScanDecoderHealth()) =
        frameSerials.fold(start) { health, frame -> healthAfterDecodeFailure(health, frame) }

    // ---- ① 连续失败才算坏：单帧抖动不再判死整页 ----

    /** ①a 第 1、2 帧失败：不停用（这就是旧写法把 scannerWorking 一次性写成 false 的那一档） */
    @Test
    fun singleTransientFailureDoesNotStopCameraScanning() {
        val one = healthAfterDecodeFailure(ScanDecoderHealth(), frameSerial = 1L)
        assertEquals(1, one.consecutiveFailures)
        assertEquals(-1L, one.suspendUntilFrame)
        assertEquals(
            "一次瞬时失败就判死相机扫码 —— 这正是 T59① 要拆的那颗单向棘轮",
            true, scannerWorkingOf(one),
        )
        val two = healthAfterDecodeFailure(one, frameSerial = 7L)
        assertEquals(2, two.consecutiveFailures)
        assertEquals(true, scannerWorkingOf(two))
    }

    /** ①b 第 3 帧失败：停用，窗口是 `0 轮 + 1` 倍基数 */
    @Test
    fun thirdConsecutiveFailureSuspendsForOneWindow() {
        val health = failures(1L, 2L, 3L)
        assertEquals(false, scannerWorkingOf(health))
        assertEquals(3L + suspendWindowFrames(0), health.suspendUntilFrame)
        assertEquals(0, health.suspensionCycles)
        assertEquals(null, health.giveUpReason)
    }

    /** ①c 同一帧二次报错（同步抛 + 异步回调）只算一次失败，不白翻倍窗口 */
    @Test
    fun twoFailuresOnTheSameFrameCountOnce() {
        val twice = failures(4L, 4L)
        assertEquals(1, twice.consecutiveFailures)
        assertEquals(-1L, twice.suspendUntilFrame)
        val burst = failures(2L, 3L, 4L, 4L)
        assertEquals("第三帧重复计入的话这里就是 4 次：", 3, burst.consecutiveFailures)
        assertEquals(4L + suspendWindowFrames(0), burst.suspendUntilFrame)
    }

    // ---- ② 停用期间的帧:压掉、窗口过后放一帧去探 ----

    @Test
    fun framesInsideTheWindowAreSkippedAndTheFirstFrameOutsideProbes() {
        val health = failures(1L, 2L, 3L)
        val until = health.suspendUntilFrame
        val table = listOf(
            until - 20L to DecoderFrameAction.Skip,
            until - 1L to DecoderFrameAction.Skip,
            until to DecoderFrameAction.Probe,
            until + 5L to DecoderFrameAction.Probe,
        )
        for ((frame, expected) in table) {
            assertEquals("第 $frame 帧（窗口止于 $until）：", expected, decoderFrameAction(health, frame))
        }
        // 没停用时每一帧都照解
        assertEquals(DecoderFrameAction.Decode, decoderFrameAction(ScanDecoderHealth(), 1L))
    }

    /** ②b 探针帧又失败：轮数 +1，窗口按轮数线性变长（越坏越不值得马上去撞） */
    @Test
    fun failedProbeCountsOneCycleAndTheWindowGrows() {
        val suspended = failures(1L, 2L, 3L)
        val probeFrame = suspended.suspendUntilFrame
        val second = healthAfterDecodeFailure(suspended, probeFrame)
        assertEquals(1, second.suspensionCycles)
        assertEquals(probeFrame + suspendWindowFrames(1), second.suspendUntilFrame)
        assertEquals(suspendWindowFrames(1), suspendWindowFrames(1) * 1)
        assertEquals(
            "窗口必须随轮数变长，否则就是在等距地撞同一颗坏解码器",
            suspendWindowFrames(0) * 2, suspendWindowFrames(1),
        )
    }

    /** ②c 轮数用完：放弃这一页剩下的时间，并留下一个能进日志的原因 */
    @Test
    fun suspensionBudgetIsHardBounded() {
        var health = failures(1L, 2L, 3L)
        var expectedFrames = 3L
        for (cycle in 1 until MaxDecodeSuspensionCycles) {
            expectedFrames += suspendWindowFrames(cycle - 1)
            assertEquals("第 $cycle 轮探针帧应当正好落在窗口止点：", DecoderFrameAction.Probe, decoderFrameAction(health, expectedFrames))
            health = healthAfterDecodeFailure(health, expectedFrames)
            assertEquals(cycle, health.suspensionCycles)
        }
        assertEquals(false, scannerWorkingOf(health))
        assertEquals(null, health.giveUpReason)
        // 最后一轮探针再失败 ⇒ 预算用完
        expectedFrames += suspendWindowFrames(MaxDecodeSuspensionCycles - 1)
        val givenUp = healthAfterDecodeFailure(health, expectedFrames)
        assertEquals(false, scannerWorkingOf(givenUp))
        val reason = requireNotNull(givenUp.giveUpReason) { "放弃那一档必须说得出原因，否则取证只能猜" }
        // 放弃之后：每一帧都 Skip，再来多少次失败也不动它（不许有"悄悄又活了"的第三条路）
        for (frame in longArrayOf(givenUp.suspendUntilFrame, givenUp.suspendUntilFrame + 1000L, 999_999L)) {
            assertEquals(DecoderFrameAction.Skip, decoderFrameAction(givenUp, frame))
        }
        val afterMore = failures(10L, 11L, 12L, start = givenUp)
        assertSame(givenUp, afterMore)
        // 原因里点名了两件事实：连错几帧、自动试回几轮
        assertEquals(true, reason.contains("连续") && reason.contains(MaxDecodeSuspensionCycles.toString()))
    }

    /** ②d 解码任务正常返回一次 ⇒ 停用与轮数一起清零（真好了就不再背著"坏过"的账） */
    @Test
    fun aSuccessfulDecodeClearsTheSuspensionLedger() {
        val suspended = failures(1L, 2L, 3L)
        val healthy = healthAfterDecodeSuccess(suspended)
        assertEquals(true, scannerWorkingOf(healthy))
        assertEquals(0, healthy.consecutiveFailures)
        assertEquals(0, healthy.suspensionCycles)
        assertEquals(-1L, healthy.suspendUntilFrame)
        // 已经活着的时候不复制一份新的（分析器每帧都可能调，别白分配）
        assertSame(healthy, healthAfterDecodeSuccess(healthy))
    }

    // ---- ③ 回到前台那一档的额度：可恢复，但有界 ----

    @Test
    fun pageVisibleGivesABoundedFreshAttempt() {
        var health = failures(1L, 2L, 3L)
        var givenUp = health
        while (givenUp.giveUpReason == null) {
            givenUp = healthAfterDecodeFailure(givenUp, givenUp.suspendUntilFrame.coerceAtLeast(0L))
        }
        assertEquals(false, scannerWorkingOf(givenUp))
        val recovered = healthAfterPageVisible(givenUp)
        assertEquals("回到前台得能把相机这条救回来（旧写法没有任何一条回头路）：", true, scannerWorkingOf(recovered))
        assertEquals(1, recovered.pageVisibleRecoveries)
        // 额度用完就没了：ON_RESUME 不能再成为无界重试的引擎
        val brokenAgain = recovered.copy(suspendUntilFrame = 1_000L, giveUpReason = "又坏了")
        val twice = healthAfterPageVisible(brokenAgain)
        assertEquals(2, twice.pageVisibleRecoveries)
        assertEquals(true, scannerWorkingOf(twice))
        val thirdBroken = twice.copy(suspendUntilFrame = 2_000L, giveUpReason = "又坏了")
        val exhausted = healthAfterPageVisible(thirdBroken)
        assertSame("回到前台的额度用完了还在给新机会：", thirdBroken, exhausted)
        assertEquals(false, scannerWorkingOf(exhausted))
        // 本来就活着时 ON_RESUME 不动它（不白换引用）
        val alive = ScanDecoderHealth()
        assertSame(alive, healthAfterPageVisible(alive))
        assertNotSame(givenUp, recovered)
    }

    // ---- ④ scannerWorkingOf 的六个乘项逐支过一遍 ----

    @Test
    fun scannerWorkingFollowsSuspendAndGiveUpOnly() {
        val table = listOf(
            ScanDecoderHealth() to true,
            ScanDecoderHealth(consecutiveFailures = 2) to true,
            ScanDecoderHealth(suspendUntilFrame = 50L) to false,
            ScanDecoderHealth(giveUpReason = "连续 3 帧解码失败、自动试回 3 轮仍不成") to false,
            // 停用的同时还放弃了：两档同向，不冲突
            ScanDecoderHealth(suspendUntilFrame = 50L, giveUpReason = "x") to false,
            // 只有轮数/失败计数本身不构成停用（旧棘轮就是把"失败过"直接当成"坏了"）
            ScanDecoderHealth(suspensionCycles = 2, consecutiveFailures = 2, framesAtLastFailure = 9L) to true,
        )
        for ((health, expected) in table) {
            assertEquals("$health", expected, scannerWorkingOf(health))
        }
    }

    /**
     * ④b（T59b②）scannerGiveUp 把 working == false 的两种病因分开：暂时 / 已判死。
     *
     * 界面文案与取景框都按这一颗分档（判据只留在这里，UI 侧不许读 giveUpReason 拼分支），
     * 所以三档输入都要在这里钉出「输入 → 输出」：健康 → false，停用窗口内 → false
     * （正在自动试回来，说"这台设备用不了"就是假话），已判死 → true。
     */
    @Test
    fun giveUpTierSeparatesSuspendWindowFromDeadOnArrival() {
        val suspending = failures(1L, 2L, 3L)
        var givenUp = suspending
        while (givenUp.giveUpReason == null) {
            givenUp = healthAfterDecodeFailure(givenUp, givenUp.suspendUntilFrame.coerceAtLeast(0L))
        }
        val table = listOf(
            // 健康：两档判据都不成立
            ScanDecoderHealth() to false,
            ScanDecoderHealth(consecutiveFailures = 1, framesAtLastFailure = 4L) to false,
            // 停用窗口内：working 已经是 false，但那是"暂时"，不是"这台设备用不了"
            suspending to false,
            ScanDecoderHealth(suspendUntilFrame = 50L, suspensionCycles = 1) to false,
            // 已判死：连错满 3 帧 × 试回 3 轮仍不成，走到这里才是既有那句措辞的事实
            givenUp to true,
            ScanDecoderHealth(giveUpReason = "回到前台额度用完") to true,
            // 判死压倒停用：判死必然不 working，不 working 却未必判死 —— 这正是分档的意义
            ScanDecoderHealth(suspendUntilFrame = 50L, giveUpReason = "x") to true,
        )
        for ((health, expected) in table) {
            assertEquals("$health", expected, scannerGiveUp(health))
        }
        assertEquals(false, scannerWorkingOf(suspending))
        assertEquals(false, scannerWorkingOf(givenUp))
        assertTrue(
            "working == false 的两档必须由这一颗分得开（分不开就等于 UI 侧继续共用一句假话）：",
            !scannerGiveUp(suspending) && scannerGiveUp(givenUp),
        )
    }

    // ---- ⑤ 绑定失败：分两支，只有瞬时那一支配重试 ----

    @Test
    fun bindFailuresAreClassifiedByTheirReasonText() {
        val noCamera = listOf(
            "No supported combination of camera characteristics",
            "Camera with LENS_FACING_BACK is no camera available",
            "Camera in use by another client",
            "camera cannot be opened right now",
        )
        for (message in noCamera) {
            assertEquals(message, CameraBindFailure.NoBackCamera, classifyCameraBindFailure(message))
            assertEquals(
                "没有后置摄像头那一档重试也治不好，一次都不许多试",
                false, cameraBindRetryAllowed(1, classifyCameraBindFailure(message)),
            )
        }
        val transient = listOf("timeout waiting for camera service", "bindToLifecycle failed", null, "")
        for (message in transient) {
            assertEquals("$message", CameraBindFailure.Retryable, classifyCameraBindFailure(message))
        }
    }

    @Test
    fun bindRetryIsBoundedAndBacksOff() {
        // 首试 = 1：还能再试两次；第 3 次失败之后不试了
        val table = listOf(
            1 to true,
            2 to true,
            MaxCameraBindAttempts to false,
            MaxCameraBindAttempts + 1 to false,
            0 to false,
        )
        for ((attempt, expected) in table) {
            assertEquals("attempt=$attempt", expected, cameraBindRetryAllowed(attempt, CameraBindFailure.Retryable))
        }
        assertEquals(0L, cameraBindBackoffMillis(0))
        assertEquals(CameraBindBackoffMillis, cameraBindBackoffMillis(1))
        assertEquals(CameraBindBackoffMillis * 2, cameraBindBackoffMillis(2))
        assertEquals("尝试上限至少要留一次重试，否则这一档等于没改", true, MaxCameraBindAttempts >= 2)
    }

    // ---- ⑥ 有码没有原文那一档的留痕节流：不是每帧一行 ----

    @Test
    fun valuelessBarcodeTraceIsLoggedOnceThenStrided() {
        val table = listOf(
            0L to false,
            1L to true,
            2L to false,
            ValuelessBarcodeLogStride - 1 to false,
            ValuelessBarcodeLogStride to true,
            ValuelessBarcodeLogStride + 1 to false,
            ValuelessBarcodeLogStride * 4 to true,
        )
        for ((count, expected) in table) {
            assertEquals("count=$count", expected, shouldLogValuelessBarcode(count))
        }
        // 负数（不该出现，但静默放行等于把"数错了"也留痕）
        assertEquals(false, shouldLogValuelessBarcode(-1L))
    }

    // ---- ⑦ 常数就是判据本体不许是魔数 ----

    @Test
    fun policyConstantsAreTheDocumentedBounds() {
        assertEquals(3, ConsecutiveDecodeFailureLimit)
        assertEquals(3, MaxDecodeSuspensionCycles)
        assertEquals(2, MaxPageVisibleRecoveries)
        assertEquals(20L, suspendWindowFrames(0))
        assertEquals(3, MaxCameraBindAttempts)
    }
}
