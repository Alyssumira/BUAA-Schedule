package com.buaa.schedule.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.ceil

val BluePrimary = Color(0xFF1A73E8)
val BlueOnPrimary = Color(0xFFFFFFFF)
val BluePrimaryContainer = Color(0xFFD7E3FF)
val BlueOnPrimaryContainer = Color(0xFF001A41)
val BlueSecondary = Color(0xFF565E71)
val BlueOnSecondary = Color(0xFFFFFFFF)
val BlueSecondaryContainer = Color(0xFFDAE2F9)
val BlueOnSecondaryContainer = Color(0xFF131C2B)
val BlueBackground = Color(0xFFF8F9FF)
val BlueOnBackground = Color(0xFF1A1B20)
val BlueSurface = Color(0xFFFFFFFF)
val BlueOnSurface = Color(0xFF1A1B20)
val BlueSurfaceVariant = Color(0xFFE1E2EC)
val BlueOnSurfaceVariant = Color(0xFF44474F)
val BlueOutline = Color(0xFF74777F)

val DarkBluePrimary = Color(0xFFAAC7FF)
val DarkBlueOnPrimary = Color(0xFF002F66)
val DarkBluePrimaryContainer = Color(0xFF004494)
val DarkBlueOnPrimaryContainer = Color(0xFFD7E3FF)
val DarkBlueSecondary = Color(0xFFBEC6DC)
val DarkBlueOnSecondary = Color(0xFF283141)
val DarkBlueSecondaryContainer = Color(0xFF3E4759)
val DarkBlueOnSecondaryContainer = Color(0xFFDAE2F9)
val DarkBlueBackground = Color(0xFF111318)
val DarkBlueOnBackground = Color(0xFFE2E2E9)
val DarkBlueSurface = Color(0xFF111318)
val DarkBlueOnSurface = Color(0xFFE2E2E9)
val DarkBlueSurfaceVariant = Color(0xFF44474F)
val DarkBlueOnSurfaceVariant = Color(0xFFC5C6D0)
val DarkBlueOutline = Color(0xFF8F9099)

/**
 * Tertiary 族：与 214° 蓝主色相邻的青绿，用来替换 M3 baseline 的 340° 玫紫。
 * baseline 那支在蓝调界面里会被读成"从别的 App 串过来的颜色"。
 */
val TealTertiary = Color(0xFF0F6B73)
val TealOnTertiary = Color(0xFFFFFFFF)
val TealContainer = Color(0xFFA7EAF4)
val TealOnContainer = Color(0xFF002024)
val DarkTealTertiary = Color(0xFF52C7D3)
val DarkTealOnTertiary = Color(0xFF00353C)
val DarkTealContainer = Color(0xFF004F58)
val DarkTealOnContainer = Color(0xFFA7EAF4)

/**
 * Error 族：浅色 #BA1A1A / 深色 #FFB4AB。
 * 周视图的时间指示线（`NowLine`）直接用它——替代此前写死的 `Color.Red@0.7`，
 * 那一支在深色背景上只有 2.71:1（非文本要求 3:1），比浅色主题下反而更难看清。
 */
val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOnErrorContainer = Color(0xFF410002)
val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF601410)
val DarkErrorContainer = Color(0xFF8C1D18)
val DarkOnErrorContainer = Color(0xFFFFE2DE)

val CourseColors = listOf(
    Color(0xFF5B8DEF),
    Color(0xFFF2994A),
    Color(0xFF27AE60),
    Color(0xFFEB5757),
    Color(0xFF9B51E0),
    Color(0xFF00B8D4),
    Color(0xFFF2C94C),
    // 这里原本是 #219653：与 index 2 的 #27AE60 色相只差 0.3°、CIEDE2000 = 7.5，
    // 在窄屏 43dp 单列上就是"两门课一个色"——而区分课程是这张表唯一的职责。
    // 换成玫粉后与其余 7 色的最小 ΔE 抬到 19.7，黑字对比度 5.37:1，取色策略与其它色一致。
    // colorIndex 是持久化字段，改的是外观不是分配。
    Color(0xFFE75FA3),
)

