package com.buaa.schedule.ui.signin

/**
 * 扫码页「第二引擎」这一侧的判据内核（T66）。纯判据，零 import
 * （仓库口径，见 [ScanRecoveryPolicy] / [ScanCameraAidPolicy]）：解码器的身份（zxing-cpp 的
 * `BarcodeReader`）、帧本体（`ImageProxy`）、线程、时钟 —— 一律由调用点拿在手里当参数传进来，
 * 本文件只吃参数、只翻档位，绝不自己去碰设备，也绝不认识任何解码器的类。
 *
 * ## 这一颗管的是什么
 *
 * ML Kit 是主力，zxing-cpp 只在它**连续失手**时补一刀。这个定位不是口味问题，是实测问题：
 * 我们自己的 468 帧私有基准里 zxing-cpp 反光 79.5% / 糊码 64.1%，**差于** ML Kit 的
 * 96.2% / 74.4% —— 拿它当主力或每一帧双解都是拿包体和电量去买一个更低的识别率。
 * 它唯一值回票价的，是 `tryHarder` / 反色 / 多二值化那一类**ML Kit 不给我们、而 zxing-cpp
 * 内置免费**的重试。所以这一颗内核存在的意义就是：**把那份免费的力气花在正确的时机上，
 * 并且绝不花在手忙脚乱上**。
 *
 * ## 三条硬约束（红线，改之前先读 [ScanCameraAidPolicy] 的 ④）
 *
 * 1. **触发只认帧观测里「ML Kit 看见了候选码却没解出原文」那两档**
 *    （[FrameCodeRung.CodeTooSmall] / [FrameCodeRung.CodeUndecodable]，即 [retryableRung]）。
 *    [FrameCodeRung.NothingDetected] 是瞄不准，不是解不开 —— 那一档双解只是烧电。
 * 2. **必须节流**：连击够长、间隔够开、每轮绑定封顶。单帧的档位是噪声（[ScanCameraAidPolicy]
 *    为同一件事付了 [RungSettleFrames] 帧的滞后窗，这一颗付 [SecondEngineStreakFrames] 帧的连击）。
 * 3. **每一种「停」都要留得出一行话**（[secondEngineStopText] 是措辞唯一来源）：这一页修过
 *    三轮的病就叫静默 no-op —— 写了没人喂、喂了没人读、读了没留痕，都算没做。
 *
 * ## 为什么发火判据吃的是「上一帧为止的连击」而不是「这一帧的档位」
 *
 * 第二引擎跑在分析线程上、`scanner.process()` 把帧交出去**之前**（理由见 SpocScanScreen 里
 * [noteFrameRung] 的邻居注释：那一帧的缓冲还没被第二个线程摸、proxy 的 close 时机一个字不改）。
 * 而 ML Kit 这一帧的档位要到它的回调里才知道 —— 拿不到未来的结论，是本设计的既定形状，
 * 不是疏忽：签到码糊了/反光了不会只糊一帧（帧间间隔 33–50 ms，坏消息的持续时间以秒计），
 * 所以「按上一帧为止的证据决定这一帧补不补」付的代价是**最晚晚一帧**，换来的是
 * 零拷贝、零 proxy 生命周期改动、零跨线程账本。这笔账在收工报告里也是这么报的。
 */

/**
 * 第二引擎值得出声的档位：ML Kit 在帧里**看见了**候选码却没解出原文。
 *
 * 与 [FrameCodeRung] 的对应关系一句话说清：[FrameCodeRung.CodeReadable] 什么都不用补
 * （主力赢了）；[FrameCodeRung.NothingDetected] 什么都不用补（画面里没码，双解改变不了这件事）；
 * 剩下两档才是 zxing-cpp 的活 —— 太小靠 `tryHarder`+`tryDownscale` 的多二值化轮捞，
 * 解不开靠 `tryInvert` 的反色重试。这两档在基准里的差距（79.5% 对 96.2%）也正好说明
 * 为什么它们是「补一刀」而不是「换主力」。
 */
internal fun retryableRung(rung: FrameCodeRung): Boolean =
    rung == FrameCodeRung.CodeTooSmall || rung == FrameCodeRung.CodeUndecodable

