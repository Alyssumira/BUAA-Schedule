package com.buaa.schedule.ui.signin

/**
 * 扫码页「喂给解码器的帧」这一侧的判据内核（T64）。纯判据，零 import（仓库口径，
 * 见 [ScanRecoveryPolicy] 与 `SpecialDayBadgePolicy`）：相机能力（有没有闪光灯、缩放范围、
 * 支不支持测光）、权限、帧的真实尺寸、视口与视图的实测尺寸 —— 一律由调用点读出来当参数传进来，
 * 本文件只吃参数、只翻档位，绝不自己去碰设备。
 *
 * 这一页被报「扫码没反应」三次。T44/T59/T59b 修的全是**观测面**（每条死路都要说得出哪一段死了、
 * 恢复棘轮收进零 import 的内核），从没有人动过**送进解码器的帧的质量**。
 * 事实是：CameraX 分析流的默认交付尺寸是 640×480，而 ML Kit 文档要求条码里最小的有意义单元
 * 至少 2 px 宽（二维码是二维的，还要 2 px 高），并建议喂 1280×720 或 1920×1080 ——
 * 只有在「码几乎占满画面」时才允许用更低档。1–3 米外看投影仪上的签到二维码，恰恰是
 * 「码只占画面一小部分」的场景：模块在被降采样那一刻就毁了，之后任何解码器都救不回来。
 * 本内核管五件事：
 * ① 交付的帧够不够（[analysisFrameVerdict] / [analysisFrameLogText]，取证行的措辞唯一来源）；
 * ② 点哪对哪：把取景画面上的点击映射成分析流坐标系里的测光点（[analysisMeteringPointForTap]）；
 * ③ 手电按钮该不该出现、写什么（[torchAffordance]）；
 * ④ 用户要的缩放比值钳到这台机器的合法区间（[clampedZoomRatio]）；
 * ⑤ 画面里到底有没有码、有的话为什么解不开（[frameCodeRung]），以及据此驱动的检测缩放阶梯
 *    （[advanceScanAssist] / [zoomLadderRatio] —— 取代 T64「每次绑定固定抬一档」的判据本体）。
 */

// ---- ① 交付帧够不够 ----

/** 向 CameraX 请求的分析流目标尺寸（宽）：ML Kit 文档推荐的最低一档就是 1280×720 */
internal const val RequestAnalysisWidthPx = 1280

/** 向 CameraX 请求的分析流目标尺寸（高）：与 [RequestAnalysisWidthPx] 同一条请求 */
internal const val RequestAnalysisHeightPx = 720

/** ML Kit 文档的下限：条码中最小的有意义单元至少 2 px（宽与高都要） */
internal const val MinModuleSizePx = 2

/**
 * 判据采用的 QR 边长模块数：版本 20 = 97 模块/边。
 *
 * 为什么不取极端：版本 40（177 模块）会把下限推到这一页永远够不着；版本 4（33 模块）
 * 又把线放到 640×480 都能过关，等于没放。签到码是长 URL，v10–v20 是实际会遇到的上档，
 * 取 97 是「常见长链接 QR 的实际上界」。改动这个数字必须连下面 [DistantCodeHoleFill] 一起重算。
 */
internal const val QrModuleSideBudget = 97

/**
 * 远距场景里码在取景洞里最多占几成：0.5。
 *
 * 1–3 米看投影：学生对准的是**整块投影**，签到码往往只占投影的一角 —— 码到不了洞的一半是常态，
 * 判据按「最多占半洞」算，是保守里偏乐观的取法（占得更少只会让帧更不够，不会更够）。
 */
internal const val DistantCodeHoleFill = 0.5f

