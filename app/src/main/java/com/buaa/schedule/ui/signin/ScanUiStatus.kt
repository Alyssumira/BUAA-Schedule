package com.buaa.schedule.ui.signin

/**
 * 扫码页降级口径的判据：底部那颗提示条说什么、取景框还画不画。
 *
 * 零 android import（仓库口径同 [ScanSubmissionGate]：权限/设备事实在调用点读出来当参数，
 * 判据本体只吃参数），所以每一档都能在 JVM 单测里逐支跑一遍 —— 见
 * `ScanUiStatusTest`。这一页的文案是唯一可靠的取证面：用户那台机器（HyperOS）把 logcat
 * 砍到 Info 级、release 又剥掉 Verbose，`Log.d` 那句在那里根本读不出来。
 */

/**
 * 提示条文案，按"用户下一步能做什么"排序（谁的出口最近谁在前）。
 *
 * 返回 null = 一切正常，不提示。
 *
 * 这一页只有两条入口：相机实时解码、相册识图（2026-09-21 删掉了第三条「手输签到码」——
 * 现实里不存在可抄的签到码）。两条入口共用同一颗 scanner，所以缺解码库时**两条一起没**：
 * 文案体系里最顶那一档不再有出路可指，只许说实话，不许指向任何东西。
 *
 * `cameraProviderMissing` 是这一版补上的一档（D2）。此前 `ProcessCameraProvider` 取不到
 * （future 抛错、或干脆永不完成）既不进 [cameraError]（那是 `bindToLifecycle` 才写得动的字段），
 * 也不在任何一支判断里 —— 于是提示条空着、`cameraLive` 还成立（它当时不看 provider），
 * 取景框就画在一块永远不会有画面的黑 `PreviewView` 上，整页看起来"一切正常"。
 * 这一档必须自己说话。
 *
 * @param decoderMissing 探针已判定：这份安装包没带这台设备的解码库
 * @param scannerUsable 相机这条现在能不能用（= `scanner != null && scannerWorking`，调用点合成）。
 *   它不成立有**三种**病因，措辞各不相同，别捏成一句：
 *   - scanner **建不出来**（`scanner == null`）：相册用的是同一颗 scanner，一起没 ⇒ 走
 *     [galleryUsable] 为 false 的那一支，点名两条都没了、不许指任何出路；
 *   - **停用窗口内**（暂时）：解码器在坏但内核正在自动试回（窗口过完放一帧去探、解出来一次
 *     就整枚清零）⇒ 走 [scannerGiveUp] 为 false 的那一支，说"在重试"；
 *   - **本轮判死**（试回轮数用完等）⇒ 走 [scannerGiveUp] 为 true 的那一支，既有那句
 *     "这台设备用不了相机扫码"描述的正是这一档的事实。
 * @param galleryUsable 相册那条路还在不在（= `scanner != null`，调用点读设备事实当参数传）。
 *   相机与相册共用同一颗 scanner，所以 scanner **跑坏了**（`scannerWorking == false`）时相册
 *   确实还是真出路，那一支指相册；scanner **建不出来**（`scanner == null`）时相册那颗按钮的
 *   `enabled` 同键一起灭，那一支再指相册就是谎话 —— 与 [decoderMissing] 同一口径，只说实话、
 *   两条一起没、不许指任何出路。
 * @param scannerGiveUp 相机这条是**判死**还是**暂时停用**（取值走 ScanRecoveryPolicy 的
 *   `scannerGiveUp(health)`，判据不在这一页，UI 侧也不许直接读 `giveUpReason` 拼分支）。
 *   暂时那一档的措辞纪律：说在重试、不承诺时间（"约 1 秒"这种话一旦帧率不对就是新的假话）、
 *   不指使用户去设置里找东西；指相册是实话（这一档 galleryUsable 为真）。
 *   判死那一档保留既有字面量不动（`ScanUiStatusTest` 与入口接线守卫都按它扫）。
 * @param granted 相机权限。相册识别不需要它，所以这一档的出口是"放行 + 相册"两条。
 * @param cameraError 失败原因的原文。它有**两个写点**（SpocScanScreen 的绑定失败与相册
 *   读图失败），后者恰恰是相册刚失败 —— 所以这一档按来源分两支说话，见函数体注释。
 * @param cameraProviderMissing 相机服务一次都没把 provider 交出来（含超时与重试过那一次）
 */