/**
 * 连击门槛：连续这么多帧 [retryableRung] 才允许第一次发火。
 *
 * 6 帧的账：分析流约 20–30 fps ⇒ 200–300 ms。低于一次自然的手抖周期、高于单帧噪声，
 * 而且它与 [SecondEngineFrameGap] 同量级 —— 用户从「码糊了」到「第二引擎出声」最多晚
 * 这 6 帧，而这一页的降级文案滞后窗是 [RungSettleFrames]=12 帧：补解比说话先动，
 * 不会出现「提示你已经解不开了、而兜底还没试过」那种难堪的次序。
 */
internal const val SecondEngineStreakFrames = 6L

/**
 * 两次补解之间的最小帧间隔。15 帧 ≈ 0.5–0.75 s。
 *
 * 这一条的量纲是**算力**而不是电：一次 zxing-cpp 补解（`tryHarder` + 反色 + 多二值化）
 * 在 1280×720 的帧上是几十到一两百毫秒量级的同步开销，全花在分析线程那唯一的一颗
 * worker 上。间隔小于它的运行耗时就是自己掐自己 —— 分析线程被补解占住，ML Kit 的
 * 派发也跟着延后，`STRATEGY_KEEP_ONLY_LATEST` 于是把帧一帧丢掉，看起来就是「预览卡住了」。
 * 15 帧的下界 = 单发最坏耗时（约 3–4 帧）的 4 倍余量，保证补解永远不会连续占满分析线程。
 */
internal const val SecondEngineFrameGap = 15L

/**
 * 每轮绑定最多补解几次。8 次。
 *
 * 8 × 15 帧 = 120 帧 ≈ 4–6 秒的持续输出，正好覆盖「用户举起手机对准投影、等两秒、
 * 觉得不对再凑近一点」这一整段动作。再多的额度不会带来新的信息 —— 一段坏帧里
 * 第 9 次和第 3 次看到的是同一枚解不开的码（连击与间隔已经把「有新证据」这件事筛掉了），
 * 而封顶是唯一能把「兜底变成常态」这条滑坡拦住的机制：没有它，一整节 45 分钟的课
 * 手机举在那儿，兜底就能安静地把电放完。
 */
internal const val SecondEngineMaxFiresPerBind = 8

/**
 * 连续几次补解都没解出原文 ⇒ 本轮绑定不再试第二引擎。3 次。
 *
 * 这是 [SecondEngineMaxFiresPerBind] 的**另一条**上界，两者管的失效不同：额度管的是
 * 「场景一直坏、别一直烧」；这一条管的是「兜底在这台/这一帧上根本没在起作用」。
 * 间隔 15 帧之下，三次发火横跨约 1.5–2 秒 —— 一段真的能救的糊码不会连续三次都落在
 * 同一个救不回的瞬间。给到 3 而不是 2，是因为反光场景里 zxing-cpp 的 79.5% 意味着
 * 它**平均**五里失手一里，判早了就是把那两成的翻盘机会也剪了。
 */
internal const val SecondEngineGiveUpAfterMisses = 3

/**
 * 补解这一发的处境。调用点据此决定说什么：[Fire] 与 [Waiting] 不说话（前者由结果行说话，
 * 后者是「还没到出声的时候」—— 每帧一句会把 logcat 冲干净），其余每一档都必须说得出原因。
 */
internal sealed class SecondEngineDecision {
    /** 这一帧交给第二引擎补解（调用点随即用 [secondEngineAfterFire] 记账） */
    internal object Fire : SecondEngineDecision()

    /** 静默观望：连击没到线 / 间隔没到线。这是绝大多数帧的取值，不许留痕 */
    internal object Waiting : SecondEngineDecision()

    /** 闸门里已经有原文在手（或结果卡挂在屏上等用户按）：不补，补了也投不出去 */
    internal object CodeInHand : SecondEngineDecision()

    /** 本轮额头发完 */
    internal object OutOfBudget : SecondEngineDecision()

    /** 连续几次没解出来，本轮判死 */
    internal object GaveUp : SecondEngineDecision()

    /** 第二引擎自身不可用：构造失败过（缺 `.so`）或解帧抛过，本轮不再碰 */
    internal object EngineUnusable : SecondEngineDecision()
}