/** 课程卡片颜色：自定义色优先，否则按 colorIndex 从调色板取（floorMod 容忍负索引） */
fun courseColor(course: com.buaa.schedule.domain.model.Course): Color =
    course.customColorArgb
        // 防御性校验：只接受 32 位 ARGB，越界值退回调色板，
        // 避免历史/外部来源的非法数值渲染出随机颜色
        ?.takeIf { it in 0L..0xFFFFFFFFL }
        ?.let { Color(it) }
        ?: CourseColors[Math.floorMod(course.colorIndex, CourseColors.size)]

/**
 * 手写液态玻璃表面（底栏 / FAB）的栏体基色。
 *
 * 这些表面不在 ColorScheme 链上（Kyant 的 `drawBackdrop` 只给 `onDrawSurface` 回调），
 * 只能自带"中性玻璃底板"色。此前 `0xFFFAFAFA`/`0xFF121212` 在两个组件文件里各写一份。
 */
val ChromeSurfaceLight = Color(0xFFFAFAFA)
val ChromeSurfaceDark = Color(0xFF121212)

/**
 * 默认场景背景（无壁纸时）：北航蓝三段渐变 + 两团光斑。
 * 玻璃折射采样的就是这几个颜色，改它们等于改所有玻璃的观感底色。
 */
val SceneDarkBase = listOf(Color(0xFF0D1526), Color(0xFF101A30), Color(0xFF131229))
val SceneLightBase = listOf(Color(0xFFEAF1FD), Color(0xFFDCE9FB), Color(0xFFE6E3F6))
val SceneDarkSpotA = Color(0xFF2E6BD6)
val SceneDarkSpotB = Color(0xFF7A4FD0)
val SceneLightSpotA = Color(0xFF9EC3F8)
val SceneLightSpotB = Color(0xFFC9B8F0)

/**
 * 背景亮度（相对亮度，0..1），用于自动选择黑/白前景
 */
fun Color.readableLuminance(): Float =
    0.2126f * srgbToLinear(red) + 0.7152f * srgbToLinear(green) + 0.0722f * srgbToLinear(blue)

/** [srgbToLinear] 的分段点在编码侧：sRGB 规范里线性段与幂段交接的那一档通道值 */
private const val SRGB_ENCODED_KNEE = 0.03928f

/** 同一分段点在线性侧的位置，即 [neutralChannelOf] 的分段条件（两者由同一条直线相接） */
private const val SRGB_LINEAR_KNEE = SRGB_ENCODED_KNEE / 12.92f

/**
 * sRGB 编码通道值 → 线性光。[readableLuminance] 与 [compositeLuma] **共用这一支**，
 * 两边各自抄一份近似就是这一族 bug 的成因（见 [compositeLuma] 的口径说明）。
 */
private fun srgbToLinear(v: Float): Float =
    if (v <= SRGB_ENCODED_KNEE) v / 12.92f else Math.pow(((v + 0.055) / 1.055).toDouble(), 2.4).toFloat()

/**
 * 亮度 → 与它等亮度的**中性灰**的 sRGB 编码通道值：[srgbToLinear] 在 r=g=b 处的解析反函数
 * （亮度三通道权重 0.2126+0.7152+0.0722 之和恰为 1，所以中性灰上"亮度 ↔ 编码通道"一一对应）。
 *
 * internal 的理由与 [compositeLuma] 相同：[DesignTokens.glassAlphaFloor] 要在同一个空间里
 * 反解 alpha，两边必须折得一模一样，否则"下限"与"复合"就不同源了
 * （钉在 `DesignSystemTest.glassAlphaFloorInvertsCompositeLumaOnEveryPlateSceneTextCell`）。
 * 定义域外（负亮度、NaN）走上方的线性段照原样送出，不夹取也不抛。
 */
internal fun neutralChannelOf(luma: Float): Float =
    if (luma <= SRGB_LINEAR_KNEE) {
        12.92f * luma
    } else {
        1.055f * Math.pow(luma.toDouble(), 1.0 / 2.4).toFloat() - 0.055f
    }

/**
 * 课程卡上大面积的文字用近黑而不是纯黑（护眼、也不显得"糊"），
 * 反过来深色底用纯白。两档都是**固定的**，不能是 `onSurface`：
 * 深色主题下 onSurface 本身就是近白，于是"亮底用 onSurface、暗底用 White"两支都成了浅色。
 *
 * internal 而不是 private：[liquid.bottomBarInk] 那类"品牌墨读不清才退黑白"的解法要复用
 * 这两支候选墨（口径同当初把 [contrastRatio]、[compositeLuma] 提到 internal——
 * 设计系统外面抄一份裸色，下一处改动就只会落在其中一份上）。数值一个字没改。
 */
