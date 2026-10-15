package com.buaa.schedule.ui.signin

import java.io.File
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T395②：递交闸门「从一次会过期的读改成一次原子换」的表驱动单测（纯 JVM，零 android、零假时钟）。
 *
 * ## 判据侧能吃到的最小输入是什么（这一格必须写清，否则表就在自证）
 *
 * [scanFlightAdmission] 拿到的关于闸门的东西**只有一枚 Boolean**（`dispatching`）。本卡之后它的
 * 出处不再是 `flight.value` 那次读，而是 `flight.compareAndSet(false, true)` 那一次原子换的**结果**：
 * 领到手 ⇒ `dispatching = false` ⇒ Dispatch；没领到 ⇒ `dispatching = true` ⇒ 落到丢掉那一族。
 * 于是"两枚线程各自读到未派发"这个形状在**领取**这一档根本没有输入可表达 —— 八手同时来抢，
 * 也只有一手能把 false 换 TRUE。①② 两格就是把这句话跑出来：同一套输入，走领取得 1 枚 Dispatch，
 * 走读得 8 枚 Dispatch（后者就是本卡的病灶形状，它的期望值是手写的，不是拿实现算的）。
 *
 * ## 线程为什么不进判据的参数表
 *
 * 线程是**调用点属性**（谁在问），不是**判据事实**（这一手凭什么投）：同一份处境下，
 * 分析线程那一手与主线程那一手的正确档位**一模一样**，把线程 id 递进去算不出任何新档，
 * 只会让调用点拿到结论之后再按自己是谁判一次 —— 那是本仓明文禁止的"返回布尔后调用点各判一次"，
 * 也是这一页修过三轮的"两份真相"。判据本体一个字没动，零 android/androidx import 的口径照旧
 * （纯度由 `ScanFlightArrivalWiringGuardTest` ⑧ 钉着）。
 *
 * ## 钉法点名（本仓四种刀法里用的是哪几种）
 *
 * - **真跑**：①②④⑤ 跑的是 `MutableStateFlow.compareAndSet` 本身 —— 与本卡实现用的是**同一颗类型**，
 *   所以量到的就是状态机所依赖的那个内存语义（`ScanChainWarmUpTest` ① 那八线程抢门闩的先例）；
 * - **表驱动**：③ 手写的行，每行的期望是档位不是布尔；
 * - **抹注释找锚点 + 读形状**：⑤b⑥⑦⑧ 从 `SignInViewModel.kt` 与 `SpocScanScreen.kt` 取函数体，
 *   钉次序（领取排在问判据之前）、唯一性（闸门字段的写点恰好两枚 CAS）、归属（落回只在
 *   `dispatchNow` 体内可达）与**否定**（裸写 false、`dispatching = flight.value` 都得是 0 枚）；
 * - **逐字节**：本文件不用，闸门那两枚基点前移的靶子在 ③④ 那两枚在册守卫里复算。
 *
 * ## 跑不到的（不许算进"已修好"）
 *
 * 交错的实际概率、真机上两发都落库时服务端答什么、`viewModelScope` 已取消时那一趟 `finally`
 * 不跑（闸门留在 true，同 T60 在 `ScheduleViewModel` 上写明的同一档残账）—— JVM 里都量不到，
 * 交回编排者。本文件钉的是"这一手投不投**只**由一次原子换的结果决定"这条性质，
 * 不是"这两枚线程真的撞上过"。
 */
class ScanFlightClaimTest {

    /** 状态机那一步的等价物：领取（一次原子换）+ 把结果递给判据。⑥⑦⑧ 钉的是 VM 自己写的也是这两句。 */
    private fun handIn(
        gate: MutableStateFlow<Boolean>,
        isRetryHand: Boolean,
        raw: String,
        dispatchingPayload: String?,
        dropNoticed: Boolean,
    ): ScanFlightAdmission {
        val claimed = gate.compareAndSet(expect = false, update = true)
        return scanFlightAdmission(
            dispatching = !claimed,
            isRetryHand = isRetryHand,
            samePayloadAsDispatching = raw == dispatchingPayload,
            alreadyNoticedForThisDispatch = dropNoticed,
            cooldownRemainingMillis = if (isRetryHand) NoLedgerCooldownRemainingMillis else RescanCooldownMillis,
        )
    }