/**
 * 交付帧短边的可用下限（px），由上面三个常量算出来，不是拍脑袋的魔数：
 *
 * `MinModuleSizePx × QrModuleSideBudget ÷ (DistantCodeHoleFill × viewfinderSideRatio)`
 * = 2 × 97 ÷ (0.5 × 0.62) ≈ 626 px。
 *
 * 推导链：ML Kit 要求每个模块 ≥2 px ⇒ 一枚 v20 码的码体至少要 194 px；
 * 远距时码最多占取景洞的一半、洞的边长是视口短边的 [ViewfinderSideRatio 档位]（本页 0.62），
 * 而 FILL_CENTER 只裁不缩 —— 洞映射进分析帧的边长 ≤ 视口短边占比 × 帧短边 ⇒
 * 帧短边必须 ≥ 194 ÷ (0.5 × 0.62)。按这个判据：CameraX 默认交付的 640×480 短边 480 **不过线**
 * （这正是「远距扫不出来」的头号现行根因），而 1280×720 短边 720 过线。
 */
internal fun minUsefulAnalysisShortEdgePx(viewfinderSideRatio: Float): Int {
    if (!viewfinderSideRatio.isFinite() || viewfinderSideRatio <= 0f || viewfinderSideRatio > 1f) {
        // 传进来的是退化值就拿请求目标兜底（宁可要求高，不可把不够的帧判成够）
        return RequestAnalysisHeightPx
    }
    val codeBodyPx = (MinModuleSizePx * QrModuleSideBudget).toFloat()
    val raw = codeBodyPx / (DistantCodeHoleFill * viewfinderSideRatio)
    val floor = raw.toInt()
    return if (floor < raw) floor + 1 else floor
}

/** 交付帧的档位。取证行与（未来可能的）提示条都只许按这四档说话。 */
internal enum class AnalysisFrameVerdict {
    /** 宽或高 ≤ 0：ImageProxy 本身不可信，先查管线再谈别的 */
    BrokenFrame,

    /** 短边低于 [minUsefulAnalysisShortEdgePx]：远距码的模块会被降到 2 px 以下，解码器救不回来 */
    BelowFloor,

    /** 过了下限但没到请求目标（设备只给了 4:3 的 960×540 这类）：可用，远距余量小 */
    MeetsFloor,

    /** 短边 ≥ 720：请求实质兑现，远距投影码有 ≥2 px/模块的余量 */
    MeetsRequest,
}

/** 档位判据本体。只吃交付尺寸与取景洞声明的边长占比，不读任何设备事实。 */
internal fun analysisFrameVerdict(
    widthPx: Int,
    heightPx: Int,
    viewfinderSideRatio: Float,
): AnalysisFrameVerdict {
    if (widthPx <= 0 || heightPx <= 0) return AnalysisFrameVerdict.BrokenFrame
    val shortEdge = if (widthPx < heightPx) widthPx else heightPx
    return when {
        shortEdge >= RequestAnalysisHeightPx -> AnalysisFrameVerdict.MeetsRequest
        shortEdge >= minUsefulAnalysisShortEdgePx(viewfinderSideRatio) -> AnalysisFrameVerdict.MeetsFloor
        else -> AnalysisFrameVerdict.BelowFloor
    }
}

/**
 * 取证行里「这一行说的是不是实话」的那半句。每绑定一次调一次，按帧路径不碰它。
 *
 * ⚠️ 调用点的 `Log.i` 不许自己拼尺寸比较（内核对零命中/手拼字符串有守卫，见
 * `ScanCameraAidPolicyTest` 的接线档）：话怎么说全在这里，改口径只改这一处。
 */
internal fun analysisFrameLogText(
    widthPx: Int,
    heightPx: Int,
    viewfinderSideRatio: Float,
): String {
    val floor = minUsefulAnalysisShortEdgePx(viewfinderSideRatio)
    val requested = "${RequestAnalysisWidthPx}×${RequestAnalysisHeightPx}"
    val delivered = "${widthPx}×${heightPx}"
    return when (analysisFrameVerdict(widthPx, heightPx, viewfinderSideRatio)) {
        AnalysisFrameVerdict.BrokenFrame ->
            "尺寸非法，这一帧本身不可信，先查相机管线"
        AnalysisFrameVerdict.BelowFloor ->
            "请求 $requested、实际 $delivered：短边低于 ${floor}px 下限 —— 远距投影码的模块会被降采样到 " +
                "小于 ${MinModuleSizePx}px/模块，任何解码器都救不回来（「扫码没反应」最可能的现行根因）"
        AnalysisFrameVerdict.MeetsFloor ->
            "请求 $requested、实际 $delivered：过了 ${floor}px 下限但没到 720 档 —— 近距够用，远距余量小"
        AnalysisFrameVerdict.MeetsRequest ->
            "请求 $requested、实际 $delivered：短边已到 ${RequestAnalysisHeightPx}px，远距码有 ≥${MinModuleSizePx}px/模块的余量"
    }
}

