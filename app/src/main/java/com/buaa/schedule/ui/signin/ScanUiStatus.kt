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
 * `cameraProviderMissing` 是这一版补上的一档（D2）。此前 `ProcessCameraProvider` 取不到
 * （future 抛错、或干脆永不完成）既不进 [cameraError]（那是 `bindToLifecycle` 才写得动的字段），
 * 也不在任何一支判断里 —— 于是提示条空着、`cameraLive` 还成立（它当时不看 provider），
 * 取景框就画在一块永远不会有画面的黑 `PreviewView` 上，手输签到码还被压成最弱一档，
 * 整页看起来"一切正常"。这一档必须自己说话。
 *
 * @param decoderMissing 探针已判定：这份安装包没带这台设备的解码库
 * @param scannerUsable 解码器可用（= `scanner != null && scannerWorking`，调用点合成）：
 *   建不出来、或跑起来之后自己报坏了，都算这一档不成立 —— 两种情形给用户的出口是同一组
 *   （相册那条用的是同一颗 scanner，所以 scanner 建不出来时它也一起没），
 *   文案与改动前一致。scanner 在而相机不在时相册仍然承诺得起，所以这句不提权限。
 * @param granted 相机权限
 * @param cameraError 绑定阶段的失败原因（没有后置摄像头、或那张图读不出来）
 * @param cameraProviderMissing 相机服务一次都没把 provider 交出来（含超时与重试过那一次）
 */
internal fun scanUiStatus(
    decoderMissing: Boolean,
    scannerUsable: Boolean,
    granted: Boolean,
    cameraError: String?,
    cameraProviderMissing: Boolean,
): String? = when {
    // 解码器整条链都不在（T24）：相机与相册用的是同一个 scanner，
    // 再提"从相册选那张二维码"就是把用户往死路上引 —— 只剩手输
    decoderMissing -> "这份安装包没带这台设备那一档的扫码解码库，相机和相册都解不出二维码，只能手输签到码。"
    !scannerUsable -> "这台设备用不了相机扫码，请从相册选那张二维码，或直接输入签到码。"
    !granted -> "没有相机权限，无法扫码。请在系统设置里放行，或改用下面两个入口。"
    // 绑定失败与 provider 缺失可以同时成立吗？不能：cameraError 只在 provider 已经拿到手之后
    // 才写得进去。但相册那条"读不出那张图"也借这个字段，它跟 provider 无关，
    // 所以这一支排在前面，让那条更具体的原因先说。
    cameraError != null -> "相机不可用（$cameraError），请改用下面两个入口。"
    cameraProviderMissing -> "相机服务没把摄像头交给这一页（CameraX 起不来），实时扫码开不起来。请从相册选那张二维码，或用手输签到码。"
    else -> null
}

/**
 * 相机这一档是否真的在跑：取景框只在它成立时画，退化到相册/手输时再压一层暗区只是噪音。
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