    /** 落回的等价物：只有握着它的那一手落得动（CAS 从 true 落回 false）。 */
    private fun releaseGate(gate: MutableStateFlow<Boolean>): Boolean =
        gate.compareAndSet(expect = true, update = false)

    /**
     * ① 八手同时抢一颗空闸门：恰好一手拿到 Dispatch。
     *
     * 这八手故意混了两条路（四手相机按帧、四手相册按次）与两种原文（同一枚 / 换一枚）——
     * 「重复提交比这次没吃进去更糟」那条裁定要的是**任何**组合下都只有一发请求。
     * 期望值 1 是手写的，不是拿实现算出来的。
     */
    @Test
    fun eightHandsOnOneFreeGateAdmitExactlyOneDispatch() {
        val gate = MutableStateFlow(false)
        val hands = 8
        val barrier = CyclicBarrier(hands)
        val admitted = AtomicInteger(0)
        val workers = (1..hands).map { index ->
            Thread {
                barrier.await()
                val verdict = handIn(
                    gate = gate,
                    isRetryHand = index % 2 == 0,
                    raw = if (index % 3 == 0) SAME_CODE else ANOTHER_CODE,
                    dispatchingPayload = SAME_CODE,
                    dropNoticed = false,
                )
                if (verdict == ScanFlightAdmission.Dispatch) admitted.incrementAndGet()
            }.also { it.start() }
        }
        workers.forEach { it.join() }
        assertEquals(
            buildString {
                append("抢同一颗空闸门的八手里拿到 Dispatch 的是 ")
                append(admitted.get())
                append(" 手，只能 1 手：多一枚就是多发一发真把学生标记成已签到的请求")
            },
            1,
            admitted.get(),
        )
        assertTrue("八手抢完闸门还留在手里（领而不还的那一条要从这里红）：", gate.value)
    }

    /**
     * ② 反面对照：把领取换成一次**读**，同一套输入就开出八枚 Dispatch。
     *
     * 这一格钉的是病灶本身，不是修复：读→判→写之所以是竞态，是因为读出来的 false 在写之前
     * 一直是过期的。谁哪天把 `dispatching` 的出处换回 `flight.value`，⑥ 与这一格一起红。
     */
    @Test
    fun aReadInsteadOfAClaimAdmitsEveryHand() {
        val gate = MutableStateFlow(false)
        val admissions = (1..8).map { index ->
            scanFlightAdmission(
                dispatching = gate.value,
                isRetryHand = index % 2 == 0,
                samePayloadAsDispatching = (if (index % 3 == 0) SAME_CODE else ANOTHER_CODE) == SAME_CODE,
                alreadyNoticedForThisDispatch = false,
                cooldownRemainingMillis = RescanCooldownMillis,
            )
        }
        assertEquals(
            buildString {
                append("读→判→写那一形状下判成 Dispatch 的是 ")
                append(admissions.count { it == ScanFlightAdmission.Dispatch })
                append(" 手，本卡期望它等于八 —— 这一格就是要把病灶的形状钉住，不是把它盖掉")
            },
            8,
            admissions.count { it == ScanFlightAdmission.Dispatch },
        )
    }

    private data class Row(
        val gateStartsHeld: Boolean,
        val isRetryHand: Boolean,
        val samePayload: Boolean,
        val alreadyNoticed: Boolean,
        val expected: ScanFlightAdmission,
        val why: String,
    )