// ---- ② 点击 → 分析流坐标系的测光点 ----

/**
 * 测光点映射结果：`x`/`y` 是**归一化**（0f..1f）的「整幅已旋转分析画面」坐标，
 * 调用点把它乘进 `SurfaceOrientedMeteringPointFactory(视口宽, 视口高, 分析流)` 的空间再 createPoint。
 *
 * ⚠️ 那颗 factory 必须用**分析流**构造：两参构造默认按「当前活跃 Preview」的画幅换算，
 * 而这一页要测光的正是分析流的传感器裁切区 —— 画幅不一致时同一个点位差的就是那条缝。
 */
internal class MeteringNormalized(val x: Float, val y: Float)

/**
 * 把取景画面上的一次点击换算成分析流坐标系的测光点。
 *
 * 几何口径（从 CameraX 源码核实过的事实）：`PreviewView.ScaleType.FILL_CENTER` 是**纯显示端
 * 变换** —— 它不改拍摄请求，分析帧携带的是会话的全视场，只是画面被放大裁切后显示。
 * 所以屏幕坐标 → 全幅图像坐标必须自己补上那次「放大 + 居中裁切」的逆变换：
 * 缩放 `scale = max(视口宽/画面宽, 视口高/画面高)`，可见窗 = 视口/scale，居中偏移 = (画面-可见窗)/2。
 * 点击落在可见窗外（退化输入的显式「画外」档）返回 null，绝不夹到边缘上 ——
 * 夹了等于把用户点在黑边上的意图翻译成画面正中的对焦，那是新的假动作。
 *
 * @param tapX/tapY 点击在**视口**里的像素坐标（0 起）
 * @param viewWidthPx/viewHeightPx 视口像素尺寸
 * @param imageWidthPx/imageHeightPx **传感器方向**的分析帧尺寸（`ImageProxy.width/height` 原值）
 * @param rotationDegrees 该帧的 `rotationDegrees`（0/90/180/270 之外一律拒映射）
 * @return null = 拒绝映射（点在界面上、尺寸退化、旋转值不认识）
 */
internal fun analysisMeteringPointForTap(
    tapX: Float,
    tapY: Float,
    viewWidthPx: Float,
    viewHeightPx: Float,
    imageWidthPx: Int,
    imageHeightPx: Int,
    rotationDegrees: Int,
): MeteringNormalized? {
    if (!tapX.isFinite() || !tapY.isFinite()) return null
    if (viewWidthPx <= 0f || viewHeightPx <= 0f) return null
    if (imageWidthPx <= 0 || imageHeightPx <= 0) return null
    if (tapX < 0f || tapX > viewWidthPx || tapY < 0f || tapY > viewHeightPx) return null
    val rot = ((rotationDegrees % 360) + 360) % 360
    // 显示端画面是「已旋转到视口方向」的：90/270 时宽高互换
    val displayW = if (rot == 90 || rot == 270) imageHeightPx.toFloat() else imageWidthPx.toFloat()
    val displayH = if (rot == 90 || rot == 270) imageWidthPx.toFloat() else imageHeightPx.toFloat()
    if (rot != 0 && rot != 90 && rot != 180 && rot != 270) return null
    val scale = maxOf(viewWidthPx / displayW, viewHeightPx / displayH)
    val windowW = viewWidthPx / scale
    val windowH = viewHeightPx / scale
    val offsetX = (displayW - windowW) / 2f
    val offsetY = (displayH - windowH) / 2f
    val u = (offsetX + tapX / scale) / displayW
    val v = (offsetY + tapY / scale) / displayH
    // 显式「画外」档：FILL_CENTER 下正常输入不会越界，越界只可能是退化/被绕过的口径
    if (u < 0f || u > 1f || v < 0f || v > 1f) return null
    return MeteringNormalized(u, v)
}