internal val ContentDark = Color(0xFF1A1B20)
internal val ContentLight = Color.White

/**
 * 根据背景亮度自动选择可读前景色（黑或白），
 * 保证课程色块（如浅黄）上的文字始终满足对比度。
 */
fun contentOn(background: Color): Color = contentOnLuma(background.readableLuminance())

/**
 * [contentOn] 的亮度版本：手头的底色是"半透明色叠在别的东西上"合成出来的，
 * 只有亮度没有 Color（周视图课程卡：课程色 tint 叠在壁纸上），
 * 判断口径仍然一样——所以拆出来复用，不要各写一套阈值。
 *
 * 取的是**实际对比度更高**的一侧，而不是按固定亮度切：近黑（亮度 0.011）与纯白
 * 两候选等对比度的交点在亮度 ≈0.203，而旧口径切在 0.45，于是 0.203~0.45 这一整段
 * 全被判给了白字——默认调色板 8 个课程色里有 7 个落在里面，
 * 例如 #F2994A 上白字只有 2.2:1，同一块底用近黑是 7.7:1。
 */
fun contentOnLuma(luma: Float): Color =
    if (contrastRatio(luma, ContentLight) >= contrastRatio(luma, ContentDark)) ContentLight else ContentDark

/**
 * 课程色当玻璃底板时，该担心壁纸的哪一档亮度。
 *
 * 判据与 [legibilityAlphaFloor] 一致：这块底板会配浅色文字（暗板）就怕亮斑，
 * 配深色文字（亮板）就怕暗斑。浅色文字怕亮斑这一支不能看平均值——
 * 一张亮斑从半透明课程色底下透上来，正好把白字糊掉。
 */
fun coursePlateSceneLuma(tint: Color, darkTheme: Boolean): Float =
    worstGlassSceneLuma(darkTheme, plateIsDark = contentOnLuma(tint.readableLuminance()) == Color.White)

/** 一块底板叠在场景上、并选好前景色之后的结果。三个字段要一起用，拆开就没有意义。 */
data class TintPlate(
    val tint: Color,
    val alpha: Float,
    val foreground: Color,
    /** 次级文字（节次/地点这类小字号）：尽可能淡，但**不低于** [DesignTokens.WCAG_AA_RATIO] */
    val secondaryForeground: Color,
)

/**
 * 把「[tint] 以 [alpha] 叠在亮度为 [sceneLuma] 的场景上」这块底板压到可读。
 *
 * 两步，按"最不打扰用户的资源"排序，前一步够用时不会走下一步：
 * 1. **选墨与压实同解**：对 [ContentLight] / [ContentDark] 两支候选墨各解一次"这支墨读到 AA
 *    所需的最小 alpha"（[alphaNeededByInk]，口径与 [DesignTokens.glassAlphaFloor] 一致——
 *    和 [GlassSurface]、分段控件、底栏共用同一个解），取**所需 alpha 更小**的那一支，
 *    等档时按 [contentOnLuma] 对初始板的偏好。
 *    两步不能拆开做：先定墨再抬 alpha，编码通道那一维的插值在暗部掉得快，
 *    抬完的板会越过 [contentOnLuma] 的黑白交点，于是"墨"与"板"错配
 *    （灰 12/255 以 0.47 叠在亮度 0.83 上：近黑那支无解、alpha 被顶到 1.0，
 *    而白墨只要 0.52 就恰好 4.5:1）。
 * 2. 连不透明都读不出（tint 本身落在"黑白都读不出"的中间亮度带，实测 0.183~0.225）
 *    才把 tint 向**最终那支墨**的对侧压暗/压亮。这一步会改变课程色的观感，所以只在
 *    "否则文字必然糊掉"的组合上发生。
 *
 * [sceneLuma] 传**最不利**的场景亮度（见 [worstGlassSceneLuma]），传平均值会低估风险；
 * NaN 表示场景未知，此时按不透明底板处理。
 *
 * @see legibilityAlphaFloor 前景色**已经定了**（如主题 `onSurface`）、只要求底板最小 alpha 时用那个
 * @see DesignTokens.glassAlphaFloor 两个入口共同的数值口径
 */