    /**
     * ③ 表驱动：闸门此刻归谁 + 递进来的那三枚事实 ⇒ 档位。每行期望手写。
     *
     * 表的两端都扭过：把闸门扭成空 ⇒ 谁都能领到；扭成已握 ⇒ 谁领都领不到，只能落进丢掉那一族。
     * 与 `ScanFlightAdmissionTest` 那十六格的分工：那一颗钉**判据本体**的档位次序，
     * 这一颗钉**领取的结果**是判据吃到的唯一一枚闸门事实（`dispatching` 之外闸门不再进参数表）。
     */
    @Test
    fun theClaimOutcomeIsTheOnlyGateFactTheKernelEverSees() {
        val rows = listOf(
            Row(false, false, false, false, ScanFlightAdmission.Dispatch, "#1 空闸门＋相机手：领到就投"),
            Row(false, true, false, false, ScanFlightAdmission.Dispatch, "#2 空闸门＋相册手：同样领得到（来源不许被闸门偏袒）"),
            Row(false, false, true, true, ScanFlightAdmission.Dispatch, "#3 空闸门时其余三枚一概不看"),
            Row(true, false, true, false, ScanFlightAdmission.DropSameCodeWhileDispatching, "#4 别人握着＋同一枚：故意不重投"),
            Row(true, false, false, false, ScanFlightAdmission.DropNewCodeWhileDispatching(RescanCooldownMillis), "#5 别人握着＋换一枚：念那一句"),
            Row(true, false, false, true, ScanFlightAdmission.DropAlreadyNoticed, "#6 别人握着＋已经念过：不重复念"),
            Row(true, true, true, true, ScanFlightAdmission.DropUserHandWhileDispatching, "#7 别人握着＋按次那一下：排在念过之前"),
            Row(true, true, false, false, ScanFlightAdmission.DropUserHandWhileDispatching, "#8 相册那一路没有冷却账也照样丢掉"),
        )
        assertEquals("表的格数应当是 8：", 8, rows.size)
        val misses = rows.filter { row ->
            val gate = MutableStateFlow(row.gateStartsHeld)
            handIn(
                gate = gate,
                isRetryHand = row.isRetryHand,
                raw = if (row.samePayload) SAME_CODE else ANOTHER_CODE,
                dispatchingPayload = if (row.gateStartsHeld) SAME_CODE else null,
                dropNoticed = row.alreadyNoticed,
            ) != row.expected
        }
        assertTrue(
            buildString {
                append("这些行落进了别的档：")
                misses.forEach { append(it.why).append("；") }
            },
            misses.isEmpty(),
        )
        // 闸门被抢走的那些行里，领而不还是唯一能长出第二发请求的走法：落回必须落得下
        val gate = MutableStateFlow(true)
        assertTrue("已握着的闸门落不回（那三处 enabled 就会永久谎报在飞）：", releaseGate(gate))
        assertFalse("落回之后闸门还在手里：", gate.value)
    }

    /**
     * ④ 两种来源都领得到同一颗闸门，一次一个。
     *
     * 「闸门只允许一种来源通过」是本卡明令不许的形状（那样相册那一手永远投不出去，
     * 而它正是这台设备上唯一还能用的扫码路）。⑥ 另钉"领取那一句不许读 isRetryHand"，
     * 这一格钉它在语义上真的两边都放行。
     */
    @Test
    fun bothSourcesWinTheGateOnceEachAndTheGateDoesNotChooseFavourites() {
        val gate = MutableStateFlow(false)
        assertEquals(
            "相机那一手领不到（闸门偏袒相册）：",
            ScanFlightAdmission.Dispatch,
            handIn(gate, isRetryHand = false, SAME_CODE, null, false),
        )
        assertTrue("相机那一手没把闸门握住：", gate.value)
        assertEquals(
            "相册那一手在相机落回之后领不到（闸门偏袒相机）：",
            ScanFlightAdmission.Dispatch,
            handIn(gate.also { releaseGate(it) }, isRetryHand = true, ANOTHER_CODE, null, false),
        )
        assertTrue("相册那一手没把闸门握住：", gate.value)
        // 握着的时候两边都领不到，且都不许被劝回「投」
        for (retryHand in listOf(false, true)) {
            assertFalse(
                buildString {
                    append(if (retryHand) "相册" else "相机")
                    append("那一手在别人握着闸门时领得到了（那就是第二发请求）：")
                },
                gate.compareAndSet(expect = false, update = true),
            )
        }
    }