// ---- ③ 手电按钮该不该出现、写什么 ----

/** 与 TorchState 的三个取值同码（0/1/2），这样调用点把 `cameraInfo.torchState` 的 Int 原样传进来即可 */
internal const val TorchStateUndefined = 0

/** 同上：关 */
internal const val TorchStateOff = 1

/** 同上：开 */
internal const val TorchStateOn = 2

/** 手电档位：show = 按钮画不画，label = 按钮上的话。UI 可见性判据，不是设备读取。 */
internal enum class TorchAffordance(val show: Boolean, val label: String) {
    /** 这台没有闪光灯 LED：按钮根本不该存在（TorchControl 在没有灯的单位上会以 IllegalStateException 失败 future，绝不能让它复活） */
    HiddenNoFlash(false, ""),

    /** 相机这条已经判死（本轮不再自动试回）：开灯照不亮一颗死掉的解码器，别演「还能救」 */
    HiddenDecoderDead(false, ""),

    /** 相机路径没在跑（没权限 / provider 没拿到 / 绑定失败 / 停用窗口）：灯开了也没有帧可照 */
    HiddenCameraNotLive(false, ""),

    /** 现在关着（含状态未知）：按下去是「开」 */
    ShowTurnOn(true, "开手电"),

    /** 现在开着：按下去是「关」 */
    ShowTurnOff(true, "关手电"),
}

/**
 * 手电按钮的档位。次序是有讲的：没有灯 ⇒ 永远隐藏；本轮判死排在「路径活不活」之前 ⇒
 * 停用窗口里路径是活的（故意不 unbind），用户仍然可以开灯试试；判死了就不用试了。
 *
 * @param hasFlashUnit `cameraInfo.hasFlashUnit()` 的原文
 * @param torchStateCode `cameraInfo.torchState` 最新值（[TorchStateUndefined]/[TorchStateOff]/[TorchStateOn]）
 * @param cameraPathLive 相机这条路径现在是否在跑（调用点与取景框用同一颗判据）
 * @param roundGivenUp 本轮是否已判死（[ScanRecoveryPolicy] 那一档的快照）
 */
internal fun torchAffordance(
    hasFlashUnit: Boolean,
    torchStateCode: Int,
    cameraPathLive: Boolean,
    roundGivenUp: Boolean,
): TorchAffordance = when {
    !hasFlashUnit -> TorchAffordance.HiddenNoFlash
    roundGivenUp -> TorchAffordance.HiddenDecoderDead
    !cameraPathLive -> TorchAffordance.HiddenCameraNotLive
    torchStateCode == TorchStateOn -> TorchAffordance.ShowTurnOff
    else -> TorchAffordance.ShowTurnOn
}

/** 这一档按下去要把手电置成什么。隐藏档没有按钮，取值无意义（给 false 让它至少是个确定的）。 */
internal fun torchTargetState(affordance: TorchAffordance): Boolean =
    affordance == TorchAffordance.ShowTurnOn

// ---- ④ 缩放比值钳制 ----

/**
 * 把用户要的缩放比值钳到这台机器的合法区间。返回 null = **这台没有缩放控制**（显式档，
 * 调用点只许按 null 不出现档处理，不许回头再自己查一次设备）：
 * - ZoomState 没报出 min/max（有的设备/有的时刻就是拿不到）；
 * - min ≤ 0 或 max < min（数据不可信）；
 * - max ≤ min（固定变焦，min==max 是这类设备的常态写法，`setZoomRatio` 对它没有意义）。
 *
 * ⚠️ [requestedRatio] 非有限值按 1f（不动）处理而不是 null：NaN 是调用方的 bug，
 * 不该被翻译成「这台没缩放控制」这种对设备说谎的结论。
 */
internal fun clampedZoomRatio(
    requestedRatio: Float,
    minZoomRatio: Float?,
    maxZoomRatio: Float?,
): Float? {
    val safeRequest = if (requestedRatio.isFinite()) requestedRatio else 1f
    val min = minZoomRatio ?: return null
    val max = maxZoomRatio ?: return null
    if (!min.isFinite() || !max.isFinite()) return null
    if (min <= 0f || max <= min) return null
    return safeRequest.coerceIn(min, max)
}