internal fun scanUiStatus(
    decoderMissing: Boolean,
    scannerUsable: Boolean,
    scannerGiveUp: Boolean,
    galleryUsable: Boolean,
    granted: Boolean,
    cameraError: String?,
    cameraProviderMissing: Boolean,
): String? = when {
    // 解码器整条链都不在（T24）：相机与相册用的是同一颗 scanner，两条一起没。
    // 手输入口删除后这一档**没有任何出路可指** —— 但必须把"哪两条路没了"点名说清楚，
    // 用户看"两条解码路径"是不知道指的是什么的。守的是 BarhopperNativeLibProbeTest ⑦：
    // 陈述"相册也解不出"是实话，**指向**相册（改用相册 / 从相册选…）才是谎话，禁的是指向语。
    decoderMissing -> "这份安装包没带这台设备那一档的扫码解码库，相机实时扫码和相册识别都解不出二维码，这台设备上用不了扫码签到。"
    // scannerUsable 不成立有三种病因（建不出来 / 暂时停用 / 判死，见 @param scannerUsable），
    // 措辞必须分开 —— T59b② 之前"暂时停用"也被说成"这台设备用不了"，那是这句假话的出处：
    // - scanner == null（建不出来）：相册同键同灭 ⇒ 与缺库同一口径，不许指任何出路。
    // - 判死（giveUp）：既有那句"这台设备用不了相机扫码"，字面量不动（守卫按它扫）。
    // - 停用窗口内（暂时）：内核正在按有界的窗口自动试回 ⇒ 说在重试、不承诺时间、
    //   不指使去设置；这一档 galleryUsable 必为真（scanner 还在），指相册是实话。
    !scannerUsable ->
        if (!galleryUsable) {
            "这台设备的扫码解码器建不起来，相机实时扫码和相册识别都用不了，这一页在这台设备上用不了扫码签到。"
        } else if (scannerGiveUp) {
            "这台设备用不了相机扫码，请改用相册识别。"
        } else {
            "相机扫码暂时解不出码，正在自动重试，也可以改用相册识别。"
        }
    // 相册不需要相机权限，所以放行与相册两条出口在这档都成立。
    !granted -> "没有相机权限，无法扫码。请在系统设置里放行，或改用相册识别（相册不需要相机权限）。"
    // 绑定失败与 provider 缺失可以同时成立吗？不能：cameraError 只在 provider 已经拿到手之后
    // 才写得进去。但相册那条"读不出那张图"也借这个字段（SpocScanScreen 里两处写：
    // 绑定 onFailure 与 galleryLauncher 的 onFailure），它跟 provider 无关，
    // 所以这一支排在前面，让那条更具体的原因先说。
    // ⚠️ 必须按来源分两支：cameraError 以「读不出那张图」开头 ⇒ 相册刚刚才失败，
    // 再说"改用相册"就是让用户原地绕圈，那一支只能说换图/重扫；其余（绑定失败）才指相册。
    cameraError != null ->
        if (cameraError.startsWith(GalleryUnreadablePrefix)) {
            "这张图读不出来，换一张图，或重新对准二维码再扫。"
        } else {
            "相机不可用（$cameraError），请改用相册识别。"
        }
    cameraProviderMissing -> "相机服务没把摄像头交给这一页（CameraX 起不来），实时扫码开不起来。请改用相册识别。"
    else -> null
}

/**
 * 相册"读不出那张图"写进 cameraError 的前缀。
 *
 * 它是 scanUiStatus 那一档分两支的**唯一**判据：cameraError 有两个写点（SpocScanScreen 的
 * 绑定 onFailure、galleryLauncher 的读图 onFailure），只有后者以它开头 —— 而 SpocScanScreen
 * 那一句必须用这枚常量拼，不许各写一份字面量，漂移了分支就静默失效。
 */
internal const val GalleryUnreadablePrefix = "读不出那张图"

/**
 * 相机这一档是否真的在跑：取景框只在它成立时画，退化到相册时再压一层暗区只是噪音。
 *
 * 旧写法漏了 `provider` 与 `analyzer` 两项（D2）：那两样缺一样都是"预览不会再有帧"，
 * 而它照旧返回 true，等于把一块黑屏标成正常。
 *
 * T59b②："活不活"这一项从 `scannerWorking` 换成**判死**（`scannerGiveUp`）。停用窗口里
 * 相机**故意不 unbind**（帧必须继续到达，内核才有"窗口过完"的观测量），预览活着、马上要
 * 试回来 —— 那一档收掉取景框就是把暂时态演成新症状，还会闪一下 1~3 秒；判死之后照旧收掉。
 */
internal fun scanCameraLive(
    scannerAvailable: Boolean,
    analyzerReady: Boolean,
    cameraProviderReady: Boolean,
    granted: Boolean,
    scannerGiveUp: Boolean,
    cameraError: String?,
): Boolean =
    scannerAvailable && analyzerReady && cameraProviderReady && granted && !scannerGiveUp && cameraError == null

/**
 * T65① 新增：「画面里有码、但还没解开」两档的提示措辞，唯一来源。
 *
 * 与 [scanUiStatus] 的分工：那颗说**结构性**降级（哪条路没了、没权限、绑定失败），
 * 这一颗说**当前取景**的可救处境（帧里有一枚候选码却没解开，是太小还是没解开）。
 * 调用点排他使用：提示条已经由 [scanUiStatus] 说话时这一颗禁声（结构性降级面前
 * 讲"走近一点"是让人对着死相机凑距离）。档位判据在 [frameCodeRung]（帧质量内核），
 * 这里只负责话怎么说 —— 页面扣不住任何一份措辞字面量，`ScanUiStatusTest` ⑤ 盯着。
 *
 * 措辞纪律：两档都得给出用户下一步真做得到的动作（凑近 / 拿稳对住），不承诺时间、
 * 不指使去设置、不提任何不存在的硬件（本页已无补光手段，「照亮」那类话从这里起不许出现）。
 *
 * @param rung 已过滞后的屏上观测档位（[ScanAssistState.shownRung] 的取值）
 * @return null = 这一档不需要说话（读到了码 / 什么都没看见 —— 后者是瞄的问题，
 *   由取景框本身回答，提示条对着空画面说"没看见"只会按帧闪）
 */
internal fun scanFrameAidText(rung: FrameCodeRung): String? = when (rung) {
    FrameCodeRung.CodeTooSmall -> "看见二维码了，但它小到解不出来：请走近一点，或把码对准取景框正中。"
    FrameCodeRung.CodeUndecodable -> "看见二维码了，但一时解不开：请拿稳对准它，或换一张更清晰的码。"
    FrameCodeRung.CodeReadable -> null
    FrameCodeRung.NothingDetected -> null
}