fun legibleTintPlate(tint: Color, alpha: Float, sceneLuma: Float): TintPlate {
    val scene = if (sceneLuma.isNaN()) tint.readableLuminance() else sceneLuma
    val tintLuma = tint.readableLuminance()
    val requested = alpha.coerceIn(0f, 1f)

    // 偏好那支排在前：minBy 取第一个最小值，等档时就是 contentOnLuma 对初始板的答案
    val preferred = contentOnLuma(compositeLuma(tintLuma, scene, requested))
    val foreground = listOf(preferred, if (preferred == ContentLight) ContentDark else ContentLight)
        .minByOrNull { alphaNeededByInk(tintLuma, scene, requested, it) }!!
    val fgLuma = foreground.readableLuminance()

    var a = alphaForInk(tintLuma, scene, requested, fgLuma)
    var luma = compositeLuma(tintLuma, scene, a)
    var plate = tint
    if (!inkReadsPlate(tintLuma, scene, a, fgLuma)) {
        val extreme = if (fgLuma > luma) Color.Black else Color.White
        var guard = 0
        while (!inkReadsPlate(plate.readableLuminance(), scene, a, fgLuma) && guard < MAX_TINT_PUSH_STEPS) {
            guard++
            plate = lerp(plate, extreme, TINT_PUSH_STEP)
        }
        luma = compositeLuma(plate.readableLuminance(), scene, a)
    }
    return TintPlate(
        tint = plate,
        alpha = a,
        foreground = foreground,
        secondaryForeground = dimForeground(foreground, luma),
    )
}

/**
 * 亮度 [inkLuma] 的那支墨，读得清「[tintLuma] 以 [alpha] 叠在 [sceneLuma] 上的板」吗。
 *
 * [SOLVE_NOISE_RATIO] 那一档不是宽容差，是**同一次解的往返误差**：[DesignTokens.glassAlphaFloor]
 * 的解恰好落在 AA 线上，喂回 [compositeLuma] 再量，浮点末位能把 4.5 报成 4.499999x，
 * 而严格小于会因此多走一步 tint push（一步就是 30% 的色差，白拿的观感）。
 */
private fun inkReadsPlate(tintLuma: Float, sceneLuma: Float, alpha: Float, inkLuma: Float): Boolean =
    contrastRatio(compositeLuma(tintLuma, sceneLuma, alpha), inkLuma) >= DesignTokens.WCAG_AA_RATIO - SOLVE_NOISE_RATIO

/** 这支墨要读清这块板，alpha 得到哪一档：[requested] 够用就原样送出，不够才压实，顶到不透明为止。 */
private fun alphaForInk(tintLuma: Float, sceneLuma: Float, requested: Float, inkLuma: Float): Float =
    if (inkReadsPlate(tintLuma, sceneLuma, requested, inkLuma)) {
        requested
    } else {
        maxOf(requested, DesignTokens.glassAlphaFloor(tintLuma, sceneLuma, inkLuma)).coerceAtMost(1f)
    }