    /**
     * ⑤ 落回只允许握着它的那一手做（行为那一半）。
     *
     * 一次原子换给到的可核性质有两件：**空着的闸门落不动**（CAS 报 false、一个字都不再写），
     * 以及**同一手落第二次也落不动**（那就是把下一发正在飞的门踹开）。
     * "只有握着的这一手才走到落回"那一半靠可达性，不是靠这颗 CAS —— 所以它钉在 ⑤b 的源码形状上，
     * 本格不许把自己说成把那一半也测了。
     */
    @Test
    fun aReleaseThatDoesNotHoldTheGateMissesAndWritesNothing() {
        val free = MutableStateFlow(false)
        assertFalse("空闸门上一次 compareAndSet(true → false) 就成功了（落回不中必须报得出）：", releaseGate(free))
        assertFalse("落回不中却把状态改了：", free.value)

        val held = MutableStateFlow(true)
        assertTrue("握着的那一手落不回：", releaseGate(held))
        assertFalse("落回之后闸门还在 true：", held.value)
        assertFalse("同一颗闸门被落了第二次（那一手此刻并不握着它）：", releaseGate(held))

        // 领而不还 = 永久 in-flight：领到之后必须还能落，落完又能被下一手领走
        val cycle = MutableStateFlow(false)
        assertTrue("第一手领不到：", cycle.compareAndSet(expect = false, update = true))
        assertTrue("第一手落不下：", releaseGate(cycle))
        assertTrue("落回之后第二手领不到（只开不开的闸门比只关不开更隐蔽）：", cycle.compareAndSet(expect = false, update = true))
    }

    /**
     * ⑤b 落回在源码里**只在握着闸门那一路可达**（形状那一半）。
     *
     * 三件事：两处落回都在 `dispatchNow` 体内（那颗函数只有 Dispatch 那一支进得来）；
     * 说话那颗 `tellDroppedArrival` 里没有落回（它是没领到手的那一手）；
     * 漏斗里"不念"那一支也没有。少一条就是有人开始替别人开门。
     */
    @Test
    fun onlyTheDispatchingPathCanBringTheGateDown() {
        val vm = withoutComments(readMainSource(VIEW_MODEL_FILE))
        val dispatch = balancedBlock(vm, "private fun dispatchNow(raw: String)")
        val tell = balancedBlock(vm, "private fun tellDroppedArrival(")
        val funnel = balancedBlock(vm, "fun signIn(raw: String, cooldownRemainingMillis: Long, isRetryHand: Boolean)")
        assertEquals("落回的调用点应当两处都在 dispatchNow 体内（解析落空 + finally）：", 2, occurrences(dispatch, "releaseFlight()"))
        assertEquals("丢掉那一手去落闸门（它根本没握着）：", 0, occurrences(tell, "releaseFlight"))
        // 漏斗里 Dispatch 那一支是唯一能落回的出路：它接的是 dispatchNow，不是 releaseFlight
        assertTrue("Dispatch 那一支接的不是 dispatchNow：\n$funnel", funnel.contains("ScanFlightAdmission.Dispatch -> dispatchNow(raw)"))
        assertEquals("漏斗里直接出现落回（绕开 dispatchNow 的那一扇门）：", 0, occurrences(funnel, "releaseFlight"))
        assertEquals("落回那颗函数只许定义一处：", 1, occurrences(vm, "private fun releaseFlight()"))
    }

