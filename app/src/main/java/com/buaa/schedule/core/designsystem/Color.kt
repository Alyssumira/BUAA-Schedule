package com.buaa.schedule.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.abs
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

/** 背景亮度（相对亮度，0..1），用于自动选择黑/白前景 */
fun Color.readableLuminance(): Float {
    fun channel(v: Float): Float = if (v <= 0.03928f) v / 12.92f else Math.pow(((v + 0.055) / 1.055).toDouble(), 2.4).toFloat()
    return 0.2126f * channel(red) + 0.7152f * channel(green) + 0.0722f * channel(blue)
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
 * 三步，按"最不打扰用户的资源"排序，前一步够用时不会走下一步：
 * 1. [contentOnLuma] 先选对黑/白；
 * 2. 仍不够 [DesignTokens.WCAG_AA_RATIO] 就抬高 alpha（玻璃变实），口径与
 *    [DesignTokens.glassAlphaFloor] 一致——和 [GlassSurface]、分段控件、底栏共用同一个解。
 * 3. alpha 顶到不透明仍不够（tint 本身落在"黑白都读不出"的中间亮度带，实测 0.183~0.225）
 *    才把 tint 向文字对侧压暗/压亮。这一步会改变课程色的观感，所以只在
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
    var a = alpha.coerceIn(0f, 1f)
    var luma = compositeLuma(tintLuma, scene, a)
    val foreground = contentOnLuma(luma)
    val fgLuma = foreground.readableLuminance()

    if (contrastRatio(luma, fgLuma) < DesignTokens.WCAG_AA_RATIO) {
        a = maxOf(a, DesignTokens.glassAlphaFloor(tintLuma, scene, fgLuma)).coerceAtMost(1f)
        luma = compositeLuma(tintLuma, scene, a)
    }
    var plate = tint
    if (contrastRatio(luma, fgLuma) < DesignTokens.WCAG_AA_RATIO) {
        val extreme = if (fgLuma > luma) Color.Black else Color.White
        var guard = 0
        while (contrastRatio(compositeLuma(plate.readableLuminance(), scene, a), fgLuma) <
            DesignTokens.WCAG_AA_RATIO && guard < MAX_TINT_PUSH_STEPS
        ) {
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
 * 次级文字允许淡到哪一档。
 *
 * 写死 alpha（此前周视图是 0.78）在 12sp 上必然掉出 AA：叠色是线性的，
 * 于是"淡一点"直接变成"看不清一点"。这里反解出**仍能达标的最小 alpha**——
 * 底板本身够极端时字就淡得下来，底板勉强达标时字就基本不虚化，层次保留、下限钉死。
 *
 * 合成亮度 `Lb = text*a + plate*(1-a)`，浅字要 `Lb ≥ limit`、深字要 `Lb ≤ limit`，
 * 两式除以 (text - plate) 后归并成同一个解（深字时分子分母同负，不等号翻转后方向一致）。
 */
private fun dimForeground(foreground: Color, plateLuma: Float): Color {
    val textLuma = foreground.readableLuminance()
    val span = textLuma - plateLuma
    if (abs(span) < 0.001f) return foreground
    val ratio = DesignTokens.WCAG_AA_RATIO
    val limit = if (span > 0f) {
        (plateLuma + 0.05f) * ratio - 0.05f
    } else {
        (plateLuma + 0.05f) / ratio - 0.05f
    }
    val solved = ((limit - plateLuma) / span).coerceIn(MIN_SUBTLE_ALPHA, 1f)
    // 向上取整到 1/255：Color 每个通道按 8 bit 存，alpha 会被截到最近的 1/255，
    // 向下截 0.5 档就把"刚好 4.5:1"变成实测 4.47:1。
    return foreground.copy(alpha = ceil(solved * 255f) / 255f)
}

/**
 * 底板与场景的线性合成亮度，口径与 [DesignTokens.glassAlphaFloor] 的反解完全一致。
 *
 * internal：[GlassSurface] 的语义卡要按"这块 tint 叠上去之后实际多亮"挑文字色，
 * 必须复用同一条合成式，不能另写一份。
 */
internal fun compositeLuma(tintLuma: Float, sceneLuma: Float, alpha: Float): Float =
    tintLuma * alpha + sceneLuma * (1f - alpha)

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

/** 次级文字的地板：再低就不是"弱化层级"而是"看不见"了 */
private const val MIN_SUBTLE_ALPHA = 0.72f