// ---- ⑤ 画面里有没有码、有码为什么解不开 ----

/**
 * 判档采用的 px/模块：3。
 *
 * 出处分两层：ML Kit 文档的 2 px 是「最小有意义单元」的**存在性下限**，不是识别率下限；
 * 同行实测（经审阅的度量口径）把可用识别推到 **≥3 px/模块**。档位判据按严的那条取：
 * 已经小到 2 px 的码本来就解不开，把「按 3 px 才算数」错判成「还能救」只会让用户多举着手机
 * 白等 —— 这一档判错的代价是文案，[MinUsefulCandidateBoxPx] 判错的代价是白抬视场，两笔都要算。
 */
internal const val UsefulModulePx = 3

/**
 * 「看见了码但太小」的候选框短边阈值（px，**已旋转的分析画面坐标系**）：
 *
 * `UsefulModulePx × QrModuleSideBudget` = 3 × 97 = **291 px**。
 *
 * 推导链（与 ① 那本账同一个预算，不另起炉灶）：交付帧实测 1280×960（旋转后 960×1280 档）、
 * 取景洞短边占比 [ViewfinderSideRatio 档位 0.62] ⇒ 洞映射进帧约 595 px 短边；一枚按预算
 * 取满 v20（97 模块/边）的签到码要填满整个洞才够 3 px/模块（595÷97≈6.1，占洞六成以下就掉到
 * 3 px 以下）。短边不足 291 px 的候选框连 v20 预算的三分之一都撑不满 —— 这种帧里没解开，
 * 第一现行犯是**尺寸**，缩放有得救。到线以上的框连最坏的预算码都放得下，还没解开就不该再
 * 怪尺寸（对不上焦或被抖动糊掉），抬视场只是白抬。
 */
internal const val MinUsefulCandidateBoxPx = UsefulModulePx * QrModuleSideBudget

/** 一帧观测的档位。UI 措辞（[scanFrameAidText]）与缩放阶梯都只许按这四档说话。 */
internal enum class FrameCodeRung {
    /** 这一帧至少解开了一枚有原文的码：什么都不用帮 */
    CodeReadable,

    /** 一枚候选都没看见：是瞄的问题，不是帧的问题，不许据此抬缩放 */
    NothingDetected,

    /** 看见了候选码但框太小（[MinUsefulCandidateBoxPx] 以下）：缩放阶梯唯一真正的那一档 */
    CodeTooSmall,

    /** 框已经够大（或候选框尺寸不可信）却还没解开：焦点/抖动问题，抬缩放帮不上，只出文案 */
    CodeUndecodable,
}

/**
 * 一帧的紧凑测量 → 档位。测量由调用点从 ML Kit 的结果里抠出来当参数传（仓库口径：
 * [Barcode.boundingBox] 只在调用点读，本文件不认识 Barcode 这个类）。
 *
 * 前提是 scanner 开了 `enableAllPotentialBarcodes()`： bundled 实现真的兑现这颗开关
 * （其字节码引用 PotentialBarcode），「检测到但解不开」的候选会带着框进来 —— 而 ML Kit 的
 * 自动缩放建议（ZoomSuggestionOptions）只活在 play-services 薄壳里，我们这条 bundled 路径
 * 上它是**静默 no-op**，所以测量与阶梯都得上在这里。
 *
 * ⚠️ 框坐标在**已旋转**的分析画面空间（`InputImage.fromMediaImage(media, rotationDegrees)`
 * 之后那幅），调用点原样报短边即可，这里不再做任何几何变换。
 * 退化档（有候选但框缺失/非正数）落 [CodeUndecodable] 而不是 [CodeTooSmall]：
 * 「太小」是一个要驱动抬视场的断言，拿不可信的测量抬视场，错的就是画面。
 */