    /**
     * ⑥ 状态机那一头的原子换形状（W/N 两臂的红就落在这里与 ③⑤）。
     *
     * 逐条都是**否定＋次序**，不是"grep 到就算绿"：
     * 领取必须在问判据之前、判据吃的是领取的结果而不是读、闸门字段的写点恰好两枚 CAS、
     * 裸写 `flight.value = ` 必须归零、领取那一句不许读 `isRetryHand`、
     * 界面还在读同一颗 StateFlow（所以本卡不必把 MutableStateFlow 换成 AtomicBoolean——
     * 第⓪问那条"合法退路"没触发，这一格就是它的凭据）。
     */
    @Test
    fun theStateMachineClaimsAndReleasesByCasAndFeedsTheKernelWithTheClaimOutcome() {
        val vm = withoutComments(readMainSource(VIEW_MODEL_FILE))
        val funnel = balancedBlock(vm, "fun signIn(raw: String, cooldownRemainingMillis: Long, isRetryHand: Boolean)")
        val claim = funnel.indexOf(CLAIM)
        val asked = funnel.indexOf("scanFlightAdmission(")
        check(claim >= 0 && asked >= 0) { "signIn 里已经没有那次领取或那次问判据，本格的次序无从核对：\n$funnel" }
        assertTrue("领取排到问判据之后了（那又回到读一份过期的 false 去判）：", claim < asked)
        assertEquals("漏斗里那次领取只许一处（两处就是领两回、把别人的门踹开）：", 1, occurrences(funnel, CLAIM))
        assertTrue("判据吃的不是领取的结果：\n$funnel", funnel.contains("dispatching = !claimed"))
        assertEquals("判据又去吃一次会过期的读：", 0, occurrences(vm, "dispatching = flight.value"))
        // 闸门字段的写点：领取 + 落回，两枚 CAS，别的写法一枚不许有
        assertEquals("闸门的写点应当恰好两枚原子换（领取 + 落回）：", 2, occurrences(vm, "flight.compareAndSet"))
        assertEquals("闸门又长回裸写（读→判→写那一形状就从这一格复活）：", 0, occurrences(vm, "flight.value = "))
        assertEquals("落回那一句不是 compareAndSet(true → false)：", 1, occurrences(vm, RELEASE))
        // 落回必须在 finally 里：抛出去的那一趟也要关门
        val dispatch = balancedBlock(vm, "private fun dispatchNow(raw: String)")
        val finallyAt = dispatch.indexOf("finally {")
        val launch = dispatch.indexOf("viewModelScope.launch")
        check(finallyAt > launch) { "dispatchNow 没有 finally：任何一条抛出去的路径都会把闸门留在 true\n$dispatch" }
        assertTrue("落回排到了 finally 之外：\n${dispatch.substring(finallyAt)}", dispatch.substring(finallyAt).contains("releaseFlight()"))
        assertTrue(
            "原文与「念过没有」排到了 launch 之后（协程可能先跑起来，比较基准就是空的）：",
            maxOf(dispatch.indexOf("dispatchingPayload = raw"), dispatch.indexOf("dropNoticed = false")) < launch,
        )
        // 领取那一句不许认识来源（N 臂：闸门只放一种来源过）
        val claimLine = vm.lines().first { it.contains(CLAIM) }
        assertFalse("领取那一句读了 isRetryHand（相册那一手就永远领不到了）：\n$claimLine", claimLine.contains("isRetryHand"))
        // 合法退路没触发：界面读的还是同一颗 StateFlow，本卡没把它换成 AtomicBoolean
        assertEquals("闸门还是 MutableStateFlow（界面那一头要的是 StateFlow，不是 AtomicBoolean）：", 1, occurrences(vm, "MutableStateFlow(false)"))
        assertTrue("闸门不再以 StateFlow 交给界面：", vm.contains("val inFlight: StateFlow<Boolean>"))
        assertFalse("AtomicBoolean 顶掉了 MutableStateFlow（界面的 collectAsState 就断了）：", vm.contains("AtomicBoolean"))
        // 三处按按钮的守卫读的还是同一个来源（T59⑤ 那一条不因本卡而放宽）
        assertEquals("if (flight.value) return 仍然只许三处：", 3, occurrences(vm, "if (flight.value) return"))
    }

    /**
     * ⑦ 本卡的前提今天还在：兜底那一手就在分析线程上就地调这颗共用闸门（没有 post）。
     *
     * 这条不是装饰：如果哪天有人把那一手 post 回主线程（第⓪问的甲档），⑥ 的原子换就成了
     * 唯一剩下的防线，而那两件事必须**同时**被读到才叫有口径 —— 所以这里既钉"分析线程还在"
     * 也钉"这一手上没有 post"。锚点用符号，不用文件名冒号数字（行号会漂，上一轮就栽过一次）。
     */
    @Test
    fun theFallbackHandStillCallsTheSharedDoorOnTheAnalyzerThread() {
        val screen = withoutComments(readMainSource(SCAN_SCREEN_FILE))
        assertTrue("分析器不再交给那颗单线程 executor（本卡的两线程前提要看住这一句）：\n", screen.contains(ANALYZER_BINDING))
        assertTrue("分析用的 executor 不再是单独一颗线程：", screen.contains("private val analysisExecutor = Executors.newSingleThreadExecutor()"))
        val analyzer = balancedBlock(screen, "private class QrCodeAnalyzer(")
        val second = balancedBlock(analyzer, "private fun trySecondEngine(")
        assertTrue("兜底那一路不再递交共用闸门（那就是又起了一道口子）：\n$second", second.contains("submitDecodedText("))
        val gate = balancedBlock(analyzer, "private fun submitDecodedText(")
        assertTrue("闸门不再把原文递给 onCode（状态机那头就收不到按帧那一手）：", gate.contains("onCode(raw, RescanCooldownMillis)"))
        // 甲档那两条腿都不许在这一卡上偷偷长出来
        for (hop in listOf("Handler", "post(", "Dispatchers.Main", "runOnUiThread")) {
            assertFalse("兜底那一路改成了先跳回主线程（$hop）：与原子换同时在场就是两份口径，要改就两半一起改\n$second", second.contains(hop))
        }
        assertEquals("相机 + 相册两条递交口仍然各一处：", 2, occurrences(screen, "viewModel.signIn("))
    }