/**
 * 选墨那一步用的**价**：候选墨 [ink] 要把「[tintLuma] 以 alpha 叠在 [sceneLuma] 上的板」读到
 * [DesignTokens.WCAG_AA_RATIO]，至少得付多少 alpha（[alphaForInk]）。
 * **任何 alpha 都读不清的那一支一律记 [1f]**——它付的是全损（通透没了还读不清），
 * 所以"能读清的那一支"永远赢它；两支都记 1f 就是 [legibleTintPlate] 第 2 步（压 tint）的入口。
 *
 * internal 的理由同 [compositeLuma]：守卫测试要拿同一个量断言"不许选到更贵的那一支"
 * （`DesignSystemTest.legibleTintPlateNeverPicksTheInkThatCostsMoreAlpha`），
 * 在测试里抄第二份解就是下一轮维度搬家的漏改点。
 *
 * ## 一条已知等价残余（只写清楚，不改行为）
 *
 * "任何 alpha 都读不清"记的 [1f]，与"恰好压实到全不透明才读得清"那支交出的也是 [1f]
 * ——[alphaForInk] 末尾那个 `coerceAtMost(1f)` 让这两种语义在**价这一维上不可分辨**。
 * 于是平局时 [legibleTintPlate] 第 1 步（`minByOrNull` 保留排在前面的 [contentOnLuma] 偏好那支）
 * 可能挑中不可达的那一支，而另一支本可以只靠压实就达标。
 *
 * 后果是**观感不是可读性**：选中不可达那支后，第 2 步的 `inkReadsPlate` 照样判不过，
 * 于是走压 tint（一步 [TINT_PUSH_STEP] = 30% 课程色色差）——文字仍读得清，多付的是课程色。
 * 上面那条守卫只数了"同档格存在"（`ties > 0`，比的是两支价的差），**没有**数过平局里是否
 * 混着"一支可达、一支不可达"，所以这条残余目前是没人踩、也没人证的：要钉它得先给价加一位
 * 可达性，再按那一维重跑一次网格。
 *
 * 真要分出这两者，改的是**返回类型**（价 + 一个"可达"位），不是给不可达那支塞一个比 [1f]
 * 更大的数：那个数没有单位（alpha 顶多 1.0），还会把"1f = 全损"这个锚点弄脏。
 */
internal fun alphaNeededByInk(tintLuma: Float, sceneLuma: Float, requested: Float, ink: Color): Float {
    val inkLuma = ink.readableLuminance()
    val priced = alphaForInk(tintLuma, sceneLuma, requested, inkLuma)
    return if (inkReadsPlate(tintLuma, sceneLuma, priced, inkLuma)) priced else 1f
}

/**
 * 次级文字允许淡到哪一档。
 *
 * 写死 alpha（此前周视图是 0.78）在 12sp 上必然掉出 AA：叠上去之后字与板的亮度差就是
 * 对比度本身，而 sRGB 编码通道那一维的插值在暗部尤其"不划算"（线性段的斜率是 12.92），
 * 于是"淡一点"直接变成"看不清一点"。这里反解出**仍能达标的最小 alpha**——
 * 底板本身够极端时字就淡得下来，底板勉强达标时字就基本不虚化，层次保留、下限钉死。
 *
 * 这条反解**不自己再解一遍**：把这支墨看成叠在板上的 tint、把板看成场景与对照物，
 * [DesignTokens.glassAlphaFloor] 的同一条解就直接可用（三个参数里两个都是板亮度，
 * 因为这里要比的就是"字 vs 它自己的板"），只有硬底换成本函数的 [MIN_SUBTLE_ALPHA]。
 * 混哪一维由 [compositeLuma] 说一次就够；抄第二份出去就是下一轮维度搬家的漏改点
 * （`DesignSystemTest.theSubtleInkReusesTheSharedFloorInsteadOfResolvingItAgain` 按源码形状钉它）。
 *
 * 墨与板同亮度那条退化格走的是 [DesignTokens.glassAlphaFloor] 的绝对硬底（这里即
 * [MIN_SUBTLE_ALPHA]）而不是旧的内联"原样返回"：那种板与字对比度恒为 1，
 * 任何 alpha 都读不出，两支行为在可读性上等价。
 */
private fun dimForeground(foreground: Color, plateLuma: Float): Color {
    val solved = DesignTokens.glassAlphaFloor(
        surfaceLuma = foreground.readableLuminance(),
        sceneLuma = plateLuma,
        textLuma = plateLuma,
        hardMin = MIN_SUBTLE_ALPHA,
    )
    // 向上取整到 1/255：Color 每个通道按 8 bit 存，alpha 会被截到最近的 1/255，
    // 向下截 0.5 档就把"刚好 4.5:1"变成实测 4.47:1。
    return foreground.copy(alpha = ceil(solved * 255f) / 255f)
}