/**
 * 停法的措辞唯一来源（同 [analysisFrameLogText] 的口径：话怎么说只在这一处，
 * 调用点手拼字符串算第二份真相，守卫会红）。
 *
 * [SecondEngineDecision.Fire] 与 [SecondEngineDecision.Waiting] 返回 null：
 * 前者由结果那一行说话（带帧号与耗时才有账可对），后者是常态，按帧说话就是刷屏。
 * 剩下四档各一句实话，都点得出**数字**（额度、连击次数）与**后果**（本轮不再试），
 * 好让「这一轮兜底到底出没出过声」在 logcat 里读得出来。
 *
 * ⚠️ 这四句都不许指向任何一条已死的入口，也不许新增 UI 档位：第二引擎不可用**不改变
 * 用户手上还剩什么**（主力 ML Kit 照旧在跑），把兜底的缺席说成一句界面文案只是噪音。
 * 它是取证行，听众是读日志的人，不是举着手机的用户。
 */
internal fun secondEngineStopText(decision: SecondEngineDecision): String? = when (decision) {
    is SecondEngineDecision.Fire, is SecondEngineDecision.Waiting -> null
    is SecondEngineDecision.CodeInHand ->
        "第二引擎暂停：闸门里已经有原文或结果卡正等用户按，这时候补解投不出去"
    is SecondEngineDecision.OutOfBudget ->
        "本轮绑定的第二引擎额度已用完（$SecondEngineMaxFiresPerBind 次），之后只由 ML Kit 解帧"
    is SecondEngineDecision.GaveUp ->
        "第二引擎连续 $SecondEngineGiveUpAfterMisses 次没解出原文，本轮绑定不再请它补解（画面里那枚码它也没办法）"
    is SecondEngineDecision.EngineUnusable ->
        "第二引擎在这台设备上不可用（解码库没带这一档，或解码时抛了），本轮只用 ML Kit"
}

/**
 * 补解的账本。整枚换引用、字段全不可变（[ScanAssistState] / [ScanDecoderHealth] 同一口径）：
 * 只有分析线程读写它，所以不需要同步，但**必须**整枚换 —— 半改的账本会让「第几次发火」
 * 和「连续第几次失手」各说各话。
 *
 * 复位点是每轮绑定（`QrCodeAnalyzer.markBindStarted`）：新一轮绑定 = 新的视场、新的相机流，
 * 旧账本里的「额度用完」对不上新画面，那正是这一页修过三轮的「说了没做」。
 *
 * @param fires 本轮绑定已发出的补解次数（含正在进行的那一发）
 * @param misses 连续没解出原文的次数（一发命中即归零；[SecondEngineGiveUpAfterMisses] 判死）
 * @param lastFireFrame 上一次发火的帧号，-1 = 本轮还没发过火
 * @param unusableSeen 引擎是否已经报过不可用（构造抛过 / 解帧抛过）
 */
internal data class SecondEngineLedger(
    val fires: Int = 0,
    val misses: Int = 0,
    val lastFireFrame: Long = -1L,
    val unusableSeen: Boolean = false,
)

/**
 * 这一帧要不要交给第二引擎补解。**唯一**的发火判据，四道上界全在这里，调用点不参与判断。
 *
 * @param ledger 补解账本（只由分析线程持有）
 * @param streak 截至上一帧为止、[retryableRung] 的连续帧数（由 [ScanAssistState] 带过来的观测量）
 * @param frame 本帧帧号（[ScanRecoveryPolicy] 那枚全局帧号，同一把尺子）
 * @param codeInHand 闸门里是否已有原文 / 结果卡是否正等用户按（调用点读 [ScanHandled] 与
 *                   awaitingUserAction 当参数传，本函数不认识闸门）
 * @param engineUsable 第二引擎此刻是否可用（调用点读探针档位：未判定按可用传 ——
 *                     那一档的可用性由构造那一下现场判定，判不动自然会转成 unusableSeen）
 */