internal fun frameCodeRung(
    readableCodeCount: Int,
    candidateCodeCount: Int,
    largestCandidateBoxShortEdgePx: Int,
): FrameCodeRung {
    if (readableCodeCount > 0) return FrameCodeRung.CodeReadable
    if (candidateCodeCount <= 0) return FrameCodeRung.NothingDetected
    if (largestCandidateBoxShortEdgePx <= 0) return FrameCodeRung.CodeUndecodable
    if (largestCandidateBoxShortEdgePx >= MinUsefulCandidateBoxPx) return FrameCodeRung.CodeUndecodable
    return FrameCodeRung.CodeTooSmall
}

/**
 * 检测驱动缩放阶梯的档位表（×）。基线（不抬）是 step 0，不在表里；表长就是最大档数。
 *
 * 步长 +0.25 的账：每上一档码的线性尺寸约 +20%（框面积 ×1.44+），恰好是「差半档到一档」
 * 的量级 —— [MinUsefulCandidateBoxPx] 那本账从 291 px 到洞满 595 px 之间只够走四档，
 * 步长再大就会一步跨过线、退回来时永远差一点。上界 2.0× 的理由：再收视场，取景洞
 * （短边占比 0.62）就盖过整块投影，瞄不准的直接后果是 [FrameCodeRung.NothingDetected]
 * —— 阶梯自己把证据源抬出画面，那是比不放大更坏的失效。
 * 1.5×（T64 的固定档）在表里：那是实测过「投影场景有效」的那一档，现在它是路径上的
 * 一站，而不是每次绑定不问青红皂白就落下去的默认。
 */
internal val ZoomLadderRatios = listOf(1.25f, 1.5f, 1.75f, 2.0f)

/** 阶梯基线比值：不抬。退回基线就是把视场还给用户默认的宽画面。 */
internal const val ZoomBaselineRatio = 1f

/** 连续多少帧 [FrameCodeRung.CodeTooSmall] 才上一档（30 帧 ≈ 20–30fps 下 1–1.5 秒）：再短就是单帧噪声在举着视场抖 */
internal const val ZoomStepFrames = 30L

/**
 * 「抬了没用」的可判定义：已到顶档之后，再连续这么多帧仍然 [FrameCodeRung.CodeTooSmall]
 * ⇒ 缩放停止帮忙 ⇒ 退回基线并本轮不再尝试（工程实录里「持续缩放仍失败就回滚基线」那条
 * 的帧数化版本）。给两倍于单档预算，是因为顶档之上没有更多余量可试，判早一档只是把
 * 「用户再凑近一点就可能成」的那半秒抢走。
 */
internal const val ZoomRollbackFrames = 60L

/** 屏上档位要连续站住这么多帧才换（12 帧 ≈ 半秒）：提示条不许被单帧的瞄偏/噪声打得闪 */
internal const val RungSettleFrames = 12L

/** 第 [stepIndex] 档（0 = 基线）要的缩放比值。越界一律钳到表尾而不是抛：这是取证路径，不是断言路径。 */
internal fun zoomLadderRatio(stepIndex: Int): Float =
    if (stepIndex <= 0) ZoomBaselineRatio else ZoomLadderRatios[(stepIndex - 1).coerceAtMost(ZoomLadderRatios.lastIndex)]

/**
 * 阶梯与档位滞后的状态。整枚换引用、字段全不可变（[ScanDecoderHealth] 同一口径）：
 * 写它的是 ML Kit 的成功回调线程，复位它的是绑定路径的主线程。
 *
 * @param shownRung 已过滞后的**在显示**档位（UI 措辞唯一读者）
 * @param candidateRung 正在计帧的候选档位
 * @param candidateFrames 候选档位已连续站住的帧数
 * @param tooSmallStreak [FrameCodeRung.CodeTooSmall] 的连续帧数（任何其他档清它 —— 阶梯只在「有码且太小」连续成立时走）
 * @param stepIndex 当前阶梯档位（0 = 基线，[ZoomLadderRatios].size = 顶档）
 * @param rolledBack 本轮是否已回滚过（回滚 = 这一轮绑定不再试缩放，视场是用户的了）
 */