/**
 * 底板 tint 与场景叠成一块板之后的亮度。**混合发生在 sRGB 编码通道那一维**，不是线性亮度那一维：
 * 平台画这块板做的是 `drawRect(color.copy(alpha = …))`，Skia 在 sRGB 表面上逐通道插值 8 bit
 * 编码值，再把结果写回表面。亮度只有一维，所以这里分三步走同一条路：
 * 把两个亮度各折成等亮度的中性灰编码通道值（[neutralChannelOf]）、按 alpha 插值、
 * 最后交给与 [readableLuminance] **同一支**通道变换（[srgbToLinear]）解回亮度。
 *
 * 旧口径 `tint * a + scene * (1 - a)` 混的是线性相对亮度，而 sRGB 的解码曲线是**凸函数**，
 * 凸函数上的线性插值恒 ≥ 先插值再解码（Jensen），于是它**永远把板算得比真机上画出来的更亮**：
 * 深色档 `#14161C` 以 0.60 叠在纯白壁纸上，旧口径给 0.4049，真机中位像素折出来是 0.1717
 * ——差 2.36 倍，后果是 [contentOnLuma] 的 0.203 黑白交点被系统性判错。
 * 旧假设"液态玻璃不是 source-over，是折射/高光层"已被实测校准表证伪，
 * 见 `GlassPlateDeviceCalibrationTest`；口径正确的另一份实现是
 * `com.buaa.schedule.widget.WidgetAppearance.autoShouldUseDarkText`（逐通道插值后才量亮度）。
 *
 * 精度边界（**亮度代理**，不是第三种混合模型）：这里只带亮度、不带彩度，等于把一块色当成
 * 与它等亮度的中性灰。tint 与场景**同为中性灰、或两者等亮度**时代理是**精确**的
 * （灰的通道值与亮度互为解析反函数，权重之和为 1）；tint 有彩度时开始偏，偏差随彩度增大，
 * 在这门色板上量到的最大偏差 0.0404（青 #00B8D4 以 0.5 叠在纯白上），
 * 且落不进任何一次选墨翻转——`CompositeLumaChannelCrossCheckTest` 逐条钉这两点。
 *
 * 任何一侧 NaN 都原样向外传 NaN（[neutralChannelOf] 与 [srgbToLinear] 的幂段都不夹取、不抛）：
 * 调用方按"场景未知"处理，这里不许把它伪装成 0。
 *
 * internal：[GlassSurface] 的语义卡要按"这块 tint 叠上去之后实际多亮"挑文字色，
 * 必须复用同一条合成式，不能另写一份。反解这条式子的 [DesignTokens.glassAlphaFloor]
 * 走的是同一个空间，两边必须一起搬（互逆性钉在 `DesignSystemTest` 的 6×7×6 网格守卫上）。
 */
internal fun compositeLuma(tintLuma: Float, sceneLuma: Float, alpha: Float): Float =
    srgbToLinear(
        alpha * neutralChannelOf(tintLuma) + (1f - alpha) * neutralChannelOf(sceneLuma),
    )

private fun contrastRatio(luma: Float, other: Color): Float =
    contrastRatio(luma, other.readableLuminance())

/**
 * 两块亮度之间的 WCAG 对比度。
 *
 * internal 而不是 private：[GlassSurface] 的语义卡要按"这块板实际画出来有多亮/多暗"挑文字色，
 * 用的必须是同一条公式，不能在设计系统里再抄第二份。阈值口径见 [DesignTokens.WCAG_AA_RATIO]。
 */
internal fun contrastRatio(a: Float, b: Float): Float {
    val hi = maxOf(a, b)
    val lo = minOf(a, b)
    return (hi + 0.05f) / (lo + 0.05f)
}

private const val MAX_TINT_PUSH_STEPS = 10
private const val TINT_PUSH_STEP = 0.3f

/**
 * 判"这支墨读不清这块板"时的浮点末位带宽。
 *
 * [DesignTokens.glassAlphaFloor] 的解**恰好**落在 [DesignTokens.WCAG_AA_RATIO] 那条线上，
 * 把它喂回 [compositeLuma] 再量一次对比度是**另一次**浮点往返，末位能把 4.5 报成 4.499999x。
 * 按严格小于判，"压实到刚好达标"的那些格子就会多走一步 tint push——一步就是 30% 的色差，
 * 白拿的观感。1e-3 比这个往返噪声大两个量级，又比测试那条 0.02（还要盖 8 bit 量化）紧 20 倍：
 * 真读不清的那批格子（中间亮度带）差的是一整档，一分也溜不过去。
 */
private const val SOLVE_NOISE_RATIO = 0.001f

/** 次级文字的地板：再低就不是"弱化层级"而是"看不见"了 */
private const val MIN_SUBTLE_ALPHA = 0.72f