    /**
     * ⑧ 那句谎订正到了位（丙的那一半）：注释不许再断言"两条入口都在主线程"。
     *
     * 读的是**带注释的原文**——这一格的靶子就是注释本身，抹掉注释就没得审了。
     * 两面都要在：假话不许留在原地，真话不许只写成一句"注意线程"（下一轮又会推出不需要 CAS）。
     */
    @Test
    fun theDocNoLongerClaimsTheTwoEntrancesShareOneThread() {
        val raw = readMainSource(VIEW_MODEL_FILE)
        for (gone in listOf("都在这颗 VM 的主线程上", "分析线程哪天也来读一次")) {
            assertFalse("状态机的注释还在断言「$gone」（本卡的修复就是从这一句推出\"不需要原子换\"的）：", raw.contains(gone))
        }
        val payloadDoc = raw.substringAfter("正在飞的那一发的原文").substringBefore("@Volatile private var dispatchingPayload")
        assertTrue("订正之后没把分析线程点名为写者（那下一轮又推出不需要 CAS）：\n$payloadDoc", payloadDoc.contains("分析线程"))
        assertTrue("没写清这颗字段只由领到闸门的那一手写：\n$payloadDoc", payloadDoc.contains("compareAndSet"))
        assertTrue("@Volatile 的职责没说准（发布 ≠ 排他）：\n$payloadDoc", payloadDoc.contains("发布"))
    }

    // ---- 源码核对工具（与同族守卫一个刀法：锚点用符号，找不着就抛）----

    private fun readMainSource(relativeFromJava: String): String {
        val file = File(findMainJavaDir(), relativeFromJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这一格要跟着改路径", file.isFile)
        return file.readText()
    }

    private fun balancedBlock(source: String, signature: String): String {
        val at = source.indexOf(signature)
        check(at >= 0) { "找不到 $signature：写法换过了，这一格要跟着改" }
        val open = source.indexOf('{', at)
        check(open >= at) { "$signature 之后找不到左括号" }
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(at, index + 1)
                }
            }
        }
        throw IllegalStateException("$signature 的花括号没配平")
    }

    private fun withoutComments(source: String): String {
        val out = StringBuilder(source)
        var block = out.indexOf("/*")
        while (block >= 0) {
            val end = out.indexOf("*/", block + 2)
            if (end < 0) break
            out.replace(block, end + 2, " ")
            block = out.indexOf("/*")
        }
        val text = out.toString()
        return text.lines().joinToString("\n") { line ->
            val slash = line.indexOf("//")
            if (slash >= 0) line.substring(0, slash) else line
        }
    }

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    /** 单测的 cwd 是 :app 模块目录，也可能是仓库根：两种布局都试，全落空就抛 */
    private fun findMainJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { File(dir, it) }
                .firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }

    private companion object {
        const val SCAN_SCREEN_FILE = "com/buaa/schedule/ui/signin/SpocScanScreen.kt"
        const val VIEW_MODEL_FILE = "com/buaa/schedule/ui/signin/SignInViewModel.kt"
        const val CLAIM = "flight.compareAndSet(expect = false, update = true)"
        const val RELEASE = "flight.compareAndSet(expect = true, update = false)"
        const val ANALYZER_BINDING = ".apply { setAnalyzer(analysisExecutor, activeAnalyzer) }"
        const val SAME_CODE = "same-payload-as-dispatching"
        const val ANOTHER_CODE = "another-payload-while-dispatching"
    }
}