internal data class ScanAssistState(
    val shownRung: FrameCodeRung = FrameCodeRung.NothingDetected,
    val candidateRung: FrameCodeRung = FrameCodeRung.NothingDetected,
    val candidateFrames: Long = 0L,
    val tooSmallStreak: Long = 0L,
    val stepIndex: Int = 0,
    val rolledBack: Boolean = false,
)

/**
 * [advanceScanAssist] 的返回值。
 *
 * @param state 下一帧要带上的状态
 * @param zoomRatio 非 null = 调用点要把视场设到这个比值（经 [clampedZoomRatio] 钳制后下发；
 *                  这台没有缩放控制时调用点自己按 null 档处理，本函数不知道设备的存在）
 * @param zoomRollback 这一发是不是回滚（调用点用它选取证行的措辞）
 * @param shownRungChanged 屏上档位是否翻面（调用点据此推 UI + 留一行换挡痕）
 */
internal class ScanAssistOutcome(
    val state: ScanAssistState,
    val zoomRatio: Float?,
    val zoomRollback: Boolean,
    val shownRungChanged: Boolean,
)

/**
 * 每帧推进一次阶梯与滞后。判据全在这里，调用点只做三件事：喂紧凑测量、下发比值、翻 UI。
 *
 * 阶梯只认 [FrameCodeRung.CodeTooSmall] 的**连续帧**：
 * - [FrameCodeRung.CodeReadable] / [FrameCodeRung.CodeUndecodable] / [FrameCodeRung.NothingDetected]
 *   都清连击 —— 前者不需要帮，中者是焦点的事（抬视场只会把焦点问题放大得更清楚），
 *   后者是码出了画面（瞄不准时继续收视场就是滚雪球）。
 * - `zoomControlAvailable == false`（调用点用 [clampedZoomRatio] 探出来的设备事实）时
 *   **连连击都不计**：这台抬不动，计数只会攒出一发注定落空的命令。提示档位照给 ——
 *   「太小、走近一点」这句话在没有缩放控制的设备上同样是实话。
 */
internal fun advanceScanAssist(
    state: ScanAssistState,
    rung: FrameCodeRung,
    zoomControlAvailable: Boolean,
): ScanAssistOutcome {
    // 档位滞后：同一档连续站满 [RungSettleFrames] 帧的那一刻换一次屏上档（恰好相等才翻，
    // 站更久不重翻；跳来跳去的单帧永远攒不满窗口）
    val sameCandidate = rung == state.candidateRung
    val candidateRung = if (sameCandidate) state.candidateRung else rung
    val candidateFrames = if (sameCandidate) state.candidateFrames + 1L else 1L
    val shownRungChanged = candidateFrames == RungSettleFrames && candidateRung != state.shownRung
    val shownRung = if (shownRungChanged) candidateRung else state.shownRung

    var step = state.stepIndex
    var rolledBack = state.rolledBack
    var zoomRatio: Float? = null
    var zoomRollback = false
    val streak = if (rung == FrameCodeRung.CodeTooSmall) state.tooSmallStreak + 1L else 0L
    if (rung == FrameCodeRung.CodeTooSmall && !rolledBack && zoomControlAvailable) {
        if (step < ZoomLadderRatios.size) {
            if (streak >= ZoomStepFrames) {
                step += 1
                zoomRatio = zoomLadderRatio(step)
            }
        } else if (streak >= ZoomRollbackFrames) {
            // 顶档又站满两档预算还是「有码、太小」⇒ 缩放停止帮忙：一次回滚，本轮不再试
            step = 0
            rolledBack = true
            zoomRatio = ZoomBaselineRatio
            zoomRollback = true
        }
    }
    // 发过命令的这一帧连击清零（下一档的预算从这一步之后重数），没发则原样带走
    val tooSmallStreak = if (zoomRatio != null) 0L else streak
    val next = state.copy(
        shownRung = shownRung,
        candidateRung = candidateRung,
        candidateFrames = candidateFrames,
        tooSmallStreak = tooSmallStreak,
        stepIndex = step,
        rolledBack = rolledBack,
    )
    return ScanAssistOutcome(next, zoomRatio, zoomRollback, shownRungChanged)
}
