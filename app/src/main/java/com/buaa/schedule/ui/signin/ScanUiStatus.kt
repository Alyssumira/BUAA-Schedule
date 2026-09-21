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
 * @param scannerUsable 解码器可用（= `scanner != null && scannerWorking`，调用点合成）：
 *   建不出来、或跑起来之后自己报坏了，都算这一档不成立。跑坏了那一支相册确实还承诺得起
 *   （scanner 在，只是相机那条先停用）；文案按这条出口写。
 * @param granted 相机权限。相册识别不需要它，所以这一档的出口是"放行 + 相册"两条。
 * @param cameraError 失败原因的原文。它有**两个写点**（SpocScanScreen 的绑定失败与相册
 *   读图失败），后者恰恰是相册刚失败 —— 所以这一档按来源分两支说话，见函数体注释。
 * @param cameraProviderMissing 相机服务一次都没把 provider 交出来（含超时与重试过那一次）
 */
internal fun scanUiStatus(
    decoderMissing: Boolean,
    scannerUsable: Boolean,
    granted: Boolean,
    cameraError: String?,
    cameraProviderMissing: Boolean,
): String? = when {
    // 解码器整条链都不在（T24）：相机与相册用的是同一颗 scanner，两条一起没。
    // 手输入口删除后这一档**没有任何出路可指** —— 提相册、提输入都是谎话，
    // 这一条被 BarhopperNativeLibProbeTest ⑦ 按子串钉着（相册/手输/输入都不许出现）。
    decoderMissing -> "这份安装包没带这台设备那一档的扫码解码库，这一页的两条解码路径都没有，在这台设备上用不了扫码签到。"
    // scanner 建不出来时相册那颗按钮也灭着（enabled 同键）；这一档主要说的是
    // "scanner 在、相机那条跑坏了"——那时相册确实还是能走的路。
    !scannerUsable -> "这台设备用不了相机扫码，请改用相册识别。"
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
 */
internal fun scanCameraLive(
    scannerAvailable: Boolean,
    analyzerReady: Boolean,
    cameraProviderReady: Boolean,
    granted: Boolean,
    scannerWorking: Boolean,
    cameraError: String?,
): Boolean =
    scannerAvailable && analyzerReady && cameraProviderReady && granted && scannerWorking && cameraError == null