internal fun secondEngineDecision(
    ledger: SecondEngineLedger,
    streak: Long,
    frame: Long,
    codeInHand: Boolean,
    engineUsable: Boolean,
): SecondEngineDecision = when {
    // 次序是定的：先问「本轮到此为止」的三档终局（判死 / 引擎不在台上 / 额度用完），
    // 再问「有没有必要」（连击），最后问「值不值」（闸门里有没有原文、间隔到没到）。
    // 终局排前面是有意的：那三档一旦成立就说一句成句的实话（[secondEngineStopText]），
    // 把它排在连击后面，等于连击不归零时永远听不到「为什么这一轮不再试了」。
    // 连击那一档紧随其后，因为它是绝大多数帧的出口（画面好的时候 streak 一直是 0）。
    ledger.misses >= SecondEngineGiveUpAfterMisses -> SecondEngineDecision.GaveUp
    ledger.unusableSeen || !engineUsable -> SecondEngineDecision.EngineUnusable
    ledger.fires >= SecondEngineMaxFiresPerBind -> SecondEngineDecision.OutOfBudget
    streak < SecondEngineStreakFrames -> SecondEngineDecision.Waiting
    codeInHand -> SecondEngineDecision.CodeInHand
    ledger.lastFireFrame < 0L || frame - ledger.lastFireFrame >= SecondEngineFrameGap ->
        SecondEngineDecision.Fire
    else -> SecondEngineDecision.Waiting
}

/** 发火之后的账本：次数 +1、失手计数保留（这一发解没解出来由 [secondEngineAfterResult] 记）。 */
internal fun secondEngineAfterFire(ledger: SecondEngineLedger, frame: Long): SecondEngineLedger =
    ledger.copy(fires = ledger.fires + 1, lastFireFrame = frame)

/**
 * 一发补解回来之后的账本。
 *
 * [usable] 是引擎自身状态（构造失败过 / 解帧抛过 ⇒ false）：那种失手**同时**记进
 * [SecondEngineLedger.unusableSeen] —— 抛异常不叫「没解出来」，叫「它不在这台设备上」，
 * 判死的档位不同、说的话也不同（[SecondEngineDecision.EngineUnusable] vs [SecondEngineDecision.GaveUp]）。
 * 一发命中则连击失手清零（下一轮坏消息可以从头攒 3 次判死，别拿旧账判新一轮的场景）。
 */
internal fun secondEngineAfterResult(
    ledger: SecondEngineLedger,
    decoded: Boolean,
    usable: Boolean,
): SecondEngineLedger = ledger.copy(
    misses = when {
        decoded -> 0
        ledger.misses >= SecondEngineGiveUpAfterMisses -> ledger.misses
        else -> ledger.misses + 1
    },
    unusableSeen = ledger.unusableSeen || !usable,
)

/**
 * 第二引擎的探针档位 —— **三态**的口径与 [NativeLibVerdict] 完全一致，这里说清为什么必须是三态：
 * [Pending] 不是不可用。把未判定当成不可用，兜底就永远不会被叫醒（探针在绑定前不跑、
 * 绑定的时候还没判过）；把未判定当成可用，第一发补解就得现场去构造解码器 ——
 * 那正是本档要盖住的失效（缺库的 `UnsatisfiedLinkError` 必须落在我们自己的 try 里）。
 * 所以调用点的纪律是：**未判定当「可以试」**，试的那一下把结论做出来（[secondEngineProbeOnConstruct]），
 * 结论一旦落定就再也不改判。
 */
internal enum class SecondEngineProbe { Pending, Usable, Unusable }

/**
 * 构造解码器那一下 ⇒ 档位（true = 构造成功）。
 *
 * 存在的理由是把「怎么判可用」这条判据也收进内核：`System.loadLibrary` 写在 zxing-cpp
 * Android wrapper 的 `BarcodeReader` 构造函数里（其 `init` 块，v3.1.1 源码核过），
 * 所以**能不能构造 = 那颗 `.so` 在这台设备上加载得起来吗**，这是唯一不算猜的探法
 * （[BarhopperNativeLibProbe] 的同一套道理：`Build.SUPPORTED_ABIS` 答的是设备支持什么、
 * 不是这份包里带了什么）。调用点只许把构造结果（布尔）递进来，档位翻面在这颗函数里判。
 */
internal fun secondEngineProbeOnConstruct(constructed: Boolean): SecondEngineProbe =
    if (constructed) SecondEngineProbe.Usable else SecondEngineProbe.Unusable
